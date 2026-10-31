package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Map;
import java.util.Set;
import net.i2p.data.DataHelper;
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
 * <p>
 * The normal path uses _facade's validate(), a sliding expiration based on
 * netdb size. When the netdb is over the size limit, eviction is
 * profile-weighted (see {@link #dropProbability}) — peers with no recent
 * contact evidence go first, recently-attested peers are kept — instead of
 * a flat random drop.
 *
 */
class ExpireRoutersJob extends JobImpl {
    /** Logger for this class. */
    private final Log _log;
    /** The store whose entries are expired. */
    private final KademliaNetworkDatabaseFacade _facade;

    /** Rerun fairly often, so the fails don't queue up too many netdb searches at once. */
    private static final long RERUN_DELAY_MS = 15*60*1000L;
    /** Most router entries examined in one run. */
    private static final int LIMIT_ROUTERS = SystemVersion.isSlow() ? 4000 : 8000;
    /** Attested more recently than this: never dropped by the size-pressure path. */
    static final long PROTECTED_ATTESTED = 12*60*60*1000L;
    /** Attested more recently than this: dropped at half the base probability. */
    static final long STALE_ATTESTED = 36*60*60*1000L;

    /**
     * Expire outdated routers from the network database.
     *
     * @param ctx the router context
     * @param facade the Kademlia network database facade
     */
    public ExpireRoutersJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ExpireRoutersJob.class);
        _facade = facade;
    }

    public String getName() { return "Expire Routers"; }

    public void runJob() {
        if (getContext().commSystem().getStatus() != Status.DISCONNECTED &&
            _facade.getKnownRouters() > 1000) {
            int removed = expireKeys();
            if (_log.shouldInfo())
                if (removed == 1)
                    _log.info("Deleted " + removed + " expired RouterInfo file from NetDb");
                else if (removed > 1)
                    _log.info("Deleted " + removed + " expired RouterInfo files from NetDb");
                else if (_log.shouldDebug() && _facade.getKnownRouters() > 2000)
                    _log.debug("No expired RouterInfo files found -> Next check in " +
                               DataHelper.formatDuration(RERUN_DELAY_MS / 5));
                else if (_log.shouldDebug())
                    _log.debug("No expired RouterInfo files found -> Next check in " + DataHelper.formatDuration(RERUN_DELAY_MS));
        }
        if (getContext().netDb().getKnownRouters() > 2000)
            requeue(RERUN_DELAY_MS / 5);
        else
            requeue(RERUN_DELAY_MS);
    }


    /**
     * Run through all of the known peers and pick ones that have really old
     * routerInfo publish dates, excluding ones that we are connected to,
     * so that they can be failed.
     *
     * @return how many routers were failed and removed, 0 if the netdb is
     * small, we are disconnected, or the system is lagging
     */
    private int expireKeys() {
        // go through the database directly for efficiency
        Set<Map.Entry<Hash, DatabaseEntry>> entries = _facade.getDataStore().getMapEntries();
        int count = entries.size();
        if (count < 500 || getContext().commSystem().getStatus() == Status.DISCONNECTED ||
                                 (getContext().jobQueue().getMaxLag() > 150) ||
                                 (getContext().throttle().getMessageDelay() > 1000))
            return 0;
        RouterKeyGenerator gen = getContext().routerKeyGenerator();
        long now = getContext().clock().now();
        long cutoff = now - 30*60*1000L;
        boolean almostMidnight = gen.getTimeTillMidnight() < FloodfillNetworkDatabaseFacade.NEXT_RKEY_RI_ADVANCE_TIME - 30*60*1000L;
        Hash us = getContext().routerHash();
        boolean isFF = _facade.floodfillEnabled();
        byte[] ourRKey = isFF ? us.getData() : null;
        // chance in 128
        int pdrop = Math.max(10, Math.min(80, (128 * count / LIMIT_ROUTERS) - 128));
        int removed = 0;
        if (_log.shouldLog(Log.INFO))
            _log.info("Expiring routers -> Routers: " + count + " * Drop probability: " +
                      (count > LIMIT_ROUTERS ? pdrop * 100 / 128 : 0) + '%');
        for (Map.Entry<Hash, DatabaseEntry> entry : entries) {
            DatabaseEntry e = entry.getValue();
            if (e.getType() != DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                count--;
                continue;
            }
            Hash key = entry.getKey();
            if (key.equals(us))
                continue;
            // Don't expire anybody we are connected to
            if (getContext().commSystem().isEstablished(key))
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
                        if (almostMidnight) {
                            // almost midnight, recheck with tomorrow's keys
                            rkey = gen.getNextRoutingKey(key).getData();
                            distance = (((rkey[0] ^ ourRKey[0]) & 0xff) << 8) |
                                        ((rkey[1] ^ ourRKey[1]) & 0xff);
                            if (distance < 256)
                                continue;
                        }
                    }
                    // Profile-weighted drop: evict known-dead first, keep useful
                    int weighted = dropProbability(pdrop, StaleContactTrimmer.lastSeen(getContext(), key), now);
                    if (weighted > 0 && getContext().random().nextInt(128) < weighted) {
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

    /**
     * Profile-weighted drop probability (chance in 128) for the size-pressure
     * eviction path. Peers with no contact evidence are dropped harder than
     * the flat probability; peers attested within {@link #PROTECTED_ATTESTED}
     * are skipped entirely, so netdb size pressure evicts known-dead entries
     * before useful ones. Pure function — directly unit-testable.
     *
     * @param base the flat probability, 10-80 in practice
     * @param lastSeen newest contact evidence for the peer, 0 if never attested
     * @param now current context time
     * @return adjusted chance in 128, 0 to protect the peer this cycle
     * @since 0.9.71+
     */
    static int dropProbability(int base, long lastSeen, long now) {
        if (lastSeen <= 0) {
            // never heard of directly — first out
            return Math.min(128, base * 2);
        }
        long age = now - lastSeen;
        if (age < PROTECTED_ATTESTED) {
            return 0;
        }
        if (age < STALE_ATTESTED) {
            return base / 2;
        }
        return Math.min(128, base * 3 / 2);
    }
}
