package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.TunnelInfo;

/**
 * The reuse scan's first-hop exclusion. A new client tunnel gets its peers by
 * one of two paths: the peer selector, which is handed the first hops the
 * executor already has a build on, or the reuse of a recent tunnel, which 75%
 * of client builds take. The reuse path ignored that set, so it handed back the
 * hop the dispatch gate is about to reject, and the build survived only by
 * re-selecting the whole path from scratch - measured live on 2026-11-27 at
 * 2,745 builds gated and 7,121 re-selections in 28 minutes.
 * <p>
 * Only the hop the build request is dispatched to counts, and that hop differs
 * by direction: hop 1 for an outbound tunnel, the gateway hop 0 for an inbound
 * one. A busy middle or far hop is not what the gate rejects, and abandoning a
 * reuse for one would cost the router a tunnel it can still use.
 *
 * @since 0.9.72+
 */
public class TunnelPoolReuseFirstHopTest {

    private static final long NOW = 1_000_000L;

    /**
     * A distinct peer hash, made unique among this class's hops by its first
     * byte.
     *
     * @param b the first byte of the hash
     * @return a 32-byte hash starting with {@code b}
     */
    private static Hash hop(byte b) {
        Hash rv = new Hash(new byte[32]);
        rv.getData()[0] = b;
        return rv;
    }

    /**
     * A set of build-request hops to hold a reuse off of.
     *
     * @param hops the excluded peers
     * @return a mutable set holding them
     */
    private static Set<Hash> excluded(Hash... hops) {
        return new HashSet<>(Arrays.asList(hops));
    }

    /**
     * An eligible reuse candidate of the given length, with hops numbered
     * gateway first as {@link TunnelInfo} numbers them: hop 0 is the gateway,
     * and for an outbound tunnel that is this router.
     *
     * @param length number of hops the tunnel reports, this router included
     * @param peers the hops in gateway-first order, padded with distinct hops if
     *              shorter than {@code length}
     * @return a mock TunnelInfo that findReusableCandidate() will accept
     */
    private static TunnelInfo candidate(int length, Hash... peers) {
        TunnelInfo ti = mock(TunnelInfo.class);
        when(ti.getLength()).thenReturn(length);
        when(ti.getExpiration()).thenReturn(NOW + 60_000L);
        when(ti.wasReused()).thenReturn(false);
        for (int i = 0; i < length; i++) {
            Hash h = i < peers.length ? peers[i] : hop((byte) (0x40 + i));
            when(ti.getPeer(i)).thenReturn(h);
        }
        return ti;
    }

    /**
     * Nothing excluded means the reuse scan behaves exactly as it did before the
     * exclusion was threaded through it - a null set and an empty one are both
     * no-ops, so no caller has to guard.
     */
    @Test
    public void testNoExclusionsReusesAnything() {
        TunnelInfo ti = candidate(3, hop((byte) 0), hop((byte) 1), hop((byte) 2));
        assertFalse(TunnelPool.reusesExcludedFirstHop(ti, false, null));
        assertFalse(TunnelPool.reusesExcludedFirstHop(ti, false, Collections.<Hash>emptySet()));
        assertFalse(TunnelPool.reusesExcludedFirstHop(null, false, excluded(hop((byte) 1))));
    }

    /**
     * An outbound build request goes to hop 1, one hop in from our gateway, so
     * that is the hop the exclusion holds the reuse off of - not hop 0, which is
     * this router.
     */
    @Test
    public void testOutboundHoldsOffHopOne() {
        Hash us = hop((byte) 0);
        Hash busy = hop((byte) 1);
        TunnelInfo ti = candidate(3, us, busy, hop((byte) 2));
        assertTrue(TunnelPool.reusesExcludedFirstHop(ti, false, excluded(busy)));
        assertFalse("hop 0 is us, never the build request target of an outbound tunnel",
                    TunnelPool.reusesExcludedFirstHop(ti, false, excluded(us)));
    }

    /**
     * An inbound build is dispatched from the far end, so its first hop is the
     * gateway, hop 0 - the same rule
     * BuildRequestor.getBuildRequestPeer() applies to an inbound config. Getting
     * this backwards would exclude the wrong hop on every inbound pool.
     */
    @Test
    public void testInboundHoldsOffTheGatewayHop() {
        Hash gateway = hop((byte) 1);
        Hash middle = hop((byte) 2);
        Hash us = hop((byte) 3);
        TunnelInfo ti = candidate(3, gateway, middle, us);
        assertTrue(TunnelPool.reusesExcludedFirstHop(ti, true, excluded(gateway)));
        assertFalse("the hop an outbound build would target is not an inbound build's",
                    TunnelPool.reusesExcludedFirstHop(ti, true, excluded(middle)));
    }

    /**
     * A busy middle or far hop does not disqualify the reuse. Only the peer the
     * build is dispatched to is what the gate rejects, so a tunnel that shares a
     * downstream hop with an in-flight build is still worth reusing.
     */
    @Test
    public void testOnlyTheDispatchHopIsChecked() {
        Hash free = hop((byte) 1);
        Hash busyMiddle = hop((byte) 2);
        Hash busyFar = hop((byte) 3);
        TunnelInfo ti = candidate(4, hop((byte) 0), free, busyMiddle, busyFar);
        assertFalse("a downstream hop being busy is not the gate's business",
                    TunnelPool.reusesExcludedFirstHop(ti, false, excluded(busyMiddle, busyFar)));
        assertTrue(TunnelPool.reusesExcludedFirstHop(ti, false, excluded(free)));
    }

    /**
     * A zero-hop tunnel - this router and nothing else - has no hop for an
     * outbound build request to go to, so it is never held back, and a short
     * hop list must not read past its end.
     */
    @Test
    public void testZeroHopTunnelIsNeverHeld() {
        Hash us = hop((byte) 0);
        TunnelInfo zeroHop = candidate(1, us);
        assertFalse(TunnelPool.reusesExcludedFirstHop(zeroHop, false, excluded(us)));
        assertFalse(TunnelPool.reusesExcludedFirstHop(zeroHop, false, excluded(hop((byte) 9))));
    }

    /**
     * The scan itself moves past a candidate on an excluded hop and reuses the
     * next eligible tunnel, rather than falling all the way through to a fresh
     * three-hop selection.
     */
    @Test
    public void testScanWalksPastTheClashToTheNextCandidate() {
        Hash busy = hop((byte) 1);
        TunnelInfo clash = candidate(3, hop((byte) 0), busy, hop((byte) 2));
        TunnelInfo free = candidate(3, hop((byte) 0), hop((byte) 4), hop((byte) 5));

        List<TunnelInfo> tunnels = Arrays.asList(clash, free);
        assertSame("the clash is still a candidate on length and age alone",
                   clash, TunnelPool.findReusableCandidate(tunnels, 3, NOW, 0));
        assertEquals("and it is the one the exclusion rule rejects", 1,
                     countRejected(tunnels, false, excluded(busy)));
        assertSame("the scan lands on the free tunnel instead",
                   free, TunnelPool.findReusableCandidate(tunnels, 3, NOW, 1));
    }

    /**
     * How many of the candidates the first-hop rule rejects, in scan order.
     *
     * @param tunnels the candidates, in the order the scan walks them
     * @param inbound whether the pool builds inbound tunnels
     * @param excludeFirstHops the build-request hops held off
     * @return the number of candidates whose build request would land on an
     *         excluded hop
     */
    private static int countRejected(List<TunnelInfo> tunnels, boolean inbound, Set<Hash> excludeFirstHops) {
        int rejected = 0;
        for (TunnelInfo ti : tunnels) {
            if (TunnelPool.reusesExcludedFirstHop(ti, inbound, excludeFirstHops)) {rejected++;}
        }
        return rejected;
    }
}
