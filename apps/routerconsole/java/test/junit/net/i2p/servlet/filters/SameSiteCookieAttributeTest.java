package net.i2p.servlet.filters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

/**
 * The SameSite attribute this filter adds to a Set-Cookie header, and the
 * detection of one that is already there.
 *
 * <p>The filter exists to give every cookie the console and its webapps set a
 * SameSite default, and it must not add a second one to a cookie that already
 * chose its own. A browser keeps the last SameSite in a cookie, so a duplicate
 * is not cosmetic: the webapp's own choice is replaced by this filter's. The
 * detection is a case-insensitive text search, and that fold has to name a
 * locale - a cookie attribute name is case-insensitive on the wire, so a
 * servlet may write it in caps, and "SAMESITE" folds under a Turkish default
 * locale to a dotless ı. The attribute then goes unseen and the filter appends
 * its own after it.
 *
 * @since 0.9.72+
 */
public class SameSiteCookieAttributeTest {

    /** The decision is a static one over the header value. */
    public SameSiteCookieAttributeTest() {}

    /**
     * A cookie with no SameSite attribute gets the default, appended in the
     * form a cookie parser reads.
     */
    @Test
    public void testDefaultIsAppendedWhenAbsent() {
        String in = "I2PSESSIONID=abc123; Path=/; HttpOnly";
        String out = SameSiteCookieFilter.addSameSite(in);
        assertEquals(in + "; SameSite=Lax", out);
        assertTrue(out, out.endsWith("; SameSite=Lax"));
    }

    /**
     * A cookie that chose its own attribute is left exactly as it was written,
     * in whatever case the servlet wrote it in.
     */
    @Test
    public void testExistingAttributeIsLeftAlone() {
        String lax = "I2PAUTH=xyz; Path=/; SameSite=Lax";
        assertEquals(lax, SameSiteCookieFilter.addSameSite(lax));
        String strict = "I2PAUTH=xyz; Path=/; samesite=strict";
        assertEquals(strict, SameSiteCookieFilter.addSameSite(strict));
        String none = "csrf=1; Secure; SameSite=None";
        assertEquals(none, SameSiteCookieFilter.addSameSite(none));
        // A cookie attribute is case-insensitive on the wire, so a servlet is
        // free to write the name in caps.
        String caps = "csrf=1; Secure; SAMESITE=None";
        assertEquals(caps, SameSiteCookieFilter.addSameSite(caps));
    }

    /**
     * Under a Turkish default locale the fold is what decides it: "SameSite"
     * becomes a dotless ı, the attribute is not found, and the filter writes a
     * second one - and the browser then uses this filter's Lax instead of the
     * webapp's None.
     */
    @Test
    public void testExistingAttributeIsFoundUnderTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            String none = "csrf=1; Secure; SameSite=None";
            assertEquals(none, SameSiteCookieFilter.addSameSite(none));
            String caps = "csrf=1; Secure; SAMESITE=None";
            assertEquals(caps, SameSiteCookieFilter.addSameSite(caps));
            String strict = "I2PAUTH=xyz; Path=/; SameSite=Strict";
            assertEquals(strict, SameSiteCookieFilter.addSameSite(strict));
            String plain = "I2PSESSIONID=abc123; Path=/; HttpOnly";
            assertEquals(plain + "; SameSite=Lax", SameSiteCookieFilter.addSameSite(plain));
        } finally {
            Locale.setDefault(saved);
        }
    }

    /**
     * The attribute name is matched, not a value that happens to contain the
     * word, so a cookie whose value mentions it is still given the default.
     */
    @Test
    public void testAttributeNameIsWhatIsMatched() {
        String in = "note=samesite-is-good; Path=/";
        assertEquals(in, SameSiteCookieFilter.addSameSite(in));
    }
}
