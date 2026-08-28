package org.klomp.snark.web;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the pure state-derivation helpers in {@link FetchAndAdd} that need no router:
 * the downloaded-bytes sentinel guard and the sanitized failure-message concatenation.
 *
 * @since 0.9.71+
 */
public class FetchAndAddTest {

    /**
     * Before the first byte the total is -1; the reported downloaded count must be 0, not a
     * bogus value derived from the -1 sentinel.
     */
    @Test
    public void testDownloadedUnknownTotal() {
        assertEquals(0, FetchAndAdd.downloaded(-1, -1));
        assertEquals(0, FetchAndAdd.downloaded(-1, 100));
    }

    @Test
    public void testDownloadedKnownTotal() {
        assertEquals(40, FetchAndAdd.downloaded(100, 60));
    }

    @Test
    public void testDownloadedComplete() {
        assertEquals(100, FetchAndAdd.downloaded(100, 0));
    }

    @Test
    public void testDownloadedRemainingNegative() {
        // remaining can be -1 while total is known (server gave a length but the final remaining
        // callback is not guaranteed); the download report simply reflects total
        assertEquals(101, FetchAndAdd.downloaded(100, -1));
    }

    @Test
    public void testAppendCausePlain() {
        Exception e = new Exception("boom");
        assertEquals("prefix: boom", FetchAndAdd.appendCause("prefix", e));
    }

    @Test
    public void testAppendCauseStripsHtml() {
        // user-supplied torrent content could carry markup; it must not reach the web table raw
        Exception e = new IllegalArgumentException("<script>alert(1)</script>bad");
        String out = FetchAndAdd.appendCause("prefix", e);
        assertFalse(out.contains("<script>"));
        assertFalse(out.contains("<"));
        assertTrue(out.contains("bad"));
    }

    @Test
    public void testAppendCauseNullMessage() {
        // stripHTML(null) yields "", so a null throwable message becomes an empty suffix
        assertEquals("prefix: ", FetchAndAdd.appendCause("prefix", new Exception()));
    }

    @Test
    public void testAppendCauseEmptyMessage() {
        assertEquals("prefix: ", FetchAndAdd.appendCause("prefix", new Exception("")));
    }
}
