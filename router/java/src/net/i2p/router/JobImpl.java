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

/**
 * Base implementation of a Job
 *
 * For use by the router only. Not to be used by applications or plugins.
 */
public abstract class JobImpl implements Job {
    private final RouterContext _context;
    private final JobTiming _timing;
    private static final AtomicLong _idSrc = new AtomicLong();
    // make this public so we can reference the job number for tunnel builds on /jobs
    public final long _id;
    private volatile long _madeReadyOn;

    public JobImpl(RouterContext context) {
        _context = context;
        _timing = new JobTiming(context);
        _id = _idSrc.incrementAndGet();
    }

    /**
     * Get the unique job ID for this job.
     *
     * @return the unique job identifier
     */
    public long getJobId() { return _id; }

    /**
     * Get the timing object for this job.
     *
     * @return the JobTiming object controlling when this job runs
     */
    public JobTiming getTiming() { return _timing; }

    /**
     * Get the router context that this job belongs to.
     *
     * @return the RouterContext for this job
     */
    public final RouterContext getContext() { return _context; }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("[Job ").append(_id).append("] ")
           .append(getClass().getSimpleName());
        return buf.toString();
    }

    /**
     * Get the timestamp when this job was last made ready for execution.
     *
     * @return timestamp in milliseconds when job was made ready, or 0 if not yet ready
     */
    public long getMadeReadyOn() { return _madeReadyOn; }

    /**
     *  Deprecated to avoid JobQueue deadlocks
     *  @deprecated use madeReady(long)
     */
    @Deprecated
    public void madeReady() { _madeReadyOn = _context.clock().now(); }

    /**
     *  For JobQueue only, not for external use
     *  @param now the current time in milliseconds
     *  @since 0.9.55
     */
    public void madeReady(long now) { _madeReadyOn = now; }


    /**
     * Default implementation does nothing.
     * Called when the job is dropped due to router overload.
     * Subclasses can override to perform cleanup or requeueing of other tasks.
     */
    public void dropped() {}

    /**
     * Requeue this job to run after the specified delay.
     * Warning - only call this from runJob() or if the Job is not already queued,
     * otherwise it may get the job queue out of order.
     *
     * @param delayMs the delay in milliseconds before the job should run again
     */
    protected void requeue(long delayMs) {
        getTiming().setStartAfter(_context.clock().now() + delayMs);
        _context.jobQueue().addJob(this);
    }
}
