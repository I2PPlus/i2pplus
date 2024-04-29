package net.i2p.router.networkdb.kademlia;

import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.networkdb.kademlia.KademliaNetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

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
 * As of 0.9.45, periodically rerun, to maintain a minimum number of
 * floodfills, primarily for hidden mode. StartExplorersJob will get us
 * to about 100 ffs and maintain that for a while, but they will eventually
 * start to expire. Use this to get us to 300 or more. Each pass of this
 * will gain us about 150 ffs. If we have more than 300 ffs, we just
 * requeue to check later. Otherwise this will grow our netdb
 * almost unbounded, as it prevents most normal expiration.
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
    private final static long RERUN_DELAY_MS = 2*1000; // 2 seconds * random value (see below)
    static final String PROP_RERUN_DELAY_MS = "router.refreshRouterDelay";
    static final String PROP_ROUTER_FRESHNESS = "router.refreshSkipIfYounger";
    static final String PROP_ROUTER_REFRESH_TIMEOUT = "router.refreshTimeout";
    static final String PROP_ROUTER_REFRESH_UNINTERESTING = "router.refreshUninteresting";
//    private final static long EXPIRE = 2*60*60*1000;
    private final static long EXPIRE = 7*24*60*60*1000;
    private final static long OLDER = 2*60*60*1000;
    private static long RESTART_DELAY_MS = 5*60*1000;
    private final static boolean DEFAULT_SHOULD_DISCONNECT = false;
    private final static String PROP_SHOULD_DISCONNECT = "router.enableImmediateDisconnect";

    public RefreshRoutersJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(RefreshRoutersJob.class);
        _facade = facade;
    }

    public String getName() { return "Refresh NetDb Routers"; }

    public void runJob() {
        Random rand = new Random();
        long lag = getContext().jobQueue().getMaxLag();
//        int netDbCount = getContext().netDbSegmentor().getKnownRouters();
        int netDbCount = getContext().netDb().getKnownRouters();
        long uptime = getContext().router().getUptime();
        boolean isCpuHighLoad = SystemVersion.getCPULoad() > 80;
        boolean shouldDisconnect = getContext().getProperty(PROP_SHOULD_DISCONNECT, DEFAULT_SHOULD_DISCONNECT);
        if (_facade.isInitialized() && lag < 500 &&
            getContext().commSystem().getStatus() != Status.DISCONNECTED &&
            netDbCount < 5000 && uptime > 60*1000) {
            if (_routers == null || _routers.isEmpty()) {
                // make a list of all routers, floodfill first
                _routers = _facade.getFloodfillPeers();
                int ff = _routers.size();
                Set<Hash> all = _facade.getAllRouters();
                all.removeAll(_routers);
                int non = all.size();
                _routers.addAll(all);
                if (_log.shouldInfo())
                    _log.info("To check: " + ff + " Floodfills and " + non + " non-Floodfills");
            }
            if (_routers.isEmpty()) {
                _routers = null;
                if (getContext().router().getUptime() < 60*60*1000) {
                    RESTART_DELAY_MS = 60*1000;
                } else if (netDbCount > 5000 && getContext().router().getUptime() > 60*60*1000) {
                    RESTART_DELAY_MS *= 12;
                } else if (netDbCount > 3000) {
                    RESTART_DELAY_MS *= rand.nextInt(12) + 1;
                } else if (netDbCount > 1000 || isCpuHighLoad) {
                    RESTART_DELAY_MS *= rand.nextInt(3) + 1;
                    requeue(RESTART_DELAY_MS);
                } else {
                    requeue(RESTART_DELAY_MS);
                }
                if (netDbCount > 5000)
                    _log.info("Finished refreshing NetDb; over 5000 known routers, job will rerun in " + (RESTART_DELAY_MS / 1000 / 60) + "m");
                else
                    _log.info("Finished refreshing NetDb routers; job will rerun in " + (RESTART_DELAY_MS / 1000) + "s");
                return;
            }
            long expire = getContext().clock().now() - EXPIRE;
            for (Iterator<Hash> iter = _routers.iterator(); iter.hasNext(); ) {
                Hash h = iter.next();
                iter.remove();
                if (h.equals(getContext().routerHash()))
                    continue;
                if (_log.shouldDebug())
                    _log.debug("Checking RouterInfo [" + h.toBase64().substring(0,6) + "]");
//                RouterInfo ri = _facade.lookupRouterInfoLocally(h);
                RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(h);
                if (ri == null)
                    continue;
//                if (ri.getPublished() < expire) {
//                long older = getContext().clock().now() - OLDER;
                long older = getContext().clock().now() - ri.getPublished();
                String freshness = getContext().getProperty("router.refreshSkipIfYounger");
                String refreshTimeout = getContext().getProperty("router.refreshTimeout");
                int routerAge = 15*60*1000;
                String v = ri.getVersion();
                String MIN_VERSION = "0.9.58";
                Hash us = getContext().routerHash();
                boolean isUs = us.equals(ri.getIdentity().getHash());
                boolean isHidden = getContext().router().isHidden();
                boolean uninteresting = (ri.getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 ||
                                         ri.getCapabilities().indexOf(Router.CAPABILITY_BW12) >= 0 ||
                                         ri.getCapabilities().indexOf(Router.CAPABILITY_BW32) >= 0 ||
                                         VersionComparator.comp(v, MIN_VERSION) < 0) &&
                                         getContext().netDb().getKnownRouters() > 3000 &&
                                         uptime > 15*60*1000 && !isHidden && !isUs;
                boolean refreshUninteresting = getContext().getBooleanProperty(PROP_ROUTER_REFRESH_UNINTERESTING);
                String caps = "unknown";
                boolean noSSU = true;
                boolean isFF = false;
                String country = "unknown";
                boolean noCountry = true;

                if (ri != null) {
                    caps = ri.getCapabilities().toUpperCase();
                    if (caps.contains("F")) {
                        isFF = true;
                    }
                    country = getContext().commSystem().getCountry(h);
                    if (country != null && country != "unknown") {
                        noCountry = false;
                    }
                }
                if (ri != null) {
                    for (RouterAddress ra : ri.getAddresses()) {
                        if (ra.getTransportStyle().equals("SSU") ||
                            ra.getTransportStyle().equals("SSU2")) {
                            noSSU = false;
                            break;
                        }
                    }
                }
                int rapidScan = 10*60*1000;
                if (uninteresting || (isFF && noSSU)) {
                    routerAge = rapidScan;
                } else if (freshness == null) {
                    if (netDbCount > 4000)
                        routerAge = 6*60*60*1000;
                    if (netDbCount > 6000)
                        routerAge = 8*60*60*1000;
                } else {
                    routerAge = Integer.valueOf(freshness)*60*60*1000;
                }
//                if (ri.getPublished() < older) {
                if (older > routerAge) {
                    if (_log.shouldInfo())
                        if (refreshTimeout == null)
                            _log.info("Refreshing Router [" + h.toBase64().substring(0,6) + "]" +
                                      "\n* Published: " + new Date(ri.getPublished()));
                        else
                            _log.info("Refreshing Router [" + h.toBase64().substring(0,6) + "] - " +
                                      Integer.valueOf(refreshTimeout) + "s timeout" +
                                      "\n* Published: " + new Date(ri.getPublished()));
//                    _facade.search(h, null, null, 15*1000, false);
//                    Job DropLookupFoundJob = new _facade.DropLookupFoundJob();
//                    _facade.search(h, null, new DropLookupFoundJob(getContext(), h, ri) , 20*1000, false);
                    if (refreshTimeout == null && uptime < 60*60*1000)
                        _facade.search(h, null, null, 20*1000, false);
                    else if (refreshTimeout == null && uptime < 8*60*60*1000)
                        _facade.search(h, null, null, 15*1000, false);
                    else if (refreshTimeout == null && uptime > 8*60*60*1000)
                        _facade.search(h, null, null, 10*1000, false);
                    else
                        _facade.search(h, null, null, Integer.valueOf(refreshTimeout)*1000, false);
                    break;
                } else {
                    if (_log.shouldDebug())
                        if ((routerAge / 60 / 60 / 1000) <= 1 && freshness == null && !uninteresting && !refreshUninteresting) {
                            _log.debug("Skipping refresh of Router [" + h.toBase64().substring(0,6) + "] -> Less than 1h old" +
                                       "\n* Published: " + new Date(ri.getPublished()));
                    } else if (uninteresting && refreshUninteresting && (routerAge / 60 / 60 / 1000) <= 1) {
                        _log.debug("Skipping refresh of uninteresting Router [" + h.toBase64().substring(0,6) + "] -> Less than " +
                                   (rapidScan / 60 / 1000) + "m old \n* Published: " + new Date(ri.getPublished()));
                    } else if (uninteresting && !refreshUninteresting && !isHidden) {
                        _log.debug("Skipping refresh of Router [" + h.toBase64().substring(0,6) + "] -> Uninteresting");
                    } else if (noSSU && isFF) {
                        _log.debug("Skipping refresh of Router [" + h.toBase64().substring(0,6) + "] -> Floodfill with SSU disabled");
/**
                    } else if (noCountry && uptime > 45*1000) {
                        _log.debug("Skipping refresh of Router [" + h.toBase64().substring(0,6) + "] -> Address not resolvable via GeoIP");
                        if (_log.shouldWarn())
                            _log.warn("Banning " + (isFF ? "Floodfill" : "Router") + " [" + h.toBase64().substring(0,6) + "] for 15m -> Address not resolvable via GeoIP");
                        if (isFF) {
                            getContext().banlist().banlistRouter(h, " <b>➜</b> Floodfill without GeoIP resolvable address", null, null, getContext().clock().now() + 15*60*1000);
                        } else {
                            getContext().banlist().banlistRouter(h, " <b>➜</b> No GeoIP resolvable address", null, null, getContext().clock().now() + 15*60*1000);
                        }
                        if (shouldDisconnect) {
                            getContext().simpleTimer2().addEvent(new Disconnector(h), 3*1000);
                        }
**/
                    } else {
                        _log.debug("Skipping refresh of Router [" + h.toBase64().substring(0,6) + "] -> less than " +
                                   (routerAge / 60 / 60 / 1000) + "h old \n* Published: " + new Date(ri.getPublished()));
                    }
                    break;
                }
            }
        } else {
            if (netDbCount > 5000) {
                _log.info("Over 5000 known routers, suspending Refresh Routers job...");
            } else if (lag > 500) {
                _log.info("Job lag over 500ms, suspending Refresh Routers job...");
            } else if (getContext().commSystem().getStatus() == Status.DISCONNECTED) {
                _log.info("Network disconnected, suspending Refresh Routers job...");
            }
        }

        int randomDelay = (1500 * (rand.nextInt(3) + 1)) + rand.nextInt(1000) + rand.nextInt(1000) + (rand.nextInt(1000) * (rand.nextInt(3) + 1)); // max 9.5 seconds
        String refresh = getContext().getProperty("router.refreshRouterDelay");
        if (netDbCount > 3000) {
            randomDelay *= 10 ;
            if (_log.shouldDebug())
                _log.debug("Over 3000 known peers, queuing next RouterInfo check to run in " + randomDelay / 1000 + "s...");
        } else if (refresh == null) {
            if (getContext().jobQueue().getMaxLag() > 150 || getContext().throttle().getMessageDelay() > 750)
                randomDelay = randomDelay * (rand.nextInt(3) + 1);
            else if (netDbCount < 500 || getContext().router().getUptime() < 30*60*1000)
                randomDelay = Math.max(Math.min(randomDelay - 6000, randomDelay - rand.nextInt(7000)), 300 + rand.nextInt(150));
            else if (netDbCount < 1000)
                randomDelay = Math.max(randomDelay - rand.nextInt(1250) - rand.nextInt(1250), 400 + rand.nextInt(150));
            else if (netDbCount < 2000)
                randomDelay = randomDelay - (rand.nextInt(750) / (rand.nextInt(3) + 1));
            else
                randomDelay = randomDelay - ((rand.nextInt(750) / (rand.nextInt(3) + 1)) * rand.nextInt(6) + 1);
            requeue(randomDelay);
            if (_log.shouldDebug())
                _log.debug("Next RouterInfo check in " + randomDelay + "ms");
        } else {
            requeue(Integer.valueOf(refresh));
            if (_log.shouldDebug())
                _log.debug("Next RouterInfo check in " + refresh + "ms");
        }
    }

    private class Disconnector implements SimpleTimer.TimedEvent {
        private final Hash h;
        public Disconnector(Hash h) { this.h = h; }
        public void timeReached() {
            getContext().commSystem().forceDisconnect(h);
        }
    }
}

