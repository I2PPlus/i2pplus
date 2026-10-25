package net.i2p.addressbook;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for HostCheckerBridge.escapeForDisplay, the address book page's
 * escape for category names arriving from the remotely downloaded categories
 * feed. The feed is fetched over plaintext HTTP, so its values are untrusted;
 * they are rendered into quoted attributes and as element text in
 * addressbook.jsp, and no payload from the feed may be able to break out.
 *
 * @since 0.9.72+
 */
public class HostCheckerBridgeEscapeTest {

    @Test
    public void testNullSafe() {
        assertNull(HostCheckerBridge.escapeForDisplay(null));
    }

    @Test
    public void testPlainCategoriesUnchanged() {
        // the real feed vocabulary must render exactly as before
        String[] vocab = {"cryptocoin", "fileshare", "i2p", "unknown", "wip"};
        for (String cat : vocab) {
            assertEquals(cat, HostCheckerBridge.escapeForDisplay(cat));
        }
    }

    @Test
    public void testBreakoutCharsEscaped() {
        assertEquals("&quot;", HostCheckerBridge.escapeForDisplay("\""));
        assertEquals("&lt;&gt;", HostCheckerBridge.escapeForDisplay("<>"));
        assertEquals("&amp;lt;", HostCheckerBridge.escapeForDisplay("&lt;"));
        assertEquals("a&#96;b", HostCheckerBridge.escapeForDisplay("a`b"));
    }

    /**
     * A feed payload must not be able to close a quoted attribute or open a
     * tag: after escaping, no bare quote or angle bracket survives anywhere.
     */
    @Test
    public void testPayloadCannotBreakQuotedAttribute() {
        String[] payloads = {
            "\"><img src=x onerror=alert(1)>",
            "\" title=\"x\" onmouseover=\"alert(1)",
            "<b>bold</b>",
            "a\"b<c>d&e`f",
        };
        for (String p : payloads) {
            String out = HostCheckerBridge.escapeForDisplay(p);
            for (char c : "\"<>".toCharArray()) {
                assertFalse("bare " + c + " in [" + out + "]", out.indexOf(c) >= 0);
            }
        }
    }

    /**
     * A spaced category stays a single attribute value once the JSP quotes
     * the class attribute: escaping plus quoting neutralizes the unquoted
     * attribute break that a space alone would cause.
     */
    @Test
    public void testSpacedCategoryStaysOneValue() {
        String out = HostCheckerBridge.escapeForDisplay("x onerror=alert(1)");
        assertTrue(out.contains(" "));
        for (char c : "\"<>".toCharArray()) {
            assertFalse("bare " + c + " in [" + out + "]", out.indexOf(c) >= 0);
        }
    }

    /**
     * The page's render chain: escape the feed category for display, and
     * escape the description only when it is a translated string rather than
     * the echoed-back category, so nothing is escaped twice. The result must
     * be inert markup, and known vocabulary must round-trip untouched.
     */
    @Test
    public void testRenderChainInertAndStable() {
        String raw = "evil\" onerror=alert(1)";
        String cat = HostCheckerBridge.escapeForDisplay(raw);
        // getCategoryDescription default branch echoes its argument back (no
        // checker instance in a unit test, the bridge returns the input); the
        // JSP then keeps the single-escaped category as the tooltip
        String echoed = HostCheckerBridge.getCategoryDescription(raw);
        String tooltip = echoed.equals(raw) ? cat : HostCheckerBridge.escapeForDisplay(echoed);
        for (char c : "\"<>".toCharArray()) {
            assertFalse("bare " + c + " in [" + tooltip + "]", tooltip.indexOf(c) >= 0);
        }
        // exactly one level of escaping: the inserted &quot; stays an entity,
        // its ampersand is not re-encoded again
        assertTrue(tooltip.contains("&quot;"));
        assertFalse(tooltip.contains("&amp;quot;"));
        // vocabulary round-trips untouched through the same chain
        assertEquals("fileshare", HostCheckerBridge.escapeForDisplay(HostCheckerBridge.escapeForDisplay("fileshare")));
    }
}
