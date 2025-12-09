package org.rrd4j.core;

import java.io.IOException;

/**
 * RRD primitive type for handling enum values.
 *
 * <p>This class provides methods to store and retrieve enum values in RRD files. It handles the
 * conversion between enum constants and their string representations, with caching support for
 * performance optimization.
 *
 * @param <U> The type of RrdUpdater this primitive belongs to
 * @param <E> The enum type being stored
 */
class RrdEnum<U extends RrdUpdater<U>, E extends Enum<E>> extends RrdPrimitive<U> {

    private E cache;
    private final Class<E> clazz;

    RrdEnum(RrdUpdater<U> updater, boolean isConstant, Class<E> clazz) {
        super(updater, RrdPrimitive.RRD_STRING, isConstant);
        this.clazz = clazz;
    }

    RrdEnum(RrdUpdater<U> updater, Class<E> clazz) {
        this(updater, false, clazz);
    }

    /**
     * Sets the enum value.
     *
     * @param value the enum value to set
     * @throws java.io.IOException if an I/O error occurs
     */
    void set(E value) throws IOException {
        if (!isCachingAllowed()) {
            writeEnum(value);
        }
        // caching allowed
        else if (cache == null || cache != value) {
            // update cache
            writeEnum((cache = value));
        }
    }

    /**
     * Gets the enum value.
     *
     * @return the enum value
     * @throws java.io.IOException if an I/O error occurs
     */
    E get() throws IOException {
        if (!isCachingAllowed()) {
            return readEnum(clazz);
        } else {
            if (cache == null) {
                cache = readEnum(clazz);
            }
            return cache;
        }
    }

    /**
     * Gets the name of the enum value.
     *
     * @return the name of the enum value
     * @throws java.io.IOException if an I/O error occurs
     */
    String name() throws IOException {
        return get().name();
    }
}
