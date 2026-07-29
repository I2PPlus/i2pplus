package net.i2p.router.util;

import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * ArrayList with per-thread cached iterators for reduced object allocation overhead.
 * <p>
 * Extends ArrayList to provide per-thread reusable iterator instances,
 * avoiding the creation of new iterator objects during repeated
 * iteration operations. This reduces garbage collection pressure
 * and improves performance for frequently traversed collections.
 * <p>
 * Each thread gets its own cached iterator to allow safe concurrent
 * iteration. Iterator state is maintained between iterations to support the
 * standard iterator contract while allowing remove() operations
 * with proper fail-fast behavior on concurrent modifications.
 *
 * @param <E>  type of elements in this list
 * @since 0.9.4 moved from net.i2p.util in 0.9.24
 * @author zab
 */
@SuppressWarnings("java:S2975")
public class CachedIteratorArrayList<E> extends ArrayList<E> {

    private static final long serialVersionUID = 4863212596318574111L;

    /** Thread local */
    private volatile ThreadLocal<CachedIterator<E>> iterator = ThreadLocal.withInitial(() -> new CachedIterator<>());

    /** Creates a new empty CachedIteratorArrayList. */
    public CachedIteratorArrayList() {
        super();
    }

    /**
     * Creates a CachedIteratorArrayList containing the elements of the given collection.
     * @param c the collection whose elements are to be placed into this list
     */
    public CachedIteratorArrayList(Collection<? extends E> c) {
        super(c);
    }

    /**
     * Returns a shallow copy of this list.
     */
    @Override
    public CachedIteratorArrayList clone() {
        return (CachedIteratorArrayList) super.clone();
    }

    /**
     * Creates a CachedIteratorArrayList with the specified initial capacity.
     * @param initialCapacity the initial capacity of the list
     */
    public CachedIteratorArrayList(int initialCapacity) {
        super(initialCapacity);
    }

    /**
     * Returns a per-thread cached iterator over the elements in this list.
     */
    @Override
    public Iterator<E> iterator() {
        ThreadLocal<CachedIterator<E>> tl = this.iterator;
        if (tl == null) {
            tl = ThreadLocal.withInitial(() -> new CachedIterator<>());
            this.iterator = tl;
        }
        CachedIterator<E> it = tl.get();
        it.reset(this);
        return it;
    }

    /**
     * Clear the list and release the ThreadLocal so its entries can be
     * reclaimed from all threads' ThreadLocalMaps.
     */
    @Override
    public void clear() {
        super.clear();
        ThreadLocal<CachedIterator<E>> tl = this.iterator;
        if (tl != null) {
            tl.remove();
            this.iterator = null;
        }
    }

    private static class CachedIterator<E> implements Iterator<E>, Serializable {
        /**
         * Index of element to be returned by subsequent call to next.
         */
        int cursor = 0;

        /**
         * Index of element returned by most recent call to next or
         * previous.  Reset to -1 if this element is deleted by a call
         * to remove.
         */
        int lastRet = -1;

        /**
         * The modCount value that the iterator believes that the backing
         * List should have.  If this expectation is violated, the iterator
         * has detected concurrent modification.
         */
        int expectedModCount;

        /** Reference to the owning list, set on each call to iterator() */
        private transient WeakReference<CachedIteratorArrayList<E>> listRef;

        /** Reset iterator state for the given list. */
        void reset(CachedIteratorArrayList<E> list) {
            this.listRef = new WeakReference<CachedIteratorArrayList<E>>(list);
            cursor = 0;
            lastRet = -1;
            expectedModCount = list.modCount;
        }

        private CachedIteratorArrayList<E> list() {
            CachedIteratorArrayList<E> l = listRef != null ? listRef.get() : null;
            if (l == null)
                throw new ConcurrentModificationException();
            return l;
        }

        /**
         * Whether there is a next element available.
         *
         * @return whether next is present
         */
        public boolean hasNext() {
            CachedIteratorArrayList<E> l = listRef != null ? listRef.get() : null;
            return l != null && cursor != l.size();
        }

        /**
         * Returns the next element in the iteration.
         *
         * @return the next element
         */
        public E next() {
            CachedIteratorArrayList<E> l = list();
            checkForComodification(l);
            int i = cursor;
            if (i >= l.size())
                throw new NoSuchElementException();
            E next = l.get(i);
            lastRet = i;
            cursor = i + 1;
            return next;
        }

        /**
         * Removes from the underlying list the last element returned by next().
         */
        public void remove() {
            if (lastRet < 0)
                throw new IllegalStateException();
            CachedIteratorArrayList<E> l = list();
            checkForComodification(l);

            try {
                l.remove(lastRet);
                if (lastRet < cursor)
                    cursor--;
                lastRet = -1;
                expectedModCount = l.modCount;
            } catch (IndexOutOfBoundsException e) {
                throw new ConcurrentModificationException();
            }
        }

        final void checkForComodification(CachedIteratorArrayList<E> l) {
            if (l.modCount != expectedModCount)
                throw new ConcurrentModificationException();
        }

    }

}
