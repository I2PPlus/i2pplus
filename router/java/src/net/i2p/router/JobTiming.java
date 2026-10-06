package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might. Use at your own risk.
 *
 */

import net.i2p.util.Clock;

/**
 * Define the timing requirements and statistics for a particular job
 *
 * For use by the router only. Not to be used by applications or plugins.
 */
public class JobTiming implements Clock.ClockUpdateListener {
    private volatile long _start;
    private volatile long _actualStart;
    private volatile long _actualEnd;
    private final RouterContext _context;

    /**
     * Create a JobTiming for the given context.
     *
     * @param context the router context
     */
    public JobTiming(RouterContext context) {
        _context = context;
        _start = context.clock().now();
    }

    /**
     * # of milliseconds after the epoch to start the job
     *
     * @return the scheduled start time
     */
    public long getStartAfter() { return _start; }

    /**
     * WARNING - this does not force a resort of the job queue any more...
     * ALWAYS call JobImpl.requeue() instead if job is already queued.
     *
     * @param startTime the new start time
     */
    public void setStartAfter(long startTime) {
        _start = startTime;
    }

    /**
     * The actual start time when the job began execution.
     *
     * @return the actual start time
     */
    public long getActualStart() { return _actualStart; }

    /**
     * The actual start time when the job began execution.
     *
     * @param actualStartTime the actual start time
     */
    public void setActualStart(long actualStartTime) { _actualStart = actualStartTime; }

    /**
     * Record the actual start.
     *
     * <p>The caller supplies the timestamp because JobQueueRunner already
     * reads the clock once per job and reuses that value for the run start,
     * the queue-wait origin and the lag origin; a second read here only added
     * clock skew between the timing record and the reported lag.
     *
     * @param now the actual start time in milliseconds
     * @since 0.9.71+
     */
    public void start(long now) { _actualStart = now; }

    /**
     * The actual end time when the job finished execution.
     *
     * @return the actual end time
     */
    public long getActualEnd() { return _actualEnd; }

    /**
     * The actual end time when the job finished execution.
     *
     * @param actualEndTime the actual end time
     */
    public void setActualEnd(long actualEndTime) { _actualEnd = actualEndTime; }

    /**
     * Record the actual end.
     *
     * <p>As with {@link #start(long)}, the caller passes in a clock reading
     * it already needs, keeping the duration consistent with the timestamp
     * JobQueueRunner takes around updateStats().
     *
     * @param now the actual end time in milliseconds
     * @since 0.9.71+
     */
    public void end(long now) { _actualEnd = now; }

    /**
     * Adjust all timing values by the specified delta
     */
    public void offsetChanged(long delta) {
        if (_start != 0) _start += delta;
        if (_actualStart != 0) _actualStart += delta;
        if (_actualEnd != 0) _actualEnd += delta;
    }
}
