package net.i2p.router.networkdb.kademlia;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Tracks the frequency of recent lookup requests targeting a specific reply peer/tunnel pair
 * to provide basic denial-of-service (DOS) protection by banning excessive requesters.
 * <p>
 * Uses burst detection over a 1-second window and a sustained-rate threshold over a
 * 30-second sliding window (combined into a single deque per key) to decide when to ban.
 * Bans last for a fixed duration and expire automatically.
 * <p>
 * Thread-safe implementation utilizing concurrent data structures and finer-grained synchronization
 * for improved performance under concurrent access.
 * <p>
 * All internal maps are size-bounded to prevent memory exhaustion under high lookup volume.
 *
 * @since 0.9.59
 */
class LookupBanHammer {
    // Concurrent map tracking timestamps of recent requests per ReplyTunnel for burst + sustained detection.
    // Uses a unified 30-second sliding window (trimmed inline during shouldBan()) so no periodic clearing is needed.
    // Each value is a ConcurrentLinkedDeque of epoch-millisecond timestamps.
    private final ConcurrentHashMap<ReplyTunnel, ConcurrentLinkedDeque<Long>> burstTimestamps;

    // Concurrent map storing expiration times of active bans per ReplyTunnel
    private final ConcurrentHashMap<ReplyTunnel, Long> banExpiration;

    // Dummy TunnelId used to represent null TunnelId values in ReplyTunnel keys
    private static final TunnelId DUMMY_ID = new TunnelId();

    // Maximum allowed lookups in 30-second sliding window before sustained-rate ban
    private static final int MAX_LOOKUPS = 120;

    // Timer interval for periodic cleaner task (30 seconds)
    private static final long CLEAN_TIME = 30 * 1000L;

    // Burst ban parameters: number of requests and time window in ms
    private static final int BURST_THRESHOLD_FOR_BAN = 10; // Requests per second to trigger ban
    private static final long BURST_WINDOW_MS = 1000L;

    // Duration of ban in milliseconds (5 minutes)
    private static final long BAN_DURATION_MS = 5 * 60 * 1000L;

    // Hard cap on unique (from, tunnel) pairs tracked to prevent memory leaks
    private static volatile int _maxEntries = 50000;

    // Cleaner interval override (ms), controlled by Tuner
    static volatile long _cleanTimeMs = CLEAN_TIME;

    /**
     * Constructs a newly initialized LookupBanHammer instance.
     * Registers a periodic cleanup event to remove expired bans.
     */
    LookupBanHammer() {
        this.burstTimestamps = new ConcurrentHashMap<ReplyTunnel, ConcurrentLinkedDeque<Long>>();
        this.banExpiration = new ConcurrentHashMap<ReplyTunnel, Long>();
        new Cleaner().schedule(CLEAN_TIME);
    }

    /**
     * Update the max entries cap (called by Tuner).
     * @since 0.9.70+
     */
    static void setMaxEntries(int max) {
        _maxEntries = Math.max(1000, Math.min(200000, max));
    }

    /**
     * @return current max entries cap
     * @since 0.9.70+
     */
    static int getMaxEntries() { return _maxEntries; }

    /**
     * Update the cleaner interval (called by Tuner).
     * @since 0.9.70+
     */
    static void setCleanTimeMs(long ms) {
        _cleanTimeMs = Math.max(5000, Math.min(120000, ms));
    }

    /**
     * Update the burst threshold (called by Tuner).
     * @since 0.9.70+
     */
    static void setBurstThreshold(int t) {
        // no-op for now; threshold is a constant
    }

    /**
     * Records a lookup request from a requester identified by key and tunnel ID,
     * and determines whether the requester should be banned based on recent activity.
     * <p>
     * Uses a single 30-second sliding window per key for both burst detection
     * (entries in last 1 second) and sustained-rate detection (total entries).
     * Inline trimming removes entries older than 30s, avoiding the need for
     * periodic burstTimestamps clearing and the associated GC churn.
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

        // Unified sliding window for burst + sustained rate detection.
        // Inline trimming removes entries &gt;30s old, so no periodic clearing is needed.
        ConcurrentLinkedDeque<Long> deque = burstTimestamps.computeIfAbsent(rt, k -> new ConcurrentLinkedDeque<Long>());
        synchronized (deque) {
            // Slide window: remove entries older than 30 seconds
            while (!deque.isEmpty() && (now - deque.peekFirst() > 30000)) {
                deque.pollFirst();
            }
            deque.addLast(now);

            // Count entries in the last 1 second for burst detection.
            // Since entries are ordered (oldest first), iterate from the end.
            int burstCount = 0;
            Iterator<Long> descIt = deque.descendingIterator();
            while (descIt.hasNext()) {
                if (now - descIt.next() <= BURST_WINDOW_MS) {
                    burstCount++;
                } else {
                    break;
                }
            }

            // Burst ban: 10+ requests in 1 second
            if (burstCount > BURST_THRESHOLD_FOR_BAN) {
                if (banExpiration.size() >= _maxEntries) {
                    expireOrEvictBan(now);
                }
                banExpiration.put(rt, now + BAN_DURATION_MS);
                // Once banned, the burstTimestamps entry is dead — every future
                // request hits banExpiration.get() first and returns true immediately.
                // Remove it to free the ReplyTunnel key.
                burstTimestamps.remove(rt);
                return true;
            }

            // Sustained-rate ban: 120+ requests in 30-second window
            if (deque.size() > MAX_LOOKUPS) {
                if (banExpiration.size() >= _maxEntries) {
                    expireOrEvictBan(now);
                }
                banExpiration.put(rt, now + BAN_DURATION_MS);
                // Same rationale as above — no need to keep tracking rate for a banned source.
                burstTimestamps.remove(rt);
                return true;
            }
        }

        return false;
    }

    /**
     * Try to make room in the ban map. Remove expired entries first.
     * If still full, remove a random entry (any single ban is expendable
     * since the burst detector will re-ban on the next burst).
     */
    private void expireOrEvictBan(long now) {
        for (Map.Entry<ReplyTunnel, Long> e : banExpiration.entrySet()) {
            if (e.getValue() <= now) {
                banExpiration.remove(e.getKey());
                return;
            }
        }
        Map.Entry<ReplyTunnel, Long> first = null;
        for (Map.Entry<ReplyTunnel, Long> e : banExpiration.entrySet()) {
            first = e;
            break;
        }
        if (first != null) {
            banExpiration.remove(first.getKey());
        }
    }

    /**
     * Periodic cleanup task that removes expired bans and prunes stale
     * burstTimestamps entries. The burstTimestamps map entries accumulate
     * indefinitely unless we age them out here — "self-cleaning via sliding
     * window" in shouldBan() only trims deque contents, not map entries.
     * Without this, every unique (Hash, TunnelId) pair that ever sends a
     * lookup creates a permanent ReplyTunnel entry, leaking ~24 bytes each.
     */
    private class Cleaner extends SimpleTimer2.TimedEvent {
        public Cleaner() { super(SimpleTimer2.getInstance()); }
        @Override
        public void timeReached() {
            long now = System.currentTimeMillis();
            int banSize = banExpiration.size();
            // Remove expired bans
            banExpiration.entrySet().removeIf(entry -> entry.getValue() <= now);
            // Remove stale burstTimestamps entries: empty deque or all entries > 30s old
            if (!burstTimestamps.isEmpty()) {
                long cutoff = now - 30000;
                burstTimestamps.entrySet().removeIf(entry -> {
                    Long last = entry.getValue().peekLast();
                    return last == null || last < cutoff;
                });
            }
            // Under heavy load, clean more frequently to stay bounded
            long interval = _cleanTimeMs;
            if (burstTimestamps.size() + banExpiration.size() > _maxEntries * 2) {
                interval = Math.max(interval / 6, 1000);
            }
            reschedule(interval);
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
