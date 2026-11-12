package net.i2p.util;

import static org.junit.Assert.*;

import java.io.IOException;

import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Tests that the SSLEepGet constructors carry the response-size ceiling down to
 * the fetch. The plain-HTTP path has enforced that ceiling for years; the HTTPS
 * path had no way to ask for one, so an oversized or gzip-bombed reseed response
 * was buffered in full before its signature was ever checked.
 *
 * @since 0.9.72+
 */
public class SSLEepGetLimitTest {

    /** A fetch that can report the ceiling it was built with. */
    private static final class Probe extends SSLEepGet {

        /**
         * Build a direct fetch.
         *
         * @param ctx context for the fetch, source of its clock and stat registry
         * @param outputFile the file to write the body to
         * @param url the URL to fetch
         * @param maxSize the ceiling to carry, -1 for unlimited
         */
        Probe(I2PAppContext ctx, String outputFile, String url, long maxSize) {
            super(ctx, outputFile, url, maxSize, null);
        }

        /**
         * Build a proxied fetch.
         *
         * @param ctx context for the fetch, source of its clock and stat registry
         * @param type the proxy scheme, NONE for a direct connection
         * @param proxyHost the proxy host
         * @param proxyPort the proxy port
         * @param outputFile the file to write the body to
         * @param url the URL to fetch
         * @param maxSize the ceiling to carry, -1 for unlimited
         */
        Probe(I2PAppContext ctx, SSLEepGet.ProxyType type, String proxyHost, int proxyPort,
              String outputFile, String url, long maxSize) {
            super(ctx, type, proxyHost, proxyPort, outputFile, url, maxSize, null);
        }

        /**
         * Build a fetch with the older constructor, which asks for no ceiling.
         *
         * @param ctx context for the fetch, source of its clock and stat registry
         * @param outputFile the file to write the body to
         * @param url the URL to fetch
         */
        Probe(I2PAppContext ctx, String outputFile, String url) {
            super(ctx, outputFile, url);
        }

        /**
         * The ceiling the read loop enforces.
         *
         * @return the limit in bytes, -1 for unlimited
         */
        long limit() {
            return getMaxSize();
        }
    }

    /** Context with no configuration; the fetch is never run. */
    private static final I2PAppContext CTX = new I2PAppContext();

    /** A URL that is never contacted, only parsed. */
    private static final String URL = "https://reseed.example.invalid/reseed.su3";

    @Test
    public void testFileConstructorCarriesTheCeiling() {
        assertEquals(1024, new Probe(CTX, "/tmp/reseed-limit-test.tmp", URL, 1024).limit());
    }

    @Test
    public void testProxyConstructorCarriesTheCeiling() {
        assertEquals(1024, new Probe(CTX, SSLEepGet.ProxyType.NONE, null, -1,
                                     "/tmp/reseed-limit-test.tmp", URL, 1024).limit());
    }

    /**
     * The pre-existing constructors keep asking for no ceiling, so nothing that
     * relied on an unbounded download changes behaviour.
     */
    @Test
    public void testOldConstructorStaysUnlimited() {
        assertEquals(-1, new Probe(CTX, "/tmp/reseed-limit-test.tmp", URL).limit());
    }

    /**
     * The ceiling is enforced chunk by chunk as the body arrives, not only when the
     * fetch ends, so a gzip bomb is stopped before it is written out. A chunk that
     * lands exactly on the ceiling is accepted, one byte past it is not, and the
     * message names both numbers.
     *
     * @throws Exception not expected; the refusal is caught in the body
     */
    @Test
    public void testChunkPastTheCeilingIsRefused() throws Exception {
        SSLEepGet.checkSizeLimit(1024, 1000, 24);
        try {
            SSLEepGet.checkSizeLimit(1024, 1000, 25);
            fail("a chunk one byte past the ceiling was accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("1025"));
            assertTrue(expected.getMessage().contains("1024"));
        }
    }

    /**
     * No ceiling means no limit, so nothing is refused however large the total.
     *
     * @throws Exception not expected; an unlimited fetch refuses nothing
     */
    @Test
    public void testUnlimitedFetchAcceptsAnyChunk() throws Exception {
        SSLEepGet.checkSizeLimit(-1, Long.MAX_VALUE / 2, Integer.MAX_VALUE);
    }

    /**
     * A total past 2 GB must stay a long comparison - an int sum would overflow and
     * read as under the ceiling.
     */
    @Test
    public void testLargeTotalsAreNotTruncated() {
        try {
            SSLEepGet.checkSizeLimit(1L << 40, (1L << 40) - 1, Integer.MAX_VALUE);
            fail("a total past the ceiling was accepted");
        } catch (IOException expected) {
            // expected
        }
    }
}
