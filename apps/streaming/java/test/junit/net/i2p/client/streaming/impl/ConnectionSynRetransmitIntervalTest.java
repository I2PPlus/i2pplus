package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the evidence-gated, RTT-aware SYN retransmit interval decision in
 * {@link Connection#computeSynRetransmitInterval(int, int)}.
 *
 * <p>SYN retransmission uses a fixed interval (no backoff — there is no
 * congestion to manage before the connection is established), so the total
 * SYN budget is {@code maxSynResends * interval}.  The historical behavior is a
 * flat {@code initialRtoMs} (default 5000) for every connection.  That
 * over-shoots the 30s connect() timeout on a dead-but-previously-fast path
 * (12 * 5s = 60s) and under-utilizes the window on a healthy path whose
 * round-trip is below 5s.  The decision is now:
 *
 * <ul>
 *   <li><b>evidence-gated</b>: a path RTT of {@code <= 0} means there is no
 *       genuine measurement (a fresh or blackholed peer where {@code Received: 0}
 *       throughout leaves {@code getRTT()} at its initial default), so the
 *       configured {@code initialRtoMs} is returned unchanged — no cold-start or
 *       blackhole regression.</li>
 *   <li><b>RTT-aware interval</b>: with evidence, the interval is
 *       {@code max(SYN_RTO_MIN, 1.5 * measuredRttMs)}, capped at
 *       {@code initialRtoMs}, so a faster measured path packs more SYN attempts
 *       into the connect window and a dead-but-fast path fails sooner.</li>
 * </ul>
 *
 * @since 0.9.71+
 */
public class ConnectionSynRetransmitIntervalTest {

    /** No RTT evidence is returned unchanged (cold start / blackholed peer). */
    @Test
    public void testNoEvidenceKeepsConfiguredRto() {
        assertEquals(5000, Connection.computeSynRetransmitInterval(-1, 5000));
        assertEquals(5000, Connection.computeSynRetransmitInterval(0, 5000));
        assertEquals(500, Connection.computeSynRetransmitInterval(0, 500));
    }

    /** Fast measured path scales the interval down toward the RTT floor. */
    @Test
    public void testFastPathScalesBelowDefault() {
        // 1.5 * 1000 = 1500
        assertEquals(1500, Connection.computeSynRetransmitInterval(1000, 5000));
        // very fast fabric -> floored at min
        assertEquals(Connection.SYN_RTO_MIN,
                     Connection.computeSynRetransmitInterval(100, 5000));
        assertEquals(Connection.SYN_RTO_MIN,
                     Connection.computeSynRetransmitInterval(300, 5000));
    }

    /** A slow measured path is capped at the configured RTO, never lengthened. */
    @Test
    public void testSlowPathCappedAtConfiguredRto() {
        assertEquals(5000, Connection.computeSynRetransmitInterval(5000, 5000));
        assertEquals(5000, Connection.computeSynRetransmitInterval(6000, 5000));
        assertEquals(30000, Connection.computeSynRetransmitInterval(20000, 30000));
        // 1.5 * 10000 = 15000, below a 30000 configured cap
        assertEquals(15000, Connection.computeSynRetransmitInterval(10000, 30000));
    }

    /** Exact 1.5x headroom boundary. */
    @Test
    public void testHeadroomBoundary() {
        assertEquals(1500, Connection.computeSynRetransmitInterval(1000, 5000));
        // 1.5 * 600 = 900 (above the 750 floor, below 5000)
        assertEquals(900, Connection.computeSynRetransmitInterval(600, 5000));
        // 1.5 * 500 = 750 == floor
        assertEquals(Connection.SYN_RTO_MIN,
                     Connection.computeSynRetransmitInterval(500, 5000));
    }

    /** Integer headroom truncation: 1.5 * 999 -> 1498 (integer division), still sane. */
    @Test
    public void testHeadroomTruncation() {
        assertEquals(1498, Connection.computeSynRetransmitInterval(999, 5000));
        assertTrue(Connection.computeSynRetransmitInterval(999, 5000) < 5000);
    }

    /** Non-positive configured RTO passes through unchanged. */
    @Test
    public void testNonPositiveConfiguredRto() {
        assertEquals(0, Connection.computeSynRetransmitInterval(1000, 0));
        assertEquals(-5, Connection.computeSynRetransmitInterval(1000, -5));
    }

    /** A capped configured RTO at/below the floor is left alone, never forced shorter than the ceiling. */
    @Test
    public void testConfiguredBelowFloorRespected() {
        // configured 700 < floor 750 -> return configured unchanged (can't go below what's configured)
        assertEquals(700, Connection.computeSynRetransmitInterval(10000, 700));
    }

    /** Large measured RTT must not overflow; result is capped at the configured RTO. */
    @Test
    public void testLargeValuesNoOverflow() {
        assertEquals(5000, Connection.computeSynRetransmitInterval(Integer.MAX_VALUE, 5000));
        assertEquals(30000, Connection.computeSynRetransmitInterval(Integer.MAX_VALUE, 30000));
    }
}
