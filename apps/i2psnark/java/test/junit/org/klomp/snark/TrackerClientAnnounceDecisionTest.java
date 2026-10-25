package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for TrackerClient's shared announce pool decisions: keeping transient
 * zzzot metadata lookups off the trackers (name and size come from BEP 9
 * metadata via DHT-discovered peers, so a lookup announcement only leaks
 * interest, burns a shared permit and adds scrape traffic), claiming at most
 * one pool slot per client, accepting tasks only while the pool has capacity
 * (a 1000-torrent batch start must not drive flow control through rejected
 * executions), and bounding the first-round spread and DHT-fetcher concurrency.
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

    @Test
    public void testSubmitRoundOnlyWithFreeSlotAndRunning() {
        assertTrue(TrackerClient.shouldSubmitRound(true, false));
        // slot claimed but the client halted: caller must release it
        assertFalse(TrackerClient.shouldSubmitRound(true, true));
        // slot held by a queued or running round: caller retries later
        assertFalse(TrackerClient.shouldSubmitRound(false, false));
        assertFalse(TrackerClient.shouldSubmitRound(false, true));
    }

    @Test
    public void testPoolBoundsSane() {
        assertTrue(TrackerClient.ANNOUNCE_THREADS > 0);
        assertTrue(TrackerClient.MAX_ANNOUNCE_THREADS >= TrackerClient.ANNOUNCE_THREADS);
        assertTrue(TrackerClient.ANNOUNCE_QUEUE > 0);
        assertTrue(TrackerClient.ROUND_RETRY_SECONDS > 0);
    }

    @Test
    public void testPoolCapacityPrecheck() {
        // below the ceiling: the pool grows to take the task
        assertTrue(TrackerClient.poolHasCapacity(0, 32, 0));
        assertTrue(TrackerClient.poolHasCapacity(31, 32, 0));
        // at the ceiling: only queue space accepts
        assertTrue(TrackerClient.poolHasCapacity(32, 32, 1));
        // at the ceiling with a full queue: reject path, no exception thrown
        assertFalse(TrackerClient.poolHasCapacity(32, 32, 0));
    }

    @Test
    public void testFirstRoundDelayBounds() {
        // zero spread means announce immediately
        assertEquals(0, TrackerClient.firstRoundDelaySeconds(true, 0, 0));
        // a nonzero sub-second delay rounds up, never to zero
        assertEquals(1, TrackerClient.firstRoundDelaySeconds(true, 1, 1));
        // a torrent's full spread stays within the 90s budget
        for (int full : new int[] {1, 45_000, 89_999}) {
            int secs = TrackerClient.firstRoundDelaySeconds(true, full, 0);
            assertTrue(secs >= 1);
            assertTrue(secs <= 90);
        }
        // a magnet/lookup announces promptly, within its short budget
        for (int magnet : new int[] {1, 1_500, 2_999}) {
            int secs = TrackerClient.firstRoundDelaySeconds(false, 0, magnet);
            assertTrue(secs >= 1);
            assertTrue(secs <= 3);
        }
    }

    @Test
    public void testDHTFetchersCannotStarvePool() {
        // fetchers hold workers for minutes; they must never be able to
        // occupy the whole announce pool
        assertTrue(TrackerClient.DHT_FETCH_PERMITS > 0);
        assertTrue(TrackerClient.DHT_FETCH_PERMITS < TrackerClient.MAX_ANNOUNCE_THREADS);
    }
}
