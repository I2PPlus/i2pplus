package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.BandwidthEstimator;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

/**
 * Maintain the state controlling a streaming connection between two destinations.
 */
class Connection {

    /** Router context for accessing services and configuration. */
    private final I2PAppContext _context;
    /** Logger for this connection. */
    private final Log _log;
    /** Manager that created and tracks this connection. */
    private final ConnectionManager _connectionManager;
    /** I2P session used to send and receive messages. */
    private final I2PSession _session;
    /** Destination of the remote peer, or null if not yet established. */
    private Destination _remotePeer;
    /** Transient signing key for this connection, or null if not using offline keys. */
    private SigningPublicKey _transientSPK;
    /** Next outbound stream ID to assign. */
    private final AtomicLong _sendStreamId = new AtomicLong();
    /** Next inbound stream ID to assign. */
    private final AtomicLong _receiveStreamId = new AtomicLong();
    /** Timestamp of the last packet sent, in context clock ms. */
    private volatile long _lastSendTime;
    /** ID of the last message sent. */
    private final AtomicLong _lastSendId;
    /** Whether a RESET packet has been received from the remote peer. */
    private final AtomicBoolean _resetReceived = new AtomicBoolean();
    /** Timestamp when a RESET was sent, or 0 if none. */
    private final AtomicLong _resetSentOn = new AtomicLong();
    /** Timestamp when a RESET was received, or 0 if none. */
    private final AtomicLong _resetReceivedOn = new AtomicLong();
    /** Wall-clock ms of the last SYN-ACK (initial or re-send) we sent for this connection. */
    private volatile long _lastSynAckSentOn;
    /** Count of SYN-ACK re-sends triggered by retransmitted SYNs for this connection. */
    private volatile int _synAckResends;
    /** Whether the regenerated SYN-ACK WARN has been logged for this connection (rate limit). */
    private volatile boolean _synAckWarnLogged;
    /** Whether the connection is currently established and usable. */
    private final AtomicBoolean _connected = new AtomicBoolean(true);
    /** Whether the final disconnect sequence has been initiated. */
    private final AtomicBoolean _finalDisconnect = new AtomicBoolean();
    /** Whether the connection has been hard-disconnected (unrecoverable). */
    private volatile boolean _hardDisconnected;
    /** Stream for receiving incoming data from the remote peer. */
    private final MessageInputStream _inputStream;
    /** Stream for sending outgoing data to the remote peer. */
    private final MessageOutputStream _outputStream;
    /** Selects the appropriate scheduler for packet pacing. */
    private final SchedulerChooser _chooser;
    /** Locking: _nextSendLock */
    private long _nextSendTime;
    /** Atomic long. */
    private final AtomicLong _ackedPackets = new AtomicLong();
    /** Created on. */
    private final long _createdOn;
    /** Atomic long. */
    private final AtomicLong _closeSentOn = new AtomicLong();
    /** Atomic long. */
    private final AtomicLong _closeReceivedOn = new AtomicLong();
    /** Unacknowledged packets counter. */
    private final AtomicInteger _unackedPacketsReceived = new AtomicInteger();
    /** Congestion window end. */
    private long _congestionWindowEnd;
    /** Atomic long. */
    private final AtomicLong _highestAckedThrough = new AtomicLong(-1);
    /** Duplicate ACK tracking for fast retransmit (accessed inside _outboundPacketsLock) */
    private int _dupAckCount;
    /** Last dup ack. */
    private long _lastDupAck;
    /** Slow start threshold. */
    private volatile int _ssthresh;
    /** Whether this is an inbound connection. */
    private final boolean _isInbound;
    /** Whether share options have been updated. */
    private boolean _updatedShareOpts;
    /** Packet ID (Long) to PacketLocal for sent but unacked packets.
     *  Lazily allocated on the first send so inbound-only connections allocate
     *  no window at all. All access (including reads) happens under
     *  {@link #_outboundPacketsLock}; null until first write. */
    private TreeMap<Long, PacketLocal> _outboundPackets;
    /** Monitor for {@link #_outboundPackets} and the sender window. Exposed via
     *  {@link #getWindowLock()} so ConnectionPacketHandler serializes with us. */
    private final Object _outboundPacketsLock = new Object();
    /** Outbound queue. */
    private final PacketQueue _outboundQueue;
    /** Packet handler for this connection. */
    private final ConnectionPacketHandler _handler;
    /** Connection options. */
    private ConnectionOptions _options;
    /** Data receiver for outbound packets. */
    private final ConnectionDataReceiver _receiver;
    /** I2P socket for this connection. */
    private I2PSocketFull _socket;
    /** Error cause if the connection could not be established. */
    private String _connectionError;
    /** Atomic long. */
    private final AtomicLong _disconnectScheduledOn = new AtomicLong();
    /** Last received on. */
    private long _lastReceivedOn;
    /** Activity timer. */
    private final ActivityTimer _activityTimer;
    /** Last congestion highest unacked. */
    private volatile long _lastCongestionHighestUnacked;
    /** Consecutive loss events since the last recovery (graduated backoff).
     *  Reset in ackPackets() once the lost window is fully recovered.
     *  Accessed only under the _outboundPacketsLock. */
    private int _lossStrikes;
    /** Whether the established-connection resume WARN was already logged (once per
     *  connection) when a data packet exceeded its retransmit budget. Reset only
     *  when the connection object is reused. */
    private boolean _establishedResumeWarned;

    // Pacing fields for smooth transmission
    /** Pacing rate in bytes per second. */
    private volatile long _pacingRate; // bytes per second; last computed rate (recomputed live in calculatePacingDelay)
    /** Last packet send time. */
    private volatile long _lastPacketSendTime;
    /** Pacing lock. */
    private final Object _pacingLock = new Object();

    /** Whether the other side has choked us. */
    private volatile boolean _isChoked;
    /** Whether we are choking the other side. */
    private volatile boolean _isChoking;
    /** When we last sent a choke ACK, for persist-timer rate-limiting of re-asserts. */
    private volatile long _lastChokeAckTime;
    /** When we last logged the persist-timer auto-unchoke WARN, to rate-limit it
     *  against a persistently choked peer. 0 = never (resets on unchoke). */
    private volatile long _lastPersistWarnTime;
    /** Unchoke messages pending. */
    private final AtomicInteger _unchokesToSend = new AtomicInteger();
    /** Ack since congestion. */
    private final AtomicBoolean _ackSinceCongestion;
    /** Notify this on connection (or connection failure) */
    private final Object _connectLock;
    /** Locking for _nextSendTime */
    private final Object _nextSendLock;
    /** How many messages have been resent and not yet ACKed. */
    private final AtomicInteger _activeResends = new AtomicInteger();
    /**
     *  Set when a soft failure triggers an immediate retransmit pass, so the
     *  resend loop can tag those resends as soft (not consuming the hard
     *  retransmit budget).  Written on the router callback thread in
     *  {@link #scheduleSoftFailureRetransmit()}, read and cleared on the timer
     *  thread in {@link RetransmitEvent#timeReached()}.  Cleared on cancel so a
     *  cancelled soft pass cannot leak into a later RTO-driven pass.
     */
    private volatile boolean _softFailureResendPending;
    /**
     *  Wall-clock time (ms) of the last permitted immediate retransmit scheduled
     *  from {@link #scheduleSoftFailureRetransmit()}.  Gates back-to-back soft
     *  failures (e.g. NO_LEASESET while the LeaseSet fetch is in flight) so they
     *  defer to the retransmit timer instead of firing a new immediate pass at
     *  the I2CP round-trip rate, which exhausted the SYN give-up budget within
     *  ~200ms.  Written on the router callback thread, read on itself.
     */
    private volatile long _lastSoftFailRetransmit;
    /** Connection event. */
    private final ConEvent _connectionEvent;
    /** Retransmit event. */
    private final RetransmitEvent _retransmitEvent;
    /** Paced event. */
    private final PacedPacketEvent _pacedEvent;
    /** Paced-send queue, lazy like {@link #_outboundPackets}: null until the
     *  first paced packet, so connections that never pace allocate nothing.
     *  All access happens under {@link #_pacedQueueLock}. */
    private LinkedList<PacketLocal> _pacedQueue;
    /** Monitor for {@link #_pacedQueue}. */
    private final Object _pacedQueueLock = new Object();
    /** Tlp event. */
    private final TLProbeEvent _tlpEvent;
    /** Ack dup event. */
    private final AckDupEvent _ackDupEvent;
    /** Acked list, reusable for ackPackets() — avoids per-call allocation.
     *  Only accessed from the receive thread, serialized by _dataLock. */
    private final List<PacketLocal> _ackedList;
    /** Random wait. */
    private final int _randomWait;
    /** Local port. */
    private final int _localPort;
    /** Remote port. */
    private final int _remotePort;
    /** Retransmission timer. */
    private final SimpleTimer2 _timer;
    /** Bandwidth estimator. */
    private final BandwidthEstimator _bwEstimator;

    /** Atomic long. */
    private final AtomicLong _lifetimeBytesSent = new AtomicLong();
    /** Atomic long. */
    private final AtomicLong _lifetimeBytesReceived = new AtomicLong();
    /** Atomic long. */
    private final AtomicLong _lifetimeDupMessageSent = new AtomicLong();
    /** Atomic long. */
    private final AtomicLong _lifetimeDupMessageReceived = new AtomicLong();
    /** Atomic long. */
    private final AtomicLong _lifetimeDupBytesSent = new AtomicLong();

    /** @since 0.9.70+ */
    public static int getMaxResendDelay() {
        return I2PAppContext.getGlobalContext().getProperty("i2p.streaming.maxResendDelay", 30*1000);
    }
    /** @since 0.9.70+ */
    public static int getMinResendDelay() {
        return I2PAppContext.getGlobalContext().getProperty("i2p.streaming.minResendDelay", 100);
    }
    /** @since 0.9.70+ */
    public static int getDisconnectTimeout() {
        return I2PAppContext.getGlobalContext().getProperty("i2p.streaming.disconnectTimeout", 2*60*1000);
    }
    /** Default connect timeout in milliseconds. */
    public static final int DEFAULT_CONNECT_TIMEOUT = 30*1000;
    /** @since 0.9.70+ */
    static long getMaxConnectTimeout() {
        return I2PAppContext.getGlobalContext().getProperty("i2p.streaming.maxConnectTimeout", 75*1000);
    }

    /**
     * Connect timeout multiplier (50-200, 100=1.0x), set by the Tuner based on
     * observed RTT. Scales each client's desired timeout so fast networks fail
     * fast and slow networks get enough time. Applied in waitForConnect().
     * @since 0.9.70+
     */
    private static volatile int connectTimeoutMultiplier = 100;

    /** @since 0.9.70+ */
    static void setConnectTimeoutMultiplier(int pct) {
        connectTimeoutMultiplier = Math.max(30, Math.min(200, pct));
    }

    /** @since 0.9.70+ */
    static int getConnectTimeoutMultiplier() {
        return connectTimeoutMultiplier;
    }

    /**
     *  Compute the effective connect timeout (ms) an outbound connect will wait, as used
     *  by both {@link #waitForConnect(int)} and the SYN give-up budget in
     *  {@link #getMaxSynSends()}.
     *
     *  <p>Scales the desired base window ({@code connectDelay + connectTimeout}, or just
     *  {@code connectTimeout}) by the Tuner connect-timeout multiplier, then applies the
     *  {@link #CONNECT_TIMEOUT_FLOOR_MS} floor and the absolute {@code maxConnectTimeoutMs}
     *  cap.  Both consumers must agree on this value: a SYN retransmit budget
     *  <em>shorter</em> than this window was the cause of the spurious "Connection failed"
     *  for live-but-slow peers, where the retransmit timer discarded the connection
     *  error-free before waitForConnect reached its own timeout.
     *
     *  @param desiredTimeoutMs   the base window (connectDelay + connectTimeout) in ms;
     *                            &lt;=0 means no connect timeout is configured
     *  @param multiplier         the Tuner connect-timeout multiplier percentage (30-200)
     *  @param maxConnectTimeoutMs the absolute cap (i2p.streaming.maxConnectTimeout) in ms
     *  @return the effective window in ms, or 0 when no connect timeout is configured
     *  @since 0.9.71+
     */
    static long computeEffectiveConnectTimeout(long desiredTimeoutMs, int multiplier, long maxConnectTimeoutMs) {
        if (desiredTimeoutMs <= 0) {return 0;}
        long totalTimeout;
        if (multiplier > 100) {
            // Scaling by >100% only grows the window; if the base is already past the
            // absolute cap the result is pinned there, which also sidesteps overflow.
            if (desiredTimeoutMs > maxConnectTimeoutMs) {return maxConnectTimeoutMs;}
            totalTimeout = desiredTimeoutMs * multiplier / 100;
        } else {
            // Division-first form so the product can never overflow: for any
            // multiplier <= 100, (desired/100)*multiplier is bounded by desired itself.
            totalTimeout = (desiredTimeoutMs / 100) * multiplier + (desiredTimeoutMs % 100) * multiplier / 100;
        }
        if (totalTimeout < CONNECT_TIMEOUT_FLOOR_MS) {totalTimeout = CONNECT_TIMEOUT_FLOOR_MS;}
        if (totalTimeout > maxConnectTimeoutMs) {totalTimeout = maxConnectTimeoutMs;}
        return totalTimeout;
    }

    /**
     *  Default window size cap used when no per-connection or global override is set.
     *  The effective ceiling is managed by getGlobalMaxWindowSize(), which the Tuner
     *  adjusts based on observed RTT, bandwidth, and loss.
     */
    public static final int MAX_WINDOW_SIZE_DEFAULT = SystemVersion.isSlow() ? 384 : 512;

    /**
     *  Absolute ceiling on in-flight packets regardless of BDP estimate.
     *  Prevents runaway window growth from estimator noise or bugs.
     *  Set to accommodate high-BDP paths (e.g. 50Mbps @ 1s RTT ~ 6250 packets @ 1KB).
     */
    public static final int ABSOLUTE_MAX_WINDOW = 4096;

    /** @since 0.9.70+ mutable for adaptive tuning via Tuner */
    private static volatile int maxWindowSize = MAX_WINDOW_SIZE_DEFAULT;
    /** @since 0.9.70+ */
    public static int getGlobalMaxWindowSize() { return maxWindowSize; }
    /** @since 0.9.70+ */
    public static void setGlobalMaxWindowSize(int val) {
        maxWindowSize = Math.max(128, Math.min(ABSOLUTE_MAX_WINDOW, val));
    }

    /** Number of remaining unchoke assertions to send. */
    private static final int UNCHOKES_TO_SEND = 8;
    /** Multiplier for the Westwood BDP estimate used to calculate the new slow start threshold */
    private static final int SSTHR_BW_FACTOR = 2;
    /** Minimum slow start threshold after fast retransmit */
    private static final int MIN_SSTHR_FAST_RETX = 16;

    /**
     *  Give up resending an unacked SYN after this many sends.
     *  SYN retransmission uses a fixed RTO interval (no backoff — there is no
     *  congestion to manage before the connection is established), so the total
     *  SYN budget equals maxSynResends * interval.  The interval is the
     *  evidence-gated RTT-aware value from {@link #computeSynRetransmitInterval(int, int)}
     *  — the initial RTO (default 5s) before any RTT measurement, or an interval
     *  derived from the measured path RTT once evidence exists.  With the default 5s
     *  interval and 12 sends the budget is 60s, matching the historical behavior.
     *
     *  <p>This is the <em>per-connection floor</em> for the connect path:
     *  {@link #getMaxSynSends()} raises the effective give-up count as needed so the
     *  retransmit budget never expires before the connect window
     *  ({@link #computeEffectiveConnectTimeout(long, int, long)}) does.  Otherwise the
     *  retransmit timer can tear the connection down error-free while
     *  {@link #waitForConnect(int)} is still waiting, leaving the caller with a generic
     *  "Connection failed" instead of an accurate message.
     *  @since 0.9.70+ mutable for adaptive tuning
     */
    private static volatile int maxSynResends = 12;

    /**
     *  Minimum connect window (ms) after Tuner-multiplier scaling, so a fast network
     *  never cuts a handshake below a full SYN retransmit cycle.
     *  @since 0.9.71+
     */
    static final long CONNECT_TIMEOUT_FLOOR_MS = 10*1000;

    /** Accurate error set when an outbound SYN is never acknowledged within its retransmit budget.
     *  @since 0.9.71+ */
    static final String ERR_SYN_NOT_ACKNOWLEDGED = "Connection timed out: SYN not acknowledged";

    /** Accurate error set when an established data packet exceeds its retransmit limit.
     *  @since 0.9.71+ */
    static final String ERR_RETRANSMIT_LIMIT = "Connection failed: Retransmission limit reached";

    /** Accurate error set when the remote closes an outbound connection before it was established.
     *  @since 0.9.71+ */
    static final String ERR_CONNECTION_REFUSED = "Connection failed: Closed by remote before establishment";

    /** Accurate error reported by {@link #waitForConnect(int)} on its own timeout.
     *  @since 0.9.71+ */
    static final String ERR_CONNECTION_TIMED_OUT = "Connection timed out";

    /**
     * Minimum SYN retransmit interval (ms) ever used once positive RTT evidence for
     * the path exists.  Keeps a fresh SYN from re-firing faster than the fabric can
     * plausibly round-trip even on a very fast tunnel, avoiding a retransmit storm on
     * a peer that is merely slow to complete the handshake.
     */
    static final int SYN_RTO_MIN = 750;

    /**
     * Headroom factor applied to a measured path RTT to derive the SYN retransmit
     * interval.  A single RTT sample is a lower bound on the true round-trip under a
     * congested tunnel, so scale it up (as a percentage, e.g. 150 = 1.5x) to give the
     * SYN and its ACK room to complete without a spurious retransmit.
     */
    private static final int SYN_RTO_HEADROOM_PCT = 150;

    /**
     * Compute the inter-SYN retransmit interval for an outbound connection, evidence-gated
     * and RTT-aware.
     *
     * <p>SYN retransmission uses a fixed interval (no backoff — there is no congestion to
     * manage before the connection is established), so the total SYN budget is {@code
     * maxSynResends * interval}.  The historical behavior is a fixed {@code initialRtoMs}
     * (default 5000) for every connection, which overshoots the 30s connect timeout on a
     * dead path (12 * 5s = 60s) and under-utilizes the window on a healthy path whose
     * round-trip is far below 5s.
     *
     * <p><b>Gate.</b> The measured RTT {@code measuredRttMs} is <em>positive</em> only when
     * there is genuine path evidence for this peer (a valid analytic ACK, or a dampened RTT
     * seeded from the TCB cache for a recently-contacted destination — see
     * {@link TCBShare}).  When it is {@code <= 0} there is no evidence (fresh or
     * blackholed peer — {@code Received: 0} throughout, so {@code getRTT()} never advances
     * past its initial default), and this returns the configured {@code initialRtoMs}
     * unchanged.  A path with no measurement is treated conservatively rather than
     * fast-failed, because shrinking the interval without evidence would be guessing.
     *
     * <p><b>RTT-aware interval.</b> With evidence, the interval becomes {@code headroomPct%
     * * measuredRttMs}, floored at {@link #SYN_RTO_MIN} (no storm on a fast fabric) and
     * capped at the configured {@code initialRtoMs} (never worse than the current default,
     * so a genuinely slow-but-alive path keeps its handshake room).  A measured RTT below
     * the 5000ms default therefore packs more SYN attempts into the connect window
     * (better odds of catching a momentarily-congested tunnel) and, if the path is dead,
     * lets SYN resends exhaust near the 30s connect timeout instead of running the full
     * 60s overshoot — so the proxy can emit the "Website Unreachable" page sooner.
     *
     * @param measuredRttMs a recent round-trip time for this peer in ms, or &lt;=0 when no
     *                      RTT evidence is available (never measured, no cache entry)
     * @param initialRtoMs  the configured initial RTO (i2p.streaming.initialRTO) in ms
     * @return the fixed inter-SYN retransmit interval (ms) to use
     * @since 0.9.70+ evidence-gated and RTT-aware SYN pacing
     */
    static int computeSynRetransmitInterval(int measuredRttMs, int initialRtoMs) {
        if (initialRtoMs <= 0) {return initialRtoMs;}
        if (measuredRttMs <= 0) {return initialRtoMs;}
        long interval = (long) measuredRttMs * SYN_RTO_HEADROOM_PCT / 100;
        if (interval < SYN_RTO_MIN) {interval = SYN_RTO_MIN;}
        if (interval > initialRtoMs) {interval = initialRtoMs;}
        return (int) interval;
    }

    /**
     *  Compute the effective number of SYN sends before this connection gives up, used by
     *  {@link #getMaxSynSends()} on each give-up decision.
     *
     *  <p>The total SYN budget is {@code sends * synIntervalMs} (fixed interval, no backoff).
     *  With RTT evidence the interval can collapse toward {@link #SYN_RTO_MIN} (750ms), so a
     *  fixed {@code configuredMax} budget can end well before {@link #waitForConnect(int)}'s
     *  window does — {@code 12 * 750ms = 9s} against a 10000ms floor — leaving waitForConnect
     *  to mislabel the handshake as "Connection failed".  This returns at least enough sends
     *  that {@code sends * synIntervalMs &gt;= connectWindowMs}, so the connect-timeout gate
     *  (which reports an accurate error) fires first on genuinely-dead paths, while a
     *  live-but-slow peer keeps receiving SYN retransmits for the full window.
     *
     *  @param configuredMax   the configured static give-up count ({@link #maxSynResends})
     *  @param synIntervalMs   the fixed inter-SYN retransmit interval (ms) in use
     *  @param connectWindowMs the effective connect window from
     *                         {@link #computeEffectiveConnectTimeout(long, int, long)};
     *                         0 means no connect timeout is configured
     *  @return the number of sends before giving up, never less than {@code configuredMax}
     *  @since 0.9.71+
     */
    static int computeSynResendBudget(int configuredMax, int synIntervalMs, long connectWindowMs) {
        if (configuredMax <= 0 || synIntervalMs <= 0 || connectWindowMs <= 0) {return configuredMax;}
        long needed = (connectWindowMs + synIntervalMs - 1) / synIntervalMs;
        if (needed <= configuredMax) {return configuredMax;}
        return (int) Math.min(needed, Integer.MAX_VALUE);
    }

    /**
     * Minimum spacing (ms) between SYN-ACK re-sends to an existing connection in
     * response to retransmitted SYNs.
     *
     * <p>This is the <em>floor</em>: the live spacing used by
     * {@link #shouldResendSynAck(long)} is the connection's own
     * {@link #getSynRetransmitInterval()} — the same RTT-aware interval the client
     * uses for its SYN retransmit timer — so every client retransmit can receive a
     * SYN-ACK response even as the Tuner adapts the initial RTO.  At 3s the server
     * skipped every other retransmit, starving lossy I2P paths where the initial
     * SYN-ACK was lost and each re-send must traverse a degraded tunnel.
     *
     * <p>The amplification bound is preserved by the resend cap
     * ({@link #computeSynAckResendCap(long, long)}): spacing
     * ensures one answer per RTO, the count cap hard-bounds the total.
     *
     * @since 0.9.71+
     */
    static final long SYN_ACK_RESEND_MIN_SPACING_MS = 2000;

    /**
     * Maximum number of SYN-ACK re-sends to an existing connection in response to
     * retransmitted SYNs, per connection lifetime.
     *
     * <p>Used when no connection window is configured (connect timeout absent).
     * The live cap is {@link #computeSynAckResendCap(long, long)}, derived from the
     * client's effective connect window so a connection that spins in a half-open
     * state (client stuck retransmitting at its RTO, handshake never completes)
     * cannot mint SYN-ACKs past the point the client itself has given up.
     *
     * @since 0.9.71+
     */
    static final int SYN_ACK_RESEND_MAX = Integer.MAX_VALUE;

    /**
     * Compute the per-connection SYN-ACK re-send cap: the number of retransmitted
     * SYNs the client can fit within its effective connect window, given the
     * inter-SYN interval in use.  Answering past this point is wasted work — by
     * then the client has given up on the handshake — so the cap hard-bounds
     * SYN-ACK minting without ever starving a live-but-slow peer.
     *
     * @param connectWindowMs the client's effective connect window
     *                        ({@link #getEffectiveConnectWindow()}); &lt;=0 means no
     *                        window is configured and no cap is applied
     * @param spacingMs       the inter-SYN-AACK spacing in use; &lt;=0 means no spacing
     *                        is derived and no cap is applied
     * @return the cap; never less than 1, or {@link #SYN_ACK_RESEND_MAX} when no
     *         window is configured
     * @since 0.9.71+
     */
    static int computeSynAckResendCap(long connectWindowMs, long spacingMs) {
        if (connectWindowMs <= 0 || spacingMs <= 0) {return SYN_ACK_RESEND_MAX;}
        long cap = (connectWindowMs + spacingMs - 1) / spacingMs;
        if (cap < 1) {cap = 1;}
        return (int) Math.min(cap, Integer.MAX_VALUE);
    }

    /**
     * Decide whether to re-send a SYN-ACK for an existing connection in response to
     * a retransmitted SYN.
     *
     * <p>Bound is applied <em>before</em> the SYN-ACK is minted so a throttled
     * retransmit costs nothing (no packet object, no signature, no enqueue).  The
     * decision is purely a function of connection send history and wall clock, which
     * keeps the hot path branch-free and the rule testable without a running router.
     *
     * <p><b>Rules.</b> A re-send is allowed when:
     * <ol>
     *   <li>fewer than {@code maxResends} re-sends have already been performed
     *       ({@code resends < maxResends}), and</li>
     *   <li>either no SYN-ACK has been sent yet in connection history
     *       ({@code lastSynAckSentOn <= 0} — nothing to space against), or the
     *       interval since the last SYN-ACK is at least {@code minSpacingMs}.</li>
     * </ol>
     * History is authoritative: calls do not mutate state, so a rejected re-send
     * neither extends nor advances the throttle window (state snapshotting; see
     * AGENTS.md "State Snapshotting").
     *
     * @param lastSynAckSentOn wall-clock ms of the last SYN-ACK sent for the
     *                         connection (initial or re-send), or &lt;=0 if none yet
     * @param resends          number of SYN-ACK re-sends already performed for this
     *                         connection
     * @param now              current wall-clock ms ({@code I2PAppContext.clock()})
     * @param minSpacingMs     minimum interval between re-sends in ms
     * @param maxResends       maximum SYN-ACK re-sends per connection lifetime
     * @return true if a SYN-ACK should be re-sent now
     * @since 0.9.71+
     */
    static boolean shouldResendSynAck(long lastSynAckSentOn, int resends, long now,
                                      long minSpacingMs, int maxResends) {
        if (resends >= maxResends) {return false;}
        if (lastSynAckSentOn <= 0) {return true;}
        return now - lastSynAckSentOn >= minSpacingMs;
    }

    /**
     * Whether this connection should re-send a SYN-ACK for a retransmitted SYN
     * right now, given its own send history.  Pure delegate over
     * {@link #shouldResendSynAck(long, int, long, long, int)} using the
     * RTT-aware spacing this connection actually arms, so the heuristic is
     * exercised through the same code path tests cover.
     *
     * @param now current wall-clock ms from {@code I2PAppContext.clock()}
     * @return true if a SYN-ACK should be re-sent now
     * @since 0.9.71+
     */
    boolean shouldResendSynAck(long now) {
        int spacing = getSynRetransmitInterval();
        return shouldResendSynAck(_lastSynAckSentOn, _synAckResends, now, spacing,
                                  computeSynAckResendCap(getEffectiveConnectWindow(), spacing));
    }

    /**
     * Record that a SYN-ACK was sent for this connection at {@code now} (initial
     * re-send from {@code ConnectionHandler#resendSynAck} only — the initial
     * handshake SYN-ACK is a normal outbound packet and is not counted here).  This
     * snapshot advances the throttle window so subsequent retransmitted SYNs are
     * spaced {@link #SYN_ACK_RESEND_MIN_SPACING_MS} after the last answer.
     *
     * @param now wall-clock ms the SYN-ACK was enqueued
     * @since 0.9.71+
     */
    void recordSynAckResend(long now) {
        _lastSynAckSentOn = now;
        _synAckResends++;
    }

    /**
     * Whether the "re-sending SYN-ACK" WARN has already been logged for this
     * connection, and mark it logged on first check.  Prevents a retransmit storm
     * against a single stuck connection from flooding the log with identical WARN
     * lines (observed as ~15% of log volume during the tracker-tunnel storm); the
     * first re-send logs WARN, subsequent ones drop to DEBUG.
     *
     * @return true if the WARN was already logged (caller should log at DEBUG),
     *         false if this is the first re-send (caller should log at WARN)
     * @since 0.9.71+
     */
    boolean synAckWarnAlreadyLogged() {
        if (_synAckWarnLogged) {return true;}
        _synAckWarnLogged = true;
        return false;
    }

    /**
     * Maximum number of packets to retransmit when the timer hits.
     * Default 32 allows faster loss recovery on wide windows (256-packet window
     * backlog clears in 4 rounds instead of 8).
     * @since 0.9.70+
     */
    private static volatile int maxRetransmissions = 32;
    /** @since 0.9.70+ */
    public static int getMaxRetransmissionsStatic() { return maxRetransmissions; }
    /** @since 0.9.70+ */
    public static void setMaxRetransmissions(int val) { maxRetransmissions = Math.max(8, Math.min(128, val)); }
    /** @since 0.9.70+ */
    public static int getMaxSynResendsStatic() { return maxSynResends; }
    /** @since 0.9.70+ */
    public static void setMaxSynResends(int val) { maxSynResends = Math.max(3, Math.min(16, val)); }

    /**
     * Maximum number of packets to retransmit in a single timer fire.
     * This caps the resend batch size only; the give-up threshold is
     * {@link ConnectionOptions#getMaxResends()}, checked separately.
     * Tunable via i2p.streaming.maxRetransmissions (default: 32).
     * @return the max rtx
     */
    private int getMaxRtx() {
        return maxRetransmissions;
    }

    /**
     * The fixed interval between SYN retransmits for this connection, evidence-gated.
     *
     * <p>Delegate for {@link #computeSynRetransmitInterval(int, int)} using this
     * connection's current path evidence ({@code ConnectionOptions#getRTT()} is a real
     * measurement only once {@code ConnectionOptions#receivedAck()} is true — either a
     * valid handshake ACK or a dampened TCB-cache value seeded from a prior connection to
     * this destination) and the configured initial RTO.  Pure so the give-up math is
     * testable directly through {@link #computeSynRetransmitInterval(int, int)}.
     *
     * @return the fixed inter-SYN retransmit interval (ms) to arm the retransmit timer with
     */
    private int getSynRetransmitInterval() {
        int rtt = _options.getRTT();
        if (!_options.receivedAck()) {rtt = -1;}
        return computeSynRetransmitInterval(rtt, ConnectionOptions.getInitialRTO());
    }

    /**
     *  Effective SYN give-up count for this connection: {@link #maxSynResends} scaled up so
     *  the retransmit budget (count * inter-SYN interval) covers the connect window, so the
     *  retransmit timer cannot tear this connection down while {@link #waitForConnect(int)}
     *  is still waiting.
     *
     *  <p>The base window mirrors {@link ConnectionManager#connect}: connectDelay is added to
     *  connectTimeout only on the delayed-SYN path, which is exactly how waitForConnect is
     *  invoked there ({@code connectDelay + connectTimeout}).
     *
     *  @return the number of SYN sends before giving up on an unacknowledged SYN
     *  @since 0.9.71+
     */
    private int getMaxSynSends() {
        long window = getEffectiveConnectWindow();
        return computeSynResendBudget(maxSynResends, getSynRetransmitInterval(), window);
    }

    /**
     *  Assemble the un-scaled base connect window (ms) for a connection's options.
     *
     *  <p>connectDelay is added to connectTimeout only on the delayed-SYN path (both
     *  set), so the base matches how {@link ConnectionManager#connect} invokes
     *  {@code waitForConnect(int)} — there the SYN has not yet been sent and {@code
     *  connectDelay} is added to the timeout so the handshake is not cut short while
     *  the SYN is still waiting to be transmitted.
     *
     *  @param connectTimeoutMs the option's connect timeout in ms (&lt;=0 means none)
     *  @param connectDelayMs   the option's connect delay in ms
     *  @return the base connect window in ms; 0 when no connect timeout is configured
     *  @since 0.9.71+
     */
    static long computeConnectBase(long connectTimeoutMs, long connectDelayMs) {
        if (connectTimeoutMs <= 0) {return 0;}
        if (connectDelayMs > 0) {return connectTimeoutMs + connectDelayMs;}
        return connectTimeoutMs;
    }

    /**
     *  The total time (ms) from connection creation during which this connecting
     *  connection may wait for its SYN to be acknowledged before the connect path
     *  tears the handshake down.
     *
     *  <p>This is the single authoritative connect window shared by
     *  {@link #waitForConnect(int)}, {@link #getMaxSynSends()}, and
     *  {@link SchedulerConnecting}, so no path can tear an unacknowledged SYN down
     *  before the others have given it the full Tuner-scaled budget.  It is the
     *  (connectDelay + connectTimeout) base — connectDelay counted only on the
     *  delayed-SYN path, exactly as {@link ConnectionManager#connect} invokes
     *  waitForConnect — scaled by the Tuner's connect-timeout multiplier, floored at
     *  {@link #CONNECT_TIMEOUT_FLOOR_MS}, and capped at the absolute max connect
     *  timeout.
     *
     *  <p>Using the raw un-scaled connectTimeout here (as SchedulerConnecting once did)
     *  lets the scheduler race ahead of waitForConnect on high-RTT paths where the
     *  Tuner has raised the multiplier above 100%, killing the handshake with a generic
     *  error instead of the accurate "SYN not acknowledged".
     *
     *  @return the effective connect window in milliseconds; 0 when no connect timeout
     *          is configured (no budget, caller should not give up on timeout)
     *  @since 0.9.71+
     */
    long getEffectiveConnectWindow() {
        long base = computeConnectBase(_options.getConnectTimeout(), _options.getConnectDelay());
        return computeEffectiveConnectTimeout(base, getConnectTimeoutMultiplier(), getMaxConnectTimeout());
    }

    /**
     *  Constructor for this connection.
     *  @param opts may be null
     */
    public Connection(I2PAppContext ctx, ConnectionManager manager,
                      I2PSession session, SchedulerChooser chooser,
                      SimpleTimer2 timer,
                      PacketQueue queue, ConnectionPacketHandler handler, ConnectionOptions opts,
                      boolean isInbound) {
        _context = ctx;
        _connectionManager = manager;
        _session = session;
        _chooser = chooser;
        _outboundQueue = queue;
        _handler = handler;
        _isInbound = isInbound;
        _log = _context.logManager().getLog(Connection.class);
        _receiver = new ConnectionDataReceiver(_context, this);
        _options = (opts != null ? opts : new ConnectionOptions());
        _inputStream = new MessageInputStream(_context, _options.getMaxMessageSize(),
                                              _options.getMaxWindowSize(), _options.getInboundBufferSize(),
                                               _options.getMaxPacketCount());
        _outputStream = new MessageOutputStream(_context, timer, _receiver,
                                                _options.getMaxMessageSize(), _options.getMaxInitialMessageSize(),
                                                _options.getPassiveFlushDelay());
        _timer = timer;
        // _outboundPackets and _pacedQueue are lazily allocated on first use
        // (see the fields) so inbound-only connections are allocation-free.
        if (opts != null) {
            _localPort = opts.getLocalPort();
            _remotePort = opts.getPort();
        } else {
            _localPort = 0;
            _remotePort = 0;
        }
        _outputStream.setWriteTimeout((int)_options.getWriteTimeout());
        _inputStream.setReadTimeout((int)_options.getReadTimeout());
        _lastSendId = new AtomicLong(-1);
        _nextSendTime = -1;
        _createdOn = _context.clock().now();
        _congestionWindowEnd = _options.getWindowSize()-1;
        _ssthresh = ConnectionPacketHandler.getMaxSlowStartWindow(_context);
        _lastCongestionHighestUnacked = -1;
        _lastReceivedOn = -1;
        _activityTimer = new ActivityTimer();
        _ackSinceCongestion = new AtomicBoolean(true);
        _connectLock = new Object();
        _nextSendLock = new Object();

        // Initialize pacing
        _pacingRate = calculatePacingRate();
        _lastPacketSendTime = 0;

        // Initialize connection event, retransmit event, and paced packet event
        _connectionEvent = new ConEvent();
        _retransmitEvent = new RetransmitEvent();
        _pacedEvent = new PacedPacketEvent();
        _tlpEvent = new TLProbeEvent();
        _ackDupEvent = new AckDupEvent();
        _ackedList = new ArrayList<>(8);

        // Initialize random wait for activity timer randomization and bandwidth estimator
        _randomWait = _context.random().nextInt(3*1000); // 0-3 seconds randomization
        _bwEstimator = new SimpleBandwidthEstimator(_context, _options);
    }

    /**
     * Calculate pacing rate based on current congestion window and RTT.
     * Rate = (cwnd * mss) / rtt to smooth transmission.
     */
    private long calculatePacingRate() {
        int cwnd = _options.getWindowSize();
        int mss = _options.getMaxMessageSize();
        int rtt = _options.getRTT();

        if (rtt <= 0 || cwnd <= 0 || mss <= 0) {
            return Long.MAX_VALUE; // No pacing if parameters invalid
        }

        // Calculate rate in bytes per second
        long rateBytesPerSec = (long) cwnd * mss * 1000 / rtt;

        // Ensure minimum rate to prevent excessive delays
        long minRate = ConnectionOptions.getMinPacingRate();
        return Math.max(rateBytesPerSec, minRate);
    }

    /**
     * Refresh the cached pacing rate when the congestion window changes.
     * Note that {@link #calculatePacingDelay(int)} also recomputes the rate
     * live, so this is now only an eager cache update for observability.
     */
    private void updatePacingRate() {
        synchronized (_pacingLock) {
            _pacingRate = calculatePacingRate();
        }
    }

    /**
     * Calculate delay needed for pacing based on packet size and current rate.
     * Pacing delay = (packetSize / rate) - timeSinceLastPacket.
     * @return delay in ms, 0 if no pacing needed
     */
    private long calculatePacingDelay(int packetSize) {
        long rate = calculatePacingRate();
        if (rate == Long.MAX_VALUE) {
            return 0;
        }
        return calculatePacingDelay(packetSize, rate);
    }

    /**
     * Same as above but recomputes live to avoid stale-RTT pacing.
     */
    private long calculatePacingDelay(int packetSize, long pacingRate) {
        synchronized (_pacingLock) {
            long now = _context.clock().now();
            long timeSinceLastPacket = now - _lastPacketSendTime;
            long expectedInterval = (long) packetSize * 1000 / pacingRate;
            if (timeSinceLastPacket >= expectedInterval) {
                return 0;
            }
            return expectedInterval - timeSinceLastPacket;
        }
    }

    /**
     * @since 0.9.46
     */
    int getSSThresh() {return _ssthresh;}

    /**
     * Next outbound packet sequence number.
     *
     * @return the next sequence number
     */
    public long getNextOutboundPacketNum() {return _lastSendId.incrementAndGet();}

    /**
     * This doesn't "send a choke". Rather, it blocks if the outbound window is full,
     * thus choking the sender that calls this.
     *
     * Block until there is an open outbound packet slot or the write timeout expires.
     * PacketLocal is the only caller, generally with -1.
     *
     * A persist timer prevents deadlock when the remote chokes us (window=1) and
     * then never sends an unchoke signal.  After RTO (min 5s), we auto-unchoke and
     * allow the retransmission mechanism to restore the window, with exponential
     * backoff so a persistently choked peer does not cause a tight probe loop.
     *
     * <p>The wait loop polls every 50ms (instead of 250ms) to reduce latency
     * when an ACK arrives to free a window slot.
     *
     * @param timeoutMs 0 or negative means wait forever, 5 minutes max
     * @return true if the packet should be sent, false for a fatal error
     *         will return false after 5 minutes even if timeoutMs is &lt;= 0.
     */
    public boolean packetSendChoke(long timeoutMs) throws IOException, InterruptedException {
        long start = _context.clock().now();
        long now = start;
        long writeExpire = start + timeoutMs;
        boolean started = false;
        int persistBackoff = 0;
        while (true) {
            long timeLeft = writeExpire - now;
            // Sample the bandwidth estimator outside the lock; it has its own
            // lock, and the timer/estimator thread takes _outboundPacketsLock
            // in the opposite order (see ackPackets / RetransmitEvent comments).
            int inFlightCap = getBDPBasedInFlightCap();
            boolean send = false;
            int chokeSize = 0;
            synchronized (_outboundPacketsLock) {
                if (!started) {_context.statManager().addRateData("stream.chokeSizeBegin", outboundSizeLocked());}
                if (start + 5*60*1000 < now) {return false;}

                if (!isConnectedOrError()) {return false;}
                started = true;

                int unacked = outboundSizeLocked();
                int wsz = _options.getWindowSize();
                if (shouldWait(unacked, wsz, inFlightCap)) {
                    if (_isChoked) {
                        long persistDelay = Math.max(_options.getRTO(), 2000L);
                        if (persistBackoff > 0) {
                            persistDelay = Math.min(persistDelay << persistBackoff, 60000L);
                        }
                        if (now - start >= persistDelay) {
                            // Consume the rate-limit token regardless of level so a
                            // stream spamming writes cannot keep the WARN hot.
                            if (shouldLogPersistWarn(_lastPersistWarnTime, now, persistDelay)) {
                                _lastPersistWarnTime = now;
                                if (_log.shouldWarn()) {
                                    _log.warn("Persist timer expired, auto-unchoking on " + this);
                                }
                            }
                            persistBackoff = Math.min(persistBackoff + 1, 5);
                            setChoked(false);
                            continue;
                        }
                    }
                    if (timeoutMs > 0) {
                        if (timeLeft <= 0) {
                            if (_log.shouldInfo()) {
                                _log.info("Outbound window is full (choked? " + _isChoked + ' ' + unacked
                                          + " unacked with " + _activeResends + " active resends"
                                          + " and we've waited too long (" + (0-(timeLeft - timeoutMs)) + "ms): "
                                          + toString());
                            }
                            return false;
                        }
                        _outboundPacketsLock.wait(Math.min(timeLeft, 50));
                    } else {
                        _outboundPacketsLock.wait(50);
                    }
                    now = _context.clock().now();
                } else {
                    chokeSize = outboundSizeLocked();
                    send = true;
                }
            }
            if (send) {
                _context.statManager().addRateData("stream.chokeSizeEnd", chokeSize);
                return true;
            }
        }
    }

    /** Connected or error. */
    private boolean isConnectedOrError() throws IOException {
        if (!_connected.get()) {
            if (getResetReceived()) {throw new I2PSocketException(I2PSocketException.STATUS_CONNECTION_RESET);}
            throw new IOException("Socket closed");
        }
        if (_outputStream.getClosed()) {throw new IOException("Output stream closed");}
        return true;
    }

    /**
     *  BDP-based upper bound on in-flight packets.
     *  Uses the Westwood+ bandwidth estimator to compute how many packets
     *  the pipe can hold, floored at the global max window size to preserve
     *  the legacy baseline on low-BDP or uncalibrated paths.
     *
     *  @return max allowed in-flight packets, in [globalMax, ABSOLUTE_MAX_WINDOW]
     */
    private int getBDPBasedInFlightCap() {
        float bwe = _bwEstimator.getBandwidthEstimate(); // packets/ms
        int rtt = Math.max(_options.getRTT(), 500);       // ms
        int bdp = Math.max(getGlobalMaxWindowSize(), (int)(bwe * rtt));
        return Math.min(ABSOLUTE_MAX_WINDOW, bdp);
    }

    /**
     *  Tail Loss Probe timeout (PTO).
     *  Time after which we send a probe if no ACK has been received for the
     *  oldest unacked packet. Fires at ~2*RTT to detect loss before RTO.
     *
     *  @return probe timeout in ms, in [200, 2000]
     */
    private int getPTO() {
        int rtt = _options.getRTT();
        if (rtt <= 0) {
            return Math.min(3000, _options.getRTO() / 2);
        }
        return Math.max(200, Math.min(2000, rtt * 2));
    }

    /**
     *  Whether the sender should block.
     *  @param unacked number of unacked packets
     *  @param wsz the current window size
     *  @param inFlightCap BDP-based in-flight cap, sampled outside the lock
     *  @return true if the sender should block (window full or choked)
     */
    private boolean shouldWait(int unacked, int wsz, int inFlightCap) {
        return _isChoked || unacked >= wsz ||
               _lastSendId.get() - _highestAckedThrough.get() >= inFlightCap;
    }

    /**
     * Whether to log another persist-timer auto-unchoke WARN.
     * Guard: emit at most one WARN per persist interval per connection. The
     * persist branch in {@link #packetSendChoke(long)} is re-armed on every
     * block and its backoff is a per-call local, so an application that keeps
     * writing into a persistently choked window would otherwise log on every
     * write attempt.
     *
     * @param lastWarnTime last timestamp the WARN was emitted, 0 if never
     * @param now          current time
     * @param minIntervalMs minimum ms between WARNs (the persist delay itself)
     * @return true if the WARN should be emitted
     * @since 0.9.71+
     */
    static boolean shouldLogPersistWarn(long lastWarnTime, long now, long minIntervalMs) {
        return lastWarnTime <= 0 || now - lastWarnTime >= minIntervalMs;
    }

    /**
     *  Graduated congestion response: the window is cut by an amount that
     *  scales with the number of CONSECUTIVE loss events since the last
     *  recovery, so a single failed packet does not collapse the connection
     *  while persistent loss still backs off.
     *
     *  @param strikes consecutive loss events (&gt;= 1) since the last recovery
     *  @param wsize current window size
     *  @return new window size, floored at 4 (never below a usable minimum)
     */
    static int graduatedLossWindow(int strikes, int wsize) {
        if (strikes <= 1) {
            return Math.max(4, wsize * 3 / 4);
        } else if (strikes == 2) {
            return Math.max(4, wsize / 2);
        } else {
            return Math.max(4, wsize / 4);
        }
    }

    /**
     *  Slow-start threshold for a graduated loss response: never below the
     *  graduated window (so the connection can regrow to at least its post-cut
     *  capacity) and never below the bandwidth-derived estimate.
     *
     *  @param strikes consecutive loss events since the last recovery
     *  @param wsize current window size
     *  @param bwBasedSsthresh the bandwidth-estimate-derived threshold (&gt;= 1)
     *  @return slow-start threshold, floored at 1
     */
    static int graduatedLossSsthresh(int strikes, int wsize, int bwBasedSsthresh) {
        return Math.max(graduatedLossWindow(strikes, wsize), Math.max(bwBasedSsthresh, 1));
    }

    /**
     * Pure predicate: has the oldest unacked packet been in flight beyond the
     * worst-case retransmit budget, measured from CREATION?
     *
     * <p>The worst case is one transmission per RTO for the full per-packet
     * retransmit budget ({@code maxResends * maxRto}). Anchoring the budget at
     * the packet's creation (instead of its last transmission) gives a fixed
     * wall-clock deadline that retransmission activity cannot extend: an
     * established connection in resume mode keeps retransmitting a
     * budget-exhausted head-of-line packet every RTO (refreshing its last send
     * time) precisely so it can surge forward when the path clears, and that
     * deadline must still hold so a genuinely dead path is closed rather than
     * retried forever. An exact-inequality comparison keeps a live path on the
     * budget boundary from being killed.
     *
     * @param maxResends configured per-packet retransmit budget (&gt; 0)
     * @param maxRto configured maximum single retransmit timeout in ms (&gt; 0)
     * @param now current time in ms since epoch
     * @param createdOn creation time of the oldest packet, in ms since epoch
     *                  (PacketLocal.getCreatedOn()), or &lt;= 0 if unknown
     * @return true if the packet has been in flight beyond the budget and
     *         recovery is presumed dead
     * @since 0.9.71+
     */
    static boolean stuckLifetimeExceeded(int maxResends, int maxRto, long now, long createdOn) {
        if (maxResends <= 0 || maxRto <= 0 || createdOn <= 0)
            return false;
        return now - createdOn > (long) maxResends * maxRto;
    }

    /**
     * Pure predicate: has the remote sent nothing at all for a full inactivity
     * window, while unacked packets are still in flight?
     *
     * <p>An ACK (or a data packet) is emitted by a live remote on receipt within
     * the ack window, so a connection that has unacked in-flight packets AND has
     * received zero bytes for the configured inactivity timeout can no longer be
     * making progress in either direction: either our sends are not arriving
     * (dead outbound leg) or the remote is gone. Holding such a connection longer
     * cannot resume it — per the protocol the remote's own inactivity timer
     * (default 120s, {@code i2p.streaming.inactivityTimeout}) has already closed
     * it, and its close is lost on the dead path back. This gives an established
     * connection in resume mode — which otherwise retransmits a budget-exhausted
     * head-of-line packet every RTO until the creation-anchored backstop
     * ({@link #stuckLifetimeExceeded(int, int, long, long)}, default 30 sends *
     * 30s maxRTO = 15 min) fires — a tighter wall-clock deadline so the
     * application gets EOF within ~one inactivity window instead of hanging on a
     * zombie stream it can never resume.
     *
     * <p>Anchoring at the last RECEIVED packet (not the last send) is deliberate:
     * resume mode refreshes the last send time on every retransmit, so a
     * last-send-anchored check would defer forever. A receive-anchored check only
     * fires when the remote has truly gone silent; a healthy path that
     * acknowledges our data — even slowly — resets {@code lastReceivedOn} and
     * never hits this bound. An exact-inequality comparison keeps a connection on
     * the boundary alive.
     *
     * @param lastReceivedOn timestamp of the last packet received from the remote,
     *                       in ms since epoch (Connection#packetReceived()), or
     *                       &lt;= 0 if nothing has ever been received (connect phase,
     *                       which is bounded separately by the SYN give-up budget)
     * @param inactivityTimeout configured inactivity timeout in ms (&gt; 0)
     * @param now current time in ms since epoch
     * @return true if the remote has been silent for the full inactivity window
     *         while unacked packets are in flight
     * @since 0.9.71+
     */
    static boolean remoteSilentTooLong(long lastReceivedOn, int inactivityTimeout, long now) {
        if (lastReceivedOn <= 0 || inactivityTimeout <= 0)
            return false;
        return now - lastReceivedOn > inactivityTimeout;
    }

    /**
     * Pure decision: has the number of HARD send attempts (sends that took the
     * router's reply as something to retry after, i.e. not router soft failures)
     * exceeded the retransmit budget?
     *
     * <p>A router soft failure (NO_TUNNELS / EXPIRED / LOCAL) means the message
     * was never put on the tunnel fabric, so the attempt is not evidence of
     * on-wire loss. Counting soft-failure-triggered resends against
     * {@code maxResends} makes a long stream abort during a tunnel handover:
     * each soft failure immediately re-fires the retransmit timer, so ~30
     * router-side failures exhaust the budget and kill the connection even
     * though the packet never left. Only genuine loss should consume the
     * budget. The subtraction is clamped at zero so an early-tagged paced
     * resend cannot undercount below the true hard count.
     *
     * @param totalSends total send attempts incl. resends (PacketLocal.getNumSends())
     * @param softResends of those, resends tagged as soft-failure-triggered
     *                    (PacketLocal.getNumSoftResends())
     * @param maxResends configured per-packet retransmit budget (&gt; 0)
     * @return true if the non-soft send attempts exceed the budget
     * @since 0.9.71+
     */
    static boolean hardResendBudgetExceeded(int totalSends, int softResends, int maxResends) {
        return Math.max(0, totalSends - softResends) > maxResends;
    }

    /**
     * Pure decision: should an exhausted per-packet retransmit budget close the
     * connection?
     *
     * <p>Once a connection has made forward progress (any packet acknowledged) it
     * may carry an in-flight download with app-level state to preserve. A single
     * head-of-line packet that exhausts its budget is then evidence of a
     * TEMPORARY path failure (a hole at a congested hop), not a dead path:
     * tearing the whole stream down forces the application to reconnect and
     * re-transfer, which is the observed "download fails and restarts" churn.
     * Such connections keep the packet in the window and keep retransmitting —
     * they resume — instead of closing. Only a connection that never got a single
     * packet through (connect phase, nothing to resume) keeps the fatal close.
     *
     * <p>The {@code budgetExhausted} input already excludes SYN and CLOSE
     * packets: connect-phase give-up is decided separately
     * ({@link #synGiveUpBudgetExceeded(int, int, int)} with a distinct error),
     * and CLOSE packets are bounded by the dedicated close-resend cap.
     *
     * @param budgetExhausted true if a non-SYN, non-CLOSE packet's hard send
     *                        count has exceeded its retransmit budget
     * @param hasForwardProgress true if any packet has been acknowledged on the
     *                           connection (Connection._highestAckedThrough &gt;= 0)
     * @return true if the connection should close (budget exhausted AND no
     *         forward progress); false if it should resume over closing
     * @since 0.9.71+
     */
    static boolean budgetExhaustionClosesConnection(boolean budgetExhausted, boolean hasForwardProgress) {
        return !hasForwardProgress && budgetExhausted;
    }

    /**
     *  Pure decision: has the retransmit loop exceeded the SYN give-up budget
     *  after excluding soft-failure-triggered resends?
     *
     *  <p>Mirror of {@link #hardResendBudgetExceeded(int, int, int)} for the
     *  SYN-phase give-up.  A SYN resent only because the router reported a soft
     *  failure (e.g. NO_LEASESET while its LeaseSet fetch is in flight) never
     *  reached the tunnel fabric, so it is not evidence of a dead path; counting
     *  it against {@code maxSynSends} lets a burst of router-side soft failures
     *  kill a connect in a few hundred ms with a spurious "SYN not acknowledged".
     *  Only genuine on-wire retransmits should consume the give-up budget.
     *
     * @param totalSends total SYN send attempts incl. resends (PacketLocal.getNumSends())
     * @param softResends of those, resends tagged as soft-failure-triggered
     *                    (PacketLocal.getNumSoftResends())
     * @param maxSynSends per-connection SYN give-up budget (getMaxSynSends())
     * @return true if the non-soft SYN send attempts exceed the budget
     * @since 0.9.71+
     */
    static boolean synGiveUpBudgetExceeded(int totalSends, int softResends, int maxSynSends) {
        return Math.max(0, totalSends - softResends) >= maxSynSends;
    }

    /**
     * Pure decision: should this packet's retransmit be paced through the paced
     * queue rather than sent directly?
     *
     * <p>The first {@link #IMMEDIATE_RETX_BURST} packets of a timer fire are sent
     * directly to avoid flooding the I2CP queue with a single-timer-fire burst.
     * Beyond that, pacing spreads the load. Packets already transmitted
     * {@link #MAX_PACED_RETX} times or more are recovery-critical and always
     * sent directly so they cannot starve behind a stale pacing rate in the
     * paced queue (hard drain deadline).
     *
     * @param burstCount number of direct sends already made in this timer fire
     * @param nResends transmissions this packet has undergone
     * @return true if the retransmit should go through pacing
     * @since 0.9.71+
     */
    static boolean shouldPaceRetx(int burstCount, int nResends) {
        return burstCount >= IMMEDIATE_RETX_BURST && nResends < MAX_PACED_RETX;
    }

    /**
     * Notify all threads waiting in packetSendChoke().
     * Also update pacing rate since window size may have changed.
     */
    void windowAdjusted() {
        synchronized (_outboundPacketsLock) {_outboundPacketsLock.notifyAll();}
        updatePacingRate();
    }

    /** Ack immediately. */
    void ackImmediately() {
        PacketLocal packet;
        // if we don't have anything to retransmit, send a small ACK
        // this calls sendPacket() below
        packet = _receiver.send(null, 0, 0);
        if (_log.shouldDebug()) {_log.debug("Sending new ACK: " + packet);}
    }

    /**
     * Got a packet we shouldn't have, send 'em a reset.
     * More than one reset may be sent.
     */
    private void sendReset() {
        long now = _context.clock().now();
        if (_resetSentOn.get() + 10*1000 > now) {return;} // don't send resets too fast
        if (_resetReceived.get()) {return;}
        // Unconditionally set
        _resetSentOn.set(now);
        Destination remotePeer;
        synchronized(this) {
            remotePeer = _remotePeer;
        }
        if ((remotePeer == null) || (_sendStreamId.get() <= 0)) {return;}
        PacketLocal reply = new PacketLocal(_context, remotePeer, this);
        reply.setFlag(Packet.FLAG_RESET);
        reply.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        reply.setSendStreamId(_sendStreamId.get());
        reply.setReceiveStreamId(_receiveStreamId.get());
        // As of 0.9.20 we do not require FROM
        reply.setLocalPort(_localPort);
        reply.setRemotePort(_remotePort);
        // this just sends the packet - no retries or whatnot
        if (_outboundQueue.enqueue(reply)) {
            _unackedPacketsReceived.set(0);
            _lastSendTime = now;
            resetActivityTimer();
        }
    }

    /**
     * Flush any data that we can. Non-blocking.
     */
    void sendAvailable() {
        // this grabs the data, builds a packet, and queues it up via sendPacket
        try {_outputStream.flushAvailable(_receiver, false);}
        catch (IOException ioe) {
            if (_log.shouldError()) {_log.error("Error flushing available", ioe);}
        }
    }

    /**
     *  Trigger immediate retransmission of unacked packets after a soft failure
     *  (e.g., tunnel expiry, no tunnels). This schedules the retransmit event
     *  with zero delay to retry sending on a new tunnel without waiting for RTO.
     *
     *  <p>Immediate reschedules are rate-limited: when the router soft-fails
     *  every message (STATUS_SEND_FAILURE_NO_LEASESET while the LeaseSet fetch for
     *  a fresh destination is still in flight), each report used to cancel the
     *  retransmit timer and fire IMMEDIATELY.  Back-to-back reports arrived at
     *  the I2CP round-trip rate (~10ms) and each immediate pass bumped the
     *  SYN's send count once, exhausting the SYN give-up budget
     *  (getMaxSynSends()) in ~200ms and killing the connect with a spurious
     *  "SYN not acknowledged" before the LeaseSet could be fetched.  During the
     *  SYN phase the minimum gap is the SYN retransmit interval, so retries are
     *  interval-spaced exactly as the give-up budget math assumes; for
     *  established connections a small fixed floor prevents any spin while still
     *  hopping a replacement tunnel far sooner than the (possibly doubled) RTO.
     *
     *  @since 0.9.70+
     */
    void scheduleSoftFailureRetransmit() {
        synchronized (_outboundPacketsLock) {
            if (_outboundPackets == null || _outboundPackets.isEmpty()) {
                if (_log.shouldDebug()) {
                    _log.debug("[" + this + "] Soft failure but no unacked packets, skipping retransmit");
                }
                return;
            }
        }
        long minSpacing = _highestAckedThrough.get() < 0 ? getSynRetransmitInterval() : SYN_RTO_MIN;
        long now = _context.clock().now();
        if (shouldRateLimitSoftRetransmit(now, _lastSoftFailRetransmit, minSpacing)) {
            // The retransmit timer is already armed at the SYN interval (SYN
            // phase) or the doubled RTO (established), so the deferred retry
            // happens automatically without an I2CP send burst.
            if (_log.shouldDebug()) {
                _log.debug("[" + this + "] soft failure within " + minSpacing +
                           "ms of last immediate retransmit, deferring to the retransmit timer");
            }
            return;
        }
        _lastSoftFailRetransmit = now;
        // Force immediate reschedule, cancelling any pending timer
        // pushBackRTO(0) cannot shorten a running timer (reschedule uses false for useEarliestTime),
        // so we must cancel and re-schedule instead.
        // Mark the resulting pass as soft so the resend loop doesn't count it
        // against the hard retransmit budget (tunnel outage != on-wire loss).
        _softFailureResendPending = true;
        _retransmitEvent.forceRescheduleNow();
        if (_log.shouldInfo()) {
            _log.info("[" + this + "] Scheduled immediate retransmit after soft failure");
        }
    }

    /**
     *  Pure decision: is this soft-failure report too close to the last
     *  permitted immediate retransmit to fire another now?
     *
     *  <p>Derived from {@link #scheduleSoftFailureRetransmit()}; extracted for
     *  testability.  A report at exactly {@code minSpacingMs} after the last is
     *  allowed, mirroring the strict-inequality pacing of the retransmit timer.
     *
     *  @param now wall-clock time of this soft-failure report (ms)
     *  @param lastSoftFailRetransmit wall-clock time of the last permitted
     *         immediate retransmit (ms), 0 if none yet this connection
     *  @param minSpacingMs minimum gap between immediate retransmits (ms)
     *  @return true if the retransmit should be deferred to the existing timer
     *  @since 0.9.71+
     */
    static boolean shouldRateLimitSoftRetransmit(long now, long lastSoftFailRetransmit, long minSpacingMs) {
        if (lastSoftFailRetransmit <= 0) {
            return false;
        }
        return now - lastSoftFailRetransmit < minSpacingMs;
    }

    /**
     *  This sends all 'normal' packets (acks and data) for the first time.
     *  Retransmits are done in ResendPacketEvent below.
     *  Resets, pings, and pongs are done elsewhere in this class,
     *  or in ConnectionManager or ConnectionHandler.
     */
    void sendPacket(PacketLocal packet) {
        if (packet == null) {
            return;
        }

        setNextSendTime(-1);
        if (_options.getRequireFullySigned()) {
            packet.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
            packet.setFlag(Packet.FLAG_SIGNATURE_REQUESTED);
        }

        if ((packet.getSequenceNum() == 0) && (!packet.isFlagSet(Packet.FLAG_SYNCHRONIZE))) {
            // ACK-only
            if (_isChoking) {
                packet.setOptionalDelay(Packet.SEND_DELAY_CHOKE);
                packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            } else if (_unchokesToSend.decrementAndGet() > 0) {
                // don't worry about wrapping around
                packet.setOptionalDelay(0);
                packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            }
            if (_outboundQueue.enqueue(packet)) {
                packet.markEnqueued();
                _unackedPacketsReceived.set(0);
                _lastSendTime = _context.clock().now();
                resetActivityTimer();
            }
            return;
        } else {
            int windowSize;
            int remaining;
            int timeout;
            synchronized (_outboundPacketsLock) {
                TreeMap<Long, PacketLocal> ob = outboundPackets();
                ob.put(Long.valueOf(packet.getSequenceNum()), packet);
                windowSize = _options.getWindowSize();
                remaining = windowSize - ob.size();
                _outboundPacketsLock.notifyAll();

                if (_isChoking) {
                    packet.setOptionalDelay(Packet.SEND_DELAY_CHOKE);
                    packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                } else if (packet.isFlagSet(Packet.FLAG_CLOSE) ||
                    _unchokesToSend.decrementAndGet() > 0 ||
                    // the other end has no idea what our window size is, so
                    // help him out by requesting acks below the 1/3 point,
                    // if remaining < 3, and every 8 minimum.
                    (remaining < 3) ||
                    (remaining < (windowSize + 2) / 3)) {
                    packet.setOptionalDelay(0);
                    packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                }

                timeout = _options.getRTO();
                packet.setTimeout(timeout);
            }

            // Pace or send immediately
            long pacingDelay = calculatePacingDelay(packet.getPayloadSize());
            if (pacingDelay > 0) {
                synchronized (_pacedQueueLock) {
                    LinkedList<PacketLocal> pq = pacedQueue();
                    boolean first = pq.isEmpty();
                    pq.add(packet);
                    if (first) {
                        _pacedEvent.forceReschedule(pacingDelay);
                    }
                }
                packet.markEnqueued();
            } else if (_outboundQueue.enqueue(packet)) {
                packet.markEnqueued();
                _unackedPacketsReceived.set(0);
                _lastSendTime = _context.clock().now();
                synchronized (_pacingLock) {
                    _lastPacketSendTime = _context.clock().now();
                }
                resetActivityTimer();
            }
            // Schedule retransmit timer outside _outboundPacketsLock
            // to prevent deadlock with RetransmitEvent.timeReached()
            // TLP scheduled at ~2*RTT to detect loss before RTO.
            if (_retransmitEvent.scheduleIfNotRunning(timeout)) {
               _tlpEvent.scheduleProbe(getPTO());
            }
        }
    }

    /**
     *  Process the acks and nacks received in a packet.
     *
     *  <p>Note: the returned list is a reused, internally-owned buffer
     *  ({@code _ackedList}) that is cleared at the start of the next
     *  {@code ackPackets()} call. Callers MUST fully consume it before the
     *  next ack is processed. This is safe because acks are serialized on the
     *  receive thread; do not retain a reference across calls.
     *
     *  @return List of packets acked for the first time (empty if none);
     *          a shared mutable buffer, not a fresh copy
     */
    public List<PacketLocal> ackPackets(long ackThrough, long[] nacks) {
        long oldHighest = _highestAckedThrough.get();
        if (nacks == null || nacks.length == 0) {
            _highestAckedThrough.updateAndGet(cur -> Math.max(cur, ackThrough));
        } else {
            long lowest = -1;
            for (int i = 0; i < nacks.length; i++) {
                if ((lowest < 0) || (nacks[i] < lowest)) {lowest = nacks[i];}
            }
            /** New val. */
            final long newVal = lowest - 1;
            _highestAckedThrough.updateAndGet(cur -> Math.max(cur, newVal));
        }

        _ackedList.clear();
        boolean anyLeft = false;
        boolean doPushBack = false;
        boolean doCancel = false;
        boolean doReArmTLP = false;
        boolean doCancelTLP = false;
        int pushBackDelay = 0;
        synchronized (_outboundPacketsLock) {
            TreeMap<Long, PacketLocal> ob = _outboundPackets;
            long prevHead = -1;
            if (ob != null && !ob.isEmpty()) {  // short circuit iterator
                prevHead = ob.firstKey();
                for (Iterator<Map.Entry<Long, PacketLocal>> iter = ob.entrySet().iterator(); iter.hasNext(); ) {
                    Map.Entry<Long, PacketLocal> e = iter.next();
                    long id = e.getKey().longValue();
                    if (id <= ackThrough) {
                        boolean nacked = false;
                        if (nacks != null && nacks.length > 0) {
                            // linear search since its probably really tiny
                            for (int i = 0; i < nacks.length; i++) {
                                if (nacks[i] == id) {
                                    nacked = true;
                                    PacketLocal nackedPacket = e.getValue();
                                    // this will do a fast retransmit if appropriate
                                    nackedPacket.incrementNACKs();
                                    break; // NACKed
                                }
                            }
                        }
                        if (!nacked) { // aka ACKed
                            PacketLocal ackedPacket = e.getValue();
                            ackedPacket.ackReceived();
                            _ackedList.add(ackedPacket);
                            iter.remove();
                        }
                    } else {
                        // Packets > ackThrough are implicitly NACKed; see below for
                        // dup-ACK-based fast retransmit of the first missing packet
                        break; // the window map is ordered
                    }
                } // for
            } // !isEmpty()
            // Dup-ACK fast retransmit (implicit NACK of packets beyond ackThrough).
            // Count consecutive duplicate ACKs; at threshold, retransmit the oldest
            // unacked packet. Only fires when peer sends data (ACKs piggybacked).
            if (ob != null && !ob.isEmpty()) {
                if (nacks == null || nacks.length == 0) {
                    if (ackThrough > oldHighest) {
                        _dupAckCount = 0;
                        // Reset the last dup-ACK marker on forward progress so a stale
                        // value can't spuriously match and trigger fast retransmit on
                        // the first (rather than the third) duplicate ACK.
                        _lastDupAck = -1;
                        if (prevHead > 0 && !ob.isEmpty() && prevHead == ob.firstKey()) {
                            // The head of the window did not move while later packets
                            // were ACKed: the head packet is stuck and a hole has opened.
                            // Re-arm the TLP probe instead of cancelling it so the head
                            // gets retransmitted at ~2*RTT; without this, the RTO (which
                            // RFC 6298 section 5.3 keeps deferring on these trickle ACKs)
                            // may never fire — the mid-download freeze.
                            doReArmTLP = true;
                        }
                    } else {
                        if (ackThrough == _lastDupAck) {
                            _dupAckCount++;
                            if (_dupAckCount >= FAST_RETRANSMIT_THRESHOLD) {
                                Map.Entry<Long, PacketLocal> first = ob.firstEntry();
                                if (first != null && first.getValue().getNumSends() > 0) {
                                    first.getValue().incrementNACKs();
                                }
                                _dupAckCount = 0;
                            }
                        } else {
                            _lastDupAck = ackThrough;
                            _dupAckCount = 1;
                        }
                    }
                } else {
                    _dupAckCount = 0;
                }
            }
            if (!_ackedList.isEmpty()) {
                _ackedPackets.addAndGet(_ackedList.size());
                for (int i = 0; i < _ackedList.size(); i++) {
                    PacketLocal p = _ackedList.get(i);
                    // removed from the window map above in the iterator
                    if (p.getNumSends() > 1) {
                        _activeResends.decrementAndGet();
                        if (_log.shouldDebug()) {
                            _log.debug("Active resend of " + p + " successful -> " + _activeResends + " resends remaining...");
                        }
                    }
                }
                _ackSinceCongestion.set(true);
            }
            if ((ob == null || ob.isEmpty()) && (_activeResends.get() != 0)) {
                if (_log.shouldInfo()) {
                    _log.info("All outbound packets ACKed, clearing " + _activeResends);
                }
                _activeResends.set(0);
            }

            anyLeft = ob != null && !ob.isEmpty();
            _outboundPacketsLock.notifyAll();

            if (_lastCongestionHighestUnacked >= 0 && ackThrough > _lastCongestionHighestUnacked) {
                // The lost window has been fully recovered: consecutive-loss
                // strikes reset so the next loss starts from the gentle tier.
                _lossStrikes = 0;
            }

            if (!_ackedList.isEmpty()) {
                if (anyLeft) {
                    // RFC 6298 section 5.3, but anchored to the OLDEST unacked
                    // packet's deadline instead of 'now'. A trickle of partial
                    // ACKs would otherwise keep deferring the RTO forever (the
                    // freeze bug). Anchor: fire no later than oldestLastSend + RTO.
                    Map.Entry<Long, PacketLocal> first = ob == null ? null : ob.firstEntry();
                    long oldestLastSend = (first != null) ? first.getValue().getLastSend() : -1;
                    long now = _context.clock().now();
                    int rto = _options.getRTO();
                    long deadline = (oldestLastSend > 0) ? Math.max(now, oldestLastSend + rto) : now + rto;
                    pushBackDelay = (int) Math.max(0, deadline - now);
                    doPushBack = true;
                } else {
                    // RFC 6298 section 5.2 — nothing left to retransmit
                    doCancel = true;
                    doCancelTLP = true;
                }
            }
        }
        // Outside the lock: the bandwidth estimator (which has its own lock)
        // and the TLP timer. The timer thread takes _outboundPacketsLock in the
        // opposite order, so these must not run inside the critical section.
        if (!_ackedList.isEmpty()) {
            _bwEstimator.addSample(_ackedList.size());
        }
        if (doReArmTLP) {
            // Forward progress but the head of the window is still stuck: re-arm
            // the TLP probe so the head packet is retransmitted at ~2*RTT instead
            // of waiting on an RTO that section 5.3 push-back keeps deferring.
            _tlpEvent.scheduleProbe(getPTO());
        }
        if (doCancelTLP) {
            _tlpEvent.cancel();
        }
        // Call RetransmitEvent outside _outboundPacketsLock
        // to prevent deadlock with RetransmitEvent.timeReached()
        if (doPushBack) {
            if (pushBackDelay == 0) {
                // Already past the oldest packet's deadline: fire the RTO now
                // rather than deferring again.
                _retransmitEvent.forceRescheduleNow();
            } else {
                _retransmitEvent.pushBackRTOBounded(pushBackDelay);
            }
            if (_log.shouldDebug()) {
                _log.debug("[" + Connection.this + "] Not all packets ACKed, pushing timer out " + pushBackDelay);
            }
        } else if (doCancel) {
            _retransmitEvent.cancel();
            if (_log.shouldDebug()) {
                _log.debug("[" + Connection.this + "] All outstanding packets ACKed, cancelling timer");
            }
        }
        return _ackedList;
    }

    /**
     * Notify the scheduler that an event occurred on this connection.
     */
    void eventOccurred() {
        TaskScheduler sched = _chooser.getScheduler(this);
        long before = System.currentTimeMillis();

        sched.eventOccurred(this);
        long elapsed = System.currentTimeMillis() - before;
        // 250 and warn for debugging
        if ((elapsed > 250) && (_log.shouldWarn())) {
            _log.warn("Took " + elapsed + "ms to pump through " + sched + " on " + toString());
        }
    }

    /**
     *  Called by CPH when a CLOSE packet is sent.
     *  Idempotent: only the first call sets the timestamp.
     */
    public void notifyCloseSent() {
        if (!_closeSentOn.compareAndSet(0, _context.clock().now()) && _log.shouldDebug()) {
            // TODO ackImmediately() after sending CLOSE causes this. Bad?
            _log.debug("Sent more than one CLOSE: " + toString());
        }
        // that's it, wait for notifyLastPacketAcked() or closeReceived()
    }

    /**
     *  Notify that a close was received.
     *  Called by CPH.
     *  May be called multiple times.
     */
    public void closeReceived() {
        if (_closeReceivedOn.compareAndSet(0, _context.clock().now())) {
            _inputStream.closeReceived();
            if (_closeSentOn.get() > 0) {
                // We sent close first, peer acked it
                disconnect(true);
            } else if (!_isInbound && _receiveStreamId.get() <= 0) {
                // Outbound connection that never completed - treat as reset/failure.
                // Set an accurate error so any waitForConnect() caller sees why the
                // remote closed before establishment instead of the generic fallback.
                if (_connectionError == null) {setConnectionError(ERR_CONNECTION_REFUSED);}
                disconnect(false);
            } else {
                // Normal close from peer
                synchronized (_connectLock) {_connectLock.notifyAll();}
            }
        }
    }

    /**
     *  Notify that a close that we sent, and all previous packets, were acked.
     *  Called by CPH. Only call this once.
     *  @since 0.9.9
     */
    public void notifyLastPacketAcked() {
        long cso = _closeSentOn.get();
        if (cso <= 0) {throw new IllegalStateException();}
        // we only create one CLOSE packet so we will only get called once,
        // no need to check
        long cro = _closeReceivedOn.get();
        if (cro > 0 && cro < cso) {disconnect(true);} // received before sent
    }

    /**
     *  Notify that a reset was received.
     *  May be called multiple times.
     */
    public void resetReceived() {
        if (!_resetReceived.compareAndSet(false, true)) {return;}
        _resetReceivedOn.set(_context.clock().now());
        IOException ioe = new I2PSocketException(I2PSocketException.STATUS_CONNECTION_RESET);
        _outputStream.streamErrorOccurred(ioe);
        _inputStream.streamErrorOccurred(ioe);
        _connectionError = "Connection reset";
        synchronized (_connectLock) {_connectLock.notifyAll();}
        // RFC 793 end of section 3.4: We are completely done.
        disconnectComplete();
    }

    /**
     * Check if a reset has been received on this connection.
     *
     * @return true if a reset was received
     */
    public boolean getResetReceived() {return _resetReceived.get();}

    /**
     * Timestamp when a reset was received.
     * @return 0 if not received
     */
    public long getResetReceivedOn() {return _resetReceivedOn.get();}

    /**
     * Check if this is an inbound connection.
     *
     * @return true if inbound
     */
    public boolean isInbound() {return _isInbound;}

    /**
     * Always true at the start, even if we haven't gotten a reply on an
     * outbound connection. Only set to false on disconnect.
     * For outbound, use getHighestAckedThrough() &gt;= 0 also,
     * to determine if the connection is up.
     *
     * In general, this is true until either:
     * - CLOSE received and CLOSE sent and our CLOSE is acked
     * - RESET received or sent
     * - closed on the socket side
     * @return the is connected
     */
    public boolean getIsConnected() {return _connected.get();}

    /**
     * Check if this connection has been hard-disconnected (via RESET).
     *
     * @return true if hard-disconnected
     */
    public boolean getHardDisconnected() {return _hardDisconnected;}

    /**
     * Check if a reset has been sent on this connection.
     *
     * @return true if a reset was sent
     */
    public boolean getResetSent() {return _resetSentOn.get() > 0;}

    /**
     * Timestamp when a reset was sent.
     * @return 0 if not sent
     */
    public long getResetSentOn() {return _resetSentOn.get();}

    /**
     * Timestamp when the disconnect was scheduled.
     * @return 0 if not scheduled
     */
    public long getDisconnectScheduledOn() {return _disconnectScheduledOn.get();}

    /**
     *  Must be called when we are done with this connection.
     *  Enters TIME-WAIT if necessary, and removes from connection manager.
     *  May be called multiple times.
     *  This closes the socket side.
     *  In normal operation, this is called when a CLOSE has been received,
     *  AND a CLOSE has been sent, AND EITHER:
     *  received close before sent close AND our CLOSE has been acked
     *  OR
     *  received close after sent close.
     *
     *  @param cleanDisconnect if true, normal close; if false, send a RESET
     */
    public void disconnect(boolean cleanDisconnect) {
        disconnect(cleanDisconnect, true);
    }

    /**
     *  Must be called when we are done with this connection.
     *  May be called multiple times.
     *  This closes the socket side.
     *  In normal operation, this is called when a CLOSE has been received,
     *  AND a CLOSE has been sent, AND EITHER:
     *  received close before sent close AND our CLOSE has been acked
     *  OR
     *  received close after sent close.
     *
     *  @param cleanDisconnect if true, normal close; if false, send a RESET
     *  @param removeFromConMgr if true, enters TIME-WAIT if necessary.
     *                          if false, MUST call disconnectComplete() later.
     *                          Should always be true unless called from ConnectionManager.
     */
    public void disconnect(boolean cleanDisconnect, boolean removeFromConMgr) {
        if (!_connected.compareAndSet(true, false)) {
            return;
        }
        synchronized (_connectLock) {_connectLock.notifyAll();}

        if (_closeReceivedOn.get() <= 0) {
            _inputStream.closeReceived();
        }

        if (cleanDisconnect) {
            if (_log.shouldDebug()) {
                _log.debug("Clean disconnecting from " + getRemotePeerString() + " -> " +
                           (removeFromConMgr ? "Removed from Connection Manager" : "Not removed from Connection Manager"));
            }
            _outputStream.closeInternal();
        } else {
            _hardDisconnected = true;
            if (_inputStream.getHighestBlockId() >= 0 && !getResetReceived()) {
                // only send a RESET if we ever got a packet (and he didn't RESET us),
                // otherwise don't waste crypto and session tags
                if (_log.shouldWarn()) {
                    _log.warn("Hard disconnecting and sending RESET to " + getRemotePeerString() + " -> " +
                              (removeFromConMgr ? "Removed from Connection Manager" : "Not removed from Connection Manager"));
                }
                sendReset();
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Hard disconnecting from " + getRemotePeerString() + " -> " +
                              (removeFromConMgr ? "Removed from Connection Manager" : "Not removed from Connection Manager"));
                }
            }
            _outputStream.streamErrorOccurred(new IOException("Hard disconnect"));
        }

        if (removeFromConMgr) {
            if (!cleanDisconnect) {disconnectComplete();}
            else {
                long cro = _closeReceivedOn.get();
                long cso = _closeSentOn.get();
                if (cro > 0 && cro < cso && getUnackedPacketsSent() <= 0) {
                    if (_log.shouldInfo()) {
                        _log.info("Rcv close -> send close -> last ACKed, skip TIME-WAIT for " + toString());
                    }
                    // They sent the first CLOSE.
                    // We do not need to enter TIME-WAIT, we are done.
                    // clean disconnect, don't schedule TIME-WAIT
                    // remove conn
                    disconnectComplete();
                } else {scheduleDisconnectEvent();}
            }
        }
    }

    /**
     *  Must be called when we are done with this connection.
     *  Final disconnect. Remove from conn manager.
     *  May be called multiple times.
     */
    public void disconnectComplete() {
        if (!_finalDisconnect.compareAndSet(false, true)) {return;}
        _connected.set(false);
        I2PSocketFull s = _socket;
        if (s != null) {
            s.destroy2();
            _socket = null;
        }
        _outputStream.destroy();
        _receiver.destroy();
        _activityTimer.cancel();
        _retransmitEvent.cancel();
        _tlpEvent.cancel();
        _inputStream.streamErrorOccurred(new IOException("Socket closed"));

        if (_log.shouldInfo()) {_log.info("Connection disconnect complete\n" + toString());}
        _connectionManager.removeConnection(this);
        killOutstandingPackets();
    }

    /**
     *  Cancel and remove all packets awaiting ack
     */
    private void killOutstandingPackets() {
        synchronized (_outboundPacketsLock) {
            TreeMap<Long, PacketLocal> ob = outboundPackets();
            if (ob.isEmpty()) {return;} // short circuit iterator
            for (PacketLocal pl : ob.values()) {pl.cancelled();}
            ob.clear();
            _outboundPacketsLock.notifyAll();
        }
    }

    /**
     *  Schedule the end of the TIME-WAIT state,
     *  but only if not previously scheduled.
     *  Must call either this or disconnectComplete()
     *
     *  @return true if a new event was scheduled; false if already scheduled
     *  @since 0.9.9
     */
    private boolean scheduleDisconnectEvent() {
        if (!_disconnectScheduledOn.compareAndSet(0, _context.clock().now())) {return false;}
        _timer.addEvent(new DisconnectEvent(), getDisconnectTimeout());
        return true;
    }

    /** Disconnect scheduled event. */
    private class DisconnectEvent extends SimpleTimer2.TimedEvent {
        DisconnectEvent() {
            super();
            if (_log.shouldInfo()) {
                _log.info("Disconnect timer initiated on connection to " + getRemotePeerString() + " -> 5 minutes to drop...");
            }
        }
        /**
         * Disconnect the connection when the timer fires.
         */
        public void timeReached() {disconnectComplete();}
    }

    /**
     *  Called from SchedulerImpl
     *
     *  @since 0.9.23 moved here so we can use our timer
     */
    public void scheduleConnectionEvent(long msToWait) {
        _timer.addEvent(_connectionEvent, msToWait);
    }

    /**
     *  Schedule an event on our timer.
     *  The event should use the no-arg constructor; the pool is set here.
     *
     *  @return the reusable AckDupEvent for duplicate ACK scheduling
     *  @since 0.9.23
     */
    AckDupEvent getAckDupEvent() { return _ackDupEvent; }

    /** Destination of the remote peer.
     * @return peer Destination or null if unset
     */
    public synchronized Destination getRemotePeer() {return _remotePeer;}

    /** Remote peer string. */
    private synchronized String getRemotePeerString() {
        if (_remotePeer != null) {return "[" + _remotePeer.calculateHash().toBase32().substring(0,8) + "]";}
        else {return "[Unknown]";}
    }

    /**
     *  Remote peer of this connection, non-null.
     *  @param peer non-null
     */
    public void setRemotePeer(Destination peer) {
        if (peer == null) {throw new NullPointerException();}
        synchronized(this) {
            if (_remotePeer != null) {
                throw new IllegalStateException("Remote peer already set [" + _remotePeer + ", " + peer + "]");
            }
            _remotePeer = peer;
        }
        // now that we know who the other end is, get the rtt etc. from the cache
        _connectionManager.updateOptsFromShare(this);
    }

    /**
     *  The key to verify signatures with.
     *  The transient SPK if previously received,
     *  else getRemotePeer().getSigningPublicKey() if previously received,
     *  else null.
     *
     *  @return peer Destination or null if unset
     *  @since 0.9.39
     */
    public synchronized SigningPublicKey getRemoteSPK() {
        if (_transientSPK != null) {return _transientSPK;}
        if (_remotePeer != null) {return _remotePeer.getSigningPublicKey();}
        return null;
    }

    /**
     *  Transient signing public key of the remote peer.
     *  @param transientSPK null ok
     *  @since 0.9.39
     */
    public void setRemoteTransientSPK(SigningPublicKey transientSPK) {
        synchronized(this) {
            if (_transientSPK != null) {
                throw new IllegalStateException("Remote Signing Public Key already set");
            }
            _transientSPK = transientSPK;
        }
    }

    /**
     *  What stream do we send data to the peer on?
     *  @return non-global stream sending ID, or 0 if unknown
     */
    public long getSendStreamId() {return _sendStreamId.get();}

    /**
     *  Stream ID that we send data on.
     *  @param id 0 to 0xffffffff
     *  @throws IllegalStateException if already set to nonzero
     */
    public void setSendStreamId(long id) {
        if (!_sendStreamId.compareAndSet(0, id)) {
            throw new IllegalStateException("Send Stream ID already set [" + _sendStreamId + ", " + id + "]");
        }
        _connectionManager.registerOutboundId(this);
    }

    /**
     *  The stream ID of a peer connection that sends data to us, or zero if unknown.
     *  @return receive stream ID, or 0 if unknown
     */
    public long getReceiveStreamId() {return _receiveStreamId.get();}

    /**
     *  Stream ID that the peer sends data on.
     *  @param id 0 to 0xffffffff
     *  @throws IllegalStateException if already set to nonzero
     */
    public void setReceiveStreamId(long id) {
        if (!_receiveStreamId.compareAndSet(0, id)) {
            throw new IllegalStateException("Receive Stream ID already set [" + _receiveStreamId + ", " + id + "]");
        }
        synchronized (_connectLock) {_connectLock.notifyAll();}
    }

    /** When did we last send anything to the peer?
     * @return Last time we sent data
     */
    public long getLastSendTime() {return _lastSendTime;}

    /** What was the last packet Id sent to the peer?
     * @return The last sent packet ID
     */
    public long getLastSendId() {return _lastSendId.get();}
    /**
     * Retrieve the current ConnectionOptions.
     * @return the current ConnectionOptions, non-null
     */
    public ConnectionOptions getOptions() {return _options;}
    /**
     * ConnectionOptions for this connection.
     * @param opts ConnectionOptions non-null
     */
    public void setOptions(ConnectionOptions opts) {_options = opts;}

    /** @since 0.9.21 */
    public ConnectionManager getConnectionManager() {return _connectionManager;}

    /**
     * I2P session for this connection.
     *
     * @return the session
     */
    public I2PSession getSession() {return _session;}

    /**
     * Socket associated with this connection.
     *
     * @return the socket, or null if not yet set
     */
    public I2PSocketFull getSocket() {return _socket;}

    /**
     * Socket associated with this connection.
     *
     * @param socket the socket
     */
    public void setSocket(I2PSocketFull socket) {_socket = socket;}

    /**
     *  The remote port.
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getPort() {return _remotePort;}

    /**
     *  Local port of this connection.
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getLocalPort() {return _localPort;}

    /**
     * Connection error message, if any.
     *
     * @return error message, or null if no error
     */
    public String getConnectionError() {return _connectionError;}

    /**
     * Connection error message.
     *
     * @param err the error message
     */
    public void setConnectionError(String err) {_connectionError = err;}

    /**
     * Lifetime of this connection in milliseconds.
     *
     * @return connection lifetime in ms
     */
    public long getLifetime() {
        long cso = _closeSentOn.get();
        if (cso <= 0) {return _context.clock().now() - _createdOn;}
        else {return cso - _createdOn;}
    }

    /**
     * Packet handler for this connection.
     *
     * @return the packet handler
     */
    public ConnectionPacketHandler getPacketHandler() {return _handler;}

    /**
     * Total bytes sent on this connection.
     *
     * @return lifetime bytes sent
     */
    public long getLifetimeBytesSent() {return _lifetimeBytesSent.get();}

    /**
     * Total bytes received on this connection.
     *
     * @return lifetime bytes received
     */
    public long getLifetimeBytesReceived() {return _lifetimeBytesReceived.get();}

    /**
     * Total duplicate messages sent on this connection.
     *
     * @return lifetime duplicate messages sent
     */
    public long getLifetimeDupMessagesSent() {return _lifetimeDupMessageSent.get();}

    /**
     * Total duplicate bytes sent on this connection.
     *
     * @return lifetime duplicate bytes sent
     */
    public long getLifetimeDupBytesSent() {return _lifetimeDupBytesSent.get();}

    /**
     * Total duplicate messages received on this connection.
     *
     * @return lifetime duplicate messages received
     */
    public long getLifetimeDupMessagesReceived() {return _lifetimeDupMessageReceived.get();}

    /**
     * Increment the lifetime bytes sent counter.
     *
     * @param bytes number of bytes to add
     */
    public void incrementBytesSent(int bytes) {_lifetimeBytesSent.addAndGet(bytes);}

    /**
     * Increment the lifetime duplicate messages sent counter.
     *
     * @param msgs number of duplicate messages to add
     */
    public void incrementDupMessagesSent(int msgs) {_lifetimeDupMessageSent.addAndGet(msgs);}

    /**
     * Increment the lifetime duplicate bytes sent counter.
     *
     * @param bytes number of duplicate bytes to add
     */
    public void incrementDupBytesSent(int bytes) {_lifetimeDupBytesSent.addAndGet(bytes);}

    /**
     * Increment the lifetime bytes received counter.
     *
     * @param bytes number of bytes to add
     */
    public void incrementBytesReceived(int bytes) {_lifetimeBytesReceived.addAndGet(bytes);}

    /**
     * Increment the lifetime duplicate messages received counter.
     *
     * @param msgs number of duplicate messages to add
     */
    public void incrementDupMessagesReceived(int msgs) {_lifetimeDupMessageReceived.addAndGet(msgs);}

    /**
     * Time when the scheduler next want to send a packet, or -1 if
     * never.  This should be set when we want to send on timeout, for
     * instance, or want to delay an ACK.
     * @return the next time the scheduler will want to send a packet, or -1 if never.
     */
    public long getNextSendTime() {
        synchronized(_nextSendLock) {return _nextSendTime;}
    }

    /**
     *  If the next send time is currently &gt;= 0 (i.e. not "never"),
     *  this may make the next time sooner but will not make it later.
     *  If the next send time is currently &lt; 0 (i.e. "never"),
     *  this will set it to the time specified, but not later than
     *  options.getSendAckDelay() from now (1000 ms)
     */
    public void setNextSendTime(long when) {
        synchronized(_nextSendLock) {
            if (_nextSendTime >= 0) {
                if (when < _nextSendTime) {_nextSendTime = when;}
            } else {_nextSendTime = when;}

            if (_nextSendTime >= 0) {
                long max = _context.clock().now() + _options.getSendAckDelay();
                if (max < _nextSendTime) {_nextSendTime = max;}
            }
        }
    }

    /**
     *  Choking state toward the other side.
     *  If on is true or the value has changed, this will call ackImmediately().
     *  @param on true for choking
     *  @since 0.9.29
     */
    public void setChoking(boolean on) {
        if (on != _isChoking) {
            _isChoking = on;
            if (_log.shouldWarn()) {_log.warn("Choking changed to " + on + " on " + this);}
            if (!on) {_unchokesToSend.set(UNCHOKES_TO_SEND);}
            _lastChokeAckTime = _context.clock().now();
            ackImmediately();
        } else if (on) {
            // Re-assert an active choke: re-notify the peer in case the prior choke
            // ACK was lost, otherwise the peer may resume sending. Rate-limit this
            // like a TCP persist timer so a persistently choked peer does not make
            // us emit a stream of redundant signed ACKs (wasted CPU and session tags).
            long now = _context.clock().now();
            if (now - _lastChokeAckTime >= _options.getSendAckDelay()) {
                _lastChokeAckTime = now;
                ackImmediately();
            }
        }
    }

    /**
     *  Choked state set by the other side.
     *  @param on true for choked
     *  @since 0.9.29
     */
    public void setChoked(boolean on) {
        if (on != _isChoked) {
           _isChoked = on;
           if (_log.shouldWarn()) {_log.warn("Choked changed to " + on + " on " + this);}
           if (!on) {
               // Episode boundary for the persist WARN gate: once the remote
               // unchokes us, a fresh persist episode in a later window may log.
               _lastPersistWarnTime = 0;
               windowAdjusted();
           }
        }
        if (on) {
            congestionOccurred();
            /*
             * https: *en.wikipedia.org/wiki/Transmission_Control_Protocol
             * When a receiver advertises a window size of 0, the sender stops sending data and starts the persist timer.
             *
             * The persist timer is used to protect TCP from a deadlock situation that could arise if a subsequent window
             * size update from the receiver is lost, and the sender cannot send more data until receiving a new window
             * size update from the receiver.
             *
             * When the persist timer expires, the TCP sender attempts recovery by sending a small packet
             * so that the receiver responds by sending another acknowledgement containing the new window size.
             *
             * We don't do any of that, but we set the window size to 1, and let the retransmission
             * of packets do the "attempted recovery".
             */
            _options.setWindowSize(1);
            updatePacingRate(); // Update pacing when window changes
        }
    }

    /**
     *  Is the other side choking us?
     *  @return if choked
     *  @since 0.9.29
     */
    public boolean isChoked() {return _isChoked;}

    /** How many packets have we sent and the other side has ACKed?
     * @return Count of how many packets ACKed.
     */
    public long getAckedPackets() {return _ackedPackets.get();}
    /**
     * Timestamp when this connection was created.
     *
     * @return creation timestamp
     */
    public long getCreatedOn() {return _createdOn;}

    /**
     * Timestamp when a close was sent.
     * @return 0 if not sent
     */
    public long getCloseSentOn() {return _closeSentOn.get();}

    /**
     * Timestamp when a close was received.
     * @return 0 if not received
     */
    public long getCloseReceivedOn() {return _closeReceivedOn.get();}

    /**
     * Update shared options from the TCB cache.
     */
    public void updateShareOpts() {
        if (_closeSentOn.get() > 0 && !_updatedShareOpts) {
            _connectionManager.updateShareOpts(this);
            _updatedShareOpts = true;
        }
    }

    /**
     * Increment the count of unacked packets received.
     */
    public void incrementUnackedPacketsReceived() {_unackedPacketsReceived.incrementAndGet();}

    /**
     * Count of unacked packets received.
     *
     * @return number of unacked packets received
     */
    public int getUnackedPacketsReceived() {return _unackedPacketsReceived.get();}

    /** How many packets have we sent but not yet received an ACK for?
     * @return Count of packets in-flight.
     */
    public int getUnackedPacketsSent() {
        synchronized (_outboundPacketsLock) {return outboundSizeLocked();}
    }

    /**
     * For ConnectionPacketHandler.adjustWindow()
     *
     * @since 0.9.71
     */
    public Object getWindowLock() {return _outboundPacketsLock;}

    /**
     * Lazily allocate the sender-side unacked-window map.
     * Caller must hold {@link #_outboundPacketsLock}.
     *
     * @return the non-null window map
     * @since 0.9.71+
     */
    private TreeMap<Long, PacketLocal> outboundPackets() {
        if (_outboundPackets == null)
            _outboundPackets = new TreeMap<>();
        return _outboundPackets;
    }

    /**
     * Null-safe window size.
     * Caller must hold {@link #_outboundPacketsLock}.
     *
     * @return 0 when the window was never allocated
     * @since 0.9.71+
     */
    private int outboundSizeLocked() {
        return _outboundPackets == null ? 0 : _outboundPackets.size();
    }

    /**
     * Lazily allocate the paced-send queue.
     * Caller must hold {@link #_pacedQueueLock}.
     *
     * @return the non-null paced queue
     * @since 0.9.71+
     */
    private LinkedList<PacketLocal> pacedQueue() {
        if (_pacedQueue == null)
            _pacedQueue = new LinkedList<>();
        return _pacedQueue;
    }

    /**
     * Congestion window end sequence number.
     *
     * @return the congestion window end
     */
    public long getCongestionWindowEnd() {return _congestionWindowEnd;}

    /**
     * Congestion window end sequence number.
     *
     * @param endMsg the new congestion window end
     */
    public void setCongestionWindowEnd(long endMsg) {_congestionWindowEnd = endMsg;}

    /**
     * Highest outbound packet we have received an ack for.
     * @return the highest outbound packet we have received an ack for
     */
    public long getHighestAckedThrough() {return _highestAckedThrough.get();}

    /**
     * Timestamp of the last send or receive activity.
     *
     * @return the later of last send time and last receive time
     */
    public long getLastActivityOn() {
        return (_lastSendTime > _lastReceivedOn ? _lastSendTime : _lastReceivedOn);
    }

    /** Congestion occurred. */
    private void congestionOccurred() {
        // Record the highest unacked packet ID at the time of congestion.
        // This is used by ResendPacketEvent to determine whether to shrink
        // the window — only the first loss in a window triggers reduction
        // to avoid multiple reductions for correlated losses.
        if (_ackSinceCongestion.compareAndSet(true, false)) {
            _lastCongestionHighestUnacked = _lastSendId.get();
        }
    }

    /**
     * Called when a packet is received on this connection.
     * Updates the last received timestamp and resets the activity timer.
     */
    void packetReceived() {
        _lastReceivedOn = _context.clock().now();
        resetActivityTimer();
        synchronized (_connectLock) {_connectLock.notifyAll();}
    }

    /**
     * Wait for the connection to be established, with the option's connectTimeout.
     */
     void waitForConnect() {
         waitForConnect(0);
     }

    /**
     * Wait for the connection to be established, but no longer than timeoutMs.
     * If the connection fails or the timeout is exceeded, sets _connectionError.
     * @param timeoutMs max wait in ms; if &lt;= 0 uses the connection option's connectTimeout
     */
      void waitForConnect(int timeoutMs) {
          long desired = timeoutMs > 0 ? timeoutMs : _options.getConnectTimeout();
          boolean hasTimeout = desired > 0;
          // Apply network-condition multiplier from the Tuner so fast networks
          // fail fast and slow networks have enough time for retransmits, and keep
          // the same computation available to the SYN give-up budget (getMaxSynSends()).
          long totalTimeout = hasTimeout ? computeEffectiveConnectTimeout(desired,
                              getConnectTimeoutMultiplier(), getMaxConnectTimeout()) : 0;
          long expiry = hasTimeout ? _context.clock().now() + totalTimeout : 0;
          long start = _context.clock().now();
          if (_log.shouldInfo()) {
              _log.info("waitForConnect() starting for " + _remotePeer + " (timeout=" + (hasTimeout ? totalTimeout : 0) + "ms)");
          }
          while (true) {
              if (_connected.get() && (_receiveStreamId.get() > 0) && (_sendStreamId.get() > 0)) {
                  if (_log.shouldInfo()) {
                      _log.info("waitForConnect() connected in " + (_context.clock().now() - start) + "ms for " + _remotePeer);
                  }
                  return;
              }
              if (_connectionError != null) {
                  return;
              }
              if (!_connected.get()) {
                  if (_connectionError == null) {
                      _connectionError = "Connection failed";
                  }
                  return;
              }
              if (hasTimeout) {
                  long timeLeft = expiry - _context.clock().now();
                  if (timeLeft <= 0) {
                      if (_connectionError == null) {
                          _connectionError = ERR_CONNECTION_TIMED_OUT;
                          disconnect(false);
                      }
                      if (_log.shouldInfo()) {
                          _log.info("waitForConnect() timed out after " + (_context.clock().now() - start) + "ms for " + _remotePeer);
                      }
                      return;
                  }
                  try {
                      synchronized (_connectLock) { _connectLock.wait(Math.min(timeLeft, 1000)); }
                  } catch (InterruptedException ie) {
                      _connectionError = "InterruptedException";
                      Thread.currentThread().interrupt();
                      return;
                  }
              } else {
                  try {
                      synchronized (_connectLock) { _connectLock.wait(60000); }
                  } catch (InterruptedException ie) {
                      _connectionError = "InterruptedException";
                      Thread.currentThread().interrupt();
                      return;
                  }
              }
          }
      }

    /** Reset activity timer. */
    private void resetActivityTimer() {
        long howLong = _options.getInactivityTimeout();
        if (howLong <= 0) {return;}
        howLong += _randomWait; // randomize it a bit, so both sides don't do it at once
        _activityTimer.reschedule(howLong, false); // use the later of current and previous timeout
    }

    /** Activity timer. */
    private class ActivityTimer extends SimpleTimer2.TimedEvent {
        /**
         * ActivityTimer.
         */
        public ActivityTimer() {
            super(_timer);
            setFuzz(5*1000); // sloppy timer, don't reschedule unless at least 5s later
        }
        /**
         * Perform the configured inactivity action.
         */
        public void timeReached() {
            if (_log.shouldDebug()) {
                _log.debug("Invoking inactivity timer on connection to " + getRemotePeerString() + "...");
            }
            // uh, nothing more to do...
            if (!_connected.get()) {
                if (_log.shouldDebug()) {
                    _log.debug("Inactivity timeout reached, but we are already closed!");
                }
                return;
            }
            // we got rescheduled already
            long left = getTimeLeft();
            if (left > 0) {
                if (_log.shouldDebug()) {
                    _log.debug("Inactivity timeout reached on connection to " + getRemotePeerString() +
                               " but there is time left (" + left + "ms)");
                }
                schedule(left);
                return;
            }
            // these are either going to time out or cause further rescheduling
            if (getUnackedPacketsSent() > 0) {
                if (_log.shouldDebug()) {
                    _log.debug("Inactivity timeout reached on connection to " + getRemotePeerString() +
                               " but there are unACKed packets!");
                }
                return;
            }
            // this shouldn't have been scheduled
            if (_options.getInactivityTimeout() <= 0) {
                if (_log.shouldDebug()) {
                    _log.debug("Inactivity timeout reached on connection to " +
                               getRemotePeerString() + " but there is no timer!");
                }
                return;
            }

            if (_log.shouldDebug()) {
                _log.debug("Inactivity timeout reached on connection to " + getRemotePeerString() +
                           " -> " + _options.getInactivityAction());
            }

            // bugger it, might as well do the hard work now
            switch (_options.getInactivityAction()) {
                case ConnectionOptions.INACTIVITY_ACTION_NOOP:
                    if (_log.shouldInfo()) {
                        _log.info("Inactivity timer expired on connection to " + getRemotePeerString() +
                                  " -> Not doing anything!");
                    }
                    break;
                case ConnectionOptions.INACTIVITY_ACTION_SEND:
                    if (_closeSentOn.get() <= 0 && _closeReceivedOn.get() <= 0) {
                        if (_log.shouldInfo()) {
                            _log.info("Sending some data to " + getRemotePeerString() + " due to inactivity...");
                        }
                        _receiver.send(null, 0, 0, true);
                        break;
                    } // else fall through
                case ConnectionOptions.INACTIVITY_ACTION_DISCONNECT:
                    // fall through
                default:
                    if (_log.shouldInfo()) {
                        int timeout = _options.getInactivityTimeout() / 1000;
                        _log.info("Closing INACTIVE connection to " + getRemotePeerString() + " -> " + timeout + "s timeout reached");
                    }
                    if (_log.shouldDebug()) {
                        StringBuilder buf = new StringBuilder(128);
                        long now = _context.clock().now();
                        buf.append("Last sent packet: ").append(now - _lastSendTime);
                        buf.append("ms ago, last received: ").append(now -_lastReceivedOn);
                        buf.append("ms ago -> Inactivity timeout is: ").append(_options.getInactivityTimeout());
                        _log.debug(buf.toString());
                    }

                    IOException ioe = new IOException("Inactivity timeout");
                    _inputStream.streamErrorOccurred(ioe);
                    _outputStream.streamErrorOccurred(ioe);
                    // Clean disconnect if we have already scheduled one
                    // (generally because we already sent a close)
                    disconnect(_disconnectScheduledOn.get() > 0);
                    break;
            }
        }

        /**
         * Time left before the inactivity timeout.
         * @return the time left
         */
        public final long getTimeLeft() {
            if (getLastActivityOn() > 0) {
                return getLastActivityOn() + _options.getInactivityTimeout() - _context.clock().now();
            } else {
                return _createdOn + _options.getInactivityTimeout() - _context.clock().now();
            }
        }
    }

    /**
     * Input stream that the local peer receives data on.
     *
     * @return the inbound message stream, non-null
     */
    public MessageInputStream getInputStream() {return _inputStream;}

    /**
     * Output stream that the local peer sends data to the remote peer on.
     *
     * @return the outbound message stream, non-null
     */
    public MessageOutputStream getOutputStream() {return _outputStream;}

    /**
     * Human-readable summary of this connection.
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("Connection: ");
        long id = _receiveStreamId.get();
        if (id > 0) {buf.append(Packet.toId(id));}
        else {buf.append("Unknown");}
        buf.append('/');
        id = _sendStreamId.get();
        if (id > 0) {buf.append(Packet.toId(id));}
        else {buf.append("Unknown");}
        if (_isInbound) {buf.append(" from ");}
        else {buf.append(" to ");}
        Destination remotePeer = getRemotePeer();
        if (remotePeer != null) {buf.append("[").append(remotePeer.calculateHash().toBase32().substring(0,8)).append("]");}
        else {buf.append("Unknown");}
        long now = _context.clock().now();
        if  (_log.shouldInfo()) {
            buf.append("\n* Up: ").append(DataHelper.formatDuration(now - _createdOn));
            buf.append("; Window size: ").append(_options.getWindowSize());
            buf.append("; Congestion window: ").append(_congestionWindowEnd - _highestAckedThrough.get());
            buf.append("; RTT: ").append(_options.getRTT());
            buf.append("; RTO: ").append(_options.getRTO());
            // not synchronized to avoid some kooky races
            buf.append("; UnACKed out: ").append(_outboundPackets == null ? 0 : _outboundPackets.size()).append("; ");
            buf.append("UnACKed in: ").append(getUnackedPacketsReceived());
            int missing = 0;
            long[] nacks = _inputStream.getNacks();
            if (nacks != null) {
                missing = nacks.length;
                buf.append(" [").append(missing).append(" missing]");
            }
            buf.append("\n* Sent: ").append(1 + _lastSendId.get());
            buf.append("; Received: ").append(1 + _inputStream.getHighestBlockId() - missing);
            buf.append("; ACKThru: ").append(_highestAckedThrough.get());
            buf.append("; SSThresh: ").append(_ssthresh);
            buf.append("; MinRTT: ").append(_options.getMinRTT());
            buf.append("; MaxWin: ").append(_options.getMaxWindowSize());
            buf.append("; MTU: ").append(_options.getMaxMessageSize());
            if (getResetSent())
                buf.append("\n* Reset sent: ").append(DataHelper.formatDuration(now - getResetSentOn())).append(" ago");
            if (getResetReceived())
                buf.append("\n* Reset received: ").append(DataHelper.formatDuration(now - getResetReceivedOn())).append(" ago");
            if (getCloseSentOn() > 0) {
                buf.append("\n* Close sent: ");
                long timeSinceClose = now - getCloseSentOn();
                buf.append(DataHelper.formatDuration(timeSinceClose));
                buf.append(" ago");
            }
            if (getCloseReceivedOn() > 0)
                buf.append("\n* Close received: ").append(DataHelper.formatDuration(now - getCloseReceivedOn())).append(" ago");
        }
        return buf.toString();
    }

    /**
     *  Tail Loss Probe: fire at ~2*RTT to detect loss before the RTO timer.
     *  Re-sends the oldest unacked packet as a probe. If the original was lost,
     *  the probe fills the gap and the peer's ACK triggers fast recovery.
     *  If the original was delivered, the duplicate triggers ackImmediately().
     *
     *  Re-armed in ackPackets() whenever the head of the window is stuck while
     *  later packets are ACKed, so a single lost head-of-line packet is probed
     *  at ~2*RTT instead of waiting on an RTO that partial-ACK push-back keeps
     *  deferring. Does NOT trigger congestion control (probe may not be lost).
     */
    private class TLProbeEvent extends SimpleTimer2.TimedEvent {
        /** Whether a probe is already scheduled. */
        private volatile boolean _pending;

        TLProbeEvent() { super(_timer); }

        /** Schedule probe. */
        synchronized void scheduleProbe(int delayMs) {
            if (!_pending) {
                _pending = true;
                schedule(delayMs);
            }
        }

        /**
         * Cancel the pending probe, if any.
         */
        @Override
        public synchronized boolean cancel() {
            _pending = false;
            return super.cancel();
        }

        /**
         * Send the tail loss probe when the timer fires.
         */
        @Override
        public void timeReached() {
            _pending = false;
            sendTLProbe();
        }
    }

    /**
     *  Send a tail loss probe: re-enqueue the oldest unacked packet.
     *  No congestion control — TLP is a probe, not a confirmed loss.
     */
    private void sendTLProbe() {
        PacketLocal oldest = null;
        synchronized (_outboundPacketsLock) {
            TreeMap<Long, PacketLocal> ob = _outboundPackets;
            if (ob != null) {
                for (Map.Entry<Long, PacketLocal> e : ob.entrySet()) {
                    PacketLocal p = e.getValue();
                    // Skip packets already acked or cancelled (payload released back
                    // to the pool): resending a released packet is use-after-release.
                    if (p.getAckTime() > 0 || p.writeReleased())
                        continue;
                    oldest = p;
                    break;
                }
            }
            if (oldest == null) return;
        }
        if (_outboundQueue.enqueue(oldest)) {
            _unackedPacketsReceived.set(0);
            _lastSendTime = _context.clock().now();
            resetActivityTimer();
            if (_log.shouldInfo()) {
                _log.info("TLP sent for seq " + oldest.getSequenceNum() + " on " + Connection.this);
            }
        }
    }

    /**
     *  A single retransmit timer for all packets.
     *  See RFCs 5681 and 6298.
     *
     *  @since 0.9.46
     */
    class RetransmitEvent extends SimpleTimer2.TimedEvent {

        /** Whether the timer is active. */
        private boolean _scheduled;

        /** Retransmit event */
        RetransmitEvent() {super(_timer);}

        /**
         * Cancel the pending retransmission timer.
         */
        @Override
        public synchronized boolean cancel() {
            _scheduled = false;
            _softFailureResendPending = false;
            return super.cancel();
        }

        /**
         * Schedule the retransmit timer if it is not already running.
         */
        public synchronized boolean scheduleIfNotRunning(long delay) {
            if (_scheduled) {return false;}
            _scheduled = true;
            schedule(delay);
            return true;
        }

        /**
         *  Force immediate reschedule with minimum delay, cancelling any
         *  pending timer. Used by scheduleSoftFailureRetransmit() so that
         *  a soft-failure notification triggers a retransmit without
         *  waiting for the current RTO to elapse.
         *
         *  Unlike pushBackRTO(), this always schedules a new timer event
         *  rather than relying on reschedule(..., false) which won't
         *  shorten a running timer.
         */
        public synchronized void forceRescheduleNow() {
            super.cancel();
            _scheduled = true;
            schedule(0);
        }

        /**
         * Push back the retransmission timeout to the given RTO.
         */
        public synchronized void pushBackRTO(int rto) {
            if (!_scheduled) {
                _scheduled = true;
                schedule(rto);
            } else {
                reschedule(rto, false);
            }
        }

        /**
         *  Push back the retransmission timer to the given DELAY FROM NOW, but
         *  allow the fire time to move EARLIER if the new deadline is earlier
         *  than the currently scheduled one. RFC 6298 section 5.3 restarts the
         *  timer at the full RTO on every new ACK; with trickle ACKs that keeps
         *  deferring an overdue head-of-line retransmission indefinitely (the
         *  mid-download freeze). Callers anchor the delay to the oldest unacked
         *  packet's send time + RTO so the timer is guaranteed to fire.
         *
         *  @param delayMs delay from now (the anchored deadline minus now); a
         *                 value &lt;= 0 forces an immediate fire
         */
        public synchronized void pushBackRTOBounded(int delayMs) {
            if (delayMs <= 0) {
                forceRescheduleNow();
            } else if (!_scheduled) {
                _scheduled = true;
                schedule(delayMs);
            } else {
                reschedule(delayMs, true);
            }
        }

        /**
         * Retransmit packets and adjust congestion state when the timer fires.
         */
        @Override
        public void timeReached() {
            _scheduled = false;
            // Is this pass soft-failure-triggered?  If so, resends of unacked
            // packets are tagged as soft and don't consume the hard retransmit
            // budget (see hardResendBudgetExceeded()).
            final boolean softPass = _softFailureResendPending;
            _softFailureResendPending = false;

           if (_resetSentOn.get() > 0 || _resetReceived.get() || _finalDisconnect.get()) {
                if (_log.shouldDebug()) {
                    _log.debug(Connection.this + " rtx event after close or reset");
                }
                return;
            }

            // Hard liveness backstop: if the oldest unacked packet has been in flight
            // (never acknowledged) beyond the worst-case retransmit budget,
            // measured from CREATION, recovery is dead. Anchoring at the packet's
            // creation rather than its last transmission matters for established
            // connections in resume mode below: they keep retransmitting a
            // budget-exhausted head-of-line packet every RTO, refreshing its last
            // send time, so a last-send-anchored test would never fire. The
            // creation anchor gives those connections a fixed wall-clock deadline
            // so a genuinely dead path is closed instead of retried forever.
            // (A packet cancelled on a reset/close but never removed from the
            // window map — the original zombie this guarded — is handled by the
            // stale-removal guard in the resend loop.)
            //
            // A second, tighter bound is the remote silence window: if the remote
            // has sent NOTHING (data or ACK) for a full inactivity timeout while a
            // packet sits unacked in the window, holding on longer cannot succeed.
            // A live remote that received our data ACKs it within the ack window,
            // so total silence means our sends are not arriving — the path is dead
            // or the remote has gone (and per the protocol its own inactivity
            // timer, default 120s, has closed it). Anchoring at the last RECEIVED
            // packet keeps resume semantics intact for holes shorter than the hold
            // window and replaces the open-ended creation-anchored wait (30 sends
            // x 30s maxRTO = 15 min by default) with a bound aligned to the
            // protocol's inactivity constant: a stalled stream that neither
            // delivers nor receives gives the application EOF within ~2 min
            // instead of lingering on a zombie it can never resume.
            synchronized (_outboundPacketsLock) {
                TreeMap<Long, PacketLocal> ob = _outboundPackets;
                Map.Entry<Long, PacketLocal> first = ob == null ? null : ob.firstEntry();
                if (first != null) {
                    long now = _context.clock().now();
                    if (stuckLifetimeExceeded(_options.getMaxResends(),
                                              ConnectionOptions.getMaxRTOStatic(),
                                              now,
                                              first.getValue().getCreatedOn())) {
                        if (_log.shouldWarn()) {
                            _log.warn(Connection.this + " oldest unacked packet stuck without progress, forcing disconnect");
                        }
                        if (_connectionError == null) {setConnectionError(ERR_RETRANSMIT_LIMIT);}
                        disconnect(false);
                        return;
                    }
                    if (remoteSilentTooLong(_lastReceivedOn,
                                            _options.getInactivityTimeout(),
                                            now)) {
                        if (_log.shouldWarn()) {
                            _log.warn(Connection.this + " remote silent for the inactivity window, forcing disconnect");
                        }
                        if (_connectionError == null) {setConnectionError(ERR_RETRANSMIT_LIMIT);}
                        disconnect(false);
                        return;
                    }
                }
            }

            if (_log.shouldDebug()) {
                _log.debug(Connection.this + " rtx timer timeReached()");
            }

            congestionOccurred();

            // 1. RTO backoff: for established connections (ACK received), double RTO
            //    per RFC 6298 sec 5.5-5.6. For SYN-phase connections, no congestion
            //    to manage — keep RTO fixed so each retry gets the same window.
            //    SYN retries are bounded by getMaxSynSends(), scaled so the budget
            //    (sends * interval) covers the connect() window.
            if (_highestAckedThrough.get() >= 0) {
                pushBackRTO(_options.doubleRTO());
                // Dark connection: re-arm the tail loss probe so a re-lost
                // head-of-line packet is detected at ~2*RTT instead of waiting
                // on the next, pushed-back RTO. Forward progress in ackPackets()
                // still cancels/re-arms the probe as before.
                _tlpEvent.scheduleProbe(getPTO());
            } else {
                // SYN phase: fixed interval, no backoff. Evidence-gated + RTT-aware so the
                // interval tracks the measured path (packing more attempts into the connect
                // window on a healthy but slower-than-default fabric) instead of always the
                // 5000ms default; a path with no RTT evidence keeps the default unchanged.
                pushBackRTO(getSynRetransmitInterval());
            }

            // 2. cut ssthresh to bandwidth estimate, window to 1
            List<PacketLocal> toResend = null;
            synchronized(_outboundPacketsLock) {
                TreeMap<Long, PacketLocal> ob = _outboundPackets;
                Map.Entry<Long, PacketLocal> e = ob == null ? null : ob.firstEntry();
                if (e == null) {
                    if (_log.shouldWarn()) {
                        _log.warn(Connection.this + " Retransmission timer hit but nothing transmitted??");
                    }
                    return;
                }

                PacketLocal oldest = e.getValue();
                // numSends may be 2 if a Tail Loss Probe was sent for this packet
                // (TLP re-enqueues the same PacketLocal, incrementing numSends).
                // Cut the window on the first real loss (numSends <= 2).
                if (oldest.getNumSends() <= 2) {
                    if (_log.shouldDebug()) {
                        _log.debug(Connection.this + " cutting SlowStartThreshold and Window");
                    }
                    int wsize = _options.getWindowSize();
                    _lossStrikes++;
                    int strikes = _lossStrikes;
                    int bwSsthresh = Math.max((int)(_bwEstimator.getBandwidthEstimate() * _options.getMinRTT()), 2 );
                    bwSsthresh = Math.min(ConnectionPacketHandler.getMaxSlowStartWindow(_context), bwSsthresh);
                    // Graduated cut: a single failed packet shrinks the window only
                    // mildly (3/4), escalated strikes back off harder (1/2, then 1/4).
                    // ssthresh is kept at least the new window so slow-start can
                    // regrow quickly after recovery instead of crawling at ~4.
                    int maxSS = ConnectionPacketHandler.getMaxSlowStartWindow(_context);
                    _ssthresh = Math.min(maxSS, graduatedLossSsthresh(strikes, wsize, bwSsthresh));
                    // Floor at 4 so repeated RTO events don't collapse the window
                    // below a usable minimum — prevents degenerative behavior
                    // where each retransmit halves the window to 1, making every
                    // subsequent send a single-packet-at-a-time ordeal.
                    _options.setWindowSize(Math.min(maxSS, graduatedLossWindow(strikes, wsize)));
                    updatePacingRate();
                } else if (_log.shouldDebug()) {
                    _log.debug(Connection.this + " not cutting SlowStartThreshold and Window");
                }

                // The window map is a TreeMap, so values() already returns
                // packets in ascending sequence number order (lower = higher priority).
                // Round down (RFC 5681 section 4.3 "MUST be no more than half")
                // https://datatracker.ietf.org/doc/html/rfc5681#section-4.3
                toResend = new ArrayList<>(ob.values());
                toResend = toResend.subList(0, Math.max(1, Math.min(getMaxRtx(), toResend.size() / 2)));
            }

            // 3. Retransmit up to half of the packets in flight (RFC 6298 section 5.4 and RFC 5681 section 4.3)
            //    Send first 4 immediately, pace the rest to avoid I2CP burst.
            boolean sentAny = false;
            int burstCount = 0;
            for (PacketLocal packet : toResend) {
                // Skip packets acknowledged or cancelled after the snapshot was
                // built (line 2634) but before this resend runs. ackPackets() and
                // cancelled() release the payload back to the buffer pool under a
                // different lock, so a stale reference here would re-enqueue a
                // packet whose _payload is now null and crash the I2CP write
                // (use-after-release TOCTOU; mirrors the paced-path guard below).
                if (packet.writeReleased()) {
                    // A cancelled-but-still-mapped packet can never be
                    // retransmitted and freezes every give-up branch below
                    // (getNumSends() never advances), leaving this timer to fire
                    // into a no-op forever — the "stuck packet forever" zombie.
                    // Drop it from the window map once (removal is a no-op if
                    // ackPackets already removed it).
                    long staleSeq = packet.getSequenceNum();
                    synchronized (_outboundPacketsLock) {
                        TreeMap<Long, PacketLocal> ob = outboundPackets();
                        if (ob.remove(Long.valueOf(staleSeq)) != null) {
                            _outboundPacketsLock.notifyAll();
                            if (_log.shouldWarn()) {
                                _log.warn(Connection.this + " removed cancelled/stale packet " + packet +
                                          " from outbound window");
                            }
                        }
                    }
                    continue;
                }
                /** N resends. */
                final int nResends = packet.getNumSends();
                // "Resume, don't close": once forward progress has been made an
                // established download must not be torn down merely because one
                // head-of-line DATA packet exhausted its retransmit budget — that
                // is a temporary path hole, not a dead path, and closing forces
                // the application to reconnect and re-transfer. Only a
                // connect-phase connection (no packet ever acknowledged, so
                // nothing to resume) keeps the fatal close below. Established
                // connections keep retransmitting; the creation-anchored liveness
                // backstop above is their terminal bound. CLOSE packets are left
                // to the dedicated close-resend cap so teardown still terminates.
                final boolean forwardProgress = _highestAckedThrough.get() >= 0;
                final boolean budgetExhausted = !packet.isFlagSet(Packet.FLAG_SYNCHRONIZE) &&
                                                !packet.isFlagSet(Packet.FLAG_CLOSE) &&
                                                hardResendBudgetExceeded(nResends,
                                                                         packet.getNumSoftResends(),
                                                                         _options.getMaxResends());
                if (budgetExhaustionClosesConnection(budgetExhausted, forwardProgress)) {
                    if (_log.shouldDebug()) {
                        _log.debug(Connection.this + " packet " + packet + " resent too many times, closing...");
                    }
                    packet.cancelled();
                    if (_connectionError == null) {setConnectionError(ERR_RETRANSMIT_LIMIT);}
                    disconnect(false);
                    return;
                }
                if (budgetExhausted && !_establishedResumeWarned) {
                    _establishedResumeWarned = true;
                    if (_log.shouldWarn()) {
                        _log.warn(Connection.this + " packet " + packet +
                                  " exceeded resend budget but connection is established; keeping alive and retransmitting");
                    }
                }
                if (packet.getNumSends() >= 3 &&
                           packet.isFlagSet(Packet.FLAG_CLOSE) &&
                           packet.getPayloadSize() <= 0) {
                    // Bug workaround to prevent 5 minutes of CLOSE retransmission.
                    // If the remote has also closed, 3 sends is enough.
                    // If they haven't, cap at 8 sends (~90s with backoff) instead of
                    // the full maxResends (~12 min).
                    int maxClose = getCloseReceivedOn() > 0 ? 3 : 8;
                    if (packet.getNumSends() >= maxClose) {
                        if (_log.shouldDebug()) {
                            _log.debug(Connection.this + " too many close resends, closing...");
                        }
                        packet.cancelled();
                        disconnect(false);
                        return;
                    }
                } else if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE) &&
                           synGiveUpBudgetExceeded(packet.getNumSends(),
                                                   packet.getNumSoftResends(),
                                                   getMaxSynSends())) {
                    // The SYN was never ACKed and the retransmit budget has now covered
                    // the entire connect window (getMaxSynSends(), scaled up from
                    // maxSynResends), so the connect() caller has given up. Stop
                    // resending instead of running to maxResends (~12 min). SYN
                    // retransmits use a fixed RTO interval (no backoff), so the total
                    // budget is getMaxSynSends() * interval. Soft-failure-triggered
                    // resends are excluded (synGiveUpBudgetExceeded()): they never
                    // reached the tunnel fabric and should not count as evidence of a
                    // dead path (see scheduleSoftFailureRetransmit()).
                    // Record an accurate error *before* the error-free disconnect, so
                    // waitForConnect() returns "Connection timed out: SYN not
                    // acknowledged" instead of the generic "Connection failed".
                    if (_log.shouldDebug()) {
                        _log.debug(Connection.this + " too many SYN resends, closing...");
                    }
                    packet.cancelled();
                    if (_connectionError == null) {setConnectionError(ERR_SYN_NOT_ACKNOWLEDGED);}
                    disconnect(false);
                    return;
                } else {

                    if (_isChoking) {
                        if (_log.shouldDebug()) {
                            _log.debug(Connection.this + " packet is choking " + packet);
                        }
                        packet.setOptionalDelay(Packet.SEND_DELAY_CHOKE);
                        packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                    } else if (_unchokesToSend.get() > 0) {
                        if (_log.shouldDebug()) {
                            _log.debug(Connection.this + " packet is unchoking " + packet);
                        }
                        // Don't worry about wrapping around
                        packet.setOptionalDelay(0);
                        packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                    } else {
                        if (_log.shouldDebug()) {
                            _log.debug(Connection.this + " packet clearing flag " + packet);
                        }
                        // clear flag
                        packet.setFlag(Packet.FLAG_DELAY_REQUESTED, false);
                    }

                    packet.setResendDelay(_options.getResendDelay() / 1000);
                    if (packet.getReceiveStreamId() <= 0) {packet.setReceiveStreamId(_receiveStreamId.get());}
                    if (packet.getSendStreamId() <= 0) {packet.setSendStreamId(_sendStreamId.get());}
                    packet.setTimeout(_options.getRTO());

                    // Direct or paced? The first IMMEDIATE_RETX_BURST go directly; the rest
                    // are paced unless recovery-critical (see shouldPaceRetx()).
                    if (!shouldPaceRetx(burstCount, nResends)) {
                        if (_outboundQueue.enqueue(packet)) {
                            burstCount++;
                            if (softPass) {packet.incrementSoftResends();}
                            if (_log.shouldInfo()) {
                                _log.info(Connection.this + " resent packet " + packet);
                            }
                            if (nResends == 1) {_activeResends.incrementAndGet();}
                            sentAny = true;
                        } else if (_log.shouldDebug()) {
                            _log.debug(Connection.this + " could not resend packet " + packet);
                        }
                    } else {
                        long pacingDelay = calculatePacingDelay(packet.getPayloadSize());
                        if (pacingDelay > 0) {
                            synchronized (_pacedQueueLock) {
                                LinkedList<PacketLocal> pq = pacedQueue();
                                pq.add(packet);
                                if (softPass) {packet.incrementSoftResends();}
                                if (pq.size() == 1) {
                                    _pacedEvent.forceReschedule(pacingDelay);
                                }
                            }
                        } else {
                            if (_outboundQueue.enqueue(packet)) {
                                if (softPass) {packet.incrementSoftResends();}
                                if (_log.shouldInfo()) {
                                    _log.info(Connection.this + " resent packet " + packet);
                                }
                                if (nResends == 1) {_activeResends.incrementAndGet();}
                                sentAny = true;
                            } else if (_log.shouldDebug()) {
                                _log.debug(Connection.this + " could not resend packet " + packet);
                            }
                        }
                    }
                }
            }

            if (sentAny) {
                _lastSendTime = _context.clock().now();
                resetActivityTimer();
                windowAdjusted();
            }
        }
    }

    /**
     * Inner class for paced packet transmission.
     * Drains from _pacedQueue, one packet per firing.
     * If more packets remain, reschedules itself with the next packet's delay.
     */
    private class PacedPacketEvent extends SimpleTimer2.TimedEvent {

        PacedPacketEvent() {
            super(_timer);
        }

        /**
         * Send the next paced packet when the timer fires.
         */
        public void timeReached() {
            PacketLocal packet;
            long nextDelay;
            synchronized (_pacedQueueLock) {
                packet = _pacedQueue == null ? null : _pacedQueue.poll();
                if (packet == null) {
                    return;
                }
                nextDelay = !_pacedQueue.isEmpty() ? calculatePacingDelay(_pacedQueue.peek().getPayloadSize()) : -1;
            }
            if (!_connected.get() || packet.writeReleased()) {
                if (nextDelay >= 0) {
                    synchronized (_pacedQueueLock) {
                        if (_pacedQueue != null && !_pacedQueue.isEmpty()) {
                            forceReschedule(calculatePacingDelay(_pacedQueue.peek().getPayloadSize()));
                        }
                    }
                }
                return;
            }
            if (_outboundQueue.enqueue(packet)) {
                packet.markEnqueued();
                _unackedPacketsReceived.set(0);
                _lastSendTime = _context.clock().now();
                synchronized (_pacingLock) {
                    _lastPacketSendTime = _context.clock().now();
                }
                resetActivityTimer();
                if (_retransmitEvent.scheduleIfNotRunning(packet.getTimeout()) && _log.shouldDebug()) {
                    _log.debug("[" + Connection.this + "] Resend in " + packet.getTimeout() + "ms for paced " + packet);
                }
            }
            if (nextDelay >= 0) {
                forceReschedule(nextDelay);
            }
        }
    }

    /** Reusable event to send an ACK for a duplicate packet after a short delay. */
    class AckDupEvent extends SimpleTimer2.TimedEvent {
        /** Timestamp of the last ACK DUP mark. */
        private long _created;

        /** Ack dup event */
        AckDupEvent() {
            super(_timer);
        }

        /** Call before each forceReschedule() to record the creation time. */
        void mark() {
            _created = _context.clock().now();
        }

        /**
         * Send an ACK for a duplicate packet when the timer fires.
         */
        public void timeReached() {
            boolean sent = false;
            if (getLastSendTime() <= _created) {
                if (getResetReceived() || getResetSent()) {
                    if (_log.shouldDebug()) {
                        _log.debug("ACK DUP on " + Connection.this + ", but we have been reset");
                    }
                    return;
                }

                if (_log.shouldDebug()) {
                    _log.debug("Last sent was a while ago, and we want to ACK a DUP on " + Connection.this);
                }
                ackImmediately();
                sent = true;
            } else {
                if (_log.shouldDebug()) {
                    _log.debug("ACK DUP on " + Connection.this + ", but we have sent (" + (getLastSendTime()-_created) + ")");
                }
            }
            _context.statManager().addRateData("stream.ack.dup.sent", sent ? 1 : 0);
        }
    }

    /**
     * Notify waiters to reschedule the connection event.
     */
    class ConEvent extends SimpleTimer2.TimedEvent {
        /** Con event */
        ConEvent() {super();}
        /**
         * Notify event waiters when the timer fires.
         */
        public void timeReached() {eventOccurred();}
        /**
         * Description of the event.
         */
        @Override
        public String toString() {return "event on connection to " + getRemotePeerString();}
    }

    /**
     * If we have been explicitly NACKed three times, retransmit the packet even if
     * there are other packets in flight.
     */
    static final int FAST_RETRANSMIT_THRESHOLD = 3;

    /**
     * Number of retransmits sent directly per RTO fire before remaining
     * retransmits are paced, to avoid flooding the I2CP queue with a
     * single-timer-fire burst. @since 0.9.71+
     */
    static final int IMMEDIATE_RETX_BURST = 4;

    /**
     * Once a packet has been transmitted this many times, always send it
     * directly instead of pacing it: a repeatedly-resent packet is
     * recovery-critical and must not starve behind a stale pacing rate in the
     * paced queue (hard drain deadline). @since 0.9.71+
     */
    static final int MAX_PACED_RETX = 4;

    /**
     * A new ResendPacketEvent.
     * @since 0.9.46
     */
    ResendPacketEvent newResendPacketEvent(PacketLocal packet) {
        return new ResendPacketEvent(packet);
    }

    /**
     * This is not normally scheduled. It's now used only for fastRetransmit(),
     * where it's scheduled with a delay of zero to put it on the timer queue.
     * Timeout retransmissions are handled by RetransmitEvent above.
     */
    class ResendPacketEvent extends SimpleTimer2.TimedEvent {
        /** The packet to retransmit. */
        private final PacketLocal _packet;

        /**
         * ResendPacketEvent.
         */
        public ResendPacketEvent(PacketLocal packet) {
            super(_timer);
            _packet = packet;
        }

        /**
         * Retransmit the packet when the timer fires.
         */
        public void timeReached() {retransmit();}

        /**
         * @since 0.9.46
         */
        void fastRetransmit() {reschedule(0);}

        /**
         * Retransmit the packet if we need to.
         *
         * ackImmediately() above calls directly in here, so
         * we have to use forceReschedule() instead of schedule() below,
         * to prevent duplicates in the timer queue.
         *
         * Don't synchronize this, deadlock with ackPackets-&gt;ackReceived-&gt;SimpleTimer2.cancel
         *
         * <p>Historical note on the lock warning: SimpleTimer2 now invokes
         * TimedEvent.timeReached()/run() outside any SimpleTimer2 monitor
         * (only checkAndPrepareRun/updateStateAfterRun are brief synchronized
         * scopes), so the called-from-across-the-wire locks taken in
         * ackPackets() and here cannot form a monitor cycle with this thread.
         * A short synchronized (_outboundPacketsLock) in the reset path below is
         * therefore deadlock-safe; ackPackets() holds the same lock only for
         * mapping + ack bookkeeping, never while calling SimpleTimer2.
         *
         * @return true if the packet was sent, false if it was not
         */
        private boolean retransmit() {
            if (_packet.getAckTime() > 0) {return false;}

            if (_resetSentOn.get() > 0 || _resetReceived.get() || _finalDisconnect.get()) {
                _packet.cancelled();
                // cancelled() does NOT remove the packet from the window map.
                // Left mapped, getNumSends() is frozen so no give-up branch in
                // RetransmitEvent.timeReached() can ever count this packet, and
                // every later RTO fire no-ops on the writeReleased() skip — the
                // "stuck packet forever" zombie. Drop it from the window now.
                synchronized (_outboundPacketsLock) {
                    TreeMap<Long, PacketLocal> ob = outboundPackets();
                    ob.remove(Long.valueOf(_packet.getSequenceNum()));
                    _outboundPacketsLock.notifyAll();
                }
                if (_log.shouldDebug()) {
                    _log.debug(Connection.this + " cancelled " + _packet + " on reset, removed from outbound window");
                }
                return false;
            }

            // revamp various fields, in case we need to ack more, etc
            if (_isChoking) {
                _packet.setOptionalDelay(Packet.SEND_DELAY_CHOKE);
                _packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            } else if (_unchokesToSend.get() > 0) {
                // don't worry about wrapping around
                _packet.setOptionalDelay(0);
                _packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            } else {
                _packet.setFlag(Packet.FLAG_DELAY_REQUESTED, false); // clear flag
            }

            _packet.setResendDelay(_options.getResendDelay() / 1000);
            if (_packet.getReceiveStreamId() <= 0) {_packet.setReceiveStreamId(_receiveStreamId.get());}
            if (_packet.getSendStreamId() <= 0) {_packet.setSendStreamId(_sendStreamId.get());}

            synchronized(_outboundPacketsLock) {
                int newWindowSize = _options.getWindowSize();
                if (_isChoked) {
                    congestionOccurred();
                    _options.setWindowSize(1);
                } else if (_ackSinceCongestion.get() && _packet.getSequenceNum() > _lastCongestionHighestUnacked) {
                    // only shrink the window once per window
                    congestionOccurred();
                    _context.statManager().addRateData("stream.con.windowSizeAtCongestion", newWindowSize, _packet.getLifetime());
                    /*
                     * RTO doubling enabled - TCP-style backoff for better congestion control.
                     * Tunnel failover still provides redundancy, but we now also use RTO
                     * backoff for more aggressive retransmit timing.
                     * Window size shrinks for congestion control.
                     */
                    _options.doubleRTO();

                    _lossStrikes++;
                    if (_packet.getNumSends() == 1) {
                        int strikes = _lossStrikes;
                        int bwSsthresh = Math.max(Math.round(_bwEstimator.getBandwidthEstimate() * _options.getMinRTT() * SSTHR_BW_FACTOR),
                                                  MIN_SSTHR_FAST_RETX);
                        bwSsthresh = Math.min(ConnectionPacketHandler.getMaxSlowStartWindow(_context), bwSsthresh);
                        int wsize = _options.getWindowSize();
                        // Floor ssthresh at the graduated window so the connection
                        // can regrow quickly after recovery, but keep the classic
                        // fast-retransmit window choice (min(ssthresh, wsize)).
                        int maxSS = ConnectionPacketHandler.getMaxSlowStartWindow(_context);
                        _ssthresh = Math.min(maxSS, Math.max(graduatedLossWindow(strikes, wsize), bwSsthresh));
                        _options.setWindowSize(Math.min(_ssthresh, wsize));
                        updatePacingRate(); // Update pacing when window changes
                    }

                    if (_log.shouldInfo()) {
                        _log.info("Network congestion: Resending packet [" + _packet.getSequenceNum() + "]"
                                      + "\n* New Window Size: " + newWindowSize + "/" + _options.getWindowSize()
                                      + " for " + Connection.this.toString());
                    }
                    windowAdjusted();
                }
            }

            int numSends = _packet.getNumSends() + 1;
            if (numSends - 1 > _options.getMaxResends() && !_packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                if (_log.shouldDebug()) {_log.debug("Disconnecting, too many resends of " + _packet);}
                _packet.cancelled();
                if (_connectionError == null) {setConnectionError(ERR_RETRANSMIT_LIMIT);}
                disconnect(false);
            } else if (numSends >= 3 &&
                       _packet.isFlagSet(Packet.FLAG_CLOSE) &&
                       _packet.getPayloadSize() <= 0 &&
                       (_outboundPackets == null ? 0 : _outboundPackets.size()) <= 1 &&
                       getCloseReceivedOn() > 0) {
                /*
                 * Bug workaround to prevent 5 minutes of retransmission
                 * Routers before 0.9.9 have bugs, they won't ack anything after
                 * they sent a close. Only send 3 CLOSE packets total, then
                 * shut down normally.
                 */
                if (_log.shouldInfo()) {
                    _log.info("Too many CLOSE resends, disconnecting: " + Connection.this.toString());
                }
                _packet.cancelled();
                disconnect(true);
            } else {
                int timeout = _options.getRTO();
                int mrd = getMaxResendDelay();
                if ((timeout > mrd) || (timeout <= 0)) {timeout = mrd;}
                // set this before enqueue() as it passes it on to the router
                _packet.setTimeout(timeout);

                if (_outboundQueue.enqueue(_packet)) {
                    if (_retransmitEvent.scheduleIfNotRunning(timeout) && _log.shouldDebug()) {
                        _log.debug("[" + Connection.this + "] Fast retransmit and schedule timer");
                    }

                    // first resend for this packet ?
                    if (numSends == 2) {_activeResends.incrementAndGet();}
                    if (_log.shouldInfo()) {
                            _log.info("Resent packet " + _packet +
                                  "(fast) " +
                                  "\n* Next resend in " + (timeout / 1000) + "s" +
                                  "\n* Active resends: " + _activeResends +
                                  "; Window Size: "
                                  + _options.getWindowSize() + "; Lifetime: "
                                  + (_context.clock().now() - _packet.getCreatedOn()) + "ms");
                    }
                    _unackedPacketsReceived.set(0);
                    _lastSendTime = _context.clock().now();
                    resetActivityTimer(); // timer reset added 0.9.1
                }
            }

            // ACKed during resending (... or somethin') ????????????
            if ((_packet.getAckTime() > 0) && (_packet.getNumSends() > 1)) {
                _activeResends.decrementAndGet();
                synchronized (_outboundPacketsLock) {
                    _outboundPacketsLock.notifyAll();
                }
            }
            return true;
        }
    }

}
