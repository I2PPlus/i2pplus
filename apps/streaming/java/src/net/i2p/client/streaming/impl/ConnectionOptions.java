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
class ConnectionOptions extends I2PSocketOptionsImpl {
    /** Connect delay. */
    private int _connectDelay;
    /** Fully signed. */
    private boolean _fullySigned;
    /** Answer pings. */
    private boolean _answerPings;
    /** Window size. */
    private volatile int _windowSize;
    /** Connection traffic profile (only bulk supported). */
    private int _profile;
    /** Smoothed rtt. */
    private int _smoothedRtt;
    /** Min rtt. */
    private int _minRtt = DEFAULT_INITIAL_RTT;
    /** Rtt deviation. */
    private int _rttDeviation;
    /** Retransmit timeout. */
    private int _retransmitTimeout = defaultInitialRTO;
    /** Retransmit delay. */
    private int _retransmitDelay;
    /** Ack delay. */
    private int _ackDelay;
    /** Max message size. */
    private int _maxMessageSize;
    /** Max initial message size. */
    private int _maxInitialMessageSize;
    /** Max resends. */
    private int _maxResends;
    /** Inactivity timeout. */
    private int _inactivityTimeout;
    /** Inactivity action. */
    private int _inactivityAction;
    /** Inbound buffer size. */
    private volatile int _inboundBufferSize;
    /** Max window size. */
    private volatile int _maxWindowSize;
    /** Congestion avoidance growth rate factor. */
    private volatile int _congestionAvoidanceGrowthRateFactor;
    /** Slow start growth rate factor. */
    private volatile int _slowStartGrowthRateFactor;
    /** Access list enabled. */
    private volatile boolean _accessListEnabled;
    /** Black list enabled. */
    private volatile boolean _blackListEnabled;
    /** Access list. */
    private Set<Hash> _accessList;
    /** Black list. */
    private Set<Hash> _blackList;
    /** Max conns per minute. */
    private volatile int _maxConnsPerMinute;
    /** Max conns per hour. */
    private volatile int _maxConnsPerHour;
    /** Max conns per day. */
    private volatile int _maxConnsPerDay;
    /** Max total conns per minute. */
    private volatile int _maxTotalConnsPerMinute;
    /** Max total conns per hour. */
    private volatile int _maxTotalConnsPerHour;
    /** Max total conns per day. */
    private volatile int _maxTotalConnsPerDay;
    /** Per-connection passive flush delay; <= 0 means use Tuner-managed global default. */
    private volatile int _passiveFlushDelay;
    /** Max conns. */
    private volatile int _maxConns;
    /** Disable reject log. */
    private volatile boolean _disableRejectLog;
    /** Limit action. */
    private volatile String _limitAction;
    /** Tags to send. */
    private volatile int _tagsToSend;
    /** Tag threshold. */
    private volatile int _tagThreshold;

    /** Prevents RTO overshoot from rapid RetransmitEvent + ResendPacketEvent double-fires */
    private long _lastRtoDoubleTime;

    /** Hybrid byte+packet buffer limit; default 1024 for optimal performance */
    private volatile int _maxPacketCount = 1024;

    /** RTT initialization state machine */
    private enum RttState {
        INIT, FIRST, STEADY
    }

    /** Synchronizes access to the RTT state. */
    private RttState _rttState = RttState.INIT;

    /** No action taken when inactivity timeout fires */
    public static final int INACTIVITY_ACTION_NOOP = 0;
    /** Disconnect the connection when inactivity timeout fires */
    public static final int INACTIVITY_ACTION_DISCONNECT = 1;
    /** Send a keepalive when inactivity timeout fires */
    public static final int INACTIVITY_ACTION_SEND = 2;

    /** RFC 6298 RTT smoothing constants */
    private static final float RTT_ALPHA = 1.0f/8;
    /** Rtt beta. */
    private static final float RTT_BETA = 1.0f/4;
    /** Rtt kappa. */
    private static final float RTT_KAPPA = 4;

    /** Prop initial rto. */
    private static final String PROP_INITIAL_RTO = "i2p.streaming.initialRTO";
    /** Prop max rto. */
    static final String PROP_MAX_RTO = "i2p.streaming.maxRTO";

    /**
     * Default initial RTO (ms) before any RTT measurement. Set to 5000 — accommodates
     * typical I2P RTT up to ~3s without premature SYN retransmit. The Tuner adjusts
     * this adaptively based on network conditions.
     * @since 0.9.70+ mutable for adaptive tuning
     */
    private static volatile int defaultInitialRTO = 5000;

    /** Initial rto. */
    static int getInitialRTO() { return defaultInitialRTO; }
    /** Initial rto. */
    static void setInitialRTO(int val) { defaultInitialRTO = Math.max(500, Math.min(30000, val)); }

    /**
     * Default 30000 accommodates RTT up to ~15s with standard TCP deviation.
     * Tuner may raise this for very high-latency networks.
     */
    private static volatile int maxRTO = 30000;

    /** @since 0.9.70+ */
    public static int getMaxRTOStatic() { return maxRTO; }
    /** @since 0.9.70+ */
    public static void setMaxRTO(int val) { maxRTO = Math.max(1000, Math.min(60000, val)); }

    /** RTO multiplier as percentage (e.g. 150 = 1.5x), clamped to [100, 500] */
    static final String PROP_RTO_MULTIPLIER = "i2p.streaming.rtoMultiplier";

    /** @since 0.9.70+ mutable for adaptive tuning */
    private static volatile int rtoMultiplier = 150;

    /** @since 0.9.70+ */
    static int getRTOMultiplier() { return rtoMultiplier; }
    /** @since 0.9.70+ */
    static void setRTOMultiplier(int val) { rtoMultiplier = Math.max(100, Math.min(500, val)); }

    /** Min resend delay. */
    private static volatile int minResendDelay = 300;

    /** @since 0.9.70+ */
    public static int getMinResendDelayStatic() { return minResendDelay; }
    /** @since 0.9.70+ */
    public static void setMinResendDelay(int val) { minResendDelay = Math.max(300, Math.min(5000, val)); }

    /**
     * Max resend delay. Raised to 30000 to accommodate high-RTT paths without premature retransmit.
     */
    private static volatile int maxResendDelay = 30000;

    /** @since 0.9.70+ */
    public static int getMaxResendDelayStatic() { return maxResendDelay; }
    /** @since 0.9.70+ */
    public static void setMaxResendDelay(int val) { maxResendDelay = Math.max(1000, Math.min(60000, val)); }

    /** Max rto. */
    private int getMaxRTO() { return maxRTO; }
    /** Min resend delay. */
    private int getMinResendDelay() { return minResendDelay; }
    /** Max resend delay. */
    private int getMaxResendDelay() { return maxResendDelay; }

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

    /** @since 0.9.3 moved from I2PSocketManagerFull */
    public static final String PROP_MAX_STREAMS = "i2p.streaming.maxConcurrentStreams";

    /**
     *  Operator-tunable ceiling for the Tuner's {@code maxConcurrentStreamsOverride}.
     *  The effective cap taken by a manager is the min of its configured value
     *  ({@link #PROP_MAX_STREAMS}) and the override, so this only bounds the Tuner;
     *  it can never raise a manager above its own per-tunnel ceiling.
     *  @since 0.9.71+
     */
    public static final String PROP_MAX_MAX_STREAMS = "i2p.streaming.maxMaxConcurrentStreams";
    /** Default ceiling; operator may raise via PROP_MAX_MAX_STREAMS. @since 0.9.71+ */
    static volatile int maxMaxConcurrentStreams = 1024;

    /** @since 0.9.4 default false */
    public static final String PROP_DISABLE_REJ_LOG = "i2p.streaming.disableRejectLogging";

    /** Reset, drop, http, or custom string; default reset @since 0.9.34 */
    public static final String PROP_LIMIT_ACTION = "i2p.streaming.limitAction";

    /** @since 0.9.34 */
    public static final String PROP_TAGS_TO_SEND = "crypto.tagsToSend";

    /** @since 0.9.34 */
    public static final String PROP_TAG_THRESHOLD = "crypto.lowTagThreshold";

    /**
     * Passive flush delay in ms for MessageOutputStream. When set to 0 (default),
     * uses the Tuner-managed global default. When profile is INTERACTIVE and no
     * explicit value is set, defaults to half the Tuner global (min 10).
     * @since 0.9.70+
     */
    public static final String PROP_PASSIVE_FLUSH_DELAY = "i2p.streaming.passiveFlushDelay";

    /**
     * RFC 6928 recommends 10; increased to 16 for I2P's high-latency environment.
     * @since 0.9.70+ mutable for adaptive tuning
     */
    static volatile int initialWindowSize = 64;

    /** Default maximum number of times a single message will be retransmitted */
    static final int DEFAULT_MAX_SENDS = 30;

    /** Initial window size. */
    static int getInitialWindowSize() { return initialWindowSize; }
    /** Initial window size. */
    static void setInitialWindowSize(int val) { initialWindowSize = Math.max(4, Math.min(256, val)); }

    /**
     *  Reactive cap on concurrent streams (both per-manager inbound budget and the
     *  outbound wait queue), written by the router's Tuner under saturation. Applies
     *  to every ConnectionManager. 0 (the default) means "no override": each manager
     *  uses its own configured {@code i2p.streaming.maxConcurrentStreams} captured at
     *  init. When positive, a manager enforces the {@code min(configured, override)}.
     *  @since 0.9.71+
     */
    static volatile int maxConcurrentStreamsOverride = 0;

    /** The Tuner's stream-cap override; 0 = none. @since 0.9.71+ */
    static int getMaxConcurrentStreamsOverride() { return maxConcurrentStreamsOverride; }
    /** The Tuner's stream-cap override; clamped to a sane [0, maxMaxConcurrentStreams]. @since 0.9.71+ */
    static void setMaxConcurrentStreamsOverride(int val) {
        maxConcurrentStreamsOverride = Math.max(0, Math.min(maxMaxConcurrentStreams, val));
    }

    /**
     *  Operator-tunable ceiling for the Tuner's stream-cap override.
     *  Reads from router.config via {@code i2p.streaming.maxMaxConcurrentStreams},
     *  falling back to the compiled default.
     *  @return the current ceiling
     *  @since 0.9.71+
     */
    static int getMaxMaxConcurrentStreams() {
        return I2PAppContext.getGlobalContext().getProperty(PROP_MAX_MAX_STREAMS, maxMaxConcurrentStreams);
    }
    /**
     *  Operator-tunable ceiling for the Tuner's stream-cap override; clamped to [64, 8192].
     *  @since 0.9.71+
     */
    static void setMaxMaxConcurrentStreams(int val) { maxMaxConcurrentStreams = Math.max(64, Math.min(8192, val)); }

    /**
     * Initial RTT estimate before first measurement.
     * I2P typically has 2-10s RTT, so 5s provides a conservative starting point.
     */
    public static final int DEFAULT_INITIAL_RTT = 2*1000;

    /** Max RTT to prevent pathological RTO cases. Tunable via i2p.streaming.maxRtt (default 10000). */
    private static volatile int maxRTT = 10*1000;

    /** Max rtt. */
    private int getMaxRtt() { return maxRTT; }
    /**
     * Max RTT, clamped to [1000, 60000] ms.
     */
    public static void setMaxRtt(int val) { maxRTT = Math.max(1000, Math.min(60000, val)); }
    /**
     * Max RTT.
     * @return the max rtt static
     */
    public static int getMaxRttStatic() { return maxRTT; }

    /**
     * 500ms provides a reasonable delayed-ACK window for I2P's high-latency,
     * allowing data piggybacking and reducing ACK-only packet floods.
     * Ref: RFC 5681 sec. 4.3, RFC 1122 sec. 4.2.3.3, ticket #2706
     * @since 0.9.70+ mutable for adaptive tuning
     */
    private static volatile int defaultInitialAckDelay = 500;

    /**
     * Default initial ACK delay.
     * @return the default initial ack delay
     */
    public static int getDefaultInitialAckDelay() { return defaultInitialAckDelay; }
    /**
     * Default initial ACK delay, clamped to [10, 500] ms.
     */
    public static void setDefaultInitialAckDelay(int val) { defaultInitialAckDelay = Math.max(10, Math.min(500, val)); }

    /**
     * Default inactivity timeout.
     * @return the default inactivity timeout
     */
    public static int getDefaultInactivityTimeout() { return defaultInactivityTimeout; }
    /**
     * Default inactivity timeout, clamped to [60000, 600000] ms.
     */
    public static void setDefaultInactivityTimeout(int val) { defaultInactivityTimeout = Math.max(60000, Math.min(600000, val)); }

    /** Minimum congestion window size (one message in flight) */
    static final int MIN_WINDOW_SIZE = 1;
    /** Default answer pings. */
    private static final boolean DEFAULT_ANSWER_PINGS = true;

    /** @since 0.9.70+ mutable for adaptive tuning */
    static volatile int defaultInactivityTimeout = 300000;

    /** Default inactivity action. */
    private static final int DEFAULT_INACTIVITY_ACTION = INACTIVITY_ACTION_SEND;

    /** @since 0.9.70+ mutable for adaptive tuning */
    static volatile int maxSlowStartWindow = SystemVersion.isSlow() ? 32 : 256;

    /** Max slow start window static. */
    static int getMaxSlowStartWindowStatic() { return maxSlowStartWindow; }
    /** Max slow start window. */
    static void setMaxSlowStartWindow(int val) { maxSlowStartWindow = Math.max(8, Math.min(Connection.ABSOLUTE_MAX_WINDOW, val)); }

    /** Immediate ack delay. */
    private static volatile int immediateAckDelay = SystemVersion.isSlow() ? 100 : 80;

    /** Immediate ack delay static. */
    static int getImmediateAckDelayStatic() { return immediateAckDelay; }
    /** Immediate ack delay. */
    static void setImmediateAckDelay(int val) { immediateAckDelay = Math.max(1, Math.min(1000, val)); }

    /** @since 0.9.70+ mutable for adaptive tuning */
    private static volatile int defaultRetransmitDelay = 1000;

    /**
     * Default resend delay.
     * @return the default resend delay static
     */
    public static int getDefaultResendDelayStatic() { return defaultRetransmitDelay; }
    /**
     * Default resend delay, clamped to [100, 3000] ms.
     */
    public static void setDefaultResendDelay(int val) { defaultRetransmitDelay = Math.max(100, Math.min(3000, val)); }

    /** @since 0.9.70+ mutable for adaptive tuning */
    private static volatile int defaultCongestionAvoidanceGrowthRateFactor = 1;

    /**
     * Default congestion avoidance growth rate factor.
     * @return the default congestion avoidance growth rate factor static
     */
    public static int getDefaultCongestionAvoidanceGrowthRateFactorStatic() { return defaultCongestionAvoidanceGrowthRateFactor; }
    /**
     * Default congestion avoidance growth rate factor, clamped to [1, 4].
     */
    public static void setDefaultCongestionAvoidanceGrowthRateFactor(int val) { defaultCongestionAvoidanceGrowthRateFactor = Math.max(1, Math.min(4, val)); }

    /** @since 0.9.70+ mutable for adaptive tuning */
    private static volatile int defaultSlowStartGrowthRateFactor = 2;

    /**
     * Default slow start growth rate factor.
     * @return the default slow start growth rate factor static
     */
    public static int getDefaultSlowStartGrowthRateFactorStatic() { return defaultSlowStartGrowthRateFactor; }
    /**
     * Default slow start growth rate factor, clamped to [1, 4].
     */
    public static void setDefaultSlowStartGrowthRateFactor(int val) { defaultSlowStartGrowthRateFactor = Math.max(1, Math.min(4, val)); }

    /**
     * Minimum pacing rate. Below this, pacing is disabled to avoid excessive delays.
     * Default 16 KB/s, tunable via Tuner.
     */
    private static volatile long minPacingRate = 16 * 1024;

    /**
     * Min pacing rate.
     * @return the min pacing rate
     */
    public static long getMinPacingRate() { return minPacingRate; }
    /**
     * Min pacing rate, clamped to [1024, 256 KB/s].
     */
    public static void setMinPacingRate(long val) { minPacingRate = Math.max(1024, Math.min(256 * 1024, val)); }

    /** @since 0.9.70+ */
    public static int getMinPacingRateKBps() { return (int) (minPacingRate / 1024); }

    /** KB/s wrapper for Tuner int reflection */
    public static void setMinPacingRateKBps(int val) { minPacingRate = Math.max(1024, Math.min(256 * 1024, (long) val * 1024)); }

    /** @since 0.9.34 */
    private static final String DEFAULT_LIMIT_ACTION = "reset";

    /** @since 0.9.34 */
    public static final int DEFAULT_TAGS_TO_SEND = 40;

    /** @since 0.9.34 */
    public static final int DEFAULT_TAG_THRESHOLD = 30;

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
    private void update(ConnectionOptions opts) {
            setMaxWindowSize(opts.getMaxWindowSize());
            setConnectDelay(opts.getConnectDelay());
            setProfile(opts.getProfile());
            setPassiveFlushDelay(opts.getPassiveFlushDelay());
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
    private void initFromProperties(Properties opts) {
        applyProperties(opts, false);
        if (_passiveFlushDelay <= 0 && _profile == PROFILE_INTERACTIVE) {
            _passiveFlushDelay = Math.max(10, MessageOutputStream.getDefaultPassiveFlushDelay() / 2);
        }
    }

    /**
     * Update from properties — only applies explicitly set values.
     * Also handles PROP_CONNECT_TIMEOUT not covered by applyProperties
     * (it's in the parent class's constructor path).
     *
     * @param opts properties to apply, may be null
     */
    @Override
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
    private void applyProperties(Properties opts, boolean onlyIfSet) {
        if (opts == null) return;

        if (opts.getProperty(PROP_MAX_WINDOW_SIZE) != null) {
            int val = getInt(opts, PROP_MAX_WINDOW_SIZE, 0);
            if (val > 0) setMaxWindowSize(val);
        }
        applyInt(opts, PROP_CONNECT_DELAY, -1, onlyIfSet, this::setConnectDelay);
        applyInt(opts, PROP_PROFILE, PROFILE_BULK, onlyIfSet, this::setProfile);
        applyInt(opts, PROP_MAX_MESSAGE_SIZE, DEFAULT_MAX_MESSAGE_SIZE, onlyIfSet, this::setMaxMessageSize);
        applyInt(opts, PROP_INITIAL_RESEND_DELAY, defaultRetransmitDelay, onlyIfSet, this::setResendDelay);
        applyInt(opts, PROP_INITIAL_ACK_DELAY, defaultInitialAckDelay, onlyIfSet, this::setSendAckDelay);
        applyInt(opts, PROP_INITIAL_WINDOW_SIZE, initialWindowSize, onlyIfSet, this::setWindowSize);
        applyInt(opts, PROP_MAX_RESENDS, DEFAULT_MAX_SENDS, onlyIfSet, this::setMaxResends);
        applyInt(opts, PROP_INACTIVITY_TIMEOUT, defaultInactivityTimeout, onlyIfSet, this::setInactivityTimeout);
        applyInt(opts, PROP_INACTIVITY_ACTION, DEFAULT_INACTIVITY_ACTION, onlyIfSet, this::setInactivityAction);

        initializeInboundBufferSize();

        applyInt(opts, PROP_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR,
                 defaultCongestionAvoidanceGrowthRateFactor, onlyIfSet,
                 this::setCongestionAvoidanceGrowthRateFactor);
        applyInt(opts, PROP_SLOW_START_GROWTH_RATE_FACTOR,
                 defaultSlowStartGrowthRateFactor, onlyIfSet,
                 this::setSlowStartGrowthRateFactor);
        applyInt(opts, PROP_PASSIVE_FLUSH_DELAY, 0, onlyIfSet, this::setPassiveFlushDelay);

        if (!onlyIfSet || opts.getProperty(PROP_ANSWER_PINGS) != null)
            setAnswerPings(getBool(opts, PROP_ANSWER_PINGS, DEFAULT_ANSWER_PINGS));
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
            _retransmitTimeout = getInt(opts, PROP_INITIAL_RTO, defaultInitialRTO);
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
    private void applyInt(Properties opts, String key, int def, boolean onlyIfSet, IntConsumer setter) {
        if (onlyIfSet && opts.getProperty(key) == null) return;
        setter.accept(getInt(opts, key, def));
    }

    /**
     * Delay before starting the connection.
     * @return delay before starting connection, in ms
     */
    public int getConnectDelay() {return _connectDelay;}
    /**
     * Delay before starting the connection.
     * @param delayMs delay before starting connection, in ms
     */
    public void setConnectDelay(int delayMs) {_connectDelay = delayMs;}

    /**
     * Sign all packets or only SYN/FIN? Unused — no property exists, always false.
     *
     * @return true if all packets should be signed, false for SYN/FIN only
     */
    public boolean getRequireFullySigned() {return _fullySigned;}
    /**
     * Whether all packets require signing.
     * @param sign true to require all packets signed
     */
    public void setRequireFullySigned(boolean sign) {_fullySigned = sign;}

    /**
     * Whether ping messages are answered.
     * @return true if ping messages are answered
     */
    public boolean getAnswerPings() {return _answerPings;}
    /**
     * Whether ping messages are answered.
     * @param yes true to respond to pings
     */
    public void setAnswerPings(boolean yes) {_answerPings = yes;}

    /**
     * Whether connection reject logging is suppressed.
     * @return true if connection reject logging is suppressed
     * @since 0.9.4
     */
    public boolean getDisableRejectLogging() {return _disableRejectLog;}
    /**
     * Whether connection reject logging is suppressed.
     * @param yes true to suppress reject log messages
     */
    public void setDisableRejectLogging(boolean yes) {_disableRejectLog = yes;}

    /**
     * Messages in flight before waiting for ACK
     *
     * @return current congestion window size in messages
     */
    public int getWindowSize() {return _windowSize;}

    /**
     * Congestion window size in messages.
     * @param numMsgs clamped to [MIN_WINDOW_SIZE, maxWindowSize]
     */
    public void setWindowSize(int numMsgs) {
        if (numMsgs <= 0) {numMsgs = 1;}
        if (numMsgs < MIN_WINDOW_SIZE) {numMsgs = MIN_WINDOW_SIZE;}
        int maxWin = getMaxWindowSize();
        if (numMsgs > maxWin) {numMsgs = maxWin;}
        _windowSize = numMsgs;
    }

    /**
     * Smoothed round-trip time estimate in ms
     *
     * @return current SRTT value in ms
     */
    public synchronized int getRTT() {return _smoothedRtt;}

    /**
     * Minimum RTT observed, greater than zero
     *
     * @return minimum RTT in ms
     * @since 0.9.46
     */
    public synchronized int getMinRTT() {return _minRtt;}

    /**
     * Smoothed RTT, clamped to maxRtt. Not public, use updateRTT().
     *
     * @param ms new RTT value in ms
     */
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
    public synchronized int getRTO() {return _retransmitTimeout;}

    /**
     * RTT deviation for RTO calculation
     *
     * @return RTT variance in ms
     * @since 0.9.8
     */
    synchronized int getRTTDev() {return _rttDeviation;}

    /**
     * RTT deviation for RTO calculation.
     * @param rttDev RTT deviation in ms
     */
    private synchronized void setRTTDev(int rttDev) {_rttDeviation = rttDev;}

    /**
     * Load cached RTT/deviation/window from TCB and transition directly to STEADY state
     *
     * @param rtt cached RTT value in ms
     * @param rttDev cached RTT deviation in ms
     * @param wdw cached window size
     */
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
    synchronized int doubleRTO() {
        long now = System.nanoTime();
        if (_lastRtoDoubleTime != 0 &&
            now - _lastRtoDoubleTime < _smoothedRtt * 1_000_000L) {
            return _retransmitTimeout;
        }
        _lastRtoDoubleTime = now;
        _retransmitTimeout = _retransmitTimeout * rtoMultiplier / 100;
        int mrto = getMaxRTO();
        if (_retransmitTimeout > mrto) {_retransmitTimeout = mrto;}
        return _retransmitTimeout;
    }

    /**
     * Update the smoothed RTT with a new measurement.
     * @param measuredValue must be positive
     */
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
            // fallthrough
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
     * Resend delay, clamped to [minResendDelay, maxResendDelay].
     */
    public void setResendDelay(int ms) {
        int minRD = getMinResendDelay();
        int maxRD = getMaxResendDelay();
        _retransmitDelay = Math.max(minRD, Math.min(ms, maxRD));
    }

    /**
     * Delay before sending a forced ACK when no data packets arrive.
     * Ref: RFC 5681 sec. 4.3, RFC 1122 sec. 4.2.3.3, ticket #2706
     * @return the send ack delay
     */
    public int getSendAckDelay() {return _ackDelay;}

    /**
     * Changing the default is not recommended.
     * Ref: RFC 5681 sec. 4.3, RFC 1122 sec. 4.2.3.3, ticket #2706
     */
    public void setSendAckDelay(int delayMs) {_ackDelay = Math.max(10, Math.min(delayMs, 500));}

    /** Maximum message size (MTU/MRU) */
    public int getMaxMessageSize() {return _maxMessageSize;}

    /**
     * Maximum message size, floored at MIN_MESSAGE_SIZE.
     */
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
     * Connection profile.
     */
    public void setProfile(int profile) {_profile = profile;}

    /**
     * Effective passive flush delay in ms.
     * @return effective passive flush delay in ms: explicit value if set,
     *         otherwise the Tuner-managed global default
     */
    public int getPassiveFlushDelay() {
        return _passiveFlushDelay > 0 ? _passiveFlushDelay : MessageOutputStream.getDefaultPassiveFlushDelay();
    }
    /**
     * Passive flush delay for the output stream.
     * @param delayMs 0 to use Tuner-managed global default, positive for explicit delay
     */
    public void setPassiveFlushDelay(int delayMs) { _passiveFlushDelay = delayMs; }

    /** Maximum retries per message */
    public int getMaxResends() {return _maxResends;}
    /**
     * Maximum retries per message.
     */
    public void setMaxResends(int numSends) {_maxResends = Math.max(numSends, 0);}

    /** Inactivity timeout before action in ms */
    public int getInactivityTimeout() {return _inactivityTimeout;}
    /**
     * Inactivity timeout before action.
     */
    public void setInactivityTimeout(int timeout) {_inactivityTimeout = timeout;}

    /**
     * Action taken when the inactivity timeout fires.
     * @return the inactivity action
     */
    public int getInactivityAction() {return _inactivityAction;}
    /**
     * Action taken when the inactivity timeout fires.
     */
    public void setInactivityAction(int action) {_inactivityAction = action;}

    /**
     * Maximum window size cap.
     * @return per-connection cap if set, otherwise the Tuner-managed global ceiling
     */
    public int getMaxWindowSize() {
        if (_maxWindowSize > 0)
            return Math.min(_maxWindowSize, Connection.getGlobalMaxWindowSize());
        return Connection.getGlobalMaxWindowSize();
    }

    /**
     * A value of 0 or less resets to the Tuner-managed global default.
     * Clamped to [2, ABSOLUTE_MAX_WINDOW].
     */
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
     * Inbound buffer size.
     * @return the inbound buffer size
     */
    public int getInboundBufferSize() {return _inboundBufferSize;}
    /**
     * Inbound buffer size in bytes.
     */
    public void setInboundBufferSize(int bytes) {_inboundBufferSize = bytes;}

    /** Maximum packets to buffer regardless of byte size (hybrid byte+packet limit) */
    public int getMaxPacketCount() {return _maxPacketCount;}

    /**
     * In congestion avoidance, window grows at 1/(windowSize*factor).
     * I2P uses messages vs TCP's bytes, so factor=maxMessageSize mimics TCP;
     * smaller factor grows faster.
     * @return the congestion avoidance growth rate factor
     */
    public int getCongestionAvoidanceGrowthRateFactor() {return _congestionAvoidanceGrowthRateFactor;}
    /**
     * Congestion avoidance growth rate factor.
     */
    public void setCongestionAvoidanceGrowthRateFactor(int factor) {_congestionAvoidanceGrowthRateFactor = factor;}

    /**
     * In slow start, window grows at 1/factor.
     * factor=maxMessageSize mimics TCP; smaller factor grows faster.
     * @return the slow start growth rate factor
     */
    public int getSlowStartGrowthRateFactor() {return _slowStartGrowthRateFactor;}
    /**
     * Slow start growth rate factor.
     */
    public void setSlowStartGrowthRateFactor(int factor) {_slowStartGrowthRateFactor = factor;}

    /** @since 0.7.14 no public setters */
    public int getMaxConnsPerMinute() {return _maxConnsPerMinute;}
    /**
     * Max connections per hour.
     * @return the max conns per hour
     */
    public int getMaxConnsPerHour() {return _maxConnsPerHour;}
    /**
     * Max connections per day.
     * @return the max conns per day
     */
    public int getMaxConnsPerDay() {return _maxConnsPerDay;}
    /**
     * Max total connections per minute.
     * @return the max total conns per minute
     */
    public int getMaxTotalConnsPerMinute() {return _maxTotalConnsPerMinute;}
    /**
     * Max total connections per hour.
     * @return the max total conns per hour
     */
    public int getMaxTotalConnsPerHour() {return _maxTotalConnsPerHour;}
    /**
     * Max total connections per day.
     * @return the max total conns per day
     */
    public int getMaxTotalConnsPerDay() {return _maxTotalConnsPerDay;}

    /** @since 0.9.3 no public setter */
    public int getMaxConns() {return _maxConns;}

    /**
     * Whether the access list is enabled.
     * @return whether access list enabled
     */
    public boolean isAccessListEnabled() {return _accessListEnabled;}
    /**
     * Whether the blacklist is enabled.
     * @return whether blacklist enabled
     */
    public boolean isBlacklistEnabled() {return _blackListEnabled;}
    /**
     * Access list.
     * @return the access list
     */
    public Set<Hash> getAccessList() {return _accessList;}
    /**
     * Blacklist.
     * @return the blacklist
     */
    public Set<Hash> getBlacklist() {return _blackList;}

    /** "reset", "drop", "http", or custom string; default "reset" @since 0.9.34 */
    public String getLimitAction() {return _limitAction;}

    /** Mostly handled on router side; PacketQueue needs to know for override limits @since 0.9.34 */
    public int getTagsToSend() {return _tagsToSend;}

    /** @since 0.9.34 */
    public int getTagThreshold() {return _tagThreshold;}

    /** Init lists. */
    private void initLists(ConnectionOptions opts) {
        _accessList = opts.getAccessList();
        _blackList = opts.getBlacklist();
        _accessListEnabled = opts.isAccessListEnabled();
        _blackListEnabled = opts.isBlacklistEnabled();
    }

    /** Init lists. */
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

    /** Log an error message. */
    private static void error(String s) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        Log log = ctx.logManager().getLog(ConnectionOptions.class);
        log.error(s);
    }

    /**
     * Human-readable summary of the options.
     */
    @Override
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

    /**
     * Maximum inbound buffer cap, tunable via Tuner.
     * Default 8MB accommodates high-BDP paths without excess memory use.
     * @since 0.9.70+ mutable for adaptive tuning
     */
    private static volatile int maxInboundBuffer = 8 * 1024 * 1024;

    /** @since 0.9.70+ */
    public static int getMaxInboundBufferStatic() { return maxInboundBuffer; }
    /** @since 0.9.70+ */
    public static void setMaxInboundBufferStatic(int val) { maxInboundBuffer = Math.max(512 * 1024, Math.min(64 * 1024 * 1024, val)); }

    /** Initialize inbound buffer size and packet count cap. */
    private void initializeInboundBufferSize() {
        int minRequiredBufferSize = getMaxMessageSize() * ((6 * getMaxWindowSize()) / 2 + 2);
        setInboundBufferSize(Math.min(minRequiredBufferSize, maxInboundBuffer));
        _maxPacketCount = Math.max(_maxPacketCount, _inboundBufferSize / getMaxMessageSize() + 32);
    }

    /** Parse a boolean property from opts. */
    private static boolean getBool(Properties opts, String name, boolean defaultVal) {
        if (opts == null) return defaultVal;
        String val = opts.getProperty(name);
        if (val == null)  return defaultVal;
        return Boolean.parseBoolean(val);
    }
}
