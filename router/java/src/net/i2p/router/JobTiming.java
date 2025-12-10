package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.concurrent.atomic.AtomicLong;
import net.i2p.util.Clock;

/**
 * Define the timing requirements and statistics for a particular job
 *
 * For use by the router only. Not to be used by applications or plugins.
 */
public class JobTiming implements Clock.ClockUpdateListener {
    private AtomicLong _start;
    private AtomicLong _actualStart;
    private AtomicLong _actualEnd;
    private final RouterContext _context;

    public JobTiming(RouterContext context) {
        _context = context;
        _start = new AtomicLong(context.clock().now());
        _actualStart = new AtomicLong(0);
        _actualEnd = new AtomicLong(0);
    }

    /** Number of milliseconds after the epoch to start the job */
    public long getStartAfter() {return _start.get();}

    /**
     * WARNING - this does not force a resort of the job queue any more...
     * ALWAYS call JobImpl.requeue() instead if job is already queued.
     */
    public void setStartAfter(long startTime) {_start.set(startTime);}

    /** Number of milliseconds after the epoch the job actually started */
    public long getActualStart() {return _actualStart.get();}

    public void setActualStart(long actualStartTime) {_actualStart.set(actualStartTime);}
    /** Notify the timing that the job began */
    public void start() {_actualStart.set(_context.clock().now());}

    /** Number of milliseconds after the epoch the job actually ended */
    public long getActualEnd() {return _actualEnd.get();}

    public void setActualEnd(long actualEndTime) {_actualEnd.set(actualEndTime);}

    /** Notify the timing that the job finished */
    public void end() {_actualEnd.set(_context.clock().now());}

    public void offsetChanged(long delta) {
        if (_start.get() != 0) {_start.addAndGet(delta);}
        if (_actualStart.get() != 0) {_actualStart.addAndGet(delta);}
        if (_actualEnd.get() != 0) {_actualEnd.addAndGet(delta);}
    }

}
