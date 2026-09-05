package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;

import net.i2p.crypto.SigType;
import net.i2p.router.transport.ntcp.NTCPTransport.BidTier;
import net.i2p.router.transport.ntcp.NTCPTransport.NetworkIdIssue;

import org.junit.Test;

/**
 *  Unit tests for the bid-selection decisions extracted from
 *  {@link NTCPTransport#bid}.
 *
 *  <p>Each helper is a pure function pinned without router context: the NTCP2
 *  capacity gate, the cross-network ban decision, the signature-type floor, and
 *  the capacity/cost cascade that maps to the cached shared bids.
 *
 *  @since 0.9.71+
 */
public class NTCPTransportBidDecisionTest {

    @Test
    public void testMessageFitsOnNTCP2() {
        assertTrue(NTCPTransport.isTooLargeForNTCP2(NTCPConnection.NTCP2_MAX_MSG_SIZE) == false);
        assertFalse(NTCPTransport.isTooLargeForNTCP2(NTCPConnection.NTCP2_MAX_MSG_SIZE + 7));
    }

    @Test
    public void testMessageTooLargeOnNTCP2() {
        assertTrue(NTCPTransport.isTooLargeForNTCP2(NTCPConnection.NTCP2_MAX_MSG_SIZE + 8));
        assertFalse(NTCPTransport.isTooLargeForNTCP2(NTCPConnection.NTCP2_MAX_MSG_SIZE));
    }

    @Test
    public void testNetworkIdOk() {
        assertEquals(NetworkIdIssue.OK, NTCPTransport.classifyNetworkId(42, 42));
    }

    @Test
    public void testNetworkIdNoNetwork() {
        assertEquals(NetworkIdIssue.NO_NETWORK, NTCPTransport.classifyNetworkId(-1, 42));
    }

    @Test
    public void testNetworkIdWrongNetwork() {
        assertEquals(NetworkIdIssue.WRONG_NETWORK, NTCPTransport.classifyNetworkId(7, 42));
    }

    @Test
    public void testSigTypeNull() {
        assertFalse(NTCPTransport.isSigTypeUsable(null));
    }

    @Test
    public void testSigTypeAvailable() {
        assertTrue(NTCPTransport.isSigTypeUsable(SigType.DSA_SHA1));
    }

    @Test
    public void testCascadeCapacityNormalCost() {
        assertEquals(BidTier.SLOW, NTCPTransport.chooseBidTier(true, false));
    }

    @Test
    public void testCascadeCapacityHighCost() {
        assertEquals(BidTier.SLOW_COST, NTCPTransport.chooseBidTier(true, true));
    }

    @Test
    public void testCascadeAtCapacityNormalCost() {
        assertEquals(BidTier.NEAR_CAPACITY, NTCPTransport.chooseBidTier(false, false));
    }

    @Test
    public void testCascadeAtCapacityHighCost() {
        assertEquals(BidTier.NEAR_CAPACITY_COST, NTCPTransport.chooseBidTier(false, true));
    }
}