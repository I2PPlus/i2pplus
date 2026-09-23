package net.i2p.router.networkdb.kademlia;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * or malicious peers. Uses per-entry TTL with backoff instead of a wholesale
 * counter wipe: a key that trips the threshold stays cached for
 * {@link #entryTtlMs(int) entryTtl(tripGeneration)} from its last fail, and
 * re-trips extend that hold up to {@link #ENTRY_TTL_MAX_MS}. Clearing all
 * keys on a timer synchronized re-probe storms across every recently-failed
 * destination at once.
 * <p>
 * Partial (below-threshold) fail streaks still expire after
 * {@code netdb.negativeCache.cleanupInterval} so the historical contract
 * "N fails within the cleaner window" is preserved per key, without
 * resetting unrelated keys.
 * <p>
 * Thread-safe implementation with atomic counters and scheduled cleanup
 * operations for reliable concurrent access in multi-threaded environments.
 *
 * @since 0.9.56
 */
class NegativeLookupCache {
    private final ObjectCounter<Hash> counter;
    private final ObjectCounter<Hash> timeoutCounter;
    private final Map<Hash, Destination> badDests;
    /** Last time a fail was recorded for the key (streak / age tracking). */
    private final Map<Hash, Long> lastFailAt;
    /** Epoch ms until which a tripped key remains negatively cached. */
    private final Map<Hash, Long> cachedUntil;
    /** How many times the key has tripped the threshold (TTL backoff). */
    private final Map<Hash, Integer> tripCount;
    private final int _maxFails;
    private final int _maxTimeoutFails;
    private final SimpleTimer2.TimedEvent cleaner;
    private final long cleanTime;
    private final long entryTtlBase;
    private final long entryTtlMax;
    private final RouterContext _context;

    /** Maximum failures before caching (definitive peer replies, zero-lease). */
    static final int MAX_FAILS = 6;
    /** Pure-timeout failures trip at a higher bar so a brief outage doesn't lock a live key. */
    static final int MAX_TIMEOUT_FAILS = MAX_FAILS * 2;
    private static final int MAX_BAD_DESTS = 128;
    private static final long CLEAN_TIME = 2*60*1000L;
    /** First-trip hold once the threshold is crossed (ms). */
    static final long ENTRY_TTL_BASE_MS = 30 * 1000L;
    /** Cap on entry TTL after backoff (ms). */
    static final long ENTRY_TTL_MAX_MS = 2 * 60 * 1000L;
    /** Backoff shift per trip generation before hitting the cap (2^n). */
    private static final int MAX_TTL_SHIFT = 3;

    /**
     * Creates a new NegativeLookupCache.
     *
     * @param context the router context
     */
    public NegativeLookupCache(RouterContext context) {
        this._context = context;
        this.counter = new ObjectCounter<>();
        this.timeoutCounter = new ObjectCounter<>();
        this.badDests = new LHMCache<>(MAX_BAD_DESTS);
        this.lastFailAt = new ConcurrentHashMap<>();
        this.cachedUntil = new ConcurrentHashMap<>();
        this.tripCount = new ConcurrentHashMap<>();
        this._maxFails = context.getProperty("netdb.negativeCache.maxFails",MAX_FAILS);
        this._maxTimeoutFails = context.getProperty("netdb.negativeCache.maxTimeoutFails", MAX_TIMEOUT_FAILS);
        cleanTime = context.getProperty("netdb.negativeCache.cleanupInterval", CLEAN_TIME);
        entryTtlBase = context.getProperty("netdb.negativeCache.entryTtlBase", ENTRY_TTL_BASE_MS);
        entryTtlMax = context.getProperty("netdb.negativeCache.entryTtlMax", ENTRY_TTL_MAX_MS);
        cleaner = new Cleaner(context.simpleTimer2());
    }

    /**
     * Pure decision: TTL for a key that has tripped the threshold
     * {@code tripGeneration} times (1 = first trip). Doubling backoff,
     * capped at {@link #ENTRY_TTL_MAX_MS} (overridable max applies at call).
     *
     * @param tripGeneration how many times this key has tripped (0 or less treated as 1)
     * @return hold time in ms for the first-trip base of 30s
     * @since 0.9.71+
     */
    static long entryTtlMs(int tripGeneration) {
        int gen = Math.max(tripGeneration, 1);
        int shift = Math.min(gen - 1, MAX_TTL_SHIFT);
        long ttl = ENTRY_TTL_BASE_MS << shift;
        return Math.min(ttl, ENTRY_TTL_MAX_MS);
    }

    /**
     * Pure decision: has a tripped entry's TTL expired?
     *
     * @param cachedUntil epoch ms at which the entry becomes expired (inclusive)
     * @param now current epoch ms
     * @return true if the entry should be expired
     * @since 0.9.71+
     */
    static boolean isEntryExpired(long cachedUntil, long now) {
        return now >= cachedUntil;
    }

    /**
     * Pure decision: has a below-threshold fail streak gone stale?
     *
     * @param lastFailAt epoch ms of the last fail for the key, or -1 if none
     * @param now current epoch ms
     * @param streakWindowMs how long a partial streak remains valid (age &gt;= window marks stale)
     * @return true if partial counts should be discarded
     * @since 0.9.71+
     */
    static boolean isStreakStale(long lastFailAt, long now, long streakWindowMs) {
        return lastFailAt >= 0 && now - lastFailAt >= streakWindowMs;
    }

    /**
     * Record a failed lookup for the given hash.
     * Counts toward the strict threshold (definitive peer replies).
     *
     * @param h the hash that failed lookup
     */
    public void lookupFailed(Hash h) {
        long now = _context.clock().now();
        refreshStreak(h, now);
        boolean wasTripped = isTripped(h);
        this.counter.increment(h);
        lastFailAt.put(h, now);
        maybeTrip(h, now, wasTripped);
    }

    /**
     * Record a pure-timeout search failure (no peer ever replied).
     * Uses a higher threshold so a brief tunnel outage doesn't lock a live key.
     *
     * @param h the hash that timed out
     * @since 0.9.71+
     */
    public void lookupTimeout(Hash h) {
        long now = _context.clock().now();
        refreshStreak(h, now);
        boolean wasTripped = isTripped(h);
        this.timeoutCounter.increment(h);
        lastFailAt.put(h, now);
        maybeTrip(h, now, wasTripped);
    }

    /**
     * Pure decision: does this failure count toward the strict threshold?
     * Zero-peer failures are local (job drop, no tunnels) and must not poison the key.
     *
     * @param peersQueried number of peers actually sent a query
     * @return true if the failure should use {@link #lookupFailed(Hash)}
     * @since 0.9.71+
     */
    static boolean countsAsDefinitiveFail(int peersQueried) {
        return peersQueried > 0;
    }

    /**
     * Negative cache the hash for one entry-TTL generation.
     *
     * @param h the hash to cache
     * @since 0.9.56
     */
    public void cache(Hash h) {
        long now = _context.clock().now();
        this.counter.max(h);
        lastFailAt.put(h, now);
        maybeTrip(h, now, false);
    }

    /**
     * Check if the hash is in the negative cache.
     * Lazily expires per-entry TTLs and stale fail streaks.
     *
     * @param h the hash to check
     * @return true if the hash is cached
     */
    public boolean isCached(Hash h) {
        expireDue(h, _context.clock().now());
        if (counter.count(h) >= _maxFails)
            return true;
        if (timeoutCounter.count(h) >= _maxTimeoutFails)
            return true;
        synchronized(badDests) {
            return badDests.get(h) != null;
        }
    }

    /**
     * Negative cache the hash permanently until restart,
     * but cache the destination.
     *
     * @param dest the destination to permanently fail
     * @since 0.9.16
     */
    public void failPermanently(Destination dest) {
        Hash h = dest.calculateHash();
        synchronized(badDests) {
            badDests.put(h, dest);
        }
    }

    /**
     * Cached bad destination for the given hash.
     *
     * @param h the hash to look up
     * @return the destination or null if not cached
     * @since 0.9.16
     */
    public Destination getBadDest(Hash h) {
        synchronized(badDests) {
            return badDests.get(h);
        }
    }

    /**
     * Clears the transient failure count for the given hash only,
     * including its trip generation (next trip starts at base TTL).
     * Permanent negative entries ({@link #failPermanently}) are left intact,
     * so a confirmed-bad destination cannot be re-probed into searching.
     *
     * @param h the hash to clear
     * @since 0.9.71+
     */
    public void clear(Hash h) {
        counter.clear(h);
        timeoutCounter.clear(h);
        lastFailAt.remove(h);
        cachedUntil.remove(h);
        tripCount.remove(h);
    }

    /**
     * Clears the negative cache and bad destinations.
     *
     * @since 0.9.16
     */
    public void clear() {
        counter.clear();
        timeoutCounter.clear();
        lastFailAt.clear();
        cachedUntil.clear();
        tripCount.clear();
        synchronized(badDests) {
            badDests.clear();
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

    /** @return true if either counter is at or over its cache threshold. */
    private boolean isTripped(Hash h) {
        return counter.count(h) >= _maxFails
            || timeoutCounter.count(h) >= _maxTimeoutFails;
    }

    /**
     * If the key already has a per-entry TTL, drop counts when due (lazy expire).
     * Does not touch permanent {@link #badDests}.
     */
    private void expireDue(Hash h, long now) {
        Long until = cachedUntil.get(h);
        if (until != null && isEntryExpired(until, now)) {
            counter.clear(h);
            timeoutCounter.clear(h);
            cachedUntil.remove(h);
            lastFailAt.remove(h);
            // tripCount intentionally kept for backoff on re-trip
        }
    }

    /**
     * Discard a below-threshold fail streak that has outlived
     * {@code cleanTime} so "N fails within the window" still holds
     * without a global wipe.
     */
    private void refreshStreak(Hash h, long now) {
        expireDue(h, now);
        if (cachedUntil.containsKey(h)) {
            // still tripped; just record the fail time for age
            return;
        }
        Long last = lastFailAt.get(h);
        if (last != null && isStreakStale(last, now, cleanTime)) {
            counter.clear(h);
            timeoutCounter.clear(h);
        }
    }

    /**
     * On the transition into the cached state, set a per-entry TTL
     * with backoff from how many times this key has tripped before.
     */
    private void maybeTrip(Hash h, long now, boolean wasTripped) {
        if (wasTripped || !isTripped(h)) {
            return;
        }
        int gen = tripCount.merge(h, 1, Integer::sum);
        long ttl = Math.min(entryTtlMs(gen), entryTtlMax);
        cachedUntil.put(h, now + ttl);
    }

    private class Cleaner extends SimpleTimer2.TimedEvent {
        /**
         *  Schedules itself.
         *
         *  @since 0.9.61
         */
        public Cleaner(SimpleTimer2 pool) {
            super(pool, cleanTime);
        }

        public void timeReached() {
            long now = _context.clock().now();
            for (Map.Entry<Hash, Long> e : cachedUntil.entrySet()) {
                if (isEntryExpired(e.getValue(), now)) {
                    Hash h = e.getKey();
                    counter.clear(h);
                    timeoutCounter.clear(h);
                    lastFailAt.remove(h);
                }
            }
            cachedUntil.entrySet().removeIf(e -> e.getValue() <= now);
            for (Map.Entry<Hash, Long> e : lastFailAt.entrySet()) {
                Hash h = e.getKey();
                if (cachedUntil.containsKey(h)) {
                    continue;
                }
                if (isStreakStale(e.getValue(), now, cleanTime)) {
                    counter.clear(h);
                    timeoutCounter.clear(h);
                }
            }
            lastFailAt.entrySet().removeIf(e ->
                !cachedUntil.containsKey(e.getKey()) && isStreakStale(e.getValue(), now, cleanTime));
            reschedule(cleanTime);
        }
    }
}
