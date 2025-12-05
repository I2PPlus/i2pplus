package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;
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
    private static final int MAX_FLOODS = 3;
//    private static final long CLEAN_TIME = 60*1000;
    private static final long CLEAN_TIME = 45*1000;

    FloodThrottler() {
        this.counter = new ObjectCounter<Hash>();
        SimpleTimer2.getInstance().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /** increments before checking */
    boolean shouldThrottle(Hash h) {
        return this.counter.increment(h) > MAX_FLOODS;
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            FloodThrottler.this.counter.clear();
        }
    }
}
