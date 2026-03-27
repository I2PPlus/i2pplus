package net.i2p.util;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Like ByteCache but works directly with byte arrays, not ByteArrays.
 * These are designed to be small caches, so there's no cleaner task
 * like there is in ByteCache. And we don't zero out the arrays here.
 * Only the static methods are public here.
 *
 * @since 0.8.3
 */
@SuppressWarnings("PMD.SingleMethodSingleton")
public final class SimpleByteCache {

    private static final ConcurrentHashMap<Integer, SimpleByteCache> _caches = new ConcurrentHashMap<Integer, SimpleByteCache>(8);
    private static final ConcurrentHashMap<Integer, SimpleByteCache> _allCaches = new ConcurrentHashMap<Integer, SimpleByteCache>(8);

    private static final int DEFAULT_SIZE = 64;

    /** how often do we cleanup all caches */
    private static final int CLEANUP_FREQUENCY = 60 * 1000; // 1 minute

    /** if we haven't exceeded the cache size in 90 seconds, cut our cache in half */
    private static final long EXPIRE_PERIOD = 90 * 1000;

    static {
        // Start single global cleanup timer for all caches
        SimpleTimer2.getInstance().addPeriodicEvent(new GlobalCleanup(), CLEANUP_FREQUENCY);
    }

    /**
     * Global cleanup task that iterates over all caches.
     */
    private static class GlobalCleanup implements SimpleTimer.TimedEvent {
        @Override
        public void timeReached() {
            for (SimpleByteCache cache : _allCaches.values()) {
                cache.cleanup();
            }
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
     * Get a cache responsible for arrays of the given size
     *
     * @param size how large should the objects cached be?
     */
    @SuppressWarnings("PMD.SingletonClassReturningNewInstance")
    public static SimpleByteCache getInstance(int size) {
        return getInstance(DEFAULT_SIZE, size);
    }

    /**
     * Get a cache responsible for objects of the given size
     *
     * @param cacheSize how large we want the cache to grow
     *                  (number of objects, NOT memory size)
     *                  before discarding released objects.
     * @param size how large should the objects cached be?
     */
    public static SimpleByteCache getInstance(int cacheSize, int size) {
        Integer sz = Integer.valueOf(size);
        SimpleByteCache cache = _caches.get(sz);
        if (cache == null) {
            cache = new SimpleByteCache(cacheSize, size);
            SimpleByteCache old = _caches.putIfAbsent(sz, cache);
            if (old != null) {
                cache = old;
            } else {
                // Also add to the allCaches list for global cleanup
                _allCaches.put(sz, cache);
            }
        }
        cache.resize(cacheSize);
        return cache;
    }

    /**
     *  Clear everything (memory pressure)
     */
    public static void clearAll() {
        for (SimpleByteCache bc : _caches.values()) {
            bc.clear();
        }
    }

    private final TryCache<byte[]> _available;
    private final int _entrySize;

    /** @since 0.9.36 */
    private static class ByteArrayFactory implements TryCache.ObjectFactory<byte[]> {
        private final int sz;

        ByteArrayFactory(int entrySize) {
            sz = entrySize;
        }

        @Override
        public byte[] newInstance() {
            return new byte[sz];
        }
    }

    private SimpleByteCache(int maxCachedEntries, int entrySize) {
        _available = new TryCache<byte[]>(new ByteArrayFactory(entrySize), maxCachedEntries);
        _entrySize = entrySize;
    }

    private void resize(int maxCachedEntries) {
        // TryCache doesn't support dynamic resize, but we could create a new cache
        // For now, this is a no-op as TryCache uses a fixed-size ConcurrentLinkedDeque
        // To fully support resize, TryCache would need modification
    }

    /**
     * Get the next available array, either from the cache or a brand new one
     */
    public static byte[] acquire(int size) {
        return getInstance(size).acquire();
    }

    /**
     * Get the next available array, either from the cache or a brand new one
     */
    private byte[] acquire() {
        return _available.acquire();
    }

    /**
     * Put this array back onto the available cache for reuse
     */
    public static void release(byte[] entry) {
        SimpleByteCache cache = _caches.get(entry.length);
        if (cache != null) {
            cache.releaseIt(entry);
        }
    }

    /**
     * Put this array back onto the available cache for reuse
     */
    private void releaseIt(byte[] entry) {
        if (entry == null || entry.length != _entrySize) {
            return;
        }
        // should be safe without this
        // Arrays.fill(entry, (byte) 0);
        _available.release(entry);
    }

    /**
     *  Clear everything (memory pressure)
     */
    private void clear() {
        _available.clear();
    }
}
