package net.i2p.router.peermanager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;

import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Copied from http://www.i2p2.i2p/how_peerselection.html
 * <p>
 * See also main() below for additional commentary.
 * <p>
 * Currently, there is no 'ejection' strategy to get rid of the profiles for peers that
 * are no longer active (or when the network consists of thousands of peers, to get rid
 * of peers that are performing poorly). However, the size of each profile is fairly small,
 * and is unrelated to how much data is collected about the peer, so that a router can
 * keep a few thousand active peer profiles before the overhead becomes a serious concern.
 * Once it becomes necessary, we can simply compact the poorly performing profiles
 * (keeping only the most basic data) and maintain hundreds of thousands of profiles
 * in memory. Beyond that size, we can simply eject the peers (e.g. keeping the best 100,000).
*/

public class PeerProfile {
    /** Logger for this peer's profile. */
    private final Log _log;
    /** The router context. */
    private final RouterContext _context;
    /** The peer this profile describes. */
    private final Hash _peer;
    /** When we first heard about this peer. */
    private long _firstHeardAbout;
    /** When we last heard about this peer. */
    private long _lastHeardAbout;
    /** When a send to this peer last succeeded. */
    private long _lastSentToSuccessfully;
    /** When a send to this peer last failed. */
    private long _lastFailedSend;
    /** When we last received from this peer. */
    private long _lastHeardFrom;
    /** Average tunnel test response time, in ms. */
    private float _tunnelTestResponseTimeAvg;
    /** When the tunnel test average was last updated. */
    private long _tunnelTestTimeAvgLastUpdate;
    /** Average direct ping response time, in ms. */
    private float _peerTestResponseTimeAvg;
    /** Rate stat for netdb response times. */
    private RateStat _dbResponseTime;
    /** Rate stat for tunnel build response times. */
    private RateStat _tunnelCreateResponseTime;
    /** Rate stat for netdb introductions. */
    private RateStat _dbIntroduction;
    /**
     * Retained for backward compatibility with stored profile data. No longer
     * used for routing decisions; reads/writes go through the lowLatency flag.
     */
    private int _speedBonus;
    /**
     * Last update time of the speed bonus, for the 4-hour expiry.
     */
    private long _speedBonusLastUpdate;
    /** Bonus points for high measured capacity. */
    private int _capacityBonus;
    /** When the capacity bonus was last recomputed. */
    private long _capacityBonusLastUpdate;
    /** Bonus points for good netdb integration. */
    private int _integrationBonus;
    /** Computed speed score for this peer. */
    private float _speedValue;
    /** Computed capacity score for this peer. */
    private float _capacityValue;
    /** Computed integration score for this peer. */
    private float _integrationValue;
    /** Speed score computed by the new calculator. */
    private float _speedValueNew;
    /** Capacity score computed by the new calculator. */
    private float _capacityValueNew;
    /** Whether the profile is in coalescing state. */
    private boolean _coalescing;
    /** History of tunnel successes and failures. */
    private TunnelHistory _tunnelHistory;
    /** History of netdb successes and failures. */
    private DBHistory _dbHistory;
    /** Floodfill reliability classification based on proven request-handling history. Persisted. */
    private FloodfillReliability _floodfillReliability;
    /** Whether the tunnel history is expanded. */
    private boolean _expanded;
    /** Whether the netdb history is expanded. */
    private boolean _expandedDB;
    /** Low latency flag, set when peer responds quickly to tunnel builds, persisted. */
    private volatile boolean _lowLatency;
    /** Keyspace distance from us, for profile sorting. */
    private final int _distance;
    /** Packet-loss (retransmit) ratio reported by the transport, 0.0 = unknown/healthy. Not persisted. */
    private transient volatile float _lossRatio;
    /** When _lossRatio was last reported by the transport. */
    private transient volatile long _lossRatioLastUpdate;
    /** Decayed loss score (max of past score and reported ratio, 50% per 15 min time decay). Not persisted. */
    private transient volatile float _lossScore;
    /** When _lossScore was last updated. */
    private transient volatile long _lossScoreLastUpdate;
    /** When the peer was last demoted from the fast/high-cap tiers for packet loss, 0 = never. Persisted. */
    private volatile long _lossySince;

    /** Keep track of the fastest 8 throughputs unless slow, then 4. */
    private static final int THROUGHPUT_COUNT = SystemVersion.isSlow() ? 4 : 8;
    /**
     * Fastest 1 minute throughput, in bytes per minute, ordered with fastest
     * first.  This is not synchronized, as we don't *need* perfection, and we only
     * reorder/insert values on coalesce.
     */
    private final float[] _peakThroughput = new float[THROUGHPUT_COUNT];
    /** Throughput total for the current measurement period. */
    private final AtomicLong _peakThroughputCurrentTotal = new AtomicLong();
    /** Peak tunnel throughputs per period. */
    private final float[] _peakTunnelThroughput = new float[THROUGHPUT_COUNT];
    /** Total number of bytes pushed through a single tunnel in a 1 minute period. */
    private final float[] _peakTunnel1mThroughput = new float[THROUGHPUT_COUNT];
    /**
     * Monitor guarding {@link #_peakTunnel1mThroughput}. Private and final so
     * it cannot be locked from outside, unlike the array itself.
     * @since 0.9.71+
     */
    private final Object _peakTunnel1mLock = new Object();
    /** When the last tunnel test of this peer started. */
    private long _lastTestStarted;
    /** When the throughput totals were last updated. */
    private volatile long _lastThroughputUpdate;
    /** When the profile was last coalesced. */
    private long _lastCoalesceDate = System.currentTimeMillis();
    /** Rate stat periods for tunnel build responses. */
    private static final long[] TUNNEL_CREATE_RESPONSE_RATES = {
        RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR, RateConstants.ONE_DAY
    };
    /** Rate stat periods for netdb responses. */
    private static final long[] DB_RESPONSE_RATES = { RateConstants.ONE_HOUR };
    /** Rate stat periods for netdb introductions. */
    private static final long[] DB_INTRODUCTION_RATES = {
        RateConstants.ONE_HOUR, RateConstants.ONE_DAY
    };
    /** Profile age needed before coalescing. */
    private static final long MIN_AGE_FOR_COALESCE = 4 * RateConstants.ONE_HOUR;

    /**
     * Countries with more than about a 2% share of the netdb.
     * Only routers in these countries will use a same-country metric.
     * Yes this is an arbitrary cutoff.
     */
    private static final Set<String> _bigCountries = new HashSet<>();

    static {
        String[] big = new String[] { "fr", "de", "ru", "au", "us", "ca", "gb", "jp", "nl", "ir", "se" };
        _bigCountries.addAll(Arrays.asList(big));
    }

    /**
     * Caller should call {@link #setLastHeardAbout(long)} and
     * {@link #setFirstHeardAbout(long)}.
     *
     * @param context the router context
     * @param peer non-null
     */
    public PeerProfile(RouterContext context, Hash peer) {this(context, peer, false);}

    /**
     * Caller should call {@link #setLastHeardAbout(long)} and
     * {@link #setFirstHeardAbout(long)}.
     *
     * @param context the router context
     * @param peer non-null
     * @param expand whether to eagerly expand (default false; auto-expanded on first use)
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
     * What peer is being profiled.
     *
     * @return the peer, non-null
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
     * The low latency flag.
     *
     * @param low true for low latency
     */
    public void setLowLatency(boolean low) {_lowLatency = low;}

    /**
     * Packet-loss (retransmit) ratio reported by the transport, 0.0 = unknown/healthy.
     *
     * @return ratio of retransmitted packets to transmitted packets
     * @since 0.9.71+
     */
    public float getLossRatio() {return _lossRatio;}

    /**
     * When the loss ratio was last reported by the transport.
     *
     * @return time in ms
     * @since 0.9.71+
     */
    public long getLossRatioLastUpdate() {return _lossRatioLastUpdate;}

    /**
     * Record the packet-loss ratio reported by the transport.
     * <p>
     * The reported ratio is folded into the decayed loss score (see
     * {@link #getLossScore(long)}) so a single clean report does not erase
     * recent loss history — the score only declines with time.
     *
     * @param ratio retransmitted / transmitted packets, 0.0 = healthy
     * @param now time in ms
     * @since 0.9.71+
     */
    public void setLossRatio(float ratio, long now) {
        _lossRatio = ratio;
        _lossRatioLastUpdate = now;
        _lossScore = Math.max(getLossScore(now), ratio);
        _lossScoreLastUpdate = now;
    }

    /**
     * Half-life of the decayed loss score: with no new transport report the
     * score halves every 15 minutes, continuously. Chosen so a transient
     * congestion episode (a 25-40% retransmit ratio) falls back under the
     * 0.20 lossy threshold in roughly 20-40 minutes instead of the hour-plus
     * an hourly-quantized decay imposed, while a peer that keeps reporting
     * loss stays gated. Each report re-inflates the score through the max()
     * fold in {@link #setLossRatio}.
     *
     * @since 0.9.71+
     */
    static final long LOSS_DECAY_HALF_LIFE_MS = 15 * 60 * 1000L;

    /**
     * Decayed loss score, the maximum of all loss ratios reported within the
     * last few hours, with continuous time-based decay: 50% every
     * {@link #LOSS_DECAY_HALF_LIFE_MS} since the last report, capped at 16
     * half-lives (4 hours, effectively zero). Unlike {@link #getLossRatio}
     * this retains loss memory when the transport stops reporting, so a
     * demoted peer does not look clean again merely because its last sample
     * went stale.
     *
     * @param now current time in ms
     * @return the decayed loss score, 0.0 if no loss was ever reported
     * @since 0.9.71+
     */
    public float getLossScore(long now) {
        if (_lossScore <= 0.0f) return 0.0f;
        long ms = now - _lossScoreLastUpdate;
        if (ms <= 0) return _lossScore;
        // Continuous decay, 50% per half-life, capped at 16 half-lives (4 h)
        double halfLives = Math.min(ms / (double) LOSS_DECAY_HALF_LIFE_MS, 16.0);
        return (float) (_lossScore * Math.pow(0.5, halfLives));
    }

    /**
     * When the peer was last demoted from the fast/high-cap tiers for packet
     * loss, or 0 if it was never demoted. Persisted across restarts so loss
     * probation survives a router reboot.
     *
     * @return time of the last loss demotion in ms, or 0
     * @since 0.9.71+
     */
    public long getLossySince() {return _lossySince;}

    /**
     * Set when the peer is demoted from the fast/high-cap tiers for packet
     * loss. The peer must then produce fresh clean evidence before it is
     * re-admitted (see {@link ProfileOrganizer}.shouldReadmitLossy).
     *
     * @param when demotion time in ms
     * @since 0.9.71+
     */
    public void setLossySince(long when) {_lossySince = when;}

    /**
     * Clear the loss demotion flag after the peer has been re-admitted.
     *
     * @since 0.9.71+
     */
    public void clearLossy() {_lossySince = 0;}

    /**
     * Whether the peer is currently in loss probation.
     *
     * @return true if the peer was demoted for packet loss and not yet re-admitted
     * @since 0.9.71+
     */
    public boolean isLossy() {return _lossySince != 0;}

    /**
     * Is this peer active at the moment (sending/receiving messages within the last
     * 10 minutes).
     *
     * @return true if active
     */
    public boolean getIsActive() {
        return getIsActive(10*60*1000L, _context.clock().now());
    }

    /**
     * Is this peer active at the moment (sending/receiving messages within the last 10 minutes).
     *
     * @param now the current clock reading in milliseconds
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
     * For now, just a one-byte comparison.
     *
     * @return -127 to +128, lower is closer
     * @since 0.8.11
     */
    int getXORDistance() {return _distance;}

    /**
     * Is this peer active at the moment (sending/receiving messages within the
     * given period?)
     * Also mark active if it is connected, as this will tend to encourage use
     * of already-connected peers.
     *
     * @param period look-back window in ms (10 minutes for the tier checks,
     *               1 hour for the capacity calculator)
     * @param now the current clock reading in milliseconds
     * @return true if active
     * @since 0.9.58
     */
    public boolean getIsActive(long period, long now) {
        long before = now - period;
        return getLastHeardFrom() >= before || getLastSendSuccessful() >= before || isEstablished();
    }

    /**
     * When did we first hear about this peer?
     *
     * @return greater than zero, set to now in constructor
     */
    public synchronized long getFirstHeardAbout() {return _firstHeardAbout;}

    /**
     * Remember when we first heard about this peer, only if older.
     * Package private, only set by profile management subsystem.
     *
     * @param when the time to set
     */
    synchronized void setFirstHeardAbout(long when) {
        if (when < _firstHeardAbout) {_firstHeardAbout = when;}
    }

    /**
     * When did we last hear about this peer?
     *
     * @return 0 if unset
     */
    public synchronized long getLastHeardAbout() {return _lastHeardAbout;}

    /**
     * Remember when we last heard about this peer, only if unset or newer.
     * Also sets FirstHeardAbout if earlier.
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
     * @return milliseconds since the epoch, or 0 if never
     */
    public long getLastSendSuccessful() {return _lastSentToSuccessfully;}

    /**
     * When we last sent to this peer successfully.
     *
     * @param when milliseconds since the epoch
     */
    public void setLastSendSuccessful(long when) {_lastSentToSuccessfully = when;}

    /**
     * Measured direct-link RTT in ms, or 0 when never measured.
     *
     * @since 0.9.71+
     */
    private volatile int _firstHopRtt;
    /**
     * When {@link #_firstHopRtt} was last refreshed, in ms since the epoch.
     *
     * @since 0.9.71+
     */
    private volatile long _firstHopRttTime;

    /**
     * How long a recorded first-hop RTT stays usable.
     * <p>Four hours rather than the {@link RateConstants#ONE_HOUR} Active tier
     * period that {@link #getLastSendSuccessful} uses. Link latency is a
     * slow-changing property of a path, and it is now refreshed continuously by
     * {@code ProfileOrganizer.sampleFirstHopRtts()} on every reorganize, so this
     * window no longer decides how current a measurement is -- it only decides
     * how long a value outlives the session that produced it. An hour threw away
     * measurements of peers we simply had not talked to lately, which is what
     * left the profiles page 97% blank.
     * <p>The cost is that a peer whose session has gone away keeps its latency
     * standing for up to four hours, and the low-latency bar is absolute, so a
     * stale value is trusted as though current. If peer counts or the fast-tier
     * mix look wrong after this, this constant is the first thing to lower.
     *
     * @since 0.9.71+
     */
    static final long FIRST_HOP_RTT_VALIDITY_MS = 4 * 60 * 60 * 1000L;

    /**
     * Absolute ceiling for the low-latency flag, in ms. Fixed, not derived from
     * the network, so a rising average cannot promote genuinely slow peers.
     * @since 0.9.71+
     */
    static final long LOW_LATENCY_CEILING_MS = 1000L;

    /**
     * Absolute floor for the low-latency flag, in ms. This is the binding
     * criterion: a peer counts as low latency only if its direct-link RTT is at
     * or below this value.
     * <p>This replaced a {@code 0.5 * mean} relative test. The relative form was
     * unsound for two reasons. First, the population it divided came from a
     * decaying, self-selecting sample: an RTT is valid for one hour and
     * refreshes only when we happen to send to that peer, so on a ~950 peer fast
     * tier only ~37% carried a usable value and the mean tracked whoever we most
     * recently happened to contact. A mean drawn from that set shrinks as it
     * shrinks, so halving it ratchets the bar tighter over time. Second, the
     * arithmetic did not discriminate. Measured against a live fast tier the
     * mean was 86ms, so the bar sat at 43ms — below the population's own median
     * of 60ms, which demoted two thirds of every measured peer rather than a
     * tail.
     * <p>A fixed 100ms admits 69% and evicts 31% on that same population, which
     * is the discrimination the flag was meant to provide. It also does not
     * move as the sample decays, which was the defect that made the relative
     * form unsafe to leave in place.
     *
     * @since 0.9.71+
     */
    static final long LOW_LATENCY_FLOOR_MS = 100L;

    /**
     * The one smoothing applied to every latency measurement, whatever measured it.
     * <p>Peer latency reaches the profile from three directions — a peer test's
     * whole-tunnel round trip, the direct-link RTT a transport has measured for a
     * live session, and an SSU/NTCP handshake. They are different quantities, but
     * they are averaged the same way so that any one of them can be compared with
     * any other: a value damped 25% per sample behaves the same whether the sample
     * came from a tunnel round trip or a handshake.
     * <p>An unknown or zero current value is replaced rather than averaged. A
     * measurement is evidence; the absence of one is not, and damping toward zero
     * would manufacture latency out of missing data.
     *
     * @param current the stored average in ms, or 0/negative when unknown
     * @param sample the new measurement in ms
     * @return the new average in ms
     * @since 0.9.71+
     */
    static float dampLatency(float current, float sample) {
        if (current <= 0f) {return sample;}
        return 0.75f * current + 0.25f * sample;
    }

    /**
     * Record the transport's measured round trip time to this peer.
     * <p>This is the direct link cost, which is what first-hop selection needs.
     * A value of 0 means the session has not measured yet and is stored as
     * unknown rather than as zero latency.
     * <p>Damped through {@link #dampLatency(float, float)}, the same smoothing the
     * peer-test average uses, so a single session's noisy sample cannot set the
     * value on its own. A value that has already gone stale is replaced rather
     * than damped: {@link #getFirstHopRtt} has declared it unknown, so averaging
     * toward it would let expired evidence drag a fresh measurement back.
     * <p>A sample older than the one already held is discarded outright. This is
     * the single choke point every measurement passes through - the sampling pass,
     * the pre-connect probe and the handshake recorder all land here - so the
     * rule cannot be bypassed by a caller that forgot it. Without it a transport
     * whose reading is old but not yet expired re-submits that same old number
     * each cycle, which both drags the average back toward one stale sample and
     * pushes the timestamp forward, so the staleness window never opens.
     *
     * @param rtt   measured round trip time in ms, 0 if unmeasured
     * @param when  timestamp of the measurement; must not predate the value held
     * @since 0.9.71+
     */
    public void setFirstHopRtt(int rtt, long when) {
        if (rtt <= 0) {return;}
        if (_firstHopRtt > 0 && _firstHopRttTime > 0 && when < _firstHopRttTime) {return;}
        boolean stale = _firstHopRtt > 0 && _firstHopRttTime > 0 &&
                        when - _firstHopRttTime >= FIRST_HOP_RTT_VALIDITY_MS;
        _firstHopRtt = (int) (stale ? rtt : dampLatency(_firstHopRtt, rtt));
        _firstHopRttTime = when;
    }

    /**
     * The direct-link RTT to this peer, or -1 when it is unknown or stale.
     * <p>Stale means older than {@link #FIRST_HOP_RTT_VALIDITY_MS} (4 hours),
     * a window sized for the direct-link RTT itself rather than borrowed from
     * the tier freshness windows.
     *
     * @param now current time in ms
     * @return the RTT in ms, or -1 if never measured or older than the window
     * @since 0.9.71+
     */
    public int getFirstHopRtt(long now) {
        int rtt = _firstHopRtt;
        if (rtt <= 0) {return -1;}
        long when = _firstHopRttTime;
        if (when > 0 && now - when >= FIRST_HOP_RTT_VALIDITY_MS) {return -1;}
        return rtt;
    }

    /**
     * When the direct-link RTT was last refreshed.
     * @return timestamp in ms, or 0 if never measured
     * @since 0.9.71+
     */
    public long getFirstHopRttTime() {return _firstHopRttTime;}

    /**
     * Whether this peer counts as low latency, judged on its direct link.
     * <p>The single low-latency decision: {@link #recalculateLowLatency} is a thin
     * wrapper over this, so the flag is derived one way everywhere. Two
     * implementations of the same comparison is how the flag ends up disagreeing
     * with itself depending on which pass happened to run last.
     * <p>Two drivers, both required, because either alone is misleading:
     * <ul>
     * <li>an absolute ceiling, so a peer is never low latency merely because
     * the whole network has slowed and every average rose with it;</li>
     * <li>an absolute floor, so on a fast network a peer at the
     * absolute ceiling is still not low if it sits at the typical value.</li>
     * </ul>
     * <p>Only the direct link is consulted. Latency to this peer has nothing to
     * do with how long a build or test involving other peers took.
     *
     * @param now        current time in ms
     * @param ceilingMs  absolute low-latency ceiling in ms
     * @return TRUE or FALSE, or null when this peer has no usable first-hop RTT
     * @since 0.9.71+
     */
    public Boolean getLowLatencyForFirstHopRtt(long now, long ceilingMs) {
        int rtt = getFirstHopRtt(now);
        if (rtt < 0) {return null;}
        if (rtt > ceilingMs) {return Boolean.FALSE;}
        // Absolute floor, not a fraction of the population mean: see
        // LOW_LATENCY_FLOOR_MS for why the relative form was unsafe.
        if (rtt > LOW_LATENCY_FLOOR_MS) {return Boolean.FALSE;}
        return Boolean.TRUE;
    }

    /**
     * When did we last have a problem sending to this peer?
     *
     * @return milliseconds since the epoch, or 0 if never
     */
    public long getLastSendFailed() {return _lastFailedSend;}

    /**
     * When we last had a problem sending to this peer.
     *
     * @param when milliseconds since the epoch
     */
    public void setLastSendFailed(long when) {_lastFailedSend = when;}

    /**
     * When did we last hear from the peer?
     *
     * @return milliseconds since the epoch, or 0 if never
     */
    public long getLastHeardFrom() {return _lastHeardFrom;}

    /**
     * When we last heard from the peer.
     *
     * @param when milliseconds since the epoch
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
     * Whether this peer has non-trivial DB interaction activity.
     * Used to persist floodfill profiles across restarts so that
     * lookup/store success rates survive and the peer is not
     * misclassified as "too new" on the next startup.
     *
     * @return true if the peer has any successful or failed lookups or stores
     * @since 0.9.71+
     */
    public boolean hasDBActivity() {
        DBHistory h = _dbHistory;
        return h != null && (h.getSuccessfulLookups() > 0 || h.getFailedLookups() > 0 ||
                             h.getLastStoreSuccessful() > 0 || h.getLastStoreFailed() > 0);
    }

    /**
     * The tunnel history.
     *
     * @param history the TunnelHistory to hold, retrieved by {@link #getTunnelHistory()}
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
     * The DB history.
     *
     * @param hist the DBHistory to hold, retrieved by {@link #getDBHistory()}
     */
    public synchronized void setDBHistory(DBHistory hist) {_dbHistory = hist;}

    /**
     * Floodfill reliability classification based on proven request-handling history.
     * Persisted across restarts.
     *
     * @return reliability classification, never null
     * @since 0.9.71+
     */
    public synchronized FloodfillReliability getFloodfillReliability() {
        return _floodfillReliability != null ? _floodfillReliability : FloodfillReliability.UNKNOWN;
    }

    /**
     * Set the floodfill reliability classification.
     *
     * @param reliability the classification, must not be null
     * @since 0.9.71+
     */
    public synchronized void setFloodfillReliability(FloodfillReliability reliability) {
        _floodfillReliability = reliability;
    }

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
     * Retained for backward compatibility with stored profile data. Returns 0
     * unconditionally if not set, or the stored value with a 4-hour expiry for
     * existing profiles that still carry it.
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
     * Retained for backward compatibility with stored profile data.
     *
     * @param bonus the speed bonus
     */
    public void setSpeedBonus(int bonus) {_speedBonus = bonus; _speedBonusLastUpdate = _context.clock().now();}
    /**
     * Last update time of the speed bonus, for the 4-hour expiry.
     * Only used by profile persistence.
     *
     * @return the last update time
     */
    long getSpeedBonusLastUpdate() {return _speedBonusLastUpdate;}
    /**
     * Last update time of the speed bonus, for the 4-hour expiry.
     * Only used by profile persistence.
     *
     * @param ts the last update time
     */
    void setSpeedBonusLastUpdate(long ts) {_speedBonusLastUpdate = ts;}

    /**
     * Extra factor added to the capacity ranking - set manually on the
     * configpeers page to nudge how the algorithm ranks a peer. Negative
     * values are penalties. Expires 4 hours after the last manual update
     * and is not persisted across a restart.
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
     * Set the capacity bonus, restarting its 4-hour expiry clock.
     * Live only - never written back to the profile file.
     *
     * @param bonus the capacity bonus
     */
    public void setCapacityBonus(int bonus) {_capacityBonus = bonus; _capacityBonusLastUpdate = _context.clock().now();}

    /**
     * The raw capacity bonus value.
     *
     * @return the bonus before time-decay is applied
     */
    int getCapacityBonusRaw() {return _capacityBonus;}

    /**
     * Extra factor added to the integration ranking - this can be updated in the profile
     * written to disk to affect how the algorithm ranks integration.  Negative values are
     * penalties.
     *
     * @return the integration bonus
     */
    public int getIntegrationBonus() {return _integrationBonus;}

    /**
     * The integration bonus.
     *
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
     * EWMA average of the tunnel test response time.
     * <p>Returns 0 if the data is older than 1 hour, treating the peer as
     * untested. This ensures stale peers are re-tested rather than selected
     * for tunnel builds based on outdated latency data.</p>
     *
     * @return EWMA average in ms, or 0 if stale (&gt;1 hour since last update)
     */
    public float getTunnelTestTimeAverage() {
        if (_tunnelTestResponseTimeAvg <= 0 || _tunnelTestTimeAvgLastUpdate <= 0) return 0;
        long msSinceUpdate = _context.clock().now() - _tunnelTestTimeAvgLastUpdate;
        if (msSinceUpdate > 60 * 60 * 1000L) return 0;
        return _tunnelTestResponseTimeAvg;
    }

    /**
     * Timestamp when the EWMA was last updated.
     *
     * @return the time, in ms
     */
    public long getTunnelTestTimeAvgLastUpdate() {return _tunnelTestTimeAvgLastUpdate;}

    /**
     * The tunnel test time average.
     *
     * @param avg the average in ms
     */
    void setTunnelTestTimeAverage(float avg) {_tunnelTestResponseTimeAvg = avg;}

    /**
     * The timestamp of the last tunnel test time average update.
     *
     * @param ts milliseconds since the epoch of the last tunnel test time average update
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
                      "] updated to " + DataHelper.formatDuration((long) _tunnelTestResponseTimeAvg));
        }
    }

    /**
     * The average peer test time.
     *
     * @return the peer test time average
     */
    public float getPeerTestTimeAverage() {return _peerTestResponseTimeAvg;}

    /**
     * Store the average peer test time.
     *
     * @param testAvg the peer test time average
     */
    void setPeerTestTimeAverage(float testAvg) {_peerTestResponseTimeAvg = testAvg;}

    /**
     * Update the peer test time average from a new measurement.
     * <p>Damped through {@link #dampLatency(float, float)}, the same smoothing every
     * other latency writer uses, so a test result and a transport RTT and a
     * handshake all accumulate into a comparable average.
     *
     * @param ms the new measurement in ms
     */
    void updatePeerTestTimeAverage(float ms) {
        _peerTestResponseTimeAvg = dampLatency(_peerTestResponseTimeAvg, ms);
        if (_log.shouldInfo()) {
            _log.info("Timed peer test average for [" + _peer.toBase64().substring(0,6) +
                      "] updated to " + (_peerTestResponseTimeAvg) + "ms");
        }
    }

    /**
     * Whether the peer has been tested (has a peer test response time average).
     * Used by the bandwidth tier fast-track to distinguish "untested, give a
     * chance" from "tested and known slow".
     *
     * @return true if the peer has a measured peer test average
     * @since 0.9.71+
     */
    public boolean hasBeenTested() {return _peerTestResponseTimeAvg > 0;}

    /**
     * Recalculate the low-latency flag from the measured direct-link RTT.
     * <p>The only low-latency decision in the codebase: both callers. The
     * reorganize sampling pass and the recorder that runs when a measurement
     * arrives — come through here, so the flag cannot be derived two different
     * ways from the same field.
     * <p>Judged on the direct link to this peer, not on how long a test through a
     * multi-hop tunnel took. {@link #getPeerTestTimeAverage} is the round trip
     * across every hop of that tunnel, so using it here measured the latency of
     * hops the peer was merely a member of.
     * <p>An unmeasured peer keeps whatever was persisted: absent evidence is not
     * evidence of high latency.
     *
     * @since 0.9.70
     */
void recalculateLowLatency() {
          recalculateLowLatency(null);
      }

    /**
     * Recompute the low-latency flag, optionally against a calibrated
     * per-transport floor instead of the absolute one.
     * <p>This remains the single low-latency decision. The calibrated floor only
     * supplies an alternative bar and never a separate verdict. It is
     * deliberately one-directional: the floor is derived from measured link
     * latencies across all peers, and nothing about this peer's tier membership
     * feeds back into it, so no cycle can tighten the bar in response to the
     * tiers that bar itself produces.
     * <p>A null or negative {@code calibratedFloorMs} means the transport has no
     * calibrated opinion yet, and the absolute rule applies unchanged - so on a
     * cold router the behaviour here is identical to it being absent.
     *
     * @param calibratedFloorMs the transport's calibrated floor in ms, or
     * -1/null when unknown
     * @since 0.9.71+
     */
    void recalculateLowLatency(Long calibratedFloorMs) {
        recalculateLowLatency(calibratedFloorMs, null);
      }

    /**
     * Recompute the low-latency flag against the calibrated bounds.
     * <p>This remains the single low-latency decision. The calibrated values only
     * supply alternative bars and never a separate verdict. It is deliberately
     * one-directional: the floors are derived from measured link latencies across
     * all peers, and nothing about this peer's tier membership feeds back into
     * them, so no cycle can tighten a bar in response to the tiers that bar
     * itself produces.
     * <p>A null or negative value means that bound has no calibrated opinion yet
     * and is skipped, leaving the absolute rule to decide alone. On a cold router
     * both are null, so behaviour is identical to their absence.
     * <p>Both calibrated bounds can only ever widen the absolute rule, never
     * narrow it: the Organizer clamps the transport floor to at least
     * {@code LOW_LATENCY_FLOOR_MS / multiple}, and the cohort floor is applied
     * only where the absolute rule already agreed.
     *
     * @param calibratedFloorMs the transport's calibrated floor in ms, or null
     * @param cohortFloorMs     the fast-cohort floor in ms, or null
     * @since 0.9.71+
     */
    void recalculateLowLatency(Long calibratedFloorMs, Long cohortFloorMs) {
        Boolean low = getLowLatencyForFirstHopRtt(_context.clock().now(),
                                                  LOW_LATENCY_CEILING_MS);
        if (low == null) {return;}
        _lowLatency = low.booleanValue();
        // The absolute ceiling above still rejects outright; a calibrated bound
        // only ever removes a peer the absolute rule had already accepted.
        if (!_lowLatency) {return;}
        if (calibratedFloorMs != null && calibratedFloorMs > 0 &&
            _firstHopRtt > calibratedFloorMs) {
            _lowLatency = Boolean.FALSE;
            return;
        }
        if (cohortFloorMs != null && cohortFloorMs > 0 && _firstHopRtt > cohortFloorMs) {
            _lowLatency = Boolean.FALSE;
        }
    }

    /**
     * The peak throughput in KBps.
     *
     * @return the sum over all periods
     */
    public float getPeakThroughputKBps() {
        float rv = 0;
        for (int i = 0; i < THROUGHPUT_COUNT; i++) {rv += _peakThroughput[i];}
        rv /= (60 * 1024L * THROUGHPUT_COUNT);
        return rv;
    }

    /**
     * Only for restoration from persisted profile.
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
     * @param size the number of bytes pushed; long because the running total is
     *             a long and a per-period sum can exceed Integer.MAX_VALUE
     */
    void dataPushed(long size) {_peakThroughputCurrentTotal.addAndGet(size);}

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
     * The peak tunnel throughput in KBps.
     *
     * @return the sum over all periods
     */
    public float getPeakTunnelThroughputKBps() {
        float rv = 0;
        for (int i = 0; i < THROUGHPUT_COUNT; i++) {rv += _peakTunnelThroughput[i];}
        rv /= (10 * 60 * 1024L * THROUGHPUT_COUNT);
        return rv;
    }

    /**
     * Only for restoration from persisted profile.
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
     * @param size the number of bytes in that minute, normalized; comparisons
     *             against the float ring widen, so the stored KBps average is unaffected
     */
    void dataPushed1m(long size) {
        _lastThroughputUpdate = _context.clock().now();
        float lowPeak = _peakTunnel1mThroughput[THROUGHPUT_COUNT-1];
        if (size > lowPeak) {
            // Monitor a dedicated, private object rather than the float array.
            // Synchronizing on the array both exposes a mutable object as a
            // lock (any other holder of that reference could lock it) and read
            // the last slot outside the lock above, so the value that gated the
            // update was not the value the update compared against.
            synchronized (_peakTunnel1mLock) {
                for (int i = 0; i < THROUGHPUT_COUNT; i++) {
                    if (size > _peakTunnel1mThroughput[i]) {
                        for (int j = THROUGHPUT_COUNT-1; j > i; j--)
                           _peakTunnel1mThroughput[j] = _peakTunnel1mThroughput[j-1];
                        _peakTunnel1mThroughput[i] = size;
                        break;
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
    }

    /**
     * This is the speed value.
     *
     * @return the average of the eight fastest one-minute data transfers (four
     * on slow JVMs), on a per-tunnel basis,
     * through this peer. Ever. Peaks are never decayed; only a higher
     * measurement can raise them, and a lower one is ignored. Freshness
     * is judged by the selection gates, not by eroding these values.
     */
    public float getPeakTunnel1mThroughputKBps() {
        float rv = 0;
        synchronized (_peakTunnel1mLock) {
            for (int i = 0; i < THROUGHPUT_COUNT; i++) {rv += _peakTunnel1mThroughput[i];}
        }
        rv /= (60 * 1024L * THROUGHPUT_COUNT);
        return rv;
    }

    /**
     * Only for restoration from persisted profile.
     *
     * @param kBps the peak 1-minute throughput in KBps
     */
    void setPeakTunnel1mThroughputKBps(float kBps) {
        // Set all so the average remains the same
        float speed = kBps * (60 * 1024);
        synchronized (_peakTunnel1mLock) {
            for (int i = 0; i < THROUGHPUT_COUNT; i++) {_peakTunnel1mThroughput[i] = speed;}
        }
    }

    /**
     * The timestamp of the last throughput update.
     *
     * @return the time, in ms
     */
    long getLastThroughputUpdate() {return _lastThroughputUpdate;}

    /**
     * Store the timestamp of the last throughput update.
     *
     * @param ts milliseconds since the epoch of the last throughput update
     */
    void setLastThroughputUpdate(long ts) {_lastThroughputUpdate = ts;}

    /**
     * The timestamp of the last test start.
     *
     * @return the time, in ms
     */
    public long getLastTestStarted() {return _lastTestStarted;}

    /**
     * Store the timestamp of the last test start.
     *
     * @param ts milliseconds since the epoch of the last test start
     */
    void setLastTestStarted(long ts) {_lastTestStarted = ts;}

    /**
     * When the given peer is performing well enough that we want to keep detailed
     * stats on them again, call this to set up the info we dropped during shrinkProfile.
     * This will not however overwrite any existing data, so it can be safely called
     * repeatedly.
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
     * For floodfills.
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
     * Shrink the profile by dropping the RateStat objects.
     * They will be re-created lazily by {@link #expandProfile()}
     * when the profile is used again.
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
     * Shrink the DB-specific part of the profile.
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

    /**
     * Coalesce throughput peaks: insert new measurement into the sorted
     * peak arrays.  Existing peaks are never modified. They represent
     * demonstrated capability, not recency.  Freshness is handled by
     * selection gates (isLowLatency, getIsActive, hasValidRouterInfo)
     * rather than by eroding the data.
     * <p>
     * At most one measurement per minute is folded in, so the running total is
     * drained on a one-minute cadence.
     */
    private void coalesceThroughput() {
        long now = System.currentTimeMillis();
        long measuredPeriod = now - _lastCoalesceDate;
        if (measuredPeriod >= 60*1000L) {
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
        if (!_coalescing) {coalesceOnly();} // can happen
        _coalescing = false;
        _speedValue = _speedValueNew;
        _capacityValue = _capacityValueNew;
    }

    /**
     * Coalesce all stats and update values.
     */
    public synchronized void coalesceStats() {
        coalesceOnly();
        updateValues();
    }

    /**
     * Caller must next call {@link #updateValues()}.
     * <p>No peak throughput value is decayed: coalescing only folds a new
     * measurement into the peak arrays and never erases demonstrated capacity.
     *
     * @since 0.9.4
     */
    synchronized void coalesceOnly() {
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
        coalesceThroughput();
        if (_expanded && mature) {
            _speedValueNew = calculateSpeed();
            _capacityValueNew = calculateCapacity();
            _integrationValue = calculateIntegration();
        }
    }

    /**
     * Recomputes the speed score from the history.
     *
     * @return the new speed score
     */
    private float calculateSpeed() {return (float) SpeedCalculator.calc(this);}
    /**
     * Recomputes the capacity score from the history.
     *
     * @return the new capacity score
     */
    private float calculateCapacity() {return (float) CapacityCalculator.calc(this);}
    /**
     * Recomputes the integration score from the history.
     *
     * @return the new integration score
     */
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
     * PeerProfile:     3 RateStats (was 5. 2 dead removed), 1-3 Rates each -   6 rates total
     * DBHistory:       2 RateStats, 1-2 rates each -                           3 rates total
     * TunnelHistory:   2 RateStats, 2 rates each -                             4 rates total
     * ---                                    ---------
     * 7                                      13 rates total
     * <p>
     * {@link #shrinkProfile()} / shrinkDBProfile() drop the PeerProfile
     * and DBProfile RateStats. TunnelHistory RateStats are final
     * and retained. Re-created lazily on the next expand.
     */

}
