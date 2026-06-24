package net.i2p.router.tunnel.pool;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.peermanager.PeerTestJob;
import net.i2p.router.peermanager.PeerManagerFacadeImpl;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelCreatorConfig;
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
    private static final String PROP_SLOW_TUNNEL_THRESHOLD = "router.tunnel.slowThreshold";
    private static final String PROP_SLOW_TUNNEL_MIN = "router.tunnel.slowThresholdMin";
    private static final String PROP_SLOW_TUNNEL_INTERVAL = "router.tunnel.slowTunnelInterval";
    private static final String PROP_PRUNE_EARLY_EXPIRY = "router.tunnel.pruneEarlyExpiryDelay";
    private static final long DEFAULT_PRUNE_EARLY_EXPIRY = 30*1000; // 30 seconds
    private static final int DEFAULT_SLOW_THRESHOLD_MS = 0; // 0 means use avg latency with min
    private static final int DEFAULT_MIN_SLOW_THRESHOLD = 3000; // 3s minimum threshold if not configured
    private static final int DEFAULT_RUN_INTERVAL_MS = 15*1000; // 15s — fast rebuild after failures
    private static final long REFRESH_DELAY_AFTER_REMOVAL = 15*1000; // wait for new tunnels to build
    private static final double MAX_SHARE_RATIO = 100000d;

    /**
     * Delay before removing pools after client disconnect.
     * Allows tunnels to naturally expire rather than collapsing.
     * @since 0.9.69+
     */
    private static final long POOL_CLEANUP_DELAY_MS = 30 * 1000; // 30 seconds

    /**
     * Pending delayed cleanups for pool removal.
     * Key: destination hash, Value: cleanup task
     * @since 0.9.69+
     */
    private final Map<Hash, DelayedPoolCleanup> _pendingCleanups;

    public TunnelPoolManager(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPoolManager.class);
        _clientInboundPools = new ConcurrentHashMap<>(32);
        _clientOutboundPools = new ConcurrentHashMap<>(32);
        _pendingCleanups = new ConcurrentHashMap<>(32);
        _clientPeerSelector = new ClientPeerSelector(ctx);
        _ghostPeerManager = new GhostPeerManager(ctx);
        ExploratoryPeerSelector selector = new ExploratoryPeerSelector(_context);
        TunnelPoolSettings inboundSettings = new TunnelPoolSettings(true);
        _inboundExploratory = new TunnelPool(_context, this, inboundSettings, selector);
        TunnelPoolSettings outboundSettings = new TunnelPoolSettings(false);
        _outboundExploratory = new TunnelPool(_context, this, outboundSettings, selector);
        // Set paired pools for exploratory tunnels
        _inboundExploratory.setPairedPool(_outboundExploratory);
        _outboundExploratory.setPairedPool(_inboundExploratory);

        // threads will be started in startup()
        _executor = new BuildExecutor(ctx, this, _ghostPeerManager);
        _handler = new BuildHandler(ctx, this, _executor);
        int numHandlerThreads;
        Boolean isSlow = SystemVersion.isSlow();
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
        ctx.statManager().createRequiredRateStat("tunnel.testExploratoryFailedTime", "Time to fail exploratory tunnel test (max 60s)", "Tunnels [Exploratory]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.testFailedCompletelyTime", "Time to complete fail for tunnel test (max 60s)", "Tunnels", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.testExploratoryFailedCompletelyTime", "Time to complete fail for exploratory tunnel test (max 60s)", "Tunnels [Exploratory]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.testSuccessLength", "Length (hops) of tunnels passing test", "Tunnels", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.testSuccessTime", "Time for tunnel test success (ms)", "Tunnels", TEST_RATES);
        ctx.statManager().createRequiredRateStat("tunnel.testAborted", "Time taken by aborted tunnel tests (no available peers)", "Tunnels", RATES);
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
        if (pool != null) {return pool.selectTunnel();}
        if (_log.shouldWarn()) {_log.warn("No pool available for Outbound tunnel for " + destination.toBase32());}
        return null;
    }

    /**
     * Pick a random outbound tunnel from any pool — client pools first,
     * then exploratory.  Used by BuildRequestor for cross-pool build
     * replies when the requesting pool's own tunnels are unavailable.
     * Client tunnels provide a lower-latency reply path than exploratory.
     *
     * @return null if none
     */
    public TunnelInfo selectAnyOutboundTunnel() {
        List<TunnelPool> pools = new ArrayList<TunnelPool>(_clientOutboundPools.values());
        Collections.shuffle(pools);
        for (TunnelPool pool : pools) {
            TunnelInfo info = pool.selectTunnel();
            if (info != null) return info;
        }
        return selectOutboundTunnel();
    }

    /**
     * Pick a random inbound tunnel from any pool — client pools first,
     * then exploratory.  Used by BuildRequestor for cross-pool build
     * replies when the requesting pool's own tunnels are unavailable.
     *
     * @return null if none
     */
    public TunnelInfo selectAnyInboundTunnel() {
        List<TunnelPool> pools = new ArrayList<TunnelPool>(_clientInboundPools.values());
        Collections.shuffle(pools);
        for (TunnelPool pool : pools) {
            TunnelInfo info = pool.selectTunnel();
            if (info != null) return info;
        }
        return selectInboundTunnel();
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

    /** @return number of valid (non-failed, non-expired) inbound exploratory tunnels */
    public int getFreeTunnelCount() {return _inboundExploratory.getValidTunnelCount();}

    /** @return number of valid (non-failed, non-expired) outbound exploratory tunnels */
    public int getOutboundTunnelCount() {return _outboundExploratory.getValidTunnelCount();}

    /** @return number of GOOD (tested+passed, non-failed, non-expired) inbound client tunnels across all destinations */
    public int getInboundClientTunnelCount() {
        int count = 0;
        for (TunnelPool pool : _clientInboundPools.values()) {
            count += pool.getActiveTunnelCount();
        }
        return count;
    }

    /** @return number of GOOD (tested+passed, non-failed, non-expired) outbound client tunnels across all destinations */
    public int getOutboundClientTunnelCount() {
        int count = 0;
        for (TunnelPool pool : _clientOutboundPools.values()) {
            count += pool.getActiveTunnelCount();
        }
        return count;
    }

    /**
     *  Use to verify a tunnel pool is alive.
     *  During bootstrap (no GOOD tunnels yet), counts any valid tunnel
     *  (including UNTESTED) so the sidebar shows ok immediately after
     *  the first build.  Once the pool has established GOOD tunnels,
     *  enforces a strict GOOD-only count — UNTESTED tunnels alone
     *  don't keep the indicator green.
     *  @return number of outbound client tunnels for the given destination
     *  @since 0.7.11
     */
    public int getOutboundClientTunnelCount(Hash destination)  {
        TunnelPool pool = _clientOutboundPools.get(destination);
        if (pool != null) {
            int good = pool.getActiveTunnelCount();
            if (good > 0) {return good;}
            return pool.getValidTunnelCount();
        }
        return 0;
    }

    /**
     *  Use to verify a tunnel pool is alive.
     *  During bootstrap (no GOOD tunnels yet), counts any valid tunnel
     *  (including UNTESTED) so the sidebar shows ok immediately after
     *  the first build.  Once the pool has established GOOD tunnels,
     *  enforces a strict GOOD-only count.
     *  @return number of inbound client tunnels for the given destination
     *  @since 0.9.68+
     */
    public int getInboundClientTunnelCount(Hash destination)  {
        TunnelPool pool = _clientInboundPools.get(destination);
        if (pool != null) {
            int good = pool.getActiveTunnelCount();
            if (good > 0) {return good;}
            return pool.getValidTunnelCount();
        }
        return 0;
    }

    /**
     *  Get USABLE inbound client tunnel count for a specific destination.
     *  @return number of GOOD (tested+passed, non-failed, non-expired) tunnels
     *  @since 0.9.69+
     */
    public int getUsableInboundClientTunnelCount(Hash destination)  {
        TunnelPool pool = _clientInboundPools.get(destination);
        if (pool != null) {return pool.getActiveTunnelCount();}
        return 0;
    }

    /**
     *  Get USABLE outbound client tunnel count for a specific destination.
     *  @return number of GOOD (tested+passed, non-failed, non-expired) tunnels
     *  @since 0.9.69+
     */
    public int getUsableOutboundClientTunnelCount(Hash destination)  {
        TunnelPool pool = _clientOutboundPools.get(destination);
        if (pool != null) {return pool.getActiveTunnelCount();}
        return 0;
    }

    /** Get the number of tunnels we're participating in as a gateway or endpoint. */
    public int getParticipatingCount() { return _context.tunnelDispatcher().getParticipatingCount(); }

    /** Get the expiration time of the last participating tunnel. */
    public long getLastParticipatingExpiration() { return _context.tunnelDispatcher().getLastParticipatingExpiration(); }

    /**
     *  Get the share ratio of participating tunnels.
     *  @return (number of part. tunnels) / (estimated total number of hops in our expl.+client tunnels)
     *  We just use length setting, not variance, for speed
     *  @since 0.7.10
     */
    public double getShareRatio() {
        int part = getParticipatingCount();
        if (part <= 0) {return 0d;}
        List<TunnelPool> pools = new ArrayList<>();
        listPools(pools);
        int count = 0;
        for (int i = 0; i < pools.size(); i++) {
            TunnelPool pool = pools.get(i);
            count += pool.size() * pool.getSettings().getLength();
        }
        if (count <= 0) {return MAX_SHARE_RATIO;}
        return Math.min(part / (double) count, MAX_SHARE_RATIO);
    }

    /**
     *  Check if a tunnel is valid and belongs to the client's pool.
     *  @param client destination hash
     *  @param tunnel tunnel to validate
     *  @return true if the tunnel is valid and belongs to the client's pool
     */
    public boolean isValidTunnel(Hash client, TunnelInfo tunnel) {
        if (tunnel.getTunnelFailed()) {return false;}
        if (tunnel.getExpiration() < _context.clock().now()) {return false;}
        TunnelPool pool;
        if (tunnel.isInbound()) {pool = _clientInboundPools.get(client);}
        else {pool = _clientOutboundPools.get(client);}
        if (pool == null) {return false;}
        return pool.listTunnels().contains(tunnel);
    }

    /** Get the inbound exploratory tunnel pool settings. */
    public TunnelPoolSettings getInboundSettings() { return _inboundExploratory.getSettings(); }

    /** Get the outbound exploratory tunnel pool settings. */
    public TunnelPoolSettings getOutboundSettings() { return _outboundExploratory.getSettings(); }

    /** Set the inbound exploratory tunnel pool settings. */
    public void setInboundSettings(TunnelPoolSettings settings) { _inboundExploratory.setSettings(settings); }

    /** Set the outbound exploratory tunnel pool settings. */
    public void setOutboundSettings(TunnelPoolSettings settings) { _outboundExploratory.setSettings(settings); }

    /**
     *  Get settings for a client's inbound tunnel pool.
     *  @param client destination hash
     *  @return settings or null if not found
     */
    public TunnelPoolSettings getInboundSettings(Hash client) {
        TunnelPool pool = _clientInboundPools.get(client);
        if (pool != null) {return pool.getSettings();}
        else {return null;}
    }

    /**
     *  Get settings for a client's outbound tunnel pool.
     *  @param client destination hash
     *  @return settings or null if not found
     */
    public TunnelPoolSettings getOutboundSettings(Hash client) {
        TunnelPool pool = _clientOutboundPools.get(client);
        if (pool != null) {return pool.getSettings();}
        else {return null;}
    }

    /**
     *  Set settings for a client's inbound tunnel pool.
     *  @param client destination hash
     */
    public void setInboundSettings(Hash client, TunnelPoolSettings settings) {
        setSettings(_clientInboundPools, client, settings);
    }

    /**
     *  Set settings for a client's outbound tunnel pool.
     *  @param client destination hash
     */
    public void setOutboundSettings(Hash client, TunnelPoolSettings settings) {
        setSettings(_clientOutboundPools, client, settings);
    }

    private static void setSettings(Map<Hash, TunnelPool> pools, Hash client, TunnelPoolSettings settings) {
        TunnelPool pool = pools.get(client);
        if (pool != null) {pool.setSettings(settings);}
    }

    /** Restart all tunnel handlers and rebuild all tunnels */
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
        boolean isClientBootstrapping = false;
        // synch with removeTunnels() below
        synchronized (this) {
            inbound = _clientInboundPools.get(dest);
            if (inbound == null) {
                inbound = new TunnelPool(_context, this, settings.getInboundSettings(),
                                         _clientPeerSelector);
                _clientInboundPools.put(dest, inbound);
                isClientBootstrapping = true;
            } else {inbound.setSettings(settings.getInboundSettings());}
            outbound = _clientOutboundPools.get(dest);
            if (outbound == null) {
                outbound = new TunnelPool(_context, this, settings.getOutboundSettings(),
                                          _clientPeerSelector);
                _clientOutboundPools.put(dest, outbound);
                delayOutbound = true;
                isClientBootstrapping = true;
            } else {outbound.setSettings(settings.getOutboundSettings());}
            // Set paired pools for client tunnels
            inbound.setPairedPool(outbound);
            outbound.setPairedPool(inbound);
        }
        inbound.startup();
        // Don't delay the outbound if it already exists, as this opens up a large
        // race window with removeTunnels() below
        if (delayOutbound) {_context.simpleTimer2().addEvent(new ConditionalOutboundStartup(outbound, inbound, _context), 2000);}
        else {outbound.startup();}

        // Bootstrap newly created client pools to ensure they get initial tunnels
        if (isClientBootstrapping) {
            _context.jobQueue().addJob(new BootstrapClientPool(_context, inbound, outbound));
        }
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
        if (_log.shouldInfo()) {
            _log.info("Added " + dest.toBase32() + " as alias for " + existingClient.toBase32() + settings);
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

    static final String PROP_OUTBOUND_STARTUP_RETRIES = "router.tunnel.outboundStartupRetries";
    private static final int DEFAULT_OUTBOUND_STARTUP_RETRIES = 5;

    /**
     *  Conditionally start the outbound pool after the inbound pool has at least one tunnel.
     *  Retries every 2s; after max attempts, starts unconditionally.
     *  This prevents "Destination not reachable (no LeaseSet found)" at startup.
     */
    private static class ConditionalOutboundStartup implements SimpleTimer.TimedEvent {
        private final TunnelPool outbound;
        private final TunnelPool inbound;
        private final RouterContext ctx;
        private final int maxAttempts;
        private int attempts;

        public ConditionalOutboundStartup(TunnelPool out, TunnelPool in, RouterContext context) {
            this.outbound = out;
            this.inbound = in;
            this.ctx = context;
            this.maxAttempts = context.getProperty(PROP_OUTBOUND_STARTUP_RETRIES, DEFAULT_OUTBOUND_STARTUP_RETRIES);
        }

        public void timeReached() {
            if (inbound.getTunnelCount() > 0 || ++attempts >= maxAttempts) {
                outbound.startup();
            } else {
                ctx.simpleTimer2().addEvent(this, 2000);
            }
        }
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
     *  Uses delayed cleanup to allow tunnels to continue operating
     *  until they naturally expire. Prevents pool collapse when client disconnects.
     *
     */
    public synchronized void removeTunnels(Hash destination) {
        if (destination == null) return;
        if (_log.shouldDebug()) {
            _log.debug("Scheduling tunnel pool removal for client [" + destination.toBase32().substring(0,8) + "]");
        }
        if (_context.clientManager().isLocal(destination)) {
            // race with buildTunnels() on restart of a client
            if (_log.shouldWarn()) {
                _log.warn("Not removing tunnel pool for [" + destination.toBase32().substring(0,8) + "] -> Still registered with ClientManager");
            }
            return;
        }

        // Cancel any existing cleanup for this destination
        DelayedPoolCleanup existingCleanup = _pendingCleanups.remove(destination);
        if (existingCleanup != null) {
            existingCleanup.cancel();
        }

        // Schedule delayed cleanup to allow graceful tunnel expiration
        DelayedPoolCleanup cleanup = new DelayedPoolCleanup(destination);
        _pendingCleanups.put(destination, cleanup);
        _context.simpleTimer2().addEvent(cleanup, POOL_CLEANUP_DELAY_MS);

        if (_log.shouldInfo()) {
            TunnelPool inbound = _clientInboundPools.get(destination);
            TunnelPool outbound = _clientOutboundPools.get(destination);
            int tunnelCount = 0;
            if (inbound != null) tunnelCount += inbound.getTunnelCount();
            if (outbound != null) tunnelCount += outbound.getTunnelCount();
            _log.info("Delayed pool removal scheduled for [" + destination.toBase32().substring(0,8) +
                      "] - " + tunnelCount + " tunnels will expire naturally");
        }
    }

    /**
     * Force immediate removal of a pool - used for router shutdown.
     * @param destination the destination hash
     * @since 0.9.69+
     */
    public synchronized void forceRemoveTunnels(Hash destination) {
        if (destination == null) return;
        // Cancel any pending delayed cleanup
        DelayedPoolCleanup cleanup = _pendingCleanups.remove(destination);
        if (cleanup != null) {cleanup.cancel();}
        doRemoveTunnels(destination);
    }

    /**
     * Actually perform pool removal.
     * @param destination the destination hash
     */
    private synchronized void doRemoveTunnels(Hash destination) {
        if (_log.shouldDebug()) {
            _log.debug("Removing tunnel pool for client [" + destination.toBase32().substring(0,8) + "]");
        }
        TunnelPool inbound = _clientInboundPools.remove(destination);
        TunnelPool outbound = _clientOutboundPools.remove(destination);
        if (inbound != null) {inbound.shutdown();}
        if (outbound != null) {outbound.shutdown();}
        if (_log.shouldInfo()) {
            _log.info("Tunnel pools removed for [" + destination.toBase32().substring(0,8) + "]");
        }
    }

    /**
     * Delayed cleanup task for pool removal.
     * Allows tunnels to continue operating until they naturally expire.
     * @since 0.9.69+
     */
    private class DelayedPoolCleanup implements SimpleTimer.TimedEvent {
        private final Hash _destination;
        private volatile boolean _cancelled = false;

        public DelayedPoolCleanup(Hash dest) {_destination = dest;}

        public void cancel() {_cancelled = true;}

        public void timeReached() {
            if (_cancelled) return;
            _pendingCleanups.remove(_destination);
            // Check if client re-registered during delay
            if (_context.clientManager().isLocal(_destination)) {
                if (_log.shouldInfo()) {
                    _log.info("Cancelling delayed pool removal - client re-registered: " +
                              _destination.toBase32().substring(0,8));
                }
                return;
            }
            doRemoveTunnels(_destination);
        }
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

    /** @return true if tunnel testing is disabled */
    public boolean disableTunnelTesting() {
        if (_context.getProperty(PROP_DISABLE_TUNNEL_TESTING) == null) {return false;}
        else {return _context.getBooleanProperty(PROP_DISABLE_TUNNEL_TESTING);}
    }

    /** Start the tunnel pool manager and build initial tunnels */
    public synchronized void startup() {
        _isShutdown = false;
        RemoveSlowTunnelsJob._runCount.set(0);
        if (!_executor.isRunning()) {
            I2PThread t = new I2PThread(_executor, "BuildExecutor", true);
            t.start();
            _handler.init();
            for (int i = 1; i <= _numHandlerThreads; i++) {
                I2PThread hThread = new I2PThread(_handler, "BuildHandler " + i + '/' + _numHandlerThreads, true);
                hThread.start();
            }
        }

        // Start INBOUND exploratory FIRST - outbound build replies need inbound tunnels
        // to receive the reply message. Starting outbound before inbound guarantees
        // initial outbound builds will fail (no reply path exists yet).
        _inboundExploratory.startup();
        _context.simpleTimer2().addEvent(new SimpleTimer.TimedEvent() {
            public void timeReached() {_outboundExploratory.startup();}
        }, 2*1000);

        // try to build up longer tunnels
        _context.jobQueue().addJob(new BootstrapPool(_context, _outboundExploratory));
        _context.jobQueue().addJob(new BootstrapPool(_context, _inboundExploratory));
        if (!_context.getBooleanProperty("router.tunnel.disableSlowTunnelRemoval")) {
            _context.jobQueue().addJob(new RemoveSlowTunnelsJob(_context, this));
        }
    }

    private static class BootstrapPool extends JobImpl {
        private final TunnelPool _pool;
        public BootstrapPool(RouterContext ctx, TunnelPool pool) {
            super(ctx);
            _pool = pool;
            getTiming().setStartAfter(ctx.clock().now() + 5*1000);
        }
        public String getName() { return "Bootstrap Tunnel Pool"; }
        public void runJob() {_pool.buildFallback();}
    }

    private static class BootstrapClientPool extends JobImpl {
        private final TunnelPool _inbound;
        private final TunnelPool _outbound;
        public BootstrapClientPool(RouterContext ctx, TunnelPool inbound, TunnelPool outbound) {
            super(ctx);
            _inbound = inbound;
            _outbound = outbound;
            getTiming().setStartAfter(ctx.clock().now() + 2*1000);
        }
        public String getName() { return "Bootstrap Client Tunnel Pool"; }
        public void runJob() {
            _inbound.buildFallback();
            _outbound.buildFallback();
        }
    }

    private static class RemoveSlowTunnelsJob extends JobImpl {
        private final TunnelPoolManager _mgr;
        private static final long STARTUP_DELAY = 90*1000; // 90s after startup
        private static final AtomicInteger _runCount = new AtomicInteger(0);

        public RemoveSlowTunnelsJob(RouterContext ctx, TunnelPoolManager mgr) {
            super(ctx);
            _mgr = mgr;
            getTiming().setStartAfter(ctx.clock().now() + STARTUP_DELAY);
        }

        public String getName() { return "Remove Slow Tunnels Job"; }

        public void runJob() {
            if (_mgr.isShutdown()) {
                if (_mgr._log.shouldInfo())
                    _mgr._log.info("Remove Slow Tunnels Job: Manager is shutdown, not rescheduling");
                return;
            }
            if (_mgr._context.getBooleanProperty("router.tunnel.disableSlowTunnelRemoval")) {
                if (_mgr._log.shouldInfo())
                    _mgr._log.info("Remove Slow Tunnels Job: Disabled via router.tunnel.disableSlowTunnelRemoval");
                return;
            }
            if (_mgr._log.shouldInfo())
                _mgr._log.info("Running Remove Slow Tunnels Job...");

            long startTime = System.currentTimeMillis();
            boolean didRemove = false;
            try {
                // Prune excess tunnels to stay within budget (configuration quantity)
                // This ensures we don't accumulate way more tunnels than configured
                _mgr.pruneAllPools();
                didRemove = _mgr.replaceSlowTunnels();
                // Proactive tunnel prebuild: ensure each pool has sufficient tunnels
                // before current ones expire.  This runs every 30-120s.
                List<TunnelPool> pools = new ArrayList<>();
                _mgr.listPools(pools);
                for (TunnelPool pool : pools) {
                    if (pool != null && pool.isAlive()) {
                        pool.ensureSufficientTunnels();
                    }
                }
            } catch (Exception e) {
                if (_mgr._log.shouldWarn())
                    _mgr._log.warn("Error replacing slow tunnels", e);
            }
            long duration = System.currentTimeMillis() - startTime;

            if (_mgr.isShutdown()) {
                if (_mgr._log.shouldInfo())
                    _mgr._log.info("Remove Slow Tunnels Job: Manager shutdown after run, not rescheduling");
                return;
            }

            // Schedule LeaseSet refresh after delay to allow new tunnels to build
            if (didRemove) {
                _mgr._context.jobQueue().addJob(new RefreshLeaseSetsJob(_mgr._context, _mgr));
            }

            // Diagnostic logging
            int runNum = _runCount.incrementAndGet();
            _mgr._log.info("RemoveSlowTunnelsJob run #" + runNum + " completed in " + duration + "ms");

            // Use configurable interval (default 30s), increase if queue is overloaded
            long interval = _mgr._context.getProperty(PROP_SLOW_TUNNEL_INTERVAL, DEFAULT_RUN_INTERVAL_MS);
            int maxWaiting = _mgr._context.getProperty("router.maxWaitingJobs", 750);
            int readyCount = _mgr._context.jobQueue().getReadyCount();
            long maxLag = _mgr._context.jobQueue().getMaxLag();
            long avgLag = _mgr._context.jobQueue().getAvgLag();
            // If queue overloaded, increase interval to reduce load (check both max and avg lag)
            if (readyCount > maxWaiting || maxLag >= 10 || avgLag >= 10) {
                interval = 2 * 60 * 1000; // 2m
                if (_mgr._log.shouldWarn()) {
                    _mgr._log.warn("Job queue overloaded (Ready jobs: " + readyCount + ", Max lag: " + maxLag +
                                   "ms, Avg lag: " + avgLag + "ms) -> Increasing interval to 90s...");
                }
            }
            _mgr._log.info("Remove Slow Tunnels Job: Requeueing in " + (interval / 1000) + "s...");
            long start = _mgr._context.clock().now();
            requeue(interval);
            long after = _mgr._context.clock().now();
            _mgr._log.info("RemoveSlowTunnelsJob requeue took " + (after - start) + "ms, next run at " + (after + interval));
        }
    }

    /**
     * Job to refresh LeaseSets after slow tunnel removal.
     * Runs ~15s after RemoveSlowTunnelsJob to allow new tunnels to build.
     * Triggers client-side LeaseSet signing which flows through
     * RepublishLeaseSetJob for NetDB publication.
     */
    private static class RefreshLeaseSetsJob extends JobImpl {
        private final TunnelPoolManager _mgr;

        public RefreshLeaseSetsJob(RouterContext ctx, TunnelPoolManager mgr) {
            super(ctx);
            _mgr = mgr;
            getTiming().setStartAfter(ctx.clock().now() + REFRESH_DELAY_AFTER_REMOVAL);
        }

        public String getName() { return "Refresh LeaseSets Job"; }

        public void runJob() {
            if (_mgr.isShutdown()) {
                return;
            }
            if (_mgr._log.shouldInfo()) {
                _mgr._log.info("Running Refresh LeaseSets Job...");
            }
            List<TunnelPool> pools = new ArrayList<>();
            _mgr.listPools(pools);
            for (TunnelPool pool : pools) {
                if (_mgr.isShutdown()) {
                    return;
                }
                if (pool != null && pool.getSettings().isInbound() && !pool.getSettings().isExploratory()) {
                    pool.refreshLeaseSet();
                }
            }
        }
    }

    /**
     * Find and remove tunnels exceeding the latency threshold.
     * Does not blame peers for the removal.
     * Removes all such tunnels per pool if pool has more tunnels than configured quantity,
     * but won't go below the configured quantity.
     * Threshold is either the configured property (in ms), or Math.max(minLatency, 1000ms), whichever is larger.
     * @return true if any tunnels were removed
     * @since 0.9.69+
     */
    public boolean replaceSlowTunnels() {
        List<TunnelPool> pools = new ArrayList<>();
        listPools(pools);
        boolean didRemove = false;

        // First pass: calculate global average and minimum latency using average of last 3 tests
        long totalLatency = 0;
        int latencyCount = 0;
        int minLatency = Integer.MAX_VALUE;
        for (TunnelPool pool : pools) {
            if (pool == null) continue;
            try {
                for (TunnelInfo info : pool.listTunnels()) {
                    // Use average latency when available (requires 3+ tests), fall back to last latency
                    int lat = -1;
                    if (info instanceof PooledTunnelCreatorConfig) {
                        PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
                        if (cfg.hasEnoughLatencyTests()) {
                            lat = cfg.getAverageLatency();
                        } else {
                            lat = cfg.getLastLatency();
                        }
                    } else {
                        lat = info.getLastLatency();
                    }
                    if (lat > 0) {
                        totalLatency += lat;
                        latencyCount++;
                        if (lat < minLatency) minLatency = lat;
                    }
                }
            } catch (Exception e) {
                // Pool might have been shut down between listPools() and now
            }
        }

        double avg = latencyCount > 0 ? (double) totalLatency / latencyCount : -1;
        if (avg <= 0) {
            if (_log.shouldWarn())
                _log.warn("Replace slow tunnels: No latency data available (avg <= 0)");
            return false;
        }

        // Ensure we have a valid minimum
        if (minLatency == Integer.MAX_VALUE) minLatency = 0;

        int configuredThreshold = _context.getProperty(PROP_SLOW_TUNNEL_THRESHOLD, DEFAULT_SLOW_THRESHOLD_MS);
        if (_log.shouldInfo()) {
            _log.info("Replace slow tunnels: avg latency = " + String.format("%.0f", avg) + "ms, min latency = " +
                      minLatency + "ms, configured threshold = " + configuredThreshold + "ms");
        }
        double threshold;
        if (configuredThreshold > 0) {
            threshold = configuredThreshold;
        } else {
            // Use avg latency as threshold, but minimum DEFAULT_MIN_SLOW_THRESHOLD (2000ms)
            threshold = Math.max(avg, DEFAULT_MIN_SLOW_THRESHOLD);
        }
        if (_log.shouldDebug()) {
            _log.debug("Using threshold: " + String.format("%.0f", threshold) + "ms");
        }

        // Second pass: identify and remove slow tunnels
        // Uses two-phase approach to prevent deadlock:
        // Phase A: Identify candidates under pool lock
        // Phase B: Remove them OUTSIDE the lock to prevent ABBA deadlock
        for (TunnelPool pool : pools) {
            if (pool == null) continue;

            // Skip server (inbound client) pools — their latency doesn't affect our sends,
            // and removing them early causes LeaseSet churn and connectivity gaps.
            // Server tunnels are only removed by pruneExcessTunnels() and natural expiry.
            if (pool.getSettings().isInbound() && !pool.getSettings().isExploratory()) {
                continue;
            }

            List<TunnelInfo> toRemove = new ArrayList<>();

            // PHASE A: Snapshot decision under lock
            synchronized (pool) {
                int currentCount = pool.getTunnelCount();
                int configuredQty = pool.getSettings().getQuantity();
                int minOverride = _context.getProperty(PROP_SLOW_TUNNEL_MIN, 0);
                int minToKeep = Math.max(2, minOverride > 0 ? minOverride : configuredQty);

                if (currentCount <= minToKeep) continue;

                if (_log.shouldDebug()) {
                    _log.debug("Pool " + pool + " has " + currentCount + " tunnels (min: " + minToKeep + "), checking for slow tunnels...");
                }

                // Create snapshot WHILE HOLDING THE LOCK
                List<TunnelInfo> tunnelSnapshot = new ArrayList<>(pool.listTunnels());
                int maxToRemove = currentCount - minToKeep;
                int found = 0;

                for (TunnelInfo info : tunnelSnapshot) {
                    if (found >= maxToRemove) break;
                    // Re-check validity inside lock
                    if (info.getTunnelFailed() || info.getExpiration() <= _context.clock().now()) {
                        // Remove failed tunnels if we're over budget
                        toRemove.add(info);
                        found++;
                        continue;
                    }
                    // Also remove zero-hop tunnels (length <= 1) if we're over budget
                    if (info.getLength() <= 1) {
                        toRemove.add(info);
                        found++;
                        continue;
                    }
                    // Use average latency, require at least 3 tests before removal
                    if (info instanceof PooledTunnelCreatorConfig) {
                        PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
                        if (cfg.hasEnoughLatencyTests()) {
                            int avgLatency = cfg.getAverageLatency();
                            if (avgLatency > threshold) {
                                toRemove.add(info);
                                found++;
                            }
                        } else if (cfg.getLastLatency() > threshold * 1.5) {
                            // Only mark for expedited test if significantly over threshold AND no 3 tests yet
                            cfg.requestExpeditedTest();
                        }
                    }
                }
            } // Lock released

            // PHASE B: Schedule early expiry for slow tunnels OUTSIDE the lock
            long now = _context.clock().now();
            long pruneDelay = _context.getProperty("router.tunnel.pruneEarlyExpiryDelay", 30000L);
            for (TunnelInfo info : toRemove) {
                // Re-verify tunnel is still valid (another thread may have removed it)
                if (info.getTunnelFailed() || info.getExpiration() <= now) {
                    continue;
                }
                // Skip if already scheduled for early expiry
                if (info.getExpiration() < now + pruneDelay) {
                    continue;
                }
                // Use early expiry via ExpireJob for graceful removal
                if (info instanceof PooledTunnelCreatorConfig) {
                    PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig) info;
                    cfg.setExpiration(now + pruneDelay);
                    cfg.setTestTooSlow();
                    ExpireJob.scheduleExpiration(_context, cfg);
                    // Schedule all peers in this tunnel for priority testing
                    List<Hash> tunnelPeers = new ArrayList<>();
                    for (int i = 0; i < cfg.getLength(); i++) {
                        tunnelPeers.add(cfg.getPeer(i));
                    }
                    if (!tunnelPeers.isEmpty()) {
                        schedulePeerTests(tunnelPeers);
                    }
                    if (_log.shouldDebug()) {
                        _log.debug("Scheduling early expiry for slow tunnel: " + cfg.getReceiveTunnelId(0));
                    }
                } else if (info instanceof TunnelCreatorConfig) {
                    // ExpireJob.scheduleExpiration requires PooledTunnelCreatorConfig,
                    // so fall back to direct removal for non-pooled tunnel configs
                    try {
                        pool.removeTunnel(info);
                    } catch (Exception e) {
                        _log.warn("Exception removing slow tunnel " + info, e);
                    }
                }
            }

            if (!toRemove.isEmpty()) {
                didRemove = true;
                _log.warn("Scheduled early expiry for " + toRemove.size() + " slow tunnels from " + pool);
            }
        }
        return didRemove;
    }

    /**
     * Prune excess tunnels from all pools to stay within budget.
     * Called periodically from RemoveSlowTunnelsJob to ensure we don't exceed
     * the configured tunnel quantity for any pool.
     * @since 0.9.69+
     */
    public void pruneAllPools() {
        List<TunnelPool> pools = new ArrayList<>();
        listPools(pools);
        for (TunnelPool pool : pools) {
            if (pool == null || !pool.isAlive()) continue;
            try {
                int pruned = pool.pruneExcessTunnels();
                if (pruned > 0 && _log.shouldInfo()) {
                    _log.info("Pruned " + pruned + " excess tunnels from " + pool);
                }
            } catch (Exception e) {
                if (_log.shouldWarn())
                    _log.warn("Error pruning tunnels from pool", e);
            }
        }
    }

    /**
     *  Cannot be restarted
     */
    public synchronized void shutdown() {
        _handler.shutdown(_numHandlerThreads);
        _executor.shutdown();

        // Cancel all pending delayed cleanups and force remove all client pools
        for (Iterator<Map.Entry<Hash, DelayedPoolCleanup>> iter = _pendingCleanups.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<Hash, DelayedPoolCleanup> entry = iter.next();
            entry.getValue().cancel();
            iter.remove();
        }

        // Force remove all client pools immediately
        for (Iterator<Map.Entry<Hash, TunnelPool>> iter = _clientInboundPools.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<Hash, TunnelPool> entry = iter.next();
            TunnelPool pool = entry.getValue();
            if (pool != null) {pool.shutdown();}
            iter.remove();
        }
        for (Iterator<Map.Entry<Hash, TunnelPool>> iter = _clientOutboundPools.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<Hash, TunnelPool> entry = iter.next();
            TunnelPool pool = entry.getValue();
            if (pool != null) {pool.shutdown();}
            iter.remove();
        }

        shutdownExploratory();
        _isShutdown = true;

        if (_log.shouldInfo()) {
            _log.info("TunnelPoolManager shutdown complete");
        }
    }

    /**
     * Schedule peers for priority testing.
     * @param peers list of peer hashes to test urgently
     * @since 0.9.69+
     */
    private void schedulePeerTests(List<Hash> peers) {
        if (peers == null || peers.isEmpty()) return;
        PeerTestJob peerTestJob = ((PeerManagerFacadeImpl) _context.peerManager()).getPeerTestJob();
        if (peerTestJob != null) {
            peerTestJob.schedulePriorityTests(peers);
        }
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

    /** @return the build executor */
    BuildExecutor getExecutor() { return _executor; }

    /** Remove a tunnel from the expiration queue to prevent memory leak */
    void removeFromExpiration(PooledTunnelCreatorConfig cfg) {
        ExpireJob.removeFromExpiration(cfg);
    }

    /**
     * Remove a participating tunnel from the expiration queue.
     * Called when IdleTunnelMonitor drops idle tunnels.
     * @param cfg the hop config to remove
     */
    public void removeFromExpirationHop(HopConfig cfg) {
        // HopConfig-based removal is a no-op for now; only PooledTunnelCreatorConfig tracked
    }

    /** @return the ghost peer manager for tracking unresponsive peers @since 0.9.69+ */
    public GhostPeerManager getGhostPeerManager() { return _ghostPeerManager; }

    /** @return true if the manager has been shut down */
    boolean isShutdown() { return _isShutdown; }

    /** @return size of the inbound build queue */
    public int getInboundBuildQueueSize() { return _handler.getInboundBuildQueueSize(); }

    /** @deprecated moved to routerconsole */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {
    }

    /** @return total number of non-fallback expl. + client tunnels */
    private int countTunnelsPerPeer(ObjectCounterUnsafe<Hash> lc) {
        List<TunnelPool> pools = new ArrayList<>();
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
     * @param lc object counter to store peer counts
     * @param exploratory if true, count exploratory tunnels; if false, count client tunnels
     * @return total number of tunnels counted
     * @since 0.9.69+
     */
    private int countTunnelsPerPeer(ObjectCounterUnsafe<Hash> lc, boolean exploratory) {
        List<TunnelPool> pools = new ArrayList<>();
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
     *  the % of total network tunnels to 10% or so, but that appears to be
     *  too low to set as a default here... much lower than 33% will push client
     *  tunnels out of the fast tier into high cap or beyond...)
     *
     *  Possible improvement - restrict based on count per IP, or IP block,
     *  to slightly increase costs of collusion
     *
     *  @return Set of peers that should not be allowed in another tunnel
     */
    public Set<Hash> selectPeersInTooManyTunnels() {
        Set<Hash> rv = new HashSet<>();
        long uptime = _context.router().getUptime();
        int max = uptime > 30*60*1000 ? DEFAULT_MAX_PCT_TUNNELS : STARTUP_MAX_PCT_TUNNELS;

        // Increase threshold under low tunnel build success
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        if (isFirewalled() || buildSuccess < 0.40) {max *= 2;}

        // Count exploratory and client tunnels separately
        ObjectCounterUnsafe<Hash> lcExp = new ObjectCounterUnsafe<>();
        int exploratoryCount = countTunnelsPerPeer(lcExp, true);

        ObjectCounterUnsafe<Hash> lcClient = new ObjectCounterUnsafe<>();
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

    /** @return true if the router is firewalled */
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
        return new HashMap<>(_clientInboundPools);
    }

    /** for TunnelRenderer in router console */
    public Map<Hash, TunnelPool> getOutboundClientPools() {
        return new HashMap<>(_clientOutboundPools);
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
     * Emergency pool recovery: Check all registered clients have valid tunnel pools.
     * Recreates missing pools to prevent service interruption.
     * Called periodically by BuildExecutor.
     * @return number of pools recovered
     * @since 0.9.69+
     */
    public int checkAndRecoverPools() {
        int recovered = 0;
        Set<Destination> clients = _context.clientManager().listClients();
        for (Destination dest : clients) {
            Hash h = dest.calculateHash();
            boolean hasInbound = _clientInboundPools.containsKey(h);
            boolean hasOutbound = _clientOutboundPools.containsKey(h);

            if (!hasInbound || !hasOutbound) {
                if (_log.shouldWarn()) {
                    _log.warn("Detected missing pool for registered client: " +
                              h.toBase32().substring(0,8) +
                              " (inbound=" + hasInbound + ", outbound=" + hasOutbound + ")");
                }

                // Cancel any pending cleanup for this destination
                DelayedPoolCleanup cleanup = _pendingCleanups.remove(h);
                if (cleanup != null) {
                    cleanup.cancel();
                    if (_log.shouldInfo()) {
                        _log.info("Cancelled delayed cleanup for re-registered client: " +
                                  h.toBase32().substring(0,8));
                    }
                }

                try {
                    ClientTunnelSettings settings = new ClientTunnelSettings(h);
                    buildTunnels(dest, settings);
                    recovered++;
                    if (_log.shouldWarn()) {
                        _log.warn("RECOVERY: Created new tunnel pools for client: " +
                                  h.toBase32().substring(0,8));
                    }
                } catch (Exception e) {
                    if (_log.shouldError()) {
                        _log.error("Failed to recover pools for client: " +
                                   h.toBase32().substring(0,8), e);
                    }
                }
            }
        }

        int inboundPools = _clientInboundPools.size();
        int outboundPools = _clientOutboundPools.size();
        int registeredClients = clients.size();

        if (inboundPools != registeredClients || outboundPools != registeredClients) {
            if (_log.shouldWarn()) {
                _log.warn("Pool health check: " + registeredClients + " registered clients, " +
                          inboundPools + " inbound pools, " + outboundPools + " outbound pools");
            }
        } else if (recovered > 0 && _log.shouldInfo()) {
            _log.info("Pool health check: All " + registeredClients + " clients have pools");
        }

        return recovered;
    }

    /**
     * Log pool health summary for monitoring.
     * @since 0.9.69+
     */
    public void logPoolHealth() {
        int inboundPools = _clientInboundPools.size();
        int outboundPools = _clientOutboundPools.size();
        int registeredClients = _context.clientManager().listClients().size();
        int pendingCleanups = _pendingCleanups.size();

        if (inboundPools != registeredClients || outboundPools != registeredClients) {
            _log.warn("Pool mismatch detected: " + registeredClients + " clients, " +
                      inboundPools + " inbound pools, " + outboundPools + " outbound pools, " +
                      pendingCleanups + " pending cleanups");
        } else {
            _log.info("Pool health: " + registeredClients + " clients, " +
                      inboundPools + " inbound pools, " + outboundPools + " outbound pools, " +
                      pendingCleanups + " pending cleanups");
        }
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
        List<TunnelInfo> toFail = new ArrayList<>();
        for (TunnelInfo tun : pool.listTunnels()) {
            int len = tun.getLength();
            if (len > 1 && tun.getPeer(1).equals(peer)) {
                toFail.add(tun);
            }
        }
        for (TunnelInfo tun : toFail) {
            if (_log.shouldWarn()) {
                _log.warn("Removing " + tun + " -> First hop [" + peer.toBase64().substring(0,6) + "] is banlisted");
            }
            pool.tunnelFailed(tun, peer);
        }
    }

    /**
     *  Fail all (inbound) tunnels with this peer as last hop (not counting us)
     *
     *  @since 0.8.13
     */
    private void failTunnelsWithLastHop(TunnelPool pool, Hash peer) {
        List<TunnelInfo> toFail = new ArrayList<>();
        for (TunnelInfo tun : pool.listTunnels()) {
            int len = tun.getLength();
            if (len > 1 && tun.getPeer(len - 2).equals(peer)) {
                toFail.add(tun);
            }
        }
        for (TunnelInfo tun : toFail) {
            if (_log.shouldWarn()) {
                _log.warn("Removing " + tun + " -> Previous hop [" + peer.toBase64().substring(0,6) + "] is banlisted");
            }
            pool.tunnelFailed(tun, peer);
        }
    }

}
