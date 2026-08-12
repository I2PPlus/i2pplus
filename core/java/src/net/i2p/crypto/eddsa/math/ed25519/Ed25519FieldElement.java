package net.i2p.crypto.eddsa.math.ed25519;

import net.i2p.crypto.eddsa.Utils;
import net.i2p.crypto.eddsa.math.*;

import java.util.Arrays;

/**
 * Class to represent a field element of the finite field $p = 2^{255} - 19$ elements.
 * <p>
 * An element $t$, entries $t[0] \dots t[9]$, represents the integer
 * $t[0]+2^{26} t[1]+2^{51} t[2]+2^{77} t[3]+2^{102} t[4]+\dots+2^{230} t[9]$.
 * Bounds on each $t[i]$ vary depending on context.
 * <p>
 * Reviewed/commented by Bloody Rookie (nemproject@gmx.de)
 */
public class Ed25519FieldElement extends FieldElement {
    /**
     * Variable is package private for encoding.
     */
    final int[] t;

    /**
     * Creates a field element.
     *
     * @param f The underlying field, must be the finite field with $p = 2^{255} - 19$ elements
     * @param t The $2^{25.5}$ bit representation of the field element.
     */
    public Ed25519FieldElement(Field f, int[] t) {
        super(f);
        if (t.length != 10) throw new IllegalArgumentException("Invalid radix-2^51 representation");
        this.t = t;
    }

    /**
     *  Copy the source field element's data into this element's array.
     *  Package-private accumulator support — allows reuse of a single
     *  field element instance across multiple operations instead of
     *  allocating a new int[10] + wrapper for each op.
     *
     *  @param source the field element to copy from
     *  @since 0.9.71+
     */
    void set(Ed25519FieldElement source) {
        System.arraycopy(source.t, 0, t, 0, 10);
    }

    /**
     *  Add a field element into this element's array in place.
     *  Package-private accumulator support.
     *
     *  @param val the field element to add
     *  @since 0.9.71+
     */
    void addInPlace(Ed25519FieldElement val) {
        int[] g = val.t;
        for (int i = 0; i < 10; i++) {t[i] += g[i];}
    }

    /**
     *  Subtract a field element from this element's array in place.
     *  Package-private accumulator support.
     *
     *  @param val the field element to subtract
     *  @since 0.9.71+
     */
    void subInPlace(Ed25519FieldElement val) {
        int[] g = val.t;
        for (int i = 0; i < 10; i++) {t[i] -= g[i];}
    }

    /**
     *  Return a new field element with a copy of this element's data.
     *  Package-private accumulator support — call this to extract the
     *  final result after a sequence of in-place operations.
     *
     *  @return a new Ed25519FieldElement with a copy of the data
     *  @since 0.9.71+
     */
    Ed25519FieldElement toFieldElement() {
        int[] copy = new int[10];
        System.arraycopy(t, 0, copy, 0, 10);
        return new Ed25519FieldElement(f, copy);
    }

    private static final byte[] ZERO = new byte[32];

    /**
     * Whether or not the field element is non-zero.
     *
     * @return 1 if it is non-zero, 0 otherwise.
     */
    @Override
    public boolean isNonZero() {
        final byte[] s = toByteArray();
        return Utils.equal(s, ZERO) == 0;
    }

    /**
     * $h = f + g$
     * <p>
     * TODO-CR BR: $h$ is allocated via new, probably not a good idea. Do we need the copying into temp variables if we do that?
     * <p>
     * Preconditions:
     * </p><ul>
     * <li>$|f|$ bounded by $1.1*2^{25},1.1*2^{24},1.1*2^{25},1.1*2^{24},$ etc.
     * <li>$|g|$ bounded by $1.1*2^{25},1.1*2^{24},1.1*2^{25},1.1*2^{24},$ etc.
     * </ul><p>
     * Postconditions:
     * </p><ul>
     * <li>$|h|$ bounded by $1.1*2^{26},1.1*2^{25},1.1*2^{26},1.1*2^{25},$ etc.
     * </ul>
     *
     * @param val The field element to add.
     * @return The field element this + val.
     */
    @Override
    public FieldElement add(FieldElement val) {
        int[] g = ((Ed25519FieldElement) val).t;
        int[] h = new int[10];
        for (int i = 0; i < 10; i++) {
            h[i] = t[i] + g[i];
        }
        return new Ed25519FieldElement(f, h);
    }

    /**
     * $h = f - g$
     * <p>
     * Can overlap $h$ with $f$ or $g$.
     * <p>
     * TODO-CR BR: See above.
     * <p>
     * Preconditions:
     * </p><ul>
     * <li>$|f|$ bounded by $1.1*2^{25},1.1*2^{24},1.1*2^{25},1.1*2^{24},$ etc.
     * <li>$|g|$ bounded by $1.1*2^{25},1.1*2^{24},1.1*2^{25},1.1*2^{24},$ etc.
     * </ul><p>
     * Postconditions:
     * </p><ul>
     * <li>$|h|$ bounded by $1.1*2^{26},1.1*2^{25},1.1*2^{26},1.1*2^{25},$ etc.
     * </ul>
     *
     * @param val The field element to subtract.
     * @return The field element this - val.
     */

    @Override
    public FieldElement subtract(FieldElement val) {
        int[] g = ((Ed25519FieldElement) val).t;
        int[] h = new int[10];
        for (int i = 0; i < 10; i++) {
            h[i] = t[i] - g[i];
        }
        return new Ed25519FieldElement(f, h);
    }

    /**
     * $h = -f$
     * <p>
     * TODO-CR BR: see above.
     * <p>
     * Preconditions:
     * </p><ul>
     * <li>$|f|$ bounded by $1.1*2^{25},1.1*2^{24},1.1*2^{25},1.1*2^{24},$ etc.
     * </ul><p>
     * Postconditions:
     * </p><ul>
     * <li>$|h|$ bounded by $1.1*2^{25},1.1*2^{24},1.1*2^{25},1.1*2^{24},$ etc.
     * </ul>
     *
     * @return The field element (-1) * this.
     */
    @Override
    public FieldElement negate() {
        int[] h = new int[10];
        for (int i = 0; i < 10; i++) {
            h[i] = -t[i];
        }
        return new Ed25519FieldElement(f, h);
    }

    /**
     * $h = f * g$
     * <p>
     * Can overlap $h$ with $f$ or $g$.
     * <p>
     * Preconditions:
     * </p><ul>
     * <li>$|f|$ bounded by
     * $1.65*2^{26},1.65*2^{25},1.65*2^{26},1.65*2^{25},$ etc.
     * <li>$|g|$ bounded by
     * $1.65*2^{26},1.65*2^{25},1.65*2^{26},1.65*2^{25},$ etc.
     * </ul><p>
     * Postconditions:
     * </p><ul>
     * <li>$|h|$ bounded by
     * $1.01*2^{25},1.01*2^{24},1.01*2^{25},1.01*2^{24},$ etc.
     * </ul><p>
     * Notes on implementation strategy:
     * <p>
     * Using schoolbook multiplication. Karatsuba would save a little in some
     * cost models.
     * <p>
     * Most multiplications by 2 and 19 are 32-bit precomputations; cheaper than
     * 64-bit postcomputations.
     * <p>
     * There is one remaining multiplication by 19 in the carry chain; one *19
     * precomputation can be merged into this, but the resulting data flow is
     * considerably less clean.
     * <p>
     * There are 12 carries below. 10 of them are 2-way parallelizable and
     * vectorizable. Can get away with 11 carries, but then data flow is much
     * deeper.
     * <p>
     * With tighter constraints on inputs can squeeze carries into int32.
     *
     * @param val The field element to multiply.
     * @return The (reasonably reduced) field element this * val.
     */
    @Override
    public FieldElement multiply(FieldElement val) {
        int[] g = ((Ed25519FieldElement) val).t;
        int g119 = 19 * g[1]; /* 1.959375*2^29 */
        int g219 = 19 * g[2]; /* 1.959375*2^30; still ok */
        int g319 = 19 * g[3];
        int g419 = 19 * g[4];
        int g519 = 19 * g[5];
        int g619 = 19 * g[6];
        int g719 = 19 * g[7];
        int g819 = 19 * g[8];
        int g919 = 19 * g[9];
        int f12 = 2 * t[1];
        int f32 = 2 * t[3];
        int f52 = 2 * t[5];
        int f72 = 2 * t[7];
        int f92 = 2 * t[9];
        long f0g0 = t[0] * (long) g[0];
        long f0g1 = t[0] * (long) g[1];
        long f0g2 = t[0] * (long) g[2];
        long f0g3 = t[0] * (long) g[3];
        long f0g4 = t[0] * (long) g[4];
        long f0g5 = t[0] * (long) g[5];
        long f0g6 = t[0] * (long) g[6];
        long f0g7 = t[0] * (long) g[7];
        long f0g8 = t[0] * (long) g[8];
        long f0g9 = t[0] * (long) g[9];
        long f1g0 = t[1] * (long) g[0];
        long f1g12 = f12 * (long) g[1];
        long f1g2 = t[1] * (long) g[2];
        long f1g32 = f12 * (long) g[3];
        long f1g4 = t[1] * (long) g[4];
        long f1g52 = f12 * (long) g[5];
        long f1g6 = t[1] * (long) g[6];
        long f1g72 = f12 * (long) g[7];
        long f1g8 = t[1] * (long) g[8];
        long f1g938 = f12 * (long) g919;
        long f2g0 = t[2] * (long) g[0];
        long f2g1 = t[2] * (long) g[1];
        long f2g2 = t[2] * (long) g[2];
        long f2g3 = t[2] * (long) g[3];
        long f2g4 = t[2] * (long) g[4];
        long f2g5 = t[2] * (long) g[5];
        long f2g6 = t[2] * (long) g[6];
        long f2g7 = t[2] * (long) g[7];
        long f2g819 = t[2] * (long) g819;
        long f2g919 = t[2] * (long) g919;
        long f3g0 = t[3] * (long) g[0];
        long f3g12 = f32 * (long) g[1];
        long f3g2 = t[3] * (long) g[2];
        long f3g32 = f32 * (long) g[3];
        long f3g4 = t[3] * (long) g[4];
        long f3g52 = f32 * (long) g[5];
        long f3g6 = t[3] * (long) g[6];
        long f3g738 = f32 * (long) g719;
        long f3g819 = t[3] * (long) g819;
        long f3g938 = f32 * (long) g919;
        long f4g0 = t[4] * (long) g[0];
        long f4g1 = t[4] * (long) g[1];
        long f4g2 = t[4] * (long) g[2];
        long f4g3 = t[4] * (long) g[3];
        long f4g4 = t[4] * (long) g[4];
        long f4g5 = t[4] * (long) g[5];
        long f4g619 = t[4] * (long) g619;
        long f4g719 = t[4] * (long) g719;
        long f4g819 = t[4] * (long) g819;
        long f4g919 = t[4] * (long) g919;
        long f5g0 = t[5] * (long) g[0];
        long f5g12 = f52 * (long) g[1];
        long f5g2 = t[5] * (long) g[2];
        long f5g32 = f52 * (long) g[3];
        long f5g4 = t[5] * (long) g[4];
        long f5g538 = f52 * (long) g519;
        long f5g619 = t[5] * (long) g619;
        long f5g738 = f52 * (long) g719;
        long f5g819 = t[5] * (long) g819;
        long f5g938 = f52 * (long) g919;
        long f6g0 = t[6] * (long) g[0];
        long f6g1 = t[6] * (long) g[1];
        long f6g2 = t[6] * (long) g[2];
        long f6g3 = t[6] * (long) g[3];
        long f6g419 = t[6] * (long) g419;
        long f6g519 = t[6] * (long) g519;
        long f6g619 = t[6] * (long) g619;
        long f6g719 = t[6] * (long) g719;
        long f6g819 = t[6] * (long) g819;
        long f6g919 = t[6] * (long) g919;
        long f7g0 = t[7] * (long) g[0];
        long f7g12 = f72 * (long) g[1];
        long f7g2 = t[7] * (long) g[2];
        long f7g338 = f72 * (long) g319;
        long f7g419 = t[7] * (long) g419;
        long f7g538 = f72 * (long) g519;
        long f7g619 = t[7] * (long) g619;
        long f7g738 = f72 * (long) g719;
        long f7g819 = t[7] * (long) g819;
        long f7g938 = f72 * (long) g919;
        long f8g0 = t[8] * (long) g[0];
        long f8g1 = t[8] * (long) g[1];
        long f8g219 = t[8] * (long) g219;
        long f8g319 = t[8] * (long) g319;
        long f8g419 = t[8] * (long) g419;
        long f8g519 = t[8] * (long) g519;
        long f8g619 = t[8] * (long) g619;
        long f8g719 = t[8] * (long) g719;
        long f8g819 = t[8] * (long) g819;
        long f8g919 = t[8] * (long) g919;
        long f9g0 = t[9] * (long) g[0];
        long f9g138 = f92 * (long) g119;
        long f9g219 = t[9] * (long) g219;
        long f9g338 = f92 * (long) g319;
        long f9g419 = t[9] * (long) g419;
        long f9g538 = f92 * (long) g519;
        long f9g619 = t[9] * (long) g619;
        long f9g738 = f92 * (long) g719;
        long f9g819 = t[9] * (long) g819;
        long f9g938 = f92 * (long) g919;

        /**
         * Remember: 2^255 congruent 19 modulo p.
         * h = h0 * 2^0 + h1 * 2^26 + h2 * 2^(26+25) + h3 * 2^(26+25+26) + ... + h9 * 2^(5*26+5*25).
         * So to get the real number we would have to multiply the coefficients with the corresponding powers of 2.
         * To get an idea what is going on below, look at the calculation of h0:
         * h0 is the coefficient to the power 2^0 so it collects (sums) all products that have the power 2^0.
         * f0 * g0 really is f0 * 2^0 * g0 * 2^0 = (f0 * g0) * 2^0.
         * f1 * g9 really is f1 * 2^26 * g9 * 2^230 = f1 * g9 * 2^256 = 2 * f1 * g9 * 2^255 congruent 2 * 19 * f1 * g9 * 2^0 modulo p.
         * f2 * g8 really is f2 * 2^51 * g8 * 2^204 = f2 * g8 * 2^255 congruent 19 * f2 * g8 * 2^0 modulo p.
         * and so on...
         */
        long h0 = f0g0 + f1g938 + f2g819 + f3g738 + f4g619 + f5g538 + f6g419 + f7g338 + f8g219 + f9g138;
        long h1 = f0g1 + f1g0 + f2g919 + f3g819 + f4g719 + f5g619 + f6g519 + f7g419 + f8g319 + f9g219;
        long h2 = f0g2 + f1g12 + f2g0 + f3g938 + f4g819 + f5g738 + f6g619 + f7g538 + f8g419 + f9g338;
        long h3 = f0g3 + f1g2 + f2g1 + f3g0 + f4g919 + f5g819 + f6g719 + f7g619 + f8g519 + f9g419;
        long h4 = f0g4 + f1g32 + f2g2 + f3g12 + f4g0 + f5g938 + f6g819 + f7g738 + f8g619 + f9g538;
        long h5 = f0g5 + f1g4 + f2g3 + f3g2 + f4g1 + f5g0 + f6g919 + f7g819 + f8g719 + f9g619;
        long h6 = f0g6 + f1g52 + f2g4 + f3g32 + f4g2 + f5g12 + f6g0 + f7g938 + f8g819 + f9g738;
        long h7 = f0g7 + f1g6 + f2g5 + f3g4 + f4g3 + f5g2 + f6g1 + f7g0 + f8g919 + f9g819;
        long h8 = f0g8 + f1g72 + f2g6 + f3g52 + f4g4 + f5g32 + f6g2 + f7g12 + f8g0 + f9g938;
        long h9 = f0g9 + f1g8 + f2g7 + f3g6 + f4g5 + f5g4 + f6g3 + f7g2 + f8g1 + f9g0;
        long carry0;
        long carry1;
        long carry2;
        long carry3;
        long carry4;
        long carry5;
        long carry6;
        long carry7;
        long carry8;
        long carry9;

        /*
        |h0| <= (1.65*1.65*2^52*(1+19+19+19+19)+1.65*1.65*2^50*(38+38+38+38+38)) i.e. |h0| <= 1.4*2^60; narrower ranges for h2, h4, h6, h8
        |h1| <= (1.65*1.65*2^51*(1+1+19+19+19+19+19+19+19+19)) i.e. |h1| <= 1.7*2^59; narrower ranges for h3, h5, h7, h9
        */

        carry0 = (h0 +  (1 << 25)) >> 26;
        h1 += carry0;
        h0 -= carry0 << 26;
        carry4 = (h4 +  (1 << 25)) >> 26;
        h5 += carry4;
        h4 -= carry4 << 26;
        /* |h0| <= 2^25 */
        /* |h4| <= 2^25 */
        /* |h1| <= 1.71*2^59 */
        /* |h5| <= 1.71*2^59 */

        carry1 = (h1 +  (1 << 24)) >> 25;
        h2 += carry1;
        h1 -= carry1 << 25;
        carry5 = (h5 +  (1 << 24)) >> 25;
        h6 += carry5;
        h5 -= carry5 << 25;
        /* |h1| <= 2^24; from now on fits into int32 */
        /* |h5| <= 2^24; from now on fits into int32 */
        /* |h2| <= 1.41*2^60 */
        /* |h6| <= 1.41*2^60 */

        carry2 = (h2 +  (1 << 25)) >> 26;
        h3 += carry2;
        h2 -= carry2 << 26;
        carry6 = (h6 +  (1 << 25)) >> 26;
        h7 += carry6;
        h6 -= carry6 << 26;
        /* |h2| <= 2^25; from now on fits into int32 unchanged */
        /* |h6| <= 2^25; from now on fits into int32 unchanged */
        /* |h3| <= 1.71*2^59 */
        /* |h7| <= 1.71*2^59 */

        carry3 = (h3 +  (1 << 24)) >> 25;
        h4 += carry3;
        h3 -= carry3 << 25;
        carry7 = (h7 +  (1 << 24)) >> 25;
        h8 += carry7;
        h7 -= carry7 << 25;
        /* |h3| <= 2^24; from now on fits into int32 unchanged */
        /* |h7| <= 2^24; from now on fits into int32 unchanged */
        /* |h4| <= 1.72*2^34 */
        /* |h8| <= 1.41*2^60 */

        carry4 = (h4 +  (1 << 25)) >> 26;
        h5 += carry4;
        h4 -= carry4 << 26;
        carry8 = (h8 +  (1 << 25)) >> 26;
        h9 += carry8;
        h8 -= carry8 << 26;
        /* |h4| <= 2^25; from now on fits into int32 unchanged */
        /* |h8| <= 2^25; from now on fits into int32 unchanged */
        /* |h5| <= 1.01*2^24 */
        /* |h9| <= 1.71*2^59 */

        carry9 = (h9 +  (1 << 24)) >> 25;
        h0 += carry9 * 19;
        h9 -= carry9 << 25;
        /* |h9| <= 2^24; from now on fits into int32 unchanged */
        /* |h0| <= 1.1*2^39 */

        carry0 = (h0 +  (1 << 25)) >> 26;
        h1 += carry0;
        h0 -= carry0 << 26;
        /* |h0| <= 2^25; from now on fits into int32 unchanged */
        /* |h1| <= 1.01*2^24 */

        int[] h = new int[10];
        h[0] = (int) h0;
        h[1] = (int) h1;
        h[2] = (int) h2;
        h[3] = (int) h3;
        h[4] = (int) h4;
        h[5] = (int) h5;
        h[6] = (int) h6;
        h[7] = (int) h7;
        h[8] = (int) h8;
        h[9] = (int) h9;
        return new Ed25519FieldElement(f, h);
    }

    /**
     * $h = f * f$
     * <p>
     * Can overlap $h$ with $f$.
     * <p>
     * Preconditions:
     * </p><ul>
     * <li>$|f|$ bounded by $1.65*2^{26},1.65*2^{25},1.65*2^{26},1.65*2^{25},$ etc.
     * </ul><p>
     * Postconditions:
     * </p><ul>
     * <li>$|h|$ bounded by $1.01*2^{25},1.01*2^{24},1.01*2^{25},1.01*2^{24},$ etc.
     * </ul><p>
     * See {@link #multiply(FieldElement)} for discussion
     * of implementation strategy.
     *
     * @return The (reasonably reduced) square of this field element.
     */
    @Override
    public FieldElement square() {
        int f0 = t[0];
        int f1 = t[1];
        int f2 = t[2];
        int f3 = t[3];
        int f4 = t[4];
        int f5 = t[5];
        int f6 = t[6];
        int f7 = t[7];
        int f8 = t[8];
        int f9 = t[9];
        int f02 = 2 * f0;
        int f12 = 2 * f1;
        int f22 = 2 * f2;
        int f32 = 2 * f3;
        int f42 = 2 * f4;
        int f52 = 2 * f5;
        int f62 = 2 * f6;
        int f72 = 2 * f7;
        int f538 = 38 * f5; /* 1.959375*2^30 */
        int f619 = 19 * f6; /* 1.959375*2^30 */
        int f738 = 38 * f7; /* 1.959375*2^30 */
        int f819 = 19 * f8; /* 1.959375*2^30 */
        int f938 = 38 * f9; /* 1.959375*2^30 */
        long f0f0 = f0 * (long) f0;
        long f0f12 = f02 * (long) f1;
        long f0f22 = f02 * (long) f2;
        long f0f32 = f02 * (long) f3;
        long f0f42 = f02 * (long) f4;
        long f0f52 = f02 * (long) f5;
        long f0f62 = f02 * (long) f6;
        long f0f72 = f02 * (long) f7;
        long f0f82 = f02 * (long) f8;
        long f0f92 = f02 * (long) f9;
        long f1f12 = f12 * (long) f1;
        long f1f22 = f12 * (long) f2;
        long f1f34 = f12 * (long) f32;
        long f1f42 = f12 * (long) f4;
        long f1f54 = f12 * (long) f52;
        long f1f62 = f12 * (long) f6;
        long f1f74 = f12 * (long) f72;
        long f1f82 = f12 * (long) f8;
        long f1f976 = f12 * (long) f938;
        long f2f2 = f2 * (long) f2;
        long f2f32 = f22 * (long) f3;
        long f2f42 = f22 * (long) f4;
        long f2f52 = f22 * (long) f5;
        long f2f62 = f22 * (long) f6;
        long f2f72 = f22 * (long) f7;
        long f2f838 = f22 * (long) f819;
        long f2f938 = f2 * (long) f938;
        long f3f32 = f32 * (long) f3;
        long f3f42 = f32 * (long) f4;
        long f3f54 = f32 * (long) f52;
        long f3f62 = f32 * (long) f6;
        long f3f776 = f32 * (long) f738;
        long f3f838 = f32 * (long) f819;
        long f3f976 = f32 * (long) f938;
        long f4f4 = f4 * (long) f4;
        long f4f52 = f42 * (long) f5;
        long f4f638 = f42 * (long) f619;
        long f4f738 = f4 * (long) f738;
        long f4f838 = f42 * (long) f819;
        long f4f938 = f4 * (long) f938;
        long f5f538 = f5 * (long) f538;
        long f5f638 = f52 * (long) f619;
        long f5f776 = f52 * (long) f738;
        long f5f838 = f52 * (long) f819;
        long f5f976 = f52 * (long) f938;
        long f6f619 = f6 * (long) f619;
        long f6f738 = f6 * (long) f738;
        long f6f838 = f62 * (long) f819;
        long f6f938 = f6 * (long) f938;
        long f7f738 = f7 * (long) f738;
        long f7f838 = f72 * (long) f819;
        long f7f976 = f72 * (long) f938;
        long f8f819 = f8 * (long) f819;
        long f8f938 = f8 * (long) f938;
        long f9f938 = f9 * (long) f938;

        /**
         * Same procedure as in multiply, but this time we have a higher symmetry leading to less summands.
         * e.g. f1f9_76 really stands for f1 * 2^26 * f9 * 2^230 + f9 * 2^230 + f1 * 2^26 congruent 2 * 2 * 19 * f1 * f9  2^0 modulo p.
         */
        long h0 = f0f0 + f1f976 + f2f838 + f3f776 + f4f638 + f5f538;
        long h1 = f0f12 + f2f938 + f3f838 + f4f738 + f5f638;
        long h2 = f0f22 + f1f12 + f3f976 + f4f838 + f5f776 + f6f619;
        long h3 = f0f32 + f1f22 + f4f938 + f5f838 + f6f738;
        long h4 = f0f42 + f1f34 + f2f2 + f5f976 + f6f838 + f7f738;
        long h5 = f0f52 + f1f42 + f2f32 + f6f938 + f7f838;
        long h6 = f0f62 + f1f54 + f2f42 + f3f32 + f7f976 + f8f819;
        long h7 = f0f72 + f1f62 + f2f52 + f3f42 + f8f938;
        long h8 = f0f82 + f1f74 + f2f62 + f3f54 + f4f4 + f9f938;
        long h9 = f0f92 + f1f82 + f2f72 + f3f62 + f4f52;
        long carry0;
        long carry1;
        long carry2;
        long carry3;
        long carry4;
        long carry5;
        long carry6;
        long carry7;
        long carry8;
        long carry9;

        carry0 = (h0 +  (1 << 25)) >> 26;
        h1 += carry0;
        h0 -= carry0 << 26;
        carry4 = (h4 +  (1 << 25)) >> 26;
        h5 += carry4;
        h4 -= carry4 << 26;

        carry1 = (h1 +  (1 << 24)) >> 25;
        h2 += carry1;
        h1 -= carry1 << 25;
        carry5 = (h5 +  (1 << 24)) >> 25;
        h6 += carry5;
        h5 -= carry5 << 25;

        carry2 = (h2 +  (1 << 25)) >> 26;
        h3 += carry2;
        h2 -= carry2 << 26;
        carry6 = (h6 +  (1 << 25)) >> 26;
        h7 += carry6;
        h6 -= carry6 << 26;

        carry3 = (h3 +  (1 << 24)) >> 25;
        h4 += carry3;
        h3 -= carry3 << 25;
        carry7 = (h7 +  (1 << 24)) >> 25;
        h8 += carry7;
        h7 -= carry7 << 25;

        carry4 = (h4 +  (1 << 25)) >> 26;
        h5 += carry4;
        h4 -= carry4 << 26;
        carry8 = (h8 +  (1 << 25)) >> 26;
        h9 += carry8;
        h8 -= carry8 << 26;

        carry9 = (h9 +  (1 << 24)) >> 25;
        h0 += carry9 * 19;
        h9 -= carry9 << 25;

        carry0 = (h0 +  (1 << 25)) >> 26;
        h1 += carry0;
        h0 -= carry0 << 26;

        int[] h = new int[10];
        h[0] = (int) h0;
        h[1] = (int) h1;
        h[2] = (int) h2;
        h[3] = (int) h3;
        h[4] = (int) h4;
        h[5] = (int) h5;
        h[6] = (int) h6;
        h[7] = (int) h7;
        h[8] = (int) h8;
        h[9] = (int) h9;
        return new Ed25519FieldElement(f, h);
    }

    /**
     * $h = 2 * f * f$
     * <p>
     * Can overlap $h$ with $f$.
     * <p>
     * Preconditions:
     * </p><ul>
     * <li>$|f|$ bounded by $1.65*2^{26},1.65*2^{25},1.65*2^{26},1.65*2^{25},$ etc.
     * </ul><p>
     * Postconditions:
     * </p><ul>
     * <li>$|h|$ bounded by $1.01*2^{25},1.01*2^{24},1.01*2^{25},1.01*2^{24},$ etc.
     * </ul><p>
     * See {@link #multiply(FieldElement)} for discussion
     * of implementation strategy.
     *
     * @return The (reasonably reduced) square of this field element times 2.
     */
    @Override
    public FieldElement squareAndDouble() {
        int f0 = t[0];
        int f1 = t[1];
        int f2 = t[2];
        int f3 = t[3];
        int f4 = t[4];
        int f5 = t[5];
        int f6 = t[6];
        int f7 = t[7];
        int f8 = t[8];
        int f9 = t[9];
        int f02 = 2 * f0;
        int f12 = 2 * f1;
        int f22 = 2 * f2;
        int f32 = 2 * f3;
        int f42 = 2 * f4;
        int f52 = 2 * f5;
        int f62 = 2 * f6;
        int f72 = 2 * f7;
        int f538 = 38 * f5; /* 1.959375*2^30 */
        int f619 = 19 * f6; /* 1.959375*2^30 */
        int f738 = 38 * f7; /* 1.959375*2^30 */
        int f819 = 19 * f8; /* 1.959375*2^30 */
        int f938 = 38 * f9; /* 1.959375*2^30 */
        long f0f0 = f0 * (long) f0;
        long f0f12 = f02 * (long) f1;
        long f0f22 = f02 * (long) f2;
        long f0f32 = f02 * (long) f3;
        long f0f42 = f02 * (long) f4;
        long f0f52 = f02 * (long) f5;
        long f0f62 = f02 * (long) f6;
        long f0f72 = f02 * (long) f7;
        long f0f82 = f02 * (long) f8;
        long f0f92 = f02 * (long) f9;
        long f1f12 = f12 * (long) f1;
        long f1f22 = f12 * (long) f2;
        long f1f34 = f12 * (long) f32;
        long f1f42 = f12 * (long) f4;
        long f1f54 = f12 * (long) f52;
        long f1f62 = f12 * (long) f6;
        long f1f74 = f12 * (long) f72;
        long f1f82 = f12 * (long) f8;
        long f1f976 = f12 * (long) f938;
        long f2f2 = f2 * (long) f2;
        long f2f32 = f22 * (long) f3;
        long f2f42 = f22 * (long) f4;
        long f2f52 = f22 * (long) f5;
        long f2f62 = f22 * (long) f6;
        long f2f72 = f22 * (long) f7;
        long f2f838 = f22 * (long) f819;
        long f2f938 = f2 * (long) f938;
        long f3f32 = f32 * (long) f3;
        long f3f42 = f32 * (long) f4;
        long f3f54 = f32 * (long) f52;
        long f3f62 = f32 * (long) f6;
        long f3f776 = f32 * (long) f738;
        long f3f838 = f32 * (long) f819;
        long f3f976 = f32 * (long) f938;
        long f4f4 = f4 * (long) f4;
        long f4f52 = f42 * (long) f5;
        long f4f638 = f42 * (long) f619;
        long f4f738 = f4 * (long) f738;
        long f4f838 = f42 * (long) f819;
        long f4f938 = f4 * (long) f938;
        long f5f538 = f5 * (long) f538;
        long f5f638 = f52 * (long) f619;
        long f5f776 = f52 * (long) f738;
        long f5f838 = f52 * (long) f819;
        long f5f976 = f52 * (long) f938;
        long f6f619 = f6 * (long) f619;
        long f6f738 = f6 * (long) f738;
        long f6f838 = f62 * (long) f819;
        long f6f938 = f6 * (long) f938;
        long f7f738 = f7 * (long) f738;
        long f7f838 = f72 * (long) f819;
        long f7f976 = f72 * (long) f938;
        long f8f819 = f8 * (long) f819;
        long f8f938 = f8 * (long) f938;
        long f9f938 = f9 * (long) f938;
        long h0 = f0f0 + f1f976 + f2f838 + f3f776 + f4f638 + f5f538;
        long h1 = f0f12 + f2f938 + f3f838 + f4f738 + f5f638;
        long h2 = f0f22 + f1f12 + f3f976 + f4f838 + f5f776 + f6f619;
        long h3 = f0f32 + f1f22 + f4f938 + f5f838 + f6f738;
        long h4 = f0f42 + f1f34 + f2f2 + f5f976 + f6f838 + f7f738;
        long h5 = f0f52 + f1f42 + f2f32 + f6f938 + f7f838;
        long h6 = f0f62 + f1f54 + f2f42 + f3f32 + f7f976 + f8f819;
        long h7 = f0f72 + f1f62 + f2f52 + f3f42 + f8f938;
        long h8 = f0f82 + f1f74 + f2f62 + f3f54 + f4f4 + f9f938;
        long h9 = f0f92 + f1f82 + f2f72 + f3f62 + f4f52;
        long carry0;
        long carry1;
        long carry2;
        long carry3;
        long carry4;
        long carry5;
        long carry6;
        long carry7;
        long carry8;
        long carry9;

        h0 += h0;
        h1 += h1;
        h2 += h2;
        h3 += h3;
        h4 += h4;
        h5 += h5;
        h6 += h6;
        h7 += h7;
        h8 += h8;
        h9 += h9;

        carry0 = (h0 +  (1 << 25)) >> 26;
        h1 += carry0;
        h0 -= carry0 << 26;
        carry4 = (h4 +  (1 << 25)) >> 26;
        h5 += carry4;
        h4 -= carry4 << 26;

        carry1 = (h1 +  (1 << 24)) >> 25;
        h2 += carry1;
        h1 -= carry1 << 25;
        carry5 = (h5 +  (1 << 24)) >> 25;
        h6 += carry5;
        h5 -= carry5 << 25;

        carry2 = (h2 +  (1 << 25)) >> 26;
        h3 += carry2;
        h2 -= carry2 << 26;
        carry6 = (h6 +  (1 << 25)) >> 26;
        h7 += carry6;
        h6 -= carry6 << 26;

        carry3 = (h3 +  (1 << 24)) >> 25;
        h4 += carry3;
        h3 -= carry3 << 25;
        carry7 = (h7 +  (1 << 24)) >> 25;
        h8 += carry7;
        h7 -= carry7 << 25;

        carry4 = (h4 +  (1 << 25)) >> 26;
        h5 += carry4;
        h4 -= carry4 << 26;
        carry8 = (h8 +  (1 << 25)) >> 26;
        h9 += carry8;
        h8 -= carry8 << 26;

        carry9 = (h9 +  (1 << 24)) >> 25;
        h0 += carry9 * 19;
        h9 -= carry9 << 25;

        carry0 = (h0 +  (1 << 25)) >> 26;
        h1 += carry0;
        h0 -= carry0 << 26;

        int[] h = new int[10];
        h[0] = (int) h0;
        h[1] = (int) h1;
        h[2] = (int) h2;
        h[3] = (int) h3;
        h[4] = (int) h4;
        h[5] = (int) h5;
        h[6] = (int) h6;
        h[7] = (int) h7;
        h[8] = (int) h8;
        h[9] = (int) h9;
        return new Ed25519FieldElement(f, h);
    }

    /**
     * Invert this field element.
     * <p>
     * The inverse is found via Fermat's little theorem:<br>
     * $a^p \cong a \mod p$ and therefore $a^{(p-2)} \cong a^{-1} \mod p$
     *
     * @return The inverse of this field element.
     */
    @Override
    public FieldElement invert() {
        FieldElement t0;
        FieldElement t1;
        FieldElement t2;
        FieldElement t3;

        // 2 == 2 * 1
        t0 = square();

        // 4 == 2 * 2
        t1 = t0.square();

        // 8 == 2 * 4
        t1 = t1.square();

        // 9 == 8 + 1
        t1 = multiply(t1);

        // 11 == 9 + 2
        t0 = t0.multiply(t1);

        // 22 == 2 * 11
        t2 = t0.square();

        // 31 == 22 + 9
        t1 = t1.multiply(t2);

        // 2^6 - 2^1
        t2 = t1.square();

        // 2^10 - 2^5
        for (int i = 1; i < 5; ++i) {
            t2 = t2.square();
        }

        // 2^10 - 2^0
        t1 = t2.multiply(t1);

        // 2^11 - 2^1
        t2 = t1.square();

        // 2^20 - 2^10
        for (int i = 1; i < 10; ++i) {
            t2 = t2.square();
        }

        // 2^20 - 2^0
        t2 = t2.multiply(t1);

        // 2^21 - 2^1
        t3 = t2.square();

        // 2^40 - 2^20
        for (int i = 1; i < 20; ++i) {
            t3 = t3.square();
        }

        // 2^40 - 2^0
        t2 = t3.multiply(t2);

        // 2^41 - 2^1
        t2 = t2.square();

        // 2^50 - 2^10
        for (int i = 1; i < 10; ++i) {
            t2 = t2.square();
        }

        // 2^50 - 2^0
        t1 = t2.multiply(t1);

        // 2^51 - 2^1
        t2 = t1.square();

        // 2^100 - 2^50
        for (int i = 1; i < 50; ++i) {
            t2 = t2.square();
        }

        // 2^100 - 2^0
        t2 = t2.multiply(t1);

        // 2^101 - 2^1
        t3 = t2.square();

        // 2^200 - 2^100
        for (int i = 1; i < 100; ++i) {
            t3 = t3.square();
        }

        // 2^200 - 2^0
        t2 = t3.multiply(t2);

        // 2^201 - 2^1
        t2 = t2.square();

        // 2^250 - 2^50
        for (int i = 1; i < 50; ++i) {
            t2 = t2.square();
        }

        // 2^250 - 2^0
        t1 = t2.multiply(t1);

        // 2^251 - 2^1
        t1 = t1.square();

        // 2^255 - 2^5
        for (int i = 1; i < 5; ++i) {
            t1 = t1.square();
        }

        // 2^255 - 21
        return t1.multiply(t0);
    }

    /**
     * This field element to the power of $(2^{252} - 3)$.
     * This is a helper function for calculating the square root.
     * <p>
     * TODO-CR BR: I think it makes sense to have a sqrt function.
     *
     * @return This field element to the power of $(2^{252} - 3)$.
     */
    @Override
    public FieldElement pow22523() {
        FieldElement t0;
        FieldElement t1;
        FieldElement t2;

        // 2 == 2 * 1
        t0 = square();

        // 4 == 2 * 2
        t1 = t0.square();

        // 8 == 2 * 4
        t1 = t1.square();

        // z9 = z1*z8
        t1 = multiply(t1);

        // 11 == 9 + 2
        t0 = t0.multiply(t1);

        // 22 == 2 * 11
        t0 = t0.square();

        // 31 == 22 + 9
        t0 = t1.multiply(t0);

        // 2^6 - 2^1
        t1 = t0.square();

        // 2^10 - 2^5
        for (int i = 1; i < 5; ++i) {
            t1 = t1.square();
        }

        // 2^10 - 2^0
        t0 = t1.multiply(t0);

        // 2^11 - 2^1
        t1 = t0.square();

        // 2^20 - 2^10
        for (int i = 1; i < 10; ++i) {
            t1 = t1.square();
        }

        // 2^20 - 2^0
        t1 = t1.multiply(t0);

        // 2^21 - 2^1
        t2 = t1.square();

        // 2^40 - 2^20
        for (int i = 1; i < 20; ++i) {
            t2 = t2.square();
        }

        // 2^40 - 2^0
        t1 = t2.multiply(t1);

        // 2^41 - 2^1
        t1 = t1.square();

        // 2^50 - 2^10
        for (int i = 1; i < 10; ++i) {
            t1 = t1.square();
        }

        // 2^50 - 2^0
        t0 = t1.multiply(t0);

        // 2^51 - 2^1
        t1 = t0.square();

        // 2^100 - 2^50
        for (int i = 1; i < 50; ++i) {
            t1 = t1.square();
        }

        // 2^100 - 2^0
        t1 = t1.multiply(t0);

        // 2^101 - 2^1
        t2 = t1.square();

        // 2^200 - 2^100
        for (int i = 1; i < 100; ++i) {
            t2 = t2.square();
        }

        // 2^200 - 2^0
        t1 = t2.multiply(t1);

        // 2^201 - 2^1
        t1 = t1.square();

        // 2^250 - 2^50
        for (int i = 1; i < 50; ++i) {
            t1 = t1.square();
        }

        // 2^250 - 2^0
        t0 = t1.multiply(t0);

        // 2^251 - 2^1
        t0 = t0.square();

        // 2^252 - 2^2
        t0 = t0.square();

        // 2^252 - 3
        return multiply(t0);
    }

    /**
     * Constant-time conditional move. Well, actually it is a conditional copy.
     * Logic is inspired by the SUPERCOP implementation at:
     *     https://github.com/floodyberry/supercop/blob/master/crypto_sign/ed25519/ref10/fe_cmov.c
     *
     * @param val the other field element.
     * @param b must be 0 or 1, otherwise results are undefined.
     * @return a copy of this if $b == 0$, or a copy of val if $b == 1$.
     * @since 0.9.36
     */
    @Override
    public FieldElement cmov(FieldElement val, int b) {
        Ed25519FieldElement that = (Ed25519FieldElement) val;
        b = -b;
        int[] result = new int[10];
        for (int i = 0; i < 10; i++) {
            result[i] = this.t[i];
            int x = this.t[i] ^ that.t[i];
            x &= b;
            result[i] ^= x;
        }
        return new Ed25519FieldElement(this.f, result);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(t);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Ed25519FieldElement)) return false;
        Ed25519FieldElement fe = (Ed25519FieldElement) obj;
        return 1 == Utils.equal(toByteArray(), fe.toByteArray());
    }

    @Override
    public String toString() {
        return "[Ed25519FieldElement val=" + Utils.bytesToHex(toByteArray()) + "]";
    }
}
