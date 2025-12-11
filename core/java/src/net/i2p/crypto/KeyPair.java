package net.i2p.crypto;

import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;

/**
 * A pair of I2P cryptographic keys consisting of a public key and corresponding private key.
 * 
 * This class provides a type-safe container for I2P key pairs, similar to
 * {@link java.security.KeyPair} but specifically designed for I2P's cryptographic
 * operations including encryption, decryption, and digital signatures within the I2P network.
 * 
 * <p>The public key is used for encryption and signature verification,
 * while the private key is used for decryption and signing operations.</p>
 *
 * @since 0.9.38
 * @author I2P Project
 */
public class KeyPair {

    private final PublicKey pub;
    private final PrivateKey priv;

    /**
     * @param publicKey non-null, same EncType as privateKey
     * @param privateKey non-null, same EncType as publicKey
     */
    public KeyPair(PublicKey publicKey, PrivateKey privateKey) {
        pub = publicKey;
        priv = privateKey;
        if (pub.getType() != priv.getType())
            throw new IllegalArgumentException();
    }

    public PublicKey getPublic() {
        return pub;
    }

    public PrivateKey getPrivate() {
        return priv;
    }
}
