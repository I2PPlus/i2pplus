package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.Writer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
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
 * Manage a pool of inbound InNetMessages.  This pool is filled by the
 * Network communication system when it receives messages, and various jobs
 * periodically retrieve them for processing.
 *
 * Actually, this doesn't 'pool' anything, since DISPATCH_DIRECT = true.
 */
public class InNetMessagePool implements Service {
    private final Log _log;
    private final RouterContext _context;
    private final HandlerJobBuilder _handlerJobBuilders[];

    /** following 5 unused unless DISPATCH_DIRECT == false */
    private final List<I2NPMessage> _pendingDataMessages;
    private final List<Hash> _pendingDataMessagesFrom;
    private final List<I2NPMessage> _pendingGatewayMessages;
    private SharedShortCircuitDataJob _shortCircuitDataJob;
    private SharedShortCircuitGatewayJob _shortCircuitGatewayJob;

    private boolean _alive;
    private final boolean _dispatchThreaded;

    /** Make this >= the max I2NP message type number (currently 24) */
    private static final int MAX_I2NP_MESSAGE_TYPE = 31;

    /**
     * If set to true, we will have two additional threads - one for dispatching
     * tunnel data messages, and another for dispatching tunnel gateway messages.
     * These will not use the JobQueue but will operate sequentially.  Otherwise,
     * if this is set to false, the messages will be queued up in the jobQueue,
     * using the jobQueue's single thread.
     *
     * There's three possible cases that might work:
     * DISPATCH_DIRECT=true (default, unqueued)
     * DISPATCH_DIRECT=false with router.dispatchThreaded=false (job queue)
     * DISPATCH_DIRECT=false with router.dispatchThreaded=true (INMP queue)
     *
     * The fourth case, DISPATCH_DIRECT=true with router.dispatchThreaded=true
     * will never work, is now prevented below.
     *
     * Both must be configured before starting.
     * Changing defaults not recommended, non-default code to be removed.
     * Ref: Ticket 2688
     */
    private static final String PROP_DISPATCH_THREADED = "router.dispatchThreaded";
    private static final boolean DEFAULT_DISPATCH_THREADED = false;
    /**
     * If we aren't doing threaded dispatch for tunnel messages, should we
     * call the actual dispatch() method inline (on the same thread which
     * called add())?  If false, we queue it up in a shared short circuit
     * job.
     *
     * false = job queue
     * true = INMP queue
     */
    private static final String PROP_DISPATCH_DIRECT = "router.dispatchDirect";
    private static final boolean DEFAULT_DISPATCH_DIRECT = true;
    private final boolean DISPATCH_DIRECT;

    public InNetMessagePool(RouterContext context) {
        _context = context;
        _handlerJobBuilders = new HandlerJobBuilder[MAX_I2NP_MESSAGE_TYPE + 1];
        _dispatchThreaded = _context.getProperty(PROP_DISPATCH_THREADED, DEFAULT_DISPATCH_THREADED);
        // must be false if threaded is true
        DISPATCH_DIRECT = !_dispatchThreaded && _context.getProperty(PROP_DISPATCH_DIRECT, DEFAULT_DISPATCH_DIRECT);
        if (DISPATCH_DIRECT) {
            // keep the compiler happy since they are final
            _pendingDataMessages = null;
            _pendingDataMessagesFrom = null;
            _pendingGatewayMessages = null;
        } else {
            _pendingDataMessages = new ArrayList<I2NPMessage>(16);
            _pendingDataMessagesFrom = new ArrayList<Hash>(16);
            _pendingGatewayMessages = new ArrayList<I2NPMessage>(16);
            _shortCircuitDataJob = new SharedShortCircuitDataJob(context);
            _shortCircuitGatewayJob = new SharedShortCircuitGatewayJob(context);
        }
        _log = _context.logManager().getLog(InNetMessagePool.class);
        _context.statManager().createRateStat("inNetPool.dropped", "How often we drop a message", "InNetPool", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("inNetPool.droppedDeliveryStatusDelay", "Notification latency for dropped messages (ms)", "InNetPool", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("inNetPool.duplicate", "How often we receive a duplicate message", "InNetPool", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("inNetPool.droppedDbLookupResponseMessage", "Frequency of DbLookup response drops", "InNetPool", new long[] { 60*1000, 60*60*1000l });
    }

    /**
     * @return previous builder for this message type, or null
     * @throws ArrayIndexOutOfBoundsException if i2npMessageType is greater than MAX_I2NP_MESSAGE_TYPE
     */
    public synchronized HandlerJobBuilder registerHandlerJobBuilder(int i2npMessageType, HandlerJobBuilder builder) {
        HandlerJobBuilder old = _handlerJobBuilders[i2npMessageType];
        _handlerJobBuilders[i2npMessageType] = builder;
        return old;
    }

    /**
     * Add a new message to the pool.
     * If there is a HandlerJobBuilder for the inbound message type, the message
     * is loaded into a job created by that builder and queued up for processing instead
     * (though if the builder doesn't create a job, it is added to the pool).
     * Equivalent to the 4-argument version with a 0-value msgIDBloomXor
     *
     * @param messageBody non-null
     * @param fromRouter may be null
     * @param fromRouterHash may be null, calculated from fromRouter if null
     *
     * @return -1 for some types of errors but not all; 0 otherwise
     *         (was queue length, long ago)
     */
    public int add(I2NPMessage messageBody, RouterIdentity fromRouter, Hash fromRouterHash) {
        return add(messageBody, fromRouter, fromRouterHash, 0);
    }

    /**
     * Add a new message to the pool.
     * If there is a HandlerJobBuilder for the inbound message type, the message is loaded
     * into a job created by that builder and queued up for processing instead
     * (though if the builder doesn't create a job, it is added to the pool)
     * if msgIDBloomXor is 0 the Xor factor is 0, therefore the ID is returned unchanged.
     *
     * @param messageBody non-null
     * @param fromRouter may be null
     * @param fromRouterHash may be null, calculated from fromRouter if null
     * @param msgIDBloomXor constant value to XOR with the messageID before passing to the bloom filter.
     *
     * @return -1 for some types of errors but not all; 0 otherwise (was queue length, long ago)
     */
    public int add(I2NPMessage messageBody,
                   RouterIdentity fromRouter,
                   Hash fromRouterHash,
                   long msgIDBloomXor) {
        final MessageHistory history = _context.messageHistory();
        final boolean doHistory = history.getDoLog();

        long exp = messageBody.getMessageExpiration();

        if (_log.shouldDebug())
                _log.debug("Received " + messageBody.getClass().getSimpleName() +
                           " [MsgID " + messageBody.getUniqueId() + "] " +
                           " [XOR MsgID " + messageBody.getUniqueId(msgIDBloomXor) + "]" +
                           "\n* Expires: " + new Date(exp));

        int type = messageBody.getType();
        String invalidReason = null;
        if (type == TunnelDataMessage.MESSAGE_TYPE) {
            // the IV validator is sufficient for dup detection on tunnel messages, so
            // just validate the expiration
            invalidReason = _context.messageValidator().validateMessage(exp);
        } else {
            invalidReason = _context.messageValidator().validateMessage(messageBody.getUniqueId(msgIDBloomXor), exp);
        }

        if (invalidReason != null) {
            if (_log.shouldInfo()) {
                _log.warn("Dropping DbLookupMessage [XOR MsgID " + messageBody.getUniqueId(msgIDBloomXor) +
                          "] -> " + invalidReason.substring(0, 1).toUpperCase() + invalidReason.substring(1) +
                          messageBody + "\n* Expires: " + new Date(exp));
            } else if (_log.shouldWarn()) {
                _log.warn("Dropping DbLookupMessage for " + messageBody + " -> " +
                          invalidReason.substring(0, 1).toUpperCase() + invalidReason.substring(1));
            }
            _context.statManager().addRateData("inNetPool.dropped", 1);
            // FIXME not necessarily a duplicate, could be expired too long ago / too far in future
            if (!invalidReason.contains("expire")) {
                _context.statManager().addRateData("inNetPool.duplicate", 1);
            }
            if (doHistory) {
                history.droppedOtherMessage(messageBody, (fromRouter != null ? fromRouter.calculateHash() : fromRouterHash));
                history.messageProcessingError(messageBody.getUniqueId(msgIDBloomXor),
                                               messageBody.getClass().getSimpleName(),
                                               "Duplicate/expired");
            }
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

          // If a DSM has a reply job, run the DSM inline
          // so the entry is stored in the netdb before the reply job runs.
          // FloodOnlyLookupMatchJob no longer stores the entry
          case DatabaseStoreMessage.MESSAGE_TYPE:
              List<OutNetMessage> origMessages = _context.messageRegistry().getOriginalMessages(messageBody);
              HandlerJobBuilder dsmbuilder = _handlerJobBuilders[DatabaseStoreMessage.MESSAGE_TYPE];
              Job dsmjob = dsmbuilder.createJob(messageBody, fromRouter, fromRouterHash);
              int sz = origMessages.size();
              if (sz > 0) {
               DatabaseStoreMessage dbsm = (DatabaseStoreMessage) messageBody;
               dbsm.setReceivedAsReply();
                  // DSM inline, reply jobs on queue
                  if (dsmjob != null)
                      dsmjob.runJob();
                  for (int i = 0; i < sz; i++) {
                       OutNetMessage omsg = origMessages.get(i);
                       ReplyJob job = omsg.getOnReplyJob();
                       if (job != null) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Setting ReplyJob ("
                                       + job + ") for original message:"
                                       + omsg + "; with reply message [id: "
                                       + messageBody.getUniqueId()
                                       + " Class: "
                                       + messageBody.getClass().getSimpleName()
                                       + "] full message: " + messageBody);
                        else if (_log.shouldLog(Log.INFO))
                            _log.info("Setting a ReplyJob ("
                                      + job + ") for original message class "
                                      + omsg.getClass().getSimpleName()
                                      + " with reply message class "
                                      + messageBody.getClass().getSimpleName());
                           job.setMessage(messageBody);
                           _context.jobQueue().addJob(job);
                       }
                  }
              } else {
                  // DSM on queue, no reply jobs
                  if (dsmjob != null)
                      _context.jobQueue().addJob(dsmjob);
              }
              allowMatches = false;
              if (_log.shouldLog(Log.DEBUG))
                  _log.debug("Finished processing DeliveryStatusMessage (allowMatches = false) from Router "
                             + fromRouter + " / " + fromRouterHash
                             + " origMessages.size() = " + sz
                             + " message: " + messageBody);
              break;

          default:
            // why don't we allow type 0? There used to be a message of type 0 long ago...
            if ( (type > 0) && (type < _handlerJobBuilders.length) ) {
                HandlerJobBuilder builder = _handlerJobBuilders[type];

                if (_log.shouldDebug())
                    _log.debug("Adding " + messageBody.getClass().getSimpleName() + " to the pool \n* Builder: " + builder);

                if (builder != null) {
                    Job job = builder.createJob(messageBody, fromRouter,
                                                fromRouterHash);
                    if (job != null) {
                        _context.jobQueue().addJob(job);
                    } else {
                        // ok, we may not have *found* a job, per se, but we could have, the
                        // job may have just executed inline
                    }
                    jobFound = true;
                }
            }
            break;
        } // switch

        if (allowMatches) {
            int replies = handleReplies(messageBody);

            if (replies <= 0) {
                // not handled as a reply
                if (!jobFound) {
                    // was not handled via HandlerJobBuilder
                    if (doHistory)
                        history.droppedOtherMessage(messageBody, (fromRouter != null ? fromRouter.calculateHash() : fromRouterHash));

                    switch (type) {
                      case DeliveryStatusMessage.MESSAGE_TYPE:
                        // Avoid logging side effect from a horrible UDP EstablishmentManager hack
                        // We could set up a separate stat for it but don't bother for now
                        long arr = ((DeliveryStatusMessage)messageBody).getArrival();
                        if (arr > 10) {
                            long timeSinceSent = _context.clock().now() - arr;
                            if (_log.shouldWarn())
                                _log.warn("Dropping unhandled DeliveryStatusMessage " + messageBody);
                            _context.statManager().addRateData("inNetPool.droppedDeliveryStatusDelay", timeSinceSent);
                        }
                        break;

                      case DatabaseSearchReplyMessage.MESSAGE_TYPE:
                        /*
                         * This is normal.
                         * The three netdb selectors:
                         * FloodOnlyLookupSelector, IterativeLookupSelector, and SearchMessageSelector
                         * never return true from isMatch() for a DSRM.
                         * IterativeLookupSelector.isMatch() queues a new IterativeLookupJob
                         * to fetch the responses.
                         */
                        break;

                      case DatabaseLookupMessage.MESSAGE_TYPE:
                        if (_log.shouldDebug())
                            _log.debug("Dropping NetDb lookup due to throttling");
                        break;

                      default:
                        if (_log.shouldWarn())
                            _log.warn("Message expiring on " + messageBody.getMessageExpiration() +
                                      " was not handled by a HandlerJobBuilder -> DROPPING: " + messageBody);
                        _context.statManager().addRateData("inNetPool.dropped", 1);
                        break;
                    }  // switch
                } else {
                    if (doHistory) {
                        String mtype = messageBody.getClass().getName();
                        history.receiveMessage(mtype, messageBody.getUniqueId(msgIDBloomXor),
                                               messageBody.getMessageExpiration(),
                                               fromRouterHash, true);
                    }
                    return 0; // no queue
                }
            }
        }

        if (doHistory) {
            String mtype = messageBody.getClass().getName();
            history.receiveMessage(mtype, messageBody.getUniqueId(msgIDBloomXor),
                                   messageBody.getMessageExpiration(),
                                   fromRouterHash, true);
        }
        return 0; // no queue
    }

    public int handleReplies(I2NPMessage messageBody) {
        List<OutNetMessage> origMessages = _context.messageRegistry().getOriginalMessages(messageBody);
        int sz = origMessages.size();
        if (sz <= 0)
            return 0;
        //if (_log.shouldDebug()) {
        //    _log.debug("Original messages for inbound message: " + sz);
        //    if (sz > 1)
        //        _log.debug("Orig: " + origMessages + " \nthe above are replies for: " + messageBody);
        //}

        for (int i = 0; i < sz; i++) {
            OutNetMessage omsg = origMessages.get(i);
            ReplyJob job = omsg.getOnReplyJob();
            //if (_log.shouldDebug())
            //    _log.debug("Original message [" + i + "] " + omsg.getReplySelector() + " : " + omsg + ": reply job: " + job);

            if (job != null) {
                job.setMessage(messageBody);
                _context.jobQueue().addJob(job);
            }
        }
        return sz;
    }

    // the following short circuits the tunnel dispatching - i'm not sure whether
    // we'll want to run the dispatching in jobs or whether it shuold go inline with
    // others and/or on other threads (e.g. transport threads).  lets try 'em both.

    private void shortCircuitTunnelGateway(I2NPMessage messageBody) {
        if (DISPATCH_DIRECT) {
            doShortCircuitTunnelGateway(messageBody);
        } else {
            synchronized (_pendingGatewayMessages) {
                _pendingGatewayMessages.add(messageBody);
                _pendingGatewayMessages.notifyAll();
            }
            if (!_dispatchThreaded)
                _context.jobQueue().addJob(_shortCircuitGatewayJob);
        }
    }

    private void doShortCircuitTunnelGateway(I2NPMessage messageBody) {
        //if (_log.shouldDebug())
        //    _log.debug("Shortcut dispatch TunnelGatewayMessage " + messageBody);
        _context.tunnelDispatcher().dispatch((TunnelGatewayMessage)messageBody);
    }

    private void shortCircuitTunnelData(I2NPMessage messageBody, Hash from) {
        if (DISPATCH_DIRECT) {
            doShortCircuitTunnelData(messageBody, from);
        } else {
            synchronized (_pendingDataMessages) {
                _pendingDataMessages.add(messageBody);
                _pendingDataMessagesFrom.add(from);
                _pendingDataMessages.notifyAll();
                //_context.jobQueue().addJob(new ShortCircuitDataJob(_context, messageBody, from));
            }
            if (!_dispatchThreaded)
                _context.jobQueue().addJob(_shortCircuitDataJob);
        }
    }
    private void doShortCircuitTunnelData(I2NPMessage messageBody, Hash from) {
        //if (_log.shouldDebug())
        //    _log.debug("Shortcut dispatch of TunnelDataMessage " + messageBody);
        _context.tunnelDispatcher().dispatch((TunnelDataMessage)messageBody, from);
    }

    public void renderStatusHTML(Writer out) {}

    /** does nothing since we aren't threaded */
    public synchronized void restart() {
        shutdown();
        try { Thread.sleep(100); } catch (InterruptedException ie) {}
        startup();
    }

    /** does nothing since we aren't threaded */
    public synchronized void shutdown() {
        _alive = false;
        if (!DISPATCH_DIRECT) {
            synchronized (_pendingDataMessages) {
                _pendingDataMessages.clear();
                _pendingDataMessagesFrom.clear();
                _pendingDataMessages.notifyAll();
            }
        }
    }

    /** does nothing since we aren't threaded */
    public synchronized void startup() {
        _alive = true;
        if (_dispatchThreaded) {
            _context.statManager().createRateStat("pool.dispatchDataTime", "How long a tunnel dispatch takes", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
            _context.statManager().createRateStat("pool.dispatchGatewayTime", "How long a tunnel gateway dispatch takes", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
            I2PThread data = new I2PThread(new TunnelDataDispatcher(), "Tunnel Data Dispatcher");
            data.setDaemon(true);
            data.start();
            I2PThread gw = new I2PThread(new TunnelGatewayDispatcher(), "Tunnel Gateway Dispatcher");
            gw.setDaemon(true);
            gw.start();
        }
    }

    /** Unused unless DISPATCH_DIRECT == false */
    private class SharedShortCircuitDataJob extends JobImpl {
        public SharedShortCircuitDataJob(RouterContext ctx) {
            super(ctx);
        }
        public String getName() { return "Dispatch Tunnel Participant Message"; }
        public void runJob() {
            int remaining = 0;
            I2NPMessage msg = null;
            Hash from = null;
            synchronized (_pendingDataMessages) {
                if (!_pendingDataMessages.isEmpty()) {
                    msg = _pendingDataMessages.remove(0);
                    from = _pendingDataMessagesFrom.remove(0);
                }
                remaining = _pendingDataMessages.size();
            }
            if (msg != null)
                doShortCircuitTunnelData(msg, from);
            if (remaining > 0)
                getContext().jobQueue().addJob(SharedShortCircuitDataJob.this);
        }
    }

    /** unused unless DISPATCH_DIRECT == false */
    private class SharedShortCircuitGatewayJob extends JobImpl {
        public SharedShortCircuitGatewayJob(RouterContext ctx) {
            super(ctx);
        }
        public String getName() { return "Dispatch Tunnel Gateway Message"; }
        public void runJob() {
            I2NPMessage msg = null;
            int remaining = 0;
            synchronized (_pendingGatewayMessages) {
                if (!_pendingGatewayMessages.isEmpty())
                    msg = _pendingGatewayMessages.remove(0);
                remaining = _pendingGatewayMessages.size();
            }
            if (msg != null)
                doShortCircuitTunnelGateway(msg);
            if (remaining > 0)
                getContext().jobQueue().addJob(SharedShortCircuitGatewayJob.this);
        }
    }

    /** unused unless router.dispatchThreaded=true */
    private class TunnelGatewayDispatcher implements Runnable {
        public void run() {
            while (_alive) {
                I2NPMessage msg = null;
                try {
                    synchronized (_pendingGatewayMessages) {
                        if (_pendingGatewayMessages.isEmpty())
                            _pendingGatewayMessages.wait();
                        else
                            msg = _pendingGatewayMessages.remove(0);
                    }
                    if (msg != null) {
                        long before = _context.clock().now();
                        doShortCircuitTunnelGateway(msg);
                        long elapsed = _context.clock().now() - before;
                        _context.statManager().addRateData("pool.dispatchGatewayTime", elapsed);
                    }
                } catch (InterruptedException ie) {

                } catch (OutOfMemoryError oome) {
                    throw oome;
                } catch (RuntimeException e) {
                    if (_log.shouldLog(Log.CRIT))
                        _log.log(Log.CRIT, "Error in the Tunnel Gateway Dispatcher", e);
                }
            }
        }
    }

    /** unused unless router.dispatchThreaded=true */
    private class TunnelDataDispatcher implements Runnable {
        public void run() {
            while (_alive) {
                I2NPMessage msg = null;
                Hash from = null;
                try {
                    synchronized (_pendingDataMessages) {
                        if (_pendingDataMessages.isEmpty()) {
                            _pendingDataMessages.wait();
                        } else {
                            msg = _pendingDataMessages.remove(0);
                            from = _pendingDataMessagesFrom.remove(0);
                        }
                    }
                    if (msg != null) {
                        long before = _context.clock().now();
                        doShortCircuitTunnelData(msg, from);
                        long elapsed = _context.clock().now() - before;
                        _context.statManager().addRateData("pool.dispatchDataTime", elapsed);
                    }
                } catch (InterruptedException ie) {

                } catch (OutOfMemoryError oome) {
                    throw oome;
                } catch (RuntimeException e) {
                    if (_log.shouldLog(Log.CRIT))
                        _log.log(Log.CRIT, "Error in the Tunnel Data Dispatcher", e);
                }
            }
        }
    }
}
