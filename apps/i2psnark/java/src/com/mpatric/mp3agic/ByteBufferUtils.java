package com.mpatric.mp3agic;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class ByteBufferUtils {

	private ByteBufferUtils() {}

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
