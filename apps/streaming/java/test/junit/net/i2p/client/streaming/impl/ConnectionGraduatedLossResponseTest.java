package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the graduated loss response in {@link Connection}: the congestion
 * window is cut by an amount that scales with consecutive loss events
 * ({@link Connection#graduatedLossWindow(int, int)}), the slow-start
 * threshold keeps that graduated window as a floor so the connection can
 * regrow quickly after recovery ({@link Connection#graduatedLossSsthresh(int, int, int)}),
 * and a stale lone strike decays back toward the gentle tier after one
 * smoothed RTT has passed ({@link Connection#decayLossStrikes(int, long, long, long)}).
 *
 * <p>The 0.9.72+ profile implements "ramp fast, decline slowly": the tiers
 * are gentler (7/8, 3/4, 1/2 instead of 3/4, 1/2, 1/4) so a single loss on a
 * high-BDP connection does not undo an RTT of window growth, while genuinely
 * persistent loss still concedes half the window.
 *
 * <p>These are the pure decision helpers behind the RetransmitEvent RTO cut
 * and the ResendPacketEvent fast-retransmit cut.  A single failed packet
 * must not collapse the connection (total-collapse was the freeze symptom:
 * window pinned at ssthresh~4 for minutes), while persistent loss still backs
 * off progressively.
 *
 * <p>Both methods are static and stateless: testable without a running router.
 *
 * @since 0.9.71+
 */
public class ConnectionGraduatedLossResponseTest {

    /** First loss: mild 7/8 cut, so a single failed packet keeps most of the window. */
    @Test
    public void testFirstStrikeWindowIsMild() {
        assertEquals(224, Connection.graduatedLossWindow(1, 256));
        assertEquals(448, Connection.graduatedLossWindow(1, 512));
        assertEquals(7, Connection.graduatedLossWindow(1, 8));   // 7/8 rounds down
    }

    /** Second consecutive loss: 3/4 cut — still gentle, not the classic halving. */
    @Test
    public void testSecondStrikeWindowThreeQuarters() {
        assertEquals(192, Connection.graduatedLossWindow(2, 256));
        assertEquals(384, Connection.graduatedLossWindow(2, 512));
        assertEquals(7, Connection.graduatedLossWindow(2, 10));
    }

    /** Three or more consecutive losses: halve the window (persistent loss). */
    @Test
    public void testThirdStrikeWindowHalves() {
        assertEquals(128, Connection.graduatedLossWindow(3, 256));
        assertEquals(128, Connection.graduatedLossWindow(4, 256));
        assertEquals(12, Connection.graduatedLossWindow(3, 24));
    }

    /** Never collapse below a usable minimum, no matter how many strikes. */
    @Test
    public void testWindowNeverBelowFour() {
        assertEquals(4, Connection.graduatedLossWindow(3, 8));
        assertEquals(4, Connection.graduatedLossWindow(5, 2));
        assertEquals(4, Connection.graduatedLossWindow(9, 1));
        assertEquals(4, Connection.graduatedLossWindow(1, 1));
    }

    /** Strikes never shrink the window: monotone non-increasing in strikes. */
    @Test
    public void testWindowMonotoneInStrikes() {
        for (int wsize : new int[] {4, 16, 64, 256, 1024}) {
            int prev = Connection.graduatedLossWindow(1, wsize);
            for (int s = 2; s <= 6; s++) {
                int next = Connection.graduatedLossWindow(s, wsize);
                assertTrue("strike escalation must not grow the window", next <= prev);
                prev = next;
            }
        }
    }

    /** ssthresh is never below the graduated window, so slow-start can regrow. */
    @Test
    public void testSsthreshAtLeastGraduatedWindow() {
        for (int strikes = 1; strikes <= 4; strikes++) {
            for (int wsize : new int[] {4, 128, 256, 1024}) {
                // tiny bandwidth estimate: regression crawl must be prevented by the floor
                int low = Connection.graduatedLossSsthresh(strikes, wsize, 1);
                assertTrue(low >= Connection.graduatedLossWindow(strikes, wsize));
            }
        }
    }

    /** ssthresh respects a healthy bandwidth estimate (Westwood-style factor). */
    @Test
    public void testSsthreshUsesBandwidthEstimate() {
        // healthy link bandwidth estimate implies ssthresh well above the graduated floor
        assertEquals(512, Connection.graduatedLossSsthresh(1, 256, 512));
        assertEquals(512, Connection.graduatedLossSsthresh(3, 256, 512));
    }

    /** ssthresh is monotone non-increasing in strikes, like the window. */
    @Test
    public void testSsthreshMonotoneInStrikes() {
        for (int wsize : new int[] {16, 256, 1024}) {
            int prev = Connection.graduatedLossSsthresh(1, wsize, 1);
            for (int s = 2; s <= 6; s++) {
                int next = Connection.graduatedLossSsthresh(s, wsize, 1);
                assertTrue("ssthresh must not grow with strikes", next <= prev);
                prev = next;
            }
        }
    }

    /** Persistent loss still converges to the deep tier, not total collapse. */
    @Test
    public void testPersistentLossConverges() {
        int wsize = 256;
        // repeated strikes converge to wsize/2, never below 4
        assertEquals(128, Connection.graduatedLossWindow(10, wsize));
        assertEquals(128, Connection.graduatedLossSsthresh(10, wsize, 1));
    }

    // =====================================================================
    // Strike decay
    // =====================================================================

    /** No strikes: nothing to decay. */
    @Test
    public void testDecayNoStrikesIsZero() {
        assertEquals(0, Connection.decayLossStrikes(0, 1_000L, 1_100L, 500L));
        assertEquals(0, Connection.decayLossStrikes(-3, 1_000L, 1_600L, 500L));
    }

    /** A fresh strike (less than one RTT old) never decays — burst escalation intact. */
    @Test
    public void testDecayFreshStrikeHolds() {
        assertEquals(3, Connection.decayLossStrikes(3, 1_000L, 1_400L, 500L));
        // exactly the RTT boundary is still too fresh (>= required)
        assertEquals(2, Connection.decayLossStrikes(2, 1_000L, 1_499L, 500L));
    }

    /** Strikes older than max(smoothedRtt, 500ms) decay by one tier. */
    @Test
    public void testDecayStaleStrikeFires() {
        assertEquals(2, Connection.decayLossStrikes(3, 1_000L, 2_500L, 500L));
        assertEquals(1, Connection.decayLossStrikes(2, 1_000L, 3_001L, 2000L));
    }

    /** Never drops below zero. */
    @Test
    public void testDecayFlooredAtZero() {
        assertEquals(0, Connection.decayLossStrikes(1, 1_000L, 2_501L, 500L));
        assertEquals(0, Connection.decayLossStrikes(1, 1_000L, Long.MAX_VALUE, 500L));
    }

    /** Unknown RTT uses the 500ms floor; unknown last-loss time never decays. */
    @Test
    public void testDecayRttFloorAndUnknownTime() {
        assertEquals(1, Connection.decayLossStrikes(2, 1_000L, 1_550L, 0));
        // no recorded loss time: cannot prove the strike is stale
        assertEquals(2, Connection.decayLossStrikes(2, 0, 100_000L, 500L));
        assertEquals(2, Connection.decayLossStrikes(2, -1, 100_000L, 500L));
    }
}
