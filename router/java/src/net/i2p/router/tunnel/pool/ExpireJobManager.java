package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;

import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;

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

    // Only run when tunnels are within this window of expiring (avoids unnecessary runs)
    private static final long BATCH_WINDOW = 90 * 1000;
    // Early expiration offsets to match original ExpireJob behavior
    private static final long OB_EARLY_EXPIRE = 30 * 1000;
    private static final long IB_EARLY_EXPIRE = OB_EARLY_EXPIRE + 7500;

    public ExpireJobManager(RouterContext ctx) {
        super(ctx);
        _expirationQueue = new PriorityBlockingQueue<>();
        getTiming().setStartAfter(ctx.clock().now() + BATCH_WINDOW);
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

        List<TunnelExpiration> readyToExpire = new ArrayList<>();
        List<TunnelExpiration> readyToDrop = new ArrayList<>();

        // Collect all tunnels ready for phase 1 (pool removal)
        Iterator<TunnelExpiration> iter = _expirationQueue.iterator();
        while (iter.hasNext()) {
            TunnelExpiration te = iter.next();
            if (te.expirationTime <= now && !te.phase1Complete) {
                readyToExpire.add(te);
            } else if (te.dropTime <= now && te.phase1Complete) {
                readyToDrop.add(te);
            }
        }

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

                // Only schedule if next expiration is within batch window
                // This avoids unnecessary job queue entries when tunnels are far from expiring
                if (nextExpiration <= now + BATCH_WINDOW) {
                    long nextRun = Math.min(nextExpiration, now + BATCH_WINDOW);
                    getTiming().setStartAfter(nextRun);
                    _isScheduled = true;
                    getContext().jobQueue().addJob(this);
                }
            }
        }
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
