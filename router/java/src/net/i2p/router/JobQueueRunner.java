package net.i2p.router;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/** a do run run run a do run run */
class JobQueueRunner extends I2PThread {
    private final Log _log;
    private final RouterContext _context;
    private volatile boolean _keepRunning;
    private final int _id;
    private volatile Job _currentJob;
    private volatile Job _lastJob;
    private volatile long _lastBegin;
    private volatile long _lastEnd;

    public JobQueueRunner(RouterContext context, int id) {
        _context = context;
        _id = id;
        _keepRunning = true;
        _log = _context.logManager().getLog(JobQueueRunner.class);
//        setPriority(NORM_PRIORITY + 1);
        setPriority(MAX_PRIORITY - 1);
        // all createRateStat in JobQueue
    }

    public Job getCurrentJob() { return _currentJob; }
    public Job getLastJob() { return _lastJob; }
    public int getRunnerId() { return _id; }
    public void stopRunning() { _keepRunning = false; }
    public void startRunning() { _keepRunning = true; }
    public long getLastBegin() { return _lastBegin; }
    public long getLastEnd() { return _lastEnd; }

    public void run() {
        long lastActive = _context.clock().now();
        while (_keepRunning && _context.jobQueue().isAlive()) {
            try {
                Job job = _context.jobQueue().getNext();
                if (job == null) {
                    if (_context.router().isAlive())
                        if (_log.shouldError())
                            _log.error("getNext returned null - dead?");
                    continue;
                }
                long now = _context.clock().now();

                long enqueuedTime = 0;
                if (job instanceof JobImpl) {
                    long when = ((JobImpl)job).getMadeReadyOn();
                    if (when <= 0) {
                        _log.error("Job was not made ready?! " + job,
                                   new Exception("Not made ready?!"));
                    } else {
                        enqueuedTime = now - when;
                    }
                }

                _currentJob = job;
                _lastJob = null;
                if (_log.shouldDebug())
                    _log.debug("[Job " + job.getJobId() + "] " + job.getName() + " -> [Runner " + _id + "] running");
                long origStartAfter = job.getTiming().getStartAfter();
                long doStart = _context.clock().now();
                job.getTiming().start();
                runCurrentJob();
                job.getTiming().end();
                long duration = job.getTiming().getActualEnd() - job.getTiming().getActualStart();
                long beforeUpdate = _context.clock().now();
                _context.jobQueue().updateStats(job, doStart, origStartAfter, duration);
                long diff = _context.clock().now() - beforeUpdate;

                long lag = doStart - origStartAfter;
                if (lag < 0) lag = 0;

                _context.statManager().addRateData("jobQueue.jobRun", duration, duration);
                _context.statManager().addRateData("jobQueue.jobLag", lag);
                _context.statManager().addRateData("jobQueue.jobWait", enqueuedTime, enqueuedTime);

//                if (duration > 1000) {
                if (duration > 1500) {
                    _context.statManager().addRateData("jobQueue.jobRunSlow", duration, duration);
                    if (_log.shouldWarn())
                        if (doStart-origStartAfter > 0)
                            _log.warn(_currentJob + " completed in " + duration + "ms -> Lag: " + (doStart-origStartAfter) + "ms");
                        else
                            _log.warn(_currentJob + " completed in " + duration + "ms");
                }

//                if (diff > 100) {
                if (diff > 1000) {
                    if (_log.shouldWarn())
                        _log.warn("Updating stats for '" + job.getName() + "' took too long (" + diff + "ms)");
                }
                if (_log.shouldInfo())
                    if (doStart-origStartAfter > 0)
                        _log.info("[Job " + job.getJobId() + "] " + job.getName() + " completed in " + duration + "ms -> Lag: " + (doStart-origStartAfter) + "ms");
                    else
                        _log.info("[Job " + job.getJobId() + "] " + job.getName() + " completed in " + duration + "ms");
                lastActive = _context.clock().now();
                _lastJob = _currentJob;
                _currentJob = null;
                _lastEnd = lastActive;
            } catch (Throwable t) {
                _log.log(Log.CRIT, "Error running?", t);
            }
        }
        if (_context.router().isAlive())
            _log.log(Log.CRIT, "Queue runner " + _id + " exiting");
        _context.jobQueue().removeRunner(_id);
    }

    private void runCurrentJob() {
        try {
            _lastBegin = _context.clock().now();
            if (_currentJob != null)
                _currentJob.runJob();
        } catch (OutOfMemoryError oom) {
            try {
                if (SystemVersion.isAndroid())
                    _context.router().shutdown(Router.EXIT_OOM);
                else
                    fireOOM(oom);
            } catch (Throwable t) {}
        } catch (Throwable t) {
//            _log.log(Log.CRIT, "Error processing job [" + _currentJob.getName() + "] on thread " + _id + "\n* " + t, t);
            _log.log(Log.CRIT, "Error processing job [" + _currentJob.getName() + "] on thread " + _id, t);
        }
    }
}
