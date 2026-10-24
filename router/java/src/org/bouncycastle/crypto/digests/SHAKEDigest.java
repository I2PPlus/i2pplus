package org.bouncycastle.crypto.digests;

import org.bouncycastle.crypto.CryptoServiceProperties;
import org.bouncycastle.crypto.CryptoServicePurpose;
import org.bouncycastle.crypto.Xof;

/**
 * Implementation of SHAKE based on following KeccakNISTInterface.c from <a href="https://keccak.team/">keccak.team</a>
 * <p>
 * Following the naming conventions used in the C source code to enable easy review of the implementation.
 */
public class SHAKEDigest
    extends KeccakDigest
    implements Xof
{
    /**
     * Rejects a bit strength that is not a multiple of 8.
     *
     * @param bitStrength the strength to check
     * @return the strength, if valid
     */
    private static int checkBitLength(int bitStrength)
    {
        switch (bitStrength)
        {
        case 128:
        case 256:
            return bitStrength;
        default:
            throw new IllegalArgumentException("'bitStrength' " + bitStrength + " not supported for SHAKE");
        }
    }

    /**
     * Creates a SHAKE digest with 128 bits of security strength.
     */
    public SHAKEDigest()
    {
        this(128);
    }

    /**
     * Creates a 128-bit SHAKE digest bound to a declared purpose.
     *
     * @param purpose the use the digest is declared to serve, which callers must match
     */
    public SHAKEDigest(CryptoServicePurpose purpose)
    {
        this(128, purpose);
    }

    /**
     * Base constructor.
     *
     * @param bitStrength the security strength in bits of the XOF.
     */
    public SHAKEDigest(int bitStrength)
    {
        super(checkBitLength(bitStrength), CryptoServicePurpose.ANY);
    }

    /**
     * Base constructor.
     *
     * @param bitStrength the security strength in bits of the XOF.
     * @param purpose the purpose of the digest will be used for.
     */
    public SHAKEDigest(int bitStrength, CryptoServicePurpose purpose)
    {
        super(checkBitLength(bitStrength), purpose);
    }

    /**
     * Clone constructor
     *
     * @param source the other digest to be copied.
     */
    public SHAKEDigest(SHAKEDigest source)
    {
        super(source);
    }

    /**
     * Names the algorithm as SHAKE followed by its bit strength.
     */
    public String getAlgorithmName()
    {
        return "SHAKE" + fixedOutputLength;
    }

    /**
     * Gets the default output size in bytes, a quarter of the bit strength.
     */
    public int getDigestSize()
    {
        return fixedOutputLength / 4;
    }

    /**
     * Squeezes the default digest size and resets the sponge.
     */
    public int doFinal(byte[] out, int outOff)
    {
        return doFinal(out, outOff, getDigestSize());
    }

    /**
     * Squeezes outLen bytes, then resets the sponge.
     */
    public int doFinal(byte[] out, int outOff, int outLen)
    {
        int length = doOutput(out, outOff, outLen);

        reset();

        return length;
    }

    /**
     * Squeezes outLen bytes without resetting, so output can be streamed.
     */
    public int doOutput(byte[] out, int outOff, int outLen)
    {
        if (!squeezing)
        {
            absorbBits(0x0F, 4);
        }

        squeeze(out, outOff, ((long)outLen) * 8);

        return outLen;
    }

    /*
     * TODO Possible API change to support partial-byte suffixes.
     */
    /**
     * Squeezes the default digest size after a partial trailing byte.
     */
    protected int doFinal(byte[] out, int outOff, byte partialByte, int partialBits)
    {
        return doFinal(out, outOff, getDigestSize(), partialByte, partialBits);
    }

    /*
     * TODO Possible API change to support partial-byte suffixes.
     */
    /**
     * Squeezes outLen bytes after a partial trailing byte.
     *
     * @param out the buffer the squeezed output is written to
     * @param outOff the index in out at which output starts
     * @param outLen the number of output bytes asked for
     * @param partialByte the byte holding any bits not yet absorbed
     * @param partialBits how many low bits of partialByte are data, 0 to 7
     * @return the number of bytes written to out, always outLen
     */
    protected int doFinal(byte[] out, int outOff, int outLen, byte partialByte, int partialBits)
    {
        if (partialBits < 0 || partialBits > 7)
        {
            throw new IllegalArgumentException("'partialBits' must be in the range [0,7]");
        }

        int finalInput = (partialByte & ((1 << partialBits) - 1)) | (0x0F << partialBits);
        int finalBits = partialBits + 4;

        if (finalBits >= 8)
        {
            absorb((byte)finalInput);
            finalBits -= 8;
            finalInput >>>= 8;
        }

        if (finalBits > 0)
        {
            absorbBits(finalInput, finalBits);
        }

        squeeze(out, outOff, ((long)outLen) * 8);

        reset();

        return outLen;
    }

    /**
     * Returns null, as SHAKE declares no crypto service properties.
     */
    protected CryptoServiceProperties cryptoServiceProperties()
    {
        return null;
    }
}
