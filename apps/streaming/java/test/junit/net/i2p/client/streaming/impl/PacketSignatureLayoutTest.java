package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.data.Signature;
import net.i2p.util.Log;

import org.junit.Test;

/**
 * Tests for how a received packet sizes the signature region when it is rebuilt
 * for verification.
 *
 * <p>Background: a CLOSE or RESET packet may carry a signature with no FROM
 * option, so {@link Packet#readPacket} has to guess the signature type from the
 * option bytes left over. The guess is only a guess: the sender may have padded
 * the signature region, and then the region length matches some other
 * {@link SigType}. {@code verifySignature()} used to size the packet from the
 * guessed type while reserving space for the <em>key's</em> type, logged
 * {@code "Written N size M"} at ERROR and dropped the packet - for every such
 * packet from that peer.
 *
 * <p>What is pinned here:
 *
 * <ul>
 *   <li>the option bytes the signature occupied on the wire are what get
 *       reserved, so the rebuilt packet is byte-identical to the received one
 *       and the size check cannot fire;</li>
 *   <li>the signature is recast to the key's type by keeping the leading
 *       {@code type.getSigLen()} bytes;</li>
 *   <li>a signature shorter than the key's type is refused, not truncated;</li>
 *   <li>a packet description is logged even when INFO is off - the gate that
 *       made every WARN and ERROR about a packet log an empty string.</li>
 * </ul>
 *
 * @since 2.13.0
 */
public class PacketSignatureLayoutTest {

    /** Sig type of the key that signed the packet, as the receiver knows it. */
    private static final SigType KEY_TYPE = SigType.EdDSA_SHA512_Ed25519;
    /** Option bytes the sender actually used for the signature. */
    private static final int SIG_OPTION_BYTES = KEY_TYPE.getSigLen();

    /**
     * Build the wire bytes of a signed packet with no FROM option, padding the
     * signature region to {@code optionBytes}.
     *
     * <p>Padding inside the option block is what a sender that does not need the
     * exact type on the receiving side can do, and the streaming spec warns that
     * leftover option bytes have to be accounted for.
     *
     * @param optionBytes size of the signature region, at least the signature
     * @param sigBytes signature bytes to place at the front of the region
     * @return the packet as it appears on the wire
     */
    private static byte[] wirePacket(int optionBytes, byte[] sigBytes) {
        Packet p = new Packet(null);
        p.setSendStreamId(7);
        p.setReceiveStreamId(9);
        p.setSequenceNum(1);
        p.setAckThrough(1);
        p.setFlag(Packet.FLAG_RESET);
        p.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        p.setOptionalSignature(new Signature(KEY_TYPE, sigBytes));
        byte[] buf = new byte[4096];
        int written = p.writePacket(buf, 0);
        // The header counts the real signature length; stretch the region to
        // optionBytes and say so in the option size field (2 bytes at 20).
        DataHelper.toLong(buf, 20, 2, optionBytes);
        byte[] wire = new byte[22 + optionBytes];
        System.arraycopy(buf, 0, wire, 0, written);
        return wire;
    }

    private static byte[] signatureBytes(int len) {
        byte[] sig = new byte[len];
        for (int i = 0; i < len; i++) {sig[i] = (byte) (i + 1);}
        return sig;
    }

    /**
     * The whole point of the fix: a padded signature region must not make the
     * rebuilt packet a different length than the one verifySignature() counted.
     * Before the fix this was 192 bytes short for the layout below (a 256 byte
     * region read as RSA_SHA256_2048 against a 64 byte EdDSA key).
     */
    @Test
    public void testPaddedSignatureRegionIsFullyReserved() {
        int optionBytes = 256;
        byte[] wire = wirePacket(optionBytes, signatureBytes(SIG_OPTION_BYTES));
        Packet r = new Packet(null);
        r.readPacket(wire, 0, wire.length);

        assertEquals("the whole option region is reserved for the signature",
                     optionBytes, r.signatureSpaceLen(KEY_TYPE));
        byte[] rebuilt = new byte[4096];
        assertEquals("rebuilding the packet yields the length verification hashes",
                     r.writtenSize(KEY_TYPE), r.writePacket(rebuilt, 0, r.signatureSpaceLen(KEY_TYPE)));
    }

    /**
     * The type guessed from the option length is not the key's type here, and
     * that must not stop verification: the signature is the leading bytes of
     * the region.
     */
    @Test
    public void testSignatureRecastToKeyTypeKeepsLeadingBytes() {
        byte[] sig = signatureBytes(SIG_OPTION_BYTES);
        byte[] wire = wirePacket(256, sig);
        Packet r = new Packet(null);
        r.readPacket(wire, 0, wire.length);

        assertNotEquals("the guessed type is not the key's type",
                         KEY_TYPE, r.getOptionalSignature().getType());
        assertTrue("a padded region is recast to the key's type",
                   r.alignSignatureToType(KEY_TYPE));
        assertEquals("recast to the key's type", KEY_TYPE, r.getOptionalSignature().getType());
        assertArrayEquals("only the leading signature bytes are kept",
                          sig, r.getOptionalSignature().getData());
    }

    /**
     * Signature bytes shorter than the key's signature cannot be verified:
     * truncating or padding them would either invent signature bytes or hash a
     * different packet, so the packet has to be refused instead.
     */
    @Test
    public void testSignatureShorterThanKeyTypeIsRefused() {
        byte[] wire = wirePacket(SIG_OPTION_BYTES, signatureBytes(SIG_OPTION_BYTES));
        Packet r = new Packet(null);
        r.readPacket(wire, 0, wire.length);
        // 64 bytes of signature against a 96 byte key type
        SigType longerType = SigType.ECDSA_SHA384_P384;
        assertTrue("precondition: fewer bytes than the key type needs",
                   longerType.getSigLen() > SIG_OPTION_BYTES);
        assertFalse("a signature too short for the key type is refused",
                    r.alignSignatureToType(longerType));
        assertEquals("a refused alignment leaves the signature as read",
                     SIG_OPTION_BYTES, r.getOptionalSignature().length());
    }

    /**
     * A packet with no signature reserves nothing, whatever the key type is.
     */
    @Test
    public void testUnsignedPacketReservesNothing() {
        Packet p = new Packet(null);
        p.setSendStreamId(1);
        p.setReceiveStreamId(2);
        assertEquals(0, p.signatureSpaceLen(KEY_TYPE));
        assertEquals(0, p.signatureSpaceLen(null));
        assertEquals("no signature region in the size either",
                     22, p.writtenSize(KEY_TYPE));
    }

    /**
     * The bug that made the console useless: every WARN and ERROR in this class
     * appends {@code toString()}, and that returned an empty string unless INFO
     * logging was on for this class.
     */
    @Test
    public void testPacketIsDescribedWhenInfoIsOff() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        Packet p = new Packet(null);
        p.setSendStreamId(7);
        p.setReceiveStreamId(9);
        p.setSequenceNum(3);
        p.setAckThrough(2);
        p.setFlag(Packet.FLAG_CLOSE);
        p.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        p.setOptionalSignature(new Signature(KEY_TYPE, signatureBytes(SIG_OPTION_BYTES)));

        Log log = ctx.logManager().getLog(Packet.class);
        int saved = log.getMinimumPriority();
        try {
            log.setMinimumPriority(Log.ERROR);
            assertFalse("precondition: INFO logging is off", log.shouldInfo());
            String desc = p.toString();
            assertTrue("a packet is identified even with INFO off: " + desc,
                       desc.contains("StreamID"));
            assertTrue("the flags are reported: " + desc, desc.contains("CLOSE"));
            assertTrue("the signature type is reported: " + desc,
                       desc.contains(KEY_TYPE.toString()));
        } finally {
            log.setMinimumPriority(saved);
        }
    }
}
