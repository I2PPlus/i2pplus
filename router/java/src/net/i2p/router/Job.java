package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */


/**
 * Interface for router-internal executable tasks. Defines job timing, execution, and failure handling for scheduled operations within the job queue system.
 *
 * For use by the router only. Not to be used by applications or plugins.
 */
public interface Job {
    /**
     * Descriptive name of the task
     * @return the descriptive name of the task
     */
    public String getName();
    /**
     * Unique id for this job.
     * @return the unique job id
     */
    public long getJobId();
    /**
     * Timing criteria for the task
     * @return the job timing object
     */
    public JobTiming getTiming();
    /**
     * Actually perform the task.  This call blocks until the Job is complete.
     */
    public void runJob();

    /**
     * the router is extremely overloaded, so this job has been dropped.  if for
     * some reason the job *must* do some cleanup / requeueing of other tasks, it
     * should do so here.
     *
     */
    public void dropped();
}
