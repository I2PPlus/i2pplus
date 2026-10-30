package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.util.Log;

/**
 * Temporarily excludes unresponsive peers from tunnel build selection.
 * <p>A peer is excluded only after
 * {@code i2p.tunnel.ghostPeer.timeoutThreshold} consecutive timeouts
 * (strikes); earlier timeouts are tracked but do not exclude the peer on their
 * own.
 * <p>The exclusion is flat and short — {@link #DEFAULT_COOLDOWN_MS}, with no
 * escalation for repeat timeouts.
 * <p>Escalation was removed because it bought nothing. The cooldown this mark
 * governs is not the one that keeps a failed peer out of selection:
 * {@code TunnelPeerSelector.FIRST_HOP_FAIL_COOLDOWN_MS} is five minutes, and
 * {@code ProfileOrganizer.passesBasicGates} tests it directly, so a peer that
 * failed as a first hop is already excluded from tier promotion for five
 * minutes regardless. Every ghost cooldown the router can express — 120s, 300s,
 * or the 240s an escalation produced — sat inside that window. Doubling it on a
 * repeat timeout therefore excluded the peer from more than it already was,
 * while making the mark outlive the build burst it was written to cover, which
 * is the only thing it is uniquely positioned to do: the mark also gates
 * promotion in roles the first-hop cooldown does not cover, and a recovered
 * peer should be back in those roles within a minute or two.
 * <p>What a silently dead peer is protected by is eviction, not escalation.
 * {@code BuildExecutor.penalizeTimeout} demotes the contacted hop from the
 * fast and high-cap tiers on the same failure, and the three-strike
 * {@code demoteIfUnreachable} still runs for the failures that do not indict
 * the peer. A peer that is dead leaves the candidate set on evidence, not on a
 * timer.
 * <p>Tracked marks are hard-bounded at {@link #MAX_TRACKED_PEERS} (expired
 * marks evicted first, then those nearest to expiry), and the *active*
 * exclusion set is separately hard-bounded at {@link #MAX_ACTIVE_GHOSTS} by
 * deactivating the exclusions closest to lapsing.  Without that second bound a
 * burst of timeouts can exclude enough of the reachable set that tier selection
 * returns no candidates at all — the exclusion list starves the very selection
 * it is meant to protect.  Deactivation keeps {@code offenses} and
 * {@code markedAt}, so the strike history survives and a peer that times out
 * again still counts toward {@code timeoutThreshold}.
 * <p>The active exclusion count is cached so {@link #getGhostCount} is a
 * volatile read on the hot path instead of a full map scan; its sweep deadline
 * is bucketed to {@link #COUNT_GRANULARITY_MS} so scattered expiry times cost
 * at most one rescan per bucket rather than one per mark.
 *
 * @since 0.9.68+
 */
public class GhostPeerManager {
    /** Logger for the ghost peer manager. */
    private final Log _log;
    /** The router context. */
    private final RouterContext _context;
    /** The marked peers, by hash. */
    private final ConcurrentHashMap<Hash, GhostMark> _ghostMarks;

/**
 * Default exclusion length. Long enough to sit out the burst of builds that
 * follows one failure, short enough that a peer proven live by a later
 * success is back in tier rotation within a minute or two.
 *
 * @since 0.9.71+
 */
static final int DEFAULT_COOLDOWN_MS = 90 * 1000;

/**
 * Reads the timeout count that marks a peer.
 * <p>Two consecutive timeouts, not one. A build timeout is the commonest
 * transient outcome of asking a peer for a tunnel on a congested path — a
 * router measured at 52% build success times out on roughly half of its
 * requests — so a single one says more about the path than about the peer. The
 * mark is a peer exclusion that also blocks tier promotion, so it needs the
 * peer to have failed twice in the strike window before it costs anything.
 * <p>Property-tunable, so an operator who wants the old one-strike behaviour
 * can set {@code i2p.tunnel.ghostPeer.timeoutThreshold=1}.
 *
 * @param ctx the router context
 * @return the threshold, clamped to a sane range
 * @since 0.9.72+ (the default is 2)
 */
private static int getTimeoutThreshold(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.ghostPeer.timeoutThreshold", 2);
    }

/**
 * Reads the ghost peer cooldown time.
 *
 * @param ctx the router context
 * @return the cooldown, in ms, clamped to a sane range
 */
private static long getCooldownMs(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.ghostPeer.cooldownMs", DEFAULT_COOLDOWN_MS);
    }

/**
 * Checks whether the build success rate means an attack.
 *
 * @param buildSuccess the cached tunnel build success rate
 * @return true if the rate is below the attack threshold
 */
private static boolean isUnderAttack(double buildSuccess) {
        return buildSuccess < ProfileOrganizer.ATTACK_THRESHOLD;
    }

    /**
     * Hard bound on tracked marks; package-visible so tests can pin the
     * eviction bound against it.
     */
    static final int MAX_TRACKED_PEERS = 1024;

    /**
 * Window in which repeat timeouts keep counting toward
 * {@code i2p.tunnel.ghostPeer.timeoutThreshold}. After this long without a
 * fresh timeout the strike count resets, so a peer that recovered and later has
 * one bad day starts the count over rather than being excluded on its first
 * strike.
 * <p>This no longer governs a cooldown — the exclusion is flat
 * ({@link #DEFAULT_COOLDOWN_MS}) — but it still governs whether a timeout is a
 * first strike or a repeat one when the threshold is above 1.
 *
 * @since 0.9.71+
 */
static final long OFFENSE_DECAY_MS = 30 * 60 * 1000L;

    /** Silence between the ghost peer warning logs. */
    private static final long GHOST_WARN_INTERVAL_MS = 60 * 1000L;

    /** When the ghost peer warning was last logged. */
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

    /** Counts captured at one point in time. */
    private static final class CountSnapshot {
        /** Marks active at the snapshot. */
        final int active;
        /** Marks expiring by the snapshot time. */
        final long due;

        /**
         * Records one count snapshot.
         *
         * @param active the marks active at the snapshot
         * @param due the marks expiring by the snapshot time
         */
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
     * as {@link #getGhostCount} is polled.  Bucketing costs at most this much
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
        /** When the mark expires. */
        final long until;
        /** Times the peer was marked. */
        final int offenses;
        /** When the mark was applied. */
        final long markedAt;

        /**
         * Records one peer mark.
         *
         * @param until when the mark expires
         * @param offenses the number of times the peer was marked
         * @param markedAt when the mark was applied
         */
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
     * keeps whichever exclusions have the most protection left. A 4× escalated
     * mark still ten minutes from lapsing outlives a base mark about to lapse,
     * which {@code markedAt} ordering would get backwards.
     */
    private static final Comparator<Map.Entry<Hash, GhostMark>> BY_EXPIRY =
        Comparator.comparingLong((Map.Entry<Hash, GhostMark> e) -> e.getValue().until);

    /**
     * Create a ghost peer manager.
     *
     * @param context the router context
     */
    public GhostPeerManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(GhostPeerManager.class);
        _ghostMarks = new ConcurrentHashMap<>(MAX_TRACKED_PEERS);
    }

    /**
     * Record that a peer timed out during tunnel build.
     * <p>The peer is excluded only once its strike count reaches the
     * configured threshold ({@code i2p.tunnel.ghostPeer.timeoutThreshold},
     * at least 1). Earlier strikes are recorded without excluding it, so the
     * configured sensitivity actually gates selection.  The exclusion expiry
     * is snapshotted at mark time so a later state change doesn't extend or
     * shorten an active exclusion; a fresh timeout is new evidence and re-marks
     * the peer for a fresh {@link #DEFAULT_COOLDOWN_MS}.  Re-marking also
     * repairs the old stale-entry hole where an expired, never-pruned entry
     * made {@code putIfAbsent} silently no-op and left the peer unmarked.
     *
     * @param peer the router hash of the peer that timed out; our own hash is ignored
     */
    public void recordTimeout(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return;}

        final long now = _context.clock().now();
        final double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        final long cooldown = getCooldownMs(_context);
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
                // Sub-threshold strike: counted toward the threshold without
                // excluding the peer.  An exclusion already in force is never
                // revoked by a later write — its expiry was snapshotted when
                // the peer earned it.
                long until = cur != null && now < cur.until ? cur.until : 0L;
                mark = new GhostMark(until, offenses, now);
            } else {
                mark = new GhostMark(now + cooldown, offenses, now);
            }
            boolean wasActive = cur != null && now < cur.until;
            _ghostMarks.put(peer, mark);
            // Every timeout that reaches here, whether it newly excludes the peer or
            // merely extends an existing mark, so the rate shows exclusion pressure
            // rather than only newly-marked peers.
            _context.statManager().addRateData("tunnel.ghostMarksAdded", 1);
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
     * Offense count for a repeat timeout: previous count plus one, reset to
     * zero when the previous mark is older than the decay window (or the
     * clock moved backwards).  Pure decision helper.
     *
     * @param currentOffenses offense count on the existing mark, &lt; 0 treated as 0
     * @param lastMarkAt when the existing mark was written (ms), 0 if none
     * @param now current router time (ms)
     * @param decayMs window in which repeat offenses count (ms)
     * @return offense count to apply to the new mark
     * @since 0.9.71+
     */
    static int nextOffenses(int currentOffenses, long lastMarkAt, long now, long decayMs) {
        int base = currentOffenses < 0 ? 0 : currentOffenses;
        if (lastMarkAt <= 0 || now < lastMarkAt || now - lastMarkAt >= decayMs) {
            return 0;
        }
        return base + 1;
    }

    /**
     * Debug detail plus the once-per-minute warn summary.  Caller must hold
     * {@code this} — the warn path both reads and writes the rate-limit stamp,
     * and callers are already inside the section that owns the map mutation, so
     * folding it in avoids a second acquisition per marked peer.
     *
     * @param peer the peer just marked as a ghost, named in the debug log
     * @param underAttack true if the network is under stress, noted in the
     *                    debug log
     * @param cooldownMs the exclusion duration applied to the peer, in
     *                   milliseconds
     * @param offenses the peer's repeat offense count, 0 for a first mark
     * @param now the caller's view of the clock, reused instead of re-reading it
     */
    private void logGhostMarkLocked(Hash peer, boolean underAttack, long cooldownMs,
                                     int offenses, long now) {
        if (_log.shouldDebug()) {
            _log.debug("Peer [" + peer.toBase64().substring(0, 6) + "] marked as ghost for " +
                       DataHelper.formatDuration(cooldownMs) +
                       (offenses > 0 ? " (repeat offense " + offenses + ")" : "") +
                       (underAttack ? " (network under stress)" : ""));
        }
        if (_log.shouldInfo() && now - _lastGhostWarnTime >= GHOST_WARN_INTERVAL_MS) {
            _lastGhostWarnTime = now;
            _log.info("Tunnel build timeouts are marking peers as ghost" +
                      " -> Enable debug logging for per-peer detail");
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
     * enough of the reachable set makes tier selection return nothing. The
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
     * Clears ghost status immediately, including its offense history. A peer
     * that just routed a tunnel is proven live.
     * <p>Called for every peer on every successful build, so the overwhelming
     * majority of calls find no mark present. Only a call that actually removed
     * a mark is counted: a success from a peer that was never excluded is not a
     * recovery, and counting those inflated this stat to roughly 3.4x the number
     * of marks added, which made the two counters impossible to reconcile.
     *
     * @param peer the router hash of the peer that just routed a tunnel; our own hash is ignored
     */
    public void recordSuccess(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return;}
        // Same monitor as recordTimeout so a removal can never interleave
        // between its map write and its count delta.  Rare enough that the
        // exact rescan is cheaper than tracking a removal delta here.
        synchronized (this) {
            if (_ghostMarks.remove(peer) == null) {return;}
            _context.statManager().addRateData("tunnel.ghostMarksCleared", 1);
            rescanActiveLocked(_context.clock().now());
        }
    }

    /**
     * Check if a peer should be excluded from tunnel selection.
     * Expired marks are left in place (not evicted) so their strike count
     * survives toward {@code i2p.tunnel.ghostPeer.timeoutThreshold}; they
     * report {@code false} here and count as inactive in {@link #getGhostCount}.
     *
     * @param peer the router hash to test
     * @return true if the peer is a ghost and should be skipped
     */
    public boolean isGhost(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) {return false;}

        GhostMark mark = _ghostMarks.get(peer);
        return mark != null && _context.clock().now() < mark.until;
    }

    /**
     * The configured timeout threshold: consecutive strikes required
     * before a peer is excluded (values below 1 are treated as 1 by
     * {@link #recordTimeout}).
     *
     * @return threshold number of timeouts before exclusion
     */
    public int getThreshold() {
        return getTimeoutThreshold(_context);
    }

    /**
     * Clear ghost status for a peer (manual intervention).
     *
     * @param peer the router hash whose ghost mark should be removed
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
     * Count of active ghost exclusions whose peer sits in the fast or
     * high-capacity tier, a peer in both tiers counted once.
     * <p>Needed to pair with {@link ProfileOrganizer#getFastOrHighCapCount}: an
     * all-tier ghost count divided by a tier size overstates, because most marks
     * land on peers that were never in the preferred tier and would not have
     * competed for those slots.
     * <p>Iterates the tracked marks, bounded by {@link #MAX_TRACKED_PEERS} and
     * filtered to active ones, so it is not a hot-path call — the health scorer
     * polls it once per tuning cycle.
     *
     * @return active ghost exclusions within the fast or high-capacity tier
     * @since 0.9.71+
     */
    public int getFastOrHighCapGhostCount() {
        ProfileOrganizer po = _context.profileOrganizer();
        long now = _context.clock().now();
        int count = 0;
        for (Map.Entry<Hash, GhostMark> entry : _ghostMarks.entrySet()) {
            if (now >= entry.getValue().until) continue;
            Hash peer = entry.getKey();
            if (po.isFast(peer) || po.isHighCapacity(peer)) count++;
        }
        return count;
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
