package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import net.i2p.data.Hash;

/**
 * Tests ExcluderBase Set delegation pattern.
 *
 * @since 0.9.70+
 */
public class ExcluderBaseTest {

    /** Concrete subclass for testing. */
    private static class TestExcluder extends ExcluderBase {
        TestExcluder(Set<Hash> set) { super(set); }
        @Override
        public boolean contains(Object o) {
            if (o instanceof Hash) {
                s.add((Hash) o);
                return true;
            }
            return false;
        }
    }

    private static Hash createHash(byte b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = b;
        return Hash.create(data);
    }

    @Test
    public void testAddAndContains() {
        Set<Hash> backing = new HashSet<Hash>();
        TestExcluder ex = new TestExcluder(backing);
        Hash h1 = createHash((byte) 1);
        Hash h2 = createHash((byte) 2);

        assertTrue(ex.add(h1));
        assertTrue(backing.contains(h1));
        assertEquals(1, ex.size());
        assertFalse(ex.isEmpty());

        assertTrue(ex.add(h2));
        assertEquals(2, ex.size());
    }

    @Test
    public void testContainsAddsToSet() {
        Set<Hash> backing = new HashSet<Hash>();
        TestExcluder ex = new TestExcluder(backing);
        Hash h = createHash((byte) 42);

        assertFalse(backing.contains(h));
        assertTrue(ex.contains(h));
        assertTrue(backing.contains(h));
        assertEquals(1, backing.size());
    }

    @Test
    public void testRemove() {
        Set<Hash> backing = new HashSet<Hash>();
        TestExcluder ex = new TestExcluder(backing);
        Hash h = createHash((byte) 7);
        ex.add(h);
        assertTrue(ex.remove(h));
        assertTrue(ex.isEmpty());
    }

    @Test
    public void testClear() {
        Set<Hash> backing = new HashSet<Hash>();
        TestExcluder ex = new TestExcluder(backing);
        Hash h = createHash((byte) 3);
        ex.add(h);
        ex.clear();
        assertEquals(0, ex.size());
        assertTrue(ex.isEmpty());
    }

    @Test
    public void testToString() {
        Set<Hash> backing = new HashSet<Hash>();
        TestExcluder ex = new TestExcluder(backing);
        String s = ex.toString();
        assertTrue(s.contains("TestExcluder"));
        assertTrue(s.contains("0"));
    }

    @Test
    public void testAddAll() {
        Set<Hash> backing = new HashSet<Hash>();
        TestExcluder ex = new TestExcluder(backing);
        Hash h1 = createHash((byte) 1);
        Hash h2 = createHash((byte) 2);
        Set<Hash> toAdd = new HashSet<Hash>();
        toAdd.add(h1);
        toAdd.add(h2);

        assertTrue(ex.addAll(toAdd));
        assertEquals(2, ex.size());
    }

    @Test
    public void testContainsAll() {
        Set<Hash> backing = new HashSet<Hash>();
        TestExcluder ex = new TestExcluder(backing);
        Hash h1 = createHash((byte) 1);
        Hash h2 = createHash((byte) 2);
        ex.add(h1);
        ex.add(h2);

        Set<Hash> check = new HashSet<Hash>();
        check.add(h1);
        check.add(h2);
        assertTrue(ex.containsAll(check));
    }
}
