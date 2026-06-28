package net.i2p.router.tunnel.pool;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.stat.RateConstants;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelInfo;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Single threaded controller of the tunnel creation process, spanning all tunnel pools.
 * Essentially, this loops across the pools, sees which want to build tunnels, and fires
 * off the necessary activities if the load allows.  If nothing wants to build any tunnels,
 * it waits for a short period before looping again (or until it is told that something
 * changed, such as a tunnel failed, new client started up, or tunnel creation was aborted).
 *
 * Note that 10 minute tunnel expiration is hardcoded in here.
 *
 * As of 0.8.11, inbound request handling is done in a separate thread.
 */
class BuildExecutor implements Runnable {
    private static final int TUNNEL_MIN_EXPIRY_MS = 5*60*1000;
    private static int getTunnelTargetMin(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.build.targetMin", 2);
    }
    private final ArrayList<Long> _recentBuildIds = new ArrayList<>(256);
    private final RouterContext _context;
    private final Log _log;
    private final TunnelPoolManager _manager;
    private final GhostPeerManager _ghostPeerManager;
    private final Object _currentlyBuilding; // Notify lock
    private final ConcurrentHashMap<Long, PooledTunnelCreatorConfig> _currentlyBuildingMap; // indexed by ptcc.getReplyMessageId()
    private final ConcurrentHashMap<Long, PooledTunnelCreatorConfig> _recentlyBuildingMap; // indexed by ptcc.getReplyMessageId()
    private volatile boolean _isRunning;
    private boolean _repoll;
    private final AtomicInteger _buildSuccessCount = new AtomicInteger();
    private final AtomicInteger _buildFailureCount = new AtomicInteger();
    private final ConcurrentHashMap<TunnelPool, Long> _lastRebuildTime = new ConcurrentHashMap<>(64);
    private final AtomicInteger _buildTimeoutCount = new AtomicInteger();
    private final AtomicInteger _firstHopSuccessCount = new AtomicInteger();
    private final AtomicInteger _firstHopFailureCount = new AtomicInteger();
    /**
     * Per-pool consecutive build failure tracking for backoff.
     * When a pool exceeds CONSECUTIVE_FAILURE_THRESHOLD, builds are
     * paused for POOL_BACKOFF_MS to prevent build storms.
     * Maps: TunnelPool -> [consecutiveFailures, backoffUntilMs]
     */
    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 3;
    private static final long POOL_BACKOFF_MS = 8 * 1000;
    private final ConcurrentHashMap<TunnelPool, long[]> _poolFailureState = new ConcurrentHashMap<>(64);
    private int _keepAliveCounter;
    private volatile long _adaptiveTimeout;
    private volatile long _adaptiveFirstHopTimeout;

    /**
     * Get the tunnel lifetime from config. Delegates to TunnelPool.
     */
    static int getTunnelLifetime(RouterContext ctx) {
        return TunnelPool.getTunnelLifetime(ctx);
    }

    /**
     * Get the target build buffer from config or default (0).
     * Extra tunnels to maintain beyond the configured quantity.
     * Tunable via i2p.tunnel.targetBuffer (default: 0).
     */
    static int getTunnelTargetBuffer(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.targetBuffer", 0);
    }

    /**
     * Get the GOOD deficit throttle interval from config or default (30s).
     * Minimum time between GOOD-tunnel deficit rebuilds for non-critical pools.
     * Tunable via i2p.tunnel.goodDeficitThrottle (default: 30000).
     */
    static long getGoodDeficitThrottle(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.goodDeficitThrottle", 30000);
    }
    /**
     * Get the maximum number of concurrent tunnel builds allowed.
     * Calculated based on CPU cores and configurable multiplier
     *
     * @return maximum concurrent builds allowed
     */
    private int getMaxConcurrentBuilds() {
        return Math.max(SystemVersion.getCores() * 2, 16);
    }
    private static final int LOOP_TIME = 1000; // calculate required tunnels to build every 1s
    private static final int TUNNEL_POOLS = 8;
    private static long getGracePeriod(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.build.gracePeriod", 60*1000);
    }
    private static final long[] RATES = { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR };
    /** @return true if full statistics are enabled */
    public boolean fullStats() {return _context.getBooleanProperty("stat.full");}

    /** Build result enumeration. @since 0.9.53 */
    enum Result { SUCCESS, REJECT, TIMEOUT, BAD_RESPONSE, DUP_ID, OTHER_FAILURE }

    /**
     * Create a new BuildExecutor.
     *
     * @param ctx the router context
     * @param mgr the tunnel pool manager
     * @param ghostMgr the ghost peer manager for tracking timeouts
     */
 public BuildExecutor(RouterContext ctx, TunnelPoolManager mgr, GhostPeerManager ghostMgr) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _manager = mgr;
        _ghostPeerManager = ghostMgr;
        _adaptiveTimeout = BuildRequestor.getRequestTimeout(ctx);
        _adaptiveFirstHopTimeout = BuildRequestor.getFirstHopTimeout(ctx);
        _currentlyBuilding = new Object();
        int maxConcurrentBuilds = getMaxConcurrentBuilds();
        _currentlyBuildingMap = new ConcurrentHashMap<>(maxConcurrentBuilds);
        _recentlyBuildingMap = new ConcurrentHashMap<>(4 * maxConcurrentBuilds);
        _context.statManager().createRequiredRateStat("tunnel.buildFailFirstHop", "OB tunnel build failure frequency (can't contact 1st hop)", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildReplySlow", "Build reply late, but not too late", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.concurrentBuildsLagged", "Concurrent build count before rejecting (job lag)", "Tunnels", RATES); // (period is lag)
        _context.statManager().createRequiredRateStat("tunnel.buildClientExpire", "No response to our build request", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildClientReject", "Response time for rejection (ms)", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildClientSuccess", "Response time for success (ms)", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildConfigTime", "Time to build a tunnel config (ms)", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildExploratoryExpire", "No response to our build request", "Tunnels [Exploratory]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildExploratoryReject", "Response time for rejection (ms)", "Tunnels [Exploratory]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildExploratorySuccess", "Response time for success (ms)", "Tunnels [Exploratory]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildRequestTime", "Time to build a tunnel request (ms)", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.concurrentBuilds", "How many builds are going at once", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildSuccessRate", "Tunnel build success rate (0-100)", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildFailureRate", "Tunnel build failure rate (0-100)", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildTimeoutRate", "Tunnel build timeout rate (0-100)", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.firstHopSuccessRate", "First-hop success count", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.firstHopFailureRate", "First-hop failure count", "Tunnels", RATES);

        StatManager statMgr = _context.statManager(); // Get stat manager, get recognized bandwidth tiers
        String bwTiers = RouterInfo.BW_CAPABILITY_CHARS; // For each bandwidth tier, create tunnel build agree/reject/expire stats
        for (int i = 0; i < bwTiers.length(); i++) {
            String bwTier = String.valueOf(bwTiers.charAt(i));
            statMgr.createRequiredRateStat("tunnel.tierAgree" + bwTier, "Agreed joins from bandwidth tier " + bwTier, "Tunnels [Participating]", RATES);
            statMgr.createRequiredRateStat("tunnel.tierReject" + bwTier, "Rejected joins from bandwidth tier " + bwTier, "Tunnels [Participating]", RATES);
            statMgr.createRequiredRateStat("tunnel.tierExpire" + bwTier, "Expired joins from bandwidth tier " + bwTier, "Tunnels [Participating]", RATES);
        }
    }

    /**
     *  @since 0.9
     */
    public synchronized void restart() {
        synchronized (_recentBuildIds) {_recentBuildIds.clear();}
        _currentlyBuildingMap.clear();
        _recentlyBuildingMap.clear();
    }

    /**
     *  Cannot be restarted.
     *  @since 0.9
     */
    public synchronized void shutdown() {
        _isRunning = false;
        restart();
    }

    /**
     * Update success/failure/timeout counters and calculate adaptive timeout
     */
    private void updateBuildStats(Result result) {
        StatManager sm = _context.statManager();
        if (result == Result.SUCCESS) {
            _buildSuccessCount.incrementAndGet();
            sm.addRateData("tunnel.buildSuccessRate", 100, 0);
        } else if (result == Result.TIMEOUT) {
            _buildTimeoutCount.incrementAndGet();
            sm.addRateData("tunnel.buildTimeoutRate", 100, 0);
        } else {
            _buildFailureCount.incrementAndGet();
            sm.addRateData("tunnel.buildFailureRate", 100, 0);
        }
        // Every 50 builds, recalculate adaptive timeout
        int total = _buildSuccessCount.get() + _buildFailureCount.get() + _buildTimeoutCount.get();
        if (total >= 50) {
            calculateAdaptiveTimeoutFromSuccess();
            // Reset counters periodically to favor recent behavior
            _buildSuccessCount.set(_buildSuccessCount.get() / 2);
            _buildFailureCount.set(_buildFailureCount.get() / 2);
            _buildTimeoutCount.set(_buildTimeoutCount.get() / 2);
        }
    }

    /**
     * Calculate adaptive timeouts based on recorded build outcomes.
     * Starts from mainline's base values (13s/10s) and adjusts
     * marginally in either direction based on success rate.
     *
     * Adaptive ranges:
     *   REQUEST_TIMEOUT:   13s base, 10-18s range
     *   FIRST_HOP_TIMEOUT: 10s base, 8-15s range
     */
    private void calculateAdaptiveTimeoutFromSuccess() {
        int successCount = _buildSuccessCount.get();
        int failureCount = _buildFailureCount.get();
        int timeoutCount = _buildTimeoutCount.get();
        int total = successCount + failureCount + timeoutCount;
        if (total < 10) { return; }

        double successRate = (double) successCount / total;

        // Base timeout from mainline (13s normal, 15s slow)
        long baseTimeout = BuildRequestor.getRequestTimeout(_context);

        // Start at base, then adjust marginally based on success rate
        _adaptiveTimeout = baseTimeout;

        if (successRate > 0.85) {
            // High success — network is fast. Reduce slightly.
            _adaptiveTimeout += -3 * 1000;  // -3s
        } else if (successRate > 0.70) {
            // Good success — keep near base.
            _adaptiveTimeout += 0;
        } else if (successRate > 0.50) {
            // Moderate success — modest increase.
            _adaptiveTimeout += 2 * 1000;  // +2s
        } else {
            // Low success — increase to give slow builds more time.
            _adaptiveTimeout += 5 * 1000;  // +5s
        }

        // Clamp: never below 10s regardless of rate; allow adaptive increase up to 30s
        if (_adaptiveTimeout < 10*1000) { _adaptiveTimeout = 10*1000; }
        if (_adaptiveTimeout > 25*1000) { _adaptiveTimeout = 25*1000; }

        // Also calculate adaptive first-hop timeout based on first-hop success rate
        int firstHopTotal = _firstHopSuccessCount.get() + _firstHopFailureCount.get();
        if (firstHopTotal >= 10) {
            double firstHopSuccessRate = (double) _firstHopSuccessCount.get() / firstHopTotal;

            // Base first-hop timeout from mainline (10s)
            long baseFirstHop = BuildRequestor.getFirstHopTimeout(_context);
            _adaptiveFirstHopTimeout = baseFirstHop;

            if (firstHopSuccessRate > 0.85) {
                _adaptiveFirstHopTimeout += -2 * 1000;
            } else if (firstHopSuccessRate > 0.70) {
                _adaptiveFirstHopTimeout += 0;
            } else if (firstHopSuccessRate > 0.50) {
                _adaptiveFirstHopTimeout += 2 * 1000;
            } else {
                _adaptiveFirstHopTimeout += 3 * 1000;
            }

            // Clamp: 8-15s
            if (_adaptiveFirstHopTimeout < 8*1000) { _adaptiveFirstHopTimeout = 8*1000; }
            if (_adaptiveFirstHopTimeout > 15*1000) { _adaptiveFirstHopTimeout = 15*1000; }

            if (_log.shouldDebug()) {
                _log.debug("Adaptive first-hop timeout: " + (_adaptiveFirstHopTimeout / 1000) +
                           "s (success: " + (int)(firstHopSuccessRate * 100) +
                           "%, failures: " + _firstHopFailureCount.get() + "/" + firstHopTotal + ")");
            }
        }

        if (_log.shouldDebug()) {
            _log.debug("Adaptive timeout: " + (_adaptiveTimeout / 1000) +
                       "s (success: " + (int)(successRate * 100) +
                       "%, timeouts: " + _buildTimeoutCount.get() +
                       "/" + total + ")");
        }
    }

    /**
     * Calculate adaptive timeout based on tunnel characteristics and network conditions
     *
     * @param cfg the tunnel configuration
     * @return adaptive timeout in milliseconds
     */
    private long calculateAdaptiveTimeout(PooledTunnelCreatorConfig cfg) {
        long baseTimeout = _adaptiveTimeout;

        // Adjust timeout based on tunnel length
        int length = cfg.getLength();
        if (length > 3) {
            baseTimeout += (length - 3) * 5*1000;
        }

        // Adjust based on system load
        int cpuLoad = SystemVersion.getCPULoadAvg();
        if (cpuLoad > 90) {
            baseTimeout += 3*1000;
        } else if (cpuLoad > 80) {
            baseTimeout += 2*1000;
        }

        // Outbound builds have a longer reply path: the build reply comes back
        // through an IB exploratory tunnel.  If exploratory tunnels are congested
        // (2 tunnels handling 18+ concurrent build replies), OB builds timeout
        // at 2x the rate of IB builds (54% vs 80% success).  Adding extra time
        // for OB builds compensates for this reply-path latency.
        if (!cfg.isInbound()) {
            baseTimeout += 5 * 1000;
        }

        // Cap at 45s safety ceiling
        return Math.min(baseTimeout, 45*1000);
    }

    /**
     * Determine if a tunnel config is for an outbound (non-inbound) tunnel.
     * Uses the parent class isInbound() to avoid importing TunnelPoolSettings.
     */

    /**
     * Determines allowed number of concurrent tunnel builds based on system status,
     * bandwidth limits, build times, system resources, and current tunnel build activity.
     * Also handles expiration and cleanup of old build requests.
     *
     * @return allowed number of concurrent tunnel builds
     */
    private int allowed() {
        final CommSystemFacade csf = _context.commSystem();
        if (csf.getStatus() == Status.DISCONNECTED) {
            return 0;
        }
        if (csf.isDummy() && csf.countActivePeers() <= 0) {
            return 0;
        }

        // Cache repeated system version calls and constants
        final boolean isSlow = SystemVersion.isSlow();
        final Hash selfHash = _context.routerHash();
        final long now = _context.clock().now();

        final int maxKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        int allowed = maxKBps / 2;
        if (allowed < 12) {allowed = 12;}

        final RateStat rs = _context.statManager().getRate("tunnel.buildRequestTime");
        double avg = -1;
        if (rs != null) {
            Rate r = rs.getRate(RateConstants.ONE_MINUTE);
            if (r != null) {
                avg = r.getAverageValue();
            } else if (rs.getLifetimeAverageValue() > 0) {
                avg = rs.getLifetimeAverageValue();
            }
        }

        int maxConcurrentBuilds = getMaxConcurrentBuilds();

        if (avg > 0) {
            int throttleFactor = isSlow ? 150 : 250;
            int throughput = (int)(throttleFactor * maxConcurrentBuilds / avg);
            if (throughput > allowed) {
                allowed = throughput; // Boost when builds are fast
            } else if (throughput < allowed) {
                allowed = throughput; // Throttle when builds are slow
            }
        }

        // Cap: never exceed maxConcurrentBuilds
        if (allowed > maxConcurrentBuilds) {
            allowed = maxConcurrentBuilds;
        }

        // Allow override through property
        allowed = _context.getProperty("router.tunnelConcurrentBuilds", allowed);

        // Constants for expiration calculations
        final long TEN_MINUTES_MS = 10 * 60 * 1000;
        final long expireRecentlyBefore = now + TEN_MINUTES_MS - BuildRequestor.getRequestTimeout(_context) + getGracePeriod(_context);
        final long expireBefore = now + TEN_MINUTES_MS - BuildRequestor.getRequestTimeout(_context);

        // Expire really old build requests from recentlyBuilding map
        for (Iterator<PooledTunnelCreatorConfig> iter = _recentlyBuildingMap.values().iterator(); iter.hasNext(); ) {
            PooledTunnelCreatorConfig cfg = iter.next();
            if (cfg.getExpiration() <= expireRecentlyBefore) {
                iter.remove();
            }
        }

        List<PooledTunnelCreatorConfig> expired = null;

        /* Expire old build requests from currentlyBuilding map, move them to recentlyBuilding
         * NOTE: cfg.getExpiration() includes TUNNEL stagger (0-300s), so comparing against it
         * directly would make timeouts take 20-320s.
         * Use creation time + adaptiveTimeout for consistent 20s timeout regardless of stagger.
         */
        for (Iterator<PooledTunnelCreatorConfig> iter = _currentlyBuildingMap.values().iterator(); iter.hasNext(); ) {
            PooledTunnelCreatorConfig cfg = iter.next();
            long adaptiveTimeout = calculateAdaptiveTimeout(cfg);
            long created = cfg.getConfig(0).getCreation();
            if (created > 0 && now - created >= adaptiveTimeout) {
                PooledTunnelCreatorConfig existingCfg = _recentlyBuildingMap.putIfAbsent(Long.valueOf(cfg.getReplyMessageId()), cfg);
                if (existingCfg == null) {
                    iter.remove();
                    if (expired == null) {
                        expired = new ArrayList<>();
                    }
                    expired.add(cfg);
                }
            }
        }

        int concurrent = _currentlyBuildingMap.size();
        allowed -= concurrent;

        if (expired != null) {
            for (PooledTunnelCreatorConfig cfg : expired) {
                if (_log.shouldInfo()) {
                    _log.info("Timeout (" + (_adaptiveTimeout / 1000) + "s) waiting for tunnel build reply -> " + cfg);
                }

                final int length = cfg.getLength();
                for (int iPeer = 0; iPeer < length; iPeer++) {
                    Hash peer = cfg.getPeer(iPeer);
                    if (peer.equals(selfHash)) {
                        continue; // Skip self
                    }
                    RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
                    String bwTier = "Unknown";
                    if (ri != null) {
                        bwTier = ri.getBandwidthTier();
                    }
                    _context.statManager().addRateData("tunnel.tierExpire" + bwTier, 1);
                    didNotReply(cfg.getReplyMessageId(), peer);
                    _context.profileManager().tunnelTimedOut(peer);
                    // Record timeout for ghost peer detection
                    if (_ghostPeerManager != null) {
                        _ghostPeerManager.recordTimeout(peer);
                    }
                }

                TunnelPool pool = cfg.getTunnelPool();
                if (pool != null) {
                    pool.buildComplete(cfg, Result.TIMEOUT);
                }
                // Update per-pool failure tracking so pool backoff engages on
                // consecutive timeouts (same as BuildExecutor.buildComplete()
                // does for BAD_RESPONSE/OTHER_FAILURE).
                if (pool != null) {
                    long[] state = _poolFailureState.get(pool);
                    if (state == null) {
                        state = new long[]{0, 0};
                        _poolFailureState.put(pool, state);
                    }
                    synchronized (state) {
                        state[0]++;
                        if (state[0] >= CONSECUTIVE_FAILURE_THRESHOLD) {
                            state[1] = System.currentTimeMillis() + POOL_BACKOFF_MS;
                        }
                    }
                }
                updateBuildStats(Result.TIMEOUT);
                if (cfg.getDestination() == null) {
                    _context.statManager().addRateData("tunnel.buildExploratoryExpire", 1);
                } else {
                    _context.statManager().addRateData("tunnel.buildClientExpire", 1);
                }
            }
        }

        _context.statManager().addRateData("tunnel.concurrentBuilds", concurrent);

        return allowed;
    }

    /**
     * Starts the tunnel building process in a loop and takes care of error handling.
     * Catches a specific fatal error related to Java versioning and ensures the
     * running state is properly reset on exit.
     */
    public void run() {
        _isRunning = true;
        try {
            run2();
        } catch (NoSuchMethodError nsme) {
            // Known fatal error scenario with Java 8 compiler and bootclasspath
            String s = "Fatal error:" +
                       "\nJava 8 compiler used with JRE version " + System.getProperty("java.version") +
                       " and no bootclasspath specified." +
                       "\nUpdate to Java 8 or contact packager." +
                       "\nStop I2P+ now, it will not build tunnels!";
            _log.log(Log.CRIT, s, nsme);
            System.out.println(s);
            throw nsme;
        } finally {
            _isRunning = false;
        }
    }

    /**
     * Main loop that manages tunnel pools and schedules tunnel builds depending on
     * system resource availability, load, and tunnel states. Handles expiration
     * and waiting for new build opportunities with appropriate backoff.
     */
    private void run2() {
        int maxConcurrentBuilds = getMaxConcurrentBuilds();
        List<TunnelPool> wanted = new ArrayList<>(maxConcurrentBuilds);
        List<TunnelPool> pools = new ArrayList<>(TUNNEL_POOLS);

        while (_isRunning && !_manager.isShutdown()) {
            try {
                _repoll = false;
                _manager.listPools(pools);

                // Proactive republish LeaseSets when all tunnels are healthy
                for (TunnelPool pool : pools) {
                    if (pool.isAlive()) {
                        try {
                            pool.proactiveRepublishIfHealthy();
                        } catch (RuntimeException e) {
                            _log.log(Log.WARN, "Error in proactiveRepublishIfHealthy for " + pool, e);
                        }
                    }
                }

                /* Periodic keepalive to maintain transport sessions with top-tier peers.
                 * Runs every ~30s. Always pre-connects to non-established eligible
                 * peers to warm connections before builds need them.
                 */
                if (++_keepAliveCounter % 30 == 0) {
                    TunnelPeerSelector.keepAlive(_context, true);
                }

                // Simplified paired replenishment algorithm
                // Target: max(4, wanted + 2) tunnels per direction with > 5 min expiry
                wanted.clear();
                calculatePairedBuilds(pools, wanted);

                // Determine how many tunnels are allowed to build concurrently
                int allowed = allowed(); // also expires timed out requests
                allowed = buildZeroHopTunnels(wanted, allowed); // zero-hop tunnels build inline
                // Cap per-iteration builds to prevent flooding the network
                if (allowed > 4) allowed = 4;

                TunnelManagerFacade mgr = _context.tunnelManager();
                boolean noInboundOrOutbound = (mgr == null) || (mgr.getFreeTunnelCount() <= 0) || (mgr.getOutboundTunnelCount() <= 0);

                if (noInboundOrOutbound) {
                    // Kickstart inbound/outbound tunnels if missing to avoid stall
                    if (mgr != null) {
                        if (mgr.getFreeTunnelCount() <= 0) {
                            mgr.selectInboundTunnel();
                        }
                        if (mgr.getOutboundTunnelCount() <= 0) {
                            mgr.selectOutboundTunnel();
                        }
                    }
                    synchronized (_currentlyBuilding) {
                        if (!_repoll) {
                            if (_log.shouldDebug()) {
                                _log.debug("No tunnel to build with (Allowed / Requested: " + allowed + " / " +
                                           wanted.size() + ") -> Waiting for a moment...");
                            }
                            try {
                                int noTunnelWait = 250 + _context.random().nextInt(250);
                                _currentlyBuilding.wait(noTunnelWait);
                            } catch (InterruptedException ie) {
                                // interrupted wait, proceed
                            }
                        }
                    }
                } else {
                    if (allowed > 0 && !wanted.isEmpty()) {
                        // Sort by build priority: collapsed pools first, then near-collapse,
                        // then by deficit (largest first).
                        // For paired destinations, prioritize the direction further behind its pair
                        wanted.sort((a, b) -> {
                            int aActive = a.getActiveTunnelCount();
                            int bActive = b.getActiveTunnelCount();
                            if (aActive == 0 && bActive > 0) return -1;
                            if (bActive == 0 && aActive > 0) return 1;
                            boolean aNear = aActive > 0 && aActive <= 2;
                            boolean bNear = bActive > 0 && bActive <= 2;
                            if (aNear && !bNear) return -1;
                            if (bNear && !aNear) return 1;
                            int aTarget = Math.max(2, a.getSettings().getTotalQuantity());
                            int bTarget = Math.max(2, b.getSettings().getTotalQuantity());
                            int aDeficit = aTarget - aActive;
                            int bDeficit = bTarget - bActive;
                            if (aDeficit != bDeficit) return Integer.compare(bDeficit, aDeficit);
                            // IB/OB balance: prioritize direction further behind its paired pool
                            TunnelPool aPaired = getPairedPool(a);
                            TunnelPool bPaired = getPairedPool(b);
                            if (aPaired != null && bPaired != null && aPaired == bPaired) {
                                int aDiff = aPaired.getActiveTunnelCount() - aActive;
                                int bDiff = bPaired.getActiveTunnelCount() - bActive;
                                if (aDiff != bDiff) return Integer.compare(bDiff, aDiff);
                            }
                            return 0;
                        });

                        for (int i = 0; i < allowed && !wanted.isEmpty(); i++) {
                            TunnelPool pool = wanted.remove(0);

                            long bef = System.currentTimeMillis();
                            PooledTunnelCreatorConfig cfg = pool.configureNewTunnel();
                            if (cfg != null) {
                                if (cfg.getLength() <= 1 && !pool.needFallback()) {
                                    if (_log.shouldDebug()) {
                                        _log.debug("We don't need more fallbacks for " + pool);
                                    }
                                    i--;
                                    pool.buildComplete(cfg, Result.OTHER_FAILURE);
                                    continue;
                                }
                                long pTime = System.currentTimeMillis() - bef;
                                _context.statManager().addRateData("tunnel.buildConfigTime", pTime);
                                if (_log.shouldDebug()) {
                                    _log.debug("Configuring new tunnel [" + i + "] for " + pool + " -> " + cfg);
                                }
                                buildTunnel(cfg);
                            } else {
                                i--;
                            }
                        }

                        /* Cancel excess in-progress builds to stay within budget.
                         * Only count building (in-progress) tunnels, not testing tunnels —
                         * they're different pipeline stages. Testing tunnels are built and
                         * being evaluated; building tunnels are still in construction.
                         */
                        for (TunnelPool pool : pools) {
                            if (!pool.isAlive()) {
                                continue;
                            }
                            int wantedCount = pool.getSettings().getTotalQuantity();
                            int buildingCount = pool.getInProgressCount();
                            int maxBuilding = Math.max(4, wantedCount * 2);
                            if (buildingCount > maxBuilding) {
                                for (PooledTunnelCreatorConfig cfg : pool.cancelExcessInProgress(maxBuilding)) {
                                    removeFromBuilding(cfg.getReplyMessageId());
                                }
                            }
                        }
                    }

                    synchronized (_currentlyBuilding) {
                        if (!_repoll) {
                            int delay = LOOP_TIME;
                            try {
                                _currentlyBuilding.wait(delay);
                            } catch (InterruptedException ie) {
                                // interrupted, loop continues
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                _log.log(Log.CRIT, "Catastrophic Tunnel Manager failure", e);
                try {
                    Thread.sleep(LOOP_TIME);
                } catch (InterruptedException ie) {
                    // ignore interrupt during sleep
                }
            }
            wanted.clear();
            pools.clear();
        }

        if (_log.shouldInfo()) {
            _log.info("Done building");
        }
    }

    /**
     *  Comparator for prioritizing tunnel pools during build selection.
     *  Priority order: Exploratory > Pools without tunnels > Everyone else.
     *
     *  Prioritize the pools for building
     *  #1: Exploratory
     *  #2: Pools without tunnels
     *  #3: Everybody else
     *
     *  This prevents a large number of client pools from starving the exploratory pool.
     *
     *  WARNING - this sort may be unstable, as a pool's tunnel count may change during the sort.
     */
    private static class TunnelPoolComparator implements Comparator<TunnelPool>, Serializable {

        private final boolean _preferEmpty;

        /** @param preferEmptyPools if true, prefer pools with no tunnels */
        public TunnelPoolComparator(boolean preferEmptyPools) {_preferEmpty = preferEmptyPools;}

        /**
         * Compare two tunnel pools for build priority.
         *
         * @param tpl left tunnel pool
         * @param tpr right tunnel pool
         * @return -1 if tpl has higher priority, 1 if tpr has higher priority, 0 if equal
         */
        public int compare(TunnelPool tpl, TunnelPool tpr) {
            if (tpl.getSettings().isExploratory() && !tpr.getSettings().isExploratory()) return -1;
            if (tpr.getSettings().isExploratory() && !tpl.getSettings().isExploratory()) return 1;
            if (_preferEmpty) {
                if (tpl.getTunnelCount() <= 0 && tpr.getTunnelCount() > 0) return -1;
                if (tpr.getTunnelCount() <= 0 && tpl.getTunnelCount() > 0) return 1;
            }
            return 0;
        }
    }

    /**
     * Iterate over the 0hop tunnels, running them all inline regardless of how many are allowed
     *
     * @return number of tunnels allowed after processing these zero hop tunnels (almost always the same as before)
     */
    private int buildZeroHopTunnels(List<TunnelPool> wanted, int allowed) {
        for (Iterator<TunnelPool> iter = wanted.iterator(); iter.hasNext(); ) {
            TunnelPool pool = iter.next();
            if (pool.getSettings().getLength() == 0) {
                PooledTunnelCreatorConfig cfg = pool.configureNewTunnel();
                if (cfg != null) {
                    if (_log.shouldDebug()) {
                        _log.debug("Configuring short tunnel for " + pool + " -> " + cfg);
                    }
                    buildTunnel(cfg);
                    if (cfg.getLength() > 1) {allowed--;} // oops... shouldn't have done that, but hey, it's not that bad...
                    iter.remove();
                } else {
                    if (_log.shouldDebug()) {_log.debug("Configured a NULL tunnel!");}
                }
            }
        }
        return allowed;
    }

    /**
     * Check if the executor is currently running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {return _isRunning;}

    /**
     *  Check if a pool is in backoff due to consecutive build failures.
     *  Uses a short (8s) backoff to prevent build storms while avoiding
     *  the starvation caused by the original 30s backoff.
     *  Failure tracking is retained for stats only.
     */
    boolean isPoolInBackoff(TunnelPool pool) {
        long[] state = _poolFailureState.get(pool);
        if (state == null) return false;
        long backoffUntil = state[1];
        return backoffUntil > 0 && _context.clock().now() < backoffUntil;
    }

    /**
     *  Get the paired pool (opposite direction) for IB/OB balance comparison.
     *  Handles both client pools (by destination) and exploratory pools (by pool reference).
     */
    private static TunnelPool getPairedPool(TunnelPool pool) {
        if (pool == null) return null;
        Hash dest = pool.getSettings().getDestination();
        if (dest != null) {
            TunnelPoolManager mgr = pool.getTunnelPoolManager();
            return pool.getSettings().isInbound() ? mgr.getOutboundPool(dest) : mgr.getInboundPool(dest);
        }
        // Null destination → exploratory pool, use the paired field directly
        return pool.getPairedPool();
    }

    /**
     * Build a tunnel with the given configuration.
     *
     * @param cfg the tunnel configuration to build
     */
    void buildTunnel(PooledTunnelCreatorConfig cfg) {
        long beforeBuild = System.currentTimeMillis();
        if (cfg.getLength() > 1) {
            do {cfg.setReplyMessageId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));} // should we allow an ID of 0?
            while (addToBuilding(cfg)); // if a dup, go araound again
        }
        boolean ok = BuildRequestor.request(_context, cfg, this, _adaptiveFirstHopTimeout);
        if (!ok) {return;}
        if (cfg.getLength() > 1) {
            long buildTime = System.currentTimeMillis() - beforeBuild;
            _context.statManager().addRateData("tunnel.buildRequestTime", buildTime);
        }
        long id = cfg.getReplyMessageId();
        if (id > 0) {
            synchronized (_recentBuildIds) {
                // every so often, shrink the list semi-efficiently
                if (_recentBuildIds.size() > 98) {
                    for (int i = 0; i < 32; i++) {_recentBuildIds.remove(0);}
                }
                _recentBuildIds.add(Long.valueOf(id));
            }
        }
    }

    /**
     * Handle a completed tunnel build.
     *
     * @param cfg the tunnel configuration that completed
     * @param result the build result (success, failure, etc.)
     * @since 0.9.53 added result parameter
     */
    public void buildComplete(PooledTunnelCreatorConfig cfg, Result result) {
        buildComplete(cfg, result, null);
    }

    public void buildComplete(PooledTunnelCreatorConfig cfg, Result result, String detail) {
        if (_log.shouldInfo()) {
            if (result == Result.OTHER_FAILURE && detail != null) {
                _log.info("Build failed -> " + detail + " for " + cfg);
            } else {
                _log.info("Build complete (" + result + ") for " + cfg);
            }
        }
        cfg.getTunnelPool().buildComplete(cfg, result);
        if (cfg.getLength() > 1) {removeFromBuilding(cfg.getReplyMessageId());}
        long now = _context.clock().now();
        long buildTime = now - cfg.getConfig(0).getCreation();
        TunnelPool pool = cfg.getTunnelPool();

        /* Per-pool consecutive failure tracking for backoff.
         * On SUCCESS: reset the counter so future failures start fresh.
         * On failure: increment counter; if threshold exceeded, set backoff
         * timestamp so calculatePairedBuilds() skips this pool temporarily.
         * REJECT is excluded — a peer that responds "no" (overloaded, no
         * capacity) is fundamentally different from a peer that doesn't
         * respond at all (TIMEOUT).  Backoff doesn't fix capacity issues
         * and prevents builds for pools that could succeed with a different
         * peer selection.
         */
        if (result == Result.SUCCESS) {
            _poolFailureState.remove(pool);
        } else if (result != Result.DUP_ID && result != Result.REJECT) {
            long[] state = _poolFailureState.get(pool);
            if (state == null) {
                state = new long[]{0, 0};
                _poolFailureState.put(pool, state);
            }
            synchronized (state) {
                state[0]++;
                if (state[0] >= CONSECUTIVE_FAILURE_THRESHOLD) {
                    state[1] = System.currentTimeMillis() + POOL_BACKOFF_MS;
                    if (_log.shouldDebug()) {
                        _log.debug("Pool backoff engaged after " + (int) state[0] +
                                   " consecutive failures for " + pool);
                    }
                }
            }
        }

        /* Track first-hop success/failure.
         * OTHER_FAILURE with buildTime >= 1000 means the build message couldn't
         * be delivered to the first hop (TunnelBuildFirstHopFailJob fires after
         * ~10s).  Count these as first-hop failures so the adaptive first-hop
         * timeout and pool quantity reduction can respond.
         */
        boolean firstHopFailure = (result == Result.OTHER_FAILURE && buildTime >= 1000);
        if (firstHopFailure) {
            if (pool != null)
                pool.incrementBuildTimeout();
        }

        /* Exclude non-latency failures from adaptive timeout stats.
         * - Immediate OTHER_FAILURE (< 50ms): no-paired-tunnel or send errors
         * - First-hop failures (OTHER_FAILURE, buildTime >= 1000): unreachable peers
         *   via TunnelBuildFirstHopFailJob
         * - TIMEOUT results: the build waited the full adaptive timeout with no
         *   reply.  Including TIMEOUTs in the adaptive calculation ensures
         *   the timeout doesn't decrease below what the network can actually
         *   support.  Excluding them inflates the apparent success rate,
         *   driving the timeout DOWN and causing MORE timeouts (positive
         *   feedback loop).  The adaptive clamp (10-25s) prevents TIMEOUTs
         *   from inflating the timeout beyond reason.
         */
        if (result == Result.SUCCESS ||
            (buildTime >= 50 && !firstHopFailure)) {
            updateBuildStats(result);
        }
        StatManager smFH = _context.statManager();
        if (result == Result.SUCCESS) {
            _firstHopSuccessCount.incrementAndGet();
            smFH.addRateData("tunnel.firstHopSuccessRate", 1, 0);
        } else if (result == Result.TIMEOUT || result == Result.BAD_RESPONSE || firstHopFailure) {
            _firstHopFailureCount.incrementAndGet();
            smFH.addRateData("tunnel.firstHopFailureRate", 1, 0);
        }

        /* Only wake up the build thread if it took a reasonable amount of time -
         * this prevents high CPU usage when there is no network connection
         * (via BuildRequestor.TunnelBuildFirstHopFailJob)
         */
        if (buildTime > 250) {
            synchronized (_currentlyBuilding) {_currentlyBuilding.notifyAll();}
        } else if (cfg.getLength() > 1 && _log.shouldInfo() && buildTime < 50) {
            _log.info("Build completed fast (" + buildTime + "ms) -> " + cfg);
        }
        long expireBefore = now + 10*60*1000 - BuildRequestor.getRequestTimeout(_context);
        if (cfg.getExpiration() <= expireBefore && _log.shouldDebug()) {
            _log.debug("Build completed for expired tunnel -> " + cfg);
        }
        if (result == Result.SUCCESS) {
            _manager.buildComplete(cfg);
            ExpireJob.scheduleExpiration(_context, cfg);

            /* Mark participating peers as low-latency when build completes quickly.
             * Only write profile when the flag actually changes to avoid disk churn.
             */
            int peerTimeout = _context.getProperty("router.peerTestTimeout", 750);
            int lowLatencyThreshold = 3 * peerTimeout;
            boolean lowLat = buildTime < lowLatencyThreshold;
            Hash selfHash = _context.routerHash();
            for (int i = 0; i < cfg.getLength(); i++) {
                Hash peer = cfg.getPeer(i);
                if (peer != null && !peer.equals(selfHash)) {
                    net.i2p.router.peermanager.PeerProfile prof = _context.profileOrganizer().getProfile(peer);
                    if (prof != null && prof.isLowLatency() != lowLat) {
                        prof.setLowLatency(lowLat);
                        _context.profileOrganizer().writeProfile(prof);
                    }
                }
            }

            // Record successful tunnel participation for ghost peer detection
            if (_ghostPeerManager != null) {
                for (int i = 0; i < cfg.getLength(); i++) {
                    Hash peer = cfg.getPeer(i);
                    if (peer != null && !peer.equals(selfHash)) {
                        _ghostPeerManager.recordSuccess(peer);
                    }
                }
            }
        }
    }

    /**
     * Check if a tunnel build was recently attempted.
     *
     * @param replyId the reply message ID to check
     * @return true if the build was recently attempted, false otherwise
     */
    public boolean wasRecentlyBuilding(long replyId) {
        synchronized (_recentBuildIds) {return _recentBuildIds.contains(Long.valueOf(replyId));}
    }

    /**
     * Signal the executor to repoll for tunnel building opportunities.
     */
    public void repoll() {
        synchronized (_currentlyBuilding) {
            _repoll = true;
            _currentlyBuilding.notifyAll();
        }
    }

    /**
     * Log that a peer did not reply to a tunnel build request.
     *
     * @param tunnel the tunnel ID
     * @param peer the peer hash that didn't reply
     */
    private void didNotReply(long tunnel, Hash peer) {
        if (_log.shouldDebug()) {
            _log.debug("No reply from [" + peer.toBase64().substring(0,6) + "] to join [Tunnel " + tunnel + "]");
        }
    }

    /**
     *  Only do this for non-fallback tunnels.
     *  @return true if refused because of a duplicate key
     *  @since 0.7.12
     */
    private boolean addToBuilding(PooledTunnelCreatorConfig cfg) {
        return _currentlyBuildingMap.putIfAbsent(Long.valueOf(cfg.getReplyMessageId()), cfg) != null;
    }

    /**
     *  This returns the PTCC up to a minute after it 'expired', thus allowing us to
     *  still use a tunnel if it was accepted, and to update peer stats.
     *  This means that manager.buildComplete() could be called more than once, and
     *  a build can be failed or successful after it was timed out,
     *  which will affect the stats and profiles.
     *  But that's ok. A peer that rejects slowly gets penalized twice, for example.
     *
     *  @return ptcc or null
     *  @since 0.7.12
     */
    PooledTunnelCreatorConfig removeFromBuilding(long id) {
        Long key = Long.valueOf(id);
        PooledTunnelCreatorConfig rv = _currentlyBuildingMap.remove(key);
        if (rv != null) {return rv;}
        rv = _recentlyBuildingMap.remove(key);
        if (rv != null) {
            long rtt = _context.clock().now() - rv.getConfig(0).getCreation();
            if (rtt < 0) {rtt = 0;}
            _context.statManager().addRateData("tunnel.buildReplySlow", rtt);
            if (_log.shouldInfo()) {
                _log.info("Received late reply (RTT: " + rtt + "ms) for: " + rv);
            }
        }
        return rv;
    }

    /**
     * Graduated-urgency replenishment - replaces simple deficit-based approach.
     * Uses expiry-window urgency multipliers from mainline I2P:
     *   1x at 210-270s, 2x at 150-210s, 4x at 90-150s, 6x at <90s.
     * This ensures tunnels are rebuilt predictively before they expire,
     * rather than only reactively after failure.
     */
    private void calculatePairedBuilds(List<TunnelPool> pools, List<TunnelPool> wanted) {
        long now = _context.clock().now();

        /* Pre-collect per-direction targets for paired destinations.
         * Used for proportional build allocation so one direction of a pair
         * can't cannibalize the other's share of the build pool.
         */
        Map<Hash, int[]> pairTargets = new HashMap<>(pools.size());
        for (TunnelPool p : pools) {
            if (!p.isAlive()) continue;
            Hash dest = p.getSettings().getDestination();
            if (dest == null) continue;
            int qty = p.getSettings().getTotalQuantity();
            int[] dirs = pairTargets.get(dest);
            if (dirs == null) {
                dirs = new int[2]; // [inboundQty, outboundQty]
                pairTargets.put(dest, dirs);
            }
            if (p.getSettings().isInbound()) {
                if (dirs[0] == 0 || qty > dirs[0]) dirs[0] = qty;
            } else {
                if (dirs[1] == 0 || qty > dirs[1]) dirs[1] = qty;
            }
        }

        // Track per-direction builds requested in this iteration for capping
        Map<Hash, int[]> pairRequested = new HashMap<>(pools.size());

        // Sort pools by urgency: collapsed (0 usable) first, then near-collapse
        // (1-2 usable), then the rest.  Without this, a healthy pool early in the
        // list consumes build slots (or triggers the proportional cap) before a
        // collapsed pool later in the list gets any.
        List<TunnelPool> sorted = new ArrayList<>(pools);
        sorted.sort((a, b) -> {
            int aUsable = a.getUsableTunnelCount();
            int bUsable = b.getUsableTunnelCount();
            // Collapsed pools (0 usable) always first
            if (aUsable == 0 && bUsable > 0) return -1;
            if (bUsable == 0 && aUsable > 0) return 1;
            // Near-collapse (1-2 usable) next
            boolean aNear = aUsable <= 2;
            boolean bNear = bUsable <= 2;
            if (aNear && !bNear) return -1;
            if (bNear && !aNear) return 1;
            // Then by deficit (largest first)
            int aTarget = Math.max(2, a.getSettings().getTotalQuantity());
            int bTarget = Math.max(2, b.getSettings().getTotalQuantity());
            int aDeficit = aTarget - aUsable;
            int bDeficit = bTarget - bUsable;
            return Integer.compare(bDeficit, aDeficit);
        });

        for (TunnelPool pool : sorted) {
            if (!pool.isAlive()) {continue;}

            int wantedCount = pool.getSettings().getTotalQuantity();
            String nickname = pool.getSettings().getDestinationNickname();
            boolean isPing = nickname != null && nickname.startsWith("Ping");

            int target = isPing ? wantedCount : Math.max(getTunnelTargetMin(_context), wantedCount + getTunnelTargetBuffer(_context));

            List<TunnelInfo> tunnels = pool.listTunnels();
            boolean allowZeroHop = pool.getSettings().getAllowZeroHop();

            int expire30s = 0, expire90s = 0, expire150s = 0, expire210s = 0, expire270s = 0, expire330s = 0, expireLater = 0;
            int fallbackCount = 0;
            int goodExpire30s = 0, goodExpire90s = 0, goodExpire150s = 0, goodExpire210s = 0, goodExpire270s = 0, goodExpire330s = 0, goodExpireLater = 0;
            int goodCount = 0;
            long totalLatency = 0;

            for (TunnelInfo info : tunnels) {
                if (!allowZeroHop && info.getLength() <= 1) {
                    fallbackCount++;
                    continue;
                }

                /* Skip completely dead tunnels — they'll be removed by ExpireJob
                 * and must NOT fill the deficit or they'll block replacement builds.
                 */
                if (info.getTunnelFailed() || info.getConsecutiveFailures() > 3) {continue;}
                boolean isGood = info.getTestStatus() == net.i2p.router.TunnelTestStatus.GOOD &&
                                 info.getConsecutiveFailures() <= 1;
                if (isGood) {
                    goodCount++;
                    int lat = TunnelPool.getTunnelAvgLatency(info);
                    if (lat > 0) totalLatency += lat;
                }
                long timeToExpire = info.getExpiration() - now;
                if (timeToExpire <= 0) {
                    expire30s++;
                    if (isGood) goodExpire30s++;
                } else if (timeToExpire <= 30*1000) {
                    expire30s++;
                    if (isGood) goodExpire30s++;
                } else if (timeToExpire <= 90*1000) {
                    expire90s++;
                    if (isGood) goodExpire90s++;
                } else if (timeToExpire <= 150*1000) {
                    expire150s++;
                    if (isGood) goodExpire150s++;
                } else if (timeToExpire <= 210*1000) {
                    expire210s++;
                    if (isGood) goodExpire210s++;
                } else if (timeToExpire <= 270*1000) {
                    expire270s++;
                    if (isGood) goodExpire270s++;
                } else if (timeToExpire <= 330*1000) {
                    expire330s++;
                    if (isGood) goodExpire330s++;
                } else {
                    expireLater++;
                    if (isGood) goodExpireLater++;
                }
            }

            int inProgress = pool.getInProgressCount();
            int remainingWanted = target - expireLater;
            if (allowZeroHop) remainingWanted -= fallbackCount;

            /* Walk through urgency windows, counting what's covered by later-expiring tunnels.
             * This uses ALL tunnels (including FAILING) so retained tunnels fill the deficit
             * and prevent unnecessary builds.
             */
            for (int i = 0; i < expire330s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < expire270s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < expire210s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < expire150s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < expire90s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < expire30s && remainingWanted > 0; i++) remainingWanted--;

            int builds;
            /* Check if pool is critically low on GOOD tunnels before entering
             * build calculation. Critical pools bypass the GOOD_DEFICIT_THROTTLE_MS
             * and test queue cap so replacement builds don't lag behind expiry.
             */
            int activeCount = pool.getActiveTunnelCount();
            boolean isCritical = !isPing && (activeCount == 0 || (activeCount < target && activeCount <= 2));
            /* Don't overbuild when we already have untested tunnels queued for testing.
             * If enough pending tunnels are waiting for test results to cover the target,
             * let those complete before building more. Otherwise we pile up untested tunnels
             * faster than the test queue can process them (25/2, 40/2).
             */
            if (isCritical) {
                int needed = target - activeCount;
                /* When there are zero GOOD tunnels, always treat as critical
                 * regardless of testing count. Testing tunnels are UNTESTED
                 * and may never complete if the TestJob queue is saturated —
                 * waiting for them allows the pool to drain to zero.
                 */
                if (activeCount > 0 && pool.getTestingTunnelCount() >= needed) {
                    isCritical = false;
                }
            }
            if (remainingWanted > 0) {
                /* Deficit — build just enough to fill the gap, no multiplier needed
                 * since builds complete in ~1s and the 1s loop refills quickly
                 */
                builds = expire330s + expire270s + expire210s + expire150s + expire90s + expire30s + remainingWanted;
            } else {
                /* Sufficient count — proactively replace GOOD tunnels approaching expiry.
                 * Start at 330s (5.5 min) so replacements have time to build before originals
                 * expire at 600s (10 min). FAILING tunnels are NOT proactively replaced —
                 * they stay until near-expiry (handled by ensureSufficientTunnels) or
                 * natural expiry. This prevents build-spam when all tunnels are FAILING.
                 * BUT: if there aren't enough GOOD tunnels to meet the target, build replacements
                 * so the pool doesn't get stuck with 0 GOOD tunnels.
                 * Without this, when all tunnels are FAILING the pool has zero viable tunnels but
                 * doesn't trigger builds (numerical deficit is satisfied by FAILING tunnels).
                 */
                builds = goodExpire330s + goodExpire270s + goodExpire210s;
                int goodDeficit = target - goodExpireLater;
                if (goodDeficit > 0) {
                    /* Don't build when untested tunnels can cover the deficit.
                     * UNTESTED tunnels are recently built and awaiting testing —
                     * building more just piles up untested tunnels faster than
                     * the test queue can process them.
                     */
                    int untestedCount = 0;
                    for (TunnelInfo ti : tunnels) {
                        if (ti.getTestStatus() == net.i2p.router.TunnelTestStatus.UNTESTED) {
                            untestedCount++;
                        }
                    }
                    if (untestedCount < goodDeficit) {
                        if (isCritical) {
                            builds += goodDeficit;
                        } else {
                            long nowMs = System.currentTimeMillis();
                            Long lastRebuild = _lastRebuildTime.get(pool);
                            if (lastRebuild == null || nowMs - lastRebuild >= getGoodDeficitThrottle(_context)) {
                                builds += goodDeficit;
                                _lastRebuildTime.put(pool, nowMs);
                            }
                        }
                    }
                }
            }

            /* Proactive latency improvement: when GOOD tunnels have high average
             * latency, build replacements to get lower-latency candidates for
             * the next LeaseSet publication.  Only when the pool isn't critical
             * (has at least some GOOD tunnels) — capacity takes priority.
             */
            if (goodCount > 0 && !isCritical) {
                long avgLatency = totalLatency / goodCount;
                int latencyThreshold = _context.getProperty("router.latencyBuildThreshold", 1500);
                if (avgLatency > latencyThreshold) {
                    int excess = (int)((avgLatency - latencyThreshold) / 500);
                    int latencyBuilds = Math.min(excess, target);
                    if (latencyBuilds > 0) {
                        builds += latencyBuilds;
                    }
                }
            }

            /* Always subtract inProgress to prevent overbuilding.
             * Critical pools (0 active) get a minimum of 1 build via the
             * EMERGENCY path in ensureSufficientTunnels(), so they don't need
             * the bypass here.  The old bypass caused 300+ excess cancellations
             * per session: calculatePairedBuilds would fire builds ignoring
             * in-flight count, then cancelExcessInProgress would immediately
             * trim them, wasting build slots.
             */
            builds -= inProgress;
            if (builds <= 0) continue;

            /* Cap at 2x target to prevent overbuilding.
             * When test queue is saturated (>80% full), also cap builds per pool
             * so we don't pile up untested tunnels faster than they can be tested.
             * Each free test slot supports ~2 concurrent builds.
             * Critical pools (low GOOD count) bypass the test queue cap — they need
             * builds regardless. Emergency test priority in TestJob.shouldSchedule()
             * ensures new tunnels get tested ASAP.
             */
            int maxBuilds = Math.max(target * 2, 2);
            if (builds > maxBuilds) builds = maxBuilds;
            if (!isCritical) {
                int testJobs = TestJob.getCurrentTestJobCount();
                int maxTestJobs = TestJob.getMaxTestJobs();
                if (testJobs > maxTestJobs * 4 / 5) {
                    int free = Math.max(maxTestJobs - testJobs, 0);
                    int maxByTestCap = Math.max(free * 2, 1);
                    if (builds > maxByTestCap) builds = maxByTestCap;
                }
            }

            /* Proportional per-direction cap for paired pools.
             * When both directions of a pair are building, the pair's combined
             * per-iteration budget is maxBuilds (the per-pool cap). Each direction
             * gets its proportional share by target ratio, preventing one direction
             * from cannibalizing the build pool and starving the other.
             * Exception: collapsed pools (0 active) bypass the cap — a dead pool
             * needs all the builds it can get regardless of what the healthy
             * direction has.
             */
            Hash dest = pool.getSettings().getDestination();
            if (dest != null && activeCount > 0) {
                int[] pt = pairTargets.get(dest);
                if (pt != null && pt[0] > 0 && pt[1] > 0) {
                    int totalTarget = pt[0] + pt[1];
                    int myQty = pool.getSettings().isInbound() ? pt[0] : pt[1];
                    // Pair budget = max of both directions' per-pool maxBuilds
                    int pairBudget = Math.max(maxBuilds, Math.max(pt[0], pt[1]) * 2);
                    int dirCap = Math.max(1, pairBudget * myQty / totalTarget);
                    int[] pr = pairRequested.get(dest);
                    if (pr == null) {
                        pr = new int[2]; // [inboundUsed, outboundUsed]
                        pairRequested.put(dest, pr);
                    }
                    int dirUsed = pool.getSettings().isInbound() ? pr[0] : pr[1];
                    int remaining = dirCap - dirUsed;
                    if (builds > remaining) {
                        builds = Math.max(remaining, 0);
                    }
                    if (pool.getSettings().isInbound()) {
                        pr[0] += builds;
                    } else {
                        pr[1] += builds;
                    }
                }
            }

            for (int i = 0; i < builds; i++) {
                wanted.add(pool);
            }
        }
    }

    /**
     * Count tunnels with expiry > minExpiryMs from now
     */
    private int countWithExpiry(TunnelPool pool, long now, int minExpiryMs) {
        int count = 0;
        List<TunnelInfo> tunnels = pool.listTunnels();
        for (TunnelInfo info : tunnels) {
            if (info.getExpiration() - now > minExpiryMs) {
                count++;
            }
        }
        return count;
    }

}
