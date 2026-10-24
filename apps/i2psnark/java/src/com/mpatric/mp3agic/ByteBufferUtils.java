package com.mpatric.mp3agic;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Helpers for reading strings out of a byte buffer. */
public final class ByteBufferUtils {

    /** Utility class, not intended for instantiation. */
    private ByteBufferUtils() {}

    /**
     * Reads from the buffer's position up to the first NUL.
     *
     * @param bb the buffer to read
     * @return the ISO-8859-1 text before the NUL
     */
    public static String extractNullTerminatedString(ByteBuffer bb) {
        int start = bb.position();

        byte[] buffer = new byte[bb.remaining()];

        bb.get(buffer);

        String s = new String(buffer, StandardCharsets.ISO_8859_1);
        int nullPos = s.indexOf(0);

        s = s.substring(0, nullPos);

        bb.position(start + s.length() + 1);

        return s;
    }
}
