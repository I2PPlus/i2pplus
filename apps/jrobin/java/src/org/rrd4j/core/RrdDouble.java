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
     * Creates a new RRD double primitive.
     *
     * @param updater the updater
     * @param isConstant whether this is a constant value
     */
    RrdDouble(RrdUpdater<U> updater, boolean isConstant) {
        super(updater, RrdDouble.RRD_DOUBLE, isConstant);
    }

    /**
     * Creates a new RRD double primitive.
     *
     * @param updater the updater
     */
    RrdDouble(RrdUpdater<U> updater) {
        super(updater, RrdDouble.RRD_DOUBLE, false);
    }

    /**
     * Sets the double value.
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
     * Gets the double value.
     *
     * @return the double value
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
