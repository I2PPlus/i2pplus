package net.i2p.client.streaming.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.junit.Test;

import net.i2p.data.Hash;

/**
 * Tests for {@link ConnectionManager#pruneEmptyPoolBuckets} (LEAK-03). The
 * per-destination stream pool kept an (eventually empty) deque entry for every
 * destination it had ever pooled a connection to; a destination that went quiet
 * was never drained, so the map grew without bound. The sweep drops empty
 * buckets while leaving live ones in place.
 *
 * @since 0.9.71+
 */
public class ConnectionManagerPoolPruneTest {

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (b + 50);
        return Hash.create(data);
    }

    /** Empty buckets are removed; a non-empty bucket is kept. */
    @Test
    public void testRemovesEmptyKeepsNonEmpty() {
        Map<Hash, ConcurrentLinkedDeque<PooledConnectionStub>> pools = new ConcurrentHashMap<>();
        Hash empty = hash(1);
        Hash live = hash(2);
        pools.put(empty, new ConcurrentLinkedDeque<>());
        ConcurrentLinkedDeque<PooledConnectionStub> dq = new ConcurrentLinkedDeque<>();
        dq.add(new PooledConnectionStub());
        pools.put(live, dq);

        int removed = ConnectionManager.pruneEmptyPoolBuckets(pools);
        assertEquals(1, removed);
        assertFalse("empty bucket must be dropped", pools.containsKey(empty));
        assertTrue("non-empty bucket must survive", pools.containsKey(live));
    }

    /** An all-empty map is fully drained. */
    @Test
    public void testAllEmptyDrained() {
        Map<Hash, ConcurrentLinkedDeque<PooledConnectionStub>> pools = new ConcurrentHashMap<>();
        pools.put(hash(1), new ConcurrentLinkedDeque<>());
        pools.put(hash(2), new ConcurrentLinkedDeque<>());
        assertEquals(2, ConnectionManager.pruneEmptyPoolBuckets(pools));
        assertTrue(pools.isEmpty());
    }

    /** An empty map is a no-op. */
    @Test
    public void testEmptyMap() {
        Map<Hash, ConcurrentLinkedDeque<PooledConnectionStub>> pools = new ConcurrentHashMap<>();
        assertEquals(0, ConnectionManager.pruneEmptyPoolBuckets(pools));
    }

    /**
     * Stand-in element type: the prune only inspects deque emptiness, so the
     * element value is irrelevant. The production signature uses the private
     * {@code PooledConnection}; the helper is generic over the element type so
     * the test can exercise it without constructing one.
     */
    private static final class PooledConnectionStub {
    }
}
