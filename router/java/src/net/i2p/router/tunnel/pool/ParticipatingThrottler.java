package net.i2p.router.tunnel.pool;


import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;

import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.networkdb.kademlia.KademliaNetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;

import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

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
    private static boolean isSlow = SystemVersion.isSlow();
    private static boolean isQuadCore = SystemVersion.getCores() >= 4;
    private static boolean isHexaCore = SystemVersion.getCores() >= 6;
    private final static boolean DEFAULT_BLOCK_OLD_ROUTERS = true;
    private final static boolean DEFAULT_SHOULD_DISCONNECT = false;
    private final static boolean DEFAULT_SHOULD_THROTTLE = true;
    private final static String PROP_BLOCK_OLD_ROUTERS = "router.blockOldRouters";
    private final static String PROP_SHOULD_DISCONNECT = "router.enableImmediateDisconnect";
    private final static String PROP_SHOULD_THROTTLE = "router.enableTransitThrottle";

    /** portion of the tunnel lifetime */
    private static final int LIFETIME_PORTION = 3;
//    private static final int MIN_LIMIT = 18 / LIFETIME_PORTION;
//    private static final int MAX_LIMIT = 66 / LIFETIME_PORTION;
    private static final int MIN_LIMIT = (isSlow ? 20 : isHexaCore ? 60 : 40) / LIFETIME_PORTION;
    private static final int MAX_LIMIT = (isSlow ? 100 : isHexaCore ? 500 : 400) / LIFETIME_PORTION;
//    private static final int PERCENT_LIMIT = 3 / LIFETIME_PORTION;
    private static final int PERCENT_LIMIT = 12 / LIFETIME_PORTION;
    private static final long CLEAN_TIME = 11*60*1000 / LIFETIME_PORTION;

    public enum Result { ACCEPT, REJECT, DROP }

    ParticipatingThrottler(RouterContext ctx) {
        this.context = ctx;
        this.counter = new ObjectCounter<Hash>();
        _log = ctx.logManager().getLog(ParticipatingThrottler.class);
        ctx.simpleTimer2().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /** increments before checking */
    Result shouldThrottle(Hash h) {
        RouterInfo ri = context.netDb().lookupRouterInfoLocally(h);
        Hash us = context.routerHash();
        String caps = ri != null ? ri.getCapabilities() : "";
        boolean isUs = ri != null && us.equals(ri.getIdentity().getHash());
        boolean isUnreachable = (ri != null && !isUs) && caps != "" && caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0;
        boolean isLowShare = (ri != null && !isUs) && (caps.indexOf(Router.CAPABILITY_BW12) >= 0 ||
                             caps.indexOf(Router.CAPABILITY_BW32) >= 0 ||
                             caps.indexOf(Router.CAPABILITY_BW64) >= 0 ||
                             caps.indexOf(Router.CAPABILITY_BW128) >= 0);
        boolean isFast = (ri != null && !isUs) && caps != "" && (caps.indexOf(Router.CAPABILITY_BW256) >= 0 ||
                         caps.indexOf(Router.CAPABILITY_BW512) >= 0 ||
                         caps.indexOf(Router.CAPABILITY_BW_UNLIMITED) >= 0);
        boolean isLU = (ri != null && !isUs) && isUnreachable && (caps.indexOf(Router.CAPABILITY_BW12) >= 0 || caps.indexOf(Router.CAPABILITY_BW32) >= 0);
        byte[] padding = ri != null ? ri.getIdentity().getPadding() : null;
        boolean isCompressible = padding != null && padding.length >= 64 && DataHelper.eq(padding, 0, padding, 32, 32);
        int numTunnels = this.context.tunnelManager().getParticipatingCount();
//        int limit = Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, numTunnels * PERCENT_LIMIT / 100));
        int limit = isUnreachable || isLowShare ? Math.min(MIN_LIMIT, Math.max(MAX_LIMIT / 12, numTunnels * (PERCENT_LIMIT / 8) / 100))
                    : isSlow ? Math.min(MIN_LIMIT, Math.max(MAX_LIMIT / 5, numTunnels * (PERCENT_LIMIT / 5) / 100))
                    : !isQuadCore ? Math.min((MIN_LIMIT * 3 / 2), Math.max(MAX_LIMIT / 4, numTunnels * (PERCENT_LIMIT / 4) / 100))
                    : (SystemVersion.getCores() >= 8 && SystemVersion.getMaxMemory() >= 2 * 1024*1024*1024) || isFast ?
                      Math.min((MIN_LIMIT * 3), Math.max(MAX_LIMIT / 2, numTunnels * (PERCENT_LIMIT / 2) / 100))
                    : Math.min(MIN_LIMIT * 2,(Math.max(MAX_LIMIT / 3, numTunnels * (PERCENT_LIMIT / 3) / 100)));
        int count = counter.increment(h);
        Result rv;
        int bantime = 30*60*1000;
        int period = bantime / 60 / 1000;
        boolean shouldThrottle = context.getProperty(PROP_SHOULD_THROTTLE, DEFAULT_SHOULD_THROTTLE);
        boolean shouldDisconnect = context.getProperty(PROP_SHOULD_DISCONNECT, DEFAULT_SHOULD_DISCONNECT);
        boolean shouldBlockOldRouters = context.getProperty(PROP_BLOCK_OLD_ROUTERS, DEFAULT_BLOCK_OLD_ROUTERS);
        boolean isBanned = context.banlist().isBanlisted(h);
        String v;
        if (ri != null) {
            if (ri.getVersion() != null) {v = ri.getVersion();}
            else {v = "0.0.0";}
        } else {v = "NoRI";}
        String MIN_VERSION = "0.9.61";

        if (v.equals("0.0.0")) {
            if (shouldDisconnect) {
                context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
            }
            rv = Result.DROP;
            if (_log.shouldWarn() && !isBanned)
                _log.warn("Banning " + (caps != "" ? caps : "") +
                          " Router [" + h.toBase64().substring(0,6) + "] for " + period + "m" +
                          " -> No router version in RouterInfo");
            context.banlist().banlistRouter(h, " <b>➜</b> No version in RouterInfo", null, null, context.clock().now() + bantime);
        } else if (VersionComparator.comp(v, "0.9.57") < 0 && isCompressible) {
            context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
            rv = Result.DROP;
            if (_log.shouldWarn() && !isBanned)
                _log.warn("Banning " + (caps != "" ? caps : "") + " Router [" + h.toBase64().substring(0,6) + "] for 4h" +
                          " -> Compressible RouterInfo / " + v);
            context.banlist().banlistRouter(h, " <b>➜</b> Compressible RouterInfo &amp; older than 0.9.57", null, null, context.clock().now() + 16*60*60*1000);
        } else if (VersionComparator.comp(v, MIN_VERSION) < 0 && isLU && shouldBlockOldRouters) {
            if (shouldDisconnect) {
                context.commSystem().forceDisconnect(h);
                if (_log.shouldWarn() && !isBanned)
                    _log.warn("Banning for " + (period*4) + "m and immediately disconnecting from Router [" + h.toBase64().substring(0,6) + "]" +
                              " -> " + v + (caps != "" ? " / " + caps : ""));
            } else {
                if (_log.shouldWarn() && !isBanned)
                    _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for " + (period*16) + "m" +
                              " -> " + v + (caps != "" ? " / " + caps : ""));
            }
            context.banlist().banlistRouter(h, " <b>➜</b> LU and older than current version", null, null, context.clock().now() + (bantime*4));
            rv = Result.DROP;
        } else if (VersionComparator.comp(v, MIN_VERSION) < 0 && isLowShare && shouldBlockOldRouters) {
            if (shouldDisconnect) {
                context.commSystem().forceDisconnect(h);
                if (_log.shouldWarn())
                    _log.warn("Ignoring Tunnel Request and immediately disconnecting from Router [" + h.toBase64().substring(0,6) +
                              "] -> " + v + (caps != "" ? " / " + caps : ""));
            } else {
                if (_log.shouldWarn())
                    _log.warn("Ignoring Tunnel Request from Router [" + h.toBase64().substring(0,6) +
                              "] -> " + v + (caps != "" ? " / " + caps : ""));
            }
            rv = Result.DROP;
        } else if (VersionComparator.comp(v, MIN_VERSION) < 0 && isUnreachable && shouldBlockOldRouters) {
            context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
            rv = Result.DROP;
            if (_log.shouldWarn())
                _log.warn("Ignoring Tunnel Request from Router [" + h.toBase64().substring(0,6) +
                          "] -> " + v + (caps != "" ? " / " + caps : ""));
        }

        if (count > limit && shouldThrottle) {
            if (isFast && !isUnreachable && count > limit * 11 / 9) {
                if (count == (limit * 11 / 9) + 1) {
                    context.banlist().banlistRouter(h, " <b>➜</b> Excessive tunnel requests", null, null, context.clock().now() + bantime);
                    // drop after any accepted tunnels have expired
                    context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
                    if (_log.shouldWarn() && !isBanned) {
                        _log.warn("Banning fast " + (caps != "" ? caps : "") +
                                  " Router [" + h.toBase64().substring(0,6) + "] for " + period + "m" +
                                  "\n* Excessive tunnel requests -> Count/limit: " + count + "/" + (limit * 11 / 9) +
                                  " in " + 11*60 / LIFETIME_PORTION + "s");
                    }
                } else {
                    context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
                    if (_log.shouldInfo())
                        _log.info("Ignoring Tunnel Requests from temp banned " + (caps != "" ? caps : "") + " Router [" + h.toBase64().substring(0,6) + "]" +
                                  "\n* Count/limit: " + count + "/" + (limit * 11 / 9) + " in " + (11*60 / LIFETIME_PORTION) + "s");
                }
                rv = Result.DROP;
            } else if (!isLowShare && !isUnreachable && count > limit * 10 / 9) {
                if (count == (limit * 10 / 9) + 1) {
                    context.banlist().banlistRouter(h, " <b>➜</b> Excessive tunnel requests", null, null, context.clock().now() + bantime);
                    // drop after any accepted tunnels have expired
                    context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
                    if (_log.shouldWarn() && !isBanned)
                        _log.warn("Banning mid-tier " + (caps != "" ? caps : "") + " Router [" + h.toBase64().substring(0,6) + "] for " + period + "m" +
                                  "\n* Excessive tunnel requests -> Count/limit: " + count + "/" + (limit * 10 / 9) +
                                  " in " + 11*60 / LIFETIME_PORTION + "s");
                } else {
                    if (_log.shouldInfo())
                        _log.info("Ignoring Tunnel Requests from temp banned " + (caps != "" ? caps : "") + " Router [" + h.toBase64().substring(0,6) + "]" +
                                  "\n* Count/limit: " + count + "/" + (limit * 10 / 9) + " in " + (11*60 / LIFETIME_PORTION) + "s");
                }
                rv = Result.DROP;
                //rv = Result.REJECT; // do we want to signal to the peer that we're busy?
            } else if ((isLowShare || isUnreachable) && count > limit * 7 / 3) {
                if (count == (limit * 7 / 3) + 1) {
                    context.banlist().banlistRouter(h, " <b>➜</b> Excessive tunnel requests", null, null, context.clock().now() + bantime);
                    // drop after any accepted tunnels have expired
                    context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
                    if (_log.shouldWarn() && !isBanned)
                        _log.warn("Banning slow or unreachable " + (caps != "" ? caps : "") + " Router [" + h.toBase64().substring(0,6) + "] for " + period + "m" +
                                  "\n* Excessive tunnel requests -> Count/limit: " + count + "/" + (limit * 7 / 3) +
                                  " in " + 11*60 / LIFETIME_PORTION + "s");
                } else {
                    context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
                    if (_log.shouldInfo())
                        _log.info("Ignoring Tunnel Requests from temp banned " + (caps != "" ? caps : "") + " Router [" + h.toBase64().substring(0,6) + "]" +
                                  "\n* Count/limit: " + count + "/" + (limit * 7 / 3) + " in " + (11*60 / LIFETIME_PORTION) + "s");
                }
                rv = Result.DROP;
            } else {
                rv = Result.REJECT;
                if (_log.shouldWarn())
                    _log.warn("Rejecting Tunnel Requests from " + (caps != null ? caps : "") + " Router [" + h.toBase64().substring(0,6) + "] " +
                              "\n* High number of requests -> Count/limit: " + count + "/" + limit + " in " + 11*60 / LIFETIME_PORTION + "s");
            }
        } else {
            rv = Result.ACCEPT;
            if (_log.shouldDebug())
                _log.debug("Accepting Tunnel Request from " + (caps != "" ? caps : "") + " Router [" + h.toBase64().substring(0,6) + "]" +
                           "\n* Count: " + count + " in " + 11*60 / LIFETIME_PORTION + "s");
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
