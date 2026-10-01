package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.Clock;

/**
 * Tests for ClientPeerSelector.shouldPreConnect.
 *
 * <p>Drives the request timeout through {@link BuildRequestor#setRequestTimeout}
 * rather than the {@code i2p.tunnel.build.requestTimeout} property, because
 * that is what the code reads. It previously read the property directly, which
 * had two consequences, both pinned below:
 * <ul>
 * <li>It never saw the runtime-tuned value, since tuning writes a static.</li>
 * <li>With the property unset its own default equalled the threshold, so the
 *     comparison was always true and first-hop pre-connect never ran on a
 *     default install.</li>
 * </ul>
 *
 * @since 0.9.71+
 */
public class ClientPeerSelectorPreConnectTest {

    private static final long NOW = 2_000_000_000L;
    private static final long ONE_HOUR = 60 * 60 * 1000L;
    private static final long FIVE_MIN = 5 * 60 * 1000L;
    private static final int TIMEOUT_10S = 10_000;
    private static final int TIMEOUT_15S = 15_000;
    private static final int TIMEOUT_20S = 20_000;

    private RouterContext _ctx;

    @Before
    public void setUp() {
        ClientPeerSelector.clearPreConnectHistory();
        _ctx = mock(RouterContext.class);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);
        when(_ctx.getProperty(ClientPeerSelector.PROP_PRECONNECT_OPTIMIZE, "true")).thenReturn("true");
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

    /** A timeout below the threshold leaves the handshake inside the budget. */
    @Test
    public void testTimeoutInsufficient_needPreConnect() {
        BuildRequestor.setRequestTimeout(TIMEOUT_10S);
        assertTrue(ClientPeerSelector.shouldPreConnect(_ctx, hash(1)));
    }

    /**
     *  The regression: a timeout exactly at the threshold must still pre-connect.
     *  With {@code >=} this returned false, and since the tuned default sits at
     *  the threshold, pre-connect was dead on a default install.
     */
    @Test
    public void testTimeoutExactlyAtThreshold_needPreConnect() {
        BuildRequestor.setRequestTimeout(TIMEOUT_15S);
        assertTrue("a timeout AT the threshold still needs the handshake warmed",
                   ClientPeerSelector.shouldPreConnect(_ctx, hash(1)));
    }

    /** Only strictly above the threshold is the budget demonstrably sufficient. */
    @Test
    public void testTimeoutAboveThreshold_skipPreConnect() {
        BuildRequestor.setRequestTimeout(TIMEOUT_20S);
        assertFalse(ClientPeerSelector.shouldPreConnect(_ctx, hash(1)));
    }

    /** The property still switches the feature off. */
    @Test
    public void testPropertyDisabled_skipPreConnect() {
        BuildRequestor.setRequestTimeout(TIMEOUT_10S);
        when(_ctx.getProperty(ClientPeerSelector.PROP_PRECONNECT_OPTIMIZE, "true")).thenReturn("false");
        assertFalse(ClientPeerSelector.shouldPreConnect(_ctx, hash(1)));
    }

    /** A peer pre-connected inside the cooldown is not probed again. */
    @Test
    public void testRecentlyConnected_skipPreConnect() {
        BuildRequestor.setRequestTimeout(TIMEOUT_10S);
        Hash peer = hash(1);
        ClientPeerSelector.recordPreConnect(peer, NOW);
        assertFalse(ClientPeerSelector.shouldPreConnect(_ctx, peer));
    }

    /** Once the cooldown lapses the peer is worth probing again. */
    @Test
    public void testCooldownExpired_needPreConnect() {
        BuildRequestor.setRequestTimeout(TIMEOUT_10S);
        Hash peer = hash(1);
        ClientPeerSelector.recordPreConnect(peer, NOW - FIVE_MIN - 1000L);
        assertTrue(ClientPeerSelector.shouldPreConnect(_ctx, peer));
    }

    /**
     *  The tuned value must win over any configured property, since tuning is
     *  what the builder actually runs with. This is the specific reason the
     *  property read was wrong.
     */
    @Test
    public void testTunedTimeoutIsObservedNotTheProperty() {
        BuildRequestor.setRequestTimeout(TIMEOUT_20S);
        // Even if a stale property claims a short timeout, the tuned value governs.
        when(_ctx.getProperty("i2p.tunnel.build.requestTimeout", 20_000L)).thenReturn(10_000L);
        assertFalse(ClientPeerSelector.shouldPreConnect(_ctx, hash(1)));
    }
}
