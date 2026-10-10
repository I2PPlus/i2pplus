package net.metanotion.util.skiplist;
// License: BSD-3-Clause. See docs/LICENSES.md

import java.io.Flushable;
import java.util.Random;
import net.i2p.util.RandomSource;

/**
 * Skip list data structure implementation for efficient key-value storage and retrieval.
 * Provides O(log n) average time complexity for search, insertion, and deletion operations.
 *
 * @param <K> type of keys, must be comparable
 * @param <V> type of values
 */
public class SkipList<K extends Comparable<? super K>, V> implements Flushable, Iterable<V> {
    /** The probability of each next higher level. */
    protected static final int P = 2;
    private static final int MIN_SLOTS = 4;
    // these two are really final
    /** First span in the list. */
    protected SkipSpan<K, V> first;
    /** Level stack above the first span. */
    protected SkipLevels<K, V> stack;
    // I2P mod
    /** Random number generator. */
    public static final Random rng = RandomSource.getInstance();

    /** Number of items in the list. */
    protected int size;

    /** Flush any pending writes. */
    public void flush() { /* no-op */ }
    /**
     * Constructor for subclass use.
     */
    protected SkipList() {
        // Protected constructor for subclasses
    }

    /**
     * Create a new skip list.
     *
     * @param span span size
     * @throws IllegalArgumentException if size too big or too small
     */
    public SkipList(int span) {
        if(span < 1 || span > SkipSpan.MAX_SIZE)
            throw new IllegalArgumentException("Invalid span size");
        first = new SkipSpan<>(span);
        stack = new SkipLevels<>(1, first);
    }

    /**
     * Return the number of items.
     *
     * @return the number of entries currently held in the list
     */
    public int size() { return size; }

    /** Increment item count. */
    public void addItem() {
        size++;
    }

    /**
     * Decrement item count, minimum zero.
     */
    public void delItem() {
        if (size > 0)
               size--;
    }

    /**
     * Fix if for some reason you want a huge in-memory skiplist.
     *
     * @return 4 since we don't track span count here any more - see override
     */
    public int maxLevels() {
        return MIN_SLOTS;
    }

    /**
     * Choose the column height for a newly inserted node, using the geometric
     * distribution that keeps the list's search cost logarithmic.
     *
     * @return 0..maxLevels(), each successive one with probability 1 / P
     */
    public int generateColHeight() {
        int bits = rng.nextInt();
        int max = maxLevels();
        for(int res = 0; res < max; res++) {
            if (bits % P == 0)
                return res;
            bits /= P;
        }
        return max;
    }

    /**
     * Insert or update a key-value pair in the skip list.
     *
     * @param key the key to insert, or whose existing entry is to be replaced
     * @param val the value stored under that key
     */
    @SuppressWarnings("unchecked")
    public void put(K key, V val)   {
        if(key == null) { throw new NullPointerException(); }
        if(val == null) { throw new NullPointerException(); }
        SkipLevels<K, V> slvls = stack.put(stack.levels.length - 1, key, val, this);
        if(slvls != null) {
            // grow our stack
            SkipLevels<K, V>[] levels = (SkipLevels<K, V>[]) new SkipLevels[slvls.levels.length];
            for(int i=0;i < slvls.levels.length; i++) {
                if(i < stack.levels.length) {
                    levels[i] = stack.levels[i];
                } else {
                    levels[i] = slvls;
                }
            }
            stack.levels = levels;
            stack.flush();
            flush();
        }
    }

    /**
     * Remove a key-value pair from the skip list.
     *
     * @param key the key whose entry is to be removed
     * @return the previous value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public V remove(K key) {
        if(key == null) { throw new NullPointerException(); }
        Object[] res = stack.remove(stack.levels.length - 1, key, this);
        if(res != null) {
            if(res[1] != null) {
                SkipLevels<K, V> slvls = (SkipLevels<K, V>) res[1];
                for(int i=0;i < slvls.levels.length; i++) {
                    if(stack.levels[i] == slvls) {
                        stack.levels[i] = slvls.levels[i];
                    }
                }
                stack.flush();
            }
            flush();
            return (V) res[0];
        }
        return null;
    }

    /**
     * Get the value for a key.
     *
     * @param key the key whose stored value is wanted
     * @return the value, or null if not found
     */
    public V get(K key) {
        if(key == null) { throw new NullPointerException(); }
        return stack.get(stack.levels.length - 1, key);
    }

    /**
     * Return an iterator over all entries.
     */
    public SkipIterator<K, V> iterator() { return new SkipIterator<>(first, 0); }

    /**
     * Look up the first stored key greater than or equal to the one given,
     * returning an iterator already positioned there.
     *
     * @param key the key to position the iterator at
     * @return an iterator where nextKey() is the first one greater than or equal to 'key'
     */
    public SkipIterator<K, V> find(K key) {
        int[] search = new int[1];
        SkipSpan<K, V> ss = stack.getSpan(stack.levels.length - 1, key, search);
        if(search[0] < 0) { search[0] = -1 * (search[0] + 1); }
        return new SkipIterator<>(ss, search[0]);
    }

    // Levels adjusted to guarantee O(log n) search
    // This is expensive proportional to the number of spans.
    /** Rebalance the skip list levels. */
    public void balance() {
        // TODO Skip List Balancing Algorithm
    }

}
