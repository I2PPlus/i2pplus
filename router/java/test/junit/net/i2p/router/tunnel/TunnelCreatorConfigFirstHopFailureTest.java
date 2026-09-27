package net.i2p.router.tunnel;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

/**
 * Tests for the windowed first-hop send failure streak on
 * {@link TunnelCreatorConfig}:
 * - bursts inside {@link TunnelCreatorConfig#FIRST_HOP_FAILURE_SPACING_MS}
 *   collapse to one counted failure
 * - spaced failures advance towards
 *   {@link TunnelCreatorConfig#FIRST_HOP_FAILURE_THRESHOLD}
 * - the streak decays out after
 *   {@link TunnelCreatorConfig#FIRST_HOP_FAILURE_WINDOW_MS}
 * - the escalation latch fires exactly once until the streak is cleared
 * - inbound and zero-hop tunnels are not applicable
 * - real traffic and a passing test clear and re-arm the streak
 *
 * @since 0.9.71+
 */
public class TunnelCreatorConfigFirstHopFailureTest {

    private static final long NOW = 1_000_000_000L;
    private static final long SPACING = TunnelCreatorConfig.FIRST_HOP_FAILURE_SPACING_MS;
    private static final long WINDOW = TunnelCreatorConfig.FIRST_HOP_FAILURE_WINDOW_MS;
    private static final int THRESHOLD = TunnelCreatorConfig.FIRST_HOP_FAILURE_THRESHOLD;

    private RouterContext _ctx;
    private File _tmpDir;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-tccfg-1sthop-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());

        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString()))
                .thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);

        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);
    }

    @After
    public void tearDown() {
        File[] children = _tmpDir.listFiles();
        if (children != null) {
            for (File c : children) {c.delete();}
        }
        _tmpDir.delete();
    }

    private TCConfig newCfg() {
        return new TCConfig(_ctx, 4, false);
    }

    // ---------- effectiveFirstHopFailures (pure decay rule) ----------

    @Test
    public void testFreshStreakReturnedUnchanged() {
        assertEquals(3, TunnelCreatorConfig.effectiveFirstHopFailures(3, NOW - 1_000L, NOW, WINDOW));
    }

    @Test
    public void testStreakAtWindowBoundaryDecaysToZero() {
        assertEquals(0, TunnelCreatorConfig.effectiveFirstHopFailures(7, NOW - WINDOW, NOW, WINDOW));
        assertEquals(7, TunnelCreatorConfig.effectiveFirstHopFailures(7, NOW - WINDOW + 1, NOW, WINDOW));
    }

    @Test
    public void testMissingStampDecaysToZero() {
        assertEquals(0, TunnelCreatorConfig.effectiveFirstHopFailures(7, 0L, NOW, WINDOW));
        assertEquals(0, TunnelCreatorConfig.effectiveFirstHopFailures(7, -1L, NOW, WINDOW));
    }

    // ---------- nextFirstHopFailureCount (pure spacing rule) ----------

    @Test
    public void testFirstFailureStartsAtOne() {
        assertEquals(1, TunnelCreatorConfig.nextFirstHopFailureCount(0, 0L, NOW, SPACING, WINDOW));
    }

    @Test
    public void testAgedOutStreakRestartsAtOne() {
        // a stale count must not be carried forward into the new streak
        assertEquals(1, TunnelCreatorConfig.nextFirstHopFailureCount(9, NOW - WINDOW, NOW, SPACING, WINDOW));
    }

    @Test
    public void testBurstInsideSpacingIsNotCounted() {
        assertEquals(-1, TunnelCreatorConfig.nextFirstHopFailureCount(2, NOW - 1L, NOW, SPACING, WINDOW));
        assertEquals(-1, TunnelCreatorConfig.nextFirstHopFailureCount(2, NOW - SPACING + 1, NOW, SPACING, WINDOW));
    }

    @Test
    public void testSpacedFailureAdvances() {
        assertEquals(3, TunnelCreatorConfig.nextFirstHopFailureCount(2, NOW - SPACING, NOW, SPACING, WINDOW));
        assertEquals(4, TunnelCreatorConfig.nextFirstHopFailureCount(3, NOW - SPACING - 1, NOW, SPACING, WINDOW));
    }

    @Test
    public void testMissingStampStartsStreak() {
        // raw count without a stamp is not trustworthy — start over
        assertEquals(1, TunnelCreatorConfig.nextFirstHopFailureCount(5, 0L, NOW, SPACING, WINDOW));
    }

    // ---------- burst collapse on a live config ----------

    @Test
    public void testOutboundFirstFailureCounts() {
        TCConfig cfg = newCfg();
        assertEquals(1, cfg.recordFirstHopSendFailure(NOW));
        assertEquals(1, cfg.getFirstHopSendFailures(NOW));
    }

    @Test
    public void testBurstCollapsesToOne() {
        TCConfig cfg = newCfg();
        assertEquals(1, cfg.recordFirstHopSendFailure(NOW));
        // the shared send-failure job runs once per failed message; a burst
        // inside the spacing window must not advance the streak
        assertEquals(0, cfg.recordFirstHopSendFailure(NOW + 10));
        assertEquals(0, cfg.recordFirstHopSendFailure(NOW + SPACING - 1));
        assertEquals(1, cfg.getFirstHopSendFailures(NOW));
    }

    @Test
    public void testSpacedFailuresReachThreshold() {
        TCConfig cfg = newCfg();
        assertEquals(1, cfg.recordFirstHopSendFailure(NOW));
        assertEquals(2, cfg.recordFirstHopSendFailure(NOW + SPACING));
        assertEquals(THRESHOLD, cfg.recordFirstHopSendFailure(NOW + 2 * SPACING));
    }

    @Test
    public void testStaleStreakRestartsInsteadOfEscalating() {
        TCConfig cfg = newCfg();
        assertEquals(1, cfg.recordFirstHopSendFailure(NOW));
        assertEquals(2, cfg.recordFirstHopSendFailure(NOW + SPACING));
        // long quiet period: the two earlier failures must not combine with
        // this one to reach the threshold.  The window runs from the last
        // counted failure, so it has to be aged out from NOW + SPACING.
        assertEquals(1, cfg.recordFirstHopSendFailure(NOW + SPACING + WINDOW));
        assertEquals(1, cfg.getFirstHopSendFailures(NOW + SPACING + WINDOW));
    }

    // ---------- escalation latch ----------

    @Test
    public void testLatchFiresOnlyOnce() {
        TCConfig cfg = newCfg();
        assertEquals(1, cfg.recordFirstHopSendFailure(NOW));
        assertEquals(2, cfg.recordFirstHopSendFailure(NOW + SPACING));
        assertEquals(THRESHOLD, cfg.recordFirstHopSendFailure(NOW + 2 * SPACING));
        // once escalated the caller is told exactly once, however many more
        // messages fail on the same shared job
        assertEquals(0, cfg.recordFirstHopSendFailure(NOW + 3 * SPACING));
        assertEquals(0, cfg.recordFirstHopSendFailure(NOW + 4 * SPACING));
    }

    @Test
    public void testClearRearmsLatchAndStreak() {
        TCConfig cfg = newCfg();
        assertEquals(1, cfg.recordFirstHopSendFailure(NOW));
        assertEquals(2, cfg.recordFirstHopSendFailure(NOW + SPACING));
        assertEquals(THRESHOLD, cfg.recordFirstHopSendFailure(NOW + 2 * SPACING));
        cfg.clearFirstHopFailures();
        assertEquals(0, cfg.getFirstHopSendFailures(NOW));
        // re-armed: the next failure starts a fresh streak at 1, not 4
        assertEquals(1, cfg.recordFirstHopSendFailure(NOW + 3 * SPACING));
    }

    // ---------- proof of life clears the streak ----------

    @Test
    public void testRealTrafficClearsStreak() {
        TCConfig cfg = newCfg();
        cfg.recordFirstHopSendFailure(NOW);
        cfg.recordFirstHopSendFailure(NOW + SPACING);
        cfg.recordRealTraffic();
        assertEquals(0, cfg.getFirstHopSendFailures(NOW));
        assertEquals(1, cfg.recordFirstHopSendFailure(System.currentTimeMillis()));
    }

    @Test
    public void testTestSuccessfulDoesNotClearStreak() {
        // A passing test proves the peer is not dead, but not that it is
        // not flaky.  The streak is aged by the decay window, not reset by
        // a single success — otherwise a flaky first hop that fails twice,
        // passes a test, fails twice more, etc. would never be detected.
        TCConfig cfg = newCfg();
        cfg.recordFirstHopSendFailure(NOW);
        cfg.recordFirstHopSendFailure(NOW + SPACING);
        cfg.testSuccessful(100);
        assertEquals(2, cfg.getFirstHopSendFailures(NOW + 2 * SPACING));
        assertEquals(3, cfg.recordFirstHopSendFailure(NOW + 2 * SPACING));
    }

    @Test
    public void testTestSuccessfulDoesNotFailTunnel() {
        TCConfig cfg = newCfg();
        cfg.recordFirstHopSendFailure(NOW);
        cfg.recordFirstHopSendFailure(NOW + SPACING);
        cfg.recordFirstHopSendFailure(NOW + 2 * SPACING);
        cfg.testSuccessful(100);
        assertEquals(0, cfg.getTunnelFailures());
        assertFalse(cfg.getTunnelFailed());
    }

    // ---------- scope ----------

    @Test
    public void testInboundNotApplicable() {
        TCConfig cfg = new TCConfig(_ctx, 4, true);
        assertEquals(-1, cfg.recordFirstHopSendFailure(NOW));
        assertEquals(0, cfg.getFirstHopSendFailures(NOW));
    }

    @Test
    public void testZeroHopNotApplicable() {
        TCConfig cfg = new TCConfig(_ctx, 1, false);
        assertEquals(-1, cfg.recordFirstHopSendFailure(NOW));
    }

    @Test
    public void testThresholdMatchesConsecutiveTestBar() {
        // first-hop escalation must reach the same bar getTunnelFailed() uses,
        // or a tunnel could be handed off at the threshold and still read as
        // alive
        assertTrue(THRESHOLD <= TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES + 1);
        assertTrue(THRESHOLD > 1);
    }
}
