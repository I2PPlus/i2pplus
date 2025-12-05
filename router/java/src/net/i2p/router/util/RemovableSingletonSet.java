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
 * Thread-safe implementation without synchronization overhead.
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
    private E _elem;

    public RemovableSingletonSet(E element) {
        if (element == null)
            throw new NullPointerException();
        _elem = element;
    }

    @Override
    public void clear() {
        _elem = null;
    }

    @Override
    public boolean contains(Object o) {
        return o != null && o.equals(_elem);
    }

    @Override
    public boolean isEmpty() {
        return _elem == null;
    }

    @Override
    public boolean remove(Object o) {
        boolean rv = o.equals(_elem);
        if (rv)
            _elem = null;
        return rv;
    }

    public int size() {
        return _elem != null ? 1 : 0;
    }

    public Iterator<E> iterator() {
        return new RSSIterator();
    }

    private class RSSIterator implements Iterator<E> {
        boolean done;

        public boolean hasNext() {
            return _elem != null && !done;
        }

        public E next() {
            if (!hasNext())
                throw new NoSuchElementException();
            done = true;
            return _elem;
        }

        public void remove() {
            if (_elem == null || !done)
                throw new IllegalStateException();
            _elem = null;
        }
    }
}

