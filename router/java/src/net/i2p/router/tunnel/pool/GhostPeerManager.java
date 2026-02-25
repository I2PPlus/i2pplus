package net.i2p.router.tunnel.pool;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Tracks routers that consistently fail to respond to tunnel build requests
 * and temporarily excludes them from tunnel selection during network stress.
 *
 * This helps mitigate ghost peer attacks where malicious routers accept tunnel
 * build requests but never respond, causing resource exhaustion.
 *
 * @since 0.9.68+
 */
public class GhostPeerManager {
    private final Log _log;
    private final RouterContext _context;
    private final ConcurrentHashMap<Hash, AtomicInteger> _timeoutCounts;
    private final ConcurrentHashMap<Hash, Long> _ghostSince;

    private static final int DEFAULT_TIMEOUT_THRESHOLD = 4;
    private static final int ATTACK_TIMEOUT_THRESHOLD = 3;
    private static final long COOLDOWN_MS = 5 * 60 * 1000; // 5 minutes (normal)
    private static final long ATTACK_COOLDOWN_MS = 10 * 60 * 1000; // 10 minutes (during attacks/low success)
    private static final int MAX_TRACKED_PEERS = 8192;
    private static final long CLEANUP_INTERVAL_MS = 30 * 60 * 1000; // 30 minutes

    public GhostPeerManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(GhostPeerManager.class);
        _timeoutCounts = new ConcurrentHashMap<Hash, AtomicInteger>(MAX_TRACKED_PEERS);
        _ghostSince = new ConcurrentHashMap<Hash, Long>(MAX_TRACKED_PEERS);
        context.simpleTimer2().addPeriodicEvent(new CleanupTimer(), CLEANUP_INTERVAL_MS);
    }

    /**
     * Periodic cleanup timer to ensure maps don't grow unbounded.
     */
    private class CleanupTimer implements SimpleTimer.TimedEvent {
        @Override
        public void timeReached() {
            cleanup();
            _context.simpleTimer2().addPeriodicEvent(this, CLEANUP_INTERVAL_MS);
        }
    }

    /**
     * Record that a peer timed out during tunnel build.
     * Called from BuildExecutor when a tunnel build expires.
     *
     * @param peer the peer hash that timed out
     */
    public void recordTimeout(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return;}

        AtomicInteger count = _timeoutCounts.putIfAbsent(peer, new AtomicInteger(1));
        if (count != null) {
            count.incrementAndGet();
        }

        // Track when peer became a ghost
        int newCount = count != null ? count.get() : 1;
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        long uptime = _context.router().getUptime();
        boolean underStress = buildSuccess < 0.40;
        if (newCount >= getThreshold()) {
            Long existingTime = _ghostSince.putIfAbsent(peer, _context.clock().now());
            if (existingTime == null && _log.shouldWarn()) {
                _log.warn("Peer [" + peer.toBase64().substring(0,6) + "] marked as ghost for " +
                          (underStress ? ATTACK_COOLDOWN_MS/60000 : COOLDOWN_MS/60000) + " min -> " +
                           newCount + " consecutive tunnel build timeouts (" +
                          (uptime < 10*60*1000 ? "router startup period" : "build success: " + (int)(buildSuccess * 100) + "%") + ")");
            }
        }
    }

    /**
     * Record successful tunnel participation by a peer.
     * Clears ghost status when a peer successfully participates in a tunnel.
     *
     * @param peer the peer hash that participated successfully
     */
    public void recordSuccess(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return;}

        AtomicInteger count = _timeoutCounts.get(peer);
        if (count != null && count.get() > 0) {
            // Decrement timeout count instead of resetting to 0
            // This allows consistent failures to still accumulate toward ghost threshold
            int newCount = count.decrementAndGet();
            if (newCount <= 0) {
                count.set(0);
                _ghostSince.remove(peer);
                if (_log.shouldDebug()) {
                    _log.debug("Peer [" + peer.toBase64().substring(0,6) + "] cleared from ghost list after successful participation");
                }
            }
        }
    }

    /**
     * Check if a peer should be excluded from tunnel selection.
     *
     * @param peer the peer hash to check
     * @return true if the peer is a ghost and should be skipped
     */
    public boolean isGhost(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return false;}

        AtomicInteger count = _timeoutCounts.get(peer);
        if (count == null) {return false;}

        int threshold = getThreshold();
        return count.get() >= threshold;
    }

    /**
      * Check if a peer is in ghost cooldown period.
      * Peers in cooldown were previously ghosts but may be retried.
      *
      * @param peer the peer hash to check
      * @return true if peer is in cooldown period
      */
    public boolean isInCooldown(Hash peer) {
         if (peer == null) {return false;}

         Long since = _ghostSince.get(peer);
         if (since == null) {return false;}

         long elapsed = _context.clock().now() - since;
         double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
         long uptime = _context.router().getUptime();
         boolean underStress = buildSuccess < 0.40;
         long cooldown = underStress ? ATTACK_COOLDOWN_MS : COOLDOWN_MS;
         return elapsed < cooldown;
     }

    /**
     * Get the current timeout threshold.
     * Lower threshold during attacks (build success < 40%).
     *
     * @return threshold number of timeouts before exclusion
     */
    public int getThreshold() {
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        if (buildSuccess < 0.40) {
            return ATTACK_TIMEOUT_THRESHOLD;
        }
        return DEFAULT_TIMEOUT_THRESHOLD;
    }

    /**
     * Get consecutive timeout count for a peer.
     *
     * @param peer the peer hash
     * @return timeout count, or 0 if not tracked
     */
    public int getTimeoutCount(Hash peer) {
        if (peer == null) {return 0;}
        AtomicInteger count = _timeoutCounts.get(peer);
        return count != null ? count.get() : 0;
    }

    /**
     * Clear ghost status for a peer (manual intervention).
     *
     * @param peer the peer hash to clear
     */
    public void clearGhost(Hash peer) {
        if (peer == null) {return;}
        _timeoutCounts.remove(peer);
        _ghostSince.remove(peer);
    }

    /**
     * Cleanup old entries to prevent memory growth.
     * Called periodically or when threshold is reached.
     *
     */
    public void cleanup() {
        long now = _context.clock().now();
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        // Cleanup threshold extends beyond cooldown to allow peer to remain blocked longer.
        // Peer is excluded via isGhost() until entry is cleaned up from the map.
        long cleanupThreshold = buildSuccess < 0.40
                                ? ATTACK_COOLDOWN_MS * 2   // 20 min (10 min cooldown * 2)
                                : COOLDOWN_MS * 2;         // 10 min (5 min cooldown * 2)
        for (Hash peer : _ghostSince.keySet()) {
            Long since = _ghostSince.get(peer);
            if (since != null && (now - since) > cleanupThreshold) {
                // Auto-reintegrate after extended ghost period
                _timeoutCounts.remove(peer);
                _ghostSince.remove(peer);
            }
        }
    }

    /**
     * Get count of currently tracked ghost peers.
     *
     * @return number of ghost peers
     */
    public int getGhostCount() {
        return _ghostSince.size();
    }
}
