package net.i2p.router.sybil;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

/**
 * Tests for Points scoring, reason tracking, comparison, and persistence.
 */
public class PointsTest {

    @Test
    public void testInitialPoints() {
        Points p = new Points(10.0, "test reason");
        assertEquals(10.0, p.getPoints(), 0.001);
    }

    @Test
    public void testInitialReasons() {
        Points p = new Points(5.0, "reason one");
        List<String> reasons = p.getReasons();
        assertEquals(1, reasons.size());
        assertTrue(reasons.get(0).contains("reason one"));
    }

    @Test
    public void testAddPointsAccumulates() {
        Points p = new Points(10.0, "first");
        p.addPoints(5.0, "second");
        assertEquals(15.0, p.getPoints(), 0.001);
    }

    @Test
    public void testAddPointsReasonsAccumulate() {
        Points p = new Points(1.0, "r1");
        p.addPoints(2.0, "r2");
        p.addPoints(3.0, "r3");
        assertEquals(3, p.getReasons().size());
    }

    @Test
    public void testAddNegativePoints() {
        Points p = new Points(10.0, "initial");
        p.addPoints(-3.0, "deduction");
        assertEquals(7.0, p.getPoints(), 0.001);
    }

    @Test
    public void testAddZeroPoints() {
        Points p = new Points(5.0, "initial");
        p.addPoints(0.0, "zero");
        assertEquals(5.0, p.getPoints(), 0.001);
        assertEquals(2, p.getReasons().size());
    }

    @Test
    public void testReasonFormatIncludesPoints() {
        Points p = new Points(7.5, "my reason");
        String reason = p.getReasons().get(0);
        assertTrue("Reason should start with formatted points", reason.startsWith("7.50:"));
        assertTrue("Reason should contain description", reason.contains("my reason"));
    }

    @Test
    public void testCompareToEqual() {
        Points a = new Points(10.0, "a");
        Points b = new Points(10.0, "b");
        assertEquals(0, a.compareTo(b));
    }

    @Test
    public void testCompareToLess() {
        Points a = new Points(5.0, "a");
        Points b = new Points(10.0, "b");
        assertTrue(a.compareTo(b) < 0);
    }

    @Test
    public void testCompareToGreater() {
        Points a = new Points(15.0, "a");
        Points b = new Points(10.0, "b");
        assertTrue(a.compareTo(b) > 0);
    }

    @Test
    public void testToStringContainsPoints() {
        Points p = new Points(42.5, "reason");
        String s = p.toString();
        assertTrue(s.contains("42.5"));
    }

    @Test
    public void testToStringPersistence() {
        Points p = new Points(25.0, "first reason");
        p.addPoints(10.0, "second reason");
        StringBuilder buf = new StringBuilder();
        p.toString(buf);
        String persisted = buf.toString();
        assertTrue(persisted.contains("25.0"));
        assertTrue(persisted.contains("%"));
        assertTrue(persisted.contains("first reason"));
        assertTrue(persisted.contains("second reason"));
    }

    @Test
    public void testFromStringValid() {
        String input = "35.5%5.00: first reason%10.00: second reason";
        Points p = Points.fromString(input);
        assertNotNull(p);
        assertEquals(35.5, p.getPoints(), 0.001);
        assertEquals(2, p.getReasons().size());
    }

    @Test
    public void testFromStringSingleReason() {
        String input = "12.0%3.00: only reason";
        Points p = Points.fromString(input);
        assertNotNull(p);
        assertEquals(12.0, p.getPoints(), 0.001);
        assertEquals(1, p.getReasons().size());
    }

    @Test
    public void testFromStringInvalidNoSeparator() {
        assertNull("No separator should return null", Points.fromString("10.0"));
    }

    @Test
    public void testFromStringInvalidPoints() {
        assertNull("Non-numeric points should return null", Points.fromString("abc%reason"));
    }

    @Test
    public void testFromStringEmpty() {
        assertNull("Empty string should return null", Points.fromString(""));
    }

    @Test
    public void testRoundTripPersistence() {
        Points original = new Points(99.9, "alpha");
        original.addPoints(0.1, "beta");
        StringBuilder buf = new StringBuilder();
        original.toString(buf);

        Points restored = Points.fromString(buf.toString());
        assertNotNull(restored);
        assertEquals(original.getPoints(), restored.getPoints(), 0.001);
        assertEquals(original.getReasons().size(), restored.getReasons().size());
    }

    @Test
    public void testNegativePointsPersistence() {
        Points p = new Points(-10.0, "negative");
        StringBuilder buf = new StringBuilder();
        p.toString(buf);

        Points restored = Points.fromString(buf.toString());
        assertNotNull(restored);
        assertEquals(-10.0, restored.getPoints(), 0.001);
    }

    @Test
    public void testLargePointsAccumulation() {
        Points p = new Points(0.0, "start");
        for (int i = 0; i < 1000; i++) {
            p.addPoints(0.1, "reason " + i);
        }
        assertEquals(100.0, p.getPoints(), 0.01);
        assertEquals(1001, p.getReasons().size());
    }
}
