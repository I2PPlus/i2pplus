package net.i2p.router;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
    private volatile long _lastRunTime;

    private static final int DEFAULT_MAX_RECENT_ENTRIES = 500;
    private static final int HIGH_FREQ_MAX_RECENT_ENTRIES = 5000;
    private static final Set<String> HIGH_FREQ_JOBS = new HashSet<>(Arrays.asList(
        "NetDb Direct RouterInfo Lookup",
        "Direct Lookup Match",
        "Verify NetDb Lookup for Failing Peer"
    ));
    private static final long RECENT_WINDOW_MS = 10 * 1000;

    private static volatile boolean _recentTrackingEnabled;
    private static volatile long _lastTrackingEnableTime;
    private static final long TRACKING_TIMEOUT_MS = 60 * 1000;  // Keep tracking for 60s after last view

    private final int _maxRecentEntries;
    private final RecentExecution[] _recentExecutions;
    private volatile int _recentIndex = 0;
    private final AtomicInteger _recentCount = new AtomicInteger();

    private static class RecentExecution {
        final long timestamp;
        final long runTime;
        final long lag;

        RecentExecution(long timestamp, long runTime, long lag) {
            this.timestamp = timestamp;
            this.runTime = runTime;
            this.lag = lag;
        }
    }

    /**
     * Enable recent execution tracking for a period of time.
     * Call this when rendering the /jobs page.
     * Tracking will remain enabled for 60 seconds after last enable.
     */
    public static void enableRecentTracking() {
        _recentTrackingEnabled = true;
        _lastTrackingEnableTime = System.currentTimeMillis();
    }

    /**
     * Check if recent tracking should be active.
     * Returns true if enabled and within timeout period.
     */
    public static boolean isRecentTrackingEnabled() {
        if (!_recentTrackingEnabled) return false;
        // Auto-disable after timeout
        if (System.currentTimeMillis() - _lastTrackingEnableTime > TRACKING_TIMEOUT_MS) {
            _recentTrackingEnabled = false;
            return false;
        }
        return true;
    }

    /**
     * Force disable recent tracking immediately.
     */
    public static void disableRecentTracking() {
        _recentTrackingEnabled = false;
    }

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
        _maxRecentEntries = HIGH_FREQ_JOBS.contains(name) ? HIGH_FREQ_MAX_RECENT_ENTRIES : DEFAULT_MAX_RECENT_ENTRIES;
        _recentExecutions = new RecentExecution[_maxRecentEntries];
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

        long now = System.currentTimeMillis();
        _lastRunTime = now;

        if (isRecentTrackingEnabled()) {
            int idx = _recentIndex;
            _recentExecutions[idx] = new RecentExecution(now, runTime, lag);
            _recentIndex = (idx + 1) % _maxRecentEntries;
            if (_recentCount.get() < _maxRecentEntries) {
                _recentCount.incrementAndGet();
            }
        }
    }

    public long getLastRunTime() {return _lastRunTime;}

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
     * Get the maximum number of recent execution entries tracked.
     *
     * @return the max recent entries limit
     */
    public int getMaxRecentEntries() {return _maxRecentEntries;}

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

    public RecentStats getRecentStats() {
        long now = System.currentTimeMillis();
        long cutoff = now - RECENT_WINDOW_MS;

        long recentRuns = 0;
        long recentTotalTime = 0;
        long recentMaxTime = -1;
        long recentMinTime = -1;
        long recentTotalPending = 0;
        long recentMaxPending = -1;
        long recentMinPending = -1;

        int count = _recentCount.get();
        int idx = _recentIndex;

        for (int i = 0; i < count; i++) {
            int actualIdx = (idx - count + i + _maxRecentEntries) % _maxRecentEntries;
            RecentExecution re = _recentExecutions[actualIdx];
            if (re == null) continue;

            if (re.timestamp >= cutoff) {
                recentRuns++;
                recentTotalTime += re.runTime;
                recentTotalPending += re.lag;

                if (recentMaxTime < 0 || re.runTime > recentMaxTime) recentMaxTime = re.runTime;
                if (recentMinTime < 0 || re.runTime < recentMinTime) recentMinTime = re.runTime;
                if (recentMaxPending < 0 || re.lag > recentMaxPending) recentMaxPending = re.lag;
                if (recentMinPending < 0 || re.lag < recentMinPending) recentMinPending = re.lag;
            }
        }

        return new RecentStats(recentRuns, recentTotalTime, recentMaxTime, recentMinTime,
                              recentTotalPending, recentMaxPending, recentMinPending);
    }

    public static class RecentStats {
        public final long runs;
        public final long totalTime;
        public final long maxTime;
        public final long minTime;
        public final long totalPendingTime;
        public final long maxPendingTime;
        public final long minPendingTime;

        RecentStats(long runs, long totalTime, long maxTime, long minTime,
                   long totalPendingTime, long maxPendingTime, long minPendingTime) {
            this.runs = runs;
            this.totalTime = totalTime;
            this.maxTime = maxTime;
            this.minTime = minTime;
            this.totalPendingTime = totalPendingTime;
            this.maxPendingTime = maxPendingTime;
            this.minPendingTime = minPendingTime;
        }

        public double getAvgTime() {
            return runs > 0 ? totalTime / (double) runs : 0;
        }

        public double getAvgPendingTime() {
            return runs > 0 ? totalPendingTime / (double) runs : 0;
        }
    }

}
