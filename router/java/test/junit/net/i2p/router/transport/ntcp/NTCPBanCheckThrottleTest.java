package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the per-connection cache in front of NTCP's ban and blocklist check.
 * <p>
 * isBanned() runs on every data-phase frame, and each call copies the remote IP
 * and binary-searches the blocklist's range array before looking the peer up in
 * the banlist - on the busiest receive path in the router. The verdict is now
 * cached for a few seconds, which trades detection latency for frame cost, so
 * both the boundary and the size of the window are pinned here.
 * <p>
 * The window is pinned as a bound rather than a value: a cached verdict that
 * outlives a few seconds would let a freshly banned peer keep talking.
 *
 * @since 0.9.72+
 */
public class NTCPBanCheckThrottleTest {

    private static final long T = 1_700_000_000_000L;

    /**
     * A connection that has never checked must check, so establishment still
     * rejects a banned peer on the first frame rather than after the window.
     */
    @Test
    public void testFirstCheckIsAlwaysDue() {
        assertTrue(NTCPConnection.banCheckIsDue(T, 0));
    }

    /**
     * Inside the window the cached verdict stands - that is the whole saving.
     */
    @Test
    public void testVerdictHeldInsideTheWindow() {
        long next = T + NTCPConnection.BAN_RECHECK_MS;
        assertFalse(NTCPConnection.banCheckIsDue(T, next));
        assertFalse(NTCPConnection.banCheckIsDue(T + NTCPConnection.BAN_RECHECK_MS - 1, next));
    }

    /**
     * The deadline is inclusive: the verdict is re-read exactly at the end of
     * the window, not one ms later.
     */
    @Test
    public void testDeadlineIsInclusive() {
        long next = T + NTCPConnection.BAN_RECHECK_MS;
        assertTrue(NTCPConnection.banCheckIsDue(next, next));
        assertTrue(NTCPConnection.banCheckIsDue(next + 1, next));
    }

    /**
     * The window has to stay short enough that a ban applied while a connection
     * is up takes effect promptly, and non-zero, or the cache buys nothing.
     */
    @Test
    public void testRecheckWindowStaysShort() {
        assertTrue("recheck window must be positive", NTCPConnection.BAN_RECHECK_MS > 0);
        assertTrue("recheck window must stay under 10 seconds: " + NTCPConnection.BAN_RECHECK_MS,
                   NTCPConnection.BAN_RECHECK_MS <= 10_000);
    }
}
