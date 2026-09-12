package net.i2p.router.message;

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
 * Fresh-connection outbound tunnel rotation decision.
 *
 * <p>Pins the contract of
 * {@link OutboundCache#pickDistinctTunnel(TunnelInfo, java.util.List, Random)}:
 * best-effort, identity-based rotation that never drops or blocks a connection.
 *
 * @since 0.9.72+
 */
public class TunnelRotationDecisionTest {

    @Test
    public void testNoCachedTunnelRotatesToNothing() {
        assertNull(OutboundCache.pickDistinctTunnel(null, Collections.<TunnelInfo>emptyList(), new Random(1)));
        assertNull(OutboundCache.pickDistinctTunnel(null, Arrays.asList(mock(TunnelInfo.class)), new Random(1)));
    }

    @Test
    public void testNoCandidatesKeepsCached() {
        TunnelInfo cached = mock(TunnelInfo.class);
        assertSame(cached, OutboundCache.pickDistinctTunnel(cached, Collections.<TunnelInfo>emptyList(), new Random(1)));
    }

    @Test
    public void testOnlyCachedCandidateKeepsCached() {
        TunnelInfo cached = mock(TunnelInfo.class);
        assertSame(cached, OutboundCache.pickDistinctTunnel(cached, Arrays.asList(cached), new Random(1)));
    }

    @Test
    public void testSingleAlternativePicksIt() {
        TunnelInfo cached = mock(TunnelInfo.class);
        TunnelInfo alt = mock(TunnelInfo.class);
        assertSame(alt, OutboundCache.pickDistinctTunnel(cached, Arrays.asList(cached, alt), new Random(1)));
    }

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

    @Test
    public void testCachedNotAmongCandidates() {
        TunnelInfo cached = mock(TunnelInfo.class);
        TunnelInfo alt1 = mock(TunnelInfo.class);
        TunnelInfo alt2 = mock(TunnelInfo.class);
        TunnelInfo picked = OutboundCache.pickDistinctTunnel(cached, Arrays.asList(alt1, alt2), new Random(1));
        assertNotNull(picked);
        assertTrue(picked == alt1 || picked == alt2);
    }
}