package net.i2p.router.tasks;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;


/**
 * Update the routing Key modifier every day at midnight (plus on startup).
 * This is done here because we want to make sure the key is updated before anyone
 * uses it.
 *
 * @since 0.8.12 moved from Router.java
 */
public class UpdateRoutingKeyModifierJob extends JobImpl {
    /**
     * Longest the job may be delayed, 15 min. It runs about that often, in case
     * of a time zone change or clock skew.
     */
    private static final long MAX_DELAY_FAILSAFE = 15*60*1000L;

    /** Where a failed key update is recorded. */
    private final Log _log;

    /**
     * Create a new routing key modifier update job.
     *
     * @param ctx the router context for accessing router services
     * @since 0.8.12 moved from Router.java
     */
    public UpdateRoutingKeyModifierJob(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
    }

    /**
     * Name of this job.
     *
     * @return job name for logging and identification
     */
    @Override
    public String getName() { return "Update Routing Key Modifier"; }

    /**
     * Update the routing key modifier if needed.
     * <p>
     * This job runs periodically (every 15 minutes maximum) to ensure
     * the routing key modifier is updated daily at midnight. The routing
     * key modifier is used in cryptographic operations and must be updated
     * regularly for security.
     * <p>
     * If the modifier data changes, notifies the network database that
     * routing keys have changed. A failure to update is logged and the job is
     * requeued anyway, so one bad key does not stop the daily update.
     */
    public void runJob() {
        RouterKeyGenerator gen = getContext().routerKeyGenerator();
        // make sure we requeue quickly if just before midnight
        long delay = Math.max(5, Math.min(MAX_DELAY_FAILSAFE, gen.getTimeTillMidnight()));
        try {
            // tell netdb if mod data changed
            boolean changed = gen.generateDateBasedModData();
            if (changed)
                getContext().netDb().routingKeyChanged();
        } catch (Throwable t) {
            // make sure it always gets requeued
            _log.error("Routing key modifier update failed -> Requeued anyway", t);
        }
        requeue(delay);
    }
}
