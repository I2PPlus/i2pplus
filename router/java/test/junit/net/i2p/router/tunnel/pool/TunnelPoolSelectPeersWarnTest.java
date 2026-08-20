package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Rate-limit semantics for the selectPeers-failure WARN: silent before
 * 3 minutes of uptime (peers still being discovered), at most one WARN
 * per 60s interval per pool afterwards.
 *
 * @since 0.9.71+
 */
public class TunnelPoolSelectPeersWarnTest {

    private static final long SILENCE_MS = 3L * 60 * 1000;
    private static final long INTERVAL_MS = 60 * 1000L;

    @Test
    public void testSilentBeforeUptimeThreshold() {
        // elapsed time is irrelevant when uptime is below the threshold
        assertFalse(TunnelPool.shouldWarnSelectPeersFailure(0, INTERVAL_MS, 0));
        assertFalse(TunnelPool.shouldWarnSelectPeersFailure(SILENCE_MS - 1, INTERVAL_MS, 0));
        assertFalse(TunnelPool.shouldWarnSelectPeersFailure(SILENCE_MS, INTERVAL_MS, 0));
    }

    @Test
    public void testFirstWarnAfterUptimeThreshold() {
        assertTrue(TunnelPool.shouldWarnSelectPeersFailure(SILENCE_MS + 1, INTERVAL_MS, 0));
    }

    @Test
    public void testRateLimitedWithinInterval() {
        assertTrue(TunnelPool.shouldWarnSelectPeersFailure(SILENCE_MS + 1, INTERVAL_MS, 0));
        assertFalse("within interval", TunnelPool.shouldWarnSelectPeersFailure(SILENCE_MS + 1, INTERVAL_MS, 1));
        assertTrue("interval elapsed", TunnelPool.shouldWarnSelectPeersFailure(SILENCE_MS + 1, INTERVAL_MS + 1, 1));
    }
}