package net.i2p.router.peermanager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.data.Hash;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;

import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

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
    private float _tunnelTestResponseTimeAvg;
    private long _tunnelTestTimeAvgLastUpdate;
    private float _peerTestResponseTimeAvg;
    // periodic rates
    private RateStat _dbResponseTime;
    private RateStat _tunnelCreateResponseTime;
    private RateStat _dbIntroduction;
    // calculation bonuses
    // ints to save some space
    /**
     * @deprecated Replaced by {@link #isLowLatency()}. This field is no longer
     * used for routing decisions; it is retained only for backward compatibility
     * with stored profile data. New reads/write go through the lowLatency flag.
     */
    @Deprecated
    private int _speedBonus;
    /**
     * @deprecated Replaced by lowLatency flag for routing decisions.
     */
    @Deprecated
    private long _speedBonusLastUpdate;
    private int _capacityBonus;
    private long _capacityBonusLastUpdate;
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
    /** low latency flag, set when peer responds quickly to tunnel builds, persisted */
    private volatile boolean _lowLatency;
    private final int _distance;

    /** keep track of the fastest 8 throughputs unless slow, then 4 */
    private static final int THROUGHPUT_COUNT = SystemVersion.isSlow() ? 4 : 8;
    /**
     * fastest 1 minute throughput, in bytes per minute, ordered with fastest
     * first.  this is not synchronized, as we don't *need* perfection, and we only
     * reorder/insert values on coalesce
     */
    private final float[] _peakThroughput = new float[THROUGHPUT_COUNT];
    private final AtomicLong _peakThroughputCurrentTotal = new AtomicLong();
    private final float[] _peakTunnelThroughput = new float[THROUGHPUT_COUNT];
    /** total number of bytes pushed through a single tunnel in a 1 minute period */
    private final float[] _peakTunnel1mThroughput = new float[THROUGHPUT_COUNT];
    private long _lastTestStarted;
    private volatile long _lastThroughputUpdate;
    /** periodically cut the measured throughput values */
    private static final int DEGRADES_PER_DAY = 4;
    // one in this many times, ~= 61
    private static final int DEGRADE_PROBABILITY = PeerManager.REORGANIZES_PER_DAY / DEGRADES_PER_DAY;
    private static final double TOTAL_DEGRADE_PER_DAY = 0.5d;
    // the goal is to cut an unchanged profile in half in 24 hours.
    // x**4 = .5; x = 4th root of .5,  x = .5**(1/4), x ~= 0.84
    private static final float DEGRADE_FACTOR = (float) Math.pow(TOTAL_DEGRADE_PER_DAY, 1.0d / DEGRADES_PER_DAY);
    private long _lastCoalesceDate = System.currentTimeMillis();
    private static final long[] TUNNEL_CREATE_RESPONSE_RATES = {
        RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR, RateConstants.ONE_DAY
    };
    private static final long[] DB_RESPONSE_RATES = { RateConstants.ONE_HOUR };
    private static final long[] DB_INTRODUCTION_RATES = {
        RateConstants.ONE_HOUR, RateConstants.ONE_DAY
    };
    private static final long MIN_AGE_FOR_COALESCE = 4 * RateConstants.ONE_HOUR;

    /**
     *  Countries with more than about a 2% share of the netdb.
     *  Only routers in these countries will use a same-country metric.
     *  Yes this is an arbitrary cutoff.
     */
    private static final Set<String> _bigCountries = new HashSet<>();

    static {
        String[] big = new String[] { "fr", "de", "ru", "au", "us", "ca", "gb", "jp", "nl", "ir", "se" };
        _bigCountries.addAll(Arrays.asList(big));
    }

    /**
     *  Caller should call setLastHeardAbout() and setFirstHeardAbout()
     *
     *  @param context the router context
     *  @param peer non-null
     */
    public PeerProfile(RouterContext context, Hash peer) {this(context, peer, false);}

    /**
     *  Caller should call setLastHeardAbout() and setFirstHeardAbout()
     *
     *  @param peer non-null
     *  @param expand whether to eagerly expand (default false; auto-expanded on first use)
     */
    private PeerProfile(RouterContext context, Hash peer, boolean expand) {
        if (peer == null) {throw new NullPointerException();}
        _context = context;
        _log = _context.logManager().getLog(PeerProfile.class);
        _peer = peer;
        _firstHeardAbout = _context.clock().now();
        if (expand) {expandProfile();}
        Hash us = _context.routerHash();
        if (us != null) {_distance = ((_peer.getData()[0] & 0xff) ^ (us.getData()[0] & 0xff)) - 127;}
        else {_distance = 0;}
    }

    /**
     *  what peer is being profiled
     *
     *  @return the peer, non-null
     */
    public Hash getPeer() {return _peer;}

    /**
     * Whether the tunnel profile data has been allocated (RateStats, TunnelHistory).
     * Profiles are created unexpanded and expanded lazily on first use.
     *
     * @return true if expanded
     */
    public boolean getIsExpanded() {return _expanded;}

    /**
     * Whether the DB profile data has been allocated.
     *
     * @return true if expanded DB
     */
    public boolean getIsExpandedDB() {return _expandedDB;}

    /**
     * Low latency flag, set when this peer has been observed to respond quickly
     * to tunnel build requests. Persisted in profiles and used to seed fast/high-cap
     * tiers at startup from prior session data.
     *
     * @return true if low latency
     */
    public boolean isLowLatency() {return _lowLatency;}

    /**
     * Set the low latency flag.
     *
     * @param low true for low latency
     */
    public void setLowLatency(boolean low) {_lowLatency = low;}

    /**
     * Is this peer active at the moment (sending/receiving messages within the last
     * 10 minutes)
     *
     * @return true if active
     */
    public boolean getIsActive() {
        return getIsActive(10*60*1000L, _context.clock().now());
    }

    /**
     * Is this peer active at the moment (sending/receiving messages within the last 10 minutes)
     *
     * @param now current time
     * @return true if active
     * @since 0.9.58
     */
    public boolean getIsActive(long now) {
        return getIsActive(10*60*1000L, now);
    }

    /**
     * Is this peer established?
     *
     * @return true if established
     * @since 0.8.11
     */
    boolean isEstablished() {
        // null for tests
        CommSystemFacade cs = _context.commSystem();
        if (cs == null) {return false;}
        return cs.isEstablished(_peer);
    }

    /**
     * Was the peer previously unreachable?
     *
     * @return true if unreachable
     * @since 0.8.11
     */
    public boolean wasUnreachable() {
        // null for tests
        CommSystemFacade cs = _context.commSystem();
        if (cs == null) {return false;}
        return cs.wasUnreachable(_peer);
    }

    /**
     * Is the peer in the same country as us?
     *
     * @return true if same country
     * @since 0.8.11
     */
    boolean isSameCountry() {
        // null for tests
        CommSystemFacade cs = _context.commSystem();
        if (cs == null) {return false;}
        String us = cs.getOurCountry();
        return us != null && (_bigCountries.contains(us) ||
                     _context.getProperty(CapacityCalculator.PROP_COUNTRY_BONUS) != null) &&
                     us.equals(cs.getCountry(_peer));
    }

    /**
     *  For now, just a one-byte comparison
     *
     *  @return -127 to +128, lower is closer
     *  @since 0.8.11
     */
    int getXORDistance() {return _distance;}

    /**
     * Is this peer active at the moment (sending/receiving messages within the
     * given period?)
     * Also mark active if it is connected, as this will tend to encourage use
     * of already-connected peers.
     *
     * @param period must be one of the periods in the RateStat constructors below
     *        (5*60*1000 or 60*60*1000)
     * @param now current time
     * @return true if active
     * @since 0.9.58
     */
    public boolean getIsActive(long period, long now) {
        long before = now - period;
        return getLastHeardFrom() >= before || getLastSendSuccessful() >= before || isEstablished();
    }

    /**
     *  When did we first hear about this peer?
     *
     *  @return greater than zero, set to now in constructor
     */
    public synchronized long getFirstHeardAbout() {return _firstHeardAbout;}

    /**
     *  Set when did we first heard about this peer, only if older.
     *  Package private, only set by profile management subsystem.
     *
     * @param when the time to set
     */
    synchronized void setFirstHeardAbout(long when) {
        if (when < _firstHeardAbout) {_firstHeardAbout = when;}
    }

    /**
     *  when did we last hear about this peer?
     *
     *  @return 0 if unset
     */
    public synchronized long getLastHeardAbout() {return _lastHeardAbout;}

    /**
     *  Set when did we last hear about this peer, only if unset or newer.
     *  Also sets FirstHeardAbout if earlier.
     *
     * @param when the time to set
     */
    public synchronized void setLastHeardAbout(long when) {
        if (when > _lastHeardAbout) {_lastHeardAbout = when;}
        // this is called by netdb PersistentDataStore, so fixup first heard
        if (when < _firstHeardAbout) {_firstHeardAbout = when;}
    }

    /**
     * When did we last send to this peer successfully?
     *
     * @return the timestamp
     */
    public long getLastSendSuccessful() {return _lastSentToSuccessfully;}

    /**
     * Set when we last sent to this peer successfully.
     *
     * @param when the timestamp
     */
    public void setLastSendSuccessful(long when) {_lastSentToSuccessfully = when;}

    /**
     * When did we last have a problem sending to this peer?
     *
     * @return the timestamp
     */
    public long getLastSendFailed() {return _lastFailedSend;}

    /**
     * Set when we last had a problem sending to this peer.
     *
     * @param when the timestamp
     */
    public void setLastSendFailed(long when) {_lastFailedSend = when;}

    /**
     * When did we last hear from the peer?
     *
     * @return the timestamp
     */
    public long getLastHeardFrom() {return _lastHeardFrom;}

    /**
     * Set when we last heard from the peer.
     *
     * @param when the timestamp
     */
    public void setLastHeardFrom(long when) {_lastHeardFrom = when;}

    /**
     * History of tunnel activity with the peer.
     * Expands the profile lazily on first access.
     *
     * @return TunnelHistory, non-null after first access
     */
    public synchronized TunnelHistory getTunnelHistory() {
        if (_tunnelHistory == null) {
            String group = (null == _peer ? "profileUnknown" : _peer.toBase64().substring(0,6));
            _tunnelHistory = new TunnelHistory(_context, group);
        }
        return _tunnelHistory;
    }

    /**
     * Whether the peer has accepted or rejected at least one of our tunnel
     * build requests. Profiles without any tunnel history are not expanded,
     * not persisted, and dropped from memory quickly.
     *
     * @return true if the peer has participated in our tunnel building
     * @since 0.9.71+
     */
    public boolean hasTunnelHistory() {
        TunnelHistory th = _tunnelHistory;
        return th != null && (th.getLifetimeAgreedTo() > 0 || th.getLifetimeRejected() > 0);
    }

    /**
     * Set the tunnel history.
     *
     * @param history the TunnelHistory
     */
    public synchronized void setTunnelHistory(TunnelHistory history) {_tunnelHistory = history;}

    /**
     * Tunnel acceptance ratio from tunnel history.
     *
     * @return ratio (0.0 to 1.0), or 1.0 if no data available
     */
    public double getTunnelAcceptanceRatio() {
        TunnelHistory th = getTunnelHistory();
        if (th == null) {return 1.0;}
        return th.getAcceptanceRatio();
    }

    /**
     * When the peer last passed a tunnel test successfully.
     *
     * @return timestamp, or 0 if never
     */
    public long getLastTestedSuccessfully() {
        TunnelHistory th = getTunnelHistory();
        if (th == null) {return 0;}
        return th.getLastTestedSuccessfully();
    }

    /**
     * History of DB activity with the peer.
     * Expands the DB profile lazily on first access.
     *
     * @return DBHistory, non-null after first access
     */
    public synchronized DBHistory getDBHistory() {
        if (!_expandedDB) {expandDBProfile();}
        return _dbHistory;
    }

    /**
     * Set the DB history.
     *
     * @param hist the DBHistory
     */
    public synchronized void setDBHistory(DBHistory hist) {_dbHistory = hist;}

    /**
     * How long it takes to get a DB response from the peer (in milliseconds).
     * Expands the DB profile lazily on first access.
     *
     * @return RateStat, non-null after first access
     */
    public synchronized RateStat getDbResponseTime() {
        if (!_expandedDB) {expandDBProfile();}
        return _dbResponseTime;
    }

    /**
     * How long it takes to get a tunnel create response from the peer (in milliseconds).
     * Expands the profile lazily on first access. The profile is not expanded
     * (and this returns null) until the peer has accepted or rejected at least
     * one of our tunnel builds.
     *
     * @return RateStat, or null if the peer has no tunnel history
     */
    public synchronized RateStat getTunnelCreateResponseTime() {
        if (!_expanded) {expandProfile();}
        return _tunnelCreateResponseTime;
    }

    /**
     * How many new peers we get from dbSearchReplyMessages or dbStore messages.
     * Expands the DB profile lazily on first access.
     *
     * @return RateStat, non-null after first access
     */
    public synchronized RateStat getDbIntroduction() {
        if (!_expandedDB) {expandDBProfile();}
        return _dbIntroduction;
    }

    /**
     * Obsolete — prefer {@link #isLowLatency()}. Retained for backward compatibility
     * with stored profile data. Returns 0 unconditionally if not set, or the stored
     * value with a 4-hour expiry for existing profiles that still carry it.
     *
     * @return the speed bonus
     */
    public int getSpeedBonus() {
        if (_speedBonus == 0) return _speedBonus;
        if (_speedBonusLastUpdate <= 0) return _speedBonus; // backward compat: no timestamp = valid
        long hoursSinceUpdate = (_context.clock().now() - _speedBonusLastUpdate) / (60 * 60 * 1000L);
        return hoursSinceUpdate >= 4 ? 0 : _speedBonus;
    }
    /**
     * Obsolete — prefer {@link #setLowLatency(boolean)}. Retained for
     * backward compatibility with stored profile data.
     *
     * @param bonus the speed bonus
     */
    public void setSpeedBonus(int bonus) {_speedBonus = bonus; _speedBonusLastUpdate = _context.clock().now();}
    /**
     * @deprecated No longer used for routing decisions.
     */
    @Deprecated
    long getSpeedBonusLastUpdate() {return _speedBonusLastUpdate;}
    /**
     * @deprecated No longer used for routing decisions.
     */
    @Deprecated
    void setSpeedBonusLastUpdate(long ts) {_speedBonusLastUpdate = ts;}

    /**
     * extra factor added to the capacity ranking - this can be updated in the profile
     * written to disk to affect how the algorithm ranks capacity.  Negative values are
     * penalties. Expires after 4 hours if not refreshed.
     *
     * @return the capacity bonus
     */
    public int getCapacityBonus() {
        if (_capacityBonus == 0) return _capacityBonus;
        if (_capacityBonusLastUpdate <= 0) return _capacityBonus; // backward compat: no timestamp = valid
        long hoursSinceUpdate = (_context.clock().now() - _capacityBonusLastUpdate) / (60 * 60 * 1000L);
        return hoursSinceUpdate >= 4 ? 0 : _capacityBonus;
    }
    /**
     * @param bonus the capacity bonus
     */
    public void setCapacityBonus(int bonus) {_capacityBonus = bonus; _capacityBonusLastUpdate = _context.clock().now();}

    /**
     * @return the timestamp of the last capacity bonus update
     */
    long getCapacityBonusLastUpdate() {return _capacityBonusLastUpdate;}

    /**
     * @param ts the timestamp
     */
    void setCapacityBonusLastUpdate(long ts) {_capacityBonusLastUpdate = ts;}

    /**
     * @return the raw capacity bonus value
     */
    int getCapacityBonusRaw() {return _capacityBonus;}

    /**
     * extra factor added to the integration ranking - this can be updated in the profile
     * written to disk to affect how the algorithm ranks integration.  Negative values are
     * penalties.
     *
     * @return the integration bonus
     */
    public int getIntegrationBonus() {return _integrationBonus;}

    /**
     * @param bonus the integration bonus
     */
    public void setIntegrationBonus(int bonus) {_integrationBonus = bonus;}

    /**
     * How fast is the peer, taking into consideration both throughput and latency.
     * This may even be made to take into consideration current rates vs. estimated
     * (or measured) max rates, allowing this speed to reflect the speed /available/.
     *
     * @return the speed value
     */
    public float getSpeedValue() {return _speedValue;}
    /**
     * How many tunnels do we think this peer can handle over the next hour?
     *
     * @return the capacity value
     */
    public float getCapacityValue() {return _capacityValue;}
    /**
     * How well integrated into the network is this peer (as measured by how much they've
     * told us that we didn't already know).  Higher numbers means better integrated.
     *
     * @return the integration value
     */
    public float getIntegrationValue() {return _integrationValue;}
    /**
     *  @return EWMA average with time-based decay (50% per hour since last update)
     */
    public float getTunnelTestTimeAverage() {
        if (_tunnelTestResponseTimeAvg <= 0 || _tunnelTestTimeAvgLastUpdate <= 0) return 0;
        long hoursSinceUpdate = (_context.clock().now() - _tunnelTestTimeAvgLastUpdate) / (60 * 60 * 1000L);
        if (hoursSinceUpdate <= 0) return _tunnelTestResponseTimeAvg;
        // Decay by 50% per hour, cap at 4 hours (effectively zero)
        float decay = (float) Math.pow(0.5, Math.min(hoursSinceUpdate, 4));
        return _tunnelTestResponseTimeAvg * decay;
    }

    /**
     * @return timestamp when the EWMA was last updated
     */
    long getTunnelTestTimeAvgLastUpdate() {return _tunnelTestTimeAvgLastUpdate;}

    /**
     * Set the tunnel test time average.
     *
     * @param avg the average in ms
     */
    void setTunnelTestTimeAverage(float avg) {_tunnelTestResponseTimeAvg = avg;}

    /**
     * Set the timestamp of the last tunnel test time average update.
     *
     * @param ts the timestamp
     */
    void setTunnelTestTimeAvgLastUpdate(long ts) {_tunnelTestTimeAvgLastUpdate = ts;}

    /**
     * Update the tunnel test time average with a new measurement.
     *
     * @param ms the new measurement in ms
     */
    void updateTunnelTestTimeAverage(float ms) {

        if (_tunnelTestResponseTimeAvg <= 0) {_tunnelTestResponseTimeAvg = ms;} // should we instead start at $ms?

        // weighted since we want to let the average grow quickly and shrink slowly
        if (ms < _tunnelTestResponseTimeAvg) {_tunnelTestResponseTimeAvg = 0.95f * _tunnelTestResponseTimeAvg + .05f * ms;}
        else {_tunnelTestResponseTimeAvg = 0.75f * _tunnelTestResponseTimeAvg + .25f * ms;}

        _tunnelTestTimeAvgLastUpdate = _context.clock().now();

        if (_log.shouldInfo()) {
            _log.info("Timed tunnel test for [" + _peer.toBase64().substring(0,6) +
                      "] updated to " + (_tunnelTestResponseTimeAvg / 1000) + "s");
        }
    }

    /**
     * @return the peer test time average
     */
    public float getPeerTestTimeAverage() {return _peerTestResponseTimeAvg;}

    /**
     * @param testAvg the peer test time average
     */
    void setPeerTestTimeAverage(float testAvg) {_peerTestResponseTimeAvg = testAvg;}

    /**
     * Update peer test time average.
     *
     * @param ms the new measurement in ms
     */
    void updatePeerTestTimeAverage(float ms) {
        if (_peerTestResponseTimeAvg <= 0) {_peerTestResponseTimeAvg = ms;}
        else {_peerTestResponseTimeAvg = 0.75f * _peerTestResponseTimeAvg + .25f * ms;}
        if (_log.shouldInfo()) {
            _log.info("Timed peer test average for [" + _peer.toBase64().substring(0,6) +
                      "] updated to " + (_peerTestResponseTimeAvg) + "ms");
        }
    }

    /**
     * Recalculate the low-latency flag from the accumulated peer test time average.
     * Low latency is defined as an average response time under 3x the peer test timeout
     * (default 750ms, so 2250ms threshold), matching the same threshold used in BuildExecutor.
     * Called periodically to keep _lowLatency in sync with measured data.
     *
     * @since 0.9.70
     */
    void recalculateLowLatency() {
        if (_peerTestResponseTimeAvg <= 0)
            return;
        int peerTimeout = _context.getProperty("router.peerTestTimeout", 750);
        _lowLatency = _peerTestResponseTimeAvg < 3 * peerTimeout;
    }

    /**
     * @return the peak throughput in KBps
     */
    public float getPeakThroughputKBps() {
        float rv = 0;
        for (int i = 0; i < THROUGHPUT_COUNT; i++) {rv += _peakThroughput[i];}
        rv /= (60 * 1024L * THROUGHPUT_COUNT);
        return rv;
    }

    /**
     *  Only for restoration from persisted profile.
     *
     * @param kBps the peak throughput in KBps
     */
    void setPeakThroughputKBps(float kBps) {
        // Set all so the average remains the same
        float speed = kBps * (60 * 1024);
        for (int i = 0; i < THROUGHPUT_COUNT; i++) {_peakThroughput[i] = speed;}
    }

    /**
     * Record data pushed through this peer.
     *
     * @param size the number of bytes pushed
     */
    void dataPushed(int size) {_peakThroughputCurrentTotal.addAndGet(size);}

    /**
     * The tunnel pushed that much data in its lifetime.
     *
     * @param tunnelByteLifetime total bytes transferred
     */
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

    /**
     * @return the peak tunnel throughput in KBps
     */
    public float getPeakTunnelThroughputKBps() {
        float rv = 0;
        for (int i = 0; i < THROUGHPUT_COUNT; i++) {rv += _peakTunnelThroughput[i];}
        rv /= (10 * 60 * 1024L * THROUGHPUT_COUNT);
        return rv;
    }

    /**
     *  Only for restoration from persisted profile.
     *
     * @param kBps the peak tunnel throughput in KBps
     */
    void setPeakTunnelThroughputKBps(float kBps) {
        // Set all so the average remains the same
        float speed = kBps * (60 * 10 * 1024);
        for (int i = 0; i < THROUGHPUT_COUNT; i++) {_peakTunnelThroughput[i] = speed;}
    }

    /**
     * The tunnel pushed that much data in a 1 minute period.
     *
     * @param size the number of bytes in that minute
     */
    void dataPushed1m(int size) {
        _lastThroughputUpdate = _context.clock().now();
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
                for (int i = 0; i < THROUGHPUT_COUNT; i++) {
                    buf.append(_peakTunnel1mThroughput[i]).append(" ");
                }
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
        for (int i = 0; i < THROUGHPUT_COUNT; i++) {rv += _peakTunnel1mThroughput[i];}
        rv /= (60 * 1024L * THROUGHPUT_COUNT);
        return rv;
    }

    /**
     *  Only for restoration from persisted profile.
     *
     * @param kBps the peak 1-minute throughput in KBps
     */
    void setPeakTunnel1mThroughputKBps(float kBps) {
        // Set all so the average remains the same
        float speed = kBps * (60 * 1024);
        for (int i = 0; i < THROUGHPUT_COUNT; i++) {_peakTunnel1mThroughput[i] = speed;}
    }

    /**
     * @return the timestamp of the last throughput update
     */
    long getLastThroughputUpdate() {return _lastThroughputUpdate;}

    /**
     * @param ts the timestamp
     */
    void setLastThroughputUpdate(long ts) {_lastThroughputUpdate = ts;}

    /**
     * @return the timestamp of the last test start
     */
    public long getLastTestStarted() {return _lastTestStarted;}

    /**
     * @param ts the timestamp
     */
    void setLastTestStarted(long ts) {_lastTestStarted = ts;}

    /**
     * When the given peer is performing well enough that we want to keep detailed
     * stats on them again, call this to set up the info we dropped during shrinkProfile.
     * This will not however overwrite any existing data, so it can be safely called
     * repeatedly
     *
     */
    public synchronized void expandProfile() {
        // Don't expand profiles that have never accepted or rejected one of our
        // tunnel builds; they'll be dropped from memory instead.
        if (!hasTunnelHistory()) {return;}
        String group = (null == _peer ? "profileUnknown" : _peer.toBase64().substring(0,6));

        if (_tunnelCreateResponseTime == null) {
            _tunnelCreateResponseTime = new RateStat("tunnelCreateResponseTime", "Time for tunnel create response from peer (ms)", group, TUNNEL_CREATE_RESPONSE_RATES);
        }
        if (_tunnelHistory == null) {_tunnelHistory = new TunnelHistory(_context, group);}
        _expanded = true;
    }

    /**
     * For floodfills
     */
    public synchronized void expandDBProfile() {
        String group = (null == _peer ? "profileUnknown" : _peer.toBase64().substring(0,6));
        if (_dbResponseTime == null) {
            _dbResponseTime = new RateStat("dbResponseTime", "Time for NetDb response from peer (ms)", group, DB_RESPONSE_RATES);
        }
        if (_dbIntroduction == null) {
            _dbIntroduction = new RateStat("dbIntroduction", "Total new peers received from DbSearchReplyMsgs or DbStore messages", group, DB_INTRODUCTION_RATES);
        }
        if (_dbHistory == null) {
            _dbHistory = new DBHistory(_context, group);
        }
        _expandedDB = true;
    }

    /**
     *  Shrink the profile by dropping the RateStat objects.
     *  They will be re-created lazily by expandProfile()
     *  when the profile is used again.
     */
    public synchronized void shrinkProfile() {
        if (_tunnelCreateResponseTime != null) {
            _context.statManager().removeRateStat(_tunnelCreateResponseTime.getName());
            _tunnelCreateResponseTime = null;
        }
        _tunnelHistory = null;
        _expanded = false;
    }

    /**
     *  Shrink the DB-specific part of the profile.
     */
    public synchronized void shrinkDBProfile() {
        if (_dbResponseTime != null) {
            _context.statManager().removeRateStat(_dbResponseTime.getName());
            _dbResponseTime = null;
        }
        if (_dbIntroduction != null) {
            _context.statManager().removeRateStat(_dbIntroduction.getName());
            _dbIntroduction = null;
        }
        _dbHistory = null;
        _expandedDB = false;
    }

    private void coalesceThroughput(boolean decay) {
        long now = System.currentTimeMillis();
        long measuredPeriod = now - _lastCoalesceDate;
        if (measuredPeriod >= 60*1000L) {
            // so we don't call random() twice
            boolean shouldDecay =  decay && _context.random().nextInt(DEGRADE_PROBABILITY) <= 0;
            long tot = _peakThroughputCurrentTotal.getAndSet(0);
            float lowPeak = _peakThroughput[THROUGHPUT_COUNT-1];
            if (tot > lowPeak) {
                for (int i = 0; i < THROUGHPUT_COUNT; i++) {
                    if (tot > _peakThroughput[i]) {
                        for (int j = THROUGHPUT_COUNT-1; j > i; j--) {_peakThroughput[j] = _peakThroughput[j-1];}
                        _peakThroughput[i] = tot;
                        break;
                    }
                }
            } else {
                if (shouldDecay) {
                    for (int i = 0; i < THROUGHPUT_COUNT; i++) {_peakThroughput[i] *= DEGRADE_FACTOR;}
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
            _lastCoalesceDate = now;
        }
    }

    /**
     * Update the speed, capacity, and integration values from the new values.
     *
     * @since 0.9.4
     */
    public synchronized void updateValues() {
        if (!_coalescing) {coalesceOnly(false);} // can happen
        _coalescing = false;
        _speedValue = _speedValueNew;
        _capacityValue = _capacityValueNew;
    }

    /**
     *  Coalesce all stats and update values
     */
    public synchronized void coalesceStats() {
        coalesceOnly(true);
        updateValues();
    }

    /**
     * Caller must next call updateValues().
     *
     * @param shouldDecay whether to decay peak throughput values
     * @since 0.9.4
     */
    synchronized void coalesceOnly(boolean shouldDecay) {
        _coalescing = true;
        boolean mature = _context.clock().now() - _firstHeardAbout > MIN_AGE_FOR_COALESCE;
        if (_expanded && mature) {
            if (_tunnelCreateResponseTime != null) {_tunnelCreateResponseTime.coalesceStats();}
            if (_tunnelHistory != null) {_tunnelHistory.coalesceStats();}
        }
        if (_expandedDB) {
            if (_dbIntroduction != null) {_dbIntroduction.coalesceStats();}
            if (_dbResponseTime != null) {_dbResponseTime.coalesceStats();}
            if (_dbHistory != null) {_dbHistory.coalesceStats();}
        }
        coalesceThroughput(shouldDecay);
        if (_expanded && mature) {
            _speedValueNew = calculateSpeed();
            _capacityValueNew = calculateCapacity();
            _integrationValue = calculateIntegration();
        }
    }

    private float calculateSpeed() {return (float) SpeedCalculator.calc(this);}
    private float calculateCapacity() {return (float) CapacityCalculator.calc(this);}
    private float calculateIntegration() {return (float) IntegrationCalculator.calc(this);}

    /**
     * Helper for calculators.
     *
     * @return the router context
     * @since 0.9.2
     */
    RouterContext getContext() {return _context;}

    @Override
    public int hashCode() {return _peer.hashCode();}

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PeerProfile)) {return false;}
        PeerProfile prof = (PeerProfile)obj;
        return _peer.equals(prof._peer);
    }

    @Override
    public String toString() {return "Profile: " + _peer;}

    /**
     * RateStat memory per expanded profile:
     * PeerProfile:     3 RateStats (was 5; 2 dead removed), 3-5 Rates each - ~15 rates total
     * DBHistory:       2 RateStats, 2 rates each -            4 rates total
     * TunnelHistory:   2 RateStats, 2 rates each -            4 rates total
     *                ---                                    ---------
     *                 7                                      23 rates total
     *
     * shrinkProfile() / shrinkDBProfile() drop the PeerProfile
     * and DBProfile RateStats; TunnelHistory RateStats are final
     * and retained. Re-created lazily on the next expand.
     */

}
