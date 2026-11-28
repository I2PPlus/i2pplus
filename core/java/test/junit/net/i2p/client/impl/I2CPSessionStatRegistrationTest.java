package net.i2p.client.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;

/**
 * The two outbound I2CP message-size counters are registered, not just written.
 *
 * <p>{@link StatManager#addRateData(String, long)} looks the name up and drops
 * the write when no such stat was created, so both counters reported nothing
 * while looking like working ones: every I2CP message reported its size before
 * and after compression, and neither number survived, which left how much
 * compression an application's traffic actually got unmeasurable.
 *
 * <p>They also have to be declared as required. {@code createRateStat()} is a
 * no-op unless <code>stat.full</code> is set, and that property defaults to
 * false, so a plain declaration is the same silence under another name.
 *
 * @since 0.9.72+
 */
public class I2CPSessionStatRegistrationTest {

    /** Every counter name the session writes. */
    private static final String[] WRITTEN = {
        I2PSessionMuxedImpl.STAT_TX_MSG_COMPRESSED,
        I2PSessionMuxedImpl.STAT_TX_MSG_EXPANDED,
    };

    /** The periods the counters record on, as the registration is handed them. */
    private static final long[] PERIODS = {
        RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR
    };

    /**
     * Test case carrying the outbound-counter registration cases. Every case
     * calls the session's static registration or opens a session, so the
     * instance holds no state and needs no router.
     */
    public I2CPSessionStatRegistrationTest() {
    }

    /**
     * A manager holding the counters as the registration leaves them.
     *
     * @return a manager with both outbound-size counters registered
     */
    private static StatManager registeredStats() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        I2PSessionMuxedImpl.registerStats(stats, PERIODS);
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
     * A write to a registered counter is counted. Registering names the write
     * sites do not use would pass the lookup cases and still report nothing.
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
     * The names stay the ones the stats page and any saved graph search for. A
     * rename at the constant alone recreates the unregistered-name fault this
     * pins, because the write sites and the registration move together.
     */
    @Test
    public void namesAreTheDocumentedOnes() {
        assertEquals("i2cp.tx.msgCompressed", I2PSessionMuxedImpl.STAT_TX_MSG_COMPRESSED);
        assertEquals("i2cp.tx.msgExpanded", I2PSessionMuxedImpl.STAT_TX_MSG_EXPANDED);
    }

    /**
     * The counters sit in the group the other I2CP counters use and carry the
     * periods the console graphs - a stat in the wrong group is a stat nobody
     * looks for.
     */
    @Test
    public void countersSitInTheClientMessagesGroup() {
        StatManager stats = registeredStats();
        for (String name : WRITTEN) {
            RateStat rs = stats.getRate(name);
            assertEquals(name + " must sit with the other I2CP counters",
                         "ClientMessages", rs.getGroupName());
            assertNotNull(name + " must carry the ten-minute rate the console graphs",
                          rs.getRate(RateConstants.TEN_MINUTES));
        }
    }

    /**
     * Both counters are declared through the required variant. The plain
     * {@code createRateStat()} returns without creating anything unless
     * <code>stat.full</code> is set, and that property defaults to false, so a
     * plain declaration is the same silence under a different name.
     */
    @Test
    public void countersAreDeclaredAsRequired() {
        StatManager stats = mock(StatManager.class);
        I2PSessionMuxedImpl.registerStats(stats, PERIODS);
        for (String name : WRITTEN)
            verify(stats).createRequiredRateStat(eq(name), anyString(), eq("ClientMessages"), same(PERIODS));
        verifyNoMoreInteractions(stats);
    }

    /**
     * Registering twice keeps the first stat. The registration runs on every
     * session open, so a second pass that replaced the stat would discard the
     * samples the first one collected.
     */
    @Test
    public void registeringTwiceKeepsTheFirstStat() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        I2PSessionMuxedImpl.registerStats(stats, PERIODS);
        RateStat first = stats.getRate(I2PSessionMuxedImpl.STAT_TX_MSG_COMPRESSED);
        stats.addRateData(I2PSessionMuxedImpl.STAT_TX_MSG_COMPRESSED, 7);
        I2PSessionMuxedImpl.registerStats(stats, PERIODS);
        assertSame(first, stats.getRate(I2PSessionMuxedImpl.STAT_TX_MSG_COMPRESSED));
        assertEquals("the second pass must not discard the first pass's sample", 1,
                     first.getLifetimeEventCount());
    }

    /**
     * Opening a session registers the counters, so the write sites are never
     * the only place the names appear. A registration nobody calls is the same
     * silent drop as no registration at all.
     *
     * @throws Exception on a key or key file the fixture cannot produce
     */
    @Test
    public void openingASessionRegistersTheCounters() throws Exception {
        I2PAppContext ctx = new I2PAppContext();
        ByteArrayOutputStream keys = new ByteArrayOutputStream();
        I2PClientFactory.createClient().createDestination(keys);
        I2PSessionMuxedImpl session = new I2PSessionMuxedImpl(
                ctx, new ByteArrayInputStream(keys.toByteArray()), null);
        assertNotNull("the session must have read its keys", session.getMyDestination());
        for (String name : WRITTEN)
            assertNotNull(name + " must exist once a session is open",
                          ctx.statManager().getRate(name));
    }
}
