package com.southernstorm.noise.protocol;

import static org.junit.Assert.*;

import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

/**
 * The cipher state clones its Poly1305 and its key material. Destroying the clone
 * must not take the original's key or nonce with it, and the original must still
 * produce the tag an independent ChaCha20-Poly1305 produces.
 *
 * @since 0.9.72+
 */
public class ChaChaPolyCloneIsolationTest {

    /**
     * A clone that is destroyed leaves the original encrypting correctly. The
     * cipher state re-keys its Poly1305 per message, so what this really pins is
     * that the clone does not share the key or the ChaCha block buffers - a
     * shared polyKey would be zeroed by the clone's destroy() and every later tag
     * would be wrong.
     *
     * @throws Exception if the JDK's ChaCha20-Poly1305 is unavailable or rejects
     *                   the parameters
     */
    @Test
    public void testDestroyingTheCloneDoesNotInvalidateTheOriginalCipher() throws Exception {
        byte[] key = filled((byte) 0x5a, 32);
        ChaChaPolyCipherState original = state(key, 11);
        ChaChaPolyCipherState copy = original.clone();
        copy.destroy();
        assertTrue(original.hasKey());
        byte[] actual = new byte[17];
        original.encryptWithAd(null, new byte[1], 0, actual, 0, 1);
        assertArrayEquals(reference(key, 11, new byte[0], new byte[1]), actual);
    }

    /**
     * The clone starts at the same nonce as its original, and each side advances
     * on its own - a shared counter would leave the clone one packet behind.
     *
     * @throws Exception if the JDK's ChaCha20-Poly1305 is unavailable or rejects
     *                   the parameters
     */
    @Test
    public void testCloneContinuesFromTheSameNonceButAdvancesAlone() throws Exception {
        byte[] key = filled((byte) 0x33, 32);
        byte[] message = "same plaintext".getBytes("US-ASCII");
        ChaChaPolyCipherState original = state(key, 7);
        ChaChaPolyCipherState copy = original.clone();
        byte[] a = new byte[message.length + 16];
        byte[] b = new byte[message.length + 16];
        original.encryptWithAd(null, message, 0, a, 0, message.length);
        copy.encryptWithAd(null, message, 0, b, 0, message.length);
        assertArrayEquals(a, b);
        original.encryptWithAd(null, message, 0, a, 0, message.length);
        copy.encryptWithAd(null, message, 0, b, 0, message.length);
        assertArrayEquals(reference(key, 8, new byte[0], message), b);
    }

    /**
     * A cipher state with a key and a starting nonce.
     *
     * @param key the 32-byte key
     * @param nonce the first nonce to use
     * @return a cipher state keyed with key and set to start at nonce
     */
    private static ChaChaPolyCipherState state(byte[] key, long nonce) {
        ChaChaPolyCipherState cipher = new ChaChaPolyCipherState();
        cipher.initializeKey(key, 0);
        cipher.setNonce(nonce);
        return cipher;
    }

    /**
     * The same packet from the JDK's own ChaCha20-Poly1305, with the nonce in the
     * last eight bytes of the IV, little-endian, as Noise defines it.
     *
     * @param key the 32-byte key
     * @param nonce the nonce for this packet
     * @param ad associated data, empty for none
     * @param message the bytes to encrypt and authenticate
     * @return the ciphertext with its tag
     * @throws Exception if the JDK cipher rejects the parameters
     */
    private static byte[] reference(byte[] key, long nonce, byte[] ad, byte[] message) throws Exception {
        byte[] iv = new byte[12];
        for (int i = 0; i < 8; i++)
            iv[4 + i] = (byte) (nonce >>> (8 * i));
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(iv));
        cipher.updateAAD(ad);
        return cipher.doFinal(message);
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
}
