package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Assume;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

/**
 * Tests the snapshot that backs TunnelPoolManager.selectPeersInTooManyTunnels().
 * The sweep it caches walks every tunnel of every pool twice and rebuilds two
 * per-peer counters, and every peer-selector construction asks the same
 * question, so the result is reused for a window instead of recomputed.
 *
 * <p>Reusing is a deliberate trade - a peer that crossed the participation
 * share, or fell back below it, inside the window keeps the previous verdict -
 * so the freshness boundary is pinned here rather than assumed, along with the
 * size of the window that a retune would move.
 *
 * @since 0.9.72+
 */
public class TunnelPoolManagerShareCacheTest {

    private static final long T0 = 5_000_000L;

    /**
     * JUnit creates one instance per test method; the manager and its context
     * are built inside each test, so this instance carries no state between
     * them.
     */
    public TunnelPoolManagerShareCacheTest() {
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        return Hash.create(data);
    }

    /**
     * A snapshot is reusable right up to its expiry and not at it: the check
     * is {@code now < expiry}, so the closing instant re-sweeps.
     */
    @Test
    public void snapshotValidUntilExpiryNotAtIt() {
        Set<Hash> peers = Collections.singleton(hash(1));
        long expiry = T0 + TunnelPoolManager.TOO_MANY_TUNNELS_TTL_MS;
        TunnelPoolManager.TooManyTunnelsCache c =
                new TunnelPoolManager.TooManyTunnelsCache(peers, expiry);
        assertTrue(c.isValid(T0));
        assertTrue(c.isValid(expiry - 1));
        assertFalse(c.isValid(expiry));
        assertFalse(c.isValid(expiry + 1));
    }

    /**
     * The snapshot hands out the sweep's result, not an empty set and not a
     * view a caller can write through into the cached copy.
     */
    @Test
    public void snapshotHoldsTheSweepResult() {
        Set<Hash> peers = new HashSet<>();
        peers.add(hash(7));
        peers.add(hash(8));
        TunnelPoolManager.TooManyTunnelsCache c =
                new TunnelPoolManager.TooManyTunnelsCache(peers, T0 + 1);
        assertEquals(2, c._peers.size());
        assertTrue(c._peers.contains(hash(7)));
        assertFalse(c._peers.contains(hash(9)));
    }

    /**
     * An empty sweep is a real answer and still gets cached - every selector
     * construction in the window must not re-walk the pools just because no
     * peer is over the share limit.
     */
    @Test
    public void emptySweepIsStillASnapshot() {
        TunnelPoolManager.TooManyTunnelsCache c =
                new TunnelPoolManager.TooManyTunnelsCache(Collections.<Hash>emptySet(),
                                                          T0 + TunnelPoolManager.TOO_MANY_TUNNELS_TTL_MS);
        assertTrue(c.isValid(T0));
        assertTrue(c._peers.isEmpty());
    }

    /**
     * The window has to be long enough to cover one build pass, which is the
     * duplication being removed, and short enough that a peer which just
     * crossed the share limit stops being picked on the next one. A retune
     * outside that band is a behavioural change, not a tuning detail.
     */
    @Test
    public void windowCoversAPassButStaysShort() {
        long ttl = TunnelPoolManager.TOO_MANY_TUNNELS_TTL_MS;
        assertTrue("share window must be positive, was " + ttl + "ms", ttl > 0);
        assertTrue("share window must stay under a minute, was " + ttl + "ms", ttl < 60 * 1000L);
    }

    /**
     * The snapshot has to suppress the sweep, not just sit beside it: two asks
     * from one manager inside the window run the per-peer pass once, for the
     * exploratory pools and the client pools together, and not twice.
     */
    @Test
    public void sweepRunsOnceInsideWindow() {
        RouterContext ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", ctx != null);
        SweepCounter mgr = new SweepCounter(ctx);
        mgr.selectPeersInTooManyTunnels();
        int afterFirst = mgr._sweeps;
        mgr.selectPeersInTooManyTunnels();
        assertEquals("the first ask must count both tunnel types", 2, afterFirst);
        assertEquals("the second ask inside the window must reuse the snapshot",
                     afterFirst, mgr._sweeps);
    }

    /**
     * Wiring of the cache, not just of the snapshot object: an expired
     * snapshot must not suppress the sweep, and its peers must not be handed
     * out in place of a fresh answer.
     */
    @Test
    public void expiredSnapshotIsNotServed() {
        RouterContext ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", ctx != null);
        SweepCounter mgr = new SweepCounter(ctx);
        mgr._tooManyTunnelsCache = new TunnelPoolManager.TooManyTunnelsCache(
                Collections.singleton(hash(7)), ctx.clock().now() - 1);
        Set<Hash> got = mgr.selectPeersInTooManyTunnels();
        assertEquals("a stale snapshot must not suppress the sweep", 2, mgr._sweeps);
        assertFalse("the stale peers must not be handed out", got.contains(hash(7)));
    }

    /**
     * The other half of the wiring: a snapshot that is still inside its window
     * is served as the answer, with neither per-peer pass re-run.
     */
    @Test
    public void freshSnapshotIsServed() {
        RouterContext ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", ctx != null);
        SweepCounter mgr = new SweepCounter(ctx);
        mgr._tooManyTunnelsCache = new TunnelPoolManager.TooManyTunnelsCache(
                Collections.singleton(hash(7)), ctx.clock().now() + 60 * 1000L);
        Set<Hash> got = mgr.selectPeersInTooManyTunnels();
        assertEquals("a fresh snapshot must suppress both passes", 0, mgr._sweeps);
        assertEquals("the snapshot is the answer", 1, got.size());
        assertTrue(got.contains(hash(7)));
    }

    /**
     * The snapshot outlives the call that made it, so every caller is handed a
     * copy: clearing what one caller got must not empty the answer the next
     * one is served.
     */
    @Test
    public void servedSetIsACopy() {
        RouterContext ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", ctx != null);
        SweepCounter mgr = new SweepCounter(ctx);
        Set<Hash> peers = new HashSet<>();
        peers.add(hash(7));
        mgr._tooManyTunnelsCache = new TunnelPoolManager.TooManyTunnelsCache(
                peers, ctx.clock().now() + 60 * 1000L);
        Set<Hash> got = mgr.selectPeersInTooManyTunnels();
        assertNotSame("callers must not be handed the snapshot itself",
                      mgr._tooManyTunnelsCache._peers, got);
        got.clear();
        assertEquals("a caller clearing its copy must not empty the snapshot",
                     1, peers.size());
    }

    /**
     * A sweep publishes the snapshot and hands the caller a copy of it in the
     * same call, so a caller that writes into its result cannot change the
     * answer the next caller is served from that snapshot.
     */
    @Test
    public void sweepResultIsCopiedForTheCaller() {
        RouterContext ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", ctx != null);
        SweepCounter mgr = new SweepCounter(ctx);
        Set<Hash> first = mgr.selectPeersInTooManyTunnels();
        first.add(hash(42));
        Set<Hash> second = mgr.selectPeersInTooManyTunnels();
        assertFalse("a caller's addition must not reach the next caller",
                    second.contains(hash(42)));
        assertEquals("the second ask must still be served from the snapshot",
                     2, mgr._sweeps);
    }

    /**
     * A sweep stamps the snapshot with one window of freshness, no more: an
     * open-ended expiry would keep a peer that just fell back below the share
     * limit out of the selection indefinitely.
     */
    @Test
    public void sweepStampsTheSnapshotWithOneWindow() {
        RouterContext ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", ctx != null);
        SweepCounter mgr = new SweepCounter(ctx);
        mgr.selectPeersInTooManyTunnels();
        long after = ctx.clock().now();
        TunnelPoolManager.TooManyTunnelsCache c = mgr._tooManyTunnelsCache;
        assertTrue("the sweep must publish a snapshot", c != null);
        assertTrue("the snapshot it publishes must be fresh", c.isValid(after));
        long ahead = c._expiry - after;
        assertTrue("the snapshot may not outlive one window, it was " + ahead + "ms ahead",
                   ahead <= TunnelPoolManager.TOO_MANY_TUNNELS_TTL_MS);
    }

    /**
     * Manager that counts the per-peer sweeps so the reuse can be observed.
     */
    private static class SweepCounter extends TunnelPoolManager {

        /** Times {@link #countTunnelsPerPeer} has run. */
        int _sweeps;

        /**
         * Build a counting manager over a live context.
         *
         * @param ctx the router context
         */
        SweepCounter(RouterContext ctx) {
            super(ctx);
        }

        /**
         * Count the pass, then run the real one.
         *
         * @param lc object counter to store peer counts
         * @param exploratory true for the exploratory pools, false for client
         * @return total number of tunnels counted
         */
        @Override
        int countTunnelsPerPeer(net.i2p.util.ObjectCounterUnsafe<Hash> lc, boolean exploratory) {
            _sweeps++;
            return super.countTunnelsPerPeer(lc, exploratory);
        }
    }
}
