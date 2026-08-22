package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the compact range representation of {@link BitField#toString()}:
 * consecutive set bits collapse to first-last ranges, singletons stay bare,
 * and boundaries (empty, all-set, trailing range) render correctly.
 *
 * @since 0.9.71+
 */
public class BitFieldToStringTest {

    private static BitField of(int size, int... setBits) {
        BitField bf = new BitField(size);
        for (int b : setBits) bf.set(b);
        return bf;
    }

    @Test
    public void emptyRendersNoEntries() {
        assertEquals("BitField(8)[]", of(8).toString());
    }

    @Test
    public void allSetIsSingleRange() {
        assertEquals("BitField(8)[0-7]", of(8, 0, 1, 2, 3, 4, 5, 6, 7).toString());
    }

    @Test
    public void singletonRendersBare() {
        assertEquals("BitField(8)[5]", of(8, 5).toString());
    }

    @Test
    public void mixedRangesAndSingletons() {
        // two runs and a gap: run-dash-end, bare singleton
        assertEquals("BitField(12)[0-2 5]", of(12, 0, 1, 2, 5).toString());
    }

    @Test
    public void trailingRangeGetsDash() {
        // a run ending at the last bit never sees an unset bit, so the
        // dash is appended by the tail handling
        assertEquals("BitField(12)[0 10-11]", of(12, 0, 10, 11).toString());
    }

    @Test
    public void trailingSingletonNoDash() {
        assertEquals("BitField(12)[0 11]", of(12, 0, 11).toString());
    }

    @Test
    public void interiorGapBetweenRuns() {
        assertEquals("BitField(16)[3-4 9-10 14]", of(16, 3, 4, 9, 10, 14).toString());
    }

    @Test
    public void largeSparseFieldStaysCompact() {
        // the whole point: 4096 pieces with scattered completion must not
        // enumerate every index
        int[] bits = new int[64];
        for (int i = 0; i < 64; i++) bits[i] = i * 64;
        String s = of(4096, bits).toString();
        assertTrue("compact form should be short", s.length() < 512);
        assertFalse(s.contains("4032 4096") && s.length() > 20000);
        assertTrue(s.startsWith("BitField(4096)["));
        assertTrue(s.endsWith("]"));
    }
}
