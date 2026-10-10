package net.i2p.router.util;

import net.i2p.I2PAppContext;
import net.i2p.util.ConcurrentHashSet;

/**
 * Double-buffered hash set with time-based decay for efficient data management.
 * <p>
 * Extends DecayingBloomFilter to provide dual-buffered hash sets
 * with automatic decay switching between active and previous buffers.
 * Optimized for high-throughput scenarios with frequent insertions
 * and periodic cleanup operations.
 * <p>
 * Memory usage analysis shows significant optimization potential:
 * <ul>
 * <li>32 bytes, peak 10 entries in 1m (320 on fast routers)</li>
 * <li>4 bytes, peak 150 entries in 10s (1600 on fast routers)</li>
 * <li>8 bytes, peak 1K entries in 2m (36K on fast routers)</li>
 * <li>16 bytes, peak 15K entries in 10m (15K on fast routers)</li>
 * </ul>
 * <p>
 * Performance characteristics:
 * <ul>
 * <li>Zero false positive rate for ≤8 byte keys (lossy hash for larger keys)</li>
 * <li>Over 8 bytes entries are reduced to a 64 bit hash, so a false
 * positive needs a hash collision: about 5.4E-20 per pair, so
 * n^2/2^65 across n entries</li>
 * <li>About 1.93x faster than {@link DecayingBloomFilter} for 8 byte (long)
 * entries and 2.46x faster for 16 byte entries</li>
 * <li>About 72 bytes of memory per 16 byte entry once loaded (measured: 9.01 MB
 * for 131,000 entries), covering the map node, the {@code ArrayWrapper}
 * and its share of the table. At the 128K soft cap that is roughly 9 MB
 * as a floor, not a ceiling: getInsertedCount() spans both buffers, so a
 * window deferred past the cap can take this to about twice that</li>
 * <li>Space-proportional traffic handling</li>
 * </ul>
 * <p>
 * Uses read/write locks with SimpleTimer2 for thread safety
 * and efficient buffer switching without synchronization overhead.
 *
 * @author zzz
 */
public class DecayingHashSet extends DecayingBloomFilter {
    private ConcurrentHashSet<ArrayWrapper> _current;
    private ConcurrentHashSet<ArrayWrapper> _previous;

    /**
     * Maximum entries before a forced early decay is attempted. This is a
     * soft target, not a hard ceiling: memory is bounded by roughly two
     * windows of traffic rather than by this number, because forcing a decay
     * sooner than an entry's lifetime would drop live entries.
     *
     * Measured cost at this cap with 16 byte entries: about 9 MB, roughly
     * 72 bytes per entry including the map node and the wrapper.
     *
     * @since 0.9.70+
     */
    static final int DEFAULT_MAX_ENTRIES = 128 * 1024;   // package visible for tests
    /**
     * Measured retained cost per entry for 16 byte keys: about 72 bytes,
     * covering the map node, the {@code ArrayWrapper} and its share of the
     * table. Measured 9.01 MB for 131,000 entries.
     * @since 0.9.71+
     */
    public static final long BYTES_PER_ENTRY = 72;
    /**
     * The cap actually in force: {@link #DEFAULT_MAX_ENTRIES} unless a test
     * asked for a smaller one so it can cross the threshold cheaply, or the
     * router lowered it to hold memory down.
     *
     * <p>A set has no fixed memory cost, so unlike a bloom filter it can only
     * be bounded by refusing growth: reaching the cap retires the older buffer
     * even if its entries have not expired. That trades duplicate detection
     * for a memory ceiling, so it is only the right call where the memory
     * ceiling is the harder constraint. Volatile so the setter can change it
     * while the router is running.
     *
     * @since 0.9.71+
     */
    private volatile int _maxEntries;
    /**
     * True once {@link #setMaxEntries(int)} has been used, meaning a caller
     * has declared a memory ceiling to be the harder constraint. The default
     * cap stays soft: it is only a backstop, and preferring duplicate
     * detection over an early retirement is the right default.
     * @since 0.9.71+
     */
    private volatile boolean _hardCap;
    /**
     * Nanotime of the last decay, used to space decays at least one interval
     * apart. Volatile so the over-cap path can test it without taking the
     * write lock. Monotonic on purpose: a wall clock jump must not be able to
     * force an early decay, which would drop entries that are still live.
     * @since 0.9.71+
     */
    private volatile long _lastDecayNanos;
    /**
     * Nanotime of the last over-cap warning, so that a sustained flood does not
     * log once per entry. Same clock as {@link #_lastDecayNanos} on purpose, so
     * one policy decision never mixes a monotonic and a wall clock reading.
     * Volatile because the warn is reached from the unlocked pre-check in
     * forceDecayIfOverCap(); a lost update here only costs an extra log line.
     * @since 0.9.71+
     */
    private volatile long _lastOverCapWarnNanos;

    /**
     * Create a double-buffered hash set that will decay its entries over time.
     *
     * @param context the app context supplying the log and the clock
     * @param durationMs entries last for at least this long, but no more than twice this long
     * @param entryBytes how large are the entries to be added?  1 to 32 bytes
     * @throws IllegalArgumentException if entryBytes is not 1-32
     */
    public DecayingHashSet(I2PAppContext context, int durationMs, int entryBytes) {
        this(context, durationMs, entryBytes, "DHS");
    }

    /**
     * Decaying hash set with a custom name.
     *
     * @param context the app context supplying the log and the clock
     * @param durationMs entries last for at least this long, but no more than twice this long
     * @param entryBytes how large are the entries to be added?  1 to 32 bytes
     * @param name the instance name, used only in log messages and stats
     * @throws IllegalArgumentException if entryBytes is not 1-32
     */
    public DecayingHashSet(I2PAppContext context, int durationMs, int entryBytes, String name) {
        this(context, durationMs, entryBytes, name, DEFAULT_MAX_ENTRIES);
    }

    /**
     * As above, with an explicit cap. Only for tests, which need to cross the
     * over-cap path without inserting the full {@link #DEFAULT_MAX_ENTRIES} entries.
     *
     * @param context the app context supplying the log and the clock
     * @param durationMs entries last for at least this long, but no more than twice this long
     * @param entryBytes how large are the entries to be added?  1 to 32 bytes
     * @param name the instance name, used only in log messages and stats
     * @param maxEntries hard cap on entries across both buffers
     * @since 0.9.71+
     */
    DecayingHashSet(I2PAppContext context, int durationMs, int entryBytes, String name, int maxEntries) {
        super(durationMs, entryBytes, name, context);
        // entries are hashed directly, with no 32 byte stretching, so the full
        // 1-32 range is usable here even though DecayingBloomFilter needs more
        if (entryBytes <= 0 || entryBytes > 32)
            throw new IllegalArgumentException("Bad size [" + entryBytes + "], must be 1-32");
        if (maxEntries <= 0)
            throw new IllegalArgumentException("Bad cap [" + maxEntries + "]");
        _maxEntries = maxEntries;
        _current = new ConcurrentHashSet<>(128);
        _previous = new ConcurrentHashSet<>(128);
        // no decay may run before one interval has passed
        _lastDecayNanos = System.nanoTime();
        if (_log.shouldDebug())
            _log.debug("New DHS " + name + " entryBytes = " + entryBytes +
                     " cycle (s) = " + (durationMs / 1000));
    }

    /**
     * Unsynchronized. Read on the add() path by forceDecayIfOverCap(), as well
     * as for logging, so it is a hot call.
     */
    @Override
    public int getInsertedCount() {
        return _current.size() + _previous.size();
    }

    /**
     * The entry cap currently in force, across both buffers.
     *
     * @return the cap
     * @since 0.9.71+
     */
    public int getMaxEntries() { return _maxEntries; }

    /**
     * Change the entry cap while running, for the router's low-memory path.
     *
     * <p>Lowering it takes effect at once: the next add() over the new cap
     * triggers a decay, which may retire entries that have not yet expired.
     * Raising it does not resurrect anything already retired.
     *
     * @param maxEntries the new cap, must be positive
     * @throws IllegalArgumentException if maxEntries is not positive
     * @since 0.9.71+
     */
    public void setMaxEntries(int maxEntries) {
        if (maxEntries <= 0)
            throw new IllegalArgumentException("Bad cap [" + maxEntries + "]");
        _maxEntries = maxEntries;
        _hardCap = true;
        if (_log.shouldDebug())
            _log.debug("Set " + _name + " maxEntries to " + maxEntries + " (enforced immediately)");
    }

    /**
     * Bytes of memory the current contents occupy, at the measured rate of
     * about 72 bytes per entry for 16 byte keys. Reported so a caller sizing
     * against a memory budget can see the cost it is actually paying.
     *
     * @return estimated retained bytes
     * @since 0.9.71+
     */
    public long getEstimatedMemoryBytes() { return (long) getInsertedCount() * BYTES_PER_ENTRY; }

    /** Stubbed rate; only used for logging elsewhere. */
    @Override
    public double getFalsePositiveRate() {
        if (_entryBytes <= 8)
            return 0d;
        // per-pair hash collision probability; only used for logging
        return 1d / Math.pow(2d, 64d);  // 5.4E-20
    }

    /**
     * Add the entry, returning whether it was a duplicate.
     *
     * @return true if the entry added is a duplicate
     */
    @Override
    public boolean add(byte[] entry, int off, int len) {
        if (entry == null)
            throw new IllegalArgumentException("Null entry");
        if (len != _entryBytes)
            throw new IllegalArgumentException("Bad entry [" + len + ", expected "
                                               + _entryBytes + "]");
        forceDecayIfOverCap();
        ArrayWrapper w = new ArrayWrapper(entry, off, len);
        getReadLock();
        try {
            return locked_add(w, true);
        } finally { releaseReadLock(); }
    }

    /**
     * Add the long entry, returning whether it was a duplicate.
     *
     * @return true if the entry added is a duplicate.  the number of low order
     * bits used is determined by the entryBytes parameter used on creation of the
     * filter.
     */
    @Override
    public boolean add(long entry) {
        return add(entry, true);
    }

    /**
     * Check whether the entry is already known, without adding it.
     *
     * @return true if the entry is already known.  this does NOT add the
     * entry however.
     */
    @Override
    public boolean isKnown(long entry) {
        return add(entry, false);
    }

    private boolean add(long entry, boolean addIfNew) {
        forceDecayIfOverCap();
        ArrayWrapper w = new ArrayWrapper(entry);
        getReadLock();
        try {
            return locked_add(w, addIfNew);
        } finally { releaseReadLock(); }
    }

    /**
     * Force a decay cycle if the entry count exceeds the soft cap.
     *
     * A decay must never run more often than the decay interval: the swap in
     * {@link #decay()} drops the older buffer, and doing that before that
     * buffer has aged out would forget entries that are still live, turning
     * this duplicate/replay filter into a source of false negatives. So when
     * the cap is exceeded we decay only if at least one interval has passed
     * since the last decay, and otherwise leave it to the scheduled event.
     *
     * Best-effort: races are harmless (an extra decay, or a one-entry
     * overshoot). Under sustained overload this holds up to about two
     * windows of entries rather than the cap.
     * @since 0.9.70+
     */
    private void forceDecayIfOverCap() {
        if (getInsertedCount() < _maxEntries)
            return;
        // Cheap unlocked pre-check first: once over the cap this branch is
        // taken on every add, and taking the exclusive write lock only to
        // decide not to act would serialise all of them. The scheduled decay
        // covers the gap, so a stale read here is harmless.
        //
        // An explicitly lowered cap is enforced straight away, because a cap
        // that waits for the next scheduled decay provides no bound for up to a
        // whole interval, which is exactly the window it exists to close. The
        // default cap is not, so the ordinary path still never retires an
        // entry that has not expired.
        if (!_hardCap && System.nanoTime() - _lastDecayNanos < _durationMs * 1000000L) {
            maybeWarnOverCap();
            return;
        }
        if (!getWriteLock())
            return;
        DecayCounters counters = null;
        try {
            if (getInsertedCount() < _maxEntries)
                return;
            if (!_hardCap && System.nanoTime() - _lastDecayNanos < _durationMs * 1000000L)
                return;
            counters = swapBuffers();
        } finally {
            releaseWriteLock();
        }
        // log outside the lock, so a slow logger cannot stall other adds
        if (counters != null)
            logDecay(counters);
    }

    /** Rate limited to once per decay interval, so a flood cannot spam the log. */
    private void maybeWarnOverCap() {
        if (!_log.shouldWarn())
            return;
        // same monotonic clock as the deferral test above, so a wall clock
        // adjustment cannot suppress or burst the warning
        long now = System.nanoTime();
        if (now - _lastOverCapWarnNanos < _durationMs * 1000000L)
            return;
        _lastOverCapWarnNanos = now;
        _log.warn("DecayingHashSet over the soft cap (" + getInsertedCount()
                  + " > " + _maxEntries + ") less than " + (_durationMs / 1000)
                  + "s after the last decay, deferring; memory now tracks traffic,"
                  + " not the cap");
    }

    /**
     * Add the entry to the current set if new, or check membership only.
     *
     * @param addIfNew if true, add the element to current if it is not already there or in previous;
     * if false, only check
     *
     * @return if the element is in either the current or previous set
     */
    private boolean locked_add(ArrayWrapper w, boolean addIfNew) {
        boolean seen = _previous.contains(w);
        // only access _current once.
        if (!seen) {
            if (addIfNew)
                seen = !_current.add(w);
            else
                seen = _current.contains(w);
        }
        if (seen) {
            // count only real duplicate insertions, not read-only isKnown() probes
            if (addIfNew)
                _currentDuplicates.incrementAndGet();
        }
        return seen;
    }

    @Override
    public void clear() {
        if (!getWriteLock())
            return;
        try {
            _current.clear();
            _previous.clear();
            _currentDuplicates.set(0);
        } finally { releaseWriteLock(); }
    }

    /** Super doesn't call clear, but neither do the users, so it seems like we should here. */
    @Override
    public void stopDecaying() {
        _keepDecaying = false;
        clear();
    }

    @Override
    protected void decay() {
        if (!getWriteLock())
            return;
        DecayCounters counters = null;
        try {
            counters = swapBuffers();
        } finally { releaseWriteLock(); }
        if (counters != null)
            logDecay(counters);
    }

    /**
     * Retire the older buffer and clear the recycled one. Caller must hold
     * the write lock. Does no logging, so the lock is held only for the swap.
     *
     * @return what was in the retiring buffer, for logging once unlocked
     */
    private DecayCounters swapBuffers() {
        int currentCount = _current.size();
        long dups = _currentDuplicates.getAndSet(0);
        ConcurrentHashSet<ArrayWrapper> tmp = _previous;
        _previous = _current;
        _current = tmp;
        _current.clear();
        _lastDecayNanos = System.nanoTime();
        return new DecayCounters(currentCount, dups);
    }

    /** Caller must not hold the write lock. */
    private void logDecay(DecayCounters counters) {
        if (_log.shouldDebug())
            _log.debug("Decaying the " + _name + " filter after inserting "
                       + counters.count + " elements and " + counters.dups
                       + " duplicates");
    }

    /**
     * What a decay found, carried out of the write lock so the caller can log
     * it without extending the exclusive section.
     * @since 0.9.71+
     */
    private static final class DecayCounters {
        private final int count;
        private final long dups;

        DecayCounters(int count, long dups) {
            this.count = count;
            this.dups = dups;
        }
    }

    /**
     * This saves the data as-is if the length is &lt;= 8 bytes,
     * otherwise it stores an 8-byte hash.
     * Hash function is from DataHelper, modded to get
     * the maximum entropy given the length of the data.
     */
    private static class ArrayWrapper {
        private final long _longhashcode;

        public ArrayWrapper(byte[] b, int offset, int len) {
            int idx = offset;
            int shift = Math.min(8, 64 / len);
            long lhc = 0;
            for (int i = 0; i < len; i++) {
                // xor better than + in tests
                lhc ^= (((long) b[idx++]) << (i * shift));
            }
            _longhashcode = lhc;
        }

        /** Faster version for when storing <= 8 bytes. */
        public ArrayWrapper(long b) {
            _longhashcode = b;
        }

        public int hashCode() {
             return (int) _longhashcode;
        }

        public long longHashCode() {
             return _longhashcode;
        }

        public boolean equals(Object o) {
             if (!(o instanceof ArrayWrapper))
                 return false;
             return ((ArrayWrapper) o).longHashCode() == _longhashcode;
        }
    }
}
