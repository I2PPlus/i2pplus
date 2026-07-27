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
     */
    public MLKEMPrivateKeyParameters(MLKEMParameters params, byte[] s, byte[] hpk, byte[] nonce, byte[] t, byte[] rho)
    {
        this(params, s, hpk, nonce, t, rho, null);
    }

    /**
     * MLKEMPrivateKeyParameters.
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
     */
    public byte[] getEncoded()
    {
        return Util.concatenate(new byte[][]{ s, t, rho, hpk, nonce });
    }

    /**
     * HPK.
     */
    public byte[] getHPK()
    {
        return Util.clone(hpk);
    }

    /**
     * Nonce.
     */
    public byte[] getNonce()
    {
        return Util.clone(nonce);
    }

    /**
     * Public key.
     */
    public byte[] getPublicKey()
    {
        return MLKEMPublicKeyParameters.getEncoded(t, rho);
    }

    /**
     * Public key parameters.
     */
    public MLKEMPublicKeyParameters getPublicKeyParameters()
    {
        return new MLKEMPublicKeyParameters(getParameters(), t, rho);
    }

    /**
     * Rho.
     */
    public byte[] getRho()
    {
        return Util.clone(rho);
    }

    /**
     * S.
     */
    public byte[] getS()
    {
        return Util.clone(s);
    }

    /**
     * T.
     */
    public byte[] getT()
    {
        return Util.clone(t);
    }

    /**
     * Seed.
     */
    public byte[] getSeed()
    {
        return Util.clone(seed);
    }
}
