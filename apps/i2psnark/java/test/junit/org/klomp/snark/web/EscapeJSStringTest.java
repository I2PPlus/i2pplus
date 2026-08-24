package org.klomp.snark.web;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for I2PSnarkServlet.escapeJSString().
 *
 * The escaper feeds torrent names into HTML attributes (data-name, client)
 * that page scripts read back into JavaScript string literals. Torrent names
 * come from untrusted .torrent files and may contain quotes, angle brackets,
 * ampersands and line breaks, so every such character must survive the
 * round trip as backslash-hex escapes: no markup may leak into the attribute
 * and no literal control character may terminate the JS string.
 *
 * @since 0.9.71+
 */
public class EscapeJSStringTest {

    @Test
    public void testBackslashEscapedFirstSoSequencesSurvive() {
        assertEquals("\\\\", I2PSnarkServlet.escapeJSString("\\"));
        // an existing escape sequence in user input must not turn into a real char
        assertEquals("\\\\" + "\\x22", I2PSnarkServlet.escapeJSString("\\\""));
    }

    @Test
    public void testLineBreaksAreEscaped() {
        // regression: a raw newline inside a JS string literal is a syntax error
        assertEquals("a\\nb", I2PSnarkServlet.escapeJSString("a\nb"));
        assertEquals("a\\rb", I2PSnarkServlet.escapeJSString("a\rb"));
        assertEquals("a\\r\\nb", I2PSnarkServlet.escapeJSString("a\r\nb"));
        assertFalse(I2PSnarkServlet.escapeJSString("x\ny\rz").matches(".*[\n\r].*"));
    }

    @Test
    public void testQuotesAndAnglesNeutralized() {
        assertEquals("\\x22", I2PSnarkServlet.escapeJSString("\""));
        assertEquals("\\x27", I2PSnarkServlet.escapeJSString("'"));
        assertEquals("\\x3c\\x3e", I2PSnarkServlet.escapeJSString("<>"));
        assertEquals("a\\x26b", I2PSnarkServlet.escapeJSString("a&b"));
        // attribute-context safety: cannot close the quoting context
        String out = I2PSnarkServlet.escapeJSString("\"><script>alert(1)</script>");
        assertFalse(out.contains("\""));
        assertFalse(out.contains("<"));
        assertTrue(out.startsWith("\\x22\\x3e"));
    }

    @Test
    public void testPlainTextPassesThrough() {
        assertEquals("Debian 12 netinst [2024]", I2PSnarkServlet.escapeJSString("Debian 12 netinst [2024]"));
        assertEquals("", I2PSnarkServlet.escapeJSString(""));
    }
}
