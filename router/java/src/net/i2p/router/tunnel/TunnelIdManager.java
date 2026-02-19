package net.i2p.router.tunnel;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Canonical manager for all tunnel IDs in the router.
 * Provides a single source of truth for TunnelId objects, ensuring:
 * - No duplicate TunnelId objects with the same value
 * - Proper memory management through canonicalization
 * - Centralized tracking for debugging and cleanup
 * - Prevention of duplicate tunnel removals
 *
 * @since 0.9.68+
 */
public class TunnelIdManager {
    private final RouterContext _context;
    private final Log _log;

    /**
     * Tunnel state enum for lifecycle tracking
     */
    private enum TunnelState { ACTIVE, REMOVING, REMOVED }

    /**
     * Canonical TunnelId cache - ensures only one TunnelId object per value.
     * This prevents memory leaks from creating duplicate TunnelId objects.
     */
    private final ConcurrentHashMap<Long, TunnelId> _canonicalIds = new ConcurrentHashMap<>();

    /**
     * Track tunnel states to prevent duplicate removals
     */
    private final ConcurrentHashMap<Long, TunnelState> _tunnelStates = new ConcurrentHashMap<>();

    /**
     * Track recently removed tunnel IDs to handle delayed message delivery.
     */
    private final ConcurrentHashMap<Long, Long> _recentlyRemoved = new ConcurrentHashMap<>();

    /**
     * Stats for debugging duplicate removal attempts
     */
    private final AtomicInteger _duplicateRemovalAttempts = new AtomicInteger();

    private static final long RECENTLY_REMOVED_EXPIRY = 60 * 1000;
    private static final int MAX_CACHED_IDS = 50000;
    private static final long CLEANUP_INTERVAL = 5 * 60 * 1000;

    public TunnelIdManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(TunnelIdManager.class);
        context.simpleTimer2().addPeriodicEvent(new CleanupTask(), CLEANUP_INTERVAL, CLEANUP_INTERVAL);
    }

    private class CleanupTask implements SimpleTimer.TimedEvent {
        public void timeReached() {
            cleanup();
        }
    }

    /**
     * Get or create a canonical TunnelId for the given value.
     * This ensures only one TunnelId object exists per value, preventing memory leaks.
     *
     * @param id the tunnel ID value (1 to 0xffffffff)
     * @return the canonical TunnelId object
     * @throws IllegalArgumentException if id is invalid
     */
    public TunnelId getOrCreate(long id) {
        if (id <= 0 || id > TunnelId.MAX_ID_VALUE) {
            throw new IllegalArgumentException("Invalid tunnel ID: " + id);
        }

        Long key = Long.valueOf(id);
        TunnelId existing = _canonicalIds.get(key);
        if (existing != null) {
            return existing;
        }

        TunnelId newId = new TunnelId(id);
        TunnelId racer = _canonicalIds.putIfAbsent(key, newId);
        return racer != null ? racer : newId;
    }

    /**
     * Get an existing canonical TunnelId, or null if not found.
     *
     * @param id the tunnel ID value
     * @return the canonical TunnelId, or null
     */
    public TunnelId get(long id) {
        return _canonicalIds.get(Long.valueOf(id));
    }

    /**
     * Remove a TunnelId from the canonical cache.
     * Called when a tunnel is destroyed.
     *
     * @param id the tunnel ID to remove
     */
    public void remove(TunnelId id) {
        if (id == null) return;
        Long key = Long.valueOf(id.getTunnelId());
        _tunnelStates.put(key, TunnelState.REMOVED);
        _canonicalIds.remove(key);
        _recentlyRemoved.put(key, _context.clock().now());
    }

    /**
     * Try to mark a tunnel as being removed.
     * Returns false if the tunnel is already being removed or was removed.
     * This prevents duplicate removal attempts.
     *
     * @param id the tunnel ID
     * @return true if successfully marked for removal, false if already removed/being removed
     */
    public boolean tryMarkForRemoval(TunnelId id) {
        if (id == null) return false;
        Long key = Long.valueOf(id.getTunnelId());
        
        TunnelState current = _tunnelStates.get(key);
        if (current == TunnelState.REMOVING || current == TunnelState.REMOVED) {
            _duplicateRemovalAttempts.incrementAndGet();
            if (_log.shouldDebug()) {
                _log.debug("Duplicate removal attempt for tunnel: " + id);
            }
            return false;
        }
        
        TunnelState previous = _tunnelStates.putIfAbsent(key, TunnelState.REMOVING);
        if (previous == TunnelState.REMOVING || previous == TunnelState.REMOVED) {
            _duplicateRemovalAttempts.incrementAndGet();
            if (_log.shouldDebug()) {
                _log.debug("Duplicate removal attempt for tunnel: " + id);
            }
            return false;
        }
        return true;
    }

    /**
     * Complete the removal of a tunnel.
     * Called after cleanup is done.
     *
     * @param id the tunnel ID
     */
    public void completeRemoval(TunnelId id) {
        if (id == null) return;
        Long key = Long.valueOf(id.getTunnelId());
        _tunnelStates.put(key, TunnelState.REMOVED);
        _canonicalIds.remove(key);
        _recentlyRemoved.put(key, _context.clock().now());
    }

    /**
     * Get count of duplicate removal attempts (for debugging)
     * @return number of duplicate removal attempts
     */
    public int getDuplicateRemovalCount() {
        return _duplicateRemovalAttempts.get();
    }

    /**
     * Check if a tunnel ID was recently removed.
     * Used to handle delayed messages arriving after tunnel cleanup.
     *
     * @param id the tunnel ID to check
     * @return true if recently removed
     */
    public boolean wasRecentlyRemoved(long id) {
        Long removedAt = _recentlyRemoved.get(Long.valueOf(id));
        if (removedAt == null) return false;

        long now = _context.clock().now();
        if (now - removedAt < RECENTLY_REMOVED_EXPIRY) {
            return true;
        }

        _recentlyRemoved.remove(Long.valueOf(id));
        return false;
    }

    /**
     * Generate a new unique tunnel ID that doesn't conflict with existing ones.
     * Uses putIfAbsent to ensure atomicity and prevent race conditions.
     *
     * @return a new unique TunnelId
     */
    public TunnelId generateNewId() {
        int attempts = 0;
        while (attempts < 10000) {
            long id = 1 + _context.random().nextLong(TunnelId.MAX_ID_VALUE);
            Long key = Long.valueOf(id);
            TunnelId newId = new TunnelId(id);
            TunnelId existing = _canonicalIds.putIfAbsent(key, newId);
            if (existing == null) {
                return newId;
            }
            attempts++;
        }
        if (_log.shouldWarn()) {
            _log.warn("Struggling to generate unique tunnel ID after 10000 attempts");
        }
        return getOrCreate(1 + _context.random().nextLong(TunnelId.MAX_ID_VALUE));
    }

    /**
     * Generate a new unique tunnel ID, excluding specific existing IDs.
     * Uses putIfAbsent to ensure atomicity and prevent race conditions.
     *
     * @param excludeIds set of tunnel ID values to exclude
     * @return a new unique TunnelId
     */
    public TunnelId generateNewIdExcluding(java.util.Set<Long> excludeIds) {
        int attempts = 0;
        while (attempts < 10000) {
            long id = 1 + _context.random().nextLong(TunnelId.MAX_ID_VALUE);
            if (excludeIds != null && excludeIds.contains(Long.valueOf(id))) {
                attempts++;
                continue;
            }
            Long key = Long.valueOf(id);
            TunnelId newId = new TunnelId(id);
            TunnelId existing = _canonicalIds.putIfAbsent(key, newId);
            if (existing == null) {
                return newId;
            }
            attempts++;
        }
        if (_log.shouldWarn()) {
            _log.warn("Struggling to generate unique tunnel ID after 10000 attempts");
        }
        return getOrCreate(1 + _context.random().nextLong(TunnelId.MAX_ID_VALUE));
    }

    /**
     * Get the count of tracked tunnel IDs.
     */
    public int size() {
        return _canonicalIds.size();
    }

    /**
     * Cleanup old entries from recently removed map and prune canonical IDs if too large.
     */
    public void cleanup() {
        long cutoff = _context.clock().now() - RECENTLY_REMOVED_EXPIRY;
        Iterator<Map.Entry<Long, Long>> it = _recentlyRemoved.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> entry = it.next();
            if (entry.getValue() < cutoff) {
                it.remove();
            }
        }

        if (_canonicalIds.size() > MAX_CACHED_IDS) {
            int toRemove = _canonicalIds.size() - (MAX_CACHED_IDS / 2);
            Iterator<Map.Entry<Long, TunnelId>> iter = _canonicalIds.entrySet().iterator();
            while (iter.hasNext() && toRemove > 0) {
                iter.next();
                iter.remove();
                toRemove--;
            }
        }
    }

    /**
     * Clear all tracked IDs - for shutdown.
     */
    public void clear() {
        _canonicalIds.clear();
        _recentlyRemoved.clear();
    }
}
