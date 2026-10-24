package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

import com.southernstorm.noise.protocol.HandshakeState;
import com.southernstorm.noise.protocol.NoiseInit;

import net.i2p.I2PAppContext;
import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.KeyPair;
import net.i2p.router.crypto.pqc.MLKEM;
import net.i2p.router.transport.crypto.X25519KeyFactory;

/**
 * Round-trips a real NTCP2 message 1 through the Noise stack to pin where the
 * plaintext options block has to be staged in the message buffer.
 *
 * <p>For the PQ patterns, noise writes the F token - the ML-KEM encapsulation
 * key plus its MAC - into the message buffer before it reads the payload, so
 * staging the options at KEY_SIZE (the non-PQ position) makes it encrypt the
 * key material instead of the options. The AEAD tag still verifies, because it
 * covers whatever bytes were at that offset, so the damage is silent: the peer
 * decrypts a valid-looking message whose network ID, padLen, m3p2Len and
 * timestamp are all garbage, and then bans it for a bad network id or clock
 * skew. Staging past the F token, as {@link OutboundNTCP2State#msg1PayloadOffset(int)}
 * computes, is what makes the handshake work.
 *
 * @since 0.9.72+
 */
public class NTCP2PQMsg1StagingTest {

    private static final byte[] OPTIONS = new byte[] {
        1, 2, 0, 64, 1, 0, 0, 0, 0x12, 0x34, 0x56, 0x78, 0, 0, 0, 0
    };

    /**
     * The options must arrive at Bob unchanged when staged at the offset our
     * code uses, for every PQ size.
     */
    @Test
    public void testOptionsSurviveAtComputedOffset() throws Exception {
        for (int version : new int[] {2, 3, 4, 5}) {
            byte[] got = roundTrip(version, OutboundNTCP2State.msg1PayloadOffset(version));
            assertArrayEquals("options corrupted at the computed offset for version " + version,
                              OPTIONS, got);
        }
    }

    /**
     * The old staging position is only correct without a PQ frame - that is the
     * regression this pins.
     */
    @Test
    public void testKeySizeOffsetOnlyWorksWithoutPq() throws Exception {
        assertArrayEquals(OPTIONS, roundTrip(2, OutboundNTCP2State.KEY_SIZE));
        for (int version : new int[] {3, 4, 5}) {
            byte[] got = roundTrip(version, OutboundNTCP2State.KEY_SIZE);
            assertFalse("staging at KEY_SIZE must not deliver the options for version " + version,
                        Arrays.equals(OPTIONS, got));
        }
    }

    /**
     * Writes message 1 as Alice with the options staged at stageOff, then reads
     * it back as Bob and returns what he recovered.
     */
    private static byte[] roundTrip(int version, int stageOff) throws Exception {
        KeyFactory xdh = new X25519KeyFactory(I2PAppContext.getGlobalContext());
        KeyFactory hkf = hybridFactory(version);
        KeyPair aliceRI = xdh.getKeys();
        KeyPair bobRI = xdh.getKeys();

        HandshakeState alice = new HandshakeState(pattern(version), HandshakeState.INITIATOR, xdh, hkf);
        alice.getLocalKeyPair().setKeys(aliceRI.getPrivate().getData(), 0, aliceRI.getPublic().getData(), 0);
        alice.getRemotePublicKey().setPublicKey(bobRI.getPublic().getData(), 0);

        HandshakeState bob = new HandshakeState(pattern(version), HandshakeState.RESPONDER, xdh);
        bob.getLocalKeyPair().setKeys(bobRI.getPrivate().getData(), 0, bobRI.getPublic().getData(), 0);
        bob.getRemotePublicKey().setPublicKey(aliceRI.getPublic().getData(), 0);

        int msg1Size = OutboundNTCP2State.msg1Size(version);
        byte[] buf = new byte[msg1Size];
        System.arraycopy(OPTIONS, 0, buf, stageOff, OPTIONS.length);

        alice.start();
        bob.start();
        int written = alice.writeMessage(buf, 0, buf, stageOff, OPTIONS.length);
        assertEquals("message 1 length for version " + version, msg1Size, written);

        byte[] got = new byte[OPTIONS.length];
        try {
            bob.readMessage(buf, 0, written, got, 0);
        } catch (java.security.GeneralSecurityException gse) {
            // staged over by the F token and then authenticated as-is: the tag
            // can still fail if the key material happens not to round-trip
            return new byte[OPTIONS.length];
        }
        return got;
    }

    private static NoiseInit.PatternID pattern(int version) {
        switch (version) {
            case 2:  return NoiseInit.PatternID.XK;
            case 3:  return NoiseInit.PatternID.XKHFS_512;
            case 4:  return NoiseInit.PatternID.XKHFS_768;
            default: return NoiseInit.PatternID.XKHFS_1024;
        }
    }

    private static KeyFactory hybridFactory(int version) {
        switch (version) {
            case 3:  return MLKEM.MLKEM512KeyFactory;
            case 4:  return MLKEM.MLKEM768KeyFactory;
            case 5:  return MLKEM.MLKEM1024KeyFactory;
            default: return null;
        }
    }
}
