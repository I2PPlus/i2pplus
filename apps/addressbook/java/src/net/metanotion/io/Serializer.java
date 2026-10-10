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
     * @param o the object to serialize
     * @return the bytes
     */
    public byte[] getBytes(T o);
    /**
     * b).
     * @param b the serialized bytes to read back
     * @return the deserialized object
     */
    public T construct(byte[] b);
}
