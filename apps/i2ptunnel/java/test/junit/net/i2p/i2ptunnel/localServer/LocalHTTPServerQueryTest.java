package net.i2p.i2ptunnel.localServer;

import static org.junit.Assert.*;

import java.util.Map;

import org.junit.Test;

/**
 * Pins what the proxy's local server does with a query it cannot read.
 *
 * <p>The address helper hands /add and /b32 a query the browser encoded, and the
 * decoder used to stop at the first escape it could not read and return what it had
 * so far. A bare percent in a subscription URL - "50%off" is a perfectly ordinary
 * URL - therefore dropped the rest of that parameter and every parameter after it,
 * and the handler answered with its generic "bad parameters" page with no hint of
 * what was missing. Escaped UTF-8 was worse: each pair became one character, so an
 * internationalised subscription name was written into the address book as mojibake.
 *
 * @since 0.9.72+
 */
public class LocalHTTPServerQueryTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public LocalHTTPServerQueryTest() {}

    /**
     * A percent escape for every byte of some text, the way a browser writes one.
     *
     * @param text the text to escape
     * @return the escaped form, upper case hex as browsers emit it
     */
    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() * 3);
        for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            sb.append('%');
            sb.append(String.format("%02X", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * A bare percent is not an escape, and the rest of the query used to vanish with
     * it.
     */
    @Test
    public void testABarePercentIsRejected() {
        try {
            LocalHTTPServer.decode("50%off");
            fail("a bare percent was accepted");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /**
     * A percent with one hex digit, or none, or one that is not a hex digit, is the
     * same defect in a different shape.
     */
    @Test
    public void testAnIncompleteOrNonHexEscapeIsRejected() {
        String[] bad = {"abc%", "abc%4", "a%z1", "%", "%4", "%zz"};
        for (String s : bad) {
            try {
                LocalHTTPServer.decode(s);
                fail("accepted the escape in: " + s);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    /**
     * A run of escapes is one byte sequence, so a character encoded as several pairs
     * comes back as that character rather than as the Latin-1 reading of its bytes.
     */
    @Test
    public void testEscapedUtf8DecodesAsOneCharacter() {
        assertEquals("\u00e9", LocalHTTPServer.decode("%C3%A9"));
        assertEquals("\u4e2d\u6587", LocalHTTPServer.decode("%E4%B8%AD%E6%96%87"));
        // a code point past the BMP arrives as four pairs and one character
        assertEquals("\ud83d\ude00", LocalHTTPServer.decode("%F0%9F%98%80"));
        assertEquals("caf\u00e9 \u4e2d\u6587 \ud83d\ude00",
                     LocalHTTPServer.decode(escape("caf\u00e9 \u4e2d\u6587 \ud83d\ude00")));
    }

    /**
     * Escapes that are not valid UTF-8 - a lone continuation byte, a truncated
     * sequence - are refused rather than turned into replacement characters.
     */
    @Test
    public void testInvalidUtf8IsRejected() {
        String[] bad = {"%80", "%C3", "%C3%28", "%E4%B8"};
        for (String s : bad) {
            try {
                LocalHTTPServer.decode(s);
                fail("accepted the bytes in: " + s);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    /**
     * A plus stays a plus. This is a URL query, not a form body: the helper
     * destination and the blinding secrets carry literal pluses.
     */
    @Test
    public void testPlusStaysLiteral() {
        assertEquals("a+b c", LocalHTTPServer.decode("a+b%20c"));
        assertEquals("AB+CD==", LocalHTTPServer.decode("AB+CD%3D%3D"));
    }

    /**
     * Text with no escapes at all is returned as it came, and an empty string stays
     * empty.
     */
    @Test
    public void testPlainTextIsUntouched() {
        assertEquals("", LocalHTTPServer.decode(""));
        assertEquals("http://example.test/a?b=c&d=e",
                     LocalHTTPServer.decode("http://example.test/a?b=c&d=e"));
        assertEquals("%41 is A", LocalHTTPServer.decode("%2541 is A"));
    }

    /**
     * The defect as the handler sees it: one bad escape in the first parameter lost
     * host and dest with it, and /add answered its generic failure page.
     */
    @Test
    public void testAQueryWithABadEscapeIsRefusedWhole() {
        try {
            LocalHTTPServer.decodeQuery("url=http://x/50%off&host=Name&dest=D");
            fail("a query with a bare percent was decoded");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /**
     * A well-formed query still decodes both keys and values, including an escaped
     * name, and a value with no equals sign stays an empty value.
     */
    @Test
    public void testAWellFormedQueryStillDecodes() {
        Map<String, String> opts =
            LocalHTTPServer.decodeQuery("url=http%3A%2F%2Fx%2F&host=" + escape("caf\u00e9")
                                        + "&dest=D&master");
        assertEquals("http://x/", opts.get("url"));
        assertEquals("caf\u00e9", opts.get("host"));
        assertEquals("D", opts.get("dest"));
        assertEquals("", opts.get("master"));
    }
}
