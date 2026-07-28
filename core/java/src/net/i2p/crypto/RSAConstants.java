package net.i2p.crypto;

import net.i2p.util.NativeBigInteger;

import java.math.BigInteger;
import java.security.spec.RSAKeyGenParameterSpec;

/**
 * Constants for RSA
 *
 * @since 0.9.9
 */
final class RSAConstants {

    /**
     * Parameter spec.
     *  @return the parameter spec
     */
    private static RSAKeyGenParameterSpec genSpec(int size, BigInteger exp) {
        return new RSAKeyGenParameterSpec(size, exp);
    }

    private static final BigInteger F4 = new NativeBigInteger(RSAKeyGenParameterSpec.F4);

    /** RSA 1024-bit with F4 exponent */
    public static final RSAKeyGenParameterSpec F4_1024_SPEC = genSpec(1024, F4);
    /** RSA 2048-bit with F4 exponent */
    public static final RSAKeyGenParameterSpec F4_2048_SPEC = genSpec(2048, F4);
    /** RSA 3072-bit with F4 exponent */
    public static final RSAKeyGenParameterSpec F4_3072_SPEC = genSpec(3072, F4);
    /** RSA 4096-bit with F4 exponent */
    public static final RSAKeyGenParameterSpec F4_4096_SPEC = genSpec(4096, F4);
}
