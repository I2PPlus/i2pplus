package net.i2p.router.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Test;

import net.i2p.data.Hash;

/**
 * Tests for {@link ClientManagerFacadeImpl#pruneStaleExpiredLogEntries}
 * (LEAK-04). The lease-expiration log throttle was keyed by destination hash
 * and never pruned, so it grew with every I2CP client the router ever served.
 * The prune keeps only entries whose destination still has a live runner.
 *
 * @since 0.9.71+
 */
public class ClientManagerFacadeImplExpiredLogPruneTest {

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (b + 7);
        return Hash.create(data);
    }

    /** Entries for destinations that no longer have a runner are dropped. */
    @Test
    public void testKeepsActiveDropsStale() {
        Hash active = hash(1);
        Hash gone = hash(2);
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        map.put(active, 100L);
        map.put(gone, 200L);
        Set<Hash> live = new HashSet<>();
        live.add(active);

        int removed = ClientManagerFacadeImpl.pruneStaleExpiredLogEntries(map, live);
        assertEquals(1, removed);
        assertTrue("active destination's throttle entry must survive", map.containsKey(active));
        assertTrue("disconnected destination's entry must be dropped", !map.containsKey(gone));
    }

    /** An empty active set clears the whole map (no clients connected). */
    @Test
    public void testEmptyActiveSetClearsAll() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        map.put(hash(1), 1L);
        map.put(hash(2), 2L);
        int removed = ClientManagerFacadeImpl.pruneStaleExpiredLogEntries(map, new HashSet<>());
        assertEquals(2, removed);
        assertTrue(map.isEmpty());
    }

    /** An empty map is a no-op. */
    @Test
    public void testEmptyMap() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        Set<Hash> live = new HashSet<>();
        live.add(hash(1));
        assertEquals(0, ClientManagerFacadeImpl.pruneStaleExpiredLogEntries(map, live));
    }
}
