package net.i2p.util;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache the objects frequently used to reduce memory churn. The ByteArray
 * should be held onto as long as the data referenced in it is needed.
 *
 * For small arrays where the management of valid bytes in ByteArray
 * and prezeroing isn't required, use SimpleByteArray instead.
 *
 * Heap size control - survey of usage:
 *
 * <pre>
 * +---------------------------------------------------------------------+
 * |   Size    |   Max   |  MaxMem  | From                               |
 * +---------------------------------------------------------------------+
 * |     1K    |    32   |    32K   | tunnel TrivialPreprocessor         |
 * |     1K    |   512   |   512K   | tunnel FragmentHandler             |
 * |     1K    |   512   |   512K   | tunnel I2NP TunnelDataMessage      |
 * |     1K    |   512   |   512K   | tunnel FragmentedMessage           |
 * |   1572    |    64   |   100K   | UDP InboundMessageState            |
 * |   1730    |   128   |   216K   | streaming MessageOutputStream      |
 * |     4K    |    32   |    28K   | I2PTunnelRunner                    |
 * |     8K    |     8   |    64K   | I2PTunnel HTTPResponseOutputStream |
 * |    16K    |    16   |   256K   | I2PSnark                           |
 * |    32K    |     4   |   128K   | SAM StreamSession                  |
 * |    32K    |    10   |   320K   | SAM v2StreamSession                |
 * |    32K    |    64   |     2M   | UDP OMS                            |
 * |    32K    |   128   |     4M   | streaming MessageInputStream       |
 * |    36K    |    64   |  2.25M   | streaming PacketQueue              |
 * |    40K    |     8   |   320K   | DataHelper decompress              |
 * |    64K    |    64   |     4M   | UDP MessageReceiver                |
 * +---------------------------------------------------------------------+
 * </pre>
 *
 */
public final class ByteCache extends TryCache<ByteArray> {

    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(ByteCache.class);
    private static final Map<Integer, ByteCache> _caches = new ConcurrentHashMap<Integer, ByteCache>(16);

    /**
     *  max size in bytes of each cache
     *  Set to max memory / 128, with a min of 256KB and a max of 8MB
     *
     *  @since 0.7.14
     */
    private static final int MIN_CACHE = 256 * 1024; // 256KB

    private static final int MAX_CACHE_LIMIT = 8 * 1024 * 1024; // 8MB
    private static final int MAX_CACHE;
    private static final int MIN_CACHE_OBJECTS = 16;

    /** how often do we cleanup all caches */
    private static final int CLEANUP_FREQUENCY = 33 * 1000;

    /** if we haven't exceeded the cache size in 90 seconds, cut our cache in half */
    private static final long EXPIRE_PERIOD = 90 * 1000;

    /** Global cleanup task - single timer for all caches */
    private static final List<ByteCache> _allCaches = new ArrayList<>();

    static {
        long maxMemory = SystemVersion.getMaxMemory();
        MAX_CACHE = Math.toIntExact(Math.min(MAX_CACHE_LIMIT, Math.max(MIN_CACHE, maxMemory / 128)));
        // Start single global cleanup timer for all caches
        SimpleTimer2.getInstance().addPeriodicEvent(new GlobalCleanup(), CLEANUP_FREQUENCY);
    }

    /**
     * Global cleanup task that iterates over all caches.
     */
    private static class GlobalCleanup implements SimpleTimer.TimedEvent {
        @Override
        public void timeReached() {
            synchronized (_allCaches) {
                for (ByteCache cache : _allCaches) {
                    cache.cleanup();
                }
            }
        }

        @Override
        public String toString() {
            return "Global ByteCache Cleanup";
        }
    }

    /**
     * Cleanup this specific cache - shrink if underutilized.
     */
    private void cleanup() {
        int origsz = size();
        if (origsz > 1 && wasUnderfilled(EXPIRE_PERIOD)) {
            int toRemove = origsz / 2;
            shrink(origsz - toRemove);
        }
        I2PAppContext.getGlobalContext().statManager().addRateData("byteCache.memory." + _entrySize, (long) _entrySize * origsz);
    }

    @SuppressWarnings("PMD.SingletonClassReturningNewInstance")
    public static ByteCache getInstance(int cacheSize, int size) {
        size = Math.max(1, size);
        cacheSize = Math.max(0, cacheSize);
        long totalBytes = (long) cacheSize * size;

        if (totalBytes > MAX_CACHE) {
            cacheSize = Math.max(MIN_CACHE_OBJECTS, MAX_CACHE / size);
        }

        Integer key = size;
        synchronized (_caches) {
            ByteCache cache = _caches.get(key);
            if (cache == null) {
                cache = new ByteCache(cacheSize, size);
                _caches.put(key, cache);
            } else {
                cache.resize(cacheSize); // Only resize if it already exists
            }

            if (_log.shouldInfo()) {
                _log.info("ByteCache for " + (size / 1024) + "KB cache -> Max objects: " + cacheSize);
            }

            return cache;
        }
    }

    /**
     *  Clear everything (memory pressure)
     *  @since 0.7.14
     */
    public static void clearAll() {
        for (ByteCache bc : _caches.values()) {
            bc.clear();
        }
        if (_log.shouldWarn()) {
            _log.warn("WARNING: Low memory, clearing byte caches...");
        }
    }

    private final int _entrySize;

    /** @since 0.9.36 */
    private static class ByteArrayFactory implements TryCache.ObjectFactory<ByteArray> {
        private final int sz;

        ByteArrayFactory(int entrySize) {
            sz = entrySize;
        }

        @Override
        public ByteArray newInstance() {
            byte data[] = new byte[sz];
            ByteArray rv = new ByteArray(data);
            rv.setValid(0);
            return rv;
        }
    }

    private ByteCache(int maxCachedEntries, int entrySize) {
        super(new ByteArrayFactory(entrySize), maxCachedEntries);
        _entrySize = entrySize;
        synchronized (_allCaches) {
            _allCaches.add(this);
        }
        I2PAppContext.getGlobalContext().statManager().createRateStat("byteCache.memory." + entrySize, "Memory usage (B)", "Router [ByteCache]", new long[] {60 * 1000, 10 * 60 * 1000, 24 * 60 * 60 * 1000});
    }

    /**
     * Resize the cache to a new capacity.
     * @param maxCachedEntries the new maximum number of entries to cache
     */
    public void resize(int maxCachedEntries) {
        // TryCache doesn't support dynamic resize, but we can create a new one
        // For now, this is a no-op as TryCache uses a fixed-size ConcurrentLinkedDeque
        // To fully support resize, TryCache would need modification
    }

    /**
     * Put this structure back onto the available cache for reuse
     *
     */
    @Override
    public final void release(ByteArray entry) {
        release(entry, true);
    }

    public final void release(ByteArray entry, boolean shouldZero) {
        if (entry == null || entry.getData() == null) {
            return;
        }
        if (entry.getData().length != _entrySize) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(ByteCache.class);
            if (log.shouldDebug()) {
                log.debug("Size of ByteCache entry is incorrect", new Exception("Stacktrace:"));
            } else if (log.shouldWarn()) {
                log.warn("Size of ByteCache entry is incorrect");
            }
            return;
        }
        entry.setValid(0);
        entry.setOffset(0);

        if (shouldZero) {
            Arrays.fill(entry.getData(), (byte) 0x0);
        }
        super.release(entry);
    }
}
