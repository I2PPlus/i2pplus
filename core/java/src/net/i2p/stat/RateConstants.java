package net.i2p.stat;

/**
 * Centralized rate period constants for memory-optimized statistics collection.
 *
 * This class provides standardized period arrays for rate statistics to ensure
 * consistency across the codebase while minimizing memory footprint.
 *
 * @since 0.9.68+
 */
public final class RateConstants {

    /** Private constructor to prevent instantiation */
    private RateConstants() {}

    /**
     * Standard optimized rate periods: 1 minute, 1 hour, and 24 hours.
     * This provides sufficient granularity for real-time monitoring,
     * medium-term trends, and long-term peer reputation tracking.
     */
    public static final long[] STANDARD_RATES = {
        60*1000l,        // 1 minute - baseline for graphs
        60*60*1000l,     // 1 hour - for trend analysis
        24*60*60*1000l   // 24 hours - for peer reputation and capacity calculations
    };

    /**
     * Extended rate periods including 5 minutes for sidebar display.
     * Used only where user interface specifically requires 5-minute averages.
     */
    public static final long[] SIDEBAR_RATES = {
        60*1000l,        // 1 minute
        5*60*1000l,      // 5 minutes - for sidebar bandwidth display
        60*60*1000l,     // 1 hour
        24*60*60*1000l   // 24 hours - for peer reputation
    };

    /**
     * Tunnel test timing rate periods: 1 minute and 1 hour.
     * Used for tunnel test performance analysis where short-term vs
     * longer-term comparison is needed to detect performance degradation.
     */
    public static final long[] TUNNEL_TEST_RATES = {
        60*1000l,        // 1 minute
        60*60*1000l      // 1 hour
    };

    /**
     * Tunnel verification rate periods: 1 minute, 10 minutes, and 1 hour.
     * Used only for tunnel verification and build performance monitoring
     * where 10-minute trends are useful but 24-hour is excessive.
     */
    public static final long[] TUNNEL_VERIFY_RATES = {
        60*1000l,        // 1 minute
        10*60*1000l,     //10 minutes - for verification performance
        60*60*1000l      // 1 hour
    };

    /**
     * Tunnel-specific rate periods including 10 minutes for build statistics.
     * Used only where tunnel monitoring specifically requires 10-minute windows.
     */
    public static final long[] TUNNEL_RATES = {
        60*1000l,        // 1 minute
        10*60*1000l,     // 10 minutes - for tunnel build success rates
        60*60*1000l,     // 1 hour
        24*60*60*1000l   // 24 hours - for tunnel capacity and reputation
    };

    /**
     * Basic rate periods for simple statistics: 1 minute and 1 hour.
     * Used where only basic rate tracking is needed.
     */
    public static final long[] BASIC_RATES = {
        60*1000l,        // 1 minute
        60*60*1000l      // 1 hour
    };

    /**
     * Short-term rate periods for real-time metrics: 1 minute and 10 minutes.
     * Used for transport, queue, and real-time operational statistics
     * where long-term trends are not needed.
     */
    public static final long[] SHORT_TERM_RATES = {
        60*1000l,        // 1 minute
        10*60*1000l      // 10 minutes
    };

    /**
     * Bandwidth limiter rates - already optimized.
     * Maintains existing 3-period structure for bandwidth management.
     */
    public static final long[] BANDWIDTH_RATES = {
        60*1000l,        // 1 minute
        10*60*1000l,     // 10 minutes
        60*60*1000l      // 1 hour
    };

    // Convenience constants for individual periods
    public static final long ONE_MINUTE = 60*1000l;
    public static final long FIVE_MINUTES = 5*60*1000l;
    public static final long TEN_MINUTES = 10*60*1000l;
    public static final long THIRTY_MINUTES = 30*60*1000l;
    public static final long ONE_HOUR = 60*60*1000l;
    public static final long ONE_DAY = 24*60*60*1000l;
}