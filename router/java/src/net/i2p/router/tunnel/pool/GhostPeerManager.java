package net.i2p.router.tunnel.pool;

import java.util.Map;
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
    private final ConcurrentHashMap<Hash, Long> _ghostUntil;

    private static final int ATTACK_TIMEOUT_THRESHOLD = 3;

    private static int getTimeoutThreshold(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.ghostPeer.timeoutThreshold", 3);
    }

    private static long getCooldownMs(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.ghostPeer.cooldownMs", 180*1000);
    }

    private static long getAttackCooldownMs(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.ghostPeer.attackCooldownMs", 60*1000);
    }

    /**
     *  Cooldown for the current network state: under stress, rehabilitate
     *  peers faster — many get ghosted through no fault of their own when
     *  the whole network is slow.  Defaults: 60s under stress, 180s normal.
     */
    private static long getActiveCooldownMs(RouterContext ctx, double buildSuccess) {
        return isUnderAttack(buildSuccess) ? getAttackCooldownMs(ctx) : getCooldownMs(ctx);
    }

    private static boolean isUnderAttack(double buildSuccess) {
        return buildSuccess < ProfileOrganizer.ATTACK_THRESHOLD;
    }

    private static final int MAX_TRACKED_PEERS = 1024;

    /** Rate limit for the ghost-mark WARN; per-peer detail stays at debug. */
    private static final long GHOST_WARN_INTERVAL_MS = 60 * 1000L;

    private volatile long _lastGhostWarnTime;

    /**
     * GhostPeerManager.
     */
    public GhostPeerManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(GhostPeerManager.class);
        _timeoutCounts = new ConcurrentHashMap<>(MAX_TRACKED_PEERS);
        _ghostUntil = new ConcurrentHashMap<>(MAX_TRACKED_PEERS);
    }

    /**
     * Record that a peer timed out during tunnel build.
     * Called from BuildExecutor when a tunnel build expires.
     * Marks the peer as ghost once the timeout threshold is reached.
     * The exclusion expiry (mark time + cooldown) is snapshotted at mark
     * time, so a later change of network state doesn't extend or shorten
     * an active exclusion.
     *
     * @param peer the peer
     */
    public void recordTimeout(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return;}
        pruneToLimit();

        AtomicInteger count = _timeoutCounts.putIfAbsent(peer, new AtomicInteger(1));
        if (count != null) {
            count.incrementAndGet();
        }

        int newCount = count != null ? count.get() : 1;
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        if (newCount >= getThreshold(_context, buildSuccess)) {
            long cooldownMs = getActiveCooldownMs(_context, buildSuccess);
            Long existingExpiry = _ghostUntil.putIfAbsent(peer, _context.clock().now() + cooldownMs);
            if (existingExpiry == null) {
                logGhostMark(peer, newCount, isUnderAttack(buildSuccess), cooldownMs);
            }
        }
    }

    /**
     *  Log a newly-marked ghost peer.  Per-peer detail at debug; a single
     *  WARN at most once per {@link #GHOST_WARN_INTERVAL_MS} so a cascade
     *  of marks doesn't flood the log.
     *
     *  @since 0.9.71+
     */
    private synchronized void logGhostMark(Hash peer, int count, boolean underAttack, long cooldownMs) {
        if (_log.shouldDebug()) {
            _log.debug("Peer [" + peer.toBase64().substring(0, 6) + "] marked as ghost for " + cooldownMs / 1000 +
                       "s -> " + count + " consecutive tunnel build timeouts" +
                       (underAttack ? " (network under stress)" : ""));
        }
        long now = _context.clock().now();
        if (_log.shouldWarn() && now - _lastGhostWarnTime >= GHOST_WARN_INTERVAL_MS) {
            _lastGhostWarnTime = now;
            _log.warn("Tunnel build timeouts are marking peers as ghost -> Enable debug logging for per-peer detail");
        }
    }

    /**
     *  Enforce {@link #MAX_TRACKED_PEERS} when the tracked set is full:
     *  evict peers that never reached the ghost threshold (their stale counts
     *  would otherwise live forever) and ghosts whose cooldown has already
     *  elapsed.  Active ghosts and counts at/above the threshold are kept.
     *  Best-effort under concurrency; size can transiently exceed the limit.
     */
    private void pruneToLimit() {
        if (_timeoutCounts.size() < MAX_TRACKED_PEERS) {
            return;
        }
        int threshold = getThreshold(_context, _context.profileOrganizer().getTunnelBuildSuccess());
        long now = _context.clock().now();
        for (Map.Entry<Hash, AtomicInteger> e : _timeoutCounts.entrySet()) {
            Hash peer = e.getKey();
            AtomicInteger count = e.getValue();
            Long until = _ghostUntil.get(peer);
            // Evict expired ghosts (cooldown elapsed without an isGhost() cleanup)
            // and sub-threshold counts (would otherwise live forever).
            boolean evict = until != null ? (now >= until)
                                          : count.get() < threshold;
            if (evict) {
                _ghostUntil.remove(peer);
                _timeoutCounts.remove(peer, count);
            }
            if (_timeoutCounts.size() < MAX_TRACKED_PEERS) {
                return;
            }
        }
    }

    /**
     * Record successful tunnel participation by a peer.
     * Clears ghost status when a peer successfully participates in a tunnel.
     *
     * @param peer the peer
     */
    public void recordSuccess(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return;}

        _timeoutCounts.computeIfPresent(peer, (k, count) -> {
            count.set(0);
            return count;
        });
        _ghostUntil.remove(peer);
    }

    /**
     * Check if a peer should be excluded from tunnel selection.
     * A peer is a ghost exactly while an unexpired mark exists; marks are
     * recorded eagerly by {@link #recordTimeout(Hash)} once the threshold
     * is reached, so an unmarked count can never exclude a peer.
     *
     * @param peer the peer
     * @return true if the peer is a ghost and should be skipped
     */
    public boolean isGhost(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return false;}

        Long until = _ghostUntil.get(peer);
        if (until == null) {return false;}
        if (_context.clock().now() < until) {return true;}

        // expired mark: drop both entries (also keeps _timeoutCounts bounded)
        _timeoutCounts.remove(peer);
        _ghostUntil.remove(peer);
        return false;
    }

    /**
     *  The current timeout threshold: 3 under stress, else the configured
     *  value.
     *
     *  @return threshold number of timeouts before exclusion
     */
    public int getThreshold() {
        return getThreshold(_context, _context.profileOrganizer().getTunnelBuildSuccess());
    }

    private static int getThreshold(RouterContext ctx, double buildSuccess) {
        return isUnderAttack(buildSuccess) ? ATTACK_TIMEOUT_THRESHOLD : getTimeoutThreshold(ctx);
    }

    /**
     * Clear ghost status for a peer (manual intervention).
     *
     * @param peer the peer
     */
    public void clearGhost(Hash peer) {
        if (peer == null) {return;}
        _timeoutCounts.remove(peer);
        _ghostUntil.remove(peer);
    }

    /**
     * Count of currently tracked ghost peers.
     *
     * @return number of ghost peers
     */
    public int getGhostCount() {
        return _ghostUntil.size();
    }
}
