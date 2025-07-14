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
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
//import net.i2p.util.SimpleTimer;

class PeerConnectionOut implements Runnable {
    private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(PeerConnectionOut.class);
    private final Peer peer;
    private final DataOutputStream dout;
    private Thread thread;
    private boolean quit;
    // Contains Messages.
    private final BlockingQueue<Message> sendQueue = new LinkedBlockingQueue<Message>();
    private static final AtomicLong __id = new AtomicLong();
    private final long _id;
    long lastSent;

    public PeerConnectionOut(Peer peer, DataOutputStream dout) {
        this.peer = peer;
        this.dout = dout;
        _id = __id.incrementAndGet();
        lastSent = System.currentTimeMillis();
    }

    public void startup() {
        thread = new I2PAppThread(this, "Snark sender " + _id + ": " + peer);
        thread.start();
    }

    /**
     * Continuesly monitors for more outgoing messages that have to be send.
     * Stops if quit is true or an IOException occurs.
     */
    public void run() {
        try {
            boolean shouldThrottleRequests = false;
            while (!quit && peer.isConnected()) {
                Message m = null;
                PeerState state = null;
                boolean shouldFlush;
                synchronized(sendQueue) {
                    shouldFlush = !quit && peer.isConnected() && sendQueue.isEmpty();
                }

                // Make sure everything will reach the other side.
                // flush while not holding lock, could take a long time
                if (shouldFlush) {dout.flush();}

                synchronized(sendQueue) {
                    while (!quit && peer.isConnected() && (shouldThrottleRequests || sendQueue.isEmpty())) {
                        try {sendQueue.wait(shouldThrottleRequests ? 5000 : 60*1000);} // Wait till more data arrives.
                        catch (InterruptedException ie) {} // ignored
                        shouldThrottleRequests = false;
                    }

                    state = peer.state;
                    if (!quit && state != null && peer.isConnected()) {
                        // Piece messages are big. So if there are other (control) messages make sure they are sent first.
                        // Also remove request messages from the queue if we are currently being choked to prevent them from
                        // being sent even if we get unchoked a little later (since we will resend them anyway in that case).
                        // And remove piece messages if we are choking.
                        Iterator<Message> it = sendQueue.iterator(); // this should get fixed for starvation
                        while (m == null && it.hasNext()) {
                            Message nm = it.next();
                            if (nm.type == Message.PIECE) {
                                if (state.choking) {
                                    it.remove();
                                    if (peer.supportsFast()) {
                                        Message r = new Message(Message.REJECT, nm.piece, nm.begin, nm.length);
                                        if (_log.shouldDebug()) {_log.debug("Sending [" + peer + "]: " + r);}
                                            r.sendMessage(dout);
                                    }
                                }
                                nm = null;
                            } else if (nm.type == Message.REQUEST) {
                                if (state.choked) {
                                    it.remove();
                                    nm = null;
                                } else if (shouldThrottleRequests) {
                                    // previous request in queue throttled, skip this one too
                                    if (_log.shouldWarn()) {_log.warn("Additional throttle: " + nm + " to " + peer);}
                                    nm = null;
                                } else if (!peer.shouldRequest(nm.length)) {
                                    // request throttle, skip this and all others in this loop
                                    if (_log.shouldWarn()) {_log.warn("Throttle: " + nm + " to " + peer);}
                                    shouldThrottleRequests = true;
                                    nm = null;
                                }
                            }
                            if (nm != null) {m = nm; it.remove();}
                        }

                        if (m == null) {
                            m = sendQueue.peek();
                            if (m != null && m.type == Message.PIECE) {
                                // bandwidth limiting
                                // Pieces are the last thing in the queue to be sent so we can
                                // simply wait right here and then loop
                                if (!peer.shouldSend(Math.min(m.length, PeerState.PARTSIZE))) {
                                    if (_log.shouldWarn()) {_log.warn("Throttle: " + m + " to " + peer);}
                                    try {sendQueue.wait(5000);}
                                    catch (InterruptedException ie) {}
                                    continue;
                                }
                            } else if (m != null && m.type == Message.REQUEST) {
                                if (shouldThrottleRequests) {continue;}
                            }
                            m = sendQueue.poll();
                        }
                    }
                }

                if (m != null) {
                    if (_log.shouldDebug()) {_log.debug("Sending [" + peer + "]: " + m);}

                    // This can block for quite a while.
                    // To help get slow peers going, and track the bandwidth better,
                    // move this _after_ state.uploaded() and see how it works.
                    //m.sendMessage(dout);
                    lastSent = System.currentTimeMillis();

                    // Remove all piece messages after sending a choke message.
                    // FiXME this causes REJECT messages to be sent before sending the CHOKE;
                    // BEP 6 recommends sending them after.
                    if (m.type == Message.CHOKE) {removeMessage(Message.PIECE);}

                    // XXX - Should also register overhead...
                    // Don't let other clients requesting big chunks get an advantage
                    // when we are seeding;
                    // only count the rest of the upload after sendMessage().
                    int remainder = 0;
                    if (m.type == Message.PIECE) {
                        // first PARTSIZE was signalled in shouldSend() above
                        if (m.len > PeerState.PARTSIZE) {remainder = m.len - PeerState.PARTSIZE;}
                    }

                    m.sendMessage(dout);
                    if (remainder > 0) {peer.uploaded(remainder);}
                    m = null;
                }
            }
        }
        catch (IOException ioe) {
            // Ignore, probably other side closed connection.
            if (_log.shouldInfo()) {_log.info("Error sending to [" + peer + "] \n* " + ioe.getMessage());}
        }
        catch (Throwable t) {
            _log.error("Error sending to [" + peer + "]", t);
            if (t instanceof OutOfMemoryError) {throw (OutOfMemoryError)t;}
        }
        finally {
            quit = true;
            peer.disconnect();
        }
    }

    public void disconnect() {
        synchronized(sendQueue) {
            quit = true;
            if (thread != null) {thread.interrupt();}
            sendQueue.clear();
            sendQueue.notifyAll();
        }

        if (dout != null) {
            try {dout.close();}
            catch (IOException ioe) {}
        }
    }

    /**
     * Adds a message to the sendQueue and notifies the method waiting
     * on the sendQueue to change.
     */
    private void addMessage(Message m) {
      synchronized(sendQueue) {
          sendQueue.offer(m);
          sendQueue.notifyAll();
        }
    }

    /** remove messages not sent in 3m */
    private static final int SEND_TIMEOUT = 3*60*1000;

    /**
     * Removes a particular message type from the queue.
     *
     * @param type the Message type to remove.
     * @returns true when a message of the given type was removed, false
     * otherwise.
     */
    private boolean removeMessage(int type) {
        boolean removed = false;
        synchronized(sendQueue) {
            Iterator<Message> it = sendQueue.iterator();
            while (it.hasNext()) {
                Message m = it.next();
                if (m.type == type) {
                    it.remove();
                    removed = true;
                    if (type == Message.PIECE && peer.supportsFast()) {
                        Message r = new Message(Message.REJECT, m.piece, m.begin, m.length);
                        if (_log.shouldDebug())
                            _log.debug("Sending [" + peer + "]: " + r);
                        try {
                            r.sendMessage(dout);
                        } catch (IOException ioe) {}
                    }
                }
              }
            sendQueue.notifyAll();
          }
        return removed;
    }

    void sendAlive() {
        synchronized(sendQueue) {
          if (sendQueue.isEmpty()) {
              Message m = new Message(Message.KEEP_ALIVE);
              sendQueue.offer(m);
          }
          sendQueue.notifyAll();
        }
    }

    void sendChoke(boolean choke) {
        // We cancel the (un)choke but keep PIECE messages.
        // PIECE messages are purged if a choke is actually send.
        synchronized(sendQueue) {
            int inverseType  = choke ? Message.UNCHOKE : Message.CHOKE;
            if (!removeMessage(inverseType)) {
                Message m = new Message(choke ? Message.CHOKE : Message.UNCHOKE);
                addMessage(m);
              }
          }
      }

    void sendInterest(boolean interest) {
        synchronized(sendQueue) {
          int inverseType  = interest ? Message.UNINTERESTED : Message.INTERESTED;
          if (!removeMessage(inverseType)) {
              Message m = new Message(interest ? Message.INTERESTED : Message.UNINTERESTED);
              addMessage(m);
            }
        }
    }

    void sendHave(int piece) {
        Message m = new Message(Message.HAVE, piece);
        addMessage(m);
    }

    void sendBitfield(BitField bitfield) {
        boolean fast = peer.supportsFast();
        boolean all = false;
        boolean none = false;
        byte[] data = null;
        synchronized(bitfield) {
            if (fast && bitfield.complete()) {all = true;}
            else if (fast && bitfield.count() <= 0) {none = true;}
            else {
               byte[] d = bitfield.getFieldBytes();
               data =  Arrays.copyOf(d, d.length);
            }
        }
        if (all) {sendHaveAll();}
        else if (none) {sendHaveNone();}
        else {
           Message m = new Message(data);
           addMessage(m);
        }
    }

    /** reransmit requests not received in 7m */
    private static final int REQ_TIMEOUT = (2 * SEND_TIMEOUT) + (60 * 1000);

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

    void sendRequests(List<Request> requests) {
        Iterator<Request> it = requests.iterator();
        while (it.hasNext()) {
            Request req = it.next();
            sendRequest(req);
        }
    }

    void sendRequest(Request req) {
        // Check for duplicate requests to deal with fibrillating i2p-bt
        // (multiple choke/unchokes received cause duplicate requests in the queue)
        synchronized(sendQueue) {
            Iterator<Message> it = sendQueue.iterator();
            while (it.hasNext()) {
                Message m = it.next();
                if (m.type == Message.REQUEST && m.piece == req.getPiece() &&
                    m.begin == req.off && m.length == req.len) {
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

    // Used by PeerState to limit pipelined requests
    int queuedBytes() {
        int total = 0;
        synchronized(sendQueue) {
            Iterator<Message> it = sendQueue.iterator();
            while (it.hasNext()) {
                Message m = it.next();
                if (m.type == Message.PIECE) {total += m.length;}
            }
        }
        return total;
    }

    /**
     *  Queue a piece message with a callback to load the data
     *  from disk when required.
     *  @since 0.8.2
     */
    void sendPiece(int piece, int begin, int length, DataLoader loader) {
        // queue a fake message... set everything up,
        // except save the PeerState instead of the bytes.
        Message m = new Message(piece, begin, length, loader);
        addMessage(m);
    }

    /** send cancel */
    void sendCancel(Request req) {
        // See if it is still in our send queue
        synchronized(sendQueue) {
            Iterator<Message> it = sendQueue.iterator();
            while (it.hasNext()) {
                Message m = it.next();
                if (m.type == Message.REQUEST && m.piece == req.getPiece() && m.begin == req.off && m.length == req.len) {
                    it.remove();
                }
            }
        }

        // Always send, just to be sure it it is really canceled.
        Message m = new Message(Message.CANCEL, req.getPiece(), req.off, req.len);
        addMessage(m);
    }

    /**
     *  Remove all Request messages from the queue.
     *  Does not send a cancel message.
     *  @since 0.8.2
     */
    void cancelRequestMessages() {
        synchronized(sendQueue) {
            for (Iterator<Message> it = sendQueue.iterator(); it.hasNext(); ) {
                if (it.next().type == Message.REQUEST) {it.remove();}
            }
        }
    }

    /**
     *  Called by the PeerState when the other side doesn't want this
     *  request to be handled anymore. Removes any pending Piece Message
     *  from out send queue.
     *  Does not send a cancel message.
     */
    void cancelRequest(int piece, int begin, int length) {
        synchronized (sendQueue) {
            Iterator<Message> it = sendQueue.iterator();
            while (it.hasNext()) {
                Message m = it.next();
                if (m.type == Message.PIECE && m.piece == piece && m.begin == begin && m.length == length) {
                    it.remove();
                }
            }
        }
    }

    /** @since 0.8.2 */
    void sendExtension(int id, byte[] bytes) {
        Message m = new Message(id, bytes);
        addMessage(m);
    }

    /** @since 0.8.4 */
    void sendPort(int port) {
        Message m = new Message(Message.PORT, port);
        addMessage(m);
    }

    /** @since 0.9.21 */
    private void sendHaveAll() {
        Message m = new Message(Message.HAVE_ALL);
        addMessage(m);
    }

    /** @since 0.9.21 */
    private void sendHaveNone() {
        Message m = new Message(Message.HAVE_NONE);
        addMessage(m);
    }

    /** @since 0.9.21 */
    void sendReject(int piece, int begin, int length) {
        Message m = new Message(Message.REJECT, piece, begin, length);
        addMessage(m);
    }

}
  