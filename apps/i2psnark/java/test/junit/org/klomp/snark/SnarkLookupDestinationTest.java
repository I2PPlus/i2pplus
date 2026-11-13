package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;

import org.junit.Test;

/**
 * Pins which torrents get their own I2P destination in multi-destination mode. A zzzot
 * metadata lookup is a throwaway torrent: it lives for minutes, and an unresolved hash
 * is retried for hours, so a destination per cycle means a session and a set of tunnels
 * built and torn down over and over for a torrent that only ever reports a name and a
 * size. These tests pin the markers that identify such a lookup and the decision that
 * keeps it on the shared destination.
 *
 * @since 0.9.72+
 */
public class SnarkLookupDestinationTest {

    /** A normal torrent's data directory. */
    private static final File USER_DIR = new File("/data/torrents");

    /** The temp directory a zzzot lookup is created under. */
    private static final File LOOKUP_DIR = new File("/tmp/zzzot-lookup-4912");

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one;
     * the decision under test is static and carries no state.
     *
     * @since 0.9.72+
     */
    public SnarkLookupDestinationTest() {}

    /**
     * A real torrent in multi-destination mode keeps its own destination; with the
     * mode off nothing has one.
     */
    @Test
    public void testUserTorrentKeepsItsDestination() {
        assertTrue(Snark.usesTorrentDestination(true, "My Movie", USER_DIR));
        assertFalse(Snark.usesTorrentDestination(false, "My Movie", USER_DIR));
    }

    /**
     * A lookup named by the hash, or living under the lookup temp directory, rides the
     * shared destination.
     */
    @Test
    public void testLookupSharesTheDestination() {
        assertFalse(Snark.usesTorrentDestination(true, "Lookup [ab12cd34]", USER_DIR));
        assertFalse(Snark.usesTorrentDestination(true, "lookup-ab12cd34", USER_DIR));
        assertFalse(Snark.usesTorrentDestination(true, "My Movie", LOOKUP_DIR));
    }

    /**
     * A torrent whose name and directory are both unknown is not a lookup: the markers
     * are what identify one, and their absence means the torrent is a real one.
     */
    @Test
    public void testUnknownNameAndDirIsNotALookup() {
        assertTrue(Snark.usesTorrentDestination(true, null, null));
    }

    /**
     * The lookup markers themselves, pinned separately because the destination decision
     * and the console's hide-lookups filter both read them: the name can be replaced
     * when the metadata arrives, which is why the directory is checked too.
     */
    @Test
    public void testLookupMarkers() {
        assertTrue(Snark.isLookupTorrent("Lookup [ab12cd34]", USER_DIR));
        assertTrue(Snark.isLookupTorrent("lookup-ab12cd34", USER_DIR));
        assertTrue(Snark.isLookupTorrent("My Movie", LOOKUP_DIR));
        assertFalse(Snark.isLookupTorrent("My Movie", USER_DIR));
        assertFalse(Snark.isLookupTorrent(null, null));
    }
}
