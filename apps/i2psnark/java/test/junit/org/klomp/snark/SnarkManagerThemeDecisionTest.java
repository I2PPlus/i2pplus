package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for SnarkManager.isAvailableTheme, the config-ingress gate that
 * keeps a theme name outside the available set out of the rendered pages.
 */
public class SnarkManagerThemeDecisionTest {

    private static final String[] THEMES = {"classic", "dark", "light", "ubergine"};

    /** Names in the available set pass. */
    @Test
    public void testAvailableAccepted() {
        assertTrue(SnarkManager.isAvailableTheme("dark", THEMES));
        assertTrue(SnarkManager.isAvailableTheme("ubergine", THEMES));
    }

    /** Null, empty, or unknown names are rejected. */
    @Test
    public void testUnknownRejected() {
        assertFalse(SnarkManager.isAvailableTheme(null, THEMES));
        assertFalse(SnarkManager.isAvailableTheme("", THEMES));
        assertFalse(SnarkManager.isAvailableTheme("neon", THEMES));
    }

    /** Breakout attempts for the JS string and class attribute contexts fail. */
    @Test
    public void testInjectionRejected() {
        assertFalse(SnarkManager.isAvailableTheme("dark\";</script>", THEMES));
        assertFalse(SnarkManager.isAvailableTheme("dark new Theme()", THEMES));
        assertFalse(SnarkManager.isAvailableTheme("DARK", THEMES));
    }

    /** An empty available set accepts nothing. */
    @Test
    public void testEmptySetRejects() {
        assertFalse(SnarkManager.isAvailableTheme("dark", new String[0]));
    }
}
