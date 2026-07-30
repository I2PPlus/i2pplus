package net.metanotion.io.data;
// License: BSD-3-Clause. See docs/LICENSES.md

import net.metanotion.io.Serializer;

/**
 * Pass-through serializer for byte arrays.
 *
 * <p>Returns byte arrays unchanged during serialization/deserialization.
 * Useful for storing raw binary data without modification.</p>
 *
 * <p><strong>Important:</strong> Performs direct reference copy, not deep copy.
 * Modifications to returned array affect the original array.</p>
 */
public class IdentityBytes implements Serializer<byte[]> {

    /** @return byte[] */
    public byte[] getBytes(byte[] o) { return o; }

    /** @return b */
    public byte[] construct(byte[] b) { return b; }
}
