package net.i2p.router.transport;

import static org.junit.Assert.assertEquals;

import java.util.Locale;

import org.junit.Test;

/**
 * The registrable domain taken out of a reverse-DNS hostname, and the case the
 * hostname is read in.
 *
 * <p>The name is folded to lower case and then matched against a table of
 * multi-part TLDs written in lower case, so that "shop.shop.co.uk" yields
 * "shop.co.uk" rather than the bare "co.uk". Several entries in that table hold
 * an I - co.il, org.il, k12.il - and a fold that follows the JVM's default
 * locale turns an upper-case I in the name into a dotless ı under a Turkish
 * default locale. The entry then stops matching, and the domain comes back one
 * label short: an Israeli host is filed under "co.il" with the rest of its
 * country, and the GeoIP and ban tables key off that string.
 *
 * @since 0.9.72+
 */
public class CountryLookupDomainCaseTest {

    /** The extraction is a static one over the name; no GeoIP database needed. */
    public CountryLookupDomainCaseTest() {}

    /**
     * A plain three-label name yields its registrable domain, whatever case the
     * resolver wrote it in.
     */
    @Test
    public void testPlainNameYieldsRegistrableDomain() {
        assertEquals("example.com", CountryLookup.getDomain("host.example.com"));
        assertEquals("example.com", CountryLookup.getDomain("WWW.EXAMPLE.COM"));
        assertEquals("example.org", CountryLookup.getDomain("Sub.Host.EXAMPLE.ORG"));
    }

    /**
     * A multi-part TLD is recognised, so the label that owns it stays in the
     * domain instead of being cut off with it.
     */
    @Test
    public void testMultiPartTldKeepsItsOwner() {
        assertEquals("host.co.uk", CountryLookup.getDomain("www.host.co.uk"));
        assertEquals("shop.co.il", CountryLookup.getDomain("shop.co.il"));
        assertEquals("host.net.au", CountryLookup.getDomain("host.net.au"));
    }

    /**
     * The names that need nothing taken off them come back folded and trimmed,
     * and an absent name is the empty string rather than null.
     */
    @Test
    public void testShortNamesAndBlanks() {
        assertEquals("singlelabel", CountryLookup.getDomain("singlelabel"));
        assertEquals("singlelabel", CountryLookup.getDomain("SINGLELABEL"));
        assertEquals("", CountryLookup.getDomain(null));
        assertEquals("", CountryLookup.getDomain(""));
        assertEquals("example.com", CountryLookup.getDomain("  example.com  "));
    }

    /**
     * Under a Turkish default locale the table has to match an upper-case name:
     * "CO.IL" folds to a dotless ı otherwise, the entry is missed, and the
     * domain is cut one label too short.
     */
    @Test
    public void testMultiPartTldWithAnIMatchesUnderTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("www.co.il", CountryLookup.getDomain("WWW.CO.IL"));
            assertEquals("school.k12.il", CountryLookup.getDomain("SCHOOL.K12.IL"));
            assertEquals("host.org.il", CountryLookup.getDomain("HOST.ORG.IL"));
            assertEquals("host.co.uk", CountryLookup.getDomain("WWW.HOST.CO.UK"));
        } finally {
            Locale.setDefault(saved);
        }
    }

    /**
     * A name already in lower case is unaffected by the locale - the fold is
     * only what happens to the upper-case letters, and the table is lower case
     * either way.
     */
    @Test
    public void testLowercaseNameIsTheSameUnderTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("shop.co.il", CountryLookup.getDomain("shop.co.il"));
            assertEquals("example.com", CountryLookup.getDomain("host.example.com"));
        } finally {
            Locale.setDefault(saved);
        }
    }
}
