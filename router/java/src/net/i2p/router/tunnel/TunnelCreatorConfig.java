package net.i2p.router.tunnel;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.TunnelId;
import net.i2p.router.JobImpl;
import net.i2p.router.ProfileManager;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelTestStatus;
import net.i2p.router.networkdb.kademlia.MessageWrapper.OneTimeSession;
import net.i2p.util.Log;

/**
 * Coordinate the info that the tunnel creator keeps track of, including what
 * peers are in the tunnel and what their configuration is
 *
 * See PooledTunnelCreatorConfig for the non-abstract class
 */
public abstract class TunnelCreatorConfig implements TunnelInfo {
    /**
     * The router context.
     */
    protected final RouterContext _context;
    /** Only necessary for client tunnels. */
    private final Hash _destination;
    /** Gateway first. */
    private final HopConfig[] _config;
    /** Gateway first. */
    private final Hash[] _peers;
    private volatile long _expiration;
    private List<Integer> _order;
    private long _replyMessageId;
    private final boolean _isInbound;
    private final AtomicInteger _messagesProcessed = new AtomicInteger();
    /**
     * Total verified bytes on this tunnel.  A {@link LongAdder} so the
     * per-fragment delivery path can add without the tunnel monitor; a
     * stale-by-one-add read of a monotonic counter is as good as synchronized.
     * @since 0.9.71+
     */
    private final LongAdder _verifiedBytesTransferred = new LongAdder();
    /**
     * Wall clock (ms) of the last production byte, volatile for the same
     * reason as {@link #_verifiedBytesTransferred}.
     * @since 0.9.71+
     */
    private volatile long _lastTransferredTime;
    /**
     * Wall-clock time this config was created, i.e. when its build started.
     * Lets the test cycle give a freshly built tunnel a grace period before
     * its results count against it — a tunnel cannot fairly be judged on a
     * round trip it had no realistic chance to complete.  Deliberately not
     * on {@link TunnelInfo}: the test cycle is the only reader, and adding a
     * method to a public interface for one caller would break out-of-tree
     * implementors.
     * @since 0.9.71+
     */
    private final long _creationTime = System.currentTimeMillis();
    private final AtomicInteger _failures = new AtomicInteger();
    private final AtomicInteger _softFailures = new AtomicInteger();
    /**
     * Wall-clock time of the most recent soft best-effort timeout, 0 if none.
     * With {@link #SOFT_FAILURE_WINDOW_MS} this bounds the soft streak at
     * read time: a failure older than the window decays out of the count, so
     * a tunnel that times out once in a while forever is never degraded or
     * rotated out, while a genuine burst still reaches the degraded and
     * removal bars.
     * @since 0.9.71+
     */
    private volatile long _lastSoftFailure;
    /**
     * Raw count of spaced first-hop send failures.  Decay and spacing are
     * applied at read time, see {@link #recordFirstHopSendFailure()}.
     * @since 0.9.71+
     */
    private final AtomicInteger _firstHopSendFailures = new AtomicInteger();
    /**
     * Wall-clock time of the most recent counted first-hop send failure,
     * 0 if none.  Only stamped when the count actually advances, so a
     * burst cannot keep pushing the spacing deadline forward and never
     * reach the threshold.
     * @since 0.9.71+
     */
    private volatile long _lastFirstHopSendFailure;
    /**
     * One-shot escalation latch: once the streak reached
     * {@link #FIRST_HOP_FAILURE_THRESHOLD} the caller is told exactly once.
     * The send-failure job is shared by every message on the tunnel and is
     * only deduped while queued, so without this a single burst would call
     * {@link #tunnelFailedFirstHop()} for each failed message.
     * @since 0.9.71+
     */
    private volatile boolean _firstHopFailed;
    private volatile TunnelTestStatus _testStatus = TunnelTestStatus.UNTESTED;

    private volatile boolean _reused;
    private volatile int _priority;
    /**
     * Whether a transport session to our local hop existed when this tunnel entered
     * the pool. Recorded once at build time by the pool, then read-only.
     *
     * <p>See {@link net.i2p.router.TunnelInfo#hadLocalHopSession()} for why the pool
     * needs the build-time fact rather than the current session state.
     * @since 0.9.71+
     */
    private volatile boolean _localHopSessionEstablished;
    /**
     * Bytes verified on this tunnel since the last per-peer profile update.
     * A {@link LongAdder} because it is bumped once per 1KB fragment and does
     * not need the monitor guarding {@link #_peakThroughputLastCoallesce}.
     * @since 0.9.71+
     */
    private final LongAdder _peakThroughputCurrentTotal = new LongAdder();
    /**
     * Wall clock (ms) of the last per-peer profile update.  Written under the
     * tunnel monitor by {@link #coalescePeakThroughput(long)} but read without
     * it, so that the per-fragment path can skip the monitor, hence volatile.
     * @since 0.9.71+
     */
    private volatile long _peakThroughputLastCoallesce = System.currentTimeMillis();
    private Hash _blankHash;
    private SessionKey[] _ChaReplyKeys;
    private byte[][] _ChaReplyADs;
    private final SessionKey[] _AESReplyKeys;
    private final byte[][] _AESReplyIVs;
    // short record OBEP only
    private OneTimeSession _garlicReplyKeys;
    private Log _log;

    /**
     * IV length for {@link #getAESReplyIV}
     * @since 0.9.48 moved from HopConfig
     */
    public static final int REPLY_IV_LENGTH = 16;

    // Make configurable? - but can't easily get to pool options from here
    /**
     * Maximum consecutive test failures before the tunnel is retired.
     */
    public static final int MAX_CONSECUTIVE_TEST_FAILURES = 3;
    /**
     * Window in which soft best-effort send timeouts accumulate into a
     * consecutive streak (status-3 timeouts only — hard dispatch failures
     * use {@link #MAX_CONSECUTIVE_TEST_FAILURES}).  A soft failure older
     * than this is decayed out of the count, bounding both the degraded bar
     * ({@code TunnelPool.SOFT_DEGRADED_FOR_ENSURE}) and the soft removal bar
     * so sparse congestion timeouts over a long tunnel lifetime can never
     * retire a tunnel that carries data when it is sent.
     * @since 0.9.71+
     */
    static final long SOFT_FAILURE_WINDOW_MS = 10 * 60 * 1000L;
    /**
     * Ceiling for the soft failure streak. The count exists only to cross the
     * soft removal bar, so saturating well above it costs nothing and stops a
     * retained tunnel that keeps timing out from reporting a streak long
     * enough to be mistaken for a count of independent failures.
     * @since 0.9.71+
     */
    static final int MAX_SOFT_FAILURES = 32;
    /**
     * Consecutive first-hop send failures before an outbound tunnel is
     * retired.  Send failures are not proof the tunnel is dead: most come
     * from local congestion (expired-on-queue, CoDel drops, no-bid replies)
     * and land on a shared job that runs once per failed message, so the
     * bar sits above a single burst.  The count is spaced out by
     * {@link #FIRST_HOP_FAILURE_SPACING_MS} so a local stall collapses to
     * 1 while a genuinely unreachable first hop reaches the bar on its own
     * within {@link #FIRST_HOP_FAILURE_WINDOW_MS}.
     * @since 0.9.71+
     */
    public static final int FIRST_HOP_FAILURE_THRESHOLD = 3;
    /**
     * Ceiling for {@link #incrementTestFailures}.  Comfortably above the
     * largest removal threshold any caller can compute (degraded mode with a
     * thin pool), so saturation never changes a decision — it only stops the
     * reported count from drifting.
     * @since 0.9.71+
     */
    public static final int FAILURE_COUNT_CEILING = 32;

    /**
     * Minimum spacing between first-hop send failures that count towards
     * {@link #FIRST_HOP_FAILURE_THRESHOLD}.  Failures closer together are
     * one event, so a burst of 40 messages failing in the same second
     * records 1, not 40.
     * @since 0.9.71+
     */
    public static final long FIRST_HOP_FAILURE_SPACING_MS = 5 * 1000L;
    /**
     * Window in which spaced first-hop send failures accumulate, mirroring
     * {@link #SOFT_FAILURE_WINDOW_MS}.  A failure older than this is decayed
     * out at read time, so an idle tunnel that finally loses a send never
     * inherits an unbounded streak.
     * @since 0.9.71+
     */
    public static final long FIRST_HOP_FAILURE_WINDOW_MS = 60 * 1000L;
    /**
     * Window over which this tunnel's throughput is totaled before the
     * per-peer profiles are updated, in ms.  Matches the window
     * {@code PeerProfile} coalesces its own peak throughput on.
     * @since 0.9.71+
     */
    private static final long PEAK_THROUGHPUT_COALESCE_MS = 60 * 1000L;
    private static final int LATENCY_SAMPLE_SIZE = 3;
    private volatile int _lastLatency = -1;
    private final int[] _latencyHistory = new int[LATENCY_SAMPLE_SIZE];
    private volatile int _latencyIdx = 0;
    private volatile int _latencyCount = 0;
    private volatile boolean _needsExpeditedTest = false;
    /**
     * Recent-traffic test exemptions used.
     * A tunnel with recent verified data gets a few free passes on test
     * failures (reply-path false negatives), but not unlimited — after
     * {@link TestJob#MAX_RECENT_EXEMPTIONS} exemptions, failures count
     * normally so the tunnel doesn't become immortal.
     * @since 0.9.69+
     */
    private volatile int _recentTestExemptions;
    /** Optional pool nickname for log display. */
    private String _destinationNickname;

    /**
     * For exploratory only (null destination)
     * @param length 1 minimum (0 hop is length 1)
     */
    public TunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound) {
        this(ctx, length, isInbound, null);
    }

    /**
     * Optional display nickname for this tunnel (e.g. pool name like I2PSnark)
     * @since 0.9.70+
     */
    public void setDestinationNickname(String name) { _destinationNickname = name; }

    /**
     * Pool nickname for this tunnel, or null if not set.
     * @return the pool nickname if set, null otherwise
     * @since 0.9.70+
     */
    public String getDestinationNickname() { return _destinationNickname; }

    /**
     * Allocates the hop configs and peer arrays for the given length.
     *
     * @param length 1 minimum (0 hop is length 1)
     * @param destination null for exploratory
     */
    public TunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound, Hash destination) {
        _context = ctx;
        if (length <= 0) {throw new IllegalArgumentException("0 length? 0 hop tunnels are 1 length!");}
        _config = new HopConfig[length];
        _peers = new Hash[length];
        for (int i = 0; i < length; i++) {_config[i] = new HopConfig();}
        _isInbound = isInbound;
        _destination = destination;
        _AESReplyKeys = new SessionKey[length];
        _AESReplyIVs = new byte[length][];
    }

    /**
     * How many hops are there in the tunnel?
     * INCLUDING US.
     * i.e. one more than the TunnelCreatorConfig length.
     * @return the length
     */
    public int getLength() {return _config.length;}

    /**
     * The tunnel pool options.
     *
     * @return the options
     */
    public Properties getOptions() {return null;}

    /**
     * Retrieve the config for the given hop.  the gateway is
     * hop 0.
     * @return the config
     */
    public HopConfig getConfig(int hop) {return _config[hop];}

    /**
     * Retrieve the tunnelId that the given hop receives messages on.
     * the gateway is hop 0.
     *
     * @return the receive tunnel id
     */
    public TunnelId getReceiveTunnelId(int hop) {return _config[hop].getReceiveTunnel();}

    /**
     * Retrieve the tunnelId that the given hop sends messages on.
     * the gateway is hop 0.
     *
     * @return the send tunnel id
     */
    public TunnelId getSendTunnelId(int hop) {return _config[hop].getSendTunnel();}

    /** Retrieve the peer at the given hop (the gateway is hop 0). */
    public Hash getPeer(int hop) {return _peers[hop];}
    /**
     * The peer at the given hop.
     */
    public void setPeer(int hop, Hash peer) {_peers[hop] = peer;}

    /**
     * For convenience
     * @return getPeer(0)
     * @since 0.8.9
     */
    public Hash getGateway() {return _peers[0];}

    /**
     * For convenience
     * @return getPeer(getLength() - 1)
     * @since 0.8.9
     */
    public Hash getEndpoint() {return _peers[_peers.length - 1];}

    /**
     * For convenience
     * @return isInbound() ? getGateway() : getEndpoint()
     * @since 0.8.9
     */
    public Hash getFarEnd() {return _peers[_isInbound ? 0 : _peers.length - 1];}

    /** Is this an inbound tunnel? */
    public boolean isInbound() {return _isInbound;}

    /**
     * If this is a client tunnel, what destination is it for?
     * @return null for exploratory
     */
    public Hash getDestination() {return _destination;}

    /**
     * The tunnel expiration, in ms since the epoch.
     *
     * @return the expiration
     */
    public long getExpiration() {return _expiration;}
    /**
     * The tunnel expiration, in ms since the epoch.
     */
    public void setExpiration(long when) {_expiration = when;}

    /**
     * Whether this tunnel held a transport session to its local hop when it was
     * built. False when never recorded.
     */
    public boolean hadLocalHopSession() {return _localHopSessionEstablished;}

    /**
     * Record whether a transport session to the local hop existed at build time.
     *
     * @param established the session state observed when the tunnel entered the pool
     */
    public void setLocalHopSessionEstablished(boolean established) {
        _localHopSessionEstablished = established;
    }

    /** Component ordering in the new style request. */
    public List<Integer> getReplyOrder() {return _order;}
    /**
     * The component ordering for the new style request.
     */
    public void setReplyOrder(List<Integer> order) {_order = order;}

    /** The message ID for the new style reply. */
    public long getReplyMessageId() {return _replyMessageId;}
    /**
     * The message ID for the new style reply.
     */
    public void setReplyMessageId(long id) {_replyMessageId = id;}

    /** Take note of a message being pumped through this tunnel. */
    public void incrementProcessedMessages() {_messagesProcessed.incrementAndGet();}
    /**
     * The number of messages pumped through this tunnel.
     *
     * @return the processed messages count
     */
    public int getProcessedMessagesCount() {return _messagesProcessed.get();}

    /**
     * Record that {@code bytes} moved through this tunnel, counting as real
     * traffic: it also stamps the last-real-traffic clock that
     * {@link #getLastTransferred()} reports and the data-verified test trust
     * in TestJob keys off.  Only the real delivery paths — inbound data
     * arrival and outbound message dispatch — may call this.
     *
     * @param bytes bytes counted against the tunnel
     * @since 0.9.71+
     */
    public void incrementVerifiedBytesTransferred(int bytes) {
        incrementVerifiedBytesTransferred(bytes, true);
    }

    /**
     * Record that {@code bytes} moved through this tunnel, optionally
     * counting as real traffic.
     *
     * <p>The distinction matters because two things read this state.  The
     * throughput profile wants *every* byte, including the 1024 a test round
     * pushes, or the per-peer throughput figures understate a busy tunnel.
     * The data-verified test trust wants *only* real traffic, because it
     * reads getLastTransferred() to decide the tunnel has proven itself in
     * production.  When the test round called the unconditional form, a
     * passing test stamped the real-traffic clock on the tunnel under test —
     * which for an outbound test is the tunnel itself — so a tunnel that had
     * never carried a single production byte could grant itself the trust
     * that is meant to mean the opposite.
     *
     * @param bytes bytes counted against the tunnel
     * @param realTraffic true when this is production traffic, false for
     * test or synthetic traffic
     * @since 0.9.71+
     */
    public void incrementVerifiedBytesTransferred(int bytes, boolean realTraffic) {
        // No monitor: this is the inbound endpoint's per-1KB-fragment path and
        // every counter it touches is a LongAdder or a volatile.
        _verifiedBytesTransferred.add(bytes);
        _peakThroughputCurrentTotal.add(bytes);
        long now = System.currentTimeMillis();
        if (realTraffic) {
            _lastTransferredTime = now;
        }
        if (now - _peakThroughputLastCoallesce >= PEAK_THROUGHPUT_COALESCE_MS) {
            coalescePeakThroughput(now);
        }
    }

    /**
     * Periodically push this tunnel's throughput into the per-peer profiles.
     *
     * <p>Run once a minute from
     * {@link #incrementVerifiedBytesTransferred(int, boolean)}, crediting both
     * {@code tunnelDataPushed()} (raw period bytes) and
     * {@code tunnelDataPushed1m()} (that total normalized to a minute): both
     * are pure sums, so bulk crediting equals per-fragment crediting.
     *
     * @param now wall clock at the call site, already compared against the
     * coalesce deadline so the fast path never gets here
     * @since 0.9.71+
     */
    private synchronized void coalescePeakThroughput(long now) {
        long timeSince = now - _peakThroughputLastCoallesce;
        // Re-tested under the monitor: inbound arrival and outbound dispatch can
        // both race in, and the loser must not reset the period twice.
        if (timeSince < PEAK_THROUGHPUT_COALESCE_MS) {return;}
        long tot = _peakThroughputCurrentTotal.sumThenReset();
        // Exact in long: tot * 60000 does not overflow for any byte count a
        // minute can hold, and avoids the double multiply/divide.
        long normalized = tot * PEAK_THROUGHPUT_COALESCE_MS / timeSince;
        _peakThroughputLastCoallesce = now;
        // Capture peers and context for iteration outside the lock.
        // The slice drops our own end of the tunnel; ProfileManagerImpl drops
        // our hash as well.
        ProfileManager pm = _context != null ? _context.profileManager() : null;
        if (pm != null && _peers.length > 0) {
            int start = _isInbound ? 0 : 1;
            int end = _isInbound ? _peers.length - 1 : _peers.length;
            Hash[] peersCopy = Arrays.copyOfRange(_peers, start, end);
            _context.jobQueue().addJob(new JobImpl(_context) {
                /**
                 * Update the per-peer throughput profiles with the normalized total.
                 */
                @Override
                public void runJob() {
                    for (Hash peer : peersCopy) {
                        pm.tunnelDataPushed(peer, 0, tot);
                        pm.tunnelDataPushed1m(peer, normalized);
                    }
                }
                /**
                 * The name of this job.
                 *
                 * @return the name
                 */
                @Override
                public String getName() { return "TunnelCreatorConfig profile update"; }
            });
        }
    }

    /**
     * The total verified bytes transferred on this tunnel.
     *
     * @return the verified bytes transferred
     */
    public long getVerifiedBytesTransferred() {return _verifiedBytesTransferred.sum();}

    /**
     * When we last sent or received data on this tunnel
     * @return the last transferred
     */
    public long getLastTransferred() { return _lastTransferredTime; }

    /**
     * When this tunnel was built, in wall-clock ms.  Never zero, so a caller
     * asking "is this young" needs no null or unset case.
     *
     * @return creation time in ms since the epoch
     * @since 0.9.71+
     */
    public long getCreationTime() { return _creationTime; }

    /**
     * When the tunnel last carried real (non-test) traffic, or 0 if never.
     * Updated only at the real traffic delivery sites — inbound data arrival
     * (InboundEndpointProcessor) and outbound message dispatch
     * (OutboundClientMessageOneShotJob) — never by TestJob, so test traffic
     * cannot pollute the proof that the tunnel works.
     * @since 0.9.71+
     */
    private volatile long _lastRealTraffic;

    /**
     * Record that the tunnel carried real traffic.  Real data proves the
     * tunnel works, so any soft best-effort streak accumulated before it is
     * cleared alongside it.
     *
     * It also refills the recent-traffic test exemption budget.  That budget
     * is spent when a test fails while the tunnel demonstrably carried data
     * — a reply-path false negative rather than evidence against the tunnel —
     * and it used to be refilled only by {@link #testSuccessful(int)}, so a
     * tunnel that could never answer a test but could still carry data got
     * exactly one free pass for its whole lifetime and was then condemned by
     * failures it had no way to clear.  Traffic is the stronger proof: the
     * inbound caller only fires after the tunnel's crypto verified, and the
     * outbound caller only fires after the remote peer ACKed.
     *
     * Status itself is not touched here — see
     * TunnelPool.clearFailingOnTraffic(), which owns promotion back to GOOD.
     *
     * @since 0.9.71+
     */
    public void recordRealTraffic() {
        _lastRealTraffic = System.currentTimeMillis();
        clearSoftFailures();
        clearFirstHopFailures();
        _recentTestExemptions = 0;
    }

    /**
     * When the tunnel last carried real traffic.
     * @return the timestamp, or 0 if it never carried real traffic
     * @since 0.9.71+
     */
    public long getLastRealTraffic() {return _lastRealTraffic;}

    /**
     * The tunnel failed a test, so (maybe) stop using it
     *
     * @return false if we stopped using it, true if still ok
     */
    public boolean tunnelFailed() {
        boolean rv = _failures.incrementAndGet() <= MAX_CONSECUTIVE_TEST_FAILURES;
        if (!rv) {_reused = true;} // don't allow it to be rebuilt
        return rv;
    }

    /**
     * Increment the failure count without triggering pool removal or reuse flag.
     * Used when a previously GOOD tunnel fails a retest — we want to track
     * the failure for selection deprioritization but keep the tunnel alive
     * for further testing and data delivery.
     *
     * <p>The count saturates.  Every caller compares the result against a
     * removal threshold, so once that threshold is passed the extra
     * increments change no decision — they only made the counter (and the
     * log line quoting it) grow without bound.  A condemned-but-retained
     * tunnel kept retesting reached counts in the hundreds, which made a
     * saturated metric impossible to read and hid the real failure ratio.
     * @since 0.9.69+
     */
    public void incrementTestFailures() {
        _failures.getAndUpdate(cur -> (cur >= FAILURE_COUNT_CEILING) ? cur : cur + 1);
    }

    /**
     * Increment the soft best-effort timeout counter only.
     * Soft status-3 must not trip getTunnelFailed() or selection gates
     * that key on the hard/test counter — congestion is not tunnel death.
     * The streak is time-windowed: a failure that arrives after
     * {@link #SOFT_FAILURE_WINDOW_MS} without one starts the count over at
     * 1 instead of extending an aging streak forever.
     *
     * @since 0.9.71+
     */
    public void incrementSoftFailures() {
        long now = System.currentTimeMillis();
        synchronized (this) {
            int current = effectiveSoftFailures(_softFailures.get(), _lastSoftFailure,
                                                now, SOFT_FAILURE_WINDOW_MS);
            // Saturate rather than wrap: the count only has to cross the removal
            // bar, and an unbounded streak on a retained tunnel produced counts
            // in the thousands that read as distinct failures when they were one
            // condition counted once per timeout.
            _softFailures.set(current >= MAX_SOFT_FAILURES
                              ? MAX_SOFT_FAILURES : current + 1);
            _lastSoftFailure = now;
        }
    }

    /**
     * Soft best-effort timeout count, with failures older than
     * {@link #SOFT_FAILURE_WINDOW_MS} decayed out at read time (the
     * time-windowed form of the streak — no sweep required).
     * @return the effective soft failure count
     * @since 0.9.71+
     */
    public int getSoftFailures() {
        return effectiveSoftFailures(_softFailures.get(), _lastSoftFailure,
                                     System.currentTimeMillis(), SOFT_FAILURE_WINDOW_MS);
    }

    /**
     * Reset the soft streak: verified traffic proves the tunnel carries
     * data, so congestion timeouts recorded before it no longer count.
     * Called from {@link #recordRealTraffic()} and
     * {@link #clearTestFailures()}, never from {@link #testSuccessful(int)} —
     * a passing test exercises the test path, not the data path.
     * @since 0.9.71+
     */
    public void clearSoftFailures() {
        // No-monitor fast path for the per-fragment caller: both fields are
        // atomic/volatile, and clearing an already-clear streak is a no-op, so
        // the unlocked read decides the same thing. A failure recorded
        // concurrently survives, the correct order for clear vs record.
        if (_softFailures.get() == 0 && _lastSoftFailure == 0) {return;}
        synchronized (this) {
            _softFailures.set(0);
            _lastSoftFailure = 0;
        }
    }

    /**
     * Effective soft-failure streak for a raw count stamped at a point in
     * time.  Pure decision helper so the decay rule is unit-testable without
     * waiting out the real window.
     *
     * @param raw stored soft failure count, &lt;= 0 means none
     * @param lastSoftFailure wall-clock time of the most recent soft
     * failure (ms), &lt;= 0 means none
     * @param now current wall-clock time (ms)
     * @param windowMs window in which soft failures accumulate (ms)
     * @return the count to act on: 0 once the streak has aged out
     * @since 0.9.71+
     */
    static int effectiveSoftFailures(int raw, long lastSoftFailure, long now, long windowMs) {
        if (raw <= 0 || lastSoftFailure <= 0 || now < lastSoftFailure ||
            now - lastSoftFailure >= windowMs) {
            return 0;
        }
        return raw;
    }

    /**
     * Effective first-hop send failure streak for a raw count stamped at a
     * point in time.  Same time-windowed decay rule as
     * {@link #effectiveSoftFailures(int, long, long, long)} — both are
     * read-time filters over a raw counter, so no sweep is required.
     *
     * @param raw stored first-hop failure count, &lt;= 0 means none
     * @param lastCounted wall-clock time of the most recent counted failure
     * (ms), &lt;= 0 means none
     * @param now current wall-clock time (ms)
     * @param windowMs window in which first-hop failures accumulate (ms)
     * @return the count to act on: 0 once the streak has aged out
     * @since 0.9.71+
     */
    static int effectiveFirstHopFailures(int raw, long lastCounted, long now, long windowMs) {
        return effectiveSoftFailures(raw, lastCounted, now, windowMs);
    }

    /**
     * Next first-hop failure count for a send failure arriving at {@code now},
     * folding in both the decay window and the spacing rule.  Pure decision
     * helper so the burst/stale behaviour is unit-testable without real waits.
     *
     * @param raw stored first-hop failure count, &lt;= 0 means none
     * @param lastCounted wall-clock time of the most recent counted failure
     * (ms), &lt;= 0 means none
     * @param now current wall-clock time (ms)
     * @param spacingMs minimum spacing between counted failures (ms)
     * @param windowMs window in which first-hop failures accumulate (ms)
     * @return the new count (&gt;= 1), or -1 when this failure is a burst
     * duplicate inside the spacing interval and must not be counted
     * @since 0.9.71+
     */
    static int nextFirstHopFailureCount(int raw, long lastCounted, long now,
                                        long spacingMs, long windowMs) {
        int effective = effectiveFirstHopFailures(raw, lastCounted, now, windowMs);
        if (effective <= 0) {
            return 1;
        }
        if (lastCounted > 0 && now >= lastCounted && now - lastCounted < spacingMs) {
            return -1;
        }
        return effective + 1;
    }

    /**
     * Record a failed send to the first hop of an outbound tunnel.
     *
     * Called from the outbound send-failure job, which fires for every
     * non-requeueable send failure (expired on queue, CoDel drop, no-bid
     * reply, dropped message) — most of which are local congestion, not a
     * dead peer.  Counting raw would retire the tunnel on one bad second,
     * so the streak is decayed by {@link #FIRST_HOP_FAILURE_WINDOW_MS} and
     * spaced by {@link #FIRST_HOP_FAILURE_SPACING_MS}.
     *
     * @return -1 if not applicable (inbound or zero-hop tunnel), 0 if this
     * failure changed nothing (inside the spacing interval, or the
     * threshold was already reached and escalated), otherwise the
     * current streak; a return value of
     * {@link #FIRST_HOP_FAILURE_THRESHOLD} or more means the caller
     * must fail the tunnel, and it is only handed that signal once
     * until {@link #clearFirstHopFailures()} re-arms it
     * @since 0.9.71+
     */
    public int recordFirstHopSendFailure() {
        return recordFirstHopSendFailure(System.currentTimeMillis());
    }

    /**
     * Timestamped form of {@link #recordFirstHopSendFailure()}, so spacing,
     * decay and latching are testable without real waits.
     *
     * @param now wall-clock time (ms) of this failure
     * @return see {@link #recordFirstHopSendFailure()}
     * @since 0.9.71+
     */
    int recordFirstHopSendFailure(long now) {
        if (isInbound() || getLength() <= 1) {return -1;}
        if (_firstHopFailed) {return 0;}
        synchronized (this) {
            int next = nextFirstHopFailureCount(_firstHopSendFailures.get(), _lastFirstHopSendFailure,
                                                now, FIRST_HOP_FAILURE_SPACING_MS,
                                                FIRST_HOP_FAILURE_WINDOW_MS);
            if (next <= 0) {return 0;}
            _firstHopSendFailures.set(next);
            // Only stamped on an advancing count, so a burst cannot extend
            // the spacing deadline and stall the streak below the bar.
            _lastFirstHopSendFailure = now;
            if (next >= FIRST_HOP_FAILURE_THRESHOLD) {_firstHopFailed = true;}
            return next;
        }
    }

    /**
     * Effective first-hop send failure streak, with failures older than
     * {@link #FIRST_HOP_FAILURE_WINDOW_MS} decayed out at read time.
     *
     * @return the streak count, 0 if none
     * @since 0.9.71+
     */
    public int getFirstHopSendFailures() {
        return getFirstHopSendFailures(System.currentTimeMillis());
    }

    /**
     * Timestamped form of {@link #getFirstHopSendFailures()}.
     *
     * @param now wall-clock time (ms) to evaluate the decay against
     * @return the streak count, 0 if none
     * @since 0.9.71+
     */
    int getFirstHopSendFailures(long now) {
        return effectiveFirstHopFailures(_firstHopSendFailures.get(), _lastFirstHopSendFailure,
                                         now, FIRST_HOP_FAILURE_WINDOW_MS);
    }

    /**
     * Reset the first-hop send failure streak and re-arm the escalation
     * latch.  Only a data-carrying send proves the path to the first hop
     * works, so the sole caller is {@link #recordRealTraffic()}.
     *
     * A passing test deliberately does not clear the streak: the round trip
     * proves the peer is not dead, but not that it is not flaky, and a first
     * hop that alternates fail/pass/fail/pass would never accumulate to
     * {@link #FIRST_HOP_FAILURE_THRESHOLD} if every pass reset it.  Staleness
     * is handled by the {@link #FIRST_HOP_FAILURE_WINDOW_MS} read-time decay
     * instead, which ages out a genuinely dead peer's old failures without
     * making accumulation impossible for a merely flaky one.
     *
     * This differs from {@link #clearSoftFailures()}, which likewise is never
     * called from {@link #testSuccessful(int)} for the same reason.
     *
     * @since 0.9.71+
     */
    public void clearFirstHopFailures() {
        // Same no-monitor fast path as clearSoftFailures().
        if (_firstHopSendFailures.get() == 0 && _lastFirstHopSendFailure == 0 && !_firstHopFailed) {return;}
        synchronized (this) {
            _firstHopSendFailures.set(0);
            _lastFirstHopSendFailure = 0;
            _firstHopFailed = false;
        }
    }

    /**
     * The tunnel failed completely, so definitely stop using it
     *
     * @since 0.9.53
     */
    public void tunnelFailedCompletely() {
        _failures.addAndGet(MAX_CONSECUTIVE_TEST_FAILURES + 1);
        _reused = true; // don't allow it to be rebuilt
    }

    /**
     * We failed to contact the first hop for an outbound tunnel,
     * so immediately stop using it.
     * For outbound non-zero-hop tunnels only.
     * <p>
     * Base hook: marks the tunnel as failed.  Subclasses with a pool
     * (PooledTunnelCreatorConfig) also notify the pool of the failure.
     *
     * @since 0.9.71+ (moved up from PooledTunnelCreatorConfig to break the
     * tunnel -> pool package cycle)
     */
    public void tunnelFailedFirstHop() {
        if (isInbound() || getLength() <= 1) {return;}
        tunnelFailedCompletely();
    }

    /**
     * Hook invoked whenever the first-hop send failure streak advances.
     * Gives subclasses a chance to re-warm the path to the first hop: the
     * failure may be a local stall rather than a dead peer, and a fresh
     * transport session makes the next send likely to land.  Not invoked for
     * burst duplicates, so callers see at most one hook per
     * {@link #FIRST_HOP_FAILURE_SPACING_MS}.
     *
     * The caller invokes this before it acts on the threshold, so the streak
     * handed in may equal {@link #FIRST_HOP_FAILURE_THRESHOLD}; a subclass
     * that only wants to re-warm a tunnel that is still going to be kept
     * alive must filter on the value itself — see
     * {@code PooledTunnelCreatorConfig.shouldPreConnect(int)}.
     *
     * Base config has no pool and nothing to warm; see
     * {@code PooledTunnelCreatorConfig}.
     *
     * @param streak the current streak, from 1 up to and including
     * {@link #FIRST_HOP_FAILURE_THRESHOLD}
     * @since 0.9.71+
     */
    public void firstHopSendFailureStreak(int streak) {
        // nothing to warm without a pool
    }

    /**
     * Has the tunnel failed completely?
     *
     * @return the tunnel failed
     * @since 0.9.53
     */
    public boolean getTunnelFailed() {return _failures.get() > MAX_CONSECUTIVE_TEST_FAILURES;}

    /**
     * The consecutive failure count.
     *
     * @return the tunnel failures
     */
    public int getTunnelFailures() {return _failures.get();}

    /**
     * Reset the consecutive failure counter and mark the tunnel as GOOD.
     * Called only on traffic proof — never by {@link #testSuccessful(int)},
     * which reaches GOOD by being tested rather than by carrying data — so
     * it also restores the recent-traffic test exemption budget alongside
     * the soft streak: a tunnel promoted back from FAILING/FAILED must
     * resume normal service with a clean slate, not inherit the spend
     * that produced the mark.
     *
     * @since 0.9.69+
     */
    public void clearTestFailures() {
        _failures.set(0);
        _testStatus = TunnelTestStatus.GOOD;
        clearSoftFailures();
        clearLastChanceAdmission();
        _recentTestExemptions = 0;
    }

    /**
     * The number of recent-traffic test exemptions used.
     * @return the recent test exemptions
     * @since 0.9.69+
     */
    public int getRecentTestExemptions() {return _recentTestExemptions;}

    /**
     * Increment the recent-traffic test exemption counter.
     * @since 0.9.69+
     */
    public void incrementRecentTestExemptions() {_recentTestExemptions++;}

    /**
     * Mark the tunnel as GOOD, recording the latency of the successful test.
     * Clears any last-chance admission — the test settled, so the early-expiry
     * gate no longer needs the bypass.  Does NOT clear the soft streak: a test
     * exercises the test path, not the data path.
     *
     * Does NOT clear the first-hop send-failure streak.  A passing test is
     * evidence the peer is not dead, but not evidence it is not flaky — a
     * flaky first hop that alternates fail/pass/fail/pass would never reach
     * the failure threshold if each pass reset the streak.  The 60s decay
     * window (FIRST_HOP_FAILURE_WINDOW_MS) already ages the streak naturally,
     * so a genuinely dead peer's old failures expire without making
     * accumulation impossible for a merely flaky one.
     */
    public void testSuccessful(int ms) {
        _failures.set(0);
        _recentTestExemptions = 0;
        _testStatus = TunnelTestStatus.GOOD;
        clearLastChanceAdmission();
        addLatencySample(ms);
    }

    /**
     * Did we reuse this tunnel?
     * @since 0.8.11
     */
    public boolean wasReused() {return _reused;}

    /**
     * Note that we reused this tunnel
     * @since 0.8.11
     */
    public void setReused() {_reused = true;}

    /**
     * Outbound message priority - for outbound tunnels only
     * @return -25 to +25, default 0
     * @since 0.9.4
     */
    public int getPriority() {return _priority;}

    /**
     * Outbound message priority - for outbound tunnels only
     * @param priority -25 to +25, default 0
     * @since 0.9.4
     */
    public void setPriority(int priority) {_priority = priority;}

    /**
     * Key and IV to encrypt the reply sent for the tunnel creation crypto.
     *
     * @throws IllegalArgumentException if iv not 16 bytes
     * @since 0.9.48 moved from HopConfig
     */
    public void setAESReplyKeys(int hop, SessionKey key, byte[] iv) {
        if (iv.length != REPLY_IV_LENGTH) {throw new IllegalArgumentException();}
        _AESReplyKeys[hop] = key;
        _AESReplyIVs[hop] = iv;
    }

    /**
     * Key to encrypt the reply sent for the tunnel creation crypto.
     * Null for short build record.
     *
     * @return key or null
     * @throws IllegalArgumentException if iv not 16 bytes
     * @since 0.9.48 moved from HopConfig
     */
    public SessionKey getAESReplyKey(int hop) {return _AESReplyKeys[hop];}

    /**
     * IV used to encrypt the reply sent for the tunnel creation crypto.
     * Null for short build record.
     *
     * @return 16 bytes or null
     * @since 0.9.48 moved from HopConfig
     */
    public byte[] getAESReplyIV(int hop) {return _AESReplyIVs[hop];}

    /**
     * Checksum for blank record
     * @return the blank hash
     * @since 0.9.48
     */
    public Hash getBlankHash() {return _blankHash;}

    /**
     * Checksum for blank record
     * @since 0.9.48
     */
    public void setBlankHash(Hash h) {_blankHash = h;}

    /**
     * The latency of the last completed test.
     * @param ms latency in milliseconds
     * @since 0.9.68+
     */
    public void setLastLatency(int ms) {
        addLatencySample(ms);
    }

    /**
     * The last recorded test latency.
     *
     * @return latency in milliseconds, or -1 if not available
     * @since 0.9.68+
     */
    public int getLastLatency() {
        return _lastLatency;
    }

    /**
     * Add a latency sample from a test result.
     * @param ms latency in milliseconds
     * @since 0.9.69+
     */
    public synchronized void addLatencySample(int ms) {
        _latencyHistory[_latencyIdx] = ms;
        _latencyIdx = (_latencyIdx + 1) % LATENCY_SAMPLE_SIZE;
        if (_latencyCount < LATENCY_SAMPLE_SIZE) _latencyCount++;
        _lastLatency = ms;
    }

    /**
     * The average latency of the last 3 tests.
     * @return average latency in ms, or -1 if no tests yet
     * @since 0.9.69+
     */
    public int getAverageLatency() {
        int count = _latencyCount;
        if (count == 0) return -1;
        int samples = Math.min(count, LATENCY_SAMPLE_SIZE);
        int sum = 0;
        for (int i = 0; i < samples; i++) sum += _latencyHistory[i];
        return sum / samples;
    }

    /**
     * Whether at least 3 latency samples have been collected.
     *
     * @return true if we have at least 3 latency samples
     * @since 0.9.69+
     */
    public boolean hasEnoughLatencyTests() {
        return _latencyCount >= LATENCY_SAMPLE_SIZE;
    }

    /**
     * Whether the tunnel needs an expedited test due to slow detection.
     *
     * @return true if tunnel needs an expedited test due to slow detection
     * @since 0.9.69+
     */
    public boolean needsExpeditedTest() { return _needsExpeditedTest; }

    /**
     * Clear the expedited test flag after running the expedited test.
     * @since 0.9.69+
     */
    public void clearExpeditedTest() { _needsExpeditedTest = false; }

    /**
     * The tunnel appeared slow, so flag it for an expedited test.
     * @since 0.9.69+
     */
    public void requestExpeditedTest() { _needsExpeditedTest = true; }

    /**
     * The current test status of this tunnel for UI display.
     * @return the current test status (UNTESTED, TESTING, GOOD, FAILING, or FAILED)
     * @since 0.9.68+
     */
    public TunnelTestStatus getTestStatus() {
        return _testStatus;
    }

    /**
     * Called when a test is started.
     * @since 0.9.68+
     */
    public void setTestStarted() {
        _testStatus = TunnelTestStatus.TESTING;
    }

    /**
     * Called when a test fails.
     * Updates the test status based on consecutive failure count.
     * Only mark as FAILED after MAX_CONSECUTIVE_TEST_FAILURES (3) failures -
     * before that, it's just FAILING and still counts as a valid tunnel.
     * @since 0.9.68+
     */
    public void setTestFailed() {
        int failures = _failures.get();
        if (failures >= MAX_CONSECUTIVE_TEST_FAILURES) {
            _testStatus = TunnelTestStatus.FAILED;
        } else if (failures >= 1) {
            _testStatus = TunnelTestStatus.FAILING;
        } else {
            _testStatus = TunnelTestStatus.GOOD;
        }
    }

    /**
     * Mark the tunnel FAILING regardless of the hard-failure counter.
     * {@link #setTestFailed()} derives the status from {@code _failures}, so
     * it reports GOOD for a tunnel retained on soft send timeouts alone —
     * which would leave the tunnel selectable and hold its slot in the pool's
     * good-tunnel accounting while it keeps timing out.
     *
     * @since 0.9.71+
     */
    public void setTestFailing() {
        _testStatus = TunnelTestStatus.FAILING;
    }

    /**
     * Mark tunnel as scheduled for early expiry (pruned from pool).
     * Revokes any last-chance admission — the pool decided to retire it.
     * @since 0.9.69+
     */
    public void setTestTooSlow() {
        _testStatus = TunnelTestStatus.TOO_SLOW;
        clearLastChanceAdmission();
    }

    /**
     * Mark tunnel as scheduled for early expiry due to pool being over budget.
     * Revokes any last-chance admission — the pool decided to retire it.
     * @since 0.9.69+
     */
    public void setTestOverBudget() {
        _testStatus = TunnelTestStatus.OVER_BUDGET;
        clearLastChanceAdmission();
    }

    /**
     * Why a tunnel that would normally be dropped by the early-expiry gate
     * was instead admitted for one last-chance test.  The admission is a
     * snapshot of intent taken when the pool offers the test, so the gate
     * cannot silently revoke it mid-schedule.
     *
     * @since 0.9.71+
     */
    public enum LastChanceReason {
        /** Not admitted for a last-chance test (the default). */
        NONE,
        /** Stale UNTESTED tunnel kept by the pool sweep for one first test. */
        STALE_UNTESTED
    }

    private volatile LastChanceReason _lastChanceReason = LastChanceReason.NONE;
    private volatile long _lastChanceDeadline;
    private volatile long _lastChanceAdmission;

    /**
     * Admit this tunnel for a last-chance test despite its remaining life
     * being inside the early-expiry window.  The deadline is snapshotted
     * from the expiration at admission time so later pool state changes
     * cannot extend it; natural expiry still bounds it.
     *
     * @param now current wall-clock time (ms)
     * @param reason why the tunnel was admitted; {@code null} clears
     * @since 0.9.71+
     */
    public void admitLastChance(long now, LastChanceReason reason) {
        if (reason == null || reason == LastChanceReason.NONE) {
            clearLastChanceAdmission();
            return;
        }
        _lastChanceAdmission = now;
        _lastChanceDeadline = _expiration;
        _lastChanceReason = reason;
    }

    /**
     * Is a last-chance test admission still valid at {@code now}?  Only
     * expiration shortens the window — the snapshot deadline prevents a
     * re-read of a later expiration from extending it, and
     * {@link #clearLastChanceAdmission()} revokes it once the test settles.
     *
     * @param now current wall-clock time (ms)
     * @return true if an unexpired admission is in effect
     * @since 0.9.71+
     */
    public boolean isLastChanceAdmitted(long now) {
        LastChanceReason reason = _lastChanceReason;
        if (reason == null || reason == LastChanceReason.NONE) {return false;}
        return now <= _lastChanceDeadline && now < _expiration;
    }

    /**
     * Why this tunnel was admitted for a last-chance test.
     * @return the reason, never null ({@link LastChanceReason#NONE} when absent)
     * @since 0.9.71+
     */
    public LastChanceReason getLastChanceReason() {
        LastChanceReason reason = _lastChanceReason;
        return reason != null ? reason : LastChanceReason.NONE;
    }

    /**
     * When the last-chance admission expires (deadline snapshot).
     * @return the deadline (ms), or 0 if never admitted
     * @since 0.9.71+
     */
    public long getLastChanceDeadline() {return _lastChanceDeadline;}

    /**
     * When the last-chance admission was granted (ms), or 0 if never.
     * @since 0.9.71+
     */
    public long getLastChanceAdmission() {return _lastChanceAdmission;}

    /**
     * Revoke the last-chance admission (test settled, or no longer needed).
     * @since 0.9.71+
     */
    public void clearLastChanceAdmission() {
        _lastChanceReason = LastChanceReason.NONE;
        _lastChanceDeadline = 0;
        _lastChanceAdmission = 0;
    }

    /**
     * The number of consecutive test failures.
     * @return the count of consecutive failures
     * @since 0.9.68+
     */
    public int getConsecutiveFailures() {
        return _failures.get();
    }

    /**
     * The ECIES reply key and associated data for the given hop.
     * @since 0.9.48
     */
    public void setChaChaReplyKeys(int hop, SessionKey key, byte[] ad) {
        if (_ChaReplyKeys == null) {
            _ChaReplyKeys = new SessionKey[_config.length];
            _ChaReplyADs = new byte[_config.length][];
        }
        _ChaReplyKeys[hop] = key;
        _ChaReplyADs[hop] = ad;
    }

    /**
     * Is it an ECIES hop?
     * @return whether e c
     * @since 0.9.48
     */
    public boolean isEC(int hop) {
        if (_ChaReplyKeys == null) {return false;}
        return _ChaReplyKeys[hop] != null;
    }

    /**
     * The ECIES reply key for the given hop, or null.
     * @return the cha cha reply key
     * @since 0.9.48
     */
    public SessionKey getChaChaReplyKey(int hop) {
        if (_ChaReplyKeys == null) {return null;}
        return _ChaReplyKeys[hop];
    }

    /**
     * The ECIES reply associated data for the given hop, or null.
     * @return the cha cha reply a d
     * @since 0.9.48
     */
    public byte[] getChaChaReplyAD(int hop) {
        if (_ChaReplyADs == null) {return null;}
        return _ChaReplyADs[hop];
    }

    /**
     * ECIES short OBEP record only.
     * @since 0.9.51
     */
    public void setGarlicReplyKeys(OneTimeSession keys) {_garlicReplyKeys = keys;}

    /**
     * ECIES short OBEP record only.
     * @return null for ElGamal or ECIES long record or non-OBEP
     * @since 0.9.51
     */
    public OneTimeSession getGarlicReplyKeys() {return _garlicReplyKeys;}

    /**
     * Human-readable description of the tunnel, its peers, and its state.
     */
    @Override
    public String toString() {
        // H0:1235 -> H1:2345 -> H2:2345
        if (_log == null) {_log = _context.logManager().getLog(TunnelCreatorConfig.class);}
        StringBuilder buf = new StringBuilder(128);
        if (_isInbound) {buf.append("Inbound");}
        else {buf.append("Outbound");}
        if (_destination == null) {buf.append(" Exploratory tunnel");}
        else {
            buf.append(" Client tunnel [");
            if (_destinationNickname != null) {
                buf.append(_destinationNickname).append(" / ");
            }
            buf.append(_destination.toBase32().substring(0, 8)).append("]");
        }
        int fails = _failures.get();
        if (fails > 1) {buf.append(" (").append(fails).append(" consecutive failures)");}
        if (_log.shouldInfo()) {
            buf.append("\n* Gateway: ");
            for (int i = 0; i < _peers.length; i++) {
                buf.append("[" + _peers[i].toBase64().substring(0,6) + "]");
                buf.append(isEC(i) ? " EC:" : " ElG:");
                long id = _config[i].getReceiveTunnelId();
                if (id != 0) {
                    // don't show for "me" at OBGW or IBEP
                    if (!_isInbound || i != _peers.length - 1) {buf.append(isEC(i) ? " EC:" : " ElG:");}
                    else {buf.append(' ');}
                    buf.append(id);
                } else {buf.append(" local");}
                id = _config[i].getSendTunnelId();
                if (id != 0) {buf.append('.').append(id);}
                else if (_isInbound || i == 0) {buf.append(".local");}
                if (i + 1 < _peers.length) {buf.append(" -> ");}
            }
            if (_lastTransferredTime > 0)
                buf.append("\n* Last traffic: ").append(DataHelper.formatTime(_lastTransferredTime));
            buf.append("\n* Expires: ").append(DataHelper.formatTime(_expiration));
            if (_replyMessageId > 0) {buf.append("; [ReplyMsgID ").append(_replyMessageId).append("]");}
            int msgs = _messagesProcessed.get();
            if (msgs > 0) {
                buf.append(" with ").append(msgs).append(" messages (")
                   .append(_verifiedBytesTransferred.sum()).append(" bytes)");
            }
        }
        return buf.toString();
    }

    /**
     * @since 0.9.51
     */
    public String toStringFull() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append(toString());
        for (int i = 0; i < _peers.length; i++) {
            if (i == 0) {buf.append("\n* Gateway ");}
            else if (i == _peers.length - 1) {buf.append("\n* Endpoint ");}
            else {buf.append("\n* Hop ").append(i);}
            buf.append(": ").append(_config[i]);
        }
        if (_garlicReplyKeys != null) {
            buf.append("\n* Garlic Reply Key: ").append(_garlicReplyKeys.key).append("\n* Tag: ").append(_garlicReplyKeys.rtag);
        }
        return buf.toString();
    }

}
