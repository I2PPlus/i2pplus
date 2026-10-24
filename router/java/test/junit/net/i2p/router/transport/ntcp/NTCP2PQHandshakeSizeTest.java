package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins NTCP2 handshake message sizes, padding bounds and buffer sizes to
 * i2pd's implementation (libi2pd/NTCP2.cpp CreateSessionRequestMessage and
 * CreateSessionCreatedMessage, libi2pd/NTCP2.h, libi2pd/PostQuantum.h), so a
 * cross-implementation handshake cannot silently regress, and so our padding
 * distribution stays the same as i2pd's on the wire.
 *
 * <p>i2pd SessionRequest layout (offset math): X (32) + ML-KEM encapKey
 * (keyLen + 16 AEAD tag) + options (16 + 16 AEAD tag). For MLKEM-768 that is
 * 32 + 1200 + 32 = 1264 with exactly TWO AEAD tags. A third MAC (the
 * {@code + MAC_SIZE} bug) makes readMessage consume padding as part of the
 * options block and the tag check fails, so msg1Size(768) must stay 1264.
 *
 * <p>i2pd padding: maxPaddingLength is offset + 32 for a PQ frame, else 944 - 64
 * (long) or 287 - 64 (short), clamped so offset + 32 + padding stays within
 * maxMsgSize, which for a PQ frame is 944 + 1568 + 16 = 2528 - that clamp is
 * what pulls MLKEM-1024 back down to 880. paddingLength is then rng() %
 * maxPaddingLength, so the bound below is exclusive.
 *
 * <p>i2pd only uses long padding once the peer advertises PQ support (its pq
 * address option, parsed into addr->v), which is exactly when we negotiate
 * versions 3-5, so a peer predating the 2026 padding increase - Java _X = 256,
 * i2pd m_Buffer = 287 - never sees more than 222 bytes of msg 1 padding from
 * us. We still ACCEPT the long bound on a version 2 session, because i2pd's
 * Alice drops to non-PQ one time in four (ClientLogin) while keeping long
 * padding.
 *
 * @since 0.9.72+
 */
public class NTCP2PQHandshakeSizeTest {

    // i2pd ground-truth constants (PostQuantum.h / NTCP2.h)
    private static final int MLKEM512_KEY = 800;
    private static final int MLKEM768_KEY = 1184;
    private static final int MLKEM1024_KEY = 1568;
    private static final int MLKEM512_CT = 768;
    private static final int MLKEM768_CT = 1088;
    private static final int MLKEM1024_CT = 1568;
    private static final int MAC = 16;
    private static final int[] VERSIONS = {2, 3, 4, 5};
    // i2pd's own receive caps: m_MaxMsgSize, 944 without PQ compiled in
    private static final int I2PD_NONPQ_MAX_MSG = 944;
    private static final int I2PD_PQ_MAX_MSG = 2 * MLKEM1024_KEY + 160;   // 3296
    // what a peer predating the 2026 padding increase can hold: i2pd's 287-byte
    // SessionRequest buffer, and Java's 256-byte _X
    private static final int OLD_PEER_MSG_MAX = 287;

    @Test
    public void testPqMsg1ExtraMatchesI2pdFrame() {
        assertEquals(0, OutboundNTCP2State.pqMsg1Extra(2));
        assertEquals(MLKEM512_KEY + MAC, OutboundNTCP2State.pqMsg1Extra(3));
        assertEquals(MLKEM768_KEY + MAC, OutboundNTCP2State.pqMsg1Extra(4));
        assertEquals(MLKEM1024_KEY + MAC, OutboundNTCP2State.pqMsg1Extra(5));
    }

    @Test
    public void testMsg1SizeMatchesI2pdSessionRequest() {
        // X(32) + options(16) + options MAC(16) = 64 base, plus the PQ frame.
        assertEquals(64, OutboundNTCP2State.msg1Size(2));
        assertEquals(32 + (MLKEM512_KEY + MAC) + 32, OutboundNTCP2State.msg1Size(3));   // 880
        assertEquals(32 + (MLKEM768_KEY + MAC) + 32, OutboundNTCP2State.msg1Size(4));   // 1264
        assertEquals(32 + (MLKEM1024_KEY + MAC) + 32, OutboundNTCP2State.msg1Size(5));  // 1648
    }

    @Test
    public void testMsg1SizeHasNoThirdMac() {
        // Regression guard: the +MAC_SIZE bug made this 1280. Two tags only.
        assertEquals(1264, OutboundNTCP2State.msg1Size(4));
        assertNotEquals(1280, OutboundNTCP2State.msg1Size(4));
    }

    @Test
    public void testMsg2SizeMatchesI2pdSessionCreated() {
        // Y(32) + options(16) + options MAC(16), plus the ML-KEM ciphertext frame.
        assertEquals(64, OutboundNTCP2State.msg2Size(2));
        assertEquals(32 + (MLKEM512_CT + MAC) + 32, OutboundNTCP2State.msg2Size(3));    // 848
        assertEquals(32 + (MLKEM768_CT + MAC) + 32, OutboundNTCP2State.msg2Size(4));    // 1168
        assertEquals(32 + (MLKEM1024_CT + MAC) + 32, OutboundNTCP2State.msg2Size(5));   // 1648
    }

    @Test
    public void testPadding1MaxMatchesI2pdSendRange() {
        // i2pd: 287 - 64 short, offset + 32 for a PQ frame, clamped to 2528 total
        assertEquals(223, OutboundNTCP2State.padding1Max(2));   // peer may predate long padding
        assertEquals(880, OutboundNTCP2State.padding1Max(3));   // MLKEM-512
        assertEquals(1264, OutboundNTCP2State.padding1Max(4));  // MLKEM-768
        assertEquals(880, OutboundNTCP2State.padding1Max(5));   // MLKEM-1024, clamped
    }

    @Test
    public void testPadding2MaxMatchesI2pdSendRange() {
        assertEquals(880, OutboundNTCP2State.padding2Max(2));   // non-PQ long padding
        assertEquals(848, OutboundNTCP2State.padding2Max(3));   // MLKEM-512 ciphertext frame
        assertEquals(1168, OutboundNTCP2State.padding2Max(4));  // MLKEM-768
        assertEquals(880, OutboundNTCP2State.padding2Max(5));   // MLKEM-1024, clamped
    }

    /**
     * We must never send more padding than i2pd's own send bound, or we stand
     * out on the wire, and never more than the peer's handshake buffer holds.
     */
    @Test
    public void testSendTotalsMatchI2pdAndFitPeerCaps() {
        for (int v : VERSIONS) {
            int total = OutboundNTCP2State.msg1Size(v) + OutboundNTCP2State.padding1Max(v) - 1;
            int cap = v == 2 ? I2PD_NONPQ_MAX_MSG : I2PD_PQ_MAX_MSG;
            assertTrue("msg 1 total " + total + " over cap for v" + v, total <= cap);
            int total2 = OutboundNTCP2State.msg2Size(v) + OutboundNTCP2State.padding2Max(v) - 1;
            assertTrue("msg 2 total " + total2 + " over cap for v" + v, total2 <= cap);
        }
    }

    /** A peer that predates the 2026 padding increase must take what we send. */
    @Test
    public void testNonPqPaddingFitsOldPeers() {
        assertTrue(OutboundNTCP2State.msg1Size(2) + OutboundNTCP2State.padding1Max(2) - 1
                   <= OLD_PEER_MSG_MAX);
        assertTrue(OutboundNTCP2State.msg2Size(2) + OutboundNTCP2State.PADDING_SHORT_MAX - 1
                   <= OLD_PEER_MSG_MAX);
    }

    /**
     * Whatever we accept, our buffers hold it, and whatever we send, our peer
     * accepts - so a session we start can never be rejected by us on the way in.
     */
    @Test
    public void testRecvBoundsCoverSendBoundsAndBuffers() {
        for (int v : VERSIONS) {
            assertTrue("recv below send for v" + v,
                       OutboundNTCP2State.padding1RecvMax(v) >= OutboundNTCP2State.padding1Max(v));
            assertTrue("msg 1 buffer too small for v" + v,
                       OutboundNTCP2State.msg1BufSize(v) >= OutboundNTCP2State.msg1Size(v));
            assertTrue("msg 1 buffer too small for padding on v" + v,
                       OutboundNTCP2State.msg1BufSize(v) >= OutboundNTCP2State.padding1RecvMax(v));
            int tmp = OutboundNTCP2State.tmpSize(v);
            assertTrue("tmp too small for v" + v,
                       tmp >= OutboundNTCP2State.msg1Size(v) + OutboundNTCP2State.padding1Max(v));
            assertTrue("tmp too small for msg 2 on v" + v, tmp >= OutboundNTCP2State.msg2Size(v));
            assertTrue("tmp too small for msg 2 padding on v" + v,
                       tmp >= OutboundNTCP2State.padding2Max(v));
        }
    }

    /**
     * The inbound buffer is reused for the padding from offset 0, so it must be
     * the larger of the two, never the sum - the sum doubles the memory per
     * inbound handshake attempt.
     */
    @Test
    public void testMsg1BufSizeIsNotTheSum() {
        assertEquals(1264, OutboundNTCP2State.msg1BufSize(4));
        assertNotEquals(1264 + 1264, OutboundNTCP2State.msg1BufSize(4));
        // PQ disabled is a version 2 session: the padding headroom, not msg 1
        assertEquals(880, OutboundNTCP2State.msg1BufSize(2));
    }

    @Test
    public void testShortPaddingIsI2pdShortScale() {
        assertEquals(287, OutboundNTCP2State.MSG_SHORT_MAX);
        assertEquals(223, OutboundNTCP2State.PADDING_SHORT_MAX);
    }

    /**
     * The plaintext options have to be staged clear of the PQ frame noise
     * writes ahead of them, or writeMessage() encrypts the encapsulation key
     * instead. See NTCP2PQMsg1StagingTest for the round trip.
     */
    @Test
    public void testMsg1PayloadOffsetClearsThePqFrame() {
        for (int v : VERSIONS) {
            int clear = OutboundNTCP2State.KEY_SIZE + OutboundNTCP2State.pqMsg1Extra(v);
            assertTrue("options staged inside the PQ frame for v" + v,
                       OutboundNTCP2State.msg1PayloadOffset(v) >= clear);
            assertTrue("options run past the message for v" + v,
                       OutboundNTCP2State.msg1PayloadOffset(v) + OutboundNTCP2State.OPTIONS1_SIZE
                       + OutboundNTCP2State.MAC_SIZE == OutboundNTCP2State.msg1Size(v));
        }
        // unchanged position for the non-PQ case
        assertEquals(32, OutboundNTCP2State.msg1PayloadOffset(2));
        assertEquals(1232, OutboundNTCP2State.msg1PayloadOffset(4));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBadVersionRejectedOnSendPadding() {
        OutboundNTCP2State.padding1Max(99);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBadVersionRejectedOnRecvPadding() {
        OutboundNTCP2State.padding1RecvMax(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBadVersionRejectedOnMsgSize() {
        OutboundNTCP2State.msg1Size(1);
    }

    /**
     * Bob's message 2 padding follows Alice's: i2pd switches to long padding
     * only once her message 1 came in longer than its 287-byte short handshake,
     * so the threshold is measured on the whole message, not on the padding
     * field alone.
     */
    @Test
    public void testMsg2PaddingFollowsAlicesPadding() {
        // a short message 1 gets a short answer back
        assertEquals(OutboundNTCP2State.PADDING_SHORT_MAX, OutboundNTCP2State.msg2PaddingMax(2, 0));
        // a peer that kept to i2pd's old 287-byte handshake is still "short"
        int shortPadlen1 = OLD_PEER_MSG_MAX - OutboundNTCP2State.msg1Size(2);
        assertEquals(OutboundNTCP2State.PADDING_SHORT_MAX,
                     OutboundNTCP2State.msg2PaddingMax(2, shortPadlen1));
        // one byte past that boundary and the answer goes long
        assertEquals(OutboundNTCP2State.padding2Max(2),
                     OutboundNTCP2State.msg2PaddingMax(2, shortPadlen1 + 1));
    }

    /**
     * A PQ message 1 is already past the short-handshake threshold by itself, so
     * a negotiated PQ version always answers with long padding.
     */
    @Test
    public void testPqVersionsAlwaysAnswerWithLongPadding() {
        for (int v : new int[] {3, 4, 5}) {
            assertEquals("v" + v, OutboundNTCP2State.padding2Max(v),
                         OutboundNTCP2State.msg2PaddingMax(v, 0));
        }
    }

    /**
     * Whatever Alice sent, Bob's cap stays inside the version's own message 2
     * bound, so the padding he picks always fits the buffer sized for it.
     */
    @Test
    public void testMsg2PaddingStaysInsideTheVersionBound() {
        for (int v : VERSIONS) {
            for (int padlen1 : new int[] {0, 1, 222, 223, 880, 1264}) {
                int max = OutboundNTCP2State.msg2PaddingMax(v, padlen1);
                assertTrue("v" + v + " padlen1 " + padlen1 + " exceeds the version bound",
                           max <= OutboundNTCP2State.padding2Max(v));
                assertTrue("v" + v + " padlen1 " + padlen1 + " leaves no room to pad", max > 0);
            }
        }
    }
}
