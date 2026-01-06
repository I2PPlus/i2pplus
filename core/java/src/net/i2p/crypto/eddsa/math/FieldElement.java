/**
 * EdDSA-Java by str4d
 *
 * To the extent possible under law, the person who associated CC0 with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java.
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <https://creativecommons.org/publicdomain/zero/1.0/>.
 *
 */
package net.i2p.crypto.eddsa.math;

import java.io.Serializable;

/**
 * Represents an element in a finite field of order $2^{b}-3$.
 * Note: concrete subclasses must implement hashCode() and equals()
 *
 * @since 0.9.15
 */
public abstract class FieldElement implements Serializable {
    private static final long serialVersionUID = 1239527465875676L;

    /** The field this element belongs to */
    protected final Field f;

    /**
     * Creates a new field element in the specified field.
     *
     * @param f the field this element belongs to
     * @throws IllegalArgumentException if f is null
     */
    public FieldElement(Field f) {
        if (null == f) {
            throw new IllegalArgumentException("field cannot be null");
        }
        this.f = f;
    }

    /**
     * Encode a FieldElement in its $(b-1)$-bit encoding.
     *
     * @return the $(b-1)$-bit encoding of this FieldElement
     */
    public byte[] toByteArray() {
        return f.getEncoding().encode(this);
    }

    /**
     * Checks if this field element is non-zero.
     *
     * @return true if this element is not equal to zero, false otherwise
     */
    public abstract boolean isNonZero();

    /**
     * Checks if this field element is negative.
     * Negative is defined as the least significant bit being 1.
     *
     * @return true if this element is negative, false otherwise
     */
    public boolean isNegative() {
        return f.getEncoding().isNegative(this);
    }

    /**
     * Adds another field element to this one.
     *
     * @param val the field element to add
     * @return the sum of this element and val
     */
    public abstract FieldElement add(FieldElement val);

    /**
     * Adds one to this field element.
     *
     * @return this element plus one
     */
    public FieldElement addOne() {
        return add(f.ONE);
    }

    /**
     * Subtracts another field element from this one.
     *
     * @param val the field element to subtract
     * @return the difference of this element and val
     */
    public abstract FieldElement subtract(FieldElement val);

    /**
     * Subtracts one from this field element.
     *
     * @return this element minus one
     */
    public FieldElement subtractOne() {
        return subtract(f.ONE);
    }

    /**
     * Negates this field element.
     *
     * @return the negation of this element
     */
    public abstract FieldElement negate();

    /**
     * Divides this field element by another one.
     *
     * @param val the divisor
     * @return this element divided by val
     */
    public FieldElement divide(FieldElement val) {
        return multiply(val.invert());
    }

    /**
     * Multiplies this field element by another one.
     *
     * @param val the multiplier
     * @return the product of this element and val
     */
    public abstract FieldElement multiply(FieldElement val);

    /**
     * Squares this field element.
     *
     * @return this element squared
     */
    public abstract FieldElement square();

    /**
     * Squares this field element and doubles the result.
     *
     * @return (this element squared) * 2
     */
    public abstract FieldElement squareAndDouble();

    /**
     * Computes the multiplicative inverse of this field element.
     *
     * @return the multiplicative inverse of this element
     * @throws ArithmeticException if this element is zero
     */
    public abstract FieldElement invert();

    /**
     * Raises this field element to the power of $2^{252} - 3$.
     *
     * @return this element raised to the power of $2^{252} - 3$
     */
    public abstract FieldElement pow22523();

    /**
     * Conditional move - sets this element to val if b is 1, otherwise leaves it unchanged.
     *
     * @param val the field element to potentially assign
     * @param b the condition: 1 means assign val, 0 means keep this element
     * @return this element, potentially modified
     * @since 0.9.36
     */
    public abstract FieldElement cmov(FieldElement val, final int b);

    // Note: concrete subclasses must implement hashCode() and equals()
}
