package net.i2p.router.tunnel.pool;

/**
 * Decides when a thin-pool warning is worth repeating.
 *
 * <p>Pure so the repetition policy is unit-testable without a router, and so the
 * policy is stated once rather than implied by wherever a log call happens to sit.
 *
 * <p>The executor calls into this path on every tick, several times a second,
 * while a pool's healthy count changes on the order of minutes. Warning per call
 * reprinted one unchanged value hundreds of times a minute, which both buries the
 * surrounding build failures and carries no information a single line did not
 * already carry. Logging on change makes the sequence itself the signal:
 * {@code 3/8 -> 2/8 -> 1/3 -> recovered}.
 *
 * @since 0.9.71+
 */
public class ThinPoolLogState {

    /** Sentinel for "no thin-pool warning emitted yet". */
    public static final int NEVER_WARNED = -1;

    private ThinPoolLogState() {}

    /**
     * Whether a thin-pool warning should be emitted for the observed healthy count.
     *
     * <p>Logs the first observation, then only when the count differs from the last
     * one logged. Returning true for a repeated value would reintroduce the flood;
     * suppressing a genuine return to a previously seen value would hide a
     * regression-then-recovery cycle, so any difference counts as news.
     *
     * @param lastWarned value passed to the previous call, or {@link #NEVER_WARNED}
     * if none has been logged yet
     * @param healthy current healthy tunnel count, which may be 0
     * @return true if this state has not been logged before
     * @since 0.9.71+
     */
    public static boolean shouldLog(int lastWarned, int healthy) {
        return lastWarned != healthy;
    }

    /**
     * Format the thin-pool warning body.
     *
     * <p>Pure, so the wording is unit-testable and cannot drift from the counts the
     * policy decides on.
     *
     * @param poolName pool name as produced by {@code TunnelPool.toString()}
     * @param healthy current healthy tunnel count
     * @param target desired healthy tunnel count
     * @return a single-line message suitable for WARN
     * @since 0.9.71+
     */
    public static String formatThinPool(String poolName, int healthy, int target) {
        return poolName + " -> Thin pool (" + healthy + "/" + target
               + " healthy) -> fast-path pre-build";
    }
}
