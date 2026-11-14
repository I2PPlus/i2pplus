package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins what the screen log shows when lookups are hidden from the torrent list. A zzzot
 * metadata lookup is a probe, not a torrent the user added: it is deleted minutes after it
 * resolves, and with the list hiding it, its messages are the only place it appears - where
 * they push the traffic about real torrents off the visible log. Hidden list, hidden messages;
 * shown list, kept messages, because then they are the only trace of a lookup in the console.
 *
 * @since 0.9.72+
 */
public class LookupMessageDisplayTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one; the
     * decisions under test are static and carry no state.
     */
    public LookupMessageDisplayTest() {}

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
     * A user torrent, a user-added magnet and a null message are not lookups - the filter
     * must not reach past the probes it was added for.
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
     * With lookups hidden from the list, a lookup's message is left out of the screen log
     * entirely; with the list showing lookups it is shown, as it is then the only trace of
     * the lookup the user can see.
     */
    @Test
    public void testTheMessageIsShownOnlyWhenTheListShowsLookups() {
        String msg = "Starting torrent: Lookup [abcd1234]";
        assertTrue(SnarkManager.hidesLookupMessage(true, msg));
        assertFalse(SnarkManager.hidesLookupMessage(false, msg));
    }

    /**
     * A message about anything else is shown whatever the list does.
     */
    @Test
    public void testOtherMessagesAreAlwaysShown() {
        String msg = "Starting torrent: Ubuntu 24.04.iso";
        assertFalse(SnarkManager.hidesLookupMessage(true, msg));
        assertFalse(SnarkManager.hidesLookupMessage(false, msg));
    }
}
