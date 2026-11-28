package net.i2p.router.transport.ntcp;

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
 * The NTCP no-address send failure that was written without ever being created.
 *
 * <p>{@link StatManager#addRateData(String, long)} looks the name up and drops
 * the write when no such stat exists, so every send NTCP gave up on because the
 * peer publishes no usable address was counted and thrown away, while its
 * neighbour on the same path, the oversized message, was registered - which is
 * how the path looked understood while the commoner of its two failure reasons
 * stayed invisible.
 *
 * <p>It is declared required. {@code createRateStat()} is a no-op unless
 * <code>stat.full</code> is set, and that property defaults to false, so a plain
 * declaration is the same silence under another name.
 *
 * @since 0.9.72+
 */
public class NTCPTransportStatRegistrationTest {

    /**
     * Test case carrying the no-address registration cases. Every case calls the
     * transport's static registration, so the instance holds no state and needs
     * no sockets.
     */
    public NTCPTransportStatRegistrationTest() {
    }

    /**
     * A manager holding the NTCP counters as the registration leaves them.
     *
     * @return a manager with the whole NTCP family created
     */
    private static StatManager registeredStats() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        NTCPTransport.registerStats(stats);
        return stats;
    }

    /**
     * The name the write site uses is registered. This is the invariant the
     * silent drop breaks, and it is why the write site and the registration
     * share one constant.
     */
    @Test
    public void writtenNameIsRegistered() {
        assertNotNull(NTCPTransport.STAT_NO_BID_NO_ADDRESS +
                      " must be registered, or addRateData() drops it silently",
                      registeredStats().getRate(NTCPTransport.STAT_NO_BID_NO_ADDRESS));
    }

    /**
     * A write to the registered counter is counted. Registering a name the write
     * site does not use would pass the lookup case and still report nothing.
     */
    @Test
    public void writeIsCountedOnceRegistered() {
        StatManager stats = registeredStats();
        stats.addRateData(NTCPTransport.STAT_NO_BID_NO_ADDRESS, 1);
        assertEquals("the counter must hold the event written to it", 1,
                     stats.getRate(NTCPTransport.STAT_NO_BID_NO_ADDRESS).getLifetimeEventCount());
    }

    /**
     * The name stays the one the stats page and any saved graph search for, and
     * sits with the other NTCP counters.
     */
    @Test
    public void nameAndGroupAreTheDocumentedOnes() {
        assertEquals("ntcp.noBidNoAddress", NTCPTransport.STAT_NO_BID_NO_ADDRESS);
        StatManager stats = registeredStats();
        assertEquals("must sit with the other NTCP counters", "Transport [NTCP]",
                     stats.getRate(NTCPTransport.STAT_NO_BID_NO_ADDRESS).getGroupName());
        assertNotNull("must carry the one-minute rate the console graphs",
                      stats.getRate(NTCPTransport.STAT_NO_BID_NO_ADDRESS).getRate(RateConstants.ONE_MINUTE));
    }

    /**
     * Declared through the required variant, and not through the plain one. The
     * plain {@code createRateStat()} returns without creating anything unless
     * <code>stat.full</code> is set, and that property defaults to false.
     */
    @Test
    public void counterIsDeclaredAsRequired() {
        StatManager stats = mock(StatManager.class);
        NTCPTransport.registerStats(stats);
        verify(stats).createRequiredRateStat(eq(NTCPTransport.STAT_NO_BID_NO_ADDRESS),
                                             anyString(), eq("Transport [NTCP]"), any(long[].class));
        verify(stats, never()).createRateStat(eq(NTCPTransport.STAT_NO_BID_NO_ADDRESS),
                                              anyString(), anyString(), any(long[].class));
    }

    /**
     * The move of the family into the static method kept every existing name.
     * The oversized-message failure beside the new one is the check: it is the
     * name the path already reported, and a lost registration in the move would
     * take it away from a router that had been showing it for years.
     */
    @Test
    public void theFamilyMovedIntact() {
        StatManager stats = registeredStats();
        assertNotNull("the pre-existing sibling must survive the move",
                      stats.getRate("ntcp.noBidTooLargeI2NP"));
        assertNotNull("the pre-existing required sibling must survive the move",
                      stats.getRate("ntcp.sendTime"));
    }
}
