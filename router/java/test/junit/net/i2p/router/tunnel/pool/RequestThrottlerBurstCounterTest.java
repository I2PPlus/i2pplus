package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import net.i2p.data.Hash;

/**
 * Tests for {@link RequestThrottler.BurstWindowCounter}, the per-peer sliding
 * window used to detect transit build-request bursts. The hot-path
 * {@code increment} was de-synchronized (the backing map is a
 * {@code ConcurrentHashMap} of {@code AtomicIntegerArray}s, so the per-bucket
 * update is already atomic) and the periodic sweep is now CAS-guarded so a
 * single caller runs it off any monitor. These tests pin the counting and
 * sweep semantics that the change had to preserve.
 *
 * @since 0.9.71+
 */
public class RequestThrottlerBurstCounterTest {

    private static final long WINDOW_MS = 10 * 1000L;
    private static final int BUCKETS = 10;   // 1s per bucket

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (b + 50);
        return Hash.create(data);
    }

    @Test
    public void incrementReturnsRisingWindowSum() {
        RequestThrottler.BurstWindowCounter c = new RequestThrottler.BurstWindowCounter(WINDOW_MS, BUCKETS);
        Hash h = hash(1);
        assertEquals(1, c.increment(h));
        assertEquals(2, c.increment(h));
        assertEquals(3, c.increment(h));
        assertEquals(3, c.getCount(h));
    }

    @Test
    public void countsArePerPeer() {
        RequestThrottler.BurstWindowCounter c = new RequestThrottler.BurstWindowCounter(WINDOW_MS, BUCKETS);
        Hash a = hash(2);
        Hash b = hash(3);
        c.increment(a);
        c.increment(a);
        c.increment(b);
        assertEquals(2, c.getCount(a));
        assertEquals(1, c.getCount(b));
    }

    @Test
    public void unknownPeerCountsZero() {
        RequestThrottler.BurstWindowCounter c = new RequestThrottler.BurstWindowCounter(WINDOW_MS, BUCKETS);
        assertEquals(0, c.getCount(hash(9)));
        assertEquals(0, c.getCurrentBucketCount(hash(9)));
    }

    @Test
    public void currentBucketCountTracksLatestIncrement() {
        RequestThrottler.BurstWindowCounter c = new RequestThrottler.BurstWindowCounter(WINDOW_MS, BUCKETS);
        Hash h = hash(4);
        c.increment(h);
        c.increment(h);
        // Both increments land in the same 1s bucket under a fast test clock,
        // so the current bucket holds the full count.
        assertEquals(2, c.getCurrentBucketCount(h));
    }

    @Test
    public void cleanupRemovesPeerWithNoRecentActivity() {
        RequestThrottler.BurstWindowCounter c = new RequestThrottler.BurstWindowCounter(WINDOW_MS, BUCKETS);
        Hash h = hash(5);
        c.increment(h);
        assertEquals(1, c.getCount(h));
        // Sweep far in the future. The offset must NOT be a whole multiple of the
        // window, or the bucket index wraps back onto the peer's own bucket and it
        // would look current. 1_005_000 ms shifts the index by 5 buckets, so the
        // peer's only count sits in neither the current nor previous bucket.
        long future = System.currentTimeMillis() + 1_005_000L;
        c.cleanup(future);
        assertEquals(0, c.getCount(h));
    }

    @Test
    public void cleanupKeepsPeerActiveInCurrentBucket() {
        RequestThrottler.BurstWindowCounter c = new RequestThrottler.BurstWindowCounter(WINDOW_MS, BUCKETS);
        Hash h = hash(6);
        c.increment(h);
        // Sweep at "now": the peer's count is in the current bucket, so it stays.
        c.cleanup(System.currentTimeMillis());
        assertEquals(1, c.getCount(h));
    }

    @Test
    public void cleanupIsIdempotent() {
        RequestThrottler.BurstWindowCounter c = new RequestThrottler.BurstWindowCounter(WINDOW_MS, BUCKETS);
        Hash h = hash(7);
        c.increment(h);
        // Sweep far in the future (offset not a window multiple, so the peer's
        // bucket is neither current nor previous). The first sweep removes it; a
        // second sweep must leave the already-clean counter in the same state.
        long future = System.currentTimeMillis() + 1_005_000L;
        c.cleanup(future);
        assertEquals(0, c.getCount(h));
        c.cleanup(future);
        assertEquals(0, c.getCount(h));
    }
}
