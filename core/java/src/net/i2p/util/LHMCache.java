package net.i2p.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 *  A LinkedHashMap with a maximum size, for use as
 *  an LRU cache. Unsynchronized.
 *
 *  @since 0.9.3
 *
 *  @param <K> type of keys in this cache
 *  @param <V> type of values in this cache
 */
public class LHMCache<K, V> extends LinkedHashMap<K, V> {
    /**  max */
    private final int _max;

    /**
     * Bounded cache with LRU eviction.
     *
     * @param max maximum entries before oldest are evicted
     */
    public LHMCache(int max) {
        super(max, 0.75f, true);
        _max = max;
    }

    /**
     * Shallow clone.
     */
    @Override
    @SuppressWarnings("unchecked")
    public LHMCache<K, V> clone() {
        return (LHMCache<K, V>) super.clone();
    }

    /**
     * Evict eldest when over capacity.
     */
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > _max;
    }
}
