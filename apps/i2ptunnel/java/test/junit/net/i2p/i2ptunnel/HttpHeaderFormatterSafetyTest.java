package net.i2p.i2ptunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for HttpHeaderFormatter line-break stripping, which keeps a
 * hostile header name, value, or request line from splitting a formatted
 * message into extra lines (HTTP response splitting / log forging).
 *
 * @since 0.9.71+
 */
public class HttpHeaderFormatterSafetyTest {

    @Test
    public void testStripLineBreaks() {
        assertNull(HttpHeaderFormatter.stripLineBreaks(null));
        assertEquals("abc", HttpHeaderFormatter.stripLineBreaks("abc"));
        assertEquals("abc", HttpHeaderFormatter.stripLineBreaks("a\r\nbc"));
        assertEquals("abc", HttpHeaderFormatter.stripLineBreaks("a\rb\nc"));
        assertEquals("abb", HttpHeaderFormatter.stripLineBreaks("a\rb\nb"));
        // only line breaks are removed
        assertEquals("a\0b", HttpHeaderFormatter.stripLineBreaks("a\0b"));
        assertFalse(HttpHeaderFormatter.stripLineBreaks("ok\r\nInjected: 1").contains("\r"));
    }

    @Test
    public void testFormatHeadersKeepsValuesOnOneLine() {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("Host", Collections.singletonList("example.com"));
        headers.put("X-Evil", Collections.singletonList("ok\r\nInjected: header"));

        String out = HttpHeaderFormatter.formatHeaders(headers,
                new StringBuilder("GET / HTTP/1.1"));
        assertTrue(out, out.contains("X-Evil: okInjected: header"));
        // command, two headers and the terminating blank line only
        assertEquals(out, 4, countOccurrences(out, "\r\n"));
    }

    @Test
    public void testFormatHeadersStripsHostileHeaderName() {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        List<String> vals = new ArrayList<String>();
        vals.add("one");
        headers.put("X-Name\r\nInjected: header", vals);

        String out = HttpHeaderFormatter.formatHeaders(headers,
                new StringBuilder("GET / HTTP/1.1"));
        assertTrue(out, out.contains("X-NameInjected: header: one"));
        assertEquals(out, 3, countOccurrences(out, "\r\n"));
    }

    @Test
    public void testFormatHeadersStripsHostileRequestLine() {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("Host", Collections.singletonList("example.com"));

        String out = HttpHeaderFormatter.formatHeaders(headers,
                new StringBuilder("GET / HTTP/1.1\r\nInjected: header"));
        assertTrue(out, out.startsWith("GET / HTTP/1.1Injected: header\r\n"));
        assertEquals(out, 3, countOccurrences(out, "\r\n"));
    }

    @Test
    public void testFormatHeadersCompactStripsLineBreaks() {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("Host", Collections.singletonList("example.com"));
        headers.put("X-Evil", Collections.singletonList("ok\r\nInjected: header"));

        String out = HttpHeaderFormatter.formatHeadersCompact(headers,
                new StringBuilder("GET / HTTP/1.1"));
        assertTrue(out, out.contains("X-Evil: okInjected: header"));
        assertFalse(out, out.contains("\r"));
        // only the fixed line separators, never one from the header value
        assertEquals(out, countOccurrences(out, "\n* "), countOccurrences(out, "\n"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {return count;}
            count++;
            from = at + needle.length();
        }
    }
}
