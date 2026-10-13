package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the dispatch ordering and the concurrency elevation that goes with it.
 *
 * <p>Both are pure arithmetic so no router is required. The ordering matters
 * because a pool whose leases lapse is losing service in minutes and needs that
 * same window to build, while a pool merely below target with hours on its
 * leases can wait a cycle — so the two must not compare equal.
 *
 * @since 0.9.71+
 */
public class BuildDispatchPriorityTest {

    // ---- tier ordering ----

    /** A collapsed pool outranks everything, expiry urgency included. */
    @Test
    public void testCollapsedOutranksEverything() {
        int collapsed = BuildExecutor.score(0, 8, 8, 8);
        int urgentFull = BuildExecutor.score(8, 8, 8, 8);
        int thin = BuildExecutor.score(1, 8, 4, 0);
        assertTrue("collapsed must outrank an expiry-urgent full pool",
                   collapsed > urgentFull);
        assertTrue("collapsed must outrank a thin pool", collapsed > thin);
    }

    /**
     * The core of the change: expiry lifts a pool whose leases lapse above a
     * pool that is merely below target with hours remaining.
     */
    @Test
    public void testExpiryBeatsDeficitAlone() {
        // Nearly full, but two leases lapse.
        int urgent = BuildExecutor.score(6, 8, 6, 2);
        // Same shape, no leases lapsing.
        int calm = BuildExecutor.score(6, 8, 6, 0);
        assertTrue("a pool losing leases must build first", urgent > calm);
    }

    /**
     * Anti-starvation: expiry urgency must never outrank near-collapse, or a
     * pool that keeps producing near-expiry tunnels would starve a genuinely
     * thin pool indefinitely.
     */
    @Test
    public void testNearCollapseOutranksExpiry() {
        // Maximum expiry urgency (saturated at MAX_EXPIRY_BONUS tunnels).
        int urgent = BuildExecutor.score(8, 8, 8, 64);
        int thin = BuildExecutor.score(2, 8, 0, 0);
        assertTrue("near-collapse must outrank even saturated expiry",
                   thin > urgent);
    }

    /** Deficit still arbitrates among pools with the same expiry urgency. */
    @Test
    public void testDeficitBreaksExpiryTies() {
        assertTrue("same urgency, larger deficit builds sooner",
                   BuildExecutor.score(4, 8, 0, 2) > BuildExecutor.score(6, 8, 0, 2));
    }

    /**
     * The urgency bonus saturates, so a pool with a whole tier about to lapse
     * cannot accumulate unbounded priority.
     */
    @Test
    public void testExpiryUrgencySaturates() {
        int atCap = BuildExecutor.score(6, 8, 6, 4);
        int farAboveCap = BuildExecutor.score(6, 8, 6, 64);
        assertEquals("beyond the cap, more near-expiry tunnels add nothing",
                     atCap, farAboveCap);
    }

    /** Paired balance still applies, and cannot be inverted by expiry. */
    @Test
    public void testPairedBalanceStillApplies() {
        int behind = BuildExecutor.score(6, 8, 8, 0);
        int ahead = BuildExecutor.score(6, 8, 0, 0);
        assertTrue("the direction behind its pair still gets boosted", behind > ahead);
    }

    /** Degenerate inputs must not produce a negative score. */
    @Test
    public void testDegenerateInputsStayNonNegative() {
        assertTrue(BuildExecutor.score(0, 0, 0, 0) >= 0);
        assertTrue(BuildExecutor.score(3, 8, -5, -3) >= 0);
        // A deficit larger than the target is floored, not multiplied negatively.
        assertTrue(BuildExecutor.score(0, 8, -10, 0) > 0);
    }

    // ---- concurrency elevation ----

    /** No urgent demand means no elevation, whatever the base. */
    @Test
    public void testNoUrgentDemandNeverElevates() {
        for (int base = 8; base <= 96; base += 8) {
            assertEquals("urgent=0 must never elevate", base,
                         BuildExecutor.elevateForUrgent(base, 0, 500));
        }
    }

    /** Room under the ceiling means no elevation is needed. */
    @Test
    public void testUrgentDemandWithHeadroomDoesNotElevate() {
        assertEquals("two urgent with room to spare",
                     32, BuildExecutor.elevateForUrgent(32, 2, 10));
    }

    /**
     * The point of the rule: an urgent pool whose leases lapse cannot wait for
     * a later cycle, so the ceiling rises rather than the pool waiting.
     */
    @Test
    public void testUrgentDemandWithoutRoomElevates() {
        assertEquals("two urgent with the ceiling already spent",
                     12, BuildExecutor.elevateForUrgent(10, 2, 10));
    }

    /** Bounded: elevation stops at the hard ceiling. */
    @Test
    public void testElevationIsBounded() {
        // 200 urgent pools with 200 in flight must not elevate without limit.
        int elevated = BuildExecutor.elevateForUrgent(32, 200, 200);
        assertTrue("elevation must stay finite", elevated <= 96);
        assertTrue("and must still grow", elevated > 32);
    }

    /**
     * Ordinary deficit must not elevate. That demand is served a pool per
     * cycle in score order, and elevating for it would raise the ceiling
     * permanently on a busy router.
     */
    @Test
    public void testUrgentCountOfZeroWithHighInProgressStays() {
        assertEquals("in-flight builds alone do not elevate",
                     32, BuildExecutor.elevateForUrgent(32, 0, 90));
    }
}
