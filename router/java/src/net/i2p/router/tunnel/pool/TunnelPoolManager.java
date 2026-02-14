package net.i2p.router.tunnel.pool;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.stat.RateConstants;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounterUnsafe;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;

/**
 * Manage all the exploratory and client tunnel pools.
 * Run the tunnel builder and handler threads.
 */
public class TunnelPoolManager implements TunnelManagerFacade {
    private final RouterContext _context;
    private final Log _log;
    /** Hash (destination) to TunnelPool */
    private final Map<Hash, TunnelPool> _clientInboundPools;
    /** Hash (destination) to TunnelPool */
    private final Map<Hash, TunnelPool> _clientOutboundPools;
    private final TunnelPool _inboundExploratory;
    private final TunnelPool _outboundExploratory;
    private final BuildExecutor _executor;
    private final BuildHandler _handler;
    private final TunnelPeerSelector _clientPeerSelector;
    private final GhostPeerManager _ghostPeerManager;
    private volatile boolean _isShutdown;
    private final int _numHandlerThreads;
    private final int DEFAULT_MAX_PCT_TUNNELS;
    private final int STARTUP_MAX_PCT_TUNNELS;
    private static final String PROP_DISABLE_TUNNEL_TESTING = "router.disableTunnelTesting";
    private static final int MIN_KBPS_TWO_HANDLERS = 32;
    private static final int MIN_KBPS_THREE_HANDLERS = 128;
    private static final double MAX_SHARE_RATIO = 100000d;

    public TunnelPoolManager(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPoolManager.class);
        _clientInboundPools = new ConcurrentHashMap<Hash, TunnelPool>(32);
        _clientOutboundPools = new ConcurrentHashMap<Hash, TunnelPool>(32);
        _clientPeerSelector = new ClientPeerSelector(ctx);
        _ghostPeerManager = new GhostPeerManager(ctx);
        ExploratoryPeerSelector selector = new ExploratoryPeerSelector(_context);
        TunnelPoolSettings inboundSettings = new TunnelPoolSettings(true);
        _inboundExploratory = new TunnelPool(_context, this, inboundSettings, selector);
        TunnelPoolSettings outboundSettings = new TunnelPoolSettings(false);
        _outboundExploratory = new TunnelPool(_context, this, outboundSettings, selector);

        // threads will be started in startup()
        _executor = new BuildExecutor(ctx, this, _ghostPeerManager);
        _handler = new BuildHandler(ctx, this, _executor);
        int numHandlerThreads;
        long maxMemory = SystemVersion.getMaxMemory();
        int cores = SystemVersion.getCores();
        Boolean isSlow = SystemVersion.isSlow();
        int share = TunnelDispatcher.getShareBandwidth(ctx);
        _numHandlerThreads = ctx.getProperty("router.buildHandlerThreads", isSlow ? 2 : 4);

        if (isFirewalled()) {
            DEFAULT_MAX_PCT_TUNNELS = 30;
            STARTUP_MAX_PCT_TUNNELS = 50;
        } else {
            DEFAULT_MAX_PCT_TUNNELS = 10;
            STARTUP_MAX_PCT_TUNNELS = 30;
        }

        // The following are for TestJob
        long[] RATES = RateConstants.SHORT_TERM_RATES;
        long[] TEST_RATES = RateConstants.TUNNEL_TEST_RATES;
        ctx.statManager().createRequiredRateStat("tunnel.testFailedTime", "Time for tunnel test failure (ms)", "Tunnels", RATES);
        ctx.statManager().createRateStat("tunnel.testExploratoryFailedTime", "Time to fail exploratory tunnel test (max 60s)", "Tunnels [Exploratory]", RATES);
        ctx.statManager().createRateStat("tunnel.testFailedCompletelyTime", "Time to complete fail for tunnel test (max 60s)", "Tunnels", RATES);
        ctx.statManager().createRateStat("tunnel.testExploratoryFailedCompletelyTime", "Time to complete fail for exploratory tunnel test (max 60s)", "Tunnels [Exploratory]", RATES);
        ctx.statManager().createRateStat("tunnel.testSuccessLength", "Length (hops) of tunnels passing test", "Tunnels", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.testSuccessTime", "Time for tunnel test success (ms)", "Tunnels", TEST_RATES);
        ctx.statManager().createRateStat("tunnel.testAborted", "Time taken by aborted tunnel tests (no available peers)", "Tunnels", RATES);
    }

    /**
     * Pick a random inbound exploratory tunnel.
     * Warning - selectInboundExploratoryTunnel(Hash) is preferred.
     *
     * @return null if none
     */
    public TunnelInfo selectInboundTunnel() {
        TunnelInfo info = _inboundExploratory.selectTunnel();
        if (info == null) {
            _inboundExploratory.buildFallback();
            info = _inboundExploratory.selectTunnel(); // still can be null, but probably not
        }
        return info;
    }

    /**
     * Pick a random inbound tunnel from the given destination's pool.
     * Warning - selectInboundTunnel(Hash, Hash) is preferred.
     *
     * @param destination if null, returns inbound exploratory tunnel
     * @return null if none
     */
    public TunnelInfo selectInboundTunnel(Hash destination) {
        if (destination == null) return selectInboundTunnel();
        TunnelPool pool = _clientInboundPools.get(destination);
        if (pool != null) {return pool.selectTunnel();}
        if (_log.shouldWarn()) {_log.warn("No pool available for Inbound tunnel for " + destination.toBase32());}
        return null;
    }

    /**
     * Pick a random outbound exploratory tunnel.
     * Warning - selectOutboundExploratoryTunnel(Hash) is preferred.
     *
     * @return null if none
     */
    public TunnelInfo selectOutboundTunnel() {
        TunnelInfo info = _outboundExploratory.selectTunnel();
        if (info == null) {
            _outboundExploratory.buildFallback();
            info = _outboundExploratory.selectTunnel(); // still can be null, but probably not
        }
        return info;
    }

    /**
     * Pick a random outbound tunnel from the given destination's pool.
     * Warning - selectOutboundTunnel(Hash, Hash) is preferred.
     *
     * @param destination if null, returns outbound exploratory tunnel
     * @return null if none
     */
    public TunnelInfo selectOutboundTunnel(Hash destination)  {
        if (destination == null) return selectOutboundTunnel();
        TunnelPool pool = _clientOutboundPools.get(destination);
        if (pool != null) {
            return pool.selectTunnel();
        }
        return null;
    }

    /**
     * Pick the inbound exploratory tunnel with the gateway closest to the given hash.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectInboundExploratoryTunnel(Hash closestTo) {
        TunnelInfo info = _inboundExploratory.selectTunnel(closestTo);
        if (info == null) {
            _inboundExploratory.buildFallback();
            info = _inboundExploratory.selectTunnel(); // still can be null, but probably not
        }
        return info;
    }

    /**
     * Pick the inbound tunnel with the gateway closest to the given hash
     * from the given destination's pool.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param destination if null, returns inbound exploratory tunnel
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectInboundTunnel(Hash destination, Hash closestTo) {
        if (destination == null) return selectInboundExploratoryTunnel(closestTo);
        TunnelPool pool = _clientInboundPools.get(destination);
        if (pool != null) {
            return pool.selectTunnel(closestTo);
        }
        if (_log.shouldWarn()) {_log.warn("No pool available for Inbound tunnel for " + destination.toBase32());}
        return null;
    }

    /**
     * Pick the outbound exploratory tunnel with the endpoint closest to the given hash.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectOutboundExploratoryTunnel(Hash closestTo) {
        TunnelInfo info = _outboundExploratory.selectTunnel(closestTo);
        if (info == null) {
            _outboundExploratory.buildFallback();
            info = _outboundExploratory.selectTunnel(); // still can be null, but probably not
        }
        return info;
    }

    /**
     * Pick the outbound tunnel with the endpoint closest to the given hash
     * from the given destination's pool.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param destination if null, returns outbound exploratory tunnel
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectOutboundTunnel(Hash destination, Hash closestTo) {
        if (destination == null) return selectOutboundExploratoryTunnel(closestTo);
        TunnelPool pool = _clientOutboundPools.get(destination);
        if (pool != null) {
            return pool.selectTunnel(closestTo);
        }
        return null;
    }

    /** @return number of inbound exploratory tunnels */
    public int getFreeTunnelCount() {return _inboundExploratory.size();}

    /** @return number of outbound exploratory tunnels */
    public int getOutboundTunnelCount() {return _outboundExploratory.size();}

    public int getInboundClientTunnelCount() {
        int count = 0;
        for (TunnelPool pool : _clientInboundPools.values()) {
            count += pool.listTunnels().size();
        }
        return count;
    }

    public int getOutboundClientTunnelCount() {
        int count = 0;
        for (TunnelPool pool : _clientOutboundPools.values()) {
            count += pool.listTunnels().size();
        }
        return count;
    }

    /**
     *  Use to verify a tunnel pool is alive
     *  @since 0.7.11
     */
    public int getOutboundClientTunnelCount(Hash destination)  {
        TunnelPool pool = _clientOutboundPools.get(destination);
        if (pool != null) {return pool.getTunnelCount();}
        return 0;
    }

    public int getParticipatingCount() { return _context.tunnelDispatcher().getParticipatingCount(); }

    public long getLastParticipatingExpiration() { return _context.tunnelDispatcher().getLastParticipatingExpiration(); }

    /**
     *  @return (number of part. tunnels) / (estimated total number of hops in our expl.+client tunnels)
     *  We just use length setting, not variance, for speed
     *  @since 0.7.10
     */
    public double getShareRatio() {
        int part = getParticipatingCount();
        if (part <= 0) {return 0d;}
        List<TunnelPool> pools = new ArrayList<TunnelPool>();
        listPools(pools);
        int count = 0;
        for (int i = 0; i < pools.size(); i++) {
            TunnelPool pool = pools.get(i);
            count += pool.size() * pool.getSettings().getLength();
        }
        if (count <= 0) {return MAX_SHARE_RATIO;}
        return Math.min(part / (double) count, MAX_SHARE_RATIO);
    }

    public boolean isValidTunnel(Hash client, TunnelInfo tunnel) {
        if (tunnel.getTunnelFailed()) {return false;}
        if (tunnel.getExpiration() < _context.clock().now()) {return false;}
        TunnelPool pool;
        if (tunnel.isInbound()) {pool = _clientInboundPools.get(client);}
        else {pool = _clientOutboundPools.get(client);}
        if (pool == null) {return false;}
        return pool.listTunnels().contains(tunnel);
    }

    /** exploratory */
    public TunnelPoolSettings getInboundSettings() { return _inboundExploratory.getSettings(); }

    /** exploratory */
    public TunnelPoolSettings getOutboundSettings() { return _outboundExploratory.getSettings(); }

    /** exploratory */
    public void setInboundSettings(TunnelPoolSettings settings) { _inboundExploratory.setSettings(settings); }

    /** exploratory */
    public void setOutboundSettings(TunnelPoolSettings settings) { _outboundExploratory.setSettings(settings); }

    public TunnelPoolSettings getInboundSettings(Hash client) {
        TunnelPool pool = _clientInboundPools.get(client);
        if (pool != null) {return pool.getSettings();}
        else {return null;}
    }

    public TunnelPoolSettings getOutboundSettings(Hash client) {
        TunnelPool pool = _clientOutboundPools.get(client);
        if (pool != null) {return pool.getSettings();}
        else {return null;}
    }

    public void setInboundSettings(Hash client, TunnelPoolSettings settings) {
        setSettings(_clientInboundPools, client, settings);
    }

    public void setOutboundSettings(Hash client, TunnelPoolSettings settings) {
        setSettings(_clientOutboundPools, client, settings);
    }

    private static void setSettings(Map<Hash, TunnelPool> pools, Hash client, TunnelPoolSettings settings) {
        TunnelPool pool = pools.get(client);
        if (pool != null) {pool.setSettings(settings);}
    }

    public synchronized void restart() {
        _handler.restart();
        _executor.restart();
        shutdownExploratory();
        startup();
    }

    /**
     *  Used only at session startup.
     *  Do not use to change settings.
     *  Do not use for aliased destinations; use addAlias().
     */
    public void buildTunnels(Destination client, ClientTunnelSettings settings) {
        Hash dest = client.calculateHash();
        if (_log.shouldDebug()) {
            _log.debug("Building tunnels for client " + client.toBase32() + ": " + settings);
        }
        TunnelPool inbound = null;
        TunnelPool outbound = null;

        boolean delayOutbound = false;
        // synch with removeTunnels() below
        synchronized (this) {
            inbound = _clientInboundPools.get(dest);
            if (inbound == null) {
                inbound = new TunnelPool(_context, this, settings.getInboundSettings(),
                                         _clientPeerSelector);
                _clientInboundPools.put(dest, inbound);
            } else {inbound.setSettings(settings.getInboundSettings());}
            outbound = _clientOutboundPools.get(dest);
            if (outbound == null) {
                outbound = new TunnelPool(_context, this, settings.getOutboundSettings(),
                                          _clientPeerSelector);
                _clientOutboundPools.put(dest, outbound);
                delayOutbound = true;
            } else {outbound.setSettings(settings.getOutboundSettings());}
        }
        inbound.startup();
        // Don't delay the outbound if it already exists, as this opens up a large
        // race window with removeTunnels() below
        if (delayOutbound) {_context.simpleTimer2().addEvent(new DelayedStartup(outbound), 1000);}
        else {outbound.startup();}
    }

    /**
     *  Add another destination to the same tunnels.
     *  Must have same encryption key and a different signing key.
     *  @throws IllegalArgumentException if not
     *  @return success
     *  @since 0.9.21
     */
    public boolean addAlias(Destination dest, ClientTunnelSettings settings, Destination existingClient) {
        if (dest.getSigningPublicKey().equals(existingClient.getSigningPublicKey())) {
            throw new IllegalArgumentException("Signing key must differ");
        }
        if (!dest.getPublicKey().equals(existingClient.getPublicKey())) {
            throw new IllegalArgumentException("Encryption key mismatch");
        }
        Hash h = dest.calculateHash();
        Hash e = existingClient.calculateHash();
        synchronized(this) {
            TunnelPool inbound = _clientInboundPools.get(h);
            TunnelPool outbound = _clientOutboundPools.get(h);
            if (inbound != null || outbound != null) {
                if (_log.shouldWarn()) {_log.warn("Already have alias " + dest.toBase32());}
                return false;
            }
            TunnelPool eInbound = _clientInboundPools.get(e);
            TunnelPool eOutbound = _clientOutboundPools.get(e);
            if (eInbound == null || eOutbound == null) {
                if (_log.shouldWarn()) {_log.warn("Primary not found " + existingClient);}
                return false;
            }
            eInbound.getSettings().getAliases().add(h);
            eOutbound.getSettings().getAliases().add(h);
            TunnelPoolSettings newIn = settings.getInboundSettings();
            TunnelPoolSettings newOut = settings.getOutboundSettings();
            newIn.setAliasOf(e);
            newOut.setAliasOf(e);
            inbound = new AliasedTunnelPool(_context, this, newIn, eInbound);
            outbound = new AliasedTunnelPool(_context, this, newOut, eOutbound);
            _clientInboundPools.put(h, inbound);
            _clientOutboundPools.put(h, outbound);
            inbound.startup();
            outbound.startup();
        }
        if (_log.shouldWarn()) {
            _log.warn("Added " + dest.toBase32() + " as alias for " + existingClient.toBase32() + settings);
        }
        return true;
    }

    /**
     *  Remove a destination for the same tunnels as another.
     *  @since 0.9.21
     */
    public void removeAlias(Destination dest) {
        Hash h = dest.calculateHash();
        synchronized(this) {
            TunnelPool inbound = _clientInboundPools.remove(h);
            if (inbound != null) {
                Hash p = inbound.getSettings().getAliasOf();
                if (p != null) {
                    TunnelPool pri = _clientInboundPools.get(p);
                    if (pri != null) {
                        Set<Hash> aliases = pri.getSettings().getAliases();
                        if (aliases != null) {aliases.remove(h);}
                    }
                }
            }
            TunnelPool outbound = _clientOutboundPools.remove(h);
            if (outbound != null) {
                Hash p = outbound.getSettings().getAliasOf();
                if (p != null) {
                    TunnelPool pri = _clientOutboundPools.get(p);
                    if (pri != null) {
                        Set<Hash> aliases = pri.getSettings().getAliases();
                        if (aliases != null) {aliases.remove(h);}
                    }
                }
            }
            // TODO if primary already vanished...
        }
    }

    private static class DelayedStartup implements SimpleTimer.TimedEvent {
        private final TunnelPool pool;

        public DelayedStartup(TunnelPool p) {this.pool = p;}

        public void timeReached() {this.pool.startup();}
    }

    /**
     *  Must be called AFTER deregistration by the client manager.
     *
     *  @since 0.9.48
     */
    public void removeTunnels(Destination dest) {
        removeTunnels(dest.calculateHash());
    }

    /**
     *  This will be called twice, once by the inbound and once by the outbound pool.
     *  Synched with buildTunnels() above.
     *
     *  Must be called AFTER deregistration by the client manager.
     *
     */
    public synchronized void removeTunnels(Hash destination) {
        if (destination == null) return;
        if (_log.shouldDebug()) {
            _log.debug("Removing tunnel pool for client [" + destination.toBase32().substring(0,8) + "]");
        }
        if (_context.clientManager().isLocal(destination)) {
            // race with buildTunnels() on restart of a client
            if (_log.shouldWarn()) {
                _log.warn("Not removing tunnel pool for [" + destination.toBase32().substring(0,8) + "] -> Still registered with ClientManager");
            }
            return;
        }
        TunnelPool inbound = _clientInboundPools.remove(destination);
        TunnelPool outbound = _clientOutboundPools.remove(destination);
        if (inbound != null) {inbound.shutdown();}
        if (outbound != null) {outbound.shutdown();}
    }

    /** queue a recurring test job if appropriate */
    void buildComplete(PooledTunnelCreatorConfig cfg) {
        if (cfg.getLength() > 1 &&
            !_context.router().gracefulShutdownInProgress() &&
            (!disableTunnelTesting() || _context.router().isHidden() ||
             _context.router().getRouterInfo().getAddressCount() <= 0)) {
            TunnelPool pool = cfg.getTunnelPool();
            // Check if we should schedule a TestJob before creating it
            if (TestJob.shouldSchedule(_context, cfg)) {
                TestJob job = new TestJob(_context, cfg, pool);
                if (job.isValid()) {
                    _context.jobQueue().addJob(job);
                }
            }
        }
    }

    public boolean disableTunnelTesting() {
        if (_context.getProperty(PROP_DISABLE_TUNNEL_TESTING) == null) {return false;}
        else {return _context.getBooleanProperty(PROP_DISABLE_TUNNEL_TESTING);}
    }

    public synchronized void startup() {
        _isShutdown = false;
        if (!_executor.isRunning()) {
            I2PThread t = new I2PThread(_executor, "BuildExecutor", true);
            t.start();
            _handler.init();
            for (int i = 1; i <= _numHandlerThreads; i++) {
                I2PThread hThread = new I2PThread(_handler, "BuildHandler " + i + '/' + _numHandlerThreads, true);
                hThread.start();
            }
        }

        _inboundExploratory.startup();
        _context.simpleTimer2().addEvent(new DelayedStartup(_outboundExploratory), 3*1000);

        // try to build up longer tunnels
        _context.jobQueue().addJob(new BootstrapPool(_context, _inboundExploratory));
        _context.jobQueue().addJob(new BootstrapPool(_context, _outboundExploratory));
    }

    private static class BootstrapPool extends JobImpl {
        private final TunnelPool _pool;
        public BootstrapPool(RouterContext ctx, TunnelPool pool) {
            super(ctx);
            _pool = pool;
            getTiming().setStartAfter(ctx.clock().now() + 5*1000);
        }
        public String getName() { return "Bootstrap Tunnel Pool"; }
        public void runJob() {
            _pool.buildFallback();
        }
    }

    /**
     *  Cannot be restarted
     */
    public synchronized void shutdown() {
        _handler.shutdown(_numHandlerThreads);
        _executor.shutdown();
        shutdownExploratory();
        _isShutdown = true;
    }

    private void shutdownExploratory() {
            _inboundExploratory.shutdown();
            _outboundExploratory.shutdown();
    }

    /** list of TunnelPool instances currently in play */
    public void listPools(List<TunnelPool> out) {
        out.addAll(_clientInboundPools.values());
        out.addAll(_clientOutboundPools.values());
        out.add(_inboundExploratory);
        out.add(_outboundExploratory);
    }

    /**
     *  Poke the build executor to build more tunnels.
     */
    void tunnelFailed() { _executor.repoll(); }

    BuildExecutor getExecutor() { return _executor; }

    /** Remove a tunnel from the expiration queue to prevent memory leak */
    void removeFromExpiration(PooledTunnelCreatorConfig cfg) {
        _executor.removeFromExpiration(cfg);
    }

    /** @return the ghost peer manager for tracking unresponsive peers @since 0.9.68+ */
    public GhostPeerManager getGhostPeerManager() { return _ghostPeerManager; }

    boolean isShutdown() { return _isShutdown; }

    public int getInboundBuildQueueSize() { return _handler.getInboundBuildQueueSize(); }

    /** @deprecated moved to routerconsole */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {
    }

    /** @return total number of non-fallback expl. + client tunnels */
    private int countTunnelsPerPeer(ObjectCounterUnsafe<Hash> lc) {
        List<TunnelPool> pools = new ArrayList<TunnelPool>();
        listPools(pools);
        int tunnelCount = 0;
        for (TunnelPool tp : pools) {
            for (TunnelInfo info : tp.listTunnels()) {
                if (info.getLength() > 1) {
                    tunnelCount++;
                    for (int j = 0; j < info.getLength(); j++) {
                        Hash peer = info.getPeer(j);
                        if (!_context.routerHash().equals(peer)) {
                            lc.increment(peer);
                        }
                    }
                }
            }
        }
        return tunnelCount;
    }

    /**
     * Count tunnels and peers separately for exploratory or client pools.
     *
     * @param lc object counter to store peer counts
     * @param exploratory if true, count exploratory tunnels; if false, count client tunnels
     * @return total number of tunnels counted
     * @since 0.9.68+
     */
    private int countTunnelsPerPeer(ObjectCounterUnsafe<Hash> lc, boolean exploratory) {
        List<TunnelPool> pools = new ArrayList<TunnelPool>();
        if (exploratory) {
            pools.add(_inboundExploratory);
            pools.add(_outboundExploratory);
        } else {
            pools.addAll(_clientInboundPools.values());
            pools.addAll(_clientOutboundPools.values());
        }
        int tunnelCount = 0;
        for (TunnelPool tp : pools) {
            for (TunnelInfo info : tp.listTunnels()) {
                if (info.getLength() > 1) {
                    tunnelCount++;
                    for (int j = 0; j < info.getLength(); j++) {
                        Hash peer = info.getPeer(j);
                        if (!_context.routerHash().equals(peer)) {
                            lc.increment(peer);
                        }
                    }
                }
            }
        }
        return tunnelCount;
    }

    /**
     *  For reliability reasons, don't allow a peer in more than x% of
     *  client and exploratory tunnels.
     *
     *  This also will prevent a single huge-capacity (or malicious) peer from
     *  taking all the tunnels in the network (although it would be nice to limit
     *  % of total network tunnels to 10% or so, but that appears to be
     *  too low to set as a default here... much lower than 33% will push client
     *  tunnels out of fast tier into high cap or beyond...)
     *
     *  Possible improvement - restrict based on count per IP, or IP block,
     *  to slightly increase costs of collusion
     *
     *  @return Set of peers that should not be allowed in another tunnel
     */
     public Set<Hash> selectPeersInTooManyTunnels() {
        Set<Hash> rv = new HashSet<Hash>();
        long uptime = _context.router().getUptime();
        int max = uptime > 30*60*1000 ? DEFAULT_MAX_PCT_TUNNELS : STARTUP_MAX_PCT_TUNNELS;

        // Increase threshold under low tunnel build success @since 0.9.68+
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        if (isFirewalled() || buildSuccess < 0.40) {max *=2;}

        // Count exploratory and client tunnels separately @since 0.9.68+
        ObjectCounterUnsafe<Hash> lcExp = new ObjectCounterUnsafe<Hash>();
        int exploratoryCount = countTunnelsPerPeer(lcExp, true);

        ObjectCounterUnsafe<Hash> lcClient = new ObjectCounterUnsafe<Hash>();
        int clientCount = countTunnelsPerPeer(lcClient, false);

        // Check percentage limits separately for each tunnel type
        for (Hash h : lcExp.objects()) {
            if (lcExp.count(h) > 0 && exploratoryCount > 0 &&
                (lcExp.count(h) + 1) * 100 / (exploratoryCount + 1) > max) {
                rv.add(h);
            }
        }

        for (Hash h : lcClient.objects()) {
            if (lcClient.count(h) > 0 && clientCount > 0 &&
                (lcClient.count(h) + 1) * 100 / (clientCount + 1) > max) {
                rv.add(h);
            }
        }

        return rv;
    }

    public boolean isFirewalled() {
        return _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.REJECT_UNSOLICITED ||
               _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_OK ||
               _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_UNKNOWN ||
               _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_OK_IPV6_FIREWALLED ||
               _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_UNKNOWN_IPV6_FIREWALLED ||
               _context.commSystem().getStatus() == net.i2p.router.CommSystemFacade.Status.IPV4_DISABLED_IPV6_FIREWALLED;
    }

    /** for TunnelRenderer in router console */
    public Map<Hash, TunnelPool> getInboundClientPools() {
        return new HashMap<Hash, TunnelPool>(_clientInboundPools);
    }

    /** for TunnelRenderer in router console */
    public Map<Hash, TunnelPool> getOutboundClientPools() {
        return new HashMap<Hash, TunnelPool>(_clientOutboundPools);
    }

    /**
     *  For TunnelRenderer in router console
     *  @return non-null
     */
    public TunnelPool getInboundExploratoryPool() {
        return _inboundExploratory;
    }

    /**
     *  For TunnelRenderer in router console
     *  @return non-null
     */
    public TunnelPool getOutboundExploratoryPool() {
        return _outboundExploratory;
    }

    /**
     *  @return pool or null
     *  @since 0.9.34
     */
    public TunnelPool getInboundPool(Hash client) {
        return _clientInboundPools.get(client);
    }

    /**
     *  @return pool or null
     *  @since 0.9.34
     */
    public TunnelPool getOutboundPool(Hash client) {
        return _clientOutboundPools.get(client);
    }

    /**
     *  Fail all outbound tunnels with this peer as first hop,
     *  and all inbound tunnels with this peer as the last hop,
     *  baecause we can't contact it any more.
     *  This is most likely to be triggered by an outbound tunnel.
     *
     *  @since 0.8.13
     */
    public void fail(Hash peer) {
        failTunnelsWithFirstHop(_outboundExploratory, peer);
        for (TunnelPool pool : _clientOutboundPools.values()) {
            failTunnelsWithFirstHop(pool, peer);
        }
        failTunnelsWithLastHop(_inboundExploratory, peer);
        for (TunnelPool pool : _clientInboundPools.values()) {
            failTunnelsWithLastHop(pool, peer);
        }
    }

    /**
     *  Fail all (outbound) tunnels with this peer as first hop (not counting us)
     *
     *  @since 0.8.13
     */
    private void failTunnelsWithFirstHop(TunnelPool pool, Hash peer) {
        for (TunnelInfo tun : pool.listTunnels()) {
            int len = tun.getLength();
            if (len > 1 && tun.getPeer(1).equals(peer)) {
                if (_log.shouldWarn()) {
                    _log.warn("Removing " + tun + " -> First hop [" + peer.toBase64().substring(0,6) + "] is banlisted");
                }
                pool.tunnelFailed(tun, peer);
            }
        }
    }

    /**
     *  Fail all (inbound) tunnels with this peer as last hop (not counting us)
     *
     *  @since 0.8.13
     */
    private void failTunnelsWithLastHop(TunnelPool pool, Hash peer) {
        for (TunnelInfo tun : pool.listTunnels()) {
            int len = tun.getLength();
            if (len > 1 && tun.getPeer(len - 2).equals(peer)) {
                if (_log.shouldWarn()) {
                    _log.warn("Removing " + tun + " -> Previous hop [" + peer.toBase64().substring(0,6) + "] is banlisted");
                }
                pool.tunnelFailed(tun, peer);
            }
        }
    }
}
