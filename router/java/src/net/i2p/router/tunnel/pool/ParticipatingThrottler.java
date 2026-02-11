package net.i2p.router.tunnel.pool;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.BanLogger;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Count how often we have accepted a tunnel with the peer as the previous or next hop.
 * We limit each peer to a percentage of all participating tunnels, subject to minimum
 * and maximum values for the limit.
 *
 * This offers basic protection against simple attacks but is not a complete solution,
 * as by design, we don't know the originator of a tunnel request.
 *
 * This also effectively limits the number of tunnels between any given pair of routers,
 * which probably isn't a bad thing.
 *
 * @since 0.8.4
 */
class ParticipatingThrottler {
    private final RouterContext context;
    private final ObjectCounter<Hash> counter;
    private final Log _log;
    private static final BanLogger _banLogger = new BanLogger(null);
    private static final boolean isSlow = SystemVersion.isSlow();
    private static final boolean DEFAULT_BLOCK_OLD_ROUTERS = true;
    private static final boolean DEFAULT_SHOULD_DISCONNECT = false;
    private static final boolean DEFAULT_SHOULD_THROTTLE = true;
    private static final String PROP_BLOCK_OLD_ROUTERS = "router.blockOldRouters";
    private static final String PROP_SHOULD_DISCONNECT = "router.enableImmediateDisconnect";
    private static final String PROP_SHOULD_THROTTLE = "router.enableTransitThrottle";

    // Minimum tunnels per peer - prevents decay floor from being too aggressive at low counts
    private static final int MIN_LIMIT = isSlow ? 40 : 80;
    private static final String PROP_MIN_LIMIT = "router.participatingThrottlerMinLimit";
    // Maximum tunnels per peer - caps individual peer participation
    private static final int MAX_LIMIT = isSlow ? 60 : 120;
    private static final String PROP_MAX_LIMIT = "router.participatingThrottlerMaxLimit";
    // Percentage-based limit for fast peers - ~5% of total tunnels
    private static final int PERCENT_LIMIT = 5;
    private static final String PROP_PERCENT_LIMIT = "router.participatingThrottlerPercentLimit";
    // Cleanup interval in ms - 90 seconds
    private static final long CLEAN_TIME = 90 * 1000;
    private static final String MIN_VERSION = "0.9.65";

    /**
     * Result of throttling decision for tunnel participation requests.
     * Determines whether to accept, reject, or drop a tunnel request.
     */
    public enum Result { ACCEPT, REJECT, DROP }

    ParticipatingThrottler(RouterContext ctx) {
        this.context = ctx;
        this.counter = new ObjectCounter<Hash>();
        this._log = ctx.logManager().getLog(ParticipatingThrottler.class);
        _banLogger.initialize(ctx);
        ctx.simpleTimer2().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /**
     * Determines whether to throttle tunnel participation for the given router.
     * Increments the participation count for the router and evaluates conditions
     * including router version, capabilities, and request count limits to decide
     * if the tunnel request should be accepted, rejected, or dropped.
     *
     * @param h the hash of the router to check
     * @return the throttling Result (ACCEPT, REJECT, or DROP)
     */
    Result shouldThrottle(Hash h) {
        RouterInfo ri = (RouterInfo) context.netDb().lookupLocallyWithoutValidation(h);
        Hash us = context.routerHash();
        String caps = ri != null ? ri.getCapabilities() : "";
        boolean isUs = ri != null && us.equals(ri.getIdentity().getHash());
        boolean isUnreachable = ri != null && !isUs && (caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 ||
                                                        caps.indexOf(Router.CAPABILITY_REACHABLE) < 0);
        boolean isG = ri != null && !isUs && caps.indexOf(Router.CAPABILITY_NO_TUNNELS) >= 0;
        boolean isLowShare = ri != null && !isUs && (
            caps.indexOf(Router.CAPABILITY_BW12) >= 0 ||
            caps.indexOf(Router.CAPABILITY_BW32) >= 0 ||
            caps.indexOf(Router.CAPABILITY_BW64) >= 0 || isG);
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
        int bantime = isLU || isLowShare || isUnreachable ? 60*60*1000 : 4*60*60*1000;
        boolean shouldThrottle = context.getProperty(PROP_SHOULD_THROTTLE, DEFAULT_SHOULD_THROTTLE);
        boolean shouldDisconnect = context.getProperty(PROP_SHOULD_DISCONNECT, DEFAULT_SHOULD_DISCONNECT);
        boolean shouldBlockOldRouters = context.getProperty(PROP_BLOCK_OLD_ROUTERS, DEFAULT_BLOCK_OLD_ROUTERS);
        boolean isBanned = context.banlist().isBanlisted(h) ||
                           context.banlist().isBanlistedHostile(h) ||
                           context.banlist().isBanlistedForever(h);

        if (!isUs && isBanned) {return Result.DROP;} // return early if router's already banned

        String version = ri != null ? ri.getVersion() : "";

        if (version.equals("0") || version.equals("")) {
            handleNoVersion(shouldDisconnect, h, isBanned, caps, bantime);
            return Result.DROP;
        }
        if (checkVersionAndCompressibility(version, isCompressible, shouldDisconnect, h, isBanned, caps)) return Result.DROP;
        if (checkLowShareAndVersion(version, isLU, shouldBlockOldRouters, h, shouldDisconnect, isBanned, caps, bantime)) return Result.DROP;
        if (checkUnreachableAndOld(version, isUnreachable, isFast, shouldBlockOldRouters, h, shouldDisconnect, isBanned, caps)) return Result.DROP;

        rv = evaluateThrottleConditions(count, limit, shouldThrottle, isFast, isLowShare, isUnreachable, h, caps, isBanned, bantime);
        return rv;
    }

    /**
     * Calculates the participation limit for tunnels based on the number of tunnels
     * and router capabilities such as reachability and bandwidth share.
     *
     * Configurable via properties (0.9.68+):
     * - router.participatingThrottlerMinLimit: minimum tunnels per peer (default: 80)
     * - router.participatingThrottlerMaxLimit: maximum tunnels per peer (default: 120)
     * - router.participatingThrottlerPercentLimit: percentage of total tunnels (default: 5)
     *
     * @param numTunnels the current count of participating tunnels
     * @param isUnreachable true if the router is unreachable
     * @param isLowShare true if the router has low bandwidth share
     * @param isFast true if the router has high bandwidth share
     * @return the maximum allowed tunnel participation limit for the router
     * @since 0.9.68+ supports configurable limits
     */
    private int calculateLimit(int numTunnels, boolean isUnreachable, boolean isLowShare, boolean isFast) {
        // Load configurable limits with defaults
        int minLimit = context.getProperty(PROP_MIN_LIMIT, MIN_LIMIT);
        int maxLimit = context.getProperty(PROP_MAX_LIMIT, MAX_LIMIT);
        int percentLimit = context.getProperty(PROP_PERCENT_LIMIT, PERCENT_LIMIT);

        // Ensure reasonable bounds to prevent misconfiguration
        minLimit = Math.max(20, Math.min(200, minLimit));
        maxLimit = Math.max(40, Math.min(500, maxLimit));
        percentLimit = Math.max(2, Math.min(20, percentLimit));

        if (isUnreachable || isLowShare) {
            return Math.min(minLimit, Math.max(maxLimit / 20, numTunnels * (percentLimit / 5) / 100));
        } else if (isSlow) {
            return Math.min(minLimit, Math.max(maxLimit / 10, numTunnels * (percentLimit / 3) / 100));
        }
        return Math.min((minLimit * 3), Math.max(maxLimit / 2, numTunnels * percentLimit / 100));
    }

    /**
     * Handles banning and optionally disconnecting routers with no version info.
     *
     * @param shouldDisconnect whether to disconnect the router after banning
     * @param h the router hash
     * @param isBanned true if already banned
     * @param caps router capabilities string
     * @param bantime duration of the ban in milliseconds
     */
    private void handleNoVersion(boolean shouldDisconnect, Hash h, boolean isBanned, String caps, int bantime) {
        if (isBanned) {return;}
        if (shouldDisconnect) {context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);}
        if (_log.shouldWarn()) {
            _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for " + (bantime / 60000) + "m -> No router version in RouterInfo");
        }
        context.banlist().banlistRouter(h, " <b>➜</b> No version in RouterInfo", null, null, context.clock().now() + bantime);
        _banLogger.logBan(h, context, "No version in RouterInfo", bantime);
    }

    /**
     * Checks router version and compressibility of RouterInfo to decide on banning.
     *
     * @param version router software version string
     * @param isCompressible true if RouterInfo data is compressible (potentially suspicious)
     * @param shouldDisconnect whether to disconnect after banning
     * @param h router hash
     * @param isBanned true if already banned
     * @param caps router capabilities string
     * @return true if the router should be dropped (banned), false otherwise
     */
    private boolean checkVersionAndCompressibility(String version, boolean isCompressible, boolean shouldDisconnect, Hash h, boolean isBanned, String caps) {
        if (VersionComparator.comp(version, "0.9.57") < 0 && isCompressible) {
            if (isBanned) {return true;}
            if (shouldDisconnect) {context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);}
            if (_log.shouldWarn()) {
                _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for 24h -> Compressible RouterInfo / " + version);
            }
            context.banlist().banlistRouter(h, " <b>➜</b> Compressible RouterInfo & older than 0.9.57", null, null, context.clock().now() + 24*60*60*1000);
            _banLogger.logBan(h, context, "Compressible RouterInfo & older than 0.9.57", 24*60*60*1000);
            return true;
        }
        return false;
    }

    /**
     * Checks if a low-share, old-version router should be banned and disconnected.
     *
     * @param version router version string
     * @param isLU true if router is low share and unreachable
     * @param shouldBlockOldRouters whether to block old routers
     * @param h router hash
     * @param shouldDisconnect whether to disconnect after banning
     * @param isBanned true if already banned
     * @param caps router capabilities string
     * @param bantime ban duration in milliseconds
     * @return true if the router should be dropped (banned), false otherwise
     */
    private boolean checkLowShareAndVersion(String version, boolean isLU, boolean shouldBlockOldRouters, Hash h,
                                            boolean shouldDisconnect, boolean isBanned, String caps, int bantime) {
        if (VersionComparator.comp(version, MIN_VERSION) < 0 && isLU && shouldBlockOldRouters) {
            if (isBanned) {return true;}
            if (shouldDisconnect) {
                context.commSystem().forceDisconnect(h);
                if (_log.shouldWarn()) {
                    _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for " + (bantime / 60000) +
                              "m -> " + version + (caps.isEmpty() ? "" : " / " + caps));
                }
            }
            context.banlist().banlistRouter(h, " <b>➜</b> Old and slow (" + version + " / LU)", null, null, context.clock().now() + bantime);
            _banLogger.logBan(h, context, "Old and slow (" + version + " / LU)", bantime);
            return true;
        }
        return false;
    }

    /**
     * Checks if an unreachable and old router should be banned or ignored.
     *
     * @param version router version string
     * @param isUnreachable true if router is unreachable
     * @param isFast true if router has high bandwidth
     * @param shouldBlockOldRouters whether to block old routers
     * @param h router hash
     * @param shouldDisconnect whether to disconnect after banning
     * @param isBanned true if already banned
     * @param caps router capabilities string
     * @return true if the router should be dropped (ignored), false otherwise
     */
    private boolean checkUnreachableAndOld(String version, boolean isUnreachable, boolean isFast, boolean shouldBlockOldRouters, Hash h,
                                           boolean shouldDisconnect, boolean isBanned, String caps) {
        if (VersionComparator.comp(version, MIN_VERSION) < 0 && isUnreachable && shouldBlockOldRouters && !isFast) {
            if (shouldDisconnect) {context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);}
            if (_log.shouldWarn()) {
                _log.warn("Ignoring Tunnel Request from Router [" + h.toBase64().substring(0,6) + "] -> " + version + (caps.isEmpty() ? "" : " / " + caps));
            }
            return true;
        }
        return false;
    }

    /**
     * Evaluates whether to accept, reject, or drop tunnel requests based on the
     * current count relative to the limit and router capabilities.
     *
     * @param count current participation count for the router
     * @param limit maximum allowed participation
     * @param shouldThrottle true if throttling is enabled
     * @param isFast true if router is high bandwidth
     * @param isLowShare true if router is low bandwidth
     * @param isUnreachable true if router is unreachable
     * @param h router hash
     * @param caps router capabilities string
     * @param isBanned true if router is already banned
     * @param bantime ban duration in milliseconds
     * @return Result indicating ACCEPT, REJECT, or DROP action
     */
    private Result evaluateThrottleConditions(int count, int limit, boolean shouldThrottle, boolean isFast, boolean isLowShare,
                                              boolean isUnreachable, Hash h, String caps, boolean isBanned, int bantime) {
        if (count > limit && shouldThrottle) {
            if (isBanned) {return Result.DROP;}
            if (isFast && !isUnreachable && count > limit * 3) {
                handleExcessiveRequests(h, caps, count, limit, bantime);
                return Result.DROP;
            } else if (!isLowShare && !isUnreachable && count > limit * 2) {
                handleExcessiveRequests(h, caps, count, limit, bantime);
                return Result.DROP;
            } else if (isUnreachable && count > limit + 30) {
                handleExcessiveRequests(h, caps, count, limit, bantime);
                return Result.DROP;
            } else if (isLowShare && count > limit + 20) {
                handleExcessiveRequests(h, caps, count, limit, bantime);
                return Result.DROP;
            } else {
                _logHighRequestCount(h, caps, count, limit);
                return Result.REJECT;
            }
        }
        _logAcceptRequest(caps, count);
        return Result.ACCEPT;
    }

    /**
     * Handles excessive tunnel request counts by banning and scheduling disconnect.
     *
     * @param h router hash
     * @param caps router capabilities string
     * @param count current participation count
     * @param limit participation limit
     * @param bantime ban duration in milliseconds
     */
    private void handleExcessiveRequests(Hash h, String caps, int count, int limit, int bantime) {
        context.banlist().banlistRouter(h, " <b>➜</b> Excessive tunnel requests", null, null, context.clock().now() + bantime);
        _banLogger.logBan(h, context, "Excessive tunnel requests", bantime);
        context.simpleTimer2().addEvent(new Disconnector(h), 11 * 60 * 1000);
        if (_log.shouldWarn()) {
            _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for " + (bantime / 60000) +
                      "m -> Excessive tunnel requests -> Count / Limit: " +
                      count + " / " + limit + " in 90s");
        }
    }

    /** Logs warnings for high count tunnel request rejections. */
    private void _logHighRequestCount(Hash h, String caps, int count, int limit) {
        if (_log.shouldWarn()) {
            _log.warn("Rejecting Tunnel Requests from " + (caps != null ? caps : "") +
                      " Router [" + h.toBase64().substring(0,6) + "] -> Count / Limit: " +
                      count + " / " + limit + " in 90s");
        }
    }

    /** Logs debug information for accepted tunnel requests. */
    private void _logAcceptRequest(String caps, int count) {
        if (_log.shouldDebug()) {
            _log.debug("Accepting Tunnel Request from " + (caps != "" ? caps : "") +
                       " Router -> Count: " + count + " in 90s");
        }
    }

    /**
     * Periodic timer event that clears the participation counts to reset throttling.
     */
    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {counter.clear();}
    }

    /**
     * Timer event that disconnects a router after a delay.
     */
    private class Disconnector implements SimpleTimer.TimedEvent {
        private final Hash h;
        public Disconnector(Hash h) { this.h = h; }
        public void timeReached() {context.commSystem().forceDisconnect(h);}
    }
}