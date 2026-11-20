package net.i2p.servlet.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;

import net.i2p.util.Log;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Origin-check decisions for POST CSRF protection, for the rule the console and
 * every webapp share. The load-bearing case is DNS rebinding: a hostname whose A
 * record points at 127.0.0.1 must NOT be treated as loopback, so the localhost
 * allowance cannot be turned into a CSRF bypass. That only holds because the
 * comparison is textual and never resolves a header.
 *
 * @since 0.9.72+
 */
public class OriginCheckDecisionTest {

    /** Runs the origin decisions; it holds no state. */
    public OriginCheckDecisionTest() {}

    /** A same-origin POST, host and port identical, is allowed. */
    @Test
    public void testSameOriginAllowed() {
        assertTrue(OriginCheck.isAllowed("http://127.0.0.1:7657", "127.0.0.1:7657", false));
    }

    /**
     * The spelling a browser actually sends for the console's own forms: the
     * address book, SusiMail and I2PSnark all post to the name in their Host
     * header, so a rule that refused this pair locked every webapp's forms out.
     */
    @Test
    public void testLocalhostSameOriginAllowed() {
        assertTrue(OriginCheck.isAllowed("http://localhost:7657", "localhost:7657", false));
        assertTrue(OriginCheck.isAllowed("https://localhost:7667", "localhost:7667", true));
    }

    /** A cross-origin POST is rejected. */
    @Test
    public void testCrossOriginRejected() {
        assertFalse(OriginCheck.isAllowed("http://evil.example:80", "127.0.0.1:7657", false));
    }

    /**
     * The rebinding case: evil.example resolves to 127.0.0.1, so a check that
     * resolved the header would see both sides as loopback and allow the POST.
     * Textually it is not loopback, so it is rejected.
     */
    @Test
    public void testReboundHostnameNotTreatedAsLoopback() {
        assertFalse(OriginCheck.isAllowed("http://rebind.localhost.example:80",
                                          "localhost:7657", false));
    }

    /**
     * Same as above with a name that really does resolve to 127.0.0.1 on this
     * host, so the distinction being tested is resolution versus literal
     * comparison, not whether the name happens to exist.
     */
    @Test
    public void testHostnameResolvingToLoopbackIsStillRejected() {
        assertTrue(OriginCheck.isAllowed("http://localhost.localdomain:7657",
                                         "localhost.localdomain:7657", false));
        assertFalse(OriginCheck.isAllowed("http://localhost.localdomain:7657",
                                          "localhost:7657", false));
        assertFalse(OriginCheck.isLoopbackName("localhost.localdomain"));
    }

    /** One side loopback is not enough - both must be. */
    @Test
    public void testLoopbackOriginWithExternalHostRejected() {
        assertFalse(OriginCheck.isAllowed("http://localhost:7657", "myrouter.example:7657", false));
    }

    /** A POST to an eepsite host must not be unlocked by a loopback Origin. */
    @Test
    public void testLoopbackOriginAgainstEepsiteHostRejected() {
        assertFalse(OriginCheck.isAllowed("http://localhost:7657",
                                          "abcdefghijklmnopqrstuvwxyz234567.b32.i2p:80",
                                          false));
    }

    /** localhost to localhost is the intended allowance. */
    @Test
    public void testLocalhostToLoopbackAllowed() {
        assertTrue(OriginCheck.isAllowed("http://localhost:7657", "localhost:7657", false));
        assertTrue(OriginCheck.isAllowed("http://localhost:7657", "127.0.0.1:7657", false));
    }

    /** Any address in 127/8 counts as loopback, per the existing policy. */
    @Test
    public void testLoopbackRangeAllowed() {
        assertTrue(OriginCheck.isAllowed("http://127.0.0.53:7657", "127.4.4.4:7657", false));
    }

    /**
     * The allowance is for an address written out in full inside 127/8, not for
     * any name that begins with those three digits. Somebody else's domain
     * called "127.somewhere.example" passed beside a real "127.0.0.1" with the
     * ports matching, and it is the name in the header that is compared here -
     * nothing resolves it, which is the whole reason rebinding is refused.
     */
    @Test
    public void testHostnameStartingWithLoopbackDigitsIsNotLoopback() {
        assertFalse(OriginCheck.isLoopbackName("127.somewhere.example"));
        assertFalse(OriginCheck.isLoopbackName("127.0.0.1.nip.io"));
        assertFalse(OriginCheck.isLoopbackName("127.1"));
        assertFalse(OriginCheck.isAllowed("http://127.somewhere.example:7657",
                                          "127.0.0.1:7657", false));
        // their own origin still matches their own host, as it should
        assertTrue(OriginCheck.isAllowed("http://127.somewhere.example:7657",
                                         "127.somewhere.example:7657", false));
    }

    /** IPv6 loopback in its written forms, including the bracketed form. */
    @Test
    public void testIPv6LoopbackAllowed() {
        assertTrue(OriginCheck.isAllowed("http://[::1]:7657", "[::1]:7657", false));
        assertTrue(OriginCheck.isAllowed("http://[::1]:7657", "[0:0:0:0:0:0:0:1]:7657", false));
    }

    /** A non-loopback IPv6 address is not loopback. */
    @Test
    public void testIPv6NonLoopbackRejected() {
        assertFalse(OriginCheck.isAllowed("http://[fd00::1]:7657", "[::1]:7657", false));
    }

    /** Hostname comparison ignores case. */
    @Test
    public void testHostnameComparisonIsCaseInsensitive() {
        assertTrue(OriginCheck.isAllowed("http://LocalHost:7657", "localhost:7657", false));
    }

    /** Same host but a different port is not same-origin. */
    @Test
    public void testPortMismatchRejected() {
        assertFalse(OriginCheck.isAllowed("http://localhost:7657", "localhost:7777", false));
    }

    /**
     * Both sides are loopback but the ports differ, so a page served from some
     * other local port must not be able to drive the console. The localhost
     * allowance covers differing spellings of the same host, not differing ports.
     */
    @Test
    public void testLocalhostAcrossPortsRejected() {
        assertFalse(OriginCheck.isAllowed("http://localhost:8080", "127.0.0.1:7657", false));
        assertFalse(OriginCheck.isAllowed("http://127.0.0.1:631/", "localhost:7657", false));
    }

    /** An absent Origin is a plain form POST and is allowed. */
    @Test
    public void testNoOriginAllowed() {
        assertTrue(OriginCheck.isAllowed(null, "127.0.0.1:7657", false));
        assertTrue(OriginCheck.isAllowed("", "127.0.0.1:7657", false));
    }

    /**
     * Origin: null names no origin. A sandboxed frame, a data: URL or a blob: page
     * sends it, and so does a privacy-hardened browser on a plain same-origin form
     * POST - LibreWolf with uMatrix, and Firefox's tracking-protection origin
     * string cleanup, both send it for a request to the console's own host. It is
     * allowed here because the CSRF token the form carries is what decides, and
     * refusing it locks the operator's own forms out. A POST with no Origin header
     * at all is not a browser POST and stays allowed for the nonce and password
     * checks behind this one.
     */
    @Test
    public void testNullOriginAllowed() {
        assertTrue(OriginCheck.isAllowed("null", "127.0.0.1:7657", false));
        assertTrue(OriginCheck.isAllowed("NULL", "localhost:7657", false));
        assertTrue(OriginCheck.isAllowed("null", "localhost:7657", false));
    }

    /** An absent Host header cannot be judged, and is allowed as before. */
    @Test
    public void testNoHostHeaderAllowed() {
        assertTrue(OriginCheck.isAllowed("http://evil.example", null, false));
    }

    /** An unknown Origin scheme is rejected rather than waved through. */
    @Test
    public void testUnknownSchemeRejected() {
        assertFalse(OriginCheck.isAllowed("javascript://localhost", "localhost:7657", false));
        assertFalse(OriginCheck.isAllowed("ftp://localhost:7657", "localhost:7657", false));
    }

    /** A malformed port does not throw its way past the check. */
    @Test
    public void testMalformedPortRejectedNotThrown() {
        assertFalse(OriginCheck.isAllowed("http://localhost:notaport", "localhost:7657", false));
    }

    /** The secure flag only supplies the default port when Host omits one. */
    @Test
    public void testSecureDefaultPort() {
        assertTrue(OriginCheck.isAllowed("https://localhost", "localhost", true));
        assertFalse(OriginCheck.isAllowed("https://localhost", "localhost", false));
    }

    /** isLoopbackName() is textual: a name that resolves to loopback is not loopback. */
    @Test
    public void testLoopbackNameIsLiteralNotResolved() {
        assertTrue(OriginCheck.isLoopbackName("localhost"));
        assertTrue(OriginCheck.isLoopbackName("LOCALHOST"));
        assertTrue(OriginCheck.isLoopbackName("127.0.0.1"));
        assertTrue(OriginCheck.isLoopbackName("::1"));
        assertFalse(OriginCheck.isLoopbackName(null));
        assertFalse(OriginCheck.isLoopbackName(""));
        assertFalse(OriginCheck.isLoopbackName("localhost.example"));
        assertFalse(OriginCheck.isLoopbackName("0127.0.0.1"));
        assertFalse(OriginCheck.isLoopbackName("fd00::1"));
    }

    /**
     * The entry point the webapps call, read off the request: the same decision
     * as the three-argument form, with the headers and the TLS flag taken from
     * the request being guarded.
     */
    @Test
    public void testRequestOverloadUsesTheRequestHeaders() {
        HttpServletRequest same = request("http://localhost:7657", "localhost:7657", false);
        assertTrue(OriginCheck.isAllowed(same));
        HttpServletRequest foreign = request("http://evil.example:80", "localhost:7657", false);
        assertFalse(OriginCheck.isAllowed(foreign));
    }

    /**
     * A refusal is reported at error level, with the pair that failed beside it,
     * so an operator can tell a browser from a proxy from a foreign page without
     * the source open.
     */
    @Test
    public void testDenialIsLoggedAtErrorLevel() {
        Log log = mock(Log.class);
        when(log.shouldLog(Log.ERROR)).thenReturn(true);
        OriginCheck.logDenial(log, request("http://evil.example:80", "localhost:7657", false));
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(log).error(msg.capture());
        assertTrue("the Origin was not echoed", msg.getValue().contains("http://evil.example:80"));
        assertTrue("the Host was not echoed", msg.getValue().contains("localhost:7657"));
        assertTrue("the path was not echoed", msg.getValue().contains("/susidns/config"));
    }

    /**
     * The headers are attacker-chosen, so they are stripped of markup before
     * they reach the console's log page, and an absent header is named rather
     * than printed as the word null.
     */
    @Test
    public void testDenialStripsMarkupAndNamesMissingHeaders() {
        Log log = mock(Log.class);
        when(log.shouldLog(Log.ERROR)).thenReturn(true);
        OriginCheck.logDenial(log, request("<script>x</script>", null, false));
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(log).error(msg.capture());
        assertFalse("markup reached the log", msg.getValue().contains("<script>"));
        assertTrue("an absent Host was printed as null", msg.getValue().contains("not sent"));
    }

    /**
     * A refusal at the level the log is not keeping is not written at all, so
     * the guard matches the call.
     */
    @Test
    public void testDenialSkippedBelowTheLoggedLevel() {
        Log log = mock(Log.class);
        when(log.shouldLog(Log.ERROR)).thenReturn(false);
        OriginCheck.logDenial(log, request("http://evil.example:80", "localhost:7657", false));
        verify(log, never()).error(anyString());
    }

    /**
     * A request carrying the three values the check reads.
     *
     * @param origin the Origin header, null for a header that was not sent
     * @param host the Host header
     * @param secure whether the request arrived over TLS
     * @return the request to hand to the check
     */
    private static HttpServletRequest request(String origin, String host, boolean secure) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Origin")).thenReturn(origin);
        when(req.getHeader("Host")).thenReturn(host);
        when(req.getRequestURI()).thenReturn("/susidns/config");
        when(req.isSecure()).thenReturn(secure);
        return req;
    }
}
