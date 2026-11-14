package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for TrackerClient's shared announce pool decisions: keeping transient
 * zzzot metadata lookups off the trackers (name and size come from BEP 9
 * metadata via DHT-discovered peers, so a lookup announcement only leaks
 * interest, burns a shared permit and adds scrape traffic) and off the swarm
 * announce that follows a DHT get_peers, claiming at most
 * one pool slot per client, accepting tasks only while the pool has capacity
 * (a 1000-torrent batch start must not drive flow control through rejected
 * executions), and bounding the first-round spread and DHT-fetcher concurrency.
 *
 * @since 0.9.72+
 */
public class TrackerClientAnnounceDecisionTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one; the
     * decisions under test are static and carry no state.
     */
    public TrackerClientAnnounceDecisionTest() {}

    /**
     * A lookup with the DHT running does not touch the trackers at all.
     */
    @Test
    public void testLookupWithDHTSkipsTrackers() {
        assertFalse(TrackerClient.shouldAnnounceToTrackers(true, true));
    }

    /**
     * A lookup with no DHT still announces, as the tracker round is then its only
     * way to find a peer.
     */
    @Test
    public void testLookupWithoutDHTFallsBackToTrackers() {
        // without a DHT the tracker round is the only way to find a peer
        assertTrue(TrackerClient.shouldAnnounceToTrackers(true, false));
    }

    /**
     * A user torrent announces whether or not a DHT is running.
     */
    @Test
    public void testUserTorrentAlwaysAnnounces() {
        assertTrue(TrackerClient.shouldAnnounceToTrackers(false, true));
        assertTrue(TrackerClient.shouldAnnounceToTrackers(false, false));
    }

    /**
     * A metadata lookup does the DHT get_peers without announcing itself into the
     * swarm it is only asking about - it holds no data for it and is deleted
     * minutes later. A user torrent still announces.
     */
    @Test
    public void testLookupSkipsTheSwarmAnnounce() {
        assertFalse(TrackerClient.announcesToSwarm(true));
        assertTrue(TrackerClient.announcesToSwarm(false));
    }

    /**
     * A round is submitted only when the client holds no slot and has not halted;
     * a claimed slot that is not submitted must be released by the caller.
     */
    @Test
    public void testSubmitRoundOnlyWithFreeSlotAndRunning() {
        assertTrue(TrackerClient.shouldSubmitRound(true, false));
        // slot claimed but the client halted: caller must release it
        assertFalse(TrackerClient.shouldSubmitRound(true, true));
        // slot held by a queued or running round: caller retries later
        assertFalse(TrackerClient.shouldSubmitRound(false, false));
        assertFalse(TrackerClient.shouldSubmitRound(false, true));
    }

    /**
     * The pool's own bounds are ordered: core threads below the ceiling, a queue
     * that is not empty, and a retry delay that is not zero.
     */
    @Test
    public void testPoolBoundsSane() {
        assertTrue(TrackerClient.ANNOUNCE_THREADS > 0);
        assertTrue(TrackerClient.MAX_ANNOUNCE_THREADS >= TrackerClient.ANNOUNCE_THREADS);
        assertTrue(TrackerClient.ANNOUNCE_QUEUE > 0);
        assertTrue(TrackerClient.ROUND_RETRY_SECONDS > 0);
    }

    /**
     * Below the thread ceiling the pool grows; at the ceiling only queue space
     * accepts, and with the queue full too the task is rejected rather than thrown.
     */
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

    /**
     * The first round's delay stays inside its budget: a torrent within the 90 s
     * spread, a magnet or lookup inside its much shorter one, and a non-zero delay
     * never rounded down to zero.
     */
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

    /**
     * DHT fetchers hold a pool worker for minutes at a time, so their number must
     * stay below the pool's ceiling - they can never occupy the whole announce pool.
     */
    @Test
    public void testDHTFetchersCannotStarvePool() {
        // fetchers hold workers for minutes; they must never be able to
        // occupy the whole announce pool
        assertTrue(TrackerClient.DHT_FETCH_PERMITS > 0);
        assertTrue(TrackerClient.DHT_FETCH_PERMITS < TrackerClient.MAX_ANNOUNCE_THREADS);
    }
}
