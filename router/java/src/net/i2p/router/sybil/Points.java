package net.i2p.router.sybil;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import net.i2p.data.DataHelper;

/**
 * A total score and a List of reason Strings.
 *
 * @since 0.9.38 moved from SybilRenderer
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingTypeName")
public class Points implements Comparable<Points> {
    /** The peer's standing, lower is more suspicious. */
    private double points;
    /** Why the points are what they are. */
    private final List<String> reasons;
    /** Format used when writing the points out, shared, and held by {@link #fmt}. */
    private static final DecimalFormat TWO_DECIMALS = new DecimalFormat("#0.00");

    /**
     * Create a new empty Points instance.
     *
     * @since 0.9.38
     */
    private Points() {
        reasons = new ArrayList<>(4);
    }

    /**
     * Create a new Points instance with an initial value.
     *
     * @param d the point value to start from, positive for a penalty or negative for credit
     * @param reason may not contain '%'
     */
    public Points(double d, String reason) {
        this();
        addPoints(d, reason);
    }

    /**
     * Total points accumulated.
     *
     * @return the accumulated score, positive for penalties and negative for credits
     * @since 0.9.38
     */
    public double getPoints() {
        return points;
    }

    /**
     * Reasons accumulated with the points.
     *
     * @return the live list of accumulated reason strings, each one the signed
     *         amount formatted to two decimals, a colon, and the reason text
     * @since 0.9.38
     */
    public List<String> getReasons() {
        return reasons;
    }

    /**
     * Add points with a reason.
     *
     * @param d the point value to add, positive for a penalty or negative for credit
     * @param reason may not contain '%'
     * @since 0.9.38
     */
    public synchronized void addPoints(double d, String reason) {
        points += d;
        reasons.add(fmt(d) + ": " + reason);
    }

    /**
     * The amount of a penalty or credit as it is written into a reason: two
     * decimals, in the locale the console reads numbers in.
     * <p>
     * The formatter is one object shared by every Points instance, and
     * DecimalFormat is not thread-safe - two formats at once can share its
     * internal digit buffer and write each other's digits. This holds the
     * formatter itself, so the amount is right whatever the caller holds; {@code
     * SybilRenderer} guards its own static formatter the same way. JDK 8 takes a
     * fast path for this pattern that keeps its digits in locals, so the race is
     * not visible there - it is the pattern on later JDKs, where that fast path
     * is gone.
     *
     * @param val the amount to format
     * @return the amount with two decimals
     * @since 0.9.72+
     */
    private static String fmt(double val) {
        synchronized (TWO_DECIMALS) {
            return TWO_DECIMALS.format(val);
        }
    }

    public int compareTo(Points r) {
        return Double.compare(points, r.points);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Points)) return false;
        Points p = (Points) o;
        return Double.compare(points, p.points) == 0 && reasons.equals(p.reasons);
    }

    @Override
    public int hashCode() {
        int result = Double.hashCode(points);
        result = 31 * result + reasons.hashCode();
        return result;
    }

    /**
     * Return a string representation.
     *
     * @since 0.9.38
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        toString(buf);
        return buf.toString();
    }

    /**
     * For persistence.
     * Total points and reasons, '%' separated, no newline.
     * The separation character is chosen to not conflict with
     * decimal point in various locales, or chars in reasons, including HTML links,
     * or special chars in Pattern.
     *
     * @param buf the buffer to append the total and the '%'-separated reasons to
     * @since 0.9.38
     */
    public void toString(StringBuilder buf) {
        buf.append(points);
        for (String r : reasons) {
            buf.append('%').append(r.replace("%", "&#x25;"));
        }
    }

    /**
     * For persistence.
     * @param s the '%'-separated persistence string: the point total, then one entry per reason
     * @return null on failure
     * @since 0.9.38
     */
    public static Points fromString(String s) {
        String[] ss = DataHelper.split(s, "%");
        if (ss.length < 2)
            return null;
        double d;
        try {
            d = Double.parseDouble(ss[0]);
        } catch (NumberFormatException nfe) {
            return null;
        }
        Points rv = new Points();
        for (int i = 1; i < ss.length; i++) {
            rv.reasons.add(ss[i]);
        }
        rv.points = d;
        return rv;
    }
}

