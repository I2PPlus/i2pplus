package com.southernstorm.noise.protocol;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;

/**
 * Interface to an authenticated cipher for use in the Noise protocol.
 *
 * CipherState objects are used to encrypt or decrypt data during a
 * session.  Once the handshake has completed, HandshakeState.split()
 * will create two CipherState objects for encrypting packets sent to
 * the other party, and decrypting packets received from the other party.
 */
public interface CipherState extends Destroyable, Cloneable {

    /**
     * Gets the Noise protocol name for this cipher.
     *
     * @return The cipher name.
     */
    String getCipherName();

    /**
     * Gets the length of the key values for this cipher.
     *
     * @return The length of the key in bytes; usually 32.
     */
    int getKeyLength();

    /**
     * Gets the length of the MAC values for this cipher.
     *
     * @return The length of MAC values in bytes, or zero if the
     * key has not yet been initialized.
     */
    int getMACLength();

    /**
     * Initializes the key on this cipher object.
     *
     * @param key Points to a buffer that contains the key.
     * @param offset The offset of the key in the key buffer.
     *
     * The key buffer must contain at least getKeyLength() bytes
     * starting at offset.
     *
     * @see #hasKey()
     */
    void initializeKey(byte[] key, int offset);

    /**
     * Determine if this cipher object has been configured with a key.
     *
     * @return true if this cipher object has a key; false if the
     * key has not yet been set with initializeKey().
     *
     * @see #initializeKey(byte[], int)
     */
    boolean hasKey();

    /**
     * Encrypts a plaintext buffer using the cipher and a block of associated data.
     *
     * @param ad The associated data, or null if there is none.
     * @param plaintext The buffer containing the plaintext to encrypt.
     * @param plaintextOffset The offset within the plaintext buffer of the
     * first byte or plaintext data.
     * @param ciphertext The buffer to place the ciphertext in.  This can
     * be the same as the plaintext buffer.
     * @param ciphertextOffset The first offset within the ciphertext buffer
     * to place the ciphertext and the MAC tag.
     * @param length The length of the plaintext.
     * @return The length of the ciphertext plus the MAC tag, or -1 if the
     * ciphertext buffer is not large enough to hold the result.
     *
     * @throws ShortBufferException The ciphertext buffer does not have
     * enough space to hold the ciphertext plus MAC.
     *
     * @throws IllegalStateException The nonce has wrapped around.
     *
     * The plaintext and ciphertext buffers can be the same for in-place
     * encryption.  In that case, plaintextOffset must be identical to
     * ciphertextOffset.
     *
     * There must be enough space in the ciphertext buffer to accomodate
     * length + getMACLength() bytes of data starting at ciphertextOffset.
     */
    int encryptWithAd(byte[] ad, byte[] plaintext, int plaintextOffset, byte[] ciphertext, int ciphertextOffset, int length) throws ShortBufferException;

    /**
     * Encrypts a plaintext buffer with a block of associated data, with separate offset/length for the AD.
     *
     * @param ad The associated data, or null if there is none.
     * @param adOffset The offset within the ad buffer of the first byte of associated data.
     * @param adLength The length of the associated data within the ad buffer.
     * @param plaintext The buffer containing the plaintext to encrypt.
     * @param plaintextOffset The offset within the plaintext buffer of the first byte to encrypt.
     * @param ciphertext The buffer to place the ciphertext in.  This can be
     * the same as the plaintext buffer.
     * @param ciphertextOffset The first offset within the ciphertext buffer
     * to place the ciphertext.
     * @param length The length of the plaintext.
     * @return The length of the ciphertext with the MAC tag appended.
     *
     * @throws ShortBufferException The ciphertext buffer does not have
     * enough space to store the encrypted data.
     *
     * @throws IllegalStateException The nonce has wrapped around.
     *
     * @since 0.9.54
     *
     * The plaintext and ciphertext buffers can be the same for in-place
     * encryption.  In that case, plaintextOffset must be identical to
     * ciphertextOffset.
     *
     * There must be enough space in the ciphertext buffer to accomodate
     * length + getMACLength() bytes of data starting at ciphertextOffset.
     */
    public int encryptWithAd(byte[] ad, int adOffset, int adLength, byte[] plaintext, int plaintextOffset,
                             byte[] ciphertext, int ciphertextOffset, int length) throws ShortBufferException;

    /**
     * Decrypts a ciphertext buffer using the cipher and a block of associated data.
     *
     * @param ad The associated data, or null if there is none.
     * @param ciphertext The buffer containing the ciphertext to decrypt.
     * @param ciphertextOffset The offset within the ciphertext buffer of
     * the first byte of ciphertext data.
     * @param plaintext The buffer to place the plaintext in.  This can be
     * the same as the ciphertext buffer.
     * @param plaintextOffset The first offset within the plaintext buffer
     * to place the plaintext.
     * @param length The length of the incoming ciphertext plus the MAC tag.
     * @return The length of the plaintext with the MAC tag stripped off.
     *
     * @throws ShortBufferException The plaintext buffer does not have
     * enough space to store the decrypted data.
     *
     * @throws BadPaddingException The MAC value failed to verify.
     *
     * @throws IllegalStateException The nonce has wrapped around.
     *
     * The plaintext and ciphertext buffers can be the same for in-place
     * decryption.  In that case, ciphertextOffset must be identical to
     * plaintextOffset.
     */
    int decryptWithAd(byte[] ad, byte[] ciphertext, int ciphertextOffset, byte[] plaintext, int plaintextOffset, int length) throws ShortBufferException, BadPaddingException;

    /**
     * Decrypts a ciphertext buffer using the cipher and a block of associated data,
     * with separate offset/length for the AD.
     *
     * @param ad The associated data, or null if there is none.
     * @param adOffset The offset within the ad buffer of the first byte of associated data.
     * @param adLength The length of the associated data within the ad buffer.
     * @param ciphertext The buffer containing the ciphertext to decrypt.
     * @param ciphertextOffset The offset within the ciphertext buffer of
     * the first byte of ciphertext data.
     * @param plaintext The buffer to place the plaintext in.  This can be
     * the same as the ciphertext buffer.
     * @param plaintextOffset The first offset within the plaintext buffer
     * to place the plaintext.
     * @param length The length of the incoming ciphertext plus the MAC tag.
     * @return The length of the plaintext with the MAC tag stripped off.
     *
     * @throws ShortBufferException The plaintext buffer does not have
     * enough space to store the decrypted data.
     *
     * @throws BadPaddingException The MAC value failed to verify.
     *
     * @throws IllegalStateException The nonce has wrapped around.
     *
     * @since 0.9.54
     *
     * The plaintext and ciphertext buffers can be the same for in-place
     * decryption.  In that case, ciphertextOffset must be identical to
     * plaintextOffset.
     */
    public int decryptWithAd(byte[] ad, int adOffset, int adLength, byte[] ciphertext,
                             int ciphertextOffset, byte[] plaintext, int plaintextOffset,
                             int length) throws ShortBufferException, BadPaddingException;

    /**
     * Creates a new instance of this cipher and initializes it with a key.
     *
     * @param key The buffer containing the key.
     * @param offset The offset into the key buffer of the first key byte.
     * @return A new CipherState of the same class as this one.
     */
    CipherState fork(byte[] key, int offset);

    /**
     * Sets the nonce value.
     *
     * @param nonce The new nonce value, which must be greater than or equal
     * to the current value.
     *
     * This function is intended for testing purposes only.  If the nonce
     * value goes backwards then security may be compromised.
     */
    void setNonce(long nonce);

    /**
     * Creates a clone of this cipher state.
     *
     * @return A clone of this CipherState.
     *
     * @throws CloneNotSupportedException If cloning is not supported.
     *
     * @since 0.9.44
     */
    public CipherState clone() throws CloneNotSupportedException;
}
