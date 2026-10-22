package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import net.i2p.router.TunnelInfo;

/**
 * Candidate selection for peer reuse. Selection used to mark the tunnel reused
 * as soon as it was picked, but the caller then rejects candidates whose peers
 * are on cooldown or whose RouterInfos have left the local netdb - so every
 * rejected candidate stayed marked for its whole life, excluded from reuse and
 * invisible to findReusedPredecessor().
 *
 * @since 0.9.72+
 */
public class TunnelPoolReuseCandidateTest {

    private static final long NOW = 1_000_000L;
    private static final long THREE_MIN = 3L * 60 * 1000;

    /** An eligible tunnel of the given length, expiring at {@code expiresAt}. */
    private static TunnelInfo tunnel(int length, long expiresAt, boolean reused) {
        TunnelInfo ti = mock(TunnelInfo.class);
        when(ti.getLength()).thenReturn(length);
        when(ti.getExpiration()).thenReturn(expiresAt);
        when(ti.wasReused()).thenReturn(reused);
        return ti;
    }

    /** A tunnel expiring well inside the reuse window. */
    private static TunnelInfo eligible(int length) {
        return tunnel(length, NOW + 60_000L, false);
    }

    @Test
    public void testEmptyListHasNoCandidate() {
        assertNull(TunnelPool.findReusableCandidate(Collections.<TunnelInfo>emptyList(), 3, NOW, 0));
    }

    /** The first eligible tunnel wins. */
    @Test
    public void testPicksFirstEligible() {
        TunnelInfo a = eligible(3);
        TunnelInfo b = eligible(4);
        assertSame(a, TunnelPool.findReusableCandidate(Arrays.asList(a, b), 3, NOW, 0));
    }

    /** Already-reused tunnels are skipped. */
    @Test
    public void testSkipsAlreadyReused() {
        TunnelInfo used = tunnel(3, NOW + 60_000L, true);
        TunnelInfo fresh = eligible(3);
        assertSame(fresh, TunnelPool.findReusableCandidate(Arrays.asList(used, fresh), 3, NOW, 0));
        assertNull(TunnelPool.findReusableCandidate(Collections.singletonList(used), 3, NOW, 0));
    }

    /** Tunnels that are not expiring inside the window are not reuse material. */
    @Test
    public void testSkipsLongLivedTunnels() {
        TunnelInfo fresh = tunnel(3, NOW + THREE_MIN + 1, false);
        assertNull(TunnelPool.findReusableCandidate(Collections.singletonList(fresh), 3, NOW, 0));
    }

    /** Boundary: expiring exactly at the window edge is outside it. */
    @Test
    public void testWindowEdgeIsExclusive() {
        TunnelInfo atEdge = tunnel(3, NOW + THREE_MIN, false);
        assertNull(TunnelPool.findReusableCandidate(Collections.singletonList(atEdge), 3, NOW, 0));
        TunnelInfo justInside = tunnel(3, NOW + THREE_MIN - 1, false);
        assertSame(justInside, TunnelPool.findReusableCandidate(Collections.singletonList(justInside), 3, NOW, 0));
    }

    /** Too short for the requested length is skipped. */
    @Test
    public void testSkipsTooShort() {
        TunnelInfo shorty = eligible(2);
        TunnelInfo ok = eligible(3);
        assertSame(ok, TunnelPool.findReusableCandidate(Arrays.asList(shorty, ok), 3, NOW, 0));
        assertNull(TunnelPool.findReusableCandidate(Collections.singletonList(shorty), 3, NOW, 0));
    }

    /** Scanning resumes at idx, which is how the caller walks past a rejected candidate. */
    @Test
    public void testHonoursStartIndex() {
        TunnelInfo a = eligible(3);
        TunnelInfo b = eligible(3);
        assertSame(b, TunnelPool.findReusableCandidate(Arrays.asList(a, b), 3, NOW, 1));
        assertNull(TunnelPool.findReusableCandidate(Arrays.asList(a, b), 3, NOW, 2));
    }

    /**
     * The regression: picking a candidate must not consume it. The caller rejects
     * some candidates after this returns, and a mark applied here excluded them
     * from reuse permanently.
     */
    @Test
    public void testSelectionDoesNotMarkReused() {
        TunnelInfo a = eligible(3);
        TunnelInfo picked = TunnelPool.findReusableCandidate(Arrays.asList(a), 3, NOW, 0);
        assertSame(a, picked);
        verify(picked, never()).setReused();
    }

    /** Walking the whole list must leave every tunnel unmarked. */
    @Test
    public void testWalkingTheListMarksNothing() {
        List<TunnelInfo> tunnels = Arrays.asList(eligible(3), eligible(4), eligible(5));
        for (int idx = 0; idx < tunnels.size(); idx++) {
            TunnelInfo ti = TunnelPool.findReusableCandidate(tunnels, 3, NOW, idx);
            assertSame(tunnels.get(idx), ti);
            verify(ti, never()).setReused();
        }
    }
}
