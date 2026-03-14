package net.i2p.router.tunnel.pool;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.stat.RateConstants;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
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

    private static final int TUNNEL_LIFETIME = 10*60*1000;
    /** if less than one success in this many, reduce quantity (exploratory only) */
    private static final int BUILD_TRIES_QUANTITY_OVERRIDE = 12;
    /** if less than one success in this many, reduce length (exploratory only) */
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_1 = 10;
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_2 = 12;
    private static final long STARTUP_TIME = 5*60*1000;


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
            synchronized (_tunnels) {ls = locked_buildNewLeaseSet();}
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
        _consecutiveBuildTimeouts.set(0);
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
        synchronized (_tunnels) {
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
                        if (info.getLength() > 1 && info.getExpiration() > now) {
                            // avoid outbound tunnels where the 1st hop is backlogged
                            if (_settings.isInbound() || !_context.commSystem().isBacklogged(info.getPeer(1))) {
                                // Reset counter on successful tunnel selection - indicates working tunnels
                                resetConsecutiveTimeoutsOnSuccess();
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
        }

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
        synchronized (_tunnels) {
            if (!_tunnels.isEmpty()) {
                // Use linear scan instead of sorting to avoid mutating list order
                // This prevents disrupting round-robin selection in selectTunnel()
                TunnelInfoComparator comparator = new TunnelInfoComparator(closestTo, avoidZeroHop);
                for (TunnelInfo info : _tunnels) {
                    if (info.getExpiration() > now) {
                        if (rv == null || comparator.compare(info, rv) < 0) {
                            rv = info;
                        }
                    }
                }
            }
            if (rv == null && _log.shouldWarn() && uptime > STARTUP_TIME) {
                shouldWarn = shouldLogNoTunnelsWarning();
            }
        }
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
        synchronized (_tunnels) {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = _tunnels.get(i);
                if (_settings.isInbound()) {
                    if (info.getReceiveTunnelId(0).equals(gatewayId)) {return info;}
                } else {
                    if (info.getSendTunnelId(0).equals(gatewayId)) {return info;}
                }
            }
        }
        return null;
    }

    /**
     * Return a list of tunnels in the pool
     *
     * @return A copy of the list of TunnelInfo objects
     */
    public List<TunnelInfo> listTunnels() {
        synchronized (_tunnels) {return new ArrayList<TunnelInfo>(_tunnels);}
    }

    /**
     *  Do we really need more fallbacks?
     *  Used to prevent a zillion of them.
     *  Does not check config, only call if config allows zero hop.
     *  @return true if more fallback tunnels are needed
     */
    boolean needFallback() {
        long exp = _context.clock().now() + 120*1000;
        synchronized (_tunnels) {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = _tunnels.get(i);
                if (info.getLength() <= 1 && info.getExpiration() > exp) {return false;}
            }
        }
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
            if (fails > 12) {
                // Linear backoff: reduce by 50% after 12 failures
                int reductionFactor = 2; // Max 2x reduction

                // Check if router is firewalled using status check
                boolean isFirewalled = _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.REJECT_UNSOLICITED ||
                                   _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_OK ||
                                   _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_UNKNOWN ||
                                   _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_OK_IPV6_FIREWALLED ||
                                   _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_UNKNOWN_IPV6_FIREWALLED ||
                                   _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_DISABLED_IPV6_FIREWALLED;

                int minTunnels = isFirewalled ? 3 : 1; // Keep 6 tunnels for firewalled routers

                rv = Math.max(minTunnels, _settings.getTotalQuantity() / reductionFactor);

                // Additional safety: never reduce below 1 for non-firewalled, 3 for firewalled
                if (isFirewalled && rv < 3) {rv = 3;}
                else if (rv < 1) {rv = 1;}

                if (fails >= 10 && _log.shouldWarn() && !shouldSuppressTimeoutWarning() && uptime > STARTUP_TIME) {
                    _log.warn("Limiting to " + rv + " tunnels after " + fails +
                              " consecutive build timeouts on " + this);
                }
            }
            // Ensure minimum of 3 tunnels or configured amount + 2 for redundancy
            // Exception: 0-hop configurations return 1
            // Only apply minimum cap when NOT under failure backoff to allow congestion response
            if (!(_settings.getLength() == 0 && _settings.getLengthVariance() == 0)) {
                if (fails <= 12) {
                    int minWanted = Math.max(3, _settings.getTotalQuantity() + 2);
                    if (rv < minWanted) {
                        rv = minWanted;
                    }
                }
            }
            return rv;
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
        // Ensure minimum of 3 tunnels or configured amount + 2 for redundancy
        // Exception: 0-hop configurations return 1
        if (!(_settings.getLength() == 0 && _settings.getLengthVariance() == 0)) {
            int minWanted = Math.max(3, _settings.getTotalQuantity() + 2);
            if (rv < minWanted) {
                rv = minWanted;
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

    /** duplicate of size(), let's pick one
     *  @return the number of tunnels in the pool
     */
    int getTunnelCount() {return size();}

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
        synchronized (_tunnels) {return _tunnels.size();}
    }

    /**
     *  @return the number of valid (non-failed, not expired) tunnels in the pool
     *  @since 0.9.68+
     */
    public int getValidTunnelCount() {
        int count = 0;
        long now = _context.clock().now();
        synchronized (_tunnels) {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = _tunnels.get(i);
                // Skip failed tunnels - they are not viable
                if (info.getTunnelFailed() ||
                    info.getTestStatus() == net.i2p.router.TunnelTestStatus.FAILED ||
                    info.getConsecutiveFailures() > 1) {
                    continue;
                }
                // Skip tunnels that have expired (timeLeft <= 0)
                // This matches the display logic in TunnelRenderer
                long timeLeft = info.getExpiration() - now;
                if (timeLeft <= 0) {
                    continue;
                }
                count++;
            }
        }
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
     *  @return the number of builds cancelled
     *  @since 0.9.68
     */
    public int cancelExcessInProgress(int maxAllowed) {
        int cancelled = 0;
        synchronized (_inProgress) {
            while (_inProgress.size() > maxAllowed && !_inProgress.isEmpty()) {
                PooledTunnelCreatorConfig cfg = _inProgress.remove(_inProgress.size() - 1);
                if (_log.shouldWarn()) {
                    _log.warn("Cancelling excess tunnel build: " + cfg);
                }
                cancelled++;
            }
        }
        return cancelled;
    }

    /**
     *  Track recently-added tunnel IDs to prevent duplicates.
     *  Uses a simple sliding window based on expiration time.
     *  Window set to 10 minutes to handle slow tunnel builds.
     */
    private static final long RECENTLY_ADDED_WINDOW = 10 * 60 * 1000;
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
        synchronized (_tunnels) {
            // Also check for duplicate in existing pool (defense in depth)
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
                _tunnels.add(info);
                // Track this tunnel ID as recently added
                _recentlyAddedTunnels.put(gatewayId, now);
                if (_settings.isInbound() && !_settings.isExploratory()) {ls = locked_buildNewLeaseSet();}
            }
        }
        if (info.getExpiration() > now + 60*1000 && ls != null) {requestLeaseSet(ls);}
    }

    /**
     *  Remove a tunnel from the pool.
     *  @param info the tunnel to remove
     */
    void removeTunnel(TunnelInfo info) {
        if (_log.shouldDebug()) {_log.debug(toString() + " -> Removing tunnel " + info);}
        int remaining = 0;
        LeaseSet ls = null;
        synchronized (_tunnels) {
            boolean removed = _tunnels.remove(info);
            if (!removed) {return;}
            if (_settings.isInbound() && !_settings.isExploratory()) {
                ls = locked_buildNewLeaseSet();
            }
            remaining = _tunnels.size();
        }

        _manager.tunnelFailed();
        _lifetimeProcessed += info.getProcessedMessagesCount();
        updateRate();
        long lifetimeConfirmed = info.getVerifiedBytesTransferred();
        long lifetime = 10*60*1000;

        for (int i = 0; i < info.getLength(); i++) {
            _context.profileManager().tunnelLifetimePushed(info.getPeer(i), lifetime, lifetimeConfirmed);
        }
        if (_alive && _settings.isInbound() && !_settings.isExploratory()) {
            if (ls != null) {requestLeaseSet(ls);}
            else {
                if (_log.shouldWarn()) {
                    _log.warn(toString() + "\n* Unable to build a new LeaseSet on removal (" + remaining
                              + " remaining) -> Requesting a new tunnel...");
                }
                if (_settings.getAllowZeroHop()) {buildFallback();}
            }
        }

        if (getTunnelCount() <= 0 && !isAlive()) {
            // this calls both our shutdown() and the other one (inbound/outbound)
            // This is racy - see TunnelPoolManager
            _manager.removeTunnels(_settings.getDestination());
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
     *  Remove the tunnel from the pool.
     *  @param cfg the tunnel to remove
     */
    private void fail(TunnelInfo cfg) {
        LeaseSet ls = null;
        synchronized (_tunnels) {
            boolean removed = _tunnels.remove(cfg);
            if (!removed) {return;}
            if (_settings.isInbound() && !_settings.isExploratory()) {
                ls = locked_buildNewLeaseSet();
            }
        }

        if (_log.shouldWarn()) {_log.warn("Tunnel build failed -> " + cfg);}

        _manager.tunnelFailed();
        _lifetimeProcessed += cfg.getProcessedMessagesCount();
        updateRate();

        if (_settings.isInbound() && !_settings.isExploratory() && ls != null) {
            requestLeaseSet(ls);
        }
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
            synchronized (_tunnels) {ls = locked_buildNewLeaseSet();}
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
        long expireAfter = _context.clock().now() + 10*1000;

        TunnelInfo zeroHopTunnel = null;
        Lease zeroHopLease = null;
        TreeSet<Lease> leases = new TreeSet<Lease>(new LeaseComparator());
        for (int i = 0; i < _tunnels.size(); i++) {
            TunnelInfo tunnel = _tunnels.get(i);
            // Skip failed tunnels - they should not be included in LeaseSet
            if (tunnel.getTunnelFailed() ||
                tunnel.getTestStatus() == net.i2p.router.TunnelTestStatus.FAILED ||
                tunnel.getConsecutiveFailures() > 1) {
                continue;
            }
            if (tunnel.getExpiration() <= expireAfter) {continue;} // expires too soon, skip it

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
            // Bugfix
            // ExpireJob reduces the expiration, which causes a 2nd leaseset with the same lease
            // to have an earlier expiration, so it isn't stored.
            // Get the "real" expiration from the gateway hop config,
            // HopConfig expirations are the same as the "real" expiration and don't change
            // see configureNewTunnel()
            long realExpiration = tunnel.getExpiration();
            if (tunnel instanceof TunnelCreatorConfig) {
                realExpiration = ((TunnelCreatorConfig) tunnel).getConfig(0).getExpiration();
            }
            lease.setEndDate(realExpiration);
            lease.setTunnelId(inId);
            lease.setGateway(gw);
            leases.add(lease);
            // Remember in case we want to remove it for a later-expiring zero-hopper
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
     *  Gather the data to see how many tunnels to build, and then actually compute that value.
     *  Now handled by BuildExecutor.calculatePairedBuilds() instead.
     *
     *  @return 0 (dead code - kept for API compatibility)
     *  @deprecated
     */
    int countHowManyToBuild() {
        return 0;
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

        if (!forceZeroHop) {
            int len = settings.getLengthOverride();
            if (len < 0) {len = settings.getLength();}
            if (len > 0 && (!settings.isExploratory()) && _context.random().nextInt(4) < 3) { // 75%
                // Look for a tunnel to reuse, if the right length and expiring soon.
                // Ignore variance for now.
                len++; // us
                synchronized (_tunnels) {
                    for (TunnelInfo ti : _tunnels) {
                        if (ti.getLength() >= len && ti.getExpiration() < now + 3*60*1000 && !ti.wasReused()) {
                            ti.setReused();
                            len = ti.getLength();
                            peers = new ArrayList<Hash>(len);
                            // Peers list is ordered endpoint first, but cfg.getPeer() is ordered gateway first
                            for (int i = len - 1; i >= 0; i--) {peers.add(ti.getPeer(i));}
                            break;
                        }
                    }
                }
            }
            if (peers == null) {
                setLengthOverride();
                peers = _peerSelector.selectPeers(settings);
            }

            if ((peers == null) || (peers.isEmpty())) {
                // No peers to build the tunnel with, and the pool is refusing 0 hop tunnels
                long uptime = _context.router().getUptime();
                if (peers == null) {
                    if (_log.shouldWarn() && uptime > 3*60*1000) {
                        _log.warn("No peers to put in the new tunnel! selectPeers returned null.. boo! hiss!");
                    }
                } else {
                    if (_log.shouldWarn() && uptime > 3*60*1000) {
                        _log.warn("No peers to put in the new tunnel! selectPeers returned an empty list?!");
                    }
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

        switch (result) {
            case SUCCESS:
                _consecutiveBuildTimeouts.set(0);
                addTunnel(cfg);
                updatePairedProfile(cfg, true);
                break;

            case REJECT:
            case BAD_RESPONSE:
            case DUP_ID:
            case TIMEOUT:
            case OTHER_FAILURE:
                _consecutiveBuildTimeouts.incrementAndGet();
                updatePairedProfile(cfg, true);
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
           paired = (PooledTunnelCreatorConfig) pool.getTunnel(pairedGW);
       }
       if (paired != null && paired.getLength() > 1) {
           if (success) {
               long requestedOn = cfg.getExpiration() - 10*60*1000;
               int rtt = (int) (_context.clock().now() - requestedOn);
               paired.testSuccessful(rtt);
           } else {paired.tunnelFailed();}
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
            return true; // Exploratory tunnels don't have a specific destination
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
                rv.append("[").append(_settings.getDestination().toBase64().substring(0,6)).append("]");
            }
            return rv.toString();
        }
    }

}