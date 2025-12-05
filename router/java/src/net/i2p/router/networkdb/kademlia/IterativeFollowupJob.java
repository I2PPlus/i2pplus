package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;

/**
 * Performs targeted followup lookups during iterative search operations.
 * <p>
 * Extends SingleSearchJob to query specific peers for RouterInfo
 * during iterative searches. Unlike SingleSearchJob, this class
 * notifies the parent search job when successful, allowing the
 * iterative search to incorporate the new peer into its search strategy.
 * <p>
 * Used when iterative searches discover new peers that need
 * direct querying to obtain their RouterInfo for further
 * search refinement. Provides seamless integration with the
 * iterative search algorithm while reusing existing selector infrastructure.
 *
 * @since 0.8.9
 */
class IterativeFollowupJob extends SingleSearchJob {
    private final IterativeSearchJob _search;

    public IterativeFollowupJob(RouterContext ctx, Hash key, Hash to, IterativeSearchJob search) {
        super(ctx, key, to);
        _search = search;
    }

    @Override
    public String getName() { return "Start DbStoreReplyMsg Iterative Search"; }

    @Override
    void success() {
        _search.newPeerToTry(_key);
        super.success();
    }
}
