package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

/**
 * Pins the input buffering {@link Peer#buffered(InputStream)} applies to a
 * connection we dial, which the accepted-connection path has had for years and the
 * dialed path has not. The framing reads in {@code PeerConnectionIn} go through a
 * {@code DataInputStream}, which asks the stream beneath it once per primitive, so
 * the tests here count how many calls one PIECE header costs against the socket
 * stream, buffered and unbuffered.
 *
 * @since 0.9.72+
 */
public class PeerInputBufferTest {

    /** A PIECE header: length, message type, piece index, block offset. */
    private static final int PIECE_HEADER_BYTES = 4 + 1 + 4 + 4;

    /**
     * How many reads a PIECE header costs against an unbuffered stream: one per
     * primitive, which is what {@code DataInputStream} does, one call for the length,
     * one for the type and one each for the piece index and the block offset.
     */
    private static final int PIECE_HEADER_CALLS = 4;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one;
     * the buffering helper is static and carries no state.
     *
     * @since 0.9.72+
     */
    public PeerInputBufferTest() {}

    /**
     * Input stream over a fixed byte array that counts every time it is asked for
     * bytes, one byte at a time or in a block. That total is the cost that matters:
     * each call against the streaming socket takes a lock and copies what it asks for.
     */
    private static final class CountingInputStream extends InputStream {

        /** The bytes handed out, in order. */
        private final ByteArrayInputStream _delegate;

        /** How many times the delegate has been asked for bytes. */
        private int _calls;

        /**
         * @param data the bytes the stream hands out, in order
         */
        CountingInputStream(byte[] data) {
            _delegate = new ByteArrayInputStream(data);
        }

        /**
         * @return how many times the stream has been asked for bytes, at any size
         */
        int calls() {
            return _calls;
        }

        @Override
        public int read() throws IOException {
            _calls++;
            return _delegate.read();
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            _calls++;
            return _delegate.read(buf, off, len);
        }

        @Override
        public int available() {
            return _delegate.available();
        }
    }

    /**
     * A PIECE header as it arrives on the wire: total message length, the PIECE
     * message type, a piece index and a zero block offset.
     *
     * @return 13 bytes holding one PIECE header
     */
    private static byte[] pieceHeader() {
        return new byte[] {
            0, 0, 0, 32,          // total message length, value unused here
            7,                    // PIECE
            0, 0, 0, 42,          // piece index 42
            0, 0, 0, 0            // block offset 0
        };
    }

    /**
     * Framing one PIECE header through an unbuffered {@code DataInputStream} costs
     * one call against the socket stream per primitive, which is what the buffer
     * exists to remove.
     *
     * @throws IOException if the fixture header cannot be framed
     */
    @Test
    public void testUnbufferedHeaderCostsOneCallPerPrimitive() throws IOException {
        CountingInputStream raw = new CountingInputStream(pieceHeader());
        DataInputStream din = new DataInputStream(raw);
        din.readInt();
        din.readByte();
        din.readInt();
        din.readInt();
        assertEquals(PIECE_HEADER_CALLS, raw.calls());
    }

    /**
     * The same header through {@link Peer#buffered(InputStream)} reaches the socket
     * stream exactly once, in the buffer's own fill, so the framing costs no per-field
     * calls.
     *
     * @throws IOException if the fixture header cannot be framed
     */
    @Test
    public void testBufferedHeaderReachesTheSocketOnce() throws IOException {
        CountingInputStream raw = new CountingInputStream(pieceHeader());
        DataInputStream din = new DataInputStream(Peer.buffered(raw));
        din.readInt();
        din.readByte();
        din.readInt();
        din.readInt();
        assertEquals(1, raw.calls());
    }

    /**
     * A raw stream is wrapped, so a connection we dial is buffered the way an
     * accepted one is.
     */
    @Test
    public void testRawStreamIsWrapped() {
        InputStream in = Peer.buffered(new ByteArrayInputStream(new byte[0]));
        assertTrue(in instanceof BufferedInputStream);
    }

    /**
     * A stream that is already buffered is returned as it is, so the accepted
     * connection is not wrapped twice and its readahead position is kept.
     */
    @Test
    public void testBufferedStreamIsNotWrappedTwice() {
        BufferedInputStream in = new BufferedInputStream(new ByteArrayInputStream(new byte[0]));
        assertSame(in, Peer.buffered(in));
    }

    /**
     * A block body larger than the buffer still arrives intact and byte for byte, and
     * it is not copied through the buffer on the way, so buffering a connection does
     * not disturb the payload framing or add a copy per block.
     *
     * @throws IOException if the fixture header and body cannot be read back
     */
    @Test
    public void testBlockBodySurvivesTheBuffer() throws IOException {
        byte[] body = new byte[16 * 1024];
        new Random(0x5EEDL).nextBytes(body);
        byte[] all = new byte[PIECE_HEADER_BYTES + body.length];
        System.arraycopy(pieceHeader(), 0, all, 0, PIECE_HEADER_BYTES);
        System.arraycopy(body, 0, all, PIECE_HEADER_BYTES, body.length);

        CountingInputStream raw = new CountingInputStream(all);
        DataInputStream din = new DataInputStream(Peer.buffered(raw));
        din.readInt();
        din.readByte();
        din.readInt();
        din.readInt();
        byte[] got = new byte[body.length];
        din.readFully(got);
        assertTrue(Arrays.equals(body, got));
        // One fill for the header, then the body goes straight into the caller's
        // array because it is larger than the buffer
        assertEquals(2, raw.calls());
    }
}
