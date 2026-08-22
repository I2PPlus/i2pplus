package net.i2p.router.tunnel.pool;

import static net.i2p.router.tunnel.pool.BuildExecutor.Result.*;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.crypto.EncType;
import net.i2p.data.DatabaseEntry;
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
import net.i2p.data.i2np.TunnelBuildMessageBase;
import net.i2p.data.i2np.TunnelBuildReplyMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.data.i2np.VariableTunnelBuildMessage;
import net.i2p.data.i2np.VariableTunnelBuildReplyMessage;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterThrottleImpl;
import net.i2p.router.networkdb.kademlia.MessageWrapper;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.router.BanLogger;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.router.util.CDQEntry;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Handle the received tunnel build message requests and replies,
 * including sending responses and updating tunnel lists.
 */
public class BuildHandler implements Runnable {
    private final RouterContext _context;
    private final Log _log;
    private final TunnelPoolManager _manager;
    private final BuildExecutor _exec;
    private final Job _buildMessageHandlerJob;
    private final Job _buildReplyMessageHandlerJob;
    private final BlockingQueue<BuildMessageState> _inboundBuildMessages;
    private final BuildMessageProcessor _processor;
    private final RequestThrottler _requestThrottler;
    private final ParticipatingThrottler _throttler;
    private final BuildReplyHandler _buildReplyHandler;
    private final IdleTunnelMonitor _idleTunnelMonitor;
    private final BanLogger _banLogger;
    /**
     * Distinct next-hop keys currently being resolved, each carrying the
     * refcount of build requests waiting on it. Slots count KEYS, not
     * requests: N builds missing the same hop attach to one shared search,
     * because the netdb coalesces duplicate lookups into a single network
     * round trip.
     */
    private final ConcurrentHashMap<Hash, AtomicInteger> _lookupKeys = new ConcurrentHashMap<>(32);
    /** FIFO of deferred lookups waiting for a concurrent-lookup slot */
    private final ConcurrentLinkedDeque<PendingLookup> _pendingLookups = new ConcurrentLinkedDeque<>();
    private volatile boolean _isRunning;
    private final Object _startupLock = new Object();
    private ExplState _explState = ExplState.NONE; // NOSONAR S1170
    private final String MIN_VERSION_HONOR_CAPS = "0.9.58";
    private static final String PROP_SHOULD_THROTTLE = "router.enableTransitThrottle";
    private enum ExplState {NONE, IB, OB, BOTH}
    private static final boolean IS_SLOW = SystemVersion.isSlow();
    /** TODO these may be too high, review and adjust */
    private static volatile int _maxQueue = IS_SLOW ? 64 : 512;
    private static final String PROP_MAX_QUEUE = "router.buildHandlerMaxQueue";
    private static final int NEXT_HOP_LOOKUP_TIMEOUT = 3*1000;
    private static final int PRIORITY = OutNetMessage.PRIORITY_BUILD_REPLY;
    /**
     *  Concurrent next-hop search ceiling. Deliberately NOT cores-scaled:
     *  a lookup slot is an I/O-bound wait on floodfill replies, not CPU
     *  work, so hardware size doesn't change how many can be in flight.
     *  Network load is bounded downstream by per-key search coalescing,
     *  IterativeSearchJob fan-out caps, and the recently-queried floodfill
     *  cooldowns. Overridable via i2p.tunnel.build.maxLookupLimit.
     */
    private static final int MAX_LOOKUP_LIMIT = IS_SLOW ? 32 : 64;
    /** i2p.tunnel.build.maxPendingLookups property; see {@link #getMaxPendingLookups}. */
    private static final String PROP_MAX_PENDING_LOOKUPS = "i2p.tunnel.build.maxPendingLookups";
    /**
     * Grace added to the lookup timeout for the independent slot-release
     * deadline, so the normal callbacks (which release earlier) win the race
     * in the common case and the deadline only catches leaks.
     */
    private static final long LOOKUP_DEADLINE_MARGIN_MS = 2 * 1000L;
    /**
     * Cold-start window for next-hop lookup concurrency: until this long
     * after startup, the lookup limit uses its full configured ceiling,
     * because the netDb miss rate is high while {@code participating x pct}
     * would clamp concurrency to near the floor exactly when inbound builds
     * need it most.
     */
    private static final long STARTUP_LOOKUP_BOOST_MS = 10 * 60 * 1000L;

    private static volatile RouterContext _cfgCtx;
    private static volatile long _cfgRefreshed;
    private static volatile int _cachedNextHopLookupTimeout;
    private static volatile int _cachedMinLookupLimit;
    private static volatile int _cachedMaxLookupLimit;
    private static volatile int _cachedPercentLookupLimit;
    private static volatile int _cachedMaxPendingLookups;
    private static volatile long _cachedMaxRequestFuture;
    private static volatile long _cachedMaxRequestAge;
    private static volatile long _cachedMaxRequestAgeEcies;
    private static volatile long _cachedJobLagLimitTunnel;
    private static volatile int _cachedMaxParticipatingTunnels;
    private static final long CONFIG_REFRESH_MS = 30 * 1000L;

    /**
     *  Refresh the cached build configuration from properties at most once
     *  per CONFIG_REFRESH_MS, or immediately when the context changes.
     *  Benign race: duplicate refreshes are idempotent writes.
     */
    private static void refreshBuildConfig(RouterContext ctx) {
        long now = ctx.clock().now();
        if (_cfgCtx == ctx && now - _cfgRefreshed < CONFIG_REFRESH_MS)
            return;
        _cachedNextHopLookupTimeout = ctx.getProperty("i2p.tunnel.build.nextHopLookupTimeout", NEXT_HOP_LOOKUP_TIMEOUT);
        _cachedMinLookupLimit = ctx.getProperty("i2p.tunnel.build.minLookupLimit", SystemVersion.isSlow() ? 4 : 10);
        _cachedMaxLookupLimit = ctx.getProperty("i2p.tunnel.build.maxLookupLimit", MAX_LOOKUP_LIMIT);
        _cachedPercentLookupLimit = ctx.getProperty("i2p.tunnel.build.percentLookupLimit", SystemVersion.isSlow() ? 15 : 40);
        _cachedMaxPendingLookups = ctx.getProperty(PROP_MAX_PENDING_LOOKUPS, 256);
        _cachedMaxRequestFuture = ctx.getProperty("i2p.tunnel.build.maxRequestFuture", 5*60*1000L);
        _cachedMaxRequestAge = ctx.getProperty("i2p.tunnel.build.maxRequestAge", 65*60*1000L);
        _cachedMaxRequestAgeEcies = ctx.getProperty("i2p.tunnel.build.maxRequestAgeEcies", 8*60*1000L);
        _cachedJobLagLimitTunnel = ctx.getProperty("i2p.tunnel.build.jobLagLimitTunnel", SystemVersion.isSlow() ? 800 : 500);
        _cachedMaxParticipatingTunnels = ctx.getProperty("router.maxParticipatingTunnels", IS_SLOW ? 4000 : 10000);
        _cfgCtx = ctx;
        _cfgRefreshed = now;
    }

    private static int getNextHopLookupTimeout(RouterContext ctx) {
        refreshBuildConfig(ctx);
        return _cachedNextHopLookupTimeout;
    }
    private static int getMinLookupLimit(RouterContext ctx) {
        refreshBuildConfig(ctx);
        return _cachedMinLookupLimit;
    }
    private static int getMaxLookupLimit(RouterContext ctx) {
        refreshBuildConfig(ctx);
        return _cachedMaxLookupLimit;
    }
    private static int getPercentLookupLimit(RouterContext ctx) {
        refreshBuildConfig(ctx);
        return _cachedPercentLookupLimit;
    }

    /**
     *  Pending next-hop lookups allowed to wait for a free slot while their
     *  build request is still inside the originator's budget. Sized near the
     *  useful maximum - a full queue drains inside the staleness window
     *  (drain rate x window is roughly 200) - so bursts absorb instead of
     *  dying; entries past the originator's budget are discarded by the
     *  staleness check either way.
     *
     *  @param ctx router context, for config lookup
     *  @return the queue depth cap
     */
    private static int getMaxPendingLookups(RouterContext ctx) {
        refreshBuildConfig(ctx);
        return _cachedMaxPendingLookups;
    }

    /**
     *  Concurrent next-hop lookup limit for the current request.
     *
     *  Within {@link #STARTUP_LOOKUP_BOOST_MS} of startup the full ceiling
     *  applies regardless of participating count: the netDb is still filling,
     *  so cache misses dominate and the proportional formula would clamp
     *  concurrency to near its floor exactly when inbound builds queue up.
     *  Afterward, scale with transit population as before, floored at
     *  minLimit so a drained pool can't zero out lookups.
     *
     *  Pure decision — safe for unit tests.
     *
     *  @param numTunnels current participating (transit) tunnel count
     *  @param minLimit configured floor for the proportional formula
     *  @param maxLimit configured ceiling
     *  @param percentLimit percent of participating count usable as slots
     *  @param uptimeMs router uptime in ms
     *  @return the concurrent lookup limit, never below minLimit
     *  @since 0.9.71+
     */
    static int lookupLimit(int numTunnels, int minLimit, int maxLimit, int percentLimit, long uptimeMs) {
        if (uptimeMs < STARTUP_LOOKUP_BOOST_MS) {return maxLimit;}
        return Math.max(minLimit, Math.min(maxLimit, numTunnels * percentLimit / 100));
    }

    /**
     *  Attach a build request's next hop to the in-flight lookup set.
     *  Capacity counts DISTINCT keys: requests joining an already-attached
     *  key consume no extra slot, because the netdb coalesces duplicate
     *  lookups for one key into a single network round trip. Without this,
     *  N builds missing the same popular hop would burn N slots to run one
     *  search and starve unrelated keys.
     *
     *  The limit is soft: racing handler threads may transiently overshoot
     *  by at most the thread count, which is harmless.
     *
     *  Pure decision on its map argument — safe for unit tests.
     *
     *  @param inFlight distinct next-hop keys currently being resolved
     *  @param key next hop of this request
     *  @param limit concurrent distinct-key ceiling
     *  @return true if attached and a lookup should be issued; false when
     *          the ceiling is reached and the request must queue
     *  @since 0.9.71+
     */
    static boolean attachLookupKey(ConcurrentHashMap<Hash, AtomicInteger> inFlight, Hash key, int limit) {
        AtomicInteger counter = inFlight.get(key);
        if (counter != null) {
            // joining an in-flight search: no additional slot consumed
            counter.incrementAndGet();
            return true;
        }
        if (inFlight.size() >= limit) {return false;}
        AtomicInteger created = new AtomicInteger(1);
        AtomicInteger prev = inFlight.putIfAbsent(key, created);
        if (prev != null) {
            // lost the race; join the winner's refcount
            prev.incrementAndGet();
        }
        return true;
    }

    /**
     *  Release one build request's attachment to an in-flight lookup key,
     *  dropping the key entry once its last waiting request completes so
     *  released slots become visible to new arrivals.
     *
     *  Pure decision on its map argument — safe for unit tests.
     *
     *  @param inFlight distinct next-hop keys currently being resolved
     *  @param key next hop being released by one request
     *  @since 0.9.71+
     */
    static void releaseLookupKey(ConcurrentHashMap<Hash, AtomicInteger> inFlight, Hash key) {
        AtomicInteger counter = inFlight.get(key);
        if (counter != null && counter.decrementAndGet() <= 0) {
            inFlight.remove(key, counter);
        }
    }

    /**
     *  Schedule an independent deadline that releases this request's lookup
     *  attachment if neither callback has done so by then.
     *
     *  Slot release must never depend solely on the netdb callbacks: any
     *  silent path between attach and callback registration (a dropped job,
     *  a search that never schedules, a constructor failure) would leak the
     *  key forever, pinning the distinct-key set at its ceiling and - since
     *  the pending queue drains only from release callbacks - freezing the
     *  queue full until restart. Observed live as exactly that deadlock.
     *  With this deadline, every attachment is guaranteed to release within
     *  lookupTimeout + margin, so leaks self-heal and the queue always has
     *  a drain trigger.
     *
     *  @param state the build request state carrying the per-request
     *               released flag shared with the callbacks
     *  @param nextPeer the attached next hop
     *  @param decremented exactly-once flag shared with HandleReq/TimeoutReq
     */
    private void scheduleLookupDeadline(BuildMessageState state, Hash nextPeer, AtomicBoolean decremented) {
        JobImpl deadline = new JobImpl(_context) {
            @Override
            public String getName() { return "Next-hop lookup slot deadline"; }
            @Override
            public void runJob() {
                if (!decremented.getAndSet(true)) {
                    // callbacks never fired (or fired without releasing) -
                    // reclaim the slot on deadline and wake the queue
                    releaseLookupKey(_lookupKeys, nextPeer);
                    drainPendingLookups();
                    if (_log.shouldInfo()) {
                        _log.info("Lookup slot reclaimed at deadline for [" +
                                  nextPeer.toBase64().substring(0,6) + "]");
                    }
                }
            }
        };
        deadline.getTiming().setStartAfter(_context.clock().now() +
                                           getNextHopLookupTimeout(_context) + LOOKUP_DEADLINE_MARGIN_MS);
        _context.jobQueue().addJob(deadline);
    }

    /**
     * How long a queued next-hop lookup may wait before it is discarded as
     * stale. Capped by the originator's build request timeout less the lookup
     * timeout: past that budget the originator has given up, so completing
     * the join is wasted work, while discarding earlier abandons builds that
     * could still have succeeded within the originator's budget.
     *
     * @param requestTimeoutMs the originator's build request timeout in ms
     * @param nextHopLookupTimeoutMs the per-lookup timeout in ms
     * @return the pending entry max age in ms, always positive
     */
    static long pendingLookupMaxAge(long requestTimeoutMs, long nextHopLookupTimeoutMs) {
        long rv = requestTimeoutMs - nextHopLookupTimeoutMs;
        return rv > 0 ? rv : Math.max(2 * nextHopLookupTimeoutMs, 1);
    }
    private static long getMaxRequestFuture(RouterContext ctx) {
        refreshBuildConfig(ctx);
        return _cachedMaxRequestFuture;
    }
    private static long getMaxRequestAge(RouterContext ctx) {
        refreshBuildConfig(ctx);
        return _cachedMaxRequestAge;
    }
    private static long getMaxRequestAgeEcies(RouterContext ctx) {
        refreshBuildConfig(ctx);
        return _cachedMaxRequestAgeEcies;
    }

    private static long getJobLagLimitTunnel(RouterContext ctx) {
        refreshBuildConfig(ctx);
        return _cachedJobLagLimitTunnel;
    }

    /**
     *  Upper bound on participating tunnels before the router drops
     *  incoming build requests, tunable via router.maxParticipatingTunnels.
     */
    private static int getMaxParticipatingTunnels(RouterContext ctx) {
        refreshBuildConfig(ctx);
        return _cachedMaxParticipatingTunnels;
    }
    private static final long[] RATES = RateConstants.SHORT_TERM_RATES;

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

    /** @since 0.9.70+ */
    public static int getMaxQueue() { return _maxQueue; }
    /** @since 0.9.70+ */
    public static void setMaxQueue(int val) { _maxQueue = Math.max(16, Math.min(2048, val)); }

    /**
     * BuildHandler.
     */
    public BuildHandler(RouterContext ctx, TunnelPoolManager manager, BuildExecutor exec) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _manager = manager;
        _exec = exec;
        _banLogger = new BanLogger();
        _banLogger.initialize(ctx);
        // Queue size = 12 * share BW / 48K
        int sz = ctx.getProperty(PROP_MAX_QUEUE, _maxQueue);
        _inboundBuildMessages = new LinkedBlockingQueue<>(sz);
        ctx.statManager().createRequiredRateStat("tunnel.buildLookupSuccess", "Confirmation of successful deferred lookup", "Tunnels", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.buildReplyTooSlow", "Received a tunnel build reply after timeout", "Tunnels", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.corruptBuildReply", "Corrupt tunnel build replies received", "Tunnels", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropConnLimits", "Dropped not rejected tunnel build (connection limits)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropDecryptFail", "Dropped tunnel build (decryption failed)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.handleRemaining", "Waiting inbound requests after 1 pass", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.nextHopLookupTimeout", "Timeout for next hop lookup (ms)", "Tunnels", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.receiveRejectionBandwidth", "Received tunnel build rejection (bandwidth overload)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.receiveRejectionCritical", "Received tunnel build rejection (critical failure)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.receiveRejectionProbabalistic", "Received tunnel build rejection probabalistically", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.receiveRejectionTransient", "Received tunnel build rejection (transient overload)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.reject.30", "Rejected a tunnel (bandwidth overload)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.rejectConnLimits", "Rejected tunnel build (connection limits)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.rejectFuture", "Rejected tunnel build (time in future)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.rejectTimeout2", "Rejected tunnel build (can't contact next hop)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.rejectTimeout", "Rejected tunnel build (unknown next hop)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropLookupStale", "Dropped deferred next-hop lookup (expired in queue)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.rejectTooOld", "Rejected tunnel build (too old)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.buildHandler.queueSize", "Build handler inbound queue depth", "Tunnels", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.acceptLoad", "Delay processing accepted request (ms)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.decryptRequestTime", "Time to decrypt a build request (ms)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropLoadBacklog", "Pending request count when dropped", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropLoad", "Delay before dropping request (ms)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropLoadDelay", "Delay before abandoning request (ms)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropLoadProactiveAbort", "Allowed requests during load", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropLoadProactive", "Delay estimate when dropped (ms)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropLookupThrottle", "Dropped tunnel build (hop lookup limit)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.pendingLookupQueue", "Pending lookup queue size", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.dropReqThrottle", "Dropped tunnel build (request limit)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.nextHopLookupSuccessTime", "Time taken for successful remote next hop lookup (ms)", "Tunnels", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.buildBanHit", "Build request next-hop is banned", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.rejectHopThrottle", "Rejected tunnel build (per-hop limit)", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.rejectHostile", "Rejected malicious tunnel build", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.rejectOverloaded", "Delay processing rejected request (ms)", "Tunnels [Participating]", RATES);
        _processor = new BuildMessageProcessor(ctx);
        boolean testMode = ctx.getBooleanProperty("i2np.allowLocal"); // previous hop, all requests
        boolean shouldThrottle = _context.getBooleanPropertyDefaultTrue(PROP_SHOULD_THROTTLE);
        _requestThrottler = testMode || !shouldThrottle ? null : new RequestThrottler(ctx);
        _throttler = testMode || !shouldThrottle ? null : new ParticipatingThrottler(ctx); // previous and next hops, successful builds only
        // Start idle tunnel monitor to detect and drop abusive idle tunnels
        _idleTunnelMonitor = shouldThrottle ? new IdleTunnelMonitor(ctx) : null;
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
            _log.info("Starting next-hop timeout at " + (getNextHopLookupTimeout(_context) / 1000.0) + "s");
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
        if (_idleTunnelMonitor != null) {
            _idleTunnelMonitor.shutdown();
        }
    }

    /**
     * Thread to handle inbound requests
     * @since 0.8.11
     */
    @Override
    public void run() {
        _isRunning = true;
        while (!_manager.isShutdown() && !Thread.currentThread().isInterrupted()) {
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
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        // check for poison
        if (state.msg == null) {Thread.currentThread().interrupt(); return;}
        long now = System.currentTimeMillis();
        long uptime = _context.router().getUptime();
        // Half the originator's adaptive budget: a request older than this can
        // no longer reliably reach its originator before they give up, but
        // dropping earlier abandons builds that would still have succeeded.
        long dropBefore = now - (BuildRequestor.getRequestTimeout(_context) / 2);
        int maxTunnels = getMaxParticipatingTunnels(_context);
        long lag = _context.jobQueue().getMaxLag();
        boolean isLagged = lag > getJobLagLimitTunnel(_context) && maxTunnels > 0 && uptime > 5*60*1000L;
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
            return;
        }
        if (highLoad && maxTunnels > 0) {
            if (_log.shouldWarn()) {
                _log.warn("Dropping Tunnel Request -> System under load");
                _context.throttle().setTunnelStatus("[rejecting/overload]" + _x("Dropping Tunnel Requests:<br>High CPU load"));
            }
            return;
        }
        handleRequest(state, now);
    }

    /**
     * Blocking call to handle a single inbound reply
     */
    private void handleReply(BuildReplyMessageState state) {
        // search through the tunnels for a reply
        long replyMessageId = state.msg.getUniqueId();
        PooledTunnelCreatorConfig cfg = _exec.removeFromBuilding(replyMessageId);
        if (cfg == null) { // cannot handle - not pending... took too long?
            if (_log.shouldWarn()) {
                _log.warn("Reply [MsgID " + replyMessageId + "] did not match any pending tunnels");
            }
            _context.statManager().addRateData("tunnel.buildReplyTooSlow", 1);
        } else {handleReply(state.msg, cfg, System.currentTimeMillis() - state.recvTime);}
    }

    /**
     * Blocking call to handle a single inbound reply
     */
    private void handleReply(TunnelBuildReplyMessage msg, PooledTunnelCreatorConfig cfg, long delay) {
        long requestedOn = cfg.getExpiration() - 10*60*1000L;
        long rtt = System.currentTimeMillis() - requestedOn;
        if (rtt < 0) {rtt = 0;}
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
                // Label-only lookup: use the cached entry without triggering
                // validation or network lookups while processing replies.
                RouterInfo ri = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
                String bwTier = "Unknown";
                if (ri != null) {
                    bwTier = ri.getBandwidthTier();
                } else if (_log.shouldLog(Log.WARN)) {
                    _log.warn("Failed detecting bwTier, null routerInfo for: " + peer);
                }
                if (howBad == 0) {
                    // Record that a peer of the given tier agreed or rejected
                    _context.statManager().addRateData("tunnel.tierAgree" + bwTier, 1);
                    _context.profileManager().tunnelJoined(peer, rtt);
                    // Proven-responder proof: this hop just carried a build to
                    // completion, so prefer it in future selections
                    TunnelPeerSelector._provenResponders.put(peer, _context.clock().now());
                    Properties props = statuses[record].props;
                    if (props != null) {
                        String avail = props.getProperty(BuildRequestor.PROP_AVAIL_BW);
                        if (avail != null && _log.shouldWarn()) {
                            _log.warn(msg.getUniqueId() + ": peer replied available: " + avail + "KBps");
                        }
                    }
                } else {
                    _context.statManager().addRateData("tunnel.tierReject" + bwTier, 1);
                    allAgree = false;
                    String reason;
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
                    // and keep them out of the immediate retry selection: we
                    // know exactly who said no
                    TunnelPeerSelector._peerCooldowns.put(peer, _context.clock().now());
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
                long posRtt = Math.max(0, rtt);
                if (cfg.getDestination() == null) {_context.statManager().addRateData("tunnel.buildExploratorySuccess", posRtt);}
                else {_context.statManager().addRateData("tunnel.buildClientSuccess", posRtt);}
            } else {
                // someone is no fun
                _exec.buildComplete(cfg, REJECT);
                long posRtt = Math.max(0, rtt);
                if (cfg.getDestination() == null) {_context.statManager().addRateData("tunnel.buildExploratoryReject", posRtt);}
                else {_context.statManager().addRateData("tunnel.buildClientReject", posRtt);}
            }
        } else {
            if (_log.shouldWarn()) {
                _log.warn("Tunnel reply [MsgID " + msg.getUniqueId() + "] could not be decrypted for tunnel " + cfg);
            }
            _context.statManager().addRateData("tunnel.corruptBuildReply", 1);
            _exec.buildComplete(cfg, BAD_RESPONSE); // don't leak
            // The corrupt record is unattributable, so cool down every hop of
            // the failed build out of the immediate retry selection.
            _exec.cooldownFailedPeers(cfg);
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
            if (caps != null && caps.indexOf(Router.CAPABILITY_NO_TUNNELS) >= 0) {
                    _context.statManager().addRateData("tunnel.dropTunnelFromCongestionCapability", 1);
                    if (_log.shouldWarn() && from != null) {
                        _log.warn("Dropped request from [" + from.toBase64().substring(0,6) + "] -> Local congestion");
                    }
                    // Presence-only check: this is a hot path, unvalidated lookup is sufficient
                    DatabaseEntry de = _context.netDb().lookupLocallyWithoutValidation(from);
                    if (de != null && de.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                        RouterInfo fromRI = (RouterInfo) de;
                        String fromVersion = fromRI.getVersion();
                        // If fromVersion is greater than 0.9.58, then then ban the router due to it
                        // disrespecting our congestion flags
                        if (fromVersion != null && VersionComparator.comp(fromVersion, MIN_VERSION_HONOR_CAPS) >= 0) {
                            _context.statManager().addRateData("tunnel.dropTunnelFromCongestionCapability" + from, 1);
                            _context.statManager().addRateData("tunnel.dropTunnelFromCongestionCapability" + fromVersion, 1);
                        }
                    }
                    return -1;
            }
        }
        if (timeSinceReceived > (BuildRequestor.getRequestTimeout(_context)*3)) {
            // don't even bother, since we are so overloaded locally
            _context.throttle().setTunnelStatus("[rejecting/overload]" + _x("Dropping Tunnel Requests: Overloaded"));
            if (_log.shouldWarn()) {
                _log.warn("Not trying to handle/decrypt stale request " + (state.msg != null ? String.valueOf(state.msg.getUniqueId()) : "null") +
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
            _log.warn("Timeout decrypting request: " + decryptTime + " for message: " + (state.msg != null ? String.valueOf(state.msg.getUniqueId()) : "null") +
                      " received " + (timeSinceReceived+decryptTime) + "ms ago");
        }
        if (req == null) {
            _context.statManager().addRateData("tunnel.dropDecryptFail", 1);
            if (from != null) {
                _context.commSystem().mayDisconnect(from);
                // no records matched, or the decryption failed. bah
                if (_log.shouldInfo()) {
                    _log.info("Request [MsgID " + (state.msg != null ? String.valueOf(state.msg.getUniqueId()) : "null") + "] could not be decrypted from [" +
                              from.toBase64().substring(0,6) + "]");
                }
            }
            return -1;
        }

        Hash nextPeer = req.readNextIdentity();
        if (nextPeer == null) {
            if (from != null) {_context.commSystem().mayDisconnect(from);}
            return -1;
        }
        if (_context.banlist().isBanlisted(nextPeer)) {
            if (_log.shouldWarn()) {
                _log.warn("Dropping Tunnel Request -> Next peer [" + nextPeer.toBase64().substring(0,6) + "] is banned");
            }
            _context.statManager().addRateData("tunnel.buildBanHit", 1);
            if (from != null) {_context.commSystem().mayDisconnect(from);}
            return -1;
        }

        RouterInfo nextPeerInfo = _context.netDb().lookupRouterInfoLocally(nextPeer);
        if (nextPeerInfo == null) {
            long lookupStartTime = System.currentTimeMillis();
            state.setLookupStartTime(lookupStartTime);
            int numTunnels = _context.tunnelManager().getParticipatingCount();
            int limit = lookupLimit(numTunnels, getMinLookupLimit(_context), getMaxLookupLimit(_context),
                                    getPercentLookupLimit(_context), _context.router().getUptime());
            if (attachLookupKey(_lookupKeys, nextPeer, limit)) {
                AtomicBoolean decremented = new AtomicBoolean(false);
                if (_log.shouldInfo()) {
                    _log.info("Looking up next hop [" + nextPeer.toBase64().substring(0,6) +
                              "] -> Distinct lookups: " + _lookupKeys.size() + " / " + limit + req);
                }
                _context.netDb().lookupRouterInfo(nextPeer, new HandleReq(_context, state, req, nextPeer, decremented),
                                                  new TimeoutReq(_context, state, req, nextPeer, decremented), getNextHopLookupTimeout(_context));
                scheduleLookupDeadline(state, nextPeer, decremented);
            } else {
                int maxPending = getMaxPendingLookups(_context);
                if (_pendingLookups.size() < maxPending) {
                    _pendingLookups.offer(new PendingLookup(state, req, nextPeer));
                    _context.statManager().addRateData("tunnel.pendingLookupQueue", _pendingLookups.size());
                    if (_log.shouldInfo()) {
                        _log.info("Queuing pending lookup for [" + nextPeer.toBase64().substring(0,6) +
                                  "] -> Queue size: " + _pendingLookups.size() + " / Lookups: " + _lookupKeys.size() + " / " + limit + req);
                    }
                } else {
                    // No explicit reject is possible here: the build reply can
                    // only travel back through the next hop we failed to
                    // resolve, so the originator sees a timeout. The pending
                    // queue and its stale window keep these drops rare.
                    _context.statManager().addRateData("tunnel.dropLookupThrottle", 1);
                    if (_log.shouldInfo()) {
                        _log.info("Dropping tunnel build [MsgID " + (state.msg != null ? String.valueOf(state.msg.getUniqueId()) : "null") + "] -> Lookup queue full (" + _pendingLookups.size() + ")");
                    }
                }
                return -1;
            }
            return -1;
        } else {
            long beforeHandle = System.currentTimeMillis();
            handleReq(nextPeerInfo, state, req, nextPeer);
            long handleTime = System.currentTimeMillis() - beforeHandle;
            long msgId = 0;
            if (state.msg != null) {msgId = state.msg.getUniqueId();}
            if (_log.shouldDebug()) {
                String nextHop = (nextPeer != null) ? "and next hop [" + nextPeer.toBase64().substring(0, 6) + "] is known" : "";
                String fromHash = from != null ? from.toString() : "Unknown";
                if (handleTime > 0) {
                    _log.debug(String.format(
                        "Build tunnel request [MsgID: %s] handled in %dms %s %n* Decrypted in: %dms; Elapsed time since received: %dms%n* From: %s %s",
                        msgId, handleTime, nextHop, decryptTime, timeSinceReceived, fromHash, req));
                } else {
                    _log.debug(String.format(
                        "Build tunnel request [MsgID: %s] handled %s %n* From: %s %s",
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
        else if (records == TunnelBuildMessageBase.MAX_RECORD_COUNT) {msg = new TunnelBuildReplyMessage(_context);}
        else {msg = new VariableTunnelBuildReplyMessage(_context, records);}
        for (int i = 0; i < records; i++) {msg.setRecord(i, state.msg.getRecord(i));}
        msg.setUniqueId(state.msg.getUniqueId());
        handleReply(msg, state.cfg, System.currentTimeMillis() - state.recvTime);
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

        /**
         * The name of this job.
         *
         * @return the name
         */
        @Override
        public String getName() {return "Defer Tunnel Join Processing";}

        /**
         * Complete the join request once the next hop is found.
         */
        @Override
        public void runJob() {
            long now = System.currentTimeMillis();

            if (_log.shouldDebug()) {
                _log.debug("Request " + _state.msg.getUniqueId() + " handled with a successful deferred lookup: " + _req);
            }
            RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(_nextPeer);
            if (ri != null && _state.claimHandled()) {
                long lookupTime = now - lookupStartTime;
                handleReq(ri, _state, _req, _nextPeer);
                getContext().statManager().addRateData("tunnel.buildLookupSuccess", 1);
                if (lookupTime > 0) {
                    getContext().statManager().addRateData("tunnel.nextHopLookupSuccessTime", lookupTime);
                }
                if (_log.shouldInfo()) {
                    _log.info("Successful lookup for [" + _nextPeer.toBase64().substring(0,6) + "] took " + lookupTime + "ms");
                }
            } else if (ri == null) {
                if (_log.shouldInfo()) {
                    _log.info("Lookup deferred, but we couldn't find [" + _nextPeer.toBase64().substring(0,6) + "] ? " + _req);
                }
                getContext().statManager().addRateData("tunnel.buildLookupSuccess", 0);
            }
            if (!_decremented.getAndSet(true)) {
                releaseLookupKey(_lookupKeys, _nextPeer);
                drainPendingLookups();
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

        /**
         * The name of this job.
         *
         * @return the name
         */
        @Override
        public String getName() {return "Timeout Locating Peer for Tunnel Join";}

        /**
         * Reject the request. The lookup timeout is a local netdb miss, not a
         * fault of the next hop, so the peer's profile is left untouched and
         * any established connection is kept: blaming healthy peers here
         * degraded selection quality over time, and mayDisconnect() shed
         * connections that later builds could have used.
         */
        @Override
        public void runJob() {
            if (_state.claimHandled()) {
                getContext().statManager().addRateData("tunnel.rejectTimeout", 1);
                getContext().statManager().addRateData("tunnel.buildLookupSuccess", 0);
                Hash from = _state.fromHash;
                if (_log.shouldInfo()) {
                    if (from == null && _state.from != null) {from = _state.from.calculateHash();}
                    long started = _state.getLookupStartTime();
                    long elapsed = started > 0 ? System.currentTimeMillis() - started : -1;
                    String how = elapsed >= 0 ? "after " + elapsed + "ms"
                                              : getNextHopLookupTimeout(_context) / 1000 + "s budget";
                    _log.info("Lookup for next hop failed " + how + " " + _req +
                              "\n* From: " + from + " [MsgID " + _state.msg.getUniqueId() + "]");
                }
                _context.messageHistory().tunnelRejected(_state.fromHash, new TunnelId(_req.readReceiveTunnelId()), _nextPeer, "lookup fail");
            } else if (_log.shouldInfo()) {
                _log.info("Lookup for [" + _nextPeer.toBase64().substring(0,6) + "] completed after timeout fired, ignoring [MsgID " +
                          _state.msg.getUniqueId() + "]");
            }

            if (!_decremented.getAndSet(true)) {
                releaseLookupKey(_lookupKeys, _nextPeer);
                drainPendingLookups();
            }
        }
    }

    /**
     *  A pending lookup request waiting for a concurrent lookup slot.
     *
     *  @since 0.9.70+
     */
    private static class PendingLookup {
        final BuildMessageState state;
        final BuildRequestRecord req;
        final Hash nextPeer;
        final long queuedTime;

        PendingLookup(BuildMessageState state, BuildRequestRecord req, Hash nextPeer) {
            this.state = state;
            this.req = req;
            this.nextPeer = nextPeer;
            this.queuedTime = System.currentTimeMillis();
        }
    }

    /**
     *  Drain the pending lookup queue, starting lookups for entries
     *  while we have capacity. Discard stale entries.
     *
     *  @since 0.9.70+
     */
    private void drainPendingLookups() {
        int numTunnels = _context.tunnelManager().getParticipatingCount();
        int limit = lookupLimit(numTunnels, getMinLookupLimit(_context), getMaxLookupLimit(_context),
                                getPercentLookupLimit(_context), _context.router().getUptime());
        long maxAge = pendingLookupMaxAge(BuildRequestor.getRequestTimeout(_context), getNextHopLookupTimeout(_context));

        PendingLookup pending;
        while ((pending = _pendingLookups.pollFirst()) != null) {
            long age = System.currentTimeMillis() - pending.queuedTime;
            if (age > maxAge) {
                if (_log.shouldInfo()) {
                    _log.info("Discarding stale pending lookup for [" + pending.nextPeer.toBase64().substring(0,6) + "] after " + age + "ms");
                }
                _context.statManager().addRateData("tunnel.dropLookupStale", 1);
                continue;
            }
            // Presence-only check: this is a hot path, unvalidated lookup is sufficient
            DatabaseEntry de = _context.netDb().lookupLocallyWithoutValidation(pending.nextPeer);
            if (de != null && de.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                RouterInfo ri = (RouterInfo) de;
                handleReq(ri, pending.state, pending.req, pending.nextPeer);
                continue;
            }
            if (!attachLookupKey(_lookupKeys, pending.nextPeer, limit)) {
                // Re-insert at the head so FIFO order is preserved for the
                // next drain once a lookup slot frees up.
                _pendingLookups.addFirst(pending);
                break;
            }
            AtomicBoolean decremented = new AtomicBoolean(false);
            if (_log.shouldInfo()) {
                _log.info("Draining pending lookup for [" + pending.nextPeer.toBase64().substring(0,6) +
                          "] -> Distinct lookups: " + _lookupKeys.size() + " / " + limit);
            }
            _context.netDb().lookupRouterInfo(pending.nextPeer,
                new HandleReq(_context, pending.state, pending.req, pending.nextPeer, decremented),
                new TimeoutReq(_context, pending.state, pending.req, pending.nextPeer, decremented),
                getNextHopLookupTimeout(_context));
            scheduleLookupDeadline(pending.state, pending.nextPeer, decremented);
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
        int bantime = 10*60*1000;
        int period = bantime / 60 / 1000;
        Hash from = state.fromHash;
        if (from == null && state.from != null) {from = state.from.calculateHash();}
        final boolean shouldThrottle = _context.getBooleanPropertyDefaultTrue(PROP_SHOULD_THROTTLE);
        final boolean shouldLog = _log.shouldDebug() || _log.shouldInfo() || _log.shouldWarn();
        String fromPeer = (shouldLog && from != null ? from.toBase64().substring(0,6) : "");
        String nextHop = (shouldLog && nextPeer != null ? nextPeer.toBase64().substring(0,6) : "");
        // Warning! from could be null, but should only happen if we will be IBGW and it came from us as OBEP
        if (isInGW && isOutEnd) {
            _context.statManager().addRateData("tunnel.rejectHostile", 1);
            if (from != null) {
                _context.commSystem().mayDisconnect(from);
                _banLogger.logBan(from, getIPPortFromHash(from), "Hostile Tunnel Request (IBGW+OBEP)", bantime);
                _context.banlist().banlistRouter(from, "Hostile Tunnel Request (IBGW+OBEP)", null, null, System.currentTimeMillis() + bantime);
                if (shouldLog) {_log.warn("Banning [" + fromPeer + "] for " + period + "m -> Hostile Tunnel Request (Inbound Gateway & Outbound Endpoint)");}
            } else if (shouldLog) {_log.warn("Dropping HOSTILE Tunnel Request from UNKNOWN -> IBGW+OBEP");}
            return;
        }
        if (ourId <= 0 || ourId > TunnelId.MAX_ID_VALUE || nextId <= 0 || nextId > TunnelId.MAX_ID_VALUE) {
            _context.statManager().addRateData("tunnel.rejectHostile", 1);
            if (from != null) {
                _context.commSystem().mayDisconnect(from);
                _banLogger.logBan(from, getIPPortFromHash(from), "Hostile Tunnel Request (BAD Tunnel ID)", bantime);
                _context.banlist().banlistRouter(from, "Hostile Tunnel Request (BAD Tunnel ID)", null, null, System.currentTimeMillis() + bantime);
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
            if (from != null) {
                _context.commSystem().mayDisconnect(from);
                _banLogger.logBan(from, getIPPortFromHash(from), "Hostile Tunnel Request (double hop)", bantime);
                _context.banlist().banlistRouter(from, "Hostile Tunnel Request (double hop)", null, null, System.currentTimeMillis() + bantime);
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
                if (from != null) {
                    _context.commSystem().mayDisconnect(from);
                    _banLogger.logBan(from, getIPPortFromHash(from), "Hostile Tunnel Request (previous hop)", bantime);
                    _context.banlist().banlistRouter(from, "Hostile Tunnel Request (previous hop)", null, null, System.currentTimeMillis() + bantime);
                    if (shouldLog) {_log.warn("Banning [" + fromPeer + "] for " + period + "m -> Hostile Tunnel Request (We are the previous hop!)");}
                } else if (shouldLog) {_log.warn("Dropping HOSTILE Tunnel Request from UNKNOWN -> We are the previous hop");}
                return;
            }
        }
        if ((!isOutEnd) && (!isInGW)) {
            // Previous and next hop the same? Don't help somebody be evil. Drop it without a reply.
            // A-B-C-A is not preventable
            if (nextPeer != null && nextPeer.equals(from)) {
                // i2pd does this
                _context.statManager().addRateData("tunnel.rejectHostile", 1);
                if (from != null) {
                    _context.commSystem().mayDisconnect(from);
                    _banLogger.logBan(from, getIPPortFromHash(from), "Hostile Tunnel Request (duplicate hops)", bantime);
                    _context.banlist().banlistRouter(from, "Hostile Tunnel Request (duplicate hops)", null, null, System.currentTimeMillis() + bantime);
                    if (shouldLog) {_log.warn("Banning [" + fromPeer + "] for " + period + "m -> Hostile Tunnel Request (duplicate hops in chain)");}
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
            long roundedNow = (now / (60*1000L)) * (60*1000L);
            timeDiff = roundedNow - time;
            maxAge = getMaxRequestAgeEcies(_context);
        } else {
            // time is in hours, rounded down.
            // tunnel-alt-creation.html specifies that this is enforced +/- 1 hour but it was not.
            // As of 0.9.16, allow + 5 minutes to - 65 minutes.
            long roundedNow = (now / (60*60*1000L)) * (60*60*1000L);
            timeDiff = roundedNow - time;
            maxAge = getMaxRequestAge(_context);
        }
        if (timeDiff > maxAge) {
            _context.statManager().addRateData("tunnel.rejectTooOld", 1);
            if (_log.shouldWarn()) {
                _log.warn("Dropping HOSTILE Tunnel Request -> Too old... replay attack? " + DataHelper.formatDuration(timeDiff) + " " + req);
            }
            if (from != null) {
                _context.commSystem().mayDisconnect(from);
                _banLogger.logBan(from, getIPPortFromHash(from), "Hostile Tunnel Request (possible replay attack)", bantime);
                _context.banlist().banlistRouter(from, "Hostile Tunnel Request (possible replay attack)", null, null, System.currentTimeMillis() + bantime);
                if (shouldLog) {_log.warn("Banning [" + fromPeer + "] for " + period + "m -> Hostile Tunnel Request (too old, replay attack?)");}
            }
            return;
        }
        if (timeDiff < 0 - getMaxRequestFuture(_context)) {
            _context.statManager().addRateData("tunnel.rejectFuture", 1);
            if (_log.shouldWarn()) {
                _log.warn("Dropping HOSTILE Tunnel Request -> Too far in future " + DataHelper.formatDuration(0 - timeDiff) + " " + req);
            }
            if (from != null) {
                _context.commSystem().mayDisconnect(from);
                _banLogger.logBan(from, getIPPortFromHash(from), "Hostile Tunnel Request (too far in future)", bantime);
                _context.banlist().banlistRouter(from, "Hostile Tunnel Request (too far in future)", null, null, System.currentTimeMillis() + bantime);
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
            float pDrop = ((float) recvDelay) / (float) (BuildRequestor.getRequestTimeout(_context)*3);
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
                    ((isInGW && !_context.commSystem().haveInboundCapacity(93)) ||
                    (isOutEnd && !_context.commSystem().haveOutboundCapacity(97)))) {
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
                _context.throttle().setTunnelStatus("[rejecting/transit]" + _x("Transit throttle drop"));
                return;
            }
            if (result == ParticipatingThrottler.Result.REJECT) {
                if (_log.shouldInfo() && from != null && req != null) {
                    _log.info("Rejecting Tunnel Request at previous hop (throttled) -> [" + fromPeer + "] " + (_log.shouldInfo() ? req : ""));
                }
                _context.statManager().addRateData("tunnel.rejectHopThrottle", 1);
                _context.throttle().setTunnelStatus("[rejecting/transit]" + _x("Transit throttle reject"));
                response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
            }
        }
        if (response == 0 && (!isOutEnd) && _throttler != null && shouldThrottle) {
            // Consult-only for the next hop: this request did not advance its
            // counter, so it must not DROP or ban on it.
            ParticipatingThrottler.Result result = _throttler.shouldThrottle(nextPeer, false);
            if (result == ParticipatingThrottler.Result.DROP) {
                if (_log.shouldInfo()) {
                    _log.info("Dropping Tunnel Request at next hop (throttled) -> [" + nextHop + "] " + (_log.shouldInfo() ? req : ""));
                }
                _context.statManager().addRateData("tunnel.rejectHopThrottle", 1);
                _context.throttle().setTunnelStatus("[rejecting/transit]" + _x("Transit throttle drop"));
            }
            if (result == ParticipatingThrottler.Result.REJECT) {
                if (_log.shouldInfo()) {
                    _log.info("Rejecting Tunnel Request at next hop (throttled) -> [" + nextHop + "] " + (_log.shouldInfo() ? req : ""));
                }
                _context.statManager().addRateData("tunnel.rejectHopThrottle", 1);
                _context.throttle().setTunnelStatus("[rejecting/transit]" + _x("Transit throttle reject"));
                response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
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

                }
            }
        }
        HopConfig cfg = null;
        if (response == 0) {
            cfg = new HopConfig();
            cfg.setCreation(now);
            cfg.setExpiration(now + 10*60*1000L);
            cfg.setIVKey(req.readIVKey());
            cfg.setLayerKey(req.readLayerKey());
            if (isInGW) {
                // default
            } else {
                if (from != null) {cfg.setReceiveFrom(from);}
                else {return;} // b0rk
            }
            cfg.setReceiveTunnelId(ourId);
            if (isOutEnd) {
                // default
                //cfg.setSendTunnelId(null);
            } else {
                cfg.setSendTo(nextPeer);
                cfg.setSendTunnelId(nextId);
            }
            if (avail > 0) {cfg.setAllocatedBW(avail);}
            else {cfg.setAllocatedBW(RouterThrottleImpl.getMinBandwidthFloorPerTunnel(_context));}
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
            // 96% = between control measures in Transports and 97% rejection above
            if ((!_context.routerHash().equals(nextPeer)) &&
                (!_context.commSystem().haveOutboundCapacity(96)) &&
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
        long expires = now + getNextHopLookupTimeout(_context);
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
            } else if (records == TunnelBuildMessageBase.MAX_RECORD_COUNT) {replyMsg = new TunnelBuildReplyMessage(_context);}
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

    /**
     * The number of inbound build messages queued.
     *
     * @return the inbound build queue size
     */
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
        @Override
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
                        // Half the originator's budget: see handleInboundRequest()
                        if (age >= BuildRequestor.getRequestTimeout(_context)/2) {
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
                            _context.throttle().setTunnelStatus("[rejecting/transit]" + _x("Transit request throttle"));
                            accept = false;
                        }
                    }
                    if (accept) {
                        accept = _inboundBuildMessages.offer(new BuildMessageState(_context, receivedMessage, from, fromHash));
                        if (accept) {
                            _exec.repoll(); // wake up the Executor to call handleInboundRequests()
                            _context.statManager().addRateData("tunnel.buildHandler.queueSize", _inboundBuildMessages.size());
                        } else {
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
        /**
         * Queue the reply message state and return the handler job.
         */
        @Override
        public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
            if (_log.shouldDebug()) {
                String fromName;
                if (fromHash != null) {
                    fromName = fromHash.toString();
                } else if (from != null) {
                    fromName = from.calculateHash().toString();
                } else {
                    fromName = "a tunnel";
                }
                _log.debug("Received TunnelBuildReplyMessage " + receivedMessage.getUniqueId() + " from " +
                           fromName);
            }
            handleReply(new BuildReplyMessageState(receivedMessage));
            return _buildReplyMessageHandlerJob;
        }
    }

    /** Normal inbound requests from other people. */
    private static class BuildMessageState implements CDQEntry {
        private final RouterContext _ctx;
        final TunnelBuildMessage msg;
        final RouterIdentity from;
        final Hash fromHash;
        final long recvTime;
        private long lookupStartTime = -1;
        /** Guards against a deferred lookup completing after its timeout fired, or vice versa */
        private final AtomicBoolean _handled = new AtomicBoolean(false);

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
        /**
         * No-op; the enqueue time is fixed at construction.
         */
        @Override
        public void setEnqueueTime(long time) {
            // intentionally empty - enqueue time is set at construction, no need to update
        }
        /**
         * The time the message was received.
         *
         * @return the enqueue time
         */
        @Override
        public long getEnqueueTime() {return recvTime;}
        /**
         * The time the next hop lookup started.
         */
        public void setLookupStartTime(long time) {this.lookupStartTime = time;}
        /**
         * The time the next hop lookup started.
         *
         * @return the lookup start time
         */
        public long getLookupStartTime() {return lookupStartTime;}
        /**
         * Claim exclusive handling of this request: exactly one of the
         * deferred-lookup success job and its timeout job may act.
         *
         * @return true if this caller won and should process the request
         */
        boolean claimHandled() {return _handled.compareAndSet(false, true);}
        /**
         * Mark the request as dropped due to queue overload.
         */
        @Override
        public void drop() {
            _ctx.throttle().setTunnelStatus("[rejecting/overload]" + _x("Dropping Tunnel Requests: Queue time"));
            _ctx.statManager().addRateData("tunnel.dropLoadProactive", System.currentTimeMillis() - recvTime);
        }
    }

    /** Replies for outbound tunnels that we have created. */
    private static class BuildReplyMessageState {
        final TunnelBuildReplyMessage msg;
        final long recvTime;
        /**
         * The reply message and the time it was received.
         */
        public BuildReplyMessageState(I2NPMessage m) {
            msg = (TunnelBuildReplyMessage)m;
            recvTime = System.currentTimeMillis();
        }
    }

    /** Replies for inbound tunnels we have created. */
    private static class BuildEndMessageState {
        final TunnelBuildMessage msg;
        final PooledTunnelCreatorConfig cfg;
        final long recvTime;
        /**
         * The tunnel config, message, and receive time of a build end reply.
         */
        public BuildEndMessageState(PooledTunnelCreatorConfig c, I2NPMessage m) {
            cfg = c;
            msg = (TunnelBuildMessage)m;
            recvTime = System.currentTimeMillis();
        }
    }

    /** No-op job returned once a tunnel build message is handled. */
    private static class TunnelBuildMessageHandlerJob extends JobImpl {
        private TunnelBuildMessageHandlerJob(RouterContext ctx) {super(ctx);}
        /**
         * No-op; the message was already handled.
         */
        @Override
        public void runJob() {
            // No-op - intentionally empty
        }
        /**
         * The name of this job.
         *
         * @return the name
         */
        @Override
        public String getName() {return "Receive Tunnel Build Message";}
    }

    /** No-op job returned once a tunnel build reply message is handled. */
    private static class TunnelBuildReplyMessageHandlerJob extends JobImpl {
        private TunnelBuildReplyMessageHandlerJob(RouterContext ctx) {super(ctx);}
        /**
         * No-op; the message was already handled.
         */
        @Override
        public void runJob() {
            // No-op - intentionally empty
        }
        /**
         * The name of this job.
         *
         * @return the name
         */
        @Override
        public String getName() {return "Receive Tunnel Build Reply Message";}
    }

    /**
     *  Remove the participating tunnel if we can't contact the next hop
     *  Not strictly necessary, as the entry doesn't use that much space,
     *  but it affects capacity calculations
     */
    private static class TunnelBuildNextHopFailJob extends JobImpl {
        private final HopConfig _cfg;
        private TunnelBuildNextHopFailJob(RouterContext ctx, HopConfig cfg) {
            super(ctx);
            _cfg = cfg;
        }
        /**
         * The name of this job.
         *
         * @return the name
         */
        @Override
        public String getName() {return "Timeout Building Tunnel Hop";}
        /**
         * Remove the participating tunnel when the next hop cannot be contacted.
         */
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
            getContext().statManager().addRateData("tunnel.nextHopLookupTimeout", getNextHopLookupTimeout(getContext()));
            if (log.shouldDebug()) {
                log.debug("Timeout (" + (getNextHopLookupTimeout(getContext()) / 1000) + "s) contacting next hop" + _cfg);
            }
        }
    }

    /**
     * Extract IP:port from RouterInfo for a peer hash.
     * @return the i p port from hash
     */
    private String getIPPortFromHash(Hash h) {
        try {
            // Presence-only check: this is for logging only, unvalidated lookup is sufficient
            DatabaseEntry de = _context.netDb().lookupLocallyWithoutValidation(h);
            if (de != null && de.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                RouterInfo ri = (RouterInfo) de;
                for (RouterAddress ra : ri.getAddresses()) {
                    if (ra != null) {
                        String host = ra.getHost();
                        int port = ra.getPort();
                        if (host != null && port > 0) {
                            return host + ":" + port;
                        }
                    }
                }
            }
        } catch (Exception e) { /* ignored */ }
        return "UNKNOWN";
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    private static final String _x(String s) {return s;}
}
