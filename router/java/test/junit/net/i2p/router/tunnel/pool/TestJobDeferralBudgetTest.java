package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TCConfig;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the test-deferral budgets in {@link TestJob#deferralAction(int, int, int)}
 * and where the counters they read are kept.
 *
 * <p>The defect: every counter that bounds the deferral escape - deferrals before
 * the paired pool is rebuilt, the rebuild budget itself, the post-rebuild budget
 * that terminates the wait, and the two partner-leg exemptions - was a field on
 * {@link TestJob}. A job is one round, and its retest is only re-queued while the
 * tunnel has lifetime left, so the counters restarted on the next round.
 * Measured over 42 minutes on a live router: 34 deferrals, every one printing a
 * count of 1, no rebuild ever issued, and a paired pool sitting at 0-1 usable
 * tunnels for the whole window was never rebuilt.
 *
 * @since 0.9.72+
 */
public class TestJobDeferralBudgetTest {

    /** A router context with the log manager and clock the configs need. */
    private RouterContext _ctx;

    /**
     * A context bare enough for a tunnel config to be built against it: the
     * configs under test only need the clock and the log manager.
     */
    @Before
    public void setUp() {
        _ctx = mock(RouterContext.class);
        when(_ctx.logManager()).thenReturn(mock(LogManager.class));
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(1_000_000_000L);
        when(_ctx.clock()).thenReturn(clock);
    }

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state.
     *
     * @since 0.9.72+
     */
    public TestJobDeferralBudgetTest() {}

    // ---- the decision itself ----

    /** Below the deferral cap the round only waits. */
    @Test
    public void belowTheCapTheRoundOnlyWaits() {
        assertEquals(TestJob.DeferralAction.DEFER, TestJob.deferralAction(1, 0, 1));
        assertEquals(TestJob.DeferralAction.DEFER,
                     TestJob.deferralAction(TestJob.MAX_DEFERRED - 1, 0, 1));
    }

    /** At the cap the thing that is missing is the paired pool, so it is rebuilt. */
    @Test
    public void theCapRebuildsThePairedPool() {
        assertEquals(TestJob.DeferralAction.KICK_PAIRED_POOL,
                     TestJob.deferralAction(TestJob.MAX_DEFERRED, 0, 1));
        assertEquals(TestJob.DeferralAction.KICK_PAIRED_POOL,
                     TestJob.deferralAction(TestJob.MAX_DEFERRED,
                                            TestJob.MAX_PAIRED_POOL_KICKS - 1, 1));
    }

    /**
     * Once the rebuild budget is spent the wait is bounded by the post-rebuild
     * budget, and spending that fails the tunnel: a tunnel that can never be
     * tested inflates the untested count and blocks new builds.
     */
    @Test
    public void thePostKickBudgetTerminatesTheWait() {
        int spent = TestJob.MAX_PAIRED_POOL_KICKS;
        assertEquals(TestJob.DeferralAction.DEFER,
                     TestJob.deferralAction(TestJob.MAX_DEFERRED, spent,
                                            TestJob.MAX_POST_KICK_DEFERRALS - 1));
        assertEquals(TestJob.DeferralAction.FAIL_TUNNEL,
                     TestJob.deferralAction(TestJob.MAX_DEFERRED, spent,
                                            TestJob.MAX_POST_KICK_DEFERRALS));
    }

    // ---- where the counters live ----

    /**
     * The defect: with the counters on the job, every round starts at one and
     * the caps are unreachable, so the paired pool is never rebuilt however long
     * the shortage lasts. This is the 34 deferrals at a count of 1.
     */
    @Test
    public void perRoundCountersNeverReachTheCap() {
        int kicks = 0;
        for (int round = 0; round < 40; round++) {
            // a fresh job each round: deferrals 1, kicks 0, post-kick 0
            if (TestJob.deferralAction(1, 0, 1) == TestJob.DeferralAction.KICK_PAIRED_POOL) {
                kicks++;
            }
        }
        assertEquals("a per-round counter cannot reach MAX_DEFERRED, so no rebuild",
                     0, kicks);
    }

    /**
     * With the counters on the tunnel, the same 40 rounds reach every cap: the
     * paired pool is rebuilt MAX_PAIRED_POOL_KICKS times, and a paired pool that
     * never comes back ends with the tunnel failed rather than a wait that has
     * no end.
     */
    @Test
    public void tunnelHeldCountersReachEveryCap() {
        List<TestJob.DeferralAction> actions = new ArrayList<>();
        DeferralState state = new DeferralState();
        for (int round = 0; round < 40 && !actions.contains(TestJob.DeferralAction.FAIL_TUNNEL);
             round++) {
            actions.add(state.round());
        }
        long kicks = actions.stream()
                           .filter(a -> a == TestJob.DeferralAction.KICK_PAIRED_POOL)
                           .count();
        assertEquals("the rebuild budget is spent, once per MAX_DEFERRED rounds",
                     TestJob.MAX_PAIRED_POOL_KICKS, kicks);
        assertEquals("the wait terminates by failing the tunnel",
                     TestJob.DeferralAction.FAIL_TUNNEL,
                     actions.get(actions.size() - 1));
        assertTrue("the tunnel waits before anything is rebuilt",
                   actions.get(0) == TestJob.DeferralAction.DEFER);
    }

    /**
     * The counters belong to the tunnel: they must not leak between tunnels of
     * the same pool, and a pass must clear the consecutive ones.
     */
    @Test
    public void countersBelongToTheTunnelTheyCountFor() {
        TunnelCreatorConfig a = new TCConfig(_ctx, 2, false);
        TunnelCreatorConfig b = new TCConfig(_ctx, 2, false);
        assertEquals(1, a.bumpTestDeferrals());
        assertEquals(2, a.bumpTestDeferrals());
        assertEquals("a different tunnel starts clean", 0, b.getTestDeferrals());
        assertEquals(1, a.bumpPairedPoolKicks());
        assertEquals(1, a.bumpPostKickDeferrals());
        assertEquals(1, a.bumpPartnerDeferrals());
        assertEquals(1, a.bumpReplyPartnerDeferrals());
        a.clearTestDeferrals();
        a.clearPostKickDeferrals();
        a.clearPartnerDeferrals();
        a.clearReplyPartnerDeferrals();
        assertEquals(0, a.getTestDeferrals());
        assertEquals(0, a.getPostKickDeferrals());
        assertEquals(0, a.getPartnerDeferrals());
        assertEquals(0, a.getReplyPartnerDeferrals());
        assertEquals("the rebuild budget is lifetime, not consecutive",
                     1, a.getPairedPoolKicks());
    }

    /**
     * The counters have to be read and written on the tunnel: a job field reads
     * green in unit tests and resets every round in the log.
     *
     * @throws Exception if the TestJob source cannot be read back from disk
     */
    @Test
    public void theJobHoldsNoDeferralCountersItself() throws Exception {
        String rel = "router/java/src/net/i2p/router/tunnel/pool/TestJob.java";
        java.io.File src = new java.io.File(rel);
        if (!src.isFile()) {
            src = new java.io.File("src/net/i2p/router/tunnel/pool/TestJob.java");
        }
        Assume.assumeTrue("source not available", src.isFile());
        String body = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        assertTrue("the deferral count must come off the tunnel",
                   body.contains("int deferrals = _cfg.bumpTestDeferrals();"));
        assertTrue("the rebuild budget must come off the tunnel",
                   body.contains("_cfg.getPairedPoolKicks()"));
        for (String field : new String[] {"_deferredCount", "_pairedPoolKicks",
                                          "_postKickDeferrals", "_partnerDeferrals",
                                          "_replyPartnerDeferrals"}) {
            assertFalse("the " + field + " counter must not live on the job",
                        body.contains("private int " + field));
        }
    }

    /**
     * Mirrors what {@code TestJob.deferForMissingPartner()} does with the
     * tunnel-held counters, so the whole escape can be walked round by round.
     */
    private static final class DeferralState {

        /** Rounds this tunnel has waited for a partner. */
        private int _deferrals;
        /** Paired-pool rebuilds issued so far. */
        private int _kicks;
        /** Rounds waited since the last rebuild. */
        private int _postKick;

        /**
         * Run one deferral round.
         *
         * @return what the pool decided to do about it
         */
        TestJob.DeferralAction round() {
            _deferrals++;
            TestJob.DeferralAction action =
                TestJob.deferralAction(_deferrals, _kicks, _postKick + 1);
            if (action == TestJob.DeferralAction.KICK_PAIRED_POOL) {
                _kicks++;
                _deferrals = 0;
                _postKick = 0;
            } else if (_deferrals >= TestJob.MAX_DEFERRED) {
                _postKick++;
            }
            return action;
        }
    }
}
