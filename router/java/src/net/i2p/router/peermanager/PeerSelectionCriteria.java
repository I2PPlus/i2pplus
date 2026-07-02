package net.i2p.router.peermanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Defines the criteria for selecting a set of peers for use when searching the
 * PeerManager.
 *
 * Only used by PeerTestJob (PURPOSE_TEST).
 *
 * @since moved from router to peermanager in 0.9.40
 */
class PeerSelectionCriteria {
    /** The peers will be used for a test message */
    public static final int PURPOSE_TEST = 1;

    private int _minReq;
    private int _maxReq;
    private int _purpose;

    /** Minimum number of peers required */
    public int getMinimumRequired() { return _minReq; }
    public void setMinimumRequired(int min) { _minReq = min; }
    /** Maximum number of peers required */
    public int getMaximumRequired() { return _maxReq; }
    public void setMaximumRequired(int max) { _maxReq = max; }
    /** Purpose for which the peers will be used */
    public int getPurpose() { return _purpose; }
    public void setPurpose(int purpose) { _purpose = purpose; }
}
