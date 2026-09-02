package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the stress-adaptive replacement buffer decision in
 * {@link TunnelPool#getReplacementTunnelBuffer(double)}.
 *
 * <p>The buffer is the number of usable tunnels above {@code target} the pool
 * may hold before a further build must replace a non-GOOD tunnel instead of
 * being added.  Healthy pools keep the 2-tunnel buffer (target + 2); pools
 * under tunnel stress (build success below the attack threshold) tighten it to
 * 1 so replacement builds trigger sooner.
 *
 * @since 0.9.71+
 */
public class TunnelPoolBufferTest {

    /** Healthy success rates keep the 2-tunnel buffer. */
    @Test
    public void testHealthyKeepsBuffer() {
        assertEquals(2, TunnelPool.getReplacementTunnelBuffer(1.0));
        assertEquals(2, TunnelPool.getReplacementTunnelBuffer(0.5));
        assertEquals(2, TunnelPool.getReplacementTunnelBuffer(0.4));
    }

    /** Stressed success rates tighten to the 1-tunnel buffer. */
    @Test
    public void testStressTightensBuffer() {
        assertEquals(1, TunnelPool.getReplacementTunnelBuffer(0.39));
        assertEquals(1, TunnelPool.getReplacementTunnelBuffer(0.1));
        assertEquals(1, TunnelPool.getReplacementTunnelBuffer(0.0));
    }

    /** Missing stats (NaN) never count as stress. */
    @Test
    public void testNoDataKeepsBuffer() {
        assertEquals(2, TunnelPool.getReplacementTunnelBuffer(Double.NaN));
    }

    /** The attack threshold is the boundary: below is stressed, at is not. */
    @Test
    public void testThresholdBoundary() {
        assertEquals(2, TunnelPool.getReplacementTunnelBuffer(0.40));
        assertEquals(1, TunnelPool.getReplacementTunnelBuffer(0.399999));
    }
}
