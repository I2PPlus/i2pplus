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
 *   <li>32 bytes, peak 10 entries in 1m (320 on fast routers)</li>
 *   <li>4 bytes, peak 150 entries in 10s (1600 on fast routers)</li>
 *   <li>8 bytes, peak 1K entries in 2m (36K on fast routers)</li>
 *   <li>16 bytes, peak 15K entries in 10m (15K on fast routers)</li>
 * </ul>
 * <p>
 * Performance characteristics:
 * <ul>
 *   <li>Zero false positive rate for ≤8 byte keys</li>
 *   <li>5.4E-20 false positive rate for larger keys</li>
 *   <li>Twice as fast as DBF in benchmarks</li>
 *   <li>Space-proportional traffic handling</li>
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
     * Create a double-buffered hash set that will decay its entries over time.
     *
     * @param durationMs entries last for at least this long, but no more than twice this long
     * @param entryBytes how large are the entries to be added?  1 to 32 bytes
     */
    public DecayingHashSet(I2PAppContext context, int durationMs, int entryBytes) {
        this(context, durationMs, entryBytes, "DHS");
    }

    /** @param name just for logging / debugging / stats */
    public DecayingHashSet(I2PAppContext context, int durationMs, int entryBytes, String name) {
        super(durationMs, entryBytes, name, context);
        if (entryBytes <= 0 || entryBytes > 32)
            throw new IllegalArgumentException("Bad size");
        _current = new ConcurrentHashSet<ArrayWrapper>(128);
        _previous = new ConcurrentHashSet<ArrayWrapper>(128);
        if (_log.shouldDebug())
            _log.debug("New DHS " + name + " entryBytes = " + entryBytes +
                     " cycle (s) = " + (durationMs / 1000));
        // try to get a handle on memory usage vs. false positives
        context.statManager().createRateStat("router.decayingHashSet." + name + ".size",
             "Size", "Router [DecayingHashSet]", new long[] { 10 * Math.max(60*1000, durationMs) });
        context.statManager().createRateStat("router.decayingHashSet." + name + ".dups",
             "1000000 * Duplicates/Size", "Router [DecayingHashSet]", new long[] { 10 * Math.max(60*1000, durationMs) });
    }

    /** unsynchronized but only used for logging elsewhere */
    @Override
    public int getInsertedCount() {
        return _current.size() + _previous.size();
    }

    /** pointless, only used for logging elsewhere */
    @Override
    public double getFalsePositiveRate() {
        if (_entryBytes <= 8)
            return 0d;
        return 1d / Math.pow(2d, 64d);  // 5.4E-20
    }

    /**
     * @return true if the entry added is a duplicate
     */
    @Override
    public boolean add(byte entry[], int off, int len) {
        if (entry == null)
            throw new IllegalArgumentException("Null entry");
        if (len != _entryBytes)
            throw new IllegalArgumentException("Bad entry [" + len + ", expected "
                                               + _entryBytes + "]");
        ArrayWrapper w = new ArrayWrapper(entry, off, len);
        getReadLock();
        try {
            return locked_add(w, true);
        } finally { releaseReadLock(); }
    }

    /**
     * @return true if the entry added is a duplicate.  the number of low order
     * bits used is determined by the entryBytes parameter used on creation of the
     * filter.
     *
     */
    @Override
    public boolean add(long entry) {
        return add(entry, true);
    }

    /**
     * @return true if the entry is already known.  this does NOT add the
     * entry however.
     *
     */
    @Override
    public boolean isKnown(long entry) {
        return add(entry, false);
    }

    private boolean add(long entry, boolean addIfNew) {
        ArrayWrapper w = new ArrayWrapper(entry);
        getReadLock();
        try {
            return locked_add(w, addIfNew);
        } finally { releaseReadLock(); }
    }

    /**
     *  @param addIfNew if true, add the element to current if it is not already there or in previous;
     *                  if false, only check
     *  @return if the element is in either the current or previous set
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
            // why increment if addIfNew == false? Only used for stats...
            _currentDuplicates++;
        }
        return seen;
    }

    @Override
    public void clear() {
        _current.clear();
        _previous.clear();
        _currentDuplicates = 0;
    }

    /** super doesn't call clear, but neither do the users, so it seems like we should here */
    @Override
    public void stopDecaying() {
        _keepDecaying = false;
        clear();
    }

    @Override
    protected void decay() {
        int currentCount;
        long dups;
        if (!getWriteLock())
            return;
        try {
            ConcurrentHashSet<ArrayWrapper> tmp = _previous;
            currentCount = _current.size();
            _previous = _current;
            _current = tmp;
            _current.clear();
            dups = _currentDuplicates;
            _currentDuplicates = 0;
        } finally { releaseWriteLock(); }

        if (_log.shouldDebug())
            _log.debug("Decaying the " + _name + " filter after inserting " + currentCount
                       + " elements and " + dups + " false positives");
        _context.statManager().addRateData("router.decayingHashSet." + _name + ".size",
                                           currentCount);
        if (currentCount > 0)
            _context.statManager().addRateData("router.decayingHashSet." + _name + ".dups",
                                               1000l*1000*dups/currentCount);
    }

    /**
     *  This saves the data as-is if the length is &lt;= 8 bytes,
     *  otherwise it stores an 8-byte hash.
     *  Hash function is from DataHelper, modded to get
     *  the maximum entropy given the length of the data.
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

        /** faster version for when storing <= 8 bytes */
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
             if (o == null || !(o instanceof ArrayWrapper))
                 return false;
             return ((ArrayWrapper) o).longHashCode() == _longhashcode;
        }
    }

    /**
     *  vs. DBF, this measures 1.93x faster for testByLong and 2.46x faster for testByBytes.
     */
/*****
    public static void main(String args[]) {
        // KBytes per sec, 1 message per KByte
        int kbps = 256;
        int iterations = 10;
        //testSize();
        testByLong(kbps, iterations);
        testByBytes(kbps, iterations);
    }
*****/

    /** and the answer is: 49.9 bytes. The ArrayWrapper alone measured 16, so that's 34 for the HashSet entry. */
/*****
    private static void testSize() {
        int qty = 256*1024;
        byte b[] = new byte[8];
        Random r = new Random();
        long old = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        ConcurrentHashSet foo = new ConcurrentHashSet(qty);
        for (int i = 0; i < qty; i++) {
            r.nextBytes(b);
            foo.add(new ArrayWrapper(b, 0, 8));
        }
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.out.println("Memory per ArrayWrapper: " + (((double) (used - old)) / qty));
    }
*****/

    /** 8 bytes, simulate the router message validator */
/*****
    private static void testByLong(int kbps, int numRuns) {
        int messages = 60 * 10 * kbps;
        Random r = new Random();
        DecayingBloomFilter filter = new DecayingHashSet(I2PAppContext.getGlobalContext(), 600*1000, 8);
        int falsePositives = 0;
        long totalTime = 0;
        for (int j = 0; j < numRuns; j++) {
            long start = System.currentTimeMillis();
            for (int i = 0; i < messages; i++) {
                if (filter.add(r.nextLong())) {
                    falsePositives++;
                    System.out.println("False positive " + falsePositives + " (testByLong j=" + j + " i=" + i + ")");
                }
            }
            totalTime += System.currentTimeMillis() - start;
            filter.clear();
        }
        System.out.println("False postive rate should be " + filter.getFalsePositiveRate());
        filter.stopDecaying();
        System.out.println("After " + numRuns + " runs pushing " + messages + " entries in "
                           + DataHelper.formatDuration(totalTime/numRuns) + " per run, there were "
                           + falsePositives + " false positives");

    }
*****/

    /** 16 bytes, simulate the tunnel IV validator */
/*****
    private static void testByBytes(int kbps, int numRuns) {
        byte iv[][] = new byte[60*10*kbps][16];
        Random r = new Random();
        for (int i = 0; i < iv.length; i++)
            r.nextBytes(iv[i]);

        DecayingBloomFilter filter = new DecayingHashSet(I2PAppContext.getGlobalContext(), 600*1000, 16);
        int falsePositives = 0;
        long totalTime = 0;
        for (int j = 0; j < numRuns; j++) {
            long start = System.currentTimeMillis();
            for (int i = 0; i < iv.length; i++) {
                if (filter.add(iv[i])) {
                    falsePositives++;
                    System.out.println("False positive " + falsePositives + " (testByBytes j=" + j + " i=" + i + ")");
                }
            }
            totalTime += System.currentTimeMillis() - start;
            filter.clear();
        }
        System.out.println("False postive rate should be " + filter.getFalsePositiveRate());
        filter.stopDecaying();
        System.out.println("After " + numRuns + " runs pushing " + iv.length + " entries in "
                           + DataHelper.formatDuration(totalTime/numRuns) + " per run, there were "
                           + falsePositives + " false positives");
    }
*****/
}
