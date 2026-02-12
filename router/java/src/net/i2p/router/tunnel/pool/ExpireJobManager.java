package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Iterator;
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
    // Queue size threshold to trigger aggressive batch processing
    private static final int BACKED_UP_THRESHOLD = 50;
    // Maximum items to process per batch when backed up
    private static final int MAX_BATCH_SIZE = 100;
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

        if (isBackedUp && _log.shouldWarn()) {
            _log.warn("ExpireJobManager backed up with " + queueSize + " pending expirations - entering recovery mode");
        }

        int processedInThisRun = 0;
        int maxPerRun = isBackedUp ? MAX_BATCH_SIZE : Integer.MAX_VALUE;

        while (processedInThisRun < maxPerRun) {
            List<TunnelExpiration> readyToExpire = new ArrayList<>();
            List<TunnelExpiration> readyToDrop = new ArrayList<>();

            // Collect all tunnels ready for phase 1 (pool removal)
            Iterator<TunnelExpiration> iter = _expirationQueue.iterator();
            boolean foundWork = false;
            while (iter.hasNext()) {
                TunnelExpiration te = iter.next();
                if (te.expirationTime <= now && !te.phase1Complete) {
                    readyToExpire.add(te);
                    foundWork = true;
                } else if (te.dropTime <= now && te.phase1Complete) {
                    readyToDrop.add(te);
                    foundWork = true;
                }
                if (readyToExpire.size() + readyToDrop.size() >= maxPerRun - processedInThisRun) {
                    break;
                }
            }

            if (readyToExpire.isEmpty() && readyToDrop.isEmpty()) {
                break;
            }

            processedInThisRun += readyToExpire.size() + readyToDrop.size();

            // Phase 1: Remove from tunnel pools
            for (TunnelExpiration te : readyToExpire) {
                PooledTunnelCreatorConfig cfg = te.config;
                TunnelPool pool = cfg.getTunnelPool();
                if (pool != null) {
                    pool.removeTunnel(cfg);
                }
                te.phase1Complete = true;
            }

            // Phase 2: Remove from dispatcher
            for (TunnelExpiration te : readyToDrop) {
                PooledTunnelCreatorConfig cfg = te.config;
                getContext().tunnelDispatcher().remove(cfg);
                _expirationQueue.remove(te);
            }
        }

        if (isBackedUp && processedInThisRun > 0 && _log.shouldWarn()) {
            _log.warn("ExpireJobManager processed " + processedInThisRun + " expirations, " +
                      _expirationQueue.size() + " remaining");
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
                        // If starved, use a shorter timeout to recover quickly
                        nextRun = now + BATCH_WINDOW / 4;
                        if (_log.shouldWarn()) {
                            _log.warn("ExpireJobManager starved for " + (lag / 1000) + "s - forcing recovery requeue");
                        }
                    } else if (stillBackedUp) {
                        // If still backed up, run again quickly to clear the queue
                        nextRun = now + BATCH_WINDOW / 2;
                    } else {
                        nextRun = Math.min(nextExpiration, now + BATCH_WINDOW);
                    }
                    getTiming().setStartAfter(nextRun);
                    _isScheduled = true;
                    getContext().jobQueue().addJob(this);
                }
            }
        } else if (_lastRunTime > 0 && (now - _lastRunTime) > MAX_ACCEPTABLE_LAG) {
            // Even with empty queue, check periodically to ensure we're still alive
            // This prevents total starvation when queue was full during normal scheduling
            synchronized (this) {
                if (!_isScheduled) {
                    long nextRun = now + BATCH_WINDOW;
                    getTiming().setStartAfter(nextRun);
                    _isScheduled = true;
                    getContext().jobQueue().addJob(this);
                    if (_log.shouldWarn()) {
                        _log.warn("ExpireJobManager health check - requeueing after " + ((now - _lastRunTime) / 1000) + "s idle");
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
