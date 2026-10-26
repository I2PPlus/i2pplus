package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * {@link I2PTunnelRunner#browserWriteStalled} — the arming decision for the
 * browser-write stall watchdog.
 *
 * <p>Only an in-flight write may ever trip the watchdog: an idle tunnel —
 * CONNECT, keepalive between requests, a remote that sends nothing for
 * hours — reports a zero start stamp and must never be torn down. A write
 * that began and has not returned past the threshold means the local side
 * stopped reading and the forwarder is parked in a blocking write with no
 * timeout to break it.
 *
 * @since 0.9.72+
 */
public class BrowserWriteStallTest {

    private static final long START_MS = 2_000_000L;
    private static final long THRESHOLD_MS = I2PTunnelRunner.BROWSER_WRITE_STALL_MS;

    /** No write in flight (stamp 0): never stalled, no matter how idle. */
    @Test
    public void testIdleNeverStalled() {
        assertFalse(I2PTunnelRunner.browserWriteStalled(0, START_MS + 10 * THRESHOLD_MS, THRESHOLD_MS));
    }

    /** Write in flight but under the threshold: still patient. */
    @Test
    public void testInProgressBelowThreshold() {
        assertFalse(I2PTunnelRunner.browserWriteStalled(START_MS, START_MS + THRESHOLD_MS - 1, THRESHOLD_MS));
    }

    /** Write in flight exactly at the threshold: stalled (inclusive boundary). */
    @Test
    public void testStalledAtThreshold() {
        assertTrue(I2PTunnelRunner.browserWriteStalled(START_MS, START_MS + THRESHOLD_MS, THRESHOLD_MS));
    }

    /** Non-positive threshold disables the watchdog entirely. */
    @Test
    public void testDisabledWithNonPositiveThreshold() {
        assertFalse(I2PTunnelRunner.browserWriteStalled(START_MS, START_MS + 10 * THRESHOLD_MS, 0));
        assertFalse(I2PTunnelRunner.browserWriteStalled(START_MS, START_MS + 10 * THRESHOLD_MS, -1));
    }

    /**
     * The write threshold must not be tighter than the I2P-side body-stall
     * read timeout: a transfer whose I2P side is merely slow (not stalled)
     * can legitimately take that long per chunk toward a slow browser.
     */
    @Test
    public void testThresholdNotBelowBodyStall() {
        assertTrue("browser-write stall must not be tighter than the I2P body-stall timeout",
                   I2PTunnelRunner.BROWSER_WRITE_STALL_MS >=
                   I2PTunnelRunner.BODY_STALL_READ_TIMEOUT_MS);
    }
}
