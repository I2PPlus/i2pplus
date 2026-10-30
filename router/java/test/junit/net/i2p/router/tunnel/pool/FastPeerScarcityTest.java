package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The peer-supply threshold that decides whether the activity window widens.
 *
 * <p>The threshold used to be a fixed 200 fast peers for every router, while the
 * tier sizing asks for 1,000. A router sitting at 227 fast peers — 23% of its own
 * target, with pools reporting starvation — was therefore declared well supplied:
 * the relief valve stayed shut because its constant had stopped meaning anything
 * once the tier minimums moved. Deriving the threshold from the tier minimum makes
 * "ample supply" a statement about this router.
 *
 * <p>Pure decisions, so no {@link net.i2p.router.RouterContext} is needed.
 *
 * @since 0.9.72+
 */
public class FastPeerScarcityTest {

    private static final int MIN = 1;
    private static final int MAX = 4;

    // ---- scarceFastPeers ----

    /**
     * Four fifths of the tier minimum: below that the tier is measurably short of
     * what it asked for, and trading recency for pool thickness is the right trade.
     */
    @Test
    public void testThresholdIsFourFifthsOfTheTierMinimum() {
        assertEquals(800, TunnelPeerSelector.scarceFastPeers(1000));
        assertEquals(640, TunnelPeerSelector.scarceFastPeers(800));
        assertEquals(80, TunnelPeerSelector.scarceFastPeers(100));
    }

    /**
     * Floored at 50 so a small network with a small tier is not declared scarce for
     * having ten peers — a router configured for a 20-peer fast tier is not starving
     * at 20.
     */
    @Test
    public void testThresholdHasAFloor() {
        assertEquals(50, TunnelPeerSelector.scarceFastPeers(10));
        assertEquals(50, TunnelPeerSelector.scarceFastPeers(1));
        assertEquals(50, TunnelPeerSelector.scarceFastPeers(60));
    }

    /**
     * No tier minimum known selects the historical constant, so a caller that
     * cannot size the tier keeps the previous behaviour rather than declaring every
     * router scarce.
     */
    @Test
    public void testNoTierMinimumSelectsTheHistoricalConstant() {
        assertEquals(TunnelPeerSelector.SCARCE_FAST_PEERS, TunnelPeerSelector.scarceFastPeers(0));
        assertEquals(TunnelPeerSelector.SCARCE_FAST_PEERS, TunnelPeerSelector.scarceFastPeers(-5));
    }

    /**
     * The measured case. 227 fast peers against a 1,000-peer minimum: the fixed
     * threshold of 200 called that ample, the derived one calls it scarce.
     */
    @Test
    public void testTheMeasuredStarvingRouterIsScarce() {
        int threshold = TunnelPeerSelector.scarceFastPeers(1000);
        assertTrue("227 peers is 23% of a 1,000-peer tier", 227 < threshold);
        assertEquals(800, threshold);
    }

    // ---- windowMultiplierFor with a supplied threshold ----

    /**
     * The four-argument form keeps the fixed threshold, so existing callers are
     * unaffected by the derivation.
     */
    @Test
    public void testFourArgFormKeepsTheConstant() {
        assertEquals(MIN, TunnelPeerSelector.windowMultiplierFor(
                                   TunnelPeerSelector.SCARCE_FAST_PEERS, false, MIN, MAX));
        assertEquals(MIN, TunnelPeerSelector.windowMultiplierFor(
                                   227, false, MIN, MAX, TunnelPeerSelector.SCARCE_FAST_PEERS));
    }

    /**
     * The same peer count gives opposite answers under the two thresholds, which is
     * the whole point: 227 peers is ample for a router that asked for 200 and
     * scarce for one that asked for 1,000.
     */
    @Test
    public void testTheSameCountIsAmpleOrScarceByRouter() {
        assertEquals(MIN, TunnelPeerSelector.windowMultiplierFor(227, false, MIN, MAX, 200));
        assertTrue("under the derived threshold the window widens",
                   TunnelPeerSelector.windowMultiplierFor(227, false, MIN, MAX, 800) > MIN);
    }

    /** Zero peers widens to the ceiling at any threshold. */
    @Test
    public void testNoPeersWidensToTheCeiling() {
        assertEquals(MAX, TunnelPeerSelector.windowMultiplierFor(0, false, MIN, MAX, 800));
    }

    /** The startup grace period wins over supply, as before. */
    @Test
    public void testStartupGraceIgnoresSupply() {
        assertEquals(MAX, TunnelPeerSelector.windowMultiplierFor(5000, true, MIN, MAX, 800));
    }

    /**
     * The multiplier rises continuously as supply falls, so a tier sliding from
     * 800 to 0 widens progressively rather than in one step at the threshold.
     */
    @Test
    public void testMultiplierIsMonotonicInSupply() {
        int prev = -1;
        for (int peers = 800; peers >= 0; peers -= 50) {
            int m = TunnelPeerSelector.windowMultiplierFor(peers, false, MIN, MAX, 800);
            assertTrue("multiplier " + m + " at " + peers + " peers is below the "
                       + prev + " at a fuller tier", m >= prev);
            assertTrue("and inside the range", m >= MIN && m <= MAX);
            prev = m;
        }
    }

    /** A degenerate range returns the floor, whatever the supply. */
    @Test
    public void testDegenerateRange() {
        assertEquals(2, TunnelPeerSelector.windowMultiplierFor(0, false, 2, 2, 800));
    }
}
