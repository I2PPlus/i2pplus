package net.i2p.i2ptunnel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Whether a request declares a body, read from the raw header block the tunnel
 * forwards verbatim.
 * <p>
 * The response thread used to ask only whether the client had sent any bytes
 * past its headers, and on "yes" it stopped keep-alive and pumped those bytes
 * at the backend as the tail of the request in flight. For a GET or HEAD that
 * declared no body there is no tail: the bytes are the client's next pipelined
 * request, which is what a kept-alive connection reads next. Measured live on
 * 2026-11-27, 19,956 of 20,217 eepsite responses began their copy with
 * keep-alive already off.
 *
 * @since 0.9.72+
 */
public class I2PTunnelHTTPServerBodyTest {

    /** A GET with no Content-Length has no body by definition. */
    @Test
    public void testGetWithoutLengthHasNoBody() {
        assertFalse(I2PTunnelHTTPServer.hasDeclaredBody("GET /a?x=1 HTTP/1.1\r\n" +
                                                       "Host: opentracker.skank.i2p\r\n" +
                                                       "\r\n"));
    }

    /** An explicit zero is a client saying there is nothing coming. */
    @Test
    public void testZeroLengthIsNoBody() {
        assertFalse(I2PTunnelHTTPServer.hasDeclaredBody("POST /a HTTP/1.1\r\n" +
                                                       "Content-Length: 0\r\n" +
                                                       "\r\n"));
    }

    /** A positive length is a body, whatever the method. */
    @Test
    public void testPositiveLengthIsABody() {
        assertTrue(I2PTunnelHTTPServer.hasDeclaredBody("GET /a HTTP/1.1\r\n" +
                                                      "Content-Length: 17\r\n" +
                                                      "\r\n"));
    }

    /**
     * The header name is case-insensitive and its value may be padded, so a
     * body cannot be missed because the client wrote the name differently.
     */
    @Test
    public void testHeaderNameCaseAndPaddingIgnored() {
        assertTrue(I2PTunnelHTTPServer.hasDeclaredBody("POST /a HTTP/1.1\r\n" +
                                                      "content-LENGTH:   5  \r\n" +
                                                      "\r\n"));
    }

    /** A chunked body has no length at all and is still a body. */
    @Test
    public void testChunkedIsABody() {
        assertTrue(I2PTunnelHTTPServer.hasDeclaredBody("POST /a HTTP/1.1\r\n" +
                                                      "Transfer-Encoding: chunked\r\n" +
                                                      "\r\n"));
    }

    /**
     * A length that is not plain digits cannot be sized here, so it counts as a
     * body and gets forwarded: the framing check that runs before this rejects
     * the non-canonical forms, and guessing it away would drop bytes.
     */
    @Test
    public void testNonCanonicalLengthCountsAsABody() {
        assertTrue(I2PTunnelHTTPServer.hasDeclaredBody("POST /a HTTP/1.1\r\n" +
                                                      "Content-Length: +13\r\n" +
                                                      "\r\n"));
        assertTrue(I2PTunnelHTTPServer.hasDeclaredBody("POST /a HTTP/1.1\r\n" +
                                                      "Content-Length: 99999999999999999999\r\n" +
                                                      "\r\n"));
    }

    /**
     * An absolute-form request line carries colons of its own in the URL, and a
     * header merely named like Content-Length is not Content-Length.
     */
    @Test
    public void testLookalikesAreNotTheHeader() {
        assertFalse(I2PTunnelHTTPServer.hasDeclaredBody("GET http://host:8080/a HTTP/1.1\r\n" +
                                                       "X-Content-Length: 5\r\n" +
                                                       "\r\n"));
    }

    /**
     * Missing or empty headers are treated as a body: the safe answer when the
     * request cannot be read is to keep forwarding it.
     */
    @Test
    public void testUnreadableHeadersCountAsABody() {
        assertTrue(I2PTunnelHTTPServer.hasDeclaredBody(null));
        assertTrue(I2PTunnelHTTPServer.hasDeclaredBody(""));
    }

    /**
     * A second Content-Length naming the same length is still one body; the
     * duplicate itself is the framing check's business, not this one's.
     */
    @Test
    public void testRepeatedSameLengthIsStillABody() {
        assertTrue(I2PTunnelHTTPServer.hasDeclaredBody("POST /a HTTP/1.1\r\n" +
                                                      "Content-Length: 4\r\n" +
                                                      "Content-Length: 4\r\n" +
                                                      "\r\n"));
        assertFalse(I2PTunnelHTTPServer.hasDeclaredBody("GET /a HTTP/1.1\r\n" +
                                                       "Accept: */*\r\n" +
                                                       "User-Agent: I2PSocketEepGet\r\n" +
                                                       "\r\n"));
    }
}
