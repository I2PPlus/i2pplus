package org.bouncycastle.pqc.crypto.mlkem;

import java.security.SecureRandom;
import java.util.Arrays;
import org.bouncycastle.util.Util;

/**
 * Core engine for ML-KEM (Module-Lattice Key Encapsulation Mechanism) operations.
 * Implements the cryptographic primitives for key generation, encapsulation, and decapsulation.
 */
class MLKEMEngine
{
    private SecureRandom random;
    private MLKEMIndCpa indCpa;

    /** Kyber N parameter */
    public final static int KyberN = 256;
    /** Kyber Q parameter */
    public final static int KyberQ = 3329;
    /** Kyber Q inverse parameter */
    public final static int KyberQinv = 62209;

    /** Number of bytes for Hashes and Seeds */
    public final static int KyberSymBytes = 32;
    private final static int KyberSharedSecretBytes = 32; // Number of Bytes for Shared Secret

    /** Kyber polynomial bytes */
    public final static int KyberPolyBytes = 384;

    private final static int KyberEta2 = 2;

    private final static int KyberIndCpaMsgBytes = KyberSymBytes;


    // parameters for Kyber{k}
    private final int KyberK;
    private final int KyberPolyVecBytes;
    private final int KyberPolyCompressedBytes;
    private final int KyberPolyVecCompressedBytes;
    private final int KyberEta1;
    private final int KyberIndCpaPublicKeyBytes;
    private final int KyberIndCpaSecretKeyBytes;
    private final int KyberIndCpaBytes;
    private final int KyberPublicKeyBytes;
    private final int KyberSecretKeyBytes;
    private final int KyberCipherTextBytes;

    // Crypto
    private final int CryptoBytes;
    private final int CryptoSecretKeyBytes;
    private final int CryptoPublicKeyBytes;
    private final int CryptoCipherTextBytes;

    private final int sessionKeyLength;
    private final Symmetric symmetric;

    /**
     * The symmetric primitives bound to this parameter set.
     *
     * @return the symmetric primitive set this engine was built with
     */
    public Symmetric getSymmetric()
    {
        return symmetric;
    }
    /**
     * The eta2 noise parameter.
     *
     * @return KyberEta2, the coefficient bound used when sampling messages
     */
    public static int getKyberEta2()
    {
        return KyberEta2;
    }

    /**
     * Message length in bytes that the IND-CPA scheme accepts.
     *
     * @return KyberIndCpaMsgBytes, the limit in bytes
     */
    public static int getKyberIndCpaMsgBytes()
    {
        return KyberIndCpaMsgBytes;
    }

    /**
     * Ciphertext length in bytes at the KEM layer.
     *
     * @return CryptoCipherTextBytes, the length in bytes
     */
    public int getCryptoCipherTextBytes()
    {
        return CryptoCipherTextBytes;
    }

    /**
     * Public key length in bytes at the KEM layer.
     *
     * @return CryptoPublicKeyBytes, the length in bytes
     */
    public int getCryptoPublicKeyBytes()
    {
        return CryptoPublicKeyBytes;
    }

    /**
     * Secret key length in bytes at the KEM layer.
     *
     * @return CryptoSecretKeyBytes, the length in bytes
     */
    public int getCryptoSecretKeyBytes()
    {
        return CryptoSecretKeyBytes;
    }

    /**
     * Total key material length in bytes at the KEM layer.
     *
     * @return CryptoBytes, the combined length in bytes
     */
    public int getCryptoBytes()
    {
        return CryptoBytes;
    }

    /**
     * Ciphertext length in bytes produced by the IND-CPA scheme.
     *
     * @return KyberCipherTextBytes, the length in bytes
     */
    public int getKyberCipherTextBytes()
    {
        return KyberCipherTextBytes;
    }

    /**
     * Secret key length in bytes for the IND-CPA scheme.
     *
     * @return KyberSecretKeyBytes, the length in bytes
     */
    public int getKyberSecretKeyBytes()
    {
        return KyberSecretKeyBytes;
    }

    /**
     * Public key length in bytes for the IND-CPA scheme.
     *
     * @return KyberIndCpaPublicKeyBytes, the length in bytes
     */
    public int getKyberIndCpaPublicKeyBytes()
    {
        return KyberIndCpaPublicKeyBytes;
    }

    /**
     * Secret key length in bytes for the IND-CPA scheme.
     *
     * @return KyberIndCpaSecretKeyBytes, the length in bytes
     */
    public int getKyberIndCpaSecretKeyBytes()
    {
        return KyberIndCpaSecretKeyBytes;
    }

    /**
     * Seed length in bytes for the IND-CPA scheme.
     *
     * @return KyberIndCpaBytes, the length in bytes
     */
    public int getKyberIndCpaBytes()
    {
        return KyberIndCpaBytes;
    }

    /**
     * Public key length in bytes before the KEM layer wraps it.
     *
     * @return KyberPublicKeyBytes, the length in bytes
     */
    public int getKyberPublicKeyBytes()
    {
        return KyberPublicKeyBytes;
    }

    /**
     * Size in bytes of one compressed polynomial.
     *
     * @return KyberPolyCompressedBytes, the length in bytes
     */
    public int getKyberPolyCompressedBytes()
    {
        return KyberPolyCompressedBytes;
    }

    /**
     * The security parameter: 2, 3 or 4.
     *
     * @return KyberK, the level: 2, 3 or 4
     */
    public int getKyberK()
    {
        return KyberK;
    }

    /**
     * Size in bytes of one uncompressed polynomial vector.
     *
     * @return KyberPolyVecBytes, the length in bytes
     */
    public int getKyberPolyVecBytes()
    {
        return KyberPolyVecBytes;
    }

    /**
     * Size in bytes of one compressed polynomial vector.
     *
     * @return KyberPolyVecCompressedBytes, the length in bytes
     */
    public int getKyberPolyVecCompressedBytes()
    {
        return KyberPolyVecCompressedBytes;
    }

    /**
     * The eta1 noise parameter.
     *
     * @return KyberEta1, the coefficient bound used when sampling ciphertext
     */
    public int getKyberEta1()
    {
        return KyberEta1;
    }

    /**
     * Build the parameter set for the given security level.
     *
     * @param k the security parameter: 2, 3 or 4
     */
    public MLKEMEngine(int k)
    {
        this.KyberK = k;
        switch (k)
        {
        case 2:
            KyberEta1 = 3;
            KyberPolyCompressedBytes = 128;
            KyberPolyVecCompressedBytes = k * 320;
            sessionKeyLength = 32;
            break;
        case 3:
            KyberEta1 = 2;
            KyberPolyCompressedBytes = 128;
            KyberPolyVecCompressedBytes = k * 320;
            sessionKeyLength = 32;
            break;
        case 4:
            KyberEta1 = 2;
            KyberPolyCompressedBytes = 160;
            KyberPolyVecCompressedBytes = k * 352;
            sessionKeyLength = 32;
            break;
        default:
            throw new IllegalArgumentException("K: " + k + " is not supported for Crystals Kyber");
        }

        this.KyberPolyVecBytes = k * KyberPolyBytes;
        this.KyberIndCpaPublicKeyBytes = KyberPolyVecBytes + KyberSymBytes;
        this.KyberIndCpaSecretKeyBytes = KyberPolyVecBytes;
        this.KyberIndCpaBytes = KyberPolyVecCompressedBytes + KyberPolyCompressedBytes;
        this.KyberPublicKeyBytes = KyberIndCpaPublicKeyBytes;
        this.KyberSecretKeyBytes = KyberIndCpaSecretKeyBytes + KyberIndCpaPublicKeyBytes + 2 * KyberSymBytes;
        this.KyberCipherTextBytes = KyberIndCpaBytes;

        // Define Crypto Params
        this.CryptoBytes = KyberSharedSecretBytes;
        this.CryptoSecretKeyBytes = KyberSecretKeyBytes;
        this.CryptoPublicKeyBytes = KyberPublicKeyBytes;
        this.CryptoCipherTextBytes = KyberCipherTextBytes;

        this.symmetric = new Symmetric.ShakeSymmetric();

        this.indCpa = new MLKEMIndCpa(this);
    }

    /**
     * Bind the entropy source used for key generation and encapsulation.
     *
     * @param random the source to draw randomness from
     */
    public void init(SecureRandom random)
    {
        this.random = random;
    }

    /**
     * Generate a KEM key pair, drawing both seeds from the bound entropy source.
     *
     * @return the key pair components, laid out as generateKemKeyPairInternal does
     */
    public byte[][] generateKemKeyPair()
    {
        byte[] d = new byte[KyberSymBytes];
        byte[] z = new byte[KyberSymBytes];
        random.nextBytes(d);
        random.nextBytes(z);

        return generateKemKeyPairInternal(d, z);
    }

    /**
     * Expand the two seeds into the full ML-KEM key pair.
     *
     * @param d the seed for the internal IND-CPA key pair
     * @param z the seed kept for implicit rejection
     * @return the key pair: encapsulated public key, hashed public key, secret
     *         key, hashed public key, z, and the concatenated seeds
     */
    public byte[][] generateKemKeyPairInternal(byte[] d, byte[] z)
    {
        byte[][] indCpaKeyPair = indCpa.generateKeyPair(d);

        byte[] s = new byte[KyberIndCpaSecretKeyBytes];

        System.arraycopy(indCpaKeyPair[1], 0, s, 0, KyberIndCpaSecretKeyBytes);

        byte[] hashedPublicKey = new byte[32];

        symmetric.hash_h(hashedPublicKey, indCpaKeyPair[0], 0);

        byte[] outputPublicKey = new byte[KyberIndCpaPublicKeyBytes];
        System.arraycopy(indCpaKeyPair[0], 0, outputPublicKey, 0, KyberIndCpaPublicKeyBytes);
        return new byte[][]
        {
            Arrays.copyOfRange(outputPublicKey, 0, outputPublicKey.length - 32),
            Arrays.copyOfRange(outputPublicKey, outputPublicKey.length - 32, outputPublicKey.length),
            s,
            hashedPublicKey,
            z,
            Util.concatenate(d, z)
        };
    }

    /**
     * Encapsulate a shared secret against the recipient's public key.
     *
     * @param publicKeyInput the recipient's encapsulated public key
     * @param randBytes the 32 bytes of randomness the message hiding samples need
     * @return the ciphertext and the shared secret
     */
    public byte[][] kemEncryptInternal(byte[] publicKeyInput, byte[] randBytes)
    {
        byte[] outputCipherText;

        byte[] buf = new byte[2 * KyberSymBytes];
        byte[] kr = new byte[2 * KyberSymBytes];

        System.arraycopy(randBytes, 0, buf, 0, KyberSymBytes);

        // SHA3-256 Public Key
        symmetric.hash_h(buf, publicKeyInput, KyberSymBytes);

        // SHA3-512( SHA3-256(RandBytes) || SHA3-256(PublicKey) )
        symmetric.hash_g(kr, buf);

        // IndCpa Encryption
        outputCipherText = indCpa.encrypt(publicKeyInput, Arrays.copyOfRange(buf, 0, KyberSymBytes), Arrays.copyOfRange(kr, 32, kr.length));

        byte[] outputSharedSecret = new byte[sessionKeyLength];

        System.arraycopy(kr, 0, outputSharedSecret, 0, outputSharedSecret.length);

        byte[][] outBuf = new byte[2][];
        outBuf[0] = outputSharedSecret;
        outBuf[1] = outputCipherText;
        return outBuf;
    }

    /**
     * Recover the shared secret from a ciphertext.
     *
     * @param secretKey the recipient's secret key
     * @param cipherText the ciphertext to decapsulate
     * @return the shared secret
     */
    public byte[] kemDecryptInternal(byte[] secretKey, byte[] cipherText)
    {
        byte[] buf = new byte[2 * KyberSymBytes],
                kr = new byte[2 * KyberSymBytes];

        byte[] publicKey = Arrays.copyOfRange(secretKey, KyberIndCpaSecretKeyBytes, secretKey.length);

        System.arraycopy(indCpa.decrypt(secretKey, cipherText), 0, buf, 0, KyberSymBytes);

        System.arraycopy(secretKey, KyberSecretKeyBytes - 2 * KyberSymBytes, buf, KyberSymBytes, KyberSymBytes);

        symmetric.hash_g(kr, buf);

        byte[] implicit_rejection = new byte[KyberSymBytes + KyberCipherTextBytes];

        System.arraycopy(secretKey, KyberSecretKeyBytes - KyberSymBytes, implicit_rejection, 0, KyberSymBytes);

        System.arraycopy(cipherText, 0, implicit_rejection, KyberSymBytes, KyberCipherTextBytes);

        symmetric.kdf(implicit_rejection, implicit_rejection ); // J(z||c)

        byte[] cmp = indCpa.encrypt(publicKey, Arrays.copyOfRange(buf, 0, KyberSymBytes), Arrays.copyOfRange(kr, KyberSymBytes, kr.length));

        boolean fail = !(Util.constantTimeAreEqual(cipherText, cmp));

        cmov(kr, implicit_rejection, KyberSymBytes, fail);

        return Arrays.copyOfRange(kr, 0, sessionKeyLength);
    }

    /**
     * Encapsulate a shared secret, drawing the randomness from this engine.
     *
     * @param publicKeyInput the recipient's encapsulated public key
     * @param randBytes the 32 bytes of randomness to encapsulate with
     * @return the ciphertext and the shared secret
     */
    public byte[][] kemEncrypt(byte[] publicKeyInput, byte[] randBytes)
    {
        //TODO: do input validation elsewhere?
        // Input validation (6.2 ML-KEM Encaps)
        // Type Check
        if (publicKeyInput.length != KyberIndCpaPublicKeyBytes)
        {
            throw new IllegalArgumentException("Input validation Error: Type check failed for ml-kem encapsulation");
        }
        // Modulus Check
        PolyVec polyVec = new PolyVec(this);
        byte[] seed = indCpa.unpackPublicKey(polyVec, publicKeyInput);
        byte[] ek = indCpa.packPublicKey(polyVec, seed);
        if (!Arrays.equals(ek, publicKeyInput))
        {
            throw new IllegalArgumentException("Input validation: Modulus check failed for ml-kem encapsulation");
        }

        return kemEncryptInternal(publicKeyInput, randBytes);
    }
    /**
     * Recover the shared secret from a ciphertext.
     *
     * @param secretKey the recipient's secret key
     * @param cipherText the ciphertext to decapsulate
     * @return the shared secret
     */
    public byte[] kemDecrypt(byte[] secretKey, byte[] cipherText)
    {
        //TODO: do input validation
        return kemDecryptInternal(secretKey, cipherText);
    }

    private void cmov(byte[] r, byte[] x, int xlen, boolean b)
    {
        if (b)
        {
            System.arraycopy(x, 0, r, 0, xlen);
        }
        else
        {
            System.arraycopy(r, 0, r, 0, xlen);
        }
    }

    /**
     * Fill a buffer with bytes from the bound entropy source.
     *
     * @param buf the buffer to fill, sized by the caller
     */
    public void getRandomBytes(byte[] buf)
    {
        this.random.nextBytes(buf);
    }
}
