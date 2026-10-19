package net.i2p.crypto.eddsa.math;

import java.io.Serializable;

/**
 * An EdDSA finite field. Includes several pre-computed values.
 *
 */
public class Field implements Serializable {
    private static final long serialVersionUID = 8746587465875676L;

    /**
     * The field's additive identity.
     */
    public final FieldElement ZERO;
    /**
     * The field's multiplicative identity.
     */
    public final FieldElement ONE;
    /**
     * The field element two.
     */
    public final FieldElement TWO;
    /**
     * The field element four.
     */
    public final FieldElement FOUR;
    /**
     * The field element five.
     */
    public final FieldElement FIVE;
    /**
     * The field element eight.
     */
    public final FieldElement EIGHT;

    /** The bit length of field elements. */
    private final int b;
    /** The field prime q. */
    private final FieldElement q;

    /**
     * The value q - 2.
     */
    private final FieldElement qm2;

    /**
     * (q-5) / 8.
     */
    private final FieldElement qm5d8;

    /** The encoding for field elements. */
    private final Encoding enc;

    /**
     * Create a finite field.
     *
     * @param b the bit length
     * @param q the field prime
     * @param enc the Encoding that converts field elements to and from bytes
     */
    public Field(int b, byte[] q, Encoding enc) {
        this.b = b;
        this.enc = enc;
        this.enc.setField(this);

        this.q = fromByteArray(q);

        // Set up constants
        ZERO = fromByteArray(Constants.ZERO);
        ONE = fromByteArray(Constants.ONE);
        TWO = fromByteArray(Constants.TWO);
        FOUR = fromByteArray(Constants.FOUR);
        FIVE = fromByteArray(Constants.FIVE);
        EIGHT = fromByteArray(Constants.EIGHT);

        // Precompute values
        qm2 = this.q.subtract(TWO);
        qm5d8 = this.q.subtract(FIVE).divide(EIGHT);
    }

    /**
     * Create a field element from a byte array.
     *
     * @param x the byte array
     * @return the field element
     */
    public final FieldElement fromByteArray(byte[] x) {
        return enc.decode(x);
    }

    /**
     * Return the bit length.
     *
     * @return the bit length
     */
    public int getb() {
        return b;
    }

    /**
     * Return the field prime.
     *
     * @return the field prime
     */
    public FieldElement getQ() {
        return q;
    }

    /**
     * Return q - 2.
     *
     * @return q - 2
     */
    public FieldElement getQm2() {
        return qm2;
    }

    /**
     * Return (q - 5) / 8.
     *
     * @return (q - 5) / 8
     */
    public FieldElement getQm5d8() {
        return qm5d8;
    }

    /**
     * Return the encoding for field elements, which converts them to and from bytes.
     *
     * @return the Encoding that converts field elements to and from bytes
     */
    public Encoding getEncoding() {
        return enc;
    }

    /**
     * Return the hash code.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return q.hashCode();
    }

    /**
     * Compare for equality.
     *
     * @param obj the object
     * @return true if equal
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Field)) return false;
        Field f = (Field) obj;
        return b == f.b && q.equals(f.q);
    }
}
