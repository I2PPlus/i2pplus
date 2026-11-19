package net.i2p.servlet.util;

import static org.junit.Assert.*;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Pins what a CSP script nonce has to be: wide enough to guess, and different
 * every time it is asked for.
 *
 * <p>A script-src 'nonce-…' policy is only as good as the nonce being unknown
 * to whoever can get markup into the page. A 32-bit value is searchable - a
 * page can carry a thousand candidate inline scripts, and each request is another
 * thousand tries - and a value computed once per servlet or per JVM is printed on
 * every page the operator ever loads, so it is not a secret at all. That is what
 * the two servlets used to do, and it is what the width and the distinctness
 * below are here to keep from coming back.
 *
 * @since 0.9.72+
 */
public class ServletUtilCSPNonceTest {

    /** Random bytes behind one nonce, as the helper documents them. */
    private static final int NONCE_BYTES = 18;

    /** Samples to draw: enough that a short cycle or a fixed value cannot hide. */
    private static final int SAMPLES = 512;

    /**
     * A fixture group for the CSP nonce helper; it holds no state.
     *
     * @since 0.9.72+
     */
    public ServletUtilCSPNonceTest() {}

    /**
     * Every nonce is the documented width: 24 Base64 characters decoding to 18
     * random bytes, well past the 128 bits a CSP nonce is meant to carry.
     */
    @Test
    public void testTheNonceCarriesItsWidth() {
        String nonce = ServletUtil.getCSPNonce();
        assertEquals("a Base64 nonce of " + NONCE_BYTES + " bytes is 24 characters",
                     24, nonce.length());
        assertEquals("the nonce did not decode to its documented width",
                     NONCE_BYTES, Base64.getDecoder().decode(nonce).length);
    }

    /**
     * The defect: 512 draws must all be different. A value cached on the servlet
     * or in a static field repeats here, and a 32-bit value repeats within 512
     * draws often enough to show up.
     */
    @Test
    public void testEveryDrawIsDifferent() {
        Set<String> seen = new HashSet<>(SAMPLES * 2);
        for (int i = 0; i < SAMPLES; i++) {
            String nonce = ServletUtil.getCSPNonce();
            assertTrue("draw " + i + " repeated an earlier nonce", seen.add(nonce));
        }
        assertEquals(SAMPLES, seen.size());
    }

    /**
     * Two draws in a row differ: the nonce is per response, not per request
     * handler, and nothing in the helper may hold one over.
     */
    @Test
    public void testTwoDrawsInARowDiffer() {
        assertNotEquals(ServletUtil.getCSPNonce(), ServletUtil.getCSPNonce());
    }

    /**
     * The console writes the nonce into an unquoted attribute on several pages,
     * so the encoding may not produce a character that ends an attribute value:
     * no padding, no quote, no angle bracket, no backtick, no whitespace.
     */
    @Test
    public void testTheNonceStaysInsideAnUnquotedAttribute() {
        for (int i = 0; i < SAMPLES; i++) {
            String nonce = ServletUtil.getCSPNonce();
            for (int j = 0; j < nonce.length(); j++) {
                char c = nonce.charAt(j);
                boolean ok = c != '=' && c != '"' && c != '\'' && c != '<' && c != '>' &&
                             c != '`' && c != ' ' && c != '\t' && c != '\r' && c != '\n';
                assertTrue("nonce " + nonce + " carries " + c +
                           ", which ends an unquoted attribute", ok);
            }
        }
    }
}
