package net.i2p.router.tunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;

/**
 * The batching counters are registered, not just written, and registered as
 * required ones.
 * <p>
 * {@link StatManager#addRateData(String, long)} looks the name up and drops the
 * write when no such stat was created, so a counter written without its
 * registration reports nothing while looking like a working one. All ten
 * batching counters were in that state: the constructor's comment claimed the
 * registrations had moved to TunnelDispatcher, and TunnelDispatcher had never
 * created any of them, so how long tunnel messages waited, how full they came
 * out, and how much of each was padding were all unmeasured on a router that had
 * been running for hours.
 * <p>
 * They also have to be declared as required. {@code createRateStat()} is a no-op
 * unless <code>stat.full</code> is set, and its default is false, so a plain
 * declaration would leave the same path dark on a stock router.
 *
 * @since 0.9.72+
 */
public class BatchedPreprocessorStatRegistrationTest {

    /** Every counter name the preprocessor writes. */
    private static final String[] WRITTEN = {
        BatchedPreprocessor.STAT_BATCH_COUNT,
        BatchedPreprocessor.STAT_BATCH_DELAY,
        BatchedPreprocessor.STAT_BATCH_DELAY_AMOUNT,
        BatchedPreprocessor.STAT_BATCH_DELAY_SENT,
        BatchedPreprocessor.STAT_BATCH_FLUSH_REMAINING,
        BatchedPreprocessor.STAT_BATCH_FULL,
        BatchedPreprocessor.STAT_BATCH_MULTIPLE,
        BatchedPreprocessor.STAT_BATCH_PENDING_DELAY,
        BatchedPreprocessor.STAT_BATCH_PRESSURE,
        BatchedPreprocessor.STAT_BATCH_SMALL,
    };

    /**
     * Test case carrying the batching-counter registration cases. Every case
     * calls the preprocessor's static registration, so the instance holds no
     * state and needs no router.
     */
    public BatchedPreprocessorStatRegistrationTest() {
    }

    /**
     * A manager holding the stats as the registration leaves them.
     *
     * @return a manager with the ten batching counters registered
     */
    private static StatManager registeredStats() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        BatchedPreprocessor.registerStats(stats, RateConstants.SHORT_TERM_RATES);
        return stats;
    }

    /**
     * Every name a write site uses is registered. This is the invariant the
     * silent drop breaks, and it is why the write sites and the registration
     * share the same constants.
     */
    @Test
    public void everyWrittenNameIsRegistered() {
        StatManager stats = registeredStats();
        for (String name : WRITTEN)
            assertNotNull(name + " must be registered, or addRateData() drops it silently",
                          stats.getRate(name));
    }

    /**
     * A write to a registered batching counter is counted. Registering names the
     * write sites do not use would pass the lookup cases and still report
     * nothing.
     */
    @Test
    public void writesAreCountedOnceRegistered() {
        StatManager stats = registeredStats();
        for (String name : WRITTEN)
            stats.addRateData(name, 3);
        for (String name : WRITTEN)
            assertEquals(name + " must hold the sample written to it", 1,
                         stats.getRate(name).getLifetimeEventCount());
    }

    /**
     * The names stay the ones the stats page, the graphs and any saved URL
     * search for. A rename at one write site alone recreates the unregistered-
     * name fault this pins.
     */
    @Test
    public void namesAreTheDocumentedOnes() {
        assertEquals("tunnel.batchCount", BatchedPreprocessor.STAT_BATCH_COUNT);
        assertEquals("tunnel.batchDelay", BatchedPreprocessor.STAT_BATCH_DELAY);
        assertEquals("tunnel.batchDelayAmount", BatchedPreprocessor.STAT_BATCH_DELAY_AMOUNT);
        assertEquals("tunnel.batchDelaySent", BatchedPreprocessor.STAT_BATCH_DELAY_SENT);
        assertEquals("tunnel.batchFlushRemaining", BatchedPreprocessor.STAT_BATCH_FLUSH_REMAINING);
        assertEquals("tunnel.batchFullFragments", BatchedPreprocessor.STAT_BATCH_FULL);
        assertEquals("tunnel.batchMultipleCount", BatchedPreprocessor.STAT_BATCH_MULTIPLE);
        assertEquals("tunnel.batchPendingDelay", BatchedPreprocessor.STAT_BATCH_PENDING_DELAY);
        assertEquals("tunnel.batchPressure", BatchedPreprocessor.STAT_BATCH_PRESSURE);
        assertEquals("tunnel.batchSmallFragments", BatchedPreprocessor.STAT_BATCH_SMALL);
    }

    /**
     * The counters sit in the tunnel group and carry the periods the console
     * graphs - a stat in the wrong group is a stat nobody looks for.
     */
    @Test
    public void statsSitInTheTunnelGroup() {
        StatManager stats = registeredStats();
        for (String name : WRITTEN) {
            RateStat rs = stats.getRate(name);
            assertEquals(name + " must sit with the other tunnel counters",
                         "Tunnels", rs.getGroupName());
            assertNotNull(name + " must carry the ten-minute rate the console graphs",
                          rs.getRate(RateConstants.TEN_MINUTES));
        }
    }

    /**
     * Every counter is declared through the required variant. The plain
     * {@code createRateStat()} returns without creating anything unless
     * <code>stat.full</code> is set, and that property defaults to false, so a
     * plain declaration is the same silence under a different name.
     */
    @Test
    public void countersAreDeclaredAsRequired() {
        StatManager stats = mock(StatManager.class);
        long[] periods = RateConstants.SHORT_TERM_RATES;
        BatchedPreprocessor.registerStats(stats, periods);
        for (String name : WRITTEN)
            verify(stats).createRequiredRateStat(eq(name), anyString(), eq("Tunnels"), same(periods));
        verifyNoMoreInteractions(stats);
    }

    /**
     * Registering twice keeps the first stat. The registration runs once per
     * router today, but a second pass that replaced the stat would discard the
     * samples the first one collected.
     */
    @Test
    public void registeringTwiceKeepsTheFirstStat() {
        StatManager stats = registeredStats();
        RateStat first = stats.getRate(BatchedPreprocessor.STAT_BATCH_PRESSURE);
        assertNotNull("the first registration must produce a stat to keep", first);
        BatchedPreprocessor.registerStats(stats, RateConstants.SHORT_TERM_RATES);
        assertSame(first, stats.getRate(BatchedPreprocessor.STAT_BATCH_PRESSURE));
    }
}
