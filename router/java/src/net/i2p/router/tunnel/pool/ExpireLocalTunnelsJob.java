package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.TunnelId;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Manager for batching tunnel expirations to reduce job queue overhead.
 * Uses a single ConcurrentHashMap for O(1) atomic replace operations,
 * eliminating the queue-map desync issue.
 *
 * Each tunnel goes through two phases:
 * 1. First phase: Remove from tunnel pool (LeaseSet refresh)
 * 2. Second phase: Remove from tunnel dispatcher (stop accepting traffic)
 *
 * @since 0.9.68+
 */
public class ExpireLocalTunnelsJob extends JobImpl {
    private final ConcurrentHashMap<Long, TunnelExpiration> _tunnelExpirations;
    private volatile boolean _isScheduled = false;
    private volatile long _lastRunTime;
    private final Log _log;
    private int _runCount = 0;

    private static final long BATCH_WINDOW = 3 * 1000;
    private static final long FORCED_REQUEUE_TIMEOUT = 2 * BATCH_WINDOW;
    private static final long MAX_ACCEPTABLE_LAG = 10 * 1000;
    private static final int BACKED_UP_THRESHOLD = 5000;
    private static final long OB_EARLY_EXPIRE = 30 * 1000;
    private static final long IB_EARLY_EXPIRE = OB_EARLY_EXPIRE + 7500;
    private static final long MAX_ENTRY_LIFETIME = 30 * 1000L; // 30 seconds - aggressive cleanup
    private static final int MAX_PHASE1_RETRIES = 3;
    private static final int MAX_ITERATE_PER_RUN = 5000;
    private static final int MAX_ITERATE_BACKED_UP = 10000;
    private static final int MAX_ITERATE_EMERGENCY = 20000;
    private static final int HEALTH_LOG_INTERVAL = 200;

    public ExpireLocalTunnelsJob(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _tunnelExpirations = new ConcurrentHashMap<>();
        getTiming().setStartAfter(ctx.clock().now() + BATCH_WINDOW);
        _lastRunTime = ctx.clock().now();
        ctx.statManager().createRateStat("tunnel.expireCount", "Active expiration entries", "Tunnels",
            new long[] { 60*1000, 10*60*1000 });
    }

    @Override
    public String getName() {
        return "Expire Local Tunnels";
    }

    public static Long getTunnelKey(PooledTunnelCreatorConfig cfg) {
        if (cfg == null) return null;
        try {
            TunnelId recvId = cfg.getReceiveTunnelId(0);
            TunnelId sendId = cfg.getSendTunnelId(0);
            if (recvId != null && sendId != null && recvId.getTunnelId() != 0 && sendId.getTunnelId() != 0) {
                return Long.valueOf((recvId.getTunnelId() << 32) | (sendId.getTunnelId() & 0xFFFFFFFFL));
            }
            return cfg.getInstanceId();
        } catch (Exception e) {
            return cfg.getInstanceId();
        }
    }

    private void removeEntry(Long key) {
        if (key != null) {
            _tunnelExpirations.remove(key);
        }
    }

    private void logHealth(int runCount) {
        int size = _tunnelExpirations.size();
        if (runCount % HEALTH_LOG_INTERVAL == 0) {
            if (_log.shouldDebug()) {
                _log.debug("Expire entries: " + size);
            }
            getContext().statManager().addRateData("tunnel.expireCount", size);
        }
    }

    public void scheduleExpiration(PooledTunnelCreatorConfig cfg) {
        if (cfg == null || cfg.getTunnelPool() == null || cfg.getTunnelPool().getSettings() == null) {
            if (_log.shouldInfo()) {
                _log.info("Cannot schedule expiration for null config or pool");
            }
            return;
        }

        // Skip transit tunnels - they don't have a destination and don't need two-phase expiration
        // Only track exploratory (isExploratory) and client tunnels (has destination)
        if (cfg.getDestination() == null && !cfg.getTunnelPool().getSettings().isExploratory()) {
            return;
        }

        Long tunnelKey = getTunnelKey(cfg);
        if (tunnelKey == null) {
            if (_log.shouldDebug()) {
                _log.debug("Cannot generate tunnel key for config - skipping expiration schedule: " + cfg);
            }
            return;
        }

        int size = _tunnelExpirations.size();
        if (size > 50000) {
            if (_log.shouldInfo()) {
                _log.info("Dropping tunnel expiration scheduling -> " + size + " entries backlogged...");
            }
            return;
        }

        long actualExpiration = cfg.getExpiration();
        boolean isInbound = cfg.getTunnelPool().getSettings().isInbound();

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

        cfg.setExpiration(earlyExpire);

        TunnelExpiration te = new TunnelExpiration(cfg, tunnelKey, earlyExpire, dropAfter, getContext().clock().now());
        _tunnelExpirations.put(tunnelKey, te);

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
        int startSize = _tunnelExpirations.size();
        boolean isBackedUp = startSize > BACKED_UP_THRESHOLD;

        _runCount++;
        logHealth(_runCount);

        List<TunnelExpiration> readyToExpire = new ArrayList<>();
        Set<TunnelExpiration> readyToDrop = new HashSet<>();

        int maxIterate;
        if (startSize > 5000) {
            maxIterate = MAX_ITERATE_EMERGENCY;
            if (_log.shouldInfo()) {
                _log.info("Emergency drainage -> Processing " + startSize + " entries...");
            }
        } else if (isBackedUp) {
            maxIterate = MAX_ITERATE_BACKED_UP;
        } else {
            maxIterate = MAX_ITERATE_PER_RUN;
        }

        int iterated = 0;
        Iterator<Map.Entry<Long, TunnelExpiration>> it = _tunnelExpirations.entrySet().iterator();
        while (it.hasNext()) {
            if (iterated++ >= maxIterate) break;

            Map.Entry<Long, TunnelExpiration> entry = it.next();
            TunnelExpiration te = entry.getValue();
            if (te == null) {
                it.remove();
                continue;
            }

            if (te.expirationTime <= now && !te.phase1Complete) {
                readyToExpire.add(te);
            } else if (te.dropTime <= now && te.phase1Complete) {
                readyToDrop.add(te);
            } else if (now - te.createdAt > MAX_ENTRY_LIFETIME) {
                it.remove();
            }
        }

        if (!readyToExpire.isEmpty() || !readyToDrop.isEmpty()) {
            if (isBackedUp && _log.shouldDebug()) {
                _log.debug("Processing " + readyToExpire.size() + " expired, " +
                          readyToDrop.size() + " ready for drop...");
            }

            int removedCount = 0;
            int failedCount = 0;
            int forceCleanupCount = 0;

            for (TunnelExpiration te : readyToExpire) {
                PooledTunnelCreatorConfig cfg = te.config;
                if (cfg == null) {
                    removeEntry(te.tunnelKey);
                    te.phase1Complete = true;
                    forceCleanupCount++;
                    continue;
                }
                TunnelPool pool = cfg.getTunnelPool();
                if (pool == null) {
                    removeEntry(te.tunnelKey);
                    te.phase1Complete = true;
                    forceCleanupCount++;
                    continue;
                }
                boolean removed = false;
                boolean giveUp = false;

                if (now - te.createdAt > MAX_ENTRY_LIFETIME) {
                    giveUp = true;
                }

                if (!giveUp) {
                    try {
                        removed = pool.removeTunnelSynchronous(cfg);
                    } catch (Exception e) {
                        if (_log.shouldWarn()) {
                            _log.warn("Error in phase 1: " + e.getMessage(), e);
                        }
                    }
                }

                if (removed) {
                    te.config = null;
                    te.phase1Complete = true;
                    removedCount++;
                    // Note: Don't remove entry here - needed for phase 2 dispatcher.remove()
                } else {
                    te.retryCount++;
                    if (te.retryCount >= MAX_PHASE1_RETRIES || giveUp) {
                        forceCleanupCount++;
                        removeEntry(te.tunnelKey);
                        te.config = null;
                        te.phase1Complete = true;
                    } else {
                        failedCount++;
                    }
                }
            }

            if (removedCount > 0 || forceCleanupCount > 0 || failedCount > 0) {
                if (_log.shouldInfo()) {
                    _log.info("Phase 1: " + removedCount + " removed, " + forceCleanupCount + " force-cleaned, " + failedCount + " failed");
                }
            }

            for (TunnelExpiration te : readyToExpire) {
                if (te.phase1Complete && te.dropTime <= now) {
                    readyToDrop.add(te);
                }
            }

            int phase2Removed = 0;
            int phase2Skipped = 0;
            for (TunnelExpiration te : readyToDrop) {
                PooledTunnelCreatorConfig cfg = te.config;
                if (cfg == null) {
                    phase2Skipped++;
                } else {
                    try {
                        getContext().tunnelDispatcher().remove(cfg);
                        phase2Removed++;
                    } catch (Exception e) {
                        if (_log.shouldWarn()) {
                            _log.warn("Error in phase 2: " + e.getMessage(), e);
                        }
                    }
                }
                removeEntry(te.tunnelKey);
            }

            if (phase2Skipped > 0 && _log.shouldDebug()) {
                _log.debug("Phase 2 skipped " + phase2Skipped + " entries (config already cleared)");
            }

            if (phase2Removed > 0 && _log.shouldInfo()) {
                _log.info("Phase 2 complete: removed " + phase2Removed + " tunnels");
            }
        }

        int remaining = _tunnelExpirations.size();
        if (remaining > 0) {
            synchronized (this) {
                if (_isScheduled) {
                    return;
                }
                long lag = now - _lastRunTime;
                boolean isStarved = _lastRunTime > 0 && lag > FORCED_REQUEUE_TIMEOUT;
                boolean stillBackedUp = remaining > BACKED_UP_THRESHOLD;
                boolean hasPending = !readyToExpire.isEmpty() || !readyToDrop.isEmpty();

                if (isStarved || stillBackedUp || hasPending || lag > BATCH_WINDOW) {
                    long nextRun;
                    if (isStarved) {
                        nextRun = now;
                        if (_log.shouldInfo()) {
                            _log.info("Job starved for " + (lag / 1000) + "s -> Requeueing...");
                        }
                    } else if (stillBackedUp || hasPending) {
                        nextRun = now + BATCH_WINDOW / 4;
                    } else {
                        nextRun = now + BATCH_WINDOW;
                    }
                    getTiming().setStartAfter(nextRun);
                    _isScheduled = true;
                    getContext().jobQueue().addJob(this);
                }
            }
        } else if (_lastRunTime > 0 && (now - _lastRunTime) > MAX_ACCEPTABLE_LAG) {
            synchronized (this) {
                if (!_isScheduled) {
                    getTiming().setStartAfter(now + BATCH_WINDOW);
                    _isScheduled = true;
                    getContext().jobQueue().addJob(this);
                }
            }
        }
        _lastRunTime = now;
    }

    public void removeTunnel(PooledTunnelCreatorConfig cfg) {
        if (cfg == null) {
            return;
        }
        Long tunnelKey = getTunnelKey(cfg);
        if (tunnelKey != null) {
            _tunnelExpirations.remove(tunnelKey);
        }
    }

    private static class TunnelExpiration {
        PooledTunnelCreatorConfig config;
        final Long tunnelKey;
        final long expirationTime;
        final long dropTime;
        final long createdAt;
        volatile boolean phase1Complete = false;
        volatile int retryCount = 0;

        TunnelExpiration(PooledTunnelCreatorConfig cfg, Long key, long expire, long drop, long created) {
            this.config = cfg;
            this.tunnelKey = key;
            this.expirationTime = expire;
            this.dropTime = drop;
            this.createdAt = created;
        }
    }
}
