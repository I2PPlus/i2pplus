package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.LHMCache;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer2;

/**
 * Negative cache for network database lookup operations.
 * <p>
 * Caches failed lookup attempts to prevent repeated queries to unavailable
 * or malicious peers. Implements time-based expiration and configurable
 * failure thresholds to balance between cache effectiveness and resource usage.
 * <p>
 * During startup or network attacks (build success &lt; 40%), the cache is more
 * lenient to avoid blocking legitimate lookups when the network is unstable.
 * <p>
 * Uses LRU eviction policy and periodic cleanup to maintain cache size.
 * Provides DOS protection by rate-limiting repeated failed lookups
 * for the same targets within configurable time windows.
 * <p>
 * Thread-safe implementation with atomic counters and scheduled cleanup
 * operations for reliable concurrent access in multi-threaded environments.
 *
 * @since 0.9.56
 */
class NegativeLookupCache {
    private final RouterContext _context;
    private final ObjectCounter<Hash> counter;
    private final Map<Hash, Destination> badDests;
    private final Map<Hash, Long> failureTime;
    private final int _maxFails;
    private final SimpleTimer2.TimedEvent cleaner;
    private final long cleanTime;

    static final int MAX_FAILS = 3;
    private static final int MAX_FAILS_UNDER_STRESS = 5;
    static final int MAX_BAD_DESTS = 128;
    private static final long CLEAN_TIME = 2*60*1000;
    private static final long CLEAN_TIME_UNDER_STRESS = 30*1000;
    private static final long CACHE_DURATION_NORMAL = 2*60*1000;
    private static final long CACHE_DURATION_UNDER_STRESS = 30*1000;
    private static final long STARTUP_PERIOD = 10*60*1000;
    private static final double STRESS_THRESHOLD = 0.40;

    public NegativeLookupCache(RouterContext context) {
        _context = context;
        this.counter = new ObjectCounter<Hash>();
        this.badDests = new LHMCache<Hash, Destination>(MAX_BAD_DESTS);
        this.failureTime = new LHMCache<Hash, Long>(MAX_BAD_DESTS);
        this._maxFails = context.getProperty("netdb.negativeCache.maxFails", MAX_FAILS);
        cleanTime = context.getProperty("netdb.negativeCache.cleanupInterval", CLEAN_TIME);
        cleaner = new Cleaner(context.simpleTimer2());
    }

    public void lookupFailed(Hash h) {
        this.counter.increment(h);
        synchronized(failureTime) {
            failureTime.put(h, Long.valueOf(_context.clock().now()));
        }
    }

    /**
     *  Negative cache the hash until the next clean time.
     *
     *  @since 0.9.56
     */
    public void cache(Hash h) {
        this.counter.max(h);
        synchronized(failureTime) {
            failureTime.put(h, Long.valueOf(_context.clock().now()));
        }
    }

    /**
     *  Clear a hash from the cache (e.g., after successful lookup).
     *
     *  @since 0.9.68+
     */
    public void clearHash(Hash h) {
        this.counter.clear(h);
        synchronized(failureTime) {
            failureTime.remove(h);
        }
    }

    public boolean isCached(Hash h) {
        int maxFails = getMaxFails();
        int count = counter.count(h);
        if (count >= maxFails) {
            if (isUnderStress()) {
                long cacheDuration = getCacheDuration();
                Long failTime;
                synchronized(failureTime) {
                    failTime = failureTime.get(h);
                }
                if (failTime != null) {
                    long elapsed = _context.clock().now() - failTime.longValue();
                    if (elapsed > cacheDuration) {
                        counter.clear(h);
                        synchronized(failureTime) {
                            failureTime.remove(h);
                        }
                        return false;
                    }
                }
            }
            return true;
        }
        synchronized(badDests) {
            return badDests.get(h) != null;
        }
    }

    /**
     *  Negative cache the hash until restart,
     *  but cache the destination.
     *
     *  @since 0.9.16
     */
    public void failPermanently(Destination dest) {
        Hash h = dest.calculateHash();
        synchronized(badDests) {
            badDests.put(h, dest);
        }
    }

    /**
     *  Get an unsupported but cached Destination
     *
     *  @return dest or null if not cached
     *  @since 0.9.16
     */
    public Destination getBadDest(Hash h) {
        synchronized(badDests) {
            return badDests.get(h);
        }
    }

    /**
     *  @since 0.9.16
     */
    public void clear() {
        counter.clear();
        synchronized(badDests) {
            badDests.clear();
        }
        synchronized(failureTime) {
            failureTime.clear();
        }
    }

    /**
     *  Stops the timer. May not be restarted.
     *
     *  @since 0.9.61
     */
    public void stop() {
        clear();
        cleaner.cancel();
    }

    private boolean isUnderStress() {
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        long uptime = _context.router().getUptime();
        return buildSuccess < STRESS_THRESHOLD || uptime < STARTUP_PERIOD;
    }

    private int getMaxFails() {
        if (isUnderStress()) {
            return MAX_FAILS_UNDER_STRESS;
        }
        return _maxFails;
    }

    private long getCacheDuration() {
        if (isUnderStress()) {
            return CACHE_DURATION_UNDER_STRESS;
        }
        return CACHE_DURATION_NORMAL;
    }

    private long getCleanTime() {
        if (isUnderStress()) {
            return CLEAN_TIME_UNDER_STRESS;
        }
        return cleanTime;
    }

    private class Cleaner extends SimpleTimer2.TimedEvent {
        public Cleaner(SimpleTimer2 pool) {
            super(pool, cleanTime);
        }

        public void timeReached() {
            if (isUnderStress()) {
                long now = _context.clock().now();
                long cacheDuration = getCacheDuration();
                List<Hash> toRemove = new ArrayList<Hash>();
                synchronized(failureTime) {
                    for (Map.Entry<Hash, Long> entry : failureTime.entrySet()) {
                        if (now - entry.getValue().longValue() > cacheDuration) {
                            toRemove.add(entry.getKey());
                        }
                    }
                    for (Hash h : toRemove) {
                        counter.clear(h);
                        failureTime.remove(h);
                    }
                }
            } else {
                counter.clear();
                synchronized(failureTime) {
                    failureTime.clear();
                }
            }
            reschedule(getCleanTime());
        }
    }
}
