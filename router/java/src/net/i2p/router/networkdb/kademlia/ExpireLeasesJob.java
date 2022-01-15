package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Periodically search through all leases to find expired ones, failing those
 * keys and firing up a new search for each (in case we want it later, might as
 * well preemptively fetch it)
 *
 */
class ExpireLeasesJob extends JobImpl {
    private final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;

//    private final static long RERUN_DELAY_MS = 1*60*1000;
    private final static long RERUN_DELAY_MS = 50*1000;

    public ExpireLeasesJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ExpireLeasesJob.class);
        _facade = facade;
    }

    public String getName() { return "Expire Leases"; }

    public void runJob() {
        Set<Hash> toExpire = selectKeysToExpire();
        if (!toExpire.isEmpty()) {
        StringBuilder buf = new StringBuilder(16);
        buf.append("Leases to expire: ");
        for (Hash h : toExpire) {
            buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
        }
//            _log.info("Leases to expire:\n* " + toExpire);
            _log.info(buf.toString());
            for (Hash key : toExpire) {
                _facade.fail(key);
                //_log.info("Lease " + key + " is expiring, so lets look for it again", new Exception("Expire and search"));
                //_facade.lookupLeaseSet(key, null, null, RERUN_DELAY_MS);
            }
        }
        //_facade.queueForExploration(toExpire); // don't do explicit searches, just explore passively
        requeue(RERUN_DELAY_MS);
    }

    /**
     * Run through the entire data store, finding all expired leaseSets (ones that
     * don't have any leases that haven't yet passed, even with the CLOCK_FUDGE_FACTOR)
     *
     */
    private Set<Hash> selectKeysToExpire() {
        Set<Hash> toExpire = new HashSet<Hash>(128);
        for (Map.Entry<Hash, DatabaseEntry> entry : _facade.getDataStore().getMapEntries()) {
            DatabaseEntry obj = entry.getValue();
            if (obj.isLeaseSet()) {
                LeaseSet ls = (LeaseSet)obj;
                if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR))
                    toExpire.add(entry.getKey());
                else if (_log.shouldLog(Log.DEBUG))
                    if (ls.getDestination() != null)
                        _log.debug("Lease [" + ls.getDestination().calculateHash().toBase64().substring(0,6) + "] is current - not expiring");
                    else
                        _log.debug("Not expiring current but unidentified Lease (no destination)");
            }
        }
        return toExpire;
    }
}
