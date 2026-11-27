package net.i2p.i2ptunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

/**
 * Tests that the extension table is matched by the extension, not by the
 * extension as the JVM's default locale happens to fold it.
 *
 * <p>The table is lowercase ASCII, so a URL is folded to lower case before it is
 * compared. Under a Turkish default locale that fold turns an upper-case I into
 * a dotless ı, and an extension that contains an I stops matching its own entry:
 * a scan served as <code>SCAN.TIF</code> by an eepsite was reported as
 * <code>application/octet-stream</code>, which costs the browser its inline
 * preview and the caching rule its immutable entry. The fold has to name a
 * locale, and the house locale for machine text is {@link Locale#US}.
 *
 * @since 0.9.72+
 */
public class MimeTypeDetectorCaseTest {

    /**
     * The table is lowercase and a plain lowercase URL matches it. This is the
     * case that worked before the fold named a locale and is pinned so the fix
     * cannot break it.
     */
    @Test
    public void testLowercaseExtensionMatches() {
        assertEquals("text/css", MimeTypeDetector.detectMimeType("/styles/site.css"));
        assertEquals("image/jpeg", MimeTypeDetector.detectMimeType("/img/photo.jpg"));
    }

    /**
     * An upper-case extension matches too - the URL is folded first - for the
     * extensions with no I in them, which every locale folds the same way.
     */
    @Test
    public void testUppercaseExtensionMatches() {
        assertEquals("text/css", MimeTypeDetector.detectMimeType("/styles/SITE.CSS"));
        assertEquals("text/html", MimeTypeDetector.detectMimeType("/INDEX.HTML"));
        assertEquals("image/jpeg", MimeTypeDetector.detectMimeType("/PHOTO.JPG"));
    }

    /**
     * The extensions that carry an I are the ones at risk, so they are pinned on
     * their own, in the default locale this build runs in.
     */
    @Test
    public void testExtensionWithAnUpperIMatches() {
        assertEquals("image/tiff", MimeTypeDetector.detectMimeType("/scan.TIF"));
        assertEquals("image/tiff", MimeTypeDetector.detectMimeType("/SCAN.TIFF"));
        assertEquals("image/avif", MimeTypeDetector.detectMimeType("/picture.AVIF"));
    }

    /**
     * A query string is cut before the extension is compared, upper case and all.
     */
    @Test
    public void testQueryStringIsCutBeforeTheExtension() {
        assertEquals("image/jpeg", MimeTypeDetector.detectMimeType("/PHOTO.JPG?w=100&h=50"));
        assertEquals("image/tiff", MimeTypeDetector.detectMimeType("/SCAN.TIF?v=2"));
    }

    /**
     * An extension the table does not hold is the safe default, and so is a
     * missing URL - neither is a locale question, and both are pinned so a fix
     * aimed at the fold cannot turn them into a null.
     */
    @Test
    public void testUnknownExtensionIsOctetStream() {
        assertEquals("application/octet-stream", MimeTypeDetector.detectMimeType("/file.qqqq"));
        assertEquals("application/octet-stream", MimeTypeDetector.detectMimeType(null));
        assertEquals("application/octet-stream", MimeTypeDetector.detectMimeType("/no-extension"));
    }

    /**
     * Under a Turkish default locale the fold is where the answer changes:
     * "TIF".toLowerCase() is "tıf" and "AVIF".toLowerCase() is "avıf", so the
     * entries for those types are unreachable unless the fold names a locale.
     */
    @Test
    public void testTurkishLocaleStillMatches() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("image/tiff", MimeTypeDetector.detectMimeType("/scan.TIF"));
            assertEquals("image/tiff", MimeTypeDetector.detectMimeType("/SCAN.TIFF"));
            assertEquals("image/avif", MimeTypeDetector.detectMimeType("/picture.AVIF"));
            assertEquals("text/html", MimeTypeDetector.detectMimeType("/INDEX.HTML"));
            assertEquals("image/jpeg", MimeTypeDetector.detectMimeType("/PHOTO.JPG?w=100"));
            assertEquals("text/css", MimeTypeDetector.detectMimeType("/styles/site.css"));
        } finally {
            Locale.setDefault(saved);
        }
    }

    /**
     * The same fold decides the caching whitelist, which is keyed by MIME type,
     * so a type that came out wrong because of the locale also misses the
     * immutable-cache entry. Pinned here as one chain: URL to type to rule.
     */
    @Test
    public void testTurkishLocaleKeepsTheImmutableCacheEntry() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            String type = MimeTypeDetector.detectMimeType("/images/LOGO.TIF");
            assertEquals("image/tiff", type);
            assertTrue(MimeTypeDetector.isImmutableCache(type));
        } finally {
            Locale.setDefault(saved);
        }
    }
}
