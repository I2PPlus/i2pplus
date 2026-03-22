package net.i2p.router.tunnel.pool;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Tracks routers that consistently fail to respond to tunnel build requests
 * and temporarily excludes them from tunnel selection during network stress.
 *
 * This helps mitigate unresponsive peer issues where routers accept tunnel
 * build requests but never respond, causing resource exhaustion.
 *
 * @since 0.9.68+
 */
public class GhostPeerManager {
    private final Log _log;
    private final RouterContext _context;
    private final ConcurrentHashMap<Hash, AtomicInteger> _timeoutCounts;
    private final ConcurrentHashMap<Hash, Long> _ghostSince;
    private final ConcurrentHashMap<Hash, AtomicInteger> _ghostCounts;
    private final SimpleTimer2 _timer;

    private static final int THRESHOLD_HEALTHY = 8;
    private static final int THRESHOLD_MODERATE = 6;
    private static final int THRESHOLD_STRESSED = 4;
    private static final int THRESHOLD_ATTACK = 3;
    private static final long COOLDOWN_FIRST_MS = 60*1000;   // 1m
    private static final long COOLDOWN_REPEAT_MS = 120*1000;  // 2m
    private static final long COOLDOWN_PERSIST_MS = 240*1000; // 4m
    private static final long GHOST_COUNT_DECAY_MS = 10*60*1000; // 10m decay
    private static final int MAX_TRACKED_PEERS = 1024;

    public GhostPeerManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(GhostPeerManager.class);
        _timeoutCounts = new ConcurrentHashMap<Hash, AtomicInteger>(MAX_TRACKED_PEERS);
        _ghostSince = new ConcurrentHashMap<Hash, Long>(MAX_TRACKED_PEERS);
        _ghostCounts = new ConcurrentHashMap<Hash, AtomicInteger>(MAX_TRACKED_PEERS);
        _timer = SimpleTimer2.getInstance();
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
        int threshold = getThreshold();
        if (newCount >= threshold) {
            Long existingTime = _ghostSince.putIfAbsent(peer, _context.clock().now());
            if (existingTime == null) {
                AtomicInteger gc = _ghostCounts.putIfAbsent(peer, new AtomicInteger(1));
                int ghostCount = gc != null ? gc.incrementAndGet() : 1;
                long cooldown = getCoolDown(ghostCount);
                if (_log.shouldWarn()) {
                    _log.warn("Peer [" + peer.toBase64().substring(0,6) + "] marked as ghost for " +
                              (cooldown/1000) + "s -> " + newCount + " consecutive tunnel build timeouts" +
                              " (threshold: " + threshold + ", repeat: " + ghostCount + ")");
                }
                scheduleDecay(peer);
                // Promote a replacement peer to fast pool to maintain connectivity
                _context.profileOrganizer().promoteReplacementPeer(peer);
            }
        }
    }

    /**
     * Schedule ghost count decay for a peer.
     */
    private void scheduleDecay(final Hash peer) {
        new SimpleTimer2.TimedEvent(_timer, GHOST_COUNT_DECAY_MS) {
            @Override
            public void timeReached() {
                _ghostCounts.computeIfPresent(peer, (k, gc) -> {
                    int val = gc.decrementAndGet();
                    return val > 0 ? gc : null;
                });
            }
        };
    }

    /**
     * Get cooldown duration based on how many times the peer has been ghosted.
     * Exponential-ish: 60s -> 120s -> 240s (cap).
     */
    private long getCoolDown(int ghostCount) {
        if (ghostCount <= 1) return COOLDOWN_FIRST_MS;
        if (ghostCount == 2) return COOLDOWN_REPEAT_MS;
        return COOLDOWN_PERSIST_MS;
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

        Long since = _ghostSince.get(peer);
        if (since != null) {
            long elapsed = _context.clock().now() - since;
            AtomicInteger gc = _ghostCounts.get(peer);
            int ghostCount = gc != null ? gc.get() : 1;
            long cooldown = getCoolDown(ghostCount);
            if (elapsed >= cooldown) {
                _timeoutCounts.remove(peer);
                _ghostSince.remove(peer);
                return false;
            }
            return true;
        }

        AtomicInteger count = _timeoutCounts.get(peer);
        if (count == null) {return false;}
        return count.get() >= getThreshold();
    }

    /**
     * Get the current timeout threshold, adapting to network conditions.
     *
     * Healthy (>=0.60): 8 timeouts before ghosting
     * Moderate (>=0.50): 6
     * Stressed (>=0.40 / ATTACK_THRESHOLD): 4
     * Severe stress (<0.40): 3
     *
     * @return threshold number of timeouts before exclusion
     */
    public int getThreshold() {
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        if (buildSuccess >= 0.60) return THRESHOLD_HEALTHY;
        if (buildSuccess >= 0.50) return THRESHOLD_MODERATE;
        if (buildSuccess >= ProfileOrganizer.ATTACK_THRESHOLD) return THRESHOLD_STRESSED;
        return THRESHOLD_ATTACK;
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
