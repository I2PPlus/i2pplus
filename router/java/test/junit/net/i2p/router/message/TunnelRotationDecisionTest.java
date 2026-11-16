package net.i2p.router.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

import net.i2p.router.TunnelInfo;

import org.junit.Test;

/**
 * Fresh-connection outbound tunnel rotation decisions.
 *
 * <p>Pins the contract of
 * {@link OutboundCache#pickDistinctTunnel(TunnelInfo, java.util.List, Random)}:
 * best-effort, identity-based rotation that never drops or blocks a connection,
 * and the gate in front of it,
 * {@link OutboundClientMessageOneShotJob#rotationPoolTooSmall(int)}, which skips
 * the attempt when the client's pool has no alternative to rotate onto.
 *
 * @since 0.9.71+
 */
public class TunnelRotationDecisionTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state.
     *
     * @since 0.9.72+
     */
    public TunnelRotationDecisionTest() {}

    /** Nothing cached, so there is nothing to rotate away from. */
    @Test
    public void testNoCachedTunnelRotatesToNothing() {
        assertNull(OutboundCache.pickDistinctTunnel(null, Collections.<TunnelInfo>emptyList(), new Random(1)));
        assertNull(OutboundCache.pickDistinctTunnel(null, Arrays.asList(mock(TunnelInfo.class)), new Random(1)));
    }

    /** With no pool picks available at all, the cached tunnel is kept. */
    @Test
    public void testNoCandidatesKeepsCached() {
        TunnelInfo cached = mock(TunnelInfo.class);
        assertSame(cached, OutboundCache.pickDistinctTunnel(cached, Collections.<TunnelInfo>emptyList(), new Random(1)));
    }

    /** A pool that only offers the cached tunnel back reports no alternative. */
    @Test
    public void testOnlyCachedCandidateKeepsCached() {
        TunnelInfo cached = mock(TunnelInfo.class);
        assertSame(cached, OutboundCache.pickDistinctTunnel(cached, Arrays.asList(cached), new Random(1)));
    }

    /** One alternative in the pool is enough: it is picked. */
    @Test
    public void testSingleAlternativePicksIt() {
        TunnelInfo cached = mock(TunnelInfo.class);
        TunnelInfo alt = mock(TunnelInfo.class);
        assertSame(alt, OutboundCache.pickDistinctTunnel(cached, Arrays.asList(cached, alt), new Random(1)));
    }

    /**
     * With several alternatives the choice is random, and it is never the tunnel
     * the previous connection used. Seeded so the sweep is reproducible.
     */
    @Test
    public void testMultipleAlternativesPickAnyDistinct() {
        TunnelInfo cached = mock(TunnelInfo.class);
        TunnelInfo alt1 = mock(TunnelInfo.class);
        TunnelInfo alt2 = mock(TunnelInfo.class);
        for (int seed = 0; seed < 20; seed++) {
            TunnelInfo picked = OutboundCache.pickDistinctTunnel(cached, Arrays.asList(cached, alt1, alt2), new Random(seed));
            assertNotNull(picked);
            assertFalse("must not pick the tunnel the previous connection used", picked == cached);
            assertTrue(picked == alt1 || picked == alt2);
        }
    }

    /** The cached tunnel need not appear among the picks; any candidate is valid. */
    @Test
    public void testCachedNotAmongCandidates() {
        TunnelInfo cached = mock(TunnelInfo.class);
        TunnelInfo alt1 = mock(TunnelInfo.class);
        TunnelInfo alt2 = mock(TunnelInfo.class);
        TunnelInfo picked = OutboundCache.pickDistinctTunnel(cached, Arrays.asList(alt1, alt2), new Random(1));
        assertNotNull(picked);
        assertTrue(picked == alt1 || picked == alt2);
    }

    /**
     * A dedicated client pool holding one outbound tunnel has nowhere to rotate
     * to, so the attempt is skipped rather than run and read as saturation. The
     * measured shape of this: one tunnel evicted 19 times in 17 minutes for a
     * one-tunnel client session, with normal selection re-picking and re-caching
     * the same tunnel every time.
     */
    @Test
    public void testSingleTunnelPoolSkipsRotation() {
        assertTrue(OutboundClientMessageOneShotJob.rotationPoolTooSmall(0));
        assertTrue(OutboundClientMessageOneShotJob.rotationPoolTooSmall(1));
    }

    /** Two tunnels is the smallest pool with an alternative to rotate onto. */
    @Test
    public void testTwoTunnelPoolRotates() {
        assertFalse(OutboundClientMessageOneShotJob.rotationPoolTooSmall(2));
        assertFalse(OutboundClientMessageOneShotJob.rotationPoolTooSmall(8));
    }

    /**
     * No dedicated client pool means the send draws on the exploratory outbound
     * pool, which holds several tunnels, so it is not the one-tunnel case. The
     * caller reports that as -1.
     */
    @Test
    public void testSharedPoolIsNotTooSmall() {
        assertFalse(OutboundClientMessageOneShotJob.rotationPoolTooSmall(-1));
    }

    /** The pool size the skip decision reads. */
    @Test
    public void testRotationNeedsTwoTunnels() {
        assertEquals(2, OutboundClientMessageOneShotJob.ROTATION_MIN_POOL_TUNNELS);
    }
}
