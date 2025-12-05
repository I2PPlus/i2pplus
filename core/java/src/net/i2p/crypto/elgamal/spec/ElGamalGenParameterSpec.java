/**
 * Parameter specification for ElGamal key generation.
 * 
 * This class specifies the parameters used when generating ElGamal key pairs,
 * particularly the size of the prime modulus in bits. The prime size determines
 * the security level of the generated keys, with larger sizes providing stronger
 * cryptographic security at the cost of computational performance.
 */
package net.i2p.crypto.elgamal.spec;

import java.security.spec.AlgorithmParameterSpec;

public class ElGamalGenParameterSpec
    implements AlgorithmParameterSpec
{
    private final int primeSize;

    /*
     * @param primeSize the size (in bits) of the prime modulus.
     */
    public ElGamalGenParameterSpec(
        int     primeSize)
    {
        this.primeSize = primeSize;
    }

    /**
     * Returns the size in bits of the prime modulus.
     *
     * @return the size in bits of the prime modulus
     */
    public int getPrimeSize()
    {
        return primeSize;
    }
}
