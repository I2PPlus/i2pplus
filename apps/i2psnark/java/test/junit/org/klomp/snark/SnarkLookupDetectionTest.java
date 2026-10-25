package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;

import org.junit.Test;

/**
 * Tests for Snark.isLookupTorrent, the marker check that identifies the
 * transient magnets SnarkManager.lookupTorrentName creates for zzzot
 * (name "Lookup [hash8]" under a "zzzot-lookup-*" temp directory). The
 * tracker-skip and the storage-skip both rely on this predicate, so user
 * torrents must never match it.
 *
 * @since 0.9.72+
 */
public class SnarkLookupDetectionTest {

    @Test
    public void testLookupNameMatches() {
        assertTrue(Snark.isLookupTorrent("Lookup [AE42B6A9]", null));
        assertTrue(Snark.isLookupTorrent("lookup-old-style", null));
        assertTrue(Snark.isLookupTorrent("whatever zzzot-lookup whatever", null));
    }

    @Test
    public void testLookupDirMatchesEvenWithoutName() {
        File dir = new File("/tmp/zzzot-lookup-1234567890");
        assertTrue(Snark.isLookupTorrent(null, dir));
        assertTrue(Snark.isLookupTorrent("Some user name", dir));
    }

    @Test
    public void testUserTorrentDoesNotMatch() {
        assertFalse(Snark.isLookupTorrent("Ubuntu 24.04.iso", new File("/home/user/i2psnark/Ubuntu 24.04")));
        assertFalse(Snark.isLookupTorrent("Magnet AE42B6A9D1919D4C0B41452AD635D4BC65D82799", null));
        assertFalse(Snark.isLookupTorrent(null, null));
    }

    /**
     * A user torrent whose name merely starts with "lookup" (no bracket or
     * dash marker) must not be mistaken for a transient lookup.
     */
    @Test
    public void testNearMissNamesDoNotMatch() {
        assertFalse(Snark.isLookupTorrent("lookup guide.pdf", null));
        assertFalse(Snark.isLookupTorrent("Lookup", null));
        assertFalse(Snark.isLookupTorrent("lookup", null));
    }
}
