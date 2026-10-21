package net.i2p.i2ptunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * the base32 head and cuts everything else.
 *
 * @since 0.9.72+
 */
public class HttpHeaderFormatterLogTest {

    private static final String B32 =
        "cznm7wm6wnr6uivj5aemqn4x3jr7ztd7kxaq34gkw7i7bbahulza.b32.i2p";

    private static final String B64 =
        "SnfiX2SaPn7ZHvl0Dc2s-EzYyZfskMZlnBVhSopkOW5Kd-JfZJo-ftke-XQNzaz4TNjJl-yQxmWcFWFK" +
        "imQ5bkp34l9kmj5-2R75dA3NrPhM2MmX7JDGZZwVYUqKZDluSnfiX2SaPn7ZHvl0Dc2s-EzYyZfskMZl" +
        "nBVhSopkOW5Kd-JfZJo-ftke-XQNzaz4TNjJl-yQxmWcFWFKimQ5bkp34l9kmj5-2R75dA3NrPhM2MmX" +
        "7JDGZZwVYUqKZDluSnfiX2SaPn7ZHvl0Dc2s-EzYyZfskMZlnBVhSopkOW5Kd-JfZJo-ftke-XQNzaz4";

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

    @Test
    public void testShortB32() {
        assertEquals("[cznm7wm6]", HttpHeaderFormatter.shortB32(B32));
        assertEquals("[short]", HttpHeaderFormatter.shortB32("short"));
        assertEquals("[unknown]", HttpHeaderFormatter.shortB32(null));
    }

    /**
     * The same header sent twice is one line, so a repeat cannot bury the
     * request the line is about.
     */
    @Test
    public void testRepeatedValuesCollapse() {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("Host", Arrays.asList("skank.i2p", "skank.i2p"));
        String out = HttpHeaderFormatter.formatHeadersForLog(headers, new StringBuilder("GET / HTTP/1.1"));
        assertEquals("\n* Request: GET / HTTP/1.1\n* Host: skank.i2p", out);
    }
}
