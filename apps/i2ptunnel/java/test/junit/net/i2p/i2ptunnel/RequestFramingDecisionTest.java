package net.i2p.i2ptunnel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The request framing the HTTP server tunnel accepts before it forwards a body
 * to the backend.
 *
 * <p>The tunnel never frames the body itself - it forwards the bytes - so a body
 * length the backend reads differently from a canonical parser lets the sender
 * put a second request where the tunnel sees body bytes. The backend trusts the
 * X-I2P-Dest header the tunnel adds, so that second request can carry one of the
 * sender's own and name a client that never connected. Jetty 9.3.30, the
 * container the eepsite runs on, reads <code>Content-Length: +13</code> as 13;
 * Jetty 12 answers 400, and so now does the tunnel.
 *
 * @since 0.9.72+
 */
public class RequestFramingDecisionTest {

    /** Runs the decisions; it holds no state. */
    public RequestFramingDecisionTest() {}

    /** A plain decimal length is canonical. */
    @Test
    public void aPlainLengthIsAccepted() {
        assertNull(I2PTunnelHTTPServer.framingError(headers("Content-Length", "13")));
        assertNull(I2PTunnelHTTPServer.framingError(headers("Content-Length", "0")));
    }

    /**
     * A leading plus is the shape Jetty 9.3.30 reads as a number and Jetty 12
     * refuses; the lab used it to smuggle a second request past the tunnel.
     */
    @Test
    public void aSignedLengthIsRefused() {
        assertNotNull(I2PTunnelHTTPServer.framingError(headers("Content-Length", "+13")));
        assertNotNull(I2PTunnelHTTPServer.framingError(headers("Content-Length", "-1")));
        assertNotNull(I2PTunnelHTTPServer.framingError(headers("Content-Length", "13 4")));
        assertNotNull(I2PTunnelHTTPServer.framingError(headers("Content-Length", "")));
        assertNotNull(I2PTunnelHTTPServer.framingError(headers("Content-Length", "0x13")));
    }

    /** Two Content-Length lines with different values are two claims about one body. */
    @Test
    public void conflictingLengthsAreRefused() {
        assertNotNull(I2PTunnelHTTPServer.framingError(headers("Content-Length", "13", "14")));
        Map<String, List<String>> three = headers("Content-Length", "13", "13", "14");
        assertNotNull(I2PTunnelHTTPServer.framingError(three));
    }

    /**
     * The same value on two lines is one message length, not a disagreement -
     * RFC 9112 6.3.3 - and a client that sends it reached the backend before
     * this check existed and was accepted there. The shape is still checked, so
     * two copies of a length parsers disagree about are still refused.
     */
    @Test
    public void repeatedIdenticalLengthIsAccepted() {
        assertNull(I2PTunnelHTTPServer.framingError(headers("Content-Length", "13", "13")));
        Map<String, List<String>> signedTwice = headers("Content-Length", "+13", "+13");
        assertNotNull(I2PTunnelHTTPServer.framingError(signedTwice));
    }

    /** A request with no body length at all is fine. */
    @Test
    public void noFramingIsAccepted() {
        assertNull(I2PTunnelHTTPServer.framingError(new HashMap<String, List<String>>()));
        assertNull(I2PTunnelHTTPServer.framingError(headers("Host", "eepsite.i2p")));
        assertNull(I2PTunnelHTTPServer.framingError(null));
    }

    /**
     * The parsed map keeps header names as the client sent them, so the check has
     * to find the length whatever case was used.
     */
    @Test
    public void headerNameCaseDoesNotMatter() {
        assertNotNull(I2PTunnelHTTPServer.framingError(headers("content-length", "+13")));
        assertNotNull(I2PTunnelHTTPServer.framingError(headers("CONTENT-LENGTH", "+13")));
        assertNotNull(I2PTunnelHTTPServer.framingError(headers("Content-Length", "+13")));
    }

    /** The reason names the header, so the 400 is traceable in the log. */
    @Test
    public void theReasonNamesTheHeader() {
        String signed = I2PTunnelHTTPServer.framingError(headers("Content-Length", "+13"));
        String twice = I2PTunnelHTTPServer.framingError(headers("Content-Length", "13", "14"));
        assertTrue(signed.contains("Content-Length"));
        assertTrue(twice.contains("Content-Length"));
        // and both claims, so the reader can see what the sender sent
        assertTrue(twice.contains("\"13\""));
        assertTrue(twice.contains("\"14\""));
    }

    /** Digits only is what counts as a length. */
    @Test
    public void onlyDigitsMakeALength() {
        assertTrue(I2PTunnelHTTPServer.isCanonicalLength("0"));
        assertTrue(I2PTunnelHTTPServer.isCanonicalLength("007"));
        assertTrue(I2PTunnelHTTPServer.isCanonicalLength("9223372036854775807"));
        assertFalse(I2PTunnelHTTPServer.isCanonicalLength("+7"));
        assertFalse(I2PTunnelHTTPServer.isCanonicalLength(" 7"));
        assertFalse(I2PTunnelHTTPServer.isCanonicalLength("7 "));
        assertFalse(I2PTunnelHTTPServer.isCanonicalLength("7a"));
        assertFalse(I2PTunnelHTTPServer.isCanonicalLength(""));
        assertFalse(I2PTunnelHTTPServer.isCanonicalLength(null));
    }

    /**
     * A header map with one name and the values given, as the parser builds it.
     *
     * @param name the header name as the client sent it
     * @param values the values, one per header line
     * @return the map the parser would have produced
     */
    private static Map<String, List<String>> headers(String name, String... values) {
        Map<String, List<String>> h = new HashMap<>();
        for (String value : values) {
            I2PTunnelHTTPServer.addEntry(h, name, value);
        }
        return h;
    }
}
