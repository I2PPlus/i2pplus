package org.rrd4j.core;

import java.io.IOException;

/**
 * RRD double primitive type.
 *
 * @param <U> the updater type
 */
class RrdDouble<U extends RrdUpdater<U>> extends RrdPrimitive<U> {
    private double cache;
    private boolean cached = false;

    /**
     * Create a double primitive, optionally flagged as constant.
     *
     * @param updater the updater that owns this primitive
     * @param isConstant true to mark the value as constant, so the backend may skip storing it
     */
    RrdDouble(RrdUpdater<U> updater, boolean isConstant) {
        super(updater, RrdDouble.RRD_DOUBLE, isConstant);
    }

    /**
     * Create a double primitive that is not flagged as constant.
     *
     * @param updater the updater that owns this primitive
     */
    RrdDouble(RrdUpdater<U> updater) {
        super(updater, RrdDouble.RRD_DOUBLE, false);
    }

    /**
     * Store a double value with optional caching.
     *
     * @param value the value to set
     * @throws java.io.IOException if an I/O error occurs
     */
    void set(double value) throws IOException {
        if (!isCachingAllowed()) {
            writeDouble(value);
        }
        // caching allowed
        else if (!cached || !Util.equal(cache, value)) {
            // update cache
            writeDouble(cache = value);
            cached = true;
        }
    }

    /**
     * Retrieve the stored double value.
     *
     * @return the stored double
     * @throws java.io.IOException if an I/O error occurs
     */
    double get() throws IOException {
        if (!isCachingAllowed()) {
            return readDouble();
        } else {
            if (!cached) {
                cache = readDouble();
                cached = true;
            }
            return cache;
        }
    }
}
