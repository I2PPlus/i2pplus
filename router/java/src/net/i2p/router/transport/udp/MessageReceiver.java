package net.i2p.router.transport.udp;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.data.i2np.I2NPMessageImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.Tuner;
import net.i2p.router.BanLogger;
import net.i2p.router.util.CoDelBlockingQueue;
import net.i2p.util.HexDump;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Pull fully completed fragments off the {@link InboundMessageFragments} queue,
 * parse 'em into I2NPMessages, and stick them on the
 * {@link net.i2p.router.InNetMessagePool} by way of the {@link UDPTransport}.
 *
 * Supports dynamic thread count adjustment via {@link #setThreadCount(int)}.
 * @since 0.9.14 (dynamic resizing since 0.9.70+)
 */
class MessageReceiver {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    private final BanLogger _banLogger;
    /** list of messages (InboundMessageState) fully received but not interpreted yet */
    private final BlockingQueue<InboundMessageState> _completeMessages;
    private volatile boolean _alive;
    private static volatile int _threadCount = SystemVersion.isSlow() ? 2 : 3;
    private final AtomicInteger _activeRunners = new AtomicInteger();
    private final CopyOnWriteArrayList<Runner> _runners = new CopyOnWriteArrayList<>();
    private static final long POISON_IMS = -99999999999L;
    private static final int MIN_THREADS = 1;
    private static final int MAX_THREADS = 16;

    public MessageReceiver(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(MessageReceiver.class);
        _transport = transport;
        _banLogger = new BanLogger();
        _banLogger.initialize(ctx);
        long maxMemory = SystemVersion.getMaxMemory();
        int qsize = Math.max(64, Math.min(512, (int)(maxMemory / (32 * 1024 * 1024L))));
        _completeMessages = new CoDelBlockingQueue<>(ctx, "UDP-MessageReceiver", qsize);
        _context.statManager().createRequiredRateStat("udp.inboundExpired", "Number of inbound messages expired before receipt", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRequiredRateStat("udp.msgRx.queueSize", "UDP message receiver queue depth", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRequiredRateStat("codel.UDP-MessageReceiver.delay", "Average queue delay (ms)", "Transport [UDP]", UDPTransport.RATES);
        _alive = true;
    }

    /**
     * Returns the current target thread count.
     * @since 0.9.70+
     */
    public static int getThreadCount() { return _threadCount; }

    /**
     * Returns the current queue depth of the message receiver.
     * @since 0.9.70+
     */
    public int getQueueSize() { return _completeMessages.size(); }

    /**
     * Returns the maximum capacity of the message receiver queue.
     * @since 0.9.70+
     */
    public int getQueueCapacity() { return _completeMessages.size() + _completeMessages.remainingCapacity(); }

    /**
     * Sets the target thread count. Takes effect immediately — excess threads
     * will exit, new threads will be started if needed.
     * @since 0.9.70+
     */
    public static void setThreadCount(int count) {
        _threadCount = Math.max(MIN_THREADS, Math.min(MAX_THREADS, count));
    }

    public synchronized void startup() {
        _alive = true;
        int count = _threadCount;
        for (int i = 0; i < count; i++) {
            startRunner();
        }
    }

    private void startRunner() {
        Runner r = new Runner();
        _runners.add(r);
        _activeRunners.incrementAndGet();
        I2PThread t = new I2PThread(r, "UDPMsgRX " + _activeRunners.get() + '/' + _threadCount, true);
        t.start();
    }

    private class Runner implements Runnable {
        private final I2NPMessageHandler _handler;
        Runner() { _handler = new I2NPMessageHandler(_context); }
        @Override
        public void run() { loop(_handler); }
    }

    public synchronized void shutdown() {
        _alive = false;
        _completeMessages.clear();
        // poison all active runners
        for (int i = 0; i < _activeRunners.get(); i++) {
            InboundMessageState ims = new InboundMessageState(_context, POISON_IMS, null);
            _completeMessages.offer(ims);
        }
        for (int i = 1; i <= 5 && !_completeMessages.isEmpty(); i++) {
            try {Thread.sleep(i * 10L);}
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        _completeMessages.clear();
        _runners.clear();
        _activeRunners.set(0);
    }

    /**
     *  Dynamically adjust thread count. If target is higher than current,
     *  start new runners. If lower, poison excess runners.
     *
     *  @since 0.9.70+
     */
    void adjustThreads() {
        if (!_alive) return;
        int target = _threadCount;
        int current = _activeRunners.get();
        while (current < target) {
            if (_activeRunners.compareAndSet(current, current + 1)) {
                Runner r = new Runner();
                _runners.add(r);
                I2PThread t = new I2PThread(r, "UDPMsgRX " + (current + 1) + '/' + target, true);
                t.start();
                _log.info("Added MessageReceiver thread, now " + (current + 1) + "/" + target);
                current = _activeRunners.get();
            } else {
                current = _activeRunners.get();
            }
        }
        while (current > target) {
            if (_activeRunners.compareAndSet(current, current - 1)) {
                // signal one runner to exit
                InboundMessageState ims = new InboundMessageState(_context, POISON_IMS, null);
                _completeMessages.offer(ims);
                _log.info("Removing MessageReceiver thread, now " + (current - 1) + "/" + target);
                current = _activeRunners.get();
            } else {
                current = _activeRunners.get();
            }
        }
    }

    /**
     *  This queues the message for processing.
     *  Processing will call state.releaseResources(), do not access state after calling this.
     *  BLOCKING if queue is full.
     */
    public void receiveMessage(InboundMessageState state) {
        if (_alive) {
            try {_completeMessages.put(state);}
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); _alive = false; }
            _context.statManager().addRateData("udp.msgRx.queueSize", _completeMessages.size());
        }
    }

    void loop(I2NPMessageHandler handler) {
        InboundMessageState message = null;
        ByteArray buf = new ByteArray(new byte[I2NPMessage.MAX_SIZE]);
        try {
            while (_alive) {
                Tuner.adjustHandlerPriority();
                int expired = 0;
                long expiredLifetime = 0;
                try {
                    while (message == null) {
                        message = _completeMessages.take();
                        if ((message != null) && (message.getMessageId() == POISON_IMS)) {
                            return;
                        }
                        if ((message != null) && (message.isExpired())) {
                            expiredLifetime += message.getLifetime();
                            message = null;
                            expired++;
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }

                if (expired > 0) {_context.statManager().addRateData("udp.inboundExpired", expired, expiredLifetime);}

                if (message != null) {
                    int size = message.getCompleteSize();
                    try {
                        I2NPMessage msg = readMessage(buf, message, handler);
                        if (msg != null) {_transport.messageReceived(msg, null, message.getFrom(), message.getLifetime(), size);}
                    } catch (RuntimeException re) {
                        _log.error("b0rked receiving a message.. wazza huzza hmm?", re);
                        continue;
                    }
                    message = null;
                }
            }
        } finally {
            _runners.remove(this);
        }
    }

    /**
     *  Assemble all the fragments into an I2NP message.
     *  This calls state.releaseResources(), do not access state after calling this.
     *
     *  @param buf temp buffer for convenience
     *  @return null on error
     */
    private I2NPMessage readMessage(ByteArray buf, InboundMessageState state, I2NPMessageHandler handler) {
        int sz = state.getCompleteSize();
        try {
            I2NPMessage m;
            int numFragments = state.getFragmentCount();
            if (numFragments > 1) {
                ByteArray[] fragments = state.getFragments();
                int off = 0;
                byte[] data = buf.getData();
                for (int i = 0; i < numFragments; i++) {
                    ByteArray ba = fragments[i];
                    int len = ba.getValid();
                    System.arraycopy(ba.getData(), 0, data, off, len);
                    off += len;
                }
                if (off != sz) {
                    if (_log.shouldWarn()) {_log.warn("Hmm, offset of the fragments = " + off + " while the state says " + sz);}
                    return null;
                }
                m = I2NPMessageImpl.fromRawByteArray(_context, data, 0, sz, handler);
            } else {
                // zero copy for single fragment
                m = I2NPMessageImpl.fromRawByteArray(_context, state.getFragments()[0].getData(), 0, sz, handler);
            }
            m.setUniqueId(state.getMessageId());
            return m;
        } catch (I2NPMessageException ime) {
            if (_log.shouldWarn()) {
                ByteArray ba;
                if (state.getFragmentCount() > 1) {ba = buf;}
                else {ba = state.getFragments()[0];}
                byte[] data = ba.getData();
                _log.warn("Message invalid: " + state + " PeerState: " + _transport.getPeerState(state.getFrom()) +
                          "\n* DUMP:\n" + HexDump.dump(data, 0, sz) + "\n* RAW:\n" + Base64.encode(data, 0, sz), ime);
            }
            if (state.getFragments()[0].getData()[0] == DatabaseStoreMessage.MESSAGE_TYPE) {
                PeerState ps = _transport.getPeerState(state.getFrom());
                if (ps != null && ps.getRemotePort() == 65520) {
                    // distinct port of buggy router
                    _transport.sendDestroy(ps, SSU2Util.REASON_BANNED);
                    _transport.dropPeer(ps, true, "Corrupt DSM");
                    _banLogger.logBanForever(state.getFrom(), _context, "Sent corrupt message");
                    _context.banlist().banlistRouterForever(state.getFrom(),
                        "" + "Sent corrupt message");  // don't bother translate
                }
            }
            _context.messageHistory().droppedInboundMessage(state.getMessageId(), state.getFrom(),
                "error: " + ime.toString() + ": " + state.toString());
            return null;
        } catch (RuntimeException e) {
            // e.g. AIOOBE
            if (_log.shouldWarn()) {_log.warn("Error handling a message: " + state, e);}
            _context.messageHistory().droppedInboundMessage(state.getMessageId(), state.getFrom(),
                "error: " + e.toString() + ": " + state.toString());
            return null;
        } finally {state.releaseResources();}
    }

}
