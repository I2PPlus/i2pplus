package org.klomp.snark.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The inline <code>const</code> declarations the servlet writes for its own
 * scripts, and the messages it puts inside them.
 *
 * <p>The values are translated strings, and the literal they are written into
 * is JavaScript source. A quote, a backslash or a newline in any of the 41
 * translations ends the literal, the inline script then fails to parse, and
 * every message that script supplies - the delete and remove confirmations -
 * is missing on the page in that locale. HTML escaping, which is what the rest
 * of the page uses, does nothing in that context.
 *
 * <p>The other half of the contract is that escaping changes nothing the
 * operator sees: every escape sequence the escaper emits decodes back to the
 * character it came from, so a message with nothing to escape is written byte
 * for byte and one with something to escape reads as it did before.
 *
 * @since 0.9.72+
 */
public class JSConstEscapingTest {

    /** A double quote, the character that ends the literal. */
    private static final String QUOTE = "" + (char) 0x22;

    /** A backslash, which escapes whatever follows it. */
    private static final String BSLASH = "" + (char) 0x5c;

    /** U+2028, a line terminator to a JS parser and ordinary text in HTML. */
    private static final String LINE_SEP = "" + (char) 0x2028;

    /** U+2029, the other JS line terminator. */
    private static final String PARA_SEP = "" + (char) 0x2029;

    /** Builds the declarations; it holds no state. */
    public JSConstEscapingTest() {}

    /**
     * The declaration shape the page's scripts expect: two spaces, the keyword,
     * the name, a double-quoted literal, a semicolon and a newline.
     */
    @Test
    public void testDeclarationShape() {
        assertEquals("  const removeMsg = " + QUOTE + "Delete it?" + QUOTE + ";\n",
                     I2PSnarkServlet.jsConst("removeMsg", "Delete it?"));
    }

    /**
     * A message with nothing to escape is written exactly as the catalog holds
     * it, which is the case for most of the translations of the five messages
     * now, non-ASCII text included.
     */
    @Test
    public void testAPlainMessageIsUnchanged() {
        String msg = "Ali ste prepričani, da želite izbrisati datoteko {0}?";
        assertEquals("  const removeMsg = " + QUOTE + msg + QUOTE + ";\n",
                     I2PSnarkServlet.jsConst("removeMsg", msg));
    }

    /**
     * The defect: a double quote in a translation ends the literal, and what
     * follows it is no longer JavaScript.
     */
    @Test
    public void testAQuoteCannotEndTheLiteral() {
        String msg = "Say " + QUOTE + "no" + QUOTE + " to delete?";
        String out = I2PSnarkServlet.jsConst("deleteMsg", msg);
        assertEquals("the literal holds one opening and one closing quote",
                     2, count(out, (char) 0x22));
        assertTrue(out, out.contains("\\x22"));
        assertEquals("the literal decodes back to the message", msg,
                     decode(out, "deleteMsg"));
    }

    /**
     * A backslash is the other character that breaks a literal - left alone it
     * escapes the closing quote and the statement runs on into the next line.
     */
    @Test
    public void testABackslashIsNotAnEscapeOfTheClosingQuote() {
        String msg = "file " + BSLASH;
        String out = I2PSnarkServlet.jsConst("removeMsg", msg);
        assertEquals("one backslash in, one escape sequence out",
                     2, count(out, (char) 0x5c));
        assertEquals(msg, decode(out, "removeMsg"));
    }

    /**
     * A newline inside the literal is a syntax error in JavaScript, so it has
     * to reach the page as an escape.
     */
    @Test
    public void testANewlineCannotBreakTheLine() {
        String out = I2PSnarkServlet.jsConst("removeMsg", "line one\nline two");
        assertEquals("one emitted line per declaration", 1, count(out, '\n'));
        assertEquals("line one\nline two", decode(out, "removeMsg"));
    }

    /**
     * U+2028 and U+2029 end a JavaScript line even though they are ordinary
     * text in HTML, so neither is written to the page raw.
     */
    @Test
    public void testTheUnicodeLineSeparatorsAreEscaped() {
        String msg = "a" + LINE_SEP + "b" + PARA_SEP + "c";
        String out = I2PSnarkServlet.jsConst("removeMsg", msg);
        assertFalse("the raw separator is not written to the page",
                    out.contains(LINE_SEP));
        assertFalse("the raw separator is not written to the page",
                    out.contains(PARA_SEP));
        assertTrue("the separator reaches the page as an escape",
                   out.contains("u2028"));
        assertTrue("the separator reaches the page as an escape",
                   out.contains("u2029"));
        assertEquals(msg, decode(out, "removeMsg"));
    }

    /**
     * Markup in a message survives: the angle brackets are escaped so a closing
     * script tag in a translation cannot end the block, and they decode back to
     * the markup the page's script inserts.
     */
    @Test
    public void testMarkupSurvivesTheRoundTrip() {
        String out = I2PSnarkServlet.jsConst("postDeleteMsg", "Deleting <b>{0}</b>...");
        assertFalse("no raw angle bracket written into the script block",
                    out.contains("<"));
        assertEquals("Deleting <b>{0}</b>...", decode(out, "postDeleteMsg"));
    }

    /**
     * The residue a translation pipeline leaves behind - a backslash written
     * before a quote the JS parser was already swallowing - is escaped rather
     * than dropped, so the text on the page does not change.
     */
    @Test
    public void testResidueRoundTripsUnchanged() {
        String msg = BSLASH + "''{0}" + BSLASH + "''";
        assertEquals(msg, decode(I2PSnarkServlet.jsConst("removeMsg", msg), "removeMsg"));
    }

    /**
     * How many times a character appears.
     *
     * @param s the string to scan
     * @param c the character to count
     * @return the number of occurrences
     */
    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /**
     * Reads the literal back the way a JS parser would, for the escapes this
     * escaper emits, and returns the value the declaration declares.
     *
     * @param decl one declaration as written to the page
     * @param name the identifier the declaration names
     * @return the string value the literal holds
     */
    private static String decode(String decl, String name) {
        String prefix = "  const " + name + " = " + QUOTE;
        assertTrue("the declaration names " + name, decl.startsWith(prefix));
        assertTrue("the declaration ends with a quoted semicolon",
                   decl.endsWith(QUOTE + ";\n"));
        String body = decl.substring(prefix.length(), decl.length() - 3);
        char bslash = (char) 0x5c;
        StringBuilder buf = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != bslash) {
                buf.append(c);
                continue;
            }
            char esc = body.charAt(++i);
            switch (esc) {
                case 'n':  buf.append('\n');  break;
                case 'r':  buf.append('\r');  break;
                case 'x':
                    buf.append((char) Integer.parseInt(body.substring(i + 1, i + 3), 16));
                    i += 2;
                    break;
                case 'u':
                    buf.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                    i += 4;
                    break;
                default:
                    buf.append(esc);
                    break;
            }
        }
        return buf.toString();
    }
}
