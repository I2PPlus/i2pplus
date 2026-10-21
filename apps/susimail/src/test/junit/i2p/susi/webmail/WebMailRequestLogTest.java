package i2p.susi.webmail;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests the session id as it appears in the webmail log. A Jetty session id is
 * a 32-character hex blob, and it is written on every session event, so the
 * full form buries the event the reader is looking for.
 *
 * @since 0.9.72+
 */
public class WebMailRequestLogTest {

    @Test
    public void testSessionIdIsTruncated() {
        assertEquals("[01234567]", WebMail.shortSessionId("0123456789abcdef0123456789abcdef"));
    }

    @Test
    public void testShortIdIsKeptWhole() {
        assertEquals("[abc]", WebMail.shortSessionId("abc"));
        assertEquals("[01234567]", WebMail.shortSessionId("01234567"));
    }

    @Test
    public void testMissingIdStaysReadable() {
        assertEquals("[unknown]", WebMail.shortSessionId(null));
    }
}
