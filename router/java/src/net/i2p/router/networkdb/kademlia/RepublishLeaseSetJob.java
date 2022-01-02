package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

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
//    public final static long REPUBLISH_LEASESET_TIMEOUT = 60*1000;
    public final static long REPUBLISH_LEASESET_TIMEOUT = 90*1000;
//    private final static int RETRY_DELAY = 20*1000;
    private final static int RETRY_DELAY = 10*1000;
    private final Hash _dest;
    private final KademliaNetworkDatabaseFacade _facade;
    /** this is actually last attempted publish */
    private long _lastPublished;

    public RepublishLeaseSetJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade, Hash destHash) {
        super(ctx);
        _log = ctx.logManager().getLog(RepublishLeaseSetJob.class);
        _facade = facade;
        _dest = destHash;
    }

    public String getName() { return "Republish Local LeaseSet"; }

    public void runJob() {
        if (!getContext().clientManager().shouldPublishLeaseSet(_dest))
            return;

        try {
            if (getContext().clientManager().isLocal(_dest)) {
                LeaseSet ls = _facade.lookupLeaseSetLocally(_dest);
                if (ls != null) {
                    if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Not publishing a stale LOCAL LeaseSet [" + _dest.toBase32().substring(0,6) + "]", new Exception("Publish expired LOCAL lease?"));
                    } else {
                        if (_log.shouldInfo())
                            _log.info(getJobId() + ": Publishing LS for " + _dest.toBase32());
                        getContext().statManager().addRateData("netDb.republishLeaseSetCount", 1);
                        _facade.sendStore(_dest, ls, null, new OnRepublishFailure(ls), REPUBLISH_LEASESET_TIMEOUT, null);
                        _lastPublished = getContext().clock().now();
                    }
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Client [" + _dest.toBase32().substring(0,6) + "] is local, but no valid LeaseSet found; being rebuilt?");
                }
                return;
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Client [" + _dest.toBase32().substring(0,6) + "] is no longer local; not republishing LeaseSet");
            }
            _facade.stopPublishing(_dest);
        } catch (RuntimeException re) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Uncaught error republishing the LeaseSet", re);
            _facade.stopPublishing(_dest);
            throw re;
        }
    }

    void requeueRepublish() {
        if (_log.shouldWarn())
            _log.warn("Failed to publish LeaseSet for [" + _dest.toBase32().substring(0,6) + "]");
        getContext().jobQueue().removeJob(this);
        requeue(RETRY_DELAY + getContext().random().nextInt(RETRY_DELAY));
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

        public String getName() { return "Timeout LeaseSet Publication"; }

        public void runJob() {
            // Don't requeue if there's a newer LS, KNDF will have already done that
            LeaseSet ls = _facade.lookupLeaseSetLocally(_ls.getHash());
            if (ls != null && ls.getEarliestLeaseDate() == _ls.getEarliestLeaseDate()) {
                requeueRepublish();
            } else {
                if (_log.shouldWarn())
                    _log.warn("[Job " + getJobId() + "] Publication of LeasetSet for  [" + _ls.getDestination().toBase32().substring(0,6) + "] failed, but not requeueing, there is a newer LeseSet");
            }
        }
    }
}
