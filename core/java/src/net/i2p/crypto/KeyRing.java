package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * No warranty of any kind, either expressed or implied.
 */

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PublicKey;

/**
 * A backend for storing and retrieving SigningPublicKeys
 * to be used for verifying signatures.
 *
 * @since 0.9.9
 */
public interface KeyRing {

    /**
     * Get a key.
     * Throws on all errors.
     *
     * @param keyName the algorithm the key belongs to, such as ElGamal or ECDSA
     * @param scope a domain identifier, indicating router update, reseed, etc.
     * @param type the signature type the key is stored for
     * @return null if none
     * @throws GeneralSecurityException if the stored key cannot be decoded
     * @throws IOException if the backing store cannot be read
     */
    public PublicKey getKey(String keyName, String scope, SigType type) throws GeneralSecurityException, IOException;

    /**
     * Store a key.
     * Throws on all errors.
     *
     * @param keyName the algorithm the key belongs to, such as ElGamal or ECDSA
     * @param scope a domain identifier, indicating router update, reseed, etc.
     * @param key the key to persist in this scope
     * @throws GeneralSecurityException if the key cannot be encoded
     * @throws IOException if the backing store cannot be written
     */
    public void setKey(String keyName, String scope, PublicKey key) throws GeneralSecurityException, IOException;
}
