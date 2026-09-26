package net.i2p.sam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the SAM connection ownership and AUTH authorization
 * decisions: which connections may manage AUTH, and which connections may
 * operate on a session they did not create.
 *
 * @since 0.9.71+
 */
public class ClientOwnershipTest {

    @Test
    public void testLoopbackAddressForms() {
        assertTrue(SAMHandler.isLoopbackAddress("127.0.0.1"));
        assertTrue(SAMHandler.isLoopbackAddress("127.1.2.3"));
        assertTrue(SAMHandler.isLoopbackAddress("::1"));
        assertTrue(SAMHandler.isLoopbackAddress("0:0:0:0:0:0:0:1"));
        assertTrue(SAMHandler.isLoopbackAddress("::ffff:127.0.0.1"));
        assertFalse(SAMHandler.isLoopbackAddress("10.0.0.1"));
        assertFalse(SAMHandler.isLoopbackAddress("192.168.1.1"));
        assertFalse(SAMHandler.isLoopbackAddress("1270.0.0.1"));
        assertFalse(SAMHandler.isLoopbackAddress("::2"));
        assertFalse(SAMHandler.isLoopbackAddress(null));
    }

    @Test
    public void testSameAuthenticatedUser() {
        assertTrue(SAMHandler.sameClient("alice", "alice", "10.0.0.1", "10.0.0.9"));
        assertFalse(SAMHandler.sameClient("alice", "bob", "10.0.0.1", "10.0.0.1"));
        // one side not authenticated falls back to the address check
        assertTrue(SAMHandler.sameClient("alice", null, "10.0.0.1", "10.0.0.1"));
        assertFalse(SAMHandler.sameClient("alice", null, "10.0.0.1", "10.0.0.9"));
    }

    @Test
    public void testSameAddressWhenNotAuthenticated() {
        assertTrue(SAMHandler.sameClient(null, null, "10.0.0.1", "10.0.0.1"));
        assertFalse(SAMHandler.sameClient(null, null, "10.0.0.1", "10.0.0.2"));
        // any loopback form is the same host
        assertTrue(SAMHandler.sameClient(null, null, "127.0.0.1", "::1"));
        assertTrue(SAMHandler.sameClient(null, null, "::ffff:127.0.0.1", "127.0.0.1"));
        assertFalse(SAMHandler.sameClient(null, null, "127.0.0.1", "10.0.0.1"));
        // unknown addresses never match
        assertFalse(SAMHandler.sameClient(null, null, null, "10.0.0.1"));
        assertFalse(SAMHandler.sameClient(null, null, "10.0.0.1", null));
        assertFalse(SAMHandler.sameClient(null, null, null, null));
    }

    @Test
    public void testMayManageAuth() {
        // localhost always may manage AUTH
        assertTrue(SAMv3Handler.mayManageAuth(true, null, false));
        assertTrue(SAMv3Handler.mayManageAuth(true, null, true));
        // an authenticated connection may manage AUTH while auth is enabled
        assertTrue(SAMv3Handler.mayManageAuth(false, "alice", true));
        // but not after it has been disabled, or if it never authenticated
        assertFalse(SAMv3Handler.mayManageAuth(false, "alice", false));
        assertFalse(SAMv3Handler.mayManageAuth(false, null, true));
        assertFalse(SAMv3Handler.mayManageAuth(false, null, false));
    }
}
