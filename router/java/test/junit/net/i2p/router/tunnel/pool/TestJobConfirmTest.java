package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelTestStatus;
import net.i2p.router.tunnel.TCConfig;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

import org.junit.Before;
import org.junit.Test;

/**
 * Contract tests for the confirm-before-charge gate and the failure-counter
 * ceiling added alongside it.
 *
 * <p>A test round has two independent failure sources: the tunnel under test,
 * and the partner leg the reply came back — or failed to come back — through.
 * The partner checks in {@link TestJob#shouldDeferForPartner} only catch a
 * partner that is <em>known</em> bad.  A nominally GOOD partner whose reply was
 * lost, slow, or dropped still got charged to the tunnel under test, and that
 * error is one-directional: an inbound test gets an independently selected
 * partner for its outbound leg, while an outbound test uses the tunnel under
 * test itself.  The gate therefore re-runs the round once against a different
 * partner before charging anything.
 *
 * <p>What matters here is that the gate cannot livelock, cannot mask a
 * genuinely dead tunnel, and cannot charge on an unproven round.  The
 * interaction with the live partner selection is exercised by the pool tests;
 * these pin the pure decision edges.
 *
 * @since 0.9.71+
 */
public class TestJobConfirmTest {

    private RouterContext _ctx;

    @Before
    public void setUp() {
        _ctx = mock(RouterContext.class);
        when(_ctx.logManager()).thenReturn(mock(LogManager.class));
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(1_000_000_000L);
        when(_ctx.clock()).thenReturn(clock);
    }

    /** A freshly recorded strike, one window old, inside the strike window. */
    private static TunnelInfo livePartner() {
        TunnelInfo t = mock(TunnelInfo.class);
        when(t.getLength()).thenReturn(2);
        when(t.getTunnelFailed()).thenReturn(false);
        when(t.getTestStatus()).thenReturn(TunnelTestStatus.GOOD);
        return t;
    }

    // ---- the confirming round must not reuse the partner that just failed ---

    @Test
    public void aDifferentPartnerIsUsableForTheConfirmingRound() {
        TunnelInfo failed = livePartner();
        TunnelInfo other = livePartner();
        assertTrue("a partner other than the one that failed must be usable",
                   TestJob.usablePartner(other, failed));
    }

    /**
     * The core of the gate.  Reusing the failed partner would make the
     * confirming round prove nothing, so the same instance is rejected — even
     * though it is perfectly healthy, which is what makes this an identity
     * check rather than a status check.
     */
    @Test
    public void thePartnerThatJustFailedIsNotUsable() {
        TunnelInfo failed = livePartner();
        assertFalse("the excluded partner must be rejected by identity, not by status",
                    TestJob.usablePartner(failed, failed));
    }

    @Test
    public void aNullCandidateIsNeverUsable() {
        assertFalse("a missing partner cannot carry the round",
                    TestJob.usablePartner(null, livePartner()));
        assertFalse("null excluded and null candidate is still not usable",
                    TestJob.usablePartner(null, null));
    }

    /** With no partner excluded, the ordinary path is unchanged. */
    @Test
    public void anyLivePartnerIsUsableWhenNoneIsExcluded() {
        assertTrue(TestJob.usablePartner(livePartner(), null));
    }

    // ---- a known-good partner still charges after its budget is spent ----

    @Test
    public void goodPartnerIsNotDeferred() {
        assertFalse("a healthy partner means the round is evidence about the tunnel",
                    TestJob.shouldDeferForPartner(livePartner(), false));
    }

    @Test
    public void exploratoryFallbackStillDefers() {
        assertTrue("a borrowed exploratory partner is not evidence about this tunnel",
                   TestJob.shouldDeferForPartner(livePartner(), true));
    }

    // ---- the counter must saturate so the reported number stays readable ----

    /**
     * The ceiling has to sit above the largest removal threshold any caller can
     * compute, or saturation would change a decision rather than just the
     * reported count.  Degraded mode with a thin pool is the maximum.
     */
    @Test
    public void failureCountCeilingIsAboveEveryRemovalThreshold() {
        int worstBar = TestJob.removalThreshold(
                TestJob.baseRemovalThreshold(true), 1);
        assertTrue("ceiling " + TunnelCreatorConfig.FAILURE_COUNT_CEILING
                   + " must exceed the worst removal bar " + worstBar,
                   TunnelCreatorConfig.FAILURE_COUNT_CEILING > worstBar);
    }

    /**
     * Saturation is what stops a retained-but-dead tunnel's counter from
     * running into the hundreds and making the failure ratio unreadable.
     */
    @Test
    public void failureCountSaturatesAtTheCeiling() {
        TunnelCreatorConfig cfg = new TCConfig(_ctx, 2, false);
        int overshoot = TunnelCreatorConfig.FAILURE_COUNT_CEILING * 4;
        for (int i = 0; i < overshoot; i++)
            cfg.incrementTestFailures();
        assertTrue("counter must stop at the ceiling, not keep climbing",
                   cfg.getTunnelFailures() <= TunnelCreatorConfig.FAILURE_COUNT_CEILING);
    }

    /** Saturation must not swallow a count that is still below the ceiling. */
    @Test
    public void failureCountBelowCeilingIsExact() {
        TunnelCreatorConfig cfg = new TCConfig(_ctx, 2, false);
        for (int i = 0; i < 5; i++)
            cfg.incrementTestFailures();
        assertEquals(5, cfg.getTunnelFailures());
    }

    // ---- per-direction stat names must stay distinct ----

    /**
     * The new events must share the existing {@code directionStat} naming
     * scheme, so there is one place that spells a per-direction test stat and
     * no way for a second scheme to drift from it.
     */
    @Test
    public void confirmEventsUseTheExistingDirectionScheme() {
        assertEquals("tunnel.testInboundConfirmFailed",
                     TestJob.directionStat(true, "ConfirmFailed"));
        assertEquals("tunnel.testOutboundCondemned",
                     TestJob.directionStat(false, "Condemned"));
        assertEquals("tunnel.testInboundConfirmPassed",
                     TestJob.directionStat(true, "ConfirmPassed"));
    }

    /** The pre-existing pass/fail pair must still resolve to the same names. */
    @Test
    public void passFailFormIsUnchangedByTheEventOverload() {
        assertEquals("tunnel.testInboundSuccess", TestJob.directionStat(true, true));
        assertEquals("tunnel.testOutboundFailed", TestJob.directionStat(false, false));
    }

    @Test
    public void newEventsDoNotCollideWithTheExistingPair() {
        for (String ev : new String[] {"ConfirmPassed", "ConfirmFailed", "Condemned"}) {
            assertNotEquals(TestJob.directionStat(true, true), TestJob.directionStat(true, ev));
            assertNotEquals(TestJob.directionStat(true, false), TestJob.directionStat(true, ev));
        }
    }

    @Test
    public void buildDirectionStatNamesAreDistinct() {
        assertEquals("tunnel.buildInboundSucceeded",
                     BuildExecutor.buildDirectionStat(true, "Succeeded"));
        assertEquals("tunnel.buildOutboundTimedOut",
                     BuildExecutor.buildDirectionStat(false, "TimedOut"));
    }
}
