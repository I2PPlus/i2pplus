package net.i2p.router.networkdb.kademlia;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

class CleanupLURoutersJob extends JobImpl {
    private final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;
    private final RouterContext _ctx;

    private static final long INITIAL_DELAY_MS = 60*1000L;
    private static final long RERUN_DELAY_MS = 10*60*1000L;

    public CleanupLURoutersJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _ctx = ctx;
        _log = ctx.logManager().getLog(CleanupLURoutersJob.class);
        _facade = facade;
        getTiming().setStartAfter(ctx.clock().now() + INITIAL_DELAY_MS);
    }

    public String getName() { return "Cleanup LU Routers"; }

    public void runJob() {
        _log.warn("Running scheduled LU router cleanup...");
        _facade.cleanupLURouters();
        getTiming().setStartAfter(_ctx.clock().now() + RERUN_DELAY_MS);
        _ctx.jobQueue().addJob(this);
    }
}
