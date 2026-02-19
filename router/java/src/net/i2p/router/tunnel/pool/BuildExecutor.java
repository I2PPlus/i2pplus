package net.i2p.router.tunnel.pool;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.stat.RateConstants;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.tunnel.HopConfig;
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
    private final ArrayList<Long> _recentBuildIds = new ArrayList<Long>(256);
    private final RouterContext _context;
    private final Log _log;
    private final TunnelPoolManager _manager;
    private final GhostPeerManager _ghostManager;
    private final Object _currentlyBuilding; // Notify lock
    private final ConcurrentHashMap<Long, PooledTunnelCreatorConfig> _currentlyBuildingMap; // indexed by ptcc.getReplyMessageId()
    private final ConcurrentHashMap<Long, PooledTunnelCreatorConfig> _recentlyBuildingMap; // indexed by ptcc.getReplyMessageId()
    private final ExpireLocalTunnelsJob _expireLocalTunnels;
    private volatile boolean _isRunning;
    private boolean _repoll;
    private volatile long _lastTimeoutExtendLog;
    /*
     * Get the maximum number of concurrent tunnel builds allowed.
     * Calculated based on CPU cores and configurable multiplier.
     *
     * @return maximum concurrent builds allowed
     */
    private int getMaxConcurrentBuilds() {
        int multiplier = _context.getProperty("router.buildConcurrencyMultiplier", 4);
        int cores = SystemVersion.getCores();
        int result = Math.max(cores * multiplier, 16);
        if (_log.shouldDebug()) {
            _log.debug("Max concurrent tunnel builds: " + result +
                       " (cores=" + cores + " * multiplier=" + multiplier + ")");
        }
        return result;
    }
    private static final int LOOP_TIME = 500;
    private static final int TUNNEL_POOLS = SystemVersion.isSlow() ? 24 : 48;
    private static final long GRACE_PERIOD = 60*1000; // accept replies up to a minute after we gave up on them
    private static final long[] RATES = { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR, RateConstants.ONE_DAY };
    /** @return true if full statistics are enabled */
    public boolean fullStats() {return _context.getBooleanProperty("stat.full");}

    /** Build result enumeration. @since 0.9.53 */
    enum Result { SUCCESS, REJECT, TIMEOUT, BAD_RESPONSE, DUP_ID, OTHER_FAILURE }

    /**
     * Create a new BuildExecutor.
     *
     * @param ctx the router context
     * @param mgr the tunnel pool manager
     * @param ghostManager the ghost peer manager for tracking unresponsive peers @since 0.9.68+
     */
    public BuildExecutor(RouterContext ctx, TunnelPoolManager mgr, GhostPeerManager ghostManager) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _manager = mgr;
        _ghostManager = ghostManager;
        _currentlyBuilding = new Object();
        int maxConcurrentBuilds = getMaxConcurrentBuilds();
        _currentlyBuildingMap = new ConcurrentHashMap<Long, PooledTunnelCreatorConfig>(maxConcurrentBuilds);
        _recentlyBuildingMap = new ConcurrentHashMap<Long, PooledTunnelCreatorConfig>(4 * maxConcurrentBuilds);
        _expireLocalTunnels = new ExpireLocalTunnelsJob(ctx);
        _context.statManager().createRateStat("tunnel.buildFailFirstHop", "OB tunnel build failure frequency (can't contact 1st hop)", "Tunnels", RATES);
        _context.statManager().createRateStat("tunnel.buildReplySlow", "Build reply late, but not too late", "Tunnels", RATES);
        _context.statManager().createRateStat("tunnel.concurrentBuildsLagged", "Concurrent build count before rejecting (job lag)", "Tunnels", RATES); // (period is lag)
        _context.statManager().createRequiredRateStat("tunnel.buildClientExpire", "No response to our build request", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildClientReject", "Response time for rejection (ms)", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildClientSuccess", "Response time for success (ms)", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildConfigTime", "Time to build a tunnel config (ms)", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildExploratoryExpire", "No response to our build request", "Tunnels [Exploratory]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildExploratoryReject", "Response time for rejection (ms)", "Tunnels [Exploratory]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildExploratorySuccess", "Response time for success (ms)", "Tunnels [Exploratory]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildDuplicate", "Duplicate tunnel builds (not a failure)", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildRequestTime", "Time to build a tunnel request (ms)", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.concurrentBuilds", "How many builds are going at once", "Tunnels", RATES);

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
     * Calculate adaptive timeout based on tunnel characteristics and network conditions
     *
     * @param cfg the tunnel configuration
     * @return adaptive timeout in milliseconds
     */
    private long calculateAdaptiveTimeout(PooledTunnelCreatorConfig cfg) {
        long baseTimeout = BuildRequestor.REQUEST_TIMEOUT;

        // Adjust timeout based on tunnel length
        int length = cfg.getLength();
        if (length > 3) {
            baseTimeout += (length - 3) * 2*1000; // Add 2s per additional hop (reduced from 5s)
        }

        // Adjust based on recent network performance
        RateStat buildTimeStat = _context.statManager().getRate("tunnel.buildRequestTime");
        if (buildTimeStat != null) {
            Rate r = buildTimeStat.getRate(RateConstants.TEN_MINUTES); // Last 10 minutes
            if (r != null) {
                double avgBuildTime = r.getAverageValue();
                if (avgBuildTime > 100) { // If builds are taking longer than 100ms
                    baseTimeout += (long)(avgBuildTime * 1.5); // 1.5x the average build time (reduced from 2x)
                }
            }
        }

        // Extend timeout during network attacks or startup when build success is under 40%
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        long uptime = _context.router().getUptime();
        boolean isUnderAttack = buildSuccess < 0.40;
        if (isUnderAttack) {
            baseTimeout *= 1.5;
            long now = _context.clock().now();
            if (_log.shouldInfo() && uptime > 60*1000 && now - _lastTimeoutExtendLog > 60*1000) {
                _lastTimeoutExtendLog = now;
                _log.info("Extending tunnel build timeout (" + (int)(buildSuccess * 100) + "% success) -> " +
                          (uptime < 10*60*1000 ? "router startup period" : "network may be under attack"));
            }
        }

        // Adjust based on system load
        int cpuLoad = SystemVersion.getCPULoadAvg();
        if (cpuLoad > 90) {
            baseTimeout += 15*1000; // Add 15s under high CPU load (reduced from 30s)
        } else if (cpuLoad > 80) {
            baseTimeout += 10*1000; // Add 10s under moderate CPU load (reduced from 15s)
        }

        // Cap the timeout to prevent excessive waits
        long maxTimeout = BuildRequestor.REQUEST_TIMEOUT * 2; // Double the base timeout (reduced from 3x)
        // During attacks with low success, allow higher max timeout
        if (isUnderAttack) {
            maxTimeout = BuildRequestor.REQUEST_TIMEOUT * 3; // Allow up to 3x timeout (reduced from 5x)
        }
        return Math.min(baseTimeout, maxTimeout);
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
        final int cores = SystemVersion.getCores();
        final long maxMemory = SystemVersion.getMaxMemory();
        final long now = _context.clock().now();
        final Hash selfHash = _context.routerHash();

        final int maxKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        int allowed = maxKBps / 2;

        final RateStat rs = _context.statManager().getRate("tunnel.buildRequestTime");
        if (rs != null) {
            Rate r = rs.getRate(RateConstants.ONE_MINUTE);
            double avg = (r != null) ? r.getAverageValue() : rs.getLifetimeAverageValue();
            int throttleFactor = isSlow ? 100 : 200;
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

        // Ensure allowed is not below core count
        if (allowed < cores) {
            allowed = cores;
        }

        // Double allowed if enough memory and not slow system
        if (maxMemory >= 1024L * 1024L * 1024L && !isSlow) {
            allowed *= 2;
        }

        // Cap allowed to max concurrent builds
        int maxConcurrentBuilds = getMaxConcurrentBuilds();
        if (allowed > maxConcurrentBuilds) {
            allowed = maxConcurrentBuilds;
        }

        // Allow override through property
        allowed = _context.getProperty("router.tunnelConcurrentBuilds", allowed);

        // Cleanup recentlyBuilding map - remove configs whose tunnel lifetime has expired
        int recentlyCleaned = 0;
        for (Iterator<Long> iter = _recentlyBuildingMap.keySet().iterator(); iter.hasNext(); ) {
            Long key = iter.next();
            PooledTunnelCreatorConfig cfg = _recentlyBuildingMap.get(key);
            if (cfg == null) {
                iter.remove();
                recentlyCleaned++;
                continue;
            }
            // Only remove if tunnel lifetime has actually expired
            if (cfg.getExpiration() <= now) {
                iter.remove();
                recentlyCleaned++;
                cleanupConfig(cfg);
            }
        }
        if (recentlyCleaned > 0 && _log.shouldDebug()) {
            _log.debug("Cleaned " + recentlyCleaned + " expired entries from _recentlyBuildingMap, remaining: " + _recentlyBuildingMap.size());
        }

        List<PooledTunnelCreatorConfig> expired = null;

        // Expire builds that have been waiting too long for a reply
        // Use age-based detection: if build was started > timeout ago, it's expired
        for (Iterator<Long> iter = _currentlyBuildingMap.keySet().iterator(); iter.hasNext(); ) {
            Long key = iter.next();
            PooledTunnelCreatorConfig cfg = _currentlyBuildingMap.get(key);
            if (cfg == null) {
                iter.remove();
                continue;
            }
            long adaptiveTimeout = calculateAdaptiveTimeout(cfg);
            long buildAge = now - cfg.getCreationTime();
            
            // Expire if build has been waiting longer than the adaptive timeout
            // or if tunnel lifetime has expired (shouldn't happen normally)
            boolean isBuildTimeout = buildAge > adaptiveTimeout;
            boolean isTunnelExpired = cfg.getExpiration() <= now;

            if (isBuildTimeout || isTunnelExpired) {
                PooledTunnelCreatorConfig existingCfg = _recentlyBuildingMap.putIfAbsent(key, cfg);
                iter.remove();
                if (existingCfg == null) {
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
                            _log.info("Timeout (" + (BuildRequestor.REQUEST_TIMEOUT / 1000) + "s) waiting for tunnel build reply -> " + cfg);
                        }

                        final int length = cfg.getLength();
                        for (int iPeer = 0; iPeer < length; iPeer++) {
                            Hash peer = cfg.getPeer(iPeer);
                            if (peer == null || peer.equals(selfHash)) {
                                continue; // Skip null or self
                            }
                            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
                            String bwTier = "Unknown";
                            if (ri != null) {
                                bwTier = ri.getBandwidthTier();
                            }
                            _context.statManager().addRateData("tunnel.tierExpire" + bwTier, 1);
                            didNotReply(cfg.getReplyMessageId(), peer);
                            _context.profileManager().tunnelTimedOut(peer);
                            // Record ghost peer for consistent timeouts @since 0.9.68+
                            if (_ghostManager != null) {
                                _ghostManager.recordTimeout(peer);
                            }
                        }

                TunnelPool pool = cfg.getTunnelPool();
                if (pool != null) {
                    pool.buildComplete(cfg, Result.TIMEOUT);
                }
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
            return isSlow ? 1 : 3;
        }

        // Adjust allowed tunnels on overload conditions - increase builds under high load to handle attacks
        if (allowed < 3) {
            allowed += 3;
            allowed *= 4;
        }

        // Periodic cleanup of in-progress configs to prevent memory leaks
        // Reduced from 5s to 2s for more aggressive cleanup under memory pressure
        long cleanupInterval = 2 * 1000;
        if (now - _lastInProgressCleanup > cleanupInterval) {
            _lastInProgressCleanup = now;
            List<TunnelPool> pools = new ArrayList<>();
            _manager.listPools(pools);
            for (TunnelPool pool : pools) {
                pool.cleanupInProgress();
            }
        }

        // Leak detection - warn if maps are growing unexpectedly
        if (now - _lastLeakCheck > 5 * 60 * 1000) { // Check every 5 minutes
            _lastLeakCheck = now;
            int currentlySize = _currentlyBuildingMap.size();
            int recentlySize = _recentlyBuildingMap.size();
            int maxExpected = getMaxConcurrentBuilds() * 2;
            if (currentlySize > maxExpected && _log.shouldWarn()) {
                _log.warn("LEAK DETECTION: _currentlyBuildingMap size " + currentlySize + " exceeds expected max " + maxExpected);
            }
            if (recentlySize > maxExpected * 2 && _log.shouldWarn()) {
                _log.warn("LEAK DETECTION: _recentlyBuildingMap size " + recentlySize + " exceeds expected max " + (maxExpected * 2));
            }

            // Periodic pool health check and recovery
            // This detects and fixes missing pools for registered clients
            int recovered = _manager.checkAndRecoverPools();
            if (recovered > 0) {
                _log.warn("Pool recovery: Restored " + recovered + " missing pool(s)");
            }
        }

        return allowed;
    }

    private volatile long _lastInProgressCleanup = 0;
    private volatile long _lastLeakCheck = 0;

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
                _repoll = false; // reset repoll flag unless inbound requests arrive
                _manager.listPools(pools);

                // Collect pools that want tunnels built
                // First pass: identify pools with no tunnels
                boolean hasEmptyPool = false;
                for (TunnelPool pool : pools) {
                    if (pool.isAlive() && pool.getTunnelCount() <= 0) {
                        hasEmptyPool = true;
                        break;
                    }
                }

                for (int i = 0, size = pools.size(); i < size; i++) {
                    TunnelPool pool = pools.get(i);
                    if (!pool.isAlive()) {
                        continue;
                    }
                    int howMany = pool.countHowManyToBuild();
                    if (howMany <= 0) {
                        continue;
                    }
                    // If there are empty pools, prioritize getting them to minimum capacity
                    // Exploratory pools need just 1 each (IB + OB), client pools need 2-3 for faster startup
                    if (hasEmptyPool && pool.getTunnelCount() <= 0) {
                        if (pool.getSettings().isExploratory()) {
                            // Exploratory: just 1 tunnel to get started
                            wanted.add(pool);
                        } else {
                            // Client pools: request minimum 2-3 tunnels for faster startup
                            // This ensures client connections work sooner
                            int emptyPoolBuilds = Math.min(howMany, 3);
                            for (int j = 0; j < emptyPoolBuilds; j++) {
                                wanted.add(pool);
                            }
                        }
                    } else {
                        // Add full request
                        for (int j = 0; j < howMany; j++) {
                            wanted.add(pool);
                        }
                    }
                }

                // Get global limit and expire old requests
                int allowed = allowed();
                
                // Build tunnels based on per-pool limits from countHowManyToBuild()
                // Each pool's canStartBuild() enforces per-pool concurrent limits
                // Global 'always prefer empty pools' ensures ALL pools get at least 1 tunnel first
                if (!wanted.isEmpty() && allowed > 0) {
                    // Always prefer empty pools to ensure all pools get at least 1 tunnel
                    DataHelper.sort(wanted, new TunnelPoolComparator());

                    // Process up to 'allowed' pools - removes from wanted as we go
                    for (int i = 0; i < allowed && !wanted.isEmpty(); i++) {
                        TunnelPool pool = wanted.remove(0);
                        if (!pool.canStartBuild(pool.getSettings().isInbound())) {
                            if (_log.shouldDebug()) {
                                _log.debug("Skipping " + pool + " -> Max concurrent builds reached for direction");
                            }
                            i--; // Don't count against allowed since we skipped
                            continue;
                        }
                        
                        long bef = System.currentTimeMillis();
                        PooledTunnelCreatorConfig cfg = pool.configureNewTunnel();
                        if (cfg != null) {
                            long pTime = System.currentTimeMillis() - bef;
                            _context.statManager().addRateData("tunnel.buildConfigTime", pTime);
                            if (_log.shouldDebug()) {
                                _log.debug("Configuring new tunnel for " + pool + " -> " + cfg);
                            }
                            buildTunnel(cfg);
                        } else {
                            i--; // Failed to configure, don't count against allowed
                        }
                    }
                } else {
                    // No pools want builds, wait a bit before next iteration
                    long lag = _context.jobQueue().getMaxLag();
                    int cpuloadavg = SystemVersion.getCPULoadAvg();
                    boolean highload = lag > 1000 && cpuloadavg > 98;
                    synchronized (_currentlyBuilding) {
                        if (!_repoll) {
                            int delay = (SystemVersion.isSlow() || highload) ? LOOP_TIME : LOOP_TIME / 2;
                            try {
                                _currentlyBuilding.wait(delay);
                            } catch (InterruptedException ie) {
                                // interrupted, loop continues
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                _log.log(Log.CRIT, "Catastrophic Tunnel Manager failure!", e);
                try {
                    Thread.sleep(LOOP_TIME);
                } catch (InterruptedException ie) {
                    // ignore interrupt during sleep
                }
            }
            
            // Always sleep briefly to prevent CPU spinning
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                // ignore interrupt during sleep
            }
            
            wanted.clear();
            pools.clear();
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

        /**
         * Priority order:
         * 1. Exploratory pools with < 2 tunnels (need 1 IB + 1 OB minimum)
         * 2. Empty client pools (get at least 1 tunnel)
         * 3. Exploratory pools with >= 2 tunnels (have minimum)
         * 4. Client pools with tunnels (fill up to match exploratory)
         *
         * @param tpl left tunnel pool
         * @param tpr right tunnel pool
         * @return -1 if tpl has higher priority, 1 if tpr has higher priority, 0 if equal
         */
        public int compare(TunnelPool tpl, TunnelPool tpr) {
            boolean tplExploratory = tpl.getSettings().isExploratory();
            boolean tprExploratory = tpr.getSettings().isExploratory();
            int tplCount = tpl.getTunnelCount();
            int tprCount = tpr.getTunnelCount();

            // Priority 1: Exploratory with < 2 tunnels (need minimum 1 IB + 1 OB)
            if (tplExploratory && tplCount < 2 && (!tprExploratory || tprCount >= 2)) return -1;
            if (tprExploratory && tprCount < 2 && (!tplExploratory || tplCount >= 2)) return 1;

            // Priority 2: Empty pools (0 tunnels) - get at least 1 first
            if (tplCount <= 0 && tprCount > 0) return -1;
            if (tprCount <= 0 && tplCount > 0) return 1;

            // Priority 3: Exploratory that have minimum
            if (tplExploratory && tplCount >= 2 && !tprExploratory) return -1;
            if (tprExploratory && tprCount >= 2 && !tplExploratory) return 1;

            // Priority 4: Pools with fewer tunnels
            if (tplCount < tprCount) return -1;
            if (tprCount < tplCount) return 1;

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
                    // 0-hop tunnels build inline and don't count toward concurrent build cap
                    // configureNewTunnel() called buildStarted(), so balance it here
                    pool.buildFinished(pool.getSettings().isInbound());
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
        if (!ok) {
            // Build request could not be sent (no tunnel available for reply path)
            // This is NOT a failure - just means we need to retry later
            // Don't call buildComplete - the executor will naturally retry
            // Only remove from _inProgress if it was added
            if (cfg.getLength() > 1) {
                removeFromBuilding(cfg.getReplyMessageId());
            }
            if (_log.shouldDebug()) {
                _log.debug("Tunnel build deferred - no reply tunnel available for " + cfg);
            }
            return;
        }
        if (cfg.getLength() > 1) {
            long buildTime = System.currentTimeMillis() - beforeBuild;
            _context.statManager().addRateData("tunnel.buildRequestTime", buildTime);
        }
        long id = cfg.getReplyMessageId();
        if (id > 0) {
            synchronized (_recentBuildIds) {
                // Aggressive cleanup to prevent unbounded growth
                // Keep list bounded to max 256 entries
                while (_recentBuildIds.size() > 256) {
                    _recentBuildIds.remove(0);
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
        
        // Immediate cleanup for failed builds to prevent memory leak
        // Failed builds (TIMEOUT, REJECT, BAD_RESPONSE, OTHER_FAILURE) should not linger in maps
        if (result != Result.SUCCESS && cfg.getLength() > 1) {
            // Force immediate removal from both maps
            Long key = Long.valueOf(cfg.getReplyMessageId());
            _currentlyBuildingMap.remove(key);
            _recentlyBuildingMap.remove(key);
            if (_log.shouldDebug()) {
                _log.debug("Immediate cleanup for failed build: " + cfg.getReplyMessageId());
            }
        }
        
        // Only wake up the build thread if it took a reasonable amount of time -
        // this prevents high CPU usage when there is no network connection
        // (via BuildRequestor.TunnelBuildFirstHopFailJob)
        long now = _context.clock().now();
        long buildTime = now + 10*60*1000 - cfg.getExpiration();
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
            // Check if tunnel was rejected as duplicate by addTunnel()
            // If so, don't schedule expiration - clean up instead
            if (cfg.isDuplicate()) {
                _manager.removeFromExpiration(cfg);
                if (_log.shouldDebug()) {
                    _log.debug("Tunnel rejected as duplicate, skipping expiration schedule: " + cfg);
                }
            } else {
                _expireLocalTunnels.scheduleExpiration(cfg);
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
     * Remove a tunnel from the expiration queue to prevent memory leak.
     * @param cfg the tunnel config to remove
     */
    public void removeFromExpiration(PooledTunnelCreatorConfig cfg) {
        _expireLocalTunnels.removeTunnel(cfg);
    }

    /**
     * Remove a participating tunnel from the expiration queue to prevent memory leak.
     * This is called when IdleTunnelMonitor drops idle tunnels.
     * @param cfg the hop config to remove from expiration
     */
    public void removeFromExpiration(HopConfig cfg) {
        Long tunnelKey = ExpireLocalTunnelsJob.getTunnelKeyForHop(cfg);
        if (tunnelKey != null) {
            _expireLocalTunnels.removeTunnelKey(tunnelKey);
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
        if (rv != null) {
            synchronized (_recentBuildIds) {
                _recentBuildIds.remove(key);
            }
            return rv;
        }
        rv = _recentlyBuildingMap.remove(key);
        if (rv != null) {
            synchronized (_recentBuildIds) {
                _recentBuildIds.remove(key);
            }
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
     * Clean up a PooledTunnelCreatorConfig when it's removed from building maps.
     * This prevents memory leaks by ensuring the config is removed from:
     * - Tunnel pool in-progress list
     * - Expiration queue
     * - TestJob tracking
     */
    private void cleanupConfig(PooledTunnelCreatorConfig cfg) {
        if (cfg == null) return;
        try {
            TunnelPool pool = cfg.getTunnelPool();
            if (pool != null) {
                // Remove from in-progress list
                pool.removeFromInProgress(cfg);
            }
            // Remove from dispatcher as safety measure (may be no-op if not joined)
            _context.tunnelDispatcher().remove(cfg, "build executor cleanup");
            // Remove from expiration queue
            _expireLocalTunnels.removeTunnel(cfg);
            // Invalidate any test jobs
            Long tunnelKey = ExpireLocalTunnelsJob.getTunnelKey(cfg);
            if (tunnelKey != null) {
                TestJob.invalidate(tunnelKey);
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Error cleaning up config: " + e.getMessage());
            }
        }
    }

}
