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
import net.i2p.kademlia.KBucketSet;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

import java.util.List;
import java.util.Set;

/**
 * Abstract base class for peer selection in Kademlia routing tables.
 * <p>All peer selection logic is implemented in FloodfillPeerSelector.
 * This class only provides common constructor and logging functionality.</p>
 */
abstract class PeerSelector {
    protected final Log _log;
    protected final RouterContext _context;

    public PeerSelector(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(getClass());
    }

    abstract List<Hash> selectNearest(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet<Hash> kbuckets);
    abstract List<Hash> selectNearestExplicit(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet<Hash> kbuckets);
    abstract List<Hash> selectNearestExplicitThin(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet<Hash> kbuckets);
    abstract List<Hash> selectMostReliablePeers(Hash key, int numClosest, Set<Hash> alreadyChecked, KBucketSet<Hash> kbuckets);

}
