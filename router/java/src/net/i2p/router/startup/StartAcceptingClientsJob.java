package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;

/** Start the I2CP interface. */
class StartAcceptingClientsJob extends JobImpl {

    /**
     * StartAcceptingClientsJob.
     */
    public StartAcceptingClientsJob(RouterContext context) {
        super(context);
    }

    /**
     *  Name of this job.
     *
     *  @return the name
     */
    public String getName() { return "Start Accepting Clients"; }

    /**
     * Start the client manager to accept I2CP connections.
     */
    public void runJob() {

        getContext().clientManager().startup();

        // pointless
    }
}
