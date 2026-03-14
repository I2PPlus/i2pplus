package net.i2p.router.tunnel.pool;

import java.util.List;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;

public class TunnelBuildRequester {

    public static final int TUNNEL_LIFETIME_MS = 10 * 60 * 1000;
    public static final int STARTUP_TIME_MS = 5 * 60 * 1000;
    public static final int DEFAULT_SAFETY_MARGIN_MS = 60 * 1000;
    public static final int EXPLORATORY_SAFETY_MARGIN_MS = 120 * 1000;

    public static final int PANIC_FACTOR = 2;

    public static final int WINDOW_30S = 30_000;
    public static final int WINDOW_90S = 90_000;
    public static final int WINDOW_150S = 150_000;
    public static final int WINDOW_210S = 210_000;
    public static final int WINDOW_270S = 270_000;
    public static final int WINDOW_330S = 330_000;
    public static final int WINDOW_390S = 390_000;

    public static final int MULT_CRITICAL = 4;
    public static final int MULT_HIGH = 3;
    public static final int MULT_MED = 2;
    public static final int MULT_LOW = 1;

    public static final int STARTUP_THROTTLE_MS = 60_000;
    public static final int MAX_TUNNEL_BUFFER = 2;

    public static class BuildContext {
        private final TunnelPoolSettings settings;
        private final List<TunnelInfo> tunnels;
        private final List<PooledTunnelCreatorConfig> inProgress;
        private final int pairedValidCount;
        private final int pairedInProgressCount;
        private final long now;
        private final int avgBuildTimeMs;
        private final boolean isExploratory;

        public BuildContext(TunnelPoolSettings settings, List<TunnelInfo> tunnels,
                           List<PooledTunnelCreatorConfig> inProgress,
                           int pairedValidCount, int pairedInProgressCount,
                           long now, int avgBuildTimeMs, boolean isExploratory) {
            this.settings = settings;
            this.tunnels = tunnels;
            this.inProgress = inProgress;
            this.pairedValidCount = pairedValidCount;
            this.pairedInProgressCount = pairedInProgressCount;
            this.now = now;
            this.avgBuildTimeMs = avgBuildTimeMs;
            this.isExploratory = isExploratory;
        }

        public TunnelPoolSettings settings() { return settings; }
        public List<TunnelInfo> tunnels() { return tunnels; }
        public List<PooledTunnelCreatorConfig> inProgress() { return inProgress; }
        public int pairedValidCount() { return pairedValidCount; }
        public int pairedInProgressCount() { return pairedInProgressCount; }
        public long now() { return now; }
        public int avgBuildTimeMs() { return avgBuildTimeMs; }
        public boolean isExploratory() { return isExploratory; }
    }

    public static class ExpirationProfile {
        private final int expire30s;
        private final int expire90s;
        private final int expire150s;
        private final int expire210s;
        private final int expire270s;
        private final int expire330s;
        private final int expire390s;
        private final int expireLater;
        private final int fallbackCount;
        private final int usableCount;
        private final int[] urgentExpirations;

        public ExpirationProfile(int expire30s, int expire90s, int expire150s, int expire210s,
                                int expire270s, int expire330s, int expire390s, int expireLater,
                                int fallbackCount, int usableCount, int[] urgentExpirations) {
            this.expire30s = expire30s;
            this.expire90s = expire90s;
            this.expire150s = expire150s;
            this.expire210s = expire210s;
            this.expire270s = expire270s;
            this.expire330s = expire330s;
            this.expire390s = expire390s;
            this.expireLater = expireLater;
            this.fallbackCount = fallbackCount;
            this.usableCount = usableCount;
            this.urgentExpirations = urgentExpirations;
        }

        public int expire30s() { return expire30s; }
        public int expire90s() { return expire90s; }
        public int expire150s() { return expire150s; }
        public int expire210s() { return expire210s; }
        public int expire270s() { return expire270s; }
        public int expire330s() { return expire330s; }
        public int expire390s() { return expire390s; }
        public int expireLater() { return expireLater; }
        public int fallbackCount() { return fallbackCount; }
        public int usableCount() { return usableCount; }
        public int[] urgentExpirations() { return urgentExpirations; }
    }

    public static class BuildDecision {
        private final int tunnelsToBuild;
        private final int tunnelsToCancel;
        private final String algorithmUsed;
        private final int avgBuildTimeMs;
        private final int urgencyThresholdMs;

        public BuildDecision(int tunnelsToBuild, int tunnelsToCancel, String algorithmUsed, int avgBuildTimeMs, int urgencyThresholdMs) {
            this.tunnelsToBuild = tunnelsToBuild;
            this.tunnelsToCancel = tunnelsToCancel;
            this.algorithmUsed = algorithmUsed;
            this.avgBuildTimeMs = avgBuildTimeMs;
            this.urgencyThresholdMs = urgencyThresholdMs;
        }

        public int tunnelsToBuild() { return tunnelsToBuild; }
        public int tunnelsToCancel() { return tunnelsToCancel; }
        public String algorithmUsed() { return algorithmUsed; }
        public int avgBuildTimeMs() { return avgBuildTimeMs; }
        public int urgencyThresholdMs() { return urgencyThresholdMs; }
    }

    public static BuildDecision calculate(BuildContext ctx, int currentValidTunnelCount) {
        // Validate inputs
        if (ctx == null) {
            throw new IllegalArgumentException("BuildContext cannot be null");
        }
        if (ctx.settings() == null) {
            throw new IllegalArgumentException("Settings cannot be null");
        }
        if (ctx.tunnels() == null) {
            throw new IllegalArgumentException("Tunnels list cannot be null");
        }
        if (ctx.inProgress() == null) {
            throw new IllegalArgumentException("InProgress list cannot be null");
        }

        int wanted = ctx.settings().getTotalQuantity();

        if (ctx.pairedValidCount() + ctx.pairedInProgressCount() == 0) {
            return new BuildDecision(Math.max(2, wanted), 0, "paired_pool", 0, 0);
        }

        boolean allowZeroHop = ctx.settings().getAllowZeroHop();

        // Calculate urgency threshold first, needed for categorizeExpirations
        int urgencyThreshold;
        if (ctx.avgBuildTimeMs() > 0 && ctx.avgBuildTimeMs() < TUNNEL_LIFETIME_MS / 3) {
            urgencyThreshold = ctx.avgBuildTimeMs() + (ctx.isExploratory()
                ? EXPLORATORY_SAFETY_MARGIN_MS
                : DEFAULT_SAFETY_MARGIN_MS);
        } else {
            urgencyThreshold = 240_000; // Default 4 minutes
        }

        ExpirationProfile profile = categorizeExpirations(ctx.tunnels(), ctx.now(), allowZeroHop, urgencyThreshold);

        int inProgress = ctx.inProgress().size();
        int fallbackInProgress = countFallbackInProgress(ctx.inProgress());
        int remainingWanted = calculateRemainingWanted(
            wanted, profile.usableCount(), inProgress, fallbackInProgress,
            profile.fallbackCount(), allowZeroHop
        );

        if (remainingWanted <= 0) {
            return new BuildDecision(0, 0, "none_needed", ctx.avgBuildTimeMs(), 0);
        }

        int buildsNeeded;

        if (ctx.avgBuildTimeMs() > 0 && ctx.avgBuildTimeMs() < TUNNEL_LIFETIME_MS / 3) {
            buildsNeeded = calculateAdaptiveBuilds(remainingWanted, profile, urgencyThreshold);
        } else {
            buildsNeeded = calculateConservativeBuilds(remainingWanted, profile);
        }

        // Calculate total relevant tunnels (valid + in-progress)
        int totalRelevantTunnels = currentValidTunnelCount + inProgress;

        int result = applyCaps(buildsNeeded, wanted, currentValidTunnelCount, inProgress, allowZeroHop,
                               ctx.now() - STARTUP_TIME_MS);

        // Calculate how many in-progress to cancel if we're over budget
        int toCancel = calculateCancellations(wanted, currentValidTunnelCount, inProgress, result);

        return new BuildDecision(result, toCancel,
            (ctx.avgBuildTimeMs() > 0 && ctx.avgBuildTimeMs() < TUNNEL_LIFETIME_MS / 3)
                ? "adaptive" : "conservative",
            ctx.avgBuildTimeMs(),
            urgencyThreshold);
    }

    /**
     * Calculate how many in-progress tunnel builds to cancel.
     * @return number of builds to cancel
     */
    private static int calculateCancellations(int wanted, int currentValidTunnelCount, int inProgress, int newBuilds) {
        if (inProgress <= 0) {
            return 0;
        }

        int maxTunnels = Math.max(2, wanted + MAX_TUNNEL_BUFFER);
        int totalAfterBuilds = currentValidTunnelCount + inProgress + newBuilds;

        if (totalAfterBuilds <= maxTunnels) {
            return 0;  // Under budget, no need to cancel
        }

        // We're over budget - cancel excess in-progress
        int excess = totalAfterBuilds - maxTunnels;
        return Math.min(excess, inProgress);  // Can't cancel more than we have
    }

    /**
     * Categorize tunnel expirations into time buckets.
     *
     * @param tunnels Snapshot copy of tunnel list (caller must synchronize externally or pass a copy)
     * @param now current time in milliseconds
     * @param allowZeroHop whether zero-hop tunnels are allowed
     * @param urgencyThreshold threshold in ms for collecting urgent expirations (adaptive algorithm)
     * @return ExpirationProfile with bucketed counts and urgent expiration times
     */
    private static ExpirationProfile categorizeExpirations(List<TunnelInfo> tunnels, long now, boolean allowZeroHop, int urgencyThreshold) {
        int expire30s = 0, expire90s = 0, expire150s = 0, expire210s = 0;
        int expire270s = 0, expire330s = 0, expire390s = 0, expireLater = 0;
        int fallbackCount = 0;
        int usableCount = 0;

        // Collect urgent expiration times for adaptive algorithm
        int[] urgentTimes = new int[tunnels.size()];
        int urgentCount = 0;

        for (TunnelInfo info : tunnels) {
            if (isFailedTunnel(info)) continue;

            long timeToExpire = info.getExpiration() - now;
            if (timeToExpire <= 0) continue;

            if (allowZeroHop || info.getLength() > 1) {
                if (timeToExpire <= WINDOW_30S) expire30s++;
                else if (timeToExpire <= WINDOW_90S) expire90s++;
                else if (timeToExpire <= WINDOW_150S) expire150s++;
                else if (timeToExpire <= WINDOW_210S) expire210s++;
                else if (timeToExpire <= WINDOW_270S) expire270s++;
                else if (timeToExpire <= WINDOW_330S) expire330s++;
                else if (timeToExpire <= WINDOW_390S) expire390s++;
                else expireLater++;

                // Usable = above fixed threshold (3.5 minutes / 210s)
                if (timeToExpire > WINDOW_210S) usableCount++;

                // Collect urgent expirations for adaptive algorithm
                if (timeToExpire < urgencyThreshold && urgentCount < urgentTimes.length) {
                    urgentTimes[urgentCount++] = (int) timeToExpire;
                }
            } else {
                fallbackCount++;
            }
        }

        // Copy urgent times to properly-sized array
        int[] finalUrgentTimes = new int[urgentCount];
        System.arraycopy(urgentTimes, 0, finalUrgentTimes, 0, urgentCount);

        return new ExpirationProfile(expire30s, expire90s, expire150s, expire210s,
            expire270s, expire330s, expire390s, expireLater, fallbackCount, usableCount, finalUrgentTimes);
    }

    private static boolean isFailedTunnel(TunnelInfo info) {
        return info.getTunnelFailed() ||
               info.getTestStatus() == net.i2p.router.TunnelTestStatus.FAILED ||
               info.getConsecutiveFailures() > 1;
    }

    private static int countFallbackInProgress(List<PooledTunnelCreatorConfig> inProgress) {
        int count = 0;
        for (PooledTunnelCreatorConfig cfg : inProgress) {
            if (cfg.getLength() <= 1) count++;
        }
        return count;
    }

    private static int calculateRemainingWanted(int wanted, int usableCount, int inProgress,
                                                 int fallbackInProgress, int fallbackExisting, boolean allowZeroHop) {
        int remaining = Math.max(0, wanted - usableCount);

        // Calculate relevant in-progress (exclude fallbacks if not allowed)
        int relevantInProgress = allowZeroHop ? inProgress : (inProgress - fallbackInProgress);
        remaining -= relevantInProgress;

        // Subtract fallbacks when they don't count toward desired total
        if (!allowZeroHop) {
            remaining -= (fallbackExisting + fallbackInProgress);
        }

        return Math.max(0, remaining);
    }

    private static int calculateAdaptiveBuilds(int remainingWanted, ExpirationProfile profile, int threshold) {
        // Early return to skip sorting overhead for empty cases
        if (remainingWanted <= 0) {
            return 0;
        }

        int rv = 0;

        // Use granular expiration times for linear scaling (original algorithm behavior)
        int[] urgentTimes = profile.urgentExpirations();
        if (urgentTimes == null || urgentTimes.length == 0) {
            return rv;
        }

        // Defensive copy before sorting to avoid mutating immutable profile
        urgentTimes = urgentTimes.clone();

        // Sort to get the latest-expiring tunnels first (original algorithm behavior)
        // Simple insertion sort for small arrays
        for (int i = 1; i < urgentTimes.length; i++) {
            int key = urgentTimes[i];
            int j = i - 1;
            while (j >= 0 && urgentTimes[j] < key) {
                urgentTimes[j + 1] = urgentTimes[j];
                j--;
            }
            urgentTimes[j + 1] = key;
        }

        // If we need more than what's expiring soon, add the deficit with PANIC_FACTOR multiplier
        int urgentCount = urgentTimes.length;
        if (remainingWanted > urgentCount) {
            rv += PANIC_FACTOR * (remainingWanted - urgentCount);
            remainingWanted = urgentCount;
        }

        // Apply linear scaling: 1 to PANIC_FACTOR builds based on time-to-expire
        int halfThreshold = threshold / 2;
        for (int i = 0; i < remainingWanted && i < urgentTimes.length; i++) {
            int timeLeft = urgentTimes[i];
            if (timeLeft > halfThreshold) {
                rv += 1;
            } else {
                // Linear scale from 1 to PANIC_FACTOR as time approaches 0
                double ratio = ((double) (halfThreshold - timeLeft)) / halfThreshold;
                rv += 1 + (int) ((PANIC_FACTOR - 1) * ratio);
            }
        }

        return rv;
    }

    private static int calculateConservativeBuilds(int remainingWanted, ExpirationProfile profile) {
        // PROACTIVE: Unconditional pre-warm replacements (always 1:1)
        int rv = profile.expire390s() + profile.expire330s() + profile.expire270s();

        // REACTIVE: Handle tunnels already below usable threshold (< 3.5 min)
        int needed = remainingWanted;

        if (needed > 0) {
            int take = Math.min(needed, profile.expire210s());
            rv += take * MULT_LOW;
            needed -= take;

            if (needed > 0) {
                take = Math.min(needed, profile.expire150s());
                rv += take * MULT_MED;
                needed -= take;
            }

            if (needed > 0) {
                take = Math.min(needed, profile.expire90s());
                rv += take * MULT_HIGH;
                needed -= take;
            }

            if (needed > 0) {
                take = Math.min(needed, profile.expire30s());
                rv += take * MULT_CRITICAL;
                needed -= take;
            }

            if (needed > 0) {
                rv += needed * MULT_CRITICAL;
            }
        }

        return rv;
    }

    private static int applyCaps(int requested, int wanted, int currentValidTunnelCount, int inProgress,
                                 boolean allowZeroHop, long lifetimeMs) {
        int rv = requested;

        // Cap 1: Zero-hop mode shouldn't exceed wanted
        if (allowZeroHop && rv > wanted) {
            rv = wanted;
        }

        // Cap 2: Hard cap - never have more than wanted + buffer
        int maxTunnels = Math.max(2, wanted + MAX_TUNNEL_BUFFER);

        // Cap 3: Startup throttle - limit builds in first 60 seconds
        if (lifetimeMs < STARTUP_THROTTLE_MS) {
            int startupMax = Math.max(1, wanted / 2);
            if (rv > startupMax) {
                rv = startupMax;
            }
        }

        // Apply hard limit: total (existing + in-progress + requested) <= maxTunnels
        int total = currentValidTunnelCount + inProgress + rv;
        if (total > maxTunnels) {
            int excess = total - maxTunnels;
            rv = Math.max(0, rv - excess);
        }

        return rv;
    }
}
