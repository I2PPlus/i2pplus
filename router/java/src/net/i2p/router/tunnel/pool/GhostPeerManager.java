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
 * there's no reason to retry an unresponsive peer.  The base ghost period
 * (120–300s) is short; legitimate peers recover quickly via
 * {@link #recordSuccess}.  Peers that keep timing out after the mark expires
 * escalate: each repeat timeout within
 * {@link #OFFENSE_DECAY_MS} doubles the next cooldown, capped at
 * {@link #MAX_ESCALATION_FACTOR}× base, so a silently dead peer cannot keep
 * eating one build per pool per cooldown window.
 *
 * @since 0.9.68+
 */
public class GhostPeerManager {
    private final Log _log;
    private final RouterContext _context;
    private final ConcurrentHashMap<Hash, GhostMark> _ghostMarks;

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

    /**
     * Window in which repeat timeouts escalate the next cooldown.  After this
     * long without a fresh timeout the offense count decays to zero, so a peer
     * that recovered and later has one bad day starts over at the base period.
     * @since 0.9.71+
     */
    static final long OFFENSE_DECAY_MS = 30 * 60 * 1000L;

    /**
     * Maximum escalation shift: base × 2² = 4× base (1200s normal, 480s under
     * attack).
     * @since 0.9.71+
     */
    static final int MAX_ESCALATION_SHIFT = 2;

    private static final long GHOST_WARN_INTERVAL_MS = 60 * 1000L;

    private volatile long _lastGhostWarnTime;

    /**
     * One ghost mark: exclusion expiry (snapshotted at mark time), the repeat
     * offense count that produced it, and when it was written so the count can
     * decay.
     */
    private static final class GhostMark {
        final long until;
        final int offenses;
        final long markedAt;

        GhostMark(long until, int offenses, long markedAt) {
            this.until = until;
            this.offenses = offenses;
            this.markedAt = markedAt;
        }
    }

    public GhostPeerManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(GhostPeerManager.class);
        _ghostMarks = new ConcurrentHashMap<>(MAX_TRACKED_PEERS);
    }

    /**
     * Record that a peer timed out during tunnel build.
     * Ghosts the peer immediately (threshold=1).  The exclusion expiry is
     * snapshotted at mark time so a later state change doesn't extend or
     * shorten an active exclusion; a fresh timeout, however, is new evidence
     * and re-marks the peer with an escalated cooldown when it has offended
     * before (see {@link #escalationCooldownMs(long, int)}).  Re-marking also
     * repairs the old stale-entry hole where an expired, never-pruned entry
     * made {@code putIfAbsent} silently no-op and left the peer unmarked.
     *
     * @param peer the peer
     */
    public void recordTimeout(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return;}
        pruneToLimit();

        final long now = _context.clock().now();
        final double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        final long baseCooldown = getActiveCooldownMs(_context, buildSuccess);
        final boolean underAttack = isUnderAttack(buildSuccess);
        _ghostMarks.compute(peer, (p, cur) -> {
            int offenses = cur == null ? 0
                : nextOffenses(cur.offenses, cur.markedAt, now, OFFENSE_DECAY_MS);
            return new GhostMark(now + escalationCooldownMs(baseCooldown, offenses),
                                 offenses, now);
        });
        GhostMark mark = _ghostMarks.get(peer);
        logGhostMark(peer, underAttack, mark != null ? mark.until - now : baseCooldown,
                     mark != null ? mark.offenses : 0);
    }

    /**
     *  Offense count for a repeat timeout: previous count plus one, reset to
     *  zero when the previous mark is older than the decay window (or the
     *  clock moved backwards).  Pure decision helper.
     *
     *  @param currentOffenses offense count on the existing mark, &lt; 0 treated as 0
     *  @param lastMarkAt when the existing mark was written (ms), 0 if none
     *  @param now current router time (ms)
     *  @param decayMs window in which repeat offenses count (ms)
     *  @return offense count to apply to the new mark
     *  @since 0.9.71+
     */
    static int nextOffenses(int currentOffenses, long lastMarkAt, long now, long decayMs) {
        int base = currentOffenses < 0 ? 0 : currentOffenses;
        if (lastMarkAt <= 0 || now < lastMarkAt || now - lastMarkAt >= decayMs) {
            return 0;
        }
        return base + 1;
    }

    /**
     *  Escalating ghost cooldown: base doubled per offense, capped at
     *  {@link #MAX_ESCALATION_SHIFT} doublings (4× base).  Pure helper.
     *
     *  @param baseMs cooldown for a first offense (ms)
     *  @param offenses repeat timeouts within the decay window, &lt;= 0 means base
     *  @return cooldown for this mark (ms)
     *  @since 0.9.71+
     */
    static long escalationCooldownMs(long baseMs, int offenses) {
        if (baseMs <= 0 || offenses <= 0) {
            return baseMs;
        }
        int shift = Math.min(offenses, MAX_ESCALATION_SHIFT);
        return baseMs << shift;
    }

    private synchronized void logGhostMark(Hash peer, boolean underAttack, long cooldownMs, int offenses) {
        if (_log.shouldDebug()) {
            _log.debug("Peer [" + peer.toBase64().substring(0, 6) + "] marked as ghost for " +
                       cooldownMs / 1000 + "s" +
                       (offenses > 0 ? " (repeat offense " + offenses + ")" : "") +
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
        if (_ghostMarks.size() < MAX_TRACKED_PEERS) {
            return;
        }
        long now = _context.clock().now();
        for (Hash peer : _ghostMarks.keySet()) {
            GhostMark mark = _ghostMarks.get(peer);
            if (mark == null || now >= mark.until) {
                _ghostMarks.remove(peer);
            }
            if (_ghostMarks.size() < MAX_TRACKED_PEERS) {
                return;
            }
        }
    }

    /**
     * Record successful tunnel participation by a peer.
     * Clears ghost status immediately, including its offense history — a peer
     * that just routed a tunnel is proven live.
     *
     * @param peer the peer
     */
    public void recordSuccess(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return;}
        _ghostMarks.remove(peer);
    }

    /**
     * Check if a peer should be excluded from tunnel selection.
     * Expired marks are left in place (not evicted) so their offense
     * history survives for escalation; they report {@code false} here and
     * count as inactive in {@link #getGhostCount()}.
     *
     * @param peer the peer
     * @return true if the peer is a ghost and should be skipped
     */
    public boolean isGhost(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return false;}

        GhostMark mark = _ghostMarks.get(peer);
        return mark != null && _context.clock().now() < mark.until;
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
        _ghostMarks.remove(peer);
    }

    /**
     * Count of currently excluded ghost peers (active marks only — expired
     * entries retained for offense history are not counted).
     *
     * @return number of ghost peers
     */
    public int getGhostCount() {
        long now = _context.clock().now();
        int count = 0;
        for (GhostMark mark : _ghostMarks.values()) {
            if (now < mark.until) {
                count++;
            }
        }
        return count;
    }
}
