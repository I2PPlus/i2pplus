package net.i2p.router.networkdb.kademlia;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Timeout handler for flood-only search operations.
 * <p>
 * Triggers search failure when the overall search timeout expires.
 * Used by FloodOnlySearchJob to enforce total time limits
 * and ensure searches don't run indefinitely without resolution.
 * <p>
 * This complements the per-peer timeout mechanisms by providing
 * a search-wide timeout boundary.
 *
 * @since 0.8.9
 */
 class FloodOnlyLookupTimeoutJob extends JobImpl {

    private final FloodSearchJob _search;

    public FloodOnlyLookupTimeoutJob(RouterContext ctx, FloodSearchJob job) {
        super(ctx);
        _search = job;
    }

    public void runJob() {
        Log log = getContext().logManager().getLog(getClass());
        if (log.shouldDebug())
            log.debug("[Job " + _search.getJobId() + "] FloodSearch timed out");
        _search.failed();
    }

    public String getName() { return "Timeout NetDb FloodSearch"; }
}
