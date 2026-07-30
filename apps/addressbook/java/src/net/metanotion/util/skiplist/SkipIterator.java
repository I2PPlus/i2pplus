package net.metanotion.util.skiplist;
// License: BSD-3-Clause. See docs/LICENSES.md

import java.util.ListIterator;
import java.util.NoSuchElementException;

/** A basic iterator for a skip list.
    This is not a complete ListIterator, in particular, since the
    skip list is a map and is therefore indexed by Comparable objects instead
    of int's, nextIndex and previousIndex methods are not really relevant.

    To be clear, this is an iterator through values.
    To get the key, call nextKey() BEFORE calling next().
 *
 * @param <K> type of keys maintained by this iterator
 * @param <V> type of values returned by this iterator
 */
public class SkipIterator<K extends Comparable<? super K>, V> implements ListIterator<V> {
    /** The current span being iterated over. */
    protected SkipSpan<K, V> ss;
    /** Current index position. */
    protected int index;

    /** Constructor for subclass use. */
    protected SkipIterator() {
        // Protected constructor for subclasses
    }

    /**
     * Constructor.
     *
     * @param ss the span to iterate over
     * @param index starting position
     */
    public SkipIterator(SkipSpan<K, V> ss, int index) {
        if(ss==null) { throw new NullPointerException(); }
        this.ss = ss;
        this.index = index;
    }

/**
 * @return whether next is present
 */
public boolean hasNext() {
        return index < ss.nKeys;
    }

    /**
     * @return the next value, and advances the index
     * @throws NoSuchElementException
     */
    public V next() {
        V o;
        if(index < ss.nKeys) {
            o = ss.vals[index];
        } else {
            throw new NoSuchElementException();
        }

        if(index < (ss.nKeys-1)) {
            index++;
        } else if(ss.next != null) {
            ss = ss.next;
            index = 0;
        } else {
            index = ss.nKeys;
        }
        return o;
    }

    /**
         * The key. Does NOT advance the index.
     * @return the key for which the value will be returned in the subsequent call to next()
     * @throws NoSuchElementException
     */
    public K nextKey() {
        if(index < ss.nKeys) { return ss.keys[index]; }
        throw new NoSuchElementException();
    }

/**
 * @return whether previous is present
 */
public boolean hasPrevious() {
        return index > 0 || ((ss.prev != null) && (ss.prev.nKeys > 0));
    }

    /**
     * @return the previous value, and decrements the index
     * @throws NoSuchElementException
     */
    public V previous() {
        if(index > 0) {
            index--;
        } else if(ss.prev != null) {
            ss = ss.prev;
            if(ss.nKeys <= 0) { throw new NoSuchElementException(); }
            index = (ss.nKeys - 1);
        }
        return ss.vals[index];
    }

    // Optional ListIterator methods - all unsupported
    /** Not supported. */
    public void add(V o) { throw new UnsupportedOperationException(); }
    /** Not supported. */
    public void remove() { throw new UnsupportedOperationException(); }
    /** Not supported. */
    public void set(V o) { throw new UnsupportedOperationException(); }
    /** Not supported. */
    public int nextIndex() { throw new UnsupportedOperationException(); }
    /** Not supported. */
    public int previousIndex() { throw new UnsupportedOperationException(); }

}
