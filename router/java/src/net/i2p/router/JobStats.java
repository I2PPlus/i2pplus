package net.i2p.router;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects and maintains statistical data for job execution performance. Tracks run counts, execution times, drop rates, and pending time metrics for job queue monitoring and optimization.
 * Public for router console only.
 * For use by the router only. Not to be used by applications or plugins.
 */
public class JobStats {
    private final String _job;
    private final AtomicLong _numRuns = new AtomicLong();
    private final AtomicLong _numDropped = new AtomicLong();
    private final AtomicLong _totalTime = new AtomicLong();
    private volatile long _maxTime;
    private volatile long _minTime;
    private final AtomicLong _totalPendingTime = new AtomicLong();
    private volatile long _maxPendingTime;
    private volatile long _minPendingTime;

    /**
     * Create statistics tracker for a named job type.
     *
     * @param name the name identifier for this job type
     */
    public JobStats(String name) {
        _job = name;
        _maxTime = -1;
        _minTime = -1;
        _maxPendingTime = -1;
        _minPendingTime = -1;
    }

    /**
     * Record that a job of this type has executed.
     *
     * @param runTime the time in milliseconds the job took to execute
     * @param lag the time in milliseconds the job spent waiting in the queue
     */
    public void jobRan(long runTime, long lag) {
        _numRuns.incrementAndGet();
        _totalTime.addAndGet(runTime);
        if ((_maxTime < 0) || (runTime > _maxTime)) {_maxTime = runTime;}
        if ((_minTime < 0) || (runTime < _minTime)) {_minTime = runTime;}
        _totalPendingTime.addAndGet(lag);
        if ((_maxPendingTime < 0) || (lag > _maxPendingTime)) {_maxPendingTime = lag;}
        if ((_minPendingTime < 0) || (lag < _minPendingTime)) {_minPendingTime = lag;}
    }

    /**
     * Record that a job of this type was dropped due to overload.
     */
    public void jobDropped() {_numDropped.incrementAndGet();}

    /**
     * Get the number of jobs that were dropped.
     *
     * @return the count of dropped jobs
     * @since 0.9.19
     */
    public long getDropped() {return _numDropped.get();}

    /**
     * Get the name of this job type.
     *
     * @return the job name
     */
    public String getName() {return _job;}

    /**
     * Get the total number of times this job type has run.
     *
     * @return the run count
     */
    public long getRuns() {return _numRuns.get();}

    /**
     * Get the total execution time for all runs of this job type.
     *
     * @return total time in milliseconds
     */
    public long getTotalTime() {return _totalTime.get();}

    /**
     * Get the maximum execution time for a single run of this job type.
     *
     * @return maximum time in milliseconds, or -1 if never run
     */
    public long getMaxTime() {return _maxTime;}

    /**
     * Get the minimum execution time for a single run of this job type.
     *
     * @return minimum time in milliseconds, or -1 if never run
     */
    public long getMinTime() {return _minTime;}

    /**
     * Get the average execution time for this job type.
     *
     * @return average time in milliseconds per run, or 0 if never run
     */
    public double getAvgTime() {
        long numRuns = _numRuns.get();
        if (numRuns > 0) {return _totalTime.get() / (double) numRuns;}
        else {return 0;}
    }
    /**
     * Get the total pending/wait time for all runs of this job type.
     *
     * @return total pending time in milliseconds
     */
    public long getTotalPendingTime() {return _totalPendingTime.get();}

    /**
     * Get the maximum pending/wait time for a single run of this job type.
     *
     * @return maximum pending time in milliseconds, or -1 if never run
     */
    public long getMaxPendingTime() {return _maxPendingTime;}

    /**
     * Get the minimum pending/wait time for a single run of this job type.
     *
     * @return minimum pending time in milliseconds, or -1 if never run
     */
    public long getMinPendingTime() {return _minPendingTime;}

    /**
     * Get the average pending/wait time for this job type.
     *
     * @return average pending time in milliseconds per run, or 0 if never run
     */
    public double getAvgPendingTime() {
        long numRuns = _numRuns.get();
        if (numRuns > 0) {return _totalPendingTime.get() / (double) numRuns;}
        else {return 0;}
    }

}
