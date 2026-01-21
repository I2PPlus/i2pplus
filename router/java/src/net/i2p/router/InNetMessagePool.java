package net.i2p.router;

import java.io.Writer;
import java.util.Date;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.stat.RateConstants;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.data.router.RouterIdentity;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Receives and dispatches inbound network messages with configurable processing modes.
 * Handles message routing, job queuing, and concurrent processing for efficient inbound traffic management.
 *
 * <p>Dispatching can operate in different modes configured by transport layer:
 * - Direct dispatch on caller thread (low latency),
 * - Queued dispatch with threaded processing for higher concurrency.</p>
 *
 * <p>Thread-safe queues from java.util.concurrent provide efficient,
 * lock-free concurrency, minimizing blocking and wake overhead.</p>
 */
public class InNetMessagePool implements Service {
    private final Log _log;
    private final RouterContext _context;
    private final HandlerJobBuilder[] _handlerJobBuilders;

    /**
     * Concurrent queues for pending messages and their sender hashes.
     * Used only when dispatching is not direct.
     */
    private final LinkedBlockingQueue<I2NPMessage> _pendingDataMessages;
    private final LinkedBlockingQueue<Hash> _pendingDataMessagesFrom;
    private final LinkedBlockingQueue<I2NPMessage> _pendingGatewayMessages;

    private SharedShortCircuitDataJob _shortCircuitDataJob;
    private SharedShortCircuitGatewayJob _shortCircuitGatewayJob;

    private final AtomicBoolean _alive = new AtomicBoolean(false);
    private final boolean _dispatchThreaded;

    /** Config flags for dispatch mode */
    private static final String PROP_DISPATCH_THREADED = "router.dispatchThreaded";
    private static final boolean DEFAULT_DISPATCH_THREADED = false;

    private static final String PROP_DISPATCH_DIRECT = "router.dispatchDirect";
    private static final boolean DEFAULT_DISPATCH_DIRECT = true;

    private final boolean DISPATCH_DIRECT;

    /** Maximum valid I2NP message type */
    private static final int MAX_I2NP_MESSAGE_TYPE = 31;
    private static final int MAX_DELIVERY_SKEW = 5000; // 5s latitude
    private static final long[] RATES = RateConstants.BASIC_RATES;

    /**
     * Constructs the message pool with given context.
     * Initializes message queues according to configuration properties.
     *
     * @param context router context for environment access
     */
    public InNetMessagePool(RouterContext context) {
        _context = context;
        _handlerJobBuilders = new HandlerJobBuilder[MAX_I2NP_MESSAGE_TYPE + 1];
        _dispatchThreaded = _context.getProperty(PROP_DISPATCH_THREADED, DEFAULT_DISPATCH_THREADED);
        DISPATCH_DIRECT = !_dispatchThreaded && _context.getProperty(PROP_DISPATCH_DIRECT, DEFAULT_DISPATCH_DIRECT);

        if (DISPATCH_DIRECT) {
            _pendingDataMessages = null;
            _pendingDataMessagesFrom = null;
            _pendingGatewayMessages = null;
        } else {
            _pendingDataMessages = new LinkedBlockingQueue<>();
            _pendingDataMessagesFrom = new LinkedBlockingQueue<>();
            _pendingGatewayMessages = new LinkedBlockingQueue<>();
            _shortCircuitDataJob = new SharedShortCircuitDataJob(context);
            _shortCircuitGatewayJob = new SharedShortCircuitGatewayJob(context);
        }

        _log = _context.logManager().getLog(InNetMessagePool.class);
        _context.statManager().createRateStat("inNetPool.dropped", "How often we drop a message", "InNetPool", RATES);
        _context.statManager().createRateStat("inNetPool.droppedDeliveryStatusDelay", "Notification latency for dropped messages (ms)", "InNetPool", RATES);
        _context.statManager().createRateStat("inNetPool.duplicate", "How often we receive a duplicate message", "InNetPool", RATES);
        _context.statManager().createRateStat("inNetPool.droppedDbLookupResponseMessage", "Frequency of DbLookup response drops", "InNetPool", RATES);
    }

    /**
     * Registers or replaces a HandlerJobBuilder for a given message type.
     *
     * @param i2npMessageType I2NP message type (0 to MAX_I2NP_MESSAGE_TYPE)
     * @param builder handler job builder for this type
     * @return previous builder registered for this type, or null if none
     * @throws ArrayIndexOutOfBoundsException if message type out of bounds
     */
    public synchronized HandlerJobBuilder registerHandlerJobBuilder(int i2npMessageType, HandlerJobBuilder builder) {
        HandlerJobBuilder old = _handlerJobBuilders[i2npMessageType];
        _handlerJobBuilders[i2npMessageType] = builder;
        return old;
    }

    /**
     * Adds a new inbound message to be processed.
     *
     * <p>The message is either dispatched directly or queued for processing,
     * depending on configuration. It supports detection of duplicates, expired
     * messages, and dispatches messages to appropriate handlers or jobs.</p>
     *
     * @param messageBody the inbound message to add, not null
     * @param fromRouter the router identity the message is from, may be null
     * @param fromRouterHash the hash of the router it is from, may be null (will be computed if so)
     * @return -1 if message dropped due to validation or errors, 0 otherwise
     */
    public int add(I2NPMessage messageBody, RouterIdentity fromRouter, Hash fromRouterHash) {
        return add(messageBody, fromRouter, fromRouterHash, 0);
    }

    /**
     * Adds a new inbound message with optional bloom filter XOR id.
     *
     * @param messageBody message to add (non-null)
     * @param fromRouter source router identity (may be null)
     * @param fromRouterHash source router hash (may be null, computed if null)
     * @param msgIDBloomXor XOR value for message unique ID in bloom filter
     * @return -1 if invalid or duplicate message, 0 otherwise
     */
    public int add(I2NPMessage messageBody, RouterIdentity fromRouter, Hash fromRouterHash, long msgIDBloomXor) {
        final MessageHistory history = _context.messageHistory();
        final boolean doHistory = history.getDoLog();

        long exp = messageBody.getMessageExpiration();

        if (_log.shouldDebug()) {
            _log.debug("Received " + messageBody.getClass().getSimpleName() +
                " [MsgID " + messageBody.getUniqueId() + "] " +
                " [XOR MsgID " + messageBody.getUniqueId(msgIDBloomXor) + "]" +
                "\n* Expires: " + new Date(exp));
        }

        int type = messageBody.getType();
        String invalidReason = null;
        if (type == TunnelDataMessage.MESSAGE_TYPE) {
            invalidReason = _context.messageValidator().validateMessage(exp);
        } else {
            invalidReason = _context.messageValidator().validateMessage(messageBody.getUniqueId(msgIDBloomXor), exp);
        }

        if (invalidReason != null) {
            handleInvalidMessage(messageBody, invalidReason, fromRouter, fromRouterHash, doHistory, msgIDBloomXor);
            return -1;
        }

        boolean jobFound = false;
        boolean allowMatches = true;

        switch (type) {
            case TunnelGatewayMessage.MESSAGE_TYPE:
                shortCircuitTunnelGateway(messageBody);
                allowMatches = false;
                break;
            case TunnelDataMessage.MESSAGE_TYPE:
                shortCircuitTunnelData(messageBody, fromRouterHash);
                allowMatches = false;
                break;
            case DatabaseStoreMessage.MESSAGE_TYPE:
                jobFound = processDatabaseStoreMessage(messageBody, fromRouter, fromRouterHash, doHistory);
                allowMatches = false;
                break;
            default:
                if (type > 0 && type < _handlerJobBuilders.length) {
                    HandlerJobBuilder builder = _handlerJobBuilders[type];
                    if (_log.shouldDebug()) {
                        _log.debug("Adding " + messageBody.getClass().getSimpleName() + " to the pool \n* Builder: " + builder);
                    }
                    if (builder != null) {
                        Job job = builder.createJob(messageBody, fromRouter, fromRouterHash);
                        if (job != null) {
                            _context.jobQueue().addJob(job);
                            jobFound = true;
                        }
                    }
                }
                break;
        }

        if (allowMatches) {
            int replies = handleReplies(messageBody);
            if (replies <= 0 && !jobFound) {
                handleDroppedUnmatchedMessage(messageBody, fromRouter, fromRouterHash, doHistory);
            } else if (doHistory) {
                history.receiveMessage(messageBody.getClass().getName(), messageBody.getUniqueId(msgIDBloomXor),
                    messageBody.getMessageExpiration(), fromRouterHash, true);
            }
        } else if (doHistory) {
            history.receiveMessage(messageBody.getClass().getName(), messageBody.getUniqueId(msgIDBloomXor),
                messageBody.getMessageExpiration(), fromRouterHash, true);
        }
        return 0;
    }

    /**
     * Handles invalid or duplicate messages by logging, statistics updates,
     * and history recording.
     */
    private void handleInvalidMessage(I2NPMessage messageBody, String invalidReason, RouterIdentity fromRouter, Hash fromRouterHash, boolean doHistory, long msgIDBloomXor) {
        if (_log.shouldInfo()) {
            _log.warn("Dropping DbLookupMessage [XOR MsgID " + messageBody.getUniqueId(msgIDBloomXor) + "] -> " +
                invalidReason.substring(0, 1).toUpperCase() + invalidReason.substring(1) +
                messageBody + "\n* Expires: " + new Date(messageBody.getMessageExpiration()));
        } else if (_log.shouldWarn()) {
            _log.warn("Dropping DbLookupMessage for " + messageBody + " -> " +
                      invalidReason.substring(0, 1).toUpperCase() + invalidReason.substring(1));
        }
        _context.statManager().addRateData("inNetPool.dropped", 1);
        if (!invalidReason.contains("expire")) {
            _context.statManager().addRateData("inNetPool.duplicate", 1);
        }
        if (doHistory) {
            MessageHistory history = _context.messageHistory();
            history.droppedOtherMessage(messageBody, fromRouter != null ? fromRouter.calculateHash() : fromRouterHash);
            history.messageProcessingError(messageBody.getUniqueId(msgIDBloomXor),
                    messageBody.getClass().getSimpleName(), "Duplicate/expired");
        }
    }

    /**
     * Processes DatabaseStoreMessage types by running or queueing corresponding jobs.
     */
    private boolean processDatabaseStoreMessage(I2NPMessage messageBody, RouterIdentity fromRouter, Hash fromRouterHash, boolean doHistory) {
        List<OutNetMessage> origMessages = _context.messageRegistry().getOriginalMessages(messageBody);
        HandlerJobBuilder dsmbuilder = _handlerJobBuilders[DatabaseStoreMessage.MESSAGE_TYPE];
        Job dsmjob = dsmbuilder != null ? dsmbuilder.createJob(messageBody, fromRouter, fromRouterHash) : null;
        int sz = origMessages.size();

        if (sz > 0) {
            DatabaseStoreMessage dbsm = (DatabaseStoreMessage) messageBody;
            dbsm.setReceivedAsReply();
            if (dsmjob != null) dsmjob.runJob();
            for (OutNetMessage omsg : origMessages) {
                ReplyJob job = omsg.getOnReplyJob();
                if (job != null) {
                    if (_log.shouldLog(Log.DEBUG)) {
                        _log.debug("Setting ReplyJob (" + job + ") for original message: " + omsg +
                            "; with reply message [id: " + messageBody.getUniqueId() + " Class: " +
                            messageBody.getClass().getSimpleName() + "] full message: " + messageBody);
                    } else if (_log.shouldLog(Log.INFO)) {
                        _log.info("Setting a ReplyJob (" + job + ") for original message class " +
                            omsg.getClass().getSimpleName() + " with reply message class " +
                            messageBody.getClass().getSimpleName());
                    }
                    job.setMessage(messageBody);
                    _context.jobQueue().addJob(job);
                }
            }
        } else {
            if (dsmjob != null) {
                _context.jobQueue().addJob(dsmjob);
            }
        }
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Finished processing DeliveryStatusMessage (allowMatches = false) from Router " + fromRouter +
                " / " + fromRouterHash + " origMessages.size() = " + sz + " message: " + messageBody);
        }
        return true; // jobFound
    }

    /**
     * Handles dropping messages that are not matched or handled.
     */
    private void handleDroppedUnmatchedMessage(I2NPMessage messageBody, RouterIdentity fromRouter, Hash fromRouterHash, boolean doHistory) {
        MessageHistory history = _context.messageHistory();

        if (doHistory)
            history.droppedOtherMessage(messageBody, fromRouter != null ? fromRouter.calculateHash() : fromRouterHash);

        switch (messageBody.getType()) {
            case DeliveryStatusMessage.MESSAGE_TYPE: {
                long now = _context.clock().now();
                long arr = ((DeliveryStatusMessage) messageBody).getArrival();
                long timeDiff = Math.abs(now - arr);
                if (timeDiff > MAX_DELIVERY_SKEW) {
                    long timeSinceSent = now - arr;
                    if (_log.shouldWarn())
                        _log.warn("Dropping unhandled DeliveryStatusMessage" + messageBody);
                    _context.statManager().addRateData("inNetPool.droppedDeliveryStatusDelay", timeSinceSent);
                }
                break;
            }
            case DatabaseSearchReplyMessage.MESSAGE_TYPE:
                // Normal case, do not log a warning for dropped DSRM
                break;
            case DatabaseLookupMessage.MESSAGE_TYPE:
                if (_log.shouldDebug())
                    _log.debug("Dropping NetDb lookup due to throttling...");
                break;
            default:
                if (_log.shouldWarn()) {
                    _log.warn("Message expiring on " + messageBody.getMessageExpiration() +
                        " was not handled by a HandlerJobBuilder -> DROPPING: " + messageBody);
                }
                _context.statManager().addRateData("inNetPool.dropped", 1);
                break;
        }
    }

    /**
     * Handles replies for outbound messages that correspond to this inbound message.
     *
     * @param messageBody inbound message for which replies are handled
     * @return count of original messages replied to
     */
    public int handleReplies(I2NPMessage messageBody) {
        List<OutNetMessage> origMessages = _context.messageRegistry().getOriginalMessages(messageBody);
        int sz = origMessages.size();
        if (sz <= 0)
            return 0;

        for (OutNetMessage omsg : origMessages) {
            ReplyJob job = omsg.getOnReplyJob();
            if (job != null) {
                job.setMessage(messageBody);
                _context.jobQueue().addJob(job);
            }
        }
        return sz;
    }

    /**
     * Shortcut dispatch tunnel gateway message either directly or queued.
     * @param messageBody the tunnel gateway message
     */
    private void shortCircuitTunnelGateway(I2NPMessage messageBody) {
        if (DISPATCH_DIRECT) {
            doShortCircuitTunnelGateway(messageBody);
        } else {
            _pendingGatewayMessages.offer(messageBody);
            if (!_dispatchThreaded)
                _context.jobQueue().addJob(_shortCircuitGatewayJob);
        }
    }

    /**
     * Actual direct dispatch of a tunnel gateway message.
     * @param messageBody the tunnel gateway message
     */
    private void doShortCircuitTunnelGateway(I2NPMessage messageBody) {
        _context.tunnelDispatcher().dispatch((TunnelGatewayMessage) messageBody);
    }

    /**
     * Shortcut dispatch tunnel data message either directly or queued.
     * @param messageBody  the tunnel data message
     * @param from         source router hash
     */
    private void shortCircuitTunnelData(I2NPMessage messageBody, Hash from) {
        if (DISPATCH_DIRECT) {
            doShortCircuitTunnelData(messageBody, from);
        } else {
            _pendingDataMessages.offer(messageBody);
            _pendingDataMessagesFrom.offer(from);
            if (!_dispatchThreaded)
                _context.jobQueue().addJob(_shortCircuitDataJob);
        }
    }

    /**
     * Actual direct dispatch of a tunnel data message.
     * @param messageBody  the tunnel data message
     * @param from         source router hash
     */
    private void doShortCircuitTunnelData(I2NPMessage messageBody, Hash from) {
        _context.tunnelDispatcher().dispatch((TunnelDataMessage) messageBody, from);
    }

    /**
     * Render the status of this message pool as HTML.
     * Package-private for use by the router console.
     *
     * @param out the Writer to output HTML to
     */
    @Override
    public void renderStatusHTML(Writer out) {
        // No status rendering in this version
    }

    /**
     * Restart the message pool by shutting down and then starting up again.
     * Clears all queued messages and reinitializes the dispatcher.
     */
    public void restart() {
        synchronized (this) {
            shutdown();
        }
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
        synchronized (this) {
            startup();
        }
    }

    /**
     * Shutdown the message pool, stopping all dispatchers and clearing queued messages.
     */
    public synchronized void shutdown() {
        _alive.set(false);
        if (!DISPATCH_DIRECT) {
            _pendingDataMessages.clear();
            _pendingDataMessagesFrom.clear();
            _pendingGatewayMessages.clear();
        }
    }

    /**
     * Startup the message pool and, if configured, start dispatcher threads.
     */
    public synchronized void startup() {
        _alive.set(true);
        if (_dispatchThreaded) {
            _context.statManager().createRateStat("pool.dispatchDataTime", "How long a tunnel dispatch takes", "Tunnels",
                    new long[] {10 * 60 * 1000L, 60 * 60 * 1000L, 24 * 60 * 60 * 1000L});
            _context.statManager().createRateStat("pool.dispatchGatewayTime", "How long a tunnel gateway dispatch takes", "Tunnels",
                    new long[] {10 * 60 * 1000L, 60 * 60 * 1000L, 24 * 60 * 60 * 1000L});
            I2PThread dataThread = new I2PThread(new TunnelDataDispatcher(), "Tunnel Data Dispatcher");
            dataThread.setDaemon(true);
            dataThread.start();
            I2PThread gatewayThread = new I2PThread(new TunnelGatewayDispatcher(), "Tunnel Gateway Dispatcher");
            gatewayThread.setDaemon(true);
            gatewayThread.start();
        }
    }

    /**
     * Job to sequentially dispatch all queued tunnel data messages.
     * Only used when dispatch not direct and not threaded.
     */
    private class SharedShortCircuitDataJob extends JobImpl {
        public SharedShortCircuitDataJob(RouterContext ctx) { super(ctx); }

        @Override
        public String getName() { return "Dispatch Tunnel Participant Message"; }

        @Override
        public void runJob() {
            I2NPMessage msg = _pendingDataMessages.poll();
            Hash from = _pendingDataMessagesFrom.poll();
            if (msg != null)
                doShortCircuitTunnelData(msg, from);
            if (!_pendingDataMessages.isEmpty())
                getContext().jobQueue().addJob(this);
        }
    }

    /**
     * Job to sequentially dispatch all queued tunnel gateway messages.
     * Only used when dispatch not direct and not threaded.
     */
    private class SharedShortCircuitGatewayJob extends JobImpl {
        public SharedShortCircuitGatewayJob(RouterContext ctx) { super(ctx); }

        @Override
        public String getName() { return "Dispatch Tunnel Gateway Message"; }

        @Override
        public void runJob() {
            I2NPMessage msg = _pendingGatewayMessages.poll();
            if (msg != null)
                doShortCircuitTunnelGateway(msg);
            if (!_pendingGatewayMessages.isEmpty())
                getContext().jobQueue().addJob(this);
        }
    }

    /**
     * Dispatcher thread that consumes queued tunnel gateway messages.
     * Only used when dispatchThreaded == true.
     */
    private class TunnelGatewayDispatcher implements Runnable {
        @Override
        public void run() {
            while (_alive.get()) {
                try {
                    I2NPMessage msg = _pendingGatewayMessages.take(); // blocks until available
                    long before = _context.clock().now();
                    doShortCircuitTunnelGateway(msg);
                    long elapsed = _context.clock().now() - before;
                    _context.statManager().addRateData("pool.dispatchGatewayTime", elapsed);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (!_alive.get())
                        break;
                } catch (RuntimeException e) {
                    if (_log.shouldLog(Log.CRIT))
                        _log.log(Log.CRIT, "Error in the Tunnel Gateway Dispatcher", e);
                }
            }
        }
    }

    /**
     * Dispatcher thread that consumes queued tunnel data messages.
     * Only used when dispatchThreaded == true.
     */
    private class TunnelDataDispatcher implements Runnable {
        @Override
        public void run() {
            while (_alive.get()) {
                try {
                    I2NPMessage msg = _pendingDataMessages.take(); // blocks until available
                    Hash from = _pendingDataMessagesFrom.take();   // blocks until available
                    long before = _context.clock().now();
                    doShortCircuitTunnelData(msg, from);
                    long elapsed = _context.clock().now() - before;
                    _context.statManager().addRateData("pool.dispatchDataTime", elapsed);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (!_alive.get())
                        break;
                } catch (RuntimeException e) {
                    if (_log.shouldLog(Log.CRIT))
                        _log.log(Log.CRIT, "Error in the Tunnel Data Dispatcher", e);
                }
            }
        }
    }
}