package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests the NACK list cap.
 *
 * <p>The NACK count on the wire is a single byte, so a list longer than
 * {@link Packet#MAX_NACKS} wraps the count (300 becomes 44) and the receiver
 * mis-parses the remaining NACK bytes as options or payload and drops the
 * ACK. Generation ({@link MessageInputStream#getNacksLocked()}) and the
 * write path both cap at 255, keeping the lowest (head-of-line) gaps the
 * sender's recovery acts on first.
 *
 * @since 0.9.72+
 */
public class MessageInputStreamNackCapTest {

    private MessageInputStream _in;

    /** Build a stream with generous sizing; the tests only drive block ids. */
    @Before
    public void setUp() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        _in = new MessageInputStream(ctx, 1730, 2048, 1730 * 2048, 2048);
    }

    /** Below the cap the list is returned whole, in ascending order. */
    @Test
    public void testUnderCapReturnsAll() {
        // block ids start at 0 (the SYN's block)
        _in.messageReceived(0, null);
        for (long id = 1; id <= 100; id++) {
            if (id == 5 || id == 50)
                continue; // the two gaps
            _in.messageReceived(id, null);
        }
        long[] nacks = _in.getNacksLocked();
        // the scan is exclusive of the highest block, so 100 is not listed
        assertEquals(2, nacks.length);
        assertEquals(5, nacks[0]);
        assertEquals(50, nacks[1]);
    }

    /** Above the cap the list is truncated at MAX_NACKS, lowest gaps first. */
    @Test
    public void testOverCapKeepsLowest() {
        _in.messageReceived(0, null);
        // ids 1..1000 with every third one missing -> 332 gaps, far over the cap
        for (long id = 1; id <= 1000; id++) {
            if (id % 3 == 0)
                continue;
            _in.messageReceived(id, null);
        }
        long[] nacks = _in.getNacksLocked();
        assertEquals(Packet.MAX_NACKS, nacks.length);
        assertEquals(3, nacks[0]);
        assertEquals(6, nacks[1]);
        assertEquals(9, nacks[2]);
        for (int i = 1; i < nacks.length; i++)
            assertTrue("ascending", nacks[i] > nacks[i - 1]);
    }

    /** Exactly at the cap: no truncation, full list returned. */
    @Test
    public void testExactlyAtCap() {
        // ids 0 and the odd ids 1..511 -> gaps 2,4,...,510 = 255, exactly MAX_NACKS
        _in.messageReceived(0, null);
        for (long id = 1; id <= 511; id += 2)
            _in.messageReceived(id, null);
        long[] nacks = _in.getNacksLocked();
        assertEquals(Packet.MAX_NACKS, nacks.length);
        assertEquals(2, nacks[0]);
        assertEquals(510, nacks[nacks.length - 1]);
    }
}
