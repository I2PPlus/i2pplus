package org.klomp.snark;

import static org.junit.Assert.*;

import java.util.Random;

import org.junit.Test;

/**
 * Tests for the DHT peer-fetch budget: the start rate scaled from how many torrents
 * actually want peers, the slots that hold that rate under Little's Law, the jittered
 * spacing that spreads the starts over the minute instead of letting a round of torrents
 * walk the DHT together, and the duration average that feeds the slots. All four are static
 * decision helpers, so they are pinned without a session or a running DHT.
 *
 * @since 0.9.72+
 */
public class DHTFetchBudgetTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one; the
     * decisions under test are static and carry no state.
     */
    public DHTFetchBudgetTest() {}

    /**
     * The rate follows the demand and stops at the ceiling: two torrents do not get forty
     * slots a minute, and two thousand do not get four.
     */
    @Test
    public void theRateScalesWithDemandAndStopsAtTheCeiling() {
        assertEquals(1, TrackerClient.dhtFetchRate(0, 1000));
        assertEquals(1, TrackerClient.dhtFetchRate(1, 1000));
        assertEquals(2, TrackerClient.dhtFetchRate(2, 1000));
        assertEquals(40, TrackerClient.dhtFetchRate(40, 1000));
        // the ceiling, not the demand, is what bounds a 2000-torrent router
        assertEquals(TrackerClient.DHT_FETCH_RATE_MAX, TrackerClient.dhtFetchRate(2000, 1000));
        assertTrue(TrackerClient.DHT_FETCH_RATE_MAX >= 40);
    }

    /**
     * A DHT that is still bootstrapping is served by one walk at a time: more walks over the
     * same handful of nodes find nothing new and only spend network-database lookups.
     */
    @Test
    public void aBootstrappingDHTFetchesOneAtATime() {
        assertEquals(1, TrackerClient.dhtFetchRate(500, 0));
        assertEquals(1, TrackerClient.dhtFetchRate(500, 15));
        // one node past the bootstrap floor, and the demand is served again
        assertEquals(TrackerClient.DHT_FETCH_RATE_MAX, TrackerClient.dhtFetchRate(500, 16));
    }

    /**
     * The slots are the start rate times how long a fetch takes - Little's Law. A healthy DHT
     * answers a get_peers round in seconds, so a few slots hold the whole rate; a deaf one
     * takes minutes, and then the slots, not the rate, bound the load on the router.
     */
    @Test
    public void theSlotsAreTheRateTimesTheFetchDuration() {
        // 40 a minute at 10 s a fetch is under 7 in flight
        assertEquals(7, TrackerClient.dhtFetchSlots(40, 10 * 1000L, 2, 32));
        // 40 a minute at a minute a fetch is 40 in flight, over the ceiling
        assertEquals(32, TrackerClient.dhtFetchSlots(40, 60 * 1000L, 2, 32));
        // a deaf DHT, eight minutes a fetch: the ceiling is what protects the network database
        assertEquals(32, TrackerClient.dhtFetchSlots(40, 8 * 60 * 1000L, 2, 32));
        // little demand, little need for slots
        assertEquals(2, TrackerClient.dhtFetchSlots(1, 60 * 1000L, 2, 32));
        // nothing measured yet, so the floor, and the first fetches report the rest
        assertEquals(2, TrackerClient.dhtFetchSlots(40, 0, 2, 32));
        assertEquals(2, TrackerClient.dhtFetchSlots(40, -1, 2, 32));
    }

    /**
     * The ceiling has to hold the full start rate for a fetch that finishes inside a minute,
     * or the rate is unreachable however many torrents want peers.
     */
    @Test
    public void theCeilingHoldsTheRateForAHealthyDHT() {
        // the ceiling is exactly what forty starts a minute needs at 48 seconds a fetch
        assertEquals(TrackerClient.DHT_FETCH_MAX_SLOTS,
                     TrackerClient.dhtFetchSlots(TrackerClient.DHT_FETCH_RATE_MAX,
                                                 48 * 1000L,
                                                 TrackerClient.DHT_FETCH_MIN_SLOTS,
                                                 TrackerClient.DHT_FETCH_MAX_SLOTS));
        // a DHT that answers faster needs slots to spare, so the rate is reachable without
        // using every one of them
        assertTrue(TrackerClient.dhtFetchSlots(TrackerClient.DHT_FETCH_RATE_MAX,
                                               30 * 1000L,
                                               TrackerClient.DHT_FETCH_MIN_SLOTS,
                                               TrackerClient.DHT_FETCH_MAX_SLOTS)
                   < TrackerClient.DHT_FETCH_MAX_SLOTS);
    }

    /**
     * Starts are spaced by the minute divided by the rate, jittered by up to half again either
     * way, so 2000 torrents do not line up on one second and the floodfills their walks reach
     * see a trickle rather than a burst.
     */
    @Test
    public void theStartSpacingIsJitteredAroundTheRate() {
        Random rnd = new Random(0x5EEDL);
        for (int i = 0; i < 200; i++) {
            long at40 = TrackerClient.fetchStartSpacing(40, rnd);
            // 1.5s nominal, plus or minus half
            assertTrue("spacing " + at40, at40 >= 750 && at40 <= 2249);
        }
        // one a minute is a minute apart, plus or minus half
        for (int i = 0; i < 50; i++) {
            long at1 = TrackerClient.fetchStartSpacing(1, rnd);
            assertTrue("spacing " + at1, at1 >= 30_000 && at1 <= 89_999);
        }
        // a rate of zero cannot divide by zero
        assertTrue(TrackerClient.fetchStartSpacing(0, rnd) > 0);
    }

    /**
     * The jitter actually spreads the starts: 40 of them at the 40-a-minute rate must not
     * land in one clump, or the floodfills see the burst the jitter exists to avoid.
     */
    @Test
    public void theSpacingSpreadsStartsOverTheMinute() {
        Random rnd = new Random(0xC0FFEE);
        long total = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        for (int i = 0; i < 40; i++) {
            long spacing = TrackerClient.fetchStartSpacing(40, rnd);
            total += spacing;
            min = Math.min(min, spacing);
            max = Math.max(max, spacing);
        }
        // 40 starts land close to the minute they were meant to fill
        assertTrue("40 starts span " + total + "ms", total > 45_000 && total < 75_000);
        assertTrue("no spread: " + min + "-" + max, max - min > 500);
    }

    /**
     * The duration average seeds itself with the first sample and then moves a quarter of the
     * way to each one, so the slots follow the DHT's current health within a few rounds rather
     * than remembering one bad hour for the life of the router.
     */
    @Test
    public void theDurationAverageFollowsTheDHTsHealth() {
        assertEquals(1500, TrackerClient.fetchDurationEwma(0, 1500));
        // a quarter of the way from the old average to the new sample
        assertEquals(1125, TrackerClient.fetchDurationEwma(1000, 1500));
        // and down the same way when the DHT recovers
        assertEquals(875, TrackerClient.fetchDurationEwma(1000, 500));
        // a clock that went backwards is ignored rather than averaged in
        assertEquals(1000, TrackerClient.fetchDurationEwma(1000, -1));
        // sixteen samples of a recovered DHT get most of the way there - at forty fetches
        // a minute that is well under a minute of wall time
        long avg = 8 * 60 * 1000L;
        for (int i = 0; i < 16; i++) {
            avg = TrackerClient.fetchDurationEwma(avg, 10 * 1000L);
        }
        assertTrue("still remembering the bad hour: " + avg, avg < 20 * 1000L);
    }
}
