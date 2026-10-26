package org.bouncycastle.crypto;

/**
 * A message digest: absorb data with {@link #update}, finish it with
 * {@link #doFinal}, or discard the computation with {@link #reset}.
 */
public interface Digest
{
    /**
     * The name of the algorithm this digest implements.
     *
     * @return the algorithm name
     */
    public String getAlgorithmName();

    /**
     * The size of the digest this message digest produces.
     *
     * @return the digest size in bytes
     */
    public int getDigestSize();

    /**
     * Absorb a single byte into the digest.
     *
     * @param in the byte to absorb
     */
    public void update(byte in);

    /**
     * Absorb part of an array into the digest.
     *
     * @param in the array holding the data to absorb
     * @param inOff the offset into in where the data starts
     * @param len the number of bytes to absorb
     */
    public void update(byte[] in, int inOff, int len);

    /**
     * Close the digest, producing the final digest value. The doFinal
     * call leaves the digest reset.
     *
     * @param out the array the digest is to be copied into.
     * @param outOff the offset into the out array the digest is to start at.
     * @return the number of bytes written to out, equal to the digest size in bytes.
     */
    public int doFinal(byte[] out, int outOff);

    /**
     * Reset the digest back to its initial state.
     */
    public void reset();
}
