package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;

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
    private volatile boolean _isScheduled = false;
    private volatile long _lastRunTime;
    private final Log _log;

    // Only run when tunnels are within this window of expiring (avoids unnecessary runs)
    private static final long BATCH_WINDOW = 90 * 1000;
    // Forced requeue timeout when job is starved (2x batch window)
    private static final long FORCED_REQUEUE_TIMEOUT = 2 * BATCH_WINDOW;
    // Maximum allowed lag before forcing immediate requeue
    private static final long MAX_ACCEPTABLE_LAG = 5 * 60 * 1000;
    // Queue size threshold to trigger aggressive recovery
    private static final int BACKED_UP_THRESHOLD = 50;
    // Early expiration offsets to match original ExpireJob behavior
    private static final long OB_EARLY_EXPIRE = 30 * 1000;
    private static final long IB_EARLY_EXPIRE = OB_EARLY_EXPIRE + 7500;

    public ExpireJobManager(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _expirationQueue = new PriorityBlockingQueue<>();
        getTiming().setStartAfter(ctx.clock().now() + BATCH_WINDOW);
        _lastRunTime = ctx.clock().now();
    }

    @Override
    public String getName() {
        return "Expire Local Tunnels";
    }

    /**
     * Schedule a tunnel for expiration.
     * Called when a tunnel build succeeds.
     *
     * @param cfg the tunnel configuration to expire
     */
    public void scheduleExpiration(PooledTunnelCreatorConfig cfg) {
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
        _expirationQueue.offer(new TunnelExpiration(cfg, earlyExpire, dropAfter));

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

        //if (isBackedUp && _log.shouldInfo()) {
        //    _log.info("Expire Tunnels Job backed up with " + queueSize + " pending tunnel expirations -> Recovering...");
        //}

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
            //if (isBackedUp && _log.shouldInfo()) {
            //    _log.info("Expire Tunnels Job backed up with " + queueSize +
            //              " pending tunnel expirations -> Waiting for tunnels to reach expiration time...");
            //}
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
     * Inner class to track tunnel expiration state
     */
    private static class TunnelExpiration implements Comparable<TunnelExpiration> {
        final PooledTunnelCreatorConfig config;
        final long expirationTime;
        final long dropTime;
        volatile boolean phase1Complete = false;

        TunnelExpiration(PooledTunnelCreatorConfig cfg, long expire, long drop) {
            this.config = cfg;
            this.expirationTime = expire;
            this.dropTime = drop;
        }

        @Override
        public int compareTo(TunnelExpiration other) {
            return Long.compare(this.expirationTime, other.expirationTime);
        }
    }
}
