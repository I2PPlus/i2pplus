package org.xlattice.crypto.filters;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for BloomSHA1 insert and membership semantics.
 *
 * Replaces the commented-out main() in BloomSHA1, which constructed a
 * (24,11) filter and inserted 100 values but asserted nothing. The (24,11)
 * sizing is not used anywhere in the tree, so these tests use the default
 * constructor.
 *
 * A bloom filter may report false positives but never false negatives, so
 * every test asserts that inserted values are found.
 */
public class BloomSHA1Test {

    private static byte[] value(int i) {
        byte[] v = new byte[32];
        v[0] = (byte) (i >> 24);
        v[1] = (byte) (i >> 16);
        v[2] = (byte) (i >> 8);
        v[3] = (byte) i;
        return v;
    }

    /** A freshly built filter reports no members. */
    @Test
    public void testEmpty() {
        BloomSHA1 b = new BloomSHA1();
        assertFalse(b.member(value(1)));
    }

    /** An inserted value is reported as a member. */
    @Test
    public void testInsertThenMember() {
        BloomSHA1 b = new BloomSHA1();
        b.insert(value(42));
        assertTrue(b.member(value(42)));
    }

    /** Every one of many inserted values is found (no false negatives). */
    @Test
    public void testNoFalseNegatives() {
        BloomSHA1 b = new BloomSHA1();
        int count = 500;
        for (int i = 0; i < count; i++)
            b.insert(value(i));
        for (int i = 0; i < count; i++)
            assertTrue("value " + i + " must not be a false negative", b.member(value(i)));
    }

    /** Inserting the same value repeatedly is harmless. */
    @Test
    public void testDuplicateInsert() {
        BloomSHA1 b = new BloomSHA1();
        b.insert(value(7));
        b.insert(value(7));
        b.insert(value(7));
        assertTrue(b.member(value(7)));
    }

    /** insert() honours the offset and length arguments. */
    @Test
    public void testInsertWithOffset() {
        BloomSHA1 b = new BloomSHA1();
        byte[] padded = new byte[40];
        System.arraycopy(value(9), 0, padded, 8, 32);
        b.insert(padded, 8, 32);
        assertTrue(b.member(value(9)));
    }

    /** member() honours the offset and length arguments. */
    @Test
    public void testMemberWithOffset() {
        BloomSHA1 b = new BloomSHA1();
        b.insert(value(11));
        byte[] padded = new byte[40];
        System.arraycopy(value(11), 0, padded, 8, 32);
        assertTrue(b.member(padded, 8, 32));
    }

    /** size() and capacity() reflect insertions and reset on clear(). */
    @Test
    public void testSizeAndCapacity() {
        BloomSHA1 b = new BloomSHA1();
        int cap = b.capacity();
        assertTrue("capacity should be positive", cap > 0);
        b.insert(value(1));
        b.insert(value(2));
        assertEquals(2, b.size());
        b.clear();
        assertEquals(0, b.size());
    }

    /** clear() removes all members. */
    @Test
    public void testClear() {
        BloomSHA1 b = new BloomSHA1();
        b.insert(value(1));
        b.insert(value(2));
        assertTrue(b.member(value(1)));
        b.clear();
        assertFalse(b.member(value(1)));
        assertFalse(b.member(value(2)));
    }

    /**
     * The FilterKey round trip: a key computed from the same bytes must
     * report a member once inserted, and the key's buffers must be returned
     * to the pool via release().
     */
    @Test
    public void testFilterKeyRoundTrip() {
        BloomSHA1 b = new BloomSHA1();
        byte[] v = value(5);
        BloomSHA1.FilterKey fk = b.getFilterKey(v, 0, v.length);
        try {
            assertFalse("nothing inserted yet", b.locked_member(fk));
            b.locked_insert(fk);
            assertTrue("inserted via key", b.locked_member(fk));
            assertTrue("also visible through member()", b.member(v));
        } finally {
            b.release(fk);
        }
    }

    /** locked_insert()/locked_member() byte[] overloads agree with member(). */
    @Test
    public void testLockedByteOverloads() {
        BloomSHA1 b = new BloomSHA1();
        byte[] v = value(13);
        b.locked_insert(v);
        assertTrue(b.locked_member(v));
        assertTrue(b.member(v));
    }

    /**
     * A prepared reset must clear every member and the size, and must be
     * reusable: a second reset prepared before the first is applied is still
     * valid, which is what lets a caller pay the allocation off the lock.
     */
    @Test
    public void testPreparedReset() {
        BloomSHA1 b = new BloomSHA1();
        b.insert(value(1));
        b.insert(value(2));
        assertEquals(2, b.size());
        BloomSHA1.Reset r1 = b.prepareReset();
        BloomSHA1.Reset r2 = b.prepareReset();     // prepared before either is applied
        b.applyReset(r1);
        assertEquals("reset must empty the filter", 0, b.size());
        assertFalse(b.member(value(1)));
        b.insert(value(3));
        assertTrue(b.member(value(3)));
        b.applyReset(r2);                          // the older token is still good
        assertEquals(0, b.size());
        assertFalse(b.member(value(3)));
    }

    /** A reset token from a differently sized filter must be rejected. */
    @Test
    public void testPreparedResetWrongSizeRejected() {
        BloomSHA1 small = new BloomSHA1(12, 6);
        BloomSHA1 big = new BloomSHA1(20, 8);
        try {
            small.applyReset(big.prepareReset());
            fail("expected IllegalArgumentException for a mismatched reset");
        } catch (IllegalArgumentException expected) {
            // as above
        }
    }

    /** A null reset must be rejected rather than corrupting the filter. */
    @Test
    public void testNullResetRejected() {
        BloomSHA1 b = new BloomSHA1();
        try {
            b.applyReset(null);
            fail("expected IllegalArgumentException for a null reset");
        } catch (IllegalArgumentException expected) {
            // as above
        }
    }
}
