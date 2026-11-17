package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the proven-responder proof window: fresh successful-participation
 * proof counts, stale or absent proof does not.
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
    public void testWindowBoundary() {
        // one ms inside the window is proof, one ms outside is not
        assertTrue(TunnelPeerSelector.isProvenResponder(NOW - (WINDOW - 1), NOW));
        assertFalse(TunnelPeerSelector.isProvenResponder(NOW - (WINDOW + 1), NOW));
    }
}
