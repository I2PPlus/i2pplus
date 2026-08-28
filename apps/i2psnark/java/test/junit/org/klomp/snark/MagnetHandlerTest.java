package org.klomp.snark;

import static org.junit.Assert.assertEquals;

import java.net.URI;

import org.junit.Test;

/**
 * Verify {@link MagnetHandler#buildApiUri(String, String)} produces the correct
 * absolute API URI for the browser/console POST. This covers the URL
 * construction that was modernized away from the deprecated
 * {@code new URL(String)} constructor.
 *
 * @since 0.9.71+
 */
public class MagnetHandlerTest {

    /** A base without a trailing slash appends the path directly. */
    @Test
    public void testNoTrailingSlash() {
        assertEquals(URI.create("http://127.0.0.1:7657/i2psnark/_add"),
                     MagnetHandler.buildApiUri("http://127.0.0.1:7657/i2psnark", "/_add"));
    }

    /** A base with a trailing slash is stripped so the path is not doubled. */
    @Test
    public void testTrailingSlashStripped() {
        assertEquals(URI.create("http://127.0.0.1:7657/i2psnark/_add"),
                     MagnetHandler.buildApiUri("http://127.0.0.1:7657/i2psnark/", "/_add"));
    }

    /** The scheme and host are preserved through the rewrite. */
    @Test
    public void testSchemeAndHostPreserved() {
        assertEquals(URI.create("https://localhost:8443/_add"),
                     MagnetHandler.buildApiUri("https://localhost:8443", "/_add"));
    }

    /** A base that is only a host (no path) still appends the API path. */
    @Test
    public void testHostOnlyBase() {
        assertEquals(URI.create("http://example.com/_add"),
                     MagnetHandler.buildApiUri("http://example.com", "/_add"));
    }

    /** The result is a valid absolute URI usable with URL#toURL. */
    @Test
    public void testResultIsAbsoluteUri() {
        URI uri = MagnetHandler.buildApiUri("http://127.0.0.1:7657", "/_add");
        assertEquals(true, uri.isAbsolute());
        assertEquals("http", uri.getScheme());
        assertEquals("/_add", uri.getPath());
    }
}
