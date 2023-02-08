package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Go through the routing table pick routers that are
 * out of date, but don't expire routers we're actively connected to.
 *
 * We could in the future use profile data, netdb total size, a Kademlia XOR distance,
 * or other criteria to minimize netdb size, but for now we just use _facade's
 * validate(), which is a sliding expiration based on netdb size.
 *
 */
class ExpireRoutersJob extends JobImpl {
    private final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;

    /** rerun fairly often, so the fails don't queue up too many netdb searches at once */
//    private final static long RERUN_DELAY_MS = 5*60*1000;
    private final static long RERUN_DELAY_MS = 15*60*1000;
    private static final int LIMIT_ROUTERS = SystemVersion.isSlow() ? 4000 : 8000;

    public ExpireRoutersJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ExpireRoutersJob.class);
        _facade = facade;
    }

    public String getName() { return "Expire Routers"; }

    public void runJob() {
        if (getContext().commSystem().getStatus() != Status.DISCONNECTED &&
            _facade.getAllRouters().size() > 1000) {
            int removed = expireKeys();
            if (_log.shouldInfo())
                if (removed == 1)
                    _log.info("Deleted " + removed + " expired RouterInfo file from NetDb");
                else if (removed > 1)
                    _log.info("Deleted " + removed + " expired RouterInfo files from NetDb");
                else if (_log.shouldDebug() && getContext().netDb().getKnownRouters() > 2000)
                    _log.debug("No expired RouterInfo files found - next check in " + ((RERUN_DELAY_MS / 5) / 1000 / 60) + "m");
                else if (_log.shouldDebug())
                    _log.debug("No expired RouterInfo files found - next check in " + (RERUN_DELAY_MS / 1000 / 60) + "m");
        }
        if (getContext().netDb().getKnownRouters() > 2000)
            requeue(RERUN_DELAY_MS / 5);
        else
            requeue(RERUN_DELAY_MS);
    }


    /**
     * Run through all of the known peers and pick ones that have really old
     * routerInfo publish dates, excluding ones that we are connected to,
     * so that they can be failed
     *
     * @return number removed
     */
    private int expireKeys() {
        Set<Hash> keys = _facade.getAllRouters();
        keys.remove(getContext().routerHash());
        int count = keys.size();
        if (count < 150)
        // Don't expire if router is disconnected, lagged, or has high message delay
//        if (count < 150 || getContext().commSystem().getStatus() == Status.DISCONNECTED ||
        if (count < 500 || getContext().commSystem().getStatus() == Status.DISCONNECTED ||
                                 (getContext().jobQueue().getMaxLag() > 150) ||
                                 (getContext().throttle().getMessageDelay() > 1000))
            return 0;
        RouterKeyGenerator gen = getContext().routerKeyGenerator();
        long now = getContext().clock().now();
        long cutoff = now - 30*60*1000;
        boolean isFF = _facade.floodfillEnabled();
        byte[] ourRKey = isFF ? getContext().routerHash().getData() : null;
        int pdrop = Math.max(10, Math.min(50, (100 * count / LIMIT_ROUTERS) - 100));
        int removed = 0;
        if (_log.shouldLog(Log.INFO))
            _log.info("Expiring routers, count = " + count + " drop probability " + (count > LIMIT_ROUTERS ? pdrop : 0) + '%');
        for (Hash key : keys) {
            // Don't expire anybody we are connected to
            if (getContext().commSystem().isEstablished(key))
                continue;
            DatabaseEntry e = _facade.lookupLocallyWithoutValidation(key);
            if (e == null)
                continue;
            if (count > LIMIT_ROUTERS) {
                // aggressive drop strategy
                if (e.getDate() < cutoff) {
                    if (isFF) {
                        // don't drop very close to us
                        byte[] rkey = gen.getRoutingKey(key).getData();
                        int distance = (((rkey[0] ^ ourRKey[0]) & 0xff) << 8) |
                                        ((rkey[1] ^ ourRKey[1]) & 0xff);
                        // they have to be within 1/256 of the keyspace
                        if (distance < 256)
                            continue;
                        // TODO maybe: long until = gen.getTimeTillMidnight();
                    }
                    if (getContext().random().nextInt(100) < pdrop) {
                        _facade.dropAfterLookupFailed(key);
                        removed++;
                    }
                }
            } else {
                // normal drop strategy
                try {
                    if (_facade.validate((RouterInfo) e) != null) {
                        _facade.dropAfterLookupFailed(key);
                        removed++;
                    }
                } catch (IllegalArgumentException iae) {
                    _facade.dropAfterLookupFailed(key);
                    removed++;
                }
            }
        }
        return removed;
    }
}
