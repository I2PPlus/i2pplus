package net.i2p.router.web.helpers;

import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SimpleTimer2.TimedEvent;
import net.i2p.util.SystemVersion;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A caching job that periodically performs:
 * - Reverse DNS precaching for known routers
 * - Introducer precaching for known routers
 *
 * Ensures:
 * - Only one instance is ever scheduled.
 * - Jobs don't run concurrently.
 * - Re-schedules itself after each run.
 */
public class NetDbCachingJob extends JobImpl {

    // Shared interval (in ms)
    private static final long INTERVAL = 30 * 60 * 1000L; // Every 30 minutes
    private static final long SCHEDULE_DELAY = 15 * 60 * 1000L; // First run after 15 minutes
    private static final boolean ENOUGH_RAM = SystemVersion.getMaxMemory() >= 1024*1024*1024;

    // Prevent duplicate scheduling
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);

    private final Log _log;
    private final NetDbRenderer _renderer;
    private final boolean _reverseDnsEnabled;
    private final boolean _introducerEnabled;

    private long _lastRunTime;

    public NetDbCachingJob(RouterContext ctx, boolean reverseDnsEnabled, boolean introducerEnabled) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _renderer = new NetDbRenderer(ctx);
        _reverseDnsEnabled = reverseDnsEnabled;
        _introducerEnabled = introducerEnabled;
        _lastRunTime = ctx.clock().now();
    }

    @Override
    public String getName() {
        return "NetDb RouterInfo PreCacher";
    }

    @Override
    public void runJob() {
        RouterContext ctx = getContext();
        long now = ctx.clock().now();

        // Don't run the job if < 1GB allocated
        if (!ENOUGH_RAM) {
            if (_log.shouldWarn()) {
                _log.warn("Insufficient RAM to run the NetDB RouterInfo Precacher Job -> 1GB needed!");
            }
            return;
        }

        // Only run if interval has passed
        if (now - _lastRunTime < INTERVAL) {
            schedule(ctx);
            return;
        }

        if (_log.shouldInfo()) {
            _log.info("Starting NetDb caching job...");
        }

        // Reverse DNS precaching
        if (_reverseDnsEnabled) {
            if (_log.shouldInfo()) {
                _log.info("Starting reverse DNS precaching...");
            }
            try {
                Set<RouterInfo> routers = new HashSet<>(ctx.netDb().getRouters());
                _renderer.precacheReverseDNSLookups(routers);
            } catch (Exception e) {
                if (_log.shouldError()) {
                    _log.error("Error during reverse DNS precaching", e);
                }
            }
        }

        // Introducer precaching
        if (_introducerEnabled) {
            if (_log.shouldInfo()) {
                _log.info("Starting introducer precaching...");
            }
            try {
                _renderer.precacheIntroducerInfos();
            } catch (Exception e) {
                if (_log.shouldError()) {
                    _log.error("Error during introducer precaching", e);
                }
            }
        }

        _lastRunTime = now;

        // Always re-schedule
        schedule(ctx);
    }

    /**
     * Schedule this job to run periodically.
     * Ensures only one instance is ever scheduled.
     */
    public static void schedule(RouterContext ctx) {
        if (SCHEDULED.get()) return;

        if (SCHEDULED.compareAndSet(false, true)) {
            SimpleTimer2 timer = ctx.simpleTimer2();
            new NetDbCachingTimer(ctx, timer, SCHEDULE_DELAY);
        }
    }

    /**
     * TimedEvent-based driver that triggers the NetDbCachingJob.
     */
    private static class NetDbCachingTimer extends TimedEvent {
        private final RouterContext _ctx;
        private final SimpleTimer2 _timer;

        NetDbCachingTimer(RouterContext ctx, SimpleTimer2 timer, long delay) {
            super(timer, delay);
            _ctx = ctx;
            _timer = timer;
        }

        @Override
        public void timeReached() {
            boolean reverseDnsEnabled = _ctx.getBooleanProperty("routerconsole.enableReverseLookups");
            String introProp = _ctx.getProperty("routerconsole.enableIntroducerPrecaching");
            boolean introducerEnabled = introProp == null || introProp.trim().isEmpty() ||
                                        introProp.equals("true");

            NetDbCachingJob job = new NetDbCachingJob(_ctx, reverseDnsEnabled, introducerEnabled);
            _ctx.jobQueue().addJob(job);

            // Schedule the next run using the same timer
            new NetDbCachingTimer(_ctx, _timer, INTERVAL);
            SCHEDULED.set(false);
        }
    }
}