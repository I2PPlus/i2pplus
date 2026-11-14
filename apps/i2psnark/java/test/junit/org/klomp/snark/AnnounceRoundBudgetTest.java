package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the wall-clock budget one announce round spends on trackers, and the interval
 * rule that makes spending it safely possible. An announce is a blocking fetch that
 * EepGet retries, with a scrape behind it, so a tracker that is not answering costs
 * minutes: measured rounds on this router ran 5 to 17 minutes, on torrents whose
 * fastest announce cadence is 90 seconds, so each round outlasted the interval it
 * schedules and peer discovery stayed that far out of date. The round now stops
 * starting new exchanges once its budget is gone, which is only safe because a tracker
 * skipped this way is tried again as soon as its own interval comes round.
 *
 * @since 0.9.72+
 */
public class AnnounceRoundBudgetTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     */
    public AnnounceRoundBudgetTest() {}

    /**
     * The deadline is the round's start plus the budget, and the budget never exceeds
     * the fastest cadence the loop schedules - a round that outruns the wait it just
     * queued is the pathology this bound exists to remove.
     */
    @Test
    public void testTheDeadlineIsTheBudgetAndNeverOutrunsTheCadence() {
        assertEquals(1_000L + TrackerClient.ROUND_BUDGET_MS, TrackerClient.roundDeadline(1_000L));
        assertTrue("the round budget must fit inside the fastest announce cadence",
                   TrackerClient.ROUND_BUDGET_MS <= TrackerClient.INITIAL_SLEEP);
    }

    /**
     * A tracker is not due again before its own interval has passed, and is due once it
     * has - the rule that makes skipping a tracker on a spent round cost nothing.
     */
    @Test
    public void testATrackerIsDueWhenItsOwnIntervalHasPassed() {
        long last = 100_000L;
        long interval = 30 * 60 * 1000L;
        assertFalse(TrackerClient.trackerDue(false, false, false, true, true, false, false,
                                             last + interval, last, interval));
        assertTrue(TrackerClient.trackerDue(false, false, false, true, true, false, false,
                                            last + interval + 1, last, interval));
    }

    /**
     * An announce that just finished the download, or just brought the metadata, is
     * always due whatever the interval says - the tracker has to be told, and a magnet
     * that has just become a torrent has never been registered anywhere.
     */
    @Test
    public void testAStateChangeIsAlwaysDue() {
        long last = 100_000L;
        long interval = 30 * 60 * 1000L;
        assertTrue(TrackerClient.trackerDue(false, false, false, true, true, true, false,
                                            last + 1, last, interval));
        assertTrue(TrackerClient.trackerDue(false, false, false, true, true, false, true,
                                            last + 1, last, interval));
    }

    /**
     * A client or tracker that is stopping is never due, whatever else holds.
     */
    @Test
    public void testAStoppedTrackerIsNeverDue() {
        long last = 100_000L;
        long interval = 1L;
        assertTrue(TrackerClient.trackerDue(false, false, true, false, true, false, false,
                                            last + interval + 1, last, interval));
        assertFalse(TrackerClient.trackerDue(true, false, true, false, true, false, false,
                                             last + interval + 1, last, interval));
        assertFalse(TrackerClient.trackerDue(false, true, true, false, true, false, false,
                                             last + interval + 1, last, interval));
    }

    /**
     * A torrent that is neither complete nor wanting another connection still has to
     * register a tracker it has never reached, and has no reason to keep announcing to
     * one that has answered it.
     */
    @Test
    public void testAnUnregisteredTrackerIsDueEvenWhenNoPeersAreWanted() {
        long last = 100_000L;
        long interval = 1L;
        long now = last + interval + 1;
        assertTrue(TrackerClient.trackerDue(false, false, false, false, false, false, false, now,
                                            last, interval));
        assertFalse(TrackerClient.trackerDue(false, false, false, false, true, false, false, now,
                                             last, interval));
    }
}
