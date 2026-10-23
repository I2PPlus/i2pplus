package org.klomp.snark.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * urlify() embeds its argument in a double-quoted href attribute. urlEncode()
 * escaped &lt;, &gt;, ; and spaces but not a literal double quote, so a URL
 * containing one closed the attribute early and the remainder of the URL became
 * new attributes on the anchor. The display text was already escaped by
 * DataHelper.escapeHTML(); only the href was exposed.
 *
 * @since 0.9.72+
 */
public class UrlifyQuoteTest {

    /**
     * Counts raw double quotes - the anchor's own delimiters are the only legal ones.
     *
     * @param s the generated anchor markup to scan
     * @return the number of raw double-quote characters in the markup
     */
    private static int quotes(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) == '"')
                n++;
        return n;
    }

    /** The payload: a quote, then a space, then an event handler attribute. */
    @Test
    public void testQuoteCannotBreakOutOfHref() {
        String out = I2PSnarkServlet.urlify("http://e.example/\" onmouseover=\"alert(1)");
        assertEquals("a well-formed anchor has exactly two quotes, both delimiters",
                     2, quotes(out));
        assertTrue(out, out.contains("%22"));
    }

    /** The injected attribute must land inside the href value, not beside it. */
    @Test
    public void testInjectedAttributeStaysInsideTheUrl() {
        String out = I2PSnarkServlet.urlify("http://e.example/\" onclick=\"x");
        int start = out.indexOf("href=\"") + 6;
        int end = out.indexOf('"', start);
        assertTrue("href must be terminated by the anchor's own quote", end > start);
        String href = out.substring(start, end);
        assertFalse("no raw quote inside the href value", href.contains("\""));
        // Everything after the quote is part of the URL, so the anchor still has
        // exactly the two attributes urlify() writes.
        assertFalse("no stray target attribute injected",
                    out.indexOf("target=_blank") < end);
    }

    /** A battery of quote-bearing URLs: none may add a quote to the anchor. */
    @Test
    public void testHostileUrlsKeepTheAnchorWellFormed() {
        String[] urls = {
            "\"",
            "http://e.example/\"",
            "http://e.example/\" onmouseover=\"alert(1)",
            "http://e.example/a\"b\"c",
            "\" onfocus=alert(1) tabindex=0 x=\"",
            "magnet:?xt=urn:btih:\"&dn=\"x",
            "http://e.example/%22",
            "http://e.example/" + repeat('"', 40),
            "https://e.example/\" style=\"color:red",
        };
        for (String url : urls) {
            String out = I2PSnarkServlet.urlify(url);
            assertEquals("quotes in [" + url + "]", 2, quotes(out));
        }
    }

    /** A long URL with quotes is truncated and still well formed. */
    @Test
    public void testTruncatedUrlStaysWellFormed() {
        String url = "http://e.example/" + repeat('a', 200) + "\" onmouseover=\"alert(1)";
        String out = I2PSnarkServlet.urlify(url, 50);
        assertEquals(2, quotes(out));
        assertTrue(out, out.contains("&hellip;"));
    }

    /** Existing escaping behaviour is unchanged - only the quote was missing. */
    @Test
    public void testOtherEscapesUnchanged() {
        String out = I2PSnarkServlet.urlify("http://e.example/a;b c<d>e[f]g&h");
        assertTrue(out, out.contains("%3B"));
        assertTrue(out, out.contains("%20"));
        assertTrue(out, out.contains("%3C"));
        assertTrue(out, out.contains("%3E"));
        assertTrue(out, out.contains("%5B"));
        assertTrue(out, out.contains("%5D"));
        assertTrue(out, out.contains("&amp;"));
        assertEquals(2, quotes(out));
    }

    /** A plain URL round-trips into a normal anchor. */
    @Test
    public void testPlainUrl() {
        String out = I2PSnarkServlet.urlify("http://e.example/torrent/file.torrent");
        assertTrue(out, out.startsWith("<a href=\"http://e.example/torrent/file.torrent\""));
        assertTrue(out, out.endsWith("</a>"));
        assertEquals(2, quotes(out));
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++)
            sb.append(c);
        return sb.toString();
    }
}
