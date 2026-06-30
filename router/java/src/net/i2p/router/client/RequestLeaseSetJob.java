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
 * Async job to walk the client through generating a lease set.
 * Sends the request to the client and queues a CheckLeaseRequestStatus job
 * for timeout cleanup. On failure, calls failLeaseRequest() to clean up
 * the pending LeaseRequestState so new requests can proceed.
 *
 */
class RequestLeaseSetJob extends JobImpl {
    private final Log _log;
    private final ClientConnectionRunner _runner;
    private final LeaseRequestState _requestState;

    private static final long DEFAULT_MAX_FUDGE = 5L*1000;
    private static final String PROP_MAX_FUDGE = "router.requestLeaseSetMaxFudge";
    private static final long TEN_MINUTES_MS = 10L * 60 * 1000;
    // Maximum future time for lease expiration (must match KademliaNetworkDatabaseFacade.MAX_LEASE_FUDGE)
    private static final long MAX_LEASE_FUTURE = 10L * 60 * 1000;
    private static final long CLOCK_FUDGE_FACTOR = 30L * 1000;

    public RequestLeaseSetJob(RouterContext ctx, ClientConnectionRunner runner, LeaseRequestState state) {
        super(ctx);
        _log = ctx.logManager().getLog(RequestLeaseSetJob.class);
        _runner = runner;
        _requestState = state;
        // all createRateStat in ClientManager
    }

    public String getName() {return "Request LeaseSet from Client";}

    public void runJob() {
        if (_runner.isDead()) {
            if (_log.shouldWarn())
                _log.warn("Runner is dead, cannot request LeaseSet: " + _requestState);
            return;
        }

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

        // Ensure lease expiration is never in the past — expired LS from
        // RepublishLeaseSetJob would otherwise cause the client's signLeaseSet()
        // to throw DataFormatException("LeaseSet expired X seconds ago").
        long now = getContext().clock().now();
        long minAllowed = now + 60*1000;
        if (endTime < minAllowed) {endTime = minAllowed;}

        // Ensure lease expiration doesn't exceed maximum future time
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
            if (_log.shouldWarn())
                _log.warn("No SessionId for destination " + dest.calculateHash().toBase32().substring(0,8) +
                          " in RequestLeaseSetJob");
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
            if (_log.shouldInfo())
                _log.info("LeaseSet request sent to client, scheduling timeout check for " + _requestState);
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
     * it wasn't yet successful, we clean up the pending state so new requests
     * can proceed.
     */
    private class CheckLeaseRequestStatus extends JobImpl {
        private final long _start;

        public CheckLeaseRequestStatus() {
            super(RequestLeaseSetJob.this.getContext());
            _start = System.currentTimeMillis();
            getTiming().setStartAfter(_requestState.getExpiration());
        }

        public void runJob() {
            if (_runner.isDead()) {
                if (_log.shouldDebug())
                    _log.debug("Already dead, dont try to expire the leaseSet lookup");
                return;
            }
            if (_requestState.getIsSuccessful()) {
                RequestLeaseSetJob.this.getContext().statManager().addRateData("client.requestLeaseSetSuccess", 1);
                return;
            } else {
                RequestLeaseSetJob.this.getContext().statManager().addRateData("client.requestLeaseSetTimeout", 1);
                if (_log.shouldError()) {
                    long waited = System.currentTimeMillis() - _start;
                    _log.error("Failed to receive a leaseSet in the time allotted (" + waited + "): " + _requestState);
                }
                if (_requestState.getOnFailed() != null)
                    RequestLeaseSetJob.this.getContext().jobQueue().addJob(_requestState.getOnFailed());
                _runner.failLeaseRequest(_requestState);
            }
        }
        public String getName() { return "Check LeaseRequest Status"; }
    }
}
