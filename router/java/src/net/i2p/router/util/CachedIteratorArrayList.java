package net.i2p.router.util;

import java.io.Serializable;
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

    private final ThreadLocal<CachedIterator> iterator = ThreadLocal.withInitial(() -> new CachedIterator());

    public CachedIteratorArrayList() {
        super();
    }

    public CachedIteratorArrayList(Collection<? extends E> c) {
        super(c);
    }

    @Override
    public Object clone() {
        return super.clone();
    }

    public CachedIteratorArrayList(int initialCapacity) {
        super(initialCapacity);
    }

    @Override
    public Iterator<E> iterator() {
        CachedIterator it = iterator.get();
        it.reset();
        return it;
    }

    private class CachedIterator implements Iterator<E>, Serializable {
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
        int expectedModCount = modCount;

        void reset() {
            cursor = 0;
            lastRet = -1;
            expectedModCount = modCount;
        }

        public boolean hasNext() {
            return cursor != size();
        }

        public E next() {
            checkForComodification();
            int i = cursor;
            if (i >= size())
                throw new NoSuchElementException();
            E next = get(i);
            lastRet = i;
            cursor = i + 1;
            return next;
        }

        public void remove() {
            if (lastRet < 0)
                throw new IllegalStateException();
            checkForComodification();

            try {
                CachedIteratorArrayList.this.remove(lastRet);
                if (lastRet < cursor)
                    cursor--;
                lastRet = -1;
                expectedModCount = modCount;
            } catch (IndexOutOfBoundsException e) {
                throw new ConcurrentModificationException();
            }
        }

        final void checkForComodification() {
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
        }

    }

}
