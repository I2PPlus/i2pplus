package net.i2p.router.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Host-header decisions, the layer that stops DNS rebinding. The defect being
 * pinned is the empty allowlist: an empty set used to mean "allow every Host
 * header", so a console that had nothing configured - or one that had been widened
 * by a wildcard bind - accepted a page on an attacker's hostname that resolves to
 * the router, and that page is same-origin as far as the browser is concerned.
 *
 * @since 0.9.72+
 */
public class HostCheckHostDecisionTest {

    /** A configured allowlist, as a normal LAN console would have. */
    private static final Set<String> ALLOWED = new HashSet<>(
        Arrays.asList("localhost", "127.0.0.1", "::1", "myrouter.lan"));

    /**
     * Runs the host-check decisions; it holds no state.
     *
     * @since 0.9.72+
     */
    public HostCheckHostDecisionTest() {}

    /**
     * The defect: nothing configured is not the same as everything allowed. A
     * hostname is only believed when somebody named it.
     */
    @Test
    public void testAnEmptyAllowlistDoesNotAdmitEveryName() {
        assertFalse(HostCheckHandler.hostAllowed("evil.example:7657",
                                                 Collections.<String>emptySet()));
    }

    /**
     * A hostname the operator named is accepted, with the port removed first.
     */
    @Test
    public void testANamedHostIsAccepted() {
        assertTrue(HostCheckHandler.hostAllowed("myrouter.lan:7657", ALLOWED));
    }

    /**
     * Host headers are case-insensitive, so an allowlist entry written as
     * "MyRouter.lan" and a header written as "MYROUTER.LAN" have to meet somewhere:
     * both sides are folded, the names as they are stored and the header as it is
     * read.
     */
    @Test
    public void testTheMatchIgnoresCase() {
        Set<String> folded = HostCheckHandler.listenHosts(
            Collections.<String>emptySet(), "MyRouter.lan");
        assertTrue(folded.contains("myrouter.lan"));
        assertTrue(HostCheckHandler.hostAllowed("MYROUTER.LAN:7657", folded));
    }

    /**
     * A hostname nobody named is refused even against a non-empty allowlist - the
     * rebinding case, where the name resolves to the router and the browser treats
     * the page as same-origin.
     */
    @Test
    public void testAnUnnamedHostIsRefused() {
        assertFalse(HostCheckHandler.hostAllowed("evil.example:7657", ALLOWED));
        assertFalse(HostCheckHandler.hostAllowed("rebind.localhost.example:80", ALLOWED));
    }

    /**
     * Every numeric address stays allowed, as it has been all along: the check is
     * over hostnames, which is the only thing a rebinding attack controls.
     */
    @Test
    public void testEveryNumericAddressIsAccepted() {
        Set<String> none = Collections.emptySet();
        assertTrue(HostCheckHandler.hostAllowed("192.168.1.3:7657", none));
        assertTrue(HostCheckHandler.hostAllowed("10.0.0.1", none));
        assertTrue(HostCheckHandler.hostAllowed("[2001:db8::1]:7657", none));
    }

    /**
     * The six authorities the console is normally opened by are accepted whatever
     * is configured, so a mis-set allowlist cannot lock the operator out of the
     * machine the console is running on.
     */
    @Test
    public void testTheOrdinaryLocalAuthoritiesAreAccepted() {
        Set<String> none = Collections.emptySet();
        assertTrue(HostCheckHandler.hostAllowed("localhost:7657", none));
        assertTrue(HostCheckHandler.hostAllowed("127.0.0.1:7657", none));
        assertTrue(HostCheckHandler.hostAllowed("[::1]:7657", none));
        assertTrue(HostCheckHandler.hostAllowed("localhost:7667", none));
        assertTrue(HostCheckHandler.hostAllowed("127.0.0.1:7667", none));
        assertTrue(HostCheckHandler.hostAllowed("[::1]:7667", none));
    }

    /**
     * An HTTP/1.0 request has no Host header at all, and refusing one would break
     * every old client without protecting anything.
     */
    @Test
    public void testNoHostHeaderIsAccepted() {
        assertTrue(HostCheckHandler.hostAllowed(null, ALLOWED));
    }

    /**
     * A host header that is only a port, or otherwise has no name in it, is not a
     * name anybody named, so it is refused rather than matched against "".
     */
    @Test
    public void testAnEmptyHostNameIsRefused() {
        assertFalse(HostCheckHandler.hostAllowed(":7657", ALLOWED));
    }
}
