package net.i2p.router.networkdb.kademlia;

import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;

import java.util.List;
import java.util.ArrayList;
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
import net.i2p.util.SimpleTimer2;
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

    private long _restartDelayMs = 60 * 1000L;            // Default restart delay 1 min

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
            requeue(_restartDelayMs);
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
            && ctx.router().getUptime() > 60 * 1000L;
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
     * Score a peer for refresh priority.
     * Higher score = refresh sooner (high-value peers first).
     * @since 0.9.70
     */
    private int scorePeer(Hash peer) {
        RouterContext ctx = getContext();
        RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(peer);
        if (ri == null) return 0;
        int score = 0;
        String cap = ri.getCapabilities();
        if (cap != null) {
            if (cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0) score += 10;
            if (cap.indexOf(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL) >= 0) score += 5;
        }
        String bw = ri.getBandwidthTier();
        if (bw != null) {
            if (bw.equals("X")) score += 8;
            else if (bw.equals("P")) score += 6;
            else if (bw.equals("O")) score += 4;
        }
        return score;
    }

    /**
     * Initializes the router list with floodfill routers sorted first,
     * then all other routers sorted by performance score.
     */
    private void initializeRouterListIfNeeded() {
        if (_routers == null || _routers.isEmpty()) {
            // Create defensive copies to avoid ConcurrentModificationException
            List<Hash> floodfills = new ArrayList<>(_facade.getFloodfillPeers());
            Set<Hash> allRouters = new HashSet<>(_facade.getAllRouters());
            allRouters.removeAll(floodfills);
            // Sort non-floodfill peers by score so high-value peers are refreshed first
            List<Hash> nonFloodfills = new ArrayList<>(allRouters);
            Collections.sort(nonFloodfills, new Comparator<Hash>() {
                public int compare(Hash a, Hash b) {
                    return scorePeer(b) - scorePeer(a);
                }
            });
            _routers = new ArrayList<>(floodfills);
            _routers.addAll(nonFloodfills);

            if (_log.shouldInfo()) {
                _log.info("To check: " + floodfills.size()
                    + " Floodfills and " + nonFloodfills.size() + " non-Floodfills");
            }
        }
    }

    /**
     * Adjust _restartDelayMs based on known router count and uptime.
     */
    private void adjustRestartDelayBasedOnNetDbCount() {
        int netDbCount = getContext().netDb().getKnownRouters();
        long uptime = getContext().router().getUptime();
        if (uptime < 60 * 60 * 1000L) {
            _restartDelayMs = 30 * 1000L;
        } else if (netDbCount > 10000) {
            _restartDelayMs *= 3;
        } else if (netDbCount > 6000) {
            _restartDelayMs *= 2;
        }
    }

    /**
     * Logs completion message when refresh cycle finishes.
     */
    private void logRefreshCompletion() {
        int netDbCount = getContext().netDb().getKnownRouters();
        if (netDbCount > 10000) {
            _log.info(String.format("Finished refreshing NetDb; over 5000 known routers, job will rerun in %dm",
                (_restartDelayMs / 1000 / 60)));
        } else {
            _log.info(String.format("Finished refreshing NetDb routers; job will rerun in %ds",
                (_restartDelayMs / 1000)));
        }
    }

    /**
     * Processes the next router(s) in the list to determine whether to refresh their info.
     * Processes up to MAX_PER_CYCLE routers per invocation for faster coverage.
     * @since 0.9.70
     */
    private void processNextRouterForRefresh() {
        RouterContext ctx = getContext();
        int processed = 0;
        int maxPerCycle = ctx.getProperty("router.refreshBatchSize", 5);
        while (processed < maxPerCycle) {
            Hash routerHash;
            synchronized (this) {
                if (_routers == null || _routers.isEmpty()) return;
                routerHash = _routers.remove(0);
            }

            if (routerHash == null) continue;
            if (routerHash.equals(ctx.routerHash())) continue;

            RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(routerHash);
            if (ri == null) continue;

            if (shouldRefreshRouter(ri, routerHash)) {
                performRouterRefresh(routerHash, ri);
                processed++;
            }
        }
    }

    /**
     * Determines whether to refresh the given router info.
     * Includes checks for age, version, capabilities, and context properties.
     */
    private boolean shouldRefreshRouter(RouterInfo ri, Hash _routerHash) {
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
                                && uptime > 15 * 60 * 1000L
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
                                      long _uptime, boolean isFloodfill) {
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
            if (uptime < 60 * 60 * 1000L) {refreshTimeoutSeconds = 20;}
            else if (uptime < 8 * 60 * 60 * 1000L) {refreshTimeoutSeconds = 15;}
            else {refreshTimeoutSeconds = 10;}
        }

        // Reverse DNS lookup leveraging CommSystemFacadeImpl cache
        if (enableReverseLookups && uptime > 30 * 1000L) {
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
            _log.info(String.format("Refreshing Router [%s] - %ds timeout%n* Published: %s",
                routerHash.toBase64().substring(0, 6), refreshTimeoutSeconds, new Date(ri.getPublished())));
        }

        _facade.search(routerHash, null, null, refreshTimeoutSeconds * 1000L, false);
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
            } else if (netDbCount < 500 || ctx.router().getUptime() < 30 * 60 * 1000L) {
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

    private class Disconnector extends SimpleTimer2.TimedEvent {
        private final Hash h;
        public Disconnector(Hash h) {super(getContext().simpleTimer2()); this.h = h;}
        public void timeReached() {getContext().commSystem().forceDisconnect(h, "Routers refresh timeout");}
    }

}
