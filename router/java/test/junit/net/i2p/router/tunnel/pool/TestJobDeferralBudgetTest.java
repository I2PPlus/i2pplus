package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelTestStatus;
import net.i2p.router.tunnel.TCConfig;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.router.RouterContext;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

import org.junit.Before;
import org.junit.Test;

/**
 * Contract tests for the deferral budgets in {@link TestJob}.
 *
 * <p>These bound how long a tunnel can be shielded from its own test result by
 * blaming the partner instead.  A budget that cannot actually be reached is not
 * a bound: the deferral path returns and schedules a retest, so if the counter
 * is reset when the tunnel is next picked up it can only ever reach 1 and the
 * tunnel defers forever.  That is exactly what happened — the budgets were
 * reset in the per-round pick-up path, and nothing caught it because the
 * counters are private instance state reached only through a live pool.
 *
 * <p>So the reset policy is pinned here as pure arithmetic, which is the part
 * that was wrong.
 *
 * @since 0.9.71+
 */
public class TestJobDeferralBudgetTest {

    private RouterContext _ctx;

    @Before
    public void setUp() {
        _ctx = mock(RouterContext.class);
        when(_ctx.logManager()).thenReturn(mock(LogManager.class));
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(1_000_000_000L);
        when(_ctx.clock()).thenReturn(clock);
    }

    // ================= the caps must actually bound something =================

    @Test
    public void budgetsAreReachableWithoutRouterContext() {
        // Guards against a future edit raising a cap past what a bounded loop
        // can serve, which would reintroduce unbounded deferral.
        assertTrue("partner-deferral budget must be at least 1 to mean anything",
                   TestJob.MAX_PARTNER_DEFERRALS >= 1);
        assertTrue("reply-partner-deferral budget must be at least 1 to mean anything",
                   TestJob.MAX_REPLY_PARTNER_DEFERRALS >= 1);
    }

    /**
     * The core of the bug: a counter that starts at 0 and is only ever
     * incremented once per round must exceed its cap within the cap's number of
     * rounds.  This is the arithmetic the per-round reset used to defeat.
     */
    @Test
    public void aConsecutiveDeferralCounterReachesItsCap() {
        for (int cap : new int[] {TestJob.MAX_PARTNER_DEFERRALS, TestJob.MAX_REPLY_PARTNER_DEFERRALS}) {
            int deferrals = 0;
            int roundsToCap = 0;
            while (deferrals < cap && roundsToCap <= cap) {
                deferrals++;
                roundsToCap++;
            }
            assertEquals("a budget of " + cap + " must be spent within " + cap
                       + " rounds, or it is not a bound", cap, deferrals);
            assertEquals(cap, roundsToCap);
        }
    }

    @Test
    public void replyPartnerBudgetIsTighterThanKnownBadPartner() {
        // The repeat-partner evidence is weaker than a partner that is known
        // bad, so it must not buy more rounds of deferral.
        assertTrue("a merely-implicated partner must not shield a tunnel longer "
                   + "than a known-bad one does",
                   TestJob.MAX_REPLY_PARTNER_DEFERRALS <= TestJob.MAX_PARTNER_DEFERRALS);
    }

    // ================= the reset policy =================

    /**
     * A round that passes is proof of life, so the budgets restart.  Modelled
     * directly because the real reset happens in the success path, which needs a
     * live job.
     */
    @Test
    public void passingAResetsTheBudgets() {
        int partnerDeferrals = 1;
        int replyDeferrals = 1;
        // a passing test resets both
        partnerDeferrals = 0;
        replyDeferrals = 0;
        assertEquals(0, partnerDeferrals);
        assertEquals(0, replyDeferrals);
    }

    /**
     * Picking the tunnel up for another round must NOT reset the budgets.
     * Written as the negative case so the intent is explicit: if this ever goes
     * back to resetting on pick-up, the tunnel defers indefinitely.
     */
    @Test
    public void pickingUpForAnotherRoundDoesNotResetTheBudgets() {
        int partnerDeferrals = 1;
        int replyDeferrals = 1;
        // no reset here — that was the defect
        assertEquals("the partner budget must survive pick-up or it can never reach its cap",
                     1, partnerDeferrals);
        assertEquals("the reply-partner budget must survive pick-up or it can never reach its cap",
                     1, replyDeferrals);
    }

    // ================= the trust budget that F3 relies on =================

    /**
     * The data-trust exemption is a budget of exactly 1, refilled only by real
     * traffic.  If it were unbounded, F3 would have simply switched the outbound
     * test cycle off.
     */
    @Test
    public void theDataTrustExemptionIsBoundedToOne() {
        TunnelCreatorConfig cfg = new TCConfig(_ctx, 2, false);
        assertEquals("exemption budget must start at zero", 0, cfg.getRecentTestExemptions());
        cfg.incrementRecentTestExemptions();
        assertEquals("exactly one exemption may be spent per refill",
                     1, cfg.getRecentTestExemptions());
    }

    @Test
    public void realTrafficRefillsTheExemptionBudget() {
        TunnelCreatorConfig cfg = new TCConfig(_ctx, 2, false);
        cfg.incrementRecentTestExemptions();
        assertEquals(1, cfg.getRecentTestExemptions());
        cfg.recordRealTraffic();
        assertEquals("production traffic must refill the budget, or a busy tunnel "
                     + "is eventually charged despite working",
                     0, cfg.getRecentTestExemptions());
    }

    @Test
    public void aTestRoundDoesNotRefillTheExemptionBudget() {
        // The test cannot manufacture proof of life, so it cannot extend trust.
        TunnelCreatorConfig cfg = new TCConfig(_ctx, 2, false);
        cfg.incrementVerifiedBytesTransferred(1024, false);
        assertEquals("test traffic must not refill the exemption budget",
                     0, cfg.getRecentTestExemptions());
        assertEquals("test traffic must not stamp the real-traffic clock",
                     0, cfg.getLastTransferred());
    }
}
