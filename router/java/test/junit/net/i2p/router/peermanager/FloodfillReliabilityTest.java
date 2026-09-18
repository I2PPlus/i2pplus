package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for FloodfillReliability enum values and ordinal ordering.
 */
public class FloodfillReliabilityTest {

    @Test
    public void testOrdinalOrdering() {
        assertEquals(0, FloodfillReliability.BAD.ordinal());
        assertEquals(1, FloodfillReliability.UNKNOWN.ordinal());
        assertEquals(2, FloodfillReliability.OK.ordinal());
        assertEquals(3, FloodfillReliability.GOOD.ordinal());
    }

    @Test
    public void testGoodIsMostReliable() {
        assertTrue(FloodfillReliability.GOOD.ordinal() > FloodfillReliability.OK.ordinal());
        assertTrue(FloodfillReliability.OK.ordinal() > FloodfillReliability.UNKNOWN.ordinal());
        assertTrue(FloodfillReliability.UNKNOWN.ordinal() > FloodfillReliability.BAD.ordinal());
    }

    @Test
    public void testValuesPresent() {
        assertEquals(4, FloodfillReliability.values().length);
        assertSame(FloodfillReliability.BAD, FloodfillReliability.valueOf("BAD"));
        assertSame(FloodfillReliability.UNKNOWN, FloodfillReliability.valueOf("UNKNOWN"));
        assertSame(FloodfillReliability.OK, FloodfillReliability.valueOf("OK"));
        assertSame(FloodfillReliability.GOOD, FloodfillReliability.valueOf("GOOD"));
    }
}
