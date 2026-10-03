package org.klomp.snark;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the stale-lookup sweep's decisions.
 *
 * <p>Why these are separated out: {@code cleanupStaleLookupTorrents()} deletes
 * torrents, and the only thing standing between a user's download and that deletion is
 * the pair of predicates below. They are pure - no router, no torrent, no filesystem -
 * so the boundary cases that matter can be pinned directly rather than inferred from a
 * sweep that has to be provoked.
 *
 * @since 0.9.71+
 */
public class SnarkLookupCleanupDecisionTest {

    /** What a lookup torrent's display name looks like. */
    private static final String LOOKUP_NAME = "Lookup [1a2b3c4d]";
    /** Where a lookup torrent's storage lives. */
    private static final String LOOKUP_PATH = "/tmp/zzzot-lookup-4711/lookup [1a2b3c4d]";
    /** A user's own torrent, which the sweep must never touch. */
    private static final String USER_NAME = "ubuntu-24.04-desktop-amd64";
    private static final String USER_PATH = "/home/user/i2psnark/torrents/ubuntu-24.04-desktop-amd64";

    private static final long NOW = 1_700_000_000_000L;
    private static final long STALE = SnarkManager.LOOKUP_STALE_MS;

    /////////////// identifying our own torrents

    @Test
    public void lookupNameIsRecognised() {
        assertTrue(SnarkManager.isLookupTorrent(LOOKUP_NAME, null));
    }

    @Test
    public void lookupPathIsRecognisedWhenTheNameIsUnusable() {
        // A torrent whose name cannot be read must still be identified by its storage,
        // otherwise it leaks for ever instead of being swept.
        assertTrue(SnarkManager.isLookupTorrent(null, LOOKUP_PATH));
    }

    @Test
    public void lookupPathIsRecognisedEvenWithAUserLikeName() {
        assertTrue(SnarkManager.isLookupTorrent("some torrent", LOOKUP_PATH));
    }

    @Test
    public void userTorrentIsNotALookup() {
        assertFalse("a user's torrent must never be identified as ours",
                    SnarkManager.isLookupTorrent(USER_NAME, USER_PATH));
    }

    @Test
    public void unknownNameAndPathAreNotALookup() {
        assertFalse(SnarkManager.isLookupTorrent(null, null));
    }

    @Test
    public void nameThatMerelyContainsTheWordLookupIsNotOneOfOurs() {
        // The marker is the bracketed prefix, not the substring "lookup", so a torrent
        // a user named "my lookup notes" is left alone.
        assertFalse(SnarkManager.isLookupTorrent("my Lookup notes", null));
    }

    @Test
    public void emptyStringsAreNotALookup() {
        assertFalse(SnarkManager.isLookupTorrent("", ""));
    }

    /////////////// the age boundary

    @Test
    public void freshLookupIsKept() {
        assertFalse(SnarkManager.isStaleLookup(LOOKUP_NAME, LOOKUP_PATH, NOW, NOW));
    }

    @Test
    public void lookupExactlyAtTheThresholdIsKept() {
        // Strictly greater than the threshold, so exactly at it is still in use.
        assertFalse(SnarkManager.isStaleLookup(LOOKUP_NAME, LOOKUP_PATH, NOW - STALE, NOW));
    }

    @Test
    public void lookupOneMsPastTheThresholdIsSwept() {
        assertTrue(SnarkManager.isStaleLookup(LOOKUP_NAME, LOOKUP_PATH, NOW - STALE - 1, NOW));
    }

    @Test
    public void longExpiredLookupIsSwept() {
        assertTrue(SnarkManager.isStaleLookup(LOOKUP_NAME, LOOKUP_PATH, NOW - 60 * STALE, NOW));
    }

    @Test
    public void expiredUserTorrentIsNeverSwept() {
        // Age alone must not be enough: identification and age are both required.
        assertFalse(SnarkManager.isStaleLookup(USER_NAME, USER_PATH, NOW - 60 * STALE, NOW));
    }

    @Test
    public void unknownTorrentIsNeverSwept() {
        assertFalse(SnarkManager.isStaleLookup(null, null, NOW - 60 * STALE, NOW));
    }

    @Test
    public void thresholdIsTenMinutes() {
        // Pinned so the number cannot be changed silently.
        assertTrue("stale threshold should be 10 minutes",
                   SnarkManager.LOOKUP_STALE_MS == 10 * 60 * 1000);
    }
}