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
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
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

    private static final int TUNNEL_LIFETIME = 10*60*1000;
    /** if less than one success in this many, reduce quantity (exploratory only) */
    private static final int BUILD_TRIES_QUANTITY_OVERRIDE = 20;
    /** if less than one success in this many, reduce length (exploratory only) */
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_1 = 12;
    private static final int BUILD_TRIES_LENGTH_OVERRIDE_2 = 15;
    private static final long STARTUP_TIME = 15*60*1000;

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
        _context.statManager().createRateStat("tunnel.buildRatio.In", "Tunnel build ratio inbound", "Tunnels", new long[] {600000});
        _context.statManager().createRateStat("tunnel.buildRatio.Out", "Tunnel build ratio outbound", "Tunnels", new long[] {600000});
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
        //SimpleTimer2 timer = _context.simpleTimer2();
        //timer.addPeriodicEvent(new TunnelPruneJob(timer, this), 3 * 60 * 1000);

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
     *  @return average bandwidth per configured tunnel in Bps
     *  @since 0.9.66
     */
    public int getAvgBWPerTunnel() {
        RateStat stat = _context.statManager().getRate(_rateName);
        if (stat == null)
            return 0;
        Rate rate = stat.getRate(5*60*1000);
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
        synchronized (_tunnels) {
            if (_tunnels.isEmpty()) {
                boolean needTunnel = needTunnelNow();
                if (_log.shouldWarn() && _context.router().getUptime() > 60_000 && needTunnel) {
                    _log.warn(toString() + " -> No tunnels available");
                }
            } else if (!_tunnels.isEmpty()) {
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
                            return info;
                        } else {backloggedTunnel = info;}
                    }
                }
                // return a random backlogged tunnel
                if (backloggedTunnel != null) {return backloggedTunnel;}
                if (_log.shouldWarn()) {
                    _log.warn(toString() + ": after " + _tunnels.size() + " tries, no unexpired tunnels were found: " + _tunnels);
                }
            }
        }

        if (_alive && !avoidZeroHop) {buildFallback();}
        if (allowRecurseOnFail) {return selectTunnel(false);}
        else {return null;}
    }

    /**
     * Determine if a tunnel is actually needed now.
     * Avoids spamming logs when there's no current traffic demand.
     */
    private boolean needTunnelNow() {

        // If there are tunnels being built, probably not urgent
        synchronized (_inProgress) {
            if (!_inProgress.isEmpty()) {
                return false;
            }
        }

        // If there are pending messages waiting for a tunnel, then we need one
        RateStat stat = _context.statManager().getRate(_rateName);
        if (stat != null) {
            Rate rate = stat.getRate(60 * 1000); // last minute
            if (rate != null && rate.getAverageValue() > 1024) { // more than 1KB/s needed
                return true;
            }
        }

        return false;
    }

    /**
     * Return the tunnel from the pool that is XOR-closet to the target.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * Does not check for backlogged next peer.
     * Does not return an expired tunnel.
     *
     * @return null on failure
     * @since 0.8.10
     */
    TunnelInfo selectTunnel(Hash closestTo) {
        boolean avoidZeroHop = !_settings.getAllowZeroHop();
        TunnelInfo rv = null;
        long now = _context.clock().now();
        boolean needTunnel = needTunnelNow();
        synchronized (_tunnels) {
            if (!_tunnels.isEmpty()) {
                if (_tunnels.size() > 1) {
                    Collections.sort(_tunnels, new TunnelInfoComparator(closestTo, avoidZeroHop));
                }
                for (TunnelInfo info : _tunnels) {
                    if (info.getExpiration() > now) {rv = info; break;}
                }
            }
        }
        if (rv != null) {
            _context.statManager().addRateData("tunnel.matchLease", closestTo.equals(rv.getFarEnd()) ? 1 : 0);
        } else {
            if (_log.shouldWarn() &&_context.router().getUptime() > 60_000 && needTunnel) {
                _log.warn(toString() + " -> No tunnels available");
            }
        }
        return rv;
    }

    /**
     *  @param gatewayId for inbound, the GW rcv tunnel ID; for outbound, the GW send tunnel ID.
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
     * Returns the adjusted tunnel quantity based on current settings and network conditions.
     *
     * For zero-hop tunnels, always returns 1.
     *
     * For client tunnels:
     * - Returns the configured quantity unless there are excessive build timeouts.
     * - If timeouts are high, the quantity is reduced to prevent congestion.
     *
     * For exploratory tunnels:
     * - Returns the configured quantity, but may be increased if the router is well-integrated.
     * - Floodfill routers get a higher base tunnel count (8) due to higher load.
     * - If the success rate over the last 10 minutes is very low (≤ 1/10), reduce the quantity by 1.
     * - During router startup, the tunnel count is slightly increased to help integrate faster.
     *
     * @return adjusted tunnel quantity (>= 1)
     * @since 0.8.11
     */
    private int getAdjustedTotalQuantity() {
        if (_settings.getLength() == 0 && _settings.getLengthVariance() == 0) return 1;
        int rv = _settings.getTotalQuantity();
        boolean shouldWarn = _log.shouldWarn();
        if (!_settings.isExploratory()) {
            if (rv <= 1) return rv;
            int fails = _consecutiveBuildTimeouts.get();
            // Only reduce after sustained failures, and never below 2
            if (fails > 10) {
                if (fails > 30) {
                    rv = Math.max(2, rv / 2);
                } else if (fails > 20) {
                    rv = Math.max(2, (3 * rv) / 4);
                } else if (rv > 5) {
                    rv = Math.max(2, rv - 1);
                }
                if (shouldWarn && rv < _settings.getTotalQuantity()) {
                    _log.warn("Limiting to " + rv + " tunnels after " + fails + " consecutive build timeouts on " + this);
                }
            }
            return rv;
        }

        // Exploratory: increase for well-integrated routers
        if (_context.router().getUptime() > 60_000 && _settings.isExploratory()) {
            if (_context.netDb().floodfillEnabled()) rv = Math.max(rv, 8);
            else rv = Math.max(rv, 6);
        }

        // Only reduce if we have sufficient data AND persistently poor success
        final long window = 10 * 60 * 1000;
        final int successThreshold = 1000 / BUILD_TRIES_QUANTITY_OVERRIDE; // 10%
        RateStat e = _context.statManager().getRate("tunnel.buildExploratoryExpire");
        RateStat r = _context.statManager().getRate("tunnel.buildExploratoryReject");
        RateStat s = _context.statManager().getRate("tunnel.buildExploratorySuccess");
        if (e != null && r != null && s != null) {
            Rate er = e.getRate(window), rr = r.getRate(window), sr = s.getRate(window);
            if (er != null && rr != null && sr != null) {
                RateAverages ra = RateAverages.getTemp();
                long ec = er.computeAverages(ra, false).getTotalEventCount();
                long rc = rr.computeAverages(ra, false).getTotalEventCount();
                long sc = sr.computeAverages(ra, false).getTotalEventCount();
                long tot = ec + rc + sc;
                // Require at least 15 attempts before considering reduction
                if (tot >= Math.max(BUILD_TRIES_QUANTITY_OVERRIDE, 15) && 1000 * sc / tot <= successThreshold) {
                    // Reduce by at most 1, never below 2
                    rv = Math.max(2, rv - 1);
                }
            }
        }

        // Startup boost only if we’re below 3
        if (_context.router().getUptime() < STARTUP_TIME && rv < 3) rv++;
        return Math.max(1, rv);
    }

    /**
     *  Shorten the length when under extreme stress, else clear the override.
     *  We only do this for exploratory tunnels, since we have to build a fallback
     *  if we run out. It's much better to have a shorter tunnel than a fallback.
     *
     *  @since 0.8.11
     */
    private void setLengthOverride() {
        if (!_settings.isExploratory()) return;
        int len = _settings.getLength();
        if (len <= 1) {
            _settings.setLengthOverride(-1);
            return;
        }

        RateStat e = _context.statManager().getRate("tunnel.buildExploratoryExpire");
        RateStat r = _context.statManager().getRate("tunnel.buildExploratoryReject");
        RateStat s = _context.statManager().getRate("tunnel.buildExploratorySuccess");
        if (e != null && r != null && s != null) {
            Rate er = e.getRate(10*60*1000);
            Rate rr = r.getRate(10*60*1000);
            Rate sr = s.getRate(10*60*1000);
            if (er != null && rr != null && sr != null) {
                RateAverages ra = RateAverages.getTemp();
                long ec = er.computeAverages(ra, false).getTotalEventCount();
                long rc = rr.computeAverages(ra, false).getTotalEventCount();
                long sc = sr.computeAverages(ra, false).getTotalEventCount();
                long tot = ec + rc + sc;

                // Require meaningful sample size
                if (tot >= Math.max(BUILD_TRIES_LENGTH_OVERRIDE_2, 15) || _firstInstalled > _context.clock().now()) {
                    long succ = tot > 0 ? 1000 * sc / tot : 0;
                    // Only reduce if success is extremely poor
                    if (succ <= 1000 / BUILD_TRIES_LENGTH_OVERRIDE_2) { // ≤ 1/15 (~6.7%)
                        if (len > 2) {
                            _settings.setLengthOverride(len - 1); // reduce by 1, not 2
                        } else {
                            _settings.setLengthOverride(1);
                        }
                        if (_log.shouldInfo()) {
                            _log.info("Reducing Exploratory tunnel length to " + _settings.getLengthOverride() +
                                      " due to low success rate (" + succ + "/1000)");
                        }
                        return;
                    }
                }
            }
        }
        _settings.setLengthOverride(-1); // disable override
    }

    /** list of tunnelInfo instances of tunnels currently being built */
    public List<PooledTunnelCreatorConfig> listPending() {synchronized (_inProgress) {return new ArrayList<PooledTunnelCreatorConfig>(_inProgress);}}

    /** duplicate of size(), let's pick one */
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

    /**
     *  Is this pool running AND either exploratory, or tracked by the client manager?
     *  A pool will be alive but not tracked after the client manager removes it
     *  but before all the tunnels have expired.
     */
    public boolean isAlive() {
        return _alive && (_settings.isExploratory() || _context.clientManager().isLocal(_settings.getDestination()));
    }

    /** duplicate of getTunnelCount(), let's pick one */
    public int size() {
        synchronized (_tunnels) {return _tunnels.size();}
    }

    /**
     *  Add to the pool.
     */
    protected void addTunnel(TunnelInfo info) {
        if (_log.shouldDebug()) {_log.debug(toString() + " -> Adding tunnel " + info + "...");}
        LeaseSet ls = null;
        synchronized (_tunnels) {
            _tunnels.add(info);
            if (_settings.isInbound() && !_settings.isExploratory()) {ls = locked_buildNewLeaseSet();}
        }
        if (ls != null) {requestLeaseSet(ls);}
        _consecutiveBuildTimeouts.set(0);
        pruneExcessTunnels();
    }

    /**
     *  Remove from the pool.
     */
    void removeTunnel(TunnelInfo info) {
        if (_log.shouldDebug()) {_log.debug(toString() + " -> Removing tunnel " + info + "...");}
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
        pruneExcessTunnels();
    }

    /**
     *  Remove the tunnel and blame all the peers (not necessarily equally).
     *  This may be called multiple times from TestJob.
     */
    void tunnelFailed(TunnelInfo cfg) {
        fail(cfg);
        tellProfileFailed(cfg);
    }

    /**
     *  Remove the tunnel and blame only one peer.
     *  This may be called multiple times.
     *
     *  @since 0.8.13
     */
    void tunnelFailed(TunnelInfo cfg, Hash blamePeer) {
        fail(cfg);
        _context.profileManager().tunnelFailed(blamePeer, 100);
    }

    /**
     *  Remove the tunnel.
     */
    private void fail(TunnelInfo cfg) {
        LeaseSet ls = null;
        long uptime = _context.router().getUptime();
        synchronized (_tunnels) {
            boolean removed = _tunnels.remove(cfg);
            if (!removed) {return;}
            if (_settings.isInbound() && !_settings.isExploratory()) {
                ls = locked_buildNewLeaseSet();
            }
        }

        if (_log.shouldWarn() && uptime > 2*60*1000) {_log.warn("Tunnel build failed -> " + cfg);}

        _manager.tunnelFailed();
        _lifetimeProcessed += cfg.getProcessedMessagesCount();
        updateRate();

        if (_settings.isInbound() && !_settings.isExploratory() && ls != null) {
            requestLeaseSet(ls);
        }
    }

    /**
     * Map to track recent tunnel failures per peer (peer -> list of failure timestamps)
     * Keep this in the TunnelPool class, not in the method.
     */
    private final Map<Hash, List<Long>> _peerFailureHistory = new HashMap<>();
    private static final long FAILURE_HISTORY_WINDOW = 12 * 60 * 1000; // 10 minutes

    /**
     *  Blame all the other peers in the tunnel, with a probability
     *  inversely related to the tunnel length, and with adaptive penalties.
     *  Older failures have less impact due to a decay factor.
     *  Uses in-memory tracking of recent tunnel failures per peer.
     */
    private void tellProfileFailed(TunnelInfo cfg) {
        int len = cfg.getLength();
        if (len < 2) return;
        int start = 0;
        int end = len;
        if (cfg.isInbound()) end--;
        else start++;

        long now = _context.clock().now();
        for (int i = start; i < end; i++) {
            Hash peer = cfg.getPeer(i);
            // Base blame: more forgiving, max 30%
            int pct = Math.min(30, 50 / (len - 1));

            // Inbound: slightly more blame on gateway
            if (cfg.isInbound() && len > 2) {
                if (i == start) {
                    pct = Math.min(30, (int)(pct * 1.3));
                } else {
                    pct = Math.max(5, pct / 2);
                }
            }

            // Adaptive penalty – but less severe
            synchronized (_peerFailureHistory) {
                List<Long> failures = _peerFailureHistory.computeIfAbsent(peer, k -> new ArrayList<>());
                // Prune old
                failures.removeIf(ts -> now - ts > FAILURE_HISTORY_WINDOW);
                double recent = failures.size();
                failures.add(now);

                if (recent > 3) {
                    pct = Math.min(35, pct + 10); // +10, not +20
                } else if (recent == 0) {
                    pct = Math.max(5, pct - 10); // allow faster recovery
                }
            }

            pct = Math.min(pct, 35); // hard cap
            if (_log.shouldInfo()) {
                _log.info(toString() + " -> Blaming [" + peer.toBase64().substring(0,6) + "] -> " + pct + '%');
            }
            _context.profileManager().tunnelFailed(peer, pct);
        }
    }

    /**
     * Called when a tunnel containing this peer was successfully built and tested.
     * Reduces or clears recent failure history to allow peer recovery.
     */
    private void peerSucceeded(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) return;
        synchronized (_peerFailureHistory) {
            List<Long> failures = _peerFailureHistory.get(peer);
            if (failures == null || failures.isEmpty()) return;

            long now = _context.clock().now();
            failures.clear();

            if (failures.isEmpty()) {
                _peerFailureHistory.remove(peer);
            }
        }
    }

    /**
     * Prune the tunnel pool if we have more than configured.
     * This ensures we don't keep more than the configured quantity of tunnels,
     * and removes the worst ones first (zero-hop, short-lived, backlogged, etc.)
     *
     * @since 0.9.68+
     */
    private void pruneExcessTunnels() {
        int target = _settings.getTotalQuantity();
        int configuredLength = _settings.getLength();
        synchronized (_tunnels) {
            if (_tunnels.size() <= target) return;

            if (_log.shouldDebug()) {
                _log.debug("Tunnel pool exceeds configured quantity: " + _tunnels.size() + " > " + target);
            }

            // Step 1: Remove tunnels with fewer than configured length first
            List<TunnelInfo> toRemove = new ArrayList<>();
            for (TunnelInfo tunnel : _tunnels) {
                if (tunnel.getLength() < configuredLength) {
                    toRemove.add(tunnel);
                }
            }

            for (TunnelInfo tunnel : toRemove) {
                if (_tunnels.size() <= target) break;
                if (_tunnels.contains(tunnel)) {
                    if (_log.shouldDebug()) {
                        _log.debug("Pruning tunnel with insufficient length: " + tunnel);
                    }
                    _tunnels.remove(tunnel);
                    _manager.tunnelFailed(); // notify pool manager
                }
            }

            // Step 2: If still over target, remove excess using existing logic
            if (_tunnels.size() > target) {
                List<TunnelInfo> sorted = new ArrayList<>(_tunnels);
                TunnelInfoComparator comparator = new TunnelInfoComparator(null, true);
                sorted.sort(comparator);

                while (_tunnels.size() > target && !sorted.isEmpty()) {
                    TunnelInfo worst = sorted.get(0);
                    if (_tunnels.contains(worst)) {
                        if (_log.shouldDebug()) {
                            _log.debug("Pruning tunnel: " + worst);
                        }
                        _tunnels.remove(worst);
                        _manager.tunnelFailed(); // notify pool manager
                    } else {
                        sorted.remove(0);
                    }
                }
            }
        }
    }

    /**
     * Select the worst tunnel(s) to remove when the pool exceeds its configured quantity.
     * Tunnels are sorted using the existing comparator logic, which puts:
     * <ul>
     *   <li>Zero-hop tunnels at the end</li>
     *   <li>Backlogged outbound tunnels next</li>
     *   <li>Tunnels about to expire</li>
     *   <li>Then shorter tunnels</li>
     * </ul>
     *
     * @param list List of tunnels to select from
     * @param count How many to remove
     * @return The worst tunnel(s), or null if none found
     * @since 0.9.68+
     */
    private TunnelInfo selectWorstTunnel(List<TunnelInfo> list, int count) {
        if (list == null || list.isEmpty() || count <= 0) {
            return null;
        }

        boolean avoidZeroHop = !_settings.getAllowZeroHop();

        // Sort from worst to best using null base (only sort by expiration)
        List<TunnelInfo> sorted = new ArrayList<>(list);
        TunnelInfoComparator comparator = new TunnelInfoComparator(null, avoidZeroHop);
        Collections.sort(sorted, comparator);

        // Reverse list so worst comes first
        Collections.reverse(sorted);

        return sorted.get(0); // return worst tunnel
    }

    /**
     * Periodically prune the tunnel pool to keep only the best tunnels.
     * Run every 3 minutes after startup.
     */
/*    private static class TunnelPruneJob extends SimpleTimer.TimedEvent {
        private final TunnelPool _pool;

        public TunnelPruneJob(SimpleTimer2 timer, TunnelPool pool) {
            super(timer, 3 * 60 * 1000); // initial delay = period
            _pool = pool;
        }

        @Override
        public void timeReached() {
            if (_pool.isAlive() && _pool._log.shouldDebug()) {
                _pool._log.debug("Pruning excess tunnels from " + _pool._settings.getDestination());
            }
            _pool.pruneExcessTunnels();
        }
    }*/

    private void updateRate() {
        long now = _context.clock().now();
        long et = now - _lastRateUpdate;
        if (et > 2*60*1000) {
            long bw = 1024 * (_lifetimeProcessed - _lastLifetimeProcessed) * 1000 / et; // Bps
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
     * Returns true if there is at least one unexpired, usable tunnel in the pool.
     * For outbound tunnels, also excludes tunnels whose first hop is backlogged.
     */
    public boolean hasUsableTunnel() {
        long now = _context.clock().now();
        synchronized (_tunnels) {
            for (TunnelInfo info : _tunnels) {
                if (info.getExpiration() <= now) continue;
                // For outbound, avoid tunnels where first hop is backlogged
                if (!_settings.isInbound() && info.getLength() > 1) {
                    if (_context.commSystem().isBacklogged(info.getPeer(1))) {
                        continue;
                    }
                }
                return true;
            }
        }
        return false;
    }

    boolean needFallback() {
        return !hasUsableTunnel();
    }

    /**
     * This will build a fallback (zero-hop) tunnel ONLY if
     * this pool is exploratory, or the settings allow it.
     *
     * @return true if a fallback tunnel is built
     */
    boolean buildFallback() {
        boolean allowZeroHop = _settings.isExploratory() || _settings.getAllowZeroHop();
        boolean chronicFailure = _consecutiveBuildTimeouts.get() >= 4;

        if (allowZeroHop && (!hasUsableTunnel() || chronicFailure)) {
            if (_log.shouldInfo()) {
                _log.info(toString() + " -> Building fallback " + (allowZeroHop ? "" : "Exploratory ") +
                          "tunnel (no usable tunnels or repeated failure)");
            }
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
     * Find the tunnel with the far-end that is XOR-closest to a given hash
     *
     * @since 0.8.10
     */
    private static class TunnelInfoComparator implements Comparator<TunnelInfo>, Serializable {
        private final byte[] _base;
        private final boolean _avoidZero;

        /**
         * @param target key to compare distances with
         * @param avoidZeroHop if true, zero-hop tunnels will be put last
         */
        public TunnelInfoComparator(Hash target, boolean avoidZeroHop) {
             _base = (target != null) ? target.getData() : null;
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

            // If base is null, prioritize by expiration only
            if (_base == null) {
                long lhsExp = lhs.getExpiration();
                long rhsExp = rhs.getExpiration();
                if (lhsExp > rhsExp) {return -1;}
                if (lhsExp < rhsExp) {return 1;}
                return 0;
            }

            // Normal XOR comparison
            byte lhsb[] = lhs.getFarEnd().getData();
            byte rhsb[] = rhs.getFarEnd().getData();
            for (int i = 0; i < _base.length; i++) {
                int ld = (lhsb[i] ^ _base[i]) & 0xff;
                int rd = (rhsb[i] ^ _base[i]) & 0xff;
                if (ld < rd) {return -1;}
                if (ld > rd) {return 1;}
            }
            // XOR match – use expiration as tie-breaker
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
        long expireAfter = _context.clock().now() - 15*1000;

        TunnelInfo zeroHopTunnel = null;
        Lease zeroHopLease = null;
        TreeSet<Lease> leases = new TreeSet<Lease>(new LeaseComparator());
        for (int i = 0; i < _tunnels.size(); i++) {
            TunnelInfo tunnel = _tunnels.get(i);
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

    public long getLifetimeProcessed() {return _lifetimeProcessed;}

    /**
     * Keep a separate stat for each type, direction, and length of tunnel.
     */
    private final String buildRateName() {
        if (_settings.isExploratory()) {return "tunnel.buildRatio.exploratory." + (_settings.isInbound() ? " In" : " Out");}
        else {return "tunnel.buildRatio.l" + _settings.getLength() + "v" + _settings.getLengthVariance() + (_settings.isInbound() ? ".in" : ".out");}
    }

    /**
     * Gather the data to see how many tunnels to build, and then actually compute that value (delegated to
     * the countHowManyToBuild function below)
     *
     */
    int countHowManyToBuild() {
        if (!isAlive()) {return 0;}
        int wanted = getAdjustedTotalQuantity();
        boolean allowZeroHop = _settings.getAllowZeroHop();

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
            /*
            final int PANIC_FACTOR = 4;  // how many builds to kick off when time gets short
            avg += 60*1000; // one minute safety factor
            if (_settings.isExploratory())
                avg += 30*1000; // two minute safety factor
            */
            final int PANIC_FACTOR = 5; // how many builds to kick off when time gets short
            avg += (_settings.isExploratory() ? 60*1000 : 90*1000);
            long now = _context.clock().now();

            int expireSoon = 0;
            int expireLater = 0;
            int expireTime[];
            int fallback = 0;
            synchronized (_tunnels) {
                expireTime = new int[_tunnels.size()];
                for (int i = 0; i < _tunnels.size(); i++) {
                    TunnelInfo info = _tunnels.get(i);
                    if (allowZeroHop || (info.getLength() > 1)) {
                        int timeToExpire = (int) (info.getExpiration() - now);
                        if (timeToExpire > 0 && timeToExpire < avg) {expireTime[expireSoon++] = timeToExpire;}
                        else {expireLater++;}
                    } else if (info.getExpiration() - now > avg) {fallback++;}
                }
            }

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

            if (rv > 0 && _log.shouldDebug())
                _log.debug("[" + toString() + "] New Count: rv: " + rv + "; Allow Zero Hop? " + allowZeroHop
                       + "; Avg: " + avg + "; LatestTime: " + latesttime
                       + "; Soon: " + expireSoon + "; Later: " + expireLater
                       + "; STD: " + wanted + "; InProgress: " + inProgress + "; Fallback: " + fallback);
            _context.statManager().addRateData(rateName, rv + inProgress);
            return rv;
        }

        // Fixed, conservative algorithm - starts building 3 1/2 - 6m before expiration
        // (210 or 270s) + (0..90s random)
        long expireAfter = _context.clock().now() + _expireSkew; // + _settings.getRebuildPeriod() + _expireSkew;
        int expire30s = 0;
        int expire90s = 0;
        int expire150s = 0;
        int expire210s = 0;
        int expire270s = 0;
        int expireLater = 0;

        int fallback = 0;
        synchronized (_tunnels) {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = _tunnels.get(i);
                if (allowZeroHop || (info.getLength() > 1)) {
                    long timeToExpire = info.getExpiration() - expireAfter;
                    if (timeToExpire <= 0) {} // consider it unusable
                    else if (timeToExpire <= 30*1000) {expire30s++;}
                    else if (timeToExpire <= 90*1000) {expire90s++;}
                    else if (timeToExpire <= 150*1000) {expire150s++;}
                    else if (timeToExpire <= 210*1000) {expire210s++;}
                    else if (timeToExpire <= 270*1000) {expire270s++;}
                    else {expireLater++;}
                } else if (info.getExpiration() > expireAfter) {fallback++;}
            }
        }

        int inProgress = 0;
        synchronized (_inProgress) {
            inProgress = _inProgress.size();
            for (int i = 0; i < inProgress; i++) {
                PooledTunnelCreatorConfig cfg = _inProgress.get(i);
                if (cfg.getLength() <= 1) {fallback++;}
            }
        }

        int rv = countHowManyToBuild(allowZeroHop, expire30s, expire90s, expire150s, expire210s, expire270s,
                                     expireLater, wanted, inProgress, fallback);
        _context.statManager().addRateData(rateName, (rv > 0 || inProgress > 0) ? 1 : 0);
        return rv;
    }

    /**
     * Determine how many new tunnels to build based on tunnel expiration times and build history.
     * - Uses adaptive multipliers based on tunnel build success rate
     * - Panic mode when tunnel count is critically low
     * - Enables fallback tunnel builds when needed
     * - Avoids tunnel starvation during high failure rates
     *
     * @param allowZeroHop whether zero-hop tunnels are allowed
     * @param expire30s tunnels expiring within 30s
     * @param expire90s tunnels expiring in 30-90s
     * @param expire150s tunnels expiring in 90-150s
     * @param expire210s tunnels expiring in 150-210s
     * @param expire270s tunnels expiring in 210-270s
     * @param expireLater tunnels expiring after 270s
     * @param standardAmount how many tunnels we want to keep
     * @param inProgress how many are being built
     * @param fallback how many zero-hop tunnels are active or in progress
     * @return number of new tunnels to build
     */
    private int countHowManyToBuild(boolean allowZeroHop, int expire30s, int expire90s, int expire150s, int expire210s,
                                    int expire270s, int expireLater, int standardAmount, int inProgress, int fallback) {
        int rv = 0;
        long uptime = _context.router().getUptime();

        int remainingWanted = standardAmount - expireLater;
        if (allowZeroHop) {
            remainingWanted -= fallback;
        }

        // Get the current build success rate directly
        RateStat stat = _context.statManager().getRate("tunnel.buildRatio." + (_settings.isInbound() ? "In" : "Out"));
        double successRate = 0.8; // Default to 80% if not available
        if (stat != null) {
            Rate rate = stat.getRate(10 * 60 * 1000); // 10-minute window
            if (rate != null) {
                successRate = rate.getAverageValue();
            }
        }
        double buildMultiplier = 1.0 / Math.max(0.3, successRate); // Inverse of success rate

        // Schedule builds based on time to expire
        rv += (int)(buildMultiplier * expire30s * 2.0); // Highest priority
        rv += (int)(buildMultiplier * expire90s * 1.5);
        rv += (int)(buildMultiplier * expire150s * 1.0);
        rv += (int)(buildMultiplier * expire210s * 0.8);
        rv += (int)(buildMultiplier * expire270s * 0.5);

        // Add extra builds if not enough are scheduled
        if (remainingWanted > 0) {
            rv += (int)(buildMultiplier * remainingWanted * 2.0);
        }

        // Subtract tunnels currently in progress
        rv -= inProgress;

        // Panic mode: if we have no usable tunnels, force more builds
        boolean noUsableTunnels = true;
        long now = _context.clock().now();
        synchronized (_tunnels) {
            for (TunnelInfo info : _tunnels) {
                if (info.getExpiration() > now) {
                    if (_settings.isInbound() || info.getLength() <= 1 ||
                        !_context.commSystem().isBacklogged(info.getPeer(1))) {
                        noUsableTunnels = false;
                        break;
                    }
                }
            }
        }

        String build = rv == 1 ? "build" : "builds";

        if (noUsableTunnels) {
            int extra = Math.min(2, standardAmount);
            rv += extra;
            if (_log.shouldWarn() && uptime > 2*60*1000 && extra > 2) {
                _log.warn("Entering PANIC mode: No usable tunnels -> Forcing " + extra + ' ' + build + "...");
            }
        }

        // If we're failing builds repeatedly, compensate by increasing builds
        int failures = _consecutiveBuildTimeouts.get();
        if (failures > 3) {
            int compensation = Math.min(failures / 5 + 1, 3); // 1 extra build per 5 failures
            rv += compensation;
            //if (_log.shouldWarn() && uptime > 2*60*1000) {
            //    _log.warn("Compensating for " + failures + " consecutive failures -> Adding " + compensation + ' ' + build + "...");
            //}
        }

        // Allow fallback tunnel if no builds scheduled and we're in trouble
        if (allowZeroHop && rv <= 0 && (noUsableTunnels || failures > 5) && inProgress == 0) {
            rv = 1; // Allow fallback only if no builds in progress
            if (_log.shouldInfo() && uptime > 2*60*1000) {
                _log.info("Allowing fallback tunnel due to tunnel shortage or repeated failure");
            }
        }

        // Cap builds to avoid overload
        int maxBuilds = (uptime < 5*60*1000 ? 3 : 2) * standardAmount;
        if (rv + inProgress > maxBuilds) {
            rv = Math.max(0, maxBuilds - inProgress);
            //if (_log.shouldWarn()) {
            //    _log.warn("Build cap reached -> Limiting to " + rv + " builds...");
            //}
        }

        // During startup, allow more builds to integrate faster
        long lifetime = getLifetime();
        if (lifetime < 60 * 1000 && (rv + inProgress + fallback < standardAmount)) {
            int needed = standardAmount - (rv + inProgress + fallback);
            rv += needed;
            if (_log.shouldDebug()) {
                _log.debug("Boosting builds during startup -> Added " + needed + ' ' + build + "...");
            }
        }

        // Clamp result to 0 minimum
        rv = Math.max(0, rv);

        // Final safety cap in case of overflow
        if (rv + inProgress + fallback > maxBuilds) {
            rv = Math.max(0, maxBuilds - inProgress - fallback);
        }

        // Log detailed info if debug is enabled
        if (rv > 0 && _log.shouldDebug()) {
            _log.debug(toString() + " (Up: " + (lifetime / 1000) + "s). Allow Zero Hop? " + allowZeroHop +
                       "\n* Count: [rv] " + rv + "; [30s] " + expire30s + "; [90s] " + expire90s +
                       "; [150s] " + expire150s + "; [210s] " + expire210s +
                       "; [270s] " + expire270s + "; [later] " + expireLater +
                       "; [std] " + standardAmount + "; [inProgress] " + inProgress +
                       "; [fallback] " + fallback + "; [multiplier] " + buildMultiplier);
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
     *  @return null on failure
     */
    private PooledTunnelCreatorConfig configureNewTunnel(boolean forceZeroHop) {
        TunnelPoolSettings settings = getSettings();
        List<Hash> peers = null;
        long now = _context.clock().now();
        long expiration = now + TunnelPoolSettings.DEFAULT_DURATION;

        if (!forceZeroHop) {
            int len = settings.getLengthOverride();
            if (len < 0) len = settings.getLength();

            // Attempt tunnel reuse
            if (len > 0 && !settings.isExploratory() && _context.random().nextInt(10) < 3) {
                len++; // include us
                synchronized (_tunnels) {
                    for (TunnelInfo ti : _tunnels) {
                        if (ti.getLength() >= len && ti.getExpiration() < now + 60*1000 && !ti.wasReused()) {
                            ti.setReused();
                            len = ti.getLength();
                            peers = new ArrayList<>(len);
                            for (int i = len - 1; i >= 0; i--) {
                                peers.add(ti.getPeer(i));
                            }
                            break;
                        }
                    }
                }
            }

            if (peers == null) {
                setLengthOverride();
                peers = _peerSelector.selectPeers(settings);
            }

            if ((peers == null || peers.isEmpty())) {
                // Critical: log clearly and consider zero-hop ONLY if allowed
                boolean allowZero = _settings.isExploratory() || _settings.getAllowZeroHop();
                if (_context.router().getUptime() > 2 * 60 * 1000) {
                    if (_log.shouldError()) {
                        _log.error("Peer selection failed for " + this);
                    }
                }
                if (!allowZero) {
                    return null;
                }
                // Fall back to zero-hop if permitted
                peers = Collections.singletonList(_context.routerHash());
            }
        } else {
            peers = Collections.singletonList(_context.routerHash());
        }

        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_context, peers.size(),
                settings.isInbound(), settings.getDestination(), this);
        for (int i = 0; i < peers.size(); i++) {
            int j = peers.size() - 1 - i;
            cfg.setPeer(j, peers.get(i));
            HopConfig hop = cfg.getConfig(j);
            hop.setCreation(now);
            hop.setExpiration(expiration);
        }
        cfg.setExpiration(expiration);
        if (!settings.isInbound()) {
            cfg.setPriority(settings.getPriority());
        }
        if (_log.shouldDebug()) {
            _log.debug("Configuring new tunnel: " + cfg);
        }
        synchronized (_inProgress) {
            _inProgress.add(cfg);
        }
        return cfg;
    }

    /**
     *  Remove from the _inprogress list and call addTunnel() if result is SUCCESS.
     *  Updates consecutive build timeout count.
     *
     *  @since 0.9.53 added result parameter
     */
    void buildComplete(PooledTunnelCreatorConfig cfg, BuildExecutor.Result result) {
        if (cfg.getTunnelPool() != this) {
            _log.error("Wrong pool " + cfg + " for " + this, new Exception());
            return;
        }

        synchronized (_inProgress) {_inProgress.remove(cfg);}

        switch (result) {
            case SUCCESS:
                _consecutiveBuildTimeouts.set(0);
                addTunnel(cfg);
                updatePairedProfile(cfg, true);
                rewardPeersOnSuccess(cfg);
                break;

            case REJECT:
            case BAD_RESPONSE:
            case DUP_ID:
                _consecutiveBuildTimeouts.set(0);
                updatePairedProfile(cfg, true);
                break;

            case TIMEOUT:
                _consecutiveBuildTimeouts.incrementAndGet();
                updatePairedProfile(cfg, false);
                break;

            case OTHER_FAILURE:
            default:
                break;
        }
    }

    /**
     * Clear or reduce failure history for all peers in a successfully built tunnel.
     */
    private void rewardPeersOnSuccess(PooledTunnelCreatorConfig cfg) {
        int len = cfg.getLength();
        if (len <= 1) return;
        int start = cfg.isInbound() ? 0 : 1;
        int end = cfg.isInbound() ? len - 1 : len;
        for (int i = start; i < end; i++) {
            Hash peer = cfg.getPeer(i);
            if (peer != null && !peer.equals(_context.routerHash())) {
                peerSucceeded(peer);
            }
        }
    }

    /**
     *  @since 0.9.53
     */
    int getConsecutiveBuildTimeouts() {return _consecutiveBuildTimeouts.get();}

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
           if (getConsecutiveBuildTimeouts() < 3) {return;}
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