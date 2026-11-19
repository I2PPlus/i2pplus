package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

import org.junit.Before;
import org.junit.Test;

/**
 * Pins what a block that arrives with no payload does to the receive side.
 *
 * <p>{@link Packet#getPayload()} is documented as possibly
 * null, and the in-order path guarded for it while the out-of-order path did
 * not: a null went straight into the map of buffered blocks. A null <em>value</em>
 * there is worse than no entry, because the code that promotes buffered blocks
 * into the readable run uses the map's value to mean "a block is held here" -
 * it stops at a null, leaving every block that arrived after the empty one
 * buffered and unreadable - and {@link MessageInputStream#close()} dereferences
 * every value it owns.
 *
 * @since 0.9.72+
 */
public class NullPayloadBlockTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public NullPayloadBlockTest() {}

    /** The stream under test, driven by block ids and payloads only. */
    private MessageInputStream _in;

    /**
     * A stream with generous sizing; nothing here runs a connection.
     */
    @Before
    public void setUp() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        _in = new MessageInputStream(ctx, 1730, 2048, 1730 * 2048, 2048);
    }

    /**
     * The defect: an empty block sitting in the buffer must not end the run of
     * blocks promoted into readable data. Block 3 arrived before the gap at 1 was
     * filled, so filling the gap has to promote through the empty block 2 and
     * deliver 3's bytes; at HEAD the promotion stopped at the empty block and the
     * data behind it was never readable.
     *
     * @throws IOException if the read fails
     */
    @Test
    public void testAnEmptyBlockDoesNotStopTheGapFill() throws IOException {
        assertTrue(_in.messageReceived(0, null));
        assertTrue(_in.messageReceived(2, null));
        assertTrue(_in.messageReceived(3, new ByteArray(new byte[] {42})));
        assertFalse("a duplicate empty block counted as new", _in.messageReceived(2, null));
        assertEquals("the gap was not reported once", 1, _in.getNacksLocked().length);
        assertEquals(1, _in.getNacksLocked()[0]);
        assertEquals("data was readable before the gap was filled", 0, _in.available());

        assertTrue(_in.messageReceived(1, new ByteArray(new byte[] {7})));
        assertEquals("the block behind the empty one was not promoted", 2, _in.available());
        assertEquals(7, _in.read());
        assertEquals(42, _in.read());
    }

    /**
     * Closing the stream dereferences every buffered block to drop its bytes, so
     * an empty block in the buffer took the close down with it. The stream object
     * is handed to application code by I2PSocket.getInputStream(), so that is an
     * application-visible throw on a normal close.
     */
    @Test
    public void testClosingAfterAnEmptyBlockDoesNotThrow() {
        _in.messageReceived(0, new ByteArray(new byte[] {1}));
        _in.messageReceived(5, null);
        _in.close();
        assertEquals(0, _in.getTotalReadySize());
    }

    /**
     * Marking the end of the stream promotes what is buffered, then a read has to
     * reach EOF rather than block or throw.
     *
     * @throws IOException if the read fails
     */
    @Test
    public void testCloseReceivedThenReadReachesEOF() throws IOException {
        _in.messageReceived(0, new ByteArray(new byte[] {1}));
        _in.messageReceived(2, null);
        _in.messageReceived(1, new ByteArray(new byte[] {9}));
        _in.closeReceived();
        assertEquals(1, _in.read());
        assertEquals(9, _in.read());
        assertEquals("EOF was not reached", -1, _in.read());
    }

    /**
     * An empty block costs nothing: it holds no bytes, so it must not be priced
     * into the ready total or the readable count, which is what the receive
     * window is measured against.
     *
     * @throws IOException if the readable count cannot be taken
     */
    @Test
    public void testAnEmptyBlockIsFree() throws IOException {
        _in.messageReceived(0, null);
        _in.messageReceived(2, null);
        _in.messageReceived(4, null);
        _in.messageReceived(1, null);
        _in.messageReceived(3, null);
        assertEquals(0, _in.getTotalReadySize());
        assertEquals(0, _in.available());
    }
}
