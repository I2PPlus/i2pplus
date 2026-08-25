package org.klomp.snark.web;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for I2PSnarkServlet torrent-list header helpers extracted from
 * appendSnarkHeader: sort-cycle ladders and the shared URL joiner that
 * replaced six hand-rolled separator compositions (double-'?' bug).
 *
 * @since 0.9.71+
 */
public class AppendSnarkHeaderHelpersTest {

    // ---- Status column cycle ----

    @Test
    public void testNextStatusSortSingleDest() {
        assertEquals("2", I2PSnarkServlet.nextStatusSort("-2", false));
        assertEquals("-2", I2PSnarkServlet.nextStatusSort("2", false));
        // pool keys unreachable without pool mode; restart at desc
        assertEquals("-2", I2PSnarkServlet.nextStatusSort("13", false));
        assertEquals("-2", I2PSnarkServlet.nextStatusSort(null, false));
    }

    @Test
    public void testNextStatusSortPoolDest() {
        assertEquals("2", I2PSnarkServlet.nextStatusSort("-2", true));
        assertEquals("13", I2PSnarkServlet.nextStatusSort("2", true));
        assertEquals("-13", I2PSnarkServlet.nextStatusSort("13", true));
        assertEquals("-2", I2PSnarkServlet.nextStatusSort("-13", true));
        assertEquals("-2", I2PSnarkServlet.nextStatusSort(null, true));
    }

    @Test
    public void testNextStatusSortCycleClosure() {
        // single-dest: two states forever
        String s = "-2";
        for (int i = 0; i < 4; i++) {s = I2PSnarkServlet.nextStatusSort(s, false);}
        assertEquals("-2", s);
        // pool: four states before returning home
        s = "-2";
        for (int i = 0; i < 4; i++) {s = I2PSnarkServlet.nextStatusSort(s, true);}
        assertEquals("-2", s);
    }

    // ---- Torrent name/type ladder ----

    @Test
    public void testNextTorrentNameTypeSort() {
        assertEquals("-1", I2PSnarkServlet.nextTorrentNameTypeSort(null));
        assertEquals("-1", I2PSnarkServlet.nextTorrentNameTypeSort("0"));
        assertEquals("-1", I2PSnarkServlet.nextTorrentNameTypeSort("1"));
        assertEquals("12", I2PSnarkServlet.nextTorrentNameTypeSort("-1"));
        assertEquals("-12", I2PSnarkServlet.nextTorrentNameTypeSort("12"));
        // unlike the directory-page cycle, foreign keys restart at name asc
        assertEquals("1", I2PSnarkServlet.nextTorrentNameTypeSort("-12"));
        assertEquals("1", I2PSnarkServlet.nextTorrentNameTypeSort("garbage"));
    }

    @Test
    public void testLaddersDivergeOnlyOnFallback() {
        // document the intentional divergence between the two ladders
        assertEquals(I2PSnarkServlet.nextNameTypeSort(null),
                     I2PSnarkServlet.nextTorrentNameTypeSort(null));
        assertNotEquals(I2PSnarkServlet.nextNameTypeSort("-12"),
                        I2PSnarkServlet.nextTorrentNameTypeSort("-12"));
    }

    // ---- RX / TX four-state ladders ----

    @Test
    public void testNextRXSort() {
        assertEquals("5", I2PSnarkServlet.nextRXSort("-5"));
        assertEquals("-6", I2PSnarkServlet.nextRXSort("5"));
        assertEquals("6", I2PSnarkServlet.nextRXSort("-6"));
        assertEquals("-5", I2PSnarkServlet.nextRXSort("6"));
        assertEquals("-5", I2PSnarkServlet.nextRXSort(null));
        assertEquals("-5", I2PSnarkServlet.nextRXSort("bogus"));
    }

    @Test
    public void testNextTXSort() {
        assertEquals("7", I2PSnarkServlet.nextTXSort("-7"));
        assertEquals("-11", I2PSnarkServlet.nextTXSort("7"));
        assertEquals("11", I2PSnarkServlet.nextTXSort("-11"));
        assertEquals("-7", I2PSnarkServlet.nextTXSort("11"));
        assertEquals("-7", I2PSnarkServlet.nextTXSort(null));
        assertEquals("-7", I2PSnarkServlet.nextTXSort("bogus"));
    }

    // ---- URL joiner ----

    private static final String PATH = "/i2psnark/";

    @Test
    public void testBuildLinkQuestionMarkQuery() {
        // the live-page bug: helper output already carries '?'
        assertEquals(PATH + "?sort=-2&filter=all",
                     I2PSnarkServlet.buildLink(PATH, "?sort=-2", "filter=all"));
    }

    @Test
    public void testBuildLinkBareAndEmptyQueries() {
        assertEquals(PATH + "?sort=-2&filter=all",
                     I2PSnarkServlet.buildLink(PATH, "sort=-2", "filter=all"));
        assertEquals(PATH + "?filter=all",
                     I2PSnarkServlet.buildLink(PATH, "", "filter=all"));
    }

    @Test
    public void testBuildLinkEmptyExtra() {
        assertEquals(PATH + "?p=1", I2PSnarkServlet.buildLink(PATH, "?p=1", ""));
        assertEquals(PATH + "?", I2PSnarkServlet.buildLink(PATH, "", ""));
    }
}
