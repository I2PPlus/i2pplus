package net.i2p.router.networkdb.kademlia;

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
 * Run periodically for each locally created leaseSet to cause it to be republished
 * if the client is still connected.
 *
 * This job is used to ensure that local LeaseSets are periodically republished to the network.
 * Failed republish attempts are requeued with high priority to ensure faster retries.
 */
public class RepublishLeaseSetJob extends JobImpl {
    private final Log _log;
    public final static long REPUBLISH_LEASESET_TIMEOUT = 20 * 1000;
    private final static int RETRY_DELAY = 3000;
    private final Hash _dest;
    private final KademliaNetworkDatabaseFacade _facade;
    private long _lastPublished;
    private final AtomicInteger failCount = new AtomicInteger(0);
    private boolean highPriority;
    private volatile boolean _lookupInProgress;

    /**
     * Create a new RepublishLeaseSetJob for the given destination.
     *
     * @param ctx the router context
     * @param facade the network database facade used to publish the lease set
     * @param destHash the hash of the destination whose LeaseSet should be republished
     */
    public RepublishLeaseSetJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade, Hash destHash) {
        super(ctx);
        _log = ctx.logManager().getLog(RepublishLeaseSetJob.class);
        _facade = facade;
        _dest = destHash;
    }

    /**
     * Get the name of this job.
     */
    public String getName() {return "Republish Local LeaseSet" + (highPriority ? " [High priority]" : "");}

    /**
     * Main job logic: checks if the LeaseSet should be republished and publishes it if necessary.
     */
    public void runJob() {
        long uptime = getContext().router().getUptime();
        if (!getContext().clientManager().shouldPublishLeaseSet(_dest) || uptime < 45 * 1000) {return;}
        try {
            if (getContext().clientManager().isLocal(_dest)) {
                LeaseSet ls = _facade.lookupLeaseSetLocally(_dest);
                if (ls != null) {
                    String tunnelName = getTunnelName(ls.getDestination());
                    String name = !tunnelName.isEmpty() ? " for \'" + tunnelName + "\'" : " for key";
                    if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                        if (_log.shouldWarn()) {
                            _log.warn("Not publishing expired LOCAL LeaseSet" + name + " [" + _dest.toBase32().substring(0,8) + "]");
                        }
                    } else {
                        if (_log.shouldInfo()) {
                            _log.info("Attempting to publish LeaseSet" + name + " [" + _dest.toBase32().substring(0,8) + "]...");
                        }
                        getContext().statManager().addRateData("netDb.republishLeaseSetCount", 1);
                        failCount.set(0);
                        _facade.sendStore(_dest, ls, null, new OnRepublishFailure(ls), REPUBLISH_LEASESET_TIMEOUT, null);
                        _lastPublished = getContext().clock().now();
                    }
                } else {
                    if (_log.shouldWarn()) {
                        _log.warn("Client [" + _dest.toBase32().substring(0,8) + "] is LOCAL, but no valid LeaseSet found -> Being rebuilt?");
                    }
                }
                return;
            } else {
                if (_log.shouldInfo()) {
                    _log.info("Client [" + _dest.toBase32().substring(0,8) + "] is no longer LOCAL -> Not republishing LeaseSet");
                }
                // Clean up the orphaned LeaseSet to prevent future expiration errors
                LeaseSet ls = _facade.lookupLeaseSetLocally(_dest);
                if (ls != null && !ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                    _facade.fail(_dest);
                }
            }
            _facade.stopPublishing(_dest);
        } catch (RuntimeException re) {
            if (_log.shouldError()) {_log.error("Uncaught error republishing the LeaseSet", re);}
            _facade.stopPublishing(_dest);
            throw re;
        }
    }

    /**
     * Requeues this job to retry the republish attempt.
     * Adds the job normally on the first failure to avoid aggressive retries,
     * and uses high priority to enqueue the job at the front of the queue
     * every 4th failure to enable faster retries.
     */
    void requeueRepublish() {
        int count = failCount.incrementAndGet();
        LeaseSet ls = getContext().clientManager().isLocal(_dest) ? _facade.lookupLeaseSetLocally(_dest) : null;
        String b32 = _dest.toBase32().substring(0,8);
        String tunnelName = ls != null ? getTunnelName(ls.getDestination()) : "";
        String name = !tunnelName.isEmpty() ? "\'" + tunnelName + "\'" + " [" + b32 + "]" : "[" + b32 + "]";
        String countStr = count > 1 ? " (Attempt: " + count + ")" : "";
        if (_log.shouldInfo()) {
            _log.info("Failed to publish LeaseSet for " + name + " -> Retrying..." + countStr + (highPriority ? " [High priority]" : ""));
        }
        getContext().statManager().addRateData("netDb.republishLeaseSetFail", 1);
        getContext().jobQueue().removeJob(this);
        // Run high priority every 4th fail
        if (count % 4 == 0) {highPriority = true;}
        requeue(RETRY_DELAY, highPriority);
    }

    /**
     * Requeues the job after a delay.
     *
     * @param delayMs delay in milliseconds before the job is run
     * @param highPriority if true, adds the job to the front of the queue
     */
    private void requeue(long delayMs, boolean highPriority) {
        requeue(delayMs);
        if (highPriority) {getContext().jobQueue().addJobToTop(this);}
        else {getContext().jobQueue().addJob(this);}
    }

    /**
     * @return last attempted publish time, or 0 if never
     */
    public long lastPublished() {return _lastPublished;}

    /**
     * Callback job executed if the lease set publication fails.
     */
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
                if (_log.shouldInfo()) {
                    _log.info("Not requeueing LeaseSet" + name + " [" +
                              _ls.getDestination().calculateHash().toBase32().substring(0,8) +
                              "] -> Newer LeaseSet exists locally");
                }
                return;
            }

            if (count % 3 == 0 && !_lookupInProgress) {
                _lookupInProgress = true;
                if (_log.shouldInfo()) {
                    _log.info("Verifying LeaseSet publication" + name + " [" +
                              _ls.getDestination().calculateHash().toBase32().substring(0,8) +
                              "] via floodfill...");
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
                    if (_log.shouldInfo()) {
                        _log.info("Valid LeaseSet" + name + " not found via floodfill -> Retrying...");
                    }
                    requeueRepublish();
                }
            };

            _facade.lookupLeaseSetRemotely(_ls.getHash(), onFound, onFailed, 10*1000, null);
        }
    }

    /**
     * Attempts to get a human-readable tunnel name for the given destination.
     *
     * @param d destination to look up
     * @return nickname if available, otherwise empty string
     */
    public String getTunnelName(Destination d) {
        TunnelPoolSettings in = getContext().tunnelManager().getInboundSettings(d.calculateHash());
        String name = (in != null ? in.getDestinationNickname() : null);
        if (name == null) {
            TunnelPoolSettings out = getContext().tunnelManager().getOutboundSettings(d.calculateHash());
            name = (out != null ? out.getDestinationNickname() : null);
        }
        return name != null ? name : "";
    }

}
