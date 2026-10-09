package net.i2p.router;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.router.message.HandleGarlicMessageJob;
import net.i2p.router.networkdb.kademlia.ExploreJob;
import net.i2p.router.networkdb.kademlia.HandleFloodfillDatabaseLookupMessageJob;
import net.i2p.router.networkdb.kademlia.IterativeSearchJob;
import net.i2p.router.networkdb.kademlia.RepublishLeaseSetJob;
import net.i2p.router.peermanager.PeerTestJob;
import net.i2p.router.tunnel.pool.TestJob;
import net.i2p.stat.RateConstants;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Prioritizes and executes router jobs with preference for earlier scheduled tasks.
 * Manages job queues, timing, and thread pool execution for router-internal operations only.
 *
 * For use by the router only. Not to be used by applications or plugins.
 */
public class JobQueue {
    private final Log _log;
    private final RouterContext _context;

    /** Integer (runnerId) to JobQueueRunner for created runners */
    private final Map<Integer, JobQueueRunner> _queueRunners;
    /** Counter to identify a job runner */
    private static final AtomicInteger _runnerId = new AtomicInteger(0);
    /** List of jobs that are ready to run ASAP */
    private final BlockingQueue<Job> _readyJobs;
    /** List of high priority jobs that should run before others */
    private final BlockingQueue<Job> _highPriorityJobs;
    /** SortedSet of jobs that are scheduled for running in the future, earliest first.
     * Typed as the concrete skip list because the pumper reads {@code first()}
     * as the minimum start time rather than re-deriving one per pass. */
    private final ConcurrentSkipListSet<Job> _timedJobs;
    /** Queue of timed jobs that are ready to run (moved from _timedJobs when ready) */
    private final BlockingQueue<Job> _timedJobsReady;
    /** Membership index over the three ready queues, so duplicate detection is O(1).
     * Maintained exclusively through {@link #offerReady}, {@link #removeReady},
     * {@link #takeReady}, {@link #addTimed} and {@link #removeTimed} so that no
     * queue mutation can leave it stale.
     *
     * <p>Membership is released at <em>dispatch</em>, not at completion:
     * {@link JobImpl#requeue} is called from {@code runJob()} on the running job
     * itself, so holding the entry until completion would swallow every
     * self-requeueing job.
     */
    private final Set<Job> _readyIndex;
    /**
     * Number of TestJob instances in the three ready queues or _timedJobs, i.e.
     * what {@link #getTestJobCount} reports.  Maintained by the same helpers, so
     * the pumper can sample it at 100 Hz without walking three collections under
     * _jobLock.
     *
     * @since 0.9.71+
     */
    private final AtomicInteger _queuedTestJobs;
    /** Job name to JobStat for that job */
    private final ConcurrentHashMap<String, JobStats> _jobStats;
    private final QueuePumper _pumper;
    /** Will we allow the # job runners to grow beyond 1? */
    private volatile boolean _allowParallelOperation;
    /** Have we been killed or are we alive? */
    private volatile boolean _alive;
    private final Object _jobLock;
    private volatile long _nextPumperRun;

    /** How many when we go parallel */
    static int runners;
    static {
        int cores = SystemVersion.getCores();
        int maxRunners = 32;
        runners = SystemVersion.isSlow() ? 8 : Math.max(cores * 2, 12);
        if (runners > maxRunners) {runners = maxRunners;}
    }

    /** Router.config parameter to override the max runners */
    static final String PROP_MAX_RUNNERS = "router.maxJobRunners";
    /** If a job is this lagged, spit out a warning, but keep going */
    private static final long DEFAULT_LAG_WARNING = 5*1000L;
    private long _lagWarning = DEFAULT_LAG_WARNING;
    /** If a job is this lagged, the router is hosed, so spit out a warning (don't shut it down) */
    private static final long DEFAULT_LAG_FATAL = 30*1000L;
    private long _lagFatal = DEFAULT_LAG_FATAL;
    /** If a job takes this long to run, spit out a warning, but keep going */
    private static final long DEFAULT_RUN_WARNING = 5*1000L;
    private long _runWarning = DEFAULT_RUN_WARNING;
    /** If a job takes this long to run, the router is hosed, so spit out a warning (don't shut it down) */
    private static final long DEFAULT_RUN_FATAL = 30*1000L;
    private long _runFatal = DEFAULT_RUN_FATAL;
    /** Don't enforce fatal limits until the router has been up for this long */
    private static final long DEFAULT_WARMUP_TIME = 15*60*1000L;
    private long _warmupTime = DEFAULT_WARMUP_TIME;
    /** Max ready and waiting jobs before we start dropping 'em - scale with runner count */
    private static final int DEFAULT_MAX_WAITING_JOBS = SystemVersion.isSlow() ? 24 : 48;
    /** Resolved once from {@link #PROP_MAX_WAITING_JOBS} at construction.
     * shouldDrop() read it per addJob() that could exceed the cap.  A
     * non-positive value disables dropping. */
    private final int _maxWaitingJobs;
    /** Minimum lag (ms) before the drop policy activates.
     * Must be high enough to avoid drops during normal processing jitter;
     * low enough to shed load before queue saturation causes cascading failure.
     * 500ms means the drop gate is meaningful: with 48+ queued jobs AND
     * a half-second of observed lag, the scaler has failed to keep up. */
    private static final long MIN_LAG_TO_DROP = 500;

    /**
     * @since 0.9.52+
     */
    private static final String PROP_MAX_WAITING_JOBS = "router.maxWaitingJobs";

    /**
     * Queue runners wait on this whenever they're not doing anything, and
     * this gets notified *once* whenever there are ready jobs
     */
    private final Object _runnerLock = new Object();

    /** Dynamic scaling controller for adjusting runner count based on load */
    private final JobQueueScaler _scaler;

    private static final long[] RATES = RateConstants.SHORT_TERM_RATES;

    /** Track dropped jobs for UI display */
    private final AtomicInteger _droppedJobsCount = new AtomicInteger();

    /**
     * Does not start the pumper. Caller MUST call startup.
     */
    public JobQueue(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(JobQueue.class);
        _maxWaitingJobs = _context.getProperty(PROP_MAX_WAITING_JOBS, DEFAULT_MAX_WAITING_JOBS);

        _context.statManager().createRateStat("jobQueue.droppedJobs", "Scheduled jobs dropped due to insane overload", "JobQueue", RATES);
        _context.statManager().createRequiredRateStat("jobQueue.queuedJobs", "Scheduled jobs in queue", "JobQueue", RATES);
        _context.statManager().createRequiredRateStat("jobQueue.readyJobs", "Ready and waiting scheduled jobs", "JobQueue", RATES);
        _context.statManager().createRateStat("jobQueue.testJobCount", "Number of TestJob instances in queue", "JobQueue", RATES);
        _context.statManager().createRateStat("jobQueue.testJobHardLimit", "TestJob hard limit events", "JobQueue", RATES);
        // following are for JobQueueRunner
        _context.statManager().createRateStat("jobQueue.jobRun", "Duration of scheduled jobs", "JobQueue", RATES);
        _context.statManager().createRequiredRateStat("jobQueue.jobRunSlow", "Duration of jobs that take over a second (ms)", "JobQueue", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRateStat("jobQueue.jobWait", "Time a scheduled job stays queued before running (ms)", "JobQueue", RATES);
        _context.statManager().createRequiredRateStat("jobQueue.jobLag", "Delay before waiting jobs are run (ms)", "JobQueue", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });

        _readyJobs = new LinkedBlockingQueue<>();
        _highPriorityJobs = new LinkedBlockingQueue<>();
        _timedJobs = new ConcurrentSkipListSet<>(new JobComparator());
        _timedJobsReady = new LinkedBlockingQueue<>();
        _readyIndex = Collections.newSetFromMap(new ConcurrentHashMap<>(256));
        _queuedTestJobs = new AtomicInteger();
        _jobLock = new Object();
        _queueRunners = new ConcurrentHashMap<>(runners);
        _jobStats = new ConcurrentHashMap<>();
        _pumper = new QueuePumper();
        _scaler = new JobQueueScaler(context, this);
    }

    /**
     * Claim the job in {@link #_readyIndex} before {@code offer()} publishes it,
     * so a concurrent addJob() sees it as already queued in that window.
     */
    private void offerReady(Job job, BlockingQueue<Job> queue) {
        _readyIndex.add(job);
        if (job instanceof TestJob) {_queuedTestJobs.incrementAndGet();}
        queue.offer(job);
    }

    /**
     * Remove from a ready queue, releasing the index and TestJob claims only if
     * the job was actually present.
     *
     * @return true if the job was in the queue and has been removed
     */
    private boolean removeReady(Job job, BlockingQueue<Job> queue) {
        if (!queue.remove(job)) return false;
        _readyIndex.remove(job);
        if (job instanceof TestJob) {_queuedTestJobs.decrementAndGet();}
        return true;
    }

    /**
     * Dispatch from a ready queue, releasing the index claim here rather than at
     * completion.
     *
     * @return the next job, or null if the queue was empty
     */
    private Job takeReady(BlockingQueue<Job> queue) {
        Job job = queue.poll();
        if (job == null) return null;
        _readyIndex.remove(job);
        if (job instanceof TestJob) {_queuedTestJobs.decrementAndGet();}
        return job;
    }

    /** Add to the future-scheduled skip list, keeping {@link #_queuedTestJobs} in step. */
    private void addTimed(Job job) {
        _timedJobs.add(job);
        if (job instanceof TestJob) {_queuedTestJobs.incrementAndGet();}
    }

    /**
     * Remove from the future-scheduled skip list.
     *
     * @return true if the job was scheduled and has been removed
     */
    private boolean removeTimed(Job job) {
        if (!_timedJobs.remove(job)) return false;
        if (job instanceof TestJob) {_queuedTestJobs.decrementAndGet();}
        return true;
    }

    /**
     * Enqueue the specified job for normal processing.
     *
     * @param job job to add to the queue
     */
    public void addJob(Job job) {
        if (job == null || !_alive) {
            if (_log.shouldWarn() && job != null) {
                _log.warn("JobQueue.addJob: job=" + job + ", alive=" + _alive + ", returning");
            }
            return;
        }

        int numReady;
        boolean alreadyExists;
        boolean dropped = false;
        boolean readyNow = false;
        long now = _context.clock().now();
        long start = job.getTiming().getStartAfter();
        if (start > now + 3*24*60*60*1000L && _log.shouldWarn()) {
            _log.warn(job + " scheduled far in the future: " + (new Date(start)));
        }
        // O(1) index lookup; it covers _timedJobsReady too, whose jobs the pumper has
        // promoted and which are as ready as those in _readyJobs.
        alreadyExists = _readyIndex.contains(job);
        numReady = getReadyCount();

        if (!alreadyExists) {
            // _timedJobs is ConcurrentSkipListSet — safe to modify outside the lock.
            boolean removed = removeTimed(job);
            if (removed && _log.shouldWarn()) {_log.warn(job + " removed from queue and rescheduled -> Duplicate instance");}

            // Don't re-add if it was already in _timedJobs (duplicate from requeue while still scheduled)
            if (!removed) {
                if (shouldDrop(job, numReady)) {
                    if (_log.shouldWarn() && job.getName().contains("Remove Slow")) {
                        _log.warn("Dropping RemoveSlowTunnelsJob: numReady=" + numReady + ", maxLag=" + getMaxLag());
                    }
                    job.dropped();
                    dropped = true;
                } else {
                    if (start <= now) {
                        job.getTiming().setStartAfter(now);
                        if (job instanceof JobImpl) {((JobImpl) job).madeReady(now);}
                        offerReady(job, _readyJobs);
                        readyNow = true;
                    } else {
                        addTimed(job);
                        if (_log.shouldDebug()) {
                            long diff = _nextPumperRun - start;
                            _log.debug("Waking pumper: job " + job.getName() + " early by " + diff + "ms");
                        }
                        // Wake pumper if this job is due before its next scheduled run.
                        // Must be inside _jobLock to pair with the pumper's wait() call.
                        if (start < _nextPumperRun) {
                            synchronized (_jobLock) {
                                _jobLock.notifyAll();
                            }
                        }
                    }
                }
            }
        }

        // Wake runners waiting in getNext() — outside _jobLock to avoid
        // contention with runners checking the queues.
        if (readyNow) {
            synchronized (_runnerLock) {
                _runnerLock.notifyAll();
            }
        }

        _context.statManager().addRateData("jobQueue.readyJobs", numReady);
        _context.statManager().addRateData("jobQueue.queuedJobs", _timedJobs.size());

        if (dropped) {
            _context.statManager().addRateData("jobQueue.droppedJobs", 1);
            _droppedJobsCount.incrementAndGet();
            if (_log.shouldWarn()) {
                _log.warn(job + " dropped due to backlog -> " + numReady + " jobs already queued");
            }
            String key = job.getName();
            JobStats stats = _jobStats.get(key);
            if (stats == null) {
                stats = new JobStats(key);
                JobStats old = _jobStats.putIfAbsent(key, stats);
                if (old != null) {stats = old;}
            }
            stats.jobDropped();
        }
    }

    /**
     * Enqueue the specified job at the top of the queue, ahead of all
     * normal-priority jobs.  The job is exempt from the drop policy and its
     * scheduled start time is overridden to run immediately.  Any existing
     * copy in the timed or ready queues is removed first, so promotion can
     * never cause the job to run twice.
     *
     * @param job the job to promote to the front of the queue
     */
    public void addJobToTop(Job job) {
        if (job == null || !_alive) {
            if (_log.shouldWarn() && job != null) {
                _log.warn("JobQueue.addJobToTop: job=" + job + ", alive=" + _alive + ", returning");
            }
            return;
        }

        synchronized (_jobLock) {
            // Promote any scheduled copy so the job runs once (now) instead of
            // twice (now, plus again when the timed copy matures).
            if (removeTimed(job) && _log.shouldWarn()) {
                _log.warn(job + " removed from queue and promoted to top -> Duplicate instance");
            }
            // remove() is O(n) on a LinkedBlockingQueue but the queue is always small (< 100).
            // Calling it unconditionally on all three avoids a second linear
            // scan for contains().
            removeReady(job, _timedJobsReady);
            removeReady(job, _readyJobs);
            removeReady(job, _highPriorityJobs);
            if (job instanceof JobImpl) {((JobImpl) job).madeReady(_context.clock().now());}
            offerReady(job, _highPriorityJobs);
        }

        // Wake up runners in case they're waiting
        synchronized (_runnerLock) {
            _runnerLock.notifyAll();
        }
    }

    /**
     * Remove a job from the job queue.
     *
     * @param job the job to remove from the queue
     */
    public void removeJob(Job job) {
        synchronized (_jobLock) {
            if (removeTimed(job)) return;
            if (removeReady(job, _timedJobsReady)) return;
            removeReady(job, _readyJobs);
            removeReady(job, _highPriorityJobs);
        }
    }

    /**
     * Number of jobs ready to be executed.
     *
     * @return count of ready jobs in normal, high priority, and timed-ready queues
     */
    public int getReadyCount() {
        return _readyJobs.size() + _highPriorityJobs.size() + _timedJobsReady.size();
    }

    /**
     * Dropped jobs count (resets on read).
     *
     * @return number of jobs dropped since last call
     */
    public int getAndResetDroppedCount() {
        return _droppedJobsCount.getAndSet(0);
    }

    /**
     * Maximum lag time for jobs waiting in the queue.
     * This is the delay between when the earliest job was supposed to start
     * and the current time.  Uses peek() on each FIFO queue — the head
     * element is the oldest and therefore has the highest lag.
     *
     * @return maximum lag in milliseconds, or 0 if all queues are empty
     */
    public long getMaxLag() {
        long now = _context.clock().now();
        long maxLag = 0;

        Job j = _timedJobsReady.peek();
        if (j != null) {
            JobTiming jt = j.getTiming();
            if (jt != null) {
                long lag = now - jt.getStartAfter();
                if (lag > maxLag) maxLag = lag;
            }
        }

        j = _highPriorityJobs.peek();
        if (j != null) {
            JobTiming jt = j.getTiming();
            if (jt != null) {
                long lag = now - jt.getStartAfter();
                if (lag > maxLag) maxLag = lag;
            }
        }

        j = _readyJobs.peek();
        if (j != null) {
            JobTiming jt = j.getTiming();
            if (jt != null) {
                long lag = now - jt.getStartAfter();
                if (lag > maxLag) maxLag = lag;
            }
        }

        return maxLag;
    }

    /**
     * Maximum duration of currently running jobs.
     * This measures how long active jobs have been executing,
     * which is important when all runners are busy but queue is empty.
     *
     * @return max duration in milliseconds of any currently running job, or 0 if no jobs running
     * @since 0.9.68+
     */
    public long getMaxActiveJobDuration() {
        long now = _context.clock().now();
        long maxDuration = 0;

        for (JobQueueRunner runner : _queueRunners.values()) {
            if (runner.getCurrentJob() != null) {
                long beginTime = runner.getLastBegin();
                if (beginTime > 0) {
                    long duration = now - beginTime;
                    if (duration > maxDuration) {
                        maxDuration = duration;
                    }
                }
            }
        }

        return maxDuration;
    }

    /**
     * Average lag time for jobs waiting in the queue.
     * This is the average delay between when jobs were supposed to start
     * and the current time across all ready jobs.
     *
     * Uses weakly-consistent iteration on each BlockingQueue — concurrent
     * additions/removals may be missed or double-counted, but the result
     * is a sufficient approximation for diagnostics and scoring.
     *
     * @return average lag in milliseconds, or 0 if queue is empty
     * @since 0.9.68+
     */
    public long getAvgLag() {
        long now = _context.clock().now();
        long totalLag = 0;
        int jobCount = 0;

        // Check timed jobs
        for (Job job : _timedJobsReady) {
            JobTiming jt = job.getTiming();
            if (jt != null) {
                long lag = now - jt.getStartAfter();
                if (lag > 0) {
                    totalLag += lag;
                    jobCount++;
                }
            }
        }

        // Check ready jobs
        for (Job job : _readyJobs) {
            JobTiming jt = job.getTiming();
            if (jt != null) {
                long lag = now - jt.getStartAfter();
                if (lag > 0) {
                    totalLag += lag;
                    jobCount++;
                }
            }
        }

        // Check high priority jobs
        for (Job job : _highPriorityJobs) {
            JobTiming jt = job.getTiming();
            if (jt != null) {
                long lag = now - jt.getStartAfter();
                if (lag > 0) {
                    totalLag += lag;
                    jobCount++;
                }
            }
        }

        return jobCount > 0 ? totalLag / jobCount : 0;
    }

    private boolean shouldDrop(Job job, int numReady) {
        if (_maxWaitingJobs <= 0) return false;
        if (!_allowParallelOperation) return false;
        if (numReady > _maxWaitingJobs) {
            Class<? extends Job> cls = job.getClass();
            String jobName = cls.getName();
            if (getMaxLag() >= MIN_LAG_TO_DROP) {
                if (cls == RepublishLeaseSetJob.class) {return false;}
                // Never drop leaseset lifecycle jobs: renewal, refresh, and batch
                // minting must always proceed even when the queue is backlogged,
                // otherwise the renewal cycle dies and services become unreachable
                if (jobName.contains("ExpireLeasesJob") ||
                    jobName.contains("RefreshClientLeaseSetsJob") ||
                    jobName.contains("BatchRepublishJob") ||
                    jobName.contains("OnRepublishFailure") ||
                    jobName.contains("OnRepublishSuccess")) {
                    return false;
                }
                // Don't drop critical tunnel management jobs
                if (jobName.equals("net.i2p.router.tunnel.pool.TunnelPoolManager$RemoveSlowTunnelsJob")) {return false;}
                // Drop timeout-based jobs when lagging to reduce queue pressure
                if (jobName.contains("SendTimeoutJob") || jobName.contains("VerifyTimeout") || jobName.contains("FloodOnlyLookupTimeout")) {return true;}
                // Drop non-critical verification jobs when lagging
                if (jobName.contains("DropLookupFoundJob") || jobName.contains("DropLookupFailedJob") ||
                    jobName.contains("DirectLookupJob") || jobName.contains("DirectLookupMatch")) {return true;}
                if (cls == PeerTestJob.class) {
                    return true;
                }
                if (cls == ExploreJob.class ||
                    cls == HandleFloodfillDatabaseLookupMessageJob.class ||
                    cls == HandleGarlicMessageJob.class ||
                    cls == IterativeSearchJob.class) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Enable parallel job execution and start additional queue runner threads.
     * After calling this, multiple jobs may execute concurrently.
     */
    public void allowParallelOperation() {
        _allowParallelOperation = true;
        int requested = _context.getProperty(PROP_MAX_RUNNERS, runners);
        // Cap to 2× cores to prevent context-switching death spirals
        int capped = Math.min(requested, Math.max(SystemVersion.getCores() * 2, 16));
        if (requested > capped && _log.shouldWarn()) {
            _log.warn("Capping job runners from " + requested + " to " + capped +
                      " (2× cores) to prevent CPU thrashing");
        }
        runQueue(capped);
    }

    /**
     * Initialize and start the job queue pumper thread.
     * Does not start the job runner threads.
     */
    public void startup() {
        _alive = true;
        I2PThread pumperThread = new I2PThread(_pumper, "JobQPumper", true);
        pumperThread.setPriority(Thread.MAX_PRIORITY);
        pumperThread.start();
        _scaler.startup();
    }

    /**
     * Shutdown the job queue, stopping all runners and clearing all jobs.
     */
    void shutdown() {
        _alive = false;
        // The queues below are cleared without running dropped() or any
        // timeout callback, so tell the batch-test subsystem to reclaim its
        // slots, pending selectors, rounds, and buffer itself; anything left
        // behind here would hold a permit that nothing will ever return.
        TestJob.onJobQueueShutdown(_context);
        _scaler.shutdown();
        synchronized (_jobLock) {
            _timedJobs.clear();
            _timedJobsReady.clear();
            _readyJobs.clear();
            _highPriorityJobs.clear();
            // The queues are now empty, so the derived state must go too.
            _readyIndex.clear();
            _queuedTestJobs.set(0);
            _jobLock.notifyAll();
        }
        Job poison = new PoisonJob();
        for (JobQueueRunner runner : _queueRunners.values()) {
            runner.stopRunning();
            _readyJobs.offer(poison);
        }
        _queueRunners.clear();
        _jobStats.clear();
        _runnerId.set(0);
    }

    /**
     * Check if the job queue is currently alive and processing jobs.
     * Public so subsystems that queue long-lived work (notably
     * {@link TestJob}'s batch dispatch) can refuse to hand it new jobs once
     * the queue is gone — addJob() silently discards then, which would leave
     * their internal state waiting on a pump that never runs.
     *
     * @return true if the queue is alive and running
     */
    public boolean isAlive() {return _alive;}

    /**
     * Timestamp of when the last job began execution.
     *
     * @return timestamp in milliseconds when the last job started, or -1 if no jobs have run
     */
    public long getLastJobBegin() {
        long when = -1;
        for (JobQueueRunner runner : _queueRunners.values()) {
            long cur = runner.getLastBegin();
            if (cur > when) {when = cur;}
        }
        return when;
    }

    /**
     * Timestamp of when the last job finished execution.
     *
     * @return timestamp in milliseconds when the last job ended, or -1 if no jobs have run
     */
    public long getLastJobEnd() {
        long when = -1;
        for (JobQueueRunner runner : _queueRunners.values()) {
            long cur = runner.getLastEnd();
            if (cur > when) {when = cur;}
        }
        return when;
    }

    /**
     * Last job that was executed.
     *
     * @return the last Job that was executed, or null if no jobs have run
     */
    public Job getLastJob() {
        Job j = null;
        long when = -1;
        for (JobQueueRunner cur : _queueRunners.values()) {
            if (cur.getLastBegin() > when) {
                j = cur.getCurrentJob();
                when = cur.getLastBegin();
            }
        }
        return j;
    }

    /** Next job in queue. */
    Job getNext() {
        while (_alive) {
            try {
                // First check high-priority jobs
                Job j = takeReady(_highPriorityJobs);
                if (j != null) {
                    if (j.getJobId() == POISON_ID) break;
                    return j;
                }

                // Check timed jobs ready queue first (O(1) instead of iterating skip list)
                j = takeReady(_timedJobsReady);
                if (j != null) {
                    if (j.getJobId() == POISON_ID) break;
                    return j;
                }

                // Check normal priority jobs (non-blocking — we wait below)
                j = takeReady(_readyJobs);
                if (j != null) {
                    if (j.getJobId() == POISON_ID) break;
                    return j;
                }

                // All queues empty — wait for notification from addJob() or the pumper.
                // 50ms timeout serves as a safety net for shutdown/poison checks.
                synchronized (_runnerLock) {
                    _runnerLock.wait(50);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (_log.shouldWarn()) {_log.warn("Job no longer alive; returning null");}
        return null;
    }

    /**
     * Start the job queue with the specified number of runner threads.
     * Does nothing if parallel operation is not enabled and runners already exist.
     *
     * @param numThreads the number of runner threads to start
     */
    public synchronized void runQueue(int numThreads) {
        if ((!_queueRunners.isEmpty()) && (!_allowParallelOperation)) return;

        if (_queueRunners.size() < numThreads) {
            if (_log.shouldInfo()) {
                _log.info("Increasing the number of queue runners from " + _queueRunners.size() + " to " + numThreads);
            }
            for (int i = _queueRunners.size(); i < numThreads; i++) {
                JobQueueRunner runner = new JobQueueRunner(_context, i);
                _queueRunners.put(Integer.valueOf(i), runner);
                runner.setName("JobQueue." + _runnerId.incrementAndGet());
                runner.setPriority(Thread.MAX_PRIORITY);
                runner.start();
            }
        } else if (_log.shouldWarn()) {
            if (_queueRunners.size() == numThreads) {_log.warn("Already have " + numThreads + " threads");}
            else {_log.warn("Already have " + _queueRunners.size() + " threads, not decreasing...");}
        }
    }

    /**
     * Remove a queue runner from the registry.
     * Package-private for use by JobQueueRunner.
     *
     * @param id the runner ID to remove
     */
    void removeRunner(int id) {_queueRunners.remove(Integer.valueOf(id));}

    /**
     * Current number of active job runners.
     * Package-private for use by JobQueueScaler.
     *
     * @return the number of active runners
     * @since 0.9.68+
     */
    public int getActiveRunnerCount() {
        return _queueRunners.size();
    }

    /**
     * Current maximum number of job runners allowed.
     * Returns the RAM-adjusted limit if scaler is active and RAM is constrained,
     * otherwise returns the hard limit (2× configured).
     *
     * @return the current effective maximum runner limit
     * @since 0.9.68+
     */
    public int getMaxRunnerCount() {
        int configured = _context.getProperty(PROP_MAX_RUNNERS, runners);
        int hardLimit = configured * 2;
        // Cap to 2× cores
        int cpuCap = Math.max(SystemVersion.getCores() * 2, 16);
        hardLimit = Math.min(hardLimit, cpuCap);
        if (_scaler != null && _scaler.isAlive()) {
            int ramLimit = _scaler.getCurrentMaxRunners();
            return Math.min(hardLimit, ramLimit);
        }
        return hardLimit;
    }

    /**
     * Request additional job runners for an anticipated burst.
     * Delegates to JobQueueScaler for tracking and auto-decay.
     *
     * @param count  number of additional runners to reserve
     * @param reason  label for logging and tracking
     * @param autoReleaseMs  if &gt; 0, auto-decay after this many ms
     * @since 0.9.70+
     */
    public void requestJobRunners(int count, String reason, long autoReleaseMs) {
        _scaler.requestRunners(count, reason, autoReleaseMs);
    }

    /**
     * Release previously requested job runners.
     *
     * @param count  number of runners to release
     * @param reason  must match the reason used in requestJobRunners
     * @since 0.9.70+
     */
    public void releaseJobRunners(int count, String reason) {
        _scaler.releaseRunners(count, reason);
    }

    /**
     * Add additional job runners to the pool.
     * Package-private for use by JobQueueScaler.
     *
     * @param count the number of runners to add
     * @since 0.9.68+
     */
    synchronized void addRunners(int count) {
        if (!_allowParallelOperation || !_alive) {
            if (_log.shouldDebug())
                _log.debug("addRunners BLOCKED: allowParallel=" + _allowParallelOperation + ", alive=" + _alive);
            return;
        }

        int startKey = -1;
        for (int i = 0; i < 10000; i++) {
            if (!_queueRunners.containsKey(Integer.valueOf(i))) {
                startKey = i;
                break;
            }
        }
        if (startKey < 0) startKey = _queueRunners.size();

        for (int i = 0; i < count; i++) {
            int key = startKey + i;
            JobQueueRunner runner = new JobQueueRunner(_context, key);
            _queueRunners.put(Integer.valueOf(key), runner);
            runner.setName("JobQueue." + _runnerId.incrementAndGet());
            if (_log.shouldDebug())
                _log.debug("Starting runner " + key + " total now " + _queueRunners.size());
            runner.start();
        }

        if (_log.shouldInfo()) {
            _log.info("Added " + count + " runners. Total: " + _queueRunners.size() + " after start");
        }
    }

    /**
     * Remove idle job runners from the pool.
     * Package-private for use by JobQueueScaler.
     * Only removes runners that are not currently processing a job.
     *
     * @param maxToRemove the maximum number of runners to remove
     * @return the number of runners actually removed
     * @since 0.9.68+
     */
    synchronized int removeIdleRunners(int maxToRemove) {
        if (!_alive) return 0;
        int removed = 0;
        Iterator<Map.Entry<Integer, JobQueueRunner>> iter = _queueRunners.entrySet().iterator();
        while (iter.hasNext() && removed < maxToRemove) {
            Map.Entry<Integer, JobQueueRunner> entry = iter.next();
            JobQueueRunner runner = entry.getValue();
            // Only remove if runner is idle (not processing a job)
            if (runner.getCurrentJob() == null) {
                runner.stopRunning();
                iter.remove();
                removed++;
            }
        }
        if (removed > 0 && _log.shouldInfo()) {
            _log.info("Removed " + removed + " idle runners. Total: " + _queueRunners.size());
        }
        return removed;
    }

    private final class QueuePumper implements Runnable, Clock.ClockUpdateListener, RouterClock.ClockShiftListener {
        /**
         * Scratch buffer for the jobs a single pass promotes out of _timedJobs,
         * reused across passes so a 100 Hz pumper does not allocate per pass.
         * Emptied after the move so it never retains Job references between passes.
         */
        private final List<Job> _toMove = new ArrayList<>();

        public QueuePumper() {
            _context.clock().addUpdateListener(this);
            ((RouterClock) _context.clock()).addShiftListener(this);
        }

        /**
         * Pump jobs from the scheduling queue to the runner queue.
         *
         * The iteration and removal of _timedJobs happens outside the lock
         * because ConcurrentSkipListSet supports safe concurrent iteration.
         * Only the wait/notify signaling needs _jobLock, reducing contention
         * with addJob() which must also acquire _jobLock.
         */
        @Override
        public void run() {
            try {
                while (_alive) {
                    long now = _context.clock().now();
                    long timeToWait = -1;
                    int movedJobs = 0;
                    try {
                        // first() *is* the minimum: _timedJobs is ordered on getStartAfter().
                        // It throws on an empty set, hence the guard. -1 means "due
                        // now", which the wait ladder below turns into the shortest interval.
                        Job first = _timedJobs.isEmpty() ? null : _timedJobs.first();
                        long minWaitTime = -1;
                        if (first != null) {
                            long timeLeft = first.getTiming().getStartAfter() - now;
                            if (timeLeft > 0) {minWaitTime = timeLeft;}
                        }
                        // Weakly-consistent iteration, so no _jobLock is held and addJob()
                        // is not blocked. Walk everything rather than stopping at the first
                        // future job: setStartAfter() and offsetChanged() mutate the key
                        // concurrently and can transiently break the ordering.
                        _toMove.clear();
                        for (Job j : _timedJobs) {
                            if (j.getTiming().getStartAfter() <= now) {
                                _toMove.add(j);
                            }
                        }
                        // Move ready jobs to timed jobs ready queue
                        for (Job j : _toMove) {
                            if (!removeTimed(j)) {continue;}
                            if (j instanceof JobImpl) ((JobImpl)j).madeReady(now);
                            j.getTiming().setStartAfter(now);
                            offerReady(j, _timedJobsReady);
                            movedJobs++;
                        }
                        _toMove.clear();
                        timeToWait = minWaitTime;
                        // Cap the wait time to prevent long delays for periodic jobs
                        if (timeToWait > 10000) {
                            timeToWait = 500;
                        }
                        if (movedJobs > 0 && _log.shouldInfo()) {
                            _log.info("Pumper moved " + movedJobs + " jobs to timed ready queue, next wait: " + timeToWait + "ms, _timedJobs size: " + _timedJobs.size());
                        }
                        // O(1) counter now, so this 100 Hz pass is cheap enough to sample
                        // a stat that only feeds a graph.
                        int testJobCount = _queuedTestJobs.get();
                        if (testJobCount > 0) {
                            _context.statManager().addRateData("jobQueue.testJobCount", testJobCount);
                        }
                        boolean highLoad = SystemVersion.getCPULoadAvg() > 98 || SystemVersion.getCPULoad() > 98;
                        // More aggressive checking - don't wait long when jobs are close to ready
                        if (timeToWait < 0) {
                            timeToWait = highLoad ? 50 : 10;
                        } else if (timeToWait < 10) {
                            timeToWait = highLoad ? 20 : 5;
                        } else if (timeToWait < 100) {
                            timeToWait = highLoad ? 50 : 25;
                        } else if (timeToWait < 1000) {
                            timeToWait = highLoad ? 200 : 100;
                        } else if (timeToWait < 5*1000L) {
                            timeToWait = highLoad ? 500 : 250;
                        } else {
                            // For jobs > 5s away, check more frequently to prevent large queues
                            timeToWait = highLoad ? 1000 : 500;
                        }
                        _nextPumperRun = _context.clock().now() + timeToWait;
                        // Wait outside the critical section — addJob() is not blocked
                        synchronized (_jobLock) {
                            _jobLock.wait(timeToWait);
                        }
                        // Wake runners blocked in getNext() so they can
                        // pick up jobs we just moved to _timedJobsReady.
                        if (movedJobs > 0) {
                            synchronized (_runnerLock) {
                                _runnerLock.notifyAll();
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Exception t) {
                if (_log.shouldError()) {_log.error("Pumper killed?!", t);}
            } finally {
                _context.clock().removeUpdateListener(this);
                ((RouterClock) _context.clock()).removeShiftListener(this);
            }
        }
        /** Handle a clock offset change */

        public void offsetChanged(long delta) {
            updateJobTimings(delta);
            synchronized (_jobLock) {_jobLock.notifyAll();}
        }
        /** Handle a clock shift event */

        public void clockShift(long delta) {
            if (delta < 0) offsetChanged(delta);
            else {
                synchronized (_jobLock) {_jobLock.notifyAll();}
            }
        }
    }

    private void updateJobTimings(long delta) {
        synchronized (_jobLock) {
            for (Job j : _timedJobs) j.getTiming().offsetChanged(delta);
            for (Job j : _readyJobs) j.getTiming().offsetChanged(delta);
            for (Job j : _highPriorityJobs) j.getTiming().offsetChanged(delta);
        }
        synchronized (_runnerLock) {
            for (JobQueueRunner runner : _queueRunners.values()) {
                Job job = runner.getCurrentJob();
                if (job != null) job.getTiming().offsetChanged(delta);
            }
        }
    }

    /** Update stats */
    void updateStats(Job job, long doStart, long origStartAfter, long duration) {
        if (_context.router() == null) return;
        String key = job.getName();
        // Fix lag calculation: use actual job start time, not current time
        long actualStart = job.getTiming().getStartAfter();
        long lag = doStart - actualStart;
        MessageHistory hist = _context.messageHistory();
        long uptime = _context.router().getUptime();

        if (lag < 0) lag = 0;
        if (duration < 0) duration = 0;

        JobStats stats = _jobStats.get(key);
        if (stats == null) {
            stats = new JobStats(key);
            JobStats old = _jobStats.putIfAbsent(key, stats);
            if (old != null) stats = old;
        }
        stats.jobRan(duration, lag);

        String dieMsg = null;
        if (lag > _lagWarning) {
            dieMsg = "Too much lag for " + job.getName() + " Job: " + lag + "ms with run time of " + duration + "ms";
        } else if (duration > _runWarning) {
            dieMsg = "Run too long for " + job.getName() + " Job: " + lag + "ms lag with run time of " + duration + "ms";
        }
        if (dieMsg != null) {
            if (_log.shouldWarn()) {_log.warn(dieMsg);}
            if (hist != null) hist.messageProcessingError(-1, JobQueue.class.getName(), dieMsg);
        }

        if ((lag > _lagFatal) && (uptime > _warmupTime)) {
            if (_log.shouldWarn()) {_log.log(Log.WARN, "Router is incredibly overloaded or there's an error.");}
            return;
        }

        if ((uptime > _warmupTime) && (duration > _runFatal) && _log.shouldWarn()) {
            _log.log(Log.WARN, "Router is incredibly overloaded (slow cpu?) or there's an error.");
        }
    }

    private static final int POISON_ID = -99999;

    private static class PoisonJob implements Job {
        @Override
        public String getName() {return null;}
        @Override
        public long getJobId() {return POISON_ID;}
        @Override
        public JobTiming getTiming() {return null;}
        @Override
        public void runJob() { /* No-op - poison sentinel, not a real job */ }
        @Override
        public void dropped() { /* No-op - poison sentinel, not a real job */ }
    }

    private static class JobComparator implements Comparator<Job>, Serializable {
          @Override
          public int compare(Job l, Job r) {
              if (l.equals(r)) return 0;
              long ld = l.getTiming().getStartAfter() - r.getTiming().getStartAfter();
              if (ld < 0) return -1;
              if (ld > 0) return 1;
              // Use Long.compare to avoid overflow when comparing job IDs
              // Job IDs are unique, so this should be sufficient
              return Long.compare(l.getJobId(), r.getJobId());
         }
     }

    /**
     * Collect statistics about jobs currently in the queue.
     * This includes jobs that are ready, timed, active, and just finished.
     *
     * @param readyJobs collection to populate with ready jobs
     * @param timedJobs collection to populate with timed/scheduled jobs
     * @param activeJobs collection to populate with currently running jobs
     * @param justFinishedJobs collection to populate with recently finished jobs
     * @return the number of queue runners currently active
     */
    public int getJobs(Collection<Job> readyJobs, Collection<Job> timedJobs,
                       Collection<Job> activeJobs, Collection<Job> justFinishedJobs) {
        for (JobQueueRunner runner :_queueRunners.values()) {
            Job job = runner.getCurrentJob();
            if (job != null) activeJobs.add(job);
            else {
                job = runner.getLastJob();
                if (job != null) justFinishedJobs.add(job);
            }
        }
        synchronized (_jobLock) {
            readyJobs.addAll(_readyJobs);
            readyJobs.addAll(_highPriorityJobs);
            timedJobs.addAll(_timedJobs);
        }
        readyJobs.addAll(_timedJobsReady);
        return _queueRunners.size();
    }

    /**
     * All job statistics collected by the queue.
     *
     * @return unmodifiable collection of JobStats for all job types
     */
    public Collection<JobStats> getJobStats() {
        return Collections.unmodifiableCollection(_jobStats.values());
    }

    /**
     * Count the number of TestJob instances currently queued in the job queue.
     * This includes both ready jobs and timed jobs waiting to be executed.
     *
     * O(1): maintained by the queue-mutation helpers, because the pumper samples
     * it on every pass and walking three collections under _jobLock was
     * measurable work for a stat that only feeds a graph.
     *
     * <p>Counts a pumper-promoted job in {@link #_timedJobsReady} as queued.
     * Those jobs are due to run now, so omitting them understated the queue;
     * this matches the {@code jobQueue.testJobCount} description and the
     * admission check in {@link #addJob(Job)}, which treats them as ready.
     *
     * @return the total number of TestJob instances in the queue
     */
    public int getTestJobCount() {
        return _queuedTestJobs.get();
    }

    /**
     * Count TestJob instances that are ready to run or currently running
     * (excluding future-scheduled timed jobs). Used by the scaler to determine
     * whether TestJobs are dominating runner resources right now.
     *
     * @return the number of ready/active TestJob instances
     * @since 0.9.70+
     */
    public int getReadyTestJobCount() {
        int count = 0;
        synchronized (_jobLock) {
            for (Job job : _readyJobs) {
                if (job instanceof TestJob) {
                    count++;
                }
            }
            for (Job job : _highPriorityJobs) {
                if (job instanceof TestJob) {
                    count++;
                }
            }
        }
        return count;
    }

}
