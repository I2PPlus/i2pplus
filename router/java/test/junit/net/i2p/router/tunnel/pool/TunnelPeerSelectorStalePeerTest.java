package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.util.Clock;

/**
 * Tests for TunnelPeerSelector.isStalePeer: unprofiled (never-heard-of) peers
 * must NOT be considered stale — they are potential newcomers during pool
 * recovery — and the startup grace period suppresses stale marking entirely.
 *
 * @since 0.9.71+
 */
public class TunnelPeerSelectorStalePeerTest {

    private static final long NOW = 2_000_000_000L;
    private static final long ONE_HOUR = 60 * 60 * 1000L;

    private RouterContext _ctx;
    private Router _router;
    private ProfileOrganizer _organizer;

    @Before
    public void setUp() {
        _ctx = mock(RouterContext.class);
        when(_ctx.routerHash()).thenReturn(hash(9));

        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);

        // getActivityWindow scales on active-peer count (0 -> 8 hour window)
        CommSystemFacade cs = mock(CommSystemFacade.class);
        when(cs.countActivePeers()).thenReturn(0);
        when(_ctx.commSystem()).thenReturn(cs);

        _router = mock(Router.class);
        when(_ctx.router()).thenReturn(_router);

        _organizer = mock(ProfileOrganizer.class);
        when(_ctx.profileOrganizer()).thenReturn(_organizer);
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (5 - b);
        return Hash.create(data);
    }

    /** A profile last heard from/about long ago (outside the 8h window). */
    private static PeerProfile oldProfile() {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getLastHeardFrom()).thenReturn(0L);
        when(p.getLastHeardAbout()).thenReturn(0L);
        return p;
    }

    /** A profile heard from within the activity window. */
    private static PeerProfile freshProfile() {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getLastHeardFrom()).thenReturn(NOW - 1000L);
        when(p.getLastHeardAbout()).thenReturn(NOW - 2000L);
        return p;
    }

    @Test
    public void testUnprofiledPeerNotStale() {
        when(_router.getUptime()).thenReturn(ONE_HOUR);
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(null);
        assertFalse(TunnelPeerSelector.isStalePeer(_ctx, hash(1), 0.5));
    }

    @Test
    public void testProfiledActivePeerNotStale() {
        when(_router.getUptime()).thenReturn(ONE_HOUR);
        PeerProfile p = freshProfile();
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(p);
        assertFalse(TunnelPeerSelector.isStalePeer(_ctx, hash(1), 0.5));
    }

    @Test
    public void testProfiledInactivePeerStale() {
        when(_router.getUptime()).thenReturn(ONE_HOUR);
        PeerProfile p = oldProfile();
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(p);
        assertTrue(TunnelPeerSelector.isStalePeer(_ctx, hash(1), 0.5));
    }

    @Test
    public void testStartupGraceSuppressesStale() {
        // 10 minutes uptime: inside the 15-minute startup grace
        when(_router.getUptime()).thenReturn(10 * 60 * 1000L);
        PeerProfile p = oldProfile();
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(p);
        assertFalse(TunnelPeerSelector.isStalePeer(_ctx, hash(1), 0.5));
    }
}