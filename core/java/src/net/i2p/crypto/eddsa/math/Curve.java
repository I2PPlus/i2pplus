package net.i2p.crypto.eddsa.math;

import java.io.Serializable;

/**
 * A twisted Edwards curve.
 * Points on the curve satisfy $-x^2 + y^2 = 1 + d x^2y^2$
 *
 * @author str4d
 */
public class Curve implements Serializable {
    private static final long serialVersionUID = 4578920872509827L;
    /** The finite field. */
    private final Field f;
    /** The curve parameter d. */
    private final FieldElement d;
    /** Twice the curve parameter d. */
    private final FieldElement d2;
    /** The square root of -1. */
    private final FieldElement I;

    /** Zero p2 */
    private final GroupElement zeroP2;
    /** Zero p3 */
    private final GroupElement zeroP3;
    /** Zero p3 precomputed double */
    private final GroupElement zeroP3PrecomputedDouble;
    /** Zero precomp */
    private final GroupElement zeroPrecomp;

    /**
     * Create a twisted Edwards curve.
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
     * Return the field.
     *
     * @return the field
     */
    public Field getField() {
        return f;
    }

    /**
     * Return the curve parameter d.
     *
     * @return the curve parameter d
     */
    public FieldElement getD() {
        return d;
    }

    /**
     * Return twice the curve parameter d.
     *
     * @return 2 * d
     */
    public FieldElement get2D() {
        return d2;
    }

    /**
     * Return the square root of -1.
     *
     * @return the square root of -1
     */
    public FieldElement getI() {
        return I;
    }

    /**
     * Return the zero element for the given representation.
     *
     * @param repr the representation type
     * @return the zero element, or null if unsupported
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
     * Create a point from an encoded representation.
     *
     * @param P the encoded point
     * @param precompute whether to precompute
     * @return the group element
     */
    public GroupElement createPoint(byte[] P, boolean precompute) {
        GroupElement ge = new GroupElement(this, P, precompute);
        return ge;
    }

    /**
     * The hash code of this curve.
     *
     * @return The hash code.
     */
    @Override
    public int hashCode() {
        return f.hashCode() ^ d.hashCode() ^ I.hashCode();
    }

    /**
     * Whether this curve equals the given object.
     *
     * @param o The object to compare.
     * @return True if equal.
     */
    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Curve)) return false;
        Curve c = (Curve) o;
        return f.equals(c.getField()) && d.equals(c.getD()) && I.equals(c.getI());
    }
}
