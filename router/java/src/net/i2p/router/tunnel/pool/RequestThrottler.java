package net.i2p.router.tunnel.pool;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;


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
//    private static final int LIFETIME_PORTION = 6;
    private static final int LIFETIME_PORTION = 4;
    private static boolean isSlow = SystemVersion.isSlow();
    private static boolean isQuadCore = SystemVersion.getCores() >=4;
//    private static final int MIN_LIMIT = 45 / LIFETIME_PORTION;
//    private static final int MAX_LIMIT = 165 / LIFETIME_PORTION;
    private static final int MIN_LIMIT = 64 / LIFETIME_PORTION;
    private static final int MAX_LIMIT = 512 / LIFETIME_PORTION;
//    private static final int PERCENT_LIMIT = 12 / LIFETIME_PORTION;
    private static final int PERCENT_LIMIT = 32 / LIFETIME_PORTION;
    private static final long CLEAN_TIME = 11*60*1000 / LIFETIME_PORTION;

    RequestThrottler(RouterContext ctx) {
        this.context = ctx;
        this.counter = new ObjectCounter<Hash>();
        _log = ctx.logManager().getLog(RequestThrottler.class);
        ctx.simpleTimer2().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /** increments before checking */
    boolean shouldThrottle(Hash h) {
        int portion = isSlow ? 6 : 4;
        int min = (isSlow ? MIN_LIMIT / 2 : MIN_LIMIT) / portion;
        int max = (isSlow ? MAX_LIMIT / 2 : MAX_LIMIT) / portion;
        int percent = (isSlow ? PERCENT_LIMIT / 4 * 3 : PERCENT_LIMIT) / portion;
        int numTunnels = this.context.tunnelManager().getParticipatingCount();
        int limit = Math.max(MIN_LIMIT, Math.min(max, numTunnels * percent / 100));
        int count = counter.increment(h);
        boolean rv = count > limit;
        if (rv) {
            if (count > limit * 10 / 9) {
                int bantime = 30*60*1000;
                int period = bantime / 60 / 1000;
                context.banlist().banlistRouter(h, " <b>➜</b> Excessive transit tunnels", null, null, context.clock().now() + bantime);
                context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
                _log.warn("Temp banning [" + h.toBase64().substring(0,6) + "] for " + period +
                          "m -> Excessive tunnel requests (Limit: " + (limit * 10 / 9) + " in " + (11*60 / portion) + "s)");
            } else {
                if (_log.shouldWarn())
                    _log.warn("Throttling tunnel requests from [" + h.toBase64().substring(0,6) + "]");
            }
        }
/*
        if (rv && count == 2 * limit) {
            context.banlist().banlistRouter(h, "Excess tunnel requests", null, null, context.clock().now() + 30*60*1000);
            // drop after any accepted tunnels have expired
            context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
            if (_log.shouldWarn())
                _log.warn("Banning router for excess tunnel requests, limit: " + limit + " count: " + count + ' ' + h.toBase64());
        }
*/
        return rv;
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            RequestThrottler.this.counter.clear();
        }
    }

    /**
     *  @since 0.9.52
     */

    private class Disconnector implements SimpleTimer.TimedEvent {
        private final Hash h;
        public Disconnector(Hash h) { this.h = h; }
        public void timeReached() {
            context.commSystem().forceDisconnect(h);
        }
    }

}
