package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the transport label attached to a zombie exclusion.
 *
 * <p>A zombie is a tunnel whose transport session died after the build
 * completed, so selection is not at fault and cannot be fixed by filtering
 * candidates. The split by transport is what identifies the cause, and the
 * RouterInfo style is a free-form string, so the label has to fold the
 * published spellings before it becomes a stat name. Counting an unrecognised
 * spelling as "unknown" matters: an empty or novel style would otherwise
 * produce a stat name with no reader.
 *
 * @since 0.9.71+
 */
public class TunnelPoolZombieTransportTest {

    @Test
    public void testKnownSpellings() {
        assertEquals("NTCP", TunnelPool.zombieTransportBucket("NTCP"));
        assertEquals("NTCP2", TunnelPool.zombieTransportBucket("NTCP2"));
        assertEquals("SSU", TunnelPool.zombieTransportBucket("SSU"));
        assertEquals("SSU2", TunnelPool.zombieTransportBucket("SSU2"));
    }

    @Test
    public void testUnrecognisedSpellingFoldsToUnknown() {
        assertEquals("unknown", TunnelPool.zombieTransportBucket("SSU2 "));
        assertEquals("unknown", TunnelPool.zombieTransportBucket("ssu2"));
        assertEquals("unknown", TunnelPool.zombieTransportBucket(""));
        assertEquals("unknown", TunnelPool.zombieTransportBucket("tor"));
    }

    @Test
    public void testAbsentStyleFoldsToUnknown() {
        assertEquals("unknown", TunnelPool.zombieTransportBucket(null));
    }

    // ---- isPreEmergencyBurstLive ----

    /** A pool that never burst is never exempt. */
    @Test
    public void testNeverBurstIsNotExempt() {
        assertFalse(TunnelPool.isPreEmergencyBurstLive(0L, 1_000_000L, 90_000L));
        assertFalse(TunnelPool.isPreEmergencyBurstLive(-1L, 1_000_000L, 90_000L));
    }

    /** Inside the window the burst still governs the pool's in-flight builds. */
    @Test
    public void testLiveBurstIsExempt() {
        long burst = 1_000_000L;
        assertTrue(TunnelPool.isPreEmergencyBurstLive(burst, burst, 90_000L));
        assertTrue(TunnelPool.isPreEmergencyBurstLive(burst, burst + 89_999L, 90_000L));
    }

    /** Exactly at the window edge the exemption lapses. */
    @Test
    public void testBurstEdgeIsNotExempt() {
        long burst = 1_000_000L;
        assertFalse(TunnelPool.isPreEmergencyBurstLive(burst, burst + 90_000L, 90_000L));
        assertFalse(TunnelPool.isPreEmergencyBurstLive(burst, burst + 200_000L, 90_000L));
    }

    /** A burst timestamp in the future is not live until it is reached. */
    @Test
    public void testFutureBurstIsNotExempt() {
        long burst = 1_000_000L;
        assertFalse(TunnelPool.isPreEmergencyBurstLive(burst, burst - 1L, 90_000L));
    }

    /** A non-positive window disables the exemption rather than exempting everything. */
    @Test
    public void testNonPositiveWindowDisablesExemption() {
        long burst = 1_000_000L;
        assertFalse(TunnelPool.isPreEmergencyBurstLive(burst, burst + 1L, 0L));
        assertFalse(TunnelPool.isPreEmergencyBurstLive(burst, burst + 1L, -1L));
    }
}
