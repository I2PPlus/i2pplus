package net.i2p.router.networkdb.kademlia;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * Go through the kbuckets and generate random keys for routers in buckets not
 * yet full, attempting to keep a pool of keys we can explore with (at least one
 * per bucket)
 *
 * @deprecated unused, see comments in KNDF
 */
@Deprecated
class ExploreKeySelectorJob extends JobImpl {
    private Log _log;
    private KademliaNetworkDatabaseFacade _facade;

    private static final long RERUN_DELAY_MS = 60 * 1000;
    private static final long OLD_BUCKET_TIME = 15 * 60 * 1000;

    public ExploreKeySelectorJob(RouterContext context, KademliaNetworkDatabaseFacade facade) {
        super(context);
        _log = context.logManager().getLog(ExploreKeySelectorJob.class);
        _facade = facade;
    }

    @Override
    public String getName() {
        return "Explore Key Selector";
    }

    @Override
    public void runJob() {
        if (_facade.floodfillEnabled()) {
            requeue(30 * RERUN_DELAY_MS);
            return;
        }
        Collection<Hash> toExplore = selectKeysToExplore();
        _log.info("Filling the explorer pool with: " + toExplore);
        if (toExplore != null) _facade.queueForExploration(toExplore);
        requeue(RERUN_DELAY_MS);
    }

    /**
     * Run through all kbuckets with too few routers and generate a random key
     * for it, with a maximum number of keys limited by the exploration pool size
     *
     */
    private Collection<Hash> selectKeysToExplore() {
        Set<Hash> alreadyQueued = _facade.getExploreKeys();
        if (alreadyQueued.size() > KademliaNetworkDatabaseFacade.MAX_EXPLORE_QUEUE) return Collections.emptyList();
        return _facade.getKBuckets().getExploreKeys(OLD_BUCKET_TIME);
    }
}
