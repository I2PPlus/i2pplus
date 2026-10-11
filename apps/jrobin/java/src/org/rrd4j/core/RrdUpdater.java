package org.rrd4j.core;

import java.io.IOException;

/**
 * Interface for RRD components that can update their state.
 *
 * <p>This interface defines the contract for objects that need to be updated when RRD data changes.
 * It provides methods to access the backend storage, copy state between objects, and manage memory
 * allocation.
 *
 * @param <T> The type of RrdUpdater, used for generic type safety
 */
interface RrdUpdater<T extends RrdUpdater<T>> {
    /**
     * Returns the backend store this updater reads and writes through.
     *
     * @return a {@link org.rrd4j.core.RrdBackend} object.
     */
    RrdBackend getRrdBackend();

    /**
     * Copies this updater's state into the given one.
     *
     * @param updater a {@link org.rrd4j.core.RrdUpdater} object.
     * @throws java.io.IOException if the state cannot be read from the backend
     */
    void copyStateTo(T updater) throws IOException;

    /**
     * Returns the allocator used for this updater's memory allocation.
     *
     * @return a {@link org.rrd4j.core.RrdAllocator} object.
     */
    RrdAllocator getRrdAllocator();
}
