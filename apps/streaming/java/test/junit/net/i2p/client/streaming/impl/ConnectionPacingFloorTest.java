package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pure pacing-rate policy {@link Connection#effectivePacingRate(int, int, int, long)},
 * extracted from {@code Connection.calculatePacingRate}.
 *
 * <p>The regression this pins: the pacing rate was
 * {@code max(cwnd * mss / rtt, minPacingRate)}, so a Tuner-held floor replaced the
 * congestion window's own pace whenever it sat above it. Live, the Tuner had
 * climbed to 192 KB/s while a 128-message window at a 1,578ms RTT derives
 * 111 KB/s, so the pacer released that window 1.7x faster than the path could
 * acknowledge it - and the burst loss that caused is what made the Tuner raise
 * the floor further. The floor now only outruns the window when the window's own
 * pace would leave the stream idle between packets.
 *
 * @since 0.9.72+
 */
public class ConnectionPacingFloorTest {

    /** The house MSS used by every case here. */
    private static final int MSS = 1400;

    /** The shipped streaming floor, 16 KB/s. */
    private static final long SHIPPED_FLOOR = 16 * 1024;

    /** The floor the Tuner had ratcheted to on the live router, 192 KB/s. */
    private static final long TUNED_FLOOR = 192 * 1024;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one. The policy under test is static and holds no state.
     *
     * @since 0.9.72+
     */
    public ConnectionPacingFloorTest() {}

    /**
     * The live case: a full 128-message window at a 1,578ms RTT derives 111 KB/s,
     * spacing packets 12ms apart, and the 192 KB/s floor must not replace it.
     */
    @Test
    public void floorDoesNotOutrunAHealthyWindow() {
        long derived = Connection.effectivePacingRate(128, MSS, 1578, TUNED_FLOOR);
        assertEquals(113_561L, derived);
        assertTrue("the floor replaced the window pace: " + derived, derived < TUNED_FLOOR);
    }

    /**
     * A window whose own pace would leave 8s between packets is stalled, and that
     * is the case the floor exists for.
     */
    @Test
    public void floorRescuesAStalledWindow() {
        long rate = Connection.effectivePacingRate(1, MSS, 8_000, SHIPPED_FLOOR);
        assertEquals(SHIPPED_FLOOR, rate);
        // and a bigger floor rescues harder, up to the operator's setting
        assertEquals(TUNED_FLOOR, Connection.effectivePacingRate(1, MSS, 8_000, TUNED_FLOOR));
    }

    /** A floor below the window's pace is simply not binding, as before. */
    @Test
    public void floorBelowTheWindowPaceIsInert() {
        long derived = Connection.effectivePacingRate(64, MSS, 1_000, SHIPPED_FLOOR);
        assertEquals(64L * MSS, derived);
        assertEquals(derived, Connection.effectivePacingRate(64, MSS, 1_000, 1L));
    }

    /**
     * The gate is spacing, not rate: at exactly the stall spacing the floor stays
     * out of the way, and one ms past it the floor engages.
     */
    @Test
    public void gateIsTheStallSpacingNotTheRate() {
        // 14 messages at a 1,400ms RTT is 14 KB/s, exactly 100ms per MSS
        assertEquals(14_000L, Connection.effectivePacingRate(14, MSS, 1_400, SHIPPED_FLOOR));
        // 1,500ms puts the spacing at 107ms, past the gate
        assertEquals(SHIPPED_FLOOR, Connection.effectivePacingRate(14, MSS, 1_500, SHIPPED_FLOOR));
    }

    /** Unset RTT, window or MSS disables pacing, as it always has. */
    @Test
    public void unsetInputsDisablePacing() {
        assertEquals(Long.MAX_VALUE, Connection.effectivePacingRate(128, MSS, 0, SHIPPED_FLOOR));
        assertEquals(Long.MAX_VALUE, Connection.effectivePacingRate(0, MSS, 1_000, SHIPPED_FLOOR));
        assertEquals(Long.MAX_VALUE, Connection.effectivePacingRate(128, 0, 1_000, SHIPPED_FLOOR));
        assertEquals(Long.MAX_VALUE, Connection.effectivePacingRate(-1, -1, -1, SHIPPED_FLOOR));
    }

    /**
     * The result is never slower than the window's own pace, whatever the floor:
     * the floor can only ever speed a stream up, never hold it back.
     */
    @Test
    public void neverSlowerThanTheWindowPace() {
        for (int cwnd = 1; cwnd <= 128; cwnd *= 2) {
            for (int rtt = 500; rtt <= 8_000; rtt += 500) {
                long derived = (long) cwnd * MSS * 1000 / rtt;
                long rate = Connection.effectivePacingRate(cwnd, MSS, rtt, TUNED_FLOOR);
                assertTrue("cwnd " + cwnd + " rtt " + rtt + " gave " + rate, rate >= derived);
            }
        }
    }

    /**
     * The gate and the shipped floor name the same region: 100ms of spacing at
     * this MSS is 14 KB/s, just under the 16 KB/s default, so the floor engages
     * exactly where it starts to matter.
     */
    @Test
    public void gateMatchesTheShippedFloor() {
        long atGate = (long) MSS * 1000 / Connection.PACING_FLOOR_STALL_MS;
        assertTrue("gate " + atGate, atGate <= SHIPPED_FLOOR);
        assertTrue("gate " + atGate, atGate * 2 > SHIPPED_FLOOR);
    }
}
