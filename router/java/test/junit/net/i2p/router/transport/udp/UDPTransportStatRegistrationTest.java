package net.i2p.router.transport.udp;

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
 * The UDP peer-count cap that was written without ever being created.
 *
 * <p>{@link StatManager#addRateData(String, long)} looks the name up and drops
 * the write when no such stat exists, so the expire pass recorded the active UDP
 * peer count and the connection cap it is measured against side by side and only
 * the first of the two survived: the console showed how many peers were held with
 * nothing to compare the number to, while the Tuner rule that moves that cap
 * looked like it had no measurement to work from.
 *
 * <p>It is declared required, like its {@code udp.peerCount} sibling.
 * {@code createRateStat()} is a no-op unless <code>stat.full</code> is set, and
 * that property defaults to false, so a plain declaration is the same silence
 * under another name.
 *
 * @since 0.9.72+
 */
public class UDPTransportStatRegistrationTest {

    /**
     * Test case carrying the peer-count-cap registration cases. Every case calls
     * the transport's static registration, so the instance holds no state and
     * needs no sockets.
     */
    public UDPTransportStatRegistrationTest() {
    }

    /**
     * A manager holding the UDP counters as the registration leaves them.
     *
     * @return a manager with the whole UDP family created
     */
    private static StatManager registeredStats() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        UDPTransport.registerStats(stats);
        return stats;
    }

    /**
     * The name the write site uses is registered. This is the invariant the
     * silent drop breaks, and it is why the write site and the registration
     * share one constant.
     */
    @Test
    public void writtenNameIsRegistered() {
        assertNotNull(UDPTransport.STAT_PEER_COUNT_MAX +
                      " must be registered, or addRateData() drops it silently",
                      registeredStats().getRate(UDPTransport.STAT_PEER_COUNT_MAX));
    }

    /**
     * A write to the registered counter is counted. Registering a name the write
     * site does not use would pass the lookup case and still report nothing.
     */
    @Test
    public void writeIsCountedOnceRegistered() {
        StatManager stats = registeredStats();
        stats.addRateData(UDPTransport.STAT_PEER_COUNT_MAX, 35);
        assertEquals("the counter must hold the cap written to it", 1,
                     stats.getRate(UDPTransport.STAT_PEER_COUNT_MAX).getLifetimeEventCount());
    }

    /**
     * The name stays the one the stats page and the Tuner cross-reference use,
     * and sits with the peer count it is compared against.
     */
    @Test
    public void nameAndGroupAreTheDocumentedOnes() {
        assertEquals("udp.peerCount.max", UDPTransport.STAT_PEER_COUNT_MAX);
        StatManager stats = registeredStats();
        assertEquals("must sit with the other UDP counters", "Transport [UDP]",
                     stats.getRate(UDPTransport.STAT_PEER_COUNT_MAX).getGroupName());
        assertNotNull("must carry the one-minute rate the console graphs",
                      stats.getRate(UDPTransport.STAT_PEER_COUNT_MAX).getRate(RateConstants.ONE_MINUTE));
    }

    /**
     * Declared through the required variant, and not through the plain one. The
     * plain {@code createRateStat()} returns without creating anything unless
     * <code>stat.full</code> is set, and that property defaults to false.
     */
    @Test
    public void counterIsDeclaredAsRequired() {
        StatManager stats = mock(StatManager.class);
        UDPTransport.registerStats(stats);
        verify(stats).createRequiredRateStat(eq(UDPTransport.STAT_PEER_COUNT_MAX),
                                             anyString(), eq("Transport [UDP]"), any(long[].class));
        verify(stats, never()).createRateStat(eq(UDPTransport.STAT_PEER_COUNT_MAX),
                                              anyString(), anyString(), any(long[].class));
    }

    /**
     * The move of the family into the static method kept every existing name,
     * including the three added in the earlier peer-count pass that the Tuner
     * reads as cross-references - a lost registration in the move would put the
     * Tuner back to reading NaN.
     */
    @Test
    public void theFamilyMovedIntact() {
        StatManager stats = registeredStats();
        for (String name : new String[] {"udp.peerCount", "udp.peerExpired", "udp.sentMessagesDepth",
                                         "udp.outboundQueueDepth", "udp.retransmitEvents"})
            assertNotNull(name + " must survive the move", stats.getRate(name));
    }
}
