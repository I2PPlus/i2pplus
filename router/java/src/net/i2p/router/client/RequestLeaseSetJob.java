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
                if (lsType != null && !lsType.equals("1")) {isLS2 = true;}
            }
        }

        LeaseSet requested = _requestState.getRequested();
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
            /**
             * Ensure endTime is in the future relative to current time to prevent
             * "LeaseSet expired" errors during signing. This can happen when tunnel
             * building takes longer than expected and lease end dates are already
             * in the past by the time they reach the client.
             */
            long now = getContext().clock().now();
            long minFutureTime = now + 30*1000; // 30 second minimum buffer
            if (endTime < minFutureTime) {endTime = minFutureTime;}
        } else {
            long diff = endTime - getContext().clock().now();
            long fudge = maxFudge - (diff / (10*60*1000 / maxFudge));
            endTime += fudge;
        }

        SessionId id = _runner.getSessionId(requested.getDestination().calculateHash());
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
                    Lease nl = new Lease();
                    nl.setGateway(lease.getGateway());
                    nl.setTunnelId(lease.getTunnelId());
                    nl.setEndDate(endTime);
                    lease = nl;
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
            // Use addJobToTop to ensure CheckLeaseRequestStatus runs promptly even under load.
            // This prevents cleanup jobs from being delayed when the job queue is backed up,
            // which would leave clients in a zombie state with expired LeaseSets.
            getContext().jobQueue().addJobToTop(new CheckLeaseRequestStatus());
        } catch (I2CPMessageException ime) {
            getContext().statManager().addRateData("client.requestLeaseSetDropped", 1);
            _log.error("Error sending I2CP message requesting the LeaseSet", ime);
            _requestState.setIsSuccessful(false);
            if (_requestState.getOnFailed() != null) {
                RequestLeaseSetJob.this.getContext().jobQueue().addJob(_requestState.getOnFailed());
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
        private final long _start;

        public CheckLeaseRequestStatus() {
            super(RequestLeaseSetJob.this.getContext());
            _start = System.currentTimeMillis();
            // Add stagger delay to spread out CheckLeaseRequestStatus jobs and prevent job queue spikes
            // This helps prevent lag when multiple lease requests timeout simultaneously
            int staggerDelay = RequestLeaseSetJob.this.getContext().random().nextInt(2000); // 0-2 seconds
            getTiming().setStartAfter(_requestState.getExpiration() + staggerDelay);
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
                CheckLeaseRequestStatus.this.getContext().statManager().addRateData("client.requestLeaseSetSuccess", 1);
                return;
            } else {
                CheckLeaseRequestStatus.this.getContext().statManager().addRateData("client.requestLeaseSetTimeout", 1);
                if (_log.shouldWarn()) {
                    long waited = System.currentTimeMillis() - _start;
                    _log.warn("Timed out requesting LeaseSet in the time allotted (" + waited + "ms) -> " + _requestState);
                }
                if (_requestState.getOnFailed() != null) {
                    RequestLeaseSetJob.this.getContext().jobQueue().addJob(_requestState.getOnFailed());
                }
                _runner.failLeaseRequest(_requestState);
            }
        }

        public String getName() {return "Check LeaseSet Request Status";}
    }

}
