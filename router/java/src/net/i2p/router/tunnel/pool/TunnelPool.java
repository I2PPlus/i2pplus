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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.stat.RateConstants;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.Tuner;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelTestStatus;
import net.i2p.router.peermanager.PeerTestJob;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 *  A group of tunnels for the router or a particular client, in a single direction.
 *  Public only for TunnelRenderer in router console.
 */
public class TunnelPool {
    private static final Comparator<TunnelInfo> EXPIRATION_COMPARATOR =
            Comparator.comparingLong(TunnelInfo::getExpiration);
    /**
     *  Best tunnels first for LeaseSet publication: freshest (longest remaining
     *  life) so the lease survives floodfill propagation, then lowest average
     *  latency, then fewest consecutive failures.
     */
    static final Comparator<TunnelInfo> QUALITY_COMPARATOR =
            (a, b) -> {
                int cmp = Long.compare(b.getExpiration(), a.getExpiration());
                if (cmp != 0) {return cmp;}
                cmp = compareAvgLatency(a, b);
                if (cmp != 0) {return cmp;}
                return Integer.compare(a.getConsecutiveFailures(), b.getConsecutiveFailures());
            };

    /** Latency ascending, tunnels with no measurements sort last. */
    private static int compareAvgLatency(TunnelInfo a, TunnelInfo b) {
        int la = getTunnelAvgLatency(a);
        int lb = getTunnelAvgLatency(b);
        if (la < 0 && lb < 0) {return 0;}
        if (la < 0) {return 1;}
        if (lb < 0) {return -1;}
        return Integer.compare(la, lb);
    }
    private final List<PooledTunnelCreatorConfig> _inProgress = new ArrayList<>();
    /** The router context */
    protected final RouterContext _context;
    /** The log */
    protected final Log _log;
    private TunnelPoolSettings _settings;
    private final List<TunnelInfo> _tunnels;
    private final ReentrantLock _tunnelsLock = new ReentrantLock();
    private final TunnelPeerSelector _peerSelector;
    private final TunnelPoolManager _manager;
    private TunnelPool _pairedPool;  // Inbound or outbound pool for same destination
    /** Whether this pool is running */
    protected volatile boolean _alive;
    private long _lifetimeProcessed;
    private long _started;
    private long _lastRateUpdate;
    private long _lastLifetimeProcessed;
    private final String _rateName;
    private final long _firstInstalled;
    private final AtomicInteger _consecutiveBuildTimeouts = new AtomicInteger();
    /** Cached rate-stat handles so per-build lookups skip the StatManager map. */
    private final RateStat[] _expireStatSlot = new RateStat[1];
    private final RateStat[] _rejectStatSlot = new RateStat[1];
    private final RateStat[] _successStatSlot = new RateStat[1];
    private final RateStat[] _buildSuccessRateStatSlot = new RateStat[1];
    private final RateStat[] _avgBWStatSlot = new RateStat[1];
    private long _lastNoTunnelsWarningTime;
    private long _lastLastResortLogTime;
    /**
     *  Dynamic pool scaling: when a pool repeatedly collapses (EMERGENCY fires),
     *  increase the effective tunnel count. More parallel tunnels = more
     *  resilience against individual tunnel failures. LeaseSet publication
     *  picks the best tunnels, so excess is fine.
     */
    private volatile int _consecutiveEmergencies = 0;
    private static final int MAX_EMERGENCY_BOOST = 10;
    /**
     *  Extra tunnels maintained while the pool is struggling, so LeaseSet
     *  publication always has fresh candidates instead of re-signing the
     *  same aging leases.
     */
    private static final int STRUGGLE_RESERVE = 2;
    /**
     *  A tunnel counts as in use when it has carried verified traffic within
     *  this window.  Generous on purpose: streaming retransmit chains can
     *  stall for tens of seconds (observed RTOs of 12-15s and one ACK after
     *  151s), and tearing down the tunnel under a live stream is what turns
     *  a slow network into a dead download.
     */
    private static final long IN_USE_TRAFFIC_MS = 5 * 60 * 1000L;
    /**
     *  Minimum interval between pre-build triggers per pool.
     *  pruneExcessTunnels() fires this on every ~15s cycle for every IB pool
     *  whose OB pair is depleted.  Without throttling, this generates ~32
     *  unnecessary BuildExecutor wakeups per minute, each queuing builds into
     *  an already-congested transport pipeline.  The build loop's
     *  calculatePairedBuilds() already handles OB pool depletion, so
     *  the pre-build trigger is redundant — throttle it to once per 60s.
     *  @since 0.9.70
     */
    private long _lastPreBuildTime;
    private static final long PRE_BUILD_THROTTLE_MS = 60_000;
    private volatile long _lastEmergencyBuildTime;
    private static final long EMERGENCY_COOLDOWN_MS = 30_000;
    private volatile boolean _leaseSetRepublishPending;
    private static final int REMOVAL_QUEUE_CAPACITY = 2000;
    private final BlockingQueue<TunnelInfo> _removalQueue = new LinkedBlockingQueue<>(REMOVAL_QUEUE_CAPACITY);
    /**
     *  Reentrancy guard for ensureSufficientTunnels().
     *  pruneNonGoodTunnels() calls removeTunnel() per-tunnel, and removeTunnel()
     *  calls ensureSufficientTunnels() — creating recursive build storms that
     *  inflate _inProgress and trigger 800+ "Cancelling excess" per session.
     */
    private final AtomicBoolean _ensuringTunnels = new AtomicBoolean(false);

    /**
     *  Last time ensureSufficientTunnels() started a batch of builds.
     *  Prevents build storms when concurrent events (prune, expire, removal)
     *  all trigger ensureSufficientTunnels() within milliseconds.  Builds take
     *  10-40s; starting duplicate batches within 5s wastes capacity and fills
     *  the pool with UNTESTED tunnels that block the addTunnel cap.
     */
    private volatile long _lastDeficitBuildTime;

    /** Default early expiration time for pruned tunnels (30 seconds) */
    static final long DEFAULT_PRUNE_EARLY_EXPIRY = 120L * 1000;
    private static final String PROP_PRUNE_EARLY_EXPIRY = "router.pruneEarlyExpiryDelay";
    /** Non-published tunnels with more remaining life than this are fresh — never pruned. */
    private static final long PRUNE_KEEP_IF_FRESH_MS = 9L * 60 * 1000;
    /** Non-published tunnels with less remaining life than this are pruned at publication. */
    private static final long PRUNE_NEAR_EXPIRY_MS = 5L * 60 * 1000;
    /** If less than one success in this many, reduce length (exploratory only) */
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_1 = 10;
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_2 = 12;
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_CLIENT_1 = 4;
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_CLIENT_2 = 5;
    /** Lease end is set this long before the tunnel expires, so peers re-fetch
     * the new LeaseSet while the gateway still routes instead of racing
     * tunnel death. */
    private static final long LEASE_SAFETY_MARGIN = 60L * 1000;
    /** Tunnels must have at least this much remaining life to be published:
     * the lease ends {@link #LEASE_SAFETY_MARGIN} before the tunnel expires
     * and the lease end is floored 60s out, so a tunnel closer to death than
     * this would produce a lease ending after the gateway stops routing. */
    private static final long LEASE_MIN_REMAINING_MS = 2L * 60 * 1000;

    /**
     * Early expiry time for pruned tunnels.
     * Reads from property router.tunnel.pruneEarlyExpiry, or uses default (30s).
     * @return early expiry time in milliseconds
     */
    long getPruneEarlyExpiry() {
        return _context.getProperty(PROP_PRUNE_EARLY_EXPIRY, DEFAULT_PRUNE_EARLY_EXPIRY);
    }

    /**
     *  Tunnel lifetime from config or default (11 minutes).
     *  Longer than stock so a pool holds overlapping tunnel generations,
     *  giving the LeaseSet re-mint more leases to choose from at any moment.
     *  Tunable via i2p.tunnel.lifetime (default: 660000).
     *
     *  @param ctx the router context
     *  @return the tunnel lifetime in milliseconds
     */
    public static int getTunnelLifetime(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.lifetime", 11 * 60 * 1000);
    }

    /**
     *  A lease counts toward a re-mint while it has at least this much
     *  remaining: three minutes covers the re-sign request, floodfill
     *  propagation, and initial connection establishment, and a tunnel closer
     *  to expiry than this is already inside the pool's proactive replacement
     *  window (BuildExecutor starts replacing GOOD tunnels 330s out), so
     *  counting it would overstate the pool's health.  Shrinks toward two
     *  minutes for very short lifetimes so short-lifetime configs aren't
     *  stuck deferring forever.
     *
     *  @param ctx the router context
     *  @return the lease viability window in ms
     */
    public static long getLeaseViabilityWindow(RouterContext ctx) {
        return Math.max(2L * 60 * 1000,
                        Math.min(3L * 60 * 1000,
                                 getTunnelLifetime(ctx) - 2L * 60 * 1000));
    }

    /**
     * Max concurrent builds per direction from config or default (6).
     * Tunable via i2p.tunnel.maxConcurrentBuildsPerDirection (default: 6).
     *
     * @param ctx the router context
     * @return the max concurrent builds per direction
     */
    static int getMaxConcurrentBuildsPerDirection(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.maxConcurrentBuildsPerDirection", 6);
    }

    /**
     * Startup suppression period from config or default (5 minutes).
     * Tunable via i2p.tunnel.startupTime (default: 300000).
     *
     * @param ctx the router context
     * @return the startup time in milliseconds
     */
    static long getStartupTime(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.startupTime", 5L * 60 * 1000);
    }

    /**
     * Quantity override threshold from config or default (12).
     * If less than one success in this many, reduce quantity.
     * Tunable via i2p.tunnel.buildTriesQuantityOverride (default: 12).
     *
     * @param ctx the router context
     * @return the quantity override threshold
     */
    static int getBuildTriesQuantityOverride(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.buildTriesQuantityOverride", 12);
    }

    /**
     * Refresh throttle interval from config or default (2 minutes).
     * Minimum interval between LeaseSet publishes, so a failed inbound
     * tunnel is dropped from the published LeaseSet promptly while staying
     * under the floodfill republish budget (~6 per destination per 10 min).
     * Tunable via i2p.tunnel.refreshThrottle (default: 120000).
     *
     * @param ctx the router context
     * @return the refresh throttle interval in milliseconds
     */
    static long getRefreshThrottle(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.refreshThrottle", 2L * 60 * 1000);
    }

    /**
     * LeaseSet build minimum interval from config or default (2 minutes).
     * Minimum interval between LeaseSet object rebuilds, kept at or below the
     * refresh throttle so a republish always reflects the current tunnel set
     * (including newly built tunnels and excluding failed ones).
     * Tunable via i2p.tunnel.leasesetBuildMinInterval (default: 120000).
     *
     * @param ctx the router context
     * @return the LeaseSet build minimum interval in milliseconds
     */
    static long getLeaseSetBuildMinInterval(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.leasesetBuildMinInterval", 2L * 60 * 1000);
    }

    /**
     * Maximum lease set lease duration from config or default (10 minutes).
     * Lease end dates in published LeaseSets are capped to this value from now,
     * so peers re-fetch our LeaseSet sooner than the 11-minute tunnel lifetime.
     * The tunnel itself continues to process messages; only the cached
     * LeaseSet on the requesting side expires earlier, triggering a re-fetch.
     *
     * The 5-minute republish cycle re-floods (or re-mints) well before a lease
     * dies: the re-mint floor re-signs any stored copy under 10 minutes, and
     * the reschedule anchors each check inside the 5-minute expiry window.
     * Set &quot;i2p.tunnel.leaseMaxDuration&quot; explicitly to override.
     *
     * @param ctx the router context
     * @return the maximum lease duration in milliseconds
     */
    static long getLeaseMaxDuration(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.leaseMaxDuration", 10L * 60 * 1000);
    }

    /** Tunnel pool */
    TunnelPool(RouterContext ctx, TunnelPoolManager mgr, TunnelPoolSettings settings, TunnelPeerSelector sel) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPool.class);
        _manager = mgr;
        _settings = settings;
        _tunnels = new ArrayList<>(settings.getTotalQuantity());
        _peerSelector = sel;
        _started = System.currentTimeMillis();
        _lastRefreshTime = -getRefreshThrottle(ctx);
        _lastRateUpdate = _started;
        _firstInstalled = ctx.getProperty("router.firstInstalled", 0L) + 60L * 60 * 1000;
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
                                         new long[] {60L * 60 * 1000});
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
     *  TunnelPoolManager that owns this pool.
     *  @return non-null
     *  @since 0.9.69+
     */
    public TunnelPoolManager getTunnelPoolManager() {return _manager;}

    /**
     *  Get a rate stat, caching the handle so per-build lookups skip the
     *  StatManager lookup.  Null results are not cached — the stat may be
     *  registered after the first use.
     *  @param slot the cached handle, null until first found
     *  @param name the stat name
     *  @return the stat, or null if not registered
     */
    private RateStat getRateStat(RateStat[] slot, String name) {
        RateStat rs = slot[0];
        if (rs == null) {
            rs = _context.statManager().getRate(name);
            slot[0] = rs;
        }
        return rs;
    }

    /**
     *  Average bandwidth per tunnel in the pool.
     *  @return average bandwidth per configured tunnel in Bps
     *  @since 0.9.66
     */
    public int getAvgBWPerTunnel() {
        RateStat stat = getRateStat(_avgBWStatSlot, _rateName);
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
        _tunnelsLock.lock();
        try {
            if (_tunnels.isEmpty()) {
               shouldWarn = _log.shouldWarn() && uptime > getStartupTime(_context) && shouldLogNoTunnelsWarning();
            } else {
                // Random start index for statistical load balancing
                int startIdx = _context.random().nextInt(_tunnels.size());
                TunnelInfo lastResortTunnel = null;
                TunnelInfo backloggedTunnel = null;
                // Skip last resort tunnels on first pass - only use if no other option
                if (avoidZeroHop) {
                    ScanResult pass1 = scanPoolForTunnel(startIdx, now, true, null);
                    if (pass1.chosen != null) {return pass1.chosen;}
                    if (pass1.backlogged != null) {
                        if (_log.shouldWarn()) {_log.warn(toString() + " -> All tunnels are backlogged");}
                        recordPooledActivity(pass1.backlogged);
                        return pass1.backlogged;
                    }
                    lastResortTunnel = pass1.lastResort;
                }
                ScanResult pass2 = scanPoolForTunnel(startIdx, now, false, lastResortTunnel);
                if (pass2.chosen != null) {return pass2.chosen;}
                lastResortTunnel = pass2.lastResort;
                backloggedTunnel = pass2.backlogged;
                // Fall back to last resort tunnel if nothing else available
                if (lastResortTunnel != null) {
                    if (_log.shouldWarn() && uptime > 120L * 1000 && shouldLogLastResortWarning()) {
                        _log.warn(toString() + " -> Using last resort tunnel as only option");
                    }
                    recordPooledActivity(lastResortTunnel);
                    return lastResortTunnel;
                }
                if (backloggedTunnel != null) {
                    recordPooledActivity(backloggedTunnel);
                    return backloggedTunnel;
                }
                TunnelInfo degraded = pickDegradedTunnel(now);
                if (degraded != null) {return degraded;}
            }
        } finally {_tunnelsLock.unlock();}

        if (shouldWarn) {
            logNoTunnelsWarning();
        }

        if (_alive) {buildFallback();}
        if (allowRecurseOnFail) {return selectTunnel(false);}
        else {return null;}
    }

    /**
     *  Result of a pool scan: the chosen tunnel (or null), the backlogged
     *  fallback candidate, and the recorded last-resort tunnel.
     */
    private static class ScanResult {
        final TunnelInfo chosen;
        final TunnelInfo backlogged;
        final TunnelInfo lastResort;
        ScanResult(TunnelInfo chosen, TunnelInfo backlogged, TunnelInfo lastResort) {
            this.chosen = chosen;
            this.backlogged = backlogged;
            this.lastResort = lastResort;
        }
    }

    /**
     *  Scan the pool from a random start index for a usable tunnel.  Skips
     *  last-resort tunnels (recording the first seen), failed tunnels, and
     *  tunnels over the max consecutive-failure cap — test failures are
     *  often reply-path problems, not tunnel quality, so let data prove the
     *  tunnel works.  A candidate is accepted when it is non-expired and
     *  its next peer is not backlogged (inbound pools skip the backlog
     *  check, and short tunnels are exempt); the first pass additionally
     *  requires length &gt; 1.  Backlogged candidates are recorded as a
     *  fallback.
     *
     *  @param longTunnelsOnly first-pass mode (zero-hop-avoiding pools): long tunnels only
     *  @param lastResortTunnel first-pass result, kept by the second pass if set
     *  @return the scan result
     */
    private ScanResult scanPoolForTunnel(int startIdx, long now, boolean longTunnelsOnly, TunnelInfo lastResortTunnel) {
        TunnelInfo backloggedTunnel = null;
        for (int i = 0; i < _tunnels.size(); i++) {
            int idx = (startIdx + i) % _tunnels.size();
            TunnelInfo info = _tunnels.get(idx);
            if (info instanceof PooledTunnelCreatorConfig && ((PooledTunnelCreatorConfig) info).isLastResort()) {
                if (lastResortTunnel == null || longTunnelsOnly) {lastResortTunnel = info;}
                continue;
            }
            if (info.getTunnelFailed()) {continue;}
            if (info.getConsecutiveFailures() > TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES) {continue;}
            if (info.getExpiration() <= now) {continue;}
            if (longTunnelsOnly && info.getLength() <= 1) {continue;}
            if (!_settings.isInbound() && info.getLength() > 1 &&
                _context.commSystem().isBacklogged(info.getPeer(1))) {
                backloggedTunnel = info;
                continue;
            }
            resetConsecutiveTimeoutsOnSuccess();
            recordPooledActivity(info);
            return new ScanResult(info, null, lastResortTunnel);
        }
        return new ScanResult(null, backloggedTunnel, lastResortTunnel);
    }

    /**
     *  Last-resort scan: accept any non-expired tunnel, even one over the
     *  consecutive-failure cap.  Retained tunnels are kept deliberately and
     *  are better than returning null (which causes "No tunnels available"
     *  and lost build replies) — applies to ALL pools, even exploratory,
     *  since a null return can disrupt exploration.
     *
     *  @return a non-expired tunnel, or null
     */
    private TunnelInfo pickDegradedTunnel(long now) {
        for (int i = 0; i < _tunnels.size(); i++) {
            TunnelInfo info = _tunnels.get(i);
            if (info.getExpiration() > now) {
                if (_log.shouldWarn()) {
                    _log.warn(toString() + " -> Using degraded tunnel (" +
                              info.getConsecutiveFailures() + " failures) as last resort");
                }
                recordPooledActivity(info);
                return info;
            }
        }
        return null;
    }

    /**
     *  Log the no-tunnels warning, distinguishing an unreachable destination
     *  (no LeaseSet found) from an empty pool.
     */
    private void logNoTunnelsWarning() {
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

    /** Record activity on a pooled tunnel (no-op for non-pooled). */
    private void recordPooledActivity(TunnelInfo info) {
        if (info instanceof PooledTunnelCreatorConfig) {
            ((PooledTunnelCreatorConfig) info).recordActivity();
        }
    }

    /**
     * Suppress last-resort tunnel warning spam with rate limiting.
     * @return whether log last resort warning
     * @since 0.9.69+
     */
    private boolean shouldLogLastResortWarning() {
        long now = System.currentTimeMillis();
        if (now - _lastLastResortLogTime < 60L * 1000) {return false;}
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
                rv = findClosestTunnel(closestTo, avoidZeroHop, now);
                // For non-exploratory pools: if nothing found, use any non-expired tunnel
                // even with high consecutive failures.
                if (rv == null && !_settings.isExploratory()) {
                    rv = pickAnyNonExpiredTunnel(now);
                }
            }
            if (rv == null && _log.shouldWarn() && uptime > getStartupTime(_context)) {
                shouldWarn = shouldLogNoTunnelsWarning();
            }
        } finally {_tunnelsLock.unlock();}
        if (rv != null) {
            _context.statManager().addRateData("tunnel.matchLease", closestTo.equals(rv.getFarEnd()) ? 1 : 0);
        } else if (shouldWarn) {
            logNoTunnelsWarning();
        }
        return rv;
    }

    /**
     *  Find the non-failed tunnel XOR-closest to the target, so OBEP-IBGW
     *  connections keep some locality network-wide.  Tunnels over the max
     *  consecutive-failure cap are skipped — test failures are often
     *  reply-path problems, not tunnel quality.
     *
     *  @return the closest tunnel, or null
     */
    private TunnelInfo findClosestTunnel(Hash closestTo, boolean avoidZeroHop, long now) {
        TunnelInfo rv = null;
        TunnelInfoComparator comparator = new TunnelInfoComparator(closestTo, avoidZeroHop);
        for (TunnelInfo info : _tunnels) {
            // Skip completely failed tunnels
            if (info.getTunnelFailed()) {continue;}
            if (info.getConsecutiveFailures() > TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES) {continue;}
            if (info.getExpiration() > now) {
                if (rv == null || comparator.compare(info, rv) < 0) {rv = info;}
            }
        }
        return rv;
    }

    /** @return any non-expired tunnel, or null */
    private TunnelInfo pickAnyNonExpiredTunnel(long now) {
        for (TunnelInfo info : _tunnels) {
            if (info.getExpiration() > now) {return info;}
        }
        return null;
    }

    /**
     *  Tunnel by its gateway tunnel ID.
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
     * List of tunnels currently in the pool.
     *
     * @return a copy of the list of TunnelInfo objects
     */
    public List<TunnelInfo> listTunnels() {
        _tunnelsLock.lock();
        try {return new ArrayList<>(_tunnels);} finally {_tunnelsLock.unlock();}
    }

    /**
     *  Do we really need more fallbacks?
     *  Used to prevent a zillion of them.
     *  Does not check config, only call if config allows zero hop.
     *  @return true if more fallback tunnels are needed
     */
    boolean needFallback() {
        long exp = _context.clock().now() + 120L * 1000;
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
     *  Shorten the length when under extreme stress, else clear the override.
     *  We only do this for exploratory tunnels, since we have to build a fallback
     *  if we run out. It's much better to have a shorter tunnel than a fallback.
     *
     *  @since 0.8.11
     */
    private void setLengthOverride() {
        int len = _settings.getLength();
        if (len > 1) {
            int th1;
            int th2;
            int minLen;
            RateStat e;
            RateStat r;
            RateStat s;
            if (_settings.isExploratory()) {
                th1 = BUILD_TRIES_LENGTH_OVERRIDE_1;   // 10
                th2 = BUILD_TRIES_LENGTH_OVERRIDE_2;   // 12
                minLen = 1;
                e = getRateStat(_expireStatSlot, "tunnel.buildExploratoryExpire");
                r = getRateStat(_rejectStatSlot, "tunnel.buildExploratoryReject");
                s = getRateStat(_successStatSlot, "tunnel.buildExploratorySuccess");
            } else {
                th1 = BUILD_TRIES_LENGTH_OVERRIDE_CLIENT_1;  // 4
                th2 = BUILD_TRIES_LENGTH_OVERRIDE_CLIENT_2;  // 5
                minLen = 2;
                e = getRateStat(_expireStatSlot, "tunnel.buildClientExpire");
                r = getRateStat(_rejectStatSlot, "tunnel.buildClientReject");
                s = getRateStat(_successStatSlot, "tunnel.buildClientSuccess");
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

    /** List of tunnelInfo instances of tunnels currently being built.
     *  @return the list of tunnels currently being built
     */
    public List<PooledTunnelCreatorConfig> listPending() {synchronized (_inProgress) {return new ArrayList<>(_inProgress);}}

    /**
     *  Count tunnels that are usable for routing — not failed, not expired,
     *  not expiring within 5 minutes.  Used by the EMERGENCY balance check
     *  so zombie tunnels don't skew the comparison.
     *
     *  @return the number of usable tunnels
     *  @since 0.9.69+
     */
    int getUsableTunnelCount() {
        long now = _context.clock().now();
        int count = 0;
        _tunnelsLock.lock();
        try {
            for (TunnelInfo t : _tunnels) {
                if (t.getExpiration() <= now) continue;
                if (t.getTunnelFailed() ||
                    t.getTestStatus() == TunnelTestStatus.FAILING) continue;
                count++;
            }
        } finally {_tunnelsLock.unlock();}
        return count;
    }

    /**
     *  Whether this is a short-lived ping pool.
     *  Ping pools keep their configured quantity — the 2-tunnel floor
     *  doesn't apply.
     */
    private boolean isPingPool() {
        String nickname = _settings.getDestinationNickname();
        return nickname != null && (nickname.equals("I2Ping") ||
                                    (nickname.startsWith("Ping") && nickname.contains("[")));
    }

    /**
     *  The tunnel count the pool maintains, never less than 2 per direction
     *  regardless of the configured quantity, unless the pool is a ping pool
     *  or expressly zero-hop.
     *
     *  @return the number of tunnels to build and keep
     */
    int getEffectiveTarget() {
        if (isPingPool() || _settings.isZeroHop()) {return _settings.getQuantity();}
        return Math.max(2, _settings.getQuantity());
    }

    /**
     * Pool settings.
     * @return the settings for this pool
     */
    public TunnelPoolSettings getSettings() {return _settings;}

    /** Update the settings for this pool
     *  @param settings the new settings, may be null
     */
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

    /** Paired pool (inbound &lt;-&gt; outbound for same destination).
     *  @param pool the paired pool
     */
    void setPairedPool(TunnelPool pool) {
        _pairedPool = pool;
    }

    /** Paired pool (inbound &lt;-&gt; outbound for same destination).
     *  @return the paired pool, or null
     */
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

    /** @return the number of tunnels in the pool */
    public int size() {
        _tunnelsLock.lock();
        try {return _tunnels.size();} finally {_tunnelsLock.unlock();}
    }

    /**
     *  Count valid (non-failed, not expired) tunnels in the pool.
     *  Includes untested and testing tunnels — suitable for exploratory pools
     *  and build management.
     *
     *  @return the number of valid tunnels
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
                    info.getTestStatus() == TunnelTestStatus.FAILED) {
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
     *  Count GOOD and TESTING (non-failed, not expired) tunnels in the pool.
     *  Excludes untested, failing, and failed tunnels.  Includes TESTING
     *  tunnels so the pool doesn't build unnecessarily while tunnels await
     *  test results.  Suitable for LeaseSet publication and UI
     *  &quot;ready&quot; indicators.
     *
     *  @return the number of active tunnels
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
                    (!isPingPool && info.getTestStatus() != TunnelTestStatus.GOOD &&
                     info.getTestStatus() != TunnelTestStatus.TESTING) ||
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
     *  Count tunnels that have been built but not yet passed their first test.
     *  Excludes previously-GOOD (FAILING), FAILED, and definitely-failed
     *  tunnels.  These are treated as &quot;in progress&quot; for replacement accounting
     *  and UI display.
     *
     *  @return the number of testing tunnels
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
                TunnelTestStatus ts = info.getTestStatus();
                // Exclude GOOD (already proven), FAILED (dead), FAILING (previously GOOD, still works),
                // and UNTESTED (haven't started testing yet — don't count toward maintenance deficit)
                if (ts == TunnelTestStatus.GOOD ||
                    ts == TunnelTestStatus.FAILED ||
                    ts == TunnelTestStatus.FAILING ||
                    ts == TunnelTestStatus.UNTESTED) {continue;}
                long timeLeft = info.getExpiration() - now;
                if (timeLeft <= 0) {continue;}
                count++;
            }
        } finally {_tunnelsLock.unlock();}
        return count;
    }

    /**
     *  Count tunnels currently being built.
     *
     *  @return the number of in-progress builds
     *  @since 0.9.67
     */
    public int getInProgressCount() {
        synchronized (_inProgress) {return _inProgress.size();}
    }

    /**
     *  Remove a config that was never actually sent to the network (e.g. a
     *  build skipped by the executor's pacing gate). Unlike buildComplete(),
     *  no result accounting, peer cooldowns, or profile updates occur; the
     *  pool's deficit logic will simply request the tunnel again later.
     *
     *  @param cfg the config to remove from the in-progress list
     *  @since 0.9.70
     */
    void removeInProgress(PooledTunnelCreatorConfig cfg) {
        synchronized (_inProgress) {
            _inProgress.remove(cfg);
        }
    }

    /**
     *  Cancel excess in-progress tunnel builds to stay within budget.
     *  Cancels the newest builds first (less time invested).
     *  @param maxAllowed the maximum number of in-progress builds allowed
     *  @return list of cancelled tunnel configs (BuildExecutor should remove from _currentlyBuildingMap)
     *  @since 0.9.68
     */
    public List<PooledTunnelCreatorConfig> cancelExcessInProgress(int maxAllowed) {
        List<PooledTunnelCreatorConfig> cancelled = new ArrayList<>();
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

        maybePreBuildReplacement();

        long now = _context.clock().now();
        List<TunnelInfo> toRemove = new ArrayList<>();

        _tunnelsLock.lock();
        try {
            int poolSize = _tunnels.size();
            if (poolSize <= 1) {
                return 0;
            }
            if (isPairedPoolStrained()) {
                return 0;
            }
            int target = _settings.getTotalQuantity();
            int currentSize = _tunnels.size();
            boolean isServerPool = _settings.isInbound() && !_settings.isExploratory();
            int pruneThreshold = computePruneThreshold(target, isServerPool);
            if (currentSize > pruneThreshold) {
                int toPrune = markExcessTunnels(toRemove, isServerPool, _settings.getQuantity(), now, currentSize - pruneThreshold);
                if (toPrune > 0 && !isServerPool) {
                    pruneExcessGoodTunnels(toRemove, toPrune, now);
                }
            }
            markFailedTunnelsForEarlyExpiry(toRemove, target, now);
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
     *  When the paired opposite-direction pool is nearly empty (size &lt;= 1),
     *  its tunnels are slow/failed and cleanup may not catch up — nudge the
     *  manager to pre-build a replacement now, throttled.
     */
    private void maybePreBuildReplacement() {
        if (!_settings.isInbound() || _settings.isExploratory()) {return;}
        Hash dest = _settings.getDestination();
        TunnelPool oppositePool = _manager.getOutboundPool(dest);
        if (oppositePool == null || oppositePool.size() > 1) {return;}
        if (size() <= 1) {return;}
        long nowMs = _context.clock().now();
        if (nowMs - _lastPreBuildTime < PRE_BUILD_THROTTLE_MS) {return;}
        _lastPreBuildTime = nowMs;
        if (_log.shouldWarn())
            _log.warn(toString() + " -> Pre-building tunnel replacement before slow/failed cleanup");
        _manager.tunnelFailed();
    }

    /**
     *  Paired-pool gate: skip pruning entirely when this inbound pool's
     *  opposite outbound pool is below its target.  Pruning IB tunnels when
     *  OB has 0 active causes a synchronized collapse: all IB tunnels built
     *  at boot expire together, all OB pools lose their paired IB tunnels
     *  simultaneously, and 5+ EMERGENCY triggers fire at once.  Let tunnels
     *  expire naturally (11 min) to stagger the removal.
     *
     *  @return true to skip pruning
     */
    private boolean isPairedPoolStrained() {
        if (!_settings.isInbound() || _settings.isExploratory()) {return false;}
        Hash dest = _settings.getDestination();
        TunnelPool oppositePool = _manager.getOutboundPool(dest);
        if (oppositePool == null) {return false;}
        int oppositeUsable = oppositePool.size();
        int oppositeMin = oppositePool.getSettings().getQuantity();
        if (oppositeUsable >= oppositeMin) {return false;}
        if (_log.shouldDebug()) {
            _log.debug(toString() + " -> Skipping cleanup - paired OB pool below target (" + oppositeUsable + "/" + oppositeMin + ")");
        }
        return true;
    }

    /**
     *  Prune threshold: the base target, relaxed when build success is poor —
     *  killing spare tunnels you can't rebuild just makes pool collapse more
     *  likely.  80%+ success prunes at target (normal), 60% or below allows
     *  1.5x target, and between the two the multiplier interpolates linearly.
     *  Server pools always prune at target.
     */
    private int computePruneThreshold(int target, boolean isServerPool) {
        if (isServerPool) {return target;}
        double bsr = getBuildSuccessRate();
        if (Double.isNaN(bsr)) {return target;}
        if (bsr <= 0.6) {
            return Math.max(target + 4, (int)(target * 1.5));
        } else if (bsr < 0.8) {
            double factor = 1.5 - 0.5 * (bsr - 0.6) / 0.2;
            return Math.max(target + 2, (int)(target * factor));
        }
        return target;
    }

    /**
     *  Mark excess tunnels for early expiry, down to the prune threshold.
     *  Tunnels are sorted by prune rank then expiration: FAILED &gt;
     *  FAILING/TOO_SLOW/OVER_BUDGET &gt; UNTESTED &gt; TESTING &gt; GOOD, soonest-
     *  expiring first within a rank.  Server pools never prune GOOD or
     *  FAILING tunnels — GOOD tunnels are published in the LeaseSet and
     *  removing them breaks client connections, and FAILING tunnels were
     *  recently GOOD, still referenced by the propagated LeaseSet; pruning
     *  during the propagation window causes unreachable destinations.
     *  Non-server pools keep at least quantity GOOD tunnels.
     *
     *  @return the remaining deficit not yet pruned
     */
    private int markExcessTunnels(List<TunnelInfo> toRemove, boolean isServerPool, int goodTarget, long now, int toPrune) {
        List<TunnelInfo> sortedTunnels = new ArrayList<>(_tunnels);
        sortedTunnels.sort(new Comparator<TunnelInfo>() {
            /** Compare tunnels by prune rank, then expiration. */
            public int compare(TunnelInfo a, TunnelInfo b) {
                int pa = pruneRank(a.getTestStatus());
                int pb = pruneRank(b.getTestStatus());
                if (pa != pb) return pa - pb;
                return Long.compare(a.getExpiration(), b.getExpiration());
            }
        });
        int goodKept = 0;
        for (TunnelInfo info : sortedTunnels) {
            if (toPrune <= 0) break;
            if (info.getTestStatus() == TunnelTestStatus.GOOD && !isServerPool) {
                goodKept++;
                if (goodKept < goodTarget) {continue;}
            }
            if (isProtectedFromPruning(info, isServerPool, now)) {continue;}
            PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
            TunnelId gwId = _settings.isInbound() ? cfg.getReceiveTunnelId(0) : cfg.getSendTunnelId(0);
            scheduleEarlyExpiry(cfg, now, 0, true);
            toRemove.add(info);
            toPrune--;
            if (_log.shouldDebug()) {
                _log.debug(toString() + " -> Scheduling early expiry for excess tunnel: " + gwId);
            }
        }
        return toPrune;
    }

    /**
     *  A tunnel is protected from pruning if it is a server-pool GOOD or
     *  FAILING tunnel, is actively carrying traffic (non-exploratory pools
     *  only — without this carve-out, pools running live traffic never get
     *  pruned because every tunnel is recently active within the 30s window,
     *  so they accumulate unbounded), is already inside the early-expiry
     *  window, is not a pool config, or has no usable gateway tunnel ID.
     */
    private boolean isProtectedFromPruning(TunnelInfo info, boolean isServerPool, long now) {
        if (isServerPool && (info.getTestStatus() == TunnelTestStatus.GOOD ||
                             info.getTestStatus() == TunnelTestStatus.FAILING)) {return true;}
        if (!_settings.isExploratory() && info instanceof PooledTunnelCreatorConfig &&
            ((PooledTunnelCreatorConfig) info).isRecentlyActive()) {return true;}
        if (info.getExpiration() < now + getPruneEarlyExpiry()) {return true;}
        if (!(info instanceof PooledTunnelCreatorConfig)) {return true;}
        PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
        TunnelId gwId = _settings.isInbound() ? cfg.getReceiveTunnelId(0) : cfg.getSendTunnelId(0);
        return gwId == null || gwId.getTunnelId() == 0;
    }

    /**
     *  Schedule an early expiry for a config; the caller records it in
     *  its removal list.  ExpireJob removes the config after the window.
     *
     *  @param staggerMs additional delay to stagger removals
     *  @param overBudget mark over budget (true) or too slow (false)
     */
    private void scheduleEarlyExpiry(PooledTunnelCreatorConfig cfg, long now, long staggerMs, boolean overBudget) {
        if (overBudget) {
            cfg.setTestOverBudget();
        } else {
            cfg.setTestTooSlow();
        }
        cfg.setExpiration(now + getPruneEarlyExpiry() + staggerMs);
        ExpireJob.scheduleExpiration(_context, cfg);
    }

    /**
     *  Fallback prune for non-server pools: when only GOOD tunnels remain
     *  to prune, mark the soonest-expiring GOOD tunnels for early expiry,
     *  skipping tunnels actively carrying traffic.
     */
    private void pruneExcessGoodTunnels(List<TunnelInfo> toRemove, int toPrune, long now) {
        List<TunnelInfo> goodTunnels = new ArrayList<>();
        for (TunnelInfo info : _tunnels) {
            if (!toRemove.contains(info) && info.getTestStatus() == TunnelTestStatus.GOOD &&
                info instanceof PooledTunnelCreatorConfig) {
                goodTunnels.add(info);
            }
        }
        goodTunnels.sort(EXPIRATION_COMPARATOR);
        for (TunnelInfo info : goodTunnels) {
            if (toPrune <= 0) break;
            // Don't prune if tunnel is actively carrying traffic
            if (((PooledTunnelCreatorConfig) info).isRecentlyActive()) {continue;}
            scheduleEarlyExpiry((PooledTunnelCreatorConfig) info, now, 0, true);
            toRemove.add(info);
            toPrune--;
        }
    }

    /**
     *  Mark completely failed tunnels for early expiry (all pool types).
     *  Capped so the pool keeps at least target tunnels, and staggered 15s
     *  apart so one ExpireJob batch doesn't remove them all simultaneously.
     */
    private void markFailedTunnelsForEarlyExpiry(List<TunnelInfo> toRemove, int target, long now) {
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
                    scheduleEarlyExpiry(cfg, now, stagger, false);
                    toRemove.add(info);
                    failedRemoved++;
                    if (_log.shouldDebug()) {
                        _log.debug(toString() + " -> Scheduling early expiry for failed tunnel: " +
                                   cfg.getReceiveTunnelId(0) + " (stagger +" + (stagger / 1000) + "s, " +
                                   (maxFailedRemove - failedRemoved) + " remaining)");
                    }
                }
            }
        }
    }

    /** Prune priority rank — lower prunes first.  UNTESTED ranked last so they get tested first. */
    private static int pruneRank(TunnelTestStatus s) {
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

    /**
     * Global tunnel build success rate as a fraction (0.0-1.0).
     * Reads the same StatManager rate the Tuner uses.
     * Returns NaN if no data yet (early startup).
     * @return the build success rate
     */
    private double getBuildSuccessRate() {
        RateStat rs = getRateStat(_buildSuccessRateStatSlot, "tunnel.buildSuccessRate");
        if (rs == null)
            return Double.NaN;
        Rate rate = rs.getRate(60000L);
        if (rate == null)
            return Double.NaN;
        double avg = rate.getAverageValue();
        if (Double.isNaN(avg))
            return Double.NaN;
        return avg / 100.0;
    }

    /**
     *  Track recently-added tunnel IDs to prevent duplicates.
     *  Uses a simple sliding window based on add time.
     *  Window is 60 seconds — long enough to catch duplicate
     *  addTunnel() calls for the same tunnel.
     */
    private static final long RECENTLY_ADDED_WINDOW = 60L * 1000;
    /**
     * Throttle refresh — publish at most once per throttle window.
     * 2 min minimum (refresh throttle default) prevents storms;
     * with occasional emergency publishes the actual interval
     * averages ~10 min, driven by the lease duration cap.
     * Initialize to allow first request immediately.
     */
    private long _lastRefreshTime;
    /** Track last proactive LeaseSet publish time for rate limiting */
    private long _lastLeaseSetPublishTime;
    /**
     * Minimum interval between LeaseSet builds (matches getRefreshThrottle(_context)).
     * Prevents rapid LeaseSet object churn on every tunnel add/remove.
     * Cached LeaseSet returned during rate-limit window.
     */
    private volatile LeaseSet _cachedLeaseSet;
    /** Timestamp of the last successful LeaseSet build */
    private long _lastLeaseSetBuildTime;
    /** True after first LeaseSet with at least one lease was built (not necessarily GOOD) */
    private boolean _hasGoodLeaseSet;
    /** True when last built LeaseSet had fewer leases than wanted — bypass cache */
    private boolean _hasIncompleteLeaseSet;
    /**
     *  Whether this pool is struggling to meet its tunnel targets.
     *  Returns true if this pool can't meet its published LeaseSet target
     *  or has fewer active tunnels than the configured quantity.
     *  Used by ClientPeerSelector to relax the cross-tunnel peer
     *  diversity constraint when the pool is starved for build candidates.
     *
     *  @return true if the pool is struggling
     *  @since 0.9.70+
     */
    public boolean isStruggling() {
        return _hasIncompleteLeaseSet || getActiveTunnelCount() < getEffectiveTarget();
    }

    /**
     * Earliest lease end time of the last published LeaseSet.
     * Used to detect same-lease-stall: if the new LS has the same earliest end time,
     * the oldest lease is rotated out so floodfill peers see wasNew=true.
     */
    private long _lastPublishedEarliestLeaseEnd;
    /** Track if a deferred refresh is already scheduled */
    private final AtomicBoolean _pendingRefreshScheduled = new AtomicBoolean();
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

            if (info.getExpiration() > now + 60L * 1000) {
                // Hard cap: never more than target + 2 usable tunnels per direction.
                // FAILED/FAILING tunnels are dead or dying and don't count — they must
                // not block replacement builds.
                int target = getEffectiveTarget();
                int usable = 0;
                for (TunnelInfo t : _tunnels) {
                    TunnelTestStatus ts = t.getTestStatus();
                    if (ts != TunnelTestStatus.FAILED && ts != TunnelTestStatus.FAILING) {
                        usable++;
                    }
                }
                // Capacity: target + 2 so a pool that lost a tunnel to expiry
                // or test failure still holds a full target's worth of usable
                // leases while replacements build.  The extra slot absorbs
                // churn without starving the LeaseSet.
                int maxUsable = Math.max(target + 2, 2);
                if (usable >= maxUsable) {
                    // At capacity — try replacing a non-GOOD tunnel instead of
                    // dropping a freshly-built tunnel.  Only replace FAILED
                    // tunnels (or FAILING for non-server pools).  UNTESTED and
                    // TESTING tunnels must not be replaced — they need time for
                    // TestJob to score them.  Replacing UNTESTED tunnels before
                    // they're tested creates a build→replace→build churn cycle
                    // where the pool can never accumulate GOOD tunnels.
                    TunnelInfo replacee = null;
                    boolean isServerPool = _settings.isInbound() && !_settings.isExploratory();
                    for (TunnelInfo t : _tunnels) {
                        TunnelTestStatus ts = t.getTestStatus();
                        if (ts == TunnelTestStatus.GOOD) {continue;}
                        if (isServerPool && ts == TunnelTestStatus.FAILING) {continue;}
                        if (ts == TunnelTestStatus.UNTESTED) {continue;}
                        if (ts == TunnelTestStatus.TESTING) {continue;}
                        replacee = t;
                        break;
                    }
                    if (replacee != null) {
                        _tunnels.remove(replacee);
                        if (replacee instanceof PooledTunnelCreatorConfig) {
                            ExpireJob.removeFromExpiration((PooledTunnelCreatorConfig) replacee);
                        }
                        _tunnels.add(info);
                        _recentlyAddedTunnels.put(gatewayId, now);
                        if (_log.shouldWarn()) {
                            _log.warn(toString() + " -> Replaced non-GOOD tunnel at cap (" +
                                      usable + " >= max " + maxUsable + ", target=" + target +
                                      ") \n* Removed: " + replacee.getTestStatus() +
                                      "\n* Added: " + info);
                        }
                    } else {
                        if (_log.shouldInfo()) {
                            _log.info(toString() + " -> Pool at capacity, all tunnels GOOD (" +
                                      usable + " >= max " + maxUsable + ", target=" + target +
                                      ") \n* " + info);
                        }
                        return;
                    }
                } else {
                    _tunnels.add(info);
                    _recentlyAddedTunnels.put(gatewayId, now);
                }
                // After adding a non-zero-hop tunnel, prune any bootstrap zero-hop tunnels
                if (!_settings.getAllowZeroHop() && info.getLength() > 1) {
                    pruneZeroHopTunnels();
                }
                if (_settings.isInbound() && !_settings.isExploratory()) {ls = locked_buildNewLeaseSet();}
            }
        } finally {_tunnelsLock.unlock();}
        if (info.getExpiration() > now + 60L * 1000 && ls != null) {
            // Check if we already have a published LeaseSet in NetDB
            Hash destHash = _settings.getDestination();
            LeaseSet publishedLS = _context.netDb().lookupLeaseSetLocally(destHash);
            boolean hasPublishedLS = publishedLS != null;

            // Bypass throttle when the pool is nearly empty AND the published
            // LeaseSet is missing or expiring soon — ensures the LS is never
            // left empty for the full 5-min throttle window after all tunnels
            // expire simultaneously.  The first replacement tunnel gets
            // published immediately so clients can reconnect.  Do not bypass
            // on pool size alone: with a current LeaseSet, requesting on every
            // build would create a requestLeaseSet storm.
            boolean lsExpiringSoon = hasPublishedLS &&
                                     publishedLS.getLatestLeaseDate() - now < getRefreshThrottle(_context);
            if (!hasPublishedLS || now - _lastRefreshTime >= getRefreshThrottle(_context) ||
                (getActiveTunnelCount() <= 1 && lsExpiringSoon)) {
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
        _tunnelsLock.lock();
        try {
            boolean removed = _tunnels.remove(info);
            if (!removed) {return;}
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

        if (size() <= 0 && !isAlive()) {
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
                List<TunnelInfo> tunnelsCopy = new ArrayList<>(_tunnels);
                ls = buildNewLeaseSetFromTunnels(tunnelsCopy, true, false, null);
            }
        } finally {_tunnelsLock.unlock();}

        if (removed) {
            _manager.tunnelFailed();
            processRemovalStats(Collections.singletonList(info));
        }

        if (_alive && _settings.isInbound() && !_settings.isExploratory()) {
            if (ls != null) {
                requestLeaseSet(ls, true);
            } else {
                if (_log.shouldWarn()) {
                    _log.warn(toString() + " -> Unable to build LeaseSet on sync removal (" + remaining + " remaining)");
                }
                buildFallback();
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
     * Remove any zero-hop bootstrap tunnels from the pool.
     * Only removes tunnels with getLength() &lt;= 1 when the pool is configured
     * for &gt;0 hops.  Caller must hold _tunnelsLock.
     */
    private void pruneZeroHopTunnels() {
        Iterator<TunnelInfo> iter = _tunnels.iterator();
        while (iter.hasNext()) {
            TunnelInfo t = iter.next();
            if (t.getLength() <= 1) {
                if (_log.shouldInfo()) {
                    _log.info(toString() + " -> Removing bootstrap zero-hop tunnel " + t);
                }
                if (t instanceof PooledTunnelCreatorConfig) {
                    ExpireJob.removeFromExpiration((PooledTunnelCreatorConfig) t);
                }
                iter.remove();
            }
        }
    }

    /**
     * Build a LeaseSet from a list of tunnels.
     *
     * This is the single canonical LeaseSet construction path for this pool;
     * all callers (periodic republish and synchronous tunnel removal) build
     * their LeaseSet here so the inclusion rules stay consistent.
     *
     * Rules:
     * <ul>
     * <li>FAILED tunnels are never included.</li>
     * <li>GOOD tunnels are preferred; when no GOOD tunnels exist, untested
     *     tunnels are used as a fallback.</li>
     * <li>Tunnels expiring within 2 minutes are excluded — the LeaseSet must
     *     propagate through the floodfill network before the lease expires.</li>
     * <li>Zero-hop tunnels (length &le; 1) are only included when the pool is
     *     expressly configured for zero hops (length 0).  Bootstrap and build
     *     stress overrides must never expose the router directly in a LeaseSet.</li>
     * <li>When no GOOD tunnel has ever been published, publication is deferred
     *     rather than falling back to degraded tunnels.</li>
     * </ul>
     *
     * @param tunnels list of tunnels to build from; the caller must not modify it concurrently
     * @param isServerPool whether this is an inbound non-exploratory pool
     * @param rotateForPropagation if true, rotate out the oldest lease when its
     *        end time matches the previous publication's earliest end time, so
     *        floodfill peers see wasNew=true and re-flood the LeaseSet
     * @param rotatedOut if rotation removed a tunnel from the LeaseSet, filled
     *        with that tunnel, otherwise empty; may be null when rotation is
     *        not needed
     * @return LeaseSet or null if not enough tunnels
     */
    private LeaseSet buildNewLeaseSetFromTunnels(List<TunnelInfo> tunnels, boolean isServerPool,
                                                 boolean rotateForPropagation, List<TunnelInfo> rotatedOut) {
        long now = _context.clock().now();
        int wanted = Math.min(_settings.getQuantity(), LeaseSet.MAX_LEASES);

        // Exclude tunnels expiring within LEASE_MIN_REMAINING_MS — gives the
        // LeaseSet time to propagate through the floodfill network before the
        // lease expires.
        long expireAfter = now + LEASE_MIN_REMAINING_MS;

        boolean hasGood = hasGoodTunnel(tunnels, expireAfter);
        LeaseTunnels selection = collectLeaseTunnels(tunnels, expireAfter, hasGood);

        // Sort by quality — freshest first, then lowest latency, then fewest
        // failures — so the LeaseSet holds the best tunnels available.
        Collections.sort(selection.good, QUALITY_COMPARATOR);
        List<TunnelInfo> goodTunnels = selection.good;
        int wantedLeases = wanted - (selection.zeroHop != null ? 1 : 0);
        if (goodTunnels.size() > wantedLeases) {
            goodTunnels = new ArrayList<>(goodTunnels.subList(0, wantedLeases));
        }

        TreeSet<Lease> leases = buildLeases(goodTunnels, selection.zeroHop);
        if (!leases.isEmpty()) {
            _hasGoodLeaseSet = true;
        }

        if (leases.isEmpty()) {
            // If we've never published a LeaseSet with GOOD tunnels, don't
            // fall back to degraded tunnels — wait for a test cycle to complete.
            if (!_hasGoodLeaseSet) {
                if (_log.shouldInfo()) {
                    _log.info(toString() + "\n* Deferring LeaseSet publication — no GOOD tunnels yet");
                }
                return null;
            }
            addDegradedFallbackLease(leases, tunnels, isServerPool);
        }
        // Rotate out the oldest lease if it matches the previous publish's
        // earliest end time.  This gives each republished LeaseSet a different
        // earliest lease end time, so floodfill peers see wasNew=true and
        // propagate the LS onward.  Without this, a stable tunnel set produces
        // the same earliest end time every build, and the floodfill never
        // re-floods the LS — it slowly fades from the network.
        if (rotateForPropagation) {
            rotateOldestLeaseForPropagation(leases, tunnels, rotatedOut);
        }

        return finalizeLeaseSet(leases, wanted, now);
    }

    /**
     *  Whether any tunnel in the list is GOOD and expiring after the
     *  propagation deadline — gates the UNTESTED fallback.
     */
    private boolean hasGoodTunnel(List<TunnelInfo> tunnels, long expireAfter) {
        for (TunnelInfo t : tunnels) {
            if (!t.getTunnelFailed() && t.getTestStatus() == TunnelTestStatus.GOOD &&
                t.getExpiration() > expireAfter) {
                return true;
            }
        }
        return false;
    }

    /**
     *  Selected tunnels for a LeaseSet build: the quality-sorted GOOD (or
     *  fallback UNTESTED) tunnels and the single best zero-hop tunnel.
     */
    private static class LeaseTunnels {
        final List<TunnelInfo> good;
        final TunnelInfo zeroHop;
        LeaseTunnels(List<TunnelInfo> good, TunnelInfo zeroHop) {
            this.good = good;
            this.zeroHop = zeroHop;
        }
    }

    /**
     *  Select the tunnels that qualify for a LeaseSet: GOOD tunnels (or
     *  UNTESTED fallback when no GOOD exists — fully built and functional,
     *  just not yet verified), expiring after the propagation deadline, with
     *  the single best zero-hop tunnel kept separately.  Zero-hop tunnels
     *  are only kept when the pool expressly allows them; bootstrap, build-
     *  stress, and collapse overrides must never expose the router directly.
     */
    private LeaseTunnels collectLeaseTunnels(List<TunnelInfo> tunnels, long expireAfter, boolean hasGoodTunnel) {
        TunnelInfo zeroHopTunnel = null;
        List<TunnelInfo> goodTunnels = new ArrayList<>();
        for (TunnelInfo tunnel : tunnels) {
            if (tunnel.getTunnelFailed()) continue;
            if (tunnel.getTestStatus() != TunnelTestStatus.GOOD &&
                (hasGoodTunnel || tunnel.getTestStatus() != TunnelTestStatus.UNTESTED)) continue;
            if (tunnel.getExpiration() <= expireAfter) {continue;}

            if (tunnel.getLength() <= 1) {
                if (!_settings.getAllowZeroHop()) {continue;}
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
        return new LeaseTunnels(goodTunnels, zeroHopTunnel);
    }

    /**
     *  Assemble the Lease objects for the selected tunnels: the single
     *  zero-hop lease (if any), then the quality-sorted GOOD tunnels.
     */
    private TreeSet<Lease> buildLeases(List<TunnelInfo> goodTunnels, TunnelInfo zeroHopTunnel) {
        TreeSet<Lease> leases = new TreeSet<>(new LeaseComparator());
        if (zeroHopTunnel != null) {
            Lease lease = buildLeaseFromTunnel(zeroHopTunnel);
            if (lease != null) {
                leases.add(lease);
            }
        }
        for (TunnelInfo tunnel : goodTunnels) {
            Lease lease = buildLeaseFromTunnel(tunnel);
            if (lease != null) {
                leases.add(lease);
            }
        }
        return leases;
    }

    /**
     *  Emergency fallback: when the LeaseSet has no leases but one has been
     *  published before, add the best degraded tunnel so clients don't lose
     *  the destination.
     */
    private void addDegradedFallbackLease(TreeSet<Lease> leases, List<TunnelInfo> tunnels, boolean isServerPool) {
        TunnelInfo fallback = findBestDegradedTunnel(tunnels, isServerPool);
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

    /**
     *  Rotate out the oldest lease when its end time matches the previous
     *  publication's earliest end time, so floodfill peers see wasNew=true
     *  and propagate the LeaseSet onward.  Without this, a stable tunnel set
     *  produces the same earliest end time every build and the floodfill
     *  never re-floods the LS — it slowly fades from the network.
     */
    private void rotateOldestLeaseForPropagation(TreeSet<Lease> leases, List<TunnelInfo> tunnels, List<TunnelInfo> rotatedOut) {
        if (!_hasGoodLeaseSet || _lastPublishedEarliestLeaseEnd <= 0 || leases.size() < 2) {return;}
        long earliest = leases.first().getEndTime();
        if (earliest <= 0 || earliest > _lastPublishedEarliestLeaseEnd) {return;}
        Lease rotated = leases.pollFirst();
        if (_log.shouldInfo())
            _log.info(toString() + " -> Rotated out oldest lease (" +
                      rotated.getGateway() + "/" + rotated.getTunnelId() +
                      ") for floodfill propagation, earliest=" + earliest);
        if (rotatedOut != null) {
            TunnelId rotatedTunnelId = rotated.getTunnelId();
            Hash rotatedGateway = rotated.getGateway();
            for (TunnelInfo tunnel : tunnels) {
                if (tunnel.getTunnelFailed()) continue;
                TunnelId inId = tunnel.getReceiveTunnelId(0);
                Hash gw = tunnel.getPeer(0);
                if (inId != null && gw != null &&
                    inId.equals(rotatedTunnelId) && gw.equals(rotatedGateway)) {
                    rotatedOut.add(tunnel);
                    break;
                }
            }
        }
    }

    /**
     *  Record completeness state, build the LeaseSet object from the leases,
     *  and refresh the publication bookkeeping fields.
     *
     *  @return the built LeaseSet, or null if there are no leases
     */
    private LeaseSet finalizeLeaseSet(TreeSet<Lease> leases, int wanted, long now) {
        if (leases.size() < wanted) {
            if (_log.shouldInfo()) {
                _log.info(toString() + "\n* Not enough leases to build full LeaseSet (" + leases.size() + "/" + wanted + " available)");
            }
            _hasIncompleteLeaseSet = true;
            if (leases.isEmpty()) {return null;}
        } else {
            _hasIncompleteLeaseSet = false;
        }

        LeaseSet ls = new LeaseSet();
        Iterator<Lease> iter = leases.iterator();
        int count = Math.min(leases.size(), wanted);
        for (int i = 0; i < count; i++) {ls.addLease(iter.next());}
        if (_log.shouldInfo()) {_log.info(toString() + " -> New LeaseSet built" + ls);}
        _cachedLeaseSet = ls;
        _lastLeaseSetBuildTime = now;
        _lastPublishedEarliestLeaseEnd = leases.isEmpty() ? 0 : leases.first().getEndTime();
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
            long lifetime = 10L * 60 * 1000;
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
            // A tunnel that failed completely cannot route traffic, even if it
            // is the pool's last one. tunnelFailedCompletely() sets the counter
            // to exactly MAX+1 (4), so compare against getTunnelFailed() rather
            // than a hardcoded threshold or the guard would keep a dead tunnel.
            // The collapse guard only protects tunnels that are still failing
            // tests (failures <= MAX), not ones already marked dead.
            boolean isDead = cfg.getTunnelFailed();
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

    private class LeaseSetRepublishEvent extends SimpleTimer2.TimedEvent {
        /**
         * Republish the LeaseSet and prune non-good tunnels.
         */
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

            if (peer != null && uptime > getStartupTime(_context) && _log.shouldWarn() && consecutiveFailures > 5) {
                _log.warn("Tunnel from " + toString() + " failed -> Blaming [" + peer.toBase64().substring(0,6) + "] -> " + pct + '%' +
                          " (" + consecutiveFailures + " consecutive failures)");
            }
            _context.profileManager().tunnelFailed(peer, pct);

            // Collect peers for priority testing — the sooner we retest,
            // the sooner bad peers drop from fast/high cap tiers
            if (peer != null && _context.clock().now() > getStartupTime(_context)) {
                if (peersToTest == null) {peersToTest = new ArrayList<>(Math.min(end - start, 3));}
                if (peersToTest.size() < 3 && !peersToTest.contains(peer)) {peersToTest.add(peer);}
            }
        }
        if (peersToTest != null && !peersToTest.isEmpty()) {
            PeerTestJob testJob = _context.peerManager().getPeerTestJob();
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
        if (et > 2L * 60 * 1000) {
            long bw = 1024L * (_lifetimeProcessed - _lastLifetimeProcessed) * 1000 / et;   // Bps
            _context.statManager().addRateData(_rateName, bw);
            _lastRateUpdate = now;
            _lastLifetimeProcessed = _lifetimeProcessed;
        }
    }

    /** No-op for outbound and exploratory pools. */
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
        long threeMinutes = 3L * 60 * 1000;
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
                // If the currently published LeaseSet is missing (already expired)
                // or will expire before the deferred refresh fires, throttling
                // would leave the network with a stale/expired LeaseSet for the
                // rest of the throttle window.  Force the refresh now instead so
                // the new LeaseSet is pushed to the republish queue immediately.
                // lookupLeaseSetLocally() returns null once the published
                // LeaseSet expires, so null means it is already stale.
                LeaseSet published = _context.netDb().lookupLeaseSetLocally(_settings.getDestination());
                if (published != null &&
                    published.getLatestLeaseDate() - now >= getRefreshThrottle(_context)) {
                    // Published LeaseSet healthy and outlasting the defer window.
                    // Instead of dropping, schedule a deferred refresh.
                    scheduleDeferredRefresh();
                    return;
                }
                if (_log.shouldDebug()) {
                    _log.debug(toString() + " -> Published LeaseSet missing or expiring within throttle, forcing refresh now");
                }
                force = true;
            }
            _lastRefreshTime = now;
            if (_log.shouldDebug()) {
                _log.debug(toString() + "\n* Refreshing LeaseSet (force=" + force + ", count=" + size() + ")");
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
        if (!_pendingRefreshScheduled.compareAndSet(false, true)) {
            return;
        }
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
    private class DeferredRefreshEvent extends SimpleTimer2.TimedEvent {
        /**
         * Perform the deferred LeaseSet refresh.
         */
        public void timeReached() {
            _pendingRefreshScheduled.set(false);
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
                return earliestExpiry > 0 && earliestExpiry < now + 60L * 1000;
            }
        } finally {_tunnelsLock.unlock();}
        return false;
    }

    /**
     * Build and return current LeaseSet from our tunnels.
     * Used by TunnelPoolManager to validate NetDB has current data and by
     * RepublishLeaseSetJob to re-mint local-only (unpublished) LeaseSets.
     * @return current LeaseSet or null
     */
    public LeaseSet getInboundTunnelsAsLeaseSet() {
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
        if (!_alive) {return false;}
        int usable = getValidTunnelCount();
        if (usable > 0) {return false;}

        if (_log.shouldInfo()) {
            _log.info(toString() + "\n* Building a fallback tunnel");
        }
        // runs inline, since its 0hop
        _manager.getExecutor().buildTunnel(configureNewTunnel(true));
        return true;
    }

    /**
     * Always build a LeaseSet with Leases in sorted order,
     * so that LeaseSet.equals() and lease-by-lease equals() always work.
     * The sort method is arbitrary, as far as the equals() tests are concerned,
     * but we use latest expiration first, since we need to sort them by that anyway.
     *
     */
    private static class LeaseComparator implements Comparator<Lease>, Serializable {
        private static final long serialVersionUID = 1L;
        /**
         * Compare leases by end time, latest first.
         */
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
        private static final long serialVersionUID = 1L;
        private final byte[] _base;
        private final boolean _avoidZero;

        /**
         * The sort key and zero-hop handling for this comparator.
         *
         * @param target key to compare distances with
         * @param avoidZeroHop if true, zero-hop tunnels will be put last
         */
        public TunnelInfoComparator(Hash target, boolean avoidZeroHop) {
            _base = target.getData();
            _avoidZero = avoidZeroHop;
        }

        /**
         * Compare tunnels by XOR distance to the target.
         */
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
        // Tunnels have an 11-minute lifetime; a cached LS within 5 min is
        // still valid and prevents unnecessary churn in published leases.
        // Emergency callers (removeTunnelSynchronous) use buildNewLeaseSetFromTunnels
        // and bypass this cache entirely.
        // CHECK: if the cached LS is expiring within 3 min, bypass cache regardless
        // of age — the caller needs fresh lease dates, not recycled ones.
        // CHECK: if the last LS was incomplete (fewer leases than wanted), rebuild
        // so new GOOD tunnels are included promptly.
        boolean cacheValid = _cachedLeaseSet != null &&
            now - _lastLeaseSetBuildTime < getLeaseSetBuildMinInterval(_context) &&
            !_hasIncompleteLeaseSet;
        if (cacheValid) {
            long earliestExpiry = _cachedLeaseSet.getEarliestLeaseDate();
            long minExpiry = now + 3L * 60 * 1000;
            if (earliestExpiry <= 0 || earliestExpiry < minExpiry) {
                if (_log.shouldInfo())
                    _log.info(toString() + "\n* Cached LeaseSet expiring soon — rebuilding");
                cacheValid = false;
            }
        }
        if (cacheValid) {
            return _cachedLeaseSet;
        }

        boolean isServerPool = _settings.isInbound() && !_settings.isExploratory();
        List<TunnelInfo> rotatedOut = new ArrayList<>(1);
        LeaseSet ls = buildNewLeaseSetFromTunnels(new ArrayList<>(_tunnels), isServerPool, true, rotatedOut);
        if (ls != null && !rotatedOut.isEmpty()) {
            // The rotated-out tunnel is no longer in the LeaseSet — remove it
            // from the pool so ensureSufficientTunnels() builds a replacement.
            // Do NOT removeFromExpiration() here: the ExpireJob Phase 2
            // (dispatcher cleanup) still needs to fire naturally to avoid
            // orphaning the tunnel in the dispatcher routing table.
            TunnelInfo rotated = rotatedOut.get(0);
            TunnelId rotatedTunnelId = rotated.getReceiveTunnelId(0);
            Hash rotatedGateway = rotated.getPeer(0);
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo t = _tunnels.get(i);
                if (t.getTunnelFailed()) continue;
                TunnelId inId = t.getReceiveTunnelId(0);
                Hash gw = t.getPeer(0);
                if (inId != null && gw != null &&
                    inId.equals(rotatedTunnelId) && gw.equals(rotatedGateway)) {
                    _tunnels.remove(i);
                    if (_log.shouldInfo())
                        _log.info(toString() + " -> Removed rotated-out tunnel from pool");
                    break;
                }
            }
        }
        return ls;
    }

    /**
     *  Average test latency for a tunnel.
     *
     *  @param t the tunnel to query
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
        List<TunnelInfo> toRemove = new ArrayList<>();
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
                // destinations.  Let them expire naturally (11 min).
                if (isServerPool && (t.getTestStatus() == TunnelTestStatus.GOOD ||
                                     t.getTestStatus() == TunnelTestStatus.FAILING)) {
                    goodCount++;
                    continue;
                }
                if (t.getTunnelFailed() ||
                    (t.getTestStatus() != TunnelTestStatus.GOOD &&
                     t.getTestStatus() != TunnelTestStatus.UNTESTED)) {
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
        int reserve;
        // Non-publishing pools (client outbound) don't have a LeaseSet to
        // maintain — keeping dead tunnels as LS fallback is pointless and
        // just inflates the pool size, blocking the addTunnel cap and new
        // tunnel replacements.  Prune all non-GOOD tunnels aggressively.
        if (publishesLeaseSet()) {
            reserve = Math.max(target - goodCount, 0);
            // When pool has zero good tunnels, cap reserve aggressively —
            // keeping broken tunnels as LS fallback is counterproductive
            // when there's nothing good to fall back to.
            if (goodCount == 0) {
                reserve = Math.min(reserve, 1);
            }
        } else {
            reserve = 0;
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
            Collections.sort(toRemove, (a, b) -> Integer.compare(a.getConsecutiveFailures(), b.getConsecutiveFailures()));
            toRemove = new ArrayList<>(toRemove.subList(toPrune, toRemove.size()));
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
        Set<Hash> publishedGateways = new HashSet<>(numLeases);
        for (int i = 0; i < numLeases; i++) {
            publishedGateways.add(publishedLS.getLease(i).getGateway());
        }
        if (publishedGateways.isEmpty()) return;
        long now = _context.clock().now();
        int latencyThreshold = _context.getProperty("router.latencyBuildThreshold", 1500);
        List<TunnelInfo> toRemove = new ArrayList<>();
        _tunnelsLock.lock();
        try {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo t = _tunnels.get(i);
                if (t.getTunnelFailed()) continue;
                if (t.getLength() <= 1) continue;
                // Freshly built tunnels stay as reserve whatever their latency —
                // measurements aren't stable yet and they're the best candidates
                // for the next LeaseSet.
                long timeLeft = t.getExpiration() - now;
                if (timeLeft > PRUNE_KEEP_IF_FRESH_MS) {
                    continue;
                }
                // Don't prune UNTESTED tunnels — they were just built and
                // haven't been tested yet.  Pruning them before testing
                // creates a build→prune→build churn cycle where the pool
                // can never accumulate enough GOOD tunnels.
                if (t.getTestStatus() == TunnelTestStatus.UNTESTED) {
                    continue;
                }
                Hash gw = t.getPeer(0);
                if (gw == null) continue;
                if (publishedGateways.contains(gw)) {
                    // Published in LeaseSet — keep
                    continue;
                }
                // Non-published tunnels near expiry or slower than the latency
                // threshold will never be leased — remove them now so fresh
                // replacements build before the pool thins out.
                if (timeLeft <= PRUNE_NEAR_EXPIRY_MS) {
                    toRemove.add(t);
                    continue;
                }
                int lat = getTunnelAvgLatency(t);
                if (lat >= 0 && lat > latencyThreshold) {
                    toRemove.add(t);
                }
            }
        } finally {_tunnelsLock.unlock();}
        if (toRemove.isEmpty()) return;
        // Collapse guard: never prune if it would leave the pool below target.
        // Non-published tunnels are valid backups — they carry data and can be
        // included in the next LeaseSet.  Pruning them creates churn: build 3
        // → publish 1 gateway → prune 2 → pool drops to 0-1 → EMERGENCY → repeat.
        int currentSize = size();
        int target = getEffectiveTarget();
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
                      "pool total " + size() + ")");
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
     * Build a Lease from a single tunnel's gateway.  The lease ends
     * {@link #LEASE_SAFETY_MARGIN} before the tunnel expires so peers
     * re-fetch while the gateway still routes.
     * <p>
     * The end date is bounded by the shorter of the tunnel's overall
     * expiration (shortened when the tunnel is scheduled for early expiry,
     * e.g. pruned excess) and the gateway hop's original full lifetime.  A
     * tunnel scheduled for early expiry stops routing when the shortened
     * expiration fires, so a lease outliving that would leave the destination
     * unreachable until the LeaseSet is republished.
     */
    private Lease buildLeaseFromTunnel(TunnelInfo cfg) {
        TunnelId inId = cfg.getReceiveTunnelId(0);
        Hash gw = cfg.getPeer(0);
        if (inId == null || gw == null) {return null;}
        Lease lease = new Lease();
        long expiration = cfg.getExpiration();
        if (cfg instanceof TunnelCreatorConfig) {
            expiration = Math.min(expiration, ((TunnelCreatorConfig) cfg).getConfig(0).getExpiration());
        }
        // End the lease before the tunnel dies: the gateway keeps routing for
        // the margin, giving peers time to fetch the successor LeaseSet.
        expiration -= LEASE_SAFETY_MARGIN;
        // Cap lease end so peers re-fetch sooner than the full tunnel lifetime.
        // The gateway still processes messages for the full lifetime; only the
        // cached LeaseSet on the requesting side expires earlier.
        long maxLease = getLeaseMaxDuration(_context);
        long maxEnd = _context.clock().now() + maxLease;
        if (expiration > maxEnd)
            expiration = maxEnd;
        long minExpiry = _context.clock().now() + 60L * 1000;
        if (expiration < minExpiry) {expiration = minExpiry;}
        lease.setEndDate(expiration);
        lease.setTunnelId(inId);
        lease.setGateway(gw);
        return lease;
    }

    /**
     * Find the best degraded tunnel from a given list for emergency LS fallback.
     * Picks the tunnel with the fewest consecutive failures (excluding fully
     * dead tunnels).  If tied, picks the one with the latest expiration.
     * For server pools (inbound non-exploratory), tunnels retained with
     * getTunnelFailed() == true are still eligible — they're deliberately
     * kept to prevent pool collapse and must be available for LS fallback.
     * Zero-hop tunnels are never eligible unless the pool is expressly
     * configured for zero hops (length 0).
     * <p>
     * Only tunnels with at least {@link #LEASE_MIN_REMAINING_MS} remaining
     * are eligible: the lease ends {@link #LEASE_SAFETY_MARGIN} before the
     * tunnel expires and is floored 60s out, so a tunnel closer to death
     * would produce a lease ending after the gateway stops routing.
     *
     * @param tunnels list to search; the caller must not modify it concurrently
     * @param isServerPool if true, don't skip getTunnelFailed() tunnels
     * @return best candidate or null
     */
    private TunnelInfo findBestDegradedTunnel(List<TunnelInfo> tunnels, boolean isServerPool) {
        long now = _context.clock().now();
        TunnelInfo best = null;
        int bestFailures = Integer.MAX_VALUE;
        for (TunnelInfo t : tunnels) {
            if (t.getLength() <= 1 && !_settings.getAllowZeroHop()) {continue;}
            if (!isServerPool && (t.getTunnelFailed() ||
                t.getTestStatus() == TunnelTestStatus.FAILED)) {
                continue;
            }
            if (t.getReceiveTunnelId(0) == null || t.getPeer(0) == null) {
                continue;
            }
            if (t.getExpiration() <= now + LEASE_MIN_REMAINING_MS) {continue;}
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
     *  This only sets the peers and creation/expiration times in the configuration.
     *  For the crypto, see BuildRequestor and BuildMessageGenerator.
     *
     *  @return null on failure
     */
    PooledTunnelCreatorConfig configureNewTunnel() {return configureNewTunnel(false);}

    /** Counts of tunnel states gathered in one sweep of the pool. */
    private static final class TunnelStats {
        private int safeActive;       // tunnels with > 3min remaining
        private int nearExpiry;       // tunnels with <= 3min remaining but not yet expired
        private int expiredZombies;   // tunnels past expiration still in pool
        private int untestedCount;    // tunnels awaiting first test — in pool, just unproven
        private int staleUntestedCount;  // UNTESTED tunnels the test queue never reached
        private int failingCount;     // tunnels that have failed tests — likely to die soon
    }

    /**
     *  Effective build target: base target plus the dynamic emergency boost,
     *  the Tuner's failure buffer, and the struggle reserve.
     */
    private int computeEffectiveTarget(int target) {
        int failureBuffer = Tuner.getBuildFailureBuffer();
        int effectiveTarget = Math.min(target + _consecutiveEmergencies + failureBuffer,
                                       target + MAX_EMERGENCY_BOOST + failureBuffer);
        if (isStruggling()) {
            effectiveTarget += STRUGGLE_RESERVE;
        }
        return effectiveTarget;
    }

    /**
     *  Sweep the pool under lock: evict expired zombie tunnels and count the
     *  survivors by state.  An UNTESTED tunnel expiring within the pre-build
     *  window that recently carried verified traffic is spared the prune —
     *  the data proves it works (inbound tunnels are marked GOOD outright;
     *  outbound stay UNTESTED but live).  Only truly idle UNTESTED tunnels are
     *  pruned, so they can't block emergency builds (untestedCount > 0) or
     *  cancel replacement builds (deficit -= untestedCount), which would
     *  deadlock the pool at zero usable tunnels until natural expiry.
     */
    private TunnelStats sweepExpiredAndCountTunnels(long now, long preBuildThreshold) {
        TunnelStats stats = new TunnelStats();
        _tunnelsLock.lock();
        try {
            // getLastTransferred() stores wall clock; read it once per sweep.
            long wallNow = System.currentTimeMillis();
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
                    stats.expiredZombies++;
                    continue;
                }
                // Count UNTESTED — they're in the pool awaiting test.
                if (t.getTestStatus() == TunnelTestStatus.UNTESTED) {
                    // A tunnel still UNTESTED within the pre-build window
                    // (expiring in < 3 min) is stuck — the test queue never
                    // reached it (saturated) or it was abandoned after a pool
                    // reset.  It can never become a usable lease, so prune it.
                    if (t.getExpiration() < preBuildThreshold) {
                        // In-use protection: never tear down a tunnel that has
                        // recently carried verified traffic.
                        if (t instanceof PooledTunnelCreatorConfig) {
                            PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) t;
                            if (cfg.getVerifiedBytesTransferred() > 0 &&
                                wallNow - cfg.getLastTransferred() < IN_USE_TRAFFIC_MS) {
                                if (_settings.isInbound()) {
                                    cfg.clearTestFailures();
                                    stats.nearExpiry++;
                                } else {
                                    stats.untestedCount++;
                                }
                                continue;
                            }
                        }
                        it.remove();
                        if (t instanceof PooledTunnelCreatorConfig) {
                            ExpireJob.removeFromExpiration((PooledTunnelCreatorConfig) t);
                        }
                        stats.staleUntestedCount++;
                        continue;
                    }
                    stats.untestedCount++;
                    continue;
                }
                // Count FAILING/FAILED tunnels separately — they can't route traffic
                // but their slots need replacement builds.
                if (t.getTunnelFailed() || t.getTestStatus() == TunnelTestStatus.FAILING) {stats.failingCount++; continue;}
                if (t.getExpiration() > preBuildThreshold) {
                    stats.safeActive++;
                } else {
                    stats.nearExpiry++;
                }
            }
        } finally { _tunnelsLock.unlock(); }
        return stats;
    }

    /** Warn once per cycle when zombie or stale untested tunnels were removed. */
    private void logCleanupSummary(TunnelStats stats) {
        if (stats.expiredZombies > 0 && _log.shouldWarn()) {
            _log.warn(toString() + " -> Cleaned up " + stats.expiredZombies + " expired zombie tunnels from pool");
        }
        if (stats.staleUntestedCount > 0 && _log.shouldWarn()) {
            _log.warn(toString() + " -> Pruned " + stats.staleUntestedCount +
                      " stale UNTESTED tunnel(s) never reached by the test queue");
        }
    }

    /**
     *  Decay the collapse counter: reset when stable or empty (a high counter
     *  on an empty pool inflates the target and hoards broken tunnels), else
     *  decrement by one per cycle once back at the base target.
     */
    private void decayEmergencyCounter(int safeActive, int effectiveTarget, int target) {
        if (safeActive >= effectiveTarget || safeActive == 0) {
            // Reset on stability or total collapse: a high counter on an empty
            // pool inflates the target and hoards broken tunnels instead of
            // recovering.
            _consecutiveEmergencies = 0;
        } else if (safeActive >= target && _consecutiveEmergencies > 0) {
            _consecutiveEmergencies--;
        }
    }

    /**
     *  Cap concurrent builds to prevent build storms: partial pools may run
     *  up to 2x target (capped at 6) so timed-out constructions are replaced
     *  without waiting for the slot; healthy pools stay at target + 1.
     *
     *  @return true to skip the build cycle
     */
    private boolean shouldSkipDueToInProgress(int safeActive, int target, int inProgress) {
        int cap = (safeActive < target) ? Math.min(Math.max(target * 2, 4), 6) : Math.max(target + 1, 2);
        if (safeActive > 0 && inProgress >= cap) {
            if (_log.shouldDebug()) {
                _log.debug(toString() + " -> Skipping build: inProgress(" +
                          inProgress + ") >= cap " + cap +
                          " (target=" + target + ", safeActive=" + safeActive + ")");
            }
            return true;
        }
        return false;
    }

    /**
     *  Dedup guard: skip the cycle when concurrent events (prune, expire,
     *  removal) all trigger within 5s — builds take 10-40s and duplicate
     *  batches waste capacity.  Bypassed on total collapse (safeActive == 0),
     *  where emergency builds must proceed immediately.
     *
     *  @return true to skip the build cycle
     */
    private boolean shouldSkipDueToDedup(int inProgress, int safeActive, long now) {
        if (inProgress > 0 && safeActive > 0 && now - _lastDeficitBuildTime < 5000) {
            if (_log.shouldDebug()) {
                _log.debug(toString() + " -> Skipping build: last deficit build " +
                          (now - _lastDeficitBuildTime) + "ms ago, inProgress=" + inProgress);
            }
            return true;
        }
        return false;
    }

    /**
     *  Proactive replacement: when safeActive is below effectiveTarget and
     *  tunnels are expiring within the pre-build window (or the LeaseSet is
     *  incomplete), build the deficit now so replacements are ready before
     *  the old tunnels die — preventing synchronized expiry cascades.
     *  Untested tunnels don't count as deficit unless the pool has zero GOOD
     *  tunnels, so waiting for test results doesn't churn the test queue.
     *  Failing tunnels count toward the deficit (capped at base target);
     *  per-cycle builds are capped at base target and respect pool backoff
     *  unless the pool is fully collapsed.
     */
    private void buildDeficitReplacements(TunnelStats stats, int target, int effectiveTarget, long now) {
        boolean incompleteLSTrigger = _hasIncompleteLeaseSet &&
            (stats.safeActive + stats.untestedCount >= effectiveTarget);
        if (stats.safeActive < effectiveTarget && (stats.nearExpiry > 0 || stats.safeActive == 0 || _hasIncompleteLeaseSet) && !incompleteLSTrigger) {
            // Failing tunnels will likely die soon — count them as deficit so
            // replacements build before the pool drains.
            int failingBoost = stats.safeActive == 0 ? 0 : Math.min(stats.failingCount, target);
            int currentInProgress = getInProgressCount();
            int deficit;
            if (stats.safeActive == 0) {
                // Zero GOOD tunnels: in-progress builds haven't produced usable
                // tunnels yet (~40s build+test) and expiring ones die first, so
                // only build the gap — bounded via Math.max so timeouts can't
                // create a build → timeout → build storm, and untested tunnels
                // count against the deficit so builds don't pile up faster
                // than the test queue can process them.
                deficit = Math.max(0, effectiveTarget - currentInProgress - stats.untestedCount) + failingBoost;
            } else {
                deficit = effectiveTarget - stats.safeActive - currentInProgress - stats.untestedCount + failingBoost;
            }
            if (deficit > 0) {
                // Cap per-cycle builds at base target — scale up gradually
                int needed = Math.min(deficit, target);
                // Respect pool backoff, unless the pool is truly collapsed
                // (zero safe + tunnels expiring) — recovery must proceed.
                boolean collapsed = stats.safeActive == 0 && stats.nearExpiry > 0;
                if (_manager.getExecutor().isPoolInBackoff(this) && !collapsed) {
                    if (_log.shouldDebug()) {
                        _log.debug(toString() + " -> Skipping " + needed +
                                  " proactive builds, pool in backoff");
                    }
                } else {
                    if (_log.shouldInfo()) {
                        String boost = _consecutiveEmergencies > 0 ?
                            " [boosted +" + _consecutiveEmergencies + "]" : "";
                        _log.info(toString() + " -> Proactive: " + stats.safeActive +
                                  " safe + " + stats.nearExpiry + " expiring + " + stats.failingCount +
                                  " failing, building " + needed +
                                   " replacements (deficit=" + deficit + ", ip=" + currentInProgress + ")" + boost);
                    }
                    _lastDeficitBuildTime = now;
                    for (int i = 0; i < needed; i++) {
                        PooledTunnelCreatorConfig cfg = configureNewTunnel(false);
                        if (cfg != null) {
                            _manager.getExecutor().buildTunnel(cfg);
                        }
                    }
                }
            }
        }
    }

    /**
     *  EMERGENCY rebuild: fires only when zero usable tunnels remain (no
     *  safe, none expiring, none untested) — never blocked by pool backoff,
     *  spaced by a cooldown to prevent death spirals, boosted per collapse,
     *  and gated against the paired pool so one direction can't hoard builds
     *  (unless both are empty, or neither would recover).
     */
    private void handleEmergencyIfNeeded(TunnelStats stats, int target) {
        boolean isPing = _settings.getDestinationNickname() != null &&
                         _settings.getDestinationNickname().startsWith("Ping");
        if (stats.safeActive == 0 && stats.nearExpiry == 0 && stats.untestedCount == 0 && !isPing) {
            // EMERGENCY cooldown: prevent death spirals by spacing out
            // emergency builds.  Without this, EMERGENCY fires every 15s,
            // queues builds that timeout, triggering more EMERGENCYs.
            long nowMs = _context.clock().now();
            if (nowMs - _lastEmergencyBuildTime < EMERGENCY_COOLDOWN_MS) {
                if (_log.shouldDebug()) {
                    _log.debug(toString() + " -> Skipping EMERGENCY: cooldown (" +
                              (nowMs - _lastEmergencyBuildTime) + "ms < " +
                              EMERGENCY_COOLDOWN_MS + "ms)");
                }
                return;
            }
            _lastEmergencyBuildTime = nowMs;
            // Dynamic scaling: boost target on repeated collapses.
            _consecutiveEmergencies = Math.min(_consecutiveEmergencies + 1,
                                               MAX_EMERGENCY_BOOST);
            int effectiveTarget = Math.min(target + _consecutiveEmergencies,
                                           target + MAX_EMERGENCY_BOOST);
            int needed = Math.max(target, 2);
            // IB/OB balance: don't emergency-build if this direction already
            // has MORE usable tunnels than its paired direction.  When both
            // pools are at zero usable, both must build — skipping both would
            // deadlock recovery.  Uses getUsableTunnelCount() (not size()) so
            // zombie tunnels with hundreds of failures don't block recovery.
            TunnelPool paired = _pairedPool;
            if (paired != null) {
                int pairedUsable = paired.getUsableTunnelCount();
                int thisUsable = stats.safeActive + stats.nearExpiry;
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
                          "forcing " + needed + " replacement builds" + boost);
            }
            for (int i = 0; i < needed; i++) {
                PooledTunnelCreatorConfig cfg = configureNewTunnel(false);
                if (cfg != null) {
                    _manager.getExecutor().buildTunnel(cfg);
                }
            }
        }
    }

    /**
     *  Ensure the pool has at least target valid tunnels, building replacements
     *  proactively when the count drops below target. This prevents the pool
     *  from silently draining to zero, avoiding tunnel collapse cascades.
     */
    void ensureSufficientTunnels() {
        if (!_alive || !_ensuringTunnels.compareAndSet(false, true)) {return;}
        try {
        // Clear out dead tunnels before counting, so FAILING/FAILED tunnels
        // don't inflate the count and block replacement builds.
        pruneNonGoodTunnels();
        int target = getEffectiveTarget();
        int effectiveTarget = computeEffectiveTarget(target);
        long now = _context.clock().now();
        // Build replacements 3 minutes before the existing tunnels expire, so
        // fresh builds (10-40s) complete well before the old tunnels die and
        // the pool never holds a LeaseSet whose leases are about to expire.
        long preBuildThreshold = now + 3L * 60 * 1000;

        TunnelStats stats = sweepExpiredAndCountTunnels(now, preBuildThreshold);
        logCleanupSummary(stats);

        int inProgress = getInProgressCount();
        decayEmergencyCounter(stats.safeActive, effectiveTarget, target);

        if (shouldSkipDueToInProgress(stats.safeActive, target, inProgress)) {return;}
        if (shouldSkipDueToDedup(inProgress, stats.safeActive, now)) {return;}

        buildDeficitReplacements(stats, target, effectiveTarget, now);
        handleEmergencyIfNeeded(stats, target);
        } finally { _ensuringTunnels.set(false); }
    }

    /**
     *  Request new inbound tunnels so the pool holds the target count with at
     *  least {@link #getLeaseViabilityWindow(RouterContext)} remaining.
     *  Called by
     *  the LeaseSet republisher before a re-mint: the normal build logic
     *  replaces tunnels only when below target or inside its proactive
     *  replacement window (BuildExecutor starts 330s out), so a pool at
     *  target with aging leases never rebuilds on its own, and a re-mint
     *  would re-sign the same near-expired leases.
     *  Respects the in-progress cap, the per-period dedup guard and pool
     *  backoff to avoid build storms.
     */
    public void requestFreshTunnelBuild() {
        if (!_alive || !_settings.isInbound() || _settings.isExploratory()) {
            if (_log.shouldDebug()) {
                _log.debug(toString() + " -> Skipping fresh build: alive=" + _alive +
                          ", inbound=" + _settings.isInbound() + ", exploratory=" + _settings.isExploratory());
            }
            return;
        }
        if (!_ensuringTunnels.compareAndSet(false, true)) {
            if (_log.shouldDebug()) {
                _log.debug(toString() + " -> Skipping fresh build: ensure already in progress");
            }
            return;
        }
        try {
            int target = getEffectiveTarget();
            long now = _context.clock().now();
            long freshUntil = now + getLeaseViabilityWindow(_context);
            _tunnelsLock.lock();
            int fresh = 0;
            try {
                for (int i = 0; i < _tunnels.size(); i++) {
                    TunnelInfo t = _tunnels.get(i);
                    if (t.getExpiration() > freshUntil &&
                        !t.getTunnelFailed() &&
                        t.getTestStatus() != TunnelTestStatus.FAILED) {
                        fresh++;
                    }
                }
            } finally {_tunnelsLock.unlock();}
            int deficit = target - fresh;
            if (deficit <= 0) {
                return;
            }
            int inProgress = getInProgressCount();
            int cap = Math.min(Math.max(target * 2, 4), 6);
            int room = Math.max(0, cap - inProgress);
            int needed = Math.min(deficit, room);
            if (needed <= 0) {
                if (_log.shouldInfo()) {
                    _log.info(toString() + " -> Skipping fresh build: inProgress=" + inProgress +
                              " >= cap " + cap + " (target=" + target + ", fresh=" + fresh + ")");
                }
                return;
            }
            if (now - _lastDeficitBuildTime < 5000) {
                if (_log.shouldDebug()) {
                    _log.debug(toString() + " -> Skipping fresh build: last deficit build " +
                              (now - _lastDeficitBuildTime) + "ms ago (dedup)");
                }
                return;
            }
            if (_manager.getExecutor().isPoolInBackoff(this)) {
                if (_log.shouldDebug()) {
                    _log.debug(toString() + " -> Skipping fresh build, pool in backoff");
                }
                return;
            }
            _lastDeficitBuildTime = now;
            if (_log.shouldInfo()) {
                _log.info(toString() + " -> Fresh build: " + fresh + "/" + target +
                          " leases fresh, building " + needed + " replacement(s) for re-mint");
            }
            for (int i = 0; i < needed; i++) {
                PooledTunnelCreatorConfig cfg = configureNewTunnel(false);
                if (cfg != null) {
                    _manager.getExecutor().buildTunnel(cfg);
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
        long expiration = now + getTunnelLifetime(_context);
        // Stagger 0-240s (4 min) to prevent all tunnels expiring simultaneously.
        // With an 11-min lifetime, 240s stagger spreads expirations over a
        // 4-minute window.  When 11+ pools all build at boot, their IB tunnels
        // all expire at ~11 min, causing ExpireJob.phase1 to remove them all in
        // one batch → mass EMERGENCY triggers → build storm → death spiral.
        // 240s stagger spreads expirations over 4 minutes, giving builds time to
        // complete before the next pool's tunnels expire.
        // NOTE: Capped at 240s because NetDb rejects LeaseSets expiring >15 min
        // in the future (MAX_LEASE_FUTURE).  With an 11-min lifetime, stagger
        // must stay under 4 min to avoid "Future LeaseSet" errors.
        int stagger = _context.random().nextInt(240001);
        expiration += stagger;

        if (!forceZeroHop) {
            int len = settings.getLengthOverride();
            if (len < 0) {len = settings.getLength();}
            if (len > 0 && (!settings.isExploratory()) && _context.random().nextInt(4) < 3) { // 75%
                // Look for a tunnel to reuse, if the right length and expiring soon.
                // Ignore variance for now.
                // Skip tunnels whose peers are on cooldown to ensure diversity.
                len++; // us
                long cooldownCutoff = now - TunnelPeerSelector.PEER_SELECTION_COOLDOWN_MS;
                int idx = 0;
                // Hold _tunnelsLock only for the scan itself; the cooldown
                // checks touch the concurrent _peerCooldowns map and don't
                // need the pool lock.
                while (peers == null) {
                    _tunnelsLock.lock();
                    try {
                        for (; idx < _tunnels.size(); idx++) {
                            TunnelInfo ti = _tunnels.get(idx);
                            if (ti.getLength() >= len && ti.getExpiration() < now + 3L * 60 * 1000 && !ti.wasReused()) {
                                ti.setReused();
                                len = ti.getLength();
                                peers = new ArrayList<>(len);
                                // Peers list is ordered endpoint first, but cfg.getPeer() is ordered gateway first
                                for (int i = len - 1; i >= 0; i--) {peers.add(ti.getPeer(i));}
                                break;
                            }
                        }
                    } finally {_tunnelsLock.unlock();}
                    if (peers == null) {
                        // no candidate found; fall through to normal selection
                        break;
                    }
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
                        continue; // try the next tunnel
                    }
                    // Record cooldown for reused peers so selectPeers respects them
                    for (Hash p : peers) {
                        if (!p.equals(_context.routerHash()) &&
                            !TunnelPeerSelector.hasRecoveredFromFailure(_context, p)) {
                            TunnelPeerSelector._peerCooldowns.put(p, now);
                        }
                    }
                }
            }
            if (peers == null) {
                setLengthOverride();
                peers = _peerSelector.selectPeers(settings);
            }

            if ((peers == null) || (peers.isEmpty())) {
                long uptime = _context.router().getUptime();
                if (_log.shouldWarn() && uptime > 3L * 60 * 1000) {
                    String nick = settings.getDestinationNickname();
                    Hash dest = settings.getDestination();
                    String destName;
                    if (nick != null) {
                        destName = nick;
                    } else if (dest != null) {
                        destName = dest.toBase32();
                    } else {
                        destName = "null";
                    }
                    _log.warn("TPool cfgNewTunnel: selectPeers returned " + (peers == null ? "null" : "empty") +
                              " for " + destName +
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

        // Record peer cooldown on build failure so bad OBEPs/IBGWs aren't retried.
        // Only REJECT/BAD_RESPONSE prove the far end was at fault — it answered
        // and refused or broke the build.  TIMEOUT and DUP_ID are ambiguous:
        // no peer can be blamed.  Cooldowning on them punishes healthy peers
        // for network-wide problems and lets a mild timeout rate saturate the
        // cooldown maps, starving peer selection (observed 247/247 client hops
        // in cooldown on a single-destination router).
        if ((result == BuildExecutor.Result.REJECT || result == BuildExecutor.Result.BAD_RESPONSE) &&
            cfg.getLength() > 1) {
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
     *  Count of consecutive tunnel build timeouts.
     *  @return the number of consecutive build timeouts
     *  @since 0.9.53
     */
    int getConsecutiveBuildTimeouts() {return _consecutiveBuildTimeouts.get();}

    /**
     *  Increment consecutive build timeout counter.
     *  Called by BuildExecutor for first-hop failures (OTHER_FAILURE with buildTime >= 1000ms)
     *  so the pool's build-health checks can respond to sustained first-hop failures.
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
                if (paired.getTestStatus() == TunnelTestStatus.UNTESTED) {
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
     * Check if the destination is reachable by looking up its LeaseSet
     * @return whether destination reachable
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
     * @return whether log no tunnels warning
     */
    private boolean shouldLogNoTunnelsWarning() {
        long uptime = _context.router().getUptime();
        if (uptime < getStartupTime(_context)) {
            return false;
        }

        long now = System.currentTimeMillis();
        int failures = _consecutiveBuildTimeouts.get();
        long suppressionPeriod = Math.min(5L * 60 * 1000 + (failures * 30L * 1000), 10L * 60 * 1000);

        if (now - _lastNoTunnelsWarningTime < suppressionPeriod) {
            return false;
        }

        _lastNoTunnelsWarningTime = now;
        return true;
    }

    /**
     * Determine if we should perform detailed failure analysis
     * Only analyze every Nth failure to avoid excessive processing
     * @return whether analyze failure
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
        if (failures > 8 && uptime > 10L * 60 * 1000) {
            _log.info("High tunnel failure rate detected for " + toString() +
                      ": " + failures + " consecutive failures. Consider checking network connectivity and peer selection.");

            // Suggest configuration adjustments
            if (getSettings().getLength() > 3) {
                _log.info("Consider reducing tunnel length for " + toString() +
                          " from " + getSettings().getLength() + " to improve reliability.");
            }
        }

        // Check for specific peer issues
        Set<Hash> failedPeers = new HashSet<>();
        for (int i = 0; i < cfg.getLength(); i++) {
            failedPeers.add(cfg.getPeer(i));
        }

        // Check if same peers appear in multiple failures
        if (failedPeers.size() < cfg.getLength() / 2) {
            _log.info("Repeated peer failures detected in " + toString() +
                      ". Consider reviewing peer selection criteria.");
        }
    }

    /**
     * Description of the pool and its settings.
     */
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
