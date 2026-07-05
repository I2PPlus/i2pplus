/**
 * EdDSA-Java by str4d
 *
 * To the extent possible under law, the person who associated CC0 with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java.
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <https://creativecommons.org/publicdomain/zero/1.0/>lt;https://creativecommons.org/publicdomain/zero/1.0/<https://creativecommons.org/publicdomain/zero/1.0/>gt;.
 *
 */
package net.i2p.crypto.eddsa.math;

import java.io.Serializable;

/**
 * A twisted Edwards curve.
 * Points on the curve satisfy $-x^2 + y^2 = 1 + d x^2y^2$
 *
 * @since 0.9.15
 * @author str4d
 *
 */
public class Curve implements Serializable {
    private static final long serialVersionUID = 4578920872509827L;
    private final Field f;
    private final FieldElement d;
    private final FieldElement d2;
    private final FieldElement I;

    private final GroupElement zeroP2;
    private final GroupElement zeroP3;
    private final GroupElement zeroP3PrecomputedDouble;
    private final GroupElement zeroPrecomp;

    /**
     * Creates a new twisted Edwards curve.
     *
     * @param f the finite field
     * @param d the curve parameter d
     * @param I the square root of -1
     */
    public Curve(Field f, byte[] d, FieldElement I) {
        this.f = f;
        this.d = f.fromByteArray(d);
        this.d2 = this.d.add(this.d);
        this.I = I;

        FieldElement zero = f.ZERO;
        FieldElement one = f.ONE;
        zeroP2 = GroupElement.p2(this, zero, one, one);
        zeroP3 = GroupElement.p3(this, zero, one, one, zero, false);
        zeroP3PrecomputedDouble = GroupElement.p3(this, zero, one, one, zero, true);
        zeroPrecomp = GroupElement.precomp(this, one, one, zero);
    }

    /**
     * Returns the finite field of this curve.
     *
     * @return the field
     */
    public Field getField() {
        return f;
    }

    /**
     * Returns the curve parameter d.
     *
     * @return the parameter d
     */
    public FieldElement getD() {
        return d;
    }

    /**
     * Returns 2 * d.
     *
     * @return 2 * d
     */
    public FieldElement get2D() {
        return d2;
    }

    /**
     * Returns the square root of -1 in this field.
     *
     * @return the square root of -1
     */
    public FieldElement getI() {
        return I;
    }

    /**
     * Returns the neutral (zero) element in the given representation.
     *
     * @param repr the representation type
     * @return the zero element, or null if the representation is unsupported
     */
    public GroupElement getZero(GroupElement.Representation repr) {
        switch (repr) {
            case P2: return zeroP2;
            case P3: return zeroP3;
            case P3PrecomputedDouble: return zeroP3PrecomputedDouble;
            case PRECOMP: return zeroPrecomp;
            default: return null;
        }
    }

    /**
     * Creates a point on this curve from an encoded byte array.
     *
     * @param P the encoded point
     * @param precompute whether to precompute lookup tables
     * @return the group element representing the point
     */
    public GroupElement createPoint(byte[] P, boolean precompute) {
        GroupElement ge = new GroupElement(this, P, precompute);
        return ge;
    }

    /**
     * Returns a hash code for this curve.
     *
     *  @since 0.9.25
     */
    @Override
    public int hashCode() {
        return f.hashCode() ^ d.hashCode() ^ I.hashCode();
    }

    /**
     * Compares this curve to another object for equality.
     *
     *  @since 0.9.25
     */
    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Curve)) return false;
        Curve c = (Curve) o;
        return f.equals(c.getField()) && d.equals(c.getD()) && I.equals(c.getI());
    }
}
