package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.data.router.RouterInfo;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;

/**
 * The build-path counters that were written without ever being created.
 *
 * <p>{@link StatManager#addRateData(String, long)} looks the name up and drops
 * the write when no such stat exists, so each of these reported nothing while
 * looking like a working counter: a build whose first hop carried no creation
 * timestamp, the two things the expiry scan attributes, every duplicate build
 * request, every request carrying the invalid reply key, and every build request
 * turned away because our own capability flags said "no tunnels".
 *
 * <p>The congestion drop had a second defect: two of its three writes appended
 * the requester's hash, or its version, to the name. Those names are unbounded,
 * one per requester, and an unbounded name can never be created up front, so the
 * writes were dropped from the other direction as well - no registration could
 * have fixed them. The bounded counter beside it is what they were reaching for.
 *
 * <p>All of them are declared required. {@code createRateStat()} is a no-op
 * unless <code>stat.full</code> is set, and that property defaults to false, so a
 * plain declaration is the same silence under another name.
 *
 * @since 0.9.72+
 */
public class TunnelBuildStatRegistrationTest {

    /** Every name a build-path write site uses. */
    private static final String[] WRITTEN = {
        BuildExecutor.STAT_BACKFILLED_CREATION,
        BuildExecutor.STAT_EXPIRE_NO_CREATION,
        BuildExecutor.STAT_EXPIRE_ID_COLLISION,
        BuildMessageProcessor.STAT_REQUEST_DUP,
        BuildMessageProcessor.STAT_REQUEST_BAD_REPLY_KEY,
        BuildHandler.STAT_CONGESTION_DROP,
        BuildHandler.STAT_CONGESTION_DROP_RECENT_VERSION,
    };

    /**
     * Test case carrying the build-path registration cases. Every case calls one
     * of the three static registrations, so the instance holds no state and needs
     * no pool, no sockets and no build in flight.
     */
    public TunnelBuildStatRegistrationTest() {
    }

    /**
     * A manager holding the build-path counters as the registrations leave them.
     *
     * @return a manager with all three families created
     */
    private static StatManager registeredStats() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        BuildExecutor.registerStats(stats);
        BuildMessageProcessor.registerStats(stats);
        BuildHandler.registerStats(stats);
        return stats;
    }

    /**
     * Every name a write site uses is registered. This is the invariant the
     * silent drop breaks, and it is why the write sites and the registrations
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
     * A write to a registered counter is counted. Registering names the write
     * sites do not use would pass the lookup cases and still report nothing.
     */
    @Test
    public void writesAreCountedOnceRegistered() {
        StatManager stats = registeredStats();
        for (String name : WRITTEN)
            stats.addRateData(name, 1);
        for (String name : WRITTEN)
            assertEquals(name + " must hold the sample written to it", 1,
                         stats.getRate(name).getLifetimeEventCount());
    }

    /**
     * The counters sit in the group their build-path siblings use, and each
     * carries the one-minute rate the console graphs.
     */
    @Test
    public void countersSitWithTheirBuildPathSiblings() {
        StatManager stats = registeredStats();
        for (String name : WRITTEN) {
            RateStat rs = stats.getRate(name);
            String group = name.startsWith("tunnel.buildConfig") || name.startsWith("tunnel.buildExpire") ?
                           "Tunnels" : "Tunnels [Participating]";
            assertEquals(name + " must sit with the other build counters", group, rs.getGroupName());
            assertNotNull(name + " must carry the one-minute rate the console graphs",
                          rs.getRate(RateConstants.ONE_MINUTE));
        }
    }

    /**
     * All of them are declared through the required variant, and none through the
     * plain one. The plain {@code createRateStat()} returns without creating
     * anything unless <code>stat.full</code> is set, and that property defaults to
     * false.
     */
    @Test
    public void countersAreDeclaredAsRequired() {
        StatManager stats = mock(StatManager.class);
        BuildExecutor.registerStats(stats);
        BuildMessageProcessor.registerStats(stats);
        BuildHandler.registerStats(stats);
        for (String name : WRITTEN) {
            verify(stats).createRequiredRateStat(eq(name), anyString(), anyString(), any(long[].class));
            verify(stats, never()).createRateStat(eq(name), anyString(), anyString(), any(long[].class));
        }
    }

    /**
     * The congestion-capability drop keeps the plain name, with nothing appended.
     * Two of its three writes used to append the requester's hash or version, and
     * a name built that way can never be created up front - one name per
     * requester, unbounded - so those writes recorded nothing no matter how many
     * registrations were added.
     */
    @Test
    public void congestionDropNameCarriesNoRequester() {
        assertEquals("tunnel.dropTunnelFromCongestionCapability", BuildHandler.STAT_CONGESTION_DROP);
        assertEquals("tunnel.dropTunnelFromCongestionCapabilityRecentVersion",
                     BuildHandler.STAT_CONGESTION_DROP_RECENT_VERSION);
    }

    /**
     * The congestion drop writes the registered name, with nothing appended. Two
     * of its three writes used to append the requester's hash, or its version, so
     * assert the source no longer builds a stat name out of a requester.
     *
     * @throws Exception if the BuildHandler source cannot be read back from disk
     */
    @Test
    public void congestionDropWritesTheRegisteredName() throws Exception {
        java.io.File src = new java.io.File("router/java/src/net/i2p/router/tunnel/pool/BuildHandler.java");
        if (!src.isFile()) {src = new java.io.File("src/net/i2p/router/tunnel/pool/BuildHandler.java");}
        org.junit.Assume.assumeTrue("source not available", src.isFile());
        String body = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        assertTrue("the congestion drop must write the registered constant",
                   body.contains("addRateData(STAT_CONGESTION_DROP, 1)"));
        assertTrue("the cap-aware case must write the registered constant",
                   body.contains("addRateData(STAT_CONGESTION_DROP_RECENT_VERSION, 1)"));
        assertTrue("no stat name may be built out of a requester",
                   !body.contains("dropTunnelFromCongestionCapability\" +"));
    }

    /**
     * The move of the two families into the static methods kept every existing
     * name, including the ones built in a loop - the per-hop expiry attribution
     * and the per-bandwidth-tier join tallies. A registration lost in the move
     * would take away a counter a router had been reporting for years.
     */
    @Test
    public void theFamiliesMovedIntact() {
        StatManager stats = registeredStats();
        for (String name : new String[] {"tunnel.buildFailFirstHop", "tunnel.buildSuccessRate",
                                         "tunnel.buildDupId", "tunnel.concurrentBuilds",
                                         "tunnel.buildRequestTime", "tunnel.buildLookupSuccess",
                                         "tunnel.firstHopRtt", "tunnel.buildHandler.queueSize",
                                         "tunnel.rejectBandwidth", "tunnel.rejectTransient",
                                         "tunnel.rejectProbabilistic"})
            assertNotNull(name + " must survive the move", stats.getRate(name));
        for (int hop : BuildHopAttribution.registeredHopIndexes())
            for (String classification : BuildHopAttribution.registeredClassifications())
                assertNotNull("per-hop attribution must survive the move",
                              stats.getRate(BuildHopAttribution.statName(hop, classification)));
        String tiers = RouterInfo.BW_CAPABILITY_CHARS;
        for (int i = 0; i < tiers.length(); i++) {
            String tier = String.valueOf(tiers.charAt(i));
            assertNotNull("the join tally for tier " + tier + " must survive the move",
                          stats.getRate("tunnel.tierAgree" + tier));
            assertNotNull("the join rejection for tier " + tier + " must survive the move",
                          stats.getRate("tunnel.tierReject" + tier));
            assertNotNull("the join expiry for tier " + tier + " must survive the move",
                          stats.getRate("tunnel.tierExpire" + tier));
        }
    }

    /**
     * Registering twice keeps the first stat. The registration sits in a
     * constructor, so it runs again whenever the pool manager is rebuilt over a
     * stat manager that already holds the counters, and a second pass that
     * replaced the stat would discard the samples the first pass collected.
     */
    @Test
    public void registeringTwiceKeepsTheFirstStat() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        BuildExecutor.registerStats(stats);
        RateStat first = stats.getRate(BuildExecutor.STAT_EXPIRE_ID_COLLISION);
        stats.addRateData(BuildExecutor.STAT_EXPIRE_ID_COLLISION, 1);
        BuildExecutor.registerStats(stats);
        assertSame(first, stats.getRate(BuildExecutor.STAT_EXPIRE_ID_COLLISION));
        assertEquals("the second pass must not discard the first pass's sample", 1,
                     first.getLifetimeEventCount());
    }
}
