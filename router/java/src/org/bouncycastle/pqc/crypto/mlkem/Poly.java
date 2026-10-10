package org.bouncycastle.pqc.crypto.mlkem;

/**
 * Polynomial representation for ML-KEM operations.
 * Provides polynomial arithmetic operations used in lattice-based cryptography.
 */
class Poly
{
    private short[] coeffs;
    private MLKEMEngine engine;
    private int polyCompressedBytes;
    private int eta1;
    private int eta2;

    private Symmetric symmetric;

    /**
     * Poly.
     * @param engine the engine supplying the Kyber parameters, RNG and symmetric primitives
     */
    public Poly(MLKEMEngine engine)
    {
        this.coeffs = new short[MLKEMEngine.KyberN];
        this.engine = engine;
        polyCompressedBytes = engine.getKyberPolyCompressedBytes();
        this.eta1 = engine.getKyberEta1();
        this.eta2 = MLKEMEngine.getKyberEta2();
        this.symmetric = engine.getSymmetric();
    }

    /**
     * getCoeffIndex.
     * @param i the coefficient index, 0 to KyberN-1
     * @return the coefficient at that index
     */
    public short getCoeffIndex(int i)
    {
        return this.coeffs[i];
    }

    /**
     * getCoeffs.
     * @return the backing coefficient array, shared rather than copied
     */
    public short[] getCoeffs()
    {
        return this.coeffs;
    }

    /**
     * setCoeffIndex.
     * @param i the coefficient index, 0 to KyberN-1
     * @param val the replacement coefficient
     */
    public void setCoeffIndex(int i, short val)
    {
        this.coeffs[i] = val;
    }

    /**
     * setCoeffs.
     * @param coeffs the new backing array, adopted by reference and not copied
     */
    public void setCoeffs(short[] coeffs)
    {
        this.coeffs = coeffs;
    }

    /**
     * polyNtt.
     */
    public void polyNtt()
    {
        this.setCoeffs(Ntt.ntt(this.getCoeffs()));
        this.reduce();
    }

    /**
     * polyInverseNttToMont.
     */
    public void polyInverseNttToMont()
    {
        this.setCoeffs(Ntt.invNtt(this.getCoeffs()));
    }

    /**
     * reduce.
     */
    public void reduce()
    {
        int i;
        for (i = 0; i < MLKEMEngine.KyberN; i++)
        {
            this.setCoeffIndex(i, Reduce.barretReduce(this.getCoeffIndex(i)));
        }
    }

    /**
     * baseMultMontgomery.
     * @param r the polynomial receiving the product, in Montgomery form
     * @param a the first multiplicand, in the number-theoretic transform domain
     * @param b the second multiplicand, in the number-theoretic transform domain
     */
    public static void baseMultMontgomery(Poly r, Poly a, Poly b)
    {
        int i;
        for (i = 0; i < MLKEMEngine.KyberN / 4; i++)
        {
            Ntt.baseMult(r, 4 * i,
                a.getCoeffIndex(4 * i), a.getCoeffIndex(4 * i + 1),
                b.getCoeffIndex(4 * i), b.getCoeffIndex(4 * i + 1),
                Ntt.nttZetas[64 + i]);
            Ntt.baseMult(r, 4 * i + 2,
                a.getCoeffIndex(4 * i + 2), a.getCoeffIndex(4 * i + 3),
                b.getCoeffIndex(4 * i + 2), b.getCoeffIndex(4 * i + 3),
                (short)(-1 * Ntt.nttZetas[64 + i]));
        }
    }

    /**
     * addCoeffs.
     * @param b the polynomial added coefficient-wise to this one
     */
    public void addCoeffs(Poly b)
    {
        int i;
        for (i = 0; i < MLKEMEngine.KyberN; i++)
        {
            this.setCoeffIndex(i, (short)(this.getCoeffIndex(i) + b.getCoeffIndex(i)));
        }
    }

    /**
     * convertToMont.
     */
    public void convertToMont()
    {
        int i;
        final short f = (short)(((long)1 << 32) % MLKEMEngine.KyberQ);
        for (i = 0; i < MLKEMEngine.KyberN; i++)
        {
            this.setCoeffIndex(i, Reduce.montgomeryReduce(this.getCoeffIndex(i) * f));
        }
    }

    /**
     * compressPoly.
     * @return the lossy compressed encoding, polyCompressedBytes long
     */
    public byte[] compressPoly()
    {
        int i, j;
        byte[] t = new byte[8];
        byte[] r = new byte[polyCompressedBytes];
        int count = 0;
        this.conditionalSubQ();

        // System.out.print("v = [");
        // Helper.printShortArray(this.coeffs);
        // System.out.print("]\n");

        if (polyCompressedBytes == 128)
        {
            for (i = 0; i < MLKEMEngine.KyberN / 8; i++)
            {
                for (j = 0; j < 8; j++)
                {
                    /*t[j] =
                        (byte)((((((short)this.getCoeffIndex(8 * i + j)) << 4)
                            +
                            (KyberEngine.KyberQ / 2)
                        ) / KyberEngine.KyberQ)
                            & 15);*/
                    // Fix for KyberSlash2: division by KyberQ above is not
                    // constant time.
                    int t_j = this.getCoeffIndex(8 * i + j);
                    t_j <<= 4;
                    t_j += 1665;
                    t_j *= 80635;
                    t_j >>= 28;
                    t_j &= 15;
                    t[j] = (byte)t_j;
                }

                r[count + 0] = (byte)(t[0] | (t[1] << 4));
                r[count + 1] = (byte)(t[2] | (t[3] << 4));
                r[count + 2] = (byte)(t[4] | (t[5] << 4));
                r[count + 3] = (byte)(t[6] | (t[7] << 4));
                count += 4;
            }
        }
        else if (polyCompressedBytes == 160)
        {
            for (i = 0; i < MLKEMEngine.KyberN / 8; i++)
            {
                for (j = 0; j < 8; j++)
                {
                    /*t[j] =
                        (byte)(((((this.getCoeffIndex(8 * i + j) << 5))
                            +
                            (KyberEngine.KyberQ / 2)
                        ) / KyberEngine.KyberQ
                        ) & 31
                        );*/
                    // Fix for KyberSlash2: division by KyberQ above is not
                    // constant time.
                    int t_j = this.getCoeffIndex(8 * i + j);
                    t_j <<= 5;
                    t_j += 1664;
                    t_j *= 40318;
                    t_j >>= 27;
                    t_j &= 31;
                    t[j] = (byte)t_j;
                }
                r[count + 0] = (byte)((t[0] >> 0) | (t[1] << 5));
                r[count + 1] = (byte)((t[1] >> 3) | (t[2] << 2) | (t[3] << 7));
                r[count + 2] = (byte)((t[3] >> 1) | (t[4] << 4));
                r[count + 3] = (byte)((t[4] >> 4) | (t[5] << 1) | (t[6] << 6));
                r[count + 4] = (byte)((t[6] >> 2) | (t[7] << 3));
                count += 5;
            }
        }
        else
        {
            throw new RuntimeException("PolyCompressedBytes is neither 128 or 160!");
        }

        // System.out.print("r = ");
        // Helper.printByteArray(r);
        // System.out.println();

        return r;
    }

    /**
     * decompressPoly.
     * @param compressedPolyCipherText the compressed encoding to decode into this polynomial
     */
    public void decompressPoly(byte[] compressedPolyCipherText)
    {
        int i, count = 0;

        if (engine.getKyberPolyCompressedBytes() == 128)
        {
            for (i = 0; i < MLKEMEngine.KyberN / 2; i++)
            {
                this.setCoeffIndex(2 * i + 0, (short)((((short)((compressedPolyCipherText[count] & 0xFF) & 15) * MLKEMEngine.KyberQ) + 8) >> 4));
                this.setCoeffIndex(2 * i + 1, (short)((((short)((compressedPolyCipherText[count] & 0xFF) >> 4) * MLKEMEngine.KyberQ) + 8) >> 4));
                count += 1;
            }
        }
        else if (engine.getKyberPolyCompressedBytes() == 160)
        {
            int j;
            byte[] t = new byte[8];
            for (i = 0; i < MLKEMEngine.KyberN / 8; i++)
            {
                t[0] = (byte)((compressedPolyCipherText[count + 0] & 0xFF) >> 0);
                t[1] = (byte)(((compressedPolyCipherText[count + 0] & 0xFF) >> 5) | ((compressedPolyCipherText[count + 1] & 0xFF) << 3));
                t[2] = (byte)((compressedPolyCipherText[count + 1] & 0xFF) >> 2);
                t[3] = (byte)(((compressedPolyCipherText[count + 1] & 0xFF) >> 7) | ((compressedPolyCipherText[count + 2] & 0xFF) << 1));
                t[4] = (byte)(((compressedPolyCipherText[count + 2] & 0xFF) >> 4) | ((compressedPolyCipherText[count + 3] & 0xFF) << 4));
                t[5] = (byte)((compressedPolyCipherText[count + 3] & 0xFF) >> 1);
                t[6] = (byte)(((compressedPolyCipherText[count + 3] & 0xFF) >> 6) | ((compressedPolyCipherText[count + 4] & 0xFF) << 2));
                t[7] = (byte)((compressedPolyCipherText[count + 4] & 0xFF) >> 3);
                count += 5;
                for (j = 0; j < 8; j++)
                {
                    this.setCoeffIndex(8 * i + j, (short)(((t[j] & 31) * MLKEMEngine.KyberQ + 16) >> 5));
                }
            }
        }
        else
        {
            throw new RuntimeException("PolyCompressedBytes is neither 128 or 160!");
        }

    }

    /**
     * toBytes.
     * @return the coefficients packed little-endian into KyberPolyBytes bytes
     */
    public byte[] toBytes()
    {
        byte[] r = new byte[MLKEMEngine.KyberPolyBytes];
        short t0, t1;
        this.conditionalSubQ();
        for (int i = 0; i < MLKEMEngine.KyberN / 2; i++)
        {
            t0 = this.getCoeffIndex(2 * i);
            t1 = this.getCoeffIndex(2 * i + 1);
            r[3 * i] = (byte)(t0 >> 0);
            r[3 * i + 1] = (byte)((t0 >> 8) | (t1 << 4));
            r[3 * i + 2] = (byte)(t1 >> 4);
        }

        return r;

    }

    /**
     * fromBytes.
     * @param inpBytes the packed coefficients to load, low 12 bits of each 2-byte group
     */
    public void fromBytes(byte[] inpBytes)
    {
        int i;
        for (i = 0; i < MLKEMEngine.KyberN / 2; i++)
        {
            this.setCoeffIndex(2 * i, (short)(
                (
                    ((inpBytes[3 * i + 0] & 0xFF) >> 0)
                        | ((inpBytes[3 * i + 1] & 0xFF) << 8)
                ) & 0xFFF)
            );
            this.setCoeffIndex(2 * i + 1, (short)(
                (
                    ((inpBytes[3 * i + 1] & 0xFF) >> 4)
                        | (long)((inpBytes[3 * i + 2] & 0xFF) << 4)
                ) & 0xFFF)
            );
        }
    }

    /**
     * toMsg.
     * @return the coefficients packed as one bit each, KyberN/8 bytes long
     */
    public byte[] toMsg()
    {
        int LOWER = MLKEMEngine.KyberQ >>> 2;
        int UPPER = MLKEMEngine.KyberQ - LOWER;

        byte[] outMsg = new byte[MLKEMEngine.getKyberIndCpaMsgBytes()];

        this.conditionalSubQ();

        for (int i = 0; i < MLKEMEngine.KyberN / 8; i++)
        {
            outMsg[i] = 0;
            for (int j = 0; j < 8; j++)
            {
                int c_j = this.getCoeffIndex(8 * i + j);

                // KyberSlash: division by Q is not constant time.
//                int t = (((c_j << 1) + (KyberEngine.KyberQ / 2)) / KyberEngine.KyberQ) & 1;
                int t = ((LOWER - c_j) & (c_j - UPPER)) >>> 31;

                outMsg[i] |= (byte)(t << j);
            }
        }
        return outMsg;
    }

    /**
     * fromMsg.
     * @param msg the one-bit-per-coefficient message, exactly KyberN/8 bytes
     */
    public void fromMsg(byte[] msg)
    {
        int i, j;
        short mask;
        if (msg.length != MLKEMEngine.KyberN / 8)
        {
            throw new RuntimeException("KYBER_INDCPA_MSGBYTES must be equal to KYBER_N/8 bytes!");
        }
        for (i = 0; i < MLKEMEngine.KyberN / 8; i++)
        {
            for (j = 0; j < 8; j++)
            {
                mask = (short)((-1) * (short)(((msg[i] & 0xFF) >> j) & 1));
                this.setCoeffIndex(8 * i + j, (short)(mask & (short)((MLKEMEngine.KyberQ + 1) / 2)));
            }
        }
    }

    /**
     * conditionalSubQ.
     */
    public void conditionalSubQ()
    {
        int i;
        for (i = 0; i < MLKEMEngine.KyberN; i++)
        {
            this.setCoeffIndex(i, Reduce.conditionalSubQ(this.getCoeffIndex(i)));
        }
    }

    /**
     * getEta1Noise.
     * @param seed the 32-byte noise seed fed to the PRF
     * @param nonce the single-byte domain separator
     */
    public void getEta1Noise(byte[] seed, byte nonce)
    {
        byte[] buf = new byte[MLKEMEngine.KyberN * eta1 / 4];
        symmetric.prf(buf, seed, nonce);
        CBD.mlkemCBD(this, buf, eta1);
    }

    /**
     * getEta2Noise.
     * @param seed the 32-byte noise seed fed to the PRF
     * @param nonce the single-byte domain separator
     */
    public void getEta2Noise(byte[] seed, byte nonce)
    {
        byte[] buf = new byte[MLKEMEngine.KyberN * eta2 / 4];
        symmetric.prf(buf, seed, nonce);
        CBD.mlkemCBD(this, buf, eta2);
    }

    /**
     * polySubtract.
     * @param b the polynomial subtracted coefficient-wise from this one
     */
    public void polySubtract(Poly b)
    {
        int i;
        for (i = 0; i < MLKEMEngine.KyberN; i++)
        {
            this.setCoeffIndex(i, (short)(b.getCoeffIndex(i) - this.getCoeffIndex(i)));
        }
    }

    /**
     * toString.
     */
    public String toString()
    {
        StringBuffer out = new StringBuffer();
        out.append("[");
        for (int i = 0; i < coeffs.length; i++)
        {
            out.append(coeffs[i]);
            if (i != coeffs.length - 1)
            {
                out.append(", ");
            }
        }
        out.append("]");
        return out.toString();
    }
}
