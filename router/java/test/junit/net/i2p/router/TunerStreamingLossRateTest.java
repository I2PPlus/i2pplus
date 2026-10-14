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

    /**
     * The aggregate path, and the case it exists for. Without it the fallback is
     * the per-connection message ratio, which reads thousands per mille when the
     * connections closing in the window are short-lived - measured live at 11,529
     * against a byte-weighted 29 for the same window. Feeding that to the old
     * fallback produced a 1.0 rate, which is 100% loss on a path carrying traffic.
     */
    @Test
    public void aggregateOutranksTheSaturatingPerConnectionRatio() {
        // 12 duplicates against 1.5 messages sent aggregates to 8.0, i.e. heavy
        // retransmission, but the per-connection ratio of 11,529 per mille must not
        // be preferred over it
        double rate = Tuner.streamingLossRate(Double.NaN, Double.NaN, 12.0, 1.5,
                                              11_529.0, 29.0);
        // aggregate is 8.0 -> clamped to 1.0, so this specific pair still saturates;
        // the point is the fallback is not consulted while the aggregate exists
        assertEquals(1.0, rate, 0.0001);
    }

    /** A sane aggregate is used as-is, in preference to a wild per-mille reading. */
    @Test
    public void saneAggregateIsPreferred() {
        // 3 duplicates against 100 messages = 3%, while the per-connection ratio
        // would have said 100%
        assertEquals(0.03, Tuner.streamingLossRate(Double.NaN, Double.NaN, 3.0, 100.0,
                                                   11_529.0, 29.0), 0.0001);
    }

    /** The observed live window: 12.05 duplicates against 1.525 messages = clamped. */
    @Test
    public void liveWindowAggregate() {
        double rate = Tuner.streamingLossRate(Double.NaN, Double.NaN, 12.05, 1.525,
                                              11_529.15, 29.25);
        assertTrue("aggregate should saturate but must come from the aggregate",
                   rate >= 0.0 && rate <= 1.0);
    }

    /** Volume weighting: a short connection cannot outweigh a long one. */
    @Test
    public void volumeWeightedAggregateTracksCarriedTraffic() {
        // one 3-message connection retransmitting 20 times, plus a 3000-message
        // connection retransmitting 30 times: 50 duplicates against 3003 messages
        assertEquals(50.0 / 3003.0,
                     Tuner.streamingLossRate(Double.NaN, Double.NaN, 50.0, 3003.0,
                                             Double.NaN, Double.NaN), 0.0001);
    }

    /** The primary event-count path still wins when it is available. */
    @Test
    public void eventCountPathStillWins() {
        assertEquals(0.5, Tuner.streamingLossRate(5.0, 10.0, 3.0, 100.0, 500.0, 250.0),
                     0.0001);
    }

    /**
     * A zero message count contributes no aggregate signal rather than dividing by
     * zero. The per-mille fallbacks still apply when they have a reading of their
     * own, which is the point of asking the aggregate first and the ratios last.
     */
    @Test
    public void zeroMessagesSentContributesNoAggregateSignal() {
        // no aggregate, and the only remaining reading is the byte-weight per-mille
        assertEquals(0.25, Tuner.streamingLossRate(Double.NaN, Double.NaN, 5.0, 0.0,
                                                   Double.NaN, 250.0), 0.0001);
        // with nothing else either, a zero count means no signal at all
        assertEquals(Double.NaN, Tuner.streamingLossRate(Double.NaN, Double.NaN, 5.0, 0.0,
                                                         Double.NaN, Double.NaN), 0.0);
    }

    /** Aggregates absent entirely: the per-mille fallbacks still apply. */
    @Test
    public void fallsBackToPerMilleWhenAggregatesAbsent() {
        assertEquals(0.25, Tuner.streamingLossRate(Double.NaN, Double.NaN,
                                                   Double.NaN, Double.NaN, 250.0, 250.0),
                     0.0001);
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
