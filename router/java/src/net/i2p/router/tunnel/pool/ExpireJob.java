package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;

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
    private static final long MAX_ENTRY_LIFETIME = 15 * 60 * 1000L;
    private static final int MAX_ITERATE_PER_RUN = 2000;
    private static final int MAX_ITERATE_BACKED_UP = 5000;
    private static final int BACKED_UP_THRESHOLD = 100;

    public ExpireJob(RouterContext ctx, PooledTunnelCreatorConfig cfg) {
        super(ctx);
        _cfg = cfg;
        long expire = cfg.getExpiration();
        if (cfg.getTunnelPool().getSettings().isInbound()) {
            _dropAfter = expire + (2 * Router.CLOCK_FUDGE_FACTOR);
            expire -= IB_EARLY_EXPIRE + ctx.random().nextLong(IB_EARLY_EXPIRE);
        } else {
            _dropAfter = expire + Router.CLOCK_FUDGE_FACTOR;
            expire -= OB_EARLY_EXPIRE + ctx.random().nextLong(OB_EARLY_EXPIRE);
        }
        cfg.setExpiration(expire);
        getTiming().setStartAfter(expire);
    }

    public String getName() {return "Expire Local Tunnels";}

    /**
     * Schedule a tunnel for batched expiration.
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
        boolean isInbound = cfg.getTunnelPool().getSettings().isInbound();
        long dropAfter;
        if (isInbound) {
            dropAfter = expire + (2 * Router.CLOCK_FUDGE_FACTOR);
        } else {
            dropAfter = expire + Router.CLOCK_FUDGE_FACTOR;
        }
        TunnelExpiration te = new TunnelExpiration(cfg, key, expire, dropAfter, ctx.clock().now());
        _expirations.put(key, te);

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
            long timeToDrop = te.dropTime - now;
            if (timeToDrop > 0) {
                continue;
            }
            readyToDrop.add(te);
        }

        for (TunnelPool pool : poolsToRefresh) {
            pool.refreshLeaseSet();
        }

        for (TunnelExpiration te : readyToDrop) {
            PooledTunnelCreatorConfig cfg = te.config;
            if (cfg != null) {
                getContext().tunnelDispatcher().remove(cfg);
            }
            _expirations.remove(te.tunnelKey);
        }

        int remaining = _expirations.size();
        // Don't reschedule here - scheduleExpiration() handles scheduling when new tunnels need expiry
        // This prevents exponential job queue growth from self-rescheduling
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
