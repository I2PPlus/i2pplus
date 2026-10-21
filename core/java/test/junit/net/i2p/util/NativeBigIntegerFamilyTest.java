package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the mapping from a jbigi optimization name to the processor family
 * shown on the console info page.
 */
public class NativeBigIntegerFamilyTest {

    /**
     * "none" means only that no tuned build matched, and null means the probe
     * never ran. Neither is a processor, so the page shows nothing instead.
     */
    @Test
    public void testUninformativeNamesShowNothing() {
        assertNull(NativeBigInteger.familyFor("none"));
        assertNull(NativeBigInteger.familyFor(null));
    }

    /**
     * Names that already read as a family are passed through unchanged. AMD is
     * not in this list: it is named from the CPU flags, so that a Zen 4 or Zen 5
     * part is not reported as the zen3 library it loaded.
     */
    @Test
    public void testOtherNamesPassThrough() {
        assertEquals("skylake", NativeBigInteger.familyFor("skylake"));
        assertEquals("core2", NativeBigInteger.familyFor("core2"));
    }
}
