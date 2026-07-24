package net.i2p.router.networkdb.kademlia;

import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.util.SimpleTimer2;

/**
 * Provides denial-of-service protection for network database lookup operations.
 * <p>
 * Tracks the frequency of recent lookup requests targeting specific reply
 * peer/tunnel pairs to prevent abuse. Implements burst detection
 * over sliding time windows and configurable request limits.
 * <p>
 * Note this is basic DOS protection and does not prevent spoofed
 * reply identifiers or multiple reply tunnels from malicious requestors.
 *
 * @since 0.7.11
 */
class LookupThrottler {
    private final ConcurrentHashMap<Hash, ConcurrentHashMap<TunnelId, AtomicInteger>> counter;
    // Map to track timestamps of recent requests per (Hash, TunnelId) for burst detection.
    // Outer key is Hash (peer), inner key is TunnelId (reply tunnel).
    private final Map<Hash, ConcurrentHashMap<TunnelId, Deque<Long>>> burstTimestamps;

    /** the id of this is -1 */
    private static final TunnelId DUMMY_ID = new TunnelId();
    /** this seems like plenty */
    private static final int DEFAULT_MAX_LOOKUPS = 30;
    private static final int DEFAULT_MAX_NON_FF_LOOKUPS = 10;
    private static final long DEFAULT_CLEAN_TIME = 3*60*1000L;
    // Max requests allowed in 1-second burst window
    private static final int BURST_THRESHOLD = 5;
    private static final long BURST_WINDOW_MS = 1000L;
    /** Hard cap on unique (from, tunnel) pairs tracked to prevent memory leaks.
     *  At ~25 peak lookups/sec with a 3-min clean window, ~4,500 keys is sufficient.
     *  10,000 gives ~7 min headroom at peak — the clean runs every 3 min so this
     *  cap is only hit during sustained attack. */
    private static final int MAX_ENTRIES = 10000;

    private final int MAX_LOOKUPS;
    private final int MAX_NON_FF_LOOKUPS;
    private final long CLEAN_TIME;
    private final FloodfillNetworkDatabaseFacade _facade;
    private volatile int _max;
    private final Cleaner _cleaner;

    LookupThrottler(FloodfillNetworkDatabaseFacade facade) {
        this(facade, DEFAULT_MAX_LOOKUPS, DEFAULT_MAX_NON_FF_LOOKUPS, DEFAULT_CLEAN_TIME);
    }

    /**
     * @param maxlookups when floodfill
     * @param maxnonfflookups when not floodfill
     * @since 0.9.61
     */
    LookupThrottler(FloodfillNetworkDatabaseFacade facade, int maxlookups, int maxnonfflookups, long cleanTime) {
        _facade = facade;
        MAX_LOOKUPS = maxlookups;
        MAX_NON_FF_LOOKUPS = maxnonfflookups;
        CLEAN_TIME = cleanTime;
        this.counter = new ConcurrentHashMap<Hash, ConcurrentHashMap<TunnelId, AtomicInteger>>();
        this.burstTimestamps = new LinkedHashMap<Hash, ConcurrentHashMap<TunnelId, Deque<Long>>>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Hash, ConcurrentHashMap<TunnelId, Deque<Long>>> eldest) {
                // Evict the eldest Hash entry to stay bounded.
                // Cap is on unique Hash keys (not per-tunnel entries).
                // Do NOT clear the global counting throttle here - doing so would
                // reset every peer's burst count and let an attacker bypass the
                // counting throttle the moment the map fills (see ticket MEDIUM-2).
                return size() > MAX_ENTRIES;
            }
        };
        _cleaner = new Cleaner();
        _cleaner.schedule(CLEAN_TIME);
        _max = _facade.floodfillEnabled() ? MAX_LOOKUPS : MAX_NON_FF_LOOKUPS;
    }

    /** Stop the periodic cleaner. Call on facade shutdown. @since 0.9.70+ */
    void cancel() {
        _cleaner.cancel();
    }

    /**
     * increments and checks throttling
     * @param key non-null
     * @param id null if for direct lookups
     * @return true if throttled
     */
    boolean shouldThrottle(Hash key, TunnelId id) {
        TunnelId lookupId = (id != null) ? id : DUMMY_ID;
        long now = System.currentTimeMillis();

        // Burst detection
        synchronized (burstTimestamps) {
            ConcurrentHashMap<TunnelId, Deque<Long>> innerBurst = burstTimestamps.get(key);
            if (innerBurst == null) {
                innerBurst = new ConcurrentHashMap<TunnelId, Deque<Long>>();
                burstTimestamps.put(key, innerBurst);
            }
            Deque<Long> deque = innerBurst.get(lookupId);
            if (deque == null) {
                deque = new LinkedList<Long>();
                innerBurst.put(lookupId, deque);
            }
            // Remove timestamps older than BURST_WINDOW_MS
            while (!deque.isEmpty() && (now - deque.peekFirst() > BURST_WINDOW_MS)) {
                deque.pollFirst();
            }
            deque.addLast(now);
            if (deque.size() > BURST_THRESHOLD) {
                return true; // Throttle burst requests
            }
        }

        // Counting throttle
        ConcurrentHashMap<TunnelId, AtomicInteger> innerCount = counter.get(key);
        if (innerCount == null) {
            innerCount = new ConcurrentHashMap<TunnelId, AtomicInteger>();
            ConcurrentHashMap<TunnelId, AtomicInteger> existing = counter.putIfAbsent(key, innerCount);
            if (existing != null) {
                innerCount = existing;
            }
        }
        AtomicInteger ai = innerCount.get(lookupId);
        if (ai == null) {
            ai = new AtomicInteger();
            AtomicInteger existing = innerCount.putIfAbsent(lookupId, ai);
            if (existing != null) {
                ai = existing;
            }
        }
        return ai.incrementAndGet() > _max;
    }

    private class Cleaner extends SimpleTimer2.TimedEvent {
        public Cleaner() { super(SimpleTimer2.getInstance()); }
        @Override
        public void timeReached() {
            int size;
            synchronized (burstTimestamps) {
                size = burstTimestamps.size();
                burstTimestamps.clear();
            }
            counter.clear();
            // Under heavy load, clean more frequently to stay bounded
            if (size > MAX_ENTRIES) {
                reschedule(Math.max(CLEAN_TIME / 6, 1000));
            }
            _max = _facade.floodfillEnabled() ? MAX_LOOKUPS : MAX_NON_FF_LOOKUPS;
        }
    }

}
