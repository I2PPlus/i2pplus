package net.i2p.router.tunnel.pool;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;

/**
 * Monitors transit tunnels for idle/abusive behavior and detects Sybil attacks.
 *
 * This class periodically scans participating tunnels and:
 * 1. Drops tunnels with no/low traffic after detection period (both messages AND bytes)
 * 2. Detects multiple router identities on same IP with low traffic (Sybils)
 * 3. Bans peers for repeated idle tunnel offenses or Sybil behavior
 *
 * Configurable properties:
 * - router.idleTunnelDetectionPeriod: Time before checking for idle (default: 30000ms)
 * - router.idleTunnelMinMessages: Minimum messages to not be considered idle (default: 2)
 * - router.idleTunnelMinBytes: Minimum bytes to not be considered idle (default: 2048)
 * - router.idleTunnelScanInterval: How often to scan (default: 30000ms)
 * - router.sybilMinIdentities: Minimum identities on same IP to trigger Sybil check (default: 2)
 * - router.sybilMinIdleTunnels: Minimum idle tunnels for Sybil ban (default: 10)
 *
 * @since 0.9.68+
 */
class IdleTunnelMonitor implements SimpleTimer.TimedEvent {
    private final RouterContext _context;
    private final Log _log;
    private TunnelDispatcher _dispatcher;

    /**
     * Get the dispatcher, fetching from context if not yet initialized.
     * This handles the case where TunnelDispatcher is created after IdleTunnelMonitor starts.
     */
    private TunnelDispatcher getDispatcher() {
        if (_dispatcher == null) {
            _dispatcher = _context.tunnelDispatcher();
        }
        return _dispatcher;
    }

    // Configuration
    private static final boolean isSlow = SystemVersion.isSlow();
    private static final long DEFAULT_DETECTION_PERIOD = 120 * 1000; // 120 seconds (under attack: <40% success - more conservative)
    private static final long HIGH_SUCCESS_DETECTION_PERIOD = 90 * 1000; // 90 seconds (normal: >=40% success)
    private static final int DEFAULT_MIN_MESSAGES = 2; // At least 2 messages
    private static final long DEFAULT_MIN_BYTES = 2 * 1024; // At least 2KB of data
    private static final long DEFAULT_SCAN_INTERVAL = 30 * 1000; // 30 seconds
    private static final int DEFAULT_SYBIL_MIN_IDENTITIES = 3; // Min 3 identities on same IP for Sybil check
    private static final int DEFAULT_SYBIL_MIN_IDLE_TUNNELS = 5; // Min 5 idle tunnels for Sybil ban
    private static final long DEFAULT_BAN_2ND_OFFENSE = 2 * 60 * 60 * 1000; // 2 hours
    private static final long DEFAULT_BAN_3RD_OFFENSE = 8 * 60 * 60 * 1000; // 8 hours
    private static final long DEFAULT_OFFENSE_RESET_TIME = 60 * 60 * 1000; // 1 hour
    private static final double BUILD_SUCCESS_THRESHOLD = 0.40; // 40% success rate

    // Only these properties are configurable
    private static final String PROP_DETECTION_PERIOD = "router.idleTunnelDetectionPeriod";
    private static final String PROP_MIN_MESSAGES = "router.idleTunnelMinMessages";
    private static final String PROP_MIN_BYTES = "router.idleTunnelMinBytes";
    private static final String PROP_SCAN_INTERVAL = "router.idleTunnelScanInterval";
    private static final String PROP_SYBIL_MIN_IDENTITIES = "router.sybilMinIdentities";
    private static final String PROP_SYBIL_MIN_IDLE_TUNNELS = "router.sybilMinIdleTunnels";
    private static final String PROP_ENABLE_BANS = "router.idleTunnelEnableBans"; // Default false to avoid amplifying attacks

    // Offense tracking per peer
    private final Map<Hash, OffenseRecord> _offenseHistory = new ConcurrentHashMap<>();

    // IP tracking for Sybil detection
    private final Map<String, Set<Hash>> _ipToPeers = new ConcurrentHashMap<>();
    private final Map<Hash, String> _peerToIP = new ConcurrentHashMap<>();

    // Control whether bans are enabled - off by default to avoid amplifying attacks
    private final boolean _enableBans;

    private volatile long _lastScanTime = 0;

    /**
     * Tracks offense history for a peer
     */
    private static class OffenseRecord {
        final AtomicInteger consecutiveOffenses = new AtomicInteger(0);
        volatile long lastOffenseTime = 0;
        volatile long lastCleanTime = System.currentTimeMillis();

        void recordOffense() {
            consecutiveOffenses.incrementAndGet();
            lastOffenseTime = System.currentTimeMillis();
        }

        void recordClean() {
            lastCleanTime = System.currentTimeMillis();
        }

        boolean shouldReset(long resetTime) {
            return System.currentTimeMillis() - lastOffenseTime > resetTime;
        }
    }

    IdleTunnelMonitor(RouterContext ctx) {
        this._context = ctx;
        this._dispatcher = ctx.tunnelDispatcher();
        this._log = ctx.logManager().getLog(IdleTunnelMonitor.class);
        this._enableBans = ctx.getBooleanProperty(PROP_ENABLE_BANS); // Default false to avoid amplifying attacks

        long interval = ctx.getProperty(PROP_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL);
        ctx.simpleTimer2().addPeriodicEvent(this, interval);

        if (_log.shouldInfo()) {
            _log.info("IdleTunnelMonitor started with interval: " + interval + "ms, bans enabled: " + _enableBans);
        }
    }

    @Override
    public void timeReached() {
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
        _lastScanTime = now;

        // Dynamic detection period based on build success @since 0.9.68+
        long basePeriod = _context.getProperty(PROP_DETECTION_PERIOD, DEFAULT_DETECTION_PERIOD);
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        long detectionPeriod;
        if (buildSuccess >= BUILD_SUCCESS_THRESHOLD) {
            // Normal operation - use longer detection period to reduce false positives
            detectionPeriod = HIGH_SUCCESS_DETECTION_PERIOD;
        } else {
            // Under stress (<40% success) - use aggressive shorter period
            detectionPeriod = basePeriod;
        }

        int minMessages = _context.getProperty(PROP_MIN_MESSAGES, DEFAULT_MIN_MESSAGES);
        long minBytes = _context.getProperty(PROP_MIN_BYTES, DEFAULT_MIN_BYTES);
        int sybilMinIdentities = _context.getProperty(PROP_SYBIL_MIN_IDENTITIES, DEFAULT_SYBIL_MIN_IDENTITIES);
        int sybilMinIdleTunnels = _context.getProperty(PROP_SYBIL_MIN_IDLE_TUNNELS, DEFAULT_SYBIL_MIN_IDLE_TUNNELS);
        // Use hardcoded defaults for ban durations (not configurable)
        long ban2nd = DEFAULT_BAN_2ND_OFFENSE;
        long ban3rd = DEFAULT_BAN_3RD_OFFENSE;
        long resetTime = DEFAULT_OFFENSE_RESET_TIME;

        // Collect all participating tunnels
        List<HopConfig> allTunnels = dispatcher.listParticipatingTunnels();

        // First pass: identify idle tunnels and organize by peer
        Map<Hash, List<HopConfig>> idleTunnelsByPeer = new HashMap<>();
        int totalDropped = 0;

        for (HopConfig tunnel : allTunnels) {
            Hash peer = getPeerHash(tunnel);
            if (peer == null) continue;

            long age = now - tunnel.getCreation();
            long messages = tunnel.getProcessedMessagesCount();
            long bytes = tunnel.getProcessedBytesCount();

            // Check if idle - both message count AND bytes must be below threshold
            // This catches attackers with high tunnel count but zero/low data transmission
            if (age >= detectionPeriod && messages < minMessages && bytes < minBytes) {
                idleTunnelsByPeer.computeIfAbsent(peer, k -> new ArrayList<>()).add(tunnel);

                if (_log.shouldDebug()) {
                    _log.debug("Detected idle tunnel from peer [" + peer.toBase64().substring(0, 6) +
                              "] -> Age: " + (age/1000) + "s [" + messages + (messages > 1 ? " messages" : " message") +
                              " / " + bytes + "B]");
                }
            } else {
                // Record as clean
                recordClean(peer);
            }
        }

        // Second pass: drop tunnels and record offenses (counting each tunnel as a separate offense)
        for (Map.Entry<Hash, List<HopConfig>> entry : idleTunnelsByPeer.entrySet()) {
            Hash peer = entry.getKey();
            List<HopConfig> tunnels = entry.getValue();
            int tunnelCount = tunnels.size();

            // Drop all idle tunnels for this peer
            for (HopConfig tunnel : tunnels) {
                dropTunnel(tunnel);
                totalDropped++;
            }

            // Record offense - each tunnel counts as a separate offense
            // This means a peer with 10 idle tunnels gets 10 offenses immediately
            recordIdleOffense(peer, tunnelCount, ban2nd, ban3rd, resetTime);
        }

        // Sybil detection - pass detection thresholds
        detectAndBanSybils(idleTunnelsByPeer, sybilMinIdentities, sybilMinIdleTunnels, ban3rd,
                           detectionPeriod, minMessages, minBytes);

        if (totalDropped > 100 && _log.shouldWarn()) {
            _log.warn("Dropped " + totalDropped + " idle tunnels across " + idleTunnelsByPeer.size() + " peers");
        }

        // Cleanup old offense records
        cleanupOffenseHistory(resetTime);
    }

    /**
     * Get the peer hash for a tunnel - this is the "entry point" peer
     * who first introduced this tunnel to us (IBGW or closest hop to IBGW).
     * We blame this peer for idle tunnels since they're responsible for the tunnel.
     */
    private Hash getPeerHash(HopConfig tunnel) {
        // Use ReceiveFrom as the entry point - this is the peer who sent us the tunnel config
        // This is either the IBGW (if we're the first hop) or the hop before us
        Hash from = tunnel.getReceiveFrom();
        if (from != null) return from;
        return tunnel.getSendTo();
    }

    /**
     * Drop an idle tunnel by removing it from the dispatcher
     */
    private void dropTunnel(HopConfig tunnel) {
        TunnelDispatcher dispatcher = getDispatcher();
        if (dispatcher == null) return;

        try {
            // Remove immediately using the dispatcher's remove method
            // This removes from _participatingConfig and other collections
            dispatcher.remove(tunnel);

            // Also mark bandwidth as freed
            int allocated = tunnel.getAllocatedBW();
            if (allocated > 0) {
                // Bandwidth will be freed via normal accounting
            }

            if (_log.shouldDebug()) {
                _log.debug("Dropped idle tunnel [" + tunnel.getReceiveTunnelId() +
                          "] -> Messages / Bytes: " + tunnel.getProcessedMessagesCount() +
                          " / " + tunnel.getProcessedBytesCount());
            }

            // Update stats
            _context.statManager().addRateData("tunnel.idleTunnelDropped", 1);
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Failed to idle drop tunnel [" + tunnel + "] -> " + e.getMessage());
            }
        }
    }

    /**
     * Record an idle offense for a peer
     * @param tunnelCount Number of idle tunnels dropped - each counts as a separate offense
     */
    private void recordIdleOffense(Hash peer, int tunnelCount, long ban2nd, long ban3rd, long resetTime) {
        OffenseRecord record = _offenseHistory.computeIfAbsent(peer, k -> new OffenseRecord());

        // Reset if enough time has passed - start fresh at 1 offense
        if (record.shouldReset(resetTime)) {
            record.consecutiveOffenses.set(1);
        } else {
            record.consecutiveOffenses.addAndGet(tunnelCount);
        }

        int offenses = record.consecutiveOffenses.get();

        if (offenses >= 3) {
            banPeer(peer, "Excessive Idle Tunnels (" + offenses + ")", ban3rd);
        } else if (offenses == 2) {
            banPeer(peer, "Excessive Idle Tunnels (" + offenses + ")", ban2nd);
        } else if (_log.shouldInfo()) {
            _log.info("First idle tunnels offense for peer [" + peer.toBase64().substring(0, 6) +
                      "] -> " + tunnelCount + " idle "+ (tunnelCount > 1 ? "tunnels" : "tunnel") + " dropped [" + System.currentTimeMillis() + "]");
        }
    }

    /**
     * Record a clean period for a peer
     */
    private void recordClean(Hash peer) {
        OffenseRecord record = _offenseHistory.get(peer);
        if (record != null) {
            record.recordClean();
        }
    }

    /**
     * Detect Sybil attacks and ban all identities on same IP
     */
    private void detectAndBanSybils(Map<Hash, List<HopConfig>> idleTunnelsByPeer,
                                    int minIdentities, int minIdleTunnels, long banTime,
                                    long detectionPeriod, int minMessages, long minBytes) {
        // Build IP -> peers mapping
        Map<String, Set<Hash>> ipToIdlePeers = new HashMap<>();

        for (Map.Entry<Hash, List<HopConfig>> entry : idleTunnelsByPeer.entrySet()) {
            Hash peer = entry.getKey();
            List<HopConfig> tunnels = entry.getValue();

            // Get IP addresses for this peer
            Set<String> ips = getPeerIPs(peer);

            for (String ip : ips) {
                if (ip == null || ip.isEmpty()) continue;

                ipToIdlePeers.computeIfAbsent(ip, k -> new HashSet<>()).add(peer);
                _ipToPeers.computeIfAbsent(ip, k -> new HashSet<>()).add(peer);
                _peerToIP.put(peer, ip);
            }
        }

        // Check for Sybils - only ban if ALL identities on IP have only idle tunnels
        for (Map.Entry<String, Set<Hash>> entry : ipToIdlePeers.entrySet()) {
            String ip = entry.getKey();
            Set<Hash> idlePeers = entry.getValue();

            if (idlePeers.size() >= minIdentities) {
                // Count total idle tunnels from these peers
                int totalIdleTunnels = 0;
                boolean allIdle = true;

                for (Hash peer : idlePeers) {
                    List<HopConfig> tunnels = idleTunnelsByPeer.get(peer);
                    if (tunnels != null) {
                        totalIdleTunnels += tunnels.size();
                    }
                    // Check if this peer has ANY active tunnels
                    if (hasActiveTunnels(peer, detectionPeriod, minMessages, minBytes)) {
                        allIdle = false;
                    }
                }

                // Only ban if ALL identities have only idle tunnels
                if (allIdle && totalIdleTunnels >= minIdleTunnels) {
                    for (Hash peer : idlePeers) {
                        if (!_context.banlist().isBanlisted(peer)) {
                            banPeer(peer, "Excessive Idle Tunnels (" + totalIdleTunnels + ")", banTime);
                        }
                    }

                    if (_log.shouldWarn()) {
                        _log.warn("Banning IP cluster for IP [" + ip + "] -> All " +
                                   idlePeers.size() + " ids have only idle tunnels (" +
                                   totalIdleTunnels + " total) [" + System.currentTimeMillis() + "]");
                    }
                }
            }
        }
    }

    /**
     * Get IP addresses for a peer from RouterInfo
     */
    private Set<String> getPeerIPs(Hash peer) {
        Set<String> ips = new HashSet<>();

        try {
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
            if (ri == null) return ips;

            for (RouterAddress addr : ri.getAddresses()) {
                String host = addr.getHost();
                if (host != null && !host.isEmpty()) {
                    // Validate it's an IP, not a hostname
                    if (isValidIP(host)) {
                        ips.add(host);
                    }
                }
            }
        } catch (Exception e) {
            if (_log.shouldDebug()) {
                _log.debug("Failed to get IPs for peer " + peer.toBase64().substring(0, 6), e);
            }
        }

        return ips;
    }

    /**
     * Check if a peer has any active (non-idle) tunnels
     */
    private boolean hasActiveTunnels(Hash peer, long detectionPeriod, int minMessages, long minBytes) {
        TunnelDispatcher dispatcher = getDispatcher();
        if (dispatcher == null) return false;

        List<HopConfig> tunnels = dispatcher.listParticipatingTunnels();
        long now = System.currentTimeMillis();

        for (HopConfig tunnel : tunnels) {
            Hash tunnelPeer = getPeerHash(tunnel);
            if (tunnelPeer != null && tunnelPeer.equals(peer)) {
                long age = now - tunnel.getCreation();
                long messages = tunnel.getProcessedMessagesCount();
                long bytes = tunnel.getProcessedBytesCount();

                // If any tunnel has enough activity, peer is not fully idle
                if (age >= detectionPeriod && (messages >= minMessages || bytes >= minBytes)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if string is a valid IP address
     */
    private boolean isValidIP(String ip) {
        try {
            InetAddress.getByName(ip);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ban a peer - only if bans are enabled (off by default to avoid amplifying attacks)
     */
    private void banPeer(Hash peer, String reason, long duration) {
        if (!_enableBans) {
            return; // Bans disabled by default to avoid amplifying attacks
        }
        if (_context.banlist().isBanlisted(peer)) {
            return; // Already banned
        }

        long now = _context.clock().now();
        _context.banlist().banlistRouter(peer, " <b>➜</b> " + reason, null, null, now + duration);

        // Force disconnect
        _context.commSystem().forceDisconnect(peer);

        if (_log.shouldWarn()) {
            _log.warn("Banned peer [" + peer.toBase64().substring(0, 6) + "] for " + (duration / 60000) +
                      "m -> " + reason);
        }

        // Update stats
        _context.statManager().addRateData("tunnel.banIdlePeer", 1);
    }

    /**
     * Cleanup old offense history entries and IP tracking maps
     */
    private void cleanupOffenseHistory(long resetTime) {
        long now = System.currentTimeMillis();
        long offenseCutoff = now - resetTime * 3;
        long cleanCutoff = now - resetTime * 6; // Remove clean entries faster

        _offenseHistory.entrySet().removeIf(entry -> {
            OffenseRecord record = entry.getValue();
            // Remove if old offense OR been clean for extended period
            return record.lastOffenseTime < offenseCutoff || record.lastCleanTime < cleanCutoff;
        });

        // Also cleanup IP tracking - remove IPs with no recent offenses
        _ipToPeers.entrySet().removeIf(entry -> {
            Set<Hash> peers = entry.getValue();
            // Remove if all peers are no longer in offense history
            for (Hash peer : peers) {
                if (_offenseHistory.containsKey(peer)) {
                    return false; // Keep this IP
                }
            }
            return true;
        });

        // Cleanup peer to IP mapping
        _peerToIP.entrySet().removeIf(entry -> {
            Hash peer = entry.getKey();
            return !_offenseHistory.containsKey(peer);
        });
    }
}
