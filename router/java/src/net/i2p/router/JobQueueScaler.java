package net.i2p.router;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.DataHelper;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Dynamic job runner scaling controller for the JobQueue.
 * <p>
 * Monitors queue metrics (ready job count, lag) and automatically scales
 * the number of JobQueueRunner threads up or down based on load conditions.
 * <p>
 * <strong>Scaling Algorithm:</strong>
 * <ul>
 * <li>Scale UP when: readyJobs &gt; activeRunners * scaleUpJobsRatio OR maxLag
 * &gt; scaleUpLagThreshold</li>
 * <li>Scale DOWN when: readyJobs &lt; activeRunners AND avgLag &lt; scaleDownLagThreshold (sustained)</li>
 * <li>A scale-down must also wait out a dwell after a scale-up, and may never
 * remove more than that scale-up added, so one quiet interval cannot undo it</li>
 * <li>Bounds: min runners = max(4, cores), max runners = min(2 * {@code router.maxJobRunners}, RAM-based limit)</li>
 * </ul>
 * <p>
 * <strong>Resource Saturation Gates:</strong>
 * Scale-up is suppressed (not just limited) when the bottleneck is resource
 * saturation rather than thread count. Emergency signals (critical lag,
 * wedged jobs, dropped jobs) override the suppression.
 * <ul>
 * <li>CPU saturation: {@code router.cpuLoad} &gt; 85%</li>
 * <li>GC storm: {@code router.gcPauseTime} &gt; 50 ms/min</li>
 * <li>Network congestion: {@code tunnel.buildSuccessRate} &lt; 50%</li>
 * <li>Bandwidth saturation: bwLimiter.outboundDelayedTime &gt; 500ms</li>
 * </ul>
 * <p>
 * <strong>Feedback Mechanism:</strong>
 * <ul>
 * <li>Captures pre-scale metrics before adding runners</li>
 * <li>Compares them after 2 check intervals, on 1-minute averages for ready
 * jobs and worst lag, and the send delay against the send delay captured
 * before the scale</li>
 * <li>If lag, maxLag, message delay, or ready jobs INCREASED after scaling,
 * rolls back — but only after 2 checks in a row look degraded</li>
 * <li>After 5 failed scale attempts, circuit breaker opens — stops scaling up</li>
 * <li>Tracks memory usage to prevent OOM conditions</li>
 * </ul>
 *
 * @since 0.9.68+
 */
class JobQueueScaler implements Runnable {
    /** Logger for scaling decisions. */
    private final Log _log;
    /** The queue whose runners are scaled. */
    private final JobQueue _jobQueue;
    /** The router context. */
    private final RouterContext _context;
    /** True while the scaler job is scheduled. */
    private volatile boolean _isRunning;
    /** False once the scaler has shut down. */
    private volatile boolean _isAlive;

    /** Checks in a row that wanted to scale up. */
    private int _consecutiveScaleUpChecks;
    /** Checks in a row that wanted to scale down. */
    private int _consecutiveScaleDownChecks;
    /** When the last scale event happened. */
    private long _lastScaleTime;
    /** True when the last scale event added runners. */
    private boolean _lastScaleWasUp;
    /**
     * Runners added by the last scale-up. The ceiling for the next scale-down,
     * so a scale-down cannot remove more than the scale-up put in and drive the
     * count below the level the scale-up judged necessary.
     */
    private int _lastScaleUpCount;
    /** Post-scale checks in a row that judged the last scale-up a failure. */
    private int _degradedFeedbackChecks;
    /** Runner limit from the config. */
    private int _configuredMaxRunners;
    /** Runner limit in effect right now. */
    private int _currentMaxRunners;

    /** State captured before the last scale, for feedback. */
    private PreScaleSnapshot _preScaleSnapshot;
    /** Checks run since the last scale event. */
    private int _checksSinceLastScale;
    /** Total checks run, never reset by scale events. */
    private int _periodicCheckCount; // monotonic counter for periodic tasks (not reset by scale events)
    /** Scale-ups in a row that did not help. */
    private int _consecutiveFailedScaleUps;
    /** True while the failure cooldown runs. */
    private boolean _isInExtendedCooldown;
    /** Circuit breaker set after repeated failures. */
    private boolean _scalingUpDisabled; // Circuit breaker after repeated failures

    /**
     * Config string the cached ratio was parsed from, kept to avoid
     * re-parsing on the hot path.
     */
    private String _cachedScaleJobsRatioProp;
    /** Backlog ratio needed to scale up. */
    private double _cachedScaleJobsRatio = DEFAULT_SCALE_UP_JOBS_RATIO;

    /** Ready jobs seen at the previous check. */
    private int _previousReadyJobs;
    /** Message delay seen at the previous check. */
    private long _previousMessageDelay;
    /** Recent message delay samples for trend tracking. */
    private long[] _messageDelayHistory;
    /** Write index into the delay history. */
    private int _msgDelayHistoryIndex;
    /** Samples held in the delay history. */
    private int _msgDelayHistoryCount;

    /** Runner floor requested by subsystems. */
    private final AtomicInteger _externalFloor = new AtomicInteger(0);
    /**
     * Runner requests by subsystem name. Each entry is a lease with an expiry,
     * so a request that is never released expires on its own and the floor is
     * recomputed from live leases only.
     */
    private final ConcurrentHashMap<String, Lease> _externalRequests = new ConcurrentHashMap<>(4);

    /** When the current overload episode began. */
    private long _loadEpisodeStart;
    /** When the queue last recovered from an overload. */
    private long _lastRecoveryTime;

    /** Checks after a scale before judging its result. */
    private static final int FEEDBACK_CHECKS_AFTER_SCALE = 2; // Check 2 times after scaling (faster response)
    /** Failed scale-ups before the circuit breaker opens. */
    private static final int MAX_CONSECUTIVE_FAILED_SCALES = 5; // Reduced from 10 - circuit breaker opens sooner
    /** Cooldown multiplier after a failed scale. */
    private static final long EXTENDED_COOLDOWN_MULTIPLIER = 2; // 2x normal cooldown (reduced from 3x)
    /**
     * Heap use percent at which a scale-up is judged to have failed. Adding
     * runners into a nearly-full heap trades one pressure for another.
     */
    private static final int MEMORY_CRITICAL_PERCENT = 85;
    /** Lag growth tolerated after a scale before rollback. */
    private static final double LAG_INCREASE_THRESHOLD = 1.5; // Allow 50% lag increase before rollback (was 2.0 = 100%)
    /** Backlog growth tolerated after a scale before rollback. */
    private static final double READY_JOBS_INCREASE_THRESHOLD = 1.5; // Allow 50% increase before rollback (was 2.0 = 100%)

    /** Delay samples kept, a 12 second window at 2s checks. */
    private static final int MSG_DELAY_HISTORY_SIZE = 6; // samples, 12s window at 2s interval
    /** Delay over the baseline median that triggers action. */
    private static final double BASELINE_MULTIPLIER = 2.0; // trigger when current > 2x baseline median
    /** Jobs added per check that count as backlog growth. */
    private static final int QUEUE_GROWTH_RATE_THRESHOLD = 2; // jobs/check interval

    /** One megabyte, for the memory limits. */
    private static final long MB = 1024 * 1024L;
    /** A runner thread is estimated at 2 MB of heap. */
    private static final long RUNNER_MEMORY_ESTIMATE = 2 * MB; // ~2MB per thread (stack + overhead)
    /** Share of the heap allowed for runner threads. */
    private static final double MAX_MEMORY_PERCENTAGE = 0.10; // Use max 10% of heap for runners

    /** The default interval between checks is 2 seconds. */
    private static final long DEFAULT_SCALE_CHECK_INTERVAL = 2000; // 2 seconds (less frequent checks reduce overhead)
    /** The default wait between scale events is 10 seconds. */
    private static final long DEFAULT_SCALE_COOLDOWN = 10000; // 10 seconds (cooldown between scale events)
    /** The default lag that justifies scaling up is 10 ms. */
    private static final int DEFAULT_SCALE_UP_LAG_THRESHOLD = 10; // 10ms (scale up only when lag is significant)
    /** The default message delay that justifies scaling up is 200 ms. */
    private static final int DEFAULT_SCALE_UP_MESSAGE_DELAY_THRESHOLD = 200; // 200ms (scale up if message delay exceeds 200ms)
    /** The default lag low enough to scale down is 5 ms. */
    private static final int DEFAULT_SCALE_DOWN_LAG_THRESHOLD = 5; // 5ms (scale down when lag is moderate)
    /** Default backlog ratio needed to scale up. */
    private static final double DEFAULT_SCALE_UP_JOBS_RATIO = 2.0; // 2x (require real backlog, not just 1 extra job)
    /** Runners added per scale-up. */
    private static final int DEFAULT_SCALE_UP_STEP = 1;
    /**
     * Minimum runners removed per scale-down. A floor, not a target: the
     * removal is bounded by half the excess above the minimum and by what the
     * last scale-up added, so a scale-down no longer shrinks faster than we
     * grow.
     */
    private static final int DEFAULT_SCALE_DOWN_STEP = 2;
    /** Checks that must want a scale-up before acting. */
    private static final int SUSTAINED_CHECKS_REQUIRED = 3;
    /**
     * Checks that must want a scale-down before acting. Equal to the scale-up
     * requirement: an asymmetric pair (3 up, 2 down) lets one quiet interval
     * undo a scale-up that took three checks to justify, and that sawtooth is
     * where the runner-thread churn came from.
     */
    private static final int SUSTAINED_CHECKS_REQUIRED_DOWN = 3;
    /**
     * Cooldowns that must pass after a scale-up before a scale-down may run.
     * One cooldown is not enough for the lag signal to respond to the runners
     * that were just added.
     */
    private static final long DWELL_AFTER_SCALE_UP_COOLDOWNS = 2;
    /**
     * Degraded post-scale checks in a row needed to roll back a scale-up. A
     * single sample pair is a coin flip on instantaneous queue depth, and a
     * rollback destroys threads the scale-up had just created.
     */
    private static final int FEEDBACK_DEGRADED_CHECKS_REQUIRED = 2;

    /** Config key turning dynamic scaling on or off. */
    private static final String PROP_DYNAMIC_SCALING = "router.dynamicJobScaling";
    /** Config key for the lag that justifies scaling up. */
    private static final String PROP_SCALE_UP_LAG = "router.scaleUpLagThreshold";
    /** Config key for the lag low enough to scale down. */
    private static final String PROP_SCALE_DOWN_LAG = "router.scaleDownLagThreshold";
    /** Config key for the backlog ratio needed to scale up. */
    private static final String PROP_SCALE_JOBS_RATIO = "router.scaleUpJobsRatio";
    /** Config key for the interval between checks. */
    private static final String PROP_SCALE_CHECK_INTERVAL = "router.scaleCheckInterval";
    /** Config key for the wait between scale events. */
    private static final String PROP_SCALE_COOLDOWN = "router.scaleCooldown";
    /** Config key for the message delay that justifies scaling up. */
    private static final String PROP_SCALE_UP_MSG_DELAY = "router.scaleUpMessageDelayThreshold";
    /** Config key for the minimum runner count. */
    private static final String PROP_MIN_RUNNERS = "router.minJobRunners";
    /** Config key turning the post-scale feedback check on or off. */
    private static final String PROP_FEEDBACK_ENABLED = "router.scaleFeedbackEnabled";

    /** CPU percent above which scale-up is skipped. */
    private static final int CPU_SATURATION_THRESHOLD = 85; // % — skip scale-up if CPU saturated
    /** GC pause ms per minute above which scale-up is skipped. */
    private static final long GC_STORM_THRESHOLD = 50; // ms/min — skip scale-up if GC pause time is high

    /**
     * The queue state captured before a scale-up, for the feedback check that
     * decides whether the scale helped. Only the values the verdict reads are
     * captured. Package-visible so the degradation decision can be tested on a
     * snapshot built directly, without driving a scale event through a live
     * queue.
     */
    static final class PreScaleSnapshot {
        /** Average job lag at the capture, in ms. */
        final long avgLag;
        /** Runners added by the scale event. */
        final int runnersAdded;
        /** Transport send delay at the capture, in ms. */
        final long messageDelay;
        /** 1-minute average ready jobs at the capture. */
        final long smoothReadyJobs;
        /** 1-minute average worst job lag at the capture, in ms. */
        final long smoothMaxLag;

        /**
         * Captures the queue values a scale-up verdict is judged against.
         *
         * @param avgLag the average job lag at the capture, in milliseconds
         * @param runnersAdded the runners the scale event adds
         * @param messageDelay the transport send delay at the capture, in milliseconds
         * @param smoothReadyJobs the 1-minute average ready jobs at the capture
         * @param smoothMaxLag the 1-minute average worst job lag, in milliseconds
         */
        PreScaleSnapshot(long avgLag, int runnersAdded, long messageDelay,
                        long smoothReadyJobs, long smoothMaxLag) {
            this.avgLag = avgLag;
            this.runnersAdded = runnersAdded;
            this.messageDelay = messageDelay;
            this.smoothReadyJobs = smoothReadyJobs;
            this.smoothMaxLag = smoothMaxLag;
        }
    }

    /**
     * A subsystem's standing claim on job runners: how many it is holding, and
     * the adjusted instant the claim stops counting. A lease expires on its own,
     * so a subsystem that stops calling leaves the floor by decaying, not by
     * being released, and the floor is always the sum of live leases.
     * Package-visible so the expiry rule can be tested directly.
     *
     * @since 0.9.72+
     */
    static final class Lease {
        /** Runners held under this lease. */
        final int _count;
        /** Adjusted ms instant the lease stops counting; 0 means it never expires. */
        final long _expiresOn;

        /**
         * Holds a subsystem's claim on job runners until the lease expires.
         *
         * @param count runners held under this lease
         * @param expiresOn adjusted ms instant the lease stops counting, 0 for never
         */
        Lease(int count, long expiresOn) {
            _count = count;
            _expiresOn = expiresOn;
        }

        /**
         * Is the lease still in force?
         *
         * @param now the current adjusted time in ms
         * @return true while the lease counts toward the floor
         */
        boolean isLive(long now) {
            return _count > 0 && (_expiresOn <= 0 || _expiresOn > now);
        }
    }

    /**
     * Create a new JobQueueScaler.
     *
     * @param context the router context
     * @param jobQueue the job queue to scale
     */
    public JobQueueScaler(RouterContext context, JobQueue jobQueue) {
        _context = context;
        _jobQueue = jobQueue;
        _log = context.logManager().getLog(JobQueueScaler.class);
        _isRunning = false;
        _isAlive = false;
        _consecutiveScaleUpChecks = 0;
        _consecutiveScaleDownChecks = 0;
        _lastScaleTime = 0;
        _lastScaleWasUp = false;
        _lastScaleUpCount = 0;
        _degradedFeedbackChecks = 0;
        _preScaleSnapshot = null;
        _checksSinceLastScale = 0;
        _consecutiveFailedScaleUps = 0;
        _isInExtendedCooldown = false;
        _scalingUpDisabled = false;

        // Trend tracking for predictive scaling
        _previousReadyJobs = -1;
        _previousMessageDelay = -1;
        _messageDelayHistory = new long[MSG_DELAY_HISTORY_SIZE];
        _msgDelayHistoryIndex = 0;
        _msgDelayHistoryCount = 0;

        // Calculate max runners
        _configuredMaxRunners = context.getProperty(JobQueue.PROP_MAX_RUNNERS, JobQueue.runners);
        _currentMaxRunners = calculateMaxRunnersBasedOnRAM(_configuredMaxRunners);

        // Register rate stats
        context.statManager().createRateStat("jobQueue.runnerScaleUp",
            "Number of runners added in scale-up events", "JobQueue",
            new long[] { RateConstants.ONE_MINUTE });
        context.statManager().createRateStat("jobQueue.runnerScaleDown",
            "Number of runners removed in scale-down events", "JobQueue",
            new long[] { RateConstants.ONE_MINUTE });
        context.statManager().createRateStat("jobQueue.runnerCount",
            "Current number of active job runners", "JobQueue",
            new long[] { RateConstants.ONE_MINUTE });
        context.statManager().createRateStat("jobQueue.scaleRollback",
            "Number of rollback events (scale up made things worse)", "JobQueue",
            new long[] { RateConstants.ONE_MINUTE });
        // runnerScaleUp/Down count runners; these count events, so "how often is
        // the scaler churning threads" is answerable without reading the log.
        context.statManager().createRateStat("jobQueue.scaleUpEvents",
            "Scale-up events", "JobQueue",
            new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        context.statManager().createRateStat("jobQueue.scaleDownEvents",
            "Scale-down events", "JobQueue",
            new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        // The feedback check compared an instantaneous max lag against a snapshot
        // max lag, so one unlucky sample rolled the scale back. Recorded here so
        // the comparison can use a 1-minute average on both sides.
        context.statManager().createRateStat("jobQueue.maxLag",
            "Longest wait of a ready job (ms)", "JobQueue",
            new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        context.statManager().createRequiredRateStat("jobQueue.memoryUsedPercent",
            "Percentage of max memory used", "JobQueue",
            new long[] { RateConstants.ONE_MINUTE });
        context.statManager().createRequiredRateStat("jobQueue.loadRecoveryTime",
            "Duration of load episodes (ms)", "JobQueue",
            new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
    }

    /**
     * Calculate max runners based on available RAM.
     * Prevents OOM by limiting runners to use max 10% of heap.
     *
     * @param configuredMax the configured maximum from properties
     * @return the lower of configuredMax*2 or RAM-based limit
     */
    private int calculateMaxRunnersBasedOnRAM(int configuredMax) {
        long maxMemory = SystemVersion.getMaxMemory();
        long maxRunnerMemory = (long) (maxMemory * MAX_MEMORY_PERCENTAGE);
        int ramBasedMax = (int) (maxRunnerMemory / RUNNER_MEMORY_ESTIMATE);

        // Also consider current memory pressure
        long usedMemory = getUsedMemory();
        long freeMemory = maxMemory - usedMemory;
        int freeMemoryBasedMax = (int) ((freeMemory * 0.5) / RUNNER_MEMORY_ESTIMATE); // Only use 50% of free mem

        // CPU-based cap: more than 2× cores causes context-switching death spirals
        int cores = SystemVersion.getCores();
        int cpuBasedMax = Math.max(cores * 2, 8);

        int effectiveMax = Math.min(ramBasedMax, freeMemoryBasedMax);
        int targetMax = configuredMax * 2;
        int finalMax = Math.min(targetMax, Math.min(effectiveMax, cpuBasedMax));

        // Ensure at least minimum
        finalMax = Math.max(getMinRunnersDynamic(), finalMax);

        if (_log.shouldInfo()) {
            _log.info("Max runners calculation: configured: " + configuredMax +
                     " * target: " + targetMax + " * ramBased: " + ramBasedMax +
                     "\n* freeMemBased: " + freeMemoryBasedMax + " * final: " + finalMax +
                     " * maxMemory: " + (maxMemory/MB) + "MB * used: " + (usedMemory/MB) + "MB");
        }

        return finalMax;
    }

    /**
     * Recalculate max runners based on current memory conditions.
     * Called periodically to adapt to changing memory pressure.
     */
    private void recalculateMaxRunners() {
        int newMax = calculateMaxRunnersBasedOnRAM(_configuredMaxRunners);
        if (newMax != _currentMaxRunners) {
            if (_log.shouldInfo()) {
                _log.info("Adjusting max runners from " + _currentMaxRunners + " to " + newMax +
                         " based on current memory conditions");
            }
            _currentMaxRunners = newMax;
        }
    }

    /**
     * Current used memory in bytes.
     * @return the used memory
     */
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * Memory usage percentage.
     * @return the memory usage percent
     */
    private double getMemoryUsagePercent() {
        long maxMemory = SystemVersion.getMaxMemory();
        if (maxMemory == 0) return 0;
        return (double) getUsedMemory() / maxMemory * 100;
    }

    /**
     * Compute the median of the message delay history ring buffer.
     *
     * @return the median of the recorded message delays in ms, or 0 when no
     *         samples have been recorded yet
     */
    private long computeMedianBaseline() {
        if (_msgDelayHistoryCount == 0) return 0;
        long[] sorted = Arrays.copyOf(_messageDelayHistory, _msgDelayHistoryCount);
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    /**
     * Read the 60s rolling average of tunnel build success rate (0-100 scale).
     * NaN if no data available.
     *
     * @return the average tunnel build success percentage for the last minute, or
     *         NaN when the rate or its 1-minute rate has no data
     */
    private double readBuildSuccessRate() {
        net.i2p.stat.RateStat rs = _context.statManager().getRate("tunnel.buildSuccessRate");
        if (rs == null) return Double.NaN;
        net.i2p.stat.Rate rate = rs.getRate(RateConstants.ONE_MINUTE);
        if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
        return rate.getAverageValue();
    }

    /**
     * Start the scaler thread.
     */
    public void startup() {
        if (!isDynamicScalingEnabled()) {
            if (_log.shouldInfo()) {
                _log.info("Dynamic job scaling is disabled via configuration");
            }
            return;
        }

        _isRunning = true;
        _isAlive = true;
        I2PThread scalerThread = new I2PThread(this, "JobQScaler", true);
        scalerThread.setPriority(Thread.NORM_PRIORITY);
        scalerThread.start();

        if (_log.shouldInfo()) {
            _log.info("JobQueueScaler started. Min runners: " + getMinRunnersDynamic() +
                     ", Max runners: " + _currentMaxRunners +
                     ", Feedback enabled: " + isFeedbackEnabled());
        }
    }

    /**
     * Shutdown the scaler gracefully.
     */
    public void shutdown() {
        _isRunning = false;
        _isAlive = false;
    }

    /**
     * Check if dynamic scaling is enabled.
     * @return whether dynamic scaling enabled
     */
    private boolean isDynamicScalingEnabled() {
        return _context.getBooleanPropertyDefaultTrue(PROP_DYNAMIC_SCALING);
    }

    /**
     * Check if feedback mechanism is enabled.
     * @return whether feedback enabled
     */
    private boolean isFeedbackEnabled() {
        return _context.getBooleanPropertyDefaultTrue(PROP_FEEDBACK_ENABLED);
    }

    /**
     * Minimum number of runners (floor), dynamic - reads property each time.
     * On router startup, returns more runners to handle startup load.
     * @return the configured minimum, floored at 1, raised to 8 in the first 3 minutes
     */
    int getMinRunnersDynamic() {
        int baseMin = Math.max(1, _context.getProperty(PROP_MIN_RUNNERS, 4));
        // On startup (first 3 minutes), use more runners to handle startup load
        // router() may be null before the router is fully wired (e.g. test contexts)
        long uptime = _context.router() != null ? _context.router().getUptime() : Long.MAX_VALUE;
        if (uptime < 3 * 60 * 1000L) {
            return Math.max(baseMin, 8);  // At least 8 runners during startup
        }
        return baseMin;
    }

    /**
     * Check interval in milliseconds.
     * @return the check interval
     */
    private long getCheckInterval() {
        return _context.getProperty(PROP_SCALE_CHECK_INTERVAL, (int) DEFAULT_SCALE_CHECK_INTERVAL);
    }

    /**
     * Cooldown period between scale events.
     * @return the cooldown period
     */
    private long getCooldownPeriod() {
        long baseCooldown = _context.getProperty(PROP_SCALE_COOLDOWN, (int) DEFAULT_SCALE_COOLDOWN);
        if (_isInExtendedCooldown) {
            return baseCooldown * EXTENDED_COOLDOWN_MULTIPLIER;
        }
        return baseCooldown;
    }

    /**
     * Lag threshold for scaling up.
     * @return the scale up lag threshold
     */
    private int getScaleUpLagThreshold() {
        return _context.getProperty(PROP_SCALE_UP_LAG, DEFAULT_SCALE_UP_LAG_THRESHOLD);
    }

    /**
     * Message delay threshold for scaling up.
     * @return the scale up message delay threshold
     */
    private int getScaleUpMessageDelayThreshold() {
        return _context.getProperty(PROP_SCALE_UP_MSG_DELAY, DEFAULT_SCALE_UP_MESSAGE_DELAY_THRESHOLD);
    }

    /**
     * Lag threshold for scaling down.
     * @return the scale down lag threshold
     */
    private int getScaleDownLagThreshold() {
        return _context.getProperty(PROP_SCALE_DOWN_LAG, DEFAULT_SCALE_DOWN_LAG_THRESHOLD);
    }

    /**
     * Jobs-to-runners ratio threshold for scaling up.
     * @return the scale up jobs ratio
     */
    private double getScaleUpJobsRatio() {
        String prop = _context.getProperty(PROP_SCALE_JOBS_RATIO);
        if (Objects.equals(prop, _cachedScaleJobsRatioProp)) {
            return _cachedScaleJobsRatio;
        }
        _cachedScaleJobsRatioProp = prop;
        if (prop != null) {
            try {
                _cachedScaleJobsRatio = Double.parseDouble(prop);
                return _cachedScaleJobsRatio;
            } catch (NumberFormatException e) {
                _cachedScaleJobsRatio = DEFAULT_SCALE_UP_JOBS_RATIO;
                return _cachedScaleJobsRatio;
            }
        }
        _cachedScaleJobsRatio = DEFAULT_SCALE_UP_JOBS_RATIO;
        return _cachedScaleJobsRatio;
    }

    /**
     * Main scaling loop.
     */
    @Override
    public void run() {
        // Initial warmup - let the queue stabilize
        try {
            Thread.sleep(getCheckInterval() * 2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        while (_isRunning && _isAlive) {
            try {
                long interval = getCheckInterval();
                Thread.sleep(interval);

                if (!_isRunning || !_isAlive) {
                    break;
                }

                // Periodically recalculate max runners based on memory
                // Uses a monotonic counter (not _checksSinceLastScale which resets on scale events)
                if (_periodicCheckCount++ % 10 == 0) {
                    recalculateMaxRunners();
                }

                checkAndScale();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception t) {
                if (_log.shouldError()) {
                    _log.error("Error in JobQueueScaler", t);
                }
            }
        }

        if (_log.shouldInfo()) {
            _log.info("JobQueueScaler stopped");
        }
    }

    /**
     * Check current conditions and scale if necessary.
     */
    private void checkAndScale() {
        int activeRunners = _jobQueue.getActiveRunnerCount();
        int readyJobs = _jobQueue.getReadyCount();
        long maxLag = _jobQueue.getMaxLag();
        long avgLag = (long) _context.statManager().getRate("jobQueue.jobLag").getRate(RateConstants.ONE_MINUTE).getAverageValue();
        long now = _context.clock().now();

        // === Resource saturation signals (gates) ===
        // CPU saturation: don't scale up into contention — the bottleneck is CPU, not threads
        long cpuLoad = 0;
        RateStat cpuRs = _context.statManager().getRate("router.cpuLoad");
        if (cpuRs != null) {
            Rate cpuRate = cpuRs.getRate(RateConstants.ONE_MINUTE);
            if (cpuRate != null) cpuLoad = (long) cpuRate.getAverageValue();
        }
        boolean cpuSaturated = cpuLoad > CPU_SATURATION_THRESHOLD;

        // GC storm: long GC pauses masquerade as lag — adding threads won't help
        long gcPause = 0;
        RateStat gcRs = _context.statManager().getRate("router.gcPauseTime");
        if (gcRs != null) {
            Rate gcRate = gcRs.getRate(RateConstants.ONE_MINUTE);
            if (gcRate != null) gcPause = (long) gcRate.getAverageValue();
        }
        boolean gcStorm = gcPause > GC_STORM_THRESHOLD;

        // Bandwidth saturation: jobs blocked on bandwidth, not CPU — scaling won't help
        long bwDelay = 0;
        RateStat bwRs = _context.statManager().getRate("bwLimiter.outboundDelayedTime");
        if (bwRs != null) {
            Rate bwRate = bwRs.getRate(RateConstants.ONE_MINUTE);
            if (bwRate != null) bwDelay = (long) bwRate.getAverageValue();
        }
        boolean bwSaturated = bwDelay > 500; // 500ms+ avg outbound delay = bandwidth-bound

        // Get message delay from throttle (how backed up we are processing messages)
        long messageDelay = _context.throttle().getMessageDelay();

        // === Trend tracking for predictive scaling ===
        // Track message delay history for rolling baseline
        if (_msgDelayHistoryCount < MSG_DELAY_HISTORY_SIZE) {
            _messageDelayHistory[_msgDelayHistoryCount++] = messageDelay;
        } else {
            _messageDelayHistory[_msgDelayHistoryIndex] = messageDelay;
            _msgDelayHistoryIndex = (_msgDelayHistoryIndex + 1) % MSG_DELAY_HISTORY_SIZE;
        }
        long baselineMsgDelay = computeMedianBaseline();

        // Rate-of-change signals (delta since last check)
        int queueGrowth = _previousReadyJobs >= 0 ? readyJobs - _previousReadyJobs : 0;
        long delayDelta = _previousMessageDelay >= 0 ? messageDelay - _previousMessageDelay : 0;
        _previousReadyJobs = readyJobs;
        _previousMessageDelay = messageDelay;

        // Secondary congestion signal: tunnel build success rate < 50% means router is congested
        double buildSuccessRate = readBuildSuccessRate();
        boolean networkCongested = !Double.isNaN(buildSuccessRate) && buildSuccessRate < 50.0;

        // Trend-derived early warning signals
        boolean rapidQueueGrowth = queueGrowth > QUEUE_GROWTH_RATE_THRESHOLD;
        boolean messageDelaySpike = baselineMsgDelay > 5 && messageDelay > baselineMsgDelay * BASELINE_MULTIPLIER;
        boolean delayAccelerating = delayDelta > 5 && queueGrowth > 0;

        // Mean job duration trend: systemic slowdown (not just one stuck job)
        long avgJobDuration = 0;
        RateStat jobRunRs = _context.statManager().getRate("jobQueue.jobRun");
        if (jobRunRs != null) {
            Rate jobRunRate = jobRunRs.getRate(RateConstants.ONE_MINUTE);
            if (jobRunRate != null) avgJobDuration = (long) jobRunRate.getAverageValue();
        }
        boolean jobsSlowing = avgJobDuration > getScaleUpLagThreshold() * 5; // avg job > 50ms = systemic

        // Record memory usage stat
        _context.statManager().addRateData("jobQueue.memoryUsedPercent", (long) getMemoryUsagePercent());

        // Record current runner count for monitoring
        _context.statManager().addRateData("jobQueue.runnerCount", activeRunners);

        // The feedback check judges a scale-up on worst-lag, and the only
        // worst-lag available was the instantaneous sample of one check
        _context.statManager().addRateData("jobQueue.maxLag", maxLag);

        // === Dropped jobs signal ===
        boolean jobsBeingDropped = false;
        RateStat droppedRs = _context.statManager().getRate("jobQueue.droppedJobs");
        if (droppedRs != null) {
            Rate droppedRate = droppedRs.getRate(RateConstants.ONE_MINUTE);
            jobsBeingDropped = droppedRate != null && droppedRate.getLastEventCount() > 0;
        }

        // === Load recovery tracking ===
        // Episode starts when either metric exceeds its adaptive threshold:
        //   maxLag > 2×  avgLag (1-min average of jobQueue.jobLag)
        //   messageDelay > 1.25× baselineMsgDelay (12s median of transport.sendProcessingTime)
        // Thresholds adapt to each router's normal operating level automatically.
        long maxLagThreshold = 2 * Math.max(avgLag, 1);
        long msgDelayThresholdRec = (long)(1.25 * Math.max(baselineMsgDelay, 1));
        boolean loaded = maxLag > maxLagThreshold || messageDelay > msgDelayThresholdRec;
        if (loaded) {
            if (_loadEpisodeStart == 0) {
                _loadEpisodeStart = now;
                if (_log.shouldDebug()) {
                    _log.debug("Load episode started: maxLag: " + maxLag + "ms" +
                               " (>" + maxLagThreshold + ") * messageDelay: " + messageDelay +
                               "ms (>" + msgDelayThresholdRec + ")");
                }
            }
        } else if (_loadEpisodeStart > 0) {
            long recoveryTime = now - _loadEpisodeStart;
            if (recoveryTime > 0) {
                _context.statManager().addRateData("jobQueue.loadRecoveryTime", recoveryTime);
                _lastRecoveryTime = recoveryTime;
                if (_log.shouldInfo()) {
                    _log.info("Load episode recovered in " + recoveryTime + "ms" +
                              " (maxLag: " + maxLag + "ms," +
                              " msgDelay: " + messageDelay + "ms)");
                }
            }
            _loadEpisodeStart = 0;
        }

        // Check cooldown
        long timeSinceLastScale = now - _lastScaleTime;
        boolean inCooldown = timeSinceLastScale < getCooldownPeriod();

        int minRunners = getMinRunnersDynamic();
        int maxRunners = _currentMaxRunners;

        // Get active job duration for monitoring
        long activeJobMaxDuration = _jobQueue.getMaxActiveJobDuration();

        // Debug logging every 10 seconds to trace scaler decisions
        if (_log.shouldDebug() && (_periodicCheckCount % 10 == 0)) {
            _log.debug("JobQueueScaler check: runners: " + activeRunners + "/" + maxRunners +
                      " * readyJobs: " + readyJobs + " * maxLag: " + maxLag + "ms" +
                      " * avgLag: " + avgLag + "ms" +
                      "\n* messageDelay: " + messageDelay + "ms" +
                      " * baselineDelay: " + baselineMsgDelay + "ms" +
                      " * cpuLoad: " + cpuLoad + "% * gcPause: " + gcPause + "ms" +
                      " * bwDelay: " + bwDelay + "ms" +
                      "\n* queueGrowth: " + queueGrowth + " * delayDelta: " + delayDelta +
                      " * activeJobMaxDuration: " + activeJobMaxDuration + "ms" +
                      " * avgJobDuration: " + avgJobDuration + "ms" +
                      "\n* inCooldown: " + inCooldown +
                      " * timeSinceLastScale: " + timeSinceLastScale + "ms" +
                      "\n* scalingDisabled: " + _scalingUpDisabled +
                      " * extendedCooldown: " + _isInExtendedCooldown +
                      " * preScaleSnapshot: " + (_preScaleSnapshot != null) +
                      " * droppedJobs: " + jobsBeingDropped);
        }

        // Check if we need to evaluate feedback from last scale-up
        if (_preScaleSnapshot != null) {
            if (!isFeedbackEnabled()) {
                // Turning feedback off mid-flight must not strand the snapshot. A
                // scale-down is gated on there being no snapshot pending, so an
                // orphaned one would block every scale-down for the rest of the run.
                _preScaleSnapshot = null;
                _checksSinceLastScale = 0;
                _degradedFeedbackChecks = 0;
            } else {
                _checksSinceLastScale++;

                if (_checksSinceLastScale >= FEEDBACK_CHECKS_AFTER_SCALE) {
                    evaluateScalingFeedback(activeRunners, avgLag, messageDelay, jobsBeingDropped,
                                            oneMinuteAverage("jobQueue.readyJobs"),
                                            oneMinuteAverage("jobQueue.maxLag"));
                }
                // Still in feedback period - skip scaling decisions
                return;
            }
        }

        // Don't scale up if circuit breaker is open — but jobs being dropped
        // is an emergency that overrides the breaker. Without this bypass, the
        // breaker permanently freezes the runner count while droppedJob pressure
        // continues to climb.
        if (_scalingUpDisabled) {
            if (timeSinceLastScale > getCooldownPeriod()) {
                _scalingUpDisabled = false;
                _consecutiveFailedScaleUps = 0;
                _isInExtendedCooldown = false;
                if (_log.shouldInfo()) {
                    _log.info("Circuit breaker reset: cooldown expired, allowing scale-up attempts");
                }
            } else if (!jobsBeingDropped) {
                checkScaleDown(activeRunners, readyJobs, maxLag, avgLag, minRunners, inCooldown);
                return;
            } else if (_log.shouldInfo()) {
                _log.info("Circuit breaker bypass: jobs being dropped, allowing emergency scale-up");
            }
        }

        // Determine if we should scale up
        boolean shouldScaleUp = false;
        boolean canScaleUp = !inCooldown && activeRunners < maxRunners && !_scalingUpDisabled;

        // Suppress normal scale-up when resource saturation is the bottleneck,
        // not thread count. Emergency signals override the suppression.
        boolean suppressNormal = cpuSaturated || gcStorm || networkCongested || bwSaturated;

        // TestJobs run 10-30s per invocation, occupying a runner slot without
        // contributing to "productive" job throughput. When queued TestJobs
        // exceed a threshold relative to active runners, we need headroom
        // so shorter tasks (searches, builds) aren't starved.
        // Use getReadyTestJobCount() to exclude future-scheduled timed jobs,
        // which don't consume runner resources.
        int testJobCount = _jobQueue.getReadyTestJobCount();
        boolean testJobsDominant = testJobCount > 2 && activeRunners > 2
            && (double) testJobCount / activeRunners > 0.25;

        if (canScaleUp) {
            double jobsRatio = activeRunners > 0 ? (double) readyJobs / activeRunners : 0;
            int lagThreshold = getScaleUpLagThreshold();
            double ratioThreshold = getScaleUpJobsRatio();

            int msgDelayThreshold = getScaleUpMessageDelayThreshold();
            long effectiveDelay = Math.max(maxLag, messageDelay);
            boolean highLag = maxLag > lagThreshold;
            boolean highMessageDelay = messageDelay > msgDelayThreshold;
            boolean highAvgLag = avgLag >= lagThreshold;
            boolean highBacklog = readyJobs > 0 && jobsRatio > ratioThreshold;
            boolean criticalLag = maxLag > lagThreshold * 10 || messageDelay > msgDelayThreshold * 10;
            boolean slowActiveJobs = activeJobMaxDuration > lagThreshold * 50; // 500ms+ = stuck
            boolean criticalSlowJobs = activeJobMaxDuration > lagThreshold * 100; // 1s+ = wedged

            boolean hasTrendSignal = rapidQueueGrowth || messageDelaySpike || delayAccelerating ||
                                     jobsBeingDropped || jobsSlowing || testJobsDominant;

            // Emergency signals bypass the suppression gate
            boolean emergencySignal = criticalLag || criticalSlowJobs || jobsBeingDropped;

            if (emergencySignal || !suppressNormal) {
                // Under trend signals, skip the sustained-checks delay
                int effectiveSustainedRequired = hasTrendSignal ? 1 : SUSTAINED_CHECKS_REQUIRED;

                if (highBacklog || highLag || highMessageDelay || highAvgLag || slowActiveJobs ||
                    rapidQueueGrowth || messageDelaySpike || delayAccelerating || jobsBeingDropped ||
                    jobsSlowing || testJobsDominant) {
                    _consecutiveScaleUpChecks++;
                    if (_consecutiveScaleUpChecks >= effectiveSustainedRequired || criticalLag || criticalSlowJobs) {
                        shouldScaleUp = true;
                        if (_log.shouldInfo()) {
                            StringBuilder info = new StringBuilder(128);
                            if (criticalLag)
                                info.append("Critical delay: ").append(effectiveDelay);
                            if (criticalSlowJobs)
                                info.append(" Active job duration: ").append(activeJobMaxDuration)
                                    .append("ms");
                            if (hasTrendSignal)
                                info.append("Trend signal: queueGrowth: ").append(queueGrowth)
                                    .append(" * delayDelta: ").append(delayDelta);
                            if (jobsBeingDropped)
                                info.append(" Jobs being dropped");
                            if (testJobsDominant)
                                info.append(" TestJobs: ").append(testJobCount)
                                    .append(" (").append(testJobCount * 100 / Math.max(activeRunners, 1))
                                    .append("% of runners)");
                            if (suppressNormal && emergencySignal)
                                info.append(" Emergency override (cpu: ").append(cpuLoad)
                                    .append("% * gc: ").append(gcPause).append("ms)");
                            info.append(" > threshold: ").append(lagThreshold)
                                .append("ms. Scaling immediately. Runners: ")
                                .append(activeRunners).append("/").append(maxRunners)
                                .append(", Ready jobs: ").append(readyJobs);
                            _log.info(info.toString());
                        }
                    }
                } else {
                    _consecutiveScaleUpChecks = 0;
                }
            } else {
                // Suppressed by resource saturation — don't accumulate scale-up checks
                _consecutiveScaleUpChecks = 0;
                if (_log.shouldDebug()) {
                    _log.debug("Scale-up suppressed: cpuLoad: " + cpuLoad +
                               "% * gcPause: " + gcPause + "ms" +
                               " * bwDelay: " + bwDelay + "ms" +
                               " * networkCongested: " + networkCongested);
                }
            }
        }

        // External floor: scale up to meet subsystem-requested minimum,
        // bypassing normal trigger conditions
        int effectiveMin = getEffectiveMinRunners();
        if (!shouldScaleUp && effectiveMin > activeRunners && canScaleUp) {
            shouldScaleUp = true;
        }

        // Execute scaling
        if (shouldScaleUp) {
            int scaleUpStep;
            if (effectiveMin > activeRunners) {
                // External floor: just reach the requested level
                scaleUpStep = Math.min(effectiveMin, maxRunners) - activeRunners;
            } else {
                scaleUpStep = Math.max(DEFAULT_SCALE_UP_STEP, 2);
                if (maxLag > 1000) {
                    scaleUpStep = Math.max(scaleUpStep, 4);
                } else if (maxLag > 500) {
                    scaleUpStep = Math.max(scaleUpStep, 3);
                }
                int lagThreshold = getScaleUpLagThreshold();
                if (maxLag > lagThreshold * 5) {
                    scaleUpStep = Math.min(6, (int) (maxLag / lagThreshold / 2));
                }
                if (rapidQueueGrowth) {
                    scaleUpStep = Math.min(8, scaleUpStep + queueGrowth / QUEUE_GROWTH_RATE_THRESHOLD);
                }
                if (networkCongested) {
                    scaleUpStep = Math.min(8, scaleUpStep + 1);
                }
                if (jobsBeingDropped) {
                    scaleUpStep = Math.min(8, scaleUpStep + 2);
                }
                if (testJobsDominant) {
                    scaleUpStep = Math.min(8, scaleUpStep + Math.max(1, testJobCount / 3));
                }
            }

            int targetRunners = Math.min(activeRunners + scaleUpStep, maxRunners);
            int runnersToAdd = targetRunners - activeRunners;

            if (runnersToAdd > 0) {
                scaleUp(runnersToAdd, readyJobs, maxLag, avgLag, messageDelay);
            }
        } else {
            checkScaleDown(activeRunners, readyJobs, maxLag, avgLag, effectiveMin, inCooldown);
        }
    }

    /**
     * Check if we should scale down.
     *
     * @param activeRunners the current number of JobQueueRunner threads
     * @param readyJobs jobs ready to run now, used for the job-to-runner ratio
     * @param maxLag ms the oldest ready job has waited, logged with the action
     * @param avgLag ms average lag of the ready jobs, compared with the
     *               scale-down lag threshold
     * @param minRunners the floor the runner count may not be reduced below
     * @param inCooldown true when a scale action happened recently enough to
     *                   suppress another one
     */
    private void checkScaleDown(int activeRunners, int readyJobs, long maxLag, long avgLag,
                                int minRunners, boolean inCooldown) {
        // Don't scale down when TestJobs dominate — they occupy runners for 10-30s,
        // so a low lag snapshot doesn't mean we have excess capacity.
        // Exclude future-scheduled timed TestJobs, which don't consume runner resources.
        int testJobCount = _jobQueue.getReadyTestJobCount();
        boolean testJobsDominant = testJobCount > 2 && activeRunners > 2
            && (double) testJobCount / activeRunners > 0.25;
        if (testJobsDominant) {
            if (_log.shouldDebug()) {
                _log.debug("Scale-down blocked: testJobs: " + testJobCount +
                          " * activeRunners: " + activeRunners);
            }
            return;
        }

        // A scale-down that lands right on the heels of a scale-up undoes it:
        // the lag signal has not had time to respond to the runners that were
        // just added, and the pair destroys threads the previous event created.
        long dwellMs = getCooldownPeriod() * DWELL_AFTER_SCALE_UP_COOLDOWNS;
        if (dwellAfterScaleUp(_lastScaleTime, _lastScaleWasUp, _context.clock().now(), dwellMs)) {
            if (_log.shouldDebug()) {
                _log.debug("Scale-down held for dwell after scale-up -> Runners: " + activeRunners
                           + " * Dwell: " + dwellMs + "ms");
            }
            return;
        }

        if (!inCooldown && activeRunners > minRunners && _preScaleSnapshot == null) {
            if (wantsScaleDown(avgLag, readyJobs, activeRunners, getScaleDownLagThreshold())) {
                _consecutiveScaleDownChecks++;
                if (_consecutiveScaleDownChecks >= SUSTAINED_CHECKS_REQUIRED_DOWN) {
                    int runnersToRemove = scaleDownStep(activeRunners, minRunners,
                                                        _lastScaleUpCount, DEFAULT_SCALE_DOWN_STEP);
                    if (runnersToRemove > 0) {
                        scaleDown(runnersToRemove, readyJobs, maxLag);
                    }
                }
            } else {
                _consecutiveScaleDownChecks = 0;
            }
        }
    }

    /**
     * Pure decision: does this check's load look low enough to want a
     * scale-down? The ready-job depth is not required to reach zero: with the
     * timed jobs a running router keeps queued, it never does. What matters is
     * the job-to-runner ratio staying under 1, with the average lag under the
     * scale-down threshold.
     *
     * @param avgLag ms average lag of the ready jobs
     * @param readyJobs jobs ready to run now
     * @param activeRunners runners in effect now
     * @param lagThreshold ms of average lag low enough to shed a runner
     * @return true when this check wants a scale-down
     * @since 0.9.72+
     */
    static boolean wantsScaleDown(long avgLag, int readyJobs, int activeRunners, int lagThreshold) {
        return avgLag < lagThreshold && readyJobs < activeRunners;
    }

    /**
     * Pure decision: is a scale-down still inside the dwell that must follow a
     * scale-up? Only a scale-up earns the dwell — after a scale-down the queue
     * is already on its way down and there is nothing to undo.
     *
     * @param lastScaleTime adjusted ms of the last scale event, 0 if there was none
     * @param lastScaleWasUp true when that event added runners
     * @param now the current adjusted time in ms
     * @param dwellMs how long must pass after a scale-up before a scale-down
     *                may run
     * @return true while a scale-down must be held back
     * @since 0.9.72+
     */
    static boolean dwellAfterScaleUp(long lastScaleTime, boolean lastScaleWasUp,
                                     long now, long dwellMs) {
        return lastScaleWasUp && lastScaleTime > 0 && now - lastScaleTime < dwellMs;
    }

    /**
     * Pure decision: how many runners to remove in one scale-down. Half the
     * excess above the floor, at least the step, and never more than the last
     * scale-up added. Removing more than the scale-up put in drives the count
     * below the level the scale-up judged necessary, and the next scale-up
     * undoes it — the oscillation that made and destroyed threads for nothing.
     *
     * @param activeRunners runners in effect now
     * @param minRunners the floor the count may not go below
     * @param lastScaleUpCount runners the last scale-up added, 0 when there was
     *                         no scale-up to bound by
     * @param step the minimum runners to remove
     * @return runners to remove, 0 when there is nothing to remove
     * @since 0.9.72+
     */
    static int scaleDownStep(int activeRunners, int minRunners, int lastScaleUpCount, int step) {
        int excess = activeRunners - minRunners;
        if (excess <= 0) {return 0;}
        int want = Math.max(step, excess / 2);
        if (lastScaleUpCount > 0) {
            want = Math.min(want, lastScaleUpCount);
        }
        return Math.min(want, excess);
    }

    /**
     * Evaluate whether the last scale-up actually helped, and roll it back if
     * it did not.
     * <p>
     * The verdict is taken on smoothed values, and only after
     * {@code FEEDBACK_DEGRADED_CHECKS_REQUIRED} checks in a row look degraded.
     * The comparison used to pit one instantaneous sample of the ready-job
     * depth and the worst lag against one other sample, so a single unlucky
     * reading destroyed the threads the scale-up had just created, and the next
     * scale-up made them again.
     *
     * @param currentRunners runner count now, caps how many added runners roll back
     * @param currentAvgLag ms average lag now, compared with the pre-scale average
     * @param currentMessageDelay ms transport send delay now, compared with the
     *                            send delay captured before the scale
     * @param currentDroppedJobs true when the queue has dropped jobs since the
     *                           snapshot, which forces a rollback
     * @param smoothReadyJobs 1-minute average ready jobs now
     * @param smoothMaxLag ms 1-minute average worst ready-job lag now
     */
    private void evaluateScalingFeedback(int currentRunners, long currentAvgLag,
                                        long currentMessageDelay, boolean currentDroppedJobs,
                                        long smoothReadyJobs, long smoothMaxLag) {
        if (_preScaleSnapshot == null) return;

        PreScaleSnapshot snapshot = _preScaleSnapshot;
        boolean memoryCritical = getMemoryUsagePercent() > MEMORY_CRITICAL_PERCENT;
        boolean degraded = feedbackDegraded(snapshot, currentAvgLag, currentMessageDelay,
                                            currentDroppedJobs, memoryCritical,
                                            smoothReadyJobs, smoothMaxLag);

        if (degraded) {
            _degradedFeedbackChecks++;
            if (_degradedFeedbackChecks < FEEDBACK_DEGRADED_CHECKS_REQUIRED) {
                // one degraded sample is not a verdict - keep the snapshot and
                // judge the same scale-up again on the next check
                if (_log.shouldDebug()) {
                    _log.debug("Scale-up looks degraded -> Checking again: "
                               + (_degradedFeedbackChecks + 1) + " of "
                               + FEEDBACK_DEGRADED_CHECKS_REQUIRED);
                }
                return;
            }
        }

        _preScaleSnapshot = null;
        _checksSinceLastScale = 0;
        _degradedFeedbackChecks = 0;

        if (degraded) {
            // Scaling made things worse - rollback!
            _consecutiveFailedScaleUps++;

            if (_log.shouldInfo()) {
                _log.info("Scale-up failed -> Rolling back: " + snapshot.runnersAdded
                          + " runners * Failures: " + _consecutiveFailedScaleUps
                          + " of " + MAX_CONSECUTIVE_FAILED_SCALES
                          + "\n* Avg lag: " + snapshot.avgLag + "ms -> " + currentAvgLag + "ms"
                          + " * Max lag: " + snapshot.smoothMaxLag + "ms -> " + smoothMaxLag + "ms"
                          + "\n* Ready jobs: " + snapshot.smoothReadyJobs + " -> " + smoothReadyJobs
                          + " * Send delay: " + snapshot.messageDelay + "ms -> "
                          + currentMessageDelay + "ms");
            }

            // Rollback: remove the runners we just added
            int runnersToRemove = Math.min(snapshot.runnersAdded, currentRunners - getMinRunnersDynamic());
            if (runnersToRemove > 0) {
                int removed = _jobQueue.removeIdleRunners(runnersToRemove);
                if (removed > 0) {
                    _context.statManager().addRateData("jobQueue.scaleRollback", removed);
                    _lastScaleUpCount = Math.max(0, _lastScaleUpCount - removed);
                }
            }

            // Enter extended cooldown
            _isInExtendedCooldown = true;
            long cooldown = getCooldownPeriod();
            _lastScaleTime = _context.clock().now();
            // a rollback takes runners away, so it is a scale-down for the dwell
            _lastScaleWasUp = false;

            // Circuit breaker: if we've failed too many times, disable scaling up
            if (_consecutiveFailedScaleUps >= MAX_CONSECUTIVE_FAILED_SCALES) {
                _scalingUpDisabled = true;
                if (_log.shouldInfo()) {
                    _log.info("Circuit breaker open -> Scaling up disabled: " + _consecutiveFailedScaleUps
                              + " failed attempts * Jobs may be CPU-bound * Retry in "
                              + DataHelper.formatDuration(cooldown));
                }
            }
        } else {
            // Scaling helped - reset failure counter
            if (_consecutiveFailedScaleUps > 0) {
                _consecutiveFailedScaleUps = 0;
                _isInExtendedCooldown = false;
                if (_log.shouldInfo()) {
                    _log.info("Scale-up helped -> Resetting the failure counter");
                }
            }
        }
    }

    /**
     * Pure decision: did the last scale-up make things worse?
     * <p>
     * Ready jobs and worst lag are compared as 1-minute averages on both sides
     * of the scale event. The send delay is compared with the send delay
     * captured before the scale, not with the job lag: the two measure
     * different things, and the earlier comparison against the job-lag baseline
     * rolled a scale-up back for load that extra runners were never going to
     * absorb.
     *
     * @param snapshot the state captured before the scale-up
     * @param currentAvgLag ms average job lag now
     * @param currentMessageDelay ms transport send delay now
     * @param currentDroppedJobs true when jobs have been dropped since the scale
     * @param memoryCritical true when the heap is near full
     * @param smoothReadyJobs 1-minute average ready jobs now
     * @param smoothMaxLag ms 1-minute average worst ready-job lag now
     * @return true when the scale-up looks like it did not help
     * @since 0.9.72+
     */
    static boolean feedbackDegraded(PreScaleSnapshot snapshot, long currentAvgLag,
                                    long currentMessageDelay, boolean currentDroppedJobs,
                                    boolean memoryCritical, long smoothReadyJobs, long smoothMaxLag) {
        // dropped jobs and a full heap are emergencies, one sample is enough
        if (currentDroppedJobs || memoryCritical) {return true;}
        if (snapshot.avgLag > 0 && currentAvgLag > snapshot.avgLag * LAG_INCREASE_THRESHOLD) {return true;}
        if (snapshot.smoothMaxLag > 0
            && smoothMaxLag > snapshot.smoothMaxLag * LAG_INCREASE_THRESHOLD) {return true;}
        if (snapshot.smoothReadyJobs > 0
            && smoothReadyJobs > snapshot.smoothReadyJobs * READY_JOBS_INCREASE_THRESHOLD) {return true;}
        return snapshot.messageDelay > 0
               && currentMessageDelay > snapshot.messageDelay * LAG_INCREASE_THRESHOLD;
    }

    /**
     * The 1-minute average of a rate, so a signal can be compared across a
     * scale event on a smoothed value instead of on one instantaneous sample.
     *
     * @param name the rate stat's name
     * @return the average, 0 when the stat is absent or has no data yet
     * @since 0.9.72+
     */
    private long oneMinuteAverage(String name) {
        RateStat stat = _context.statManager().getRate(name);
        if (stat == null) {return 0;}
        Rate rate = stat.getRate(RateConstants.ONE_MINUTE);
        return rate != null ? (long) rate.getAverageValue() : 0;
    }

    /**
     * Scale up by adding runners, and snapshot the current queue metrics so the
     * feedback check can judge whether the change helped.
     *
     * @param count runners to add, already capped at the maximum by the caller
     * @param readyJobs ready job count now, logged with the event
     * @param maxLag ms of worst ready-job lag now, logged with the event
     * @param avgLag ms of average lag now, the snapshot's lag baseline
     * @param messageDelay ms of transport send delay now, the snapshot's baseline
     */
    private void scaleUp(int count, int readyJobs, long maxLag, long avgLag, long messageDelay) {
        _lastScaleTime = _context.clock().now();
        _lastScaleWasUp = true;
        _lastScaleUpCount = count;
        _degradedFeedbackChecks = 0;
        _consecutiveScaleUpChecks = 0;
        _consecutiveScaleDownChecks = 0;
        _checksSinceLastScale = 0;
        _isInExtendedCooldown = false; // Reset extended cooldown on new scale attempt

        if (_log.shouldInfo()) {
            _log.info("Scaling up -> Adding: " + count + " * Ready jobs: " + readyJobs
                      + " * Max lag: " + maxLag + "ms * Avg lag: " + avgLag + "ms");
        }

        // Capture pre-scale snapshot for feedback
        if (isFeedbackEnabled()) {
            _preScaleSnapshot = new PreScaleSnapshot(avgLag, count, messageDelay,
                                                    oneMinuteAverage("jobQueue.readyJobs"),
                                                    oneMinuteAverage("jobQueue.maxLag"));
        }

        _jobQueue.addRunners(count);
        _context.statManager().addRateData("jobQueue.runnerScaleUp", count);
        _context.statManager().addRateData("jobQueue.scaleUpEvents", 1);
    }

    /**
     * Scale down by removing runners. The queue retires idle runners only, so
     * fewer than requested may be removed.
     *
     * @param count runners to remove
     * @param readyJobs ready job count now, logged with the action
     * @param maxLag ms of worst ready-job lag now, logged with the action
     */
    private void scaleDown(int count, int readyJobs, long maxLag) {
        _lastScaleTime = _context.clock().now();
        _lastScaleWasUp = false;
        _degradedFeedbackChecks = 0;
        _consecutiveScaleUpChecks = 0;
        _consecutiveScaleDownChecks = 0;

        // Clear any pending feedback snapshot when scaling down
        if (_preScaleSnapshot != null) {
            _preScaleSnapshot = null;
            _checksSinceLastScale = 0;
        }

        if (_log.shouldInfo()) {
            _log.info("Scaling down -> Removing: " + count + " * Ready jobs: " + readyJobs
                      + " * Max lag: " + maxLag + "ms");
        }

        int removed = _jobQueue.removeIdleRunners(count);
        if (removed > 0) {
            _context.statManager().addRateData("jobQueue.runnerScaleDown", removed);
            _context.statManager().addRateData("jobQueue.scaleDownEvents", 1);
            // what the last scale-up has left to give, so the next scale-down
            // is bounded by the runners actually still owed to it
            _lastScaleUpCount = Math.max(0, _lastScaleUpCount - removed);
        }
    }

    /**
     * Request additional runners for an anticipated burst.
     * Called by subsystems (e.g., Tuner) that know demand is imminent.
     * The floor prevents the scaler from reducing below active + requested
     * until the lease expires or is released explicitly.
     * <p>
     * Repeated calls under one reason accumulate, so a caller that asks on
     * every pass must also release what it asked for. Prefer {@link
     * #setRunners}, which replaces the claim and needs no release.
     *
     * @param count  number of additional runners to reserve
     * @param reason  label for tracking and logging
     * @param autoReleaseMs  if &gt; 0, the lease stops counting after this many ms
     * @since 0.9.70+
     */
    public void requestRunners(int count, String reason, long autoReleaseMs) {
        if (count <= 0) return;
        long expiresOn = expiry(autoReleaseMs);
        long now = _context.clock().now();
        _externalRequests.compute(reason, (k, v) -> {
            int held = v != null && v.isLive(now) ? v._count : 0;
            return new Lease(held + count, expiresOn);
        });
        recalcExternalFloor();
        if (_log.shouldInfo()) {
            _log.info("Requested " + count + " runners -> Reason: " + reason
                      + " * Floor: " + _externalFloor.get());
        }
    }

    /**
     * Release previously requested runners. Idempotent — releasing more
     * than requested just floors the per-reason count at 0.
     *
     * @param count  number of runners to release
     * @param reason  must match the reason used in requestRunners
     * @since 0.9.70+
     */
    public void releaseRunners(int count, String reason) {
        if (count <= 0) return;
        long now = _context.clock().now();
        _externalRequests.computeIfPresent(reason, (k, v) -> {
            int held = v.isLive(now) ? v._count : 0;
            int left = Math.max(0, held - count);
            return left <= 0 ? null : new Lease(left, v._expiresOn);
        });
        recalcExternalFloor();
        if (_log.shouldInfo()) {
            _log.info("Released " + count + " runners -> Reason: " + reason
                      + " * Floor: " + _externalFloor.get());
        }
    }

    /**
     * Set the runners held under one reason, replacing whatever was claimed
     * before. A caller with a standing request refreshes it here on each pass,
     * in a single call, so the floor never transiently drops to zero the way a
     * release-then-request pair allows: the scaler's check landing between the
     * two calls saw a floor 2 lower, scaled down, then scaled the runners back
     * in on the next pass.
     *
     * @param reason  label for tracking and logging, non-null
     * @param count  runners to hold; 0 withdraws the request
     * @param ttlMs  how long the claim stays in force, refreshed by each call;
     *               0 means it never expires
     * @since 0.9.72+
     */
    public void setRunners(String reason, int count, long ttlMs) {
        if (count <= 0) {
            _externalRequests.remove(reason);
        } else {
            _externalRequests.put(reason, new Lease(count, expiry(ttlMs)));
        }
        recalcExternalFloor();
        if (_log.shouldDebug()) {
            _log.debug("Runner request set -> Reason: " + reason + " * Count: " + count
                       + " * Floor: " + _externalFloor.get());
        }
    }

    /**
     * The runners subsystems are currently holding, expired leases excluded.
     *
     * @return the external floor
     * @since 0.9.72+
     */
    int getExternalFloor() {
        recalcExternalFloor();
        return _externalFloor.get();
    }

    /**
     * When a lease taken now stops counting.
     *
     * @param ttlMs how long the lease lasts, 0 for no expiry
     * @return the adjusted ms instant the lease expires, 0 for never
     */
    private long expiry(long ttlMs) {
        return ttlMs > 0 ? _context.clock().now() + ttlMs : 0;
    }

    /**
     * Recalculate the external floor from the live leases, dropping the expired
     * ones as it goes. Pruning at read time is what retires a lease from a
     * subsystem that stopped calling, and the scaler's own check pass drives it,
     * so no timer event is needed for the expiry.
     */
    private void recalcExternalFloor() {
        long now = _context.clock().now();
        int total = 0;
        for (Map.Entry<String, Lease> entry : _externalRequests.entrySet()) {
            Lease lease = entry.getValue();
            if (lease.isLive(now)) {
                total += lease._count;
            } else {
                _externalRequests.remove(entry.getKey(), lease);
            }
        }
        _externalFloor.set(total);
    }

    /**
     * Effective minimum runners including external requests.
     *
     * @return base min runners + external floor
     */
    private int getEffectiveMinRunners() {
        // leases expire on this pass, not on a timer event
        recalcExternalFloor();
        return getMinRunnersDynamic() + _externalFloor.get();
    }

    /**
     * Check if the scaler is currently running.
     * @return true between {@link #startup} and {@link #shutdown}, and false when dynamic
     * scaling is disabled, so {@link #startup()} may leave it false
     */
    public boolean isAlive() {
        return _isAlive;
    }

    /**
     * Current maximum runner limit (may be up to 2x configured or RAM-limited).
     * @return the current max runners
     */
    public int getCurrentMaxRunners() {
        return _currentMaxRunners;
    }

    /**
     * Update the maximum runner limit (called when configuration changes).
     * @param configuredMax the new limit from configuration, before the RAM cap is applied
     */
    public void updateMaxRunners(int configuredMax) {
        _configuredMaxRunners = configuredMax;
        _currentMaxRunners = calculateMaxRunnersBasedOnRAM(configuredMax);
        if (_log.shouldInfo()) {
            _log.info("Updated max runners to: " + _currentMaxRunners);
        }
    }
}
