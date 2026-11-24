package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * How long a floodfill stays rotated out of a selection,
 * {@link KademliaNetworkDatabaseFacade#queryCooldownMs(long)}.
 * <p>
 * Selection skips a floodfill for a flat minute after any search asks it, to
 * keep one router from hammering one floodfill. A LeaseSet is held only by the
 * floodfills nearest the destination's own hash, and a live router runs several
 * LeaseSet lookups a second, so a minute of skipping takes exactly those
 * floodfills out of every search that follows the first: 5,192 searches in 40
 * minutes ended with no floodfill left to ask, and two searches for the same
 * live destination 8 seconds apart queried disjoint peer sets, the 24-peer one
 * failing while the 8-peer one found the LeaseSet.
 * <p>
 * The rotation only protects anything while a search could still be asking that
 * peer, so the cooldown is bounded by the search window.
 *
 * @since 0.9.72+
 */
public class FloodfillCooldownBoundTest {

    /**
     * Test case carrying the rotation-window cases. Every case calls a static
     * helper with a number, so the instance holds no state and needs no router.
     */
    public FloodfillCooldownBoundTest() {
    }

    /**
     * The cooldown the facade would apply for a search window of this length.
     *
     * @param windowMs how long an iterative search may run, in ms
     * @return the rotation length in ms
     */
    private static long bound(long windowMs) {
        return KademliaNetworkDatabaseFacade.queryCooldownMs(windowMs);
    }

    /**
     * A search window shorter than the ceiling sets the cooldown: a peer held
     * out for longer than any search can run is held out past the point where
     * holding it out protects anything.
     */
    @Test
    public void testWindowSetsTheCooldown() {
        assertEquals(15 * 1000L, bound(15 * 1000L));
        assertEquals(4 * 1000L, bound(4 * 1000L));
    }

    /**
     * The ceiling still applies to a long window, so a Tuner that stretches
     * searches cannot turn the load-spreading off by holding the rotation open
     * for minutes.
     */
    @Test
    public void testCeilingHoldsForALongWindow() {
        assertEquals(60 * 1000L, bound(60 * 1000L));
        assertEquals(60 * 1000L, bound(10 * 60 * 1000L));
    }

    /**
     * A window too short to be worth rotating on still leaves a second of
     * rotation, so concurrent searches do not all pick the same floodfill in
     * the same instant. A window that is not a duration at all - zero or
     * negative, which means the caller does not know it - keeps the ceiling
     * rather than guessing a bound.
     */
    @Test
    public void testFloorKeepsTheRotationOn() {
        assertEquals(1000L, bound(250L));
        assertEquals(1000L, bound(999L));
        assertEquals(60 * 1000L, bound(0L));
        assertEquals(60 * 1000L, bound(-5000L));
    }

    /**
     * A longer window never shortens the rotation, and the result always stays
     * between the floor and the ceiling, so a Tuner moving the search window
     * cannot make the skipping more aggressive than a second or less
     * aggressive than a minute.
     */
    @Test
    public void testLongerWindowNeverShortensTheRotation() {
        long prev = -1;
        for (long window = 500L; window <= 120 * 1000L; window += 500L) {
            long cooldown = bound(window);
            assertTrue("cooldown went back at " + window, cooldown >= prev);
            assertTrue("below the floor at " + window, cooldown >= 1000L);
            assertTrue("past the ceiling at " + window, cooldown <= 60 * 1000L);
            prev = cooldown;
        }
    }
}
