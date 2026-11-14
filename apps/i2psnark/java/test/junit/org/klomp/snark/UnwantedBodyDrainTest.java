package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

/**
 * Tests for consuming a message body we did not ask for. The body has to be read: the socket
 * stream inherits <code>InputStream.skip()</code>, which reports that it skipped nothing, so a
 * skip only consumes whatever a buffer happens to hold already and leaves the rest of the body
 * to be parsed as the next message's header.
 *
 * @since 0.9.72+
 */
public class UnwantedBodyDrainTest {

    /** Body size used by both tests, well past any buffer a stream holds. */
    private static final int BODY = 40 * 1024;

    /** The byte that follows the body, the header marker of the next message. */
    private static final int MARKER = 0x09;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public UnwantedBodyDrainTest() {}

    /**
     * A stream that reads normally and cannot skip, which is what the streaming socket stream
     * is: it declares no skip of its own.
     */
    private static class NoSkipStream extends InputStream {

        /** The bytes being handed out. */
        private final ByteArrayInputStream _in;

        /**
         * @param bs the bytes the stream returns
         */
        NoSkipStream(byte[] bs) {
            _in = new ByteArrayInputStream(bs);
        }

        /**
         * @return the next byte, or -1 at the end
         */
        @Override
        public int read() {
            return _in.read();
        }

        /**
         * @param b the array to fill
         * @param off where in it to start
         * @param len how many bytes to read
         * @return the number of bytes read, or -1 at the end
         */
        @Override
        public int read(byte[] b, int off, int len) {
            return _in.read(b, off, len);
        }

        /**
         * Reports that nothing was skipped, as {@code InputStream} does.
         *
         * @param n the bytes asked to be skipped
         * @return zero, because nothing is consumed without a read
         */
        @Override
        public long skip(long n) {
            return 0;
        }
    }

    /**
     * A body of {@link #BODY} filler bytes followed by the next message's header marker.
     *
     * @return the bytes of one unwanted message and the start of the next
     */
    private static byte[] bodyThenMarker() {
        byte[] bs = new byte[BODY + 1];
        java.util.Arrays.fill(bs, 0, BODY, (byte) 0x7f);
        bs[BODY] = (byte) MARKER;
        return bs;
    }

    /**
     * Reading the body away leaves the stream positioned exactly on the next message.
     *
     * @throws Exception if the body cannot be read
     */
    @Test
    public void testDrainingKeepsTheStreamAligned() throws Exception {
        InputStream in = new NoSkipStream(bodyThenMarker());
        PeerConnectionIn.drain(in, new byte[8 * 1024], BODY);
        assertEquals(MARKER, in.read());
        assertEquals(-1, in.read());
    }

    /**
     * Skipping the same body on the same kind of stream consumes only part of it, which is why
     * the body is read instead: whatever is left is parsed as a header.
     *
     * @throws Exception if the body cannot be read
     */
    @Test
    public void testSkippingLeavesTheBodyPartlyInPlace() throws Exception {
        InputStream raw = new NoSkipStream(bodyThenMarker());
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw));
        int skipped = in.skipBytes(BODY);
        assertTrue("skip consumed the whole body: " + skipped, skipped < BODY);
        int next = in.read();
        assertEquals("the next byte read is not the leftover body byte", 0x7f, next);
    }

    /**
     * A stream that ends in the middle of an unwanted body is an error, not a silent
     * desync of everything after it.
     *
     * @throws Exception if the stream misbehaves in some other way
     */
    @Test
    public void testAShortBodyIsAnError() throws Exception {
        InputStream in = new NoSkipStream(new byte[10]);
        try {
            PeerConnectionIn.drain(in, new byte[8 * 1024], BODY);
            fail("a body that ends early must be reported");
        } catch (IOException expected) { /* the stream is done */ }
    }
}
