package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;

/**
 * Handles timeout events for individual peer lookups in iterative searches.
 * <p>
 * Manages timeout scenarios for specific peer queries during
 * iterative search operations. Unlike overall search timeouts,
 * this handles individual peer response timeouts while allowing
 * the broader iterative search to continue with other peers.
 * <p>
 * Coordinates with IterativeSearchJob to mark peers as failed
 * when they don't respond within the expected time window.
 * The decision whether a timeout actually occurred is delegated
 * to the parent search job's failure detection logic.
 * <p>
 * Ensures robust iterative search behavior by handling unresponsive
 * peers without disrupting the overall search progress.
 *
 * @since 0.8.9
 */
class IterativeTimeoutJob extends JobImpl {
    private final IterativeSearchJob _search;
    private final Hash _peer;

    public IterativeTimeoutJob(RouterContext ctx, Hash peer, IterativeSearchJob job) {
        super(ctx);
        _peer = peer;
        _search = job;
    }

    public void runJob() {
        _search.failed(_peer, true);
    }

    public String getName() { return "Timeout Iterative Search"; }
}
