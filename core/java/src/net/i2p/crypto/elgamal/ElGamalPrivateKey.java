package net.i2p.crypto.elgamal;

import java.math.BigInteger;

import javax.crypto.interfaces.DHPrivateKey;

/**
 * Interface for ElGamal private keys.
 *
 * This interface defines the contract for ElGamal private keys, extending both
 * the ElGamalKey interface and the standard DHPrivateKey interface. ElGamal
 * private keys contain the secret exponent x used for decryption operations
 * in the ElGamal public-key cryptosystem.
 */
public interface ElGamalPrivateKey extends ElGamalKey, DHPrivateKey {
    public BigInteger getX();
}
