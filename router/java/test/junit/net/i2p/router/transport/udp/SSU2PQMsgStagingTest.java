package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import org.junit.Test;

import com.southernstorm.noise.protocol.HandshakeState;
import com.southernstorm.noise.protocol.NoiseInit;

import net.i2p.I2PAppContext;
import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.KeyPair;
import net.i2p.data.Hash;
import net.i2p.router.crypto.pqc.MLKEM;
import net.i2p.router.transport.crypto.X25519KeyFactory;

/**
 * Round-trips a real SSU2 session request through the Noise stack to pin the
 * two things the send path has to get right for the post-quantum versions.
 *
 * <p>First, the payload blocks must be staged past the Noise F token. Noise
 * lays the ML-KEM encapsulation key into the message buffer before it reads the
 * payload, so staging the blocks at the non-PQ position makes it encrypt its
 * own key material instead. The AEAD tag still verifies, because it covers
 * whatever bytes were there, so the peer decrypts a well-formed message whose
 * blocks are garbage and rejects the handshake for the wrong reason.
 *
 * <p>Second, both sides must hash the responder's router hash into the
 * handshake before the header - Alice hashes the peer's, Bob hashes his own.
 * Skip it on the send side and the tags simply do not match.
 *
 * @since 0.9.72+
 */
public class SSU2PQMsgStagingTest {

    /** Payload blocks as written by the send path: date/time (7) plus an empty padding block (3). */
    private static final byte[] BLOCKS = new byte[] {3, 0, 4, 0x12, 0x34, 0x56, 0x78, 1, 0, 0};

    /**
     * The blocks must arrive at Bob unchanged when staged at the offset the
     * send path uses, for every version.
     */
    @Test
    public void testBlocksSurviveAtComputedOffset() throws Exception {
        for (int version : new int[] {2, 3, 4}) {
            int payloadOff = PacketBuilder2.handshakePayloadOffset(SSU2Util.pqRequestExtra(version));
            byte[] got = roundTrip(version, payloadOff, true);
            assertArrayEquals("blocks corrupted at the computed offset for version " + version, BLOCKS, got);
        }
    }

    /**
     * The non-PQ staging position is only correct without a PQ frame - that is
     * the regression this pins.
     */
    @Test
    public void testStagingInsideTheFrameCorruptsTheBlocks() throws Exception {
        int oldOff = SSU2Util.LONG_HEADER_SIZE + SSU2Util.KEY_LEN;
        assertArrayEquals(BLOCKS, roundTrip(2, oldOff, true));
        for (int version : new int[] {3, 4}) {
            byte[] got = roundTrip(version, oldOff, true);
            assertFalse("staging at the non-PQ offset must not deliver the blocks for version " + version,
                        Arrays.equals(BLOCKS, got));
        }
    }

    /**
     * Alice has to hash the peer's router hash, as Bob hashes his own, or the
     * handshake hashes diverge and Bob's AEAD check fails.
     */
    @Test
    public void testRouterHashIsPartOfThePqHandshake() throws Exception {
        int payloadOff = PacketBuilder2.handshakePayloadOffset(SSU2Util.pqRequestExtra(4));
        try {
            byte[] got = roundTrip(4, payloadOff, false);
            fail("expected the handshake to fail without the router hash, Bob read " + Arrays.toString(got));
        } catch (GeneralSecurityException gse) {
            // expected
        }
    }

    /**
     * Writes the session request as Alice with the blocks staged at payloadOff,
     * measured from the start of the long header exactly as the send path
     * measures it, then reads it back as Bob and returns what he recovered.
     */
    private static byte[] roundTrip(int version, int payloadOff, boolean aliceMixHashesRouterHash) throws Exception {
        KeyFactory xdh = new X25519KeyFactory(I2PAppContext.getGlobalContext());
        NoiseInit.PatternID pattern = pattern(version);
        KeyFactory hkf = hybridFactory(version);
        KeyPair aliceRI = xdh.getKeys();
        KeyPair bobRI = xdh.getKeys();
        // any 32 bytes will do, as long as both sides agree on which router hash
        Hash bobHash = new Hash(bobRI.getPublic().getData());

        HandshakeState alice = new HandshakeState(pattern, HandshakeState.INITIATOR, xdh, hkf);
        alice.getLocalKeyPair().setKeys(aliceRI.getPrivate().getData(), 0, aliceRI.getPublic().getData(), 0);
        alice.getRemotePublicKey().setPublicKey(bobRI.getPublic().getData(), 0);

        HandshakeState bob = new HandshakeState(pattern, HandshakeState.RESPONDER, xdh);
        bob.getLocalKeyPair().setKeys(bobRI.getPrivate().getData(), 0, bobRI.getPublic().getData(), 0);
        bob.getRemotePublicKey().setPublicKey(aliceRI.getPublic().getData(), 0);

        int frame = SSU2Util.pqRequestExtra(version);
        int msgSize = SSU2Util.KEY_LEN + frame + BLOCKS.length + SSU2Util.MAC_LEN;
        byte[] msg = new byte[SSU2Util.LONG_HEADER_SIZE + msgSize];
        // the header bytes are arbitrary, but both sides hash the same ones
        for (int i = 0; i < SSU2Util.LONG_HEADER_SIZE; i++) {
            msg[i] = (byte) i;
        }
        System.arraycopy(BLOCKS, 0, msg, payloadOff, BLOCKS.length);

        alice.start();
        bob.start();
        if (aliceMixHashesRouterHash && version != 2) {
            alice.mixHash(bobHash.getData(), 0, 32);
        }
        alice.mixHash(msg, 0, SSU2Util.LONG_HEADER_SIZE);
        int written = alice.writeMessage(msg, SSU2Util.LONG_HEADER_SIZE, msg, payloadOff, BLOCKS.length);
        assertEquals("session request length for version " + version, msgSize, written);

        if (version != 2) {
            bob.mixHash(bobHash.getData(), 0, 32);
        }
        bob.mixHash(msg, 0, SSU2Util.LONG_HEADER_SIZE);
        byte[] got = new byte[BLOCKS.length];
        bob.readMessage(msg, SSU2Util.LONG_HEADER_SIZE, written, got, 0);
        return got;
    }

    private static NoiseInit.PatternID pattern(int version) {
        switch (version) {
            case 2:  return NoiseInit.PatternID.XK_SSU2;
            case 3:  return NoiseInit.PatternID.XKHFS_512_SSU2;
            default: return NoiseInit.PatternID.XKHFS_768_SSU2;
        }
    }

    private static KeyFactory hybridFactory(int version) {
        switch (version) {
            case 3:  return MLKEM.MLKEM512KeyFactory;
            case 4:  return MLKEM.MLKEM768KeyFactory;
            default: return null;
        }
    }
}
