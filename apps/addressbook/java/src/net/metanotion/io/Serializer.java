package net.metanotion.io;

/**
 * Interface for serializing and deserializing objects to/from byte arrays.
 * Provides bidirectional conversion between objects and their byte representation.
 * License: BSD-3-Clause. See docs/LICENSES.md.
 *
 * @param <T> type of objects to serialize/deserialize
 */
public interface Serializer<T> {
    /**
     * Serialize an object to its byte representation.
     *
     * @param o the object to serialize
     * @return the given object's serialized form as a byte array
     */
    public byte[] getBytes(T o);
    /**
     * Reconstruct an object from the bytes written by {@link #getBytes}.
     *
     * @param b the serialized bytes to read back
     * @return the deserialized object
     */
    public T construct(byte[] b);
}
