package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.Test;

import net.i2p.data.Hash;

/**
 * Tests for {@link ClientPeerSelector#pruneStalePreConnect} (LEAK-02). The
 * pre-connect map is keyed by peer hash and was never pruned in production, so
 * it grew with every distinct peer the router pre-connected to over its
 * lifetime. The sweep drops entries past the cooldown, which can never be read
 * as "recently connected" again.
 *
 * @since 0.9.71+
 */
public class ClientPeerSelectorPreConnectPruneTest {

    private static final long COOLDOWN = 5 * 60 * 1000L;
    private static final long NOW = 10_000_000L;

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (b + 100);
        return Hash.create(data);
    }

    /** Entries older than the cooldown are dropped; fresh ones survive. */
    @Test
    public void testPrunesStaleKeepsFresh() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        Hash stale = hash(1);
        Hash fresh = hash(2);
        map.put(stale, NOW - COOLDOWN - 1000L);
        map.put(fresh, NOW - 1000L);
        int removed = ClientPeerSelector.pruneStalePreConnect(map, NOW);
        assertEquals(1, removed);
        assertTrue("fresh entry must survive", map.containsKey(fresh));
        assertTrue("stale entry must be dropped", !map.containsKey(stale));
    }

    /** Boundary: an entry exactly at the cooldown is no longer "recently connected". */
    @Test
    public void testBoundaryExactlyCooldownPruned() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        Hash at = hash(3);
        map.put(at, NOW - COOLDOWN);
        int removed = ClientPeerSelector.pruneStalePreConnect(map, NOW);
        assertEquals("exactly at the cooldown is pruned", 1, removed);
    }

    /** Boundary: one millisecond inside the cooldown is kept. */
    @Test
    public void testBoundaryInsideCooldownKept() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        Hash inside = hash(4);
        map.put(inside, NOW - COOLDOWN + 1L);
        int removed = ClientPeerSelector.pruneStalePreConnect(map, NOW);
        assertEquals("one ms inside the cooldown is kept", 0, removed);
        assertTrue(map.containsKey(inside));
    }

    /** An empty map is a no-op. */
    @Test
    public void testEmptyMap() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        assertEquals(0, ClientPeerSelector.pruneStalePreConnect(map, NOW));
    }
}
