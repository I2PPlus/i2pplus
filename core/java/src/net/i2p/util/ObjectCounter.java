package net.i2p.util;

import java.io.Serializable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe counter for tracking occurrences of objects.
 * 
 * <p>This class provides a concurrent way to count how many times specific
 * objects appear. It uses ConcurrentHashMap and AtomicInteger to ensure
 * thread-safe operations in multi-threaded environments.</p>
 * 
 * <p>Typical use cases include:</p>
 * <ul>
 * <li>Counting message frequencies</li>
 * <li>Tracking request rates by client</li>
 * <li>Monitoring event occurrences</li>
 * <li>Statistical data collection</li>
 * </ul>
 *
 * @author zzz, welterde
 * @param <K> type of objects being counted
 */
public class ObjectCounter<K> implements Serializable {
    /**
     * Serializable so it can be passed in an Android Bundle
     */
    private static final long serialVersionUID = 3160378641721937421L;

    private final ConcurrentHashMap<K, AtomicInteger> map;
    private final int maxSize;
    private volatile boolean full;

    public ObjectCounter() {
        this(1024);
    }

    /**
     * @param maxSize Maximum number of entries before oldest are evicted
     * @since 0.9.68+
     */
    public ObjectCounter(int maxSize) {
        this.map = new ConcurrentHashMap<K, AtomicInteger>();
        this.maxSize = maxSize;
        this.full = false;
    }

    /**
     *  Add one.
     *  @return count after increment
     */
    public int increment(K h) {
        AtomicInteger i = map.putIfAbsent(h, new AtomicInteger(1));
        if (i != null) {
            int rv = i.incrementAndGet();
            // Evict oldest entries if we're over max size (simple strategy: clear all)
            int size = map.size();
            if (size > maxSize && !full) {
                full = true;
                clear();
                full = false;
            }
            return rv;
        }
        return 1;
    }

    /**
     *  Set a high value
     *  @since 0.9.56
     */
    public void max(K h) {
        map.put(h, new AtomicInteger(Integer.MAX_VALUE / 2));
    }

    /**
     *  @return current count
     */
    public int count(K h) {
        AtomicInteger i = this.map.get(h);
        if (i != null)
            return i.get();
        return 0;
    }

    /**
     *  @return set of objects with counts &gt; 0
     */
    public Set<K> objects() {
        return this.map.keySet();
    }

    /**
     *  Start over. Reset the count for all keys to zero.
     *  @since 0.7.11
     */
    public void clear() {
        this.map.clear();
    }

    /**
     *  Reset the count for this key to zero
     *  @since 0.9.36
     */
    public void clear(K h) {
        this.map.remove(h);
    }
}

