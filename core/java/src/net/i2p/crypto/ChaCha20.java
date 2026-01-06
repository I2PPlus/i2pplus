package net.i2p.crypto;

/*
 * Contains code from Noise ChaChaPolyCipherState:
 *
 * Copyright (C) 2016 Southern Storm Software, Pty Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

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
     *  @param length the length to encrypt
     */
    public static void encrypt(byte[] key, byte[] iv,
                               byte[] plaintext, int plaintextOffset,
                               byte[] ciphertext, int ciphertextOffset, int length) {
        encrypt(key, iv, 0, plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
    }

    /**
     * Encrypt from plaintext to ciphertext
     *
     * @param key first 32 bytes used as the key
     * @param iv first 12 bytes starting at ivOffset used as the iv
     * @since 0.9.54
     */
    public static void encrypt(byte[] key, byte[] iv, int ivOffset,
                               byte[] plaintext, int plaintextOffset,
                               byte[] ciphertext, int ciphertextOffset, int length) {
        int[] input = new int[16];
        int[] output = new int[16];
        ChaChaCore.initKey256(input, key, 0);
        //System.out.println("initkey");
        //dumpBlock(input);
        // RFC 7539
        // block counter
        input[12] = 1;
        // Words 13-15 are a nonce, which should not be repeated for the same
        // key.  The 13th word is the first 32 bits of the input nonce taken
        // as a little-endian integer, while the 15th word is the last 32
        // bits.
        //ChaChaCore.initIV(input, iv, counter);
        //ChaChaCore.initIV(input, iv[4:11], iv[0:3]);
        input[13] = (int) DataHelper.fromLongLE(iv, ivOffset, 4);
        input[14] = (int) DataHelper.fromLongLE(iv, ivOffset + 4, 4);
        input[15] = (int) DataHelper.fromLongLE(iv, ivOffset + 8, 4);
        //System.out.println("initIV");
        //dumpBlock(input);
        ChaChaCore.hash(output, input);
        //int ctr = 1;
        //System.out.println("hash " + ctr);
        //dumpBlock(output);
        while (length > 0) {
            int tempLen = 64;
            if (tempLen > length) {tempLen = length;}
            ChaChaCore.hash(output, input);
            //System.out.println("hash " + ++ctr);
            //dumpBlock(output);
            ChaChaCore.xorBlock(plaintext, plaintextOffset, ciphertext, ciphertextOffset, tempLen, output);
            if (++(input[12]) == 0) {++(input[13]);}
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
     *  @param length the length to decrypt
     */
    public static void decrypt(byte[] key, byte[] iv,
                               byte[] ciphertext, int ciphertextOffset,
                               byte[] plaintext, int plaintextOffset, int length) {
        // it's symmetric!
        encrypt(key, iv, 0, ciphertext, ciphertextOffset, plaintext, plaintextOffset, length);
    }

    /**
     * Encrypt from ciphertext to plaintext
     *
     *  @param key first 32 bytes used as the key
     *  @param iv first 12 bytes starting at ivOffset used as the iv
     *  @param ciphertext the ciphertext to decrypt
     *  @param ciphertextOffset offset in ciphertext
     *  @param plaintext the plaintext output buffer
     *  @param plaintextOffset offset in plaintext
     *  @param length the length to decrypt
     *  @since 0.9.54
     */
    public static void decrypt(byte[] key, byte[] iv, int ivOffset,
                               byte[] ciphertext, int ciphertextOffset,
                               byte[] plaintext, int plaintextOffset, int length) {
        // it's symmetric!
        encrypt(key, iv, ivOffset, ciphertext, ciphertextOffset, plaintext, plaintextOffset, length);
    }

}
