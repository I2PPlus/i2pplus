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
 * An EdDSA finite field. Includes several pre-computed values.
 *
 * @since 0.9.15
 * @author str4d
 *
 */
public class Field implements Serializable {
    private static final long serialVersionUID = 8746587465875676L;

    public final FieldElement ZERO;
    public final FieldElement ONE;
    public final FieldElement TWO;
    public final FieldElement FOUR;
    public final FieldElement FIVE;
    public final FieldElement EIGHT;

    private final int b;
    private final FieldElement q;

    /**
     * q-2
     */
    private final FieldElement qm2;

    /**
     * (q-5) / 8
     */
    private final FieldElement qm5d8;

    private final Encoding enc;

    /**
     * Creates a new finite field with the given parameters.
     *
     * @param b the bit length of the field
     * @param q the field prime as a byte array
     * @param enc the encoding to use for field elements
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
     * Creates a field element from a byte array.
     *
     * @param x the byte array encoding of the field element
     * @return the field element
     */
    public final FieldElement fromByteArray(byte[] x) {
        return enc.decode(x);
    }

    /**
     * Returns the bit length of the field.
     *
     * @return the bit length
     */
    public int getb() {
        return b;
    }

    /**
     * Returns the field prime q.
     *
     * @return the field prime
     */
    public FieldElement getQ() {
        return q;
    }

    /**
     * Returns q - 2.
     *
     * @return q - 2
     */
    public FieldElement getQm2() {
        return qm2;
    }

    /**
     * Returns (q - 5) / 8.
     *
     * @return (q - 5) / 8
     */
    public FieldElement getQm5d8() {
        return qm5d8;
    }

    /**
     * Returns the encoding used for this field.
     *
     * @return the encoding
     */
    public Encoding getEncoding() {
        return enc;
    }

    @Override
    public int hashCode() {
        return q.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Field)) return false;
        Field f = (Field) obj;
        return b == f.b && q.equals(f.q);
    }
}
