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
    /** _log. / */
    protected final Log _log;
    /** _context. / */
    protected final RouterContext _context;

    /**
     * PeerSelector. /
     *
     * @param ctx the router context whose log and profile organizer this selector uses
     */
    public PeerSelector(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(getClass());
    }

    /**
     * Peers nearest to the key in routing space.
     *
     * @param key the routing key to search for
     * @param maxNumRouters the most hashes to return, zero or less meaning no limit
     * @param peersToIgnore hashes already chosen or excluded from this selection
     * @param kbuckets the routing table to select from
     * @return the nearest peers in ascending Kademlia distance
     */
    abstract List<Hash> selectNearest(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet<Hash> kbuckets);
    /**
     * Peers nearest to the key, floodfills first, then sorted by Kademlia distance.
     *
     * @param key the routing key to search for
     * @param maxNumRouters the most hashes to return, zero or less meaning no limit
     * @param peersToIgnore hashes already chosen or excluded from this selection
     * @param kbuckets the routing table to select from
     * @return the floodfill peers nearest the key, then the other peers by distance
     */
    abstract List<Hash> selectNearestExplicit(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet<Hash> kbuckets);

}

