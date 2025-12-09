package org.rrd4j.core;

import java.io.IOException;

/**
 * RRD primitive type for handling arrays of double values.
 *
 * <p>This class provides methods to store and retrieve arrays of double values in RRD files. It
 * extends RrdPrimitive to handle the low-level storage operations while providing array-specific
 * access methods.
 *
 * @param <U> The type of RrdUpdater this primitive belongs to
 */
class RrdDoubleArray<U extends RrdUpdater<U>> extends RrdPrimitive<U> {
    private final int length;

    RrdDoubleArray(RrdUpdater<U> updater, int length) {
        super(updater, RrdPrimitive.RRD_DOUBLE, length, false);
        this.length = length;
    }

    /**
     * Sets a double value at the specified index.
     *
     * @param index the index in the array
     * @param value the double value to set
     * @throws java.io.IOException if an I/O error occurs
     */
    void set(int index, double value) throws IOException {
        set(index, value, 1);
    }

    void set(int index, double value, int count) throws IOException {
        // rollovers not allowed!
        assert index + count <= length
                : "Invalid robin index supplied: index="
                        + index
                        + ", count="
                        + count
                        + ", length="
                        + length;
        writeDouble(index, value, count);
    }

    /**
     * Gets a double value at the specified index.
     *
     * @param index the index in the array
     * @return the double value at the index
     * @throws java.io.IOException if an I/O error occurs
     */
    double get(int index) throws IOException {
        assert index < length : "Invalid index supplied: " + index + ", length=" + length;
        return readDouble(index);
    }

    double[] get(int index, int count) throws IOException {
        assert index + count <= length
                : "Invalid index/count supplied: "
                        + index
                        + "/"
                        + count
                        + " (length="
                        + length
                        + ")";
        return readDouble(index, count);
    }
}
