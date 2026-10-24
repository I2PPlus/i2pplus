package net.i2p.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Like ByteCache but works directly with byte arrays, not ByteArrays.
 * These are designed to be small caches. Unlike ByteCache, the arrays are
 * not zeroed before being returned to the cache, so a caller that needs
 * cleanup must zero the array itself.
 * <p>
 * A single global cleanup task runs once a minute over all caches and halves
 * any cache that has been underfilled for 90 seconds, so the array sizes of
 * an idle program are not retained indefinitely.
 * <p>
 * Only the static methods are public here.
 *
 * @since 0.8.3
 */
@SuppressWarnings("PMD.SingleMethodSingleton")
public final class SimpleByteCache {

    /**
     * The caches, keyed by array size. An int-keyed table rather than a
     * ConcurrentHashMap&lt;Integer, SimpleByteCache&gt;, so the hot acquire and
     * release lookups neither hash an Integer nor box the size.
     */
    private static final IntCacheMap _caches = new IntCacheMap(8);

    /** Default number of buffers cached. */
    private static final int DEFAULT_SIZE = 64;

    /** How often do we cleanup all caches. */
    private static final int CLEANUP_FREQUENCY = 60 * 1000; // 1 minute

    /** If we haven't exceeded the cache size in 90 seconds, cut our cache in half. */
    private static final long EXPIRE_PERIOD = (long) 90 * 1000;

    static {
        // Start single global cleanup timer for all caches
        new GlobalCleanup().schedule(CLEANUP_FREQUENCY);
    }

    /**
     * Global cleanup task that iterates over all caches.
     */
    private static class GlobalCleanup extends SimpleTimer2.TimedEvent {
        /**
         * Timer task that periodically cleans up every byte cache.
         */
        private GlobalCleanup() {}

        @Override
        public void timeReached() {
            for (SimpleByteCache cache : _caches.values()) {
                cache.cleanup();
            }
            schedule(CLEANUP_FREQUENCY);
        }

        @Override
        public String toString() {
            return "Global SimpleByteCache Cleanup";
        }
    }

    /**
     * Cleanup this specific cache - shrink if underutilized.
     */
    private void cleanup() {
        int origsz = _available.size();
        if (origsz > 1 && _available.wasUnderfilled(EXPIRE_PERIOD)) {
            int toRemove = origsz / 2;
            _available.shrink(origsz - toRemove);
        }
    }

    /**
     * Cache responsible for arrays of the given size, using the default size.
     *
     * @param size how large should the objects cached be?
     * @return the shared cache holding arrays of {@code size} bytes
     */
    @SuppressWarnings("PMD.SingletonClassReturningNewInstance")
    public static SimpleByteCache getInstance(int size) {
        return getInstance(DEFAULT_SIZE, size);
    }

    /**
     * Cache responsible for objects of the given size.
     * <p>
     * There is one cache per size for the life of the JVM, and cacheSize is
     * (re)applied to it whenever it differs from the size the cache already
     * holds: a later call with a different cacheSize resizes the shared cache,
     * discarding the excess on a shrink. The arrays evicted are simply garbage
     * collected - nothing zeroes them.
     *
     * @param cacheSize how many objects (NOT memory bytes) to keep before
     *                  discarding released objects
     * @param size how large should the objects cached be?
     * @return the cache for arrays of {@code size} bytes, shared by every caller that
     *         asked for the same size
     */
    public static SimpleByteCache getInstance(int cacheSize, int size) {
        SimpleByteCache cache = _caches.get(size);
        if (cache == null) {
            cache = _caches.putIfAbsent(size, new SimpleByteCache(cacheSize, size));
        }
        // On the acquire/release path the requested cacheSize never changes, so
        // this volatile read is the whole cost and resize() is skipped. Callers
        // passing *different* cacheSize values must not interleave read-then-
        // write, or the loser would skip a resize that the previous
        // unconditional call would have applied, so the rare resize is
        // serialised per cache.
        if (cache.capacity() != cacheSize) {
            synchronized (cache) {
                if (cache.capacity() != cacheSize) {
                    cache.resize(cacheSize);
                }
            }
        }
        return cache;
    }

    /**
     * Clear everything (memory pressure).
     */
    public static void clearAll() {
        for (SimpleByteCache bc : _caches.values()) {
            bc.clear();
        }
    }

    /** The buffers available for reuse. */
    private final TryCache<byte[]> _available;
    /** Size of each cached buffer. */
    private final int _entrySize;

    /**
     * Factory for cached byte arrays.
     *
     * @since 0.9.36
     */
    private static class ByteArrayFactory implements TryCache.ObjectFactory<byte[]> {
        /** Size of the arrays this factory makes. */
        private final int sz;

        /**
         * Builds the array factory.
         *
         * @param entrySize the size of the arrays to make
         */
        ByteArrayFactory(int entrySize) {
            sz = entrySize;
        }

        @Override
        public byte[] newInstance() {
            return new byte[sz];
        }
    }

    /**
     * Builds a cache of byte arrays.
     *
     * @param maxCachedEntries most buffers cached
     * @param entrySize the size of each buffer
     */
    private SimpleByteCache(int maxCachedEntries, int entrySize) {
        _available = new TryCache<>(new ByteArrayFactory(entrySize), maxCachedEntries);
        _entrySize = entrySize;
    }

    /**
     * Apply a new object count to the existing cache. Shrinking evicts the
     * excess immediately; growing only raises the ceiling for future
     * releases. Concurrent acquire/release calls are unaffected.
     *
     * @param maxCachedEntries the new maximum number of entries
     */
    private void resize(int maxCachedEntries) {
        _available.resize(maxCachedEntries);
    }

    /**
     * Current object count ceiling.
     *
     * @return the maximum number of entries currently held
     */
    private int capacity() {
        return _available.getCapacity();
    }

    /**
     * Next available array, either from the cache or a brand new one.
     *
     * @param size how large should the object be?
     * @return a byte array of exactly that length, taken from the cache when one
     *         of that size is free and allocated otherwise; never null
     */
    public static byte[] acquire(int size) {
        return getInstance(size).acquire();
    }

    /**
     * Next available array, either from the cache or a brand new one.
     *
     * @return an array of this cache's entry size, never null
     */
    private byte[] acquire() {
        return _available.acquire();
    }

    /**
     * Put this array back onto the available cache for reuse.
     * @param entry the array to recycle; ignored if null, or if no cache exists for its length
     */
    public static void release(byte[] entry) {
        if (entry == null) return;
        SimpleByteCache cache = _caches.get(entry.length);
        if (cache != null) {
            cache.releaseIt(entry);
        }
    }

    /**
     * Put this array back onto the available cache for reuse.
     *
     * @param entry the array to recycle; ignored if its length does not match
     *              this cache's entry size
     */
    private void releaseIt(byte[] entry) {
        if (entry.length != _entrySize) {
            return;
        }
        // should be safe without this
        // Arrays.fill(entry, (byte) 0);
        _available.release(entry);
    }

    /**
     * Clear everything (memory pressure).
     */
    private void clear() {
        _available.clear();
    }

/**
 * A set of {@link SimpleByteCache}, keyed by array size. Open addressed with
 * linear probing: lookups are lock-free and unboxed, and only putIfAbsent and
 * the occasional growth take the lock.
 * <p>Values are read through an AtomicReferenceArray, which both marks the
 * occupied slots - every int is a legal key, so there is no value to spare
 * as an empty-slot sentinel - and publishes each cache's fully built internals.
 *
 * @since 0.9.72+
 */
    private static final class IntCacheMap {

        /** Replaced wholesale on growth, so a reader always sees a complete one. */
        private static final class Table {
            /** Mask hashing keys into the table. */
            private final int mask;
            /** The keys stored in the table. */
            private final int[] keys;
            /** The caches stored in the table. */
            private final AtomicReferenceArray<SimpleByteCache> vals;

            /**
             * Builds an empty hash table.
             *
             * @param capacity slots in the table
             */
            Table(int capacity) {
                keys = new int[capacity];
                vals = new AtomicReferenceArray<>(capacity);
                mask = capacity - 1;
            }
        }

        /** The current hash table. */
        private volatile Table _table;

        /** Occupied slots in the current table. Guarded by this. */
        private int _size;

        /**
         * Builds the map of caches by size.
         *
         * @param expectedSize entries expected in the map
         */
        IntCacheMap(int expectedSize) {
            int capacity = 16;
            while (capacity < expectedSize * 2) capacity <<= 1;
            _table = new Table(capacity);
        }

        /**
         * Mixes the key, so that nearby sizes land in different slots.
         *
         * @param key the array size being cached
         * @param mask table capacity minus one
         * @return the table slot index for the key
         */
        private static int slot(int key, int mask) {
            int h = key * 0x9E3779B1;
            h ^= h >>> 16;
            return h & mask;
        }

        /**
         * The cached array for the key, creating it if needed.
         *
         * @param key the array size
         * @return the cache for the key, or null
         */
        SimpleByteCache get(int key) {
            Table t = _table;
            int mask = t.mask;
            int idx = slot(key, mask);
            while (true) {
                SimpleByteCache val = t.vals.get(idx);
                if (val == null) return null;
                if (t.keys[idx] == key) return val;
                idx = (idx + 1) & mask;
            }
        }

        /**
         * Store a cache unless the key is already present.
         *
         * @param key the array size
         * @param val the cache to store if the key is free
         * @return the value now stored for the key - val, or the one that was
         *         already there
         */
        synchronized SimpleByteCache putIfAbsent(int key, SimpleByteCache val) {
            Table t = _table;
            int idx = slot(key, t.mask);
            while (t.vals.get(idx) != null) {
                if (t.keys[idx] == key) return t.vals.get(idx);
                idx = (idx + 1) & t.mask;
            }
            // keep the table at most three-quarters full, so probes stay short
            if (_size * 4 >= t.keys.length * 3) {
                t = grow();
                idx = slot(key, t.mask);
                while (t.vals.get(idx) != null) idx = (idx + 1) & t.mask;
            }
            t.keys[idx] = key;
            _size++;
            t.vals.set(idx, val);
            return val;
        }

        /**
         * Double the table. Caller must synchronize on this.
         *
         * @return the new table, twice the size, already rehashed
         */
        private Table grow() {
            Table old = _table;
            Table t = new Table(old.keys.length * 2);
            for (int i = 0; i < old.keys.length; i++) {
                SimpleByteCache val = old.vals.get(i);
                if (val == null) continue;
                int key = old.keys[i];
                int idx = slot(key, t.mask);
                while (t.vals.get(idx) != null) idx = (idx + 1) & t.mask;
                t.keys[idx] = key;
                t.vals.set(idx, val);
            }
            _table = t;
            return t;
        }

        /**
         * Snapshot of the caches, for the cleanup task and {@link #clearAll}.
         *
         * @return never null
         */
        List<SimpleByteCache> values() {
            Table t = _table;
            List<SimpleByteCache> rv = new ArrayList<>(_size);
            for (int i = 0; i < t.vals.length(); i++) {
                SimpleByteCache val = t.vals.get(i);
                if (val != null) rv.add(val);
            }
            return rv;
        }
    }
}
