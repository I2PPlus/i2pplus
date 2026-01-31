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

    /**
     * Total time stored in microseconds (1/1000 of a millisecond) for sub-millisecond precision.
     * This allows accurate tracking of fast jobs without floating-point accumulation errors.
     * @since sub-millisecond precision update
     */
    private final AtomicLong _totalTimeMicros = new AtomicLong();
    private volatile long _maxTimeMicros;
    private volatile long _minTimeMicros;

    /**
     * Total pending time stored in microseconds for sub-millisecond precision.
     * @since sub-millisecond precision update
     */
    private final AtomicLong _totalPendingTimeMicros = new AtomicLong();
    private volatile long _maxPendingTimeMicros;
    private volatile long _minPendingTimeMicros;

    private volatile long _lastRunTime;

    /** Sliding window for recent job executions (last 60 seconds) @since 0.9.68+ */
    private static final int MAX_RECENT_ENTRIES = 1000;
    private static final long RECENT_WINDOW_MS = 60 * 1000; // 60 seconds
    private final RecentExecution[] _recentExecutions = new RecentExecution[MAX_RECENT_ENTRIES];
    private volatile int _recentIndex = 0;
    private volatile int _recentCount = 0;

    /** Data holder for recent execution statistics @since 0.9.68+ */
    private static class RecentExecution {
        final long timestamp;
        final long runTimeMicros;
        final long lagMicros;

        RecentExecution(long timestamp, long runTimeMicros, long lagMicros) {
            this.timestamp = timestamp;
            this.runTimeMicros = runTimeMicros;
            this.lagMicros = lagMicros;
        }
    }

    /**
     * Create statistics tracker for a named job type.
     *
     * @param name the name identifier for this job type
     */
    public JobStats(String name) {
        _job = name;
        _maxTimeMicros = -1;
        _minTimeMicros = -1;
        _maxPendingTimeMicros = -1;
        _minPendingTimeMicros = -1;
    }

    /**
     * Record that a job of this type has executed.
     * Legacy method for backward compatibility - stores millisecond precision only.
     *
     * @param runTime the time in milliseconds the job took to execute
     * @param lag the time in milliseconds the job spent waiting in the queue
     * @deprecated use {@link #jobRan(double, double)} for sub-millisecond precision
     */
    @Deprecated
    public void jobRan(long runTime, long lag) {
        jobRan((double) runTime, (double) lag);
    }

    /**
     * Record that a job of this type has executed with sub-millisecond precision.
     *
     * @param runTime the time in milliseconds the job took to execute (supports sub-millisecond values like 0.5 for 500μs)
     * @param lag the time in milliseconds the job spent waiting in the queue (supports sub-millisecond values)
     * @since sub-millisecond precision update
     */
    public void jobRan(double runTime, double lag) {
        _numRuns.incrementAndGet();

        // Convert to microseconds for storage (1 ms = 1000 μs)
        long runTimeMicros = Math.round(runTime * 1000.0);
        long lagMicros = Math.round(lag * 1000.0);

        _totalTimeMicros.addAndGet(runTimeMicros);
        if ((_maxTimeMicros < 0) || (runTimeMicros > _maxTimeMicros)) {_maxTimeMicros = runTimeMicros;}
        if ((_minTimeMicros < 0) || (runTimeMicros < _minTimeMicros)) {_minTimeMicros = runTimeMicros;}

        _totalPendingTimeMicros.addAndGet(lagMicros);
        if ((_maxPendingTimeMicros < 0) || (lagMicros > _maxPendingTimeMicros)) {_maxPendingTimeMicros = lagMicros;}
        if ((_minPendingTimeMicros < 0) || (lagMicros < _minPendingTimeMicros)) {_minPendingTimeMicros = lagMicros;}

        // Store in recent executions buffer
        long now = System.currentTimeMillis();
        _lastRunTime = now;
        int idx = _recentIndex;
        _recentExecutions[idx] = new RecentExecution(now, runTimeMicros, lagMicros);
        _recentIndex = (idx + 1) % MAX_RECENT_ENTRIES;
        if (_recentCount < MAX_RECENT_ENTRIES) {
            _recentCount++;
        }
    }

    /**
     * Get the timestamp of the most recent job execution.
     * @return timestamp in milliseconds, or 0 if never run
     * @since 0.9.68+
     */
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
     * Get the total number of times this job type has run.
     *
     * @return the run count
     */
    public long getRuns() {return _numRuns.get();}

    /**
     * Get the total execution time for all runs of this job type.
     *
     * @return total time in milliseconds
     * @deprecated use {@link #getTotalTimeDouble()} for sub-millisecond precision
     */
    @Deprecated
    public long getTotalTime() {return _totalTimeMicros.get() / 1000L;}

    /**
     * Get the total execution time for all runs of this job type with sub-millisecond precision.
     *
     * @return total time in milliseconds as a double
     * @since sub-millisecond precision update
     */
    public double getTotalTimeDouble() {return _totalTimeMicros.get() / 1000.0;}

    /**
     * Get the maximum execution time for a single run of this job type.
     *
     * @return maximum time in milliseconds, or -1 if never run
     * @deprecated use {@link #getMaxTimeDouble()} for sub-millisecond precision
     */
    @Deprecated
    public long getMaxTime() {
        long maxMicros = _maxTimeMicros;
        return maxMicros < 0 ? -1 : maxMicros / 1000L;
    }

    /**
     * Get the maximum execution time for a single run of this job type with sub-millisecond precision.
     *
     * @return maximum time in milliseconds as a double, or -1.0 if never run
     * @since sub-millisecond precision update
     */
    public double getMaxTimeDouble() {
        long maxMicros = _maxTimeMicros;
        return maxMicros < 0 ? -1.0 : maxMicros / 1000.0;
    }

    /**
     * Get the minimum execution time for a single run of this job type.
     *
     * @return minimum time in milliseconds, or -1 if never run
     * @deprecated use {@link #getMinTimeDouble()} for sub-millisecond precision
     */
    @Deprecated
    public long getMinTime() {
        long minMicros = _minTimeMicros;
        return minMicros < 0 ? -1 : minMicros / 1000L;
    }

    /**
     * Get the minimum execution time for a single run of this job type with sub-millisecond precision.
     *
     * @return minimum time in milliseconds as a double, or -1.0 if never run
     * @since sub-millisecond precision update
     */
    public double getMinTimeDouble() {
        long minMicros = _minTimeMicros;
        return minMicros < 0 ? -1.0 : minMicros / 1000.0;
    }

    /**
     * Get the average execution time for this job type.
     *
     * @return average time in milliseconds per run, or 0 if never run
     */
    public double getAvgTime() {
        long numRuns = _numRuns.get();
        if (numRuns > 0) {return (_totalTimeMicros.get() / 1000.0) / numRuns;}
        else {return 0;}
    }

    /**
     * Get the total pending/wait time for all runs of this job type.
     *
     * @return total pending time in milliseconds
     * @deprecated use {@link #getTotalPendingTimeDouble()} for sub-millisecond precision
     */
    @Deprecated
    public long getTotalPendingTime() {return _totalPendingTimeMicros.get() / 1000L;}

    /**
     * Get the total pending/wait time for all runs of this job type with sub-millisecond precision.
     *
     * @return total pending time in milliseconds as a double
     * @since sub-millisecond precision update
     */
    public double getTotalPendingTimeDouble() {return _totalPendingTimeMicros.get() / 1000.0;}

    /**
     * Get the maximum pending/wait time for a single run of this job type.
     *
     * @return maximum pending time in milliseconds, or -1 if never run
     * @deprecated use {@link #getMaxPendingTimeDouble()} for sub-millisecond precision
     */
    @Deprecated
    public long getMaxPendingTime() {
        long maxMicros = _maxPendingTimeMicros;
        return maxMicros < 0 ? -1 : maxMicros / 1000L;
    }

    /**
     * Get the maximum pending/wait time for a single run of this job type with sub-millisecond precision.
     *
     * @return maximum pending time in milliseconds as a double, or -1.0 if never run
     * @since sub-millisecond precision update
     */
    public double getMaxPendingTimeDouble() {
        long maxMicros = _maxPendingTimeMicros;
        return maxMicros < 0 ? -1.0 : maxMicros / 1000.0;
    }

    /**
     * Get the minimum pending/wait time for a single run of this job type.
     *
     * @return minimum pending time in milliseconds, or -1 if never run
     * @deprecated use {@link #getMinPendingTimeDouble()} for sub-millisecond precision
     */
    @Deprecated
    public long getMinPendingTime() {
        long minMicros = _minPendingTimeMicros;
        return minMicros < 0 ? -1 : minMicros / 1000L;
    }

    /**
     * Get the minimum pending/wait time for a single run of this job type with sub-millisecond precision.
     *
     * @return minimum pending time in milliseconds as a double, or -1.0 if never run
     * @since sub-millisecond precision update
     */
    public double getMinPendingTimeDouble() {
        long minMicros = _minPendingTimeMicros;
        return minMicros < 0 ? -1.0 : minMicros / 1000.0;
    }

    /**
     * Get the average pending/wait time for this job type.
     *
     * @return average pending time in milliseconds per run, or 0 if never run
     */
    public double getAvgPendingTime() {
        long numRuns = _numRuns.get();
        if (numRuns > 0) {return (_totalPendingTimeMicros.get() / 1000.0) / numRuns;}
        else {return 0;}
    }

    /** @return RecentStats containing aggregated stats for the last 60 seconds @since 0.9.68+ */
    public RecentStats getRecentStats() {
        long now = System.currentTimeMillis();
        long cutoff = now - RECENT_WINDOW_MS;

        long recentRuns = 0;
        long recentTotalTimeMicros = 0;
        long recentMaxTimeMicros = -1;
        long recentMinTimeMicros = -1;
        long recentTotalPendingMicros = 0;
        long recentMaxPendingMicros = -1;
        long recentMinPendingMicros = -1;

        int count = _recentCount;
        int idx = _recentIndex;

        // Iterate through recent executions (circular buffer)
        for (int i = 0; i < count; i++) {
            int actualIdx = (idx - count + i + MAX_RECENT_ENTRIES) % MAX_RECENT_ENTRIES;
            RecentExecution re = _recentExecutions[actualIdx];
            if (re == null) continue;

            // Only include if within the last 60 seconds
            if (re.timestamp >= cutoff) {
                recentRuns++;
                recentTotalTimeMicros += re.runTimeMicros;
                recentTotalPendingMicros += re.lagMicros;

                if (recentMaxTimeMicros < 0 || re.runTimeMicros > recentMaxTimeMicros) recentMaxTimeMicros = re.runTimeMicros;
                if (recentMinTimeMicros < 0 || re.runTimeMicros < recentMinTimeMicros) recentMinTimeMicros = re.runTimeMicros;
                if (recentMaxPendingMicros < 0 || re.lagMicros > recentMaxPendingMicros) recentMaxPendingMicros = re.lagMicros;
                if (recentMinPendingMicros < 0 || re.lagMicros < recentMinPendingMicros) recentMinPendingMicros = re.lagMicros;
            }
        }

        return new RecentStats(recentRuns, recentTotalTimeMicros, recentMaxTimeMicros, recentMinTimeMicros,
                              recentTotalPendingMicros, recentMaxPendingMicros, recentMinPendingMicros);
    }

    /** Container for recent statistics within the sliding window @since 0.9.68+ */
    public static class RecentStats {
        public final long runs;
        // Stored in microseconds internally, but provide getter in double ms for consistency
        private final long totalTimeMicros;
        private final long maxTimeMicros;
        private final long minTimeMicros;
        private final long totalPendingTimeMicros;
        private final long maxPendingTimeMicros;
        private final long minPendingTimeMicros;

        RecentStats(long runs, long totalTimeMicros, long maxTimeMicros, long minTimeMicros,
                   long totalPendingTimeMicros, long maxPendingTimeMicros, long minPendingTimeMicros) {
            this.runs = runs;
            this.totalTimeMicros = totalTimeMicros;
            this.maxTimeMicros = maxTimeMicros;
            this.minTimeMicros = minTimeMicros;
            this.totalPendingTimeMicros = totalPendingTimeMicros;
            this.maxPendingTimeMicros = maxPendingTimeMicros;
            this.minPendingTimeMicros = minPendingTimeMicros;
        }

        /** @return total time in milliseconds as a double */
        public double getTotalTime() {
            return totalTimeMicros / 1000.0;
        }

        /** @return maximum time in milliseconds as a double, or -1.0 if never run */
        public double getMaxTime() {
            return maxTimeMicros < 0 ? -1.0 : maxTimeMicros / 1000.0;
        }

        /** @return minimum time in milliseconds as a double, or -1.0 if never run */
        public double getMinTime() {
            return minTimeMicros < 0 ? -1.0 : minTimeMicros / 1000.0;
        }

        /** @return total pending time in milliseconds as a double */
        public double getTotalPendingTime() {
            return totalPendingTimeMicros / 1000.0;
        }

        /** @return maximum pending time in milliseconds as a double, or -1.0 if never run */
        public double getMaxPendingTime() {
            return maxPendingTimeMicros < 0 ? -1.0 : maxPendingTimeMicros / 1000.0;
        }

        /** @return minimum pending time in milliseconds as a double, or -1.0 if never run */
        public double getMinPendingTime() {
            return minPendingTimeMicros < 0 ? -1.0 : minPendingTimeMicros / 1000.0;
        }

        public double getAvgTime() {
            return runs > 0 ? (totalTimeMicros / 1000.0) / runs : 0;
        }

        public double getAvgPendingTime() {
            return runs > 0 ? (totalPendingTimeMicros / 1000.0) / runs : 0;
        }
    }

}
