package net.i2p.router.tunnel.pool;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.util.Log;

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

    private static final int DEFAULT_TIMEOUT_THRESHOLD = 3;
    private static final int ATTACK_TIMEOUT_THRESHOLD = 3;
    private static final long COOLDOWN_MS = 180*1000; // 3m
    private static final long ATTACK_COOLDOWN_MS = 300*1000; // 5m
    private static final int MAX_TRACKED_PEERS = 1024;

    public GhostPeerManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(GhostPeerManager.class);
        _timeoutCounts = new ConcurrentHashMap<Hash, AtomicInteger>(MAX_TRACKED_PEERS);
        _ghostSince = new ConcurrentHashMap<Hash, Long>(MAX_TRACKED_PEERS);
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

        int newCount = count != null ? count.get() : 1;
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        boolean underAttack = buildSuccess < ProfileOrganizer.ATTACK_THRESHOLD;
        if (newCount >= getThreshold()) {
            Long existingTime = _ghostSince.putIfAbsent(peer, _context.clock().now());
            if (existingTime == null && _log.shouldWarn()) {
                _log.warn("Peer [" + peer.toBase64().substring(0,6) + "] marked as ghost for " +
                          (underAttack ? ATTACK_COOLDOWN_MS/1000 : COOLDOWN_MS/1000) + "s -> " +
                           newCount + " consecutive tunnel build timeouts");
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

        _timeoutCounts.computeIfPresent(peer, (k, count) -> {
            count.set(0);
            return count;
        });
        _ghostSince.remove(peer);
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

        Long since = _ghostSince.get(peer);
        if (since != null) {
            long elapsed = _context.clock().now() - since;
            double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
            long cooldown = buildSuccess < ProfileOrganizer.ATTACK_THRESHOLD ? ATTACK_COOLDOWN_MS : COOLDOWN_MS;
            if (elapsed >= cooldown) {
                _timeoutCounts.remove(peer);
                _ghostSince.remove(peer);
                return false;
            }
        }

        int threshold = getThreshold();
        return count.get() >= threshold;
    }

    /**
     * Get the current timeout threshold.
     *
     * @return threshold number of timeouts before exclusion
     */
    public int getThreshold() {
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        if (buildSuccess < ProfileOrganizer.ATTACK_THRESHOLD) {
            return ATTACK_TIMEOUT_THRESHOLD;
        }
        return DEFAULT_TIMEOUT_THRESHOLD;
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
     * Get count of currently tracked ghost peers.
     *
     * @return number of ghost peers
     */
    public int getGhostCount() {
        return _ghostSince.size();
    }
}
