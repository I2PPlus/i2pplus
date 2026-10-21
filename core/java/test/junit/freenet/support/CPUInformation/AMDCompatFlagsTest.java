package freenet.support.CPUInformation;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.util.NativeBigInteger;

/**
 * Tests the AMD family and model table that sets the instruction-set
 * compatibility flags. jbigi library selection reads those flags, so a family
 * that is not in the table silently costs the tuned library.
 */
public class AMDCompatFlagsTest {

    static {
        // Touch NativeBigInteger before probing anything: initializing it runs
        // its own CPUID probe, which rewrites these static flags for the real
        // CPU and would otherwise land in the middle of a probe assertion.
        NativeBigInteger.cpuType();
    }

    /**
     * Family 1Ah model 44h, a Ryzen 9000 (Zen 5). Every generation at or below
     * it must be flagged, or jbigi falls back to the generic build.
     */
    @Test
    public void testZen5SetsWholeChain() {
        AMDInfoImpl cpu = probe(15, 4, 11, 4);
        assertTrue(cpu.IsZenCompatible());
        assertTrue(cpu.IsZen2Compatible());
        assertTrue(cpu.IsZen3Compatible());
        assertTrue(cpu.IsZen4Compatible());
        assertTrue(cpu.IsZen5Compatible());
    }

    /** Family 19h model 60h, a Ryzen 7000 (Raphael, Zen 4). */
    @Test
    public void testZen4StopsBelowZen5() {
        AMDInfoImpl cpu = probe(15, 0, 10, 6);
        assertTrue(cpu.IsZenCompatible());
        assertTrue(cpu.IsZen3Compatible());
        assertTrue(cpu.IsZen4Compatible());
        assertFalse(cpu.IsZen5Compatible());
    }

    /** Family 19h model 01h, an Epyc 7003 (Milan, Zen 3). */
    @Test
    public void testZen3StopsBelowZen4() {
        AMDInfoImpl cpu = probe(15, 1, 10, 0);
        assertTrue(cpu.IsZen3Compatible());
        assertFalse(cpu.IsZen4Compatible());
        assertFalse(cpu.IsZen5Compatible());
    }

    /** Family 17h model 31h, a Ryzen 2000 (Zen+). */
    @Test
    public void testZen2StopsBelowZen3() {
        AMDInfoImpl cpu = probe(15, 1, 8, 3);
        assertTrue(cpu.IsZenCompatible());
        assertTrue(cpu.IsZen2Compatible());
        assertFalse(cpu.IsZen3Compatible());
    }

    /**
     * The flags are static, so probing a second CPU must not inherit the first
     * result. The router probes twice per run: once for jbigi, once for the
     * processor-change check.
     */
    @Test
    public void testProbeDoesNotInheritPreviousResult() {
        probe(15, 4, 11, 4);
        AMDInfoImpl cpu = probe(15, 1, 8, 3);
        assertFalse(cpu.IsZen5Compatible());
        assertFalse(cpu.IsZen4Compatible());
        assertFalse(cpu.IsZen3Compatible());
        assertTrue(cpu.IsZen2Compatible());
    }

    /** An unrecognised family names nothing and flags nothing. */
    @Test
    public void testUnknownFamily() {
        assertNull(new AMDInfoImpl().identifyCPUByModel(99, 0, 0, 0));
        assertFalse(new AMDInfoImpl().IsZenCompatible());
    }

    /**
     * The console reports the generation the chip belongs to, not the jbigi
     * build it loaded. The tuned build stops at zen3, so the library name
     * cannot tell a Zen 3 part from a Zen 5 one.
     */
    @Test
    public void testFamilyNamesReportedGeneration() {
        assertEquals("Zen5", NativeBigInteger.amdFamily(probe(15, 4, 11, 4)));
        assertEquals("Zen4", NativeBigInteger.amdFamily(probe(15, 0, 10, 6)));
        assertEquals("Zen3", NativeBigInteger.amdFamily(probe(15, 1, 10, 0)));
        assertEquals("Zen2", NativeBigInteger.amdFamily(probe(15, 1, 8, 3)));
        assertNull(NativeBigInteger.amdFamily(probe(15, 1, 5, 0)));
    }

    private static AMDInfoImpl probe(int family, int model, int extFamily, int extModel) {
        AMDInfoImpl cpu = new AMDInfoImpl();
        cpu.identifyCPUByModel(family, model, extFamily, extModel);
        return cpu;
    }
}
