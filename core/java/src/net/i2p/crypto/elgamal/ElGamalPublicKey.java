package net.i2p.crypto.elgamal;

import java.math.BigInteger;
import javax.crypto.interfaces.DHPublicKey;

/**
 * Interface for ElGamal public keys.
 *
 * This interface defines the contract for ElGamal public keys, extending both
 * the ElGamalKey interface and the standard DHPublicKey interface. ElGamal
 * public keys contain the public value y = g^x mod p used for encryption operations
 * in the ElGamal public-key cryptosystem.
 */
public interface ElGamalPublicKey extends ElGamalKey, DHPublicKey {
    public BigInteger getY();
}
