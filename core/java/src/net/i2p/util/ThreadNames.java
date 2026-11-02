package net.i2p.util;

/**
 * Names for threads, so they read the same in the log, in htop and on the
 * console thread page.
 *
 * <p>The log renders the thread column at a fixed 12 characters
 * (LogRecordFormatter.MAX_THREAD_LENGTH) and shortens a longer name with an
 * ellipsis, so a name built from a counter that grows past its budget loses
 * its head - {@code …kAccpt-a1b2} - and the column stops lining up. A
 * sequence suffix is therefore zero-padded to two digits and wrapped at 100,
 * which keeps the rendered width fixed for the life of the router: a pool of
 * nine threads and a counter that has run for a month both produce a
 * three-character suffix.
 *
 * <p>Not in this class: the names themselves. They live at the site that
 * creates the thread, beside the code they describe.
 *
 * @see <a href="https://trac.i2p2.de/wiki/Logging">the log format</a>
 * @since 0.9.72+
 */
public class ThreadNames {

    /**
     * Constructor. Every method here is a static function of the number
     * passed to it, so an instance carries no state.
     */
    public ThreadNames() {}

    /**
     * The suffix is two digits, so a name with a sequence suffix has 9
     * characters of stem: 9 + 1 separator + 2 digits = the 12-character column.
     */
    private static final int SEQ_MODULUS = 100;

    /**
     * The width of a padded sequence suffix, including the separator, for
     * anyone budgeting a stem length.
     *
     * @return 3, for {@code .NN}
     * @since 0.9.72+
     */
    public static int seqWidth() {
        return 3;
    }

    /**
     * A two-digit, zero-padded sequence number for a thread name, wrapped at
     * 100. The wrap is deliberate: the counters behind these names are
     * monotonic over the router's life (one per I2CP client, per host check,
     * per SAM session), and a name that grows to {@code .1234} pushes the
     * stem out of the column. Two digits distinguish every thread alive at
     * one time, which is all a reader of a log line needs.
     *
     * @param n the sequence number, any value
     * @return the suffix, including the leading dot, e.g. {@code .07}
     * @since 0.9.72+
     */
    public static String seq(long n) {
        return String.format(".%02d", Math.floorMod(n, SEQ_MODULUS));
    }

    /**
     * A sequence number that is already bounded below 100 by the pool that
     * allocates it, e.g. a shard index. Padded, not wrapped, so a pool that
     * outgrows its budget shows the real number instead of wrapping.
     *
     * @param n the number, non-negative
     * @return the suffix, including the leading dot, e.g. {@code .03}
     * @since 0.9.72+
     */
    public static String idx(long n) {
        return String.format(".%02d", n);
    }
}
