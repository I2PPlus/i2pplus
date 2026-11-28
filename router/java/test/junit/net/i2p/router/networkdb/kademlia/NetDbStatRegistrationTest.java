package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;

/**
 * The three NetDb counters that were written without ever being created.
 *
 * <p>{@link StatManager#addRateData(String, long)} looks the name up and drops
 * the write when no such stat exists, so each of these reported nothing while
 * looking like a working counter: the flood-check wait of every verification,
 * every store of our own client's LeaseSet refused by the subDB that owns it,
 * and the blind-cache size at netdb shutdown. The blind-cache name was also
 * spelled <code>netdb.</code> while every other counter in the group is
 * <code>netDb.</code>, so it sat outside the group the console and the Tuner
 * rules read even in the runs where it was written.
 *
 * <p>All three are declared required. {@code createRateStat()} is a no-op unless
 * <code>stat.full</code> is set, and that property defaults to false, so a plain
 * declaration is the same silence under another name.
 *
 * @since 0.9.72+
 */
public class NetDbStatRegistrationTest {

    /** The three names the write sites use. */
    private static final String[] WRITTEN = {
        FloodfillVerifyStoreJob.STAT_PHASE2_DELAY,
        HandleFloodfillDatabaseStoreMessageJob.STAT_LOCAL_LS_TO_LOCAL_CLIENT,
        BlindCache.STAT_CACHE_SIZE,
    };

    /**
     * Test case carrying the three registration cases. Every case calls one of
     * the static registrations, so the instance holds no state and needs no
     * router.
     */
    public NetDbStatRegistrationTest() {
    }

    /**
     * A manager holding all three counters as the registrations leave them.
     *
     * @return a manager with the three counters created
     */
    private static StatManager registeredStats() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        FloodfillVerifyStoreJob.registerStats(stats);
        FloodfillDatabaseStoreMessageHandler.registerStats(stats);
        BlindCache.registerStats(stats);
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
     * The blind-cache counter carries the group's capitalisation. The lower-case
     * spelling put it outside the <code>netDb.</code> group the console and the
     * Tuner rules select on, which is how a written counter stayed invisible
     * even where it was registered.
     */
    @Test
    public void blindCacheNameCarriesTheGroupCapitalisation() {
        assertEquals("netDb.blindCache.size", BlindCache.STAT_CACHE_SIZE);
    }

    /**
     * The counters sit in the group the other NetDb counters use, and the
     * blind-cache size carries the day-long period - it is written once, at
     * netdb shutdown, so a shorter period would have forgotten it before the
     * console next asked.
     */
    @Test
    public void countersSitInTheNetworkDatabaseGroup() {
        StatManager stats = registeredStats();
        for (String name : WRITTEN) {
            RateStat rs = stats.getRate(name);
            assertEquals(name + " must sit with the other NetDb counters",
                         "NetworkDatabase", rs.getGroupName());
            assertNotNull(name + " must carry the one-minute rate the console graphs",
                          rs.getRate(RateConstants.ONE_MINUTE));
        }
        assertNotNull("the blind-cache size must survive to the next console read",
                      stats.getRate(BlindCache.STAT_CACHE_SIZE).getRate(RateConstants.ONE_DAY));
    }

    /**
     * All three are declared through the required variant. The plain
     * {@code createRateStat()} returns without creating anything unless
     * <code>stat.full</code> is set, and that property defaults to false, so a
     * plain declaration is the same silence under a different name.
     */
    @Test
    public void countersAreDeclaredAsRequired() {
        StatManager stats = mock(StatManager.class);
        FloodfillVerifyStoreJob.registerStats(stats);
        FloodfillDatabaseStoreMessageHandler.registerStats(stats);
        BlindCache.registerStats(stats);
        verify(stats).createRequiredRateStat(eq(FloodfillVerifyStoreJob.STAT_PHASE2_DELAY),
                                             anyString(), eq("NetworkDatabase"),
                                             eq(RateConstants.TUNNEL_VERIFY_RATES));
        verify(stats).createRequiredRateStat(eq(BlindCache.STAT_CACHE_SIZE),
                                             anyString(), eq("NetworkDatabase"),
                                             any(long[].class));
        verify(stats).createRequiredRateStat(eq(HandleFloodfillDatabaseStoreMessageJob.STAT_LOCAL_LS_TO_LOCAL_CLIENT),
                                             anyString(), eq("NetworkDatabase"),
                                             any(long[].class));
    }

    /**
     * Registering twice keeps the first stat. The verification job registers on
     * every job it builds, so a second pass that replaced the stat would discard
     * the samples the first pass collected.
     */
    @Test
    public void registeringTwiceKeepsTheFirstStat() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        FloodfillVerifyStoreJob.registerStats(stats);
        RateStat first = stats.getRate(FloodfillVerifyStoreJob.STAT_PHASE2_DELAY);
        stats.addRateData(FloodfillVerifyStoreJob.STAT_PHASE2_DELAY, 2500);
        FloodfillVerifyStoreJob.registerStats(stats);
        assertSame(first, stats.getRate(FloodfillVerifyStoreJob.STAT_PHASE2_DELAY));
        assertEquals("the second pass must not discard the first pass's sample", 1,
                     first.getLifetimeEventCount());
    }
}
