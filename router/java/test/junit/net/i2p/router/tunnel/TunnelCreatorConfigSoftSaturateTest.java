package net.i2p.router.tunnel;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

/**
 * Tests the saturation ceiling on the soft best-effort streak,
 * {@link TunnelCreatorConfig#MAX_SOFT_FAILURES}.
 *
 * <p>The streak exists only to cross the soft removal bar. An unbounded counter
 * on a retained tunnel reported values in the thousands for what was one
 * congestion condition counted once per timeout, which reads as a count of
 * independent failures and misleads anyone triaging from the log.
 *
 * @since 0.9.71+
 */
public class TunnelCreatorConfigSoftSaturateTest {

    private static final long NOW = 1_000_000_000L;

    private RouterContext _ctx;
    private File _tmpDir;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"),
                           "i2p-tccfg-sat-" + System.nanoTime());
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

    @Test
    public void streakSaturatesAtTheCeiling() {
        TCConfig cfg = newCfg();
        for (int i = 0; i < 5000; i++) {
            cfg.incrementSoftFailures();
        }
        assertEquals(TunnelCreatorConfig.MAX_SOFT_FAILURES, cfg.getSoftFailures());
    }

    @Test
    public void saturationIsWellAboveTheRemovalBar() {
        // The ceiling must never be the thing that decides removal, or a
        // saturated streak would be indistinguishable from a real one.
        assertTrue("ceiling must exceed the soft removal bar",
                   TunnelCreatorConfig.MAX_SOFT_FAILURES
                       > TunnelPool.softRemovalThreshold());
    }

    @Test
    public void countBelowTheCeilingIsExact() {
        TCConfig cfg = newCfg();
        for (int i = 0; i < 9; i++) {
            cfg.incrementSoftFailures();
        }
        assertEquals(9, cfg.getSoftFailures());
    }

    @Test
    public void saturationStillCrossesTheRemovalBar() {
        TCConfig cfg = newCfg();
        for (int i = 0; i < 5000; i++) {
            cfg.incrementSoftFailures();
        }
        assertTrue("a saturated streak must still condemn",
                   TunnelPool.exceedsSoftRemovalThreshold(cfg.getSoftFailures()));
    }

    @Test
    public void trafficStillClearsASaturatedStreak() {
        TCConfig cfg = newCfg();
        for (int i = 0; i < 5000; i++) {
            cfg.incrementSoftFailures();
        }
        assertEquals(TunnelCreatorConfig.MAX_SOFT_FAILURES, cfg.getSoftFailures());
        cfg.recordRealTraffic();
        assertEquals("verified traffic must clear the streak regardless of ceiling",
                     0, cfg.getSoftFailures());
    }

    @Test
    public void saturationDoesNotTripTheHardCounter() {
        TCConfig cfg = newCfg();
        for (int i = 0; i < 5000; i++) {
            cfg.incrementSoftFailures();
        }
        assertEquals(0, cfg.getConsecutiveFailures());
        assertFalse(cfg.getTunnelFailed());
    }
}
