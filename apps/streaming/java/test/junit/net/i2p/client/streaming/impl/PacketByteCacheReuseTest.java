package net.i2p.client.streaming.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Random;

import net.i2p.I2PAppContext;

import net.i2p.client.I2PSession;
import net.i2p.crypto.SigType;
import net.i2p.data.ByteArray;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.ByteCache;

import org.junit.Test;

/**
 * Pins the no-memset release contract used by the streaming byte-cache sites.
 *
 * <p>Background: {@link ByteCache#release(ByteArray)} zeroed its whole entry
 * ({@code Arrays.fill} over the entry size) on every return to the pool, and the
 * streaming caches hold 64KiB queue buffers, 32KiB payload buffers and 4KiB
 * verify buffers, so every send and every signed-packet verification paid a
 * full-buffer memset for no reason. The six streaming sites now release with
 * {@link ByteCache#release(ByteArray, boolean) release(entry, false)}:
 *
 * <ul>
 * <li><b>{@link PacketQueue}</b> - {@code writePacket()} overwrites exactly the
 * range {@code sendMessage()} hands to the session, {@code [0, size)};</li>
 * <li><b>{@link PacketLocal#releasePayload()}</b> - the next owner overwrites
 * exactly {@code [0, getValid())}, in
 * {@code ConnectionDataReceiver.buildPacket()} or {@code Packet.writePacket()};</li>
 * <li><b>the four {@code verifySignature()} buffers</b> in
 * {@link ConnectionPacketHandler} and {@link PacketHandler} -
 * {@code Packet.verifyWithKey()} rebuilds the packet with
 * {@code writePacket(buffer, 0, sigSpace)} and hashes only the range it just
 * wrote, so leftover bytes are never read.</li>
 * </ul>
 *
 * <p>What this test pins down, in order: that a no-memset release really does
 * hand back a dirty buffer (the memset was the only thing keeping pooled buffers
 * uniform, so this is what makes the fix load-bearing rather than cosmetic);
 * that each producer overwrites every byte its consumer reads, proven by
 * round-tripping through deliberately poisoned buffers and getting identical
 * bytes out of buffers with different leftovers; that one of the converted sites,
 * {@link PacketLocal#releasePayload()}, actually returns the buffer dirty; and
 * that a signed packet verifies in a dirty buffer while the hash covers exactly
 * the rebuilt range - leftover bytes past it are not hashed, and a payload byte
 * inside it is.
 *
 * @since 0.9.72+
 */
public class PacketByteCacheReuseTest {

    /**
     * JUnit instantiates one instance per test method; each test builds its own
     * packets and poisons its own buffers, so there is no shared state to set up.
     */
    public PacketByteCacheReuseTest() {
    }

    /**
     * Entry size for the caches this test creates. Deliberately not 4096, 32768
     * or 65536: {@link ByteCache} keys its global cache map by entry size, so a
     * shared size would put poisoned buffers into the production pools.
     */
    private static final int CACHE_SIZE = 4100;

    /** Sig type used by the signed-packet tests. */
    private static final SigType KEY_TYPE = SigType.EdDSA_SHA512_Ed25519;

    /** Payload size of the signed test packet. */
    private static final int PAYLOAD_SIZE = 500;

    /** First fill used to poison a buffer before handing it to a producer. */
    private static final byte POISON = (byte) 0xA5;

    /** Second fill, so two buffers can carry different leftovers. */
    private static final byte OTHER_POISON = (byte) 0x5C;

    /**
     * A buffer released with {@code shouldZero} false comes back with its bytes
     * intact; one released with {@code shouldZero} true comes back zeroed. This
     * is the whole difference the fix relies on, and it is also what makes the
     * fix load-bearing: without the memset a pooled buffer carries its previous
     * owner's bytes, so every other test here is about that leftover tail.
     */
    @Test
    public void testNoMemsetReleaseKeepsDirtyBytes() {
        ByteCache cache = ByteCache.getInstance(2, CACHE_SIZE);
        ByteArray ba = cache.acquire();
        byte[] data = ba.getData();
        assertEquals(CACHE_SIZE, data.length);
        Arrays.fill(data, POISON);

        cache.release(ba, false);
        ByteArray again = cache.acquire();
        assertSame(again, ba);
        assertSame(again.getData(), data);
        for (int i = 0; i < CACHE_SIZE; i++) {
            assertEquals("byte " + i + " was zeroed by a no-memset release",
                         POISON, again.getData()[i]);
        }

        // and the zeroing release really does clear it, so the assertions above
        // are about the flag, not about the pool refusing to reuse the entry
        cache.release(again, true);
        ByteArray clean = cache.acquire();
        assertSame(clean.getData(), data);
        for (int i = 0; i < CACHE_SIZE; i++) {
            assertEquals("byte " + i + " survived a zeroing release",
                         0, clean.getData()[i]);
        }
        cache.release(clean, false);
    }

    /**
     * The {@link PacketQueue} contract: {@code writePacket()} fills exactly the
     * range {@code sendMessage()} passes to the session, {@code [0, size)}, so a
     * buffer that still holds another connection's bytes produces byte-identical
     * output on the wire.
     */
    @Test
    public void testWritePacketOverwritesEverythingRead() {
        ByteCache cache = ByteCache.getInstance(2, CACHE_SIZE);

        Packet pkt = new Packet(null);
        pkt.setFlag(Packet.FLAG_SYNCHRONIZE);
        pkt.setFlag(Packet.FLAG_DELAY_REQUESTED);
        pkt.setFlag(Packet.FLAG_MAX_PACKET_SIZE_INCLUDED);
        pkt.setSendStreamId(0x11223344);
        pkt.setReceiveStreamId(0x55667788);
        pkt.setSequenceNum(0x99aabbcc);
        pkt.setAckThrough(0xddeeff00L);
        pkt.setNacks(new long[] {7, 9, 11});
        pkt.setResendDelay(3);
        pkt.setOptionalDelay(250);
        pkt.setOptionalMaxSize(16384);
        byte[] payload = new byte[300];
        new Random(13L).nextBytes(payload);
        pkt.setPayload(new ByteArray(payload));

        // first write, into a poisoned buffer
        ByteArray ba = cache.acquire();
        Arrays.fill(ba.getData(), POISON);
        int size = pkt.writePacket(ba.getData(), 0, 0);
        assertEquals(pkt.writtenSize(null), size);
        assertTrue(size > 0);
        byte[] first = Arrays.copyOfRange(ba.getData(), 0, size);

        // second write, into the same buffer, which now holds the first packet's
        // bytes plus the poison that survived past `size`
        cache.release(ba, false);
        ByteArray ba2 = cache.acquire();
        assertSame(ba, ba2);
        int size2 = pkt.writePacket(ba2.getData(), 0, 0);
        assertEquals(size, size2);
        assertArrayEquals("a dirty pooled buffer changed the bytes on the wire",
                          first, Arrays.copyOfRange(ba2.getData(), 0, size2));

        // the tail past `size` is leftover poison that the session never sees -
        // it exists, which is why the memset was being paid, and it is ignored,
        // which is why skipping the memset is safe
        assertEquals(POISON, ba2.getData()[size]);
        assertEquals(POISON, ba2.getData()[CACHE_SIZE - 1]);
        cache.release(ba2, false);
    }

    /**
     * The {@link PacketLocal#releasePayload()} contract: the next owner writes
     * exactly {@code [0, getValid())}, so a payload buffer that still holds a
     * previous packet's bytes yields the new packet's bytes for the whole range
     * that gets copied into a wire buffer.
     */
    @Test
    public void testPayloadCopyOverwritesValidRange() {
        ByteCache cache = ByteCache.getInstance(2, CACHE_SIZE);

        // acquire the way ConnectionDataReceiver.buildPacket() does, and poison
        // it with the previous packet's contents
        ByteArray dst = cache.acquire();
        Arrays.fill(dst.getData(), POISON);

        byte[] src = new byte[200];
        new Random(7L).nextBytes(src);
        int valid = src.length;
        System.arraycopy(src, 0, dst.getData(), dst.getOffset(), valid);
        dst.setValid(valid);
        dst.setOffset(0);

        assertArrayEquals("stale bytes survived inside the valid range",
                          src, Arrays.copyOfRange(dst.getData(), 0, valid));
        // past the valid range the old contents survive, and nothing reads them:
        // Packet.writePacket() copies payload.getValid() bytes, no more
        assertEquals(POISON, dst.getData()[valid]);

        // now the consumer's view: a shorter packet reusing this buffer
        byte[] src2 = new byte[50];
        new Random(11L).nextBytes(src2);
        System.arraycopy(src2, 0, dst.getData(), 0, src2.length);
        dst.setValid(src2.length);
        Packet pkt = new Packet(null);
        pkt.setFlag(Packet.FLAG_SYNCHRONIZE);
        pkt.setPayload(dst);
        ByteArray out = cache.acquire();
        Arrays.fill(out.getData(), OTHER_POISON);
        int size = pkt.writePacket(out.getData(), 0, 0);
        assertArrayEquals(src2, Arrays.copyOfRange(out.getData(), size - src2.length, size));

        cache.release(dst, false);
        cache.release(out, false);
    }

    /**
     * One of the converted sites exercised directly:
     * {@link PacketLocal#releasePayload()} returns the 32KiB payload buffer to
     * the shared cache without wiping it, so the buffer that comes back out of
     * the pool still holds the previous packet's bytes. If the site reverts to
     * the zeroing {@link ByteCache#release(ByteArray)} form, the poison
     * assertion fails, which is what gives the fix teeth at the call site.
     */
    @Test
    public void testReleasePayloadReturnsDirtyBufferToPool() {
        // the same shared cache PacketLocal releases into, keyed by entry size
        ByteCache cache = ByteCache.getInstance(128, Packet.MAX_PAYLOAD_SIZE);
        ByteArray ba = cache.acquire();
        assertEquals(Packet.MAX_PAYLOAD_SIZE, ba.getData().length);
        Arrays.fill(ba.getData(), POISON);
        ba.setValid(PAYLOAD_SIZE);

        PacketLocal pkt = new PacketLocal(I2PAppContext.getGlobalContext(), null, (I2PSession) null);
        pkt.setPayload(ba);
        pkt.releasePayload();
        assertNull(pkt.getPayload());

        // the cache is LIFO, so it hands the same entry straight back
        ByteArray reused = cache.acquire();
        assertSame("the released buffer did not come back out of the payload cache", ba, reused);
        for (int i = PAYLOAD_SIZE; i < ba.getData().length; i++) {
            if (ba.getData()[i] != POISON) {
                fail("releasePayload() zeroed the buffer, first wiped byte at " + i);
            }
        }
        // leave the shared pool clean for the rest of the suite
        cache.release(reused, true);
    }

    /**
     * The {@code verifySignature()} contract for a packet that was written out
     * rather than read in: {@code verifyWithKey()} rebuilds it into the caller's
     * buffer and hashes only the range it wrote, so a buffer holding another
     * connection's leftovers verifies identically, the leftovers past the range
     * are not hashed, and a payload byte inside the range is. A packet built by
     * {@code readPacket()} takes the in-place path instead and is covered by
     * {@link PacketSignatureInPlaceTest}.
     *
     * <p>Each verification uses its own {@link Packet} because a successful
     * verification is cached on the object, so a second call would be answered
     * from the cache instead of by hashing.
     *
     * @throws GeneralSecurityException if the EdDSA test key pair could not be
     *         generated
     */
    @Test
    public void testSignedPacketVerifiesInDirtyBuffer() throws GeneralSecurityException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SimpleDataStructure[] keys = ctx.keyGenerator().generateSigningKeys(KEY_TYPE);
        SigningPublicKey spk = (SigningPublicKey) keys[0];
        SigningPrivateKey sk = (SigningPrivateKey) keys[1];
        int sigLen = KEY_TYPE.getSigLen();
        ByteCache cache = ByteCache.getInstance(2, CACHE_SIZE);

        // the signature every packet below carries
        Signature sig = signatureFor(ctx, sk, testPacket(payload(29L)));

        // 1. verifies in a buffer poisoned with one pattern
        ByteArray ba = cache.acquire();
        Arrays.fill(ba.getData(), POISON);
        Packet pkt = signedPacket(ctx, sk, payload(29L));
        int size = pkt.writePacket(ba.getData(), 0, sigLen);
        assertEquals(pkt.writtenSize(KEY_TYPE), size);
        assertTrue(pkt.verifySignature(ctx, spk, ba.getData()));

        // 2. and the same packet verifies in a buffer poisoned with a different
        // pattern, with the rebuilt bytes identical both times
        ByteArray ba2 = cache.acquire();
        Arrays.fill(ba2.getData(), OTHER_POISON);
        Packet pkt2 = signedPacket(ctx, sk, payload(29L));
        int size2 = pkt2.writePacket(ba2.getData(), 0, sigLen);
        assertEquals(size, size2);
        assertArrayEquals(Arrays.copyOfRange(ba.getData(), 0, size),
                          Arrays.copyOfRange(ba2.getData(), 0, size2));
        assertTrue(pkt2.verifySignature(ctx, spk, ba2.getData()));

        // 3. leftovers past the rebuilt range are not part of the hash, which is
        // exactly why the memset is safe to skip
        ba2.getData()[size] = POISON;
        ba2.getData()[CACHE_SIZE - 1] = POISON;
        Packet pkt3 = signedPacket(ctx, sk, payload(29L));
        pkt3.writePacket(ba2.getData(), 0, sigLen);
        assertTrue(pkt3.verifySignature(ctx, spk, ba2.getData()));

        // 4. but a payload byte inside the range is hashed, so the test above is
        // not passing because nothing at all is covered: same signature, one
        // payload byte changed, verification must fail
        byte[] flipped = payload(29L);
        flipped[PAYLOAD_SIZE - 1] ^= 0x01;
        Packet pkt4 = testPacket(flipped);
        pkt4.setOptionalSignature(sig);
        assertFalse(pkt4.verifySignature(ctx, spk, ba2.getData()));

        cache.release(ba, false);
        cache.release(ba2, false);
    }

    /**
     * Deterministic test payload.
     *
     * @param seed seed for the payload contents
     * @return {@link #PAYLOAD_SIZE} random bytes
     */
    private static byte[] payload(long seed) {
        byte[] payload = new byte[PAYLOAD_SIZE];
        new Random(seed).nextBytes(payload);
        return payload;
    }

    /**
     * A signed RESET packet with a payload, the shape the {@code
     * verifySignature()} sites handle: {@link Packet#FLAG_SIGNATURE_INCLUDED}
     * set and an {@code _optionSignature} of {@link #KEY_TYPE}.
     *
     * @param ctx context for the signing engine
     * @param sk key to sign with
     * @param payload payload bytes to carry
     * @return the signed packet
     */
    private static Packet signedPacket(I2PAppContext ctx, SigningPrivateKey sk, byte[] payload) {
        Packet p = testPacket(payload);
        p.setOptionalSignature(signatureFor(ctx, sk, p));
        return p;
    }

    /**
     * The unsigned packet the signed-packet tests are built from: fixed stream,
     * sequence and ack numbers, {@link Packet#FLAG_RESET},
     * {@link Packet#FLAG_ECHO} and {@link Packet#FLAG_SIGNATURE_INCLUDED} set,
     * and the given payload.
     *
     * @param payload payload bytes to carry
     * @return the unsigned packet
     */
    private static Packet testPacket(byte[] payload) {
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
     * Signature over the packet's rebuilt bytes, with the signature space zeroed
     * the way {@code writePacket()} does it while the signature is still unknown,
     * so {@code verifyWithKey()} rebuilds exactly these bytes.
     *
     * @param ctx context for the signing engine
     * @param sk key to sign with
     * @param p packet to sign
     * @return the signature
     */
    private static Signature signatureFor(I2PAppContext ctx, SigningPrivateKey sk, Packet p) {
        int sigSpace = KEY_TYPE.getSigLen();
        byte[] buf = new byte[p.writtenSize(KEY_TYPE)];
        int written = p.writePacket(buf, 0, sigSpace);
        assertEquals(buf.length, written);
        return ctx.dsa().sign(buf, 0, written, sk);
    }
}
