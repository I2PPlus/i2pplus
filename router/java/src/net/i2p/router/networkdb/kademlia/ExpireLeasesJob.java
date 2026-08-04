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
import java.io.Serializable;

/**
 * Periodically searches through all leases to find expired ones, failing those
 * keys and firing up a new search for each (in case we want it later, might as
 * well preemptively fetch it).
 * Also refreshes leasesets that are about to expire to ensure continuity of service.
 *
 * @since 0.8.9
 */
class ExpireLeasesJob extends JobImpl {
    private final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;
    private static final long RERUN_DELAY_MS = 30*1000L;
    private static final int LIMIT_LEASES_FF = 1250;
    private static final int LIMIT_LEASES_CLIENT = SystemVersion.isSlow() ? 300 : 750;
    /** Refresh leasesets with less than this much time remaining before expiry */
    private static final long REFRESH_THRESHOLD_MS = 2 * 60 * 1000L;
    /** Aggressive purge interval for client databases (ms) */
    private static final long AGGRESSIVE_PURGE_INTERVAL_MS = 10 * 1000L;
    /** After this long past expiry, a leaseset is considered stale and purged immediately */
    private static final long STALE_EXPIRED_MS = 5 * 60 * 1000L;

    /**
     * ExpireLeasesJob.
     */
    public ExpireLeasesJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ExpireLeasesJob.class);
        _facade = facade;
    }

    /**
     * @return the name
     */
    public String getName() { return "Expire Leases"; }

/**
      * runJob.
      */
    public void runJob() {
        long uptime = getContext().router().getUptime();
        List<Hash> toExpire = selectKeysToExpire();
        if (!toExpire.isEmpty() && uptime >= 90*1000L) {
            for (Hash key : toExpire) {_facade.fail(key);}
            if (_log.shouldInfo()) {_log.info("Leases expired: " + toExpire.size());}
        }
        refreshAboutToExpire();
        if (_facade.isClientDb()) {purgeStaleLeasesets();}
        requeue(RERUN_DELAY_MS);
    }

    /**
     * Aggressively purge leasesets that have been expired for longer than
     * the stale threshold. This ensures expired leasesets don't linger
     * in the netdb for extended periods.
     */
    private void purgeStaleLeasesets() {
        RouterContext ctx = getContext();
        long now = ctx.clock().now();
        Set<Map.Entry<Hash, DatabaseEntry>> entries = _facade.getDataStore().getMapEntries();
        for (Map.Entry<Hash, DatabaseEntry> entry : entries) {
            DatabaseEntry obj = entry.getValue();
            if (obj == null || !obj.isLeaseSet()) {continue;}
            LeaseSet ls = (LeaseSet) obj;
            Hash h = entry.getKey();
            boolean isLocal = ctx.clientManager().isLocal(h);
            if (isLocal) {continue;}
            if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                long expiredAgo = now - ls.getLatestLeaseDate();
                if (expiredAgo > STALE_EXPIRED_MS) {
                    if (_log.shouldInfo()) {_log.info("Purging stale LeaseSet [" + h.toBase32().substring(0,8) + "] expired " + expiredAgo + "ms ago");}
                    _facade.fail(h);
                }
            }
        }
    }

    /**
     * Refresh leasesets that are about to expire to ensure continuity of service.
     * This preemptively fetches new leasesets before the old ones expire,
     * avoiding any gap in service.
     */
    private void refreshAboutToExpire() {
        RouterContext ctx = getContext();
        long now = ctx.clock().now();
        Set<Map.Entry<Hash, DatabaseEntry>> entries = _facade.getDataStore().getMapEntries();
        for (Map.Entry<Hash, DatabaseEntry> entry : entries) {
            DatabaseEntry obj = entry.getValue();
            if (obj == null || !obj.isLeaseSet()) {continue;}
            LeaseSet ls = (LeaseSet) obj;
            Hash h = entry.getKey();
            boolean isLocal = ctx.clientManager().isLocal(h);
            if (isLocal) {continue;}
            long expiry = ls.getLatestLeaseDate();
            if (expiry > now && expiry - now < REFRESH_THRESHOLD_MS) {
                if (_log.shouldInfo()) {_log.info("Refreshing LeaseSet [" + h.toBase32().substring(0,8) + "] before expiry");}
                _facade.lookupLeaseSetRemotely(h, null);
            }
        }
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
        List<LeaseSet> current = new ArrayList<>(isFFDB ? 512 : (isClient ? entries.size() : 128)); // clientdb only has leasesets
        List<Hash> toExpire = new ArrayList<>(Math.min(entries.size(), 128));
        int sz = 0;
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
    private static class LeaseSetComparator implements Comparator<LeaseSet>, Serializable {
         /**
          * compare.
          */
         @Override
         public int compare(LeaseSet l, LeaseSet r) {
             long dl = l.getLatestLeaseDate();
             long dr = r.getLatestLeaseDate();
             if (dl < dr) return -1;
             if (dl > dr) return 1;
             return 0;
        }
    }

    /**
     * @return the tunnel name
     */
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
