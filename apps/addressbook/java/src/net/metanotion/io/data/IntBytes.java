package net.metanotion.io.data;
// License: BSD-3-Clause. See docs/LICENSES.md

import net.metanotion.io.Serializer;

/**
 * Integer serializer using 4-byte big-endian representation.
 *
 * <p>Converts between Integer objects and 4-byte big-endian arrays.
 * Provides consistent byte ordering for cross-platform compatibility.</p>
 *
 * <p>Serialization format:</p>
 * <ul>
 * <li>Byte 0: Most significant byte (bits 24-31)</li>
 * <li>Byte 1: Second most significant byte (bits 16-23)</li>
 * <li>Byte 2: Third most significant byte (bits 8-15)</li>
 * <li>Byte 3: Least significant byte (bits 0-7)</li>
 * </ul>
 */
public class IntBytes implements Serializer<Integer> {
    public byte[] getBytes(Integer o) {
        byte[] b = new byte[4];
        int v = o.intValue();
        b[0] = (byte)(0xff & (v >> 24));
        b[1] = (byte)(0xff & (v >> 16));
        b[2] = (byte)(0xff & (v >>  8));
        b[3] = (byte)(0xff & v);
        return b;
    }

    public Integer construct(byte[] b) {
        int v = (((b[0] & 0xff) << 24) |
                 ((b[1] & 0xff) << 16) |
                 ((b[2] & 0xff) <<  8) |
                 (b[3] & 0xff));
        return Integer.valueOf(v);
    }
}
