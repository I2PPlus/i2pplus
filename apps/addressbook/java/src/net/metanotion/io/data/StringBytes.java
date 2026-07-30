package net.metanotion.io.data;
// License: BSD-3-Clause. See docs/LICENSES.md

import java.io.UnsupportedEncodingException;
import net.metanotion.io.Serializer;

/**
 * String serializer using US-ASCII encoding.
 *
 * <p>Converts between String objects and US-ASCII byte arrays.
 * Only supports 7-bit ASCII characters.</p>
 *
 * <p><strong>Note:</strong> Use only when data is guaranteed to contain only US-ASCII.
 * For Unicode text, use {@link UTF8StringBytes} instead.</p>
 *
 * <p>If string contains characters outside US-ASCII range, an Error is thrown.</p>
 */
public class StringBytes implements Serializer<String> {
    public byte[] getBytes(String o) {
        try {
            return o.getBytes("US-ASCII");
        } catch (UnsupportedEncodingException uee) { throw new Error("Unsupported Encoding"); }
    }

    public String construct(byte[] b) {
        try {
            return new String(b, "US-ASCII");
        } catch (UnsupportedEncodingException uee) { throw new Error("Unsupported Encoding"); }
    }
}
