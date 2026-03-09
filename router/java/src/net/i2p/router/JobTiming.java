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

    public JobTiming(RouterContext context) {
        _context = context;
        _start = context.clock().now();
    }

    /**
     * # of milliseconds after the epoch to start the job
     */
    public long getStartAfter() { return _start; }

    /**
     * WARNING - this does not force a resort of the job queue any more...
     * ALWAYS call JobImpl.requeue() instead if job is already queued.
     */
    public void setStartAfter(long startTime) { 
        _start = startTime; 
    }

    /**
     * Get the actual start time when the job began execution.
     */
    public long getActualStart() { return _actualStart; }

    /**
     * Set the actual start time when the job began execution.
     */
    public void setActualStart(long actualStartTime) { _actualStart = actualStartTime; }
    public void start() { _actualStart = _context.clock().now(); }

    /**
     * Get the actual end time when the job finished execution.
     */
    public long getActualEnd() { return _actualEnd; }

    /**
     * Set the actual end time when the job finished execution.
     */
    public void setActualEnd(long actualEndTime) { _actualEnd = actualEndTime; }
    public void end() { _actualEnd = _context.clock().now(); }

    /**
     * Adjust all timing values by the specified delta
     */
    public void offsetChanged(long delta) {
        if (_start != 0) _start += delta;
        if (_actualStart != 0) _actualStart += delta;
        if (_actualEnd != 0) _actualEnd += delta;
    }
}
