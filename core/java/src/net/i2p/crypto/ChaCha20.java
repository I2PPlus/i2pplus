package net.i2p.crypto;

// License: MIT. See docs/LICENSES.md

import com.southernstorm.noise.crypto.chacha20.ChaChaCore;

import net.i2p.data.DataHelper;

/**
 * ChaCha20, wrapper around Noise ChaChaCore.
 * RFC 7539
 *
 * @since 0.9.39
 */
public final class ChaCha20 {

    private ChaCha20() {}

    /**
     * Encrypt from plaintext to ciphertext
     *
     *  @param key first 32 bytes used as the key
     *  @param iv first 12 bytes used as the iv
     *  @param plaintext the plaintext to encrypt
     *  @param plaintextOffset offset in plaintext
     *  @param ciphertext the ciphertext output buffer
     *  @param ciphertextOffset offset in ciphertext
     *  @param length the length
     */
    public static void encrypt(byte[] key, byte[] iv, byte[] plaintext, int plaintextOffset, byte[] ciphertext, int ciphertextOffset, int length) {
        encrypt(key, iv, 0, plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
    }

    /**
     * Encrypt from plaintext to ciphertext
     *
     * @param key first 32 bytes used as the key
     * @param iv first 12 bytes starting at ivOffset used as the iv
     * @param ivOffset offset into iv array
     * @param plaintext the plaintext input buffer
     * @param plaintextOffset offset into plaintext buffer
     * @param ciphertext the ciphertext output buffer
     * @param ciphertextOffset offset into ciphertext buffer
     * @param length the number of bytes to encrypt
     * @since 0.9.54
     */
    public static void encrypt(byte[] key, byte[] iv, int ivOffset, byte[] plaintext, int plaintextOffset, byte[] ciphertext, int ciphertextOffset, int length) {
        int[] input = new int[16];
        int[] output = new int[16];
        ChaChaCore.initKey256(input, key, 0);
        input[12] = 1;
        input[13] = (int) DataHelper.fromLongLE(iv, ivOffset, 4);
        input[14] = (int) DataHelper.fromLongLE(iv, ivOffset + 4, 4);
        input[15] = (int) DataHelper.fromLongLE(iv, ivOffset + 8, 4);
        ChaChaCore.hash(output, input);
        while (length > 0) {
            int tempLen = 64;
            if (tempLen > length) {
                tempLen = length;
            }
            ChaChaCore.hash(output, input);
            ChaChaCore.xorBlock(plaintext, plaintextOffset, ciphertext, ciphertextOffset, tempLen, output);
            if (++(input[12]) == 0) {
                ++(input[13]);
            }
            plaintextOffset += tempLen;
            ciphertextOffset += tempLen;
            length -= tempLen;
        }
    }

    /**
     * Encrypt from ciphertext to plaintext
     *
     *  @param key first 32 bytes used as the key
     *  @param iv first 12 bytes used as the iv
     *  @param ciphertext the ciphertext to decrypt
     *  @param ciphertextOffset offset in ciphertext
     *  @param plaintext the plaintext output buffer
     *  @param plaintextOffset offset in plaintext
     *  @param length the length
     */
    public static void decrypt(byte[] key, byte[] iv, byte[] ciphertext, int ciphertextOffset, byte[] plaintext, int plaintextOffset, int length) {
        // it's symmetric!
        encrypt(key, iv, 0, ciphertext, ciphertextOffset, plaintext, plaintextOffset, length);
    }

    /**
     * Encrypt from ciphertext to plaintext
     *
     *  @param key first 32 bytes used as the key
     *  @param iv first 12 bytes starting at ivOffset used as the iv
     *  @param ivOffset offset into iv array
     *  @param ciphertext the ciphertext to decrypt
     *  @param ciphertextOffset offset in ciphertext
     *  @param plaintext the plaintext output buffer
     *  @param plaintextOffset offset in plaintext
     *  @param length the length
     *  @since 0.9.54
     */
    public static void decrypt(byte[] key, byte[] iv, int ivOffset, byte[] ciphertext, int ciphertextOffset, byte[] plaintext, int plaintextOffset, int length) {
        // it's symmetric!
        encrypt(key, iv, ivOffset, ciphertext, ciphertextOffset, plaintext, plaintextOffset, length);
    }
}
