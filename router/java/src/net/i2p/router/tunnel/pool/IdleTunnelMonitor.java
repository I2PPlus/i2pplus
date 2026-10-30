package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.stat.RateConstants;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

/**
 * Monitors transit tunnels for idle behavior.
 * <p>
 * This class periodically scans participating tunnels and drops those that
 * have carried no traffic at all since joining — but only as a pressure
 * valve: culling is skipped unless the transit population is near the
 * configured capacity, because every dropped tunnel looks like a failed
 * tunnel to its originator. With defaults, quiet-but-alive tunnels live out
 * their full lifetime. Expiration handles normal lifecycle.
 * <p>
 * Configurable properties:
 * - {@code router.idleTunnelDetectionPeriod}: Time before checking for idle (default: 300000ms)
 * - {@code router.idleTunnelMinMessages}: Minimum messages to not be considered idle (default: 1)
 * - {@code router.idleTunnelScanInterval}: How often to scan (default: 60000ms).
 */
class IdleTunnelMonitor extends SimpleTimer2.TimedEvent {
    /** The router context. */
    private final RouterContext _context;
    /** The log for the idle monitor. */
    private final Log _log;
    /** The dispatcher whose tunnels are checked. */
    private TunnelDispatcher _dispatcher;

    /**
     * The dispatcher, fetching from context if not yet initialized.
     * @return the transit tunnel dispatcher, cached here after the first lookup
     */
    private TunnelDispatcher getDispatcher() {
        if (_dispatcher == null) {
            _dispatcher = _context.tunnelDispatcher();
        }
        return _dispatcher;
    }

    /**
     * Default value of the router.idleTunnelDetectionPeriod property: five
     * minutes, half of a tunnel's typical lifetime, so genuinely dead tunnels
     * are still reaped well before expiry.
     */
    private static final long DEFAULT_DETECTION_PERIOD = 300 * 1000L;
    /** Floor of 1 drops only zero-message tunnels; higher values re-enable low-traffic culling. */
    private static final int DEFAULT_MIN_MESSAGES = 1;
    /** Default interval between the idle scans. */
    private static final long DEFAULT_SCAN_INTERVAL = 60 * 1000L; // 60 seconds

    /** Config key of the idle detection period, one of the only configurable settings. */
    private static final String PROP_DETECTION_PERIOD = "router.idleTunnelDetectionPeriod";
    /** Config key of the minimum messages for activity. */
    private static final String PROP_MIN_MESSAGES = "router.idleTunnelMinMessages";
    /** Config key of the idle scan interval. */
    private static final String PROP_SCAN_INTERVAL = "router.idleTunnelScanInterval";

    /**
     * Transit usage fraction at or above which idle culling runs; below it,
     * capacity is not under pressure and expiration alone handles lifecycle.
     */
    protected static final double CULL_CAPACITY_FRACTION = 0.80;
    /** Config key of the most participating tunnels. */
    private static final String PROP_MAX_PARTICIPATING = "router.maxParticipatingTunnels";

    /** True once the monitor shut down. */
    private volatile boolean _isShutdown = false;

    /**
     * Starts the scan timer and registers the dropped-tunnel stat.
     *
     * @param ctx the context supplying the timers, dispatcher, log and stat manager
     */
    IdleTunnelMonitor(RouterContext ctx) {
        super(ctx.simpleTimer2());
        this._context = ctx;
        this._dispatcher = ctx.tunnelDispatcher();
        this._log = ctx.logManager().getLog(IdleTunnelMonitor.class);
        ctx.statManager().createRequiredRateStat("tunnel.idleTunnelDropped",
                                                 "Idle transit tunnels dropped", "Tunnels [Participating]",
                                                 new long[] {RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR});

        long interval = ctx.getProperty(PROP_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL);
        schedule(interval);

        if (_log.shouldInfo()) {
            _log.info("IdleTunnelMonitor started with interval: " + interval + "ms");
        }
    }

    /**
     * Shut down the monitor and clean up resources.
     */
    public void shutdown() {
        _isShutdown = true;
    }

    @Override
    public void timeReached() {
        if (_isShutdown) {
            return;
        }
        try {
            scanAndCleanup();
        } catch (Exception t) {
            if (_log.shouldError()) {
                _log.error("Error in IdleTunnelMonitor scan", t);
            }
        }
        long interval = _context.getProperty(PROP_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL);
        schedule(interval);
    }

    /**
     * Main scan method - checks all participating tunnels.
     */
    private void scanAndCleanup() {
        TunnelDispatcher dispatcher = getDispatcher();
        if (dispatcher == null) {
            if (_log.shouldDebug()) {
                _log.debug("TunnelDispatcher not yet initialized -> Skipping idle tunnel scan...");
            }
            return;
        }

        long now = System.currentTimeMillis();
        long detectionPeriod = _context.getProperty(PROP_DETECTION_PERIOD, DEFAULT_DETECTION_PERIOD);
        int minMessages = _context.getProperty(PROP_MIN_MESSAGES, DEFAULT_MIN_MESSAGES);

        // Disable if detectionPeriod <= 0
        if (detectionPeriod <= 0) {
            return;
        }

        // Pressure valve: skip culling unless the transit population is near
        // the configured capacity. Below that, dropped tunnels cost reputation
        // with originators while freeing nothing we need.
        int participating = _context.tunnelManager().getParticipatingCount();
        int maxParticipating = _context.getProperty(
                PROP_MAX_PARTICIPATING, SystemVersion.isSlow() ? 4000 : 10000);
        if (!nearCapacity(participating, maxParticipating)) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping idle tunnel scan -> Transit usage " + participating +
                           " / " + maxParticipating + " below cull threshold");
            }
            return;
        }

        // Collect all participating tunnels
        List<HopConfig> allTunnels = dispatcher.listParticipatingTunnels();

        // Identify idle tunnels
        Map<Hash, List<HopConfig>> idleTunnelsByPeer = new HashMap<>();
        int totalDropped = 0;

        for (HopConfig tunnel : allTunnels) {
            Hash peer = getPeerHash(tunnel);
            if (peer == null) continue;

            long age = now - tunnel.getCreation();
            long messages = tunnel.getProcessedMessagesCount();

            // Check if idle - message count must be below threshold
            if (age >= detectionPeriod && messages < minMessages) {
                idleTunnelsByPeer.computeIfAbsent(peer, k -> new ArrayList<>()).add(tunnel);

                if (_log.shouldDebug()) {
                    _log.debug("Detected idle tunnel from peer [" + peer.toBase64().substring(0, 6) +
                              "] -> Age: " + DataHelper.formatDuration(age) + " [" + messages +
                              (messages > 1 ? " messages" : " message") + "]");
                }
            }
        }

        // Drop idle tunnels
        for (Map.Entry<Hash, List<HopConfig>> entry : idleTunnelsByPeer.entrySet()) {
            Hash peer = entry.getKey();
            List<HopConfig> tunnels = entry.getValue();
            int tunnelCount = tunnels.size();

            // Drop all idle tunnels for this peer
            for (HopConfig tunnel : tunnels) {
                dropTunnel(tunnel);
                totalDropped++;
            }

            if (_log.shouldInfo()) {
                _log.info("Dropped " + tunnelCount + " idle tunnels from peer [" + peer.toBase64().substring(0, 6) + "]");
            }
        }

        if (totalDropped > 100 && _log.shouldWarn()) {
            _log.warn("Dropped " + totalDropped + " idle tunnels across " + idleTunnelsByPeer.size() + " peers");
        }
    }

    /**
     * The peer hash for a tunnel - this is the "entry point" peer
     * who first introduced this tunnel to us (IBGW or closest hop to IBGW).
     *
     * @param tunnel the tunnel to find the entry point peer of
     * @return the peer hash
     */
    private Hash getPeerHash(HopConfig tunnel) {
        Hash from = tunnel.getReceiveFrom();
        if (from != null) return from;
        return tunnel.getSendTo();
    }

    /**
     * Whether the transit population is close enough to configured capacity
     * that idle culling is worthwhile. Pure decision helper, package visible
     * for tests.
     *
     * @param participating current transit tunnel count
     * @param maxParticipating the configured participating-tunnel capacity
     * @return true if usage is at or above {@link #CULL_CAPACITY_FRACTION};
     *         false when capacity is unknown or usage is below it
     * @since 0.9.71+
     */
    static boolean nearCapacity(int participating, int maxParticipating) {
        if (maxParticipating <= 0) {return false;}
        return (long) participating * 100 >= (long) maxParticipating * CULL_CAPACITY_FRACTION * 100;
    }

    /**
     * Drop an idle tunnel by removing it from the dispatcher.
     * <p>
     * Package-visible so a test can drive a single drop against a dispatcher it
     * controls and check the bandwidth accounting exactly.
     *
     * @param tunnel the idle tunnel to drop
     */
    void dropTunnel(HopConfig tunnel) {
        TunnelDispatcher dispatcher = getDispatcher();
        if (dispatcher == null) return;

        try {
            // Remove immediately using the dispatcher's remove method.
            // remove() owns the bandwidth accounting and the expiration-queue
            // removal itself; doing either again here double-decremented
            // _allocatedBW, which drifted the transit total permanently
            // negative by one tunnel's allocation per idle drop.
            dispatcher.remove(tunnel);

            if (_log.shouldDebug()) {
                _log.debug("Dropped idle tunnel [" + tunnel.getReceiveTunnelId() +
                          "] -> Messages: " + tunnel.getProcessedMessagesCount());
            }

            // Update stats
            _context.statManager().addRateData("tunnel.idleTunnelDropped", 1);
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Failed to idle drop tunnel [" + tunnel + "] -> " + e.getMessage());
            }
        }
    }
}
