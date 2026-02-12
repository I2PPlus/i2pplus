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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.i2p.util.SystemVersion;

/**
 * Async job to walk the client through generating a lease set.  First sends it to the client
 * and then queues up a CheckLeaseRequest job for processing after the expiration.
 * When that CheckLeaseRequest is run, if the client still hasn't provided the signed
 * leaseSet, fire off the onFailed job from the intermediary LeaseRequestState and drop the client.
 *
 */
class RequestLeaseSetJob extends JobImpl {
    private final Log _log;
    private final ClientConnectionRunner _runner;
    private final LeaseRequestState _requestState;

    private static final long DEFAULT_MAX_FUDGE = 5*1000;
    private static final String PROP_MAX_FUDGE = "router.requestLeaseSetMaxFudge";

    private static final long[] RETRY_DELAYS_NORMAL = {
        5000L, 10000L, 15000L, 20000L, 30000L, 45000L, 60000L, 90000L, 120000L, 180000L
    };

    private static final long[] RETRY_DELAYS_UNDER_ATTACK = {
        10000L, 20000L, 30000L, 45000L, 60000L, 90000L, 120000L, 180000L, 240000L, 300000L
    };

    /** Track pending requests by destination hash to deduplicate */
    private static final ConcurrentHashMap<String, Long> _pendingRequests = new ConcurrentHashMap<>();
    private static final long DEDUPE_WINDOW_MS = 30*1000; // 30 second dedupe window

    public RequestLeaseSetJob(RouterContext ctx, ClientConnectionRunner runner, LeaseRequestState state) {
        super(ctx);
        _log = ctx.logManager().getLog(RequestLeaseSetJob.class);
        _runner = runner;
        _requestState = state;
    }

    public String getName() {return "Request LeaseSet from Client";}

    public void runJob() {
        if (_runner.isDead()) {return;}

        long now = getContext().clock().now();
        LeaseSet requested = _requestState.getRequested();
        String destHash = requested.getDestination().calculateHash().toBase64();

        // Deduplicate: skip if we already have a pending request for this destination
        Long existingTime = _pendingRequests.get(destHash);
        if (existingTime != null && now - existingTime < DEDUPE_WINDOW_MS) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping duplicate LeaseSet request for " + destHash + " (recent request pending)");
            }
            return;
        }
        _pendingRequests.put(destHash, now);

        boolean isLS2 = false;
        SessionConfig cfg = _runner.getPrimaryConfig();
        if (cfg != null) {
            Properties props = cfg.getOptions();
            if (props != null) {
                String lsType = props.getProperty("i2cp.leaseSetType");
                if (lsType != null && !lsType.equals("1")) {isLS2 = true;}
            }
        }

        long endTime = requested.getEarliestLeaseDate();
        long maxFudge = getContext().getProperty(PROP_MAX_FUDGE, DEFAULT_MAX_FUDGE);
        if (isLS2) {
            long earliest = maxFudge + _requestState.getCurrentEarliestLeaseDate();
            if (endTime < earliest) {endTime = earliest;}
            now = getContext().clock().now();
            long minFutureTime = now + 30*1000;
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
            RequestVariableLeaseSetMessage rmsg = new RequestVariableLeaseSetMessage();
            rmsg.setSessionId(id);
            for (int i = 0; i < requested.getLeaseCount(); i++) {
                Lease lease = requested.getLease(i);
                if (lease.getEndTime() < endTime) {
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
            getContext().jobQueue().addJobToTop(new CheckLeaseRequestStatus(destHash));
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

    private class CheckLeaseRequestStatus extends JobImpl {
        private final long _start;
        private final String _destHash;
        private final int _retryCount;

        public CheckLeaseRequestStatus(String destHash) {
            this(destHash, 0);
        }

        public CheckLeaseRequestStatus(String destHash, int retryCount) {
            super(RequestLeaseSetJob.this.getContext());
            _start = System.currentTimeMillis();
            _destHash = destHash;
            _retryCount = retryCount;
            int staggerDelay = RequestLeaseSetJob.this.getContext().random().nextInt(2000);
            getTiming().setStartAfter(_requestState.getExpiration() + staggerDelay);
        }

        public void runJob() {
            _pendingRequests.remove(_destHash);

            if (_runner.isDead()) {
                if (_log.shouldDebug()) {
                    _log.debug("Runner is dead, not expiring LeaseSet lookup");
                }
                return;
            }

            if (_requestState.getIsSuccessful()) {
                getContext().statManager().addRateData("client.requestLeaseSetSuccess", 1);
                return;
            }

            getContext().statManager().addRateData("client.requestLeaseSetTimeout", 1);

            int buildSuccess = SystemVersion.getTunnelBuildSuccess();
            boolean isUnderAttack = buildSuccess < 40;
            int maxRetries = isUnderAttack ? Integer.MAX_VALUE : 10;
            int maxWindowMs = isUnderAttack ? 4 * 60 * 1000 : 2 * 60 * 1000;

            long now = getContext().clock().now();
            long earliestLease = _requestState.getRequested().getEarliestLeaseDate();
            long windowMs = earliestLease > 0 ? earliestLease - now : Long.MAX_VALUE;

            if (_retryCount < maxRetries && windowMs < maxWindowMs) {
                if (_log.shouldWarn()) {
                    _log.warn("LeaseSet request timed out (attempt " + (_retryCount + 1) + ") for " + _destHash +
                              "\n* Tunnel build success: " + buildSuccess + "% -> " +
                              (isUnderAttack ? "Under attack, will retry indefinitely" : "Scheduling retry..."));
                }

                long delay = getRetryDelay(_retryCount, isUnderAttack);
                RequestLeaseSetJob retryJob = new RequestLeaseSetJob(getContext(), _runner, _requestState);
                retryJob.getTiming().setStartAfter(now + delay);
                getContext().jobQueue().addJob(retryJob);
                return;
            }

            if (_log.shouldInfo()) {
                _log.info("LeaseSet request failed after " + (_retryCount + 1) + " attempts for " + _destHash +
                          " -> Failing permanently...");
            }

            if (_requestState.getOnFailed() != null) {
                RequestLeaseSetJob.this.getContext().jobQueue().addJob(_requestState.getOnFailed());
            }
            _runner.failLeaseRequest(_requestState);
        }

        private long getRetryDelay(int retryCount, boolean isUnderAttack) {
            long[] delays = isUnderAttack ? RETRY_DELAYS_UNDER_ATTACK : RETRY_DELAYS_NORMAL;
            if (retryCount < delays.length) {
                return delays[retryCount];
            }
            return delays[delays.length - 1];
        }

        public String getName() {return "Check LeaseSet Request Status";}
    }
}
