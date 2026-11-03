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

    // ---- trackerHost ----

    /**
     * The announce path is the same on every HTTP announce and the thread name
     * says this is an announce, so the host alone is the fact.
     */
    @Test
    public void announcePathIsCut() {
        assertEquals("sigmatracker.i2p", TrackerClient.trackerHost("http://sigmatracker.i2p/a"));
        assertEquals("tracker.example.i2p", TrackerClient.trackerHost("http://tracker.example.i2p/announce"));
    }

    /** A UDP endpoint is identified by its port, so the port stays. */
    @Test
    public void udpEndpointKeepsItsPort() {
        assertEquals("tracker.insulaocculta.i2p:6969",
                     TrackerClient.trackerHost("udp://tracker.insulaocculta.i2p:6969/"));
    }

    /** A known base32 tracker still renders as its symbolic name. */
    @Test
    public void knownBase32TrackerBecomesItsName() {
        assertEquals("opentracker.dg2.i2p",
                     TrackerClient.trackerHost("http://w7tpbzncbcocrqtwwm3nezhnnsw4ozadvi2hmvzdhrqzfxfum7wa.b32.i2p/a"));
    }

    /** No URL, no host - and no exception out of the log line. */
    @Test
    public void nullAnnounceGivesEmptyHost() {
        assertEquals("", TrackerClient.trackerHost(null));
    }

    // ---- trackerFailure ----

    /**
     * The defect: the line named the tracker, and the cause named it again, so
     * one tracker's failure read as two.
     */
    @Test
    public void hostCutFromTheEndOfTheReason() {
        assertEquals("No response",
                     TrackerClient.trackerFailure("sigmatracker.i2p",
                                                  new IOException("No response from sigmatracker.i2p")));
        assertEquals("UDP announce error",
                     TrackerClient.trackerFailure("tracker.insulaocculta.i2p",
                                                  new IOException("UDP announce error to: tracker.insulaocculta.i2p")));
    }

    /**
     * The UDP client names the host without the port the announce URL carries,
     * so the host is tried both with and without its port.
     */
    @Test
    public void hostIsCutWithAndWithoutThePort() {
        assertEquals("UDP announce error",
                     TrackerClient.trackerFailure("tracker.insulaocculta.i2p:6969",
                                                  new IOException("UDP announce error to: tracker.insulaocculta.i2p")));
    }

    /**
     * An IPv6 literal has colons with no port after them, so nothing is split off
     * it - a message naming the bracketed host without the port is passed through
     * rather than cut at a colon that is not a port separator.
     */
    @Test
    public void ipv6HostIsNotSplitAtAColon() {
        String msg = "No response from [::1]";
        assertEquals(msg, TrackerClient.trackerFailure("[::1]:6969", new IOException(msg)));
    }

    /** A reason that does not end with the host is passed through untouched. */
    @Test
    public void reasonWithoutTheHostIsUnchanged() {        assertEquals("Connection refused", TrackerClient.trackerFailure("sigmatracker.i2p",
                                                                       new IOException("Connection refused")));
        // Only a trailing clause is cut - the host mid-sentence is part of the fact.
        assertEquals("No response from sigmatracker.i2p and others",
                     TrackerClient.trackerFailure("sigmatracker.i2p",
                                                  new IOException("No response from sigmatracker.i2p and others")));
    }

    /** A message-less failure still says what failed. */
    @Test
    public void messagelessFailureNamesTheClass() {
        assertEquals("IOException", TrackerClient.trackerFailure("sigmatracker.i2p", new IOException()));
    }
}
