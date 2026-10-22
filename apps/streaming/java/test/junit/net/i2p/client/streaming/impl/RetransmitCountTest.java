package net.i2p.client.streaming.impl;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * How much an RTO retransmits (5.1). RFC 5681 section 4.3 says a retransmit
 * timeout resends no more than half the packets in flight, and the connection
 * caps that again with its own retransmit limit. The floor matters as much as
 * the ceiling: a window holding one packet still has to resend it, or a
 * single-loss timeout stalls the stream until the next timer.
 */
public class RetransmitCountTest {

    /**
     * Half the window, rounded down, is the RFC ceiling.
     */
    @Test
    public void testHalfTheWindow() {
        assertEquals(50, Connection.retransmitCount(100, 1000));
        assertEquals(1, Connection.retransmitCount(3, 1000));
        assertEquals(2, Connection.retransmitCount(4, 1000));
        assertEquals(2, Connection.retransmitCount(5, 1000));
    }

    /**
     * The connection's own cap binds below the half-window figure.
     */
    @Test
    public void testTheCapBinds() {
        assertEquals(4, Connection.retransmitCount(100, 4));
        assertEquals(1, Connection.retransmitCount(100, 1));
        assertEquals(4, Connection.retransmitCount(8, 4));
    }

    /**
     * A window of one or two packets still resends one - the floor is what
     * stops a lone loss from being skipped entirely.
     */
    @Test
    public void testALonePacketIsStillResent() {
        assertEquals(1, Connection.retransmitCount(1, 4));
        assertEquals(1, Connection.retransmitCount(2, 4));
    }
}
