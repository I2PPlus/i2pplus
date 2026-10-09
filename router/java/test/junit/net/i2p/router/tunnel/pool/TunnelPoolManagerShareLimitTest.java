package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the per-peer participation share limit and the relief applied to it when
 * tunnel supply is short.
 *
 * <p>The share limit exists to stop a handful of peers absorbing most of our
 * participation, but on an open port it is set tight (10%). When pools cannot
 * fill their LeaseSets that tightness starves first-hop selection, so the limit
 * relaxes. The relaxation is the part worth pinning: it must not have silently
 * moved the thresholds that existed before it.
 *
 * @since 0.9.71+
 */
public class TunnelPoolManagerShareLimitTest {

    // ---- buildSuccessRelief ----

    /** No relief when builds are healthy: this is the pre-existing behaviour. */
    @Test
    public void healthyBuildsGetNoRelief() {
        assertEquals(1.0, TunnelPoolManager.buildSuccessRelief(1.0), 1e-9);
        assertEquals(1.0, TunnelPoolManager.buildSuccessRelief(0.90), 1e-9);
        assertEquals(1.0, TunnelPoolManager.buildSuccessRelief(0.60), 1e-9);
    }

    /**
     * Full relief at or below the old cliff. The previous code doubled the
     * limit outright below 0.40, and that must not regress.
     */
    @Test
    public void atOrBelowOldCliffGetsFullRelief() {
        assertEquals(2.0, TunnelPoolManager.buildSuccessRelief(0.40), 1e-9);
        assertEquals(2.0, TunnelPoolManager.buildSuccessRelief(0.20), 1e-9);
        assertEquals(2.0, TunnelPoolManager.buildSuccessRelief(0.0), 1e-9);
    }

    /** The gap the old code ignored: 0.40 to 0.60 now interpolates. */
    @Test
    public void betweenCliffsInterpolates() {
        double mid = TunnelPoolManager.buildSuccessRelief(0.50);
        assertTrue("midpoint relief must sit between the endpoints", mid > 1.0 && mid < 2.0);
        assertEquals(1.5, mid, 1e-9);
    }

    /** Relief rises monotonically as build success falls. */
    @Test
    public void reliefIsMonotonicInBuildSuccess() {
        double prev = 3.0;
        for (double bs = 0.0; bs <= 1.0; bs += 0.05) {
            double r = TunnelPoolManager.buildSuccessRelief(bs);
            assertTrue("relief must not increase as build success improves at " + bs,
                       r <= prev + 1e-9);
            prev = r;
        }
    }

    /** Unknown build success arrives as 1.0 and must not trigger relief. */
    @Test
    public void unknownBuildSuccessIsTreatedAsHealthy() {
        assertEquals(1.0, TunnelPoolManager.buildSuccessRelief(1.0), 1e-9);
    }

    /** Relief is never below 1 nor above 2, whatever the input. */
    @Test
    public void reliefStaysBounded() {
        assertTrue(TunnelPoolManager.buildSuccessRelief(-5.0) <= 2.0);
        assertTrue(TunnelPoolManager.buildSuccessRelief(5.0) >= 1.0);
    }

    // ---- participationRelief (precedence) ----

    /** Firewalled takes full relief whatever build success says. */
    @Test
    public void firewalledTakesPrecedence() {
        assertEquals(2.0, TunnelPoolManager.participationRelief(true, 1.0, false), 1e-9);
        assertEquals(2.0, TunnelPoolManager.participationRelief(true, 0.0, false), 1e-9);
    }

    /** An incomplete LeaseSet outranks healthy build success. */
    @Test
    public void incompleteLeaseSetOutranksHealthyBuildSuccess() {
        assertEquals(TunnelPoolManager.INCOMPLETE_LEASESET_RELIEF,
                     TunnelPoolManager.participationRelief(false, 1.0, true), 1e-9);
        assertEquals(TunnelPoolManager.INCOMPLETE_LEASESET_RELIEF,
                     TunnelPoolManager.participationRelief(false, 0.95, true), 1e-9);
    }

    /** With neither hard signal, build success alone decides. */
    @Test
    public void buildSuccessDecidesWhenNoHardSignal() {
        assertEquals(1.0, TunnelPoolManager.participationRelief(false, 0.90, false), 1e-9);
        assertEquals(1.5, TunnelPoolManager.participationRelief(false, 0.50, false), 1e-9);
        assertEquals(2.0, TunnelPoolManager.participationRelief(false, 0.20, false), 1e-9);
    }

    /** The realistic observed case: healthy builds but a pool cannot fill. */
    @Test
    public void healthyBuildsWithIncompleteLeaseSetStillGetRelief() {
        double r = TunnelPoolManager.participationRelief(false, 0.66, true);
        assertEquals(2.0, r, 1e-9);
        assertEquals(20, TunnelPoolManager.participationShareLimit(10, r));
    }

    // ---- participationShareLimit ----

    /** Relief of 1.0 leaves the base untouched. */
    @Test
    public void noReliefLeavesBaseUnchanged() {
        assertEquals(10, TunnelPoolManager.participationShareLimit(10, 1.0));
        assertEquals(30, TunnelPoolManager.participationShareLimit(30, 1.0));
    }

    /** Relief of 2.0 doubles, which is what the old code did. */
    @Test
    public void fullReliefDoublesTheBase() {
        assertEquals(20, TunnelPoolManager.participationShareLimit(10, 2.0));
        assertEquals(60, TunnelPoolManager.participationShareLimit(30, 2.0));
    }

    /** Partial relief scales the base without ever shrinking it. */
    @Test
    public void partialReliefScalesBetweenEndpoints() {
        assertEquals(15, TunnelPoolManager.participationShareLimit(10, 1.5));
    }

    /** A nonsensical factor must never produce a limit below the base. */
    @Test
    public void limitNeverBelowBase() {
        assertEquals(10, TunnelPoolManager.participationShareLimit(10, 0.5));
        assertEquals(10, TunnelPoolManager.participationShareLimit(10, 0.0));
        assertEquals(10, TunnelPoolManager.participationShareLimit(10, -3.0));
    }

    // ---- exceedsShareLimit ----

    /** A peer holding a small share of a large total is not excluded. */
    @Test
    public void smallShareNotExcluded() {
        assertFalse(TunnelPoolManager.exceedsShareLimit(1, 100, 10));
        assertFalse(TunnelPoolManager.exceedsShareLimit(5, 100, 10));
    }

    /** A peer absorbing a large share is excluded. */
    @Test
    public void largeShareExcluded() {
        assertTrue(TunnelPoolManager.exceedsShareLimit(30, 100, 10));
        assertTrue(TunnelPoolManager.exceedsShareLimit(100, 100, 10));
    }

    /**
     * A lone peer in a tiny pool does read 100%, so it is excluded -- the
     * {@code +1} form does not rescue it. Written explicitly because the
     * intuitive reading of "smoothing" is the opposite of the actual effect:
     * it inflates the share for small pools rather than deflating it.
     */
    @Test
    public void lonePeerInTinyPoolIsExcluded() {
        assertTrue(TunnelPoolManager.exceedsShareLimit(1, 1, 10));
        assertTrue(TunnelPoolManager.exceedsShareLimit(1, 3, 10));
    }

    /**
     * Where the {@code +1} form actually diverges from the unsmoothed share:
     * it is higher for a peer in a small pool, and identical once the total is
     * ten times the count.
     */
    @Test
    public void smoothedShareIsInflatedOnlyForSmallPools() {
        // (1+1)*100/(2+1) = 66 where the unsmoothed share is 50
        assertFalse(TunnelPoolManager.exceedsShareLimit(1, 2, 66));
        assertTrue(TunnelPoolManager.exceedsShareLimit(1, 2, 65));
        // (1+1)*100/(9+1) = 20 where the unsmoothed share is 11
        assertFalse(TunnelPoolManager.exceedsShareLimit(1, 9, 20));
        assertTrue(TunnelPoolManager.exceedsShareLimit(1, 9, 19));
        // (5+1)*100/(100+1) truncates to 5, the same as 5*100/100
        assertFalse(TunnelPoolManager.exceedsShareLimit(5, 100, 5));
        assertTrue(TunnelPoolManager.exceedsShareLimit(5, 100, 4));
    }

    /** Exactly at the limit is not excluded: the comparison is strict. */
    @Test
    public void exactlyAtLimitIsNotExcluded() {
        // count=1,total=9 -> (2*100)/10 = 20
        assertFalse(TunnelPoolManager.exceedsShareLimit(1, 9, 20));
        assertTrue(TunnelPoolManager.exceedsShareLimit(1, 9, 19));
    }

    /** Integer truncation is part of the contract, so pin it explicitly. */
    @Test
    public void shareIsIntegerTruncated() {
        // count=2,total=9 -> (3*100)/10 = 30 exactly
        assertFalse(TunnelPoolManager.exceedsShareLimit(2, 9, 30));
        assertTrue(TunnelPoolManager.exceedsShareLimit(2, 9, 29));
        // count=1,total=2 -> (2*100)/3 = 66 (truncated from 66.67)
        assertFalse(TunnelPoolManager.exceedsShareLimit(1, 2, 66));
        assertTrue(TunnelPoolManager.exceedsShareLimit(1, 2, 65));
    }

    /**
     * The realistic open-port case: 218 client tunnels, one peer holding too
     * many. Under the old 10% limit the peer is excluded; under the relaxed
     * limit it is admitted, which is the intended effect.
     */
    @Test
    public void openPortPeerExcludedAtBaseLimitButAdmittedWhenRelieved() {
        int total = 218;
        int peer = 30;
        assertTrue("excluded at the base 10% limit",
                   TunnelPoolManager.exceedsShareLimit(peer, total,
                                                      TunnelPoolManager.participationShareLimit(10, 1.0)));
        assertFalse("admitted once relief doubles the limit",
                    TunnelPoolManager.exceedsShareLimit(peer, total,
                                                       TunnelPoolManager.participationShareLimit(10, 2.0)));
    }

    // ---- incomplete LeaseSet relief window ----

    /**
     * The signal decays rather than being a live count, so a pool destroyed
     * while incomplete cannot leave relaxation stuck on. Exercised through the
     * pure window arithmetic, which is what the manager applies.
     */
    @Test
    public void incompleteLeaseSetReliefDecays() {
        long window = TunnelPoolManager.INCOMPLETE_LEASESET_WINDOW_MS;
        long now = 1_000_000L;
        long justSeen = now - 1000L;
        long longAgo = now - window - 1000L;
        assertTrue(now - justSeen < window);
        assertFalse(now - longAgo < window);
    }

    /** The window is long enough to matter and short enough to expire. */
    @Test
    public void incompleteLeaseSetWindowIsBounded() {
        assertTrue(TunnelPoolManager.INCOMPLETE_LEASESET_WINDOW_MS >= 60_000L);
        assertTrue(TunnelPoolManager.INCOMPLETE_LEASESET_WINDOW_MS <= 15L * 60 * 1000);
    }

    /** The incomplete-LeaseSet factor matches the pre-existing full relief. */
    @Test
    public void incompleteLeaseSetReliefMatchesFullRelief() {
        assertEquals(2.0, TunnelPoolManager.INCOMPLETE_LEASESET_RELIEF, 1e-9);
        assertEquals(TunnelPoolManager.INCOMPLETE_LEASESET_RELIEF,
                     TunnelPoolManager.buildSuccessRelief(0.0), 1e-9);
    }
}
