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

public class DummyNetworkDatabaseFacade extends NetworkDatabaseFacade {
    private final Map<Hash, RouterInfo> _routers;
    private final RouterContext _context;
    
    public DummyNetworkDatabaseFacade(RouterContext ctx) {
        _routers = Collections.synchronizedMap(new HashMap<Hash, RouterInfo>());
        _context = ctx;
    }

    public void restart() {}
    public void shutdown() {}
    public void startup() {
        RouterInfo info = _context.router().getRouterInfo();
        _routers.put(info.getIdentity().getHash(), info);
    }
    
    public DatabaseEntry lookupLocally(Hash key) { return null; }
    public DatabaseEntry lookupLocallyWithoutValidation(Hash key) { return null; }
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {}
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, Hash fromLocalDest) {}
    public LeaseSet lookupLeaseSetLocally(Hash key) { return null; }
    public void lookupLeaseSetRemotely(Hash key, Hash fromLocalDest) {}
    public void lookupLeaseSetRemotely(Hash key, Job onFindJob, Job onFailedLookupJob,
                                       long timeoutMs, Hash fromLocalDest) {}

    public void lookupDestination(Hash key, Job onFinishedJob, long timeoutMs, Hash fromLocalDest) {}

    public Destination lookupDestinationLocally(Hash key) { return null; }

    public void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        RouterInfo info = lookupRouterInfoLocally(key);
        if (info == null) 
            _context.jobQueue().addJob(onFailedLookupJob);
        else
            _context.jobQueue().addJob(onFindJob);
    }
    public RouterInfo lookupRouterInfoLocally(Hash key) { return _routers.get(key); }

    public void publish(LeaseSet localLeaseSet) {}
    public void publish(RouterInfo localRouterInfo) {}

    public LeaseSet store(Hash key, LeaseSet leaseSet) { return leaseSet; }
    public RouterInfo store(Hash key, RouterInfo routerInfo) {
        RouterInfo rv = _routers.put(key, routerInfo);
        return rv;
    }

    public void unpublish(LeaseSet localLeaseSet) {}
    public void fail(Hash dbEntry) {
        _routers.remove(dbEntry);
    }
    
    public Set<Hash> getAllRouters() { return new HashSet<Hash>(_routers.keySet()); }
    public Set<Hash> findNearestRouters(Hash key, int maxNumRouters, Set<Hash> peersToIgnore) { return getAllRouters(); }
}
