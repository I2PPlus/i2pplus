package net.i2p.router;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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
    private final static AtomicInteger _runnerId = new AtomicInteger(0);
    /** List of jobs that are ready to run ASAP */
    private final BlockingQueue<Job> _readyJobs;
    /** List of high priority jobs that should run before others */
    private final BlockingQueue<Job> _highPriorityJobs;
    /** SortedSet of jobs that are scheduled for running in the future, earliest first */
    private final Set<Job> _timedJobs;
    /** Job name to JobStat for that job */
    private final ConcurrentHashMap<String, JobStats> _jobStats;
    private final QueuePumper _pumper;
    /** Will we allow the # job runners to grow beyond 1? */
    private volatile boolean _allowParallelOperation;
    /** Have we been killed or are we alive? */
    private volatile boolean _alive;
    private final Object _jobLock;
    private volatile long _nextPumperRun;
    /** Peak lag observed from completed jobs (reset on readout) */
    private volatile long _peakLag;
    /** Timestamp of last peak lag reset */
    private long _peakLagResetTime;
    private static final long PEAK_LAG_RESET_INTERVAL = 60*1000; // Reset every 60 seconds

    /** How many when we go parallel */
    static int RUNNERS;
    static {
        int cores = SystemVersion.getCores();
        int maxRunners = 32;
        RUNNERS = SystemVersion.isSlow() ? 16 : Math.max(cores * 2, 24);
        if (RUNNERS > maxRunners) {RUNNERS = maxRunners;}
    }

    /** Default max # job queue runners operating */
    private static int DEFAULT_MAX_RUNNERS = RUNNERS;
    /** router.config parameter to override the max runners */
    final static String PROP_MAX_RUNNERS = "router.maxJobRunners";
    /** If a job is this lagged, spit out a warning, but keep going */
    private final static long DEFAULT_LAG_WARNING = 15*1000;
    private long _lagWarning = DEFAULT_LAG_WARNING;
    /** If a job is this lagged, the router is hosed, so spit out a warning (don't shut it down) */
    private final static long DEFAULT_LAG_FATAL = 60*1000;
    private long _lagFatal = DEFAULT_LAG_FATAL;
    /** If a job takes this long to run, spit out a warning, but keep going */
    private final static long DEFAULT_RUN_WARNING = 10*1000;
    private long _runWarning = DEFAULT_RUN_WARNING;
    /** If a job takes this long to run, the router is hosed, so spit out a warning (don't shut it down) */
    private final static long DEFAULT_RUN_FATAL = 30*1000;
    private long _runFatal = DEFAULT_RUN_FATAL;
    /** Don't enforce fatal limits until the router has been up for this long */
    private final static long DEFAULT_WARMUP_TIME = 5*60*1000;
    private long _warmupTime = DEFAULT_WARMUP_TIME;
    /** Max ready and waiting jobs before we start dropping 'em */
    private final static int DEFAULT_MAX_WAITING_JOBS = SystemVersion.isSlow() ? 128 : 192;
    private int _maxWaitingJobs = DEFAULT_MAX_WAITING_JOBS;
    private final static long MIN_LAG_TO_DROP = 2000; // 2 seconds

    /** Attack mode: increase queue capacity to handle more load without dropping */
    private static final double BUILD_SUCCESS_THRESHOLD = 0.40; // 40% - under attack
    private static final int ATTACK_QUEUE_MULTIPLIER = 2; // 2x queue capacity during attacks

    /**
     *  @since 0.9.52+
     */
    private final static String PROP_MAX_WAITING_JOBS = "router.maxWaitingJobs";

    /**
     * Queue runners wait on this whenever they're not doing anything, and
     * this gets notified *once* whenever there are ready jobs
     */
    private final Object _runnerLock = new Object();

    /** Dynamic scaling controller for adjusting runner count based on load */
    private final JobQueueScaler _scaler;

    private static final long[] RATES = RateConstants.SHORT_TERM_RATES;

    /**
     *  Does not start the pumper. Caller MUST call startup.
     */
    public JobQueue(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(JobQueue.class);

        _context.statManager().createRateStat("jobQueue.droppedJobs", "Scheduled jobs dropped due to overload", "JobQueue", RATES);
        _context.statManager().createRateStat("jobQueue.queuedJobs", "Scheduled jobs in queue", "JobQueue", RATES);
        _context.statManager().createRateStat("jobQueue.readyJobs", "Ready and waiting scheduled jobs", "JobQueue", RATES);
        _context.statManager().createRateStat("jobQueue.testJobCount", "Number of TestJob instances in queue", "JobQueue", RATES);
        _context.statManager().createRateStat("jobQueue.testJobHardLimit", "TestJob hard limit events", "JobQueue", RATES);
        // following are for JobQueueRunner
        _context.statManager().createRateStat("jobQueue.jobRun", "Duration of scheduled jobs", "JobQueue", RATES);
        _context.statManager().createRateStat("jobQueue.jobRunSlow", "Duration of jobs that take over a second (ms)", "JobQueue", RATES);
        _context.statManager().createRateStat("jobQueue.jobWait", "Time a scheduled job stays queued before running (ms)", "JobQueue", RATES);
        _context.statManager().createRequiredRateStat("jobQueue.jobLag", "Delay before waiting jobs are run (ms)", "JobQueue", RATES);

        _readyJobs = new LinkedBlockingQueue<>();
        _highPriorityJobs = new LinkedBlockingQueue<>();
        _timedJobs = new TreeSet<>(new JobComparator());
        _jobLock = new Object();
        _queueRunners = new ConcurrentHashMap<>(RUNNERS);
        _jobStats = new ConcurrentHashMap<>();
        _pumper = new QueuePumper();
        _scaler = new JobQueueScaler(context, this);
    }

    /**
     * Enqueue the specified job for normal processing.
     *
     * @param job job to add to the queue
     */
    public void addJob(Job job) {
        if (job == null || !_alive) return;

        int numReady;
        boolean alreadyExists = false;
        boolean dropped = false;
        long now = _context.clock().now();
        long start = job.getTiming().getStartAfter();
        if (start > now + 3*24*60*60*1000L && _log.shouldWarn()) {
            _log.warn(job + " scheduled far in the future: " + (new Date(start)));
        }
        synchronized (_jobLock) {
            alreadyExists = _readyJobs.contains(job) || _highPriorityJobs.contains(job);
            numReady = _readyJobs.size();

            if (!alreadyExists) {
                boolean removed = _timedJobs.remove(job);
                if (removed) {
                    // Job was already scheduled in timed jobs - check if it's non-critical
                    // Non-critical duplicate jobs should be dropped, not rescheduled
                    if (isNonCriticalJob(job)) {
                        if (_log.shouldWarn()) {_log.warn(job + " removed from queue and dropped -> Duplicate non-critical job");}
                        job.dropped();
                        dropped = true;
                    } else if (_log.shouldWarn()) {
                        _log.warn(job + " removed from queue and rescheduled -> Duplicate instance");
                    }
                }

                if (!dropped && shouldDrop(job, numReady)) {
                    job.dropped();
                    dropped = true;
                }
                else {
                    if (start <= now) {
                        job.getTiming().setStartAfter(now);
                        if (job instanceof JobImpl) {((JobImpl) job).madeReady(now);}
                        _readyJobs.offer(job);
                    } else {
                        _timedJobs.add(job);
                        if (start < _nextPumperRun) {_jobLock.notifyAll();}
                    }
                }
            }
        }

        _context.statManager().addRateData("jobQueue.readyJobs", numReady);
        _context.statManager().addRateData("jobQueue.queuedJobs", _timedJobs.size());

        // Track TestJob queue count for monitoring
        int testJobCount = getTestJobCount();
        if (testJobCount > 0) {
            _context.statManager().addRateData("jobQueue.testJobCount", testJobCount);
        }
        if (dropped) {
            _context.statManager().addRateData("jobQueue.droppedJobs", 1);
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
     * Enqueue the specified job to be processed at the top of the queue.
     * This is a high-priority job that will be run before any normal-priority jobs.
     *
     * @param job job to add to the front of the queue
     */
    public void addJobToTop(Job job) {
        if (job == null || !_alive) return;

        synchronized (_jobLock) {
            // Avoid duplicates
            if (_highPriorityJobs.contains(job)) {
                _highPriorityJobs.remove(job);
            }
            if (job instanceof JobImpl) {((JobImpl) job).madeReady(_context.clock().now());}
            _highPriorityJobs.offer(job);
        }

        // Wake up runners in case they're waiting
        synchronized (_runnerLock) {
            _runnerLock.notify();
        }
    }

    /**
     * Remove a job from the job queue.
     *
     * @param job the job to remove from the queue
     */
    public void removeJob(Job job) {
        synchronized (_jobLock) {
            boolean removed = _timedJobs.remove(job);
            if (!removed) {_readyJobs.remove(job); _highPriorityJobs.remove(job);}
        }
    }

    /**
     * Get the number of jobs ready to be executed.
     *
     * @return count of ready jobs in both normal and high priority queues
     */
    public int getReadyCount() {
        return _readyJobs.size() + _highPriorityJobs.size();
    }

    /**
     * Get the maximum lag time for jobs waiting in the queue.
     * This is the delay between when the earliest job was supposed to start
     * and the current time.
     *
     * @return maximum lag in milliseconds, or 0 if queue is empty
     */
    public long getMaxLag() {
        Job j = _readyJobs.peek();
        if (j == null) j = _highPriorityJobs.peek();
        if (j == null) return 0;
        JobTiming jt = j.getTiming();
        if (jt == null) return 0;
        long startAfter = jt.getStartAfter();
        return _context.clock().now() - startAfter;
    }

    /**
     * Get the maximum duration of currently running jobs.
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
     * Get the average lag time for jobs waiting in the queue.
     * This is the average delay between when jobs were supposed to start
     * and the current time across all ready jobs.
     *
     * @return average lag in milliseconds, or 0 if queue is empty
     * @since 0.9.68+
     */
    public long getAvgLag() {
        long now = _context.clock().now();
        long totalLag = 0;
        int jobCount = 0;

        // Check ready jobs
        for (Job job : _readyJobs) {
            JobTiming jt = job.getTiming();
            if (jt != null) {
                long startAfter = jt.getStartAfter();
                long lag = now - startAfter;
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
                long startAfter = jt.getStartAfter();
                long lag = now - startAfter;
                if (lag > 0) {
                    totalLag += lag;
                    jobCount++;
                }
            }
        }

        return jobCount > 0 ? totalLag / jobCount : 0;
    }

    /**
     * Get the maximum lag time with sub-millisecond precision.
     * This is the delay between when the earliest job was supposed to start
     * and the current time.
     *
     * @return maximum lag in milliseconds as a double, or 0.0 if queue is empty
     * @since sub-millisecond precision update
     */
    public double getMaxLagDouble() {
        Job j = _readyJobs.peek();
        if (j == null) j = _highPriorityJobs.peek();
        if (j == null) return 0.0;
        JobTiming jt = j.getTiming();
        if (jt == null) return 0.0;
        long startAfter = jt.getStartAfter();
        // Calculate sub-millisecond lag using the pending time calculation from JobTiming
        double lagMs = _context.clock().now() - startAfter;
        if (jt.getActualStartNanos() > 0) {
            // If job has started, use its pending time for more accuracy
            lagMs = jt.getPendingMillis();
        }
        return lagMs > 0 ? lagMs : 0.0;
    }

    /**
     * Get the average lag time with sub-millisecond precision.
     * This is the average delay between when jobs were supposed to start
     * and the current time across all ready jobs.
     *
     * @return average lag in milliseconds as a double, or 0.0 if queue is empty
     * @since sub-millisecond precision update
     */
    public double getAvgLagDouble() {
        long now = _context.clock().now();
        double totalLag = 0.0;
        int jobCount = 0;

        // Check ready jobs
        for (Job job : _readyJobs) {
            JobTiming jt = job.getTiming();
            if (jt != null) {
                long startAfter = jt.getStartAfter();
                double lag = now - startAfter;
                if (jt.getActualStartNanos() > 0) {
                    // Use pending time calculation for sub-ms precision
                    lag = jt.getPendingMillis();
                }
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
                long startAfter = jt.getStartAfter();
                double lag = now - startAfter;
                if (jt.getActualStartNanos() > 0) {
                    // Use pending time calculation for sub-ms precision
                    lag = jt.getPendingMillis();
                }
                if (lag > 0) {
                    totalLag += lag;
                    jobCount++;
                }
            }
        }

        return jobCount > 0 ? totalLag / jobCount : 0.0;
    }

    /**
     * Check if a job is non-critical and can tolerate high lag without impacting router functionality.
     * These are exploratory, testing, or informational jobs that don't affect core routing.
     *
     * @param job the job to check
     * @return true if the job is non-critical and shouldn't trigger lag warnings
     */
    private static boolean isNonCriticalJob(Job job) {
        Class<? extends Job> cls = job.getClass();
        // Always critical
        if (cls == RepublishLeaseSetJob.class) {return false;}
        // Non-critical: testing, exploration, profiling
        if (cls == TestJob.class || cls == PeerTestJob.class) {return true;}
        String jobName = job.getName();
        if (jobName != null) {
            // Non-critical job types by name pattern
            if (jobName.contains("Test Local Tunnel") ||
                jobName.contains("Test") ||
                jobName.contains("Explore") ||
                jobName.contains("Peer Test") ||
                jobName.contains("Profile") ||
                jobName.contains("Bandwidth") ||
                jobName.contains("Stats") ||
                jobName.contains("Timeout Iterative Search")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the peak lag observed from completed jobs in the last interval.
     * This shows the maximum delay experienced by any job that finished,
     * unlike getAvgLag() which only shows current queue state.
     *
     * @return peak lag in milliseconds from recently completed jobs
     * @since 0.9.68+
     */
    public long getPeakLag() {
        return _peakLag;
    }

    private boolean shouldDrop(Job job, int numReady) {
        if (_maxWaitingJobs <= 0) return false;
        if (!_allowParallelOperation) return false;

        Class<? extends Job> cls = job.getClass();
        // NEVER drop RequestLeaseSetJob - critical for client connectivity
        if (cls.getName().contains("RequestLeaseSetJob")) {return false;}
        // NEVER drop RepublishLeaseSetJob - critical for network connectivity
        if (cls == RepublishLeaseSetJob.class) {return false;}

        int maxWaitingJobs = getMaxWaitingJobs();

        // Drop based on lag alone - prevents lag from building up
        // Also drop based on queue size to prevent memory bloat
        boolean highLag = getMaxLag() >= MIN_LAG_TO_DROP;
        if (highLag || numReady > maxWaitingJobs) {
            String jobName = job.getName();
            if (jobName != null) {
                if (jobName.contains("Lease") || jobName.contains("Timeout")) {return false;}
                // NEVER drop Build related jobs - critical for transit
                if (jobName.contains("Build")) {return false;}
                // NEVER drop Handle Message related jobs
                if (jobName.contains("Handle")) {return false;}
                // NEVER drop garlic messages - critical for all I2P communication
                if (jobName.contains("Garlic")) {return false;}
                // NEVER drop database store/lookup - critical for floodfill and peer discovery
                if (jobName.contains("Database Store") || jobName.contains("Database Lookup") || jobName.contains("Db")) {return false;}
                // NEVER drop tunnel build messages
                if (jobName.contains("Tunnel Build")) {return false;}
            }
            boolean disableTunnelTests = _context.getBooleanProperty("router.disableTunnelTesting");
            if ((!disableTunnelTests && cls == TestJob.class) ||
                cls == PeerTestJob.class ||
                cls == ExploreJob.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the maximum number of waiting jobs before dropping.
     * During attacks (low tunnel build success), increases queue capacity to handle more load.
     * This does NOT increase lag - it just allows more jobs to queue before dropping.
     *
     * @return the max waiting jobs limit
     * @since 0.9.68+
     */
    public int getMaxWaitingJobs() {
        int baseMax = _context.getProperty(PROP_MAX_WAITING_JOBS, DEFAULT_MAX_WAITING_JOBS);
        // Check if we're under attack (low tunnel build success)
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        if (buildSuccess < BUILD_SUCCESS_THRESHOLD) {
            // Under attack - increase queue capacity
            return baseMax * ATTACK_QUEUE_MULTIPLIER;
        }
        return baseMax;
    }

    /**
     * Enable parallel job execution and start additional queue runner threads.
     * After calling this, multiple jobs may execute concurrently.
     */
    public void allowParallelOperation() {
        _allowParallelOperation = true;
        runQueue(_context.getProperty(PROP_MAX_RUNNERS, RUNNERS));
    }

    /**
     * Initialize and start the job queue pumper thread.
     * Does not start the job runner threads.
     */
    public void startup() {
        _alive = true;
        I2PThread pumperThread = new I2PThread(_pumper, "JobQueuePumper", true);
        pumperThread.setPriority(I2PThread.MAX_PRIORITY - 1);
        pumperThread.start();
        _scaler.startup();
    }

    /**
     * Shutdown the job queue, stopping all runners and clearing all jobs.
     */
    void shutdown() {
        _alive = false;
        _scaler.shutdown();
        synchronized (_jobLock) {
            _timedJobs.clear();
            _readyJobs.clear();
            _highPriorityJobs.clear();
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
     *
     * @return true if the queue is alive and running
     */
    boolean isAlive() {return _alive;}

    /**
     * Get the timestamp of when the last job began execution.
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
     * Get the timestamp of when the last job finished execution.
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
     * Get the last job that was executed.
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

    Job getNext() {
        while (_alive) {
            try {
                // First check high-priority jobs
                Job j = _highPriorityJobs.poll();
                if (j != null) {
                    if (j.getJobId() == POISON_ID) break;
                    return j;
                }

                // Check for ready timed jobs before blocking
                long now = _context.clock().now();
                synchronized (_jobLock) {
                    for (Iterator<Job> iter = _timedJobs.iterator(); iter.hasNext(); ) {
                        Job job = iter.next();
                        long timeLeft = job.getTiming().getStartAfter() - now;
                        if (timeLeft <= 0) {
                            iter.remove();
                            if (job instanceof JobImpl) {((JobImpl) job).madeReady(now);}
                            return job;
                        } else {
                            // Job not ready yet, wait for it
                            // No deferral logic here - that's QueuePumper's job
                            break;
                        }
                    }
                }

                // Then check normal priority jobs
                j = _readyJobs.poll(100, TimeUnit.MILLISECONDS);
                if (j != null) {
                    if (j.getJobId() == POISON_ID) break;
                    return j;
                }
            } catch (InterruptedException ie) {}
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
                runner.setName("JobQueue " + _runnerId.incrementAndGet() + '/' + numThreads);
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
     * Get the current number of active job runners.
     * Package-private for use by JobQueueScaler.
     *
     * @return the number of active runners
     * @since 0.9.68+
     */
    int getActiveRunnerCount() {
        return _queueRunners.size();
    }

    /**
     * Get the current maximum number of job runners allowed.
     * Returns the RAM-adjusted limit if scaler is active and RAM is constrained,
     * otherwise returns the hard limit (2× configured).
     *
     * @return the current effective maximum runner limit
     * @since 0.9.68+
     */
    public int getMaxRunnerCount() {
        int hardLimit = _context.getProperty(PROP_MAX_RUNNERS, RUNNERS) * 2;
        if (_scaler != null && _scaler.isAlive()) {
            int ramLimit = _scaler.getCurrentMaxRunners();
            // Return the lower of the two - if RAM is constrained, show that
            return Math.min(hardLimit, ramLimit);
        }
        return hardLimit;
    }

    /**
     * Add additional job runners to the pool.
     * Package-private for use by JobQueueScaler.
     *
     * @param count the number of runners to add
     * @since 0.9.68+
     */
    synchronized void addRunners(int count) {
        if (!_allowParallelOperation || !_alive) return;

        int currentSize = _queueRunners.size();
        int targetSize = currentSize + count;

        for (int i = currentSize; i < targetSize; i++) {
            JobQueueRunner runner = new JobQueueRunner(_context, i);
            _queueRunners.put(Integer.valueOf(i), runner);
            runner.setName("JobQueue " + _runnerId.incrementAndGet() + '/' + targetSize + " (scaled)");
            runner.start();
        }

        if (_log.shouldInfo()) {
            _log.info("Added " + count + " runners. Total: " + _queueRunners.size());
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

    /**
     * Interrupt all job runners to break potential deadlocks.
     * Used by RouterWatchdog when stuck runners are detected.
     * 
     * @return the number of runners interrupted
     * @since 0.9.68+
     */
    public int interruptAllRunners() {
        int interrupted = 0;
        for (JobQueueRunner runner : _queueRunners.values()) {
            try {
                runner.interrupt();
                interrupted++;
            } catch (SecurityException se) {
                if (_log.shouldWarn()) {
                    _log.warn("Failed to interrupt runner " + runner.getRunnerId(), se);
                }
            }
        }
        if (interrupted > 0 && _log.shouldInfo()) {
            _log.info("Interrupted " + interrupted + " job runners to break potential deadlock");
        }
        return interrupted;
    }

    /**
     * Spawn replacement runners when stuck runners are detected.
     * Adds new runners to ensure queue processing continues.
     * 
     * @param count the number of replacement runners to spawn
     * @since 0.9.68+
     */
    public void spawnReplacementRunners(int count) {
        if (!_alive || !_allowParallelOperation) return;

        // Find the next available ID
        int maxId = -1;
        for (Integer id : _queueRunners.keySet()) {
            if (id > maxId) maxId = id;
        }

        synchronized (this) {
            for (int i = 0; i < count; i++) {
                int newId = maxId + 1 + i;
                JobQueueRunner runner = new JobQueueRunner(_context, newId);
                _queueRunners.put(Integer.valueOf(newId), runner);
                runner.setName("JobQueue " + _runnerId.incrementAndGet() + " (replacement)");
                runner.start();
            }
        }

        if (_log.shouldInfo()) {
            _log.info("Spawned " + count + " replacement runners. Total: " + _queueRunners.size());
        }
    }

    /**
     * Get the number of runners that appear to be stuck (running a job for too long).
     * A runner is considered stuck if it's been running the same job for more than 60 seconds.
     * 
     * @return the number of potentially stuck runners
     * @since 0.9.68+
     */
    public int getStuckRunnerCount() {
        long now = _context.clock().now();
        long stuckThreshold = 60 * 1000; // 60 seconds
        int stuckCount = 0;

        for (JobQueueRunner runner : _queueRunners.values()) {
            if (runner.getCurrentJob() != null) {
                long beginTime = runner.getLastBegin();
                if (beginTime > 0 && (now - beginTime) > stuckThreshold) {
                    stuckCount++;
                }
            }
        }

        return stuckCount;
    }

    private final class QueuePumper implements Runnable, Clock.ClockUpdateListener, RouterClock.ClockShiftListener {
        public QueuePumper() {
            _context.clock().addUpdateListener(this);
            ((RouterClock) _context.clock()).addShiftListener(this);
        }

        public void run() {
            try {
                while (_alive) {
                    long now = _context.clock().now();
                    long timeToWait = -1;
                    try {
                        synchronized (_jobLock) {
                            for (Iterator<Job> iter = _timedJobs.iterator(); iter.hasNext(); ) {
                                Job j = iter.next();
                                long timeLeft = j.getTiming().getStartAfter() - now;
                                if (timeLeft <= 0) {
                                    // Job is ready to run now
                                    if (j instanceof JobImpl) ((JobImpl)j).madeReady(now);
                                    _readyJobs.offer(j);
                                    iter.remove();
                                } else {
                                    // Found first future job, wait until it's ready
                                    timeToWait = timeLeft;
                                    break;
                                }
                            }
                            boolean highLoad = SystemVersion.getCPULoadAvg() > 98 || SystemVersion.getCPULoad() > 98;
                            boolean isSlow = SystemVersion.isSlow();
                            if (timeToWait < 0) {timeToWait = highLoad ? 250 : 100;}
                            else if (timeToWait < 10) {timeToWait = highLoad ? 100 : 50;}
                            else if (timeToWait > 10*1000) {timeToWait = highLoad ? 12*1000 : 10*1000;}
                            else if (!isSlow && timeToWait > 2000) {timeToWait = highLoad ? 3*1000 : 2*1000;}
                            _nextPumperRun = _context.clock().now() + timeToWait;
                            _jobLock.wait(timeToWait);
                        }
                    } catch (InterruptedException ie) {}
                }
            } catch (Throwable t) {
                if (_log.shouldError()) {_log.error("Pumper killed?!", t);}
            } finally {
                _context.clock().removeUpdateListener(this);
                ((RouterClock) _context.clock()).removeShiftListener(this);
            }
        }

        public void offsetChanged(long delta) {
            updateJobTimings(delta);
            synchronized (_jobLock) {_jobLock.notifyAll();}
        }

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

    void updateStats(Job job, double duration) {
        if (_context.router() == null) return;
        String key = job.getName();
        long actualStart = job.getTiming().getActualStart();
        long scheduledStart = job.getTiming().getStartAfter();
        double lag = actualStart - scheduledStart;
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

        // Check if this is a non-critical job that can tolerate lag
        // These jobs are exploratory/testing and don't impact core router functionality
        boolean isNonCritical = isNonCriticalJob(job);

        // Track peak lag from completed jobs for sidebar display
        // Only track peak for critical jobs that actually matter
        if (!isNonCritical) {
            long now = _context.clock().now();
            if (now - _peakLagResetTime > PEAK_LAG_RESET_INTERVAL) {
                _peakLag = 0;
                _peakLagResetTime = now;
            }
            if (lag > _peakLag) {
                _peakLag = (long) lag;
            }
        }

        String dieMsg = null;
        // Convert to long for warning comparison (warnings still based on ms thresholds)
        long lagMs = (long) lag;
        long durationMs = (long) duration;
        String jobName = job.getName();

        // Skip lag warnings for non-critical jobs - they can tolerate delay
        if (lagMs > _lagWarning && !isNonCritical && !jobName.startsWith("Read")) {
            dieMsg = "Too much lag for " + jobName + " Job: " + String.format("%.3f", lag) + "ms with run time of " + String.format("%.3f", duration) + "ms";
        } else if (durationMs > _runWarning && !jobName.startsWith("Read")) {
            dieMsg = "Run too long for " + jobName + " Job: " + String.format("%.3f", lag) + "ms lag with run time of " + String.format("%.3f", duration) + "ms";
        }

        if (dieMsg != null) {
            if (_log.shouldInfo() && uptime > _warmupTime) {_log.info(dieMsg);}
            if (hist != null) hist.messageProcessingError(-1, JobQueue.class.getName(), dieMsg);
        }

        if ((lag > _lagFatal) && (uptime > _warmupTime)) {
            if (_log.shouldWarn()) {_log.log(Log.WARN, "High job lag detected (" + lag + "ms) -> Check router and network conditions");}
            return;
        }

        if ((uptime > _warmupTime) && (duration > _runFatal)) {
            if (_log.shouldWarn()) {
                _log.log(Log.WARN, "Router is incredibly overloaded (slow cpu?) or there's an error.");
            }
            return;
        }
    }

    private static final int POISON_ID = -99999;

    private static class PoisonJob implements Job {
        public String getName() {return null;}
        public long getJobId() {return POISON_ID;}
        public JobTiming getTiming() {return null;}
        public void runJob() {}
        public void dropped() {}
    }

    private static class JobComparator implements Comparator<Job>, Serializable {
         public int compare(Job l, Job r) {
             if (l.equals(r)) return 0;
             long ld = l.getTiming().getStartAfter() - r.getTiming().getStartAfter();
             if (ld < 0) return -1;
             if (ld > 0) return 1;
             ld = l.getJobId() - r.getJobId();
             if (ld < 0) return -1;
             if (ld > 0) return 1;
             return l.hashCode() - r.hashCode();
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
        return _queueRunners.size();
    }

    /**
     * Get all job statistics collected by the queue.
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
     * @return the total number of TestJob instances in the queue
     */
    public int getTestJobCount() {
        int count = 0;
        synchronized (_jobLock) {
            // Count TestJob instances in ready queues
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
            // Count TestJob instances in timed queue
            for (Job job : _timedJobs) {
                if (job instanceof TestJob) {
                    count++;
                }
            }
        }
        return count;
    }

}
