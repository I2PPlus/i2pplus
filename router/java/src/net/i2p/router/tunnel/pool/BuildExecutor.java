package net.i2p.router.tunnel.pool;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
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
    private static final int TUNNEL_LIFETIME_MS = 10*60*1000;
    private static final int TUNNEL_MIN_EXPIRY_MS = 5*60*1000;
    private static final int TUNNEL_TARGET_MIN = 2;
    private static final int TUNNEL_TARGET_BUFFER = 0;
    private final ArrayList<Long> _recentBuildIds = new ArrayList<Long>(256);
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
    private final ConcurrentHashMap<TunnelPool, Long> _lastRebuildTime = new ConcurrentHashMap<TunnelPool, Long>(64);
    private static final long GOOD_DEFICIT_THROTTLE_MS = 30000;
    private final AtomicInteger _buildTimeoutCount = new AtomicInteger();
    private final AtomicInteger _firstHopSuccessCount = new AtomicInteger();
    private final AtomicInteger _firstHopFailureCount = new AtomicInteger();
    private volatile long _adaptiveTimeout = BuildRequestor.REQUEST_TIMEOUT;
    private volatile long _adaptiveFirstHopTimeout = BuildRequestor.FIRST_HOP_TIMEOUT;
    /**
     * Get the maximum number of concurrent tunnel builds allowed.
     * Calculated based on CPU cores and configurable multiplier
     *
     * @return maximum concurrent builds allowed
     */
    private int getMaxConcurrentBuilds() {
        int multiplier = _context.getProperty("router.buildConcurrencyMultiplier", 4);
        int cores = SystemVersion.getCores();
        int result = Math.max(cores * multiplier, 16);
        int maxCap = _context.getProperty("router.maxConcurrentBuilds", Math.max(cores * 8, 64));
        if (result > maxCap) {
            result = maxCap;
        }
        if (_log.shouldDebug()) {
            _log.debug("Max concurrent tunnel builds: " + result +
                       " (cores=" + cores + " * multiplier=" + multiplier + ")");
        }
        return result;
    }
    private static final int LOOP_TIME = 1000; // calculate required tunnels to build every 1s
    private static final int TUNNEL_POOLS = SystemVersion.isSlow() ? 24 : 48;
    private static final long GRACE_PERIOD = 60*1000; // accept replies up to a minute after we gave up on them
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
        _currentlyBuilding = new Object();
        int maxConcurrentBuilds = getMaxConcurrentBuilds();
        _currentlyBuildingMap = new ConcurrentHashMap<Long, PooledTunnelCreatorConfig>(maxConcurrentBuilds);
        _recentlyBuildingMap = new ConcurrentHashMap<Long, PooledTunnelCreatorConfig>(4 * maxConcurrentBuilds);
        _context.statManager().createRateStat("tunnel.buildFailFirstHop", "OB tunnel build failure frequency (can't contact 1st hop)", "Tunnels", RATES);
        _context.statManager().createRateStat("tunnel.buildReplySlow", "Build reply late, but not too late", "Tunnels", RATES);
        _context.statManager().createRateStat("tunnel.concurrentBuildsLagged", "Concurrent build count before rejecting (job lag)", "Tunnels", RATES); // (period is lag)
        _context.statManager().createRateStat("tunnel.buildClientExpire", "No response to our build request", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.buildClientReject", "Response time for rejection (ms)", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.buildClientSuccess", "Response time for success (ms)", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.buildConfigTime", "Time to build a tunnel config (ms)", "Tunnels", RATES);
        _context.statManager().createRateStat("tunnel.buildExploratoryExpire", "No response to our build request", "Tunnels [Exploratory]", RATES);
        _context.statManager().createRateStat("tunnel.buildExploratoryReject", "Response time for rejection (ms)", "Tunnels [Exploratory]", RATES);
        _context.statManager().createRateStat("tunnel.buildExploratorySuccess", "Response time for success (ms)", "Tunnels [Exploratory]", RATES);
        _context.statManager().createRateStat("tunnel.buildRequestTime", "Time to build a tunnel request (ms)", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.concurrentBuilds", "How many builds are going at once", "Tunnels", RATES);
        _context.statManager().createRateStat("tunnel.buildSuccessRate", "Tunnel build success rate (0-100)", "Tunnels", RATES);

        StatManager statMgr = _context.statManager(); // Get stat manager, get recognized bandwidth tiers
        String bwTiers = RouterInfo.BW_CAPABILITY_CHARS; // For each bandwidth tier, create tunnel build agree/reject/expire stats
        for (int i = 0; i < bwTiers.length(); i++) {
            String bwTier = String.valueOf(bwTiers.charAt(i));
            statMgr.createRateStat("tunnel.tierAgree" + bwTier, "Agreed joins from bandwidth tier " + bwTier, "Tunnels [Participating]", RATES);
            statMgr.createRateStat("tunnel.tierReject" + bwTier, "Rejected joins from bandwidth tier " + bwTier, "Tunnels [Participating]", RATES);
            statMgr.createRateStat("tunnel.tierExpire" + bwTier, "Expired joins from bandwidth tier " + bwTier, "Tunnels [Participating]", RATES);
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
        if (result == Result.SUCCESS) {
            _buildSuccessCount.incrementAndGet();
        } else if (result == Result.TIMEOUT) {
            _buildTimeoutCount.incrementAndGet();
        } else {
            _buildFailureCount.incrementAndGet();
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
     * Calculate adaptive timeout based on build success/failure/timeout ratio.
     * Differentiates between rejection (peers respond fast with NACK) and
     * timeout (peers don't respond at all) to set the right timeout.
     */
    private void calculateAdaptiveTimeoutFromSuccess() {
        int successCount = _buildSuccessCount.get();
        int failureCount = _buildFailureCount.get();
        int timeoutCount = _buildTimeoutCount.get();
        int total = successCount + failureCount + timeoutCount;
        if (total < 10) { return; }

        double successRate = (double) successCount / total;
        double failureRate = (double) failureCount / total;
        double timeoutRate = (double) timeoutCount / total;

        // Base timeout from 15s
        long baseTimeout = BuildRequestor.REQUEST_TIMEOUT;

        // When timeout rate is high, increase timeout to give slow peers more time.
        // Timeouts mean peers are reachable but slow — more time helps.
        if (timeoutRate > 0.30) {
            // Increase 1s per 10% timeout above 30%
            // At 50% timeouts -> 17s; at 80% -> 20s
            long increment = (long) ((timeoutRate - 0.30) * 100 / 10);
            increment = Math.min(increment, 5L);
            baseTimeout += increment * 1000;
        }

        // When rejection rate is high but timeouts are low, decrease timeout to
        // cycle through peers faster (peers reject fast, more time won't help).
        if (failureRate > 0.50 && timeoutRate < 0.20) {
            long decrement = (long) ((failureRate - 0.50) * 100 / 10);
            decrement = Math.min(decrement, 3L);
            baseTimeout = Math.max(BuildRequestor.REQUEST_TIMEOUT - 5000, baseTimeout - decrement * 1000);
        }

        // When success rate is high, increase timeout to maximize build success
        if (successRate > 0.50) {
            long increment = (long) ((successRate - 0.50) * 100 / 5);
            baseTimeout += increment * 1000;
        }

        // Ensure minimum timeout is reasonable for multi-hop tunnels
        long minimumTimeout = Math.max(BuildRequestor.REQUEST_TIMEOUT - 3000, BuildRequestor.FIRST_HOP_TIMEOUT + 2000);
        if (baseTimeout < minimumTimeout) {
            baseTimeout = minimumTimeout;
        }

         // Cap at 30 seconds
         _adaptiveTimeout = Math.min(baseTimeout, 30 * 1000);

        // Also calculate adaptive first-hop timeout based on first-hop success rate
        int firstHopTotal = _firstHopSuccessCount.get() + _firstHopFailureCount.get();
        if (firstHopTotal >= 10) {
            double firstHopSuccessRate = (double) _firstHopSuccessCount.get() / firstHopTotal;
            double firstHopFailureRate = 1.0 - firstHopSuccessRate;

            // Base first-hop timeout from 8s, up to 15s max
            long baseFirstHop = BuildRequestor.FIRST_HOP_TIMEOUT;

            // When failure rate is high, decrease first-hop timeout to fail fast.
            if (firstHopFailureRate > 0.50) {
                // Decrease 1s per 10% failure above 50%, min 5s
                long decrement = (long) ((firstHopFailureRate - 0.50) * 100 / 10);
                decrement = Math.min(decrement, 3L);
                baseFirstHop = Math.max(5000, baseFirstHop - decrement * 1000);
            } else if (firstHopFailureRate > 0.20) {
                long increment = (long) ((firstHopFailureRate - 0.20) * 100 / 10);
                baseFirstHop += increment * 1000;
            }

            _adaptiveFirstHopTimeout = Math.min(baseFirstHop, 15 * 1000);

            if (_log.shouldDebug()) {
                _log.debug("Adaptive first-hop timeout: " + (_adaptiveFirstHopTimeout / 1000) + "s (success: " +
                           (int)(firstHopSuccessRate * 100) + "%, failures: " + _firstHopFailureCount.get() +
                           "/" + firstHopTotal + ")");
            }
        }

        if (_log.shouldDebug()) {
            _log.debug("Adaptive timeout: " + (_adaptiveTimeout / 1000) + "s (success: " +
                       (int)(successRate * 100) + "%, failures: " + _buildFailureCount.get() +
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
            baseTimeout += (length - 3) * 5*1000; // Add 5s per additional hop
        }

        // Adjust based on system load
        int cpuLoad = SystemVersion.getCPULoadAvg();
        if (cpuLoad > 90) {
            baseTimeout += 5*1000; // Add 5s under high CPU load
        } else if (cpuLoad > 80) {
            baseTimeout += 3*1000; // Add 3s under moderate CPU load
        }

        // Cap at 30 seconds max
        return Math.min(baseTimeout, 30*1000);
    }

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
        if (allowed < 4) {allowed = 4;}

        final RateStat rs = _context.statManager().getRate("tunnel.buildRequestTime");
        if (rs != null) {
            Rate r = rs.getRate(RateConstants.ONE_MINUTE);
            double avg = (r != null) ? r.getAverageValue() : rs.getLifetimeAverageValue();
            int throttleFactor = isSlow ? 150 : 250;
            if (avg > 100) { // If builds take more than 100ms, start throttling
                int maxConcurrentBuilds = getMaxConcurrentBuilds();
                int throttle = (int) (throttleFactor * maxConcurrentBuilds / avg);
                if (throttle < allowed) {
                    allowed = throttle;
                    if (!isSlow && avg < 100) {
                        allowed *= 2;
                    }
                    if (allowed > maxConcurrentBuilds && _log.shouldInfo()) {
                        _log.info("Throttling concurrent tunnel builds to " + allowed + " -> Average build time is " + ((int) avg) + "ms");
                    }
                }
            } else if (avg <= 0 && rs.getLifetimeAverageValue() > 0) {
                avg = rs.getLifetimeAverageValue();
            }
        }

        // Cap allowed to max concurrent builds
        int maxConcurrentBuilds = getMaxConcurrentBuilds();
        if (allowed > maxConcurrentBuilds) {
            allowed = maxConcurrentBuilds;
        }

        // Allow override through property
        allowed = _context.getProperty("router.tunnelConcurrentBuilds", allowed);

        // Constants for expiration calculations
        final long TEN_MINUTES_MS = 10 * 60 * 1000;
        final long expireRecentlyBefore = now + TEN_MINUTES_MS - BuildRequestor.REQUEST_TIMEOUT + GRACE_PERIOD;
        final long expireBefore = now + TEN_MINUTES_MS - BuildRequestor.REQUEST_TIMEOUT;

        // Expire really old build requests from recentlyBuilding map
        for (Iterator<PooledTunnelCreatorConfig> iter = _recentlyBuildingMap.values().iterator(); iter.hasNext(); ) {
            PooledTunnelCreatorConfig cfg = iter.next();
            if (cfg.getExpiration() <= expireRecentlyBefore) {
                iter.remove();
            }
        }

        List<PooledTunnelCreatorConfig> expired = null;

        // Expire old build requests from currentlyBuilding map, move them to recentlyBuilding
        // Enhanced with adaptive timeout handling
        for (Iterator<PooledTunnelCreatorConfig> iter = _currentlyBuildingMap.values().iterator(); iter.hasNext(); ) {
            PooledTunnelCreatorConfig cfg = iter.next();
            long adaptiveTimeout = calculateAdaptiveTimeout(cfg);
            long adjustedExpireBefore = now + TEN_MINUTES_MS - adaptiveTimeout;

            if (cfg.getExpiration() <= adjustedExpireBefore) {
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
                updateBuildStats(Result.TIMEOUT);
                if (cfg.getDestination() == null) {
                    _context.statManager().addRateData("tunnel.buildExploratoryExpire", 1);
                } else {
                    _context.statManager().addRateData("tunnel.buildClientExpire", 1);
                }
            }
        }

        _context.statManager().addRateData("tunnel.concurrentBuilds", concurrent);

        long lag = _context.jobQueue().getMaxLag();
        int cpuloadavg = SystemVersion.getCPULoadAvg();
        boolean highLoad = (lag > 1000 && cpuloadavg > 98);
        if (_context.router().getUptime() > 5 * 60 * 1000 && highLoad) {
            if (_log.shouldWarn()) {
                _log.warn("System is under load -> Slowing down new tunnel builds...");
            }
            _context.statManager().addRateData("tunnel.concurrentBuildsLagged", concurrent, lag);
            return 1;
        }

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

                // Simplified paired replenishment algorithm
                // Target: max(4, wanted + 2) tunnels per direction with > 5 min expiry
                wanted.clear();
                calculatePairedBuilds(pools, wanted);

                // Determine how many tunnels are allowed to build concurrently
                int allowed = allowed(); // also expires timed out requests
                allowed = buildZeroHopTunnels(wanted, allowed); // zero-hop tunnels build inline

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
                        // Shuffle for randomness
                        Collections.shuffle(wanted, _context.random());

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

                        // Cancel excess in-progress builds to stay within budget.
                        // Only count building (in-progress) tunnels, not testing tunnels —
                        // they're different pipeline stages. Testing tunnels are built and
                        // being evaluated; building tunnels are still in construction.
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

            // Always delay between iterations to prevent rapid spinning
            try {
                Thread.sleep(LOOP_TIME);
            } catch (InterruptedException ie) {
                // ignore
            }
        }

        if (_log.shouldInfo()) {
            _log.info("Done building");
        }
    }

    /**
     *  Prioritize the pools for building
     *  #1: Exploratory
     *  #2: Pools without tunnels
     *  #3: Everybody else
     *
     *  This prevents a large number of client pools from starving the exploratory pool.
     *
     *  WARNING - this sort may be unstable, as a pool's tunnel count may change
     *  during the sort. This will cause Java 7 sort to throw an IAE.
     */
    /**
     * Comparator for prioritizing tunnel pools during build selection.
     * Priority order: Exploratory > Pools without tunnels > Everyone else.
     *
     * WARNING - this sort may be unstable, as a pool's tunnel count may change
     * during the sort. This will cause Java 7 sort to throw an IAE.
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
     * iterate over the 0hop tunnels, running them all inline regardless of how many are allowed
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
        boolean ok = BuildRequestor.request(_context, cfg, this);
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
        if (_log.shouldInfo()) {_log.info("Build complete (" + result + ") for " + cfg);}
        cfg.getTunnelPool().buildComplete(cfg, result);
        if (cfg.getLength() > 1) {removeFromBuilding(cfg.getReplyMessageId());}
        long now = _context.clock().now();
        long buildTime = now + 10*60*1000 - cfg.getExpiration();
        // Exclude immediate OTHER_FAILURE from adaptive timeout stats.
        // These complete in under 50ms because no paired tunnel was available,
        // and don't reflect actual network conditions that more time would help.
        if (result == Result.SUCCESS || buildTime >= 50) {
            updateBuildStats(result);
        }

        // Track first-hop success/failure
        if (result == Result.SUCCESS) {
            _firstHopSuccessCount.incrementAndGet();
        } else if (result == Result.TIMEOUT || result == Result.BAD_RESPONSE) {
            _firstHopFailureCount.incrementAndGet();
        }
        // Only wake up the build thread if it took a reasonable amount of time -
        // this prevents high CPU usage when there is no network connection
        // (via BuildRequestor.TunnelBuildFirstHopFailJob)
        if (buildTime > 250) {
            synchronized (_currentlyBuilding) {_currentlyBuilding.notifyAll();}
        } else if (cfg.getLength() > 1 && _log.shouldInfo() && buildTime < 50) {
            _log.info("Build completed fast (" + buildTime + "ms) -> " + cfg);
        }
        long expireBefore = now + 10*60*1000 - BuildRequestor.REQUEST_TIMEOUT;
        if (cfg.getExpiration() <= expireBefore && _log.shouldDebug()) {
            _log.debug("Build completed for expired tunnel -> " + cfg);
        }
        if (result == Result.SUCCESS) {
            _manager.buildComplete(cfg);
            ExpireJob.scheduleExpiration(_context, cfg);
            // Mark participating peers as low-latency when build completes quickly.
            // Only write profile when the flag actually changes to avoid disk churn.
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
        //_log.error("Removing ID: " + id + "; size was: " + _currentlyBuildingMap.size());
        Long key = Long.valueOf(id);
        PooledTunnelCreatorConfig rv = _currentlyBuildingMap.remove(key);
        if (rv != null) {return rv;}
        rv = _recentlyBuildingMap.remove(key);
        if (rv != null) {
            long requestedOn = rv.getExpiration() - 10*60*1000;
            long rtt = _context.clock().now() - requestedOn;
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

        for (TunnelPool pool : pools) {
            if (!pool.isAlive()) continue;

            int wantedCount = pool.getSettings().getTotalQuantity();
            String nickname = pool.getSettings().getDestinationNickname();
            boolean isPing = nickname != null && nickname.startsWith("Ping");

            int target = isPing ? wantedCount : Math.max(TUNNEL_TARGET_MIN, wantedCount + TUNNEL_TARGET_BUFFER);

            List<TunnelInfo> tunnels = pool.listTunnels();
            boolean allowZeroHop = pool.getSettings().getAllowZeroHop();

            int expire30s = 0, expire90s = 0, expire150s = 0, expire210s = 0, expire270s = 0, expire330s = 0, expireLater = 0;
            int fallbackCount = 0;
            int goodExpire30s = 0, goodExpire90s = 0, goodExpire150s = 0, goodExpire210s = 0, goodExpire270s = 0, goodExpire330s = 0, goodExpireLater = 0;

            for (TunnelInfo info : tunnels) {
                if (!allowZeroHop && info.getLength() <= 1) {
                    fallbackCount++;
                    continue;
                }
                // Skip completely dead tunnels — they'll be removed by ExpireJob
                // and must NOT fill the deficit or they'll block replacement builds.
                if (info.getTunnelFailed()) {continue;}
                boolean isGood = info.getTestStatus() == net.i2p.router.TunnelTestStatus.GOOD &&
                                 info.getConsecutiveFailures() <= 1;
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

            // Walk through urgency windows, counting what's covered by later-expiring tunnels.
            // This uses ALL tunnels (including FAILING) so retained tunnels fill the deficit
            // and prevent unnecessary builds.
            for (int i = 0; i < expire330s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < expire270s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < expire210s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < expire150s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < expire90s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < expire30s && remainingWanted > 0; i++) remainingWanted--;

            int builds;
            // Check if pool is critically low on GOOD tunnels before entering
            // build calculation. Critical pools bypass the GOOD_DEFICIT_THROTTLE_MS
            // and test queue cap so replacement builds don't lag behind expiry.
            int activeCount = pool.getActiveTunnelCount();
            boolean isCritical = !isPing && (activeCount == 0 || (activeCount < target && activeCount <= 2));
            // Don't overbuild when we already have untested tunnels queued for testing.
            // If enough pending tunnels are waiting for test results to cover the target,
            // let those complete before building more. Otherwise we pile up untested tunnels
            // faster than the test queue can process them (25/2, 40/2).
            if (isCritical) {
                int needed = target - activeCount;
                if (pool.getTestingTunnelCount() >= needed) {
                    isCritical = false;
                }
            }
            if (remainingWanted > 0) {
                // Deficit — build just enough to fill the gap, no multiplier needed
                // since builds complete in ~1s and the 1s loop refills quickly
                builds = expire330s + expire270s + expire210s + expire150s + expire90s + expire30s + remainingWanted;
            } else {
                // Sufficient count — proactively replace GOOD tunnels approaching expiry.
                // Start at 330s (5.5 min) so replacements have time to build before
                // the originals expire at 600s (10 min). FAILING tunnels are NOT
                // proactively replaced — they stay until near-expiry (handled by
                // ensureSufficientTunnels) or natural expiry. This prevents build-spam
                // when all tunnels are FAILING.
                // BUT: if there aren't enough GOOD tunnels to meet the target, build
                // replacements so the pool doesn't get stuck with 0 GOOD tunnels.
                // Without this, when all tunnels are FAILING the pool has zero viable
                // tunnels but doesn't trigger builds (numerical deficit is satisfied
                // by FAILING tunnels).
                builds = goodExpire330s + goodExpire270s + goodExpire210s;
                int goodDeficit = target - goodExpireLater;
                if (goodDeficit > 0) {
                    if (isCritical) {
                        builds += goodDeficit;
                    } else {
                        long nowMs = System.currentTimeMillis();
                        Long lastRebuild = _lastRebuildTime.get(pool);
                        if (lastRebuild == null || nowMs - lastRebuild >= GOOD_DEFICIT_THROTTLE_MS) {
                            builds += goodDeficit;
                            _lastRebuildTime.put(pool, nowMs);
                        }
                    }
                }
            }

            builds -= inProgress;
            if (builds <= 0) continue;

            // Cap at 2x target to prevent overbuilding.
            // When test queue is saturated (>80% full), also cap builds per pool
            // so we don't pile up untested tunnels faster than they can be tested.
            // Each free test slot supports ~2 concurrent builds.
            // Critical pools (low GOOD count) bypass the test queue cap — they need
            // builds regardless. Emergency test priority in TestJob.shouldSchedule()
            // ensures new tunnels get tested ASAP.
            int maxBuilds = Math.max(target * 2, 4);
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
