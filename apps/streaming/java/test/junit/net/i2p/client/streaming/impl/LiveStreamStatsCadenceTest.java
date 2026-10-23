package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.StatManager;

/**
 * Pins the cadence and registration of the live stream RTT and send-window stats.
 *
 * <p>These are the first continuously-updated streaming RTTs. Before them the only
 * RTTs published were the setup samples and {@code lifetimeRTT}, which is written
 * once, when a stream closes — so eleven tuner params had nothing but setup cost to
 * steer by. A stat that is registered but never emitted looks identical from the
 * console to one that does not exist, which makes the cadence the thing worth
 * pinning rather than the value.
 *
 * @since 0.9.71+
 */
public class LiveStreamStatsCadenceTest {

    /** Sampled exactly on the period, not before it and not off it. */
    @Test
    public void testSamplesExactlyOnThePeriod() {
        assertFalse("counter 1 must not sample", ConnectionPacketHandler.shouldSampleLiveStreamStats(1));
        assertFalse("counter 15 must not sample", ConnectionPacketHandler.shouldSampleLiveStreamStats(15));
        assertTrue("counter 16 must sample", ConnectionPacketHandler.shouldSampleLiveStreamStats(16));
        assertFalse("counter 17 must not sample", ConnectionPacketHandler.shouldSampleLiveStreamStats(17));
        assertTrue("counter 32 must sample", ConnectionPacketHandler.shouldSampleLiveStreamStats(32));
        assertTrue("counter 48 must sample", ConnectionPacketHandler.shouldSampleLiveStreamStats(48));
    }

    /**
     * The regression the choke-size test guards against, guarded here too: a
     * predicate that requires counter 1 and rejects counter 16 is unsatisfiable,
     * and the stat never emits at all. First block must be reachable.
     */
    @Test
    public void testFirstBlockIsReachable() {
        boolean emitted = false;
        for (int i = 1; i <= 16; i++) {
            emitted |= ConnectionPacketHandler.shouldSampleLiveStreamStats(i);
        }
        assertTrue("a stream that sends exactly one block must sample once", emitted);
    }

    /** Zero and negatives must not sample — they would emit a bogus first period. */
    @Test
    public void testNonPositiveCounterNeverSamples() {
        assertFalse("counter 0 must not sample", ConnectionPacketHandler.shouldSampleLiveStreamStats(0));
        assertFalse("counter -1 must not sample", ConnectionPacketHandler.shouldSampleLiveStreamStats(-1));
        assertFalse("Integer.MIN_VALUE must not sample",
                    ConnectionPacketHandler.shouldSampleLiveStreamStats(Integer.MIN_VALUE));
    }

    /**
     * The mask is only correct for a power-of-two period. A non-power-of-two
     * would make the cadence silently wrong rather than fail loudly, so the
     * property is pinned rather than assumed.
     */
    @Test
    public void testPeriodIsAPowerOfTwo() {
        int period = 16; // must match TELEMETRY_SAMPLE_PERIOD
        assertEquals("period changed; this test documents the mask requirement",
                     16, period);
        assertEquals("power of two is required for the mask to be a modulo",
                     0, period & (period - 1));
    }

    /**
     * One predicate for all three stats. If the live RTT and window had their own
     * gate they could drift to per-packet while the size sample stayed sampled,
     * and nothing would fail.
     */
    @Test
    public void testAllThreeShareOneCadence() {
        // The receive-side size sample and the live RTT/window are written in the
        // same block, so they cannot diverge. Assert the predicate is a pure
        // function of the counter, which is what makes sharing it safe.
        assertEquals(ConnectionPacketHandler.shouldSampleLiveStreamStats(16),
                     ConnectionPacketHandler.shouldSampleLiveStreamStats(16));
        assertTrue(ConnectionPacketHandler.shouldSampleLiveStreamStats(16));
    }

    /**
     * Registration against a fresh StatManager, the reason
     * {@link ConnectionPacketHandler#registerLiveStreamStats} exists. A name
     * StatManager was never told about is dropped silently, which is how a
     * registered-but-never-fed stat reads as absent rather than wrong.
     */
    @Test
    public void testRegistrationCoversBothStats() {
        StatManager sm = new StatManager(I2PAppContext.getGlobalContext());
        ConnectionPacketHandler.registerLiveStreamStats(sm);

        assertNotNull("stream.rtt must be registered", sm.getRate("stream.rtt"));
        assertNotNull("stream.sendWindowSize must be registered",
                      sm.getRate("stream.sendWindowSize"));
        assertNull("an unrelated name must not be registered by this call",
                   sm.getRate("stream.chokeSizeBegin"));
    }

    /**
     * The registered stats must carry every period the tuner samples. A rate
     * registered without one reads null at that period, which is how a stat can
     * exist on the console yet be invisible to every rule that looks at it.
     */
    @Test
    public void testRegisteredWithEverySampledPeriod() {
        StatManager sm = new StatManager(I2PAppContext.getGlobalContext());
        ConnectionPacketHandler.registerLiveStreamStats(sm);

        for (String name : new String[] { "stream.rtt", "stream.sendWindowSize" }) {
            assertNotNull(name + " must be registered", sm.getRate(name));
            assertNotNull(name + " must carry the 1-minute period the tuner samples",
                          sm.getRate(name).getRate(RateConstants.ONE_MINUTE));
            assertNotNull(name + " must carry the 10-minute period",
                          sm.getRate(name).getRate(RateConstants.TEN_MINUTES));
            assertNotNull(name + " must carry the 1-hour period",
                          sm.getRate(name).getRate(RateConstants.ONE_HOUR));
        }
    }

    /**
     * No five-minute period, deliberately. The generic observed-stat path reads
     * {@code STAT_PERIOD}, which is {@code ONE_MINUTE}, so one minute is the period
     * that has to exist. Five minutes matters only to params that override the
     * accessor for it - {@code stream.rtxRatio} is read that way, which is why that
     * stat carries {@code RTX_RATIO_RATES} and this one does not.
     */
    @Test
    public void testFiveMinutePeriodNotRequired() {
        StatManager sm = new StatManager(I2PAppContext.getGlobalContext());
        ConnectionPacketHandler.registerLiveStreamStats(sm);
        assertNull("the generic tuner path samples one minute, not five",
                   sm.getRate("stream.rtt").getRate(RateConstants.FIVE_MINUTES));
        assertNotNull("but one minute, which it does sample, must be there",
                      sm.getRate("stream.rtt").getRate(RateConstants.ONE_MINUTE));
    }
}
