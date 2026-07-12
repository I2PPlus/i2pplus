// The Node class below is derived from Java's LinkedList.java
/*
 * Copyright (c) 1997, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package net.i2p.router.util;

import java.util.AbstractCollection;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Synchronized collection with per-thread cached iterators for efficient
 * element iteration.
 * <p>
 * Extends AbstractCollection to provide a collection that can be iterated
 * without creating new iterator objects per call. Uses a linked list
 * structure with per-thread cached iterator instances to minimize object
 * churn during frequent iteration operations.
 * <p>
 * All mutation and iteration methods are synchronized for thread safety.
 * Nested iteration from the same thread allocates a new iterator.
 *
 * @param <E>  type of elements in this collection
 * @since 0.9.36
 */

public class CachedIteratorCollection<E> extends AbstractCollection<E> {

    // FOR DEBUGGING & LOGGING PURPOSES
    //Log log = I2PAppContext.getGlobalContext().logManager().getLog(CachedIteratorCollection.class);

    // Thread-local iterators to avoid concurrent modification when multiple threads iterate.
    // Each thread's iterator holds an explicit reference back to this collection, so
    // clear() sets _cleared to prevent stale iterators from holding PeerStates alive.
    private volatile ThreadLocal<CachedIterator<E>> _iterator = ThreadLocal.withInitial(() -> new CachedIterator<>(this));

    // Set to true in clear() to signal all iterator instances the collection is gone.
    // ThreadLocalMap entries for other threads will be cleaned lazily via WeakRef key.
    volatile boolean _cleared;

    // Size of the AbstractCollectionTest object
    int size;

    /**
     * Node object that contains:
     * (1) Data object
     * (2) Link to previous Node object
     * (3) Link to next Node object
     */
    private static class Node<E> {
        E item;
        Node<E> next;
        Node<E> prev;

        Node(Node<E> prev, E element) {
            this.item = element;
            this.prev = prev;
            this.next = null;
        }
    }

    // First Node in the AbstractCollectionTest object
    Node<E> first;

    // Last Node in the AbstractCollectionTest object
    Node<E> last;

    /**
     * Default constructor
     */
    public CachedIteratorCollection() {
        // Intentionally empty - default constructor
    }

    /**
     * Adds a data object (element) as a Node and sets previous/next pointers accordingly
     *
     */
    @Override
    public synchronized boolean add(E element) {
        final Node<E> newNode = new Node<>(last, element);
        if (this.size == 0) {
            this.first = newNode;
        } else {
            this.last.next = newNode;
        }
        this.last = newNode;
        this.size++;
        return true;
    }

    /**
     *  Clears the collection, all pointers reset to 'null'
     *
     */
    @Override
    public synchronized void clear() {
        this.first = null;
        this.last = null;
        this.size = 0;
        this._cleared = true;
        _iterator.remove();
        _iterator = null;
    }

    /**
     *  Remove the first element matching by identity (==).
     *  Used by PeerState.acked() to avoid iterating with early-exit break.
     *
     *  @return true if removed
     *  @since 0.9.70+
     */
    public synchronized boolean remove(Object o) {
        for (Node<E> x = first; x != null; x = x.next) {
            if (x.item == o) {
                unlink(x);
                return true;
            }
        }
        return false;
    }

    /**
     *  Unlink a node from the doubly-linked list.
     */
    private void unlink(Node<E> x) {
        Node<E> prev = x.prev;
        Node<E> next = x.next;
        if (prev == null) {
            first = next;
        } else {
            prev.next = next;
            x.prev = null;
        }
        if (next == null) {
            last = prev;
        } else {
            next.prev = prev;
            x.next = null;
        }
        x.item = null;
        size--;
    }

    /**
     *  Reset the current thread's cached iterator so it is no longer
     *  considered "in use".  Call this before any early return from an
     *  iteration loop that may not exhaust the iterator.
     *
     *  @since 0.9.70+
     */
    public void releaseCurrentThreadIterator() {
        ThreadLocal<CachedIterator<E>> tl = _iterator;
        if (tl == null)
            return;
        CachedIterator<E> it = tl.get();
        it.itrIndexNode = null;
    }

    /**
     * Returns a cached iterator over the elements in this collection.
     *
     * Each thread gets its own iterator instance that is reused and reset on each call.
     * If the current thread's cached iterator is still in use (hasNext() not yet exhausted),
     * a new iterator is allocated to allow safe nested iteration.
     *
     * @return a {@link CachedIterator} instance, reset to the beginning
     */
    @Override
    public synchronized Iterator<E> iterator() {
        ThreadLocal<CachedIterator<E>> tl = _iterator;
        if (tl == null)
            return Collections.emptyIterator();
        CachedIterator<E> it = tl.get();
        if (it.inUse()) {
            CachedIterator<E> nested = new CachedIterator<>(this);
            nested.reset();
            return nested;
        }
        it.reset();
        return it;
    }

    /**
     *  Static inner CachedIterator class - implements hasNext(), next() &amp; remove()
     *  <p>
     *  Static to avoid holding an implicit {@code this$0} reference to the enclosing
     *  collection, which would prevent GC of the collection (and its owning PeerState)
     *  when held in another thread's ThreadLocal after {@link CachedIteratorCollection#clear()} is called.
     *
     */
    @SuppressWarnings("ReferenceEquality")
    public static class CachedIterator<E> implements Iterator<E> {

        private final CachedIteratorCollection<E> coll;
        private boolean nextCalled;

        // Iteration Index
        private Node<E> itrIndexNode;

        CachedIterator(CachedIteratorCollection<E> coll) {
            this.coll = coll;
        }

        /**
         * Reset iteration
         */
        private void reset() {
            itrIndexNode = coll.first;
            nextCalled = false;
        }

        /**
         * @return true if this iterator is in the middle of an iteration
         */
        private boolean inUse() {
            return itrIndexNode != null;
        }

        /**
         *  If nextCalled is true (i.e. next() has been called at least once),
         *  remove() will remove the last returned Node
         *
         */
        @Override
        public void remove() {
            if (coll._cleared)
                throw new IllegalStateException();
            if (nextCalled) {
                // Are we at the end of the collection? If so itrIndexNode will
                // be null
                if (itrIndexNode != null) {
                    // The Node we are trying to remove is itrIndexNode.prev
                    // Is there a Node before itrIndexNode.prev?
                    if (itrIndexNode != coll.first.next) {
                        // Set current itrIndexNode's prev to Node N-2
                        itrIndexNode.prev = itrIndexNode.prev.prev;
                        // Then set Node N-2's next to current itrIndexNode,
                        // this drops all references to the Node being removed
                        itrIndexNode.prev.next = itrIndexNode;
                    } else {
                        // There is no N-2 Node, we are removing the first Node
                        // in the collection
                        itrIndexNode.prev = null;
                        coll.first = itrIndexNode;
                    }
                } else {
                    // itrIndexNode is null, we are at the end of the collection
                    // Are there any items before the Node that is being removed?
                    if (coll.last.prev != null) {
                        coll.last.prev.next = null;
                        coll.last = coll.last.prev;
                    } else {
                        // There are no more items, clear() the collection
                        nextCalled = false;
                        coll.clear();
                        return;
                    }
                }
                coll.size--;
                nextCalled = false;
            } else {
                throw new IllegalStateException();
            }
        }

        /**
         *  Returns true as long as current Iteration Index Node (itrIndexNode)
         *  is non-null
         *
         */
        @Override
        public boolean hasNext() {
            if (coll._cleared)
                return false;
            return itrIndexNode != null;
        }

        /**
         * Returns the next node in the iteration
         *
         */
        @Override
        public E next() {
            if (coll._cleared)
                throw new NoSuchElementException();
            if (this.hasNext()) {
                Node<E> node = itrIndexNode;
                itrIndexNode = itrIndexNode.next;
                nextCalled = true;
                return node.item;
            } else {
                throw new NoSuchElementException();
            }
        }
    }

    /**
     * Return size of current collection
     */
    @Override
    public synchronized int size() {
        return this.size;
    }
}
