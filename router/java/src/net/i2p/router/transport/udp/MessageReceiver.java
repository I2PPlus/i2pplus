package net.i2p.router.transport.udp;

import java.util.concurrent.BlockingQueue;

import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.data.i2np.I2NPMessageImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.util.CoDelBlockingQueue;
//import net.i2p.util.ByteCache;
import net.i2p.util.HexDump;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Pull fully completed fragments off the {@link InboundMessageFragments} queue,
 * parse 'em into I2NPMessages, and stick them on the
 * {@link net.i2p.router.InNetMessagePool} by way of the {@link UDPTransport}.
 */
class MessageReceiver {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    /** list of messages (InboundMessageState) fully received but not interpreted yet */
    private final BlockingQueue<InboundMessageState> _completeMessages;
    private volatile boolean _alive;
    private static final int cores = SystemVersion.getCores();
    private static final int MIN_THREADS = 1;
    private static final int MAX_THREADS = 2;
    private static final int MIN_QUEUE_SIZE =  SystemVersion.isSlow() ? 16 : 32;
    private static final int MAX_QUEUE_SIZE = SystemVersion.isSlow() ? 64 : 128;
    private final int _threadCount;
    private static final long POISON_IMS = -99999999999l;

    public MessageReceiver(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(MessageReceiver.class);
        _transport = transport;
        long maxMemory = SystemVersion.getMaxMemory();
        boolean isSlow = SystemVersion.isSlow();
        _threadCount = MAX_THREADS;
        int qsize = (int) MAX_QUEUE_SIZE;
        _completeMessages = new CoDelBlockingQueue<InboundMessageState>(ctx, "UDP-MessageReceiver", qsize);
        _context.statManager().createRateStat("udp.inboundExpired", "Number of inbound messages expired before receipt", "Transport [UDP]", UDPTransport.RATES);
        _alive = true;
    }

    public synchronized void startup() {
        _alive = true;
        for (int i = 0; i < _threadCount; i++) {
            I2PThread t = new I2PThread(new Runner(), "UDPMsgRX " + (i+1) + '/' + _threadCount, true);
            t.start();
        }
    }

    private class Runner implements Runnable {
        private final I2NPMessageHandler _handler;
        public Runner() { _handler = new I2NPMessageHandler(_context); }
        public void run() { loop(_handler); }
    }

    public synchronized void shutdown() {
        _alive = false;
        _completeMessages.clear();
        for (int i = 0; i < _threadCount; i++) {
            InboundMessageState ims = new InboundMessageState(_context, POISON_IMS, null);
            _completeMessages.offer(ims);
        }
        for (int i = 1; i <= 5 && !_completeMessages.isEmpty(); i++) {
            try {Thread.sleep(i * 10);}
            catch (InterruptedException ie) {}
        }
        _completeMessages.clear();
    }

    /**
     *  This queues the message for processing.
     *  Processing will call state.releaseResources(), do not access state after calling this.
     *  BLOCKING if queue is full.
     */
    public void receiveMessage(InboundMessageState state) {
        if (_alive) {
            try {_completeMessages.put(state);}
            catch (InterruptedException ie) {_alive = false;}
        }
    }

    public void loop(I2NPMessageHandler handler) {
        InboundMessageState message = null;
        ByteArray buf = new ByteArray(new byte[I2NPMessage.MAX_SIZE]);
        while (_alive) {
            int expired = 0;
            long expiredLifetime = 0;
            try {
                while (message == null) {
                    message = _completeMessages.take();
                    if ((message != null) && (message.getMessageId() == POISON_IMS)) {
                        message = null;
                        break;
                    }
                    if ((message != null) && (message.isExpired())) {
                        expiredLifetime += message.getLifetime();
                        message = null;
                        expired++;
                    }
                }
            } catch (InterruptedException ie) {}

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
                ByteArray fragments[] = state.getFragments();
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
                    _context.banlist().banlistRouterForever(state.getFrom(),
                        " <b>➜</b> " + "Sent corrupt message");  // don't bother translating
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
