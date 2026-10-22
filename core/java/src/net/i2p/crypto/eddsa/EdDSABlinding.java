package net.i2p.crypto.eddsa;

import net.i2p.crypto.eddsa.math.Field;
import net.i2p.crypto.eddsa.math.GroupElement;
import net.i2p.crypto.eddsa.math.ScalarOps;
import net.i2p.crypto.eddsa.math.bigint.BigIntegerLittleEndianEncoding;
import net.i2p.crypto.eddsa.math.bigint.BigIntegerScalarOps;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import java.math.BigInteger;

/**
 * Utilities for EdDSA key blinding operations.
 * Provides methods to blind and unblind EdDSA keys for privacy protection.
 *
 * @since 0.9.38
 */
public final class EdDSABlinding {

    /**
     * Order of the Ed25519 prime-order subgroup, as used to reduce exponents.
     */
    public static final BigInteger ORDER = new BigInteger("2").pow(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final byte[] ONE =
            Utils.hexToBytes("0100000000000000000000000000000000000000000000000000000000000000");
    /** -1 mod l, little endian; used by unblind to subtract alpha. */
    private static final byte[] MINUS_ONE = toLittleEndian(ORDER.subtract(BigInteger.ONE));
    private static final Field CURVE_FIELD = EdDSANamedCurveTable.getByName("Ed25519").getCurve().getField();

    private EdDSABlinding() {}

    /**
     * Blind an EdDSA key.
     * Only for SigType EdDSA_SHA512_Ed25519.
     *
     * @param key must be SigType EdDSA_SHA512_Ed25519
     * @param alpha generated from hash of secret data
     * @return the blinded public key
     * @throws UnsupportedOperationException for an unsupported group representation
     */
    public static EdDSAPublicKey blind(EdDSAPublicKey key, EdDSAPrivateKey alpha) {
        GroupElement a = key.getA();
        GroupElement aa = alpha.getA();
        // Add the two public keys together.
        GroupElement d = a.add(aa.toCached());
        EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(d, key.getParams());
        EdDSAPublicKey rv = new EdDSAPublicKey(pubKey);
        return rv;
    }

    /**
     * Blind an EdDSA private key.
     * Only for SigType EdDSA_SHA512_Ed25519.
     *
     * @param key must be SigType EdDSA_SHA512_Ed25519
     * @param alpha generated from hash of secret data
     * @return the blinded private key
     * @throws UnsupportedOperationException for an unsupported curve
     */
    public static EdDSAPrivateKey blind(EdDSAPrivateKey key, EdDSAPrivateKey alpha) {
        byte[] a = key.geta();
        byte[] aa = alpha.geta();
        Field f = key.getParams().getCurve().getField();
        BigIntegerLittleEndianEncoding enc = new BigIntegerLittleEndianEncoding();
        enc.setField(f);
        ScalarOps sc = new BigIntegerScalarOps(f, ORDER);
        // Add the two private keys together.
        // just for now, since we don't have a pure add.
        byte[] d = sc.multiplyAndAdd(ONE, a, aa);
        EdDSAPrivateKeySpec privKey = new EdDSAPrivateKeySpec(d, null, key.getParams());
        return new EdDSAPrivateKey(privKey);
    }

    /**
     * Unblind an EdDSA private key using alpha, the inverse of
     * {@link #blind(EdDSAPrivateKey, EdDSAPrivateKey)}: subtracts alpha
     * from the blinded scalar mod l.
     * The recovered scalar is the original scalar reduced mod l; it yields
     * the same public key point and signatures as the original.
     *
     * @param key must be SigType EdDSA_SHA512_Ed25519
     * @param alpha the same alpha used to blind the key
     * @return the unblinded private key
     */
    public static EdDSAPrivateKey unblind(EdDSAPrivateKey key, EdDSAPrivateKey alpha) {
        byte[] d = key.geta();
        byte[] aa = alpha.geta();
        Field f = key.getParams().getCurve().getField();
        ScalarOps sc = new BigIntegerScalarOps(f, ORDER);
        // Subtract alpha: (-1)*alpha + d mod l
        byte[] rv = sc.multiplyAndAdd(MINUS_ONE, aa, d);
        EdDSAPrivateKeySpec privKey = new EdDSAPrivateKeySpec(rv, null, key.getParams());
        return new EdDSAPrivateKey(privKey);
    }

    /**
     * Encode a positive BigInteger below 2^256 as 32 little-endian bytes.
     *
     * @param v the value to encode
     * @return the value as 32 little-endian bytes, zero-padded
     */
    private static byte[] toLittleEndian(BigInteger v) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[32];
        for (int i = 0; i < be.length && i < 32; i++)
            le[i] = be[be.length - 1 - i];
        return le;
    }

    /**
     * Reduce a 64-byte value modulo l.
     * Use to generate alpha.
     *
     * @param b 64 bytes little endian of random
     * @return 32 bytes little endian mod l
     */
    public static byte[] reduce(byte[] b) {
        if (b.length != 64) throw new IllegalArgumentException("Must be 64 bytes");
        ScalarOps sc = new BigIntegerScalarOps(CURVE_FIELD, ORDER);
        return sc.reduce(b);
    }
}
