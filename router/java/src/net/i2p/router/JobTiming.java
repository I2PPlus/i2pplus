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

    /**
     * Nanosecond precision timing for execution duration tracking.
     * System.nanoTime() provides high-resolution timing independent of system clock changes.
     * @since sub-millisecond precision update
     */
    private AtomicLong _actualStartNanos;
    private AtomicLong _actualEndNanos;

    public JobTiming(RouterContext context) {
        _context = context;
        _start = new AtomicLong(context.clock().now());
        _actualStart = new AtomicLong(0);
        _actualEnd = new AtomicLong(0);
        _actualStartNanos = new AtomicLong(0);
        _actualEndNanos = new AtomicLong(0);
    }

    /**
     * Get the scheduled start time for the job.
     *
     * @return milliseconds after the epoch when the job should start
     */
    public long getStartAfter() {return _start.get();}

    /**
     * Set the scheduled start time for the job.
     * WARNING: This does not force a resort of the job queue.
     * Always call JobImpl.requeue() instead if the job is already queued.
     *
     * @param startTime milliseconds after the epoch when the job should start
     */
    public void setStartAfter(long startTime) {_start.set(startTime);}

    /**
     * Get the actual start time when the job began execution.
     *
     * @return milliseconds after the epoch when the job actually started, or 0 if not yet started
     */
    public long getActualStart() {return _actualStart.get();}

    /**
     * Set the actual start time when the job began execution.
     *
     * @param actualStartTime milliseconds after the epoch when the job actually started
     */
    public void setActualStart(long actualStartTime) {_actualStart.set(actualStartTime);}

    /**
     * Get the nanosecond-precision start time when the job began execution.
     *
     * @return nanoseconds from System.nanoTime() when the job actually started, or 0 if not yet started
     * @since sub-millisecond precision update
     */
    public long getActualStartNanos() {return _actualStartNanos.get();}

    /**
     * Mark the job as started, recording the current time as the actual start time.
     * Uses the router context clock for consistency.
     * Also records high-precision nanosecond timestamp for accurate duration calculation.
     */
    public void start() {
        _actualStart.set(_context.clock().now());
        _actualStartNanos.set(System.nanoTime());
    }

    /**
     * Get the actual end time when the job finished execution.
     *
     * @return milliseconds after the epoch when the job actually ended, or 0 if not yet ended
     */
    public long getActualEnd() {return _actualEnd.get();}

    /**
     * Set the actual end time when the job finished execution.
     *
     * @param actualEndTime milliseconds after the epoch when the job actually ended
     */
    public void setActualEnd(long actualEndTime) {_actualEnd.set(actualEndTime);}

    /**
     * Get the nanosecond-precision end time when the job finished execution.
     *
     * @return nanoseconds from System.nanoTime() when the job actually ended, or 0 if not yet ended
     * @since sub-millisecond precision update
     */
    public long getActualEndNanos() {return _actualEndNanos.get();}

    /**
     * Mark the job as finished, recording the current time as the actual end time.
     * Uses the router context clock for consistency.
     * Also records high-precision nanosecond timestamp for accurate duration calculation.
     */
    public void end() {
        _actualEnd.set(_context.clock().now());
        _actualEndNanos.set(System.nanoTime());
    }

    /**
     * Get the execution duration in milliseconds with sub-millisecond precision.
     * Uses nanosecond timing for accurate measurement of short-running jobs.
     *
     * @return duration in milliseconds as a double (e.g., 0.5 for 500 microseconds),
     *         or 0.0 if job hasn't started or finished
     * @since sub-millisecond precision update
     */
    public double getDurationMillis() {
        long startNanos = _actualStartNanos.get();
        long endNanos = _actualEndNanos.get();
        if (startNanos == 0 || endNanos == 0) {
            return 0.0;
        }
        return (endNanos - startNanos) / 1_000_000.0;
    }

    /**
     * Get the pending/wait time in milliseconds with sub-millisecond precision.
     * This is the time between when the job was scheduled to start and when it actually started.
     *
     * @return pending time in milliseconds as a double, or 0.0 if not yet started
     * @since sub-millisecond precision update
     */
    public double getPendingMillis() {
        long startNanos = _actualStartNanos.get();
        if (startNanos == 0) {
            return 0.0;
        }
        long scheduledStart = _start.get();
        // Convert scheduled time (ms) to a comparable nanosecond value
        // We use the relationship between System.currentTimeMillis() and System.nanoTime()
        long currentMillis = System.currentTimeMillis();
        long currentNanos = System.nanoTime();
        long scheduledNanos = currentNanos - ((currentMillis - scheduledStart) * 1_000_000L);
        double pendingNanos = startNanos - scheduledNanos;
        return pendingNanos / 1_000_000.0;
    }

    /**
     * Adjust all timing values by the specified delta.
     * Used when the router clock is adjusted (e.g., NTP sync).
     *
     * @param delta milliseconds to add to all timing values (positive or negative)
     */
    public void offsetChanged(long delta) {
        if (_start.get() != 0) {_start.addAndGet(delta);}
        if (_actualStart.get() != 0) {_actualStart.addAndGet(delta);}
        if (_actualEnd.get() != 0) {_actualEnd.addAndGet(delta);}
    }

}
