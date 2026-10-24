package net.i2p.crypto;

import java.lang.reflect.Constructor;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.Security;
import java.security.spec.ECField;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.EllipticCurve;
import net.i2p.util.NativeBigInteger;

/**
 * Constants for elliptic curves, from NIST FIPS 186-4 (2013) / ANSI X9.62.
 *
 * @since 0.9.9
 */
final class ECConstants {

    /**
     * Constructor. The curve, domain and provider lookups below all run in the
     * static initializer, so an instance holds nothing.
     */
    ECConstants() {}

    /** True to log the parameter verification. */
    private static final boolean DEBUG = false;

    /**
     * Logs a debug message.
     *
     * @param s the message to log
     */
    private static void log(String s) {
        log(s, null);
    }

    /**
     * Logs a debug message with a cause.
     *
     * @param s the message to log
     * @param t the error to log with the message
     */
    private static void log(String s, Throwable t) {
        if (DEBUG) {
            System.out.println("ECConstants: " + s);
            if (t != null)
                t.printStackTrace();
        }
    }

    /** True when Bouncy Castle is present. */
    private static final boolean BC_AVAILABLE;

    static {
        boolean loaded;
        if (Security.getProvider("BC") == null) {
            try {
                Class<?> cls = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider");
                Constructor<?> con = cls.getConstructor();
                Provider bc = (Provider)con.newInstance();
                Security.addProvider(bc);
                log("Added BC provider");
                loaded = true;
            } catch (Exception e) {
                log("Unable to add BC provider", e);
                loaded = false;
            }
        } else {
            log("BC provider already loaded");
            loaded = true;
        }
        BC_AVAILABLE = loaded;
    }

    /**
     * Return whether Bouncy Castle is available.
     * @return whether BC available
     */
    public static boolean isBCAvailable() { return BC_AVAILABLE; }

    /** The parameters of one elliptic curve. */
    private static class ECParms {
        /**
         * Prime modulus p.
         */
        public final String ps;
        /**
         * Order n.
         */
        public final String ns;
        /**
         * Seed for the SHA-1 based generation algorithm.
         */
        public final String ss;
        /**
         * Coefficient b.
         */
        public final String bs;
        /**
         * Base point x coordinate (Gx).
         */
        public final String gxs;
        /**
         * Base point y coordinate (Gy).
         */
        public final String gys;
        /** The A coefficient of the curve, minus three. */
        private static final BigInteger A = new NativeBigInteger("-3");
        /** The cofactor of the curve, one. */
        private static final int H = 1;

        /**
         * P and N in decimal, no spaces;
         * Seed, B, Gx, Gy in hex, spaces allowed.
         *
         * @param pss prime p, decimal
         * @param nss order n of the base point, decimal
         * @param sss curve seed, hex
         * @param bss curve parameter b, hex
         * @param gxss x coordinate of the base point, hex
         * @param gyss y coordinate of the base point, hex
         */
        public ECParms(String pss, String nss, String sss, String bss, String gxss, String gyss) {
            ps = pss; ns = nss; ss = sss; bs = bss; gxs = gxss; gys = gyss;
        }

        /**
         * Generate the EC parameter spec.
         *
         * @return the EC parameter spec
         */
        public ECParameterSpec genSpec() {
            BigInteger pb = new NativeBigInteger(ps);
            BigInteger nb = new NativeBigInteger(ns);
            BigInteger sb = new NativeBigInteger(ss.replace(" ", ""), 16);
            BigInteger bb = new NativeBigInteger(bs.replace(" ", ""), 16);
            BigInteger gxb = new NativeBigInteger(gxs.replace(" ", ""), 16);
            BigInteger gyb = new NativeBigInteger(gys.replace(" ", ""), 16);
            BigInteger ab = new NativeBigInteger(A.mod(pb));
            ECField field = new ECFieldFp(pb);
            EllipticCurve curve = new EllipticCurve(field, ab, bb, sb.toByteArray());
            ECPoint g = new ECPoint(gxb, gyb);
            return new ECParameterSpec(curve, g, nb, H);
        }
    }






    /** The parameters of the P-256 curve. */
    private static final ECParms PARM_P256 = new ECParms(
        // P N Seed B Gx Gy
            "1157920892103562487626974469494075735300861434152903141955" +
            "33631308867097853951",
            "115792089210356248762697446949407573529996955224135760342" +
            "422259061068512044369",
            "c49d3608 86e70493 6a6678e1 139d26b7 819f7e90",
            "5ac635d8 aa3a93e7 b3ebbd55 769886bc 651d06b0 cc53b0f6" +
            "3bce3c3e 27d2604b",
            "6b17d1f2 e12c4247 f8bce6e5 63a440f2 77037d81 2deb33a0" +
            "f4a13945 d898c296",
            "4fe342e2 fe1a7f9b 8ee7eb4a 7c0f9e16 2bce3357 6b315ece" +
            "cbb64068 37bf51f5"
    );


    /** The parameters of the P-384 curve. */
    private static final ECParms PARM_P384 = new ECParms(
        // P N Seed B Gx Gy
            "3940200619639447921227904010014361380507973927046544666794" +
            "8293404245721771496870329047266088258938001861606973112319",
            "3940200619639447921227904010014361380507973927046544666794" +
            "6905279627659399113263569398956308152294913554433653942643",
            "a335926a a319a27a 1d00896a 6773a482 7acdac73",
            "b3312fa7 e23ee7e4 988e056b e3f82d19 181d9c6e fe814112" +
            "0314088f 5013875a c656398d 8a2ed19d 2a85c8ed d3ec2aef",
            "aa87ca22 be8b0537 8eb1c71e f320ad74 6e1d3b62 8ba79b98" +
            "59f741e0 82542a38 5502f25d bf55296c 3a545e38 72760ab7",
            "3617de4a 96262c6f 5d9e98bf 9292dc29 f8f41dbd 289a147c" +
            "e9da3113 b5f0b8c0 0a60b1ce 1d7e819d 7a431d7c 90ea0e5f"
    );


    /** The parameters of the P-521 curve. */
    private static final ECParms PARM_P521 = new ECParms(
            "686479766013060971498190079908139321726943530014330540939" +
            "446345918554318339765605212255964066145455497729631139148" +
            "0858037121987999716643812574028291115057151",
            "686479766013060971498190079908139321726943530014330540939" +
            "446345918554318339765539424505774633321719753296399637136" +
            "3321113864768612440380340372808892707005449",
            "d09e8800 291cb853 96cc6717 393284aa a0da64ba",
            "051 953eb961 8e1c9a1f 929a21a0 b68540ee a2da725b" +
            "99b315f3 b8b48991 8ef109e1 56193951 ec7e937b 1652c0bd" +
            "3bb1bf07 3573df88 3d2c34f1 ef451fd4 6b503f00",
            "c6 858e06b7 0404e9cd 9e3ecb66 2395b442 9c648139" +
            "053fb521 f828af60 6b4d3dba a14b5e77 efe75928 fe1dc127" +
            "a2ffa8de 3348b3c1 856a429b f97e7e31 c2e5bd66",
            "118 39296a78 9a3bc004 5c8a5fb4 2c7d1bd9 98f54449" +
            "579b4468 17afbd17 273e662c 97ee7299 5ef42640 c550b901" +
            "3fad0761 353c7086 a272c240 88be9476 9fd16650"
    );

    /**
     * Generate a spec from a curve name.
     *
     * @param name the standard curve name, e.g. secp256r1
     * @return null if fail
     */
    private static ECParameterSpec genSpec(String name) {
        // convert the ECGenParameterSpecs to ECParameterSpecs for several reasons:
        // 1) to check availability
        // 2) efficiency
        // 3) SigUtil must cast the AlgorithmParameterSpec to a ECParameterSpec
        //    to convert a I2P key to a Java key. Sadly, a ECGenParameterSpec
        //    is not a ECParameterSpec.
        try {
            AlgorithmParameters ap;
            try {
                ap = AlgorithmParameters.getInstance("EC");
            } catch (GeneralSecurityException e) {
                if (BC_AVAILABLE) {
                    log("Named curve " + name + " is not available, trying BC", e);
                    ap = AlgorithmParameters.getInstance("EC", "BC");
                    log("Fallback to BC worked for named curve " + name);
                } else {
                    throw e;
                }
            }
            ECGenParameterSpec ecgps = new ECGenParameterSpec(name);
            ap.init(ecgps);
            ECParameterSpec rv = ap.getParameterSpec(ECParameterSpec.class);
            log("Named curve " + name + " loaded");
            return rv;
        } catch (GeneralSecurityException e) {
            log("Named curve " + name + " is not available", e);
            return null;
        }
    }

    /**
     * Tries curve name1, then name2, then creates new from parms.
     *
     * @param name1 the first standard curve name to try
     * @param name2 null to skip
     * @param parms null to skip
     * @return null if all fail
     */
    private static ECParameterSpec genSpec(String name1, String name2, ECParms parms) {
        ECParameterSpec rv = genSpec(name1);
        if (rv == null && name2 != null) {
            rv = genSpec(name2);
            if (rv == null && parms != null) {
                rv = parms.genSpec();
                if (rv != null)
                    log("Curve " + name2 + " created");
            }
        }
        return rv;
    }

    /**
     * P256_SPEC.
     */
    public static final ECParameterSpec P256_SPEC = genSpec("secp256r1", "P-256", PARM_P256);
    /**
     * P384_SPEC.
     */
    public static final ECParameterSpec P384_SPEC = genSpec("secp384r1", "P-384", PARM_P384);
    /**
     * P521_SPEC.
     */
    public static final ECParameterSpec P521_SPEC = genSpec("secp521r1", "P-521", PARM_P521);


    /**
     * There is no {@link java.security.spec.ECParameterSpec#equals}.
     * Needed to load family keys on Android via {@link net.i2p.crypto.SigUtil#fromJavaKey}.
     *
     * @param s1 the spec being compared; null is tolerated only if {@code s2} is also null
     * @param s2 the spec being compared; null is tolerated only if {@code s1} is also null
     * @return true if equal
     * @since 0.9.55
     */
    public static boolean equals(ECParameterSpec s1, ECParameterSpec s2) {
        if (s1 == s2)
            return true;
        // do this field by field, nothing has equals()
        // but the BigIntegers, however they do have defined hashcodes
        return s1.getCofactor() == s2.getCofactor() &&                          // int
               s1.getCurve().hashCode() == s2.getCurve().hashCode() &&          // EllipticCurve
               s1.getGenerator().hashCode() == s2.getGenerator().hashCode() &&  // ECPoint
               s1.getOrder().equals(s2.getOrder());                             // BigInteger
    }
}
