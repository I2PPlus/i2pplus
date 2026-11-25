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
 * <li>A scale-up then needs SUSTAINED_CHECKS_REQUIRED checks in a row that want
 * it, unless the lag is at the critical multiple, an active job is wedged, or
 * jobs are being dropped — a trend signal starts the count, it does not skip
 * it</li>
 * <li>The queue-growth trend counts only once the backlog is approaching the
 * runner pool, so ordinary jitter in a shallow queue is not a trend</li>
 * <li>Scale DOWN when: readyJobs &lt; activeRunners AND avgLag &lt;
 * scaleDownLagThreshold, sustained over the scale-down check count</li>
 * <li>A scale-down must also wait out a dwell after a scale-up, and may never
 * remove more than that scale-up added, so one quiet interval cannot undo it</li>
 * <li>Bounds: min runners = max(4, cores) plus any held under lease, max runners
 * = min(2 * {@code router.maxJobRunners}, RAM-based limit, 2 per core), and the
 * configured value is re-read every tenth check rather than once at startup</li>
 * </ul>
 * <p>
 * <strong>Resource Saturation Gates:</strong>
 * Scale-up is suppressed (not just limited) when the bottleneck is resource
 * saturation rather than thread count. Emergency signals (critical lag, wedged
 * jobs, a severe backlog, dropped jobs) override the suppression, but only when
 * the machine itself has room — a queue that is behind because the CPU is full
 * or the bandwidth limiter is holding its jobs is not answered by another
 * thread.
 * <ul>
 * <li>CPU saturation: {@code router.cpuLoad} &gt; 85%</li>
 * <li>GC storm: {@code router.gcPauseTime} &gt; 50 ms/min</li>
 * <li>Network congestion: {@code tunnel.buildSuccessRate} &lt; 50% — suppresses
 * an ordinary scale-up only. Congestion is what the queue's own exploratory and
 * LeaseSet jobs relieve, so an emergency still acts on it</li>
 * <li>Bandwidth saturation: bwLimiter.outboundDelayedTime &gt; 500ms</li>
 * <li>No saturation signal enlarges the step a scale-up adds: if threads are
 * not the bottleneck, adding more of them because of that bottleneck is
 * contradictory, so the step is sized by the backlog alone</li>
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
 * <li>Jobs dropped after the scale event force the rollback. Drops from before
 * it are the reason the scale was made, so the verdict compares the stat's event
 * count against the count captured before the scale, not the trigger's window</li>
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
    /**
     * Runner limit from the config, re-read every tenth check so a change to
     * {@code router.maxJobRunners} takes effect without a restart.
     */
    private volatile int _configuredMaxRunners;
    /**
     * Runner limit in effect right now. Written by the scaler thread and read by
     * {@link JobQueue#getMaxRunnerCount()} from whatever thread asks, so it is
     * volatile.
     */
    private volatile int _currentMaxRunners;

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

    /**
     * Multiple of the router's own average lag at which a load episode starts.
     * Floored at the scale-up lag threshold, so a router that normally runs at
     * under a millisecond of lag does not take a few milliseconds of jitter for
     * an overload.
     */
    private static final int LOAD_EPISODE_LAG_MULTIPLIER = 2;
    /**
     * Multiple of the 12s median send delay at which a load episode starts,
     * floored at the send delay that justifies scaling up.
     */
    private static final double LOAD_EPISODE_MSG_DELAY_MULTIPLIER = 1.25;

    /** One megabyte, for the memory limits. */
    private static final long MB = 1024 * 1024L;
    /** A runner thread is estimated at 2 MB of heap. */
    private static final long RUNNER_MEMORY_ESTIMATE = 2 * MB; // ~2MB per thread (stack + overhead)
    /** Share of the heap allowed for runner threads. */
    private static final double MAX_MEMORY_PERCENTAGE = 0.10; // Use max 10% of heap for runners
    /**
     * Share of the heap still free that runners may claim. Half, so a scale-up
     * cannot take the heap to the edge on the strength of a reading taken before
     * the last allocation burst.
     */
    private static final double FREE_MEMORY_SHARE = 0.5;
    /**
     * Runners the CPU ceiling allows on a machine with few cores. Two per core
     * is roughly where context switching starts costing more than the extra
     * thread earns; the floor keeps a small machine at a workable count and
     * matches the core cap in {@link JobQueue#getMaxRunnerCount()}, so the two
     * cannot disagree about how many runners may exist.
     */
    private static final int MIN_CPU_BASED_RUNNERS = 16;

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
    /**
     * Multiple of that ratio at which the backlog is severe rather than merely
     * high: the queue is not jittering, it is behind, and the scale-up acts on
     * the first check that sees it instead of waiting out the sustained count.
     */
    private static final int SEVERE_BACKLOG_MULTIPLIER = 2;
    /** Runners added per scale-up. */
    private static final int DEFAULT_SCALE_UP_STEP = 1;
    /**
     * Most runners one scale-up pass may add, however many backlog signals
     * fired. Each scale event creates OS threads and the feedback check can
     * only roll the pass back after it has run, so the cap keeps one bad check
     * from doubling the pool.
     */
    private static final int MAX_SCALE_UP_STEP = 8;
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
     * Average outbound bandwidth delay, in ms, above which the queue is
     * bandwidth-bound and scale-up is skipped: the jobs are waiting on the
     * limiter, not on a runner.
     */
    private static final long BANDWIDTH_DELAY_THRESHOLD = 500;

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
         * Dropped-job events counted when the scale event was taken. The stat's
         * event count only ever rises, so the difference against it is the jobs
         * dropped after the scale — the ones that say something about it.
         */
        final long droppedEvents;

        /**
         * Captures the queue values a scale-up verdict is judged against.
         *
         * @param avgLag the average job lag at the capture, in milliseconds
         * @param runnersAdded the runners the scale event adds
         * @param messageDelay the transport send delay at the capture, in milliseconds
         * @param smoothReadyJobs the 1-minute average ready jobs at the capture
         * @param smoothMaxLag the 1-minute average worst job lag, in milliseconds
         * @param droppedEvents the dropped-job event count at the capture
         */
        PreScaleSnapshot(long avgLag, int runnersAdded, long messageDelay,
                        long smoothReadyJobs, long smoothMaxLag, long droppedEvents) {
            this.avgLag = avgLag;
            this.runnersAdded = runnersAdded;
            this.messageDelay = messageDelay;
            this.smoothReadyJobs = smoothReadyJobs;
            this.smoothMaxLag = smoothMaxLag;
            this.droppedEvents = droppedEvents;
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
     * The runner ceiling for the heap and CPU this router has right now. Logged
     * by {@link #recalculateMaxRunners()} only when it changes: this runs every
     * tenth check, and logging it every time put an INFO line in the log every
     * 20 seconds for the life of the router.
     *
     * @param configuredMax the configured runner count from the properties
     * @return the ceiling, never below the minimum runner count
     */
    private int calculateMaxRunnersBasedOnRAM(int configuredMax) {
        long maxMemory = SystemVersion.getMaxMemory();
        return runnerCeiling(configuredMax, maxMemory, maxMemory - getUsedMemory(),
                             SystemVersion.getCores(), getMinRunnersDynamic());
    }

    /**
     * Pure decision: the most runners this router may have. Four ceilings, and
     * the lowest of them wins — twice what the operator configured, the share of
     * the heap a runner thread may take, half the heap still free, and twice the
     * CPU cores, past which the context switches cost more than the threads
     * earn. The floor is the minimum runner count: a router that cannot afford
     * its own minimum still gets it, because running with fewer is not a choice
     * the scaler can make.
     *
     * @param configuredMax the configured runner count, doubled for the ceiling
     * @param maxMemory the heap ceiling in bytes
     * @param freeMemory bytes of that heap not in use right now
     * @param cores CPU cores available
     * @param minRunners the floor the ceiling may not fall below
     * @return the most runners this router may have
     * @since 0.9.72+
     */
    static int runnerCeiling(int configuredMax, long maxMemory, long freeMemory, int cores,
                             int minRunners) {
        int ramBasedMax = (int) ((maxMemory * MAX_MEMORY_PERCENTAGE) / RUNNER_MEMORY_ESTIMATE);
        int freeBasedMax = (int) ((freeMemory * FREE_MEMORY_SHARE) / RUNNER_MEMORY_ESTIMATE);
        int cpuBasedMax = Math.max(cores * 2, MIN_CPU_BASED_RUNNERS);
        int ceiling = Math.min(configuredMax * 2, Math.min(Math.min(ramBasedMax, freeBasedMax), cpuBasedMax));
        return Math.max(minRunners, ceiling);
    }

    /**
     * Recalculate the runner ceiling from the current configuration and memory
     * conditions. Run every tenth check, so a change to
     * {@code router.maxJobRunners} takes effect within 20 seconds of being
     * written — the configured value used to be read once in the constructor and
     * never again, and the setter that claimed to handle configuration changes
     * had no callers.
     */
    private void recalculateMaxRunners() {
        _configuredMaxRunners = _context.getProperty(JobQueue.PROP_MAX_RUNNERS, JobQueue.runners);
        int newMax = calculateMaxRunnersBasedOnRAM(_configuredMaxRunners);
        if (newMax != _currentMaxRunners) {
            if (_log.shouldInfo()) {
                _log.info("Adjusting the runner ceiling -> Was: " + _currentMaxRunners +
                          " * Now: " + newMax + " * Configured runners: " + _configuredMaxRunners);
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
            _log.info("JobQueueScaler started -> Min runners: " + getMinRunnersDynamic() +
                      " * Max runners: " + _currentMaxRunners +
                      " * Feedback enabled: " + isFeedbackEnabled());
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
     * Check interval in milliseconds, floored at 100 ms. A 0 or negative
     * interval would spin the scaler thread against a queue it cannot drain any
     * faster by looking at it more often.
     *
     * @return the check interval, at least 100
     */
    private long getCheckInterval() {
        return Math.max(100, _context.getProperty(PROP_SCALE_CHECK_INTERVAL, (int) DEFAULT_SCALE_CHECK_INTERVAL));
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
     * Lag threshold for scaling up, floored at 1 ms. Every lag trigger is a
     * multiple of this value, so a 0 in the config puts them all at zero and
     * any non-zero lag, and any job that ran at all, reads as an overload.
     *
     * @return the scale up lag threshold, at least 1
     */
    private int getScaleUpLagThreshold() {
        return Math.max(1, _context.getProperty(PROP_SCALE_UP_LAG, DEFAULT_SCALE_UP_LAG_THRESHOLD));
    }

    /**
     * Message delay threshold for scaling up, floored at 1 ms for the same
     * reason as the lag threshold: the critical-delay trigger is ten times it.
     *
     * @return the scale up message delay threshold, at least 1
     */
    private int getScaleUpMessageDelayThreshold() {
        return Math.max(1, _context.getProperty(PROP_SCALE_UP_MSG_DELAY, DEFAULT_SCALE_UP_MESSAGE_DELAY_THRESHOLD));
    }

    /**
     * Lag threshold for scaling down, floored at 1 ms. A 0 asks for an average
     * lag below zero, which no check has, and the pool would never come back
     * down from a burst.
     *
     * @return the scale down lag threshold, at least 1
     */
    private int getScaleDownLagThreshold() {
        return Math.max(1, _context.getProperty(PROP_SCALE_DOWN_LAG, DEFAULT_SCALE_DOWN_LAG_THRESHOLD));
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
        long avgLag = oneMinuteAverage("jobQueue.jobLag");
        long now = _context.clock().now();

        // The lag and send-delay levels every trigger below compares against,
        // read once for the whole check.
        int lagThreshold = getScaleUpLagThreshold();
        int msgDelayThreshold = getScaleUpMessageDelayThreshold();

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
        boolean bwSaturated = bwDelay > BANDWIDTH_DELAY_THRESHOLD;

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
        boolean rapidQueueGrowth = rapidQueueGrowth(queueGrowth, readyJobs, activeRunners);
        boolean messageDelaySpike = baselineMsgDelay > 5 && messageDelay > baselineMsgDelay * BASELINE_MULTIPLIER;
        boolean delayAccelerating = delayDelta > 5 && queueGrowth > 0;

        // Mean job duration trend: systemic slowdown (not just one stuck job)
        long avgJobDuration = 0;
        RateStat jobRunRs = _context.statManager().getRate("jobQueue.jobRun");
        if (jobRunRs != null) {
            Rate jobRunRate = jobRunRs.getRate(RateConstants.ONE_MINUTE);
            if (jobRunRate != null) avgJobDuration = (long) jobRunRate.getAverageValue();
        }
        boolean jobsSlowing = avgJobDuration > lagThreshold * 5; // 5x the lag threshold = systemic

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
        // An episode starts when either metric passes its limit and ends when
        // both fall back under it:
        //   maxLag &gt; 2x the 1-minute average of jobQueue.jobLag
        //   messageDelay &gt; 1.25x the 12s median of the transport send delay
        // Both limits adapt to the router's own operating level, but are floored
        // at the levels that justify scaling up. A router averaging 1ms of lag
        // and no send delay would otherwise take a 3ms jitter for an overload:
        // 207 such episodes, averaging 2.4s each, were recorded in 2.25 hours of
        // checks on a router whose worst ready-job lag averaged 0.38ms.
        long loadLagLimit = loadLagLimit(avgLag, lagThreshold);
        long loadDelayLimit = loadMessageDelayLimit(baselineMsgDelay, msgDelayThreshold);
        boolean loaded = maxLag > loadLagLimit || messageDelay > loadDelayLimit;
        if (loaded) {
            if (_loadEpisodeStart == 0) {
                _loadEpisodeStart = now;
                if (_log.shouldDebug()) {
                    _log.debug("Load episode started -> Max lag: " + DataHelper.formatDuration(maxLag) +
                               " (limit: " + DataHelper.formatDuration(loadLagLimit) + ")" +
                               "\n* Message delay: " + DataHelper.formatDuration(messageDelay) +
                               " (limit: " + DataHelper.formatDuration(loadDelayLimit) + ")");
                }
            }
        } else if (_loadEpisodeStart > 0) {
            long recoveryTime = now - _loadEpisodeStart;
            if (recoveryTime > 0) {
                _context.statManager().addRateData("jobQueue.loadRecoveryTime", recoveryTime);
                _lastRecoveryTime = recoveryTime;
                if (_log.shouldInfo()) {
                    _log.info("Load episode recovered -> Duration: " + DataHelper.formatDuration(recoveryTime) +
                              " * Max lag: " + DataHelper.formatDuration(maxLag) +
                              " * Message delay: " + DataHelper.formatDuration(messageDelay));
                }
            }
            _loadEpisodeStart = 0;
        }

        // Check cooldown
        long timeSinceLastScale = now - _lastScaleTime;
        boolean inCooldown = timeSinceLastScale < getCooldownPeriod();

        int maxRunners = _currentMaxRunners;
        // The floor, including any runners subsystems are holding under lease.
        // Leases expire on this pass, not on a timer event.
        int effectiveMin = getEffectiveMinRunners();

        // Get active job duration for monitoring
        long activeJobMaxDuration = _jobQueue.getMaxActiveJobDuration();

        // Debug logging every 10 seconds to trace scaler decisions
        if (_log.shouldDebug() && (_periodicCheckCount % 10 == 0)) {
            _log.debug("JobQueueScaler check -> Runners: " + activeRunners + " of " + maxRunners
                       + " * Ready jobs: " + readyJobs
                       + "\n* Max lag: " + maxLag + "ms * Average lag: " + avgLag + "ms"
                       + " * Message delay: " + messageDelay + "ms"
                       + "\n* Baseline delay: " + baselineMsgDelay + "ms * CPU load: " + cpuLoad + "%"
                       + " * GC pause: " + gcPause + "ms * Bandwidth delay: " + bwDelay + "ms"
                       + "\n* Queue growth: " + queueGrowth + " * Delay change: " + delayDelta
                       + " * Longest active job: " + activeJobMaxDuration + "ms"
                       + "\n* Average job duration: " + avgJobDuration + "ms"
                       + " * In cooldown: " + inCooldown
                       + " * Since last scale: " + timeSinceLastScale + "ms"
                       + "\n* Scaling disabled: " + _scalingUpDisabled
                       + " * Extended cooldown: " + _isInExtendedCooldown
                       + "\n* Snapshot pending: " + (_preScaleSnapshot != null)
                       + " * Dropped jobs: " + jobsBeingDropped);
        }

        // === Scale-up triggers, worked out before the feedback window ===
        // The verdict on the last scale-up holds every decision for
        // FEEDBACK_CHECKS_AFTER_SCALE checks, and a degraded verdict holds it for
        // two more: up to four checks, 8s at the default interval, in which a
        // queue that has gone critically behind cannot be answered. The triggers
        // are therefore read here, and a critical one opens the window.
        double ratioThreshold = getScaleUpJobsRatio();
        double jobsRatio = activeRunners > 0 ? (double) readyJobs / activeRunners : 0;
        boolean highLag = maxLag > lagThreshold;
        boolean highMessageDelay = messageDelay > msgDelayThreshold;
        boolean highAvgLag = avgLag >= lagThreshold;
        boolean highBacklog = readyJobs > 0 && jobsRatio > ratioThreshold;
        boolean criticalLag = maxLag > lagThreshold * 10 || messageDelay > msgDelayThreshold * 10;
        boolean slowActiveJobs = activeJobMaxDuration > lagThreshold * 50; // 50x the lag threshold
        boolean criticalSlowJobs = activeJobMaxDuration > lagThreshold * 100; // 100x = wedged
        boolean severeBacklog = severeBacklog(readyJobs, activeRunners, ratioThreshold);

        // TestJobs run 10-30s per invocation, occupying a runner slot without
        // contributing to "productive" job throughput. When queued TestJobs
        // exceed a threshold relative to active runners, we need headroom
        // so shorter tasks (searches, builds) aren't starved.
        // Use getReadyTestJobCount() to exclude future-scheduled timed jobs,
        // which don't consume runner resources.
        int testJobCount = _jobQueue.getReadyTestJobCount();
        boolean testJobsDominant = testJobCount > 2 && activeRunners > 2
            && (double) testJobCount / activeRunners > 0.25;

        boolean hasTrendSignal = rapidQueueGrowth || messageDelaySpike || delayAccelerating ||
                                 jobsBeingDropped || jobsSlowing || testJobsDominant;

        // Anything that says the queue is behind now, not trending that way:
        // these act on the first check and are not held by the feedback window,
        // the cooldown, or, for dropped jobs, the circuit breaker. A subsystem's
        // lease is an explicit demand and counts the same way.
        boolean floorUnmet = effectiveMin > activeRunners && activeRunners < maxRunners;
        boolean emergencyNow = emergencyActionable(criticalLag, criticalSlowJobs, severeBacklog,
                                                   jobsBeingDropped, cpuSaturated, gcStorm,
                                                   bwSaturated) || floorUnmet;

        // Check if we need to evaluate feedback from last scale-up
        if (_preScaleSnapshot != null) {
            if (!isFeedbackEnabled()) {
                // Turning feedback off mid-flight must not strand the snapshot. A
                // scale-down is gated on there being no snapshot pending, so an
                // orphaned one would block every scale-down for the rest of the run.
                _preScaleSnapshot = null;
                _checksSinceLastScale = 0;
                _degradedFeedbackChecks = 0;
            } else if (emergencyNow) {
                // A critical condition outranks the verdict on the last scale-up.
                // Its runners are already in the pool and have had their checks;
                // holding the queue behind while a judgement is completed costs
                // more than the judgement is worth. The snapshot is dropped, not
                // judged, so the scale-up neither rolls back nor trips the breaker.
                _preScaleSnapshot = null;
                _checksSinceLastScale = 0;
                _degradedFeedbackChecks = 0;
                if (_log.shouldInfo()) {
                    _log.info("Feedback window abandoned -> Queue is behind now" +
                              "\n* Max lag: " + DataHelper.formatDuration(maxLag) +
                              " * Ready jobs: " + readyJobs + " * Runners: " + activeRunners);
                }
            } else {
                _checksSinceLastScale++;

                if (_checksSinceLastScale >= FEEDBACK_CHECKS_AFTER_SCALE) {
                    evaluateScalingFeedback(activeRunners, avgLag, messageDelay,
                                            oneMinuteAverage("jobQueue.readyJobs"),
                                            oneMinuteAverage("jobQueue.maxLag"));
                }
                // Still in feedback period - skip scaling decisions
                return;
            }
        }

        // Don't scale up if circuit breaker is open — but jobs being dropped
        // is an emergency that overrides the breaker, and the cooldown with it.
        // Without this bypass, the breaker permanently freezes the runner count
        // while droppedJob pressure continues to climb.
        if (_scalingUpDisabled) {
            if (timeSinceLastScale > getCooldownPeriod()) {
                _scalingUpDisabled = false;
                _consecutiveFailedScaleUps = 0;
                _isInExtendedCooldown = false;
                if (_log.shouldInfo()) {
                    _log.info("Circuit breaker reset -> Cooldown expired, scale-up attempts allowed again");
                }
            } else if (!jobsBeingDropped) {
                checkScaleDown(activeRunners, readyJobs, maxLag, avgLag, effectiveMin, inCooldown,
                               testJobCount, testJobsDominant);
                return;
            } else if (_log.shouldInfo()) {
                _log.info("Circuit breaker bypassed -> Jobs being dropped" +
                          " * Runners: " + activeRunners + '/' + maxRunners);
            }
        }

        // Determine if we should scale up
        boolean shouldScaleUp = false;
        boolean canScaleUp = canScaleUp(inCooldown, activeRunners, maxRunners, _scalingUpDisabled,
                                       jobsBeingDropped);

        // Suppress normal scale-up when resource saturation is the bottleneck,
        // not thread count. Emergency signals override the suppression.
        boolean suppressNormal = cpuSaturated || gcStorm || networkCongested || bwSaturated;

        if (canScaleUp) {
            long effectiveDelay = Math.max(maxLag, messageDelay);

            if (emergencyNow || !suppressNormal) {
                if (highBacklog || highLag || highMessageDelay || highAvgLag || slowActiveJobs ||
                    rapidQueueGrowth || messageDelaySpike || delayAccelerating || jobsBeingDropped ||
                    jobsSlowing || testJobsDominant) {
                    _consecutiveScaleUpChecks++;
                    if (sustainedOrEmergency(_consecutiveScaleUpChecks, criticalLag, criticalSlowJobs,
                                             jobsBeingDropped, severeBacklog)) {
                        shouldScaleUp = true;
                        if (_log.shouldInfo()) {
                            // The continuation line carries the level each signal
                            // was measured against, then the signals that fired,
                            // joined with the house separator.
                            StringBuilder info = new StringBuilder(128);
                            info.append(" * Lag threshold: ").append(lagThreshold).append("ms");
                            if (criticalLag)
                                info.append(" * Critical delay: ").append(effectiveDelay).append("ms");
                            if (criticalSlowJobs)
                                info.append(" * Active job duration: ").append(activeJobMaxDuration).append("ms");
                            if (hasTrendSignal)
                                info.append(" * Queue growth: ").append(queueGrowth)
                                    .append(" * Delay delta: ").append(delayDelta);
                            if (jobsBeingDropped)
                                info.append(" * Jobs being dropped");
                            if (testJobsDominant)
                                info.append(" * Test jobs: ").append(testJobCount)
                                    .append(" (").append(testJobCount * 100 / Math.max(activeRunners, 1))
                                    .append("% of runners)");
                            if (suppressNormal && emergencyNow)
                                info.append(" * Emergency override * CPU: ").append(cpuLoad).append('%')
                                    .append(" * GC pause: ").append(gcPause).append("ms");
                            _log.info("Scaling up immediately -> Runners: " + activeRunners + '/' + maxRunners +
                                      " * Ready jobs: " + readyJobs + "\n*" + info.substring(3));
                        }
                    }
                } else {
                    _consecutiveScaleUpChecks = 0;
                }
            } else {
                // Suppressed by resource saturation — don't accumulate scale-up checks
                _consecutiveScaleUpChecks = 0;
                if (_log.shouldDebug()) {
                    _log.debug("Scale-up suppressed -> CPU load: " + cpuLoad + "%" +
                               " * GC pause: " + DataHelper.formatDuration(gcPause) +
                               "\n* Bandwidth delay: " + DataHelper.formatDuration(bwDelay) +
                               " * Network congested: " + networkCongested);
                }
            }
        }

        // External floor: a subsystem's standing request is an explicit demand,
        // not a signal read off the queue's jitter, so it does not wait out the
        // cooldown. The ceiling and an open circuit breaker still apply.
        if (!shouldScaleUp && floorUnmet && !_scalingUpDisabled) {
            shouldScaleUp = true;
        }

        // Execute scaling
        if (shouldScaleUp) {
            int step;
            if (effectiveMin > activeRunners) {
                // External floor: just reach the requested level
                step = Math.min(effectiveMin, maxRunners) - activeRunners;
            } else {
                step = scaleUpStep(maxLag, lagThreshold, rapidQueueGrowth, queueGrowth,
                                   jobsBeingDropped, testJobsDominant, testJobCount);
            }

            int targetRunners = Math.min(activeRunners + step, maxRunners);
            int runnersToAdd = targetRunners - activeRunners;

            if (runnersToAdd > 0) {
                scaleUp(runnersToAdd, readyJobs, maxLag, avgLag, messageDelay);
            }
        } else {
            checkScaleDown(activeRunners, readyJobs, maxLag, avgLag, effectiveMin, inCooldown,
                           testJobCount, testJobsDominant);
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
     * @param minRunners the floor the runner count may not be reduced below,
     *                   including any runners held under lease
     * @param inCooldown true when a scale action happened recently enough to
     *                   suppress another one
     * @param testJobCount ready TestJobs, counted once for the whole check
     * @param testJobsDominant true when those TestJobs hold enough of the
     *                         runners that a quiet lag sample means nothing
     */
    private void checkScaleDown(int activeRunners, int readyJobs, long maxLag, long avgLag,
                                int minRunners, boolean inCooldown, int testJobCount,
                                boolean testJobsDominant) {
        // Don't scale down when TestJobs dominate — they occupy runners for 10-30s,
        // so a low lag snapshot doesn't mean we have excess capacity.
        if (testJobsDominant) {
            if (_log.shouldDebug()) {
                _log.debug("Scale-down blocked -> Test jobs: " + testJobCount +
                          " * Active runners: " + activeRunners);
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
     * Pure decision: may this check add runners at all?
     * <p>
     * The cooldown and the circuit breaker both hold scale-ups back, and jobs
     * being dropped outranks both: the breaker exists because scale-ups were not
     * helping, and a queue that has begun shedding work is the one condition
     * where not trying is worse than trying. The bypass used to be only a log
     * line: the breaker, and the cooldown that the same branch implies, both
     * still blocked the scale-up, and the pass fell through to the scale-down
     * path while the queue was dropping jobs. The feedback check is what bounds
     * the churn this can cause — every scale-up arms a snapshot, and the checks
     * that follow judge it.
     *
     * @param inCooldown true when a scale event happened too recently for another
     * @param activeRunners runners in effect now
     * @param maxRunners the ceiling this queue may hold
     * @param scalingUpDisabled true while the circuit breaker is open
     * @param jobsBeingDropped true when the queue dropped jobs in the last minute
     * @return true when this check may add runners
     * @since 0.9.72+
     */
    static boolean canScaleUp(boolean inCooldown, int activeRunners, int maxRunners,
                              boolean scalingUpDisabled, boolean jobsBeingDropped) {
        if (activeRunners >= maxRunners) {return false;}
        boolean breakerOverride = scalingUpDisabled && jobsBeingDropped;
        return (breakerOverride || !scalingUpDisabled) && (jobsBeingDropped || !inCooldown);
    }

    /**
     * Pure decision: is the bottleneck the machine rather than the runner
     * count? Too little CPU left, a GC storm masquerading as lag, and a queue
     * held up by the bandwidth limiter are all conditions where a further
     * runner makes them worse, not better.
     * <p>
     * A low tunnel build success rate is deliberately not one of them. Transit
     * and bandwidth pressure is what the queue's own work relieves — the
     * exploratory, profile and LeaseSet jobs that find better paths and rebuild
     * the tunnels — so congestion is a reason to run those jobs sooner.
     *
     * @param cpuSaturated true when the CPU is past its saturation threshold
     * @param gcStorm true when GC pause time is past its threshold
     * @param bwSaturated true when the average outbound delay is past its
     *                    threshold, so jobs are waiting on the limiter
     * @return true when adding runners cannot help
     * @since 0.9.72+
     */
    static boolean localResourcesSaturated(boolean cpuSaturated, boolean gcStorm,
                                           boolean bwSaturated) {
        return cpuSaturated || gcStorm || bwSaturated;
    }

    /**
     * Pure decision: does this check report the queue behind now, rather than
     * trending that way, in a way more runners would answer?
     * <p>
     * These are the signals that act on the first check instead of waiting out
     * the sustained count, the feedback window, the cooldown or the circuit
     * breaker. They are only worth acting on while the bottleneck is the runner
     * count: a queue that is behind because the machine is out of CPU, or
     * because the bandwidth limiter is holding its jobs, is answered by neither
     * more threads nor a faster queue.
     *
     * @param criticalLag true when the worst lag or the send delay is ten times
     *                    its threshold
     * @param criticalSlowJobs true when an active job has run past the wedged
     *                         multiple of the lag threshold
     * @param severeBacklog true when the ready depth is past the severe ratio
     * @param jobsBeingDropped true when the queue dropped jobs in the last minute
     * @param cpuSaturated true when the CPU is past its saturation threshold
     * @param gcStorm true when GC pause time is past its threshold
     * @param bwSaturated true when the average outbound delay is past its
     *                    threshold
     * @return true when this check should answer at once
     * @since 0.9.72+
     */
    static boolean emergencyActionable(boolean criticalLag, boolean criticalSlowJobs,
                                       boolean severeBacklog, boolean jobsBeingDropped,
                                       boolean cpuSaturated, boolean gcStorm,
                                       boolean bwSaturated) {
        if (localResourcesSaturated(cpuSaturated, gcStorm, bwSaturated)) {return false;}
        return criticalLag || criticalSlowJobs || severeBacklog || jobsBeingDropped;
    }

    /**
     * Pure decision: how many runners one scale-up pass adds, from the backlog
     * signals that justified it. The oldest job's delay sets the size on its own
     * scale, and each other signal adds to it, all bounded by
     * {@link #MAX_SCALE_UP_STEP}.
     * <p>
     * Network congestion is deliberately not an input. Congestion already
     * suppresses a routine scale-up at the trigger, so the only pass that gets
     * here while the network is congested was forced by critical lag or dropped
     * jobs. Charging congestion for an extra runner at that point contradicts
     * the gate above it, which treats congestion as evidence that the bottleneck
     * is not thread count. One signal gets one response, and it is the gate's.
     *
     * @param maxLag ms the oldest ready job has waited
     * @param lagThreshold ms of lag that justifies a scale-up, the configured
     *                     threshold; 0 or less disables the delay-proportional
     *                     step rather than dividing by it
     * @param rapidQueueGrowth true when the ready queue grew faster than
     *                         {@link #QUEUE_GROWTH_RATE_THRESHOLD} jobs per check
     * @param queueGrowth jobs added to the ready queue since the last check
     * @param jobsBeingDropped true when the queue is dropping jobs outright
     * @param testJobsDominant true when queued TestJobs hold enough of the
     *                         runners that the shorter jobs are starved
     * @param testJobCount ready TestJobs in the queue
     * @return runners to add, at least 2 and at most {@link #MAX_SCALE_UP_STEP}
     * @since 0.9.72+
     */
    static int scaleUpStep(long maxLag, int lagThreshold, boolean rapidQueueGrowth, int queueGrowth,
                           boolean jobsBeingDropped, boolean testJobsDominant, int testJobCount) {
        int step = Math.max(DEFAULT_SCALE_UP_STEP, 2);
        if (maxLag > 1000) {
            step = Math.max(step, 4);
        } else if (maxLag > 500) {
            step = Math.max(step, 3);
        }
        if (lagThreshold > 0 && maxLag > lagThreshold * 5) {
            step = Math.min(6, (int) (maxLag / lagThreshold / 2));
        }
        if (rapidQueueGrowth) {
            step = Math.min(MAX_SCALE_UP_STEP, step + queueGrowth / QUEUE_GROWTH_RATE_THRESHOLD);
        }
        if (jobsBeingDropped) {
            step = Math.min(MAX_SCALE_UP_STEP, step + 2);
        }
        if (testJobsDominant) {
            step = Math.min(MAX_SCALE_UP_STEP, step + Math.max(1, testJobCount / 3));
        }
        return step;
    }

    /**
     * Pure decision: is the ready queue growing early enough, and deep enough,
     * to be worth runners before the backlog-ratio gate opens?
     * <p>
     * Growth on its own is noise on a shallow queue: measured on a running
     * router, the ready depth sits near four jobs and moves by three between
     * two checks with nobody waiting for any of them. The backlog therefore has
     * to be approaching the pool that serves it — half of it here, against the
     * ratio gate's double — before the growth means anything.
     *
     * @param queueGrowth jobs added to the ready queue since the last check
     * @param readyJobs jobs ready to run now
     * @param activeRunners runners in effect now
     * @return true when the growth is an early warning worth acting on
     * @since 0.9.72+
     */
    static boolean rapidQueueGrowth(int queueGrowth, int readyJobs, int activeRunners) {
        return queueGrowth > QUEUE_GROWTH_RATE_THRESHOLD && readyJobs * 2 > activeRunners;
    }

    /**
     * Pure decision: the worst ready-job lag that still counts as a healthy
     * queue. Twice the router's own 1-minute average, but never below twice the
     * lag that justifies scaling up. A router averaging 1ms of lag would
     * otherwise record an overload episode for a 3ms jitter, and 207 of those,
     * averaging 2.4s each, went into {@code jobQueue.loadRecoveryTime} in 2.25
     * hours of checks.
     *
     * @param avgLag ms 1-minute average lag of the ready jobs, 0 with no data
     * @param lagThreshold ms of lag that justifies scaling up, at least 1
     * @return ms of worst ready-job lag above which the queue counts as loaded
     * @since 0.9.72+
     */
    static long loadLagLimit(long avgLag, int lagThreshold) {
        return LOAD_EPISODE_LAG_MULTIPLIER * Math.max(avgLag, lagThreshold);
    }

    /**
     * Pure decision: the transport send delay that still counts as a healthy
     * queue. 1.25 times the 12s median of the delay, but never below 1.25 times
     * the delay that justifies scaling up: an unthrottled router reports no
     * send delay at all, so a baseline of nothing would put the limit at a
     * millisecond and call any send at all an overload.
     *
     * @param baselineMsgDelay ms median send delay over the last 12s, 0 when the
     *                         router is not throttling
     * @param msgDelayThreshold ms of send delay that justifies scaling up, at
     *                          least 1
     * @return ms of send delay above which the queue counts as loaded
     * @since 0.9.72+
     */
    static long loadMessageDelayLimit(long baselineMsgDelay, int msgDelayThreshold) {
        return (long) (LOAD_EPISODE_MSG_DELAY_MULTIPLIER * Math.max(baselineMsgDelay, msgDelayThreshold));
    }

    /**
     * Pure decision: is the backlog far past the depth the runner pool can
     * serve? Twice the ratio that opens the ordinary backlog trigger. At that
     * depth the queue is not jittering, it is behind, and waiting out the
     * sustained checks costs the whole queue the delay that the extra runners
     * would have removed two checks ago.
     *
     * @param readyJobs jobs ready to run now
     * @param activeRunners runners in effect now
     * @param ratioThreshold the job-to-runner ratio that opens the backlog
     *                       trigger
     * @return true when the backlog is severe enough to act on at once
     * @since 0.9.72+
     */
    static boolean severeBacklog(int readyJobs, int activeRunners, double ratioThreshold) {
        return activeRunners > 0 && readyJobs > activeRunners * ratioThreshold * SEVERE_BACKLOG_MULTIPLIER;
    }

    /**
     * Pure decision: has the backlog wanted runners for long enough to act on,
     * or is this check an emergency that cannot wait?
     * <p>
     * The sustained count used to be waived for any trend signal, and a trend
     * signal fires on ordinary jitter: 51 scale-ups and 63 scale-downs, 167
     * runner threads created, in 2.25 hours of checks on a router whose worst
     * ready-job lag averaged 0.38ms. Only a lag past the critical multiple, a
     * wedged active job, jobs being dropped, or a backlog past the severe ratio
     * is an emergency. A trend is a reason to start counting, not a reason to
     * skip the count.
     *
     * @param consecutiveChecks checks in a row that wanted a scale-up
     * @param criticalLag true when the worst lag or the send delay is ten times
     *                    its threshold
     * @param criticalSlowJobs true when an active job has run past the wedged
     *                         multiple of the lag threshold
     * @param jobsBeingDropped true when the queue dropped jobs in the last minute
     * @param severeBacklog true when the ready depth is past the severe ratio
     * @return true when this check may scale up
     * @since 0.9.72+
     */
    static boolean sustainedOrEmergency(int consecutiveChecks, boolean criticalLag,
                                        boolean criticalSlowJobs, boolean jobsBeingDropped,
                                        boolean severeBacklog) {
        return consecutiveChecks >= SUSTAINED_CHECKS_REQUIRED || criticalLag || criticalSlowJobs ||
               jobsBeingDropped || severeBacklog;
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
     * Pure decision: how many of the runners a failed scale-up added may be
     * taken back? No more than it added, and never past the floor — which
     * counts the runners other subsystems hold under lease. The rollback used to
     * floor itself at the configured minimum alone and so took back a runner a
     * subsystem was still counting on, which the floor path then handed straight
     * back on the next check: a pair of thread creations for nothing.
     *
     * @param runnersAdded runners the scale-up added
     * @param currentRunners runners in effect now
     * @param floor the runner count may not go below, leases included
     * @return runners to remove, 0 when there is nothing to take back
     * @since 0.9.72+
     */
    static int rollbackCount(int runnersAdded, int currentRunners, int floor) {
        return Math.max(0, Math.min(runnersAdded, currentRunners - floor));
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
     * @param currentRunners runner count now, which with the floor caps how many
     *                       added runners roll back
     * @param currentAvgLag ms average lag now, compared with the pre-scale average
     * @param currentMessageDelay ms transport send delay now, compared with the
     *                            send delay captured before the scale
     * @param smoothReadyJobs 1-minute average ready jobs now
     * @param smoothMaxLag ms 1-minute average worst ready-job lag now
     */
    private void evaluateScalingFeedback(int currentRunners, long currentAvgLag,
                                        long currentMessageDelay,
                                        long smoothReadyJobs, long smoothMaxLag) {
        if (_preScaleSnapshot == null) return;

        PreScaleSnapshot snapshot = _preScaleSnapshot;
        boolean memoryCritical = getMemoryUsagePercent() > MEMORY_CRITICAL_PERCENT;
        boolean droppedSince = droppedSinceScale(lifetimeEvents("jobQueue.droppedJobs"),
                                                 snapshot.droppedEvents);
        boolean degraded = feedbackDegraded(snapshot, currentAvgLag, currentMessageDelay,
                                            droppedSince, memoryCritical,
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
                _log.info("Scale-up failed -> Rolling back the runners it added" +
                          "\n* Runners to remove: " + snapshot.runnersAdded +
                          " * Consecutive failures: " + _consecutiveFailedScaleUps +
                          "\n* Average lag: " + snapshot.avgLag + "ms to " + currentAvgLag + "ms" +
                          " * Maximum lag: " + snapshot.smoothMaxLag + "ms to " + smoothMaxLag + "ms" +
                          "\n* Ready jobs: " + snapshot.smoothReadyJobs + " to " + smoothReadyJobs +
                          " * Send delay: " + snapshot.messageDelay + "ms to " + currentMessageDelay + "ms");
            }

            // Rollback: remove the runners we just added, but not past the floor
            // the queue is held to — leases included.
            int runnersToRemove = rollbackCount(snapshot.runnersAdded, currentRunners,
                                                getEffectiveMinRunners());
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
                    _log.info("Circuit breaker open -> Scaling up disabled"
                              + "\n* Failed attempts: " + _consecutiveFailedScaleUps
                              + " * Jobs may be CPU-bound * Retry in "
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
     * @param droppedSinceScale true when jobs have been dropped since the scale-up
     * @param memoryCritical true when the heap is near full
     * @param smoothReadyJobs 1-minute average ready jobs now
     * @param smoothMaxLag ms 1-minute average worst ready-job lag now
     * @return true when the scale-up looks like it did not help
     * @since 0.9.72+
     */
    static boolean feedbackDegraded(PreScaleSnapshot snapshot, long currentAvgLag,
                                    long currentMessageDelay, boolean droppedSinceScale,
                                    boolean memoryCritical, long smoothReadyJobs, long smoothMaxLag) {
        // dropped jobs and a full heap are emergencies, one sample is enough
        if (droppedSinceScale || memoryCritical) {return true;}
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
     * The number of events a rate has recorded since the process started. It
     * only ever rises, so a difference between two readings is the events that
     * happened between them — which is what a windowed rate cannot give, because
     * its window still holds the events from before the point being measured.
     *
     * @param name the rate stat's name
     * @return the event count, 0 when the stat is absent
     * @since 0.9.72+
     */
    private long lifetimeEvents(String name) {
        RateStat stat = _context.statManager().getRate(name);
        return stat != null ? stat.getLifetimeEventCount() : 0;
    }

    /**
     * Pure decision: has the queue dropped jobs since the scale-up being judged?
     * <p>
     * The trigger reads a 1-minute window, and the drops that justified the
     * scale-up are still inside it when the verdict is taken two checks later —
     * so reading the window again reported the reason for the scale as evidence
     * against it, and a drop storm rolled back every scale-up it caused and
     * opened the circuit breaker on top. The comparison is made on the stat's
     * event count instead, captured before the scale and read after it.
     *
     * @param eventsNow dropped-job events counted now
     * @param eventsBefore the count captured before the scale-up
     * @return true when jobs have been dropped since the scale-up
     * @since 0.9.72+
     */
    static boolean droppedSinceScale(long eventsNow, long eventsBefore) {
        return eventsNow > eventsBefore;
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
                                                    oneMinuteAverage("jobQueue.maxLag"),
                                                    lifetimeEvents("jobQueue.droppedJobs"));
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
            _log.debug("Runner request set -> Reason: " + reason + " * Requested runners: " + count
                       + " * Floor: " + _externalFloor.get() + " runners");
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
}
