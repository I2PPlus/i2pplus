package net.i2p.util;

import java.util.NavigableSet;
import java.util.TreeSet;

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

    /**
     * Numbers for one family of threads that start and stop, handed out lowest
     * first and harvested back when a thread finishes.
     *
     * <p>A monotonic counter answers a question nobody is asking - which number
     * was free when this thread was created. The useful answer is which number
     * is free now, and handing that out keeps a family of ten threads named
     * {@code .01} to {@code .10} for the life of the router, however many
     * thousands of them come and go. It also keeps a name honest: a number in a
     * log line belongs to a thread that is alive, so a reader who greps for it
     * finds the thread's whole life, not one of several.
     *
     * <p>The contract is one {@link #release} per {@link #acquire}, at the point
     * the thread is known to have finished - a {@code finally} around
     * {@link Thread#run()} is the usual place. A family that never releases
     * behaves exactly like a monotonic counter, which is the state before this
     * class existed, so a site that cannot see its threads die is no worse off
     * for having a pool.
     *
     * <p>Thread-safe: the calls are at thread-creation and thread-exit rate, so
     * a lock is cheaper than the alternatives.
     *
     * @see <a href="https://trac.i2p2.de/wiki/Logging">the log format</a>
     * @since 0.9.72+
     */
    public static class Pool {

        /** The numbers handed back, waiting to be reused. */
        private final NavigableSet<Integer> _free = new TreeSet<>();

        /** The next number to hand out when {@link #_free} is empty. */
        private int _next = 1;

        /**
         * A pool with no threads in it yet, so the first thread is {@code .01}.
         */
        public Pool() {}

        /**
         * The lowest number no thread in this family is using.
         *
         * @return a number to name a thread with, from 1
         */
        public synchronized int acquire() {
            Integer n = _free.pollFirst();
            if (n != null) {return n;}
            return _next++;
        }

        /**
         * Harvest a number back. The highest number in use is dropped rather
         * than pooled, so a family that shrinks back to its starting size
         * returns to handing out {@code .01} upward instead of recycling a
         * tail of freed numbers.
         *
         * @param n the number the finished thread was named with
         */
        public synchronized void release(int n) {
            if (n < 1) {return;}
            if (n == _next - 1) {_next--;}
            else {_free.add(n);}
        }

        /**
         * The name suffix for a number from this pool, the same padded form as
         * {@link ThreadNames#idx(long)}. Padded and not wrapped: a pool hands
         * out numbers in use, which is a count of live threads, and a family
         * with more than 99 alive at one time should show the real number.
         *
         * @param n a number from {@link #acquire()}
         * @return the suffix, including the leading dot, e.g. {@code .07}
         */
        public String suffix(int n) {
            return idx(n);
        }

        /**
         * A thread named with a number from this pool, which releases the
         * number when its {@code run()} returns - the point at which the thread
         * has finished, including on an exception. A thread that never returns
         * never releases, which is correct: its number is in use.
         *
         * @param r what the thread runs
         * @param stem the family name, at most 9 characters
         * @param daemon whether the thread is a daemon
         * @return a thread with its daemon flag set, not started
         */
        public Thread thread(Runnable r, String stem, boolean daemon) {
            final int n = acquire();
            Thread t = new Thread(r, stem + suffix(n)) {
                @Override
                public void run() {
                    try {
                        super.run();
                    } finally {
                        release(n);
                    }
                }
            };
            t.setDaemon(daemon);
            return t;
        }

        /**
         * An {@link I2PThread}, daemon, named with a number from this pool, and
         * releasing the number when its {@code run()} returns. The {@link
         * I2PThread} form, because most of the router's threads use it for its
         * out-of-memory reporting.
         *
         * @param r what the thread runs
         * @param stem the family name, at most 9 characters
         * @return a daemon thread, not started
         */
        public I2PThread i2pThread(Runnable r, String stem) {
            final int n = acquire();
            return new I2PThread(r, stem + suffix(n), true) {
                @Override
                public void run() {
                    try {
                        super.run();
                    } finally {
                        release(n);
                    }
                }
            };
        }
    }
}
