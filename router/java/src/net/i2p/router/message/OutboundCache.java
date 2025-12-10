package net.i2p.router.message;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.SimpleTimer;

/**
 * Helper cache manager for outbound message optimization in OCMOSJ.
 *
 * <p>This class maintains several caches to optimize outbound message routing and delivery:
 * <ul>
 *   <li>LeaseSet cache - minimizes overhead by reusing our own LeaseSet.</li>
 *   <li>Lease cache - persists sending to the same lease to reduce out-of-order delivery.</li>
 *   <li>Tunnel caches - caches outbound tunnels per destination to stabilize delivery paths.</li>
 *   <li>Last reply request cache - ensures periodic replies to detect failed tunnels.</li>
 *   <li>Multihomed cache - tracks LeaseSets for multihomed routers.</li>
 * </ul>
 *
 * <p>All caches use ConcurrentHashMap with tuned initial capacity, load factor, and concurrency level
 * to allow safe concurrent access and high throughput with low contention.
 * Tunnel caches have been converted from HashMaps with explicit synchronization to ConcurrentHashMaps
 * for consistent concurrency management without external locking.
 *
 * <p>Cache cleaning occurs periodically with a configurable interval, removing expired entries
 * to balance memory use and cache freshness.
 *
 * @since 0.9 Moved out of OCMOSJ
 */
public class OutboundCache {

    /**
     * Cache for outbound tunnels per source-destination pair.
     * Maintains consistent outbound tunnel usage to optimize streaming performance.
     * Uses ConcurrentHashMap for thread safety and high concurrency.
     */
    final ConcurrentHashMap<HashPair, TunnelInfo> tunnelCache = new ConcurrentHashMap<>(128, 0.9f, 16);

    /**
     * Cache for backlogged tunnels per source-destination pair.
     * Also uses ConcurrentHashMap for consistency and concurrency.
     */
    final ConcurrentHashMap<HashPair, TunnelInfo> backloggedTunnelCache = new ConcurrentHashMap<>(128, 0.9f, 16);

    /**
     * LeaseSet cache keyed by source-destination pairs.
     * Controls when to bundle our own LeaseSet to reduce overhead on repeated communications.
     */
    final ConcurrentHashMap<HashPair, LeaseSet> leaseSetCache = new ConcurrentHashMap<>(128, 0.9f, 16);

    /**
     * Lease cache keyed by source-destination pairs.
     * Ensures persistent use of the same inbound lease to minimize out-of-order delivery.
     */
    final ConcurrentHashMap<HashPair, Lease> leaseCache = new ConcurrentHashMap<>(128, 0.9f, 16);

    /**
     * Cache to track the last reply request time per source-destination pair.
     * Used to detect and recover from failed tunnels.
     */
    final ConcurrentHashMap<HashPair, Long> lastReplyRequestCache = new ConcurrentHashMap<>(128, 0.9f, 16);

    /**
     * Cache for LeaseSets associated with multihomed routers or hosted destinations.
     */
    public final ConcurrentHashMap<Hash, LeaseSet> multihomedCache = new ConcurrentHashMap<>(128, 0.9f, 16);

    private final RouterContext _context;

    private static final int CLEAN_INTERVAL = 2 * 60 * 1000; // 2 minutes cleaning interval

    /**
     * Creates an OutboundCache instance and schedules periodic cache cleanup.
     *
     * @param ctx the router context providing environment and utilities.
     */
    public OutboundCache(final RouterContext ctx) {
        _context = ctx;
        _context.simpleTimer2().addPeriodicEvent(new OCMOSJCacheCleaner(), CLEAN_INTERVAL, CLEAN_INTERVAL);
    }

    /**
     * Composite key for caches, combining source and destination Hashes.
     * Implements robust hashCode and equals for minimal collisions.
     *
     * @since 0.8.3
     */
    static class HashPair {
        private final Hash sh;
        private final Hash dh;

        HashPair(final Hash s, final Hash d) {
            sh = s;
            dh = d;
        }

        @Override
        public int hashCode() {
            int result = sh.hashCode();
            result = 31 * result + dh.hashCode();
            return result;
        }

        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof HashPair)) return false;
            final HashPair hp = (HashPair) o;
            return sh.equals(hp.sh) && dh.equals(hp.dh);
        }
    }

    /**
     * Clears cache entries related to the provided key and elements upon failure,
     * allowing retries with fresh associations.
     *
     * @param hashPair the source-destination key for caches.
     * @param lease    lease to clear from leaseCache, may be null.
     * @param inTunnel inbound tunnel to check for leaseSetCache invalidation, may be null.
     * @param outTunnel outbound tunnel to clear from tunnel caches, may be null.
     */
    void clearCaches(final HashPair hashPair, final Lease lease, final TunnelInfo inTunnel, final TunnelInfo outTunnel) {
        if (inTunnel != null) {
            leaseSetCache.remove(hashPair);
        }
        if (lease != null) {
            leaseCache.remove(hashPair, lease);
        }
        if (outTunnel != null) {
            backloggedTunnelCache.remove(hashPair, outTunnel);
            tunnelCache.remove(hashPair, outTunnel);
        }
    }

    /**
     * Clears all caches managed by this instance.
     * Use cautiously as this impacts performance temporarily.
     *
     * @since 0.8.8
     */
    public void clearAllCaches() {
        leaseSetCache.clear();
        leaseCache.clear();
        backloggedTunnelCache.clear();
        tunnelCache.clear();
        lastReplyRequestCache.clear();
    }

    /**
     * Removes expired LeaseSets from the provided cache.
     *
     * @param ctx the router context for current time.
     * @param tc  the LeaseSet cache to clean.
     */
    private static void cleanLeaseSetCache(final RouterContext ctx, final Map<HashPair, LeaseSet> tc) {
        final long now = ctx.clock().now();
        for (Iterator<LeaseSet> iter = tc.values().iterator(); iter.hasNext(); ) {
            final LeaseSet l = iter.next();
            if (l.getEarliestLeaseDate() < now) {
                iter.remove();
            }
        }
    }

    /**
     * Removes expired Leases from the provided cache.
     *
     * @param tc the Lease cache to clean.
     */
    private static void cleanLeaseCache(final Map<HashPair, Lease> tc) {
        for (Iterator<Lease> iter = tc.values().iterator(); iter.hasNext(); ) {
            final Lease l = iter.next();
            if (l.isExpired(Router.CLOCK_FUDGE_FACTOR)) {
                iter.remove();
            }
        }
    }

    /**
     * Removes invalid or expired TunnelInfo entries from the provided tunnel cache.
     *
     * @param ctx the router context for tunnel validation.
     * @param tc  the TunnelInfo cache to clean.
     */
    private static void cleanTunnelCache(final RouterContext ctx, final Map<HashPair, TunnelInfo> tc) {
        for (Iterator<Map.Entry<HashPair, TunnelInfo>> iter = tc.entrySet().iterator(); iter.hasNext(); ) {
            final Map.Entry<HashPair, TunnelInfo> entry = iter.next();
            final HashPair key = entry.getKey();
            final TunnelInfo tunnel = entry.getValue();
            if (!ctx.tunnelManager().isValidTunnel(key.sh, tunnel)) {
                iter.remove();
            }
        }
    }

    /**
     * Removes stale entries from the last reply request cache.
     * Entries older than CLEAN_INTERVAL are purged.
     *
     * @param ctx the router context for current time.
     * @param tc  the last reply request cache to clean.
     */
    private static void cleanReplyCache(final RouterContext ctx, final Map<HashPair, Long> tc) {
        final long now = ctx.clock().now();
        final long expiration = now - CLEAN_INTERVAL;
        for (Iterator<Long> iter = tc.values().iterator(); iter.hasNext(); ) {
            final Long timestamp = iter.next();
            if (timestamp < expiration) {
                iter.remove();
            }
        }
    }

    /**
     * Internal timer event that periodically cleans all caches.
     */
    private class OCMOSJCacheCleaner implements SimpleTimer.TimedEvent {
        @Override
        public void timeReached() {
            cleanLeaseSetCache(_context, leaseSetCache);
            cleanLeaseCache(leaseCache);
            cleanTunnelCache(_context, tunnelCache);
            cleanTunnelCache(_context, backloggedTunnelCache);
            cleanReplyCache(_context, lastReplyRequestCache);
        }
    }
}
