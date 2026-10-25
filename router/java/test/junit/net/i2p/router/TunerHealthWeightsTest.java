package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the health-composite weight table {@link Tuner.SystemHealth#HEALTH_WEIGHTS}
 * that {@code Tuner.SystemHealth.compute()} feeds to
 * {@link Tuner.SystemHealth#compositeScore}.
 *
 * <p>Streaming was not a factor in the composite at all, so a router whose
 * streams were retransmitting themselves to death still reported a healthy score,
 * and {@code BaseParam.autoRevertToDefault()} — which only fires below
 * {@code DEGRADED_THRESHOLD} — never pulled the auto-tuned parameters back. The
 * table is what pins that streaming now counts, and that rebalancing the factors
 * did not break the sum the composite renormalizes over.
 *
 * @since 0.9.72+
 */
public class TunerHealthWeightsTest {

    /** The factor order compute() uses, mirrored here to keep the table honest. */
    private static final int FACTORS = 7;

    @Test
    public void everyFactorHasAWeight() {
        assertEquals(FACTORS, Tuner.SystemHealth.HEALTH_WEIGHTS.length);
        for (double weight : Tuner.SystemHealth.HEALTH_WEIGHTS)
            assertTrue("a factor must carry real influence", weight > 0);
    }

    @Test
    public void weightsSumToOne() {
        double sum = 0;
        for (double weight : Tuner.SystemHealth.HEALTH_WEIGHTS)
            sum += weight;
        assertEquals(1.0, sum, 0.0001);
    }

    /**
     * All factors healthy is a healthy router.
     */
    @Test
    public void allFactorsHealthyScoreOne() {
        double[] factors = {1, 1, 1, 1, 1, 1, 1};
        assertEquals(1.0, Tuner.SystemHealth.compositeScore(factors,
                         Tuner.SystemHealth.HEALTH_WEIGHTS), 0.0001);
    }

    /**
     * A collapsed streaming subsystem drags the composite down even with every
     * other factor perfect — the point of adding the factor.
     */
    @Test
    public void collapsedStreamingLowersTheScore() {
        double[] factors = {1, 1, 1, 1, 1, 1, 0};
        double score = Tuner.SystemHealth.compositeScore(factors,
                         Tuner.SystemHealth.HEALTH_WEIGHTS);
        assertTrue("streaming did not move the composite: " + score, score < 0.95);
    }

    /**
     * A factor with no data is skipped, not scored as a failure.
     */
    @Test
    public void unavailableStreamingIsSkipped() {
        double[] factors = {1, 1, 1, 1, 1, 1, Double.NaN};
        assertEquals(1.0, Tuner.SystemHealth.compositeScore(factors,
                         Tuner.SystemHealth.HEALTH_WEIGHTS), 0.0001);
    }
}
