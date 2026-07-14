package net.i2p.router;

import java.util.Arrays;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Dynamic job runner scaling controller for the JobQueue.
 *
 * Monitors queue metrics (ready job count, lag) and automatically scales
 * the number of JobQueueRunner threads up or down based on load conditions.
 *
 * <strong>Scaling Algorithm:</strong>
 * <ul>
 *   <li>Scale UP when: readyJobs > activeRunners * scaleUpJobsRatio OR maxLag > scaleUpLagThreshold</li>
 *   <li>Scale DOWN when: readyJobs < activeRunners AND maxLag < scaleDownLagThreshold (sustained)</li>
 *   <li>Bounds: min runners = max(4, cores), max runners = min(2 * router.maxJobRunners, RAM-based limit)</li>
 * </ul>
 *
 * <strong>Feedback Mechanism:</strong>
 * <ul>
 *   <li>Captures pre-scale metrics before adding runners</li>
 *   <li>Compares post-scale metrics after 3 check intervals</li>
 *   <li>If lag INCREASED after scaling, automatically rolls back added runners</li>
 *   <li>After 2 failed scale attempts, stops scaling up until router restart</li>
 *   <li>Tracks memory usage to prevent OOM conditions</li>
 * </ul>
 *
 * <strong>Sub-millisecond Lag Target:</strong>
 * With very low lag targets (sub-ms), even small degradations are significant.
 * The feedback system uses tight thresholds (20% increase = failure) to
 * ensure we don't add runners that worsen performance.
 *
 * @since 0.9.68+
 */
class JobQueueScaler implements Runnable {
    private final Log _log;
    private final JobQueue _jobQueue;
    private final RouterContext _context;
    private volatile boolean _isRunning;
    private volatile boolean _isAlive;

    // Scaling state
    private int _consecutiveScaleUpChecks;
    private int _consecutiveScaleDownChecks;
    private long _lastScaleTime;
    private int _configuredMaxRunners;
    private int _currentMaxRunners;

    // Feedback mechanism for detecting ineffective scaling
    private PreScaleSnapshot _preScaleSnapshot;
    private int _checksSinceLastScale;
    private int _consecutiveFailedScaleUps;
    private boolean _isInExtendedCooldown;
    private boolean _scalingUpDisabled; // Circuit breaker after repeated failures

    // Trend tracking for predictive scaling
    private int _previousReadyJobs;
    private long _previousMessageDelay;
    private long[] _messageDelayHistory;
    private int _msgDelayHistoryIndex;
    private int _msgDelayHistoryCount;

    // Recovery time tracking
    private long _loadEpisodeStart;
    private long _lastRecoveryTime;

    // Feedback configuration
    private static final int FEEDBACK_CHECKS_AFTER_SCALE = 2; // Check 2 times after scaling (faster response)
    private static final int MAX_CONSECUTIVE_FAILED_SCALES = 5; // Reduced from 10 - circuit breaker opens sooner
    private static final long EXTENDED_COOLDOWN_MULTIPLIER = 2; // 2x normal cooldown (reduced from 3x)
    private static final double LAG_INCREASE_THRESHOLD = 1.5; // Allow 50% lag increase before rollback (was 2.0 = 100%)
    private static final double READY_JOBS_INCREASE_THRESHOLD = 1.5; // Allow 50% increase before rollback (was 2.0 = 100%)

    // Trend tracking for predictive scaling
    private static final int MSG_DELAY_HISTORY_SIZE = 6; // samples, 12s window at 2s interval
    private static final double BASELINE_MULTIPLIER = 2.0; // trigger when current > 2x baseline median
    private static final int QUEUE_GROWTH_RATE_THRESHOLD = 2; // jobs/check interval

    // RAM-based limits
    private static final long MB = 1024 * 1024L;
    private static final long RUNNER_MEMORY_ESTIMATE = 2 * MB; // ~2MB per thread (stack + overhead)
    private static final double MAX_MEMORY_PERCENTAGE = 0.10; // Use max 10% of heap for runners

    // Configuration defaults
    //
    // Scale-up: add runners when lag is consistently high, not on every transient spike.
    // Scale-down: remove idle runners when load drops, don't require impossible conditions.
    // Max runners capped at 2× cores to prevent context-switching death spirals.

    private static final long DEFAULT_SCALE_CHECK_INTERVAL = 2000; // 2 seconds (less frequent checks reduce overhead)
    private static final long DEFAULT_SCALE_COOLDOWN = 10000; // 10 seconds (cooldown between scale events)
    private static final int DEFAULT_SCALE_UP_LAG_THRESHOLD = 10; // 10ms (scale up only when lag is significant)
    private static final int DEFAULT_SCALE_UP_MESSAGE_DELAY_THRESHOLD = 200; // 200ms (scale up if message delay exceeds 200ms)
    private static final int DEFAULT_SCALE_DOWN_LAG_THRESHOLD = 5; // 5ms (scale down when lag is moderate)
    private static final double DEFAULT_SCALE_UP_JOBS_RATIO = 2.0; // 2x (require real backlog, not just 1 extra job)
    private static final int DEFAULT_SCALE_UP_STEP = 1;
    private static final int DEFAULT_SCALE_DOWN_STEP = 2; // Remove 2 at a time (shrink faster than we grow)
    private static final int SUSTAINED_CHECKS_REQUIRED = 3; // 3 checks before scaling up
    private static final int SUSTAINED_CHECKS_REQUIRED_DOWN = 2; // 2 checks before scaling down (respond faster to idle)

    // Property names
    private static final String PROP_DYNAMIC_SCALING = "router.dynamicJobScaling";
    private static final String PROP_SCALE_UP_LAG = "router.scaleUpLagThreshold";
    private static final String PROP_SCALE_DOWN_LAG = "router.scaleDownLagThreshold";
    private static final String PROP_SCALE_JOBS_RATIO = "router.scaleUpJobsRatio";
    private static final String PROP_SCALE_CHECK_INTERVAL = "router.scaleCheckInterval";
    private static final String PROP_SCALE_COOLDOWN = "router.scaleCooldown";
    private static final String PROP_MIN_RUNNERS = "router.minJobRunners";
    private static final String PROP_FEEDBACK_ENABLED = "router.scaleFeedbackEnabled";

    /**
     * Snapshot of metrics taken before scaling up.
     * Used to evaluate if scaling actually helped.
     */
    private static class PreScaleSnapshot {
        final long timestamp;
        final int runnerCount;
        final int readyJobs;
        final long maxLag;
        final long avgLag;
        final long usedMemory;
        final int runnersAdded;

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
        _configuredMaxRunners = context.getProperty(JobQueue.PROP_MAX_RUNNERS, JobQueue.RUNNERS);
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
        context.statManager().createRateStat("jobQueue.loadRecoveryTime",
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
        int cpuBasedMax = Math.max(cores * 2, 16);

        int effectiveMax = Math.min(ramBasedMax, freeMemoryBasedMax);
        int targetMax = configuredMax * 2;
        int finalMax = Math.min(targetMax, Math.min(effectiveMax, cpuBasedMax));

        // Ensure at least minimum
        finalMax = Math.max(getMinRunnersDynamic(), finalMax);

        if (_log.shouldInfo()) {
            _log.info("Max runners calculation: configured=" + configuredMax +
                     ", target=" + targetMax + ", ramBased=" + ramBasedMax +
                     ", freeMemBased=" + freeMemoryBasedMax + ", final=" + finalMax +
                     " (maxMemory=" + (maxMemory/MB) + "MB, used=" + (usedMemory/MB) + "MB)");
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
     * Get current used memory in bytes.
     */
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * Get memory usage percentage.
     */
    private double getMemoryUsagePercent() {
        long maxMemory = SystemVersion.getMaxMemory();
        if (maxMemory == 0) return 0;
        return (double) getUsedMemory() / maxMemory * 100;
    }

    /**
     * Compute the median of the message delay history ring buffer.
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
        I2PThread scalerThread = new I2PThread(this, "JobQueueScaler", true);
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
     */
    private boolean isDynamicScalingEnabled() {
        return _context.getBooleanPropertyDefaultTrue(PROP_DYNAMIC_SCALING);
    }

    /**
     * Check if feedback mechanism is enabled.
     */
    private boolean isFeedbackEnabled() {
        return _context.getBooleanPropertyDefaultTrue(PROP_FEEDBACK_ENABLED);
    }

    /**
     * Get the minimum number of runners (floor), dynamic - reads property each time.
     * On router startup, returns more runners to handle startup load.
     */
    int getMinRunnersDynamic() {
        int baseMin = Math.max(1, _context.getProperty(PROP_MIN_RUNNERS, 4));
        // On startup (first 3 minutes), use more runners to handle startup load
        long uptime = _context.router().getUptime();
        if (uptime < 3 * 60 * 1000L) {
            return Math.max(baseMin, 8);  // At least 8 runners during startup
        }
        return baseMin;
    }

    /**
     * Get the check interval in milliseconds.
     */
    private long getCheckInterval() {
        return _context.getProperty(PROP_SCALE_CHECK_INTERVAL, (int) DEFAULT_SCALE_CHECK_INTERVAL);
    }

    /**
     * Get the cooldown period between scale events.
     */
    private long getCooldownPeriod() {
        long baseCooldown = _context.getProperty(PROP_SCALE_COOLDOWN, (int) DEFAULT_SCALE_COOLDOWN);
        if (_isInExtendedCooldown) {
            return baseCooldown * EXTENDED_COOLDOWN_MULTIPLIER;
        }
        return baseCooldown;
    }

    /**
     * Get the lag threshold for scaling up.
     */
    private int getScaleUpLagThreshold() {
        return _context.getProperty(PROP_SCALE_UP_LAG, DEFAULT_SCALE_UP_LAG_THRESHOLD);
    }

    /**
     * Get the message delay threshold for scaling up.
     */
    private int getScaleUpMessageDelayThreshold() {
        return DEFAULT_SCALE_UP_MESSAGE_DELAY_THRESHOLD;
    }

    /**
     * Get the lag threshold for scaling down.
     */
    private int getScaleDownLagThreshold() {
        return _context.getProperty(PROP_SCALE_DOWN_LAG, DEFAULT_SCALE_DOWN_LAG_THRESHOLD);
    }

    /**
     * Get the jobs-to-runners ratio threshold for scaling up.
     */
    private double getScaleUpJobsRatio() {
        String prop = _context.getProperty(PROP_SCALE_JOBS_RATIO);
        if (prop != null) {
            try {
                return Double.parseDouble(prop);
            } catch (NumberFormatException e) {
                // ignore, use default
            }
        }
        return DEFAULT_SCALE_UP_JOBS_RATIO;
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
                if (_checksSinceLastScale % 10 == 0) {
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
                    _log.debug("Load episode started: maxLag=" + maxLag + "ms (>" + maxLagThreshold +
                               "), messageDelay=" + messageDelay + "ms (>" + msgDelayThresholdRec + ")");
                }
            }
        } else if (_loadEpisodeStart > 0) {
            long recoveryTime = now - _loadEpisodeStart;
            if (recoveryTime > 0) {
                _context.statManager().addRateData("jobQueue.loadRecoveryTime", recoveryTime);
                _lastRecoveryTime = recoveryTime;
                if (_log.shouldInfo()) {
                    _log.info("Load episode recovered in " + recoveryTime + "ms (maxLag=" + maxLag +
                              "ms, msgDelay=" + messageDelay + "ms)");
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
        if (_log.shouldDebug() && (_checksSinceLastScale % 10 == 0)) {
            _log.debug("JobQueueScaler check: runners=" + activeRunners + "/" + maxRunners +
                      ", readyJobs=" + readyJobs + ", maxLag=" + maxLag + "ms, avgLag=" + avgLag + "ms, " +
                      "messageDelay=" + messageDelay + "ms, baselineDelay=" + baselineMsgDelay + "ms, " +
                      "queueGrowth=" + queueGrowth + ", delayDelta=" + delayDelta + ", " +
                      "activeJobMaxDuration=" + activeJobMaxDuration + "ms, " +
                      "inCooldown=" + inCooldown + ", timeSinceLastScale=" + timeSinceLastScale + "ms, " +
                      "scalingDisabled=" + _scalingUpDisabled + ", extendedCooldown=" + _isInExtendedCooldown +
                      ", preScaleSnapshot=" + (_preScaleSnapshot != null) +
                      ", droppedJobs=" + jobsBeingDropped);
        }

        // Check if we need to evaluate feedback from last scale-up
        if (_preScaleSnapshot != null && isFeedbackEnabled()) {
            _checksSinceLastScale++;

            if (_checksSinceLastScale >= FEEDBACK_CHECKS_AFTER_SCALE) {
                evaluateScalingFeedback(activeRunners, readyJobs, maxLag, avgLag);
            }
            // Still in feedback period - skip scaling decisions
            return;
        }

        // Don't scale up if circuit breaker is open
        if (_scalingUpDisabled) {
            if (timeSinceLastScale > getCooldownPeriod()) {
                _scalingUpDisabled = false;
                _consecutiveFailedScaleUps = 0;
                _isInExtendedCooldown = false;
                if (_log.shouldInfo()) {
                    _log.info("CIRCUIT BREAKER RESET: Cooldown expired, allowing scale-up attempts");
                }
            } else {
                checkScaleDown(activeRunners, readyJobs, maxLag, avgLag, minRunners, inCooldown);
                return;
            }
        }

        // Determine if we should scale up
        boolean shouldScaleUp = false;
        if (!inCooldown && activeRunners < maxRunners && !_scalingUpDisabled) {
            double jobsRatio = activeRunners > 0 ? (double) readyJobs / activeRunners : 0;
            int lagThreshold = getScaleUpLagThreshold();
            double ratioThreshold = getScaleUpJobsRatio();

            int msgDelayThreshold = getScaleUpMessageDelayThreshold();
            long effectiveDelay = Math.max(maxLag, messageDelay);
            boolean highLag = maxLag > lagThreshold;
            boolean highMessageDelay = messageDelay > msgDelayThreshold;
            boolean highAvgLag = avgLag >= lagThreshold;
            boolean highBacklog = readyJobs > 0 && jobsRatio > ratioThreshold;
            boolean criticalLag = maxLag > lagThreshold * 10 || messageDelay > msgDelayThreshold * 10; // 100ms+
            boolean slowActiveJobs = activeJobMaxDuration > lagThreshold * 50; // 500ms+ = stuck
            boolean criticalSlowJobs = activeJobMaxDuration > lagThreshold * 100; // 1s+ = wedged

            // Trend-based triggers allow faster scale-up (predictive, not reactive)
            // Jobs being dropped is a strong overload signal — scale immediately
            boolean hasTrendSignal = rapidQueueGrowth || messageDelaySpike || delayAccelerating || jobsBeingDropped;
            boolean hasCongestionSignal = networkCongested;
            // Under trend/congestion signals, skip the sustained-checks delay
            int effectiveSustainedRequired = (hasTrendSignal || hasCongestionSignal) ? 1 : SUSTAINED_CHECKS_REQUIRED;

            if (highBacklog || highLag || highMessageDelay || highAvgLag || slowActiveJobs ||
                rapidQueueGrowth || messageDelaySpike || delayAccelerating || jobsBeingDropped) {
                _consecutiveScaleUpChecks++;
                if (_consecutiveScaleUpChecks >= effectiveSustainedRequired || criticalLag || criticalSlowJobs) {
                    shouldScaleUp = true;
                    if ((criticalLag || criticalSlowJobs || hasTrendSignal || jobsBeingDropped) && _log.shouldInfo()) {
                        StringBuilder info = new StringBuilder(128);
                        if (criticalLag)
                            info.append("Critical delay=").append(effectiveDelay);
                        if (criticalSlowJobs)
                            info.append(" Active job duration=").append(activeJobMaxDuration).append("ms");
                        if (hasTrendSignal)
                            info.append("Trend signal: queueGrowth=").append(queueGrowth)
                                .append(", delayDelta=").append(delayDelta);
                        if (jobsBeingDropped)
                            info.append(" Jobs being dropped");
                        info.append(" > threshold=").append(lagThreshold).append("ms. Scaling immediately. Runners: ")
                            .append(activeRunners).append("/").append(maxRunners)
                            .append(", Ready jobs: ").append(readyJobs);
                        _log.info(info.toString());
                    }
                }
            } else {
                _consecutiveScaleUpChecks = 0;
            }
        }

        // Execute scaling
        if (shouldScaleUp) {
            int scaleUpStep = Math.max(DEFAULT_SCALE_UP_STEP, 2);
            if (maxLag > 1000) {
                scaleUpStep = Math.max(scaleUpStep, 4);
            } else if (maxLag > 500) {
                scaleUpStep = Math.max(scaleUpStep, 3);
            }
            int lagThreshold = getScaleUpLagThreshold();
            if (maxLag > lagThreshold * 5) {
                scaleUpStep = Math.min(6, (int) (maxLag / lagThreshold / 2));
            }
            // Queue growing fast: scale more aggressively to get ahead of the spike
            if (rapidQueueGrowth) {
                scaleUpStep = Math.min(8, scaleUpStep + queueGrowth / QUEUE_GROWTH_RATE_THRESHOLD);
            }
            // Network congestion: add an extra runner to help drain backlog
            if (networkCongested) {
                scaleUpStep = Math.min(8, scaleUpStep + 1);
            }
            // Jobs being dropped: urgent overload signal, add 2 runners
            if (jobsBeingDropped) {
                scaleUpStep = Math.min(8, scaleUpStep + 2);
            }

            int targetRunners = Math.min(activeRunners + scaleUpStep, maxRunners);
            int runnersToAdd = targetRunners - activeRunners;

            if (runnersToAdd > 0) {
                scaleUp(runnersToAdd, readyJobs, maxLag, avgLag);
            }
        } else {
            checkScaleDown(activeRunners, readyJobs, maxLag, avgLag, minRunners, inCooldown);
        }
    }

    /**
     * Check if we should scale down.
     */
    private void checkScaleDown(int activeRunners, int readyJobs, long maxLag, long avgLag,
                                int minRunners, boolean inCooldown) {
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
     * If lag increased or ready jobs increased (worse performance), roll back.
     */
    private void evaluateScalingFeedback(int currentRunners, int currentReadyJobs,
                                        long _currentMaxLag, long currentAvgLag) {
        if (_preScaleSnapshot == null) return;

        PreScaleSnapshot snapshot = _preScaleSnapshot;
        _preScaleSnapshot = null;
        _checksSinceLastScale = 0;

        // Calculate changes
        double lagRatio = snapshot.avgLag > 0 ? (double) currentAvgLag / snapshot.avgLag : 1.0;
        double readyJobsRatio = snapshot.readyJobs > 0 ? (double) currentReadyJobs / snapshot.readyJobs : 1.0;

        boolean lagIncreased = lagRatio > LAG_INCREASE_THRESHOLD;
        boolean readyJobsIncreased = readyJobsRatio > READY_JOBS_INCREASE_THRESHOLD;
        boolean memoryCritical = getMemoryUsagePercent() > 85; // Roll back if memory is critical

        if (lagIncreased || readyJobsIncreased || memoryCritical) {
            // Scaling made things worse - rollback!
            _consecutiveFailedScaleUps++;

            if (_log.shouldInfo()) {
                _log.info("SCALE-UP FAILED: Rolling back " + snapshot.runnersAdded + " runners. " +
                          "Lag " + snapshot.avgLag + "ms->" + currentAvgLag + "ms, Ready " + snapshot.readyJobs + "->" + currentReadyJobs +
                          " (" + _consecutiveFailedScaleUps + "/" + MAX_CONSECUTIVE_FAILED_SCALES + ")");
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
                    _log.info("CIRCUIT BREAKER OPEN: Scaling up disabled due to " +
                              _consecutiveFailedScaleUps + " failed attempts. Jobs may be CPU-bound. Retrying in " + (cooldown / 1000) + "s.");
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
     * Scale up by adding runners.
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
     * Scale down by removing runners.
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
     * Check if the scaler is currently running.
     */
    public boolean isAlive() {
        return _isAlive;
    }

    /**
     * Get the current maximum runner limit (may be up to 2x configured or RAM-limited).
     */
    public int getCurrentMaxRunners() {
        return _currentMaxRunners;
    }

    /**
     * Update the maximum runner limit (called when configuration changes).
     */
    public void updateMaxRunners(int configuredMax) {
        _configuredMaxRunners = configuredMax;
        _currentMaxRunners = calculateMaxRunnersBasedOnRAM(configuredMax);
        if (_log.shouldInfo()) {
            _log.info("Updated max runners to: " + _currentMaxRunners);
        }
    }
}
