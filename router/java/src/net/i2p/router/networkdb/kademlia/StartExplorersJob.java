package net.i2p.router.networkdb.kademlia;

import java.util.HashSet;
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
 * This job initiates exploration of the network database by selecting keys to explore
 * and queuing ExploreJobs up to a maximum per run. It adjusts the exploration frequency
 * and intensity based on network conditions such as number of known routers, floodfills,
 * system load, and message delays.
 * <p>
 * For hidden routers, this job helps maintain integration in the network
 * by aggressively exploring when known peers are few and reducing load otherwise.
 * The job is scheduled to run repeatedly with delays adapted to system load and network state.
 */
class StartExplorersJob extends JobImpl {
    private static final int MAX_PER_RUN = 2;
    private static final int MIN_RERUN_DELAY_MS = 90 * 1000;
    private static final int MAX_RERUN_DELAY_MS = 15 * 60 * 1000;
    private static final int STARTUP_TIME = 2 * 60 * 60 * 1000; // 2 hours
    private static final int MIN_ROUTERS = 2000;
    private static final int LOW_ROUTERS = 3000;
    private static final int MAX_ROUTERS = 5000;
    private static final int MIN_FFS = 400;
    static final int LOW_FFS = 2 * MIN_FFS;
    private static final long MAX_LAG = 150;
    private static final long MAX_MSG_DELAY = 650;
    private static final long DELAY_SPREAD_BASE = 500;
    private static final long DELAY_SPREAD_VARIANCE = 500;
    private static final long LAGGED_DELAY_MS = 3 * 60 * 1000L; // 3 minutes

    static final String PROP_EXPLORE_DELAY = "router.explorePeersDelay";
    static final String PROP_EXPLORE_BUCKETS = "router.exploreBuckets";
    static final String PROP_FORCE_EXPLORE = "router.exploreWhenFloodfill";

    private final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;
    private final long _msgIDBloomXor = RandomSource.getInstance().nextLong(I2NPMessage.MAX_ID_VALUE);

    /**
     * Constructs a StartExplorersJob to manage peer exploration scheduling.
     *
     * @param context the router context
     * @param facade the network database facade to interact with peer data
     */
    public StartExplorersJob(RouterContext context, KademliaNetworkDatabaseFacade facade) {
        super(context);
        _log = context.logManager().getLog(StartExplorersJob.class);
        _facade = facade;
    }

    @Override
    public String getName() {
        return "Start NetDb Explorers";
    }

    /**
     * Runs the job, determines if exploration should occur, how many explorations to run,
     * selects keys to explore, creates and queues ExploreJobs, and schedules the next run.
     */
    @Override
    public void runJob() {
        RouterContext ctx = getContext();

        // Cache relevant context properties
        final boolean forceExplore = ctx.getBooleanProperty(PROP_FORCE_EXPLORE);
        final int datastoreSize = _facade.getDataStore().size();
        final boolean isFloodfill = _facade.floodfillEnabled();
        final long lag = ctx.jobQueue().getMaxLag();
        final long msgDelay = ctx.throttle().getMessageDelay();
        final boolean highLoad = SystemVersion.getCPULoadAvg() > 95 && lag > 1000;
        final Status commStatus = ctx.commSystem().getStatus();

        if (isFloodfill && !forceExplore) {
            if (_log.shouldInfo()) {
                _log.info("Not initiating Peer Exploration -> We are a floodfill");
            }
            return;
        }

        if (!shouldExplore(lag, msgDelay, commStatus)) {
            // Conditions not met for exploration
            scheduleNextRun(ctx, lag, msgDelay, highLoad);
            return;
        }

        final int numExplorations = determineNumExplorations(ctx, datastoreSize, lag, msgDelay, highLoad);
        if (_log.shouldInfo()) {
            _log.info("Exploring " + numExplorations + " buckets during this run");
        }

        Set<Hash> keysToExplore = selectKeysToExplore(numExplorations);
        _facade.removeFromExploreKeys(keysToExplore);

        final int floodfillCount = ctx.peerManager().countPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL);
        final boolean needFloodfills = floodfillCount < MIN_FFS;
        final boolean lowFloodfills = floodfillCount < LOW_FFS;
        final java.util.Random random = ctx.random();

        long delay = 0;
        for (Hash key : keysToExplore) {
            boolean realExploration = !((needFloodfills && random.nextInt(2) == 0) || (lowFloodfills && random.nextInt(3) == 0));
            ExploreJob job = new ExploreJob(ctx, _facade, key, realExploration, _msgIDBloomXor);

            // Spread out job start times slightly to avoid bursts
            delay += DELAY_SPREAD_BASE + random.nextInt((int) DELAY_SPREAD_VARIANCE);
            job.getTiming().setStartAfter(ctx.clock().now() + delay);
            ctx.jobQueue().addJob(job);

            if (_log.shouldInfo()) {
                if (realExploration) {
                    _log.info("Exploring for new peers in " + delay + " ms");
                } else {
                    _log.info("Acquiring new floodfills in " + delay + " ms");
                }
            }
        }
        scheduleNextRun(ctx, lag, msgDelay, highLoad);
    }

    /**
     * Determines if conditions allow for exploration based on lag, message delay, and comm status.
     *
     * @param lag the current maximum job queue lag in ms
     * @param msgDelay the current message delay in ms
     * @param commStatus current communication system status
     * @return true if exploration should proceed
     */
    private boolean shouldExplore(long lag, long msgDelay, Status commStatus) {
        return lag <= MAX_LAG && msgDelay <= MAX_MSG_DELAY && commStatus != Status.DISCONNECTED;
    }

    /**
     * Dynamically determines the number of explorations to run based on system state.
     * Also respects configured overrides from context properties.
     *
     * @param ctx router context
     * @param datastoreSize number of known peers in the data store
     * @param lag maximum lag of job queue
     * @param msgDelay message delay
     * @param highLoad whether the system is deemed under high load
     * @return number of explorations to run this cycle
     */
    private int determineNumExplorations(RouterContext ctx, int datastoreSize, long lag, long msgDelay, boolean highLoad) {
        int num = MAX_PER_RUN;

        String exploreBucketsProp = ctx.getProperty(PROP_EXPLORE_BUCKETS);
        if (exploreBucketsProp != null) {
            try {
                return Integer.parseInt(exploreBucketsProp);
            } catch (NumberFormatException nfe) {
                _log.warn("Invalid value for exploreBuckets: " + exploreBucketsProp);
                // fallback to dynamic calculation
            }
        }

        if (datastoreSize < MIN_ROUTERS) {
            num *= 8; // extremely aggressive exploration
        } else if (datastoreSize < LOW_ROUTERS) {
            num *= 5; // moderately aggressive
        }

        if (ctx.router().getUptime() < STARTUP_TIME && datastoreSize < MAX_ROUTERS) {
            num *= 2;
        }

        if (datastoreSize < MAX_ROUTERS) {
            num += 1;
        }

        if (ctx.router().isHidden() && datastoreSize < MIN_ROUTERS) {
            num += 2;
        }

        if (lag > 500 || msgDelay > 1000 || highLoad) {
            num = 1;
        } else if (lag > 250 || msgDelay > 500) {
            num = Math.min(num, 2);
        }

        return num;
    }

    /**
     * Schedules the next job run based on current load and configured delay properties.
     *
     * @param ctx router context
     * @param lag current max lag in ms
     * @param msgDelay current message delay in ms
     * @param highLoad whether the system is under high load
     */
    private void scheduleNextRun(RouterContext ctx, long lag, long msgDelay, boolean highLoad) {
        String exploreDelayProp = ctx.getProperty(PROP_EXPLORE_DELAY);
        final long defaultDelay = getNextRunDelay();
        final int floodfillCount = ctx.peerManager().countPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL);
        final boolean needFloodfills = floodfillCount < MIN_FFS;

        if (exploreDelayProp != null && !highLoad) {
            try {
                long delay = Integer.parseInt(exploreDelayProp) * 1000L;
                if (_log.shouldInfo()) {
                    _log.info("Next Peer Exploration run in " + (delay / 1000) + " s");
                }
                requeue(delay);
                return;
            } catch (NumberFormatException nfe) {
                _log.warn("Invalid explorer delay property: " + exploreDelayProp);
                // fallback below
            }
        }

        if ((lag > 500 || msgDelay > 750 || (highLoad && !needFloodfills))) {
            if (_log.shouldInfo()) {
                _log.info("Next Peer Exploration run in " + (LAGGED_DELAY_MS / 1000) + " s (router is under load)");
            }
            requeue(LAGGED_DELAY_MS);
        } else {
            if (_log.shouldInfo()) {
                _log.info("Next Peer Exploration run in " + (defaultDelay / 1000) + " s");
            }
            requeue(defaultDelay);
        }
    }

    /**
     * Calculates the delay until the next peer exploration job run based on router state and uptime.
     *
     * @return delay in milliseconds before the next exploration run
     */
    private long getNextRunDelay() {
        RouterContext ctx = getContext();
        final String exploreDelay = ctx.getProperty(PROP_EXPLORE_DELAY);
        final String exploreWhenFloodfill = ctx.getProperty(PROP_FORCE_EXPLORE);
        final boolean isFloodfill = _facade.floodfillEnabled();
        final boolean isHidden = ctx.router().isHidden();
        final RouterInfo ri = ctx.router().getRouterInfo();
        final String capabilities = ri != null ? ri.getCapabilities() : "";
        final boolean isSlow = ri != null && !capabilities.isEmpty() &&
                (capabilities.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0
                        || capabilities.indexOf(Router.CAPABILITY_BW12) >= 0
                        || capabilities.indexOf(Router.CAPABILITY_BW32) >= 0);

        final int netDbSize = ctx.netDb().getKnownRouters();
        final long uptime = ctx.router().getUptime();
        final long delaySinceLastExplore = ctx.clock().now() - _facade.getLastExploreNewDate();

        if (exploreDelay == null) {
            if (delaySinceLastExplore > MAX_RERUN_DELAY_MS && !isFloodfill) {
                return MAX_RERUN_DELAY_MS;
            } else if (isFloodfill
                    && (exploreWhenFloodfill == null || "false".equals(exploreWhenFloodfill))
                    && uptime > STARTUP_TIME || netDbSize > MAX_ROUTERS) {
                return MAX_RERUN_DELAY_MS * 2; // 30 minutes
            } else if ((uptime < STARTUP_TIME && netDbSize < MIN_ROUTERS) || isHidden || isSlow) {
                return MIN_RERUN_DELAY_MS;
            } else {
                return Math.max(0, delaySinceLastExplore);
            }
        } else {
            try {
                return Integer.parseInt(exploreDelay) * 1000L;
            } catch (NumberFormatException nfe) {
                _log.warn("Invalid explorePeersDelay property, using default delay");
                return MIN_RERUN_DELAY_MS;
            }
        }
    }

    /**
     * Selects a set of keys from the exploration pool, supplementing with random keys if needed.
     *
     * @param num number of keys to select
     * @return a set of keys to explore of size 'num'
     */
    private Set<Hash> selectKeysToExplore(int num) {
        Set<Hash> queued = _facade.getExploreKeys();
        Set<Hash> keys = new HashSet<>(num);

        for (Hash key : queued) {
            keys.add(key);
            if (keys.size() >= num) break;
        }

        while (keys.size() < num) {
            byte[] hashBytes = new byte[Hash.HASH_LENGTH];
            getContext().random().nextBytes(hashBytes);
            keys.add(new Hash(hashBytes));
        }

        if (_log.shouldInfo()) {
            _log.info("Keys waiting for exploration: " + queued.size());
        }
        return keys;
    }
}
