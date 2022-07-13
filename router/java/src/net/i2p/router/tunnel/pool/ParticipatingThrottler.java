package net.i2p.router.tunnel.pool;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;

/**
 * Count how often we have accepted a tunnel with the peer
 * as the previous or next hop.
 * We limit each peer to a percentage of all participating tunnels,
 * subject to minimum and maximum values for the limit.
 *
 * This offers basic protection against simple attacks
 * but is not a complete solution, as by design, we don't know
 * the originator of a tunnel request.
 *
 * This also effectively limits the number of tunnels between
 * any given pair of routers, which probably isn't a bad thing.
 *
 * Note that the actual limits will be higher than specified
 * by up to 1 / LIFETIME_PORTION because the counter window resets.
 *
 * Note that the counts are of previous + next hops, so the total will
 * be higher than the participating tunnel count, and will also grow
 * as the network uses more 3-hop tunnels.
 *
 * @since 0.8.4
 */
class ParticipatingThrottler {
    private final RouterContext context;
    private final ObjectCounter<Hash> counter;
    private final Log _log;

    /** portion of the tunnel lifetime */
    private static final int LIFETIME_PORTION = 3;
//    private static final int MIN_LIMIT = 18 / LIFETIME_PORTION;
//    private static final int MAX_LIMIT = 66 / LIFETIME_PORTION;
    private static final int MIN_LIMIT = 64 / LIFETIME_PORTION;
    private static final int MAX_LIMIT = 256 / LIFETIME_PORTION;
//    private static final int PERCENT_LIMIT = 12 / LIFETIME_PORTION;
    private static final int PERCENT_LIMIT = 20 / LIFETIME_PORTION;
    private static final long CLEAN_TIME = 11*60*1000 / LIFETIME_PORTION;
    private boolean isSlow = SystemVersion.isSlow();
    private boolean isQuadCore = SystemVersion.getCores() >=4;

    public enum Result { ACCEPT, REJECT, DROP }

    ParticipatingThrottler(RouterContext ctx) {
        this.context = ctx;
        this.counter = new ObjectCounter<Hash>();
        _log = ctx.logManager().getLog(ParticipatingThrottler.class);
        ctx.simpleTimer2().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /** increments before checking */
    Result shouldThrottle(Hash h) {
        int numTunnels = this.context.tunnelManager().getParticipatingCount();
//        int limit = Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, numTunnels * PERCENT_LIMIT / 100));
        int limit = isSlow || !isQuadCore ? Math.max(MIN_LIMIT / 2, Math.max(MAX_LIMIT / 2, numTunnels * PERCENT_LIMIT / 100)) :
                                            Math.max(MIN_LIMIT, Math.max(MAX_LIMIT, numTunnels * PERCENT_LIMIT / 100));
        int count = counter.increment(h);
        Result rv;
        if (count > limit) {
            if (count > limit) {
                int random = (context.random().nextInt(90) * context.random().nextInt(90)) * 1000;
                int bantime = Math.max(random, (30 + context.random().nextInt(30)) * 60 * 1000);
                int period = bantime / 60 / 1000;
                context.banlist().banlistRouter(h, "Excessive participating tunnels", null, null, context.clock().now() + bantime);
                // drop after any accepted tunnels have expired
                context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
                if (_log.shouldWarn())
                    _log.warn("Temp banning router [" + h.toBase64().substring(0,6) + "] for " + period +
                              " minutes for excessive part. tunnel requests (Limit: " + limit + ")");
                rv = Result.DROP;
            } else {
                rv = Result.REJECT;
            }
        } else {
            rv = Result.ACCEPT;
        }
        return rv;
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            ParticipatingThrottler.this.counter.clear();
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
