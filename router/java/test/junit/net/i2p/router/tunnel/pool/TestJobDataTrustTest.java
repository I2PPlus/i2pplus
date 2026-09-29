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

    // ================= per-partner failure rate =================

    @Test
    public void anUnseenPartnerHasNoRecordedRate() {
        Map<Hash, long[]> tally = new ConcurrentHashMap<>();
        assertEquals(0d, TestJob.replyPartnerFailureRate(tally, partner(hash(1))), 0.001d);
    }

    @Test
    public void aPartnerThatAlwaysFailsRatesAtOne() {
        Map<Hash, long[]> tally = new ConcurrentHashMap<>();
        TunnelInfo p = partner(hash(1));
        for (int i = 0; i < 5; i++) {
            TestJob.noteReplyPartnerRound(tally, p, true);
        }
        assertEquals("five failures out of five rounds", 1d,
                     TestJob.replyPartnerFailureRate(tally, p), 0.001d);
    }

    @Test
    public void aPartnerThatAlwaysSucceedsRatesAtZero() {
        Map<Hash, long[]> tally = new ConcurrentHashMap<>();
        TunnelInfo p = partner(hash(1));
        for (int i = 0; i < 5; i++) {
            TestJob.noteReplyPartnerRound(tally, p, false);
        }
        assertEquals(0d, TestJob.replyPartnerFailureRate(tally, p), 0.001d);
    }

    /**
     * The reason the repeat flag was replaced.  A raw "blamed before" flag
     * reads a stable pool as bad because the same partners are re-picked, and
     * a churning pool as good because partners are always new.  A rate is
     * immune to that: these two partners fail equally often, but the one
     * carrying many more rounds has the lower rate.
     */
    @Test
    public void theRateIsIndependentOfHowOftenAPartnerIsPicked() {
        Map<Hash, long[]> tally = new ConcurrentHashMap<>();
        TunnelInfo heavilyUsed = partner(hash(1));
        TunnelInfo lightlyUsed = partner(hash(2));
        // 9 rounds, 3 failed
        for (int i = 0; i < 9; i++) {
            TestJob.noteReplyPartnerRound(tally, heavilyUsed, i < 3);
        }
        // 1 round, 1 failed
        TestJob.noteReplyPartnerRound(tally, lightlyUsed, true);
        double heavy = TestJob.replyPartnerFailureRate(tally, heavilyUsed);
        double light = TestJob.replyPartnerFailureRate(tally, lightlyUsed);
        assertEquals(3d / 9d, heavy, 0.001d);
        assertEquals(1d, light, 0.001d);
        assertTrue("frequency of use must not decide who looks bad", heavy < light);
    }

    @Test
    public void anUnpopulatedGatewayIsNotRecorded() {
        Map<Hash, long[]> tally = new ConcurrentHashMap<>();
        TunnelInfo noGateway = mock(TunnelInfo.class);
        when(noGateway.getGateway()).thenReturn(null);
        TestJob.noteReplyPartnerRound(tally, noGateway, true);
        assertTrue("a null gateway must not be recorded, and must not throw", tally.isEmpty());
        assertEquals(0d, TestJob.replyPartnerFailureRate(tally, noGateway), 0.001d);
    }

    @Test
    public void nullsAreHandled() {
        Map<Hash, long[]> tally = new ConcurrentHashMap<>();
        assertEquals(0d, TestJob.replyPartnerFailureRate(null, partner(hash(1))), 0.001d);
        assertEquals(0d, TestJob.replyPartnerFailureRate(tally, null), 0.001d);
        TestJob.noteReplyPartnerRound(null, partner(hash(1)), true);
        TestJob.noteReplyPartnerRound(tally, null, true);
    }

    @Test
    public void repeatedRoundsStayBounded() {
        Map<Hash, long[]> tally = new ConcurrentHashMap<>();
        for (int i = 0; i < 900; i++) {
            TestJob.noteReplyPartnerRound(tally, partner(hash(i)), i % 2 == 0);
        }
        assertTrue("the tally must stay bounded under sustained use, was " + tally.size(),
                   tally.size() <= 513);
    }

    @Test
    public void directionsHaveSeparateMemories() {
        // The diagnosis is directional: a reply leg that keeps failing for
        // inbound tests is a different finding from one that does so outbound.
        assertNotSame(TestJob.replyPartnerMemory(true), TestJob.replyPartnerMemory(false));
    }
}
