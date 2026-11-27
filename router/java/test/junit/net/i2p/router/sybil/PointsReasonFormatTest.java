package net.i2p.router.sybil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * The reason strings a Points object accumulates, and the amount written into
 * each one.
 *
 * <p>Every reason names its amount with two decimals, and the amount is written
 * by a formatter shared by every Points instance. DecimalFormat is not
 * thread-safe: two formats in flight can share its internal digit buffer and
 * write each other's digits, and the breakdown is what the operator reads on the
 * sybil page to decide whether a peer is being scored fairly. JDK 8 happens to
 * take a fast path for this pattern that uses no shared state, so the hazard is
 * not visible there - it is the pattern on later JDKs, where that fast path is
 * gone - and the amounts have to be right either way.
 *
 * @since 0.9.72+
 */
public class PointsReasonFormatTest {

    /** Two decimals, signed, then the reason text. */
    private static final String REASON_SHAPE = "^-?\\d+[.,]\\d\\d: reason \\d+$";

    /** Amounts each thread of the concurrency test adds. */
    private static final int PER_THREAD = 2000;

    /** The reason strings one Points object accumulates. */
    public PointsReasonFormatTest() {}

    /**
     * A fresh formatter of the same pattern, used to work out what the reason
     * should have said. Not shared, so it cannot be the thing under test.
     *
     * @param d the amount to format
     * @return the amount with two decimals, in this JVM's number format
     */
    private static String expected(double d) {
        return new DecimalFormat("#0.00").format(d);
    }

    /**
     * A reason names its amount, a colon, and the text it was given, and the
     * total is the amount added.
     */
    @Test
    public void testReasonNamesTheAmountAndTheText() {
        Points p = new Points(1.5, "Too close");
        assertEquals(1.5, p.getPoints(), 0.0001);
        assertEquals(1, p.getReasons().size());
        String rsn = p.getReasons().get(0);
        assertEquals(expected(1.5) + ": Too close", rsn);
        assertTrue(rsn, rsn.matches("^1\\d*[.,]50: Too close$"));
    }

    /**
     * A credit keeps its sign, so a reader can tell a penalty from a credit in
     * the list, and an amount that needs no rounding still gets two decimals.
     */
    @Test
    public void testCreditKeepsItsSign() {
        Points p = new Points(-2, "Well integrated");
        assertEquals(expected(-2) + ": Well integrated", p.getReasons().get(0));
        assertTrue(p.getReasons().get(0), p.getReasons().get(0).startsWith("-"));
    }

    /**
     * Adding to an existing total appends a reason rather than replacing the
     * list, and the total is the sum - the operator sees every step of the score.
     */
    @Test
    public void testAddingAppends() {
        Points p = new Points(10, "First");
        p.addPoints(-4.25, "Second");
        p.addPoints(0.5, "Third");
        assertEquals(6.25, p.getPoints(), 0.0001);
        assertEquals(3, p.getReasons().size());
        assertEquals(expected(-4.25) + ": Second", p.getReasons().get(1));
        assertEquals(expected(0.5) + ": Third", p.getReasons().get(2));
    }

    /**
     * The trap the guard exists for: instances formatted at the same time share
     * one formatter, and an unguarded shared formatter lets one format see
     * another's digits. Every reason has to name the amount it was given. This
     * is a stress guard, not a reproduction - JDK 8's fast path for this pattern
     * keeps the digits in locals, so it passes there with or without the guard.
     *
     * @throws Exception if a thread is interrupted while the formats are running,
     *                   or a thread's work fails
     */
    @Test
    public void testReasonsSurviveConcurrentUse() throws Exception {
        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<List<String>>> jobs = new ArrayList<>(threads);
            for (int t = 0; t < threads; t++) {
                // A Points of its own per thread - the only shared state is the formatter.
                jobs.add(newReasons(PER_THREAD, barrier));
            }
            for (Future<List<String>> f : pool.invokeAll(jobs)) {
                assertReasons(f.get());
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    /**
     * The amounts the threads format, chosen to differ in digit count so a
     * format that picks up another thread's digits shows up as a wrong amount
     * rather than a lucky coincidence.
     */
    private static final double[] AMOUNTS = {
        0.01, 1.5, 12.75, 1234.5, 98765.43, 1234567.89, -0.5, -12345.6, 999999999.99
    };

    /**
     * The amount a thread adds at position i.
     *
     * @param i the position in the thread's list
     * @return the amount to add there
     */
    private static double amountAt(int i) {
        return AMOUNTS[i % AMOUNTS.length] * (1 + (i / AMOUNTS.length));
    }

    /**
     * Adds a spread of amounts to a Points of its own and returns the reasons.
     * All threads wait at the barrier so their formats really do overlap.
     *
     * @param count how many amounts to add
     * @param barrier where the threads start together
     * @return the reasons as written by the class under test
     */
    private static Callable<List<String>> newReasons(int count, CyclicBarrier barrier) {
        return () -> {
            List<String> out = new ArrayList<>(count);
            Points p = new Points(0, "start");
            barrier.await();
            for (int i = 0; i < count; i++) {
                p.addPoints(amountAt(i), "reason " + i);
            }
            out.addAll(p.getReasons());
            return out;
        };
    }

    /**
     * Every reason has to be the amount it was given, then its text - a shape
     * check alone is not enough, since a torn format keeps the shape and changes
     * the digits.
     *
     * @param reasons the reasons written by one thread, first one skipped as the
     *                barrier's starting credit
     */
    private static void assertReasons(List<String> reasons) {
        DecimalFormat df = new DecimalFormat("#0.00");
        assertEquals(PER_THREAD + 1, reasons.size());
        for (int i = 1; i < reasons.size(); i++) {
            String r = reasons.get(i);
            double d = amountAt(i - 1);
            assertTrue(r, r.matches(REASON_SHAPE));
            assertEquals(df.format(d) + ": reason " + (i - 1), r);
        }
    }
}
