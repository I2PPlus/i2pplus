package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SigType;
import net.i2p.data.ByteArray;
import net.i2p.data.Certificate;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.SigningPublicKey;

import org.junit.Test;

/**
 * Tests the ingress hardening of {@link Packet#readPacket(byte[], int, int)}
 * and the NACK count cap in {@link Packet#writePacket(byte[], int)}.
 *
 * <p>The NACK count byte wraps above 255 (300 becomes 44), corrupting the
 * rest of the packet for the receiver, so the write path truncates the list
 * to {@link Packet#MAX_NACKS}. Option fields demanded by the flags must fit
 * in the declared option region — a hostile packet with e.g. the signature
 * flag and a zero option size used to read payload bytes as option bytes or
 * run off the packet end. The FROM option stream must also honour the read
 * offset.
 *
 * @since 0.9.72+
 */
public class PacketIngressHardeningTest {

    /** A 300-entry NACK list is truncated to the count byte's range. */
    @Test
    public void testNackCountCappedOnWrite() {
        Packet p = new Packet(null);
        p.setSendStreamId(1);
        p.setReceiveStreamId(2);
        p.setSequenceNum(5);
        p.setAckThrough(10);
        long[] nacks = new long[300];
        for (int i = 0; i < nacks.length; i++)
            nacks[i] = i + 1;
        p.setNacks(nacks);
        byte[] buf = new byte[Packet.MAX_PAYLOAD_SIZE + 1024];
        int written = p.writePacket(buf, 0);
        // count byte at offset 16: 4+4+4+4 header fields before it
        assertEquals(Packet.MAX_NACKS, buf[16] & 0xff);
        Packet p2 = new Packet(null);
        p2.readPacket(buf, 0, written);
        assertNotNull(p2.getNacks());
        assertEquals(Packet.MAX_NACKS, p2.getNacks().length);
        assertEquals(1, p2.getNacks()[0]);
        assertEquals(Packet.MAX_NACKS, p2.getNacks()[Packet.MAX_NACKS - 1]);
        // the payload after the (truncated) NACK list still parses
        assertEquals(5, p2.getSequenceNum());
        assertEquals(10, p2.getAckThrough());
    }

    /** A delay flag with an option region too small is rejected. */
    @Test
    public void testDelayOptionTooSmall() {
        byte[] buf = new byte[64];
        int cur = 0;
        DataHelper.toLong(buf, cur, 4, 1); cur += 4;   // sendStreamId
        DataHelper.toLong(buf, cur, 4, 2); cur += 4;   // receiveStreamId
        DataHelper.toLong(buf, cur, 4, 0); cur += 4;   // sequence
        DataHelper.toLong(buf, cur, 4, 0); cur += 4;   // ackThrough
        buf[cur++] = 0;                                 // no nacks
        buf[cur++] = 0;                                 // resend delay
        DataHelper.toLong(buf, cur, 2, Packet.FLAG_DELAY_REQUESTED); cur += 2;
        DataHelper.toLong(buf, cur, 2, 0);              // option size 0, flag wants 2
        Packet p = new Packet(null);
        try {
            p.readPacket(buf, 0, cur + 2);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // expected
        }
    }

    /** A signature flag with no room for the signature is rejected. */
    @Test
    public void testSignatureOptionTooSmall() {
        byte[] buf = new byte[64];
        int cur = 0;
        DataHelper.toLong(buf, cur, 4, 1); cur += 4;
        DataHelper.toLong(buf, cur, 4, 2); cur += 4;
        DataHelper.toLong(buf, cur, 4, 0); cur += 4;
        DataHelper.toLong(buf, cur, 4, 0); cur += 4;
        buf[cur++] = 0;
        buf[cur++] = 0;
        DataHelper.toLong(buf, cur, 2, Packet.FLAG_SIGNATURE_INCLUDED); cur += 2;
        DataHelper.toLong(buf, cur, 2, 0);              // option size 0
        Packet p = new Packet(null);
        try {
            p.readPacket(buf, 0, cur + 2);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // expected
        }
    }

    /**
     * The FROM option stream is bounded by the packet end measured from the
     * read offset, so a packet read at a non-zero offset parses its FROM
     * (and everything else) identically to offset 0.
     *
     * @throws Exception on key generation or destination parse failure
     */
    @Test
    public void testFromOptionAtNonZeroOffset() throws Exception {
        Destination dest = new Destination();
        // A structurally valid destination: the FROM option is serialized in
        // full, so the key material must be present and correctly sized.
        dest.setCertificate(Certificate.NULL_CERT);
        dest.setPublicKey(KeyGenerator.getInstance().generatePKIKeys(EncType.ELGAMAL_2048).getPublic());
        byte[] sigKey = new byte[SigType.DSA_SHA1.getPubkeyLen()];
        dest.setSigningPublicKey(new SigningPublicKey(SigType.DSA_SHA1, sigKey));
        Packet p = new Packet(null);
        p.setSendStreamId(1);
        p.setReceiveStreamId(2);
        p.setSequenceNum(7);
        p.setAckThrough(0);
        p._optionFrom = dest;
        p.setFlag(Packet.FLAG_FROM_INCLUDED);
        byte[] payload = new byte[16];
        p.setPayload(new ByteArray(payload));
        int size = p.writtenSize(null);
        byte[] buf = new byte[size + 10];
        int written = p.writePacket(buf, 10);
        assertEquals(size, written);
        Packet p2 = new Packet(null);
        p2.readPacket(buf, 10, written);
        assertEquals(7, p2.getSequenceNum());
        assertNotNull(p2.getOptionalFrom());
        assertArrayEquals(dest.toByteArray(), p2.getOptionalFrom().toByteArray());
    }
}
