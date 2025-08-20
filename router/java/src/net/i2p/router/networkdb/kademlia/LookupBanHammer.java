package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;

import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * Count how often we have recently received a lookup request with
 * the reply specified to go to a peer/TunnelId pair.
 * This offers basic DOS protection but is not a complete solution.
 * The reply peer/tunnel could be spoofed, for example.
 * And a requestor could have up to 6 reply tunnels.
 *
 * @since 0.9.59
 */
class LookupBanHammer {
    private final ObjectCounter<ReplyTunnel> counter;
    private final Map<ReplyTunnel, Deque<Long>> burstTimestamps;
    private final Map<ReplyTunnel, Long> banExpiration;

    private static final TunnelId DUMMY_ID = new TunnelId();
    private static final int MAX_LOOKUPS = 60;
    private static final long CLEAN_TIME = 30 * 1000;

    private static final int BURST_THRESHOLD_FOR_BAN = 10; // 10 requests per second triggers ban
    private static final long BURST_WINDOW_MS = 1000L;
    private static final long BAN_DURATION_MS = 5 * 60 * 1000L; // 5 minutes ban

    LookupBanHammer() {
        this.counter = new ObjectCounter<>();
        this.burstTimestamps = new HashMap<>();
        this.banExpiration = new HashMap<>();
        SimpleTimer2.getInstance().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /**
     * increments before checking ban
     * @param key non-null
     * @param id null if for direct lookups
     * @return true if requestor should be banned
     */
    boolean shouldBan(Hash key, TunnelId id) {
        ReplyTunnel rt = new ReplyTunnel(key, id);
        long now = System.currentTimeMillis();

        // Check active ban
        Long banUntil = banExpiration.get(rt);
        if (banUntil != null) {
            if (now < banUntil) {return true;}
            else {banExpiration.remove(rt);}
        }

        // Track burst timestamps for this ReplyTunnel
        synchronized (burstTimestamps) {
            Deque<Long> deque = burstTimestamps.get(rt);
            if (deque == null) {
                deque = new LinkedList<>();
                burstTimestamps.put(rt, deque);
            }
            while (!deque.isEmpty() && (now - deque.peekFirst() > BURST_WINDOW_MS)) {
                deque.pollFirst();
            }
            deque.addLast(now);
            if (deque.size() > BURST_THRESHOLD_FOR_BAN) {
                banExpiration.put(rt, now + BAN_DURATION_MS);
                return true;
            }
        }

        // Existing ban criteria by count
        return this.counter.increment(rt) > MAX_LOOKUPS * 2;
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            counter.clear();
            synchronized (burstTimestamps) {burstTimestamps.clear();}
            synchronized (banExpiration) {
                long now = System.currentTimeMillis();
                banExpiration.entrySet().removeIf(entry -> entry.getValue() <= now);
            }
        }
    }

    private static class ReplyTunnel {
        public final Hash h;
        public final TunnelId id;

        ReplyTunnel(Hash h, TunnelId id) {
            this.h = h;
            this.id = (id != null) ? id : DUMMY_ID;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ReplyTunnel)) return false;
            ReplyTunnel other = (ReplyTunnel) obj;
            return this.h.equals(other.h) && this.id.equals(other.id);
        }

        @Override
        public int hashCode() {
            return this.h.hashCode() ^ this.id.hashCode();
        }
    }

}