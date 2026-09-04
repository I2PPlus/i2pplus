package net.i2p.client.streaming.impl;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.IncomingConnectionFilter;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.stat.RateConstants;
import net.i2p.util.ByteCache;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.ConvertToHash;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer2;

/**
 * Coordinate all of the connections for a single local destination.
 *
 *
 */
class ConnectionManager {
    private final I2PAppContext _context;
    private final Log _log;
    private final I2PSession _session;
    private final MessageHandler _messageHandler;
    private final PacketHandler _packetHandler;
    private final ConnectionHandler _connectionHandler;
    private final PacketQueue _outboundQueue;
    private final SchedulerChooser _schedulerChooser;
    private final ConnectionPacketHandler _conPacketHandler;
    private final TCBShare _tcbShare;
    private final IncomingConnectionFilter _connectionFilter;
    /** Inbound stream ID (Long) to Connection map */
    private final ConcurrentHashMap<Long, Connection> _connectionByInboundId;
    /** Outbound stream ID (Long) to Connection map — reverse index for O(1) lookup */
    private final ConcurrentHashMap<Long, Connection> _connectionByOutboundId;
    /** Ping ID (Long) to PingRequest */
    private final ConcurrentHashMap<Long, PingRequest> _pendingPings;
    private volatile boolean _throttlersInitialized;
    private final ConnectionOptions _defaultOptions;
    private final AtomicInteger _numWaiting = new AtomicInteger();
    private long _soTimeout;
    private volatile ConnThrottler _minuteThrottler;
    private volatile ConnThrottler _hourThrottler;
    private volatile ConnThrottler _dayThrottler;
    /** Since 0.9, each manager instantiates its own timer. */
    private final RetransmissionTimer _timer;
    private final Map<Long, Object> _recentlyClosed;
    private final ByteCache _cache = ByteCache.getInstance(32, 4*1024);
    private static final Object DUMMY = new Object();

    /**
     * Lock for interruptible cooldown wait in connect() — notified on shutdown.
     */
    private final Object _cooldownLock = new Object();

    /**
     * Per-destination cooldown to avoid hammering unreachable peers.
     * Key is destination Hash, value is timestamp of last failed connect.
     * @since 2.7.0
     */
    private final ConcurrentHashMap<Hash, Long> _destFailures = new ConcurrentHashMap<>(4);

    /**
     * Stream connection pool — reuses established streams to the same destination.
     * Key: destination Hash, Value: deque of pooled connections.
     * @since 0.9.71+
     */
    private final ConcurrentHashMap<Hash, ConcurrentLinkedDeque<PooledConnection>> _streamPools =
        new ConcurrentHashMap<>(32);

    /**
     * Max idle time before a pooled stream is closed and evicted.
     * Default 20s — balances reuse vs staleness.
     * Tunable via i2p.streaming.poolMaxIdleMs.
     * @return the pool max idle ms
     */
    private long getPoolMaxIdleMs() {
        return _context.getProperty("i2p.streaming.poolMaxIdleMs", 20 * 1000);
    }

    /**
     * Max pooled streams per destination.
     * Default 8 — limits memory per peer.
     * Tunable via i2p.streaming.poolMaxPerDestination.
     * @return the pool max per destination
     */
    private int getPoolMaxPerDestination() {
        return _context.getProperty("i2p.streaming.poolMaxPerDestination", 8);
    }

    /**
     * Whether stream pooling is enabled.
     * Disabled by default — pooled connections retain stale remote state
     * that causes zero-data-flow after reuse. Enable only with proper
     * connection state reset.
     * Tunable via i2p.streaming.streamPool.enabled (default: false).
     * @return whether pool enabled
     */
    private boolean isPoolEnabled() {
        return _context.getProperty("i2p.streaming.streamPool.enabled", false);
    }

    /**
     * Cooldown between connection attempts to the same failed destination.
     * Tunable via i2p.streaming.destinationCooldownMs (default: 60000).
     * @return the dest cooldown ms
     */
    private long getDestCooldownMs() {
        return _context.getProperty("i2p.streaming.destinationCooldownMs", 5*1000);
    }

     private static final long[] RATES = RateConstants.SHORT_TERM_RATES;

    /** Cache of the property to detect changes. */
    private static volatile String currentBlacklist = "";
    private static final Set<Hash> _globalBlacklist = new ConcurrentHashSet<>();

    /**
     *  Temporary bans keyed by destination hash -> bannedUntil epoch ms.
     *  Autoban hammering dests for 24h (or i2p.streaming.tempBanMinutes).
     *  Enforced in shouldRejectConnection() before the port/budget counters so a
     *  banned dest no longer consumes its per-dest budget or per-peer throttlers.
     *  Expired entries
     *  are removed by BanExpiry. @since 0.9.71+
     */
    private final ConcurrentHashMap<Hash, Long> _tempBanUntil = new ConcurrentHashMap<>();

    /**
     *  Trigger reason for an active temp-ban, kept alongside _tempBanUntil so the
     *  enforcement path can tell a client *why* it was banned (e.g. the per-minute
     *  limit it tripped). Written on ban, removed on expiry by BanExpiry.
     *  @since 0.9.71+
     */
    private final ConcurrentHashMap<Hash, String> _tempBanReason = new ConcurrentHashMap<>();

    /**
     *  Rolling refusals per destination, incremented each time the
     *  MAXIMUM streams gate refuses a SYN from that dest. Empties when a
     *  dest is banned or when BanExpiry clears the window.
     *  @since 0.9.71+
     */
    private final ObjectCounter<Hash> _refusalCounter = new ObjectCounter<>();

    /**
     *  Live concurrent-stream count keyed by remote destination. Each remote dest
     *  gets its own stream budget (captured from the same effective max as the old
     *  global gate), so a flood from one dest can no longer starve legitimate
     *  clients sharing the listener. Incremented when a stream registers in
     *  {@link #_connectionByInboundId} and decremented on removal; reconciled
     *  against the live table every minute by {@link BanExpiry} so any missed
     *  teardown self-heals. @since 0.9.71+
     */
    private final ConcurrentHashMap<Hash, AtomicInteger> _streamsByDest = new ConcurrentHashMap<>();

    /**
     *  Per-destination burst state for the sub-second SYN gate: value is a two-element
     *  array {burstStartMs, count}, updated at most once per validated SYN via
     *  compare-and-swap (replace). A dest that quiesces naturally falls out of the
     *  next window; no per-dest entries accumulate beyond one array, so this is bounded
     *  and cheap on the hot path. Reset per-dest by the SYN-rate sweeper. @since 0.9.71+
     */
    private final ConcurrentHashMap<Hash, long[]> _recentSyns = new ConcurrentHashMap<>();

    /**
     *  Cached sub-second burst config, refreshed by the BanExpiry sweeper rather than
     *  re-read from the property store on every validated SYN (hot path).
     *  @since 0.9.71+
     */
    private volatile long _synRateMs = DEFAULT_TEMP_BAN_RATE_MS;
    private volatile int _synBurst = DEFAULT_TEMP_BAN_SYN_BURST;

    /**
     *  Cached autoban duration (ms) and refusal threshold, refreshed by the
     *  BanExpiry sweeper so the hot path (isTempBanned / refusal latch) avoids
     *  per-SYN property-store reads. Snapshot-at-mark-time still holds: a ban uses
     *  the duration current when it was placed.
     *  @since 0.9.71+
     */
    private volatile long _tempBanMs = DEFAULT_TEMP_BAN_MINUTES * 60L * 1000L;
    private volatile long _tempBanRefusals = DEFAULT_TEMP_BAN_REFUSALS;

    /**
     *  Blacklist property for streaming.
     *  @since 0.9.3
     */
    public static final String PROP_BLACKLIST = "i2p.streaming.blacklist";

    /**
     *  Autoban property: a dest is temporarily banned until this many minutes after
     *  it first trips a flood threshold. Default 24 hours.
     *  Tunable via i2p.streaming.tempBanMinutes. 0 disables autoban. @since 0.9.71+
     */
    public static final String PROP_TEMP_BAN_MINUTES = "i2p.streaming.tempBanMinutes";
    private static final long DEFAULT_TEMP_BAN_MINUTES = 24 * 60;

    /**
     *  Autoban property: whether the temp-ban map and sweeper are enabled.
     *  0 disables; nonzero enables. Default on. @since 0.9.71+
     */
    public static final String PROP_AUTOBAN = "i2p.streaming.autoban";
    private static final int DEFAULT_AUTOBAN = -1;

    /**
     *  Autoban property: refusals from a single dest within a one-minute window
     *  (as counted by _refusalCounter) at which the dest is auto-banned.
     *  Tunable via i2p.streaming.tempBanRefusals. @since 0.9.71+
     */
    public static final String PROP_TEMP_BAN_REFUSALS = "i2p.streaming.tempBanRefusals";
    private static final long DEFAULT_TEMP_BAN_REFUSALS = 100;

    /**
     *  Autoban property: a dest sending more than tempBanSynBurst SYNs within a
     *  tempBanSynRate-ms rolling window is auto-banned. This is the sub-second
     *  rate gate: a legit client (e.g. a BitTorrent announce on a timer) never bursts
     *  tens of SYNs within a few hundred ms, but the observed tracker flood does
     *  (~25 SYNs in 259ms). Catches the burst before it consumes the stream budget.
     *  @since 0.9.71+
     */
    public static final String PROP_TEMP_BAN_RATE_MS = "i2p.streaming.tempBanSynRate";
    private static final long DEFAULT_TEMP_BAN_RATE_MS = 500;

    /**
     *  Autoban property: sub-second burst threshold (SYNs within rate window).
     *  Default 10 SYNs per 500ms = 20 req/s instantaneous.
     *  @since 0.9.71+
     */
    public static final String PROP_TEMP_BAN_SYN_BURST = "i2p.streaming.tempBanSynBurst";
    private static final int DEFAULT_TEMP_BAN_SYN_BURST = 10;

    /**
     *  Ban a dest for the configured duration. Idempotent; an existing longer ban
     *  is left in place so repeated abuse can't shrink it.
     *  @param h dest hash to ban
     *  @param why human-readable trigger, e.g. "exceeded max 50 conns/minute"
     *  @param now current clock time
     *  @since 0.9.71+
     */
    void banPeer(Hash h, String why, long now) {
        long ms = _tempBanMs;
        if (ms <= 0)
            return;
        Long until = Long.valueOf(now + ms);
        Long prev = _tempBanUntil.putIfAbsent(h, until);
        if (prev != null && banIsLonger(prev, until)) {
            _tempBanUntil.replace(h, prev, until);
            _tempBanReason.replace(h, why);
        } else if (prev == null) {
            _tempBanReason.put(h, why);
        }
        _refusalCounter.clear(h);
        if (_log.shouldInfo())
            _log.info("Autobanning " + h.toBase32().substring(0, 6) + " for " + (ms / 60000L) + " minutes - " + why);
    }

    /**
     *  Account a newly registered stream against its destination's per-dest budget.
     *  Must be called exactly once per stream, at registration, keyed by the remote
     *  destination. Teardown must call {@link #removeStream(Hash)} for the same hash.
     *  @param h remote dest hash, non-null
     *  @since 0.9.71+
     */
    private void addStream(Hash h) {
        if (h == null)
            return;
        AtomicInteger c = _streamsByDest.get(h);
        if (c == null) {
            AtomicInteger n = new AtomicInteger();
            c = _streamsByDest.putIfAbsent(h, n);
            if (c == null)
                c = n;
        }
        c.incrementAndGet();
    }

    /**
     *  Release one stream from its destination's per-dest budget. Decrement is
     *  skipped when the remote is unknown (a torn-down connection whose dest was
     *  never established); the BanExpiry sweeper reconciles such drift.
     *  @param h remote dest hash, null-safe
     *  @since 0.9.71+
     */
    private void removeStream(Hash h) {
        if (h == null)
            return;
        AtomicInteger c = _streamsByDest.get(h);
        if (c != null && c.decrementAndGet() <= 0)
            _streamsByDest.remove(h, c);
    }

    /**
     *  Pure decision for the per-destination stream budget: a dest is over budget
     *  once its live concurrent-stream count reaches the per-dest ceiling.
     *  A non-positive ceiling disables the gate entirely.
     *  @param streamCount live streams currently held by the dest
     *  @param max the per-dest concurrent stream ceiling
     *  @return true if the dest is at or over its own budget
     *  @since 0.9.71+
     */
    static boolean tooManyStreamsForDest(int streamCount, int max) {
        return max > 0 && streamCount >= max;
    }

    /**
     *  Whether a remote destination currently holds at least its per-dest ceiling
     *  of concurrent streams. This replaces the old global count: each client gets
     *  its own budget, so one abuser can no longer exhaust the listener for
     *  everyone else.
     *  @param h remote dest hash, non-null
     *  @param max the per-dest concurrent stream ceiling
     *  @return true if the dest is over its own budget
     *  @since 0.9.71+
     */
    private boolean tooManyStreamsForDest(Hash h, int max) {
        if (h == null)
            return false;
        AtomicInteger c = _streamsByDest.get(h);
        return tooManyStreamsForDest(c == null ? 0 : c.get(), max);
    }

    /**
     *  The stored trigger description for an active temp-ban, if any.
     *  @param h dest hash
     *  @return the recorded reason, or null if none stored
     *  @since 0.9.71+
     */
    private String tempBanReason(Hash h) {
        return _tempBanReason.get(h);
    }

    /**
     *  Whether a new ban end time should replace an existing one: only when it
     *  is strictly longer. Prevents a late-arriving shorter ban from shrinking
     *  an active ban under repeated abuse.
     *  @param existing current bannedUntil (aged), null if none
     *  @param candidate proposed new bannedUntil
     *  @return true if candidate exceeds existing
     *  @since 0.9.71+
     */
    static boolean banIsLonger(Long existing, Long candidate) {
        return existing != null && candidate != null && candidate.longValue() > existing.longValue();
    }

    /**
     *  Whether the dest is currently temp-banned.
     *  @param h dest hash to check
     *  @param now current clock time
     *  @return true if temp-banned and not yet expired
     *  @since 0.9.71+
     */
    boolean isTempBanned(Hash h, long now) {
        if (_tempBanMs <= 0)
            return false;
        return banActive(_tempBanUntil.get(h), now);
    }

    /**
     *  Whether a bannedUntil time is still in the future.
     *  @param bannedUntil epoch ms; null treated as not banned
     *  @param now current clock time
     *  @return true if bannedUntil is non-null and greater than now
     *  @since 0.9.71+
     */
    static boolean banActive(Long bannedUntil, long now) {
        return bannedUntil != null && bannedUntil.longValue() > now;
    }

    /**
     *  Whether a dest has tripped the autoban refusal threshold.
     *  @param refusals count of refusals seen for the dest in the current window
     *  @param threshold configured refusals-to-ban threshold
     *  @return true when refusals exceed threshold
     *  @since 0.9.71+
     */
    static boolean refusalThresholdMet(long refusals, long threshold) {
        return threshold > 0 && refusals > threshold;
    }

    /**
     *  Pure decision for the sub-second SYN-burst gate: whether a dest has sent
     *  more than {@code burst} SYNs within the last {@code windowMs} ms.
     *  @param burstStartMs epoch ms of the first SYN in the current burst window
     *                     (the ''oldest'' still counted), null if none
     *  @param count number of SYNs attributed to the open window
     *  @param now current clock time
     *  @param windowMs rolling window length
     *  @param burst SYNs-per-window that constitutes an abusive burst
     *  @return true if the dest tripped the burst gate
     *  @since 0.9.71+
     */
    static boolean synBurstTripped(Long burstStartMs, int count, long now, long windowMs, int burst) {
        if (windowMs <= 0 || burst <= 0)
            return false;
        if (burstStartMs == null)
            return false;
        if (now - burstStartMs.longValue() >= windowMs)
            return false;
        return count > burst;
    }

    /**
     *  Whether a SYN from a dest tripped the sub-second burst gate. Side effects:
     *  records the SYN in the per-dest rolling window. Allocation-free on the hot
     *  path: an existing window is bumped in place rather than replaced, and the
     *  window/burst limits are read from cached volatile fields refreshed by the
     *  BanExpiry sweeper. Call only for a validated SYN source.
     *  @param h dest hash to record against
     *  @param now current clock time
     *  @return true if this SYN pushed the dest over its burst threshold
     *  @since 0.9.71+
     */
    private boolean checkSynBurst(Hash h, long now) {
        long windowMs = _synRateMs;
        int burst = _synBurst;
        if (windowMs <= 0 || burst <= 0)
            return false;
        long[] cur = _recentSyns.get(h);
        if (cur == null || now - cur[0] >= windowMs) {
            // no active window (or it aged out): start a fresh one. One allocation
            // per new dest is fine; the flood path for an already-banned dest is
            // short-circuited before this is ever reached.
            long[] init = {now, 1};
            long[] prev = _recentSyns.putIfAbsent(h, init);
            if (prev == null)
                return false;
            cur = prev;
        }
        // Hot path: bump in place. Non-atomic under concurrency, which can only
        // under-count a rare race for a DoS gate -- never over-ban.
        cur[1] = cur[1] + 1;
        return synBurstTripped(cur[0], (int) cur[1], now, windowMs, burst);
    }

    /**
     *  Package-visible flood gate for the retransmit-SYN path in
     *  {@link ConnectionHandler#receiveNewSyn(Packet)}. A retransmitted SYN carries the
     *  stream IDs of a connection that already exists in the manager, so it never
     *  reaches {@link #receiveConnection(Packet)} (and thus never passes the
     *  {@link #checkSynBurst(Hash, long)} gate at the top of that method). An
     *  attacker exploits that by planting a handful of half-open connections and
     *  then blasting retransmitted SYNs against them; without this gate every hit
     *  makes {@code ConnectionHandler.resendSynAck} mint and enqueue a fresh
     *  SYN-ACK, consuming CPU and egress, and the connection never establishes.
     *
     *  <p>This routes the retransmit through the <em>same</em> per-destination
     *  sub-second burst window as fresh SYNs, so a dest that exceeds the burst
     *  threshold across new <em>or</em> retransmitted SYNs is autobanned. Once
     *  banned, subsequent calls return {@code true} (drop) immediately.
     *
     *  @param h remote dest hash of the retransmitted SYN's source, non-null
     *  @param now current clock time
     *  @return true if this SYN should be dropped (dest already temp-banned, or
     *          this SYN tripped the burst gate and just banned it)
     *  @since 0.9.71+
     */
    boolean checkInboundSynFlood(Hash h, long now) {
        if (h == null)
            return false;
        if (isTempBanned(h, now))
            return true;
        if (checkSynBurst(h, now)) {
            banPeer(h, "exceeded max " + _synBurst + " SYNs/" + _synRateMs + "ms on inbound retransmit",
                    now);
            return true;
        }
        return false;
    }

    private long getTempBanMinutes() {
        return _context.getProperty(PROP_TEMP_BAN_MINUTES, (int) DEFAULT_TEMP_BAN_MINUTES);
    }

    /**
     *  The concurrent-stream cap to enforce right now. Normally this is the value
     *  captured into {@link #_defaultOptions} at init (the user-configured
     *  {@code i2p.streaming.maxConcurrentStreams}); if the router's Tuner has armed an
     *  override via {@link I2PSocketManagerFull#setMaxStreamsOverride}, the effective
     *  cap is the <em>lower</em> of the two so a user ceiling is never exceeded a
     *  Tuner. A volatile read; no config lookup per call.
     *  @return the cap; &le; 0 means no cap is enforced
     *  @since 0.9.71+
     */
    private int getEffectiveMaxStreams() {
        int override = ConnectionOptions.getMaxConcurrentStreamsOverride();
        int user = _defaultOptions.getMaxConns();
        if (override > 0)
            return Math.min(user, override);
        return user;
    }

    /**
     *  The cap to <em>report</em> in refusal log messages: the Tuner's current
     *  override when it has armed one, otherwise the user-configured ceiling.
     *
     *  <p>This deliberately differs from {@link #getEffectiveMaxStreams()}, which
     *  min's the override against the user ceiling and is the real enforcement
     *  limit at the per-destination {@link #tooManyStreamsForDest(Hash, int)} gate.
     *  When the Tuner relaxes a healthy
     *  host back up toward its own max (e.g. 512) that is higher than a user's
     *  configured ceiling (e.g. 256), the enforcement limit stays 256 while the
     *  operator reading the log wants to see the Tuner's current target (512).
     *
     *  @return the tuner override when armed, else the configured cap
     *  @since 0.9.71+
     */
    private int getLogMaxStreams() {
        int override = ConnectionOptions.getMaxConcurrentStreamsOverride();
        if (override > 0)
            return override;
        return _defaultOptions.getMaxConns();
    }

    /**
     * Maximum ping timeout. Tunable via i2p.streaming.maxPingTimeout (default: 300000).
     * @return the max ping timeout
     */
    private long getMaxPingTimeout() {
        return _context.getProperty("i2p.streaming.maxPingTimeout", 5*60*1000);
    }

    private static final int MAX_PONG_PAYLOAD = 32;

    /**
     * Once over throttle limits, respond this many times before just dropping.
     * Tunable via i2p.streaming.dropOverLimit (default: 3).
     * @return the drop over limit
     */
    private int getDropOverLimit() {
        return _context.getProperty("i2p.streaming.dropOverLimit", 3);
    }

    /** @since 0.9.54+ */
    private static final String PROP_ENABLE_PONG_DELAY = "i2p.streaming.enablePongDelay";
    private static final boolean DEFAULT_ENABLE_PONG_DELAY = false;
    private static final int MAX_PONG_DELAY = 50;
    /** Property: maximum pong delay in ms. */
    static final String PROP_MAX_PONG_DELAY = "i2p.streaming.maxPongDelay";

    // https://stackoverflow.com/questions/16022624/examples-of-http-api-rate-limiting-http-response-headers
    // RFC 6585

    private static final String LIMIT_HTTP_RESPONSE =
         "HTTP/1.1 429 Too Many Requests\r\n" +
         "Content-Type: text/html; charset=iso-8859-1\r\n" +
         "Retry-After: 600\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<html>\n" +
         "<head><title>429 Too Many Requests</title></head>\n" +
         "<body>\n" +
         "<center><h1>429 Too Many Requests</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";
    /**
     *  Manage all conns for this session
     *
     *  @param context the I2P app context
     *  @param session the primary session, packets may come in on subsessions also
     *  @param defaultOptions the default connection options
     *  @param connectionFilter the incoming connection filter
     */
    public ConnectionManager(I2PAppContext context,
                             I2PSession session,
                             ConnectionOptions defaultOptions,
                             IncomingConnectionFilter connectionFilter) {
        _context = context;
        _session = session;
        _defaultOptions = defaultOptions;
        _connectionFilter = connectionFilter;
        _log = _context.logManager().getLog(ConnectionManager.class);
        _connectionByInboundId = new ConcurrentHashMap<>(32);
        _connectionByOutboundId = new ConcurrentHashMap<>(32);
        _pendingPings = new ConcurrentHashMap<>(4);
        _messageHandler = new MessageHandler(_context, this);
        _packetHandler = new PacketHandler(_context, this);
        _schedulerChooser = new SchedulerChooser(_context);
        _conPacketHandler = new ConnectionPacketHandler(_context);
        _timer = new RetransmissionTimer(_context, "StreamTimer:" +
                                         session.getMyDestination().calculateHash().toBase64().substring(0, 4));
        _connectionHandler = new ConnectionHandler(_context, this, _timer.getSharedTimer());
        _tcbShare = new TCBShare(_context, _timer.getSharedTimer());
        // PROTO_ANY is for backward compatibility (pre-0.7.1)
        // PacketQueue has sent PROTO_STREAMING since the beginning of mux support (0.7.1)
        // As of 0.9.1, new option to enforce streaming protocol, off by default
        // As of 0.9.1, listen on configured port (default 0 = all)
        // enforce protocol default changed to true in 0.9.36
        // disable option in 0.9.71
        int protocol = I2PSession.PROTO_STREAMING;
        _session.addMuxedSessionListener(_messageHandler, protocol, defaultOptions.getLocalPort());
        _outboundQueue = new PacketQueue(_context, _timer.getSharedTimer());
        _recentlyClosed = new LHMCache<>(512);
        /** Socket timeout for accept() */
        _soTimeout = -1;

        // Stats for this class
        _context.statManager().createRequiredRateStat("stream.con.lifetimeMessagesSent", "Number of messages we send on a stream", "Stream", RATES);
        _context.statManager().createRateStat("stream.con.lifetimeMessagesReceived", "Number of messages we receive on a stream", "Stream", RATES);
        _context.statManager().createRateStat("stream.con.lifetimeBytesSent", "How many bytes we send on a stream", "Stream", RATES);
        _context.statManager().createRateStat("stream.con.lifetimeBytesReceived", "How many bytes we receive on a stream", "Stream", RATES);
        _context.statManager().createRateStat("stream.con.lifetimeDupMessagesSent", "Number of duplicate messages we send on a stream", "Stream", RATES);
        _context.statManager().createRateStat("stream.con.lifetimeDupMessagesReceived", "Number of duplicate messages we receive on a stream", "Stream", RATES);
        _context.statManager().createRequiredRateStat("stream.rtxRatio", "Retransmissions per 1000 messages sent when a stream closes", "Stream", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRequiredRateStat("stream.rtxRatioBytes", "Retransmitted bytes per 1000 bytes sent when a stream closes (bandwidth-overhead view, less sensitive to small-message connections)", "Stream", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRequiredRateStat("stream.con.lifetimeRTT", "Final RTT when a stream closes", "Stream", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRequiredRateStat("stream.con.lifetimeSendWindowSize", "Final send window size when a stream closes", "Stream", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRateStat("stream.receiveActive", "Number of active streams when a new one is received (period being not yet dropped)", "Stream", RATES);
        // Stats for Connection
        _context.statManager().createRequiredRateStat("stream.con.windowSizeAtCongestion", "Size of our send window when we send a dup", "Stream", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRateStat("stream.connectionReceived", "Number of stream connections received", "Stream", RATES);
        // Published by ConnectionHandler on each SYN sample-window rollover; read by the
        // Tuner (stream.con.synExpireRate) to tell latency-bound accept stalls (high
        // expire rate) from genuinely parallelizable load.
        _context.statManager().createRateStat("stream.con.synExpireRate", "Percent of queued SYNs that expired un-accepted during the sample window", "Stream", RATES);
        _context.statManager().createRequiredRateStat("stream.connectionCreated", "Number of outbound stream connections created", "Stream", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRequiredRateStat("stream.connectFailed", "Elapsed time (ms) of a failed outbound connect attempt", "Stream", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRequiredRateStat("stream.connectTime", "Elapsed time (ms) of a successful outbound connect", "Stream", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRequiredRateStat("stream.chokeSizeBegin", "Number of outstanding messages when we started to choke", "Stream", RATES);
        _context.statManager().createRequiredRateStat("stream.chokeSizeEnd", "Number of outstanding messages when we stopped being choked", "Stream", RATES);
        // Stats for PacketQueue
        _context.statManager().createRequiredRateStat("stream.con.sendMessageSize", "Size of a message sent on a connection", "Stream", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRequiredRateStat("stream.con.sendDuplicateSize", "Size of a message resent on a connection", "Stream", RATES);
        if (_context.getProperty(PROP_AUTOBAN, DEFAULT_AUTOBAN) != 0)
            new BanExpiry();
    }

    /**
     * Look up a connection by its inbound stream ID.
     *
     * @param id the inbound stream ID
     * @return the connection, or null if not found
     */
    Connection getConnectionByInboundId(long id) {
        return _connectionByInboundId.get(Long.valueOf(id));
    }

    /**
     * Look up a connection by its outbound stream ID.
     * Not guaranteed to be unique, but in case we receive more than one packet
     * on an inbound connection that we haven't ack'ed yet...
     *
     * @param id the outbound stream ID
     * @return the connection, or null if not found
     */
    Connection getConnectionByOutboundId(long id) {
        return _connectionByOutboundId.get(id);
    }

    /**
     *  Was this conn recently closed?
     *  @since 0.9.12
     */
    public boolean wasRecentlyClosed(long inboundID) {
        synchronized(_recentlyClosed) {
            // use get() instead of containsKey() to update LRU access order,
            // as we may get additional packets with the same ID
            return _recentlyClosed.get(Long.valueOf(inboundID)) != null;
        }
    }

    /**
     * Socket accept() timeout.
     * @param x
     */
    public void setSoTimeout(long x) {
        _soTimeout = x;
    }

    /**
     * Socket accept() timeout.
     * @return accept timeout in ms.
     */
    public long getSoTimeout() {
        return _soTimeout;
    }

    /**
     * Enable or disable acceptance of incoming connections.
     * When first enabled, initializes throttlers if needed.
     *
     * @param allow true to accept incoming connections
     */
    public void setAllowIncomingConnections(boolean allow) {
        _connectionHandler.setActive(allow);
        if (allow) {
            synchronized(this) {
                if (!_throttlersInitialized) {
                    updateOptions();
                    _throttlersInitialized = true;
                }
            }
        }
    }

    /**
     * Update the throttler options
     *
     * @since 0.9.3
     */
    public synchronized void updateOptions() {
            if ((_defaultOptions.getMaxConnsPerMinute() > 0 || _defaultOptions.getMaxTotalConnsPerMinute() > 0) &&
                _minuteThrottler == null) {
               _context.statManager().createRateStat("stream.con.throttledMinute", "Dropped for conn limit", "Stream", RATES);
               _minuteThrottler = new ConnThrottler(_defaultOptions.getMaxConnsPerMinute(), _defaultOptions.getMaxTotalConnsPerMinute(),
                                                     (long) 60*1000, _timer.getSharedTimer());
            } else if (_minuteThrottler != null) {
               _minuteThrottler.updateLimits(_defaultOptions.getMaxConnsPerMinute(), _defaultOptions.getMaxTotalConnsPerMinute());
            }
            if ((_defaultOptions.getMaxConnsPerHour() > 0 || _defaultOptions.getMaxTotalConnsPerHour() > 0) &&
                _hourThrottler == null) {
               _context.statManager().createRateStat("stream.con.throttledHour", "Dropped for conn limit", "Stream", RATES);
               _hourThrottler = new ConnThrottler(_defaultOptions.getMaxConnsPerHour(), _defaultOptions.getMaxTotalConnsPerHour(),
                                                   60*(long) 60*1000, _timer.getSharedTimer());
            } else if (_hourThrottler != null) {
               _hourThrottler.updateLimits(_defaultOptions.getMaxConnsPerHour(), _defaultOptions.getMaxTotalConnsPerHour());
            }
            if ((_defaultOptions.getMaxConnsPerDay() > 0 || _defaultOptions.getMaxTotalConnsPerDay() > 0) &&
                _dayThrottler == null) {
               _context.statManager().createRateStat("stream.con.throttledDay", "Dropped for conn limit", "Stream", RATES);
               _dayThrottler = new ConnThrottler(_defaultOptions.getMaxConnsPerDay(), _defaultOptions.getMaxTotalConnsPerDay(),
                                                  24*60*(long) 60*1000, _timer.getSharedTimer());
            } else if (_dayThrottler != null) {
               _dayThrottler.updateLimits(_defaultOptions.getMaxConnsPerDay(), _defaultOptions.getMaxTotalConnsPerDay());
            }
    }

    /**
     *  Whether incoming connections are accepted.
     *  @return if we should accept connections
     */
    public boolean getAllowIncomingConnections() {
        return _connectionHandler.getActive();
    }

    /**
     * Create a new connection based on the SYN packet we received.
     *
     * @param synPacket SYN packet to process
     * @return created Connection with the packet's data already delivered to it,
     *         or null if the syn's streamId was already taken,
     *         or if the connection was rejected
     */
    public Connection receiveConnection(Packet synPacket) {
        Destination from = synPacket.getOptionalFrom();
        if (from == null) {
            if (_log.shouldWarn())
                _log.warn("Received a SYN packet without FROM: " + synPacket);
            return null;
        }
        ByteArray ba = _cache.acquire();
        boolean sigOk = synPacket.verifySignature(_context, ba.getData());
        _cache.release(ba);
        if (sigOk) {
            long[] nacks = synPacket.getNacks();
            if (nacks != null && nacks.length == 8) {
                // we use the packet's session because it may be a subsession
                Hash hash = synPacket.getSession().getMyDestination().calculateHash();
                byte[] h = hash.getData();
                for (int i = 0; i < 8; i++) {
                    if (nacks[i] != DataHelper.fromLong(h, i << 2, 4)) {
                        if (_log.shouldWarn()) {
                            // glue it back together for logging only
                            byte[] g = new byte[32];
                            for (int j = 0; j < 8; j++) {
                                DataHelper.toLong(g, j << 2, 4, nacks[j]);
                            }
                            Hash ghash = new Hash(g);
                            _log.warn("Signature passed but hash failed, sending reset, expected: " + hash.toBase32() +
                                      " received: " + ghash.toBase32() +
                                      " from: " + from.calculateHash().toBase32());
                        }
                        _packetHandler.sendResetUnverified(synPacket);
                        return null;
                    }
                }
                if (sigOk && _log.shouldInfo())
                    _log.info("Validated SYN NACKS from: " + from.toBase32());
            }
        }
        if (!sigOk) {
            if (_log.shouldWarn())
                _log.warn("Received UNSIGNED / FORGED SYN packet apparently from " + from.toBase32() + ": " + synPacket);
            return null;
        }

        boolean reject = false;
        int retryAfter = 0;
        String client = synPacket.getOptionalFrom() == null ? "unknown" :
                        synPacket.getOptionalFrom().toBase32().substring(0, 6);

        // Sub-second SYN burst gate: a dest that blasts > tempBanSynBurst SYNs
        // within tempBanSynRate-ms (a legit announce client never does) is
        // auto-banned immediately, BEFORE the stream budget or refusal counters
        // are consulted, so the burst cannot first consume budget slots.
        if (from != null) {
            Hash bh = from.calculateHash();
            long now = _context.clock().now();
            if (!isTempBanned(bh, now) && checkSynBurst(bh, now)) {
                banPeer(bh, "exceeded max " + _synBurst + " SYNs/" + _synRateMs + "ms",
                        now);
            }
        }

        if (tooManyStreamsForDest(from.calculateHash(), getEffectiveMaxStreams())) {
            // If already temp-banned, drop the SYN silently before any processing,
            // SYN-ACK, or refusal logging, so a banned dest can't keep hammering.
            if (from != null && isTempBanned(from.calculateHash(), _context.clock().now())) {
                if ((!_defaultOptions.getDisableRejectLogging()) && _log.shouldDebug())
                    _log.debug("Dropping SYN from temp-banned " + from.toBase32().substring(0, 6));
                return null;
            }
            if ((!_defaultOptions.getDisableRejectLogging()) && _log.shouldWarn()) {
                // Log the peer so a rejection burst can be attributed to a single source rather than
                // a faceless count. Matches the client logging in the shouldRejectConnection() branch below.
                _log.warn("Refusing connection from [" + client + "] -> Maximum " + getLogMaxStreams() +
                          " concurrent streams exceeded");
            }
            // A dest refused this many times in the current window is hammering its
            // per-dest budget; promote it to an autoban so its SYNs are dropped
            // before they can keep starving legitimate announces.
            if (from != null) {
                Hash h = from.calculateHash();
                if (refusalThresholdMet(_refusalCounter.increment(h), _tempBanRefusals))
                    banPeer(h, "exceeded max " + _tempBanRefusals + " refusals/min", _context.clock().now());
            }
            reject = true;
            retryAfter = 120;
        } else {
            // this may not be right if more than one is enabled
            Reason why = shouldRejectConnection(synPacket);
            if (why != null) {
                if ((!_defaultOptions.getDisableRejectLogging()) && !why.isSilent() && _log.shouldWarn())
                    _log.warn("Refusing connection from [" + client + "] -> " + why);
                reject = true;
                retryAfter = why.getSeconds();
            }
        }

        _context.statManager().addRateData("stream.receiveActive", 1);

        if (reject) {
            String resp = _defaultOptions.getLimitAction();
            if ("drop".equals(resp)) {
                // always drop
                return null;
            }
            Hash h = from.calculateHash();
            if (retryAfter >= MAX_TIME) {
                // always drop these regardless of setting
                return null;
            }

            if ((_minuteThrottler != null && _minuteThrottler.isOverBy(h, getDropOverLimit())) ||
                (_hourThrottler != null && _hourThrottler.isOverBy(h, getDropOverLimit())) ||
                (_dayThrottler != null && _dayThrottler.isOverBy(h, getDropOverLimit()))) {
                // A signed RST/close packet + ElGamal + session tags is fairly expensive, so
                // once a limit is significantly exceeded for a particular peer, don't even send it.
                // This is a tradeoff, because it will keep retransmitting the SYN for a while,
                // thus more inbound, but let's not spend several KB on the outbound.
                if (_log.shouldInfo())
                    _log.info("Dropping limit response to " + from.toBase32());
                return null;
            }

            boolean reset = resp == null || resp.equals("reset") || resp.length() <= 0 ||
                            synPacket.getLocalPort() == 443;
            boolean http = !reset && "http".equals(resp);
            boolean custom = !(reset || http);
            String sendResponse;
            if (http) {
                if (retryAfter > 0)
                    sendResponse = LIMIT_HTTP_RESPONSE.replace("900", Integer.toString(retryAfter));
                else
                    sendResponse = LIMIT_HTTP_RESPONSE.replace("Retry-After: 900\r\n", "");
            } else if (custom) {
                sendResponse = resp.replace("\\r", "\r").replace("\\n", "\n");
            } else {
                sendResponse = null;
            }

            PacketLocal reply = new PacketLocal(_context, from, synPacket.getSession());
            if (sendResponse != null) {
                reply.setFlag(Packet.FLAG_SYNCHRONIZE | Packet.FLAG_CLOSE | Packet.FLAG_SIGNATURE_INCLUDED);
                reply.setSequenceNum(0);
                ByteArray payload = new ByteArray(DataHelper.getUTF8(sendResponse));
                reply.setPayload(payload);
            } else {
                reply.setFlag(Packet.FLAG_RESET | Packet.FLAG_SIGNATURE_INCLUDED);
            }
            reply.setAckThrough(synPacket.getSequenceNum());
            reply.setSendStreamId(synPacket.getReceiveStreamId());
            long rcvStreamId = assignRejectId();
            reply.setReceiveStreamId(rcvStreamId);
            reply.setOptionalFrom();
            reply.setLocalPort(synPacket.getLocalPort());
            reply.setRemotePort(synPacket.getRemotePort());
            if (_log.shouldInfo())
                _log.info("Over limit, sending " + reply + " to " + from.toBase32());
            // this just sends the packet - no retries or whatnot
            _outboundQueue.enqueue(reply);
            return null;
        }

        ConnectionOptions opts = new ConnectionOptions(_defaultOptions);
        opts.setPort(synPacket.getRemotePort());
        opts.setLocalPort(synPacket.getLocalPort());

        // set up the MTU for the connection
        int size;
        if (synPacket.isFlagSet(Packet.FLAG_MAX_PACKET_SIZE_INCLUDED)) {
            size = synPacket.getOptionalMaxSize();
            if (size < ConnectionOptions.MIN_MESSAGE_SIZE) {
                // log.error? connection reset?
                size = ConnectionOptions.MIN_MESSAGE_SIZE;
            }
        } else {
            // specs not clear if MTU may be omitted from SYN
            size = ConnectionOptions.DEFAULT_MAX_MESSAGE_SIZE;
        }
        int mtu = opts.getMaxMessageSize();
        if (size < mtu) {
            if (_log.shouldInfo())
                _log.info("Reducing MTU for Inbound connection to " + size
                          + " bytes from " + mtu);
            opts.setMaxMessageSize(size);
            opts.setMaxInitialMessageSize(size);
        } else if (size > opts.getMaxInitialMessageSize()) {
            if (size > mtu)
                size = mtu;
            if (size != mtu) {
                opts.setMaxMessageSize(size);
                if (_log.shouldInfo())
                    _log.info("Increasing MTU for Inbound connection to " + size + " bytes from " + mtu);
            }
            opts.setMaxInitialMessageSize(size);
        }

        Connection con = new Connection(_context, this, synPacket.getSession(), _schedulerChooser,
                                        _timer.getSharedTimer(), _outboundQueue, _conPacketHandler, opts, true);
        _tcbShare.updateOptsFromShare(con);
        assignReceiveStreamId(con);
        addStream(from.calculateHash());

        // finally, we know enough that we can log the packet with the conn filled in
        if (I2PSocketManagerFull.pcapWriter != null &&
            _context.getBooleanProperty(I2PSocketManagerFull.PROP_PCAP))
            synPacket.logTCPDump(con);
        try {
            // This validates the packet, and sets the con's SendStreamID and RemotePeer
            con.getPacketHandler().receivePacket(synPacket, con);
        } catch (I2PException ie) {
            _connectionByInboundId.remove(Long.valueOf(con.getReceiveStreamId()));
            removeStream(con.getRemotePeer() == null ? null : con.getRemotePeer().calculateHash());
            return null;
        }

        _context.statManager().addRateData("stream.connectionReceived", 1);
        return con;
    }

    /**
     *  Process a ping by checking for throttling, etc., then sending a pong.
     *
     *  @param con null if unknown
     *  @param ping Ping packet to process, must have From and Sig fields,
     *              with signature already verified, only if answerPings() returned true
     *  @return true if we sent a pong
     *  @since 0.9.12 from PacketHandler.receivePing()
     */
    public boolean receivePing(Connection con, Packet ping) {
        Destination dest = ping.getOptionalFrom();
        if (dest == null)
            return false;
        if (con == null) {
            // Use the same throttling as for connections
            Reason why = shouldRejectConnection(ping);
            if (why != null) {
                if ((!_defaultOptions.getDisableRejectLogging()) || _log.shouldWarn())
                    _log.logAlways(Log.WARN, "Dropping ping: " + why + "\n* From: " + dest.toBase32());
                return false;
            }
        } else {
            // in-connection ping to a 3rd party ???
            if (!dest.equals(con.getRemotePeer())) {
                _log.logAlways(Log.WARN, "Dropping ping to 3rd party from: " + con.getRemotePeer().toBase32() +
                                         "\n* Target: " + dest.toBase32());
                return false;
            }
        }
        PacketLocal pong = new PacketLocal(_context, dest, ping.getSession());
        pong.setFlag(Packet.FLAG_ECHO | Packet.FLAG_NO_ACK);
        pong.setReceiveStreamId(ping.getSendStreamId());
        pong.setLocalPort(ping.getLocalPort());
        pong.setRemotePort(ping.getRemotePort());
        // as of 0.9.18, return the payload
        ByteArray payload = ping.getPayload();
        if (payload != null) {
            if (payload.getValid() > MAX_PONG_PAYLOAD)
                payload.setValid(MAX_PONG_PAYLOAD);
            pong.setPayload(payload);
        }

        int pongDelay = _context.getProperty(PROP_MAX_PONG_DELAY, MAX_PONG_DELAY);
        int randomDelay = _context.random().nextInt(pongDelay);
        boolean enableDelay = _context.getProperty(PROP_ENABLE_PONG_DELAY, DEFAULT_ENABLE_PONG_DELAY);
        if (enableDelay) {
            try { Thread.sleep(randomDelay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); /* ignored */ }
            if (_log.shouldInfo())
                _log.info("Sending pong to: " + dest.toBase32() + " with random delay of " + randomDelay + "ms");
        } else {
            if (_log.shouldInfo())
                _log.info("Sending pong to: " + dest.toBase32());
        }
        _outboundQueue.enqueue(pong);
        return true;
    }

    /**
     *  Pick a new random stream ID for the con and assign it,
     *  taking care to avoid duplicates, and put it in the connection table.
     *
     *  @since 0.9.12 consolidated from receiveConnection() and connect()
     */
    private void assignReceiveStreamId(Connection con) {
        long receiveId;
        synchronized(_recentlyClosed) {
            Long rcvID;
            do {
                receiveId = _context.random().nextLong(Packet.MAX_STREAM_ID-1)+1;
                rcvID = Long.valueOf(receiveId);
            } while (_recentlyClosed.containsKey(rcvID) ||
                     _pendingPings.containsKey(rcvID) ||
                     _connectionByInboundId.putIfAbsent(rcvID, con) != null);
        }
        con.setReceiveStreamId(receiveId);
    }

    /**
     *  Pick a new random stream ID for a ping and assign it,
     *  taking care to avoid duplicates, and return it.
     *
     *  @since 0.9.12
     */
    private long assignPingId(PingRequest req) {
        long receiveId;
        synchronized(_recentlyClosed) {
            Long rcvID;
            do {
                receiveId = _context.random().nextLong(Packet.MAX_STREAM_ID-1)+1;
                rcvID = Long.valueOf(receiveId);
            } while (_recentlyClosed.containsKey(rcvID) ||
                     _connectionByInboundId.containsKey(rcvID) ||
                     _pendingPings.putIfAbsent(rcvID, req) != null);
        }
        return receiveId;
    }

    /**
     *  Pick a new random stream ID that we are rejecting,
     *  taking care to avoid duplicates, and return it.
     *
     *  @since 0.9.34
     */
    private long assignRejectId() {
        long receiveId;
        synchronized(_recentlyClosed) {
            Long rcvID;
            do {
                receiveId = _context.random().nextLong(Packet.MAX_STREAM_ID-1)+1;
                rcvID = Long.valueOf(receiveId);
            } while (_recentlyClosed.containsKey(rcvID) ||
                     _connectionByInboundId.containsKey(rcvID));
            _recentlyClosed.put(rcvID, DUMMY);
        }
        return receiveId;
    }

    /**
     * Default stream delay maximum when no connect timeout is set.
     * Tunable via i2p.streaming.defaultStreamDelayMax (default: 10000).
     * @return the default stream delay max
     */
    private long getDefaultStreamDelayMax() {
        return _context.getProperty("i2p.streaming.defaultStreamDelayMax", 10*1000);
    }

    /**
     * Build a new connection to the given peer.  This blocks if there is no
     * connection delay, otherwise it returns immediately.
     *
     * @param peer Destination to contact, non-null
     * @param opts Connection's options
     * @param session generally the session from the constructor, but could be a subsession
     * @return new connection, or null if we have exceeded our limit
     */
public Connection connect(Destination peer, ConnectionOptions opts, I2PSession session) {
          if (peer == null) {throw new NullPointerException();}
          Connection con = null;
          long connectStart = _context.clock().now();

          // Try to acquire a pooled connection first
          if (isPoolEnabled()) {
              con = acquireFromPool(peer);
              if (con != null) {
                  // Update connection options and session if needed
                  con.setRemotePeer(peer);
                  // Assign new stream IDs since this is a new logical connection
                  assignReceiveStreamId(con);
                  addStream(peer.calculateHash());
                  if (_log.shouldDebug()) {
                      _log.debug("Reusing pooled connection to " + peer.calculateHash().toBase64().substring(0,6));
                  }
                  // Skip to post-creation setup (fromPool=true — skip cooldown)
                  return finalizeConnection(con, peer, opts, connectStart, true);
              }
          }
          long expiration = _context.clock().now();
          long tmout = opts.getConnectTimeout();
          int max = getEffectiveMaxStreams();
          if (tmout <= 0) {expiration += getDefaultStreamDelayMax();}
          else {expiration += tmout;}
          _numWaiting.incrementAndGet();
          while (true) {
              long remaining = expiration - _context.clock().now();
              if (remaining <= 0) {
                  _log.logAlways(Log.WARN, "Refusing connection -> Maximum " + getLogMaxStreams() + " concurrent streams exceeded");
                  _numWaiting.decrementAndGet();
                  return null;
              }

if (tooManyStreamsForDest(peer.calculateHash(), getEffectiveMaxStreams())) {
                  // allow a full buffer of pending/waiting streams
                  if (_numWaiting.get() > max) {
                      _log.logAlways(Log.WARN, "Refusing connection -> Maximum " + getLogMaxStreams() + " concurrent streams exceeded, with " +
                                               _numWaiting + " queued");
                      _numWaiting.decrementAndGet();
                      return null;
                  }

                   // no remaining streams, let's wait a bit
                   try { Thread.sleep(remaining/4); } catch (InterruptedException ie) { /* ignored */ }
              } else {
                  con = new Connection(_context, this, session, _schedulerChooser, _timer.getSharedTimer(),
                                        _outboundQueue, _conPacketHandler, opts, false);
                  con.setRemotePeer(peer);
                  assignReceiveStreamId(con);
                  addStream(peer.calculateHash());
                  break; // stop looping as a psuedo-wait
              }
          }

          // Delegate to shared finalizeConnection for cooldown, waitForConnect, and stats.
          // fromPool=false so cooldown is applied for new connections.
          return finalizeConnection(con, peer, opts, connectStart, false);
      }

      /**
       * Common post-creation setup for both pooled and new connections.
       * Handles cooldown, waitForConnect, stats recording.
       * @param fromPool if true, skip cooldown delay (connection already established)
       */
      private Connection finalizeConnection(Connection con, Destination peer,
                                             ConnectionOptions opts, long connectStart,
                                             boolean fromPool) {
          // ok we're in...
          Hash destHash = peer.calculateHash();
          // Skip cooldown for pooled connections — the connection is already established
          if (!fromPool) {
              Long lastFailure = _destFailures.get(destHash);
              if (lastFailure != null) {
                  long elapsed = _context.clock().now() - lastFailure;
                  if (elapsed < getDestCooldownMs() && elapsed >= 0) {
                      long delay = getDestCooldownMs() - elapsed;
                      if (_log.shouldWarn())
                            _log.warn("Delaying connect to [" + destHash.toBase64().substring(0,6) +
                                      "] for " + delay + "ms (cooldown from previous failure)");
                      synchronized (_cooldownLock) {
                          try {
                               _cooldownLock.wait(delay);
                           } catch (InterruptedException ie) {
                              Thread.currentThread().interrupt();
                          }
                      }
                  } else {
                      _destFailures.remove(destHash, lastFailure);
                  }
              }
          }
          con.eventOccurred();

           if (_log.shouldDebug())
               _log.debug("Connect() conDelay = " + opts.getConnectDelay());
           if (opts.getConnectDelay() <= 0) {
               con.waitForConnect();
            } else {
                // The SYN send is delayed by connectDelay ms (SchedulerPreconnect).
                // Wait for the connection to establish with enough margin for the
                // SYN delay plus the configured connect timeout (which governs
                // RTO doubling and retry budget). The connectTimeout from opts
                // (_connectTimeout, default 30s) is the authoritative limit.
                // Sets _connectionError on timeout.
               int boundedTimeout = opts.getConnectDelay() + (int) opts.getConnectTimeout();
               con.waitForConnect(boundedTimeout);
          }
          long connectElapsed = _context.clock().now() - connectStart;
          if (_log.shouldInfo()) {
              String err = con.getConnectionError();
              if (err != null) {
                  _log.info("ConnectionManager.connect() to [" + destHash.toBase64().substring(0,6) +
                            "] failed in " + connectElapsed + "ms: " + err);
              } else {
                  _log.info("ConnectionManager.connect() to [" + destHash.toBase64().substring(0,6) +
                            "] succeeded in " + connectElapsed + "ms");
              }
          }
           // Record failure for cooldown, clear on success
           if (con.getConnectionError() != null) {
              _context.statManager().addRateData("stream.connectFailed", connectElapsed, connectElapsed);
              _destFailures.put(destHash, _context.clock().now());
              // Opportunistic trim: prevent unbounded growth from abandoned destinations
              if (_destFailures.size() > 256) {
                  long cutoff = _context.clock().now() - getDestCooldownMs();
                  for (Map.Entry<Hash, Long> e : _destFailures.entrySet()) {
                      if (e.getValue() < cutoff)
                          _destFailures.remove(e.getKey(), e.getValue());
                  }
              }
          } else {
             _context.statManager().addRateData("stream.connectTime", connectElapsed, connectElapsed);
             _destFailures.remove(destHash);
         }
         // safe decrement
         for (;;) {
             int n = _numWaiting.get();
             if (n <= 0)
                 break;
             if (_numWaiting.compareAndSet(n, n - 1))
                 break;
         }

         _context.statManager().addRateData("stream.connectionCreated", 1);
         return con;
    }

    /** Locked too many streams. */
    /**
     * Encapsulates a connection rejection reason with an optional
     * Retry-After duration in seconds.
     *
     * @since 0.9.49
     */
    private static class Reason {
        private final String txt;
        private final int seconds;
        private final boolean silent;

        /**
         * Reason.
         *
         * @param text description
         * @param secs seconds for the Retry-After header
         */
        public Reason(String text, int secs) {
            txt = text; seconds = secs; silent = false;
        }

        /**
         * Reason.
         *
         * @param text description
         * @param secs seconds for the Retry-After header
         * @param silent if true, suppress the per-rejection WARN log. Used for
         *        repeated rejections of an already-temp-banned dest so a hammering
         *        peer doesn't spam the log on every SYN.
         */
        public Reason(String text, int secs, boolean silentFlags) {
            txt = text; seconds = secs; silent = silentFlags;
        }

        /**
         * Text description of the reason.
         */
        @Override
        public String toString() { return txt; }

        /**
         * Seconds for the Retry-After header.
         * @return the seconds
         */
        public int getSeconds() { return seconds; }

        /**
         * Whether per-rejection logging should be suppressed.
         * @return true to skip the WARN log for this rejection
         */
        public boolean isSilent() { return silent; }
    }

    private static final int MAX_TIME = 9999999;


    /**
     * Check if a connection should be rejected based on blacklists,
     * access lists, throttling, and the connection filter.
     *
     * @param syn the incoming SYN packet
     * @return a Reason with seconds for Retry-After header; MAX_TIME for
     *         drop, 0 if unknown; or null if not rejected
     */
    private Reason shouldRejectConnection(Packet syn) {
        // unfortunately we don't have access to the router client manager here,
        // so we can't whitelist local access
        Destination from = syn.getOptionalFrom();
        if (from == null)
            return new Reason("null", MAX_TIME);
        Hash h = from.calculateHash();

        // As of 0.9.9, run the blacklist checks BEFORE the port counters,
        // so blacklisted dests will not increment the counters and
        // possibly trigger total-counter blocks for others.

        // Temp autoban first, so a banned dest dumps all SYNs before they
        // consume its per-dest budget or the per-peer throttlers below.
        // Reason is reported once at ban time; per-rejection logging is silent so
        // a hammering dest doesn't spam WARN on every SYN.
        if (isTempBanned(h, _context.clock().now())) {
            String why = tempBanReason(h);
            String txt = "Temp banned (" + getTempBanMinutes() + " min)" +
                         (why != null ? " - " + why : "");
            return new Reason(txt, MAX_TIME, true);
        }

        // if the sig is absent or bad it will be caught later (in CPH)
        String hashes = _context.getProperty(PROP_BLACKLIST, "");
        if (!currentBlacklist.equals(hashes)) {
            // rebuild _globalBlacklist when property changes
            synchronized(_globalBlacklist) {
                if (!hashes.isEmpty()) {
                    Set<Hash> newSet = new HashSet<>();
                    StringTokenizer tok = new StringTokenizer(hashes, ",; ");
                    while (tok.hasMoreTokens()) {
                        String hashstr = tok.nextToken();
                        Hash hh = ConvertToHash.getHash(hashstr);
                        if (hh != null)
                            newSet.add(hh);
                        else
                            _log.error("Bad blacklist entry: " + hashstr);
                    }
                    _globalBlacklist.addAll(newSet);
                    _globalBlacklist.retainAll(newSet);
                    currentBlacklist = hashes;
                } else {
                    _globalBlacklist.clear();
                    currentBlacklist = "";
                }
            }
        }
        if (!hashes.isEmpty() && _globalBlacklist.contains(h))
            return new Reason("Blacklisted globally", MAX_TIME);

        if (_defaultOptions.isAccessListEnabled() &&
            !_defaultOptions.getAccessList().contains(h))
            return new Reason("not whitelisted", MAX_TIME);
        if (_defaultOptions.isBlacklistEnabled() &&
            _defaultOptions.getBlacklist().contains(h))
            return new Reason("blacklisted", MAX_TIME);

        if (_dayThrottler != null && _dayThrottler.shouldThrottle(h)) {
            _context.statManager().addRateData("stream.con.throttledDay", 1);
            banPeer(h, "exceeded max " + _defaultOptions.getMaxConnsPerDay() + " conns/day",
                    _context.clock().now());
            if (_defaultOptions.getMaxConnsPerDay() <= 0)
                return new Reason("Total daily limit of " + _defaultOptions.getMaxTotalConnsPerDay() +
                        " connections reached", 86400);
            else if (_defaultOptions.getMaxTotalConnsPerDay() <= 0)
                return new Reason("Per-peer daily limit of " + _defaultOptions.getMaxConnsPerDay() +
                        " connections reached", 86400);
            else
                return new Reason("Per-peer limit of " + _defaultOptions.getMaxConnsPerDay() +
                        " or total daily limit of " + _defaultOptions.getMaxTotalConnsPerDay() +
                        " connections reached", 86400);
        }
        if (_hourThrottler != null && _hourThrottler.shouldThrottle(h)) {
            _context.statManager().addRateData("stream.con.throttledHour", 1);
            banPeer(h, "exceeded max " + _defaultOptions.getMaxConnsPerHour() + " conns/hour",
                    _context.clock().now());
            if (_defaultOptions.getMaxConnsPerHour() <= 0)
                return new Reason("total hourly limit of " + _defaultOptions.getMaxTotalConnsPerHour() +
                        " reached", 3600);
            else if (_defaultOptions.getMaxTotalConnsPerHour() <= 0)
                return new Reason("Per-peer hourly limit of " + _defaultOptions.getMaxConnsPerHour() +
                        " connections", 3600);
            else
                return new Reason("Per-peer hourly limit of " + _defaultOptions.getMaxConnsPerHour() +
                        " or total hourly limit of " + _defaultOptions.getMaxTotalConnsPerHour() +
                        " connections reached", 3600);
        }
        if (_minuteThrottler != null && _minuteThrottler.shouldThrottle(h)) {
            _context.statManager().addRateData("stream.con.throttledMinute", 1);
            banPeer(h, "exceeded max " + _defaultOptions.getMaxConnsPerMinute() + " conns/min",
                    _context.clock().now());
            if (_defaultOptions.getMaxConnsPerMinute() <= 0)
                return new Reason("Total limit of " + _defaultOptions.getMaxTotalConnsPerMinute() +
                        " connections per minute reached", 60);
            else if (_defaultOptions.getMaxTotalConnsPerMinute() <= 0)
                return new Reason("Per-peer limit of " + _defaultOptions.getMaxConnsPerMinute() +
                        " connections per minute reached", 60);
            else
                return new Reason("Per-peer limit of " + _defaultOptions.getMaxConnsPerMinute() +
                        " connections per minute or total limit of " + _defaultOptions.getMaxTotalConnsPerMinute() +
                        " connections per minute reached", 60);
        }

        if (!_connectionFilter.allowDestination(from)) {
            return new Reason("Destination blocked by Tunnel Filter", 0);
        }

        return null;
    }


    /**
     * Message handler for this manager.
     * @return the message handler
     */
    public MessageHandler getMessageHandler() { return _messageHandler; }
    /**
     * Packet handler for this manager.
     * @return the packet handler
     */
    public PacketHandler getPacketHandler() { return _packetHandler; }

    /**
     * This is the primary session only
     *
     * @return the session
     */
    public I2PSession getSession() { return _session; }

    /**
     * Update opts from share.
     * @param con the connection
     */
    public void updateOptsFromShare(Connection con) { _tcbShare.updateOptsFromShare(con); }
    /**
     * Update share opts.
     * @param con the connection
     */
    public void updateShareOpts(Connection con) { _tcbShare.updateShareOpts(con); }
    /**
     * Connection handler for this manager.
     * @return the connection handler
     */
    public ConnectionHandler getConnectionHandler() { return _connectionHandler; }
    /**
     * Outbound packet queue.
     * @return the outbound packet queue
     */
    public PacketQueue getPacketQueue() { return _outboundQueue; }
    /**
     * Do we respond to pings that aren't on an existing connection?
     *
     * @return true if we answer pings
     */
    public boolean answerPings() { return _defaultOptions.getAnswerPings(); }

    /**
     * Something b0rked hard, so kill all of our connections without mercy.
     * Don't bother sending close packets.
     * This will not close the ServerSocket.
     * This will not kill the timer threads.
     *
     * CAN continue to use the manager.
     */
    public void disconnectAllHard() {
        for (Iterator<Connection> iter = _connectionByInboundId.values().iterator(); iter.hasNext(); ) {
            Connection con = iter.next();
            con.disconnect(false, false);
            iter.remove();
        }
        synchronized(_recentlyClosed) {
            _recentlyClosed.clear();
        }
        _pendingPings.clear();
        synchronized (_cooldownLock) {
            _cooldownLock.notifyAll();
        }
        // Timer threads are shared via ctx.simpleTimer2() — no per-pool threads to stop.
    }

    /**
     * Kill all connections and the timers.
     * Don't bother sending close packets.
     * As of 0.9.17, this will close the ServerSocket, killing one thread in accept().
     *
     * CANNOT continue to use the manager or restart.
     *
     * @since 0.9.7
     */
    public void shutdown() {
        disconnectAllHard();
        _destFailures.clear();
        int protocol = I2PSession.PROTO_STREAMING;
        _session.removeListener(protocol, _defaultOptions.getLocalPort());
        _tcbShare.stop();
        _timer.stop();
        _outboundQueue.close();
        _connectionHandler.setActive(false);
    }

    /**
     * Wrapper for a pooled connection with metadata.
     * @since 0.9.71+
     */
    private static class PooledConnection {
        final Connection connection;
        final long pooledAt;
        final AtomicLong lastUsed;

        PooledConnection(Connection con, long now) {
            this.connection = con;
            this.pooledAt = now;
            this.lastUsed = new AtomicLong(now);
        }

        /** Whether the pooled connection has exceeded max idle time. */
        boolean isStale(long maxIdleMs, long now) {
            return now - lastUsed.get() > maxIdleMs;
        }

        /** Update the last-used timestamp. */
        void touch(long now) {
            lastUsed.set(now);
        }
    }

    /**
     * Try to acquire a pooled connection for the given destination.
     * Returns a valid connection or null if none available.
     */
    private Connection acquireFromPool(Destination peer) {
        Hash destHash = peer.calculateHash();
        ConcurrentLinkedDeque<PooledConnection> pool = _streamPools.get(destHash);
        if (pool == null || pool.isEmpty()) {
            return null;
        }

        long now = _context.clock().now();
        long maxIdle = getPoolMaxIdleMs();
        PooledConnection pc;

        while ((pc = pool.pollFirst()) != null) {
            // Evict stale entries
            if (pc.isStale(maxIdle, now)) {
                if (_log.shouldDebug()) {
                    _log.debug("Evicting stale pooled connection to " + destHash.toBase64().substring(0, 6));
                }
                pc.connection.disconnect(false, false);
                continue;
            }
            // Validate: connection must be established and healthy
            if (pc.connection.getIsConnected() && pc.connection.getConnectionError() == null) {
                pc.touch(now);
                if (_log.shouldDebug()) {
                    _log.debug("Reusing pooled connection to " + destHash.toBase64().substring(0, 6) +
                               " (idle " + (now - pc.pooledAt) + "ms)");
                }
                return pc.connection;
            } else {
                // Unhealthy, discard
                pc.connection.disconnect(false, false);
            }
        }
        return null;
    }

    /**
     * Return a connection to the pool if healthy.
     * Called from removeConnection().
     */
    private void returnToPool(Connection con) {
        if (!isPoolEnabled())
            return;
        if (con == null || con.getRemotePeer() == null) {
            return;
        }
        // Only pool established, healthy connections
        if (!con.getIsConnected() || con.getConnectionError() != null) {
            return;
        }
        // Check if input/output streams are clean
        if (con.getInputStream().isLocallyClosed() || con.getOutputStream().getClosed()) {
            return;
        }

        Hash destHash = con.getRemotePeer().calculateHash();
        ConcurrentLinkedDeque<PooledConnection> pool = _streamPools.computeIfAbsent(destHash,
            k -> new ConcurrentLinkedDeque<>());

        // Enforce per-destination limit
        int maxPerDest = getPoolMaxPerDestination();
        while (pool.size() >= maxPerDest) {
            PooledConnection oldest = pool.pollFirst();
            if (oldest != null) {
                oldest.connection.disconnect(false, false);
            }
        }

        PooledConnection pc = new PooledConnection(con, _context.clock().now());
        pool.addLast(pc);

        if (_log.shouldDebug()) {
            _log.debug("Pooled connection to " + destHash.toBase64().substring(0, 6) +
                       " (pool size: " + pool.size() + ")");
        }
    }

    /**
     * Periodic pool cleanup — evicts stale connections.
     * Called from timer or on shutdown.
     */
    void cleanupPool() {
        long now = _context.clock().now();
        long maxIdle = getPoolMaxIdleMs();
        for (Map.Entry<Hash, ConcurrentLinkedDeque<PooledConnection>> entry : _streamPools.entrySet()) {
            ConcurrentLinkedDeque<PooledConnection> pool = entry.getValue();
            PooledConnection pc;
            while ((pc = pool.peekFirst()) != null && pc.isStale(maxIdle, now)) {
                pool.pollFirst();
                pc.connection.disconnect(false, false);
            }
        }
    }
    /** Register outbound id. */
    void registerOutboundId(Connection con) {
        long sendId = con.getSendStreamId();
        if (sendId > 0)
            _connectionByOutboundId.put(sendId, con);
    }

    /**
     * Remove a connection from the manager.
     *
     * @param con Connection to drop.
     */
    public void removeConnection(Connection con) {

        // Attempt to return to pool before removing
        returnToPool(con);

        Long rcvID = Long.valueOf(con.getReceiveStreamId());
        synchronized(_recentlyClosed) {
            _recentlyClosed.put(rcvID, DUMMY);
        }

            Object o = _connectionByInboundId.remove(Long.valueOf(con.getReceiveStreamId()));
            removeStream(con.getRemotePeer() == null ? null : con.getRemotePeer().calculateHash());
            long sendId = con.getSendStreamId();
            if (sendId > 0)
                _connectionByOutboundId.remove(sendId);
            boolean removed = (o == con);
            if (_log.shouldDebug())
                _log.debug("Connection removed? " + removed + " Remaining: "
                           + _connectionByInboundId.size() + "\n " + con);
            if (!removed && _log.shouldDebug())
                _log.debug("Failed to remove " + con + "\n" + _connectionByInboundId.values());

        if (removed) {
            _context.statManager().addRateData("stream.con.lifetimeMessagesSent", 1+con.getLastSendId(), con.getLifetime());
            MessageInputStream stream = con.getInputStream();
                long rcvd = 1 + stream.getHighestBlockId();
                long[] nacks = stream.getNacks();
                if (nacks != null)
                    rcvd -= nacks.length;
                _context.statManager().addRateData("stream.con.lifetimeMessagesReceived", rcvd, con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeBytesSent", con.getLifetimeBytesSent(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeBytesReceived", con.getLifetimeBytesReceived(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeDupMessagesSent", con.getLifetimeDupMessagesSent(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeDupMessagesReceived", con.getLifetimeDupMessagesReceived(), con.getLifetime());
            // Retransmission ratio in per-mille (resends per 1000 messages sent) — a
            // path-loss signal independent of message size, unlike sendDuplicateSize (bytes).
            long msgsSent = 1 + con.getLastSendId();
            if (msgsSent > 0) {
                long rtxPerMille = 1000L * con.getLifetimeDupMessagesSent() / msgsSent;
                _context.statManager().addRateData("stream.rtxRatio", rtxPerMille, con.getLifetime());
            }
            // Byte-weighted retransmit ratio: actual bandwidth overhead from
            // retransmission, complementary to the message-count ratio above.
            long bytesSent = con.getLifetimeBytesSent();
            if (bytesSent > 0) {
                long rtxBytesPerMille = 1000L * con.getLifetimeDupBytesSent() / bytesSent;
                _context.statManager().addRateData("stream.rtxRatioBytes", rtxBytesPerMille, con.getLifetime());
            }
            _context.statManager().addRateData("stream.con.lifetimeRTT", con.getOptions().getRTT(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeSendWindowSize", con.getOptions().getWindowSize(), con.getLifetime());
            if (I2PSocketManagerFull.pcapWriter != null)
                I2PSocketManagerFull.pcapWriter.flush();
        }
    }

    /** Connections currently managed.
     * @return set of Connection objects
     */
    public Set<Connection> listConnections() {
            return new HashSet<>(_connectionByInboundId.values());
    }

    /**
     *  Ping the destination and wait for a pong.
     *
     *  @param peer the destination
     *  @param timeoutMs greater than zero
     *  @return true if pong received
     */
    public boolean ping(Destination peer, long timeoutMs) {
        return ping(peer, 0, 0, timeoutMs, true, null);
    }

    /**
     *  Ping the destination and wait for a pong.
     *
     *  @param peer the destination
     *  @param fromPort the source port
     *  @param toPort the destination port
     *  @param timeoutMs greater than zero
     *  @return true if pong received
     *  @since 0.9.12 added port args
     */
    public boolean ping(Destination peer, int fromPort, int toPort, long timeoutMs) {
        return ping(peer, fromPort, toPort, timeoutMs, true, null);
    }

    /**
     *  Ping the destination, optionally waiting for a pong.
     *
     *  @param peer the destination
     *  @param fromPort the source port
     *  @param toPort the destination port
     *  @param timeoutMs greater than zero
     *  @param blocking true to block until pong
     *  @return true if blocking and pong received
     *  @since 0.9.12 added port args
     */
    public boolean ping(Destination peer, int fromPort, int toPort, long timeoutMs, boolean blocking) {
        return ping(peer, fromPort, toPort, timeoutMs, blocking, null);
    }

    /**
     *  Ping the destination, optionally waiting for a pong.
     *
     *  @param peer the destination
     *  @param fromPort the source port
     *  @param toPort the destination port
     *  @param timeoutMs greater than zero
     *  @param blocking true to block until pong
     *  @param notifier may be null
     *  @return true if blocking and pong received
     *  @since 0.9.12 added port args
     */
    public boolean ping(Destination peer, int fromPort, int toPort, long timeoutMs,
                        boolean blocking, PingNotifier notifier) {
        PingRequest req = new PingRequest(notifier);
        long id = assignPingId(req);
        PacketLocal packet = new PacketLocal(_context, peer, _session);
        packet.setSendStreamId(id);
        packet.setFlag(Packet.FLAG_ECHO |
                       Packet.FLAG_NO_ACK |
                       Packet.FLAG_SIGNATURE_INCLUDED);
        packet.setOptionalFrom();
        packet.setLocalPort(fromPort);
        packet.setRemotePort(toPort);
        if (timeoutMs > getMaxPingTimeout())
            timeoutMs = getMaxPingTimeout();
        if (_log.shouldInfo()) {
            _log.info(String.format("About to ping %s port %d from port %d timeout=%d blocking=%b",
                      peer.calculateHash().toString(), toPort, fromPort, timeoutMs, blocking));
        }

        _outboundQueue.enqueue(packet);
        packet.releasePayload();

        if (blocking) {
            synchronized (req) {
                if (!req.pongReceived())
                    try { req.wait(timeoutMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); /* ignored */ }
            }
            _pendingPings.remove(id);
        } else {
            PingFailed pf = new PingFailed(id, notifier);
            pf.schedule(timeoutMs);
        }

        return req.pongReceived();
    }

    /**
     *  Ping the destination with a payload and wait for the pong.
     *
     *  @param peer the destination
     *  @param fromPort the source port
     *  @param toPort the destination port
     *  @param timeoutMs greater than zero
     *  @param payload non-null, include in packet, up to 32 bytes may be returned in pong
     *                 not copied, do not modify
     *  @return the payload received in the pong, zero-length if none, null on failure or timeout
     *  @since 0.9.18
     */
    public byte[] ping(Destination peer, int fromPort, int toPort, long timeoutMs,
                        byte[] payload) {
        PingRequest req = new PingRequest(null);
        long id = assignPingId(req);
        PacketLocal packet = new PacketLocal(_context, peer, _session);
        packet.setSendStreamId(id);
        packet.setFlag(Packet.FLAG_ECHO |
                       Packet.FLAG_NO_ACK |
                       Packet.FLAG_SIGNATURE_INCLUDED);
        packet.setOptionalFrom();
        packet.setLocalPort(fromPort);
        packet.setRemotePort(toPort);
        /** Byte array. */
        packet.setPayload(new ByteArray(payload));
        if (timeoutMs > getMaxPingTimeout())
            timeoutMs = getMaxPingTimeout();
        if (_log.shouldInfo()) {
            _log.info(String.format("About to ping %s port %d from port %d timeout=%d payload=%d",
                      peer.calculateHash().toString(), toPort, fromPort, timeoutMs, payload.length));
        }

        _outboundQueue.enqueue(packet);
        packet.releasePayload();

        synchronized (req) {
            if (!req.pongReceived())
                try { req.wait(timeoutMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); /* ignored */ }
        }
        _pendingPings.remove(id);

        boolean ok = req.pongReceived();
        if (!ok)
            return null;
        ByteArray ba = req.getPayload();
        if (ba == null)
            return new byte[0];
        byte[] rv = new byte[ba.getValid()];
        System.arraycopy(ba, ba.getOffset(), rv, 0, ba.getValid());
        return rv;
    }

    /**
     *  The callback interface for a pong.
     *  Unused? Not part of the public streaming API.
     */
    public interface PingNotifier {
        /**
         *  Notify the caller that the ping completed.
         *  @param ok true if pong received; false if timed out
         */
        public void pingComplete(boolean ok);
    }

    /**
     * Timer event that removes a pending ping from the map
     * and notifies the caller on timeout.
     */
    private class PingFailed extends SimpleTimer2.TimedEvent {
        private final Long _id;
        private final PingNotifier _notifier;

        /**
         * PingFailed.
         */
        public PingFailed(Long id, PingNotifier notifier) {
            super(_timer.getSharedTimer());
            _id = id;
            _notifier = notifier;
        }

        /**
         * Remove the pending ping and notify the caller on timeout.
         */
        public void timeReached() {
            PingRequest pr = _pendingPings.remove(_id);
            if (pr != null) {
                if (_notifier != null)
                    _notifier.pingComplete(false);
                if (_log.shouldInfo())
                    _log.info("Ping failed");
            }
        }
    }

    /**
     *  Periodically drops temp-bans whose time has elapsed and resets the
     *  per-dest refusal window, so a ban always expires (24h default) and the
     *  refusal counter doesn't grow unbounded. Mirrors ConnThrottler.Cleaner.
     *  @since 0.9.71+
     */
    private class BanExpiry extends SimpleTimer2.TimedEvent {
        private static final long PERIOD = 60 * 1000;

        BanExpiry() {
            super(_context.simpleTimer2());
            schedule(PERIOD + (PERIOD / 2));
        }

        public void timeReached() {
            long now = _context.clock().now();
            // Refresh cached autoban config once per sweep so the hot path never
            // re-reads the property store on every validated SYN.
            _tempBanMs = _context.getProperty(PROP_TEMP_BAN_MINUTES, (int) DEFAULT_TEMP_BAN_MINUTES)
                        * 60L * 1000L;
            _tempBanRefusals = _context.getProperty(PROP_TEMP_BAN_REFUSALS, DEFAULT_TEMP_BAN_REFUSALS);
            _synRateMs = _context.getProperty(PROP_TEMP_BAN_RATE_MS, DEFAULT_TEMP_BAN_RATE_MS);
            _synBurst = _context.getProperty(PROP_TEMP_BAN_SYN_BURST, DEFAULT_TEMP_BAN_SYN_BURST);
            long ms = _tempBanMs;
            if (ms > 0) {
                _tempBanUntil.entrySet().removeIf(e -> {
                    if (e.getValue() <= now) {
                        _tempBanReason.remove(e.getKey());
                        return true;
                    }
                    return false;
                });
                if (_log.shouldDebug() && !_tempBanUntil.isEmpty())
                    _log.debug("Temp bans: " + _tempBanUntil.size());
            }
            // Drop burst-window state for dests whose window aged out, so a dest that
            // surged (but stayed under threshold) can't keep a stale entry indefinitely.
            long windowMs = _synRateMs;
            if (windowMs > 0)
                _recentSyns.entrySet().removeIf(e -> now - e.getValue()[0] >= windowMs);
            // Reconcile per-dest stream budgets against the live connection table once
            // per sweep, so any teardown that missed its removeStream() (e.g. a dest
            // torn down before the remote peer was established) cannot permanently
            // inflate a budget. Rebuild covers both drift and stale zero entries.
            _streamsByDest.clear();
            for (Connection con : _connectionByInboundId.values()) {
                Hash peerHash = con.getRemotePeer() == null ? null : con.getRemotePeer().calculateHash();
                if (peerHash != null)
                    addStream(peerHash);
            }
            // Refusals decay by half each 60s sweep instead of being cleared to
            // zero. A hard clear lets a sustained-but-spread flood (< burst + under
            // the full 60s-window threshold) slip through forever, since no single
            // 60s window ever accumulates >tempBanRefusals. Decay keeps a slow,
            // persistent abuser accumulating toward the ban while an isolated spike
            // (e.g. a legit announce rollout) fades to nothing within a few sweeps.
            _refusalCounter.decay(2);
            schedule(PERIOD);
        }
    }

    /**
     * Holds the state for a pending ping request, including
     * optional payload and notification callback.
     */
    private static class PingRequest {
        private boolean _ponged;
        private ByteArray _payload;
        private final PingNotifier _notifier;

        /**
         * Ping request.
         * @param notifier may be null
         */
        public PingRequest(PingNotifier notifier) {
            _notifier = notifier;
        }

        /**
         *  Record the pong and notify the caller.
         *  @param payload may be null
         */
        public void pong(ByteArray payload) {
            // static, no log
            synchronized (this) {
                _ponged = true;
                _payload = payload;
                notifyAll();
            }
            if (_notifier != null)
                _notifier.pingComplete(true);
        }

        /**
         * Whether a pong has been received.
         */
        public synchronized boolean pongReceived() { return _ponged; }

        /**
         *  Payload received in the pong.
         *  @return null if no payload or no pong received
         *  @since 0.9.18
         */
        public synchronized ByteArray getPayload() { return _payload; }
    }

    /**
     * Process a received pong response.
     *
     * @param pingId the ping stream ID to match
     * @param payload the pong payload, may be null
     */
    void receivePong(long pingId, ByteArray payload) {
        PingRequest req = _pendingPings.remove(Long.valueOf(pingId));
        if (req != null)
            req.pong(payload);
    }

    /**
     *  @since 0.9.21
     */
    @Override
    public String toString() {
        return "ConnectionManager for " + _session;
    }
}
