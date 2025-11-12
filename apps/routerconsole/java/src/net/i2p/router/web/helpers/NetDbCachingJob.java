package net.i2p.router.web.helpers;

import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SimpleTimer2.TimedEvent;

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

    // Caching intervals (in ms)
    private static final long REVERSE_DNS_INTERVAL = 21 * 60 * 1000L; // 21 minutes
    private static final long INTRODUCER_PRECACHE_INTERVAL = 36 * 60 * 1000L; // 36 minutes
    private static final long SCHEDULE_DELAY = 11 * 60 * 1000L; // First run after 11 minutes

    // Prevent duplicate scheduling
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);

    private final Log _log;
    private final NetDbRenderer _renderer;
    private final boolean _reverseDnsEnabled;
    private final boolean _introducerEnabled;

    private long _lastReverseDNSRun;
    private long _lastIntroducerRun;

    public NetDbCachingJob(RouterContext ctx, boolean reverseDnsEnabled, boolean introducerEnabled) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _renderer = new NetDbRenderer(ctx);
        _reverseDnsEnabled = reverseDnsEnabled;
        _introducerEnabled = introducerEnabled;
        long now = ctx.clock().now();
        _lastReverseDNSRun = now;
        _lastIntroducerRun = now;
    }

    @Override
    public String getName() {
        return "NetDb RouterInfo PreCacher";
    }

    @Override
    public void runJob() {
        RouterContext ctx = getContext();
        long now = ctx.clock().now();

        boolean didWork = false;

        // Reverse DNS precaching
        if (_reverseDnsEnabled && (now - _lastReverseDNSRun >= REVERSE_DNS_INTERVAL)) {
            if (_log.shouldInfo()) {
                _log.info("Starting reverse DNS precaching...");
            }
            try {
                Set<RouterInfo> routers = new HashSet<>(ctx.netDb().getRouters());
                _renderer.precacheReverseDNSLookups(routers);
                _lastReverseDNSRun = now;
                didWork = true;
            } catch (Exception e) {
                if (_log.shouldError()) {
                    _log.error("Error during reverse DNS precaching", e);
                }
            }
        }

        // Introducer precaching
        if (_introducerEnabled && (now - _lastIntroducerRun >= INTRODUCER_PRECACHE_INTERVAL)) {
            if (_log.shouldInfo()) {
                _log.info("Starting introducer precaching...");
            }
            try {
                _renderer.precacheIntroducerInfos();
                _lastIntroducerRun = now;
                didWork = true;
            } catch (Exception e) {
                if (_log.shouldError()) {
                    _log.error("Error during introducer precaching", e);
                }
            }
        }

        // Always re-schedule, even if no work was done
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

        NetDbCachingTimer(RouterContext ctx, SimpleTimer2 timer, long delay) {
            super(timer, delay);
            _ctx = ctx;
        }

        @Override
        public void timeReached() {
            boolean reverseDnsEnabled = _ctx.getBooleanProperty("routerconsole.enableReverseLookups");
            String introProp = _ctx.getProperty("routerconsole.enableIntroducerPrecaching");
            boolean introducerEnabled = introProp == null || introProp.trim().isEmpty() ||
                                        introProp.equals("true");

            NetDbCachingJob job = new NetDbCachingJob(_ctx, reverseDnsEnabled, introducerEnabled);
            _ctx.jobQueue().addJob(job);
        }
    }
}