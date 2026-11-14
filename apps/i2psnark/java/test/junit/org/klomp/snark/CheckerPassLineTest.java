package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the one line a check pass reports, and with it the rule that lets the pass stay
 * quiet. The checker runs every {@link PeerCoordinator#CHECK_PERIOD} whatever else is
 * happening, and it used to print its tally on every pass: 2,647 lines in a four hour
 * window, nearly all of them repeating the last. The pass now compares its line against
 * the last one it printed and says nothing when they match, so two passes with the same
 * numbers must produce the same string, and any number that moves must produce a
 * different one.
 *
 * @since 0.9.72+
 */
public class CheckerPassLineTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     */
    public CheckerPassLineTest() {}

    /**
     * Two passes with the same numbers produce the same line, which is what lets the
     * second one stay unprinted.
     */
    @Test
    public void testAnUnchangedPassProducesTheSameLine() {
        assertEquals(PeerCheckerTask.passLine(4, 1, 2, 1, 0, false),
                     PeerCheckerTask.passLine(4, 1, 2, 1, 0, false));
    }

    /**
     * Every number the pass reports moves the line when it moves, so a pass that has
     * anything new to say is never mistaken for one that has not.
     */
    @Test
    public void testAnyNumberThatMovesChangesTheLine() {
        String base = PeerCheckerTask.passLine(4, 1, 2, 1, 0, false);
        assertNotEquals(base, PeerCheckerTask.passLine(5, 1, 2, 1, 0, false));
        assertNotEquals(base, PeerCheckerTask.passLine(4, 2, 2, 1, 0, false));
        assertNotEquals(base, PeerCheckerTask.passLine(4, 1, 3, 1, 0, false));
        assertNotEquals(base, PeerCheckerTask.passLine(4, 1, 2, 0, 0, false));
        assertNotEquals(base, PeerCheckerTask.passLine(4, 1, 2, 1, 1, false));
        assertNotEquals(base, PeerCheckerTask.passLine(4, 1, 2, 1, 0, true));
    }

    /**
     * A pass that choked nobody and is within its slots carries no second line - the
     * two abnormal states are reported only when they happen.
     */
    @Test
    public void testTheAbnormalStatesAreAbsentWhenTheyDidNotHappen() {
        String line = PeerCheckerTask.passLine(4, 1, 2, 1, 0, false);
        assertFalse(line, line.contains("Choked"));
        assertFalse(line, line.contains("Over the upload limit"));
        assertTrue(PeerCheckerTask.passLine(4, 1, 2, 1, 3, false),
                   PeerCheckerTask.passLine(4, 1, 2, 1, 3, false).contains("Choked: 3 peers"));
        assertTrue(PeerCheckerTask.passLine(4, 1, 2, 1, 0, true).contains("Over the upload limit"));
    }

    /**
     * The line leads with the event and points the arrow at what it counted, and every
     * count names what it counts - the form the logging guide requires of a tally.
     */
    @Test
    public void testTheLineNamesWhatItCounts() {
        String line = PeerCheckerTask.passLine(4, 1, 2, 1, 0, false);
        assertTrue(line, line.startsWith("Peer check pass -> "));
        assertTrue(line, line.contains("Peers checked: 4"));
        assertTrue(line, line.contains("Uploaders: 1 of 2"));
        assertTrue(line, line.contains("Interested: 1"));
    }
}
