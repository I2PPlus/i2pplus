package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;

import org.junit.Test;

/**
 * Tests that the stream-close retransmission ratios are registered with the
 * periods the router's Tuner actually reads.
 *
 * <p>{@code Tuner.getAdditionalStat5Min()} samples stream.rtxRatio and
 * stream.rtxRatioBytes at {@link RateConstants#FIVE_MINUTES}; a RateStat built
 * without that period has no rate for it, the lookup returns null, and the
 * Tuner sees NaN forever — the stat exists, is populated at the minute and hour
 * periods, and still never influences congestion decisions. The control case
 * below pins exactly that failure mode.
 *
 * @since 0.9.71+
 */
public class RtxRatioStatTest {

    private static final String RATIO = "stream.rtxRatio";
    private static final String RATIO_BYTES = "stream.rtxRatioBytes";

    private static Rate period(StatManager sm, String name, long period) {
        RateStat rs = sm.getRate(name);
        assertNotNull("stat must be registered: " + name, rs);
        return rs.getRate(period);
    }

    @Test
    public void testRegisteredWithEveryTunedPeriod() {
        StatManager sm = new StatManager(I2PAppContext.getGlobalContext());
        ConnectionManager.registerRtxRatioStats(sm);

        for (String name : new String[] { RATIO, RATIO_BYTES }) {
            assertNotNull(name + " must have a 5-minute rate for Tuner.getAdditionalStat5Min()",
                          period(sm, name, RateConstants.FIVE_MINUTES));
            assertNotNull(name + " 1-minute", period(sm, name, RateConstants.ONE_MINUTE));
            assertNotNull(name + " 10-minute", period(sm, name, RateConstants.TEN_MINUTES));
            assertNotNull(name + " 1-hour", period(sm, name, RateConstants.ONE_HOUR));
        }
    }

    /**
     * Pins the bug itself: the same stat registered without the 5-minute
     * period yields null there, which is what the Tuner reads.
     */
    @Test
    public void testLegacyPeriodsMakeTheTunerBlind() {
        StatManager sm = new StatManager(I2PAppContext.getGlobalContext());
        sm.createRequiredRateStat(RATIO, "Retransmissions per 1000 messages sent when a stream closes",
                                  "Stream", new long[] { RateConstants.ONE_MINUTE,
                                                         RateConstants.TEN_MINUTES,
                                                         RateConstants.ONE_HOUR });
        assertNotNull(period(sm, RATIO, RateConstants.ONE_MINUTE));
        assertNull("without the period the Tuner samples, the data never reaches it",
                   period(sm, RATIO, RateConstants.FIVE_MINUTES));
    }

    @Test
    public void testRegistrationIsIdempotent() {
        StatManager sm = new StatManager(I2PAppContext.getGlobalContext());
        ConnectionManager.registerRtxRatioStats(sm);
        RateStat first = sm.getRate(RATIO);
        // A second manager construction must not replace or truncate the stat.
        ConnectionManager.registerRtxRatioStats(sm);
        assertSame(first, sm.getRate(RATIO));
        assertNotNull(sm.getRate(RATIO).getRate(RateConstants.FIVE_MINUTES));
    }
}
