package net.i2p.router.networkdb.kademlia;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.util.Log;

/**
 * A job that periodically republishes a local LeaseSet to the network database.
 *
 * This job handles the lifecycle of lease set publication including:
 * <ul>
 *   <li>Initial publication when the router has been running for sufficient uptime</li>
 *   <li>Periodic republishing before lease expiration (every 7 minutes)</li>
 *   <li>Retry logic with exponential backoff on publication failure</li>
 *   <li>Floodfill verification of published lease sets</li>
 *   <li>Cleanup of expired/stale lease sets when service stops</li>
 * </ul>
 *
 * The job manages a retry mechanism that:
 * <ul>
 *   <li>Retries every 2 seconds on failure</li>
 *   <li>Elevates to high priority every 4th attempt</li>
 *   <li>Verifies publication via floodfill peers after 3 failures</li>
 *   <li>Stops publishing when the client is no longer local</li>
 * </ul>
 *
 * This class is thread-safe and uses concurrent maps for tracking retry state
 * and logging throttling across multiple instances.
 */
public class RepublishLeaseSetJob extends JobImpl {
    private final Log _log;
    public final static long REPUBLISH_LEASESET_TIMEOUT = 30 * 1000;
    private final static int RETRY_DELAY = 2000;
    private final static long REPUBLISH_INTERVAL = 8 * 60 * 1000; // 8 minutes
    private static final long CACHE_CLEANUP_THRESHOLD = 15 * 60 * 1000;
    private static final ConcurrentHashMap<Hash, Boolean> _retryInProgress = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Hash, Long> _lastPublishLogTime = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Hash, Long> _lastVerifyLogTime = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Hash, Long> _lastNotRequeueLogTime = new ConcurrentHashMap<>();
    private final Hash _dest;
    private final KademliaNetworkDatabaseFacade _facade;
    private long _lastPublished;
    private final AtomicInteger failCount = new AtomicInteger(0);
    private boolean highPriority;
    private volatile boolean _lookupInProgress;

    public RepublishLeaseSetJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade, Hash destHash) {
        super(ctx);
        _log = ctx.logManager().getLog(RepublishLeaseSetJob.class);
        _facade = facade;
        _dest = destHash;
        registerSelf();
    }

    private void registerSelf() {
        _facade.registerPublishingJob(this);
    }

    @Override
    public void runJob() {
        long uptime = getContext().router().getUptime();

        try {
            if (!getContext().clientManager().shouldPublishLeaseSet(_dest)) {
                LeaseSet ls = _facade.lookupLeaseSetLocally(_dest);
                if (ls != null) {
                    _facade.fail(_dest);
                    if (_log.shouldDebug()) {
                        _log.debug("Cleaning up local LeaseSet [" + _dest.toBase32().substring(0,8) + "] on service stop");
                    }
                }
                _facade.stopPublishing(_dest);
                return;
            }
            if (uptime < 20 * 1000) {
                long delay = Math.max(1000, 20 * 1000 - uptime);
                scheduleRepublish(delay);
                return;
            }
            if (getContext().clientManager().isLocal(_dest)) {
                LeaseSet ls = _facade.lookupLeaseSetLocally(_dest);
                if (ls != null) {
                    String tunnelName = getTunnelName(ls.getDestination());
                    String name = !tunnelName.isEmpty() ? " for '" + tunnelName + "'" : " for key";
                    if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                        if (_log.shouldWarn()) {
                            _log.warn("Not publishing expired LOCAL LeaseSet" + name + " [" + _dest.toBase32().substring(0,8) + "]");
                        }
                        scheduleRepublish(REPUBLISH_INTERVAL);
                    } else {
                        long timeUntilExpiry = ls.getLatestLeaseDate() - getContext().clock().now();
                        long now = getContext().clock().now();
                        Long lastPubLog = _lastPublishLogTime.get(_dest);
                        if (_log.shouldInfo() && (lastPubLog == null || (now - lastPubLog > 10 * 1000))) {
                            _log.info("Attempting to publish LeaseSet" + name + " [" + _dest.toBase32().substring(0,8) +
                                       "] (expires in " + (timeUntilExpiry / 1000) + "s)...");
                            _lastPublishLogTime.put(_dest, now);
                            cleanupStaleEntries();
                        }
                        getContext().statManager().addRateData("netDb.republishLeaseSetCount", 1);
                        failCount.set(0);
                        _facade.sendStore(_dest, ls, null, new OnRepublishFailure(ls), REPUBLISH_LEASESET_TIMEOUT, null);
                        _lastPublished = now;
                        scheduleRepublish(REPUBLISH_INTERVAL);
                    }
                } else {
                    if (_log.shouldWarn()) {
                        _log.warn("Client [" + _dest.toBase32().substring(0,8) + "] is LOCAL, but no valid LeaseSet found -> Being rebuilt?");
                    }
                    clearRetryInProgress();
                    scheduleRepublish(REPUBLISH_INTERVAL);
                }
            } else {
                if (_log.shouldInfo()) {
                    _log.info("Client [" + _dest.toBase32().substring(0,8) + "] is no longer LOCAL -> Not republishing LeaseSet");
                }
                LeaseSet ls = _facade.lookupLeaseSetLocally(_dest);
                if (ls != null && !ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                    _facade.fail(_dest);
                }
                _facade.stopPublishing(_dest);
            }
        } catch (RuntimeException re) {
            if (_log.shouldError()) {_log.error("Uncaught error republishing the LeaseSet", re);}
            _facade.stopPublishing(_dest);
            throw re;
        } finally {
            _facade.removePublishingJob(_dest, this);
            clearRetryInProgress();
        }
    }

    public String getName() {return "Republish Local LeaseSet" + (highPriority ? " [High priority]" : "");}

    private void scheduleRepublish(long delayMs) {
        if (_facade.hasActiveRepublishJob(_dest)) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping republish for [" + _dest.toBase32().substring(0, 8) +
                           "] -> Job already active");
            }
            return;
        }
        RepublishLeaseSetJob nextJob = new RepublishLeaseSetJob(getContext(), _facade, _dest);
        nextJob.getTiming().setStartAfter(getContext().clock().now() + delayMs);
        getContext().jobQueue().addJob(nextJob);
    }

    void requeueRepublish() {
        if (_retryInProgress.putIfAbsent(_dest, Boolean.TRUE) != null) {
            if (_log.shouldDebug()) {
                _log.debug("Retry already in progress for " + _dest.toBase32().substring(0,8) + "] -> Skipping...");
            }
            return;
        }
        cleanupStaleEntries();
        if (_facade.hasActiveRepublishJob(_dest)) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping retry for [" + _dest.toBase32().substring(0, 8) + "] -> Job already active");
            }
            clearRetryInProgress();
            return;
        }
        int count = failCount.incrementAndGet();
        LeaseSet ls = getContext().clientManager().isLocal(_dest) ? _facade.lookupLeaseSetLocally(_dest) : null;
        String b32 = _dest.toBase32().substring(0,8);
        String tunnelName = ls != null ? getTunnelName(ls.getDestination()) : "";
        String name = !tunnelName.isEmpty() ? "'" + tunnelName + "'" + " [" + b32 + "]" : "[" + b32 + "]";
        String countStr = count > 1 ? " (Attempt: " + count + ")" : "";
        if (_log.shouldInfo() && count > 3) {
            _log.info("Failed to publish LeaseSet for " + name + " -> Retrying..." + countStr);
        }
        getContext().statManager().addRateData("netDb.republishLeaseSetFail", 1);

        long retryDelay = RETRY_DELAY;
        boolean isHighPriority = false;
        if (count % 4 == 0) {
            retryDelay = Math.max(100, RETRY_DELAY / 4);
            isHighPriority = true;
        }
        RepublishLeaseSetJob retryJob = new RepublishLeaseSetJob(getContext(), _facade, _dest);
        retryJob.getTiming().setStartAfter(getContext().clock().now() + retryDelay);
        if (isHighPriority) {
            retryJob.highPriority = true;
            getContext().jobQueue().addJobToTop(retryJob);
        } else {
            getContext().jobQueue().addJob(retryJob);
        }
    }

    private void clearRetryInProgress() {
        _retryInProgress.remove(_dest);
    }

    private static void cleanupStaleEntries() {
        long now = System.currentTimeMillis();
        cleanupMap(_lastPublishLogTime, now);
        cleanupMap(_lastVerifyLogTime, now);
        cleanupMap(_lastNotRequeueLogTime, now);
    }

    private static void cleanupMap(ConcurrentHashMap<Hash, Long> map, long now) {
        Iterator<Map.Entry<Hash, Long>> iter = map.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Hash, Long> entry = iter.next();
            if (now - entry.getValue() > CACHE_CLEANUP_THRESHOLD) {
                iter.remove();
            }
        }
    }

    public long lastPublished() {return _lastPublished;}

    Hash getDestHash() {return _dest;}

    private class OnRepublishFailure extends JobImpl {
        private final LeaseSet _ls;

        public OnRepublishFailure(LeaseSet ls) {
            super(RepublishLeaseSetJob.this.getContext());
            _ls = ls;
        }

        public String getName() {return "Timeout LeaseSet Publication";}

        public void runJob() {
            int count = failCount.get();
            LeaseSet ls = _facade.lookupLeaseSetLocally(_ls.getHash());
            String tunnelName = ls != null ? getTunnelName(_ls.getDestination()) : "";
            String name = !tunnelName.isEmpty() ? " for '" + tunnelName + "'" : "";

            if (ls == null || KademliaNetworkDatabaseFacade.isNewer(ls, _ls)) {
                clearRetryInProgress();
                long now = getContext().clock().now();
                Long lastNotRequeueLog = _lastNotRequeueLogTime.get(_ls.getHash());
                if (_log.shouldInfo() && (lastNotRequeueLog == null || (now - lastNotRequeueLog > 10 * 1000))) {
                    _log.info("Not requeueing LeaseSet" + name + " [" +
                              _ls.getDestination().calculateHash().toBase32().substring(0,8) +
                              "] -> Newer LeaseSet exists locally");
                    _lastNotRequeueLogTime.put(_ls.getHash(), now);
                    cleanupStaleEntries();
                }
                return;
            }

            if (count % 3 == 0 && !_lookupInProgress) {
                _lookupInProgress = true;
                long now = getContext().clock().now();
                Long lastVerifyLog = _lastVerifyLogTime.get(_ls.getHash());
                if (_log.shouldInfo() && (lastVerifyLog == null || (now - lastVerifyLog > 10 * 1000))) {
                    _log.info("Verifying LeaseSet publication" + name + " [" +
                              _ls.getDestination().calculateHash().toBase32().substring(0,8) + "] via floodfill...");
                    _lastVerifyLogTime.put(_ls.getHash(), now);
                    cleanupStaleEntries();
                }
                verifyAndRetry(ls);
            } else {
                requeueRepublish();
            }
        }

        private void verifyAndRetry(final LeaseSet ls) {
            String tunnelName = getTunnelName(ls.getDestination());
            String name = !tunnelName.isEmpty() ? " for '" + tunnelName + "'" : " for key";

            Job onFound = new JobImpl(getContext()) {
                public String getName() {return "Verify LS Published";}
                public void runJob() {
                    _lookupInProgress = false;
                    clearRetryInProgress();
                    LeaseSet local = _facade.lookupLeaseSetLocally(_ls.getHash());
                    if (local != null && KademliaNetworkDatabaseFacade.isNewer(local, ls)) {
                        if (_log.shouldInfo()) {
                            _log.info("Valid LeaseSet" + name + " confirmed via floodfill -> Skipping retry");
                        }
                    } else {
                        if (_log.shouldInfo()) {
                            _log.info("Valid LeaseSet" + name + " not confirmed via floodfill -> Retrying...");
                        }
                        requeueRepublish();
                    }
                }
            };

            Job onFailed = new JobImpl(getContext()) {
                public String getName() {return "Verify LS Failed";}
                public void runJob() {
                    _lookupInProgress = false;
                    clearRetryInProgress();
                    if (_log.shouldInfo()) {
                        _log.info("Valid LeaseSet" + name + " not found via floodfill -> Retrying...");
                    }
                    requeueRepublish();
                }
            };

            _facade.lookupLeaseSetRemotely(_ls.getHash(), onFound, onFailed, 10*1000, null);
        }
    }

    public String getTunnelName(Destination d) {
        TunnelPoolSettings in = getContext().tunnelManager().getInboundSettings(d.calculateHash());
        String name = (in != null ? in.getDestinationNickname() : null);
        if (name == null) {
            TunnelPoolSettings out = getContext().tunnelManager().getOutboundSettings(d.calculateHash());
            name = (out != null ? out.getDestinationNickname() : null);
        }
        return name != null ? name : "[" + d.calculateHash().toBase32().substring(0,8) + "]";
    }
}
