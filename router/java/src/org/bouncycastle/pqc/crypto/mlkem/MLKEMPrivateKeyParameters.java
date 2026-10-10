package org.bouncycastle.pqc.crypto.mlkem;

import java.util.Arrays;
import org.bouncycastle.util.Util;

/**
 * Private key parameters for ML-KEM (Module-Lattice Key Encapsulation Mechanism).
 * Contains the private key components including seed, secret, and related values.
 */
public class MLKEMPrivateKeyParameters
    extends MLKEMKeyParameters
{
    /** Secret vector s. */
    final byte[] s;
    /** Hash of public key. */
    final byte[] hpk;
    /** Nonce value. */
    final byte[] nonce;
    /** Public key t component. */
    final byte[] t;
    /** Public key rho component. */
    final byte[] rho;
    /** Optional seed for deterministic key gen. */
    final byte[] seed;

    /**
     * MLKEMPrivateKeyParameters.
     *
     * @param params the ML-KEM parameter set this key belongs to
     * @param s the secret vector
     * @param hpk the hash of the corresponding public key
     * @param nonce the 32-byte nonce carried alongside the key material
     * @param t the public key t component
     * @param rho the public key rho component
     */
    public MLKEMPrivateKeyParameters(MLKEMParameters params, byte[] s, byte[] hpk, byte[] nonce, byte[] t, byte[] rho)
    {
        this(params, s, hpk, nonce, t, rho, null);
    }

    /**
     * MLKEMPrivateKeyParameters.
     *
     * @param params the ML-KEM parameter set this key belongs to
     * @param s the secret vector
     * @param hpk the hash of the corresponding public key
     * @param nonce the 32-byte nonce carried alongside the key material
     * @param t the public key t component
     * @param rho the public key rho component
     * @param seed the optional seed permitting deterministic regeneration of the key, or null when the key was not generated from one
     */
    public MLKEMPrivateKeyParameters(MLKEMParameters params, byte[] s, byte[] hpk, byte[] nonce, byte[] t, byte[] rho, byte[] seed)
    {
        super(true, params);

        this.s = Util.clone(s);
        this.hpk = Util.clone(hpk);
        this.nonce = Util.clone(nonce);
        this.t = Util.clone(t);
        this.rho = Util.clone(rho);
        this.seed = seed != null ? Util.clone(seed) : null;
    }

    /**
     * MLKEMPrivateKeyParameters.
     *
     * @param params the ML-KEM parameter set this key belongs to
     * @param encoding the private key in either the seed-based or the expanded packed form
     */
    public MLKEMPrivateKeyParameters(MLKEMParameters params, byte[] encoding)
    {
        super(true, params);

        MLKEMEngine eng = params.getEngine();
        if (encoding.length == MLKEMEngine.KyberSymBytes * 2)
        {
            byte[][] keyData = eng.generateKemKeyPairInternal(
                Arrays.copyOfRange(encoding, 0, MLKEMEngine.KyberSymBytes),
                Arrays.copyOfRange(encoding, MLKEMEngine.KyberSymBytes, encoding.length));
            this.s = keyData[2];
            this.hpk = keyData[3];
            this.nonce = keyData[4];
            this.t = keyData[0];
            this.rho = keyData[1];
            this.seed = keyData[5];
        }
        else
        {
            int index = 0;
            this.s = Arrays.copyOfRange(encoding, 0, eng.getKyberIndCpaSecretKeyBytes());
            index += eng.getKyberIndCpaSecretKeyBytes();
            this.t = Arrays.copyOfRange(encoding, index, index + eng.getKyberIndCpaPublicKeyBytes() - MLKEMEngine.KyberSymBytes);
            index += eng.getKyberIndCpaPublicKeyBytes() - MLKEMEngine.KyberSymBytes;
            this.rho = Arrays.copyOfRange(encoding, index, index + 32);
            index += 32;
            this.hpk = Arrays.copyOfRange(encoding, index, index + 32);
            index += 32;
            this.nonce = Arrays.copyOfRange(encoding, index, index + MLKEMEngine.KyberSymBytes);
            this.seed = null;
        }
    }

    /**
     * Encoded.
     *
     * @return the concatenation of s, t, rho, hpk and nonce, the expanded form
     *     accepted by the two-argument constructor
     */
    public byte[] getEncoded()
    {
        return Util.concatenate(new byte[][]{ s, t, rho, hpk, nonce });
    }

    /**
     * HPK.
     *
     * @return a copy of the hash of the corresponding public key
     */
    public byte[] getHPK()
    {
        return Util.clone(hpk);
    }

    /**
     * Nonce.
     *
     * @return a copy of the nonce value
     */
    public byte[] getNonce()
    {
        return Util.clone(nonce);
    }

    /**
     * Public key.
     *
     * @return the encoded public key matching this private key
     */
    public byte[] getPublicKey()
    {
        return MLKEMPublicKeyParameters.getEncoded(t, rho);
    }

    /**
     * Public key parameters.
     *
     * @return the public key parameters matching this private key
     */
    public MLKEMPublicKeyParameters getPublicKeyParameters()
    {
        return new MLKEMPublicKeyParameters(getParameters(), t, rho);
    }

    /**
     * Rho.
     *
     * @return a copy of the public key rho component
     */
    public byte[] getRho()
    {
        return Util.clone(rho);
    }

    /**
     * S.
     *
     * @return a copy of the secret vector
     */
    public byte[] getS()
    {
        return Util.clone(s);
    }

    /**
     * T.
     *
     * @return a copy of the public key t component
     */
    public byte[] getT()
    {
        return Util.clone(t);
    }

    /**
     * Seed.
     *
     * @return a copy of the deterministic key generation seed, or null when
     *     this key was not generated from one
     */
    public byte[] getSeed()
    {
        return Util.clone(seed);
    }
}
