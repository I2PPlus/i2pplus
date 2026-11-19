package net.i2p.i2ptunnel.util;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Tests the RFC2616 chunked-transfer parser: valid framing at every write
 * boundary, and the malformed framing that used to be skipped silently and left
 * the parser out of step with the body.
 *
 * @since 0.9.72+
 */
public class DechunkedOutputStreamTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public DechunkedOutputStreamTest() {}

    /** A complete chunked body with two chunks and one trailer header. */
    private static final String VALID = "5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\nx-trailer: 1\r\n\r\n";

    /** The body {@link #VALID} decodes to. */
    private static final String VALID_BODY = "hello world";

    /** The parser's ceiling on one chunk header, size digits and extension together. */
    private static final int MAX_CHUNK_HEADER = 64 * 1024;

    /** The parser's ceiling on the whole trailer section. */
    private static final int MAX_TRAILER = 64 * 1024;

    /**
     * With strip set, the framing is dropped and only the chunk data reaches the
     * target.
     *
     * @throws IOException not expected; the framing is valid
     */
    @Test
    public void testStripsTheFraming() throws IOException {
        assertEquals(VALID_BODY, dechunk(VALID, true, 4096));
    }

    /**
     * With strip unset, the framing reaches the target unchanged, for a client that
     * wants the chunked body as the server sent it.
     *
     * @throws IOException not expected; the framing is valid
     */
    @Test
    public void testPassesTheFramingThrough() throws IOException {
        assertEquals(VALID, dechunk(VALID, false, 4096));
    }

    /**
     * The parser is a state machine fed arbitrary writes, so a message must
     * decode the same way however it is split up.
     *
     * @throws IOException not expected; the target is a byte array and the
     *                     framing is valid
     */
    @Test
    public void testValidMessageAtEveryWriteBoundary() throws IOException {
        for (int piece = 1; piece <= VALID.length() + 2; piece++) {
            assertEquals("split at " + piece, VALID_BODY, dechunk(VALID, true, piece));
            assertEquals("split at " + piece, VALID, dechunk(VALID, false, piece));
        }
    }

    /**
     * Same again with random split points, including empty writes.
     *
     * @throws IOException not expected; the target is a byte array and the
     *                     framing is valid
     */
    @Test
    public void testValidMessageAtRandomBoundaries() throws IOException {
        Random rnd = new Random(7);
        for (int round = 0; round < 200; round++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DechunkedOutputStream os = stream(out, true);
            int i = 0;
            while (i < VALID.length()) {
                if (rnd.nextBoolean())
                    os.write(VALID.getBytes(StandardCharsets.US_ASCII), i, 0);
                int n = Math.min(1 + rnd.nextInt(6), VALID.length() - i);
                os.write(VALID.getBytes(StandardCharsets.US_ASCII), i, n);
                i += n;
            }
            assertEquals(VALID_BODY, out.toString("US-ASCII"));
        }
    }

    /**
     * The done callback fires exactly once, when the terminating blank line of the
     * last chunk is read.
     *
     * @throws IOException not expected; the framing is valid
     */
    @Test
    public void testCallbackFiresOnceAtTheTerminator() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        final AtomicInteger done = new AtomicInteger();
        DechunkedOutputStream os = new DechunkedOutputStream(out, done::incrementAndGet, true);
        os.write(VALID.getBytes(StandardCharsets.US_ASCII));
        assertEquals(1, done.get());
    }

    /**
     * A chunk-size line with no hex digits used to read as the terminating
     * zero-length chunk, ending the body early.
     *
     * @throws IOException not expected; the bad framing is caught by the helper
     */
    @Test
    public void testEmptyChunkSizeLineRejected() throws IOException {
        assertRejected("\r\n5\r\nhello\r\n0\r\n\r\n");
        assertRejected("5\r\nhello\r\n\r\n0\r\n\r\n");
    }

    /**
     * A byte where the chunk footer's CRLF belongs used to be ignored, leaving the
     * parser one byte out of step for the rest of the body.
     *
     * @throws IOException not expected; the bad framing is caught by the helper
     */
    @Test
    public void testMissingChunkFooterRejected() throws IOException {
        assertRejected("5\r\nhelloX0\r\n\r\n");
        assertRejected("5\r\nhello\rX0\r\n\r\n");
    }

    /**
     * A bare LF in place of CRLF stays tolerated - servers do emit it.
     *
     * @throws IOException not expected; bare line feeds are accepted framing
     */
    @Test
    public void testBareLineFeedsTolerated() throws IOException {
        assertEquals("hi", dechunk("2\nhi\n0\n\n", true, 4096));
    }

    /**
     * A chunk extension after the ';' is part of the framing and is dropped with it,
     * whatever it holds.
     *
     * @throws IOException not expected; the framing is valid
     */
    @Test
    public void testChunkExtensionSkipped() throws IOException {
        assertEquals("hi", dechunk("2;a=b c\r\nhi\r\n0\r\n\r\n", true, 4096));
    }

    /**
     * An oversized chunk length is rejected, and so is a digit run long enough to
     * overflow the accumulator into a small number.
     *
     * @throws IOException not expected; the oversized sizes are caught by the
     *                     helper
     */
    @Test
    public void testOversizedChunkSizeRejected() throws IOException {
        assertRejected("10000000\r\n");          // 128 MB + 1
        assertRejected("ffffffff\r\n");         // 4 GB
        assertRejected("ffffffffffffffff\r\n"); // far past int range
        // just under the ceiling parses
        dechunk("7ffffff\r\n", true, 4096);
    }

    /**
     * Trailer headers are bounded in count and in line length, so a body that
     * never ends cannot grow the parser's counters without limit.
     *
     * @throws IOException not expected; the unbounded trailers are caught by the
     *                     helper
     */
    @Test
    public void testTrailerHeadersBounded() throws IOException {
        StringBuilder many = new StringBuilder("0\r\n");
        for (int i = 0; i < 129; i++)
            many.append("x: 1\r\n");
        many.append("\r\n");
        assertRejected(many.toString());

        StringBuilder longLine = new StringBuilder("0\r\nx: ");
        for (int i = 0; i < 8193; i++)
            longLine.append('y');
        longLine.append("\r\n\r\n");
        assertRejected(longLine.toString());

        // within the bounds, and the trailer really does end the body
        StringBuilder ok = new StringBuilder("0\r\n");
        for (int i = 0; i < 128; i++)
            ok.append("x: 1\r\n");
        ok.append("\r\n");
        assertEquals("", dechunk(ok.toString(), true, 4096));
    }

    /**
     * A body written after the chunking is done means the parser is out of step with
     * the sender, so the extra bytes are reported rather than swallowed.
     *
     * @throws IOException not expected; the framing up to the terminator is valid
     */
    @Test(expected = EOFException.class)
    public void testBytesAfterDoneRejected() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DechunkedOutputStream os = stream(out, true);
        os.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        os.write("more".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * A zero-length chunk followed by the blank line is a complete body with nothing
     * in it.
     *
     * @throws IOException not expected; the framing is valid
     */
    @Test
    public void testEmptyBodyIsJustTheTerminator() throws IOException {
        assertEquals("", dechunk("0\r\n\r\n", true, 4096));
    }

    /**
     * A chunk extension runs as long as the sender likes, but not forever: the
     * parser sits in the extension state until a line ending arrives, and a body
     * that never gets one never finishes.
     *
     * @throws IOException not expected; the long extensions are caught by the
     *                     helper
     */
    @Test
    public void testChunkExtensionBounded() throws IOException {
        // the ceiling counts the size digit, the ';', the extension and the CRLF,
        // so an extension of ceiling minus four bytes is the longest that fits
        assertRejected("3;" + repeat('x', MAX_CHUNK_HEADER - 3) + "\r\nabc\r\n0\r\n\r\n");
        // one byte less than the ceiling, and the extension really does end the
        // header rather than the body
        assertEquals("abc", dechunk("3;" + repeat('x', MAX_CHUNK_HEADER - 4) + "\r\nabc\r\n0\r\n\r\n",
                                    true, 4096));
    }

    /**
     * A run of bare CR bytes in the trailer section is accepted framing - a line
     * ending may be preceded by more than one CR - so it has to be bounded as a
     * section, not as a line.
     *
     * @throws IOException not expected; the long runs are caught by the helper
     */
    @Test
    public void testTrailerCRRunBounded() throws IOException {
        assertRejected("0\r\n" + repeat('\r', MAX_TRAILER - 1) + "\r\n");
        // a run just inside the ceiling still ends the body
        assertEquals("", dechunk("0\r\n" + repeat('\r', MAX_TRAILER - 2) + "\r\n", true, 4096));
    }

    /**
     * A run of one character, for building the oversized metadata lines.
     *
     * @param c the character to repeat
     * @param count how many times
     * @return the string to feed the parser
     */
    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++)
            sb.append(c);
        return sb.toString();
    }

    /**
     * Feeds the input through a dechunking stream.
     *
     * @param input the chunked bytes as written by the server
     * @param strip true to drop the framing, false to pass it through
     * @param piece largest write issued to the stream
     * @return what the stream forwarded to the target
     * @throws IOException on malformed framing
     */
    private static String dechunk(String input, boolean strip, int piece) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DechunkedOutputStream os = stream(out, strip);
        byte[] bytes = input.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < bytes.length; i += piece) {
            os.write(bytes, i, Math.min(piece, bytes.length - i));
        }
        return out.toString("US-ASCII");
    }

    /**
     * A dechunking stream over a throwaway target.
     *
     * @param out the target stream
     * @param strip true to drop the framing
     * @return the stream to feed
     */
    private static DechunkedOutputStream stream(OutputStream out, boolean strip) {
        return new DechunkedOutputStream(out, () -> {}, strip);
    }

    /**
     * Feeds the input one byte at a time and requires an IOException.
     *
     * @param input the chunked bytes as written by the server
     * @throws IOException if the framing is accepted
     */
    private static void assertRejected(String input) throws IOException {
        try {
            dechunk(input, true, 1);
            fail("Malformed framing was accepted: " + input.replace("\r", "\\r").replace("\n", "\\n"));
        } catch (IOException expected) {
            // expected
        }
    }
}
