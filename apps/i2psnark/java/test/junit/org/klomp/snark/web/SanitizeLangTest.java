package org.klomp.snark.web;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for I2PSnarkServlet.sanitizeLang, the gate that keeps the lang
 * request parameter inside the two-letter (plus optional country) shape it
 * is written into CSS class names and query strings unescaped.
 *
 * @since 0.9.72+
 */
public class SanitizeLangTest {

    @Test
    public void testNullStaysNull() {
        assertNull(I2PSnarkServlet.sanitizeLang(null));
    }

    @Test
    public void testPlainCodesAccepted() {
        assertEquals("en", I2PSnarkServlet.sanitizeLang("en"));
        assertEquals("zh", I2PSnarkServlet.sanitizeLang("zh"));
        // house selector format: ISO code plus underscore country
        assertEquals("pt_BR", I2PSnarkServlet.sanitizeLang("pt_BR"));
        assertEquals("sr_RS", I2PSnarkServlet.sanitizeLang("sr_RS"));
        // hyphen form tolerated as well
        assertEquals("pt-BR", I2PSnarkServlet.sanitizeLang("pt-BR"));
    }

    @Test
    public void testThreeLetterCodesAccepted() {
        // ISO 639-2 three-letter codes are supported by the selectors
        assertEquals("yue", I2PSnarkServlet.sanitizeLang("yue"));
        assertEquals("fil_PH", I2PSnarkServlet.sanitizeLang("fil_PH"));
    }

    @Test
    public void testMalformedDropped() {
        // empty, wrong length, wrong case in the primary subtag
        assertNull(I2PSnarkServlet.sanitizeLang(""));
        assertNull(I2PSnarkServlet.sanitizeLang("e"));
        assertNull(I2PSnarkServlet.sanitizeLang("engg"));
        assertNull(I2PSnarkServlet.sanitizeLang("EN"));
        // injection attempts and stray punctuation
        assertNull(I2PSnarkServlet.sanitizeLang("en\"><script>"));
        assertNull(I2PSnarkServlet.sanitizeLang("en-"));
        assertNull(I2PSnarkServlet.sanitizeLang("en-BRAZIL"));
        assertNull(I2PSnarkServlet.sanitizeLang(" lang"));
    }
}
