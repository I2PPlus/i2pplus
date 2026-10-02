package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelTestStatus;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 * Tests the pool-state counts attached to the "No tunnels available" warning.
 *
 * <p>The warning used to be the same string whether the pool was empty or merely
 * full of tunnels the pool would not use. Those are opposite problems -- build
 * supply versus eligibility -- and telling them apart needs the counts, not the
 * message.
 *
 * @since 0.9.71+
 */
public class TunnelPoolPoolStateTest {

    private static RouterContext _ctx;
    private static final long MINUTE = 60 * 1000L;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
    }

    private static TunnelPool createPool() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPoolManager mgr = mock(TunnelPoolManager.class);
        TunnelPeerSelector sel = mock(TunnelPeerSelector.class);
        return new TunnelPool(_ctx, mgr, new TunnelPoolSettings(false), sel);
    }

    /** A pooled config with the given remaining life and test status, idle. */
    private static TunnelCreatorConfig config(long remaining, TunnelTestStatus status) {
        TunnelCreatorConfig cfg =
            new PooledTunnelCreatorConfig(_ctx, 3, true, null, createPool());
        cfg.setExpiration(_ctx.clock().now() + remaining);
        try {
            Field f = PooledTunnelCreatorConfig.class.getDeclaredField("_lastActivity");
            f.setAccessible(true);
            f.setLong(cfg, _ctx.clock().now() - 10 * MINUTE);
        } catch (Exception e) {
            throw new IllegalStateException("cannot age last activity", e);
        }
        if (status == TunnelTestStatus.GOOD) {
            cfg.clearTestFailures();
        } else if (status != TunnelTestStatus.UNTESTED) {
            try {
                Field f = TunnelCreatorConfig.class.getDeclaredField("_testStatus");
                f.setAccessible(true);
                f.set(cfg, status);
            } catch (Exception e) {
                throw new IllegalStateException("cannot set test status", e);
            }
        }
        return cfg;
    }

    private static List<TunnelInfo> list(TunnelCreatorConfig... cfgs) {
        List<TunnelInfo> rv = new ArrayList<>();
        for (TunnelCreatorConfig cfg : cfgs) {
            rv.add(cfg);
        }
        return rv;
    }

    private static Predicate<TunnelInfo> neverBacklogged() {
        return info -> false;
    }

    // ---- the case the old warning could not express ----

    /**
     *  A full pool with nothing selectable in it. This is the diagnostic that
     *  matters: it is distinguishable from empty only because the counts are
     *  reported.
     */
    @Test
    public void fullPoolWithNothingUsableIsDistinguishableFromEmpty() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        long now = _ctx.clock().now();
        List<TunnelInfo> full = list(config(-1 * MINUTE, TunnelTestStatus.GOOD),
                                     config(9 * MINUTE, TunnelTestStatus.FAILED));
        TunnelPool.PoolState state =
            TunnelPool.classifyPoolState(full, now, neverBacklogged());
        assertEquals("a non-empty pool must not report as empty", 2, state.pooled);
        assertEquals(0, state.usable);
    }

    /**
     *  "Usable" here means selectable for a send, NOT lease-worthy: the gates use
     *  plain expiry, so a tunnel with seconds left still counts. Anyone reading
     *  these counts must not mistake them for lease adequacy, which is
     *  {@code countLeaseViableTunnels} and the lease admission floor.
     */
    @Test
    public void nearExpiryTunnelStillCountsAsSelectable() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool.PoolState state = TunnelPool.classifyPoolState(
            list(config(20 * 1000L, TunnelTestStatus.GOOD)),
            _ctx.clock().now(), neverBacklogged());
        assertEquals("selectability is expiry-based, not lease-based", 1, state.usable);
    }

    @Test
    public void emptyPoolReportsZeroEverywhere() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool.PoolState state =
            TunnelPool.classifyPoolState(new ArrayList<>(), _ctx.clock().now(), neverBacklogged());
        assertEquals(0, state.pooled);
        assertEquals(0, state.usable);
        assertEquals(0, state.lastResort);
        assertEquals(0, state.backlogged);
    }

    /** The constant used by the empty-pool call site agrees with a real scan. */
    @Test
    public void emptyConstantMatchesAnEmptyScan() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        assertEquals(TunnelPool.PoolState.EMPTY.toString(),
                     TunnelPool.classifyPoolState(new ArrayList<>(), _ctx.clock().now(),
                                                  neverBacklogged()).toString());
    }

    /** A null tunnel list is treated as empty rather than throwing. */
    @Test
    public void nullListIsTolerated() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        assertEquals(0, TunnelPool.classifyPoolState(null, _ctx.clock().now(),
                                                    neverBacklogged()).pooled);
    }

    // ---- classification ----

    /** Fresh, viable, idle tunnels are usable. */
    @Test
    public void viableTunnelsCountAsUsable() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool.PoolState state = TunnelPool.classifyPoolState(
            list(config(9 * MINUTE, TunnelTestStatus.GOOD),
                 config(8 * MINUTE, TunnelTestStatus.GOOD)),
            _ctx.clock().now(), neverBacklogged());
        assertEquals(2, state.pooled);
        assertEquals(2, state.usable);
    }

    /** Expired tunnels are in the pool but not usable. */
    @Test
    public void expiredTunnelsAreNotUsable() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool.PoolState state = TunnelPool.classifyPoolState(
            list(config(-1 * MINUTE, TunnelTestStatus.GOOD)),
            _ctx.clock().now(), neverBacklogged());
        assertEquals(1, state.pooled);
        assertEquals(0, state.usable);
    }

    /** A FAILED tunnel is unusable however fresh it is. */
    @Test
    public void failedTunnelsAreNotUsable() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool.PoolState state = TunnelPool.classifyPoolState(
            list(config(9 * MINUTE, TunnelTestStatus.FAILED)),
            _ctx.clock().now(), neverBacklogged());
        assertEquals(1, state.pooled);
        assertEquals(0, state.usable);
    }

    /** UNTESTED tunnels clear the gates; the pool may still promote them. */
    @Test
    public void untestedTunnelsClearTheGates() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool.PoolState state = TunnelPool.classifyPoolState(
            list(config(9 * MINUTE, TunnelTestStatus.UNTESTED)),
            _ctx.clock().now(), neverBacklogged());
        assertEquals(1, state.usable);
    }

    /**
     *  Last-resort tunnels are reported in their own bucket, not as usable. They
     *  clear every gate, so without the isLastResort() branch they would be
     *  counted as usable and this case would be invisible.
     */
    @Test
    public void lastResortTunnelsGetTheirOwnBucket() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelCreatorConfig held = config(9 * MINUTE, TunnelTestStatus.GOOD);
        ((PooledTunnelCreatorConfig) held).setLastResort();
        TunnelPool.PoolState state = TunnelPool.classifyPoolState(
            list(held), _ctx.clock().now(), neverBacklogged());
        assertEquals(1, state.pooled);
        assertEquals(1, state.lastResort);
        assertEquals("a held-back tunnel is not offered for a new send", 0, state.usable);
    }

    /** Without the flag the very same tunnel is usable, pinning the branch. */
    @Test
    public void unflaggedTunnelIsUsableNotLastResort() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool.PoolState state = TunnelPool.classifyPoolState(
            list(config(9 * MINUTE, TunnelTestStatus.GOOD)),
            _ctx.clock().now(), neverBacklogged());
        assertEquals(1, state.usable);
        assertEquals(0, state.lastResort);
    }

    /** A backlogged next peer moves a tunnel from usable to backlogged. */
    @Test
    public void backloggedTunnelsAreCountedSeparately() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool.PoolState state = TunnelPool.classifyPoolState(
            list(config(9 * MINUTE, TunnelTestStatus.GOOD),
                 config(8 * MINUTE, TunnelTestStatus.GOOD)),
            _ctx.clock().now(), info -> true);
        assertEquals(2, state.pooled);
        assertEquals("backlogged tunnels are not usable for a new send",
                     0, state.usable);
        assertEquals(2, state.backlogged);
    }

    /** A null predicate means no backlog filter, so everything usable counts. */
    @Test
    public void nullPredicateSkipsTheBacklogCheck() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool.PoolState state = TunnelPool.classifyPoolState(
            list(config(9 * MINUTE, TunnelTestStatus.GOOD)),
            _ctx.clock().now(), null);
        assertEquals(1, state.usable);
        assertEquals(0, state.backlogged);
    }

    /** The rendering must name every bucket, since that is the whole point. */
    @Test
    public void renderingNamesEveryBucket() {
        String s = new TunnelPool.PoolState(3, 1, 1, 1).toString();
        assertTrue(s, s.contains("pool=3"));
        assertTrue(s, s.contains("usable=1"));
        assertTrue(s, s.contains("lastResort=1"));
        assertTrue(s, s.contains("backlogged=1"));
    }
}
