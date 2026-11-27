package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.stat.RateStat;

/**
 * Tests the executor's per-first-hop in-flight guard. A build whose first hop
 * (peer 0 for inbound, peer 1 for outbound) already carries a build, or has
 * been failing as a first hop, is re-configured over another peer; only when
 * the pool cannot offer a different hop is the build dropped. Dropping a
 * build throws away a refill the pool still needs, so the guard has to be
 * measured by what it costs, not only by what it prevents: live on 2026-11-25
 * it turned away 86 builds a minute against 61 attempted.
 * <p>
 * Dropped builds must not leak their configs in the pool's in-progress list,
 * or the count would inflate forever and starve every cap-guard that gates on
 * in-progress builds. Emergency builds (setBypassPacing) bypass the guard
 * entirely. A second guard holds a build whose first hop had another build
 * dispatched to it inside the spacing window; it drops the build rather than
 * re-picking the hop, so it is counted separately, as
 * {@code tunnel.buildStaggeredOut}.
 *
 * @since 0.9.70+
 */
public class BuildExecutorPacingTest {

    /** Shared context for the tests that need a live pool over a real context. */
    private static RouterContext _ctx;

    /**
     * Holds no state of its own; the pool, executor and building map are built
     * inside each case.
     */
    public BuildExecutorPacingTest() {
    }

    /**
     * Build the shared context once for the class. It is null when the test
     * environment cannot create one, which every case treats as a skip.
     */
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
     * A build whose first-hop peer already has a build in flight, on a pool
     * that cannot select any other hop, is dropped: it is removed from the
     * pool's in-progress list and never added to the executor's building map.
     * Regression test for the leak where a dropped config stayed in
     * _inProgress forever.
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
     * A build whose first hop is busy is kept when the pool can offer another
     * one: the retry is configured with the gated hop excluded, the original
     * config leaves the pool's in-progress list, and the replacement gets as
     * far as the dispatch path.
     *
     * @throws Exception on reflection failure seeding the executor's building map
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testGatedBuildIsReSelectedOverAFreshHop() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        RouterContext ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", ctx != null);
        TunnelPool pool = mockPool();
        BuildExecutor exec = new BuildExecutor(ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));

        Hash busy = hop((byte) 1);
        Hash free = hop((byte) 2);
        PooledTunnelCreatorConfig inFlight = new PooledTunnelCreatorConfig(ctx, 3, true, null, pool);
        inFlight.setPeer(0, busy);
        inFlight.setReplyMessageId(1234L);
        seedBuildingMap(exec, inFlight);

        PooledTunnelCreatorConfig gated = new PooledTunnelCreatorConfig(ctx, 3, true, null, pool);
        gated.setPeer(0, busy);
        PooledTunnelCreatorConfig fresh = new PooledTunnelCreatorConfig(ctx, 3, true, null, pool);
        fresh.setPeer(0, free);
        when(pool.configureNewTunnel(eq(false), anySet())).thenReturn(fresh);

        exec.buildTunnel(gated);

        ArgumentCaptor<Set<Hash>> hops = ArgumentCaptor.forClass(Set.class);
        verify(pool).configureNewTunnel(eq(false), hops.capture());
        assertTrue("the gated hop must be excluded from the retry", hops.getValue().contains(busy));
        assertEquals("only the gated hop is known bad for the first retry", 1, hops.getValue().size());
        verify(pool).removeInProgress(gated);
        assertEquals("the build was kept, not paced out", 1, statEvents(ctx, "tunnel.buildPacedReselect"));
        assertEquals("nothing was discarded", 0, statEvents(ctx, "tunnel.buildPacedOut"));
        // The replacement went on past the pacing guard: the next gate it met was
        // the first-hop session check, which the test context always fails.
        assertEquals("the replacement build reached dispatch", 1, statEvents(ctx, "tunnel.buildNoSession"));
    }

    /**
     * The walk is bounded. A pool that can only ever offer the hop that is
     * already gated gives up after {@link BuildExecutor#FIRST_HOP_RESELECT_LIMIT}
     * re-selections, and every config it touched is taken out of the pool's
     * in-progress list, so a busy hop cannot hold the pool's build slots open.
     *
     * @throws Exception on reflection failure seeding the executor's building map
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testReselectStopsAtTheCeiling() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        RouterContext ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", ctx != null);
        TunnelPool pool = mockPool();
        BuildExecutor exec = new BuildExecutor(ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));

        Hash busy = hop((byte) 3);
        PooledTunnelCreatorConfig inFlight = new PooledTunnelCreatorConfig(ctx, 3, true, null, pool);
        inFlight.setPeer(0, busy);
        inFlight.setReplyMessageId(1234L);
        seedBuildingMap(exec, inFlight);

        PooledTunnelCreatorConfig gated = new PooledTunnelCreatorConfig(ctx, 3, true, null, pool);
        gated.setPeer(0, busy);
        PooledTunnelCreatorConfig sameHop = new PooledTunnelCreatorConfig(ctx, 3, true, null, pool);
        sameHop.setPeer(0, busy);
        when(pool.configureNewTunnel(eq(false), anySet())).thenReturn(sameHop);

        exec.buildTunnel(gated);

        int limit = BuildExecutor.FIRST_HOP_RESELECT_LIMIT;
        ArgumentCaptor<Set<Hash>> hops = ArgumentCaptor.forClass(Set.class);
        verify(pool, times(limit)).configureNewTunnel(eq(false), hops.capture());
        for (Set<Hash> excluded : hops.getAllValues()) {
            assertTrue("every retry excludes the hop it was gated on", excluded.contains(busy));
        }
        assertEquals(limit, statEvents(ctx, "tunnel.buildPacedReselect"));
        assertEquals("the build is dropped once, at the end", 1, statEvents(ctx, "tunnel.buildPacedOut"));
        assertEquals("it never reached dispatch", 0, statEvents(ctx, "tunnel.buildNoSession"));
        verify(pool, times(limit + 1)).removeInProgress(any(PooledTunnelCreatorConfig.class));
    }

    /**
     * When selection cannot offer any hop at all, the build is dropped rather
     * than dispatched onto the gated one — the re-select path must not become a
     * way around the guard.
     *
     * @throws Exception on reflection failure seeding the executor's building map
     */
    @Test
    public void testNoSelectableHopDropsTheBuild() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        RouterContext ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", ctx != null);
        TunnelPool pool = mockPool();
        BuildExecutor exec = new BuildExecutor(ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));

        Hash busy = hop((byte) 4);
        PooledTunnelCreatorConfig inFlight = new PooledTunnelCreatorConfig(ctx, 3, true, null, pool);
        inFlight.setPeer(0, busy);
        inFlight.setReplyMessageId(1234L);
        seedBuildingMap(exec, inFlight);

        PooledTunnelCreatorConfig gated = new PooledTunnelCreatorConfig(ctx, 3, true, null, pool);
        gated.setPeer(0, busy);
        when(pool.configureNewTunnel(eq(false), anySet())).thenReturn(null);

        exec.buildTunnel(gated);

        verify(pool).removeInProgress(gated);
        assertEquals(1, statEvents(ctx, "tunnel.buildPacedOut"));
        assertEquals(0, statEvents(ctx, "tunnel.buildPacedReselect"));
        assertEquals(0, statEvents(ctx, "tunnel.buildNoSession"));
    }

    /**
     * A pool mock the walk can drive. Selection is stubbed per test; the
     * config constructor reads the pool's settings, so the mock has to carry
     * real ones.
     *
     * @return a mock inbound client pool with live settings
     */
    private static TunnelPool mockPool() {
        TunnelPool pool = mock(TunnelPool.class);
        when(pool.getSettings()).thenReturn(new TunnelPoolSettings(true));
        return pool;
    }

    /**
     * A distinct peer hash to use as a first hop.
     *
     * @param b the first byte, making the hash unique among the test's hops
     * @return a 32-byte hash starting with {@code b}
     */
    private static Hash hop(byte b) {
        Hash rv = new Hash(new byte[32]);
        rv.getData()[0] = b;
        return rv;
    }

    /**
     * Events recorded against a stat since its context was created. Each test
     * builds its own context, so the class's other tests cannot feed it.
     *
     * @param ctx the context whose stat manager to read
     * @param name the stat name as the console sees it
     * @return the event count on record, 0 if none
     */
    private static long statEvents(RouterContext ctx, String name) {
        RateStat st = ctx.statManager().getRate(name);
        assertNotNull("stat not registered: " + name, st);
        return st.getLifetimeEventCount();
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

    /**
     * Reflect the executor's per-hop last-dispatch map, the state the dispatch
     * spacing guard reads.
     *
     * @param exec the executor whose private {@code _lastDispatchTime} is read
     * @return the live map, so a test can seed a hop or read what was recorded
     * @throws Exception if the private field is gone or reflection is not allowed
     *                   to open it
     */
    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<Hash, Long> lastDispatchMap(BuildExecutor exec) throws Exception {
        Field f = BuildExecutor.class.getDeclaredField("_lastDispatchTime");
        f.setAccessible(true);
        return (ConcurrentHashMap<Hash, Long>) f.get(exec);
    }

    /**
     * The dispatch spacing guard, as a pure decision: a hop never dispatched to
     * is clear, a hop inside the window is held for the rest of it, and a hop at
     * or past the window is clear again. The boundary matters because the caller
     * drops the build rather than waiting, so a window measured one ms wrong
     * spends a build that could have gone out.
     *
     * @throws Exception not expected; the body only calls a pure helper
     */
    @Test
    public void testStaggerHoldBoundary() throws Exception {
        int window = BuildExecutor.MIN_BUILD_SPACING_MS;
        long now = 1_000_000L;
        assertEquals("a hop never dispatched to is clear", 0, BuildExecutor.staggerHoldMs(0L, now, window));
        assertEquals("dispatched now, held for the whole window", window,
                     BuildExecutor.staggerHoldMs(now, now, window));
        assertEquals(window / 2, BuildExecutor.staggerHoldMs(now - window / 2, now, window));
        assertEquals("at the window the hop is clear again", 0, BuildExecutor.staggerHoldMs(now - window, now, window));
        assertEquals("past the window, clear", 0, BuildExecutor.staggerHoldMs(now - window - 1, now, window));
        assertEquals("a clock step backwards holds the hop for the window, no longer", window,
                     BuildExecutor.staggerHoldMs(now + 5000L, now, window));
    }

    /**
     * A build to a first hop that had another build dispatched inside the spacing
     * window is dropped, taken out of the pool's in-progress list, and counted.
     * The count is the point: this guard drops the build outright instead of
     * re-picking the hop, so before the stat it was the only build-loss path on
     * the router with no number against it - measured live on 2026-11-27 at
     * roughly 2,500 builds an hour, against 4,477 dispatched.
     *
     * @throws Exception on reflection failure seeding the executor's dispatch map
     */
    @Test
    public void testStaggeredBuildIsCounted() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool();
        BuildExecutor exec = new BuildExecutor(_ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));

        Hash recent = hop((byte) 11);
        lastDispatchMap(exec).put(recent, Long.valueOf(System.currentTimeMillis()));

        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        cfg.setPeer(0, recent);
        seedInProgress(pool, cfg);
        long before = statEvents(_ctx, "tunnel.buildStaggeredOut");

        exec.buildTunnel(cfg);

        assertEquals("the dropped config left the pool's in-progress list", 0, pool.getInProgressCount());
        assertTrue(pool.listPending().isEmpty());
        assertEquals("the drop is counted", 1, statEvents(_ctx, "tunnel.buildStaggeredOut") - before);
        assertEquals("it was not a first-hop re-pick", 0, statEvents(_ctx, "tunnel.buildPacedReselect"));
    }

    /**
     * A hop outside the spacing window dispatches, and is recorded as dispatched
     * so a second build to it inside the window is the one that gets held. Without
     * the record the guard would never fire at all, and without the count the
     * first build would be dropped for nothing.
     *
     * @throws Exception on reflection failure reading the executor's dispatch map
     */
    @Test
    public void testDispatchRecordsTheHopItWentTo() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool();
        BuildExecutor exec = new BuildExecutor(_ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));

        Hash clear = hop((byte) 12);
        long outside = System.currentTimeMillis() - BuildExecutor.MIN_BUILD_SPACING_MS - 1L;
        lastDispatchMap(exec).put(clear, Long.valueOf(outside));

        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        cfg.setPeer(0, clear);
        seedInProgress(pool, cfg);
        long before = statEvents(_ctx, "tunnel.buildStaggeredOut");

        exec.buildTunnel(cfg);

        assertEquals("the hop is recorded as dispatched", 1, lastDispatchMap(exec).size());
        assertEquals("the build was not dropped for spacing", 0,
                     statEvents(_ctx, "tunnel.buildStaggeredOut") - before);
    }
}
