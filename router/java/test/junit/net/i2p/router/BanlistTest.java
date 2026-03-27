package net.i2p.router;

import static org.junit.Assert.*;

import net.i2p.data.Hash;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for Banlist.
 *
 * Note: isBanlisted(Hash) currently delegates to isBanlisted(Hash, null)
 * which returns false when transport is null. Tests here use
 * getEntries().containsKey() or isBanlisted(Hash, String) with
 * a transport to verify ban state.
 */
public class BanlistTest {

    private static final String T = "NTCP";
    private static RouterContext _context;
    private Banlist _banlist;

    @BeforeClass
    public static void checkContext() {
        _context = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _context != null);
    }

    @Before
    public void setUp() {
        Assume.assumeTrue("No RouterContext available", _context != null);
        _banlist = _context.banlist();
    }

    private Hash randomHash() {
        Hash h = new Hash();
        byte[] d = new byte[Hash.HASH_LENGTH];
        _context.random().nextBytes(d);
        h.setData(d);
        return h;
    }

    @Test
    public void testBanAndCheckViaEntries() {
        Hash peer = randomHash();
        assertFalse(_banlist.getEntries().containsKey(peer));
        _banlist.banlistRouter(peer, "test reason");
        assertTrue(_banlist.getEntries().containsKey(peer));
    }

    @Test
    public void testBanWithTransport() {
        Hash peer = randomHash();
        _banlist.banlistRouter(peer, "test", T);
        assertTrue(_banlist.isBanlisted(peer, T));
    }

    @Test
    public void testBanlistRouterReturnsFalseFirstTime() {
        Hash peer = randomHash();
        assertFalse("First ban should return false", _banlist.banlistRouter(peer, "test"));
    }

    @Test
    public void testBanlistRouterReturnsTrueSecondTime() {
        Hash peer = randomHash();
        _banlist.banlistRouter(peer, "first");
        assertTrue("Second ban should return true", _banlist.banlistRouter(peer, "second"));
    }

    @Test
    public void testBanlistRouterForever() {
        Hash peer = randomHash();
        _banlist.banlistRouterForever(peer, "permanent ban");
        assertTrue(_banlist.isBanlistedForever(peer));
        assertTrue(_banlist.getEntries().containsKey(peer));
    }

    @Test
    public void testUnbanlistRouter() {
        Hash peer = randomHash();
        _banlist.banlistRouter(peer, "test", T);
        assertTrue(_banlist.isBanlisted(peer, T));
        _banlist.unbanlistRouter(peer);
        assertFalse(_banlist.isBanlisted(peer, T));
        assertFalse(_banlist.getEntries().containsKey(peer));
    }

    @Test
    public void testBanlistNullPeerNotBanlisted() {
        assertFalse(_banlist.isBanlisted(null, T));
    }

    @Test
    public void testBanNullPeerReturnsFalse() {
        assertFalse(_banlist.banlistRouter(null, "test"));
    }

    @Test
    public void testCannotBanSelf() {
        assertFalse("Should not ban own router", _banlist.banlistRouter(_context.routerHash(), "self ban"));
    }

    @Test
    public void testGetEntriesContainsBan() {
        Hash peer1 = randomHash();
        Hash peer2 = randomHash();
        _banlist.banlistRouter(peer1, "reason1");
        _banlist.banlistRouter(peer2, "reason2");
        assertTrue(_banlist.getEntries().containsKey(peer1));
        assertTrue(_banlist.getEntries().containsKey(peer2));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testGetEntriesUnmodifiable() {
        Hash peer = randomHash();
        _banlist.banlistRouter(peer, "test");
        _banlist.getEntries().clear();
    }

    @Test
    public void testIsBanlistedHostile() {
        Hash peer = randomHash();
        _banlist.banlistRouterForever(peer, "hostile");
        assertTrue(_banlist.isBanlistedHostile(peer));
    }

    @Test
    public void testNonBannedNotHostile() {
        assertFalse(_banlist.isBanlistedHostile(randomHash()));
    }

    @Test
    public void testBanExpiredNotBanlisted() {
        Hash peer = randomHash();
        _banlist.banlistRouter(peer, "test", null, null, _context.clock().now() - 1000);
        assertFalse("Expired ban should not be active", _banlist.getEntries().containsKey(peer));
    }

    @Test
    public void testBanWithExpiredTimeReturnsFalse() {
        Hash peer = randomHash();
        assertFalse("Ban with past expiration should return false", _banlist.banlistRouter(peer, "test", null, null, _context.clock().now() - 1000));
    }

    @Test
    public void testGetRouterCount() {
        int initial = _banlist.getRouterCount();
        _banlist.banlistRouter(randomHash(), "test");
        _banlist.banlistRouter(randomHash(), "test");
        assertEquals(initial + 2, _banlist.getRouterCount());
    }

    @Test
    public void testBanlistForeverDurationExceedsThreshold() {
        assertTrue("Forever duration should exceed threshold", Banlist.BANLIST_DURATION_FOREVER > 24 * 60 * 60 * 1000L);
    }

    @Test
    public void testBanWithNullTransport() {
        Hash peer = randomHash();
        assertFalse(_banlist.banlistRouter(peer, "test", null));
        assertTrue(_banlist.getEntries().containsKey(peer));
    }

    @Test
    public void testBanEntryFields() {
        Hash peer = randomHash();
        _banlist.banlistRouter(peer, "my reason");
        Banlist.Entry e = _banlist.getEntries().get(peer);
        assertNotNull(e);
        assertEquals("my reason", e.cause);
        assertTrue(e.expireOn > _context.clock().now());
    }

    @Test
    public void testBanForeverEntry() {
        Hash peer = randomHash();
        _banlist.banlistRouterForever(peer, "forever", "FLOOD");
        Banlist.Entry e = _banlist.getEntries().get(peer);
        assertNotNull(e);
        assertEquals("FLOOD", e.causeCode);
        assertTrue(e.expireOn > _context.clock().now() + Banlist.BANLIST_DURATION_FOREVER - 60000);
    }
}
