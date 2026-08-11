package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;

/**
 * Tests that tunnel builds skipped by the executor's pacing gate do not
 * leak their configs in the pool's in-progress list. A leaked config never
 * reaches the timeout sweep (it is never added to the building map), so it
 * would inflate {@link TunnelPool#getInProgressCount()} forever and starve
 * every cap-guard that gates on in-progress builds.
 *
 * @since 0.9.70+
 */
public class BuildExecutorPacingTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
    }

    /**
     * Real pool over a real context; client (non-exploratory) settings so
     * the constructor's config-map refresh is skipped. Manager and peer
     * selector are mocks — they are never touched by the paced-out path.
     */
    private static TunnelPool createPool() {
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        TunnelPoolManager mgr = mock(TunnelPoolManager.class);
        TunnelPeerSelector sel = mock(TunnelPeerSelector.class);
        return new TunnelPool(_ctx, mgr, settings, sel);
    }

    /** Seed the pool's private in-progress list. */
    @SuppressWarnings("unchecked")
    private static void seedInProgress(TunnelPool pool, PooledTunnelCreatorConfig cfg) throws Exception {
        Field f = TunnelPool.class.getDeclaredField("_inProgress");
        f.setAccessible(true);
        ((List<PooledTunnelCreatorConfig>) f.get(pool)).add(cfg);
    }

    /** Force the pace window to be full so the next multi-hop build is paced out. */
    private static void fillPaceWindow(BuildExecutor exec) throws Exception {
        Field window = BuildExecutor.class.getDeclaredField("_buildsInPaceWindow");
        window.setAccessible(true);
        Field start = BuildExecutor.class.getDeclaredField("_paceWindowStart");
        start.setAccessible(true);
        start.setLong(exec, System.currentTimeMillis());
        window.setInt(exec, Integer.MAX_VALUE);
    }

    /**
     * The removeInProgress() method must remove only the matching config, leaving
     * unrelated in-progress builds untouched.
     */
    @Test
    public void testRemoveInProgressRemovesOnlyMatching() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool();
        PooledTunnelCreatorConfig keep = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        PooledTunnelCreatorConfig drop = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        seedInProgress(pool, keep);
        seedInProgress(pool, drop);
        assertEquals(2, pool.getInProgressCount());

        pool.removeInProgress(drop);

        assertEquals(1, pool.getInProgressCount());
        assertTrue(pool.listPending().contains(keep));
        assertFalse(pool.listPending().contains(drop));
    }

    /**
     * Removing a config that is not in the list must be a silent no-op.
     */
    @Test
    public void testRemoveInProgressUnknownIsNoOp() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool();
        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);

        pool.removeInProgress(cfg);

        assertEquals(0, pool.getInProgressCount());
    }

    /**
     * Regression test: a build skipped by the pacing gate must be removed
     * from the pool's in-progress list. Before the fix it stayed there
     * forever — no timeout fires because the config never entered the
     * executor's building map.
     */
    @Test
    public void testPacedOutBuildRemovesFromInProgress() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool();
        BuildExecutor exec = new BuildExecutor(_ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));
        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        seedInProgress(pool, cfg);
        fillPaceWindow(exec);

        exec.buildTunnel(cfg);

        assertEquals(0, pool.getInProgressCount());
        assertTrue(pool.listPending().isEmpty());
    }

    /**
     * A build that clears the pace gate must remain in the in-progress list;
     * only paced-out builds are removed.
     */
    @Test
    public void testPacedOutRemovesOnlyPacedBuild() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool();
        BuildExecutor exec = new BuildExecutor(_ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));
        PooledTunnelCreatorConfig paced = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        PooledTunnelCreatorConfig other = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        seedInProgress(pool, paced);
        seedInProgress(pool, other);
        fillPaceWindow(exec);

        exec.buildTunnel(paced);

        assertEquals(1, pool.getInProgressCount());
        assertTrue(pool.listPending().contains(other));
        assertFalse(pool.listPending().contains(paced));
    }
}
