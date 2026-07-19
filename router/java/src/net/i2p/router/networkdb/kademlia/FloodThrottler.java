package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer2;

/**
 * Provides basic denial-of-service protection for database flood operations.
 * <p>
 * Tracks recent flood frequency for each key to prevent excessive flooding
 * of the same data. Implements simple rate limiting with configurable
 * thresholds and automatic cleanup of expired counters.
 * <p>
 * Offers lightweight DOS protection by rejecting flood operations that
 * exceed maximum frequency limits within a time window. This is a
 * partial solution and should be used in conjunction with other
 * security mechanisms for comprehensive protection.
 *
 * @since 0.7.11
 */
class FloodThrottler {
    private final ObjectCounter<Hash> counter;
    private final Cleaner _cleaner;
    private static final int MAX_FLOODS = 3;
    private static final long CLEAN_TIME = 45*1000L;

    FloodThrottler() {
        this.counter = new ObjectCounter<>();
        _cleaner = new Cleaner();
        _cleaner.schedule(CLEAN_TIME);
    }

    /** increments before checking */
    boolean shouldThrottle(Hash h) {
        return this.counter.increment(h) > MAX_FLOODS;
    }

    /** Stop the periodic cleaner. Call on facade shutdown. @since 0.9.70+ */
    void cancel() {
        _cleaner.cancel();
    }

    private class Cleaner extends SimpleTimer2.TimedEvent {
        public Cleaner() { super(SimpleTimer2.getInstance()); }
        public void timeReached() {
            FloodThrottler.this.counter.decay(2);
        }
    }
}
