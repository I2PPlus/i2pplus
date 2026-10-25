package net.i2p.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Tests for the Escapes utility. The differential tests keep verbatim copies
 * of the pre-consolidation implementations (DataHelper.escapeHTML,
 * QRServlet.escapeXml, I2PSnarkServlet.escapeJSString) and require the new
 * primitives to produce byte-identical output over an adversarial corpus and
 * seeded random strings, so the consolidation is provably not a behavior
 * change except where a documented bug fix intentionally changes it.
 *
 * @since 0.9.72+
 */
public class EscapesTest {

    /**
     * Reference copy of the old DataHelper.escapeHTML: five sequential
     * String.replace calls, ampersand first, no backtick.
     *
     * @param unescaped the unescaped string, may be null
     * @return the escaped string, or null if null is passed in
     */
    private static String oldEscapeHTML(String unescaped) {
        if (unescaped == null) return null;
        String escaped = unescaped;
        String[] escapeChars = {"&", "\"", "<", ">", "'"};
        String[] escapeCodes = {"&amp;", "&quot;", "&lt;", "&gt;", "&apos;"};
        for (int i = 0; i < escapeChars.length; i++) {
            escaped = escaped.replace(escapeChars[i], escapeCodes[i]);
        }
        return escaped;
    }

    /**
     * Reference copy of the old QRServlet.escapeXml: single-pass switch over
     * the five XML metacharacters.
     *
     * @param s the text to escape
     * @return s with the XML metacharacters replaced by their entities
     */
    private static String oldEscapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&')
                sb.append("&amp;");
            else if (c == '<')
                sb.append("&lt;");
            else if (c == '>')
                sb.append("&gt;");
            else if (c == '"')
                sb.append("&quot;");
            else if (c == '\'')
                sb.append("&apos;");
            else
                sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Reference copy of the old I2PSnarkServlet.escapeJSString: sequential
     * replaces, backslash first, no null check, C0 controls and U+2028/29
     * passed through raw.
     *
     * @param s non-null string to escape
     * @return the escaped string
     */
    private static String oldEscapeJSString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("<", "\\x3c")
                .replace(">", "\\x3e")
                .replace("\"", "\\x22")
                .replace("'", "\\x27")
                .replace("&", "\\x26");
    }

    /**
     * Corpus of adversarial and ordinary inputs: markup payloads, already
     * escaped entities, backslashes and escape-sequence lookalikes, line
     * terminators, controls, non-ASCII text, and clean strings.
     *
     * @return the shared corpus for every differential and property test
     */
    private static List<String> corpus() {
        List<String> cases = new ArrayList<>();
        cases.add("");
        cases.add("plain text, no specials 1234");
        cases.add("</script>");
        cases.add("<img src=x onerror=alert(1)>");
        cases.add("`");
        cases.add("class=`foo");
        cases.add("\" ' &");
        cases.add("\\");
        cases.add("\\x3c");
        cases.add("\\x22");
        cases.add("\r\n");
        cases.add("a\nb\rc\rd");
        cases.add("&lt;");
        cases.add("&amp;");
        cases.add("&nbsp;");
        cases.add("&#39;");
        cases.add("name\u2028with\u2029separators");
        cases.add("\u000b\u000c");
        cases.add("\u0000\u0001\u001f");
        cases.add("\u00001");
        cases.add("torrent \u00e9\u4e2d\u6587 \u2603");
        cases.add("<a href=\"/?a=1&b=2\">&amp;</a>");
        cases.add("C:\\path\\to\\file");
        cases.add("'\"`<>");
        cases.add("ends with backslash\\");
        cases.add("null\u0000byte");
        cases.add("tab\tand bell\u0007");
        return cases;
    }

    /**
     * Seeded pseudo-random strings over an alphabet dense in escapable
     * characters, plus a sparse pass mixing in ordinary text.
     *
     * @return randomly generated strings for the differential tests
     */
    private static List<String> randomStrings() {
        String alphabet = "&\"<>'`\\\n\r\u2028\u2029\u000b\u000c\u0000 abcdef;/%=x";
        List<String> out = new ArrayList<>();
        Random r = new Random(0x12345678L);
        for (int i = 0; i < 2000; i++) {
            int len = r.nextInt(40);
            StringBuilder sb = new StringBuilder(len);
            for (int j = 0; j < len; j++) {
                sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
            }
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * Every corpus and random string, plus null.
     *
     * @return the full input set for the differential loops
     */
    private static List<String> allInputs() {
        List<String> all = new ArrayList<>(corpus());
        all.addAll(randomStrings());
        all.add(null);
        return all;
    }

    /**
     * html() must equal the old sequential chain on every corpus or random
     * input, except where the input contains a backtick (pinned separately).
     */
    @Test
    public void testHtmlDifferential() {
        for (String s : allInputs()) {
            if (s != null && s.indexOf('`') >= 0) {
                continue;
            }
            assertEquals("html differential for [" + s + "]",
                         oldEscapeHTML(s), Escapes.html(s));
        }
    }

    /**
     * xml() must equal the old single-pass escaper on every non-null input;
     * the old one was not null-safe, null is covered by testNull.
     */
    @Test
    public void testXmlDifferential() {
        for (String s : allInputs()) {
            if (s == null) {
                continue;
            }
            assertEquals("xml differential for [" + s + "]",
                         oldEscapeXml(s), Escapes.xml(s));
        }
    }

    /**
     * jsString() must equal the old chain except for the documented additions:
     * U+2028/29, vertical tab, form feed and the remaining C0 controls, which
     * the old code passed through raw. The old one was not null-safe, null is
     * covered by testNull.
     */
    @Test
    public void testJsStringDifferential() {
        for (String s : allInputs()) {
            if (s == null || _hasNewOnlyEscapes(s)) {
                continue;
            }
            assertEquals("jsString differential for [" + s + "]",
                         oldEscapeJSString(s), Escapes.jsString(s));
        }
    }

    /**
     * True when a string contains a character that jsString() escapes but the
     * old implementation did not, i.e. the differential test must skip it.
     *
     * @param s the string to classify
     * @return true if the intentional bug-fix additions apply
     */
    private static boolean _hasNewOnlyEscapes(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u2028' || c == '\u2029' || (c >= 0x0b && c <= 0x0c) ||
                (c < 0x20 && c != '\n' && c != '\r')) {
                return true;
            }
        }
        return false;
    }

    /**
     * All three primitives return null for null input.
     */
    @Test
    public void testNull() {
        assertNull(Escapes.html(null));
        assertNull(Escapes.xml(null));
        assertNull(Escapes.jsString(null));
    }

    /**
     * A string with nothing to escape must come back as the same instance,
     * including the empty string.
     */
    @Test
    public void testIdentity() {
        String clean = "nothing here 1234 \u00e9\u4e2d\u6587";
        assertSame(clean, Escapes.html(clean));
        assertSame(clean, Escapes.xml(clean));
        assertSame(clean, Escapes.jsString(clean));
        String empty = "";
        assertSame(empty, Escapes.html(empty));
        assertSame(empty, Escapes.xml(empty));
        assertSame(empty, Escapes.jsString(empty));
    }

    /**
     * html() encodes the five metacharacters with the historical entities, and
     * encodes an ampersand first so existing entities are encoded rather than
     * re-expanded.
     */
    @Test
    public void testHtmlEntities() {
        assertEquals("&amp;&quot;&lt;&gt;&apos;", Escapes.html("&\"<>'"));
        assertEquals("&amp;lt;", Escapes.html("&lt;"));
        assertEquals("&amp;amp;", Escapes.html("&amp;"));
    }

    /**
     * html() encodes the backtick, the documented change from the old chain,
     * which left it bare.
     */
    @Test
    public void testHtmlBacktick() {
        assertEquals("&#96;", Escapes.html("`"));
        assertEquals("class=&#96;foo", Escapes.html("class=`foo"));
        assertEquals("`", oldEscapeHTML("`"));
    }

    /**
     * xml() encodes the five XML metacharacters and leaves the backtick bare.
     */
    @Test
    public void testXmlEntities() {
        assertEquals("&amp;&quot;&lt;&gt;&apos;", Escapes.xml("&\"<>'"));
        assertEquals("a`b", Escapes.xml("a`b"));
    }

    /**
     * jsString() emits the historical short and hex escapes, never re-escapes
     * the sequences it inserts, and passes non-ASCII text through unchanged.
     */
    @Test
    public void testJsStringEscapes() {
        assertEquals("\\\\", Escapes.jsString("\\"));
        assertEquals("\\n\\r", Escapes.jsString("\n\r"));
        assertEquals("\\x3c\\x3e\\x22\\x27\\x26", Escapes.jsString("<>\"'&"));
        assertEquals("\\x3c", Escapes.jsString("<"));
        assertEquals("\\\\x3c", Escapes.jsString("\\x3c"));
        assertEquals("\u00e9\u4e2d\u6587", Escapes.jsString("\u00e9\u4e2d\u6587"));
    }

    /**
     * Bug fix: U+2028, U+2029, vertical tab and form feed terminate a JS
     * string literal line and are now escaped instead of emitted raw.
     */
    @Test
    public void testJsStringLineTerminators() {
        assertEquals("a\\u2028b\\u2029c", Escapes.jsString("a\u2028b\u2029c"));
        assertEquals("\\x0b\\x0c", Escapes.jsString("\u000b\u000c"));
    }

    /**
     * Bug fix: the remaining C0 controls are hex-escaped; a NUL before a
     * digit must not read as a different escape sequence.
     */
    @Test
    public void testJsStringControls() {
        assertEquals("\\x00\\x01\\x1f", Escapes.jsString("\u0000\u0001\u001f"));
        assertEquals("\\x001", Escapes.jsString("\u00001"));
        assertEquals("a\\x09b", Escapes.jsString("a\tb"));
    }

    /**
     * Decode an Escapes.jsString() result the way a JavaScript string literal
     * would be parsed: short escapes, two-digit hex escapes and four-digit
     * unicode escapes back to characters. A malformed or truncated escape
     * fails the test rather than decoding to something wrong.
     *
     * @param s the escaped output to decode
     * @return the original string the escapes stand for
     */
    private static String _decodeJs(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case '\\':
                    sb.append('\\');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 'x': {
                    int v = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                    sb.append((char) v);
                    i += 2;
                    break;
                }
                case 'u': {
                    int v = Integer.parseInt(s.substring(i + 1, i + 5), 16);
                    sb.append((char) v);
                    i += 4;
                    break;
                }
                default:
                    fail("bad escape \\" + n + " in [" + s + "]");
            }
        }
        return sb.toString();
    }

    /**
     * Security properties of the output: no markup metacharacter survives
     * bare in html/xml, no string terminator or metacharacter survives bare
     * in jsString, and every escaped output decodes back to its input.
     */
    @Test
    public void testRoundTripProperties() {
        for (String s : corpus()) {
            String h = Escapes.html(s);
            String x = Escapes.xml(s);
            String j = Escapes.jsString(s);
            for (char c : "\"<>".toCharArray()) {
                assertFalse("bare " + c + " in html output", h.indexOf(c) >= 0);
                assertFalse("bare " + c + " in xml output", x.indexOf(c) >= 0);
            }
            assertFalse("bare ` in html output", h.indexOf('`') >= 0);
            // backtick is not an XML metacharacter, it stays bare by design
            for (char c : "<>\"'&".toCharArray()) {
                assertFalse("bare " + c + " in js output", j.indexOf(c) >= 0);
            }
            for (char c : "\n\r\u2028\u2029".toCharArray()) {
                assertFalse("bare terminator in js output", j.indexOf(c) >= 0);
            }
            for (int i = 0; i < 0x20; i++) {
                assertFalse("bare C0 in js output", j.indexOf((char) i) >= 0);
            }
            // every backslash in js output introduces a valid escape, and the
            // whole result decodes back to the original input
            assertEquals("js round trip for [" + s + "]", s, _decodeJs(j));
        }
        for (String s : randomStrings()) {
            assertEquals("js round trip for random [" + s + "]", s,
                         _decodeJs(Escapes.jsString(s)));
        }
    }
}
