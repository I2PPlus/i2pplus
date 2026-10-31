package net.i2p.kademlia;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory storage of buckets sorted by XOR metric from base (us)
 * passed in via constructor.
 * This starts with one bucket covering the whole key space, and
 * may eventually be split to a max of number of bits in data type
 * (160 for SHA1Hash or 256 for Hash),
 * times 2**(B-1) for Kademlia value B.
 * <p>
 * Refactored from net.i2p.router.networkdb.kademlia.
 *
 * @param <T> type of SimpleDataStructure objects stored in buckets
 * @since 0.9.2 in i2psnark, moved to core in 0.9.10
 */
public class KBucketSet<T extends SimpleDataStructure> {
    /** Logger for the routing table. */
    private final Log _log;
    /** The app context. */
    private final I2PAppContext _context;
    /** The local node's key. */
    private final T _us;

    /**
     * The bucket list is locked by _bucketsLock, however the individual
     * buckets are not locked. Users may see buckets that have more than
     * the maximum k entries, or may have adds and removes silently fail
     * when they appear to succeed.
     * <p>
     * Closest values are in bucket 0, furthest are in the last bucket.
     */
    private final List<KBucket<T>> _buckets;

    /** Calculator of the bucket range for a key. */
    private final Range<T> _rangeCalc;
    /** Trimmer deciding which entries to drop. */
    private final KBucketTrimmer<T> _trimmer;

    /**
     * Locked for reading only when traversing all the buckets.
     * Locked for writing only when splitting a bucket.
     * Adds/removes/gets from individual buckets are not locked.
     */
    private final ReentrantReadWriteLock _bucketsLock = new ReentrantReadWriteLock(false);

    /** Number of bits in a key. */
    private final int KEYSIZE_BITS;
    /** Number of buckets in the set. */
    private final int NUM_BUCKETS;
    /** Entries one bucket holds. */
    private final int BUCKET_SIZE;
    /** B value from the kademlia paper. */
    private final int B_VALUE;
    /** Factor scaling the bucket ranges. */
    private final int B_FACTOR;

    /**
     * Use the default trim strategy, which removes a random entry.
     *
     * @param context the router context
     * @param us the local identity (typically a SHA1Hash or Hash)
     *           The class must have a zero-argument constructor.
     * @param max the Kademlia value "k", the max per bucket, must be &gt; 4
     * @param b the Kademlia value "b", split buckets an extra 2**(b-1) times,
     *          1 &lt;= b &lt;= 8, use 1 for bittorrent, Kademlia paper recommends 5
     * @throws IllegalArgumentException if max &lt;= 4, or b is outside 1..8
     * @throws RuntimeException if the us class has no zero-argument constructor
     */
    public KBucketSet(I2PAppContext context, T us, int max, int b) {
        this(context, us, max, b, new RandomTrimmer<>(context, max));
    }

    /**
     * Use the supplied trim strategy.
     *
     * @param context the router context
     * @param us the local identity
     * @param max the Kademlia value "k", the max per bucket, must be &gt; 4
     * @param b the Kademlia value "b", 1 &lt;= b &lt;= 8
     * @param trimmer the trim strategy
     * @throws IllegalArgumentException if max &lt;= 4, or b is outside 1..8
     * @throws RuntimeException if the us class has no zero-argument constructor
     */
    public KBucketSet(I2PAppContext context, T us, int max, int b, KBucketTrimmer<T> trimmer) {
        _us = us;
        _context = context;
        _log = context.logManager().getLog(KBucketSet.class);
        _trimmer = trimmer;
        if (max <= 4 || b <= 0 || b > 8) {
            throw new IllegalArgumentException();
        }
        KEYSIZE_BITS = us.length() * 8;
        B_VALUE = b;
        B_FACTOR = 1 << (b - 1);
        NUM_BUCKETS = KEYSIZE_BITS * B_FACTOR;
        BUCKET_SIZE = max;
        _buckets = createBuckets();
        _rangeCalc = new Range<>(us, B_VALUE);
        makeKey(new byte[us.length()]); // this verifies the zero-argument constructor
    }

    /** Takes the read lock. */
    private void getReadLock() {
        _bucketsLock.readLock().lock();
    }

    /** Releases the read lock. */
    private void releaseReadLock() {
        _bucketsLock.readLock().unlock();
    }

    /**
     * Try to acquire the write lock.
     * @return true if the lock was acquired
     */
    private boolean getWriteLock() {
        try {
            boolean rv = _bucketsLock.writeLock().tryLock(3000, TimeUnit.MILLISECONDS);
            if ((!rv) && _log.shouldWarn()) {
                _log.warn("Bucket lock timed out -> Lock not acquired * Waiters: " +
                          _bucketsLock.getQueueLength());
            }
            return rv;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /** Releases the write lock. */
    private void releaseWriteLock() {
        _bucketsLock.writeLock().unlock();
    }

    /**
     * Add a peer to the appropriate bucket.
     * Will split the bucket if necessary.
     *
     * @param peer the router hash to insert
     * @return true if the peer is new to the bucket it goes in, or false if it was
     *         already in it. Always returns false on an attempt to add ourselves.
     */
    public boolean add(T peer) {
        KBucket<T> bucket;
        getReadLock();
        try {
            bucket = getBucket(peer);
        } finally {
            releaseReadLock();
        }
        if (bucket != null) {
            if (bucket.add(peer)) {
                if (_log.shouldDebug()) {
                    _log.debug("Router [" + peer.toBase64().substring(0, 6) + "] added to bucket\n* " + bucket);
                }
                if (shouldSplit(bucket)) {
                    if (_log.shouldDebug()) {
                        _log.debug("Splitting bucket " + bucket);
                    }
                    split(bucket.getRangeBegin());
                }
                return true;
            } else {
                if (_log.shouldDebug()) {
                    _log.debug("Router [" + peer.toBase64().substring(0, 6) + "] NOT added to bucket -> Already known\n* " + bucket);
                }
                return false;
            }
        } else {
            if (_log.shouldInfo()) {
                _log.info("Failed to add peer to DHT bucket -> Probably us: " + peer);
            }
            return false;
        }
    }

    /**
     * No lock required.
     *
     * @param b the bucket to test
     * @return true if the bucket is over BUCKET_SIZE and its range covers more
     *         than one key
     */
    private boolean shouldSplit(KBucket<T> b) {
        // FIXME will split the closest buckets too far if B > 1 and K < 2**B
        // Won't ever really happen and if it does it still works.
        return b.getRangeBegin() != b.getRangeEnd() && b.getKeyCount() > BUCKET_SIZE;
    }

    /**
     * Grabs the write lock.
     * Caller must NOT have the read lock.
     * The bucket should be splittable (range start != range end).
     *
     * @param r the range start of the bucket to be split
     */
    private void split(int r) {
        if (!getWriteLock()) {
            return;
        }
        try {
            locked_split(r);
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Creates two or more new buckets. The old bucket is replaced and discarded.
     * <p>
     * Caller must hold write lock
     * The bucket should be splittable (range start != range end).
     *
     * @param r the range start of the bucket to be split
     */
    private void locked_split(int r) {
        int b = pickBucket(r);
        while (shouldSplit(_buckets.get(b))) {
            KBucket<T> b0 = _buckets.get(b);
            // Each bucket gets half the keyspace.
            // When B_VALUE = 1, or the bucket is larger than B_FACTOR, then
            // e.g. 0-159 => 0-158, 159-159
            // When B_VALUE > 1, and the bucket is smaller than B_FACTOR, then
            // e.g. 1020-1023 => 1020-1021, 1022-1023
            int s1;
            int e1;
            int s2;
            int e2;
            s1 = b0.getRangeBegin();
            e2 = b0.getRangeEnd();
            if (B_VALUE == 1 || ((s1 & (B_FACTOR - 1)) == 0 && ((e2 + 1) & (B_FACTOR - 1)) == 0 && e2 > s1 + B_FACTOR)) {
                // The bucket is a "whole" kbucket with a range > B_FACTOR,
                // so it should be split into two "whole" kbuckets each with
                // a range >= B_FACTOR.
                // Log split
                s2 = e2 + 1 - B_FACTOR;
            } else {
                // The bucket is the smallest "whole" kbucket with a range == B_FACTOR,
                // or B_VALUE > 1 and the bucket has already been split.
                // Start or continue splitting down to a depth B_VALUE.
                // Linear split
                s2 = s1 + ((1 + e2 - s1) / 2);
            }
            e1 = s2 - 1;
            if (_log.shouldInfo()) {
                _log.info("Splitting (" + s1 + ',' + e2 + ") -> (" + s1 + ',' + e1 + ") (" + s2 + ',' + e2 + ')');
            }

            KBucket<T> b1 = createBucket(s1, e1);
            KBucket<T> b2 = createBucket(s2, e2);
            for (T key : b0.getEntries()) {
                if (getRange(key) < s2) {
                    b1.add(key);
                } else {
                    b2.add(key);
                }
            }

            _buckets.set(b, b1);
            _buckets.add(b + 1, b2);

            if (_log.shouldDebug()) {
                _log.debug("Split bucket at idx " + b + ":\n* " + b0 + "\n* into: " + b1 + "\n* and: " + b2);
            }

            if (b2.getKeyCount() > BUCKET_SIZE) {
                // should be rare... too hard to call _trimmer from here
                // (and definitely not from inside the write lock)
                if (_log.shouldInfo()) {
                    _log.info("All went into 2nd bucket after split");
                }
            }
            // loop if all the entries went in the first bucket
        }
    }

    /**
     * The current number of entries.
     *
     * @return total number of entries across all buckets
     */
    public int size() {
        int rv = 0;
        getReadLock();
        try {
            for (KBucket<T> b : _buckets) {
                rv += b.getKeyCount();
            }
        } finally {
            releaseReadLock();
        }
        return rv;
    }

    /**
     * Number of buckets in this set.
     *
     * @return the bucket count
     * @since 0.9.71
     */
    public int getBucketCount() {
        getReadLock();
        try {
            return _buckets.size();
        } finally {
            releaseReadLock();
        }
    }

    /**
     * The number of entries a single bucket holds before splitting.
     *
     * @return the per-bucket capacity
     * @since 0.9.71
     */
    public int getBucketCapacity() {return BUCKET_SIZE;}

    /**
     * The entry count of the fullest bucket, to spot eviction pressure.
     *
     * @return zero or more
     * @since 0.9.71
     */
    public int getFullestBucketSize() {
        int max = 0;
        getReadLock();
        try {
            for (KBucket<T> b : _buckets) {
                int n = b.getKeyCount();
                if (n > max) {max = n;}
            }
        } finally {
            releaseReadLock();
        }
        return max;
    }

    /**
     * The key this set is rooted on; bucket ranges are bit positions of the
     * XOR distance from this key.
     *
     * @return non-null
     * @since 0.9.71
     */
    public T getRoot() {return _us;}

    /**
     * The B value: the number of subranges the key space is split into at
     * the top level.
     *
     * @return one or more
     * @since 0.9.71
     */
    public int getBucketBValue() {return B_VALUE;}

    /**
     * Snapshot of every bucket for the debug console, one row per bucket:
     * {index, bucket count, range begin, range end, key count, keys} as
     * strings. Ranges are bit positions of the XOR distance from the root
     * key. Keys are the entry keys' {@link #toString()} values, comma-separated,
     * empty if the bucket is empty.
     *
     * @return non-null, empty if no buckets exist
     * @since 0.9.71
     */
    public List<String[]> getDebugBucketRows() {
        getReadLock();
        try {
            int len = _buckets.size();
            List<String[]> rv = new ArrayList<String[]>(len);
            String total = String.valueOf(len);
            for (int i = 0; i < len; i++) {
                KBucket<T> b = _buckets.get(i);
                StringBuilder keys = new StringBuilder(64);
                for (T key : b.getEntries()) {
                    if (keys.length() > 0) keys.append(',');
                    keys.append(key.toString());
                }
                rv.add(new String[] {String.valueOf(i + 1), total,
                        String.valueOf(b.getRangeBegin()), String.valueOf(b.getRangeEnd()),
                        String.valueOf(b.getKeyCount()), keys.toString()});
            }
            return rv;
        } finally {
            releaseReadLock();
        }
    }

    /**
     * Remove a peer from its bucket.
     *
     * @param entry the peer to remove
     * @return true if removed
     */
    public boolean remove(T entry) {
        KBucket<T> kbucket;
        getReadLock();
        try {
            kbucket = getBucket(entry);
        } finally {
            releaseReadLock();
        }
        if (kbucket == null) {
            return false;
        } // us
        boolean removed = kbucket.remove(entry);
        return removed;
    }

    /**
     * Clear all buckets.
     *
     * @since 0.8.8
     */
    public void clear() {
        getReadLock();
        try {
            for (KBucket<T> b : _buckets) {
                b.clear();
            }
        } finally {
            releaseReadLock();
        }
        _rangeCalc.clear();
    }

    /**
     * Return all entries in all buckets.
     *
     * @return a copy in a new set
     */
    public Set<T> getAll() {
        Set<T> all = new HashSet<>(256);
        getReadLock();
        try {
            for (KBucket<T> b : _buckets) {
                all.addAll(b.getEntries());
            }
        } finally {
            releaseReadLock();
        }
        return all;
    }

    /**
     * All entries in all buckets, excluding the specified entries.
     *
     * @param toIgnore entries to exclude
     * @return a copy in a new set
     */
    public Set<T> getAll(Set<T> toIgnore) {
        Set<T> all = getAll();
        all.removeAll(toIgnore);
        return all;
    }

    /**
     * Add all entries in all buckets to the provided collector.
     * <p>The read lock is held only long enough to copy the bucket references
     * out of the list, then released before the collector runs. Collectors do
     * netDb, banlist and profile lookups, so holding the lock across the pass
     * blocked every {@link #add} and bucket split in the router for the
     * duration of a full traversal of the whole keyspace — and this is called
     * once per DSRM reply and once per outbound explore query. A concurrent
     * split may leave a snapshot entry already emptied and a concurrent add may
     * not appear; both were already possible outcomes of the unsynchronized
     * per-bucket access documented on {@link #_buckets}.
     *
     * @param collector the collector to add entries to
     */
    public void getAll(SelectionCollector<T> collector) {
        List<KBucket<T>> snapshot;
        getReadLock();
        try {
            snapshot = new ArrayList<>(_buckets);
        } finally {
            releaseReadLock();
        }
        for (KBucket<T> b : snapshot) {
            b.getEntries(collector);
        }
    }

    /**
     * The keys closest to us.
     * Returned list will never contain us.
     *
     * @param max maximum number of keys to return
     * @return non-null, closest first
     */
    public List<T> getClosest(int max) {
        return getClosest(max, Collections.<T>emptySet());
    }

    /**
     * The keys closest to us.
     * Returned list will never contain us.
     *
     * @param max maximum number of keys to return
     * @param toIgnore entries to exclude
     * @return non-null, closest first
     */
    public List<T> getClosest(int max, Collection<T> toIgnore) {
        List<T> rv = new ArrayList<>(max);
        int count = 0;
        getReadLock();
        try {
            // start at first (closest) bucket
            for (KBucket<T> bucket : _buckets) {
                if (count >= max) break;
                for (T e : bucket.getEntries()) {
                    if (!toIgnore.contains(e)) {
                        rv.add(e);
                        count++;
                    }
                }
            }
        } finally {
            releaseReadLock();
        }
        Comparator<T> comp = new XORComparator<>(_us);
        Collections.sort(rv, comp);
        if (rv.size() > max)
            rv.subList(max, rv.size()).clear();
        return rv;
    }

    /**
     * The keys closest to the key.
     * Returned list will never contain us.
     *
     * @param key the target key
     * @param max maximum number of keys to return
     * @return non-null, closest first
     */
    public List<T> getClosest(T key, int max) {
        return getClosest(key, max, Collections.<T>emptySet());
    }

    /**
     * The keys closest to the key.
     * Returned list will never contain us.
     *
     * @param key the target key
     * @param max maximum number of keys to return
     * @param toIgnore entries to exclude
     * @return non-null, closest first
     */
    public List<T> getClosest(T key, int max, Collection<T> toIgnore) {
        if (key.equals(_us)) return getClosest(max, toIgnore);
        List<T> rv = new ArrayList<>(max);
        int count = 0;
        List<KBucket<T>> buckets = _buckets;
        int numBuckets = buckets.size();
        getReadLock();
        try {
            int start = pickBucket(key);
            // start at closest bucket, then to the smaller (closer to us) buckets
            for (int i = start; i >= 0 && count < max; i--) {
                for (T e : buckets.get(i).getEntries()) {
                    if (!toIgnore.contains(e)) {
                        rv.add(e);
                        count++;
                    }
                }
            }
            // then the farther from us buckets if necessary
            for (int i = start + 1; i < numBuckets && count < max; i++) {
                for (T e : buckets.get(i).getEntries()) {
                    if (!toIgnore.contains(e)) {
                        rv.add(e);
                        count++;
                    }
                }
            }
        } finally {
            releaseReadLock();
        }
        Comparator<T> comp = new XORComparator<>(key);
        Collections.sort(rv, comp);
        if (rv.size() > max)
            rv.subList(max, rv.size()).clear();
        return rv;
    }

    /**
     * The bucket number (NOT the range number) that the xor of the key goes in
     * Caller must hold read lock.
     *
     * @param key the key whose bucket is wanted
     * @return 0 to max-1 or -1 for us
     */
    private int pickBucket(T key) {
        int range = getRange(key);
        if (range < 0) {
            return -1;
        }
        int rv = pickBucket(range);
        if (rv >= 0) {
            return rv;
        }
        _log.error("Key does not fit in any bucket -> Dropping it" +
                   "\n* Key: " + DataHelper.toHexString(key.getData()).substring(0, 16) +
                   "\n* Us: " + DataHelper.toHexString(_us.getData()).substring(0, 16) +
                   "\n* Delta: " + DataHelper.toHexString(DataHelper.xor(_us.getData(), key.getData())).substring(0, 16) +
                   "\n* Buckets: " + _buckets.size() + " * Keys: " + size());
        throw new IllegalStateException("pickBucket returned " + rv);
    }

    /**
     * Returned list is a copy of the bucket list, closest first,
     * with the actual buckets (not a copy).
     * <p>
     * Primarily for testing. You shouldn't ever need to get all the buckets.
     * Use {@link #getClosest} or getAll() instead to get the keys.
     *
     * @return non-null
     */
    List<KBucket<T>> getBuckets() {
        getReadLock();
        try {
            return new ArrayList<>(_buckets);
        } finally {
            releaseReadLock();
        }
    }

    /**
     * The bucket that the xor of the key goes in
     * Caller must hold read lock.
     *
     * @param key the key whose bucket is wanted
     * @return null if key is us
     */
    private KBucket<T> getBucket(T key) {
        int bucket = pickBucket(key);
        if (bucket < 0) {
            return null;
        }
        return _buckets.get(bucket);
    }

    /**
     * The bucket number that contains this range number
     * Caller must hold read lock or write lock.
     *
     * @param range the range number whose bucket is wanted
     * @return 0 to max-1 or -1 for us
     */
    private int pickBucket(int range) {
        // If B is small, a linear search from back to front
        // is most efficient since most of the keys are at the end...
        // If B is larger, there's a lot of sub-buckets
        // of equal size to be checked so a binary search is better
        if (B_VALUE <= 3) {
            for (int i = _buckets.size() - 1; i >= 0; i--) {
                KBucket<T> b = _buckets.get(i);
                if (range >= b.getRangeBegin() && range <= b.getRangeEnd()) {
                    return i;
                }
            }
            return -1;
        } else {
            KBucket<T> dummy = new DummyBucket<>(range);
            return Collections.binarySearch(_buckets, dummy, new BucketComparator<>());
        }
    }

    /**
     * Builds the initial bucket list.
     *
     * @return the new buckets
     */
    private List<KBucket<T>> createBuckets() {
        // just an initial size
        List<KBucket<T>> buckets = new ArrayList<>(4 * B_FACTOR);
        buckets.add(createBucket(0, NUM_BUCKETS - 1));
        return buckets;
    }

    /**
     * Builds one bucket covering a range.
     *
     * @param start the first bit of the range
     * @param end the last bit of the range
     * @return the new bucket
     */
    private KBucket<T> createBucket(int start, int end) {
        if (end - start >= B_FACTOR && (((end + 1) & B_FACTOR - 1) != 0 || (start & B_FACTOR - 1) != 0)) throw new IllegalArgumentException("Sub-bkt crosses K-bkt boundary: " + start + '-' + end);
        KBucket<T> bucket = new KBucketImpl<>(_context, start, end, BUCKET_SIZE, _trimmer);
        return bucket;
    }

    /**
     * The number of bits minus 1 (range number) for the xor of the key.
     * Package private for testing only. Others shouldn't need this.
     *
     * @param key the router hash whose bucket range is wanted
     * @return 0 to max-1 or -1 for us
     */
    int getRange(T key) {
        return _rangeCalc.getRange(key);
    }

    /**
     * For every bucket that hasn't been updated in this long,
     * or isn't close to full,
     * generate a random key that would be a member of that bucket.
     * The returned keys may be searched for to "refresh" the buckets.
     *
     * @param age the maximum age in milliseconds
     * @return non-null, closest first
     */
    public List<T> getExploreKeys(long age) {
        List<T> rv = new ArrayList<>(_buckets.size());
        long old = _context.clock().now() - age;
        getReadLock();
        try {
            for (KBucket<T> b : _buckets) {
                int curSize = b.getKeyCount();
                // Always explore the closest bucket
                if ((b.getRangeBegin() == 0) || (b.getLastChanged() < old || curSize < BUCKET_SIZE * 3 / 4)) rv.add(generateRandomKey(b));
            }
        } finally {
            releaseReadLock();
        }
        return rv;
    }

    /**
     * For every bucket that hasn't been updated in this long,
     * or isn't close to full, generate a random key that would be a member
     * of that bucket, up to max keys. The closest bucket's key is always
     * first. The remaining buckets are visited in random order so a capped
     * pass does not always starve the far end of the keyspace.
     *
     * @param age the maximum age in milliseconds
     * @param max the maximum number of keys to generate
     * @return non-null, closest bucket first
     * @since 0.9.71+
     */
    public List<T> getExploreKeys(long age, int max) {
        if (max <= 0) {return Collections.emptyList();}
        List<T> rv = new ArrayList<>(Math.min(max, 32));
        long old = _context.clock().now() - age;
        getReadLock();
        try {
            int len = _buckets.size();
            int[] order = new int[len];
            for (int i = 0; i < len; i++) {order[i] = i;}
            // Fisher-Yates over all but the closest bucket, which stays first
            for (int i = len - 1; i > 1; i--) {
                int j = 1 + _context.random().nextInt(i);
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }
            for (int i = 0; i < len && rv.size() < max; i++) {
                KBucket<T> b = _buckets.get(order[i]);
                int curSize = b.getKeyCount();
                // Always explore the closest bucket
                if ((b.getRangeBegin() == 0) || (b.getLastChanged() < old || curSize < BUCKET_SIZE * 3 / 4)) {
                    rv.add(generateRandomKey(b));
                }
            }
        } finally {
            releaseReadLock();
        }
        return rv;
    }

    /**
     * A copy of the entries of the bucket the given key belongs to.
     *
     * @param key the key whose bucket entries are wanted
     * @return non-null, empty if the key is us or fits no bucket
     * @since 0.9.71+
     */
    public Set<T> getBucketEntries(T key) {
        KBucket<T> bucket;
        getReadLock();
        try {
            bucket = getBucket(key);
        } finally {
            releaseReadLock();
        }
        if (bucket == null) {return Collections.emptySet();}
        return new HashSet<>(bucket.getEntries());
    }

    /**
     * Generate a random key to go within this bucket
     * Package private for testing only. Others shouldn't need this.
     *
     * @param bucket the bucket to pick a random key for
     * @return a random key in the bucket's range
     */
    T generateRandomKey(KBucket<T> bucket) {
        int begin = bucket.getRangeBegin();
        int end = bucket.getRangeEnd();
        // number of fixed bits, out of B_VALUE - 1 bits
        int fixed = 0;
        int bsz = 1 + end - begin;
        // compute fixed = (B_VALUE - 1) - log2(bsz)
        // e.g for B=4, B_FACTOR=8, sz 4-> fixed 1, sz 2->fixed 2, sz 1 -> fixed 3
        while (bsz < B_FACTOR) {
            fixed++;
            bsz <<= 1;
        }
        int fixedBits = 0;
        if (fixed > 0) {
            // 0x01, 03, 07, 0f, ...
            int mask = (1 << fixed) - 1;
            // fixed bits masked from begin
            fixedBits = (begin >> (B_VALUE - (fixed + 1))) & mask;
        }
        int obegin = begin;
        int oend = end;
        begin >>= (B_VALUE - 1);
        end >>= (B_VALUE - 1);
        // we need randomness for [0, begin) bits
        BigInteger variance;
        // 00000000rrrr
        if (begin > 0) {
            variance = new BigInteger(begin - fixed, _context.random());
        } else {
            variance = BigInteger.ZERO;
        }
        // we need nonzero randomness for [begin, end] bits
        int numNonZero = 1 + end - begin;
        if (numNonZero == 1) {
            // 00001000rrrr
            variance = variance.setBit(begin);
            // fixed bits as the 'main' bucket is split
            // 00001fffrrrr
            if (fixed > 0) {
                variance = variance.or(BigInteger.valueOf(fixedBits).shiftLeft(begin - fixed));
            }
        } else {
            // Don't span main bucket boundaries with depth > 1
            if (fixed > 0) {
                throw new IllegalStateException("??? " + bucket);
            }
            BigInteger nonz;
            if (numNonZero <= 62) {
                // add one to ensure nonzero
                long nz = 1 + _context.random().nextLong((1L << numNonZero) - 1);
                nonz = BigInteger.valueOf(nz);
            } else {
                // loop to ensure nonzero
                do {
                    nonz = new BigInteger(numNonZero, _context.random());
                } while (nonz.equals(BigInteger.ZERO));
            }
            // shift left and or-in the nonzero randomness
            if (begin > 0) {
                nonz = nonz.shiftLeft(begin);
            }
            // 0000nnnnrrrr
            variance = variance.or(nonz);
        }

        if (_log.shouldDebug()) {
            _log.debug("SB(" + obegin + ',' + oend + ") KB(" + begin + ',' + end + ") fixed=" + fixed + " fixedBits=" + fixedBits + " numNonZ=" + numNonZero);
        }
        byte[] data = variance.toByteArray();
        T key = makeKey(data);
        byte[] hash = DataHelper.xor(key.getData(), _us.getData());
        T rv = makeKey(hash);
        return rv;
    }

    /**
     * Make a new SimpleDataStructure from the data.
     *
     * @param data size &lt;= SDS length, else throws IAE,
     *             can be 1 bigger if top byte is zero
     * @return a new data structure holding the given key bytes
     */
    @SuppressWarnings("unchecked")
    private T makeKey(byte[] data) {
        int len = _us.length();
        int dlen = data.length;
        if (dlen > len + 1 || (dlen == len + 1 && data[0] != 0)) {
            throw new IllegalArgumentException("Bad length " + dlen + " > " + len);
        }
        T rv;
        try {
            rv = (T) _us.getClass().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            _log.error("Failed to create key instance", e);
            throw new RuntimeException(e);
        }
        if (dlen == len) {
            rv.setData(data);
        } else {
            byte[] ndata = new byte[len];
            if (dlen == len + 1) {
                System.arraycopy(data, 1, ndata, 0, len);
            } // one bigger
            else {
                System.arraycopy(data, 0, ndata, len - dlen, dlen);
            } // smaller
            rv.setData(ndata);
        }
        return rv;
    }

    /**
     * Calculator of the bucket range for a key.
     *
     * @param <T> the key type of the buckets
     */
    private static class Range<T extends SimpleDataStructure> {
        /** B value from the kademlia paper. */
        private final int _bValue;
        /** Our key as a big integer. */
        private final BigInteger _bigUs;
        /** Cached range numbers, by key. */
        private final Map<T, Integer> _distanceCache;

        /**
         * Builds the range calculator.
         *
         * @param us the local node's key
         * @param bValue the B value from the kademlia paper
         */
        public Range(T us, int bValue) {
            _bValue = bValue;
            _bigUs = new BigInteger(1, us.getData());
            _distanceCache = new LHMCache<>(256);
        }

        /**
         * XOR distance range number for the given key — the B-aligned bucket
         * range containing the key's distance from us.
         * @param key the key to measure
         * @return the range number, or -1 for us
         */
        public int getRange(T key) {
            Integer rv;
            synchronized (_distanceCache) {
                rv = _distanceCache.get(key);
                if (rv == null) {
                    // easy way when _bValue == 1
                    // rv = Integer.valueOf(_bigUs.xor(new BigInteger(1, key.getData())).bitLength() - 1);
                    BigInteger xor = _bigUs.xor(new BigInteger(1, key.getData()));
                    int range = xor.bitLength() - 1;
                    if (_bValue > 1) {
                        int toShift = range + 1 - _bValue;
                        int highbit = range;
                        range <<= _bValue - 1;
                        if (toShift >= 0) {
                            int extra = xor.clearBit(highbit).shiftRight(toShift).intValue();
                            range += extra;
                        }
                    }
                    rv = Integer.valueOf(range);
                    _distanceCache.put(key, rv);
                }
            }
            return rv.intValue();
        }

        /** Clears the cached range numbers. */
        public void clear() {
            synchronized (_distanceCache) {
                _distanceCache.clear();
            }
        }
    }

    /**
     * For Collections.binarySearch.
     * getRangeBegin == getRangeEnd.
     *
     * @param <T> the key type of the bucket set
     */
    private static class DummyBucket<T extends SimpleDataStructure> implements KBucket<T> {
        /** Range number of the dummy bucket. */
        private final int r;

        /**
         * Builds a dummy bucket for a range.
         *
         * @param range the range number
         */
        public DummyBucket(int range) {
            r = range;
        }

        @Override
        public int getRangeBegin() {
            return r;
        }

        @Override
        public int getRangeEnd() {
            return r;
        }

        @Override
        public int getKeyCount() {
            return 0;
        }

        @Override
        public Set<T> getEntries() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void getEntries(SelectionCollector<T> collector) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            // TODO
        }
        @Override
        public boolean add(T peer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(T peer) {
            return false;
        }

        @Override
        public void setLastChanged() {
            // TODO
        }
        @Override
        public long getLastChanged() {
            return 0;
        }
    }

    /**
     * For Collections.binarySearch.
     * Returns equal for any overlap.
     *
     * @param <T> type of SimpleDataStructure objects being ordered
     */
    private static class BucketComparator<T extends SimpleDataStructure> implements Comparator<KBucket<T>>, Serializable {
        /** Orders the buckets by their range. */
        private BucketComparator() {}
        @Override
        public int compare(KBucket<T> l, KBucket<T> r) {
            if (l.getRangeEnd() < r.getRangeBegin()) {
                return -1;
            }
            if (l.getRangeBegin() > r.getRangeEnd()) {
                return 1;
            }
            return 0;
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<div class=\"debug_container buckets\">")
           .append("<p id=bucketSet class=infohelp><b>Bucket Set rooted on:</b> <code>")
           .append(_us.toString()).append("</code> <br>").append("<b>Totals:</b> ")
           .append(size()).append(" keys in ").append(_buckets.size())
           .append(" buckets").append(" &nbsp;<b>Max bucket size:</b> ")
           .append(BUCKET_SIZE).append(" entries &nbsp;<b>B value:</b> ")
           .append(B_VALUE).append("</p>\n");
        getReadLock();
        try {
            int len = _buckets.size();
            for (int i = 0; i < len; i++) {
                KBucket<T> b = _buckets.get(i);
                buf.append("<div class=bucketCount><b>Bucket ").append(i + 1).append("/").append(len).append(":</b> ")
                   .append(b.toString().replace(" : [", "</div><div class=bucketsContainer><span class=bucket>")
                   .replace(", ", " </span><span class=bucket>").replace("]", "</span></div>"))
                   .append("<br>\n");
            }
        } finally {
            releaseReadLock();
        }
        buf.append("</div>");
        return buf.toString();
    }
}
