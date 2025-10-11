package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Tracks the frequency of recent lookup requests targeting a specific reply peer/tunnel pair
 * to provide basic denial-of-service (DOS) protection by banning excessive requesters.
 * <p>
 * Uses burst detection over a sliding 1-second window and an overall lookup count threshold
 * to decide when to ban peers. Bans last for a fixed duration and expire automatically.
 * <p>
 * Thread-safe implementation utilizing concurrent data structures and finer-grained synchronization
 * for improved performance under concurrent access.
 * <p>
 * This mechanism is a partial DOS protection and does not prevent spoofed reply identifiers or multiple reply tunnels.
 *
 * @since 0.9.59
 */
class LookupBanHammer {
    // Counter for total lookup requests by ReplyTunnel
    private final ObjectCounter<ReplyTunnel> counter;

    // Concurrent map tracking timestamps of recent requests per ReplyTunnel for burst detection
    private final ConcurrentHashMap<ReplyTunnel, ConcurrentLinkedDeque<Long>> burstTimestamps;

    // Concurrent map storing expiration times of active bans per ReplyTunnel
    private final ConcurrentHashMap<ReplyTunnel, Long> banExpiration;

    // Dummy TunnelId used to represent null TunnelId values in ReplyTunnel keys
    private static final TunnelId DUMMY_ID = new TunnelId();

    // Maximum allowed lookups before triggering ban based on total count
    private static final int MAX_LOOKUPS = 60;

    // Timer interval for periodic cleaner task (30 seconds)
    private static final long CLEAN_TIME = 30 * 1000L;

    // Burst ban parameters: number of requests and time window in ms
    private static final int BURST_THRESHOLD_FOR_BAN = 10; // Requests per second to trigger ban
    private static final long BURST_WINDOW_MS = 1000L;

    // Duration of ban in milliseconds (5 minutes)
    private static final long BAN_DURATION_MS = 5 * 60 * 1000L;

    /**
     * Constructs a newly initialized LookupBanHammer instance.
     * Registers a periodic cleanup event to remove expired bans and reset counters.
     */
    LookupBanHammer() {
        this.counter = new ObjectCounter<>();
        this.burstTimestamps = new ConcurrentHashMap<>();
        this.banExpiration = new ConcurrentHashMap<>();
        SimpleTimer2.getInstance().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /**
     * Records a lookup request from a requester identified by key and tunnel ID,
     * and determines whether the requester should be banned based on recent activity.
     * <p>
     * This method increments the request count for the requester, tracks burst-frequency
     * within a sliding window, and enforces bans if thresholds are exceeded.
     *
     * @param key non-null Hash representing the requester identifying key
     * @param id TunnelId of the target reply tunnel, or null if direct lookups
     * @return true if the requester is currently banned and should be blocked; false otherwise
     */
    boolean shouldBan(Hash key, TunnelId id) {
        ReplyTunnel rt = new ReplyTunnel(key, id);
        long now = System.currentTimeMillis();

        // Quick check if the requester is banned with active ban expiration
        Long banUntil = banExpiration.get(rt);
        if (banUntil != null) {
            if (now < banUntil) {
                return true;
            } else {
                banExpiration.remove(rt);
            }
        }

        // Track burst timestamps for this requester within the BURST_WINDOW_MS sliding window
        ConcurrentLinkedDeque<Long> deque = burstTimestamps.computeIfAbsent(rt, k -> new ConcurrentLinkedDeque<>());
        synchronized (deque) {
            // Remove timestamps outside the burst window
            while (!deque.isEmpty() && (now - deque.peekFirst() > BURST_WINDOW_MS)) {
                deque.pollFirst();
            }
            deque.addLast(now);
            if (deque.size() > BURST_THRESHOLD_FOR_BAN) {
                banExpiration.put(rt, now + BAN_DURATION_MS);
                return true;
            }
        }

        // Check overall lookup count threshold
        return this.counter.increment(rt) > MAX_LOOKUPS * 2;
    }

    /**
     * Periodic cleanup task that clears counters and timestamps,
     * and removes any expired bans from the ban expiration map.
     */
    private class Cleaner implements SimpleTimer.TimedEvent {
        @Override
        public void timeReached() {
            long now = System.currentTimeMillis();
            counter.clear();
            burstTimestamps.clear();
            banExpiration.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
    }

    /**
     * Key class representing a reply peer/tunnel combination.
     * Implements equality and hash code based on both Hash key and TunnelId.
     * Caches hashcode for performance on repeated access.
     */
    private static class ReplyTunnel {
        public final Hash h;
        public final TunnelId id;
        private final int cachedHash;

        ReplyTunnel(Hash h, TunnelId id) {
            this.h = h;
            this.id = (id != null) ? id : DUMMY_ID;
            this.cachedHash = this.h.hashCode() ^ this.id.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ReplyTunnel)) return false;
            ReplyTunnel other = (ReplyTunnel) obj;
            return this.h.equals(other.h) && this.id.equals(other.id);
        }

        @Override
        public int hashCode() {
            return cachedHash;
        }
    }
}
