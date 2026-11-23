package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the build-success tiers {@link Tuner} hands its params: the scale
 * adapter {@link Tuner#buildSuccessFraction(double)} and the predicates
 * {@link Tuner#buildsPoor}, {@link Tuner#buildsUsable},
 * {@link Tuner#buildsUnusable}, {@link Tuner#buildsWeak},
 * {@link Tuner#buildsHealthy}, {@link Tuner#buildsBelowStrong},
 * {@link Tuner#buildsStrong} and {@link Tuner#networkHealthy}.
 *
 * <p>Twelve params observe {@code tunnel.buildSuccessRate}, which
 * {@code BuildExecutor} emits as {@code (successes * 100) / count}. Four of them
 * divided by 100 in their own accessor and compared against 0.4 to 0.95; eight
 * read it raw and compared against 50, 60, 70, 80, 85 and 90. Reading one scale
 * with the other's thresholds pins a param on one branch forever: a raw 0-100
 * reading tested against 0.7 is true for any router that builds a single tunnel,
 * and a 0.0-1.0 reading tested against 90 is never true at all. Every param now
 * converts in one place and every threshold is a fraction.
 *
 * <p>The tiers themselves replace numbers that had been written out at eleven
 * call sites, four of them drifted: the high-capacity peer tier grew on 0.6
 * while the fast-peer tier grew on 0.7, the build timeout raised on 0.6 and
 * lowered on 0.85, and the pool spare buffer called 0.8 healthy while the pool
 * backoff called 0.85 failing.
 *
 * @since 0.9.72+
 */
public class TunerBuildSuccessHealthTest {

    /**
     * The build-success cases. Every case calls a static predicate, so the
     * instance holds no state and needs no router or tunnel pool.
     */
    public TunerBuildSuccessHealthTest() {
    }

    /**
     * The tiers are ordered and all on the 0.0-1.0 scale, not the emitted 0-100
     * one: a tier that drifted onto the raw scale would read as healthy for any
     * router that built a single tunnel.
     */
    @Test
    public void tiersAreOrderedFractions() {
        assertTrue(Tuner.BUILD_SUCCESS_POOR < Tuner.BUILD_SUCCESS_USABLE);
        assertTrue(Tuner.BUILD_SUCCESS_USABLE < Tuner.BUILD_SUCCESS_HEALTHY);
        assertTrue(Tuner.BUILD_SUCCESS_HEALTHY < Tuner.BUILD_SUCCESS_STRONG);
        assertTrue(Tuner.BUILD_SUCCESS_STRONG <= 1.0d);
        assertEquals(0.4d, Tuner.BUILD_SUCCESS_POOR, 0.0001d);
        assertEquals(0.5d, Tuner.BUILD_SUCCESS_USABLE, 0.0001d);
        assertEquals(0.7d, Tuner.BUILD_SUCCESS_HEALTHY, 0.0001d);
        assertEquals(0.9d, Tuner.BUILD_SUCCESS_STRONG, 0.0001d);
    }

    /**
     * The adapter puts the emitted percentage on the fraction scale, and leaves
     * no data as no data rather than turning it into zero - a NaN read as 0.0
     * would read as a router that builds nothing.
     */
    @Test
    public void adapterConvertsAndKeepsNoData() {
        assertEquals(0.54d, Tuner.buildSuccessFraction(54d), 0.0001d);
        assertEquals(1.0d, Tuner.buildSuccessFraction(100d), 0.0001d);
        assertEquals(0.0d, Tuner.buildSuccessFraction(0d), 0.0001d);
        assertTrue(Double.isNaN(Tuner.buildSuccessFraction(Double.NaN)));
    }

    /**
     * The live reading: this router ran 54-56 % build success through the
     * download that started this audit, and that is usable but weak - it must
     * grow nothing and shrink nothing on the health question alone.
     */
    @Test
    public void liveRouterIsUsableButWeak() {
        double live = Tuner.buildSuccessFraction(54d);
        assertTrue(Tuner.buildsUsable(live));
        assertFalse(Tuner.buildsUnusable(live));
        assertTrue(Tuner.buildsWeak(live));
        assertFalse(Tuner.buildsHealthy(live));
        assertFalse(Tuner.buildsPoor(live));
        assertFalse(Tuner.networkHealthy(live));
    }

    /**
     * The boundary of the healthy bar is strict, as every call site had it:
     * exactly 70 % neither grows a pool nor counts as weak, and the first step
     * either side of it decides.
     */
    @Test
    public void healthyBoundaryIsStrictlyAbove() {
        assertFalse(Tuner.buildsHealthy(0.7d));
        assertTrue(Tuner.buildsHealthy(0.7001d));
        assertFalse(Tuner.buildsWeak(0.7d));
        assertTrue(Tuner.buildsWeak(0.6999d));
    }

    /**
     * The usable bar is inclusive, as the four build-path params had it
     * ({@code observed >= 50}): exactly 50 % leaves a first-hop cooldown alone.
     */
    @Test
    public void usableBoundaryIsInclusive() {
        assertTrue(Tuner.buildsUsable(0.5d));
        assertFalse(Tuner.buildsUnusable(0.5d));
        assertFalse(Tuner.buildsUsable(0.4999d));
        assertTrue(Tuner.buildsUnusable(0.4999d));
    }

    /** The poor and strong boundaries are strict, as their call sites had them. */
    @Test
    public void poorAndStrongBoundaries() {
        assertFalse(Tuner.buildsPoor(0.4d));
        assertTrue(Tuner.buildsPoor(0.3999d));
        assertFalse(Tuner.buildsStrong(0.9d));
        assertTrue(Tuner.buildsStrong(0.9001d));
        assertFalse(Tuner.buildsBelowStrong(0.9d));
        assertTrue(Tuner.buildsBelowStrong(0.8999d));
    }

    /**
     * No data is not evidence: every predicate is false for NaN, so a param
     * whose branch is guarded by one of them leaves its value alone rather than
     * taking the "unhealthy" action on an empty window.
     */
    @Test
    public void noDataIsNotEvidence() {
        double nan = Double.NaN;
        assertFalse(Tuner.buildsPoor(nan));
        assertFalse(Tuner.buildsUsable(nan));
        assertFalse(Tuner.buildsUnusable(nan));
        assertFalse(Tuner.buildsWeak(nan));
        assertFalse(Tuner.buildsHealthy(nan));
        assertFalse(Tuner.buildsBelowStrong(nan));
        assertFalse(Tuner.buildsStrong(nan));
    }

    /**
     * {@link Tuner#networkHealthy(double)} is the one predicate where no data is
     * neutral rather than absent: a param that stopped growing whenever the stats
     * were empty would sit wherever startup left it, and empty stats are the
     * ordinary state of a router that has not built tunnels in the window.
     */
    @Test
    public void noDataIsNeutralForNetworkHealth() {
        assertTrue(Tuner.networkHealthy(Double.NaN));
        assertFalse(Tuner.networkHealthy(0.7d));
        assertTrue(Tuner.networkHealthy(0.7001d));
        assertFalse(Tuner.networkHealthy(0.0d));
    }

    /**
     * The tiers cover the scale: every rate lands on exactly one of poor,
     * unusable-but-not-poor, usable-but-weak, healthy-but-not-strong or strong,
     * except the two strict upper boundaries at 70 % and 90 %, which are inert
     * on both sides by the same strictness the call sites always had - a param
     * sitting on a threshold has to see a real change to move, which is what
     * keeps it from alternating direction every five seconds.
     */
    @Test
    public void tiersCoverTheScaleExceptTheStrictBoundaries() {
        for (int pct = 0; pct <= 100; pct++) {
            double bs = Tuner.buildSuccessFraction(pct);
            int matches = 0;
            if (Tuner.buildsPoor(bs)) matches++;
            if (!Tuner.buildsPoor(bs) && Tuner.buildsUnusable(bs)) matches++;
            if (Tuner.buildsUsable(bs) && Tuner.buildsWeak(bs)) matches++;
            if (Tuner.buildsHealthy(bs) && Tuner.buildsBelowStrong(bs)) matches++;
            if (Tuner.buildsStrong(bs)) matches++;
            if (pct == 70 || pct == 90)
                assertEquals("pct " + pct + " should be inert", 0, matches);
            else
                assertEquals("pct " + pct + " on no tier or several", 1, matches);
        }
    }
}
