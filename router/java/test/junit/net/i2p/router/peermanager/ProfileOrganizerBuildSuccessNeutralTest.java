package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The unknown-reading contract for {@code ProfileOrganizer.getTunnelBuildSuccess()}.
 *
 * <p>The ratio itself is implemented in core and tested in {@code BuildSuccessRatioTest} there;
 * it cannot be exercised from this module because the router's test classpath carries core's
 * test jar rather than its main one. What is specific to this side is the mapping of an
 * unknown reading onto a neutral 1.0, and that mapping gates three separate behaviours - profile
 * eviction, the ghost cooldown, and first-hop selection - so it is pinned here.
 *
 * <p>This replaces a test that used to feed synthetic {@code Rate} objects into the ratio
 * directly. That is no longer possible: the canonical implementation reads the count over the
 * last completed window, which a rate built inside a test has no value for.
 *
 * @since 0.9.71+
 */
public class ProfileOrganizerBuildSuccessNeutralTest {

    @Test
    public void unknownBecomesNeutral() {
        assertEquals(1.0, ProfileOrganizer.neutraliseUnknownBuildSuccess(Double.NaN), 0.0);
    }

    /** The whole point: unknown must not be readable as failure. */
    @Test
    public void unknownIsNotBelowTheAttackThreshold() {
        double mapped = ProfileOrganizer.neutraliseUnknownBuildSuccess(Double.NaN);
        assertTrue("an absent or empty window must not read as an attack",
                   mapped > 0.40);
    }

    @Test
    public void aRealRatioIsPassedThroughUnchanged() {
        double[] real = {0.0, 0.01, 0.2, 0.4, 0.55, 0.668, 0.9, 1.0};
        for (double r : real) {
            assertEquals("a real reading must not be softened", r,
                         ProfileOrganizer.neutraliseUnknownBuildSuccess(r), 0.0);
        }
    }

    /**
     * A genuine collapse must survive the mapping. Only NaN is softened - 0.0 is a real answer
     * meaning builds settled and none won, and softening it would hide exactly the condition
     * the guard exists to catch.
     */
    @Test
    public void aRealZeroIsNotSoftened() {
        double mapped = ProfileOrganizer.neutraliseUnknownBuildSuccess(0.0);
        assertEquals(0.0, mapped, 0.0);
        assertTrue("a real collapse must still be visible to the guard", mapped < 0.40);
    }

    @Test
    public void onlyNaNIsTreatedAsUnknown() {
        // infinity is not a ratio this can produce, but it must not be silently turned into
        // the neutral reading either
        assertEquals(Double.POSITIVE_INFINITY,
                     ProfileOrganizer.neutraliseUnknownBuildSuccess(Double.POSITIVE_INFINITY), 0.0);
        assertEquals(Double.NEGATIVE_INFINITY,
                     ProfileOrganizer.neutraliseUnknownBuildSuccess(Double.NEGATIVE_INFINITY), 0.0);
    }
}
