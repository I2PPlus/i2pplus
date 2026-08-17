package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for TrackerClient.scrapeURL, the BEP 48 scrape URL builder.
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
}