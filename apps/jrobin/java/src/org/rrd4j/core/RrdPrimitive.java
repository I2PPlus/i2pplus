package org.rrd4j.core;

import java.io.IOException;

/**
 * Abstract base class for all RRD primitive data types.
 *
 * <p>This class provides the foundation for storing different types of primitive data (int, long,
 * double, string) in RRD files. It handles the low-level operations of reading and writing bytes to
 * the backend storage, with support for caching and different data type sizes.
 *
 * @param <U> The type of RrdUpdater this primitive belongs to
 */
abstract class RrdPrimitive<U extends RrdUpdater<U>> {
    /** Maximum length of string values stored in RRD primitives. */
    static final int STRING_LENGTH = 20;

    /** Type identifier for integer RRD primitives. */
    static final int RRD_INT = 0;

    /** Type identifier for long RRD primitives. */
    static final int RRD_LONG = 1;

    /** Type identifier for double RRD primitives. */
    static final int RRD_DOUBLE = 2;

    /** Type identifier for string RRD primitives. */
    static final int RRD_STRING = 3;

    /**
     * Array containing the size in bytes for each RRD primitive type. Index corresponds to the type
     * constants (RRD_INT, RRD_LONG, RRD_DOUBLE, RRD_STRING).
     */
    static final int[] RRD_PRIM_SIZES = {4, 8, 8, 2 * STRING_LENGTH};

    private final RrdBackend backend;
    private final int byteCount;
    private final long pointer;
    private final boolean cachingAllowed;

    RrdPrimitive(RrdUpdater<U> updater, int type, boolean isConstant) {
        this(updater, type, 1, isConstant);
    }

    RrdPrimitive(RrdUpdater<U> updater, int type, int count, boolean isConstant) {
        this.backend = updater.getRrdBackend();
        this.byteCount = RRD_PRIM_SIZES[type] * count;
        this.pointer = updater.getRrdAllocator().allocate(byteCount);
        this.cachingAllowed = isConstant || backend.isCachingAllowed();
    }

    /** @return raw byte array from backend storage */
    final byte[] readBytes() throws IOException {
        byte[] b = new byte[byteCount];
        backend.read(pointer, b);
        return b;
    }

    /** @param b byte array to write to backend storage */
    final void writeBytes(byte[] b) throws IOException {
        assert b.length == byteCount
                : "Invalid number of bytes supplied to RrdPrimitive.write method";
        backend.write(pointer, b);
    }

    /** @return int value from backend storage */
    final int readInt() throws IOException {
        return backend.readInt(pointer);
    }

    /** @param value int value to write to backend storage */
    final void writeInt(int value) throws IOException {
        backend.writeInt(pointer, value);
    }

    /** @return long value from backend storage */
    final long readLong() throws IOException {
        return backend.readLong(pointer);
    }

    /** @param value long value to write to backend storage */
    final void writeLong(long value) throws IOException {
        backend.writeLong(pointer, value);
    }

    /** @return double value from backend storage */
    final double readDouble() throws IOException {
        return backend.readDouble(pointer);
    }

    /** @param index position in the primitive array
     *  @return double value at the given array index */
    final double readDouble(int index) throws IOException {
        long offset = pointer + index * RRD_PRIM_SIZES[RRD_DOUBLE];
        return backend.readDouble(offset);
    }

    /** @param index starting position in the primitive array
     *  @param count number of consecutive doubles to read
     *  @return array of double values */
    final double[] readDouble(int index, int count) throws IOException {
        long offset = pointer + index * RRD_PRIM_SIZES[RRD_DOUBLE];
        return backend.readDouble(offset, count);
    }

    /** @param value double value to write to backend storage */
    final void writeDouble(double value) throws IOException {
        backend.writeDouble(pointer, value);
    }

    /** @param index position in the primitive array
     *  @param value double value to write */
    final void writeDouble(int index, double value) throws IOException {
        long offset = pointer + index * RRD_PRIM_SIZES[RRD_DOUBLE];
        backend.writeDouble(offset, value);
    }

    /** @param index starting position in the primitive array
     *  @param value double value to write
     *  @param count number of consecutive slots to fill */
    final void writeDouble(int index, double value, int count) throws IOException {
        long offset = pointer + index * RRD_PRIM_SIZES[RRD_DOUBLE];
        backend.writeDouble(offset, value, count);
    }

    /** @param index starting position in the primitive array
     *  @param values array of doubles to write */
    final void writeDouble(int index, double[] values) throws IOException {
        long offset = pointer + index * RRD_PRIM_SIZES[RRD_DOUBLE];
        backend.writeDouble(offset, values);
    }

    /** @return string value from backend storage */
    final String readString() throws IOException {
        return backend.readString(pointer);
    }

    /** @param value string value to write to backend storage */
    final void writeString(String value) throws IOException {
        backend.writeString(pointer, value);
    }

    /**
     * Deserialize an enum value from backend storage.
     * @param <E> enum type
     * @param clazz enum class to deserialize
     * @return the enum value, or null if the stored value is empty
     */
    protected final <E extends Enum<E>> E readEnum(Class<E> clazz) throws IOException {
        String value = backend.readString(pointer);
        if (value == null || value.isEmpty()) {
            return null;
        } else {
            try {
                return Enum.valueOf(clazz, value);
            } catch (IllegalArgumentException e) {
                throw new InvalidRrdException("Invalid value for " + clazz.getSimpleName(), e);
            }
        }
    }

    /** Serialize an enum value to backend storage. */
    protected final <E extends Enum<E>> void writeEnum(E value) throws IOException {
        writeString(value.name());
    }

    /**
     * Checks if caching is allowed for this primitive.
     *
     * @return true if caching is allowed, false otherwise
     */
    final boolean isCachingAllowed() {
        return cachingAllowed;
    }
}
