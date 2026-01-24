package net.i2p.router.tunnel.pool;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
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
 * Throttles incoming tunnel requests earlier than ParticipatingThrottler,
 * with higher limits and more frequent cleanup.
 *
 * Checks various router characteristics and decides whether to throttle,
 * ban, or disconnect routers based on request count, version, bandwidth,
 * country, and system load.
 *
 * @since 0.9.5
 */
class RequestThrottler {
    private final RouterContext context;
    private final ObjectCounter<Hash> counter;
    private final Log _log;
    private volatile String lastBlockCountriesProp;
    private volatile Set<String> cachedBlockedCountries;
    private volatile long lastFirewallCheckTime;
    private volatile boolean cachedFirewalledStatus;
    private static final long FIREWALL_CHECK_INTERVAL = 10*60*1000; // Check every 10 minutes

    // Cached properties
    private volatile long lastPropertyCheckTime;
    private volatile Boolean cachedShouldThrottle;
    private volatile Boolean cachedShouldDisconnect;
    private volatile Boolean cachedShouldBlockOldRouters;
    private static final long PROPERTY_CHECK_INTERVAL = 30*1000; // Check every 30 seconds

    private static final int MIN_LIMIT = 200;
    private static final int MAX_LIMIT = 400;
    private static final int PERCENT_LIMIT = 20;
    private static final long CLEAN_TIME = 90 * 1000; // Reset limits every 90 seconds
    private final static boolean DEFAULT_SHOULD_THROTTLE = true;
    private final static String PROP_SHOULD_THROTTLE = "router.enableTransitThrottle";
    private final static boolean DEFAULT_SHOULD_DISCONNECT = false;
    private final static String PROP_SHOULD_DISCONNECT = "router.enableImmediateDisconnect";
    private final static boolean DEFAULT_BLOCK_OLD_ROUTERS = true;
    private final static String PROP_BLOCK_OLD_ROUTERS = "router.blockOldRouters";
    private final static String PROP_BLOCK_COUNTRIES = "router.blockCountries";
    private final static String DEFAULT_BLOCK_COUNTRIES = "";

    RequestThrottler(RouterContext ctx) {
        this.context = ctx;
        this.counter = new ObjectCounter<Hash>();
        _log = ctx.logManager().getLog(RequestThrottler.class);
        this.lastBlockCountriesProp = null;
        this.cachedBlockedCountries = Collections.emptySet();
        this.lastFirewallCheckTime = 0;
        this.cachedFirewalledStatus = false;
        this.lastPropertyCheckTime = 0;
        this.cachedShouldThrottle = null;
        this.cachedShouldDisconnect = null;
        this.cachedShouldBlockOldRouters = null;
        ctx.simpleTimer2().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /**
     * Checks if the given router's tunnel requests should be throttled.
     * Increments the request count and evaluates limits, router capabilities,
     * blocking policies, version, and system load to decide throttling.
     *
     * @param h the router's hash
     * @return true if the request should be throttled (denied), false otherwise
     */
    boolean shouldThrottle(Hash h) {
        String routerId = h.toBase64().substring(0, 6);
        RouterInfo ri = context.netDb().lookupRouterInfoLocally(h);

        // Extract router capabilities once for efficiency
        boolean isUnreachable = isUnreachable(ri);
        boolean isLowShare = isLowShare(ri);
        boolean isFast = isFast(ri);
        boolean isLTier = isLTier(ri);
        boolean weAreFirewalled = isFirewalled();
        updateCachedProperties();
        boolean shouldBlockOldRouters = weAreFirewalled ? false : cachedShouldBlockOldRouters;
        int numTunnels = this.context.tunnelManager().getParticipatingCount();

        /*
         * Calculate limit based on router capabilities for fair resource allocation
         * Uses percentage-based scaling with multipliers:
         *  - Slow/Unreachable: 4% (conservative)
         *  - Regular: 8% (balanced)
         *  - Fast: 15% (rewards capability)
         * All bounds are clamped by MIN_LIMIT (200) and MAX_LIMIT (400)
         */
        int limit;
        if (isUnreachable || isLowShare) {
            // 4% for unreachable/low-share routers - conservative limit to protect network
            limit = Math.min(MAX_LIMIT, Math.max(MIN_LIMIT, numTunnels * 4 / 100));
        } else if (isFast) {
            // 15% for high-bandwidth routers - rewards capable routers with higher limits
            limit = Math.min(MAX_LIMIT, Math.max(MIN_LIMIT, numTunnels * 15 / 100));
        } else {
            // 8% for regular routers - balanced approach for average capability
            limit = Math.min(MAX_LIMIT, Math.max(MIN_LIMIT, numTunnels * 8 / 100));
        }
        int count = counter.increment(h);
        boolean rv = count > limit;
        boolean enableThrottle = cachedShouldThrottle;
        boolean shouldDisconnect = cachedShouldDisconnect;
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
            isOld = VersionComparator.comp(v, "0.9.62") < 0;
        }

        // Early return: Blocked countries
        Set<String> blockedCountries = getBlockedCountries();
        if (blockedCountries.contains(country)) {
            if (_log.shouldWarn()) {
                _log.warn("Banning and disconnecting from [" + routerId + "] -> Blocked country: " + country);
            }
            context.banlist().banlistRouter(h, " <b>➜</b> Blocked country: " + country, null, null, context.clock().now() + 8*60*60*1000);
            context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
            return true;
        }

        // Early return: High system load
        if (highload) {
            if (_log.shouldWarn())
                _log.warn("Rejecting Tunnel Request from Router [" + routerId + "] -> " +
                          "CPU is under sustained high load");
            return rv;
        }

        // Early return: Old, low-tier, unreachable routers
        if (isLTier && isUnreachable && isOld) {
            if (_log.shouldInfo() && !context.banlist().isBanlisted(h)) {
                _log.info("Banning for 1h and disconnecting from [" + routerId + "] -> LU / " + v);
            }
            context.banlist().banlistRouter(h, " <b>➜</b> Old and slow (" + v + " / LU)", null, null, context.clock().now() + 60*60*1000);
            context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
            return true;
        }

        // Early return: Old, unreachable or low-share routers when blocking is enabled
        if (isOld && (isUnreachable || isLowShare) && shouldBlockOldRouters) {
            if (_log.shouldInfo()) {
                _log.info("Dropping all connections from [" + routerId + "] -> Unreachable / Slow / " + v);
            }
            context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
            return true;
        }

        // Handle excessive tunnel requests
        if (rv && enableThrottle) {
            int bantime = (isLowShare || isUnreachable) ? 60*60*1000 : 30*60*1000;
            int period = bantime / 60 / 1000;
            if (count == limit + 1) {
                context.banlist().banlistRouter(h, " <b>➜</b> Excessive tunnel requests", null, null, context.clock().now() + bantime);
                context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
                if (_log.shouldWarn()) {
                    _log.warn("Banning " + (isLowShare || isUnreachable ? "slow or unreachable" : "") +
                              " Router [" + routerId + "] for " + period + "m" +
                              "\n* Excessive tunnel requests (Requested: " + count + " / Hard limit " + limit +
                              " in 165s)");
                }
            } else {
                if (_log.shouldInfo())
                    _log.info("Rejecting Tunnel Requests from temp banned Router [" + routerId + "] -> " +
                              "(Requested: " + count + " / Hard limit: " + limit + " in 165s)");
                context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
            }
        }

        // Final check for extremely excessive requests
        if (rv && count >= 3 * limit && enableThrottle) {
            context.banlist().banlistRouter(h, "Excessive tunnel requests", null, null, context.clock().now() + 30*60*1000);
            // drop after any accepted tunnels have expired
            context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
            if (_log.shouldWarn())
                _log.warn("Banning Router [" + routerId + "] for 30m -> " +
                          "Excessive tunnel requests (Requested: " + count + " / Hard limit: " + limit + ")");
        }

        return rv;
    }

    /**
     * Returns the set of country codes configured to be blocked from participation.
     * Uses caching to avoid repeated string operations.
     *
     * @return set of country codes (in lower case) to block; empty if none configured
     * @since 0.9.65+
     */
    private Set<String> getBlockedCountries() {
        String blockCountries = context.getProperty(PROP_BLOCK_COUNTRIES, DEFAULT_BLOCK_COUNTRIES);

        // Check if cache is still valid
        if (blockCountries.equals(lastBlockCountriesProp)) {
            return cachedBlockedCountries;
        }

        // Update cache
        lastBlockCountriesProp = blockCountries;
        if (blockCountries.isEmpty()) {
            cachedBlockedCountries = Collections.emptySet();
        } else {
            cachedBlockedCountries = new HashSet<>(Arrays.asList(blockCountries.toLowerCase().split(",")));
        }

        return cachedBlockedCountries;
    }

    /**
     * Periodic timer event that clears the request counts to reset throttling.
     */
    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {RequestThrottler.this.counter.clear();}
    }

    /**
     * Timer event that forces disconnection from a router after a delay.
     *
     * @since 0.9.52
     */
    private class Disconnector implements SimpleTimer.TimedEvent {
        private final Hash h;
        public Disconnector(Hash h) {this.h = h;}
        public void timeReached() {context.commSystem().forceDisconnect(h);}
    }

    /**
     * Checks if router is unreachable based on capabilities.
     */
    private boolean isUnreachable(RouterInfo ri) {
        if (ri == null) return false;
        String caps = ri.getCapabilities();
        return caps.indexOf(Router.CAPABILITY_REACHABLE) < 0;
    }

    /**
     * Checks if router has low bandwidth sharing capabilities.
     */
    private boolean isLowShare(RouterInfo ri) {
        if (ri == null) return false;
        String caps = ri.getCapabilities();
        return caps.indexOf(Router.CAPABILITY_BW12) >= 0 ||
               caps.indexOf(Router.CAPABILITY_BW32) >= 0 ||
               caps.indexOf(Router.CAPABILITY_BW64) >= 0;
    }

    /**
     * Checks if router has high bandwidth capabilities.
     */
    private boolean isFast(RouterInfo ri) {
        if (ri == null) return false;
        String caps = ri.getCapabilities();
        return caps.indexOf(Router.CAPABILITY_BW256) >= 0 ||
               caps.indexOf(Router.CAPABILITY_BW512) >= 0 ||
               caps.indexOf(Router.CAPABILITY_BW_UNLIMITED) >= 0;
    }

    /**
     * Checks if router is low tier (lowest bandwidth tiers).
     */
    private boolean isLTier(RouterInfo ri) {
        if (ri == null) return false;
        String caps = ri.getCapabilities();
        return caps.indexOf(Router.CAPABILITY_BW12) >= 0 ||
               caps.indexOf(Router.CAPABILITY_BW32) >= 0;
    }

    /**
     * Updates cached property values to avoid repeated property lookups.
     */
    private void updateCachedProperties() {
        long now = context.clock().now();
        if (now - lastPropertyCheckTime > PROPERTY_CHECK_INTERVAL) {
            cachedShouldThrottle = context.getProperty(PROP_SHOULD_THROTTLE, DEFAULT_SHOULD_THROTTLE);
            cachedShouldDisconnect = context.getProperty(PROP_SHOULD_DISCONNECT, DEFAULT_SHOULD_DISCONNECT);
            cachedShouldBlockOldRouters = context.getProperty(PROP_BLOCK_OLD_ROUTERS, DEFAULT_BLOCK_OLD_ROUTERS);
            lastPropertyCheckTime = now;
        }
    }

    /**
     * Checks if we are firewalled, with caching to avoid repeated commSystem status checks.
     *
     * @return true if firewalled, false otherwise
     * @since 0.9.68+
     */
    private boolean isFirewalled() {
        long now = context.clock().now();
        if (now - lastFirewallCheckTime > FIREWALL_CHECK_INTERVAL) {
            net.i2p.router.CommSystemFacade.Status status = context.commSystem().getStatus();
            cachedFirewalledStatus = status == net.i2p.router.CommSystemFacade.Status.REJECT_UNSOLICITED ||
                                     status == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_OK ||
                                     status == net.i2p.router.CommSystemFacade.Status.IPV4_FIREWALLED_IPV6_UNKNOWN ||
                                     status == net.i2p.router.CommSystemFacade.Status.IPV4_OK_IPV6_FIREWALLED ||
                                     status == net.i2p.router.CommSystemFacade.Status.IPV4_UNKNOWN_IPV6_FIREWALLED ||
                                     status == net.i2p.router.CommSystemFacade.Status.IPV4_DISABLED_IPV6_FIREWALLED;
            lastFirewallCheckTime = now;
        }
        return cachedFirewalledStatus;
    }

}