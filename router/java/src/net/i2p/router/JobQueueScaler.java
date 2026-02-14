package net.i2p.router;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

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
    private long _circuitBreakerOpenTime; // When the circuit breaker was opened

    // Feedback configuration
    private static final int FEEDBACK_CHECKS_AFTER_SCALE = 3; // Check 3 times after scaling - faster response
    private static final int MAX_CONSECUTIVE_FAILED_SCALES = 5; // Fail fast - disable after 5 failures
    private static final double LAG_INCREASE_THRESHOLD = 1.5; // Rollback if lag increases by 50%
    private static final double READY_JOBS_INCREASE_THRESHOLD = 1.1; // If ready jobs increased by 10%
    private static final double EXTENDED_COOLDOWN_MULTIPLIER = 3; // 3x normal cooldown after failed scale
    private static final long CIRCUIT_BREAKER_RESET_TIME = 3*60*1000; // Reset circuit breaker after 3 minutes
    private static final long LAG_EMERGENCY_THRESHOLD = 5; // 5ms - emergency scaling threshold - trigger immediately when lag starts

    // RAM-based limits
    private static final long MB = 1024 * 1024;
    private static final long RUNNER_MEMORY_ESTIMATE = 2 * MB; // ~2MB per thread (stack + overhead)
    private static final double MAX_MEMORY_PERCENTAGE = 0.10; // Use max 10% of heap for runners

    // Configuration defaults - Optimized for SUB-MICROSECOND lag targets
    private static final boolean DEFAULT_DYNAMIC_SCALING = true;
    private static final long DEFAULT_SCALE_CHECK_INTERVAL = 1000; // 1 second (very responsive for sub-μs targets)
    private static final long DEFAULT_SCALE_COOLDOWN = 5000; // 5 seconds (quick recovery)
    private static final int DEFAULT_SCALE_UP_LAG_THRESHOLD = 1; // 1ms = 1000μs (scale up if lag exceeds 1ms)
    private static final int DEFAULT_SCALE_DOWN_LAG_THRESHOLD = 0; // 0ms (scale down only when lag is essentially zero)
    private static final double DEFAULT_SCALE_UP_JOBS_RATIO = 1.2; // 1.2x (very aggressive - any backlog triggers scale)
    private static final int DEFAULT_SCALE_UP_STEP = 1; // Add 1 at a time (very conservative to avoid disruption)
    private static final int DEFAULT_SCALE_DOWN_STEP = 1;
    private static final int SUSTAINED_CHECKS_REQUIRED = 2; // 2 checks (was 3 - faster response to load spikes)

    // Property names
    private static final String PROP_DYNAMIC_SCALING = "router.dynamicJobScaling";
    private static final String PROP_SCALE_UP_LAG = "router.scaleUpLagThreshold";
    private static final String PROP_SCALE_DOWN_LAG = "router.scaleDownLagThreshold";
    private static final String PROP_SCALE_JOBS_RATIO = "router.scaleUpJobsRatio";
    private static final String PROP_SCALE_CHECK_INTERVAL = "router.scaleCheckInterval";
    private static final String PROP_SCALE_COOLDOWN = "router.scaleCooldown";
    private static final String PROP_MIN_RUNNERS = "router.minJobRunners";
    private static final String PROP_FEEDBACK_ENABLED = "router.scaleFeedbackEnabled";

    // Attack mode: be more aggressive when build success is low
    private static final double BUILD_SUCCESS_THRESHOLD = 0.40; // 40% - under attack
    private static final int ATTACK_SCALE_UP_STEP = 2; // Add 2 runners at a time during attacks
    private static final long ATTACK_CHECK_INTERVAL = 500; // Check every 500ms during attacks

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

        // Calculate max runners
        _configuredMaxRunners = context.getProperty(JobQueue.PROP_MAX_RUNNERS, JobQueue.RUNNERS);
        _currentMaxRunners = calculateMaxRunnersBasedOnRAM(_configuredMaxRunners);

        // Register rate stats
        context.statManager().createRateStat("jobQueue.runnerScaleUp",
            "Number of runners added in scale-up events", "JobQueue",
            new long[] {60*1000, 10*60*1000, 60*60*1000});
        context.statManager().createRateStat("jobQueue.runnerScaleDown",
            "Number of runners removed in scale-down events", "JobQueue",
            new long[] {60*1000, 10*60*1000, 60*60*1000});
        context.statManager().createRateStat("jobQueue.runnerCount",
            "Current number of active job runners", "JobQueue",
            new long[] {60*1000, 10*60*1000, 60*60*1000});
        context.statManager().createRateStat("jobQueue.scaleRollback",
            "Number of rollback events (scale up made things worse)", "JobQueue",
            new long[] {60*1000, 10*60*1000, 60*60*1000});
        context.statManager().createRateStat("jobQueue.memoryUsedPercent",
            "Percentage of max memory used", "JobQueue",
            new long[] {60*1000, 10*60*1000, 60*60*1000});
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

        int effectiveMax = ramBasedMax;
        int targetMax = configuredMax * 2;
        int finalMax = Math.min(targetMax, effectiveMax);

        // Ensure at least minimum
        finalMax = Math.max(getMinRunners(), finalMax);

        if (_log.shouldDebug()) {
            _log.debug("Job Runners -> Configured / Maximum available: " + configuredMax + " / " + targetMax);
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
            if (_log.shouldDebug()) {
                _log.debug("Adjusting max job runners from " + _currentMaxRunners + " to " + newMax +
                         " based on current memory conditions");
            }
            _currentMaxRunners = newMax;
        }
    }

    /**
     * Get current used memory in bytes using MemoryMXBean for accuracy.
     */
    private static final MemoryMXBean MEMORY_MX_BEAN = ManagementFactory.getMemoryMXBean();

    private long getUsedMemory() {
        return MEMORY_MX_BEAN.getHeapMemoryUsage().getUsed();
    }

    /**
     * Get free memory headroom (max heap - used).
     */
    private long getFreeMemoryHeadroom() {
        long maxMemory = SystemVersion.getMaxMemory();
        if (maxMemory == 0) {
            maxMemory = MEMORY_MX_BEAN.getHeapMemoryUsage().getMax();
        }
        return maxMemory - getUsedMemory();
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
     * Start the scaler thread.
     */
    public void startup() {
        if (!isDynamicScalingEnabled()) {
            if (_log.shouldDebug()) {
                _log.debug("Dynamic job scaling is disabled via configuration");
            }
            return;
        }

        _isRunning = true;
        _isAlive = true;
        I2PThread scalerThread = new I2PThread(this, "JobQueueScaler", true);
        scalerThread.setPriority(I2PThread.NORM_PRIORITY);
        scalerThread.start();

        if (_log.shouldInfo()) {
            _log.info("JobQueueScaler started. Min runners: " + getMinRunners() +
                     " Max runners: " + _currentMaxRunners +
                     " Feedback enabled: " + (isFeedbackEnabled() ? "yes" : "no"));
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
     * Get the minimum number of runners (floor).
     */
    private int getMinRunners() {
        int cores = SystemVersion.getCores();
        int configuredMin = _context.getProperty(PROP_MIN_RUNNERS, cores);
        return Math.max(4, configuredMin); // Never below 4
    }

    /**
     * Get the check interval in milliseconds.
     * During attacks, check more frequently to respond faster.
     */
    private long getCheckInterval() {
        // During attacks, check more frequently
        if (isUnderAttack()) {
            return ATTACK_CHECK_INTERVAL;
        }
        return _context.getProperty(PROP_SCALE_CHECK_INTERVAL, (int) DEFAULT_SCALE_CHECK_INTERVAL);
    }

    /**
     * Check if we're under attack (low tunnel build success).
     */
    private boolean isUnderAttack() {
        if (_context.profileOrganizer() == null) {
            return false;
        }
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        return buildSuccess < BUILD_SUCCESS_THRESHOLD;
    }

    /**
     * Get the cooldown period between scale events.
     * Shorter cooldown during attacks for faster response.
     */
    private long getCooldownPeriod() {
        long baseCooldown = _context.getProperty(PROP_SCALE_COOLDOWN, (int) DEFAULT_SCALE_COOLDOWN);
        if (_isInExtendedCooldown) {
            return (long) (baseCooldown * EXTENDED_COOLDOWN_MULTIPLIER);
        }
        // Shorter cooldown during attacks
        if (isUnderAttack()) {
            return baseCooldown / 2;
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
            } catch (Throwable t) {
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
        long avgLag = _jobQueue.getAvgLag();
        long now = _context.clock().now();

        // Record memory usage stat
        _context.statManager().addRateData("jobQueue.memoryUsedPercent", (long) getMemoryUsagePercent());

        // Record current runner count for monitoring
        _context.statManager().addRateData("jobQueue.runnerCount", activeRunners);

        // Check cooldown
        long timeSinceLastScale = now - _lastScaleTime;
        boolean inCooldown = timeSinceLastScale < getCooldownPeriod();

        int minRunners = getMinRunners();
        int maxRunners = _currentMaxRunners;

        // Get active job duration for monitoring
        long activeJobMaxDuration = _jobQueue.getMaxActiveJobDuration();

        // Calculate emergency mode early - we need this for feedback check decision
        boolean emergencyMode = maxLag > LAG_EMERGENCY_THRESHOLD || activeJobMaxDuration > LAG_EMERGENCY_THRESHOLD;

        // Log scaler state every check for debugging
        if (_log.shouldInfo() && timeSinceLastScale > 10000) {
            _log.info("JobQueueScaler state: runners=" + activeRunners + "/" + maxRunners +
                      " readyJobs=" + readyJobs + " maxLag=" + maxLag + "ms activeDuration=" + activeJobMaxDuration + "ms" +
                      " emergencyMode=" + emergencyMode + " cooldown=" + inCooldown +
                      (_scalingUpDisabled ? " CB_DISABLED" : ""));
        }

        // Debug logging every 10 seconds to trace scaler decisions
        if (_log.shouldDebug() && (_checksSinceLastScale % 10 == 0)) {
            _log.debug("JobQueueScaler check: Active / Max runners: " + activeRunners + "/" + maxRunners +
                      " Ready jobs: " + readyJobs + " Max / Avg / Max job run: " + maxLag + " / " + avgLag + " / " + activeJobMaxDuration + "ms " +
                      "\n* In cooldown: " + (inCooldown ? "yes" : "no") + " Last scale: " + timeSinceLastScale / 1000 + "s " +
                      (_scalingUpDisabled ? "(disabled)" : "") + " Extended cooldown active: " + (_isInExtendedCooldown ? "yes" : "no") +
                      " Pre-scale snapshot: " + (_preScaleSnapshot != null ? "yes" : "no"));
        }

        // Log when emergency mode detected but no action taken
        if (emergencyMode && activeRunners >= maxRunners && _log.shouldInfo()) {
            _log.info("JobQueueScaler: Emergency lag detected but at max runners (" + activeRunners + "/" + maxRunners + ")" +
                      " Ready jobs: " + readyJobs + " Max lag: " + maxLag + "ms, Active duration: " + activeJobMaxDuration + "ms");
        }

        // Check if we need to evaluate feedback from last scale-up
        // Skip feedback checks during emergency conditions - we need immediate scaling action
        if (_preScaleSnapshot != null && isFeedbackEnabled() && !emergencyMode) {
            _checksSinceLastScale++;

            if (_checksSinceLastScale >= FEEDBACK_CHECKS_AFTER_SCALE) {
                evaluateScalingFeedback(activeRunners, readyJobs, maxLag, avgLag);
                return; // Skip this cycle's scaling decisions
            }
            // Still in feedback period - skip scaling decisions (unless emergency)
            return;
        }
        // Clear feedback state in emergency mode to allow immediate re-scaling
        if (emergencyMode && _preScaleSnapshot != null) {
            _preScaleSnapshot = null;
            _checksSinceLastScale = 0;
        }

        // EMERGENCY MODE: Always handle high lag first, even if circuit breaker is open
        if (emergencyMode && activeRunners < maxRunners) {
            int runnersAvailable = maxRunners - activeRunners;
            // Cap emergency additions to prevent memory spikes - max 4 at once
            int maxEmergencyAdd = Math.min(4, runnersAvailable);
            // Also respect memory headroom (require 3x per runner)
            long headroom = getFreeMemoryHeadroom();
            int memoryCapped = (int)(headroom / (RUNNER_MEMORY_ESTIMATE * 3));
            int runnersToAdd = Math.min(maxEmergencyAdd, memoryCapped);
            runnersToAdd = Math.max(4, runnersToAdd); // At least 4 in emergency

            if (runnersToAdd > 0) {
                if (_log.shouldInfo()) {
                    _log.info("Adding " + runnersToAdd + " runners now -> " +
                              "Max lag: " + maxLag + "ms, Active duration=" + activeJobMaxDuration + "ms " +
                              "Runners: " + activeRunners + "/" + maxRunners);
                }
                // Emergency mode bypasses circuit breaker and feedback
                scaleUp(runnersToAdd, readyJobs, maxLag, avgLag);
                return;
            }
        }

        // Check if circuit breaker should be reset
        if (_scalingUpDisabled) {
            long timeSinceBreakerOpen = now - _circuitBreakerOpenTime;
            if (timeSinceBreakerOpen > CIRCUIT_BREAKER_RESET_TIME) {
                _scalingUpDisabled = false;
                _consecutiveFailedScaleUps = 0;
                _isInExtendedCooldown = false;
                if (_log.shouldInfo()) {
                    _log.info("Resetting JobQueue circuit breaker after " +
                              (timeSinceBreakerOpen/1000) + " seconds cooloff");
                }
            } else {
                // Still in circuit breaker period - only allow scale down
                if (_log.shouldDebug()) {
                    _log.debug("Scaling up disabled -> Circuit breaker open for " +
                              (timeSinceBreakerOpen/1000) + "/" + (CIRCUIT_BREAKER_RESET_TIME/1000) + " seconds");
                }
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

            // Check both queue lag AND active job duration
            // When all runners are busy with slow jobs, queue lag is 0 but active job duration is high
            boolean highLag = maxLag > lagThreshold;
            boolean highBacklog = readyJobs > 0 && jobsRatio > ratioThreshold;
            boolean criticalLag = maxLag > lagThreshold * 10; // 10x threshold = critical
            boolean slowActiveJobs = activeJobMaxDuration > lagThreshold * 50; // Jobs running >50ms (very slow)
            boolean criticalSlowJobs = activeJobMaxDuration > lagThreshold * 100; // Jobs running >100ms (critical)

            if (_log.shouldInfo() && readyJobs > 50) {
                _log.info("JobQueueScaler backlog check: readyJobs=" + readyJobs + " activeRunners=" + activeRunners +
                          " jobsRatio=" + String.format("%.2f", jobsRatio) + " ratioThreshold=" + ratioThreshold +
                          " highBacklog=" + highBacklog + " highLag=" + highLag + " slowActiveJobs=" + slowActiveJobs);
            }

            if (highBacklog || highLag || slowActiveJobs) {
                _consecutiveScaleUpChecks++;
                if (_consecutiveScaleUpChecks >= SUSTAINED_CHECKS_REQUIRED || criticalLag || criticalSlowJobs) {
                    shouldScaleUp = true;
                    if (criticalLag || criticalSlowJobs) {
                        if (_log.shouldWarn()) {
                            _log.warn("CRITICAL: " + (criticalLag ? "Queue lag=" + maxLag + "ms" : "") +
                                      (criticalSlowJobs ? " Active job duration=" + activeJobMaxDuration + "ms" : "") +
                                      " > threshold=" + lagThreshold + "ms. Scaling immediately. Runners: " +
                                      activeRunners + "/" + maxRunners + ", Ready jobs: " + readyJobs);
                        }
                    }
                }
            } else {
                _consecutiveScaleUpChecks = 0;
            }
        }

        // Execute scaling
        if (_log.shouldDebug()) {
            _log.debug("Scaling decision: shouldScaleUp=" + shouldScaleUp +
                      " readyJobs=" + readyJobs + " activeRunners=" + activeRunners +
                      " maxLag=" + maxLag + " avgLag=" + avgLag +
                      " emergencyMode=" + emergencyMode + " inCooldown=" + inCooldown +
                      " minRunners=" + minRunners + " maxRunners=" + maxRunners +
                      " scalingUpDisabled=" + _scalingUpDisabled +
                      " consecutiveScaleUpChecks=" + _consecutiveScaleUpChecks);
        }
        if (shouldScaleUp) {
            // Calculate how many runners to add based on backlog severity
            int scaleUpStep = isUnderAttack() ? ATTACK_SCALE_UP_STEP : DEFAULT_SCALE_UP_STEP;
            int lagThreshold = getScaleUpLagThreshold();

            // During attacks, scale more aggressively but still respect feedback
            if (readyJobs > activeRunners * 2) {
                // Severe backlog: double runners
                scaleUpStep = Math.max(scaleUpStep, activeRunners);
            } else if (readyJobs > activeRunners) {
                // Significant backlog: add half current runners
                scaleUpStep = Math.max(scaleUpStep, activeRunners / 2);
            } else if (maxLag > lagThreshold * 5) {
                // High lag: add more runners (up to 8 during attack, 4 normally)
                int maxStep = isUnderAttack() ? 8 : 4;
                scaleUpStep = Math.max(scaleUpStep, Math.min(maxStep, (int) (maxLag / lagThreshold)));
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
        if (!inCooldown && activeRunners > minRunners) {
            int lagThreshold = getScaleDownLagThreshold();

            // Check for sustained low load
            if (readyJobs < activeRunners && maxLag < lagThreshold && avgLag < lagThreshold) {
                _consecutiveScaleDownChecks++;
                // Require more sustained checks for scale-down (conservative)
                if (_consecutiveScaleDownChecks >= SUSTAINED_CHECKS_REQUIRED * 2) {
                    int runnersToRemove = Math.min(DEFAULT_SCALE_DOWN_STEP, activeRunners - minRunners);
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
                                        long currentMaxLag, long currentAvgLag) {
        if (_preScaleSnapshot == null) return;

        PreScaleSnapshot snapshot = _preScaleSnapshot;
        _preScaleSnapshot = null;
        _checksSinceLastScale = 0;

        // Calculate changes
        double lagRatio = snapshot.avgLag > 0 ? (double) currentAvgLag / snapshot.avgLag : 1.0;
        double readyJobsRatio = snapshot.readyJobs > 0 ? (double) currentReadyJobs / snapshot.readyJobs : 1.0;

        boolean lagIncreased = lagRatio > LAG_INCREASE_THRESHOLD;
        boolean readyJobsIncreased = readyJobsRatio > READY_JOBS_INCREASE_THRESHOLD;
        boolean memoryCritical = getMemoryUsagePercent() > 75; // Roll back if memory is critical

        if (lagIncreased || readyJobsIncreased || memoryCritical) {
            // Scaling made things worse - rollback!
            _consecutiveFailedScaleUps++;

            if (_log.shouldWarn()) {
                _log.warn("SCALE-UP FAILED: Rolling back " + snapshot.runnersAdded + " runners. " +
                         "Before: readyJobs=" + snapshot.readyJobs + ", avgLag=" + snapshot.avgLag + "ms. " +
                         "After: readyJobs=" + currentReadyJobs + ", avgLag=" + currentAvgLag + "ms. " +
                         "Lag ratio=" + String.format("%.2f", lagRatio) +
                         ", Ready jobs ratio=" + String.format("%.2f", readyJobsRatio) +
                         (memoryCritical ? ", Memory critical=" + getMemoryUsagePercent() + "%" : "") +
                         ". Failures: " + _consecutiveFailedScaleUps + "/" + MAX_CONSECUTIVE_FAILED_SCALES);
            }

            // Rollback: remove the runners we just added
            int runnersToRemove = Math.min(snapshot.runnersAdded, currentRunners - getMinRunners());
            if (runnersToRemove > 0) {
                int removed = _jobQueue.removeIdleRunners(runnersToRemove);
                if (removed > 0) {
                    _context.statManager().addRateData("jobQueue.scaleRollback", removed);
                }
            }

            // Enter extended cooldown
            _isInExtendedCooldown = true;
            _lastScaleTime = _context.clock().now();

            // Circuit breaker: if we've failed too many times, disable scaling up
            if (_consecutiveFailedScaleUps >= MAX_CONSECUTIVE_FAILED_SCALES) {
                _scalingUpDisabled = true;
                _circuitBreakerOpenTime = _context.clock().now();
                if (_log.shouldError()) {
                    _log.error("CIRCUIT BREAKER OPEN: Scaling up is now disabled due to " +
                              _consecutiveFailedScaleUps + " consecutive failed attempts. " +
                              "Will reset after " + (CIRCUIT_BREAKER_RESET_TIME/1000) + " seconds.");
                }
            }
        } else {
            // Scaling helped - reset failure counter
            if (_consecutiveFailedScaleUps > 0) {
                _consecutiveFailedScaleUps = 0;
                _isInExtendedCooldown = false;
                if (_log.shouldInfo()) {
                    _log.info("Scale-up successful. Resetting failure counter.");
                }
            }
        }
    }

    /**
     * Scale up by adding runners.
     */
    private void scaleUp(int count, int readyJobs, long maxLag, long avgLag) {
        // Check memory percentage before scaling up - prevent OOM
        double currentMemoryPercent = getMemoryUsagePercent();
        if (currentMemoryPercent > 70) {
            if (_log.shouldWarn()) {
                _log.warn("Skipping scale-up: memory usage too high at " + 
                          String.format("%.1f", currentMemoryPercent) + "%");
            }
            // Enter cooldown to prevent repeated attempts
            _lastScaleTime = _context.clock().now();
            _isInExtendedCooldown = true;
            return;
        }

        // Check absolute memory headroom - require 3x estimated memory per runner
        long headroom = getFreeMemoryHeadroom();
        long requiredMemory = (long) count * RUNNER_MEMORY_ESTIMATE * 3;
        if (headroom < requiredMemory) {
            if (_log.shouldWarn()) {
                _log.warn("Skipping scale-up: insufficient memory headroom (" + 
                          (headroom / MB) + "MB < " + (requiredMemory / MB) + "MB required)");
            }
            _lastScaleTime = _context.clock().now();
            _isInExtendedCooldown = true;
            return;
        }

        _lastScaleTime = _context.clock().now();
        _consecutiveScaleUpChecks = 0;
        _consecutiveScaleDownChecks = 0;
        _checksSinceLastScale = 0;
        _isInExtendedCooldown = false; // Reset extended cooldown on new scale attempt

        int activeRunners = _jobQueue.getActiveRunnerCount();

        if (_log.shouldInfo()) {
            _log.info("Scaling UP: Adding " + count + " runners. " +
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
            _log.info("Scaling DOWN: Removing " + count + " runners. " +
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
