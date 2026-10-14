package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pure streaming retransmission-rate decision
 * {@link Tuner#streamingLossRate} and its two thresholds
 * {@link Tuner#isStreamLossy} / {@link Tuner#isStreamLossSevere}, extracted
 * from {@code BaseParam#getStreamingLossRate}.
 *
 * <p>This is the signal that replaced the old {@code sendDuplicateSize}
 * byte-size heuristic: a byte count says nothing about how much of the
 * traffic was resent, so a low-window connection with one large duplicate
 * looked worse than a high-window connection resending constantly. The rate
 * is dimensionless, so the same 20% / 60% thresholds apply to every
 * window-sized parameter regardless of message size.
 *
 * @since 0.9.71+
 */
public class TunerStreamingLossRateTest {

    // ----- streamingLossRate: primary event-count ratio -----

    @Test
    public void eventCountRatioIsThePrimarySignal() {
        assertEquals(0.5, Tuner.streamingLossRate(5, 10, Double.NaN, Double.NaN), 0.0001);
        // the closed-stream ratios must not override a live signal
        assertEquals(0.25, Tuner.streamingLossRate(1, 4, 999, 999), 0.0001);
    }

    @Test
    public void eventCountRatioIsClampedToOne() {
        // 20 duplicates out of 10 sends is not a 200% rate
        assertEquals(1.0, Tuner.streamingLossRate(20, 10, Double.NaN, Double.NaN), 0.0001);
    }

    @Test
    public void eventCountRatioIsClampedToZero() {
        assertEquals(0.0, Tuner.streamingLossRate(-1, 10, Double.NaN, Double.NaN), 0.0001);
    }

    @Test
    public void zeroSendEventsFallThroughRatherThanDivide() {
        assertEquals(Double.NaN, Tuner.streamingLossRate(5, 0, Double.NaN, Double.NaN), 0.0);
    }

    // ----- streamingLossRate: closed-stream fallbacks -----

    @Test
    public void byteRatioIsUsedWhenNoLiveCounts() {
        // stream.rtxRatioBytes is per-mille, so 250 → 0.25
        assertEquals(0.25, Tuner.streamingLossRate(Double.NaN, Double.NaN, Double.NaN, 250), 0.0001);
    }

    @Test
    public void messageRatioIsUsedWhenTheByteRatioIsAbsent() {
        assertEquals(0.5, Tuner.streamingLossRate(Double.NaN, Double.NaN, 500, Double.NaN), 0.0001);
    }

    @Test
    public void byteRatioOutranksMessageRatio() {
        // both emitted at the same close; the byte view is the bandwidth truth
        assertEquals(0.1, Tuner.streamingLossRate(Double.NaN, Double.NaN, 900, 100), 0.0001);
    }

    @Test
    public void fallbackRatiosAreClamped() {
        assertEquals(1.0, Tuner.streamingLossRate(Double.NaN, Double.NaN, 5000, Double.NaN), 0.0001);
        assertEquals(0.0, Tuner.streamingLossRate(Double.NaN, Double.NaN, Double.NaN, 0), 0.0001);
    }

    @Test
    public void negativeFallbackRatiosAreIgnored() {
        // -1 is StatManager's "no value" sentinel, not a negative loss rate
        assertEquals(Double.NaN, Tuner.streamingLossRate(Double.NaN, Double.NaN, -1, Double.NaN), 0.0);
        assertEquals(Double.NaN, Tuner.streamingLossRate(Double.NaN, Double.NaN, Double.NaN, -1), 0.0);
    }

    @Test
    public void noSignalAtAllIsNaN() {
        assertEquals(Double.NaN,
                     Tuner.streamingLossRate(Double.NaN, Double.NaN, Double.NaN, Double.NaN), 0.0);
    }

    // ----- isStreamLossy: the 20% growth gate -----

    @Test
    public void lossyThresholdIsInclusive() {
        assertFalse(Tuner.isStreamLossy(Double.NaN));
        assertFalse(Tuner.isStreamLossy(0.0));
        assertFalse(Tuner.isStreamLossy(0.19999));
        assertTrue("exactly 20% must count as lossy", Tuner.isStreamLossy(0.20));
        assertTrue(Tuner.isStreamLossy(1.0));
    }

    // ----- isStreamLossSevere: the 60% aggressive-shrink gate -----

    @Test
    public void severeThresholdIsInclusive() {
        assertFalse(Tuner.isStreamLossSevere(Double.NaN));
        assertFalse(Tuner.isStreamLossSevere(0.19));
        assertFalse(Tuner.isStreamLossSevere(0.59999));
        assertTrue("exactly 60% must count as severe", Tuner.isStreamLossSevere(0.60));
        assertTrue(Tuner.isStreamLossSevere(1.0));
    }

    /** Everything that is severe is also lossy; the tiers cannot invert. */
    @Test
    public void severeImpliesLossy() {
        for (double r : new double[] { 0.0, 0.1, 0.19, 0.2, 0.5, 0.6, 1.0 }) {
            if (Tuner.isStreamLossSevere(r))
                assertTrue("severe " + r + " must also be lossy", Tuner.isStreamLossy(r));
        }
    }
}
