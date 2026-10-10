package org.bouncycastle.pqc.crypto.mlkem;

import org.bouncycastle.crypto.digests.SHA3Digest;
import org.bouncycastle.crypto.digests.SHAKEDigest;

/**
 * Abstract base class for symmetric cryptographic primitives in ML-KEM.
 * Provides hash function interfaces for different symmetric operations.
 */
abstract class Symmetric
{

    /** Bytes produced per call to the XOF squeeze, i.e. the SHAKE rate. */
    final int xofBlockBytes;

    /**
     * Hash h operation, the spec's H applied to a 64-byte input.
     *
     * @param out the buffer to write the 32-byte hash into
     * @param in the 64 bytes to hash
     * @param outOffset the offset into out at which to write the hash
     */
    abstract void hash_h(byte[] out, byte[] in, int outOffset);

    /**
     * Hash g operation, the spec's G applied to a 128-byte input.
     *
     * @param out the buffer to write the 64-byte hash into
     * @param in the 128 bytes to hash
     */
    abstract void hash_g(byte[] out, byte[] in);

    /**
     * XOF absorb operation, seeding the extendable output function.
     *
     * @param seed the 32-byte seed to absorb
     * @param x the low public-sample byte to absorb after the seed
     * @param y the high public-sample byte to absorb after x
     */
    abstract void xofAbsorb(byte[] seed, byte x, byte y);

    /**
     * XOF squeeze operation, emitting whole rate-sized blocks at a time.
     *
     * @param out the buffer to write the expanded bytes into
     * @param outOffset the offset into out at which to write
     * @param outLen the number of bytes to write, a multiple of xofBlockBytes
     */
    abstract void xofSqueezeBlocks(byte[] out, int outOffset, int outLen);

    /**
     * PRF operation, producing out.length bytes keyed by key and nonce.
     *
     * @param out the buffer to write the pseudorandom bytes into
     * @param key the 32-byte pseudorandom key
     * @param nonce the single-byte nonce distinguishing one output from another
     */
    abstract void prf(byte[] out, byte[] key, byte nonce);

    /**
     * KDF operation, deriving out.length bytes from in.
     *
     * @param out the buffer to write the derived bytes into
     * @param in the input keying material
     */
    abstract void kdf(byte[] out, byte[] in);

    /**
     * Create a symmetric primitive set with the given XOF rate.
     *
     * @param blockBytes the XOF rate in bytes, the granularity of each squeeze
     */
    Symmetric(int blockBytes)
    {
        this.xofBlockBytes = blockBytes;
    }

    /**
     * SHAKE-based symmetric implementation for ML-KEM operations.
     * Uses SHAKE extendable output functions for cryptographic hashing.
     */
    static class ShakeSymmetric
        extends Symmetric
        {
        private final SHAKEDigest xof;
        private final SHA3Digest sha3Digest512;
        private final SHA3Digest sha3Digest256;
        private final SHAKEDigest shakeDigest;

        /** Create shake symmetric. */
        ShakeSymmetric()
        {
            super(168);
            this.xof = new SHAKEDigest(128);
            this.shakeDigest = new SHAKEDigest(256);
            this.sha3Digest256 = new SHA3Digest(256);
            this.sha3Digest512 = new SHA3Digest(512);
        }

        @Override
        void hash_h(byte[] out, byte[] in, int outOffset)
        {
            sha3Digest256.update(in, 0, in.length);
            sha3Digest256.doFinal(out, outOffset);
        }

        @Override
        void hash_g(byte[] out, byte[] in)
        {
            sha3Digest512.update(in, 0, in.length);
            sha3Digest512.doFinal(out, 0);
        }

        @Override
        void xofAbsorb(byte[] seed, byte a, byte b)
        {
            xof.reset();
            byte[] buf = new byte[seed.length + 2];
            System.arraycopy(seed, 0, buf, 0, seed.length);
            buf[seed.length] = a;
            buf[seed.length + 1] = b;
            xof.update(buf, 0, seed.length + 2);
        }

        @Override
        void xofSqueezeBlocks(byte[] out, int outOffset, int outLen)
        {
            xof.doOutput(out, outOffset, outLen);
        }

        @Override
        void prf(byte[] out, byte[] seed, byte nonce)
        {
            byte[] extSeed = new byte[seed.length + 1];
            System.arraycopy(seed, 0, extSeed, 0, seed.length);
            extSeed[seed.length] = nonce;
            shakeDigest.update(extSeed, 0, extSeed.length);
            shakeDigest.doFinal(out, 0, out.length);
        }

        @Override
        void kdf(byte[] out, byte[] in)
        {
            shakeDigest.update(in, 0, in.length);
            shakeDigest.doFinal(out, 0, out.length);
        }
    }
}
