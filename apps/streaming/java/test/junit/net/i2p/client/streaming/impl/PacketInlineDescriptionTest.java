package net.i2p.client.streaming.impl;

import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SigType;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.SigningPublicKey;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the two packet descriptions say. The inline form is what the per-packet
 * DEBUG sites print - one entry for every packet in and out of every stream -
 * and it carried the sender identity's size and the peer's maximum packet size
 * beside every one of them. Those two options frame the stream and are
 * negotiated once at the SYN, so on a live router they were written 105,968 and
 * 114,519 times for 103,762 SYN packets, always the same two values, reprinted
 * on every retransmit of a SYN that never opened.
 *
 * <p>The full multi-line description keeps both, because that is the form a
 * reader asks for when they want the packet's shape.
 *
 * @since 0.9.72+
 */
public class PacketInlineDescriptionTest {

    /**
     * The description cases. Every case calls a packet formatter directly, so
     * the instance holds no state and needs no session or router.
     */
    public PacketInlineDescriptionTest() {
    }

    /**
     * A structurally valid destination: the sender identity is serialized in
     * full, so the key material has to be present and correctly sized for
     * {@link Destination#size()} to report the option's real length.
     *
     * @return a destination with an ElGamal and a DSA key, non-null
     * @throws Exception on key generation failure
     */
    private static Destination identity() throws Exception {
        Destination dest = new Destination();
        dest.setCertificate(Certificate.NULL_CERT);
        KeyGenerator gen = KeyGenerator.getInstance();
        KeyPair pki = gen.generatePKIKeys(EncType.ELGAMAL_2048);
        dest.setPublicKey(pki.getPublic());
        byte[] sigKey = new byte[SigType.DSA_SHA1.getPubkeyLen()];
        SigningPublicKey spk = new SigningPublicKey(SigType.DSA_SHA1, sigKey);
        dest.setSigningPublicKey(spk);
        return dest;
    }

    /**
     * A SYN carrying both framing options, as an inbound SYN arrives.
     *
     * @param max the maximum packet size option to carry
     * @return a packet with the SYN, NO_ACK, FROM and MAX SIZE options set
     * @throws Exception on key generation failure
     */
    private static Packet synWithOptions(int max) throws Exception {
        Packet p = new Packet(null);
        p.setSendStreamId(7);
        p.setSequenceNum(0);
        p.setFlag(Packet.FLAG_SYNCHRONIZE);
        p.setFlag(Packet.FLAG_NO_ACK);
        p.setFlag(Packet.FLAG_FROM_INCLUDED);
        p._optionFrom = identity();
        p.setOptionalMaxSize(max);
        return p;
    }

    /**
     * The inline form names the flags and the payload but not the handshake
     * options, which is what keeps a retransmitted SYN from describing the
     * handshake again on every attempt.
     *
     * @throws Exception if the identity's ElGamal keys cannot be generated
     */
    @Test
    public void inlineFormOmitsTheHandshakeOptions() throws Exception {
        String inline = synWithOptions(1812).formatInline().toString();
        assertTrue("flags still belong: " + inline, inline.contains("SYN"));
        assertTrue("NO-ACK still belongs: " + inline,
                   inline.contains("NO-ACK"));
        assertFalse("sender identity size repeated per packet: " + inline,
                    inline.contains("Sender identity"));
        assertFalse("max packet size repeated per packet: " + inline,
                    inline.contains("Max size"));
    }

    /**
     * The full description keeps both, with the values the packet carries, so a
     * reader who wants the packet's shape can still get it.
     *
     * @throws Exception if the identity's ElGamal keys cannot be generated
     */
    @Test
    public void fullFormKeepsTheHandshakeOptions() throws Exception {
        Packet p = synWithOptions(1812);
        String full = p.formatAsString().toString();
        assertTrue("max size missing from the full form: " + full,
                   full.contains("Max size: 1812 bytes"));
        String ident = "Sender identity: " + p._optionFrom.size() + " bytes";
        assertTrue("sender identity missing from the full form: " + full,
                   full.contains(ident));
    }

    /**
     * The flags themselves stay inline, including the ACK count, because they
     * change per packet - unlike the handshake options. An ACK-only packet is
     * the common case and its flags are the whole message.
     */
    @Test
    public void inlineFormKeepsWhatChangesPerPacket() {
        Packet p = new Packet(null);
        p.setSendStreamId(3);
        p.setSequenceNum(12);
        p.setAckThrough(9);
        String inline = p.formatInline().toString();
        assertTrue("ACK through: " + inline, inline.contains("ACK 9"));
        assertFalse("an ACK of nothing is not a value: " + inline,
                    new Packet(null).formatInline().toString().contains("ACK"));
    }

    /**
     * The stream tag carries the sequence number on the caller's line, so
     * dropping the multi-line header does not drop the sequence with it.
     */
    @Test
    public void streamTagCarriesTheSequenceNumber() {
        Packet p = new Packet(null);
        p.setSequenceNum(4242);
        String tag = p.streamTag();
        assertTrue(tag.contains("Sequence: 4242"));
        assertFalse("no line break on the caller's line: " + tag,
                    tag.contains("\n"));
    }

    /**
     * A requested delay of zero is no delay, so it is not printed - every SYN
     * in a live log carried "Delay: 0ms" for exactly that reason.
     */
    @Test
    public void zeroDelayIsNotPrinted() {
        Packet p = new Packet(null);
        p.setFlag(Packet.FLAG_DELAY_REQUESTED);
        p.setOptionalDelay(0);
        String zero = p.formatInline().toString();
        assertFalse("an absence printed as a value: " + zero,
                    zero.contains("Delay"));
        p.setOptionalDelay(25);
        assertTrue(p.formatInline().toString().contains("Delay: 25ms"));
    }

    /**
     * The NO_ACK flag names what it does - no acknowledgement is requested for
     * this packet - and is not printed as "NACK". A client SYN sets that flag
     * and carries no NACK list, and "SYN NACK" read as the receiver negatively
     * acknowledging the SYN, the reverse of the handshake it describes; the
     * NACK list, the packet's real negative acknowledgements, is printed
     * separately.
     *
     * @throws Exception if the identity's ElGamal keys cannot be generated
     */
    @Test
    public void noAckFlagIsNotPrintedAsANack() throws Exception {
        String inline = synWithOptions(1812).formatInline().toString();
        assertTrue("the flag is named: " + inline, inline.contains("NO-ACK"));
        assertFalse("no bare NACK token to read as the NACK list: " + inline,
                    inline.matches("(?s).*\\sNACK(\\s|$).*"));
    }
}
