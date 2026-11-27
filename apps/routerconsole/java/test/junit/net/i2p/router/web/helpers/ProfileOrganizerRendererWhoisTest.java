package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;

import java.util.Locale;

import org.junit.Test;

/**
 * The whois answer the peers table shortens into a cell.
 *
 * <p>The shortening is a table of lower-case ASCII phrases: the registry names
 * an operator recognises become their acronyms, and the corporate suffixes come
 * off. The answer comes back from the lookup in whatever case its owner wrote it
 * in, so it is folded first - and a fold that follows the JVM's default locale
 * makes the table depend on the machine. Under a Turkish default locale an
 * upper-case I folds to a dotless ı, "Inc" and "LLC" stop matching their
 * entries, and the cell keeps the long form it was meant to shorten.
 *
 * @since 0.9.72+
 */
public class ProfileOrganizerRendererWhoisTest {

    /** The shortening is a static decision over the lookup's answer. */
    public ProfileOrganizerRendererWhoisTest() {}

    /**
     * Shortens one answer and checks the text the cell would show, naming the
     * answer in the failure so a long registry string is readable there.
     *
     * @param expected the text the cell should hold
     * @param whois the answer as the lookup returned it
     */
    private static void check(String expected, String whois) {
        assertEquals(whois, expected, ProfileOrganizerRenderer.shortWhois(whois));
    }

    /**
     * The registries the operator knows are shown by acronym, in whatever case
     * the whois answer wrote them in.
     */
    @Test
    public void testRegistriesBecomeAcronyms() {
        check("apnic", "Asia Pacific Network Information Centre");
        check("lacnic", "Latin American and Caribbean IP address Regional Registry");
        check("mediacom", "Mediacom Communications Corp");
    }

    /**
     * The parenthesised blocks a whois answer carries are cut, and the
     * corporate suffixes come off what is left.
     */
    @Test
    public void testParenthesesAndSuffixesAreCut() {
        check("example hosting", "Example Hosting, Inc");
        check("acme", "Acme (referral notes above) LLC");
        check("widgets", "Administered by Widgets");
    }

    /**
     * An answer that needs nothing done to it comes back trimmed and folded, so
     * the cell never carries leading space from the lookup's formatting.
     */
    @Test
    public void testPlainAnswerIsFoldedAndTrimmed() {
        check("ripe ncc", "  RIPE NCC  ");
        check("", "");
    }

    /**
     * Under a Turkish default locale the suffix table still has to match:
     * "INC" folds to a dotless ı otherwise, and the cell keeps the suffix the
     * shortening exists to remove.
     */
    @Test
    public void testTableStillMatchesUnderTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            check("example hosting", "EXAMPLE HOSTING, INC");
            check("acme", "ACME LLC");
            check("apnic", "Asia Pacific Network Information Centre");
            check("widgets", "ADMINISTERED BY WIDGETS");
        } finally {
            Locale.setDefault(saved);
        }
    }
}
