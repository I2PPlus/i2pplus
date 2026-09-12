package net.i2p.i2ptunnel;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 *  Unit tests for the pure connect-retry decision logic extracted from
 *  I2PTunnelIRCClient.clientConnectionRun: exponential backoff, retryable
 *  failure classification, and the attempt-budget/dead-pool gate.
 */
public class I2PTunnelIRCClientRetryTest {

    // ---------- getConnectRetryDelayMs ----------

    @Test
    public void testConnectRetryDelay_NonPositiveAttemptIsImmediate() {
        assertEquals(0, I2PTunnelIRCClient.getConnectRetryDelayMs(0));
        assertEquals(0, I2PTunnelIRCClient.getConnectRetryDelayMs(-1));
    }

    @Test
    public void testConnectRetryDelay_ExponentialSequence() {
        assertEquals(1000, I2PTunnelIRCClient.getConnectRetryDelayMs(1));
        assertEquals(2000, I2PTunnelIRCClient.getConnectRetryDelayMs(2));
        assertEquals(4000, I2PTunnelIRCClient.getConnectRetryDelayMs(3));
    }

    @Test
    public void testConnectRetryDelay_CappedAt8s() {
        assertEquals(8000, I2PTunnelIRCClient.getConnectRetryDelayMs(4));
        assertEquals(8000, I2PTunnelIRCClient.getConnectRetryDelayMs(5));
        assertEquals(8000, I2PTunnelIRCClient.getConnectRetryDelayMs(100));
    }

    @Test
    public void testConnectRetryDelay_TracksRetryConstant() {
        long base = I2PTunnelIRCClient.IRC_CONNECT_RETRY_BASE_DELAY;
        assertEquals(base, I2PTunnelIRCClient.getConnectRetryDelayMs(1));
        assertEquals(base * 8, I2PTunnelIRCClient.getConnectRetryDelayMs(100));
    }

    // ---------- isRetryableConnectFailure ----------

    @Test
    public void testRetryable_NullIsNotRetryable() {
        assertFalse(I2PTunnelIRCClient.isRetryableConnectFailure(null));
    }

    @Test
    public void testRetryable_ConnectTimeoutIsRetryable() {
        // the reported bug: java.net.NoRouteToHostException: Connection timed out
        assertTrue(I2PTunnelIRCClient.isRetryableConnectFailure(new NoRouteToHostException("Connection timed out")));
    }

    @Test
    public void testRetryable_UnresolvedHostIsRetryable() {
        // b32 dest may resolve on a later attempt once its LeaseSet is available
        assertTrue(I2PTunnelIRCClient.isRetryableConnectFailure(new UnknownHostException("Could not resolve ...")));
    }

    @Test
    public void testRetryable_I2PExceptionIsRetryable() {
        assertTrue(I2PTunnelIRCClient.isRetryableConnectFailure(new net.i2p.I2PException("Tunnel build failed")));
    }

    @Test
    public void testRetryable_GenericIOIsRetryable() {
        assertTrue(I2PTunnelIRCClient.isRetryableConnectFailure(new IOException("burst")));
    }

    @Test
    public void testRetryable_RefusedIsNotRetryable() {
        // explicit refusal is a hard answer; a new attempt cannot change it
        assertFalse(I2PTunnelIRCClient.isRetryableConnectFailure(new ConnectException("Connection refused")));
    }

    @Test
    public void testRetryable_InterruptedIsNotRetryable() {
        // local cancellation (tunnel closing) must not reschedule work
        assertFalse(I2PTunnelIRCClient.isRetryableConnectFailure(new InterruptedIOException("interrupted")));
    }

    // ---------- shouldRetryConnect ----------

    @Test
    public void testShouldRetryConnect_BudgetNotExhausted() {
        assertTrue(I2PTunnelIRCClient.shouldRetryConnect(1, 4, 1, new IOException("x")));
        assertTrue(I2PTunnelIRCClient.shouldRetryConnect(3, 4, 0, new IOException("x")));
    }

    @Test
    public void testShouldRetryConnect_BudgetExhausted() {
        assertFalse(I2PTunnelIRCClient.shouldRetryConnect(4, 4, 0, new IOException("x")));
        assertFalse(I2PTunnelIRCClient.shouldRetryConnect(5, 4, 1, new IOException("x")));
    }

    @Test
    public void testShouldRetryConnect_DeadPoolFailsFast() {
        assertFalse(I2PTunnelIRCClient.shouldRetryConnect(1, 4, -1, new IOException("x")));
        assertFalse(I2PTunnelIRCClient.shouldRetryConnect(1, 4, -1, new NoRouteToHostException("Connection timed out")));
    }

    @Test
    public void testShouldRetryConnect_UnknownPoolContinues() {
        // -2 (standalone client, no router pool) must NOT fail fast, or outproxy/
        // standalone clients (which always read -2) would never retry.
        assertTrue(I2PTunnelIRCClient.shouldRetryConnect(1, 4, -2, new IOException("x")));
    }

    @Test
    public void testShouldRetryConnect_BuildingAndHealthyPoolsContinue() {
        assertTrue(I2PTunnelIRCClient.shouldRetryConnect(1, 4, 0, new IOException("x")));
        assertTrue(I2PTunnelIRCClient.shouldRetryConnect(1, 4, 1, new IOException("x")));
    }

    @Test
    public void testShouldRetryConnect_NonRetryableFailureStops() {
        assertFalse(I2PTunnelIRCClient.shouldRetryConnect(1, 4, 0, new ConnectException("Connection refused")));
        assertFalse(I2PTunnelIRCClient.shouldRetryConnect(1, 4, 0, null));
    }

    // ---------- budget constant sanity ----------

    @Test
    public void testMaxAttemptsAtLeastOne() {
        // the loop must make at least the initial attempt
        assertTrue(I2PTunnelIRCClient.IRC_CONNECT_MAX_ATTEMPTS >= 1);
    }
}
