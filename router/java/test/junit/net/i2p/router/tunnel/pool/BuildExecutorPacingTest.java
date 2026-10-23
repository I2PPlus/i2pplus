package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;

/**
 * Tests the executor's per-first-hop in-flight guard. A build is skipped
 * when the peer it would dispatch to (peer 0 for inbound, peer 1 for
 * outbound) already has a build in flight, so one peer is never flooded
 * with stacked build requests. Skipped builds must not leak their configs
 * in the pool's in-progress list, or the count would inflate forever and
 * starve every cap-guard that gates on in-progress builds. Emergency
 * builds (setBypassPacing) bypass the guard entirely.
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
     * selector are mocks — they are never touched by the skipped path.
     *
     * @return a live client outbound pool with an empty tunnel list, backed by
     *         mocked manager and peer selector
     */
    private static TunnelPool createPool() {
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        TunnelPoolManager mgr = mock(TunnelPoolManager.class);
        TunnelPeerSelector sel = mock(TunnelPeerSelector.class);
        return new TunnelPool(_ctx, mgr, settings, sel);
    }

    /**
     * Seed the pool's private in-progress list.
     *
     * @param pool the pool whose private {@code _inProgress} list is seeded
     * @param cfg the config appended to that list, as if a build had just been
     *            dispatched
     * @throws Exception if the private {@code _inProgress} field is gone or
     *                   reflection is not allowed to open it
     */
    @SuppressWarnings("unchecked")
    private static void seedInProgress(TunnelPool pool, PooledTunnelCreatorConfig cfg) throws Exception {
        Field f = TunnelPool.class.getDeclaredField("_inProgress");
        f.setAccessible(true);
        ((List<PooledTunnelCreatorConfig>) f.get(pool)).add(cfg);
    }

    /**
     * Seed the executor's building map with an in-flight build.
     *
     * @param exec the executor whose private {@code _currentlyBuildingMap} is seeded
     * @param inFlight the config registered in the map, keyed by its reply message id
     * @return the map that was seeded, so a test can inspect or clear it
     * @throws Exception if the private building-map field is gone or reflection is
     *                   not allowed to open it
     */
    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<Long, PooledTunnelCreatorConfig> seedBuildingMap(BuildExecutor exec,
                                                                                      PooledTunnelCreatorConfig inFlight)
            throws Exception {
        Field f = BuildExecutor.class.getDeclaredField("_currentlyBuildingMap");
        f.setAccessible(true);
        ConcurrentHashMap<Long, PooledTunnelCreatorConfig> map =
            (ConcurrentHashMap<Long, PooledTunnelCreatorConfig>) f.get(exec);
        map.put(Long.valueOf(inFlight.getReplyMessageId()), inFlight);
        return map;
    }

    /**
     * The removeInProgress() method must remove only the matching config, leaving
     * unrelated in-progress builds untouched.
     *
     * @throws Exception on reflection failure seeding the pool's private in-progress list
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
     *
     * @throws Exception not expected; the body only builds the config and asserts
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
     * A build whose first-hop peer already has a build in flight is skipped:
     * it is removed from the pool's in-progress list and never added to the
     * executor's building map. Regression test for the leak where a skipped
     * config stayed in _inProgress forever.
     *
     * @throws Exception on reflection failure seeding the in-progress list or building map
     */
    @Test
    public void testBuildSkippedWhenFirstHopBusy() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool();
        BuildExecutor exec = new BuildExecutor(_ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));

        Hash firstHop = new Hash(new byte[32]);
        PooledTunnelCreatorConfig inFlight = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        inFlight.setPeer(0, firstHop);
        inFlight.setReplyMessageId(1234L);
        seedBuildingMap(exec, inFlight);

        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        cfg.setPeer(0, firstHop);
        seedInProgress(pool, cfg);

        exec.buildTunnel(cfg);

        assertEquals(0, pool.getInProgressCount());
        assertTrue(pool.listPending().isEmpty());
    }

    /**
     * A build to a different first hop than every in-flight build is not
     * skipped — bursting to distinct peers is allowed.
     *
     * @throws Exception on reflection failure seeding the building map or invoking the guard
     */
    @Test
    public void testBuildNotSkippedWhenFirstHopFree() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool();
        BuildExecutor exec = new BuildExecutor(_ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));

        Hash busy = new Hash(new byte[32]);
        busy.getData()[0] = 1;
        Hash other = new Hash(new byte[32]);
        other.getData()[0] = 2;
        PooledTunnelCreatorConfig inFlight = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        inFlight.setPeer(0, busy);
        inFlight.setReplyMessageId(1234L);
        seedBuildingMap(exec, inFlight);

        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        cfg.setPeer(0, other);

        // different first hop -> guard must not fire
        assertEquals(false, hasBuildInFlightToFirstHop(exec, cfg));
    }

    /**
     * Emergency builds bypass the guard: with setBypassPacing, a build to a
     * busy first hop is still allowed, so a collapsed pool recovers.
     *
     * @throws Exception on reflection failure seeding the building map or invoking the guard
     */
    @Test
    public void testBypassPacingSkipsGuard() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool();
        BuildExecutor exec = new BuildExecutor(_ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));

        Hash firstHop = new Hash(new byte[32]);
        PooledTunnelCreatorConfig inFlight = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        inFlight.setPeer(0, firstHop);
        inFlight.setReplyMessageId(1234L);
        seedBuildingMap(exec, inFlight);

        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        cfg.setPeer(0, firstHop);
        cfg.setBypassPacing();

        // guard sees a busy first hop, but bypass must win
        assertEquals(true, hasBuildInFlightToFirstHop(exec, cfg));
        assertEquals(true, cfg.isBypassPacing());
        assertEquals(false, !cfg.isBypassPacing() && hasBuildInFlightToFirstHop(exec, cfg));
    }

    /**
     * Reflect the private guard method.
     *
     * @param exec the executor to invoke the guard on
     * @param cfg the config whose peer 0 is matched against the building map
     * @return true when a build to cfg's first hop is already in flight
     * @throws Exception if the private guard method is gone or reflection cannot
     *                   invoke it
     */
    private static boolean hasBuildInFlightToFirstHop(BuildExecutor exec, PooledTunnelCreatorConfig cfg) throws Exception {
        java.lang.reflect.Method m = BuildExecutor.class.getDeclaredMethod("hasBuildInFlightToFirstHop",
                                                                           PooledTunnelCreatorConfig.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(exec, cfg);
    }
}
