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
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.stat.Rate;
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
    /** Notify lock */
    private final Object _currentlyBuilding;
    /** indexed by ptcc.getReplyMessageId() */
    private final ConcurrentHashMap<Long, PooledTunnelCreatorConfig> _currentlyBuildingMap;
    /** indexed by ptcc.getReplyMessageId() */
    private final ConcurrentHashMap<Long, PooledTunnelCreatorConfig> _recentlyBuildingMap;
    private volatile boolean _isRunning;
    private boolean _repoll;
    private static final int MAX_CONCURRENT_BUILDS = SystemVersion.isSlow() ? 10 : Math.max(SystemVersion.getCores() * 4, 16);
    private static final int TUNNEL_POOLS = SystemVersion.isSlow() ? 12 : 32;
    private static final long GRACE_PERIOD = 60*1000; // accept replies up to a minute after we gave up on them
    private static final long[] RATES = { 60*1000, 10*60*1000l, 60*60*1000l, 24*60*60*1000l };
    public boolean fullStats() {return _context.getBooleanProperty("stat.full");}

    /** @since 0.9.53 */
    enum Result { SUCCESS, REJECT, TIMEOUT, BAD_RESPONSE, DUP_ID, OTHER_FAILURE }

    public BuildExecutor(RouterContext ctx, TunnelPoolManager mgr) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _manager = mgr;
        _currentlyBuilding = new Object();
        _currentlyBuildingMap = new ConcurrentHashMap<Long, PooledTunnelCreatorConfig>(MAX_CONCURRENT_BUILDS);
        _recentlyBuildingMap = new ConcurrentHashMap<Long, PooledTunnelCreatorConfig>(4 * MAX_CONCURRENT_BUILDS);
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
        _context.statManager().createRequiredRateStat("tunnel.buildRequestTime", "Time to build a tunnel request (ms)", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.concurrentBuilds", "How many builds are going at once", "Tunnels", RATES);
        //_context.statManager().createRateStat("tunnel.buildRequestZeroHopTime", "Time to build a zero hop tunnel", "Tunnels", RATES);
        //_context.statManager().createRateStat("tunnel.pendingRemaining", "How many inbound requests are pending after a pass (period is how long the pass takes)?", "Tunnels", RATES);
        //ctx.statManager().createRateStat("tunnel.buildClientExpireIB", "", "Tunnels", RATES);
        //ctx.statManager().createRateStat("tunnel.buildClientExpireOB", "", "Tunnels", RATES);
        //ctx.statManager().createRateStat("tunnel.buildExploratoryExpireIB", "", "Tunnels [Exploratory]", RATES);
        //ctx.statManager().createRateStat("tunnel.buildExploratoryExpireOB", "", "Tunnels [Exploratory]", RATES);

        // Get stat manager, get recognized bandwidth tiers
        StatManager statMgr = _context.statManager();
        String bwTiers = RouterInfo.BW_CAPABILITY_CHARS;
        // For each bandwidth tier, create tunnel build agree/reject/expire stats
        for (int i = 0; i < bwTiers.length(); i++) {
            String bwTier = String.valueOf(bwTiers.charAt(i));
            statMgr.createRateStat("tunnel.tierAgree" + bwTier, "Agreed joins from bandwidth tier " + bwTier, "Tunnels [Participating]", RATES);
            statMgr.createRateStat("tunnel.tierReject" + bwTier, "Rejected joins from bandwidth tier " + bwTier, "Tunnels [Participating]", RATES);
            statMgr.createRateStat("tunnel.tierExpire" + bwTier, "Expired joins from bandwidth tier " + bwTier, "Tunnels [Participating]", RATES);
        }
        // For caution, also create stats for unknown
        statMgr.createRateStat("tunnel.tierAgreeUnknown", "Agreed joins from unknown bandwidth tier", "Tunnels [Participating]", RATES);
        statMgr.createRateStat("tunnel.tierRejectUnknown", "Rejected joins from unknown bandwidth tier", "Tunnels [Participating]", RATES);
        statMgr.createRateStat("tunnel.tierExpireUnknown", "Expired joins from unknown bandwidth tier", "Tunnels [Participating]", RATES);
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

    private int allowed() {
        CommSystemFacade csf = _context.commSystem();
        if (csf.getStatus() == Status.DISCONNECTED) {return 0;}
        if (csf.isDummy() && csf.countActivePeers() <= 0) {return 0;}
        int maxKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        int allowed = maxKBps / 4;
        RateStat rs = _context.statManager().getRate("tunnel.buildRequestTime");
        boolean isSlow = SystemVersion.isSlow();

        if (rs != null) {
            Rate r = rs.getRate(60 * 1000);
            double avg = r != null ? r.getAverageValue() : rs.getLifetimeAverageValue();
            int throttleFactor = isSlow ? 100 : 200;
            if (avg > 50) { // If builds take more than 50ms, start throttling
                int throttle = (int) (throttleFactor * MAX_CONCURRENT_BUILDS / avg);
                if (throttle < allowed) {
                    allowed = throttle;
                    if (!isSlow && avg < 100) {allowed *= 2;}
                    if (allowed > MAX_CONCURRENT_BUILDS && _log.shouldInfo()) {
                        _log.info("Throttling concurrent tunnel builds to " + allowed + " -> Average build time is " + ((int) avg) + "ms");
                    }
                }
            } else if (avg <= 0 && rs.getLifetimeAverageValue() > 0) {
                avg = rs.getLifetimeAverageValue();
            }
        }

        if (allowed < SystemVersion.getCores()) {allowed = SystemVersion.getCores();} // Never choke below # cores
        if (SystemVersion.getMaxMemory() >= 1024*1024*1024 && !SystemVersion.isSlow()) {allowed *= 2;}
        if (allowed > MAX_CONCURRENT_BUILDS) {allowed = MAX_CONCURRENT_BUILDS;}
        allowed = _context.getProperty("router.tunnelConcurrentBuilds", allowed);

        // expire any REALLY old requests
        long now = _context.clock().now();
        long expireBefore = now + 10*60*1000 - BuildRequestor.REQUEST_TIMEOUT - GRACE_PERIOD;
        for (Iterator<PooledTunnelCreatorConfig> iter = _recentlyBuildingMap.values().iterator(); iter.hasNext(); ) {
            PooledTunnelCreatorConfig cfg = iter.next();
            if (cfg.getExpiration() <= expireBefore) {iter.remove();}
        }

        // expire any old requests
        List<PooledTunnelCreatorConfig> expired = null;
        int concurrent = 0;
        // Todo: Make expiration variable
        expireBefore = now + 10*60*1000 - BuildRequestor.REQUEST_TIMEOUT;
        for (Iterator<PooledTunnelCreatorConfig> iter = _currentlyBuildingMap.values().iterator(); iter.hasNext(); ) {
            PooledTunnelCreatorConfig cfg = iter.next();
            if (cfg.getExpiration() <= expireBefore) {
                // save them for another minute
                _recentlyBuildingMap.putIfAbsent(Long.valueOf(cfg.getReplyMessageId()), cfg);
                iter.remove();
                if (expired == null) {expired = new ArrayList<PooledTunnelCreatorConfig>();}
                expired.add(cfg);
            }
        }
        concurrent = _currentlyBuildingMap.size();
        allowed -= concurrent;

        if (expired != null) {
            for (int i = 0; i < expired.size(); i++) {
                PooledTunnelCreatorConfig cfg = expired.get(i);
                if (_log.shouldInfo())
                    _log.info("Timeout (" + BuildRequestor.REQUEST_TIMEOUT / 1000 + "s) waiting for tunnel build reply -> " + cfg);

                // Iterate through peers in the tunnel, get their bandwidth tiers,
                // record for each that a peer of the given tier expired
                // Also note the fact that this tunnel request timed out in the peers' profiles.
                for (int iPeer = 0; iPeer < cfg.getLength(); iPeer++) {
                    // Look up peer
                    Hash peer = cfg.getPeer(iPeer);
                    if (peer.equals(_context.routerHash())) {continue;} // Avoid recording ourselves
                    RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer); // Look up routerInfo
                    String bwTier = "Unknown"; // Default and detect bandwidth tier
                    if (ri != null) bwTier = ri.getBandwidthTier(); // Returns "Unknown" if none recognized
                    _context.statManager().addRateData("tunnel.tierExpire" + bwTier, 1); // Record that a peer of the given tier expired
                    didNotReply(cfg.getReplyMessageId(), peer);
                    // Blame everybody since we don't know whose fault it is.
                    // (it could be our exploratory tunnel's fault too...)
                    _context.profileManager().tunnelTimedOut(peer);
                }

                TunnelPool pool = cfg.getTunnelPool();
                if (pool != null) {pool.buildComplete(cfg, Result.TIMEOUT);}
                if (cfg.getDestination() == null) {_context.statManager().addRateData("tunnel.buildExploratoryExpire", 1);}
                else {_context.statManager().addRateData("tunnel.buildClientExpire", 1);}
            }
        }

        _context.statManager().addRateData("tunnel.concurrentBuilds", concurrent);

        long lag = _context.jobQueue().getMaxLag();
        int cpuloadavg = SystemVersion.getCPULoadAvg();
        int cpuload = SystemVersion.getCPULoad();
        if (_context.router().getUptime() > 5*60*1000 && (lag > 2000 || (cpuloadavg > 98 && cpuload > 98))) {
            if (_log.shouldWarn())
                if (cpuloadavg > 98 && cpuload > 98) {
                    _log.warn("High CPU load (" + cpuloadavg + "%) -> Slowing down new tunnel builds...");
                } else {
                    _log.warn("Job queue is lagged (" + lag + "ms) -> Slowing down new tunnel builds...");
                }
            _context.statManager().addRateData("tunnel.concurrentBuildsLagged", concurrent, lag);
            // if we have a job heavily blocking our jobqueue, ssllloowww dddooowwwnnn
            return SystemVersion.isSlow() ? 1 : 3;
        }

        // Trim the number of allowed tunnels for overload,
        // initiate a tunnel drop on severe overload
        if (allowed < 3) {allowed += 3; allowed *= 4;}
        return allowed;
    }

    private static final int LOOP_TIME = 200;

    public void run() {
        _isRunning = true;
        try {run2();}
        catch (NoSuchMethodError nsme) {
            // http://zzz.i2p/topics/1668
            // https://gist.github.com/AlainODea/1375759b8720a3f9f094
            // at ObjectCounter.objects()
            String s = "Fatal error:" +
                       "\nJava 8 compiler used with JRE version " + System.getProperty("java.version") +
                       " and no bootclasspath specified." +
                       "\nUpdate to Java 8 or contact packager." +
                       "\nStop I2P+ now, it will not build tunnels!";
            _log.log(Log.CRIT, s, nsme);
            System.out.println(s);
            throw nsme;
        } finally {_isRunning = false;}
    }

    private void run2() {
        List<TunnelPool> wanted = new ArrayList<TunnelPool>(MAX_CONCURRENT_BUILDS);
        List<TunnelPool> pools = new ArrayList<TunnelPool>(TUNNEL_POOLS);

        while (_isRunning && !_manager.isShutdown()){
            try {
                _repoll = false; // resets repoll to false unless there are inbound requeusts pending
                _manager.listPools(pools);
                for (int i = 0; i < pools.size(); i++) {
                    TunnelPool pool = pools.get(i);
                    if (!pool.isAlive()) {continue;}
                    int howMany = pool.countHowManyToBuild();
                    for (int j = 0; j < howMany; j++) {wanted.add(pool);}
                }
                int allowed = allowed(); // allowed() also expires timed out requests (for new style requests)
                allowed = buildZeroHopTunnels(wanted, allowed); // zero hop ones can run inline
                TunnelManagerFacade mgr = _context.tunnelManager();
                if ((mgr == null) || (mgr.getFreeTunnelCount() <= 0) || (mgr.getOutboundTunnelCount() <= 0)) {
                    // we don't have either inbound or outbound tunnels, so don't bother trying to build
                    // non-zero-hop tunnels
                    // try to kickstart it to build a fallback, otherwise we may get stuck here for a long time (minutes)
                    if (mgr != null) {
                        if (mgr.getFreeTunnelCount() <= 0) {mgr.selectInboundTunnel();}
                        if (mgr.getOutboundTunnelCount() <= 0) {mgr.selectOutboundTunnel();}
                    }
                    synchronized (_currentlyBuilding) {
                        if (!_repoll) {
                            if (_log.shouldDebug())
                                _log.debug("No tunnel to build with (Allowed / Requested: " + allowed + " / " + wanted.size() + ") -> Waiting for a moment...");
                            try {_currentlyBuilding.wait(500 +_context.random().nextInt(500));}
                            catch (InterruptedException ie) {}
                        }
                    }
                } else {
                    if ((allowed > 0) && (!wanted.isEmpty())) {
                        if (wanted.size() > 1) {
                            Collections.shuffle(wanted, _context.random());
                            // We generally prioritize pools with no tunnels,
                            // but sometimes (particularly at startup), the paired tunnel endpoint
                            // can start dropping the build messages... or hit connection limits,
                            // or be broken in other ways. So we allow other pools to go
                            // to the front of the line sometimes, to prevent being "locked up"
                            // for several minutes.
                            boolean preferEmpty = _context.random().nextInt(3) != 0;
                            // Java 7 TimSort - see info in TunnelPoolComparator
                            DataHelper.sort(wanted, new TunnelPoolComparator(preferEmpty));
                        }

                        // force the loops to be short, since 3 consecutive tunnel build requests can take
                        // a long, long time
                        if (allowed > 3) {allowed = SystemVersion.isSlow() ? 3 : 8;}

                        for (int i = 0; (i < allowed) && (!wanted.isEmpty()); i++) {
                            TunnelPool pool = wanted.remove(0);
                            long bef = System.currentTimeMillis();
                            PooledTunnelCreatorConfig cfg = pool.configureNewTunnel();
                            if (cfg != null) {
                                // 0hops are taken care of above, these are nonstandard 0hops
                                if (cfg.getLength() <= 1 && !pool.needFallback()) {
                                    if (_log.shouldDebug())
                                        _log.debug("We don't need more fallbacks for " + pool);
                                    i--; //0hop, we can keep going, as there's no worry about throttling
                                    pool.buildComplete(cfg, Result.OTHER_FAILURE);
                                    continue;
                                }
                                long pTime = System.currentTimeMillis() - bef;
                                _context.statManager().addRateData("tunnel.buildConfigTime", pTime);
                                if (_log.shouldDebug()) {
                                    _log.debug("Configuring new tunnel [" + i + "] for " + pool + " -> " + cfg);
                                }
                                buildTunnel(cfg);
                                //realBuilt++;
                            } else {
                                i--;
                            }
                        }
                    }

                    // wait whether we built tunnels or not
                    try {
                        synchronized (_currentlyBuilding) {
                            if (!_repoll) {
                                int delay = SystemVersion.isSlow() ? LOOP_TIME : LOOP_TIME / 2;
                                _currentlyBuilding.wait(delay);
                            }
                        }
                    } catch (InterruptedException ie) {} // someone wanted to build something
                }
            } catch (RuntimeException e) {
                _log.log(Log.CRIT, "Catastrophic Tunnel Manager failure! -> " + e.getMessage());
                try {Thread.sleep(LOOP_TIME);}
                catch (InterruptedException ie) {}
            }
            wanted.clear();
            pools.clear();
        }

        if (_log.shouldInfo()) {_log.info("Done building");}
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
    private static class TunnelPoolComparator implements Comparator<TunnelPool>, Serializable {

        private final boolean _preferEmpty;

        public TunnelPoolComparator(boolean preferEmptyPools) {_preferEmpty = preferEmptyPools;}

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
                        _log.debug("Configuring short tunnel for " + pool + ": " + cfg);
                    }
                    buildTunnel(cfg);
                    if (cfg.getLength() > 1) {allowed--;} // oops... shouldn't have done that, but hey, its not that bad...
                    iter.remove();
                } else {
                    if (_log.shouldDebug()) {_log.debug("Configured a NULL tunnel!");}
                }
            }
        }
        return allowed;
    }

    public boolean isRunning() {return _isRunning;}

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
     *  This calls TunnelPool.buildComplete which calls TunnelPool.addTunnel()
     *  on success, and then we wake up the executor.
     *
     *  On success, this also calls TunnelPoolManager to optionally start a test job,
     *  and queues an ExpireJob.
     *
     *  @since 0.9.53 added result parameter
     */
    public void buildComplete(PooledTunnelCreatorConfig cfg, Result result) {
        if (_log.shouldInfo()) {_log.info("Build complete (" + result + ") for " + cfg);}
        cfg.getTunnelPool().buildComplete(cfg, result);
        if (cfg.getLength() > 1) {removeFromBuilding(cfg.getReplyMessageId());}
        // Only wake up the build thread if it took a reasonable amount of time -
        // this prevents high CPU usage when there is no network connection
        // (via BuildRequestor.TunnelBuildFirstHopFailJob)
        long now = _context.clock().now();
        long buildTime = now + 10*60*1000 - cfg.getExpiration();
        if (buildTime > 100) {
            synchronized (_currentlyBuilding) {_currentlyBuilding.notifyAll();}
        } else if (cfg.getLength() > 1 && _log.shouldInfo() && buildTime < 50) {
            _log.info("Build completed really fast (" + buildTime + "ms) -> " + cfg);
        }
        long expireBefore = now + 10*60*1000 - BuildRequestor.REQUEST_TIMEOUT;
        if (cfg.getExpiration() <= expireBefore && _log.shouldDebug()) {
            _log.debug("Build completed for expired tunnel -> " + cfg);
        }
        if (result == Result.SUCCESS) {
            _manager.buildComplete(cfg);
            ExpireJob expireJob = new ExpireJob(_context, cfg);
            _context.jobQueue().addJob(expireJob);
        }
    }

    public boolean wasRecentlyBuilding(long replyId) {
        synchronized (_recentBuildIds) {return _recentBuildIds.contains(Long.valueOf(replyId));}
    }

    public void repoll() {
        synchronized (_currentlyBuilding) {
            _repoll = true;
            _currentlyBuilding.notifyAll();
        }
    }

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

}
