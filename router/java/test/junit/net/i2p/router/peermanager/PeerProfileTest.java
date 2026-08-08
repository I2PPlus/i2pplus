package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *  Tests for PeerProfile.
 */
public class PeerProfileTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void checkContext() {
        _ctx = RouterTestHelper.getContext();
    }

    @Test
    public void testConstruction() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        assertNotNull(profile);
        assertEquals(peer, profile.getPeer());
    }

    @Test
    public void testTunnelHistoryNotNull() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        assertNotNull(profile.getTunnelHistory());
    }

    @Test
    public void testDBHistoryNotNull() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        assertNotNull(profile.getDBHistory());
    }

    @Test
    public void testSpeedValueGetter() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        float val = profile.getSpeedValue();
        assertTrue(val >= 0);
    }

    @Test
    public void testCapacityValueGetter() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        float val = profile.getCapacityValue();
        assertTrue(val >= 0);
    }

    @Test
    public void testIntegrationValueGetter() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        float val = profile.getIntegrationValue();
        assertTrue(val >= 0);
    }

    @Test
    public void testSpeedBonus() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        profile.setSpeedBonus(5);
        assertEquals(5, profile.getSpeedBonus());
    }

    @Test
    public void testCapacityBonus() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        profile.setCapacityBonus(10);
        assertEquals(10, profile.getCapacityBonus());
    }

    @Test
    public void testIntegrationBonus() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        profile.setIntegrationBonus(3);
        assertEquals(3, profile.getIntegrationBonus());
    }

    @Test
    public void testLastHeardAbout() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        long now = System.currentTimeMillis();
        profile.setLastHeardAbout(now);
        assertEquals(now, profile.getLastHeardAbout());
    }

    @Test
    public void testLastSendSuccessful() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        long now = System.currentTimeMillis();
        profile.setLastSendSuccessful(now);
        assertEquals(now, profile.getLastSendSuccessful());
    }

    @Test
    public void testLastSendFailed() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        long now = System.currentTimeMillis();
        profile.setLastSendFailed(now);
        assertEquals(now, profile.getLastSendFailed());
    }

    @Test
    public void testLastHeardFrom() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        long now = System.currentTimeMillis();
        profile.setLastHeardFrom(now);
        assertEquals(now, profile.getLastHeardFrom());
    }

    @Test
    public void testIsExpandedDefault() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        // profiles are created unexpanded; merely accessing the tunnel history
        // must not expand them — only real tunnel participation does, so idle
        // profiles can be dropped from memory
        assertFalse(profile.getIsExpanded());
        profile.getTunnelHistory();
        assertFalse(profile.getIsExpanded());
        profile.getTunnelHistory().incrementAgreedTo();
        profile.expandProfile();
        assertTrue(profile.getIsExpanded());
    }

    @Test
    public void testDefaultSpeedValue() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        PeerProfile profile = new PeerProfile(_ctx, peer);
        // default speed value is 0
        assertEquals(0.0f, profile.getSpeedValue(), 0.001f);
    }

    @Test
    public void testDifferentPeersNotEqual() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer1 = Hash.create(new byte[Hash.HASH_LENGTH]);
        byte[] d2 = new byte[Hash.HASH_LENGTH];
        d2[0] = 1;
        Hash peer2 = Hash.create(d2);
        PeerProfile p1 = new PeerProfile(_ctx, peer1);
        PeerProfile p2 = new PeerProfile(_ctx, peer2);
        assertNotEquals(p1.getPeer(), p2.getPeer());
    }
}
