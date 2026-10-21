package i2p.susi.webmail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import i2p.susi.util.LogHelper;
import org.junit.Test;

/**
 * Tests the log form of a message id. A UIDL is a base64 blob of up to 70
 * characters, and a fetch logs one per message, so the full form buries the
 * page it is written on.
 *
 * @since 0.9.72+
 */
public class MailCacheShortIdTest {

    @Test
    public void testHeadOnly() {
        assertEquals("[abcdefgh]", LogHelper.shortId("abcdefghijklmnopqrstuvwxyz"));
        // short ids are already readable
        assertEquals("[ab]", LogHelper.shortId("ab"));
        assertEquals("[abcdefgh]", LogHelper.shortId("abcdefgh"));
    }

    @Test
    public void testMissingId() {
        assertEquals("[unknown]", LogHelper.shortId(null));
        assertEquals("unknown", LogHelper.head(null));
    }

    /**
     * A message that continues past the id writes its own brackets around it, so
     * the helper must not add a second pair — the doubled form is what a reader
     * would have to strip before grepping for the id.
     */
    @Test
    public void testHeadCarriesNoBrackets() {
        String uidl = "VUlEMTA0Mi0xMjQ4Mzk5MzE2";
        assertEquals("VUlEMTA0", LogHelper.head(uidl));
        assertEquals('[' + LogHelper.head(uidl) + ']', LogHelper.shortId(uidl));
    }

    /**
     * The bracketed head is what a reader can line up against the UIDL shown in
     * the webmail page, and it keeps a fetch line inside one log line.
     */
    @Test
    public void testStaysOnOneLine() {
        String uidl = "SnfiX2SaPn7ZHvl0Dc2s-EzYyZfskMZlnBVhSopkOW5Kd-JfZJo-ftke-XQNzaz4TNjJl";
        String out = LogHelper.shortId(uidl);
        assertTrue(out, out.length() <= 10);
        assertTrue(out, out.startsWith("[") && out.endsWith("]"));
    }

    /**
     * The head is also what appears in the cache filename, so the truncated id
     * still leads a reader to the stored message.
     */
    @Test
    public void testHeadMatchesTheCacheFilename() {
        String uidl = "VUlEMTA0Mi0xMjQ4Mzk5MzE2";
        String filename = "mail-" + uidl + ".hdr.txt.gz";
        String out = LogHelper.shortId(uidl);
        assertTrue(out, filename.contains(out.substring(1, out.length() - 1)));
    }
}
