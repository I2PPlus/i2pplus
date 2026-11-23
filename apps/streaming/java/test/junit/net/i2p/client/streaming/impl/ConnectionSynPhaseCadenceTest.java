package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the agreement between the handshake's retransmit cadence and the budgets
 * that are sized from it.
 *
 * <p>{@link Connection#computeSynPhaseBudgetMs(long, int, int)} and
 * {@link Connection#getMaxSynSends} both size the unacknowledged-SYN phase from
 * the SYN retransmit interval, and
 * {@link Connection#fitSynBudgetToWindow(int, long, int)} shortens that interval
 * so the configured probe count fits the caller's connect window. The phase only
 * gets those probes if the retransmit timer is actually armed with the same
 * interval. It was not: the send that put the SYN on the fabric armed the timer
 * with the connection's RTO, 9s by default, while the budget had been sized from
 * a window-fitted 1250ms interval and floored at 8s. The first pass therefore
 * fired at 9.0s, past the 8s budget, and the give-up branch ended the handshake
 * with one SYN on the fabric.
 *
 * <p>Measured on a live router over 30 minutes: 274 handshake give-ups, and for
 * the locally-hosted eepsite 24 proxy connects of which 7 opened in 0.8-3.0s and
 * the rest died at 9.0s having sent a single SYN, against a 15000ms connect
 * window that the sizing says holds twelve probes.
 *
 * @since 0.9.72+
 */
public class ConnectionSynPhaseCadenceTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state.
     *
     * @since 0.9.72+
     */
    public ConnectionSynPhaseCadenceTest() {}

    /** The production SYN send budget, as shipped. */
    private static final int CONFIGURED_SENDS = 12;

    /** The production initial RTO, as shipped. */
    private static final int INITIAL_RTO = 9000;

    /** The connect window the I2P HTTP proxy asked for, measured live. */
    private static final long PROXY_WINDOW_MS = 15000;

    /**
     * The handshake must be paced at the SYN interval, not at the RTO, and the
     * first probe must be able to fire inside the budget that interval produced.
     * This is the regression: the gap was the 9s RTO against an 8s budget.
     */
    @Test
    public void firstProbeFiresInsideTheBudgetItSized() {
        int interval = Connection.fitSynBudgetToWindow(INITIAL_RTO, PROXY_WINDOW_MS, CONFIGURED_SENDS);
        long budget = Connection.computeSynPhaseBudgetMs(PROXY_WINDOW_MS, -1, interval);
        int gap = Connection.computeRetransmitArmDelay(true, interval, INITIAL_RTO, false);
        assertEquals(1250, interval);
        assertEquals(8000, budget);
        assertEquals("the armed gap is the interval the budget was sized from", interval, gap);
        assertTrue("first probe at " + gap + "ms must fit the " + budget + "ms budget", gap <= budget);
    }

    /**
     * The defect, pinned: arming the first pass with the RTO spends the whole
     * handshake budget before that pass can fire, so the peer never gets a
     * second SYN and the caller's retry loop never sees a probe it could have
     * answered on a different outbound tunnel.
     */
    @Test
    public void rtoGapSpendsTheBudgetBeforeTheFirstProbe() {
        int interval = Connection.fitSynBudgetToWindow(INITIAL_RTO, PROXY_WINDOW_MS, CONFIGURED_SENDS);
        long budget = Connection.computeSynPhaseBudgetMs(PROXY_WINDOW_MS, -1, interval);
        assertTrue("the 9s RTO gap overshoots the " + budget + "ms budget it was sized past",
                   INITIAL_RTO > budget);
    }

    /**
     * A handshake on a path with a measured RTO is paced at the SYN interval
     * capped by that measurement, so the cadence can only shorten, never lengthen
     * a path that is already probing faster than the SYN interval asks.
     */
    @Test
    public void handshakeGapIsTheSynIntervalCappedByAMeasuredRto() {
        assertEquals(1250, Connection.computeRetransmitArmDelay(true, 1250, INITIAL_RTO, true));
        assertEquals(1500, Connection.computeRetransmitArmDelay(true, 9000, 1500, true));
        assertEquals(Connection.SYN_RTO_MIN,
                     Connection.computeRetransmitArmDelay(true, Connection.SYN_RTO_MIN,
                                                          INITIAL_RTO, true));
    }

    /**
     * A seed RTO is not a measurement, and the handshake's budgets are sized from
     * the SYN interval, so a seed shorter than that interval must not shorten the
     * gap. This is the case the faster pre-measurement seed creates: a Snark
     * connect window of 171,950ms fits twelve probes at 9s each, and a 2s seed
     * would have spent that budget in 24s of a handshake the caller said could run
     * for three minutes.
     */
    @Test
    public void seedRtoDoesNotShortenTheHandshakeGap() {
        int window = 171950;
        int interval = Connection.fitSynBudgetToWindow(INITIAL_RTO, window, CONFIGURED_SENDS);
        assertEquals(9000, interval);
        assertEquals("the seed is not evidence, so the interval holds", interval,
                     Connection.computeRetransmitArmDelay(true, interval, 2005, false));
        assertEquals("a measured 2005ms round trip does win", 2005,
                     Connection.computeRetransmitArmDelay(true, interval, 2005, true));
        long budget = Connection.computeSynPhaseBudgetMs(window, -1, interval);
        assertTrue("budget " + budget + "ms must stay inside the window", budget <= window);
        assertTrue("the armed gap must buy the probe floor",
                   budget / interval >= Connection.SYN_PHASE_BUDGET_MIN_PROBES);
    }

    /**
     * Once an ACK has advanced the connection out of the handshake, the RTO and
     * its congestion backoff govern and the SYN interval must not shorten them.
     */
    @Test
    public void establishedPathKeepsItsRto() {
        assertEquals(INITIAL_RTO,
                     Connection.computeRetransmitArmDelay(false, 1250, INITIAL_RTO, true));
        assertEquals(18000, Connection.computeRetransmitArmDelay(false, 1250, 18000, false));
    }

    /**
     * Across the range of connect windows a client may ask for, the budget must
     * still buy the probe floor at the gap that is actually armed - otherwise
     * the phase ends before the probes it promised, which is what made a
     * handshake a single coin flip.
     */
    @Test
    public void budgetBuysTheProbeFloorAtTheArmedGap() {
        long[] windows = {10000, 15000, 30000, 57950, 117650, 300000};
        for (long window : windows) {
            int interval = Connection.fitSynBudgetToWindow(INITIAL_RTO, window, CONFIGURED_SENDS);
            long budget = Connection.computeSynPhaseBudgetMs(window, -1, interval);
            int gap = Connection.computeRetransmitArmDelay(true, interval, INITIAL_RTO, false);
            long probes = budget / gap;
            assertTrue("window " + window + "ms buys " + probes + " probes at " + gap +
                       "ms, below the floor of " + Connection.SYN_PHASE_BUDGET_MIN_PROBES,
                       probes >= Connection.SYN_PHASE_BUDGET_MIN_PROBES);
            assertTrue("window " + window + "ms budget " + budget + " must stay inside the window",
                       budget <= window);
        }
    }

    /**
     * A path with RTT evidence keeps the evidence-sized interval as its gap, and
     * the budget sized from that same interval still buys the probe floor.
     */
    @Test
    public void measuredPathKeepsBudgetAndGapAgreeing() {
        // 1.5s round trip, 150% headroom
        int interval = Connection.computeSynRetransmitInterval(1000, INITIAL_RTO);
        long budget = Connection.computeSynPhaseBudgetMs(PROXY_WINDOW_MS, 1000, interval);
        int gap = Connection.computeRetransmitArmDelay(true, interval, 1250, true);
        assertEquals(1500, interval);
        assertEquals("the RTO is shorter than the SYN interval here, and wins", 1250, gap);
        assertTrue(budget / gap >= Connection.SYN_PHASE_BUDGET_MIN_PROBES);
    }
}
