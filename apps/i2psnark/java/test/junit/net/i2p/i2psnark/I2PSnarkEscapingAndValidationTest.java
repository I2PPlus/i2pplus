package net.i2p.i2psnark;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import static org.junit.Assert.*;

import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.SnarkManager;
import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;
import org.klomp.snark.web.RedirectQuery;

/**
 * Smoke tests guarding the robustness and presentation rules of the web UI:
 *
 * <ul>
 *   <li>Status messages are HTML-escaped so torrent/error text can never inject markup,
 *       while intentional '&amp;nbsp;' spacers still render as a single space.</li>
 *   <li>Post-action redirect query strings are strictly validated so a crafted query
 *       can never turn into an open redirect, while normal pagination and sort
 *       links ("?p=1&amp;sort=-1") keep working.</li>
 *   <li>Bencoded byte-array lengths are capped so a malicious peer cannot trigger a
 *       huge allocation (or a negative array size) with a bogus length prefix.</li>
 * </ul>
 *
 * @since 0.9.71+
 */
public class I2PSnarkEscapingAndValidationTest {

    // ----- status message escaping: markup must never survive as HTML -----

    @Test
    public void testMessageEscapingNeutralizesScriptTags() {
        assertEquals(
                "&lt;script&gt;alert(1)&lt;/script&gt;",
                SnarkManager.escapeMessage("<script>alert(1)</script>"));
    }

    @Test
    public void testMessageEscapingKeepsAmpersandAndAnglesReadable() {
        assertEquals("a &amp; b &lt; c &gt; d", SnarkManager.escapeMessage("a & b < c > d"));
    }

    @Test
    public void testMessageEscapingPreservesNbspAsSingleSpace() {
        assertEquals("Skipping &nbsp; now", SnarkManager.escapeMessage("Skipping &nbsp; now"));
    }

    @Test
    public void testMessageEscapingDoubleEscapesExistingEntities() {
        assertEquals("&amp;lt; &amp;gt;", SnarkManager.escapeMessage("&lt; &gt;"));
    }

    @Test
    public void testMessageEscapingLeavesNoRawAnchorTag() {
        String escaped =
                SnarkManager.escapeMessage(
                        "See <a href=\"http://example.i2p/?q=1&amp;x=2\">here</a>");
        assertFalse("escaping must not leave raw '<a '", escaped.contains("<a "));
        assertTrue("tag should be escaped as text", escaped.contains("&lt;a "));
    }

    @Test
    public void testMessageEscapingKeepsLinkTextReadable() {
        assertEquals(
                "&lt;http://example.i2p/&gt;",
                SnarkManager.escapeMessage("<http://example.i2p/>"));
    }

    // ----- redirect query validation: external URLs must be refused -----

    @Test
    public void testRedirectQueryAcceptsPagination() {
        assertTrue(RedirectQuery.isSafeRedirectQuery("?p=1"));
    }

    @Test
    public void testRedirectQueryAcceptsSortAndPage() {
        assertTrue(RedirectQuery.isSafeRedirectQuery("?p=1&sort=-1"));
    }

    @Test
    public void testRedirectQueryAcceptsStart() {
        assertTrue(RedirectQuery.isSafeRedirectQuery("?st=0&p=25"));
    }

    @Test
    public void testRedirectQueryAcceptsEmpty() {
        assertTrue(RedirectQuery.isSafeRedirectQuery(""));
        assertTrue(RedirectQuery.isSafeRedirectQuery(null));
    }

    @Test
    public void testRedirectQueryRejectsAbsoluteUrl() {
        assertFalse(RedirectQuery.isSafeRedirectQuery("http://evil.invalid/"));
    }

    @Test
    public void testRedirectQueryRejectsProtocolRelativeUrl() {
        assertFalse(RedirectQuery.isSafeRedirectQuery("//evil.invalid/"));
    }

    @Test
    public void testRedirectQueryRejectsUnknownParameter() {
        assertFalse(RedirectQuery.isSafeRedirectQuery("?continue=http://evil.invalid/"));
    }

    @Test
    public void testRedirectQueryRejectsNonNumericValue() {
        assertFalse(RedirectQuery.isSafeRedirectQuery("?p=1&search=<script>"));
    }

    @Test
    public void testRedirectQueryRejectsEmbeddedEntity() {
        assertFalse(RedirectQuery.isSafeRedirectQuery("?p=1&amp;x=2"));
    }

    @Test
    public void testRedirectQueryRejectsNumberTooLargeForInt() {
        // a giant digit string must not pass validation and explode later
        assertFalse(RedirectQuery.isSafeRedirectQuery("?p=2147483648"));
    }

    // ----- bencoded byte-array length handling -----

    /** @since 0.9.71+ */
    private static String decodeBencoded(String bencoded) throws Exception {
        BDecoder decoder =
                new BDecoder(
                        new ByteArrayInputStream(bencoded.getBytes(StandardCharsets.US_ASCII)));
        BEValue value = decoder.bdecode();
        if (value == null) {return null;}
        return new String(value.getBytes(), StandardCharsets.US_ASCII);
    }

    @Test
    public void testBencodeDecodesSimpleString() throws Exception {
        assertEquals("foobar", decodeBencoded("6:foobar"));
    }

    @Test
    public void testBencodeDecodesEmptyString() throws Exception {
        assertEquals("", decodeBencoded("0:"));
    }

    @Test
    public void testBencodeRejectsLengthOverCap() throws Exception {
        try {
            decodeBencoded("67108865:foobar");
            fail("expected InvalidBEncodingException for oversized length");
        } catch (InvalidBEncodingException ibe) {
            // expected
        }
    }

    @Test
    public void testBencodeRejectsLengthThatWouldOverflowInt() throws Exception {
        // used to wrap to a small or negative length instead of failing cleanly
        try {
            decodeBencoded("99999999999999:foobar");
            fail("expected InvalidBEncodingException for overflowing length");
        } catch (InvalidBEncodingException ibe) {
            // expected
        }
    }

    // ----- bencoded nesting depth: deep recursion must not overflow the stack -----

    /**
     * Bencoded stream of the given number of nested lists.
     *
     * @param depth how many nested lists
     * @return the bencoded bytes
     */
    private static String nestedLists(int depth) {
        StringBuilder sb = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; i++) {
            sb.append('l');
        }
        for (int i = 0; i < depth; i++) {
            sb.append('e');
        }
        return sb.toString();
    }

    @Test
    public void testBencodeRejectsDeeplyNestedLists() throws Exception {
        // 100000 levels of nesting used to throw StackOverflowError, an Error
        // that is not caught by callers and killed the I2CP listener thread
        try {
            BDecoder.bdecode(
                    new ByteArrayInputStream(
                            nestedLists(100000).getBytes(StandardCharsets.US_ASCII)));
            fail("expected InvalidBEncodingException for deep nesting");
        } catch (InvalidBEncodingException ibe) {
            // expected
        }
    }

    @Test
    public void testBencodeAcceptsReasonableNesting() throws Exception {
        BDecoder.bdecode(
                new ByteArrayInputStream(nestedLists(10).getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void testBencodeAcceptsNestingAtTheLimit() throws Exception {
        BDecoder.bdecode(
                new ByteArrayInputStream(nestedLists(64).getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void testBencodeRejectsDeeplyNestedMaps() throws Exception {
        StringBuilder sb = new StringBuilder(4096);
        for (int i = 0; i < 100000; i++) {
            sb.append("d1:x");
        }
        sb.append("6:value");
        for (int i = 0; i < 100000; i++) {
            sb.append('e');
        }
        try {
            BDecoder.bdecode(
                    new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.US_ASCII)));
            fail("expected InvalidBEncodingException for deep map nesting");
        } catch (InvalidBEncodingException ibe) {
            // expected
        }
    }

    @Test
    public void testTrackerB32ToHostnameConvertsKnownHosts() {
        assertEquals(
                "tracker2.postman.i2p/announce",
                I2PSnarkUtil.trackerB32ToHostname(
                        "http://ahsplxkbhemefwvvml7qovzl5a2b5xo5i7lyai7ntdunvcyfdtna.b32.i2p/announce"));
        assertEquals(
                "opentracker.skank.i2p/announce",
                I2PSnarkUtil.trackerB32ToHostname(
                        "http://by7luzwhx733fhc5ug2o75dcaunblq2ztlshzd7qvptaoa73nqua.b32.i2p/announce"));
        assertEquals(
                "sigmatracker.i2p/announce",
                I2PSnarkUtil.trackerB32ToHostname(
                        "http://qimlze77z7w32lx2ntnwkuqslrzlsqy7774v3urueuarafyqik5a.b32.i2p/announce"));
    }

    @Test
    public void testTrackerB32ToHostnameLeavesUnknownHostsAndStripScheme() {
        assertEquals(
                "tracker.example.i2p/announce",
                I2PSnarkUtil.trackerB32ToHostname("http://tracker.example.i2p/announce"));
        assertNull(I2PSnarkUtil.trackerB32ToHostname(null));
    }
}
