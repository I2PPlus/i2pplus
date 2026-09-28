package net.i2p.router.tunnel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.security.MessageDigest;
import java.util.Random;

import net.i2p.crypto.SHA256Generator;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.RouterContext;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for the two byte ranges {@link FragmentHandler#hashRanges} hands to
 * the digest, for the payload-then-IV order they must be hashed in, and for a
 * full preprocess/verify round trip.
 *
 * The order is not visible in the source once the payload and the IV are fed
 * to the digest separately instead of being copied into one contiguous buffer,
 * so it needs a test of its own: the two orderings do not collide, which is
 * what lets a reversal be caught here.
 *
 * @since 0.9.71+
 */
public class VerifyPreprocessedDigestTest {

    private static final int IV_LEN = HopProcessor.IV_LENGTH;
    private static final int MAX_SIZE = TrivialPreprocessor.PREPROCESSED_SIZE;
    /** IV, the 4 byte hash segment, then the terminating 0x00: no padding. */
    private static final int NO_PADDING = IV_LEN + 4 + 1;

    private static RouterContext _context;

    /** The context argument is unused, so a bare instance is all that is needed. */
    private final SHA256Generator _sha = new SHA256Generator(null);

    @BeforeClass
    public static void globalSetUp() {
        _context = new RouterContext(null);
    }

    @Test
    public void testNoPadding() {
        int validLength = IV_LEN + 100;
        int[] ranges = FragmentHandler.hashRanges(0, validLength, NO_PADDING);
        assertArrayEquals(new int[] { NO_PADDING, 100, 0, IV_LEN }, ranges);
    }

    @Test
    public void testOneBytePadding() {
        int paddingEnd = NO_PADDING + 1;
        int validLength = IV_LEN + 100;
        int[] ranges = FragmentHandler.hashRanges(0, validLength, paddingEnd);
        assertArrayEquals(new int[] { NO_PADDING + 1, 100, 0, IV_LEN }, ranges);
    }

    /**
     * A padding-only message is allowed by the spec, so a zero length payload
     * has to come out as zero rather than as a negative length.
     */
    @Test
    public void testFullPadding() {
        int[] ranges = FragmentHandler.hashRanges(0, IV_LEN, MAX_SIZE);
        assertArrayEquals(new int[] { MAX_SIZE, 0, 0, IV_LEN }, ranges);
    }

    /**
     * The IV range stays at the head of the buffer while the payload range
     * follows the message's own offset.
     */
    @Test
    public void testNonZeroOffset() {
        int offset = 7;
        int paddingEnd = offset + NO_PADDING + 2;
        int payloadLength = 27;
        int validLength = payloadLength + IV_LEN;
        int[] ranges = FragmentHandler.hashRanges(offset, validLength, paddingEnd);
        assertArrayEquals(new int[] { offset + paddingEnd, payloadLength, 0, IV_LEN }, ranges);
        assertEquals(0, ranges[2]);
        assertEquals(IV_LEN, ranges[3]);
    }

    /**
     * A full size message with the least padding the spec allows, i.e. the
     * largest payload that is ever hashed.
     */
    @Test
    public void testMaximumLength() {
        int payloadLength = MAX_SIZE - NO_PADDING;
        int[] ranges = FragmentHandler.hashRanges(0, payloadLength + IV_LEN, NO_PADDING);
        assertArrayEquals(new int[] { NO_PADDING, payloadLength, 0, IV_LEN }, ranges);
        assertEquals(MAX_SIZE, ranges[0] + ranges[1]);
    }

    /**
     * The helper must agree with the valid length arithmetic
     * verifyPreprocessed() derives it from, so that the two range lengths
     * always add back up to the number of bytes the sender hashed.
     */
    @Test
    public void testRangesMatchValidLengthArithmetic() {
        for (int paddingEnd = NO_PADDING; paddingEnd < NO_PADDING + 8; paddingEnd++) {
            for (int payloadLength = 0; payloadLength < 8; payloadLength++) {
                int offset = 0;
                int length = offset + paddingEnd + payloadLength;
                int validLength = length - offset - paddingEnd + IV_LEN;
                int[] ranges = FragmentHandler.hashRanges(offset, validLength, paddingEnd);
                assertArrayEquals(new int[] { paddingEnd, payloadLength, 0, IV_LEN }, ranges);
                assertEquals(validLength, ranges[1] + ranges[3]);
            }
        }
    }

    @Test
    public void testTwoRangeDigestEqualsConcatenated() throws Exception {
        assertRangesHashAsPayloadThenIV(0, NO_PADDING, 100);
        assertRangesHashAsPayloadThenIV(0, NO_PADDING + 1, 100);
        assertRangesHashAsPayloadThenIV(0, NO_PADDING + 15, 100);
        assertRangesHashAsPayloadThenIV(0, MAX_SIZE, 0);
        assertRangesHashAsPayloadThenIV(9, NO_PADDING + 3, 63);
    }

    /**
     * Hashing the IV before the payload gives a different digest, which is what
     * makes a reversal of the two updates detectable rather than invisible.
     */
    @Test
    public void testIvFirstDiffersFromPayloadFirst() throws Exception {
        byte[] buf = syntheticMessage(0, NO_PADDING, 100);
        int[] payloadFirst = FragmentHandler.hashRanges(0, IV_LEN + 100, NO_PADDING);
        int[] ivFirst = new int[] { payloadFirst[2], payloadFirst[3], payloadFirst[0], payloadFirst[1] };
        assertFalse("IV-first must not yield the payload-first digest",
                    DataHelper.eq(twoRangeDigest(buf, payloadFirst), twoRangeDigest(buf, ivFirst)));
    }

    /**
     * A message preprocessed by the sending side's own code must pass
     * verification, so the receiver's digest matches the sender's over the
     * same bytes in the same order.
     */
    @Test
    public void testVerifyPreprocessedAcceptsTrivialPreprocessorOutput() {
        byte[] preprocessed = trivialPreprocess(16);
        I2NPMessage[] received = new I2NPMessage[1];
        FragmentHandler handler = new FragmentHandler(_context,
                (msg, toRouter, toTunnel) -> received[0] = msg);
        assertTrue("a validly preprocessed message must verify",
                   handler.receiveTunnelMessage(preprocessed, 0, preprocessed.length));
        assertNotNull("the reassembled message must be delivered", received[0]);
    }

    /**
     * Tampering with a byte of either hashed range must fail verification, which
     * only holds if both the payload and the IV really are inside it.  The
     * padding is deliberately left alone: it sits outside the digest.
     */
    @Test
    public void testVerifyPreprocessedRejectsTamperedRanges() {
        byte[] tamperedPayload = trivialPreprocess(16);
        tamperedPayload[3] ^= 0x40;
        assertFalse("a tampered payload byte must not verify",
                    new FragmentHandler(_context, null).receiveTunnelMessage(
                            tamperedPayload, 0, tamperedPayload.length));

        byte[] tamperedIV = trivialPreprocess(16);
        tamperedIV[0] ^= 0x40;
        assertFalse("a tampered IV byte must not verify",
                    new FragmentHandler(_context, null).receiveTunnelMessage(
                            tamperedIV, 0, tamperedIV.length));
    }

    /**
     * Hash the same bytes two ways and require agreement: once as a single
     * payload||IV buffer through calculateHash(), and once as the pair of
     * MessageDigest.update() calls verifyPreprocessed() makes.
     *
     * @param offset where the message starts inside the buffer
     * @param paddingEnd one past the last padding byte
     * @param payloadLength how many payload bytes follow the padding
     */
    private void assertRangesHashAsPayloadThenIV(int offset, int paddingEnd, int payloadLength) throws Exception {
        byte[] buf = syntheticMessage(offset, paddingEnd, payloadLength);
        int length = offset + paddingEnd + payloadLength;
        int validLength = length - offset - paddingEnd + IV_LEN;
        int[] ranges = FragmentHandler.hashRanges(offset, validLength, paddingEnd);

        byte[] concatenated = new byte[ranges[1] + ranges[3]];
        System.arraycopy(buf, ranges[0], concatenated, 0, ranges[1]);
        System.arraycopy(buf, ranges[2], concatenated, ranges[1], ranges[3]);
        byte[] expected = new byte[Hash.HASH_LENGTH];
        _sha.calculateHash(concatenated, 0, concatenated.length, expected, 0);

        assertArrayEquals("two ranges must hash as payload||IV", expected, twoRangeDigest(buf, ranges));
    }

    /**
     * The update sequence verifyPreprocessed() uses, including the acquire and
     * release of the pooled digest.
     *
     * @param buf the buffer holding the message
     * @param ranges {offset, length} of the first range, then of the second
     * @return the 32 byte digest
     */
    private byte[] twoRangeDigest(byte[] buf, int[] ranges) throws Exception {
        byte[] v = new byte[Hash.HASH_LENGTH];
        MessageDigest digest = _sha.acquire();
        try {
            digest.update(buf, ranges[0], ranges[1]);
            digest.update(buf, ranges[2], ranges[3]);
            digest.digest(v, 0, Hash.HASH_LENGTH);
        } finally {
            _sha.release(digest);
        }
        return v;
    }

    /**
     * Build { IV + H[0:3] + padding + payload } the way verifyPreprocessed sees
     * one, without the hash segment being meaningful.
     *
     * @param offset where the message starts inside the returned array
     * @param paddingEnd one past the last padding byte
     * @param payloadLength how many payload bytes follow the padding
     * @return a buffer with the message starting at offset
     */
    private static byte[] syntheticMessage(int offset, int paddingEnd, int payloadLength) {
        byte[] buf = new byte[offset + paddingEnd + payloadLength];
        new Random(42).nextBytes(buf);
        for (int i = offset + IV_LEN + 4; i < offset + paddingEnd - 1; i++)
            buf[i] = 0x01;
        buf[offset + paddingEnd - 1] = 0x00;
        return buf;
    }

    /**
     * Run a real I2NP message through the sender side's preprocessing, giving
     * the buffer verifyPreprocessed() will later be handed.
     *
     * @param dataLength payload length of the message
     * @return a full size preprocessed buffer
     */
    private static byte[] trivialPreprocess(int dataLength) {
        DataMessage msg = new DataMessage(_context);
        msg.setData(new byte[dataLength]);
        msg.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
        msg.setMessageExpiration(_context.clock().now() + 60 * 1000);
        byte[] body = msg.toByteArray();

        byte[] preprocessed = new byte[MAX_SIZE];
        // control byte: local delivery, not fragmented, no extended options
        preprocessed[0] = 0x00;
        DataHelper.toLong(preprocessed, 1, 2, body.length);
        System.arraycopy(body, 0, preprocessed, 3, body.length);
        new TrivialPreprocessor(_context).preprocess(preprocessed, 3 + body.length);
        return preprocessed;
    }
}
