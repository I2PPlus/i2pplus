package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Clock;

/**
 * Tests for ClientPeerSelector.shouldPreConnect: approach 1 (timeout
 * threshold), approach 3 (property toggle), and approach 4 (recent
 * connection cooldown).
 *
 * @since 0.9.71+
 */
public class ClientPeerSelectorPreConnectTest {

    private static final long NOW = 2_000_000_000L;
    private static final long ONE_HOUR = 60 * 60 * 1000L;
    private static final long FIVE_MIN = 5 * 60 * 1000L;
    private static final long TIMEOUT_10S = 10_000L;
    private static final long TIMEOUT_15S = 15_000L;
    private static final long TIMEOUT_20S = 20_000L;

    private RouterContext _ctx;

    @Before
    public void setUp() {
        ClientPeerSelector.clearPreConnectHistory();
        _ctx = mock(RouterContext.class);
        when(_ctx.routerHash()).thenReturn(hash(9));

        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);

        Router _router = mock(Router.class);
        when(_ctx.router()).thenReturn(_router);
        when(_router.getUptime()).thenReturn(ONE_HOUR);
    }

    @After
    public void tearDown() {
        ClientPeerSelector.clearPreConnectHistory();
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (5 - b);
        return Hash.create(data);
    }

    @Test
    public void testTimeoutSufficient_skipPreConnect() {
        when(_ctx.getProperty(eq("i2p.tunnel.build.requestTimeout"), anyLong())).thenReturn(TIMEOUT_15S);
        when(_ctx.getProperty(eq(ClientPeerSelector.PROP_PRECONNECT_OPTIMIZE), any())).thenReturn("true");
        Hash peer = hash(1);
        assertFalse(ClientPeerSelector.shouldPreConnect(_ctx, peer));
    }

    @Test
    public void testTimeoutInsufficient_needPreConnect() {
        when(_ctx.getProperty(eq("i2p.tunnel.build.requestTimeout"), anyLong())).thenReturn(TIMEOUT_10S);
        when(_ctx.getProperty(eq(ClientPeerSelector.PROP_PRECONNECT_OPTIMIZE), any())).thenReturn("true");
        Hash peer = hash(1);
        assertTrue(ClientPeerSelector.shouldPreConnect(_ctx, peer));
    }

    @Test
    public void testPropertyDisabled_skipPreConnect() {
        when(_ctx.getProperty(eq("i2p.tunnel.build.requestTimeout"), anyLong())).thenReturn(TIMEOUT_10S);
        when(_ctx.getProperty(eq(ClientPeerSelector.PROP_PRECONNECT_OPTIMIZE), any())).thenReturn("false");
        Hash peer = hash(1);
        assertFalse(ClientPeerSelector.shouldPreConnect(_ctx, peer));
    }

    @Test
    public void testRecentlyConnected_skipPreConnect() {
        when(_ctx.getProperty(eq("i2p.tunnel.build.requestTimeout"), anyLong())).thenReturn(TIMEOUT_10S);
        when(_ctx.getProperty(eq(ClientPeerSelector.PROP_PRECONNECT_OPTIMIZE), any())).thenReturn("true");
        Hash peer = hash(1);
        ClientPeerSelector.recordPreConnect(peer, NOW);
        assertFalse(ClientPeerSelector.shouldPreConnect(_ctx, peer));
    }

    @Test
    public void testNotRecentlyConnected_needPreConnect() {
        when(_ctx.getProperty(eq("i2p.tunnel.build.requestTimeout"), anyLong())).thenReturn(TIMEOUT_10S);
        when(_ctx.getProperty(eq(ClientPeerSelector.PROP_PRECONNECT_OPTIMIZE), any())).thenReturn("true");
        Hash peer = hash(1);
        assertTrue(ClientPeerSelector.shouldPreConnect(_ctx, peer));
    }

    @Test
    public void testTimeoutSufficient_noRecentConnection_skipPreConnect() {
        when(_ctx.getProperty(eq("i2p.tunnel.build.requestTimeout"), anyLong())).thenReturn(TIMEOUT_20S);
        when(_ctx.getProperty(eq(ClientPeerSelector.PROP_PRECONNECT_OPTIMIZE), any())).thenReturn("true");
        Hash peer = hash(1);
        assertFalse(ClientPeerSelector.shouldPreConnect(_ctx, peer));
    }

    @Test
    public void testAllConditionsFalse_needPreConnect() {
        when(_ctx.getProperty(eq("i2p.tunnel.build.requestTimeout"), anyLong())).thenReturn(TIMEOUT_10S);
        when(_ctx.getProperty(eq(ClientPeerSelector.PROP_PRECONNECT_OPTIMIZE), any())).thenReturn("true");
        Hash peer = hash(1);
        assertTrue(ClientPeerSelector.shouldPreConnect(_ctx, peer));
    }

    @Test
    public void testCooldownExpired_needPreConnect() {
        when(_ctx.getProperty(eq("i2p.tunnel.build.requestTimeout"), anyLong())).thenReturn(TIMEOUT_10S);
        when(_ctx.getProperty(eq(ClientPeerSelector.PROP_PRECONNECT_OPTIMIZE), any())).thenReturn("true");
        Hash peer = hash(1);
        ClientPeerSelector.recordPreConnect(peer, NOW - FIVE_MIN - 1000L);
        assertTrue(ClientPeerSelector.shouldPreConnect(_ctx, peer));
    }
}
