package org.bouncycastle.pqc.crypto.mlkem;

import java.util.Arrays;
import org.bouncycastle.util.Util;

/**
 * Indistinguishability under Chosen Plaintext Attack (IND-CPA) security for ML-KEM.
 * Provides key generation operations with IND-CPA security guarantees.
 */
class MLKEMIndCpa
{
    private MLKEMEngine engine;
    private int kyberK;
    private int indCpaPublicKeyBytes;
    private int polyVecBytes;
    private int indCpaBytes;
    private int polyVecCompressedBytes;
    private int polyCompressedBytes;

    private Symmetric symmetric;

    /**
     * MLKEMIndCpa.
     */
    public MLKEMIndCpa(MLKEMEngine engine)
    {
        this.engine = engine;
        this.kyberK = engine.getKyberK();
        this.indCpaPublicKeyBytes = engine.getKyberPublicKeyBytes();
        this.polyVecBytes = engine.getKyberPolyVecBytes();
        this.indCpaBytes = engine.getKyberIndCpaBytes();
        this.polyVecCompressedBytes = engine.getKyberPolyVecCompressedBytes();
        this.polyCompressedBytes = engine.getKyberPolyCompressedBytes();
        this.symmetric = engine.getSymmetric();

        KyberGenerateMatrixNBlocks =
            (
                (
                    12 * MLKEMEngine.KyberN
                        / 8 * (1 << 12)
                        / MLKEMEngine.KyberQ + symmetric.xofBlockBytes
                )
                    / symmetric.xofBlockBytes
            );
    }

    /**
     * Generates IndCpa Key Pair
     *
     * @return KeyPair where each key is represented as bytes
     */
    byte[][] generateKeyPair(byte[] d)
    {
        PolyVec secretKey = new PolyVec(engine),
            publicKey = new PolyVec(engine),
            e = new PolyVec(engine);

        // (p, sigma) <- G(d || k)

        byte[] buf = new byte[64];
        symmetric.hash_g(buf, Util.append(d, (byte)kyberK));

        byte[] publicSeed = new byte[32]; // p in docs
        byte[] noiseSeed = new byte[32]; // sigma in docs
        System.arraycopy(buf, 0, publicSeed, 0, 32);
        System.arraycopy(buf, 32, noiseSeed, 0, 32);

        byte count = (byte)0;

        // Helper.printByteArray(buf);

        PolyVec[] aMatrix = new PolyVec[kyberK];

        int i;
        for (i = 0; i < kyberK; i++)
        {
            aMatrix[i] = new PolyVec(engine);
        }

        generateMatrix(aMatrix, publicSeed, false);

        for (i = 0; i < kyberK; i++)
        {
            secretKey.getVectorIndex(i).getEta1Noise(noiseSeed, count);

            // System.out.print("SecretKeyPolyVec["+i+"] = [");
            // for (int j =0; j < KyberEngine.KyberN; j++) {
            //     System.out.print(secretKey.getVectorIndex(i).getCoeffIndex(j) + ", ");
            // }
            // System.out.println("]");
            count = (byte)(count + (byte)1);
        }

        for (i = 0; i < kyberK; i++)
        {
            e.getVectorIndex(i).getEta1Noise(noiseSeed, count);
            count = (byte)(count + (byte)1);
        }

        secretKey.polyVecNtt();

        e.polyVecNtt();

        for (i = 0; i < kyberK; i++)
        {
            PolyVec.pointwiseAccountMontgomery(publicKey.getVectorIndex(i), aMatrix[i], secretKey, engine);
            publicKey.getVectorIndex(i).convertToMont();
        }

        //    System.out.print("PublicKey PolyVec = [");
        //    Helper.printPolyVec(publicKey, kyberK);

        publicKey.addPoly(e);
        publicKey.reducePoly();

        return new byte[][]{packPublicKey(publicKey, publicSeed), packSecretKey(secretKey)};
    }

    /**
     * encrypt.
     */
    public byte[] encrypt(byte[] publicKeyInput, byte[] msg, byte[] coins)
    {
        int i;
        byte[] seed;
        byte nonce = (byte)0;
        PolyVec sp = new PolyVec(engine),
            publicKeyPolyVec = new PolyVec(engine),
            errorPolyVector = new PolyVec(engine),
            bp = new PolyVec(engine);
        PolyVec[] aMatrixTranspose = new PolyVec[engine.getKyberK()];
        Poly errorPoly = new Poly(engine),
            v = new Poly(engine),
            k = new Poly(engine);

        seed = unpackPublicKey(publicKeyPolyVec, publicKeyInput);

        k.fromMsg(msg);

        for (i = 0; i < kyberK; i++)
        {
            aMatrixTranspose[i] = new PolyVec(engine);
        }

        generateMatrix(aMatrixTranspose, seed, true);

        for (i = 0; i < kyberK; i++)
        {
            sp.getVectorIndex(i).getEta1Noise(coins, nonce);
            nonce = (byte)(nonce + (byte)1);
        }

        for (i = 0; i < kyberK; i++)
        {
            errorPolyVector.getVectorIndex(i).getEta2Noise(coins, nonce);
            nonce = (byte)(nonce + (byte)1);
        }
        errorPoly.getEta2Noise(coins, nonce);

        sp.polyVecNtt();

        for (i = 0; i < kyberK; i++)
        {

            PolyVec.pointwiseAccountMontgomery(bp.getVectorIndex(i), aMatrixTranspose[i], sp, engine);
        }
        // System.out.print("bp = [");
        // for (i = 0; i < kyberK; i++) {
        //     Helper.printShortArray(bp.getVectorIndex(i).getCoeffs());
        //     System.out.print("], \n");
        // }
        // System.out.println("]");

        PolyVec.pointwiseAccountMontgomery(v, publicKeyPolyVec, sp, engine);

        bp.polyVecInverseNttToMont();

        v.polyInverseNttToMont();

        bp.addPoly(errorPolyVector);

        v.addCoeffs(errorPoly);
        v.addCoeffs(k);

        bp.reducePoly();
        v.reduce();

        //         System.out.print("bp = [");
        // for (i = 0; i < kyberK; i++) {
        //     Helper.printShortArray(bp.getVectorIndex(i).getCoeffs());
        //     System.out.print("], \n");
        // }
        // System.out.println("]");

        // System.out.print("v = ");
        // Helper.printShortArray(v.getCoeffs());
        // System.out.println();

        byte[] outputCipherText = packCipherText(bp, v);

        return outputCipherText;
    }

    private byte[] packCipherText(PolyVec b, Poly v)
    {
        byte[] outBuf = new byte[indCpaBytes];
        System.arraycopy(b.compressPolyVec(), 0, outBuf, 0, polyVecCompressedBytes);
        System.arraycopy(v.compressPoly(), 0, outBuf, polyVecCompressedBytes, polyCompressedBytes);
        // System.out.print("outBuf = [");
        // Helper.printByteArray(outBuf);
        return outBuf;
    }

    private void unpackCipherText(PolyVec b, Poly v, byte[] cipherText)
    {
        byte[] compressedPolyVecCipherText = Arrays.copyOfRange(cipherText, 0, engine.getKyberPolyVecCompressedBytes());
        b.decompressPolyVec(compressedPolyVecCipherText);

        byte[] compressedPolyCipherText = Arrays.copyOfRange(cipherText, engine.getKyberPolyVecCompressedBytes(), cipherText.length);
        v.decompressPoly(compressedPolyCipherText);
    }

    /**
     * packPublicKey.
     */
    public byte[] packPublicKey(PolyVec publicKeyPolyVec, byte[] seed)
    {
        byte[] buf = new byte[indCpaPublicKeyBytes];
        System.arraycopy(publicKeyPolyVec.toBytes(), 0, buf, 0, polyVecBytes);
        System.arraycopy(seed, 0, buf, polyVecBytes, MLKEMEngine.KyberSymBytes);
        return buf;
    }

    /**
     * unpackPublicKey.
     */
    public byte[] unpackPublicKey(PolyVec publicKeyPolyVec, byte[] publicKey)
    {
        byte[] outputSeed = new byte[MLKEMEngine.KyberSymBytes];
        publicKeyPolyVec.fromBytes(publicKey);
        System.arraycopy(publicKey, polyVecBytes, outputSeed, 0, MLKEMEngine.KyberSymBytes);
        return outputSeed;
    }

    /**
     * packSecretKey.
     */
    public byte[] packSecretKey(PolyVec secretKeyPolyVec)
    {
        return secretKeyPolyVec.toBytes();
    }

    /**
     * unpackSecretKey.
     */
    public void unpackSecretKey(PolyVec secretKeyPolyVec, byte[] secretKey)
    {
        secretKeyPolyVec.fromBytes(secretKey);
    }

    /**
     * KyberGenerateMatrixNBlocks.
     */
    public final int KyberGenerateMatrixNBlocks;

    /**
     * generateMatrix.
     */
    public void generateMatrix(PolyVec[] aMatrix, byte[] seed, boolean transposed)
    {
        int i, j, k, ctr, off;
        byte[] buf = new byte[KyberGenerateMatrixNBlocks * symmetric.xofBlockBytes + 2];
        for (i = 0; i < kyberK; i++)
        {
            for (j = 0; j < kyberK; j++)
            {
                if (transposed)
                {
                    symmetric.xofAbsorb(seed, (byte) i, (byte) j);
                }
                else
                {
                    symmetric.xofAbsorb(seed, (byte) j, (byte) i);
                }
                symmetric.xofSqueezeBlocks(buf, 0, symmetric.xofBlockBytes * KyberGenerateMatrixNBlocks);

                int buflen = KyberGenerateMatrixNBlocks * symmetric.xofBlockBytes;
                ctr = rejectionSampling(aMatrix[i].getVectorIndex(j), 0, MLKEMEngine.KyberN, buf, buflen);

                while (ctr < MLKEMEngine.KyberN)
                {
                    off = buflen % 3;
                    for (k = 0; k < off; k++)
                    {
                        buf[k] = buf[buflen - off + k];
                    }
                    symmetric.xofSqueezeBlocks(buf, off, symmetric.xofBlockBytes * 2);
                    buflen = off + symmetric.xofBlockBytes;
                    // Error in code Section Unsure
                    ctr += rejectionSampling(aMatrix[i].getVectorIndex(j), ctr, MLKEMEngine.KyberN - ctr, buf, buflen);
                }
            }
        }

    }

    private static int rejectionSampling(Poly outputBuffer, int coeffOff, int len, byte[] inpBuf, int inpBufLen)
    {
        int ctr, pos;
        short val0, val1;
        ctr = pos = 0;
        while (ctr < len && pos + 3 <= inpBufLen)
        {
            val0 = (short)(((((short)(inpBuf[pos] & 0xFF)) >> 0) | (((short)(inpBuf[pos + 1] & 0xFF)) << 8)) & 0xFFF);
            val1 = (short)(((((short)(inpBuf[pos + 1] & 0xFF)) >> 4) | (((short)(inpBuf[pos + 2] & 0xFF)) << 4)) & 0xFFF);
            pos = pos + 3;
            if (val0 < (short)MLKEMEngine.KyberQ)
            {
                outputBuffer.setCoeffIndex(coeffOff + ctr, (short)val0);
                ctr++;
            }
            if (ctr < len && val1 < (short)MLKEMEngine.KyberQ)
            {
                outputBuffer.setCoeffIndex(coeffOff + ctr, (short)val1);
                ctr++;
            }
        }
        return ctr;

    }

    /**
     * decrypt.
     */
    public byte[] decrypt(byte[] secretKey, byte[] cipherText)
    {
        byte[] outputMessage = new byte[MLKEMEngine.getKyberIndCpaMsgBytes()];

        PolyVec bp = new PolyVec(engine), secretKeyPolyVec = new PolyVec(engine);
        Poly v = new Poly(engine), mp = new Poly(engine);

        unpackCipherText(bp, v, cipherText);

        unpackSecretKey(secretKeyPolyVec, secretKey);

        bp.polyVecNtt();

        PolyVec.pointwiseAccountMontgomery(mp, secretKeyPolyVec, bp, engine);

        mp.polyInverseNttToMont();

        mp.polySubtract(v);

        mp.reduce();

        outputMessage = mp.toMsg();

        return outputMessage;
    }

}
