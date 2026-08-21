package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the proven-responder bias stage of the peer quality cascade: fresh
 * successful-participation proof sorts first, stale or absent proof does not,
 * and same-side peers tie so later stages decide.
 *
 * @since 0.9.71+
 */
public class ProvenResponderTest {

    // must exceed the 60-min proof window so test timestamps stay positive
    private static final long NOW = 50_000_000L;
    private static final long WINDOW = TunnelPeerSelector.PROVEN_RESPONDER_WINDOW_MS;

    @Test
    public void testFreshProofIsProven() {
        assertTrue(TunnelPeerSelector.isProvenResponder(NOW, NOW));
        assertTrue(TunnelPeerSelector.isProvenResponder(NOW - WINDOW, NOW));
        // exactly at the window edge is still fresh
        assertTrue(TunnelPeerSelector.isProvenResponder(NOW - WINDOW, NOW - 0));
    }

    @Test
    public void testStaleOrAbsentProofIsNotProven() {
        assertFalse(TunnelPeerSelector.isProvenResponder(0, NOW));
        assertFalse(TunnelPeerSelector.isProvenResponder(NOW - WINDOW - 1, NOW));
        assertFalse(TunnelPeerSelector.isProvenResponder(-1, NOW));
    }

    @Test
    public void testCompareProvenFirst() {
        long fresh = NOW - 1000;
        long none = 0;
        assertEquals(-1, TunnelPeerSelector.compareProven(fresh, none, NOW));
        assertEquals(1, TunnelPeerSelector.compareProven(none, fresh, NOW));
    }

    @Test
    public void testSameSideTies() {
        long a = NOW - 1000, b = NOW - 2000;
        assertEquals(0, TunnelPeerSelector.compareProven(a, b, NOW));
        assertEquals(0, TunnelPeerSelector.compareProven(0, 0, NOW));
        // both stale tie too, even though timestamps differ
        assertEquals(0, TunnelPeerSelector.compareProven(NOW - WINDOW - 1, NOW - WINDOW * 2, NOW));
    }

    @Test
    public void testWindowBoundary() {
        // one ms inside vs one ms outside the window splits the comparison
        long inside = NOW - (WINDOW - 1);
        long outside = NOW - (WINDOW + 1);
        assertEquals(-1, TunnelPeerSelector.compareProven(inside, outside, NOW));
        assertEquals(1, TunnelPeerSelector.compareProven(outside, inside, NOW));
    }
}
