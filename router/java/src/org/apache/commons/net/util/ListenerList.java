/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.net.util;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.EventListener;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A list of event listeners.
 *
 * @param <T> the type of elements returned by the iterator
 */
public class ListenerList<T extends EventListener> implements Serializable, Iterable<T> {

    private static final long serialVersionUID = -1934227607974228213L;

    /**
     * The thread-safe list of listeners.
     */
    private final CopyOnWriteArrayList<T> listeners;

    /**
     * Constructs a new instance.
     */
    public ListenerList() {
        listeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Adds the given listener to the end of this list.
     *
     * @param listener A listener.
     */
    public void addListener(final T listener) {
        listeners.add(listener);
    }

    /**
     * Gets the number of elements in this list.
     *
     * @return the number of elements in this list
     */
    public int getListenerCount() {
        return listeners.size();
    }

    /**
     * Tests whether if this listener list is empty.
     *
     * @return whether if this listener list is empty.
     * @since 3.12.0
     */
    public boolean isEmpty() {
        return getListenerCount() == 0;
    }

    /**
     * Return an {@link Iterator} for the {@link EventListener} instances.
     *
     * @return an {@link Iterator} for the {@link EventListener} instances
     * @since 2.0 TODO Check that this is a good defensive strategy
     */
    @Override
    public Iterator<T> iterator() {
        return listeners.iterator();
    }

    /**
     * Throws UnsupportedOperationException.
     *
     * @param ignored Ignore.
     */
    private void readObject(final ObjectInputStream ignored) {
        throw new UnsupportedOperationException("Serialization is not supported");
    }

    /**
     * Removes the first occurrence of the specified listener from this list, if it is present.
     *
     * @param listener listener to be removed from this list, if present.
     */
    public void removeListener(final T listener) {
        listeners.remove(listener);
    }

    /**
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param ignored ignored.
     * @throws UnsupportedOperationException Always thrown.
     */
    private void writeObject(final ObjectOutputStream ignored) {
        throw new UnsupportedOperationException("Serialization is not supported");
    }

}
