package net.i2p.client.streaming.impl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.IncomingConnectionFilter;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.stat.RateConstants;
import net.i2p.stat.StatManager;
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
     * When we last logged the "Delaying connect" cooldown WARN per destination.
     * Complements {@link #_destFailures}: a dest that keeps failing re-enters
     * cooldown (and thus arms the WARN) on every attempt, so without this the
     * message would fire once per connect() against an unreachable peer. The
     * key is pruned together with {@link #_destFailures} using the same cutoff.
     * @since 0.9.71+
     */
    private final ConcurrentHashMap<Hash, Long> _cooldownWarned = new ConcurrentHashMap<>(4);

    /**
     * Whether to log another cooldown-delay WARN for a destination.
     * Rate-limits to one warning per cooldown window per destination so a peer
     * that keeps failing to connect cannot turn every retry into a log line.
     * A value of 0 (never warned, or pruned) always logs.
     *
     * @param lastWarnTime last timestamp the WARN was emitted for this dest, 0 if never
     * @param now          current time
     * @param cooldownMs   length of the cooldown window that arms the WARN
     * @return true if the WARN should be emitted
     * @since 0.9.71+
     */
    static boolean shouldLogCooldownWarn(long lastWarnTime, long now, long cooldownMs) {
        return lastWarnTime <= 0 || now - lastWarnTime >= cooldownMs;
    }

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
        return _context.getProperty("i2p.streaming.poolMaxIdleMs", 120 * 1000);
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
     * Tunable via i2p.streaming.destinationCooldownMs (default: 5000).
     * @return the dest cooldown ms
     */
    private long getDestCooldownMs() {
        return _context.getProperty("i2p.streaming.destinationCooldownMs", 5*1000);
    }

    /**
     * Remove expired entries from {@link #_destFailures} and {@link #_cooldownWarned}.
     * Called on every connect failure to prevent unbounded growth.
     * Uses the cooldown window as the eviction threshold.
     * @since 0.9.71+
     */
    private void trimDestFailures() {
        long cutoff = _context.clock().now() - getDestCooldownMs();
        for (Iterator<Map.Entry<Hash, Long>> it = _destFailures.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Hash, Long> e = it.next();
            if (e.getValue() < cutoff)
                it.remove();
        }
        for (Iterator<Map.Entry<Hash, Long>> it = _cooldownWarned.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Hash, Long> e = it.next();
            if (e.getValue() < cutoff)
                it.remove();
        }
    }

    private static final long[] RATES = RateConstants.SHORT_TERM_RATES;

    /**
     *  Sample periods for the stream-close retransmission ratios. The five
     *  minute period is mandatory, not cosmetic: Tuner.getAdditionalStat5Min()
     *  reads exactly FIVE_MINUTES from both stats to measure routing
     *  congestion, and a RateStat without that period makes addRateData()
     *  silently drop those samples, leaving the tuner with NaN forever.
     *  @since 0.9.71+
     */
    static final long[] RTX_RATIO_RATES = { RateConstants.ONE_MINUTE, RateConstants.FIVE_MINUTES,
                                            RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR };

    /**
     *  Register the stream-close retransmission ratio stats, including the
     *  five-minute period the router's congestion tuner samples. Separated
     *  from the constructor so tests can register against a fresh StatManager
     *  without building a whole manager.
     *
     *  @param sm stat manager to register with, non-null
     *  @since 0.9.71+
     */
    static void registerRtxRatioStats(StatManager sm) {
        sm.createRequiredRateStat("stream.rtxRatio",
                "Retransmissions per 1000 messages sent when a stream closes",
                "Stream", RTX_RATIO_RATES);
        sm.createRequiredRateStat("stream.rtxRatioBytes",
                "Retransmitted bytes per 1000 bytes sent when a stream closes (bandwidth-overhead view, less sensitive to small-message connections)",
                "Stream", RTX_RATIO_RATES);
    }

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
     *  clients sharing the listener. Slots are only ever taken by
     *  {@link #reserveStreamSlot(ConcurrentHashMap, Hash, int)} (an atomic
     *  check-and-reserve, so two SYNs racing the gate cannot both observe "room
     *  left") and released through {@link #_streamReservations} so a teardown
     *  cannot double-count. Shrunk toward the live table by {@link BanExpiry}
     *  only after the same excess is seen twice. @since 0.9.71+
     */
    private final ConcurrentHashMap<Hash, AtomicInteger> _streamsByDest = new ConcurrentHashMap<>();

    /**
     *  Reservation tokens: receive stream ID &rarr; the dest whose budget the
     *  stream was admitted under. Bound inside
     *  {@link #assignReceiveStreamId(Connection, Hash)} in the same critical
     *  section that publishes the ID, and consumed exactly once by
     *  {@link #releaseReservation(long)} when that ID leaves the manager. The
     *  token, not the connection's remote peer, is the source of truth on
     *  teardown, so a connection torn down before its peer was ever learned
     *  (or one whose dest hash is expensive to recompute) still returns its
     *  slot to the right budget. @since 0.9.71+
     */
    private final ConcurrentHashMap<Long, Hash> _streamReservations = new ConcurrentHashMap<>(32);

    /**
     *  Reconciliation memory: what {@link BanExpiry} last observed for a dest
     *  whose ledger count exceeded the live connection table. The excess is
     *  only rebased downward when it repeats on a consecutive sweep, which
     *  rules out an in-flight reservation (accepted, ID not yet published).
     *  @since 0.9.71+
     */
    private final ConcurrentHashMap<Hash, Integer> _streamShrinkCandidates = new ConcurrentHashMap<>(16);

    /**
     *  Admission barrier: readers hold it across a whole reserve-to-bind span
     *  (tryReserveStream through assignReceiveStreamId) so a reservation can
     *  never straddle disconnectAllHard()'s ledger clear. Without the barrier
     *  a SYN admitted mid-clear would bind a token against an erased count and
     *  its teardown would then decrement a DIFFERENT stream's fresh count.
     *  The write side wraps only the hard-disconnect loop and the clears, so
     *  the rare teardown pays for quiescing the (microsecond) admission spans.
     *  @since 0.9.71+
     */
    private final ReentrantReadWriteLock _admissionLock = new ReentrantReadWriteLock();

    /**
     *  The sweeper owned by this manager. Held so {@link #shutdown()} can stop
     *  it: a fired-and-cancelled TimedEvent would otherwise re-arm itself from
     *  inside timeReached(), outliving the manager it reads. @since 0.9.71+
     */
    private volatile BanExpiry _banExpiry;

    /**
     *  Per-destination burst state for the sub-second SYN gate: value is a two-element
     *  array {burstStartMs, count}, updated at most once per validated SYN via
     *  compare-and-swap (replace). A dest that quiesces naturally falls out of the
     *  next window; no per-dest entries accumulate beyond one array, so this is bounded
     *  and cheap on the hot path. Reset per-dest by the SYN-rate sweeper. @since 0.9.71+
     */
    private final ConcurrentHashMap<Hash, long[]> _recentSyns = new ConcurrentHashMap<>();

    /**
     *  Each dest's most recent SYN-burst strike: the start of the burst window
     *  that crossed the threshold plus the time the strike was recorded.
     *  Forgiveness ages from the strike time — window start can precede the
     *  strike by up to a whole burst window, and aging from it would forgive
     *  a second window's trip early. A trip from a DIFFERENT burst window
     *  within {@link #STRIKE_WINDOW_MS} of the strike autobans; repeat trips
     *  inside the window that already struck are ignored, so a single unlucky
     *  page-load burst never bans no matter how far over threshold it goes.
     *  After the strike window the entry is swept by BanExpiry.
     *  @since 0.9.71+
     */
    private final ConcurrentHashMap<Hash, SynStrike> _synBurstStrikes = new ConcurrentHashMap<>();

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
     *  Cached autoban switch ({@link #PROP_AUTOBAN}), refreshed by the BanExpiry
     *  sweeper like the other hot-path config. When false, banPeer() is a no-op,
     *  the SYN burst gates do not count or strike, and no SYN is dropped for a
     *  would-ban trip. Bans placed while it was enabled still expire naturally.
     *  @since 0.9.71+
     */
    private volatile boolean _autobanEnabled = DEFAULT_AUTOBAN != 0;

    /**
     *  Blacklist property for streaming.
     *  @since 0.9.3
     */
    public static final String PROP_BLACKLIST = "i2p.streaming.blacklist";

    /**
     *  Autoban property: a dest is temporarily banned until this many minutes after
     *  it first trips a flood threshold. Default 5 minutes — short enough that
     *  a legitimate client's retry window (30s connect timeout) overlaps with
     *  the ban expiry, so retries succeed on the next attempt.  The previous
     *  24-hour default was far too aggressive: a burst of page-load SYNs from
     *  a browser (local or remote) could ban a peer for an entire day.
     *  Tunable via i2p.streaming.tempBanMinutes. 0 disables autoban. @since 0.9.71+
     */
    public static final String PROP_TEMP_BAN_MINUTES = "i2p.streaming.tempBanMinutes";
    private static final long DEFAULT_TEMP_BAN_MINUTES = 5;

    /**
     *  Autoban property: whether autoban enforcement is enabled.
     *  0 disables (no new bans are recorded and the SYN flood gates do not
     *  strike or drop); nonzero enables. Default on. The BanExpiry sweeper runs
     *  regardless, so per-dest gate state stays bounded and the cached autoban
     *  config keeps refreshing while enforcement is off. @since 0.9.71+
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
    private static final long DEFAULT_TEMP_BAN_RATE_MS = 1000;

    /**
     *  Autoban property: sub-second burst threshold (SYNs within rate window).
     *  Default 40 SYNs per 1s = 40 req/s instantaneous.
     *  A browser page load can fire 15-20 parallel connections, each with SYN
     *  retransmits; empty-response retry dual-race adds up to 4 SYNs/s per
     *  dest. The threshold must sit well above legitimate bursts so only a
     *  serious abuser trips it. First trip is a strike (no ban); a second
     *  trip within {@link #STRIKE_WINDOW_MS} autobans.
     *  @since 0.9.71+
     */
    public static final String PROP_TEMP_BAN_SYN_BURST = "i2p.streaming.tempBanSynBurst";
    private static final int DEFAULT_TEMP_BAN_SYN_BURST = 40;

    /**
     *  Strike window for the two-strike SYN-burst gate: a second trip within
     *  this many ms of the first autobans. A dest that trips once and then
     *  quiesces for the window is forgiven (single unlucky page-load burst).
     *  @since 0.9.71+
     */
    static final long STRIKE_WINDOW_MS = 60 * 1000;

    /**
     *  Ban a dest for the configured duration. Idempotent; an existing longer ban
     *  is left in place so repeated abuse can't shrink it. No-op when autoban is
     *  disabled ({@link #PROP_AUTOBAN} 0) or the duration is 0.
     *  @param h dest hash to ban
     *  @param why human-readable trigger, e.g. "exceeded max 50 conns/minute"
     *  @param now current clock time
     *  @return true if the dest is banned after this call (newly banned,
     *          extended, or already banned); false if bans are disabled
     *  @since 0.9.71+
     */
    boolean banPeer(Hash h, String why, long now) {
        long ms = _tempBanMs;
        if (ms <= 0 || !_autobanEnabled)
            return false;
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
        return true;
    }

    /**
     *  Pure admission decision for the per-dest stream budget: one more stream
     *  fits while the dest's live count is strictly below the ceiling.
     *  A non-positive ceiling disables the gate entirely (no cap enforced).
     *  @param held streams currently reserved by the dest
     *  @param max the per-dest concurrent stream ceiling
     *  @return true if a further stream would stay under the ceiling
     *  @since 0.9.71+
     */
    static boolean canReserveStream(int held, int max) {
        return max <= 0 || held < max;
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
     *  Atomically take one stream slot from a dest's budget: the ceiling check
     *  and the increment happen inside a single per-key {@code compute()}, so
     *  two SYNs racing the gate can no longer both observe "room left" and
     *  admit past the ceiling (the check-then-add this replaced).
     *
     *  <p>Refusal leaves the counter untouched, so a rejected SYN never costs
     *  the dest anything. An unknown dest (null hash) is admitted without
     *  accounting, matching the old gate's "no hash, no budget" behavior.
     *
     *  @param ledger the per-dest stream ledger, non-null
     *  @param h remote dest hash, may be null
     *  @param max the per-dest concurrent stream ceiling; &le; 0 disables the gate
     *  @return true if the slot was taken (the caller now owns a slot and must
     *          either bind it to a reservation token or release it)
     *  @since 0.9.71+
     */
    static boolean reserveStreamSlot(ConcurrentHashMap<Hash, AtomicInteger> ledger,
                                     Hash h, int max) {
        if (h == null)
            return true;
        final boolean[] admitted = new boolean[1];
        ledger.compute(h, (key, cur) -> {
            int held = cur == null ? 0 : cur.get();
            if (!canReserveStream(held, max))
                return cur;
            admitted[0] = true;
            if (cur == null)
                return new AtomicInteger(1);
            cur.incrementAndGet();
            return cur;
        });
        return admitted[0];
    }

    /**
     *  Return one stream slot to a dest's budget. The count is clamped at zero
     *  and the entry is dropped once drained, so a mismatched teardown can
     *  never drive a budget negative (which would hand out free slots) and an
     *  idle dest leaves no entry behind.
     *
     *  @param ledger the per-dest stream ledger, non-null
     *  @param h remote dest hash, null-safe
     *  @since 0.9.71+
     */
    static void releaseStreamSlot(ConcurrentHashMap<Hash, AtomicInteger> ledger, Hash h) {
        if (h == null)
            return;
        ledger.computeIfPresent(h, (key, cur) -> {
            cur.updateAndGet(v -> v > 1 ? v - 1 : 0);
            return cur.get() > 0 ? cur : null;
        });
    }

    /**
     *  Read the stream count a dest is actually holding in the ledger, or 0 if
     *  it holds none.
     *  @param ledger the per-dest stream ledger, non-null
     *  @param h remote dest hash, non-null
     *  @return the reserved stream count for the dest, never negative
     *  @since 0.9.71+
     */
    static int streamSlotCount(ConcurrentHashMap<Hash, AtomicInteger> ledger, Hash h) {
        if (h == null)
            return 0;
        AtomicInteger c = ledger.get(h);
        return c == null ? 0 : Math.max(0, c.get());
    }

    /**
     *  Consume the reservation token bound to a receive stream ID, releasing
     *  the dest budget slot it owns. A token is removed first and released
     *  after, so concurrent teardowns of the same ID cannot both return the
     *  slot; a missing token is a no-op rather than a guess about which dest
     *  to debit.
     *
     *  @param tokens receive stream ID &rarr; dest hash, non-null
     *  @param ledger the per-dest stream ledger, non-null
     *  @param receiveStreamId the connection's receive stream ID
     *  @return the dest hash whose slot was released, or null if no token was bound
     *  @since 0.9.71+
     */
    static Hash consumeReservation(ConcurrentHashMap<Long, Hash> tokens,
                                   ConcurrentHashMap<Hash, AtomicInteger> ledger,
                                   long receiveStreamId) {
        Hash h = tokens.remove(Long.valueOf(receiveStreamId));
        if (h != null)
            releaseStreamSlot(ledger, h);
        return h;
    }

    /**
     *  Decrement a connect-attempt waiter count without ever going negative.
     *
     *  <p>Waiters are released from a {@code finally} around the wait loop, so
     *  an abandoned or failed attempt always drains its own increment exactly
     *  once and a double release can only stop at zero.
     *
     *  @param waiting the waiter counter, non-null
     *  @return the value after the release, or the unchanged value if it was
     *          already zero
     *  @since 0.9.71+
     */
    static int releaseWaiting(AtomicInteger waiting) {
        for (;;) {
            int n = waiting.get();
            if (n <= 0)
                return n;
            if (waiting.compareAndSet(n, n - 1))
                return n - 1;
        }
    }

    /**
     *  Pure reconciliation step for one dest: how much of a ledger count that
     *  exceeds the live connection table should survive this sweep.
     *
     *  <p>The excess is only dropped when the SAME excess was recorded by the
     *  previous sweep (the candidate), which means it persisted across a whole
     *  sweep interval and therefore cannot be an in-flight reservation
     *  admitted moments before the first sweep. Counts at or below the table
     *  are never raised: the ledger only ever shrinks toward observed reality,
     *  so a sweep can free leaked slots but can never hand out new ones.
     *
     *  <p>Even a repeated candidate cannot shrink below the bound-token floor.
     *  A release followed by a re-reserve can return the count to the same
     *  value while the observed snapshot still shows the old, smaller table
     *  (ABA): shrinking to observed there would erase brand-new reservations.
     *  Tokens are bound one-per-admission, so {@code max(observed, boundTokens)}
     *  is never below the live floor and a genuine leak (no token behind the
     *  excess) still shrinks to observed.
     *
     *  @param held the count currently in the ledger
     *  @param observed the count of live connections for the dest
     *  @param candidate the excess last sweep recorded for this dest, or null
     *  @param boundTokens reservation tokens bound for this dest right now
     *  @return the count to keep in the ledger this sweep
     *  @since 0.9.71+
     */
    static int reconcileStreamCount(int held, int observed, Integer candidate, int boundTokens) {
        if (observed >= held)
            return held;
        if (candidate != null && candidate.intValue() == observed) {
            int floor = Math.max(observed, boundTokens);
            return floor < held ? floor : held;
        }
        return held;
    }

    /**
     *  Reconcile the per-dest stream ledger against the live connection table.
     *  See {@link #reconcileStreamCount(int, int, Integer, int)} for the policy;
     *  this walks the ledger, records or clears per-dest candidates, and prunes
     *  candidates for dests that have left the ledger.
     *
     *  <p>A rebase is applied with a compare-and-set on the count we read, so a
     *  reserve or release landing mid-sweep makes us skip that dest rather than
     *  overwrite the newer value.
     *
     *  @param ledger the per-dest stream ledger, non-null
     *  @param observed live connection count per dest, non-null
     *  @param candidates sweep-over-sweep observation memory, non-null
     *  @param boundTokens reservation tokens bound per dest, non-null
     *  @since 0.9.71+
     */
    static void reconcileStreamSlots(ConcurrentHashMap<Hash, AtomicInteger> ledger,
                                     Map<Hash, Integer> observed,
                                     ConcurrentHashMap<Hash, Integer> candidates,
                                     Map<Hash, Integer> boundTokens) {
        for (Map.Entry<Hash, AtomicInteger> e : ledger.entrySet()) {
            Hash h = e.getKey();
            Integer obs = observed.get(h);
            int obsCount = obs == null ? 0 : obs.intValue();
            Integer tok = boundTokens.get(h);
            int tokens = tok == null ? 0 : tok.intValue();
            AtomicInteger c = e.getValue();
            int held = c.get();
            int kept = reconcileStreamCount(held, obsCount, candidates.get(h), tokens);
            if (kept < held) {
                for (;;) {
                    int cur = c.get();
                    if (cur != held || cur <= kept)
                        break;
                    if (c.compareAndSet(cur, kept))
                        break;
                }
                // The observed excess was accepted, so this dest owes no
                // further candidate; a fresh one starts on the next sighting.
                candidates.remove(h);
            } else if (obsCount < held) {
                candidates.put(h, Integer.valueOf(obsCount));
            } else {
                candidates.remove(h);
            }
            // Idle dests must not leave zero entries behind (they are not
            // released through releaseStreamSlot()'s drain path above).
            ledger.computeIfPresent(h, (key, cur) -> cur.get() > 0 ? cur : null);
        }
        candidates.keySet().retainAll(ledger.keySet());
    }

    /**
     *  Take a stream slot from this dest's budget. See
     *  {@link #reserveStreamSlot(ConcurrentHashMap, Hash, int)}.
     *  @param h remote dest hash, non-null on every real path
     *  @param max the per-dest concurrent stream ceiling
     *  @return true if the slot was taken
     *  @since 0.9.71+
     */
    private boolean tryReserveStream(Hash h, int max) {
        return reserveStreamSlot(_streamsByDest, h, max);
    }

    /**
     *  Return a stream slot taken outside the token protocol (a SYN rejected
     *  after admission, or a connection built before a token was bound).
     *  @param h remote dest hash, null-safe
     *  @since 0.9.71+
     */
    private void releaseStream(Hash h) {
        releaseStreamSlot(_streamsByDest, h);
    }

    /**
     *  Consume the reservation bound to a receive stream ID, releasing its slot.
     *  @param receiveStreamId the connection's receive stream ID
     *  @return the dest hash released, or null if no token was bound
     *  @since 0.9.71+
     */
    private Hash releaseReservation(long receiveStreamId) {
        return consumeReservation(_streamReservations, _streamsByDest, receiveStreamId);
    }

    /**
     *  Release whatever this connection still holds against a dest budget:
     *  first its reservation token, and only if no token was bound, its remote
     *  peer (a connection built outside the reservation protocol, so the slot
     *  must still be drained or the budget leaks forever).
     *
     *  <p>The release is claimed on the connection first: any path may reach
     *  here for a connection whose slot was already returned (a hard-disconnect
     *  sweep racing an async teardown, or a teardown after the sweeper
     *  released it), and the peer fallback would then decrement a DIFFERENT
     *  stream's count. The claim is re-armed by assignReceiveStreamId() when a
     *  pooled connection starts a new generation.
     *
     *  @param con the connection leaving the manager, non-null
     *  @return the dest hash released, or null if nothing was held
     *  @since 0.9.71+
     */
    private Hash releaseConnectionReservation(Connection con) {
        if (!con.claimSlotRelease())
            return null;
        Hash released = releaseReservation(con.getReceiveStreamId());
        if (released != null)
            return released;
        Destination peer = con.getRemotePeer();
        if (peer != null)
            releaseStream(peer.calculateHash());
        return null;
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
     *
     *  <p>The autoban policy is a parameter, snapshotted once per SYN by the
     *  caller, so one SYN can never be counted under one policy and then
     *  recorded (or not) under a different one that changed mid-evaluation.
     *
     *  @param h dest hash to record against
     *  @param now current clock time
     *  @param autoban the autoban policy snapshotted when this SYN was evaluated
     *  @return the window start of the burst window that tripped (used to key
     *          the strike to a distinct burst), or -1 if this SYN did not trip
     *          the gate (below threshold, window just re-armed, gate disabled,
     *          or autoban enforcement off)
     *  @since 0.9.71+
     */
    private long checkSynBurst(Hash h, long now, boolean autoban) {
        if (!autoban)
            return -1;
        long windowMs = _synRateMs;
        int burst = _synBurst;
        if (windowMs <= 0 || burst <= 0)
            return -1;
        long[] cur = _recentSyns.get(h);
        if (cur == null) {
            // no active window: start a fresh one. One allocation per new dest
            // is fine; the flood path for an already-banned dest is
            // short-circuited before this is ever reached.
            long[] init = {now, 1};
            long[] prev = _recentSyns.putIfAbsent(h, init);
            if (prev == null)
                return -1;
            cur = prev;
        }
        // Hot path: bump in place under the per-dest window lock so concurrent
        // SYNs can only be lost to a (safe) under-count, never a lost update
        // that over-counts a dest toward a ban. The age check is done inside
        // the lock too: once the window ages out, putIfAbsent can never replace
        // the stale entry, so without re-arming in place the burst gate would go
        // dead until the periodic sweep removes the entry.
        synchronized (cur) {
            if (now - cur[0] >= windowMs) {
                cur[0] = now;
                cur[1] = 1;
                return -1;
            }
            cur[1] = cur[1] + 1;
            if (synBurstTripped(cur[0], (int) cur[1], now, windowMs, burst))
                return cur[0];
            return -1;
        }
    }

    /**
     *  Outcome of evaluating one SYN burst-gate trip against the dest's recorded
     *  strike.
     *  @since 0.9.71+
     */
    enum SynBurstAction {
        /** Repeat trip inside the window that already struck, or the gate is disabled. */
        IGNORE,
        /** First trip, or a prior strike that aged out: record a strike, do not ban. */
        RECORD,
        /** Second distinct burst window inside the strike window: autoban. */
        BAN
    }

    /**
     *  A recorded SYN-burst strike. Forgiveness ages from the strike TIME, not
     *  from the burst window that caused it: a burst window is only milliseconds
     *  long, so keying forgiveness to the window start (the pre-0.9.71+ value)
     *  forgave a strike almost immediately and the two-strike autoban never fired.
     *
     *  @since 0.9.71+
     */
    static final class SynStrike {
        /** Window start of the burst that recorded this strike (identity for same-window IGNORE). */
        final long windowStart;
        /** Clock time at which this strike was recorded (forgiveness ages from here). */
        final long strikeTime;

        /**
         * @param windowStart window start of the burst that recorded the strike
         * @param strikeTime clock time at which the strike was recorded
         */
        SynStrike(long windowStart, long strikeTime) {
            this.windowStart = windowStart;
            this.strikeTime = strikeTime;
        }
    }

    /**
     *  Pure decision for the two-strike SYN-burst gate. Two strikes means two
     *  DISTINCT burst windows: repeat trips inside the window that recorded the
     *  first strike are IGNOREd, so a single unlucky page-load burst can never
     *  cost a 5-minute outage however far over threshold it goes. A trip from a
     *  different window within {@link #STRIKE_WINDOW_MS} of the recorded strike
     *  is BAN (demonstrable repeat abuse); after the window the strike is
     *  forgiven and a new one RECORDs instead.
     *
     *  <p>Forgiveness ages from the strike time, so the clock starts when the
     *  strike was recorded, not when the (millisecond-scale) burst window began.
     *
     *  @param last recorded strike for this dest, or null if this dest has none
     *  @param windowStart window start of the burst window that just tripped
     *  @param now current clock time
     *  @param strikeWindowMs strike forgiveness window (pass {@link #STRIKE_WINDOW_MS})
     *  @return the action to take for this trip
     *  @since 0.9.71+
     */
    static SynBurstAction synBurstStrikeAction(SynStrike last, long windowStart, long now, long strikeWindowMs) {
        if (strikeWindowMs <= 0)
            return SynBurstAction.IGNORE;
        if (last == null)
            return SynBurstAction.RECORD;
        if (last.windowStart == windowStart)
            // same burst window: the first strike already covers this episode
            return SynBurstAction.IGNORE;
        long age = now - last.strikeTime;
        if (age < 0 || age >= strikeWindowMs)
            // forgiven (or clock skew): start a fresh strike
            return SynBurstAction.RECORD;
        return SynBurstAction.BAN;
    }

    /**
     *  Atomically evaluate and apply a burst-gate trip against {@code h}'s
     *  recorded strike. The read-modify-write runs under
     *  {@link ConcurrentHashMap#compute} so concurrent trips from one dest can
     *  neither both miss a just-recorded first strike (double-counting a single
     *  burst as two strikes) nor lose it to a racing put (letting a sustained
     *  flood escape the ban).
     *
     *  @param strikes per-dest strike map
     *  @param h remote dest hash, non-null
     *  @param windowStart window start of the burst window that just tripped
     *  @param now current clock time
     *  @param strikeWindowMs strike forgiveness window (pass {@link #STRIKE_WINDOW_MS})
     *  @return the action taken
     *  @since 0.9.71+
     */
    static SynBurstAction applySynBurstStrike(ConcurrentHashMap<Hash, SynStrike> strikes, Hash h,
                                              long windowStart, long now, long strikeWindowMs) {
        final SynBurstAction[] taken = { SynBurstAction.IGNORE };
        strikes.compute(h, (k, prev) -> {
            SynBurstAction action = synBurstStrikeAction(prev, windowStart, now, strikeWindowMs);
            taken[0] = action;
            return action == SynBurstAction.RECORD ? new SynStrike(windowStart, now) : prev;
        });
        return taken[0];
    }

    /**
     *  Record a SYN-burst strike for {@code h} and decide whether this trip
     *  autobans (see {@link #synBurstStrikeAction}). First trip in a window (or
     *  a trip after the strike window): store the strike, return false.
     *  Repeat trips in an already-struck window: no-op, return false. Second
     *  distinct window inside the strike window: leave the original strike in
     *  place (the ban supersedes further strikes), return true.
     *
     *  <p>Autoban is re-checked live here (the callers already gate
     *  checkSynBurst() on a per-SYN snapshot): if enforcement is switched off
     *  between the trip and this record, no strike may be stored, or the map
     *  would keep evidence recorded under a policy the operator has since
     *  disabled. Callers' snapshot decides BAN for the trip in flight; this
     *  check only gates the record.
     *
     *  @param h remote dest hash, non-null
     *  @param windowStart window start of the burst window that just tripped
     *  @param now current clock time
     *  @return true if this trip should autoban
     *  @since 0.9.71+
     */
    private boolean noteSynBurstStrike(Hash h, long windowStart, long now) {
        if (!_autobanEnabled)
            return false;
        SynBurstAction action = applySynBurstStrike(_synBurstStrikes, h, windowStart, now, STRIKE_WINDOW_MS);
        if (action == SynBurstAction.BAN)
            return true;
        if (action == SynBurstAction.RECORD && _log.shouldWarn()) {
            _log.warn("SYN burst strike for " + h.toBase32().substring(0, 6) +
                      " (>" + _synBurst + " SYNs/" + _synRateMs + "ms); " +
                      "a second burst window within " + (STRIKE_WINDOW_MS / 1000) + "s autobans");
        }
        return false;
    }

    /**
     *  Package-visible flood gate for the retransmit-SYN path in
     *  {@link ConnectionHandler#receiveNewSyn(Packet)}. A retransmitted SYN carries the
     *  stream IDs of a connection that already exists in the manager, so it never
     *  reaches {@link #receiveConnection(Packet)} (and thus never passes the
     *  {@link #checkSynBurst(Hash, long, boolean)} gate at the top of that method). An
     *  attacker exploits that by planting a handful of half-open connections and
     *  then blasting retransmitted SYNs against them; without this gate every hit
     *  makes {@code ConnectionHandler.resendSynAck} mint and enqueue a fresh
     *  SYN-ACK, consuming CPU and egress, and the connection never establishes.
     *
     *  <p>This routes the retransmit through the <em>same</em> per-destination
     *  sub-second burst window as fresh SYNs, so a dest that crosses the burst
     *  threshold in two DISTINCT windows within the strike window — the
     *  demonstrable pattern of repeat abuse — is autobanned and dropped. A single
     *  burst only strikes, and with autoban disabled ({@link #PROP_AUTOBAN} 0)
     *  nothing is recorded or dropped here. Once banned, subsequent calls return
     *  {@code true} (drop) immediately.
     *
     *  @param h remote dest hash, non-null
     *  @param now current clock time
     *  @return true if this SYN should be dropped (dest already temp-banned, or
     *          this SYN is a second distinct burst strike and just banned it)
     *  @since 0.9.71+
     */
    boolean checkInboundSynFlood(Hash h, long now) {
        if (h == null)
            return false;
        if (isTempBanned(h, now))
            return true;
        // One snapshot for gate + record of this SYN (see receiveConnection()).
        final boolean autoban = _autobanEnabled;
        long windowStart = checkSynBurst(h, now, autoban);
        if (windowStart >= 0 && noteSynBurstStrike(h, windowStart, now))
            return banPeer(h, "exceeded max " + _synBurst + " SYNs/" + _synRateMs + "ms on inbound retransmit",
                           now);
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
     *  limit at the per-destination stream budget gate
     *  ({@link #reserveStreamSlot(ConcurrentHashMap, Hash, int)}).
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
        // Listen on the streaming protocol only. The pre-mux PROTO_ANY
        // wildcard is no longer accepted, so packets on any other protocol are
        // never dispatched to the streaming mux listener.
        int protocol = I2PSession.PROTO_STREAMING;
        _session.addMuxedSessionListener(_messageHandler, protocol, defaultOptions.getLocalPort());
        _outboundQueue = new PacketQueue(_context, _timer.getSharedTimer());
        _recentlyClosed = new LHMCache<>(512);
        // Socket timeout for accept()
        _soTimeout = -1;

        // Stats for this class
        _context.statManager().createRequiredRateStat("stream.con.lifetimeMessagesSent", "Number of messages we send on a stream", "Stream", RATES);
        _context.statManager().createRateStat("stream.con.lifetimeMessagesReceived", "Number of messages we receive on a stream", "Stream", RATES);
        _context.statManager().createRateStat("stream.con.lifetimeBytesSent", "How many bytes we send on a stream", "Stream", RATES);
        _context.statManager().createRateStat("stream.con.lifetimeBytesReceived", "How many bytes we receive on a stream", "Stream", RATES);
        _context.statManager().createRateStat("stream.con.lifetimeDupMessagesSent", "Number of duplicate messages we send on a stream", "Stream", RATES);
        _context.statManager().createRateStat("stream.con.lifetimeDupMessagesReceived", "Number of duplicate messages we receive on a stream", "Stream", RATES);
        registerRtxRatioStats(_context.statManager());
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
        // Always schedule the sweeper: it bounds the per-dest burst/strike/ban
        // maps and refreshes the cached autoban config, even when autoban
        // enforcement (i2p.streaming.autoban=0) is off.
        _autobanEnabled = _context.getProperty(PROP_AUTOBAN, DEFAULT_AUTOBAN) != 0;
        _banExpiry = new BanExpiry();
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
        // SHA-256 of the peer dest; used by every gate below (flood, refusal,
        // response, accounting). Computed once — calculateHash() on a fresh
        // SYN payload is real work.
        Hash fromHash = from.calculateHash();
        ByteArray ba = _cache.acquire();
        boolean sigOk = synPacket.verifySignature(_context, ba.getData());
        _cache.release(ba);
        if (sigOk) {
            long[] nacks = synPacket.getNacks();
            if (nacks != null && nacks.length == 8) {
                // we use the packet's session because it may be a subsession
                Hash localHash = synPacket.getSession().getMyDestination().calculateHash();
                if (!synNacksMatch(nacks, localHash)) {
                    if (_log.shouldWarn()) {
                        // glue it back together for logging only
                        byte[] g = new byte[32];
                        for (int j = 0; j < 8; j++) {
                            DataHelper.toLong(g, j << 2, 4, nacks[j]);
                        }
                        Hash ghash = new Hash(g);
                        _log.warn("Signature passed but hash failed, sending reset, expected: " + localHash.toBase32() +
                                  " received: " + ghash.toBase32() +
                                  " from: " + fromHash.toBase32());
                    }
                    _packetHandler.sendResetUnverified(synPacket);
                    return null;
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
        // within tempBanSynRate-ms records a strike; a SECOND distinct burst
        // window within the strike window autobans and drops this SYN. The gate
        // runs before the stream budget and refusal counters are consulted, so
        // only the second distinct window is stopped there — the FIRST window
        // deliberately passes even far over threshold (grace: one unlucky
        // page-load burst is never punished with a 5-minute outage), with the
        // per-dest stream budget still capping what its connections may hold.
        // The autoban policy is read once here and snapshotted for the whole
        // evaluation of this SYN, so one SYN can never be gated under one
        // policy and then recorded under another that changed mid-flight.
        if (from != null) {
            long now = _context.clock().now();
            final boolean autoban = _autobanEnabled;
            if (!isTempBanned(fromHash, now)) {
                long windowStart = checkSynBurst(fromHash, now, autoban);
                if (windowStart >= 0 && noteSynBurstStrike(fromHash, windowStart, now) &&
                    banPeer(fromHash, "exceeded max " + _synBurst + " SYNs/" + _synRateMs + "ms", now)) {
                    // second demonstrated burst: dump the SYN like the temp-banned drop below
                    return null;
                }
            }
        }

        // Read side of the admission barrier: reserve through bind is ONE span
        // so disconnectAllHard()'s write side cannot clear the ledger between
        // them (a token bound over an erased count would later decrement a
        // different stream's slot). The span is wait-free by construction —
        // nothing between reserve and bind blocks, no waitForConnect, no
        // finalizeConnection — so holding the read lock here can never stall
        // the write side behind a network wait; reject paths that return from
        // inside the span release it via the finally.
        Connection con;
        _admissionLock.readLock().lock();
        try {
            // The budget gate is the reservation: the ceiling check and the slot
            // take are one atomic step, so a burst of concurrent SYNs can no longer
            // all read "room left" and admit past the per-dest ceiling. The slot is
            // bound to a reservation token once the connection has a receive ID; a
            // SYN rejected below never gets that far and gives its slot back here.
            boolean reserved = tryReserveStream(fromHash, getEffectiveMaxStreams());
            if (!reserved) {
                // If already temp-banned, drop the SYN silently before any processing,
                // SYN-ACK, or refusal logging, so a banned dest can't keep hammering.
                if (isTempBanned(fromHash, _context.clock().now())) {
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
                if (refusalThresholdMet(_refusalCounter.increment(fromHash), _tempBanRefusals))
                    banPeer(fromHash, "exceeded max " + _tempBanRefusals + " refusals/min", _context.clock().now());
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
                if (reserved)
                    // Admitted, then refused for an unrelated reason (throttler,
                    // filter, ...): the slot was never bound to a token, so it is
                    // released directly. Without this the refused SYN would hold a
                    // slot until the sweeper noticed the drift.
                    releaseStream(fromHash);
                sendRejectResponse(synPacket, from, fromHash, retryAfter);
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
                // specs not clear if MTU may be omitted in SYN
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

            con = null;
            try {
                con = new Connection(_context, this, synPacket.getSession(), _schedulerChooser,
                                     _timer.getSharedTimer(), _outboundQueue, _conPacketHandler, opts, true);
                _tcbShare.updateOptsFromShare(con);
                // Binds the reservation token: from here the token, not this frame,
                // owns the slot, so every later teardown path releases exactly once.
                assignReceiveStreamId(con, fromHash);
            } catch (RuntimeException | Error e) {
                // Still holding the reservation taken at the gate. Claim the
                // release so a later async teardown of this half-built connection
                // cannot drain a fresh stream's count, then release by token if
                // the bind made it far enough, otherwise straight by dest.
                if (con == null || con.claimSlotRelease()) {
                    Hash bound = con != null ? releaseReservation(con.getReceiveStreamId()) : null;
                    if (bound == null)
                        releaseStream(fromHash);
                }
                throw e;
            }
        } finally {
            _admissionLock.readLock().unlock();
        }

        // finally, we know enough that we can log the packet with the conn filled in
        if (I2PSocketManagerFull.pcapWriter != null &&
            _context.getBooleanProperty(I2PSocketManagerFull.PROP_PCAP))
            synPacket.logTCPDump(con);
        try {
            // This validates the packet, and sets the con's SendStreamID and RemotePeer
            con.getPacketHandler().receivePacket(synPacket, con);
        } catch (I2PException ie) {
            _connectionByInboundId.remove(Long.valueOf(con.getReceiveStreamId()));
            // Token lookup, not the peer: the peer may never have been learned
            // on this failure path, and it can be expensive to recompute.
            // Claimed so a timer armed by the half-run receivePacket cannot
            // release this slot a second time on its async teardown.
            releaseConnectionReservation(con);
            return null;
        }

        _context.statManager().addRateData("stream.connectionReceived", 1);
        return con;
    }

    /**
     * Send the configured response for a rejected SYN (RESET, HTTP-style
     * Retry-After payload, or custom payload), subject to the peer's throttler
     * status. Only called when the SYN was already judged rejectable;
     * extracted from {@link #receiveConnection(Packet)} to bound its
     * complexity.
     *
     * @param synPacket the incoming SYN being rejected
     * @param from the peer destination
     * @param fromHash {@code from.calculateHash()} (already computed by the caller)
     * @param retryAfter seconds for the Retry-After header; {@link #MAX_TIME}
     *                   means never retry (silent drop)
     */
    private void sendRejectResponse(Packet synPacket, Destination from, Hash fromHash, int retryAfter) {
        String resp = _defaultOptions.getLimitAction();
        if ("drop".equals(resp)) {
            // always drop
            return;
        }
        if (retryAfter >= MAX_TIME) {
            // always drop these regardless of setting
            return;
        }
        if ((_minuteThrottler != null && _minuteThrottler.isOverBy(fromHash, getDropOverLimit())) ||
            (_hourThrottler != null && _hourThrottler.isOverBy(fromHash, getDropOverLimit())) ||
            (_dayThrottler != null && _dayThrottler.isOverBy(fromHash, getDropOverLimit()))) {
            // A signed RST/close packet + ElGamal + session tags is fairly expensive, so
            // once a limit is significantly exceeded for a particular peer, don't even send it.
            // This is a tradeoff, because it will keep retransmitting the SYN for a while,
            // thus more inbound, but let's not spend several KB on the outbound.
            if (_log.shouldInfo())
                _log.info("Dropping limit response to " + from.toBase32());
            return;
        }
        String sendResponse = limitResponse(resp, retryAfter, synPacket.getLocalPort() == 443);
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
        reply.setReceiveStreamId(assignRejectId());
        reply.setOptionalFrom();
        reply.setLocalPort(synPacket.getLocalPort());
        reply.setRemotePort(synPacket.getRemotePort());
        if (_log.shouldInfo())
            _log.info("Over limit, sending " + reply + " to " + from.toBase32());
        // this just sends the packet - no retries or whatnot
        _outboundQueue.enqueue(reply);
    }

    /**
     * The payload to send in a limit-response SYN/CLOSE, or null for a RESET.
     *
     * <p>Pure decision helper (extracted from {@code receiveConnection}):
     * the "reset" limit action, an empty action, and port 443 (the synth
     * HTTPS over I2P port, which must not be tricked into a text payload)
     * all produce a plain RESET; "http" produces an HTTP Retry-After body;
     * anything else is echoed verbatim after normalizing {@code \r}/\code{n}
     * escape sequences. HTTPS (443) always stacks the port check too, per RFC-compliant
     * servers that disregard limit responses on that port.
     *
     * @param limitAction the configured i2p.streaming.limitAction value, may be null
     * @param retryAfter seconds for the Retry-After header, or 0 to omit it
     * @param sslPort true if the SYN arrived on local port 443
     * @return the response payload, or null for a RESET
     * @since 0.9.71
     */
    static String limitResponse(String limitAction, int retryAfter, boolean sslPort) {
        if (limitAction == null || limitAction.equals("reset") || limitAction.length() <= 0 || sslPort)
            return null;
        if ("http".equals(limitAction)) {
            // The template's placeholder is "600" (the historical suggestion
            // for a 429); the caller's computed retryAfter replaces it, or the
            // whole header is dropped when 0 (the caller said "retry later"
            // without a number).
            if (retryAfter > 0)
                return LIMIT_HTTP_RESPONSE.replace("600", Integer.toString(retryAfter));
            return LIMIT_HTTP_RESPONSE.replace("Retry-After: 600\r\n", "");
        }
        return limitAction.replace("\\r", "\r").replace("\\n", "\n");
    }

    /**
     * Whether a SYN's 8 NACK words match the local destination hash.
     * A NACK that does not match the local hash means the sender is wrong
     * about who we are; extracted so the word-split comparison is unit-testable.
     *
     * @param nacks the packet's NACK values, or null
     * @param localHash the local destination hash to compare against
     * @return true if the NACKs are absent or all 8 words match
     * @since 0.9.71
     */
    static boolean synNacksMatch(long[] nacks, Hash localHash) {
        if (nacks == null || nacks.length != 8)
            return true;
        byte[] h = localHash.getData();
        for (int i = 0; i < 8; i++) {
            if (nacks[i] != DataHelper.fromLong(h, i << 2, 4))
                return false;
        }
        return true;
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
            // A ping that arrived on an existing connection may only come from
            // that connection's remote peer; treating it as an open relay would
            // let one peer probe a third party through our tunnels.
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
        // Echo the payload back, truncated to MAX_PONG_PAYLOAD
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
     *  <p>Binds this stream's budget reservation here, inside the same critical
     *  section that publishes the ID, so the token becomes the source of truth
     *  for the slot from the moment the ID is visible to teardown paths. An
     *  earlier id the con may have held is deliberately NOT re-released: a
     *  pooled connection hands back its previous slot when it leaves the
     *  manager, and releasing here as well would double-count.
     *
     *  @param con the connection to assign an ID to
     *  @param destHash the remote dest whose budget admitted this stream; the
     *         reservation token for the new ID points here
     *  @since 0.9.12 consolidated from receiveConnection() and connect();
     *         destHash added 0.9.71+ for reservation binding
     */
    private void assignReceiveStreamId(Connection con, Hash destHash) {
        // A pooled connection's previous generation already claimed its slot
        // release; re-arm the claim before this generation binds a new token.
        // Done before publishing the id so no teardown can observe a live
        // token behind a stale claim (which would strand the slot forever).
        con.resetSlotRelease();
        long receiveId;
        synchronized(_recentlyClosed) {
            Long rcvID;
            do {
                receiveId = _context.random().nextLong(Packet.MAX_STREAM_ID-1)+1;
                rcvID = Long.valueOf(receiveId);
            } while (_recentlyClosed.containsKey(rcvID) ||
                     _pendingPings.containsKey(rcvID) ||
                     _connectionByInboundId.putIfAbsent(rcvID, con) != null);
            con.setReceiveStreamId(receiveId);
            if (destHash != null)
                _streamReservations.put(rcvID, destHash);
        }
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
          // Captured once: every admission (pool reuse and the wait loop) gates
          // against the same ceiling, and calculateHash() on the peer is real work.
          final Hash destHash = peer.calculateHash();
          final int max = getEffectiveMaxStreams();

          // Try to acquire a pooled connection first
          if (isPoolEnabled()) {
              Connection pooled = acquireFromPool(peer);
              if (pooled != null) {
                  boolean admitted = false;
                  // Read side of the admission barrier: reserve through bind
                  // is one span so disconnectAllHard() cannot clear the ledger
                  // between them (a token bound over an erased count would make
                  // this teardown decrement a later stream's slot).
                  _admissionLock.readLock().lock();
                  try {
                      if (tryReserveStream(destHash, max)) {
                          // Update connection options and session if needed
                          try {
                              pooled.setRemotePeer(peer);
                              // Assign new stream IDs since this is a new logical connection;
                              // this also binds the reservation token, which now owns the slot.
                              assignReceiveStreamId(pooled, destHash);
                          } catch (RuntimeException | Error e) {
                              // The slot was taken but the bind did not complete:
                              // claim the release so no later teardown of this
                              // connection can drain a different stream's count.
                              if (pooled.claimSlotRelease()) {
                                  Hash bound = releaseReservation(pooled.getReceiveStreamId());
                                  if (bound == null)
                                      releaseStream(destHash);
                              }
                              returnToPool(pooled);
                              throw e;
                          }
                          admitted = true;
                      } else {
                          // At this dest's stream ceiling: return the pooled connection
                          // rather than abandoning it outside the pool.
                          returnToPool(pooled);
                      }
                  } finally {
                      _admissionLock.readLock().unlock();
                  }
                  if (admitted) {
                      if (_log.shouldDebug()) {
                          _log.debug("Reusing pooled connection to " + destHash.toBase64().substring(0,6));
                      }
                      // Skip to post-creation setup (fromPool=true — skip cooldown)
                      return finalizeConnection(pooled, peer, opts, connectStart, true);
                  }
              }
          }
          long expiration = _context.clock().now();
          long tmout = opts.getConnectTimeout();
          if (tmout <= 0) {expiration += getDefaultStreamDelayMax();}
          else {expiration += tmout;}
          _numWaiting.incrementAndGet();
          try {
              while (true) {
                  long remaining = expiration - _context.clock().now();
                  if (remaining <= 0) {
                      _log.logAlways(Log.WARN, "Refusing connection -> Maximum " + getLogMaxStreams() + " concurrent streams exceeded");
                      return null;
                  }

                  // Admission is the reservation itself: the ceiling check and the
                  // slot take are one atomic step, so two concurrent connect()s to
                  // the same dest can never both observe room left and both pass.
                  // Reserve through bind runs under the admission barrier's read
                  // lock (see _admissionLock); the wait below must stay outside it.
                  boolean admitted = false;
                  _admissionLock.readLock().lock();
                  try {
                      if (tryReserveStream(destHash, max)) {
                          try {
                              con = new Connection(_context, this, session, _schedulerChooser, _timer.getSharedTimer(),
                                                   _outboundQueue, _conPacketHandler, opts, false);
                              con.setRemotePeer(peer);
                              assignReceiveStreamId(con, destHash);
                          } catch (RuntimeException | Error e) {
                              // Token-first: releases the slot exactly once whether
                              // or not the reservation made it onto a token; falls
                              // back to the dest when the con never came up. Claimed
                              // so no later teardown of this half-built connection
                              // can drain another stream's count.
                              if (con == null || con.claimSlotRelease()) {
                                  Hash bound = con != null ? releaseReservation(con.getReceiveStreamId()) : null;
                                  if (bound == null)
                                      releaseStream(destHash);
                              }
                              throw e;
                          }
                          admitted = true;
                      }
                  } finally {
                      _admissionLock.readLock().unlock();
                  }
                  if (admitted)
                      break; // stop looping as a psuedo-wait
                  // allow a full buffer of pending/waiting streams
                  if (_numWaiting.get() > max) {
                      _log.logAlways(Log.WARN, "Refusing connection -> Maximum " + getLogMaxStreams() + " concurrent streams exceeded, with " +
                                               _numWaiting + " queued");
                      return null;
                  }

                  // no remaining streams, let's wait a bit
                  try { Thread.sleep(remaining/4); }
                  catch (InterruptedException ie) {
                      // Honoring the interrupt: restore the flag and abandon
                      // the connect rather than burning the whole wait window
                      // re-throwing on every sleep iteration (see below).
                      Thread.currentThread().interrupt();
                      return null;
                  }
              }

              // Delegate to shared finalizeConnection for cooldown, waitForConnect, and stats.
              // fromPool=false so cooldown is applied for new connections.
              return finalizeConnection(con, peer, opts, connectStart, false);
          } finally {
              // Single drain point for the increment above: every exit (admit,
              // timeout, over-buffer, interrupt, exception) returns this
              // attempt's waiter exactly once, and the CAS clamp in
              // releaseWaiting() keeps the count non-negative.
              releaseWaiting(_numWaiting);
          }
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
                      long now = _context.clock().now();
                      Long lw = _cooldownWarned.get(destHash);
                      long lastWarn = lw == null ? 0 : lw.longValue();
                      if (shouldLogCooldownWarn(lastWarn, now, getDestCooldownMs())) {
                          _cooldownWarned.put(destHash, Long.valueOf(now));
                          if (_log.shouldWarn())
                              _log.warn("Delaying connect to [" + destHash.toBase64().substring(0,6) +
                                        "] for " + delay + "ms (cooldown from previous failure)");
                      }
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
               long boundedTimeout = (long) opts.getConnectDelay() + opts.getConnectTimeout();
               // long math so a huge/saturated connect timeout can't overflow the
               // int sum; clamp into waitForConnect's int range.
               con.waitForConnect((int) Math.min(boundedTimeout, Integer.MAX_VALUE));
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
              trimDestFailures();
          } else {
              _context.statManager().addRateData("stream.connectTime", connectElapsed, connectElapsed);
              _destFailures.remove(destHash);
          }
          // No _numWaiting adjustment here: connect()'s finally block is the
          // single drain point for the increment (releaseWaiting() clamps the
          // CAS at zero). Decrementing again for non-pooled completions —
          // the pre-0.9.71+ behavior — stole a genuinely-waiting connect's
          // slot and drove the count negative under concurrent queued connects.

         _context.statManager().addRateData("stream.connectionCreated", 1);
         return con;
    }

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
     * Drop every connection, sending a RESET instead of a CLOSE handshake.
     * This will not close the ServerSocket.
     * This will not kill the timer threads.
     *
     * CAN continue to use the manager.
     */
    public void disconnectAllHard() {
        // Write side of the admission barrier: every reserve→bind span in
        // connect()/receiveConnection() holds the read lock, so acquiring the
        // write lock here first waits for in-flight admissions to finish binding
        // their tokens; once held, no new admission can start, and the loop
        // below can disconnect, unlink, and release each stream's slot as one
        // atomic step against admission. Without the barrier an admission could
        // re-reserve from the ledger mid-loop and bind a token over counts the
        // clear below is about to erase — that stream's later teardown would
        // then decrement a slot it never took.
        _admissionLock.writeLock().lock();
        try {
            for (Iterator<Connection> iter = _connectionByInboundId.values().iterator(); iter.hasNext(); ) {
                Connection con = iter.next();
                con.disconnect(false, false);
                iter.remove();
                // disconnect(false, false) never reaches removeConnection(), so this
                // is the only place a hard-killed stream gives its budget slot back.
                // Token lookup only: a token exists for exactly the connections in
                // this table, so this can neither miss nor double-count. Claimed on
                // the connection first, so a teardown racing this loop cannot have
                // the slot released a second time.
                releaseConnectionReservation(con);
            }
            // Every connection above has released its own slot; whatever the
            // ledger still holds is either a bound-but-orphaned token or a leak
            // from a stream that failed before its ID was published. With no
            // connections left there is no budget to preserve, so drop it all.
            _streamReservations.clear();
            _streamShrinkCandidates.clear();
            _streamsByDest.clear();
            synchronized(_recentlyClosed) {
                _recentlyClosed.clear();
            }
            _pendingPings.clear();
        } finally {
            _admissionLock.writeLock().unlock();
        }
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
        // Stop the sweeper FIRST: it re-arms itself from inside timeReached(),
        // so letting it fire while the maps below are torn down risks a sweep
        // against a half-dead manager, and one fired after cancel() would
        // outlive this manager entirely. stop() both cancels and blocks
        // re-arming, unlike a bare cancel() from here.
        BanExpiry sweeper = _banExpiry;
        if (sweeper != null)
            sweeper.stop();
        disconnectAllHard();
        _destFailures.clear();
        int protocol = I2PSession.PROTO_STREAMING;
        _session.removeListener(protocol, _defaultOptions.getLocalPort());
        _tcbShare.stop();
        _timer.stop();
        _outboundQueue.close();
        _connectionHandler.setActive(false);
        _packetHandler.shutdownDispatcher();
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

        // Unlink the dispatch tables before the pooled-connection handover: a
        // pooled connection gets fresh stream IDs on reuse, and if it stayed
        // reachable by the old ones a late packet would route into a foreign
        // logical connection. returnToPool() runs after the unlink.
        Long rcvID = Long.valueOf(con.getReceiveStreamId());
        synchronized(_recentlyClosed) {
            _recentlyClosed.put(rcvID, DUMMY);
        }

        // Two-arg removes: if this id was already recycled, a stale entry must
        // not unlink the live connection that now owns it.
        Object o = _connectionByInboundId.remove(rcvID, con);
        // Token lookup first: the reservation records the dest that was
        // actually charged at admission, so teardown neither misses nor
        // mis-attributes the slot (and never has to recompute the peer hash).
        // Claim-guarded on the connection, so a disconnectAllHard() sweep that
        // raced this teardown cannot have the slot released twice.
        releaseConnectionReservation(con);
        long sendId = con.getSendStreamId();
        if (sendId > 0)
            _connectionByOutboundId.remove(Long.valueOf(sendId), con);

        // Attempt to return to pool after removing from the dispatch tables
        returnToPool(con);

        boolean removed = (o == con);
        if (_log.shouldDebug())
            _log.debug("Connection removed? " + removed + " Remaining: "
                       + _connectionByInboundId.size() + "\n " + con);
        if (!removed && _log.shouldDebug())
            _log.debug("Failed to remove " + con + "\n" + _connectionByInboundId.values());

        if (removed) {
            _context.statManager().addRateData("stream.con.lifetimeMessagesSent", 1+con.getLastSendId(), con.getLifetime());
            // Static-scan "stream may not be closed": false positive. The
            // MessageInputStream is owned by Connection and is signalled closed
            // via streamErrorOccurred() + _receiver.destroy() in
            // Connection.disconnectComplete(), which always precedes
            // removeConnection(). Nothing here acquires the stream.
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
        // Byte array.
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
     * Ping the destination through a specific subsession, waiting for the pong.
     *
     * @param peer the destination
     * @param session the subsession to use (must not be null)
     * @param fromPort the source port
     * @param toPort the destination port
     * @param timeoutMs greater than zero
     * @return true if pong received
     * @since 0.9.71+
     */
    public boolean ping(Destination peer, I2PSession session, int fromPort, int toPort, long timeoutMs) {
        return ping(peer, session, fromPort, toPort, timeoutMs, true);
    }

    /**
     * Ping the destination through a specific subsession, optionally blocking.
     *
     * @param peer the destination
     * @param session the subsession to use (must not be null)
     * @param fromPort the source port
     * @param toPort the destination port
     * @param timeoutMs greater than zero
     * @param blocking true to block until pong
     * @return true if blocking and pong received
     * @since 0.9.71+
     */
    public boolean ping(Destination peer, I2PSession session, int fromPort, int toPort, long timeoutMs, boolean blocking) {
        PingRequest req = new PingRequest(null);
        long id = assignPingId(req);
        PacketLocal packet = new PacketLocal(_context, peer, session);
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
            _log.info(String.format("About to ping %s port %d from port %d timeout=%d blocking=%b via subsession",
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
            PingFailed pf = new PingFailed(id, null);
            pf.schedule(timeoutMs);
        }

        return req.pongReceived();
    }

    /**
     * Ping the destination with a payload through a specific subsession.
     *
     * @param peer the destination
     * @param session the subsession to use (must not be null)
     * @param fromPort the source port
     * @param toPort the destination port
     * @param timeoutMs greater than zero
     * @param payload non-null, include in packet, up to 32 bytes may be returned in pong
     * @return the payload received in the pong, zero-length if none, null on failure or timeout
     * @since 0.9.71+
     */
    public byte[] ping(Destination peer, I2PSession session, int fromPort, int toPort, long timeoutMs, byte[] payload) {
        PingRequest req = new PingRequest(null);
        long id = assignPingId(req);
        PacketLocal packet = new PacketLocal(_context, peer, session);
        packet.setSendStreamId(id);
        packet.setFlag(Packet.FLAG_ECHO |
                        Packet.FLAG_NO_ACK |
                        Packet.FLAG_SIGNATURE_INCLUDED);
        packet.setOptionalFrom();
        packet.setLocalPort(fromPort);
        packet.setRemotePort(toPort);
        // Byte array.
        packet.setPayload(new ByteArray(payload));
        if (timeoutMs > getMaxPingTimeout())
            timeoutMs = getMaxPingTimeout();
        if (_log.shouldInfo()) {
            _log.info(String.format("About to ping %s port %d from port %d timeout=%d payload=%d via subsession",
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
     *  Live connection count per dest, preferring the reservation token (the
     *  dest that was actually charged at admission) and falling back to the
     *  connection's remote peer for anything bound outside the protocol.
     *  Built per sweep; a dest with no live connection is simply absent.
     *
     *  @return observed stream count per dest, never null
     *  @since 0.9.71+
     */
    private Map<Hash, Integer> observedStreamCounts() {
        Map<Hash, Integer> observed = new HashMap<>();
        for (Connection con : _connectionByInboundId.values()) {
            Hash h = _streamReservations.get(Long.valueOf(con.getReceiveStreamId()));
            if (h == null && con.getRemotePeer() != null)
                h = con.getRemotePeer().calculateHash();
            if (h == null)
                continue;
            Integer n = observed.get(h);
            observed.put(h, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
        }
        return observed;
    }

    /**
     *  Bound reservation tokens per dest: the ABA floor for
     *  {@link #reconcileStreamCount(int, int, Integer, int)}. A token exists
     *  for exactly the admissions that own a slot (it is bound after the
     *  connection is published and released together with the count), so a
     *  stale shrink candidate can never claim slots that are genuinely held.
     *
     *  @return tokens per dest; empty when nothing is bound, never null
     *  @since 0.9.71+
     */
    private Map<Hash, Integer> tokenStreamCounts() {
        Map<Hash, Integer> counts = new HashMap<>();
        for (Hash h : _streamReservations.values()) {
            if (h == null)
                continue;
            Integer n = counts.get(h);
            counts.put(h, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
        }
        return counts;
    }

    /**
     *  Periodically drops temp-bans whose time has elapsed and resets the
     *  per-dest refusal window, so a ban always expires (24h default) and the
     *  refusal counter doesn't grow unbounded. Mirrors ConnThrottler.Cleaner.
     *  Also reconciles the per-dest stream budgets against the live
     *  connection table.
     *  @since 0.9.71+
     */
    private class BanExpiry extends SimpleTimer2.TimedEvent {
        private static final long PERIOD = 60 * 1000;

        /**
         *  Set by stop(); blocks both further sweeps and, critically, the
         *  self re-arm at the end of timeReached(). TimedEvent.schedule()
         *  unconditionally clears _cancelAfterRun, so a bare cancel() racing
         *  a sweep that had already started would be undone by that
         *  re-arming schedule() — the event would fire again on a shut-down
         *  manager. Reading the flag makes shutdown sticky either way.
         */
        private volatile boolean _stopped;

        BanExpiry() {
            super(_context.simpleTimer2());
            schedule(PERIOD + (PERIOD / 2));
        }

        /**
         *  Shut this sweeper down permanently: cancel the pending run and
         *  prevent timeReached() from re-arming itself. Called from
         *  ConnectionManager.shutdown(), which owns this instance.
         *  @since 0.9.71+
         */
        void stop() {
            _stopped = true;
            cancel();
        }

        /**
         *  Whether {@link #stop()} has been called.
         *  @return true once stopped
         *  @since 0.9.71+
         */
        boolean isStopped() {
            return _stopped;
        }

        public void timeReached() {
            if (_stopped)
                return;
            long now = _context.clock().now();
            // Refresh cached autoban config once per sweep so the hot path never
            // re-reads the property store on every validated SYN.
            _tempBanMs = _context.getProperty(PROP_TEMP_BAN_MINUTES, (int) DEFAULT_TEMP_BAN_MINUTES)
                        * 60L * 1000L;
            _tempBanRefusals = _context.getProperty(PROP_TEMP_BAN_REFUSALS, DEFAULT_TEMP_BAN_REFUSALS);
            _synRateMs = _context.getProperty(PROP_TEMP_BAN_RATE_MS, DEFAULT_TEMP_BAN_RATE_MS);
            _synBurst = _context.getProperty(PROP_TEMP_BAN_SYN_BURST, DEFAULT_TEMP_BAN_SYN_BURST);
            final boolean autoban = _context.getProperty(PROP_AUTOBAN, DEFAULT_AUTOBAN) != 0;
            if (_autobanEnabled && !autoban) {
                // Enforcement just turned off: strikes recorded under the old
                // policy must not outlive it, or a later flip back on would ban
                // on pre-disable evidence. Burst windows go too, so nothing is
                // counted under the disabled policy and replayed when re-enabled.
                _synBurstStrikes.clear();
                _recentSyns.clear();
            }
            _autobanEnabled = autoban;
            long ms = _tempBanMs;
            if (ms > 0) {
                // Two-arg removes: a ban refreshed between the value read and
                // the remove keeps its entry (removeIf would drop the newer ban).
                for (Map.Entry<Hash, Long> e : _tempBanUntil.entrySet()) {
                    Long until = e.getValue();
                    if (until.longValue() <= now && _tempBanUntil.remove(e.getKey(), until))
                        _tempBanReason.remove(e.getKey());
                }
                if (_log.shouldDebug() && !_tempBanUntil.isEmpty())
                    _log.debug("Temp bans: " + _tempBanUntil.size());
            }
            // Drop burst-window state for dests whose window aged out, so a dest that
            // surged (but stayed under threshold) can't keep a stale entry indefinitely.
            // The age re-check runs under the window's own monitor because
            // checkSynBurst() re-arms an expired window in place under that lock;
            // the two-arg remove then drops the entry only if it did not change,
            // so a freshly re-armed window is never swept away.
            long windowMs = _synRateMs;
            if (windowMs > 0) {
                for (Map.Entry<Hash, long[]> e : _recentSyns.entrySet()) {
                    long[] cur = e.getValue();
                    synchronized (cur) {
                        if (now - cur[0] >= windowMs)
                            _recentSyns.remove(e.getKey(), cur);
                    }
                }
            }
            // Forgive strikes older than the strike window so a single unlucky
            // page-load burst never carries a ban across a long idle gap. Age is
            // measured from the strike time, not the (millisecond-scale) burst
            // window that caused it. With autoban off, drop all strikes each
            // sweep: they are evidence recorded under an inactive policy.
            if (!autoban) {
                _synBurstStrikes.clear();
            } else {
                for (Map.Entry<Hash, SynStrike> e : _synBurstStrikes.entrySet()) {
                    SynStrike strike = e.getValue();
                    if (strike == null || now - strike.strikeTime >= STRIKE_WINDOW_MS)
                        _synBurstStrikes.remove(e.getKey(), strike);
                }
            }
            // Reconcile per-dest stream budgets against the live connection table
            // once per sweep, so a teardown that slipped past every release path
            // cannot permanently inflate a dest's budget. Shrink-only and
            // two-strike (see reconcileStreamCount), never a blind rebuild: a
            // rebuild erases slots held by connections that are mid-admission,
            // handing out more than the ceiling for up to a sweep. Bound tokens
            // floor the shrink so a stale candidate can never claim slots a
            // concurrent admission already owns (see tokenStreamCounts()).
            reconcileStreamSlots(_streamsByDest, observedStreamCounts(), _streamShrinkCandidates,
                                 tokenStreamCounts());
            // Refusals decay by half each 60s sweep instead of being cleared to
            // zero. A hard clear lets a sustained-but-spread flood (< burst + under
            // the full 60s-window threshold) slip through forever, since no single
            // 60s window ever accumulates >tempBanRefusals. Decay keeps a slow,
            // persistent abuser accumulating toward the ban while an isolated spike
            // (e.g. a legit announce rollout) fades to nothing within a few sweeps.
            _refusalCounter.decay(2);
            if (!_stopped)
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
