package net.i2p.router;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
  Worker thread that executes jobs from the router job queue.
  Handles job execution, timing collection, error handling,
  and performance monitoring for individual job processing threads.
*/
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
        setPriority(MAX_PRIORITY);
        // all createRateStat in JobQueue
    }

    public Job getCurrentJob() {return _currentJob;}
    public Job getLastJob() {return _lastJob;}
    public int getRunnerId() {return _id;}
    public void stopRunning() {_keepRunning = false;}
    public void startRunning() {_keepRunning = true;}
    public long getLastBegin() {return _lastBegin;}
    public long getLastEnd() {return _lastEnd;}

    public void run() {
        long lastActive = _context.clock().now();
        while (_keepRunning && _context.jobQueue().isAlive()) {
            try {
                Job job = _context.jobQueue().getNext();
                if (job == null) {
                    if (_context.router().isAlive() && _log.shouldError()) {
                        _log.error("Failed to pull next job from queue -> Dead?");
                    }
                    continue;
                }
                long now = _context.clock().now();

                long enqueuedTime = 0;
                if (job instanceof JobImpl) {
                    long when = ((JobImpl)job).getMadeReadyOn();
                    if (when <= 0) {
                        _log.error("Job was not made ready?! " + job, new Exception("Not made ready?!"));
                    } else {enqueuedTime = now - when;}
                }

                _currentJob = job;
                _lastJob = null;
                if (_log.shouldDebug()) {
                    _log.debug("[Job " + job.getJobId() + "] " + job.getName() + " -> [Runner " + _id + "] running");
                }
                long origStartAfter = job.getTiming().getStartAfter();
                long doStart = _context.clock().now();
                job.getTiming().start();
                runCurrentJob();
                job.getTiming().end();

                // Use nanosecond-precision duration calculation for sub-millisecond accuracy
                double duration = job.getTiming().getDurationMillis();
                long beforeUpdate = _context.clock().now();
                _context.jobQueue().updateStats(job, doStart, origStartAfter, duration);
                long diff = _context.clock().now() - beforeUpdate;

                // Calculate lag with sub-millisecond precision
                double lag = job.getTiming().getPendingMillis();
                if (lag < 0) {lag = 0;}

                // Cast to long for rate statistics (they don't need sub-millisecond precision)
                _context.statManager().addRateData("jobQueue.jobRun", (long) duration, (long) duration);
                _context.statManager().addRateData("jobQueue.jobLag", (long) lag);
                _context.statManager().addRateData("jobQueue.jobWait", enqueuedTime, enqueuedTime);

                if (duration > 1500) {
                    _context.statManager().addRateData("jobQueue.jobRunSlow", (long) duration, (long) duration);
                    if (_log.shouldWarn() && lag > 100) {
                        _log.warn(_currentJob + " completed in " + String.format("%.3f", duration) + "ms -> Lag: " + String.format("%.3f", lag) + "ms");
                    } else if (_log.shouldInfo()) {
                        _log.warn(_currentJob + " completed in " + String.format("%.3f", duration) + "ms");
                    }
                }

                if (diff > 1000) {
                    if (_log.shouldWarn()) {
                        _log.warn("Updating stats for '" + job.getName() + "' took too long (" + diff + "ms)");
                    }
                }

                lastActive = _context.clock().now();
                _lastJob = _currentJob;
                _currentJob = null;
                _lastEnd = lastActive;
            } catch (Throwable t) {_log.log(Log.CRIT, "Error running?", t);}
        }
        if (_context.router().isAlive()) {_log.log(Log.CRIT, "Queue runner " + _id + " exiting");}
        _context.jobQueue().removeRunner(_id);
    }

    private void runCurrentJob() {
        try {
            _lastBegin = _context.clock().now();
            if (_currentJob != null) {_currentJob.runJob();}
        } catch (OutOfMemoryError oom) {
            try {
                if (SystemVersion.isAndroid()) {_context.router().shutdown(Router.EXIT_OOM);}
                else {fireOOM(oom);}
            } catch (Throwable t) {}
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Error processing job [" + _currentJob.getName() + "] on thread " + _id, t);
        }
    }

}
