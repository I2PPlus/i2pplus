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

    public DummyNetworkDatabaseFacade(RouterContext ctx) {
        _routers = Collections.synchronizedMap(new HashMap<Hash, RouterInfo>());
        _context = ctx;
    }

    @Override
    public void restart() {}
    @Override
    public void shutdown() {}
    @Override
    public void startup() {
        RouterInfo info = _context.router().getRouterInfo();
        _routers.put(info.getIdentity().getHash(), info);
    }

    @Override
    public DatabaseEntry lookupLocally(Hash key) { return null; }
    @Override
    public DatabaseEntry lookupLocallyWithoutValidation(Hash key) { return null; }
    @Override
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {}
    @Override
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, Hash fromLocalDest) {}
    @Override
    public LeaseSet lookupLeaseSetLocally(Hash key) { return null; }
    @Override
    public void lookupLeaseSetRemotely(Hash key, Hash fromLocalDest) {}
    @Override
    public void lookupLeaseSetRemotely(Hash key, Job onFindJob, Job onFailedLookupJob,
                                       long timeoutMs, Hash fromLocalDest) {}

    @Override
    public void lookupDestination(Hash key, Job onFinishedJob, long timeoutMs, Hash fromLocalDest) {}

    @Override
    public Destination lookupDestinationLocally(Hash key) { return null; }

    @Override
    public void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        RouterInfo info = lookupRouterInfoLocally(key);
        if (info == null)
            _context.jobQueue().addJob(onFailedLookupJob);
        else
            _context.jobQueue().addJob(onFindJob);
    }

    @Override
    public RouterInfo lookupRouterInfoLocally(Hash key) { return _routers.get(key); }

    @Override
    public void publish(LeaseSet localLeaseSet) {}
    @Override
    public void publish(RouterInfo localRouterInfo) {}

    @Override
    public LeaseSet store(Hash key, LeaseSet leaseSet) { return leaseSet; }
    @Override
    public RouterInfo store(Hash key, RouterInfo routerInfo) {
        RouterInfo rv = _routers.put(key, routerInfo);
        return rv;
    }

    @Override
    public void unpublish(LeaseSet localLeaseSet) {}
    @Override
    public void fail(Hash dbEntry) {
        _routers.remove(dbEntry);
    }

    @Override
    public Set<Hash> getAllRouters() { return new HashSet<Hash>(_routers.keySet()); }
    @Override
    public Set<Hash> findNearestRouters(Hash key, int maxNumRouters, Set<Hash> peersToIgnore) { return getAllRouters(); }
}
