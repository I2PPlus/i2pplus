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
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.VersionComparator;

/**
 * RefreshRoutersJob performs router info refresh operations periodically.
 * <p>
 * After startup, refetches router infos, prioritizing floodfill routers,
 * to keep the network database fresh and healthy. This class tries to balance
 * load and avoid overload by controlling timing and refresh intervals.
 *
 * @since 0.8.9
 */
class RefreshRoutersJob extends JobImpl {
    private final Log _log;
    private final FloodfillNetworkDatabaseFacade _facade;
    private List<Hash> _routers;

    private static final long EXPIRE = 7 * 24 * 60 * 60 * 1000L; // 7 days
    private static final long OLDER = 2 * 60 * 60 * 1000L;       // 2 hours
    private static long RESTART_DELAY_MS = 60 * 1000;            // Default restart delay 1 min

    private static final String PROP_SHOULD_DISCONNECT = "router.enableImmediateDisconnect";
    private static final boolean DEFAULT_SHOULD_DISCONNECT = false;

    // Shared Random instance for efficient random number generation
    private static final Random _random = new Random();

    public RefreshRoutersJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(RefreshRoutersJob.class);
        _facade = facade;
    }

    @Override
    public String getName() {
        return "Refresh NetDb Routers";
    }

    /**
     * Entry point for the job execution.
     * Performs checks and delegates detailed router refresh logic.
     */
    @Override
    public void runJob() {
        if (!shouldRunJob()) {
            logSuspendedReason();
            scheduleNextRunRandom();
            return;
        }

        initializeRouterListIfNeeded();

        if (_routers == null || _routers.isEmpty()) {
            adjustRestartDelayBasedOnNetDbCount();
            logRefreshCompletion();
            requeue(RESTART_DELAY_MS);
            _routers = null;
            return;
        }

        processNextRouterForRefresh();
        scheduleNextRunRandom();
    }

    /**
     * Checks if conditions are suitable for running the refresh job.
     */
    private boolean shouldRunJob() {
        RouterContext ctx = getContext();
        return _facade.isInitialized()
            && ctx.jobQueue().getMaxLag() < 500
            && ctx.commSystem().getStatus() != Status.DISCONNECTED
            && ctx.router().getUptime() > 60 * 1000;
    }

    /**
     * Logs job suspension reasons if not running.
     */
    private void logSuspendedReason() {
        RouterContext ctx = getContext();
        if (ctx.jobQueue().getMaxLag() > 500)
            _log.info("Job lag over 500ms, suspending Refresh Routers job...");
        else if (ctx.commSystem().getStatus() == Status.DISCONNECTED)
            _log.info("Network disconnected, suspending Refresh Routers job...");
    }

    /**
     * Initializes the router list with floodfill routers first,
     * then all others appended.
     */
    private void initializeRouterListIfNeeded() {
        if (_routers == null || _routers.isEmpty()) {
            _routers = _facade.getFloodfillPeers();
            Set<Hash> allRouters = _facade.getAllRouters();
            allRouters.removeAll(_routers);
            _routers.addAll(allRouters);

            if (_log.shouldInfo()) {
                _log.info("To check: " + _facade.getFloodfillPeers().size()
                    + " Floodfills and " + allRouters.size() + " non-Floodfills");
            }
        }
    }

    /**
     * Adjust RESTART_DELAY_MS based on known router count and uptime.
     */
    private void adjustRestartDelayBasedOnNetDbCount() {
        int netDbCount = getContext().netDb().getKnownRouters();
        long uptime = getContext().router().getUptime();
        if (uptime < 60 * 60 * 1000) {
            RESTART_DELAY_MS = 30 * 1000;
        } else if (netDbCount > 10000) {
            RESTART_DELAY_MS *= 3;
        } else if (netDbCount > 6000) {
            RESTART_DELAY_MS *= 2;
        }
    }

    /**
     * Logs completion message when refresh cycle finishes.
     */
    private void logRefreshCompletion() {
        int netDbCount = getContext().netDb().getKnownRouters();
        if (netDbCount > 10000) {
            _log.info(String.format("Finished refreshing NetDb; over 5000 known routers, job will rerun in %dm",
                (RESTART_DELAY_MS / 1000 / 60)));
        } else {
            _log.info(String.format("Finished refreshing NetDb routers; job will rerun in %ds",
                (RESTART_DELAY_MS / 1000)));
        }
    }

    /**
     * Processes the next router in the list to determine whether to refresh its info.
     */
    private void processNextRouterForRefresh() {
        Iterator<Hash> iter = _routers.iterator();
        if (!iter.hasNext()) return;

        Hash routerHash = iter.next();
        iter.remove();

        if (routerHash.equals(getContext().routerHash())) {
            return;
        }

        RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(routerHash);
        if (ri == null) {
            return;
        }

        if (shouldRefreshRouter(ri, routerHash)) {
            performRouterRefresh(routerHash, ri);
        }
    }

    /**
     * Determines whether to refresh the given router info.
     * Includes checks for age, version, capabilities, and context properties.
     */
    private boolean shouldRefreshRouter(RouterInfo ri, Hash routerHash) {
        RouterContext ctx = getContext();
        long now = ctx.clock().now();
        long age = now - ri.getPublished();

        int netDbCount = ctx.netDb().getKnownRouters();
        long uptime = ctx.router().getUptime();

        String version = ri.getVersion();
        Hash us = ctx.routerHash();
        boolean isUs = us.equals(ri.getIdentity().getHash());
        boolean isFloodfill = ctx.netDb().floodfillEnabled();
        boolean isHidden = ctx.router().isHidden();

        // Uninteresting routers filter
        boolean uninteresting = (ri.getCapabilities().contains(Character.toString(Router.CAPABILITY_UNREACHABLE))
                                || ri.getCapabilities().contains(Character.toString(Router.CAPABILITY_BW12))
                                || ri.getCapabilities().contains(Character.toString(Router.CAPABILITY_BW32))
                                || VersionComparator.comp(version, "0.9.64") < 0)
                                && netDbCount > 5000
                                && uptime > 15 * 60 * 1000
                                && !isHidden
                                && !isUs;

        boolean refreshUninteresting = ctx.getBooleanProperty("router.refreshUninteresting");
        int routerAgeThreshold = getRouterAgeThreshold(uninteresting, refreshUninteresting, netDbCount, uptime, isFloodfill);

        return age > routerAgeThreshold;
    }

    /**
     * Returns the router age threshold in milliseconds for refresh decision.
     */
    private int getRouterAgeThreshold(boolean uninteresting, boolean refreshUninteresting, int netDbCount,
                                      long uptime, boolean isFloodfill) {
        final int FIFTEEN_MINUTES = 15 * 60 * 1000;
        final int RAPID_SCAN = 10 * 60 * 1000;

        if (uninteresting && !refreshUninteresting) {
            return RAPID_SCAN;
        } else {
            if (refreshUninteresting || !isFloodfill) {
                return FIFTEEN_MINUTES;
            } else if (netDbCount > 6000) {
                return 8 * 60 * 60 * 1000;
            } else if (netDbCount > 4000) {
                return 6 * 60 * 60 * 1000;
            } else {
                return FIFTEEN_MINUTES;
            }
        }
    }

    /**
     * Performs the router refresh operation by calling the facade search with proper timeout.
     */
    private void performRouterRefresh(Hash routerHash, RouterInfo ri) {
        RouterContext ctx = getContext();
        long uptime = ctx.router().getUptime();
        String refreshTimeoutProp = ctx.getProperty("router.refreshTimeout");
        boolean enableReverseLookups = ctx.getBooleanProperty("routerconsole.enableReverseLookups");
        int refreshTimeoutSeconds;

        if (refreshTimeoutProp != null) {
            refreshTimeoutSeconds = Integer.parseInt(refreshTimeoutProp);
        } else {
            if (uptime < 60 * 60 * 1000) {refreshTimeoutSeconds = 20;}
            else if (uptime < 8 * 60 * 60 * 1000) {refreshTimeoutSeconds = 15;}
            else {refreshTimeoutSeconds = 10;}
        }

        // Reverse DNS lookup leveraging CommSystemFacadeImpl cache
        if (enableReverseLookups && uptime > 30 * 1000) {
            RouterAddress address = null;
            for (RouterAddress ra : ri.getAddresses()) {
                if (ra.getTransportStyle().contains("SSU")) {
                    address = ra;
                    break;
                }
            }
            if (address != null) {
                String ipAddress = address.getHost();
                if (ipAddress != null && !ipAddress.isEmpty()) {
                    // Use the CommSystemFacadeImpl's reverse DNS cache and lookup method
                    String rdns = ctx.commSystem().getCanonicalHostName(ipAddress);
                    if (_log.shouldInfo()) {
                        _log.info("Reverse DNS for " + ipAddress + ": " + rdns);
                    }
                }
            }
        }

        if (_log.shouldInfo()) {
            _log.info(String.format("Refreshing Router [%s] - %ds timeout\n* Published: %s",
                routerHash.toBase64().substring(0, 6), refreshTimeoutSeconds, new Date(ri.getPublished())));
        }

        _facade.search(routerHash, null, null, refreshTimeoutSeconds * 1000, false);
    }

    /**
     * Schedules the next run of the job with randomized delay.
     * Delay depends on router count, system load, and configured properties.
     */
    private void scheduleNextRunRandom() {
        RouterContext ctx = getContext();
        int netDbCount = ctx.netDb().getKnownRouters();

        int baseDelay = (1500 * (_random.nextInt(3) + 1))
                + _random.nextInt(1000)
                + _random.nextInt(1000)
                + (_random.nextInt(1000) * (_random.nextInt(3) + 1));

        String refreshProp = ctx.getProperty("router.refreshRouterDelay");

        if (netDbCount > 10000) {
            baseDelay *= 10;
            if (_log.shouldDebug()) {
                _log.debug("Over 10000 known peers, queuing next RouterInfo check to run in " + baseDelay / 1000 + "s...");
            }
            requeue(baseDelay);
            return;
        }

        if (refreshProp == null) {
            // Adjust delay based on load and netDb count
            if (ctx.jobQueue().getMaxLag() > 150 || ctx.throttle().getMessageDelay() > 750) {
                baseDelay *= (_random.nextInt(3) + 1);
            } else if (netDbCount < 500 || ctx.router().getUptime() < 30 * 60 * 1000) {
                baseDelay = Math.max(Math.min(baseDelay - 6000, baseDelay - _random.nextInt(7000)),
                                     300 + _random.nextInt(150));
            } else if (netDbCount < 1000) {
                baseDelay = Math.max(baseDelay - _random.nextInt(1250) - _random.nextInt(1250),
                                     400 + _random.nextInt(150));
            } else if (netDbCount < 2000) {
                baseDelay -= _random.nextInt(750) / (_random.nextInt(3) + 1);
            } else {
                baseDelay -= ((_random.nextInt(750) / (_random.nextInt(3) + 1)) * _random.nextInt(6) + 1);
            }
            if (_log.shouldDebug()) {
                _log.debug("Next RouterInfo check in " + baseDelay + "ms");
            }
            requeue(baseDelay);
        } else {
            int delay = Integer.parseInt(refreshProp);
            if (_log.shouldDebug()) {
                _log.debug("Next RouterInfo check in " + delay + "ms");
            }
            requeue(delay);
        }
    }

    private class Disconnector implements SimpleTimer.TimedEvent {
        private final Hash h;
        public Disconnector(Hash h) {this.h = h;}
        public void timeReached() {getContext().commSystem().forceDisconnect(h);}
    }

}