package net.metanotion.io;
// License: BSD-3-Clause. See docs/LICENSES.md

/**
 * Interface for serializing and deserializing objects to/from byte arrays.
 * Provides bidirectional conversion between objects and their byte representation.
 *
 * @param <T> type of objects to serialize/deserialize
 */
public interface Serializer<T> {
    /**
     * o).
     * @return the bytes
     */
    public byte[] getBytes(T o);
    /**
     * b).
     */
    public T construct(byte[] b);
}
