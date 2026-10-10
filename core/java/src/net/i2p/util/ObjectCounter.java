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
 *
 * @param <K> type of objects being counted
 */
public class ObjectCounter<K> implements Serializable {
    /**
     * Serializable so it can be passed in an Android Bundle
     */
    private static final long serialVersionUID = 3160378641721937421L;

    /** Object counter storage keyed by tracked item. */
    private final ConcurrentHashMap<K, AtomicInteger> map;

    /**
     * Start an empty counter; nothing is tracked until something is incremented.
     */
    public ObjectCounter() {
        this.map = new ConcurrentHashMap<>();
    }

    /**
     * Add one.
     *
     * @param h the object whose occurrence count is to be advanced by one
     * @return count after increment
     */
    public int increment(K h) {
        AtomicInteger i = this.map.putIfAbsent(h, new AtomicInteger(1));
        if (i != null) return i.incrementAndGet();
        return 1;
    }

    /**
     * Jump the count straight to a high value instead of incrementing to it.
     *
     * @param h the object whose count is pinned high so later increments cannot overflow
     * @since 0.9.56
     */
    public void max(K h) {
        map.put(h, new AtomicInteger(Integer.MAX_VALUE / 2));
    }

    /**
     * How many times this object has been counted, without changing the count.
     *
     * @param h the object whose count is to be read; 0 if it has never been counted
     * @return current count
     */
    public int count(K h) {
        AtomicInteger i = this.map.get(h);
        if (i != null) return i.get();
        return 0;
    }

    /**
     * Every object currently held, which is the live key set rather than a copy,
     * so it reflects later increments and clears as they happen.
     *
     * @return set of objects with counts &gt; 0
     */
    public Set<K> objects() {
        return this.map.keySet();
    }

    /**
     * Start over. Reset the count for all keys to zero.
     *
     * @since 0.7.11
     */
    public void clear() {
        this.map.clear();
    }

    /**
     * Reset the count for this key to zero
     *
     * @param h the object to drop, leaving later reads of it at 0
     * @since 0.9.36
     */
    public void clear(K h) {
        this.map.remove(h);
    }

    /**
     * Decay all counts by the given factor (integer division).
     * Removes entries that decay to zero.
     *
     * @param factor divisor for decay (e.g., 2 for 50% decay)
     * @since 0.9.69+
     */
    public void decay(int factor) {
        if (factor <= 1) return;
        this.map.entrySet().removeIf(entry -> {
            AtomicInteger ai = entry.getValue();
            int current = ai.get();
            if (current <= 0) return true;
            ai.set(current / factor);
            return ai.get() == 0;
        });
    }
}
