package net.i2p.i2ptunnel.localServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which of the files the HTTP proxy serves for its own pages may be read
 * cross-origin.
 *
 * <p>The proxy serves its theme out of docs/themes under the reserved host
 * proxy.i2p, and the pages that reference it are served at whatever host the
 * browser asked for, so the theme is always cross-origin to them. Granting
 * every one of those files to every origin let any clearnet page
 * <code>fetch()</code> the proxy's own CSS through 127.0.0.1:4444 and read it
 * back - a cookie-free statement that this browser runs an I2P+ proxy, and
 * which console theme it has. Only a web font is fetched in CORS mode; a
 * stylesheet, an image or a script is not, so only a font needs the grant.
 *
 * @since 0.9.72+
 */
public class ThemeCorsDecisionTest {

    /** Runs the decisions; it holds no state. */
    public ThemeCorsDecisionTest() {}

    /** The stylesheet is the file the lab read - 33,961 bytes of proxy.css. */
    @Test
    public void cssIsNotReadableCrossOrigin() {
        assertFalse(permitsAnyOrigin(headers("console/dark/proxy.css", "text/css; charset=UTF-8")));
        assertFalse(permitsAnyOrigin(headers("console/default/proxy.css", "text/css; charset=UTF-8")));
    }

    /** A script tag is a no-CORS fetch, so a script needs no grant either. */
    @Test
    public void scriptsAreNotReadableCrossOrigin() {
        assertFalse(permitsAnyOrigin(headers("console/shared.js", "text/javascript")));
    }

    /** Images and icons, including the SVG favicon, are no-CORS too. */
    @Test
    public void imagesAreNotReadableCrossOrigin() {
        assertFalse(permitsAnyOrigin(headers("console/default/images/i2plogo.png", "image/png")));
        assertFalse(permitsAnyOrigin(headers("console/default/images/favicon.svg", "image/svg+xml")));
        assertFalse(permitsAnyOrigin(headers("console/default/images/bg.jpg", "image/jpeg")));
        assertFalse(permitsAnyOrigin(headers("console/default/images/texture.webp", "image/webp")));
        assertFalse(permitsAnyOrigin(headers("console/default/images/old.gif", "image/gif")));
    }

    /**
     * A web font is the one asset the browser fetches in CORS mode, and the page
     * that names it sits at the destination's host while the font sits at
     * proxy.i2p, so without the grant the theme falls back to a system font.
     */
    @Test
    public void fontsKeepTheGrant() {
        assertTrue(permitsAnyOrigin(headers("fonts/OpenSans/OpenSans.woff2", "font/woff2")));
        assertTrue(permitsAnyOrigin(headers("fonts/FiraCode.woff", "font/woff")));
        assertTrue(permitsAnyOrigin(headers("fonts/Sora.ttf", "font/ttf")));
    }

    /**
     * The grant follows the content type, not the name, so a file served as a
     * font is treated as one whatever it is called.
     */
    @Test
    public void theDecisionIsTheContentType() {
        assertTrue(permitsAnyOrigin(headers("fonts/odd.name", "font/woff2")));
        assertFalse(permitsAnyOrigin(headers("fonts/notafont.css", "text/css; charset=UTF-8")));
    }

    /** A document, a PDF or a text file is not readable cross-origin either. */
    @Test
    public void documentsAreNotReadableCrossOrigin() {
        assertFalse(permitsAnyOrigin(headers("docs/help.xhtml", "application/xhtml+xml")));
        assertFalse(permitsAnyOrigin(headers("docs/manual.pdf", "application/pdf")));
        assertFalse(permitsAnyOrigin(headers("docs/notes.txt", "text/plain")));
        assertFalse(permitsAnyOrigin(headers("docs/index.html", "text/html; charset=UTF-8")));
    }

    /** Every response still starts with the status line and its content type. */
    @Test
    public void theStatusAndTypeAreUnchanged() {
        String hdr = headers("console/dark/proxy.css", "text/css; charset=UTF-8");
        assertTrue(hdr.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(hdr.contains("Content-Type: text/css; charset=UTF-8\r\n"));
    }

    /** The block ends with the blank line that separates headers from body. */
    @Test
    public void theBlockEndsAtTheHeaderEnd() {
        String hdr = headers("console/dark/proxy.css", "text/css; charset=UTF-8");
        assertTrue(hdr.endsWith("\r\n\r\n"));
        assertFalse(hdr.endsWith("\r\n\r\n\r\n"));
    }

    /**
     * The caching rules are untouched by the CORS change: the theme is
     * immutable, and everything else stays private.
     */
    @Test
    public void cachingIsUnchanged() {
        assertTrue(permitsImmutable(headers("console/dark/proxy.css", "text/css; charset=UTF-8")));
        assertTrue(permitsImmutable(headers("console/shared.js", "text/javascript")));
        assertTrue(permitsImmutable(headers("console/default/images/favicon.svg", "image/svg+xml")));
        assertTrue(permitsImmutable(headers("fonts/OpenSans/OpenSans.woff2", "font/woff2")));
        assertTrue(permitsImmutable(headers("console/default/images/i2plogo.png", "image/png")));
        assertFalse(permitsImmutable(headers("console/default/images/old.gif", "image/gif")));
        assertFalse(permitsImmutable(headers("docs/manual.pdf", "application/pdf")));
    }

    /** The connection is closed after one response, as this server does no pipelining. */
    @Test
    public void theConnectionIsClosed() {
        assertTrue(headers("console/dark/proxy.css", "text/css; charset=UTF-8").contains("Connection: close\r\n"));
    }

    /** The exact block for the file the lab read, pinned end to end. */
    @Test
    public void theCssBlockIsExact() {
        assertEquals("HTTP/1.1 200 OK\r\nContent-Type: text/css; charset=UTF-8\r\n" +
                     "Cache-Control: max-age=2628000, immutable\r\nConnection: close\r\n\r\n",
                     headers("console/dark/proxy.css", "text/css; charset=UTF-8"));
    }

    /**
     * The headers for one theme file.
     *
     * @param filename the path under docs/themes, as the request named it
     * @param type the content type chosen for it
     * @return the header block under test
     */
    private static String headers(String filename, String type) {
        return LocalHTTPServer.themeResponseHeaders(filename, type);
    }

    /**
     * Whether the response lets any origin read it.
     *
     * @param hdr the header block under test
     * @return true if it carries the wildcard CORS grant
     */
    private static boolean permitsAnyOrigin(String hdr) {
        return hdr.contains("Access-Control-Allow-Origin: *\r\n");
    }

    /**
     * Whether the response is the long-lived immutable one.
     *
     * @param hdr the header block under test
     * @return true if it carries the immutable cache directive
     */
    private static boolean permitsImmutable(String hdr) {
        return hdr.contains("Cache-Control: max-age=2628000, immutable\r\n");
    }
}
