package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import net.i2p.stat.Rate;
import org.junit.Test;

/**
 * Build-success ratio computation: real ratios when data exists, 1.0 (neutral,
 * not "under attack") when any rate is missing or the window is empty.
 *
 * @since 0.9.71+
 */
public class ProfileOrganizerBuildSuccessTest {

    private static final int TEN_MINUTES_MS = 10 * 60 * 1000;

    private Rate rate(long value) {
        Rate r = new Rate(TEN_MINUTES_MS);
        if (value > 0) {
            r.addData(value);
        }
        return r;
    }

    @Test
    public void testRatioFromData() {
        double ratio = ProfileOrganizer.buildSuccessRatio(rate(5), rate(2), rate(3), rate(5), rate(3), rate(2));
        // (3+2) / ((5+5)+(2+3)+(3+2)) = 5 / 20
        assertEquals(0.25, ratio, 0.0001);
    }

    @Test
    public void testMissingRateIsNeutral() {
        assertEquals(1.0, ProfileOrganizer.buildSuccessRatio(null, rate(2), rate(3), rate(5), rate(3), rate(2)), 0.0);
        assertEquals(1.0, ProfileOrganizer.buildSuccessRatio(rate(5), rate(2), rate(3), rate(5), null, rate(2)), 0.0);
        assertEquals(1.0, ProfileOrganizer.buildSuccessRatio(rate(5), null, null, null, null, null), 0.0);
    }

    @Test
    public void testEmptyWindowIsNeutral() {
        assertEquals(1.0, ProfileOrganizer.buildSuccessRatio(rate(0), rate(0), rate(0), rate(0), rate(0), rate(0)), 0.0);
    }

    @Test
    public void testZeroSuccessIsZeroRatio() {
        assertEquals(0.0, ProfileOrganizer.buildSuccessRatio(rate(10), rate(0), rate(0), rate(10), rate(0), rate(0)), 0.0001);
    }

    @Test
    public void testAttackThresholdCrossing() {
        // under attack: 0.39 < 0.40
        double under = ProfileOrganizer.buildSuccessRatio(rate(61), rate(0), rate(39), rate(0), rate(0), rate(0));
        assertTrue(under < ProfileOrganizer.ATTACK_THRESHOLD);
        // healthy: 0.60 >= 0.40
        double over = ProfileOrganizer.buildSuccessRatio(rate(40), rate(0), rate(60), rate(0), rate(0), rate(0));
        assertTrue(over >= ProfileOrganizer.ATTACK_THRESHOLD);
    }
}