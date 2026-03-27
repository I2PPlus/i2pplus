package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TransientDataStoreTest {

    private static RouterContext _context;
    private TransientDataStore _store;

    @BeforeClass
    public static void checkContext() {
        _context = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _context != null);
    }

    @Before
    public void setUp() {
        Assume.assumeTrue("No RouterContext available", _context != null);
        _store = new TransientDataStore(_context);
    }

    private Hash randomHash() {
        Hash h = new Hash();
        byte[] d = new byte[Hash.HASH_LENGTH];
        _context.random().nextBytes(d);
        h.setData(d);
        return h;
    }

    private RouterInfo createRouterInfo() {
        RouterInfo ri = new RouterInfo();
        ri.setPublished(_context.clock().now());
        return ri;
    }

    @Test
    public void testIsInitialized() {
        assertTrue(_store.isInitialized());
    }

    @Test
    public void testEmptyStore() {
        assertEquals(0, _store.size());
        assertNull(_store.get(randomHash()));
    }

    @Test
    public void testPutRouterInfo() {
        Hash key = randomHash();
        RouterInfo ri = createRouterInfo();
        assertTrue(_store.put(key, ri));
        assertEquals(1, _store.size());
    }

    @Test
    public void testGetRouterInfo() {
        Hash key = randomHash();
        RouterInfo ri = createRouterInfo();
        _store.put(key, ri);
        assertEquals(ri, _store.get(key));
    }

    @Test
    public void testGetNonexistent() {
        assertNull(_store.get(randomHash()));
    }

    @Test
    public void testGetNullKey() {
        assertNull(_store.get(null));
    }

    @Test
    public void testRemoveExisting() {
        Hash key = randomHash();
        RouterInfo ri = createRouterInfo();
        _store.put(key, ri);
        assertEquals(ri, _store.remove(key));
        assertEquals(0, _store.size());
        assertNull(_store.get(key));
    }

    @Test
    public void testRemoveNonexistent() {
        assertNull(_store.remove(randomHash()));
    }

    @Test
    public void testIsKnown() {
        Hash key = randomHash();
        assertFalse(_store.isKnown(key));
        _store.put(key, createRouterInfo());
        assertTrue(_store.isKnown(key));
    }

    @Test
    public void testStopClearsStore() {
        Hash key = randomHash();
        _store.put(key, createRouterInfo());
        assertEquals(1, _store.size());
        _store.stop();
        assertEquals(0, _store.size());
    }

    @Test
    public void testGetKeys() {
        Hash key1 = randomHash();
        Hash key2 = randomHash();
        _store.put(key1, createRouterInfo());
        _store.put(key2, createRouterInfo());
        assertTrue(_store.getKeys().contains(key1));
        assertTrue(_store.getKeys().contains(key2));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testGetKeysUnmodifiable() {
        _store.put(randomHash(), createRouterInfo());
        _store.getKeys().clear();
    }

    @Test
    public void testGetEntries() {
        RouterInfo ri = createRouterInfo();
        Hash key = randomHash();
        _store.put(key, ri);
        assertTrue(_store.getEntries().contains(ri));
    }

    @Test
    public void testGetEntriesUnmodifiable() {
        _store.put(randomHash(), createRouterInfo());
        try {
            _store.getEntries().clear();
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void testForcePut() {
        Hash key = randomHash();
        RouterInfo ri1 = createRouterInfo();
        RouterInfo ri2 = createRouterInfo();
        assertTrue(_store.forcePut(key, ri1));
        assertTrue(_store.forcePut(key, ri2));
        assertEquals(1, _store.size());
        assertEquals(ri2, _store.get(key));
    }

    @Test
    public void testPutNullDataReturnsFalse() {
        assertFalse(_store.put(randomHash(), null));
    }

    @Test
    public void testPutPersistThrows() {
        try {
            _store.put(randomHash(), createRouterInfo(), true);
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void testGetPersistThrows() {
        try {
            _store.get(randomHash(), true);
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void testRemovePersistThrows() {
        try {
            _store.remove(randomHash(), true);
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void testCountLeaseSetsEmpty() {
        assertEquals(0, _store.countLeaseSets());
    }

    @Test
    public void testCountLeaseSetsRouterInfosDontCount() {
        _store.put(randomHash(), createRouterInfo());
        _store.put(randomHash(), createRouterInfo());
        assertEquals(0, _store.countLeaseSets());
    }

    @Test
    public void testCountLeaseSets() {
        Hash key = randomHash();
        LeaseSet ls = new LeaseSet();
        assertTrue(_store.put(key, ls));
        assertEquals(1, _store.countLeaseSets());
    }

    @Test
    public void testMultiplePuts() {
        for (int i = 0; i < 100; i++) {
            _store.put(randomHash(), createRouterInfo());
        }
        assertEquals(100, _store.size());
    }

    @Test
    public void testToString() {
        _store.put(randomHash(), createRouterInfo());
        String s = _store.toString();
        assertNotNull(s);
        assertTrue(s.contains("Transient DataStore"));
    }
}
