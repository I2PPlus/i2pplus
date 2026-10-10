package net.i2p.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collapses repeated occurrences of the same log message.
 *
 * <p>Some conditions repeat thousands of times a minute - a peer resetting a connection,
 * a tunnel failing to build - and each occurrence is individually uninteresting while
 * the volume hides the messages that matter. At a few thousand lines a minute the log
 * also stops being readable during an incident and starts costing real disk through
 * rotation.
 *
 * <p>A caller passes a stable {@code key} identifying the condition and logs through
 * {@link Log#warnThrottled} and friends. The first occurrence is always written; after
 * that, an occurrence is written when either enough repeats have accumulated
 * ({@link #DEFAULT_BURST}) or enough time has passed ({@link #DEFAULT_WINDOW_MS}),
 * whichever comes first, and the line states how many were collapsed. A condition that
 * fires once an hour is never hidden; one that fires two thousand times a minute is
 * reduced to a couple of lines a minute that still say how big it was.
 *
 * <p>Keys are scoped to the {@link Log} instance, which {@code LogManager} caches per
 * class, so state is effectively per logging class and keys cannot collide across
 * classes. The key table is bounded: entries whose window has long expired are pruned
 * when it grows past {@link #MAX_KEYS}, so a caller that keys on something unique per
 * occurrence - a peer hash, say - cannot leak.
 *
 * <p>Instances are thread-safe.
 *
 * @since 0.9.71+
 */
public class LogSuppressor {

    /** Repeats to accumulate before writing again, unless the window expires first. */
    public static final int DEFAULT_BURST = 1000;

    /** Longest a repeat can stay hidden once the first occurrence has been written. */
    public static final long DEFAULT_WINDOW_MS = 60 * 1000;

    /**
     * Upper bound on tracked keys. Pruned down to the live window past this size, so a
     * caller keying per-occurrence cannot grow the table without bound.
     */
    static final int MAX_KEYS = 4096;

    /** What the caller should do with one occurrence. */
    public static final class Decision {
        /** True if this occurrence should be written. */
        public final boolean log;
        /** Occurrences collapsed since the previous write; zero when none. */
        public final long suppressed;

        /**
         * Record what the caller should do with one occurrence.
         *
         * @param log true if this occurrence should be written
         * @param suppressed how many repeats this write stands in for, 0 for the first
         */
        Decision(boolean log, long suppressed) {
            this.log = log;
            this.suppressed = suppressed;
        }
    }

    /** Per-key bookkeeping. Replaced wholesale so readers never see a torn value. */
    private static final class State {
        /** Occurrence count when this key was last written. */
        final long lastReportCount;
        /** Wall-clock ms when this key was last written. */
        final long lastReportMs;

        State(long lastReportCount, long lastReportMs) {
            this.lastReportCount = lastReportCount;
            this.lastReportMs = lastReportMs;
        }
    }

    private final ConcurrentHashMap<String, AtomicLong> _counts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, State> _reported = new ConcurrentHashMap<>();
    private final int _burst;
    private final long _windowMs;

    /**
     * Create a suppressor with the default burst and window.
     *
     * A suppressor allows {@code _burst} messages through before suppressing for
     * {@code _windowMs}, so a repeated error is logged without flooding the log.
     */
    public LogSuppressor() {
        this(DEFAULT_BURST, DEFAULT_WINDOW_MS);
    }

    /**
     * Construct a suppressor with an explicit burst size and window.
     *
     * @param burst repeats to accumulate before writing again, forced to at least 1
     * @param windowMs longest a repeat may stay hidden, in ms, forced to at least 1
     */
    public LogSuppressor(int burst, long windowMs) {
        _burst = Math.max(1, burst);
        _windowMs = Math.max(1, windowMs);
    }

    /**
     * Decide whether one occurrence of {@code key} should be written.
     *
     * <p>Pure, so the policy can be pinned by test without a clock or a logger. The
     * first occurrence of a key always reports; afterwards a report is due once the
     * repeats since the last one reach {@code burst}, or once {@code windowMs} have
     * passed - time as well as count, so a slow trickle stays visible.
     *
     * @param occurrences total occurrences of this key including this one
     * @param lastReportCount occurrence count at the previous report, zero if never
     * @param lastReportMs wall-clock ms of the previous report, zero if never
     * @param burst repeats to accumulate before reporting again
     * @param windowMs longest a repeat may stay hidden
     * @param nowMs current wall-clock ms
     * @return true if this occurrence should be written
     * @since 0.9.71+
     */
    static boolean isDue(long occurrences, long lastReportCount, long lastReportMs,
                         int burst, long windowMs, long nowMs) {
        if (lastReportCount <= 0) {
            return true;
        }
        if (occurrences - lastReportCount >= burst) {
            return true;
        }
        return nowMs - lastReportMs >= windowMs;
    }

    /**
     * Record one occurrence of {@code key} and say whether to write it.
     *
     * @param key stable identifier for the condition being repeated
     * @return whether to write, and how many occurrences were collapsed since the last write
     */
    public Decision record(String key) {
        return record(key, System.currentTimeMillis());
    }

    /**
     * Record one occurrence at an explicit time.
     *
     * @param key stable identifier for the condition being repeated
     * @param nowMs current wall-clock ms
     * @return whether to write, and how many occurrences were collapsed since the last write
     */
    public Decision record(String key, long nowMs) {
        long occurrences = _counts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        State prev = _reported.get(key);
        long lastCount = (prev != null ? prev.lastReportCount : 0L);
        long lastMs = (prev != null ? prev.lastReportMs : 0L);
        prune(nowMs);
        if (!isDue(occurrences, lastCount, lastMs, _burst, _windowMs, nowMs)) {
            return new Decision(false, 0L);
        }
        _reported.put(key, new State(occurrences, nowMs));
        long collapsed = occurrences - lastCount - 1;
        return new Decision(true, collapsed > 0 ? collapsed : 0L);
    }

    /**
     * Drop bookkeeping for keys that can no longer report.
     *
     * <p>Called on every recorded occurrence once the table is over its cap, so a caller
     * that keys per-occurrence settles at the cap instead of growing forever. Cheap
     * because it only runs when the table is already large.
     *
     * @param nowMs current wall-clock ms
     */
    private void prune(long nowMs) {
        if (_counts.size() < MAX_KEYS) {
            return;
        }
        for (Map.Entry<String, State> e : _reported.entrySet()) {
            if (nowMs - e.getValue().lastReportMs > _windowMs) {
                _reported.remove(e.getKey());
                _counts.remove(e.getKey());
            }
        }
        // Nothing was old enough, so every key is live and the cap is genuinely
        // reachable. Forget the oldest half rather than refusing new work: a suppressed
        // key that is evicted simply starts reporting again.
        if (_counts.size() >= MAX_KEYS) {
            int drop = _counts.size() / 2;
            for (String k : _counts.keySet()) {
                if (drop-- <= 0) {
                    break;
                }
                _counts.remove(k);
                _reported.remove(k);
            }
        }
    }

    /**
     * Forget all bookkeeping, so every key starts counting from scratch again.
     *
     * @since 0.9.71+
     */
    public void reset() {
        _counts.clear();
        _reported.clear();
    }

    /**
     * How many distinct keys are being tracked.
     *
     * @return current key count
     * @since 0.9.71+
     */
    public int size() {
        return _counts.size();
    }
}
