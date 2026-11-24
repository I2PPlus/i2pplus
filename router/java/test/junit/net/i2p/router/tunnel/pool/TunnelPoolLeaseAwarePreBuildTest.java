package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Contract tests for {@link TunnelPool#needsLeaseAwarePreBuild(boolean, long, long, long, long)},
 * the trigger that makes a server pool replace tunnels early enough to keep the
 * LeaseSet it publishes advertised for the lease cap rather than the lease floor.
 *
 * <p>A pool at its target tunnel count is not the same thing as a pool that can
 * publish a healthy LeaseSet.  Every tunnel it holds can be older than the expiry
 * the published set already advertises, in which case the next mint re-signs the
 * same short copy and the advertised life cannot move.  Measured on a live
 * router, hosted destinations published LeaseSets that lived 3m - the lease
 * floor - while their pools sat at target with nothing building, and each
 * re-mint logged a stored expiry and a fresh expiry of the same 3m.  A set
 * advertised for 3m has to be re-looked-up three times as often as one
 * advertised for the 10m the lease cap allows, and a client holding a copy that
 * is already near its end is the "website not found" that follows.
 *
 * <p>The trigger is deliberately self-limiting: it engages only while the
 * published set's remaining life is inside the widened window, so the extra
 * builds are spent only while the lease supply is short.
 *
 * @since 0.9.72+
 */
public class TunnelPoolLeaseAwarePreBuildTest {

    /** One minute in milliseconds. */
    private static final long MINUTE = 60 * 1000L;
    /** The lease floor, matching {@code LEASE_MIN_REMAINING_MS}. */
    private static final long LEASE_FLOOR = 3 * MINUTE;
    /** The ordinary proactive window, matching {@code PRE_BUILD_WINDOW_MS}. */
    private static final long WINDOW = 3 * MINUTE;
    /** A fixed clock so the arithmetic under test is readable. */
    private static final long NOW = 1_700_000_000_000L;

    /**
     * JUnit creates one instance per test method; the predicate under test is
     * static, so the instance carries no state.
     */
    public TunnelPoolLeaseAwarePreBuildTest() {
    }

    /**
     * The regression: a published set down at the lease floor cannot be lifted by
     * the tunnels the pool already holds, so the pool has to be replacing them
     * now.
     */
    @Test
    public void widensTheWindowWhenThePublishedSetIsAtTheLeaseFloor() {
        assertTrue(TunnelPool.needsLeaseAwarePreBuild(true, NOW + 3 * MINUTE, NOW,
                                                      LEASE_FLOOR, WINDOW));
    }

    /**
     * A set already past its advertised end is worse than one at the floor, and
     * the trigger has to stay engaged through it.
     */
    @Test
    public void widensTheWindowWhenThePublishedSetHasLapsed() {
        assertTrue(TunnelPool.needsLeaseAwarePreBuild(true, NOW - MINUTE, NOW,
                                                      LEASE_FLOOR, WINDOW));
    }

    /**
     * Exactly at the widened bar still engages - that is the point at which a
     * replacement built now lands while the oldest tunnel can still carry a
     * full-length lease.
     */
    @Test
    public void engagesAtTheWidenedBoundary() {
        assertTrue(TunnelPool.needsLeaseAwarePreBuild(true, NOW + LEASE_FLOOR + WINDOW, NOW,
                                                      LEASE_FLOOR, WINDOW));
    }

    /**
     * A millisecond past the bar disengages, which is what stops the extra build
     * volume once the published life is back.
     */
    @Test
    public void disengagesJustPastTheWidenedBoundary() {
        assertFalse(TunnelPool.needsLeaseAwarePreBuild(true, NOW + LEASE_FLOOR + WINDOW + 1, NOW,
                                                       LEASE_FLOOR, WINDOW));
    }

    /**
     * A set advertised for the life the lease cap allows needs nothing extra:
     * the ordinary window already keeps it there.
     */
    @Test
    public void leavesAHealthyPublishedSetAlone() {
        assertFalse(TunnelPool.needsLeaseAwarePreBuild(true, NOW + 9 * MINUTE, NOW,
                                                       LEASE_FLOOR, WINDOW));
    }

    /**
     * A pool that has published nothing yet is not in this state - there is no
     * short advertised life to lift, and the mint path handles the empty case.
     */
    @Test
    public void nothingPublishedNeedsNoWidening() {
        assertFalse(TunnelPool.needsLeaseAwarePreBuild(true, 0, NOW, LEASE_FLOOR, WINDOW));
    }

    /**
     * Only a pool that publishes a LeaseSet has a lease supply to protect.  An
     * outbound or exploratory pool spends the same build budget on bandwidth, and
     * widening its window would take builds from the direction the LeaseSet
     * problem does not involve.
     */
    @Test
    public void nonServerPoolsNeverWiden() {
        assertFalse(TunnelPool.needsLeaseAwarePreBuild(false, NOW + MINUTE, NOW,
                                                       LEASE_FLOOR, WINDOW));
        assertFalse(TunnelPool.needsLeaseAwarePreBuild(false, NOW - MINUTE, NOW,
                                                       LEASE_FLOOR, WINDOW));
    }

    /**
     * The bar tracks the window the pool is already using.  A pool whose builds
     * are timing out runs on a 6m window, so its widened bar is 9m and a set
     * advertised for 7m still engages - the pool that is slow to build is the one
     * that has to start earliest.
     */
    @Test
    public void aTimeoutScaledWindowRaisesTheBarWithIt() {
        long timeoutWindow = 6 * MINUTE;
        assertTrue(TunnelPool.needsLeaseAwarePreBuild(true, NOW + 7 * MINUTE, NOW,
                                                      LEASE_FLOOR, timeoutWindow));
        assertFalse(TunnelPool.needsLeaseAwarePreBuild(true, NOW + 10 * MINUTE, NOW,
                                                       LEASE_FLOOR, timeoutWindow));
    }
}
