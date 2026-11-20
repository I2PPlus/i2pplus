package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import java.security.GeneralSecurityException;

import net.i2p.crypto.EncType;
import net.i2p.crypto.x25519.X25519DH;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;

import org.junit.Test;

/**
 * Which SSU2 handshake failure is ours and which one the peer caused.
 *
 * <p>Both arrive at the establishment catches as the same unchecked type. The
 * handshake reads answer a corrupt packet, and a peer that puts a low-order X25519
 * point in its ephemeral key gets an IllegalArgumentException out of the
 * Diffie-Hellman - three of the five low-order points have bit 255 clear, so they
 * pass the check the transport runs first and reach the DH. A peer can buy one of
 * those per packet, so they are logged at debug and trended in udp.establishDropped.
 *
 * <p>The other kind is a fault in the state we hold for the peer: an address whose
 * SSU2 static key option is gone or unusable, or a version with no handshake
 * pattern. Caught by the same arm, it was logged at debug too, which left the
 * operator no sign that the address they were trying to reach could never be
 * reached - and the Retry path is exactly where an address loses that option
 * between the Session Request and the Retry.
 *
 * @since 0.9.72+
 */
public class EstablishFaultDecisionTest {

    /** The key type the SSU2 handshake runs its Diffie-Hellman with. */
    private static final EncType TYPE = EncType.ECIES_X25519;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public EstablishFaultDecisionTest() {}

    /**
     * A fault in the state we hold for the peer is ours, and belongs in the log.
     */
    @Test
    public void ourOwnStateFaultIsOurs() {
        EstablishFaultException missingKey = new EstablishFaultException("no SSU2 S");
        EstablishFaultException badVersion = new EstablishFaultException("Bad version 9");
        assertTrue(EstablishmentManager.ownEstablishFault(missingKey));
        assertTrue(EstablishmentManager.ownEstablishFault(badVersion));
    }

    /**
     * The Diffie-Hellman's rejection of a low-order point is not ours. It has to
     * stay the plain IllegalArgumentException this decision runs on, or a peer's
     * per-packet junk starts costing a warning again.
     */
    @Test
    public void theDHLowOrderRejectionIsNotOurs() {
        byte[] privData = new byte[32];
        for (int i = 0; i < 32; i++)
            privData[i] = (byte) (i * 7 + 3);
        privData[0] &= 0xf8;
        privData[31] &= 0x7f;
        privData[31] |= 0x40;
        byte[] zeroPoint = new byte[32];
        try {
            X25519DH.dh(new PrivateKey(TYPE, privData), new PublicKey(TYPE, zeroPoint));
            fail("a low-order public key was accepted by the Diffie-Hellman");
        } catch (IllegalArgumentException iae) {
            assertEquals("the Diffie-Hellman rejection is no longer the plain type",
                         IllegalArgumentException.class, iae.getClass());
            assertFalse(EstablishmentManager.ownEstablishFault(iae));
        }
    }

    /**
     * A corrupt packet is remote junk, whichever layer noticed it.
     */
    @Test
    public void aCorruptPacketIsNotOurs() {
        assertFalse(EstablishmentManager.ownEstablishFault(
                        new GeneralSecurityException("BAD token 0 in retry")));
    }

    /**
     * Our fault stays inside IllegalArgumentException, so every caller that already
     * catches that keeps taking it: the outbound path marks the peer unreachable and
     * fails the message on that catch, and narrowing the type would let a fault
     * escape it instead.
     */
    @Test
    public void ourFaultIsStillAnIllegalArgumentException() {
        IllegalArgumentException fault = new EstablishFaultException("BAD SSU2 S length");
        assertTrue(fault instanceof IllegalArgumentException);
    }
}
