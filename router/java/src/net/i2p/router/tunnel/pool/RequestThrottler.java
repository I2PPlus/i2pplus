package net.i2p.router.tunnel.pool;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;

import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Like ParticipatingThrottler, but checked much earlier,
 * cleaned more frequently, and with more than double the min and max limits.
 * This is called before the request is queued or decrypted.
 *
 * @since 0.9.5
 */
class RequestThrottler {
    private final RouterContext context;
    private final ObjectCounter<Hash> counter;
    private final Log _log;

    /** portion of the tunnel lifetime */
    private static final int LIFETIME_PORTION = 4;
    private static boolean isSlow = SystemVersion.isSlow();
    private static final int MIN_LIMIT = (isSlow ? 512 : 1024) / LIFETIME_PORTION;
    private static final int MAX_LIMIT = (isSlow ? 1204 : 4096) / LIFETIME_PORTION;
    private static final int PERCENT_LIMIT = 25 / LIFETIME_PORTION;
    private static final long CLEAN_TIME = 11*60*1000 / LIFETIME_PORTION;
    private final static boolean DEFAULT_SHOULD_THROTTLE = true;
    private final static String PROP_SHOULD_THROTTLE = "router.enableTransitThrottle";
    private final static boolean DEFAULT_SHOULD_DISCONNECT = false;
    private final static String PROP_SHOULD_DISCONNECT = "router.enableImmediateDisconnect";
    private final static boolean DEFAULT_BLOCK_OLD_ROUTERS = true;
    private final static String PROP_BLOCK_OLD_ROUTERS = "router.blockOldRouters";

    RequestThrottler(RouterContext ctx) {
        this.context = ctx;
        this.counter = new ObjectCounter<Hash>();
        _log = ctx.logManager().getLog(RequestThrottler.class);
        ctx.simpleTimer2().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /** increments before checking */
    boolean shouldThrottle(Hash h) {
        RouterInfo ri = context.netDb().lookupRouterInfoLocally(h);
        boolean isUnreachable = ri != null && ri.getCapabilities().indexOf(Router.CAPABILITY_REACHABLE) < 0;
        boolean isLowShare = ri != null && (ri.getCapabilities().indexOf(Router.CAPABILITY_BW12) >= 0 ||
                             ri.getCapabilities().indexOf(Router.CAPABILITY_BW32) >= 0 ||
                             ri.getCapabilities().indexOf(Router.CAPABILITY_BW64) >= 0);
        boolean isFast = ri != null && (ri.getCapabilities().indexOf(Router.CAPABILITY_BW256) >= 0 ||
                         ri.getCapabilities().indexOf(Router.CAPABILITY_BW512) >= 0 ||
                         ri.getCapabilities().indexOf(Router.CAPABILITY_BW_UNLIMITED) >= 0);
        boolean isLTier = ri != null && (ri.getCapabilities().indexOf(Router.CAPABILITY_BW12) >= 0 ||
                          ri.getCapabilities().indexOf(Router.CAPABILITY_BW32) >= 0);
        boolean shouldBlockOldRouters = context.getProperty(PROP_BLOCK_OLD_ROUTERS, DEFAULT_BLOCK_OLD_ROUTERS);
        int numTunnels = this.context.tunnelManager().getParticipatingCount();
        int portion = isSlow ? 6 : 4;
        //int limit = (isUnreachable || isLowShare) ? MIN_LIMIT : Math.max(MIN_LIMIT, Math.min(MAX_LIMIT / 8, numTunnels * (isFast ? 3 / 2 : PERCENT_LIMIT / 2) / 100));
        int limit = Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, numTunnels * PERCENT_LIMIT / 100));
        int count = counter.increment(h);
        boolean rv = count > limit;
        boolean enableThrottle = context.getProperty(PROP_SHOULD_THROTTLE, DEFAULT_SHOULD_THROTTLE);
        boolean shouldDisconnect = context.getProperty(PROP_SHOULD_DISCONNECT, DEFAULT_SHOULD_DISCONNECT);
        boolean isFF = false;
        String v = "unknown";
        String country = "unknown";
        boolean isOld = false;
        long uptime = context.router().getUptime();
        long lag = context.jobQueue().getMaxLag();
        boolean highload = lag > 1000 && SystemVersion.getCPULoadAvg() > 95;
        if (ri != null) {
            isFF = ri.getCapabilities().contains("f");
            v = ri.getVersion();
            country = context.commSystem().getCountry(h);
            isOld = VersionComparator.comp(v, "0.9.61") < 0;
        }

        if (highload) {
            if (_log.shouldWarn())
                _log.warn("Rejecting Tunnel Request from Router [" + h.toBase64().substring(0,6) + "] -> " +
                          "CPU is under sustained high load");
        }
        else if (isLTier && isUnreachable && isOld) {
            if (_log.shouldWarn() && !context.banlist().isBanlisted(h)) {
                _log.warn("Banning for 4h and disconnecting from [" + h.toBase64().substring(0,6) + "] -> LU / " + v);
            }
            context.banlist().banlistRouter(h, " <b>➜</b> LU and older than current version", null, null, context.clock().now() + 4*60*60*1000);
            context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
        } else if (isOld && (isUnreachable || isLowShare) && shouldBlockOldRouters) {
            if (_log.shouldWarn()) {
                _log.warn("Dropping all connections from [" + h.toBase64().substring(0,6) + "] -> Unreachable / Slow / " + v);
            }
            context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
        } else if (rv && enableThrottle) {
            int bantime = (isLowShare || isUnreachable) ? 60*60*1000 : 30*60*1000;
            int period = bantime / 60 / 1000;
            if (count == limit + 1) {
                context.banlist().banlistRouter(h, " <b>➜</b> Excessive tunnel requests", null, null, context.clock().now() + bantime);
                context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
                if (_log.shouldWarn()) {
                    _log.warn("Banning " + (isLowShare || isUnreachable ? "slow or unreachable" : "") +
                              " Router [" + h.toBase64().substring(0,6) + "] for " + period + "m" +
                              "\n* Excessive tunnel requests (Requested: " + count + " / Hard limit " + limit +
                              " in " + (11*60 / portion) + "s)");
                }
            } else {
                if (_log.shouldInfo())
                    _log.info("Rejecting Tunnel Requests from temp banned Router [" + h.toBase64().substring(0,6) + "] -> " +
                              "(Requested: " + count + " / Hard limit: " + limit + " in " + (11*60 / portion) + "s)");
                context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
            }
        }

        if (rv && count >= 3 * limit && enableThrottle) {
            context.banlist().banlistRouter(h, "Excessive tunnel requests", null, null, context.clock().now() + 30*60*1000);
            // drop after any accepted tunnels have expired
            context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
            if (_log.shouldWarn())
                _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for 30m -> " +
                          "Excessive tunnel requests (Requested: " + count + " / Hard limit: " + limit + ")");
        }
        return rv;
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {RequestThrottler.this.counter.clear();}
    }

    /**
     *  @since 0.9.52
     */

    private class Disconnector implements SimpleTimer.TimedEvent {
        private final Hash h;
        public Disconnector(Hash h) { this.h = h; }
        public void timeReached() {context.commSystem().forceDisconnect(h);}
    }

}