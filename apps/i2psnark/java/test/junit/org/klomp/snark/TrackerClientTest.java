package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for TrackerClient.scrapeURL, the BEP 48 scrape URL builder, and
 * needBackupTrackers, the backup-tracker consultation policy for
 * trackerless torrents.
 */
public class TrackerClientTest {

    /** The last path segment is replaced. */
    @Test
    public void testScrapeURL() {
        assertEquals("http://host/scrape", TrackerClient.scrapeURL("http://host/announce"));
    }

    /** Query strings are dropped before replacing the last segment. */
    @Test
    public void testScrapeURLQuery() {
        assertEquals(
                "http://host/scrape",
                TrackerClient.scrapeURL("http://host/announce?info_hash=abc&port=123"));
    }

    /** A trailing slash is ignored, so the last non-empty segment is replaced. */
    @Test
    public void testScrapeURLTrailingSlash() {
        assertEquals("http://host/scrape", TrackerClient.scrapeURL("http://host/announce/"));
    }

    /** Only the last segment is replaced. */
    @Test
    public void testScrapeURLNested() {
        assertEquals(
                "http://host/tracker/scrape",
                TrackerClient.scrapeURL("http://host/tracker/announce"));
    }

    /** A bare host gains a scrape segment. */
    @Test
    public void testScrapeURLBareHost() {
        assertEquals("http://host/scrape", TrackerClient.scrapeURL("http://host"));
        assertEquals("http://host/scrape", TrackerClient.scrapeURL("http://host/"));
    }

    /** A downloading trackerless torrent consults backup trackers regardless of DHT size. */
    @Test
    public void testNeedBackupDownloadWithDHT() {
        assertTrue(
                TrackerClient.needBackupTrackers(false, true, true, 100, true));
    }

    /** A downloading trackerless torrent consults backup trackers even with a tiny DHT. */
    @Test
    public void testNeedBackupDownloadTinyDHT() {
        assertTrue(
                TrackerClient.needBackupTrackers(false, true, true, 2, true));
    }

    /** A downloading trackerless torrent consults backup trackers even with DHT disabled. */
    @Test
    public void testNeedBackupDownloadNoDHT() {
        assertTrue(
                TrackerClient.needBackupTrackers(false, true, false, 0, true));
    }

    /** A seeding trackerless torrent is skipped once the DHT has enough nodes. */
    @Test
    public void testNeedBackupSeedHealthyDHT() {
        assertFalse(
                TrackerClient.needBackupTrackers(false, true, true, 100, false));
        assertFalse(
                TrackerClient.needBackupTrackers(false, true, true, 16, false));
    }

    /** A seeding trackerless torrent still uses backup trackers while the DHT bootstraps. */
    @Test
    public void testNeedBackupSeedTinyDHT() {
        assertTrue(
                TrackerClient.needBackupTrackers(false, true, true, 15, false));
        assertTrue(
                TrackerClient.needBackupTrackers(false, true, true, 0, false));
    }

    /** A seeding trackerless torrent with no DHT has nothing to bootstrap. */
    @Test
    public void testNeedBackupSeedNoDHT() {
        assertFalse(
                TrackerClient.needBackupTrackers(false, true, false, 0, false));
    }

    /** Torrents with their own primary trackers never consult backup trackers. */
    @Test
    public void testNeedBackupHasPrimary() {
        assertFalse(
                TrackerClient.needBackupTrackers(true, true, true, 100, true));
        assertFalse(
                TrackerClient.needBackupTrackers(true, true, false, 0, true));
        assertFalse(
                TrackerClient.needBackupTrackers(true, false, true, 100, false));
    }

    /** No backup trackers configured means nothing to consult. */
    @Test
    public void testNeedBackupNoneConfigured() {
        assertFalse(
                TrackerClient.needBackupTrackers(false, false, true, 100, true));
        assertFalse(
                TrackerClient.needBackupTrackers(false, false, false, 0, false));
    }
}
