package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for TrackerClient.shouldAnnounceToTrackers, the decision that keeps
 * transient zzzot metadata lookups off the trackers: name and size come from
 * BEP 9 metadata via DHT-discovered peers, so a lookup announcement only
 * leaks interest, burns a shared announce permit and adds scrape traffic.
 *
 * @since 0.9.72+
 */
public class TrackerClientAnnounceDecisionTest {

    @Test
    public void testLookupWithDHTSkipsTrackers() {
        assertFalse(TrackerClient.shouldAnnounceToTrackers(true, true));
    }

    @Test
    public void testLookupWithoutDHTFallsBackToTrackers() {
        // without a DHT the tracker round is the only way to find a peer
        assertTrue(TrackerClient.shouldAnnounceToTrackers(true, false));
    }

    @Test
    public void testUserTorrentAlwaysAnnounces() {
        assertTrue(TrackerClient.shouldAnnounceToTrackers(false, true));
        assertTrue(TrackerClient.shouldAnnounceToTrackers(false, false));
    }
}
