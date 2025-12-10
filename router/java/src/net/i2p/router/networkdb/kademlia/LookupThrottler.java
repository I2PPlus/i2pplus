package net.i2p.router.networkdb.kademlia;

import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;
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
    private final ObjectCounter<ReplyTunnel> counter;
    // Map to track timestamps of recent requests per ReplyTunnel for burst detection
    private final Map<ReplyTunnel, Deque<Long>> burstTimestamps;

    /** the id of this is -1 */
    private static final TunnelId DUMMY_ID = new TunnelId();
    /** this seems like plenty */
    private static final int DEFAULT_MAX_LOOKUPS = 30;
    private static final int DEFAULT_MAX_NON_FF_LOOKUPS = 10;
    private static final long DEFAULT_CLEAN_TIME = 3*60*1000;
    // Max requests allowed in 1-second burst window
    private static final int BURST_THRESHOLD = 5;
    private static final long BURST_WINDOW_MS = 1000L;

    private final int MAX_LOOKUPS;
    private final int MAX_NON_FF_LOOKUPS;
    private final long CLEAN_TIME;
    private final FloodfillNetworkDatabaseFacade _facade;
    private int _max;

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
        this.counter = new ObjectCounter<ReplyTunnel>();
        this.burstTimestamps = new HashMap<>();
        SimpleTimer2.getInstance().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
        _max = _facade.floodfillEnabled() ? MAX_LOOKUPS : MAX_NON_FF_LOOKUPS;
    }

    /**
     * increments and checks throttling
     * @param key non-null
     * @param id null if for direct lookups
     * @return true if throttled
     */
    boolean shouldThrottle(Hash key, TunnelId id) {
        ReplyTunnel rt = new ReplyTunnel(key, id);

        // Update burst timestamps
        long now = System.currentTimeMillis();
        synchronized (burstTimestamps) {
            Deque<Long> deque = burstTimestamps.get(rt);
            if (deque == null) {
                deque = new LinkedList<>();
                burstTimestamps.put(rt, deque);
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

        // Existing counting throttle
        return this.counter.increment(rt) > _max;
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            LookupThrottler.this.counter.clear();
            synchronized (burstTimestamps) {
                burstTimestamps.clear();
            }
            _max = _facade.floodfillEnabled() ? MAX_LOOKUPS : MAX_NON_FF_LOOKUPS;
        }
    }

    /** yes, we could have a two-level lookup, or just do h.tostring() + id.tostring() */
    private static class ReplyTunnel {
        public final Hash h;
        public final TunnelId id;

        ReplyTunnel(Hash h, TunnelId id) {
            this.h = h;
            if (id != null) {
                this.id = id;
            } else {
                this.id = DUMMY_ID;
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || !(obj instanceof ReplyTunnel)) {
                return false;
            }
            ReplyTunnel other = (ReplyTunnel) obj;
            return this.h.equals(other.h) && this.id.equals(other.id);
        }

        @Override
        public int hashCode() {
            return this.h.hashCode() ^ this.id.hashCode();
        }
    }
}
