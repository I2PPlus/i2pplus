package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the stress-adaptive SYN accept-queue timeout decision in
 * {@link ConnectionHandler#getAdaptiveSynTimeout(int, double)}.
 *
 * <p>A queued SYN is reset via {@code TimeoutSyn} after the accept timeout;
 * while the tunnel system is stressed (build success below
 * {@code SYN_STRESS_THRESHOLD}) the window is clamped so a dead stream fails
 * fast and the client retries through its own connect backoff instead of
 * waiting out the full configured timeout.
 *
 * @since 0.9.71+
 */
public class ConnectionHandlerSynTimeoutTest {

    /** Healthy / recovered build success keeps the configured timeout. */
    @Test
    public void testHealthyKeepsConfiguredTimeout() {
        assertEquals(30 * 1000, ConnectionHandler.getAdaptiveSynTimeout(30 * 1000, 1.0));
        assertEquals(30 * 1000, ConnectionHandler.getAdaptiveSynTimeout(30 * 1000, 0.5));
        assertEquals(60 * 1000, ConnectionHandler.getAdaptiveSynTimeout(60 * 1000, 0.8));
    }

    /** The attack threshold is the boundary: at it, the timeout is kept. */
    @Test
    public void testThresholdBoundary() {
        assertEquals(30 * 1000, ConnectionHandler.getAdaptiveSynTimeout(30 * 1000, 0.4));
        assertEquals(10 * 1000, ConnectionHandler.getAdaptiveSynTimeout(30 * 1000, 0.399999));
    }

    /** Stressed build success clamps the timeout, never lengthens it. */
    @Test
    public void testStressClampsTimeout() {
        assertEquals(10 * 1000, ConnectionHandler.getAdaptiveSynTimeout(30 * 1000, 0.39));
        assertEquals(10 * 1000, ConnectionHandler.getAdaptiveSynTimeout(30 * 1000, 0.1));
        assertEquals(10 * 1000, ConnectionHandler.getAdaptiveSynTimeout(30 * 1000, 0.0));
        assertEquals(10 * 1000, ConnectionHandler.getAdaptiveSynTimeout(60 * 1000, 0.0));
    }

    /** A user-configured timeout already below the clamp is left alone. */
    @Test
    public void testStressNeverLengthensShortTimeout() {
        assertEquals(5 * 1000, ConnectionHandler.getAdaptiveSynTimeout(5 * 1000, 0.0));
    }

    /** Missing stats (NaN) never trigger the clamp. */
    @Test
    public void testNoDataKeepsConfiguredTimeout() {
        assertEquals(30 * 1000, ConnectionHandler.getAdaptiveSynTimeout(30 * 1000, Double.NaN));
        assertEquals(60 * 1000, ConnectionHandler.getAdaptiveSynTimeout(60 * 1000, Double.NaN));
    }

    /** A non-positive configured timeout is returned unchanged. */
    @Test
    public void testNonPositiveConfiguredTimeout() {
        assertEquals(0, ConnectionHandler.getAdaptiveSynTimeout(0, 0.0));
        assertEquals(-1, ConnectionHandler.getAdaptiveSynTimeout(-1, 0.0));
    }
}
