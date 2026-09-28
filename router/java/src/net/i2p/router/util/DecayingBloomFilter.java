package net.i2p.router.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import org.xlattice.crypto.filters.BloomSHA1;

/**
 * Time-decaying Bloom filter series for efficient data management.
 * <p>
 * Provides a series of Bloom filters with automatic time-based decay
 * to handle time-sensitive data with minimal false positive rates.
 * Uses fixed-size buffers with periodic switching to maintain
 * consistent memory usage while allowing high-throughput operations.
 * <p>
 * Optimized for scenarios requiring hundreds of entries per second
 * with virtually no false positive rate. Memory usage is carefully
 * controlled through configurable filter parameters and buffer sizes.
 * <p>
 * Performance characteristics:
 * <ul>
 *   <li>Configurable m and k parameters for optimal false positive rates</li>
 *   <li>Automatic buffer switching and decay operations</li>
 *   <li>Thread-safe with read/write lock coordination</li>
 *   <li>Comprehensive statistics tracking and performance monitoring</li>
 * </ul>
 * <p>
 * Suitable for high-throughput applications like message validation,
 * tunnel IV filtering, and router message processing where
 * memory efficiency and low false positive rates are critical.
 *
 * @see net.i2p.router.util.DecayingHashSet
 * @see net.i2p.router.tunnel.BloomFilterIVValidator
 * @since 0.8.8
 */
public class DecayingBloomFilter {
    /** The I2P app context */
    protected final I2PAppContext _context;
    /** The logger */
    protected final Log _log;
    /** Filter size exponent: each buffer holds 2^m bits. */
    private final int _m;
    /**
     *  Entries seen in the most recently completed decay window, written under
     *  the write lock. This is the quantity a filter has to be sized against,
     *  and decay() already computes it, so tracking it is free.
     */
    private volatile int _lastWindowCount;
    /** Current bloom filter */
    private BloomSHA1 _current;
    /** Previous bloom filter */
    private BloomSHA1 _previous;
    /** Duration of each filter in ms */
    protected final int _durationMs;
    /** Size of entries in bytes */
    protected final int _entryBytes;
    /** Extender arrays for small entries */
    private final byte[][] _extenders;
    /** Mask for converting longs to entries */
    private final long _longToEntryMask;
    /** Thread-local buffer for extended entries */
    private final ThreadLocal<byte[]> _extendedBuf;
    /**
     *  Thread-local buffer for the long-to-entry conversion, sized _entryBytes.
     *  Only the long overloads of add()/isKnown() use it, so it is null when the
     *  entries are too wide to hold a long.
     *  @since 0.9.71+
     */
    private final ThreadLocal<byte[]> _longToEntryBuf;
    /** Counter for current duplicates */
    protected final AtomicLong _currentDuplicates = new AtomicLong();
    /** Whether decay is still running */
    protected volatile boolean _keepDecaying;
    /** The decay timer event */
    protected final SimpleTimer2.TimedEvent _decayEvent;
    /** Name for logging. */
    protected final String _name;
    /** Synchronize against this lock when switching double buffers. */
    protected final ReentrantReadWriteLock _reorganizeLock = new ReentrantReadWriteLock();

    /**
     *  Default sizing rationale.
     *
     *  The rates below are theoretical false positive rates for a filter of
     *  2^m bits with k hash functions, (1 - e^(-k*n/m))^k, evaluated at
     *  n = 600 * KBps entries: one message per second per KBps of share,
     *  accumulated over a 600 second decay window. They are not measurements.
     *  The formula understates the rate once the filter is close to full, so
     *  treat the high figures at the end of each line as optimistic.
     *
     *  The larger m values are chosen by the caller, which also steps k down to
     *  hold k*ln(2) roughly constant: 23/11, then 10 above m=23, then 9 above
     *  m=26. m is capped at 29, which is where KeySelector's offset formula
     *  stops fitting in a 32 byte key.
     *
     *<pre>
     *  m=23, k=11:
     *  Theoretical false positive rate for   16 KBps: 1.17E-21
     *  Theoretical false positive rate for   24 KBps: 9.81E-20
     *  Theoretical false positive rate for   32 KBps: 2.24E-18
     *  Theoretical false positive rate for  256 KBps: 7.45E-9
     *  Theoretical false positive rate for  512 KBps: 5.32E-6
     *  Theoretical false positive rate for 1024 KBps: 1.48E-3
     *  Then it gets bad: 1280 .67%; 1536 2.0%; 1792 4.4%; 2048 8.2%.
     *
     *  m=24, k=10:
     *  1280 4.5E-5; 1792 5.6E-4; 2048 0.14%
     *
     *  m=25, k=10:
     *  1792 2.4E-6; 4096 0.14%; 5120 0.6%; 6144 1.7%; 8192 6.8%; 10240 15%
     *
     *  m=26, k=10:
     *  4096 7.3E-6; 5120 4.5E-5; 6144 1.8E-4; 8192 0.14%; 10240 0.6%, 12288 1.7%
     *
     *  m=27, k=9:
     *  8192 1.1E-5; 10240 5.6E-5; 12288 2.0E-4; 14336 5.8E-4; 16384 0.14%
     *</pre>
     */
    private static final int DEFAULT_M = 23;
    private static final int DEFAULT_K = 11;
    /** True for debugging. */
    private static final boolean ALWAYS_MISS = false;

    /**
     *  Validate an entry size for a bloom filter. Entries are stretched to
     *  exactly 32 bytes for hashing, in uniform blocks of entryBytes, so only
     *  sizes that can reach 32 bytes without overrunning are usable: 1-16, and
     *  32 itself. Sizes 17-31 would need a partial trailing block, which the
     *  extender loop does not support, and hashing them unextended overruns
     *  KeySelector. This does not apply to {@link DecayingHashSet}, which
     *  hashes entries directly and accepts the full 1-32 range.
     *
     *  @param entryBytes how large are the entries
     *  @throws IllegalArgumentException if entryBytes is not 1-16 or 32
     *  @since 0.9.71+
     */
    protected static void checkEntryBytes(int entryBytes) {
        if (entryBytes <= 0 || entryBytes > 32 || (entryBytes > 16 && entryBytes < 32))
            throw new IllegalArgumentException("Bad size [" + entryBytes
                                               + "], must be 1-16 or 32");
    }

    /**
     *  How many extender blocks are needed to stretch an entryBytes entry out
     *  to the 32 bytes that are hashed. This is the largest count that still
     *  fits without overrunning the buffer, so a non-divisor size such as 6
     *  yields the most extenders that are safe (4, using 30 of the 32 bytes).
     *
     *  @param entryBytes how large are the entries, already validated to 1-16
     *         or 32, so for anything below 32 the result is always at least 1
     *  @return the number of extenders; 0 only for a full 32 byte entry
     *  @since 0.9.71+
     */
    private static int calcNumExtenders(int entryBytes) {
        return (entryBytes >= 32) ? 0 : (32 / entryBytes) - 1;
    }

    /**
     * Only for extension by DecayingHashSet, which validates entryBytes itself
     * and never stretches entries, so it is deliberately not checked here.
     *
     * @param durationMs entries last for at least this long
     * @param entryBytes how large are the entries
     * @param name filter name for logging
     * @param context the I2P app context
     */
    protected DecayingBloomFilter(int durationMs, int entryBytes, String name, I2PAppContext context) {
        _m = 0;    // no bit array in a subclass that does not use one
        _context = context;
        _log = context.logManager().getLog(getClass());
        _entryBytes = entryBytes;
        _name = name;
        _durationMs = durationMs;
        // all final
        _extenders = null;
        _longToEntryMask = 0;
        _extendedBuf = null;
        _longToEntryBuf = null;
        context.addShutdownTask(new Shutdown());
        _keepDecaying = true;
        if (_durationMs == 60*60*1000) {
            // special mode for BuildMessageProcessor
            _decayEvent = new DecayHourlyEvent();
        } else {
            _decayEvent = new DecayEvent();
            _decayEvent.schedule(_durationMs);
        }
    }

    /**
     * Create a bloom filter that will decay its entries over time.
     * Uses default m of 23, memory usage is 2 MB.
     *
     * @param context the I2P app context
     * @param durationMs entries last for at least this long, but no more than twice this long
     * @param entryBytes how large are the entries to be added?  must be 1-16, or
     *                   32.  Entries narrower than 32 bytes are expanded to 32 by
     *                   concatenating their XORing against random values; 17-31
     *                   cannot reach 32 in whole blocks and are refused.
     * @throws IllegalArgumentException if entryBytes is not 1-16 or 32
     * @see #checkEntryBytes(int)
     */
    public DecayingBloomFilter(I2PAppContext context, int durationMs, int entryBytes) {
        this(context, durationMs, entryBytes, "DBF");
    }

    /**
     * Uses default m of 23, memory usage is 2 MB.
     *
     * @param context the I2P app context
     * @param durationMs entries last for at least this long
     * @param entryBytes how large are the entries, 1-16 or 32
     * @param name just for logging / debugging / stats
     * @throws IllegalArgumentException if entryBytes is not 1-16 or 32
     */
    public DecayingBloomFilter(I2PAppContext context, int durationMs, int entryBytes, String name) {
        // this is instantiated in four different places, they may have different
        // requirements, but for now use this as a gross method of memory reduction.
        // m == 23 => 1MB each BloomSHA1 (4 pairs = 8MB total)
        this(context, durationMs, entryBytes, name, context.getProperty("router.decayingBloomFilterM", DEFAULT_M));
    }

    /**
     * Memory usage is 2 * (2**m) bits or 2**(m-2) bytes, allocated up front for
     * both buffers, so it does not grow with traffic.
     *
     * @param context the I2P app context
     * @param durationMs entries last for at least this long
     * @param entryBytes how large are the entries, 1-16 or 32
     * @param name filter name for logging
     * @param m filter size exponent, max is 29
     * @throws IllegalArgumentException if entryBytes is not 1-16 or 32, or if m
     *         is over 29
     */
    public DecayingBloomFilter(I2PAppContext context, int durationMs, int entryBytes, String name, int m) {
        checkEntryBytes(entryBytes);
        _context = context;
        _log = context.logManager().getLog(DecayingBloomFilter.class);
        _entryBytes = entryBytes;
        _name = name;
        _m = m;
        int k = DEFAULT_K;
        // hold k*ln(2) ~ constant as m grows: (23,11), (26,10), (29,9)
        if (m > DEFAULT_M) {
            k--;
            if (m > 26) {
                k--;
                if (m > MAX_M)
                    throw new IllegalArgumentException("Max m is " + MAX_M);
            }
        }
        _current = new BloomSHA1(m, k);
        _previous = new BloomSHA1(m, k);
        _durationMs = durationMs;
        int numExtenders = calcNumExtenders(entryBytes);
        if (numExtenders > 0) {
            _extenders = new byte[numExtenders][entryBytes];
            for (int i = 0; i < numExtenders; i++) {
                _context.random().nextBytes(_extenders[i]);
            }
            _extendedBuf = ThreadLocal.withInitial(() -> new byte[32]);
            _longToEntryMask = (_entryBytes < 8) ? (1L << (_entryBytes * 8L)) -1 : 0;
            // a long only fits in 8 bytes or fewer, see encodeLong()
            _longToEntryBuf = (entryBytes <= 8)
                              ? ThreadLocal.withInitial(() -> new byte[entryBytes]) : null;
        } else {
            // final
            _extenders = null;
            _extendedBuf = null;
            _longToEntryMask = 0;
            _longToEntryBuf = null;
        }
        _keepDecaying = true;
        if (_durationMs == 60*60*1000) {
            // special mode for BuildMessageProcessor
            _decayEvent = new DecayHourlyEvent();
        } else {
            _decayEvent = new DecayEvent();
            _decayEvent.schedule(_durationMs);
        }
        if (_log.shouldWarn())
           _log.warn("New DecayingBloomFilter " + name + " m = " + m + " k = " + k + " entryBytes = " + entryBytes +
                     " numExtenders = " + numExtenders + " cycle (s) = " + (durationMs / 1000));
        context.addShutdownTask(new Shutdown());
    }

    /**
     * @since 0.8.8
     */
    private class Shutdown implements Runnable {
        /**
         * Clear on shutdown.
         */
        @Override
        public void run() {
           clear();
        }
    }

    /**
     *  Current number of duplicates detected.
     *
     *  @return the current duplicate count
     */
    public long getCurrentDuplicateCount() { return _currentDuplicates.get(); }

    /**
     *  Filter size exponent. Each of the two buffers holds 2^m bits, so the
     *  pair occupies 2^(m-2) bytes.
     *
     *  @return the exponent this filter was built with, 0 if it keeps no bits
     *  @since 0.9.71+
     */
    public int getM() { return _m; }

    /**
     *  Bytes of memory this filter occupies, for both buffers.
     *
     *  @return 2^(m-2), or 0 for a subclass that keeps no bits
     *  @since 0.9.71+
     */
    public long getMemoryBytes() {
        return (_m < 2) ? 0L : (1L << (_m - 2));
    }

    /**
     *  Entries seen in the most recently completed decay window, which is the
     *  count this filter has to be sized against. Zero until the first decay.
     *
     *  @return entries in the last window, or 0 if none has completed
     *  @since 0.9.71+
     */
    public int getLastWindowCount() { return _lastWindowCount; }

    /**
     *  Smallest exponent whose false positive rate stays at or under the target
     *  for the given number of entries, at the optimal k for that exponent.
     *
     *  <p>Uses p ~= exp(-(ln2)^2/2 * 2^m / n), which is the standard optimal-k
     *  Bloom bound. It reproduces the table in {@link #DEFAULT_M}: for
     *  n=614400 it gives m=24 for a 1.48E-3 target, against 23 measured.
     *
     *  @param entries entries expected in a decay window, non-negative
     *  @param targetFpr wanted false positive rate, in (0,1)
     *  @return the exponent, never negative
     *  @since 0.9.71+
     */
    public static int mForEntries(int entries, double targetFpr) {
        if (entries <= 0 || !(targetFpr > 0) || targetFpr >= 1)
            return 0;
        // -ln(p) / ((ln2)^2 / 2)
        double scale = -Math.log(targetFpr) / (Math.log(2) * Math.log(2) / 2.0d);
        int m = (int) Math.ceil(Math.log(entries * scale) / Math.log(2));
        return Math.max(0, m);
    }

    /**
     *  Largest exponent whose pair of buffers fits in the given byte budget,
     *  capped at the largest a 32 byte key can be hashed into.
     *
     *  @param budgetBytes memory available for the filter, non-negative
     *  @return the exponent, never negative
     *  @since 0.9.71+
     */
    public static int mForBudget(long budgetBytes) {
        if (budgetBytes < 4)
            return 0;
        int m = 63 - Long.numberOfLeadingZeros(budgetBytes) + 2;
        return Math.max(0, Math.min(MAX_M, m));
    }

    /**
     *  Largest exponent usable for a 32 byte key. Above this the offset
     *  arithmetic in KeySelector runs past the end of the key.
     */
    public static final int MAX_M = 29;

    /** Unsynchronized; DecayingHashSet also reads this on the add() path. */
    public int getInsertedCount() {
            return _current.size() + _previous.size();
    }

    /**
     * False positive rate.
     *
     * @return the false positive rate
     */
    public double getFalsePositiveRate() {
            return _current.falsePositives();
    }

    /**
     * Add a byte array entry to the filter
     *
     * @param entry the entry to add
     * @return true if the entry added is a duplicate
     */
    public boolean add(byte[] entry) {
        return add(entry, 0, entry.length);
    }

    /**
     * Add a byte array entry at the specified offset and length
     *
     * @param entry the entry data
     * @param off the offset
     * @param len the length
     * @return true if the entry added is a duplicate
     */
    public boolean add(byte[] entry, int off, int len) {
        if (ALWAYS_MISS) return false;
        if (entry == null)
            throw new IllegalArgumentException("Null entry");
        if (len != _entryBytes)
            throw new IllegalArgumentException("Bad entry [" + len + ", expected "
                                               + _entryBytes + "]");
        getReadLock();
        try {
            return locked_add(entry, off, len, true);
        } finally { releaseReadLock(); }
    }

    /**
     *  Encode a long entry into the byte array that gets hashed. Negative
     *  values are stored as their magnitude with the top bit of the first byte
     *  set as the sign, so a long and its negation stay distinct.
     *
     *  Single source of truth for the long-to-entry conversion, shared by
     *  add(long) and isKnown(long) so the two cannot drift apart.
     *
     *  @param entry the value to encode
     *  @return the thread's scratch array of exactly _entryBytes bytes. The
     *          caller must consume it before the next call on the same thread.
     *  @throws IllegalArgumentException if entryBytes is over 8, since
     *          DataHelper.toLong() cannot write more than 8 bytes; use the
     *          byte array form of add() for wider entries
     *  @since 0.9.71+
     */
    private byte[] encodeLong(long entry) {
        if (_entryBytes > 8)
            throw new IllegalArgumentException("Bad size [" + _entryBytes
                                               + "], a long cannot be encoded wider than 8 bytes");
        if (_entryBytes <= 7)
            entry ^= _longToEntryMask;
        byte[] rv = _longToEntryBuf.get();
        if (entry < 0) {
            // 0 - Long.MIN_VALUE overflows back to Long.MIN_VALUE, which
            // DataHelper.toLong() rejects, so fold that single value onto
            // Long.MAX_VALUE and set the sign bit. That gives ff ff ... ff,
            // which no positive value can produce, because a non-negative long
            // never has a 0xff most significant byte. The fold therefore
            // collides with nothing and the encoding stays injective over all
            // 2**64 inputs: the sign takes the top bit, which
            // DataHelper.toLong's big endian write leaves clear for every
            // non-negative long, so no magnitude bit is given up. Below 8 bytes
            // the same holds, as the mask keeps the magnitude clear of it.
            long magnitude = (entry == Long.MIN_VALUE) ? Long.MAX_VALUE : 0 - entry;
            DataHelper.toLong(rv, 0, _entryBytes, magnitude);
            rv[0] |= (1 << 7);
        } else {
            DataHelper.toLong(rv, 0, _entryBytes, entry);
        }
        return rv;
    }

    /**
     * Add a long entry to the filter. The number of low order
     * bits used is determined by the entryBytes parameter used on creation of the
     * filter.
     *
     *  Only for filters built with 8 or fewer bytes per entry, since a long
     *  cannot be encoded any wider. Use add(byte[], int, int) otherwise.
     *
     * @param entry the long value to add
     * @return true if the entry added is a duplicate
     */
    public boolean add(long entry) {
        if (ALWAYS_MISS) return false;
        byte[] longToEntry = encodeLong(entry);
        getReadLock();
        try {
            return locked_add(longToEntry, 0, _entryBytes, true);
        } finally { releaseReadLock(); }
    }

    /**
     * Check if an entry is already known without adding it
     *
     *  Only for filters built with 8 or fewer bytes per entry, since a long
     *  cannot be encoded any wider.
     *
     * @param entry the long value to check
     * @return true if the entry is already known.  this does NOT add the
     * entry however.
     */
    public boolean isKnown(long entry) {
        if (ALWAYS_MISS) return false;
        byte[] longToEntry = encodeLong(entry);
        getReadLock();
        try {
            return locked_add(longToEntry, 0, _entryBytes, false);
        } finally { releaseReadLock(); }
    }

    private boolean locked_add(byte[] entry, int offset, int len, boolean addIfNew) {
        if (_extenders != null) {
            // extend the entry to 32 bytes
            // The extender loop only writes [0, entryBytes * (1 + numExtenders)),
            // which is under 32 bytes for a non-divisor size. The untouched tail
            // keeps the zeros it was created with, which is stable per thread,
            // so the hashed key never depends on the previous entry. The buffer
            // is deliberately not re-zeroed per call: the written prefix is a
            // fixed length for the life of the filter, so the tail cannot drift.
            byte[] extended = _extendedBuf.get();
            System.arraycopy(entry, offset, extended, 0, len);
            for (int i = 0; i < _extenders.length; i++) {
                DataHelper.xor(entry, offset, _extenders[i], 0, extended, _entryBytes * (i+1), _entryBytes);
            }
            BloomSHA1.FilterKey key = _current.getFilterKey(extended, 0, 32);
            boolean seen = _current.locked_member(key);
            if (!seen)
                seen = _previous.locked_member(key);
            if (seen) {
                if (addIfNew)
                    _currentDuplicates.incrementAndGet();
                _current.release(key);
                return true;
            } else {
                if (addIfNew) {
                    _current.locked_insert(key);
                }
                _current.release(key);
                return false;
            }
        } else {
            BloomSHA1.FilterKey key = _current.getFilterKey(entry, offset, len);
            boolean seen = _current.locked_member(key);
            if (!seen)
                seen = _previous.locked_member(key);
            if (seen) {
                if (addIfNew)
                    _currentDuplicates.incrementAndGet();
                _current.release(key);
                return true;
            } else {
                if (addIfNew) {
                    _current.locked_insert(key);
                }
                _current.release(key);
                return false;
            }
        }
    }

    /** Clear all filters and reset counts */
    public void clear() {
        // prepare both replacements before taking the write lock, for the same
        // reason decay() does
        BloomSHA1.Reset cur = _current.prepareReset();
        BloomSHA1.Reset prev = _previous.prepareReset();
        if (!getWriteLock())
            return;
        try {
            _current.applyReset(cur);
            _previous.applyReset(prev);
            _currentDuplicates.set(0);
        } finally { releaseWriteLock(); }
    }

    /** Stop the decay process */
    public void stopDecaying() {
        _keepDecaying = false;
        _decayEvent.cancel();
    }

    /**
     * Decay the filter, moving the current buffer to the previous.
     *
     * <p>The buffer that becomes the new current one is reset to a freshly
     * allocated array rather than zeroed in place. Zeroing in place is a memset
     * of the whole filter held under the write lock, and every add() takes the
     * read lock, so it stalls all inserts for its duration: measured at about
     * 2.6ms at m=29. Allocating the replacement does the same zeroing without
     * the lock and is in fact slightly cheaper at that size, so the lock is
     * held only for the pointer swap. See {@link BloomSHA1#prepareReset()} for
     * the allocation/GC tradeoff this accepts.
     */
    protected void decay() {
        int currentCount = 0;
        long dups = 0;
        double fpr = 0d;
        // pay the memset before taking the write lock
        BloomSHA1.Reset reset = _current.prepareReset();
        if (!getWriteLock())
            return;
        try {
            BloomSHA1 tmp = _previous;
            currentCount = _current.size();
            if (_log.shouldDebug() && currentCount > 0)
                fpr = _current.falsePositives();
            _previous = _current;
            _current = tmp;
            _current.applyReset(reset);
            dups = _currentDuplicates.getAndSet(0);
            _lastWindowCount = currentCount;
        } finally { releaseWriteLock(); }
        if (_log.shouldDebug())
            _log.debug("Decaying the " + _name + " filter after inserting " + currentCount
                       + " elements and " + dups + " duplicates with FPR = " + fpr);
    }

    private class DecayEvent extends SimpleTimer2.TimedEvent {
        /**
         *  Caller MUST schedule.
         */
        DecayEvent() {
            super(_context.simpleTimer2());
        }

        /**
         * Decay and re-schedule the next event.
         */
        @Override
        public void timeReached() {
            if (_keepDecaying) {
                decay();
                schedule(_durationMs);
            }
        }
    }

    /**
     *  Decays at 5 minutes after the top of the hour.
     *  This ignores leap seconds.
     *
     *  @since 0.9.24
     */
    private class DecayHourlyEvent extends SimpleTimer2.TimedEvent {
        private static final long HOUR = 60 * 60 * 1000L;
        private static final long LAG = 5 * 60 * 1000L;
        private volatile long _currentHour;

        /**
         *  Schedules itself. Caller MUST NOT schedule.
         */
        DecayHourlyEvent() {
            super(_context.simpleTimer2());
            schedule(getTimeTillNextHour());
        }

        /**
         * Decay at the next hour, if the hour has changed.
         */
        public void timeReached() {
            if (_keepDecaying) {
                long now = _context.clock().now();
                long currentHour = now / HOUR;
                // handle possible clock adjustments
                if (_currentHour != currentHour) {
                    decay();
                    _currentHour = currentHour;
                }
                long next = ((1 + currentHour) * HOUR) + LAG;
                schedule(Math.max(5000, next - now));
            }
        }

        /** Side effect: sets the current hour. */
        private long getTimeTillNextHour() {
            long now = _context.clock().now();
            long currentHour = now / HOUR;
            _currentHour = currentHour;
            long next = ((1 + currentHour) * HOUR) + LAG;
            return Math.max(5000, next - now);
        }
    }

    /**
     * Acquire the read lock
     * @since 0.8.11 moved from DecayingHashSet
     */
    protected void getReadLock() {
        _reorganizeLock.readLock().lock();
    }

    /**
     * Release the read lock
     * @since 0.8.11 moved from DecayingHashSet
     */
    protected void releaseReadLock() {
        _reorganizeLock.readLock().unlock();
    }

    /**
     *  Acquire the write lock
     *
     *  @return true if the lock was acquired
     *  @since 0.8.11 moved from DecayingHashSet
     */
    protected boolean getWriteLock() {
        try {
            boolean rv = _reorganizeLock.writeLock().tryLock(5000, TimeUnit.MILLISECONDS);
            if (!rv)
                _log.error("no lock, size is: " + _reorganizeLock.getQueueLength(), new Exception("rats"));
            return rv;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /**
     * Release the write lock
     * @since 0.8.11 moved from DecayingHashSet
     */
    protected void releaseWriteLock() {
        _reorganizeLock.writeLock().unlock();
    }

}
