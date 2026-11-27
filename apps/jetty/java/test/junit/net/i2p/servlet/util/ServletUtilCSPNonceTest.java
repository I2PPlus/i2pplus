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
 * <p>The other half is that the nonce reaches the page as an attribute, and a
 * Base64 value carries a slash - which ends an unquoted attribute value. A page
 * that writes it unquoted names a fragment of the nonce the header holds whole,
 * and CSP blocks every inline script on it, so the tests here also pin that the
 * tag helper quotes it.
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
     * The nonce is Base64, so it carries a slash or a plus in about one draw in
     * three - the characters that end an unquoted attribute value. That is why
     * the tag helper quotes it, and it is what the two tests below pin.
     */
    @Test
    public void testTheAlphabetCarriesCharactersThatEndAnAttribute() {
        boolean found = false;
        for (int i = 0; i < SAMPLES; i++) {
            String nonce = ServletUtil.getCSPNonce();
            if (nonce.indexOf('/') >= 0 || nonce.indexOf('+') >= 0) {found = true;}
        }
        assertTrue("no draw in " + SAMPLES + " carried a slash or a plus, " +
                   "so the alphabet changed and the quoting needs rethinking", found);
    }

    /**
     * A slash in the nonce has to survive the tag whole. Written unquoted, the
     * attribute ends at the first slash: the browser holds the first part of the
     * nonce while the header names all 24 characters, and CSP blocks every inline
     * script on the page - the page looks normal with its sorting, toggling and
     * theme scripts dead.
     */
    @Test
    public void testASlashInANonceSurvivesTheTag() {
        String nonce = "kKxB7BTpKA/eYGQgwm+uu00V";
        String tag = ServletUtil.scriptOpen(nonce, "");
        assertEquals("<script nonce=\"" + nonce + "\">", tag);
    }

    /**
     * Every draw is held inside the quotes the helper opens, and only a quote or
     * an angle bracket could break out of them - the Base64 alphabet carries
     * neither, and the helper is what keeps the tag well formed whatever it is.
     */
    @Test
    public void testEveryDrawStaysInsideTheQuotes() {
        for (int i = 0; i < SAMPLES; i++) {
            String nonce = ServletUtil.getCSPNonce();
            for (int j = 0; j < nonce.length(); j++) {
                char c = nonce.charAt(j);
                boolean ok = c != '"' && c != '<' && c != '>' && c != ' ' && c != '\t' &&
                             c != '\r' && c != '\n';
                assertTrue("nonce " + nonce + " carries " + c +
                           ", which would break the tag", ok);
            }
            String tag = ServletUtil.scriptOpen(nonce, "");
            assertEquals("<script nonce=\"" + nonce + "\">", tag);
        }
    }

    /**
     * Extra attributes of the tag go after the quoted nonce, not inside the
     * value: the module scripts on the console pages are written this way.
     */
    @Test
    public void testExtraAttributesFollowTheQuotedNonce() {
        assertEquals("<script nonce=\"abc\" type=module>",
                     ServletUtil.scriptOpen("abc", "type=module"));
        assertEquals("<script nonce=\"a/b+c\">", ServletUtil.scriptOpen("a/b+c", ""));
    }
}
