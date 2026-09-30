package net.i2p.router.tunnel.pool;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 *  Tests the next-hop contact outcome summary added to {@link BuildHandler}.
 *
 *  The failure this covers was invisible: 42% of handled inbound builds hit the
 *  hop-contact timeout, but the only record was one DEBUG line per event, so an
 *  eepsite silently stopped serving and nothing at WARN reflected it.
 */
public class BuildHandlerNextHopSummaryTest {

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
                   s.contains("172 handled") && s.contains("100 replied") && s.contains("72 timed out"));
        assertTrue("must show 42%: " + s, s.contains("(42%)"));
    }

    /**
     *  The denominator is replied+timedOut, not all outcomes: "dropped" is a
     *  rejection we made, not a peer that failed to answer, so including it
     *  would understate the real unanswered rate.
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
     *  Exactly at the flag threshold the summary stays quiet. The rate is
     *  timedOut/(replied+timedOut), so 20% needs 20 unanswered per 80 answered,
     *  not 20 per 100 — 20/120 is 17%.
     */
    @Test
    public void thresholdBoundary() {
        assertFalse("19% is below threshold",
                    BuildHandler.nextHopSummaryText(new long[] {80, 19, 0}, 60_000).contains("unreachable"));
        assertTrue("20% reaches threshold",
                   BuildHandler.nextHopSummaryText(new long[] {80, 20, 0}, 60_000).contains("unreachable"));
    }

    /** Counting must accumulate into the right buckets. */
    @Test
    public void countingAccumulatesPerOutcome() {
        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_REPLIED);
        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_REPLIED);
        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_TIMEOUT);
        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_DROPPED);
        long[] c = BuildHandler.snapshotNextHopOutcomes();
        assertEquals(2, c[0]);
        assertEquals(1, c[1]);
        assertEquals(1, c[2]);
    }

    /** An out-of-range outcome must be ignored, not corrupt a bucket. */
    @Test
    public void invalidOutcomeIsIgnored() {
        BuildHandler.countNextHopOutcome(-1);
        BuildHandler.countNextHopOutcome(99);
        long[] c = BuildHandler.snapshotNextHopOutcomes();
        assertEquals(0, c[0] + c[1] + c[2]);
    }

    /**
     *  The summary is rate limited: a burst of events must not produce a log
     *  line each. With a null log nothing is emitted, but the counters still
     *  accumulate so the next real summary sees the full burst.
     */
    @Test
    public void countersAccumulateEvenWhenNotLogged() {
        for (int i = 0; i < 500; i++) {
            assertFalse("must not log on every event",
                        BuildHandler.countNextHopOutcome(BuildHandler.NEXT_HOP_TIMEOUT, null, 1000));
        }
        assertEquals(500, BuildHandler.snapshotNextHopOutcomes()[1]);
    }
}
