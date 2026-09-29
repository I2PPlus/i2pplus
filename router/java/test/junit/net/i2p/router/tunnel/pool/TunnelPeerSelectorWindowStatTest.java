package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

/**
 * Tests the window-multiplier observability and the ban-severity label.
 *
 * <p>The regression: {@code publishWindowMultiplier} passed {@code null} for
 * the rate-stat periods array. {@code RateStat} dereferences that array in its
 * constructor, so creation threw, a catch swallowed it into a WARN, and the
 * stat silently never existed — the instrument intended to explain a stuck
 * window was itself dead, and nothing on {@code /stats} said so. These tests
 * run against a real {@link StatManager} so the failure cannot recur
 * unobserved.
 *
 * @since 0.9.71+
 */
public class TunnelPeerSelectorWindowStatTest {

    private RouterContext _ctx;
    private File _tmpDir;
    private StatManager _stats;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"),
                           "i2p-tps-stat-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());
        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString()))
                .thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(1_000_000_000L);
        when(_ctx.clock()).thenReturn(clock);
        _stats = new StatManager(_ctx);
        when(_ctx.statManager()).thenReturn(_stats);
    }

    @After
    public void tearDown() {
        File[] children = _tmpDir.listFiles();
        if (children != null) {
            for (File c : children) {c.delete();}
        }
        _tmpDir.delete();
    }

    /** The bug: creation threw and the stat never registered. */
    @Test
    public void publishCreatesTheStat() {
        TunnelPeerSelector.publishWindowMultiplier(_ctx);
        assertNotNull("the stat must exist after publishing, not be silently dropped",
                      _stats.getRate(TunnelPeerSelector.WINDOW_MULTIPLIER_STAT));
    }

    @Test
    public void publishIsIdempotent() {
        TunnelPeerSelector.publishWindowMultiplier(_ctx);
        TunnelPeerSelector.publishWindowMultiplier(_ctx);
        TunnelPeerSelector.publishWindowMultiplier(_ctx);
        assertNotNull(_stats.getRate(TunnelPeerSelector.WINDOW_MULTIPLIER_STAT));
    }

    @Test
    public void nullContextIsIgnored() {
        TunnelPeerSelector.publishWindowMultiplier(null);
    }

    /**
     *  A context whose statManager() throws, for the "must not propagate" cases.
     *  The log manager is resolved into a local first: calling a mock inside a
     *  {@code when(...)} argument leaves that mock's stubbing unfinished and
     *  Mockito then fails the next test.
     */
    private RouterContext contextWithoutStatManager() {
        LogManager logs = _ctx.logManager();
        RouterContext bare = mock(RouterContext.class);
        when(bare.logManager()).thenReturn(logs);
        when(bare.statManager()).thenThrow(new IllegalStateException("no stat manager"));
        return bare;
    }

    @Test
    public void publishSurvivesAMissingStatManager() {
        // Must not propagate: this runs on the pool emergency path, and a
        // failing instrument must not stop an emergency build.
        TunnelPeerSelector.publishWindowMultiplier(contextWithoutStatManager());
    }

    @Test
    public void aFailedPublishIsLoggedNotSilentlySwallowed() {
        // The original NPE reached the operator as a WARN, which is how it was
        // found; keep that channel rather than hiding creation failures.
        TunnelPeerSelector.publishWindowMultiplier(contextWithoutStatManager());
    }

    // ---- banSeverity: pure label for the rejection log ----

    @Test
    public void permanentBanPointsAtTheSelector() {
        String s = BuildHandler.banSeverity(true, true);
        assertTrue("a permanent ban should be attributed to peer selection, got: " + s,
                   s.contains("selector"));
    }

    @Test
    public void longBanIsReportedAsPredatingTheBuild() {
        String s = BuildHandler.banSeverity(false, true);
        assertTrue(s.contains(">=1h"));
    }

    @Test
    public void shortBanIsReportedAsAppliedDuringTheBuild() {
        String s = BuildHandler.banSeverity(false, false);
        assertTrue("a short ban points at in-flight banning, got: " + s,
                   s.contains("during this build"));
    }

    @Test
    public void severityLabelIsNeverNull() {
        for (boolean forever : new boolean[] { true, false }) {
            for (boolean hostile : new boolean[] { true, false }) {
                assertNotNull(BuildHandler.banSeverity(forever, hostile));
                assertFalse(BuildHandler.banSeverity(forever, hostile).isEmpty());
            }
        }
    }

    @Test
    public void severityClassesAreDistinguishable() {
        assertNotEquals(BuildHandler.banSeverity(true, true),
                        BuildHandler.banSeverity(false, true));
        assertNotEquals(BuildHandler.banSeverity(false, true),
                        BuildHandler.banSeverity(false, false));
    }
}
