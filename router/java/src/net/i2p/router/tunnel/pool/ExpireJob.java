package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.router.JobImpl;
import net.i2p.router.TunnelTestStatus;
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

    private static final long BATCH_WINDOW = 5 * 1000L;
    private static final long OB_EARLY_EXPIRE = 30*1000L;
    private static final long IB_EARLY_EXPIRE = OB_EARLY_EXPIRE + 7500;
    /** Keep tunnels alive for 10 minutes after LeaseSet refresh so clients
     *  with cached (stale) LeaseSets can still connect using old tunnel IDs. */
    private static final long LEASESET_GRACE_PERIOD = 10 * 60 * 1000L;
    // Must be greater than tunnel lifetime (10 min) + LEASESET_GRACE_PERIOD (10 min)
    // to allow Phase 2 (dispatcher removal) to fire.  With early expiration,
    // entries live up to ~19 min total; 25 min provides safety margin for
    // clock skew and slow job execution.
    private static final long MAX_ENTRY_LIFETIME = 25 * 60 * 1000L;
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
        TunnelPool pool = cfg.getTunnelPool();
        if (pool.getSettings().isInbound()) {
            if (pool.getSettings().isExploratory()) {
                expire -= IB_EARLY_EXPIRE + ctx.random().nextLong(IB_EARLY_EXPIRE);
            } else {
                // Non-exploratory (client) pools: wider early expiration window
                // (60-120s) spreads tunnel expirations over a 2-minute window
                // instead of the old 45s, preventing synchronized mass expiry.
                // Replacement builds are triggered in ExpireJob.phase1 BEFORE
                // tunnels are removed, so this is safe.
                expire -= 60*1000L + ctx.random().nextLong(60*1000L);
            }
        } else {
            if (pool.getSettings().isExploratory()) {
                expire -= OB_EARLY_EXPIRE + ctx.random().nextLong(OB_EARLY_EXPIRE);
            } else {
                expire -= 60*1000L + ctx.random().nextLong(60*1000L);
            }
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

    /**
     *  Remove a tunnel from the expiration queue.
     *  Called during pool shutdown to prevent memory leaks.
     *  @param cfg config to remove
     *  @since 0.9.69+
     */
    public static void removeFromExpiration(PooledTunnelCreatorConfig cfg) {
        if (cfg == null) return;
        Long key = getTunnelKey(cfg);
        if (key != null) {_expirations.remove(key);}
    }

    /**
     *  Get the unique key for this tunnel config.
     *  @return key or null
     *  @since 0.9.69+
     */
    public static Long getTunnelKey(PooledTunnelCreatorConfig cfg) {
        if (cfg == null) return null;
        try {
            net.i2p.data.TunnelId recvIdObj = cfg.getReceiveTunnelId(0);
            net.i2p.data.TunnelId sendIdObj = cfg.getSendTunnelId(0);
            if (recvIdObj == null || sendIdObj == null) {
                // Tunnel partially cleaned up — use identity hash fallback
                TunnelPool pool = cfg.getTunnelPool();
                int poolHash = (pool != null) ? System.identityHashCode(pool) : 0;
                return Long.valueOf(((long)poolHash << 32) | (System.identityHashCode(cfg) & 0xFFFFFFFFL));
            }
            long recvId = recvIdObj.getTunnelId();
            long sendId = sendIdObj.getTunnelId();
            if (recvId != 0 && sendId != 0) {
                return Long.valueOf((recvId << 32) | (sendId & 0xFFFFFFFFL));
            }
            // Fallback: include pool identity to avoid collisions across pools
            TunnelPool pool = cfg.getTunnelPool();
            int poolHash = (pool != null) ? System.identityHashCode(pool) : 0;
            return Long.valueOf(((long)poolHash << 32) | (System.identityHashCode(cfg) & 0xFFFFFFFFL));
        } catch (Exception e) {
            TunnelPool pool = cfg.getTunnelPool();
            int poolHash = (pool != null) ? System.identityHashCode(pool) : 0;
            return Long.valueOf(((long)poolHash << 32) | (System.identityHashCode(cfg) & 0xFFFFFFFFL));
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
        Log log = getContext().logManager().getLog(ExpireJob.class);

        try {
            runPhase(log, now);
        } catch (Exception e) {
            log.error("ExpireJob crashed — rescheduling anyway to keep lifecycle alive", e);
        }

        int remaining = _expirations.size();
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

    private void runPhase(Log log, long now) {
        List<TunnelExpiration> readyToExpire = new ArrayList<>();
        List<TunnelExpiration> readyToDrop = new ArrayList<>();

        boolean isBackedUp = _expirations.size() > BACKED_UP_THRESHOLD;
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
                if (log.shouldWarn()) {
                    log.warn("Evicting orphaned ExpireJob entry " + te.tunnelKey +
                        " (age=" + ((now - te.createdAt) / 1000) + "s, phase1=" + te.phase1Complete + ")");
                }
                it.remove();
                continue;
            }
            if (te.expirationTime <= now && !te.phase1Complete) {
                readyToExpire.add(te);
            } else if (te.dropTime <= now && te.phase1Complete) {
                readyToDrop.add(te);
            }
        }

        // Trigger replacement builds BEFORE removing tunnels so the build pipeline
        // is primed when the pool shrinks.  Without this, removeTunnel() calls
        // ensureSufficientTunnels() which may not build if UNTESTED/TESTING tunnels
        // inflate the count — by then the pool has already lost a tunnel.
        Set<TunnelPool> poolsToPreBuild = new HashSet<>();
        for (TunnelExpiration te : readyToExpire) {
            PooledTunnelCreatorConfig cfg = te.config;
            if (cfg == null) continue;
            TunnelPool pool = cfg.getTunnelPool();
            if (pool != null) {poolsToPreBuild.add(pool);}
        }
        for (TunnelPool pool : poolsToPreBuild) {
            pool.ensureSufficientTunnels();
        }

        Set<TunnelPool> poolsToRefresh = new HashSet<>();
        int extendedCount = 0;
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

            // Don't remove UNTESTED tunnels — they haven't been tested yet.
            // Removing them before testing creates a death spiral where every
            // new build is removed before it can prove itself, resulting in 0
            // safe tunnels and perpetual EMERGENCY builds.
            if (cfg.getTestStatus() == TunnelTestStatus.UNTESTED
                    && te.untestedExtensions < MAX_UNTESTED_EXTENSIONS) {
                te.untestedExtensions++;
                te.expirationTime = now + UNTESTED_EXTENSION_MS;
                log.info("Extending UNTESTED tunnel " + te.tunnelKey +
                    " (+" + UNTESTED_EXTENSION_MS + "ms, extension " +
                    te.untestedExtensions + "/" + MAX_UNTESTED_EXTENSIONS + ")");
                extendedCount++;
                continue;
            }

            try {
                pool.removeTunnel(cfg);
            } catch (Exception e) {
                log.warn("Failed to remove tunnel " + te.tunnelKey + " from pool, marking Phase 1 done", e);
                te.phase1Complete = true;
                continue;
            }
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
        if (extendedCount > 0) {
            log.info("Extended " + extendedCount + " UNTESTED tunnels to allow testing");
        }

        // Refresh all affected pools so the LeaseSet is updated.
        // Uses non-forced refresh so the 5-minute REFRESH_THROTTLE
        // prevents cascading republishes on staggered tunnel expiries.
        for (TunnelPool pool : poolsToRefresh) {
            try {
                pool.refreshLeaseSet(false);
            } catch (Exception e) {
                log.warn("Failed to refresh LeaseSet", e);
            }
        }

        for (TunnelExpiration te : readyToDrop) {
            PooledTunnelCreatorConfig cfg = te.config;
            if (cfg != null) {
                try {
                    getContext().tunnelDispatcher().remove(cfg);
                } catch (Exception e) {
                    log.warn("Failed to remove tunnel " + te.tunnelKey + " from dispatcher", e);
                }
                if (log.shouldInfo()) {
                    log.info("Phase 2 complete for tunnel " + te.tunnelKey +
                        " (removed from dispatcher)");
                }
            }
            _expirations.remove(te.tunnelKey);
        }

        if (log.shouldInfo() && (readyToExpire.size() > 0 || readyToDrop.size() > 0)) {
            log.info("ExpireJob processed " + readyToExpire.size() + " phase 1, " +
                readyToDrop.size() + " phase 2, " + _expirations.size() + " remaining");
        }
    }

    /**
     *  Maximum number of times an UNTESTED tunnel's expiry can be extended
     *  to give tests time to complete.  Each extension is {@link #UNTESTED_EXTENSION_MS}.
     */
    static final int MAX_UNTESTED_EXTENSIONS = 6;

    /**
     *  How long to extend an UNTESTED tunnel's expiry each time.
     *  30s gives the test pipeline enough time for one full test cycle.
     */
    static final long UNTESTED_EXTENSION_MS = 30 * 1000L;

    private static class TunnelExpiration {
        final PooledTunnelCreatorConfig config;
        final Long tunnelKey;
        volatile long expirationTime;
        final long dropTime;
        final long createdAt;
        volatile boolean phase1Complete = false;
        int untestedExtensions = 0;

        TunnelExpiration(PooledTunnelCreatorConfig cfg, Long key, long expire, long drop, long created) {
            this.config = cfg;
            this.tunnelKey = key;
            this.expirationTime = expire;
            this.dropTime = drop;
            this.createdAt = created;
        }
    }
}
