package net.i2p.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.i2p.I2PAppContext;
import org.junit.Test;

/**
 * Tests the ceiling on the chunk-size line EepGet reads between chunks.
 *
 * <p>The line is read one byte at a time into a buffer that grows until two line
 * endings show up, and the server decides how long it is: an advertised
 * {@code Transfer-Encoding: chunked} followed by a stream that never ends the
 * line costs heap for as long as the connection stays warm, and none of those
 * bytes count against the body-size ceiling, because the read sits between
 * chunks. The ceiling is therefore checked before each append, so the buffer
 * never gets past the bound it is bounding, and the failure names no bytes -
 * echoing the line into the message would cost the same heap again.
 *
 * <p>No server and no socket: the reader takes the stream directly.
 *
 * @since 0.9.72+
 */
public class EepGetChunkLineTest {

    /** Bytes of junk a hostile server would stream past the bound. */
    private static final int JUNK = 10000;

    /**
     * A fixture group for the chunk-size line reader; it holds no state.
     *
     * @since 0.9.72+
     */
    public EepGetChunkLineTest() {}

    /**
     * A fetch with no socket attached, so only the stream set here is read.
     *
     * @return an EepGet whose chunk-size reader can be driven directly
     */
    private static EepGet newGet() {
        return new EepGet(I2PAppContext.getGlobalContext(), 0, null, "http://127.0.0.1/x");
    }

    /**
     * A chunk-size line with no line ending in it is refused at the ceiling, the
     * read stops there instead of draining the line, and the failure says nothing
     * about the bytes that caused it.
     */
    @Test
    public void testALineWithNoEndingIsRefusedAtTheCeiling() {
        StringBuilder junk = new StringBuilder("1\r");
        for (int i = 0; i < JUNK; i++)
            junk.append('A');
        junk.append("\r\n");
        CountingStream in = new CountingStream(junk.toString().getBytes(StandardCharsets.UTF_8));
        EepGet get = newGet();
        get._proxyIn = in;
        try {
            long len = get.readChunkLength();
            fail("a chunk-size line with no line ending was accepted, length " + len);
        } catch (IOException expected) {
            String msg = expected.getMessage();
            assertTrue("the message echoes the line it read: " + msg, msg.length() < 100);
            assertFalse("the message repeats the bytes read: " + msg, msg.contains("AAAA"));
        }
        assertEquals("the read went past the ceiling", EepGet.MAX_CHUNK_LINE, in.reads());
    }

    /**
     * The ceiling is far above the grammar RFC 9112 allows - 19 hex digits plus a
     * chunk extension - so a long extension still parses.
     *
     * @throws IOException never, the line is well-formed and inside the ceiling
     */
    @Test
    public void testALongButLegalLineStillParses() throws IOException {
        StringBuilder line = new StringBuilder("1a;");
        while (line.length() < EepGet.MAX_CHUNK_LINE - 8)
            line.append('x');
        line.append("\r\n");
        EepGet get = newGet();
        get._proxyIn = new CountingStream(line.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(26, get.readChunkLength());
    }

    /**
     * An ordinary chunk-size line, extension and all, is read whole and parsed,
     * and nothing past its line ending is consumed.
     *
     * @throws IOException never, the line is well-formed
     */
    @Test
    public void testAnOrdinaryLineIsReadWhole() throws IOException {
        String line = "1a;foo=bar\r\n";
        CountingStream in = new CountingStream(line.getBytes(StandardCharsets.UTF_8));
        EepGet get = newGet();
        get._proxyIn = in;
        assertEquals(26, get.readChunkLength());
        assertEquals("the reader ran past the line ending", line.length(), in.reads());
    }

    /**
     * A stream that ends inside the line is still reported as an end of stream,
     * not as a size problem.
     */
    @Test
    public void testEndOfStreamInsideTheLineIsAnEnd() {
        EepGet get = newGet();
        get._proxyIn = new CountingStream("1a\r".getBytes(StandardCharsets.UTF_8));
        try {
            long len = get.readChunkLength();
            fail("a chunk-size line that ended mid-line was accepted, length " + len);
        } catch (IOException expected) {
            assertTrue("wrong failure: " + expected.getMessage(),
                       expected.getMessage().contains("Chunk ended too soon"));
        }
    }

    /**
     * A stream that reports how many bytes were read, which is how the ceiling is
     * measured: the guard has to stop the read, not merely reject what it read.
     */
    private static class CountingStream extends InputStream {

        /** The bytes handed out, in order. */
        private final byte[] _data;
        /** How many bytes have been handed out so far. */
        private int _pos;

        /**
         * A stream over the given bytes.
         *
         * @param data the bytes to hand out
         */
        CountingStream(byte[] data) {
            _data = data;
        }

        /**
         * @return the next byte, or -1 once the bytes are exhausted
         */
        @Override
        public int read() {
            if (_pos >= _data.length)
                return -1;
            _pos++;
            return _data[_pos - 1] & 0xff;
        }

        /**
         * How many bytes the reader consumed before it stopped.
         *
         * @return the number of bytes read
         */
        int reads() {
            return _pos;
        }
    }
}
