package net.i2p.i2ptunnel.access;

/*
 * Counts connection attempts and detects threshold breaches.
 *
 * @since 0.9.40
 */
/**
 * Access attempt counter.
 * <p>
 * Retains the attempt timestamps in a fixed circular buffer sized from the rule
 * that owns the counter, so a flooded destination cannot grow the history without
 * limit and both {@link #recordAccess(long)} and {@link #isBreached(Threshold, long)}
 * stay constant time. The old implementation kept an {@code ArrayList} of every
 * attempt and purged it with {@code remove(0)}, which was O(n) per purge and
 * unbounded between purges - {@link AccessFilter#MAX_STORED_DESTS} caps how many
 * destinations are tracked, not how long one destination's history grows.
 * <p>
 * Not thread-safe; {@link DestTracker} synchronizes every path that reaches this
 * class.
 */
class AccessCounter {

    /**
     * Fewest timestamps retained, so a rule with a small count still keeps some
     * history between purges.
     */
    private static final int MIN_RETAINED = 64;

    /**
     * Most timestamps retained, so a rule written with an absurd count, or with
     * {@link Threshold#ALLOW}, cannot allocate a buffer per destination. A rule
     * that wants more than this can never be met by the history kept, and
     * {@link FilterDefinition} says so once when the definition is read.
     */
    static final int MAX_RETAINED = 16384;

    /**
     * Retained attempt timestamps, oldest first at {@link #_oldest}, in the order
     * they were recorded.
     */
    private final long[] _ring;

    /**
     * How many timestamps are retained, never more than {@link #_ring}.length.
     */
    private int _size;

    /**
     * Index in {@link #_ring} of the oldest retained timestamp.
     */
    private int _oldest;

    /**
     * Create a counter with the default retention.
     */
    AccessCounter() {
        this(MIN_RETAINED);
    }

    /**
     * Create a counter that retains enough history for the rule that owns it and
     * for the recorder that reads the same attempts.
     *
     * @param threshold the rule tested against the recorded attempts
     * @param recorderConnections the largest count any recorder of the
     *                            same definition tests against these
     *                            attempts, 0 if it has none
     */
    AccessCounter(Threshold threshold, int recorderConnections) {
        this(retainedFor(threshold, recorderConnections));
    }

    /**
     * How many timestamps a counter for this rule must retain.
     * <p>
     * A rule of N attempts per window only ever needs the N newest attempts, so the
     * retention is the larger of the admission rule's count and the largest recorder
     * count reading the same attempts, floored at {@link #MIN_RETAINED} and capped at
     * {@link #MAX_RETAINED}. The recorder half is not optional: the filter tests the
     * recorded attempts against each recorder's own rule as well, so a counter sized
     * from the admission rule alone cannot hold the attempts a wider recorder rule
     * asks for, and that recorder writes nothing, forever.
     * <p>
     * A rule whose count exceeds the cap can never be met by the retained history
     * and is therefore never breached - the same answer {@link Threshold#ALLOW}
     * gives, and the only way to bound the memory a hand-written definition file can
     * ask for; {@link FilterDefinition} logs the case once when the definition is
     * read. A rule of zero attempts ({@link Threshold#DENY}) only needs to know
     * whether anything was recorded at all, so it retains one timestamp, which is
     * what keeps a deny list of thousands of destinations cheap.
     *
     * @param threshold the rule to size the retention for
     * @param recorderConnections the largest count any recorder of the
     *                            same definition tests, 0 if there are none
     * @return the number of timestamps to retain, at least 1
     * @since 0.9.72+
     */
    static int retainedFor(Threshold threshold, int recorderConnections) {
        int count = Math.max(threshold.getConnections(), recorderConnections);
        if (count <= 0)
            return 1;
        return Math.min(Math.max(count, MIN_RETAINED), MAX_RETAINED);
    }

    /**
     * Create a counter with an explicit retention.
     *
     * @param capacity how many timestamps to keep, positive
     */
    private AccessCounter(int capacity) {
        _ring = new long[capacity];
    }

    /**
     * Records that a connection attempt was made.
     * <p>
     * Timestamps are expected in non-decreasing order, as they come from
     * {@link net.i2p.util.Clock#now()}. Once the retention is full the oldest
     * attempt is dropped, which cannot turn a breach into a non-breach: the
     * breach test only ever looks at the newest attempts.
     *
     * @param now the current time in milliseconds, as returned by
     * {@link net.i2p.util.Clock#now()}, stored as this access's timestamp
     */
    void recordAccess(long now) {
        if (_size < _ring.length) {
            _ring[(_oldest + _size) % _ring.length] = now;
            _size++;
        } else {
            _ring[_oldest] = now;
            _oldest = (_oldest + 1) % _ring.length;
        }
    }

    /**
     * Check if the given threshold has been breached.
     * <p>
     * A rule of N attempts per window is breached exactly when at least N
     * attempts fall inside the window ending at {@code now}, so it is enough to
     * look at the N-th newest retained attempt. Constant time, and equivalent to
     * the scan this replaces for any rule the retention can hold.
     *
     * @param threshold definition of a threshold
     * @param now the current time in milliseconds, as returned by
     * {@link net.i2p.util.Clock#now()}, used to age recorded accesses
     * against the threshold's window
     * @return true if the given threshold has been breached
     */
    boolean isBreached(Threshold threshold, long now) {
        int count = threshold.getConnections();
        if (count == 0)
            return _size > 0;
        if (_size < count)
            return false;
        long window = (long) threshold.getSeconds() * 1000L;
        return at(_size - count) >= now - window;
    }

    /**
     * Purges old accesses from the list.
     *
     * @param olderThan remove all accesses older than the given timestamp
     * @return true if there is nothing left in the access history
     */
    boolean purge(long olderThan) {
        while (_size > 0 && at(0) < olderThan) {
            _ring[_oldest] = 0L;
            _oldest = (_oldest + 1) % _ring.length;
            _size--;
        }
        return _size == 0;
    }

    /**
     * How many attempts are currently retained.
     *
     * @return the retained attempt count, at most the counter's capacity
     * @since 0.9.72+
     */
    int retained() {
        return _size;
    }

    /**
     * Read one retained attempt.
     *
     * @param index how far from the oldest retained attempt, 0 based
     * @return the timestamp recorded at that position
     */
    private long at(int index) {
        return _ring[(_oldest + index) % _ring.length];
    }
}
