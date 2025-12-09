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
     * getRrdBackend.
     *
     * @return a {@link org.rrd4j.core.RrdBackend} object.
     */
    RrdBackend getRrdBackend();

    /**
     * copyStateTo.
     *
     * @param updater a {@link org.rrd4j.core.RrdUpdater} object.
     * @throws java.io.IOException if any.
     */
    void copyStateTo(T updater) throws IOException;

    /**
     * getRrdAllocator.
     *
     * @return a {@link org.rrd4j.core.RrdAllocator} object.
     */
    RrdAllocator getRrdAllocator();
}
