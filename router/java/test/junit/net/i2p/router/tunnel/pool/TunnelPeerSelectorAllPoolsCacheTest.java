package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.util.Clock;

/**
 * Tests the snapshot behind TunnelPeerSelector.getPeersInAllPools(). The walk
 * it caches visits every pool, every tunnel and every hop of this router, and
 * every pool running a build pass asks the same question, so the answer is
 * reused inside a window rather than rebuilt per selection attempt.
 *
 * <p>Both directions are pinned: that the reuse actually happens by walking
 * the pool list once, and that it does not leak - past the window, into
 * another router context, or into a caller that mutates the set it was given.
 *
 * @since 0.9.72+
 */
public class TunnelPeerSelectorAllPoolsCacheTest {

    private static final long T0 = 5_000_000L;

    /**
     * JUnit creates one instance per test method; the stub context and pools
     * are rebuilt in the setup for each test, so this instance carries no
     * state between them.
     */
    public TunnelPeerSelectorAllPoolsCacheTest() {
    }

    private RouterContext _ctx;
    private TunnelManagerFacade _tmf;
    private Clock _clock;
    private TunnelPool _pool;

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        return Hash.create(data);
    }

    /**
     * Stub the context, its clock, and a single pool holding whatever tunnel
     * the test publishes.
     */
    @Before
    public void setUp() {
        _ctx = mock(RouterContext.class);
        _tmf = mock(TunnelManagerFacade.class);
        _clock = mock(Clock.class);
        _pool = mock(TunnelPool.class);
        when(_ctx.tunnelManager()).thenReturn(_tmf);
        when(_ctx.clock()).thenReturn(_clock);
        when(_ctx.routerHash()).thenReturn(hash(0));
        when(_clock.now()).thenReturn(T0);
        doAnswer(inv -> {
            List<TunnelPool> out = inv.getArgument(0);
            out.add(_pool);
            return null;
        }).when(_tmf).listPools(anyList());
    }

    /**
     * Publish one tunnel whose hops are {@code peers}.
     *
     * @param peers the hop peers, in order
     */
    private void stubTunnel(Hash... peers) {
        TunnelInfo ti = mock(TunnelInfo.class);
        when(ti.getLength()).thenReturn(peers.length);
        for (int i = 0; i < peers.length; i++) {
            when(ti.getPeer(i)).thenReturn(peers[i]);
        }
        when(_pool.listTunnels()).thenReturn(Collections.singletonList(ti));
    }

    /**
     * Two asks from the same context inside the window walk the pools once -
     * that single walk is the whole point of the snapshot.
     */
    @Test
    public void secondAskInsideWindowReusesSnapshot() {
        stubTunnel(hash(1), hash(2), hash(3));
        Set<Hash> first = TunnelPeerSelector.getPeersInAllPools(_ctx);
        Set<Hash> second = TunnelPeerSelector.getPeersInAllPools(_ctx);
        assertEquals(first, second);
        verify(_tmf, times(1)).listPools(anyList());
    }

    /**
     * Once the window has passed the pools are walked again, so the peers of a
     * tunnel built since the last answer do start being excluded.
     */
    @Test
    public void askAfterWindowRewalksPools() {
        when(_clock.now()).thenReturn(T0, T0 + TunnelPeerSelector.ALL_POOLS_TTL_MS);
        stubTunnel(hash(1), hash(2));
        Set<Hash> first = TunnelPeerSelector.getPeersInAllPools(_ctx);
        Set<Hash> second = TunnelPeerSelector.getPeersInAllPools(_ctx);
        assertEquals(first, second);
        verify(_tmf, times(2)).listPools(anyList());
    }

    /**
     * Callers get their own set. The first ask warms the snapshot, the second
     * is served from it and clears what it was handed, and the third must
     * still see both peers - and none of them may be the same object.
     */
    @Test
    public void callerMutationCannotCorruptSnapshot() {
        stubTunnel(hash(1), hash(2));
        Set<Hash> miss = TunnelPeerSelector.getPeersInAllPools(_ctx);
        Set<Hash> hit = TunnelPeerSelector.getPeersInAllPools(_ctx);
        assertNotSame("a cache hit must not hand out the snapshot itself", miss, hit);
        hit.clear();
        assertTrue(hit.isEmpty());
        Set<Hash> third = TunnelPeerSelector.getPeersInAllPools(_ctx);
        assertEquals(2, third.size());
        assertTrue(third.contains(hash(1)));
        assertTrue(third.contains(hash(2)));
        verify(_tmf, times(1)).listPools(anyList());
    }

    /**
     * A second router context - or a second test mock - is never served the
     * first one's tunnels: the context is part of the key, and each context
     * filters out its own router hash.
     */
    @Test
    public void otherContextIsNotServedThisSnapShot() {
        stubTunnel(hash(0), hash(1), hash(2));
        Set<Hash> mine = TunnelPeerSelector.getPeersInAllPools(_ctx);
        assertFalse(mine.contains(hash(0)));
        assertTrue(mine.contains(hash(1)));

        RouterContext other = mock(RouterContext.class);
        when(other.tunnelManager()).thenReturn(_tmf);
        when(other.clock()).thenReturn(_clock);
        when(other.routerHash()).thenReturn(hash(1));
        Set<Hash> theirs = TunnelPeerSelector.getPeersInAllPools(other);
        assertFalse("own router hash must be filtered for the other context too",
                    theirs.contains(hash(1)));
        assertTrue("the first context's own hash is a peer for the other one",
                   theirs.contains(hash(0)));
        verify(_tmf, times(2)).listPools(anyList());
    }

    /**
     * The snapshot bundle itself: fresh by time only for the context it was
     * built from, and stale from its expiry onward.
     */
    @Test
    public void snapshotBundleChecksContextAndExpiry() {
        TunnelPeerSelector.AllPoolsCache c = new TunnelPeerSelector.AllPoolsCache(
                Collections.singleton(hash(1)), _ctx, T0 + TunnelPeerSelector.ALL_POOLS_TTL_MS);
        assertTrue(c.isValid(_ctx, T0));
        assertTrue(c.isValid(_ctx, T0 + TunnelPeerSelector.ALL_POOLS_TTL_MS - 1));
        assertFalse(c.isValid(_ctx, T0 + TunnelPeerSelector.ALL_POOLS_TTL_MS));
        assertFalse(c.isValid(mock(RouterContext.class), T0));
    }

    /**
     * The window must cover a build pass, which is the duplication removed,
     * and stay short enough that a peer a freshly built tunnel just joined is
     * excluded on the next pass.
     */
    @Test
    public void windowCoversAPassButStaysShort() {
        long ttl = TunnelPeerSelector.ALL_POOLS_TTL_MS;
        assertTrue("cross-pool window must be positive, was " + ttl + "ms", ttl > 0);
        assertTrue("cross-pool window must stay under a minute, was " + ttl + "ms",
                   ttl < 60 * 1000L);
    }
}
