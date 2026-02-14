package net.i2p.router.tunnel.pool;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.BanLogger;
import net.i2p.util.Log;
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
 * Now includes burst detection with sliding window to detect DDoS attacks
 * that attempt to overwhelm the router with rapid successive requests.
 *
 * @since 0.9.5
 */
class RequestThrottler {
    private final RouterContext context;
    private final BurstWindowCounter counter;
    private final Log _log;
    private static final BanLogger _banLogger = new BanLogger(null);
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

    // Burst offense tracking for escalation to 8h ban
    private final Map<Hash, BurstOffenseRecord> _burstOffenses = new ConcurrentHashMap<>();
    private static final long BURST_OFFENSE_RESET = 60 * 60 * 1000; // Reset after 1 hour clean

    // Traditional limits (per 90s window)
    private static final int MIN_LIMIT = 200;
    private static final int MAX_LIMIT = 400;
    private static final int PERCENT_LIMIT = 20;

    // Burst detection limits (per 10s sliding window)
    private static final long BURST_WINDOW_MS = 10 * 1000; // 10 second sliding window
    private static final int BURST_BUCKET_COUNT = 10; // 1 second per bucket
    private static final int BURST_THRESHOLD_MIN = 30; // Minimum burst threshold (30 in 10s)
    private static final int BURST_THRESHOLD_MAX = 150; // Maximum burst threshold
    private static final double BURST_THRESHOLD_PERCENT = 0.5; // 50% of normal limit

    // Additional burst thresholds for faster detection
    private static final int BURST_1S_THRESHOLD = 10; // 10 requests in 1 second = immediate ban

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
        this.counter = new BurstWindowCounter(BURST_WINDOW_MS, BURST_BUCKET_COUNT);
        _log = ctx.logManager().getLog(RequestThrottler.class);
        this.lastBlockCountriesProp = null;
        this.cachedBlockedCountries = Collections.emptySet();
        this.lastFirewallCheckTime = 0;
        this.cachedFirewalledStatus = false;
        this.lastPropertyCheckTime = 0;
        this.cachedShouldThrottle = null;
        this.cachedShouldDisconnect = null;
        this.cachedShouldBlockOldRouters = null;
        _banLogger.initialize(ctx);
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
            if (!context.banlist().isBanlisted(h)) {
                if (_log.shouldWarn()) {
                    _log.warn("Banning and disconnecting from [" + routerId + "] -> Blocked country: " + country);
                }
                context.banlist().banlistRouter(h, " <b>➜</b> Blocked country: " + country, null, null, context.clock().now() + 8*60*60*1000);
                _banLogger.logBan(h, context, "Blocked country: " + country, 8*60*60*1000);
            }
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
            if (!context.banlist().isBanlisted(h)) {
                if (_log.shouldInfo()) {
                    _log.info("Banning for 1h and disconnecting from [" + routerId + "] -> LU / " + v);
                }
                context.banlist().banlistRouter(h, " <b>➜</b> Old and slow (" + v + " / LU)", null, null, context.clock().now() + 60*60*1000);
                _banLogger.logBan(h, context, "Old and slow (" + v + " / LU)", 60*60*1000);
            }
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
            if (count == limit + 1 && !context.banlist().isBanlisted(h)) {
                context.banlist().banlistRouter(h, " <b>➜</b> Excessive tunnel requests", null, null, context.clock().now() + bantime);
                _banLogger.logBan(h, context, "Excessive tunnel requests", bantime);
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

        // Burst detection: Check for rapid successive requests in short time window
        // This catches DDoS attempts that try to overwhelm the router before the 90s limit kicks in
        if (enableThrottle && !rv) {
            // Check 1-second threshold first (severe burst = immediate ban)
            int currentBucketCount = counter.getCurrentBucketCount(h);
            if (currentBucketCount >= BURST_1S_THRESHOLD) {
                // 10+ requests in 1 second = immediate 4-hour ban
                if (_log.shouldWarn()) {
                    _log.warn("Severe transit request burst detected from Router [" + routerId + "] -> " +
                              "Requests: " + currentBucketCount + " in 1s (threshold: " + BURST_1S_THRESHOLD + ")");
                }
                context.banlist().banlistRouter(h, " <b>➜</b> Severe transit burst (1s)", null, null,
                    context.clock().now() + 4*60*60*1000);
                if (shouldDisconnect) {
                    context.simpleTimer2().addEvent(new Disconnector(h), 3 * 1000);
                }
                return true;
            }

            // Check 10-second threshold (escalating bans)
            if (counter.isBursting(h, limit)) {
                int burstCount = counter.getCount(h);
                int burstLimit = Math.max(BURST_THRESHOLD_MIN,
                    Math.min(BURST_THRESHOLD_MAX, (int) (limit * BURST_THRESHOLD_PERCENT * 10 / 90)));

                // Track burst offenses for escalation
                BurstOffenseRecord record = _burstOffenses.computeIfAbsent(h, k -> new BurstOffenseRecord());
                record.recordOffense();

                int offenses = record.getConsecutiveOffenses();

                // Escalate ban based on consecutive offenses
                long banTime;
                String reason;
                if (offenses >= 3) {
                    // 3+ consecutive bursts - 4 hour ban
                    banTime = 4 * 60 * 60 * 1000;
                    reason = "Burst tunnel requests (" + offenses + " offenses)";
                } else if (offenses == 2) {
                    // 2 consecutive bursts - 2 hour ban
                    banTime = 2 * 60 * 60 * 1000;
                    reason = "Burst tunnel requests (" + offenses + " offenses)";
                } else {
                    // First burst - 1 hour ban
                    banTime = 60 * 60 * 1000;
                    reason = "Burst tunnel requests";
                }

                if (_log.shouldWarn()) {
                    _log.warn("Burst detected from Router [" + routerId + "] -> " +
                              "Requests: " + burstCount + " in " + (BURST_WINDOW_MS / 1000) + "s " +
                              "(limit: " + burstLimit + ", offenses: " + offenses + ")");
                }

                context.banlist().banlistRouter(h, " <b>➜</b> " + reason, null, null,
                    context.clock().now() + banTime);

                if (shouldDisconnect) {
                    context.simpleTimer2().addEvent(new Disconnector(h), 3 * 1000);
                }

                return true;
            }
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
        public void timeReached() {
            RequestThrottler.this.counter.clear();
            // Reset expired burst offenses
            _burstOffenses.entrySet().removeIf(entry ->
                System.currentTimeMillis() - entry.getValue().lastOffenseTime > BURST_OFFENSE_RESET);
        }
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

    /**
     * Sliding window counter for burst detection.
     * Tracks requests in time buckets to detect sudden spikes in tunnel requests.
     * This helps identify DDoS attacks that attempt to overwhelm the router with
     * rapid successive requests from the same peer.
     *
     * @since 0.9.68+
     */
    private static class BurstWindowCounter {
        private final long windowSizeMs;
        private final int bucketCount;
        private final long bucketSizeMs;
        private final Map<Hash, AtomicIntegerArray> peerBuckets;
        private final AtomicIntegerArray globalBuckets;
        private volatile long lastCleanupTime;
        private static final long CLEANUP_INTERVAL = 60 * 1000; // Cleanup every 60s

        /**
         * Creates a new sliding window counter.
         *
         * @param windowSizeMs total window size in milliseconds (e.g., 10000 for 10s)
         * @param bucketCount number of buckets in the window (e.g., 10 for 1s buckets)
         */
        BurstWindowCounter(long windowSizeMs, int bucketCount) {
            this.windowSizeMs = windowSizeMs;
            this.bucketCount = bucketCount;
            this.bucketSizeMs = windowSizeMs / bucketCount;
            this.peerBuckets = new ConcurrentHashMap<>();
            this.globalBuckets = new AtomicIntegerArray(bucketCount);
            this.lastCleanupTime = System.currentTimeMillis();
        }

        /**
         * Increments the counter for a peer and returns the current count in the sliding window.
         *
         * @param h the peer hash
         * @return the total count in the current sliding window
         */
        int increment(Hash h) {
            long now = System.currentTimeMillis();
            int bucketIndex = (int) ((now / bucketSizeMs) % bucketCount);

            // Get or create peer bucket array
            AtomicIntegerArray peerArray = peerBuckets.computeIfAbsent(h, k -> new AtomicIntegerArray(bucketCount));

            // Increment both peer and global counters
            int peerCount = peerArray.incrementAndGet(bucketIndex);
            globalBuckets.incrementAndGet(bucketIndex);

            // Calculate total in sliding window for this peer
            int total = getWindowSum(peerArray, bucketIndex);

            // Periodic cleanup of old entries
            if (now - lastCleanupTime > CLEANUP_INTERVAL) {
                cleanup(now);
            }

            return total;
        }

        /**
         * Gets the current count for a peer in the sliding window without incrementing.
         *
         * @param h the peer hash
         * @return the total count in the current sliding window
         */
        int getCount(Hash h) {
            AtomicIntegerArray peerArray = peerBuckets.get(h);
            if (peerArray == null) return 0;

            long now = System.currentTimeMillis();
            int bucketIndex = (int) ((now / bucketSizeMs) % bucketCount);
            return getWindowSum(peerArray, bucketIndex);
        }

        /**
         * Gets the global request count across all peers in the sliding window.
         *
         * @return the total global count
         */
        int getGlobalCount() {
            long now = System.currentTimeMillis();
            int bucketIndex = (int) ((now / bucketSizeMs) % bucketCount);
            return getWindowSum(globalBuckets, bucketIndex);
        }

        /**
         * Calculates the sum of all buckets except the current one (sliding window).
         * The current bucket is excluded as it's still being filled.
         *
         * @param array the bucket array
         * @param currentBucket the current bucket index
         * @return the sum of all other buckets
         */
        private int getWindowSum(AtomicIntegerArray array, int currentBucket) {
            int sum = 0;
            for (int i = 0; i < bucketCount; i++) {
                if (i != currentBucket) {
                    sum += array.get(i);
                }
            }
            return sum;
        }

        /**
         * Clears all counters (called periodically by Cleaner).
         */
        void clear() {
            peerBuckets.clear();
            for (int i = 0; i < bucketCount; i++) {
                globalBuckets.set(i, 0);
            }
        }

        /**
         * Removes stale peer entries that haven't been seen recently.
         *
         * @param now current time
         */
        private void cleanup(long now) {
            lastCleanupTime = now;
            int currentBucket = (int) ((now / bucketSizeMs) % bucketCount);
            int prevBucket = (currentBucket - 1 + bucketCount) % bucketCount;

            // Remove peers with zero count in last two buckets
            peerBuckets.entrySet().removeIf(entry -> {
                AtomicIntegerArray arr = entry.getValue();
                return arr.get(currentBucket) == 0 && arr.get(prevBucket) == 0;
            });
        }

        /**
         * Gets the count in the current 1-second bucket only.
         * Used for detecting severe bursts (e.g., 10+ requests in 1s).
         *
         * @param h the peer hash
         * @return the count in the current bucket
         */
        int getCurrentBucketCount(Hash h) {
            AtomicIntegerArray peerArray = peerBuckets.get(h);
            if (peerArray == null) return 0;
            long now = System.currentTimeMillis();
            int bucketIndex = (int) ((now / bucketSizeMs) % bucketCount);
            return peerArray.get(bucketIndex);
        }

        /**
         * Checks if a peer is exceeding burst thresholds.
         *
         * @param h the peer hash
         * @param normalLimit the normal 90s limit for this peer
         * @return true if burst threshold exceeded
         */
        boolean isBursting(Hash h, int normalLimit) {
            int count = getCount(h);
            // Burst threshold is 50% of the normal limit scaled to 10s window
            // Normal limit is per 90s, so for 10s: limit * 10/90 * 0.5
            int burstLimit = Math.max(BURST_THRESHOLD_MIN,
                Math.min(BURST_THRESHOLD_MAX, (int) (normalLimit * BURST_THRESHOLD_PERCENT * 10 / 90)));
            return count > burstLimit;
        }
    }

    /**
     * Tracks burst offense history for a peer.
     * Used to escalate bans for persistent burst violators.
     */
    private static class BurstOffenseRecord {
        private final AtomicInteger consecutiveOffenses = new AtomicInteger(0);
        private volatile long lastOffenseTime = 0;

        void recordOffense() {
            consecutiveOffenses.incrementAndGet();
            lastOffenseTime = System.currentTimeMillis();
        }

        int getConsecutiveOffenses() {
            return consecutiveOffenses.get();
        }

        void resetIfExpired(long resetTime) {
            if (System.currentTimeMillis() - lastOffenseTime > resetTime) {
                consecutiveOffenses.set(0);
            }
        }
    }

}