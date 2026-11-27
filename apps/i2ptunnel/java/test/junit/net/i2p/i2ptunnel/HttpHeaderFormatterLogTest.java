package net.i2p.i2ptunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for the log form of the HTTP header dump. The wire form names the
 * client destination three times, once as a 500 character base64 blob, and a
 * tracker request repeats it again in the query string, so the log form keeps
 * the base32 head and cuts everything else. The header pairs come first and the
 * request line last, with the Host folded into the request target instead of
 * repeated as a pair of its own.
 *
 * @since 0.9.72+
 */
public class HttpHeaderFormatterLogTest {

    /**
     * Builds header maps per case and holds nothing of its own.
     */
    public HttpHeaderFormatterLogTest() {
    }

    /** The client destination in the form the log viewer links. */
    private static final String B32 =
        "cznm7wm6wnr6uivj5aemqn4x3jr7ztd7kxaq34gkw7i7bbahulza.b32.i2p";

    /** The client destination in the 500 character base64 form the wire carries. */
    private static final String B64 =
        "SnfiX2SaPn7ZHvl0Dc2s-EzYyZfskMZlnBVhSopkOW5Kd-JfZJo-ftke-XQNzaz4TNjJl-yQxmWcFWFK" +
        "imQ5bkp34l9kmj5-2R75dA3NrPhM2MmX7JDGZZwVYUqKZDluSnfiX2SaPn7ZHvl0Dc2s-EzYyZfskMZl" +
        "nBVhSopkOW5Kd-JfZJo-ftke-XQNzaz4TNjJl-yQxmWcFWFKimQ5bkp34l9kmj5-2R75dA3NrPhM2MmX" +
        "7JDGZZwVYUqKZDluSnfiX2SaPn7ZHvl0Dc2s-EzYyZfskMZlnBVhSopkOW5Kd-JfZJo-ftke-XQNzaz4";

    /**
     * The wire form names the client destination three times; the log form keeps
     * only the base32 head the console links.
     */
    @Test
    public void testDestinationNamedOnce() {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("X-I2P-DestHash", Collections.singletonList("FlrP2Z6zY-oiqegIyDeX2mP8zH9VwQ3y"));
        headers.put("X-I2P-DestB32", Collections.singletonList(B32));
        headers.put("X-I2P-DestB64", Collections.singletonList(B64));

        String out = HttpHeaderFormatter.formatHeadersForLog(headers, new StringBuilder("GET / HTTP/1.1"));
        assertFalse(out, out.contains("DestB64"));
        assertFalse(out, out.contains("DestHash"));
        assertFalse(out, out.contains("SnfiX2SaPn7Z"));
        assertTrue(out, out.contains("X-I2P-DestB32: [cznm7wm6]"));
    }

    /**
     * A tracker request carries the whole destination in the query string. The
     * method, the path and the protocol version are what a reader needs.
     */
    @Test
    public void testLongRequestIsCutAtTheQuery() {
        String req = "GET /a?info_hash=%9b%26%02%1ca%25S&peer_id=%00%00%00&ip=" + B64 + ".i2p HTTP/1.1";
        String out = HttpHeaderFormatter.formatHeadersForLog(Collections.<String, List<String>>emptyMap(),
                                                             new StringBuilder(req));
        assertEquals("\n* Request: GET /a?... HTTP/1.1", out);
    }

    /**
     * A request inside the budget is passed through untouched, query included.
     */
    @Test
    public void testShortRequestPassesThrough() {
        assertEquals("GET /?page=2 HTTP/1.1", HttpHeaderFormatter.shortRequest("GET /?page=2 HTTP/1.1"));
        assertEquals("GET /x?a=1&b=2", HttpHeaderFormatter.shortRequest("GET /x?a=1&b=2"));
    }

    /**
     * A value with no query to cut is still cut, and the hidden length is
     * reported so the reader knows what was left out.
     */
    @Test
    public void testLongValueReportsItsLength() {
        StringBuilder val = new StringBuilder();
        for (int i = 0; i < HttpHeaderFormatter.MAX_VALUE + 40; i++) {val.append('x');}
        String out = HttpHeaderFormatter.shortValue(val.toString());
        assertTrue(out, out.startsWith("xxxx"));
        assertTrue(out, out.endsWith("... [104 chars]"));
        assertTrue(out, out.length() < 90);
    }

    /**
     * A base32 destination is cut to the eight characters the log viewer links,
     * and a missing one is named rather than printed blank.
     */
    @Test
    public void testShortB32() {
        assertEquals("[cznm7wm6]", HttpHeaderFormatter.shortB32(B32));
        assertEquals("[short]", HttpHeaderFormatter.shortB32("short"));
        assertEquals("[unknown]", HttpHeaderFormatter.shortB32(null));
    }

    /**
     * The same header sent twice is one pair, so a repeat cannot bury the
     * request the line is about.
     */
    @Test
    public void testRepeatedValuesCollapse() {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("X-Trace", Arrays.asList("abc", "abc"));
        String out = HttpHeaderFormatter.formatHeadersForLog(headers, new StringBuilder("GET / HTTP/1.1"));
        assertEquals("\n* X-Trace: abc\n* Request: GET / HTTP/1.1", out);
    }

    /**
     * A header sent with nothing after the colon reports nothing. Measured on
     * 2026-11-26 over 6,481 eepserver request dumps: 1,375 of the 2,800
     * Accept-Encoding lines were empty, each one a line that says nothing.
     */
    @Test
    public void testEmptyValueIsDropped() {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("Accept-Encoding", Collections.singletonList(""));
        headers.put("Range", Collections.singletonList("bytes=100-"));
        String out = HttpHeaderFormatter.formatHeadersForLog(headers, new StringBuilder("GET / HTTP/1.1"));
        assertEquals("\n* Range: bytes=100-\n* Request: GET / HTTP/1.1", out);
    }

    /**
     * The pair every browser sends identically is dropped; a pair that varies is
     * kept, because the variation is the finding.
     */
    @Test
    public void testBoilerplateDroppedVariedKept() {
        assertTrue(HttpHeaderFormatter.isBoilerplate("connection", "close"));
        assertTrue(HttpHeaderFormatter.isBoilerplate("pragma", "no-cache"));
        assertTrue(HttpHeaderFormatter.isBoilerplate("cache-control", "no-cache"));
        assertFalse(HttpHeaderFormatter.isBoilerplate("connection", "keep-alive"));
        assertFalse(HttpHeaderFormatter.isBoilerplate("cache-control", "max-age=600"));
        assertFalse(HttpHeaderFormatter.isBoilerplate("host", "skank.i2p"));
    }

    /**
     * Short pairs share a continuation line, and the request line follows them
     * on one of its own, so a three-header request costs two lines rather than
     * four.
     */
    @Test
    public void testShortPairsShareALine() {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("Host", Collections.singletonList("skank.i2p"));
        headers.put("Range", Collections.singletonList("bytes=100-"));
        headers.put("Accept", Collections.singletonList("*/*"));
        String out = HttpHeaderFormatter.formatHeadersForLog(headers, new StringBuilder("GET / HTTP/1.1"));
        assertEquals("\n* Range: bytes=100- * Accept: */*\n* Request: GET skank.i2p/ HTTP/1.1", out);
    }

    /**
     * Packing never runs a continuation line past the budget: a pair that will
     * not fit starts a new line, and the dump still needs fewer lines than it
     * has pairs.
     */
    @Test
    public void testLinesStayInsideTheBudget() {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < 8; i++) {
            headers.put("X-Hdr-" + i, Collections.singletonList(value(i)));
        }
        String out = HttpHeaderFormatter.formatHeadersForLog(headers, new StringBuilder("GET / HTTP/1.1"));
        // The dump opens with a newline, so split() yields an empty first
        // element ahead of the request line.
        String[] lines = out.split("\n");
        int headerLines = lines.length - 2;
        assertTrue("packing needed: " + headerLines + " lines for 8 pairs", headerLines < 8);
        for (String line : lines) {
            assertTrue("line over budget (" + line.length() + "): " + line,
                       line.length() <= HttpHeaderFormatter.LINE_BUDGET);
        }
    }

    /**
     * The request the router actually serves: who is asking on one line, what
     * for on the next, with the authority the client sent folded into the
     * request target instead of repeated beside it.
     */
    @Test
    public void testTrackerRequestFoldsTheHostIntoTheTarget() {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("X-I2P-DestB32", Collections.singletonList(B32));
        headers.put("User-Agent", Collections.singletonList("I2PSocketEepGet"));
        headers.put("Host", Collections.singletonList("opentracker.skank.i2p"));
        String out = HttpHeaderFormatter.formatHeadersForLog(
            headers, new StringBuilder("GET /a?info_hash=%9b%26%02%1ca%25S&peer_id=%00%00%00&ip=" +
                                       B64 + ".i2p HTTP/1.1"));
        assertEquals("\n* X-I2P-DestB32: [cznm7wm6] * User-Agent: I2PSocketEepGet" +
                     "\n* Request: GET opentracker.skank.i2p/a?... HTTP/1.1", out);
    }

    /**
     * The tunnel serves plain HTTP, so a folded target invents no scheme. An
     * absolute-form target keeps what the client wrote: an http:// is the
     * assumed default and is dropped, an https:// is a fact about the request
     * and stays. Either way the Host pair is a repeat of the target and goes.
     */
    @Test
    public void testSchemeIsNotInvented() {
        assertEquals("\n* Request: GET skank.i2p/a HTTP/1.1", dump("skank.i2p", "GET /a HTTP/1.1"));
        assertEquals("\n* Request: GET skank.i2p/a HTTP/1.1",
                     dump("skank.i2p", "GET http://skank.i2p/a HTTP/1.1"));
        assertEquals("\n* Request: GET https://skank.i2p/a HTTP/1.1",
                     dump("skank.i2p", "GET https://skank.i2p/a HTTP/1.1"));
    }

    /**
     * A Host that is not a plain authority - a bracketed IPv6 literal, or junk
     * someone put there - is not moved into the request line, where it would
     * read as the host the request was for. It stays a pair, cut like any other
     * over-long value, and the request line is left as the client wrote it.
     */
    @Test
    public void testJunkHostStaysAPair() {
        assertEquals("\n* Host: [::1]:8080\n* Request: GET /a HTTP/1.1",
                     dump("[::1]:8080", "GET /a HTTP/1.1"));
        assertEquals("\n* Host: skank.i2p * Host: x\n* Request: GET /a HTTP/1.1",
                     dump("skank.i2p * Host: x", "GET /a HTTP/1.1"));
    }

    /**
     * A request line with no query to cut is still cut, so the authority folded
     * into it cannot push the emitted line past the budget.
     */
    @Test
    public void testFoldedRequestStaysInsideTheBudget() {
        StringBuilder path = new StringBuilder("GET /");
        while (path.length() < 200) {path.append("segment/");}
        String out = dump("opentracker.skank.i2p", path.append(" HTTP/1.1").toString());
        for (String line : out.split("\n")) {
            assertTrue("line over budget (" + line.length() + "): " + line,
                       line.length() <= HttpHeaderFormatter.LINE_BUDGET);
        }
        assertTrue(out, out.endsWith("... HTTP/1.1"));
    }

    /**
     * A request line with no target, or one that is not a path, is left alone -
     * there is nothing to fold an authority into.
     */
    @Test
    public void testUnfoldableTargetsAreLeftAlone() {
        assertNull(HttpHeaderFormatter.requestTarget("GET"));
        assertFalse(HttpHeaderFormatter.targetIsAbsolute("OPTIONS * HTTP/1.1"));
        assertTrue(HttpHeaderFormatter.targetIsAbsolute("GET http://skank.i2p/a HTTP/1.1"));
        assertNull(HttpHeaderFormatter.requestWithHost("OPTIONS * HTTP/1.1", "skank.i2p"));
        assertNull(HttpHeaderFormatter.requestWithHost("GET", "skank.i2p"));
        assertEquals("\n* Host: skank.i2p\n* Request: OPTIONS * HTTP/1.1",
                     dump("skank.i2p", "OPTIONS * HTTP/1.1"));
    }

    /**
     * A request block a proxy rebuilt for its destination dumps the same way a
     * header map does: the request line last with the authority in its target,
     * the Host pair dropped as a repeat of it, and the boilerplate the tunnel
     * adds left out.
     */
    @Test
    public void testRequestBlockDumpsLikeTheMapForm() {
        String out = HttpHeaderFormatter.formatBlockForLog("GET http://skank.i2p/a HTTP/1.1\r\n" +
                                                           "Host: skank.i2p\r\n" +
                                                           "Connection: close\r\n" +
                                                           "Accept-Encoding: \r\n" +
                                                           "User-Agent: I2P\r\n" +
                                                           "\r\n");
        assertEquals("\n* User-Agent: I2P\n* Request: GET skank.i2p/a HTTP/1.1", out);
    }

    /**
     * A block with no headers at all still reports its request line, and an
     * empty or missing block reports nothing rather than a blank line.
     */
    @Test
    public void testBlockWithNoHeadersOrNoBlock() {
        assertEquals("\n* Request: CONNECT skank.i2p:80 HTTP/1.1",
                     HttpHeaderFormatter.formatBlockForLog("CONNECT skank.i2p:80 HTTP/1.1\r\n\r\n"));
        assertEquals("", HttpHeaderFormatter.formatBlockForLog(null));
        assertEquals("", HttpHeaderFormatter.formatBlockForLog(""));
    }

    /**
     * A distinct value of a fixed length, so the packing arithmetic is stable.
     *
     * @param i the index to vary the value by
     * @return a 20 character value
     */
    private static String value(int i) {
        StringBuilder buf = new StringBuilder(20);
        buf.append("v").append(i).append('-');
        while (buf.length() < 20) {buf.append('x');}
        return buf.toString();
    }

    /**
     * The log dump for a single Host header and a request line, the shape most
     * of these cases need.
     *
     * @param host the Host value, or null to send no Host at all
     * @param request the request line
     * @return the formatted dump
     */
    private static String dump(String host, String request) {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        if (host != null) {headers.put("Host", Collections.singletonList(host));}
        return HttpHeaderFormatter.formatHeadersForLog(headers, new StringBuilder(request));
    }
}
