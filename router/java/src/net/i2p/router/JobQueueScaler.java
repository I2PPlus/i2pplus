package net.i2p.router;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
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
 * <li>Compares post-scale metrics after 2 check intervals</li>
 * <li>If lag, maxLag, message delay, or ready jobs INCREASED after scaling, rolls back</li>
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
    /** Runner requests by subsystem name, with expiry times. */
    private final ConcurrentHashMap<String, int[]> _externalRequests = new ConcurrentHashMap<>(4);

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
    /** Runners removed per scale-down, faster than we grow. */
    private static final int DEFAULT_SCALE_DOWN_STEP = 2; // Remove 2 at a time (shrink faster than we grow)
    /** Checks that must want a scale-up before acting. */
    private static final int SUSTAINED_CHECKS_REQUIRED = 3; // 3 checks before scaling up
    /** Checks that must want a scale-down before acting. */
    private static final int SUSTAINED_CHECKS_REQUIRED_DOWN = 2; // 2 checks before scaling down (respond faster to idle)

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
     * Snapshot of metrics taken before scaling up.
     * Used to evaluate if scaling actually helped.
     */
    private static class PreScaleSnapshot {
        /** When the state was captured. */
        final long timestamp;
        /** Runners in effect at the capture. */
        final int runnerCount;
        /** Ready jobs at the capture. */
        final int readyJobs;
        /** Worst job lag at the capture. */
        final long maxLag;
        /** Average job lag at the capture. */
        final long avgLag;
        /** Heap in use at the capture. */
        final long usedMemory;
        /** Runners added by the scale event. */
        final int runnersAdded;

        /**
         * Captures the queue state before a scaling decision.
         *
         * @param runnerCount the job runners then running
         * @param readyJobs the jobs ready to run
         * @param maxLag the longest job lag, in milliseconds
         * @param avgLag the average job lag, in milliseconds
         * @param usedMemory the memory in use, in bytes
         * @param runnersAdded the runners added so far
         */
        PreScaleSnapshot(int runnerCount, int readyJobs, long maxLag, long avgLag,
                        long usedMemory, int runnersAdded) {
            this.timestamp = System.currentTimeMillis();
            this.runnerCount = runnerCount;
            this.readyJobs = readyJobs;
            this.maxLag = maxLag;
            this.avgLag = avgLag;
            this.usedMemory = usedMemory;
            this.runnersAdded = runnersAdded;
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
        if (_preScaleSnapshot != null && isFeedbackEnabled()) {
            _checksSinceLastScale++;

            if (_checksSinceLastScale >= FEEDBACK_CHECKS_AFTER_SCALE) {
                evaluateScalingFeedback(activeRunners, readyJobs, maxLag, avgLag,
                                       messageDelay, jobsBeingDropped);
            }
            // Still in feedback period - skip scaling decisions
            return;
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
                scaleUp(runnersToAdd, readyJobs, maxLag, avgLag);
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

        if (!inCooldown && activeRunners > minRunners && _preScaleSnapshot == null) {
            int lagThreshold = getScaleDownLagThreshold();

            // Scale down when average lag is low and ready jobs are manageable.
            // Don't require readyJobs == 0 — with 90+ timed jobs queued, that never happens.
            // Instead: avg lag below threshold AND ready jobs < activeRunners (job-to-runner ratio < 1).
            boolean lowLag = avgLag < lagThreshold;
            boolean lowBacklog = readyJobs < activeRunners;

            if (lowLag && lowBacklog) {
                _consecutiveScaleDownChecks++;
                if (_consecutiveScaleDownChecks >= SUSTAINED_CHECKS_REQUIRED_DOWN) {
                    // Remove up to half the excess above min, or DEFAULT_SCALE_DOWN_STEP, whichever is larger
                    int excess = activeRunners - minRunners;
                    int runnersToRemove = Math.min(Math.max(DEFAULT_SCALE_DOWN_STEP, excess / 2), excess);
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
     * Evaluate whether the last scale-up actually helped.
     * Checks multiple signals: avg lag, max lag, ready jobs, message delay, and dropped jobs.
     * If any signal worsened beyond threshold, rolls back.
     *
     * @param currentRunners runner count now, caps how many added runners roll back
     * @param currentReadyJobs ready jobs now, compared with the pre-scale snapshot
     * @param currentMaxLag ms, worst ready-job lag now, compared with the snapshot
     * @param currentAvgLag ms average lag now, compared with the pre-scale average
     * @param currentMessageDelay ms message delay now, checked against pre-scale lag
     * @param currentDroppedJobs true when the queue has dropped jobs since the
     *                           snapshot, which forces a rollback
     */
    private void evaluateScalingFeedback(int currentRunners, int currentReadyJobs,
                                        long currentMaxLag, long currentAvgLag,
                                        long currentMessageDelay, boolean currentDroppedJobs) {
        if (_preScaleSnapshot == null) return;

        PreScaleSnapshot snapshot = _preScaleSnapshot;
        _preScaleSnapshot = null;
        _checksSinceLastScale = 0;

        // Calculate changes
        double lagRatio = snapshot.avgLag > 0 ? (double) currentAvgLag / snapshot.avgLag : 1.0;
        double readyJobsRatio = snapshot.readyJobs > 0 ? (double) currentReadyJobs / snapshot.readyJobs : 1.0;

        boolean lagIncreased = lagRatio > LAG_INCREASE_THRESHOLD;
        boolean readyJobsIncreased = readyJobsRatio > READY_JOBS_INCREASE_THRESHOLD;
        boolean memoryCritical = getMemoryUsagePercent() > 85;

        // Widened criteria: max lag also worsened
        boolean maxLagWorsened = snapshot.maxLag > 0 && currentMaxLag > snapshot.maxLag * LAG_INCREASE_THRESHOLD;

        // Widened criteria: message delay worse than pre-scale
        boolean messageDelayWorsened = snapshot.avgLag > 0 && currentMessageDelay > snapshot.avgLag * LAG_INCREASE_THRESHOLD;

        if (lagIncreased || readyJobsIncreased || memoryCritical || maxLagWorsened || messageDelayWorsened || currentDroppedJobs) {
            // Scaling made things worse - rollback!
            _consecutiveFailedScaleUps++;

            if (_log.shouldInfo()) {
                StringBuilder msg = new StringBuilder("SCALE-UP FAILED: Rolling back ")
                    .append(snapshot.runnersAdded).append(" runners. ")
                    .append("avgLag ").append(snapshot.avgLag).append("ms->").append(currentAvgLag).append("ms, ")
                    .append("maxLag ").append(snapshot.maxLag).append("ms->").append(currentMaxLag).append("ms, ")
                    .append("ready ").append(snapshot.readyJobs).append("->").append(currentReadyJobs).append(", ")
                    .append("msgDelay ").append(currentMessageDelay).append("ms");
                if (currentDroppedJobs) msg.append(", jobs being dropped");
                msg.append(" (").append(_consecutiveFailedScaleUps).append("/").append(MAX_CONSECUTIVE_FAILED_SCALES).append(")");
                _log.info(msg.toString());
            }

            // Rollback: remove the runners we just added
            int runnersToRemove = Math.min(snapshot.runnersAdded, currentRunners - getMinRunnersDynamic());
            if (runnersToRemove > 0) {
                int removed = _jobQueue.removeIdleRunners(runnersToRemove);
                if (removed > 0) {
                    _context.statManager().addRateData("jobQueue.scaleRollback", removed);
                }
            }

            // Enter extended cooldown
            _isInExtendedCooldown = true;
            long cooldown = getCooldownPeriod();
            _lastScaleTime = _context.clock().now();

            // Circuit breaker: if we've failed too many times, disable scaling up
            if (_consecutiveFailedScaleUps >= MAX_CONSECUTIVE_FAILED_SCALES) {
                _scalingUpDisabled = true;
                if (_log.shouldInfo()) {
                    _log.info("Circuit breaker open: scaling up disabled due to " +
                              _consecutiveFailedScaleUps + " failed attempts" +
                              "\n* Jobs may be CPU-bound" +
                              " -> Retrying in " + (cooldown / 1000) + "s");
                }
            }
        } else {
            // Scaling helped - reset failure counter
            if (_consecutiveFailedScaleUps > 0) {
                _consecutiveFailedScaleUps = 0;
                _isInExtendedCooldown = false;
                if (_log.shouldInfo()) {
                    _log.info("Job queue runner scale-up successful -> Resetting failure counter...");
                }
            }
        }
    }

    /**
     * Scale up by adding runners, and snapshot the current queue metrics so the
     * feedback check can judge whether the change helped.
     *
     * @param count runners to add, already capped at the maximum by the caller
     * @param readyJobs ready job count now, logged and stored in the snapshot
     * @param maxLag ms of worst ready-job lag now, stored in the snapshot
     * @param avgLag ms of average lag now, the snapshot's lag baseline
     */
    private void scaleUp(int count, int readyJobs, long maxLag, long avgLag) {
        _lastScaleTime = _context.clock().now();
        _consecutiveScaleUpChecks = 0;
        _consecutiveScaleDownChecks = 0;
        _checksSinceLastScale = 0;
        _isInExtendedCooldown = false; // Reset extended cooldown on new scale attempt

        int activeRunners = _jobQueue.getActiveRunnerCount();

        if (_log.shouldInfo()) {
            _log.info("Scaling up: Adding " + count + " runners -> " +
                     "Ready jobs: " + readyJobs + ", Max lag: " + maxLag + "ms, Avg lag: " + avgLag + "ms");
        }

        // Capture pre-scale snapshot for feedback
        if (isFeedbackEnabled()) {
            _preScaleSnapshot = new PreScaleSnapshot(
                activeRunners, readyJobs, maxLag, avgLag,
                getUsedMemory(), count
            );
        }

        _jobQueue.addRunners(count);
        _context.statManager().addRateData("jobQueue.runnerScaleUp", count);
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
        _consecutiveScaleUpChecks = 0;
        _consecutiveScaleDownChecks = 0;

        // Clear any pending feedback snapshot when scaling down
        if (_preScaleSnapshot != null) {
            _preScaleSnapshot = null;
            _checksSinceLastScale = 0;
        }

        if (_log.shouldInfo()) {
            _log.info("Scaling down: Removing " + count + " runners -> " +
                     "Ready jobs: " + readyJobs + ", Max lag: " + maxLag + "ms");
        }

        int removed = _jobQueue.removeIdleRunners(count);
        if (removed > 0) {
            _context.statManager().addRateData("jobQueue.runnerScaleDown", removed);
        }
    }

    /**
     * Request additional runners for an anticipated burst.
     * Called by subsystems (e.g., Tuner) that know demand is imminent.
     * The floor prevents the scaler from reducing below active + requested
     * until auto-decay or explicit release.
     *
     * @param count  number of additional runners to reserve
     * @param reason  label for tracking and logging
     * @param autoReleaseMs  if &gt; 0, auto-decay after this many ms
     * @since 0.9.70+
     */
    public void requestRunners(int count, String reason, long autoReleaseMs) {
        if (count <= 0) return;
        _externalFloor.addAndGet(count);
        _externalRequests.compute(reason, (k, v) -> {
            int[] cur = v;
            if (cur == null) {
                cur = new int[]{0};
            }
            cur[0] += count;
            return cur;
        });
        if (autoReleaseMs > 0) {
            _context.simpleTimer2().addEvent(new AutoReleaseEvent(reason, count), autoReleaseMs);
        }
        if (_log.shouldInfo()) {
            _log.info("Requested " + count + " runners (" + reason + ")"
                      + " -> Floor: " + _externalFloor.get());
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
        _externalRequests.computeIfPresent(reason, (k, v) -> {
            v[0] = Math.max(0, v[0] - count);
            return v[0] <= 0 ? null : v;
        });
        recalcExternalFloor();
        if (_log.shouldInfo()) {
            _log.info("Released " + count + " runners (" + reason + ")"
                      + " -> Floor: " + _externalFloor.get());
        }
    }

    /**
     * Recalculate the external floor from all outstanding requests.
     */
    private void recalcExternalFloor() {
        int total = 0;
        for (int[] v : _externalRequests.values()) {
            total += v[0];
        }
        _externalFloor.set(total);
    }

    /**
     * Effective minimum runners including external requests.
     *
     * @return base min runners + external floor
     */
    private int getEffectiveMinRunners() {
        return getMinRunnersDynamic() + _externalFloor.get();
    }

    /**
     * Auto-release timer event — safety net for stranded requests.
     * Fires once after the auto-release delay if releaseRunners wasn't
     * called explicitly.
     */
    private class AutoReleaseEvent extends SimpleTimer2.TimedEvent {
        /** Why the runners are being released. */
        private final String _reason;
        /** Number of runners to release. */
        private final int _count;

        /**
         * Prepares to release runners back to the floor.
         *
         * @param reason why the runners are released
         * @param count runners to release
         */
        AutoReleaseEvent(String reason, int count) {
            super(_context.simpleTimer2());
            _reason = reason;
            _count = count;
        }

        @Override
        public void timeReached() {
            releaseRunners(_count, _reason);
        }
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
