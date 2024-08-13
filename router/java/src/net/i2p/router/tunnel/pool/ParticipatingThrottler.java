package net.i2p.router.tunnel.pool;

import net.i2p.data.DataHelper;
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
 * @since 0.8.4
 */
class ParticipatingThrottler {
    private final RouterContext context;
    private final ObjectCounter<Hash> counter;
    private final Log _log;
    private static final boolean isSlow = SystemVersion.isSlow();
    private static final boolean isQuadCore = SystemVersion.getCores() >= 4;
    private static final boolean isHexaCore = SystemVersion.getCores() >= 6;
    private static final boolean DEFAULT_BLOCK_OLD_ROUTERS = true;
    private static final boolean DEFAULT_SHOULD_DISCONNECT = false;
    private static final boolean DEFAULT_SHOULD_THROTTLE = true;
    private static final String PROP_BLOCK_OLD_ROUTERS = "router.blockOldRouters";
    private static final String PROP_SHOULD_DISCONNECT = "router.enableImmediateDisconnect";
    private static final String PROP_SHOULD_THROTTLE = "router.enableTransitThrottle";

    /** portion of the tunnel lifetime */
    private static final int LIFETIME_PORTION = 3;
    private static final int MIN_LIMIT = (isSlow ? 20 : isHexaCore ? 60 : 40) / LIFETIME_PORTION;
    private static final int MAX_LIMIT = (isSlow ? 100 : isHexaCore ? 500 : 400) / LIFETIME_PORTION;
    private static final int PERCENT_LIMIT = 12 / LIFETIME_PORTION;
    private static final long CLEAN_TIME = 11 * 60 * 1000 / LIFETIME_PORTION;
    private static final String MIN_VERSION = "0.9.63";

    public enum Result { ACCEPT, REJECT, DROP }

    ParticipatingThrottler(RouterContext ctx) {
        this.context = ctx;
        this.counter = new ObjectCounter<Hash>();
        this._log = ctx.logManager().getLog(ParticipatingThrottler.class);
        ctx.simpleTimer2().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /** increments before checking */
    Result shouldThrottle(Hash h) {
        RouterInfo ri = context.netDb().lookupRouterInfoLocally(h);
        Hash us = context.routerHash();
        String caps = ri != null ? ri.getCapabilities() : "";
        boolean isUs = ri != null && us.equals(ri.getIdentity().getHash());
        boolean isUnreachable = ri != null && !isUs && caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0;
        boolean isLowShare = ri != null && !isUs && (
            caps.indexOf(Router.CAPABILITY_BW12) >= 0 ||
            caps.indexOf(Router.CAPABILITY_BW32) >= 0 ||
            caps.indexOf(Router.CAPABILITY_BW64) >= 0 ||
            caps.indexOf(Router.CAPABILITY_BW128) >= 0);
        boolean isFast = ri != null && !isUs && (
            caps.indexOf(Router.CAPABILITY_BW256) >= 0||
            caps.indexOf(Router.CAPABILITY_BW512) >= 0||
            caps.indexOf(Router.CAPABILITY_BW_UNLIMITED) >= 0);
        boolean isLU = isUnreachable && isLowShare;
        byte[] padding = ri != null ? ri.getIdentity().getPadding() : null;
        boolean isCompressible = padding != null && padding.length >= 64 && DataHelper.eq(padding, 0, padding, 32, 32);
        int numTunnels = context.tunnelManager().getParticipatingCount();
        int limit = calculateLimit(numTunnels, isUnreachable, isLowShare, isFast);
        int count = counter.increment(h);
        Result rv;
        int bantime = 30 * 60 * 1000;
        boolean shouldThrottle = context.getProperty(PROP_SHOULD_THROTTLE, DEFAULT_SHOULD_THROTTLE);
        boolean shouldDisconnect = context.getProperty(PROP_SHOULD_DISCONNECT, DEFAULT_SHOULD_DISCONNECT);
        boolean shouldBlockOldRouters = context.getProperty(PROP_BLOCK_OLD_ROUTERS, DEFAULT_BLOCK_OLD_ROUTERS);
        boolean isBanned = context.banlist().isBanlisted(h);
        String version = getRouterVersion(ri);

        if (version.equals("0.0.0")) {
            handleNoVersion(shouldDisconnect, h, isBanned, caps, bantime);
            return Result.DROP;
        }
        if (checkVersionAndCompressibility(version, isCompressible, shouldDisconnect, h, isBanned, caps)) return Result.DROP;
        if (checkLowShareAndVersion(version, isLU, shouldBlockOldRouters, h, shouldDisconnect, isBanned, caps, bantime)) return Result.DROP;
        if (checkUnreachableAndOld(version, isUnreachable, shouldBlockOldRouters, h, shouldDisconnect, isBanned, caps)) return Result.DROP;

        rv = evaluateThrottleConditions(count, limit, shouldThrottle, isFast, isLowShare, isUnreachable, h, caps, isBanned, bantime);
        return rv;
    }

    private int calculateLimit(int numTunnels, boolean isUnreachable, boolean isLowShare, boolean isFast) {
        if (isUnreachable || isLowShare) {return Math.min(MIN_LIMIT, Math.max(MAX_LIMIT / 12, numTunnels * (PERCENT_LIMIT / 8) / 100));}
        else if (isSlow) {return Math.min(MIN_LIMIT, Math.max(MAX_LIMIT / 5, numTunnels * (PERCENT_LIMIT / 5) / 100));}
        else if (!isQuadCore) {return Math.min(MIN_LIMIT * 3 / 2, Math.max(MAX_LIMIT / 4, numTunnels * (PERCENT_LIMIT / 4) / 100));}
        return Math.min((MIN_LIMIT * 3), Math.max(MAX_LIMIT / 2, numTunnels * (PERCENT_LIMIT / 2) / 100));
    }

    private String getRouterVersion(RouterInfo ri) {
        return (ri != null && ri.getVersion() != null) ? ri.getVersion() : "0.0.0";
    }

    private void handleNoVersion(boolean shouldDisconnect, Hash h, boolean isBanned, String caps, int bantime) {
        if (shouldDisconnect) {context.simpleTimer2().addEvent(new Disconnector(h), 60 * 1000);}
        if (!isBanned && _log.shouldWarn()) {
            _log.warn("Banning Router [" + h.toBase64().substring(0, 6) + "] for " + (bantime / 60000) + "m -> No router version in RouterInfo");
        }
        context.banlist().banlistRouter(h, " <b>➜</b> No version in RouterInfo", null, null, context.clock().now() + bantime);
    }

    private boolean checkVersionAndCompressibility(String version, boolean isCompressible, boolean shouldDisconnect, Hash h, boolean isBanned, String caps) {
        if (VersionComparator.comp(version, "0.9.57") < 0 && isCompressible) {
            if (shouldDisconnect) {context.simpleTimer2().addEvent(new Disconnector(h), 60 * 1000);}
            if (!isBanned && _log.shouldWarn()) {
                _log.warn("Banning Router [" + h.toBase64().substring(0, 6) + "] for 4h -> Compressible RouterInfo / " + version);
            }
            context.banlist().banlistRouter(h, " <b>➜</b> Compressible RouterInfo & older than 0.9.57",
                                            null, null, context.clock().now() + 16 * 60 * 60 * 1000);
            return true;
        }
        return false;
    }

    private boolean checkLowShareAndVersion(String version, boolean isLU, boolean shouldBlockOldRouters, Hash h, boolean shouldDisconnect, boolean isBanned, String caps, int bantime) {
        if (VersionComparator.comp(version, MIN_VERSION) < 0 && isLU && shouldBlockOldRouters) {
            if (shouldDisconnect) {
                context.commSystem().forceDisconnect(h);
                if (!isBanned && _log.shouldWarn()) {
                    _log.warn("Banning Router [" + h.toBase64().substring(0, 6) + "] for " + (bantime / 60000) + "m -> " + version + (caps.isEmpty() ? "" : " / " + caps));
                }
            }
            context.banlist().banlistRouter(h, " <b>➜</b> LU and older than current version", null, null, context.clock().now() + (bantime * 4));
            return true;
        }
        return false;
    }

    private boolean checkUnreachableAndOld(String version, boolean isUnreachable, boolean shouldBlockOldRouters, Hash h, boolean shouldDisconnect, boolean isBanned, String caps) {
        if (VersionComparator.comp(version, MIN_VERSION) < 0 && isUnreachable && shouldBlockOldRouters) {
            if (shouldDisconnect) {context.simpleTimer2().addEvent(new Disconnector(h), 60 * 1000);}
            if (_log.shouldWarn()) {
                _log.warn("Ignoring Tunnel Request from Router [" + h.toBase64().substring(0, 6) + "] -> " + version + (caps.isEmpty() ? "" : " / " + caps));
            }
            return true;
        }
        return false;
    }

    private Result evaluateThrottleConditions(int count, int limit, boolean shouldThrottle, boolean isFast, boolean isLowShare, boolean isUnreachable, Hash h, String caps, boolean isBanned, int bantime) {
        if (count > limit && shouldThrottle) {
            if (isFast && !isUnreachable) {
                if (count > limit * 11 / 9) {
                    handleExcessiveRequests(h, caps, count, limit, bantime);
                    return Result.DROP;
                }
            } else if (!isLowShare && !isUnreachable) {
                if (count > limit * 10 / 9) {
                    handleExcessiveRequests(h, caps, count, limit, bantime);
                    return Result.DROP;
                }
            } else if ((isLowShare || isUnreachable) && count > limit * 7 / 3) {
                handleExcessiveRequests(h, caps, count, limit, bantime);
                return Result.DROP;
            } else {
                _logHighRequestCount(caps, count, limit);
                return Result.REJECT;
            }
        }
        _logAcceptRequest(caps, count);
        return Result.ACCEPT;
    }

    private void handleExcessiveRequests(Hash h, String caps, int count, int limit, int bantime) {
        context.banlist().banlistRouter(h, " <b>➜</b> Excessive tunnel requests", null, null, context.clock().now() + bantime);
        context.simpleTimer2().addEvent(new Disconnector(h), 11 * 60 * 1000);
        if (_log.shouldWarn()) {
            _log.warn("Banning Router [" + h.toBase64().substring(0, 6) + "] for " + (bantime / 60000) + "m -> Excessive tunnel requests -> Count / Limit: " +
                count + " / " + limit + " in " + 11 * 60 / LIFETIME_PORTION + "s");
        }
    }

    private void _logHighRequestCount(String caps, int count, int limit) {
        if (_log.shouldWarn()) {
            _log.warn("Rejecting Tunnel Requests from " + (caps != null ? caps : "") + " Router -> Count / Limit: " + count + " / " + limit + " in " + 11 * 60 / LIFETIME_PORTION + "s");
        }
    }

    private void _logAcceptRequest(String caps, int count) {
        if (_log.shouldDebug()) {
            _log.debug("Accepting Tunnel Request from " + (caps != "" ? caps : "") + " Router -> Count: " + count + " in " + 11 * 60 / LIFETIME_PORTION + "s");
        }
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {counter.clear();}
    }

    private class Disconnector implements SimpleTimer.TimedEvent {
        private final Hash h;
        public Disconnector(Hash h) { this.h = h; }
        public void timeReached() {
            context.commSystem().forceDisconnect(h);
        }
    }
}