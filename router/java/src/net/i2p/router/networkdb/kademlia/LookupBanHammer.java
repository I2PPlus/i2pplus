package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.util.SimpleTimer2;

/**
 * Tracks lookup frequency per (peer, tunnel) pair to provide DOS protection
 * via temporary bans on excessive requesters. Uses burst detection over a
 * 1-second window and sustained-rate threshold over a 30-second sliding window.
 * Maps are size-bounded to prevent memory exhaustion.
 *
 * @since 0.9.59
 */
class LookupBanHammer {
    private final ConcurrentHashMap<ReplyTunnel, ConcurrentLinkedDeque<Long>> burstTimestamps;
    private final ConcurrentHashMap<ReplyTunnel, Long> banExpiration;

    private static final TunnelId DUMMY_ID = new TunnelId();
    private static final int MAX_LOOKUPS = 120;
    private static final long CLEAN_TIME = 30 * 1000L;
    private static volatile int _burstThreshold = 10;
    private static final long BURST_WINDOW_MS = 1000L;
    private static final long BAN_DURATION_MS = 5 * 60 * 1000L;
    private static volatile int _maxEntries = 50000;
    static volatile long _cleanTimeMs = CLEAN_TIME;

    private final Cleaner _cleaner;

    LookupBanHammer() {
        burstTimestamps = new ConcurrentHashMap<ReplyTunnel, ConcurrentLinkedDeque<Long>>();
        banExpiration = new ConcurrentHashMap<ReplyTunnel, Long>();
        _cleaner = new Cleaner();
        _cleaner.schedule(CLEAN_TIME);
    }

    void cancel() { _cleaner.cancel(); }

    /** @since 0.9.70+ */
    static void setMaxEntries(int max) {
        _maxEntries = Math.max(1000, Math.min(200000, max));
    }

    static int getMaxEntries() { return _maxEntries; }

    /** @since 0.9.70+ */
    static void setCleanTimeMs(long ms) {
        _cleanTimeMs = Math.max(5000, Math.min(120000, ms));
    }

    /** @since 0.9.70+ */
    static void setBurstThreshold(int t) {
        _burstThreshold = Math.max(2, Math.min(100, t));
    }

    /**
     * Record a lookup and check if the requester should be banned.
     * @param key requester Hash
     * @param id reply tunnel, or null for direct
     * @return true if currently banned
     */
    boolean shouldBan(Hash key, TunnelId id) {
        ReplyTunnel rt = new ReplyTunnel(key, id);
        long now = System.currentTimeMillis();
        if (isBanned(rt, now))
            return true;
        ConcurrentLinkedDeque<Long> deque = burstTimestamps.computeIfAbsent(rt, k -> new ConcurrentLinkedDeque<Long>());
        synchronized (deque) {
            slideWindow(deque, now);
            deque.addLast(now);
            if (burstCount(deque, now) > _burstThreshold)
                return imposeBan(rt, now);
            if (deque.size() > MAX_LOOKUPS)
                return imposeBan(rt, now);
        }
        return false;
    }

    /** @return true if ban is active; removes expired entries. */
    private boolean isBanned(ReplyTunnel rt, long now) {
        Long until = banExpiration.get(rt);
        if (until == null) return false;
        if (now < until) return true;
        banExpiration.remove(rt);
        return false;
    }

    /** Remove entries older than 30s from the deque. */
    private static void slideWindow(ConcurrentLinkedDeque<Long> deque, long now) {
        while (!deque.isEmpty() && (now - deque.peekFirst() > 30000))
            deque.pollFirst();
    }

    /** Count entries in the last 1-second window (newest-first iteration). */
    private static int burstCount(ConcurrentLinkedDeque<Long> deque, long now) {
        int count = 0;
        Iterator<Long> it = deque.descendingIterator();
        while (it.hasNext()) {
            if (now - it.next() <= BURST_WINDOW_MS)
                count++;
            else
                break;
        }
        return count;
    }

    /** Record a ban, evicting the oldest if at capacity. Returns true. */
    private boolean imposeBan(ReplyTunnel rt, long now) {
        if (banExpiration.size() >= _maxEntries)
            evictOneBan();
        banExpiration.put(rt, now + BAN_DURATION_MS);
        burstTimestamps.remove(rt);
        return true;
    }

    /** Evict the first available ban entry (the Cleaner handles bulk expiry). */
    private void evictOneBan() {
        for (Map.Entry<ReplyTunnel, Long> e : banExpiration.entrySet()) {
            banExpiration.remove(e.getKey());
            return;
        }
    }

    /** Periodic cleanup: remove expired bans, prune idle burst trackers, size-bound. */
    private class Cleaner extends SimpleTimer2.TimedEvent {
        public Cleaner() { super(SimpleTimer2.getInstance()); }

        @Override
        public void timeReached() {
            long now = System.currentTimeMillis();
            banExpiration.entrySet().removeIf(e -> e.getValue() <= now);
            if (!burstTimestamps.isEmpty()) {
                long cutoff = now - 30000;
                burstTimestamps.entrySet().removeIf(e -> {
                    Long last = e.getValue().peekLast();
                    return last == null || last < cutoff;
                });
            }
            if (burstTimestamps.size() > _maxEntries) {
                int over = burstTimestamps.size() - _maxEntries;
                List<Long> sample = new ArrayList<Long>();
                int s = 0;
                for (ConcurrentLinkedDeque<Long> d : burstTimestamps.values()) {
                    if (s++ >= 8192) break;
                    Long last = d.peekLast();
                    sample.add(last == null ? Long.MAX_VALUE : last);
                }
                if (!sample.isEmpty()) {
                    Collections.sort(sample);
                    int idx = Math.min((over * sample.size()) / burstTimestamps.size(), sample.size() - 1);
                    long cutoff = sample.get(idx);
                    burstTimestamps.entrySet().removeIf(e -> {
                        Long last = e.getValue().peekLast();
                        return last != null && last <= cutoff;
                    });
                }
            }
            long interval = _cleanTimeMs;
            if (burstTimestamps.size() + banExpiration.size() > _maxEntries * 3 / 2)
                interval = Math.max(interval / 6, 1000);
            reschedule(interval);
        }
    }

    /** (peer, tunnel) key with cached hash. */
    private static class ReplyTunnel {
        public final Hash h;
        public final TunnelId id;
        private final int cachedHash;

        ReplyTunnel(Hash h, TunnelId id) {
            this.h = h;
            this.id = (id != null) ? id : DUMMY_ID;
            this.cachedHash = h.hashCode() ^ this.id.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ReplyTunnel)) return false;
            ReplyTunnel o = (ReplyTunnel) obj;
            return id.equals(o.id) && h.equals(o.h);
        }

        @Override
        public int hashCode() { return cachedHash; }
    }
}
