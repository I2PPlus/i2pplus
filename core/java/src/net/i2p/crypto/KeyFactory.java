package net.i2p.crypto;

/**
 * Factory interface for generating I2P cryptographic key pairs.
 * 
 * Implementations of this interface provide methods to create {@link KeyPair} objects
 * containing public and private keys for various cryptographic algorithms supported by I2P.
 * 
 * <p>This abstraction allows for different key generation strategies while maintaining
 * a consistent API across the I2P cryptographic framework.</p>
 *
 * @since 0.9.44
 * @author I2P Project
 */
public interface KeyFactory {

    public KeyPair getKeys();

}
