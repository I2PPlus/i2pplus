package net.i2p.router.tunnel;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.TunnelTestStatus;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

/**
 * Tests for the windowed soft best-effort streak and the last-chance test
 * admission state on {@link TunnelCreatorConfig}:
 * - soft failures only accumulate inside {@link TunnelCreatorConfig#SOFT_FAILURE_WINDOW_MS}
 * - verified traffic and traffic-proof test resets clear the streak
 * - a passing test does NOT clear it (the test path is not the data path)
 * - last-chance admissions snapshot their deadline at admission time and are
 *   revoked by settlement, pruning, or expiry
 *
 * @since 0.9.71+
 */
public class TunnelCreatorConfigSoftWindowTest {

    private static final long NOW = 1_000_000_000L;
    private static final long WINDOW = TunnelCreatorConfig.SOFT_FAILURE_WINDOW_MS;

    private RouterContext _ctx;
    private File _tmpDir;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-tccfg-soft-" + System.nanoTime());
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
        return new TCConfig(_ctx, 2, false);
    }

    // ---------- effectiveSoftFailures (pure decay rule) ----------

    @Test
    public void testFreshStreakReturnedUnchanged() {
        assertEquals(3, TunnelCreatorConfig.effectiveSoftFailures(3, NOW - 1_000L, NOW, WINDOW));
    }

    @Test
    public void testStreakAtWindowBoundaryDecaysToZero() {
        // now - last == window is already outside the window
        assertEquals(0, TunnelCreatorConfig.effectiveSoftFailures(7, NOW - WINDOW, NOW, WINDOW));
        assertEquals(7, TunnelCreatorConfig.effectiveSoftFailures(7, NOW - WINDOW + 1, NOW, WINDOW));
    }

    @Test
    public void testMissingStampDecaysToZero() {
        assertEquals(0, TunnelCreatorConfig.effectiveSoftFailures(7, 0L, NOW, WINDOW));
        assertEquals(0, TunnelCreatorConfig.effectiveSoftFailures(7, -1L, NOW, WINDOW));
    }

    @Test
    public void testBackwardsClockDecaysToZero() {
        assertEquals(0, TunnelCreatorConfig.effectiveSoftFailures(7, NOW + 5_000L, NOW, WINDOW));
    }

    @Test
    public void testNonPositiveRawDecaysToZero() {
        assertEquals(0, TunnelCreatorConfig.effectiveSoftFailures(0, NOW, NOW, WINDOW));
        assertEquals(0, TunnelCreatorConfig.effectiveSoftFailures(-2, NOW, NOW, WINDOW));
    }

    // ---------- streak integration ----------

    @Test
    public void testSoftFailuresAccumulate() {
        TCConfig cfg = newCfg();
        assertEquals(0, cfg.getSoftFailures());
        cfg.incrementSoftFailures();
        cfg.incrementSoftFailures();
        cfg.incrementSoftFailures();
        assertEquals(3, cfg.getSoftFailures());
    }

    @Test
    public void testSoftFailuresDontTripHardCounter() {
        TCConfig cfg = newCfg();
        cfg.incrementSoftFailures();
        cfg.incrementSoftFailures();
        cfg.incrementSoftFailures();
        cfg.incrementSoftFailures();
        cfg.incrementSoftFailures();
        cfg.incrementSoftFailures();
        // congestion must not look like test failure
        assertEquals(6, cfg.getSoftFailures());
        assertEquals(0, cfg.getConsecutiveFailures());
        assertFalse(cfg.getTunnelFailed());
    }

    @Test
    public void testClearSoftFailuresResets() {
        TCConfig cfg = newCfg();
        cfg.incrementSoftFailures();
        cfg.incrementSoftFailures();
        cfg.clearSoftFailures();
        assertEquals(0, cfg.getSoftFailures());
    }

    @Test
    public void testRealTrafficClearsSoftFailures() {
        TCConfig cfg = newCfg();
        cfg.incrementSoftFailures();
        cfg.incrementSoftFailures();
        long before = System.currentTimeMillis();
        cfg.recordRealTraffic();
        assertEquals(0, cfg.getSoftFailures());
        // the streak is windowed on System time, so the stamp is wall-clock
        assertTrue(cfg.getLastRealTraffic() >= before);
        assertTrue(cfg.getLastRealTraffic() <= System.currentTimeMillis());
    }

    @Test
    public void testClearTestFailuresClearsSoftFailures() {
        TCConfig cfg = newCfg();
        cfg.incrementSoftFailures();
        cfg.clearTestFailures();
        assertEquals(0, cfg.getSoftFailures());
    }

    @Test
    public void testTestSuccessfulDoesNotClearSoftFailures() {
        // a passing test exercises the test path, not the data path
        TCConfig cfg = newCfg();
        cfg.incrementSoftFailures();
        cfg.incrementSoftFailures();
        cfg.testSuccessful(100);
        assertEquals(2, cfg.getSoftFailures());
        assertEquals(TunnelTestStatus.GOOD, cfg.getTestStatus());
    }

    // ---------- last-chance admission ----------

    @Test
    public void testNotAdmittedByDefault() {
        TCConfig cfg = newCfg();
        assertFalse(cfg.isLastChanceAdmitted(NOW));
        assertEquals(TunnelCreatorConfig.LastChanceReason.NONE, cfg.getLastChanceReason());
        assertEquals(0L, cfg.getLastChanceDeadline());
    }

    @Test
    public void testAdmittedWithinDeadline() {
        TCConfig cfg = newCfg();
        cfg.setExpiration(NOW + 90_000L);
        cfg.admitLastChance(NOW, TunnelCreatorConfig.LastChanceReason.STALE_UNTESTED);
        assertTrue(cfg.isLastChanceAdmitted(NOW));
        // admission runs strictly until expiration
        assertTrue(cfg.isLastChanceAdmitted(NOW + 89_999L));
        assertFalse(cfg.isLastChanceAdmitted(NOW + 90_000L));
        assertEquals(NOW + 90_000L, cfg.getLastChanceDeadline());
        assertEquals(NOW, cfg.getLastChanceAdmission());
    }

    @Test
    public void testAdmissionRevokedAfterDeadline() {
        TCConfig cfg = newCfg();
        cfg.setExpiration(NOW + 90_000L);
        cfg.admitLastChance(NOW, TunnelCreatorConfig.LastChanceReason.STALE_UNTESTED);
        assertFalse(cfg.isLastChanceAdmitted(NOW + 90_001L));
    }

    @Test
    public void testDeadlineSnapshotsExpirationAtAdmission() {
        // extending the expiration later must not extend the admission window
        TCConfig cfg = newCfg();
        cfg.setExpiration(NOW + 90_000L);
        cfg.admitLastChance(NOW, TunnelCreatorConfig.LastChanceReason.STALE_UNTESTED);
        cfg.setExpiration(NOW + 300_000L);
        assertFalse(cfg.isLastChanceAdmitted(NOW + 100_000L));
    }

    @Test
    public void testAdmissionNeverOutlivesExpiration() {
        TCConfig cfg = newCfg();
        cfg.setExpiration(NOW + 30_000L);
        cfg.admitLastChance(NOW, TunnelCreatorConfig.LastChanceReason.STALE_UNTESTED);
        // expiration shortened below the snapshot deadline (pruned mid-flight)
        cfg.setExpiration(NOW + 5_000L);
        assertFalse(cfg.isLastChanceAdmitted(NOW + 10_000L));
        assertTrue(cfg.isLastChanceAdmitted(NOW + 4_000L));
    }

    @Test
    public void testClearRevokesAdmission() {
        TCConfig cfg = newCfg();
        cfg.setExpiration(NOW + 90_000L);
        cfg.admitLastChance(NOW, TunnelCreatorConfig.LastChanceReason.STALE_UNTESTED);
        cfg.clearLastChanceAdmission();
        assertFalse(cfg.isLastChanceAdmitted(NOW));
        assertEquals(TunnelCreatorConfig.LastChanceReason.NONE, cfg.getLastChanceReason());
        assertEquals(0L, cfg.getLastChanceDeadline());
        assertEquals(0L, cfg.getLastChanceAdmission());
    }

    @Test
    public void testNullReasonClearsAdmission() {
        TCConfig cfg = newCfg();
        cfg.setExpiration(NOW + 90_000L);
        cfg.admitLastChance(NOW, TunnelCreatorConfig.LastChanceReason.STALE_UNTESTED);
        cfg.admitLastChance(NOW, null);
        assertFalse(cfg.isLastChanceAdmitted(NOW));
    }

    @Test
    public void testTestSuccessfulClearsAdmission() {
        TCConfig cfg = newCfg();
        cfg.setExpiration(NOW + 90_000L);
        cfg.admitLastChance(NOW, TunnelCreatorConfig.LastChanceReason.STALE_UNTESTED);
        cfg.testSuccessful(80);
        assertFalse("settled test revokes the bypass", cfg.isLastChanceAdmitted(NOW));
    }

    @Test
    public void testTestTooSlowRevokesAdmission() {
        TCConfig cfg = newCfg();
        cfg.setExpiration(NOW + 90_000L);
        cfg.admitLastChance(NOW, TunnelCreatorConfig.LastChanceReason.STALE_UNTESTED);
        cfg.setTestTooSlow();
        assertFalse("pruning revokes the bypass", cfg.isLastChanceAdmitted(NOW));
    }

    @Test
    public void testTestOverBudgetRevokesAdmission() {
        TCConfig cfg = newCfg();
        cfg.setExpiration(NOW + 90_000L);
        cfg.admitLastChance(NOW, TunnelCreatorConfig.LastChanceReason.STALE_UNTESTED);
        cfg.setTestOverBudget();
        assertFalse("pruning revokes the bypass", cfg.isLastChanceAdmitted(NOW));
    }
}
