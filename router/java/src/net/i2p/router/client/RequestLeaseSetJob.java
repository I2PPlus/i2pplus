package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.util.Properties;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.RequestLeaseSetMessage;
import net.i2p.data.i2cp.RequestVariableLeaseSetMessage;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.data.i2cp.SessionId;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Async job to walk the client through generating a lease set.  First sends it to the client
 * and then queues up a CheckLeaseRequestStatus job for processing after the expiration.
 * When that CheckLeaseRequestStatus is run, if the client still hasn't provided the signed
 * leaseSet, fire off the onFailed job from the intermediary LeaseRequestState and drop the client.
 *
 */
class RequestLeaseSetJob extends JobImpl {
    private final Log _log;
    private final ClientConnectionRunner _runner;
    private final LeaseRequestState _requestState;

    private static final long DEFAULT_MAX_FUDGE = 5*1000;
    private static final String PROP_MAX_FUDGE = "router.requestLeaseSetMaxFudge";
    private static final long TEN_MINUTES_MS = 10 * 60 * 1000;
    // Maximum future time for lease expiration (must match KademliaNetworkDatabaseFacade.MAX_LEASE_FUDGE)
    private static final long MAX_LEASE_FUTURE = 10 * 60 * 1000;
    private static final long CLOCK_FUDGE_FACTOR = 30 * 1000;

    public RequestLeaseSetJob(RouterContext ctx, ClientConnectionRunner runner, LeaseRequestState state) {
        super(ctx);
        _log = ctx.logManager().getLog(RequestLeaseSetJob.class);
        _runner = runner;
        _requestState = state;
        // all createRateStat in ClientManager
    }

    public String getName() {return "Request LeaseSet from Client";}

    public void runJob() {
        if (_runner.isDead()) {return;}

        boolean isLS2 = false;
        SessionConfig cfg = _runner.getPrimaryConfig();
        if (cfg != null) {
            Properties props = cfg.getOptions();
            if (props != null) {
                String lsType = props.getProperty("i2cp.leaseSetType");
                if (lsType != null && !lsType.equals(Integer.toString(DatabaseEntry.KEY_TYPE_LEASESET))) {isLS2 = true;}
            }
        }

        LeaseSet requested = _requestState.getRequested();
        if (requested == null) {
            if (_log.shouldWarn()) {
                _log.warn("Requested LeaseSet is null, cannot send request to client");
            }
            _runner.failLeaseRequest(_requestState);
            return;
        }
        long endTime = requested.getEarliestLeaseDate();
        long maxFudge = getContext().getProperty(PROP_MAX_FUDGE, DEFAULT_MAX_FUDGE);
        /**
         * Add a small number of ms (0 to MAX_FUDGE) that increases as we approach the expire time.
         * Since the earliest date functions as a version number,
         * this will force the floodfill to flood each new version;
         * otherwise it won't if the earliest time hasn't changed.
         */
        if (isLS2) {
            /**
             * Fix for 0.9.38 floodfills, adding some ms doesn't work since
             * the dates are truncated, and 0.9.38 did not use LeaseSet2.getPublished()
             */
            long earliest = maxFudge + _requestState.getCurrentEarliestLeaseDate();
            if (endTime < earliest) {endTime = earliest;}
        } else {
            long diff = endTime - getContext().clock().now();
            long fudge = maxFudge - (diff / (TEN_MINUTES_MS / Math.max(1, maxFudge)));
            endTime += fudge;
        }

        // Ensure lease expiration doesn't exceed maximum future time
        long now = getContext().clock().now();
        long maxAllowedTime = now + MAX_LEASE_FUTURE + CLOCK_FUDGE_FACTOR;
        if (endTime > maxAllowedTime) {
            if (_log.shouldInfo()) {
                _log.info("LeaseSet expiration would exceed limit -> Capping to ~10 minutes...");
            }
            endTime = maxAllowedTime;
        }

        Destination dest = requested.getDestination();
        if (dest == null) {
            if (_log.shouldWarn()) {
                _log.warn("Requested LeaseSet has null destination");
            }
            _runner.failLeaseRequest(_requestState);
            return;
        }
        SessionId id = _runner.getSessionId(dest.calculateHash());
        if (id == null) {
            _runner.failLeaseRequest(_requestState);
            return;
        }
        I2CPMessage msg;
        if (_runner instanceof QueuedClientConnectionRunner ||
             RequestVariableLeaseSetMessage.isSupported(_runner.getClientVersion())) {
            // new style - leases will have individual expirations
            RequestVariableLeaseSetMessage rmsg = new RequestVariableLeaseSetMessage();
            rmsg.setSessionId(id);
            for (int i = 0; i < requested.getLeaseCount(); i++) {
                Lease lease = requested.getLease(i);
                if (lease.getEndTime() < endTime) {
                    // don't modify old object, we don't know where it came from
                    Lease modifiedLease = new Lease();
                    modifiedLease.setGateway(lease.getGateway());
                    modifiedLease.setTunnelId(lease.getTunnelId());
                    modifiedLease.setEndDate(endTime);
                    lease = modifiedLease;
                }
                rmsg.addEndpoint(lease);
            }
            msg = rmsg;
        } else {
            // old style - all leases will have same expiration
            RequestLeaseSetMessage rmsg = new RequestLeaseSetMessage();
            Date end = new Date(endTime);
            rmsg.setEndDate(end);
            rmsg.setSessionId(id);
            for (int i = 0; i < requested.getLeaseCount(); i++) {
                Lease lease = requested.getLease(i);
                rmsg.addEndpoint(lease.getGateway(), lease.getTunnelId());
            }
            msg = rmsg;
        }

        try {
            _runner.doSend(msg);
            getContext().jobQueue().addJob(new CheckLeaseRequestStatus());
        } catch (I2CPMessageException ime) {
            getContext().statManager().addRateData("client.requestLeaseSetDropped", 1);
            _log.error("Error sending I2CP message requesting the LeaseSet", ime);
            _requestState.setIsSuccessful(false);
            if (_requestState.getOnFailed() != null) {
                getContext().jobQueue().addJob(_requestState.getOnFailed());
            }
            _runner.failLeaseRequest(_requestState);
        }
    }

    /**
     * Schedule this job to be run after the request's expiration, so that if
     * it wasn't yet successful, we fire off the failure job and disconnect the
     * client (but if it was, noop)
     *
     */
    private class CheckLeaseRequestStatus extends JobImpl {
        public CheckLeaseRequestStatus() {
            super(RequestLeaseSetJob.this.getContext());
            getTiming().setStartAfter(_requestState.getExpiration());
        }

        public void runJob() {
            if (_runner.isDead()) {
                if (_log.shouldDebug()) {
                    _log.debug("Runner is already dead -> Not trying to expire the LeaseSet lookup...");
                }
                return;
            }
            if (_requestState.getIsSuccessful()) {
                // we didn't fail
                getContext().statManager().addRateData("client.requestLeaseSetSuccess", 1);
                return;
            } else {
                getContext().statManager().addRateData("client.requestLeaseSetTimeout", 1);
                if (_log.shouldWarn()) {
                    long waited = System.currentTimeMillis() - getTiming().getStartAfter();
                    _log.warn("Timed out requesting LeaseSet after expiration (" + waited + "ms) -> " + _requestState);
                }
                if (_requestState.getOnFailed() != null) {
                    getContext().jobQueue().addJob(_requestState.getOnFailed());
                }
                _runner.failLeaseRequest(_requestState);
            }
        }

        public String getName() {return "Check LeaseSet Request Status";}
    }

}
