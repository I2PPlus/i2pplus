package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 *  Unit tests for the send-backlog decision extracted from
 *  {@link NTCPConnection#tooBacklogged}.
 *
 *  <p>Pins the grace-period edge exactly: the outbound backlog only counts once
 *  the connection has been alive for the full 15-second window, so a freshly
 *  established connection is never immediately flagged as slow regardless of
 *  backlog depth.
 *
 *  @since 0.9.71+
 */
public class NTCPConnectionBacklogDecisionTest {

    private static final long GRACE = 15 * 1000L;

    @Test
    public void notBackloggedNeverFlags() {
        assertFalse(NTCPConnection.isTooBacklogged(Long.MAX_VALUE, false));
    }

    @Test
    public void backloggedAfterGracePeriod() {
        assertTrue(NTCPConnection.isTooBacklogged(GRACE, true));
        assertTrue(NTCPConnection.isTooBacklogged(GRACE + 1, true));
    }

    @Test
    public void backloggedBeforeGracePeriod() {
        assertFalse(NTCPConnection.isTooBacklogged(GRACE - 1, true));
        assertFalse(NTCPConnection.isTooBacklogged(0, true));
    }

    @Test
    public void noUptimeNeverFlags() {
        assertFalse(NTCPConnection.isTooBacklogged(Long.MIN_VALUE, true));
    }
}