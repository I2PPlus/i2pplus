package org.bouncycastle.pqc.crypto.mlkem;

import java.util.Arrays;
import org.bouncycastle.util.Util;

/**
 * Public key parameters for ML-KEM (Module-Lattice Key Encapsulation Mechanism).
 * Contains public key components including polynomial coefficients for cryptographic operations.
 */
public class MLKEMPublicKeyParameters
    extends MLKEMKeyParameters
{
    /**
     * Concatenate t and rho into a single encoding.
     *
     * @param t the polynomial coefficients
     * @param rho the random seed
     * @return the concatenated encoding
     */
    static byte[] getEncoded(byte[] t, byte[] rho)
    {
        return Util.concatenate(t, rho);
    }

    /** Polynomial coefficients */
    final byte[] t;
    /** Random seed */
    final byte[] rho;

    /**
     * Create public key parameters from individual components.
     *
     * @param params the ML-KEM parameters
     * @param t the polynomial coefficients
     * @param rho the random seed
     */
    public MLKEMPublicKeyParameters(MLKEMParameters params, byte[] t, byte[] rho)
    {
        super(false, params);
        this.t = Util.clone(t);
        this.rho = Util.clone(rho);
    }

    /**
     * Create public key parameters from an encoded byte array.
     *
     * @param params the ML-KEM parameters
     * @param encoding the encoded public key
     */
    public MLKEMPublicKeyParameters(MLKEMParameters params, byte[] encoding)
    {
        super(false, params);
        this.t = Arrays.copyOfRange(encoding, 0, encoding.length - MLKEMEngine.KyberSymBytes);
        this.rho = Arrays.copyOfRange(encoding, encoding.length - MLKEMEngine.KyberSymBytes, encoding.length);
    }

    /**
     * Get the encoded public key.
     *
     * @return the encoded public key
     */
    public byte[] getEncoded()
    {
        return getEncoded(t, rho);
    }

    /**
     * Get the random seed.
     *
     * @return the random seed
     */
    public byte[] getRho()
    {
        return Util.clone(rho);
    }

    /**
     * Get the polynomial coefficients.
     *
     * @return the polynomial coefficients
     */
    public byte[] getT()
    {
        return Util.clone(t);
    }
}
