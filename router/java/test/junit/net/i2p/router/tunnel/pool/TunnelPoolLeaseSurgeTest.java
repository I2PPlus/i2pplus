package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelTestStatus;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 * Tests the lease surge: the temporary raising of a pool's target from
 * "enough tunnels" to "enough tunnels that can still be advertised", which is
 * active only while a LeaseSet mint is imminent.
 *
 * <p>The gap this closes: {@code pruneExcessTunnels} only prunes once a pool
 * exceeds its active target and skips anything recently active, so a pool can
 * sit at full size holding nothing advertiseable. Nothing tied build demand to
 * that before, and a re-mint with no viable leases is a LeaseSet that expires
 * long before its successor propagates.
 *
 * @since 0.9.71+
 */
public class TunnelPoolLeaseSurgeTest {

    private static RouterContext _ctx;
    private static final long MINUTE = 60 * 1000L;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
    }

    private static TunnelPool createPool(int quantity) {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPoolManager mgr = mock(TunnelPoolManager.class);
        TunnelPeerSelector sel = mock(TunnelPeerSelector.class);
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        settings.setQuantity(quantity);
        return new TunnelPool(_ctx, mgr, settings, sel);
    }

    /**
     *  A pooled config with the given remaining life and test status. Idle by
     *  default: a freshly constructed config reports itself recently active,
     *  which would mask every prune decision, so the last-activity stamp is
     *  aged out unless the test asks otherwise.
     */
    private static TunnelCreatorConfig config(long remaining, TunnelTestStatus status) {
        return config(remaining, status, true);
    }

    /** As {@link #config(long, TunnelTestStatus)}, controlling recency. */
    private static TunnelCreatorConfig config(long remaining, TunnelTestStatus status,
                                              boolean idle) {
        TunnelPool pool = createPool(3);
        PooledTunnelCreatorConfig cfg =
            new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        cfg.setExpiration(_ctx.clock().now() + remaining);
        if (idle) {
            try {
                Field f = PooledTunnelCreatorConfig.class.getDeclaredField("_lastActivity");
                f.setAccessible(true);
                f.setLong(cfg, _ctx.clock().now() - 10 * MINUTE);
            } catch (Exception e) {
                throw new IllegalStateException("cannot age last activity", e);
            }
        } else {
            cfg.recordActivity();
        }
        if (status == TunnelTestStatus.GOOD) {
            cfg.clearTestFailures();
        } else if (status != TunnelTestStatus.UNTESTED) {
            // no public setter; write the field the way the test cycle does
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

    // ---- the target itself ----

    /** With no surge the pool keeps its ordinary active-count target. */
    @Test
    public void noSurgeLeavesTargetUnchanged() {
        assertEquals(3, createPool(3).getEffectiveTarget());
    }

    /** A surge raises the target to the advertiseable count a mint needs. */
    @Test
    public void surgeRaisesTarget() {
        TunnelPool pool = createPool(2);
        pool.requestLeaseSurge(4);
        assertEquals(4, pool.getEffectiveTarget());
    }

    /** Releasing returns the pool to plain active-count upkeep. */
    @Test
    public void clearingSurgeRestoresTarget() {
        TunnelPool pool = createPool(2);
        pool.requestLeaseSurge(5);
        pool.clearLeaseSurge();
        assertEquals(2, pool.getEffectiveTarget());
    }

    /**
     *  A surge can only ever raise the target. A later, lower request must not
     *  walk it back down mid-cycle, or a mint asking for 2 after one asked for 5
     *  would silently shrink the target while the first is still waiting.
     */
    @Test
    public void surgeOnlyEverRises() {
        TunnelPool pool = createPool(2);
        pool.requestLeaseSurge(5);
        pool.requestLeaseSurge(3);
        assertEquals(5, pool.getEffectiveTarget());
    }

    /** A non-positive request is meaningless and must not be honoured. */
    @Test
    public void nonPositiveSurgeIgnored() {
        TunnelPool pool = createPool(3);
        pool.requestLeaseSurge(0);
        pool.requestLeaseSurge(-1);
        assertEquals(3, pool.getEffectiveTarget());
    }

    /** The surge never lowers a target below the pool's own configured floor. */
    @Test
    public void surgeCannotGoBelowActiveTarget() {
        TunnelPool pool = createPool(4);
        pool.requestLeaseSurge(1);
        assertEquals(4, pool.getEffectiveTarget());
    }

    // ---- which tunnels the surge may give up ----

    private static List<TunnelInfo> list(TunnelCreatorConfig... cfgs) {
        List<TunnelInfo> rv = new ArrayList<>();
        for (TunnelCreatorConfig cfg : cfgs) {
            rv.add(cfg);
        }
        return rv;
    }

    /**
     *  The safety-critical decision. Only tunnels that could never be advertised
     *  are candidates: a tunnel well inside the lease admission floor is doing
     *  useful work, and giving up an advertiseable tunnel to chase a fresher one
     *  would trade LeaseSet lifetime for capacity.
     */
    @Test
    public void viableTunnelsAreNeverPruned() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        List<TunnelInfo> tunnels = list(config(9 * MINUTE, TunnelTestStatus.GOOD));
        assertTrue("an advertiseable tunnel must never be pruned",
                   TunnelPool.leaseSurgeCandidates(tunnels, new ArrayList<>(),
                                                   _ctx.clock().now()).isEmpty());
    }

    /** A GOOD tunnel past the floor is prunable: it can no longer be leased. */
    @Test
    public void unviableIdleGoodTunnelIsPrunable() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelCreatorConfig dying = config(30 * 1000L, TunnelTestStatus.GOOD);
        List<TunnelInfo> tunnels = list(dying);
        List<TunnelInfo> prunable =
            TunnelPool.leaseSurgeCandidates(tunnels, new ArrayList<>(), _ctx.clock().now());
        assertEquals(1, prunable.size());
        assertEquals(dying, prunable.get(0));
    }

    /**
     *  The pool keeps unviable and advertiseable tunnels together; only the
     *  unviable ones are offered up. This is the case the surge exists for.
     */
    @Test
    public void onlyUnviableTunnelsAreOffered() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelCreatorConfig viable = config(9 * MINUTE, TunnelTestStatus.GOOD);
        TunnelCreatorConfig dying = config(20 * 1000L, TunnelTestStatus.GOOD);
        List<TunnelInfo> prunable = TunnelPool.leaseSurgeCandidates(
            list(viable, dying), new ArrayList<>(), _ctx.clock().now());
        assertEquals(1, prunable.size());
        assertEquals(dying, prunable.get(0));
    }

    /** Untested tunnels are not ours to prune; the pool may still promote them. */
    @Test
    public void untestedTunnelsAreNeverPruned() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        List<TunnelInfo> tunnels = list(config(20 * 1000L, TunnelTestStatus.UNTESTED));
        assertTrue("an UNTESTED tunnel is not prunable by a lease surge",
                   TunnelPool.leaseSurgeCandidates(tunnels, new ArrayList<>(),
                                                   _ctx.clock().now()).isEmpty());
    }

    /** A tunnel already scheduled for pruning is not offered twice. */
    @Test
    public void alreadyPrunedTunnelsAreSkipped() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelCreatorConfig dying = config(20 * 1000L, TunnelTestStatus.GOOD);
        List<TunnelInfo> toRemove = new ArrayList<>();
        toRemove.add(dying);
        assertTrue(TunnelPool.leaseSurgeCandidates(list(dying), toRemove,
                                                   _ctx.clock().now()).isEmpty());
    }

    /**
     *  A tunnel carrying traffic is never sacrificed for a fresher one. This is
     *  the counterpart to {@link #unviableIdleGoodTunnelIsPrunable}: same
     *  expiration, same status, but recently active, so it stays.
     */
    @Test
    public void recentlyActiveTunnelsAreNeverPruned() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelCreatorConfig busy = config(20 * 1000L, TunnelTestStatus.GOOD, false);
        assertTrue("a tunnel carrying traffic must not be pruned by a lease surge",
                   TunnelPool.leaseSurgeCandidates(list(busy), new ArrayList<>(),
                                                   _ctx.clock().now()).isEmpty());
    }

    // ---- the advertiseable count the surge measures against ----

    /** Count uses the same bar as the lease admission gate. */
    @Test
    public void viableCountUsesTheLeaseAdmissionBar() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        long now = _ctx.clock().now();
        List<TunnelInfo> tunnels =
            list(config(9 * MINUTE, TunnelTestStatus.GOOD),
                 config(20 * 1000L, TunnelTestStatus.GOOD),
                 config(4 * MINUTE, TunnelTestStatus.GOOD));
        // Two clear the 3-minute admission floor; the 20s one does not.
        assertEquals(2, TunnelPool.countLeaseViableTunnels(tunnels, new ArrayList<>(), now));
    }

    /** An empty or null pool reports nothing viable, which is what triggers a surge. */
    @Test
    public void emptyPoolHasNoViableTunnels() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        assertEquals(0, TunnelPool.countLeaseViableTunnels(
            new ArrayList<>(), new ArrayList<>(), _ctx.clock().now()));
        assertEquals(0, TunnelPool.countLeaseViableTunnels(
            null, new ArrayList<>(), _ctx.clock().now()));
    }

    /** Already-pruned tunnels do not count toward supply either. */
    @Test
    public void prunedTunnelsDoNotCountAsSupply() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelCreatorConfig good = config(9 * MINUTE, TunnelTestStatus.GOOD);
        List<TunnelInfo> toRemove = new ArrayList<>();
        toRemove.add(good);
        assertEquals(0, TunnelPool.countLeaseViableTunnels(
            list(good), toRemove, _ctx.clock().now()));
    }
}
