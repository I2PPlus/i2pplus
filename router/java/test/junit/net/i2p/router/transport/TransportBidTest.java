package net.i2p.router.transport;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 *  Tests for TransportBid.
 *  Pure data class - no I2P context needed.
 */
public class TransportBidTest {

    @Test
    public void testDefaultLatency() {
        TransportBid bid = new TransportBid();
        assertEquals(-1, bid.getLatencyMs());
    }

    @Test
    public void testSetLatency() {
        TransportBid bid = new TransportBid();
        bid.setLatencyMs(100);
        assertEquals(100, bid.getLatencyMs());
    }

    @Test
    public void testZeroLatency() {
        TransportBid bid = new TransportBid();
        bid.setLatencyMs(0);
        assertEquals(0, bid.getLatencyMs());
    }

    @Test
    public void testHighLatency() {
        TransportBid bid = new TransportBid();
        bid.setLatencyMs(TransportBid.TRANSIENT_FAIL);
        assertEquals(TransportBid.TRANSIENT_FAIL, bid.getLatencyMs());
    }

    @Test
    public void testTransientFailConstant() {
        assertEquals(999999, TransportBid.TRANSIENT_FAIL);
    }

    @Test
    public void testDefaultTransportNull() {
        TransportBid bid = new TransportBid();
        assertNull(bid.getTransport());
    }

    @Test
    public void testMultipleBids() {
        TransportBid cheap = new TransportBid();
        cheap.setLatencyMs(10);

        TransportBid expensive = new TransportBid();
        expensive.setLatencyMs(500);

        assertTrue(cheap.getLatencyMs() < expensive.getLatencyMs());
    }

    @Test
    public void testIsTransientFail() {
        TransportBid bid = new TransportBid();
        bid.setLatencyMs(TransportBid.TRANSIENT_FAIL);
        assertEquals(TransportBid.TRANSIENT_FAIL, bid.getLatencyMs());
    }
}
