package net.i2p.crypto.eddsa.spec;

import net.i2p.crypto.eddsa.math.GroupElement;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.KeySpec;
import java.util.Arrays;

/**
 * EdDSA private key specification.
 *
 * @since 0.9.15
 */
public class EdDSAPrivateKeySpec implements KeySpec {
    /** The 32-byte seed the key was derived from. */
    private final byte[] seed;
    /** The hash of the seed, from which the scalar and prefix come. */
    private final byte[] h;
    /** The private scalar. */
    private final byte[] a;
    /** The matching public point. */
    private final GroupElement A;
    /** The curve parameters the key belongs to. */
    private final EdDSAParameterSpec spec;

    /**
     * Create a new EdDSA private key specification from a seed.
     *
     * @param seed the private key
     * @param spec the parameter specification for this key
     * @throws IllegalArgumentException if seed length is wrong or hash algorithm is unsupported
     */
    public EdDSAPrivateKeySpec(byte[] seed, EdDSAParameterSpec spec) {
        int bd8 = spec.getCurve().getField().getb() / 8;
        if (seed.length != bd8) throw new IllegalArgumentException("seed length is wrong");

        this.spec = spec;
        // Defensive copy: the caller may mutate or retain its array afterwards.
        this.seed = seed.clone();

        try {
            MessageDigest hash = MessageDigest.getInstance(spec.getHashAlgorithm());

            // H(k)
            h = hash.digest(this.seed);

            // Saves ~0.4ms per key when running signing tests.
            // TODO: are these bitflips the same for any hash function?
            h[0] &= 248;
            h[bd8 - 1] &= 63;
            h[bd8 - 1] |= 64;
            a = Arrays.copyOfRange(h, 0, bd8);

            A = spec.getB().scalarMultiply(a);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unsupported hash algorithm");
        }
    }

    /**
     * Initialize directly from the hash.
     * {@link #getSeed()} will return null if this constructor is used.
     *
     * @param spec the parameter specification for this key
     * @param h the private key; copied and clamped on the copy, the caller's
     * array is never modified
     * @throws IllegalArgumentException if hash length is wrong
     * @since 0.9.27 (GitHub issue #17)
     */
    public EdDSAPrivateKeySpec(EdDSAParameterSpec spec, byte[] h) {
        int bd4 = spec.getCurve().getField().getb() / 4;
        if (h.length != bd4) throw new IllegalArgumentException("hash length is wrong");
        int bd8 = bd4 / 2;

        this.seed = null;
        // Copy before clamping so the caller's array is never mutated in place
        // nor retained by reference.
        this.h = h.clone();
        this.spec = spec;

        this.h[0] &= 248;
        this.h[bd8 - 1] &= 63;
        this.h[bd8 - 1] |= 64;
        a = Arrays.copyOfRange(this.h, 0, bd8);

        A = spec.getB().scalarMultiply(a);
    }

    /**
     * No validation of any parameters other than a.
     * getSeed() and {@link #getH()} will return null if this constructor is used.
     *
     * @param a must be "clamped" (for Ed) or reduced mod l (for Red); it is
     * not validated - an invalid scalar silently yields a wrong public key.
     * The array is copied, later changes by the caller have no effect.
     * @param aPoint if null, will be derived from a.
     * @param spec the domain parameters naming the curve and cofactor this key belongs to
     * @since 0.9.39
     */
    public EdDSAPrivateKeySpec(byte[] a, GroupElement aPoint, EdDSAParameterSpec spec) {
        this(null, null, a, aPoint, spec);
    }

    /**
     * No validation of any parameters other than a.
     *
     * @param seed may be null; copied
     * @param h may be null; copied
     * @param a must be "clamped" (for Ed) or reduced mod l (for Red); it is
     * not validated - an invalid scalar silently yields a wrong public key.
     * The array is copied, later changes by the caller have no effect.
     * @param aPoint if null, will be derived from a.
     * @param spec the domain parameters naming the curve and cofactor this key belongs to
     */
    public EdDSAPrivateKeySpec(byte[] seed, byte[] h, byte[] a, GroupElement aPoint, EdDSAParameterSpec spec) {
        this.seed = copy(seed);
        this.h = copy(h);
        this.a = copy(a);
        this.A = (aPoint != null) ? aPoint : spec.getB().scalarMultiply(a);
        this.spec = spec;
    }

    /**
     * Defensive copy; null passes through unchanged.
     *
     * @param b the array to copy, may be null
     * @return a clone of b, or null if b was null
     */
    private static byte[] copy(byte[] b) {
        if (b == null)
            return null;
        return b.clone();
    }

    /**
     * Return the seed, or null if constructed from the private key directly.
     *
     * @return a copy of the seed, or null if constructed directly from the private key
     */
    public byte[] getSeed() {
        return copy(seed);
    }

    /**
     * Return the clamped hash of the seed, H(k), from which the scalar a is derived.
     *
     * @return a copy of the clamped hash of the seed, 2*bd8 bytes with the
     * low bits of the first byte cleared and bit 6 of the last byte set
     */
    public byte[] getH() {
        return copy(h);
    }

    /**
     * Return the private key.
     *
     * @return a copy of the private key
     */
    public byte[] geta() {
        return copy(a);
    }

    /**
     * Return the public key.
     *
     * @return the public key
     */
    public GroupElement getA() {
        return A;
    }

    /**
     * Returns the parameter specification for this key.
     *
     * @return the parameter specification
     */
    public EdDSAParameterSpec getParams() {
        return spec;
    }
}
