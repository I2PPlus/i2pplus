package net.i2p.router.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Origin-check decisions for POST CSRF protection. The load-bearing case is DNS
 * rebinding: a hostname whose A record points at 127.0.0.1 must NOT be treated as
 * loopback, so the localhost allowance cannot be turned into a CSRF bypass. That
 * only holds because the comparison is textual and never resolves a header.
 */
public class HostCheckOriginDecisionTest {

    /** A same-origin POST, host and port identical, is allowed. */
    @Test
    public void testSameOriginAllowed() {
        assertTrue(HostCheckHandler.originAllowed("http://127.0.0.1:7657",
                                                  "127.0.0.1:7657", false));
    }

    /** A cross-origin POST is rejected. */
    @Test
    public void testCrossOriginRejected() {
        assertFalse(HostCheckHandler.originAllowed("http://evil.example:80",
                                                  "127.0.0.1:7657", false));
    }

    /**
     * The rebinding case: evil.example resolves to 127.0.0.1, so a check that
     * resolved the header would see both sides as loopback and allow the POST.
     * Textually it is not loopback, so it is rejected.
     */
    @Test
    public void testReboundHostnameNotTreatedAsLoopback() {
        assertFalse(HostCheckHandler.originAllowed("http://rebind.localhost.example:80",
                                                  "localhost:7657", false));
    }

    /**
     * Same as above with a name that really does resolve to 127.0.0.1 on this
     * host, so the distinction being tested is resolution versus literal
     * comparison, not whether the name happens to exist.
     */
    @Test
    public void testHostnameResolvingToLoopbackIsStillRejected() {
        assertTrue(HostCheckHandler.originAllowed("http://localhost.localdomain:7657",
                                                  "localhost.localdomain:7657", false));
        assertFalse(HostCheckHandler.originAllowed("http://localhost.localdomain:7657",
                                                  "localhost:7657", false));
        assertFalse(HostCheckHandler.isLoopbackName("localhost.localdomain"));
    }

    /** One side loopback is not enough - both must be. */
    @Test
    public void testLoopbackOriginWithExternalHostRejected() {
        assertFalse(HostCheckHandler.originAllowed("http://localhost:7657",
                                                  "myrouter.example:7657", false));
    }

    /** A POST to an eepsite host must not be unlocked by a loopback Origin. */
    @Test
    public void testLoopbackOriginAgainstEepsiteHostRejected() {
        assertFalse(HostCheckHandler.originAllowed("http://localhost:7657",
                                                  "abcdefghijklmnopqrstuvwxyz234567.b32.i2p:80",
                                                  false));
    }

    /** localhost to localhost is the intended allowance. */
    @Test
    public void testLocalhostToLoopbackAllowed() {
        assertTrue(HostCheckHandler.originAllowed("http://localhost:7657",
                                                  "localhost:7657", false));
        assertTrue(HostCheckHandler.originAllowed("http://localhost:7657",
                                                  "127.0.0.1:7657", false));
    }

    /** Any address in 127/8 counts as loopback, per the existing policy. */
    @Test
    public void testLoopbackRangeAllowed() {
        assertTrue(HostCheckHandler.originAllowed("http://127.0.0.53:7657",
                                                  "127.4.4.4:7657", false));
    }

    /** IPv6 loopback in its written forms, including the bracketed form. */
    @Test
    public void testIPv6LoopbackAllowed() {
        assertTrue(HostCheckHandler.originAllowed("http://[::1]:7657", "[::1]:7657", false));
        assertTrue(HostCheckHandler.originAllowed("http://[::1]:7657",
                                                  "[0:0:0:0:0:0:0:1]:7657", false));
    }

    /** A non-loopback IPv6 address is not loopback. */
    @Test
    public void testIPv6NonLoopbackRejected() {
        assertFalse(HostCheckHandler.originAllowed("http://[fd00::1]:7657",
                                                  "[::1]:7657", false));
    }

    /** Hostname comparison ignores case. */
    @Test
    public void testHostnameComparisonIsCaseInsensitive() {
        assertTrue(HostCheckHandler.originAllowed("http://LocalHost:7657",
                                                  "localhost:7657", false));
    }

    /** Same host but a different port is not same-origin. */
    @Test
    public void testPortMismatchRejected() {
        assertFalse(HostCheckHandler.originAllowed("http://localhost:7657",
                                                  "localhost:7777", false));
    }

    /**
     * Both sides are loopback but the ports differ, so a page served from some
     * other local port must not be able to drive the console. The localhost
     * allowance covers differing spellings of the same host, not differing ports.
     */
    @Test
    public void testLocalhostAcrossPortsRejected() {
        assertFalse(HostCheckHandler.originAllowed("http://localhost:8080",
                                                  "127.0.0.1:7657", false));
        assertFalse(HostCheckHandler.originAllowed("http://127.0.0.1:631/",
                                                  "localhost:7657", false));
    }

    /** An absent Origin is a plain form POST and is allowed. */
    @Test
    public void testNoOriginAllowed() {
        assertTrue(HostCheckHandler.originAllowed(null, "127.0.0.1:7657", false));
        assertTrue(HostCheckHandler.originAllowed("", "127.0.0.1:7657", false));
    }

    /** An absent Host header cannot be judged, and is allowed as before. */
    @Test
    public void testNoHostHeaderAllowed() {
        assertTrue(HostCheckHandler.originAllowed("http://evil.example", null, false));
    }

    /** An unknown Origin scheme is rejected rather than waved through. */
    @Test
    public void testUnknownSchemeRejected() {
        assertFalse(HostCheckHandler.originAllowed("javascript://localhost",
                                                  "localhost:7657", false));
        assertFalse(HostCheckHandler.originAllowed("ftp://localhost:7657",
                                                  "localhost:7657", false));
    }

    /** A malformed port does not throw its way past the check. */
    @Test
    public void testMalformedPortRejectedNotThrown() {
        assertFalse(HostCheckHandler.originAllowed("http://localhost:notaport",
                                                  "localhost:7657", false));
    }

    /** The secure flag only supplies the default port when Host omits one. */
    @Test
    public void testSecureDefaultPort() {
        assertTrue(HostCheckHandler.originAllowed("https://localhost", "localhost", true));
        assertFalse(HostCheckHandler.originAllowed("https://localhost", "localhost", false));
    }

    /** isLoopbackName() is textual: a name that resolves to loopback is not loopback. */
    @Test
    public void testLoopbackNameIsLiteralNotResolved() {
        assertTrue(HostCheckHandler.isLoopbackName("localhost"));
        assertTrue(HostCheckHandler.isLoopbackName("LOCALHOST"));
        assertTrue(HostCheckHandler.isLoopbackName("127.0.0.1"));
        assertTrue(HostCheckHandler.isLoopbackName("::1"));
        assertFalse(HostCheckHandler.isLoopbackName(null));
        assertFalse(HostCheckHandler.isLoopbackName(""));
        assertFalse(HostCheckHandler.isLoopbackName("localhost.example"));
        assertFalse(HostCheckHandler.isLoopbackName("0127.0.0.1"));
        assertFalse(HostCheckHandler.isLoopbackName("fd00::1"));
    }
}
