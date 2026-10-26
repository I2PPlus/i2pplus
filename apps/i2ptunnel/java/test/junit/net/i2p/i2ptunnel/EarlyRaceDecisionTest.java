package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * {@link I2PTunnelRunner#earlyRaceDue} — the arming decision for the early
 * reconnect race.
 *
 * <p>The race must arm on a much shorter silence threshold than the
 * 120s initial-response close, but with the same preconditions: only after
 * a request was actually written upstream, and only while no response byte
 * has arrived yet. A mis-armed race would open fresh sockets against a
 * healthy transfer and double the SYN load on the destination.
 *
 * @since 0.9.72+
 */
public class EarlyRaceDecisionTest {

    private static final long WRITE_MS = 1_000_000L;
    private static final long RACE_MS = I2PTunnelRunner.EARLY_RACE_RESPONSE_TIMEOUT_MS;

    /** Silent for less than the threshold: not due yet. */
    @Test
    public void testNotDueBeforeThreshold() {
        assertFalse(I2PTunnelRunner.earlyRaceDue(WRITE_MS, 0, WRITE_MS + RACE_MS - 1, RACE_MS));
    }

    /** Silent for exactly the threshold: due (boundary is inclusive). */
    @Test
    public void testDueAtThreshold() {
        assertTrue(I2PTunnelRunner.earlyRaceDue(WRITE_MS, 0, WRITE_MS + RACE_MS, RACE_MS));
    }

    /** A response byte arrived: the race is retired for this attempt. */
    @Test
    public void testNotDueAfterFirstByte() {
        assertFalse(I2PTunnelRunner.earlyRaceDue(WRITE_MS, WRITE_MS + 500,
                                                WRITE_MS + RACE_MS + 1000, RACE_MS));
    }

    /** No request written yet (anchor 0): nothing can be late. */
    @Test
    public void testNotDueWithoutRequestWrite() {
        assertFalse(I2PTunnelRunner.earlyRaceDue(0, 0, WRITE_MS + RACE_MS + 1000, RACE_MS));
    }

    /** Non-positive threshold disables the early race entirely. */
    @Test
    public void testDisabledWithNonPositiveThreshold() {
        assertFalse(I2PTunnelRunner.earlyRaceDue(WRITE_MS, 0, WRITE_MS + 10 * RACE_MS, 0));
        assertFalse(I2PTunnelRunner.earlyRaceDue(WRITE_MS, 0, WRITE_MS + 10 * RACE_MS, -1));
    }

    /**
     * The race threshold must stay strictly below the give-up close, or the
     * race could never fire before the socket was already closed.
     */
    @Test
    public void testRaceThresholdBelowGiveUp() {
        assertTrue("early race must arm before the initial-response close",
                   I2PTunnelRunner.EARLY_RACE_RESPONSE_TIMEOUT_MS <
                   I2PTunnelRunner.INITIAL_RESPONSE_TIMEOUT_MS);
    }
}
