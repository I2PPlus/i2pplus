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
 * marks evicted first, then those nearest to expiry), and the *active*
 * exclusion set is separately hard-bounded at {@link #MAX_ACTIVE_GHOSTS} by
 * deactivating the exclusions closest to lapsing.  Without that second bound a
 * burst of timeouts can exclude enough of the reachable set that tier selection
 * returns no candidates at all — the exclusion list starves the very selection
 * it is meant to protect.  Deactivation keeps {@code offenses} and
 * {@code markedAt}, so escalation resumes if the peer times out again.
 *
 * <p>The active exclusion count is cached so {@link #getGhostCount()} is a
 * volatile read on the hot path instead of a full map scan; its sweep deadline
 * is bucketed to {@link #COUNT_GRANULARITY_MS} so scattered expiry times cost
 * at most one rescan per bucket rather than one per mark.
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
     * Hard bound on *active* exclusions.  Excluded peers are also gated out of
     * tier selection, so an unbounded active set starves the candidate pool:
     * observed as hundreds of "Skipping ghost peer" lines and "All selected
     * peers were ghosts" build failures.  Keeping the active set well below the
     * reachable peer count preserves the exclusion's value without ever letting
     * it empty the tier.  Package-visible so tests can pin the bound.
     * @since 0.9.71+
     */
    static final int MAX_ACTIVE_GHOSTS = 128;

    /**
     * Active exclusions are evicted down to this headroom, mirroring
     * {@link #EVICTION_HEADROOM}, so a sustained timeout storm pays for a full
     * count rescan every few marks rather than on every mark.
     * @since 0.9.71+
     */
    private static final int ACTIVE_EVICTION_HEADROOM = 16;

    /**
     * Sweep deadlines are rounded up to this many milliseconds.  Marks expire
     * at whatever offset the timeout landed on; without bucketing, a hundred
     * staggered expiries would force a full map rescan per distinct millisecond
     * as {@link #getGhostCount()} is polled.  Bucketing costs at most this much
     * staleness in the *count* only — {@link #isGhost(Hash)} always reads the
     * map directly, so selection is never affected.
     * @since 0.9.71+
     */
    static final long COUNT_GRANULARITY_MS = 1000L;

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

    /**
     * Eviction order by expiry ascending: expired marks sort first (oldest
     * expiry first, so they go in the order they lapsed), followed by active
     * marks nearest to lapsing.  Keying on the expiry rather than the mark time
     * keeps whichever exclusions have the most protection left — a 4× escalated
     * mark still ten minutes from lapsing outlives a base mark about to lapse,
     * which {@code markedAt} ordering would get backwards.
     */
    private static final Comparator<Map.Entry<Hash, GhostMark>> BY_EXPIRY =
        Comparator.comparingLong((Map.Entry<Hash, GhostMark> e) -> e.getValue().until);

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

        final long now = _context.clock().now();
        final double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        final long baseCooldown = getActiveCooldownMs(_context, buildSuccess);
        final boolean underAttack = isUnderAttack(buildSuccess);
        final int threshold = Math.max(1, getTimeoutThreshold(_context));
        GhostMark mark;
        boolean isActive;
        // One monitor section for prune + mark + count.  _ghostMarks is private
        // to this class, so serialising every writer here makes the read/modify/
        // write below atomic and lets the active count be maintained by delta
        // instead of a full rescan per timeout.  The rate-limited ghost warning
        // rides along in the same section: it was already a synchronized method,
        // so this folds two acquisitions into one rather than adding hold time.
        synchronized (this) {
            pruneToLimitLocked(now);
            GhostMark cur = _ghostMarks.get(peer);
            int offenses = cur == null ? 0
                : nextOffenses(cur.offenses, cur.markedAt, now, OFFENSE_DECAY_MS);
            if (offenses < threshold - 1) {
                // Sub-threshold strike: track it for escalation without
                // excluding the peer.  An exclusion already in force is never
                // revoked by a later write — its expiry was snapshotted when
                // the peer earned it.
                long until = cur != null && now < cur.until ? cur.until : 0L;
                mark = new GhostMark(until, offenses, now);
            } else {
                // strikes = offenses + 1; shift so the first strike at or above
                // the threshold gets the base cooldown (T=1: shift == offenses).
                int shift = Math.max(0, offenses - (threshold - 1));
                mark = new GhostMark(now + escalationCooldownMs(baseCooldown, shift),
                                     offenses, now);
            }
            boolean wasActive = cur != null && now < cur.until;
            _ghostMarks.put(peer, mark);
            adjustActiveLocked(wasActive, now < mark.until, mark.until);
            capActiveLocked(now);
            // The active-set cap may have just deactivated this very mark, so
            // read it back rather than trusting the value we inserted.
            mark = _ghostMarks.get(peer);
            isActive = mark != null && now < mark.until;
            if (isActive) {
                logGhostMarkLocked(peer, underAttack, mark.until - now, mark.offenses, now);
            }
        }
        if (!isActive && _log.shouldDebug()) {
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

    /**
     * Debug detail plus the once-per-minute warn summary.  Caller must hold
     * {@code this} — the warn path both reads and writes the rate-limit stamp,
     * and callers are already inside the section that owns the map mutation, so
     * folding it in avoids a second acquisition per marked peer.
     *
     * @param now the caller's view of the clock, reused instead of re-reading it
     */
    private void logGhostMarkLocked(Hash peer, boolean underAttack, long cooldownMs,
                                    int offenses, long now) {
        if (_log.shouldDebug()) {
            _log.debug("Peer [" + peer.toBase64().substring(0, 6) + "] marked as ghost for " +
                       cooldownMs / 1000 + "s" +
                       (offenses > 0 ? " (repeat offense " + offenses + ")" : "") +
                       (underAttack ? " (network under stress)" : ""));
        }
        if (_log.shouldWarn() && now - _lastGhostWarnTime >= GHOST_WARN_INTERVAL_MS) {
            _lastGhostWarnTime = now;
            _log.warn("Tunnel build timeouts are marking peers as ghost -> Enable debug logging for per-peer detail");
        }
    }

    /**
     * Apply one mark insertion to the cached active count: +1 when the peer
     * becomes newly excluded, −1 when it stops being excluded, and no change
     * when it was and still is (or was not and still is not).  The deadline
     * only ever moves earlier — pushing it out would risk serving a stale count
     * past a real expiry, whereas an early deadline just costs one extra
     * exact rescan.  Caller must hold {@code this}.
     *
     * @param wasActive whether the peer's previous mark was still excluding it
     * @param isActive whether the new mark excludes it
     * @param until new mark's expiry (ms), irrelevant when not active
     */
    private void adjustActiveLocked(boolean wasActive, boolean isActive, long until) {
        CountSnapshot snap = _activeSnapshot;
        int active = snap.active + (isActive ? 1 : 0) - (wasActive ? 1 : 0);
        long due = snap.due;
        if (active < 0) {
            active = 0;
        }
        if (isActive && until < due) {
            due = until;
        }
        _activeSnapshot = new CountSnapshot(active, bucketDue(due));
    }

    /**
     * Round a sweep deadline up to the next {@link #COUNT_GRANULARITY_MS}
     * boundary, so the cache is recomputed at most once per bucket instead of
     * once per distinct expiry millisecond.  Idempotent; {@code Long.MAX_VALUE}
     * (no active marks) passes through untouched.
     *
     * @param due earliest active expiry, or {@code Long.MAX_VALUE}
     * @return the bucketed deadline
     * @since 0.9.71+
     */
    static long bucketDue(long due) {
        if (due == Long.MAX_VALUE || due <= 0) {
            return due;
        }
        return (due + COUNT_GRANULARITY_MS - 1) / COUNT_GRANULARITY_MS
               * COUNT_GRANULARITY_MS;
    }

    /**
     * Enforce {@link #MAX_ACTIVE_GHOSTS}: a burst of timeouts that excludes
     * enough of the reachable set makes tier selection return nothing — the
     * exclusion intended to protect the build instead starves it (seen as
     * "All selected peers were ghosts").  Deactivate the excess, dropping the
     * exclusions nearest to lapsing so the set keeps the marks with the most
     * time left.  Deactivation zeroes {@code until} only: the offense count and
     * mark time survive, so a peer that keeps timing out re-escalates instead
     * of starting over.  No-op — and so free — while the set is within bounds.
     * Caller must hold {@code this}.
     *
     * @param now current router time (ms)
     */
    private void capActiveLocked(long now) {
        CountSnapshot snap = _activeSnapshot;
        if (snap.active < MAX_ACTIVE_GHOSTS) {
            return;
        }
        int target = MAX_ACTIVE_GHOSTS - ACTIVE_EVICTION_HEADROOM;
        List<Map.Entry<Hash, GhostMark>> active = new ArrayList<>(snap.active);
        for (Map.Entry<Hash, GhostMark> e : _ghostMarks.entrySet()) {
            if (now < e.getValue().until) {
                active.add(e);
            }
        }
        active.sort(BY_EXPIRY);
        int excess = active.size() - target;
        if (excess <= 0) {
            // Snapshot overstated the count (an expiry lapsed since the last
            // sweep); publish the true one and skip the evict pass.
            rescanActiveLocked(now);
            return;
        }
        for (int i = 0; i < excess; i++) {
            Map.Entry<Hash, GhostMark> victim = active.get(i);
            GhostMark m = victim.getValue();
            _ghostMarks.put(victim.getKey(), new GhostMark(0L, m.offenses, m.markedAt));
        }
        rescanActiveLocked(now);
    }

    /**
     * Enforce {@link #MAX_TRACKED_PEERS} as a hard bound.  Triggered only once
     * the bound is actually reached, then evicted down to the headroom, so the
     * headroom really is the number of inserts absorbed before the next pass
     * rather than the point the pass starts at.  Eviction order is
     * {@link #BY_EXPIRY} — expired marks first, then actives nearest to lapsing.
     * O(n log n), once per headroom's worth of inserts.  Rescans the active
     * count only when it actually evicted, so the caller's single delta isn't
     * undone by an unconditional recompute.  Caller must hold {@code this}.
     *
     * @param now current router time (ms)
     */
    private void pruneToLimitLocked(long now) {
        int size = _ghostMarks.size();
        if (size < MAX_TRACKED_PEERS) {
            return;
        }
        int toEvict = size - (MAX_TRACKED_PEERS - EVICTION_HEADROOM);
        List<Map.Entry<Hash, GhostMark>> entries = new ArrayList<>(_ghostMarks.entrySet());
        entries.sort(BY_EXPIRY);
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
        // Same monitor as recordTimeout so a removal can never interleave
        // between its map write and its count delta.  Rare enough that the
        // exact rescan is cheaper than tracking a removal delta here.
        synchronized (this) {
            if (_ghostMarks.remove(peer) != null) {
                rescanActiveLocked(_context.clock().now());
            }
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
        synchronized (this) {
            if (_ghostMarks.remove(peer) != null) {
                rescanActiveLocked(_context.clock().now());
            }
        }
    }

    /**
     * Count of currently excluded ghost peers (active marks only — expired
     * or sub-threshold entries retained for offense history are not counted).
     * A single volatile read while the cached count is within its deadline;
     * an exact rescan only once the bucketed deadline passes.  Deliberately
     * capped by {@link #MAX_ACTIVE_GHOSTS}, which is what keeps a timeout
     * storm from emptying the candidate tier.
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
     * Recompute and publish the active count; caller must hold {@code this}.
     * The deadline is the earliest expiry among active marks, bucketed up to
     * {@link #COUNT_GRANULARITY_MS}, so the cache is self-invalidating within
     * one bucket of the soonest exclusion lapsing without forcing a rescan per
     * staggered expiry.
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
        _activeSnapshot = new CountSnapshot(active, bucketDue(due));
    }
}
