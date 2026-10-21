package i2p.susi.webmail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
        assertEquals("[abcdefgh]", MailCache.shortId("abcdefghijklmnopqrstuvwxyz"));
        // short ids are already readable
        assertEquals("[ab]", MailCache.shortId("ab"));
        assertEquals("[abcdefgh]", MailCache.shortId("abcdefgh"));
    }

    @Test
    public void testMissingId() {
        assertEquals("[unknown]", MailCache.shortId(null));
    }

    /**
     * The bracketed head is what a reader can line up against the UIDL shown in
     * the webmail page, and it keeps a fetch line inside one log line.
     */
    @Test
    public void testStaysOnOneLine() {
        String uidl = "SnfiX2SaPn7ZHvl0Dc2s-EzYyZfskMZlnBVhSopkOW5Kd-JfZJo-ftke-XQNzaz4TNjJl";
        String out = MailCache.shortId(uidl);
        assertTrue(out, out.length() <= 10);
        assertTrue(out, out.startsWith("[") && out.endsWith("]"));
    }
}
