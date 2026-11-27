package net.i2p.i2ptunnel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.Test;

/**
 * Tests that the log form of the header dump reads header names as ASCII, not
 * as the JVM's default locale happens to fold them.
 *
 * <p>Header names are case-insensitive on the wire, so the dump folds each name
 * to lower case and compares it against the lowercase table: the two spellings
 * of the client destination that get dropped as repeats of the base32 form, the
 * base32 form that gets shortened to what the log viewer links, and the boiler
 * plate pairs that carry no information. Every one of those names carries an
 * upper-case I, and under a Turkish default locale <code>toLowerCase()</code>
 * turns it into a dotless ı - so the 500 character base64 destination was
 * printed in full, the base32 pair lost its shortening, and the value the reader
 * was meant to look at was buried under one the log form exists to drop.
 *
 * @since 0.9.72+
 */
public class HttpHeaderFormatterCaseTest {

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
     * The three destination spellings, written the way a client writes them -
     * mixed case, because the wire allows any case.
     *
     * @return the headers of one proxied request
     */
    private static Map<String, List<String>> destinationHeaders() {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("X-I2P-DestHash", Collections.singletonList("FlrP2Z6zY-oiqegIyDeX2mP8zH9VwQ3y"));
        headers.put("X-I2P-DestB32", Collections.singletonList(B32));
        headers.put("X-I2P-DestB64", Collections.singletonList(B64));
        return headers;
    }

    /**
     * Formats one request for the log.
     *
     * @param headers the header pairs, in the order the client sent them
     * @param request the request line
     * @return the log form of the whole block
     */
    private static String logOf(Map<String, List<String>> headers, String request) {
        return HttpHeaderFormatter.formatHeadersForLog(headers, new StringBuilder(request));
    }

    /**
     * In the default locale this build runs in, the destination is named once,
     * in the short base32 form.
     */
    @Test
    public void testDestinationNamedOnce() {
        String out = logOf(destinationHeaders(), "GET / HTTP/1.1");
        assertFalse(out, out.contains("DestHash"));
        assertFalse(out, out.contains("DestB64"));
        assertFalse(out, out.contains("SnfiX2SaPn7Z"));
        assertTrue(out, out.contains("X-I2P-DestB32: [cznm7wm6]"));
    }

    /**
     * Under a Turkish default locale the same request has to dump the same way.
     * Without a locale on the fold, "X-I2P-DestB64" folds to a dotless i and
     * stops matching the name the dump drops, so the base64 blob comes back and
     * the base32 pair loses its shortening.
     */
    @Test
    public void testTurkishLocaleDumpsTheSameRequest() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            String out = logOf(destinationHeaders(), "GET / HTTP/1.1");
            assertFalse(out, out.contains("DestHash"));
            assertFalse(out, out.contains("DestB64"));
            assertFalse(out, out.contains("SnfiX2SaPn7Z"));
            assertTrue(out, out.contains("X-I2P-DestB32: [cznm7wm6]"));
        } finally {
            Locale.setDefault(saved);
        }
    }

    /**
     * A boiler plate pair is dropped whatever case its name arrives in, and the
     * fold must not depend on the machine's locale to decide that.
     */
    @Test
    public void testBoilerplatePairDroppedUnderTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
            headers.put("CONNECTION", Collections.singletonList("close"));
            headers.put("X-Trace", Collections.singletonList("abc"));
            String out = logOf(headers, "GET / HTTP/1.1");
            assertTrue(out, out.contains("X-Trace: abc"));
            assertFalse(out, out.contains("onnection"));
        } finally {
            Locale.setDefault(saved);
        }
    }

    /**
     * The Host pair is folded into the request target rather than repeated, and
     * the name is recognised in the upper case a client is free to send it in,
     * under a locale that folds other names differently.
     */
    @Test
    public void testHostFoldedUnderTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
            headers.put("HOST", Collections.singletonList("127.0.0.1:7657"));
            String out = logOf(headers, "GET /index.jsp HTTP/1.1");
            assertTrue(out, out.contains("Request: GET 127.0.0.1:7657/index.jsp HTTP/1.1"));
            assertFalse(out, out.contains("\n* HOST:"));
        } finally {
            Locale.setDefault(saved);
        }
    }
}
