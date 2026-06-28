package net.i2p.router.tunnel.pool;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.stat.RateConstants;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelTestStatus;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 *  A group of tunnels for the router or a particular client, in a single direction.
 *  Public only for TunnelRenderer in router console.
 */
public class TunnelPool {
    private final List<PooledTunnelCreatorConfig> _inProgress = new ArrayList<PooledTunnelCreatorConfig>();
    protected final RouterContext _context;
    protected final Log _log;
    private TunnelPoolSettings _settings;
    private final List<TunnelInfo> _tunnels;
    private final ReentrantLock _tunnelsLock = new ReentrantLock();
    private final TunnelPeerSelector _peerSelector;
    private final TunnelPoolManager _manager;
    private TunnelPool _pairedPool;  // Inbound or outbound pool for same destination
    protected volatile boolean _alive;
    private long _lifetimeProcessed;
    private int _lastSelectedIdx;
    private final int _expireSkew;
    private long _started;
    private long _lastRateUpdate;
    private long _lastLifetimeProcessed;
    private final String _rateName;
    private final long _firstInstalled;
    private final AtomicInteger _consecutiveBuildTimeouts = new AtomicInteger();
    private long _lastTimeoutWarningTime;
    private long _lastNoTunnelsWarningTime;
    private long _lastLastResortLogTime;
    /**
     *  Dynamic pool scaling: when a pool repeatedly collapses (EMERGENCY fires),
     *  increase the effective tunnel count. More parallel tunnels = more
     *  resilience against individual tunnel failures. LeaseSet publication
     *  picks the best tunnels, so excess is fine.
     */
    private volatile int _consecutiveEmergencies = 0;
    private static final int MAX_EMERGENCY_BOOST = 6;
    private volatile boolean _leaseSetRepublishPending;
    private static final int REMOVAL_QUEUE_CAPACITY = 2000;
    private final BlockingQueue<TunnelInfo> _removalQueue = new LinkedBlockingQueue<TunnelInfo>(REMOVAL_QUEUE_CAPACITY);
    private volatile boolean _removalJobScheduled = false;
    /**
     *  Reentrancy guard for ensureSufficientTunnels().
     *  pruneNonGoodTunnels() calls removeTunnel() per-tunnel, and removeTunnel()
     *  calls ensureSufficientTunnels() — creating recursive build storms that
     *  inflate _inProgress and trigger 800+ "Cancelling excess" per session.
     */
    private final java.util.concurrent.atomic.AtomicBoolean _ensuringTunnels = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final AtomicInteger GLOBAL_CONCURRENT_INBOUND_BUILDS = new AtomicInteger();
    private static final AtomicInteger GLOBAL_CONCURRENT_OUTBOUND_BUILDS = new AtomicInteger();

    /** Default early expiration time for pruned tunnels (30 seconds) */
    static final long DEFAULT_PRUNE_EARLY_EXPIRY = 120*1000;
    private static final String PROP_PRUNE_EARLY_EXPIRY = "router.pruneEarlyExpiryDelay";
    /** if less than one success in this many, reduce length (exploratory only) */
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_1 = 10;
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_2 = 12;
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_CLIENT_1 = 4;
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_CLIENT_2 = 5;

    /**
     * Get the early expiry time for pruned tunnels.
     * Reads from property router.tunnel.pruneEarlyExpiry, or uses default (30s).
     * @return early expiry time in milliseconds
     */
    long getPruneEarlyExpiry() {
        return _context.getProperty(PROP_PRUNE_EARLY_EXPIRY, DEFAULT_PRUNE_EARLY_EXPIRY);
    }

    /**
     * Get the tunnel lifetime from config or default (10 minutes).
     * Tunable via i2p.tunnel.lifetime (default: 600000).
     */
    static int getTunnelLifetime(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.lifetime", 10*60*1000);
    }

    /**
     * Get max concurrent builds per direction from config or default (6).
     * Tunable via i2p.tunnel.maxConcurrentBuildsPerDirection (default: 6).
     */
    static int getMaxConcurrentBuildsPerDirection(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.maxConcurrentBuildsPerDirection", 6);
    }

    /**
     * Get the startup suppression period from config or default (5 minutes).
     * Tunable via i2p.tunnel.startupTime (default: 300000).
     */
    static long getStartupTime(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.startupTime", 5*60*1000);
    }

    /**
     * Get the quantity override threshold from config or default (12).
     * If less than one success in this many, reduce quantity.
     * Tunable via i2p.tunnel.buildTriesQuantityOverride (default: 12).
     */
    static int getBuildTriesQuantityOverride(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.buildTriesQuantityOverride", 12);
    }

    /**
     * Get the refresh throttle interval from config or default (5 minutes).
     * Minimum interval between LeaseSet publishes.
     * Tunable via i2p.tunnel.refreshThrottle (default: 300000).
     */
    static long getRefreshThrottle(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.refreshThrottle", 5*60*1000);
    }

    /**
     * Get the LeaseSet build minimum interval from config or default (5 minutes).
     * Minimum interval between LeaseSet builds to prevent churn.
     * Tunable via i2p.tunnel.leasesetBuildMinInterval (default: 300000).
     */
    static long getLeaseSetBuildMinInterval(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.leasesetBuildMinInterval", 5*60*1000);
    }


    TunnelPool(RouterContext ctx, TunnelPoolManager mgr, TunnelPoolSettings settings, TunnelPeerSelector sel) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPool.class);
        _manager = mgr;
        _settings = settings;
        _tunnels = new ArrayList<TunnelInfo>(settings.getTotalQuantity());
        _peerSelector = sel;
        _expireSkew = _context.random().nextInt(90*1000);
        _started = System.currentTimeMillis();
        _lastRefreshTime = -getRefreshThrottle(ctx);
        _lastRateUpdate = _started;
        _firstInstalled = ctx.getProperty("router.firstInstalled", 0L) + 60*60*1000;
        String name;
        if (_settings.isExploratory()) {name = "Exploratory";}
        else {
            name = _settings.getDestinationNickname();
            // just strip HTML here rather than escape it everywhere in the console
            if (name != null) {name = DataHelper.stripHTML(name);}
            else {
                Hash d = _settings.getDestination();
                name = d != null ? "[" + d.toBase32().substring(0,8) + "]" : "[null]";
            }
        }
        _rateName = "[" + name + "] " + (_settings.isInbound() ? "InBps" : "OutBps");
        refreshSettings();
        ctx.statManager().createRequiredRateStat("tunnel.matchLease", "How often our Outbound Endpoint matches their Inbound Gateway", "Tunnels",
                                         new long[] {60*60*1000});
    }

    /**
     *  Warning, this may be called more than once
     *  (without an intervening shutdown()) if the
     *  tunnel is stopped and then restarted by the client manager with the same
     *  Destination (i.e. for servers or clients w/ persistent key,
     *  or restarting close-on-idle clients)
     */
    synchronized void startup() {
        synchronized (_inProgress) {_inProgress.clear();}
        if (_log.shouldDebug()) {
            _log.debug(toString() + ": Startup() called, was already alive? " + _alive, new Exception());
        }
        _alive = true;
        _started = System.currentTimeMillis();
        _lastRateUpdate = _started;
        _lastLifetimeProcessed = 0;
        _manager.tunnelFailed();
        if (_settings.isInbound() && !_settings.isExploratory()) {
            LeaseSet ls = null;
            _tunnelsLock.lock();
            try {ls = locked_buildNewLeaseSet();} finally {_tunnelsLock.unlock();}
            if (ls != null) {requestLeaseSet(ls, true);}
        }
        String name;
        if (_settings.isExploratory()) {name = "Exploratory tunnels";}
        else {
            name = _settings.getDestinationNickname();
            // just strip HTML here rather than escape it everywhere in the console
            if (name != null) {name = DataHelper.stripHTML(name);}
            else {
                Hash d = _settings.getDestination();
                name = d != null ? d.toBase32() : "[null]";
            }
        }
        if (_settings.isExploratory()) {
            _context.statManager().createRequiredRateStat(_rateName, (_settings.isInbound() ? "In " : "Out ") +
                                   "(B/s) for " + name, "Tunnels [Exploratory]",
                                   new long[] {60*1000L });
        } else {
            _context.statManager().createRequiredRateStat(_rateName, (_settings.isInbound() ? "In " : "Out ") +
                                   "(B/s) for " + name, "Tunnels [Services]",
                                   new long[] {60*1000L });
        }
    }

    /**
     *  Shut down the pool and clean up resources.
     */
    synchronized void shutdown() {
        if (_log.shouldInfo()) {_log.info(toString() + ": Shutdown called");}
        _alive = false;
        _context.statManager().removeRateStat(_rateName);
        synchronized (_inProgress) {_inProgress.clear();}
        _consecutiveBuildTimeouts.set(0);
        _cachedLeaseSet = null;
        _lastLeaseSetBuildTime = 0;

        // Clean up tunnels list to prevent memory leak - configs hold
        // references to HopConfig, peers, crypto material, etc.
        _tunnelsLock.lock();
        try {
            for (TunnelInfo info : _tunnels) {
                if (info instanceof PooledTunnelCreatorConfig) {
                    PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
                    _context.tunnelDispatcher().remove(cfg);
                    ExpireJob.removeFromExpiration(cfg);
                }
            }
            _tunnels.clear();
        } finally {_tunnelsLock.unlock();}

        // Clean up removal queue
        TunnelInfo ti;
        while ((ti = _removalQueue.poll()) != null) {
            if (ti instanceof PooledTunnelCreatorConfig) {
                PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) ti;
                _context.tunnelDispatcher().remove(cfg);
                ExpireJob.removeFromExpiration(cfg);
            }
        }
    }

    /**
     *  RateStat name for the bandwidth graph
     *  @return non-null
     *  @since 0.9.35
     */
    public String getRateName() {return _rateName;}

    /**
     *  Get the TunnelPoolManager that owns this pool.
     *  @return non-null
     *  @since 0.9.69+
     */
    public TunnelPoolManager getTunnelPoolManager() {return _manager;}

    /**
     *  Get the average bandwidth per tunnel in the pool.
     *  @return average bandwidth per configured tunnel in Bps
     *  @since 0.9.66
     */
    public int getAvgBWPerTunnel() {
        RateStat stat = _context.statManager().getRate(_rateName);
        if (stat == null)
            return 0;
        Rate rate = stat.getRate(RateConstants.FIVE_MINUTES);
        if (rate == null)
            return 0;
        int count = _settings.isInbound() ? _settings.getQuantity() : _settings.getTotalQuantity();
        if (count <= 0)
            return 0;
        return (int) (((float) rate.getAvgOrLifetimeAvg()) / count);
    }

    private void refreshSettings() {
        if (!_settings.isExploratory()) {return;} // don't override client specified settings
        Properties props = new Properties();
        props.putAll(_context.router().getConfigMap());
        if (_settings.isInbound()) {
            _settings.readFromProperties(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY, props);
        } else {
            _settings.readFromProperties(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY, props);
        }
    }

    private long getLifetime() {return System.currentTimeMillis() - _started;}

    /**
     * Pull a random tunnel out of the pool.  If there are none available but
     * the pool is configured to allow 0hop tunnels, this builds a fake one
     * and returns it.
     *
     * @return null on failure, but it should always build and return a fallback
     */
    TunnelInfo selectTunnel() {return selectTunnel(true);}

    private TunnelInfo selectTunnel(boolean allowRecurseOnFail) {
        boolean avoidZeroHop = !_settings.getAllowZeroHop();

        long now = _context.clock().now();
        long uptime = _context.router().getUptime();
        boolean shouldWarn = false;
        TunnelInfo lastResortTunnel = null;
        _tunnelsLock.lock();
        try {
            if (_tunnels.isEmpty()) {
               shouldWarn = _log.shouldWarn() && uptime > getStartupTime(_context) && shouldLogNoTunnelsWarning();
            } else {
                TunnelInfo backloggedTunnel = null;
                // Random start index for statistical load balancing
                int startIdx = _tunnels.size() > 0 ? _context.random().nextInt(_tunnels.size()) : 0;
                if (avoidZeroHop) {
                    for (int i = 0; i < _tunnels.size(); i++) {
                        int idx = (startIdx + i) % _tunnels.size();
                        TunnelInfo info = _tunnels.get(idx);
                        // Skip last resort tunnels on first pass - only use if no other option
                        if (info instanceof PooledTunnelCreatorConfig &&
                            ((PooledTunnelCreatorConfig)info).isLastResort()) {
                            lastResortTunnel = info;
                            continue;
                        }
                        // Skip tunnels that have failed completely
                        if (info.getTunnelFailed()) {continue;}
                        // Only skip tunnels that have exceeded max allowed failures.
                        // Test failures are often reply-path problems, not tunnel
                        // quality issues.  Let data prove the tunnel works.
                        if (info.getConsecutiveFailures() > TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES) {continue;}
                        if (info.getLength() > 1 && info.getExpiration() > now) {
                            if (_settings.isInbound() || !_context.commSystem().isBacklogged(info.getPeer(1))) {
                                resetConsecutiveTimeoutsOnSuccess();
                                if (info instanceof PooledTunnelCreatorConfig) {
                                    ((PooledTunnelCreatorConfig) info).recordActivity();
                                }
                                return info;
                            } else {backloggedTunnel = info;}
                        }
                    }
                    if (backloggedTunnel != null) {
                        if (_log.shouldWarn()) {_log.warn(toString() + " -> All tunnels are backlogged");}
                        if (backloggedTunnel instanceof PooledTunnelCreatorConfig) {
                            ((PooledTunnelCreatorConfig) backloggedTunnel).recordActivity();
                        }
                        return backloggedTunnel;
                    }
                }
                for (int i = 0; i < _tunnels.size(); i++) {
                    int idx = (startIdx + i) % _tunnels.size();
                    TunnelInfo info = _tunnels.get(idx);
                    // Skip last resort tunnels on first pass
                    if (info instanceof PooledTunnelCreatorConfig &&
                        ((PooledTunnelCreatorConfig)info).isLastResort()) {
                        if (lastResortTunnel == null) lastResortTunnel = info;
                        continue;
                    }
                    // Skip completely failed tunnels
                    if (info.getTunnelFailed()) {continue;}
                    // Skip only after max failures exceeded
                    if (info.getConsecutiveFailures() > TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES) {continue;}
                    if (info.getExpiration() > now) {
                        if (_settings.isInbound() || info.getLength() <= 1 ||
                            !_context.commSystem().isBacklogged(info.getPeer(1))) {
                            resetConsecutiveTimeoutsOnSuccess();
                            if (info instanceof PooledTunnelCreatorConfig) {
                                ((PooledTunnelCreatorConfig) info).recordActivity();
                            }
                            return info;
                        } else {backloggedTunnel = info;}
                    }
                }
                // Fall back to last resort tunnel if nothing else available
                if (lastResortTunnel != null) {
                    if (_log.shouldWarn() && uptime > 120*1000 && shouldLogLastResortWarning()) {
                        _log.warn(toString() + " -> Using last resort tunnel as only option");
                    }
                    if (lastResortTunnel instanceof PooledTunnelCreatorConfig) {
                        ((PooledTunnelCreatorConfig) lastResortTunnel).recordActivity();
                    }
                    return lastResortTunnel;
                }
                if (backloggedTunnel != null) {
                    if (backloggedTunnel instanceof PooledTunnelCreatorConfig) {
                        ((PooledTunnelCreatorConfig) backloggedTunnel).recordActivity();
                    }
                    return backloggedTunnel;
                }
                // Accept any non-expired tunnel even with high
                // consecutive failures. Retained tunnels are kept deliberately
                // and are better than returning null (which causes "No tunnels
                // available" and lost build replies). This applies to ALL pools
                // — even exploratory, since a null return can disrupt exploration.
                for (int i = 0; i < _tunnels.size(); i++) {
                    TunnelInfo info = _tunnels.get(i);
                    if (info.getExpiration() > now) {
                        if (_log.shouldWarn()) {
                            _log.warn(toString() + " -> Using degraded tunnel (" +
                                      info.getConsecutiveFailures() + " failures) as last resort");
                        }
                        if (info instanceof PooledTunnelCreatorConfig) {
                            ((PooledTunnelCreatorConfig) info).recordActivity();
                        }
                        return info;
                    }
                }
            }
        } finally {_tunnelsLock.unlock();}

        if (shouldWarn) {
            String warning;
            if (!_settings.isExploratory()) {
                boolean destReachable = isDestinationReachable();
                if (!destReachable) {
                    warning = toString() + " -> Destination not reachable (no LeaseSet found)";
                } else {
                    warning = toString() + " -> No tunnels available";
                }
            } else {
                warning = toString() + " -> No tunnels available";
            }
            if (!warning.contains("Ping")) {
                _log.warn(warning);
            }
        }

        if (_alive && !avoidZeroHop) {buildFallback();}
        if (allowRecurseOnFail) {return selectTunnel(false);}
        else {return null;}
    }

    /**
     * Suppress last-resort tunnel warning spam with rate limiting.
     * @since 0.9.69+
     */
    private boolean shouldLogLastResortWarning() {
        long now = System.currentTimeMillis();
        if (now - _lastLastResortLogTime < 60*1000) {return false;}
        _lastLastResortLogTime = now;
        return true;
    }

    /**
     *  Return the tunnel from the pool that is XOR-closest to the target.
     *  By using this instead of the random selectTunnel(),
     *  we force some locality in OBEP-IBGW connections to minimize
     *  those connections network-wide.
     *
     *  Does not check for backlogged next peer.
     *  Does not return an expired tunnel.
     *
     *  @param closestTo the hash to find the closest tunnel to
     *  @return the tunnel closest to the target, or null on failure
     *  @since 0.8.10
     */
    TunnelInfo selectTunnel(Hash closestTo) {
        boolean avoidZeroHop = !_settings.getAllowZeroHop();
        TunnelInfo rv = null;
        long now = _context.clock().now();
        long uptime = _context.router().getUptime();
        boolean shouldWarn = false;
        _tunnelsLock.lock();
        try {
            if (!_tunnels.isEmpty()) {
                TunnelInfoComparator comparator = new TunnelInfoComparator(closestTo, avoidZeroHop);
                for (TunnelInfo info : _tunnels) {
                    // Skip completely failed tunnels
                    if (info.getTunnelFailed()) {continue;}
                    // Only skip tunnels that have exceeded max allowed failures.
                    // Test failures are often reply-path problems, not tunnel
                    // quality issues.  Let data prove the tunnel works.
                    if (info.getConsecutiveFailures() > TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES) {continue;}
                    if (info.getExpiration() > now) {
                        if (rv == null || comparator.compare(info, rv) < 0) {
                            rv = info;
                        }
                    }
                }
                // For non-exploratory pools: if nothing found, use any non-expired tunnel
                // even with high consecutive failures.
                if (rv == null && !_settings.isExploratory()) {
                    for (TunnelInfo info : _tunnels) {
                        if (info.getExpiration() > now) {
                            rv = info;
                            break;
                        }
                    }
                }
            }
            if (rv == null && _log.shouldWarn() && uptime > getStartupTime(_context)) {
                shouldWarn = shouldLogNoTunnelsWarning();
            }
        } finally {_tunnelsLock.unlock();}
        if (rv != null) {
            _context.statManager().addRateData("tunnel.matchLease", closestTo.equals(rv.getFarEnd()) ? 1 : 0);
        } else if (shouldWarn) {
            String warning;
            if (!_settings.isExploratory()) {
                boolean destReachable = isDestinationReachable();
                if (!destReachable) {
                    warning = toString() + " -> Destination not reachable (no LeaseSet found)";
                } else {
                    warning = toString() + " -> No tunnels available";
                }
            } else {
                warning = toString() + " -> No tunnels available";
            }
            if (!warning.contains("Ping")) {
                _log.warn(warning);
            }
        }
        return rv;
    }

    /**
     *  Get a tunnel by its gateway tunnel ID.
     *  @param gatewayId for inbound, the GW rcv tunnel ID; for outbound, the GW send tunnel ID.
     *  @return the tunnel with the matching gateway ID, or null if not found
     */
    public TunnelInfo getTunnel(TunnelId gatewayId) {
        _tunnelsLock.lock();
        try {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = _tunnels.get(i);
                if (_settings.isInbound()) {
                    if (info.getReceiveTunnelId(0).equals(gatewayId)) {return info;}
                } else {
                    if (info.getSendTunnelId(0).equals(gatewayId)) {return info;}
                }
            }
        } finally {_tunnelsLock.unlock();}
        return null;
    }

    /**
     * Return a list of tunnels in the pool
     *
     * @return A copy of the list of TunnelInfo objects
     */
    public List<TunnelInfo> listTunnels() {
        _tunnelsLock.lock();
        try {return new ArrayList<TunnelInfo>(_tunnels);} finally {_tunnelsLock.unlock();}
    }

    /**
     *  Do we really need more fallbacks?
     *  Used to prevent a zillion of them.
     *  Does not check config, only call if config allows zero hop.
     *  @return true if more fallback tunnels are needed
     */
    boolean needFallback() {
        long exp = _context.clock().now() + 120*1000;
        _tunnelsLock.lock();
        try {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = _tunnels.get(i);
                if (info.getLength() <= 1 && info.getExpiration() > exp) {return false;}
            }
        } finally {_tunnelsLock.unlock();}
        return true;
    }

    /**
     *  Return settings.getTotalQuantity, unless this is an exploratory tunnel
     *  AND exploratory build success rate is less than 1/10, AND total settings
     *  is greater than 1. Otherwise subtract 1 to help prevent congestion collapse,
     *  and prevent really unintegrated routers from working too hard.
     *  We only do this for exploratory as different clients could have different
     *  length settings. Although I guess inbound and outbound exploratory
     *  could be different too, and inbound is harder...
     *
     *  As of 0.9.19, add more if exploratory and floodfill, as floodfills
     *  generate a lot of exploratory traffic.
     *  TODO high-bandwidth non-floodfills do also...
     *
     *  Also returns 1 if set for zero hop, client or exploratory.
     *
     *  Enhanced with exponential backoff for firewalled routers to prevent
     *  tunnel pool exhaustion after extended uptime.
     *
     *  @since 0.8.11
     */
    private int getAdjustedTotalQuantity() {
        if (_settings.getLength() == 0 && _settings.getLengthVariance() == 0) {return 1;}
        long uptime = _context.router().getUptime();
        int rv = _settings.getTotalQuantity();

        if (!_settings.isExploratory()) {
            if (rv <= 1) {return rv;}
            // throttle client tunnel builds in times of congestion with exponential backoff
            int fails = _consecutiveBuildTimeouts.get();
            // Don't reduce quantity if test queue is saturated — the bottleneck is
            // testing capacity, not build capacity. Reducing builds would starve
            // the pool of new tunnels that could be tested when capacity frees up.
            boolean testQueueSaturated = net.i2p.router.tunnel.pool.TestJob.getCurrentTestJobCount() >=
                                         net.i2p.router.tunnel.pool.TestJob.getMaxTestJobs();
            // Don't reduce quantity when pool has 0 GOOD tunnels — reducing the
            // target in this state makes recovery impossible and guarantees
            // pool collapse.  The backoff is meant to avoid wasting build slots
            // during congestion, but with 0 GOOD tunnels every build is critical.
            boolean poolEmpty = getActiveTunnelCount() == 0;
            // At >= 30 consecutive timeouts, override the poolEmpty guard:
            // the destination is likely unreachable or all candidate peers dead.
            // Reduce quantity to 1 to stop wasting build slots.
            // The pool gets a single attempt per cycle — if it succeeds, normal
            // backoff resumes; if not, no harm in minimal retries.
            if (fails > 30 && !testQueueSaturated) {
                rv = Math.max(1, _settings.getTotalQuantity() / 4);
                if (rv < 1) {rv = 1;}
                if (_log.shouldWarn() && !shouldSuppressTimeoutWarning()) {
                    _log.warn("Extreme backoff: reducing " + this + " to " + rv +
                              " after " + fails + " consecutive timeouts (destination may be unreachable)");
                }
            } else if (fails > 12 && !testQueueSaturated && !poolEmpty) {
                // Linear backoff: reduce by 50% after 12 failures
                int reductionFactor = 2; // Max 2x reduction

                // Check if router is firewalled using status check
                boolean isFirewalled = _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.REJECT_UNSOLICITED ||
                                   _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_OK ||
                                   _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_UNKNOWN ||
                                   _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_OK_IPV6_FIREWALLED ||
                                   _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_UNKNOWN_IPV6_FIREWALLED ||
                                   _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_DISABLED_IPV6_FIREWALLED;

                int minTunnels = isFirewalled ? 3 : 2; // Keep minimum tunnels for redundancy

                rv = Math.max(minTunnels, _settings.getTotalQuantity() / reductionFactor);

                // Additional safety: never reduce below 2 for non-firewalled, 3 for firewalled
                if (isFirewalled && rv < 3) {rv = 3;}
                else if (rv < 2) {rv = 2;}

                if (fails >= 10 && _log.shouldWarn() && !shouldSuppressTimeoutWarning() && uptime > getStartupTime(_context)) {
                    _log.warn("Limiting to " + rv + " tunnels after " + fails +
                              " consecutive build timeouts on " + this);
                }
            } else if (fails > 12 && testQueueSaturated) {
                if (_log.shouldWarn() && !shouldSuppressTimeoutWarning() && uptime > getStartupTime(_context)) {
                    _log.warn("Test queue saturated (" + net.i2p.router.tunnel.pool.TestJob.getCurrentTestJobCount() +
                              "/" + net.i2p.router.tunnel.pool.TestJob.getMaxTestJobs() +
                              ") -> NOT reducing tunnel quantity on " + this + " (fails=" + fails + ")");
                }
            }
            // Ensure minimum of 2 for basic redundancy, but don't over-build
            // beyond the configured quantity during normal operation
            if (!(_settings.getLength() == 0 && _settings.getLengthVariance() == 0)) {
                if (fails <= 12 || testQueueSaturated) {
                    if (rv < 2) {
                        rv = 2;
                    }
                }
            }
            return rv;
        }
        // TODO high-bw non-ff also
        if ((_context.netDb().floodfillEnabled() && uptime > getStartupTime(_context) ||SystemVersion.getMaxMemory() >= 1024*1024*1024) && rv < 3) {
            rv = 3;
       } else if (_settings.isExploratory() && rv < 2) {
            rv = 2;
       }
       if (rv > 1) {
           RateStat e = _context.statManager().getRate("tunnel.buildExploratoryExpire");
           RateStat r = _context.statManager().getRate("tunnel.buildExploratoryReject");
           RateStat s = _context.statManager().getRate("tunnel.buildExploratorySuccess");
           if (e != null && r != null && s != null) {
               Rate er = e.getRate(RateConstants.TEN_MINUTES);
               Rate rr = r.getRate(RateConstants.TEN_MINUTES);
               Rate sr = s.getRate(RateConstants.TEN_MINUTES);
               if (er != null && rr != null && sr != null) {
                   RateAverages ra = RateAverages.getTemp();
                   long ec = er.computeAverages(ra, false).getTotalEventCount();
                   long rc = rr.computeAverages(ra, false).getTotalEventCount();
                   long sc = sr.computeAverages(ra, false).getTotalEventCount();
                   long tot = ec + rc + sc;
                   if (tot >= getBuildTriesQuantityOverride(_context)) {
                       if (1000 * sc / tot <= 1000 / getBuildTriesQuantityOverride(_context)) {rv--;}
                   }
                }
            }
        }
        // Ensure minimum of 2 for basic redundancy, but don't over-build
        // beyond what the configuration and existing logic already provide
        if (!(_settings.getLength() == 0 && _settings.getLengthVariance() == 0)) {
            if (rv < 2) {
                rv = 2;
            }
        }
        return rv;
    }

    /**
     *  Shorten the length when under extreme stress, else clear the override.
     *  We only do this for exploratory tunnels, since we have to build a fallback
     *  if we run out. It's much better to have a shorter tunnel than a fallback.
     *
     *  @since 0.8.11
     */
    private void setLengthOverride() {
        int len = _settings.getLength();
        if (len > 1) {
            int th1, th2, minLen;
            RateStat e, r, s;
            if (_settings.isExploratory()) {
                th1 = BUILD_TRIES_LENGTH_OVERRIDE_1;   // 10
                th2 = BUILD_TRIES_LENGTH_OVERRIDE_2;   // 12
                minLen = 1;
                e = _context.statManager().getRate("tunnel.buildExploratoryExpire");
                r = _context.statManager().getRate("tunnel.buildExploratoryReject");
                s = _context.statManager().getRate("tunnel.buildExploratorySuccess");
            } else {
                th1 = BUILD_TRIES_LENGTH_OVERRIDE_CLIENT_1;  // 4
                th2 = BUILD_TRIES_LENGTH_OVERRIDE_CLIENT_2;  // 5
                minLen = 2;
                e = _context.statManager().getRate("tunnel.buildClientExpire");
                r = _context.statManager().getRate("tunnel.buildClientReject");
                s = _context.statManager().getRate("tunnel.buildClientSuccess");
            }
            if (e != null && r != null && s != null) {
                Rate er = e.getRate(RateConstants.TEN_MINUTES);
                Rate rr = r.getRate(RateConstants.TEN_MINUTES);
                Rate sr = s.getRate(RateConstants.TEN_MINUTES);
                if (er != null && rr != null && sr != null) {
                    RateAverages ra = RateAverages.getTemp();
                    long ec = er.computeAverages(ra, false).getTotalEventCount();
                    long rc = rr.computeAverages(ra, false).getTotalEventCount();
                    long sc = sr.computeAverages(ra, false).getTotalEventCount();
                    long tot = ec + rc + sc;
                    if (tot >= th1 ||
                        _firstInstalled > _context.clock().now()) {
                        long succ = tot > 0 ? 1000 * sc / tot : 0;
                        if (succ <=  1000 / th1) {
                            if (len > 2 && succ <= 1000 / th2) {
                                _settings.setLengthOverride(Math.max(minLen, len - 2));
                            } else {
                                _settings.setLengthOverride(Math.max(minLen, len - 1));
                            }
                            return;
                        }
                    }
                }
            }
        }
        _settings.setLengthOverride(-1); // disable
    }

    /** list of tunnelInfo instances of tunnels currently being built
     *  @return the list of tunnels currently being built
     */
    public List<PooledTunnelCreatorConfig> listPending() {synchronized (_inProgress) {return new ArrayList<PooledTunnelCreatorConfig>(_inProgress);}}

    /** duplicate of size(), let's pick one
     *  @return the number of tunnels in the pool
     */
    int getTunnelCount() {return size();}

    /**
     *  Count tunnels that are usable for routing — not failed, not expired,
     *  not expiring within 5 minutes.  Used by the EMERGENCY balance check
     *  so zombie tunnels don't skew the comparison.
     *  @since 0.9.69+
     */
    int getUsableTunnelCount() {
        long now = _context.clock().now();
        long preBuildThreshold = now + 5 * 60 * 1000;
        int count = 0;
        _tunnelsLock.lock();
        try {
            for (TunnelInfo t : _tunnels) {
                if (t.getExpiration() <= now) continue;
                if (t.getTunnelFailed() ||
                    t.getTestStatus() == net.i2p.router.TunnelTestStatus.FAILING) continue;
                count++;
            }
        } finally {_tunnelsLock.unlock();}
        return count;
    }

    public TunnelPoolSettings getSettings() {return _settings;}

    void setSettings(TunnelPoolSettings settings) {
        if (settings != null && _settings != null) {
            if (!(settings.isExploratory() || _settings.isExploratory())) {
                settings.getAliases().addAll(_settings.getAliases());
                settings.setAliasOf(_settings.getAliasOf());
            }
        }
        _settings = settings;
        if (_settings != null) {
            if (_log.shouldInfo()) {
                _log.info(toString() + " -> Settings updated \n" + settings);
            }
            _manager.tunnelFailed(); // in case we need more
        }
    }

    /** Set the paired pool (inbound <-> outbound for same destination) */
    void setPairedPool(TunnelPool pool) {
        _pairedPool = pool;
    }

    /** Get the paired pool (inbound <-> outbound for same destination) */
    TunnelPool getPairedPool() {
        return _pairedPool;
    }

    /**
     *  Is this pool running AND either exploratory, or tracked by the client manager?
     *  A pool will be alive but not tracked after the client manager removes it
     *  but before all the tunnels have expired.
     *  @return true if the pool is alive and should be tracked
     */
    public boolean isAlive() {
        return _alive && (_settings.isExploratory() || _context.clientManager().isLocal(_settings.getDestination()));
    }

    /** duplicate of getTunnelCount(), let's pick one
     *  @return the number of tunnels in the pool
     */
    public int size() {
        _tunnelsLock.lock();
        try {return _tunnels.size();} finally {_tunnelsLock.unlock();}
    }

    /**
     *  @return the number of valid (non-failed, not expired) tunnels in the pool
     *  Includes untested and testing tunnels — suitable for exploratory pools
     *  and build management.
     *  @since 0.9.68+
     */
    public int getValidTunnelCount() {
        int count = 0;
        long now = _context.clock().now();
        _tunnelsLock.lock();
        try {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = _tunnels.get(i);
                if (info.getTunnelFailed() ||
                    info.getTestStatus() == net.i2p.router.TunnelTestStatus.FAILED) {
                    continue;
                }
                long timeLeft = info.getExpiration() - now;
                if (timeLeft <= 0) {
                    continue;
                }
                count++;
            }
        } finally {_tunnelsLock.unlock();}
        return count;
    }

    /**
     *  @return the number of GOOD (tested+passed, non-failed, not expired)
     *          tunnels in the pool.  Excludes untested, testing, failing, and
     *          failed tunnels.  Suitable for LeaseSet publication and UI
     *          "ready" indicators.
     *  @since 0.9.69+
     */
    public int getActiveTunnelCount() {
        int count = 0;
        long now = _context.clock().now();
        // Ping tunnels don't publish LeaseSets and aren't tested — always count as active
        String nickname = _settings.getDestinationNickname();
        boolean isPingPool = nickname != null && (nickname.equals("I2Ping") ||
                                                  (nickname.startsWith("Ping") && nickname.contains("[")));
        _tunnelsLock.lock();
        try {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = _tunnels.get(i);
                if (info.getTunnelFailed() ||
                    (!isPingPool && info.getTestStatus() != net.i2p.router.TunnelTestStatus.GOOD) ||
                    info.getConsecutiveFailures() > 1) {
                    continue;
                }
                long timeLeft = info.getExpiration() - now;
                if (timeLeft <= 0) {
                    continue;
                }
                count++;
            }
        } finally {_tunnelsLock.unlock();}
        return count;
    }

    /**
     *  @return the number of tunnels that have been built but not yet passed
     *          their first test.  Excludes previously-GOOD (FAILING), FAILED,
     *          and definitely-failed tunnels.  These are treated as "in progress"
     *          for replacement accounting and UI display.
     *  @since 0.9.69+
     */
    public int getTestingTunnelCount() {
        int count = 0;
        long now = _context.clock().now();
        String nickname = _settings.getDestinationNickname();
        boolean isPingPool = nickname != null && (nickname.equals("I2Ping") ||
                                                  (nickname.startsWith("Ping") && nickname.contains("[")));
        if (isPingPool) {return 0;}
        _tunnelsLock.lock();
        try {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = _tunnels.get(i);
                if (info.getTunnelFailed() || info.getConsecutiveFailures() > 1) {continue;}
                net.i2p.router.TunnelTestStatus ts = info.getTestStatus();
                // Exclude GOOD (already proven), FAILED (dead), FAILING (previously GOOD, still works),
                // and UNTESTED (haven't started testing yet — don't count toward maintenance deficit)
                if (ts == net.i2p.router.TunnelTestStatus.GOOD ||
                    ts == net.i2p.router.TunnelTestStatus.FAILED ||
                    ts == net.i2p.router.TunnelTestStatus.FAILING ||
                    ts == net.i2p.router.TunnelTestStatus.UNTESTED) {continue;}
                long timeLeft = info.getExpiration() - now;
                if (timeLeft <= 0) {continue;}
                count++;
            }
        } finally {_tunnelsLock.unlock();}
        return count;
    }

    /**
     *  @return the number of tunnels currently being built
     *  @since 0.9.67
     */
    public int getInProgressCount() {
        synchronized (_inProgress) {return _inProgress.size();}
    }

    /**
     *  Cancel excess in-progress tunnel builds to stay within budget.
     *  Cancels the newest builds first (less time invested).
     *  @param maxAllowed the maximum number of in-progress builds allowed
     *  @return list of cancelled tunnel configs (BuildExecutor should remove from _currentlyBuildingMap)
     *  @since 0.9.68
     */
    public List<PooledTunnelCreatorConfig> cancelExcessInProgress(int maxAllowed) {
        List<PooledTunnelCreatorConfig> cancelled = new ArrayList<PooledTunnelCreatorConfig>();
        synchronized (_inProgress) {
            while (_inProgress.size() > maxAllowed && !_inProgress.isEmpty()) {
                PooledTunnelCreatorConfig cfg = _inProgress.remove(_inProgress.size() - 1);
                if (_log.shouldWarn()) {
                    _log.warn("Cancelling excess tunnel build: " + cfg);
                }
                cancelled.add(cfg);
            }
        }
        return cancelled;
    }

    /**
     *  Prune excess tunnels from the pool to stay within budget.
     *  Uses ExpireJob.scheduleExpiration() for budget-based pruning instead of direct removal.
     *  @return number of tunnels marked for removal
     *  @since 0.9.69+
     */
    public int pruneExcessTunnels() {
        if (!_alive || _tunnels.isEmpty()) {
            return 0;
        }

        int poolSize = size();
        boolean needsReplacement = false;
        if (_settings.isInbound() && !_settings.isExploratory()) {
            Hash dest = _settings.getDestination();
            TunnelPool oppositePool = _manager.getOutboundPool(dest);
            if (oppositePool != null && oppositePool.size() <= 1) {
                needsReplacement = true;
            }
        }
        if (needsReplacement && poolSize > 1) {
            if (_log.shouldWarn())
                _log.warn(toString() + " -> Pre-building tunnel replacement before slow/failed cleanup");
            _manager.tunnelFailed();
        }

        int removed = 0;
        long now = _context.clock().now();
        List<TunnelInfo> toRemove = new ArrayList<>();

        _tunnelsLock.lock();
        try {
            poolSize = _tunnels.size();
            if (poolSize <= 1) {
                return 0;
            }

            if (_settings.isInbound() && !_settings.isExploratory()) {
                Hash dest = _settings.getDestination();
                TunnelPool oppositePool = _manager.getOutboundPool(dest);
                if (oppositePool != null) {
                    int oppositeUsable = oppositePool.getTunnelCount();
                    int oppositeMin = oppositePool.getSettings().getQuantity();
                    if (oppositeUsable < oppositeMin) {
                        // OB pool is below target — don't prune IB tunnels.
                        // Pruning IB tunnels when OB has 0 active causes a
                        // synchronized collapse: all IB tunnels built at boot
                        // expire together, all OB pools lose their paired IB
                        // tunnels simultaneously, and 5+ EMERGENCY triggers
                        // fire at once.  Let IB tunnels expire naturally
                        // (10 min) to stagger the removal.
                        if (_log.shouldDebug()) {
                            _log.debug(toString() + " -> Skipping cleanup - paired OB pool below target (" + oppositeUsable + "/" + oppositeMin + ")");
                        }
                        return 0;
                    }
                }
            }

            int target = _settings.getTotalQuantity();
            // Dynamic scaling: keep extra tunnels when pool keeps collapsing
            int effectiveTarget = Math.min(target + _consecutiveEmergencies * 2,
                                           target + MAX_EMERGENCY_BOOST * 2);
            int currentSize = _tunnels.size();
            boolean isServerPool = _settings.isInbound() && !_settings.isExploratory();

            // Unified pruning for all pool types: prune excess tunnels down to target.
            // For server pools, NEVER prune GOOD tunnels — they're published in the
            // LeaseSet and removing them breaks client connections.
            // Priority order: FAILED > FAILING/TOO_SLOW/OVER_BUDGET > UNTESTED > TESTING > GOOD
            // Within same status, prune soonest-expiring first.
            if (currentSize > target) {
                int toPrune = currentSize - target;
                int goodKept = 0;
                int goodTarget = _settings.getQuantity(); // how many GOOD we want to keep
                List<TunnelInfo> sortedTunnels = new ArrayList<>(_tunnels);
                sortedTunnels.sort(new Comparator<TunnelInfo>() {
                    public int compare(TunnelInfo a, TunnelInfo b) {
                        int pa = pruneRank(a.getTestStatus());
                        int pb = pruneRank(b.getTestStatus());
                        if (pa != pb) return pa - pb;
                        return Long.compare(a.getExpiration(), b.getExpiration());
                    }
                    private int pruneRank(TunnelTestStatus s) {
                        if (s == null) return 0;
                        switch (s) {
                            case FAILED: return 0;
                            case TOO_SLOW: return 1;
                            case OVER_BUDGET: return 2;
                            case FAILING: return 3;
                            case UNTESTED: return 99; // Don't prune untested — let them be tested first
                            case TESTING: return 5;
                            default: return 6; // GOOD last
                        }
                    }
                });
                for (TunnelInfo info : sortedTunnels) {
                    if (toPrune <= 0) break;
                    // For server pools: never prune GOOD or FAILING tunnels.
                    // GOOD tunnels are published in the LeaseSet and removing them
                    // breaks client connections.  FAILING tunnels were recently GOOD
                    // and the old LeaseSet (propagated to peers) still references
                    // them — pruning during the propagation window causes unreachable
                    // destinations.  Let them expire naturally (10 min).
                    if (isServerPool && (info.getTestStatus() == TunnelTestStatus.GOOD ||
                                         info.getTestStatus() == TunnelTestStatus.FAILING)) {continue;}
                    // For non-server pools: keep at least goodTarget GOOD tunnels
                    if (!isServerPool && info.getTestStatus() == TunnelTestStatus.GOOD) {
                        goodKept++;
                        if (goodKept < goodTarget) {continue;}
                    }
                    // Never prune a tunnel actively carrying traffic,
                    // except for exploratory pools (no LeaseSet, no client impact).
                    // Without this carve-out, pools running active traffic never get
                    // pruned because every tunnel is recently active (30s window),
                    // allowing unbounded accumulation.
                    if (!_settings.isExploratory() && info instanceof PooledTunnelCreatorConfig &&
                        ((PooledTunnelCreatorConfig) info).isRecentlyActive()) {continue;}
                    if (info.getExpiration() < now + getPruneEarlyExpiry()) {continue;}
                    if (!(info instanceof PooledTunnelCreatorConfig)) {continue;}
                    PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
                    TunnelId gwId = _settings.isInbound() ? cfg.getReceiveTunnelId(0) : cfg.getSendTunnelId(0);
                    if (gwId == null || gwId.getTunnelId() == 0) {continue;}
                    cfg.setExpiration(now + getPruneEarlyExpiry());
                    cfg.setTestOverBudget();
                    ExpireJob.scheduleExpiration(_context, cfg);
                    toRemove.add(info);
                    toPrune--;
                    removed++;
                    if (_log.shouldDebug()) {
                        _log.debug(toString() + " -> Scheduling early expiry for excess tunnel: " + gwId);
                    }
                }

                // If we still need to prune but only have GOOD tunnels left (non-server),
                // prune the soonest-expiring GOOD tunnels.
                if (toPrune > 0 && !isServerPool) {
                    List<TunnelInfo> goodTunnels = new ArrayList<>();
                    for (TunnelInfo info : _tunnels) {
                        if (!toRemove.contains(info) && info.getTestStatus() == TunnelTestStatus.GOOD &&
                            info instanceof PooledTunnelCreatorConfig) {
                            goodTunnels.add(info);
                        }
                    }
                    goodTunnels.sort(Comparator.comparingLong(TunnelInfo::getExpiration));
                    for (TunnelInfo info : goodTunnels) {
                        if (toPrune <= 0) break;
                        // Don't prune if tunnel is actively carrying traffic
                        if (((PooledTunnelCreatorConfig) info).isRecentlyActive()) {continue;}
                        PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
                        cfg.setExpiration(now + getPruneEarlyExpiry());
                        cfg.setTestOverBudget();
                        ExpireJob.scheduleExpiration(_context, cfg);
                        toRemove.add(info);
                        toPrune--;
                        removed++;
                    }
                }
            }

            // Schedule early expiry for completely failed tunnels (all pool types).
            // Caps removal per run and staggers expiry times to prevent
            // simultaneous removal of all failed tunnels in one ExpireJob batch.
            int poolSizeAtEnd = _tunnels.size();
            int minAfterFailed = Math.max(target, 2);
            int alreadyMarked = toRemove.size();
            int maxFailedRemove = Math.max(0, poolSizeAtEnd - minAfterFailed - alreadyMarked);
            int failedRemoved = 0;
            for (TunnelInfo info : _tunnels) {
                if (failedRemoved >= maxFailedRemove) break;
                if (info instanceof PooledTunnelCreatorConfig && !toRemove.contains(info)) {
                    PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
                    if (cfg.getTunnelFailed()) {
                        long stagger = failedRemoved * 15000L; // 15s between each
                        cfg.setTestTooSlow();
                        cfg.setExpiration(now + getPruneEarlyExpiry() + stagger);
                        ExpireJob.scheduleExpiration(_context, cfg);
                        toRemove.add(info);
                        removed++;
                        failedRemoved++;
                        if (_log.shouldDebug()) {
                            _log.debug(toString() + " -> Scheduling early expiry for failed tunnel: " +
                                       cfg.getReceiveTunnelId(0) + " (stagger +" + (stagger / 1000) + "s, " +
                                       (maxFailedRemove - failedRemoved) + " remaining)");
                        }
                    }
                }
            }
        } finally {_tunnelsLock.unlock();}

        // Notify manager to trigger tunnel rebuild for pruned tunnels.
        // ExpireJob will handle actual removal gracefully after the early expiry window.
        if (!toRemove.isEmpty()) {
            _manager.tunnelFailed();
            if (_log.shouldInfo()) {
                _log.info(toString() + " -> Scheduled early expiry for " + toRemove.size() + " excess tunnels");
            }
        }

        return toRemove.size();
    }

    /**
     *  Get an effective latency value for prune sorting.
     *  Higher = slower = more likely to be pruned.
     *  Uses average latency (3+ samples), then last latency,
     *  then MAX_VALUE (no data = prune first).
     *  @return latency in ms, or Integer.MAX_VALUE if unknown
     */
    private static int getEffectiveLatencyForPrune(TunnelInfo info) {
        if (info instanceof TunnelCreatorConfig) {
            TunnelCreatorConfig cfg = (TunnelCreatorConfig) info;
            if (cfg.hasEnoughLatencyTests()) {
                return cfg.getAverageLatency();
            }
            int last = cfg.getLastLatency();
            if (last >= 0) return last;
        }
        return Integer.MAX_VALUE;
    }

    /**
     *  Track recently-added tunnel IDs to prevent duplicates.
     *  Uses a simple sliding window based on expiration time.
     *  Window set to 10 minutes to handle slow tunnel builds.
     */
    private static final long RECENTLY_ADDED_WINDOW = 60 * 1000;
    /** Throttle refresh — publish at most once per throttle window.
     *  5 min minimum prevents storms; with occasional emergency publishes
     *  the actual interval averages ~10 min. */
    /** Initialize to allow first request immediately */
    private long _lastRefreshTime;
    /** Track last proactive LeaseSet publish time for rate limiting */
    private long _lastLeaseSetPublishTime;
    /** Minimum interval between LeaseSet builds (matches getRefreshThrottle(_context)).
     *  Prevents rapid LeaseSet object churn on every tunnel add/remove. */
    /** Cached LeaseSet returned during rate-limit window */
    private LeaseSet _cachedLeaseSet;
    /** Timestamp of the last successful LeaseSet build */
    private long _lastLeaseSetBuildTime;
    /** Track if a deferred refresh is already scheduled */
    private volatile boolean _pendingRefreshScheduled;
    private final Map<TunnelId, Long> _recentlyAddedTunnels = new ConcurrentHashMap<>();

    /**
     *  Add a tunnel to the pool.
     *  @param info the tunnel to add
     */
    protected void addTunnel(TunnelInfo info) {
        if (info == null) {return;}
        long now = _context.clock().now();

        // Get the gateway tunnel ID for deduplication
        TunnelId gatewayId = _settings.isInbound()
            ? info.getReceiveTunnelId(0)
            : info.getSendTunnelId(0);

        // Check for duplicates using recent additions tracking
        Long addedTime = _recentlyAddedTunnels.get(gatewayId);
        if (addedTime != null && now - addedTime < RECENTLY_ADDED_WINDOW) {
            if (_log.shouldWarn()) {
                _log.warn(toString() + " -> Rejecting duplicate tunnel addition: " + info.getReceiveTunnelId(0));
            }
            return;
        }

        // Cleanup old entries to prevent memory leak
        _recentlyAddedTunnels.entrySet().removeIf(entry -> now - entry.getValue() > RECENTLY_ADDED_WINDOW);

        if (_log.shouldDebug()) {_log.debug(toString() + " -> Adding tunnel " + info);}
        LeaseSet ls = null;
        _tunnelsLock.lock();
        try {
            // Defense in depth: check for duplicates by identity AND by gateway ID.
            // Identity check catches re-adds of the same object (e.g. from buildComplete
            // called twice). Gateway ID check catches different objects with the same
            // tunnel ID (shouldn't happen but prevents pool corruption).
            if (_tunnels.contains(info)) {
                if (_log.shouldWarn()) {
                    _log.warn(toString() + " -> Tunnel already in pool (identity), skipping add: " + info);
                }
                return;
            }
            for (TunnelInfo existing : _tunnels) {
                TunnelId existingId = _settings.isInbound()
                    ? existing.getReceiveTunnelId(0)
                    : existing.getSendTunnelId(0);
                if (existingId.equals(gatewayId)) {
                    if (_log.shouldWarn()) {
                        _log.warn(toString() + " -> Tunnel ID " + gatewayId + " already exists in pool, skipping add");
                    }
                    return;
                }
            }

            if (info.getExpiration() > now + 60*1000) {
                // Cap UNTESTED tunnels only — limit in-flight builds that
                // haven't been verified yet.  GOOD tunnels can accumulate
                // freely so the pool never starves while tests run.
                int target = _settings.getQuantity();
                int effectiveTarget = Math.min(target + _consecutiveEmergencies,
                                               target + MAX_EMERGENCY_BOOST);
                int untestedNow = 0;
                for (TunnelInfo t : _tunnels) {
                    if (t.getTestStatus() == net.i2p.router.TunnelTestStatus.UNTESTED) {
                        untestedNow++;
                    }
                }
                int maxUntested = Math.max(effectiveTarget * 2, 2);
                if (untestedNow >= maxUntested) {
                    if (_log.shouldWarn()) {
                        _log.warn(toString() + " -> Too many UNTESTED tunnels (" + untestedNow +
                                  " >= max " + maxUntested + ", target=" + effectiveTarget + ") \n* " + info);
                    }
                    return;
                }
                // For exploratory pools (no LeaseSet), cap total non-FAILED tunnels
                // to prevent unbounded accumulation.  Without this, a pool with 100%
                // build success can accumulate far more tunnels than needed since
                // pruneExcessTunnels() skips recently-active tunnels (30s window).
                // These pools don't need LeaseSet rotation overhead so any excess
                // beyond 3x target is wasted.
                if (_settings.isExploratory()) {
                    int totalNonFailed = 0;
                    for (TunnelInfo t : _tunnels) {
                        if (!t.getTunnelFailed()) totalNonFailed++;
                    }
                    int maxTotal = Math.max(target * 3, 6);
                    if (totalNonFailed >= maxTotal) {
                        if (_log.shouldWarn()) {
                            _log.warn(toString() + " -> Exploratory pool at capacity (" + totalNonFailed +
                                      " >= max " + maxTotal + ", target=" + target +
                                      ") — rejecting build \n* " + info);
                        }
                        return;
                    }
                }
                _tunnels.add(info);
                // Track this tunnel ID as recently added
                _recentlyAddedTunnels.put(gatewayId, now);
                if (_settings.isInbound() && !_settings.isExploratory()) {ls = locked_buildNewLeaseSet();}
            }
        } finally {_tunnelsLock.unlock();}
        if (info.getExpiration() > now + 60*1000 && ls != null) {
            // Check if we already have a published LeaseSet in NetDB
            Hash destHash = _settings.getDestination();
            boolean hasPublishedLS = _context.netDb().lookupLeaseSetLocally(destHash) != null;

            // Bypass throttle when the pool is nearly empty — ensures the LS
            // is never left empty for the full 5-min throttle window after
            // all tunnels expire simultaneously. The first replacement tunnel
            // gets published immediately so clients can reconnect.
            if (!hasPublishedLS || now - _lastRefreshTime >= getRefreshThrottle(_context) ||
                getActiveTunnelCount() <= 1) {
                _lastRefreshTime = now;
                requestLeaseSet(ls);
                pruneNonPublishedTunnels(ls);
            }
        }
    }

    /**
     *  Remove a tunnel from the pool.
     *  @param info the tunnel to remove
     */
    void removeTunnel(TunnelInfo info) {
        int remaining = 0;
        _tunnelsLock.lock();
        try {
            boolean removed = _tunnels.remove(info);
            if (!removed) {return;}
            remaining = _tunnels.size();
        } finally {_tunnelsLock.unlock();}

        if (_log.shouldDebug()) {_log.debug(toString() + " -> Removing tunnel " + info);}

        // Do NOT cancel the ExpireJob here.  The 2-phase ExpireJob lifecycle
        // must complete: Phase 1 (pool removal + LS refresh) has already run
        // or will run, and Phase 2 (dispatcher removal) fires 10 min later
        // (LEASESET_GRACE_PERIOD).  Canceling the ExpireJob here would leave
        // the tunnel orphaned in the dispatcher without proper lifecycle
        // management, and would yank the tunnel from the LS before clients
        // with cached LSes have had time to transition.  The ExpireJob entry
        // is cleaned up naturally when Phase 2 fires.

        _manager.tunnelFailed();
        _lifetimeProcessed += info.getProcessedMessagesCount();
        updateRate();
        long lifetimeConfirmed = info.getVerifiedBytesTransferred();
        long lifetime = getTunnelLifetime(_context);

        for (int i = 0; i < info.getLength(); i++) {
            _context.profileManager().tunnelLifetimePushed(info.getPeer(i), lifetime, lifetimeConfirmed);
        }
        if (_alive) {
            // Invalidate cached LeaseSet — it may reference the tunnel we just
            // removed.  Without this, refreshLeaseSet(true) returns the stale
            // cache for up to 5 minutes, publishing a LS with ghost tunnels.
            _cachedLeaseSet = null;
            // Proactively build replacements when valid tunnel count falls below target.
            // This catches all removal paths (expiry, failure, manual) and prevents
            // the pool from draining to zero.
            ensureSufficientTunnels();
            if (_settings.isInbound() && !_settings.isExploratory()) {
                // Let the 5s throttle batch rapid removals rather than
                // publishing a new LeaseSet on every single removal.
                refreshLeaseSet(false);
            }
        }

        if (getTunnelCount() <= 0 && !isAlive()) {
            // this calls both our shutdown() and the other one (inbound/outbound)
            // This is racy - see TunnelPoolManager
            _manager.removeTunnels(_settings.getDestination());
        }
    }

    /**
     * Synchronous tunnel removal for use during recovery or critical situations.
     * This removes the tunnel and ensures a new LeaseSet is published BEFORE returning,
     * preventing client connection failures during recovery.
     *
     * @param info tunnel to remove
     * @return true if removed and LeaseSet republished, false otherwise
     * @since 0.9.68+
     */
    boolean removeTunnelSynchronous(TunnelInfo info) {
        if (_log.shouldDebug()) {_log.debug(toString() + " -> Synchronous tunnel removal " + info);}

        LeaseSet ls = null;
        int remaining = 0;
        boolean removed = false;

        _tunnelsLock.lock();
        try {
            if (_tunnels.remove(info)) {
                removed = true;
            }
            remaining = _tunnels.size();

            if (_settings.isInbound() && !_settings.isExploratory()) {
                List<TunnelInfo> tunnelsCopy = new ArrayList<TunnelInfo>(_tunnels);
                ls = buildNewLeaseSetFromCopy(tunnelsCopy, true);
            }
        } finally {_tunnelsLock.unlock();}

        if (removed) {
            _manager.tunnelFailed();
            processRemovalStats(java.util.Collections.singletonList(info));
        }

        if (_alive && _settings.isInbound() && !_settings.isExploratory()) {
            if (ls != null) {
                requestLeaseSet(ls, true);
            } else {
                if (_log.shouldWarn()) {
                    _log.warn(toString() + " -> Unable to build LeaseSet on sync removal (" + remaining + " remaining)");
                }
                if (_settings.getAllowZeroHop()) {
                    buildFallback();
                }
            }
        }

        if (removed && _log.shouldDebug()) {
            _log.debug(toString() + " -> Synchronous tunnel removal complete, LeaseSet republished");
        }
        if (removed) {
            ensureSufficientTunnels();
        }

        return removed;
    }

    /**
     * Build a LeaseSet from a copy of the tunnels list.
     * Caller must NOT hold _tunnels lock.
     *
     * @param tunnelsCopy copy of the tunnels list
     * @return LeaseSet or null if not enough tunnels
     */
    private LeaseSet buildNewLeaseSetFromCopy(List<TunnelInfo> tunnelsCopy, boolean isServerPool) {
        long now = _context.clock().now();
        long expireAfter = now + 10 * 1000;
        int wanted = Math.min(_settings.getQuantity(), LeaseSet.MAX_LEASES);

        TunnelInfo zeroHopTunnel = null;
        Lease zeroHopLease = null;
        TreeSet<Lease> leases = new TreeSet<Lease>(new LeaseComparator());

        boolean hasGoodTunnel = false;
        for (TunnelInfo tunnel : tunnelsCopy) {
            if (!tunnel.getTunnelFailed() &&
                tunnel.getTestStatus() == net.i2p.router.TunnelTestStatus.GOOD) {
                hasGoodTunnel = true;
                break;
            }
        }

        for (TunnelInfo tunnel : tunnelsCopy) {
            // Only include GOOD tunnels by default. When no GOOD tunnels exist
            // (e.g. test queue saturated), fall back to UNTESTED tunnels —
            // they're fully built and functional, just not yet verified.
            if (tunnel.getTunnelFailed()) continue;
            if (tunnel.getTestStatus() == net.i2p.router.TunnelTestStatus.GOOD) {
                // include — known good
            } else if (hasGoodTunnel || tunnel.getTestStatus() != net.i2p.router.TunnelTestStatus.UNTESTED) {
                continue;
            }
            // else: no GOOD tunnels exist and this is UNTESTED — include as fallback

            TunnelId inId = tunnel.getReceiveTunnelId(0);
            Hash gw = tunnel.getPeer(0);
            if ((inId == null) || (gw == null)) {
                continue;
            }
            Lease lease = new Lease();
            long realExpiration = tunnel.getExpiration();
            if (tunnel instanceof TunnelCreatorConfig) {
                realExpiration = ((TunnelCreatorConfig) tunnel).getConfig(0).getExpiration();
            }
            lease.setEndDate(realExpiration);
            lease.setTunnelId(inId);
            lease.setGateway(gw);
            leases.add(lease);
            if (tunnel.getLength() <= 1) {
                zeroHopLease = lease;
            }
        }

        if (leases.isEmpty()) {
            TunnelInfo fallback = findBestDegradedTunnel(tunnelsCopy, isServerPool);
            if (fallback != null) {
                TunnelId inId = fallback.getReceiveTunnelId(0);
                Hash gw = fallback.getPeer(0);
                if (inId != null && gw != null) {
                    Lease lease = buildLeaseFromTunnel(fallback);
                    leases.add(lease);
                    if (_log.shouldWarn()) {
                        _log.warn(toString() + "\n* Emergency fallback lease from degraded tunnel (" +
                                  fallback.getConsecutiveFailures() + " failures)");
                    }
                }
            }
        }
        if (leases.isEmpty()) {
            return null;
        }

        LeaseSet ls = new LeaseSet();
        java.util.Iterator<Lease> iter = leases.iterator();
        int count = Math.min(leases.size(), wanted);
        for (int i = 0; i < count; i++) {
            ls.addLease(iter.next());
        }
        return ls;
    }

    /**
     * Process removal statistics for removed tunnels.
     *
     * @param removed list of removed tunnels
     */
    private void processRemovalStats(List<TunnelInfo> removed) {
        for (TunnelInfo info : removed) {
            _lifetimeProcessed += info.getProcessedMessagesCount();
            long lifetimeConfirmed = info.getVerifiedBytesTransferred();
            long lifetime = 10 * 60 * 1000;
            for (int i = 0; i < info.getLength(); i++) {
                _context.profileManager().tunnelLifetimePushed(info.getPeer(i), lifetime, lifetimeConfirmed);
            }
        }
        updateRate();
    }

    /**
     *  Remove tunnel and blame all peers (not necessarily equally).
     *  This may be called multiple times from TestJob.
     *  Enhanced with intelligent failure analysis and recovery.
     *  @param cfg the tunnel that failed
     */
    void tunnelFailed(TunnelInfo cfg) {
        fail(cfg);
        // Enhanced failure analysis before blaming peers
        if (shouldAnalyzeFailure(cfg)) {
            analyzeFailurePattern(cfg);
        }
        tellProfileFailed(cfg);
    }

    /**
     *  Remove the tunnel and blame only one peer.
     *  This may be called multiple times.
     *
     *  @param cfg the tunnel that failed
     *  @param blamePeer the peer to blame
     *  @since 0.8.13
     */
    void tunnelFailed(TunnelInfo cfg, Hash blamePeer) {
        fail(cfg);
        _context.profileManager().tunnelFailed(blamePeer, 100);
        tellProfileFailed(cfg);
    }

    /**
     *  Remove the tunnel from the pool.
     *  @param cfg the tunnel to remove
     */
    /**
     *  Does this pool publish a LeaseSet to the network?
     *  Structural: inbound + non-exploratory. Additionally, for
     *  I2CP pools, the session's i2cp.dontPublishLeaseSet option
     *  can suppress publication.
     */
    private boolean publishesLeaseSet() {
        if (!_settings.isInbound() || _settings.isExploratory()) {return false;}
        Hash dest = _settings.getDestination();
        if (dest == null) {return false;}
        return _context.clientManager().shouldPublishLeaseSet(dest);
    }

    private void fail(TunnelInfo cfg) {
        if (cfg.getConsecutiveFailures() > 1) {
            int failures = cfg.getConsecutiveFailures();
            int remaining = size();
            // A tunnel with 5+ consecutive test failures cannot route traffic.
            // Remove it immediately — it wastes build slots and test cycles.
            // The collapse guard only protects tunnels with <= 4 failures.
            boolean isDead = failures > 4;
            if (remaining <= 1 && !isDead) {
                // Collapse guard: don't remove if this would leave the pool
                // with zero usable tunnels and the tunnel isn't conclusively dead.
                if (_log.shouldWarn()) {
                    _log.warn("Keeping " + (cfg.isInbound() ? "inbound" : "outbound") +
                              " tunnel despite " + failures +
                              " failures — would collapse pool (1 remaining) \n* " + cfg);
                }
                ensureSufficientTunnels();
                return;
            }
            if (_log.shouldWarn()) {
                _log.warn("Removing " + (cfg.isInbound() ? "inbound" : "outbound") +
                          " tunnel via fail() -> " + failures +
                          " failures (remaining=" + remaining +
                          (isDead ? ", dead" : "") + ") \n* " + cfg);
            }
            removeTunnel(cfg);
        }
    }

    /**
     *  Defer LeaseSet republish so rapid consecutive failures in server pools
     *  are batched into a single LeaseSet update. Uses a boolean gate so that
     *  multiple failures within the 10s debounce window only trigger one
     *  republish.
     */
    private synchronized void scheduleDeferredLeaseSetRepublish() {
        if (_leaseSetRepublishPending) {
            // Already scheduled within the debounce window, no change
            return;
        }
        _leaseSetRepublishPending = true;
        _context.simpleTimer2().addEvent(new LeaseSetRepublishEvent(), 10000);
    }

    private class LeaseSetRepublishEvent implements net.i2p.util.SimpleTimer.TimedEvent {
        public void timeReached() {
            _leaseSetRepublishPending = false;
            refreshLeaseSet(true);
            pruneNonGoodTunnels();
        }
    }

    /**
     *  Blame all peers in tunnel, with a probability
     *  inversely related to tunnel length
     *  Enhanced with intelligent blame distribution and recovery consideration.
     *  Also schedules priority peer tests so failed peers are quickly
     *  identified and evicted from fast/high capacity tiers.
     *  @param cfg the failed tunnel
     */
    private void tellProfileFailed(TunnelInfo cfg) {
        long uptime = _context.router().getUptime();
        int len = cfg.getLength();
        if (len < 2) {return;}
        int start = 0;
        int end = len;
        if (cfg.isInbound()) {end--;}
        else {start++;}

        // Analyze failure pattern to adjust blame intelligently
        int consecutiveFailures = _consecutiveBuildTimeouts.get();

        List<Hash> peersToTest = null;
        for (int i = start; i < end; i++) {
            int pct = 100/(len-1);
            Hash peer = cfg.getPeer(i);

            // Standard blame logic - avoid over-complex reductions
            if (cfg.isInbound() && len > 2) {
                if (i == start) {pct *= 2;}
                else {pct /= 2;}
            }

            // Only moderate reduction for high failure scenarios
            if (consecutiveFailures > 6) {
                pct = Math.max(pct * 3 / 4, 15); // Reduce by 25%, minimum 15% blame
            }

            if (uptime > getStartupTime(_context) && _log.shouldWarn() && consecutiveFailures > 5) {
                _log.warn("Tunnel from " + toString() + " failed -> Blaming [" + peer.toBase64().substring(0,6) + "] -> " + pct + '%' +
                          " (" + consecutiveFailures + " consecutive failures)");
            }
            _context.profileManager().tunnelFailed(peer, pct);

            // Collect peers for priority testing — the sooner we retest,
            // the sooner bad peers drop from fast/high cap tiers
            if (peer != null && _context.clock().now() > getStartupTime(_context)) {
                if (peersToTest == null) {peersToTest = new ArrayList<Hash>(Math.min(end - start, 3));}
                if (peersToTest.size() < 3 && !peersToTest.contains(peer)) {peersToTest.add(peer);}
            }
        }
        if (peersToTest != null && !peersToTest.isEmpty()) {
            net.i2p.router.peermanager.PeerTestJob testJob = _context.peerManager().getPeerTestJob();
            if (testJob != null) {
                testJob.schedulePriorityTests(peersToTest);
                if (_log.shouldInfo()) {
                    _log.info("Scheduled " + peersToTest.size() + " priority peer tests from " + toString() + " failure");
                }
            }
        }
    }

    private void updateRate() {
        long now = _context.clock().now();
        long et = now - _lastRateUpdate;
        if (et > 2*60*1000) {
            long bw = 1024 * (_lifetimeProcessed - _lastLifetimeProcessed) * 1000 / et;   // Bps
            _context.statManager().addRateData(_rateName, bw);
            _lastRateUpdate = now;
            _lastLifetimeProcessed = _lifetimeProcessed;
        }
    }

    /** noop for outbound and exploratory */
    void refreshLeaseSet() {
        refreshLeaseSet(false);
    }

    /**
     * Proactively republish the LeaseSet when all tunnels are healthy (all > 5 min expiry).
     * This ensures the LeaseSet always has fresh lifetime and prevents orphaned LeaseSets
     * where the LeaseSet is technically valid but all leases have expired.
     *
     * Unlike refreshLeaseSet() which republishes when tunnels are EXPIRING SOON,
     * this republishes when tunnels are ALL HEALTHY to reset the LeaseSet lifetime.
     *
     * @since 0.9.72
     */
    void proactiveRepublishIfHealthy() {
        if (!_settings.isInbound() || _settings.isExploratory() || !_alive) {
            return;
        }

        long now = _context.clock().now();
        long threeMinutes = 3 * 60 * 1000;
        long expiryThreshold = now + threeMinutes;

        boolean allHealthy = false;
        int tunnelCount = 0;

        _tunnelsLock.lock();
        try {
            for (TunnelInfo t : _tunnels) {
                if (t.getExpiration() > now) {
                    tunnelCount++;
                    if (t.getExpiration() > expiryThreshold) {
                        allHealthy = true;
                    } else {
                        // At least one tunnel expiring soon, let existing logic handle it
                        return;
                    }
                }
            }
        } finally {_tunnelsLock.unlock();}

        // Only republish if we have tunnels and they're all healthy
        if (tunnelCount > 0 && allHealthy) {
            // Rate limit: don't republish more than every 3 minutes
            long lastPublish = _lastLeaseSetPublishTime;
            if (lastPublish > 0 && now - lastPublish < threeMinutes) {
                return;
            }

            // Don't republish during attacks (low build success)
            double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
            if (buildSuccess < 0.40) {
                return;
            }

            LeaseSet ls;
            _tunnelsLock.lock();
            try {ls = locked_buildNewLeaseSet();} finally {_tunnelsLock.unlock();}
            if (ls != null && ls.getLeaseCount() >= _settings.getQuantity() / 2) {
                if (_log.shouldInfo()) {
                    _log.info("Proactive republish: all " + tunnelCount + " tunnels healthy, refreshing LeaseSet lifetime");
                }
                _lastLeaseSetPublishTime = now;
                requestLeaseSet(ls);
            }
        }
    }

    /**
     * Refresh the LeaseSet, throttled to prevent flooding but not on initial creation.
     * @param force if true, bypass throttle (for critical refresh when below minimum or near expiry)
     */
    void refreshLeaseSet(boolean force) {
        // Skip LeaseSet refresh for ping tunnels - they're short-lived and don't publish LeaseSets
        String nickname = _settings.getDestinationNickname();
        if (nickname != null && (nickname.equals("I2Ping") ||
            (nickname.startsWith("Ping") && nickname.contains("[")))) {
            if (_log.shouldDebug()) {
                _log.debug(toString() + "\n* Skipping LeaseSet refresh - ping tunnel");
            }
            return;
        }

        if (_settings.isInbound() && !_settings.isExploratory()) {
            long now = _context.clock().now();

            // Track first publication locally instead of checking NetDB —
            // LeaseSets are sent to the client asynchronously and may not
            // be stored in NetDB yet when this check runs, causing every
            // call to auto-force and bypass the throttle.
            boolean hasPublishedBefore = _lastRefreshTime > 0;
            if (!force && !hasPublishedBefore) {
                force = true;
            }
            if (!force && now - _lastRefreshTime < getRefreshThrottle(_context)) {
                // Instead of dropping, schedule a deferred refresh.
                scheduleDeferredRefresh();
                return;
            }
            _lastRefreshTime = now;
            if (_log.shouldDebug()) {
                _log.debug(toString() + "\n* Refreshing LeaseSet (force=" + force + ", count=" + getTunnelCount() + ")");
            }
            LeaseSet ls;
            _tunnelsLock.lock();
            try {ls = locked_buildNewLeaseSet();} finally {_tunnelsLock.unlock();}
            if (ls != null) {
                requestLeaseSet(ls);
                pruneNonPublishedTunnels(ls);
            }
            // On each publish cycle, clean out tunnels that haven't passed
            // testing so ensureSufficientTunnels() builds replacements.
            pruneNonGoodTunnels();
        }
    }

    /**
     * Schedule a deferred LeaseSet refresh to fire after the throttle window expires.
     * Prevents dropping refresh requests during rapid tunnel changes.
     */
    private void scheduleDeferredRefresh() {
        if (_pendingRefreshScheduled) {
            return;
        }
        _pendingRefreshScheduled = true;
        long delay = getRefreshThrottle(_context) - (_context.clock().now() - _lastRefreshTime);
        if (delay <= 0) {
            delay = getRefreshThrottle(_context);
        }
        _context.simpleTimer2().addEvent(new DeferredRefreshEvent(), delay);
        if (_log.shouldDebug()) {
            _log.debug(toString() + " -> Scheduled deferred LeaseSet refresh in " + (delay / 1000) + "s");
        }
    }

    /**
     * Event to perform deferred LeaseSet refresh after throttle window expires.
     */
    private class DeferredRefreshEvent implements net.i2p.util.SimpleTimer.TimedEvent {
        public void timeReached() {
            _pendingRefreshScheduled = false;
            refreshLeaseSet(false);
        }
    }

    /**
     * Check if the LeaseSet is expiring soon (within 1 minute).
     * @param now current time
     * @return true if refresh should be forced
     */
    boolean isExpiringSoon(long now) {
        _tunnelsLock.lock();
        try {
            LeaseSet ls = locked_buildNewLeaseSet();
            if (ls != null) {
                long earliestExpiry = ls.getEarliestLeaseDate();
                return earliestExpiry > 0 && earliestExpiry < now + 60 * 1000;
            }
        } finally {_tunnelsLock.unlock();}
        return false;
    }

    /**
     * Build and return current LeaseSet from our tunnels.
     * Used by TunnelPoolManager to validate NetDB has current data.
     * @return current LeaseSet or null
     */
    LeaseSet getInboundTunnelsAsLeaseSet() {
        _tunnelsLock.lock();
        try {return locked_buildNewLeaseSet();} finally {_tunnelsLock.unlock();}
    }

    /**
     * Called by TestJob when a server pool tunnel fails a test but is retained.
     * Schedules a deferred republish to batch any additional failures within
     * the 10s debounce window rather than publishing an emergency LeaseSet
     * on every single failure.
     * @since 0.9.69+
     */
    void notifyServerPoolTestFailed() {
        if (!_settings.isInbound() || _settings.isExploratory() || !_alive)
            return;
        // Don't publish on every test failure — the deferred republish batches
        // rapid test failures and respects the refresh throttle (5 min min).
        scheduleDeferredLeaseSetRepublish();
    }

    /**
     *  Request lease set from client for the primary and all aliases.
     *
     *  @param ls non-null
     *  @since 0.9.49
     */
    private void requestLeaseSet(LeaseSet ls) {
        // Always register the LS locally so ClientManager can serve it to
        // sidebar queries and local consumers. Network publication is
        // handled by ClientManager.shouldPublishLeaseSet() which checks
        // i2cp.dontPublishLeaseSet internally.
        _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
        Set<Hash> aliases = _settings.getAliases();
        if (aliases != null && !aliases.isEmpty()) {
            for (Hash h : aliases) {
                 // don't corrupt other requests
                 LeaseSet ls2 = new LeaseSet();
                 for (int i = 0; i < ls.getLeaseCount(); i++) {ls2.addLease(ls.getLease(i));}
                _context.clientManager().requestLeaseSet(h, ls2);
            }
        }
    }

    /**
     *  Request LeaseSet with optional force bypass of throttle.
     *  @param ls non-null
     *  @param force if true, bypass throttle
     */
    private void requestLeaseSet(LeaseSet ls, boolean force) {
        if (force) {
            _lastRefreshTime = _context.clock().now();
        }
        requestLeaseSet(ls);
    }

    /**
     *  This will build a fallback (zero-hop) tunnel ONLY if
     *  this pool is exploratory, or the settings allow it.
     *
     *  @return true if a fallback tunnel is built, false otherwise
     */
    boolean buildFallback() {
        int quantity = getAdjustedTotalQuantity();
        int usable = getValidTunnelCount();
        if (usable > 0) {return false;}

        if (_settings.isExploratory() || _settings.getAllowZeroHop()) {
            if (_log.shouldInfo()) {
                _log.info(toString() + "\n* Building a fallback tunnel (Usable: " + usable + "; Needed: " + quantity + ")");
            }
            // runs inline, since its 0hop
            _manager.getExecutor().buildTunnel(configureNewTunnel(true));
            return true;
        }
        return false;
    }

    /**
     * Always build a LeaseSet with Leases in sorted order,
     * so that LeaseSet.equals() and lease-by-lease equals() always work.
     * The sort method is arbitrary, as far as the equals() tests are concerned,
     * but we use latest expiration first, since we need to sort them by that anyway.
     *
     */
    private static class LeaseComparator implements Comparator<Lease>, Serializable {
         public int compare(Lease l, Lease r) {
             long lt = l.getEndTime();
             long rt = r.getEndTime();
             if (rt > lt) {return 1;}
             if (rt < lt) {return -1;}
             return 0;
        }
    }

    /**
     *  Find the tunnel with the far-end that is XOR-closest to a given hash.
     *  @since 0.8.10
     */
    private static class TunnelInfoComparator implements Comparator<TunnelInfo>, Serializable {
        private final byte[] _base;
        private final boolean _avoidZero;

        /**
         * @param target key to compare distances with
         * @param avoidZeroHop if true, zero-hop tunnels will be put last
         */
        public TunnelInfoComparator(Hash target, boolean avoidZeroHop) {
            _base = target.getData();
            _avoidZero = avoidZeroHop;
        }

        public int compare(TunnelInfo lhs, TunnelInfo rhs) {
            if (_avoidZero) {
                // put the zero-hops last
                int llen = lhs.getLength();
                int rlen = rhs.getLength();
                if (llen > 1 && rlen <= 1) {return -1;}
                if (rlen > 1 && llen <= 1) {return 1;}
            }
            // TODO don't prefer exact match for security?
            byte lhsb[] = lhs.getFarEnd().getData();
            byte rhsb[] = rhs.getFarEnd().getData();
            for (int i = 0; i < _base.length; i++) {
                int ld = (lhsb[i] ^ _base[i]) & 0xff;
                int rd = (rhsb[i] ^ _base[i]) & 0xff;
                if (ld < rd) {return -1;}
                if (ld > rd) {return 1;}
            }
            // latest-expiring first as a tie-breaker
            return (int) (rhs.getExpiration() - lhs.getExpiration());
        }
    }

    /**
     * Build a leaseSet with the required tunnels that aren't about to expire.
     * Caller must hold _tunnelsLock.
     * The returned LeaseSet will be incomplete; it will not have the destination
     * set and will not be signed. Only the leases will be included.
     *
     * @return null on failure
     */
    protected LeaseSet locked_buildNewLeaseSet() {
        if (!_alive) {return null;}

        long now = _context.clock().now();

        // Rate-limit: return cached LeaseSet if within minimum interval.
        // Tunnels have a 10-minute lifetime; a cached LS within 5 min is
        // still valid and prevents unnecessary churn in published leases.
        // Emergency callers (removeTunnelSynchronous) use buildNewLeaseSetFromCopy
        // and bypass this cache entirely.
        if (_cachedLeaseSet != null && now - _lastLeaseSetBuildTime < getLeaseSetBuildMinInterval(_context)) {
            return _cachedLeaseSet;
        }

        int wanted = Math.min(_settings.getQuantity(), LeaseSet.MAX_LEASES);
        if (_tunnels.size() < wanted) {
            if (_log.shouldInfo()) {
                _log.info(toString() + "\n* Not enough tunnels to build full LeaseSet (" + _tunnels.size() + "/" + wanted + " available)");
            }
            // See comment below
            if (_tunnels.isEmpty()) {return null;}
        }

        // We don't want it to expire before the client signs it or the ff gets it
        long expireAfter = now + 5*60*1000;

        boolean hasGoodTunnel = false;
        for (int i = 0; i < _tunnels.size(); i++) {
            TunnelInfo t = _tunnels.get(i);
            if (!t.getTunnelFailed() && t.getTestStatus() == net.i2p.router.TunnelTestStatus.GOOD &&
                t.getExpiration() > expireAfter) {
                hasGoodTunnel = true;
                break;
            }
        }

        TunnelInfo zeroHopTunnel = null;
        Lease zeroHopLease = null;
        List<TunnelInfo> goodTunnels = new ArrayList<TunnelInfo>();
        for (int i = 0; i < _tunnels.size(); i++) {
            TunnelInfo tunnel = _tunnels.get(i);
            if (tunnel.getTunnelFailed()) continue;
            if (tunnel.getTestStatus() == net.i2p.router.TunnelTestStatus.GOOD) {
                // include — known good
            } else if (hasGoodTunnel || tunnel.getTestStatus() != net.i2p.router.TunnelTestStatus.UNTESTED) {
                continue;
            }
            // else: no GOOD tunnels exist and this is UNTESTED — include as fallback
            if (tunnel.getExpiration() <= expireAfter) {continue;}

            if (tunnel.getLength() <= 1) {
                // More than one zero-hop tunnel in a lease is pointless
                // and increases the leaseset size needlessly.
                // Keep only the one that expires the latest.
                if (zeroHopTunnel != null) {
                    if (zeroHopTunnel.getExpiration() > tunnel.getExpiration()) {continue;}
                }
                zeroHopTunnel = tunnel;
                continue;
            }

            TunnelId inId = tunnel.getReceiveTunnelId(0);
            Hash gw = tunnel.getPeer(0);
            if ((inId == null) || (gw == null)) {
                _log.error(toString() + "-> Broken? Tunnel has no InboundGateway / TunnelID? " + tunnel);
                continue;
            }
            goodTunnels.add(tunnel);
        }

        // Sort by latency ascending — prefer fast tunnels for the LeaseSet.
        // Tunnels with no latency data sort after tested ones.
        Collections.sort(goodTunnels, new Comparator<TunnelInfo>() {
            public int compare(TunnelInfo a, TunnelInfo b) {
                int la = getTunnelAvgLatency(a);
                int lb = getTunnelAvgLatency(b);
                if (la < 0 && lb < 0) return 0;
                if (la < 0) return 1;
                if (lb < 0) return -1;
                return Integer.compare(la, lb);
            }
        });

        // Take only the best latency tunnels up to wanted count
        int wantedLeases = wanted - (zeroHopTunnel != null ? 1 : 0);
        if (goodTunnels.size() > wantedLeases) {
            goodTunnels = new ArrayList<TunnelInfo>(goodTunnels.subList(0, wantedLeases));
        }

        TreeSet<Lease> leases = new TreeSet<Lease>(new LeaseComparator());

        // Add zero-hop lease if present
        if (zeroHopTunnel != null) {
            Lease lease = buildLeaseFromTunnel(zeroHopTunnel);
            if (lease != null) {
                leases.add(lease);
                zeroHopLease = lease;
            }
        }

        // Add latency-sorted GOOD tunnels
        for (TunnelInfo tunnel : goodTunnels) {
            Lease lease = buildLeaseFromTunnel(tunnel);
            if (lease != null) {
                leases.add(lease);
            }
        }

        /* Go ahead and use less leases for now, hopefully a new tunnel will be built soon,
         * and we will get called again to generate a full leaseset.
         *
         * For clients with high tunnel count or length, this will make startup considerably faster,
         * and reduce loss of leaseset when one tunnel is lost, thus making us much more robust.
         *
         * This also helps when returning to full lease count after reduce-on-idle or close-on-idle.
         * So we will generate a succession of leases at startup. That's OK.
         * Do we want a config option for this, or are there times when we shouldn't do this?
         */
        if (leases.isEmpty()) {
            // All tunnels filtered by quality checks — pick the best degraded
            // tunnel as a fallback so the LeaseSet never goes empty.
            TunnelInfo fallback = findBestDegradedTunnel();
            if (fallback != null) {
                TunnelId inId = fallback.getReceiveTunnelId(0);
                Hash gw = fallback.getPeer(0);
                if (inId != null && gw != null) {
                    Lease lease = buildLeaseFromTunnel(fallback);
                    leases.add(lease);
                    if (_log.shouldWarn()) {
                        _log.warn(toString() + "\n* Emergency fallback lease from degraded tunnel (" +
                                  fallback.getConsecutiveFailures() + " failures)");
                    }
                }
            }
        }
        if (leases.size() < wanted) {
            if (_log.shouldInfo()) {
                _log.info(toString() + "\n* Not enough leases to build full LeaseSet (" + leases.size() + "/" + wanted + " available)");
            }
            if (leases.isEmpty()) {return null;}
        }

        LeaseSet ls = new LeaseSet();
        Iterator<Lease> iter = leases.iterator();
        int count = Math.min(leases.size(), wanted);
        for (int i = 0; i < count; i++) {ls.addLease(iter.next());}
        if (_log.shouldInfo()) {_log.info(toString() + " -> New LeaseSet built" + ls);}
        _cachedLeaseSet = ls;
        _lastLeaseSetBuildTime = now;
        return ls;
    }



    /**
     *  @return average test latency in ms for the tunnel, or -1 if unknown
     */
    static int getTunnelAvgLatency(TunnelInfo t) {
        if (t instanceof TunnelCreatorConfig) {
            return ((TunnelCreatorConfig) t).getAverageLatency();
        }
        return -1;
    }

    /**
     *  Remove non-GOOD tunnels from the pool when enough GOOD ones remain.
     *  This prevents FAILING tunnel accumulation that stalls getActiveTunnelCount()
     *  and prevents the pool from building replacements via ensureSufficientTunnels().
     */
    private void pruneNonGoodTunnels() {
        List<TunnelInfo> toRemove = new ArrayList<TunnelInfo>();
        int goodCount = 0;
        boolean isServerPool = _settings.isInbound() && !_settings.isExploratory();
        _tunnelsLock.lock();
        try {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo t = _tunnels.get(i);
                // For server pools: never prune GOOD or FAILING tunnels.
                // GOOD tunnels are published in the LeaseSet and removing them
                // breaks client connections.  FAILING tunnels were recently GOOD
                // and the old LeaseSet (propagated to peers) still references
                // them — pruning during the propagation window causes unreachable
                // destinations.  Let them expire naturally (10 min).
                if (isServerPool && (t.getTestStatus() == net.i2p.router.TunnelTestStatus.GOOD ||
                                     t.getTestStatus() == net.i2p.router.TunnelTestStatus.FAILING)) {
                    goodCount++;
                    continue;
                }
                if (t.getTunnelFailed() ||
                    (t.getTestStatus() != net.i2p.router.TunnelTestStatus.GOOD &&
                     t.getTestStatus() != net.i2p.router.TunnelTestStatus.UNTESTED)) {
                    toRemove.add(t);
                } else {
                    goodCount++;
                }
            }
        } finally {_tunnelsLock.unlock();}
        if (toRemove.isEmpty()) {return;}

        // Always prune FAILED/FAILING tunnels down to a small reserve.
        // Without this, the pool fills with FAILED tunnels and
        // ensureSufficientTunnels() rejects new builds (totalNow >= target + 8),
        // locking the pool into a permanently degraded state.
        // Reserve keeps the best (lowest-failure) non-GOOD tunnels as LS
        // fallback so the LeaseSet never goes empty.
        // Cap: never keep zombies (>5 failures) as LS fallback — they're
        // useless and block pool recovery by inflating size().
        int target = _settings.getQuantity();
        // Use base target for reserve, NOT effectiveTarget — dynamic scaling
        // must not inflate the LS fallback reserve or pools full of broken
        // tunnels can never recover (reserve = effectiveTarget - 0 = huge).
        int reserve = Math.max(target - goodCount, 0);
        // When pool has zero good tunnels, cap reserve aggressively —
        // keeping broken tunnels as LS fallback is counterproductive
        // when there's nothing good to fall back to.
        if (goodCount == 0) {
            reserve = Math.min(reserve, 1);
        }
        if (reserve > 0) {
            // Only count low-failure non-zombie tunnels toward the reserve.
            // Tunnels with >5 consecutive failures are effectively dead —
            // keeping them blocks pool recovery.
            int nonZombieCount = 0;
            for (TunnelInfo t : toRemove) {
                if (t.getConsecutiveFailures() <= 5) {
                    nonZombieCount++;
                }
            }
            reserve = Math.min(reserve, nonZombieCount);
        }
        int toPrune = toRemove.size() - reserve;
        if (toPrune <= 0) {
            if (_log.shouldInfo()) {
                _log.info("Keeping " + toRemove.size() + " non-GOOD tunnels as LS fallback " +
                          "(good=" + goodCount + ", reserve=" + reserve + ")");
            }
            return;
        }
        // Keep the best reserve tunnels (fewest failures), remove the rest
        if (toRemove.size() > toPrune) {
            Collections.sort(toRemove, new Comparator<TunnelInfo>() {
                public int compare(TunnelInfo a, TunnelInfo b) {
                    return Integer.compare(a.getConsecutiveFailures(), b.getConsecutiveFailures());
                }
            });
            toRemove = new ArrayList<TunnelInfo>(toRemove.subList(toPrune, toRemove.size()));
        }
        if (_log.shouldInfo()) {
            String boost = _consecutiveEmergencies > 0 ?
                " (dynamic target " + Math.min(target + _consecutiveEmergencies, target + MAX_EMERGENCY_BOOST) + ")" : "";
            _log.info("Pruning " + toRemove.size() + " non-GOOD tunnels from " + toString() +
                      " (good=" + goodCount + ", remaining=" + (goodCount + reserve) + ")" + boost);
        }
        // Batch removal: remove all at once under the lock, then do stats/cleanup
        // outside.  Calling removeTunnel() per-tunnel triggers ensureSufficientTunnels()
        // for each one, creating recursive build storms.
        // Do NOT cancel ExpireJobs — the 2-phase lifecycle must complete so the
        // tunnel stays in the dispatcher for the full LEASESET_GRACE_PERIOD
        // after pool removal, giving clients with cached LeaseSets time to
        // transition to new tunnels.
        _tunnelsLock.lock();
        try {
            for (TunnelInfo t : toRemove) {
                _tunnels.remove(t);
            }
        } finally {_tunnelsLock.unlock();}
        for (TunnelInfo t : toRemove) {
            _manager.tunnelFailed();
            _lifetimeProcessed += t.getProcessedMessagesCount();
            updateRate();
            long lifetime = getTunnelLifetime(_context);
            for (int i = 0; i < t.getLength(); i++) {
                _context.profileManager().tunnelLifetimePushed(t.getPeer(i), lifetime, t.getVerifiedBytesTransferred());
            }
        }
        // Refresh LeaseSet after batch removal (normally done per-tunnel in removeTunnel).
        // Invalidate cache first so refreshLeaseSet() builds fresh.
        _cachedLeaseSet = null;
        if (_alive && _settings.isInbound() && !_settings.isExploratory()) {
            refreshLeaseSet(false);
        }
    }

    /**
     *  After publishing a LeaseSet, prune GOOD tunnels that weren't included
     *  in the published LeaseSet.  These tunnels will just expire unused —
     *  they consume slots and their IP-based LeaseSet presence can't be used
     *  because the LeaseSet referencing them has already been published.
     *  Only applies to inbound server pools that publish LeaseSets.
     */
    private void pruneNonPublishedTunnels(LeaseSet publishedLS) {
        if (publishedLS == null || !_alive || !_settings.isInbound() || _settings.isExploratory()) {
            return;
        }
        // Collect set of published gateway hashes from the LeaseSet
        int numLeases = publishedLS.getLeaseCount();
        if (numLeases <= 0) return;
        Set<Hash> publishedGateways = new HashSet<Hash>(numLeases);
        for (int i = 0; i < numLeases; i++) {
            publishedGateways.add(publishedLS.getLease(i).getGateway());
        }
        if (publishedGateways.isEmpty()) return;
        long now = _context.clock().now();
        List<TunnelInfo> toRemove = new ArrayList<TunnelInfo>();
        _tunnelsLock.lock();
        try {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo t = _tunnels.get(i);
                if (t.getTunnelFailed()) continue;
                if (t.getLength() <= 1) continue;
                if (t.getExpiration() <= now + 5*60*1000) {
                    // Expiring soon — let it expire naturally
                    continue;
                }
                // Don't prune UNTESTED tunnels — they were just built and
                // haven't been tested yet.  Pruning them before testing
                // creates a build→prune→build churn cycle where the pool
                // can never accumulate enough GOOD tunnels.
                if (t.getTestStatus() == net.i2p.router.TunnelTestStatus.UNTESTED) {
                    continue;
                }
                Hash gw = t.getPeer(0);
                if (gw == null) continue;
                if (publishedGateways.contains(gw)) {
                    // Published in LeaseSet — keep
                    continue;
                }
                toRemove.add(t);
            }
        } finally {_tunnelsLock.unlock();}
        if (toRemove.isEmpty()) return;
        // Collapse guard: never prune if it would leave the pool below target.
        // Non-published tunnels are valid backups — they carry data and can be
        // included in the next LeaseSet.  Pruning them creates churn: build 3
        // → publish 1 gateway → prune 2 → pool drops to 0-1 → EMERGENCY → repeat.
        int currentSize = getTunnelCount();
        int target = _settings.getQuantity();
        // Dynamic scaling: keep extra tunnels when pool keeps collapsing
        int effectiveTarget = Math.min(target + _consecutiveEmergencies,
                                       target + MAX_EMERGENCY_BOOST);
        int afterPrune = currentSize - toRemove.size();
        if (afterPrune < effectiveTarget) {
            if (_log.shouldInfo()) {
                _log.info(toString() + " -> Skipping non-published prune — would drop below target " +
                          "(" + currentSize + " total, " + toRemove.size() + " candidates, " +
                          "target " + effectiveTarget + ", after prune " + afterPrune + ")");
            }
            return;
        }
        if (_log.shouldInfo()) {
            _log.info(toString() + " -> Pruning " + toRemove.size() +
                      " non-published tunnels after LeaseSet publish " +
                      "(published " + publishedGateways.size() + " gateways, " +
                      "pool total " + getTunnelCount() + ")");
        }
        // Batch removal under lock
        // Do NOT cancel ExpireJobs — same reason as pruneNonGoodTunnels():
        // the 2-phase lifecycle must complete for proper dispatcher cleanup.
        _tunnelsLock.lock();
        try {
            for (TunnelInfo t : toRemove) {
                _tunnels.remove(t);
            }
        } finally {_tunnelsLock.unlock();}
        for (TunnelInfo t : toRemove) {
            _manager.tunnelFailed();
            _lifetimeProcessed += t.getProcessedMessagesCount();
            updateRate();
            long lifetime = getTunnelLifetime(_context);
            for (int i = 0; i < t.getLength(); i++) {
                _context.profileManager().tunnelLifetimePushed(t.getPeer(i), lifetime, t.getVerifiedBytesTransferred());
            }
        }
        // Batch removal bypasses removeTunnel() which normally triggers
        // ensureSufficientTunnels().  Without this, the pool stays short
        // until the next periodic check cycle, extending the window
        // where the pool has fewer tunnels than needed.
        if (_alive) {
            _cachedLeaseSet = null;
            ensureSufficientTunnels();
        }
    }

    /**
     * Build a Lease from a single tunnel's gateway, using the HopConfig
     * expiration (original full lifetime) rather than the possibly-shortened
     * failure expiration.
     */
    private Lease buildLeaseFromTunnel(TunnelInfo cfg) {
        TunnelId inId = cfg.getReceiveTunnelId(0);
        Hash gw = cfg.getPeer(0);
        if (inId == null || gw == null) {return null;}
        Lease lease = new Lease();
        long expiration = cfg.getExpiration();
        if (cfg instanceof TunnelCreatorConfig) {
            expiration = ((TunnelCreatorConfig) cfg).getConfig(0).getExpiration();
        }
        long minExpiry = _context.clock().now() + 60*1000;
        if (expiration < minExpiry) {expiration = minExpiry;}
        lease.setEndDate(expiration);
        lease.setTunnelId(inId);
        lease.setGateway(gw);
        return lease;
    }

    /**
     * Build an emergency LeaseSet from a single tunnel's gateway lease,
     * bypassing failure filters. Used when all tunnels have been removed
     * due to failures, to keep the destination reachable during the
     * replacement build window. Uses the HopConfig expiration (full
     * 10-minute lifetime) rather than the shortened failure expiration.
     */
    private LeaseSet buildEmergencyLeaseSet(TunnelInfo cfg) {
        if (cfg == null) {return null;}
        Lease lease = buildLeaseFromTunnel(cfg);
        if (lease == null) {return null;}
        LeaseSet ls = new LeaseSet();
        ls.addLease(lease);
        if (_log.shouldWarn()) {
            _log.warn(toString() + "\n* Emergency LeaseSet published from failed tunnel " + cfg);
        }
        return ls;
    }

    /**
     * Find the best degraded tunnel from _tunnels for emergency LS fallback.
     * Picks the tunnel with the fewest consecutive failures (excluding fully
     * dead tunnels). If tied, picks the one with the latest expiration.
     * For server pools (inbound non-exploratory), tunnels retained with
     * getTunnelFailed() == true are still eligible — they're deliberately
     * kept to prevent pool collapse and must be available for LS fallback.
     */
    private TunnelInfo findBestDegradedTunnel() {
        long now = _context.clock().now();
        boolean isServerPool = _settings.isInbound() && !_settings.isExploratory();
        TunnelInfo best = null;
        int bestFailures = Integer.MAX_VALUE;
        for (int i = 0; i < _tunnels.size(); i++) {
            TunnelInfo t = _tunnels.get(i);
            if (!isServerPool && (t.getTunnelFailed() ||
                t.getTestStatus() == net.i2p.router.TunnelTestStatus.FAILED)) {
                continue;
            }
            if (t.getReceiveTunnelId(0) == null || t.getPeer(0) == null) {
                continue;
            }
            if (t.getExpiration() <= now + 10*1000) {continue;}
            int failures = t.getConsecutiveFailures();
            if (best == null || failures < bestFailures ||
                (failures == bestFailures && t.getExpiration() > best.getExpiration())) {
                best = t;
                bestFailures = failures;
            }
        }
        return best;
    }

    /**
     * Find the best degraded tunnel from a given list for emergency LS fallback.
     * @param isServerPool if true, don't skip getTunnelFailed() tunnels
     */
    private static TunnelInfo findBestDegradedTunnel(List<TunnelInfo> tunnels, boolean isServerPool) {
        TunnelInfo best = null;
        int bestFailures = Integer.MAX_VALUE;
        for (TunnelInfo t : tunnels) {
            if (!isServerPool && (t.getTunnelFailed() ||
                t.getTestStatus() == net.i2p.router.TunnelTestStatus.FAILED)) {
                continue;
            }
            if (t.getReceiveTunnelId(0) == null || t.getPeer(0) == null) {
                continue;
            }
            int failures = t.getConsecutiveFailures();
            if (best == null || failures < bestFailures ||
                (failures == bestFailures && t.getExpiration() > best.getExpiration())) {
                best = t;
                bestFailures = failures;
            }
        }
        return best;
    }

    /**
     *  Total lifetime processed bytes for this pool.
     *  @return the total number of bytes processed through this pool
     *  @since 0.9.53
     */
    public long getLifetimeProcessed() {return _lifetimeProcessed;}

    /**
     * Keep a separate stat for each type, direction, and length of tunnel.
     */
    private final String buildRateName() {
        if (_settings.isExploratory()) {return "tunnel.buildRatio.exploratory." + (_settings.isInbound() ? " In" : " Out");}
        else {return "tunnel.buildRatio.l" + _settings.getLength() + "v" + _settings.getLengthVariance() + (_settings.isInbound() ? ".in" : ".out");}
    }

    /**
     *  This only sets the peers and creation/expiration times in the configuration.
     *  For the crypto, see BuildRequestor and BuildMessageGenerator.
     *
     *  @return null on failure
     */
    PooledTunnelCreatorConfig configureNewTunnel() {return configureNewTunnel(false);}

    /**
     *  Ensure the pool has at least target valid tunnels, building replacements
     *  proactively when the count drops below target. This prevents the pool
     *  from silently draining to zero, avoiding tunnel collapse cascades.
     */
    /** called from RemoveSlowTunnelsJob in TunnelPoolManager */
    void ensureSufficientTunnels() {
        if (!_alive || !_ensuringTunnels.compareAndSet(false, true)) {return;}
        try {
        // Clear out dead tunnels before counting, so FAILING/FAILED tunnels
        // don't inflate the count and block replacement builds.
        pruneNonGoodTunnels();
        int target = _settings.getQuantity();
        // Dynamic scaling: boost target when pool keeps collapsing.
        // More tunnels = more resilience. LeaseSet picks the best.
        int effectiveTarget = Math.min(target + _consecutiveEmergencies,
                                       target + MAX_EMERGENCY_BOOST);
        long now = _context.clock().now();
        long preBuildThreshold = now + 5 * 60 * 1000;

        int safeActive = 0;  // tunnels with > 5min remaining
        int nearExpiry = 0;  // tunnels with <= 5min remaining but not yet expired
        int expiredZombies = 0;  // tunnels past expiration still in pool
        int untestedCount = 0;  // tunnels awaiting first test — in pool, just unproven

        _tunnelsLock.lock();
        try {
            // Sweep zombie tunnels: expired tunnels still in the pool that
            // weren't cleaned up by ExpireJob (e.g. MAX_ENTRY_LIFETIME eviction
            // removed them from the expiry map but not from the pool).  These
            // zombies inflate the pool count and block replacement builds.
            Iterator<TunnelInfo> it = _tunnels.iterator();
            while (it.hasNext()) {
                TunnelInfo t = it.next();
                if (t.getExpiration() <= now) {
                    it.remove();
                    if (t instanceof PooledTunnelCreatorConfig) {
                        ExpireJob.removeFromExpiration((PooledTunnelCreatorConfig) t);
                    }
                    expiredZombies++;
                    continue;
                }
                // Count UNTESTED — they're in the pool awaiting test.
                if (t.getTestStatus() == net.i2p.router.TunnelTestStatus.UNTESTED) {
                    untestedCount++;
                    continue;
                }
                // Skip FAILING/FAILED tunnels — they can't route traffic.
                if (t.getTunnelFailed() || t.getTestStatus() == net.i2p.router.TunnelTestStatus.FAILING) continue;
                if (t.getExpiration() > preBuildThreshold) {
                    safeActive++;
                } else {
                    nearExpiry++;
                }
            }
        } finally { _tunnelsLock.unlock(); }

        if (expiredZombies > 0 && _log.shouldWarn()) {
            _log.warn(toString() + " -> Cleaned up " + expiredZombies + " expired zombie tunnels from pool");
        }

        // Proactive pre-building: if safeActive is below target AND tunnels
        // are expiring within 5 min, build replacements NOW so they're ready
        // before the old tunnels expire.  This prevents synchronized expiry
        // cascades where all tunnels expire at once, leaving the pool empty
        // while new builds queue (inProgress blocks EMERGENCY).
        // Without this, every boot-time tunnel batch expires together at
        // ~10 min intervals, and pending-but-timing-out builds (20s each)
        // keep the inProgress counter above zero, starving the pool.
        int inProgress = getInProgressCount();

        // Reset collapse counter when pool is stable (safeActive >= effectiveTarget).
        // Slow decay: also reduce by 1 each cycle when safeActive >= base target,
        // so pools gradually return to normal even without full stability.
        // Force reset when pool is empty (safeActive == 0) — keeping the counter
        // high inflates effectiveTarget, causing the pool to hoard broken tunnels
        // as LS fallback instead of recovering.
        if (safeActive >= effectiveTarget) {
            _consecutiveEmergencies = 0;
        } else if (safeActive == 0) {
            _consecutiveEmergencies = 0;
        } else if (safeActive >= target && _consecutiveEmergencies > 0) {
            _consecutiveEmergencies--;
        }

        // Count UNTESTED tunnels — they're in the pool and just need time
        // to be tested.  Don't count them as deficit UNLESS the pool has
        // zero GOOD tunnels, in which case they're likely stuck failing
        // tests and blocking replacement builds.
        if (safeActive < effectiveTarget && (nearExpiry > 0 || safeActive == 0)) {
            int deficit;
            if (safeActive == 0 && nearExpiry > 0 && inProgress > 0) {
                // When safeActive is 0 and tunnels are expiring, in-progress
                // builds haven't produced GOOD tunnels yet (~40s build+test).
                // The expiring tunnels will die before in-progress builds
                // become usable, leaving the pool at 0 safe forever.
                // However, don't ignore inProgress entirely — queuing more
                // builds when inProgress >= effectiveTarget creates a build storm:
                // timeout → ensureSufficientTunnels → deficit=target → build
                // more → timeout → repeat.  Only build the gap.
                deficit = Math.max(0, effectiveTarget - inProgress);
            } else if (safeActive == 0) {
                // Pool has zero GOOD tunnels — UNTESTED tunnels are likely
                // stuck failing tests and blocking new builds.  Don't count
                // them toward the deficit so replacement builds proceed.
                deficit = effectiveTarget - inProgress;
            } else {
                deficit = effectiveTarget - safeActive - inProgress - untestedCount;
            }
            if (deficit > 0) {
                // Cap per-cycle builds at base target — scale up gradually
                int needed = Math.min(deficit, target);
                // Respect pool backoff to prevent build storms.
                // When tunnels are expiring (nearExpiry > 0), still allow
                // builds if the pool is truly collapsed (safeActive == 0)
                // but otherwise respect the backoff to avoid churning.
                boolean collapsed = safeActive == 0 && nearExpiry > 0;
                if (_manager.getExecutor().isPoolInBackoff(this) && !collapsed) {
                    if (_log.shouldDebug()) {
                        _log.debug(toString() + " -> Skipping " + needed +
                                  " proactive builds, pool in backoff");
                    }
                } else {
                    if (_log.shouldInfo()) {
                        String boost = _consecutiveEmergencies > 0 ?
                            " [boosted +" + _consecutiveEmergencies + "]" : "";
                        _log.info(toString() + " -> Proactive: " + safeActive +
                                  " safe + " + nearExpiry + " expiring, building " + needed +
                                  " replacements (deficit=" + deficit + ", ip=" + inProgress + ")" + boost);
                    }
                    for (int i = 0; i < needed; i++) {
                        PooledTunnelCreatorConfig cfg = configureNewTunnel(false);
                        if (cfg != null) {
                            _manager.getExecutor().buildTunnel(cfg);
                        }
                    }
                }
            }
        }

        // EMERGENCY: only when zero usable tunnels remain.  The inProgress
        // check is intentionally removed — when all pending builds timeout,
        // inProgress goes to 0 but the pool has been empty for 20s+ already.
        // EMERGENCY always proceeds regardless of pool backoff — a dead pool
        // must rebuild immediately; backoff is meant to prevent build storms
        // on struggling pools, not block recovery from total collapse.
        boolean isPing = _settings.getDestinationNickname() != null &&
                         _settings.getDestinationNickname().startsWith("Ping");
        // Count UNTESTED tunnels — the pool has capacity, just waiting for
        // test results.  Firing EMERGENCY here creates a death spiral:
        // build → UNTESTED → test queue saturated → can't test → EMERGENCY →
        // build more → Too many UNTESTED → prune untested → pool drops → repeat.
        if (safeActive == 0 && nearExpiry == 0 && untestedCount == 0 && !isPing) {
            // Dynamic scaling: boost target on repeated collapses.
            _consecutiveEmergencies = Math.min(_consecutiveEmergencies + 1,
                                               MAX_EMERGENCY_BOOST);
            effectiveTarget = Math.min(target + _consecutiveEmergencies,
                                       target + MAX_EMERGENCY_BOOST);
            // Cap per-cycle builds at base target — pool scales up gradually
            // over multiple EMERGENCY cycles, not all at once.  Flooding the
            // build queue with 5+ concurrent builds wastes build slots that
            // other pools need, and most will timeout anyway.
            int needed = Math.max(target, 2);
            // If builds are already queued, don't stack more — let the
            // existing builds resolve first.  Use base target for the cap,
            // not effectiveTarget — we don't need all slots filled instantly.
            if (inProgress >= target) {
                if (_log.shouldDebug()) {
                    _log.debug(toString() + " -> Skipping EMERGENCY: " +
                              inProgress + " in-progress >= target " + target);
                }
                return;
            }
            if (inProgress > 0) { needed = Math.max(1, target - inProgress); }
            // IB/OB balance: don't emergency-build if this direction already has
            // MORE usable tunnels than its paired direction.  When both pools are at
            // zero usable, both must build — skipping both causes a deadlock where
            // neither pool ever recovers.  Use getUsableTunnelCount() (not
            // getTunnelCount()) so zombie tunnels with hundreds of failures
            // don't block recovery.
            TunnelPool paired = _pairedPool;
            if (paired != null) {
                int pairedUsable = paired.getUsableTunnelCount();
                int thisUsable = safeActive + nearExpiry;
                if (thisUsable > pairedUsable) {
                    if (_log.shouldInfo()) {
                        _log.info(toString() + " -> Skipping EMERGENCY: " +
                                  thisUsable + " usable vs " +
                                  pairedUsable + " usable in paired pool, letting pair catch up");
                    }
                    return;
                }
            }
            if (_log.shouldWarn()) {
                String boost = _consecutiveEmergencies > 0 ?
                    " (dynamic target " + effectiveTarget + ", collapse #" + _consecutiveEmergencies + ")" : "";
                _log.warn(toString() + " -> EMERGENCY: Zero usable tunnels, " +
                          inProgress + " in-progress, forcing " + needed +
                          " replacement builds" + boost);
            }
            for (int i = 0; i < needed; i++) {
                PooledTunnelCreatorConfig cfg = configureNewTunnel(false);
                if (cfg != null) {
                    _manager.getExecutor().buildTunnel(cfg);
                }
            }
        }
        } finally { _ensuringTunnels.set(false); }
    }

    /**
     *  This only sets the peers and creation/expiration times in the configuration.
     *  For the crypto, see BuildRequestor and BuildMessageGenerator.
     *
     *  @param forceZeroHop if true, force a zero-hop tunnel
     *  @return the configured tunnel, or null on failure
     */
    private PooledTunnelCreatorConfig configureNewTunnel(boolean forceZeroHop) {
        TunnelPoolSettings settings = getSettings();
        // Peers for new tunnel, including us, ENDPOINT FIRST
        List<Hash> peers = null;
        long now = _context.clock().now();
        long expiration = now + TunnelPoolSettings.DEFAULT_DURATION;
        // Stagger 0-300s (5 min) to prevent all tunnels expiring simultaneously.
        // With a 10-min lifetime, 120s stagger only spread expirations over a
        // 2-minute window.  When 11+ pools all build at boot, their IB tunnels
        // all expire at ~10 min, causing ExpireJob.phase1 to remove them all in
        // one batch → mass EMERGENCY triggers → build storm → death spiral.
        // 300s stagger spreads expirations over 5 minutes, giving builds time to
        // complete before the next pool's tunnels expire.
        // NOTE: Capped at 300s because NetDb rejects LeaseSets expiring >15 min
        // in the future (MAX_LEASE_FUTURE).  With DEFAULT_DURATION=10 min,
        // stagger must stay under 5 min to avoid "Future LeaseSet" errors.
        int stagger = _context.random().nextInt(300001);
        expiration += stagger;

        if (!forceZeroHop) {
            int len = settings.getLengthOverride();
            if (len < 0) {len = settings.getLength();}
            if (len > 0 && (!settings.isExploratory()) && _context.random().nextInt(4) < 3) { // 75%
                // Look for a tunnel to reuse, if the right length and expiring soon.
                // Ignore variance for now.
                // Skip tunnels whose peers are on cooldown to ensure diversity.
                len++; // us
                _tunnelsLock.lock();
                try {
                    long cooldownCutoff = now - TunnelPeerSelector.PEER_SELECTION_COOLDOWN_MS;
                    for (TunnelInfo ti : _tunnels) {
                        if (ti.getLength() >= len && ti.getExpiration() < now + 3*60*1000 && !ti.wasReused()) {
                            ti.setReused();
                            len = ti.getLength();
                            peers = new ArrayList<Hash>(len);
                            // Peers list is ordered endpoint first, but cfg.getPeer() is ordered gateway first
                            for (int i = len - 1; i >= 0; i--) {peers.add(ti.getPeer(i));}
                            // Skip reuse if any non-self peer is on cooldown
                            boolean anyInCooldown = false;
                            for (Hash p : peers) {
                                if (p.equals(_context.routerHash())) continue;
                                Long lastSel = TunnelPeerSelector._peerCooldowns.get(p);
                                if (lastSel != null && lastSel > cooldownCutoff) {
                                    anyInCooldown = true;
                                    break;
                                }
                            }
                            if (anyInCooldown) {
                                peers = null;
                                continue;
                            }
                            // Record cooldown for reused peers so selectPeers respects them
                            for (Hash p : peers) {
                                if (!p.equals(_context.routerHash()) &&
                                    !TunnelPeerSelector.hasRecoveredFromFailure(_context, p)) {
                                    TunnelPeerSelector._peerCooldowns.put(p, now);
                                }
                            }
                            break;
                        }
                    }
                } finally {_tunnelsLock.unlock();}
            }
            if (peers == null) {
                setLengthOverride();
                peers = _peerSelector.selectPeers(settings);
            }

            if ((peers == null) || (peers.isEmpty())) {
                long uptime = _context.router().getUptime();
                if (_log.shouldWarn() && uptime > 3*60*1000) {
                    String nick = settings.getDestinationNickname();
                    Hash dest = settings.getDestination();
                    _log.warn("TPool cfgNewTunnel: selectPeers returned " + (peers == null ? "null" : "empty") +
                              " for " + (nick != null ? nick : dest != null ? dest.toBase32() : "null") +
                              " (" + (settings.isInbound() ? "in" : "out") + ")");
                }
                return null;
            }
        } else {peers = Collections.singletonList(_context.routerHash());}

        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_context, peers.size(),
                                                settings.isInbound(), settings.getDestination(),
                                                this);
        // Peers list is ordered endpoint first, but cfg.getPeer() is ordered gateway first
        for (int i = 0; i < peers.size(); i++) {
            int j = peers.size() - 1 - i;
            cfg.setPeer(j, peers.get(i));
            HopConfig hop = cfg.getConfig(j);
            hop.setCreation(now);
            hop.setExpiration(expiration);
            // IV and Layer key now set in BuildRequestor.createTunnelBuildMessage() tunnelIds will be
            // updated during building, and as the creator, we don't need to worry about prev/next hop
        }
        // Note that this will be adjusted by expire job
        cfg.setExpiration(expiration);
        if (!settings.isInbound()) {cfg.setPriority(settings.getPriority());}

        // Fast-fail: for outbound tunnels, check if the TBR target (cfg.getPeer(1))
        // is reachable.  The TBR is sent directly to this peer via transport.
        // If it's not connected and not connecting, skip this cycle and start
        // pre-connecting so the transport has time to establish before the
        // next build attempt.  handleOutboundBuild() gives connecting peers
        // a 12s timeout, which covers the typical ~8.5s SSU2 handshake.
        if (!settings.isInbound() && cfg.getLength() > 1) {
            Hash tbrTarget = cfg.getPeer(1);
            if (tbrTarget != null && !tbrTarget.equals(_context.routerHash()) &&
                !_context.commSystem().isEstablished(tbrTarget) &&
                !_context.commSystem().isConnecting(tbrTarget)) {
                if (_log.shouldInfo()) {
                    _log.info("configureNewTunnel: TBR target [" + tbrTarget.toBase64().substring(0,6) +
                              "] not connected, pre-connecting for next attempt \n* " + cfg);
                }
                TunnelPeerSelector.preConnectTo(_context, tbrTarget);
                return null;
            }
        }

        if (_log.shouldDebug()) {
            StringBuilder sb = new StringBuilder("Tunnel created for pool: " + cfg);
            if (peers != null) {
                sb.append("\n* Peers: ");
                for (int i = 0; i < peers.size(); i++) {
                    if (i > 0) sb.append("[").append(peers.get(i).toBase64().substring(0,6)).append("] ");
                }
            }
            _log.debug(sb.toString());
        }
        synchronized (_inProgress) {_inProgress.add(cfg);}
        return cfg;
    }

    /**
     *  Remove from the _inprogress list and call addTunnel() if result is SUCCESS.
     *  Updates consecutive build timeout count.
     *
     *  @param cfg the completed tunnel configuration
     *  @param result the build result
     *  @since 0.9.53 added result parameter
     */
    void buildComplete(PooledTunnelCreatorConfig cfg, BuildExecutor.Result result) {
        if (cfg.getTunnelPool() != this) {
            _log.error("Tunnel created for wrong pool: " + cfg + "-> Should have been for: " + this, new Exception());
            return;
        }

        synchronized (_inProgress) {_inProgress.remove(cfg);}

        // Record peer cooldown on build failure so bad OBEPs/IBGWs aren't retried
        if (result != BuildExecutor.Result.SUCCESS && cfg.getLength() > 1) {
            Hash farEnd = cfg.getFarEnd();
            if (farEnd != null && !farEnd.equals(_context.routerHash()) &&
                !TunnelPeerSelector.hasRecoveredFromFailure(_context, farEnd)) {
                TunnelPeerSelector._peerCooldowns.put(farEnd, _context.clock().now());
            }
        }

        switch (result) {
            case SUCCESS:
                _consecutiveBuildTimeouts.set(0);
                addTunnel(cfg);
                updatePairedProfile(cfg, true);
                break;

            case REJECT:
            case BAD_RESPONSE:
            case DUP_ID:
                // Peer responded but couldn't build tunnel — reset timeout counter
                _consecutiveBuildTimeouts.set(0);
                updatePairedProfile(cfg, true);
                break;

            case TIMEOUT:
                _consecutiveBuildTimeouts.incrementAndGet();
                updatePairedProfile(cfg, false);
                break;

            case OTHER_FAILURE:
                // Not a real failure (e.g., fallback skipping) — don't penalize
                updatePairedProfile(cfg, false);
                break;

            default:
                break;
        }
    }

    /**
     *  Get the count of consecutive tunnel build timeouts.
     *  @return the number of consecutive build timeouts
     *  @since 0.9.53
     */
    int getConsecutiveBuildTimeouts() {return _consecutiveBuildTimeouts.get();}

    /**
     *  Increment consecutive build timeout counter.
     *  Called by BuildExecutor for first-hop failures (OTHER_FAILURE with buildTime >= 1000ms)
     *  so the adaptive quantity throttling in getAdjustedTotalQuantity() can respond.
     *  @since 0.9.68
     */
    void incrementBuildTimeout() {_consecutiveBuildTimeouts.incrementAndGet();}

    /**
     *  Reset consecutive timeout counter when tunnels are working properly.
     *  This prevents excessive backoff on firewalled routers after recovery.
     *  Only resets if we have a significant number of consecutive timeouts
     *  to avoid flapping during normal operation.
     *  @since 0.9.53
     */
    private void resetConsecutiveTimeoutsOnSuccess() {
        int current = _consecutiveBuildTimeouts.get();
        // Only reset if we have accumulated significant timeouts (>= 8)
        // to avoid counter resets during normal operation
        if (current >= 8) {
            _consecutiveBuildTimeouts.set(0);
            if (_log.shouldInfo()) {
                _log.info("Resetting consecutive timeout counter after successful tunnel selection on " + this +
                          " (was " + current + ")");
            }
        }
    }

    /**
     *  Update the paired tunnel profiles by treating the build as a tunnel test
     *
     *  @param cfg the build for this tunnel, to lookup the paired tunnel
     *  @param success did the paired tunnel pass the message through
     *  @since 0.9.53
     */
    private void updatePairedProfile(PooledTunnelCreatorConfig cfg, boolean success) {
       // Will be null if paired tunnel is 0-hop
       TunnelId pairedGW = cfg.getPairedGW();
       if (pairedGW == null) {return;}
       if (!success) {
           // Don't blame the paired tunnel for exploratory build failures
           if (_settings.isExploratory()) {return;}
           // Don't blame the paired tunnel if there might be some other problem
           if (getConsecutiveBuildTimeouts() > 3) {return;}
       }
       TunnelPool pool;
       PooledTunnelCreatorConfig paired = null;
       if (!_settings.isExploratory()) {
           Hash dest = _settings.getDestination();
           if (_settings.isInbound()) {pool = _manager.getOutboundPool(dest);}
           else {pool = _manager.getInboundPool(dest);}
           if (pool != null) {paired = (PooledTunnelCreatorConfig) pool.getTunnel(pairedGW);}
       }
        if (paired == null) { // Not found or exploratory
            if (_settings.isInbound()) {pool = _manager.getOutboundExploratoryPool();}
            else {pool = _manager.getInboundExploratoryPool();}
            if (pool != null) {paired = (PooledTunnelCreatorConfig) pool.getTunnel(pairedGW);}
        }
         if (paired != null && paired.getLength() > 1) {
             if (success) {
                 // Seed UNTESTED paired tunnels as GOOD on build success so
                 // the pool has at least one usable tunnel. Once tested
                 // (GOOD/FAILING), build RTT doesn't overwrite real results.
                 if (paired.getTestStatus() == net.i2p.router.TunnelTestStatus.UNTESTED) {
                     long requestedOn = cfg.getExpiration() - getTunnelLifetime(_context);
                     int rtt = (int) (_context.clock().now() - requestedOn);
                     if (rtt > 0) {
                         paired.testSuccessful(rtt);
                     }
                 }
             }
             // On failure: don't touch the paired tunnel's test status.
             // A build failure in this direction doesn't indicate the paired
             // tunnel is broken — the failure was in the new tunnel's path.
         }
    }

    /**
     * Suppress timeout warning spam when we have adequate tunnels
     * Enhanced to be much more aggressive about suppression to eliminate log spam
     */
    private boolean shouldSuppressTimeoutWarning() {
        long uptime = _context.router().getUptime();
        if (uptime < getStartupTime(_context)) {
            return true; // Suppress early warnings
        }

        // For client tunnels, check if we have adequate tunnels in both directions
        if (!_settings.isExploratory()) {
            Hash dest = _settings.getDestination();
            TunnelPool inboundPool = _manager.getInboundPool(dest);
            TunnelPool outboundPool = _manager.getOutboundPool(dest);

            // Enhanced check: count usable tunnels (non-expired, appropriate length)
            int usableInbound = countUsableTunnels(inboundPool);
            int usableOutbound = countUsableTunnels(outboundPool);

            // Also check if we have working tunnels (recent successful builds)
            boolean hasRecentSuccess = hasRecentSuccessfulBuilds();

            if (_log.shouldDebug()) {
                _log.debug("Enhanced tunnel availability check for " + this + "\n* Usable inbound:" + usableInbound +
                          " Usable outbound: " + usableOutbound + " Recent success: " + hasRecentSuccess +
                          " Failures: " + _consecutiveBuildTimeouts.get());
            }

            // Check if the current pool has usable tunnels OR if the opposite direction has adequate tunnels
            int currentUsable = (_settings.isInbound() ? usableInbound : usableOutbound);
            int oppositeUsable = (_settings.isInbound() ? usableOutbound : usableInbound);

            // More aggressive suppression: if we have ANY usable tunnels in current direction,
            // OR 2+ in opposite direction, OR recent success, suppress warnings
            // This prevents log spam when one direction struggles but overall connectivity is fine
            if (currentUsable >= 1 || oppositeUsable >= 2 || hasRecentSuccess) {
                return true;
            }

            // Additional suppression: if uptime > 1h and we have some tunnels (even if not many), suppress
            // to avoid chronic warnings during network stress
            if (uptime > 60*60*1000 && (currentUsable >= 1 || oppositeUsable >= 1)) {
                return true;
            }
        }

        // For exploratory tunnels, enhanced check with usable tunnel count
        if (_settings.isExploratory()) {
            int usable = countUsableTunnels(this);
            boolean hasRecentSuccess = hasRecentSuccessfulBuilds();

            if (_log.shouldDebug()) {
                _log.debug("Enhanced exploratory tunnel availability check for " + this + "\n*  Usable: " + usable +
                          " Recent success: " + hasRecentSuccess + " Failures: " + _consecutiveBuildTimeouts.get());
            }

            // Much more aggressive suppression: if we have ANY usable tunnels OR recent success, suppress
            if (usable >= 1 || hasRecentSuccess) {
                return true;
            }
        }

        long now = System.currentTimeMillis();
        // Very aggressive suppression: minimum 15 minutes, scaling with failures, max 60 minutes
        int failures = _consecutiveBuildTimeouts.get();
        long suppressionPeriod = Math.min(15*60*1000 + (failures * 2*60*1000), 60*60*1000); // Max 60 minutes

        if (now - _lastTimeoutWarningTime < suppressionPeriod) {
            return true; // Suppress repeated warnings
        }

        _lastTimeoutWarningTime = now;
        return false;
    }

    /**
     * Check if the destination is reachable by looking up its LeaseSet
     */
    private boolean isDestinationReachable() {
        if (_settings.isExploratory()) {
            return true;
        }

        // For inbound server pools: the pool IS the source of the LeaseSet.
        // If we're alive and have tunnels (or builds in progress), the destination
        // is reachable even if the signed LS hasn't propagated to the local netDB
        // yet (the client app signs it asynchronously). Don't check netDB — that
        // would create a window where the destination appears unreachable between
        // LS build and client signing. Also count in-progress builds so the
        // destination never appears unreachable during the brief gap between
        // natural tunnel expiry and replacement build completion.
        if (_settings.isInbound()) {
            return _alive && (size() > 0 || getInProgressCount() > 0);
        }

        // For outbound pools to local destinations: the LS is published by our
        // own inbound pool. Same async signing issue applies. If the destination
        // runs on this router, it's reachable.
        if (_context.clientManager().isLocal(_settings.getDestination())) {
            return true;
        }

        Hash destHash = _settings.getDestination().calculateHash();
        boolean hasLeaseSet = _context.netDb().lookupLeaseSetLocally(destHash) != null;

        if (!hasLeaseSet && _log.shouldDebug()) {
            _log.debug("Destination " + toString() + " has no LeaseSet in local network DB");
        }

        return hasLeaseSet;
    }

    /**
     * Suppress "no tunnels available" warning spam with rate limiting
     * Uses adaptive suppression between 5 and 10 minutes based on failures
     */
    private boolean shouldLogNoTunnelsWarning() {
        long uptime = _context.router().getUptime();
        if (uptime < getStartupTime(_context)) {
            return false;
        }

        long now = System.currentTimeMillis();
        int failures = _consecutiveBuildTimeouts.get();
        long suppressionPeriod = Math.min(5*60*1000 + (failures * 30*1000), 10*60*1000);

        if (now - _lastNoTunnelsWarningTime < suppressionPeriod) {
            return false;
        }

        _lastNoTunnelsWarningTime = now;
        return true;
    }

    /**
     * Check if this pool has had recent successful tunnel builds
     * Enhanced to be more sensitive and detect working tunnels better
     * @return true if there were successful builds in the last 10 minutes OR any usable tunnels exist
     */
    private boolean hasRecentSuccessfulBuilds() {
        // First check if we have any usable tunnels right now - this is the most reliable indicator
        int usableTunnels = countUsableTunnels(this);
        if (usableTunnels > 0) {
            return true; // We have working tunnels now
        }

        // Check rate stats as secondary indicator
        String rateName = buildRateName();
        RateStat rs = _context.statManager().getRate(rateName);
        if (rs == null) {
            return false;
        }

        // Check longer time window (10 minutes) to be more forgiving
        Rate r = rs.getRate(RateConstants.TEN_MINUTES); // Last 10 minutes
        if (r == null) {
            return false;
        }

        // Check if we have any successful builds (average > 0 indicates activity)
        // Also check total events to catch any successful builds
        return r.getAverageValue() > 0 || r.getLastEventCount() > 0;
    }

    /**
     * Count usable tunnels in a pool (non-zero-hop unless pool allows zero-hop)
     */
    private int countUsableTunnels(TunnelPool pool) {
        if (pool == null) return 0;

        long now = _context.clock().now();
        boolean allowZeroHop = pool.getSettings().getAllowZeroHop();
        int usable = 0;

        List<TunnelInfo> tunnels = pool.listTunnels();
        for (TunnelInfo info : tunnels) {
            if (info.getExpiration() > now) {
                if (allowZeroHop || info.getLength() > 1) {
                    usable++;
                }
            }
        }
        return usable;
    }

    /**
     * Determine if we should perform detailed failure analysis
     * Only analyze every Nth failure to avoid excessive processing
     */
    private boolean shouldAnalyzeFailure(TunnelInfo cfg) {
        int failures = _consecutiveBuildTimeouts.get();
        // Analyze every 3rd failure, or always for high failure counts
        return (failures % 3 == 0) || (failures > 10);
    }

    /**
     * Analyze tunnel failure patterns to identify root causes
     * and suggest recovery strategies
     */
    private void analyzeFailurePattern(TunnelInfo cfg) {
        if (!_log.shouldInfo()) return;

        int failures = _consecutiveBuildTimeouts.get();
        long uptime = _context.router().getUptime();

        // Check for common failure patterns
        if (failures > 8 && uptime > 10*60*1000) {
            _log.info("High tunnel failure rate detected for " + toString() +
                      ": " + failures + " consecutive failures. Consider checking network connectivity and peer selection.");

            // Suggest configuration adjustments
            if (getSettings().getLength() > 3) {
                _log.info("Consider reducing tunnel length for " + toString() +
                          " from " + getSettings().getLength() + " to improve reliability.");
            }
        }

        // Check for specific peer issues
        Set<Hash> failedPeers = new java.util.HashSet<>();
        for (int i = 0; i < cfg.getLength(); i++) {
            failedPeers.add(cfg.getPeer(i));
        }

        // Check if same peers appear in multiple failures
        if (failedPeers.size() < cfg.getLength() / 2) {
            _log.info("Repeated peer failures detected in " + toString() +
                      ". Consider reviewing peer selection criteria.");
        }
    }

    @Override
    public String toString() {
        if (_settings.isExploratory()) {
            if (_settings.isInbound()) {return "Inbound Exploratory Pool";}
            else {return "Outbound Exploratory Pool";}
        } else {
            StringBuilder rv = new StringBuilder(32);
            if (_settings.isInbound()) {rv.append("Inbound Client Pool ");}
            else {rv.append("Outbound Client Pool ");}
            if (_settings.getDestinationNickname() != null) {
                rv.append("[").append(_settings.getDestinationNickname()).append("]");
            } else {
                Hash d = _settings.getDestination();
                rv.append("[").append(d != null ? d.toBase32().substring(0,8) : "null").append("]");
            }
            return rv.toString();
        }
    }

    /**
     * Format a pool identity for log messages, combining nickname and truncated hash.
     * Format: "nickname/hash" if nickname set, or just "hash" if not.
     * Globally available helper for consistent log formatting across all tunnel pool code.
     *
     * @param settings the pool settings
     * @return formatted identity string
     * @since 0.9.70+
     */
    public static String formatPoolIdentity(TunnelPoolSettings settings) {
        String nickname = settings.getDestinationNickname();
        Hash dest = settings.getDestination();
        if (nickname != null) {
            return nickname + " / " + dest.toBase32().substring(0, 8);
        }
        return dest.toBase32().substring(0, 8);
    }

}
