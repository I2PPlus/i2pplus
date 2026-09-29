package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the sampling cadence for the two choke-depth stats in
 * {@link Connection#packetSendChoke(long)}:
 * {@link Connection#shouldSampleChokeSizeBegin(int)} and
 * {@link Connection#shouldSampleChokeSizeEnd(int)}.
 *
 * <p>Both stats are the Tuner's only input for choke-driven adaptation
 * ({@code MaxInboundBufferParam} and the params at {@code Tuner.java:3829},
 * {@code 6801} and {@code 6921} all read {@code stream.chokeSizeBegin}). The
 * prior {@code !started && (++cnt & 15) == 0} expression was unsatisfiable —
 * the mask required counter 16 while {@code !started} required counter 1 — so
 * the stat never emitted and every dependent parameter stayed pinned at its
 * default. These tests pin the reachable cadence.
 *
 * <p>Both helpers are pure, so no router is required.
 *
 * @since 0.9.71+
 */
public class ConnectionChokeSizeSamplingTest {

    private static final int PERIOD = 16;

    /** A first block must be recordable, or the stat is dead again. */
    @Test
    public void testBeginSamplesOnceCounterReachesPeriod() {
        for (int i = 1; i < PERIOD; i++) {
            assertFalse("counter " + i + " must not emit", Connection.shouldSampleChokeSizeBegin(i));
        }
        assertTrue("counter " + PERIOD + " must emit", Connection.shouldSampleChokeSizeBegin(PERIOD));
        assertTrue("beyond the period keeps emitting",
                   Connection.shouldSampleChokeSizeBegin(PERIOD + 1));
    }

    /**
     * The regression that mattered: counter 1 is the only counter reachable
     * while {@code !started} on the first iteration, and the old mask rejected
     * it. Period-based sampling must not reintroduce "never emits".
     */
    @Test
    public void testBeginIsReachableWithinFirstBlock() {
        boolean emitted = false;
        for (int i = 1; i <= PERIOD; i++) {
            if (Connection.shouldSampleChokeSizeBegin(i)) {
                emitted = true;
                break;
            }
        }
        assertTrue("chokeSizeBegin must become emit-able", emitted);
    }

    /** A successful release follows the same cadence. */
    @Test
    public void testEndSamplesOnceCounterReachesPeriod() {
        for (int i = 1; i < PERIOD; i++) {
            assertFalse("counter " + i + " must not emit", Connection.shouldSampleChokeSizeEnd(i));
        }
        assertTrue(Connection.shouldSampleChokeSizeEnd(PERIOD));
    }

    /**
     * A zero or negative counter never reaches the period, so a stat value of
     * zero outstanding messages can never be mistaken for a real observation.
     */
    @Test
    public void testZeroAndNegativeNeverEmit() {
        assertFalse(Connection.shouldSampleChokeSizeBegin(0));
        assertFalse(Connection.shouldSampleChokeSizeEnd(0));
        assertFalse(Connection.shouldSampleChokeSizeBegin(-1));
        assertFalse(Connection.shouldSampleChokeSizeEnd(-1));
    }

    /**
     * The counter is reset to zero by the caller after an emit, so the cadence
     * must be periodic rather than one-shot at some absolute counter value.
     */
    @Test
    public void testCadenceRepeatsAfterCallerReset() {
        int counter = 0;
        int emits = 0;
        for (int send = 0; send < PERIOD * 3; send++) {
            if (Connection.shouldSampleChokeSizeEnd(++counter)) {
                counter = 0;
                emits++;
            }
        }
        assertEquals("exactly one sample per period", 3, emits);
    }

    /**
     * Over a long idle-free run the emit rate must stay bounded — the whole
     * point of the period is to keep Rate lock traffic off the send path.
     */
    @Test
    public void testEmitRateIsBoundedByPeriod() {
        int sends = 10000;
        int counter = 0;
        int emits = 0;
        for (int i = 0; i < sends; i++) {
            if (Connection.shouldSampleChokeSizeEnd(++counter)) {
                counter = 0;
                emits++;
            }
        }
        assertEquals(sends / PERIOD, emits);
    }
}
