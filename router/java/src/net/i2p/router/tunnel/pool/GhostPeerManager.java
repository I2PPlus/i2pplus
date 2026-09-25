package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.util.Log;

/**
 * Temporarily excludes unresponsive peers from tunnel build selection.
 *
 * A peer is excluded only after
 * {@code i2p.tunnel.ghostPeer.timeoutThreshold} consecutive timeouts
 * (strikes); earlier timeouts are tracked so they escalate the cooldown once
 * the threshold is reached, but they do not exclude the peer on their own.
 * The base ghost period (120–300s) is short; legitimate peers recover
 * quickly via {@link #recordSuccess}.  Peers that keep timing out after the
 * mark expires escalate: each repeat timeout within {@link #OFFENSE_DECAY_MS}
 * doubles the next cooldown, capped at {@link #MAX_ESCALATION_SHIFT} doublings
 * (4× base), so a silently dead peer cannot keep eating one build per pool per
 * cooldown window.
 *
 * <p>Tracked marks are hard-bounded at {@link #MAX_TRACKED_PEERS} (expired
 * marks evicted first, then the oldest active ones), and the active exclusion
 * count is cached so {@link #getGhostCount()} is a volatile read on the hot
 * path instead of a full map scan.
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

    /**
     * Hard bound on tracked marks; package-visible so tests can pin the
     * eviction bound against it.
     */
    static final int MAX_TRACKED_PEERS = 1024;

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
     * Immutable (active count, sweep deadline) pair written under
     * {@code synchronized (this)} and read lock-free, so the two fields can
     * never be observed torn.  The deadline is the earliest expiry among
     * active marks (Long.MAX_VALUE when none), which makes it the validity
     * bound of the cached count: once the clock passes it the count is stale
     * and the next reader or writer recomputes it.
     */
    private volatile CountSnapshot _activeSnapshot = new CountSnapshot(0, 0L);

    private static final class CountSnapshot {
        final int active;
        final long due;

        CountSnapshot(int active, long due) {
            this.active = active;
            this.due = due;
        }
    }

    /**
     * Marks evicted above {@link #MAX_TRACKED_PEERS} go down to this headroom
     * so a burst of new timeouts does not force a full evict pass per mark.
     */
    private static final int EVICTION_HEADROOM = 32;

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
     *
     * <p>The peer is excluded only once its strike count reaches the
     * configured threshold ({@code i2p.tunnel.ghostPeer.timeoutThreshold},
     * at least 1); earlier strikes are recorded without excluding it, so the
     * configured sensitivity actually gates selection.  The exclusion expiry
     * is snapshotted at mark time so a later state change doesn't extend or
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
        final int threshold = Math.max(1, getTimeoutThreshold(_context));
        _ghostMarks.compute(peer, (p, cur) -> {
            int offenses = cur == null ? 0
                : nextOffenses(cur.offenses, cur.markedAt, now, OFFENSE_DECAY_MS);
            if (offenses < threshold - 1) {
                // Sub-threshold strike: track it for escalation without
                // excluding the peer.  An exclusion already in force is never
                // revoked by a later write — its expiry was snapshotted when
                // the peer earned it.
                long until = cur != null && now < cur.until ? cur.until : 0L;
                return new GhostMark(until, offenses, now);
            }
            // strikes = offenses + 1; shift so the first strike at or above
            // the threshold gets the base cooldown (T=1: shift == offenses).
            int shift = Math.max(0, offenses - (threshold - 1));
            return new GhostMark(now + escalationCooldownMs(baseCooldown, shift),
                                 offenses, now);
        });
        refreshActiveCount();
        GhostMark mark = _ghostMarks.get(peer);
        if (mark != null && now < mark.until) {
            logGhostMark(peer, underAttack, mark.until - now, mark.offenses);
        } else if (_log.shouldDebug()) {
            _log.debug("Peer [" + peer.toBase64().substring(0, 6) + "] strike " +
                       (mark != null ? mark.offenses + 1 : 1) + "/" + threshold +
                       (underAttack ? " (network under stress)" : "") +
                       " - not yet excluded");
        }
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
     * Enforce {@link #MAX_TRACKED_PEERS} as a hard bound: evict down to the
     * headroom, expired marks first (oldest first), then the oldest active
     * marks, so an hour of timeouts across many peers can never grow the map
     * without limit.  O(n log n) but only runs once the bound is reached;
     * the active count is recomputed at the end.
     */
    private synchronized void pruneToLimit() {
        int size = _ghostMarks.size();
        int target = MAX_TRACKED_PEERS - EVICTION_HEADROOM;
        if (size <= target) {
            return;
        }
        long now = _context.clock().now();
        int toEvict = size - target;
        List<Map.Entry<Hash, GhostMark>> entries = new ArrayList<>(_ghostMarks.entrySet());
        entries.sort(Comparator
                .comparingLong((Map.Entry<Hash, GhostMark> e) -> now >= e.getValue().until ? 0 : 1)
                .thenComparingLong(e -> e.getValue().markedAt));
        for (int i = 0; i < toEvict && i < entries.size(); i++) {
            _ghostMarks.remove(entries.get(i).getKey());
        }
        rescanActiveLocked(now);
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
        if (_ghostMarks.remove(peer) != null) {
            refreshActiveCount();
        }
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
     *  The configured timeout threshold: consecutive strikes required
     *  before a peer is excluded (values below 1 are treated as 1 by
     *  {@link #recordTimeout}).
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
        if (_ghostMarks.remove(peer) != null) {
            refreshActiveCount();
        }
    }

    /**
     * Count of currently excluded ghost peers (active marks only — expired
     * or sub-threshold entries retained for offense history are not counted).
     * A single volatile read when the cached count is still within its
     * deadline; a full rescan only when an exclusion has expired since the
     * last refresh or the map was mutated.
     *
     * @return number of ghost peers
     */
    public int getGhostCount() {
        CountSnapshot snap = _activeSnapshot;
        long now = _context.clock().now();
        if (now < snap.due) {
            return snap.active;
        }
        synchronized (this) {
            rescanActiveLocked(now);
            return _activeSnapshot.active;
        }
    }

    /**
     * Number of tracked marks, active or not (exposed for tests).
     * Bounded by {@link #MAX_TRACKED_PEERS} plus in-flight inserts.
     *
     * @return the size of the mark map
     * @since 0.9.71+
     */
    int getTrackedCount() {
        return _ghostMarks.size();
    }

    /**
     * Recompute the cached active count after a mutation.  Full rescan rather
     * than deltas: the map is hard-bounded, mutations are per build result (not
     * per packet), and one exact pass cannot drift the way incremental
     * +/- accounting can under concurrent mark/write races.
     */
    private synchronized void refreshActiveCount() {
        rescanActiveLocked(_context.clock().now());
    }

    /**
     * Recompute and publish the active count; caller must hold {@code this}.
     * The deadline is the earliest expiry among active marks, so the cache is
     * self-invalidating the moment the soonest exclusion lapses.
     *
     * @param now current router time (ms)
     */
    private void rescanActiveLocked(long now) {
        int active = 0;
        long due = Long.MAX_VALUE;
        for (GhostMark mark : _ghostMarks.values()) {
            if (now < mark.until) {
                active++;
                if (mark.until < due) {
                    due = mark.until;
                }
            }
        }
        _activeSnapshot = new CountSnapshot(active, due);
    }
}
