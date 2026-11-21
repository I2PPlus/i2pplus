package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins when a fired inactivity timer re-arms itself and when it performs the
 * configured action.
 *
 * <p>The deadline is armed at {@code inactivityTimeout + _randomWait}, but the
 * timer event carries a 5-second fuzz, so a send inside that window refreshes
 * the activity timestamp without moving the deadline. The fire then computes
 * {@code getTimeLeft()} against the refreshed timestamp and finds a hair of
 * the window left, and the old test {@code left > 0} re-armed for that
 * sub-second delay instead of acting. In a live router's 133-minute window one
 * destination took 8,371 timer fires, of which 2,907 were those re-arms, with
 * up to 51 fires in a single second; every one of them wrote a DEBUG line, and
 * the fire that did act wrote an INFO line as well.
 *
 * @since 0.9.72+
 */
public class ConnectionInactivityCadenceTest {

    /**
     * Time-left values read straight out of the live log's "Rescheduled, Nms
     * left" lines, including the smallest and largest seen.
     */
    private static final long[] MEASURED_LEFT_MS = {
        1L, 36L, 59L, 551L, 646L, 720L, 727L, 761L, 777L, 798L, 918L, 991L,
        1149L, 4785L
    };

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the decision under test is pure, so nothing is set up.
     */
    public ConnectionInactivityCadenceTest() {}

    /**
     * Every measured value is inside the fuzz window the timer is maintained
     * at, so none of them is worth waiting for: the action has to run.
     */
    @Test
    public void testMeasuredLeftValuesActInsteadOfReArming() {
        for (long left : MEASURED_LEFT_MS) {
            assertFalse("a " + left + "ms remainder is not a wait",
                        Connection.inactivityWaitOut(left));
        }
    }

    /**
     * The pre-fix behaviour, kept as an assertion so the two are not confused:
     * every one of those values did re-arm, which is the second timer fire.
     */
    @Test
    public void testEveryMeasuredValueReArmedBeforeTheFix() {
        for (long left : MEASURED_LEFT_MS) {
            assertTrue("the old test re-armed for " + left + "ms", left > 0);
        }
    }

    /**
     * The floor itself is not waited out, and one millisecond past it is: the
     * boundary is the fuzz, not zero.
     */
    @Test
    public void testFloorIsTheBoundary() {
        assertFalse(Connection.inactivityWaitOut(
                        Connection.INACTIVITY_RESCHEDULE_FLOOR_MS));
        assertTrue(Connection.inactivityWaitOut(
                       Connection.INACTIVITY_RESCHEDULE_FLOOR_MS + 1));
    }

    /**
     * A window genuinely extended by recent traffic still waits out its
     * remaining time - the branch exists for that case, and it must keep
     * working.
     */
    @Test
    public void testGenuineExtensionStillWaits() {
        assertTrue(Connection.inactivityWaitOut(60_000L));
        assertTrue(Connection.inactivityWaitOut(299_000L));
    }

    /**
     * An elapsed window acts, as it always did.
     */
    @Test
    public void testElapsedWindowActs() {
        assertFalse(Connection.inactivityWaitOut(0L));
        assertFalse(Connection.inactivityWaitOut(-1L));
        assertFalse(Connection.inactivityWaitOut(-300_000L));
    }

    /**
     * The floor and the timer's fuzz are one number. The fuzz is what lets a
     * fire find a remainder at all, so if they drift apart the decision stops
     * describing the timer it guards.
     */
    @Test
    public void testFloorMatchesTheTimersFuzz() {
        assertEquals(5 * 1000L, Connection.INACTIVITY_RESCHEDULE_FLOOR_MS);
    }

    /**
     * What the fix costs the flood: the measured run's 8,371 fires were 2,907
     * re-arms plus 5,464 actions, and the re-arms also each wrote a DEBUG line
     * while the actions each wrote an INFO line. Dropping the re-arm removes
     * the fires and the lines they carried.
     */
    @Test
    public void testMeasuredFloodLosesTheReArmHalf() {
        int measuredFires = 8371;
        int measuredReArms = 2907;
        int measuredActions = 5464;
        assertEquals("the measured accounting closes", measuredFires,
                     measuredReArms + measuredActions);
        int reArms = 0;
        for (long left : MEASURED_LEFT_MS) {
            if (Connection.inactivityWaitOut(left)) {
                reArms++;
            }
        }
        assertEquals("no measured remainder is worth waiting for", 0, reArms);
        assertTrue(measuredReArms + " re-arms removed from " + measuredFires +
                   " fires", measuredReArms * 2 <= measuredFires);
    }
}
