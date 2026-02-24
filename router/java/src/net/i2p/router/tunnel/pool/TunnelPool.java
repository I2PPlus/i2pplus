package net.i2p.router.tunnel.pool;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
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
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.pool.ExpireLocalTunnelsJob;
import net.i2p.router.tunnel.pool.TestJob;
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
    private static final int REMOVAL_QUEUE_CAPACITY = 1000;
    private final BlockingQueue<TunnelInfo> _removalQueue = new LinkedBlockingQueue<TunnelInfo>(REMOVAL_QUEUE_CAPACITY);
    private volatile boolean _removalJobScheduled = false;
    private final AtomicInteger _concurrentInboundBuilds = new AtomicInteger();
    private final AtomicInteger _concurrentOutboundBuilds = new AtomicInteger();

    private static final int TUNNEL_LIFETIME = 10*60*1000;  // 10 minutes
    private static final int MAX_CONCURRENT_BUILDS_PER_DIRECTION = 6;
    /** if less than one success in this many, reduce quantity (exploratory only) */
    private static final int BUILD_TRIES_QUANTITY_OVERRIDE = 12;
    /** if less than one success in this many, reduce length (exploratory only) */
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_1 = 10;
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_2 = 12;
    private static final long STARTUP_TIME = 30*60*1000;
    //// SIMPLIFIED TIMING - Removed complex thresholds, now uses simple tunnel count ////

    TunnelPool(RouterContext ctx, TunnelPoolManager mgr, TunnelPoolSettings settings, TunnelPeerSelector sel) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPool.class);
        _manager = mgr;
        _settings = settings;
        _tunnels = new ArrayList<TunnelInfo>(settings.getTotalQuantity());
        _peerSelector = sel;
        _expireSkew = _context.random().nextInt(90*1000);
        _started = System.currentTimeMillis();
        _lastRateUpdate = _started;
        _firstInstalled = ctx.getProperty("router.firstInstalled", 0L) + 60*60*1000;
        String name;
        if (_settings.isExploratory()) {name = "Exploratory";}
        else {
            name = _settings.getDestinationNickname();
            // just strip HTML here rather than escape it everywhere in the console
            if (name != null) {name = DataHelper.stripHTML(name);}
            else {name = "[" + _settings.getDestination().toBase32().substring(0,8) + "]";}
        }
        _rateName = "[" + name + "] " + (_settings.isInbound() ? "InBps" : "OutBps");
        refreshSettings();
        ctx.statManager().createRateStat("tunnel.matchLease", "How often our Outbound Endpoint matches their Inbound Gateway", "Tunnels",
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
        _concurrentInboundBuilds.set(0);
        _concurrentOutboundBuilds.set(0);
        if (_log.shouldDebug()) {
            _log.debug(toString() + ": Startup() called, was already alive? " + _alive, new Exception());
        }
        _alive = true;
        _started = System.currentTimeMillis();
        _lastRateUpdate = _started;
        _lastLifetimeProcessed = 0;
        _manager.tunnelFailed();
        if (_settings.isInbound() && !_settings.isExploratory()) {
            // we just reconnected and didn't require any new tunnel builders.
            // however, we /do/ want a leaseSet, so build one
            LeaseSet ls = null;
        _tunnelsLock.lock();
        try {ls = locked_buildNewLeaseSet();} finally {_tunnelsLock.unlock();}
            if (ls != null) {requestLeaseSet(ls);}
        }
        String name;
        if (_settings.isExploratory()) {name = "Exploratory tunnels";}
        else {
            name = _settings.getDestinationNickname();
            // just strip HTML here rather than escape it everywhere in the console
            if (name != null) {name = DataHelper.stripHTML(name);}
            else {name = _settings.getDestination().toBase32();}
        }
        if (_settings.isExploratory()) {
            _context.statManager().createRequiredRateStat(_rateName, (_settings.isInbound() ? "In " : "Out ") +
                                   "(B/s) for " + name, "Tunnels [Exploratory]",
                                   new long[] {60*1000l });
        } else {
            _context.statManager().createRequiredRateStat(_rateName, (_settings.isInbound() ? "In " : "Out ") +
                                   "(B/s) for " + name, "Tunnels [Services]",
                                   new long[] {60*1000l });
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

        // Clean up tunnels list to prevent memory leak - configs in this list
        // hold references to HopConfig, peers, crypto material, etc.
        _tunnelsLock.lock();
        try {
            for (TunnelInfo info : _tunnels) {
                if (info instanceof PooledTunnelCreatorConfig) {
                    PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
                    _context.tunnelDispatcher().remove(cfg, "pool shutdown");
                    _manager.removeFromExpiration(cfg);
                    Long tunnelKey = ExpireLocalTunnelsJob.getTunnelKey(cfg);
                    TestJob.invalidate(tunnelKey);
                }
            }
            _tunnels.clear();
        } finally {_tunnelsLock.unlock();}

        // Clean up removal queue to prevent memory leak
        TunnelInfo ti;
        while ((ti = _removalQueue.poll()) != null) {
            if (ti instanceof PooledTunnelCreatorConfig) {
                PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) ti;
                _context.tunnelDispatcher().remove(cfg, "pool shutdown cleanup");
                _manager.removeFromExpiration(cfg);
                Long tunnelKey = ExpireLocalTunnelsJob.getTunnelKey(cfg);
                TestJob.invalidate(tunnelKey);
            }
        }

        _consecutiveBuildTimeouts.set(0);
        _concurrentInboundBuilds.set(0);
        _concurrentOutboundBuilds.set(0);
    }

    /**
     *  RateStat name for the bandwidth graph
     *  @return non-null
     *  @since 0.9.35
     */
    public String getRateName() {return _rateName;}

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
               shouldWarn = _log.shouldWarn() && uptime > STARTUP_TIME && shouldLogNoTunnelsWarning();
            } else {
                // if there are nonzero hop tunnels and the zero hop tunnels are fallbacks,
                // avoid the zero hop tunnels
                TunnelInfo backloggedTunnel = null;
                if (avoidZeroHop) {
                    for (int i = 0; i < _tunnels.size(); i++) {
                        _lastSelectedIdx++;
                        if (_lastSelectedIdx >= _tunnels.size()) {_lastSelectedIdx = 0;}
                        TunnelInfo info = _tunnels.get(_lastSelectedIdx);
                        // Skip last resort tunnels on first pass - only use if no other option
                        if (info instanceof PooledTunnelCreatorConfig &&
                            ((PooledTunnelCreatorConfig)info).isLastResort()) {
                            lastResortTunnel = info;
                            continue;
                        }
                        // Skip tunnels that have failed completely - they should not be used
                        if (info instanceof PooledTunnelCreatorConfig &&
                            ((PooledTunnelCreatorConfig)info).getTunnelFailed()) {
                            continue;
                        }
                        if (info.getLength() > 1 && info.getExpiration() > now) {
                            // avoid outbound tunnels where the 1st hop is backlogged
                            if (_settings.isInbound() || !_context.commSystem().isBacklogged(info.getPeer(1))) {
                                // Reset counter on successful tunnel selection - indicates working tunnels
                                resetConsecutiveTimeoutsOnSuccess();
                                // Record activity for recentlyActive tracking
                                if (info instanceof PooledTunnelCreatorConfig) {
                                    ((PooledTunnelCreatorConfig) info).recordActivity();
                                }
                                return info;
                            } else {backloggedTunnel = info;}
                        }
                    }
                    // return a random backlogged tunnel
                    if (backloggedTunnel != null) {
                        if (_log.shouldWarn()) {_log.warn(toString() + " -> All tunnels are backlogged");}
                        return backloggedTunnel;
                    }
                }
                // ok, either we are ok using zero hop tunnels, or only fallback tunnels remain.  pick 'em randomly
                for (int i = 0; i < _tunnels.size(); i++) {
                    TunnelInfo info = _tunnels.get(i);
                    // Skip last resort tunnels on first pass - only use if no other option
                    if (info instanceof PooledTunnelCreatorConfig &&
                        ((PooledTunnelCreatorConfig)info).isLastResort()) {
                        lastResortTunnel = info;
                        continue;
                    }
                    // Skip tunnels that have failed completely - they should not be used
                    if (info instanceof PooledTunnelCreatorConfig &&
                        ((PooledTunnelCreatorConfig)info).getTunnelFailed()) {
                        continue;
                    }
                    if (info.getExpiration() > now) {
                        // avoid outbound tunnels where the 1st hop is backlogged
                        if (_settings.isInbound() || info.getLength() <= 1 ||
                            !_context.commSystem().isBacklogged(info.getPeer(1))) {
                            //_log.debug("Selecting tunnel: " + info + " - " + _tunnels);
                            // Reset counter on successful tunnel selection - indicates working tunnels
                            resetConsecutiveTimeoutsOnSuccess();
                            return info;
                        } else {backloggedTunnel = info;}
                    }
                }
                // return a random backlogged tunnel
                if (backloggedTunnel != null) {return backloggedTunnel;}
                //if (_log.shouldWarn()) {
                //    _log.warn(toString() + ": after " + _tunnels.size() + " tries -> No unexpired tunnels were found: " + _tunnels);
                //}
            }
        } finally {_tunnelsLock.unlock();}

        // If we found a last resort tunnel and no other options, use it
        if (lastResortTunnel != null) {
            if (_log.shouldDebug()) {
                _log.debug("Using last resort tunnel for " + this + "...");
            }
            resetConsecutiveTimeoutsOnSuccess();
            return lastResortTunnel;
        }

        if (shouldWarn) {
            String warning;
            if (!_settings.isExploratory() && !isDestinationReachable()) {
                warning = toString() + " -> Destination not reachable (no LeaseSet found)";
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
                if (_tunnels.size() > 1) {
                    Collections.sort(_tunnels, new TunnelInfoComparator(closestTo, avoidZeroHop));
                }
                for (TunnelInfo info : _tunnels) {
                    if (info.getExpiration() > now) {rv = info; break;}
                }
            }
            if (rv == null && _log.shouldWarn() && uptime > STARTUP_TIME) {
                shouldWarn = shouldLogNoTunnelsWarning();
            }
        } finally {_tunnelsLock.unlock();}
        if (rv != null) {
            _context.statManager().addRateData("tunnel.matchLease", closestTo.equals(rv.getFarEnd()) ? 1 : 0);
        } else if (shouldWarn) {
            String warning;
            if (!_settings.isExploratory() && !isDestinationReachable()) {
                warning = toString() + " -> Destination not reachable (no LeaseSet found)";
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
        TunnelInfo rv = null;
        _tunnelsLock.lock();
        try {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = _tunnels.get(i);
                if (_settings.isInbound()) {
                    if (info.getReceiveTunnelId(0).equals(gatewayId)) {rv = info; break;}
                } else {
                    if (info.getSendTunnelId(0).equals(gatewayId)) {rv = info; break;}
                }
            }
        } finally {_tunnelsLock.unlock();}
        return rv;
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
        boolean hasFallback = false;
        _tunnelsLock.lock();
        try {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = _tunnels.get(i);
                if (info.getLength() <= 1 && info.getExpiration() > exp) {hasFallback = true; break;}
            }
        } finally {_tunnelsLock.unlock();}
        return !hasFallback;
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

    /**
     * Check if a tunnel is marked as last resort and is the only tunnel left.
     * Last resort tunnels should never be removed unless replaced.
     *
     * @param info the tunnel to check
     * @return true if this is a protected last resort tunnel
     */
    private boolean isLastResortTunnel(TunnelInfo info) {
        if (!(info instanceof PooledTunnelCreatorConfig)) {
            return false;
        }
        PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
        if (!cfg.isLastResort()) {
            return false;
        }
        // Don't protect failed tunnels - they cannot handle traffic anyway
        if (cfg.getTunnelFailed()) {
            return false;
        }
        // Only protect if this is the only usable tunnel
        int usableCount = 0;
        long now = _context.clock().now();
        for (TunnelInfo t : _tunnels) {
            if (t != info && t.getExpiration() > now) {
                // Skip failed tunnels - they don't count as usable
                if (t instanceof PooledTunnelCreatorConfig) {
                    PooledTunnelCreatorConfig otherCfg = (PooledTunnelCreatorConfig) t;
                    if (otherCfg.getTunnelFailed()) {
                        continue;
                    }
                }
                usableCount++;
            }
        }
        return usableCount <= 0;
    }

    /**
     * Check if removing a tunnel would leave the pool with 0 usable tunnels.
     * MUST be called while holding _tunnelsLock.
     * This is the PRIMARY protection to prevent pool collapse.
     *
     * @param info the tunnel to potentially remove (can be null)
     * @param exclude additional tunnel to exclude from count (can be null)
     * @return true if removal would leave pool with 0 usable tunnels
     */
    private boolean wouldLeavePoolEmptyLocked(TunnelInfo info, TunnelInfo exclude) {
        int usableCount = 0;
        long now = _context.clock().now();
        for (TunnelInfo t : _tunnels) {
            if (t != info && t != exclude) {
                // FIX: Only count tunnels that are NOT expired AND NOT failed
                // Expired and failed tunnels cannot handle traffic, even if marked last-resort
                // This was causing pools to appear healthy when they had only expired/failed tunnels
                boolean isLastResort = (t instanceof PooledTunnelCreatorConfig)
                    && ((PooledTunnelCreatorConfig)t).isLastResort();
                // Skip failed tunnels - they don't count as usable
                if (t instanceof PooledTunnelCreatorConfig) {
                    PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) t;
                    if (cfg.getTunnelFailed()) {
                        continue;
                    }
                }
                // Only count if not expired (last-resort doesn't help if expired)
                if (t.getExpiration() > now) {
                    usableCount++;
                }
            }
        }
        return usableCount <= 0;
    }

    /**
     * Attempt to remove a tunnel from _tunnels with last resort protection.
     * MUST be called while holding _tunnelsLock.
     * NEVER removes if it would leave the pool empty.
     *
     * @param info the tunnel to remove
     * @return true if removed, false if protected (last tunnel)
     */
    private boolean safeRemoveTunnelLocked(TunnelInfo info) {
        if (wouldLeavePoolEmptyLocked(info, null)) {
            if (info instanceof PooledTunnelCreatorConfig) {
                ((PooledTunnelCreatorConfig) info).setLastResort();
            }
            triggerReplacementBuild();
            // CRITICAL: Even when protecting last tunnel, we must republish LeaseSet
            // The tunnel is still valid and should be in the LeaseSet
            if (_alive && _settings.isInbound() && !_settings.isExploratory()) {
                List<TunnelInfo> tunnelsCopy = new ArrayList<TunnelInfo>(_tunnels);
                if (!tunnelsCopy.isEmpty()) {
                    LeaseSet ls = buildNewLeaseSetFromCopy(tunnelsCopy);
                    if (ls != null) {
                        requestLeaseSet(ls);
                        if (_log.shouldInfo()) {
                            _log.info("PROTECTED: Republished LeaseSet with last tunnel: " + toString());
                        }
                    }
                }
            }
            if (_log.shouldWarn()) {
                _log.warn("PROTECTED: Cannot remove last tunnel from " + toString() + " -> " + info);
            }
            return false;
        }
        _tunnels.remove(info);
        return true;
    }

    private int getAdjustedTotalQuantity() {
        if (_settings.getLength() == 0 && _settings.getLengthVariance() == 0) {return 1;}
        long uptime = _context.router().getUptime();
        int rv = _settings.getTotalQuantity();

        // Hard cap on tunnel counts to prevent runaway growth
        int max = _settings.isExploratory() ?
            TunnelPoolSettings.MAX_EXPLORATORY_QUANTITY :
            TunnelPoolSettings.MAX_CLIENT_QUANTITY;
        if (rv > max) {
            if (_log.shouldWarn()) {
                _log.warn("Tunnel quantity " + rv + " exceeds max " + max + " for " + _settings);
            }
            rv = max;
        }

        if (!_settings.isExploratory()) {
            if (rv <= 1) {return rv;}
        }
        // TODO high-bw non-ff also
        if ((_context.netDb().floodfillEnabled() && uptime > STARTUP_TIME ||SystemVersion.getMaxMemory() >= 1024*1024*1024) && rv < 3) {
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
                   if (tot >= BUILD_TRIES_QUANTITY_OVERRIDE) {
                       if (1000 * sc / tot <= 1000 / BUILD_TRIES_QUANTITY_OVERRIDE) {rv--;}
                   }
               }
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
        if (!_settings.isExploratory()) {return;}
        int len = _settings.getLength();
        if (len > 1) {
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
                    if (tot >= BUILD_TRIES_LENGTH_OVERRIDE_1 ||
                        _firstInstalled > _context.clock().now()) {
                        long succ = tot > 0 ? 1000 * sc / tot : 0;
                        if (succ <=  1000 / BUILD_TRIES_LENGTH_OVERRIDE_1) {
                            if (len > 2 && succ <= 1000 / BUILD_TRIES_LENGTH_OVERRIDE_2) {
                                _settings.setLengthOverride(len - 2);
                            } else {_settings.setLengthOverride(len - 1);}
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

    /**
      * Get the count of tunnels currently being built for this pool.
      * @return number of tunnels in progress
      * @since 0.9.68+
     */
     public int getInProgressCount() {
         synchronized (_inProgress) {return _inProgress.size();}
     }

    /**
     * Remove a config from in-progress list.
     * Called by BuildExecutor to clean up stale configs.
     * @param cfg the config to remove
     * @return true if removed
     */
    boolean removeFromInProgress(PooledTunnelCreatorConfig cfg) {
        synchronized (_inProgress) {
            return _inProgress.remove(cfg);
        }
    }

    /** Republish LeaseSet if tunnels expiring soon - prevents service unavailability */
    private void checkAndRepublishLeaseSet() {
        if (!_settings.isInbound() || _settings.isExploratory() || !_alive) return;

        long now = _context.clock().now();
        // Simple 5-minute threshold for LeaseSet republishing
        long expiryThreshold = now + 5*60*1000;

        synchronized (_tunnels) {
            int expiringSoon = 0;
            for (TunnelInfo t : _tunnels) {
                if (t.getExpiration() > now && t.getExpiration() <= expiryThreshold) {
                    expiringSoon++;
                }
            }
            if (expiringSoon > 0) {
                LeaseSet ls = locked_buildNewLeaseSet();
                if (ls != null) {
                    _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
                }
            }
        }
    }

    /**
       * Check if we can start a new tunnel build for the given direction.
       * Limits concurrent builds per pool per direction to prevent overcompensation
       * when builds are failing.
       *
       * @param isInbound true for inbound pool, false for outbound
       * @return true if a new build can be started
       * @since 0.9.68+
       */
      boolean canStartBuild(boolean isInbound) {
          AtomicInteger counter = isInbound ? _concurrentInboundBuilds : _concurrentOutboundBuilds;
          int maxAllowed = MAX_CONCURRENT_BUILDS_PER_DIRECTION;
          // Increase limit during attacks when build success is low or zero
          double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
          if (buildSuccess < 0.40) {
              maxAllowed += 2; // Add 2 slots during attack
          }
          return counter.get() < maxAllowed;
      }

     /**
      * Record that a tunnel build has started for this pool.
      * Should be called when the build is actually sent to the network.
      *
      * @param isInbound true for inbound pool, false for outbound
      * @since 0.9.68+
      */
     void buildStarted(boolean isInbound) {
         AtomicInteger counter = isInbound ? _concurrentInboundBuilds : _concurrentOutboundBuilds;
         counter.incrementAndGet();
     }

     /**
      * Record that a tunnel build has completed for this pool.
      * Should be called when the build finishes (success or failure).
      *
      * @param isInbound true for inbound pool, false for outbound
      * @since 0.9.68+
      */
     void buildFinished(boolean isInbound) {
         AtomicInteger counter = isInbound ? _concurrentInboundBuilds : _concurrentOutboundBuilds;
         counter.decrementAndGet();
     }

     /**
      * Get the current count of concurrent builds for inbound direction.
      * @return number of concurrent inbound builds
      * @since 0.9.68+
      */
     int getConcurrentInboundBuilds() { return _concurrentInboundBuilds.get(); }

     /**
      * Get the current count of concurrent builds for outbound direction.
      * @return number of concurrent outbound builds
      * @since 0.9.68+
      */
     int getConcurrentOutboundBuilds() { return _concurrentOutboundBuilds.get(); }

    /** duplicate of size(), let's pick one
     *  @return the number of tunnels in the pool
     */
    int getTunnelCount() {return size();}

    /** @return the number of non-expired tunnels (for priority decisions) */
    int getNonExpiredTunnelCount() {
        long now = _context.clock().now();
        int count = 0;
        _tunnelsLock.lock();
        try {
            for (TunnelInfo t : _tunnels) {
                if (t.getExpiration() > now) count++;
            }
        } finally {_tunnelsLock.unlock();}
        return count;
    }

    /** @return true if pool has tunnels expiring within 3 minutes (emergency) */
    boolean isEmergency() {
        long now = _context.clock().now();
        long emergencyThreshold = now + 3*60*1000;
        _tunnelsLock.lock();
        try {
            for (TunnelInfo t : _tunnels) {
                if (t.getExpiration() > now && t.getExpiration() <= emergencyThreshold) {
                    return true;
                }
            }
        } finally {_tunnelsLock.unlock();}
        return false;
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

    /**
     *  Is this pool running AND either exploratory, or tracked by the client manager?
     *  A pool will be alive but not tracked after the client manager removes it
     *  but before all the tunnels have expired.
     *
     *  CRITICAL FIX: For client pools, stay alive if we have usable tunnels even when
     *  the client disconnects. This prevents pool collapse at tunnel expiration.
     *
     *  @return true if the pool is alive and should be tracked
     */
    public boolean isAlive() {
        return _alive && (_settings.isExploratory() ||
               _context.clientManager().isLocal(_settings.getDestination()));
    }

    /**
     * Check if the pool has any usable (non-expired, non-failed) tunnels.
     * Used to determine if pool should continue building replacements
     * even when client is disconnected.
     *
     * @return true if at least one usable tunnel exists
     * @since 0.9.68+
     */
    private boolean hasUsableTunnels() {
        long now = _context.clock().now();
        _tunnelsLock.lock();
        try {
            for (TunnelInfo t : _tunnels) {
                // Count tunnels that haven't failed and haven't expired
                if (t.getExpiration() > now) {
                    if (t instanceof PooledTunnelCreatorConfig) {
                        if (!((PooledTunnelCreatorConfig)t).getTunnelFailed()) {
                            return true;
                        }
                    } else {
                        return true;
                    }
                }
            }
        } finally {
            _tunnelsLock.unlock();
        }
        return false;
    }

    /**
     * Count tunnels that will expire within the next 3 minutes.
     * Used for defensive building when client is disconnected.
     *
     * @return number of tunnels expiring soon
     * @since 0.9.68+
     */
    private int countExpiringSoonTunnels() {
        long now = _context.clock().now();
        long expireThreshold = now + 3 * 60 * 1000; // 3 minutes
        int count = 0;

        _tunnelsLock.lock();
        try {
            for (TunnelInfo t : _tunnels) {
                // Count tunnels expiring within 3 minutes that haven't failed
                if (t.getExpiration() > now && t.getExpiration() <= expireThreshold) {
                    if (t instanceof PooledTunnelCreatorConfig) {
                        if (!((PooledTunnelCreatorConfig)t).getTunnelFailed()) {
                            count++;
                        }
                    } else {
                        count++;
                    }
                }
            }
        } finally {
            _tunnelsLock.unlock();
        }
        return count;
    }

    /** duplicate of getTunnelCount(), let's pick one
     *  @return the number of tunnels in the pool
     */
    public int size() {
        _tunnelsLock.lock();
        try {return _tunnels.size();} finally {_tunnelsLock.unlock();}
    }

    /**
     *  Add a tunnel to the pool.
     *  @param info the tunnel to add
     */
    protected void addTunnel(TunnelInfo info) {
        if (info == null) {return;}
        long now = _context.clock().now();
        if (_log.shouldDebug()) {_log.debug(toString() + " -> Adding tunnel " + info);}

        // Skip limit enforcement for Ping tunnels - they manage their own 1-in/1-out configuration
        String nickname = _settings.getDestinationNickname();
        if (nickname != null && nickname.startsWith("Ping")) {
            if (_log.shouldDebug()) {_log.debug("Skipping limit check for Ping tunnel -> " + toString());}
        } else {

            // Hard cap: reject tunnels that exceed configured quantity
            // During attacks, reduce allocated tunnels to reduce build pressure
            boolean isUnderAttack = _context.profileOrganizer().isLowBuildSuccess();
            int configured = _settings.getQuantity();
            boolean isExploratory = _settings.isExploratory();
            int maxAllowed = isExploratory ? Math.min(configured + 2, 16) : isUnderAttack ? Math.max(configured - 2, 4) : configured;
            int currentCount = 0;
            int expiringCount = 0;
            _tunnelsLock.lock();
            try {
                // Simple 5-minute threshold for tunnel counting
                long expiryThreshold = now + 5*60*1000;
                for (TunnelInfo t : _tunnels) {
                    if (t.getExpiration() > now) {
                        currentCount++;
                        // Count tunnels expiring within unified threshold
                        if (t.getExpiration() <= expiryThreshold) {
                            expiringCount++;
                        }
                    }
                }
            } finally {_tunnelsLock.unlock();}

            // Allow going over limit if tunnels are expiring soon (replacement builds)
            int effectiveMax = maxAllowed;
            if (expiringCount > 0) {
                effectiveMax = maxAllowed + expiringCount; // Allow extra for replacements
            }

            if (currentCount >= effectiveMax) {
                if (_log.shouldInfo()) {
                    long uptime = _context.router().getUptime();
                    _log.info((isUnderAttack ? "Limiting tunnels to " + maxAllowed + " in " :
                                               "Not adding new tunnel to ") + toString() + " -> " +
                              (isUnderAttack ? "Low build success" + (uptime > 15*60*1000 ? " (network under attack?)" : "") :
                                               "Configured limit reached (" + configured + ")"));
                }
                if (info instanceof PooledTunnelCreatorConfig) {
                    ((PooledTunnelCreatorConfig) info).setDuplicate();
                }
                return;
            }

            // Reject 0-hop tunnels for client pools unless explicitly allowed
            if (info.getLength() <= 1 && !_settings.isExploratory() && !_settings.getAllowZeroHop()) {
                if (_log.shouldInfo()) {
                    _log.info("Rejecting 0-hop tunnel for client pool -> " + info);
                }
                if (info instanceof PooledTunnelCreatorConfig) {
                    ((PooledTunnelCreatorConfig) info).setDuplicate();
                }
                return;
            }
        }  // end else for Ping tunnel skip

            // Check for duplicate peer sequence and handle appropriately @since 0.9.68+
            List<TunnelInfo> duplicates = findDuplicateTunnels(info);
            if (!duplicates.isEmpty()) {
                // Check if we have at least 1 good (non-duplicate) tunnel
                if (hasGoodTunnel(info)) {
                    // Check if existing duplicates are expiring soon
                    // If so, replace them with the new tunnel instead of rejecting
                    boolean hasExpiringDuplicate = false;
                    for (TunnelInfo dup : duplicates) {
                        if (dup.getExpiration() <= now + 5*60*1000) {
                            hasExpiringDuplicate = true;
                            break;
                        }
                    }

                    // Count current usable tunnels to ensure we don't drop to 0
                    int usableCount = 0;
                    _tunnelsLock.lock();
                    try {
                        for (TunnelInfo t : _tunnels) {
                            if (t.getExpiration() > now) usableCount++;
                        }
                    } finally {_tunnelsLock.unlock();}
                    // Never reject if we'd have 0 usable tunnels left
                    int minimumRequired = Math.max(1, getAdjustedTotalQuantity() / 2);
                    // During attacks (low build success), be more conservative about rejecting duplicates
                    // since replacements are hard to build - keep duplicates to maintain pool
                    boolean isUnderAttack = _context.profileOrganizer().isLowBuildSuccess();
                    if (isUnderAttack) {
                        minimumRequired = Math.max(minimumRequired, getAdjustedTotalQuantity() - 1);
                    }

                    if (!isUnderAttack && !hasExpiringDuplicate && usableCount > minimumRequired) {
                        // Normal operation: reject duplicate if we have enough alternatives
                        if (_log.shouldWarn()) {
                            _log.warn("Rejecting new tunnel with duplicate peer sequence for " + info);
                        }
                        if (info instanceof PooledTunnelCreatorConfig) {
                            ((PooledTunnelCreatorConfig) info).setDuplicate();
                        }
                        // Don't add this tunnel since it's a duplicate
                        return;
                    } else if (hasExpiringDuplicate && !isUnderAttack) {
                        // Normal operation: replace expiring duplicates with the new tunnel
                        if (_log.shouldInfo()) {
                            _log.info("Replacing expiring duplicate tunnel(s) with new tunnel: " + info);
                        }
                        for (TunnelInfo dup : duplicates) {
                            if (dup.getExpiration() <= now + 5*60*1000) {
                                if (dup instanceof PooledTunnelCreatorConfig) {
                                    ((PooledTunnelCreatorConfig) dup).setDuplicate();
                                }
                                removeTunnel(dup);
                            }
                        }
                    } else {
                        // Under attack OR need this tunnel to maintain minimum pool size
                        if (_log.shouldInfo()) {
                            _log.info("Accepting duplicate tunnel -> " +
                                      (isUnderAttack ? "Under attack" : "Maintaining pool size") + " (" +
                                      usableCount + " usable / " + minimumRequired + " required): " + info);
                        }
                    }
                }
            }

            LeaseSet ls = null;
            _tunnelsLock.lock();
            try {
                boolean canAdd = info.getExpiration() > now + 60*1000;
                // Allow recently-built tunnels regardless of expiration (handles slow builds)
                // If tunnel was created within the last 8 minutes, allow it even if expiration is soon
                // This prevents rejection of tunnels that took a long time to build
                if (!canAdd && info instanceof PooledTunnelCreatorConfig) {
                    long age = now - ((PooledTunnelCreatorConfig) info).getCreationTime();
                    if (age < 8 * 60 * 1000) {
                        canAdd = true;
                    }
                }
                if (canAdd) {
                    _tunnels.add(info);

                    // PROACTIVE EXPIRY: If we're over the configured limit (due to replacement builds),
                    // proactively remove the oldest tunnels to maintain the configured pool size.
                    // This ensures we don't accumulate excess tunnels over time.
                    int configured = _settings.getQuantity();
                    if (_tunnels.size() > configured + 1) {
                        // Sort by expiration and remove oldest ones
                        List<TunnelInfo> sorted = new ArrayList<TunnelInfo>(_tunnels);
                        Collections.sort(sorted, new Comparator<TunnelInfo>() {
                            public int compare(TunnelInfo a, TunnelInfo b) {
                                return Long.compare(a.getExpiration(), b.getExpiration());
                            }
                        });
                        // Remove oldest tunnels until we're back at configured + 1
                        int toRemove = _tunnels.size() - (configured + 1);
                        for (int i = 0; i < toRemove && i < sorted.size(); i++) {
                            TunnelInfo oldest = sorted.get(i);
                            // Don't remove the tunnel we just added
                            if (oldest != info && oldest.getExpiration() > now) {
                                if (_log.shouldInfo()) {
                                    _log.info("Proactively removing oldest tunnel to maintain pool size: " + oldest);
                                }
                                removeTunnel(oldest);
                            }
                        }
                    }

                    if (_settings.isInbound() && !_settings.isExploratory()) {ls = locked_buildNewLeaseSet();}
                }
            } finally {_tunnelsLock.unlock();}
            boolean requestLease = info.getExpiration() > now + 60*1000;
            if (!requestLease && info instanceof PooledTunnelCreatorConfig) {
                long age = now - ((PooledTunnelCreatorConfig) info).getCreationTime();
                if (age < 8 * 60 * 1000) {
                    requestLease = true;
                }
            }
            if (requestLease && ls != null) {requestLeaseSet(ls);}

            // Check if we can now remove any last resort tunnels since we have a replacement
            boolean cleanup = info.getExpiration() > now + 60*1000;
            if (!cleanup && info instanceof PooledTunnelCreatorConfig) {
                long age = now - ((PooledTunnelCreatorConfig) info).getCreationTime();
                if (age < 8 * 60 * 1000) {
                    cleanup = true;
                }
            }
            if (cleanup) {
                cleanupLastResortTunnels();
                // If we added a multi-hop tunnel, remove zero-hop fallbacks immediately
                // This applies to both exploratory and client pools
                if (info.getLength() > 1) {
                    cleanupZeroHopTunnels();
            }
        }
    }

    /**
     * Check if we have at least one good (non-duplicate) tunnel in the pool.
     * A good tunnel is one that isn't a duplicate of the given tunnel.
     *
     * @param newTunnel the tunnel to check duplicates against
     * @return true if we have at least one good tunnel
     * @since 0.9.68+
     */
    private boolean hasGoodTunnel(TunnelInfo newTunnel) {
        boolean hasGood = false;
        _tunnelsLock.lock();
        try {
            for (TunnelInfo existing : _tunnels) {
                if (existing == newTunnel) {continue;}
                if (existing.getLength() != newTunnel.getLength()) {continue;}

                boolean isDuplicate = true;
                for (int i = 0; i < existing.getLength(); i++) {
                    Hash existingPeer = existing.getPeer(i);
                    Hash newPeer = newTunnel.getPeer(i);
                    if (existingPeer == null || newPeer == null || !existingPeer.equals(newPeer)) {
                        isDuplicate = false;
                        break;
                    }
                }

                if (!isDuplicate) {
                    hasGood = true;
                    break;
                }
            }
        } finally {_tunnelsLock.unlock();}
        return hasGood;
    }

    /**
     * Find all tunnels in the pool that have the same peer sequence as the given tunnel.
     *
     * @param tunnel the tunnel to check for duplicates
     * @return list of duplicate tunnels (excluding the given tunnel)
     * @since 0.9.68+
     */
    private List<TunnelInfo> findDuplicateTunnels(TunnelInfo tunnel) {
        List<TunnelInfo> duplicates = new ArrayList<>();
        if (tunnel == null || tunnel.getLength() <= 1) {return duplicates;}

        long now = _context.clock().now();
        _tunnelsLock.lock();
        try {
            for (TunnelInfo existing : _tunnels) {
                if (existing == tunnel) {continue;}
                if (existing.getLength() != tunnel.getLength()) {continue;}

                // FIX: Skip expired tunnels - they can't handle traffic and shouldn't count as duplicates
                // This was causing new tunnels to be rejected when expired ones were still in pool
                if (existing.getExpiration() <= now) {continue;}

                boolean isDuplicate = true;
                for (int i = 0; i < existing.getLength(); i++) {
                    Hash existingPeer = existing.getPeer(i);
                    Hash tunnelPeer = tunnel.getPeer(i);
                    if (existingPeer == null || tunnelPeer == null || !existingPeer.equals(tunnelPeer)) {
                        isDuplicate = false;
                        break;
                    }
                }

                if (isDuplicate) {
                    duplicates.add(existing);
                }
            }
        } finally {_tunnelsLock.unlock();}
        return duplicates;
    }

    /**
     *  Remove last resort tunnels if we have at least one healthy replacement.
     *  Called when a new tunnel is added to the pool.
     *  Never removes tunnels if it would leave the pool with less than minimum required.
     *  @since 0.9.68+
     */
    private void cleanupLastResortTunnels() {
        List<TunnelInfo> toRemove = new ArrayList<>();
        int healthyCount = 0;
        int totalUsable = 0;
        int minimumRequired = Math.max(1, getAdjustedTotalQuantity() / 2);

        _tunnelsLock.lock();
        try {
            long now = _context.clock().now();
            // Count healthy (non-last-resort) and total usable tunnels
            for (TunnelInfo t : _tunnels) {
                if (t.getExpiration() > now) {
                    totalUsable++;
                    if (t instanceof PooledTunnelCreatorConfig &&
                        ((PooledTunnelCreatorConfig)t).isLastResort()) {
                        // This is a last resort tunnel - mark for removal if we have healthy ones
                        toRemove.add(t);
                    } else {
                        healthyCount++;
                    }
                }
            }

            // Never remove if we would drop below minimum required
            int remainingAfterRemoval = totalUsable - toRemove.size();
            if (remainingAfterRemoval < minimumRequired) {
                if (_log.shouldInfo()) {
                    _log.info("Preserving last resort tunnels to maintain minimum pool size (" +
                              remainingAfterRemoval + " < " + minimumRequired + ")");
                }
                toRemove.clear();
            } else if (healthyCount >= 1 && !toRemove.isEmpty()) {
                // Remove last resort tunnels, but NEVER the last tunnel
                List<TunnelInfo> actuallyRemoved = new ArrayList<>();
                for (TunnelInfo t : toRemove) {
                    if (safeRemoveTunnelLocked(t)) {
                        actuallyRemoved.add(t);
                    }
                }
                toRemove.clear();
                toRemove.addAll(actuallyRemoved);
            } else {
                // Don't remove if no healthy tunnels yet
                toRemove.clear();
            }
        } finally {_tunnelsLock.unlock();}

        // Actually perform the removals outside the synchronized block
        for (TunnelInfo t : toRemove) {
            if (_log.shouldInfo()) {
                _log.info("Removing last resort tunnel after replacement built: " + t);
            }
            if (t instanceof PooledTunnelCreatorConfig) {
                PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) t;
                _context.tunnelDispatcher().remove(cfg, "last resort replaced");
                _manager.removeFromExpiration(cfg);
                Long tunnelKey = ExpireLocalTunnelsJob.getTunnelKey(cfg);
                TestJob.invalidate(tunnelKey);
            }
            _manager.tunnelFailed(); // Signal that we need to update counts
        }
    }

    /**
      *  Remove zero-hop fallback tunnels once we have multi-hop tunnels.
      *  Called when a multi-hop tunnel is added to any pool (exploratory or client).
      *  This prevents keeping 0-hop tunnels around after bootstrap is complete
      *  or when multi-hop tunnels become available.
      *  Removes 0-hop tunnels when we have more than 2 total tunnels (3+).
      * @since 0.9.68+
      */
    private void cleanupZeroHopTunnels() {
        List<TunnelInfo> zeroHopTunnels = new ArrayList<>();
        int multiHopCount = 0;
        int totalUsable = 0;
        // Remove 0-hop tunnels if we have more than 2 total tunnels (3+)
        int minimumTotal = 3;

        _tunnelsLock.lock();
        try {
            long now = _context.clock().now();
            // Find all zero-hop tunnels and count multi-hop tunnels
            for (TunnelInfo t : _tunnels) {
                if (t.getExpiration() > now) {
                    totalUsable++;
                    if (t.getLength() <= 1) {
                        // This is a zero-hop tunnel
                        zeroHopTunnels.add(t);
                    } else {
                        multiHopCount++;
                    }
                }
            }

            // Remove 0-hop tunnels ONLY if we have multi-hop alternatives
            // CRITICAL: Never remove all tunnels - must have at least one multi-hop replacement
            // This prevents pool collapse when only zero-hop fallbacks exist
            if (totalUsable >= minimumTotal && multiHopCount > 0 && !zeroHopTunnels.isEmpty()) {
                if (_log.shouldInfo()) {
                    _log.info("Removing " + zeroHopTunnels.size() + " zero-hop tunnels (have " +
                              multiHopCount + " multi-hop of " + totalUsable + " total tunnels)");
                }
                // Remove but NEVER the last tunnel
                List<TunnelInfo> actuallyRemoved = new ArrayList<>();
                for (TunnelInfo t : zeroHopTunnels) {
                    if (safeRemoveTunnelLocked(t)) {
                        actuallyRemoved.add(t);
                    }
                }
                zeroHopTunnels.clear();
                zeroHopTunnels.addAll(actuallyRemoved);
            } else {
                // Don't remove if not enough tunnels or no multi-hop alternatives
                zeroHopTunnels.clear();
            }
        } finally {_tunnelsLock.unlock();}

        // Actually perform the removals outside the synchronized block
        for (TunnelInfo t : zeroHopTunnels) {
            if (_log.shouldInfo()) {
                _log.info("Removing zero-hop fallback tunnel after multi-hop tunnel added to " + toString() + ": " + t);
            }
            if (t instanceof PooledTunnelCreatorConfig) {
                PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) t;
                _context.tunnelDispatcher().remove(cfg, "zero-hop replaced by multi-hop");
                _manager.removeFromExpiration(cfg);
                Long tunnelKey = ExpireLocalTunnelsJob.getTunnelKey(cfg);
                TestJob.invalidate(tunnelKey);
            }
            _manager.tunnelFailed();
        }
    }

    /**
     *  Check if we need defensive (emergency) tunnel building.
     *  This triggers aggressive building when:
     *  1. Pool has critically low tunnels (0-1 usable)
     *  2. Under attack (low build success rate < 30%)
     *  3. Many consecutive build failures
     *
     *  @param wanted the desired number of tunnels
     *  @return number of defensive builds needed, or 0 if no emergency
     *  @since 0.9.68+
     */
    private int checkDefensiveBuilding(int wanted) {
        long now = _context.clock().now();
        int usableTunnels = 0;
        int usableMultiHop = 0;
        int inProgressCount = 0;

        _tunnelsLock.lock();
        try {
            for (TunnelInfo t : _tunnels) {
                // Skip tunnels that have failed completely - they don't count as usable
                if (t instanceof PooledTunnelCreatorConfig &&
                    ((PooledTunnelCreatorConfig)t).getTunnelFailed()) {
                    continue;
                }
                if (t.getExpiration() > now) {
                    usableTunnels++;
                    if (t.getLength() > 1) {
                        usableMultiHop++;
                    }
                }
            }
        } finally {_tunnelsLock.unlock();}

        synchronized (_inProgress) {
            inProgressCount = _inProgress.size();
        }

        // Calculate total tunnels we'll have (existing + in-progress)
        int totalExpected = usableTunnels + inProgressCount;

        // Check for critical conditions
        // Lower threshold during attacks to trigger defensive building earlier
        // Normal: critically low = 0-1 tunnels. During attack: also consider < wanted/2 as concerning
        boolean isCriticallyLow = usableTunnels <= 1 || usableMultiHop == 0;
        boolean isUnderAttack = _context.profileOrganizer().isLowBuildSuccess();
        // During attacks, trigger earlier when we have less than half our desired tunnels
        // Use Math.min to cap at minimum 2 to avoid over-triggering with small pool sizes
        boolean isGettingLowDuringAttack = isUnderAttack && usableTunnels < Math.min(2, wanted / 3 * 2);
        int consecutiveFailures = _consecutiveBuildTimeouts.get();
        boolean hasHighFailures = consecutiveFailures > 5;

        // If we're critically low, trigger emergency builds regardless of in-progress count
        // During attacks or critical tunnel shortage, we need aggressive rebuilding
        if (isCriticallyLow || isGettingLowDuringAttack) {
            // Cap emergency builds to reasonable limits per pool
            // Never build more than wanted + 2 or 8 at a time for a single pool
            int maxEmergencyBuilds = Math.min(wanted + 2, 8);
            int emergencyBuilds = Math.min(Math.max(wanted, 3), maxEmergencyBuilds);
            // Log at info level only - this is handled automatically
            // Suppress during first 15 minutes of uptime - normal during startup
            long uptime = _context.router().getUptime();
            if (_log.shouldInfo() && uptime > 15*60*1000) {
                _log.info((usableTunnels > 0 ? "Low tunnel count (" + usableTunnels + ")" : "No tunnels") +
                          " in " + toString() + " -> Building " + emergencyBuilds + " tunnels...");
            }
            return emergencyBuilds;
        }

        // If under attack with high failures, build more aggressively
        if (isUnderAttack && hasHighFailures && totalExpected < wanted) {
            // Cap defensive builds - never more than wanted or 5 at a time per pool
            int maxDefensiveBuilds = Math.min(wanted, 5);
            int needed = wanted - totalExpected;
            int defensiveBuilds = Math.min(needed + 1, maxDefensiveBuilds);
            if (_log.shouldDebug()) {
                _log.debug("Building " + defensiveBuilds + " additional tunnels for " + toString() +
                           " (current: " + totalExpected + ", failures: " + consecutiveFailures + ")");
            }
            return defensiveBuilds;
        }

        // Proactive attack-time building: maintain 1.5-2x tunnel buffer when under attack
        // to compensate for expected build failures
        if (isUnderAttack) {
            // Target 1.5x wanted during attacks for redundancy
            int targetBuffer = (int)(wanted * 1.5);
            int attackTarget = Math.max(wanted + 1, targetBuffer);

            if (totalExpected < attackTarget) {
                int needed = attackTarget - totalExpected;
                // Build up to wanted + buffer, capped at 2x wanted or 10
                int maxAttackBuilds = Math.min(wanted * 2, 10);
                int attackBuilds = Math.min(needed, maxAttackBuilds);
                if (_log.shouldDebug()) {
                    _log.debug("Under attack: building " + attackBuilds + " for buffer (target: " + attackTarget +
                               ", current: " + totalExpected + ", wanted: " + wanted + ")");
                }
                return attackBuilds;
            }
        }

        return 0;
    }

    /**
     *  Scan pool for tunnels with duplicate peer sequences and remove duplicates.
     *  Ensures at least one tunnel remains per pool (inbound/outbound separately).
     *  This handles existing duplicate tunnels that were in the pool before detection.
     *  Called periodically from countHowManyToBuild().
     *  Skips cleanup during attacks since replacements are hard to build.
     *  @since 0.9.68+
     */
    void cleanupDuplicatePeerTunnels() {
        // Skip cleanup during attacks since replacements are hard to build
        // Keep duplicates to maintain pool functionality
        if (_context.profileOrganizer().isLowBuildSuccess()) {
            return;
        }

        // Only cleanup if we have more than minimum required tunnels
        int wanted = getAdjustedTotalQuantity();
        if (_tunnels.size() <= wanted) {return;}

        List<TunnelInfo> toRemove = new ArrayList<>();
        _tunnelsLock.lock();
        try {
            // Build map of peer sequences to tunnels
            Map<String, List<TunnelInfo>> peerSequenceMap = new HashMap<>();

            for (TunnelInfo t : _tunnels) {
                if (t.getLength() <= 1) {continue;} // Skip 0/1-hop tunnels

                // Build peer sequence key
                StringBuilder key = new StringBuilder();
                for (int i = 0; i < t.getLength(); i++) {
                    Hash peer = t.getPeer(i);
                    if (peer != null) {
                        key.append(peer.toBase64());
                    }
                    key.append("|");
                }

                String sequenceKey = key.toString();
                peerSequenceMap.computeIfAbsent(sequenceKey, k -> new ArrayList<>()).add(t);
            }

            // Find duplicates - keep newest, remove older ones
            for (List<TunnelInfo> duplicates : peerSequenceMap.values()) {
                if (duplicates.size() > 1) {
                    // Sort by expiration (newest first)
                    duplicates.sort((a, b) -> Long.compare(b.getExpiration(), a.getExpiration()));
                    // Keep the first (newest), remove the rest
                    for (int i = 1; i < duplicates.size(); i++) {
                        toRemove.add(duplicates.get(i));
                    }
                }
            }

            // Ensure we keep at least the minimum required tunnels
            int remainingAfterRemoval = _tunnels.size() - toRemove.size();
            while (remainingAfterRemoval < wanted && !toRemove.isEmpty()) {
                // Keep tunnels by removing from toRemove list (keep newest ones)
                toRemove.remove(toRemove.size() - 1);
                remainingAfterRemoval++;
            }

            // Don't remove from _tunnels here - let removeTunnel() handle it with last resort protection
        } finally {
            _tunnelsLock.unlock();
        }

        // Remove duplicates from pool without counting as failures (duplicates are valid tunnels)
        // removeTunnel() has last resort protection
        for (TunnelInfo t : toRemove) {
            if (_log.shouldInfo()) {
                _log.info("Removing duplicate peer sequence tunnel from " + toString());
            }
            if (t instanceof PooledTunnelCreatorConfig) {
                ((PooledTunnelCreatorConfig) t).setDuplicate();
            }
            removeTunnel(t);
        }
    }

    /**
     *  Remove a tunnel from the pool.
     *  NEVER removes if this is the last tunnel (last resort protection).
     *  @param info tunnel to remove
     */
    void removeTunnel(TunnelInfo info) {
        // NEVER remove the last tunnel - protect last resort tunnels
        if (isLastResortTunnel(info)) {
            if (_log.shouldWarn()) {
                _log.warn("Skipping removal of last resort tunnel: " + info + " in " + toString());
            }
            return;
        }

        if (_log.shouldDebug()) {_log.debug(toString() + " -> Queuing tunnel removal " + info);}

        boolean queued = _removalQueue.offer(info);
        if (!queued) {
            if (_log.shouldInfo()) {
                _log.info("Removal queue full, performing synchronous removal: " + info);
            }
            _tunnelsLock.lock();
            try {
                // NEVER remove the last tunnel
                if (!safeRemoveTunnelLocked(info)) {
                    return; // Protected - don't do any cleanup
                }
            } finally {_tunnelsLock.unlock();}
            if (info instanceof PooledTunnelCreatorConfig) {
                PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
                _context.tunnelDispatcher().remove(cfg, "removal queue overflow");
                _manager.removeFromExpiration(cfg);
                Long tunnelKey = ExpireLocalTunnelsJob.getTunnelKey(cfg);
                TestJob.invalidate(tunnelKey);
            }
            _manager.tunnelFailed();
        }

        if (!_removalJobScheduled) {
            _removalJobScheduled = true;
            _context.jobQueue().addJob(new TunnelRemovalJob());
        }
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
    }

    /**
     *  Trigger an immediate replacement build for this pool.
     *  Called when a tunnel is marked as last resort (only tunnel remaining).
     *  Wakes up the BuildExecutor to prioritize building a replacement.
     *
     *  @since 0.9.68+
     */
    void triggerReplacementBuild() {
        if (!isAlive()) return;

        if (_log.shouldInfo()) {
            _log.info("Triggering replacement build for " + this +
                      " (current tunnels: " + getTunnelCount() + ")");
        }

        // Wake up the BuildExecutor immediately
        _manager.tunnelFailed();
    }

    /**
     *  Remove tunnel from the pool.
     *  @param cfg the tunnel to remove
     */
    private void fail(TunnelInfo cfg) {
        long uptime = _context.router().getUptime();
        if (_log.shouldWarn() && uptime > 10*60*1000) {_log.warn("Tunnel build failed -> " + cfg);}

        // Check if this will leave us with no tunnels - if so, trigger immediate replacement
        int tunnelsBeforeRemove = getTunnelCount();
        removeTunnel(cfg);
        int tunnelsAfterRemove = getTunnelCount();

        // If this was the last tunnel, trigger immediate replacement build
        if (tunnelsBeforeRemove > 0 && tunnelsAfterRemove <= 0) {
            if (_log.shouldInfo()) {
                _log.info("Last tunnel failed for " + this + " - triggering immediate replacement");
            }
            triggerReplacementBuild();
        }

        _manager.tunnelFailed();
        _lifetimeProcessed += cfg.getProcessedMessagesCount();
        updateRate();
    }

    /**
     *  Blame all peers in tunnel, with a probability
     *  inversely related to tunnel length
     *  Enhanced with intelligent blame distribution and recovery consideration.
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

            if (uptime > STARTUP_TIME && _log.shouldWarn() && consecutiveFailures > 5) {
                _log.warn("Tunnel from " + toString() + " failed -> Blaming [" + peer.toBase64().substring(0,6) + "] -> " + pct + '%' +
                          " (" + consecutiveFailures + " consecutive failures)");
            }
            _context.profileManager().tunnelFailed(peer, pct);
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
        if (_settings.isInbound() && !_settings.isExploratory()) {
            if (_log.shouldDebug()) {
                _log.debug(toString() + "\n* Refreshing LeaseSet on tunnel expiration (but prior to grace timeout)");
            }
            LeaseSet ls;
            _tunnelsLock.lock();
            try {ls = locked_buildNewLeaseSet();} finally {_tunnelsLock.unlock();}
            if (ls != null) {requestLeaseSet(ls);}
        }
    }

    /**
     *  Request lease set from client for the primary and all aliases.
     *
     *  @param ls non-null
     *  @since 0.9.49
     */
    private void requestLeaseSet(LeaseSet ls) {
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
      *  This will build a fallback tunnel only if:
      *  - Exploratory pool: 0-hop fallback allowed
      *  - Client pool with allowZeroHop=true AND configured for 0 hops: 0-hop fallback allowed
      *  - Client pool configured for 1 hop: 1-hop fallback allowed
      *  - Otherwise: build multi-hop tunnel (enforce 2-hop minimum)
      *
      *  @return true if a fallback tunnel is built, false otherwise
      */
      boolean buildFallback() {
          int quantity = getAdjustedTotalQuantity();
          int usable = 0;
          _tunnelsLock.lock();
          try {usable = _tunnels.size();} finally {_tunnelsLock.unlock();}
          if (usable > 0) {return false;}

          // CRITICAL FIX: Always allow fallback when pool is critically low (0-1 tunnels)
          // Bypass concurrent limits to prevent pool collapse during emergencies
          if (usable > 1 && !canStartBuild(_settings.isInbound())) {
              if (_log.shouldDebug()) {
                  _log.debug("Skipping fallback build for " + toString() + " - max concurrent builds reached for direction");
              }
              return false;
          }

          // Emergency: If pool is critically low, force build immediately
          if (usable <= 1) {
              if (_log.shouldInfo()) {
                  _log.info("Pool critically low (" + usable + ") -> Forcing fallback build for " + toString() + "...");
              }
          }

         // Exploratory pools: allow 0-hop fallback
         if (_settings.isExploratory()) {
             if (_log.shouldInfo()) {
                 _log.info(toString() + "\n* Building 0-hop fallback tunnel (Usable: " + usable + "; Needed: " + quantity + ")");
             }
             _manager.getExecutor().buildTunnel(configureNewTunnel(true));
             return true;
         }

         // Client pools: check configured hop count
         int configuredLength = _settings.getLength();
         boolean allowZeroHop = _settings.getAllowZeroHop();

         // Allow short fallback only if explicitly configured for 0 or 1 hop
         // Do NOT allow zero-hop fallback just because allowZeroHop=true with 2+ hop config
         if (configuredLength <= 1 || (allowZeroHop && configuredLength == 0)) {
             // Client configured for 1 hop or explicit 0-hop: allow short fallback
             if (_log.shouldWarn()) {
                 _log.warn("Warning! Building " + (configuredLength <= 1 ? "1-hop" : "0-hop") + " fallback tunnel for client pool " + toString() +
                           " (configured length: " + configuredLength + ", allowZeroHop: " + allowZeroHop + ")");
             }
             boolean forceZeroHop = (configuredLength == 0) && allowZeroHop;
             _manager.getExecutor().buildTunnel(configureNewTunnel(forceZeroHop));
             return true;
         }

          // Client pools configured for 2+ hops: try to build multi-hop tunnel
          // Don't give up - the BuildExecutor will retry if it fails
          if (_log.shouldWarn()) {
              _log.warn("Client pool " + toString() + " has no tunnels -> Attempting multi-hop build (configured: " + configuredLength + " hops)");
          }
          // Configure and attempt to build a multi-hop tunnel
          // This will fail if no exploratory tunnels available, but keep trying
          _manager.getExecutor().buildTunnel(configureNewTunnel(false));
          return true;
      }

    /**
     * Build a leaseSet from a copy of tunnel list (outside synchronization).
     * Similar to locked_buildNewLeaseSet() but works on copied list.
     * @param tunnelsCopy copy of tunnels to work with
     * @return null on failure
     */
    private LeaseSet buildNewLeaseSetFromCopy(List<TunnelInfo> tunnelsCopy) {
        if (!_alive) {return null;}

        int wanted = Math.min(_settings.getQuantity(), LeaseSet.MAX_LEASES);
        if (tunnelsCopy.size() < wanted) {
            if (_log.shouldInfo()) {
                _log.info(toString() + "\n* Not enough tunnels to build full LeaseSet (" + tunnelsCopy.size() + "/" + wanted + " available)");
            }
            if (tunnelsCopy.isEmpty()) {return null;}
        }

        long expireAfter = _context.clock().now() + 120*1000; // 2 minutes

        TunnelInfo zeroHopTunnel = null;
        Lease zeroHopLease = null;
        TreeSet<Lease> leases = new TreeSet<Lease>(new LeaseComparator());
        for (TunnelInfo tunnel : tunnelsCopy) {
            // Skip tunnels that have failed completely - they should not be in the LeaseSet
            if (tunnel instanceof PooledTunnelCreatorConfig &&
                ((PooledTunnelCreatorConfig)tunnel).getTunnelFailed()) {
                continue;
            }
            if (tunnel.getExpiration() <= expireAfter) {continue;}

            if (tunnel.getLength() <= 1) {
                if (zeroHopTunnel != null) {
                    if (zeroHopTunnel.getExpiration() > tunnel.getExpiration()) {continue;}
                    if (zeroHopLease != null) {leases.remove(zeroHopLease);}
                }
                zeroHopTunnel = tunnel;
            }

            TunnelId inId = tunnel.getReceiveTunnelId(0);
            Hash gw = tunnel.getPeer(0);
            if ((inId == null) || (gw == null)) {
                _log.error(toString() + "-> Broken? Tunnel has no InboundGateway / TunnelID? " + tunnel);
                continue;
            }
            Lease lease = new Lease();
            lease.setEndDate(((TunnelCreatorConfig)tunnel).getConfig(0).getExpiration());
            lease.setTunnelId(inId);
            lease.setGateway(gw);
            leases.add(lease);
            if (tunnel.getLength() <= 1) {zeroHopLease = lease;}
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
        return ls;
    }

    /**
     * Helper method to process tunnel removal statistics outside synchronized block.
     * @param removed list of tunnels that were removed
     */
    private void processRemovalStats(List<TunnelInfo> removed) {
        for (TunnelInfo info : removed) {
            _lifetimeProcessed += info.getProcessedMessagesCount();
            updateRate();

            long lifetimeConfirmed = info.getVerifiedBytesTransferred();
            long lifetime = 10*60*1000;

            for (int i = 0; i < info.getLength(); i++) {
                _context.profileManager().tunnelLifetimePushed(info.getPeer(i), lifetime, lifetimeConfirmed);
            }
        }
    }

    /**
     * Synchronous tunnel removal for use during ExpireLocalTunnelsJob recovery.
     * This removes the tunnel and ensures a new LeaseSet is published BEFORE returning,
     * preventing client connection failures during recovery.
     * NEVER removes last resort tunnels.
     *
     * @param info tunnel to remove
     * @return true if removed and LeaseSet republished, false otherwise
     * @since 0.9.68+
     */
     boolean removeTunnelSynchronous(TunnelInfo info) {
        if (_log.shouldDebug()) {_log.debug(toString() + " -> Synchronous tunnel removal " + info);}

        LeaseSet ls = null;
        int remaining = 0;
        boolean wasInPool = false;

        _tunnelsLock.lock();
        try {
            wasInPool = _tunnels.contains(info);
            if (wasInPool) {
                // NEVER remove the last tunnel
                if (!safeRemoveTunnelLocked(info)) {
                    if (_log.shouldWarn()) {
                        _log.warn("PROTECTED: Skipping synchronous removal of last tunnel: " + info + " in " + toString());
                    }
                    return false;
                }
            }
            remaining = _tunnels.size();

            if (_settings.isInbound() && !_settings.isExploratory()) {
                List<TunnelInfo> tunnelsCopy = new ArrayList<TunnelInfo>(_tunnels);
                ls = buildNewLeaseSetFromCopy(tunnelsCopy);
            }
        } finally {_tunnelsLock.unlock();}

        if (wasInPool) {
            _manager.tunnelFailed();
            processRemovalStats(java.util.Collections.singletonList(info));
            // NOTE: Do NOT remove from dispatcher here!
            // The tunnel is still valid until actual expiration.
            // ExpireLocalTunnelsJob Phase 2 will remove from dispatcher after expiration.
            // This ensures the tunnel can continue to handle traffic until it actually expires.
            if (info instanceof PooledTunnelCreatorConfig) {
                PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
                Long tunnelKey = ExpireLocalTunnelsJob.getTunnelKey(cfg);
                TestJob.invalidate(tunnelKey);
                // Keep in expiration tracking for Phase 2 dispatcher removal
            }
        }

        if (_alive && _settings.isInbound() && !_settings.isExploratory()) {
            if (ls != null) {
                requestLeaseSet(ls);
            } else {
                if (_log.shouldWarn()) {
                    _log.warn(toString() + " -> Unable to build LeaseSet on sync removal (" + remaining + " remaining)");
                }
                if (_settings.getAllowZeroHop()) {
                    buildFallback();
                }
            }
        }

        if (wasInPool && _log.shouldDebug()) {
            _log.debug(toString() + " -> Synchronous tunnel removal complete, LeaseSet republished");
        }

        // LEAK DETECTION: Log when tunnel not found in pool during removal
        if (!wasInPool && _log.shouldInfo()) {
            _log.info("LEAK WARNING: Tunnel not found in pool during removal: " + info +
                      " | Pool size: " + remaining + " | Pool: " + toString());
        }

        return wasInPool;
    }

    /**
     * Inner class to process tunnel removals in batch.
     * Processes all queued removals in a single synchronized operation.
     */
    private class TunnelRemovalJob extends JobImpl {
        public TunnelRemovalJob() {
            super(_context);
        }

        public String getName() {
            return "Process Tunnel Removals";
        }

        public void runJob() {
            _removalJobScheduled = false;

            List<TunnelInfo> toRemove = new ArrayList<TunnelInfo>();
            int maxPerJob = 100;
            for (int i = 0; i < maxPerJob; i++) {
                TunnelInfo ti = _removalQueue.poll();
                if (ti == null) break;
                toRemove.add(ti);
            }

            if (toRemove.isEmpty()) {return;}

            if (_log.shouldInfo()) {
                _log.info(toString() + " -> Processing " + toRemove.size() + " tunnel removals in batch...");
            }

            LeaseSet ls = null;
            int remaining = 0;
            int actuallyRemoved = 0;
            List<TunnelInfo> tunnelsCopy = null;

            _tunnelsLock.lock();
            try {
                for (TunnelInfo info : toRemove) {
                    // NEVER remove the last tunnel - uses safeRemoveTunnelLocked for robust protection
                    if (safeRemoveTunnelLocked(info)) {
                        actuallyRemoved++;
                    }
                }

                remaining = _tunnels.size();

                if (_settings.isInbound() && !_settings.isExploratory()) {
                    tunnelsCopy = new ArrayList<TunnelInfo>(_tunnels);
                }
            } finally {
                _tunnelsLock.unlock();
            }

            if (tunnelsCopy != null) {
                ls = buildNewLeaseSetFromCopy(tunnelsCopy);
            }

            if (actuallyRemoved == 0) {return;}

            // Remove tunnels from expiration queue, TestJob references, and dispatcher maps to prevent memory leak
            for (TunnelInfo info : toRemove) {
                if (info instanceof PooledTunnelCreatorConfig) {
                    PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
                    // Remove from dispatcher maps - this is critical to prevent memory leak!
                    // Without this, OutboundSender, OutboundReceiver, OutboundGatewayProcessor,
                    // and PumpedTunnelGateway objects remain in memory
                    _context.tunnelDispatcher().remove(cfg, "removal job");
                    Long tunnelKey = ExpireLocalTunnelsJob.getTunnelKey(cfg);
                    TestJob.invalidate(tunnelKey);
                    _manager.removeFromExpiration(cfg);
                }
            }

            for (int i = 0; i < actuallyRemoved; i++) {
                _manager.tunnelFailed();
            }

            processRemovalStats(toRemove);

            if (_alive && _settings.isInbound() && !_settings.isExploratory()) {
                if (ls != null) {
                    requestLeaseSet(ls);
                } else {
                    if (_log.shouldWarn()) {
                        _log.warn(toString() + "\n* Unable to build a new LeaseSet on removal (" + remaining
                                  + " remaining) -> Requesting a new tunnel...");
                    }
                    if (_settings.getAllowZeroHop()) {
                        buildFallback();
                    }
                }
            }

            if (getTunnelCount() <= 0 && !isAlive()) {
                _manager.removeTunnels(_settings.getDestination());
            }
        }
    }

    /**
     * Always build a LeaseSet with Leases in sorted order,
     * so that LeaseSet.equals() and lease-by-lease equals() always work.
     * The sort method is arbitrary, as far as equals() tests are concerned,
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
     * Caller must synchronize on _tunnels.
     * The returned LeaseSet will be incomplete; it will not have the destination
     * set and will not be signed. Only the leases will be included.
     *
     * @return null on failure
     */
    protected LeaseSet locked_buildNewLeaseSet() {
        if (!_alive) {return null;}

        int wanted = Math.min(_settings.getQuantity(), LeaseSet.MAX_LEASES);
        if (_tunnels.size() < wanted) {
            if (_log.shouldInfo()) {
                _log.info(toString() + "\n* Not enough tunnels to build full LeaseSet (" + _tunnels.size() + "/" + wanted + " available)");
            }
            // See comment below
            if (_tunnels.isEmpty()) {return null;}
        }

        // We don't want it to expire before the client signs it or the ff gets it
        long expireAfter = _context.clock().now() + 120*1000; // 2 minutes

        TunnelInfo zeroHopTunnel = null;
        Lease zeroHopLease = null;
        TreeSet<Lease> leases = new TreeSet<Lease>(new LeaseComparator());
        TunnelInfo lastResortTunnel = null; // Track last resort tunnel as fallback

        for (int i = 0; i < _tunnels.size(); i++) {
            TunnelInfo tunnel = _tunnels.get(i);
            // Skip tunnels that have failed completely - they should not be in the LeaseSet
            if (tunnel instanceof PooledTunnelCreatorConfig &&
                ((PooledTunnelCreatorConfig)tunnel).getTunnelFailed()) {
                continue;
            }

            // Check if this is a last resort tunnel - include even if near expiry
            boolean isLastResort = tunnel instanceof PooledTunnelCreatorConfig &&
                                   ((PooledTunnelCreatorConfig)tunnel).isLastResort();

            // Track last resort tunnel as fallback for LeaseSet
            if (isLastResort && (lastResortTunnel == null ||
                tunnel.getExpiration() > lastResortTunnel.getExpiration())) {
                lastResortTunnel = tunnel;
            }

            if (tunnel.getExpiration() <= expireAfter) {
                // Skip near-expiry tunnels UNLESS they're last resort (only tunnel available)
                if (!isLastResort) {continue;}
            }

            if (tunnel.getLength() <= 1) {
                // More than one zero-hop tunnel in a lease is pointless
                // and increases the leaseset size needlessly.
                // Keep only the one that expires the latest.
                if (zeroHopTunnel != null) {
                    if (zeroHopTunnel.getExpiration() > tunnel.getExpiration()) {continue;}
                    if (zeroHopLease != null) {leases.remove(zeroHopLease);}
                }
                zeroHopTunnel = tunnel;
            }

            TunnelId inId = tunnel.getReceiveTunnelId(0);
            Hash gw = tunnel.getPeer(0);
            if ((inId == null) || (gw == null)) {
                _log.error(toString() + "-> Broken? Tunnel has no InboundGateway / TunnelID? " + tunnel);
                continue;
            }
            Lease lease = new Lease();

            // ExpireJob reduces the expiration, which causes a 2nd leaseset with the same lease
            // to have an earlier expiration, so it isn't stored.
            // Get the "real" expiration from the gateway hop config,
            // HopConfig expirations are the same as the "real" expiration and don't change
            // see configureNewTunnel()
            lease.setEndDate(((TunnelCreatorConfig)tunnel).getConfig(0).getExpiration());
            lease.setTunnelId(inId);
            lease.setGateway(gw);
            leases.add(lease);
            // Eemember in case we want to remove it for a later-expiring zero-hopper
            if (tunnel.getLength() <= 1) {zeroHopLease = lease;}
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
        return ls;
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
     *  Gather the data to see how many tunnels to build, and then actually compute that value (delegated to
     *  the countHowManyToBuild function below)
     *
     *  CRITICAL FIX: For disconnected clients with usable tunnels, continue building
     *  replacements to prevent pool collapse at tunnel expiration.
     *
     *  @return the number of tunnels to build
     */
    int countHowManyToBuild() {
        // Republish LeaseSet if tunnels expiring soon - prevents service unavailability
        checkAndRepublishLeaseSet();

        // Cleanup expired tunnels BEFORE counting - prevents counting expired tunnels as valid
        // This is the primary defense against pool collapse from expired tunnels
        cleanupExpiredTunnels();

        // Always log entry - used for debugging collapse
        if (_log.shouldDebug()) {
            _log.debug("countHowManyToBuild ENTRY for " + toString() + " isAlive=" + isAlive());
        }

        // Count only non-expired tunnels
        if (isAlive()) {
            int tunnelCount = 0;
            int inProgress = 0;
            long now = _context.clock().now();

            // Count only tunnels that haven't expired
            _tunnelsLock.lock();
            try {
                int expiredCount = 0;
                for (TunnelInfo t : _tunnels) {
                    if (t.getExpiration() > now) {
                        tunnelCount++;
                    } else {
                        expiredCount++;
                    }
                }
                if (expiredCount > 0 && _log.shouldDebug()) {
                    _log.debug("Pool has " + expiredCount + " expired tunnels (not counted)");
                }
            } finally {_tunnelsLock.unlock();}

            synchronized (_inProgress) { inProgress = _inProgress.size(); }
            int wanted = getAdjustedTotalQuantity();
            int total = tunnelCount + inProgress;

            // Debug logging
            if (_log.shouldDebug()) {
                _log.debug("POOL DEBUG " + toString() + ": tunnels=" + tunnelCount +
                          ", inProgress=" + inProgress + ", wanted=" + wanted +
                          ", poolSize=" + _tunnels.size() + ", now=" + now +
                          ", oldestExp=" + (_tunnels.isEmpty() ? "none" : "" +
                          ((TunnelInfo)_tunnels.get(0)).getExpiration()));
            }

            // Build if we have fewer tunnels than wanted
            if (total < wanted) {
                int rv = wanted - total;
                if (_log.shouldDebug()) _log.debug("Building " + rv + " tunnels (have " + tunnelCount + ", want " + wanted + ")");
                return rv;
            }

            // No builds needed
            if (_log.shouldDebug()) _log.debug("No tunnels needed - have " + tunnelCount + ", want " + wanted);
            return 0;
        }

        // Skip disconnected-client logic - simplified block above handles everything
        // Exploratory pools always follow normal build logic
        if (!_settings.isExploratory()) {
            // Check if client is disconnected but we still have usable tunnels
            boolean clientRegistered = _context.clientManager().isLocal(_settings.getDestination());

            if (!clientRegistered && _alive) {
                // Client disconnected - maintain minimum tunnel count to prevent collapse
                // CRITICAL: Count only USABLE (non-expired) tunnels!
                int currentTunnels = 0;
                long now = _context.clock().now();
                _tunnelsLock.lock();
                try {
                    for (TunnelInfo t : _tunnels) {
                        if (t.getExpiration() > now) {
                            currentTunnels++;
                        }
                    }
                } finally {_tunnelsLock.unlock();}
                int wanted = getAdjustedTotalQuantity();
                int inProgress = 0;
                synchronized (_inProgress) { inProgress = _inProgress.size(); }

                // Always maintain at least 2 tunnels or wanted/2 (whichever is smaller)
                // to prevent pool collapse during graceful shutdown
                int minimumToMaintain = Math.min(Math.max(2, wanted / 2), wanted);
                int needed = minimumToMaintain - currentTunnels - inProgress;

                if (needed > 0) {
                    if (_log.shouldInfo()) {
                        _log.info("Client disconnected but maintaining " + needed +
                                  " tunnel(s) to prevent collapse for " + toString() +
                                  " (current: " + currentTunnels + ", wanted: " + wanted + ")");
                    }
                    // Maintain at least wanted/2 tunnels for disconnected clients
                    // to prevent pool collapse during graceful shutdown and allow reconnection
                    int maxToBuild = Math.max(3, wanted / 2);
                    return Math.max(needed, maxToBuild);
                }

                // Even if we have minimum, check for expiring tunnels
                int expiringSoon = countExpiringSoonTunnels();
                if (expiringSoon > 0) {
                    if (_log.shouldInfo()) {
                        _log.info("Client disconnected - building " + expiringSoon +
                                  " replacement(s) for expiring tunnels in " + toString());
                    }
                    return Math.max(expiringSoon, 2);
                }
            }
        }

        if (!isAlive()) {return 0;}

        // Periodic cleanup: remove 0-hop tunnels if we have multi-hop alternatives
        // This handles existing 0-hop tunnels that were in the pool before cleanup logic was added
        cleanupZeroHopTunnels();

        // Periodic cleanup: remove tunnels with duplicate peer sequences
        // This handles existing duplicates that were in the pool before detection
        cleanupDuplicatePeerTunnels();

        int wanted = getAdjustedTotalQuantity();
        boolean allowZeroHop = _settings.getAllowZeroHop();

        // Defensive building: Check if we need emergency tunnel building @since 0.9.68+
        // This triggers aggressive building when under attack or critically low on tunnels
        int defensiveBuilds = checkDefensiveBuilding(wanted);
        if (defensiveBuilds > 0) {
            return defensiveBuilds;
        }

        /**
         * This algorithm builds based on the previous average length of time it takes
         * to build a tunnel. This average is kept in the _buildRateName stat.
         * It is a separate stat for each type of pool, since in and out building use different methods,
         * as do exploratory and client pools,
         * and each pool can have separate length and length variance settings.
         * We add one minute to the stat for safety (two for exploratory tunnels).
         *
         * We linearly increase the number of builds per expiring tunnel from
         * 1 to PANIC_FACTOR as the time-to-expire gets shorter.
         *
         * The stat will be 0 for first 10m of uptime so we will use the older, conservative algorithm
         * below instead. This algorithm will take about 30m of uptime to settle down.
         * Or, if we are building more than 33% of the time something is seriously wrong,
         * we also use the conservative algorithm instead
         *
         **/

        final String rateName = buildRateName();

        // Compute the average time it takes us to build a single tunnel of this type.
        int avg = 0;
        RateStat rs = _context.statManager().getRate(rateName);
        if (rs == null) {
            // Create the RateStat here rather than at the top because
            // the user could change the length settings while running
            String name;
            if (_settings.isExploratory()) {name = "Exploratory tunnels";}
            else {
                name = _settings.getDestinationNickname();
                // Just strip HTML here rather than escape it everywhere in the console
                if (name != null) {name = DataHelper.stripHTML(name);}
                else {name = _settings.getDestination().toBase32();}
            }
            if (_settings.isExploratory()) {
            _context.statManager().createRequiredRateStat(rateName, (_settings.isInbound() ? "In " : "Out ") +
                                   "Tunnel build frequency [" + name + "]", "Tunnels [Exploratory]",
                                   new long[] {TUNNEL_LIFETIME });
            } else {
            _context.statManager().createRequiredRateStat(rateName, (_settings.isInbound() ? "In " : "Out ") +
                                   "Tunnel build frequency [" + name + "]", "Tunnels [Services]",
                                   new long[] {TUNNEL_LIFETIME });
            }
            rs = _context.statManager().getRate(rateName);
        }
        if (rs != null) {
            Rate r = rs.getRate(TUNNEL_LIFETIME);
            if (r != null) {avg = (int) (TUNNEL_LIFETIME * r.getAverageValue() / wanted);}
        }

        if (avg > 0 && avg < TUNNEL_LIFETIME / 3) { // if we're taking less than 200s per tunnel to build
            // Increase PANIC_FACTOR during attacks to build more aggressively
            boolean isUnderAttack = _context.profileOrganizer().isLowBuildSuccess();
            final int PANIC_FACTOR = isUnderAttack ? 8 : 4;  // how many builds to kick off when time gets short
            avg += 60*1000; // one minute safety factor
            if (_settings.isExploratory())
                avg += 60*1000; // two minute safety factor
            long now = _context.clock().now();

            int expireSoon = 0;
            int expireLater = 0;
            int expireTime[];
            int fallback = 0;
            _tunnelsLock.lock();
            try {
                expireTime = new int[_tunnels.size()];
                for (int i = 0; i < _tunnels.size(); i++) {
                    TunnelInfo info = _tunnels.get(i);
                    if (allowZeroHop || (info.getLength() > 1)) {
                        int timeToExpire = (int) (info.getExpiration() - now);
                        if (timeToExpire > 0 && timeToExpire < avg) {expireTime[expireSoon++] = timeToExpire;}
                        else {expireLater++;}
                    } else if (info.getExpiration() - now > avg) {fallback++;}
                }
            } finally {_tunnelsLock.unlock();}

            int inProgress;
            synchronized (_inProgress) {inProgress = _inProgress.size();}
            int remainingWanted = (wanted - expireLater) - inProgress;

            int rv = 0;
            int latesttime = 0;
            if (remainingWanted > 0) {
                if (remainingWanted > expireSoon) {
                    rv = PANIC_FACTOR * (remainingWanted - expireSoon);  // for tunnels completely missing
                    remainingWanted = expireSoon;
                }
                // add from 1 to PANIC_FACTOR builds, depending on how late it is
                // only use the expire times of the latest-expiring tunnels,
                // the other ones are extras
                for (int i = 0; i < remainingWanted; i++) {
                    int latestidx = 0;
                    // given the small size of the array this is efficient enough
                    for (int j = 0; j < expireSoon; j++) {
                        if (expireTime[j] > latesttime) {
                            latesttime = expireTime[j];
                            latestidx = j;
                        }
                    }
                    expireTime[latestidx] = 0;
                    if (latesttime > avg / 2) {rv += 1;}
                    else {rv += 2 + ((PANIC_FACTOR - 2) * (((avg / 2) - latesttime) / (avg / 2)));}
                }
            }

            if (rv > 0 && _log.shouldDebug()) {
                _log.debug("[" + toString() + "] Requested: " + rv + (allowZeroHop ? " (zero hop)" : "") +
                           " -> Average build time: " + avg + "ms");
            }
            _context.statManager().addRateData(rateName, rv + inProgress);
            return rv;
        }

        // Fixed, conservative algorithm - starts building 3 1/2 - 6m before expiration
        // (210 or 270s) + (0..90s random)
        // During attacks: start building earlier (6m instead of 4.5m) and build more aggressively
        boolean isUnderAttack = _context.profileOrganizer().isLowBuildSuccess();
        long expireAfter = _context.clock().now() + _expireSkew; // + _settings.getRebuildPeriod() + _expireSkew;
        int expire90s = 0;
        int expire150s = 0;
        int expire210s = 0;
        int expire270s = 0;
        int expire360s = 0; // Early warning bucket during attacks
        int expireLater = 0;

        int fallback = 0;
        _tunnelsLock.lock();
        try {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = _tunnels.get(i);
                // Skip tunnels that have failed completely - they don't count as usable
                if (info instanceof PooledTunnelCreatorConfig &&
                    ((PooledTunnelCreatorConfig)info).getTunnelFailed()) {
                    continue;
                }
                // CRITICAL: Don't count last-resort protected tunnels - they need replacement!
                // They are about to expire and shouldn't be counted as "stable" tunnels
                if (info instanceof PooledTunnelCreatorConfig &&
                    ((PooledTunnelCreatorConfig)info).isLastResort()) {
                    continue;
                }
                if (allowZeroHop || (info.getLength() > 1)) {
                    long timeToExpire = info.getExpiration() - expireAfter;
                    // Tunnels expiring in <= 3 minutes are considered "effectively expired"
                    // Do NOT count them - we need to build replacements
                    if (timeToExpire <= 180*1000) {
                        // Within 3 min - treat as expired, don't count
                    } else if (timeToExpire <= 270*1000) {expire270s++;}
                    else if (isUnderAttack && timeToExpire <= 360*1000) {expire360s++;}
                    else {expireLater++;}
                } else if (info.getExpiration() > expireAfter) {fallback++;}
            }
        } finally {_tunnelsLock.unlock();}

        int inProgress = 0;
        synchronized (_inProgress) {
            inProgress = _inProgress.size();
            for (int i = 0; i < inProgress; i++) {
                PooledTunnelCreatorConfig cfg = _inProgress.get(i);
                if (cfg.getLength() <= 1) {fallback++;}
            }
        }

        int rv = countHowManyToBuild(isUnderAttack, allowZeroHop, expire90s, expire150s, expire210s, expire270s, expire360s,
                                     expireLater, wanted, inProgress, fallback);
        _context.statManager().addRateData(rateName, (rv > 0 || inProgress > 0) ? 1 : 0);
        return rv;
    }

    /**
     * Helper function for the old conservative algorithm.
     * This is the big scary function determining how many new tunnels we want to try to build at this
     * point in time, as used by the BuildExecutor
     *
     * @param isUnderAttack whether we're currently under attack (build success < 40%)
     * @param allowZeroHop do we normally allow zero hop tunnels?  If true, treat fallback tunnels like normal ones
     * @param earliestExpire how soon do some of our usable tunnels expire, or, if we are missing tunnels, -1
     * @param usable how many tunnels will be around for a while (may include fallback tunnels)
     * @param wantToReplace how many tunnels are still usable, but approaching unusability
     * @param standardAmount how many tunnels we want to have, in general
     * @param inProgress how many tunnels are being built for this pool right now (may include fallback tunnels)
     * @param fallback how many zero hop tunnels do we have, or are being built
     */
    private int countHowManyToBuild(boolean isUnderAttack, boolean allowZeroHop, int expire90s, int expire150s, int expire210s,
                                     int expire270s, int expire360s, int expireLater, int standardAmount, int inProgress, int fallback) {
        // Emergency: if pool is empty, always build minimum tunnels
        // This handles the case where all expiration buckets are 0 and the algorithm
        // would otherwise request 0 builds
        int usableTunnels = expire90s + expire150s + expire210s + expire270s + expire360s + expireLater;
        if (usableTunnels <= 0 && standardAmount > 0) {
            return Math.max(2, standardAmount);
        }

        // Prevent pool collapse: if we have very few tunnels, ensure we always build at least 1
        // This ensures established pools never collapse to 0
        int totalTunnels = usableTunnels + inProgress;
        if (totalTunnels <= 1 && standardAmount > 0) {
            // Always build at least 1 more tunnel to prevent collapse
            int rv = 1;
            // Also ensure we have at least 2 when empty
            if (usableTunnels <= 0) {
                rv = Math.max(2, standardAmount);
            }
            if (_log.shouldInfo()) {
                _log.info("Preventing pool collapse for " + toString() + ": building " + rv + " (have " + usableTunnels + " + " + inProgress + " in progress)");
            }
            return rv;
        }

        // ADDITIONAL EMERGENCY: Check if tunnels are about to expire (within 90 seconds)
        // This catches edge cases where the bucket counting might miss critical timing
        if (expire90s > 0 || expire150s > 0) {
            // Tunnels expiring very soon - ensure we build at least 1
            if (totalTunnels < standardAmount) {
                int needed = standardAmount - totalTunnels;
                if (_log.shouldInfo()) {
                    _log.info("Emergency: tunnels expiring soon (" + expire90s + " in 90s, " + expire150s + " in 150s) for " + toString() +
                              ", building " + needed);
                }
                return needed;
            }
        }

        // Increase multipliers during attacks for more aggressive building
        int multiplier360 = isUnderAttack ? 2 : 1;  // 6min: normally 0, during attack 1x (early warning)
        int multiplier270 = isUnderAttack ? 2 : 1;  // 4.5min: 1x normal, 2x attack
        int multiplier210 = isUnderAttack ? 2 : 1; // 3.5min: 1x normal, 2x attack
        int multiplier150 = isUnderAttack ? 3 : 2; // 2.5min: 2x normal, 3x attack
        int multiplier90  = isUnderAttack ? 8 : 6; // Urgent: <90s: 6x normal, 8x attack (was 4x/5x)

        int rv = 0;
        int remainingWanted = standardAmount - expireLater;
        if (allowZeroHop) {remainingWanted -= fallback;}

        // Handle the new 360s bucket during attacks
        if (isUnderAttack) {
            for (int i = 0; i < expire360s && remainingWanted > 0; i++)
                remainingWanted--;
        } else {
            // Add 360s to expireLater for non-attack mode
            remainingWanted -= expire360s;
        }

        if (remainingWanted > 0) {
            // 1x the tunnels expiring between 3.5 and 2.5 minutes from now
            for (int i = 0; i < expire210s && remainingWanted > 0; i++) {remainingWanted--;}
            if (remainingWanted > 0) {
                // 2x the tunnels expiring between 2.5 and 1.5 minutes from now
                for (int i = 0; i < expire150s && remainingWanted > 0; i++) {remainingWanted--;}
                if (remainingWanted > 0) {
                    for (int i = 0; i < expire90s && remainingWanted > 0; i++) {remainingWanted--;}
                    if (remainingWanted > 0) {
                        for (int i = 0; i < expire90s && remainingWanted > 0; i++) {remainingWanted--;}
                        if (remainingWanted > 0) {
                            rv = (((expire270s > 0) && _context.random().nextBoolean()) ? 1 : 0);
                            rv += multiplier270 * expire270s;
                            rv += multiplier210 * expire210s;
                            rv += multiplier150 * expire150s;
                            rv += multiplier90 * expire90s;
                            rv += multiplier90 * expire90s;
                            rv += multiplier90 * remainingWanted;
                            rv -= inProgress;
                            rv -= expireLater;
                        } else {
                            rv = (((expire270s > 0) && _context.random().nextBoolean()) ? 1 : 0);
                            rv += multiplier270 * expire270s;
                            rv += multiplier210 * expire210s;
                            rv += multiplier150 * expire150s;
                            rv += multiplier90 * expire90s;
                            rv += multiplier90 * expire90s;
                            rv -= inProgress;
                            rv -= expireLater;
                        }
                    } else {
                        rv = (((expire270s > 0) && _context.random().nextBoolean()) ? 1 : 0);
                        rv += multiplier270 * expire270s;
                        rv += multiplier210 * expire210s;
                        rv += multiplier150 * expire150s;
                        rv += multiplier90 * expire90s;
                        rv -= inProgress;
                        rv -= expireLater;
                    }
                } else {
                    rv = (((expire270s > 0) && _context.random().nextBoolean()) ? 1 : 0);
                    rv += multiplier270 * expire270s;
                    rv += multiplier210 * expire210s;
                    rv += multiplier150 * expire150s;
                    rv -= inProgress;
                    rv -= expireLater;
                }
            } else {
                rv = (((expire270s > 0) && _context.random().nextBoolean()) ? 1 : 0);
                rv += multiplier270 * expire270s;
                rv += multiplier210 * expire210s;
                rv -= inProgress;
                rv -= expireLater;
            }
        } else {
            rv = (((expire270s > 0) && _context.random().nextBoolean()) ? 1 : 0);
            rv += multiplier270 * expire270s;
            rv -= inProgress;
            rv -= expireLater;
        }
        // yes, the above numbers and periods are completely arbitrary.  suggestions welcome

        if (allowZeroHop && (rv > standardAmount)) {rv = standardAmount;}

        if (rv + inProgress + expireLater + fallback > 4*standardAmount) {
            rv = 4*standardAmount - inProgress - expireLater - fallback;
        }

        long lifetime = getLifetime();
        if ((lifetime < 60*1000) && (rv + inProgress + fallback >= standardAmount)) {
            rv = standardAmount - inProgress - fallback;
        }

        if (rv > 0 && _log.shouldDebug()) {
            _log.debug(toString() + " (Up: " + (lifetime / 1000) + "s). Allow Zero Hop? " + allowZeroHop +
                       "\n* Count: [rv] " + rv + "; [30s] " + expire90s + "; [90s] " + expire90s + "; [150s] " + expire150s + "; [210s] "
                       + expire210s + "; [270s] " + expire270s + "; [later] " + expireLater
                       + "; [std] " + standardAmount + "; [inProgress] " + inProgress + "; [fallback] " + fallback);
        }
        if (rv < 0) {return 0;}

        // EMERGENCY BYPASS: If pool is critically low (<=2 usable tunnels), bypass concurrent build limits
        // This prevents pool collapse during critical periods when we need to build aggressively
        int emergencyUsableTunnels = 0;
        long now = _context.clock().now();
        long threeMinFromNow = now + 3*60*1000;
        _tunnelsLock.lock();
        try {
            for (TunnelInfo t : _tunnels) {
                if (t.getExpiration() > threeMinFromNow) {
                    emergencyUsableTunnels++;
                }
            }
        } finally {_tunnelsLock.unlock();}

        // Cap builds per direction to prevent overcompensation during failures
        // This limits concurrent build requests for each tunnel pool/direction
        int maxAllowed = MAX_CONCURRENT_BUILDS_PER_DIRECTION;

        // Emergency: increase limit if pool is critically low
        if (emergencyUsableTunnels <= 2) {
            maxAllowed = MAX_CONCURRENT_BUILDS_PER_DIRECTION * 2; // Double the limit in emergencies
            if (_log.shouldInfo()) {
                _log.info(toString() + " EMERGENCY: Pool critically low (" + emergencyUsableTunnels + "), increasing max builds to " + maxAllowed);
            }
        }

        int currentConcurrent = _settings.isInbound() ? _concurrentInboundBuilds.get() : _concurrentOutboundBuilds.get();
        int availableSlots = maxAllowed - currentConcurrent;
        if (availableSlots < 0) {availableSlots = 0;}
        if (rv > availableSlots) {
            if (_log.shouldDebug()) {
                _log.debug(toString() + " limiting builds from " + rv + " to " + availableSlots +
                           " (current concurrent: " + currentConcurrent + " for " +
                           (_settings.isInbound() ? "inbound" : "outbound") + ")");
            }
            rv = availableSlots;
        }

        return rv;
    }

    /**
     *  This only sets the peers and creation/expiration times in the configuration.
     *  For the crypto, see BuildRequestor and BuildMessageGenerator.
     *
     *  @return null on failure
     */
    PooledTunnelCreatorConfig configureNewTunnel() {return configureNewTunnel(false);}

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

        // Enforce minimum 2 hops for client pools unless explicitly configured shorter
        // @since 0.9.70
        int minLength = 2;
        if (settings.isExploratory() || settings.getLength() <= 1 || settings.getAllowZeroHop()) {
            minLength = 1; // Allow 1-hop for exploratory or explicitly short-configured
        }

        if (!forceZeroHop) {
            int len = settings.getLengthOverride();
            if (len < 0) {len = settings.getLength();}
            // Enforce minimum length for client pools
            if (len < minLength) {len = minLength;}
            if (len > 0 && (!settings.isExploratory()) && _context.random().nextInt(4) < 3) { // 75%
                // Look for a tunnel to reuse, if the right length and expiring soon.
                // Ignore variance for now.
                len++; // us
                _tunnelsLock.lock();
                try {
                    // Simple 5-minute threshold for tunnel reuse
                    for (TunnelInfo ti : _tunnels) {
                        if (ti.getLength() >= len && ti.getExpiration() < now + 5*60*1000 && !ti.wasReused()) {
                            ti.setReused();
                            len = ti.getLength();
                            peers = new ArrayList<Hash>(len);
                            for (int i = len - 1; i >= 0; i--) {peers.add(ti.getPeer(i));}
                            break;
                        }
                    }
                } finally {_tunnelsLock.unlock();}
            }
            if (peers == null) {
                setLengthOverride();

                // Collect first peers from existing tunnels for diversity @since 0.9.68+
                Set<Hash> firstPeers = new java.util.HashSet<>();
                // Collect last peers from existing tunnels for diversity
                Set<Hash> lastPeers = new java.util.HashSet<>();
                _tunnelsLock.lock();
                try {
                    for (TunnelInfo t : _tunnels) {
                        if (t.getLength() > 1) {
                            Hash firstPeer = t.getPeer(0);
                            if (firstPeer != null) {
                                firstPeers.add(firstPeer);
                            }
                            Hash lastPeer = t.getPeer(t.getLength() - 1);
                            if (lastPeer != null) {
                                lastPeers.add(lastPeer);
                            }
                        }
                    }
                } finally {_tunnelsLock.unlock();}
                // Temporarily set exclusions in settings
                Set<Hash> oldFirstExclusions = settings.getFirstPeerExclusions();
                Set<Hash> oldLastExclusions = settings.getLastPeerExclusions();
                settings.setFirstPeerExclusions(firstPeers);
                settings.setLastPeerExclusions(lastPeers);

                peers = _peerSelector.selectPeers(settings);

                // Restore old exclusions
                settings.setFirstPeerExclusions(oldFirstExclusions);
                settings.setLastPeerExclusions(oldLastExclusions);
            }

            if ((peers == null) || (peers.isEmpty())) {
                // No peers to build the tunnel with, and the pool is refusing 0 hop tunnels
                long uptime = _context.router().getUptime();
                // Progressive fallback under attack conditions @since 0.9.68+
                double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
                // Under attack if: low success rate OR 0% success after 3min uptime (botnet attack from startup)
                boolean isUnderAttack = buildSuccess < 0.40;

                if (isUnderAttack && uptime > 3*60*1000) {
                    if (_log.shouldWarn()) {
                        _log.warn("Under probable attack (" + (int)(buildSuccess * 100) +
                                  "% build success) -> Attempting emergency peer selection...");
                    }

                    // Try emergency selection with relaxed constraints
                    TunnelPoolSettings emergencySettings = new TunnelPoolSettings(settings.isInbound());
                    emergencySettings.setLength(settings.getLength());
                    emergencySettings.setLengthVariance(settings.getLengthVariance());
                    emergencySettings.setAllowZeroHop(settings.getAllowZeroHop());

                    // Try again with relaxed IP restriction (override the setting)
                    // Note: we can't directly set IP restriction on the settings object
                    // but the peer selectors already handle the < 40% case internally

                    List<Hash> emergencyPeers = _peerSelector.selectPeers(emergencySettings);

                    if (emergencyPeers != null && !emergencyPeers.isEmpty()) {
                        // Filter for transport compatibility during attacks
                        // Peers with incompatible transports (mask 4 vs our mask 17) will never work
                        List<Hash> compatiblePeers = new java.util.ArrayList<>();
                        for (Hash peer : emergencyPeers) {
                            // Check if we can actually connect to this peer
                            // canConnect returns true if there's ANY common transport protocol
                            // Use peerSelector's canConnect method (from=our router, to=peer)
                            if (_peerSelector.canConnect(_context.routerHash(), peer)) {
                                compatiblePeers.add(peer);
                            }
                        }

                        if (!compatiblePeers.isEmpty()) {
                            peers = compatiblePeers;
                            if (_log.shouldWarn()) {
                                _log.warn("Emergency peer selection successful -> Found " + peers.size() +
                                          " compatible peers out of " + emergencyPeers.size());
                            }
                        }
                    }
                }

                if (peers == null) {
                    if (_log.shouldWarn() && uptime > 3*60*1000) {
                        _log.warn("No compatible peers found for new tunnel");
                    }
                } else if (peers.isEmpty()) {
                    if (_log.shouldWarn() && uptime > 3*60*1000) {
                        _log.warn("No compatible peers found for new tunnel -> Apparently we have no peers to choose from!");
                    }
                }

                if (peers == null || peers.isEmpty()) {
                    // CRITICAL FIX: Allow emergency 0-hop fallback for exploratory pools
                    // when no peers are available. This prevents pool collapse.
                    // Only allow this for exploratory pools and only when we've tried everything else.
                    if (settings.isExploratory() && settings.getAllowZeroHop()) {
                        if (_log.shouldWarn()) {
                            _log.warn("No peers available for exploratory pool - using emergency 0-hop fallback");
                        }
                        peers = Collections.singletonList(_context.routerHash());
                    } else if (settings.isExploratory()) {
                        // Even if zero hop not normally allowed, allow it as emergency fallback
                        if (_log.shouldWarn()) {
                            _log.warn("No peers available for exploratory pool - EMERGENCY enabling 0-hop");
                        }
                        settings.setAllowZeroHop(true);
                        peers = Collections.singletonList(_context.routerHash());
                    } else {
                        // CRITICAL FIX: Client pool emergency 0-hop fallback
                        // Allow 0-hop for client pools as emergency fallback when no peers available
                        // This prevents complete pool failure during network stress or attacks
                        if (_log.shouldWarn()) {
                            _log.warn("No peers available for client pool - EMERGENCY enabling 0-hop to prevent pool collapse");
                        }
                        settings.setAllowZeroHop(true);
                        peers = Collections.singletonList(_context.routerHash());
                    }
                }
            }
        } else {peers = Collections.singletonList(_context.routerHash());}

        // EMERGENCY HARD CAP: Prevent tunnel build flood
        synchronized (_inProgress) {
            if (_inProgress.size() >= 1000) {
                if (_log.shouldInfo()) {
                    _log.info("Skipping tunnel build -> " + _inProgress.size() + " already in progress...");
                }
                return null;
            }
        }

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

        if (_log.shouldDebug()) {_log.debug("Tunnel Pool created \n* Peers: " + peers + cfg);}
        synchronized (_inProgress) {_inProgress.add(cfg);}
        buildStarted(_settings.isInbound());
        return cfg;
    }

    /**
     * Nuclear cleanup: Remove expired/stale configs from _inProgress.
     * Called periodically to prevent memory leak when buildComplete isn't reached.
     * @return number of stale configs removed
     */
    int cleanupInProgress() {
        int removed = 0;
        long now = _context.clock().now();
        boolean isUnderAttack = _context.profileOrganizer().isLowBuildSuccess();

        // CRITICAL: Drain removal queue to prevent memory leak
        // The removal queue can grow unbounded if jobs are starved
        int drained = drainRemovalQueue();
        if (drained > 0 && _log.shouldInfo()) {
            _log.info("Drained " + drained + " tunnels from removal queue");
        }

        // Collect configs to clean up BEFORE removing from _inProgress
        List<PooledTunnelCreatorConfig> toCleanup = new ArrayList<>();

        // Maximum time a tunnel build can be in progress before being considered stale
        final long STALE_BUILD_TIMEOUT = isUnderAttack ? 15 * 1000 : 10 * 1000;

        synchronized (_inProgress) {
            // HARD CAP: Make dynamic based on pool size to prevent memory growth
            int dynamicCap = Math.min(16, getAdjustedTotalQuantity() * 3 / 2);
            while (_inProgress.size() > dynamicCap) {
                if (!_inProgress.isEmpty()) {
                    PooledTunnelCreatorConfig cfg = _inProgress.remove(0);
                    toCleanup.add(cfg);
                    removed++;
                } else {
                    break;
                }
            }

            // TTL cleanup - remove configs whose tunnels have already expired
            // This catches builds that got stuck and never completed
            Iterator<PooledTunnelCreatorConfig> it = _inProgress.iterator();
            while (it.hasNext()) {
                PooledTunnelCreatorConfig cfg = it.next();
                // Clean up by expiration time OR by staleness (build taking too long)
                long expiration = cfg.getExpiration() + 60 * 1000; // 1 minute grace period
                // Get creation time from first hop config
                long creation = 0;
                try {
                    HopConfig hop = cfg.getConfig(0);
                    if (hop != null) {
                        creation = hop.getCreation();
                    }
                } catch (Exception e) {
                    // Ignore
                }
                long age = (creation > 0) ? (now - creation) : Long.MAX_VALUE;
                if (expiration < now || age > STALE_BUILD_TIMEOUT) {
                    it.remove();
                    toCleanup.add(cfg);
                    removed++;
                }
            }
        }

        // Clean up resources for removed configs
        for (PooledTunnelCreatorConfig cfg : toCleanup) {
            _context.tunnelDispatcher().remove(cfg, "in-progress expired");
            _manager.removeFromExpiration(cfg);
            Long tunnelKey = ExpireLocalTunnelsJob.getTunnelKey(cfg);
            TestJob.invalidate(tunnelKey);
        }

        if (removed > 0 && _log.shouldInfo()) {
            _log.info("Removed " + removed + " failed or expired tunnels -> Remaining: " + _inProgress.size());
        }

        return removed;
    }

    /**
     * Cleanup expired tunnels from _tunnels that were never added to expiration tracking.
     * This handles the case where tunnels expired but weren't tracked due to the old 3000 limit bug.
     * @return number of expired tunnels removed
     */
    int cleanupExpiredTunnels() {
        int removed = 0;
        int skipped = 0;
        long now = _context.clock().now();
        List<PooledTunnelCreatorConfig> expiredConfigs = new ArrayList<>();

        _tunnelsLock.lock();
        try {
            // First pass: count non-expired tunnels
            int nonExpired = 0;
            for (TunnelInfo t : _tunnels) {
                if (t.getExpiration() >= now) nonExpired++;
            }

            // FIX: We can remove ALL expired tunnels if there are still some non-expired ones
            // The "keep last resort" logic only applies when we'd be removing the LAST tunnel

            Iterator<TunnelInfo> it = _tunnels.iterator();
            while (it.hasNext()) {
                TunnelInfo info = it.next();
                if (info instanceof PooledTunnelCreatorConfig) {
                    PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
                    // Remove tunnels that have already expired
                    if (cfg.getExpiration() < now) {
                        // NEVER remove the last tunnel - keep it as last resort
                        // But only if ALL tunnels are expired (nonExpired would be 0 after removal)
                        if (nonExpired <= 1) {
                            cfg.setLastResort();
                            triggerReplacementBuild();
                            skipped++;
                            if (_log.shouldWarn()) {
                                _log.warn("Keeping expired tunnel as last resort in " + toString() + " -> Triggering replacement...");
                            }
                            // Don't remove it from the list - it's our last resort
                            continue;
                        }
                        // We have other non-expired tunnels, safe to remove this one
                        it.remove();
                        expiredConfigs.add(cfg);
                        removed++;
                        nonExpired--;
                        if (_log.shouldDebug()) {
                            _log.debug("Removing expired tunnel from pool: " + cfg);
                        }
                    }
                }
            }
        } finally {_tunnelsLock.unlock();}

        if (removed > 0 || skipped > 0) {
            if (_log.shouldInfo()) {
                _log.info("Cleaned up " + removed + " expired tunnels from pool" + (skipped > 0 ? ", kept " + skipped + " as last resort" : ""));
            }
            // Cleanup the dispatcher maps for the removed tunnels
            for (PooledTunnelCreatorConfig cfg : expiredConfigs) {
                _context.tunnelDispatcher().remove(cfg, "expired");
                _manager.removeFromExpiration(cfg);
                Long tunnelKey = ExpireLocalTunnelsJob.getTunnelKey(cfg);
                TestJob.invalidate(tunnelKey);
            }
        }

        return removed;
    }

    /**
     * Drain the removal queue to prevent unbounded memory growth.
     * Emergency drain when queue gets too large - processes tunnels directly.
     * @return number of items drained
     */
    private int drainRemovalQueue() {
        int drained = 0;
        int maxDrain = 1000;
        while (drained < maxDrain) {
            TunnelInfo ti = _removalQueue.poll();
            if (ti == null) break;
            // NEVER remove the last tunnel
            if (isLastResortTunnel(ti)) {
                if (_log.shouldWarn()) {
                    _log.warn("Skipping queue drain of last resort tunnel: " + ti + " in " + toString());
                }
                continue;
            }
            if (ti instanceof PooledTunnelCreatorConfig) {
                PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) ti;
                _context.tunnelDispatcher().remove(cfg, "queue drain");
                _manager.removeFromExpiration(cfg);
                Long tunnelKey = ExpireLocalTunnelsJob.getTunnelKey(cfg);
                TestJob.invalidate(tunnelKey);
            }
            drained++;
        }
        _removalJobScheduled = false;
        return drained;
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
        // Prevent duplicate calls - only process the first call
        if (!cfg.markBuildCompleteCalled()) {
            if (_log.shouldDebug()) {
                _log.debug("Ignoring duplicate buildComplete call for " + cfg);
            }
            return;
        }

        // Always remove from inProgress to prevent memory leaks
        synchronized (_inProgress) {_inProgress.remove(cfg);}

        if (cfg.getTunnelPool() != this) {
            _log.error("Wrong pool " + cfg + " for " + this, new Exception());
            return;
        }

        buildFinished(_settings.isInbound());

        switch (result) {
            case SUCCESS:
                _consecutiveBuildTimeouts.set(0);
                addTunnel(cfg);
                updatePairedProfile(cfg, true);
                break;

            case REJECT:
            case BAD_RESPONSE:
                _consecutiveBuildTimeouts.set(0);
                cleanupFailedBuild(cfg);
                updatePairedProfile(cfg, true);
                break;

            case DUP_ID:
                _consecutiveBuildTimeouts.set(0);
                cfg.setDuplicate();
                cleanupFailedBuild(cfg);
                break;

            case TIMEOUT:
                _consecutiveBuildTimeouts.incrementAndGet();
                if (_log.shouldDebug()) {
                    _log.debug("buildComplete TIMEOUT - cleaning up for " + toString());
                }
                cleanupFailedBuild(cfg);
                updatePairedProfile(cfg, false);
                break;

            case OTHER_FAILURE:
            default:
                cleanupFailedBuild(cfg);
                break;
        }
    }

    /**
     * Clean up resources for a failed/unadded tunnel build.
     * NEVER removes a tunnel if it would leave the pool with 0 tunnels.
     * @param cfg the failed tunnel config
     */
    private void cleanupFailedBuild(PooledTunnelCreatorConfig cfg) {
        // Count ONLY non-expired tunnels - expired ones can't handle traffic
        int currentTunnels = 0;
        long now = _context.clock().now();
        _tunnelsLock.lock();
        try {
            for (TunnelInfo t : _tunnels) {
                if (t.getExpiration() > now) currentTunnels++;
            }
        } finally {_tunnelsLock.unlock();}

        // Check if there are already replacement builds in progress
        int inProgress = 0;
        synchronized (_inProgress) {
            inProgress = _inProgress.size();
        }

        // CRITICAL FIX: If pool is EMPTY (0 non-expired tunnels), ALWAYS keep failed tunnel as last resort
        // This prevents collapse when all builds keep failing
        if (currentTunnels <= 0) {
            if (_log.shouldWarn()) {
                _log.warn("POOL COLLAPSE PREVENTION: Keeping failed tunnel as last resort in " + toString() +
                          " (tunnels=" + currentTunnels + ", inProgress=" + inProgress + ")");
            }
            cfg.setLastResort();
            // CRITICAL: Add the failed tunnel to the pool so tunnels > 0!
            // Without this, countHowManyToBuild sees tunnels=0 and keeps building
            _tunnelsLock.lock();
            try { _tunnels.add(cfg); }
            finally {_tunnelsLock.unlock();}
            triggerReplacementBuild();
            return;
        }

        // Keep failed tunnel as last resort if pool is low and no replacements building
        if (currentTunnels <= 1) {
            if (inProgress == 0) {
                // No replacements in progress - keep failed tunnel as last resort
                if (_log.shouldWarn()) {
                    _log.warn("Keeping failed tunnel as last resort in " + toString() +
                              " (Total: " + currentTunnels + ") -> Triggering replacement...");
                }
                cfg.setLastResort();
                // CRITICAL: Add to pool so countHowManyToBuild sees tunnels > 0
                _tunnelsLock.lock();
                try { _tunnels.add(cfg); }
                finally {_tunnelsLock.unlock();}
                triggerReplacementBuild();
            } else {
                // Builds in progress - let them complete before triggering more
                if (_log.shouldDebug()) {
                    _log.debug("Build failed in " + toString() +
                               " but " + inProgress + " replacements in progress");
                }
            }
            return;
        }

        // Pool has tunnels - clean up the failed one
        _context.tunnelDispatcher().remove(cfg, "build failed");
        _manager.removeFromExpiration(cfg);
        Long tunnelKey = ExpireLocalTunnelsJob.getTunnelKey(cfg);
        TestJob.invalidate(tunnelKey);
    }

    /**
     *  Get the count of consecutive tunnel build timeouts.
     *  @return the number of consecutive build timeouts
     *  @since 0.9.53
     */
    int getConsecutiveBuildTimeouts() {return _consecutiveBuildTimeouts.get();}

    /**
     *  Reset consecutive timeout counter when tunnels are working properly.
     *  This prevents excessive backoff on firewalled routers after recovery.
     *  Only resets if we have a significant number of consecutive timeouts
     *  to avoid flapping during normal operation.
     *  @since 0.9.53
     */
    private void resetConsecutiveTimeoutsOnSuccess() {
        int current = _consecutiveBuildTimeouts.get();
        // Reset after 3+ consecutive successes to recover faster from network issues
        // Previously was 8, which was too conservative
        if (current >= 3) {
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
           paired = (PooledTunnelCreatorConfig) pool.getTunnel(pairedGW);
       }
       if (paired != null && paired.getLength() > 1) {
            if (success) {
                long requestedOn = cfg.getExpiration() - 10*60*1000;
                int rtt = (int) (_context.clock().now() - requestedOn);
                paired.testSuccessful(rtt);
                // Remove slow tunnels after successful test to maintain efficient pool
                removeSlowTunnels();
            } else {paired.tunnelFailed();}
        }
     }

    /**
     * Remove tunnels that are significantly slower than the pool average.
     * This is called after a successful test to proactively remove slow tunnels
     * without marking them as failed, keeping only efficient tunnels in the pool.
     * Only removes a tunnel if we have adequate backup (>=2 good tunnels, or >=1 if under attack).
     * @since 0.9.68+
     */
    private void removeSlowTunnels() {
        List<TunnelInfo> tunnels = listTunnels();
        // Get the configured quantity for this pool
        int configuredQuantity = _settings.getQuantity();
        // Only remove slow tunnels if we have more than the configured minimum
        if (tunnels.size() <= configuredQuantity) return;

        // Calculate average latency per hop for tunnels with valid latency data
        long totalLatency = 0;
        int countWithLatency = 0;
        for (TunnelInfo ti : tunnels) {
            int latency = ti.getLastLatency();
            if (latency > 0) {
                int latencyPerHop = latency / ti.getLength();
                totalLatency += latencyPerHop;
                countWithLatency++;
            }
        }

        if (countWithLatency < 2) return;  // Need at least 2 tunnels with data to compare

        long avgLatencyPerHop = totalLatency / countWithLatency;
        if (avgLatencyPerHop <= 0) return;

        // Threshold: remove if > 2x average latency per hop
        long threshold = avgLatencyPerHop * 2;

        // Count good tunnels (below threshold or no latency data yet)
        int goodCount = 0;
        TunnelInfo slowest = null;
        long slowestLatency = -1;
        for (TunnelInfo ti : tunnels) {
            int latency = ti.getLastLatency();
            if (latency <= 0) {
                goodCount++;  // Assume untested tunnels are okay
            } else {
                int latencyPerHop = latency / ti.getLength();
                if (latencyPerHop <= threshold) {
                    goodCount++;
                } else if (latencyPerHop > slowestLatency) {
                    slowestLatency = latencyPerHop;
                    slowest = ti;
                }
            }
        }

        if (slowest == null) return;

        // Determine minimum required based on attack status
        boolean isUnderAttack = _context.profileOrganizer().isLowBuildSuccess();
        int minRequired = isUnderAttack ? 2 : 2;

        if (goodCount >= minRequired && slowest != null) {
            // Don't remove tunnels that are actively being used - check recent activity
            // This prevents disconnects for active connections (e.g., IRC)
            if (slowest instanceof PooledTunnelCreatorConfig) {
                PooledTunnelCreatorConfig ptcc = (PooledTunnelCreatorConfig) slowest;
                // Consider active if used in last 60 seconds or has recent messages
                if (ptcc.isRecentlyActive(60*1000)) {
                    if (_log.shouldInfo()) {
                        _log.info("Not removing slow tunnel " + slowest + " - actively used within last 60s");
                    }
                    return;
                }
            }
            if (_log.shouldInfo()) {
                _log.info("Removing slow tunnel " + slowest + " (latency/hop: " + slowestLatency + "ms, pool avg: " + avgLatencyPerHop + "ms/hop)");
            }
            removeTunnel(slowest);
        }
    }

    /**
     * Suppress timeout warning spam when we have adequate tunnels
     * Enhanced to be much more aggressive about suppression to eliminate log spam
     */
    private boolean shouldSuppressTimeoutWarning() {
        long uptime = _context.router().getUptime();
        if (uptime < STARTUP_TIME) {
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
                _log.debug("Enhanced tunnel availability check for " + this + ": usable_inbound=" + usableInbound +
                          " usable_outbound=" + usableOutbound + " recent_success=" + hasRecentSuccess +
                          " failures=" + _consecutiveBuildTimeouts.get());
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
                _log.debug("Enhanced exploratory tunnel availability check for " + this + ": usable=" + usable +
                          " recent_success=" + hasRecentSuccess + " failures=" + _consecutiveBuildTimeouts.get());
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
     * Suppress "no tunnels available" warning spam with rate limiting
     * Uses adaptive suppression between 5 and 10 minutes based on failures
     */
    private boolean shouldLogNoTunnelsWarning() {
        long uptime = _context.router().getUptime();
        if (uptime < STARTUP_TIME) {
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
     * Check if the destination is reachable by looking up its LeaseSet.
     * Uses the correct NetDB context for local destinations.
     * @return true if the destination has a LeaseSet in the local NetDB
     */
    private boolean isDestinationReachable() {
        if (_settings.isExploratory()) {
            return true;
        }

        Hash destHash = _settings.getDestination().calculateHash();
        boolean isLocal = _context.clientManager().isLocal(destHash);
        if (isLocal) {
            return true;
        }

        // Log when a client pool's destination is not recognized as local
        // This could indicate a registration issue
        if (_log.shouldWarn()) {
            _log.warn("Client pool " + toString() + " destination not recognized as local by clientManager" +
                      " (destHash: " + destHash.toBase64().substring(0, 6) + ")" +
                      " - checking main NetDB for LeaseSet");
        }

        boolean hasLeaseSet = _context.netDb().lookupLeaseSetLocally(destHash) != null;

        if (!hasLeaseSet && _log.shouldDebug()) {
            _log.debug("Destination " + toString() + " has no LeaseSet in local network DB (local: " + isLocal + ")");
        }

        return hasLeaseSet;
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
            // Skip tunnels that have failed completely
            if (info instanceof PooledTunnelCreatorConfig &&
                ((PooledTunnelCreatorConfig)info).getTunnelFailed()) {
                continue;
            }
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
                rv.append("[").append(_settings.getDestination().toBase64().substring(0,6)).append("]");
            }
            return rv.toString();
        }
    }

}