package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Hash;

import org.junit.Test;

/**
 * Unit tests for the per-key in-flight lookup accounting extracted from
 * BuildHandler: {@link BuildHandler#attachLookupKey} and
 * {@link BuildHandler#releaseLookupKey}.
 *
 * Pins the contract that slots count DISTINCT next-hop keys, not requests:
 * N builds missing the same popular hop attach to one shared search, while
 * distinct keys compete for the configured ceiling.
 *
 * @since 0.9.71+
 */
public class BuildHandlerLookupKeyTest {

    /** Deterministic 32-byte hash with every byte set to the given value. */
    private static Hash hash(int val) {
        byte[] b = new byte[Hash.HASH_LENGTH];
        Arrays.fill(b, (byte) val);
        Hash h = new Hash();
        h.setData(b);
        return h;
    }

    @Test
    public void firstAttachConsumesSlotAndSucceeds() {
        ConcurrentHashMap<Hash, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        assertTrue(BuildHandler.attachLookupKey(inFlight, hash(1), 2));
        assertEquals(1, inFlight.size());
    }

    @Test
    public void sameKeyReattachesWithoutExtraSlot() {
        // 5 builds missing the same hop share one search
        ConcurrentHashMap<Hash, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        Hash key = hash(2);
        for (int i = 0; i < 5; i++) {
            assertTrue(BuildHandler.attachLookupKey(inFlight, key, 1));
            assertEquals("duplicate-key attaches must not grow the set", 1, inFlight.size());
        }
    }

    @Test
    public void ceilingBlocksDistinctKeysOnly() {
        ConcurrentHashMap<Hash, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        Hash a = hash(3);
        Hash b = hash(4);
        assertTrue(BuildHandler.attachLookupKey(inFlight, a, 1));
        assertFalse("distinct key over the ceiling must queue",
                    BuildHandler.attachLookupKey(inFlight, b, 1));
        // but the saturated key still accepts joiners
        assertTrue(BuildHandler.attachLookupKey(inFlight, a, 1));
    }

    @Test
    public void releaseFreesSlotForNewKey() {
        ConcurrentHashMap<Hash, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        Hash a = hash(5);
        Hash b = hash(6);
        assertTrue(BuildHandler.attachLookupKey(inFlight, a, 1));
        assertFalse(BuildHandler.attachLookupKey(inFlight, b, 1));
        BuildHandler.releaseLookupKey(inFlight, a);
        assertTrue("released slot must be reusable by a distinct key",
                   BuildHandler.attachLookupKey(inFlight, b, 1));
    }

    @Test
    public void keyEntryRemovedWhenLastRequestReleases() {
        ConcurrentHashMap<Hash, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        Hash key = hash(7);
        BuildHandler.attachLookupKey(inFlight, key, 4);
        BuildHandler.attachLookupKey(inFlight, key, 4);
        BuildHandler.releaseLookupKey(inFlight, key);
        assertEquals(1, inFlight.size());
        BuildHandler.releaseLookupKey(inFlight, key);
        assertEquals("entry must be dropped when refcount hits zero", 0, inFlight.size());
    }

    @Test
    public void independentKeysCountSeparately() {
        ConcurrentHashMap<Hash, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        BuildHandler.attachLookupKey(inFlight, hash(8), 2);
        BuildHandler.attachLookupKey(inFlight, hash(9), 2);
        assertFalse(BuildHandler.attachLookupKey(inFlight, hash(10), 2));
        assertEquals(2, inFlight.size());
    }

    @Test
    public void releaseUnknownKeyIsNoop() {
        ConcurrentHashMap<Hash, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        BuildHandler.releaseLookupKey(inFlight, hash(11));
        assertEquals(0, inFlight.size());
    }
}
