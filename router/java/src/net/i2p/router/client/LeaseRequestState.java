package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.LeaseSet;
import net.i2p.router.Job;

/**
 * Bundle up the data points necessary when asynchronously requesting a lease
 * from a client
 *
 */
class LeaseRequestState {
    private LeaseSet _grantedLeaseSet;
    private final LeaseSet _requestedLeaseSet;
    private final Job _onGranted;
    private final Job _onFailed;
    private final long _expiration;
    private final long _currentEarliestLeastDate;
    private boolean _successful;

    /**
     *  @param currentEarliestLeastDate absolute time, the earliest expiration in
     *         the current LS (NOT the requested one), or 0 if none
     *  @param expiration absolute time, when the request expires (not when the LS expires)
     *  @param requested LeaseSet with requested leases - this object must be updated to contain the
     *         signed version (as well as any changed/added/removed Leases)
     *         The LeaseSet contains Leases and destination only, it is unsigned.
     */
    public LeaseRequestState(Job onGranted, Job onFailed, long currentEarliestLeastDate,
                             long expiration, LeaseSet requested) {
        _onGranted = onGranted;
        _onFailed = onFailed;
        _currentEarliestLeastDate = currentEarliestLeastDate;
        _expiration = expiration;
        _requestedLeaseSet = requested;
    }

    /** created lease set from client - FIXME always null */
    public LeaseSet getGranted() {return _grantedLeaseSet;}

    /** FIXME unused - why? */
    public void setGranted(LeaseSet ls) {_grantedLeaseSet = ls;}

    /** lease set that is being requested */
    public LeaseSet getRequested() {return _requestedLeaseSet;}

    /** what to do once the lease set is created */
    public Job getOnGranted() { return _onGranted; }

    /** what to do if the lease set create fails / times out */
    public Job getOnFailed() { return _onFailed; }

    /** when the request for the lease set expires */
    public long getExpiration() { return _expiration; }

    /**
     * The earliest lease expiration time in the current LS (NOT the requested one),
     * or 0 if none.
     *
     * @since 0.9.39
     */
    public long getCurrentEarliestLeaseDate() {return _currentEarliestLeastDate;}

    /** whether the request was successful in the time allotted */
    public boolean getIsSuccessful() {return _successful;}
    public void setIsSuccessful(boolean is) {_successful = is;}

    @Override
    public String toString() {
        return "LeaseSet request asking for " + _requestedLeaseSet
               + " having received " + _grantedLeaseSet
               + " succeeding? " + _successful
               + " expiring on " + _expiration;
    }

}
