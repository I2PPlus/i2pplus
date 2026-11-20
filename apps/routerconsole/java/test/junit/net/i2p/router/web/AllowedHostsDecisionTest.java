package net.i2p.router.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Which host names the console believes, given the addresses it bound to. The
 * defect being pinned is the wildcard bind: a console listening on 0.0.0.0 or ::
 * used to be handed an empty allowlist, and an empty allowlist meant "every Host
 * header is fine", so the DNS-rebinding check was switched off for exactly the
 * operators who put the console on a network - the ones it is worth having for.
 *
 * @since 0.9.72+
 */
public class AllowedHostsDecisionTest {

    /** The four loopback names every bind is expected to allow. */
    private static final String[] LOOPBACK = {
        "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1"
    };

    /**
     * Runs the listen-host decisions; it holds no state.
     *
     * @since 0.9.72+
     */
    public AllowedHostsDecisionTest() {}

    /**
     * The defect: a wildcard bind widened the set to "anything at all" instead of
     * leaving the localhost names and the allowlist in place.
     */
    @Test
    public void testAWildcardBindStillNamesTheLocalHosts() {
        for (String wildcard : new String[] {"0.0.0.0", "::", "0:0:0:0:0:0:0:0"}) {
            Set<String> allowed = HostCheckHandler.listenHosts(
                Collections.singleton(wildcard), null);
            assertFalse("a wildcard bind left the allowlist empty", allowed.isEmpty());
            for (String h : LOOPBACK) {
                assertTrue(wildcard + " dropped " + h, allowed.contains(h));
            }
        }
    }

    /**
     * A wildcard bind widens which of the router's addresses answer, not which
     * names are believed, so an attacker's hostname is still refused.
     */
    @Test
    public void testAWildcardBindDoesNotAdmitEveryName() {
        Set<String> allowed = HostCheckHandler.listenHosts(
            Collections.singleton("0.0.0.0"), "myrouter.i2p");
        assertFalse(allowed.contains("evil.example"));
        assertTrue(allowed.contains("myrouter.i2p"));
    }

    /**
     * The operator's property is read whatever was bound, and both separators the
     * property allows are honoured.
     */
    @Test
    public void testTheAllowedHostsPropertyIsTokenised() {
        Set<String> allowed = HostCheckHandler.listenHosts(
            Collections.singleton("127.0.0.1"), "one.i2p, two.i2p three.i2p");
        assertTrue(allowed.contains("one.i2p"));
        assertTrue(allowed.contains("two.i2p"));
        assertTrue(allowed.contains("three.i2p"));
    }

    /**
     * An entry written with a port still matches. The header is compared with its
     * port removed, so an entry that keeps one - the name as it sits in the browser's
     * address bar - is in the set and matches nothing, and the operator who copies it
     * off the URL is refused with no hint why.
     */
    @Test
    public void testAnEntryWrittenWithAPortStillMatches() {
        Set<String> allowed = HostCheckHandler.listenHosts(
            Collections.singleton("0.0.0.0"), "router.lan:7657, lan-2.lan:8080");
        assertTrue("the name copied out of the address bar was refused",
                   HostCheckHandler.hostAllowed("router.lan:7657", allowed));
        assertTrue(HostCheckHandler.hostAllowed("LAN-2.LAN:8080", allowed));
    }

    /**
     * A plain loopback bind keeps the names it always kept, so the fix is not a
     * change to the ordinary configuration.
     */
    @Test
    public void testALoopbackBindNamesTheLocalHosts() {
        Set<String> allowed = HostCheckHandler.listenHosts(
            Collections.singleton("127.0.0.1"), null);
        for (String h : LOOPBACK) {
            assertTrue(h, allowed.contains(h));
        }
    }

    /**
     * The bound addresses are carried through, so a console bound to a LAN address
     * still answers for the name it was bound to, and the caller's set is left
     * alone.
     */
    @Test
    public void testTheBoundAddressesAreKept() {
        Set<String> bound = new HashSet<>(Arrays.asList("192.168.1.10", "0:0:0:0:0:0:0:0"));
        Set<String> allowed = HostCheckHandler.listenHosts(bound, null);
        assertTrue(allowed.containsAll(bound));
        assertEquals("the bound set was modified", 2, bound.size());
    }

    /**
     * Nothing configured at all still leaves a usable console rather than an empty
     * allowlist.
     */
    @Test
    public void testNothingBoundStillNamesSomething() {
        Set<String> allowed = HostCheckHandler.listenHosts(
            Collections.<String>emptySet(), null);
        assertFalse(allowed.isEmpty());
        assertTrue(allowed.contains("localhost"));
    }

    /**
     * Host names are case-insensitive, so the names are folded as they are stored -
     * an operator who writes the property as "MyRouter.LAN" is not writing a name
     * no header can ever match.
     */
    @Test
    public void testTheNamesAreFolded() {
        Set<String> allowed = HostCheckHandler.listenHosts(
            Collections.singleton("MyRouter.LAN"), "Two.I2P");
        assertTrue(allowed.contains("myrouter.lan"));
        assertTrue(allowed.contains("two.i2p"));
    }
}
