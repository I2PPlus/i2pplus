package i2p.susi.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests how the regex replacement values are written to the log. They are HTML
 * fragments of several hundred characters, and one per replacement would bury
 * the message the reader is scanning for.
 *
 * @since 0.9.72+
 */
public class RegexOutputStreamLogTest {

    @Test
    public void testShortValuesPassThrough() {
        assertEquals("<a href>", RegexOutputStream.logText("<a href>"));
        assertEquals("", RegexOutputStream.logText(""));
    }

    @Test
    public void testLongValuesAreCut() {
        StringBuilder buf = new StringBuilder();
        while (buf.length() < 300) {
            buf.append("<td class=\"mailEntry\">");
        }
        String out = RegexOutputStream.logText(buf.toString());
        assertEquals(43, out.length());
        assertTrue(out, out.endsWith("..."));
        assertTrue(out, out.startsWith("<td class=\"mailEntry\">"));
    }
}
