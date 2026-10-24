package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the SSU2 post-quantum handshake geometry to the sizes the wire format
 * and i2pd assume. The Noise F token - the ML-KEM encapsulation key in the
 * session request, the ciphertext in the session created, each with its MAC -
 * sits between the ephemeral key and the payload blocks, so it is counted in
 * the packet length, in the payload staging offset, and in the padding budget.
 *
 * <p>The version 4 session request is the tight case: header, key, frame and
 * MAC come to 1280 bytes, so {@link PeerState2#MIN_MLKEM768_IPV4_MTU} and
 * {@link PeerState2#MIN_MLKEM768_IPV6_MTU} are exactly that layout with the
 * smallest possible block set and no padding. These tests fail if the frame
 * sizes and the MTU minimums ever drift apart.
 *
 * @since 0.9.72+
 */
public class SSU2PQHandshakeSizeTest {

    /** A session request's smallest block set: date/time (7) plus an empty padding block (3). */
    private static final int MIN_REQUEST_BLOCKS = 7 + 3;

    /** A session created's smallest block set: date/time (7) plus an IPv4 address block (10). */
    private static final int MIN_CREATED_BLOCKS = 7 + 10;

    /** A padding block header plus the largest padding a session message type allows. */
    private static final int FULL_PADDING_BLOCK =
            SSU2Payload.BLOCK_HEADER_SIZE + SSU2Util.PADDING_MAX_SESSION_REQUEST;

    @Test
    public void testRequestFrameLengths() {
        assertEquals(0, SSU2Util.pqRequestExtra(2));
        // 16-byte MAC plus the 800-byte MLKEM-512 encapsulation key
        assertEquals(816, SSU2Util.pqRequestExtra(3));
        // 16-byte MAC plus the 1184-byte MLKEM-768 encapsulation key
        assertEquals(1200, SSU2Util.pqRequestExtra(4));
    }

    @Test
    public void testCreatedFrameLengths() {
        assertEquals(0, SSU2Util.pqCreatedExtra(2));
        // 16-byte MAC plus the 768-byte MLKEM-512 ciphertext
        assertEquals(784, SSU2Util.pqCreatedExtra(3));
        // 16-byte MAC plus the 1088-byte MLKEM-768 ciphertext
        assertEquals(1104, SSU2Util.pqCreatedExtra(4));
    }

    /**
     * The MTU minimums for version 4 are derived from the request layout, so
     * they have to stay equal to it.
     */
    @Test
    public void testRequestWireSizeMatchesTheMtuMinimums() {
        int frame = SSU2Util.pqRequestExtra(4);
        assertEquals(PeerState2.MIN_MLKEM768_IPV4_MTU,
                     PacketBuilder2.handshakeWireSize(false, MIN_REQUEST_BLOCKS, frame));
        assertEquals(PeerState2.MIN_MLKEM768_IPV6_MTU,
                     PacketBuilder2.handshakeWireSize(true, MIN_REQUEST_BLOCKS, frame));
    }

    /**
     * A version 4 session created is smaller than the request because the
     * ciphertext is smaller than the encapsulation key, so it always fits the
     * minimum MTU and still has room for at least an empty padding block.
     */
    @Test
    public void testCreatedFitsMinimumMtuWithRoomForPadding() {
        int frame = SSU2Util.pqCreatedExtra(4);
        int wire = PacketBuilder2.handshakeWireSize(true, MIN_CREATED_BLOCKS, frame);
        assertTrue("session created does not fit the minimum MTU: " + wire,
                   wire <= PeerState2.MIN_MTU);
        assertTrue("no room left for a padding block",
                   PeerState2.MIN_MTU - wire >= SSU2Payload.BLOCK_HEADER_SIZE);
    }

    /**
     * A version 4 request at the minimum MTU has no padding room at all, which
     * is why the send path sends it without padding rather than giving up.
     */
    @Test
    public void testRequestHasNoPaddingRoomAtTheMinimumMtu() {
        int frame = SSU2Util.pqRequestExtra(4);
        int wire = PacketBuilder2.handshakeWireSize(true, MIN_REQUEST_BLOCKS, frame);
        assertEquals(PeerState2.MIN_MLKEM768_IPV6_MTU, wire);
        assertTrue("the minimum MTU should leave no padding room",
                   PeerState2.MIN_MTU - wire < SSU2Payload.BLOCK_HEADER_SIZE);
    }

    /**
     * Versions 2 and 3 keep the flat minimum-MTU budget, as upstream does -
     * the 512 frame leaves plenty of room, only 768 is tight.
     */
    @Test
    public void testSmallerVersionsFitTheMinimumMtuWithFullPadding() {
        for (int version : new int[] {2, 3}) {
            int frame = SSU2Util.pqRequestExtra(version);
            int wire = PacketBuilder2.handshakeWireSize(true, MIN_REQUEST_BLOCKS, frame);
            int room = PeerState2.MIN_MTU - wire;
            assertTrue("v" + version + " cannot use its full padding at the minimum MTU",
                       room >= FULL_PADDING_BLOCK);
        }
    }

    /**
     * An unsupported version is a caller bug, not a zero-length frame: silently
     * returning 0 would omit the frame from the packet length and the staging
     * offset, and the handshake would fail its tag check with nothing to point
     * at. Version 2 is a supported version with no frame, so it stays accepted.
     */
    @Test
    public void testUnknownVersionsAreRejected() {
        for (int version : new int[] {0, 1, 5, 6}) {
            try {
                SSU2Util.pqRequestExtra(version);
                fail("v" + version + " accepted for a session request");
            } catch (IllegalArgumentException e) {
                // expected
            }
            try {
                SSU2Util.pqCreatedExtra(version);
                fail("v" + version + " accepted for a session created");
            } catch (IllegalArgumentException e) {
                // expected
            }
        }
        assertEquals(0, SSU2Util.pqRequestExtra(2));
        assertEquals(0, SSU2Util.pqCreatedExtra(2));
    }

    /**
     * The send path sizes handshake padding from two version-dependent numbers:
     * what the budget is measured against, and which MTU it is measured under.
     * Only version 4 uses the exact wire size and the peer's MTU.
     */
    @Test
    public void testPaddingDecisionPerVersion() {
        assertEquals(PeerState2.MIN_MTU, PacketBuilder2.handshakePaddingMTU(2, 1500));
        assertEquals(PeerState2.MIN_MTU, PacketBuilder2.handshakePaddingMTU(3, 1500));
        assertEquals(1500, PacketBuilder2.handshakePaddingMTU(4, 1500));

        int frame3 = SSU2Util.pqRequestExtra(3);
        int frame4 = SSU2Util.pqRequestExtra(4);
        assertEquals(MIN_REQUEST_BLOCKS,
                     PacketBuilder2.handshakePaddingBase(2, true, MIN_REQUEST_BLOCKS, 0));
        assertEquals(MIN_REQUEST_BLOCKS,
                     PacketBuilder2.handshakePaddingBase(3, true, MIN_REQUEST_BLOCKS, frame3));
        assertEquals(PeerState2.MIN_MLKEM768_IPV6_MTU,
                     PacketBuilder2.handshakePaddingBase(4, true, MIN_REQUEST_BLOCKS, frame4));
    }

    /**
     * Whether a version 4 handshake gets any padding is decided entirely by the
     * MTU the caller supplies: the conservative minimum, and the v4 minimum MTU
     * itself, both leave a request unpadded, while a real MTU leaves room for
     * the full 32 bytes.
     */
    @Test
    public void testVersionFourPaddingFollowsTheCallersMtu() {
        int frame = SSU2Util.pqRequestExtra(4);
        int base = PacketBuilder2.handshakePaddingBase(4, true, MIN_REQUEST_BLOCKS, frame);
        int minBudget = PacketBuilder2.handshakePaddingMTU(4, PeerState2.MIN_MTU);
        int v4Budget = PacketBuilder2.handshakePaddingMTU(4, PeerState2.MIN_MLKEM768_IPV6_MTU);
        int realBudget = PacketBuilder2.handshakePaddingMTU(4, PeerState2.MAX_MTU);
        assertTrue("the minimum MTU should leave a v4 request unpadded",
                   minBudget - base < SSU2Payload.BLOCK_HEADER_SIZE);
        assertTrue("the v4 minimum MTU is exactly the wire size, so it pads nothing",
                   v4Budget - base == 0);
        assertTrue("a real MTU should leave room for the full padding",
                   realBudget - base >= FULL_PADDING_BLOCK);
    }

    /**
     * IPv6 is held to the minimum MTU to survive brokered IPv6 tunnels, but
     * version 4 cannot be: its frame is already larger than that minimum, and
     * the version gate only negotiates it where the real MTU fits. An
     * unavailable MTU on our side must not zero the answer either.
     */
    @Test
    public void testVersionFourIsExemptFromTheConservativeIpv6Clamp() {
        byte[] v4 = new byte[] {127, 0, 0, 1};
        byte[] v6 = new byte[16];
        // v2 and v3 keep the conservative minimum, for IPv6 and an unknown family
        assertEquals(PeerState2.MIN_MTU, OutboundEstablishState2.effectiveMTU(2, v6, 1500, 1500));
        assertEquals(PeerState2.MIN_MTU, OutboundEstablishState2.effectiveMTU(3, v6, 1500, 1500));
        assertEquals(PeerState2.MIN_MTU, OutboundEstablishState2.effectiveMTU(2, null, 1500, 1500));
        // v4 uses the real MTU, capped by ours
        assertEquals(1500, OutboundEstablishState2.effectiveMTU(4, v6, 1500, 1500));
        assertEquals(1400, OutboundEstablishState2.effectiveMTU(4, v6, 1500, 1400));
        assertEquals(1350, OutboundEstablishState2.effectiveMTU(4, v6, 1350, 1500));
        // our MTU being unavailable must not zero the answer
        assertEquals(1500, OutboundEstablishState2.effectiveMTU(4, v6, 1500, 0));
        // IPv4 was never clamped
        assertEquals(1500, OutboundEstablishState2.effectiveMTU(2, v4, 1500, 1500));
    }

    /**
     * The point of the exemption: a version 4 IPv6 request sized from the real
     * MTU gets its padding, which the conservative clamp used to deny it.
     */
    @Test
    public void testExemptionGivesTheVersionFourRequestItsPadding() {
        int frame = SSU2Util.pqRequestExtra(4);
        int base = PacketBuilder2.handshakePaddingBase(4, true, MIN_REQUEST_BLOCKS, frame);
        int clamped = PacketBuilder2.handshakePaddingMTU(4, PeerState2.MIN_MTU);
        int peerMTU = OutboundEstablishState2.effectiveMTU(4, new byte[16],
                                                           PeerState2.MAX_MTU, PeerState2.MAX_MTU);
        int real = PacketBuilder2.handshakePaddingMTU(4, peerMTU);
        assertTrue("the conservative clamp should leave no padding room",
                   clamped - base < SSU2Payload.BLOCK_HEADER_SIZE);
        assertTrue("the real MTU should leave room for the full padding",
                   real - base >= FULL_PADDING_BLOCK);
    }
}
