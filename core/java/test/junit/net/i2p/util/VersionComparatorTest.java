package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class VersionComparatorTest {

    @Test
    public void testEqualVersions() {
        assertEquals(0, VersionComparator.comp("0.9.50", "0.9.50"));
    }

    @Test
    public void testAscending() {
        assertTrue(VersionComparator.comp("0.9.49", "0.9.50") < 0);
    }

    @Test
    public void testDescending() {
        assertTrue(VersionComparator.comp("0.9.50", "0.9.49") > 0);
    }

    @Test
    public void testMajorVersionDifference() {
        assertTrue(VersionComparator.comp("0.9.50", "1.0.0") < 0);
    }

    @Test
    public void testMultiDigitComponents() {
        assertTrue(VersionComparator.comp("0.9.99", "0.9.100") < 0);
    }

    @Test
    public void testUnderscoreSeparator() {
        assertEquals(0, VersionComparator.comp("0.9.50", "0_9_50"));
    }

    @Test
    public void testDashSeparator() {
        assertEquals(0, VersionComparator.comp("0.9.50", "0-9-50"));
    }

    @Test
    public void testMixedSeparators() {
        assertEquals(0, VersionComparator.comp("0.9-50", "0_9.50"));
    }

    @Test
    public void testShorterVersionPrefix() {
        assertTrue(VersionComparator.comp("0.9", "0.9.1") < 0);
    }

    @Test
    public void testComparatorInstance() {
        VersionComparator vc = new VersionComparator();
        assertEquals(0, vc.compare("1.2.3", "1.2.3"));
        assertTrue(vc.compare("1.0", "2.0") < 0);
    }

    @Test
    public void testEmptyStrings() {
        assertEquals(0, VersionComparator.comp("", ""));
    }

    @Test
    public void testNonNumericIgnored() {
        assertTrue(VersionComparator.comp("v0.9.50", "v0.9.51") < 0);
    }
}
