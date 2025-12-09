package org.rrd4j.core;

import java.io.IOException;

/**
 * RRD integer primitive type.
 *
 * @param <U> the updater type
 */
class RrdInt<U extends RrdUpdater<U>> extends RrdPrimitive<U> {
    private int cache;
    private boolean cached = false;

    /**
     * Creates a new RRD integer primitive.
     *
     * @param updater the updater
     * @param isConstant whether this is a constant value
     */
    RrdInt(RrdUpdater<U> updater, boolean isConstant) {
        super(updater, RrdPrimitive.RRD_INT, isConstant);
    }

    /**
     * Creates a new RRD integer primitive.
     *
     * @param updater the updater
     */
    RrdInt(RrdUpdater<U> updater) {
        this(updater, false);
    }

    /**
     * Sets the integer value.
     *
     * @param value the value to set
     * @throws java.io.IOException if an I/O error occurs
     */
    void set(int value) throws IOException {
        if (!isCachingAllowed()) {
            writeInt(value);
        }
        // caching allowed
        else if (!cached || cache != value) {
            // update cache
            writeInt(cache = value);
            cached = true;
        }
    }

    /**
     * Gets the integer value.
     *
     * @return the integer value
     * @throws java.io.IOException if an I/O error occurs
     */
    int get() throws IOException {
        if (!isCachingAllowed()) {
            return readInt();
        } else {
            if (!cached) {
                cache = readInt();
                cached = true;
            }
            return cache;
        }
    }
}
