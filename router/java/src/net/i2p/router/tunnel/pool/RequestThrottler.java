package net.i2p.router.tunnel.pool;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.networkdb.kademlia.KademliaNetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.CommSystemFacadeImpl;

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
//    private static final int LIFETIME_PORTION = 6;
    private static final int LIFETIME_PORTION = 4;
    private static boolean isSlow = SystemVersion.isSlow();
    private static boolean isQuadCore = SystemVersion.getCores() >= 4;
    private static boolean isHexaCore = SystemVersion.getCores() >= 6;
//    private static final int MIN_LIMIT = 45 / LIFETIME_PORTION;
//    private static final int MAX_LIMIT = 165 / LIFETIME_PORTION;
    private static final int MIN_LIMIT = (isSlow ? 32 : 64) / LIFETIME_PORTION;
    private static final int MAX_LIMIT = (isSlow ? 256 : isHexaCore ? 512 : 384) / LIFETIME_PORTION;
//    private static final int PERCENT_LIMIT = 12 / LIFETIME_PORTION;
    private static final int PERCENT_LIMIT = 25 / LIFETIME_PORTION;
    private static final long CLEAN_TIME = 11*60*1000 / LIFETIME_PORTION;
    private final static boolean DEFAULT_SHOULD_THROTTLE = true;
    private final static String PROP_SHOULD_THROTTLE = "router.enableTransitThrottle";
    private final static boolean DEFAULT_SHOULD_DISCONNECT = false;
    private final static String PROP_SHOULD_DISCONNECT = "router.enableImmediateDisconnect";

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
                             ri.getCapabilities().indexOf(Router.CAPABILITY_BW64) >= 0 ||
                             ri.getCapabilities().indexOf(Router.CAPABILITY_BW128) >= 0);
        boolean isFast = ri != null && (ri.getCapabilities().indexOf(Router.CAPABILITY_BW256) >= 0 ||
                         ri.getCapabilities().indexOf(Router.CAPABILITY_BW512) >= 0 ||
                         ri.getCapabilities().indexOf(Router.CAPABILITY_BW_UNLIMITED) >= 0);
        boolean isLTier = ri != null && (ri.getCapabilities().indexOf(Router.CAPABILITY_BW12) >= 0 ||
                          ri.getCapabilities().indexOf(Router.CAPABILITY_BW32) >= 0);
        int numTunnels = this.context.tunnelManager().getParticipatingCount();
        int portion = isSlow ? 6 : 4;
        int min = (isSlow ? MIN_LIMIT / 2 : MIN_LIMIT) / portion;
        int max = (isSlow ? MAX_LIMIT / 2 : MAX_LIMIT) / portion;
        int percent = (isSlow ? PERCENT_LIMIT / 4 * 3 : PERCENT_LIMIT) / portion;
//        int limit = Math.max(MIN_LIMIT, Math.min(max, numTunnels * percent / 100));
        int limit = isUnreachable || isLowShare ? Math.min(MIN_LIMIT, Math.max(MAX_LIMIT / 8, numTunnels * (PERCENT_LIMIT / 2) / 100))
                    : isFast ? Math.min((MIN_LIMIT * 3 / 2), Math.max(MAX_LIMIT * 3 / 2, numTunnels * (PERCENT_LIMIT) / 100))
                    : Math.min(MIN_LIMIT, (Math.max(MAX_LIMIT, numTunnels * (PERCENT_LIMIT) / 100)));
        int count = counter.increment(h);
        boolean rv = count > limit;
        boolean enableThrottle = context.getProperty(PROP_SHOULD_THROTTLE, DEFAULT_SHOULD_THROTTLE);
        boolean shouldDisconnect = context.getProperty(PROP_SHOULD_DISCONNECT, DEFAULT_SHOULD_DISCONNECT);
        boolean noSSU = true;
        boolean isFF = false;
        String MIN_VERSION = "0.9.61";
        String v = MIN_VERSION;
        String country = "unknown";
        boolean noCountry = true;
        boolean isOld = VersionComparator.comp(v, MIN_VERSION) < 0;
        long uptime = context.router().getUptime();
        if (ri != null) {
            for (RouterAddress ra : ri.getAddresses()) {
                if (ra.getTransportStyle().equals("SSU") ||
                    ra.getTransportStyle().equals("SSU2")) {
                    noSSU = false;
                    break;
                }
            }
            if (ri.getCapabilities().contains("f")) {
                isFF = true;
            }
            v = ri.getVersion();
            country = context.commSystem().getCountry(h);
            if (country != null && country != "unknown") {
                noCountry = false;
            }
        }

        if (SystemVersion.getCPULoad() > 95 && SystemVersion.getCPULoadAvg() > 95) {
            if (_log.shouldWarn())
                _log.warn("Rejecting tunnel request from Router [" + h.toBase64().substring(0,6) + "] -> " +
                          "CPU is under sustained high load");
        }

        if (isFF && (noSSU || isUnreachable)) {
            if (noSSU) {
                context.banlist().banlistRouter(h, " <b>➜</b> Floodfill with SSU disabled", null, null, context.clock().now() + 4*60*60*1000);
                if (_log.shouldWarn())
                    _log.warn("Banning Floodfill [" + h.toBase64().substring(0,6) + "] for 4h -> No SSU transport enabled");
            } else {
                context.banlist().banlistRouter(h, " <b>➜</b> Floodfill is unreachable / firewalled", null, null, context.clock().now() + 4*60*60*1000);
                if (_log.shouldWarn())
                    _log.warn("Banning Floodfill [" + h.toBase64().substring(0,6) + "] for 4h -> Unreachable / firewalled");
            }
            if (shouldDisconnect) {
                context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
            }
        }

/**
        if (uptime > 45*1000 && (noCountry && isLowShare) || (noCountry && isUnreachable)) {
            if (noCountry && isLowShare) {
                context.banlist().banlistRouter(h, " <b>➜</b> No GeoIP resolvable address and slow", null, null, context.clock().now() + 60*60*1000);
                if (_log.shouldWarn())
                    _log.warn("Banning " + (isFF ? "Floodfill" : "Router") + " [" + h.toBase64().substring(0,6) + "] for 1h -> " +
                              "Address not resolvable via GeoIP + slow tier");
            } else {
                if (isFF) {
                    context.banlist().banlistRouter(h, " <b>➜</b> Floodfill without GeoIP resolvable address", null, null, context.clock().now() + 15*60*1000);
                } else {
                    context.banlist().banlistRouter(h, " <b>➜</b> No GeoIP resolvable address", null, null, context.clock().now() + 15*60*1000);
                }
                if (_log.shouldWarn())
                    _log.warn("Banning " + (isFF ? "Floodfill" : "Router") + " [" + h.toBase64().substring(0,6) + "] for 15m -> " +
                              "Address not resolvable via GeoIP and unreachable");
            }
            if (shouldDisconnect) {
                context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
            }
        } else if (uptime > 45*1000 && noCountry) {
            if (isFF) {
                context.banlist().banlistRouter(h, " <b>➜</b> Floodfill without GeoIP resolvable address", null, null, context.clock().now() + 15*60*1000);
            } else {
                context.banlist().banlistRouter(h, " <b>➜</b> No GeoIP resolvable address", null, null, context.clock().now() + 15*60*1000);
            }
            if (_log.shouldWarn())
                _log.warn("Banning " + (isFF ? "Floodfill" : "Router") + " [" + h.toBase64().substring(0,6) + "] for 15m -> " +
                          "Address not resolvable via GeoIP");
            if (shouldDisconnect) {
                context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
            }
        }
**/

        if (isLTier && isUnreachable && isOld) {
            if (_log.shouldWarn() && !context.banlist().isBanlisted(h)) {
                _log.warn("Banning for 4h and immediately disconnecting from [" + h.toBase64().substring(0,6) + "] -> LU / " + v);
            }
            context.banlist().banlistRouter(h, " <b>➜</b> LU and older than current version", null, null, context.clock().now() + 4*60*60*1000);
            context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
        } else if (isOld && (isUnreachable || isLowShare)) {
            if (isUnreachable) {
                if (_log.shouldWarn())
                    _log.warn("Dropping all connections from [" + h.toBase64().substring(0,6) + "] -> Unreachable / " + v);
            } else {
                if (_log.shouldWarn())
                    _log.warn("Dropping all connections from [" + h.toBase64().substring(0,6) + "] -> Slow / " + v);
            }
            context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
        } else if (rv && enableThrottle) {
            if (count > limit * 5 / 3) {
                int bantime = (isLowShare || isUnreachable) ? 60*60*1000 : 30*60*1000;
                int period = bantime / 60 / 1000;
                if (count == (limit * 5 / 3) + 1) {
                    context.banlist().banlistRouter(h, " <b>➜</b> Excessive tunnel requests", null, null, context.clock().now() + bantime);
                    context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
                    if (_log.shouldWarn())
                        _log.warn("Banning " + (isLowShare || isUnreachable ? "slow or unreachable" : "") +
                                  " Router [" + h.toBase64().substring(0,6) + "] for " + period + "m" +
                                  "\n* Excessive tunnel requests (Requested: " + count + " / Hard limit " + (limit * 5 / 3) +
                                  " in " + (11*60 / portion) + "s)");
                } else {
                    if (_log.shouldInfo())
                        _log.info("Rejecting tunnel requests from temp banned Router [" + h.toBase64().substring(0,6) + "] -> " +
                                  "(Requested: " + count + " / Hard limit: " + (limit * 5 / 3) + " in " + (11*60 / portion) + "s)");
                    context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
                }
            } else {
                if (_log.shouldWarn())
                    _log.warn("Throttling tunnel requests from " + (isLowShare || isUnreachable ? "slow or unreachable" : "") +
                              " Router [" + h.toBase64().substring(0,6) + "] -> " +
                              "Requested: " + count + " / Hard limit: " + limit + " in " + (11*60 / portion) + "s");
            }
        }
        if (rv && count == 2 * limit) {
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
