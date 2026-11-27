package com.southernstorm.noise.protocol;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.DigestException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.ShortBufferException;

import net.i2p.I2PAppContext;
import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.KeyPair;
import net.i2p.router.transport.crypto.X25519KeyFactory;

import org.junit.Test;

/**
 * Pins that a failed Noise round scrubs the whole range it was working in, and
 * does not trade the failure it is cleaning up for an index exception of its own.
 * <p>
 * The four scrub calls passed a length where {@code Arrays.fill()} wants an end
 * index, so the range they named was usually short of the range that needed
 * clearing, and once the offset was past the buffer's midpoint it was inverted -
 * and {@code Arrays.fill()} answers an inverted range with
 * {@code IllegalArgumentException}, thrown out of the handler that was meant to
 * contain the failure. Key material written before the failure survives in the
 * tail nobody cleared. Upstream fixed the end index in 08fa5065f8; the same four
 * sites are pinned here.
 *
 * @since 0.9.72+
 */
public class NoiseFailureScrubTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state, only the fill pattern and the
     * sizes the assertions are built against.
     *
     * @since 0.9.72+
     */
    public NoiseFailureScrubTest() {}

    /** Written everywhere before the round under test, so an unscrubbed byte stays visible. */
    private static final byte FILL = (byte) 0xaa;

    /** Bytes of a SHA-256 digest, the length every Noise digest call is sized for. */
    private static final int DIGEST = 32;

    /** Payload both handshake tests carry, long enough to need more room than is left. */
    private static final int PAYLOAD = 32;

    /**
     * Reflects one of the two private digest helpers of {@link SymmetricState}
     * and calls it with data buffers that always fit and an output range that
     * always fits, so the only thing wrong with the call is that the round
     * failed. The digest is replaced first, because when the range itself does
     * not fit, {@code MessageDigest.digest(byte[], int, int)} answers that with
     * {@code IllegalArgumentException} before the provider is asked, and the
     * scrub handler never runs at all.
     *
     * @param name helper to call, {@code hashTwo} or {@code hmac}
     * @param output buffer the digest is asked to write into, never null
     * @param outputOffset where in the buffer the digest is asked to start
     * @param outputLength how many bytes the caller asked the helper to write
     * @throws Exception the cause of the invocation failure, unwrapped, so a
     *                   {@code DigestException} that escaped the handler
     *                   fails the test with the exception that matters
     */
    private static void invokeDigest(String name, byte[] output, int outputOffset,
                                    int outputLength) throws Exception {
        Class<?>[] types = {byte[].class, int.class, int.class, byte[].class, int.class, int.class,
                            byte[].class, int.class, int.class};
        Method m = SymmetricState.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        SymmetricState state = new SymmetricState(NoiseInit.PatternID.N);
        Field hash = SymmetricState.class.getDeclaredField("hash");
        hash.setAccessible(true);
        hash.set(state, new FailingDigest());
        byte[] data = new byte[DIGEST];
        try {
            m.invoke(state, data, 0, data.length, data, 0, data.length,
                     output, outputOffset, outputLength);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    /**
     * A SHA-256 digest whose ranged digest call always fails, so the scrub
     * handler is entered on its own terms rather than on whatever schedule the
     * registered provider has for rejecting a range. Everything else is left to
     * the JDK.
     *
     * @since 0.9.72+
     */
    private static final class FailingDigest extends MessageDigest {

        /** Names the algorithm the state expects of its digest. */
        FailingDigest() {
            super("SHA-256");
        }

        @Override
        protected void engineUpdate(byte input) {}

        @Override
        protected void engineUpdate(byte[] input, int offset, int len) {}

        @Override
        protected byte[] engineDigest() {
            return new byte[DIGEST];
        }

        /**
         * The failure both helpers catch.
         *
         * @param buf unused, the call never writes
         * @param offset unused, the call never writes
         * @param len unused, the call never writes
         * @return never returns
         * @throws DigestException always, to put the caller in its failure handler
         */
        @Override
        protected int engineDigest(byte[] buf, int offset, int len) throws DigestException {
            throw new DigestException("forced, to reach the scrub handler");
        }

        @Override
        protected void engineReset() {}

        @Override
        protected int engineGetDigestLength() {
            return DIGEST;
        }
    }

    /**
     * Asserts that every byte of {@code [from, to)} was cleared and every byte
     * outside it still holds the fill pattern, which is what a scrub sized to the
     * range the round owned looks like.
     *
     * @param buf buffer to check, never null
     * @param from first index the scrub should have cleared
     * @param to one past the last index the scrub should have cleared
     */
    private static void assertCleared(byte[] buf, int from, int to) {
        for (int i = 0; i < buf.length; i++) {
            if (i >= from && i < to) {
                assertEquals("byte " + i + " was left unscrubbed", 0, buf[i]);
            } else {
                assertEquals("byte " + i + " was scrubbed outside the range", FILL, buf[i]);
            }
        }
    }

    /**
     * A digest round that fails clears the range it was asked to write. At HEAD
     * it cleared nothing of the range: the end index was the length, so with the
     * offset at 40 and the length at 32 the pair was {@code (40, 32)} - inverted,
     * and {@code Arrays.fill()} answers an inverted range with
     * {@code IllegalArgumentException} out of the failure handler.
     *
     * @throws Exception if the helper cannot be reached, or the failure it is
     *                   forced with escapes the handler instead of being
     *                   caught
     */
    @Test
    public void hashTwoScrubsTheRangeItWasAskedToWrite() throws Exception {
        byte[] output = new byte[96];
        Arrays.fill(output, FILL);
        invokeDigest("hashTwo", output, 40, DIGEST);
        assertCleared(output, 40, 40 + DIGEST);
    }

    /**
     * The HMAC path clears the same way. Its first digest call is the one that
     * fails, and the handler must clear the HMAC range rather than leave the
     * half-written value, or an index exception, in its place.
     *
     * @throws Exception if the helper cannot be reached, or the failure it is
     *                   forced with escapes the handler instead of being
     *                   caught
     */
    @Test
    public void hmacScrubsTheRangeItWasAskedToWrite() throws Exception {
        byte[] output = new byte[96];
        Arrays.fill(output, FILL);
        invokeDigest("hmac", output, 40, DIGEST);
        assertCleared(output, 40, 40 + DIGEST);
    }

    /**
     * Alice and Bob on the SSU2 handshake pattern, with static keys on both
     * sides, as an SSU2 session request is built.
     *
     * @param xdh key factory to draw both static key pairs from, never null
     * @return a two-element array holding Alice then Bob, both started
     * @throws Exception if the handshake states cannot be built
     */
    private static HandshakeState[] ssu2Pair(KeyFactory xdh) throws Exception {
        NoiseInit.PatternID pattern = NoiseInit.PatternID.XK_SSU2;
        KeyPair aliceRI = xdh.getKeys();
        KeyPair bobRI = xdh.getKeys();
        HandshakeState alice = new HandshakeState(pattern, HandshakeState.INITIATOR, xdh);
        alice.getLocalKeyPair().setKeys(aliceRI.getPrivate().getData(), 0,
                                        aliceRI.getPublic().getData(), 0);
        alice.getRemotePublicKey().setPublicKey(bobRI.getPublic().getData(), 0);
        HandshakeState bob = new HandshakeState(pattern, HandshakeState.RESPONDER, xdh);
        bob.getLocalKeyPair().setKeys(bobRI.getPrivate().getData(), 0,
                                      bobRI.getPublic().getData(), 0);
        bob.getRemotePublicKey().setPublicKey(aliceRI.getPublic().getData(), 0);
        alice.start();
        bob.start();
        return new HandshakeState[] {alice, bob};
    }

    /**
     * A handshake write that cannot fit its message buffer fails, and everything
     * from the message offset to the end of the buffer is cleared. At HEAD the
     * range was {@code (40, 60 - 40)} - inverted, so the handler threw
     * {@code IllegalArgumentException} out of the failure path and the caller saw
     * the wrong exception.
     *
     * @throws Exception if the static keys or the handshake states cannot be
     *                   built
     */
    @Test
    public void failedHandshakeWriteScrubsToTheEndOfTheBuffer() throws Exception {
        KeyFactory xdh = new X25519KeyFactory(I2PAppContext.getGlobalContext());
        HandshakeState alice = ssu2Pair(xdh)[0];
        byte[] payload = new byte[PAYLOAD];
        byte[] message = new byte[60];
        Arrays.fill(message, FILL);
        try {
            // 20 bytes of room, less than the ephemeral public key alone.
            alice.writeMessage(message, 40, payload, 0, payload.length);
            fail("a write with no room for its first token should not complete");
        } catch (ShortBufferException expected) {
            // The caller must see the buffer error, not one thrown by the scrub
            // while unwinding.
        }
        assertCleared(message, 40, message.length);
    }

    /**
     * A handshake read whose payload does not fit fails, and everything from the
     * payload offset to the end of that buffer is cleared, so the plaintext the
     * cipher wrote before running out of room does not survive beside the bytes it
     * never reached. At HEAD the range was inverted the same way.
     *
     * @throws Exception if the static keys or the handshake states cannot be
     *                   built, or the handshake completes when the payload
     *                   was expected not to fit
     */
    @Test
    public void failedHandshakeReadScrubsToTheEndOfTheBuffer() throws Exception {
        KeyFactory xdh = new X25519KeyFactory(I2PAppContext.getGlobalContext());
        HandshakeState[] pair = ssu2Pair(xdh);
        byte[] payload = new byte[PAYLOAD];
        Arrays.fill(payload, (byte) 0x5a);
        byte[] message = new byte[256];
        int written = pair[0].writeMessage(message, 0, payload, 0, payload.length);
        byte[] recovered = new byte[48];
        Arrays.fill(recovered, FILL);
        try {
            // 8 bytes of room at the offset for a 32-byte payload.
            pair[1].readMessage(message, 0, written, recovered, 40);
            fail("a read with no room for its payload should not complete");
        } catch (ShortBufferException expected) {
            // As above: the buffer error is the finding, anything else is the
            // scrub failing.
        }
        assertCleared(recovered, 40, recovered.length);
    }
}
