package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

import net.i2p.data.TunnelId;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.stat.RateConstants;
import net.i2p.util.Log;

/**
 * Manager for batching tunnel expirations to reduce job queue overhead.
 * Instead of creating individual ExpireJobs for each tunnel, this manager
 * runs periodically and processes all tunnels ready to expire in a batch.
 *
 * Each tunnel goes through two phases:
 * 1. First phase: Remove from tunnel pool (LeaseSet refresh)
 * 2. Second phase: Remove from tunnel dispatcher (stop accepting traffic)
 *
 * @since 0.9.68+
 */
public class ExpireLocalTunnelsJob extends JobImpl {
    private final PriorityBlockingQueue<TunnelExpiration> _expirationQueue;
    private final ConcurrentHashMap<Long, TunnelExpiration> _tunnelKeys;
    private volatile boolean _isScheduled = false;
    private volatile long _lastRunTime;
    private final Log _log;
    private int _runCount = 0;

    // Only run when tunnels are within this window of expiring (avoids unnecessary runs)
    private static final long BATCH_WINDOW = 5 * 1000;
    // Forced requeue timeout when job is starved (2x batch window)
    private static final long FORCED_REQUEUE_TIMEOUT = 2 * BATCH_WINDOW;
    // Maximum allowed lag before forcing immediate requeue
    private static final long MAX_ACCEPTABLE_LAG = 30 * 1000;
    // Queue size threshold to trigger aggressive recovery
    private static final int BACKED_UP_THRESHOLD = 5000;
    // Massive queue threshold - trigger emergency cleanup
    private static final int MASSIVE_QUEUE_THRESHOLD = 10000;
    // Early expiration offsets to match original ExpireJob behavior
    private static final long OB_EARLY_EXPIRE = 30 * 1000;
    private static final long IB_EARLY_EXPIRE = OB_EARLY_EXPIRE + 7500;
    // Stale entry threshold - remove entries older than this
    private static final long STALE_THRESHOLD = 500; // ms
    // Maximum entries to clean per run
    private static final int MAX_CLEANUP_PER_RUN = 500;
    // Maximum retries for phase 1 removal before forcing cleanup
    private static final int MAX_PHASE1_RETRIES = 5;
    // Maximum entries to iterate per run (avoid OOM from queue iteration)
    private static final int MAX_ITERATE_PER_RUN = 3000;
    private static final int MAX_ITERATE_BACKED_UP = 5000;
    // Emergency drainage rate when massively backed up (100K+ entries)
    private static final int MAX_ITERATE_EMERGENCY = 5000;
    // Debug logging interval for queue health (every N runs)
    private static final int HEALTH_LOG_INTERVAL = 60;

    public ExpireLocalTunnelsJob(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _expirationQueue = new PriorityBlockingQueue<>();
        _tunnelKeys = new ConcurrentHashMap<>();
        getTiming().setStartAfter(ctx.clock().now() + BATCH_WINDOW);
        _lastRunTime = ctx.clock().now();
        // Create rate stat for monitoring queue health
        ctx.statManager().createRateStat("tunnel.expireQueueSize", "Expiration queue size", "Tunnels",
            new long[] { 60*1000, 10*60*1000 });
        ctx.statManager().createRateStat("tunnel.expireKeysSize", "Expiration keys map size", "Tunnels",
            new long[] { 60*1000, 10*60*1000 });
    }

    @Override
    public String getName() {
        return "Expire Local Tunnels";
    }

    /**
     * Generate a unique key for a tunnel config.
     * Uses tunnel IDs (receive + send) when available for proper deduplication.
     * 
     * Falls back to System.identityHashCode(cfg) for:
     * - Zero-length tunnels
     * - Configs without valid tunnel IDs yet
     * - Any exceptions during ID extraction
     * 
     * Note: Identity hash fallback does NOT dedupe across multiple instances,
     * meaning tunnels without valid IDs will each get their own queue entry.
     * This is acceptable as such configs are typically short-lived.
     */
    public static Long getTunnelKey(PooledTunnelCreatorConfig cfg) {
        if (cfg == null) return null;
        try {
            TunnelId recvId = cfg.getReceiveTunnelId(0);
            TunnelId sendId = cfg.getSendTunnelId(0);
            if (recvId != null && sendId != null && recvId.getTunnelId() != 0 && sendId.getTunnelId() != 0) {
                return Long.valueOf((recvId.getTunnelId() << 32) | (sendId.getTunnelId() & 0xFFFFFFFFL));
            }
            return Long.valueOf(System.identityHashCode(cfg));
        } catch (Exception e) {
            return Long.valueOf(System.identityHashCode(cfg));
        }
    }

    /**
     * Remove a tunnel expiration entry from the index map.
     * This centralizes key removal to prevent dangling keys.
     *
     * @param te the tunnel expiration to remove from index
     */
    private void removeFromIndex(TunnelExpiration te) {
        if (te != null && te.tunnelKey != null) {
            _tunnelKeys.remove(te.tunnelKey);
        }
    }

    /**
     * Determine if a tunnel expiration entry should be kept in the queue.
     * 
     * Policy:
     * - Keep if phase 1 not complete (still waiting for pool removal)
     * - Keep if drop time is in the future (still waiting for dispatcher removal)
     * - Keep if not overdue (still within acceptable grace period)
     * - Otherwise, can be cleaned up
     */
    private boolean shouldKeepEntry(TunnelExpiration te, long now) {
        if (!te.phase1Complete) {
            return true;  // Still waiting for phase 1 (pool removal)
        }
        long relevantTime = te.phase1Complete ? te.dropTime : te.expirationTime;
        boolean isOverdue = relevantTime < now - STALE_THRESHOLD;
        boolean isFuture = te.expirationTime > now;
        
        if (isFuture) {
            return true;  // Drop time hasn't arrived yet
        }
        if (!isOverdue) {
            return true;  // Still within grace period
        }
        return false;  // Stale and overdue - can be cleaned up
    }

    /**
     * Log queue health metrics for debugging.
     */
    private void logQueueHealth(int runCount) {
        if (runCount % HEALTH_LOG_INTERVAL == 0) {
            int queueSize = _expirationQueue.size();
            int keysSize = _tunnelKeys.size();
            if (_log.shouldDebug()) {
                _log.debug("Expire queue health: queue=" + queueSize + ", keys=" + keysSize +
                           ", diff=" + (keysSize - queueSize));
            }
            // Alert if keys significantly exceed queue (potential leak indicator)
            if (keysSize > queueSize + 10 && queueSize > 20) {
                if (_log.shouldWarn()) {
                    _log.warn("Expire keys map (" + keysSize + ") significantly larger than queue (" +
                              queueSize + ") - possible index leak");
                }
            }
            // Record stats
            getContext().statManager().addRateData("tunnel.expireQueueSize", queueSize);
            getContext().statManager().addRateData("tunnel.expireKeysSize", keysSize);
        }
    }

    /**
     * Schedule a tunnel for expiration.
     * Called when a tunnel build succeeds.
     *
     * @param cfg the tunnel configuration to expire
     */
    public void scheduleExpiration(PooledTunnelCreatorConfig cfg) {
        // Defensive null checks to prevent NPEs that could leave entries partially added
        if (cfg == null || cfg.getTunnelPool() == null || cfg.getTunnelPool().getSettings() == null) {
            if (_log.shouldWarn()) {
                _log.warn("Cannot schedule expiration for null config or pool");
            }
            return;
        }

        Long tunnelKey = getTunnelKey(cfg);
        if (tunnelKey == null) {
            // Can't generate key - log and skip (avoid degenerate configs)
            if (_log.shouldDebug()) {
                _log.debug("Cannot generate tunnel key for config - skipping expiration schedule: " + cfg);
            }
            return;
        }

        // Use putIfAbsent to avoid race window between containsKey and put
        long actualExpiration = cfg.getExpiration();
        boolean isInbound = cfg.getTunnelPool().getSettings().isInbound();

        // Calculate early expiration (same logic as original ExpireJob)
        long earlyExpire;
        long dropAfter;
        if (isInbound) {
            dropAfter = actualExpiration + (2 * Router.CLOCK_FUDGE_FACTOR);
            earlyExpire = actualExpiration - IB_EARLY_EXPIRE
                        - getContext().random().nextLong(IB_EARLY_EXPIRE);
        } else {
            dropAfter = actualExpiration + Router.CLOCK_FUDGE_FACTOR;
            earlyExpire = actualExpiration - OB_EARLY_EXPIRE
                        - getContext().random().nextLong(OB_EARLY_EXPIRE);
        }

        // Update the tunnel's expiration to the early time
        // Note: This takes ownership of the expiration field - other subsystems
        // reading cfg.getExpiration() will see the early expiry time
        cfg.setExpiration(earlyExpire);

        // Add to queue and map (use putIfAbsent to dedupe and avoid race window)
        TunnelExpiration te = new TunnelExpiration(cfg, tunnelKey, earlyExpire, dropAfter);
        if (tunnelKey != null && _tunnelKeys.putIfAbsent(tunnelKey, te) != null) {
            // Already existed in keys map - this is a duplicate, skip adding
            return;
        }
        _expirationQueue.offer(te);

        // Schedule this job if not already scheduled
        synchronized (this) {
            if (!_isScheduled) {
                _isScheduled = true;
                long now = getContext().clock().now();
                long nextRun = Math.min(earlyExpire, now + BATCH_WINDOW);
                getTiming().setStartAfter(nextRun);
                getContext().jobQueue().addJob(this);
            }
        }
    }

    @Override
    public void runJob() {
        synchronized (this) {
            _isScheduled = false;
        }
        long now = getContext().clock().now();
        int queueSize = _expirationQueue.size();
        boolean isBackedUp = queueSize > BACKED_UP_THRESHOLD;
        boolean isMassive = queueSize > MASSIVE_QUEUE_THRESHOLD;

        // Log queue health metrics periodically
        _runCount++;
        logQueueHealth(_runCount);

        // Cleanup stale entries if queue is backed up or massive
        if (isBackedUp || isMassive) {
            cleanupStaleEntries(now, isMassive);
        }

        List<TunnelExpiration> readyToExpire = new ArrayList<>();
        List<TunnelExpiration> readyToDrop = new ArrayList<>();
        List<TunnelExpiration> toRequeue = new ArrayList<>();

        // Bounded drain WITHOUT destroying heap invariant
        // Use emergency drainage rate when massively backed up (>5K entries)
        int maxDrain;
        if (queueSize > 5000) {
            maxDrain = MAX_ITERATE_EMERGENCY;
            if (_log.shouldWarn()) {
                _log.warn("Emergency drainage mode: processing " + maxDrain + " entries (queue size: " + queueSize + ")");
            }
        } else if (isBackedUp) {
            maxDrain = MAX_ITERATE_BACKED_UP;
        } else {
            maxDrain = MAX_ITERATE_PER_RUN;
        }
        List<TunnelExpiration> batch = new ArrayList<>(maxDrain);
        _expirationQueue.drainTo(batch, maxDrain);

        // Process batch while preserving phase semantics
        for (TunnelExpiration te : batch) {
            if (te.expirationTime <= now && !te.phase1Complete) {
                // Phase 1 candidate - will be processed below
                readyToExpire.add(te);
            } else if (te.dropTime <= now && te.phase1Complete) {
                // Ready for phase 2
                readyToDrop.add(te);
            } else {
                // Not ready for either phase yet
                toRequeue.add(te);
            }
        }

        if (readyToExpire.isEmpty() && readyToDrop.isEmpty()) {
            // Nothing ready to process yet
        } else {
            if (isBackedUp && _log.shouldInfo() && (readyToExpire.size() > 0 || readyToDrop.size() > 0)) {
                _log.info("Removing " + readyToExpire.size() +
                          " expired " + (readyToExpire.size() > 1 ? "tunnels" : "tunnel") +
                          (readyToDrop.size() > 0 ? ", cleaning up " + readyToDrop.size() +
                          " old tunnels from dispatcher" : "") + "... (Queue: " + queueSize + " jobs)");
            }

            // Phase 1: Remove from tunnel pools synchronously during recovery
            // This ensures LeaseSets are republished BEFORE removing from queue,
            // preventing client connection failures
            int removedCount = 0;
            int failedCount = 0;
            int forceCleanupCount = 0;
            for (TunnelExpiration te : readyToExpire) {
                PooledTunnelCreatorConfig cfg = te.config;
                TunnelPool pool = cfg.getTunnelPool();
                boolean removed = false;
                if (pool != null) {
                    removed = pool.removeTunnelSynchronous(cfg);
                }
                if (removed) {
                    te.phase1Complete = true;
                    removedCount++;
                } else {
                    te.retryCount++;
                    if (te.retryCount >= MAX_PHASE1_RETRIES) {
                        te.phase1Complete = true;
                        forceCleanupCount++;
                        if (_log.shouldWarn()) {
                            _log.warn("Forcing phase 1 complete after " + MAX_PHASE1_RETRIES + " retries (possible LeaseSet inconsistency): " + cfg);
                        }
                    } else {
                        failedCount++;
                        if (_log.shouldInfo()) {
                            _log.info("Failed to remove tunnel (retry " + te.retryCount + "/" + MAX_PHASE1_RETRIES + ") from pool " + pool + " -> Will retry on next run...");
                        }
                    }
                }
            }

            if (failedCount > 0 || forceCleanupCount > 0) {
                if (_log.shouldInfo()) {
                    _log.info("Tunnel expiration: " + removedCount + " removed, " + forceCleanupCount + " force-cleaned, " + failedCount + " failed (will retry)");
                }
            }

            // Check if phase 1 complete entries are ready for phase 2
            for (TunnelExpiration te : readyToExpire) {
                if (te.phase1Complete && te.dropTime <= now) {
                    readyToDrop.add(te);
                } else {
                    // Includes: failed phase1 (retry), completed but dropTime in future
                    toRequeue.add(te);
                }
            }

            // Phase 2: Remove from dispatcher
            for (TunnelExpiration te : readyToDrop) {
                PooledTunnelCreatorConfig cfg = te.config;
                try {
                    getContext().tunnelDispatcher().remove(cfg);
                } catch (Exception e) {
                    if (_log.shouldWarn()) {
                        _log.warn("Error removing tunnel in phase 2: " + e.getMessage(), e);
                    }
                }
                // Use centralized removal to prevent dangling keys
                removeFromIndex(te);
            }

            // Requeue entries that need more time before phase 2
            // Only requeue if still valid (not removed via removeTunnel())
            for (TunnelExpiration te : toRequeue) {
                if (te.tunnelKey == null || _tunnelKeys.get(te.tunnelKey) == te) {
                    _expirationQueue.offer(te);
                } else {
                    // Key was removed externally - clean up the index
                    removeFromIndex(te);
                }
            }
        }

        // Requeue if there are more pending expirations
        if (!_expirationQueue.isEmpty()) {
            synchronized (this) {
                if (_isScheduled) {
                    return;
                }
                // Find the next expiration time
                long nextExpiration = Long.MAX_VALUE;
                for (TunnelExpiration te : _expirationQueue) {
                    long targetTime = te.phase1Complete ? te.dropTime : te.expirationTime;
                    if (targetTime < nextExpiration) {
                        nextExpiration = targetTime;
                    }
                }

                // Check if we've been starved - force requeue if lag is too high
                long lag = now - _lastRunTime;
                boolean isStarved = _lastRunTime > 0 && lag > FORCED_REQUEUE_TIMEOUT;
                boolean stillBackedUp = _expirationQueue.size() > BACKED_UP_THRESHOLD;

                if (isStarved || stillBackedUp || nextExpiration <= now + BATCH_WINDOW) {
                    long nextRun;
                    if (isStarved) {
                        // If starved, run immediately
                        nextRun = now;
                        if (_log.shouldInfo()) {
                            _log.info("Expire Tunnels Job starved for " + (lag / 1000) + "s -> Immediately requeueing...");
                        }
                    } else if (stillBackedUp) {
                        // If still backed up, run again soon
                        nextRun = now + BATCH_WINDOW / 4;
                    } else {
                        nextRun = Math.min(nextExpiration, now + BATCH_WINDOW);
                    }
                    getTiming().setStartAfter(nextRun);
                    _isScheduled = true;
                    getContext().jobQueue().addJob(this);
                }
            }
        } else if (_lastRunTime > 0 && (now - _lastRunTime) > MAX_ACCEPTABLE_LAG) {
            // Health check
            synchronized (this) {
                if (!_isScheduled) {
                    long nextRun = now + BATCH_WINDOW;
                    getTiming().setStartAfter(nextRun);
                    _isScheduled = true;
                    getContext().jobQueue().addJob(this);
                    if (_log.shouldInfo()) {
                        _log.info("Expire Tunnels Job health check -> Requeueing after " + ((now - _lastRunTime) / 1000) + "s idle...");
                    }
                }
            }
        }
        _lastRunTime = now;
    }

    /**
     * Remove a tunnel from the expiration queue when it's explicitly removed.
     * Mark it as expired so cleanup will remove it on next run.
     * Call this when a tunnel is removed from the pool (failed, replaced, etc.)
     */
    public void removeTunnel(PooledTunnelCreatorConfig cfg) {
        if (cfg == null) {
            return;
        }
        Long tunnelKey = getTunnelKey(cfg);
        if (tunnelKey != null) {
            TunnelExpiration te = _tunnelKeys.remove(tunnelKey);
            if (te != null) {
                _expirationQueue.remove(te);
                // Note: removeFromIndex not needed since we already removed from _tunnelKeys above
            }
        }
    }

    /**
     * Clean up entries from the queue - remove those already processed or expired
     * @param now current time
     * @param aggressive if true, clean more entries
     */
    private void cleanupStaleEntries(long now, boolean aggressive) {
        int maxDrain = aggressive ? MAX_CLEANUP_PER_RUN * 4 : MAX_CLEANUP_PER_RUN * 2;
        int cleaned = 0;
        int kept = 0;

        List<TunnelExpiration> batch = new ArrayList<>(maxDrain);
        _expirationQueue.drainTo(batch, maxDrain);

        List<TunnelExpiration> keep = new ArrayList<>();

        for (TunnelExpiration te : batch) {
            boolean shouldKeep = shouldKeepEntry(te, now);
            
            if (shouldKeep) {
                keep.add(te);
                kept++;
            } else {
                // Use centralized removal with defensive null checks
                if (te.config != null) {
                    try {
                        TunnelPool pool = te.config.getTunnelPool();
                        if (pool != null && !te.phase1Complete) {
                            pool.removeTunnelSynchronous(te.config);
                        }
                        if (te.phase1Complete) {
                            getContext().tunnelDispatcher().remove(te.config);
                        }
                    } catch (Exception e) {
                        if (_log.shouldWarn()) {
                            _log.warn("Error cleaning up tunnel config in cleanupStaleEntries: " + e.getMessage(), e);
                        }
                    }
                }
                // Always remove from index to prevent dangling keys
                removeFromIndex(te);
                cleaned++;
            }
        }

        // CRITICAL FIX: Do NOT re-add entries during cleanup!
        // Previously this re-added ALL kept entries, causing the queue to never shrink
        // because the same ~2000 entries kept cycling through cleanup every run.
        // With 600K queue and 2000 drain, each entry was processed ~300 times before expiring!
        // The regular runJob() will handle proper re-queuing of non-expired tunnels.
        for (TunnelExpiration te : keep) {
            // Don't re-add - let runJob() handle it properly
            // Just clean up index if key is stale
            if (te.tunnelKey != null && _tunnelKeys.get(te.tunnelKey) != te) {
                removeFromIndex(te);
            }
        }

        if (cleaned > 0 && _log.shouldInfo()) {
            _log.info("Cleanup: removed " + cleaned + " expired " + (cleaned > 1 ? "tunnels" : "tunnel"));
        }
    }

    /**
     * Inner class to track tunnel expiration state
     */
    private static class TunnelExpiration implements Comparable<TunnelExpiration> {
        final PooledTunnelCreatorConfig config;
        final Long tunnelKey;
        final long expirationTime;
        final long dropTime;
        volatile boolean phase1Complete = false;
        volatile int retryCount = 0;

        TunnelExpiration(PooledTunnelCreatorConfig cfg, Long key, long expire, long drop) {
            this.config = cfg;
            this.tunnelKey = key;
            this.expirationTime = expire;
            this.dropTime = drop;
        }

        @Override
        public int compareTo(TunnelExpiration other) {
            return Long.compare(this.expirationTime, other.expirationTime);
        }
    }
}
