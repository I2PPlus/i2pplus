package net.i2p.crypto.elgamal;

import javax.crypto.interfaces.DHKey;
import net.i2p.crypto.elgamal.spec.ElGamalParameterSpec;

/**
 * Base interface for ElGamal cryptographic keys used in I2P.
 * 
 * ElGamal is a public-key cryptosystem used in I2P for encrypting
 * session keys and messages. This interface defines the common contract for
 * both ElGamal public and private keys, extending the standard DHKey interface
 * for compatibility with Java's cryptographic architecture.
 * 
 * <p>Implementations include {@link ElGamalPublicKey} and {@link ElGamalPrivateKey}
 * which provide the actual cryptographic operations for encryption and decryption.</p>
 *
 * @since 0.9.25
 * @author I2P Project
 * @see <a href="https://en.wikipedia.org/wiki/ElGamal_encryption">ElGamal Encryption</a>
 */
public interface ElGamalKey
    extends DHKey
{
    public ElGamalParameterSpec getParameters();
}
