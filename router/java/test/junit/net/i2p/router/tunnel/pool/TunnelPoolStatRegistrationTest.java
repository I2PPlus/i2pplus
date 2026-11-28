package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;

/**
 * The pool counters that were written without ever being created.
 *
 * <p>{@link StatManager#addRateData(String, long)} looks the name up and drops the
 * write when no such stat exists, so all five families reported nothing while
 * looking like working counters: which side lost a zombie tunnel's transport
 * session, how many tunnels a gone session cost, which transport lost the zombie
 * sessions against which transport carries the tunnels overall, and how often a
 * rebuild was declined for want of peers. The last of them is the one the
 * starvation investigation reads, and it had never recorded a single sample.
 *
 * <p>Three of the five build their name from a suffix - the {@link TunnelPool.SessionLoss}
 * attribution, or the transport the local hop speaks. A suffixed family is only
 * fully registered if every value the suffix can take is registered too, so the
 * suffix sets are pinned here against the code that produces them.
 *
 * <p>All of them are declared required. {@code createRateStat()} is a no-op unless
 * <code>stat.full</code> is set, and that property defaults to false.
 *
 * @since 0.9.72+
 */
public class TunnelPoolStatRegistrationTest {

    /** Every name a pool write site uses. */
    private static final String[] WRITTEN = {
        TunnelPool.STAT_ZOMBIE_SESSION_LOSS + "OURSIDE",
        TunnelPool.STAT_ZOMBIE_SESSION_LOSS + "PEER_DEAD",
        TunnelPool.STAT_ZOMBIE_SESSION_LOSS + "UNKNOWN",
        TunnelPool.STAT_ZOMBIE_TRANSPORT + "NTCP",
        TunnelPool.STAT_ZOMBIE_TRANSPORT + "NTCP2",
        TunnelPool.STAT_ZOMBIE_TRANSPORT + "SSU",
        TunnelPool.STAT_ZOMBIE_TRANSPORT + "SSU2",
        TunnelPool.STAT_ZOMBIE_TRANSPORT + "unknown",
        TunnelPool.STAT_LOCAL_HOP_TRANSPORT + "NTCP",
        TunnelPool.STAT_LOCAL_HOP_TRANSPORT + "NTCP2",
        TunnelPool.STAT_LOCAL_HOP_TRANSPORT + "SSU",
        TunnelPool.STAT_LOCAL_HOP_TRANSPORT + "SSU2",
        TunnelPool.STAT_LOCAL_HOP_TRANSPORT + "unknown",
        TunnelPool.STAT_LOST_SESSION_DROPPED,
        TunnelPool.STAT_STARVATION_DECLINED,
    };

    /**
     * Test case carrying the registration cases. Every case calls the static
     * registration, so no pool, selector or tunnel in flight is needed.
     */
    public TunnelPoolStatRegistrationTest() {
    }

    /**
     * A manager holding the pool counters as the registration leaves them.
     *
     * @return a manager with all five families created
     */
    private static StatManager registeredStats() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        TunnelPool.registerStats(stats);
        return stats;
    }

    /**
     * The TunnelPool source, for the cases that assert what the write sites and
     * the transport fold actually say, not merely what the constants claim.
     *
     * @return the source text, or null when the test is run without the tree
     */
    private static String poolSource() {
        java.io.File src = new java.io.File("router/java/src/net/i2p/router/" +
                                            "tunnel/pool/TunnelPool.java");
        if (!src.isFile()) {src = new java.io.File("src/net/i2p/router/tunnel/" +
                                                   "pool/TunnelPool.java");}
        if (!src.isFile()) {return null;}
        try {
            return new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        } catch (java.io.IOException ioe) {
            return null;
        }
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
     * The counters sit in the group the pool's other counters use, and each
     * carries the one-minute rate the console graphs.
     */
    @Test
    public void countersSitWithTheirPoolSiblings() {
        StatManager stats = registeredStats();
        for (String name : WRITTEN) {
            RateStat rs = stats.getRate(name);
            assertEquals(name + " must sit with the other pool counters", "Tunnels",
                         rs.getGroupName());
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
        TunnelPool.registerStats(stats);
        for (String name : WRITTEN) {
            verify(stats).createRequiredRateStat(eq(name), anyString(), anyString(),
                                                 any(long[].class));
            verify(stats, never()).createRateStat(eq(name), anyString(), anyString(),
                                                  any(long[].class));
        }
    }

    /**
     * The session-loss suffix set is exactly the attribution enum, so a cause
     * added to {@link TunnelPool.SessionLoss} cannot arrive unregistered - the
     * write would
     * silently record nothing for that cause and the split would read as a
     * missing category rather than a broken one.
     */
    @Test
    public void sessionLossSuffixesAreTheWholeEnum() {
        StatManager stats = registeredStats();
        TunnelPool.SessionLoss[] losses = TunnelPool.SessionLoss.values();
        assertEquals("the enum must stay enumerable for this test to mean anything", 3,
                     losses.length);
        for (TunnelPool.SessionLoss loss : losses)
            assertNotNull(TunnelPool.STAT_ZOMBIE_SESSION_LOSS + loss.name() + " must be registered",
                          stats.getRate(TunnelPool.STAT_ZOMBIE_SESSION_LOSS + loss.name()));
    }

    /**
     * Every transport style the fold can be handed - including null and an
     * unrecognised style - lands in a bucket whose two names are registered. The
     * fold is what decides the suffix, so this is the case that fails when a
     * transport is added to the fold but not to the registered set.
     */
    @Test
    public void everyFoldedTransportIsRegistered() {
        StatManager stats = registeredStats();
        for (String style : new String[] {"NTCP", "NTCP2", "SSU", "SSU2", "NETBOOBEEN", null}) {
            String bucket = TunnelPool.zombieTransportBucket(style);
            assertNotNull("the zombie split for " + bucket + " must be registered",
                          stats.getRate(TunnelPool.STAT_ZOMBIE_TRANSPORT + bucket));
            assertNotNull("the base rate for " + bucket + " must be registered",
                          stats.getRate(TunnelPool.STAT_LOCAL_HOP_TRANSPORT + bucket));
        }
    }

    /**
     * The write sites use the registered constants rather than spelling the name
     * out, which is what keeps a renamed or added counter from going back to the
     * silent-drop path. The suffixed families must keep their suffix, or the two
     * transport families and the loss split collapse into one unusable number.
     *
     * @throws Exception if the TunnelPool source cannot be read back from disk
     */
    @Test
    public void writeSitesUseTheRegisteredConstants() throws Exception {
        String body = poolSource();
        org.junit.Assume.assumeTrue("source not available", body != null);
        assertTrue("the loss split must write the registered constant with its suffix",
                   body.contains("addRateData(STAT_ZOMBIE_SESSION_LOSS + loss.name()"));
        assertTrue("the gone-session drop must write the registered constant",
                   body.contains("addRateData(STAT_LOST_SESSION_DROPPED, dropped)"));
        assertTrue("the zombie transport split must write the registered constant with its bucket",
                   body.contains("addRateData(STAT_ZOMBIE_TRANSPORT + bucket"));
        assertTrue("the base rate must write the registered constant with its bucket",
                   body.contains("addRateData(STAT_LOCAL_HOP_TRANSPORT +"));
        assertTrue("the starvation decline must write the registered constant",
                   body.contains("addRateData(STAT_STARVATION_DECLINED, fastPeers)"));
        assertTrue("no pool counter may be written under a name spelled at the call site",
                   !body.contains("addRateData(\"tunnel.pool"));
    }

    /**
     * The registered suffix set is exactly what the fold returns, in neither
     * direction: a bucket registered but unreachable is a dead row on the stats
     * page, and a bucket reachable but unregistered is the silent drop again.
     *
     * <p>Read from the source rather than from a hand-written style list, because
     * the failure this guards is a transport added to the fold and not to the
     * registered set - a list of styles written next to the test cannot grow with
     * the fold, and a case that never feeds the fold its new style reports a pass
     * for a bucket nothing ever registers.
     *
     * @throws Exception if the TunnelPool source cannot be read back from disk
     */
    @Test
    public void theFoldReturnsOnlyRegisteredBuckets() throws Exception {
        String body = poolSource();
        org.junit.Assume.assumeTrue("source not available", body != null);
        java.util.Map<String, String> buckets = new java.util.HashMap<>();
        java.util.regex.Matcher decl =
                java.util.regex.Pattern.compile("static final String (BUCKET_\\w+) = \"([^\"]*)\"")
                                        .matcher(body);
        while (decl.find())
            buckets.put(decl.group(1), decl.group(2));
        int at = body.indexOf("static String zombieTransportBucket(String style) {");
        assertTrue("the fold must be findable for this case to mean anything", at >= 0);
        int end = body.indexOf("\n    }", at);
        assertTrue("the fold's body must be findable", end > at);
        java.util.Set<String> folded = new java.util.HashSet<>();
        java.util.regex.Matcher ret =
                java.util.regex.Pattern.compile("return\\s+(\\w+|\\\"[^\\\"]*\\\");")
                                        .matcher(body.substring(at, end));
        while (ret.find()) {
            String token = ret.group(1);
            if (token.startsWith("\""))
                folded.add(token.substring(1, token.length() - 1));
            else
                assertTrue("the fold returns " + token + ", not a documented bucket constant",
                           buckets.containsKey(token));
            folded.add(buckets.containsKey(token) ? buckets.get(token) : token);
        }
        java.util.Set<String> registered = new java.util.HashSet<>();
        for (String bucket : TunnelPool.TRANSPORT_BUCKETS)
            registered.add(bucket);
        assertEquals("the fold and the registered set must offer the same buckets", registered,
                     folded);
        StatManager stats = registeredStats();
        for (String bucket : folded) {
            assertNotNull("the zombie split for " + bucket + " must be registered",
                          stats.getRate(TunnelPool.STAT_ZOMBIE_TRANSPORT + bucket));
            assertNotNull("the base rate for " + bucket + " must be registered",
                          stats.getRate(TunnelPool.STAT_LOCAL_HOP_TRANSPORT + bucket));
        }
    }

    /**
     * The pool keeps the counters it already reported: the per-pool bandwidth
     * stat and the OBEP/IBGW match counter. A registration moved into the static
     * method must not take either away.
     */
    @Test
    public void theExistingPoolCountersSurvive() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        String desc = "How often our Outbound Endpoint matches their Inbound Gateway";
        stats.createRequiredRateStat("tunnel.matchLease", desc, "Tunnels",
                                     RateConstants.SHORT_TERM_RATES);
        assertNotNull("the match counter must still be there", stats.getRate("tunnel.matchLease"));
        TunnelPool.registerStats(stats);
        assertNotNull("the match counter must survive the pool registration",
                      stats.getRate("tunnel.matchLease"));
    }
}
