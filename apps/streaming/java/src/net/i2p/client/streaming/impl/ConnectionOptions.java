package net.i2p.client.streaming.impl;

import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.function.IntConsumer;
import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Hash;
import net.i2p.util.ConvertToHash;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Define the current options for the con (and allow custom tweaking midstream).
 *
 * <p>Static fields with {@code volatile} and class-level setters (e.g.,
 * {@link #setInitialRTO(int)}, {@link #setMaxWindowSize(int)}) are
 * globally shared and adjusted by the Tuner.  Per-connection fields are
 * copied from these globals when a new {@code Connection} is created.
 */
/** ignored */
class ConnectionOptions extends I2PSocketOptionsImpl {
    /** ignored */
    private int _connectDelay;
    /** ignored */
    private boolean _fullySigned;
    /** ignored */
    private boolean _answerPings;
    /** ignored */
    private boolean _enforceProto;
    /** ignored */
    private volatile int _windowSize;
    /** ignored */
    private int _profile;
    /** ignored */
    private int _smoothedRtt;
    /** ignored */
    private int _minRtt = DEFAULT_INITIAL_RTT;
    /** ignored */
    private int _rttDeviation;
    /** ignored */
    private int _retransmitTimeout = _defaultInitialRTO;
    /** ignored */
    private int _retransmitDelay;
    /** ignored */
    private int _ackDelay;
    /** ignored */
    private int _maxMessageSize;
    /** ignored */
    private int _maxInitialMessageSize;
    /** ignored */
    private int _maxResends;
    /** ignored */
    private int _inactivityTimeout;
    /** ignored */
    private int _inactivityAction;
    /** ignored */
    private volatile int _inboundBufferSize;
    /** ignored */
    private volatile int _maxWindowSize;
    /** ignored */
    private volatile int _congestionAvoidanceGrowthRateFactor;
    /** ignored */
    private volatile int _slowStartGrowthRateFactor;
    /** ignored */
    private volatile boolean _accessListEnabled;
    /** ignored */
    private volatile boolean _blackListEnabled;
    /** ignored */
    private Set<Hash> _accessList;
    /** ignored */
    private Set<Hash> _blackList;
    /** ignored */
    private volatile int _maxConnsPerMinute;
    /** ignored */
    private volatile int _maxConnsPerHour;
    /** ignored */
    private volatile int _maxConnsPerDay;
    /** ignored */
    private volatile int _maxTotalConnsPerMinute;
    /** ignored */
    private volatile int _maxTotalConnsPerHour;
    /** ignored */
    private volatile int _maxTotalConnsPerDay;
    /** ignored */
    private volatile int _maxConns;
    /** ignored */
    private volatile boolean _disableRejectLog;
    /** ignored */
    private volatile String _limitAction;
    /** ignored */
    private volatile int _tagsToSend;
    /** ignored */
    private volatile int _tagThreshold;

    /** Prevents RTO overshoot from rapid RetransmitEvent + ResendPacketEvent double-fires */
    private long _lastRtoDoubleTime;

    /** Hybrid byte+packet buffer limit; default 1024 for optimal performance */
    private volatile int _maxPacketCount = 1024;

    /** RTT initialization state machine */
    private enum RttState {
        INIT, FIRST, STEADY
    }

    /** synchronize access */
    private RttState _rttState = RttState.INIT;

    /** No action taken when inactivity timeout fires */
    public static final int INACTIVITY_ACTION_NOOP = 0;
    /** Disconnect the connection when inactivity timeout fires */
    public static final int INACTIVITY_ACTION_DISCONNECT = 1;
    /** Send a keepalive when inactivity timeout fires */
    public static final int INACTIVITY_ACTION_SEND = 2;

    /** RFC 6298 RTT smoothing constants */
    private static final float RTT_ALPHA = 1.0f/8;
    /** ignored */
    private static final float RTT_BETA = 1.0f/4;
    /** ignored */
    private static final float RTT_KAPPA = 4;

    /** ignored */
    private static final String PROP_INITIAL_RTO = "i2p.streaming.initialRTO";
    /** ignored */
    static final String PROP_MAX_RTO = "i2p.streaming.maxRTO";

    /** @since 0.9.70+ mutable for adaptive tuning */
    private static volatile int _defaultInitialRTO = 3000;

    /** ignored */
    static int getInitialRTO() { return _defaultInitialRTO; }
    /** ignored */
    static void setInitialRTO(int val) { _defaultInitialRTO = Math.max(500, Math.min(15000, val)); }

    /**
     * Default 15000 accommodates RTT up to ~7s with standard TCP deviation.
     * Tuner may raise this for high-latency networks to avoid spurious retransmits.
     */
    /** ignored */
    private static volatile int _maxRTO = 15000;

    /** @since 0.9.70+ */
    public static int getMaxRTOStatic() { return _maxRTO; }
    /** @since 0.9.70+ */
    public static void setMaxRTO(int val) { _maxRTO = Math.max(1000, Math.min(60000, val)); }

    /** RTO multiplier as percentage (e.g. 150 = 1.5x), clamped to [100, 500] */
    static final String PROP_RTO_MULTIPLIER = "i2p.streaming.rtoMultiplier";

    /** @since 0.9.70+ mutable for adaptive tuning */
    private static volatile int _rtoMultiplier = 150;

    /** @since 0.9.70+ */
    static int getRTOMultiplier() { return _rtoMultiplier; }
    /** @since 0.9.70+ */
    static void setRTOMultiplier(int val) { _rtoMultiplier = Math.max(100, Math.min(500, val)); }

    /** ignored */
    private static volatile int _minResendDelay = 300;

    /** @since 0.9.70+ */
    public static int getMinResendDelayStatic() { return _minResendDelay; }
    /** @since 0.9.70+ */
    public static void setMinResendDelay(int val) { _minResendDelay = Math.max(300, Math.min(5000, val)); }

    /** ignored */
    private static volatile int _maxResendDelay = 15000;

    /** @since 0.9.70+ */
    public static int getMaxResendDelayStatic() { return _maxResendDelay; }
    /** @since 0.9.70+ */
    public static void setMaxResendDelay(int val) { _maxResendDelay = Math.max(1000, Math.min(60000, val)); }

    /** ignored */
    private int getMaxRTO() { return _maxRTO; }
    /** ignored */
    private int getMinResendDelay() { return _minResendDelay; }
    /** ignored */
    private int getMaxResendDelay() { return _maxResendDelay; }

    /** Delay before starting connection setup, in ms */
    public static final String PROP_CONNECT_DELAY = "i2p.streaming.connectDelay";
    /** Maximum size of a streaming message, in bytes */
    public static final String PROP_MAX_MESSAGE_SIZE = "i2p.streaming.maxMessageSize";
    /** Maximum number of times a message is resent */
    public static final String PROP_MAX_RESENDS = "i2p.streaming.maxResends";
    /** Initial delay before retransmitting, in ms */
    public static final String PROP_INITIAL_RESEND_DELAY = "i2p.streaming.initialResendDelay";
    /** Initial delay before sending an ACK, in ms */
    public static final String PROP_INITIAL_ACK_DELAY = "i2p.streaming.initialAckDelay";
    /** Initial congestion window size (messages in flight) */
    public static final String PROP_INITIAL_WINDOW_SIZE = "i2p.streaming.initialWindowSize";
    /** Idle time before inactivity action fires, in ms */
    public static final String PROP_INACTIVITY_TIMEOUT = "i2p.streaming.inactivityTimeout";
    /** Action to take on inactivity timeout */
    public static final String PROP_INACTIVITY_ACTION = "i2p.streaming.inactivityAction";
    /** Maximum congestion window size */
    public static final String PROP_MAX_WINDOW_SIZE = "i2p.streaming.maxWindowSize";
    /** Congestion avoidance window growth rate factor */
    public static final String PROP_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR = "i2p.streaming.congestionAvoidanceGrowthRateFactor";
    /** Slow start window growth rate factor */
    public static final String PROP_SLOW_START_GROWTH_RATE_FACTOR = "i2p.streaming.slowStartGrowthRateFactor";
    /** Whether to respond to ping messages */
    public static final String PROP_ANSWER_PINGS = "i2p.streaming.answerPings";
    /** Enable destination access list filtering */
    public static final String PROP_ENABLE_ACCESS_LIST = "i2cp.enableAccessList";
    /** Enable destination blacklist filtering */
    public static final String PROP_ENABLE_BLACKLIST = "i2cp.enableBlackList";
    /** Comma-separated list of allowed or blocked destination hashes */
    public static final String PROP_ACCESS_LIST = "i2cp.accessList";
    /** Max connections per minute from a single peer */
    public static final String PROP_MAX_CONNS_MIN = "i2p.streaming.maxConnsPerMinute";
    /** Max connections per hour from a single peer */
    public static final String PROP_MAX_CONNS_HOUR = "i2p.streaming.maxConnsPerHour";
    /** Max connections per day from a single peer */
    public static final String PROP_MAX_CONNS_DAY = "i2p.streaming.maxConnsPerDay";
    /** Max total connections per minute across all peers */
    public static final String PROP_MAX_TOTAL_CONNS_MIN = "i2p.streaming.maxTotalConnsPerMinute";
    /** Max total connections per hour across all peers */
    public static final String PROP_MAX_TOTAL_CONNS_HOUR = "i2p.streaming.maxTotalConnsPerHour";
    /** Max total connections per day across all peers */
    public static final String PROP_MAX_TOTAL_CONNS_DAY = "i2p.streaming.maxTotalConnsPerDay";
    /** Reject connections without PROTO_STREAMING flag */
    public static final String PROP_ENFORCE_PROTO = "i2p.streaming.enforceProtocol";

    /** @since 0.9.3 moved from I2PSocketManagerFull */
    public static final String PROP_MAX_STREAMS = "i2p.streaming.maxConcurrentStreams";

    /** @since 0.9.4 default false */
    public static final String PROP_DISABLE_REJ_LOG = "i2p.streaming.disableRejectLogging";

    /** reset, drop, http, or custom string; default reset @since 0.9.34 */
    public static final String PROP_LIMIT_ACTION = "i2p.streaming.limitAction";

    /** @since 0.9.34 */
    public static final String PROP_TAGS_TO_SEND = "crypto.tagsToSend";

    /** @since 0.9.34 */
    public static final String PROP_TAG_THRESHOLD = "crypto.lowTagThreshold";

    /**
     * RFC 6928 recommends 10; increased to 16 for I2P's high-latency environment.
     * @since 0.9.70+ mutable for adaptive tuning
     */
    /** ignored */
    static volatile int _initialWindowSize = 16;

    /** Default maximum number of times a single message will be retransmitted */
    static final int DEFAULT_MAX_SENDS = 30;

    /** ignored */
    static int getInitialWindowSize() { return _initialWindowSize; }
    /** ignored */
    static void setInitialWindowSize(int val) { _initialWindowSize = Math.max(4, Math.min(256, val)); }

    /**
     * Initial RTT estimate before first measurement.
     * I2P typically has 2-10s RTT, so 5s provides a conservative starting point.
     */
    /** DEFAULT_INITIAL_RTT */
    public static final int DEFAULT_INITIAL_RTT = 5*1000;

    /** Max RTT to prevent pathological RTO cases. Tunable via i2p.streaming.maxRtt (default 10000). */
    private static volatile int _maxRTT = 10*1000;

    /** ignored */
    private int getMaxRtt() { return _maxRTT; }
    /**
     * setMaxRtt.
     */
    /** _maxRTT */
    public static void setMaxRtt(int val) { _maxRTT = Math.max(1000, Math.min(60000, val)); }
    /**
     * getMaxRttStatic.
     */
    /** ignored */
    public static int getMaxRttStatic() { return _maxRTT; }

    /**
     * 500ms provides a reasonable delayed-ACK window for I2P's high-latency,
     * allowing data piggybacking and reducing ACK-only packet floods.
     * Ref: RFC 5681 sec. 4.3, RFC 1122 sec. 4.2.3.3, ticket #2706
     * @since 0.9.70+ mutable for adaptive tuning
     */
    /** ignored */
    private static volatile int _defaultInitialAckDelay = 500;

    /**
     * getDefaultInitialAckDelay.
     */
    /** ignored */
    public static int getDefaultInitialAckDelay() { return _defaultInitialAckDelay; }
    /**
     * setDefaultInitialAckDelay.
     */
    /** _defaultInitialAckDelay */
    public static void setDefaultInitialAckDelay(int val) { _defaultInitialAckDelay = Math.max(10, Math.min(500, val)); }

    /**
     * getDefaultInactivityTimeout.
     */
    /** ignored */
    public static int getDefaultInactivityTimeout() { return _defaultInactivityTimeout; }
    /**
     * setDefaultInactivityTimeout.
     */
    /** _defaultInactivityTimeout */
    public static void setDefaultInactivityTimeout(int val) { _defaultInactivityTimeout = Math.max(60000, Math.min(600000, val)); }

    /** Minimum congestion window size (one message in flight) */
    static final int MIN_WINDOW_SIZE = 1;
    /** ignored */
    private static final boolean DEFAULT_ANSWER_PINGS = true;

    /** @since 0.9.70+ mutable for adaptive tuning */
    static volatile int _defaultInactivityTimeout = 300000;

    /** ignored */
    private static final int DEFAULT_INACTIVITY_ACTION = INACTIVITY_ACTION_SEND;

    /** @since 0.9.70+ mutable for adaptive tuning */
    static volatile int _maxSlowStartWindow = SystemVersion.isSlow() ? 32 : 256;

    /** ignored */
    static int getMaxSlowStartWindowStatic() { return _maxSlowStartWindow; }
    /** ignored */
    static void setMaxSlowStartWindow(int val) { _maxSlowStartWindow = Math.max(8, Math.min(Connection.ABSOLUTE_MAX_WINDOW, val)); }

    /** ignored */
    private static volatile int _immediateAckDelay = SystemVersion.isSlow() ? 100 : 80;

    /** ignored */
    static int getImmediateAckDelayStatic() { return _immediateAckDelay; }
    /** ignored */
    static void setImmediateAckDelay(int val) { _immediateAckDelay = Math.max(1, Math.min(1000, val)); }

    /** @since 0.9.70+ mutable for adaptive tuning */
    private static volatile int _defaultRetransmitDelay = 1000;

    /**
     * getDefaultResendDelayStatic.
     */
    /** ignored */
    public static int getDefaultResendDelayStatic() { return _defaultRetransmitDelay; }
    /**
     * setDefaultResendDelay.
     */
    /** _defaultRetransmitDelay */
    public static void setDefaultResendDelay(int val) { _defaultRetransmitDelay = Math.max(100, Math.min(3000, val)); }

    /** @since 0.9.70+ mutable for adaptive tuning */
    private static volatile int _defaultCongestionAvoidanceGrowthRateFactor = 1;

    /**
     * getDefaultCongestionAvoidanceGrowthRateFactorStatic.
     */
    /** ignored */
    public static int getDefaultCongestionAvoidanceGrowthRateFactorStatic() { return _defaultCongestionAvoidanceGrowthRateFactor; }
    /**
     * setDefaultCongestionAvoidanceGrowthRateFactor.
     */
    /** _defaultCongestionAvoidanceGrowthRateFactor */
    public static void setDefaultCongestionAvoidanceGrowthRateFactor(int val) { _defaultCongestionAvoidanceGrowthRateFactor = Math.max(1, Math.min(4, val)); }

    /** @since 0.9.70+ mutable for adaptive tuning */
    private static volatile int _defaultSlowStartGrowthRateFactor = 2;

    /**
     * getDefaultSlowStartGrowthRateFactorStatic.
     */
    /** ignored */
    public static int getDefaultSlowStartGrowthRateFactorStatic() { return _defaultSlowStartGrowthRateFactor; }
    /**
     * setDefaultSlowStartGrowthRateFactor.
     */
    /** _defaultSlowStartGrowthRateFactor */
    public static void setDefaultSlowStartGrowthRateFactor(int val) { _defaultSlowStartGrowthRateFactor = Math.max(1, Math.min(4, val)); }

    /**
     * Minimum pacing rate. Below this, pacing is disabled to avoid excessive delays.
     * Default 16 KB/s, tunable via Tuner.
     */
    /** ignored */
    private static volatile long _minPacingRate = 16 * 1024;

    /**
     * getMinPacingRate.
     */
    /** ignored */
    public static long getMinPacingRate() { return _minPacingRate; }
    /**
     * setMinPacingRate.
     */
    /** _minPacingRate */
    public static void setMinPacingRate(long val) { _minPacingRate = Math.max(1024, Math.min(256 * 1024, val)); }

    /** @since 0.9.70+ */
    public static int getMinPacingRateKBps() { return (int) (_minPacingRate / 1024); }

    /** KB/s wrapper for Tuner int reflection */
    public static void setMinPacingRateKBps(int val) { _minPacingRate = Math.max(1024, Math.min(256 * 1024, (long) val * 1024)); }

    /** @since 0.9.34 */
    private static final String DEFAULT_LIMIT_ACTION = "reset";

    /** @since 0.9.34 */
    public static final int DEFAULT_TAGS_TO_SEND = 40;

    /** @since 0.9.34 */
    public static final int DEFAULT_TAG_THRESHOLD = 30;

    /**
     * If PROTO is enforced, we cannot communicate with destinations before 0.7.1.
     * Default true since 0.9.36.
     */
    /** ignored */
    private static final boolean DEFAULT_ENFORCE_PROTO = true;

    /*
     * Message size derivation (1730 == 2 tunnel messages):
     *   1024 Tunnel Message - 21 Header = 1003 Tunnel Payload
     *   - 39 Unfragmented instructions = 964 Garlic Message
     *   - 16 I2NP header - 4 length = 944 Garlic payload (AES padded)
     *   - 32 tag - 2 count - 4 size - 32 hash - 1 flags - 1 clove count
     *   - 33 delivery - 4 ID - 8 exp - 3 clove cert - 3 garlic cert - 4 ID - 8 exp
     *     = 809 Data Message - 16 I2NP - 4 length = 789 Gzipped
     *   - 23 gzip overhead = 766 - 28 streaming header = 738 (1 msg)
     *
     *   With 2 tunnel messages: 738 * 2 + 254 = 1730
     *   See also: 3 msgs = 2722, 4 msgs = 3714
     *
     * Historical values: 4096 (pre-0.6.1.14), 960 (0.6.1.14-0.6.4),
     * 1730 (0.6.5+). The earlier 960 didn't actually fit in one tunnel msg
     * due to leaseSet bundling overhead.
     */
    /** Default maximum streaming message payload size, derived from 2 tunnel messages */
    public static final int DEFAULT_MAX_MESSAGE_SIZE = 1730;
    /** Minimum allowed message size */
    public static final int MIN_MESSAGE_SIZE = 512;

    /** Override max message size for ratcheted connections per proposal 144 @since 0.9.47 */
    public static final int DEFAULT_MAX_MESSAGE_SIZE_RATCHET = 1812;

    /**
     * Create options with default values from system properties
     */
    /** ignored */
    public ConnectionOptions() {
        super();
        _smoothedRtt = DEFAULT_INITIAL_RTT;
        initFromProperties(System.getProperties());
    }

    /**
     * Create options initialized from the given properties
     *
     * @param opts properties to initialize from, may be null
     */
    /** ignored */
    public ConnectionOptions(Properties opts) {
        super(opts);
        _smoothedRtt = DEFAULT_INITIAL_RTT;
        initFromProperties(opts);
    }

    /**
     * Create options as a copy of the given I2PSocketOptions,
     * then apply system property defaults for streaming-specific fields
     *
     * @param opts options to copy, may be null
     */
    /** ignored */
    public ConnectionOptions(I2PSocketOptions opts) {
        super(opts);
        _smoothedRtt = DEFAULT_INITIAL_RTT;
        initFromProperties(System.getProperties());
    }

    /**
     * Create options as a deep copy of the given ConnectionOptions,
     * applying system property defaults then overlaying all local fields
     *
     * @param opts options to copy, may be null
     */
    /** ignored */
    public ConnectionOptions(ConnectionOptions opts) {
        super(opts);
        _smoothedRtt = DEFAULT_INITIAL_RTT;
        initFromProperties(System.getProperties());
        if (opts != null) {update(opts);}
    }

    /**
     * Copy all parent-class options then overlay ours from opts.
     *
     * @param opts source options to copy from, may be null
     */
    /** ignored */
    public void updateAll(ConnectionOptions opts) {
        setConnectTimeout(opts.getConnectTimeout());
        setReadTimeout(opts.getReadTimeout());
        setWriteTimeout(opts.getWriteTimeout());
        setMaxBufferSize(opts.getMaxBufferSize());
        setLocalPort(opts.getLocalPort());
        setPort(opts.getPort());
        update(opts);
    }

    /**
     * Copy all local (streaming-specific) options from opts
     *
     * @param opts source options to copy from, may be null
     */
    /** ignored */
    private void update(ConnectionOptions opts) {
            setMaxWindowSize(opts.getMaxWindowSize());
            setConnectDelay(opts.getConnectDelay());
            setProfile(opts.getProfile());
            setRTTDev(opts.getRTTDev());
            setRTT(opts.getRTT());
            setRequireFullySigned(opts.getRequireFullySigned());
            setWindowSize(opts.getWindowSize());
            setResendDelay(opts.getResendDelay());
            setMaxMessageSize(opts.getMaxMessageSize());
            setMaxResends(opts.getMaxResends());
            setInactivityTimeout(opts.getInactivityTimeout());
            setInactivityAction(opts.getInactivityAction());
            setInboundBufferSize(opts.getInboundBufferSize());
            setCongestionAvoidanceGrowthRateFactor(opts.getCongestionAvoidanceGrowthRateFactor());
            setSlowStartGrowthRateFactor(opts.getSlowStartGrowthRateFactor());
            setAnswerPings(opts.getAnswerPings());
            setEnforceProtocol(opts.getEnforceProtocol());
            setDisableRejectLogging(opts.getDisableRejectLogging());
            initLists(opts);
            _maxConnsPerMinute = opts.getMaxConnsPerMinute();
            _maxConnsPerHour = opts.getMaxConnsPerHour();
            _maxConnsPerDay = opts.getMaxConnsPerDay();
            _maxTotalConnsPerMinute = opts.getMaxTotalConnsPerMinute();
            _maxTotalConnsPerHour = opts.getMaxTotalConnsPerHour();
            _maxTotalConnsPerDay = opts.getMaxTotalConnsPerDay();
            _maxConns = opts.getMaxConns();
            _limitAction = opts.getLimitAction();
            _tagsToSend = opts.getTagsToSend();
            _tagThreshold = opts.getTagThreshold();
    }

    /**
     * Initialize from Properties with defaults (called from constructors).
     * Applies configured values or defaults for all properties.
     *
     * @param opts properties to initialize from, may be null
     */
    /** ignored */
    private void initFromProperties(Properties opts) {
        applyProperties(opts, false);
    }

    /**
     * Update from properties — only applies explicitly set values.
     * Also handles PROP_CONNECT_TIMEOUT not covered by applyProperties
     * (it's in the parent class's constructor path).
     *
     * @param opts properties to apply, may be null
     */
    @Override
    /** ignored */
    public void setProperties(Properties opts) {
        super.setProperties(opts);
        applyProperties(opts, true);
        if (opts != null && opts.getProperty(PROP_CONNECT_TIMEOUT) != null) {
            setConnectTimeout(getInt(opts, PROP_CONNECT_TIMEOUT, Connection.DEFAULT_CONNECT_TIMEOUT));
        }
    }

    /**
     * Apply properties from opts. When onlyIfSet is true, only properties
     * explicitly present in opts are applied (used by setProperties()).
     * When false, defaults are applied for missing properties (used by constructors).
     */
    /** ignored */
    private void applyProperties(Properties opts, boolean onlyIfSet) {
        if (opts == null) return;

        if (opts.getProperty(PROP_MAX_WINDOW_SIZE) != null) {
            int val = getInt(opts, PROP_MAX_WINDOW_SIZE, 0);
            if (val > 0) setMaxWindowSize(val);
        }
        applyInt(opts, PROP_CONNECT_DELAY, -1, onlyIfSet, this::setConnectDelay);
        applyInt(opts, PROP_PROFILE, PROFILE_BULK, onlyIfSet, this::setProfile);
        applyInt(opts, PROP_MAX_MESSAGE_SIZE, DEFAULT_MAX_MESSAGE_SIZE, onlyIfSet, this::setMaxMessageSize);
        applyInt(opts, PROP_INITIAL_RESEND_DELAY, _defaultRetransmitDelay, onlyIfSet, this::setResendDelay);
        applyInt(opts, PROP_INITIAL_ACK_DELAY, _defaultInitialAckDelay, onlyIfSet, this::setSendAckDelay);
        applyInt(opts, PROP_INITIAL_WINDOW_SIZE, _initialWindowSize, onlyIfSet, this::setWindowSize);
        applyInt(opts, PROP_MAX_RESENDS, DEFAULT_MAX_SENDS, onlyIfSet, this::setMaxResends);
        applyInt(opts, PROP_INACTIVITY_TIMEOUT, _defaultInactivityTimeout, onlyIfSet, this::setInactivityTimeout);
        applyInt(opts, PROP_INACTIVITY_ACTION, DEFAULT_INACTIVITY_ACTION, onlyIfSet, this::setInactivityAction);

        initializeInboundBufferSize();

        applyInt(opts, PROP_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR,
                 _defaultCongestionAvoidanceGrowthRateFactor, onlyIfSet,
                 this::setCongestionAvoidanceGrowthRateFactor);
        applyInt(opts, PROP_SLOW_START_GROWTH_RATE_FACTOR,
                 _defaultSlowStartGrowthRateFactor, onlyIfSet,
                 this::setSlowStartGrowthRateFactor);
        if (!onlyIfSet || opts.getProperty(PROP_ANSWER_PINGS) != null)
            setAnswerPings(getBool(opts, PROP_ANSWER_PINGS, DEFAULT_ANSWER_PINGS));
        if (!onlyIfSet || opts.getProperty(PROP_ENFORCE_PROTO) != null)
            setEnforceProtocol(getBool(opts, PROP_ENFORCE_PROTO, DEFAULT_ENFORCE_PROTO));
        if (!onlyIfSet || opts.getProperty(PROP_DISABLE_REJ_LOG) != null)
            setDisableRejectLogging(getBool(opts, PROP_DISABLE_REJ_LOG, false));

        initLists(opts);

        applyInt(opts, PROP_MAX_CONNS_MIN, 0, onlyIfSet, v -> _maxConnsPerMinute = v);
        applyInt(opts, PROP_MAX_CONNS_HOUR, 0, onlyIfSet, v -> _maxConnsPerHour = v);
        applyInt(opts, PROP_MAX_CONNS_DAY, 0, onlyIfSet, v -> _maxConnsPerDay = v);
        applyInt(opts, PROP_MAX_TOTAL_CONNS_MIN, 0, onlyIfSet, v -> _maxTotalConnsPerMinute = v);
        applyInt(opts, PROP_MAX_TOTAL_CONNS_HOUR, 0, onlyIfSet, v -> _maxTotalConnsPerHour = v);
        applyInt(opts, PROP_MAX_TOTAL_CONNS_DAY, 0, onlyIfSet, v -> _maxTotalConnsPerDay = v);
        applyInt(opts, PROP_MAX_STREAMS, 0, onlyIfSet, v -> _maxConns = v);

        if (!onlyIfSet || opts.getProperty(PROP_LIMIT_ACTION) != null) {
            _limitAction = opts.getProperty(PROP_LIMIT_ACTION, DEFAULT_LIMIT_ACTION);
        }
        applyInt(opts, PROP_TAGS_TO_SEND, DEFAULT_TAGS_TO_SEND, onlyIfSet, v -> _tagsToSend = v);
        applyInt(opts, PROP_TAG_THRESHOLD, DEFAULT_TAG_THRESHOLD, onlyIfSet, v -> _tagThreshold = v);

        synchronized(this) {
            _retransmitTimeout = getInt(opts, PROP_INITIAL_RTO, _defaultInitialRTO);
        }
    }

    /**
     * Apply an integer property: if onlyIfSet, skip when property key is absent.
     * Otherwise apply value (or default).
     *
     * @param opts properties to read from, may be null
     * @param key property key to look up
     * @param def default value if key is absent
     * @param onlyIfSet if true, skip when key is not present in opts
     * @param setter consumer to apply the parsed value
     */
    /** ignored */
    private void applyInt(Properties opts, String key, int def, boolean onlyIfSet, IntConsumer setter) {
        if (onlyIfSet && opts.getProperty(key) == null) return;
        setter.accept(getInt(opts, key, def));
    }

    /** @return delay before starting connection, in ms */
    public int getConnectDelay() {return _connectDelay;}
    /** @param delayMs delay before starting connection, in ms */
    public void setConnectDelay(int delayMs) {_connectDelay = delayMs;}

    /**
     * Sign all packets or only SYN/FIN? Unused — no property exists, always false.
     *
     * @return true if all packets should be signed, false for SYN/FIN only
     */
    /** ignored */
    public boolean getRequireFullySigned() {return _fullySigned;}
    /** @param sign true to require all packets signed */
    public void setRequireFullySigned(boolean sign) {_fullySigned = sign;}

    /** @return true if ping messages are answered */
    public boolean getAnswerPings() {return _answerPings;}
    /** @param yes true to respond to pings */
    public void setAnswerPings(boolean yes) {_answerPings = yes;}

    /**
     * If true, only accept traffic with I2PSession.PROTO_STREAMING (6).
     * Destinations before 0.7.1 (March 2009) lack this flag and will be rejected.
     * Set to true when running multiple protocols on a single Destination.
     *
     * @return true if protocol enforcement is enabled
     */
    /** ignored */
    public boolean getEnforceProtocol() {return _enforceProto;}
    /** @param yes true to enforce PROTO_STREAMING flag */
    public void setEnforceProtocol(boolean yes) {_enforceProto = yes;}

    /**
     * @since 0.9.4
     * @return true if connection reject logging is suppressed
     */
    /** ignored */
    public boolean getDisableRejectLogging() {return _disableRejectLog;}
    /** @param yes true to suppress reject log messages */
    public void setDisableRejectLogging(boolean yes) {_disableRejectLog = yes;}

    /**
     * Messages in flight before waiting for ACK
     *
     * @return current congestion window size in messages
     */
    /** ignored */
    public int getWindowSize() {return _windowSize;}

    /**
     * @param numMsgs clamped to [MIN_WINDOW_SIZE, maxWindowSize]
     */
    /** ignored */
    public void setWindowSize(int numMsgs) {
        if (numMsgs <= 0) {numMsgs = 1;}
        if (numMsgs < MIN_WINDOW_SIZE) {numMsgs = MIN_WINDOW_SIZE;}
        if (numMsgs > _maxWindowSize) {numMsgs = _maxWindowSize;}
        _windowSize = numMsgs;
    }

    /**
     * Smoothed round-trip time estimate in ms
     *
     * @return current SRTT value in ms
     */
    /** ignored */
    public synchronized int getRTT() {return _smoothedRtt;}

    /**
     * Minimum RTT observed, greater than zero
     *
     * @return minimum RTT in ms
     * @since 0.9.46
     */
    /** ignored */
    public synchronized int getMinRTT() {return _minRtt;}

    /**
     * Set smoothed RTT, clamped to maxRtt. Not public, use updateRTT().
     *
     * @param ms new RTT value in ms
     */
    /** ignored */
    private void setRTT(int ms) {
        synchronized(this) {
            _smoothedRtt = ms;
            if (_smoothedRtt > getMaxRtt()) {_smoothedRtt = getMaxRtt();}
        }
    }

    /**
     * Retransmit timeout, clamped to [minResendDelay, maxResendDelay]
     *
     * @return current RTO in ms
     */
    /** ignored */
    public synchronized int getRTO() {return _retransmitTimeout;}

    /**
     * RTT deviation for RTO calculation
     *
     * @return RTT variance in ms
     * @since 0.9.8
     */
    /** ignored */
    synchronized int getRTTDev() {return _rttDeviation;}

    /**
     * @param rttDev RTT deviation in ms
     */
    /** ignored */
    private synchronized void setRTTDev(int rttDev) {_rttDeviation = rttDev;}

    /**
     * Load cached RTT/deviation/window from TCB and transition directly to STEADY state
     *
     * @param rtt cached RTT value in ms
     * @param rttDev cached RTT deviation in ms
     * @param wdw cached window size
     */
    /** ignored */
    synchronized void loadFromCache(int rtt, int rttDev, int wdw) {
        _rttState = RttState.STEADY;
        setRTT(rtt);
        setRTTDev(rttDev);
        setWindowSize(wdw);
        computeRTO();
    }

    /** Recalculate RTO from current smoothed RTT and deviation per RFC 6298 */
    private synchronized void computeRTO() {
        switch(_rttState) {
        case INIT :
            throw new IllegalStateException();
        case FIRST :
            _retransmitTimeout = _smoothedRtt + _smoothedRtt / 2;
            break;
        case STEADY :
            _retransmitTimeout = _smoothedRtt + (int) (_rttDeviation * RTT_KAPPA);
            break;
        }

        int minRD = getMinResendDelay();
        int maxRD = getMaxResendDelay();
        if (_retransmitTimeout < minRD) {_retransmitTimeout = minRD;}
        else if (_retransmitTimeout > maxRD) {_retransmitTimeout = maxRD;}
    }

    /**
     * Double RTO after congestion per RFC 6298 sec. 5 item 5.5.
     * Guards against rapid double-fires from RetransmitEvent + ResendPacketEvent
     * by limiting to once per RTT.
     *
     * @return the new RTO value in ms
     */
    /** ignored */
    synchronized int doubleRTO() {
        long now = System.currentTimeMillis();
        if (now - _lastRtoDoubleTime < _smoothedRtt) {
            return _retransmitTimeout;
        }
        _lastRtoDoubleTime = now;
        _retransmitTimeout = _retransmitTimeout * _rtoMultiplier / 100;
        int mrto = getMaxRTO();
        if (_retransmitTimeout > mrto) {_retransmitTimeout = mrto;}
        return _retransmitTimeout;
    }

    /** @param measuredValue must be positive */
    public synchronized void updateRTT(int measuredValue) {
        _minRtt = Math.min(_minRtt, measuredValue);
        switch(_rttState) {
        case INIT:
            _rttState = RttState.FIRST;
            setRTT(measuredValue);
            _rttDeviation = _smoothedRtt / 2;
            break;
        case FIRST:
            _rttState = RttState.STEADY;
        case STEADY:
            _rttDeviation = Math.round((1-RTT_BETA) *_rttDeviation + RTT_BETA * Math.abs(measuredValue-_smoothedRtt));
            int smoothed = Math.round((1-RTT_ALPHA)*_smoothedRtt + RTT_ALPHA*measuredValue);
            setRTT(smoothed);
        }
        computeRTO();
    }

    /** Has at least one ACK been received? */
    public synchronized boolean receivedAck() {return _rttState != RttState.INIT;}

    /** Delay before retransmitting a packet in ms */
    public int getResendDelay() {return _retransmitDelay;}

    /**
     * setResendDelay.
     */
    /** ignored */
    public void setResendDelay(int ms) {
        int minRD = getMinResendDelay();
        int maxRD = getMaxResendDelay();
        _retransmitDelay = Math.max(minRD, Math.min(ms, maxRD));
    }

    /**
     * Delay before sending a forced ACK when no data packets arrive.
     * Ref: RFC 5681 sec. 4.3, RFC 1122 sec. 4.2.3.3, ticket #2706
     */
    /** ignored */
    public int getSendAckDelay() {return _ackDelay;}

    /**
     * Changing the default is not recommended.
     * Ref: RFC 5681 sec. 4.3, RFC 1122 sec. 4.2.3.3, ticket #2706
     */
    /** {_ackDelay */
    public void setSendAckDelay(int delayMs) {_ackDelay = Math.max(10, Math.min(delayMs, 500));}

    /** Maximum message size (MTU/MRU) */
    public int getMaxMessageSize() {return _maxMessageSize;}

    /**
     * setMaxMessageSize.
     */
    /** ignored */
    public void setMaxMessageSize(int bytes) {
        _maxMessageSize = Math.max(bytes, MIN_MESSAGE_SIZE);
        _maxInitialMessageSize = Math.min(_maxMessageSize, DEFAULT_MAX_MESSAGE_SIZE);
    }

    /** Largest message to send in SYN @since 0.9.47 */
    public int getMaxInitialMessageSize() {return _maxInitialMessageSize;}

    /** @since 0.9.47 */
    public void setMaxInitialMessageSize(int bytes) {
        _maxInitialMessageSize = bytes;
    }

    /** Connection profile. Only bulk is supported. @since 0.9.64 */
    public int getProfile() {return _profile;}
    /**
     * setProfile.
     */
    /** {_profile */
    public void setProfile(int profile) {_profile = profile;}

    /** Maximum retries per message */
    public int getMaxResends() {return _maxResends;}
    /**
     * setMaxResends.
     */
    /** {_maxResends */
    public void setMaxResends(int numSends) {_maxResends = Math.max(numSends, 0);}

    /** Inactivity timeout before action in ms */
    public int getInactivityTimeout() {return _inactivityTimeout;}
    /**
     * setInactivityTimeout.
     */
    /** {_inactivityTimeout */
    public void setInactivityTimeout(int timeout) {_inactivityTimeout = timeout;}

    /**
     * getInactivityAction.
     */
    /** ignored */
    public int getInactivityAction() {return _inactivityAction;}
    /**
     * setInactivityAction.
     */
    /** {_inactivityAction */
    public void setInactivityAction(int action) {_inactivityAction = action;}

    /**
     * @return per-connection cap if set, otherwise the Tuner-managed global ceiling
     */
    /** ignored */
    public int getMaxWindowSize() {
        if (_maxWindowSize > 0)
            return Math.min(_maxWindowSize, Connection.getGlobalMaxWindowSize());
        return Connection.getGlobalMaxWindowSize();
    }

    /**
     * A value of 0 or less resets to the Tuner-managed global default.
     * Clamped to [2, ABSOLUTE_MAX_WINDOW].
     */
    /** ignored */
    public void setMaxWindowSize(int msgs) {
        if (msgs <= 0) {
            _maxWindowSize = 0;
        } else if (msgs > Connection.ABSOLUTE_MAX_WINDOW) {
            _maxWindowSize = Connection.ABSOLUTE_MAX_WINDOW;
        } else if (msgs < 2) {
            _maxWindowSize = 2;
        } else {
            _maxWindowSize = msgs;
        }
    }

    /**
     * getInboundBufferSize.
     */
    /** ignored */
    public int getInboundBufferSize() {return _inboundBufferSize;}
    /**
     * setInboundBufferSize.
     */
    /** {_inboundBufferSize */
    public void setInboundBufferSize(int bytes) {_inboundBufferSize = bytes;}

    /** Maximum packets to buffer regardless of byte size (hybrid byte+packet limit) */
    public int getMaxPacketCount() {return _maxPacketCount;}

    /**
     * In congestion avoidance, window grows at 1/(windowSize*factor).
     * I2P uses messages vs TCP's bytes, so factor=maxMessageSize mimics TCP;
     * smaller factor grows faster.
     */
    /** ignored */
    public int getCongestionAvoidanceGrowthRateFactor() {return _congestionAvoidanceGrowthRateFactor;}
    /**
     * setCongestionAvoidanceGrowthRateFactor.
     */
    /** {_congestionAvoidanceGrowthRateFactor */
    public void setCongestionAvoidanceGrowthRateFactor(int factor) {_congestionAvoidanceGrowthRateFactor = factor;}

    /**
     * In slow start, window grows at 1/factor.
     * factor=maxMessageSize mimics TCP; smaller factor grows faster.
     */
    /** ignored */
    public int getSlowStartGrowthRateFactor() {return _slowStartGrowthRateFactor;}
    /**
     * setSlowStartGrowthRateFactor.
     */
    /** {_slowStartGrowthRateFactor */
    public void setSlowStartGrowthRateFactor(int factor) {_slowStartGrowthRateFactor = factor;}

    /** @since 0.7.14 no public setters */
    public int getMaxConnsPerMinute() {return _maxConnsPerMinute;}
    /**
     * getMaxConnsPerHour.
     */
    /** ignored */
    public int getMaxConnsPerHour() {return _maxConnsPerHour;}
    /**
     * getMaxConnsPerDay.
     */
    /** ignored */
    public int getMaxConnsPerDay() {return _maxConnsPerDay;}
    /**
     * getMaxTotalConnsPerMinute.
     */
    /** ignored */
    public int getMaxTotalConnsPerMinute() {return _maxTotalConnsPerMinute;}
    /**
     * getMaxTotalConnsPerHour.
     */
    /** ignored */
    public int getMaxTotalConnsPerHour() {return _maxTotalConnsPerHour;}
    /**
     * getMaxTotalConnsPerDay.
     */
    /** ignored */
    public int getMaxTotalConnsPerDay() {return _maxTotalConnsPerDay;}

    /** @since 0.9.3 no public setter */
    public int getMaxConns() {return _maxConns;}

    /**
     * isAccessListEnabled.
     */
    /** ignored */
    public boolean isAccessListEnabled() {return _accessListEnabled;}
    /**
     * isBlacklistEnabled.
     */
    /** ignored */
    public boolean isBlacklistEnabled() {return _blackListEnabled;}
    /**
     * getAccessList.
     */
    /** ignored */
    public Set<Hash> getAccessList() {return _accessList;}
    /**
     * getBlacklist.
     */
    /** ignored */
    public Set<Hash> getBlacklist() {return _blackList;}

    /** "reset", "drop", "http", or custom string; default "reset" @since 0.9.34 */
    public String getLimitAction() {return _limitAction;}

    /** Mostly handled on router side; PacketQueue needs to know for override limits @since 0.9.34 */
    public int getTagsToSend() {return _tagsToSend;}

    /** @since 0.9.34 */
    public int getTagThreshold() {return _tagThreshold;}

    /** ignored */
    private void initLists(ConnectionOptions opts) {
        _accessList = opts.getAccessList();
        _blackList = opts.getBlacklist();
        _accessListEnabled = opts.isAccessListEnabled();
        _blackListEnabled = opts.isBlacklistEnabled();
    }

    /** ignored */
    private void initLists(Properties opts) {
        boolean accessListEnabled = getBool(opts, PROP_ENABLE_ACCESS_LIST, false);
        boolean blackListEnabled = getBool(opts, PROP_ENABLE_BLACKLIST, false);
        Set<Hash> accessList;
        Set<Hash> blackList;
        if (accessListEnabled) {accessList = new HashSet<>();}
        else {accessList = Collections.emptySet();}
        if (blackListEnabled) {blackList = new HashSet<>();}
        else {blackList = Collections.emptySet();}
        if (accessListEnabled || blackListEnabled) {
            String hashes = opts.getProperty(PROP_ACCESS_LIST);
            if (hashes == null) {return;}
            StringTokenizer tok = new StringTokenizer(hashes, ",; ");
            while (tok.hasMoreTokens()) {
                String hashstr = tok.nextToken();
                Hash h = ConvertToHash.getHash(hashstr);
                if (h == null) {error("Invalid entry in access list: " + hashstr);}
                else if (blackListEnabled) {blackList.add(h);}
                else {accessList.add(h);}
            }
        }
        _accessList = accessList;
        _blackList = blackList;
        _accessListEnabled = accessListEnabled;
        _blackListEnabled = blackListEnabled;
        if (_accessListEnabled && _accessList.isEmpty()) {
            error("Connection access list enabled but no valid entries; no peers can connect");
        } else if (_blackListEnabled && _blackList.isEmpty()) {
            error("Connection blacklist enabled but no valid entries; all peers can connect");
        }
    }

    /** ignored */
    private static void error(String s) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        Log log = ctx.logManager().getLog(ConnectionOptions.class);
        log.error(s);
    }

    /**
     * toString.
     */
    @Override
    /** ignored */
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("\n*");
        if (_connectDelay > 0) {buf.append(" conDelay=").append(_connectDelay);}
        buf.append(" maxSize=").append(_maxMessageSize);
        buf.append(" rtt=").append(_smoothedRtt);
        buf.append(" resendDelay=").append(_retransmitDelay);
        buf.append(" ackDelay=").append(_ackDelay);
        buf.append(" cwin=").append(_windowSize);
        buf.append(" maxResends=").append(_maxResends);
        buf.append("\n* writeTimeout=").append(getWriteTimeout());
        buf.append(" readTimeout=").append(getReadTimeout());
        if (_inactivityTimeout > 0) {buf.append(" inactivityTimeout=").append(_inactivityTimeout);}
        buf.append(" inboundBuffer=").append(_inboundBufferSize);
        buf.append(" maxWindowSize=").append(_maxWindowSize);
        if (_maxConnsPerMinute > 0 || _maxConnsPerHour > 0 || _maxConnsPerDay > 0) {
            buf.append(" maxConns=").append(_maxConnsPerMinute).append('/')
                                    .append(_maxConnsPerHour).append('/')
                                    .append(_maxConnsPerDay);
        }
        if (_maxTotalConnsPerMinute > 0 || _maxTotalConnsPerHour > 0 || _maxTotalConnsPerDay > 0) {
            buf.append(" maxTotalConns=").append(_maxTotalConnsPerMinute).append('/')
                                         .append(_maxTotalConnsPerHour).append('/')
                                         .append(_maxTotalConnsPerDay);
        }
        return buf.toString();
    }

    /** Calculate min inbound buffer to accommodate full window: 1.5*maxWindowSize + 2 */
    private static final int MAX_INBOUND_BUFFER = 8 * 1024 * 1024;

    /** ignored */
    private void initializeInboundBufferSize() {
        int minRequiredBufferSize = getMaxMessageSize() * ((3 * getMaxWindowSize()) / 2 + 2);
        setInboundBufferSize(Math.min(minRequiredBufferSize, MAX_INBOUND_BUFFER));
    }

    /** ignored */
    private static boolean getBool(Properties opts, String name, boolean defaultVal) {
        if (opts == null) return defaultVal;
        String val = opts.getProperty(name);
        if (val == null)  return defaultVal;
        return Boolean.parseBoolean(val);
    }
}
