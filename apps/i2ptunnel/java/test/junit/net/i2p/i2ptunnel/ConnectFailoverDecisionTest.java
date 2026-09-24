package net.i2p.i2ptunnel;

import java.io.InterruptedIOException;
import java.net.NoRouteToHostException;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 *  Unit tests for the pure tunnel-failover decision helpers extracted from
 *  I2PTunnelClientBase.createI2PSocketWithFailover: dead-pool fail-fast,
 *  timeout-failure cap, and timeout classification.
 */
public class ConnectFailoverDecisionTest {

    // ---------- shouldContinueFailover ----------

    @Test
    public void testContinue_HealthyPoolWithinBudget() {
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 1, 0, false));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 2, 0, false));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 3, 1, false));
    }

    @Test
    public void testContinue_StopsAtTunnelCount() {
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 4, 0, false));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 5, 0, false));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(1, 1, 0, false));
    }

    @Test
    public void testContinue_DeadPoolStopsImmediately() {
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 1, 0, true));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 1, 1, true));
    }

    @Test
    public void testContinue_TimeoutCapIndependentOfQuantity() {
        // quantity=4 must not multiply timeout legs past MAX_TIMEOUT_FAILOVER
        int max = I2PTunnelClientBase.MAX_TIMEOUT_FAILOVER;
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 1, max - 1, false));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 1, max, false));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(8, 2, max, false));
    }

    @Test
    public void testContinue_NonTimeoutFailuresUseTunnelBudget() {
        // NoRoute (not timeout) still walks configured failover legs
        assertEquals(2, I2PTunnelClientBase.MAX_TIMEOUT_FAILOVER);
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 2, 0, false));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 3, 0, false));
    }

    // ---------- isConnectTimeout ----------

    @Test
    public void testTimeout_NoRouteConnectionTimedOut() {
        // the live regression: NoRouteToHostException wrapping streaming SYN give-up
        assertTrue(I2PTunnelClientBase.isConnectTimeout(
                new NoRouteToHostException("Connection timed out")));
    }

    @Test
    public void testTimeout_InterruptedIOException() {
        assertTrue(I2PTunnelClientBase.isConnectTimeout(
                new InterruptedIOException("connect timed out")));
        assertTrue(I2PTunnelClientBase.isConnectTimeout(
                new InterruptedIOException("Read timed out")));
    }

    @Test
    public void testTimeout_CauseChain() {
        NoRouteToHostException wrapped = new NoRouteToHostException("unreachable");
        wrapped.initCause(new InterruptedIOException("Connection timed out"));
        assertTrue(I2PTunnelClientBase.isConnectTimeout(wrapped));
    }

    @Test
    public void testTimeout_NotATimeout() {
        assertFalse(I2PTunnelClientBase.isConnectTimeout(null));
        assertFalse(I2PTunnelClientBase.isConnectTimeout(
                new NoRouteToHostException("No route to host")));
        assertFalse(I2PTunnelClientBase.isConnectTimeout(
                new java.io.IOException("Connection refused")));
    }

    @Test
    public void testDefaultConnectTimeoutIsThirtySeconds() {
        assertEquals(30_000L, I2PTunnelClientBase.DEFAULT_CONNECT_TIMEOUT);
    }
}
