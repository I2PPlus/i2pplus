package net.i2p.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The command-line help of EepGet and EepPost must describe the options the
 * parsers actually accept, and the help defaults must match the timeout
 * constants. Also covers the chunk-size line parsing, which must skip chunk
 * extensions (RFC 9112 sec. 7.1.1).
 *
 * @since 0.9.71+
 */
public class EepGetUsageTest {

    /** Options accepted by EepGet.main(), from its Getopt spec. */
    private static final String EEPGET_OPTIONS = "p:cn:t:e:o:m:l:h:u:x:";

    /** Options accepted by EepPost.main(), from its Getopt spec. */
    private static final String EEPPOST_OPTIONS = "p:cn:t:v:w:o:u:x:l:m:s:f:";

    /**
     * The help must mention each option as a standalone token, so a letter
     * that is part of a word (as in "outputFile") does not count.
     */
    private static void assertDocumentsOptions(String tool, String usage, String spec) {
        for (int i = 0; i < spec.length(); i++) {
            char opt = spec.charAt(i);
            if (opt == ':') continue;
            assertTrue(tool + " help does not document -" + opt,
                       Pattern.compile("(?<![A-Za-z0-9])-" + opt + "(?![A-Za-z0-9])").matcher(usage).find());
        }
    }

    @Test
    public void testEepGetUsageDocumentsEveryOption() {
        assertDocumentsOptions("eepget", EepGet.usageText(), EEPGET_OPTIONS);
    }

    @Test
    public void testEepGetUsageTimeouts() {
        String usage = EepGet.usageText();
        // -t is the inactivity timeout, not a generic or header timeout
        assertTrue(usage.contains("-t <value>           inactivity timeout in seconds (default "
                                 + (EepGet.DEFAULT_INACTIVITY_TIMEOUT / 1000) + ")"));
        assertTrue(usage.contains("(default " + EepGet.DEFAULT_NUM_RETRIES + ")"));
    }

    @Test
    public void testEepPostUsageDocumentsEveryOption() {
        assertDocumentsOptions("eeppost", EepPost.usage(), EEPPOST_OPTIONS);
    }

    @Test
    public void testEepPostUsageTimeouts() {
        String usage = EepPost.usage();
        // -t is the inactivity timeout, -v the header timeout, -u the username
        assertTrue(usage.contains("[-t inactivityTimeout]  (default "
                                 + (EepGet.DEFAULT_INACTIVITY_TIMEOUT / 1000) + " sec)"));
        assertTrue(usage.contains("[-v headerTimeout]  (default "
                                 + (EepGet.DEFAULT_CONNECT_TIMEOUT / 1000) + " sec)"));
        assertTrue(usage.contains("[-w totalTimeout]  (default unlimited)"));
        assertTrue(usage.contains("[-u username] [-x password] url"));
    }

    @Test
    public void testParseChunkLength() throws IOException {
        assertEquals(0, EepGet.parseChunkLength("0\r\n"));
        assertEquals(26, EepGet.parseChunkLength("1a\r\n"));
        assertEquals(26, EepGet.parseChunkLength("1A"));
        assertEquals(0xFFFFFFFFL, EepGet.parseChunkLength("ffffffff"));
    }

    @Test
    public void testParseChunkLengthSkipsExtensions() throws IOException {
        assertEquals(26, EepGet.parseChunkLength("1a;foo=bar\r\n"));
        assertEquals(26, EepGet.parseChunkLength("1a ;foo=\"bar baz\"\r\n"));
        assertEquals(0, EepGet.parseChunkLength("0;name=value\r\n"));
        assertEquals(255, EepGet.parseChunkLength("ff;a\r\n"));
    }

    @Test
    public void testParseChunkLengthRejectsGarbage() {
        for (String bad : new String[] {"", "zz", "1g", ";foo", "-1"}) {
            try {
                long rv = EepGet.parseChunkLength(bad);
                fail("expected IOException for [" + bad + "], got " + rv);
            } catch (IOException expected) {
                // expected
            }
        }
    }
}
