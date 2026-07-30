package net.metanotion.io.data;
// License: BSD-3-Clause. See docs/LICENSES.md

import java.io.UnsupportedEncodingException;
import net.metanotion.io.Serializer;

/**
 * String serializer using UTF-8 encoding.
 *
 * <p>Converts between String objects and UTF-8 byte arrays.
 * Supports full Unicode character set for internationalized text.</p>
 *
 * <p>UTF-8 encoding characteristics:</p>
 * <ul>
 * <li>ASCII characters (U+0000 to U+007F) use 1 byte</li>
 * <li>Latin characters with diacritics (U+0080 to U+07FF) use 2 bytes</li>
 * <li>Most common characters including CJK (U+0800 to U+FFFF) use 3 bytes</li>
 * <li>Astral plane characters (U+10000 to U+10FFFF) use 4 bytes</li>
 * </ul>
 */
public class UTF8StringBytes implements Serializer<String> {
    public byte[] getBytes(String o) {
        try {
            return o.getBytes("UTF-8");
        } catch (UnsupportedEncodingException uee) { throw new Error("Unsupported Encoding"); }
    }

    public String construct(byte[] b) {
        try {
            return new String(b, "UTF-8");
        } catch (UnsupportedEncodingException uee) { throw new Error("Unsupported Encoding"); }
    }
}
