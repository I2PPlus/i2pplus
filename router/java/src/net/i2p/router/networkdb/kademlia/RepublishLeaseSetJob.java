package net.i2p.router.networkdb.kademlia;

import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Run periodically for each locally created leaseSet to cause it to be republished
 * if the client is still connected.
 *
 */
class RepublishLeaseSetJob extends JobImpl {
    private final Log _log;
    public final static long REPUBLISH_LEASESET_TIMEOUT = 90 * 1000;
    private final static int RETRY_DELAY = 5 * 1000;
    private final Hash _dest;
    private final KademliaNetworkDatabaseFacade _facade;
    /** this is actually last attempted publish */
    private long _lastPublished;
    private final AtomicInteger failCount = new AtomicInteger(0);

    public RepublishLeaseSetJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade, Hash destHash) {
        super(ctx);
        _log = ctx.logManager().getLog(RepublishLeaseSetJob.class);
        _facade = facade;
        _dest = destHash;
    }

    public String getName() {
        return "Republish Local LeaseSet";
    }

    public void runJob() {
        if (!getContext().clientManager().shouldPublishLeaseSet(_dest)) {
            return;
        }

        try {
            if (getContext().clientManager().isLocal(_dest)) {
                LeaseSet ls = _facade.lookupLeaseSetLocally(_dest);
                if (ls != null) {
                    if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                        if (_log.shouldWarn()) {
                            _log.warn("Not publishing expired LOCAL LeaseSet [" + _dest.toBase32().substring(0,8) + "]",
                                    new Exception("Publish expired LOCAL lease?"));
                        }
                    } else {
                        if (_log.shouldInfo()) {
                            _log.info("Attempting to publish new LeaseSet for key [" + _dest.toBase32().substring(0,8) + "]...");
                        }
                        getContext().statManager().addRateData("netDb.republishLeaseSetCount", 1);
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
            }
            _facade.stopPublishing(_dest);
        } catch (RuntimeException re) {
            if (_log.shouldError()) {
                _log.error("Uncaught error republishing the LeaseSet", re);
            }
            _facade.stopPublishing(_dest);
            throw re;
        }
    }

    void requeueRepublish() {
        failCount.incrementAndGet();
        String count = failCount.get() > 1 ? " (Attempt: " + failCount.get() + ")" : "";
        if (_log.shouldWarn()) {
            _log.warn("Failed to publish LeaseSet for [" + _dest.toBase32().substring(0,8) + "] -> Retrying..." + count);
        }
        getContext().jobQueue().removeJob(this);
        requeue(RETRY_DELAY);
    }

    /**
     * @return last attempted publish time, or 0 if never
     */
    public long lastPublished() {
        return _lastPublished;
    }

    /** requeue */
    private class OnRepublishFailure extends JobImpl {
        private final LeaseSet _ls;

        public OnRepublishFailure(LeaseSet ls) {
            super(RepublishLeaseSetJob.this.getContext());
            _ls = ls;
        }

        public String getName() {
            return "Timeout LeaseSet Publication";
        }

        public void runJob() {
            LeaseSet ls = _facade.lookupLeaseSetLocally(_ls.getHash());
            if (ls != null && ls.getEarliestLeaseDate() == _ls.getEarliestLeaseDate()) {
                requeueRepublish();
            } else {
                if (_log.shouldInfo()) {
                    _log.info("Not requeueing failed publication of LeaseSet [" +
                            _ls.getDestination().toBase32().substring(0,8) + "] -> Newer LeaseSet exists");
                }
            }
        }
    }
}