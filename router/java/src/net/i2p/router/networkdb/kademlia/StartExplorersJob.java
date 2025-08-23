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
import java.util.Random;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;
import net.i2p.util.SystemVersion;

/**
 * Fire off search jobs for random keys from the explore pool, up to MAX_PER_RUN at a time.
 * If the explore pool is empty, just search for a random key.
 *
 * For hidden mode routers, this is the primary mechanism for staying integrated.
 * The goal is to keep known router count above LOW_ROUTERS and
 * the known floodfill count above LOW_FFS.
 */
class StartExplorersJob extends JobImpl {
    private final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;

    /** don't explore more than 1 bucket at a time */
    private static final int MAX_PER_RUN = 2;
    /** don't explore the network more often than this */
    private static final int MIN_RERUN_DELAY_MS = 90*1000;
    /** explore the network at least this often */
    private static final int MAX_RERUN_DELAY_MS = 15*60*1000;
    /** aggressively explore during this time - same as KNDF expiration grace period */
    private static final int STARTUP_TIME = 2*60*60*1000; // let's give it 2 hours
    /** very aggressively explore if we have less than this many routers */
    private static final int MIN_ROUTERS = 2000;
    /** aggressively explore if we have less than this many routers */
    private static final int LOW_ROUTERS = 3000;
    /** explore slowly if we have more than this many routers */
    private static final int MAX_ROUTERS = 5000;
    private static final int MIN_FFS = 400;
    static final int LOW_FFS = 2 * MIN_FFS;
    private static final long MAX_LAG = 150;
    private static final long MAX_MSG_DELAY = 650;
    private final long _msgIDBloomXor = RandomSource.getInstance().nextLong(I2NPMessage.MAX_ID_VALUE);
    static final String PROP_EXPLORE_DELAY = "router.explorePeersDelay";
    static final String PROP_EXPLORE_BUCKETS = "router.exploreBuckets";
    static final String PROP_FORCE_EXPLORE = "router.exploreWhenFloodfill";

    public StartExplorersJob(RouterContext context, KademliaNetworkDatabaseFacade facade) {
        super(context);
        _log = context.logManager().getLog(StartExplorersJob.class);
        _facade = facade;
    }

    public String getName() { return "Start NetDb Explorers"; }

    public void runJob() {
        RouterContext ctx = getContext();
        int count = _facade.getDataStore().size();
        final boolean forceExplore = ctx.getBooleanProperty(PROP_FORCE_EXPLORE);
        final boolean isFF = _facade.floodfillEnabled();
        long lag = ctx.jobQueue().getMaxLag();
        long msgDelay = ctx.throttle().getMessageDelay();
        boolean highLoad = SystemVersion.getCPULoadAvg() > 95 && lag > 1000;

        // Helper method to check if we can proceed exploring
        if (!isFF || forceExplore) {
            if (shouldExplore(lag, msgDelay, ctx.commSystem().getStatus())) {

                int num = determineNumExplorations(ctx, count, lag, msgDelay, highLoad);
                Set<Hash> toExplore = selectKeysToExplore(num);

                if (_log.shouldInfo()) {_log.info("Exploring " + num + " buckets during this run");}

                _facade.removeFromExploreKeys(toExplore);

                int ffs = ctx.peerManager().countPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL);
                boolean needFFs = ffs < MIN_FFS;
                boolean lowFFs = ffs < LOW_FFS;

                Random random = ctx.random();
                long delay = 0;
                for (Hash key : toExplore) {
                    boolean realExploration = !((needFFs && random.nextInt(2) == 0) ||
                                                (lowFFs && random.nextInt(3) == 0));
                    ExploreJob job = new ExploreJob(ctx, _facade, key, realExploration, _msgIDBloomXor);
                    delay += 500 + random.nextInt(500); // spread start times by 500-1000 ms
                    job.getTiming().setStartAfter(ctx.clock().now() + delay);
                    ctx.jobQueue().addJob(job);

                    if (_log.shouldInfo()) {
                        if (realExploration) {_log.info("Exploring for new peers in " + delay + "ms");}
                        else {_log.info("Acquiring new floodfills in " + delay + "ms");}
                    }
                }
            }

            // Schedule next job run delay
            scheduleNextRun(ctx, lag, msgDelay, highLoad);

        } else if (_log.shouldInfo()) {_log.info("Not initiating Peer Exploration -> We are a floodfill");}
    }

    private boolean shouldExplore(long lag, long msgDelay, Status commStatus) {
        return !(lag > MAX_LAG || msgDelay > MAX_MSG_DELAY || commStatus == Status.DISCONNECTED);
    }

    private int determineNumExplorations(RouterContext ctx, int datastoreSize, long lag, long msgDelay, boolean highLoad) {
        int num = MAX_PER_RUN;
        String exploreBuckets = ctx.getProperty(PROP_EXPLORE_BUCKETS);
        if (exploreBuckets != null) {
            try {return Integer.parseInt(exploreBuckets);}
            catch (NumberFormatException nfe) {_log.warn("Invalid value for exploreBuckets: " + exploreBuckets);}
            // fallback to dynamic calculation below
        }

        if (datastoreSize < MIN_ROUTERS) {num *= 8;} // extremely aggressive if very few routers
        else if (datastoreSize < LOW_ROUTERS) {num *= 5;} // moderate aggression
        if (ctx.router().getUptime() < STARTUP_TIME && datastoreSize < MAX_ROUTERS) {num *= 2;}
        if (datastoreSize < MAX_ROUTERS) {num += 1;}
        if (ctx.router().isHidden() && datastoreSize < MIN_ROUTERS) {num += 2;}
        if (lag > 500 || msgDelay > 1000 || highLoad) {num = 1;}
        else if (lag > 250 || msgDelay > 500) {num = 2;}
        return num;
    }

    private void scheduleNextRun(RouterContext ctx, long lag, long msgDelay, boolean highLoad) {
        String exploreDelay = ctx.getProperty(PROP_EXPLORE_DELAY);
        long defaultDelay = getNextRunDelay();
        long laggedDelay = 3 * 60 * 1000L; // 3 minutes

        if (exploreDelay != null && !highLoad) {
            if (_log.shouldInfo()) {_log.info("Next Peer Exploration run in " + exploreDelay + "s");}
            try {
                requeue(Integer.parseInt(exploreDelay) * 1000L);
                return;
            } catch (NumberFormatException nfe) {
                _log.warn("Invalid explorer delay property: " + exploreDelay);
                // fallback below
            }
        }

        if (lag > 500 || msgDelay > 750 || highLoad && !needFFs) {
            if (_log.shouldInfo()) {
                _log.info("Next Peer Exploration run in " + (laggedDelay / 1000) + "s (router is under load)");
            }
            requeue(laggedDelay);
        } else {
            if (_log.shouldInfo()) {
                _log.info("Next Peer Exploration run in " + (defaultDelay / 1000) + "s");
            }
            requeue(defaultDelay);
        }
    }

    /**
     *  How long should we wait before exploring?
     *  We wait as long as it's been since we were last successful,
     *  with exceptions.
     */
    private long getNextRunDelay() {
        RouterContext ctx = getContext();
        String exploreDelay = ctx.getProperty("router.explorePeersDelay");
        String exploreWhenFloodfill = ctx.getProperty("router.exploreWhenFloodfill");
        boolean isFloodfill = _facade.floodfillEnabled();
        boolean isHidden =  ctx.router().isHidden();
        RouterInfo ri = ctx.router().getRouterInfo();
        String caps = ri != null ? ri.getCapabilities() : "";
        boolean isSlow = ri != null && !caps.equals("") && (caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 ||
                         caps.indexOf(Router.CAPABILITY_BW12) >= 0 || caps.indexOf(Router.CAPABILITY_BW32) >= 0);
        int netDbSize = ctx.netDb().getKnownRouters();
        long uptime = ctx.router().getUptime();
        long delay = ctx.clock().now() - _facade.getLastExploreNewDate();
        if (exploreDelay == null) {
            if (delay > MAX_RERUN_DELAY_MS && !isFloodfill) {return MAX_RERUN_DELAY_MS;} // we don't explore if floodfill
            else if (isFloodfill && (exploreWhenFloodfill == null || "false".equals(exploreWhenFloodfill)) && uptime > STARTUP_TIME ||
                     netDbSize > MAX_ROUTERS) {return MAX_RERUN_DELAY_MS * 2;} // every 20mins
            // If we don't know too many peers, or just started, explore aggressively
            // Also if hidden or K/L/U, as nobody will be connecting to us
            // Use DataStore.size() which includes leasesets because it's faster
            else if ((uptime < STARTUP_TIME && netDbSize < MIN_ROUTERS) || isHidden || isSlow) {return MIN_RERUN_DELAY_MS;}
            else {return delay;}
        } else {return Integer.parseInt(exploreDelay) * 1000;}
    }

    /**
     * Run through the explore pool and pick out some values
     *
     * Nope, ExploreKeySelectorJob is disabled, so the explore pool
     * may be empty. In that case, generate random keys.
     */
    private Set<Hash> selectKeysToExplore(int num) {
        Set<Hash> queued = _facade.getExploreKeys();
        Set<Hash> rv = new HashSet<Hash>(num);
        for (Hash key : queued) {
            rv.add(key);
            if (rv.size() >= num) break;
        }
        for (int i = rv.size(); i < num; i++) {
            byte hash[] = new byte[Hash.HASH_LENGTH];
            getContext().random().nextBytes(hash);
            Hash key = new Hash(hash);
            rv.add(key);
        }
        if (_log.shouldInfo()) {_log.info("Keys waiting for exploration: " + queued.size());}
        return rv;
    }

}