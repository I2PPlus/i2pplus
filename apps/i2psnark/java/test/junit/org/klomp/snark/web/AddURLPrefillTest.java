package org.klomp.snark.web;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import net.i2p.data.DataHelper;

import org.junit.Test;

/**
 * Pins what the add-torrent form hands back for a prefilled URL.
 *
 * <p>The form is prefilled from the nofilter_newURL query parameter, which bypasses
 * the XSS parameter filter, and the only thing done to it before it was written into
 * the value attribute was stripHTML() - four characters replaced by spaces. That
 * cannot break out of the attribute, but it is lossy: an apostrophe in a magnet dn=
 * field became a space, and any '&amp;name;' sequence in the URL was left for the
 * browser's parser to decode, so the URL submitted was not the one the user followed.
 *
 * @since 0.9.72+
 */
public class AddURLPrefillTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public AddURLPrefillTest() {}

    /** A magnet link with the characters this form has to carry unchanged. */
    private static final String MAGNET = "magnet:?xt=urn:btih:ABCD&dn=it%27s%20a%20test&amp;x=1";

    /** The same link with the display name decoded, apostrophe and all. */
    private static final String APOSTROPHE = "magnet:?xt=urn:btih:ABCD&dn=it's a test&amp;x=1";

    /**
     * A request carrying the given query parameters.
     *
     * @param method the HTTP method of the request
     * @param pairs the parameters, name then value, repeated
     * @return the request to read them from
     */
    private static HttpServletRequest request(String method, String... pairs) {
        Map<String, String> params = new HashMap<>(4);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            params.put(pairs[i], pairs[i + 1]);
        }
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getParameter(anyString())).thenAnswer(inv -> params.get(inv.getArgument(0)));
        return req;
    }

    /**
     * What a browser's parser makes of an attribute value: turns each entity
     * escapeHTML() emits back into the character it stands for, the apostrophe
     * entity and the numeric backtick included.
     *
     * @param escaped the attribute value as written
     * @return the string the form submits back
     */
    private static String unescape(String escaped) {
        return escaped.replace("&quot;", "\"")
                      .replace("&lt;", "<")
                      .replace("&gt;", ">")
                      .replace("&apos;", "'")
                      .replace("&#96;", "`")
                      .replace("&amp;", "&");
    }

    /**
     * The point of escaping instead of stripping: the URL that comes back out of the
     * form is byte for byte the one that was linked.
     */
    @Test
    public void testTheURLSurvivesTheFormRoundTrip() {
        HttpServletRequest req = request("GET", "nofilter_newURL", MAGNET);
        String url = I2PSnarkServlet.addURL(req, req.getMethod());
        assertEquals(MAGNET, url);
        assertEquals(MAGNET, unescape(DataHelper.escapeHTML(url)));
    }

    /**
     * The defect, pinned: the stripping the form used to do is lossy on this same
     * input, which is why the value is escaped instead.
     */
    @Test
    public void testStrippingWouldHaveChangedTheURL() {
        String stripped = DataHelper.stripHTML(APOSTROPHE);
        assertNotEquals(APOSTROPHE, stripped);
        assertTrue("stripping left the apostrophe alone: " + stripped,
                   stripped.indexOf('\'') < 0);
        assertEquals(APOSTROPHE, unescape(DataHelper.escapeHTML(APOSTROPHE)));
    }

    /**
     * A quote or a tag cannot end the attribute or inject markup, which is what the
     * escaping is for as well.
     */
    @Test
    public void testAQuoteCannotEndTheAttribute() {
        String nasty = "http://example.i2p/a\"><script>alert(1)</script>";
        HttpServletRequest req = request("GET", "nofilter_newURL", nasty);
        String escaped = DataHelper.escapeHTML(I2PSnarkServlet.addURL(req, "GET"));
        assertFalse("a raw quote in the attribute: " + escaped, escaped.contains("\""));
        assertFalse("a raw angle in the attribute: " + escaped, escaped.contains("<"));
        assertEquals(nasty, unescape(escaped));
    }

    /**
     * A POST is the form being submitted, not a link being followed, so nothing is
     * prefilled from it.
     */
    @Test
    public void testAPostPrefillsNothing() {
        HttpServletRequest req = request("POST", "nofilter_newURL", MAGNET);
        assertEquals("", I2PSnarkServlet.addURL(req, "POST"));
    }

    /**
     * The plain newURL name, which the XSS filter has already had its way with, is
     * still read so the links that have pointed here for years keep working.
     */
    @Test
    public void testTheLegacyParameterNameStillWorks() {
        HttpServletRequest req = request("GET", "newURL", "http://example.i2p/t.torrent");
        assertEquals("http://example.i2p/t.torrent", I2PSnarkServlet.addURL(req, "GET"));
    }

    /**
     * The nofilter_ name wins when both are present, and surrounding spaces are cut
     * so a value of spaces counts as nothing at all.
     */
    @Test
    public void testTheUnfilteredNameWinsAndBlankCountsAsNothing() {
        HttpServletRequest both = request("GET", "nofilter_newURL", " http://a.i2p/x ",
                                          "newURL", "http://b.i2p/y");
        assertEquals("http://a.i2p/x", I2PSnarkServlet.addURL(both, "GET"));
        HttpServletRequest blank = request("GET", "nofilter_newURL", "   ",
                                          "newURL", "http://b.i2p/y");
        assertEquals("http://b.i2p/y", I2PSnarkServlet.addURL(blank, "GET"));
        HttpServletRequest none = request("GET");
        assertEquals("", I2PSnarkServlet.addURL(none, "GET"));
    }
}
