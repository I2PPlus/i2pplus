package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;

/**
 * The two counters the floodfill rotation tier reports are registered, not just
 * written.
 * <p>
 * {@link StatManager#addRateData(String, long)} looks the name up and drops the
 * write when no such stat was ever created, so a counter added without its
 * registration counts nothing while looking like a working one. Both rotation
 * counters behaved that way from the commit that added them: neither
 * <code>netDb.floodfillRotatedOut</code> nor <code>netDb.floodfillReasked</code>
 * reached the stats page on a router that had run for two hours and rotated
 * floodfills on every LeaseSet search, so the pair could not answer the question
 * the tier was sized against - how many floodfills a selection held back, and
 * how many it had to give back because nothing fresher filled its slots.
 *
 * @since 0.9.72+
 */
public class FloodfillRotationStatsTest {

    /**
     * Test case carrying the rotation-counter registration cases. Every case
     * calls the selector's static registration against a fresh StatManager, so
     * the instance holds no state and needs no router.
     */
    public FloodfillRotationStatsTest() {
    }

    /**
     * A manager holding the stats exactly as the selector's own constructor
     * leaves them.
     *
     * @return a manager with the two rotation counters registered
     */
    private static StatManager registeredStats() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        FloodfillPeerSelector.createRotationStats(stats, RateConstants.SHORT_TERM_RATES);
        return stats;
    }

    /**
     * Every name the selection writes is one the selector registers. This is the
     * invariant the silent drop breaks: a write site and its registration have
     * to agree on the name, which is why both go through the same constants.
     */
    @Test
    public void everyWrittenNameIsRegistered() {
        StatManager stats = registeredStats();
        assertNotNull(FloodfillPeerSelector.STAT_ROTATED_OUT +
                      " must be registered, or addRateData() drops it silently",
                      stats.getRate(FloodfillPeerSelector.STAT_ROTATED_OUT));
        assertNotNull(FloodfillPeerSelector.STAT_REASKED +
                      " must be registered, or addRateData() drops it silently",
                      stats.getRate(FloodfillPeerSelector.STAT_REASKED));
    }

    /**
     * A write to a registered rotation counter is counted. Registering a name
     * that the write site does not use, or under which nothing lands, would pass
     * the lookup tests above and still report nothing.
     */
    @Test
    public void writesAreCountedOnceRegistered() {
        StatManager stats = registeredStats();
        stats.addRateData(FloodfillPeerSelector.STAT_ROTATED_OUT, 4);
        stats.addRateData(FloodfillPeerSelector.STAT_REASKED, 2);
        assertEquals(1, stats.getRate(FloodfillPeerSelector.STAT_ROTATED_OUT).getLifetimeEventCount());
        assertEquals(1, stats.getRate(FloodfillPeerSelector.STAT_REASKED).getLifetimeEventCount());
    }

    /**
     * The names stay the ones the stats page and any saved graph search for. A
     * rename at one of the two sites, or in the registration alone, recreates
     * the unregistered-name fault this pins.
     */
    @Test
    public void namesAreTheDocumentedOnes() {
        assertEquals("netDb.floodfillRotatedOut", FloodfillPeerSelector.STAT_ROTATED_OUT);
        assertEquals("netDb.floodfillReasked", FloodfillPeerSelector.STAT_REASKED);
    }

    /**
     * The counters sit in the NetDb group, beside the other <code>netDb.</code>
     * counters, and carry the short-term periods the console graphs - a stat in
     * the wrong group is a stat nobody looks for.
     */
    @Test
    public void statsSitBesideTheOtherNetDbCounters() {
        StatManager stats = registeredStats();
        RateStat rotated = stats.getRate(FloodfillPeerSelector.STAT_ROTATED_OUT);
        RateStat reasked = stats.getRate(FloodfillPeerSelector.STAT_REASKED);
        assertEquals("NetworkDatabase", rotated.getGroupName());
        assertEquals("NetworkDatabase", reasked.getGroupName());
        assertNotNull("the ten-minute rate the console graphs must exist",
                      rotated.getRate(RateConstants.TEN_MINUTES));
        assertNotNull("the ten-minute rate the console graphs must exist",
                      reasked.getRate(RateConstants.TEN_MINUTES));
    }

    /**
     * Registering twice keeps the first stat. A router builds one selector per
     * NetDb facade, so the registration runs more than once in a normal startup,
     * and a second pass that replaced the stat would discard the samples the
     * first one collected.
     */
    @Test
    public void registeringTwiceKeepsTheFirstStat() {
        StatManager stats = registeredStats();
        RateStat first = stats.getRate(FloodfillPeerSelector.STAT_REASKED);
        assertNotNull("the first registration must produce a stat to keep", first);
        FloodfillPeerSelector.createRotationStats(stats, RateConstants.SHORT_TERM_RATES);
        assertSame(first, stats.getRate(FloodfillPeerSelector.STAT_REASKED));
    }
}
