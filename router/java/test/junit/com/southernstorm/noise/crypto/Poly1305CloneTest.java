package com.southernstorm.noise.crypto;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

/**
 * Poly1305 is cloned by the ChaChaPoly cipher state, so a clone that shares the
 * key, nonce and accumulator arrays with its original is a trap: destroying or
 * re-keying either one silently corrupts the other, and absorbing a message into
 * one rewrites the other's partial block. These pin the deep copy, against the
 * RFC 8439 answer so the copy cannot pass by changing the output.
 *
 * @since 0.9.72+
 */
public class Poly1305CloneTest {

    /** RFC 8439 section 2.5.2 key. */
    private static final byte[] RFC_KEY =
        hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b");

    /**
     * The implementation itself has to be right before the copy is: the RFC
     * 8439 known-answer tag.
     */
    @Test
    public void testRfc8439KnownAnswer() {
        byte[] data = "Cryptographic Forum Research Group".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(hex("a8061dc1305136c6c22b8baf0c0127a9"), tag(RFC_KEY, data));
    }

    /**
     * Two branches from one partially-absorbed prefix, each finished on its own,
     * must both match the tag of the whole message. A shared accumulator or block
     * makes the second branch absorb the first branch's bytes.
     *
     * @throws Exception not expected; {@code clone()} is always supported here
     */
    @Test
    public void testPartialBlockCloneKeepsAnIndependentAccumulator() throws Exception {
        byte[] prefix = "shared partial prefix".getBytes(StandardCharsets.US_ASCII);
        byte[] first = " first branch".getBytes(StandardCharsets.US_ASCII);
        byte[] second = " second branch has more than one block".getBytes(StandardCharsets.US_ASCII);
        Poly1305 original = new Poly1305();
        original.reset(RFC_KEY, 0);
        original.update(prefix, 0, prefix.length);
        Poly1305 copy = original.clone();

        original.update(first, 0, first.length);
        byte[] originalTag = new byte[16];
        original.finish(originalTag, 0);
        original.destroy();

        copy.update(second, 0, second.length);
        byte[] copyTag = new byte[16];
        copy.finish(copyTag, 0);

        assertArrayEquals(tag(RFC_KEY, concatenate(prefix, first)), originalTag);
        assertArrayEquals(tag(RFC_KEY, concatenate(prefix, second)), copyTag);
    }

    /**
     * Re-keying the clone must not replace the original's key - the key lives in
     * the r and c arrays, which a shallow clone shares.
     *
     * @throws Exception not expected; {@code clone()} is always supported here
     */
    @Test
    public void testResettingTheCloneDoesNotReplaceTheOriginalKey() throws Exception {
        byte[] key = filled((byte) 0x5a, 32);
        byte[] message = new byte[17];
        Poly1305 original = new Poly1305();
        original.reset(key, 0);
        original.update(message, 0, 3);
        Poly1305 copy = original.clone();
        copy.reset(new byte[32], 0);
        original.update(message, 3, message.length - 3);
        byte[] result = new byte[16];
        original.finish(result, 0);
        assertArrayEquals(tag(key, message), result);
    }

    /**
     * Destroying the clone must leave the original able to finish its own
     * message - destroy() zeroes nonce, block, h, r, c and t.
     *
     * @throws Exception not expected; {@code clone()} is always supported here
     */
    @Test
    public void testDestroyingTheCloneDoesNotZeroTheOriginal() throws Exception {
        byte[] key = filled((byte) 0xa5, 32);
        byte[] message = "message for the original only".getBytes(StandardCharsets.US_ASCII);
        Poly1305 original = new Poly1305();
        original.reset(key, 0);
        original.update(message, 0, message.length);
        Poly1305 copy = original.clone();
        copy.destroy();
        byte[] result = new byte[16];
        original.finish(result, 0);
        assertArrayEquals(tag(key, message), result);
    }

    /**
     * The tag of a whole message, computed by a fresh authenticator.
     *
     * @param key the 32-byte key
     * @param message the bytes to authenticate
     * @return the 16-byte tag
     */
    private static byte[] tag(byte[] key, byte[] message) {
        Poly1305 poly = new Poly1305();
        poly.reset(key, 0);
        poly.update(message, 0, message.length);
        byte[] result = new byte[16];
        poly.finish(result, 0);
        return result;
    }

    /**
     * @param first the leading bytes
     * @param second the trailing bytes
     * @return the two arrays end to end
     */
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /**
     * @param value the byte to fill with
     * @param length how many bytes
     * @return the filled array
     */
    private static byte[] filled(byte value, int length) {
        byte[] data = new byte[length];
        Arrays.fill(data, value);
        return data;
    }

    /**
     * @param text hex digits, an even count
     * @return the decoded bytes
     */
    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return bytes;
    }
}
