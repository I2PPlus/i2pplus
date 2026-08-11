package net.i2p.router.util;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Singleton set with removable element support and iterator functionality.
 * <p>
 * Similar to Collections.singleton() but provides the ability to
 * remove the single element and clear the set. Iterator supports
 * remove() operations while maintaining singleton behavior.
 * <p>
 * Does not support add() or addAll() operations as they would
 * violate the singleton contract. Item may not be null.
 * <p>
 * Useful for scenarios requiring a single, modifiable element
 * with standard collection interface compliance and removal capabilities.
 *
 * @param <E>  type of element in this set
 * @since 0.9.7
 */
public class RemovableSingletonSet<E> extends AbstractSet<E> {
    private volatile E _elem;

    /**
     * RemovableSingletonSet.
     */
    public RemovableSingletonSet(E element) {
        if (element == null)
            throw new NullPointerException();
        _elem = element;
    }

    /**
     * Remove the single element.
     */
    @Override
    public void clear() {
        _elem = null;
    }

    /**
     * Whether the set contains the given element.
     */
    @Override
    public boolean contains(Object o) {
        return o != null && o.equals(_elem);
    }

    /**
     * Whether the set is empty.
     *
     * @return whether empty
     */
    @Override
    public boolean isEmpty() {
        return _elem == null;
    }

    /**
     * Remove the element if it matches.
     */
    @Override
    public boolean remove(Object o) {
        boolean rv = o.equals(_elem);
        if (rv)
            _elem = null;
        return rv;
    }

    /**
     * Number of elements, 0 or 1.
     */
    @Override
    public int size() {
        return _elem != null ? 1 : 0;
    }

    /**
     * Iterator over the single element.
     */
    public Iterator<E> iterator() {
        return new RSSIterator();
    }

    private class RSSIterator implements Iterator<E> {
        boolean done;

        /**
         * Whether the next element is present.
         *
         * @return whether next is present
         */
        @Override
        public boolean hasNext() {
            return _elem != null && !done;
        }

        /**
         * Return the single element.
         */
        public E next() {
            if (!hasNext())
                throw new NoSuchElementException();
            done = true;
            return _elem;
        }

        /**
         * Remove the single element after next() has been called.
         */
        public void remove() {
            if (_elem == null || !done)
                throw new IllegalStateException();
            _elem = null;
        }
    }
}
