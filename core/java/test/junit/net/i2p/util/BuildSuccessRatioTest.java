package net.i2p.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The canonical tunnel build-success ratio, in {@link SystemVersion}.
 *
 * <p>Three implementations existed and none agreed. {@code ProfileOrganizer} asked for the
 * ten-minute rate and then read {@code getCurrentEventCount()} - the current <em>partial</em>
 * period - so it sampled a fraction of one bucket and discarded the history.
 * {@code SystemVersion} read {@code getLastEventCount()}, the last <em>completed</em> period.
 * The Tuner used a different set of stats entirely, hourly. The value is not cosmetic: it
 * gates profile eviction, selects the ghost cooldown length, and feeds
 * {@code ClientPeerSelector} first-hop selection.
 *
 * @since 0.9.71+
 */
public class BuildSuccessRatioTest {

    // ---- the pure arithmetic ----

    @Test
    public void ratiosSuccessesAgainstEverythingSettled() {
        assertEquals(0.5, SystemVersion.buildSuccessRatio(5, 3, 2), 1e-9);
        assertEquals(1.0, SystemVersion.buildSuccessRatio(9, 0, 0), 1e-9);
        assertEquals(0.0, SystemVersion.buildSuccessRatio(0, 3, 2), 1e-9);
    }

    @Test
    public void rejectsAndExpireBothCountAgainstSuccess() {
        // expire and reject are distinct outcomes, neither is a success
        assertEquals(1.0 / 3, SystemVersion.buildSuccessRatio(1, 1, 1), 1e-9);
        assertEquals(2.0 / 4, SystemVersion.buildSuccessRatio(2, 1, 1), 1e-9);
    }

    /** An empty window is unknown, not zero and not a perfect score. */
    @Test
    public void emptyWindowIsNaN() {
        assertTrue(Double.isNaN(SystemVersion.buildSuccessRatio(0, 0, 0)));
    }

    @Test
    public void negativeTotalsAreNaN() {
        assertTrue(Double.isNaN(SystemVersion.buildSuccessRatio(-1, 0, 0)));
    }

    // A note on what is deliberately not tested here. The implementation reads
    // getLastEventCount() rather than getAverageValue(), and the choice matters: the three stats
    // record different magnitudes - success and reject a round-trip time in ms, expire a literal
    // 1 - so an average is not a proxy for a count. Neither accessor can be exercised on a
    // synthetic Rate, though: getLastEventCount() needs a completed period and
    // getAverageValue() needs elapsed time, so both return zero on a rate built inside a test.
    // Asserting the choice here would be asserting the mock rather than the behaviour, so it is
    // pinned in the implementation's own comment and checked against the live stats instead.

    /**
     * What the fix does and does not buy, stated plainly.
     *
     * <p>It removes the dependence on where in the period the value was sampled, which is
     * what made the reading swing. It cannot make a genuinely bad window read well: a window
     * containing nothing but expiries still reads 0.0, and any ratio will. What changes is
     * that such a window has to actually be bad, rather than one unlucky partial bucket
     * looking like one.
     */
    @Test
    public void aGenuinelyBadWindowStillReadsBad() {
        assertEquals(0.0, SystemVersion.buildSuccessRatio(0, 0, 40), 1e-9);
        assertEquals(0.25, SystemVersion.buildSuccessRatio(10, 20, 10), 1e-9);
        assertTrue("a real collapse must still be visible, or the guard is useless",
                   SystemVersion.buildSuccessRatio(10, 20, 10) < 0.40);
    }

    /** A realistic ten minute window at this router's observed rate is nowhere near empty. */
    @Test
    public void realisticWindowIsNotSparse() {
        // roughly 70 settled builds a minute, so a ten minute window holds ~700
        int successes = 385, rejects = 210, expires = 105;
        double r = SystemVersion.buildSuccessRatio(successes, rejects, expires);
        assertTrue("a full window must read as a probability", r > 0.40);
        assertEquals(0.55, r, 0.001);
    }

    // ---- the window constant ----

    @Test
    public void windowIsTenMinutes() {
        assertEquals(10 * 60 * 1000, SystemVersion.BUILD_SUCCESS_WINDOW_MS);
    }

    /**
     * The threshold this value is compared against. If the ratio is now read from a full
     * window rather than a partial one, it should sit comfortably above it in steady state -
     * the point of the fix is that ordinary operation no longer trips it.
     */
    @Test
    public void steadyStateSitsAboveTheAttackThreshold() {
        double steadyState = SystemVersion.buildSuccessRatio(55, 25, 20);
        assertTrue("a 55% stream must not read as an attack",
                   steadyState > 0.40);
    }
}
