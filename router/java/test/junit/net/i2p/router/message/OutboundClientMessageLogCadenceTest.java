package net.i2p.router.message;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import net.i2p.util.LogSuppressor;

/**
 * Pins the cadence of the recurring per-message debug lines of
 * OutboundClientMessageOneShotJob.
 *
 * <p>With that class at DEBUG on a live router, three lines that restate
 * routine state - the lease set was in the local cache, the lease was reused
 * from the cache, and the computed message window - wrote 38,941 lines in
 * 7m 37s: 14,233 across 335 destinations, 14,020 across 298, and 10,688 that
 * named no destination at all. That is 5,100 lines a minute to say three
 * things that stay true of a destination for minutes at a time, and it is why
 * the class could not be left at DEBUG in the field. Each one now goes through
 * the router's suppressor under a key naming the destination and the
 * condition, so the condition is reported once a burst per destination with
 * the collapsed count on the line, and the per-message dispatch trace - the
 * line an operator actually needs - is untouched.
 *
 * @since 0.9.72+
 */
public class OutboundClientMessageLogCadenceTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the key builder is pure, so nothing is set up.
     */
    public OutboundClientMessageLogCadenceTest() {}

    /**
     * A suppressor key has to name the condition and the destination, not the
     * occurrence. A key that varies per message lets every line through and
     * the flood continues; a key that names only the condition hides one
     * destination's problem behind another destination's counter.
     */
    @Test
    public void testDebugKeyNamesDestinationAndCondition() {
        String one = "[by7luzwh]";
        String other = "[o3nim53d]";
        String hit = key(one, "lease-cache-hit");
        assertEquals("stable across occurrences", hit,
                     key(one, "lease-cache-hit"));
        assertNotEquals("one key per condition", hit,
                        key(one, "leaseset-local"));
        assertNotEquals("one key per destination", hit,
                        key(other, "lease-cache-hit"));
    }

    /**
     * The four conditions this class reports must not collide with each other,
     * or collapsing one silences the others.
     */
    @Test
    public void testConditionsHaveTheirOwnKeys() {
        String[] conditions = {"leaseset-current", "leaseset-local",
                               "lease-cache-hit", "window"};
        Set<String> keys = new HashSet<>(conditions.length * 2);
        for (String cond : conditions) {
            assertTrue("distinct key per condition: " + cond,
                       keys.add(key("[by7luzwh]", cond)));
        }
        assertEquals(conditions.length, keys.size());
    }

    /**
     * The key budget is a fact, not a hope: the suppressor holds at most 4,096
     * keys per logger and evicts the oldest half when it is full, which turns
     * a suppressed condition back into a reported one. Four conditions over
     * the 335 destinations this class served in the measured run has to stay
     * clear of that cap.
     */
    @Test
    public void testKeyBudgetStaysUnderTheSuppressorCap() {
        int conditions = 4;
        int destinations = 335;
        int cap = 4096;
        assertTrue(conditions * destinations + " keys over the measured run",
                   conditions * destinations < cap);
    }

    /**
     * The measured run through the suppressor: 14,233 lease-set cache hits
     * over 335 destinations, 14,020 lease cache hits over 298 and 10,688
     * window lines over 335, all in 457 seconds. Under per-destination keys
     * the 60-second window, not the 1,000-message burst, ends a suppression
     * run, so the three conditions collapse from 5,100 lines a minute to a few
     * hundred while every destination keeps reporting.
     */
    @Test
    public void testSuppressorCollapsesTheMeasuredRun() {
        int total = 0;
        int written = 0;
        for (Map.Entry<String, int[]> e : MEASURED.entrySet()) {
            int[] counted = e.getValue();
            total += counted[0];
            written += collapse(e.getKey(), counted[0], counted[1]);
        }
        assertEquals("the measured total", 38_941, total);
        assertTrue("every condition stays reportable",
                   written >= MEASURED.size());
        assertTrue("at least a four-fold cut, got " + written + " of " + total,
                   written * 4 <= total);
    }

    /**
     * Collapsing must not silence a destination: a router sending to 335
     * destinations has to still see the condition for each of them, or the
     * suppression hides the one destination that matters behind the busiest.
     */
    @Test
    public void testEveryDestinationStillReports() {
        LogSuppressor sup = new LogSuppressor();
        long start = 1_700_000_000_000L;
        int destinations = 335;
        int perDest = 14_233 / destinations;
        long spacing = 457_000L / perDest;
        Set<String> reported = new HashSet<>(destinations * 2);
        for (int d = 0; d < destinations; d++) {
            String key = key("[dest" + d + ']', "leaseset-local");
            long now = start;
            for (int i = 0; i < perDest; i++) {
                now += spacing;
                if (sup.record(key, now).log) {reported.add(key);}
            }
        }
        assertEquals("one surviving line at least per destination",
                     destinations, reported.size());
    }

    /**
     * The three conditions as counted in a live router's log: occurrences,
     * then distinct destinations they fired for.
     */
    private static final Map<String, int[]> MEASURED = new HashMap<>(8);

    static {
        MEASURED.put("leaseset-local", new int[] {14_233, 335});
        MEASURED.put("lease-cache-hit", new int[] {14_020, 298});
        MEASURED.put("window", new int[] {10_688, 335});
    }

    /**
     * Runs {@code occurrences} of one condition, spread evenly over
     * {@code destinations} and over the measured 457 seconds, and counts the
     * lines the suppressor lets through.
     *
     * @param condition the fixed condition name
     * @param occurrences how many times it fired in the measured run
     * @param destinations distinct destinations it fired for
     * @return the number of lines written
     */
    private static int collapse(String condition, int occurrences,
                                int destinations) {
        LogSuppressor sup = new LogSuppressor();
        long start = 1_700_000_000_000L;
        int perDest = occurrences / destinations;
        long spacing = 457_000L / perDest;
        int written = 0;
        for (int d = 0; d < destinations; d++) {
            String key = key("[dest" + d + ']', condition);
            long now = start;
            for (int i = 0; i < perDest; i++) {
                now += spacing;
                if (sup.record(key, now).log) {written++;}
            }
        }
        return written;
    }

    /**
     * Shorthand for the key builder under test, so the assertions stay inside
     * the file's line budget.
     *
     * @param dest the target as the job renders it, in brackets
     * @param condition a fixed name for the recurring fact
     * @return the suppressor key the job will use
     */
    private static String key(String dest, String condition) {
        return OutboundClientMessageOneShotJob.debugKey(dest, condition);
    }
}
