package org.rrd4j.core;

import java.io.IOException;

/**
 * RRD primitive type for handling long integer values.
 *
 * <p>This class provides methods to store and retrieve long integer values in RRD files. It
 * includes caching support for performance optimization when the backend allows it.
 *
 * @param <U> The type of RrdUpdater this primitive belongs to
 */
class RrdLong<U extends RrdUpdater<U>> extends RrdPrimitive<U> {
    private long cache;
    private boolean cached = false;

    RrdLong(RrdUpdater<U> updater, boolean isConstant) {
        super(updater, RrdPrimitive.RRD_LONG, isConstant);
    }

    RrdLong(RrdUpdater<U> updater) {
        this(updater, false);
    }

    /**
     * Sets the long value.
     *
     * @param value the long value to set
     * @throws java.io.IOException if an I/O error occurs
     */
    void set(long value) throws IOException {
        if (!isCachingAllowed()) {
            writeLong(value);
        }
        // caching allowed
        else if (!cached || cache != value) {
            // update cache
            writeLong(cache = value);
            cached = true;
        }
    }

    /**
     * Gets the long value.
     *
     * @return the long value
     * @throws java.io.IOException if an I/O error occurs
     */
    long get() throws IOException {
        if (!isCachingAllowed()) {
            return readLong();
        } else {
            if (!cached) {
                cache = readLong();
                cached = true;
            }
            return cache;
        }
    }
}
