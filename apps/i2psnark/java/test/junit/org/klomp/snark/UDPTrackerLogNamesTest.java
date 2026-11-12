package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the words a private-tracker log line uses for a BEP 15 action or announce
 * event. A bare number is not a finding - {@code "Sent 1"} says nothing - and the
 * number beside the word, {@code "announce (Action: 1)"}, repeats a fact the word
 * already states and eats the line's width, so the word alone is what the log
 * prints and what a reader greps.
 *
 * @since 0.9.72+
 */
public class UDPTrackerLogNamesTest {

    /**
     * Test instance; every test calls a static naming method.
     */
    public UDPTrackerLogNamesTest() {
    }

    /** BEP 15 action 0, connect. */
    private static final int ACTION_CONNECT = 0;
    /** BEP 15 action 1, announce. */
    private static final int ACTION_ANNOUNCE = 1;
    /** BEP 15 action 2, scrape. */
    private static final int ACTION_SCRAPE = 2;
    /** BEP 15 action 3, error reply. */
    private static final int ACTION_ERROR = 3;

    /** Every action is one greppable word, with no code and no parentheses. */
    @Test
    public void actionsAreNamedInWordsOnly() {
        assertEquals("connect", UDPTrackerClient.actionName(ACTION_CONNECT));
        assertEquals("announce", UDPTrackerClient.actionName(ACTION_ANNOUNCE));
        assertEquals("scrape", UDPTrackerClient.actionName(ACTION_SCRAPE));
        assertEquals("error", UDPTrackerClient.actionName(ACTION_ERROR));
        for (int action : new int[] {ACTION_CONNECT, ACTION_ANNOUNCE, ACTION_SCRAPE,
                                     ACTION_ERROR, 42}) {
            String s = UDPTrackerClient.actionName(action);
            assertFalse(action + ": " + s, s.contains("("));
            assertFalse(action + ": " + s, s.matches(".*\\d.*"));
        }
    }

    /** An action outside BEP 15 is named in prose, not printed as a bare number. */
    @Test
    public void unknownActionIsNamedInProse() {
        assertEquals("unknown", UDPTrackerClient.actionName(99));
    }

    /** Every announce event is one word, and an announce with no event says none. */
    @Test
    public void eventsAreNamedInWordsOnly() {
        assertEquals("completed", UDPTrackerClient.eventName(UDPTrackerClient.EVENT_COMPLETED));
        assertEquals("started", UDPTrackerClient.eventName(UDPTrackerClient.EVENT_STARTED));
        assertEquals("stopped", UDPTrackerClient.eventName(UDPTrackerClient.EVENT_STOPPED));
        assertEquals("paused", UDPTrackerClient.eventName(UDPTrackerClient.EVENT_PAUSED));
        assertEquals("none", UDPTrackerClient.eventName(7));
        assertFalse(UDPTrackerClient.eventName(UDPTrackerClient.EVENT_STARTED).contains("("));
    }
}
