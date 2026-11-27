package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

/**
 * The three decisions the ban table makes over ban causes and GeoIP country
 * codes, all of which compare ASCII keywords against text a peer or a lookup
 * wrote.
 *
 * <p>A cause is a human-readable string, and in two of these three cases the
 * keyword being looked for is fixed ASCII: the hash-pattern bans the router
 * table leaves out, the floodfill bans it marks with a class, and the two-letter
 * country code that names a flag image. Folding the text with the JVM's default
 * locale makes each of those comparisons depend on the machine: under a Turkish
 * default locale an upper-case I folds to a dotless ı, so Israel's "IL" stops
 * naming a flag, and a cause that says "Hash" stops being recognised as one.
 *
 * @since 0.9.72+
 */
public class BanlistRendererCauseTest {

    /** Runs the cause decisions; the renderer holds no state of its own here. */
    public BanlistRendererCauseTest() {}

    /**
     * A hash ban is left out of the router table: the Blocklist bans addresses
     * by hash and writes "Hash: &lt;address&gt;" into the cause, translated, so
     * the keyword is the only part of it that stays in English.
     */
    @Test
    public void testHashBanIsLeftOutOfTheRouterTable() {
        assertTrue(BanlistRenderer.isHashBan("Hash: 1.2.3.4:5678"));
        assertTrue(BanlistRenderer.isHashBan("hash mismatch in the store job"));
    }

    /**
     * The detector's own cause holds "hash" as well and is the one that stays
     * in, because a router banned by the pattern detector is still a router.
     */
    @Test
    public void testHashPatternDetectorCauseStaysIn() {
        assertFalse(BanlistRenderer.isHashBan("HashPatternDetector: pattern 7 matched"));
        assertFalse(BanlistRenderer.isHashBan("hashpatterndetector said so"));
    }

    /**
     * A cause with no "hash" in it is an ordinary ban, and a null cause never
     * reaches these decisions - the caller skips those entries first.
     */
    @Test
    public void testOtherCausesAreNotHashBans() {
        assertFalse(BanlistRenderer.isHashBan("Blocklist (1.2.3.4:5678)"));
        assertFalse(BanlistRenderer.isHashBan("Sybil Analysis (2gq4...)"));
        assertFalse(BanlistRenderer.isHashBan("Floodfill verify failed"));
    }

    /**
     * A floodfill ban is one whose cause says floodfill, or whose router's
     * capabilities carry the floodfill flag. That flag is the lower-case
     * <code>f</code>; the upper-case <code>F</code> is a different capability
     * and does not make a peer a floodfill.
     */
    @Test
    public void testFloodfillBanFromCauseOrCaps() {
        assertTrue(BanlistRenderer.isFloodfillBan("Floodfill verify failed", null));
        assertTrue(BanlistRenderer.isFloodfillBan("banned for a floodfill probe", "PRT"));
        assertTrue(BanlistRenderer.isFloodfillBan("Bad packet", "PRf"));
    }

    /**
     * Neither half is enough on its own: a cause that does not mention a
     * floodfill and a peer without the flag is not a floodfill ban, and unknown
     * capabilities are not a floodfill either.
     */
    @Test
    public void testOrdinaryBanIsNotAFloodfill() {
        assertFalse(BanlistRenderer.isFloodfillBan("Bad packet", "PRT"));
        assertFalse(BanlistRenderer.isFloodfillBan("Bad packet", "PRF"));
        assertFalse(BanlistRenderer.isFloodfillBan("Bad packet", null));
        assertFalse(BanlistRenderer.isFloodfillBan("Blocklist (1.2.3.4:5678)", ""));
    }

    /**
     * A GeoIP country code is lower-cased for the flag image and the table's
     * sort key, and a lookup that knows nothing is "xx" - the same value the
     * renderer falls back to, so the flag and the sort order agree.
     */
    @Test
    public void testCountryCodeIsLowercaseOrUnknown() {
        assertEquals("us", BanlistRenderer.normalizeCountryCode("US"));
        assertEquals("il", BanlistRenderer.normalizeCountryCode("IL"));
        assertEquals("xx", BanlistRenderer.normalizeCountryCode("xx"));
        assertEquals("xx", BanlistRenderer.normalizeCountryCode("XX"));
        assertEquals("xx", BanlistRenderer.normalizeCountryCode(""));
        assertEquals("xx", BanlistRenderer.normalizeCountryCode(null));
    }

    /**
     * Under a Turkish default locale "IL".toLowerCase() is a dotless ı, which
     * names no flag image and no country name - the ban table would show an
     * empty flag cell for every Israeli peer.
     */
    @Test
    public void testCountryCodeUnderTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("il", BanlistRenderer.normalizeCountryCode("IL"));
            assertEquals("us", BanlistRenderer.normalizeCountryCode("US"));
            assertEquals("xx", BanlistRenderer.normalizeCountryCode(null));
        } finally {
            Locale.setDefault(saved);
        }
    }

    /**
     * The cause keywords are ASCII, so the two cause decisions have to read the
     * same under a Turkish default locale as they do here.
     */
    @Test
    public void testCauseDecisionsUnderTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertTrue(BanlistRenderer.isHashBan("Hash: 1.2.3.4:5678"));
            assertFalse(BanlistRenderer.isHashBan("HashPatternDetector: pattern 7 matched"));
            assertTrue(BanlistRenderer.isFloodfillBan("Floodfill verify failed", null));
            assertFalse(BanlistRenderer.isFloodfillBan("Bad packet", "PRT"));
        } finally {
            Locale.setDefault(saved);
        }
    }
}
