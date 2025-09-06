package net.i2p.router;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import net.i2p.data.DataHelper;
import net.i2p.router.message.HandleGarlicMessageJob;
import net.i2p.router.networkdb.kademlia.ExploreJob;
import net.i2p.router.networkdb.kademlia.HandleFloodfillDatabaseLookupMessageJob;
import net.i2p.router.networkdb.kademlia.IterativeSearchJob;
import net.i2p.router.networkdb.kademlia.RepublishLeaseSetJob;
import net.i2p.router.peermanager.PeerTestJob;
import net.i2p.router.tunnel.pool.TestJob;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Manage the pending jobs according to whatever algorithm is appropriate, giving
 * preference to earlier scheduled jobs.
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

    /** How many when we go parallel */
    private static final int RUNNERS;
    static {
        long maxMemory = SystemVersion.getMaxMemory();
        int cores = SystemVersion.getCores();
        int maxRunners = 24;
        int minRunners = 12;
        RUNNERS = SystemVersion.isSlow() ? 6 : Math.max(cores / 2, 10);
    }

    /** Default max # job queue runners operating */
    private static int DEFAULT_MAX_RUNNERS = RUNNERS;
    /** router.config parameter to override the max runners */
    private final static String PROP_MAX_RUNNERS = "router.maxJobRunners";
    /** How frequently should we check and update the max runners */
    private final static long MAX_LIMIT_UPDATE_DELAY = 90*1000;
    /** If a job is this lagged, spit out a warning, but keep going */
    private final static long DEFAULT_LAG_WARNING = 5*1000;
    private long _lagWarning = DEFAULT_LAG_WARNING;
    /** If a job is this lagged, the router is hosed, so spit out a warning (don't shut it down) */
    private final static long DEFAULT_LAG_FATAL = 30*1000;
    private long _lagFatal = DEFAULT_LAG_FATAL;
    /** If a job takes this long to run, spit out a warning, but keep going */
    private final static long DEFAULT_RUN_WARNING = 5*1000;
    private long _runWarning = DEFAULT_RUN_WARNING;
    /** If a job takes this long to run, the router is hosed, so spit out a warning (don't shut it down) */
    private final static long DEFAULT_RUN_FATAL = 30*1000;
    private long _runFatal = DEFAULT_RUN_FATAL;
    /** Don't enforce fatal limits until the router has been up for this long */
    private final static long DEFAULT_WARMUP_TIME = 15*60*1000;
    private long _warmupTime = DEFAULT_WARMUP_TIME;
    /** Max ready and waiting jobs before we start dropping 'em */
    private final static int DEFAULT_MAX_WAITING_JOBS = SystemVersion.isSlow() ? 100 : 300;
    private int _maxWaitingJobs = DEFAULT_MAX_WAITING_JOBS;
    private final static long MIN_LAG_TO_DROP = SystemVersion.isSlow() ? 1500 : 1000;

    /**
     *  @since 0.9.52+
     */
    private final static String PROP_MAX_WAITING_JOBS = "router.maxWaitingJobs";

    /**
     * Queue runners wait on this whenever they're not doing anything, and
     * this gets notified *once* whenever there are ready jobs
     */
    private final Object _runnerLock = new Object();

    private static final long[] RATES = {60*1000, 10*60*1000l, 60*60*1000l, 24*60*60*1000l};

    /**
     *  Does not start the pumper. Caller MUST call startup.
     */
    public JobQueue(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(JobQueue.class);

        _context.statManager().createRateStat("jobQueue.droppedJobs", "Scheduled jobs dropped due to insane overload", "JobQueue", RATES);
        _context.statManager().createRateStat("jobQueue.queuedJobs", "Scheduled jobs in queue", "JobQueue", RATES);
        _context.statManager().createRateStat("jobQueue.readyJobs", "Ready and waiting scheduled jobs", "JobQueue", RATES);
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
                if (removed && _log.shouldWarn()) {_log.warn(job + " rescheduled");}

                if (shouldDrop(job, numReady)) {
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
            _highPriorityJobs.offer(job);
        }

        // Wake up runners in case they're waiting
        synchronized (_runnerLock) {
            _runnerLock.notify();
        }
    }

    public void removeJob(Job job) {
        synchronized (_jobLock) {
            boolean removed = _timedJobs.remove(job);
            if (!removed) {_readyJobs.remove(job); _highPriorityJobs.remove(job);}
        }
    }

    public int getReadyCount() {
        return _readyJobs.size() + _highPriorityJobs.size();
    }

    public long getMaxLag() {
        Job j = _readyJobs.peek();
        if (j == null) j = _highPriorityJobs.peek();
        if (j == null) return 0;
        JobTiming jt = j.getTiming();
        if (jt == null) return 0;
        long startAfter = jt.getStartAfter();
        return _context.clock().now() - startAfter;
    }

    private boolean shouldDrop(Job job, long numReady) {
        if (_maxWaitingJobs <= 0) return false;
        if (!_allowParallelOperation) return false;
        if (numReady > _context.getProperty(PROP_MAX_WAITING_JOBS, DEFAULT_MAX_WAITING_JOBS)) {
            Class<? extends Job> cls = job.getClass();
            boolean disableTunnelTests = _context.getBooleanProperty("router.disableTunnelTesting");
            boolean shouldDrop = getMaxLag() >= MIN_LAG_TO_DROP;
            if (shouldDrop) {
                if (cls == RepublishLeaseSetJob.class) {return false;}
                if ((!disableTunnelTests && cls == TestJob.class) || cls == PeerTestJob.class) {
                    return true;
                }
                if ((!disableTunnelTests && cls == TestJob.class) ||
                    cls == PeerTestJob.class ||
                    cls == ExploreJob.class ||
                    cls == HandleFloodfillDatabaseLookupMessageJob.class ||
                    cls == HandleGarlicMessageJob.class ||
                    cls == IterativeSearchJob.class) {
                    return true;
                }
            }
        }
        return false;
    }

    public void allowParallelOperation() {
        _allowParallelOperation = true;
        runQueue(_context.getProperty(PROP_MAX_RUNNERS, RUNNERS));
    }

    public void startup() {
        _alive = true;
        I2PThread pumperThread = new I2PThread(_pumper, "JobQueuePumper", true);
        pumperThread.setPriority(I2PThread.MAX_PRIORITY);
        pumperThread.start();
    }

    void shutdown() {
        _alive = false;
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

    boolean isAlive() {return _alive;}

    public long getLastJobBegin() {
        long when = -1;
        for (JobQueueRunner runner : _queueRunners.values()) {
            long cur = runner.getLastBegin();
            if (cur > when) {when = cur;}
        }
        return when;
    }

    public long getLastJobEnd() {
        long when = -1;
        for (JobQueueRunner runner : _queueRunners.values()) {
            long cur = runner.getLastEnd();
            if (cur > when) {when = cur;}
        }
        return when;
    }

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

    void removeRunner(int id) {_queueRunners.remove(Integer.valueOf(id));}

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
                            Job lastJob = null;
                            long lastTime = Long.MIN_VALUE;
                            for (Iterator<Job> iter = _timedJobs.iterator(); iter.hasNext(); ) {
                                Job j = iter.next();
                                long timeLeft = j.getTiming().getStartAfter() - now;
                                if (lastJob != null && lastTime > j.getTiming().getStartAfter() && _log.shouldInfo()) {
                                    _log.info(lastJob + " out of order with " + j + "\n* Difference: " +
                                              DataHelper.formatDuration(lastTime - j.getTiming().getStartAfter()));
                                }
                                lastJob = j;
                                lastTime = lastJob.getTiming().getStartAfter();
                                if (timeLeft <= 0) {
                                    if (j instanceof JobImpl) ((JobImpl)j).madeReady(now);
                                    _readyJobs.offer(j);
                                    iter.remove();
                                } else {
                                    timeToWait = timeLeft;
                                    if (timeToWait > 10*1000 && iter.hasNext()) {
                                        if (_log.shouldInfo()) {
                                            _log.info(j + " deferred for " + DataHelper.formatDuration(timeToWait));
                                        }
                                        iter.remove();
                                        Job nextJob = iter.next();
                                        _timedJobs.add(j);
                                        long nextTimeLeft = nextJob.getTiming().getStartAfter() - now;
                                        if (timeToWait > nextTimeLeft) {
                                            if (_log.shouldInfo()) {
                                                _log.info(j + " out of order with " + nextJob + "\n* Difference: " +
                                                          DataHelper.formatDuration(timeToWait - nextTimeLeft));
                                            }
                                            timeToWait = Math.max(10, nextTimeLeft);
                                        }
                                    }
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

    void updateStats(Job job, long doStart, long origStartAfter, long duration) {
        if (_context.router() == null) return;
        String key = job.getName();
        long lag = doStart - origStartAfter;
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

        @SuppressWarnings("deprecation")
        public Exception getAddedBy() {return null;}

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

    public Collection<JobStats> getJobStats() {
        return Collections.unmodifiableCollection(_jobStats.values());
    }

}
