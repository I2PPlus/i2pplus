package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import net.i2p.util.Log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the next-hop contact outcome summary added to {@link BuildHandler}.
 *
 * The failure this covers was invisible: 42% of handled inbound builds hit the
 * hop-contact timeout, but the only record was one DEBUG line per event, so an
 * eepsite silently stopped serving and nothing at WARN reflected it.
 *
 * The second defect this pins is a mislabelled rate: the summary named a
 * 5-minute window while printing counters that were never advanced past the
 * previous summary, so the same line read 517, 1103, 1753, 2352, 3006, 3750
 * handled across an hour. A lifetime total under a window label is a running
 * average - a router that recovered could not lower the percentage and one that
 * had just broken could not raise it.
 */
public class BuildHandlerNextHopSummaryTest {

    /**
     * JUnit fixture object; every test here drives static state of
     * {@link BuildHandler}, so the instance carries no state.
     */
    public BuildHandlerNextHopSummaryTest() {
    }

    /**
     * Start each test from zeroed counters and an unseeded summary window -
     * the counters are static, so a test that ran first would otherwise feed
     * this one's summary line.
     */
    @Before
    public void setUp() {
        BuildHandler.resetNextHopOutcomes();
    }

    /** The summary must state the timeout rate against handled requests. */
    @Test
    public void summaryReportsTimeoutPercentage() {
        // 42% unanswered: the rate observed in production.
        String s = BuildHandler.nextHopSummaryText(new long[] {100, 72, 5}, 300_000);
        assertTrue("must name the counts: " + s,
                   s.contains("172 handled") && s.contains("Replied: 100") && s.contains("Timed out: 72"));
        assertTrue("must show 42%: " + s, s.contains("(42%)"));
    }

    /**
     * The denominator is replied+timedOut, not all outcomes: "dropped" is a
     * rejection we made, not a peer that failed to answer, so including it
     * would understate the real unanswered rate.
     */
    @Test
    public void denominatorExcludesDropped() {
        String withDrops = BuildHandler.nextHopSummaryText(new long[] {50, 50, 100}, 60_000);
        assertTrue("dropped must not enter the denominator: " + withDrops,
                   withDrops.contains("100 handled") && withDrops.contains("(50%)"));
    }

    /** A sustained high rate must call itself out, since that is the alarm. */
    @Test
    public void highRateIsFlagged() {
        assertTrue(">=20% must warn about unreachable peers",
                   BuildHandler.nextHopSummaryText(new long[] {10, 5, 0}, 60_000).contains("unreachable"));
    }

    /** A healthy rate must not cry wolf. */
    @Test
    public void lowRateIsNotFlagged() {
        assertFalse("low rate must not warn",
                    BuildHandler.nextHopSummaryText(new long[] {100, 2, 1}, 60_000).contains("unreachable"));
    }

    /** No traffic means no division by zero and no bogus percentage. */
    @Test
    public void emptyCountsAreSafe() {
        String s = BuildHandler.nextHopSummaryText(new long[] {0, 0, 0}, 300_000);
        assertTrue("must render without dividing by zero: " + s, s.contains("0 handled") && s.contains("(0%)"));
    }

    /**
     * Exactly at the flag threshold the summary stays quiet. The rate is
     * timedOut/(replied+timedOut), so 20% needs 20 unanswered per 80 answered,
     * not 20 per 100 — 20/120 is 17%.
     */
    @Test
    public void thresholdBoundary() {
        assertFalse("19% is below threshold",
                    BuildHandler.nextHopSummaryText(new long[] {80, 19, 0}, 60_000).contains("unreachable"));
        assertTrue("20% reaches threshold",
                   BuildHandler.nextHopSummaryText(new long[] {80, 20, 0}, 60_000).contains("unreachable"));
    }

    /**
     * Counting must accumulate into the right buckets.
     *
     * <p>Every call passes the log and the clock, never a counting-only
     * variant.  That is the regression this pins: the first version of the
     * feature had a second overload that only incremented, and both hot-path
     * call sites used it, so the counters moved and the summary never fired.
     * Unit tests of the function itself passed the whole time.  Requiring the
     * log at every call site makes a silent split impossible again.
     */
    @Test
    public void countingAccumulatesPerOutcome() {
        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_REPLIED, null, 0);
        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_REPLIED, null, 0);
        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_TIMEOUT, null, 0);
        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_DROPPED, null, 0);
        long[] c = BuildHandler.snapshotNextHopOutcomes();
        assertEquals(2, c[0]);
        assertEquals(1, c[1]);
        assertEquals(1, c[2]);
    }

    /** An out-of-range outcome must be ignored, not corrupt a bucket. */
    @Test
    public void invalidOutcomeIsIgnored() {
        BuildHandler.countNextHopOutcome(-1, null, 0);
        BuildHandler.countNextHopOutcome(99, null, 0);
        long[] c = BuildHandler.snapshotNextHopOutcomes();
        assertEquals(0, c[0] + c[1] + c[2]);
    }

    /**
     * The dropped bucket must be reachable from production, not only from a
     * test.  A bucket nothing writes to always reads zero, which looks like a
     * healthy router and hides the rejections entirely. So assert the source
     * actually records the two drop paths we care about.
     *
     * @throws Exception if the BuildHandler source cannot be read back from disk
     */
    @Test
    public void droppedBucketIsWiredToRealDropPaths() throws Exception {
        java.io.File src = new java.io.File("router/java/src/net/i2p/router/tunnel/pool/BuildHandler.java");
        if (!src.isFile()) {src = new java.io.File("src/net/i2p/router/tunnel/pool/BuildHandler.java");}
        org.junit.Assume.assumeTrue("source not available", src.isFile());
        String body = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        int dropped = body.split("NEXT_HOP_DROPPED,", -1).length - 1;
        assertTrue("NEXT_HOP_DROPPED must be recorded on real drop paths, found " + dropped,
                   dropped >= 2);
        assertTrue("a counting-only overload must not exist alongside the logging one",
                   !body.contains("static void countNextHopOutcome(int outcome) {"));
    }

    /**
     * The summary is rate limited: a burst of events must not produce a log
     * line each. With a null log nothing is emitted, but the counters still
     * accumulate so the next real summary sees the full burst.
     */
    @Test
    public void countersAccumulateEvenWhenNotLogged() {
        for (int i = 0; i < 500; i++) {
            assertFalse("must not log on every event",
                        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_TIMEOUT, null, 1000));
        }
        assertEquals(500, BuildHandler.snapshotNextHopOutcomes()[1]);
    }

    // ---------------- the summary covers its own window ----------------

    /** A clock well past the epoch: a window start of 0 is also the "unseeded" marker. */
    private static final long T0 = 1_000_000L;

    /** One summary interval, the interval the line claims to cover. */
    private static final long WINDOW = BuildHandler.NEXT_HOP_SUMMARY_INTERVAL_MS;

    /**
     * A WARN log that keeps every line written to it.
     *
     * @param lines list the captured lines are appended to
     * @return the mock, already answering shouldWarn() yes
     */
    private static Log capturingLog(final List<String> lines) {
        Log log = mock(Log.class);
        when(log.shouldWarn()).thenReturn(true);
        doAnswer(inv -> { lines.add(inv.getArgument(0)); return null; }).when(log).warn(anyString());
        return log;
    }

    /**
     * The counts on a line labelled "in 5m" must be the counts of that 5
     * minutes, not the router's lifetime totals.
     *
     * <p>This was the defect: the counters were never advanced past the
     * previous summary, so every line repeated everything since startup. In
     * one hour of logs the same line read 517, 1103, 1753, 2352, 3006, 3750
     * handled - monotonic, because it was a lifetime counter wearing a
     * 5-minute label - and the percentage was a running average that a
     * recovered router could not lower and a broken one could not raise.
     */
    @Test
    public void summaryCountsCoverTheWindowNotLifetime() {
        List<String> lines = new ArrayList<String>();
        Log log = capturingLog(lines);

        for (int i = 0; i < 3; i++) {
            BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_REPLIED, log, T0);
        }
        for (int i = 0; i < 2; i++) {
            BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_TIMEOUT, log, T0);
        }
        assertTrue("first outcome must seed the window without logging", lines.isEmpty());

        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_TIMEOUT, log, T0 + WINDOW);
        assertEquals("one summary per window: " + lines, 1, lines.size());
        String first = lines.get(0);
        assertTrue(first, first.contains("6 handled"));
        assertTrue(first, first.contains("Replied: 3"));
        assertTrue(first, first.contains("Timed out: 3 (50%)"));

        // A clean second window: everything answered, nothing unanswered. The
        // outcome that crosses the interval is the one that writes the line, so
        // it belongs to the window it reports.
        for (int i = 0; i < 4; i++) {
            BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_REPLIED, log, T0 + 2 * WINDOW - 1);
        }
        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_REPLIED, log, T0 + 2 * WINDOW);
        assertEquals("one summary per window: " + lines, 2, lines.size());
        String second = lines.get(1);
        assertTrue(second, second.contains("5 handled"));
        assertTrue(second, second.contains("Replied: 5"));
        assertTrue("a recovered window must read 0%: " + second, second.contains("Timed out: 0 (0%)"));
        assertFalse("must not repeat the handled total: " + second, second.contains("11 handled"));
        assertFalse("must not repeat the replied total: " + second, second.contains("Replied: 8"));
    }

    /**
     * Taking a window advances the baseline, so the same event is never
     * reported by two summaries.
     */
    @Test
    public void takingAWindowAdvancesTheBaseline() {
        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_TIMEOUT, null, T0);
        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_DROPPED, null, T0);
        long[] first = BuildHandler.takeNextHopWindow();
        assertEquals(1, first[1]);
        assertEquals(1, first[2]);
        long[] again = BuildHandler.takeNextHopWindow();
        assertEquals("nothing new counted: " + Arrays.toString(again), 0,
                     again[0] + again[1] + again[2]);
        long[] life = BuildHandler.snapshotNextHopOutcomes();
        assertEquals("lifetime totals are untouched", 2, life[1] + life[2]);
    }

    /**
     * A window longer than the interval - no outcome for a while, then one
     * that triggers the summary - must label the real elapsed time and cover
     * exactly the counts in it.
     */
    @Test
    public void longWindowIsLabelledWithItsRealLength() {
        List<String> lines = new ArrayList<String>();
        Log log = capturingLog(lines);
        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_REPLIED, log, T0);
        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_TIMEOUT, log, T0 + 3 * WINDOW);
        assertEquals(1, lines.size());
        String s = lines.get(0);
        assertTrue(s, s.contains("in 15m"));
        assertTrue(s, s.contains("2 handled"));
    }
}
