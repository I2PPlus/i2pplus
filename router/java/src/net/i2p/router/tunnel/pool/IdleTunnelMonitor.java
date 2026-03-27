package net.i2p.router.tunnel.pool;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitors transit tunnels for idle behavior.
 *
 * This class periodically scans participating tunnels and
 * drops tunnels with no/low traffic after detection period (messages only).
 *
 * Configurable properties:
 * - router.idleTunnelDetectionPeriod: Time before checking for idle (default: 120000ms)
 * - router.idleTunnelMinMessages: Minimum messages to not be considered idle (default: 3)
 * - router.idleTunnelScanInterval: How often to scan (default: 60000ms)
 *
 * @since 2.11.0
 */
class IdleTunnelMonitor implements SimpleTimer.TimedEvent {
    private final RouterContext _context;
    private final Log _log;
    private TunnelDispatcher _dispatcher;

    /**
     * Get the dispatcher, fetching from context if not yet initialized.
     */
    private TunnelDispatcher getDispatcher() {
        if (_dispatcher == null) {
            _dispatcher = _context.tunnelDispatcher();
        }
        return _dispatcher;
    }

    private static final long DEFAULT_DETECTION_PERIOD = 120 * 1000;
    private static final int DEFAULT_MIN_MESSAGES = 3;
    private static final long DEFAULT_SCAN_INTERVAL = 60 * 1000;

    private static final String PROP_DETECTION_PERIOD = "router.idleTunnelDetectionPeriod";
    private static final String PROP_MIN_MESSAGES = "router.idleTunnelMinMessages";
    private static final String PROP_SCAN_INTERVAL = "router.idleTunnelScanInterval";

    private volatile boolean _isShutdown = false;

    IdleTunnelMonitor(RouterContext ctx) {
        this._context = ctx;
        this._dispatcher = ctx.tunnelDispatcher();
        this._log = ctx.logManager().getLog(IdleTunnelMonitor.class);

        long interval = ctx.getProperty(PROP_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL);
        ctx.simpleTimer2().addPeriodicEvent(this, interval);

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
        } catch (Throwable t) {
            if (_log.shouldError()) {
                _log.error("Error in IdleTunnelMonitor scan", t);
            }
        }
    }

    /**
     * Main scan method - checks all participating tunnels
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
                    _log.debug("Detected idle tunnel from peer [" + peer.toBase64().substring(0, 6) + "] -> Age: " + (age / 1000) + "s [" + messages + (messages > 1 ? " messages" : " message") + "]");
                }
            }
        }

        // Drop idle tunnels
        for (Map.Entry<Hash, List<HopConfig>> entry : idleTunnelsByPeer.entrySet()) {
            Hash peer = entry.getKey();
            List<HopConfig> tunnels = entry.getValue();
            int tunnelCount = tunnels.size();

            for (HopConfig tunnel : tunnels) {
                dropTunnel(tunnel);
                totalDropped++;
            }

            if (_log.shouldInfo()) {
                _log.info("Dropped " + tunnelCount + " idle tunnels from peer [" + peer.toBase64().substring(0, 6) + "]");
            }
        }

        if (totalDropped >= 10 && _log.shouldWarn()) {
            _log.warn("Dropped " + totalDropped + " idle tunnels across " + idleTunnelsByPeer.size() + " peers");
        }
    }

    /**
     * Get the adjacent peer hash for a tunnel.
     */
    private Hash getPeerHash(HopConfig tunnel) {
        Hash from = tunnel.getReceiveFrom();
        if (from != null) return from;
        return tunnel.getSendTo();
    }

    /**
     * Drop an idle tunnel by removing it from the dispatcher.
     */
    private void dropTunnel(HopConfig tunnel) {
        TunnelDispatcher dispatcher = getDispatcher();
        if (dispatcher == null) return;

        try {
            // Record stats first so they persist even if cleanup fails
            _context.statManager().addRateData("tunnel.idleTunnelDropped", 1);

            // Remove the tunnel from dispatcher maps
            dispatcher.remove(tunnel);

            // Free allocated bandwidth (remove() does not handle this)
            int allocated = tunnel.getAllocatedBW();
            if (allocated > 0) {
                dispatcher.freeBandwidth(allocated);
            }

            if (_log.shouldDebug()) {
                _log.debug("Dropped idle tunnel [" + tunnel.getReceiveTunnelId() + "] -> Messages: " + tunnel.getProcessedMessagesCount());
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Failed to idle drop tunnel [" + tunnel + "] -> " + e.getMessage());
            }
        }
    }
}
