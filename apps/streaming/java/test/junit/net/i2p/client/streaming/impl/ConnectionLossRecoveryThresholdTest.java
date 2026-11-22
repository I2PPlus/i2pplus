package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the slow-start threshold a loss cut leaves behind,
 * {@link Connection#lossRecoverySsthresh(int, int, int)}.
 *
 * <p>The threshold is a high-water mark, not a second cut. The window has
 * already conceded capacity through
 * {@link Connection#graduatedLossWindow(int, int)}; flooring the threshold at
 * the pre-cut window lets the connection slow-start back to the size it had,
 * while flooring it at the cut — which is what happened whenever the
 * bandwidth-derived anchor was low — leaves it in congestion avoidance from
 * its own floor, climbing one packet per RTT.
 *
 * <p>The live case: a stream cut to a low window printed
 * "Window size: 13-15 * SSThresh: 4" and every growth line read
 * "Growth: congestion avoidance". The bandwidth anchor that was supposed to
 * prevent that lock cannot bind, because it is estimated from ACK arrivals and
 * those fall precisely because the window is small.
 *
 * <p>The helper is static and stateless: testable without a running router.
 *
 * @since 0.9.72+
 */
public class ConnectionLossRecoveryThresholdTest {

    /**
     * Test case carrying the post-cut threshold cases. Every case calls a
     * static helper, so the instance holds no state and needs no router.
     */
    public ConnectionLossRecoveryThresholdTest() {
    }

    /**
     * The defect: with a collapsed bandwidth anchor the old threshold equalled
     * the new window, so congestion avoidance started at the floor and slow
     * start never ran.
     */
    @Test
    public void testOldThresholdEqualledTheCutWindow() {
        int strikes = 3;
        int wsize = 128;
        int collapsedAnchor = 2;      // the floor the RTO path passes in
        int cutWindow = Connection.graduatedLossWindow(strikes, wsize);
        assertEquals(cutWindow,
                     Connection.graduatedLossSsthresh(strikes, wsize, collapsedAnchor));
    }

    /** A collapsed anchor leaves the threshold at the pre-cut window. */
    @Test
    public void testCollapsedAnchorKeepsTheHighWaterMark() {
        assertEquals(128, Connection.lossRecoverySsthresh(3, 128, 2));
        assertEquals(128, Connection.lossRecoverySsthresh(1, 128, 1));
        assertEquals(128, Connection.lossRecoverySsthresh(9, 128, 4));
    }

    /**
     * The threshold must sit strictly above the window the same cut installs,
     * at every tier, or the connection cannot slow-start out of the cut.
     */
    @Test
    public void testThresholdIsAboveTheCutAtEveryTier() {
        for (int strikes = 1; strikes <= 6; strikes++) {
            int wsize = 128;
            int cut = Connection.graduatedLossWindow(strikes, wsize);
            int ssthresh = Connection.lossRecoverySsthresh(strikes, wsize, 2);
            assertTrue("strike " + strikes + " leaves no room to slow-start",
                       ssthresh > cut);
        }
    }

    /** A larger bandwidth estimate is kept, not replaced by the high-water mark. */
    @Test
    public void testHighAnchorIsEnhancedNotReplaced() {
        assertEquals(200, Connection.lossRecoverySsthresh(1, 128, 200));
    }

    /**
     * Sustained loss still walks the threshold down: the high-water mark is the
     * window before this cut, and that window was itself reduced by the last
     * one, so a chain of cuts decays rather than latching at the original size.
     */
    @Test
    public void testSustainedLossDecaysTheThreshold() {
        int wsize = 128;
        int first = Connection.lossRecoverySsthresh(1, wsize, 2);
        wsize = Connection.graduatedLossWindow(1, wsize);
        int second = Connection.lossRecoverySsthresh(2, wsize, 2);
        wsize = Connection.graduatedLossWindow(2, wsize);
        int third = Connection.lossRecoverySsthresh(3, wsize, 2);
        assertEquals(128, first);
        assertEquals(112, second);
        assertEquals(84, third);
        assertTrue(second < first);
        assertTrue(third < second);
    }

    /** Never below the graduated window or the anchor, whichever is larger. */
    @Test
    public void testNeverBelowTheGraduatedWindowOrTheAnchor() {
        int strikes = 3;
        int wsize = 8;
        int cut = Connection.graduatedLossWindow(strikes, wsize);
        int threshold = Connection.lossRecoverySsthresh(strikes, wsize, 1);
        assertTrue(threshold >= cut);
        assertTrue(threshold >= wsize);
        assertTrue(Connection.lossRecoverySsthresh(1, 4, 64) >= 64);
    }

    /**
     * A connection already at the floor cannot be cut below itself, and the
     * threshold stays at that floor rather than drifting.
     */
    @Test
    public void testConnectionAtTheFloorStaysThere() {
        int floor = Connection.graduatedLossWindow(9, 4);
        assertEquals(4, floor);
        assertEquals(4, Connection.lossRecoverySsthresh(9, 4, 2));
    }
}
