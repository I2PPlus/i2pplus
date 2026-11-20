package net.i2p.i2ptunnel;

import java.lang.reflect.Method;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the cross-network (I2P &lt;-&gt; clearnet) sub-resource block, which
 * defends against a page on one network reporting the visit to the other by embedding
 * an image, icon, favicon, script, stylesheet or font hosted there.
 *
 * <p>The predicate is pure - five values in, one boolean out - so no tunnel, socket or
 * router context is needed to pin it. The Accept value is consulted only when the
 * browser sent neither Fetch Metadata header, so the tests that pin a Fetch Metadata
 * case pass null for it.
 */
public class CrossNetworkBlockTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     */
    public CrossNetworkBlockTest() {}

    /** The request is routed to an outproxy. */
    private static final boolean CLEARNET = true;
    /** The request is routed to an I2P destination. */
    private static final boolean I2P = false;

    // ---------- the core leak: an I2P page pulling a clearnet sub-resource ----------

    /**
     * A favicon on an eepsite pointing at a clearnet host is refused: the fetch would
     * hand that host a visit log entry for the eepsite.
     */
    @Test
    public void testI2PPageLoadingClearnetFaviconIsBlocked() {
        // <link rel="icon" href="http://tracker.example.com/id.ico"> on an eepsite
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://secure.i2p/page", "image", "no-cors", null, CLEARNET));
    }

    /**
     * An inline image is refused by the same rule that refuses the favicon.
     */
    @Test
    public void testI2PPageLoadingClearnetImageIsBlocked() {
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://forum.i2p/thread", "image", "no-cors", null, CLEARNET));
    }

    /**
     * A script tag is refused - it is fetched automatically, with no user action and
     * with cookies attached.
     */
    @Test
    public void testI2PPageLoadingClearnetScriptIsBlocked() {
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://forum.i2p/thread", "script", "no-cors", null, CLEARNET));
    }

    /**
     * A web font is refused too, the fourth way a page can report a visit without
     * asking.
     */
    @Test
    public void testI2PPageLoadingClearnetFontIsBlocked() {
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://forum.i2p/thread", "font", "cors", null, CLEARNET));
    }

    /**
     * A numeric <code>.b32.i2p</code> referer is read as I2P, the same as a vanity
     * name, so the block does not depend on the destination being named.
     */
    @Test
    public void testB32I2PRefererIsRecognised() {
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://ukeu3k5oycgaauneqgtnvselmt4yemvoilkln7jpvamvfx7dnkdq.b32.i2p/",
                "image", "no-cors", null, CLEARNET));
    }

    // ---------- the reverse direction: a clearnet page probing an I2P resource ----------

    /**
     * A clearnet page embedding an <code>.i2p</code> resource is refused as well: that
     * fetch is how an I2P host tests whether the visitor browses I2P at all.
     */
    @Test
    public void testClearnetPageLoadingI2PResourceIsBlocked() {
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://example.com/page", "image", "no-cors", null, I2P));
    }

    // ---------- a navigation the user asked for is always allowed ----------

    /**
     * Following a clearnet link off an eepsite is user intent and is never refused.
     */
    @Test
    public void testTopLevelNavigationToClearnetFromI2PAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://forum.i2p/thread", "document", "navigate", null, CLEARNET));
    }

    /**
     * The mode says navigation, so it is navigation even when the destination field
     * looks resource-like - the mode is the more specific of the two.
     */
    @Test
    public void testNavigateModeBeatsSubresourceDest() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://forum.i2p/thread", "empty", "navigate", null, CLEARNET));
    }

    /**
     * An iframe the user opened is a nested navigation, allowed like a top-level one.
     */
    @Test
    public void testNestedNavigateAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://forum.i2p/thread", "iframe", "nested-navigate", null, CLEARNET));
    }

    /**
     * A browser that sends only Sec-Fetch-Dest still gets its document load allowed.
     */
    @Test
    public void testDocumentDestWithoutModeAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://forum.i2p/thread", "document", null, null, CLEARNET));
    }

    // ---------- same-network loads are always allowed ----------

    /**
     * A site's own favicon, same network, is the common case and must stay working.
     */
    @Test
    public void testI2PPageLoadingI2PFaviconAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://secure.i2p/page", "image", "no-cors", null, I2P));
    }

    /**
     * A different eepsite's asset stays inside I2P, so nothing reaches the clearnet
     * even though the two sites can see each other.
     */
    @Test
    public void testI2PPageLoadingOtherEepsiteResourceAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://a.i2p/page", "image", "no-cors", null, I2P));
    }

    /**
     * Ordinary outproxy browsing of a clearnet site with a clearnet CDN is unaffected.
     */
    @Test
    public void testClearnetPageLoadingClearnetResourceAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://example.com/page", "image", "no-cors", null, CLEARNET));
    }

    // ---------- requests that do not reveal a cross-network origin are allowed ----------

    /**
     * With no Referer there is no origin to compare against, so the request is
     * allowed - a typed address and a bookmark arrive this way.
     */
    @Test
    public void testNoRefererAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                null, "image", "no-cors", null, CLEARNET));
    }

    /**
     * The proxy's own error pages set <code>Referrer-Policy: no-referrer</code>, so
     * their stylesheet and favicon arrive with no Referer and must not be refused -
     * that is what would leave the block's own 403 page unstyled.
     */
    @Test
    public void testProxyAssetRequestWithoutRefererAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                null, "style", "no-cors", null, I2P));
    }

    /**
     * A Referer with no host is the same page again, which cannot cross a network.
     */
    @Test
    public void testRelativeRefererAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "/page", "image", "no-cors", null, CLEARNET));
    }

    /**
     * A Referer the parser cannot read is allowed: the block refuses only what it can
     * prove, so a malformed header never breaks a load.
     */
    @Test
    public void testMalformedRefererAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "::not a uri::", "image", "no-cors", null, CLEARNET));
    }

    // ---------- Referer-only fallback (browsers that send no Fetch Metadata) ----------

    /**
     * The defect: a browser that sends no Fetch Metadata - Safari before 16, or any
     * client or extension that strips the headers - had a link click treated as a
     * sub-resource load, so the user got a 403 with no way past it. With nothing to
     * tell the two apart, the request is allowed.
     */
    @Test
    public void testCrossNetworkNavigationAllowedWithoutSecFetch() {
        String browserAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://secure.i2p/page", null, null, browserAccept, CLEARNET));
    }

    /**
     * A client that sends no Accept header either is unknown, and unknown is allowed:
     * a false 403 costs the user a site, a missed block costs nothing.
     */
    @Test
    public void testCrossNetworkAllowedWithoutSecFetchOrAccept() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://secure.i2p/page", null, null, null, CLEARNET));
    }

    /**
     * What is provably a sub-resource is still blocked without Fetch Metadata, so the
     * protection is not lost on the older browsers - an image load says so in its own
     * Accept header.
     */
    @Test
    public void testCrossNetworkSubResourceBlockedWithoutSecFetch() {
        String imageAccept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8";
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://secure.i2p/page", null, null, imageAccept, CLEARNET));
        String xhrAccept = "application/json, text/plain, */*";
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://secure.i2p/page", null, null, xhrAccept, CLEARNET));
    }

    /**
     * And the same browser keeps its same-network loads.
     */
    @Test
    public void testSameNetworkAllowedWithoutSecFetch() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://secure.i2p/page", null, null, null, I2P));
    }

    /**
     * Fetch Metadata outranks the Accept header: a request the browser calls a
     * sub-resource is refused even when its Accept names HTML, and a navigation is
     * allowed even when its Accept looks like an image load.
     */
    @Test
    public void testSecFetchOutranksTheAcceptHeader() {
        String pageAccept = "text/html,application/xhtml+xml";
        String imageAccept = "image/*";
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://secure.i2p/page", "image", "no-cors", pageAccept, CLEARNET));
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://secure.i2p/page", "document", "navigate", imageAccept, CLEARNET));
    }

    /**
     * The Accept test itself: HTML in any form is a page, everything else is something
     * the page loaded by itself, and an empty header is treated as a page.
     */
    @Test
    public void testNavigationAcceptClassification() {
        assertTrue(I2PTunnelHTTPClient.isNavigationAccept(null));
        assertTrue(I2PTunnelHTTPClient.isNavigationAccept(""));
        assertTrue(I2PTunnelHTTPClient.isNavigationAccept("text/html,application/xhtml+xml"));
        assertTrue(I2PTunnelHTTPClient.isNavigationAccept("TEXT/HTML"));
        assertTrue(I2PTunnelHTTPClient.isNavigationAccept("application/xhtml+xml"));
        assertFalse(I2PTunnelHTTPClient.isNavigationAccept("image/webp,image/*,*/*;q=0.8"));
        assertFalse(I2PTunnelHTTPClient.isNavigationAccept("text/css,*/*;q=0.1"));
        assertFalse(I2PTunnelHTTPClient.isNavigationAccept("*/*"));
        assertFalse(I2PTunnelHTTPClient.isNavigationAccept("font/woff2"));
    }

    // ---------- the network named in the log line ----------

    /**
     * The log names the network a Referer belongs to, never the Referer itself, whose
     * path and query would keep the user's browsing in the router log.
     *
     * @throws Exception if the private helper cannot be reached
     */
    @Test
    public void testRefererNetworkForLog() throws Exception {
        assertEquals("i2p", refererNetwork("http://forum.i2p/thread"));
        assertEquals("i2p", refererNetwork("http://abcd.b32.i2p/"));
        assertEquals("clearnet", refererNetwork("http://example.com/page"));
        assertEquals("relative", refererNetwork("/page"));
        assertEquals("none", refererNetwork(null));
        assertEquals("unparseable", refererNetwork("::not a uri::"));
    }

    /**
     * The network name the block logs for a Referer. The helper is private, so it is
     * reached by reflection rather than widened to package scope on a request-path
     * method.
     *
     * @param referer the raw Referer value, or null
     * @return the network name the router log would show
     * @throws Exception if the helper cannot be found
     */
    private static String refererNetwork(String referer) throws Exception {
        Method m = I2PTunnelHTTPClient.class.getDeclaredMethod("refererNetworkForLog",
                                                               String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, referer);
    }
}
