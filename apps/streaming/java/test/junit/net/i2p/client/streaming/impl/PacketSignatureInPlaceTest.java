package net.i2p.client.streaming.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import net.i2p.I2PAppContext;

import net.i2p.crypto.SigType;
import net.i2p.data.ByteArray;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;

import org.junit.Test;

/**
 * Tests that a received packet is signature-checked against the bytes it arrived
 * in, without rebuilding a second copy of it.
 *
 * <p>Background: {@code verifyWithKey()} has to hash the packet with the
 * signature space zeroed, so it used to write the whole packet - header, options
 * and up to {@link Packet#MAX_PAYLOAD_SIZE} bytes of payload - into a scratch
 * buffer for every verified packet. That is a second full copy of bytes the
 * receive path had already copied once, and a fresh allocation whenever the
 * packet is bigger than the 4 KiB verify pool. A received packet already holds a
 * reference to the buffer it was read from (its payload wraps it), so the bytes
 * are in hand: zero the signature region, hash where they lie, put the region
 * back.
 *
 * <p>What is pinned here, with real EdDSA keys rather than a stubbed engine:
 *
 * <ul>
 * <li>a signed packet read off the wire verifies at a non-zero buffer offset with
 *     no scratch buffer passed at all;</li>
 * <li>the bytes hashed in place are byte-for-byte the ones the rebuild path
 *     produces, so the two paths cannot disagree about a signature;</li>
 * <li>the signature region is put back, so the packet's own bytes are unchanged
 *     for whoever reads the payload next, and a second verification is still
 *     served;</li>
 * <li>tampering with the payload or the signature, and a truncated packet, are
 *     still refused;</li>
 * <li>the gate that picks a path refuses a packet that was never read from a
 *     buffer, an unsigned packet, and a size that does not match what we would
 *     write.</li>
 * </ul>
 *
 * @since 0.9.72+
 */
public class PacketSignatureInPlaceTest {

    /** Sig type used to sign and verify, available without jbigi. */
    private static final SigType KEY_TYPE = SigType.EdDSA_SHA512_Ed25519;

    /** Bytes between the start of the packet and its options. */
    private static final int HEADER_SIZE = 22;

    /**
     * Offset the packet is placed at inside the buffer it is read from, so a
     * verification that assumed offset 0 would fail here instead of at 3am.
     */
    private static final int BUFFER_OFFSET = 16;

    /**
     * Deterministic payload of {@code len} bytes, so the expected bytes of a
     * packet do not depend on the seed.
     *
     * @param len how many payload bytes to generate
     * @return the payload bytes
     */
    private static byte[] payload(int len) {
        byte[] rv = new byte[len];
        for (int i = 0; i < len; i++) {
            rv[i] = (byte) ((i * 31 + 7) & 0x7f);
        }
        return rv;
    }

    /**
     * The unsigned packet every test is built from: fixed stream, sequence and
     * ack numbers, no FROM option, so {@code verifySignature()} uses the key it
     * is handed, one flag with no option bytes, and the given payload.
     *
     * @param payload payload to carry
     * @return the packet, with {@link Packet#FLAG_SIGNATURE_INCLUDED} set
     */
    private static Packet unsigned(byte[] payload) {
        Packet p = new Packet(null);
        p.setFlag(Packet.FLAG_RESET);
        p.setFlag(Packet.FLAG_ECHO);
        p.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        p.setSendStreamId(0x01020304);
        p.setReceiveStreamId(0x05060708);
        p.setSequenceNum(0x090a0b0c);
        p.setAckThrough(0x0d0e0f10L);
        p.setPayload(new ByteArray(payload));
        return p;
    }

    /**
     * Signature over the packet as it will be hashed: rebuilt with the signature
     * space zeroed, which is what the sender signs.
     *
     * @param ctx context for the signing engine
     * @param sk key to sign with
     * @param p packet to sign
     * @return a signature over exactly the bytes a sender would write for it
     */
    private static Signature signatureFor(I2PAppContext ctx, SigningPrivateKey sk, Packet p) {
        byte[] buf = new byte[p.writtenSize(KEY_TYPE)];
        int written = p.writePacket(buf, 0, KEY_TYPE.getSigLen());
        assertEquals(buf.length, written);
        return ctx.dsa().sign(buf, 0, written, sk);
    }

    /**
     * The signing key pair, generated fresh per test so no key material is
     * shared between them.
     *
     * @param ctx context for the key generator
     * @return the public key at 0 and the private key at 1
     * @throws GeneralSecurityException if the EdDSA pair could not be generated
     */
    private static SimpleDataStructure[] keys(I2PAppContext ctx) throws GeneralSecurityException {
        return ctx.keyGenerator().generateSigningKeys(KEY_TYPE);
    }

    /**
     * Wire bytes of a signed packet, placed at {@link #BUFFER_OFFSET} inside a
     * larger buffer, the way the dispatcher hands one packet out of a pooled
     * buffer.
     *
     * @param ctx context for the signing engine
     * @param sk key to sign with
     * @param payload payload to carry
     * @return a buffer whose packet occupies {@code [BUFFER_OFFSET, length)}
     */
    private static byte[] wire(I2PAppContext ctx, SigningPrivateKey sk, byte[] payload) {
        Packet p = unsigned(payload);
        p.setOptionalSignature(signatureFor(ctx, sk, p));
        byte[] tmp = new byte[Packet.MAX_PAYLOAD_SIZE + 1024];
        int written = p.writePacket(tmp, 0);
        byte[] buf = new byte[BUFFER_OFFSET + written];
        System.arraycopy(tmp, 0, buf, BUFFER_OFFSET, written);
        return buf;
    }

    /**
     * Read the packet in {@code buf} back the way the receive path does.
     *
     * @param buf buffer whose packet occupies {@code [BUFFER_OFFSET, length)}
     * @return the parsed packet
     */
    private static Packet read(byte[] buf) {
        Packet r = new Packet(null);
        r.readPacket(buf, BUFFER_OFFSET, buf.length - BUFFER_OFFSET);
        return r;
    }

    /**
     * Where the signature's option bytes sit inside a packet that carries only
     * this signature as an option: the last thing before the payload.
     *
     * @param buf buffer whose packet occupies {@code [BUFFER_OFFSET, length)}
     * @param payloadLen payload bytes the packet carries
     * @param sigSpace option bytes the signature occupies
     * @return offset of the signature region inside {@code buf}
     */
    private static int sigRegion(byte[] buf, int payloadLen, int sigSpace) {
        return BUFFER_OFFSET + (buf.length - BUFFER_OFFSET) - payloadLen - sigSpace;
    }

    /**
     * A signed packet that arrived in a buffer verifies with real crypto, with
     * no scratch buffer handed to it - the whole point of hashing in place.
     *
     * @throws GeneralSecurityException if the test key pair could not be generated
     */
    @Test
    public void receivedPacketVerifiesWithoutAScratchBuffer() throws GeneralSecurityException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SimpleDataStructure[] keys = keys(ctx);
        SigningPublicKey spk = (SigningPublicKey) keys[0];
        SigningPrivateKey sk = (SigningPrivateKey) keys[1];
        byte[] pl = payload(1000);
        byte[] buf = wire(ctx, sk, pl);

        Packet r = read(buf);
        assertTrue("a signed packet verifies from the buffer it was read in",
                   r.verifySignature(ctx, spk, null));
    }

    /**
     * The bytes hashed in place have to be the bytes the rebuild path would have
     * hashed, or the two paths disagree about a signature and a packet verifies
     * one way and not the other.
     *
     * @throws GeneralSecurityException if the test key pair could not be generated
     */
    @Test
    public void inPlaceBytesMatchTheRebuiltPacket() throws GeneralSecurityException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SimpleDataStructure[] keys = keys(ctx);
        SigningPrivateKey sk = (SigningPrivateKey) keys[1];
        byte[] pl = payload(500);
        byte[] buf = wire(ctx, sk, pl);
        Packet r = read(buf);

        int sigSpace = r.signatureSpaceLen(KEY_TYPE);
        int size = r.writtenSize(KEY_TYPE);
        byte[] rebuilt = new byte[size];
        assertEquals("the rebuild writes exactly the size it counted",
                     size, r.writePacket(rebuilt, 0, sigSpace));

        // The received packet with the signature region zeroed is what the sender
        // signed, so it has to be what the in-place path hashes.
        int pktLen = buf.length - BUFFER_OFFSET;
        assertEquals("the size we hash is the packet as it arrived", pktLen, size);
        byte[] received = Arrays.copyOfRange(buf, BUFFER_OFFSET, buf.length);
        Arrays.fill(received, sigRegion(buf, pl.length, sigSpace) - BUFFER_OFFSET,
                    sigRegion(buf, pl.length, sigSpace) - BUFFER_OFFSET + sigSpace, (byte) 0x0);
        assertArrayEquals("in place and rebuild hash the same bytes", rebuilt, received);
        assertEquals("the signature follows the fixed header here, no other options",
                     HEADER_SIZE, sigRegion(buf, pl.length, sigSpace) - BUFFER_OFFSET);
    }

    /**
     * Hashing in place borrows the packet's own buffer, so the zeroed signature
     * region has to be put back: the payload the packet carries is read after
     * verification, by a different thread on some connections.
     *
     * @throws GeneralSecurityException if the test key pair could not be generated
     */
    @Test
    public void signatureRegionIsRestoredAfterVerifying() throws GeneralSecurityException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SimpleDataStructure[] keys = keys(ctx);
        SigningPublicKey spk = (SigningPublicKey) keys[0];
        SigningPrivateKey sk = (SigningPrivateKey) keys[1];
        byte[] pl = payload(200);
        byte[] buf = wire(ctx, sk, pl);
        byte[] before = buf.clone();

        Packet r = read(buf);
        assertTrue(r.verifySignature(ctx, spk, null));
        assertArrayEquals("verification leaves the buffer as it found it", before, buf);

        // The cached answer is still true, and nothing more has been touched.
        assertTrue("a verified packet stays verified", r.verifySignature(ctx, spk, null));
        assertArrayEquals(before, buf);
        byte[] again = wire(ctx, sk, pl);
        assertArrayEquals("the payload is intact for its reader", again, buf);
        ByteArray pay = r.getPayload();
        assertEquals(pl.length, pay.getValid());
        assertArrayEquals("the payload view still points at the receive buffer", pl,
                          Arrays.copyOfRange(pay.getData(), pay.getOffset(), pay.getOffset() + pay.getValid()));
    }

    /**
     * A payload byte changed in flight still fails: the signature covers the
     * payload where it lies, exactly as it covered the rebuilt copy.
     *
     * @throws GeneralSecurityException if the test key pair could not be generated
     */
    @Test
    public void tamperedPayloadIsRefused() throws GeneralSecurityException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SimpleDataStructure[] keys = keys(ctx);
        SigningPublicKey spk = (SigningPublicKey) keys[0];
        SigningPrivateKey sk = (SigningPrivateKey) keys[1];
        byte[] pl = payload(300);
        byte[] buf = wire(ctx, sk, pl);
        buf[buf.length - 1] ^= 0x01;

        Packet r = read(buf);
        assertFalse("a changed payload byte voids the signature",
                    r.verifySignature(ctx, spk, null));
    }

    /**
     * A signature byte changed in flight still fails, and the borrowed bytes are
     * given back even on the failing path.
     *
     * @throws GeneralSecurityException if the test key pair could not be generated
     */
    @Test
    public void tamperedSignatureIsRefused() throws GeneralSecurityException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SimpleDataStructure[] keys = keys(ctx);
        SigningPublicKey spk = (SigningPublicKey) keys[0];
        SigningPrivateKey sk = (SigningPrivateKey) keys[1];
        byte[] pl = payload(300);
        byte[] buf = wire(ctx, sk, pl);
        byte[] before = buf.clone();
        buf[sigRegion(buf, pl.length, KEY_TYPE.getSigLen())] ^= 0x01;

        Packet r = read(buf);
        assertFalse("a changed signature byte does not verify",
                    r.verifySignature(ctx, spk, null));
        byte[] want = before.clone();
        want[sigRegion(before, pl.length, KEY_TYPE.getSigLen())] ^= 0x01;
        assertArrayEquals("the failure path puts the borrowed region back", want, buf);
    }

    /**
     * A packet truncated by one byte is refused: the reader takes whatever
     * payload is left, so the bytes it hashes are not the bytes the sender
     * signed.
     *
     * @throws GeneralSecurityException if the test key pair could not be generated
     */
    @Test
    public void truncatedPacketIsRefused() throws GeneralSecurityException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SimpleDataStructure[] keys = keys(ctx);
        SigningPublicKey spk = (SigningPublicKey) keys[0];
        SigningPrivateKey sk = (SigningPrivateKey) keys[1];
        byte[] pl = payload(300);
        byte[] full = wire(ctx, sk, pl);
        byte[] buf = Arrays.copyOf(full, full.length - 1);

        Packet r = read(buf);
        assertFalse("a packet one byte short of what was signed does not verify",
                    r.verifySignature(ctx, spk, null));
        assertArrayEquals("and the bytes that did arrive are left as they arrived",
                          Arrays.copyOf(full, buf.length), buf);
    }

    /**
     * The in-place gate refuses to hash past the end of the packet as it
     * arrived, which is the only thing standing between a mis-sized packet and a
     * hash over the rest of the pooled buffer.
     *
     * @throws GeneralSecurityException if the test key pair could not be generated
     */
    @Test
    public void gateRefusesToHashPastThePacketEnd() throws GeneralSecurityException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SimpleDataStructure[] keys = keys(ctx);
        byte[] pl = payload(300);
        Packet r = read(wire(ctx, (SigningPrivateKey) keys[1], pl));
        int size = r.writtenSize(KEY_TYPE);
        int sigSpace = r.signatureSpaceLen(KEY_TYPE);
        assertTrue("the packet as it arrived is hashable",
                   r.canHashInPlace(size, sigSpace));
        assertFalse("one byte past the packet end is not",
                    r.canHashInPlace(size + 1, sigSpace));
        // 22 is the minimum header size, so a signature space that leaves no
        // room inside the packet cannot be hashed from it
        assertFalse("nor is a signature space larger than the packet allows",
                    r.canHashInPlace(size, size - 22 + 1));
    }

    /**
     * The largest packet the protocol allows verifies in place, which is the case
     * the removed copy was paid for.
     *
     * @throws GeneralSecurityException if the test key pair could not be generated
     */
    @Test
    public void largestPayloadVerifiesInPlace() throws GeneralSecurityException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SimpleDataStructure[] keys = keys(ctx);
        SigningPublicKey spk = (SigningPublicKey) keys[0];
        SigningPrivateKey sk = (SigningPrivateKey) keys[1];
        byte[] pl = payload(Packet.MAX_PAYLOAD_SIZE);
        byte[] buf = wire(ctx, sk, pl);

        Packet r = read(buf);
        int sigSpace = r.signatureSpaceLen(KEY_TYPE);
        assertTrue("a full-size packet takes the in-place path",
                   r.canHashInPlace(r.writtenSize(KEY_TYPE), sigSpace));
        assertTrue(r.verifySignature(ctx, spk, null));
    }

    /**
     * The path gate: only a packet we read from a buffer we still hold, that is
     * signed, and whose length we can reproduce, is hashed where it lies.
     */
    @Test
    public void gateRefusesWhatItShould() {
        int sigLen = KEY_TYPE.getSigLen();
        Packet built = unsigned(payload(10));
        built.setOptionalSignature(new Signature(KEY_TYPE, payload(sigLen)));
        int builtSize = built.writtenSize(KEY_TYPE);
        assertFalse("a packet we built has no received bytes to hash",
                    built.canHashInPlace(builtSize, sigLen));

        byte[] pl = payload(10);
        byte[] tmp = new byte[Packet.MAX_PAYLOAD_SIZE + 1024];
        int written = built.writePacket(tmp, 0);
        byte[] buf = new byte[BUFFER_OFFSET + written];
        System.arraycopy(tmp, 0, buf, BUFFER_OFFSET, written);
        Packet r = read(buf);
        assertTrue("a received, signed packet of the right size takes it",
                   r.canHashInPlace(r.writtenSize(KEY_TYPE), sigLen));
        assertFalse("a size we cannot reproduce stays on the rebuild path",
                    r.canHashInPlace(r.writtenSize(KEY_TYPE) + 1, sigLen));
        assertFalse("an unsigned packet has no region to zero",
                    r.canHashInPlace(r.writtenSize(KEY_TYPE), 0));
        assertFalse("a region that would run past the packet is refused",
                    r.canHashInPlace(r.writtenSize(KEY_TYPE), buf.length - BUFFER_OFFSET));
    }
}
