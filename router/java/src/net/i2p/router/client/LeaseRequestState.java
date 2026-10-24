package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import net.i2p.data.LeaseSet;
import net.i2p.router.Job;

/**
 * Bundle up the data points necessary when asynchronously requesting a lease
 * from a client.
 */
class LeaseRequestState {
    /**
     * Maximum prompt re-requests after netdb publish() rejected the client's
     * LeaseSet as expired. Bounds the retry loop when the client's clock or
     * signer stays skewed; after the bound the timeout check resolves the
     * request as before.
     *
     * @since 0.9.71+
     */
    static final int MAX_TRANSIENT_PUBLISH_RETRIES = 3;

    /** The lease set being requested. */
    private final LeaseSet _requestedLeaseSet;
    /** Job run when the request succeeds. */
    private final Job _onGranted;
    /** Job run when the request fails. */
    private final Job _onFailed;
    /** Time past which the request is abandoned. */
    private final long _expiration;
    /** Earliest lease end seen so far. */
    private final long _currentEarliestLeastDate;
    /** True once the request has succeeded. */
    private volatile boolean _successful;
    /** Publish retries used so far. */
    private int _transientPublishRetries;
    /** True once the outcome has been acted on. */
    private boolean _resolutionClaimed;

    /**
     * Create a new LeaseRequestState.
     *
     * @param onGranted job to run on success
     * @param onFailed job to run on failure
     * @param currentEarliestLeastDate absolute time, the earliest expiration in
     *                                 the current LS (NOT the requested one), or 0 if none
     *
     * @param expiration absolute time, when the request expires (not when the LS expires)
     * @param requested LeaseSet with requested leases - this object must be updated to contain the
     *                  signed version (as well as any changed/added/removed Leases)
     *                  The LeaseSet contains Leases and destination only, it is unsigned.
     */
    public LeaseRequestState(Job onGranted, Job onFailed, long currentEarliestLeastDate,
                             long expiration, LeaseSet requested) {
        _onGranted = onGranted;
        _onFailed = onFailed;
        _currentEarliestLeastDate = currentEarliestLeastDate;
        _expiration = expiration;
        _requestedLeaseSet = requested;
    }

    /**
     * The lease set this request is for.
     * @return the unsigned lease set, updated in place once it is signed
     */
    public LeaseSet getRequested() {return _requestedLeaseSet;}

    /**
     * The job to run once the lease set is created.
     * @return what to do once the lease set is created
     */
    public Job getOnGranted() { return _onGranted; }

    /**
     * The job to run if the lease set create fails or times out.
     * @return what to do if the lease set create fails / times out
     */
    public Job getOnFailed() { return _onFailed; }

    /**
     * When the request is abandoned if the client has not answered.
     * @return absolute adjusted time in ms, not the lease set's own expiration
     */
    public long getExpiration() { return _expiration; }

    /**
     * The earliest lease expiration time in the current LS (NOT the requested one),
     * or 0 if none.
     *
     * @return the earliest lease date
     * @since 0.9.39
     */
    public long getCurrentEarliestLeaseDate() {return _currentEarliestLeastDate;}

    /**
     * Whether the request was successful in the time allotted.
     * @return true if successful
     */
    public boolean getIsSuccessful() {return _successful;}

    /**
     * Record whether the request was successful.
     * @param is true if successful
     */
    public void setIsSuccessful(boolean is) {_successful = is;}

    /**
     * Claim one attempt to re-request the LeaseSet after netdb publish()
     * rejected it as expired (client clock skew or slow signing). The first
     * {@link #MAX_TRANSIENT_PUBLISH_RETRIES} callers receive true so the
     * request is promptly re-run with freshly floored lease times. Later
     * callers receive false so a persistently skewed client cannot loop.
     *
     * @return true if a retry attempt is available
     * @since 0.9.71+
     */
    synchronized boolean claimTransientPublishRetry() {
        if (_transientPublishRetries >= MAX_TRANSIENT_PUBLISH_RETRIES)
            return false;
        _transientPublishRetries++;
        return true;
    }

    /**
     * Claim the single accounting resolution for this request. Exactly one
     * resolution path (early job failure, timeout-check success, or
     * timeout-check failure) proceeds past this claim, so onFailed fires at
     * most once and success/timeout stats are never double-counted when
     * several checks or a retry and a check race each other.
     *
     * @return true if the caller owns the resolution
     * @since 0.9.71+
     */
    synchronized boolean claimCheckResolution() {
        if (_resolutionClaimed)
            return false;
        _resolutionClaimed = true;
        return true;
    }

    @Override
    public String toString() {
        return "LeaseSet request " + _requestedLeaseSet
               + (_successful ? " succeeded" : " failed");
    }

}
