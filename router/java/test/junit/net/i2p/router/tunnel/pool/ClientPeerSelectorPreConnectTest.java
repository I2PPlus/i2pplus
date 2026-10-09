package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.CommSystemFacade;
import net.i2p.util.Clock;

/**
 * Tests for ClientPeerSelector.shouldPreConnect.
 *
 * <p>The predicate keys off the contact peer's transport session, not the build
 * timeout. It previously inferred "the handshake fits in the budget" from
 * {@code requestTimeout > 15s}, and with adaptive timeouts tuned to 17s and 22s
 * that skipped pre-connect for 71% of builds. The builds that then expired had
 * a first hop with no session 22-33% of the time, which is the failure these
 * tests now pin shut.
 *
 * <p>{@code testTimeoutNoLongerGoverns} is the specific regression: a long
 * timeout must not suppress pre-connect when no session exists.
 *
 * @since 0.9.71+
 */
public class ClientPeerSelectorPreConnectTest {

    private static final long NOW = 2_000_000_000L;
    private static final long FIVE_MIN = 5 * 60 * 1000L;
    private static final int TIMEOUT_10S = 10_000;
    private static final int TIMEOUT_20S = 20_000;

    private RouterContext _ctx;
    private CommSystemFacade _commSystem;

    @Before
    public void setUp() {
        ClientPeerSelector.clearPreConnectHistory();
        _ctx = mock(RouterContext.class);
        _commSystem = mock(CommSystemFacade.class);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);
        when(_ctx.commSystem()).thenReturn(_commSystem);
        when(_ctx.getProperty(ClientPeerSelector.PROP_PRECONNECT_OPTIMIZE, "true")).thenReturn("true");
        // Default: no session, none in progress — the case that needs pre-connect.
        when(_commSystem.isEstablished(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(_commSystem.isConnecting(org.mockito.ArgumentMatchers.any())).thenReturn(false);
    }

    @After
    public void tearDown() {
        ClientPeerSelector.clearPreConnectHistory();
        // Leave no tuned value behind: it is static, so leaking it would change
        // the answer for every later test in this JVM.
        BuildRequestor.setRequestTimeout(-1);
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (5 - b);
        return Hash.create(data);
    }

    /** No session and none in progress: the build cannot leave, so warm it. */
    @Test
    public void testNoSession_needPreConnect() {
        BuildRequestor.setRequestTimeout(TIMEOUT_10S);
        assertTrue(ClientPeerSelector.shouldPreConnect(_ctx, hash(1)));
    }

    /**
     * The regression this change fixes. A generous timeout used to mean
     * "pre-connect is unnecessary"; it only means the budget is larger than
     * the handshake, not that a session already exists. With adaptive
     * timeouts at 17s and 22s this path skipped pre-connect for most builds.
     */
    @Test
    public void testTimeoutNoLongerGoverns() {
        BuildRequestor.setRequestTimeout(TIMEOUT_20S);
        assertTrue("a long timeout must not suppress pre-connect when there is no session",
                   ClientPeerSelector.shouldPreConnect(_ctx, hash(1)));
    }

    /** An established session is the real reason to skip. */
    @Test
    public void testEstablishedSession_skipPreConnect() {
        when(_commSystem.isEstablished(hash(1))).thenReturn(true);
        assertFalse(ClientPeerSelector.shouldPreConnect(_ctx, hash(1)));
    }

    /** A handshake already in flight will get there without a second one. */
    @Test
    public void testAlreadyConnecting_skipPreConnect() {
        when(_commSystem.isConnecting(hash(1))).thenReturn(true);
        assertFalse(ClientPeerSelector.shouldPreConnect(_ctx, hash(1)));
    }

    /** The property still switches the feature off. */
    @Test
    public void testPropertyDisabled_skipPreConnect() {
        when(_ctx.getProperty(ClientPeerSelector.PROP_PRECONNECT_OPTIMIZE, "true")).thenReturn("false");
        assertFalse(ClientPeerSelector.shouldPreConnect(_ctx, hash(1)));
    }

    /** A peer pre-connected inside the cooldown is not probed again. */
    @Test
    public void testRecentlyConnected_skipPreConnect() {
        Hash peer = hash(1);
        ClientPeerSelector.recordPreConnect(peer, NOW);
        assertFalse(ClientPeerSelector.shouldPreConnect(_ctx, peer));
    }

    /** Once the cooldown lapses the peer is worth probing again. */
    @Test
    public void testCooldownExpired_needPreConnect() {
        Hash peer = hash(1);
        ClientPeerSelector.recordPreConnect(peer, NOW - FIVE_MIN - 1000L);
        assertTrue(ClientPeerSelector.shouldPreConnect(_ctx, peer));
    }

    /**
     * The cooldown is checked before the session state, so a peer we just
     * pre-connected is not re-probed even if the handshake has not completed
     * yet. Without this ordering a slow handshake would be retried on every
     * build and pile handshakes onto the same peer.
     */
    @Test
    public void testCooldownBeatsSessionState() {
        Hash peer = hash(1);
        ClientPeerSelector.recordPreConnect(peer, NOW);
        when(_commSystem.isEstablished(peer)).thenReturn(false);
        when(_commSystem.isConnecting(peer)).thenReturn(false);
        assertFalse(ClientPeerSelector.shouldPreConnect(_ctx, peer));
    }
}