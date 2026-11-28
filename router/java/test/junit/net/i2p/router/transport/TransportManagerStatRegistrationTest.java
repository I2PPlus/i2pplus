package net.i2p.router.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.StatManager;

/**
 * The bid-failure counter that was written without ever being created.
 *
 * <p>{@link StatManager#addRateData(String, long)} looks the name up and drops
 * the write when no such stat exists, so every message the dispatcher gave up on
 * because the message carried no target was counted and thrown away, while its
 * neighbours on the same path - banlisted, self, no transports, all transports -
 * reported normally and made the path look understood.
 *
 * <p>It is declared required. {@code createRateStat()} is a no-op unless
 * <code>stat.full</code> is set, and that property defaults to false, so a plain
 * declaration is the same silence under another name.
 *
 * @since 0.9.72+
 */
public class TransportManagerStatRegistrationTest {

    /**
     * Test case carrying the bid-failure registration cases. Every case calls
     * the manager's static registration, so the instance holds no state and
     * needs no transports.
     */
    public TransportManagerStatRegistrationTest() {
    }

    /**
     * A manager holding the dispatch counters as the registration leaves them.
     *
     * @return a manager with the bid-failure counters created
     */
    private static StatManager registeredStats() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        TransportManager.registerStats(stats);
        return stats;
    }

    /**
     * The name the write site uses is registered. This is the invariant the
     * silent drop breaks, and it is why the write site on the bid job and the
     * registration on the manager share one constant.
     */
    @Test
    public void writtenNameIsRegistered() {
        assertNotNull(GetBidsJob.STAT_BID_FAIL_NULL_TARGET +
                      " must be registered, or addRateData() drops it silently",
                      registeredStats().getRate(GetBidsJob.STAT_BID_FAIL_NULL_TARGET));
    }

    /**
     * A write to the registered counter is counted. Registering a name the write
     * site does not use would pass the lookup case and still report nothing.
     */
    @Test
    public void writeIsCountedOnceRegistered() {
        StatManager stats = registeredStats();
        stats.addRateData(GetBidsJob.STAT_BID_FAIL_NULL_TARGET, 1500);
        assertEquals("the counter must hold the lifetime written to it", 1,
                     stats.getRate(GetBidsJob.STAT_BID_FAIL_NULL_TARGET).getLifetimeEventCount());
    }

    /**
     * The name stays the one the stats page and any saved graph search for, and
     * sits with the other bid-failure counters.
     */
    @Test
    public void nameAndGroupAreTheDocumentedOnes() {
        assertEquals("transport.bidFailNullTarget", GetBidsJob.STAT_BID_FAIL_NULL_TARGET);
        StatManager stats = registeredStats();
        assertEquals("must sit with the other transport counters", "Transport",
                     stats.getRate(GetBidsJob.STAT_BID_FAIL_NULL_TARGET).getGroupName());
        assertNotNull("must carry the one-minute rate the console graphs",
                      stats.getRate(GetBidsJob.STAT_BID_FAIL_NULL_TARGET).getRate(RateConstants.ONE_MINUTE));
    }

    /**
     * Declared through the required variant, and not through the plain one. The
     * plain {@code createRateStat()} returns without creating anything unless
     * <code>stat.full</code> is set, and that property defaults to false.
     */
    @Test
    public void counterIsDeclaredAsRequired() {
        StatManager stats = mock(StatManager.class);
        TransportManager.registerStats(stats);
        verify(stats).createRequiredRateStat(eq(GetBidsJob.STAT_BID_FAIL_NULL_TARGET),
                                             anyString(), eq("Transport"), any(long[].class));
        verify(stats, never()).createRateStat(eq(GetBidsJob.STAT_BID_FAIL_NULL_TARGET),
                                              anyString(), anyString(), any(long[].class));
    }
}
