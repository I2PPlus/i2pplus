package net.i2p.client.streaming.impl;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * When the tail loss probe fires. The target is two round trips, but the ceiling
 * used to be a flat 2000 ms, and this tree's retransmit timer is about 1.5 round
 * trips, so on any path slower than ~1s the probe went off inside one round trip,
 * before the acknowledgement it was waiting for could arrive. A 3s path probed at
 * 2s - 0.67 RTT - so every stall bought a guaranteed wasted probe.
 *
 * @since 0.9.72+
 */
public class ConnectionProbeTimeoutDecisionTest {

    /** Runs the cases; it holds no state of its own. */
    public ConnectionProbeTimeoutDecisionTest() {
        super();
    }

    /**
     * A fast path keeps the cadence it has always had: the derived ceiling never
     * falls below the 2000 ms it used to be capped at, so nothing that was not
     * broken moves.
     */
    @Test
    public void fastPathIsUnchanged() {
        assertEquals(1000, Connection.computeProbeTimeoutMs(500, 750));
        assertEquals(2000, Connection.computeProbeTimeoutMs(1000, 1500));
        assertEquals(2000, Connection.computeProbeTimeoutMs(1200, 1800));
    }

    /**
     * A slow path probes outside one round trip. This is the defect: at a 3s RTT
     * the old cap fired at 2s, two thirds of the way to an acknowledgement that
     * could not have arrived yet.
     */
    @Test
    public void slowPathDoesNotProbeInsideOneRoundTrip() {
        int probe = Connection.computeProbeTimeoutMs(3000, 4500);
        assertEquals(3375, probe);
        assertTrue("probe " + probe + " must not precede one round trip of 3000ms",
                   probe > 3000);
    }

    /**
     * The probe exists to pre-empt the retransmit timer, so once the ceiling is
     * derived from the path it stays ahead of that timer.
     */
    @Test
    public void derivedCeilingStillPreemptsTheRetransmitTimer() {
        for (int rtt = 2000; rtt <= 10000; rtt += 1000) {
            int rto = rtt + rtt / 2;
            int probe = Connection.computeProbeTimeoutMs(rtt, rto);
            assertTrue("probe " + probe + " inside one RTT of " + rtt, probe > rtt);
            assertTrue("probe " + probe + " later than the RTO " + rto, probe < rto);
        }
    }

    /** A backoff that has grown the timer widens the probe with it, never past 2 RTT. */
    @Test
    public void grownTimerNeverPushesTheProbePastTwoRoundTrips() {
        assertEquals(6000, Connection.computeProbeTimeoutMs(3000, 20000));
    }

    /** The floor holds however fast the path is. */
    @Test
    public void floorHoldsOnAFastPath() {
        assertEquals(Connection.PROBE_MIN_MS, Connection.computeProbeTimeoutMs(50, 100));
    }

    /**
     * With no round trip measured yet the probe is half the retransmit timer,
     * bounded, and a timer of nothing probes at nothing.
     */
    @Test
    public void noMeasurementFallsBackToHalfTheTimer() {
        assertEquals(3000, Connection.computeProbeTimeoutMs(0, 6000));
        assertEquals(500, Connection.computeProbeTimeoutMs(-1, 1000));
        assertEquals(0, Connection.computeProbeTimeoutMs(0, 0));
        assertEquals(0, Connection.computeProbeTimeoutMs(0, -500));
    }
}
