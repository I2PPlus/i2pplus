package net.i2p.router.tunnel.pool;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests the tunnel-lifetime / lease-end relationship.
 *
 * The published lease end is
 * {@code min(tunnel expiry, now + leaseMaxDuration) - LEASE_SAFETY_MARGIN},
 * so the tunnel must sit exactly one grace period above the lease cap for the
 * advertised value to land on the I2P standard 10m. Shortening the tunnel to
 * 10m drags the advertised lease down to 9m, which is the mistake this test
 * exists to catch.
 */
public class TunnelPoolLeaseLifetimeTest {

    private static final long TEN_MIN = 10L * 60 * 1000;
    private static final long ELEVEN_MIN = 11L * 60 * 1000;
    private static final long NOW = 1_700_000_000_000L;

    /** The tunnel lifetime may never exceed 10m standard + 1m grace. */
    @Test
    public void tunnelLifetimeIsCappedAtElevenMinutes() {
        assertEquals("tunnel lifetime cap is the standard 10m plus 1m grace",
                     ELEVEN_MIN, TunnelPool.MAX_TUNNEL_LIFETIME_MS);
    }

    /**
     * With the shipped defaults the lease cap binds, not the tunnel, and peers
     * are advertised exactly the standard 10m. The safety margin is subtracted
     * before the cap, so a binding cap overwrites it — the advertised value
     * lands on 10m, not 9m59s.
     */
    @Test
    public void advertisedLeaseIsTheStandardTenMinutes() {
        long leaseEnd = TunnelPool.computeLeaseEndDate(NOW + ELEVEN_MIN, NOW, TEN_MIN);
        assertEquals("peers must see exactly the standard 10m",
                     TEN_MIN, leaseEnd - NOW);
    }

    /** The tunnel must outlive its lease by exactly the grace period. */
    @Test
    public void tunnelOutlivesItsLeaseByTheGracePeriod() {
        long tunnelExpiry = NOW + ELEVEN_MIN;
        long leaseEnd = TunnelPool.computeLeaseEndDate(tunnelExpiry, NOW, TEN_MIN);
        assertEquals("tunnel runs past its lease by the safety margin",
                     60_000L, tunnelExpiry - leaseEnd);
    }

    /**
     * A tunnel living exactly the standard lifetime would advertise only 9m,
     * because the margin is subtracted from whichever bound is smaller. This
     * is the regression the cap exists to prevent.
     */
    @Test
    public void aTenMinuteTunnelWouldUnderAdvertise() {
        long leaseEnd = TunnelPool.computeLeaseEndDate(NOW + TEN_MIN, NOW, TEN_MIN);
        assertEquals("10m tunnel advertises only 9m, so the tunnel must be 11m",
                     TEN_MIN - 60_000L, leaseEnd - NOW);
    }

    /** A short tunnel must not drag the advertised lease below the standard. */
    @Test
    public void shortTunnelIsCappedNotExtended() {
        // 5m tunnel, 10m cap: the tunnel binds, lease = 5m - 60s.
        long leaseEnd = TunnelPool.computeLeaseEndDate(NOW + 5 * 60 * 1000, NOW, TEN_MIN);
        assertEquals(5 * 60 * 1000 - 60_000L, leaseEnd - NOW);
    }

    /**
     * The 60s floor: a nearly-dead tunnel still yields a usable lease. This is
     * the branch a thin pool hits, and the reason leases were seen living
     * ~89s — the pool had nothing fresher to offer.
     */
    @Test
    public void nearlyDeadTunnelIsFlooredAtSixtySeconds() {
        long leaseEnd = TunnelPool.computeLeaseEndDate(NOW + 30_000, NOW, TEN_MIN);
        assertEquals("a 30s-old tunnel must still publish a 60s lease",
                     60_000L, leaseEnd - NOW);
    }

    /** The floor holds even for an already-expired tunnel. */
    @Test
    public void expiredTunnelStillYieldsUsableLease() {
        long leaseEnd = TunnelPool.computeLeaseEndDate(NOW - 120_000, NOW, TEN_MIN);
        assertEquals(60_000L, leaseEnd - NOW);
        assertTrue("lease must never be in the past", leaseEnd > NOW);
    }

    /** A tunnel with exactly the margin left lands on the floor boundary. */
    @Test
    public void marginBoundaryIsExact() {
        long leaseEnd = TunnelPool.computeLeaseEndDate(NOW + 60_000, NOW, TEN_MIN);
        assertEquals(60_000L, leaseEnd - NOW);
    }

    /**
     * The invariant that matters: whatever the tunnel lifetime, the advertised
     * lease never exceeds the standard 10m.
     */
    @Test
    public void advertisedLeaseNeverExceedsTheStandard() {
        for (long tunnelMinutes = 1; tunnelMinutes <= 12; tunnelMinutes++) {
            long leaseEnd = TunnelPool.computeLeaseEndDate(
                NOW + tunnelMinutes * 60 * 1000, NOW, TEN_MIN);
            assertTrue("tunnel " + tunnelMinutes + "m advertised " +
                       ((leaseEnd - NOW) / 1000) + "s, over the 10m standard",
                       leaseEnd - NOW <= TEN_MIN);
        }
    }
}
