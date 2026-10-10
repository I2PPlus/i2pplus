package org.bouncycastle.pqc.crypto.mlkem;

/**
 * Polynomial reduction operations for ML-KEM cryptography.
 * Provides Montgomery reduction and Barrett reduction for polynomial arithmetic.
 */
class Reduce
{

    /**
     * Both reductions are static and the class holds no fields, so an instance
     * carries nothing; it exists only to give the helper class a documented entry point.
     */
    Reduce()
    {
    }

    /**
     * montgomeryReduce.
     *
     * @param a the value to reduce, wide enough to hold the intermediate product
     * @return a scaled down into the Montgomery representation, as a short
     */
    public static short montgomeryReduce(int a)
    {
        int t;
        short u;

        u = (short)(a * MLKEMEngine.KyberQinv);
        t = (int)(u * MLKEMEngine.KyberQ);
        t = a - t;
        t >>= 16;
        return (short)t;
    }

    /**
     * barretReduce.
     *
     * @param a the coefficient to centre into the range -q/2 to q/2
     * @return the reduced coefficient, as a short
     */
    public static short barretReduce(short a)
    {
        short t;
        long shift = (((long)1) << 26);
        short v = (short)((shift + (MLKEMEngine.KyberQ / 2)) / MLKEMEngine.KyberQ);
        t = (short)((v * a) >> 26);
        t = (short)(t * MLKEMEngine.KyberQ);
        return (short)(a - t);
    }

    /**
     * conditionalSubQ.
     *
     * @param a the coefficient that may need q subtracting to become non-negative
     * @return a if it was already non-negative, otherwise a plus q, as a short
     */
    public static short conditionalSubQ(short a)
    {
        a -= MLKEMEngine.KyberQ;
        a += (a >> 15) & MLKEMEngine.KyberQ;
        return a;
    }

}
