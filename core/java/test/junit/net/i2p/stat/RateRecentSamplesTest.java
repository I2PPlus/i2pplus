package net.i2p.stat;

import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link Rate}'s retained-sample ring and ledger counters:
 * {@link Rate#getRecentSamples(int)}, {@link Rate#getCoalesceCount()} and
 * {@link Rate#getCoalesceBacklogCollapses()}.
 *
 * <p>The ring exists so a healed listener can backfill steps it never stored, and
 * carries an explicit {@code timestampMs} because {@code RateSummaryListener.add}
 * cannot report one. The ring's own constraints matter as much as its contents: it
 * is written from the coalesce path under two monitors, so it must be a fixed
 * array that cannot grow, and it is read from console threads, so it must never
 * hand out null or a mutable view.
 *
 * <p>{@code Rate} reads the OS clock rather than the {@code Clock} stat, so these
 * tests need no router context. A period of 1ms puts the skip threshold
 * ({@code period - 2000}) below zero, so every coalesce is due immediately and no
 * sleeping is needed; a period of 2100ms puts that threshold at 100ms, which makes
 * the due step exactly predictable as {@code creationDate + period}.
 *
 * @since 0.9.71+
 */
public class RateRecentSamplesTest {

    /** Period short enough that {@code period - SLACK} is negative: always due. */
    private static final long ALWAYS_DUE_PERIOD = 1;

    /** 2100ms puts the due threshold at 100ms, reachable without a long sleep. */
    private static final long NEAR_SLACK_PERIOD = 2100;

    private static Rate coalescedRate(long period, int coalesces) {
        Rate rate = new Rate(period);
        for (int i = 0; i < coalesces; i++) {
            rate.addData(i + 1, 1);
            rate.coalesce();
        }
        return rate;
    }

    @Test
    public void freshRateRetainsNothing() {
        Rate rate = new Rate(ALWAYS_DUE_PERIOD);
        List<Rate.CoalescedSample> got = rate.getRecentSamples(4);
        assertNotNull("a fresh rate must not return null", got);
        assertTrue("a fresh rate retains nothing", got.isEmpty());
        assertEquals(0, rate.getCoalesceCount());
        assertEquals(0, rate.getCoalesceBacklogCollapses());
    }

    @Test
    public void maxIsClampedAndNonPositiveYieldsEmpty() {
        Rate rate = coalescedRate(ALWAYS_DUE_PERIOD, 3);
        assertEquals("max must be clamped to what is retained", 3, rate.getRecentSamples(1000).size());
        assertEquals(3, rate.getRecentSamples(3).size());
        assertEquals(1, rate.getRecentSamples(1).size());
        assertTrue(rate.getRecentSamples(0).isEmpty());
        assertTrue("a negative max must not underflow the read", rate.getRecentSamples(-5).isEmpty());
    }

    @Test
    public void returnedListIsImmutable() {
        Rate rate = coalescedRate(ALWAYS_DUE_PERIOD, 2);
        List<Rate.CoalescedSample> got = rate.getRecentSamples(4);
        try {
            got.add(null);
            fail("the retained sample list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    /**
     * Fixed size, by construction: the ring is written from the coalesce path under
     * two monitors and must not be able to grow the heap. Overfilling it keeps only
     * the newest entries.
     */
    @Test
    public void ringIsBoundedAndKeepsTheNewestSamples() {
        Rate rate = coalescedRate(ALWAYS_DUE_PERIOD, 20);
        List<Rate.CoalescedSample> all = rate.getRecentSamples(1000);
        assertEquals("the ring must not grow past its fixed size", 8, all.size());
        for (int i = 0; i < all.size(); i++)
            assertEquals("the ring must retain the newest samples, oldest first",
                         13.0d + i, all.get(i).totalValue, 0.001d);
    }

    /**
     * Oldest first, and strictly increasing in step stamp. A consumer backfilling
     * from this list relies on both: an RRD rejects a timestamp that does not
     * advance, so a list in the wrong order would silently write nothing.
     */
    @Test
    public void retainedSamplesAreOldestFirstAndStepOrdered() {
        Rate rate = coalescedRate(ALWAYS_DUE_PERIOD, 12);
        List<Rate.CoalescedSample> all = rate.getRecentSamples(1000);
        assertEquals(8, all.size());
        for (int i = 1; i < all.size(); i++)
            assertTrue("retained samples must advance in time",
                       all.get(i).timestampMs > all.get(i - 1).timestampMs);
        for (Rate.CoalescedSample s : all)
            assertEquals("each retained sample must own its step",
                         s.timestampMs, Rate.archiveStep(s.timestampMs, ALWAYS_DUE_PERIOD));
    }

    /**
     * The ring has to carry the values because {@code RateSummaryListener.add} cannot
     * report a step stamp — a backfilling listener has no other way to know which
     * step a sample is for. Compared against the rate's own getters, since the
     * coalesce path rescales event time by how far over the period the visit was.
     */
    @Test
    public void retainedSampleCarriesTheCoalescedValues() throws Exception {
        Rate rate = new Rate(ALWAYS_DUE_PERIOD);
        rate.addData(11, 3);
        rate.addData(9, 4);
        // Enough elapsed time for the event-time rescale to be well defined.
        Thread.sleep(3);
        rate.coalesce();

        List<Rate.CoalescedSample> got = rate.getRecentSamples(1);
        assertEquals(1, got.size());
        Rate.CoalescedSample s = got.get(0);
        assertEquals(20.0d, s.totalValue, 0.001d);
        assertEquals(2, s.eventCount);
        assertEquals(rate.getLastTotalEventTime(), s.totalEventTime, 0.001d);
        assertEquals(ALWAYS_DUE_PERIOD, s.period);
        assertTrue("a coalesced sample must carry a stamp", s.timestampMs > 0);
    }

    @Test
    public void coalesceCountTracksSuccessfulCoalescesOnly() throws Exception {
        Rate early = new Rate(NEAR_SLACK_PERIOD);
        early.coalesce();
        assertEquals("a visit before the period is due is a skip, not a coalesce",
                     0, early.getCoalesceCount());
        assertEquals(1, early.getCoalesceSkips());

        Rate onTime = new Rate(NEAR_SLACK_PERIOD);
        onTime.addData(1, 1);
        onTime.addData(1, 1);
        onTime.addData(1, 1);
        onTime.addData(1, 1);
        onTime.addData(1, 1);
        Thread.sleep(150);
        onTime.coalesce();
        assertEquals("only an actual coalesce may be counted", 1, onTime.getCoalesceCount());
        assertEquals(0, onTime.getCoalesceSkips());
    }

    /**
     * On-time coalesces stamp the due step and leave the grid there, so the stamp is
     * fully determined by the creation instant and the period — independent of when
     * the test happened to run.
     */
    @Test
    public void onTimeCoalesceStampsTheDueStepWithoutCollapsing() throws Exception {
        Rate rate = new Rate(NEAR_SLACK_PERIOD);
        long creation = rate.getCreationDate();
        rate.coalesce();
        assertEquals("the period is not due yet", 1, rate.getCoalesceSkips());

        Thread.sleep(150);
        rate.coalesce();

        assertEquals(0, rate.getCoalesceBacklogCollapses());
        assertEquals(1, rate.getCoalesceCount());
        List<Rate.CoalescedSample> got = rate.getRecentSamples(4);
        assertEquals(1, got.size());
        assertEquals("an on-time coalesce must stamp the step it was due for",
                     creation + NEAR_SLACK_PERIOD, got.get(0).timestampMs);
        assertEquals("the grid must advance to the due instant, not to now",
                     creation + NEAR_SLACK_PERIOD, rate.getLastCoalesceDate());
    }

    /**
     * A starved timer cannot be replayed — a rate holds only the current partial
     * period — so the backlog collapses into the current step and is counted. The
     * tell is that the stamp is the current time rather than the missed due step.
     */
    @Test
    public void starvedTimerCollapsesTheBacklogIntoTheCurrentStep() throws Exception {
        Rate rate = new Rate(ALWAYS_DUE_PERIOD);
        long creation = rate.getCreationDate();
        rate.addData(7, 1);

        // Well past the collapse threshold, which for a 1ms period is 2ms.
        Thread.sleep(25);
        rate.coalesce();

        assertEquals("the missed step must be counted, not silently dropped",
                     1, rate.getCoalesceBacklogCollapses());
        assertEquals(1, rate.getCoalesceCount());
        List<Rate.CoalescedSample> got = rate.getRecentSamples(4);
        assertEquals(1, got.size());
        assertTrue("a collapsed backlog must stamp the current step, not the missed due step",
                   got.get(0).timestampMs >= creation + 25);
        assertEquals("a collapse must realign the grid to now, or every later visit collapses too",
                     got.get(0).timestampMs, rate.getLastCoalesceDate());
    }

    /**
     * After a collapse the grid is aligned again, so the next visit resumes one step
     * per period instead of collapsing forever. Without the realignment the count
     * would climb on every coalesce and a single stall would cost every later step.
     */
    @Test
    public void gridRecoversAfterACollapse() throws Exception {
        Rate rate = new Rate(ALWAYS_DUE_PERIOD);
        Thread.sleep(25);
        rate.addData(1, 1);
        rate.coalesce();
        assertEquals(1, rate.getCoalesceBacklogCollapses());
        long collapsedStamp = rate.getLastCoalesceDate();

        rate.addData(2, 1);
        rate.coalesce();

        assertEquals("a realigned grid must not collapse again",
                     1, rate.getCoalesceBacklogCollapses());
        assertEquals(2, rate.getCoalesceCount());
        List<Rate.CoalescedSample> got = rate.getRecentSamples(4);
        assertEquals(2, got.size());
        assertEquals("the first retained sample is the collapsed backlog",
                     collapsedStamp, got.get(0).timestampMs);
        assertTrue("the grid resumes one period along", got.get(1).timestampMs > got.get(0).timestampMs);
    }

    /**
     * The grid instant and the real visit instant are separate things, and conflating
     * them would rescale {@code _lastTotalEventTime} by the grid error — a few ms of
     * timer jitter — instead of by how long the data actually accrued. The grid
     * error is negative whenever the visit lands before the due instant, which turns
     * the rescale negative and the whole saturation family of statistics with it.
     */
    @Test
    public void eventTimeIsRescaledByRealElapsedTimeNotTheGridError() throws Exception {
        Rate rate = new Rate(NEAR_SLACK_PERIOD);
        rate.addData(1, 100);       // 100ms of processing, in the current period
        rate.coalesce();            // not due, so this is a skip and the data is kept
        assertEquals(1, rate.getCoalesceSkips());

        Thread.sleep(150);
        rate.coalesce();            // the first on-time visit, 150ms in

        // Second period. This is the case the two instants exist for: the visit is a
        // whole period of real time after the last one, so the real span is a full
        // period and the rescale must be ~1, while the grid error is only the
        // overshoot and would blow the factor up by orders of magnitude.
        rate.addData(1, 100);
        long previousVisit = rate.getLastCoalesceNow();
        // _lastCoalesceDate is already the next due instant: the last coalesce left
        // the grid there. A visit is due once the grid error reaches
        // period - SLACK, which is 100ms for this period.
        long dueAt = rate.getLastCoalesceDate() + 150;
        while (System.currentTimeMillis() < dueAt)
            Thread.sleep(10);
        rate.coalesce();

        long realSpan = rate.getLastCoalesceNow() - previousVisit;
        assertTrue("the real span must be about one period, was " + realSpan,
                   realSpan >= NEAR_SLACK_PERIOD - 200 && realSpan < NEAR_SLACK_PERIOD + 200);
        int expected = (int) (100 * (double) NEAR_SLACK_PERIOD / realSpan);
        assertTrue("event time must be extrapolated by the real span: got "
                   + rate.getLastTotalEventTime() + ", expected about " + expected,
                   Math.abs(rate.getLastTotalEventTime() - expected) <= 20);
    }
    @Test
    public void statReadsTheShortestPeriodRingAndSumsCounts() throws Exception {
        RateStat rs = new RateStat("ring", "ring source", "tests",
                                   new long[] {3600000, NEAR_SLACK_PERIOD, 60000});
        assertEquals("periods are held in ascending order", NEAR_SLACK_PERIOD, rs.getPeriods()[0]);

        rs.addData(5, 1);
        Thread.sleep(150);
        rs.coalesceStats();

        assertEquals("only the due rate may coalesce", 1, rs.getCoalesceCount());
        assertEquals("the two longer periods were visited early", 2, rs.getCoalesceSkips());
        assertEquals(1, rs.getRate(NEAR_SLACK_PERIOD).getCoalesceCount());
        assertEquals(0, rs.getRate(60000).getCoalesceCount());

        List<Rate.CoalescedSample> got = rs.getRecentSamples(4);
        assertEquals("the stat must read the shortest period's ring", 1, got.size());
        assertEquals(rs.getRate(NEAR_SLACK_PERIOD).getRecentSamples(4).get(0).timestampMs,
                     got.get(0).timestampMs);
        assertEquals(5.0d, got.get(0).totalValue, 0.001d);
    }

    @Test
    public void statRecentSamplesIsNeverNull() {
        RateStat rs = new RateStat("empty", "no data yet", "tests", new long[] {60000});
        assertNotNull(rs.getRecentSamples(8));
        assertTrue(rs.getRecentSamples(8).isEmpty());
        assertEquals(0, rs.getCoalesceCount());
        assertEquals(0, rs.getCoalesceBacklogCollapses());
    }
}
