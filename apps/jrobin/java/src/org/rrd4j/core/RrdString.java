package org.rrd4j.core;

import java.io.IOException;

/**
 * RRD primitive type for handling string values.
 *
 * <p>This class provides methods to store and retrieve string values in RRD files. It includes
 * caching support for performance optimization when the backend allows it.
 *
 * @param <U> The type of RrdUpdater this primitive belongs to
 */
class RrdString<U extends RrdUpdater<U>> extends RrdPrimitive<U> {
/** The last value read, when caching is enabled. */
    private String cache;

    /**
     * Create a string-valued primitive.
     *
     * @param updater the source of values
     * @param isConstant true to read a constant rather than a varying value
     */
    RrdString(RrdUpdater<U> updater, boolean isConstant) {
        super(updater, RrdPrimitive.RRD_STRING, isConstant);
    }

    /**
     * Create a varying string-valued primitive.
     *
     * @param updater the source of values
     */
    RrdString(RrdUpdater<U> updater) {
        this(updater, false);
    }

    /**
     * Store a string value with optional caching.
     *
     * @param value the string value to set
     * @throws java.io.IOException if an I/O error occurs
     */
    void set(String value) throws IOException {
        if (!isCachingAllowed()) {
            writeString(value);
        }
        // caching allowed
        else if (cache == null || !cache.equals(value)) {
            // update cache
            writeString(cache = value);
        }
    }

    /**
     * Retrieve the stored string value.
     *
     * @return the stored string
     * @throws java.io.IOException if an I/O error occurs
     */
    String get() throws IOException {
        if (!isCachingAllowed()) {
            return readString();
        } else {
            if (cache == null) {
                cache = readString();
            }
            return cache;
        }
    }
}
