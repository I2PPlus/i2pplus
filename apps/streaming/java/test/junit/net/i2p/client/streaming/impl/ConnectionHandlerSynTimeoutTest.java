package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the stress-adaptive SYN accept-queue timeout decision in
 * {@link ConnectionHandler#getAdaptiveSynTimeout(int, double, int, int)}.
 *
 * <p>A queued SYN is reset via {@code TimeoutSyn} after the accept timeout.
 * The historical implementation clamped to a flat 10s whenever tunnel build
 * success fell below {@code SYN_STRESS_THRESHOLD}, which also expired
 * slow-but-alive handshakes on a high-latency / congested fabric (seen as empty
 * pages to every client).  The decision is now:
 *
 * <ul>
 *   <li><b>evidence-gated (#4)</b>: the clamp fires only when build success is
 *       low <em>and</em> the recent SYN expire rate is at/above the threshold
 *       ({@link ConnectionHandler#SYN_EXPIRE_THRESHOLD_DEFAULT}); a server that
 *       drains its queue — even slowly — is treated as healthy.</li>
 *   <li><b>RTT-aware floor (#2)</b>: when the clamp does fire, the window is
 *       {@code max(SYN_STRESS_MIN_TIMEOUT, scale * rttMs)}, capped at the
 *       configured timeout, so slow fabrics keep much longer than the flat 10s.</li>
 * </ul>
 *
 * @since 0.9.71+
 */
public class ConnectionHandlerSynTimeoutTest {

    private static final int CONFIGURED = 60 * 1000;

    /** Healthy build success never clamps regardless of expire rate. */
    @Test
    public void testHealthyKeepsConfiguredTimeout() {
        assertEquals(CONFIGURED, ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 1.0, 100, 10000));
        assertEquals(CONFIGURED, ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.5, 100, 10000));
        assertEquals(CONFIGURED, ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.4, 100, 10000));
    }

    /** Low expire rate (queue draining) keeps configured timeout even under stress — the #4 gate. */
    @Test
    public void testLowExpireRateDoesNotClamp() {
        assertEquals(CONFIGURED, ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.0, 0, 10000));
        assertEquals(CONFIGURED, ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.1, 59, 10000));
    }

    /** Exactly at the expire threshold arms the clamp. */
    @Test
    public void testExpireThresholdBoundary() {
        assertEquals(CONFIGURED, ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.0, 59, 0));
        // at threshold: clamp fires; fast fabric (rtt unknown) -> min floor
        assertEquals(ConnectionHandler.SYN_STRESS_MIN_TIMEOUT,
                     ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.0, 60, 0));
        assertEquals(ConnectionHandler.SYN_STRESS_MIN_TIMEOUT,
                     ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.0, 100, 0));
    }

    /** Unknown expire rate (no history yet) is "no evidence" and must not clamp — no startup false-positive. */
    @Test
    public void testUnknownExpireRateTreatedAsNoEvidence() {
        assertEquals(CONFIGURED, ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.0, -1, 9000));
        assertEquals(CONFIGURED, ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.0, Integer.MIN_VALUE, 9000));
    }

    /** Genuine stall on a fast fabric keeps the minimum floor (true fast-fail). */
    @Test
    public void testFastFabricClampsToMinimumFloor() {
        assertEquals(ConnectionHandler.SYN_STRESS_MIN_TIMEOUT,
                     ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.0, 100, 0));
        assertEquals(ConnectionHandler.SYN_STRESS_MIN_TIMEOUT,
                     ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.0, 100, 1000));
    }

    /** Genuine stall on a slow fabric extends the floor with RTT, capped at configured (#2). */
    @Test
    public void testSlowFabricFloorScalesWithRtt() {
        int scale = ConnectionHandler.SYN_RTT_SCALE_DEFAULT;
        int expected = Math.min(CONFIGURED, scale * 3000);
        assertEquals(expected, ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.0, 100, 3000));

        // 10s RTT with scale 4 -> 40s, still below the 60s configured cap
        assertEquals(40 * 1000, ConnectionHandler.getAdaptiveSynTimeout(60 * 1000, 0.0, 100, 10000));

        // very high RTT never exceeds the configured timeout
        assertEquals(CONFIGURED, ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.0, 100, 20000));
        assertEquals(CONFIGURED, ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.0, 100, Integer.MAX_VALUE / 4));
    }

    /** A configured timeout already below the floor is left alone; never lengthened by the clamp. */
    @Test
    public void testClampNeverLengthensShortConfiguredTimeout() {
        assertEquals(5 * 1000, ConnectionHandler.getAdaptiveSynTimeout(5 * 1000, 0.0, 100, 10000));
        assertEquals(1000, ConnectionHandler.getAdaptiveSynTimeout(1000, 0.0, 100, 100000));
    }

    /** Missing stats (NaN build success) never clamp — early startup / stand-alone streaming. */
    @Test
    public void testNoDataKeepsConfiguredTimeout() {
        assertEquals(CONFIGURED, ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, Double.NaN, 100, 10000));
    }

    /** A non-positive configured timeout is returned unchanged. */
    @Test
    public void testNonPositiveConfiguredTimeout() {
        assertEquals(0, ConnectionHandler.getAdaptiveSynTimeout(0, 0.0, 100, 10000));
        assertEquals(-1, ConnectionHandler.getAdaptiveSynTimeout(-1, 0.0, 100, 10000));
    }

    /** Tuner disabling the expire threshold (>=100) turns the clamp off entirely. */
    @Test
    public void testDisableClampViaThreshold100() {
        I2PSocketManagerFull.setSynExprExpireThresh(100);
        try {
            assertEquals(CONFIGURED, ConnectionHandler.getAdaptiveSynTimeout(CONFIGURED, 0.0, 100, 0));
        } finally {
            I2PSocketManagerFull.setSynExprExpireThresh(0);
        }
    }

    /** Tuner scale 0 (never set) means "use the built-in default RTT scale", so the RTT-aware floor
     *  stays active — a slow fabric still extends its window rather than dropping to the flat minimum. */
    @Test
    public void testScaleUnalteredFallsBackToDefaultScale() {
        I2PSocketManagerFull.setRttSynTimeoutScale(0);
        try {
            // default scale 4 * 10s RTT = 40s, capped at configured 60s
            assertEquals(40 * 1000,
                         ConnectionHandler.getAdaptiveSynTimeout(60 * 1000, 0.0, 100, 10000));
        } finally {
            I2PSocketManagerFull.setRttSynTimeoutScale(0);
        }
    }

    /*
     * Deterministic Tuner vantages for the non-Tuner-controlled tests: restore the
     * defaults so the assertion expectations above hold regardless of prior state.
     */
    @Before
    public void resetTuner() {
        I2PSocketManagerFull.setSynExprExpireThresh(0);
        I2PSocketManagerFull.setRttSynTimeoutScale(0);
    }

    @After
    public void restoreTuner() {
        I2PSocketManagerFull.setSynExprExpireThresh(0);
        I2PSocketManagerFull.setRttSynTimeoutScale(0);
    }
}
