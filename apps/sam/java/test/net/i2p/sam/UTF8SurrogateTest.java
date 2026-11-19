package net.i2p.sam;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * A supplementary character in a SAM command line survives as both of its
 * surrogates, and the bytes after the command line stay on the stream.
 *
 * <p>{@link UTF8Reader} exists so the header of a SAM connection can be read as
 * text while the bytes that follow it are handed to something else as binary -
 * it must never consume a byte it does not return. Decoding into a one-character
 * buffer broke that in a quiet way: a code point above the BMP needs two chars,
 * all four of its bytes were already off the stream, and the low surrogate was
 * dropped. The client sent an emoji in a nickname and the parser saw U+D83D.
 *
 * @since 0.9.72+
 */
public class UTF8SurrogateTest {

    /** Holds no state; each case feeds one buffer to the reader. */
    public UTF8SurrogateTest() {}

    /** The emoji the client puts in a quoted SAM value (U+1F600). */
    private static final String EMOJI = new String(Character.toChars(0x1F600));

    /** The command text ahead of the emoji. */
    private static final String PREFIX = "SESSION CREATE STYLE=TUNNEL NICKNAME=";

    /** The command text after the emoji, before the newline. */
    private static final String SUFFIX = " \"";

    /** The three binary bytes that follow the command line. */
    private static final byte[] PAYLOAD = new byte[] { 0x01, 0x02, 0x03 };

    /**
     * The line comes back exactly as it was sent, both surrogates included, so
     * the code point the client typed is the code point the parser sees. At HEAD
     * the low surrogate was dropped and the nickname held an unpaired U+D83D.
     *
     * @throws IOException if the reader refuses a well-formed line
     */
    @Test
    public void supplementaryCharacterKeepsBothSurrogates() throws IOException {
        String line = PREFIX + EMOJI + SUFFIX;
        StringBuilder got = readLine(line + "\n" + bin());
        assertEquals("the decoded line is not the line that was sent", line, got.toString());
        assertEquals("the emoji did not survive the decode",
                     0x1F600, got.codePointAt(line.indexOf(EMOJI)));
    }

    /**
     * Two supplementary characters in a row both arrive intact - the pending half
     * of one pair must not be overwritten by the next.
     *
     * @throws IOException if the reader refuses a well-formed line
     */
    @Test
    public void twoSupplementaryCharactersInARow() throws IOException {
        String line = PREFIX + EMOJI + EMOJI + SUFFIX;
        StringBuilder got = readLine(line + "\n" + bin());
        assertEquals("two emoji did not survive the decode", line, got.toString());
    }

    /**
     * The half of a surrogate pair held between calls is handed back without
     * reading a byte, which is the whole reason this reader is unbuffered: the
     * bytes after the command line belong to whatever reads them next.
     *
     * @throws IOException if the reader refuses a well-formed line
     */
    @Test
    public void pendingHalfIsHandedBackWithoutReading() throws IOException {
        CountingInputStream in = new CountingInputStream(bytes(PREFIX + EMOJI + SUFFIX + "\n"));
        UTF8Reader r = new UTF8Reader(in);
        StringBuilder got = new StringBuilder(PREFIX.length());
        int c;
        while ((c = r.read()) >= 0 && !Character.isHighSurrogate((char) c)) {
            got.append((char) c);
        }
        assertEquals("the reader did not stop at the high surrogate", PREFIX, got.toString());
        int reads = in.reads();
        int low = r.read();
        assertEquals("handing back the pending half read another byte off the stream",
                     reads, in.reads());
        assertTrue("the second half is not a low surrogate", Character.isLowSurrogate((char) low));
    }

    /**
     * Reading the line leaves the binary tail on the stream, unread, which is what
     * the SAM handlers rely on when they pass the connection on as a stream.
     *
     * @throws IOException if the reader refuses a well-formed line
     */
    @Test
    public void binaryTailStaysOnTheStream() throws IOException {
        byte[] line = bytes(PREFIX + EMOJI + SUFFIX + "\n");
        byte[] withTail = new byte[line.length + PAYLOAD.length];
        System.arraycopy(line, 0, withTail, 0, line.length);
        System.arraycopy(PAYLOAD, 0, withTail, line.length, PAYLOAD.length);
        ByteArrayInputStream in = new ByteArrayInputStream(withTail);
        UTF8Reader r = new UTF8Reader(in);
        int c;
        while ((c = r.read()) >= 0 && c != '\n') {
            // the command line only; what follows belongs to the next reader
        }
        byte[] tail = new byte[in.available()];
        assertEquals("the tail was not the length it was sent as", PAYLOAD.length, tail.length);
        in.read(tail);
        assertArrayEquals("the bytes after the command line were consumed", PAYLOAD, tail);
    }

    /**
     * The ordinary multi-byte cases still decode, so widening the decode buffer
     * to two characters cost nothing on the path every SAM client uses.
     *
     * @throws IOException if the reader refuses a well-formed line
     */
    @Test
    public void twoAndThreeByteCharactersStillDecode() throws IOException {
        String line = "PING \u00e9 \u4e2d";
        StringBuilder got = readLine(line + "\n" + bin());
        assertEquals("a two-byte or three-byte character changed", line, got.toString());
    }

    /**
     * Reads one line, up to and not including the newline.
     *
     * @param text the bytes to read, as text to convert
     * @return the characters before the newline
     * @throws IOException if the reader refuses a well-formed line
     */
    private static StringBuilder readLine(String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        UTF8Reader r = new UTF8Reader(new ByteArrayInputStream(data));
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = r.read()) >= 0 && c != '\n') {
            sb.append((char) c);
        }
        return sb;
    }

    /**
     * @param text the text to encode
     * @return the text as UTF-8 bytes
     */
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The payload bytes appended after a command line, as text that round-trips
     * through {@link #bytes(String)} unchanged.
     *
     * @return three bytes below 0x80, so no decoding is involved
     */
    private static String bin() {
        return new String(PAYLOAD, StandardCharsets.UTF_8);
    }

    /**
     * A stream that counts the read() calls its reader makes, so a test can see a
     * byte consumed and never returned.
     *
     * @since 0.9.72+
     */
    private static final class CountingInputStream extends InputStream {

        /** The bytes handed out, in order. */
        private final byte[] _data;
        /** How far into those bytes the reader has got. */
        private int _pos;
        /** How many one-byte read() calls have been made. */
        private int _reads;

        /**
         * @param data the bytes to hand out
         */
        CountingInputStream(byte[] data) {
            _data = data;
        }

        /**
         * @return the number of bytes this stream has handed out so far
         */
        int reads() {
            return _reads;
        }

        /**
         * @return the next byte, or -1 at the end
         */
        @Override
        public int read() {
            _reads++;
            return _pos < _data.length ? _data[_pos++] & 0xff : -1;
        }
    }
}
