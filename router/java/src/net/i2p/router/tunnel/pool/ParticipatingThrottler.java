package net.i2p.router.tunnel.pool;

import java.util.Arrays;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.BanLogger;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.stat.RateConstants;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;
import net.i2p.util.SimpleTimer2;

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
public class ParticipatingThrottler {
    private final RouterContext context;
    private final ObjectCounter<Hash> counter;
    private final Log _log;
    private BanLogger _banLogger;
    private static final boolean IS_SLOW = SystemVersion.isSlow();
    private static final boolean DEFAULT_BLOCK_OLD_ROUTERS = true;
    private static final boolean DEFAULT_SHOULD_DISCONNECT = false;
    private static final boolean DEFAULT_SHOULD_THROTTLE = true;
    private static final String PROP_BLOCK_OLD_ROUTERS = "router.blockOldRouters";
    private static final String PROP_SHOULD_DISCONNECT = "router.enableImmediateDisconnect";
    private static final String PROP_SHOULD_THROTTLE = "router.enableTransitThrottle";

    /** @since 0.9.70+ */
    public static volatile int _minLimit = SystemVersion.isSlow() ? 40 : 80;
    /** @since 0.9.70+ */
    public static volatile int _maxLimit = SystemVersion.isSlow() ? 150 : 300;
    /** @since 0.9.70+ */
    public static volatile int _percentLimit = 10;
    /** Rejection threshold: start rejecting at count/limit ratio this high (30-100, default 70%). @since 0.9.70+ */
    public static volatile int _rejectThreshold = 70;
    /** Rejection steepness: 100=linear ramp, 200=quadratic, higher=faster escalation. @since 0.9.70+ */
    public static volatile int _rejectSteepness = 200;
    /** Load weight: how much load inflates effective ratio (0-300%, default 100%). @since 0.9.70+ */
    public static volatile int _loadWeight = 100;

    /** @since 0.9.70+ */
    public static int getParticipatingMinLimit() { return _minLimit; }
    /** @since 0.9.70+ */
    public static void setParticipatingMinLimit(int val) { _minLimit = Math.max(20, Math.min(500, val)); }
    /** @since 0.9.70+ */
    public static int getParticipatingMaxLimit() { return _maxLimit; }
    /** @since 0.9.70+ */
    public static void setParticipatingMaxLimit(int val) { _maxLimit = Math.max(50, Math.min(1000, val)); }
    /** @since 0.9.70+ */
    public static int getParticipatingPctLimit() { return _percentLimit; }
    /** @since 0.9.70+ */
    public static void setParticipatingPctLimit(int val) { _percentLimit = Math.max(5, Math.min(100, val)); }
    /** @since 0.9.70+ */
    public static int getRejectThreshold() { return _rejectThreshold; }
    /** @since 0.9.70+ */
    public static void setRejectThreshold(int val) { _rejectThreshold = Math.max(30, Math.min(100, val)); }
    /** @since 0.9.70+ */
    public static int getRejectSteepness() { return _rejectSteepness; }
    /** @since 0.9.70+ */
    public static void setRejectSteepness(int val) { _rejectSteepness = Math.max(100, Math.min(500, val)); }
    /** @since 0.9.70+ */
    public static int getLoadWeight() { return _loadWeight; }
    /** @since 0.9.70+ */
    public static void setLoadWeight(int val) { _loadWeight = Math.max(0, Math.min(300, val)); }
    // Cleanup interval in ms - 90 seconds
    private static final long CLEAN_TIME = 90 * 1000L;
    private static final long[] RATES = { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR };
    private static final String MIN_VERSION = "0.9.66";

    /**
     * Result of throttling decision for tunnel participation requests.
     * Determines whether to accept, reject, or drop a tunnel request.
     */
    public enum Result { ACCEPT, REJECT, DROP }

    /**
     * Extract IP address and port from RouterInfo for logging to sessionbans.txt.
     * Returns IP:PORT format for IPv4 or [IPv6]:PORT format for IPv6.
     * Uses getCompatibleIP to return an IP for our supported protocols.
     *
     * @param router the RouterInfo to extract from
     * @return IP:PORT string or empty string if not available
     */
    private String getRouterIPPort(RouterInfo router) {
        if (router == null) { return ""; }
        try {
            // Try getCompatibleIP first - returns IP for our supported protocols
            byte[] ip = CommSystemFacadeImpl.getCompatibleIP(router);
            if (ip != null) {
                int port = 0;
                // Find port from addresses
                for (RouterAddress addr : router.getAddresses()) {
                    if (addr != null && addr.getIP() != null && Arrays.equals(addr.getIP(), ip)) {
                        port = addr.getPort();
                        break;
                    }
                }
                return formatIPPort(ip, port);
            }
            // Fallback to first available
            for (RouterAddress addr : router.getAddresses()) {
                if (addr != null && addr.getHost() != null) {
                    String ipAddr = addr.getHost();
                    int port = addr.getPort();
                    if (port > 0) {
                        if (ipAddr.contains(":") && !ipAddr.startsWith("[")) {
                            return "[" + ipAddr + "]:" + port;
                        } else {
                            return ipAddr + ":" + port;
                        }
                    } else {
                        return ipAddr;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore extraction errors
        }
        return "";
    }

    /**
     * Format IP byte array to string with port.
     */
    private String formatIPPort(byte[] ip, int port) {
        if (ip.length == 4) {
            return (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff) + ":" + port;
        } else if (ip.length == 16) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < 16; i += 2) {
                if (i > 0) sb.append(":");
                sb.append(String.format("%x", (ip[i] << 8 | ip[i + 1] & 0xff)));
            }
            sb.append("]").append(":").append(port);
            return sb.toString();
        }
        return "";
    }

    ParticipatingThrottler(RouterContext ctx) {
        this.context = ctx;
        this.counter = new ObjectCounter<>();
        this._log = ctx.logManager().getLog(ParticipatingThrottler.class);
        _banLogger = new BanLogger();
        _banLogger.initialize(ctx);
        // Initialize from config, Tuner will override at runtime
        _minLimit = ctx.getProperty("i2p.tunnel.participatingThrottle.minLimit", SystemVersion.isSlow() ? 40 : 80);
        _maxLimit = ctx.getProperty("i2p.tunnel.participatingThrottle.maxLimit", SystemVersion.isSlow() ? 150 : 300);
        _percentLimit = ctx.getProperty("i2p.tunnel.participatingThrottle.percentLimit", 10);
        // Enforce minimums to prevent integer division truncation to zero
        _minLimit = Math.max(20, _minLimit);
        _maxLimit = Math.max(50, _maxLimit);
        _percentLimit = Math.max(5, _percentLimit);
        // Probabilistic rejection params, Tuner will override at runtime
        _rejectThreshold = ctx.getProperty("i2p.tunnel.participatingThrottle.rejectThreshold", 70);
        _rejectSteepness = ctx.getProperty("i2p.tunnel.participatingThrottle.rejectSteepness", 200);
        _loadWeight = ctx.getProperty("i2p.tunnel.participatingThrottle.loadWeight", 100);
        _rejectThreshold = Math.max(30, Math.min(100, _rejectThreshold));
        _rejectSteepness = Math.max(100, Math.min(500, _rejectSteepness));
        _loadWeight = Math.max(0, Math.min(300, _loadWeight));
        ctx.statManager().createRequiredRateStat("tunnel.throttleParticipatingAccept", "Participating throttle accepts", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.throttleParticipatingReject", "Participating throttle rejects", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.throttleParticipatingDrop", "Participating throttle drops", "Tunnels [Participating]", RATES);
        new Cleaner().schedule(CLEAN_TIME);
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

        if (version.equals("0") || version.isEmpty()) {
            handleNoVersion(shouldDisconnect, h, isBanned, caps, bantime, ri, "none");
            return Result.DROP;
        }
        if (checkVersionAndCompressibility(version, isCompressible, shouldDisconnect, h, isBanned, caps, ri)) return Result.DROP;
        if (checkLowShareAndVersion(version, isLU, shouldBlockOldRouters, h, shouldDisconnect, isBanned, caps, bantime, ri)) return Result.DROP;
        if (checkUnreachableAndOld(version, isUnreachable, isFast, shouldBlockOldRouters, h, shouldDisconnect, isBanned, caps)) return Result.DROP;

        rv = evaluateThrottleConditions(count, limit, shouldThrottle, isFast, isLowShare, isUnreachable, h, caps, isBanned, bantime, ri);
        if (rv == Result.ACCEPT) {
            context.statManager().addRateData("tunnel.throttleParticipatingAccept", 1);
        } else if (rv == Result.REJECT) {
            context.statManager().addRateData("tunnel.throttleParticipatingReject", 1);
        } else {
            context.statManager().addRateData("tunnel.throttleParticipatingDrop", 1);
        }
        return rv;
    }

    /**
     * Calculates the participation limit for tunnels based on the number of tunnels
     * and router capabilities such as reachability and bandwidth share.
     * Relaxes limits when we have spare capacity (bandwidth headroom + available transit slots).
     * Enforces a floor (2 for low-share/unreachable, 5 for normal) to prevent zero limits
     * from integer division truncation at low percentage values.
     *
     * @param numTunnels the current count of participating tunnels
     * @param isUnreachable true if the router is unreachable
     * @param isLowShare true if the router has low bandwidth share
     * @param isFast true if the router has high bandwidth share
     * @return the per-peer tunnel participation limit, floored to prevent starvation
     */
    private int calculateLimit(int numTunnels, boolean isUnreachable, boolean isLowShare, boolean isFast) {
        int baseLimit;
        int minLimit = _minLimit;
        int maxLimit = _maxLimit;
        int pctLimit = _percentLimit;
        // Use floating-point to avoid integer division truncation (e.g. pctLimit=5 → 5/100=0)
        if (isUnreachable || isLowShare) {
            int pctBased = (int) ((double) numTunnels * pctLimit / 500.0);
            baseLimit = Math.min(minLimit, Math.max(maxLimit / 20, pctBased));
        } else if (IS_SLOW) {
            int pctBased = (int) ((double) numTunnels * pctLimit / 300.0);
            baseLimit = Math.min(minLimit, Math.max(maxLimit / 10, pctBased));
        } else {
            int pctBased = (int) ((double) numTunnels * pctLimit / 100.0);
            baseLimit = Math.min((minLimit * 3), Math.max(maxLimit / 2, pctBased));
        }

        // Capacity-based relaxation: if we have plenty of headroom, allow more tunnels per peer
        int maxTunnels = context.getProperty("router.maxParticipatingTunnels", 7500);
        double usagePct = (double) numTunnels / maxTunnels;
        int maxBps = context.bandwidthLimiter().getOutboundKBytesPerSecond() * 1024;
        double bwUsage = 0;
        if (maxBps > 0) {
            RateStat rs = context.statManager().getRate("tunnel.participating InBps");
            if (rs != null) {
                Rate rate = rs.getRate(60 * 1000L);
                if (rate != null && rate.getLastEventCount() > 0)
                    bwUsage = rate.getAverageValue() / maxBps;
            }
        }

        // Plenty of capacity: < 50% tunnel slots used AND < 60% bandwidth used
        if (usagePct < 0.5 && bwUsage < 0.6) {
            // Relax by up to 50% based on spare capacity
            double spareSlots = 1.0 - usagePct * 2; // 0-1, higher = more spare
            double spareBw = 1.0 - bwUsage * 1.67;  // 0-1, higher = more spare
            double relaxation = Math.min(spareSlots, spareBw);
            baseLimit = (int) (baseLimit * (1.0 + relaxation * 0.5));
        }

        // System load relaxation: further boost when CPU is idle
        int sysLoad = SystemVersion.getSystemLoad();
        if (sysLoad < 30) {
            // Up to 25% bonus when system is very idle (0% → 25%, 30% → 0%)
            double idleBonus = (30 - sysLoad) / 30.0 * 0.25;
            baseLimit = (int) (baseLimit * (1.0 + idleBonus));
        }

        // Floor: always allow at least some participation per peer
        return Math.max(baseLimit, isUnreachable || isLowShare ? 2 : 5);
    }

    /**
     * Handles banning and optionally disconnecting routers with no version info.
     *
     * @param shouldDisconnect whether to disconnect the router after banning
     * @param h the router hash
     * @param isBanned true if already banned
     * @param caps router capabilities string
     * @param bantime duration of the ban in milliseconds
     * @param ri RouterInfo for IP extraction and logging
     * @param version router version string, used if disconnect is scheduled
     */
    private void handleNoVersion(boolean shouldDisconnect, Hash h, boolean isBanned, String caps, int bantime, RouterInfo ri, String version) {
        if (shouldDisconnect) {context.simpleTimer2().addEvent(new Disconnector(h, version), 11*60*1000L);}
        if (!isBanned && "true".equals(context.getProperty("router.banlist.enableNoVersionBan", "true"))) {
            String ipPort = getRouterIPPort(ri);
            String banReason = "No version in RouterInfo";
            _banLogger.logBan(h, ipPort, banReason, bantime, ri);
            context.banlist().banlistRouter(h, "" + banReason, null, null, context.clock().now() + bantime);
            if (_log.shouldWarn()) {
                _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for " + (bantime / 60000) + "m -> No router version in RouterInfo");
            }
        }
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
    private boolean checkVersionAndCompressibility(String version, boolean isCompressible, boolean shouldDisconnect, Hash h, boolean isBanned, String caps, RouterInfo ri) {
        if (VersionComparator.comp(version, "0.9.57") < 0 && isCompressible) {
            if (shouldDisconnect) {context.simpleTimer2().addEvent(new Disconnector(h, version), 11*60*1000L);}
            if (!isBanned && _log.shouldWarn()) {
                _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for 24h -> Compressible RouterInfo / " + version);
            }
            String ipPort = getRouterIPPort(ri);
            String banReason = "Compressible RouterInfo & older than 0.9.57";
            _banLogger.logBan(h, ipPort, banReason, 24*60*60*1000L, ri);
            context.banlist().banlistRouter(h, "" + banReason, null, null, context.clock().now() + 24*60*60*1000);
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
                                            boolean shouldDisconnect, boolean isBanned, String caps, int bantime, RouterInfo ri) {
        if (VersionComparator.comp(version, MIN_VERSION) < 0 && isLU && shouldBlockOldRouters) {
            if (shouldDisconnect) {
                context.commSystem().forceDisconnect(h, "Old version " + version);
            }
            if (!isBanned && _log.shouldWarn()) {
                _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for " + (bantime / 60000) +
                          "m -> " + version + (caps.isEmpty() ? "" : " / " + caps));
            }
            if (context.banlist().isLuBanEnabled()) {
                String ipPort = getRouterIPPort(ri);
                String banReason = "Old and slow (" + version + ")";
                _banLogger.logBan(h, ipPort, banReason, bantime, ri);
                context.banlist().banlistRouter(h, "" + banReason, null, null, context.clock().now() + bantime);
            }
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
            if (shouldDisconnect) {context.simpleTimer2().addEvent(new Disconnector(h, version), 11*60*1000L);}
            if (_log.shouldWarn()) {
                _log.warn("Ignoring Tunnel Request from Router [" + h.toBase64().substring(0,6) + "] -> " + version + (caps.isEmpty() ? "" : " / " + caps));
            }
            return true;
        }
        return false;
    }

    /**
     * Evaluates whether to accept, reject, or drop tunnel requests based on
     * probabilistic rejection curve modulated by system load.
     * Replaces the old deterministic count > limit cutoff with a smooth
     * probability function that ramps from 0 at the reject threshold to
     * near-1 under load or high ratio.
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
                                              boolean isUnreachable, Hash h, String caps, boolean isBanned, int bantime, RouterInfo ri) {
        if (!shouldThrottle || limit <= 0)
            return Result.ACCEPT;

        float ratio = (float) count / limit;
        float load = calculateLoadScore();
        float threshold = _rejectThreshold / 100.0f;
        float steepness = _rejectSteepness / 100.0f;

        // Inflate effective ratio by load: at full load (1.0) with _loadWeight=100,
        // effective ratio = 2x actual. At zero load, no inflation.
        float effectiveRatio = ratio * (1.0f + load * _loadWeight / 100.0f);

        if (effectiveRatio >= threshold) {
            // When threshold < 1.0, range spans from threshold to 1.0 (full saturation).
            // When threshold >= 1.0 (tuned higher by the auto-tuner), use threshold
            // itself as the range so the curve spans from threshold to 2*threshold.
            float range = threshold < 1.0f ? (1.0f - threshold) : threshold;
            float normalized = Math.min(1.0f, (effectiveRatio - threshold) / range);
            // steepness=100 → linear, steepness=200 → quadratic (faster ramp at high ratios)
            float prob = (float) Math.pow(normalized, 100.0f / steepness);
            prob = Math.min(1.0f, Math.max(0.0f, prob));

            if (context.random().nextFloat() < prob) {
                if (ratio >= 2.0f || (isUnreachable && count > limit + 30) ||
                    (isLowShare && count > limit + 20)) {
                    handleExcessiveRequests(h, caps, count, limit, bantime, ri);
                    return Result.DROP;
                }
                _logHighRequestCount(h, caps, count, limit);
                return Result.REJECT;
            }
        }
        _logAcceptRequest(caps, count);
        return Result.ACCEPT;
    }

    /**
     * Computes a 0.0–1.0 load score from job queue lag, CPU load, system load,
     * and bandwidth queue pressure. Used to shift the rejection probability curve
     * left when the router is under load.
     */
    private float calculateLoadScore() {
        float score = 0.0f;
        // Job lag: 0ms → 0, 1000ms+ → 1.0 (40% weight)
        long lag = context.jobQueue().getMaxLag();
        score += Math.min(1.0f, lag / 1000.0f) * 0.4f;
        // CPU load: 0% → 0, 100% → 1.0 (25% weight)
        int cpuLoad = SystemVersion.getCPULoadAvg();
        if (cpuLoad > 0)
            score += Math.min(1.0f, cpuLoad / 100.0f) * 0.25f;
        // System load: 0 → 0, 100 → 1.0 (20% weight)
        int sysLoad = SystemVersion.getSystemLoad();
        score += Math.min(1.0f, sysLoad / 100.0f) * 0.2f;
        // Bandwidth queue: 0 → 0, 100KB+ → 1.0 (15% weight)
        RateStat bwRs = context.statManager().getRate("bwLimiter.participatingBandwidthQueue");
        if (bwRs != null) {
            net.i2p.stat.Rate rate = bwRs.getRate(60000);
            if (rate != null && rate.getLastEventCount() > 0) {
                score += Math.min(1.0f, (float)(rate.getAverageValue() / 100000.0)) * 0.15f;
            }
        }
        return Math.min(1.0f, score);
    }

    /**
     * Handles excessive tunnel request counts by banning and scheduling disconnect.
     *
     * @param h router hash
     * @param caps router capabilities string
     * @param count current participation count
     * @param limit participation limit
     * @param bantime ban duration in milliseconds
     * @param ri the router info for IP extraction and logging
     */
    private void handleExcessiveRequests(Hash h, String caps, int count, int limit, int bantime, RouterInfo ri) {
        if ("true".equals(context.getProperty("router.banlist.enableExcessiveTunnelRequestsBan", "true"))) {
            String ipPort = getRouterIPPort(ri);
            String banReason = "Excessive tunnel requests";
            _banLogger.logBan(h, ipPort, banReason, bantime, ri);
            context.banlist().banlistRouter(h, "" + banReason, null, null, context.clock().now() + bantime);
            context.simpleTimer2().addEvent(new Disconnector(h, banReason), 11 * 60 * 1000L);
            if (_log.shouldWarn()) {
                _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for " + (bantime / 60000) +
                          "m -> Excessive tunnel requests -> Count / Limit: " +
                          count + " / " + limit + " in 90s");
            }
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
            _log.debug("Accepting Tunnel Request from " + (!caps.isEmpty() ? caps : "") +
                       " Router -> Count: " + count + " in 90s");
        }
    }

    /**
     * Periodic timer event that clears the participation counts to reset throttling.
     * Reschedules itself each run so the counter is cleared every CLEAN_TIME; without
     * the reschedule the counter would clear only once then grow unbounded, leaking
     * memory and eventually banning peers on stale accumulated counts.
     */
    private class Cleaner extends SimpleTimer2.TimedEvent {
        public Cleaner() { super(context.simpleTimer2()); }
        @Override
        public void timeReached() {
            counter.clear();
            schedule(CLEAN_TIME);
        }
    }

    /**
     * Timer event that disconnects a router after a delay.
     */
    private class Disconnector extends SimpleTimer2.TimedEvent {
        private final Hash h;
        private final String version;
        public Disconnector(Hash h, String version) { super(context.simpleTimer2()); this.h = h; this.version = version; }
        public void timeReached() {
            String reason = (version == null || version.isEmpty()) ? "Old version" : "Old version (" + version + ")";
            context.commSystem().forceDisconnect(h, reason);
        }
    }
}
