package net.i2p.i2ptunnel.access;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests Threshold equality, the gate the access-filter reload uses to decide
 * whether a listed destination's rule changed and its tracker must be rebuilt.
 *
 * @since 0.9.72+
 */
public class ThresholdEqualityTest {

    @Test
    public void testEqualCountsAndWindows() {
        assertEquals(new Threshold(15, 5), new Threshold(15, 5));
        assertEquals(new Threshold(15, 5).hashCode(), new Threshold(15, 5).hashCode());
    }

    @Test
    public void testDifferentCountOrWindow() {
        assertNotEquals(new Threshold(15, 5), new Threshold(16, 5));
        assertNotEquals(new Threshold(15, 5), new Threshold(15, 6));
    }

    @Test
    public void testSentinels() {
        assertEquals(Threshold.ALLOW, new Threshold(Integer.MAX_VALUE, 1));
        assertEquals(Threshold.DENY, new Threshold(0, 1));
        assertNotEquals(Threshold.ALLOW, Threshold.DENY);
    }

    @Test
    public void testNotEqualToOtherTypes() {
        assertFalse(new Threshold(15, 5).equals("15/5"));
        assertFalse(new Threshold(15, 5).equals(null));
    }
}
