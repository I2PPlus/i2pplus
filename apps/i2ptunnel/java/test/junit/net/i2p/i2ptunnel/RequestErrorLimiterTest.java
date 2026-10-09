package net.i2p.i2ptunnel;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the per-client rate limiting of "Request error" warnings in
 * {@link I2PTunnelHTTPServer}.
 *
 * A single torrent client that reconnects without sending a request produced
 * over 1000 read-timeout warnings in one log rotation, burying every other
 * warning. These tests pin the interval, the map bound, and the fact that a
 * different client is not suppressed by a noisy neighbour.
 */
public class RequestErrorLimiterTest {

    private static final String PEER_A = "gbbkv6ordyceofn542c2aa2o24s77mbis3pzio77yegf4glqsmwq.b32.i2p";
    private static final String PEER_B = "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy.b32.i2p";
    private static final long T0 = 1_000_000L;

    @Before
    public void setUp() {
        I2PTunnelHTTPServer.resetRequestErrorLimiter();
    }

    /** The first request error for a client is always reported. */
    @Test
    public void firstOccurrenceLogs() {
        assertTrue(I2PTunnelHTTPServer.shouldLogRequestError(PEER_A, T0));
    }

    /** A repeat inside the interval is suppressed. */
    @Test
    public void repeatWithinIntervalSuppressed() {
        assertTrue(I2PTunnelHTTPServer.shouldLogRequestError(PEER_A, T0));
        assertFalse(I2PTunnelHTTPServer.shouldLogRequestError(PEER_A, T0 + 1));
        assertFalse(I2PTunnelHTTPServer.shouldLogRequestError(PEER_A, T0 + 1000));
    }

    /** Suppression ends exactly at the interval boundary, not before it. */
    @Test
    public void logsAgainAtIntervalBoundary() {
        assertTrue(I2PTunnelHTTPServer.shouldLogRequestError(PEER_A, T0));
        long interval = I2PTunnelHTTPServer.REQUEST_ERROR_LOG_INTERVAL_MS;
        assertFalse(I2PTunnelHTTPServer.shouldLogRequestError(PEER_A, T0 + interval - 1));
        assertTrue(I2PTunnelHTTPServer.shouldLogRequestError(PEER_A, T0 + interval));
    }

    /** Suppression is per client: one noisy peer must not silence another. */
    @Test
    public void distinctClientsAreIndependent() {
        assertTrue(I2PTunnelHTTPServer.shouldLogRequestError(PEER_A, T0));
        for (int i = 0; i < 50; i++) {
            assertFalse(I2PTunnelHTTPServer.shouldLogRequestError(PEER_A, T0 + i * 1000));
        }
        assertTrue(I2PTunnelHTTPServer.shouldLogRequestError(PEER_B, T0 + 60_000));
    }

    /**
     * A burst of distinct peers must not grow the tracking map without bound.
     * Stale entries are evicted once the cap is reached.
     */
    @Test
    public void mapStaysBoundedUnderDistinctPeerBurst() {
        long interval = I2PTunnelHTTPServer.REQUEST_ERROR_LOG_INTERVAL_MS;
        for (int i = 0; i < I2PTunnelHTTPServer.MAX_TRACKED_ERROR_CLIENTS * 2; i++) {
            I2PTunnelHTTPServer.shouldLogRequestError("peer-" + i + ".b32.i2p", T0);
        }
        assertTrue("map grew unbounded: " + I2PTunnelHTTPServer.trackedErrorClients(),
                   I2PTunnelHTTPServer.trackedErrorClients() <= I2PTunnelHTTPServer.MAX_TRACKED_ERROR_CLIENTS);
        // Two intervals later every entry is stale, so the next burst evicts them.
        for (int i = 0; i < I2PTunnelHTTPServer.MAX_TRACKED_ERROR_CLIENTS; i++) {
            I2PTunnelHTTPServer.shouldLogRequestError("fresh-" + i + ".b32.i2p", T0 + (2 * interval));
        }
        assertTrue("stale entries were not evicted: " + I2PTunnelHTTPServer.trackedErrorClients(),
                   I2PTunnelHTTPServer.trackedErrorClients() <= I2PTunnelHTTPServer.MAX_TRACKED_ERROR_CLIENTS);
    }

    /**
     * A burst of distinct peers arriving inside a single interval leaves
     * nothing stale to prune, so pruning alone would not bound the map.
     * This was a real defect found by {@link #mapStaysBoundedUnderDistinctPeerBurst}.
     */
    @Test
    public void burstWithinOneIntervalStaysBounded() {
        for (int i = 0; i < I2PTunnelHTTPServer.MAX_TRACKED_ERROR_CLIENTS * 3; i++) {
            // every call at the same instant: nothing is ever stale
            I2PTunnelHTTPServer.shouldLogRequestError("burst-" + i + ".b32.i2p", T0);
        }
        assertTrue("map grew unbounded within one interval: "
                   + I2PTunnelHTTPServer.trackedErrorClients(),
                   I2PTunnelHTTPServer.trackedErrorClients() <= I2PTunnelHTTPServer.MAX_TRACKED_ERROR_CLIENTS);
    }

    /**
     * An unknown client (null base32) is always logged. Suppressing it would
     * hide errors we cannot attribute, and there is nothing to rate limit.
     */
    @Test
    public void nullClientAlwaysLogs() {
        assertTrue(I2PTunnelHTTPServer.shouldLogRequestError(null, T0));
        assertTrue(I2PTunnelHTTPServer.shouldLogRequestError(null, T0));
        assertEquals(0, I2PTunnelHTTPServer.trackedErrorClients());
    }

    /** Suppression must not consume tracking slots. */
    @Test
    public void suppressedRepeatsDoNotAddEntries() {
        assertTrue(I2PTunnelHTTPServer.shouldLogRequestError(PEER_A, T0));
        for (int i = 1; i < 100; i++) {
            I2PTunnelHTTPServer.shouldLogRequestError(PEER_A, T0 + i);
        }
        assertEquals(1, I2PTunnelHTTPServer.trackedErrorClients());
    }
}
