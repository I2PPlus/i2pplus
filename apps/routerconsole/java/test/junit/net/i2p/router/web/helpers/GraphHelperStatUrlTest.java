package net.i2p.router.web.helpers;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pins how a stat or group name is escaped on the way into a graph URL.
 * <p>Stat names are not identifiers: a per-tunnel stat is named after the user's
 * nickname as registered, "[nickname] InBps", and the nickname may hold &amp;, +, =
 * or %. Only the space was escaped, so those characters were emitted raw and the
 * link parsed as extra parameters - the period and width were simply lost. The
 * encoder here escapes everything outside the unreserved set of RFC 3986, while
 * leaving a plain name byte-identical, which is what keeps every existing graph
 * link and the In/Out pairing intact.
 */
public class GraphHelperStatUrlTest {

    /** The names the console already links must come back unchanged, byte for byte. */
    @Test
    public void plainNamesAreByteIdentical() {
        assertEquals("bw.sendRate", GraphHelper.urlStatName("bw.sendRate"));
        assertEquals("bw.combined", GraphHelper.urlStatName("bw.combined"));
        assertEquals("tunnel.participatingTunnels",
                     GraphHelper.urlStatName("tunnel.participatingTunnels"));
        assertEquals("stream.connectSuccessRate",
                     GraphHelper.urlStatName("stream.connectSuccessRate"));
        assertEquals("peerCaps", GraphHelper.urlStatName("peerCaps"));
    }

    /** The one escape the old code did, done the same way, so no URL changes. */
    @Test
    public void spaceBecomesPercent20() {
        assertEquals("tunnel.participating%20InBps",
                     GraphHelper.urlStatName("tunnel.participating InBps"));
        assertEquals("tunnel.participating%20OutBps",
                     GraphHelper.urlStatName("tunnel.participating OutBps"));
    }

    /**
     * A per-tunnel stat is bracketed by the nickname. Brackets are not allowed in a
     * query value unescaped, and a browser's tolerance is not a contract.
     */
    @Test
    public void bracketsAreEscaped() {
        assertEquals("%5Bskank.i2p%5D%20InBps", GraphHelper.urlStatName("[skank.i2p] InBps"));
    }

    /**
     * The actual defect: a nickname holding a query delimiter ended the parameter
     * where it sat, so the rest of the URL was read as further parameters.
     */
    @Test
    public void queryDelimitersAreEscaped() {
        String enc = GraphHelper.urlStatName("[a&b] InBps");
        assertEquals("%5Ba%26b%5D%20InBps", enc);
        assertEquals("the value must not carry a parameter separator", -1, enc.indexOf('&'));
        assertEquals("the value must not carry a raw space", -1, enc.indexOf(' '));
        assertEquals("a%2Bb%3Dc%25d", GraphHelper.urlStatName("a+b=c%d"));
        assertEquals("pair%3A%5Bskank.i2p%5D", GraphHelper.urlStatName("pair:[skank.i2p]"));
    }

    /**
     * Whatever the encoder emits, the console's request.getParameter() must read the
     * registered name back - otherwise the graph would be drawn for nothing.
     *
     * @throws Exception if UTF-8 is not available as a charset name for the decoder
     */
    @Test
    public void everyNameRoundTripsThroughTheDecoder() throws Exception {
        String[] names = {"bw.sendRate", "tunnel.participating InBps", "[skank.i2p] InBps",
                          "[a&b] InBps", "[a+b] OutBps", "[a=b] InBps", "[100%] InBps",
                          "pair:[skank.i2p]", "\u30c8\u30f3\u30cd\u30eb InBps", "[\u0442\u0435\u0441\u0442] OutBps"};
        for (String name : names) {
            String enc = GraphHelper.urlStatName(name);
            assertEquals(name + " must survive the round trip",
                         name, URLDecoder.decode(enc, StandardCharsets.UTF_8.name()));
        }
    }

    /** A non-ASCII nickname is escaped as UTF-8 bytes, not mangled into '?'. */
    @Test
    public void nonAsciiNicknameBecomesUtf8Escapes() {
        assertEquals("%E3%83%88%E3%83%B3%E3%83%8D%E3%83%AB%20InBps",
                     GraphHelper.urlStatName("\u30c8\u30f3\u30cd\u30eb InBps"));
    }

    /**
     * Re-encoding an already-safe name changes nothing, so a caller that wraps a
     * value twice cannot corrupt it. A name that needed escaping is not safe to
     * double-encode, and nothing does so - the encoder is applied only at emission.
     */
    @Test
    public void safeNamesAreStableUnderRewrite() {
        String once = GraphHelper.urlStatName("bw.sendRate");
        assertEquals(once, GraphHelper.urlStatName(once));
    }

    /** An empty name stays empty rather than becoming a stray escape. */
    @Test
    public void emptyNameStaysEmpty() {
        assertEquals("", GraphHelper.urlStatName(""));
    }
}
