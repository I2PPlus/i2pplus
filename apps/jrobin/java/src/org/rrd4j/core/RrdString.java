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
    private String cache;

    RrdString(RrdUpdater<U> updater, boolean isConstant) {
        super(updater, RrdPrimitive.RRD_STRING, isConstant);
    }

    RrdString(RrdUpdater<U> updater) {
        this(updater, false);
    }

    /**
     * Sets the string value.
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
     * Gets the string value.
     *
     * @return the string value
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
