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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Periodically searches through all leases to find expired ones, failing those
 * keys and firing up a new search for each (in case we want it later, might as
 * well preemptively fetch it).
 *
 * @since 0.8.9
 */
class ExpireLeasesJob extends JobImpl {
    private final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;
    private final static long RERUN_DELAY_MS = 45*1000;
    private static final int LIMIT_LEASES_FF = 1250;
    private static final int LIMIT_LEASES_CLIENT = SystemVersion.isSlow() ? 300 : 750;
    private volatile boolean _isShutdown;

    public ExpireLeasesJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ExpireLeasesJob.class);
        _facade = facade;
    }

    public String getName() { return "Expire Leases"; }

    public void runJob() {
        if (_isShutdown) {return;}
        long uptime = getContext().router().getUptime();
        List<Hash> toExpire = selectKeysToExpire();
        if (!toExpire.isEmpty() && uptime >= 90*1000) {
            for (Hash key : toExpire) {_facade.fail(key);}
            if (_log.shouldInfo()) {_log.info("Leases expired: " + toExpire.size());}
        }
        if (!_isShutdown) {
            requeue(RERUN_DELAY_MS);
        }
    }

    /**
     *  Mark this job as shutdown to prevent requeueing
     *  @since 0.9.68+
     */
    void shutdown() {
        _isShutdown = true;
    }

    /**
     * Run through the entire data store, finding all expired leaseSets (ones that
     * don't have any leases that haven't yet passed, even with the CLOCK_FUDGE_FACTOR)
     *
     */
    private List<Hash> selectKeysToExpire() {
        RouterContext ctx = getContext();
        long now = ctx.clock().now();
        boolean isClient = _facade.isClientDb();
        boolean isFFDB = _facade.floodfillEnabled() && !isClient;
        Set<Map.Entry<Hash, DatabaseEntry>> entries =  _facade.getDataStore().getMapEntries();
        List<LeaseSet> current = new ArrayList<LeaseSet>(isFFDB ? 512 : (isClient ? entries.size() : 128)); // clientdb only has leasesets
        List<Hash> toExpire = new ArrayList<Hash>(Math.min(entries.size(), 128));
        int sz = 0;
        String tunnelName = "";
        for (Map.Entry<Hash, DatabaseEntry> entry : entries) {
            DatabaseEntry obj = entry.getValue();
            if (obj != null && obj.isLeaseSet()) {
                LeaseSet ls = (LeaseSet) obj;
                Hash h = entry.getKey();
                boolean isLocal = ctx.clientManager().isLocal(h);
                // Skip local LeaseSets - they're managed by RepublishLeaseSetJob
                if (isLocal) {
                    continue;
                }
                if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                    toExpire.add(h);
                } else {
                    sz++;
                    current.add(ls);
                }
            }
        }
        int origsz = sz;
        int limit = isFFDB ? LIMIT_LEASES_FF : LIMIT_LEASES_CLIENT;
        if (sz > limit) {
            // aggressive drop strategy
            if (isFFDB) {
                RouterKeyGenerator gen = ctx.routerKeyGenerator();
                byte[] ourRKey = ctx.routerHash().getData();
                for (LeaseSet ls : current) {
                    Hash h = ls.getHash();
                    // don't drop very close to us
                    byte[] rkey = gen.getRoutingKey(h).getData();
                    int distance = (((rkey[0] ^ ourRKey[0]) & 0xff) << 8) |
                                    ((rkey[1] ^ ourRKey[1]) & 0xff);
                    // they have to be within 1/256 of the keyspace
                    if (distance >= 256) {
                         toExpire.add(h);
                         if (--sz <= limit) {break;}
                    }
                }
            } else {
                Collections.sort(current, new LeaseSetComparator());
                for (LeaseSet ls : current) {
                     toExpire.add(ls.getHash());
                     if (_log.shouldInfo()) {_log.info("Aggressively expiring LeaseSets for " + _facade + "\n*" + ls);}
                     if (--sz <= limit) {break;}
                }
            }
        }
        return toExpire;
    }

    /**
     *  Oldest first
     *  @since 0.9.65
     */
    private static class LeaseSetComparator implements Comparator<LeaseSet> {
         public int compare(LeaseSet l, LeaseSet r) {
             long dl = l.getLatestLeaseDate();
             long dr = r.getLatestLeaseDate();
             if (dl < dr) return -1;
             if (dl > dr) return 1;
             return 0;
        }
    }

    public String getTunnelName(Destination d) {
        if (d != null) {
            TunnelPoolSettings in = getContext().tunnelManager().getInboundSettings(d.calculateHash());
            String name = (in != null ? in.getDestinationNickname() : null);
            if (name == null) {
                TunnelPoolSettings out = getContext().tunnelManager().getOutboundSettings(d.calculateHash());
                name = (out != null ? out.getDestinationNickname() : null);
            }
            if (name != null) {return name;}
            else {return "";}
        }
        return "";
    }

}
