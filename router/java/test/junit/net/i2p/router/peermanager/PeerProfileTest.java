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
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        assertNotNull(profile);
        assertEquals(peer, profile.getPeer());
    }

    @Test
    public void testTunnelHistoryNotNull() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        assertNotNull(profile.getTunnelHistory());
    }

    @Test
    public void testDBHistoryNotNull() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        assertNotNull(profile.getDBHistory());
    }

    @Test
    public void testSpeedValueGetter() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        float val = profile.getSpeedValue();
        assertTrue(val >= 0);
    }

    @Test
    public void testCapacityValueGetter() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        float val = profile.getCapacityValue();
        assertTrue(val >= 0);
    }

    @Test
    public void testIntegrationValueGetter() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        float val = profile.getIntegrationValue();
        assertTrue(val >= 0);
    }

    @Test
    public void testSpeedBonus() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        profile.setSpeedBonus(5);
        assertEquals(5, profile.getSpeedBonus());
    }

    @Test
    public void testCapacityBonus() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        profile.setCapacityBonus(10);
        assertEquals(10, profile.getCapacityBonus());
    }

    @Test
    public void testIntegrationBonus() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        profile.setIntegrationBonus(3);
        assertEquals(3, profile.getIntegrationBonus());
    }

    @Test
    public void testLastHeardAbout() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        long now = System.currentTimeMillis();
        profile.setLastHeardAbout(now);
        assertEquals(now, profile.getLastHeardAbout());
    }

    @Test
    public void testLastSendSuccessful() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        long now = System.currentTimeMillis();
        profile.setLastSendSuccessful(now);
        assertEquals(now, profile.getLastSendSuccessful());
    }

    @Test
    public void testLastSendFailed() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        long now = System.currentTimeMillis();
        profile.setLastSendFailed(now);
        assertEquals(now, profile.getLastSendFailed());
    }

    @Test
    public void testLastHeardFrom() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        long now = System.currentTimeMillis();
        profile.setLastHeardFrom(now);
        assertEquals(now, profile.getLastHeardFrom());
    }

    @Test
    public void testIsExpandedDefault() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        // default is expanded
        assertTrue(profile.getIsExpanded());
    }

    @Test
    public void testIsNotFailing() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer = new Hash();
        PeerProfile profile = new PeerProfile(_ctx, peer);
        assertFalse(profile.getIsFailing());
    }

    @Test
    public void testDifferentPeersNotEqual() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Hash peer1 = new Hash();
        Hash peer2 = new Hash();
        peer2.getData()[0] = 1;
        PeerProfile p1 = new PeerProfile(_ctx, peer1);
        PeerProfile p2 = new PeerProfile(_ctx, peer2);
        assertNotEquals(p1.getPeer(), p2.getPeer());
    }
}
