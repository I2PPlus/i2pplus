package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

import net.i2p.data.TunnelId;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
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
public class ExpireJobManager extends JobImpl {
    private final PriorityBlockingQueue<TunnelExpiration> _expirationQueue;
    private final Set<Long> _tunnelKeys;
    private volatile boolean _isScheduled = false;
    private volatile long _lastRunTime;
    private final Log _log;

    // Only run when tunnels are within this window of expiring (avoids unnecessary runs)
    private static final long BATCH_WINDOW = 15 * 1000;
    // Forced requeue timeout when job is starved (2x batch window)
    private static final long FORCED_REQUEUE_TIMEOUT = 2 * BATCH_WINDOW;
    // Maximum allowed lag before forcing immediate requeue
    private static final long MAX_ACCEPTABLE_LAG = 90 * 1000;
    // Queue size threshold to trigger aggressive recovery
    private static final int BACKED_UP_THRESHOLD = 50;
    // Massive queue threshold - trigger emergency cleanup
    private static final int MASSIVE_QUEUE_THRESHOLD = 100;
    // Early expiration offsets to match original ExpireJob behavior
    private static final long OB_EARLY_EXPIRE = 30 * 1000;
    private static final long IB_EARLY_EXPIRE = OB_EARLY_EXPIRE + 7500;
    // Stale entry threshold - remove entries older than this
    private static final long STALE_THRESHOLD = 3000;
    // Maximum entries to clean per run
    private static final int MAX_CLEANUP_PER_RUN = 50000;

    public ExpireJobManager(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _expirationQueue = new PriorityBlockingQueue<>();
        _tunnelKeys = ConcurrentHashMap.newKeySet();
        getTiming().setStartAfter(ctx.clock().now() + BATCH_WINDOW);
        _lastRunTime = ctx.clock().now();
    }

    @Override
    public String getName() {
        return "Expire Local Tunnels";
    }

    /**
     * Generate a unique key for a tunnel config.
     * Falls back to identity hash code if tunnel IDs aren't available yet.
     */
    private static Long getTunnelKey(PooledTunnelCreatorConfig cfg) {
        if (cfg == null) return null;
        try {
            int length = cfg.getLength();
            if (length <= 0) {
                // Use identity hash as fallback for zero-length tunnels
                return Long.valueOf(System.identityHashCode(cfg));
            }
            TunnelId recvId = cfg.getReceiveTunnelId(0);
            TunnelId sendId = cfg.getSendTunnelId(0);
            if (recvId == null || sendId == null || recvId.getTunnelId() == 0 || sendId.getTunnelId() == 0) {
                // Use identity hash as fallback
                return Long.valueOf(System.identityHashCode(cfg));
            }
            return Long.valueOf((recvId.getTunnelId() << 32) | (sendId.getTunnelId() & 0xFFFFFFFFL));
        } catch (Exception e) {
            // Use identity hash as fallback
            return Long.valueOf(System.identityHashCode(cfg));
        }
    }

    /**
     * Schedule a tunnel for expiration.
     * Called when a tunnel build succeeds.
     *
     * @param cfg the tunnel configuration to expire
     */
    public void scheduleExpiration(PooledTunnelCreatorConfig cfg) {
        Long tunnelKey = getTunnelKey(cfg);
        if (tunnelKey == null) {
            // Can't generate key - add anyway (will be cleaned up later)
        } else if (!_tunnelKeys.add(tunnelKey)) {
            // Already in queue - skip duplicate
            return;
        }

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
        cfg.setExpiration(earlyExpire);

        // Add to queue
        _expirationQueue.offer(new TunnelExpiration(cfg, tunnelKey, earlyExpire, dropAfter));

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

        // Cleanup stale entries if queue is backed up or massive
        if (isBackedUp || isMassive) {
            cleanupStaleEntries(now, isMassive);
        }

        List<TunnelExpiration> readyToExpire = new ArrayList<>();
        List<TunnelExpiration> readyToDrop = new ArrayList<>();

        // Collect all tunnels ready for phase 1 (pool removal)
        for (TunnelExpiration te : _expirationQueue) {
            if (te.expirationTime <= now && !te.phase1Complete) {
                readyToExpire.add(te);
            } else if (te.dropTime <= now && te.phase1Complete) {
                readyToDrop.add(te);
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
            for (TunnelExpiration te : readyToExpire) {
                PooledTunnelCreatorConfig cfg = te.config;
                TunnelPool pool = cfg.getTunnelPool();
                boolean removed = false;
                if (pool != null) {
                    removed = pool.removeTunnelSynchronous(cfg);
                }
                if (removed) {
                    // Remove from queue only after synchronous removal + LeaseSet republish is complete
                    _expirationQueue.remove(te);
                    if (te.tunnelKey != null) {
                        _tunnelKeys.remove(te.tunnelKey);
                    }
                    removedCount++;
                } else {
                    // Lock acquisition failed - tunnel stays in queue for retry
                    // This prevents job queue deadlock by not blocking indefinitely
                    failedCount++;
                    if (_log.shouldInfo()) {
                        _log.info("Failed to remove tunnel (couldn't acquire lock) from pool " + pool + " -> Will retry on next run...");
                    }
                }
            }

            if (failedCount > 0 && _log.shouldInfo()) {
                _log.info("Tunnel expiration: " + removedCount + " removed, " + failedCount + " failed -> Will retry on next run...");
            }

            // Phase 2: Remove from dispatcher and fully remove from queue
            for (TunnelExpiration te : readyToDrop) {
                PooledTunnelCreatorConfig cfg = te.config;
                getContext().tunnelDispatcher().remove(cfg);
                _expirationQueue.remove(te);
                if (te.tunnelKey != null) {
                    _tunnelKeys.remove(te.tunnelKey);
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
        Long tunnelKey = getTunnelKey(cfg);
        if (tunnelKey != null) {
            _tunnelKeys.remove(tunnelKey);
            // Set expiration to now so cleanup will remove it
            cfg.setExpiration(getContext().clock().now() - 1000);
        }
    }

    /**
     * Clean up entries from the queue - remove those already processed or expired
     * @param now current time
     * @param aggressive if true, clean more entries
     */
    private void cleanupStaleEntries(long now, boolean aggressive) {
        int maxDrain = aggressive ? 5000 : 2000;
        int cleaned = 0;
        List<TunnelExpiration> keep = new ArrayList<>();

        // Drain entries and keep those that are still valid (future expiration)
        TunnelExpiration te;
        int drained = 0;
        while (drained < maxDrain && (te = _expirationQueue.poll()) != null) {
            // Keep entries that haven't expired yet (expirationTime > now)
            // Remove entries that have expired (ready to process or already processed)
            if (te.expirationTime > now) {
                keep.add(te);
            } else {
                // Entry has expired - remove from tracking
                if (te.tunnelKey != null) {
                    _tunnelKeys.remove(te.tunnelKey);
                }
                cleaned++;
            }
            drained++;
        }

        // Re-add entries that are still valid
        _expirationQueue.addAll(keep);

        if (cleaned > 0 && _log.shouldInfo()) {
            _log.info("Cleaned up " + cleaned + " expired tunnels -> " + keep.size() + " active tunnels in use...");
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
