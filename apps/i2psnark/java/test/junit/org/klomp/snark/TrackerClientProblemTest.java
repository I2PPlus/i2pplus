package org.klomp.snark;

import java.io.IOException;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for tracker error classification in {@link TrackerClient}.
 *
 * <p>The defect: {@code IOException.getMessage()} returns null for several subtypes,
 * and the announce loop lower-cased it unguarded. That NPE escaped the catch block and
 * aborted the announce for every remaining tracker because one tracker sent something
 * unparseable - so a single bad response took down the whole announce cycle, which is
 * indistinguishable in the log from every tracker failing at once.
 *
 * @since 0.9.71+
 */
public class TrackerClientProblemTest {

    // ---- normalizeTrackerProblem ----

    /** The regression: a null message must not blow up the announce loop. */
    @Test
    public void aNullMessageNormalizesToEmpty() {
        assertEquals("", TrackerClient.normalizeTrackerProblem(null));
    }

    @Test
    public void messagesAreLowerCased() {
        assertEquals("torrent not registered",
                     TrackerClient.normalizeTrackerProblem("Torrent Not Registered"));
    }

    @Test
    public void surroundingWhitespaceIsNotStripped() {
        // Pinned deliberately: the prefix checks are startsWith, so a leading space
        // is a classification miss. Documenting it stops anyone assuming otherwise.
        assertEquals(" torrent not registered",
                     TrackerClient.normalizeTrackerProblem(" Torrent Not Registered"));
    }

    @Test
    public void anEmptyMessageStaysEmpty() {
        assertEquals("", TrackerClient.normalizeTrackerProblem(""));
    }

    // ---- isRegistrationFailure ----

    @Test
    public void eachKnownRejectionMessageIsClassified() {
        for (String msg : new String[] {
                "Torrent not registered",
                "Torrent not found",
                "Torrent unauthorised",
                "Received HTML (invalid response)" }) {
            assertTrue(msg + " should be a registration failure",
                       TrackerClient.isRegistrationFailure(
                               TrackerClient.normalizeTrackerProblem(msg)));
        }
    }

    @Test
    public void aTransientFailureIsNotARegistrationFailure() {
        assertFalse(TrackerClient.isRegistrationFailure(
                TrackerClient.normalizeTrackerProblem("Read timed out")));
    }

    @Test
    public void aConnectionFailureIsNotARegistrationFailure() {
        assertFalse(TrackerClient.isRegistrationFailure(
                TrackerClient.normalizeTrackerProblem("Connection refused")));
    }

    /** The empty string a null message becomes must not match any prefix. */
    @Test
    public void anEmptyProblemIsNotARegistrationFailure() {
        assertFalse(TrackerClient.isRegistrationFailure(
                TrackerClient.normalizeTrackerProblem(null)));
    }

    /**
     * A prefix match must stay a prefix match: a message that merely contains the
     * phrase is not the tracker's own rejection.
     */
    @Test
    public void thePhraseMustBeAtTheStart() {
        assertFalse(TrackerClient.isRegistrationFailure(
                TrackerClient.normalizeTrackerProblem("proxy said: torrent not registered")));
    }

    /**
     * A real null-message exception must survive the whole classification path.
     * This is the end-to-end guard for the announce-loop abort.
     */
    @Test
    public void aRealNullMessageExceptionIsHandled() {
        IOException noMessage = new IOException();
        assertNull(noMessage.getMessage());
        String normalized = TrackerClient.normalizeTrackerProblem(noMessage.getMessage());
        assertFalse(TrackerClient.isRegistrationFailure(normalized));
    }

    @Test
    public void aTimeoutIsReportedWithItsCause() {
        // The reason the cause is now logged: a timeout, a refused connection and
        // "no tunnel available" must be distinguishable from the log line alone.
        IOException timeout = new IOException("Read timed out");
        assertTrue(timeout.toString().contains("Read timed out"));
        assertTrue(timeout.toString().contains("IOException"));
    }
}
