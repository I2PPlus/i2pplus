package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 * Tests that a published LeaseSet is checked against the tunnels the pool still
 * holds, through {@link TunnelPool#countLeasesWithoutTunnel(LeaseSet)} and its
 * identity predicate {@link TunnelPool#holdsTunnelFor(List, Lease)}.
 *
 * <p>A published set is a snapshot of the pool at mint time.  When the pool
 * replaces a tunnel, the stored copy keeps advertising the tunnel it was built
 * from, and a client that picks that lease reaches a gateway with nothing behind
 * it.  Measured on a live router, 5 of 46 published leases named no live inbound
 * tunnel, and one destination's set carried 2 of 2 dead: the destination
 * resolved, a stream connected, and nothing ever answered.
 *
 * <p>The re-mint gate cannot see this on its own.  It compares lease dates, and
 * a dead lease with a long date out-dates a live one with a short date, so the
 * swap is refused as a regression and the dead copy stays published until it
 * lapses.  Counting the mismatch is what turns "wrong" into a signal distinct
 * from "thin".
 *
 * @since 0.9.72+
 */
public class TunnelPoolStaleLeaseTest {

    /**
     * JUnit creates one instance per test method; pools and tunnels are built
     * per call from a shared context, so the instance carries no state.
     */
    public TunnelPoolStaleLeaseTest() {
    }

    /** Shared router context for the pools and tunnels this test builds. */
    private static RouterContext _ctx;
    /** One minute in milliseconds, for the lifetimes below. */
    private static final long MINUTE = 60 * 1000L;

    /**
     * Builds the router context every test in this class reads.
     */
    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
    }

    /**
     * A client inbound pool wanting the given number of tunnels.
     *
     * @param quantity tunnels the pool is configured to hold
     * @return a pool over the shared context, never null
     */
    private static TunnelPool pool(int quantity) {
        assumeTrue("No RouterContext available", _ctx != null);
        TunnelPoolSettings settings = new TunnelPoolSettings(true);
        settings.setQuantity(quantity);
        return new TunnelPool(_ctx, mock(TunnelPoolManager.class), settings,
                              mock(TunnelPeerSelector.class));
    }

    /**
     * A one-hop inbound tunnel with the given identity, owned by the pool.
     *
     * @param owner the pool that will hold it, so the config's pool lookups
     *              resolve the way they do in the running router
     * @param receiveTunnelId receive tunnel id of the single hop, the value a
     *                        lease for this tunnel carries
     * @param gateway ordinal the gateway hash is built from
     * @return a one-hop tunnel expiring an hour out, never failed
     */
    private static PooledTunnelCreatorConfig tunnel(TunnelPool owner, long receiveTunnelId,
                                                    int gateway) {
        PooledTunnelCreatorConfig cfg =
            new PooledTunnelCreatorConfig(_ctx, 1, true, null, owner);
        cfg.getConfig(0).setReceiveTunnelId(receiveTunnelId);
        cfg.setPeer(0, hash(gateway));
        cfg.setExpiration(_ctx.clock().now() + 60 * MINUTE);
        return cfg;
    }

    /**
     * A gateway hash built from an ordinal, so each tunnel is distinct.
     *
     * @param ordinal written into the hash's first four bytes, big-endian
     * @return a hash whose first four bytes are the ordinal and the rest zero
     */
    private static Hash hash(int ordinal) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) (ordinal >>> 24);
        data[1] = (byte) (ordinal >>> 16);
        data[2] = (byte) (ordinal >>> 8);
        data[3] = (byte) ordinal;
        return Hash.create(data);
    }

    /**
     * A published LeaseSet advertising one lease per tunnel given, all ending at
     * the same instant so no date difference can change the answer.
     *
     * @param tunnels the tunnels the set was minted from
     * @return an unsigned LeaseSet holding those leases
     */
    private static LeaseSet publishedSet(PooledTunnelCreatorConfig... tunnels) {
        LeaseSet ls = new LeaseSet();
        long end = _ctx.clock().now() + 5 * MINUTE;
        for (PooledTunnelCreatorConfig tunnel : tunnels) {
            Lease lease = new Lease();
            lease.setGateway(tunnel.getPeer(0));
            lease.setTunnelId(tunnel.getReceiveTunnelId(0));
            lease.setEndDate(end);
            ls.addLease(lease);
        }
        return ls;
    }

    /**
     * Installs tunnels in the pool as if those builds had just completed.
     *
     * @param pool the pool to install into
     * @param tunnels tunnels to install, each already owned by this pool
     */
    private static void stocked(TunnelPool pool, PooledTunnelCreatorConfig... tunnels) {
        for (PooledTunnelCreatorConfig tunnel : tunnels) {
            pool.addTunnel(tunnel);
        }
    }

    // ---- the identity predicate ----

    /**
     * A lease is satisfied by the tunnel whose gateway and receive tunnel id it
     * names - the same key the lease-rotation path matches on.
     */
    @Test
    public void aLeaseMatchesTheTunnelItWasBuiltFrom() {
        TunnelPool pool = pool(1);
        PooledTunnelCreatorConfig tunnel = tunnel(pool, 1000, 1);
        Lease lease = publishedSet(tunnel).getLease(0);
        assertTrue(TunnelPool.holdsTunnelFor(Arrays.asList(tunnel), lease));
    }

    /**
     * The same receive tunnel id behind a different gateway is a different path,
     * so it does not satisfy the lease.  One peer can gateway several tunnels of
     * a destination, and only the id says which one a client reaches.
     */
    @Test
    public void aDifferentGatewayDoesNotSatisfyTheLease() {
        TunnelPool pool = pool(1);
        PooledTunnelCreatorConfig tunnel = tunnel(pool, 1000, 1);
        Lease lease = publishedSet(tunnel).getLease(0);
        PooledTunnelCreatorConfig sameIdOtherGateway = tunnel(pool, 1000, 2);
        assertFalse(TunnelPool.holdsTunnelFor(Arrays.asList(sameIdOtherGateway), lease));
    }

    /**
     * The same gateway with a different receive tunnel id is a tunnel a client
     * has no way to reach through that lease.
     */
    @Test
    public void aDifferentTunnelIdDoesNotSatisfyTheLease() {
        TunnelPool pool = pool(1);
        PooledTunnelCreatorConfig tunnel = tunnel(pool, 1000, 1);
        Lease lease = publishedSet(tunnel).getLease(0);
        PooledTunnelCreatorConfig sameGatewayOtherId = tunnel(pool, 1001, 1);
        assertFalse(TunnelPool.holdsTunnelFor(Arrays.asList(sameGatewayOtherId), lease));
    }

    /**
     * Nothing to match against is never a match: a null lease, a null list and
     * an empty pool all answer false rather than throwing, because the caller
     * counts misses and a thrown exception would stop the re-mint entirely.
     */
    @Test
    public void missingInputsNeverMatch() {
        TunnelPool pool = pool(1);
        PooledTunnelCreatorConfig tunnel = tunnel(pool, 1000, 1);
        Lease lease = publishedSet(tunnel).getLease(0);
        assertFalse(TunnelPool.holdsTunnelFor(null, lease));
        assertFalse(TunnelPool.holdsTunnelFor(Collections.<TunnelInfo>emptyList(), lease));
        assertFalse(TunnelPool.holdsTunnelFor(Arrays.asList(tunnel), null));
    }

    /**
     * A lease missing either half of its identity cannot be matched, and counts
     * as unmatched rather than matching every tunnel that shares the half it has.
     */
    @Test
    public void aLeaseWithoutBothHalvesOfItsIdentityCannotMatch() {
        TunnelPool pool = pool(1);
        PooledTunnelCreatorConfig tunnel = tunnel(pool, 1000, 1);
        long end = _ctx.clock().now() + 5 * MINUTE;

        Lease noId = new Lease();
        noId.setGateway(tunnel.getPeer(0));
        noId.setEndDate(end);
        assertFalse(TunnelPool.holdsTunnelFor(Arrays.asList(tunnel), noId));

        Lease noGateway = new Lease();
        noGateway.setTunnelId(tunnel.getReceiveTunnelId(0));
        noGateway.setEndDate(end);
        assertFalse(TunnelPool.holdsTunnelFor(Arrays.asList(tunnel), noGateway));
    }

    // ---- the pool-side count ----

    /**
     * A set minted from the tunnels the pool still holds is not stale, which is
     * the steady state the count must leave alone.
     */
    @Test
    public void aSetBuiltFromLiveTunnelsIsNotStale() {
        TunnelPool pool = pool(2);
        PooledTunnelCreatorConfig a = tunnel(pool, 1000, 1);
        PooledTunnelCreatorConfig b = tunnel(pool, 1001, 2);
        stocked(pool, a, b);
        assertEquals(0, pool.countLeasesWithoutTunnel(publishedSet(a, b)));
    }

    /**
     * The regression: replacing one tunnel leaves the published copy advertising
     * a gateway with nothing behind it, and the count has to see exactly one.
     */
    @Test
    public void replacingOneTunnelLeavesOneStaleLease() {
        TunnelPool minted = pool(2);
        LeaseSet published = publishedSet(tunnel(minted, 1000, 1), tunnel(minted, 1001, 2));

        TunnelPool pool = pool(2);
        stocked(pool, tunnel(pool, 1001, 2), tunnel(pool, 1002, 3));
        assertEquals(1, pool.countLeasesWithoutTunnel(published));
    }

    /**
     * The case that made a destination unreachable while it looked healthy: the
     * pool rebuilt every inbound tunnel, so all published leases were dead, and
     * the set still advertised a full complement with a long expiry.
     */
    @Test
    public void aFullyRebuiltPoolLeavesEveryLeaseStale() {
        TunnelPool minted = pool(2);
        LeaseSet published = publishedSet(tunnel(minted, 1000, 1), tunnel(minted, 1001, 2));

        TunnelPool pool = pool(2);
        stocked(pool, tunnel(pool, 1002, 3), tunnel(pool, 1003, 4));
        assertEquals("the infobase.i2p case: 2 of 2 published leases dead",
                     2, pool.countLeasesWithoutTunnel(published));
    }

    /**
     * Nothing published yet is not a stale copy - there is no wrong advertisement
     * to correct, and the mint path handles the empty case.
     */
    @Test
    public void nothingPublishedIsNotStale() {
        TunnelPool pool = pool(2);
        stocked(pool, tunnel(pool, 1000, 1));
        assertEquals(0, pool.countLeasesWithoutTunnel(null));
    }

    /**
     * A revoked or empty published set has no lease to be wrong about.
     */
    @Test
    public void anEmptyPublishedSetIsNotStale() {
        TunnelPool pool = pool(2);
        stocked(pool, tunnel(pool, 1000, 1));
        assertEquals(0, pool.countLeasesWithoutTunnel(new LeaseSet()));
    }

    /**
     * A tunnel that failed its tests but is still held still satisfies its
     * lease: the gateway mapping stays installed until the tunnel is removed, so
     * that lease is degraded, not dead, and the count must not call it stale.
     * This pins the choice - staleness is about presence in the pool, not about
     * test status.
     */
    @Test
    public void aFailedTunnelStillHeldStillSatisfiesItsLease() {
        TunnelPool pool = pool(1);
        PooledTunnelCreatorConfig tunnel = tunnel(pool, 1000, 1);
        stocked(pool, tunnel);
        LeaseSet published = publishedSet(tunnel);
        // The saturated counter, not tunnelFailed(), so the pool keeps the
        // tunnel: this is the condemned-but-retained state, not the removal.
        for (int i = 0; i <= TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES; i++) {
            tunnel.incrementTestFailures();
        }
        assertTrue(tunnel.getTunnelFailed());
        assertEquals(0, pool.countLeasesWithoutTunnel(published));
    }

    /**
     * The tunnels a lease is matched against are read once, under the pool's
     * lock, so a build landing mid-count cannot be seen twice or not at all.
     */
    @Test
    public void theCountReadsOneConsistentSnapshot() {
        TunnelPool minted = pool(3);
        LeaseSet published = publishedSet(tunnel(minted, 1000, 1), tunnel(minted, 1001, 2),
                                          tunnel(minted, 1002, 3));

        TunnelPool pool = pool(3);
        PooledTunnelCreatorConfig a = tunnel(pool, 1000, 1);
        PooledTunnelCreatorConfig b = tunnel(pool, 1001, 2);
        PooledTunnelCreatorConfig c = tunnel(pool, 1002, 3);
        stocked(pool, a, b);
        int before = pool.countLeasesWithoutTunnel(published);
        pool.addTunnel(c);
        int after = pool.countLeasesWithoutTunnel(published);
        assertEquals(1, before);
        assertEquals("a tunnel added between reads must move the count once, not twice",
                     0, after);
    }
}
