package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins how long a screen-log message stays when lookups are hidden from the torrent list.
 * A zzzot metadata lookup is a probe, not a torrent the user added: with the list hiding
 * it, its lines in the screen log are the only place it appears, and they outlive it by
 * hours, pushing the messages about real torrents off the visible log. It is shown for
 * half a minute and then dropped, while every other message keeps the retention it had.
 *
 * @since 0.9.72+
 */
public class LookupMessageLifetimeTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one; the
     * decisions under test are static and carry no state.
     */
    public LookupMessageLifetimeTest() {}

    /**
     * The message shapes a lookup torrent actually produces are recognised: the name is
     * "Lookup [hash8]" and its data directory is named "zzzot-lookup-*".
     */
    @Test
    public void testLookupMessagesAreRecognised() {
        assertTrue(SnarkManager.isLookupMessage(
                "Starting torrent: <a href=\"/torrent/1\">Lookup [abcd1234]</a>"));
        assertTrue(SnarkManager.isLookupMessage(
                "Resolving torrent lookup: <a href=\"/torrent/1\">Lookup [abcd1234]</a>"));
        assertTrue(SnarkManager.isLookupMessage(
                "Removing torrent /tmp/zzzot-lookup-42/Lookup [abcd1234]"));
    }

    /**
     * A user torrent, a user-added magnet and a null message are not lookups - the
     * lifetime must not reach past the probes it was added for.
     */
    @Test
    public void testUserTorrentMessagesAreNot() {
        assertFalse(SnarkManager.isLookupMessage(
                "Starting torrent: <a href=\"/torrent/3\">Ubuntu 24.04.iso</a>"));
        assertFalse(SnarkManager.isLookupMessage("Adding Magnet my-magnet-name"));
        assertFalse(SnarkManager.isLookupMessage("Resolving torrent lookup: Magnet [abcd]"));
        assertFalse(SnarkManager.isLookupMessage(null));
    }

    /**
     * With lookups hidden, a lookup message lives for half a minute; with the list showing
     * lookups it stays as it always did, because then the messages are the only trace of
     * a lookup the user can see.
     */
    @Test
    public void testTheLifetimeAppliesOnlyWhileLookupsAreHidden() {
        String msg = "Starting torrent: Lookup [abcd1234]";
        assertEquals(SnarkManager.LOOKUP_MESSAGE_LIFETIME,
                     SnarkManager.messageLifetime(true, msg));
        assertEquals(0, SnarkManager.messageLifetime(false, msg));
    }

    /**
     * A message about anything else is never given a lifetime, hidden lookups or not.
     */
    @Test
    public void testOtherMessagesAreNeverExpired() {
        String msg = "Starting torrent: Ubuntu 24.04.iso";
        assertEquals(0, SnarkManager.messageLifetime(true, msg));
        assertEquals(0, SnarkManager.messageLifetime(false, msg));
    }

    /**
     * The lifetime is long enough to read and short enough that a probe does not sit in the
     * log for the rest of the session.
     */
    @Test
    public void testTheLifetimeIsReadableButBrief() {
        long secs = SnarkManager.LOOKUP_MESSAGE_LIFETIME / 1000;
        assertTrue(Long.toString(secs), secs >= 15);
        assertTrue(Long.toString(secs), secs <= 60);
    }
}
