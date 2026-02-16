package net.i2p.router.tunnel;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.util.CoDelBlockingQueue;
import net.i2p.router.util.CoDelPriorityBlockingQueue;
import net.i2p.util.SystemVersion;

/**
 * This class represents a tunnel gateway with multiple hops that accepts messages,
 * then coalesces and fragments them before sending them down the tunnel.
 *
 * The processing flow is:
 * <ol>
 *  <li>add an I2NPMessage (and optionally, the target router or tunnel)</li>
 *  <li>messages queue in this PumpedTunnelGateway's internal _prequeue</li>
 *  <li>QueuePreprocessor pulls PendingGatewayMessages from _prequeue and processes them</li>
 *  <li>Fragments are sent to Sender, encrypted, and delivered to the Receiver</li>
 *  <li>Receiver directs the message to the next tunnel hop or endpoint</li>
 * </ol>
 *
 * This class uses specialized CoDel queues to manage queue delays and bufferbloat.
 * The outbound gateway uses a priority queue, while inbound uses a bounded non-priority queue.
 *
 * Thread safety:
 * <ul>
 *  <li>_prequeue is a thread-safe blocking queue managing pending messages</li>
 *  <li>_queue is synchronized for modifications and preprocessing</li>
 *  <li>Expiration pruning is done outside locks to minimize contention</li>
 *  <li>The pump() method is safe for concurrent invocation by pumper threads</li>
 * </ul>
 */
class PumpedTunnelGateway extends TunnelGateway {
    private final BlockingQueue<PendingGatewayMessage> _prequeue;
    private TunnelGatewayPumper _pumper;
    public final boolean _isInbound;
    private Hash _nextHop;

    private static final int MAX_OB_MSGS_PER_PUMP = SystemVersion.isSlow() ? 64 : 256;
    private static final int MAX_IB_MSGS_PER_PUMP = SystemVersion.isSlow() ? 32 : 128;
    private static final int INITIAL_OB_QUEUE = SystemVersion.isSlow() ? 64 : 256;
    private static final int MAX_IB_QUEUE = SystemVersion.isSlow() ? 1024 : 2048;

    public static final String PROP_MAX_OB_MSGS_PER_PUMP = "router.pumpMaxOutboundMsgs";
    public static final String PROP_MAX_IB_MSGS_PER_PUMP = "router.pumpMaxInboundMsgs";
    public static final String PROP_INITIAL_OB_QUEUE = "router.pumpInitialOutboundQueue";
    public static final String PROP_MAX_IB_QUEUE = "router.pumpMaxInboundQueue";

    /**
     * Constructs a PumpedTunnelGateway instance.
     *
     * Outbound gateways use an unbounded priority queue;
     * inbound gateways use a bounded blocking queue.
     *
     * @param context RouterContext
     * @param preprocessor QueuePreprocessor responsible for preprocessing messages
     * @param sender Sender responsible for encrypting and delivering messages
     * @param receiver Receiver that consumes encrypted messages for forwarding
     * @param pumper TunnelGatewayPumper managing pumping threads
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public PumpedTunnelGateway(RouterContext context, QueuePreprocessor preprocessor,
                               Sender sender, Receiver receiver, TunnelGatewayPumper pumper) {
        super(context, preprocessor, sender, receiver);
        if (getClass() == PumpedTunnelGateway.class) {
            // Outbound gateway uses priority queue
            _prequeue = new CoDelPriorityBlockingQueue(context, "OBGW",
                    context.getProperty(PROP_INITIAL_OB_QUEUE, INITIAL_OB_QUEUE));
            _isInbound = false;
        } else {
            // Inbound gateway uses bounded blocking queue
            _prequeue = new CoDelBlockingQueue<PendingGatewayMessage>(context, "IBGW",
                    context.getProperty(PROP_MAX_IB_QUEUE, MAX_IB_QUEUE));
            _isInbound = true;
        }
        _nextHop = receiver.getSendTo();
        _pumper = pumper;
    }

    /**
     * Adds a message to be sent down the tunnel.
     * If the prequeue is full, the message is dropped silently and a statistic is updated.
     *
     * This method is optimized for outbound gateways and called by TPTG for inbound.
     *
     * @param msg the message to send
     * @param toRouter optional target router
     * @param toTunnel optional target tunnel
     */
    @Override
    public void add(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
        OutboundGatewayMessage cur = new OutboundGatewayMessage(msg, toRouter, toTunnel);
        if (_log.shouldDebug()) {
            _log.debug("Outbound PumpedTunnelGateway added [Type " + msg.getType() + "] at priority: " + cur.getPriority());
        }
        add(cur);
    }

    /**
     * Adds a PendingGatewayMessage to the prequeue and notifies the pumper to process it.
     *
     * @param cur the PendingGatewayMessage to add
     */
    protected void add(PendingGatewayMessage cur) {
        _messagesSent++;
        if (_prequeue.offer(cur)) {
            _pumper.wantsPumping(this);
        } else {
            _context.statManager().addRateData("tunnel.dropGatewayOverflow", 1);
        }
    }

    /**
     * Pumps messages from the internal prequeue into the processing queue.
     *
     * This method tries to drain a bounded number of messages from _prequeue into the provided queueBuf,
     * then adds them to the internal _queue for preprocessing and fragmentation.
     *
     * Expired messages are removed after preprocessing, outside the synchronization block, to reduce contention.
     *
     * @param queueBuf an empty list used as a temporary buffer for messages (will be cleared before return)
     * @return true if there are still messages remaining in _prequeue and the caller should requeue this gateway
     */
    public boolean pump(List<PendingGatewayMessage> queueBuf) {
        // Adjust max messages per pump based on backlog and system load
        int max;
        boolean backlogged = _context.commSystem().isBacklogged(_nextHop);
        long lag = _context.jobQueue().getMaxLag();

        if (backlogged && _log.shouldInfo()) {
            _log.info("PumpedTunnelGateway backlogged, queued to " + _nextHop + " : "
                      + _prequeue.size() + " inbound? " + _isInbound);
        }

        if (backlogged) {
            max = _isInbound ? 1 : 2;
        } else {
            max = _isInbound
                    ? _context.getProperty(PROP_MAX_IB_MSGS_PER_PUMP, MAX_IB_MSGS_PER_PUMP)
                    : _context.getProperty(PROP_MAX_OB_MSGS_PER_PUMP, MAX_OB_MSGS_PER_PUMP);
        }

        _prequeue.drainTo(queueBuf, max);
        if (queueBuf.isEmpty()) {
            return false;
        }

        boolean moreMessagesExist = !_prequeue.isEmpty();

        final boolean debug = _log.shouldDebug();
        long startTime = debug ? _context.clock().now() : 0;

        synchronized (_queue) {
            _queue.addAll(queueBuf);
            if (debug) {
                _log.debug("Added before direct flush preprocessing for " + toString() + ":\n* " + _queue);
            }
            boolean delayedFlush = _preprocessor.preprocessQueue(_queue, _sender, _receiver);
            if (debug) {
                long afterPreprocess = _context.clock().now();
                if (afterPreprocess - startTime > 0) {
                    _log.debug("Preprocessing took " + (afterPreprocess - startTime) + " ms");
                }
            }
            _lastFlush = _context.clock().now();
        }

        queueBuf.clear();
        pruneExpiredMessages();

        if (debug) {
            long endTime = _context.clock().now();
            _log.debug("Total pump processing time for " + toString() + ": " + (endTime - startTime) + " ms");
        }

        if (moreMessagesExist && _log.shouldInfo()) {
            _log.info("PumpedTunnelGateway remaining to [" + _nextHop.toBase64().substring(0, 6) + "] -> Pre-queue: "
                      + _prequeue.size() + " inbound? " + _isInbound + " backlogged? " + backlogged);
        }

        return moreMessagesExist;
    }

    /**
     * Destroy this gateway and release all resources.
     * Cancels pending timers, clears queues, and nulls references to enable timely GC.
     * @since 0.9.68+
     */
    @Override
    public void destroy() {
        super.destroy();
        _prequeue.clear();
        synchronized (_queue) {
            _queue.clear();
        }
        // Help GC by nulling references
        _pumper = null;
        _nextHop = null;
    }

    /**
     * Removes messages from the internal processing queue that have expired.
     * This method is called outside synchronized blocks to minimize contention.
     */
    private void pruneExpiredMessages() {
        long now = _context.clock().now();
        long expirationCutoff = now - Router.CLOCK_FUDGE_FACTOR;

        synchronized (_queue) {
            Iterator<PendingGatewayMessage> it = _queue.iterator();
            while (it.hasNext()) {
                PendingGatewayMessage m = it.next();
                if (m.getExpiration() < expirationCutoff) {
                    if (_log.shouldDebug()) {
                        _log.debug("Expiring message from queue: " + m);
                    }
                    it.remove();
                }
            }
        }
    }
}
