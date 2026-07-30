package com.southernstorm.noise.protocol;

/**
 * Interface to a Diffie-Hellman algorithm for the Noise protocol.
 */
public interface DHState extends Destroyable, Cloneable {

    /**
     * Gets the Noise protocol name for this Diffie-Hellman algorithm.
     *
     * @return The algorithm name.
     */
    String getDHName();

    /**
     * Gets the length of public keys for this algorithm.
     *
     * @return The length of public keys in bytes.
     */
    int getPublicKeyLength();

    /**
     * Gets the length of private keys for this algorithm.
     *
     * @return The length of private keys in bytes.
     */
    int getPrivateKeyLength();

    /**
     * Gets the length of shared keys for this algorithm.
     *
     * @return The length of shared keys in bytes.
     */
    int getSharedKeyLength();

    /**
     * Generates a new random keypair.
     */
    void generateKeyPair();

    /**
     * Gets the public key associated with this object.
     *
     * @param key The buffer to copy the public key to.
     * @param offset The first offset in the key buffer to copy to.
     */
    void getPublicKey(byte[] key, int offset);

    /**
     * Sets the public key for this object.
     *
     * @param key The buffer containing the public key.
     * @param offset The first offset in the buffer that contains the key.
     *
     * If this object previously held a key pair, then this function
     * will change it into a public key only object.
     */
    void setPublicKey(byte[] key, int offset);

    /**
     * Gets the private key associated with this object.
     *
     * @param key The buffer to copy the private key to.
     * @param offset The first offset in the key buffer to copy to.
     */
    void getPrivateKey(byte[] key, int offset);

    /**
     * Sets the private key for this object.
     *
     * @param key The buffer containing the [rivate key.
     * @param offset The first offset in the buffer that contains the key.
     *
     * If this object previously held only a public key, then
     * this function will change it into a key pair.
     *
     * @deprecated use setKeys()
     */
    @Deprecated
    void setPrivateKey(byte[] key, int offset);

    /**
     * Sets the private and public keys for this object.
     * I2P for efficiency, since setPrivateKey() calculates the public key
     * and overwrites it.
     * Does NOT check that the two keys match.
     *
     * @param privkey The buffer containing the private key.
     * @param privoffset The first offset in the buffer that contains the key.
     * @param pubkey The buffer containing the private key.
     * @param puboffset The first offset in the buffer that contains the key.
     * @since 0.9.48
     */
    void setKeys(byte[] privkey, int privoffset, byte[] pubkey, int puboffset);

    /**
     * Sets this object to the null public key and clears the private key.
     */
    void setToNullPublicKey();

    /**
     * Clears the key pair.
     */
    void clearKey();

    /**
     * Determine if this object contains a public key.
     *
     * @return Returns true if this object contains a public key,
     * or false if the public key has not yet been set.
     */
    boolean hasPublicKey();

    /**
     * Determine if this object contains a private key.
     *
     * @return Returns true if this object contains a private key,
     * or false if the private key has not yet been set.
     */
    boolean hasPrivateKey();

    /**
     * Determine if the public key in this object is the special null value.
     *
     * @return Returns true if the public key is the special null value,
     * or false otherwise.
     */
    boolean isNullPublicKey();

    /**
     * Determine if this object contains an optional encoded public key.
     *
     * @return Returns true if this object contains an encoded public key,
     * or false if the public key has not yet been set.
     *
     * @since 0.9.44
     */
    boolean hasEncodedPublicKey();

    /**
     * Gets the public key associated with this object.
     *
     * @param key The buffer to copy the public key to.
     * @param offset The first offset in the key buffer to copy to.
     *
     * @since 0.9.44
     */
    void getEncodedPublicKey(byte[] key, int offset);

    /**
     * Performs a Diffie-Hellman calculation with this object as the private key.
     *
     * @param sharedKey Buffer to put the shared key into.
     * @param offset Offset of the first byte for the shared key.
     * @param publicDH Object that contains the public key for the calculation.
     *
     * @throws IllegalArgumentException The publicDH object is not the same
     * type as this object, or one of the objects does not contain a valid key.
     */
    void calculate(byte[] sharedKey, int offset, DHState publicDH);

    /**
     * Copies the key values from another DH object of the same type.
     *
     * @param other The other DH object to copy from
     *
     * @throws IllegalStateException The other DH object does not have
     * the same type as this object.
     */
    void copyFrom(DHState other);

    /**
     *  I2P
     *  @since 0.9.44
     */
    public DHState clone() throws CloneNotSupportedException;
}
