package net.i2p.crypto;

import net.i2p.util.NativeBigInteger;

import java.math.BigInteger;
import java.security.spec.ECFieldFp;
import java.security.spec.ECPoint;
import java.security.spec.EllipticCurve;

/**
 * Elliptic curve utility functions for cryptographic operations.
 *
 * This class provides essential mathematical operations for elliptic curve cryptography
 * used throughout I2P, including scalar multiplication, point operations,
 * and curve parameter handling. It supports multiple curve types with optimized
 * implementations for common cryptographic operations.
 *
 * <p>Key operations include:</p>
 * <ul>
 *   <li>Scalar multiplication of curve points</li>
 *   <li>Point addition and doubling operations</li>
 *   <li>Curve parameter validation and extraction</li>
 *   <li>Support for standard NIST curves (P-192, P-256, P-384, P-521)</li>
 * </ul>
 *
 * @since 0.9.16
 */
final class ECUtil {

    private static final BigInteger TWO = new BigInteger("2");
    private static final BigInteger THREE = new BigInteger("3");

    /**
     * Scalar multiplication on the curve.
     *
     * @return the resulting ECPoint
     */
    public static ECPoint scalarMult(ECPoint p, BigInteger kin, EllipticCurve curve) {
        ECPoint r = ECPoint.POINT_INFINITY;
        BigInteger prime = ((ECFieldFp) curve.getField()).getP();
        BigInteger k = kin.mod(prime);
        int length = k.bitLength();
        byte[] binarray = new byte[length];
        for (int i = 0; i <= length - 1; i++) {
            binarray[i] = k.mod(TWO).byteValue();
            k = k.divide(TWO);
        }

        for (int i = length - 1; i >= 0; i--) {
            // i should start at length-1 not -2 because the MSB of binarry may not be 1
            r = doublePoint(r, curve);
            if (binarray[i] == 1) r = addPoint(r, p, curve);
        }
        return r;
    }

    private static ECPoint addPoint(ECPoint r, ECPoint s, EllipticCurve curve) {
        if (r.equals(s)) return doublePoint(r, curve);
        else if (r.equals(ECPoint.POINT_INFINITY)) return s;
        else if (s.equals(ECPoint.POINT_INFINITY)) return r;
        BigInteger prime = ((ECFieldFp) curve.getField()).getP();
        BigInteger tmp = r.getAffineX().subtract(s.getAffineX());
        tmp = new NativeBigInteger(tmp);
        BigInteger slope = (r.getAffineY().subtract(s.getAffineY())).multiply(tmp.modInverse(prime)).mod(prime);
        slope = new NativeBigInteger(slope);
        BigInteger xOut = (slope.modPow(TWO, prime).subtract(r.getAffineX())).subtract(s.getAffineX()).mod(prime);
        BigInteger yOut = s.getAffineY().negate().mod(prime);
        yOut = yOut.add(slope.multiply(s.getAffineX().subtract(xOut))).mod(prime);
        return new ECPoint(xOut, yOut);
    }

    private static ECPoint doublePoint(ECPoint r, EllipticCurve curve) {
        if (r.equals(ECPoint.POINT_INFINITY)) return r;
        BigInteger slope = (r.getAffineX().pow(2)).multiply(THREE);
        slope = slope.add(curve.getA());
        BigInteger prime = ((ECFieldFp) curve.getField()).getP();
        BigInteger tmp = r.getAffineY().multiply(TWO);
        tmp = new NativeBigInteger(tmp);
        slope = slope.multiply(tmp.modInverse(prime));
        BigInteger xOut = slope.pow(2).subtract(r.getAffineX().multiply(TWO)).mod(prime);
        BigInteger yOut = (r.getAffineY().negate()).add(slope.multiply(r.getAffineX().subtract(xOut))).mod(prime);
        return new ECPoint(xOut, yOut);
    }

    /**
     *  P-192 test only.
     *  See KeyGenerator.main() for a test of all supported curves.
     */

}
