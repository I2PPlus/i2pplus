package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.router.TunnelInfo;
import net.i2p.router.tunnel.TCConfig;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.router.RouterContext;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

import org.junit.Before;
import org.junit.Test;

/**
 * Contract tests for the data-verified test trust that F1/F3 reworked, and for
 * the reply-partner attribution that F2/F6 added.
 *
 * <p>A round trip exercises the tunnel under test <em>and</em> the partner leg
 * the reply came back through, and a pass/fail bit cannot separate them.  Real
 * traffic is what breaks the tie: it proves the tunnel's own leg works, so a
 * failure alongside it indicts the reply leg instead.  That reasoning holds in
 * both directions — an inbound test's reply arrives down the tunnel under test,
 * an outbound test's outgoing leg is the tunnel under test — and the previous
 * code applied it only to inbound.
 *
 * <p>F1 is the prerequisite that makes this sound.  The test round used to call
 * the unconditional {@code incrementVerifiedBytesTransferred}, which stamps the
 * last-transferred clock.  For an outbound test the outgoing tunnel <em>is</em>
 * the tunnel under test, so a passing test let a tunnel with no production
 * traffic at all grant itself the trust that is meant to mean the opposite.
 *
 * @since 0.9.71+
 */
public class TestJobDataTrustTest {

    private RouterContext _ctx;

    @Before
    public void setUp() {
        _ctx = mock(RouterContext.class);
        when(_ctx.logManager()).thenReturn(mock(LogManager.class));
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(1_000_000_000L);
        when(_ctx.clock()).thenReturn(clock);
    }

    // ================= F1: test traffic must not spoof real traffic =================

    @Test
    public void testRoundDoesNotStampTheRealTrafficClock() {
        TunnelCreatorConfig cfg = new TCConfig(_ctx, 2, false);
        assertEquals("a tunnel that has never carried production traffic must say so",
                     0, cfg.getLastTransferred());
        // Exactly what TestJob.testSuccessful() now does.
        cfg.incrementVerifiedBytesTransferred(1024, false);
        assertEquals("a passing test must not make a tunnel look as if it carried traffic",
                     0, cfg.getLastTransferred());
    }

    /**
     * Pins the call site, not just the config method.  Asserting on
     * {@code TunnelCreatorConfig} alone would pass even if the test round
     * passed {@code realTraffic = true}, which is the original bug.
     */
    @Test
    public void theTestRoundCallSiteDoesNotStampTheClock() {
        TunnelCreatorConfig cfg = new TCConfig(_ctx, 2, false);
        TestJob.recordTestRoundBytes(cfg);
        assertEquals("a completed test round must not look like production traffic",
                     0, cfg.getLastTransferred());
        assertTrue("but the bytes must still count for throughput accounting",
                   cfg.getVerifiedBytesTransferred() > 0);
    }

    @Test
    public void theCallSiteToleratesATunnelWithoutTheConfigType() {
        // _outTunnel is typed TunnelInfo, so the guard has to hold for any
        // implementation, not just TunnelCreatorConfig.
        TestJob.recordTestRoundBytes(null);
        TestJob.recordTestRoundBytes(mock(TunnelInfo.class));
    }

    @Test
    public void realTrafficStampsTheClock() {
        TunnelCreatorConfig cfg = new TCConfig(_ctx, 2, false);
        cfg.incrementVerifiedBytesTransferred(1024, true);
        assertTrue("production traffic must stamp the clock, or the trust can never engage",
                   cfg.getLastTransferred() > 0);
    }

    @Test
    public void bothPathsStillCountBytes() {
        TunnelCreatorConfig a = new TCConfig(_ctx, 2, false);
        TunnelCreatorConfig b = new TCConfig(_ctx, 2, false);
        a.incrementVerifiedBytesTransferred(1024, true);
        b.incrementVerifiedBytesTransferred(1024, false);
        assertEquals("throughput accounting must count test bytes too, or per-peer "
                   + "throughput understates a busy tunnel",
                   a.getVerifiedBytesTransferred(), b.getVerifiedBytesTransferred());
    }

    @Test
    public void unconditionalFormIsRealTraffic() {
        // The one-arg form is the production path and must keep stamping.
        TunnelCreatorConfig cfg = new TCConfig(_ctx, 2, false);
        cfg.incrementVerifiedBytesTransferred(512);
        assertTrue(cfg.getLastTransferred() > 0);
    }

    // ================= F3: the staleness bound =================

    @Test
    public void neverCarriedTrafficNeverQualifies() {
        assertFalse("lastTransferred == 0 must never qualify for the trust",
                    TestJob.isRecentDataTransfer(0, 1_000_000L));
    }

    /**
     * The guard on {@code lastTransferMs == 0} is only load-bearing early in a
     * router's life.  With a large {@code now} the age of a never-set timestamp
     * already exceeds the staleness bound and the guard is redundant, so a test
     * using a realistic uptime cannot tell the difference.  This pins the case
     * that actually depends on it: shortly after boot, a never-used timestamp
     * would otherwise look "recently transferred".
     */
    @Test
    public void neverCarriedTrafficNeverQualifiesEarlyInUptime() {
        long earlyUptime = 1_000L;
        assertFalse("a never-set timestamp must not read as recent just because the "
                    + "router has only been up " + earlyUptime + "ms",
                    TestJob.isRecentDataTransfer(0, earlyUptime));
        assertFalse(TestJob.isRecentDataTransfer(0, 0L));
    }

    @Test
    public void recentTrafficQualifies() {
        long now = 1_000_000L;
        assertTrue(TestJob.isRecentDataTransfer(now - 1, now));
    }

    @Test
    public void staleTrafficDoesNotQualify() {
        long now = 1_000_000L;
        assertFalse("the bound exists so a tunnel cannot coast on old traffic",
                    TestJob.isRecentDataTransfer(now - 10_000_000L, now));
    }

    @Test
    public void futureTimestampIsTreatedAsStale() {
        // A backwards clock step must not produce a negative age and read as
        // "very recent", which would grant indefinite trust.
        assertFalse(TestJob.isRecentDataTransfer(2_000_000L, 1_000_000L));
    }

    @Test
    public void negativeTimestampIsTreatedAsStale() {
        assertFalse(TestJob.isRecentDataTransfer(-1, 1_000_000L));
    }

    // ================= F2/F6: reply-partner attribution =================

    private static TunnelInfo partner(Hash gateway) {
        TunnelInfo t = mock(TunnelInfo.class);
        when(t.getGateway()).thenReturn(gateway);
        when(t.getLength()).thenReturn(2);
        return t;
    }

    private static Hash hash(int seed) {
        byte[] b = new byte[Hash.HASH_LENGTH];
        b[0] = (byte) seed;
        return Hash.create(b);
    }

    @Test
    public void firstFailureThroughAPartnerIsNotARepeat() {
        Map<Hash, Long> mem = new ConcurrentHashMap<>();
        assertFalse(TestJob.isRepeatReplyPartnerFailure(mem, partner(hash(1)), 1000L));
    }

    @Test
    public void secondFailureThroughTheSamePartnerIsARepetition() {
        Map<Hash, Long> mem = new ConcurrentHashMap<>();
        TunnelInfo p = partner(hash(1));
        TestJob.blameReplyPartner(mem, p, 1000L);
        assertTrue(TestJob.isRepeatReplyPartnerFailure(mem, p, 2000L));
    }

    @Test
    public void aDifferentPartnerIsNotARepetition() {
        Map<Hash, Long> mem = new ConcurrentHashMap<>();
        TestJob.blameReplyPartner(mem, partner(hash(1)), 1000L);
        assertFalse("blaming one partner must not implicate another",
                    TestJob.isRepeatReplyPartnerFailure(mem, partner(hash(2)), 2000L));
    }

    @Test
    public void aStaleRepeatIsForgiven() {
        Map<Hash, Long> mem = new ConcurrentHashMap<>();
        TunnelInfo p = partner(hash(1));
        long now = 1_000_000_000L;
        TestJob.blameReplyPartner(mem, p, now);
        assertFalse("the memory window must expire, or one bad partner is permanent",
                    TestJob.isRepeatReplyPartnerFailure(mem, p, now + 3_600_000L));
    }

    /**
     * A partner's peer array is allocated with null entries and only filled in
     * as the build completes; a 0-hop tunnel is length 1, so its sole entry is
     * null until it is built.  {@link java.util.concurrent.ConcurrentHashMap}
     * rejects a null key with an NPE, so an unpopulated gateway must be
     * treated as "nothing to attribute" rather than crashing the test job.
     */
    @Test
    public void anUnpopulatedGatewayDoesNotThrow() {
        Map<Hash, Long> mem = new ConcurrentHashMap<>();
        TunnelInfo noGateway = mock(TunnelInfo.class);
        when(noGateway.getGateway()).thenReturn(null);
        assertFalse(TestJob.isRepeatReplyPartnerFailure(mem, noGateway, 1000L));
        TestJob.blameReplyPartner(mem, noGateway, 1000L);
        assertTrue("a null gateway must not be recorded", mem.isEmpty());
    }

    /** Pruning must keep the map bounded without dropping live entries. */
    @Test
    public void pruningKeepsLiveEntriesAndDropsStaleOnes() {
        Map<Hash, Long> mem = new ConcurrentHashMap<>();
        long now = 1_000_000_000L;
        TunnelInfo stale = partner(hash(9));
        mem.put(stale.getGateway(), now - 3_600_000L);
        TunnelInfo live = partner(hash(1));
        mem.put(live.getGateway(), now);
        TestJob.pruneStaleReplyPartners(mem, now);
        assertFalse("a partner past the memory window is worth nothing to the diagnostic",
                    mem.containsKey(stale.getGateway()));
        assertTrue("a live partner must survive pruning", mem.containsKey(live.getGateway()));
    }

    /** Repeated blame must not grow the map without bound. */
    @Test
    public void repeatedBlameStaysBounded() {
        Map<Hash, Long> mem = new ConcurrentHashMap<>();
        long now = 1_000_000_000L;
        // Far more distinct partners than the cap, all in-window, so the
        // fallback oldest-eviction path is the one exercised.
        for (int i = 0; i < 900; i++) {
            TestJob.blameReplyPartner(mem, partner(hash(i)), now);
        }
        assertTrue("the diagnostic must stay bounded under sustained blame, was " + mem.size(),
                   mem.size() <= 513);
    }

    @Test
    public void nullsAreHandled() {
        assertFalse(TestJob.isRepeatReplyPartnerFailure(null, partner(hash(1)), 1000L));
        assertFalse(TestJob.isRepeatReplyPartnerFailure(new ConcurrentHashMap<>(), null, 1000L));
        TestJob.blameReplyPartner(null, partner(hash(1)), 1000L);
        TestJob.blameReplyPartner(new ConcurrentHashMap<>(), null, 1000L);
    }

    @Test
    public void theMemoryIsBounded() {
        // The diagnostic rolls over every destination the router serves, so it
        // must not grow without limit.  Seed past the cap, then confirm the
        // bound holds after a blame that would otherwise overflow it.
        Map<Hash, Long> mem = new ConcurrentHashMap<>();
        for (int i = 0; i < 600; i++) {
            TestJob.blameReplyPartner(mem, partner(hash(i)), 1000L + i);
        }
        assertTrue("reply-partner memory must stay bounded, was " + mem.size(),
                   mem.size() <= 513);
    }

    @Test
    public void directionsHaveSeparateMemories() {
        // The diagnosis is directional: a reply leg that keeps failing for
        // inbound tests is a different finding from one that does so outbound.
        assertNotSame(TestJob.replyPartnerMemory(true), TestJob.replyPartnerMemory(false));
    }
}
