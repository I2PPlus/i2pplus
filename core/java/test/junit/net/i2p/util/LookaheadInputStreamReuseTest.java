package net.i2p.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;

/**
 * Pins what a reused lookahead stream holds after an initialization that fails.
 *
 * <p>initialize() fills the footer buffer with a blocking read that throws once the
 * source runs out, and that read writes into the buffer as it goes. With no cleanup on
 * the failure path the buffer was left holding the few bytes the new source gave plus
 * the tail of whatever the previous user of the stream had put there - and these
 * streams are pooled and handed out again, so that tail came from an unrelated stream.
 * getFooter() returns the buffer as it is, and the EOF flag still said the stream was
 * open.
 *
 * @since 0.9.72+
 */
public class LookaheadInputStreamReuseTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public LookaheadInputStreamReuseTest() {}

    /**
     * How many bytes these cases hide from the reader.
     */
    private static final int FOOTER = 8;

    /**
     * The defect: a source too short to fill the footer leaves the buffer half new
     * bytes and half another stream's bytes, and the stream claims it is still open.
     *
     * @throws IOException if a source that should work reports a failure
     */
    @Test
    public void testAFailedReinitializationZeroesTheFooter() throws IOException {
        LookaheadInputStream in = new LookaheadInputStream(FOOTER);
        byte[] first = new byte[256];
        Arrays.fill(first, (byte) 0xAA);
        in.initialize(new ByteArrayInputStream(first));
        while (in.read() >= 0) { /* drain the first source */ }
        assertArrayEquals(new byte[] {(byte) 0xAA, (byte) 0xAA, (byte) 0xAA, (byte) 0xAA,
                                      (byte) 0xAA, (byte) 0xAA, (byte) 0xAA, (byte) 0xAA},
                          in.getFooter());

        try {
            in.initialize(new ByteArrayInputStream(new byte[] {1, 2, 3}));
            fail("a 3-byte source filled an 8-byte footer");
        } catch (EOFException expected) {
            // expected
        }

        assertArrayEquals("the footer kept bytes from before the failed read",
                          new byte[FOOTER], in.getFooter());
        assertTrue("the stream said it was still open", in.getEOFReached());
        assertEquals(-1, in.read());
        assertEquals(-1, in.read(new byte[4], 0, 4));
    }

    /**
     * The same failure over a footer that holds distinct bytes, so a mix of old and
     * new would show up as a mix rather than as a run of one value.
     *
     * @throws IOException if a source that should work reports a failure
     */
    @Test
    public void testAPartialFooterKeepsNothingOfThePreviousStream() throws IOException {
        LookaheadInputStream in = new LookaheadInputStream(FOOTER);
        byte[] first = new byte[FOOTER];
        for (int i = 0; i < FOOTER; i++) {
            first[i] = (byte) (i + 1);
        }
        in.initialize(new ByteArrayInputStream(first));
        assertArrayEquals(first, in.getFooter());

        try {
            in.initialize(new ByteArrayInputStream(new byte[] {0x7f, 0x7f, 0x7f}));
            fail("a 3-byte source filled an 8-byte footer");
        } catch (EOFException expected) {
            // expected
        }
        for (int i = 0; i < in.getFooter().length; i++) {
            assertEquals("footer byte " + i + " survived the failed read", 0, in.getFooter()[i]);
        }
    }

    /**
     * A re-initialization that succeeds still replaces the whole footer, so the
     * zeroing on the failure path is not the only thing keeping the buffer honest.
     *
     * @throws IOException if a source that should work reports a failure
     */
    @Test
    public void testASuccessfulReinitializationReplacesTheFooter() throws IOException {
        LookaheadInputStream in = new LookaheadInputStream(FOOTER);
        byte[] first = new byte[64];
        Arrays.fill(first, (byte) 0x11);
        in.initialize(new ByteArrayInputStream(first));

        byte[] second = new byte[64];
        Arrays.fill(second, (byte) 0x22);
        in.initialize(new ByteArrayInputStream(second));

        byte[] out = new byte[64];
        int n = 0;
        int count;
        while (n < out.length && (count = in.read(out, n, out.length - n)) >= 0) {
            n += count;
        }
        assertEquals(64 - FOOTER, n);
        assertArrayEquals(Arrays.copyOfRange(second, 64 - FOOTER, 64), in.getFooter());
    }

    /**
     * A source that is empty from the start fails the same way, without leaving the
     * stream half-initialized.
     *
     * @throws IOException if a source that should work reports a failure
     */
    @Test
    public void testAnEmptySourceFailsTheInitialization() throws IOException {
        LookaheadInputStream in = new LookaheadInputStream(FOOTER);
        try {
            in.initialize(new ByteArrayInputStream(new byte[0]));
            fail("an empty source filled an 8-byte footer");
        } catch (EOFException expected) {
            // expected
        }
        assertArrayEquals(new byte[FOOTER], in.getFooter());
        assertTrue(in.getEOFReached());
        assertEquals(-1, in.read());
    }
}
