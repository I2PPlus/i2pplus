package net.i2p.router.crypto.ratchet;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.eddsa.math.Curve;
import net.i2p.crypto.eddsa.math.Field;
import net.i2p.crypto.eddsa.math.bigint.BigIntegerLittleEndianEncoding;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.data.PublicKey;
import net.i2p.util.NativeBigInteger;

/**
 *  Elligator2 encoding and decoding for X25519 public keys to provide cryptographically indistinguishable representations
 *
 *  Ported from the Jan. 13, 2016 C version at https://github.com/Kleshni/Elligator-2
 *  Note: That code was completely rewritten May 8, 2017 and is now much more complex.
 *  No apparent license.
 *
 *  @since 0.9.44
 */
class Elligator2 {

    private final I2PAppContext _context;

    private static final BigInteger p;
    private static final BigInteger divide_plus_p_3_8;
    private static final BigInteger divide_minus_p_1_2;
    private static final BigInteger divide_minus_p_1_4;
    private static final BigInteger square_root_negative_1;
    private static final long A_INT = 486662;
    private static final BigInteger A = new BigInteger(Long.toString(A_INT));
    private static final BigInteger negative_A;
    private static final BigInteger u;
    private static final BigInteger inverted_u;
    private static final BigInteger TWO = new NativeBigInteger("2");

    private static final int REPRESENTATIVE_LENGTH = 32;

    private static final EdDSANamedCurveSpec SPEC = EdDSANamedCurveTable.getByName("ed25519-sha-512");
    private static final Curve CURVE = SPEC.getCurve();
    private static final Field FIELD = CURVE.getField();
    private static final BigIntegerLittleEndianEncoding ENCODING = new BigIntegerLittleEndianEncoding();

    private static final boolean DISABLE = false;

    static {
        ENCODING.setField(FIELD);

        p = TWO.pow(255).subtract(new BigInteger("19"));

        divide_plus_p_3_8 = p.add(new BigInteger("3")).divide(new BigInteger("8"));

        divide_minus_p_1_2 = p.subtract(BigInteger.ONE).divide(TWO);

        divide_minus_p_1_4 = divide_minus_p_1_2.divide(TWO);

        square_root_negative_1 = TWO.modPow(divide_minus_p_1_4, p);

        negative_A = p.subtract(A);

        u = TWO;

        inverted_u = u.modInverse(p);
    }

    /**
     * Create an Elligator2 instance.
     *
     * @param ctx the I2P app context
     */
    public Elligator2(I2PAppContext ctx) {
        _context = ctx;
    }

    /**
     * Use for on-the-wire. Don't use for unit tests as output will be randomized
     * based on the 'alternative' and the high bits.
     * There are eight possible encodings for any point.
     * Output will look like 256 random bits.
     *
     * @param point the public key to encode
     * @return "representative", little endian or null on failure
     */
    public byte[] encode(PublicKey point) {
        byte[] random = new byte[1];
        _context.random().nextBytes(random);
        byte rand = random[0];
        return encode(point, (rand & 0x01) == 0, rand);
    }

    /**
     * Use for unit tests. Don't use for on-the-wire; use one-arg version.
     * Output will look like 254 random bits.
     * High two bits of rv[31] will be zero.
     *
     * From javascript version documentation:
     *
     * The algorithm can return two different values for a single x coordinate if it's not 0.
     * Which one to return is determined by y coordinate.
     * Since Curve25519 doesn't use y due to optimizations, you should specify a Boolean value
     * as the second argument of the function.
     * It should be unpredictable, because it's recoverable from the representative.
     *
     * @return "representative", little endian or null on failure
     * @deprecated
     */
    @Deprecated
    protected static byte[] encode(PublicKey point, boolean alternative) {
        return encode(point, alternative, (byte) 0);
    }

    /**
     * Output will look like 254 random bits. High two bits of highBits will be ORed in.
     *
     * From javascript version documentation:
     *
     * The algorithm can return two different values for a single x coordinate if it's not 0.
     * Which one to return is determined by y coordinate.
     * Since Curve25519 doesn't use y due to optimizations, you should specify a Boolean value
     * as the second argument of the function.
     * It should be unpredictable, because it's recoverable from the representative.
     *
     * @param highBits High two bits will be ORed into rv[31]
     * @return "representative", little endian or null on failure
     * @since 0.9.45 to add highBits arg
     */
    private static byte[] encode(PublicKey point, boolean alternative, byte highBits) {
        if (DISABLE)
            return point.getData();

        // x
        BigInteger x = ENCODING.toBigInteger(point.getData());

        // If x = 0
        if (x.signum() == 0) {
            alternative = false;
        }

        // negative_plus_x_A = -(x + A) (mod p)
        BigInteger negative_plus_x_A = x.add(A).negate();

        // negative_multiply3_u_x_plus_x_A = -ux(x + A) (mod p)
        BigInteger negative_multiply3_u_x_plus_x_A = u.multiply(x);
        negative_multiply3_u_x_plus_x_A = negative_multiply3_u_x_plus_x_A.mod(p);
        negative_multiply3_u_x_plus_x_A = negative_multiply3_u_x_plus_x_A.multiply(negative_plus_x_A);
        negative_multiply3_u_x_plus_x_A = negative_multiply3_u_x_plus_x_A.mod(p);

        // If -ux(x + A) is not a square modulo p
        if (legendre(negative_multiply3_u_x_plus_x_A) == -1) {
            return null;
        }

        BigInteger r;
        if (alternative) {
            // r := -(x + A) / x (mod p)
            r = x.modInverse(p);
            r = r.multiply(negative_plus_x_A);
        } else {
            // r := -x / (x + A) (mod p)
            r = negative_plus_x_A.modInverse(p);
            r = r.multiply(x);
        }
        r = r.mod(p);

        // r := square_root(r / u) (mod p)
        r = r.multiply(inverted_u);
        r = r.mod(p);
        r = square_root(r);

        // little endian
        byte[] rv = ENCODING.encode(r);
        // randomize two high bits
        rv[REPRESENTATIVE_LENGTH - 1] |= highBits & (byte) 0xc0;
        return rv;
    }

    /**
     * From javascript version documentation:
     *
     * Returns an array with the point and the second argument of the corresponding call to the `encode` function.
     * It's also able to return null if the representative is invalid (there are only 10 invalid representatives).
     *
     * @param representative the encoded data, little endian, 32 bytes
     *                       WILL BE MODIFIED by masking byte 31
     *
     * @return x or null on failure
     */
    public static PublicKey decode(byte[] representative) {
        return decode(null, representative);
    }

    /**
     * From javascript version documentation:
     *
     * Returns an array with the point and the second argument of the corresponding call to the `encode` function.
     * It's also able to return null if the representative is invalid (there are only 10 invalid representatives).
     *
     * @param alternative out parameter, or null if you don't care
     * @param representative the encoded data, little endian, 32 bytes;
     *                       WILL BE MODIFIED by masking byte 31
     *
     * @return x or null on failure
     */
    public static PublicKey decode(AtomicBoolean alternative, byte[] representative) {
        if (representative.length != REPRESENTATIVE_LENGTH)
            throw new IllegalArgumentException("must be 32 bytes");
        if (DISABLE)
            return new PublicKey(EncType.ECIES_X25519, representative);

        // r
        // Mask out two high bits, to get valid 254 bits.
        representative[REPRESENTATIVE_LENGTH - 1] &= (byte) 0x3f;
        BigInteger r = ENCODING.toBigInteger(representative);

        // If r >= (p - 1) / 2
        if (r.compareTo(divide_minus_p_1_2) >= 0) {
            return null;
        }

        // v = -A / (1 + ur ^ 2) (mod p)
        BigInteger v = r.multiply(r);
        v = v.mod(p);
        v = v.multiply(u);
        v = v.add(BigInteger.ONE);
        v = v.mod(p);
        v = v.modInverse(p);
        v = v.multiply(negative_A);
        v = v.mod(p);

        // plus_v_A = v + A (mod p)
        BigInteger plus_v_A = v.add(A);

        // t = x ^ 3 + Ax ^ 2 + Bx (mod p)
        BigInteger t = v.multiply(v);
        t = t.mod(p);
        t = t.multiply(plus_v_A);
        t = t.add(v);
        t = t.mod(p);

        // e = Legendre symbol (t / p)
        int e = legendre(t);

        BigInteger x;
        if (e == 1) {
            x = v;
        } else {
            x = p.subtract(v);
            x = x.subtract(A);
            x = x.mod(p);
        }

        if (alternative != null)
            alternative.set(e == 1);

        byte[] dec = ENCODING.encode(x);
        return new PublicKey(EncType.ECIES_X25519, dec);
    }

    private static BigInteger square_root(BigInteger x) {
        // t = x ^ ((p - 1) / 4) (mod p)
        if (!(x instanceof NativeBigInteger))
            x = new NativeBigInteger(x);

        BigInteger t = x.modPow(divide_minus_p_1_4, p);

        // result := x ^ ((p + 3) / 8) (mod p)
        BigInteger result = x.modPow(divide_plus_p_3_8, p);

        // If t = -1 (mod p)
        t = t.add(BigInteger.ONE);
        if (t.compareTo(p) == 0) {
            // result := result * square_root(-1) (mod p)
            result = result.multiply(square_root_negative_1);
            result = result.mod(p);
        }

        // If result > (p - 1) / 2
        if (result.compareTo(divide_minus_p_1_2) > 0) {
            // result := -result (mod p)
            result = p.subtract(result);
        }
        return result;
    }

    /**
     * https://gmplib.org/manual/Number-Theoretic-Functions.html
     * https://en.wikipedia.org/wiki/Legendre_symbol
     *
     * @param a must already be mod(p)
     *
     * @return -1/0/1
     */
    private static int legendre(BigInteger a) {
        if (a.signum() == 0)
            return 0;
        if (!(a instanceof NativeBigInteger))
            a = new NativeBigInteger(a);
        BigInteger mp = a.modPow(divide_minus_p_1_2, p);
        // mp is either 1 or (p - 1) (0x7ffff...fffec)
        int cmp = mp.compareTo(BigInteger.ONE);
        if (cmp == 0)
            return 1;
        return -1;
    }

}
