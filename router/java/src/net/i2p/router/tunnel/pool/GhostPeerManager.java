package net.i2p.router.tunnel.pool;

import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.util.Log;

/**
 * Temporarily excludes unresponsive peers from tunnel build selection.
 *
 * A single ignored build request ghosts a peer — with a large candidate pool
 * there's no reason to retry an unresponsive peer.  The ghost period
 * (120–300s) is short; legitimate peers recover quickly via
 * {@link #recordSuccess}.
 *
 * @since 0.9.68+
 */
public class GhostPeerManager {
    private final Log _log;
    private final RouterContext _context;
    private final ConcurrentHashMap<Hash, Long> _ghostUntil;

    private static int getTimeoutThreshold(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.ghostPeer.timeoutThreshold", 1);
    }

    private static long getCooldownMs(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.ghostPeer.cooldownMs", 300*1000);
    }

    private static long getAttackCooldownMs(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.ghostPeer.attackCooldownMs", 120*1000);
    }

    private static long getActiveCooldownMs(RouterContext ctx, double buildSuccess) {
        return isUnderAttack(buildSuccess) ? getAttackCooldownMs(ctx) : getCooldownMs(ctx);
    }

    private static boolean isUnderAttack(double buildSuccess) {
        return buildSuccess < ProfileOrganizer.ATTACK_THRESHOLD;
    }

    private static final int MAX_TRACKED_PEERS = 1024;

    private static final long GHOST_WARN_INTERVAL_MS = 60 * 1000L;

    private volatile long _lastGhostWarnTime;

    public GhostPeerManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(GhostPeerManager.class);
        _ghostUntil = new ConcurrentHashMap<>(MAX_TRACKED_PEERS);
    }

    /**
     * Record that a peer timed out during tunnel build.
     * Ghosts the peer immediately (threshold=1).  The exclusion expiry is
     * snapshotted at mark time so a later state change doesn't extend or
     * shorten an active exclusion.
     *
     * @param peer the peer
     */
    public void recordTimeout(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return;}
        pruneToLimit();

        long now = _context.clock().now();
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        long cooldownMs = getActiveCooldownMs(_context, buildSuccess);
        Long existingExpiry = _ghostUntil.putIfAbsent(peer, now + cooldownMs);
        if (existingExpiry == null) {
            logGhostMark(peer, isUnderAttack(buildSuccess), cooldownMs);
        }
    }

    private synchronized void logGhostMark(Hash peer, boolean underAttack, long cooldownMs) {
        if (_log.shouldDebug()) {
            _log.debug("Peer [" + peer.toBase64().substring(0, 6) + "] marked as ghost for " + cooldownMs / 1000 + "s" +
                       (underAttack ? " (network under stress)" : ""));
        }
        long now = _context.clock().now();
        if (_log.shouldWarn() && now - _lastGhostWarnTime >= GHOST_WARN_INTERVAL_MS) {
            _lastGhostWarnTime = now;
            _log.warn("Tunnel build timeouts are marking peers as ghost -> Enable debug logging for per-peer detail");
        }
    }

    /**
     *  Evict expired ghosts to enforce {@link #MAX_TRACKED_PEERS}.
     *  Best-effort under concurrency; size can transiently exceed the limit.
     */
    private void pruneToLimit() {
        if (_ghostUntil.size() < MAX_TRACKED_PEERS) {
            return;
        }
        long now = _context.clock().now();
        for (Hash peer : _ghostUntil.keySet()) {
            Long until = _ghostUntil.get(peer);
            if (until != null && now >= until) {
                _ghostUntil.remove(peer);
            }
            if (_ghostUntil.size() < MAX_TRACKED_PEERS) {
                return;
            }
        }
    }

    /**
     * Record successful tunnel participation by a peer.
     * Clears ghost status immediately.
     *
     * @param peer the peer
     */
    public void recordSuccess(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return;}
        _ghostUntil.remove(peer);
    }

    /**
     * Check if a peer should be excluded from tunnel selection.
     *
     * @param peer the peer
     * @return true if the peer is a ghost and should be skipped
     */
    public boolean isGhost(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return false;}

        Long until = _ghostUntil.get(peer);
        if (until == null) {return false;}
        if (_context.clock().now() < until) {return true;}

        _ghostUntil.remove(peer);
        return false;
    }

    /**
     *  The current timeout threshold.
     *
     *  @return threshold number of timeouts before exclusion
     */
    public int getThreshold() {
        return getTimeoutThreshold(_context);
    }

    /**
     * Clear ghost status for a peer (manual intervention).
     *
     * @param peer the peer
     */
    public void clearGhost(Hash peer) {
        if (peer == null) {return;}
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
