package net.i2p.kademlia;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;

import org.junit.Test;

/**
 *  Tests for KBucketImpl, the per-bucket storage used by the DHT.
 *  Covers add/remove semantics, the three trim strategies, range
 *  validation, and the last-changed timestamp.
 *
 *  @since 0.9.10
 */
public class KBucketImplTest {

    private static final int K = 4;

    private static Hash h(int seed) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        for (int i = 0; i < 4; i++) {
            data[i] = (byte) (seed >> (8 * (3 - i)));
        }
        data[Hash.HASH_LENGTH - 1] = (byte) seed;
        return Hash.create(data);
    }

    /** never rejects: accepts everything */
    private static final KBucketTrimmer<Hash> ACCEPT_ALL = new KBucketTrimmer<Hash>() {
        public boolean trim(KBucket<Hash> kbucket, Hash toAdd) {
            return true;
        }
    };

    @Test
    public void testRejectsInvertedRange() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        try {
            new KBucketImpl<Hash>(ctx, 10, 5, K, ACCEPT_ALL);
            fail("Expected IllegalArgumentException for begin > end");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testAddRemoveCount() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        KBucketImpl<Hash> bucket = new KBucketImpl<>(ctx, 0, 20, K, ACCEPT_ALL);
        assertTrue(bucket.add(h(1)));
        assertTrue(bucket.add(h(2)));
        assertTrue(bucket.add(h(3)));
        assertEquals(3, bucket.getKeyCount());
        assertTrue(bucket.remove(h(2)));
        assertEquals(2, bucket.getKeyCount());
        assertFalse(bucket.remove(h(99)));
    }

    @Test
    public void testDuplicateAdd() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        KBucketImpl<Hash> bucket = new KBucketImpl<>(ctx, 0, 20, K, ACCEPT_ALL);
        Hash hh = h(5);
        assertTrue(bucket.add(hh));
        // duplicate add returns false (not added), but still marks last-changed
        assertFalse(bucket.add(hh));
        assertEquals(1, bucket.getKeyCount());
    }

    @Test
    public void testRange() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        KBucketImpl<Hash> bucket = new KBucketImpl<>(ctx, 3, 8, K, ACCEPT_ALL);
        assertEquals(3, bucket.getRangeBegin());
        assertEquals(8, bucket.getRangeEnd());
    }

    @Test
    public void testEntriesUnmodifiable() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        KBucketImpl<Hash> bucket = new KBucketImpl<>(ctx, 0, 20, K, ACCEPT_ALL);
        bucket.add(h(1));
        try {
            bucket.getEntries().add(h(2));
            fail("getEntries() should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void testClear() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        KBucketImpl<Hash> bucket = new KBucketImpl<>(ctx, 0, 20, K, ACCEPT_ALL);
        bucket.add(h(1));
        bucket.add(h(2));
        bucket.clear();
        assertEquals(0, bucket.getKeyCount());
    }

    @Test
    public void testRejectTrimmerFullBucketRejects() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        KBucketImpl<Hash> bucket = new KBucketImpl<>(ctx, 0, 0, K, new RejectTrimmer<Hash>());
        for (int i = 0; i < K; i++) {
            assertTrue(bucket.add(h(i)));
        }
        // flood resistant: full single-range bucket always rejects
        assertFalse(bucket.add(h(K)));
        assertFalse(bucket.add(h(K + 1)));
        assertEquals(K, bucket.getKeyCount());
    }

    @Test
    public void testRandomTrimmerFullBucketAcceptsOne() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        KBucketImpl<Hash> bucket = new KBucketImpl<>(ctx, 0, 0, K, new RandomTrimmer<Hash>(ctx, K));
        for (int i = 0; i < K; i++) {
            assertTrue(bucket.add(h(i)));
        }
        // RandomTrimmer removes one and accepts the new one
        assertTrue(bucket.add(h(K)));
        assertEquals(K, bucket.getKeyCount());
    }

    @Test
    public void testRandomIfOldTrimmerRejectsWhenRecent() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        KBucketImpl<Hash> bucket = new KBucketImpl<>(ctx, 0, 0, K, new RandomIfOldTrimmer<Hash>(ctx, K));
        for (int i = 0; i < K; i++) {
            assertTrue(bucket.add(h(i)));
        }
        // last-changed is recent, so the trimmer refuses to evict
        assertFalse(bucket.add(h(K)));
        assertEquals(K, bucket.getKeyCount());
    }

    @Test
    public void testRandomIfOldTrimmerAcceptsWhenOld() throws Exception {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        KBucketImpl<Hash> bucket = new KBucketImpl<>(ctx, 0, 0, K, new RandomIfOldTrimmer<Hash>(ctx, K));
        for (int i = 0; i < K; i++) {
            assertTrue(bucket.add(h(i)));
        }
        // backdate last-changed beyond the 5 minute window without touching the shared clock
        Field lastChanged = KBucketImpl.class.getDeclaredField("_lastChanged");
        lastChanged.setAccessible(true);
        lastChanged.setLong(bucket, ctx.clock().now() - 10 * 60 * 1000);
        // bucket is old now, so the trimmer may evict and accept the new entry
        assertTrue(bucket.add(h(K)));
        assertEquals(K, bucket.getKeyCount());
    }

    @Test
    public void testGetEntriesCollector() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        KBucketImpl<Hash> bucket = new KBucketImpl<>(ctx, 0, 20, K, ACCEPT_ALL);
        bucket.add(h(1));
        bucket.add(h(2));
        final Set<Hash> collected = new HashSet<>();
        bucket.getEntries(new SelectionCollector<Hash>() {
            public void add(Hash entry) {
                collected.add(entry);
            }
        });
        assertEquals(2, collected.size());
    }

    @Test
    public void testLastChangedSetOnAdd() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        KBucketImpl<Hash> bucket = new KBucketImpl<>(ctx, 0, 20, K, ACCEPT_ALL);
        assertTrue(bucket.getLastChanged() == 0);
        bucket.add(h(1));
        assertTrue(bucket.getLastChanged() > 0);
        long first = bucket.getLastChanged();
        bucket.setLastChanged();
        assertTrue(bucket.getLastChanged() >= first);
    }
}
