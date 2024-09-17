package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
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
    private final static long RERUN_DELAY_MS = 3*60*1000;

    public ExpireLeasesJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ExpireLeasesJob.class);
        _facade = facade;
    }

    public String getName() { return "Expire Leases"; }

    public void runJob() {
        long uptime = getContext().router().getUptime();
        List<Hash> toExpire = selectKeysToExpire();
        if (!toExpire.isEmpty() && uptime >= 10*60*1000) {
            StringBuilder buf = new StringBuilder(toExpire.size()*16);
            if (_log.shouldLog(Log.DEBUG)) {buf.append("Leases to expire (" + toExpire.size() + "): ");}
            for (Hash h : toExpire) {
                _facade.fail(h);
                if (_log.shouldLog(Log.DEBUG)) {
                    buf.append("[").append(h.toBase32().substring(0,8)).append("]"); buf.append(" ");
                }
            }
            if (_log.shouldLog(Log.INFO)) {
                _log.info("Adding " + toExpire.size() + " expired " +
                          (toExpire.size() > 1 ? "LeaseSets" : "LeaseSet") + " to the explore queue...");
            }
            if (_log.shouldLog(Log.DEBUG)) {_log.info(buf.toString());}
            _facade.queueForExploration(toExpire); // don't do explicit searches, just explore passively
        }
        requeue(RERUN_DELAY_MS);
    }

    /**
     * Run through the entire data store, finding all expired leaseSets (ones that
     * don't have any leases that haven't yet passed, even with the CLOCK_FUDGE_FACTOR)
     *
     */
    private List<Hash> selectKeysToExpire() {
        RouterContext ctx = getContext();
        long now = ctx.clock().now();
        List<Hash> toExpire = new ArrayList<Hash>();
        List<Hash> current = new ArrayList<Hash>();
        for (Map.Entry<Hash, DatabaseEntry> entry : _facade.getDataStore().getMapEntries()) {
            DatabaseEntry obj = entry.getValue();
            if (obj != null && obj.isLeaseSet()) {
                LeaseSet ls = (LeaseSet) obj;
                Hash h = entry.getKey();
                String tunnelName = ls != null ? " for \'" + getTunnelName(ls.getDestination()) + "\'" : "";
                if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                    toExpire.add(h);
                    if (ctx.clientManager().isLocal(h)) {
                        _log.logAlways(Log.ERROR, "LOCAL LeaseSet" + tunnelName + " [" + h.toBase32().substring(0,8) + "] has expired");
                    }
                } else if (!ls.isCurrent(90*1000)) {current.add(h);} // if we're expiring in < 90s, queue for exploration
            }
        }
        if (!current.isEmpty()) {
            if (_log.shouldLog(Log.INFO)) {
                _log.info("Adding " + current.size() + " soon-to-expire " + (current.size() > 1 ? "LeaseSets" : "LeaseSet") + " to the explore queue...");
            }
            if (current.size() > 2) {Collections.shuffle(current);}
            _facade.queueForExploration(current); // passively explore non-expired keys
            current.clear();
        }
        return toExpire;
    }

    public String getTunnelName(Destination d) {
        TunnelPoolSettings in = getContext().tunnelManager().getInboundSettings(d.calculateHash());
        String name = (in != null ? in.getDestinationNickname() : null);
        if (name == null) {
            TunnelPoolSettings out = getContext().tunnelManager().getOutboundSettings(d.calculateHash());
            name = (out != null ? out.getDestinationNickname() : null);
        }
        if (name == null) {return "";}
        return name;
    }

}
