package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pure decision helpers that rate-limit high-frequency WARN paths.
 *
 * <p>Two spam vectors are bounded by these gates:
 * <ol>
 *   <li>{@link ConnectionManager#shouldLogCooldownWarn(long, long, long)} — the
 *       "Delaying connect" cooldown message. A destination that keeps failing
 *       re-enters cooldown on every connect() attempt and would otherwise log
 *       once per retry.</li>
 *   <li>{@link Connection#shouldLogPersistWarn(long, long, long)} — the persist
 *       timer auto-unchoke message. The persist branch in packetSendChoke is
 *       re-entered on every blocked write, so a persistently choked peer would
 *       otherwise log once per write attempt.</li>
 * </ol>
 *
 * @since 0.9.71+
 */
public class WarnRateLimitTest {

    private static final long COOLDOWN = 60 * 1000L;

    // ---------- ConnectionManager.shouldLogCooldownWarn ----------

    /** Never-warned (0) always logs. */
    @Test
    public void testCooldownNeverWarnedLogs() {
        assertTrue(ConnectionManager.shouldLogCooldownWarn(0, 1_000_000, COOLDOWN));
    }

    /** Within the cooldown window of the last warn: silence. */
    @Test
    public void testCooldownWithinWindowSilenced() {
        assertFalse(ConnectionManager.shouldLogCooldownWarn(1_000_000, 1_000_500, COOLDOWN));
    }

    /** Exactly at the window boundary: log again (>= window). */
    @Test
    public void testCooldownAtBoundaryLogs() {
        assertTrue(ConnectionManager.shouldLogCooldownWarn(1_000_000, 1_000_000 + COOLDOWN, COOLDOWN));
    }

    /** Past the window: log again. */
    @Test
    public void testCooldownPastWindowLogs() {
        assertTrue(ConnectionManager.shouldLogCooldownWarn(1_000_000, 1_000_000 + COOLDOWN + 1, COOLDOWN));
    }

    /** Zero cooldown (never delay) must not suppress on the edge. */
    @Test
    public void testCooldownNullWindow() {
        assertTrue(ConnectionManager.shouldLogCooldownWarn(1_000_000, 1_000_001, 0));
    }

    // ---------- Connection.shouldLogPersistWarn ----------

    /** Never-warned (0) always logs. */
    @Test
    public void testPersistNeverWarnedLogs() {
        assertTrue(Connection.shouldLogPersistWarn(0, 500_000, 2000));
    }

    /** Within the persist interval: silence. */
    @Test
    public void testPersistWithinIntervalSilenced() {
        assertFalse(Connection.shouldLogPersistWarn(500_000, 500_500, 2000));
    }

    /** At least one persist interval elapsed: log again. */
    @Test
    public void testPersistPastIntervalLogs() {
        assertTrue(Connection.shouldLogPersistWarn(500_000, 502_000, 2000));
    }

    /** Zero interval must not suppress. */
    @Test
    public void testPersistNullInterval() {
        assertTrue(Connection.shouldLogPersistWarn(500_000, 500_001, 0));
    }
}
