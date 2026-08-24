package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the pure capability ban pattern matcher in
 * Banlist.matchCapabilityPattern().
 *
 * Pattern syntax: comma/space separated list of patterns; each pattern is a
 * set of required capability characters, optionally followed by '!' and a set
 * of excluded characters ("G!f" = require G unless floodfill). Matching is
 * case-sensitive because capability letters are case-significant
 * (lowercase 'f' is floodfill).
 *
 * @since 0.9.70+
 */
public class BanlistCapabilityPatternTest {

    @Test
    public void testExclusionHonored() {
        // G-capable floodfill is spared by G!f
        assertNull(Banlist.matchCapabilityPattern("XfRG", "G!f"));
        assertNull(Banlist.matchCapabilityPattern("fG", "G!f"));
    }

    @Test
    public void testRequiredWithoutExcluded() {
        assertEquals("G!f", Banlist.matchCapabilityPattern("XRG", "G!f"));
        assertEquals("G!f", Banlist.matchCapabilityPattern("GRUH", "G!f"));
    }

    @Test
    public void testRequiredMissing() {
        assertNull(Banlist.matchCapabilityPattern("XfR", "G!f"));
        assertNull(Banlist.matchCapabilityPattern("LMNOPX", "G!f"));
    }

    @Test
    public void testConjunctionPattern() {
        // Lf = require both L and f (L-tier floodfills)
        assertEquals("Lf", Banlist.matchCapabilityPattern("XLf", "Lf"));
        assertNull(Banlist.matchCapabilityPattern("XRf", "Lf"));
        assertNull(Banlist.matchCapabilityPattern("XLR", "Lf"));
    }

    @Test
    public void testCommaSeparatedCombination() {
        String patterns = "G!f,Lf";
        assertEquals("G!f", Banlist.matchCapabilityPattern("XRG", patterns));
        assertEquals("Lf", Banlist.matchCapabilityPattern("XLf", patterns));
        assertNull(Banlist.matchCapabilityPattern("XfRG", patterns));
        assertNull(Banlist.matchCapabilityPattern("XLR", patterns));
    }

    @Test
    public void testSpaceSeparatedCombination() {
        String patterns = "G!f Lf";
        assertEquals("G!f", Banlist.matchCapabilityPattern("XRG", patterns));
        assertEquals("Lf", Banlist.matchCapabilityPattern("XLf", patterns));
    }

    @Test
    public void testCaseSensitive() {
        // Uppercase 'F' is not the floodfill flag; only literal lowercase 'f'
        // satisfies the exclusion. This pins the no-case-folding contract.
        assertEquals("G!F", Banlist.matchCapabilityPattern("Gf", "G!F"));
        // A router with literal uppercase 'F' in its caps is NOT spared by
        // the lowercase-f exclusion - it has G and is not a floodfill.
        assertEquals("G!f", Banlist.matchCapabilityPattern("GF", "G!f"));
    }

    @Test
    public void testBareExclusionNeverMatches() {
        // A pattern with an empty required part can never match, so a bare
        // exclusion cannot ban the entire network.
        assertNull(Banlist.matchCapabilityPattern("f", "!f"));
        assertNull(Banlist.matchCapabilityPattern("XfRG", "!f"));
    }

    @Test
    public void testFirstMatchWins() {
        assertEquals("G", Banlist.matchCapabilityPattern("XfRG", "G,G!f"));
    }

    @Test
    public void testNullAndEmptyInputs() {
        assertNull(Banlist.matchCapabilityPattern(null, "G!f"));
        assertNull(Banlist.matchCapabilityPattern("", "G!f"));
        assertNull(Banlist.matchCapabilityPattern("XRG", null));
        assertNull(Banlist.matchCapabilityPattern("XRG", ""));
        assertNull(Banlist.matchCapabilityPattern(null, null));
    }

    @Test
    public void testWhitespaceTolerance() {
        assertEquals("G!f", Banlist.matchCapabilityPattern("XRG", "  G!f  ,  Lf "));
        assertEquals("Lf", Banlist.matchCapabilityPattern("XLf", "  G!f  ,  Lf "));
    }
}
