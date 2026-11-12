package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;

/**
 * Tests what a retransmitted block does to the receive buffer, and that
 * {@link MessageInputStream#read(byte[], int, int)} validates its arguments
 * before it blocks. A retransmit of a block that is already held used to replace
 * the buffered payload and be counted as new traffic.
 *
 * @since 0.9.72+
 */
public class MessageInputStreamDuplicateTest {

    /** Context for the stream's logging and clock. */
    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();

    /** The stream under test, rebuilt for each case. */
    private MessageInputStream _in;

    /** Build a stream with small blocks so the tests can hold a whole one. */
    @Before
    public void setUp() {
        _in = new MessageInputStream(CTX, 1024, 64, 64 * 1024, 64);
    }

    /**
     * A retransmit of an out-of-order block that is already buffered must be
     * reported as a duplicate and must not replace the payload on the map.
     *
     * @throws IOException if the buffered blocks cannot be read back
     */
    @Test
    public void testRetransmitKeepsTheBufferedPayload() throws IOException {
        assertTrue(_in.messageReceived(0, block("aaaa")));
        assertTrue(_in.messageReceived(2, block("cccc")));
        assertFalse("a retransmit of a held block is not new traffic",
                    _in.messageReceived(2, block("dddd")));
        assertTrue(_in.messageReceived(1, block("bbbb")));

        _in.closeReceived();
        byte[] read = new byte[12];
        assertEquals(12, DataHelper.read(_in, read));
        assertEquals("aaaabbbbcccc", new String(read, 0, 12, StandardCharsets.US_ASCII));
    }

    /**
     * The same on a stream closed locally, where the held block is replaced by a
     * placeholder: the retransmit is still only a duplicate.
     */
    @Test
    public void testRetransmitOnLocallyClosedStreamIsNotNewTraffic() {
        assertTrue(_in.messageReceived(0, block("aaaa")));
        _in.close();
        assertTrue(_in.messageReceived(5, block("eeee")));
        assertFalse(_in.messageReceived(5, block("ffff")));
    }

    /**
     * A block that has already been promoted to the ready list is a duplicate too,
     * which is the case the buffer has always handled.
     */
    @Test
    public void testRetransmitOfReadyBlockIsNotNewTraffic() {
        assertTrue(_in.messageReceived(0, block("aaaa")));
        assertFalse(_in.messageReceived(0, block("dddd")));
    }

    @Test(expected = NullPointerException.class, timeout = 5000)
    public void testReadRejectsNullBuffer() throws IOException {
        _in.read(null, 0, 4);
    }

    @Test(expected = IndexOutOfBoundsException.class, timeout = 5000)
    public void testReadRejectsNegativeOffset() throws IOException {
        _in.read(new byte[4], -1, 2);
    }

    @Test(expected = IndexOutOfBoundsException.class, timeout = 5000)
    public void testReadRejectsNegativeLength() throws IOException {
        _in.read(new byte[4], 0, -1);
    }

    @Test(expected = IndexOutOfBoundsException.class, timeout = 5000)
    public void testReadRejectsRangePastTheEnd() throws IOException {
        _in.read(new byte[4], 2, 4);
    }

    /**
     * The range check has to be written as offset &gt; length - count: adding the
     * two overflows for a huge count and the range then looks legal.
     *
     * @throws IOException not expected; the range is rejected before any read
     */
    @Test(expected = IndexOutOfBoundsException.class, timeout = 5000)
    public void testReadRejectsOverflowingRange() throws IOException {
        _in.read(new byte[4], Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1);
    }

    /**
     * A zero-length read returns at once by contract, and must not wait for data
     * that may never come.
     *
     * @throws IOException not expected; the zero-length read returns at once
     */
    @Test
    public void testZeroLengthReadDoesNotBlock() throws IOException {
        _in.setReadTimeout(5000);
        long start = System.currentTimeMillis();
        assertEquals(0, _in.read(new byte[4], 0, 0));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("a zero-length read waited " + elapsed + "ms", elapsed < 1000);
    }

    /**
     * A bad range fails immediately rather than after the read timeout, so the
     * checks run before anything waits.
     *
     * @throws IOException not expected; the range is rejected before any wait
     */
    @Test
    public void testBadRangeFailsBeforeWaiting() throws IOException {
        _in.setReadTimeout(5000);
        long start = System.currentTimeMillis();
        try {
            _in.read(new byte[4], 0, 5);
            fail("A range past the end of the buffer must be rejected");
        } catch (IndexOutOfBoundsException expected) {
            // expected
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("the range check ran after a wait: " + elapsed + "ms", elapsed < 1000);
    }

    /**
     * A payload block of the given ASCII text.
     *
     * @param text the bytes to carry
     * @return a payload block holding those bytes
     */
    private static ByteArray block(String text) {
        return new ByteArray(text.getBytes(StandardCharsets.US_ASCII));
    }
}
