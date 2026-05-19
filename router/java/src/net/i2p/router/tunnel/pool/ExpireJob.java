package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Handles tunnel expiration in two phases for graceful shutdown.
 * Uses batch processing to reduce job queue overhead.
 * First run removes tunnel from LeaseSet and triggers refresh.
 * Second run stops accepting data for the tunnel entirely.
 * Uses randomized early expiration with different timing for inbound vs outbound tunnels.
 *
 * @since 0.9.68+ Batch expiry
 */
class ExpireJob extends JobImpl {
    private static final ConcurrentHashMap<Long, TunnelExpiration> _expirations = new ConcurrentHashMap<>();
    private static volatile boolean _isScheduled = false;

    private final PooledTunnelCreatorConfig _cfg;
    private final long _dropAfter;
    private volatile boolean _phase1Complete = false;

    private static final long BATCH_WINDOW = 5 * 1000;
    private static final long OB_EARLY_EXPIRE = 30*1000;
    private static final long IB_EARLY_EXPIRE = OB_EARLY_EXPIRE + 7500;
    /** Keep tunnels alive for 10 minutes after LeaseSet refresh so clients
     *  with cached (stale) LeaseSets can still connect using old tunnel IDs. */
    private static final long LEASESET_GRACE_PERIOD = 10 * 60 * 1000;
    private static final long MAX_ENTRY_LIFETIME = 15 * 60 * 1000L;
    private static final int MAX_ITERATE_PER_RUN = 2000;
    private static final int MAX_ITERATE_BACKED_UP = 5000;
    private static final int BACKED_UP_THRESHOLD = 100;

    @Override
    public String getName() {return "Expire Local Tunnels";}

    /**
     * Schedule a tunnel for batched expiration.
     * Applies randomized early expiration to reduce the chance of
     * LeaseSet/tunnel mismatch during the transition period.
     * @param ctx the router context
     * @param cfg the tunnel config to expire
     */
    public static void scheduleExpiration(RouterContext ctx, PooledTunnelCreatorConfig cfg) {
        Long key = getTunnelKey(cfg);
        if (key == null) {
            return;
        }
        if (_expirations.get(key) != null) {
            return;
        }
        long expire = cfg.getExpiration();
        // Apply randomized early expiration so tunnels expire before their
        // official expiration time, giving LeaseSet refresh time to propagate
        if (cfg.getTunnelPool().getSettings().isInbound()) {
            expire -= IB_EARLY_EXPIRE + ctx.random().nextLong(IB_EARLY_EXPIRE);
        } else {
            expire -= OB_EARLY_EXPIRE + ctx.random().nextLong(OB_EARLY_EXPIRE);
        }
        cfg.setExpiration(expire);
        // Keep tunnel alive for 10 minutes after phase 1 (LeaseSet refresh)
        // so clients with cached LeaseSets can still connect using old tunnel IDs.
        long dropAfter = expire + LEASESET_GRACE_PERIOD;
        TunnelExpiration te = new TunnelExpiration(cfg, key, expire, dropAfter, ctx.clock().now());
        _expirations.put(key, te);

        if (ctx.logManager().getLog(ExpireJob.class).shouldInfo()) {
            ctx.logManager().getLog(ExpireJob.class).info("Scheduled expiration for tunnel " + key +
                " at " + expire + " (drop at " + dropAfter + ")");
        }

        synchronized (ExpireJob.class) {
            if (!_isScheduled) {
                _isScheduled = true;
                ExpireJob job = new ExpireJob(ctx);
                long now = ctx.clock().now();
                long delay = expire - now;
                if (delay < 0) delay = 0;
                if (delay > BATCH_WINDOW) delay = BATCH_WINDOW;
                job.getTiming().setStartAfter(now + delay);
                ctx.jobQueue().addJob(job);
            }
        }
    }

    private static Long getTunnelKey(PooledTunnelCreatorConfig cfg) {
        if (cfg == null) return null;
        try {
            long recvId = cfg.getReceiveTunnelId(0).getTunnelId();
            long sendId = cfg.getSendTunnelId(0).getTunnelId();
            if (recvId != 0 && sendId != 0) {
                return Long.valueOf((recvId << 32) | (sendId & 0xFFFFFFFFL));
            }
            return Long.valueOf(System.identityHashCode(cfg));
        } catch (Exception e) {
            return Long.valueOf(System.identityHashCode(cfg));
        }
    }

    /**
     * Constructor for batch job (no config)
     */
    private ExpireJob(RouterContext ctx) {
        super(ctx);
        _cfg = null;
        _dropAfter = 0;
    }

    public void runJob() {
        synchronized (ExpireJob.class) {
            _isScheduled = false;
        }
        long now = getContext().clock().now();
        int startSize = _expirations.size();
        Log log = getContext().logManager().getLog(ExpireJob.class);

        List<TunnelExpiration> readyToExpire = new ArrayList<>();
        List<TunnelExpiration> readyToDrop = new ArrayList<>();

        boolean isBackedUp = startSize > BACKED_UP_THRESHOLD;
        int maxIterate = isBackedUp ? MAX_ITERATE_BACKED_UP : MAX_ITERATE_PER_RUN;

        int iterated = 0;
        Iterator<Map.Entry<Long, TunnelExpiration>> it = _expirations.entrySet().iterator();
        while (it.hasNext()) {
            if (iterated++ >= maxIterate) {
                if (!isBackedUp) {
                    break;
                }
                if (iterated >= maxIterate * 2) {
                    break;
                }
            }

            Map.Entry<Long, TunnelExpiration> entry = it.next();
            TunnelExpiration te = entry.getValue();
            if (te == null) {
                it.remove();
                continue;
            }
            if (now - te.createdAt > MAX_ENTRY_LIFETIME) {
                it.remove();
                continue;
            }
            if (te.expirationTime <= now && !te.phase1Complete) {
                readyToExpire.add(te);
            } else if (te.dropTime <= now && te.phase1Complete) {
                readyToDrop.add(te);
            }
        }

        Set<TunnelPool> poolsToRefresh = new HashSet<>();
        for (TunnelExpiration te : readyToExpire) {
            PooledTunnelCreatorConfig cfg = te.config;
            if (cfg == null) {
                te.phase1Complete = true;
                continue;
            }
            TunnelPool pool = cfg.getTunnelPool();
            if (pool == null) {
                te.phase1Complete = true;
                continue;
            }
            pool.removeTunnel(cfg);
            poolsToRefresh.add(pool);
            te.phase1Complete = true;
            if (log.shouldInfo()) {
                log.info("Phase 1 complete for tunnel " + te.tunnelKey +
                    " (removed from pool, LeaseSet refresh pending)");
            }
            // Never allow immediate phase 2 - always enforce grace period
            // even if the job runs late, to give clients with cached LeaseSets
            // time to transition to the new tunnels
        }

        // Force refresh all pools - bypass throttling to ensure LeaseSets
        // are updated immediately before tunnels are dropped
        for (TunnelPool pool : poolsToRefresh) {
            pool.refreshLeaseSet(true);
        }

        for (TunnelExpiration te : readyToDrop) {
            PooledTunnelCreatorConfig cfg = te.config;
            if (cfg != null) {
                getContext().tunnelDispatcher().remove(cfg);
                if (log.shouldInfo()) {
                    log.info("Phase 2 complete for tunnel " + te.tunnelKey +
                        " (removed from dispatcher)");
                }
            }
            _expirations.remove(te.tunnelKey);
        }

        int remaining = _expirations.size();
        if (log.shouldInfo() && (readyToExpire.size() > 0 || readyToDrop.size() > 0)) {
            log.info("ExpireJob processed " + readyToExpire.size() + " phase 1, " +
                readyToDrop.size() + " phase 2, " + remaining + " remaining");
        }
        // Reschedule if there are remaining entries to process
        if (remaining > 0) {
            synchronized (ExpireJob.class) {
                if (!_isScheduled) {
                    _isScheduled = true;
                    ExpireJob nextJob = new ExpireJob(getContext());
                    nextJob.getTiming().setStartAfter(now + BATCH_WINDOW);
                    getContext().jobQueue().addJob(nextJob);
                }
            }
        }
    }

    private static class TunnelExpiration {
        final PooledTunnelCreatorConfig config;
        final Long tunnelKey;
        final long expirationTime;
        final long dropTime;
        final long createdAt;
        volatile boolean phase1Complete = false;

        TunnelExpiration(PooledTunnelCreatorConfig cfg, Long key, long expire, long drop, long created) {
            this.config = cfg;
            this.tunnelKey = key;
            this.expirationTime = expire;
            this.dropTime = drop;
            this.createdAt = created;
        }
    }
}
