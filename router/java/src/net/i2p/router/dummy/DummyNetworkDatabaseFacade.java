package net.i2p.router.dummy;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Job;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.RouterContext;

/**
 * In-memory network database implementation for testing and development scenarios.
 * Provides basic router info storage and lookup functionality without persistence or network communication.
 */
public class DummyNetworkDatabaseFacade extends NetworkDatabaseFacade {
    private final Map<Hash, RouterInfo> _routers;
    private final RouterContext _context;
/** Dummynetworkdatabasefacade */

    public DummyNetworkDatabaseFacade(RouterContext ctx) {
        _routers = Collections.synchronizedMap(new HashMap<>());
        _context = ctx;
    }

    /**
     * restart.
     */
    @Override
    public void restart() { /* Intentionally empty - dummy implementation */ }
/** Shut down and release resources */
    public void shutdown() { /* Intentionally empty - dummy implementation */ }
    /**
     * startup.
     */
    @Override
    public void startup() {
        RouterInfo info = _context.router().getRouterInfo();
        _routers.put(info.getIdentity().getHash(), info);
    }
/** Lookuplocally */

    public DatabaseEntry lookupLocally(Hash key) { return null; }
/** Lookuplocallywithoutvalidation */
    public DatabaseEntry lookupLocallyWithoutValidation(Hash key) { return null; }
    /**
     * lookupLeaseSet.
     */
    @Override
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) { /* Intentionally empty - dummy implementation */ }
/** Lookupleaseset */
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, Hash fromLocalDest) { /* Intentionally empty - dummy implementation */ }
/** Lookupleasesetlocally */
    public LeaseSet lookupLeaseSetLocally(Hash key) { return null; }
/** Lookupleasesetremotely */
    public void lookupLeaseSetRemotely(Hash key, Hash fromLocalDest) { /* Intentionally empty - dummy implementation */ }
    @Override
    public void lookupLeaseSetRemotely(Hash key, Job onFindJob, Job onFailedLookupJob,
                                       long timeoutMs, Hash fromLocalDest) { /* Intentionally empty - dummy implementation */ }

    /**
     * lookupDestination.
     */
    @Override
    public void lookupDestination(Hash key, Job onFinishedJob, long timeoutMs, Hash fromLocalDest) { /* Intentionally empty - dummy implementation */ }

    /**
     * lookupDestinationLocally.
     */
    @Override
    public Destination lookupDestinationLocally(Hash key) { return null; }

    /**
     * lookupRouterInfo.
     */
    @Override
    public void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        RouterInfo info = lookupRouterInfoLocally(key);
        if (info == null)
            _context.jobQueue().addJob(onFailedLookupJob);
        else
            _context.jobQueue().addJob(onFindJob);
    }
    /**
     * lookupRouterInfoLocally.
     */
    @Override
    public RouterInfo lookupRouterInfoLocally(Hash key) { return _routers.get(key); }
/** Publish a key to the network */

    public void publish(LeaseSet localLeaseSet) { /* Intentionally empty - dummy implementation */ }
/** Publish a key to the network */
    public void publish(RouterInfo localRouterInfo) { /* Intentionally empty - dummy implementation */ }
/** Store a key locally */

    public LeaseSet store(Hash key, LeaseSet leaseSet) { return leaseSet; }
/** Store a key locally */
    public RouterInfo store(Hash key, RouterInfo routerInfo) {
        return _routers.put(key, routerInfo);
    }
/** Remove a key from publication */

    public void unpublish(LeaseSet localLeaseSet) { /* Intentionally empty - dummy implementation */ }
//** Access the stored lease set */
    public void accessLeaseSet(Hash key) { /* Intentionally empty - dummy implementation */ }
/** Remove a lease set from tracking */
    public void removeLeaseSetFromTracking(Hash key) { /* Intentionally empty - dummy implementation */ }
/** Record a lookup failure */

    public void fail(Hash dbEntry) {
        _routers.remove(dbEntry);
    }
/** Return the allRouters */

    public Set<Hash> getAllRouters() { return new HashSet<>(_routers.keySet()); }
/** Findnearestrouters */
    public Set<Hash> findNearestRouters(Hash key, int maxNumRouters, Set<Hash> peersToIgnore) { return getAllRouters(); }
}
