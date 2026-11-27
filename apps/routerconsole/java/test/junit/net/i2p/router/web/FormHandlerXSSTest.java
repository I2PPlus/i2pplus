package net.i2p.router.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

/**
 * The XSS patterns a form value is checked against, and the case they are
 * written in.
 *
 * <p>The patterns are ASCII - a tag name, a scheme, an attribute name - and a
 * value is matched against them after a fold to lower case. A fold that follows
 * the JVM's default locale makes the check depend on the machine: under a
 * Turkish default locale an upper-case I folds to a dotless ı, so an upper-case
 * <code>&lt;IFRAME&gt;</code> or <code>JAVASCRIPT:</code> in a form value stops
 * matching its own pattern and the value is taken at face value.
 *
 * @since 0.9.72+
 */
public class FormHandlerXSSTest {

    /** The check is a static one over the value; no form or session needed. */
    public FormHandlerXSSTest() {}

    /**
     * A value in any case is caught, because the wire does not care about the
     * case of a tag or a scheme.
     */
    @Test
    public void testPatternsAreCaughtInAnyCase() {
        assertTrue(FormHandler.containsXSS("<script src=x>"));
        assertTrue(FormHandler.containsXSS("<SCRIPT SRC=X>"));
        assertTrue(FormHandler.containsXSS("<IFRAME SRC=x>"));
        assertTrue(FormHandler.containsXSS("javascript:alert(1)"));
        assertTrue(FormHandler.containsXSS("JAVASCRIPT:ALERT(1)"));
        assertTrue(FormHandler.containsXSS("x ONERROR=boom"));
        assertTrue(FormHandler.containsXSS("vbscript:msgbox"));
    }

    /**
     * Ordinary text a form carries is not XSS, and an absent value is not
     * either - the check runs on every form field, so a false positive here is
     * a field the operator cannot save.
     */
    @Test
    public void testOrdinaryValuesAreNotXSS() {
        assertFalse(FormHandler.containsXSS("127.0.0.1:7657"));
        assertFalse(FormHandler.containsXSS("My Tunnel"));
        assertFalse(FormHandler.containsXSS("http://example.i2p/?q=evaluation"));
        assertFalse(FormHandler.containsXSS(null));
        assertFalse(FormHandler.containsXSS(""));
    }

    /**
     * Under a Turkish default locale the fold is what decides it: "IFRAME" and
     * "JAVASCRIPT" both carry an upper-case I, and both fold to a dotless ı, so
     * the patterns stop matching and the value is accepted.
     */
    @Test
    public void testPatternsAreCaughtUnderTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertTrue(FormHandler.containsXSS("<IFRAME SRC=x>"));
            assertTrue(FormHandler.containsXSS("JAVASCRIPT:ALERT(1)"));
            assertTrue(FormHandler.containsXSS("<SCRIPT SRC=X>"));
            assertTrue(FormHandler.containsXSS("x ONLOAD=boom"));
            assertFalse(FormHandler.containsXSS("My Tunnel"));
        } finally {
            Locale.setDefault(saved);
        }
    }
}
