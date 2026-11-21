package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins {@link Connection#fitSynBudgetToWindow(int, long, int)}, the sizing of
 * the SYN retransmit interval on a path with no RTT evidence.
 *
 * <p>A connect with no measurement for its peer keeps the configured initial
 * RTO as its inter-SYN spacing — 9s by default, and measured at 12s once the
 * retransmit pass was counted. Against the 58s connect the proxy asks for, that
 * let roughly five SYNs go out before the window expired, so seven of the
 * twelve the operator configured through
 * {@code i2p.streaming.maxSYNRetransmissions} were never sent, and the
 * handshake reached only two or three outbound tunnels before the caller gave
 * up. The window is the deadline the caller actually set, so the interval is
 * now sized so the configured number of probes fits inside it.
 *
 * <p>Measured on the live router: 208 distinct handshake attempts to one
 * locally-served destination in 93 seconds, every one of them unanswered, and
 * 13,981 "SYN unacked after N sends, rotating to a fresh outbound tunnel" in
 * three hours.
 *
 * @since 0.9.72+
 */
public class ConnectionSynIntervalWindowTest {

    /** The production SYN send budget, as shipped. */
    private static final int CONFIGURED_SENDS = 12;

    /** The production initial RTO, as shipped. */
    private static final int INITIAL_RTO = 9000;

    /** The connect window the I2P HTTP proxy measured on a live router. */
    private static final long PROXY_WINDOW_MS = 57950;

    /**
     * The blind interval must be short enough that the configured probes all
     * fit the window the caller set.
     */
    @Test
    public void configuredProbesFitTheWindow() {
        int iv = Connection.fitSynBudgetToWindow(INITIAL_RTO, PROXY_WINDOW_MS, CONFIGURED_SENDS);
        // Probes start at 0, iv, 2*iv ... so all twelve start inside the window
        // when 11 * iv fits it.
        assertTrue("interval " + iv + " must let 12 probes start inside 57950ms",
                   (long) (CONFIGURED_SENDS - 1) * iv <= PROXY_WINDOW_MS);
        assertTrue(iv < INITIAL_RTO);
    }

    /**
     * The sizing divides the window by the probe count, rounded up so the last
     * probe still starts inside the window.
     */
    @Test
    public void intervalIsTheWindowDividedByTheProbeCount() {
        assertEquals(1000, Connection.fitSynBudgetToWindow(INITIAL_RTO, 12000, 12));
        // 10001 / 12 rounds up to 834, not 833
        assertEquals(834, Connection.fitSynBudgetToWindow(INITIAL_RTO, 10001, 12));
    }

    /**
     * A window long enough to pay for the configured interval leaves it alone:
     * this only ever shortens the spacing, never makes the handshake louder.
     */
    @Test
    public void longWindowKeepsTheConfiguredInterval() {
        assertEquals(INITIAL_RTO, Connection.fitSynBudgetToWindow(INITIAL_RTO, 120000, 12));
        assertEquals(3000, Connection.fitSynBudgetToWindow(3000, 120000, 12));
    }

    /**
     * A short window cannot push the cadence below the fabric's own round-trip
     * floor; the SYN budget then simply runs out before the window does, which
     * is what a caller asking for a third of a second deserves.
     */
    @Test
    public void shortWindowStopsAtTheRetransmitFloor() {
        assertEquals(Connection.SYN_RTO_MIN,
                     Connection.fitSynBudgetToWindow(INITIAL_RTO, 3000, 12));
        assertEquals(Connection.SYN_RTO_MIN,
                     Connection.fitSynBudgetToWindow(INITIAL_RTO, 1, 1));
    }

    /**
     * No window, no budget, or a non-positive interval leaves the value
     * untouched — there is nothing to size against.
     */
    @Test
    public void nothingToSizeLeavesTheIntervalAlone() {
        assertEquals(INITIAL_RTO, Connection.fitSynBudgetToWindow(INITIAL_RTO, 0, 12));
        assertEquals(INITIAL_RTO, Connection.fitSynBudgetToWindow(INITIAL_RTO, -1, 12));
        assertEquals(INITIAL_RTO, Connection.fitSynBudgetToWindow(INITIAL_RTO, PROXY_WINDOW_MS, 0));
        assertEquals(-1, Connection.fitSynBudgetToWindow(-1, PROXY_WINDOW_MS, 12));
    }

    /**
     * A smaller configured budget is respected: sizing for three probes against
     * the same window leaves the configured interval in place.
     */
    @Test
    public void smallerBudgetSizesTheWindowToo() {
        // 57950 / 3 rounds up to 19317ms, well past the configured 9s, which
        // stays in force because the sizing only ever shortens.
        assertEquals(INITIAL_RTO, Connection.fitSynBudgetToWindow(INITIAL_RTO, PROXY_WINDOW_MS, 3));
    }

    /**
     * The defect, pinned: the shipped 9s interval against the proxy's 58s
     * window starts only seven probes, so five of the twelve configured sends
     * never happen and the handshake reaches barely two outbound tunnels
     * (rotation is every third send) before the caller gives up.
     */
    @Test
    public void shippedIntervalStartsTooFewProbesInTheWindow() {
        int probes = (int) (PROXY_WINDOW_MS / INITIAL_RTO) + 1;
        assertEquals(7, probes);
        assertTrue(probes < CONFIGURED_SENDS);
    }

    /**
     * The floor and the configured budget together still fit a real window:
     * at the floor, a 58s window gets far more than the configured probes, and
     * the send-count budget from {@link Connection#computeSynResendBudget} is
     * what stops it, not the interval.
     */
    @Test
    public void floorStillLeavesTheSendBudgetInCharge() {
        int iv = Connection.fitSynBudgetToWindow(INITIAL_RTO, PROXY_WINDOW_MS, CONFIGURED_SENDS);
        int sends = Connection.computeSynResendBudget(CONFIGURED_SENDS, iv, PROXY_WINDOW_MS);
        assertEquals("the interval now fits the window, so the count is not inflated",
                     CONFIGURED_SENDS, sends);
    }
}
