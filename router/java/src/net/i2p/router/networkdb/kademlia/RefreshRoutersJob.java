package net.i2p.router.networkdb.kademlia;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

import java.util.Date;
import java.util.Random;
import net.i2p.router.CommSystemFacade.Status;

import net.i2p.router.Job;
import net.i2p.router.NetworkDatabaseFacade;

import net.i2p.router.networkdb.kademlia.KademliaNetworkDatabaseFacade;



/**
 * Go through all the routers once, after startup, and refetch their router infos.
 * This should be run once after startup (and preferably after any reseed is complete,
 * but we don't have any indication when that is).
 * This will help routers that start after being shutdown for many days or weeks,
 * as well as newly-reseeded routers, since
 * validate() in KNDF doesn't start failing and refetching until the router has been
 * up for an hour.
 * To improve integration even more, we fetch the floodfills first.
 * Ideally this should complete within the first half-hour of uptime.
 *
 * @since 0.8.8
 */
class RefreshRoutersJob extends JobImpl {
    private final Log _log;
    private final FloodfillNetworkDatabaseFacade _facade;
    private List<Hash> _routers;
    private boolean _wasRun;

    /** rerun fairly often. 1000 routers in 50 minutes
     *  Don't go faster as this overloads the expl. OBEP / IBGW
     */
    private final static long RERUN_DELAY_MS = 2*750; // 1.5 seconds * random value (see below)
    static final String PROP_RERUN_DELAY_MS = "router.refreshRouterDelay";
    static final String PROP_ROUTER_FRESHNESS = "router.refreshSkipIfYounger";
    static final String PROP_ROUTER_REFRESH_TIMEOUT = "router.refreshTimeout";
//    private final static long EXPIRE = 2*60*60*1000;
    private final static long EXPIRE = 31*24*60*60*1000;
    private final static long OLDER = 2*60*60*1000;
    private static long RESTART_DELAY_MS = 5*60*1000;

    public RefreshRoutersJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(RefreshRoutersJob.class);
        _facade = facade;
    }

    public String getName() { return "Refresh NetDb Routers"; }

    public void runJob() {
        Random rand = new Random();
        int netDbCount = _facade.getAllRouters().size();
        if (getContext().commSystem().getStatus() == Status.DISCONNECTED) {
            if (_log.shouldLog(Log.WARN))
            _log.warn("Suspending Refresh Routers job - network disconnected");
            return;
        }
        long lag = getContext().jobQueue().getMaxLag();
        if (lag > 500) {
            if (_log.shouldLog(Log.WARN))
            _log.warn("Suspending Refresh Routers job - job lag is over 500ms");
            return;
        }
        if (_facade.isInitialized() && netDbCount > 10000) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Suspending Refresh Routers job - over 10,000 known peers in NetDb");
            return;
        }
        if (_facade.isInitialized()) {
            if (_routers == null || _routers.isEmpty()) {
                // make a list of all routers, floodfill first
                _routers = _facade.getFloodfillPeers();
                int ff = _routers.size();
                Set<Hash> all = _facade.getAllRouters();
                all.removeAll(_routers);
                int non = all.size();
                _routers.addAll(all);
                if (_log.shouldLog(Log.INFO))
                    _log.info("To check: " + ff + " Floodfills and " + non + " non-Floodfills");
            }
            if (_routers.isEmpty()) {
                _routers = null;
                if (netDbCount > 4000) {
                    RESTART_DELAY_MS *= rand.nextInt(3) + 1;
                    requeue(RESTART_DELAY_MS);
                } else if (netDbCount > 8000) {
                    RESTART_DELAY_MS *= rand.nextInt(12) + 1;
                } else {
                    requeue(RESTART_DELAY_MS);
                }
                if (_log.shouldLog(Log.INFO))
                    _log.info("Finished refreshing NetDb routers; job will rerun in " + (RESTART_DELAY_MS / 1000) + "s");
                return;
            }
            long expire = getContext().clock().now() - EXPIRE;
            for (Iterator<Hash> iter = _routers.iterator(); iter.hasNext(); ) {
                Hash h = iter.next();
                iter.remove();
                if (h.equals(getContext().routerHash()))
                    continue;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Checking RouterInfo [" + h.toBase64().substring(0,6) + "]");
                RouterInfo ri = _facade.lookupRouterInfoLocally(h);
                if (ri == null)
                    continue;
//                if (ri.getPublished() < expire) {
//                long older = getContext().clock().now() - OLDER;
                long older = getContext().clock().now() - ri.getPublished();
                String freshness = getContext().getProperty("router.refreshSkipIfYounger");
                int routerAge = 60*60*1000;
                if (freshness == null) {
                    if (netDbCount > 2000)
                        routerAge = 2*60*60*1000;
                    if (netDbCount > 4000)
                        routerAge = 4*60*60*1000;
                    if (netDbCount > 6000)
                        routerAge = 8*60*60*1000;
                    if (netDbCount > 8000)
                        routerAge = 16*60*60*1000;
                    if (netDbCount > 9000)
                        routerAge = 20*60*60*1000;
                } else {
                    routerAge = Integer.valueOf(freshness)*60*60*1000;
                }
//                if (ri.getPublished() < older) {
                String refreshTimeout = getContext().getProperty("router.refreshTimeout");
                if (older > routerAge) {
                    if (_log.shouldLog(Log.INFO))
                        if (refreshTimeout == null)
                            _log.info("Refreshing Router [" + h.toBase64().substring(0,6) + "]" +
                            "\n* Published: " + new Date(ri.getPublished()));
                        else
                            _log.info("Refreshing Router [" + h.toBase64().substring(0,6) + "] - " + Integer.valueOf(refreshTimeout) + "s timeout" +
                            "\n* Published: " + new Date(ri.getPublished()));
//                    _facade.search(h, null, null, 15*1000, false);
//                    Job DropLookupFoundJob = new _facade.DropLookupFoundJob();
//                    _facade.search(h, null, new DropLookupFoundJob(getContext(), h, ri) , 20*1000, false);
                    if (refreshTimeout == null)
                        _facade.search(h, null, null , 20*1000, false);
                    else
                        _facade.search(h, null, null , Integer.valueOf(refreshTimeout)*1000, false);
                    break;
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        if ((routerAge / 60 / 60 / 1000) <= 1) {
                            _log.debug("Skipping refresh of Router [" + h.toBase64().substring(0,6) + "] - less than an hour old" +
                        "\n* Published: " + new Date(ri.getPublished()));
                    } else {
                        _log.debug("Skipping refresh of Router [" + h.toBase64().substring(0,6) + "] - less than " + (routerAge / 60 / 60 / 1000) + " hours old" +
                        "\n* Published: " + new Date(ri.getPublished()));
                    }
                    break;
                }
            }
        }

        int randomDelay = (1500 * (rand.nextInt(3) + 1)) + rand.nextInt(1000) + rand.nextInt(1000) + (rand.nextInt(1000) * (rand.nextInt(3) + 1)); // max 9.5 seconds
        String refresh = getContext().getProperty("router.refreshRouterDelay");
        if (refresh == null) {
            if (getContext().jobQueue().getMaxLag() > 150 || getContext().throttle().getMessageDelay() > 750)
                randomDelay = randomDelay * (rand.nextInt(3) + 1);
            else if (netDbCount < 4000)
                randomDelay = randomDelay - (rand.nextInt(2000));
            else if (netDbCount < 6000)
                randomDelay = randomDelay - (rand.nextInt(1250) + rand.nextInt(1250));
            else if (netDbCount < 8000)
                randomDelay = randomDelay - (rand.nextInt(750) / (rand.nextInt(3) + 1));
            else
                randomDelay = randomDelay - ((rand.nextInt(750) / (rand.nextInt(3) + 1)) * rand.nextInt(6) + 1);
            requeue(randomDelay);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Next RouterInfo check in " + randomDelay + "ms");
        } else {
            requeue(Integer.valueOf(refresh));
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Next RouterInfo check in " + refresh + "ms");
        }
    }
}

