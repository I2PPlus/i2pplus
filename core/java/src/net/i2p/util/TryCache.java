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
        T newInstance();
    }

    private final ObjectFactory<T> factory;
    private final int capacity;
    private final ConcurrentLinkedDeque<T> items = new ConcurrentLinkedDeque<>();
    private volatile long _lastUnderflow = System.currentTimeMillis();

    public TryCache(ObjectFactory<T> factory, int capacity) {
        this.factory = factory;
        this.capacity = Math.max(0, capacity);
    }

    /**
     * Acquire an object from the cache or create a new one.
     */
    public T acquire() {
        T item = items.pollLast();
        if (item == null) {
            _lastUnderflow = System.currentTimeMillis();
            item = factory.newInstance();
        }
        return item;
    }

    /**
     * Try to return the item to the cache.
     * If the cache is full, the item is discarded.
     */
    public void release(T item) {
        if (DEBUG_DUP) {
            for (T existing : items) {
                if (existing == item) {
                    net.i2p.I2PAppContext.getGlobalContext().logManager().getLog(TryCache.class).log(Log.CRIT,
                        "Duplicate release of " + item.getClass(), new Exception("Duplicate release"));
                    return;
                }
            }
        }

        // If full, discard the object
        if (items.size() >= capacity) {
            return;
        }

        items.add(item);
    }

    /**
     * Clear all cached items.
     * This is best-effort; some may still be in flight.
     */
    public void clear() {
        items.clear();
    }

    public int size() {
        return items.size();
    }

    public boolean wasUnderfilled(long thresholdMillis) {
        return (System.currentTimeMillis() - _lastUnderflow) > thresholdMillis;
    }

    public void shrink(int targetSize) {
        while (items.size() > targetSize) {
            items.pollLast();
        }
    }

    public long getLastUnderflowTime() {
        return _lastUnderflow;
    }
}