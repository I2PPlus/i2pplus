/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.i2ptunnel.util.LimitOutputStream.DoneCallback;
import net.i2p.util.ByteCache;
import net.i2p.util.Clock;
import net.i2p.util.I2PAppThread;
import net.i2p.util.InternalSocket;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Thread that forwards traffic between an I2PSocket and a TCP Socket.
 * <p>
 * I2PTunnelRunner implements the core bidirectional data forwarding between
 * I2P and TCP network connections. Operates two StreamForwarders: the
 * I2P to TCP direction runs inline and the TCP to I2P direction runs
 * concurrently via a shared executor pool.
 * </p>
 * <p>
 * <b>Connection Flow:</b>
 * <ol>
 *   <li>Runner is created with connected I2PSocket and TCP Socket</li>
 *   <li>Initial data may be sent immediately via initialI2PData/initialSocketData</li>
 *   <li>Two StreamForwarders run for bidirectional streaming; TCP to I2P via executor pool</li>
 *   <li>Runner monitors both connections for errors or disconnection</li>
 *   <li>On completion or failure, callbacks may be invoked and sockets are closed</li>
 * </ol>
 * <p>
 * <b>Keep-Alive Support:</b> When keep-alive is enabled for either connection,
 * the runner may skip spawning one direction of forwarding if no data is expected.
 * This optimization is used for simple GET requests that don't require responses.
 * </p>
 * <p>
 * <b>Thread Safety:</b> This class uses locks (slock) to coordinate socket access
 * and prevent concurrent writes from multiple threads. The finishLock ensures
 * thread-safe state transitions.
 * </p>
 *
 * @see #toI2P
 * @see #fromI2P
 * @see I2PTunnelServer
 */
public class I2PTunnelRunner extends I2PAppThread implements I2PSocket.SocketErrorListener, DoneCallback {
    protected final Log _log;
    private static final AtomicLong __runnerId = new AtomicLong();
    private final long _runnerId;
    /**
     * Max bytes streamed in a packet - smaller ones might be filled up to this size.
     * Larger ones are not split (at least not on Sun's impl of BufferedOutputStream),
     * but that is the streaming api's job...
     */
    static final int MAX_PACKET_SIZE = 4 * 1024;
    /** network buffer size for buffered streams */
    static final int NETWORK_BUFFER_SIZE = MAX_PACKET_SIZE * 8;
    /**
     *  Max number of consecutive <em>no-progress</em> cycles (stalls) the outer
     *  empty-response / body-resume reconnect loop may invoke the callback for.
     *  The callback's own for-loop handles connect-failure backoff, but when the
     *  destination is reachable yet always sends zero bytes, each cycle succeeds
     *  on attempt 1 and the outer loop would spin forever.  This is the
     *  baseline for entities under {@link #RETRY_RAMP_UNIT_BYTES};
     *  {@link #stallCycleLimit(long)} ramps the cap by one cycle per 1MB of
     *  Content-Length so a large file can outlive proportionally more
     *  tunnel-pool churn.  A cycle that makes any forward progress on
     *  the entity body resets this budget (see {@link #MAX_RESUME_CYCLES}).
     *
     *  @since 0.9.71+
     */
    static final int MAX_EMPTY_RECONNECT_CYCLES = 4;
    /**
     *  Absolute safety cap on total body-resume cycles regardless of progress.
     *  Progress resets the stall budget ({@link #MAX_EMPTY_RECONNECT_CYCLES})
     *  so a slowly-advancing transfer is never abandoned mid-body for lack of
     *  progress, but a pathological trickle (one byte per cycle forever) must
     *  still terminate.  Well above any legitimate need: a healthy resume
     *  completes in 1–2 cycles.
     *
     *  @since 0.9.71+
     */
    static final int MAX_RESUME_CYCLES = 32;
    /**
     *  Entity-size unit for retry-budget ramping: size-scaled limits gain one
     *  cycle (or connect attempt) per this many bytes of Content-Length.
     *  Entities smaller than this keep the baseline caps untouched.
     *
     *  @since 0.9.71+
     */
    static final long RETRY_RAMP_UNIT_BYTES = 1024 * 1024;
    /**
     *  Multiplier applied to a baseline budget to cap the size-scaled ramps.
     *  The historical caps were 8x baseline under a 4MB ramp unit, i.e. 7x
     *  baseline of headroom.  The 1MB unit is 4x finer, so preserving the same
     *  saturation sizes (112MB for the stall budget, 896MB for the total
     *  budget) needs 4 * 7 = 28x baseline of headroom, 29x baseline in total.
     *
     *  @since 0.9.71+
     */
    static final int SCALED_CAP_MULTIPLIER = 29;
    /**
     *  Hard cap on the size-scaled stall budget
     *  ({@link #SCALED_CAP_MULTIPLIER} times baseline), bounding
     *  worst-case browser wait when the destination is genuinely stuck.
     *
     *  @since 0.9.71+
     */
    static final int MAX_SCALED_STALL_CYCLES = MAX_EMPTY_RECONNECT_CYCLES * SCALED_CAP_MULTIPLIER;
    /**
     *  Hard cap on the size-scaled total cycle budget
     *  ({@link #SCALED_CAP_MULTIPLIER} times baseline).
     *
     *  @since 0.9.71+
     */
    static final int MAX_SCALED_RESUME_CYCLES = MAX_RESUME_CYCLES * SCALED_CAP_MULTIPLIER;

    /**
     *  Base delay before empty-retry cycle N+1 (ms). Combined with exponential
     *  growth this keeps successful-connect-but-empty retries from spinning
     *  back-to-back and stampeding the remote SYN-burst gate.
     *  @since 0.9.71+
     */
    static final long EMPTY_CYCLE_BASE_DELAY_MS = 150;
    /** Cap for empty-retry inter-cycle delay (ms). @since 0.9.71+ */
    static final long EMPTY_CYCLE_MAX_DELAY_MS = 1200;
    /** Per-dest race token bucket capacity (SYNs worth of budget). @since 0.9.71+ */
    static final int RACE_TOKENS_MAX = 6;
    /** Token refill rate: tokens per second, keyed per remote dest. @since 0.9.71+ */
    static final int RACE_REFILL_PER_SEC = 6;
    /** Budget cost of one dual-race open (two SYNs). @since 0.9.71+ */
    static final int RACE_COST_TOKENS = 2;
    /** Budget cost of one single-socket open on the empty path. @since 0.9.71+ */
    static final int SINGLE_COST_TOKENS = 1;
    /** Stagger between the two race connects (ms) to avoid same-tick double SYN. @since 0.9.71+ */
    static final long RACE_STAGGER_MS = 100;
    /** Overall wait for a race winner's first response byte (ms). @since 0.9.71+ */
    static final long EMPTY_RACE_TIMEOUT_MS = 30 * 1000;
    /**
     *  No-bytes read timeout (ms) once a response body has started streaming.
     *  Armed after the first body byte so a black-holed tunnel fails over to
     *  Range resume instead of pinning the runner for the full browser window.
     *  @since 0.9.71+
     */
    static final long BODY_STALL_READ_TIMEOUT_MS = 30 * 1000;
    /**
     *  Deadline for the very first response byte after a request is written
     *  upstream (ms). Without it, a peer that accepts the connection but never
     *  sends a byte pins the runner forever: the body stall timeout is only
     *  armed after the first byte and streaming inactivity is disabled for
     *  HTTP tunnels, so nothing else bounds the wait. The anchor slides forward
     *  on every request-side write, so a slow POST upload never trips it; it is
     *  re-armed on each empty-response reconnect. 120s matches streaming's
     *  default inactivity window and stays well inside browser patience (~300s)
     *  across the empty-retry budget. Superseded permanently by the first
     *  response byte, which hands off to {@link #BODY_STALL_READ_TIMEOUT_MS}.
     *  @since 0.9.71+
     */
    static final long INITIAL_RESPONSE_TIMEOUT_MS = 120 * 1000;
    /** Watchdog poll interval (ms); SimpleTimer2 rejects periods under 5s. @since 0.9.71+ */
    static final long INITIAL_WATCHDOG_POLL_MS = 5 * 1000;

    /**
     *  Delay before empty-retry cycle {@code cycle} (1-based, after the cycle
     *  counter has been incremented). Cycle 1 races immediately (the prior
     *  attempt already failed); later cycles back off exponentially so a
     *  reachable-but-empty dest cannot spin at full rate.
     *
     *  @param cycle 1-based empty-retry cycle number
     *  @return delay in ms before invoking the reconnect callback
     *  @since 0.9.71+
     */
    static long emptyCycleDelayMs(int cycle) {
        if (cycle <= 1) {return 0;}
        int shift = cycle - 2;
        if (shift > 3) {shift = 3;}
        return Math.min(EMPTY_CYCLE_MAX_DELAY_MS, EMPTY_CYCLE_BASE_DELAY_MS << shift);
    }

    /**
     *  Effective first-response deadline (ms), read per watchdog tick.
     *  Non-positive disables the watchdog entirely. Test hook; do not
     *  modify in production.
     *  @since 0.9.71+
     */
    static volatile long initialResponseTimeoutMs = INITIAL_RESPONSE_TIMEOUT_MS;

    /**
     *  Whether the first-response deadline has elapsed for the current
     *  request attempt (pure predicate).
     *
     *  <p>The deadline only ever arms before the first response byte: once
     *  any upstream byte has arrived the watchdog retires permanently and
     *  {@link #BODY_STALL_READ_TIMEOUT_MS} takes over. An unset anchor means
     *  no request has been written (server-side runners, or a runner that
     *  never started), so nothing can be late.
     *
     *  @param lastRequestWriteMs epoch-ms of the most recent request-side
     *                            write upstream, 0 if none
     *  @param firstByteMs epoch-ms of the first response byte, 0 if none yet
     *  @param nowMs current epoch-ms
     *  @param timeoutMs deadline window in ms; non-positive disables it
     *  @return true if the deadline expired with no response byte
     *  @since 0.9.71+
     */
    static boolean initialResponseExpired(long lastRequestWriteMs, long firstByteMs,
                                          long nowMs, long timeoutMs) {
        if (timeoutMs <= 0 || firstByteMs > 0 || lastRequestWriteMs <= 0) {return false;}
        return nowMs - lastRequestWriteMs >= timeoutMs;
    }

    /**
     *  Refill a per-dest race token bucket in place. State is
     *  {@code {tokensMilli, lastRefillMs}}; tokens are held in milli-units so
     *  a whole-second refill can be applied from a millisecond delta without
     *  truncation to zero.
     *
     *  @param state non-null {@code long[2]} bucket, updated in place
     *  @param now current epoch-ms
     *  @return the same state array
     *  @since 0.9.71+
     */
    static long[] refillRaceBudget(long[] state, long now) {
        if (state == null) {return null;}
        if (now > state[1]) {
            long tokens = state[0] + (now - state[1]) * RACE_REFILL_PER_SEC;
            state[0] = Math.min(RACE_TOKENS_MAX * 1000L, tokens);
            state[1] = now;
        }
        return state;
    }

    /**
     *  Try to consume {@code costTokens} from the race budget, refilling first.
     *  Insufficient balance leaves the bucket unchanged and returns false.
     *
     *  @param state non-null {@code long[2]} bucket, updated in place when consumed
     *  @param now current epoch-ms
     *  @param costTokens whole tokens to consume (1 = single open, 2 = dual-race)
     *  @return true if the budget allowed the open
     *  @since 0.9.71+
     */
    static boolean tryConsumeRaceBudget(long[] state, long now, int costTokens) {
        if (state == null || costTokens <= 0) {return false;}
        refillRaceBudget(state, now);
        long cost = costTokens * 1000L;
        if (state[0] < cost) {return false;}
        state[0] -= cost;
        return true;
    }

    /**
     *  Size-scaled cap on consecutive no-progress resume cycles.
     *
     *  <p>A long transfer lives through proportionally more tunnel-pool churn
     *  than a small file, so the stall budget ramps by one cycle per
     *  {@link #RETRY_RAMP_UNIT_BYTES} of the entity's verified Content-Length.
     *  Unknown or sub-1MB entities keep the
     *  {@link #MAX_EMPTY_RECONNECT_CYCLES} baseline, and the result is capped
     *  at {@link #MAX_SCALED_STALL_CYCLES} so a stuck destination still fails
     *  within a bounded browser wait.
     *
     *  @param contentLength entity Content-Length in bytes, or -1 if unknown
     *  @return the stall-cycle cap for this entity; never below the baseline
     *  @since 0.9.71+
     */
    static int stallCycleLimit(long contentLength) {
        long ramp = contentLength > 0 ? contentLength / RETRY_RAMP_UNIT_BYTES : 0;
        return (int) Math.min(MAX_EMPTY_RECONNECT_CYCLES + ramp, MAX_SCALED_STALL_CYCLES);
    }

    /**
     *  Size-scaled cap on total body-resume cycles regardless of progress.
     *
     *  <p>Progress resets the stall counter, but every resume attempt counts
     *  against this absolute cap, so it must grow with the entity the same way
     *  {@link #stallCycleLimit(long)} does: one cycle per
     *  {@link #RETRY_RAMP_UNIT_BYTES} of verified Content-Length, baseline
     *  below 1MB, capped at {@link #MAX_SCALED_RESUME_CYCLES} so a pathological
     *  trickle still terminates.
     *
     *  @param contentLength entity Content-Length in bytes, or -1 if unknown
     *  @return the total-cycle cap for this entity; never below the baseline
     *  @since 0.9.71+
     */
    static int totalCycleLimit(long contentLength) {
        long ramp = contentLength > 0 ? contentLength / RETRY_RAMP_UNIT_BYTES : 0;
        return (int) Math.min(MAX_RESUME_CYCLES + ramp, MAX_SCALED_RESUME_CYCLES);
    }

    /**
     *  Progress-based budget for body-resume cycles. Pure decision state so
     *  the {@link #resumeIncompleteBody} loop stays thin and tests can pin
     *  stall-reset and absolute-cap behaviour without a live transfer.
     *
     *  <p>Only consecutive no-progress cycles count against the size-scaled
     *  stall cap ({@link #stallCycleLimit(long)}; baseline
     *  {@link #MAX_EMPTY_RECONNECT_CYCLES}); any forward progress resets that
     *  counter. {@link #totalCycleLimit(long)} bounds total attempts
     *  regardless of progress so a pathological trickle still terminates.
     *
     *  @since 0.9.71+
     */
    static final class ResumeBudget {
        private int stallCycles;
        private int totalCycles;
        private long lastBody = -1;

        /**
         *  Consume one resume cycle for the given body progress.
         *
         *  @param bodyReceived entity-body bytes delivered so far
         *  @param contentLength entity Content-Length in bytes, or -1 if
         *         unknown; scales both caps
         *  @return true if another resume attempt may proceed
         */
        boolean tryConsume(long bodyReceived, long contentLength) {
            boolean progressed = lastBody >= 0 && bodyReceived > lastBody;
            if (progressed) {stallCycles = 0;}
            lastBody = bodyReceived;
            if (stallCycles >= stallCycleLimit(contentLength)) {return false;}
            if (totalCycles >= totalCycleLimit(contentLength)) {return false;}
            totalCycles++;
            stallCycles++;
            return true;
        }

        /** Stall cycles consumed so far (including the in-flight one). @since 0.9.71+ */
        int getStallCycles() { return stallCycles; }
        /** Total resume attempts consumed so far. @since 0.9.71+ */
        int getTotalCycles() { return totalCycles; }

        /**
         *  Refund the last consume's stall charge when a resume attempt failed
         *  with a transient status (408/502/503/504) rather than a real stall —
         *  a gateway timeout must not eat the empty/stall budget, or the next
         *  non-Range retry would be blocked for an unrelated reason.
         *
         *  <p>Only the stall charge is refunded. The total count stays
         *  consumed so {@link #totalCycleLimit(long)} remains an absolute cap
         *  over ALL attempts; refunding it would let a persistently
         *  transient-failing upstream reconnect forever.
         *
         *  @since 0.9.71+
         */
        void refundLast() {
            if (stallCycles > 0) {stallCycles--;}
        }
    }

    /** Plain TCP socket (local or remote endpoint). */
    private final Socket s;
    /** I2P socket (the tunnel connection). Non-final so an "empty response"
     *  reconnect may replace it with a fresh connection while the local browser
     *  socket stays open. Only ever reassigned within {@link #run()} when a
     *  {@link ReconnectCallback} is installed and yields a new connection.
     *  Volatile so the initial-response watchdog on the timer thread sees the
     *  swap when it fires. */
    private volatile I2PSocket i2ps;
    /** Synchronization lock for socket access. */
    private final Object slock;
    private final Object finishLock = new Object();
    private volatile boolean finished;
    /** Data to send over I2P before normal forwarding starts. */
    private final byte[] initialI2PData;
    /** Data to send over TCP before normal forwarding starts. */
    private final byte[] initialSocketData;
    /** when runner started up */
    private final long startedOn;
    private final List<I2PSocket> sockList;
    /** if we die before receiving any data, run this job */
    private final Runnable onTimeout;
    private final FailCallback _onFail;
    private SuccessCallback _onSuccess;
    /** Optional reconnect callback for the empty-response retry; null = never retry. */
    private ReconnectCallback _reconnectCallback;
    private volatile long totalSent;
    private volatile long totalReceived;
    /** Prevent the no-data failure callback from firing more than once across
     *  the synchronous completion block and the exception/finally paths. */
    private boolean _noDataHandled;
    /** Sliding anchor: epoch-ms of the most recent request-side write upstream.
     *  Refreshed by the toI2P forwarder so a slow upload never trips the
     *  initial-response deadline; reset on each empty-response re-send. */
    private volatile long _lastRequestWriteMs;
    /** Epoch-ms of the first response byte, 0 until one arrives; retires the
     *  initial-response watchdog permanently. */
    private volatile long _firstByteMs;
    /** One-shot latch per request attempt so a fired deadline does not warn
     *  and close again every watchdog tick; cleared when the anchor slides or
     *  a re-send starts a new attempt. */
    private volatile boolean _initialDeadlineFired;
    /** Watchdog lifecycle: set on schedule, cleared on cancel, read by the
     *  timer thread so a tick racing run()'s finally cannot re-arm. */
    private volatile boolean _initialWatchdogOn;
    private volatile SimpleTimer2.TimedEvent _initialWatchdog;
    /** Keep I2P socket alive after data transfer */
    protected volatile boolean _keepAliveI2P;
    /** Keep local socket alive after data transfer */
    protected volatile boolean _keepAliveSocket;
    /** Executor for submitting tasks; null = fallback to new Thread */
    private volatile Executor _runnerExecutor;
    private volatile StreamForwarder toI2P;
    private volatile StreamForwarder fromI2P;

    /**
     *  For use in new constructor
     *
     */
    public interface FailCallback {
        /**
         *  @param e may be null
         */
        public void onFail(Exception e);
    }

    /**
     * Callback interface for successful tunnel operation completion.
     *
     */
    public interface SuccessCallback {
        /** Called on successful completion */
        public void onSuccess();
    }

    /**
     * Callback for reconnecting via a second route when the first attempt
     * completes with zero upstream bytes, or dies mid-body with an incomplete
     * Content-Length response (Range resume).
     *
     * <p>Used by the HTTP client proxy to transparently retry an idempotent
     * GET/HEAD against a fresh I2P connection (a new tunnel) without tearing
     * down the browser socket (a silent zero-byte close becomes
     * {@code NS_ERROR_NET_EMPTY_RESPONSE} for the browser; a mid-body death
     * would truncate a download). The runner keeps the local socket open across
     * attempts; the callback supplies each new connection (or null to stop).
     *
     * @since 0.9.62
     */
    public interface ReconnectCallback {
        /**
         * @param cause the cause of the empty or mid-body completion, or null
         * @return a freshly connected I2P socket to retry on, or null to give up
         */
        public I2PSocket reconnect(Exception cause);

        /**
         *  Open one or two sockets for an empty-response retry. Two sockets are
         *  raced by the runner (first response byte wins) so a dest that black-holes
         *  one tunnel path still recovers on the alternate. The default opens a
         *  single socket via {@link #reconnect(Exception)} so existing callbacks
         *  keep working unchanged.
         *
         *  @param cause the cause of the empty completion, or null
         *  @return 1–2 freshly connected sockets (index 0 is primary), or null to give up
         *  @since 0.9.71+
         */
        public default I2PSocket[] reconnectPair(Exception cause) {
            I2PSocket s = reconnect(cause);
            return s == null ? null : new I2PSocket[]{s};
        }

        /**
         *  Release any per-dest permit held for a dual-race's secondary socket.
         *  Called by the runner as soon as the race settles (winner chosen or
         *  both legs failed) so the extra permit is not held for the download.
         *  Default no-op for single-socket callbacks.
         *
         *  @since 0.9.71+
         */
        public default void releaseRacePermit() { /* no extra permit */ }
    }

    /**
     *  Starts itself
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @deprecated use FailCallback constructor
     */
    @Deprecated
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData, List<I2PSocket> sockList) {
        this(s, i2ps, slock, initialI2PData, null, sockList, null, null, false, false, true);
    }

    /**
     *  Starts itself
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @deprecated use FailCallback constructor
     */
    @Deprecated
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           byte[] initialSocketData, List<I2PSocket> sockList) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, null, null, false, false, true);
    }

    /**
     *  Starts itself
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onTimeout May be null. If non-null and no data (except initial data) was received,
     *                   it will be run before closing s.
     *  @deprecated use FailCallback constructor
     */
    @Deprecated
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           List<I2PSocket> sockList, Runnable onTimeout) {
        this(s, i2ps, slock, initialI2PData, null, sockList, onTimeout, null, false, false, true);
    }

    /**
     *  Starts itself
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onTimeout May be null. If non-null and no data (except initial data) was received,
     *                   it will be run before closing s.
     *  @deprecated use FailCallback constructor
     */
    @Deprecated
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           byte[] initialSocketData, List<I2PSocket> sockList, Runnable onTimeout) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, onTimeout, null, false, false, true);
    }

    /**
     *  Recommended new constructor. Does NOT start itself. Caller must call start().
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onFail May be null. If non-null and no data (except initial data) was received,
     *                it will be run before closing s.
     */
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           byte[] initialSocketData, List<I2PSocket> sockList, FailCallback onFail) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, onFail, false);
    }

    /**
     *  With keepAlive args. Does NOT start itself. Caller must call start().
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onFail May be null. If non-null and no data (except initial data) was received,
     *                it will be run before closing s.
     *  @param keepAliveI2P Do not close the I2P socket when done.
     *  @param keepAliveSocket Do not close the local socket when done.
     *                         For client side only; must be false for server side.
     *                         NO data will be forwarded from the socket to the i2psocket other than
     *                         initialI2PData if this is true.
     *
     */
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           byte[] initialSocketData, List<I2PSocket> sockList, FailCallback onFail,
                           boolean keepAliveI2P, boolean keepAliveSocket) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, null, onFail, keepAliveI2P, keepAliveSocket, false);
    }

    /**
     *  Base constructor
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onTimeout May be null. If non-null and no data (except initial data) was received,
     *                   it will be run before closing s.
     *  @param onFail Trumps onTimeout
     *  @param shouldStart should thread be started in constructor (bad, false recommended)
     */
    private I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                            byte[] initialSocketData, List<I2PSocket> sockList,
                            FailCallback onFail, boolean shouldStart) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, null, onFail, false, false, shouldStart);
    }

    /**
     *  Base constructor with keepAlive args
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onTimeout May be null. If non-null and no data (except initial data) was received,
     *                   it will be run before closing s.
     *  @param onFail Trumps onTimeout
     *  @param shouldStart should thread be started in constructor (bad, false recommended)
     *  @param keepAliveI2P Do not close the I2P socket when done.
     *  @param keepAliveSocket Do not close the local socket when done.
     *                         For client side only; must be false for server side.
     *                         NO data will be forwarded from the socket to the i2psocket other than
     *                         initialI2PData if this is true.
     *
     */
    private I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                            byte[] initialSocketData, List<I2PSocket> sockList, Runnable onTimeout,
                            FailCallback onFail,
                            boolean keepAliveI2P, boolean keepAliveSocket,
                            boolean shouldStart) {
        this.sockList = sockList;
        this.s = s;
        this.i2ps = i2ps;
        this.slock = slock;
        this.initialI2PData = initialI2PData;
        this.initialSocketData = initialSocketData;
        this.onTimeout = onTimeout;
        _onFail = onFail;
        startedOn = Clock.getInstance().now();
        _log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
        _keepAliveI2P = keepAliveI2P;
        _keepAliveSocket = keepAliveSocket;
        if (_log.shouldLog(Log.INFO)) {_log.info("I2PTunnelRunner started");}
        _runnerId = __runnerId.incrementAndGet();
        if (shouldStart) {
            setName("TunRunner." + _runnerId);
            start();
        }
    }

    /**
     *  Returns the timestamp when this runner started.
     * <p>
     * This value is set at construction time and represents when the runner
     * was created, not when it started executing.
     * </p>
     *
     * @return the timestamp in milliseconds since epoch when this runner was created
     */
    public long getStartedOn() {return startedOn;}

    /**
     *  Sets a callback to be invoked on successful data transfer.
     * <p>
     * The callback is invoked after the first byte of data is received from
     * the destination, not when the entire transfer completes. Only one of
     * SuccessCallback, onTimeout, or onFail will be called.
     * </p>
     *
     * @param sc the callback to invoke on success, may be null
     *
     */
    public void setSuccessCallback(SuccessCallback sc) {
        _onSuccess = sc;
    }

    /**
     *  Set the reconnect callback used for the empty-response retry.
     *
     *  <p>When set, and this is a no-request-body transfer (GET/HEAD, i.e. the
     *  {@code toI2P} forwarder was never started) that completes with zero
     *  upstream bytes, {@link #run()} will call the callback to obtain a fresh
     *  I2P socket and re-drive the request on it, keeping the local browser
     *  socket open. The same callback is used for mid-body Range resume when a
     *  partial Content-Length body was already delivered. Only the
     *  {@link #onNoDataFailure(Exception)} path triggers an <em>empty</em>
     *  reconnect; a mid-body death with {@code totalReceived > 0} triggers
     *  {@link #resumeIncompleteBody(OutputStream)} instead. A genuine non-empty
     *  completion (full body, or no Content-Length to resume) never reconnects.
     *
     *  @param rc the callback, or null to disable reconnects
     *  @since 0.9.62
     */
    public void setReconnectCallback(ReconnectCallback rc) {
        _reconnectCallback = rc;
    }

    /**
     *  Set the executor for submitting forwarder tasks.
     *  When null (default), forwarders use a fallback thread.
     */
    public void setExecutor(Executor exec) { _runnerExecutor = exec; }

    /**
     *  The tunnel's runner executor (set by {@link #setExecutor}), or null
     *  when the runner will spawn a dedicated thread instead.
     *
     *  @return the executor, or null
     *  @since 0.9.71+
     */
    Executor getRunnerExecutor() { return _runnerExecutor; }

    /**
     *  Gets the TCP socket input stream.
     * <p>
     * This method is protected to allow subclasses to override socket access
     * for testing or special handling (e.g., SSL unwrapping).
     * </p>
     *
     * @return the TCP socket's input stream
     * @throws IOException if the socket is closed
     */
    protected InputStream getSocketIn() throws IOException { return s.getInputStream(); }

    /**
     *  Gets the TCP socket output stream.
     *
     * @return the TCP socket's output stream
     * @throws IOException if the socket is closed
     */
    protected OutputStream getSocketOut() throws IOException { return s.getOutputStream(); }

    /**
     *  Checks if the I2P socket should be kept open after data transfer.
     * <p>
     * On the client side, this is true only if the browser and server both
     * support HTTP keep-alive. On the server side, it's true only if the
     * client supports keep-alive.
     * </p>
     *
     * @return true if the I2P socket should remain open for reuse
     *
     */
    boolean getKeepAliveI2P() {return _keepAliveI2P;}

    /**
     *  Checks if the local socket should be kept open after data transfer.
     * <p>
     * Usually true for client-side connections (browser to proxy).
     * Always false for server-side connections (I2P to local service).
     * </p>
     *
     * @return true if the local socket should remain open for reuse
     *
     */
    boolean getKeepAliveSocket() {return _keepAliveSocket;}

    /**
     * The DoneCallback for the I2P socket.
     *
     *
     */
    public void streamDone() {
        if (_keepAliveSocket && fromI2P != null) {
            // we are client-side
            // tell the from-I2P runner
            if (_log.shouldInfo()) {
                _log.info("I2P client stream closed by peer -> Total received: " + totalReceived + " bytes");
            }
            fromI2P.done = true;
        } else if (_keepAliveI2P && toI2P != null) {
            // we are server-side - tell the to-I2P runner
            if (_log.shouldInfo()) {
                _log.info("I2P server stream closed by peer -> Total sent: " + totalSent + " bytes");
            }
            toI2P.done = true;
        } else {
            if (_log.shouldInfo()) {_log.info("I2P stream closed prematurely");}
        }
    }

    /**
     *  Invoke the no-data failure callback when a transfer completes (or aborts)
     *  without delivering any bytes from the I2P peer.
     *
     *  <p>This is the single choke point behind the "empty response" class of bugs:
     *  without it, a connection that is established to the proxy but never yields an
     *  upstream byte (server unresponsive, reset before first data, or the executor
     *  rejecting the forwarder) would end by closing the local socket with nothing
     *  written, which the browser reports as {@code NS_ERROR_NET_EMPTY_RESPONSE}.
     *  The HTTP client runner wires its {@link FailCallback} to a handler that
     *  writes a well-formed HTTP error page to the browser socket before closing;
     *  this is what turns a silent empty close into a surfaced 5xx. The base
     *  implementation simply dispatches to the configured {@link FailCallback} or
     *  {@link #onTimeout}.
     *
     *  <p>Safe to call from any completion or exception path; {@link #_noDataHandled}
     *  guarantees the callback runs at most once even when several paths race.
     *  Run even when {@code totalSent > 0} (post body) — the absence of a response
     *  is still a failure. Never run when any upstream bytes were received.
     *
     *  @since 0.9.62
     */
    protected void onNoDataFailure() { onNoDataFailure(null); }

    /**
     *  Invoke the no-data failure callback with an optional cause.
     *
     *  @param e the failure cause, or {@code null} for a clean empty transfer
     *  @since 0.9.62
     */
    protected void onNoDataFailure(Exception e) {
        if (!shouldFireNoDataFailure(totalReceived, _noDataHandled)) {return;}
        synchronized (this) {
            if (_noDataHandled) {return;}
            _noDataHandled = true;
        }
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("No data received from peer" + (e != null ? " (" + e + ")" : "") +
                       " -> invoking failure callback, totalSent=" + totalSent);
        }
        if (_onFail != null) {
            _onFail.onFail(e);
        } else if (onTimeout != null) {
            onTimeout.run();
        }
    }

    /**
     *  Whether a transfer with the given upstream byte count and handling state
     *  should trigger the no-data failure callback.
     *
     *  <p>This is the decision behind the "empty response" fix: the failure callback
     *  must fire iff no upstream bytes were received (an empty transfer is still a
     *  failure even when a POST body was sent upstream) and the callback has not
     *  already fired for this transfer. Extracted as a pure static predicate so the
     *  empty-response contract is unit-testable without a router or live socket.
     *
     *  @param totalReceived upstream bytes received from the I2P peer
     *  @param handled whether the no-data failure has already been signalled
     *  @return true if the failure callback should fire
     *  @since 0.9.62
     */
    static boolean shouldFireNoDataFailure(long totalReceived, boolean handled) {
        return totalReceived <= 0 && !handled;
    }

    /**
     *  Whether an empty-upstream transfer should be retried on a fresh I2P connection.
     *
     *  <p>The HTTP client proxy re-sends an idempotent (GET/HEAD) request against a fresh
     *  I2P connection after a transfer that produced no upstream bytes, instead of closing
     *  the browser socket with nothing (which the browser reports as
     *  {@code NS_ERROR_NET_EMPTY_RESPONSE}). Reconnecting is only justified when the
     *  transfer was genuinely empty: once the peer has reported a complete HTTP response
     *  (even a definitive error such as a 5xx), {@code totalReceived} is positive and a
     *  reconnect is both pointless and harmful - it re-drives a request the outproxy already
     *  answered, contributing to the very congestion behind slow/failed streams. Guarding
     *  on <em>upstream bytes actually received</em> (rather than bytes written to the
     *  browser) is essential: a response whose browser write threw (e.g. {@code Pipe
     *  closed}) still counts as received, so it must terminate, not retry.
     *
     *  @param totalReceived upstream bytes received from the I2P peer since reconnect reset
     *  @param hasReconnectCallback whether a reconnect callback is installed
     *  @param retryableRequest whether the buffered request is an idempotent, body-less GET/HEAD
     *  @return true if the transfer should be retried on a fresh connection
     *  @since 0.9.62
     */
    static boolean shouldReconnectEmptyResponse(long totalReceived, boolean hasReconnectCallback,
                                                boolean retryableRequest) {
        return totalReceived <= 0 && hasReconnectCallback && retryableRequest;
    }

    /**
     *  Whether an incomplete Content-Length body should be resumed via HTTP Range
     *  on a fresh I2P connection (new tunnel).
     *
     *  <p>Used when the browser has already been sent response headers with a
     *  definite Content-Length, but the upstream stream died (tunnel failure,
     *  write timeout, stall) after only part of the entity arrived. I2P delays
     *  are expected; the download must not be abandoned. Reconnecting with
     *  {@code Range: bytes=N-} fetches only the remainder so the browser-visible
     *  byte stream stays contiguous. Only GET/HEAD qualify (idempotent, no
     *  request body). Resume is refused when the body cannot be spliced safely
     *  (unknown length, chunked, transparent gzip decode, or headers not yet
     *  emitted — the latter is the empty-response case handled instead).
     *
     *  @param bodyReceived entity-body bytes already delivered to the browser
     *  @param dataExpected original response Content-Length, or -1 if unknown
     *  @param hasReconnectCallback whether a reconnect callback is installed
     *  @param retryableRequest whether the buffered request is an idempotent GET/HEAD
     *  @param canRangeResume whether the response stream can safely splice (see
     *         {@code HTTPResponseOutputStream.canRangeResume()})
     *  @return true if the transfer should resume on a fresh connection
     *  @since 0.9.71+
     */
    static boolean shouldResumeIncompleteBody(long bodyReceived, long dataExpected,
                                              boolean hasReconnectCallback,
                                              boolean retryableRequest,
                                              boolean canRangeResume) {
        return canRangeResume
               && dataExpected > 0
               && bodyReceived >= 0
               && bodyReceived < dataExpected
               && hasReconnectCallback
               && retryableRequest;
    }

    /**
     *  Whether a non-Range full re-request is safe after a transient status
     *  (408/502/503/504) aborted a Range resume attempt.
     *
     *  <p>Used by the resume loop instead of {@link #shouldResumeIncompleteBody}
     *  while the fallback is pending: the response stream's header-written
     *  state was reset when it swallowed the failed resume headers, so the
     *  Range-specific {@code canRangeResume} gate can no longer pass even
     *  though headers and a partial body were already delivered to the
     *  browser. The remaining requirements are the same splice invariants —
     *  a definite Content-Length so the delivered prefix is known, and an
     *  incomplete body so there is still something to fetch. The caller
     *  (resume loop entry) has already required a reconnect callback and an
     *  idempotent GET/HEAD.
     *
     *  @param bodyReceived entity-body bytes already delivered to the browser
     *  @param dataExpected original response Content-Length, or -1 if unknown
     *  @return true if a fresh full-entity request may be issued
     *  @since 0.9.71+
     */
    static boolean shouldFallbackFullBody(long bodyReceived, long dataExpected) {
        return dataExpected > 0
               && bodyReceived >= 0
               && bodyReceived < dataExpected;
    }

    /**
     *  Rewrite a buffered GET/HEAD request to resume at {@code start} via a
     *  Range header, replacing any Range the browser already sent.
     *
     *  <p>The header is inserted immediately before the terminating blank line
     *  of the header block. {@code start <= 0} returns the request unchanged
     *  (full entity; no Range needed). A request with no header terminator is
     *  returned unchanged rather than corrupted.
     *
     *  @param request the raw request bytes (request-line + headers), may be null
     *  @param start first byte offset of the remaining entity (inclusive)
     *  @return a new request array with the Range header, or the original when
     *          no rewrite applies; never null if {@code request} is non-null
     *  @since 0.9.71+
     */
    static byte[] withRangeHeader(byte[] request, long start) {
        if (request == null || start <= 0) {return request;}
        int end = indexOfHeaderEnd(request);
        if (end < 0) {return request;}
        // Strip any existing Range lines so the resume offset is authoritative.
        byte[] base = stripRangeHeader(request, end);
        end = indexOfHeaderEnd(base);
        if (end < 0) {return base;}
        byte[] range = DataHelper.getASCII("Range: bytes=" + start + "-\r\n");
        // end points at the \r of the first CRLF in the terminating CRLFCRLF;
        // insert after that CRLF so the new header is a full line before the blank.
        int insertAt = end + 2;
        if (insertAt > base.length) {return base;}
        byte[] out = new byte[base.length + range.length];
        System.arraycopy(base, 0, out, 0, insertAt);
        System.arraycopy(range, 0, out, insertAt, range.length);
        System.arraycopy(base, insertAt, out, insertAt + range.length, base.length - insertAt);
        return out;
    }

    /**
     *  Return {@code request} with every {@code Range:} header line removed —
     *  the non-Range fallback form re-requested after a transient status
     *  (408/502/503/504) aborts a Range resume. The response then starts at
     *  byte 0, and the resume stream drops the already-delivered prefix so the
     *  splice stays seamless.
     *
     *  @param request the raw request bytes (request-line + headers), may be null
     *  @return a copy without Range lines (original array when none present);
     *          null if {@code request} is null
     *  @since 0.9.71+
     */
    static byte[] withoutRangeHeader(byte[] request) {
        if (request == null) {return null;}
        int end = indexOfHeaderEnd(request);
        if (end < 0) {return request;}
        return stripRangeHeader(request, end);
    }

    /**
     *  Index of the first {@code \r\n\r\n} (or {@code \n\n}) header terminator.
     *
     *  @param data request or header bytes, may be null
     *  @return index of the first byte of the terminator, or -1 if absent
     */
    private static int indexOfHeaderEnd(byte[] data) {
        if (data == null) {return -1;}
        for (int i = 0; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                if (i + 3 < data.length && data[i + 2] == '\r' && data[i + 3] == '\n') {
                    return i;
                }
            } else if (data[i] == '\n' && data[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     *  Return a copy of {@code request} with any {@code Range:} header line removed.
     *  Only lines before the header terminator are considered.
     *
     *  @param request raw request bytes
     *  @param headerEnd index from {@link #indexOfHeaderEnd(byte[])}
     *  @return request without Range lines (new array if stripped, else original)
     */
    private static byte[] stripRangeHeader(byte[] request, int headerEnd) {
        // Scan header lines (after the request line) for a case-insensitive "Range:".
        int lineStart = 0;
        for (int i = 0; i < headerEnd; i++) {
            boolean eol = (request[i] == '\n')
                || (request[i] == '\r' && i + 1 < headerEnd && request[i + 1] == '\n');
            if (!eol) {continue;}
            int lineEnd = i + 1;
            if (request[i] == '\r') {lineEnd = i + 2;}
            if (isRangeHeaderLine(request, lineStart, lineEnd)) {
                byte[] out = new byte[request.length - (lineEnd - lineStart)];
                System.arraycopy(request, 0, out, 0, lineStart);
                System.arraycopy(request, lineEnd, out, lineStart, request.length - lineEnd);
                return out;
            }
            lineStart = lineEnd;
            if (request[i] == '\r') {i++;}
        }
        return request;
    }

    /**
     *  Whether {@code [start, end)} is a {@code Range:} header line (not the request line).
     *
     *  @param data request bytes
     *  @param start first byte of the line
     *  @param end one past the line (including CRLF)
     *  @return true if the line starts with {@code Range:} ignoring ASCII case
     */
    private static boolean isRangeHeaderLine(byte[] data, int start, int end) {
        final byte[] prefix = { 'r', 'a', 'n', 'g', 'e', ':' };
        if (end - start <= prefix.length) {return false;}
        for (int i = 0; i < prefix.length; i++) {
            byte b = data[start + i];
            if (b >= 'A' && b <= 'Z') {b += 32;}
            if (b != prefix[i]) {return false;}
        }
        return true;
    }

    private static final byte[] GET = { 'G', 'E', 'T', ' ' };
    private static final byte[] HEAD = { 'H', 'E', 'A', 'D', ' ' };
    private static final byte[] POST = { 'P', 'O', 'S', 'T', ' ' };
    private static final byte[] PUT = { 'P', 'U', 'T', ' ' };

    /**
     *  Whether the buffered initial request is safe to re-send on a fresh connection
     *  in the "empty response" retry.
     *
     *  <p>A request is retryable iff it is idempotent and carries no streamed body, so
     *  re-sending it cannot duplicate a submission or split a byte stream mid-body. Only
     *  GET and HEAD qualify: they have no request body by definition and repeating them
     *  is safe. The leading ASCII method token is compared case-insensitively because
     *  {@code initialI2PData} holds the raw request bytes as the browser sent them. This
     *  mirrors the {@link #POST}/{@link #PUT} guard used when deciding whether to flush
     *  the initial packet before the body arrives, and is deliberately independent of the
     *  reconnect callback so a future caller cannot enable re-sends for a POST/PUT.
     *
     *  @param initialData the buffered request (request-line + headers), may be null
     *  @return true if the request starts with {@code GET} or {@code HEAD}
     *  @since 0.9.62
     */
    static boolean isRetryableRequest(byte[] initialData) {
        return startsWithIgnoreCase(initialData, GET) || startsWithIgnoreCase(initialData, HEAD);
    }

    /**
     *  Entity-body progress for a mid-body Range resume. Default: no HTTP
     *  response stream (server-side or non-HTTP runner) — resume never applies.
     *
     *  @return body bytes delivered, content length (-1 if unknown), and whether
     *          headers were written / Range can splice; defaults disable resume
     *  @since 0.9.71+
     */
    protected BodyProgress getBodyProgress() { return null; }

    /**
     *  Prepare the HTTP response stream to swallow the next header block
     *  (mid-body Range resume). No-op when there is no response stream.
     *
     *  @since 0.9.71+
     */
    protected void prepareBodyResume() { /* no HTTP response stream */ }

    /**
     *  Snapshot of body-delivery progress used by {@link #shouldResumeIncompleteBody}.
     *
     *  @since 0.9.71+
     */
    protected static final class BodyProgress {
        /** Entity-body bytes already written to the browser. */
        public final long bodyReceived;
        /** Original Content-Length, or -1 if unknown. */
        public final long contentLength;
        /** Whether the first response headers were emitted to the browser. */
        public final boolean headerWritten;
        /** Whether a Content-Length body can be safely spliced (not gzip, etc.). */
        public final boolean canRangeResume;

        /**
         *  @param bodyReceived entity bytes delivered
         *  @param contentLength original Content-Length or -1
         *  @param headerWritten whether browser already has headers
         *  @param canRangeResume whether splice is safe
         */
        public BodyProgress(long bodyReceived, long contentLength,
                            boolean headerWritten, boolean canRangeResume) {
            this.bodyReceived = bodyReceived;
            this.contentLength = contentLength;
            this.headerWritten = headerWritten;
            this.canRangeResume = canRangeResume;
        }
    }

    /**
     *  Re-drive the I2P→browser forwarder inline after a reconnect, waiting up
     *  to the standard 180s for completion (same policy as the initial run).
     *
     *  @param out browser-facing output stream (possibly HTTP-filtered)
     *  @param i2pin input stream of the current I2P socket (updated field preferred)
     *  @since 0.9.71+
     */
    private void redriveReceiveForwarder(OutputStream out, InputStream i2pin) {
        finished = false;
        InputStream pin = i2pin;
        // Preserve the caller-provided stream: the dual-race winner's pushback
        // wrapper still holds the first response byte unread, and reacquiring a
        // fresh socket stream would discard it, corrupting the response header.
        // Only reacquire when the caller supplied nothing.
        try {
            pin = pickRedriveStream(i2pin, i2ps);
        } catch (IOException ioe) {
            if (_log.shouldDebug()) {_log.debug("redrive: falling back to caller-provided stream", ioe);}
        }
        fromI2P = new StreamForwarder(pin, out, false, _onSuccess);
        fromI2P.run();
        synchronized (finishLock) {
            long endTime = System.currentTimeMillis() + 3*60*1000;
            while (!finished) {
                long remaining = endTime - System.currentTimeMillis();
                if (remaining <= 0) {finished = true; finishLock.notifyAll(); break;}
                try {finishLock.wait(Math.min(remaining, 5000));}
                catch (InterruptedException ie) {Thread.currentThread().interrupt(); finished = true; break;}
            }
        }
    }

    /**
     *  Choose the stream a re-driven receive forwarder reads from.
     *
     *  <p>The caller-provided stream always wins when present: the dual-race
     *  winner is delivered as a {@link PushbackInputStream} whose buffer holds
     *  the first response byte the race already consumed, and reacquiring
     *  {@code sock.getInputStream()} would start past that byte, truncating
     *  the response header. A fresh socket stream is used only when the
     *  caller supplied none.
     *
     *  @param supplied caller-provided stream (race winner's pushback wrapper
     *                  or a fresh socket stream), may be null
     *  @param sock current I2P socket to reacquire from, may be null
     *  @return {@code supplied} when non-null; otherwise {@code sock}'s input
     *          stream; otherwise null
     *  @throws IOException if {@code sock} cannot open its input stream
     *  @since 0.9.71+
     */
    static InputStream pickRedriveStream(InputStream supplied, I2PSocket sock) throws IOException {
        if (supplied != null) {return supplied;}
        if (sock != null) {return sock.getInputStream();}
        return null;
    }

    /**
     *  Winner of a dual-race empty retry: the socket that produced the first
     *  response byte, with a pushback stream that still holds that byte so the
     *  forwarder never loses the head of the response.
     */
    static final class RaceWin {
        final I2PSocket sock;
        final InputStream in;

        RaceWin(I2PSocket sock, InputStream in) {
            this.sock = sock;
            this.in = in;
        }
    }

    /**
     *  Race two freshly connected sockets for an empty-response retry: write
     *  the buffered request to both, then the first socket to deliver a
     *  non-EOF byte wins. The loser is closed; a total failure (both empty,
     *  timed out, or errored) returns null so the outer cycle can burn an
     *  attempt. Pure I/O — no budget/permit decisions live here. All socket
     *  cleanup happens in {@code finally}: exactly the sockets not returned
     *  are closed on every exit path, including unexpected exceptions.
     *
     *  @param pair two non-null sockets from {@link ReconnectCallback#reconnectPair}
     *  @param request buffered request bytes, may be null (nothing to re-send)
     *  @return the winner with its pushback stream, or null if neither produced data
     *  @since 0.9.71+
     */
    RaceWin raceEmptyPair(I2PSocket[] pair, byte[] request) {
        final I2PSocket sockA = pair[0];
        final I2PSocket sockB = pair[1];
        final AtomicReference<RaceWin> winner = new AtomicReference<>();
        final AtomicInteger failures = new AtomicInteger();
        final CountDownLatch settled = new CountDownLatch(1);
        RaceWin win = null;
        try {
            PushbackInputStream pA;
            PushbackInputStream pB;
            try {
                pA = new PushbackInputStream(sockA.getInputStream(), 32);
                pB = new PushbackInputStream(sockB.getInputStream(), 32);
                if (request != null) {
                    // isRetryableRequest() guarantees GET/HEAD — no body, flush is safe.
                    OutputStream outA = sockA.getOutputStream();
                    outA.write(request);
                    outA.flush();
                    OutputStream outB = sockB.getOutputStream();
                    outB.write(request);
                    outB.flush();
                }
            } catch (IOException ioe) {
                if (_log.shouldWarn()) {_log.warn("Empty-race: failed to prepare sockets", ioe);}
                return null;
            }
            final PushbackInputStream inA = pA;
            final PushbackInputStream inB = pB;
            startRaceLeg("A", sockA, inA, winner, failures, settled);
            startRaceLeg("B", sockB, inB, winner, failures, settled);
            try {
                if (!settled.await(EMPTY_RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    if (_log.shouldWarn()) {_log.warn("Empty-race: no first byte within " + EMPTY_RACE_TIMEOUT_MS + "ms");}
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            win = winner.get();
            if (win != null && _log.shouldInfo()) {_log.info("Empty-race winner selected");}
            if (win == null && _log.shouldInfo()) {
                _log.info("Empty-race both legs failed (settled=" + settled.getCount() +
                          ", failures=" + failures.get() + ')');
            }
            return win;
        } finally {
            // Close exactly what was not returned: the loser when a winner was
            // chosen, both sockets otherwise (failure, timeout, or exception).
            if (win != null) {
                closeQuietly(win.sock == sockA ? sockB : sockA);
            } else {
                closeQuietly(sockA);
                closeQuietly(sockB);
            }
        }
    }

    /**
     *  One race leg: read a single first byte, unread it into a pushback
     *  stream, and publish the win. EOF / timeout / IOException counts as a
     *  leg failure; the second failure (or overall timeout) settles the race.
     *
     *  @param label "A" or "B" for log context
     *  @param sock  the socket this leg is reading from (closed by caller on loss)
     *  @param in    pushback stream wrapping that socket's input
     *  @param winner shared winner slot (first CAS wins)
     *  @param failures shared failure counter
     *  @param settled counted down when the race has a winner or both legs failed
     *  @since 0.9.71+
     */
    private void startRaceLeg(String label, final I2PSocket sock, final PushbackInputStream in,
                              final AtomicReference<RaceWin> winner,
                              final AtomicInteger failures, final CountDownLatch settled) {
        Runnable leg = new Runnable() {
            @Override
            public void run() {
                try {
                    int b = in.read();
                    if (b >= 0) {
                        in.unread(b);
                        if (winner.compareAndSet(null, new RaceWin(sock, in))) {
                            settled.countDown();
                            return;
                        }
                    }
                } catch (IOException ioe) {
                    if (_log.shouldDebug()) {_log.debug("Empty-race leg " + label + " failed: " + ioe.getMessage());}
                }
                if (failures.incrementAndGet() >= 2) {
                    settled.countDown();
                }
            }
        };
        Thread t = new Thread(leg, "EmptyRace-" + label);
        t.setDaemon(true);
        t.start();
    }

    private static void closeQuietly(I2PSocket sock) {
        if (sock == null) {return;}
        try {sock.close();} catch (IOException ioe) {/* ignored */}
    }

    private static boolean sleepQuietly(long delayMs) {
        if (delayMs <= 0) {return true;}
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     *  Resume an incomplete Content-Length body on a fresh I2P connection.
     *
     *  <p>Rotates to a new tunnel via {@link ReconnectCallback}, re-sends the
     *  buffered GET/HEAD with {@code Range: bytes=N-} for the undelivered
     *  remainder, and splices the second response body onto the browser stream
     *  without re-emitting headers.  After a transient status (408/502/503/504)
     *  aborts a Range attempt the stream can no longer splice, so the next
     *  attempt re-requests the full entity with all Range headers stripped;
     *  the fresh 200's already-delivered prefix is then dropped so the splice
     *  stays seamless.  The budget is progress-based: only
     *  consecutive stall cycles (no new body bytes since the previous attempt)
     *  count against the size-scaled cap from {@link #stallCycleLimit(long)}
     *  (baseline {@link #MAX_EMPTY_RECONNECT_CYCLES}); any forward progress
     *  resets that counter so a slowly-advancing download is never abandoned
     *  for lack of progress.  {@link #totalCycleLimit(long)} is an absolute
     *  safety cap on total attempts regardless of progress.
     *
     *  @param out browser-facing output stream
     *  @since 0.9.71+
     */
    private void resumeIncompleteBody(OutputStream out) {
        if (_reconnectCallback == null || !isRetryableRequest(initialI2PData)) {return;}
        ResumeBudget budget = new ResumeBudget();
        // Set after a transient 408/502/503/504 aborts a Range attempt: the
        // response stream's header-written state was reset while swallowing
        // the failed resume headers, so Range eligibility (which requires
        // headerWritten) can never become true again until a response header
        // block completes. Without this flag the next eligibility check would
        // see headerWritten==false and silently abandon the download.
        boolean nonRangeFallback = false;
        while (true) {
            BodyProgress bp = getBodyProgress();
            if (bp == null) {return;}
            boolean resume = nonRangeFallback
                    ? shouldFallbackFullBody(bp.bodyReceived, bp.contentLength)
                    : shouldResumeIncompleteBody(bp.bodyReceived, bp.contentLength,
                            true, true, bp.headerWritten && bp.canRangeResume);
            if (!resume) {return;}
            if (!budget.tryConsume(bp.bodyReceived, bp.contentLength)) {
                if (_log.shouldWarn()) {
                    _log.warn("Body-resume budget exhausted at " + bp.bodyReceived + '/' +
                              bp.contentLength + " bytes (stalls=" + budget.getStallCycles() +
                              ", attempts=" + budget.getTotalCycles() + ')');
                }
                return;
            }
            Exception e = fromI2P != null ? fromI2P.getFailure() : null;
            if (e == null && toI2P != null) {e = toI2P.getFailure();}
            I2PSocket fresh = _reconnectCallback.reconnect(e);
            if (fresh == null) {
                if (_log.shouldWarn()) {
                    _log.warn("Body-resume reconnect declined at " + bp.bodyReceived + '/' +
                              bp.contentLength + " bytes; leaving download truncated");
                }
                return;
            }
            if (sockList != null) {synchronized (slock) {sockList.remove(i2ps);}}
            try {i2ps.close();} catch (IOException ioe) {/* ignored */}
            i2ps = fresh;
            InputStream i2pin;
            OutputStream i2pout;
            try {
                i2pin = i2ps.getInputStream();
                i2pout = i2ps.getOutputStream();
            } catch (IOException ioe) {
                if (_log.shouldWarn()) {_log.warn("Body-resume: failed to open fresh I2P streams", ioe);}
                return;
            }
            byte[] req = nonRangeFallback
                    ? withoutRangeHeader(initialI2PData)
                    : withRangeHeader(initialI2PData, bp.bodyReceived);
            try {
                // Swallow the second response's headers before any byte arrives.
                prepareBodyResume();
                if (req != null) {
                    i2pout.write(req);
                    i2pout.flush();
                }
            } catch (IOException ioe) {
                if (_log.shouldWarn()) {_log.warn("Body-resume: failed to send " +
                        (nonRangeFallback ? "non-Range" : "Range") + " request", ioe);}
                return;
            }
            if (_log.shouldInfo()) {
                _log.info("Body resume " + (nonRangeFallback ? "from byte 0 (no Range)" :
                          "from byte " + bp.bodyReceived) + '/' + bp.contentLength +
                          " on a fresh I2P socket (tunnel rotation)");
            }
            redriveReceiveForwarder(out, i2pin);
            // Transient status (408/502/503/504) on this attempt: refund the
            // stall charge and switch to (or stay in) non-Range fallback so
            // the next iteration re-requests the full entity from byte 0.
            if (wasTransientResumeFailure()) {
                nonRangeFallback = true;
                budget.refundLast();
                if (_log.shouldInfo()) {
                    _log.info("Body-resume attempt hit a transient status; " +
                              "falling back to a non-Range re-request");
                }
            } else {
                // Successful splice (or a genuine stall) restores Range mode:
                // a completed header block re-enables headerWritten/canRangeResume.
                nonRangeFallback = false;
            }
        }
    }

    /**
     *  Whether the last body-resume attempt failed with a transient status
     *  (408/502/503/504) rather than a real stall.  Subclasses with an HTTP
     *  response stream override this; the base runner has no stream so it
     *  always returns false.
     *
     *  @return true if the last resume aborted with a transient status
     *  @since 0.9.71+
     */
    protected boolean wasTransientResumeFailure() { return false; }

    /**
     *  Whether a transient status (408/502/503/504) is currently latched on
     *  the response stream — observed without clearing, unlike
     *  {@link #wasTransientResumeFailure()}. The resume loop clears the latch
     *  when it observes the failure; the forwarder's finally-block runs first
     *  and uses this peek to keep the browser stream open while the non-Range
     *  fallback is still pending.
     *
     *  @return true if a transient status aborted the last resume attempt
     *          and the loop has not yet observed it
     *  @since 0.9.71+
     */
    protected boolean hasTransientResumeFailure() { return false; }

    /**
     *  Whether an incomplete body is still eligible for resume — used by
     *  the forwarder finally-block to keep the browser stream open. A
     *  latched transient status means the non-Range fallback is still
     *  pending; closing the browser stream now would sever the splice target
     *  before the loop can re-request the full entity.
     *
     *  @return true if resume should run (or may still run) after this forwarder
     *  @since 0.9.71+
     */
    private boolean isBodyResumePending() {
        if (_reconnectCallback == null || !isRetryableRequest(initialI2PData)) {return false;}
        BodyProgress bp = getBodyProgress();
        if (bp == null) {return false;}
        if (hasTransientResumeFailure()) {
            return shouldFallbackFullBody(bp.bodyReceived, bp.contentLength);
        }
        return shouldResumeIncompleteBody(bp.bodyReceived, bp.contentLength,
                true, true, bp.headerWritten && bp.canRangeResume);
    }

    /**
     *  Case-insensitive ASCII prefix match of {@code prefix} against {@code data}.
     *
     *  @param data the bytes to test, may be null
     *  @param prefix the byte sequence to match at the start of {@code data}
     *  @return true if {@code data} is non-null, at least as long as {@code prefix}, and
     *          equals {@code prefix} ignoring ASCII case
     *  @since 0.9.62
     */
    private static boolean startsWithIgnoreCase(byte[] data, byte[] prefix) {
        if (data == null || prefix == null || data.length < prefix.length) {return false;}
        for (int i = 0; i < prefix.length; i++) {
            byte b = data[i];
            // Upper-case an ASCII lower-case letter in place comparison (byte is unsigned).
            if (b >= 'a' && b <= 'z') {b -= 32;}
            if (b != prefix[i]) {return false;}
        }
        return true;
    }

    /**
     *  Whether this runner type arms the first-response deadline watchdog.
     *  False in the base runner so idle long-lived sessions (HTTP CONNECT,
     *  SOCKS, IRC, server-side tunnels) are never torn down for silence; the
     *  HTTP request/response runner overrides it to true. For CONNECT the
     *  remote proxy's 200 response would retire the watchdog anyway, but the
     *  default keeps the deadline strictly scoped to request/response flows.
     *
     *  @return true to schedule the initial-response watchdog
     *  @since 0.9.71+
     */
    protected boolean trackInitialResponseDeadline() { return false; }

    /**
     *  Record a request-side write upstream: start (or slide, or restart) the
     *  first-response deadline for the current attempt. Sliding on every
     *  toI2P write keeps a slow POST upload from tripping the deadline; a
     *  re-send after an empty response restarts it for the fresh attempt.
     *  Clears the fired latch so the new attempt may fire independently.
     */
    void noteRequestWritten() {
        noteRequestWritten(System.currentTimeMillis());
    }

    /**
     *  {@link #noteRequestWritten()} with an explicit timestamp (test hook).
     *
     *  @param nowMs epoch-ms to record as the request write anchor
     */
    void noteRequestWritten(long nowMs) {
        _lastRequestWriteMs = nowMs;
        _initialDeadlineFired = false;
    }

    /**
     *  Record the first response byte upstream: permanently retires the
     *  initial-response watchdog from here on ({@link #BODY_STALL_READ_TIMEOUT_MS}
     *  covers further stalling).
     */
    void noteFirstResponseByte() {
        _firstByteMs = System.currentTimeMillis();
    }

    /**
     *  Enforce the first-response deadline: close the I2P socket if no
     *  response byte arrived within {@link #initialResponseTimeoutMs} of the
     *  last request-side write. Closing the socket is what unblocks the
     *  receive forwarder (a blocked read throws once the stream closes), and
     *  run()'s existing empty-transfer path then invokes the no-data failure
     *  callback — the single choke point — so the browser sees a proper 5xx
     *  instead of an indefinite hang. At most one close per request attempt;
     *  a subsequent re-send re-arms via {@link #noteRequestWritten()}.
     *
     *  @param nowMs current epoch-ms
     *  @return true if the socket was closed by this call
     *  @since 0.9.71+
     */
    boolean checkInitialResponseDeadline(long nowMs) {
        if (_initialDeadlineFired) {return false;}
        if (!initialResponseExpired(_lastRequestWriteMs, _firstByteMs, nowMs,
                                    initialResponseTimeoutMs)) {return false;}
        // Claim the fire, then re-check: a request write may have raced in
        // between the two checks above, in which case the deadline slid and
        // this attempt must not be torn down.
        _initialDeadlineFired = true;
        if (!initialResponseExpired(_lastRequestWriteMs, _firstByteMs, nowMs,
                                    initialResponseTimeoutMs)) {
            _initialDeadlineFired = false;
            return false;
        }
        I2PSocket sock = i2ps;
        if (sock == null) {return false;}
        if (_log.shouldWarn()) {
            _log.warn("No response from peer within " + (initialResponseTimeoutMs / 1000) +
                      "s of the request write (runner " + _runnerId + "), closing I2P socket");
        }
        try {sock.close();}
        catch (IOException ioe) { /* ignored */ }
        return true;
    }

    /**
     *  Arm the first-response watchdog after the request has been written.
     *  Idempotent; a no-op when {@link #trackInitialResponseDeadline()} is
     *  false or the timer rejects scheduling (headless/broken context).
     */
    private void scheduleInitialResponseWatchdog() {
        if (!trackInitialResponseDeadline() || _initialWatchdog != null) {return;}
        _initialWatchdogOn = true;
        SimpleTimer2.TimedEvent wd = new InitialResponseWatchdog();
        _initialWatchdog = wd;
        try {
            I2PAppContext.getGlobalContext().simpleTimer2()
                         .addPeriodicEvent(wd, INITIAL_WATCHDOG_POLL_MS, INITIAL_WATCHDOG_POLL_MS);
        } catch (RuntimeException re) {
            _initialWatchdogOn = false;
            _initialWatchdog = null;
            if (_log.shouldWarn()) {_log.warn("Failed to schedule initial-response watchdog", re);}
        }
    }

    /**
     *  Disarm the initial-response watchdog; called from run()'s finally so
     *  a completed runner never leaves a timer event behind. Safe to call
     *  when never scheduled.
     */
    private void cancelInitialResponseWatchdog() {
        _initialWatchdogOn = false;
        SimpleTimer2.TimedEvent wd = _initialWatchdog;
        _initialWatchdog = null;
        if (wd != null) {wd.cancel();}
    }

    /**
     *  Periodic tick enforcing {@link #INITIAL_RESPONSE_TIMEOUT_MS}.
     *
     *  <p>Deliberately does NOT stop on {@code finished}: that flag flips when
     *  the forwarders end, before run()'s empty-response reconnect loop has
     *  re-driven the request on a fresh socket — exactly when a new attempt
     *  needs its deadline. It retires only on a first response byte, or when
     *  run()'s finally cancels it.
     */
    private final class InitialResponseWatchdog extends SimpleTimer2.TimedEvent {
        @Override
        public void timeReached() {
            if (!_initialWatchdogOn || _firstByteMs > 0) {
                cancel();
                return;
            }
            checkInitialResponseDeadline(System.currentTimeMillis());
            if (_initialWatchdogOn) {schedule(INITIAL_WATCHDOG_POLL_MS);}
            else {cancel();}
        }
    }

    /**
     * run.
     */
    @Override
    public void run() {
        boolean i2pReset = false;
        boolean sockReset = false;
        InputStream in = null;
        OutputStream out = null;
        InputStream i2pin = null;
        OutputStream i2pout = null;
        try {
            out = getSocketOut();
            i2pin = i2ps.getInputStream();
            i2pout = i2ps.getOutputStream();
            String direction = (toI2P != null ? "[To I2P]" : "[From I2P]");

            if (initialI2PData != null) {
                i2pout.write(initialI2PData);
                /*
                 * Do NOT flush here, it will block and then onTimeout.run() won't happen on fail.
                 * But if we don't flush, then we have to wait for the connectDelay timer to fire
                 * in i2p socket? To be researched and/or fixed.
                 *
                 * AS OF 0.8.1, MessageOutputStream.flush() is fixed to only wait for accept,
                 * not for "completion" (i.e. an ACK from the far end).
                 *
                 * So we now get a fast return from flush(), and can do it here to save 250 ms.
                 * To make sure we are under the initial window size and don't hang waiting for accept,
                 * only flush if it fits in one message.
                 */
                // Don't flush if POST, so we can get POST data into the initial packet
                if (initialI2PData.length <= 1730 && (initialI2PData.length < 5 ||
                    !(DataHelper.eq(POST, 0, initialI2PData, 0, 5) ||
                      DataHelper.eq(PUT, 0, initialI2PData, 0, 4))))
                    i2pout.flush();
                noteRequestWritten();
            }
            scheduleInitialResponseWatchdog();
            if (initialSocketData != null) {out.write(initialSocketData);} // this does not increment totalReceived
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Initial data -> " + (initialI2PData != null ? initialI2PData.length : 0)
                           + " bytes written to I2P, " + (initialSocketData != null ? initialSocketData.length : 0)
                           + " bytes written to the socket, starting forwarders...");

            }
            if (_keepAliveSocket) {
                // Standard GET or HEAD, no data, do not thread a forwarder because we don't need it
                // and we don't want it to swallow the next request
            } else {
                in = getSocketIn();
                // InternalSocket already has buffering
                if (!(s instanceof InternalSocket)) {in = new BufferedInputStream(in, 2*NETWORK_BUFFER_SIZE);}
                toI2P = new StreamForwarder(in, i2pout, true, null);
                Executor exec = _runnerExecutor;
                if (exec != null) {
                    try {
                        exec.execute(toI2P);
                    } catch (RejectedExecutionException ree) {
                        // All runner threads busy - drop this connection cleanly.
                        // Continuing would leave only the I2P-to-client forwarder
                        // running, so the client would hang instead of failing.
                        if (_log.shouldWarn())
                            _log.warn(direction + " Connection dropped: client pool saturated");
                        onNoDataFailure(ree);
                        try {i2ps.close();} catch (IOException ioe) {}
                        try {s.close();} catch (IOException ioe) {}
                        return;
                    }
                } else {
                    Thread t = new Thread(toI2P, "TunFwdI2P." + _runnerId);
                    t.setDaemon(true);
                    t.start();
                }
            }
            fromI2P = new StreamForwarder(i2pin, out, false, _onSuccess);
            // We are already a thread, so run the second one inline
            fromI2P.run();
            synchronized (finishLock) {
                long endTime = System.currentTimeMillis() + 3*60*1000; // 180 second timeout
                while (!finished) {
                    long remaining = endTime - System.currentTimeMillis();
                    if (remaining <= 0) {
                        // Timeout reached
                        if (_log.shouldLog(Log.WARN)) {
                            _log.warn(direction + " Timeout waiting for completion - forcing cleanup");
                        }
                        finished = true;
                        finishLock.notifyAll();
                        break;
                    }
                    try {
                        // Wait for the remaining time or up to 5 seconds, whichever is smaller
                        finishLock.wait(Math.min(remaining, 5000));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Both forwarders completed -> " + totalSent + " bytes sent, " +  totalReceived + " bytes received");
            }

            // This task is useful for the httpclient
            final boolean tookEmptyPath = (onTimeout != null || _onFail != null) && totalReceived <= 0;
            if (tookEmptyPath) {
                // "Empty response" retry: if a reconnect callback is installed and the
                // buffered request is idempotent (GET/HEAD only, verified by
                // isRetryableRequest() so a misconfigured callback can never re-send a
                // POST/PUT body), a transfer that completed with zero upstream bytes is
                // retried against a fresh I2P connection on the SAME browser socket
                // instead of surfacing an NS_ERROR_NET_EMPTY_RESPONSE close. This applies
                // to both keepalive-browser GET/HEAD (no toI2P forwarder) and
                // non-keepalive GET/HEAD (a toI2P forwarder ran but carried no body). We
                // loop as long as the callback offers a new connection, bailing to the
                // normal failure path once it declines (budget exhausted) or a re-drive
                // errors.
                //
                // MAX_EMPTY_RECONNECT_CYCLES caps the outer loop: the callback's own
                // for-loop handles connect-failure backoff, but when the destination is
                // reachable yet sends zero bytes each cycle succeeds on attempt 1 and
                // the outer loop would spin forever.  4 cycles × callback's 9-attempt
                // budget gives generous headroom for transient failures while bounding
                // the total to ~40 attempts / ~30s worst-case.
                int emptyReconnectCycles = 0;
                while (shouldReconnectEmptyResponse(totalReceived, _reconnectCallback != null, isRetryableRequest(initialI2PData))) {
                    if (emptyReconnectCycles++ >= MAX_EMPTY_RECONNECT_CYCLES) {
                        if (_log.shouldWarn()) {
                            _log.warn("Empty-response reconnect budget exhausted after " + MAX_EMPTY_RECONNECT_CYCLES + " cycles");
                        }
                        break;
                    }
                    // Pace later cycles so a reachable-but-empty dest cannot spin
                    // back-to-back SYNs through the remote burst gate.
                    if (emptyCycleDelayMs(emptyReconnectCycles) > 0 &&
                        !sleepQuietly(emptyCycleDelayMs(emptyReconnectCycles))) {
                        break;
                    }
                    Exception e = fromI2P.getFailure();
                    if (e == null && toI2P != null) {e = toI2P.getFailure();}
                    I2PSocket[] pair = _reconnectCallback.reconnectPair(e);
                    if (pair == null || pair.length == 0) {break;}
                    // The dead connection is done; retire it from the shared socket list
                    // and swap in the fresh one the callback obtained.
                    //
                    // A non-keepalive GET/HEAD may have started a browser->I2P forwarder; it is
                    // deliberately left untouched. For a body-less GET/HEAD that forwarder is
                    // blocked on the browser-input read (the request was already consumed into
                    // initialI2PData), so it is inert and will not write to the closing socket
                    // or interfere with the re-send. Stopping it would be actively harmful:
                    // its finally() closes the browser input stream when !_keepAliveSocket,
                    // which would sever the very browser socket we are trying to keep open.
                    if (sockList != null) {synchronized (slock) {sockList.remove(i2ps);}}
                    try {i2ps.close();} catch (IOException ioe) {/* ignored */}
                    if (pair.length >= 2) {
                        RaceWin win = null;
                        try {
                            try {
                                win = raceEmptyPair(pair, initialI2PData);
                            } finally {
                                _reconnectCallback.releaseRacePermit();
                            }
                            if (win == null) {continue;}
                            i2ps = win.sock;
                            i2pin = win.in;
                            i2pout = null;
                            if (sockList != null) {synchronized (slock) {sockList.add(i2ps);}}
                            if (_log.shouldInfo()) {
                                _log.info("Empty-response dual-race selected a winner socket");
                            }
                            totalReceived = 0;
                            redriveReceiveForwarder(out, i2pin);
                            // raceEmptyPair() already sent the request on the winner;
                            // restart the first-response deadline for this attempt.
                            noteRequestWritten();
                            continue;
                        } finally {
                            // A winner adopted above (i2ps = win.sock) is owned by run()'s
                            // outer finally; close one that never made it past the swap so
                            // an exception between the race and the adoption cannot leak it.
                            if (win != null && win.sock != i2ps) {closeQuietly(win.sock);}
                        }
                    }
                    i2ps = pair[0];
                    i2pin = i2ps.getInputStream();
                    i2pout = i2ps.getOutputStream();
                    if (initialI2PData != null) {
                        i2pout.write(initialI2PData);
                        // isRetryableRequest() guarantees no POST/PUT body, so a flush is safe.
                        i2pout.flush();
                    }
                    if (_log.shouldInfo()) {
                        _log.info("Empty upstream response, reconnected and re-sending on a fresh I2P socket");
                    }
                    // Re-drive the receive forwarder inline; totalReceived is updated by it.
                    totalReceived = 0;
                    redriveReceiveForwarder(out, i2pin);
                    noteRequestWritten();
                }
                if (totalReceived <= 0) {
                    Exception e = fromI2P.getFailure();
                    onNoDataFailure(e);
                }
            }

            // Mid-body Range resume: headers + partial Content-Length body already
            // reached the browser; the upstream tunnel died (or stalled out) before
            // the entity completed. I2P latency is expected — do not abandon the
            // download. Reconnect on a fresh tunnel (rotate) and fetch only the
            // remaining bytes via Range so the browser sees one contiguous body.
            // Runs after the empty path too, when that path recovered a partial body.
            if (totalReceived > 0) {
                resumeIncompleteBody(out);
            }

            // Detect a reset on one side, and propagate to the other.
            // Skipped when the empty path handled a still-empty transfer
            // (onNoDataFailure already ran) — matches the pre-resume else-branch.
            if (!tookEmptyPath || totalReceived > 0) {
                Exception e1 = fromI2P.getFailure();
                Exception e2 = toI2P != null ? toI2P.getFailure() : null;
                Throwable c1 = e1 != null ? e1.getCause() : null;
                Throwable c2 = e2 != null ? e2.getCause() : null;
                if (c1 != null && c1 instanceof I2PSocketException) {
                    I2PSocketException ise = (I2PSocketException) c1;
                    int status = ise.getStatus();
                    i2pReset = status == I2PSocketException.STATUS_CONNECTION_RESET;
                }
                if (!i2pReset && c2 != null && c2 instanceof I2PSocketException) {
                    I2PSocketException ise = (I2PSocketException) c2;
                    int status = ise.getStatus();
                    i2pReset = status == I2PSocketException.STATUS_CONNECTION_RESET;
                }
                if (!i2pReset && e1 != null && e1 instanceof SocketException) {
                    String msg = e1.getMessage();
                    sockReset = msg != null && msg.contains("reset");
                }
                if (!sockReset && e2 != null && e2 instanceof SocketException) {
                    String msg = e2.getMessage();
                    sockReset = msg != null && msg.contains("reset");
                }
            }

        } catch (SSLException she) {
            _log.error("SSL error", she);
            _keepAliveI2P = false;
            _keepAliveSocket = false;
            onNoDataFailure(she);
        } catch (IOException ex) {
            if (_log.shouldLog(Log.DEBUG)) {_log.debug("Error forwarding (" + ex.getMessage() + ")");}
            _keepAliveI2P = false;
            _keepAliveSocket = false;
            onNoDataFailure(ex);
        } catch (IllegalStateException ise) {
            if (_log.shouldWarn()) {_log.warn("gnu?", ise);}
            _keepAliveI2P = false;
            _keepAliveSocket = false;
            onNoDataFailure(ise);
        } catch (RuntimeException e) {
            if (_log.shouldLog(Log.ERROR)) {_log.error("Internal error", e);}
            _keepAliveI2P = false;
            _keepAliveSocket = false;
            onNoDataFailure(e);
        } finally {
            cancelInitialResponseWatchdog();
            removeRef();
            if (i2pReset) {
                if (_log.shouldInfo()) {_log.warn("Received I2P reset, resetting socket...");}
                // Flush buffered data before RST to avoid truncated HTTP responses
                if (out != null) {
                    try {out.flush();}
                    catch (IOException ioe) { /* ignored */ }
                }
                try {s.setSoLinger(true, 0);}
                catch (IOException ioe) { /* ignored */ }
                try {s.close();}
                catch (IOException ioe) { /* ignored */ }
                try {i2ps.close();}
                catch (IOException ioe) { /* ignored */ }
                _keepAliveI2P = false;
                _keepAliveSocket = false;
            } else if (sockReset) {
                if (_log.shouldInfo()) {_log.warn("Received socket reset, resetting I2P socket...");}
                // Flush buffered data before closing to avoid truncated HTTP responses
                if (out != null) {
                    try {out.flush();}
                    catch (IOException ioe) { /* ignored */ }
                }
                try {i2ps.reset();}
                catch (IOException ioe) { /* ignored */ }
                try {s.close();}
                catch (IOException ioe) { /* ignored */ }
                _keepAliveI2P = false;
                _keepAliveSocket = false;
            } else {
                // Now one connection is dead - kill the other as well, after making sure we flush
                try {close(out, in, i2pout, i2pin, s, i2ps, null, null);}
                catch (InterruptedException ie) {Thread.currentThread().interrupt(); /* ignored */ }
            }
        }
    }

    /**
     *  Warning - overridden in I2PTunnelHTTPClientRunner.
     *  Here we ignore keepalive and always close both sides.
     *  The HTTP flavor handles keepalive.
     *
     *  @param out may be null
     *  @param in may be null
     *  @param i2pout may be null
     *  @param i2pin may be null
     *  @param t1 may be null
     *  @param t2 may be null, ignored, we only join t1
     */
    protected void close(OutputStream out, InputStream in, OutputStream i2pout, InputStream i2pin,
                         Socket s, I2PSocket i2ps, Thread t1, Thread t2) throws InterruptedException {
        if (out != null) {
            try {out.flush();}
            catch (IOException ioe) { /* ignored */ }
        }
        if (i2pout != null) {
            try {i2pout.flush();}
            catch (IOException ioe) { /* ignored */ }
        }
        if (in != null) {
            try {in.close();}
            catch (IOException ioe) { /* ignored */ }
        }
        if (i2pin != null) {
            try {i2pin.close();}
            catch (IOException ioe) { /* ignored */ }
        }
        // There's a race here in theory, if data comes in after flushing and before closing, but it's better than before...
        try {s.close();}
        catch (IOException ioe) { /* ignored */ }
        try {i2ps.close();}
        catch (IOException ioe) { /* ignored */ }
        if (t1 != null) {t1.join((long) 30*1000);}
    }

    /**
     *  Remove this runner's I2PSocket from the shared socket list.
     */
    private void removeRef() {
        if (sockList != null) {
            synchronized (slock) {sockList.remove(i2ps);}
        }
    }

    /**
     *  Forward data in one direction between two streams.
     *  Reads from the input stream and writes to the output stream
     *  until the stream is closed or an error occurs.
     */
    private class StreamForwarder implements Runnable {

        private final InputStream in;
        private final OutputStream out;
        private final String direction;
        private final boolean _toI2P;
        private final ByteCache _cache;
        private final SuccessCallback _callback;
        private volatile Exception _failure;
        /** flag to signal this forwarder should stop */
        public volatile boolean done;
        /** true once the stall read-timeout has been armed on the I2P socket */
        private boolean _stallArmed;

        /**
         *  @param cb may be null, only used for toI2P == false
         */
        public StreamForwarder(InputStream in, OutputStream out, boolean toI2P, SuccessCallback cb) {
            this.in = in;
            this.out = out;
            _toI2P = toI2P;
            _callback = cb;
            direction = (toI2P ? "[To I2P]" : "[From I2P]");
            _cache = ByteCache.getInstance(32, NETWORK_BUFFER_SIZE);
        }

        /**
         * run.
         */
        @Override
        public void run() {
            String from = i2ps.getThisDestination().calculateHash().toBase64().substring(0,8);
            String to = i2ps.getPeerDestination().calculateHash().toBase64().substring(0,8);

            if (_log.shouldLog(Log.DEBUG)) {_log.debug(direction + " Forwarding between [" + from + "] and [" + to + "]");}

            ByteArray ba = _cache.acquire();
            byte[] buffer = ba.getData();
            try {
                int len;
                while (!done && (len = in.read(buffer)) != -1) {
                    if (len > 0) {
                        if (_toI2P) {totalSent += len;}
                        else {
                            if (totalReceived == 0 && _callback != null) {_callback.onSuccess();}
                            // First body byte: arm a stall timeout so a subsequent
                            // black-hole fails over instead of blocking forever.
                            if (!_stallArmed) {
                                _stallArmed = true;
                                noteFirstResponseByte();
                                try {i2ps.setReadTimeout(BODY_STALL_READ_TIMEOUT_MS);}
                                catch (RuntimeException re) { /* older socket impl */ }
                            }
                            // Count the upstream bytes BEFORE the browser write. If the
                            // browser socket is already closed (e.g. Pipe closed under a
                            // congested stream), the write throws and the bytes would
                            // otherwise be lost from totalReceived. A real upstream
                            // response (even a 502) is not an "empty transfer" - recording
                            // it here stops the empty-response reconnect loop from treating
                            // a definitive answer as a retryable no-data failure.
                            totalReceived += len;
                        }
                        out.write(buffer, 0, len);
                        // Slide the first-response deadline so a slow request
                        // upload (POST body) never trips the initial-response
                        // watchdog mid-flight.
                        if (_toI2P) {noteRequestWritten();}
                    }
                    try {
                        if (in.available() == 0) {out.flush();}
                    } catch (IOException ioex) {
                        // Ignore flush errors
                    }
                }
            } catch (SocketException ex) {
                // This *will* occur when other threads closes the socket
                if (_log.shouldDebug()) {
                    boolean fnshd;
                    synchronized (finishLock) {fnshd = finished;}
                    if (!fnshd) {_log.debug(direction + " IO Error: Error forwarding -> " + ex.getMessage());}
                    else {_log.debug(direction + " IO Error caused by other direction -> " + ex.getMessage());}
                }
                _failure = ex;
                // Force cleanup to prevent stuck threads
                synchronized (finishLock) {
                    finished = true;
                    finishLock.notifyAll();
                }
            } catch (IOException ex) {
                if (_log.shouldWarn())
                    _log.warn(direction + " IO Error: " + ex);
                _failure = ex;
                synchronized (finishLock) {
                    finished = true;
                    finishLock.notifyAll();
                }
            } finally {
                _cache.release(ba);
                boolean keepAliveFrom;
                boolean keepAliveTo;
                if (_toI2P) {
                    keepAliveFrom = _keepAliveSocket;
                    keepAliveTo = _keepAliveI2P;
                } else {
                    keepAliveFrom = _keepAliveI2P;
                    keepAliveTo = _keepAliveSocket;
                }
                if (_log.shouldLog(Log.INFO)) {
                    _log.info(direction + " Done forwarding " + (_toI2P ? totalSent : totalReceived) + " bytes from [" + from + "] " +
                              (keepAliveFrom ? "(KeepAlive)" : "") + " to [" + to + "] " + (keepAliveTo ? "(KeepAlive)" : ""));
                }
                if (!keepAliveFrom) {
                    try {in.close();}
                    catch (IOException ex) {
                        if (_log.shouldWarn()) {_log.warn(direction + " Error closing input stream (" + ex.getMessage() + ")");}
                    }
                }

                try {
                    /*
                     * Thread must close() before exiting for a PipedOutputStream, or else input end gives up
                     * and we have data loss - techtavern.wordpress.com/2008/07/16/whats-this-ioexception-write-end-dead/
                     *
                     * DON'T close if we have a timeout job and we haven't received anything, or else the timeout job can't
                     * write the error message to the stream.
                     * close() above will close it after the timeout job is run.
                     *
                     * Also keep the browser stream open when a mid-body Range resume
                     * is still pending — closing it would sever the splice target.
                     */
                    boolean resumePending = !_toI2P && isBodyResumePending();
                    if (!((onTimeout != null || _onFail != null) && (!_toI2P) && totalReceived <= 0)) {
                        if (keepAliveTo || resumePending) {out.flush();}
                        else {out.close();}
                    } else {
                        if (_log.shouldInfo()) {_log.info(direction + " Not closing stream so we can write the error message...");}
                        if (keepAliveTo || resumePending) {out.flush();}
                    }
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.DEBUG)) {_log.debug(direction + " Error flushing stream before close (" + ioe.getMessage() + ")");}
                }
                synchronized (finishLock) {
                    finished = true;
                    finishLock.notifyAll();
                    // the main thread will close sockets etc. now
                }
            }
        }

        /**
         *
         * @return the failure
         */
        public Exception getFailure() {return _failure;}
    }

    @Override
    public void errorOccurred() {
        synchronized (finishLock) {
            finished = true;
            finishLock.notifyAll();
        }
    }

}
