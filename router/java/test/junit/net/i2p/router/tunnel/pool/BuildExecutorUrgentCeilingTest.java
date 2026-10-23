package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The urgency lift on the concurrent-build ceiling. The lift used to be written
 * into the static configured ceiling, and nothing ever lowered it again: one
 * burst of expiry-urgent pools left the router permanently above its Tuner
 * setting, and the throttle's restore step then ramped back up to the elevated
 * value instead of the configured one. The lift is now per-pass instance state.
 * <p>
 * The configured ceiling is pinned to its minimum for these tests. Left at the
 * default it is cores*4, which on a 24-core box is already the absolute maximum
 * of 96, so no lift is reachable and the elevation assertions say nothing.
 *
 * @since 0.9.72+
 */
public class BuildExecutorUrgentCeilingTest {

    /** BuildExecutor's private absolute ceiling; bounds how far one pass can lift. */
    private static final int MAX_BUILD_CONCURRENCY = 96;
    /** The lowest ceiling setMaxConcurrentBuilds() accepts. */
    private static final int MIN_BUILD_CONCURRENCY = 8;

    private RouterContext _ctx;
    private int _savedCeiling;

    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _savedCeiling = BuildExecutor.getMaxConcurrentBuilds();
        BuildExecutor.setMaxConcurrentBuilds(MIN_BUILD_CONCURRENCY);
    }

    @After
    public void tearDown() {
        BuildExecutor.setMaxConcurrentBuilds(_savedCeiling);
    }

    /**
     * A pool holding {@code nearExpiry} leases inside the pre-build window.
     *
     * @param nearExpiry the value getNearExpiryCount() reports from now on; 0
     *                   means a pool with nothing urgent to rebuild
     * @return a mock pool answering only the near-expiry count query
     */
    private static TunnelPool pool(int nearExpiry) {
        TunnelPool p = mock(TunnelPool.class);
        when(p.getNearExpiryCount()).thenReturn(nearExpiry);
        return p;
    }

    /**
     * {@code n} pools that each hold a lease about to expire.
     *
     * @param n the number of pools to create, each reporting one near-expiry
     *          lease
     * @return a list of mock pools, in creation order
     */
    private static List<TunnelPool> urgentPools(int n) {
        List<TunnelPool> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            l.add(pool(1));
        return l;
    }

    private BuildExecutor newBuildExecutor() {
        return new BuildExecutor(_ctx, new TunnelPoolManager(_ctx), new GhostPeerManager(_ctx));
    }

    /** With nothing urgent the pass ceiling is just the adaptive one. */
    @Test
    public void testNoUrgencyNoLift() {
        BuildExecutor exec = newBuildExecutor();
        int base = exec.getAdaptiveMaxConcurrentBuilds();
        assertEquals(base, exec.refreshUrgentCeiling(Collections.<TunnelPool>emptyList()));
        assertEquals(base, exec.refreshUrgentCeiling(Collections.singletonList(pool(0))));
        assertEquals(base, exec.getUrgentMaxConcurrentBuilds());
    }

    /** Expiry-urgent demand does lift the ceiling - the precondition the rest needs. */
    @Test
    public void testUrgencyLiftsTheCeiling() {
        BuildExecutor exec = newBuildExecutor();
        int ceiling = exec.refreshUrgentCeiling(urgentPools(MIN_BUILD_CONCURRENCY + 1));
        assertTrue("ceiling " + ceiling + " must exceed the adaptive " +
                   exec.getAdaptiveMaxConcurrentBuilds(),
                   ceiling > exec.getAdaptiveMaxConcurrentBuilds());
        assertEquals(ceiling, exec.getUrgentMaxConcurrentBuilds());
    }

    /**
     * The regression: the lift must not be written into the static configured
     * ceiling, where it would outlive the urgency that caused it.
     */
    @Test
    public void testLiftDoesNotMoveTheConfiguredCeiling() {
        BuildExecutor exec = newBuildExecutor();
        int ceiling = exec.refreshUrgentCeiling(urgentPools(MIN_BUILD_CONCURRENCY + 1));
        assertTrue("precondition: the pass must have lifted", ceiling > MIN_BUILD_CONCURRENCY);
        assertEquals("the configured ceiling must be untouched",
                     MIN_BUILD_CONCURRENCY, BuildExecutor.getMaxConcurrentBuilds());
    }

    /**
     * The lift decays on its own: the next pass without urgent demand is back at
     * the adaptive ceiling. This is what the static ratchet could not do.
     */
    @Test
    public void testLiftDecaysWhenUrgencyGoes() {
        BuildExecutor exec = newBuildExecutor();
        int lifted = exec.refreshUrgentCeiling(urgentPools(MIN_BUILD_CONCURRENCY + 1));
        assertTrue("precondition: the pass must have lifted",
                   lifted > exec.getAdaptiveMaxConcurrentBuilds());
        assertEquals("a calm pass must be back at the adaptive ceiling",
                     exec.getAdaptiveMaxConcurrentBuilds(),
                     exec.refreshUrgentCeiling(Collections.<TunnelPool>emptyList()));
        assertEquals(exec.getAdaptiveMaxConcurrentBuilds(), exec.getUrgentMaxConcurrentBuilds());
    }

    /** Many urgent passes in a row still never ratchet the configured ceiling up. */
    @Test
    public void testRepeatedUrgentPassesNeverRatchetTheStatic() {
        BuildExecutor exec = newBuildExecutor();
        for (int i = 0; i < 200; i++)
            exec.refreshUrgentCeiling(urgentPools(500));
        assertEquals(MIN_BUILD_CONCURRENCY, BuildExecutor.getMaxConcurrentBuilds());
    }

    /** The lift is bounded, however many pools are urgent. */
    @Test
    public void testLiftIsBounded() {
        BuildExecutor exec = newBuildExecutor();
        int ceiling = exec.refreshUrgentCeiling(urgentPools(5000));
        assertTrue("ceiling " + ceiling + " over the absolute maximum",
                   ceiling <= MAX_BUILD_CONCURRENCY);
        assertEquals(MAX_BUILD_CONCURRENCY, ceiling);
    }

    /** Ordinary deficit - pools below target but not near expiry - never lifts. */
    @Test
    public void testOrdinaryDeficitDoesNotLift() {
        BuildExecutor exec = newBuildExecutor();
        List<TunnelPool> wanted = new ArrayList<>();
        for (int i = 0; i < 100; i++)
            wanted.add(pool(0));
        assertEquals(exec.getAdaptiveMaxConcurrentBuilds(), exec.refreshUrgentCeiling(wanted));
    }
}
