package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the shared predicate {@link Tuner#networkHealthy(double)}, which
 * replaced nine identical inline copies of "is the tunnel build success rate
 * above 0.7" across the Tuner's params.
 *
 * <p>Nine params asked that question with the same number written out nine
 * times, while the same file also carries 0.6, 0.8, 0.85 and 0.95 for adjacent
 * decisions, and two params - the pool rebuild backoff and the pool failure
 * threshold - read the same stat on its raw scale and compare against 90, 85
 * and 70. {@code tunnel.buildSuccessRate} is emitted by
 * {@code BuildExecutor} as {@code (successes * 100) / count}, so a threshold
 * applied to the wrong scale pins a param on one branch forever: a raw 0-100
 * reading tested against 0.7 is true for any router that builds a single
 * tunnel, and a 0.0-1.0 reading tested against 90 is never true at all.
 *
 * @since 0.9.72+
 */
public class TunerBuildSuccessHealthTest {

    /**
     * The build-success cases. Every case calls the static predicate, so the
     * instance holds no state and needs no router or tunnel pool.
     */
    public TunerBuildSuccessHealthTest() {
    }

    /**
     * The shared threshold is the 0.7 the nine call sites each wrote out, on
     * the fraction scale the params' accessor returns - not the 0-100 scale the
     * stat is emitted on.
     */
    @Test
    public void thresholdIsTheSharedFraction() {
        assertEquals(0.7d, Tuner.HEALTHY_BUILD_SUCCESS, 0.0001d);
        assertTrue("threshold is on the 0.0-1.0 scale, not the emitted 0-100 scale",
                   Tuner.HEALTHY_BUILD_SUCCESS < 1.0d);
    }

    /**
     * No data is neutral, not a failure. A param that treated empty stats as
     * unhealthy would sit wherever startup left it on a router that has not
     * built tunnels in the window - the ordinary state just after a restart.
     */
    @Test
    public void noDataIsHealthy() {
        assertTrue(Tuner.networkHealthy(Double.NaN));
    }

    /**
     * The boundary is strict, as every call site had it: exactly 70 % is not
     * healthy, and the first step above it is.
     */
    @Test
    public void boundaryIsStrictlyAbove() {
        assertFalse(Tuner.networkHealthy(0.7d));
        assertTrue(Tuner.networkHealthy(0.7001d));
    }

    /** A network that is failing to build tunnels is not healthy. */
    @Test
    public void failingBuildsAreNotHealthy() {
        assertFalse(Tuner.networkHealthy(0.0d));
        assertFalse(Tuner.networkHealthy(0.4d));
        assertFalse(Tuner.networkHealthy(0.69d));
    }

    /** A network building every tunnel asked is healthy. */
    @Test
    public void succeedingBuildsAreHealthy() {
        assertTrue(Tuner.networkHealthy(0.71d));
        assertTrue(Tuner.networkHealthy(1.0d));
    }
}
