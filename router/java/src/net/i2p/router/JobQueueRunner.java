package net.i2p.router;

import net.i2p.stat.RateStat;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Worker thread that executes jobs from the router job queue.
 * Handles job execution, timing collection, error handling,
 * and performance monitoring for individual job processing threads.
 */
class JobQueueRunner extends I2PThread {
    /** The log for the runner. */
    private final Log _log;
    /** The router context. */
    private final RouterContext _context;
    /** True while the runner should keep running. */
    private volatile boolean _keepRunning;
    /** The id of this runner. */
    private final int _id;
    /** The job running right now. Only this runner's own thread reads or writes it. */
    private volatile Job _currentJob;
    /** The job that ran last. */
    private volatile Job _lastJob;
    /**
     * The job being executed and the instant its run began, published as one
     * immutable value in a single volatile write. Every other thread reads the
     * in-flight job and its start time through this field, so it can never pair
     * a job with the start time of an earlier run. Publishing the job and the
     * timestamp in two separate writes let the watchdog's liveliness check read
     * a job that had just begun together with the timestamp left over from the
     * runner's previous job, and report a run of the whole idle gap that
     * preceded it. Null while this runner is idle, so an idle runner reports
     * nothing in flight at all.
     *
     * @since 0.9.72+
     */
    private volatile JobQueue.InFlight _inFlight;
    /** When the last job ended, stats update included. */
    private volatile long _lastEnd;

    /**
     * Cached "{@code jobQueue.jobRun}" handle, resolved on first use.
     * Null until resolved, and it stays null when {@code stat.full} is off (in which
     * case JobQueue's createRateStat() is a no-op). Resolution is idempotent,
     * so a benign race can only assign the same handle twice.
     *
     * @since 0.9.71+
     */
    private volatile RateStat _jobRunStat;
    /** Cached "{@code jobQueue.jobLag}" handle, resolved on first use. @since 0.9.71+. */
    private volatile RateStat _jobLagStat;
    /** Cached "{@code jobQueue.jobWait}" handle, resolved on first use. @since 0.9.71+. */
    private volatile RateStat _jobWaitStat;

    /**
     * Create a new job queue runner.
     *
     * @param context the router context
     * @param id the runner id
     */
    public JobQueueRunner(RouterContext context, int id) {
        _context = context;
        _id = id;
        _keepRunning = true;
        _log = _context.logManager().getLog(JobQueueRunner.class);
        setPriority(NORM_PRIORITY + 1);
        // all createRateStat in JobQueue
    }

    /**
     * The job currently being executed.
     *
     * @return the current job, null while this runner is idle
     */
    public Job getCurrentJob() {
        JobQueue.InFlight f = _inFlight;
        return f != null ? f._job : null;
    }
    /**
     * The most recently completed job.
     *
     * @return the last job
     */
    public Job getLastJob() {return _lastJob;}
    /**
     * Identifier for this runner.
     *
     * @return the runner id
     */
    public int getRunnerId() {return _id;}
    /** Stop the runner. */
    public void stopRunning() {_keepRunning = false;}
    /** Start the runner. */
    public void startRunning() {_keepRunning = true;}
    /**
     * The in-flight job and the instant its run began, as one consistent value.
     * Callers that need both take them here, from a single read.
     *
     * @return the snapshot, null while this runner is idle
     * @since 0.9.72+
     */
    public JobQueue.InFlight getInFlight() {return _inFlight;}

    /**
     * When the job being executed right now began. This is read together with
     * {@link #getCurrentJob()} from {@link JobQueue.InFlight}, one value, so a
     * caller cannot see a job alongside a timestamp from an earlier run.
     *
     * @return adjusted time in ms, -1 while this runner is idle
     * @since 0.9.72+; replaces {@code getLastBegin()}, which reported the start
     *        of the last job to run and stayed set, and stale, after it finished
     */
    public long getInFlightBegin() {
        JobQueue.InFlight f = _inFlight;
        return f != null ? f._beganOn : -1;
    }
    /**
     * When the job that ran most recently finished, stats update included.
     *
     * @return adjusted time in ms, 0 until the first job has finished
     */
    public long getLastEnd() {return _lastEnd;}

    /**
     * Resolve the cached RateStat handles for the three per-job stats.
     * <p>
     * Every job used to pay three string-keyed ConcurrentHashMap lookups in
     * StatManager, and each handle then fans out to a synchronized Rate per
     * period - nine monitor acquisitions per job across three periods. The
     * stats are created once by the JobQueue constructor before any runner
     * exists, so caching the handles removes the lookups without changing
     * which rates receive data. Left null when the stat was never created
     * ({@code stat.full} off), in which case the update is skipped, matching
     * {@link net.i2p.stat.StatManager#addRateData}'s own no-op.
     */
    private void resolveStats() {
        if (_jobRunStat == null) {_jobRunStat = _context.statManager().getRate("jobQueue.jobRun");}
        if (_jobLagStat == null) {_jobLagStat = _context.statManager().getRate("jobQueue.jobLag");}
        if (_jobWaitStat == null) {_jobWaitStat = _context.statManager().getRate("jobQueue.jobWait");}
    }

    public void run() {
        long lastActive;
        resolveStats();
        while (_keepRunning && _context.jobQueue().isAlive()) {
            Job job = null;
            try {
                job = _context.jobQueue().getNext();
                if (job == null) {
                    if (_context.router().isAlive() && _log.shouldError()) {
                        _log.error("Failed to pull next job from queue while router alive -> Retrying the pull");
                    }
                    continue;
                }
                // Single clock read per iteration, reused as the job's start
                // instant, its queue wait origin and its lag origin.
                long now = _context.clock().now();

                long enqueuedTime = 0;
                if (job instanceof JobImpl) {
                    long when = ((JobImpl)job).getMadeReadyOn();
                    if (when <= 0) {
                        _log.error("Job was never made ready: " + job);
                    } else {enqueuedTime = now - when;}
                }

                _currentJob = job;
                _lastJob = null;
                if (_log.shouldDebug()) {
                    _log.debug(job.getName() + " -> [Runner " + _id + "] running");
                }
                long origStartAfter = job.getTiming().getStartAfter();
                long doStart = now;
                job.getTiming().start(doStart);
                // One volatile write publishes the job and its start instant
                // together, so a reader cannot pair them inconsistently.
                _inFlight = new JobQueue.InFlight(job, doStart);
                try {
                    runCurrentJob();
                } finally {
                    // Retire the record on every path: a job that dies with an
                    // Error escaping runCurrentJob() must not leave the
                    // watchdog reading a job that runs forever.
                    _inFlight = null;
                }
                long beforeUpdate = _context.clock().now();
                job.getTiming().end(beforeUpdate);
                long duration = job.getTiming().getActualEnd() - job.getTiming().getActualStart();
                _context.jobQueue().updateStats(job, doStart, origStartAfter, duration);

                long lag = doStart - origStartAfter;
                if (lag < 0) {lag = 0;}

                RateStat jobRunStat = _jobRunStat;
                if (jobRunStat != null) {jobRunStat.addData(duration, duration);}
                RateStat jobLagStat = _jobLagStat;
                if (jobLagStat != null) {jobLagStat.addData(lag);}
                RateStat jobWaitStat = _jobWaitStat;
                if (jobWaitStat != null) {jobWaitStat.addData(enqueuedTime, enqueuedTime);}

                if (duration > 1500) {
                    _context.statManager().addRateData("jobQueue.jobRunSlow", duration, duration);
                    if (_log.shouldWarn() && doStart-origStartAfter > 100) {
                        _log.warn("Completed " + _currentJob + " in " + duration
                                  + "ms -> Lag: " + (doStart-origStartAfter) + "ms");
                    } else if (_log.shouldInfo()) {
                        _log.info("Completed " + _currentJob + " in " + duration + "ms");
                    }
                }

                lastActive = _context.clock().now();
                // Reuses the post-update reading instead of a second one taken
                // immediately before the slow-job branch; the warning threshold
                // is 1s, far beyond the microseconds that separates them.
                if (lastActive - beforeUpdate > 1000 && _log.shouldWarn()) {
                    _log.warn("Updating stats for '" + job.getName() + "' took too long (" + (lastActive - beforeUpdate) + "ms)");
                }
                _lastJob = _currentJob;
                _currentJob = null;
                _lastEnd = lastActive;
            } catch (Exception t) {
                _log.log(Log.CRIT, "Job failed" + (job != null ? ": " + job.getName() : "") + " * Runner " + _id, t);
            }
        }
        if (_context.router().isAlive()) {_log.debug("Queue runner " + _id + " removed (idle)");}
        _context.jobQueue().removeRunner(_id);
    }

    /** Runs the job taken from the queue. */
    private void runCurrentJob() {
        try {
            if (_currentJob != null) {_currentJob.runJob();}
        } catch (OutOfMemoryError oom) {
            try {
                if (SystemVersion.isAndroid()) {_context.router().shutdown(Router.EXIT_OOM);}
                else {fireOOM(oom);}
            } catch (Throwable t) { /* ignored */ }
        } catch (Exception t) {
            _log.log(Log.CRIT, "Error processing job [" + _currentJob.getName() + "] on thread " + _id, t);
        }
    }

}
