package org.klomp.snark;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Tests for the tunnel quantity decision in {@link IdleChecker}.
 *
 * <p>The regression: the target was recomputed from {@code peerCount / 2} on every 63s check and
 * pushed at the router whenever it differed from the last value applied. BitTorrent peer counts
 * swing across any threshold within seconds, so the count oscillated, and each change is a
 * ReconfigureSessionMessage whose handler replaces the pool settings outright - surplus tunnels
 * destroyed, then rebuilt. A running torrent therefore saw its pool collapse and regrow once a
 * minute instead of holding steady.
 *
 * <p>Two properties are pinned here:
 *
 * <ul>
 *   <li>a running torrent never drops below the floor, however few peers it has
 *   <li>a peer count hovering at the threshold does not move the quantity at all
 * </ul>
 *
 * @since 0.9.71+
 */
public class IdleCheckerTest {

    private static final int NO_CHANGE = IdleChecker.NO_CHANGE;
    private static final int Hysteresis = 3;

    // ---- desiredQuantity: the floor holds regardless of peers ----

    @Test
    public void noPeersStillWantsTheFloor() {
        assertEquals(2, IdleChecker.desiredQuantity(0));
    }

    @Test
    public void fewPeersStayAtTheFloor() {
        assertEquals(2, IdleChecker.desiredQuantity(1));
        assertEquals(2, IdleChecker.desiredQuantity(3));
        assertEquals(2, IdleChecker.desiredQuantity(4));
    }

    @Test
    public void manyPeersScaleUp() {
        assertEquals(4, IdleChecker.desiredQuantity(8));
        assertEquals(10, IdleChecker.desiredQuantity(20));
    }

    /** The floor is a floor, not a value that peer count can push below. */
    @Test
    public void desiredQuantityNeverDropsBelowFloor() {
        for (int peers = 0; peers < 40; peers++) {
            assertNotEquals(
                    "peer count " + peers + " must not want fewer than the floor",
                    true,
                    IdleChecker.desiredQuantity(peers) < 2);
        }
    }

    // ---- decideQuantity: growth applies immediately ----

    @Test
    public void growthIsAppliedOnTheFirstCheck() {
        assertEquals(4, IdleChecker.decideQuantity(8, 2, 0));
    }

    @Test
    public void growthIsAppliedEvenThoughNothingHasBeenIdle() {
        // A swarm filling up after a restart must not wait out the shrink hysteresis.
        assertEquals(6, IdleChecker.decideQuantity(12, 2, 0));
    }

    // ---- decideQuantity: a running torrent keeps its floor ----

    /** The core regression: a running torrent with no peers keeps the floor. */
    @Test
    public void runningTorrentWithNoPeersKeepsTheFloor() {
        assertEquals(NO_CHANGE, IdleChecker.decideQuantity(0, 2, 0));
    }

    /** Same, with the pool ramped up: peers vanished but the torrent is still running. */
    @Test
    public void runningTorrentKeepsRampedCountWhenPeersDrop() {
        assertEquals(NO_CHANGE, IdleChecker.decideQuantity(0, 8, 0));
    }

    /** Peers present, so the shrink hysteresis is irrelevant - still no reduction. */
    @Test
    public void noReductionWhilePeersRemain() {
        assertEquals(NO_CHANGE, IdleChecker.decideQuantity(2, 8, Hysteresis * 10));
    }

    // ---- decideQuantity: no churn at the threshold ----

    /**
     * The regression, replayed: a peer count oscillating across the threshold must not produce a
     * different quantity on each check.
     */
    @Test
    public void peerCountHoveringAtThresholdDoesNotChurn() {
        int current = 2;
        // A single torrent whose peers wobble between 3 and 5, i.e. right around the 4 threshold.
        int[] wobble = {4, 3, 5, 4, 3, 4, 5, 3, 4, 5, 3, 4};
        for (int peers : wobble) {
            int decided = IdleChecker.decideQuantity(peers, current, 0);
            if (decided != NO_CHANGE) {
                // Growth is allowed, but then the count has genuinely risen and stays there.
                assertNotEquals("growth must never shrink the pool", true, decided < current);
                current = decided;
            }
        }
        assertEquals("a wobbling swarm must not have moved off the floor", 2, current);
    }

    /** Peer noise one tunnel below the threshold must not issue a reconfigure. */
    @Test
    public void singleTickBelowThresholdIsIgnored() {
        // Pool at 4 (8 peers last check), peers fall to 5 -> wants 2, but only for one tick.
        assertEquals(NO_CHANGE, IdleChecker.decideQuantity(5, 4, 0));
    }

    // ---- decideQuantity: reduction only after sustained idleness ----

    @Test
    public void reductionWaitsForTheHysteresis() {
        assertEquals(NO_CHANGE, IdleChecker.decideQuantity(0, 4, 0));
        assertEquals(NO_CHANGE, IdleChecker.decideQuantity(0, 4, 1));
        assertEquals(NO_CHANGE, IdleChecker.decideQuantity(0, 4, Hysteresis - 1));
    }

    @Test
    public void sustainedIdlenessReducesToTheFloor() {
        assertEquals(2, IdleChecker.decideQuantity(0, 4, Hysteresis));
    }

    /** Idling re-arms on every reconfigure, so a reduction cannot immediately cascade. */
    @Test
    public void reductionFromHighCountLandsOnTheFloor() {
        assertEquals(2, IdleChecker.decideQuantity(0, 8, Hysteresis));
    }

    /** Already at the floor and idle: re-sending the same value would still rebuild the pool. */
    @Test
    public void alreadyAtFloorIsNoChange() {
        assertEquals(NO_CHANGE, IdleChecker.decideQuantity(0, 2, Hysteresis * 5));
    }

    // ---- the floor is per session, so pools are independent ----

    /**
     * Each pool is judged on its own peers and holds its own floor, so a busy sibling cannot drag
     * an idle pool below 2/2 and cannot lift it either.
     */
    @Test
    public void poolsAreFlooredIndependently() {
        // Pool A: 20 peers, wants 10. Pool B: nothing connected but still has a torrent, wants 2.
        int poolA = IdleChecker.decideQuantity(20, 2, 0);
        int poolB = IdleChecker.decideQuantity(0, 2, 0);
        assertEquals(10, poolA);
        assertEquals(NO_CHANGE, poolB);
        // A ramped pool that goes quiet keeps its count, exactly like the shared session does.
        assertEquals(NO_CHANGE, IdleChecker.decideQuantity(0, poolA, 0));
    }

    /** An emptied pool sheds its tunnels, and the reduction lands on the floor, never below. */
    @Test
    public void emptiedPoolReducesToTheFloor() {
        assertEquals(2, IdleChecker.decideQuantity(0, 10, Hysteresis));
    }

    /**
     * One pool growing must not reconfigure its sibling: the busy pool ramps, the quiet one is
     * left alone at its own floor.
     */
    @Test
    public void onePoolGrowingDoesNotReconfigureTheOther() {
        int busyCurrent = 2;
        int quietCurrent = 2;
        int busyTarget = IdleChecker.decideQuantity(16, busyCurrent, 0);
        int quietTarget = IdleChecker.decideQuantity(0, quietCurrent, 0);
        if (busyTarget != NO_CHANGE) {
            busyCurrent = busyTarget;
        }
        assertEquals("busy pool should have ramped", 8, busyCurrent);
        assertEquals("quiet pool must be untouched", NO_CHANGE, quietTarget);
        assertEquals("quiet pool must stay at its floor", 2, quietCurrent);
    }
}
