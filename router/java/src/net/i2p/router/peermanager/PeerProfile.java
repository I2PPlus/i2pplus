package net.i2p.router.peermanager;

import java.io.File;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

import net.i2p.data.router.RouterInfo;

/**
 * Copied from http://www.i2p2.i2p/how_peerselection.html
 *
 * See also main() below for additional commentary by zzz.
 *
 * Currently, there is no 'ejection' strategy to get rid of the profiles for peers that
 * are no longer active (or when the network consists of thousands of peers, to get rid
 * of peers that are performing poorly). However, the size of each profile is fairly small,
 * and is unrelated to how much data is collected about the peer, so that a router can
 * keep a few thousand active peer profiles before the overhead becomes a serious concern.
 * Once it becomes necessary, we can simply compact the poorly performing profiles
 * (keeping only the most basic data) and maintain hundreds of thousands of profiles
 * in memory. Beyond that size, we can simply eject the peers (e.g. keeping the best 100,000).
 *
 * TODO most of the methods should be synchronized.
 *
 */

public class PeerProfile {
    private final Log _log;
    private final RouterContext _context;
    // whoozaat?
    private final Hash _peer;
    // general peer stats
    private long _firstHeardAbout;
    private long _lastHeardAbout;
    private long _lastSentToSuccessfully;
    private long _lastFailedSend;
    private long _lastHeardFrom;
    // unused
    private float _tunnelTestResponseTimeAvg;
    private float _peerTestResponseTimeAvg;
    // periodic rates
    //private RateStat _sendSuccessSize = null;
    //private RateStat _receiveSize = null;
    private RateStat _dbResponseTime;
    private RateStat _tunnelCreateResponseTime;
    // unused
    private RateStat _tunnelTestResponseTime;
    private RateStat _peerTestResponseTime;
    private RateStat _dbIntroduction;
    // calculation bonuses
    // ints to save some space
    private int _speedBonus;
    private int _capacityBonus;
    private int _integrationBonus;
    // calculation values
    // floats to save some space
    private float _speedValue;
    private float _capacityValue;
    private float _integrationValue;
    // new calculation values, to be updated
    // floats to save some space
    private float _speedValueNew;
    private float _capacityValueNew;
    // are we in coalescing state?
    private boolean _coalescing;
    // good vs bad behavior
    private TunnelHistory _tunnelHistory;
    private DBHistory _dbHistory;
    // does this peer profile contain expanded data, or just the basics?
    private boolean _expanded;
    private boolean _expandedDB;
    //private int _consecutiveBanlists;
    private final int _distance;

    /** keep track of the fastest 3 throughputs */
//    private static final int THROUGHPUT_COUNT = 3;
    private static final int THROUGHPUT_COUNT = 12;
    /**
     * fastest 1 minute throughput, in bytes per minute, ordered with fastest
     * first.  this is not synchronized, as we don't *need* perfection, and we only
     * reorder/insert values on coalesce
     */
    private final float _peakThroughput[] = new float[THROUGHPUT_COUNT];
    private volatile long _peakThroughputCurrentTotal;
    private final float _peakTunnelThroughput[] = new float[THROUGHPUT_COUNT];
    /** total number of bytes pushed through a single tunnel in a 1 minute period */
    private final float _peakTunnel1mThroughput[] = new float[THROUGHPUT_COUNT];
    /** periodically cut the measured throughput values */
    private static final int DEGRADES_PER_DAY = 4;
    // one in this many times, ~= 61
    private static final int DEGRADE_PROBABILITY = PeerManager.REORGANIZES_PER_DAY / DEGRADES_PER_DAY;
    private static final double TOTAL_DEGRADE_PER_DAY = 0.5d;
    // the goal is to cut an unchanged profile in half in 24 hours.
    // x**4 = .5; x = 4th root of .5,  x = .5**(1/4), x ~= 0.84
    private static final float DEGRADE_FACTOR = (float) Math.pow(TOTAL_DEGRADE_PER_DAY, 1.0d / DEGRADES_PER_DAY);
    //static { System.out.println("Degrade factor is " + DEGRADE_FACTOR); }
    static final boolean ENABLE_TUNNEL_TEST_RESPONSE_TIME = false;

    private long _lastCoalesceDate = System.currentTimeMillis();

    private static final long[] RATES = { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000 };

    /**
     *  Countries with more than about a 2% share of the netdb.
     *  Only routers in these countries will use a same-country metric.
     *  Yes this is an arbitrary cutoff.
     */
    private static final Set<String> _bigCountries = new HashSet<String>();

    static {
        String[] big = new String[] { "fr", "de", "ru", "au", "us", "ca", "gb", "jp", "nl" };
        _bigCountries.addAll(Arrays.asList(big));
    }

    /**
     *  Caller should call setLastHeardAbout() and setFirstHeardAbout()
     *
     *  @param peer non-null
     */
    public PeerProfile(RouterContext context, Hash peer) {
        this(context, peer, true);
    }

    /**
     *  Caller should call setLastHeardAbout() and setFirstHeardAbout()
     *
     *  @param peer non-null
     *  @param expand must be true (see below)
     */
    private PeerProfile(RouterContext context, Hash peer, boolean expand) {
        if (peer == null)
            throw new NullPointerException();
        _context = context;
        _log = context.logManager().getLog(PeerProfile.class);
        _peer = peer;
        _firstHeardAbout = _context.clock().now();
        // this is always true, and there are several places in the router that will NPE
        // if it is false, so all need to be fixed before we can have non-expanded profiles
        if (expand)
            expandProfile();
        Hash us = _context.routerHash();
        if (us != null)
            _distance = ((_peer.getData()[0] & 0xff) ^ (us.getData()[0] & 0xff)) - 127;
        else
            _distance = 0;
    }

    /** what peer is being profiled, non-null */
    public Hash getPeer() { return _peer; }

    /**
     * are we keeping an expanded profile on the peer, or just the bare minimum.
     * If we aren't keeping the expanded profile, all of the rates as well as the
     * TunnelHistory and DBHistory will not be available.
     *
     */
    public boolean getIsExpanded() { return _expanded; }
    public boolean getIsExpandedDB() { return _expandedDB; }

    //public int incrementBanlists() { return _consecutiveBanlists++; }
    //public void unbanlist() { _consecutiveBanlists = 0; }

    /**
     * Is this peer active at the moment (sending/receiving messages within the last
     * 1 minute)
     */
    public boolean getIsActive() {
        return getIsActive(5*60*1000, _context.clock().now());
        //return getIsActive(60*1000, _context.clock().now());
    }

    /**
     * Is this peer active at the moment (sending/receiving messages within the last
     * 5 minutes)
     *
     * @since 0.9.58
     */
    public boolean getIsActive(long now) {
        return getIsActive(5*60*1000, now);
        //return getIsActive(60*1000, now);
    }

    /** @since 0.8.11 */
    boolean isEstablished() {
        // null for tests
        CommSystemFacade cs = _context.commSystem();
        if (cs == null)
            return false;
        return cs.isEstablished(_peer);
    }

    /** @since 0.8.11 */
    public boolean wasUnreachable() {
        // null for tests
        CommSystemFacade cs = _context.commSystem();
        if (cs == null)
            return false;
        return cs.wasUnreachable(_peer);
    }

    /** @since 0.8.11 */
    boolean isSameCountry() {
        // null for tests
        CommSystemFacade cs = _context.commSystem();
        if (cs == null)
            return false;
        String us = cs.getOurCountry();
        return us != null &&
               (_bigCountries.contains(us) ||
                _context.getProperty(CapacityCalculator.PROP_COUNTRY_BONUS) != null) &&
               us.equals(cs.getCountry(_peer));
    }

    /**
     *  For now, just a one-byte comparison
     *  @return -127 to +128, lower is closer
     *  @since 0.8.11
     */
    int getXORDistance() {
        return _distance;
    }

    /**
     * Is this peer active at the moment (sending/receiving messages within the
     * given period?)
     * Also mark active if it is connected, as this will tend to encourage use
     * of already-connected peers.
     *
     * Note: this appears to be the only use for these two RateStats.
     *
     * Update: Rewritten so we can get rid of the two RateStats.
     *         This also helps by not having it depend on coalesce boundaries.
     *
     * @param period must be one of the periods in the RateStat constructors below
     *        (5*60*1000 or 60*60*1000)
     *
     * @since 0.9.58
     */
    public boolean getIsActive(long period, long now) {
        long before = now - period;
        return getLastHeardFrom() < before ||
               getLastSendSuccessful() < before ||
               isEstablished();
    }


    /**
     *  When did we first hear about this peer?
     *  @return greater than zero, set to now in constructor
     */
    public synchronized long getFirstHeardAbout() { return _firstHeardAbout; }

    /**
     *  Set when did we first heard about this peer, only if older.
     *  Package private, only set by profile management subsystem.
     */
    synchronized void setFirstHeardAbout(long when) {
        if (when < _firstHeardAbout)
            _firstHeardAbout = when;
    }

    /**
     *  when did we last hear about this peer?
     *  @return 0 if unset
     */
    public synchronized long getLastHeardAbout() { return _lastHeardAbout; }

    /**
     *  Set when did we last hear about this peer, only if unset or newer
     *  Also sets FirstHeardAbout if earlier
     */
    public synchronized void setLastHeardAbout(long when) {
        if (_lastHeardAbout <= 0 || when > _lastHeardAbout)
            _lastHeardAbout = when;
        // this is called by netdb PersistentDataStore, so fixup first heard
        if (when < _firstHeardAbout)
            _firstHeardAbout = when;
    }

    /** when did we last send to this peer successfully? */
    public long getLastSendSuccessful() { return _lastSentToSuccessfully; }
    public void setLastSendSuccessful(long when) { _lastSentToSuccessfully = when; }

    /** when did we last have a problem sending to this peer? */
    public long getLastSendFailed() { return _lastFailedSend; }
    public void setLastSendFailed(long when) { _lastFailedSend = when; }

    /** when did we last hear from the peer? */
    public long getLastHeardFrom() { return _lastHeardFrom; }
    public void setLastHeardFrom(long when) { _lastHeardFrom = when; }

    /** history of tunnel activity with the peer
        Warning - may return null if !getIsExpanded() */
    public TunnelHistory getTunnelHistory() { return _tunnelHistory; }
    public void setTunnelHistory(TunnelHistory history) { _tunnelHistory = history; }

    /** history of db activity with the peer
        Warning - may return null if !getIsExpandedDB() */
    public DBHistory getDBHistory() { return _dbHistory; }
    public void setDBHistory(DBHistory hist) { _dbHistory = hist; }

    /** how large successfully sent messages are, calculated over a 1 minute, 1 hour, and 1 day period */
    //public RateStat getSendSuccessSize() { return _sendSuccessSize; }
    /** how large received messages are, calculated over a 1 minute, 1 hour, and 1 day period */
    //public RateStat getReceiveSize() { return _receiveSize; }
    /** how long it takes to get a db response from the peer (in milliseconds), calculated over a 1 minute, 1 hour, and 1 day period
        Warning - may return null if !getIsExpandedDB() */
    public RateStat getDbResponseTime() { return _dbResponseTime; }
    /** how long it takes to get a tunnel create response from the peer (in milliseconds), calculated over a 1 minute, 1 hour, and 1 day period
        Warning - may return null if !getIsExpanded() */
    public RateStat getTunnelCreateResponseTime() { return _tunnelCreateResponseTime; }

    /**
     *  How long it takes to successfully test a tunnel this peer participates in (in milliseconds),
     *  calculated over a 10 minute, 1 hour, and 1 day period
     *  Warning - may return null if !getIsExpanded()
     *
     *  @deprecated unused
     *  @return null always
     */
    @Deprecated
    public RateStat getTunnelTestResponseTime() { return _tunnelTestResponseTime; }

    /** how long it takes for a peer to respond to a direct test (ms) */
    public RateStat getPeerTestResponseTime() { return _peerTestResponseTime; }

    /** how many new peers we get from dbSearchReplyMessages or dbStore messages, calculated over a 1 hour, 1 day, and 1 week period
        Warning - may return null if !getIsExpandedDB() */
    public RateStat getDbIntroduction() { return _dbIntroduction; }

    /**
     * extra factor added to the speed ranking - this can be updated in the profile
     * written to disk to affect how the algorithm ranks speed.  Negative values are
     * penalties
     */
    public int getSpeedBonus() { return _speedBonus; }
    public void setSpeedBonus(int bonus) { _speedBonus = bonus; }

    /**
     * extra factor added to the capacity ranking - this can be updated in the profile
     * written to disk to affect how the algorithm ranks capacity.  Negative values are
     * penalties
     */
    public int getCapacityBonus() { return _capacityBonus; }
    public void setCapacityBonus(int bonus) { _capacityBonus = bonus; }

    /**
     * extra factor added to the integration ranking - this can be updated in the profile
     * written to disk to affect how the algorithm ranks integration.  Negative values are
     * penalties
     */
    public int getIntegrationBonus() { return _integrationBonus; }
    public void setIntegrationBonus(int bonus) { _integrationBonus = bonus; }

    /**
     * How fast is the peer, taking into consideration both throughput and latency.
     * This may even be made to take into consideration current rates vs. estimated
     * (or measured) max rates, allowing this speed to reflect the speed /available/.
     *
     */
    public float getSpeedValue() { return _speedValue; }
    /**
     * How many tunnels do we think this peer can handle over the next hour?
     *
     */
    public float getCapacityValue() { return _capacityValue; }
    /**
     * How well integrated into the network is this peer (as measured by how much they've
     * told us that we didn't already know).  Higher numbers means better integrated
     *
     */
    public float getIntegrationValue() { return _integrationValue; }
    /**
     * is this peer actively failing (aka not worth touching)?
     * @deprecated - unused - always false
     */
    @Deprecated
    public boolean getIsFailing() { return false; }

    /**
     *  @deprecated unused
     *  @return 0 always
     */
    @Deprecated
    public float getTunnelTestTimeAverage() { return _tunnelTestResponseTimeAvg; }

    /**
     *  @deprecated unused
     */
    @Deprecated
    void setTunnelTestTimeAverage(float avg) { /* _tunnelTestResponseTimeAvg = avg; */ }

    /**
     *  @deprecated unused
     */
    @Deprecated
    void updateTunnelTestTimeAverage(long ms) {
/*
        if (_tunnelTestResponseTimeAvg <= 0)
            _tunnelTestResponseTimeAvg = 30*1000; // should we instead start at $ms?

        // weighted since we want to let the average grow quickly and shrink slowly
        if (ms < _tunnelTestResponseTimeAvg)
            _tunnelTestResponseTimeAvg = 0.95f * _tunnelTestResponseTimeAvg + .05f * ms;
        else
            _tunnelTestResponseTimeAvg = 0.75f * _tunnelTestResponseTimeAvg + .25f * ms;

        if (_log.shouldInfo())
            _log.info("Timed tunnel test for [" + _peer.toBase64().substring(0,6) +
                      "] updated to " + (_tunnelTestResponseTimeAvg / 1000) + "s");
*/
    }

    public float getPeerTestTimeAverage() { return _peerTestResponseTimeAvg; }
    void setPeerTestTimeAverage(float testAvg) { _peerTestResponseTimeAvg = testAvg; }

    void updatePeerTestTimeAverage(long ms) {
        if (_peerTestResponseTimeAvg <= 0)
            _peerTestResponseTimeAvg = 10*1000; // default timeout * 2
        else
             _peerTestResponseTimeAvg = 0.75f * _peerTestResponseTimeAvg + .25f * ms;
        if (_log.shouldInfo())
            _log.info("Timed peer test average for [" + _peer.toBase64().substring(0,6) +
                      "] updated to " + (_peerTestResponseTimeAvg) + "ms");
    }

    public float getPeakThroughputKBps() {
        float rv = 0;
        for (int i = 0; i < THROUGHPUT_COUNT; i++)
            rv += _peakThroughput[i];
        rv /= (60 * 1024 * THROUGHPUT_COUNT);
        return rv;
    }

    /**
     *  Only for restoration from persisted profile.
     */
    void setPeakThroughputKBps(float kBps) {
        // Set all so the average remains the same
        float speed = kBps * (60 * 1024);
        for (int i = 0; i < THROUGHPUT_COUNT; i++) {
            _peakThroughput[i] = speed;
        }
    }

    void dataPushed(int size) { _peakThroughputCurrentTotal += size; }

    /** the tunnel pushed that much data in its lifetime */
    void tunnelDataTransferred(long tunnelByteLifetime) {
        float lowPeak = _peakTunnelThroughput[THROUGHPUT_COUNT-1];
        if (tunnelByteLifetime > lowPeak) {
            synchronized (_peakTunnelThroughput) {
                for (int i = 0; i < THROUGHPUT_COUNT; i++) {
                    if (tunnelByteLifetime > _peakTunnelThroughput[i]) {
                        for (int j = THROUGHPUT_COUNT-1; j > i; j--)
                           _peakTunnelThroughput[j] = _peakTunnelThroughput[j-1];
                        _peakTunnelThroughput[i] = tunnelByteLifetime;
                        break;
                    }
                }
            }
        }
    }
    public float getPeakTunnelThroughputKBps() {
        float rv = 0;
        for (int i = 0; i < THROUGHPUT_COUNT; i++)
            rv += _peakTunnelThroughput[i];
        rv /= (10 * 60 * 1024 * THROUGHPUT_COUNT);
        return rv;
    }

    /**
     *  Only for restoration from persisted profile.
     */
    void setPeakTunnelThroughputKBps(float kBps) {
        // Set all so the average remains the same
        float speed = kBps * (60 * 10 * 1024);
        for (int i = 0; i < THROUGHPUT_COUNT; i++) {
            _peakTunnelThroughput[i] = speed;
        }
    }

    /** the tunnel pushed that much data in a 1 minute period */
    void dataPushed1m(int size) {
        float lowPeak = _peakTunnel1mThroughput[THROUGHPUT_COUNT-1];
        if (size > lowPeak) {
            synchronized (_peakTunnel1mThroughput) {
                for (int i = 0; i < THROUGHPUT_COUNT; i++) {
                    if (size > _peakTunnel1mThroughput[i]) {
                        for (int j = THROUGHPUT_COUNT-1; j > i; j--)
                           _peakTunnel1mThroughput[j] = _peakTunnel1mThroughput[j-1];
                        _peakTunnel1mThroughput[i] = size;
                        break;
                    }
                }
            }

            if (_log.shouldDebug() ) {
                StringBuilder buf = new StringBuilder(128);
                buf.append("1 minute throughput for [");
                buf.append(_peer.toBase64().substring(0,6));
                buf.append("] updated after ").append(size).append(" bytes sent \n* Measured: ");
                for (int i = 0; i < THROUGHPUT_COUNT; i++)
                    buf.append(_peakTunnel1mThroughput[i]).append(" ");
                _log.debug(buf.toString());
            }
        }
    }

    /**
     * This is the speed value
     *
     * @return the average of the three fastest one-minute data transfers, on a per-tunnel basis,
     *         through this peer. Ever. Except that the peak values are cut in half
     *         periodically by coalesceThroughput().
     */
    public float getPeakTunnel1mThroughputKBps() {
        float rv = 0;
        for (int i = 0; i < THROUGHPUT_COUNT; i++)
            rv += _peakTunnel1mThroughput[i];
        rv /= (60 * 1024 * THROUGHPUT_COUNT);
        return rv;
    }

    /**
     *  Only for restoration from persisted profile.
     */
    void setPeakTunnel1mThroughputKBps(float kBps) {
        // Set all so the average remains the same
        float speed = kBps * (60 * 1024);
        for (int i = 0; i < THROUGHPUT_COUNT; i++) {
            _peakTunnel1mThroughput[i] = speed;
        }
    }

    /**
     * when the given peer is performing so poorly that we don't want to bother keeping
     * extensive stats on them, call this to discard excess data points.  Specifically,
     * this drops the rates, the tunnelHistory, and the dbHistory.
     *
     * UNUSED for now, will cause NPEs elsewhere
     */
/*****
    public void shrinkProfile() {
        //_sendSuccessSize = null;
        //_receiveSize = null;
        _dbResponseTime = null;
        _tunnelCreateResponseTime = null;
        _tunnelTestResponseTime = null;
        _dbIntroduction = null;
        _tunnelHistory = null;
        _dbHistory = null;

        _expanded = false;
        _expandedDB = false;
    }
******/

    /**
     * When the given peer is performing well enough that we want to keep detailed
     * stats on them again, call this to set up the info we dropped during shrinkProfile.
     * This will not however overwrite any existing data, so it can be safely called
     * repeatedly
     *
     */
    public synchronized void expandProfile() {
        String group = (null == _peer ? "profileUnknown" : _peer.toBase64().substring(0,6));
        if (_tunnelCreateResponseTime == null)
            _tunnelCreateResponseTime = new RateStat("tunnelCreateResponseTime", "Time for tunnel create response from peer (ms)", group, RATES);

        if (ENABLE_TUNNEL_TEST_RESPONSE_TIME && _tunnelTestResponseTime == null)
            _tunnelTestResponseTime = new RateStat("tunnelTestResponseTime", "Time to test a tunnel this peer participates in (ms)", group, RATES);

        if (_tunnelHistory == null)
            _tunnelHistory = new TunnelHistory(_context, group);

        _expanded = true;
    }

    /**
     * For floodfills
     */
    public synchronized void expandDBProfile() {
        String group = (null == _peer ? "profileUnknown" : _peer.toBase64().substring(0,6));
        if (_dbResponseTime == null)
            _dbResponseTime = new RateStat("dbResponseTime", "Time for NetDb response from peer (ms)", group, RATES);
        if (_dbIntroduction == null)
            _dbIntroduction = new RateStat("dbIntroduction", "Total new peers received from DbSearchReplyMsgs or DbStore messages", group, RATES);

        if (_dbHistory == null)
            _dbHistory = new DBHistory(_context, group);

        _expandedDB = true;
    }

    private void coalesceThroughput(boolean decay) {
        long now = System.currentTimeMillis();
        long measuredPeriod = now - _lastCoalesceDate;
        if (measuredPeriod >= 60*1000) {
            // so we don't call random() twice
            boolean shouldDecay =  decay && _context.random().nextInt(DEGRADE_PROBABILITY) <= 0;
            long tot = _peakThroughputCurrentTotal;
            float lowPeak = _peakThroughput[THROUGHPUT_COUNT-1];
            if (tot > lowPeak) {
                for (int i = 0; i < THROUGHPUT_COUNT; i++) {
                    if (tot > _peakThroughput[i]) {
                        for (int j = THROUGHPUT_COUNT-1; j > i; j--)
                            _peakThroughput[j] = _peakThroughput[j-1];
                        _peakThroughput[i] = tot;
                        break;
                    }
                }
            } else {
                if (shouldDecay) {
                    for (int i = 0; i < THROUGHPUT_COUNT; i++)
                        _peakThroughput[i] *= DEGRADE_FACTOR;
                }
            }

            // we degrade the tunnel throughput here too, regardless of the current
            // activity
            if (shouldDecay) {
                for (int i = 0; i < THROUGHPUT_COUNT; i++) {
                    _peakTunnelThroughput[i] *= DEGRADE_FACTOR;
                    _peakTunnel1mThroughput[i] *= DEGRADE_FACTOR;
                }
            }
            _peakThroughputCurrentTotal = 0;
            _lastCoalesceDate = now;
        }
    }

    /**
     *  Update the stats and rates. This is only called by addProfile()
     */
    void coalesceStats() {
        if (!_expanded) return;

        coalesceOnly(false);
        updateValues();

        if (_log.shouldDebug())
            _log.debug("Coalesced stats: Speed [" + _speedValue + "] Capacity [" + _capacityValue + "] Integration [" + _integrationValue + "]");
    }

    /**
     *  Caller must next call updateValues()
     *  @since 0.9.4
     */
    void coalesceOnly(boolean shouldDecay) {
        _coalescing = true;

        //_receiveSize.coalesceStats();
        //_sendSuccessSize.coalesceStats();
        _tunnelCreateResponseTime.coalesceStats();
        if (_tunnelTestResponseTime != null)
            _tunnelTestResponseTime.coalesceStats();
/*
        if (_peerTestResponseTime != null)
          _peerTestResponseTime.coalesceStats();
*/
        _tunnelHistory.coalesceStats();
        if (_expandedDB) {
            _dbIntroduction.coalesceStats();
            _dbResponseTime.coalesceStats();
            _dbHistory.coalesceStats();
        }

        coalesceThroughput(shouldDecay);

        _speedValueNew = calculateSpeed();
        _capacityValueNew = calculateCapacity();
        // These two are not used by InverseCapacityComparator
        // to sort _strictCapacityOrder in ProfileOrganizer
        // (in fact aren't really used at all), so we can
        // update them directly
        _integrationValue = calculateIntegration();
    }

    /**
     *  Copy over the new values generated by coalesceOnly()
     *  @since 0.9.4
     */
    void updateValues() {
        if (!_coalescing) // can happen
            coalesceOnly(false);
        _coalescing = false;

        _speedValue = _speedValueNew;
        _capacityValue = _capacityValueNew;
    }

    private float calculateSpeed() { return (float) SpeedCalculator.calc(this); }
    private float calculateCapacity() { return (float) CapacityCalculator.calc(this); }
    private float calculateIntegration() { return (float) IntegrationCalculator.calc(this); }

    /**
     * @deprecated - unused - always false
     */
    @Deprecated
    void setIsFailing(boolean val) {}

    /**
     *  Helper for calculators
     *  @since 0.9.2
     */
    RouterContext getContext() {
        return _context;
    }

    @Override
    public int hashCode() { return _peer.hashCode(); }

    @Override
    public boolean equals(Object obj) {
        if (obj == null ||
            !(obj instanceof PeerProfile))
            return false;
        PeerProfile prof = (PeerProfile)obj;
        return _peer.equals(prof._peer);
    }

    @Override
    public String toString() { return "Profile: " + _peer; }

    /**
     * New measurement is 12KB per expanded profile. (2009-03 zzz)
     * And nowhere in the code is shrinkProfile() called so
     * the size of compact profiles doesn't matter right now.
     * This is far bigger than the NetDB entry, which is only about 1.5KB
     * now that most of the stats have been removed.
     *
     * The biggest user in the profile is the Rates. (144 bytes per according to jhat).
     * PeerProfile:     9 RateStats, 3-5 Rates each - 35 total
     * DBHistory:       2 RateStats, 3 each -          6 total
     * TunnelHistory:   4 RateStats, 5 each -         20 total
     *                ---                            ---------
     *                 15                             61 total
     *                *60 bytes                     *144 bytes
     *                ---                            ---------
     *                900 bytes                     8784 bytes
     *
     * The RateStat itself is 32 bytes and the Rate[] is 28 so that adds
     * about 1KB.
     *
     * So two obvious things to do are cut out some of the Rates,
     * and call shrinkProfile().
     *
     * Obsolete calculation follows:
     *
     * Calculate the memory consumption of profiles.  Measured to be ~3739 bytes
     * for an expanded profile, and ~212 bytes for a compacted one.
     *
     */
/****
    public static void main(String args[]) {
        RouterContext ctx = new RouterContext(new net.i2p.router.Router());
        testProfileSize(ctx, 100, 0); // 560KB
        testProfileSize(ctx, 1000, 0); // 3.9MB
        testProfileSize(ctx, 10000, 0); // 37MB
        testProfileSize(ctx, 0, 10000); // 2.2MB
        testProfileSize(ctx, 0, 100000); // 21MB
        testProfileSize(ctx, 0, 300000);  // 63MB
    }
****/

    /**
     * Read in all of the profiles specified and print out
     * their calculated values.  Usage: <pre>
     *  PeerProfile [filename]*
     * </pre>
     */
/****
    public static void main2(String args[]) {
        RouterContext ctx = new RouterContext(new net.i2p.router.Router());
        DecimalFormat fmt = new DecimalFormat("0,000.0");
        fmt.setPositivePrefix("+");
        ProfilePersistenceHelper helper = new ProfilePersistenceHelper(ctx);
        try { Thread.sleep(5*1000); } catch (InterruptedException e) {}
        StringBuilder buf = new StringBuilder(1024);
        for (int i = 0; i < args.length; i++) {
            PeerProfile profile = helper.readProfile(new File(args[i]));
            if (profile == null) {
                buf.append("Could not load profile ").append(args[i]).append('\n');
                continue;
            }
            //profile.coalesceStats();
            buf.append("Peer " + profile.getPeer().toBase64()
                       + ":\t Speed:\t" + fmt.format(profile.calculateSpeed())
                       + " Capacity:\t" + fmt.format(profile.calculateCapacity())
                       + " Integration:\t" + fmt.format(profile.calculateIntegration())
                       + " Active?\t" + profile.getIsActive()
                       + " Failing?\t" + profile.calculateIsFailing()
                       + '\n');
        }
        try { Thread.sleep(5*1000); } catch (InterruptedException e) {}
        System.out.println(buf.toString());
    }

    private static void testProfileSize(RouterContext ctx, int numExpanded, int numCompact) {
        Runtime.getRuntime().gc();
        PeerProfile profs[] = new PeerProfile[numExpanded];
        PeerProfile profsCompact[] = new PeerProfile[numCompact];
        long used = Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        long usedPer = used / (numExpanded+numCompact);
        System.out.println(numExpanded + "/" + numCompact + ": create array - Used: " + used + " bytes (or " + usedPer + " bytes per array entry)");

        int i = 0;
        try {
            for (; i < numExpanded; i++)
                profs[i] = new PeerProfile(ctx, new Hash(new byte[Hash.HASH_LENGTH]));
        } catch (OutOfMemoryError oom) {
            profs = null;
            profsCompact = null;
            Runtime.getRuntime().gc();
            System.out.println("Ran out of memory when creating profile " + i);
            return;
        }
        try {
            for (; i < numCompact; i++)
                profsCompact[i] = new PeerProfile(ctx, new Hash(new byte[Hash.HASH_LENGTH]), false);
        } catch (OutOfMemoryError oom) {
            profs = null;
            profsCompact = null;
            Runtime.getRuntime().gc();
            System.out.println("Ran out of memory when creating compacted profile " + i);
            return;
        }

        Runtime.getRuntime().gc();
        long usedObjects = Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        usedPer = usedObjects / (numExpanded+numCompact);
        System.out.println(numExpanded + "/" + numCompact + ": create objects - Used: " + usedObjects + " bytes (or " + usedPer + " bytes per profile)");
        profs = null;
        profsCompact = null;
        Runtime.getRuntime().gc();
    }
****/
}
