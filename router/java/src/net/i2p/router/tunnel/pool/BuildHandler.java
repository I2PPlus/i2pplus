package net.i2p.router.tunnel.pool;

import static net.i2p.router.tunnel.pool.BuildExecutor.Result.*;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import net.i2p.crypto.EncType;
import net.i2p.data.DataHelper;
import net.i2p.data.EmptyProperties;
import net.i2p.stat.RateConstants;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.BuildRequestRecord;
import net.i2p.data.i2np.BuildResponseRecord;
import net.i2p.data.i2np.EncryptedBuildRecord;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.OutboundTunnelBuildReplyMessage;
import net.i2p.data.i2np.ShortTunnelBuildMessage;
import net.i2p.data.i2np.ShortTunnelBuildReplyMessage;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.data.i2np.TunnelBuildReplyMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.data.i2np.VariableTunnelBuildMessage;
import net.i2p.data.i2np.VariableTunnelBuildReplyMessage;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.BanLogger;
import net.i2p.router.RouterThrottleImpl;
import net.i2p.router.networkdb.kademlia.MessageWrapper;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.router.util.CDQEntry;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Handle the received tunnel build message requests and replies,
 * including sending responses and updating tunnel lists.
 */
class BuildHandler implements Runnable {
    private final RouterContext _context;
    private final Log _log;
    private static final BanLogger _banLogger = new BanLogger(null);
    private final TunnelPoolManager _manager;
    private final BuildExecutor _exec;
    private final Job _buildMessageHandlerJob;
    private final Job _buildReplyMessageHandlerJob;
    private final BlockingQueue<BuildMessageState> _inboundBuildMessages;
    private final BuildMessageProcessor _processor;
    private final RequestThrottler _requestThrottler;
    private final ParticipatingThrottler _throttler;
    private final BuildReplyHandler _buildReplyHandler;
    private final AtomicInteger _currentLookups = new AtomicInteger();
    private volatile boolean _isRunning;
    private final Object _startupLock = new Object();
    private ExplState _explState = ExplState.NONE;
    private final String MIN_VERSION_HONOR_CAPS = "0.9.58";
    private final static boolean DEFAULT_SHOULD_THROTTLE = true;
    private final static String PROP_SHOULD_THROTTLE = "router.enableTransitThrottle";
    private enum ExplState {NONE, IB, OB, BOTH}
    private static final boolean isSlow = SystemVersion.isSlow();
    /** TODO these may be too high, review and adjust */
    private static final int MIN_QUEUE = isSlow ? 16 : 32;
    private static final int MAX_QUEUE = isSlow ? 64 : 512;
    private static final String PROP_MAX_QUEUE = "router.buildHandlerMaxQueue";
    private static final int NEXT_HOP_LOOKUP_TIMEOUT = 30*1000;
    private static final int PRIORITY = OutNetMessage.PRIORITY_BUILD_REPLY;
    private static final int MIN_LOOKUP_LIMIT = isSlow ? 4 : 10; // limits on concurrent next-hop RI lookup
    private static final int MAX_LOOKUP_LIMIT = isSlow ? 10 : Math.max(SystemVersion.getCores() / 2, 16);
    private static final int PERCENT_LOOKUP_LIMIT = isSlow ? 15 : 40; // limit lookups to this % of current participating tunnels
    private static final long MAX_REQUEST_FUTURE = 5*60*1000;
    private static final long MAX_REQUEST_AGE = 65*60*1000; /** must be > 1 hour due to rounding down */
    private static final long MAX_REQUEST_AGE_ECIES = 8*60*1000;
    private static final long JOB_LAG_LIMIT_TUNNEL = isSlow ? 800 : 500;
    private static final long[] RATES = RateConstants.SHORT_TERM_RATES;

    // Global rate limiter for DDoS protection - tracks ALL tunnel build requests regardless of peer
    private static final String PROP_GLOBAL_RATE_LIMIT = "router.tunnelBuildGlobalRateLimit";
    private static final int DEFAULT_GLOBAL_RATE_LIMIT = isSlow ? 50 : 200; // per second
    private static final int GLOBAL_RATE_WINDOW_MS = 1000; // 1 second window
    private static final int GLOBAL_RATE_BUCKETS = 10; // 100ms per bucket
    private static final String PROP_GLOBAL_BURST_LIMIT = "router.tunnelBuildGlobalBurstLimit";
    private static final int DEFAULT_GLOBAL_BURST_LIMIT = isSlow ? 100 : 400; // absolute max in window

    private final GlobalRateLimiter _globalRateLimiter;

    /**
     * This is baseline minimum for estimating tunnel bandwidth, if accepted.
     * We use an estimate of 200 messages (1 KB each) in 10 minutes.
     *
     * 200 KB in 10 minutes equals 340 Bps - optimized for high bandwidth contexts.
     */
    private static final int DEFAULT_BW_PER_TUNNEL_ESTIMATE = RouterThrottleImpl.DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE * 4096 / (10*60);

    private static String formatBandwidth(int bps) {
        if (bps >= 1000000000) {
            return String.format("%.2fGB/s", bps / 1000000000.0);
        } else if (bps >= 1000000) {
            return String.format("%.2fMB/s", bps / 1000000.0);
        } else if (bps >= 1000) {
            return String.format("%.2fKB/s", bps / 1000.0);
        } else {
            return bps + "B/s";
        }
    }

    public BuildHandler(RouterContext ctx, TunnelPoolManager manager, BuildExecutor exec) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _manager = manager;
        _exec = exec;
        // Queue size = 12 * share BW / 48K
        //int sz = Math.min(MAX_QUEUE, Math.max(MIN_QUEUE, TunnelDispatcher.getShareBandwidth(ctx) * MIN_QUEUE / 48));
        int sz = ctx.getProperty(PROP_MAX_QUEUE, MAX_QUEUE);
        _inboundBuildMessages = new LinkedBlockingQueue<BuildMessageState>(sz);
        ctx.statManager().createRateStat("tunnel.buildLookupSuccess", "Confirmation of successful deferred lookup", "Tunnels", RATES);
        ctx.statManager().createRateStat("tunnel.buildReplyTooSlow", "Received a tunnel build reply after timeout", "Tunnels", RATES);
        ctx.statManager().createRateStat("tunnel.corruptBuildReply", "Corrupt tunnel build replies received", "Tunnels", RATES);
        ctx.statManager().createRateStat("tunnel.dropConnLimits", "Dropped not rejected tunnel build (connection limits)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.dropDecryptFail", "Dropped tunnel build (decryption failed)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.handleRemaining", "Waiting inbound requests after 1 pass", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.nextHopLookupTimeout", "Timeout for next hop lookup (ms)", "Tunnels", RATES);
        ctx.statManager().createRateStat("tunnel.receiveRejectionBandwidth", "Received tunnel build rejection (bandwidth overload)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.receiveRejectionCritical", "Received tunnel build rejection (critical failure)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.receiveRejectionProbabalistic", "Received tunnel build rejection probabalistically", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.receiveRejectionTransient", "Received tunnel build rejection (transient overload)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.reject.30", "Rejected a tunnel (bandwidth overload)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.rejectConnLimits", "Rejected tunnel build (connection limits)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.rejectFuture", "Rejected tunnel build (time in future)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.rejectTimeout2", "Rejected tunnel build (can't contact next hop)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.rejectTimeout", "Rejected tunnel build (unknown next hop)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.rejectTooOld", "Rejected tunnel build (too old)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.acceptLoad", "Delay processing accepted request (ms)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.decryptRequestTime", "Time to decrypt a build request (ms)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropLoadBacklog", "Pending request count when dropped", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropLoad", "Delay before dropping request (ms)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropLoadDelay", "Delay before abandoning request (ms)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropLoadProactiveAbort", "Allowed requests during load", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropLoadProactive", "Delay estimate when dropped (ms)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropLookupThrottle", "Dropped tunnel build (hop lookup limit)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropReqThrottle", "Dropped tunnel build (request limit)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.nextHopLookupSuccessTime", "Time taken for successful remote next hop lookup (ms)", "Tunnels", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.ownDupID", "Our tunnel dup. ID", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.rejectDupID", "Rejected tunnel build (duplicate ID)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.rejectHopThrottle", "Rejected tunnel build (per-hop limit)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.rejectHostile", "Rejected malicious tunnel build", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.rejectOverloaded", "Delay processing rejected request (ms)", "Tunnels [Participating]", RATES);
        _processor = new BuildMessageProcessor(ctx);
        boolean testMode = ctx.getBooleanProperty("i2np.allowLocal"); // previous hop, all requests
        boolean shouldThrottle = _context.getBooleanPropertyDefaultTrue(PROP_SHOULD_THROTTLE);
        _requestThrottler = testMode || !shouldThrottle ? null : new RequestThrottler(ctx);
        _throttler = testMode || !shouldThrottle ? null : new ParticipatingThrottler(ctx); // previous and next hops, successful builds only
        _globalRateLimiter = shouldThrottle ? new GlobalRateLimiter(ctx) : null;
        // Start idle tunnel monitor to detect and drop abusive idle tunnels
        if (shouldThrottle) {
            new IdleTunnelMonitor(ctx);
        }
        _buildReplyHandler = new BuildReplyHandler(ctx);
        _buildMessageHandlerJob = new TunnelBuildMessageHandlerJob(ctx);
        _buildReplyMessageHandlerJob = new TunnelBuildReplyMessageHandlerJob(ctx);
        TunnelBuildMessageHandlerJobBuilder tbmhjb = new TunnelBuildMessageHandlerJobBuilder();
        TunnelBuildReplyMessageHandlerJobBuilder tbrmhjb = new TunnelBuildReplyMessageHandlerJobBuilder();
        ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelBuildMessage.MESSAGE_TYPE, tbmhjb);
        ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelBuildReplyMessage.MESSAGE_TYPE, tbrmhjb);
        ctx.inNetMessagePool().registerHandlerJobBuilder(VariableTunnelBuildMessage.MESSAGE_TYPE, tbmhjb);
        ctx.inNetMessagePool().registerHandlerJobBuilder(VariableTunnelBuildReplyMessage.MESSAGE_TYPE, tbrmhjb);
        ctx.inNetMessagePool().registerHandlerJobBuilder(ShortTunnelBuildMessage.MESSAGE_TYPE, tbmhjb);
        ctx.inNetMessagePool().registerHandlerJobBuilder(OutboundTunnelBuildReplyMessage.MESSAGE_TYPE, tbrmhjb);
        _banLogger.initialize(ctx);
    }

    /**
     *  Call the same time you start the threads
     *
     *  @since 0.9.18
     */
    void init() {
        if (_context.commSystem().isDummy()) {
            _explState = ExplState.BOTH;
            _context.router().setExplTunnelsReady();
            return;
        }
        // fixup startup state if 0-hop exploratory is allowed in either direction
        int ibl = _manager.getInboundSettings().getLength();
        int ibv = _manager.getInboundSettings().getLengthVariance();
        int obl = _manager.getOutboundSettings().getLength();
        int obv = _manager.getOutboundSettings().getLengthVariance();
        boolean ibz = ibl <= 0 || ibl + ibv <= 0;
        boolean obz = obl <= 0 || obl + obv <= 0;
        if (ibz && obz) {
            _explState = ExplState.BOTH;
            _context.router().setExplTunnelsReady();
        } else if (ibz) {_explState = ExplState.IB;}
        else if (obz) {_explState = ExplState.OB;}
        if (_log.shouldInfo()) {
            _log.info("Starting next-hop timeout at " + (NEXT_HOP_LOOKUP_TIMEOUT / 1000.0) + "s");
        }
    }

    /**
     *  @since 0.9
     */
    public void restart() {_inboundBuildMessages.clear();}

    /**
     *  Cannot be restarted.
     *  @param numThreads the number of threads to be shut down
     *  @since 0.9
     */
    public synchronized void shutdown(int numThreads) {
        _isRunning = false;
        _inboundBuildMessages.clear();
        BuildMessageState poison = new BuildMessageState(_context, null, null, null);
        for (int i = 0; i < numThreads; i++) {_inboundBuildMessages.offer(poison);}
    }

    /**
     * Thread to handle inbound requests
     * @since 0.8.11
     */
    public void run() {
        _isRunning = true;
        while (_isRunning && !_manager.isShutdown()) {
            try {handleInboundRequest();}
            catch (RuntimeException e) {_log.log(Log.CRIT, "Catastrophic tunnel build failure! -> " +  e.getMessage());}
        }
        if (_log.shouldWarn()) {_log.warn("Completed handling Inbound build requests");}
        _isRunning = false;
    }

    /**
     * Blocking call to handle a single inbound request
     */
    private void handleInboundRequest() {
        BuildMessageState state = null;
        try {state = _inboundBuildMessages.take();}
        catch (InterruptedException ie) {return;}
        // check for poison
        if (state.msg == null) {_isRunning = false; return;}
        long now = System.currentTimeMillis();
        long uptime = _context.router().getUptime();
        long dropBefore = now - (BuildRequestor.REQUEST_TIMEOUT / 2); // 40s latitude
        String PROP_MAX_TUNNELS = _context.getProperty("router.maxParticipatingTunnels");
        int DEFAULT_MAX_TUNNELS = isSlow ? 4000 : 10000;
        int maxTunnels;
        if (PROP_MAX_TUNNELS != null) {maxTunnels = Integer.parseInt(PROP_MAX_TUNNELS);}
        else {maxTunnels = DEFAULT_MAX_TUNNELS;}
        long lag = _context.jobQueue().getMaxLag();
        boolean isLagged = lag > JOB_LAG_LIMIT_TUNNEL && maxTunnels > 0 && uptime > 5*60*1000;
        boolean highLoad = SystemVersion.getCPULoadAvg() > 98 && isLagged;
        if (state.recvTime <= dropBefore) {
            if (_log.shouldWarn()) {
                _log.warn("Not processing stale tunnel build request [MsgID " + state.msg.getUniqueId() + "]" +
                          " -> Request received " + (now - state.recvTime) + "ms ago");
            }
            _context.statManager().addRateData("tunnel.dropLoadDelay", now - state.recvTime);
            if (maxTunnels > 0) {
                _context.throttle().setTunnelStatus("[rejecting/overload]" + _x("Dropping Tunnel Requests: Too slow")
                                   .replace("tunnel requests:", "requests:"));
            }
            return;
        }
        if (isLagged) { // TODO reject instead of drop also for a lower limit? see throttle
            if (_log.shouldWarn()) {
                _log.warn("Dropping Tunnel Request -> Job lag (" + lag + "ms)");
                _context.throttle().setTunnelStatus("[rejecting/overload]" + _x("Dropping Tunnel Requests: High job lag")
                                   .replace("requests: ", "requests:<br>"));
            }
            _context.statManager().addRateData("router.throttleTunnelCause", lag);
            return;
        }
        if (highLoad && maxTunnels > 0) {
            if (_log.shouldWarn()) {
                _log.warn("Dropping Tunnel Request -> System under load");
                _context.throttle().setTunnelStatus("[rejecting/overload]" + _x("Dropping Tunnel Requests:<br>High CPU load"));
            }
            _context.statManager().addRateData("router.throttleTunnelCause", lag);
            return;
        }
        handleRequest(state, now);
    }

    /**
     * Async handler for a single inbound reply - queues work to prevent InNetMessagePool lag
     */
    private void handleReply(BuildReplyMessageState state) {
        // Queue async job to handle the heavy lifting (decrypt, profile updates)
        // This prevents InNetMessagePool thread from being blocked by crypto operations
        long replyMessageId = state.msg.getUniqueId();
        _context.jobQueue().addJob(new HandleBuildReplyJob(replyMessageId, state.msg, state.recvTime));
    }

    /**
     * Async job to handle tunnel build reply processing off the InNetMessagePool thread
     */
    private class HandleBuildReplyJob extends JobImpl {
        private final long _replyMessageId;
        private final TunnelBuildReplyMessage _msg;
        private final long _recvTime;

        HandleBuildReplyJob(long replyMessageId, TunnelBuildReplyMessage msg, long recvTime) {
            super(BuildHandler.this._context);
            _replyMessageId = replyMessageId;
            _msg = msg;
            _recvTime = recvTime;
            // Minimal stagger delay for tunnel build replies - they're time-critical
            long delay = _context.random().nextInt(100); // 0-100ms max
            getTiming().setStartAfter(_context.clock().now() + delay);
        }

        public String getName() {return "Handle Build Reply";}

        @Override
        public void runJob() {
            // Remove from building map - if null, tunnel already timed out
            PooledTunnelCreatorConfig cfg = _exec.removeFromBuilding(_replyMessageId);
            if (cfg == null) {
                if (_log.shouldWarn()) {
                    _log.warn("Reply [MsgID " + _replyMessageId + "] did not match any pending tunnels (timed out?)");
                }
                _context.statManager().addRateData("tunnel.buildReplyTooSlow", 1);
                return;
            }
            handleReplyAsync(_msg, cfg, System.currentTimeMillis() - _recvTime);
        }
    }

    /**
     * Blocking call to handle a single inbound reply (called from async job)
     */
    private void handleReplyAsync(TunnelBuildReplyMessage msg, PooledTunnelCreatorConfig cfg, long delay) {
        long requestedOn = cfg.getExpiration() - 10*60*1000;
        long rtt = System.currentTimeMillis() - requestedOn;
        if (_log.shouldInfo()) {
            _log.info("Handled reply [MsgID " + msg.getUniqueId() + "] in " + rtt + "ms -> " +
                      (delay > 0 ? "Waited " + delay + "ms for config \n* " : "") + cfg);
        }
        List<Integer> order = cfg.getReplyOrder();
        BuildReplyHandler.Result statuses[] = _buildReplyHandler.decrypt(msg, cfg, order);
        if (statuses != null) {
            boolean allAgree = true;
            for (int i = 0; i < cfg.getLength(); i++) { // For each peer in the tunnel
                Hash peer = cfg.getPeer(i);
                // If this tunnel member is us, skip this record, don't update profile or stats
                // for ourselves, we always agree - why must we save a slot for ourselves anyway?
                if (peer.equals(_context.routerHash())) {continue;}
                int record = order.indexOf(Integer.valueOf(i));
                if (record < 0) {
                    _log.error("Bad Status Index " + i);
                    _exec.buildComplete(cfg, BAD_RESPONSE); // don't leak
                    return;
                }
                int howBad = statuses[record].code;
                RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer); // Look up routerInfo
                String bwTier = "Unknown"; // Default and detect bandwidth tier
                if (ri != null) {
                    bwTier = ri.getBandwidthTier(); // Returns "Unknown" if none recognized
                    if (bwTier == "Unknown" && !_context.banlist().isBanlisted(peer)) {
                        if (_log.shouldWarn()) {
                            _log.warn("Banning [" + peer.toBase64().substring(0,6) + "] for 4h -> No Bandwidth Tier in RouterInfo");
                        }
                        String reason = " <b> -> </b> No Bandwidth Tier in RouterInfo";
                        _context.commSystem().mayDisconnect(peer);
                        _context.banlist().banlistRouter(peer, reason, null, null, 4*60*60*1000);
                        _banLogger.logBan(peer, _context, "No Bandwidth Tier in RouterInfo", 4*60*60*1000);
                    }
                }
                if (howBad == 0) {
                    // Record that a peer of the given tier agreed or rejected
                    _context.statManager().addRateData("tunnel.tierAgree" + bwTier, 1);
                    _context.profileManager().tunnelJoined(peer, rtt);
                    Properties props = statuses[record].props;
                    if (props != null) {
                        String avail = props.getProperty(BuildRequestor.PROP_AVAIL_BW);
                        if (avail != null) {
                            if (_log.shouldWarn())
                                _log.warn(msg.getUniqueId() + ": peer replied available: " + avail + "KBps");
                            // TODO
                        }
                    }
                } else {
                    _context.statManager().addRateData("tunnel.tierReject" + bwTier, 1);
                    allAgree = false;
                    String reason = "Unknown";
                    switch (howBad) {
                        case TunnelHistory.TUNNEL_REJECT_BANDWIDTH:
                            _context.statManager().addRateData("tunnel.receiveRejectionBandwidth", 1);
                            reason = "Bandwidth limits reached";
                            break;
                        case TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD:
                            _context.statManager().addRateData("tunnel.receiveRejectionTransient", 1);
                            reason = "Temporarily overloaded";
                            break;
                        case TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT:
                            _context.statManager().addRateData("tunnel.receiveRejectionProbabalistic", 1);
                            reason = "Near bandwidth limits";
                            break;
                        case TunnelHistory.TUNNEL_REJECT_CRIT:
                        default:
                            _context.statManager().addRateData("tunnel.receiveRejectionCritical", 1);
                            reason = "Critical state";
                    }
                    // penalize peer based on their reported error level
                    _context.profileManager().tunnelRejected(peer, rtt, howBad);
                    _context.messageHistory().tunnelParticipantRejected(peer, "peer rejected after " + rtt + " with " + howBad + ": " + cfg.toString());
                    if (_log.shouldInfo()) {
                        _log.info("Received reply from [" + peer.toBase64().substring(0,6) + "] for [MsgID " + msg.getUniqueId() +
                                      "] -> Request rejected (Reason: " + reason + ")");
                    }
                }
            }
            if (allAgree) {
                boolean success; // wicked, build completed
                if (cfg.isInbound()) {success = _context.tunnelDispatcher().joinInbound(cfg);}
                else {success = _context.tunnelDispatcher().joinOutbound(cfg);}
                if (!success) {
                    // This will happen very rarely. We check for dups when
                    // creating the config, but we don't track IDs for builds in progress.
                    _context.statManager().addRateData("tunnel.ownDupID", 1);
                    _exec.buildComplete(cfg, DUP_ID);
                    if (_log.shouldWarn()) {_log.warn("Duplicate ID for our own tunnel " + cfg);}
                    return;
                }
                _exec.buildComplete(cfg, SUCCESS);
                if (cfg.getTunnelPool().getSettings().isExploratory()) {
                    // Notify router that exploratory tunnels are ready
                    boolean isIn = cfg.isInbound();
                    synchronized(_startupLock) {
                        switch (_explState) {
                            case NONE:
                                if (isIn) {_explState = ExplState.IB;}
                                else {_explState = ExplState.OB;}
                                break;
                            case IB:
                                if (!isIn) {
                                    _explState = ExplState.BOTH;
                                    _context.router().setExplTunnelsReady();
                                }
                                break;
                            case OB:
                                if (isIn) {
                                    _explState = ExplState.BOTH;
                                    _context.router().setExplTunnelsReady();
                                }
                                break;
                            case BOTH:
                                break;
                        }
                    }
                }
                if (cfg.getDestination() == null) {_context.statManager().addRateData("tunnel.buildExploratorySuccess", rtt);}
                else {_context.statManager().addRateData("tunnel.buildClientSuccess", rtt);}
            } else {
                // someone is no fun
                _exec.buildComplete(cfg, REJECT);
                if (cfg.getDestination() == null) {_context.statManager().addRateData("tunnel.buildExploratoryReject", rtt);}
                else {_context.statManager().addRateData("tunnel.buildClientReject", rtt);}
            }
        } else {
            if (_log.shouldWarn()) {
                _log.warn("Tunnel reply [MsgID " + msg.getUniqueId() + "] could not be decrypted for tunnel " + cfg);
            }
            _context.statManager().addRateData("tunnel.corruptBuildReply", 1);
            _exec.buildComplete(cfg, BAD_RESPONSE); // don't leak
            // TODO blame everybody
        }
    }

    /**
     *  Decrypt the request, lookup the RI locally,
     *  and call handleReq() if found or queue a lookup job.
     *
     *  @return handle time or -1 if it wasn't completely handled
     */
    private long handleRequest(BuildMessageState state, long now) {
        long timeSinceReceived = now - state.recvTime;
        Hash from = state.fromHash;

        if (from == null && state.from != null) {from = state.from.calculateHash();}
        if (from != null && _context.banlist().isBanlisted(from)) {
            // Usually won't have connected, but may have been banlisted after connect
            if (_log.shouldWarn()) {
                _log.warn("Dropping Tunnel Request -> Previous peer [" + from.toBase64().substring(0,6) + "] is banned");
            }
            _context.commSystem().mayDisconnect(from);
            return -1;
        }
        // get our own RouterInfo
        RouterInfo myRI = _context.router().getRouterInfo();
        if (myRI != null) {
            String caps = myRI.getCapabilities();
            if (caps != null) {
                if (caps.indexOf(Router.CAPABILITY_NO_TUNNELS) >= 0) {
                    _context.statManager().addRateData("tunnel.dropTunnelFromCongestionCapability", 1);
                    if (_log.shouldWarn() && from != null) {
                        _log.warn("Dropped request from [" + from.toBase64().substring(0,6) + "] -> Local congestion");
                    }
                    RouterInfo fromRI = _context.netDb().lookupRouterInfoLocally(from);
                    if (fromRI != null) {
                        String fromVersion = fromRI.getVersion();
                        // If fromVersion is greater than 0.9.58, then then ban the router due to it
                        // disrespecting our congestion flags
                        if (fromVersion != null) {
                            if (VersionComparator.comp(fromVersion, MIN_VERSION_HONOR_CAPS) >= 0) {
                                _context.statManager().addRateData("tunnel.dropTunnelFromCongestionCapability" + from, 1);
                                _context.statManager().addRateData("tunnel.dropTunnelFromCongestionCapability" + fromVersion, 1);
                            }
                        }
                    }
                    return -1;
                }
            }
        }
        if (timeSinceReceived > (BuildRequestor.REQUEST_TIMEOUT*2)) {
            // don't even bother, since we are so overloaded locally
            _context.throttle().setTunnelStatus("[rejecting/overload]" + _x("Dropping Tunnel Requests: Overloaded"));
            if (_log.shouldWarn()) {
                _log.warn("Not trying to handle/decrypt stale request " + state.msg.getUniqueId() +
                           " -> Received " + timeSinceReceived + "ms ago");
            }
            _context.statManager().addRateData("tunnel.dropLoadDelay", timeSinceReceived);
            if (from != null) {_context.commSystem().mayDisconnect(from);}
            return -1;
        }
        // ok, this is not our own tunnel, so we need to do some heavy lifting
        // this not only decrypts the current hop's record, but encrypts the other records
        // with the enclosed reply key
        long beforeDecrypt = System.currentTimeMillis();
        BuildRequestRecord req = _processor.decrypt(state.msg, _context.routerHash(), _context.keyManager().getPrivateKey());
        long decryptTime = System.currentTimeMillis() - beforeDecrypt;
        _context.statManager().addRateData("tunnel.decryptRequestTime", decryptTime);
        if (decryptTime > 500 && _log.shouldWarn()) {
            _log.warn("Timeout decrypting request: " + decryptTime + " for message: " + state.msg.getUniqueId() +
                      " received " + (timeSinceReceived+decryptTime) + "ms ago");
        }
        if (req == null) {
            _context.statManager().addRateData("tunnel.dropDecryptFail", 1);
            if (from != null) {
                _context.commSystem().mayDisconnect(from);
                // no records matched, or the decryption failed. bah
                if (_log.shouldInfo()) {
                    _log.info("Request [MsgID " + state.msg.getUniqueId() + "] could not be decrypted from [" +
                              from.toBase64().substring(0,6) + "]");
                }
            }
            return -1;
        }

        Hash nextPeer = req.readNextIdentity();
        if (_context.banlist().isBanlisted(nextPeer)) {
            if (_log.shouldWarn()) {
                _log.warn("Dropping Tunnel Request -> Next peer [" + nextPeer.toBase64().substring(0,6) + "] is banned");
            }
            if (from != null) {_context.commSystem().mayDisconnect(from);}
            return -1;
        }

        RouterInfo nextPeerInfo = (RouterInfo) _context.netDb().lookupRouterInfoLocally(nextPeer);
        if (nextPeerInfo == null) {
            long lookupStartTime = System.currentTimeMillis();
            state.setLookupStartTime(lookupStartTime);
            int numTunnels = _context.tunnelManager().getParticipatingCount();
            int limit = Math.max(MIN_LOOKUP_LIMIT, Math.min(MAX_LOOKUP_LIMIT, numTunnels * PERCENT_LOOKUP_LIMIT / 100));
            long uptime = _context.router().getUptime();
            long maxQueueLag = _context.jobQueue().getMaxLag();
            boolean highLoad = SystemVersion.getCPULoadAvg() > 60 || maxQueueLag > 500;
            boolean isSlow = SystemVersion.isSlow();
            if (isSlow) {highLoad = SystemVersion.getCPULoadAvg() > 40 || maxQueueLag > 800;}

            boolean lucky;
            int dropPercent;
            if (highLoad) {
                lucky = false;
                dropPercent = 100;
            } else if (numTunnels < 500) {
                lucky = _context.random().nextInt(3) < 2;
                dropPercent = 33;
            } else if (numTunnels < 1000) {
                lucky = _context.random().nextInt(5) < 3;
                dropPercent = 40;
            } else {
                lucky = _context.random().nextInt(2) == 0;
                dropPercent = 50;
            }

            AtomicBoolean decremented = new AtomicBoolean(false);
            int currentLookups = _currentLookups.get();
            if (currentLookups < limit && !highLoad && lucky) {
                _currentLookups.incrementAndGet();
                if (_log.shouldInfo()) {
                    _log.info("Looking up next hop [" + nextPeer.toBase64().substring(0,6) +
                              "] -> Concurrent lookups: " + (currentLookups + 1) + " / " + limit + req);
                }
                _context.netDb().lookupRouterInfo(nextPeer, new HandleReq(_context, state, req, nextPeer, decremented),
                                                  new TimeoutReq(_context, state, req, nextPeer, decremented), NEXT_HOP_LOOKUP_TIMEOUT);
            } else {
                // Drop without ever incrementing
                String status = "...\n* From: " + from + " [MsgID: " + state.msg.getUniqueId() + "]" + req;
                if (highLoad) {
                    _log.info("Dropping next hop lookup -> System is under load" + status);
                } else if (!lucky) {
                    if (_log.shouldInfo()) {
                        _log.info("Dropping next hop lookup -> " + dropPercent + "% chance of drop" + status);
                    }
                }
                _context.statManager().addRateData("tunnel.dropLookupThrottle", 1);
                return -1;
            }
            return -1;
        } else {
            long beforeHandle = System.currentTimeMillis();
            handleReq(nextPeerInfo, state, req, nextPeer);
            long handleTime = System.currentTimeMillis() - beforeHandle;
            long msgId = 0;
            if (state != null && state.msg != null) {msgId = state.msg.getUniqueId();}
            if (_log.shouldDebug()) {
                String nextHop = (nextPeer != null) ? "and next hop [" + nextPeer.toBase64().substring(0, 6) + "] is known" : "";
                String fromHash = from != null ? from.toString() : "Unknown";
                if (handleTime > 0) {
                    _log.debug(String.format(
                        "Build tunnel request [MsgID: %s] handled in %dms %s \n* Decrypted in: %dms; Elapsed time since received: %dms\n* From: %s %s",
                        msgId, handleTime, nextHop, decryptTime, timeSinceReceived, fromHash, req));
                } else {
                    _log.debug(String.format(
                        "Build tunnel request [MsgID: %s] handled %s \n* From: %s %s",
                        msgId, nextHop, fromHash, req));

                }
            }
            return handleTime;
        }
    }

    /**
     * This request is actually a reply, process it as such
     */
    private void handleRequestAsInboundEndpoint(BuildEndMessageState state) {
        int records = state.msg.getRecordCount();
        TunnelBuildReplyMessage msg;
        if (state.msg.getType() == ShortTunnelBuildMessage.MESSAGE_TYPE) {msg = new ShortTunnelBuildReplyMessage(_context, records);}
        else if (records == TunnelBuildMessage.MAX_RECORD_COUNT) {msg = new TunnelBuildReplyMessage(_context);}
        else {msg = new VariableTunnelBuildReplyMessage(_context, records);}
        for (int i = 0; i < records; i++) {msg.setRecord(i, state.msg.getRecord(i));}
        msg.setUniqueId(state.msg.getUniqueId());
            handleReplyAsync(msg, state.cfg, System.currentTimeMillis() - state.recvTime);
    }

    private class HandleReq extends JobImpl {
        private final BuildMessageState _state;
        private final BuildRequestRecord _req;
        private final Hash _nextPeer;
        private final AtomicBoolean _decremented;
        private final long lookupStartTime;

        HandleReq(RouterContext ctx, BuildMessageState state, BuildRequestRecord req, Hash nextPeer, AtomicBoolean decremented) {
            super(ctx);
            _state = state;
            _req = req;
            _nextPeer = nextPeer;
            _decremented = decremented;
            this.lookupStartTime = _state.getLookupStartTime();
        }

        public String getName() {return "Defer Tunnel Join Processing";}

        public void runJob() {
            long now = System.currentTimeMillis();

            if (_log.shouldDebug()) {
                _log.debug("Request " + _state.msg.getUniqueId() + " handled with a successful deferred lookup: " + _req);
            }
            RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(_nextPeer);
            if (ri != null) {
                long lookupTime = now - lookupStartTime;
                handleReq(ri, _state, _req, _nextPeer);
                getContext().statManager().addRateData("tunnel.buildLookupSuccess", 1);
                if (lookupTime > 0) {
                    getContext().statManager().addRateData("tunnel.nextHopLookupSuccessTime", lookupTime);
                }
                if (_log.shouldInfo()) {
                    _log.info("Successful lookup for [" + _nextPeer.toBase64().substring(0,6) + "] took " + lookupTime + "ms");
                }
            } else {
                if (_log.shouldInfo()) {
                    _log.info("Lookup deferred, but we couldn't find [" + _nextPeer.toBase64().substring(0,6) + "] ? " + _req);
                }
                getContext().statManager().addRateData("tunnel.buildLookupSuccess", 0);
            }
            if (!_decremented.getAndSet(true)) {
                if (_currentLookups.decrementAndGet() < 0) {
                    if (_log.shouldWarn()) {
                        _log.warn("Count for concurrent next-hop lookups reports " + _currentLookups + " active (underflow) ➜ Resetting to 0...");
                    }
                    _currentLookups.set(0);
                }
            }
        }
    }

    private class TimeoutReq extends JobImpl {
        private final BuildMessageState _state;
        private final BuildRequestRecord _req;
        private final Hash _nextPeer;
        private final AtomicBoolean _decremented;

        TimeoutReq(RouterContext ctx, BuildMessageState state, BuildRequestRecord req, Hash nextPeer, AtomicBoolean decremented) {
            super(ctx);
            _state = state;
            _req = req;
            _nextPeer = nextPeer;
            _decremented = decremented;
        }

        public String getName() {return "Timeout Locating Peer for Tunnel Join";}

        @Override
        public void runJob() {
            long now = System.currentTimeMillis();
            long lookupTime = Math.max(1, now - _state.getLookupStartTime());
            getContext().statManager().addRateData("tunnel.rejectTimeout", 1);
            getContext().statManager().addRateData("tunnel.buildLookupSuccess", 0);
            Hash from = _state.fromHash;
            if (_log.shouldInfo()) {
                if (from == null && _state.from != null) {from = _state.from.calculateHash();}
                _log.info("Timeout (" + NEXT_HOP_LOOKUP_TIMEOUT / 1000 + "s) locating peer for next hop " + _req +
                          "\n* From: " + from + " [MsgID " + _state.msg.getUniqueId() + "]");
            }
            if (_nextPeer != null) {_context.commSystem().mayDisconnect(_nextPeer);}
            _context.profileManager().tunnelFailed(_nextPeer, 100); // blame
            _context.profileManager().tunnelTimedOut(_nextPeer);
            _context.messageHistory().tunnelRejected(_state.fromHash, new TunnelId(_req.readReceiveTunnelId()), _nextPeer, "lookup fail");

            if (!_decremented.getAndSet(true)) {
                if (_currentLookups.decrementAndGet() < 0) {
                    if (_log.shouldWarn()) {
                        _log.warn("Count for concurrent next-hop lookups reports " + _currentLookups.get() + " active (underflow) ➜ Resetting to 0...");
                    }
                    _currentLookups.set(0);
                }
            }
        }
    }

    /**
     *  Actually process the request and send the reply.
     *
     *  Todo: Replies are not subject to RED for bandwidth reasons,
     *  and the bandwidth is not credited to any tunnel.
     *  If we did credit the reply to the tunnel, it would
     *  prevent the classification of the tunnel as 'inactive' on tunnels.jsp.
     */
    private void handleReq(RouterInfo nextPeerInfo, BuildMessageState state, BuildRequestRecord req, Hash nextPeer) {
        long ourId = req.readReceiveTunnelId();
        long nextId = req.readNextTunnelId();
        boolean isInGW = req.readIsInboundGateway();
        boolean isOutEnd = req.readIsOutboundEndpoint();
        int bantime = 8*60*60*1000;
        int period = bantime / 60 / 1000;
        Hash from = state.fromHash;
        if (from == null && state.from != null) {from = state.from.calculateHash();}

        // Check for algorithmic bot patterns before processing tunnel request
        if (from != null && _banLogger.checkPatternAndPredict(from, _context)) {
            return; // Router was predictively banned
        }

        final boolean shouldThrottle = _context.getBooleanPropertyDefaultTrue(PROP_SHOULD_THROTTLE);
        final boolean shouldLog = _log.shouldDebug() || _log.shouldInfo() || _log.shouldWarn();
        String fromPeer = (shouldLog && from != null ? from.toBase64().substring(0,6) : "");
        String nextHop = (shouldLog && nextPeer != null ? nextPeer.toBase64().substring(0,6) : "");
        // Warning! from could be null, but should only happen if we will be IBGW and it came from us as OBEP
        if (isInGW && isOutEnd) {
            _context.statManager().addRateData("tunnel.rejectHostile", 1);
            if (from != null && !_context.banlist().isBanlisted(from)) {
                _context.commSystem().mayDisconnect(from);
                _context.banlist().banlistRouter(from, " <b> -> </b> Hostile Tunnel Request (IBGW+OBEP)", null, null, System.currentTimeMillis() + bantime);
                _banLogger.logBan(from, _context, "Hostile Tunnel Request (IBGW+OBEP)", bantime);
                if (shouldLog) {_log.warn("Banning [" + fromPeer + "] for " + period + "m -> Hostile Tunnel Request (Inbound Gateway & Outbound Endpoint)");}
            } else if (shouldLog) {_log.warn("Dropping HOSTILE Tunnel Request from UNKNOWN -> IBGW+OBEP");}
            return;
        }
        if (ourId <= 0 || ourId > TunnelId.MAX_ID_VALUE || nextId <= 0 || nextId > TunnelId.MAX_ID_VALUE) {
            _context.statManager().addRateData("tunnel.rejectHostile", 1);
            if (from != null && !_context.banlist().isBanlisted(from)) {
                _context.commSystem().mayDisconnect(from);
                _context.banlist().banlistRouter(from, " <b> -> </b> Hostile Tunnel Request (BAD Tunnel ID)", null, null, System.currentTimeMillis() + bantime);
                _banLogger.logBan(from, _context, "Hostile Tunnel Request (BAD Tunnel ID)", bantime);
                if (shouldLog) {_log.warn("Banning [" + fromPeer + "] for " + period + "m -> Hostile Tunnel Request (BAD TunnelID)");}
            } else if (shouldLog) {_log.warn("Dropping HOSTILE Tunnel Request from UNKNOWN -> BAD Tunnel ID");}
            return;
        }
        // Loop checks
        if ((!isOutEnd) && _context.routerHash().equals(nextPeer)) {
            _context.statManager().addRateData("tunnel.rejectHostile", 1);
            // We are 2 hops in a row? Drop it without a reply.
            // No way to recognize if we are every other hop, but see below
            // old i2pd
            if (from != null && !_context.banlist().isBanlisted(from)) {
                _context.commSystem().mayDisconnect(from);
                _context.banlist().banlistRouter(from, " <b> -> </b> Hostile Tunnel Request (double hop)", null, null, System.currentTimeMillis() + bantime);
                _banLogger.logBan(from, _context, "Hostile Tunnel Request (double hop)", bantime);
                _log.warn("Banning [" + fromPeer + "] for " + period + "m -> Hostile Tunnel Request (We are 2 hops in a row!)");
            } else if (shouldLog) {_log.warn("Dropping HOSTILE Tunnel Request from UNKNOWN -> We are the next hop");}
            return;
        }
        if (!isInGW) {
            // if from is null, it came via OutboundMessageDistributor.distribute(),
            // i.e. we were the OBEP, which is fine if we're going to be an IBGW
            // but if not, something is seriously wrong here.
            if (from == null || _context.routerHash().equals(from)) {
                _context.statManager().addRateData("tunnel.rejectHostile", 1);
                if (from != null && !_context.banlist().isBanlisted(from)) {
                    _context.commSystem().mayDisconnect(from);
                    _context.banlist().banlistRouter(from, " <b> -> </b> Hostile Tunnel Request (previous hop)", null, null, System.currentTimeMillis() + bantime);
                    _banLogger.logBan(from, _context, "Hostile Tunnel Request (previous hop)", bantime);
                    if (shouldLog) {_log.warn("Banning [" + fromPeer + "] for " + period + "m -> Hostile Tunnel Request (We are the previous hop!)");}
                } else if (shouldLog) {_log.warn("Dropping HOSTILE Tunnel Request from UNKNOWN -> We are the previous hop");}
                return;
            }
        }
        if ((!isOutEnd) && (!isInGW)) {
            // Previous and next hop the same? Don't help somebody be evil. Drop it without a reply.
            // A-B-C-A is not preventable
            if (nextPeer.equals(from)) {
                // i2pd does this
                _context.statManager().addRateData("tunnel.rejectHostile", 1);
                if (from != null && !_context.banlist().isBanlisted(from)) {
                    _context.commSystem().mayDisconnect(from);
                    _context.banlist().banlistRouter(from, " <b>➜</b> Hostile Tunnel Request (duplicate hops)", null, null, System.currentTimeMillis() + bantime);
                    _banLogger.logBan(from, _context, "Hostile Tunnel Request (duplicate hops)", bantime);
                    if (shouldLog) {_log.warn("Banning [" + fromPeer + "] for " + period + "m -> Hostile Tunnel Request (duplicate hops)");}
                } else if (shouldLog) {_log.warn("Dropping HOSTILE Tunnel Request from UNKNOWN -> Previous and next hop are the same");}
                return;
            }
        }
        long time = req.readRequestTime();
        long now = System.currentTimeMillis();
        boolean isEC = _context.keyManager().getPrivateKey().getType() == EncType.ECIES_X25519;
        long timeDiff;
        long maxAge;
        if (isEC) {
            // time is in minutes, rounded down.
            long roundedNow = (now / (60*1000L)) * (60*1000);
            timeDiff = roundedNow - time;
            maxAge = MAX_REQUEST_AGE_ECIES;
        } else {
            // time is in hours, rounded down.
            // tunnel-alt-creation.html specifies that this is enforced +/- 1 hour but it was not.
            // As of 0.9.16, allow + 5 minutes to - 65 minutes.
            long roundedNow = (now / (60*60*1000L)) * (60*60*1000);
            timeDiff = roundedNow - time;
            maxAge = MAX_REQUEST_AGE;
        }
        if (timeDiff > maxAge) {
            _context.statManager().addRateData("tunnel.rejectTooOld", 1);
            if (_log.shouldWarn()) {
                _log.warn("Dropping HOSTILE Tunnel Request -> Too old... replay attack? " + DataHelper.formatDuration(timeDiff) + " " + req);
            }
            if (from != null && !_context.banlist().isBanlisted(from)) {
                _context.commSystem().mayDisconnect(from);
                _context.banlist().banlistRouter(from, " <b> -> </b> Hostile Tunnel Request (possible replay attack)", null, null, System.currentTimeMillis() + bantime);
                _banLogger.logBan(from, _context, "Hostile Tunnel Request (possible replay attack)", bantime);
                if (shouldLog) {_log.warn("Banning [" + fromPeer + "] for " + period + "m -> Hostile Tunnel Request (too old, replay attack?)");}
            }
            return;
        }
        if (timeDiff < 0 - MAX_REQUEST_FUTURE) {
            _context.statManager().addRateData("tunnel.rejectFuture", 1);
            if (_log.shouldWarn()) {
                _log.warn("Dropping HOSTILE Tunnel Request -> Too far in future " + DataHelper.formatDuration(0 - timeDiff) + " " + req);
            }
            if (from != null && !_context.banlist().isBanlisted(from)) {
                _context.commSystem().mayDisconnect(from);
                _context.banlist().banlistRouter(from, " <b> -> </b> Hostile Tunnel Request (too far in future)", null, null, System.currentTimeMillis() + bantime);
                _banLogger.logBan(from, _context, "Hostile Tunnel Request (too far in future)", bantime);
                if (shouldLog) {_log.warn("Banning [" + fromPeer + "] for " + period + "m -> Hostile Tunnel Request (too far in future)");}
            }
            return;
        }
        int response;
        if (_context.router().isHidden()) {
            _context.throttle().setTunnelStatus("[hidden]" + _x("Declining requests" + ":" + _x("Hidden Mode")));
            response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        } else {response = _context.throttle().acceptTunnelRequest();}
        if (response == 0) {
            int type = req.readLayerEncryptionType(); // only in short build request, otherwise 0
            if (type != 0) {
                if (shouldLog) {_log.warn("Unsupported layer encryption type: " + type);}
                response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
            }
        }
        long recvDelay = now - state.recvTime;
        if (response == 0) {
            float pDrop = ((float) recvDelay) / (float) (BuildRequestor.REQUEST_TIMEOUT*2);
            pDrop = (float)Math.pow(pDrop, 16);
            if (_context.random().nextFloat() < pDrop) {
                _context.statManager().addRateData("tunnel.rejectOverloaded", recvDelay);
                _context.throttle().setTunnelStatus("[rejecting/overload]" + _x("Declining Tunnel Requests" + ":<br>" + _x("Request overload")));
                response = TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
            } else {
                _context.statManager().addRateData("tunnel.acceptLoad", recvDelay);
            }
        }
        /*
         * Being a IBGW or OBEP generally leads to more connections, so if we are
         * approaching our connection limit (i.e. !haveCapacity()),
         * reject this request.
         *
         * Don't do this for class N or O, under the assumption that they are already talking
         * to most of the routers, so there's no reason to reject. This may drive them
         * to their conn. limits, but it's hopefully a temporary solution to the
         * tunnel build congestion. As the net grows this will have to be revisited.
         */
        RouterInfo ri = _context.router().getRouterInfo();
        if (response == 0) {
            if (ri == null) {response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;} // ?? We should always have a RI
            else {
                char bw = ri.getBandwidthTier().charAt(0);
                if (bw != 'N' && bw != 'O' && bw != 'P' && bw != 'X' &&
                    ((isInGW && !_context.commSystem().haveInboundCapacity(87)) ||
                    (isOutEnd && !_context.commSystem().haveOutboundCapacity(87)))) {
                    _context.statManager().addRateData("tunnel.rejectConnLimits", 1);
                    _context.throttle().setTunnelStatus("[rejecting/max]" + _x("Declining Tunnel Requests" + ":<br>" + _x("Connection limit reached")));
                    response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                }
            }
        }
        // Check participating throttle counters for previous and next hops
        // This is at the end as it compares to a percentage of created tunnels.
        // We may need another counter above for requests.
        if (response == 0 && !isInGW && _throttler != null && from != null && shouldThrottle) {
            ParticipatingThrottler.Result result = _throttler.shouldThrottle(from);
            if (result == ParticipatingThrottler.Result.DROP) {
                if (_log.shouldInfo() && from != null && req != null) {
                    _log.info("Dropping Tunnel Request at previous hop (throttled) -> [" + fromPeer + "] " + (_log.shouldInfo() ? req : ""));
                }
                _context.statManager().addRateData("tunnel.rejectHopThrottle", 1);
                _context.commSystem().mayDisconnect(from);
                // fake failed so we won't use him for our tunnels
                _context.profileManager().tunnelFailed(from, 400);
                return;
            }
            if (result == ParticipatingThrottler.Result.REJECT) {
                if (_log.shouldInfo() && from != null && req != null) {
                    _log.info("Rejecting Tunnel Request at previous hop (throttled) -> [" + fromPeer + "] " + (_log.shouldInfo() ? req : ""));
                }
                _context.statManager().addRateData("tunnel.rejectHopThrottle", 1);
                response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                // fake failed so we won't use him for our tunnels
                _context.profileManager().tunnelFailed(from, 200);
            }
        }
        if (response == 0 && (!isOutEnd) && _throttler != null && shouldThrottle) {
            ParticipatingThrottler.Result result = _throttler.shouldThrottle(nextPeer);
            if (result == ParticipatingThrottler.Result.DROP) {
                if (_log.shouldInfo()) {
                    _log.info("Dropping Tunnel Request at next hop (throttled) -> [" + nextHop + "] " + (_log.shouldInfo() ? req : ""));
                }
                _context.statManager().addRateData("tunnel.rejectHopThrottle", 1);
                if (from != null) {_context.commSystem().mayDisconnect(from);}
                // fake failed so we won't use him for our tunnels
                _context.profileManager().tunnelFailed(nextPeer, 400);
            }
            if (result == ParticipatingThrottler.Result.REJECT) {
                if (_log.shouldInfo()) {
                    _log.info("Rejecting Tunnel Request at next hop (throttled) -> [" + nextHop + "] " + (_log.shouldInfo() ? req : ""));
                }
                _context.statManager().addRateData("tunnel.rejectHopThrottle", 1);
                response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                // fake failed so we won't use him for our tunnels
                _context.profileManager().tunnelFailed(nextPeer, 200);
            }
        }
        // BW params
        int avail = 0;
        if (response == 0) {
            Properties props = req.readOptions();
            if (props != null && !props.isEmpty()) {
                int min = 0;
                int rqu = 0;
                int ibgwmax = 0;
                String smin = props.getProperty(BuildRequestor.PROP_MIN_BW);
                if (smin != null) {
                    try {min = 1000 * Integer.parseInt(smin);}
                    catch (NumberFormatException nfe) {response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;}
                }
                String sreq = props.getProperty(BuildRequestor.PROP_REQ_BW);
                if (sreq != null) {
                    try {rqu = 1000 * Integer.parseInt(sreq);}
                    catch (NumberFormatException nfe) {response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;}
                }
                if (isInGW) {
                    String smax = props.getProperty(BuildRequestor.PROP_MAX_BW);
                    if (smax != null) {
                        try {ibgwmax = 1000 * Integer.parseInt(smax);}
                        catch (NumberFormatException nfe) {response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;}
                    }
                }
                if ((min > 0 || rqu > 0 || ibgwmax > 0) && response == 0) {
                    int share = 1000 * TunnelDispatcher.getShareBandwidth(_context);
                    int max = share / 2;
                    if (min > max) {response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;}
                    else {
                        RateStat stat = _context.statManager().getRate("tunnel.participatingBandwidth");
                        if (stat != null) {
                            Rate rate = stat.getRate(RateConstants.TEN_MINUTES);
                            if (rate != null) {
                            int used = (int) rate.getAvgOrLifetimeAvg();
                            int available = share - used;
                            avail = Math.min(max, available);
                                if (_log.shouldDebug())
                                    _log.debug("Tunnel bandwidth - share: " + formatBandwidth(share) +
                                              " used: " + formatBandwidth(used) + " max: " + formatBandwidth(max) +
                                              " avail: " + formatBandwidth(avail));
                                if (min > avail) {
                                    if (_log.shouldInfo())
                                        _log.info("Rejecting transit tunnel request -> Insufficient bandwidth available (Required / Available: " +
                                                  rqu + " / " + avail + "bytes");
                                    response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                                } else {
                                    if (min > 0 && rqu > 4 * min)
                                        rqu = 4 * min;
                                    if (rqu > 0 && rqu < avail)
                                        avail = rqu;
                                    if (ibgwmax > 0 && ibgwmax < avail)
                                        avail = ibgwmax;
                                    if (_log.shouldDebug())
                                        _log.debug("Accepting transit tunnel request -> Sufficient bandwidth available (Required / Available: " +
                                                  rqu + " / " + avail + "bytes");
                                }
                            }
                        }
                    }
                }
            }
        }
        HopConfig cfg = null;
        if (response == 0) {
            cfg = new HopConfig();
            cfg.setCreation(now);
            cfg.setExpiration(now + 10*60*1000);
            cfg.setIVKey(req.readIVKey());
            cfg.setLayerKey(req.readLayerKey());
            if (isInGW) {
                // default
                //cfg.setReceiveFrom(null);
            } else {
                if (from != null) {cfg.setReceiveFrom(from);}
                else {return;} // b0rk
            }
            cfg.setReceiveTunnelId(ourId);
            if (isOutEnd) {
                // default
                //cfg.setSendTo(null);
                //cfg.setSendTunnelId(null);
            } else {
                cfg.setSendTo(nextPeer);
                cfg.setSendTunnelId(nextId);
            }
            if (avail > 0) {cfg.setAllocatedBW(avail);}
            else {cfg.setAllocatedBW(DEFAULT_BW_PER_TUNNEL_ESTIMATE);}
            if (_log.shouldDebug())
                _log.debug("Tunnel join - Allocated: " + formatBandwidth(cfg.getAllocatedBW()));
            // now "actually" join
            boolean success;
            if (isOutEnd) {success = _context.tunnelDispatcher().joinOutboundEndpoint(cfg);}
            else if (isInGW) {success = _context.tunnelDispatcher().joinInboundGateway(cfg);}
            else {success = _context.tunnelDispatcher().joinParticipant(cfg);}
            if (!success) {
                // Dup Tunnel ID. This can definitely happen (birthday paradox).
                // Probability in 11 minutes (per hop type):
                // 0.1% for 2900 tunnels; 1% for 9300 tunnels
                response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                _context.statManager().addRateData("tunnel.rejectDupID", 1);
                if (shouldLog) {_log.warn("Duplicate TunnelID failure " + req);}
            }
        }
        // determination of response is now complete
        if (response != 0) {
            _context.statManager().addRateData("tunnel.reject." + response, 1);
            _context.messageHistory().tunnelRejected(from, new TunnelId(ourId), nextPeer, Integer.toString(response));
            if (from != null) {_context.commSystem().mayDisconnect(from);}
            // Connection congestion control:
            // If we rejected the request, are near our conn limits, and aren't connected to the next hop,
            // just drop it.
            // 81% = between 75% control measures in Transports and 87% rejection above
            if ((!_context.routerHash().equals(nextPeer)) &&
                (!_context.commSystem().haveOutboundCapacity(90)) &&
                (!_context.commSystem().isEstablished(nextPeer))) {
                _context.statManager().addRateData("tunnel.dropConnLimits", 1);
                if (shouldLog) {_log.warn("Dropping Tunnel Request -> Congestion control enabled (close to our limit) " + (_log.shouldInfo() ? req : ""));}
                return;
            }
        } else if (isInGW && from != null) {_context.commSystem().mayDisconnect(from);} // we're the start of the tunnel, no use staying connected
        if (_log.shouldDebug()) {
            _log.debug("Responding to [MsgID " + state.msg.getUniqueId()
                       + "] after " + (recvDelay >= 1 ? recvDelay + "ms" : "") + " with response [#" + response
                       + "] from " + (from != null ? "[" + fromPeer + "]" : "tunnel") + req);
        }
        int records = state.msg.getRecordCount();
        int ourSlot = -1;
        for (int j = 0; j < records; j++) {
            if (state.msg.getRecord(j) == null) {
                ourSlot = j;
                break;
            }
        }
        EncryptedBuildRecord reply;
        if (isEC) {
            Properties props;
            if (avail > 0) {
                props = new Properties();
                props.setProperty(BuildRequestor.PROP_AVAIL_BW, Integer.toString(avail / 1000));
            } else {
                props = EmptyProperties.INSTANCE;
            }
            if (state.msg.getType() == ShortTunnelBuildMessage.MESSAGE_TYPE) {
                reply = BuildResponseRecord.createShort(_context, response, req.getChaChaReplyKey(), req.getChaChaReplyAD(), props, ourSlot);
            } else {
                reply = BuildResponseRecord.create(_context, response, req.getChaChaReplyKey(), req.getChaChaReplyAD(), props);
            }
        } else {
            reply = BuildResponseRecord.create(_context, response, req.readReplyKey(), req.readReplyIV(), state.msg.getUniqueId());
        }
        state.msg.setRecord(ourSlot, reply);
        if (_log.shouldDebug()) {
            _log.debug("Read slot [#" + ourSlot + "] containing reply [MsgID " + req.readReplyMessageId() + "] -> " +
                       (response == 0 ? "Rejected" : "Accepted?") + (recvDelay >= 1 ? " in " + recvDelay + "ms" : "") + req);
        }
        // now actually send the response
        long expires = now + NEXT_HOP_LOOKUP_TIMEOUT;
        if (!isOutEnd) {
            TunnelBuildMessage nextMessage = state.msg;
            nextMessage.setUniqueId(req.readReplyMessageId());
            nextMessage.setMessageExpiration(expires);
            OutNetMessage msg = new OutNetMessage(_context, nextMessage, expires, PRIORITY, nextPeerInfo);
            if (response == 0) {msg.setOnFailedSendJob(new TunnelBuildNextHopFailJob(_context, cfg));}
            _context.outNetMessagePool().add(msg);
        } else {
            // We are the OBEP.
            // send it to the reply tunnel on the reply peer within a new TunnelBuildReplyMessage
            // (enough layers jrandom?)
            TunnelBuildReplyMessage replyMsg;
            if (state.msg.getType() == ShortTunnelBuildMessage.MESSAGE_TYPE) {
                OutboundTunnelBuildReplyMessage otbrm  = new OutboundTunnelBuildReplyMessage(_context, records);
                replyMsg = otbrm;
            } else if (records == TunnelBuildMessage.MAX_RECORD_COUNT) {replyMsg = new TunnelBuildReplyMessage(_context);}
            else {replyMsg = new VariableTunnelBuildReplyMessage(_context, records);}
            for (int i = 0; i < records; i++) {replyMsg.setRecord(i, state.msg.getRecord(i));}
            replyMsg.setUniqueId(req.readReplyMessageId());
            replyMsg.setMessageExpiration(expires);
            boolean replyGwIsUs = _context.routerHash().equals(nextPeer);
            I2NPMessage outMessage;
            if (!replyGwIsUs && state.msg.getType() == ShortTunnelBuildMessage.MESSAGE_TYPE) {
                outMessage = MessageWrapper.wrap(_context, replyMsg, req.readGarlicKeys()); // garlic encrypt
                if (outMessage == null) {
                    if (shouldLog) {_log.warn("OutboundTunnelBuildReplyMessage encryption failure");}
                    return;
                }
            } else {outMessage = replyMsg;}
            TunnelGatewayMessage m = new TunnelGatewayMessage(_context);
            m.setMessage(outMessage);
            m.setMessageExpiration(expires);
            m.setTunnelId(new TunnelId(nextId));
            if (replyGwIsUs) {
                // ok, we are the gateway, so inject it
                _context.tunnelDispatcher().dispatch(m);
                if (_log.shouldDebug()) {
                    _log.debug("We are the reply gateway for " + nextId + " when replying to ReplyMessage " + req);
                }
            } else {
                // ok, the gateway is some other peer, shove 'er across
                OutNetMessage outMsg = new OutNetMessage(_context, m, expires, PRIORITY, nextPeerInfo);
                if (response == 0) {outMsg.setOnFailedSendJob(new TunnelBuildNextHopFailJob(_context, cfg));}
                _context.outNetMessagePool().add(outMsg);
            }
        }
    }

    public int getInboundBuildQueueSize() {return _inboundBuildMessages.size();}

    /**
     *  Handle incoming Tunnel Build Messages, which are generally requests to us,
     *  but could also be the reply where we are the IBEP.
     */
    private class TunnelBuildMessageHandlerJobBuilder implements HandlerJobBuilder {
        /**
         *  Either from or fromHash may be null, but both should be null only if
         *  we're to be a IBGW and it came from us as a OBEP.
         */
        public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
            // need to figure out if this is a reply to an inbound Tunnel Request (where we are the
            // endpoint, receiving the request at the last hop)
            long reqId = receivedMessage.getUniqueId();
            PooledTunnelCreatorConfig cfg = _exec.removeFromBuilding(reqId);
            boolean shouldThrottle = _context.getBooleanPropertyDefaultTrue(PROP_SHOULD_THROTTLE);
            if (cfg != null) {
                if (!cfg.isInbound()) { // shouldnt happen - should we put it back?
                    _log.error("Received TunnelBuildMessage, but it's not Inbound? " + cfg);
                }
                BuildEndMessageState state = new BuildEndMessageState(cfg, receivedMessage);
                handleRequestAsInboundEndpoint(state);
            } else {
                if (_exec.wasRecentlyBuilding(reqId)) {
                    // we are the IBEP but we already gave up?
                    if (_log.shouldWarn()) {
                        _log.warn("Dropping reply [RequestID: " + reqId + "] -> Previously abandoned");
                    }
                    _context.statManager().addRateData("tunnel.buildReplyTooSlow", 1);
                } else {
                    int sz = _inboundBuildMessages.size();
                    // Can probably remove this check, since CoDel is in use
                    BuildMessageState cur = _inboundBuildMessages.peek();
                    boolean accept = true;
                    if (cur != null) {
                        long age = System.currentTimeMillis() - cur.recvTime;
                        if (age >= BuildRequestor.REQUEST_TIMEOUT/2) {
                            _context.statManager().addRateData("tunnel.dropLoad", age, sz);
                            _context.throttle().setTunnelStatus("[rejecting/overload]" + _x("Dropping Tunnel Requests: High load"));
                            // if the queue is backlogged, stop adding new messages
                            accept = false;
                        }
                    }
                    if (accept && _requestThrottler != null  && shouldThrottle) {
                        // early request throttle check, before queueing and decryption
                        Hash fh = fromHash;
                        if (fh == null && from != null) {fh = from.calculateHash();}
                        if (fh != null && _requestThrottler.shouldThrottle(fh)) {
                            if (_log.shouldWarn()) {
                                _log.warn("Dropping Tunnel Request [ID: " + reqId + "] -> Previous hop [" + fh.toBase64().substring(0,6) + "] is being throttled");
                            }
                            _context.statManager().addRateData("tunnel.dropReqThrottle", 1);
                            // fake failed so we won't use him for our tunnels
                            _context.profileManager().tunnelFailed(fh, 400);
                            accept = false;
                        }
                    }
                    // Global rate limiter - catch distributed DDoS attacks regardless of peer diversity
                    if (accept && _globalRateLimiter != null && shouldThrottle) {
                        if (_globalRateLimiter.shouldDrop()) {
                            if (_log.shouldWarn()) {
                                _log.warn("Dropping Tunnel Request [ID: " + reqId + "] -> Global rate limit exceeded");
                            }
                            _context.statManager().addRateData("tunnel.dropGlobalRateLimit", 1);
                            accept = false;
                        }
                    }
                    if (accept) {
                        accept = _inboundBuildMessages.offer(new BuildMessageState(_context, receivedMessage, from, fromHash));
                        if (accept) {_exec.repoll();} // wake up the Executor to call handleInboundRequests()
                        else {
                            _context.throttle().setTunnelStatus("[rejecting/overload]" + _x("Dropping Tunnel Requests: High load"));
                            _context.statManager().addRateData("tunnel.dropLoadBacklog", sz);
                        }
                    }
                }
            }
            return _buildMessageHandlerJob;
        }
    }

    private class TunnelBuildReplyMessageHandlerJobBuilder implements HandlerJobBuilder {
        public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
            if (_log.shouldDebug()) {
                _log.debug("Received TunnelBuildReplyMessage " + receivedMessage.getUniqueId() + " from " +
                           (fromHash != null ? fromHash : from != null ? from.calculateHash() : "a tunnel"));
            }
            handleReply(new BuildReplyMessageState(receivedMessage));
            return _buildReplyMessageHandlerJob;
        }
    }

    /** normal inbound requests from other people */
    private static class BuildMessageState implements CDQEntry {
        private final RouterContext _ctx;
        final TunnelBuildMessage msg;
        final RouterIdentity from;
        final Hash fromHash;
        final long recvTime;
        private long lookupStartTime = -1;

        /**
         *  Either f or h may be null, but both should be null only if we're to be a IBGW and it came from us as a OBEP.
         */
        public BuildMessageState(RouterContext ctx, I2NPMessage m, RouterIdentity f, Hash h) {
            _ctx = ctx;
            msg = (TunnelBuildMessage)m;
            from = f;
            fromHash = h;
            recvTime = System.currentTimeMillis();
        }
        public void setEnqueueTime(long time) {} // set at instantiation, which is just before enqueueing
        public long getEnqueueTime() {return recvTime;}
        public void setLookupStartTime(long time) {this.lookupStartTime = time;}
        public long getLookupStartTime() {return lookupStartTime;}
        public void drop() {
            _ctx.throttle().setTunnelStatus("[rejecting/overload]" + _x("Dropping Tunnel Requests: Queue time"));
            _ctx.statManager().addRateData("tunnel.dropLoadProactive", System.currentTimeMillis() - recvTime);
        }
    }

    /** replies for outbound tunnels that we have created */
    private static class BuildReplyMessageState {
        final TunnelBuildReplyMessage msg;
        final long recvTime;
        public BuildReplyMessageState(I2NPMessage m) {
            msg = (TunnelBuildReplyMessage)m;
            recvTime = System.currentTimeMillis();
        }
    }

    /** replies for inbound tunnels we have created */
    private static class BuildEndMessageState {
        final TunnelBuildMessage msg;
        final PooledTunnelCreatorConfig cfg;
        final long recvTime;
        public BuildEndMessageState(PooledTunnelCreatorConfig c, I2NPMessage m) {
            cfg = c;
            msg = (TunnelBuildMessage)m;
            recvTime = System.currentTimeMillis();
        }
    }

    /** noop */
    private static class TunnelBuildMessageHandlerJob extends JobImpl {
        private TunnelBuildMessageHandlerJob(RouterContext ctx) {super(ctx);}
        @Override
        public void runJob() {}
        public String getName() {return "Receive Tunnel Build Message";}
    }

    /** noop */
    private static class TunnelBuildReplyMessageHandlerJob extends JobImpl {
        private TunnelBuildReplyMessageHandlerJob(RouterContext ctx) {super(ctx);}
        @Override
        public void runJob() {}
        public String getName() {return "Receive Tunnel Build Reply Message";}
    }

    /**
     *  Remove the participating tunnel if we can't contact the next hop
     *  Not strictly necessary, as the entry doesn't use that much space,
     *  but it affects capacity calculations
     */
    private class TunnelBuildNextHopFailJob extends JobImpl {
        private final HopConfig _cfg;
        private TunnelBuildNextHopFailJob(RouterContext ctx, HopConfig cfg) {
            super(ctx);
            _cfg = cfg;
        }
        public String getName() {return "Timeout Building Tunnel Hop";}
        @Override
        public void runJob() {
            Log log = getContext().logManager().getLog(BuildHandler.class);

            // Attempt to remove the participating tunnel entry to avoid leaving
            // stale in-progress state that can starve new builds. If removal fails,
            // log and continue; later cleanup will still prune stale entries.
            try {
                getContext().tunnelDispatcher().remove(_cfg);
            } catch (RuntimeException ex) {
                if (log.shouldWarn()) {
                    log.warn("Error removing timed-out hop config: " + _cfg, ex);
                }
            }
            getContext().statManager().addRateData("tunnel.rejectTimeout2", 1);
            getContext().statManager().addRateData("tunnel.nextHopLookupTimeout", NEXT_HOP_LOOKUP_TIMEOUT);
            if (log.shouldDebug()) {
                log.debug("Timeout (" + (NEXT_HOP_LOOKUP_TIMEOUT / 1000) + "s) contacting next hop" + _cfg);
            }
        }
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    private static final String _x(String s) {return s;}

    /**
     * Global rate limiter for tunnel build requests.
     * Tracks ALL incoming requests regardless of peer identity to detect and mitigate
     * distributed DDoS attacks where attackers use many different router IDs.
     *
     * Uses a sliding window with 100ms buckets over 1 second.
     * Configurable via properties:
     * - router.tunnelBuildGlobalRateLimit: requests per second (default: 200)
     * - router.tunnelBuildGlobalBurstLimit: absolute max in window (default: 400)
     *
     * @since 0.9.68+
     */
    private static class GlobalRateLimiter {
        private final RouterContext _ctx;
        private final AtomicIntegerArray _buckets;
        private final int _bucketCount;
        private final long _bucketSizeMs;
        private final int _rateLimit;
        private final int _burstLimit;
        private final AtomicInteger _currentCount;
        private volatile long _lastBucketTime;
        private volatile int _currentBucket;

        GlobalRateLimiter(RouterContext ctx) {
            this._ctx = ctx;
            this._bucketCount = GLOBAL_RATE_BUCKETS;
            this._bucketSizeMs = GLOBAL_RATE_WINDOW_MS / _bucketCount; // 100ms per bucket
            this._buckets = new AtomicIntegerArray(_bucketCount);
            this._rateLimit = ctx.getProperty(PROP_GLOBAL_RATE_LIMIT, DEFAULT_GLOBAL_RATE_LIMIT);
            this._burstLimit = ctx.getProperty(PROP_GLOBAL_BURST_LIMIT, DEFAULT_GLOBAL_BURST_LIMIT);
            this._currentCount = new AtomicInteger(0);
            this._lastBucketTime = System.currentTimeMillis();
            this._currentBucket = 0;
        }

        /**
         * Check if we should drop this request due to global rate limiting.
         * Also increments the counter if not dropping.
         *
         * @return true if request should be dropped, false if allowed
         */
        boolean shouldDrop() {
            long now = System.currentTimeMillis();
            int newBucket = (int) ((now / _bucketSizeMs) % _bucketCount);

            // If we've moved to a new bucket, reset old buckets and update count
            if (newBucket != _currentBucket) {
                synchronized (this) {
                    if (newBucket != _currentBucket) {
                        // Reset all buckets between old and new (handles wraparound)
                        int bucket = (_currentBucket + 1) % _bucketCount;
                        while (bucket != newBucket) {
                            int oldVal = _buckets.getAndSet(bucket, 0);
                            _currentCount.addAndGet(-oldVal);
                            bucket = (bucket + 1) % _bucketCount;
                        }
                        // Reset the new bucket too
                        int oldVal = _buckets.getAndSet(newBucket, 0);
                        _currentCount.addAndGet(-oldVal);
                        _currentBucket = newBucket;
                        _lastBucketTime = now;
                    }
                }
            }

            // Check burst limit first (absolute ceiling)
            int current = _currentCount.get();
            if (current >= _burstLimit) {
                if (_ctx.logManager().getLog(BuildHandler.class).shouldWarn()) {
                    _ctx.logManager().getLog(BuildHandler.class).warn(
                        "Global burst limit exceeded: " + current + " / " + _burstLimit);
                }
                return true;
            }

            // Check rate limit (sustained rate)
            if (current >= _rateLimit) {
                // Probabilistic drop based on how far over we are
                double overage = (double) (current - _rateLimit) / _rateLimit;
                double dropProb = Math.min(0.95, overage * 2); // Max 95% drop rate
                if (_ctx.random().nextFloat() < dropProb) {
                    if (_ctx.logManager().getLog(BuildHandler.class).shouldWarn()) {
                        _ctx.logManager().getLog(BuildHandler.class).warn(
                            "Global rate limit exceeded: " + current + " / " + _rateLimit + " (drop prob: " + dropProb + ")");
                    }
                    return true;
                }
            }

            // Allow the request
            _buckets.incrementAndGet(newBucket);
            _currentCount.incrementAndGet();
            return false;
        }

        /**
         * Get current global request count in the sliding window.
         * @return current count
         */
        int getCurrentCount() {
            return _currentCount.get();
        }
    }
}