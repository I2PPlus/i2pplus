package net.i2p.router.networkdb.kademlia;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final String PROP_TIMEOUT = "router.leaseSetPublishTimeout";
    private static final String PROP_RETRY_DELAY = "router.leaseSetPublishRetryDelay";
    private static final String PROP_MAX_RETRY_DELAY = "router.leaseSetPublishMaxRetryDelay";
    public static final long REPUBLISH_LEASESET_TIMEOUT_DEFAULT = 60L * 1000;
    public static final int RETRY_DELAY_DEFAULT = (int) (20L * 1000);
    public static final int RETRY_MAX_DELAY_DEFAULT = (int) (30L * 1000);
    private static final long EXPIRY_WINDOW = 3L * 60 * 1000;
    private static final long CACHE_CLEANUP_THRESHOLD = 15L * 60 * 1000;
    /** Last time cleanupStaleEntries() ran — guards against redundant sweeps */
    private static volatile long _lastCleanupTime;
    private static final ConcurrentHashMap<Hash, Boolean> _retryInProgress = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Hash, Long> _lastPublishLogTime = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Hash, Long> _lastVerifyLogTime = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Hash, Long> _lastNotRequeueLogTime = new ConcurrentHashMap<>();
    // Persistent per-destination failure count — never reset, survives job instances.
    // Used to decide when to perform expensive floodfill verification.
    private static final ConcurrentHashMap<Hash, AtomicInteger> _globalFailCount = new ConcurrentHashMap<>();
    private static final int MAX_FLOODFILL_VERIFICATIONS = 3;
    private final Hash _dest;
    private final KademliaNetworkDatabaseFacade _facade;
    private long _lastPublished;
    private final AtomicInteger failCount = new AtomicInteger(0);
    private boolean highPriority;
    private final AtomicBoolean _lookupInProgress = new AtomicBoolean(false);
    private boolean _registered = false;

    public RepublishLeaseSetJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade, Hash destHash) {
        super(ctx);
        _log = ctx.logManager().getLog(RepublishLeaseSetJob.class);
        _facade = facade;
        _dest = destHash;
        // Registration is now done explicitly after construction
    }

    /**
     * Attempt to register this job with the facade.
     * @return true if registration succeeded, false if a job was already active
     */
    public boolean registerSelf() {
        _registered = _facade.registerPublishingJob(this);
        return _registered;
    }

    @Override
    public void runJob() {
        cleanupStaleEntries();
        // Check if this job was successfully registered
        if (!_registered) {
            if (_log.shouldWarn()) {
                _log.warn("Job not registered for [" + _dest.toBase32().substring(0,8) + "] - skipping execution");
            }
            return;
        }

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
            if (uptime < 5L * 1000) {
                long delay = Math.max(1000, 5L * 1000 - uptime);
                scheduleRepublish(delay);
                return;
            }
            if (getContext().clientManager().isLocal(_dest)) {
                LeaseSet ls = _facade.lookupLeaseSetLocally(_dest);
                if (ls != null) {
                    String tunnelName = getTunnelName(ls.getDestination());
                    String name = !tunnelName.isEmpty() ? " for '" + tunnelName + "'" : " for key";
                    long now = getContext().clock().now();

                    if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                        // LeaseSet is already expired - request immediate rebuild
                        if (_log.shouldWarn()) {
                            _log.warn("LeaseSet EXPIRED - triggering immediate rebuild for " + name + " [" + _dest.toBase32().substring(0,8) + "]");
                        }
                        getContext().clientManager().requestLeaseSet(_dest, ls);
                        scheduleRepublish(getRepublishInterval());
                    } else {
                        long timeUntilExpiry = ls.getLatestLeaseDate() - now;

                        // Renew early enough to never expire - at least EXPIRY_WINDOW before expiry
                        Long lastPubLog = _lastPublishLogTime.get(_dest);
                        if (timeUntilExpiry <= EXPIRY_WINDOW) {
                            // Too close to expiry — trigger a fresh LS from the client NOW
                            if (_log.shouldInfo()) {
                                _log.info("LeaseSet expiring soon for " + name + " [" + _dest.toBase32().substring(0,8) +
                                          "] (expires in " + (timeUntilExpiry / 1000) + "s) — requesting immediate renew");
                            }
                            getContext().clientManager().requestLeaseSet(_dest, ls);
                            lastPubLog = null;
                        }
                        if (_log.shouldInfo() && (lastPubLog == null || (now - lastPubLog > 10L * 1000))) {
                            _log.info("Publishing LeaseSet" + name + " [" + _dest.toBase32().substring(0,8) +
                                       "] (expires in " + (timeUntilExpiry / 1000) + "s)...");
                            _lastPublishLogTime.put(_dest, now);
                        }
                        // Publish even with 0 healthy (tested) inbound tunnels.
                        // Untested tunnels have valid leases — publishing them
                        // immediately makes the destination reachable.  Waiting
                        // for a full test cycle just delays the first usable
                        // LeaseSet by 30+ seconds.
                        getContext().statManager().addRateData("netDb.republishLeaseSetCount", 1);
                        failCount.set(0);
                        _facade.sendStore(_dest, ls, null, new OnRepublishFailure(ls), getPublishTimeout(), null);
                        _lastPublished = now;
                        // Schedule next republish so it fires at least EXPIRY_WINDOW before expiry.
                        // Math.min — when the LS is close to expiry we schedule SOONER, not later.
                        // The old Math.max did the opposite, causing leases to expire between
                        // republish cycles and taking services offline until the next cycle.
                        long nextRepublish = Math.max(30L * 1000,
                                                      Math.min(getRepublishInterval(),
                                                               timeUntilExpiry - EXPIRY_WINDOW));
                        scheduleRepublish(nextRepublish);
                    }
                } else {
                    // No LeaseSet found - request immediate rebuild
                    if (_log.shouldWarn()) {
                        _log.warn("Client [" + _dest.toBase32().substring(0,8) + "] is LOCAL, but no valid LeaseSet found -> Requesting immediate rebuild");
                    }
                    clearRetryInProgress();
                    getContext().clientManager().requestLeaseSet(_dest, null);
                    scheduleRepublish(getRepublishInterval());
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

    private long getPublishTimeout() {
        return getContext().getProperty(PROP_TIMEOUT, REPUBLISH_LEASESET_TIMEOUT_DEFAULT);
    }

    private int getRetryDelay() {
        return getContext().getProperty(PROP_RETRY_DELAY, RETRY_DELAY_DEFAULT);
    }

    private int getMaxRetryDelay() {
        return getContext().getProperty(PROP_MAX_RETRY_DELAY, RETRY_MAX_DELAY_DEFAULT);
    }

    private long getRepublishInterval() {
        return getContext().getProperty("i2p.netdb.republishInterval", 5L * 60 * 1000);
    }

    private void scheduleRepublish(long delayMs) {
        // Unregister this job before scheduling the next, so hasActiveRepublishJob()
        // on the facade side doesn't block successor scheduling.
        _facade.removePublishingJob(_dest, this);
        if (_facade.hasActiveRepublishJob(_dest)) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping republish for [" + _dest.toBase32().substring(0, 8) +
                           "] -> Job already active (scheduled externally)");
            }
            return;
        }
        RepublishLeaseSetJob nextJob = new RepublishLeaseSetJob(getContext(), _facade, _dest);
        // Try to register the job - if it fails, another job is already active
        if (!nextJob.registerSelf()) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping republish for [" + _dest.toBase32().substring(0, 8) +
                           "] -> Registration failed (job already active)");
            }
            return;
        }
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

        long retryDelay = Math.min((long)getRetryDelay() * (1 << Math.min(count - 1, 4)), getMaxRetryDelay());
        // Boost to high priority on periodic attempts, but never faster than base delay
        boolean isHighPriority = count % 4 == 0;
        RepublishLeaseSetJob retryJob = new RepublishLeaseSetJob(getContext(), _facade, _dest);
        // Try to register the job - if it fails, another job is already active
        if (!retryJob.registerSelf()) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping retry for [" + _dest.toBase32().substring(0, 8) +
                           "] -> Registration failed (job already active)");
            }
            clearRetryInProgress();
            return;
        }
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
        if (now - _lastCleanupTime <= CACHE_CLEANUP_THRESHOLD)
            return;
        _lastCleanupTime = now;
        cleanupMap(_lastPublishLogTime, now);
        cleanupMap(_lastVerifyLogTime, now);
        cleanupMap(_lastNotRequeueLogTime, now);
        cleanupGlobalFailCount(now);
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

    /** Reset global fail counters that have been idle beyond the threshold. */
    private static void cleanupGlobalFailCount(long now) {
        // Global fail count has no timestamp per entry; just clear entries
        // for destinations no longer being tracked in the publish log.
        // If _lastPublishLogTime is empty for a given hash and the global
        // fail counter exists, assume publication succeeded and reset.
        Iterator<Map.Entry<Hash, AtomicInteger>> iter = _globalFailCount.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Hash, AtomicInteger> entry = iter.next();
            if (!_retryInProgress.containsKey(entry.getKey()) &&
                !_lastPublishLogTime.containsKey(entry.getKey())) {
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
            LeaseSet ls = _facade.lookupLeaseSetLocally(_ls.getHash());
            String tunnelName = ls != null ? getTunnelName(_ls.getDestination()) : "";
            String name = !tunnelName.isEmpty() ? " for '" + tunnelName + "'" : "";

            if (ls == null || KademliaNetworkDatabaseFacade.isNewer(ls, _ls)) {
                clearRetryInProgress();
                long now = getContext().clock().now();
                Long lastNotRequeueLog = _lastNotRequeueLogTime.get(_ls.getHash());
                if (_log.shouldInfo() && (lastNotRequeueLog == null || (now - lastNotRequeueLog > 10L * 1000))) {
                    _log.info("Not requeueing LeaseSet" + name + " [" +
                              _ls.getDestination().calculateHash().toBase32().substring(0,8) +
                              "] -> Newer LeaseSet exists locally");
                    _lastNotRequeueLogTime.put(_ls.getHash(), now);
                }
                cleanupStaleEntries();
                return;
            }

            // Use the persistent global fail counter, NOT the per-instance failCount
            // (which is reset to 0 every runJob()).  Verification only kicks in after
            // multiple real failures, not on the first store timeout.
            int globalCount = _globalFailCount
                .computeIfAbsent(_ls.getHash(), k -> new AtomicInteger(0))
                .incrementAndGet();

            if (globalCount > 2 && globalCount % 3 == 0 && _lookupInProgress.compareAndSet(false, true)) {
                long now = getContext().clock().now();
                Long lastVerifyLog = _lastVerifyLogTime.get(_ls.getHash());
                if (globalCount / 3 > MAX_FLOODFILL_VERIFICATIONS) {
                    if (_log.shouldWarn()) {
                        _log.warn("Floodfill verification maxed out for" + name + " [" +
                                  _ls.getDestination().calculateHash().toBase32().substring(0,8) +
                                  "] -> falling back to retry directly");
                    }
                    _lookupInProgress.set(false);
                    requeueRepublish();
                    return;
                }
                if (_log.shouldInfo() && (lastVerifyLog == null || (now - lastVerifyLog > 10L * 1000))) {
                    _log.info("Verifying LeaseSet publication" + name + " [" +
                              _ls.getDestination().calculateHash().toBase32().substring(0,8) + "] via floodfill...");
                    _lastVerifyLogTime.put(_ls.getHash(), now);
                }
                cleanupStaleEntries();
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
                    _lookupInProgress.set(false);
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
                    _lookupInProgress.set(false);
                    clearRetryInProgress();
                    if (_log.shouldInfo()) {
                        _log.info("Valid LeaseSet" + name + " not found via floodfill -> Retrying...");
                    }
                    requeueRepublish();
                }
            };

            _facade.lookupLeaseSetRemotely(_ls.getHash(), onFound, onFailed, 10L * 1000, null);
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
