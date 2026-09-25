package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link PeerState#effectiveRTO(int, int, int)}, the clamp that turns
 * a raw retransmission-timeout estimate into the value a peer actually waits
 * under before retransmitting.
 *
 * <p>The raw estimate ({@code _rto}) is what the aggregate stats have always
 * reported as {@code udp.avgRTO}; the clamped value is what scheduling uses.
 * Keeping the clamp testable on its own lets the new effective-RTO telemetry be
 * verified without standing up a transport.
 *
 * @since 0.9.71+
 */
public class PeerStateRtoTest {

    private static final int MIN = 1000;
    private static final int MAX = 60000;

    @Test
    public void testInWindowIsUnchanged() {
        assertEquals(MIN, PeerState.effectiveRTO(MIN, MIN, MAX));
        assertEquals(MAX, PeerState.effectiveRTO(MAX, MIN, MAX));
        assertEquals(1500, PeerState.effectiveRTO(1500, MIN, MAX));
        assertEquals(59999, PeerState.effectiveRTO(59999, MIN, MAX));
    }

    @Test
    public void testBelowFloorIsRaised() {
        assertEquals(MIN, PeerState.effectiveRTO(0, MIN, MAX));
        assertEquals(MIN, PeerState.effectiveRTO(-1, MIN, MAX));
        assertEquals(MIN, PeerState.effectiveRTO(MIN - 1, MIN, MAX));
        assertEquals(500, PeerState.effectiveRTO(500, 500, MAX));
    }

    @Test
    public void testAboveCeilingIsLowered() {
        assertEquals(MAX, PeerState.effectiveRTO(MAX + 1, MIN, MAX));
        assertEquals(MAX, PeerState.effectiveRTO(Integer.MAX_VALUE, MIN, MAX));
    }

    /**
     * The clamped value must never leave the window, whatever the raw estimate
     * is — that is the invariant {@link PeerState#getRTO()} promises.
     */
    @Test
    public void testResultAlwaysInsideWindow() {
        int[] samples = { Integer.MIN_VALUE, -5000, 0, 1, 999, 1000, 1001,
                          30000, 60000, 60001, 120000, Integer.MAX_VALUE };
        for (int i = 0; i < samples.length; i++) {
            int effective = PeerState.effectiveRTO(samples[i], MIN, MAX);
            assertTrue("raw " + samples[i] + " clamped to " + effective,
                       effective >= MIN && effective <= MAX);
        }
    }

    /**
     * Degenerate window (ceiling below floor): still floors the value so a
     * caller cannot end up below MIN_RTO.
     */
    @Test
    public void testInvertedWindowStillFloors() {
        assertEquals(MIN, PeerState.effectiveRTO(0, MIN, MIN - 1));
        assertEquals(MIN, PeerState.effectiveRTO(MIN, MIN, MIN - 1));
        assertEquals(2000, PeerState.effectiveRTO(2000, MIN, MIN - 1));
    }

    /**
     * A raw estimate below the floor is exactly where raw and effective
     * telemetry must disagree — the whole point of tracking both.
     */
    @Test
    public void testRawAndEffectiveDivergeWhenClamped() {
        int raw = 500;
        int effective = PeerState.effectiveRTO(raw, MIN, MAX);
        assertEquals(MIN, effective);
        assertTrue("raw estimate and effective RTO must differ when clamped",
                   effective > raw);
    }
}
