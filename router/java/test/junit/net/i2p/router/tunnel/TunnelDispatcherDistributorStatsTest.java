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
 * The counters written by the tunnel message distributors and receivers are
 * declared where those classes say they are.
 * <p>
 * Each of {@link InboundGatewayReceiver}, {@link OutboundReceiver},
 * {@link TunnelParticipant}, {@link OutboundMessageDistributor},
 * {@link InboundMessageDistributor} and {@link OutboundTunnelEndpoint} carries a
 * comment saying its stat declarations were moved to {@link TunnelDispatcher},
 * and none of them were ever there. {@link StatManager#addRateData(String, long)}
 * drops a write to a name it has never created, so a next-hop lookup that failed,
 * a message the new-connection throttle refused at the outbound endpoint, and a
 * DatabaseStore reply token arriving down a tunnel that cannot carry the answer
 * back were all invisible - the lookup counters most of all, since a falling hit
 * ratio there is the only sign that a healthy-looking tunnel is being fed by a
 * NetDb that cannot find the next hop.
 *
 * @since 0.9.72+
 */
public class TunnelDispatcherDistributorStatsTest {

    /** Every counter name the distributors and receivers write. */
    private static final String[] WRITTEN = {
        "tunnel.inboundLookupSuccess",
        "tunnel.outboundLookupSuccess",
        "tunnel.participantLookupSuccess",
        "tunnel.distributeLookupSuccess",
        "tunnel.dropAtOBEP",
        "tunnel.dropDangerousClientTunnelMessage",
        "tunnel.dropDangerousExplTunnelMessage",
        "tunnel.handleLoadClove",
        "tunnel.outboundTunnelEndpointFwdRIDSM",
    };

    /**
     * Test case carrying the distributor-counter registration cases. Every case
     * calls the dispatcher's static registration, so the instance holds no state
     * and needs no router.
     */
    public TunnelDispatcherDistributorStatsTest() {
    }

    /**
     * A manager holding the counters as the registration leaves them.
     *
     * @return a manager with the nine distributor counters registered
     */
    private static StatManager registeredStats() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        TunnelDispatcher.registerDistributorStats(stats, RateConstants.SHORT_TERM_RATES);
        return stats;
    }

    /**
     * Every name a write site uses is declared. This is the invariant the silent
     * drop breaks: an undeclared name reads as a stat that does not exist rather
     * than a measurement that was never taken.
     */
    @Test
    public void everyWrittenNameIsDeclared() {
        StatManager stats = registeredStats();
        for (String name : WRITTEN)
            assertNotNull(name + " must be declared, or addRateData() drops it silently",
                          stats.getRate(name));
    }

    /**
     * A write to a declared counter is counted, including the outcome markers,
     * whose average is the hit ratio of the path they sit on.
     */
    @Test
    public void writesAreCountedOnceDeclared() {
        StatManager stats = registeredStats();
        for (String name : WRITTEN) {
            stats.addRateData(name, 1);
            stats.addRateData(name, 0);
        }
        for (String name : WRITTEN)
            assertEquals(name + " must hold both samples written to it", 2,
                         stats.getRate(name).getLifetimeEventCount());
    }

    /**
     * The counters sit in the tunnel group and carry the periods the console
     * graphs.
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
        TunnelDispatcher.registerDistributorStats(stats, periods);
        for (String name : WRITTEN)
            verify(stats).createRequiredRateStat(eq(name), anyString(), eq("Tunnels"), same(periods));
        verifyNoMoreInteractions(stats);
    }

    /**
     * Declaring twice keeps the first stat, so a second pass cannot discard the
     * samples the first one collected.
     */
    @Test
    public void declaringTwiceKeepsTheFirstStat() {
        StatManager stats = registeredStats();
        RateStat first = stats.getRate("tunnel.inboundLookupSuccess");
        assertNotNull("the first declaration must produce a stat to keep", first);
        TunnelDispatcher.registerDistributorStats(stats, RateConstants.SHORT_TERM_RATES);
        assertSame(first, stats.getRate("tunnel.inboundLookupSuccess"));
    }
}
