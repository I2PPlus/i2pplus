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

    /**
     * Minimum number of peers required
     *
     * @return the minimum number of peers
     */
    public int getMinimumRequired() { return _minReq; }
    /**
     * Set the minimum number of peers required
     *
     * @param min the minimum number of peers
     */
    public void setMinimumRequired(int min) { _minReq = min; }
    /**
     * Maximum number of peers required
     *
     * @return the maximum number of peers
     */
    public int getMaximumRequired() { return _maxReq; }
    /**
     * Set the maximum number of peers required
     *
     * @param max the maximum number of peers
     */
    public void setMaximumRequired(int max) { _maxReq = max; }
    /**
     * Purpose for which the peers will be used
     *
     * @return the purpose
     */
    public int getPurpose() { return _purpose; }
    /**
     * Set the purpose for which the peers will be used
     *
     * @param purpose the purpose
     */
    public void setPurpose(int purpose) { _purpose = purpose; }
}
