package net.i2p.router.transport.udp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.crypto.SigType;
import org.junit.Test;

/**
 * Pins bid-decision logic extracted from UDPTransport.bid(). All helpers are
 * pure static functions in UDPTransport; no router context is required.
 *
 * @since 0.9.71+
 */
public class BidDecisionTest {

    @Test
    public void testIsTooLargeBoundary() {
        assertFalse(UDPTransport.isTooLarge(OutboundMessageState.MAX_MSG_SIZE));
        assertTrue(UDPTransport.isTooLarge(OutboundMessageState.MAX_MSG_SIZE + 1));
        assertFalse(UDPTransport.isTooLarge(0));
    }

    @Test
    public void testIsDifferentNetwork() {
        assertTrue(UDPTransport.isDifferentNetwork(1, 0));
        assertFalse(UDPTransport.isDifferentNetwork(0, 0));
        assertTrue(UDPTransport.isDifferentNetwork(-1, 0));
    }

    @Test
    public void testRejectCppBugCost10() {
        assertTrue(UDPTransport.rejectCppBug(10, "0.9.49"));
        assertTrue(UDPTransport.rejectCppBug(10, "0.9.52"));
        assertFalse(UDPTransport.rejectCppBug(10, "0.9.53"));
        assertFalse(UDPTransport.rejectCppBug(10, "0.10.0"));
        // non-cost-10 addresses never hit the version window
        assertFalse(UDPTransport.rejectCppBug(5, "0.9.49"));
    }

    @Test
    public void testRejectCppBugCost9Exact() {
        assertTrue(UDPTransport.rejectCppBug(9, "0.9.52"));
        assertFalse(UDPTransport.rejectCppBug(9, "0.9.51"));
        assertFalse(UDPTransport.rejectCppBug(9, "0.9.53"));
        assertFalse(UDPTransport.rejectCppBug(9, null));
    }

    @Test
    public void testSigTypeUnsupported() {
        assertTrue(UDPTransport.sigTypeUnsupported(null));
        assertFalse(UDPTransport.sigTypeUnsupported(SigType.DSA_SHA1));
        assertFalse(UDPTransport.sigTypeUnsupported(SigType.ECDSA_SHA256_P256));
    }

    @Test
    public void testNeedsMinimumSigTypeVersion() {
        // DSA never needs a minimum
        assertFalse(UDPTransport.needsMinimumSigTypeVersion(SigType.DSA_SHA1, "0.9.16"));
        assertFalse(UDPTransport.needsMinimumSigTypeVersion(SigType.DSA_SHA1, "0.9.17"));
        // non-DSA + older than 0.9.17
        assertTrue(UDPTransport.needsMinimumSigTypeVersion(SigType.ECDSA_SHA256_P256, "0.9.16"));
        // boundary at the minimum and above
        assertFalse(UDPTransport.needsMinimumSigTypeVersion(SigType.ECDSA_SHA256_P256, "0.9.17"));
        assertFalse(UDPTransport.needsMinimumSigTypeVersion(SigType.ECDSA_SHA256_P256, "0.9.50"));
    }
}