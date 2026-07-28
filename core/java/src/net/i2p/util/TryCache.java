package net.i2p.util;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A thread-safe, lock-free-ish object cache that reuses objects to reduce allocation overhead.
 * Designed to avoid object loss due to lock contention.
 *
 * @param <T> type of objects cached
 * @author zab
 */
public class TryCache<T> {

    private static final boolean DEBUG_DUP = false;

    /**
     * Factory interface for creating new objects in TryCache.
     *
     * @param <T> the type of objects created by this factory
     * @since 0.9.35
     */
    public interface ObjectFactory<T> {
        /** Create instance */
        T newInstance();
    }

    private final ObjectFactory<T> factory;
    private volatile int capacity;
    private final ConcurrentLinkedDeque<T> items = new ConcurrentLinkedDeque<>();
    private volatile long _lastUnderflow = System.currentTimeMillis();
    private volatile long _acquireCount;
    private volatile long _missCount;
    private volatile long _releaseCount;
    private volatile long _discardCount;

    /**
     * TryCache.
     */
    public TryCache(ObjectFactory<T> factory, int capacity) {
        this.factory = factory;
        this.capacity = Math.max(0, capacity);
    }

    /**
     * Acquire an object from the cache or create a new one.
     *
     * @return a cached or newly created object
     */
    public T acquire() {
        T item = items.pollLast();
        if (item == null) {
            _lastUnderflow = System.currentTimeMillis();
            _missCount++;
            item = factory.newInstance();
        }
        _acquireCount++;
        return item;
    }

    /**
     * Try to return the item to the cache.
     * If the cache is full, the item is discarded.
     *
     * @param item the item
     */
    public void release(T item) {
        if (DEBUG_DUP) {
            for (T existing : items) {
                if (existing == item) { // NOPMD - CompareObjectsWithEquals (identity check for duplicate release)
                    net.i2p.I2PAppContext.getGlobalContext().logManager().getLog(TryCache.class).log(Log.CRIT, "Duplicate release of " + item.getClass(), new Exception("Duplicate release"));
                    return;
                }
            }
        }

        // If full, discard the object
        if (items.size() >= capacity) {
            _discardCount++;
            return;
        }

        items.add(item);
        _releaseCount++;
    }

    /** Clear all cached items. */
    public void clear() {
        items.clear();
    }

    /**
     * Get the current number of cached items.
     *
     * @return the current number of cached items
     */
    public int size() {
        return items.size();
    }

    /**
     * Check if the cache has been underfilled longer than the threshold.
     *
     * @param thresholdMillis the threshold in milliseconds
     * @return true if the cache has been underfilled longer than the threshold
     */
    public boolean wasUnderfilled(long thresholdMillis) {
        return (System.currentTimeMillis() - _lastUnderflow) > thresholdMillis;
    }

    /**
     * Shrink the cache to the target size, evicting excess items.
     *
     * @param targetSize the desired maximum number of items
     */
    public void shrink(int targetSize) {
        while (items.size() > targetSize) {
            items.pollLast();
        }
    }

    /**
     * Resize the cache to a new capacity.
     * If shrinking, excess items are evicted. If growing, the cache
     * simply allows more items to accumulate on future releases.
     *
     * @param newCapacity the new maximum number of entries
     * @since 0.9.70+
     */
    public void resize(int newCapacity) {
        newCapacity = Math.max(0, newCapacity);
        int oldCapacity = capacity;
        capacity = newCapacity;
        // If shrinking, evict excess items
        if (newCapacity < oldCapacity) {
            shrink(newCapacity);
        }
    }

    /**
     * Get the timestamp of the last underflow.
     *
     * @return the timestamp of the last underflow
     */
    public long getLastUnderflowTime() {
        return _lastUnderflow;
    }

    /**
     * Return the current cache capacity.
     *
     * @return the maximum number of cached items
     * @since 0.9.70+
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Return the number of acquire() calls that found an empty cache
     * (misses required allocating a new object).
     *
     * @return the miss count
     * @since 0.9.70+
     */
    public long getMissCount() {
        return _missCount;
    }

    /**
     * Return the number of release() calls that were discarded because
     * the cache was full.
     *
     * @return the discard count
     * @since 0.9.70+
     */
    public long getDiscardCount() {
        return _discardCount;
    }

    /**
     * Return total acquire() count (hits + misses).
     *
     * @return the acquire count
     * @since 0.9.70+
     */
    public long getAcquireCount() {
        return _acquireCount;
    }

    /**
     * Return total release() count (returned to cache).
     *
     * @return the release count
     * @since 0.9.70+
     */
    public long getReleaseCount() {
        return _releaseCount;
    }
}
