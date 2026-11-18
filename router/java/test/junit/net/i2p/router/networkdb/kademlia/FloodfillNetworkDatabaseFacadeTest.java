package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.LeaseSet2;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.Test;

/**
 * The decisions a floodfill makes when it stores an entry of its own: which
 * floodfill peers a store goes to, whether the entry is wide-flooded, and how
 * many reply-based store jobs run beside the flood. It also pins the flag those
 * decisions read — a client's sub-NetDb, which is what publishes every lease
 * set, has no floodfill monitor and so is never floodfill-enabled.
 */
public class FloodfillNetworkDatabaseFacadeTest {

    /** Runs the cases; the fixtures it needs are static. */
    public FloodfillNetworkDatabaseFacadeTest() {}

    /**
     * A distinct hash to use as a peer, a store key or a sub-Db id.
     *
     * @param n written into the first byte, so different values give different hashes
     * @return a 32-byte hash
     */
    private static Hash hash(int n) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) n;
        return Hash.create(data);
    }

    @Test
    public void testPrefersNonRecentlyQueried() {
        List<Hash> shuffled = new ArrayList<>();
        Hash h1 = hash(1);
        Hash h2 = hash(2);
        Hash h3 = hash(3);
        Hash h4 = hash(4);
        shuffled.add(h1);
        shuffled.add(h2);
        shuffled.add(h3);
        shuffled.add(h4);
        Set<Hash> recentlyQueried = new HashSet<>();
        recentlyQueried.add(h1);
        recentlyQueried.add(h3);

        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(shuffled, 2, recentlyQueried);
        assertEquals(2, rv.size());
        assertTrue(rv.contains(h2));
        assertTrue(rv.contains(h4));
    }

    @Test
    public void testTopsUpWithRecentlyQueried() {
        List<Hash> shuffled = new ArrayList<>();
        Hash h1 = hash(1);
        Hash h2 = hash(2);
        shuffled.add(h1);
        shuffled.add(h2);
        Set<Hash> recentlyQueried = new HashSet<>();
        recentlyQueried.add(h1);
        recentlyQueried.add(h2);

        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(shuffled, 2, recentlyQueried);
        assertEquals(2, rv.size());
        assertTrue(rv.contains(h1));
        assertTrue(rv.contains(h2));
    }

    @Test
    public void testPartialTopUp() {
        List<Hash> shuffled = new ArrayList<>();
        Hash h1 = hash(1);
        Hash h2 = hash(2);
        Hash h3 = hash(3);
        shuffled.add(h1);
        shuffled.add(h2);
        shuffled.add(h3);
        Set<Hash> recentlyQueried = new HashSet<>();
        recentlyQueried.add(h1);
        recentlyQueried.add(h3);

        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(shuffled, 3, recentlyQueried);
        assertEquals(3, rv.size());
        assertTrue(rv.contains(h1));
        assertTrue(rv.contains(h2));
        assertTrue(rv.contains(h3));
    }

    @Test
    public void testCapsAtConcurrent() {
        List<Hash> shuffled = new ArrayList<>();
        Set<Hash> all = new HashSet<>();
        for (int i = 1; i <= 10; i++) {
            Hash h = hash(i);
            shuffled.add(h);
            all.add(h);
        }

        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(shuffled, 3, Collections.<Hash>emptySet());
        assertEquals(3, rv.size());
        assertTrue(all.containsAll(rv));
    }

    @Test
    public void testEmptyInput() {
        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(
            Collections.<Hash>emptyList(), 3, Collections.<Hash>emptySet());
        assertTrue(rv.isEmpty());
    }

    @Test
    public void testZeroConcurrent() {
        List<Hash> shuffled = new ArrayList<>();
        shuffled.add(hash(1));
        shuffled.add(hash(2));
        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(
            shuffled, 0, Collections.<Hash>emptySet());
        assertTrue(rv.isEmpty());
    }

    @Test
    public void testConcurrentAboveSizeReturnsAll() {
        List<Hash> shuffled = new ArrayList<>();
        Hash h1 = hash(1);
        Hash h2 = hash(2);
        shuffled.add(h1);
        shuffled.add(h2);

        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(
            shuffled, 10, Collections.<Hash>emptySet());
        assertEquals(2, rv.size());
        assertTrue(rv.contains(h1));
        assertTrue(rv.contains(h2));
    }

    @Test
    public void testNoDuplicatesInResult() {
        List<Hash> shuffled = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            shuffled.add(hash(i));
        }
        Set<Hash> recentlyQueried = new HashSet<>();
        recentlyQueried.add(hash(1));
        recentlyQueried.add(hash(2));

        Set<Hash> rv = FloodfillNetworkDatabaseFacade.selectStoreParticipants(shuffled, 4, recentlyQueried);
        assertEquals(4, rv.size());
    }

    /** A RouterInfo entry, enough for the type test the decisions make. */
    private static RouterInfo ri() {
        return new RouterInfo();
    }

    /** A LeaseSet2 entry, enough for the type test the decisions make. */
    private static LeaseSet2 ls2() {
        return new LeaseSet2();
    }

    /**
     * A lease set is never wide-flooded by sendStore, even on a floodfill: the
     * flood is a direct, token-less store, so a floodfill that hosted the
     * destination would name itself as that destination's gateway.
     */
    @Test
    public void testShouldNotFloodOnStoreForLeaseSet() {
        assertFalse(FloodfillNetworkDatabaseFacade.shouldFloodOnStore(true, ls2()));
        assertFalse(FloodfillNetworkDatabaseFacade.shouldFloodOnStore(false, ls2()));
    }

    /** Our own RouterInfo is the one entry sendStore wide-floods. */
    @Test
    public void testShouldFloodOnStoreFloodfillRouterInfo() {
        assertTrue(FloodfillNetworkDatabaseFacade.shouldFloodOnStore(true, ri()));
    }

    /** A router that is not a floodfill participant floods nothing. */
    @Test
    public void testShouldFloodOnStoreNotFloodfill() {
        assertFalse(FloodfillNetworkDatabaseFacade.shouldFloodOnStore(false, ls2()));
        assertFalse(FloodfillNetworkDatabaseFacade.shouldFloodOnStore(false, ri()));
    }

    /** No entry to name means no flood to decide. */
    @Test
    public void testShouldFloodOnStoreNullEntry() {
        assertFalse(FloodfillNetworkDatabaseFacade.shouldFloodOnStore(true, null));
        assertFalse(FloodfillNetworkDatabaseFacade.shouldFloodOnStore(false, null));
    }

    /**
     * A floodfill publishing a lease set runs the ordinary staggered store jobs,
     * one per selected peer — that is the tunnel-based path, and the only one.
     */
    @Test
    public void testStoreJobCountFloodfillLeaseSetUsesSelectedCount() {
        assertEquals(2, FloodfillNetworkDatabaseFacade.storeJobCount(true, ls2(), 2));
        assertEquals(3, FloodfillNetworkDatabaseFacade.storeJobCount(true, ls2(), 3));
    }

    /** The wide flood is the whole delivery of our own RouterInfo. */
    @Test
    public void testStoreJobCountFloodfillRouterInfoIsZero() {
        assertEquals(0, FloodfillNetworkDatabaseFacade.storeJobCount(true, ri(), 2));
    }

    /** A non-floodfill stores to every peer it selected. */
    @Test
    public void testStoreJobCountNonFloodfillUsesSelectedCount() {
        assertEquals(2, FloodfillNetworkDatabaseFacade.storeJobCount(false, ls2(), 2));
        assertEquals(3, FloodfillNetworkDatabaseFacade.storeJobCount(false, ri(), 3));
        assertEquals(1, FloodfillNetworkDatabaseFacade.storeJobCount(false, ls2(), 1));
    }

    /** Nothing to store, or nobody to store it to, schedules nothing. */
    @Test
    public void testStoreJobCountNullOrNonPositiveSelected() {
        assertEquals(0, FloodfillNetworkDatabaseFacade.storeJobCount(true, null, 2));
        assertEquals(0, FloodfillNetworkDatabaseFacade.storeJobCount(false, null, 2));
        assertEquals(0, FloodfillNetworkDatabaseFacade.storeJobCount(true, ls2(), 0));
        assertEquals(0, FloodfillNetworkDatabaseFacade.storeJobCount(false, ls2(), -1));
    }

    /** One selected peer still means one job, floodfill or not. */
    @Test
    public void testStoreJobCountFloodfillWithSingleSelected() {
        assertEquals(1, FloodfillNetworkDatabaseFacade.storeJobCount(true, ls2(), 1));
        assertEquals(0, FloodfillNetworkDatabaseFacade.storeJobCount(true, ri(), 1));
    }

    /**
     * The facade that publishes every lease set is the client's own sub-NetDb,
     * built with a destination hash as its id. It has no floodfill monitor, so
     * its floodfill flag can never be set — not by the monitor job, which it
     * never runs, and not by the console's toggle, which refuses to act without
     * a monitor and acts on the main netDb in any case. This is what kept the
     * lease-set flood above from ever running; if the flag ever becomes
     * reachable here, that flood comes back with it.
     */
    @Test
    public void testClientSubDbIsNeverFloodfillEnabled() {
        RouterContext ctx = RouterTestHelper.newContext(false);
        Assume.assumeTrue("No RouterContext available", ctx != null);
        FloodfillNetworkDatabaseFacade sub = new FloodfillNetworkDatabaseFacade(ctx, hash(7));
        assertTrue("a facade built with a destination id is a client Db", sub.isClientDb());
        assertFalse(sub.floodfillEnabled());
        sub.setFloodfillEnabled(true);
        assertFalse("the console toggle cannot enable a client sub-Db", sub.floodfillEnabled());
    }

    /**
     * The main netDb is the facade that carries the floodfill monitor, and so
     * the only one whose flag the monitor can set.
     */
    @Test
    public void testMainDbIsNotAClientDb() {
        RouterContext ctx = RouterTestHelper.newContext(false);
        Assume.assumeTrue("No RouterContext available", ctx != null);
        FloodfillNetworkDatabaseFacade main = new FloodfillNetworkDatabaseFacade(ctx);
        assertFalse(main.isClientDb());
        assertFalse(main.floodfillEnabled());
    }
}
