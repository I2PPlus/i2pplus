/* PeerConnectionOut - Keeps a queue of outgoing messages and delivers them.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Manages outgoing BitTorrent protocol messages to a peer.<br>
 * This class maintains a queue of outgoing messages and handles bandwidth limiting, message
 * prioritization, and reliable delivery to the peer.
 * @since 0.1
 */
class PeerConnectionOut implements Runnable {
    private final Log _log =
            I2PAppContext.getGlobalContext().logManager().getLog(PeerConnectionOut.class);
    private final Peer peer;
    private final DataOutputStream dout;
    private Thread thread;
    private boolean quit;
    // Contains Messages.
    // Bounded queue to prevent unbounded memory growth under slow network conditions
    private static final int MAX_QUEUE_SIZE = 1000;
    /** Max consecutive PIECE messages the priority scan walks before giving up. */
    private static final int MAX_PRIORITY_SCAN = 16;
    private final BlockingQueue<Message> sendQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);

    // ---- PIECE prefetch --------------------------------------------------
    // Disk reads for queued PIECE messages run on this shared elastic pool
    // instead of inline on the sender thread, so a slow (cold) read no longer
    // stalls the bytes already ready to go out behind it. The pool spawns a
    // thread per concurrent burst up to MAX_PREFETCH_THREADS and lets idle
    // threads die, so cost at rest is zero. When all threads are busy or the
    // per-connection depth cap is hit, new pieces simply stay lazy and fall
    // back to the pre-existing inline load in Message.sendMessage().
    /** Max threads across all connections; Storage reads lock per-file, so more buys little. */
    private static final int MAX_PREFETCH_THREADS = 4;
    /** Idle prefetch threads exit after this long. */
    private static final long PREFETCH_THREAD_IDLE_MS = 60 * 1000;
    /** Max uncompleted prefetches per connection; bounds pinned buffers to depth x 16KB. */
    static volatile int MAX_PREFETCH_INFLIGHT = 2;
    /** Loads slower than this are logged; sustained counts confirm cold-read stalls. */
    private static final long SLOW_LOAD_WARN_MS = 1000;
    private static final AtomicInteger _prefetchThreadId = new AtomicInteger();
    private static final ThreadPoolExecutor _prefetchExec =
            new ThreadPoolExecutor(
                    0,
                    MAX_PREFETCH_THREADS,
                    PREFETCH_THREAD_IDLE_MS,
                    TimeUnit.MILLISECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t =
                                new I2PAppThread(
                                        r, "SnarkPrefetch." + _prefetchThreadId.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    static {
        _prefetchExec.allowCoreThreadTimeOut(true);
    }

    // Load timing stats: cheap instrumentation to verify cold-read stall share.
    private static final AtomicLong _loadCount = new AtomicLong();
    private static final AtomicLong _loadTotalMs = new AtomicLong();
    private static volatile long _maxLoadMs;

    /**
     * Record a deferred-load duration. Called from both the prefetch task and the inline fallback
     * in {@link Message#sendMessage(DataOutputStream)}.
     *
     * @param ms load duration in milliseconds
     * @since 0.9.71+
     */
    static void recordLoad(long ms) {
        if (ms <= 0) return;
        _loadCount.incrementAndGet();
        _loadTotalMs.addAndGet(ms);
        if (ms > _maxLoadMs) _maxLoadMs = ms;
        if (ms >= SLOW_LOAD_WARN_MS) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(PeerConnectionOut.class);
            log.logAlways(
                    Log.WARN,
                    "Slow piece load: "
                            + ms
                            + "ms (loads="
                            + _loadCount.get()
                            + ", total="
                            + _loadTotalMs.get()
                            + "ms, max="
                            + _maxLoadMs
                            + "ms)");
        }
    }

    private static final AtomicLong __id = new AtomicLong();
    private final long _id;
    /** Last send time. */
    long lastSent;
    /** Uncompleted prefetch tasks for this connection; bounds memory to depth x block size. */
    private final AtomicInteger _prefetchInFlight = new AtomicInteger();

    /**
     * Creates a new outgoing connection handler.
     *
     * @param peer the peer
     * @param dout the output stream to send messages on
     */
    public PeerConnectionOut(Peer peer, DataOutputStream dout) {
        this.peer = peer;
        this.dout = dout;
        _id = __id.incrementAndGet();
        lastSent = System.currentTimeMillis();
    }

    /**
     * Start the sender thread.
     */
    public void startup() {
        thread = new I2PAppThread(this, "SnarkSend." + _id);
        thread.start();
    }

    /**
     * Whether a PIECE message is still queued, i.e. we are actively uploading to the peer.
     *
     * @return true if any PIECE message is pending
     * @since 0.9.71+
     */
    boolean hasPendingPiece() {
        for (Message m : sendQueue) {
            if (m.type == Message.PIECE) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first queued PIECE message in send order.
     *
     * @return the oldest pending PIECE message, or null if none queued
     * @since 0.9.71+
     */
    Message headPiece() {
        synchronized (sendQueue) {
            for (Message m : sendQueue) {
                if (m.type == Message.PIECE) return m;
            }
        }
        return null;
    }

    /**
     * Current number of uncompleted prefetch loads for this connection.
     *
     * @return in-flight prefetch count, capped by MAX_PREFETCH_INFLIGHT
     * @since 0.9.71+
     */
    int prefetchInFlight() {
        return _prefetchInFlight.get();
    }

    /**
     * Pull messages from the send queue and deliver to the peer.
     */
    @Override
    public void run() {
        try {
            boolean shouldThrottleRequests = false;
            while (!quit && peer.isConnected()) {
                Message m = null;
                PeerState state = null;
                boolean shouldFlush;
                synchronized (sendQueue) {
                    shouldFlush = !quit && peer.isConnected() && sendQueue.isEmpty();
                }

                // Make sure everything will reach the other side.
                // flush while not holding lock, could take a long time
                if (shouldFlush) {
                    dout.flush();
                }

                synchronized (sendQueue) {
                    while (!quit
                            && peer.isConnected()
                            && (shouldThrottleRequests || sendQueue.isEmpty())) {
                        try {
                            sendQueue.wait(shouldThrottleRequests ? 5000 : 60 * 1000);
                        } // Wait till more data arrives.
                        catch (InterruptedException ie) { /* ignored */ } // ignored
                        shouldThrottleRequests = false;
                    }

                    state = peer.state;
                    if (!quit && state != null && peer.isConnected()) {
                        // Piece messages are big. So if there are other (control) messages make
                        // sure they are sent first.
                        // Also remove request messages from the queue if we are currently being
                        // choked to prevent them from
                        // being sent even if we get unchoked a little later (since we will resend
                        // them anyway in that case).
                        // And remove piece messages if we are choking.
                        // Bound the scan so a long run of piece messages
                        // doesn't cost O(n) per message sent
                        int skipped = 0;
                        Iterator<Message> it = sendQueue.iterator();
                        while (m == null && it.hasNext() && skipped < MAX_PRIORITY_SCAN) {
                            Message nm = it.next();
                            if (nm.type == Message.PIECE) {
                                // BEP 6: keep serving allowed fast pieces when we choke
                                if (state.choking && !state.isAllowedFast(nm.piece)) {
                                    it.remove();
                                    nm.discard();
                                    if (peer.supportsFast()) {
                                        Message r =
                                                new Message(
                                                        Message.REJECT,
                                                        nm.piece,
                                                        nm.begin,
                                                        nm.length);
                                        if (_log.shouldDebug()) {
                                            _log.debug("Sending [" + peer + "]: " + r);
                                        }
                                        r.sendMessage(dout);
                                    }
                                }
                                nm = null;
                                skipped++;
                            } else if (nm.type == Message.REQUEST) {
                                if (state.choked) {
                                    it.remove();
                                    nm = null;
                                } else if (shouldThrottleRequests) {
                                    // previous request in queue throttled, skip this one too
                                    if (_log.shouldWarn()) {
                                        _log.warn("Additional throttle: " + nm + " to " + peer);
                                    }
                                    nm = null;
                                } else if (!peer.shouldRequest(nm.length)) {
                                    // request throttle, skip this and all others in this loop
                                    if (_log.shouldWarn()) {
                                        _log.warn("Throttle: " + nm + " to " + peer);
                                    }
                                    shouldThrottleRequests = true;
                                    nm = null;
                                }
                            }
                            if (nm != null) {
                                m = nm;
                                it.remove();
                            }
                        }

                        if (m == null) {
                            m = sendQueue.peek();
                            if (m != null && m.type == Message.PIECE) {
                                // bandwidth limiting
                                // Pieces are the last thing in the queue to be sent so we can
                                // simply wait right here and then loop
                                if (!peer.shouldSend(Math.min(m.length, PeerState.PARTSIZE))) {
                                    if (_log.shouldWarn()) {
                                        _log.warn("Throttle: " + m + " to " + peer);
                                    }
                                    try {
                                        sendQueue.wait(5000);
                                    } catch (InterruptedException ie) { /* ignored */ }
                                    continue;
                                }
                            } else if (m != null && m.type == Message.REQUEST && shouldThrottleRequests) {
                                continue;
                            }
                            m = sendQueue.poll();
                        }
                    }
                }

                if (m != null) {
                    if (_log.shouldDebug()) {
                        _log.debug("Sending [" + peer + "]: " + m);
                    }

                    lastSent = System.currentTimeMillis();

                    if (m.type == Message.CHOKE) {
                        removeMessage(Message.PIECE);
                    }

                    int remainder = 0;
                    if (m.type == Message.PIECE && m.len > PeerState.PARTSIZE) {
                        remainder = m.len - PeerState.PARTSIZE;
                    }

                    if (m.sendMessage(dout) && remainder > 0) {
                        peer.uploaded(remainder);
                    }
                }
            }
        } catch (IOException ioe) {
            // Ignore, probably other side closed connection.
            if (_log.shouldInfo()) {
                _log.info("Error sending to [" + peer + "] \n* " + ioe.getMessage());
            }
        } catch (OutOfMemoryError oome) {
            _log.error("Error sending to [" + peer + "]", oome);
            throw oome;
        } catch (Throwable t) {
            _log.error("Error sending to [" + peer + "]", t);
        } finally {
            quit = true;
            peer.disconnect();
        }
    }

    /**
     * Disconnect and close the output stream.
     */
    public void disconnect() {
        synchronized (sendQueue) {
            quit = true;
            if (thread != null) {
                thread.interrupt();
            }
            // release any prefetched piece buffers before dropping the queue
            for (Iterator<Message> it = sendQueue.iterator(); it.hasNext(); ) {
                Message m = it.next();
                if (m.type == Message.PIECE) {
                    m.discard();
                }
            }
            sendQueue.clear();
            sendQueue.notifyAll();
        }

        if (dout != null) {
            try {
                dout.close();
            } catch (IOException ioe) { /* ignored */ }
        }
    }

    /**
     * Adds a message to the sendQueue and notifies the method waiting on the sendQueue to change.
     * Control messages are never silently dropped: if the queue is full, a queued piece message is
     * dropped to make room for it. Piece messages are dropped instead, the requester will retry.
     */
    private void addMessage(Message m) {
        synchronized (sendQueue) {
            if (!sendQueue.offer(m)) {
                if (m.type != Message.PIECE && removeMessage(Message.PIECE)) {
                    sendQueue.offer(m);
                } else {
                    if (_log.shouldWarn()) {
                        _log.warn("Send queue full, dropping " + m + " to [" + peer + "]");
                    }
                }
            }
            sendQueue.notifyAll();
        }
    }

    /** Remove messages not sent in 3m. */
    private static final int SEND_TIMEOUT = 3 * 60 * 1000;

    /**
     * Removes a particular message type from the queue.
     *
     * @param type the Message type to remove.
     * @return true when a message of the given type was removed, false otherwise.
     */
    private boolean removeMessage(int type) {
        boolean removed = false;
        synchronized (sendQueue) {
            Iterator<Message> it = sendQueue.iterator();
            while (it.hasNext()) {
                Message m = it.next();
                if (m.type == type) {
                    it.remove();
                    if (type == Message.PIECE) m.discard();
                    removed = true;
                    if (type == Message.PIECE && peer.supportsFast()) {
                        Message r = new Message(Message.REJECT, m.piece, m.begin, m.length);
                        if (_log.shouldDebug()) _log.debug("Sending [" + peer + "]: " + r);
                        try {
                            r.sendMessage(dout);
                        } catch (IOException ioe) { /* ignored */ }
                    }
                }
            }
            sendQueue.notifyAll();
        }
        return removed;
    }

    /**
     * Queue a keepalive message if the queue is empty.
     */
    void sendAlive() {
        synchronized (sendQueue) {
            if (sendQueue.isEmpty()) {
                Message m = new Message(Message.KEEP_ALIVE);
                sendQueue.offer(m);
            }
            sendQueue.notifyAll();
        }
    }

    /**
     * Queue a choke or unchoke message, cancelling any pending inverse.
     *
     * @param choke true to choke, false to unchoke
     */
    void sendChoke(boolean choke) {
        // We cancel the (un)choke but keep PIECE messages.
        // PIECE messages are purged if a choke is actually send.
        synchronized (sendQueue) {
            int inverseType = choke ? Message.UNCHOKE : Message.CHOKE;
            if (!removeMessage(inverseType)) {
                Message m = new Message(choke ? Message.CHOKE : Message.UNCHOKE);
                addMessage(m);
            }
        }
    }

    /**
     * Queue an interested or not-interested message, cancelling any pending inverse.
     *
     * @param interest true for interested, false for not-interested
     */
    void sendInterest(boolean interest) {
        synchronized (sendQueue) {
            int inverseType = interest ? Message.UNINTERESTED : Message.INTERESTED;
            if (!removeMessage(inverseType)) {
                Message m = new Message(interest ? Message.INTERESTED : Message.UNINTERESTED);
                addMessage(m);
            }
        }
    }

    /**
     * Queue a have message for the given piece.
     *
     * @param piece the piece number
     */
    void sendHave(int piece) {
        Message m = new Message(Message.HAVE, piece);
        addMessage(m);
    }

    /**
     * Queue a bitfield, have_all, or have_none message as appropriate.
     *
     * @param bitfield the bitfield to send
     */
    void sendBitfield(BitField bitfield) {
        boolean fast = peer.supportsFast();
        boolean all = false;
        boolean none = false;
        byte[] data = null;
        synchronized (bitfield) {
            if (fast && bitfield.complete()) {
                all = true;
            } else if (fast && bitfield.count() <= 0) {
                none = true;
            } else {
                byte[] d = bitfield.getFieldBytes();
                data = Arrays.copyOf(d, d.length);
            }
        }
        if (all) {
            sendHaveAll();
        } else if (none) {
            sendHaveNone();
        } else {
            Message m = new Message(data);
            addMessage(m);
        }
    }

    /** Retransmit requests not received in 7m. */
    private static final int REQ_TIMEOUT = (2 * SEND_TIMEOUT) + (60 * 1000);

    /**
     * Retransmit requests that haven't been received within REQ_TIMEOUT.
     *
     * @param requests the list of outstanding requests
     */
    void retransmitRequests(List<Request> requests) {
        long now = System.currentTimeMillis();
        Iterator<Request> it = requests.iterator();
        while (it.hasNext()) {
            Request req = it.next();
            if (now > req.sendTime + REQ_TIMEOUT) {
                if (_log.shouldDebug()) {
                    _log.debug("Retransmitting request " + req + " to [" + peer + "]");
                }
                sendRequest(req);
            }
        }
    }

    /**
     * Send all requests in the list.
     *
     * @param requests the list of requests to send
     */
    void sendRequests(List<Request> requests) {
        Iterator<Request> it = requests.iterator();
        while (it.hasNext()) {
            Request req = it.next();
            sendRequest(req);
        }
    }

    /**
     * Queue a request message, checking for duplicates.
     *
     * @param req the request to send
     */
    void sendRequest(Request req) {
        // Check for duplicate requests to deal with fibrillating i2p-bt
        // (multiple choke/unchokes received cause duplicate requests in the queue)
        synchronized (sendQueue) {
            Iterator<Message> it = sendQueue.iterator();
            while (it.hasNext()) {
                Message m = it.next();
                if (m.type == Message.REQUEST
                        && m.piece == req.getPiece()
                        && m.begin == req.off
                        && m.length == req.len) {
                    if (_log.shouldDebug()) {
                        _log.debug("Discarding duplicate request " + req + " to [" + peer + "]");
                    }
                    return;
                }
            }
        }
        Message m = new Message(Message.REQUEST, req.getPiece(), req.off, req.len);
        addMessage(m);
        req.sendTime = System.currentTimeMillis();
    }

    /**
     * Returns the total bytes of piece messages currently queued.
     * Used by PeerState to limit pipelined requests.
     *
     * @return total queued piece bytes
     */
    int queuedBytes() {
        int total = 0;
        synchronized (sendQueue) {
            Iterator<Message> it = sendQueue.iterator();
            while (it.hasNext()) {
                Message m = it.next();
                if (m.type == Message.PIECE) {
                    total += m.length;
                }
            }
        }
        return total;
    }

    /**
     * Queue a piece message with a callback to load the data from disk when required.
     *
     * @since 0.8.2
     */
    void sendPiece(int piece, int begin, int length, DataLoader loader) {
        // queue a fake message... set everything up,
        // except save the PeerState instead of the bytes.
        Message m = new Message(piece, begin, length, loader);
        addMessage(m);
        kickPrefetch(m);
    }

    /**
     * Submit an off-thread load for a freshly queued PIECE so its data is ready before the sender
     * thread reaches it, decoupling disk latency from the write path. Best-effort: when the depth
     * cap or thread pool is saturated the message stays lazy and loads inline as before. The
     * cap check is racy in principle, but enqueues are single-threaded per connection
     * (PeerState's reader), so the bound holds exactly.
     *
     * @param m the queued PIECE message
     * @since 0.9.71+
     */
    private void kickPrefetch(Message m) {
        if (_prefetchInFlight.get() >= MAX_PREFETCH_INFLIGHT) return;
        _prefetchInFlight.incrementAndGet();
        try {
            _prefetchExec.execute(() -> runPrefetch(m));
        } catch (RejectedExecutionException ree) {
            // pool saturated; message stays lazy, inline load covers it
            _prefetchInFlight.decrementAndGet();
        }
    }

    /**
     * Prefetch task: claim exclusive load rights, skip if purged meanwhile, read outside all
     * locks, then publish. Storage errors already surface as a null result (with REJECT) via
     * PeerCoordinator; an unexpected Throwable here drops only this block instead of killing
     * the connection as the old inline path would have.
     *
     * @param m the PIECE message to load
     * @since 0.9.71+
     */
    private void runPrefetch(Message m) {
        try {
            if (!m.claimLoad()) return;
            if (m.abortIfDiscarded()) return;
            long start = System.currentTimeMillis();
            ByteArray ba;
            try {
                ba = m.runLoader();
            } finally {
                recordLoad(System.currentTimeMillis() - start);
            }
            m.completeLoad(ba);
        } catch (Throwable t) {
            _log.error("Prefetch failed for [" + peer + "] " + m, t);
            m.completeLoad(null);
        } finally {
            _prefetchInFlight.decrementAndGet();
        }
    }

    /**
     * Send a cancel message and remove any matching request from the queue.
     *
     * @param req the request to cancel
     */
    void sendCancel(Request req) {
        // See if it is still in our send queue
        synchronized (sendQueue) {
            Iterator<Message> it = sendQueue.iterator();
            while (it.hasNext()) {
                Message m = it.next();
                if (m.type == Message.REQUEST
                        && m.piece == req.getPiece()
                        && m.begin == req.off
                        && m.length == req.len) {
                    it.remove();
                }
            }
        }

        // Always send, just to be sure it it is really canceled.
        Message m = new Message(Message.CANCEL, req.getPiece(), req.off, req.len);
        addMessage(m);
    }

    /**
     * Remove all Request messages from the queue. Does not send a cancel message.
     *
     * @since 0.8.2
     */
    void cancelRequestMessages() {
        synchronized (sendQueue) {
            for (Iterator<Message> it = sendQueue.iterator(); it.hasNext(); ) {
                if (it.next().type == Message.REQUEST) {
                    it.remove();
                }
            }
        }
    }

    /**
     * Remove Request messages for a piece from the queue. Does not send a cancel message.
     *
     * @param piece the piece index
     * @since 0.9.71+
     */
    void cancelRequestMessages(int piece) {
        synchronized (sendQueue) {
            for (Iterator<Message> it = sendQueue.iterator(); it.hasNext(); ) {
                Message m = it.next();
                if (m.type == Message.REQUEST && m.piece == piece) {
                    it.remove();
                }
            }
        }
    }

    /**
     * Called by the PeerState when the other side doesn't want this request to be handled anymore.
     * Removes any pending Piece Message from our send queue. Does not send a cancel message.
     *
     * @param piece the piece number
     * @param begin the offset within the piece
     * @param length the length of the request
     */
    void cancelRequest(int piece, int begin, int length) {
        synchronized (sendQueue) {
            Iterator<Message> it = sendQueue.iterator();
            while (it.hasNext()) {
                Message m = it.next();
                if (m.type == Message.PIECE
                        && m.piece == piece
                        && m.begin == begin
                        && m.length == length) {
                    it.remove();
                    m.discard();
                }
            }
        }
    }

    /** Queue an extension message. */
    void sendExtension(int id, byte[] bytes) {
        Message m = new Message(id, bytes);
        addMessage(m);
    }

    /** Queue a port message. */
    void sendPort(int port) {
        Message m = new Message(Message.PORT, port);
        addMessage(m);
    }

    /** Queue a have-all message. */
    private void sendHaveAll() {
        Message m = new Message(Message.HAVE_ALL);
        addMessage(m);
    }

    /** Queue a have-none message. */
    private void sendHaveNone() {
        Message m = new Message(Message.HAVE_NONE);
        addMessage(m);
    }

    /** Queue a reject message. */
    void sendReject(int piece, int begin, int length) {
        Message m = new Message(Message.REJECT, piece, begin, length);
        addMessage(m);
    }

    /** Queue an allowed fast message (BEP 6). */
    void sendAllowedFast(int piece) {
        Message m = new Message(Message.ALLOWED_FAST, piece);
        addMessage(m);
    }
}
