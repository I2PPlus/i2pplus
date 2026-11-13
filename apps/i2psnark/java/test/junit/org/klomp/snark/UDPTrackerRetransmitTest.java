package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins how long one UDP tracker query waits before it is resent. The reply timer used to be
 * armed at the caller's entire budget, and the tracker's own timeout is longer than every
 * budget the announce code passes - 90 s against a 60 s announce, a 15 s scrape and a 5 s
 * fast update - so the timer fired only once the budget was already spent. The retransmit
 * arm of the wait loop could never run, and a single lost datagram cost the whole announce.
 *
 * @since 0.9.72+
 */
public class UDPTrackerRetransmitTest {

    /** The tracker query timeout before any backoff, as the client uses it. */
    private static final long TRACKER_TIMEOUT = 90 * 1000L;

    /** An ordinary announce's whole budget. */
    private static final long ANNOUNCE_BUDGET = 60 * 1000L;

    /** A scrape's whole budget. */
    private static final long SCRAPE_BUDGET = 15 * 1000L;

    /** A fast UI update's whole budget. */
    private static final long FAST_BUDGET = 5 * 1000L;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one; the
     * decision under test is static and carries no state.
     *
     * @since 0.9.72+
     */
    public UDPTrackerRetransmitTest() {}

    /**
     * The attempt is shorter than the whole budget, which is the whole point: the timer has
     * to fire while there is still time to resend.
     */
    @Test
    public void testAttemptLeavesRoomToResend() {
        assertTrue(UDPTrackerClient.attemptTimeout(TRACKER_TIMEOUT, ANNOUNCE_BUDGET)
                   < ANNOUNCE_BUDGET);
        assertTrue(UDPTrackerClient.attemptTimeout(TRACKER_TIMEOUT, SCRAPE_BUDGET)
                   < SCRAPE_BUDGET);
        assertTrue(UDPTrackerClient.attemptTimeout(TRACKER_TIMEOUT, FAST_BUDGET) < FAST_BUDGET);
    }

    /**
     * Two attempts fit inside every real budget, so one lost datagram leaves a second
     * chance rather than ending the exchange.
     */
    @Test
    public void testTwoAttemptsFitTheBudget() {
        assertFits(ANNOUNCE_BUDGET);
        assertFits(SCRAPE_BUDGET);
        assertFits(FAST_BUDGET);
    }

    /**
     * A tracker whose own timeout is shorter than the budget's share sets the pace, so a
     * backed-off timeout is still honoured rather than being stretched.
     */
    @Test
    public void testShorterTrackerTimeoutWins() {
        assertEquals(3000, UDPTrackerClient.attemptTimeout(3000, ANNOUNCE_BUDGET));
        assertEquals(1, UDPTrackerClient.attemptTimeout(1, ANNOUNCE_BUDGET));
    }

    /**
     * A degenerate budget still yields a positive wait, so the reply timer is never armed
     * at zero or less.
     */
    @Test
    public void testDegenerateBudgetsStayPositive() {
        assertEquals(1, UDPTrackerClient.attemptTimeout(TRACKER_TIMEOUT, 0));
        assertEquals(1, UDPTrackerClient.attemptTimeout(TRACKER_TIMEOUT, 1));
        assertEquals(1, UDPTrackerClient.attemptTimeout(TRACKER_TIMEOUT, -5));
    }

    /**
     * A backed-off tracker timeout, which doubles up to eight times the base, still cannot
     * outlast the budget.
     */
    @Test
    public void testBackedOffTimeoutIsStillCapped() {
        long backedOff = TRACKER_TIMEOUT << 3;
        assertTrue(UDPTrackerClient.attemptTimeout(backedOff, ANNOUNCE_BUDGET) < ANNOUNCE_BUDGET);
        assertEquals(UDPTrackerClient.attemptTimeout(TRACKER_TIMEOUT, ANNOUNCE_BUDGET),
                     UDPTrackerClient.attemptTimeout(backedOff, ANNOUNCE_BUDGET));
    }

    /**
     * Assert the attempt timeout is positive and that two of them fit the budget.
     *
     * @param budget the whole time the caller is prepared to wait, in ms
     */
    private static void assertFits(long budget) {
        long attempt = UDPTrackerClient.attemptTimeout(TRACKER_TIMEOUT, budget);
        assertTrue("attempt " + attempt + " for budget " + budget, attempt > 0);
        assertTrue("attempt " + attempt + " for budget " + budget, attempt * 2 <= budget);
    }
}
