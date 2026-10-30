/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLException;
import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.EventDispatcher;
import net.i2p.stat.RateConstants;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

import java.nio.charset.StandardCharsets;
/**
 * HTTP server tunnel that filters headers and provides compression.
 * <p>
 * Extends I2PTunnelServer to filter HTTP headers from client to server,
 * replacing the Host header with configured value. Compresses HTTP message
 * body and sets Content-Encoding: x-i2p-gzip when browser requests
 * Accept-Encoding: x-i2p-gzip.
 * <p>
 * Features include POST/PUT rate limiting, referer and user-agent rejection,
 * inproxy detection, HTTP blocklist support, keepalive management,
 * and response header security filtering.
 */
public class I2PTunnelHTTPServer extends I2PTunnelServer {

    /**
     * Post throttle window (seconds).
     */
    public static final int DEFAULT_POST_BAN_TIME = 15*60;
    /** Default max posts per window. */
    public static final int DEFAULT_POST_MAX = 16;
    /** Default total ban time for excessive posts. */
    public static final int DEFAULT_POST_TOTAL_BAN_TIME = 10*60;
    /** Default max total posts before ban. */
    public static final int DEFAULT_POST_TOTAL_MAX = 30;
    /** Default post rate-limit window (seconds). */
    public static final int DEFAULT_POST_WINDOW = 3*60;
    /** Default keepalive setting for HTTP persistent connections. */
    private static final boolean DEFAULT_KEEPALIVE = true;
    /** Config key for post ban time. */
    public static final String OPT_POST_BAN_TIME = "postBanTime";
    /** Config key for max posts. */
    public static final String OPT_POST_MAX = "maxPosts";
    /** Config key for post total ban time. */
    public static final String OPT_POST_TOTAL_BAN_TIME = "postTotalBanTime";
    /** Config key for max total posts. */
    public static final String OPT_POST_TOTAL_MAX = "maxTotalPosts";
    /** Config key for post check time window. */
    public static final String OPT_POST_WINDOW = "postCheckTime";

    /** Max wait for DNS resolution of non-I2P hostnames in the HTTP proxy. */
    private static final int DNS_TIMEOUT_MS = 5000;

    /**
     * Ceiling on a request host as it is logged. The Host header is
     * attacker-controlled and a header line may be 8 KB, so an unbounded host
     * would put an 8 KB value on one log line. 64 keeps the widest line under
     * 100 characters, and a FQDN is 253 at most.
     */
    private static final int MAX_LOG_HOST_LEN = 64;

    /**
     * Minimum interval between per-client "Request error" warnings. A single
     * torrent client reconnecting without sending a request produced 1000+
     * read timeouts in one rotation, which buried every other warning.
     * @since 0.9.71+
     */
    static final long REQUEST_ERROR_LOG_INTERVAL_MS = 5 * 60 * 1000;

    /**
     * Cap on distinct clients tracked for warning rate limiting, so a
     * flood from many distinct peers cannot grow this map without bound.
     * @since 0.9.71+
     */
    static final int MAX_TRACKED_ERROR_CLIENTS = 1024;

    /**
     * Last time each client was logged for a request error, for rate limiting.
     * Keyed on the base32 destination; bounded by {@link #MAX_TRACKED_ERROR_CLIENTS}.
     * @since 0.9.71+
     */
    private static final Map<String, Long> _lastErrorLogTime = new ConcurrentHashMap<>(64);

    /**
     * Decide whether a request-error warning should be emitted for a client.
     * First occurrence for a client is always logged; subsequent ones only
     * after {@link #REQUEST_ERROR_LOG_INTERVAL_MS}. Evicts the oldest entries
     * once the tracking map is full, so a burst of distinct peers cannot
     * grow it without bound.
     *
     * @param peerB32 client base32, may be null
     * @param now current time in ms
     * @return true if the caller should log at WARN
     * @since 0.9.71+
     */
    static boolean shouldLogRequestError(String peerB32, long now) {
        if (peerB32 == null) {return true;}
        Long last = _lastErrorLogTime.get(peerB32);
        if (last != null && (now - last) < REQUEST_ERROR_LOG_INTERVAL_MS) {return false;}
        if (_lastErrorLogTime.size() >= MAX_TRACKED_ERROR_CLIENTS) {
            evictOldestErrorClients(now);
        }
        _lastErrorLogTime.put(peerB32, now);
        return true;
    }

    /**
     * Free space in the tracking map, guaranteeing it never exceeds
     * {@link #MAX_TRACKED_ERROR_CLIENTS}. First drops clients whose last
     * warning is older than the log interval. If that is not enough (a burst
     * of distinct peers inside one interval leaves nothing stale), the single
     * oldest entry is evicted so the bound always holds. This runs only on the
     * overflow path, never on the normal per-request check.
     *
     * @param now current time in ms
     * @since 0.9.71+
     */
    private static void evictOldestErrorClients(long now) {
        _lastErrorLogTime.entrySet().removeIf(e -> (now - e.getValue()) >= REQUEST_ERROR_LOG_INTERVAL_MS);
        if (_lastErrorLogTime.size() < MAX_TRACKED_ERROR_CLIENTS) {return;}
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, Long> e : _lastErrorLogTime.entrySet()) {
            if (e.getValue() < oldestTime) {
                oldestTime = e.getValue();
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {_lastErrorLogTime.remove(oldestKey);}
    }

    /**
     * Test seam: clear the request-error rate limiting state.
     * @since 0.9.71+
     */
    static void resetRequestErrorLimiter() { _lastErrorLogTime.clear(); }

    /**
     * Test seam: number of clients currently tracked.
     * @return tracked client count
     * @since 0.9.71+
     */
    static int trackedErrorClients() { return _lastErrorLogTime.size(); }

    /**
     * Dedicated executor for non-blocking DNS lookups with bounded timeout.
     * Fixed-size with a bounded queue: a cached pool spawns a thread per blocked
     * lookup without bound, so hostile unique Host: headers could exhaust native
     * threads, and its unbounded queue made the RejectedExecutionException
     * handling below dead code. Rejections now surface and shed the request.
     */
    private static final ExecutorService _dnsResolver = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64), r -> {
            Thread t = new Thread(r, "DNS-Resolver");
            t.setDaemon(true);
            return t;
        });

    /**
     * Per-tunnel I/O pools for Server→Client data transfer, keyed by this
     * server instance so a saturated dest cannot consume every IO thread.
     * Sized as shares of {@link TunnelControllerGroup#getIOTransferThreads}.
     * Separate from the runner pool so pinned runner threads are never
     * consumed by slow downloads.
     * @since 0.9.71+
     */
    private static final ConcurrentHashMap<Object, ThreadPoolExecutor> _ioPools =
        new ConcurrentHashMap<>(4);
    /** Lock for creating the shared IO executor. */
    private static final Object _ioExecutorLock = new Object();
    /**
     * Default I/O transfer threads (global budget); Tuner adjusts via {@link TunnelControllerGroup}.
     * Sized for concurrent body transfers across many server tunnels (e.g. 16
     * eepsites x floor 8 = 128 threads when fully loaded).
     */
    static volatile int ioTransferThreads =
        Math.max(32, Math.min(128, Runtime.getRuntime().availableProcessors() * 4));
    /**
     * Absolute floor for the global I/O budget. Two transfers minimum so a
     * quiet router can still serve a page and its assets; the per-tunnel
     * floor is derived from the budget in {@link #ioPoolFloor}.
     * @since 0.9.71+
     */
    static final int IO_THREADS_MIN = 2;
    /** Maximum global I/O budget (Tuner max must match). @since 0.9.71+. */
    static final int IO_THREADS_MAX = maxIOThreads();
    /**
     * Sample periods for the I/O transfer load stat. Five minutes is included
     * because a body transfer holds its thread for the whole transfer, so the
     * per-minute view is noisy while the five-minute one shows the trend the
     * operator needs against the pool's ceiling.
     */
    private static final long[] IO_TRANSFER_RATES = {
        RateConstants.ONE_MINUTE, RateConstants.FIVE_MINUTES,
        RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR };

    /**
     * Heap-derived ceiling on the I/O transfer budget.
     *
     * <p>Each I/O thread holds 16 KB of buffer plus a 16 KB BufferedInputStream
     * and is blocked for the whole of a body transfer, so a thread costs roughly
     * an order of magnitude more than a runner-pool task, which finishes in
     * microseconds. Costing the divisor accordingly: 4 MB per thread rather than
     * the 2 MB the runner pool uses. At 8 GB that is 2048 threads, adding roughly
     * 190 MB to a 1.2 GB footprint — comfortable, and still large headroom over
     * the few hundred concurrent transfers a 40 K-client tracker produces at a
     * 30-minute announce interval.
     *
     * <p>The ceiling is burst headroom, not residency: {@code allowCoreThreadTimeOut}
     * with a 30-second keepalive reaps threads half a minute after their last
     * task, so an idle pool costs nothing whatever the ceiling says.
     *
     * @return the memory-feasible I/O thread ceiling, in [1024, 16384]
     * @since 0.9.71+
     */
    public static int maxIOThreads() {
        return Math.max(1024, Math.min(16384,
                (int) (SystemVersion.getMaxMemory() / (4L * 1024 * 1024))));
    }

    /**
     * Idle timeout for Server→Client transfers (ms). If no read/write progress
     * occurs for this long, the transfer is considered stalled and cancelled.
     * Measured between progress events, not as a total transfer deadline —
     * multi-minute I2P downloads are expected and must not be killed.
     */
    /**
     * Floor for the configured stall window, ms. A window below this is
     * shorter than any pause in a bulk transfer over a congested path and
     * aborts healthy downloads; bodies that have moved megabytes scale up
     * further via {@link #computeStallWindowMs}.
     * @since 0.9.71+
     */
    static final long STALL_WINDOW_FLOOR_MS = 60_000L;
    /** Current stall window, adjustable by the Tuner between the floor and max. */
    static volatile long ioStallTimeoutMs = STALL_WINDOW_FLOOR_MS;
    /**
     * Monotonically increasing counter of stall events detected by runOnIO
     * or the Sender. Used by the Tuner as the observed signal for stall
     * timeout adjustment.
     */
    static final AtomicLong _stallEventCount = new AtomicLong();

    /**
     * Bodies aborted because the I/O pool was saturated. A response aborted
     * here reaches the client as a truncated body, which is otherwise
     * indistinguishable from a socket close or a read timeout, so the counter
     * is what lets the cause be told apart in the log and on the console.
     * @since 0.9.71+
     */
    private static final AtomicLong _bodyAbortCount = new AtomicLong();

      /**
       * Count of bodies dropped because the I/O pool had no free worker.
       *
       * @return bodies aborted due to I/O pool saturation since router start
       * @since 0.9.71+
       */
    public static long getBodyAbortCount() { return _bodyAbortCount.get(); }

    /**
     * Get (creating on first use) this tunnel's private I/O pool for data
     * transfer. The pool is a share of the global ioTransferThreads budget so
     * one tunnel's bulk downloads cannot starve another's.
     *
     * @return non-null, this tunnel's I/O executor
     * @since 0.9.71+
     */
    ThreadPoolExecutor getIOExecutor() {
        synchronized (_ioExecutorLock) {
            ThreadPoolExecutor ex = _ioPools.get(this);
            if (ex == null || ex.isShutdown()) {
                ex = createIOExecutor(ioPoolFloor());
                _ioPools.put(this, ex);
            }
            rebalanceIOPools();
            return _ioPools.get(this);
        }
    }

    /**
     * Grow this tunnel's runner pool if work is backing up, immediately before a
     * submit.
     *
     * <p>The fast path is one atomic load of the queue depth, so a request that
     * finds a free pool pays nothing. Only when the queue holds work is
     * {@code getActiveCount()} consulted, because that takes the pool's mainLock and
     * would otherwise be a per-request monitor acquisition on a busy tunnel.
     *
     * <p>Growing here rather than in the accept loop is deliberate: the accept loop
     * owns the server handler pool, and the runner pool is only reachable from a
     * request that has already been parsed.
     *
     * @since 0.9.71+
     */
     private void growRunnerPoolForSubmit() {
         TunnelControllerGroup tcg = _tcg;
         ThreadPoolExecutor ex = _clientExecutor;
         if (tcg == null || ex == null || ex.isShutdown()) {return;}
         int q = ex.getQueue().size();
         if (q <= 0) {
             // Nothing queued. The idle observation is what lets a stale elevation
             // decay, so it must be reported even on the quiet path — a pool that was
             // elevated under load otherwise holds its floor on the shared budget
             // forever. noteRunnerIdle returns immediately for a pool that was never
             // elevated, so an unelevated tunnel pays one map lookup.
             tcg.noteRunnerIdle(this);
             return;
         }
         tcg.growRunnerExecutorForLoad(this, q, ex.getActiveCount());
     }

    /**
     * Report a runner-pool rejection to the Tuner and the log.
     *
     * <p>The shed stat is the point of this method. It is the unambiguous overload
     * signal {@code I2PTunnelClientRunnerMaxParam} grows on, and the server paths
     * that reject never wrote it: {@code i2ptunnel.clientConnectionShed} read
     * "No lifetime events" on a live router that was rejecting about 1.7 requests
     * a second. Without the write the Tuner reads such a tunnel as idle and decays
     * its runner budget toward the floor while the tunnel rejects at its share.
     *
     * <p>The active-thread sample is taken here because this is the only instant
     * the pool is observably pinned; the accept-loop sample is taken before the
     * work starts and reads low. Closing the connection is left to the caller,
     * which knows what it opened.
     *
     * @param tunnelId the tunnel identifier for the log line
     * @param peerB32 the client's base32 address for the log line
     * @since 0.9.71+
     */
    private void reportRunnerShed(String tunnelId, String peerB32) {
        TunnelControllerGroup.sampleRunnerActiveThreads(_clientExecutor);
        I2PAppContext ctx = getTunnel().getContext();
        if (ctx != null) {
            // No try/catch: StatManager.addRateData silently DEBUGs for
            // unregistered stats, never throws. The stat is registered in
            // I2PTunnelServer, which runs before any accept can reach here.
            ctx.statManager().addRateData("i2ptunnel.clientConnectionShed", 1L);
        }
        long now = ctx != null ? ctx.clock().now() : System.currentTimeMillis();
        if (now - _lastShedLog < SHED_LOG_THROTTLE_MS) {return;}
        _lastShedLog = now;
        if (_log.shouldWarn())
            _log.warn("[HTTPServer] Executor pool saturated, rejecting request " + tunnelId
                      + (peerB32 != null ? "\n* Client: " + peerB32 : ""));
    }

    /** Minimum interval between shed warnings for this listener. */
    private static final long SHED_LOG_THROTTLE_MS = 5 * 60 * 1000L;
    /** When the last overload shed was logged. */
    private volatile long _lastShedLog;

    /**
     * Maximum queued body tasks for an I/O pool with {@code threads} workers.
     * The queue is bounded so a saturated pool cannot park an unbounded number
     * of sockets with their browser and I2P streams open; overflow runs the
     * body on the handler thread instead (see {@link #handOffBody}), which
     * preserves a response whose headers were already sent. Package-visible
     * for tests.
     *
     * @param threads pool worker count (already floored)
     * @return queue capacity, at least 32
     * @since 0.9.71+
     */
    static int ioQueueCap(int threads) {
        return Math.max(32, threads * 4);
    }

    /**
     * Create a fixed-size I/O transfer pool. Package-visible for tests.
     *
     * @param threads fixed core/max worker count
     * @return non-null executor with a bounded queue ({@link #ioQueueCap(int)})
     * @since 0.9.71+
     */
    static ThreadPoolExecutor createIOExecutor(int threads) {
        int n = Math.max(ioPoolFloor(), threads);
        ThreadPoolExecutor ex = new ThreadPoolExecutor(n, n,
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(ioQueueCap(n)),
            r -> {
                Thread t = new Thread(r, "I2P-IO-Transfer");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    /**
     * Rebalance every live I/O pool from the global ioTransferThreads budget.
     * Must run under {@link #_ioExecutorLock}.
     * @since 0.9.71+
     */
    private static void rebalanceIOPools() {
        int n = _ioPools.size();
        if (n == 0) {return;}
        int budget = ioTransferThreads;
        int[] desired = new int[n];
        int w = 0;
        for (ThreadPoolExecutor ex : _ioPools.values()) {
            int load = ex != null && !ex.isShutdown()
                    ? ex.getActiveCount() + ex.getQueue().size() : 0;
            // An idle pool requests nothing rather than the whole budget. The old
            // code asked for the full budget from every pool, so an idle eepsite
            // had as much claim on a proportional cut as a tracker under load, and
            // the tracker's share fell as the tunnel count rose.
            desired[w++] = load > 0 ? budget : ioPoolFloor();
        }
        int[] alloc = TunnelControllerGroup.allocateServerThreads(budget, desired, ioPoolFloor());
        // Soft-floor: never starve a live tunnel below ioPoolFloor() even when
        // the global budget cannot cover every pool. Brief oversubscription is
        // preferable to body Senders queueing past the browser body-stall.
        for (int i = 0; i < alloc.length; i++) {
            if (alloc[i] < ioPoolFloor()) {alloc[i] = ioPoolFloor();}
        }
        int idx = 0;
        for (ThreadPoolExecutor ex : _ioPools.values()) {
            int want = alloc[idx++];
            if (ex == null || ex.isShutdown()) {continue;}
            resizeIOExecutor(ex, want);
        }
    }

    /**
     * Resize an I/O pool, preserving core == max.
     * @param ex the pool; ignored if null or shut down
     * @param newThreads the new fixed size
     * @since 0.9.71+
     */
    private static void resizeIOExecutor(ThreadPoolExecutor ex, int newThreads) {
        if (ex == null || ex.isShutdown()) {return;}
        if (newThreads > ex.getMaximumPoolSize()) {
            ex.setMaximumPoolSize(newThreads);
            ex.setCorePoolSize(newThreads);
        } else {
            ex.setCorePoolSize(newThreads);
            ex.setMaximumPoolSize(newThreads);
        }
    }

    /**
     * Deregister this tunnel's I/O pool on stop so its budget share is freed.
     * @since 0.9.71+
     */
    private void ioPoolStopped() {
        synchronized (_ioExecutorLock) {
            ThreadPoolExecutor ex = _ioPools.remove(this);
            if (ex != null) {ex.shutdown();}
            rebalanceIOPools();
        }
    }

    /**
     * Trigger a full I/O-pool rebalance after a global budget change (Tuner).
     * @since 0.9.71+
     */
    static void rebalanceAllIOPools() {
        synchronized (_ioExecutorLock) {
            rebalanceIOPools();
        }
    }

    /**
     * Get the saturation pressure summed across all live I/O pools:
     * running workers plus queued transfers. Used by the Tuner as the
     * reactive growth signal (queued bodies count as pressure so a backlog
     * grows the budget on the next cycle, not only when workers are full).
     *
     * @return active + queued transfers across pools, or 0 if none created
     * @since 0.9.71+
     */
    public static int getIOTransferActiveCount() {
        synchronized (_ioExecutorLock) {
            int total = 0;
            for (ThreadPoolExecutor ex : _ioPools.values()) {
                if (ex != null) {total += ex.getActiveCount() + ex.getQueue().size();}
            }
            return total;
        }
    }

    /**
     * The busiest pool's utilization: its active plus queued transfers divided
     * by its present maximum, as a rate that may exceed 1.0 when the queue is
     * backing up past the pool's ceiling.
     *
     * <p>The growth signal must be per-pool, not a global sum. Ten pools
     * sharing one budget means the summed pressure divided by the budget reads
     * low even when one pool is saturated — a tracker under load can sit at its
     * allocation while the global ratio stays below the growth step. This
     * returns the busiest pool's own utilization against its own maximum, so a
     * saturated pool is visible regardless of how quiet the others are.
     *
     * @return the busiest pool's utilization rate, or 0.0 when no pools exist
     * @since 0.9.71+
     */
    public static double getIOMaxPoolUtilization() {
        synchronized (_ioExecutorLock) {
            double max = 0.0d;
            for (ThreadPoolExecutor ex : _ioPools.values()) {
                if (ex == null || ex.isShutdown()) {continue;}
                int cap = ex.getMaximumPoolSize();
                if (cap <= 0) {continue;}
                double util = (double) (ex.getActiveCount() + ex.getQueue().size()) / (double) cap;
                if (util > max) {max = util;}
            }
            return max;
        }
    }

    /**
     * The floor for one I/O pool, proportional to the budget and pool count.
     *
     * <p>A fixed floor scales linearly with tunnel count and can consume the
     * whole budget before any load-based allocation — ten pools at a floor of
     * 8 leave 16 of 96 threads for load. The floor is a quarter of one pool's
     * fair share, so it shrinks as pools multiply and always leaves the
     * majority of the budget for pressure-based distribution.
     *
     * @return the per-pool floor, never below {@link #IO_THREADS_MIN}
     * @since 0.9.71+
     */
    static int ioPoolFloor() {
        synchronized (_ioExecutorLock) {
            int pools = Math.max(1, _ioPools.size());
            int share = Math.max(IO_THREADS_MIN, ioTransferThreads / (pools * 4));
            return share;
        }
    }

    /**
     * Set the I/O transfer pool size (called by Tuner via reflection).
     * Clamped to the absolute bounds only: the per-pool floor is derived from
     * the budget it would clamp against, so using it here would make the
     * setter path-dependent. A set could never lower the budget below a
     * quarter of its previous value. The per-pool floor is enforced where it
     * is used, at allocation in {@link #rebalanceIOPools}.
     *
     * @param val new pool size (clamped to [{@link #IO_THREADS_MIN}, {@link #IO_THREADS_MAX}])
     * @since 0.9.71+
     */
    public static void setIOTransferThreads(int val) {
        ioTransferThreads = Math.max(IO_THREADS_MIN, Math.min(IO_THREADS_MAX, val));
        rebalanceAllIOPools();
    }

    /**
     * Get the current I/O transfer pool size.
     *
     * @return current pool size
     * @since 0.9.71+
     */
    public static int getIOTransferThreads() { return ioTransferThreads; }

    /**
     * Set the I/O stall timeout in ms (called by Tuner via reflection).
     * <p>The floor matches the Tuner's own floor: a window below
     * {@link #STALL_WINDOW_FLOOR_MS} is shorter than any pause in a bulk
     * transfer, so it aborts healthy downloads rather than freeing threads.
     * Bulk bodies get a larger window still via
     * {@link #computeStallWindowMs}.
     *
     * @param val new timeout in ms (clamped to [STALL_WINDOW_FLOOR_MS, 300000])
     * @since 0.9.71+
     */
    public static void setIOStallTimeoutMs(long val) {
        ioStallTimeoutMs = Math.max(STALL_WINDOW_FLOOR_MS, Math.min(300_000L, val));
    }

    /**
     * Get the current I/O stall timeout in ms.
     *
     * @return current stall timeout
     * @since 0.9.71+
     */
    public static long getIOStallTimeoutMs() { return ioStallTimeoutMs; }

    /**
     * Get the total number of stall events detected by Sender since router start.
     * Each stall event means a Server→Client transfer was interrupted because
     * no data arrived for {@link #ioStallTimeoutMs}. Used by the Tuner as a
     * real-time signal for stall timeout adjustment.
     *
     * @return monotonically increasing stall event count
     * @since 0.9.71+
     */
    public static long getStallEventCount() { return _stallEventCount.get(); }

    /**
     * Publish a pool's load for the operator-facing view on /stats and the graphs
     * page.
     *
     * <p>Explicitly *not* the Tuner's input, despite the name matching its param:
     * {@code I2PTunnelServerIOTransferParam.getObservedStat} reads the live
     * {@link #getIOTransferActiveCount} total instead, on the fast five-second
     * cycle, so the ceiling reacts to load in seconds rather than to a rate
     * window. An earlier revision of this javadoc claimed this stat unblocked that
     * policy — it does not, and the frozen pool it was meant to explain was
     * caused by {@link #rebalanceIOPools} splitting the budget equally across
     * every pool, which left a busy tracker at the 8-thread floor alongside nine
     * idle eepsites. Load-weighting that rebalance is the fix; this stat is how an
     * operator sees the same pressure over time.
     *
     * @param ioPool the pool to sample; ignored if null or shut down
     * @since 0.9.71+
     */
    private static void sampleIOLoad(ThreadPoolExecutor ioPool) {
        if (ioPool == null || ioPool.isShutdown()) {return;}
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (ctx == null) {return;}
        ctx.statManager().createRequiredRateStat("i2ptunnel.serverIO.activeCount",
                "Active plus queued I/O transfer threads", "I2PTunnel",
                IO_TRANSFER_RATES);
        ctx.statManager().addRateData("i2ptunnel.serverIO.activeCount",
                                      ioPool.getActiveCount() + ioPool.getQueue().size());
    }

    /**
     * Hand off the response body to the I/O transfer pool, or run it inline
     * when the pool is null or shut down.
     *
     * @param pool the I/O executor to submit to; null or shut down forces an inline run
     * @param s the Sender to run; null does nothing and reports false
     * @param desc descriptive name for error messages
     * @return true if the body was submitted asynchronously, false if it ran inline
     * @throws IOException part of the handoff API's signature; the body reports every
     *                     failure through the Sender rather than throwing
     * @since 0.9.71+
     */
    static boolean handOffBody(ThreadPoolExecutor pool, Sender s, String desc)
            throws IOException {
        return handOffBody(pool, s, desc, null);
    }

    /**
     * As {@link #handOffBody(ThreadPoolExecutor, Sender, String)} with a
     * completion callback invoked after the Sender finishes (success,
     * failure, or abort), on the pool worker when submitted and on the
     * caller otherwise. The callback always runs, so a caller can rely on
     * it for connection teardown no matter which path was taken.
     *
     * @param pool the I/O executor to submit to; null or shut down forces an inline run
     * @param s the Sender to run; null runs the callback only and reports false
     * @param desc descriptive name for error messages
     * @param onCompletion may be null; must not throw
     * @return true if the body was submitted asynchronously, false if it ran
     *         inline or was aborted
     * @throws IOException part of the handoff API's signature; the body reports every
     *                     failure through the Sender rather than throwing
     * @since 0.9.71+
     */

    static boolean handOffBody(ThreadPoolExecutor pool, Sender s, String desc,
                               Runnable onCompletion) throws IOException {
        final Runnable completion = onCompletion;
        final Runnable task = () -> {
            try {
                if (s != null) {s.run();}
            } finally {
                if (completion != null) {
                    try {completion.run();}
                    catch (Throwable t) {
                        // Completion must not kill the pool worker
                        // (finish runs under AtomicBoolean; swallow here).
                    }
                }
            }
        };
        if (s == null) {
            task.run();
            return false;
        }
        if (pool == null || pool.isShutdown()) {
            task.run();
            return false;
        }
        // Publish the pool's load on every transfer so the operator-facing stat has
        // per-window history, not just the moments the pool was already full. No
        // growth happens here: raising the budget is the Tuner's job — it reads
        // getIOTransferActiveCount() live on the fast cycle and
        // setIOTransferThreads rebalances every pool — which leaves handOffBody's
        // abort below as the overload valve it is documented to be.
        sampleIOLoad(pool);
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException ree) {
            // The I/O pool is saturated. Do NOT run the body here: this is the
            // handler thread, and a multi-minute body would pin it for the
            // whole transfer, driving the runner pool to 0 free threads and
            // stalling every other request to this service. Abort instead so
            // the client fails fast and retries on a fresh connection.
            sampleIOLoad(pool);
            long aborts = _bodyAbortCount.incrementAndGet();
            s.abort(new IOException("I/O transfer pool saturated, body aborted (" + aborts
                                    + " this session): " + desc, ree));
            if (completion != null) {
                try {completion.run();}
                catch (Throwable t) {
                    // Same contract as the pool-worker path: never propagate.
                }
            }
            return false;
        }
    }

    /**
     * Decide whether an async body handoff has stalled. Pure decision helper
     * for the body watchdog: true when the Sender's last-read stamp is at
     * least the stall window old. A task that has not started yet (stamp 0)
     * never stalls — queueing behind a healthy long transfer is normal — and
     * a disabled window (&lt;= 0) never stalls.
     *
     * @param lastReadNanos Sender#getLastReadNanos(); 0 or negative if the
     * worker has not started the copy yet
     * @param nowNanos the current {@link java.lang.System#nanoTime}, same base as lastReadNanos
     * @param stallNs stall window in nanoseconds; &lt;= 0 disables
     * @return true if the watchdog should close the streams and finish
     * @since 0.9.71+
     */
    static boolean isBodyWatchdogStalled(long lastReadNanos, long nowNanos, long stallNs) {
        if (stallNs <= 0 || lastReadNanos <= 0)
            return false;
        return nowNanos - lastReadNanos >= stallNs;
    }

    /**
     * Stall window for a body that has already moved {@code bytesTransferred}.
     * <p>The configured window is right for a small response but wrong for a
     * bulk one: over a congested path a 45MB transfer routinely pauses for
     * many seconds between copy steps, and a single fixed window either kills
     * the transfer or, if sized for the bulk case, pins a worker for minutes
     * behind a dead peer. The response {@code Content-Length} is not available
     * here (it is never parsed, and gzip and chunked bodies have no useful
     * declared length anyway), so the window scales with progress instead: a
     * body that has already moved megabytes has demonstrably earned patience.
     *
     * @param baseMs configured window, {@link #ioStallTimeoutMs}
     * @param bytesTransferred bytes already moved by the body
     * @return the window to apply, ms
     * @since 0.9.71+
     */
    static long computeStallWindowMs(long baseMs, long bytesTransferred) {
        if (baseMs <= 0)
            return 0;
        long scale = 1;
        if (bytesTransferred >= BULK_STALL_32M) scale = 8;
        else if (bytesTransferred >= BULK_STALL_8M) scale = 4;
        else if (bytesTransferred >= BULK_STALL_1M) scale = 2;
        long scaled = baseMs * scale;
        return Math.min(MAX_STALL_WINDOW_MS, scaled);
    }

    /** Progress thresholds for {@link #computeStallWindowMs}. @since 0.9.71+. */
    private static final long BULK_STALL_1M = 1024L * 1024L;
    /**
     * Body size where the bulk-transfer stall begins.
     *
     * @since 0.9.71+
     */
    private static final long BULK_STALL_8M = 8L * 1024L * 1024L;
    /**
     * Body size where the bulk-transfer stall is at full delay.
     *
     * @since 0.9.71+
     */
    private static final long BULK_STALL_32M = 32L * 1024L * 1024L;
    /**
     * Hard ceiling on a progress-scaled window, ms. Above the top progress
     * tier (8x) so a raised base window still cannot push a body past ten
     * minutes without a stall.
     * @since 0.9.71+
     */
    static final long MAX_STALL_WINDOW_MS = 600_000L;

    /** Stall side reported when both stamps are equally stale. @since 0.9.71+. */
    static final String STALL_SIDE_INDETERMINATE = "source or destination (indeterminate)";
    /**
     * Stall side reported when the source moved nothing and only a read blocked.
     *
     * @since 0.9.71+
     */
    static final String STALL_SIDE_SOURCE = "source (local backend silent)";
    /**
     * Stall side reported when the destination moved nothing.
     *
     * @since 0.9.71+
     */
    static final String STALL_SIDE_DESTINATION = "destination (I2P egress congested)";

    /**
     * Which side of a stalled body transfer stopped making progress.
     * <p>Read and write stamps bracket the same copy step from opposite sides:
     * the read stamp advances before the write runs, the write stamp only
     * after it returns. A body blocked mid-write therefore leaves the read
     * stamp fresh and the write stamp old; a body blocked mid-read leaves the
     * write stamp fresh (from the last completed write) and the read stamp
     * old. The two cases never look identical while a transfer is in flight —
     * the age comparison alone separates them.
     * <p>Equal stamps occur only before the first read completes, when both
     * still hold the start value: nothing has been read from the source yet,
     * so the source is the silent side.
     * <p>Reporting the ambiguous case as the source is actively misleading: a
     * 45MB download that stalled after 3.2MB was logged as "local backend
     * silent" while the same backend served the whole file in 4ms on a fresh
     * connection, sending every diagnosis to the wrong layer.
     *
     * @param readAgeMs age of the last-read stamp
     * @param writeAgeMs age of the last-write stamp
     * @param bytesTransferred bytes already moved by the body
     * @return one of {@link #STALL_SIDE_SOURCE}, {@link #STALL_SIDE_DESTINATION},
     *         or {@link #STALL_SIDE_INDETERMINATE}
     * @since 0.9.71+
     */
    static String classifyStalledSide(long readAgeMs, long writeAgeMs, long bytesTransferred) {
        // Write fresher than read: the sink is moving, so the source went quiet.
        if (writeAgeMs < readAgeMs)
            return STALL_SIDE_SOURCE;
        // Read fresher than write: the source is moving, so the sink backed up.
        if (readAgeMs < writeAgeMs)
            return STALL_SIDE_DESTINATION;
        // Equally stale: only possible before the first read returns (both stamps
        // still at the start value) — the source has sent nothing.
        if (bytesTransferred == 0)
            return STALL_SIDE_SOURCE;
        // Post-progress nanoTime tie: too close to call.
        return STALL_SIDE_INDETERMINATE;
    }

    /**
     * Close a stream, ignoring failures. Used by stall/teardown paths where
     * the close is best-effort (the stream may already be dead) and the goal
     * is only to unblock a thread parked in read or write.
     *
     * @param c stream to close; ignored if null
     * @since 0.9.71+
     */
    private static void closeQuietly(Closeable c) {
        if (c == null) {return;}
        try { c.close(); }
        catch (IOException ioe) { /* ignored */ }
    }

    /** Config key to reject requests from inproxy. */
    public static final String OPT_REJECT_INPROXY = "rejectInproxy";
    /** Config key to reject requests with Referer header. */
    public static final String OPT_REJECT_REFERER = "rejectReferer";
    /** Config key to reject specific User-Agents. */
    public static final String OPT_REJECT_USER_AGENTS = "rejectUserAgents";
    /** Config key for User-Agent reject list. */
    public static final String OPT_USER_AGENTS = "userAgentRejectList";
    /** Config key to enable keepalive (legacy short form; see PROP_KEEPALIVE). */
    public static final String OPT_KEEPALIVE = "keepalive.i2p";
    /**
     * Config key to enable HTTP persistent connections; live via optionsUpdated.
     * Falls back to the legacy {@link #OPT_KEEPALIVE} short key when unset.
     * Key: {@code tunnel.N.option.i2ptunnel.server.keepalive}.
     * @since 0.9.71+
     */
    public static final String PROP_KEEPALIVE = "i2ptunnel.server.keepalive";
    /**
     * Config key for the idle keepalive wait before closing a persistent
     * connection, in ms. Replaces the hard-coded 130s wait so a browser that
     * goes away does not pin a handler thread for over two minutes.
     * Key: {@code tunnel.N.option.i2ptunnel.server.keepAliveTimeout}.
     * @since 0.9.71+
     */
    public static final String PROP_KEEPALIVE_TIMEOUT = "i2ptunnel.server.keepAliveTimeout";
    /**
     * Config key for the first-request header read timeout, in ms.
     * Key: {@code tunnel.N.option.i2ptunnel.server.headerTimeout}.
     * @since 0.9.71+
     */
    public static final String PROP_HEADER_TIMEOUT = "i2ptunnel.server.headerTimeout";
    /**
     * Default idle keepalive wait (ms); short enough to free the handler
     * thread, long enough for a browser to reuse the connection.
     */
    public static final long DEFAULT_KEEPALIVE_TIMEOUT_MS = 10 * 1000L;
    /**
     * Default first-request header timeout (ms).  30s matches the pre-config
     * hard-coded value and gives slow eepsites time to start responding before
     * the 408 kill fires (which aborts Range-resume downloads mid-body).
     */
    public static final long DEFAULT_HEADER_TIMEOUT_MS = 30 * 1000L;
    /** Bounds for {@link #PROP_KEEPALIVE_TIMEOUT} (ms). */
    public static final long MIN_KEEPALIVE_TIMEOUT_MS = 1000L;
    /** Ceiling for {@link #PROP_KEEPALIVE_TIMEOUT} (ms). */
    public static final long MAX_KEEPALIVE_TIMEOUT_MS = 300 * 1000L;
    /** Bounds for {@link #PROP_HEADER_TIMEOUT} (ms). */
    public static final long MIN_HEADER_TIMEOUT_MS = 1000L;
    /** Ceiling for {@link #PROP_HEADER_TIMEOUT} (ms). */
    public static final long MAX_HEADER_TIMEOUT_MS = 120 * 1000L;
    /** Config key to add Allow response header. */
    public static final String OPT_ADD_RESPONSE_HEADER_ALLOW = "addResponseHeaderAllow";
    /** Config key to add Cache-Control response header. */
    public static final String OPT_ADD_RESPONSE_HEADER_CACHE_CONTROL = "addResponseHeaderCacheControl";
    /** Config key to add X-Content-Type-Options: nosniff. */
    public static final String OPT_ADD_RESPONSE_HEADER_NOSNIFF = "addResponseHeaderNoSniff";
    /** Config key to add Referrer-Policy response header. */
    public static final String OPT_ADD_RESPONSE_HEADER_REFERRER_POLICY = "addResponseHeaderReferrerPolicy";

    /** The hostname to inject as the Host header, or null for passthrough. */
    private String _spoofHost;

    /** Client request headers to remove. */
    private static final String HASH_HEADER = "X-I2P-DestHash";
    /** Header carrying the server's destination in base64. */
    private static final String DEST64_HEADER = "X-I2P-DestB64";
    /** Header carrying the server's destination in base32. */
    private static final String DEST32_HEADER = "X-I2P-DestB32";
    /** The Proxy-Connection header. */
    private static final String PROXY_CONN_HEADER = "proxy-connection";
    /** Header carrying the browser's request priority. */
    private static final String PRIORITY_HEADER = "Priority";
    /** Header carrying the Global Privacy Control signal. */
    private static final String SEC_GPC_HEADER = "Sec-GPC";
    /** Header a proxy uses to name the client address. */
    private static final String X_REAL_IP_HEADER = "X-Real-Ip";

    /** Headers stripped from the client's request. */
    private static final Set<String> CLIENT_SKIPHEADERS = new HashSet<>();
    /** Headers stripped from the server's response. */
    private static final Set<String> SERVER_SKIPHEADERS = new HashSet<>();

    /** Age header, stripped for anonymity. */
    private static final String AGE_HEADER = "age"; // possible anonymity implications, informational
    /** Alt-Svc header pointing elsewhere, stripped. */
    private static final String ALT_SVC_HEADER = "alt-svc"; // superfluous
    /** Date header, replaced by the proxy. */
    private static final String DATE_HEADER = "date";
    /** Expires header, stripped as it conflicts with cache-control. */
    private static final String EXPIRES_HEADER = "expires"; // not needed with cache-control max-age, and php inserts erroneous header
    /** Obsolete Pragma header, stripped. */
    private static final String PRAGMA_HEADER = "pragma"; // obsolete
    /** Referer header, stripped as it does not belong in responses. */
    private static final String REFERER_HEADER = "referer"; // shouldn't be in response headers
    /** Server header naming the web server, stripped. */
    private static final String SERVER_HEADER = "server";
    /** HSTS header, useless through the proxy, stripped. */
    private static final String STRICT_TRANSPORT_SECURITY_HEADER = "strict-transport-security"; // superfluous
    /** Via header listing proxies, stripped for anonymity. */
    private static final String VIA_HEADER = "via"; // possible anonymity implications, informational
    /** X-Cache header revealing cache state, stripped. */
    private static final String X_CACHE_HEADER = "x-cache"; // possible anonymity implications, informational
    /** X-Cache-Hits header revealing cache state, stripped. */
    private static final String X_CACHE_HITS_HEADER = "x-cache-hits"; // possible anonymity implications, informational
    /** Google Cloud trace header, stripped. */
    private static final String X_CLOUD_TRACE_CONTEXT_HEADER = "x-cloud-trace-context"; // superfluous
    /** X-Contextid header, stripped. */
    private static final String X_CONTEXTID_HEADER = "x-contextid";
    /** Google Cloud Storage generation header, stripped. */
    private static final String X_GOOG_GENERATION_HEADER = "x-goog-generation"; // superfluous
    /** Google Cloud Storage hash header, stripped. */
    private static final String X_GOOG_HASH_HEADER = "x-goog-hash"; // superfluous
    /** Google upload id header, stripped. */
    private static final String X_GUPLOADER_UPLOADID_HEADER = "x-guploader-uploadid"; // superfluous
    /** Wordpress X-Hacker header, stripped. */
    private static final String X_HACKER_HEADER = "x-hacker"; // Wordpress
    /** Wordpress Batcache header, stripped. */
    private static final String X_NANANANA_HEADER = "x-nananana"; // wordpress batcache, informational
    /** Pantheon hosting host name, stripped for anonymity. */
    private static final String X_PANTHEON_STYX_HOSTNAME_HEADER = "x-pantheon-styx-hostname"; // possible anonymity implications, informational
    /** X-Powered-By header naming the framework, stripped. */
    private static final String X_POWERED_BY_HEADER = "x-powered-by";
    /** Rails runtime header, stripped. */
    private static final String X_RUNTIME_HEADER = "x-runtime"; // Rails
    /** Host that served the response, stripped for anonymity. */
    private static final String X_SERVED_BY_HEADER = "x-served-by"; // possible anonymity implications, informational
    /** Request id on Pantheon responses, stripped. */
    private static final String X_STYX_REQ_ID_HEADER = "x-styx-req-id"; // possible anonymity implications, informational

    /**
     * Proxy header, checked against the httpoxy attack.
     * See also: <a href="https://httpoxy.org">httpoxy</a>
     */
    private static final String PROXY_HEADER = "proxy";
    /** Pattern splitting a request line at CRLF. */
    private static final Pattern NEWLINE_SPLIT = Pattern.compile("\r\n");
    /** Pattern splitting a request line at spaces. */
    private static final Pattern SPACE_SPLIT = Pattern.compile(" ");
    /** Pattern splitting a path at slashes. */
    private static final Pattern SLASH_SPLIT = Pattern.compile("/");

    static {
        CLIENT_SKIPHEADERS.add(HASH_HEADER.toLowerCase(Locale.US));
        CLIENT_SKIPHEADERS.add(DEST64_HEADER.toLowerCase(Locale.US));
        CLIENT_SKIPHEADERS.add(DEST32_HEADER.toLowerCase(Locale.US));
        CLIENT_SKIPHEADERS.add(PRIORITY_HEADER.toLowerCase(Locale.US));
        CLIENT_SKIPHEADERS.add(PROXY_CONN_HEADER);
        CLIENT_SKIPHEADERS.add(SEC_GPC_HEADER.toLowerCase(Locale.US));
        CLIENT_SKIPHEADERS.add(X_REAL_IP_HEADER.toLowerCase(Locale.US));

        SERVER_SKIPHEADERS.add(AGE_HEADER);
        SERVER_SKIPHEADERS.add(ALT_SVC_HEADER);
        SERVER_SKIPHEADERS.add(DATE_HEADER);
        SERVER_SKIPHEADERS.add(EXPIRES_HEADER);
        SERVER_SKIPHEADERS.add(PRAGMA_HEADER);
        SERVER_SKIPHEADERS.add(PROXY_CONN_HEADER);
        SERVER_SKIPHEADERS.add(PROXY_HEADER);
        SERVER_SKIPHEADERS.add(REFERER_HEADER);
        SERVER_SKIPHEADERS.add(SERVER_HEADER);
        SERVER_SKIPHEADERS.add(STRICT_TRANSPORT_SECURITY_HEADER);
        SERVER_SKIPHEADERS.add(VIA_HEADER);
        SERVER_SKIPHEADERS.add(X_CACHE_HEADER);
        SERVER_SKIPHEADERS.add(X_CACHE_HITS_HEADER);
        SERVER_SKIPHEADERS.add(X_CLOUD_TRACE_CONTEXT_HEADER);
        SERVER_SKIPHEADERS.add(X_CONTEXTID_HEADER);
        SERVER_SKIPHEADERS.add(X_GOOG_GENERATION_HEADER);
        SERVER_SKIPHEADERS.add(X_GOOG_HASH_HEADER);
        SERVER_SKIPHEADERS.add(X_GUPLOADER_UPLOADID_HEADER);
        SERVER_SKIPHEADERS.add(X_HACKER_HEADER);
        SERVER_SKIPHEADERS.add(X_NANANANA_HEADER);
        SERVER_SKIPHEADERS.add(X_PANTHEON_STYX_HOSTNAME_HEADER);
        SERVER_SKIPHEADERS.add(X_POWERED_BY_HEADER);
        SERVER_SKIPHEADERS.add(X_RUNTIME_HEADER);
        SERVER_SKIPHEADERS.add(X_SERVED_BY_HEADER);
        SERVER_SKIPHEADERS.add(X_STYX_REQ_ID_HEADER);
    }

    /**
     * Live first-request header timeout (ms); updated by optionsUpdated.
     * Was a hard-coded 30s constant.
     * @since 0.9.71+
     */
    private volatile long _headerTimeoutMs = DEFAULT_HEADER_TIMEOUT_MS;
    /**
     * Live idle keepalive wait (ms); updated by optionsUpdated.
     * Was a hard-coded 130s wait.
     * @since 0.9.71+
     */
    private volatile long _keepAliveTimeoutMs = DEFAULT_KEEPALIVE_TIMEOUT_MS;
    /**
     * Whether HTTP persistent connections are accepted; updated by optionsUpdated.
     * @since 0.9.71+
     */
    private volatile boolean _keepAlive = DEFAULT_KEEPALIVE;
    /**
     * Grace period for remaining header lines after the first line, on top of
     * the initial header timeout (slowloris bound).
     */
    private static final long HEADER_FINISH_TIMEOUT = DEFAULT_HEADER_TIMEOUT_MS;
    /** Min time before socket error is escalated to ERROR level. */
    private static final long START_INTERVAL = (60 * 1000) * 3;
    /** Longest request header line accepted. */
    private static final int MAX_LINE_LENGTH = 8*1024;
    /**
     * Ridiculously long, just to prevent OOM DOS.
     * @since 0.7.13
     */
    private static final int MAX_HEADERS = 60;
    /**
     * Includes request, just to prevent OOM DOS.
     * @since 0.9.20
     */
    private static final int MAX_TOTAL_HEADER_SIZE = 32*1024;
    /**
     * Default read timeout for the server socket, -1 for none.
     * It does not apply to header reads; forever keeps a large
     * response from timing out, and the web server presumably
     * implements its own timeout for POST requests.
     */
    private static final long DEFAULT_HTTP_READ_TIMEOUT = -1;
    /**
     * Read timeout while reading a GET request, relatively
     * short; POST and CONNECT get a long failsafe timeout
     * since the user may be uploading a massive file.
     */
    private static final int SERVER_READ_TIMEOUT_GET = 60*1000;
    /** Read timeout for medium uploads. */
    private static final int SERVER_READ_TIMEOUT_MEDIUM = 3*60*1000;
    /** Read timeout while reading a POST body. */
    private static final int SERVER_READ_TIMEOUT_POST = 5*60*1000;

    /** Timestamp when the tunnel started, used for error-level escalation timing. */
    private long _startedOn = 0L;
    /** POST/PUT throttler for rate limiting. */
    private ConnThrottler _postThrottler;
    /**
     * Max clients tracked by the HTTP blocklist.
     *
     * @since 0.9.62+
     */
    private static final int HTTP_BLOCKLIST_CLIENT_LIMIT = 512;
    /** HTTP blocklist manager. */
    private BlocklistManager _blocklistManager;

    /** 404 Not Found error response. */
    final static String ERR_NOT_FOUND =
         "HTTP/1.1 404 Not Found\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n" +
         "<head><title>404 Not Found</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<body>\n" +
         "<center><h1>404 Not Found</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";

    /** 503 Service Unavailable error response. */
    private final static String ERR_UNAVAILABLE =
         "HTTP/1.1 503 Service Unavailable\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n" +
         "<head><title>503 Service Temporarily Unavailable</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<body>\n" +
         "<center><h1>503 Service Temporarily Unavailable</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";

    /** 429 Too Many Requests error response. */
    private final static String ERR_DENIED =
         "HTTP/1.1 429 Too Many Requests\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Retry-After: 600\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n" +
         "<head><title>429 Too Many Requests</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<body>\n" +
         "<center><h1>429 Too Many Requests</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";

    /** 403 Forbidden error response. */
    final static String ERR_FORBIDDEN =

         "HTTP/1.1 403 Denied\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n"+
         "Cache-Control: no-cache\r\n"+
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n" +
         "<head><title>403 Forbidden</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<body>\n" +
         "<center><h1>403 Forbidden</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";

    /** 414 Request URI too long error response. */
    private final static String ERR_REQUEST_URI_TOO_LONG =
         "HTTP/1.1 414 Request URI too long\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n" +
         "<head><title>414 Request URI Too Long</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<body>\n" +
         "<center><h1>414 Request URI too long</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";

    /** 431 Request header fields too large error response. */
    private final static String ERR_HEADERS_TOO_LARGE =
         "HTTP/1.1 431 Request header fields too large\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n<head><title>431 Request Header Fields Too Large</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<center><h1>431 Request header fields too large</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";

    /**
     * HTTP 408 Request Timeout error response.
     * Includes a meta refresh to retry after 5 seconds.
     *
     * @since 0.9.33 made protected for I2PTunnelHTTPClientBase, was private
     */
    protected final static String ERR_REQUEST_TIMEOUT =
         "HTTP/1.1 408 Request timeout\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n" +
         "<head><meta http-equiv=\"refresh\" content=\"5\"></head>\n" +
         "</html>";

    /** 400 Bad Request error response. */
    private final static String ERR_BAD_REQUEST =
         "HTTP/1.1 400 Bad Request\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n<head><title>400 Bad Request</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<center><h1>400 Bad request (malformed datastream)</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";

    /**
     * Create an HTTP tunnel server with a private key string.
     *
     * @param host the local address to bind to
     * @param port the local port
     * @param privData the private key data as a Base64 string
     * @param spoofHost the hostname to spoof in the Host header, or null
     * @param l the logging instance
     * @param notifyThis the event dispatcher for notifications
     * @param tunnel the parent tunnel configuration
     */
    public I2PTunnelHTTPServer(InetAddress host, int port, String privData, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    /**
     * Create an HTTP tunnel server from a private key file.
     *
     * @param host the local address to bind to
     * @param port the local port
     * @param privkey the private key file
     * @param privkeyname the name for the private key
     * @param spoofHost the hostname to spoof in the Host header, or null
     * @param l the logging instance
     * @param notifyThis the event dispatcher for notifications
     * @param tunnel the parent tunnel configuration
     */
    public I2PTunnelHTTPServer(InetAddress host, int port, File privkey, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privkey, privkeyname, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    /**
     * Create an HTTP tunnel server from a private key stream.
     *
     * @param host the local address to bind to
     * @param port the local port
     * @param privData the input stream containing the private key data
     * @param privkeyname the name for the private key
     * @param spoofHost the hostname to spoof in the Host header, or null
     * @param l the logging instance
     * @param notifyThis the event dispatcher for notifications
     * @param tunnel the parent tunnel configuration
     */
    public I2PTunnelHTTPServer(InetAddress host, int port, InputStream privData, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, privkeyname, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    /**
     * Common initialization for all constructors.
     * Sets the spoofed host, creates stats, and initializes the blocklist manager.
     *
     * @param spoofHost the hostname to spoof in the Host header, or null for no spoofing
     */
    private void setupI2PTunnelHTTPServer(String spoofHost) {
        _spoofHost = (spoofHost != null && !spoofHost.trim().isEmpty()) ? spoofHost.trim() : null;
        getTunnel().getContext().statManager().createRateStat("i2ptunnel.httpserver.blockingHandleTime",
                                                              "How long the blocking handle takes to complete",
                                                              "Tunnels [HTTPServer]", IO_TRANSFER_RATES);
        readTimeout = DEFAULT_HTTP_READ_TIMEOUT;
        _blocklistManager = new BlocklistManager(_log, HTTP_BLOCKLIST_CLIENT_LIMIT);
    }

    /**
     * Start the tunnel and initialize timing and throttling.
     */
    @Override
    public void startRunning() {
        super.startRunning();
        // Would be better if this was set when the inbound tunnel becomes alive.
        _startedOn = getTunnel().getContext().clock().now();
        setupPostThrottle();
    }

    /**
     * Initialize or update the POST/PUT throttler from tunnel options.
     * Creates a new ConnThrottler if limits are configured, or updates existing.
     *
     * @since 0.9.9
     */
    private void setupPostThrottle() {
        int pp = getIntOption(OPT_POST_MAX, 0);
        int pt = getIntOption(OPT_POST_TOTAL_MAX, 0);
        synchronized(this) {
            if (pp != 0 || pt != 0 || _postThrottler != null) {
                long pw = 1000L * getIntOption(OPT_POST_WINDOW, DEFAULT_POST_WINDOW);
                long pb = 1000L * getIntOption(OPT_POST_BAN_TIME, DEFAULT_POST_BAN_TIME);
                long px = 1000L * getIntOption(OPT_POST_TOTAL_BAN_TIME, DEFAULT_POST_TOTAL_BAN_TIME);
                if (_postThrottler == null)
                    _postThrottler = new ConnThrottler(pp, pt, pw, pb, px, "POST/PUT", _log);
                else
                    _postThrottler.updateLimits(pp, pt, pw, pb, px);
                _postThrottler.start();
            }
        }
    }

    /**
     * Parse the keepalive enable flag from tunnel options.
     * Prefers {@link #PROP_KEEPALIVE}; falls back to the legacy
     * {@link #OPT_KEEPALIVE} short key; defaults to true when unset.
     *
     * @param opts the client options; may be null
     * @return true if persistent connections are enabled
     * @since 0.9.71+
     */
    static boolean parseKeepAlive(Properties opts) {
        if (opts == null) {return DEFAULT_KEEPALIVE;}
        String v = opts.getProperty(PROP_KEEPALIVE);
        if (v == null) {v = opts.getProperty(OPT_KEEPALIVE);}
        if (v == null) {return DEFAULT_KEEPALIVE;}
        return Boolean.parseBoolean(v.trim());
    }

    /**
     * Parse and clamp the idle keepalive timeout (ms) from tunnel options.
     * Unparseable values fall back to the default; out-of-range values clamp.
     *
     * @param opts the client options; may be null
     * @return timeout in ms within [{@link #MIN_KEEPALIVE_TIMEOUT_MS},
     *         {@link #MAX_KEEPALIVE_TIMEOUT_MS}]
     * @since 0.9.71+
     */
    static long parseKeepAliveTimeout(Properties opts) {
        return parseTimeoutMs(opts, PROP_KEEPALIVE_TIMEOUT, DEFAULT_KEEPALIVE_TIMEOUT_MS,
                              MIN_KEEPALIVE_TIMEOUT_MS, MAX_KEEPALIVE_TIMEOUT_MS);
    }

    /**
     * Parse and clamp the first-request header timeout (ms) from tunnel options.
     *
     * @param opts the client options; may be null
     * @return timeout in ms within [{@link #MIN_HEADER_TIMEOUT_MS},
     *         {@link #MAX_HEADER_TIMEOUT_MS}]
     * @since 0.9.71+
     */
    static long parseHeaderTimeout(Properties opts) {
        return parseTimeoutMs(opts, PROP_HEADER_TIMEOUT, DEFAULT_HEADER_TIMEOUT_MS,
                              MIN_HEADER_TIMEOUT_MS, MAX_HEADER_TIMEOUT_MS);
    }

    /**
     * Shared timeout parser: default on missing/unparseable, clamp to [min, max].
     *
     * @param opts the options; may be null
     * @param key the property key
     * @param dflt default when unset or not a number
     * @param min inclusive lower bound
     * @param max inclusive upper bound
     * @return the parsed and clamped timeout in ms
     * @since 0.9.71+
     */
    private static long parseTimeoutMs(Properties opts, String key, long dflt, long min, long max) {
        if (opts == null) {return dflt;}
        String v = opts.getProperty(key);
        if (v == null) {return dflt;}
        try {
            long t = Long.parseLong(v.trim());
            return Math.max(min, Math.min(max, t));
        } catch (NumberFormatException nfe) {
            return dflt;
        }
    }

      /**
       * Whether a client may reuse one connection for several requests.
       *
       * @return whether HTTP persistent connections are currently enabled
       * @since 0.9.71+
       */
    public boolean isKeepAlive() {return _keepAlive;}

    /**
     * Enable/disable HTTP persistent connections without a tunnel restart.
     * @param on true to accept persistent connections
     * @since 0.9.71+
     */
    public void setKeepAlive(boolean on) {_keepAlive = on;}

      /**
       * How long an idle keepalive connection is held before it is probed.
       *
       * @return idle keepalive wait in ms
       * @since 0.9.71+
       */
    public long getKeepAliveTimeout() {return _keepAliveTimeoutMs;}

    /**
     * Set the idle keepalive wait, clamped to the configured bounds.
     * @param ms timeout in ms
     * @since 0.9.71+
     */
    public void setKeepAliveTimeout(long ms) {
        _keepAliveTimeoutMs = Math.max(MIN_KEEPALIVE_TIMEOUT_MS, Math.min(MAX_KEEPALIVE_TIMEOUT_MS, ms));
    }

      /**
       * How long the first request's headers may take to arrive.
       *
       * @return first-request header timeout in ms
       * @since 0.9.71+
       */
    public long getHeaderTimeout() {return _headerTimeoutMs;}

    /**
     * Set the first-request header timeout, clamped to the configured bounds.
     * @param ms timeout in ms
     * @since 0.9.71+
     */
    public void setHeaderTimeout(long ms) {
        _headerTimeoutMs = Math.max(MIN_HEADER_TIMEOUT_MS, Math.min(MAX_HEADER_TIMEOUT_MS, ms));
    }

    /**
     * Get an integer option from the tunnel client options.
     *
     * @param opt the option key
     * @param dflt the default value if not set or invalid
     * @return the option value, or dflt
     * @since 0.9.9
     */
    private int getIntOption(String opt, int dflt) {
        Properties opts = getTunnel().getClientOptions();
        String o = opts.getProperty(opt);
        if (o != null) {
            try {return Integer.parseInt(o);}
            catch (NumberFormatException nfe) { /* ignored */ }
        }
        return dflt;
    }

    /**
     * Close the tunnel and stop the POST throttler.
     *
     * @param forced true to force close
     * @return true if closed successfully
     * @since 0.9.9
     */
    @Override
    public boolean close(boolean forced) {
        synchronized(this) {
            if (_postThrottler != null) {_postThrottler.stop();}
        }
        boolean closed = super.close(forced);
        if (closed) {ioPoolStopped();}
        return closed;
    }

    /**
     * Called when tunnel options are updated.
     * Re-initializes the POST throttler and updates the spoofed host.
     *
     * @param tunnel tunnel that was updated; ignored unless it is the tunnel we serve
     * @since 0.9.9
     */
    @Override
    public void optionsUpdated(I2PTunnel tunnel) {
        if (getTunnel() != tunnel) {return;}
        setupPostThrottle();
        Properties props = tunnel.getClientOptions();
        // see TunnelController.setSessionOptions()
        String spoofHost = props.getProperty(TunnelController.PROP_SPOOFED_HOST);
        _spoofHost = (spoofHost != null && !spoofHost.trim().isEmpty()) ? spoofHost.trim() : null;
        _keepAlive = parseKeepAlive(props);
        _keepAliveTimeoutMs = parseKeepAliveTimeout(props);
        _headerTimeoutMs = parseHeaderTimeout(props);
        super.optionsUpdated(tunnel);
    }

    /**
     * Short human-readable identifier for this HTTP server tunnel, e.g.
     * "[skank / 8charB32]", matching the outbound tunnel log format
     * "[nickname / b32prefix]" so related warnings can be correlated across
     * the router and the i2ptunnel logs.  A trailing ".i2p" on the configured
     * nickname is stripped to match that format.
     *
     * @param localDest this tunnel's own destination, may be null
     * @return the "[nickname / prefix]" label, never null
     */
    private String getTunnelLogId(Destination localDest) {
        String nickname = getTunnel().getClientOptions().getProperty("inbound.nickname");
        if (nickname != null) {
            String lower = nickname.toLowerCase(Locale.US);
            if (lower.endsWith(".i2p")) {nickname = nickname.substring(0, nickname.length() - 4);}
        }
        String prefix = "";
        if (localDest != null) {
            String b32 = localDest.toBase32();
            prefix = b32.substring(0, Math.min(8, b32.length()));
        }
        return "[" + (nickname != null ? nickname : "?") + " / " + prefix + "]";
    }

    /**
     * Called by the thread pool of I2PSocket handlers.
     * Handles a single client connection through the HTTP proxy lifecycle:
     * read headers, validate, apply spoofing, compress, and forward.
     *
     * @param socket the incoming I2P socket from the client
     */
    @Override
    protected void blockingHandle(I2PSocket socket) {
        if (socket == null) {return;}
        Hash peerHash = socket.getPeerDestination() != null ? socket.getPeerDestination().calculateHash() : null;
        if (peerHash == null) {return;}
        String peerB32 = socket.getPeerDestination().toBase32();
        String tunnelId = getTunnelLogId(socket.getThisDestination());
        if (_log.shouldDebug()) {
            _log.debug("[HTTPServer] Incoming connection to " + toString().replace("/", "") + ":" + socket.getLocalPort() +
                       " " + tunnelId + "\n* From: " + peerB32 + " on port " + socket.getPort());
        }
        // local is fast, so synchronously. Does not need that many threads.
        try {
            if (handlePort443(socket))
                return;

            long afterAccept = getTunnel().getContext().clock().now();
            int requestCount = 0;
            boolean keepalive = _keepAlive;
            long headerTimeout = _headerTimeoutMs;
            long keepAliveTimeout = _keepAliveTimeoutMs;

            do {
                if (requestCount > 0 && _log.shouldDebug()) {
                    _log.debug("[HTTPServer] KeepAlive " + tunnelId + " -> Awaiting request #" + requestCount);
                }

                // The headers _should_ be in the first packet, but may not be, depending on the client-side options

                StringBuilder command = new StringBuilder(128);
                Map<String, List<String>> headers = readRequestHeaders(socket, command, requestCount, peerB32,
                                                                       getTunnel().getContext(), _log, tunnelId,
                                                                       headerTimeout, keepAliveTimeout);
                if (headers == null) {return;}

                if (!validateRequestHost(headers, socket, peerB32)) {return;}

                long afterHeaders = getTunnel().getContext().clock().now();
                Properties opts = getTunnel().getClientOptions();

                if (isInproxyRejection(headers, socket, peerB32, opts))
                    return;

                if (isRefererRejection(headers, socket, peerB32, opts))
                    return;

                if (isUserAgentRejection(headers, socket, peerB32, opts))
                    return;

                if (isPostThrottled(command, peerHash, socket, peerB32))
                    return;

                addEntry(headers, HASH_HEADER, peerHash.toBase64());
                addEntry(headers, DEST32_HEADER, peerB32);
                addEntry(headers, DEST64_HEADER, socket.getPeerDestination().toBase64());

                applySpoofedHost(socket, headers, opts);

                // Force Connection: close, unless websocket
                boolean upgrade = false;
                int connType = getConnectionType(headers);
                if (connType == CONN_UPGRADE || connType == CONN_CLOSE) {keepalive = false;}
                if (connType == CONN_UPGRADE) {upgrade = true;}
                else {setEntry(headers, "Connection", "close");}

                // process http_blocklist.txt entries
                if (command.length() > 0) {processBlocklist(socket, command);}

                // HTTP Persistent Connections (RFC 2616) for the I2P socket.
                // Keep it very simple.
                // Will be set to false for non-GET/HEAD, non-HTTP/1.1,
                // Connection: close, InternalSocket,
                // or after analysis of the response headers in CompressedOutputStream,
                // or on errors in I2PTunnelRunner.
                // We do NOT support keepalive on the server socket.
                String cmd = command.toString().trim();
                if (!isKeepAliveRequest(cmd)) {keepalive = false;}

                // we keep the enc sent by the browser before clobbering it, since it may have been x-i2p-gzip
                String enc = getEntryOrNull(headers, "Accept-Encoding");
                String altEnc = getEntryOrNull(headers, "X-Accept-Encoding");
                // We leave "Accept-Encoding" as the browser sent it. RFC 2616 s14.3
                // says '*;q=0' should force identity even without an explicit
                // 'identity;q=0', but the client passes the header through and so
                // do we: if the server and the browser can both do the
                // compression there is nothing to force.

                socket.setReadTimeout(readTimeout);
                Socket s = getSocket(peerHash, socket.getLocalPort());
                long afterSocket = getTunnel().getContext().clock().now();
                if (_log.shouldDebug()) {
                    // The local port is client-driven (socket.getLocalPort()), not
                    // read from any eepgsite config, so a failing request is
                    // otherwise impossible to attribute to a backend. Log the
                    // resolved target and both timeout budgets: the I2P-side
                    // per-read budget and the local socket's header-read budget.
                    _log.debug("[HTTPServer] " + toString()
                              + "\n* Read timeout: " + readTimeout
                              + " * Header Read timeout: " + SERVER_READ_TIMEOUT_GET
                              + "\n* Client: " + peerB32);
                }

                // Instead of i2ptunnelrunner, use something that reads the HTTP
                // request from the socket, modifies the headers, sends the request
                // to the server, reads the response headers, rewriting to include
                // 'Content-Encoding: x-i2p-gzip' if it was one of the
                // Accept-Encoding: values, and gzips the payload
                boolean allowGZIP = isGzipAllowed(opts);
                if (_log.shouldDebug() && ((enc != null && !enc.isEmpty()) || altEnc != null && !altEnc.isEmpty())) {
                    _log.debug("[HTTPServer] Encoding header: " + enc + "/" + altEnc);
                }
                boolean alt = hasGzipEncoding(altEnc);
                boolean useGZIP = alt || hasGzipEncoding(enc);
                // Don't pass this on, outproxies should strip so I2P traffic isn't so obvious but they probably don't
                if (alt) {headers.remove("X-Accept-Encoding");}

                String modifiedHeader = HttpHeaderFormatter.formatHeaders(headers, command);
                if (_log.shouldDebug()) {
                    _log.debug("[HTTPServer] Modified headers" +
                               HttpHeaderFormatter.formatHeadersForLog(headers, command));
                }
                else if (_log.shouldInfo() && !command.toString().toLowerCase().contains("head")) {
                    String compactHeaders = HttpHeaderFormatter.formatHeadersCompact(headers, command);
                    _log.info("[HTTPServer] Received request headers" + compactHeaders);
                }

                boolean compress = allowGZIP && useGZIP;
                AtomicInteger waiter = keepalive ? new AtomicInteger() : null;
                Runnable t = new CompressedRequestor(s, socket, modifiedHeader, getTunnel().getContext(),
                                                     _log, compress, upgrade, _clientExecutor, keepalive, waiter,
                                                     this::getIOExecutor, tunnelId);
                // Persistent connections run inline so the waiter can gate the
                // next request on this connection. Non-keepalive requests
                // (including GET/HEAD with keepalive off or Connection: close)
                // offload to the executor pool so a slow I2P write does not
                // pin this handler thread.
                if (keepalive) {
                    t.run();
                } else {
                    // Size the pool at the moment the work is handed to it. The queue
                    // depth is a lock-free read, so the common path costs one atomic
                    // load; getActiveCount() takes the pool's mainLock and is only
                    // paid when the queue is actually backing up.
                    growRunnerPoolForSubmit();
                    try {
                        _clientExecutor.execute(t);
                    } catch (RejectedExecutionException ree) {
                        // Recover rather than drop: one load-aware rebalance and a
                        // retry, as the client path does. An idle sibling runner
                        // pool may be sitting on budget this tunnel needs.
                        TunnelControllerGroup.rebalanceRunnerPoolsNow();
                        try {
                            _clientExecutor.execute(t);
                        } catch (RejectedExecutionException ree2) {
                            // Still full after rebalance.
                            reportRunnerShed(tunnelId, peerB32);
                            try { sendError(socket, ERR_UNAVAILABLE); } catch (IOException ioe) { /* ignore */ }
                            try { socket.close(); } catch (IOException ioe) { /* ignore */ }
                            // also close the raw TCP socket opened above - the runner
                            // never ran, so its cleanup never runs (as in handlePort443)
                            try { s.close(); } catch (IOException ioe) { /* ignore */ }
                            return;
                        }
                    }
                }

                long afterHandle = getTunnel().getContext().clock().now();
                recordInitialTiming(afterAccept, afterHeaders, afterSocket, afterHandle, requestCount, peerB32);
                if (keepalive && !shouldKeepalive(waiter, afterAccept, requestCount))
                    break;

                // go around again
                requestCount++;

            } while (keepalive);

        } catch (SocketException ex) {
            int port = socket.getLocalPort();
            sendErrorAndClose(socket);
            // Don't complain too early, Jetty may not be ready.
            int level = getTunnel().getContext().clock().now() - _startedOn > START_INTERVAL ? Log.ERROR : Log.WARN;
            if (_log.shouldLog(level)) {
                _log.log(level, "[HTTPServer] Error connecting to HTTP server " + getSocketString(port) + " " + tunnelId);
            }
        } catch (IOException ex) {
            try {
                socket.close();
            } catch (IOException ioe) { /* ignored */ }
            // DataHelper.messageOf because getMessage() is null far more often than
            // not - a stream that dies mid-read yields a message-less IOException,
            // and dereferencing it here threw an NPE out of this catch block and
            // replaced the diagnostic below with a stack trace.
            final String exMsg = DataHelper.messageOf(ex);
            if (exMsg.indexOf("Name or service not known") >= 0) {
                if (_log.shouldInfo())
                    _log.info("[HTTPServer] Request error: DNS error (blocked?)" +
                              " for: " + exMsg.replace(": Name or service not known", "") +
                              " " + tunnelId + " client=" + peerB32);
            } else if (_log.shouldDebug()) {
                _log.debug("[HTTPServer] Request error: " + exMsg +
                           " " + tunnelId + " client=" + peerB32 +
                           " backend=" + getSocketString(socket.getLocalPort()));
            }
        } catch (OutOfMemoryError oom) {
            // Often actually a file handle limit problem so we can safely send a response
            // java.lang.OutOfMemoryError: unable to create new native thread
            sendErrorAndClose(socket);
            if (_log.shouldError()) {
                _log.error("[HTTPServer] Out of Memory error " + tunnelId + " -> " + oom.getMessage());
            }
        }
    }

    /**
     * Read the request headers with the default timeouts.
     * Delegates to {@link #readRequestHeaders(I2PSocket, StringBuilder, int,
     * String, I2PAppContext, Log, String, long, long)}.
     *
     * @param socket the client socket
     * @param command buffer for the request line
     * @param requestCount the number of requests already handled on this connection (0 = first)
     * @param peerB32 the client's base32 for logging
     * @param ctx the I2P app context
     * @param log the logging instance
     * @param tunnelId "[nickname / b32prefix]" label for this server tunnel
     * @return the parsed headers, or null if the request failed and the client was notified
     * @throws IOException on other I/O errors, propagated to the caller
     */
    static Map<String, List<String>> readRequestHeaders(I2PSocket socket, StringBuilder command, int requestCount,
                                                        String peerB32, I2PAppContext ctx, Log log, String tunnelId) throws IOException {
        return readRequestHeaders(socket, command, requestCount, peerB32, ctx, log, tunnelId,
                                  DEFAULT_HEADER_TIMEOUT_MS, DEFAULT_KEEPALIVE_TIMEOUT_MS);
    }

    /**
     * Read the request headers, handling the specific errors that can occur
     * by sending the appropriate error response to the client.
     * The 5 error handlers share this shape: send error, log, close, return null.
     *
     * @param socket the client socket
     * @param command buffer for the request line
     * @param requestCount the number of requests already handled on this connection (0 = first)
     * @param peerB32 the client's base32 for logging
     * @param ctx the I2P app context
     * @param log the logging instance
     * @param tunnelId "[nickname / b32prefix]" label for this server tunnel
     * @param headerTimeout first-request header timeout (ms)
     * @param keepAliveTimeout idle keepalive wait for subsequent requests (ms)
     * @return the parsed headers, or null if the request failed and the client was notified
     * @throws IOException on other I/O errors, propagated to the caller
     * @since 0.9.71+ timeout parameters added for live tuning
     */
    static Map<String, List<String>> readRequestHeaders(I2PSocket socket, StringBuilder command, int requestCount,
                                                        String peerB32, I2PAppContext ctx, Log log, String tunnelId,
                                                        long headerTimeout, long keepAliveTimeout) throws IOException {
        try {
            /*
             * Catch specific exceptions thrown, to return a good error to the client.
             * Keepalive wait is the configured idle timeout; first request uses the
             * (shorter) header timeout. Previously the keepalive wait was the
             * client-side 120s timeout + 10s, which pinned a handler thread for
             * over two minutes when a browser vanished mid-persistent-connection.
             */
            long timeout = requestCount > 0 ? keepAliveTimeout : headerTimeout;
            return readHeaders(socket, null, command, CLIENT_SKIPHEADERS, ctx, timeout);
        } catch (SocketTimeoutException ste) {
            if (requestCount > 0) {
                if (log.shouldDebug())
                     log.debug("[HTTPServer] Timeout reached awaiting request #" + requestCount + " " + tunnelId);
            } else {
                try {sendError(socket, ERR_REQUEST_TIMEOUT);}
                catch (IOException ioe) { /* ignored */ }
                // Rate limited per client: a torrent client that reconnects
                // without ever sending a request generates thousands of these.
                if (log.shouldDebug() && ste.getMessage() != null
                        && shouldLogRequestError(peerB32, ctx.clock().now())) {
                    log.debug("[HTTPServer] Request error: " + ste.getMessage() +
                              " " + tunnelId + " client=" + peerB32);
                }
            }
            try {socket.close();}
            catch (IOException ioe) { /* ignored */ }
            return null;
        } catch (EOFException eofe) {
            if (requestCount > 0) {
                if (log.shouldDebug())
                     log.debug("[HTTPServer] Client closed awaiting request #" + requestCount + " " + tunnelId);
            } else {
                try {sendError(socket, ERR_BAD_REQUEST);}
                catch (IOException ioe) { /* ignored */ }
                if (log.shouldDebug() && eofe.getMessage() != null
                        && shouldLogRequestError(peerB32, ctx.clock().now())) {
                    log.debug("[HTTPServer] Request error: " + eofe.getMessage() +
                              " " + tunnelId + " client=" + peerB32);
                }
            }
            try {socket.close();}
            catch (IOException ioe) { /* ignored */ }
            return null;
        } catch (LineTooLongException ltle) {
            try {sendError(socket, ERR_HEADERS_TOO_LARGE);}
            catch (IOException ioe) { /* ignored */ }
            finally {
                try {socket.close();}
                catch (IOException ioe) { /* ignored */ }
            }
            if (log.shouldDebug()) {
                log.debug("[HTTPServer] Request error: Headers too large " +
                          tunnelId + " client=" + peerB32);
            }
            return null;
        } catch (RequestTooLongException rtle) {
            try {sendError(socket, ERR_REQUEST_URI_TOO_LONG);}
            catch (IOException ioe) { /* ignored */ }
            finally {
                try {socket.close();}
                catch (IOException ioe) { /* ignored */ }
            }
            if (log.shouldDebug()) {
                log.debug("[HTTPServer] Request error: URI too long " +
                          tunnelId + " client=" + peerB32);
            }
            return null;
        } catch (BadRequestException bre) {
            try {sendError(socket, ERR_BAD_REQUEST);}
            catch (IOException ioe) { /* ignored */ }
            finally {
                try {socket.close();}
                catch (IOException ioe) { /* ignored */ }
            }
            if (log.shouldDebug() && bre.getMessage() != null) {
                log.debug("[HTTPServer] Request error: " + bre.getMessage() +
                          " " + tunnelId + " client=" + peerB32);
            }
            return null;
        }
    }

    /** Connection header absent. */
    static final int CONN_NONE = 0;
    /** Connection: keep-alive. */
    static final int CONN_KEEPALIVE = 1;
    /** Connection: upgrade (websocket). */
    static final int CONN_UPGRADE = 2;
    /** Connection: close or unknown value. */
    static final int CONN_CLOSE = 3;

    /**
     * Classify the request's Connection header value.
     *
     * @param headers the parsed request headers, searched for a Connection entry
     * @return CONN_NONE, CONN_KEEPALIVE, CONN_UPGRADE, or CONN_CLOSE
     */
    static int getConnectionType(Map<String, List<String>> headers) {
        String conn = getEntryOrNull(headers, "Connection");
        if (conn == null) {return CONN_NONE;}
        String connlc = conn.toLowerCase(Locale.US);
        if (connlc.contains("upgrade")) {return CONN_UPGRADE;}
        if (connlc.contains("keep-alive")) {return CONN_KEEPALIVE;}
        return CONN_CLOSE;
    }

    /**
     * Whether the request can keep the connection alive: GET or HEAD with HTTP/1.1.
     *
     * @param cmd the trimmed request line
     * @return true if keepalive may continue
     */
    static boolean isKeepAliveRequest(String cmd) {
        return (cmd.startsWith("GET ") || cmd.startsWith("HEAD ")) && cmd.endsWith(" HTTP/1.1");
    }

    /**
     * Whether the Accept-Encoding value includes x-i2p-gzip.
     *
     * @param enc an Accept-Encoding header value, or null
     * @return true if it includes x-i2p-gzip
     */
    static boolean hasGzipEncoding(String enc) {
        return enc != null && enc.indexOf("x-i2p-gzip") >= 0;
    }

    /**
     * Whether the tunnel allows gzip responses, from the tunnel options.
     *
     * @param opts the client options
     * @return true unless PROP_TUN_GZIP is set to false
     */
    static boolean isGzipAllowed(Properties opts) {
        String val = opts.getProperty(TunnelController.PROP_TUN_GZIP);
        return val == null || Boolean.parseBoolean(val);
    }

    /**
     * Handle port 443 (SSL passthrough).
     * If no target is configured for port 443, reset the socket.
     * Otherwise, set a long timeout and forward directly to the server
     * via {@link I2PTunnelRunner}.
     *
     * @param socket the incoming I2P socket
     * @return true if the socket was handled (port 443), false otherwise
     * @throws IOException if socket operations fail
     */
    private boolean handlePort443(I2PSocket socket) throws IOException {
        if (socket.getLocalPort() != 443)
            return false;
        if (getTunnel().getClientOptions().getProperty("targetForPort.443") == null) {
            // can't write non-ssl error message
            // client side already sent 200 to browser
            try {socket.reset();}
            catch (IOException ioe) { /* ignored */ }
            return true;
        }
        // We don't know if this is GET or POST or what, so set a huge timeout
        // and rely on the server to do the actual timeout
        socket.setReadTimeout(SERVER_READ_TIMEOUT_POST);
        Socket s = getSocket(socket.getPeerDestination().calculateHash(), 443);
        I2PTunnelRunner runner = new I2PTunnelRunner(s, socket, slock, null, null, null,
            (e) -> {
                // SSL passthrough: no HTTP error page possible (browser already
                // sent ClientHello).  Reset the socket so the browser gets a
                // clean failure instead of a silent hang.
                if (_log.shouldWarn()) {
                    _log.warn("[HTTPServer] 443 connection failed" + (e != null ? ": " + e : ""));
                }
                try { socket.reset(); }
                catch (IOException ioe) { /* ignored */ }
            });
        runner.setExecutor(_clientExecutor);
        growRunnerPoolForSubmit();
        try {
            _clientExecutor.execute(runner);
        } catch (RejectedExecutionException ree) {
            // One load-aware rebalance and a retry before shedding: an idle sibling
            // runner pool may release budget to this one.
            TunnelControllerGroup.rebalanceRunnerPoolsNow();
            try {
                _clientExecutor.execute(runner);
            } catch (RejectedExecutionException ree2) {
                // All client runner threads busy, even after rebalance. Report it so
                // the Tuner can grow this tunnel's share instead of reading it idle.
                String peerB32;
                try {
                    peerB32 = socket.getPeerDestination().toBase32();
                } catch (RuntimeException re) {
                    peerB32 = null;
                }
                reportRunnerShed(getTunnelLogId(socket.getThisDestination()), peerB32);
                // also close the raw TCP socket opened above - the runner never ran, so its
                // cleanup never runs
                try {s.close();}
                catch (IOException ioe) { /* ignored */ }
                socket.close();
            }
        }
        return true;
    }

    /**
     * Refuse an inbound connection when the gate cap is exceeded: send a 503
     * (SSL port 443 resets instead) and close. The base implementation then logs
     * the rate-limited warning.
     *
     * @param socket the accepted but undelivered I2PSocket to reject
     * @since 0.9.71+
     */
    @Override
    protected void rejectConnection(I2PSocket socket) {
        rejectConnection(socket, false);
    }

    /**
     * Refuse an inbound connection (queue-full or connection-cap): send a 503
     * (SSL port 443 resets instead) and close, then log the accurate reason.
     *
     * @param socket the accepted but undelivered I2PSocket to reject
     * @param queueFull true if the handler queue gate rejected, false for the connection cap
     * @since 0.9.71+
     */
    @Override
    protected void rejectConnection(I2PSocket socket, boolean queueFull) {
        sendErrorAndClose(socket);
        super.rejectConnection(socket, queueFull);
    }

    /**
     * Validate the request hostname against local/loopback/private addresses.
     * If the hostname resolves to a private or loopback address, the peer is
     * blocklisted and the socket is closed. If DNS resolves to 0.0.0.0 (blocked DNS),
     * a 403 is sent.
     * DNS is resolved asynchronously with a bounded timeout to avoid
     * blocking the shared client thread pool on slow or unreachable DNS servers.
     *
     * @param headers the request headers map (to extract Host header)
     * @param socket the incoming I2P socket
     * @param peerB32 the peer's base32 address for logging/blocklisting
     * @return true if the request may proceed, false if it was rejected and the
     *         socket closed (the caller must not continue processing)
     * @throws IOException if blocklist writing fails
     */
    private boolean validateRequestHost(Map<String, List<String>> headers, I2PSocket socket, String peerB32) throws IOException {
        String tunnelId = getTunnelLogId(socket.getThisDestination());
        String hostname = null;
        boolean isValidRequest = true;
        boolean isPossibleExploit = false;
        List<String> host = headers.get("Host");

        if (peerB32.length() != 60) {
            // The Host header has not been read at this point, so this line
            // names the tunnel and the client, never a host.
            _log.warn("[HTTPServer] Invalid B32 (expected 60 characters, got " + peerB32.length() + ")" +
                      " -> Denying the request" +
                      "\n* Tunnel: " + tunnelId + "\n* Client: " + peerB32);
            try {socket.close();}
            catch (IOException ioe) { /* ignored */ }
            return false;
        }

        if (host != null && !host.isEmpty()) {
            hostname = host.get(0);
            int port = hostname.indexOf(":");
            if (port != -1) {hostname = hostname.substring(0, port);}
        }
        // A hostname is neither a router hash nor a destination, so
        // loggableHost() prints it whole, unbracketed and length-bounded.
        if (_log.shouldDebug()) {
            _log.debug("[HTTPServer] Incoming request for: " + loggableHost(hostname) +
                       "\n* Tunnel: " + tunnelId + "\n* Client: " + peerB32);
        }
        if (hostname != null && !hostname.endsWith(".i2p") && !hostname.endsWith(".onion")) {
            InetAddress address;
            Future<InetAddress> future = null;
            try {
                final String lookupHost = hostname;
                future = _dnsResolver.submit(() -> InetAddress.getByName(lookupHost));
                address = future.get(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (_log.shouldWarn()) {
                    _log.warn("[HTTPServer] DNS lookup timed out -> Denying the request" +
                              "\n* Host: " + loggableHost(hostname) + " * Timeout: " + DNS_TIMEOUT_MS + "ms" +
                              " * Tunnel: " + tunnelId + "\n* Client: " + peerB32);
                }
                // Cancel so the resolver thread is not left parked in a JVM-level
                // resolution (which can run far longer than our timeout) holding a
                // pool slot; a queued task is dropped outright.
                if (future != null) {future.cancel(true);}
                try {socket.close();}
                catch (IOException ioe) { /* ignored */ }
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (future != null) {future.cancel(true);}
                try {socket.close();}
                catch (IOException ioe) { /* ignored */ }
                return false;
            } catch (ExecutionException e) {
                if (_log.shouldWarn()) {
                    _log.warn("[HTTPServer] DNS lookup failed -> Denying the request" +
                              "\n* Host: " + loggableHost(hostname) + " * Tunnel: " + tunnelId +
                              "\n* Client: " + peerB32);
                }
                try {socket.close();}
                catch (IOException ioe) { /* ignored */ }
                return false;
            } catch (RejectedExecutionException e) {
                // Executor saturated — do NOT block on synchronous DNS, which
                // would stall the request handler thread under load.  Close the
                // socket so the client gets a clean failure instead of a hang.
                if (_log.shouldWarn()) {
                    _log.warn("[HTTPServer] DNS executor saturated, cannot resolve the host" +
                              " -> Denying the request" +
                              "\n* Host: " + loggableHost(hostname) + " * Tunnel: " + tunnelId +
                              "\n* Client: " + peerB32);
                }
                try {socket.close();}
                catch (IOException ioe) { /* ignored */ }
                return false;
            }
            if (address.isLinkLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()) {
                if (_log.shouldWarn()) {
                    _log.warn("[HTTPServer] Localhost or loopback address requested -> Blocking the client" +
                              "\n* Host: " + loggableHost(hostname) + " * Tunnel: " + tunnelId +
                              "\n* Client: " + peerB32 + " * Blocklist: added");
                }
                _blocklistManager.logBlockedDestination(peerB32);
                isValidRequest = false;
                isPossibleExploit = true;
            } else if (address.isAnyLocalAddress()) { // check for 0.0.0.0 response (DNS blocking)
                if (!hostname.equals("::") && !hostname.equals("0.0.0.0")) {
                    if (_log.shouldWarn()) {
                        _log.warn("[HTTPServer] DNS server appears to be blocking requests -> Sending Error 403" +
                                  "\n* Host: " + loggableHost(hostname) + " * Tunnel: " + tunnelId +
                                  "\n* Client: " + peerB32);
                    }
                    try {sendError(socket, ERR_FORBIDDEN);}
                    catch (IOException ioe) { /* ignored */ }
                    isValidRequest = false;
                    isPossibleExploit = false;
                } else {
                    try {sendError(socket, ERR_FORBIDDEN);}
                    catch (IOException ioe) { /* ignored */ }
                    isValidRequest = false;
                    isPossibleExploit = true;
                }
            } else {
                if (_log.shouldInfo() && !hostname.equals(address.getHostAddress())) {
                    _log.info("[HTTPServer] Hostname validated -> Resolves to: " + address.getHostAddress() +
                              "\n* Host: " + loggableHost(hostname) + " * Tunnel: " + tunnelId);
                }
                isPossibleExploit = false;
            }
            if (isPossibleExploit) {
                _blocklistManager.logBlockedDestination(peerB32);
                if (_log.shouldWarn()) {
                    _log.warn("[HTTPServer] Private or wildcard address requested -> Sending Error 403" +
                              "\n* Host: " + loggableHost(hostname) + " * Tunnel: " + tunnelId +
                              "\n* Client: " + peerB32 + " * Blocklist: added");
                }
            }
            if (!isValidRequest) {
                try {socket.close();}
                catch (IOException e) { /* ignored */ }
                return false;
            }
        }
        return true;
    }

    /**
     * The request host as it is logged: the name the client sent, with the port
     * already removed, or a fixed phrase when the client sent no usable Host
     * header. A name longer than 64 characters is cut to 61 plus an ellipsis.
     * <p>
     * The name is printed whole and unbracketed. It is neither a router hash
     * nor a destination, and the console log linkifier only recognizes
     * bracketed hashes - 6 characters base64 for a router, 8 characters base32
     * for a destination - so a bracketed host renders as a link to a
     * destination that does not exist.
     * <p>
     * Package-visible and static so a test can drive the shapes the request
     * path cannot: this site used to call substring(0, 8) on the name, which
     * threw on any host shorter than 8 characters, and dereferenced a null
     * hostname for a request with no Host header at all.
     *
     * @param hostname the host as the client named it, port removed, null or
     *                 empty when there was no Host header
     * @return a bounded, non-null, unbracketed host for the log
     * @since 0.9.72+
     */
    static String loggableHost(String hostname) {
        if (hostname == null || hostname.isEmpty()) {return "no host header";}
        if (hostname.length() <= MAX_LOG_HOST_LEN) {return hostname;}
        return hostname.substring(0, MAX_LOG_HOST_LEN - 3) + "...";
    }

    /**
     * Reject requests that contain forwarded-for headers (inproxy detection).
     *
     * @param headers the request headers
     * @param socket the incoming I2P socket
     * @param peerB32 the peer's base32 address for logging
     * @param opts the tunnel client options
     * @return true if the request was rejected and the socket closed
     */
    boolean isInproxyRejection(Map<String, List<String>> headers, I2PSocket socket, String peerB32, Properties opts) {
        if (!Boolean.parseBoolean(opts.getProperty(OPT_REJECT_INPROXY)))
            return false;
        if (!headers.containsKey("X-Forwarded-For") &&
            !headers.containsKey("X-Forwarded-Server") &&
            !headers.containsKey("Forwarded") &&
            !headers.containsKey("X-Forwarded-Host"))
            return false;
        if (_log.shouldWarn()) {
            StringBuilder buf = new StringBuilder();
            buf.append("[HTTPServer] ").append(getTunnelLogId(socket.getThisDestination())).append(" Refusing Inproxy access \n* Client: ").append(peerB32);
            List<String> h = headers.get("X-Forwarded-For");
            if (h != null)
                buf.append("\n* X-Forwarded-For: ").append(h.get(0));
            h = headers.get("X-Forwarded-Server");
            if (h != null)
                buf.append("\n* X-Forwarded-Server: ").append(h.get(0));
            h = headers.get("X-Forwarded-Host");
            if (h != null)
                buf.append("\n* X-Forwarded-Host: ").append(h.get(0));
            h = headers.get("Forwarded");
            if (h != null)
                buf.append("\n* Forwarded: ").append(h.get(0));
            _log.warn(buf.toString());
        }
        try {sendError(socket, ERR_FORBIDDEN);}
        catch (IOException ioe) { /* ignored */ }
        try {socket.close();}
        catch (IOException ioe) { /* ignored */ }
        return true;
    }

    /**
     * Reject requests with absolute Referer URIs.
     *
     * @param headers the request headers
     * @param socket the incoming I2P socket
     * @param peerB32 the peer's base32 address for logging
     * @param opts the tunnel client options
     * @return true if the request was rejected and the socket closed
     */
    boolean isRefererRejection(Map<String, List<String>> headers, I2PSocket socket, String peerB32, Properties opts) {
        if (!Boolean.parseBoolean(opts.getProperty(OPT_REJECT_REFERER)))
            return false;
        List<String> h = headers.get("Referer");
        if (h == null)
            return false;
        String referer = h.get(0);
        if (referer.startsWith("http://") || referer.startsWith("https://")) {
            if (_log.shouldInfo()) {
                _log.info("[HTTPServer] " + getTunnelLogId(socket.getThisDestination()) +
                          " Refusing access (Bad referer) \n* Client: " + peerB32 +
                          "\n* Referer: " + referer);
            }
            try {sendError(socket, ERR_FORBIDDEN);}
            catch (IOException ioe) { /* ignored */ }
            try {socket.close();}
            catch (IOException ioe) { /* ignored */ }
            return true;
        }
        return false;
    }

    /**
     * Reject requests with blacklisted User-Agent strings.
     *
     * @param headers the request headers
     * @param socket the incoming I2P socket
     * @param peerB32 the peer's base32 address for logging
     * @param opts the tunnel client options
     * @return true if the request was rejected and the socket closed
     */
    boolean isUserAgentRejection(Map<String, List<String>> headers, I2PSocket socket, String peerB32, Properties opts) {
        if (!Boolean.parseBoolean(opts.getProperty(OPT_REJECT_USER_AGENTS)))
            return false;
        String blockAgents = opts.getProperty(OPT_USER_AGENTS);
        if (blockAgents == null)
            return false;
        String[] agents = DataHelper.split(blockAgents, ",");
        if (headers != null && headers.containsKey("User-Agent")) {
            String ua = headers.get("User-Agent").get(0);
            if (ua.startsWith("MYOB"))
                return false;
            for (int i = 0; i < agents.length; i++) {
                String ag = agents[i].trim();
                if (ag.equals("none")) {continue;}
                if (!ag.isEmpty() && ua.contains(ag)) {
                    if (_log.shouldInfo()) {
                        _log.info("[HTTPServer] " + getTunnelLogId(socket.getThisDestination()) +
                                  " Refusing access: Blacklisted User Agent (" + ua + ")" +
                                  " \n* Client: " + peerB32);
                    }
                    try {sendError(socket, ERR_FORBIDDEN);}
                    catch (IOException ioe) { /* ignored */ }
                    try {socket.close();}
                    catch (IOException ioe) { /* ignored */ }
                    return true;
                }
            }
        } else {
            // no user-agent, block if blocklist contains "none"
            for (int i = 0; i < agents.length; i++) {
                String ag = agents[i].trim();
                if (ag.equals("none")) {
                    if (_log.shouldInfo()) {
                        _log.info("[HTTPServer] " + getTunnelLogId(socket.getThisDestination()) +
                                  " Refusing access: User Agent header is blank" +
                                  " \n* Client: " + peerB32);
                    }
                    try {sendError(socket, ERR_FORBIDDEN);}
                    catch (IOException ioe) { /* ignored */ }
                    try {socket.close();}
                    catch (IOException ioe) { /* ignored */ }
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check POST/PUT throttling for this peer.
     *
     * @param command the request command string (first line)
     * @param peerHash the peer's hash for throttling lookup
     * @param socket the incoming I2P socket
     * @param peerB32 the peer's base32 address for logging
     * @return true if the request was throttled and the socket closed
     */
    boolean isPostThrottled(StringBuilder command, Hash peerHash, I2PSocket socket, String peerB32) {
        ConnThrottler postThrottler;
        synchronized(this) {
            postThrottler = _postThrottler;
        }
        if (postThrottler == null || command.length() < 4)
            return false;
        boolean isPost = command.length() >= 5 && command.substring(0, 5).toUpperCase(Locale.US).equals("POST ");
        boolean isPut = command.length() >= 4 && command.substring(0, 4).toUpperCase(Locale.US).equals("PUT ");
        if (!isPost && !isPut)
            return false;
        if (postThrottler.shouldThrottle(peerHash)) {
            if (_log.shouldWarn()) {
                _log.warn("[HTTPServer] " + getTunnelLogId(socket.getThisDestination()) + " Refusing POST/PUT since peer is throttled \n* Client: " + peerB32);
            }
            try {sendError(socket, ERR_DENIED);}
            catch (IOException ioe) { /* ignored */ }
            try {socket.close();}
            catch (IOException ioe) { /* ignored */ }
            return true;
        }
        return false;
    }

    /**
     * Record timing stats for the first request in a keepalive cycle.
     *
     * @param afterAccept clock time right after accept
     * @param afterHeaders clock time after reading headers
     * @param afterSocket clock time after creating the server socket
     * @param afterHandle clock time after dispatching the request
     * @param requestCount the request number (only logs for 0)
     * @param peerB32 the peer's base32 address for logging
     */
    private void recordInitialTiming(long afterAccept, long afterHeaders, long afterSocket,
                                     long afterHandle, int requestCount, String peerB32) {
        if (requestCount != 0)
            return;
        long timeToHandle = afterHandle - afterAccept;
        getTunnel().getContext().statManager().addRateData("i2ptunnel.httpserver.blockingHandleTime", timeToHandle);
        if ((timeToHandle > 1500) && (_log.shouldDebug())) {
            _log.info("[HTTPServer] Took a while (" + timeToHandle + "ms) to handle the request for " + remoteHost + ':' + remotePort +
                      "\n* Client: " + peerB32 +
                      "\n* Tasks: Read headers: " + (afterHeaders-afterAccept) + "ms * " +
                      "Socket create: " + (afterSocket-afterHeaders) + "ms * " +
                      "Start runners: " + (afterHandle-afterSocket) + "ms");
         }
    }

    /**
     * Apply port-specific spoofed host header.
     * If no port-specific spoof is configured for the socket's port,
     * falls back to the default _spoofHost.
     *
     * @param socket the incoming I2P socket
     * @param headers the request header map to modify
     * @param opts the tunnel client options
     */
    private void applySpoofedHost(I2PSocket socket, Map<String, List<String>> headers, Properties opts) {
        String spoofHost;
        int ourPort = socket.getLocalPort();
        if (ourPort != 80 && ourPort > 0 && ourPort <= 65535) {
            String portSpoof = opts.getProperty("spoofedHost." + ourPort);
            if (portSpoof != null) {spoofHost = portSpoof.trim();}
            else {spoofHost = _spoofHost;}
        } else {spoofHost = _spoofHost;}
        if (spoofHost != null) {setEntry(headers, "Host", spoofHost);}
    }

    /**
     * Check whether the keepalive waiter indicates we should stay alive.
     * @param waiter the AtomicInteger set by CompressedRequestor
     * @param afterAccept clock time right after accept (for debug logging)
     * @param requestCount the request number (for debug logging)
     * @return true if the waiter returned keepalive-eligible (value 2)
     */
    private boolean shouldKeepalive(AtomicInteger waiter, long afterAccept, int requestCount) {
        if (_log.shouldDebug()) {
            long timeToWait = getTunnel().getContext().clock().now() - afterAccept;
            // 0: not done; 1: not keepalive-able response; 2: keepalive
            String code;
            switch (waiter.get()) {
                case 0: code = "Not complete"; break;
                case 1: code = "Not KeepAlive"; break;
                case 2: code = "KeepAlive"; break;
                default: code = "Unknown";
           }
           _log.debug("[HTTPServer] Waited " + timeToWait + "ms for response [#" + requestCount + "] to complete -> " + code);
        }
        return waiter.get() == 2;
    }

    /**
     * Send an error response to the client. For port 443 (SSL),
     * resets the socket instead since we can't send plaintext error messages.
     *
     * @param socket the client socket
     * @param resp the HTTP error response string
     * @throws IOException if writing the response fails
     * @since 0.9.62
     */
    private static void sendError(I2PSocket socket, String resp) throws IOException {
        if (socket.getLocalPort() == 443) {socket.reset();}
        else {socket.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));}
    }

    /**
     * Send a 503 and close the socket, ignoring any failures.
     *
     * @param socket the socket to respond on
     */
    private static void sendErrorAndClose(I2PSocket socket) {
        try {
            // Send a 503, so the user doesn't get an HTTP Proxy error message
            // and blame his router or the network.
            sendError(socket, ERR_UNAVAILABLE);
        } catch (IOException ioe) { /* ignored */ }
        try {
            socket.close();
        } catch (IOException ioe) { /* ignored */ }
    }

    /**
     * Extract the request URL from the client's headers for logging.
     * <p>
     * Package-visible and static so a test can drive it with a hostile request
     * line: the URL is entirely attacker-controlled, and this method used to call
     * substring(0, 12) on any host merely containing "b32.i2p", so a request for
     * "/b32.i2p" aborted the handler with StringIndexOutOfBoundsException.
     *
     * @param headers the raw request headers, may be null
     * @return a human-readable request description, never null
     * @since 0.9.72+
     */
    static String extractRequestUrl(String headers) {
        if (headers == null || headers.isEmpty())
            return "Unknown request";
        String[] requestLines = NEWLINE_SPLIT.split(headers);
        if (requestLines.length == 0)
            return "Unknown request";
        String requestLine = requestLines[0];
        String[] requestParts = SPACE_SPLIT.split(requestLine);
        String url = requestParts.length > 1 ? requestParts[1] : null;
        if (url == null)
            return "Unknown request";
        if (url.startsWith("http://"))
            url = url.replace("http://", "");
        if (url.length() > 100)
            url = url.substring(0, 48) + "..." + url.substring(url.length() - 48);
        String[] urlParts = SLASH_SPLIT.split(url);
        StringBuilder hostBuilder = new StringBuilder();
        if (urlParts.length > 0) {
            hostBuilder.append(urlParts[0]);
            for (int i = 1; i < urlParts.length; i++) {
                if (!urlParts[i].trim().isEmpty())
                    hostBuilder.append("/").append(urlParts[i]);
            }
        } else {
            hostBuilder.append(url);
        }
        String host = hostBuilder.toString();
        // Shorten a full base32 address to its first 12 characters. The length
        // test matters: "b32.i2p" alone is 7 characters and contains the marker.
        if (host.contains("b32.i2p") && host.length() >= 12)
            host = host.substring(0, 12) + "...b32.i2p";
        return host;
    }

    /** Copies a gzipped request through to the web server. */
    private static class CompressedRequestor implements Runnable {
        /**
         * Far-side label for transfer logs, e.g. {@code [skank / aanoquc2]}.
         * Null when the caller did not supply one.
         * @since 0.9.71+
         */
        private final String _tunnelId;
        /** The web server socket. */
        private final Socket _webserver;
        /** The browser's I2P socket. */
        private final I2PSocket _browser;
        /** The request headers to forward. */
        private final String _headers;
        /** The app context. */
        private final I2PAppContext _ctx;
        /** Logger, shadows the one in the superclass. */
        private final Log _log;
        /** True when the request is gzipped. */
        private final boolean _shouldCompress;
        /** True for an upgrade request. */
        private final boolean _upgrade;
        /** The executor running this task. */
        private final ThreadPoolExecutor _tpe;
        /** Whether the browser connection stays open. */
        private volatile boolean _keepalive;
        /** Waiter count for the IO pool. */
        private final AtomicInteger _waiter;
        /** Supplier of the shared IO pool. */
        private final Supplier<ThreadPoolExecutor> _ioPool;
        /** Copy buffer size. */
        private static final int BUF_SIZE = 16*1024;

        /**
         * Serves a gzipped response from the web server to the browser.
         *
         * @param webserver the web server socket to read the response from
         * @param browser the browser socket to send the response to
         * @param headers the request headers to forward to the web server
         * @param ctx the application context, used to schedule the stall watchdog
         * @param log the logger for the transfer logs
         * @param shouldCompress if false, don't compress, just filter server headers
         * @param upgrade true if the request is a protocol upgrade, which disables keepalive
         * @param tpe the thread pool that runs the inline server-to-browser sender
         * @param keepalive true if the browser connection may be reused afterwards
         * @param waiter to notify when done, if non-null; will set value to 1: not keepalive-able response, or 2: keepalive
         * @param ioPool resolves the owning tunnel's I/O transfer pool (never returns null in production)
         */
          public CompressedRequestor(Socket webserver, I2PSocket browser, String headers,
                                     I2PAppContext ctx, Log log, boolean shouldCompress, boolean upgrade,
                                     ThreadPoolExecutor tpe, boolean keepalive, AtomicInteger waiter,
                                     Supplier<ThreadPoolExecutor> ioPool) {
              this(webserver, browser, headers, ctx, log, shouldCompress, upgrade, tpe, keepalive, waiter,
                   ioPool, null);
          }

          /**
           * Serves a gzipped response, with an optional stall watchdog.
           *
           * @param webserver the web server socket to read the response from
           * @param browser the browser socket to send the response to
           * @param headers the request headers to forward to the web server
           * @param ctx the application context, used to schedule the stall watchdog
           * @param log the logger for the transfer logs
           * @param shouldCompress if false, don't compress, just filter server headers
           * @param upgrade true if the request is a protocol upgrade, which disables keepalive
           * @param tpe the thread pool that runs the inline server-to-browser sender
           * @param keepalive true if the browser connection may be reused afterwards
           * @param waiter to notify when done, if non-null; will set value to 1: not keepalive-able response, or 2: keepalive
           * @param ioPool resolves the owning tunnel's I/O transfer pool (never returns null in production)
           * @param tunnelId far-side label for transfer logs, e.g.
           *                 {@code [skank / aanoquc2]}; may be null
           * @since 0.9.71+
           */
          public CompressedRequestor(Socket webserver, I2PSocket browser, String headers,
                                     I2PAppContext ctx, Log log, boolean shouldCompress, boolean upgrade,
                                     ThreadPoolExecutor tpe, boolean keepalive, AtomicInteger waiter,
                                     Supplier<ThreadPoolExecutor> ioPool, String tunnelId) {
              _tunnelId = tunnelId;
              _webserver = webserver;
              _browser = browser;
              _headers = headers;
            _ctx = ctx;
            _log = log;
            _shouldCompress = shouldCompress;
            _upgrade = upgrade;
            _tpe = tpe;
            _keepalive = keepalive;
            _waiter = waiter;
            _ioPool = ioPool;
        }

        /**
         * Returns the URL suffix of a request line.
         *
         * @param req the request line
         * @return the path after the method, empty if malformed
         */
        private static String urlSuffix(String req) {
            return req != null && !req.isEmpty() && !req.equals("Unknown request") ? "\n* URL: " + req : "";
        }

        /**
         * Run a Sender on the dedicated I/O pool with idle-based stall detection.
         * The handler thread polls {@link Future#get} in short slices and cancels
         * only after {@link I2PTunnelHTTPServer#ioStallTimeoutMs} with no read
         * progress. A total-timeout Future.get would kill any transfer longer
         * than the stall window (a multi-minute I2P download at hundreds of
         * KB/s), which is expected latency — not a stall. On true idle the
         * Sender thread is interrupted (breaking any blocking {@code read()}),
         * and an IOException propagates to the finally-block cleanup.
         * <p>
         * Used for keepalive responses (must wait before the next request)
         * and as a fallback when async handoff is not taken.
         * <p>The idle window is re-evaluated every poll with
         * {@link #computeStallWindowMs(long, long)}, exactly as the detached
         * body path does: a keepalive response that has already moved
         * megabytes earns the same patience as the same body handed off
         * asynchronously, instead of being cut off at the flat configured
         * floor.
         *
         * @param s the Sender to run
         * @param desc descriptive name for error messages
         * @throws IOException if the transfer goes idle for the stall timeout,
         *                     fails, or the pool rejects the task
         */
         private void runOnIO(Sender s, String desc) throws IOException {
             ThreadPoolExecutor ioPool = _ioPool != null ? _ioPool.get() : null;
             Future<?> f;
             if (ioPool == null) {
                 // Headless/unit path: no owning server, run inline rather than drop.
                 s.run();
                 return;
             }
             // Publish load every transfer, matching handOffBody. Growth is the
             // Tuner's job — setIOTransferThreads rebalances every live pool — so
             // nothing grows here and the inline fallback below stays the
             // overload valve it is documented to be.
             sampleIOLoad(ioPool);
             try {
                 f = ioPool.submit(s);
             } catch (RejectedExecutionException ree) {
                 // I/O pool saturated — fall back to inline to avoid dropping the
                 // connection entirely. The handler thread is pinned, but this is
                 // a transient overload, not a permanent state.
                 sampleIOLoad(ioPool);
                 if (_log.shouldWarn()) {
                     _log.warn("[HTTPServer] I/O pool saturated, falling back to inline " + desc);
                 }
                 s.run();
                 return;
             }
             sampleIOLoad(ioPool);
            final long baseStallMs = ioStallTimeoutMs;
            final long pollMs = Math.max(1_000L, Math.min(baseStallMs / 10, 5_000L));
            final long started = System.nanoTime();
            try {
                while (true) {
                    try {
                        f.get(pollMs, TimeUnit.MILLISECONDS);
                        return;
                    } catch (TimeoutException te) {
                        // slice expired — fall through to idle check
                    }
                    long last = s.getLastReadNanos();
                    if (last <= 0) {last = started;}
                    long idleMs = (System.nanoTime() - last) / 1_000_000L;
                    // Scale with body progress exactly as BodyWatchdog and the
                    // Sender loop do: a keepalive body that has already moved
                    // megabytes has demonstrably earned patience.
                    long stallMs = computeStallWindowMs(baseStallMs, s.getBytesTransferred());
                    if (idleMs >= stallMs) {
                        f.cancel(true); // interrupts the Sender thread
                        _stallEventCount.incrementAndGet();
                        throw new IOException("[HTTPServer] I/O transfer stalled (no data for " +
                                              idleMs + "ms): " + desc);
                    }
                }
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof IOException) {throw (IOException) cause;}
                throw new IOException("[HTTPServer] I/O transfer failed: " + desc, cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                f.cancel(true);
                throw new IOException("[HTTPServer] I/O transfer interrupted: " + desc, ie);
            }
        }

        /**
         * This thread handles the response from the server back to the browser.
         * If the request was not GET or HEAD, (typically POST or CONNECT), it spawns another thread
         * "Sender" to push the remaining request data from the browser to the server.
         */
        @Override
        public void run() {
            OutputStream serverout = null;
            OutputStream browserout = null;
            CompressedResponseOutputStream compressedout = null;
            InputStream browserin = null;
            InputStream serverin = null;
            Sender s = null;
            Sender sender = null;
            IOException ioex = null;
            String req = null;
            // Finish exactly once: async body completion and the finally path
            // both may call finishTransfer (inline fallback vs pool worker).
            final AtomicBoolean finished = new AtomicBoolean(false);
            boolean bodySubmitted = false;
            try {
                serverout = _webserver.getOutputStream();
                serverout.write(DataHelper.getUTF8(_headers));
                browserin = _browser.getInputStream();
                // Don't spin off a thread for this except for POSTs and PUTs and Connection: Upgrade
                // beware interference with Shoutcast, etc.?

                req = extractRequestUrl(_headers);

                boolean isHead = _headers != null && _headers.startsWith("HEAD ");
                boolean isGet = _headers != null && _headers.startsWith("GET ");
                boolean isPost = _headers != null && _headers.startsWith("POST ");
                if (!(isGet || isHead) || _upgrade || browserin.available() > 0) {  // just in case
                    // Unless this is POST, set a huge
                    // timeout and rely on the server to do the actual timeout
                    _browser.setReadTimeout(isPost ?
                                            SERVER_READ_TIMEOUT_MEDIUM :   // medium
                                            SERVER_READ_TIMEOUT_POST);     // long
                    _keepalive = false;
                    sender = new Sender(serverout, browserin, "from Client -> Server", _tunnelId, _log);
                    // The request line is only known now, so label the request
                    // direction after the fact; the response direction already
                    // carries the URL in its name.
                    sender.setRequestLabel(req);
                    // run in the limited client pool
                    try {
                        _tpe.execute(sender);
                    } catch (RejectedExecutionException ree) {
                        // pool saturated - reuse the IOException path so the client gets a 503
                        throw new IOException("Client pool saturated");
                    }
                }
                int timeout = (isGet || isHead) ?
                              SERVER_READ_TIMEOUT_GET :   // short
                              SERVER_READ_TIMEOUT_POST;   // long
                _webserver.setSoTimeout(timeout);
                browserout = _browser.getOutputStream();

                try {serverin = new BufferedInputStream(_webserver.getInputStream(), BUF_SIZE);}
                catch (NullPointerException npe) {throw new IOException("getInputStream NPE");}
                String modifiedHeaders = readAndRewriteServerResponse(serverin, timeout, _ctx);
                // After the headers, the body gets the same base window the
                // I2P-side watchdogs use (floor 60s, raisable by the Tuner),
                // and the Sender raises it further as the body crosses bulk
                // progress tiers — one arbiter, one scale, both ends of the
                // pump.
                _webserver.setSoTimeout((int) computeStallWindowMs(ioStallTimeoutMs, 0));

                if (_shouldCompress) {
                    // A HEAD response carries the GET's Content-Length but no body;
                    // without the flag it would be gzipped and the empty gzip
                    // member parsed as the next pipelined response. (isHead is the
                    // request-method flag computed at the top of run().)
                    compressedout = new CompressedResponseOutputStream(browserout, isHead, _keepalive, _headers);
                    compressedout.write(DataHelper.getUTF8(modifiedHeaders));
                    // Push headers before the body handoff so TTFB does not
                    // wait on MessageOutputStream's passive flush interval.
                    compressedout.flush();
                      s = new Sender(compressedout, serverin, "Server -> Client (Gzip) " +
                                     urlSuffix(req), _tunnelId, _log);
                    browserout = compressedout;
                } else {
                    browserout.write(DataHelper.getUTF8(modifiedHeaders));
                    browserout.flush();
                      s = new Sender(browserout, serverin, "Server -> Client " +
                                     urlSuffix(req), _tunnelId, _log);
                }
                // Keep the local backend's read timeout in lockstep with the
                // progress-scaled stall window, so a backend that pauses
                // mid-body gets the same patience the I2P side would, instead
                // of being killed at the flat header value.
                s.setReadTimeoutTuner(ms -> {
                    try {_webserver.setSoTimeout(ms);}
                    catch (IOException ioe) { /* socket gone; the copy fails on its next read */ }
                });
                if (_log.shouldDebug())
                    _log.debug("[HTTPServer] Running server-to-browser Compressed? " + _shouldCompress + " KeepAlive? " + _keepalive +
                               urlSuffix(req));
                // Non-keepalive: submit the body and free the handler thread
                // immediately. Keepalive still waits (runOnIO) so the next
                // request on this connection sees a finished body.
                if (!_keepalive) {
                    final Sender body = s;
                    final Sender reqSender = sender;
                    final OutputStream fServerOut = serverout;
                    final OutputStream fBrowserOut = browserout;
                    final CompressedResponseOutputStream fCompressedOut = compressedout;
                    final InputStream fBrowserIn = browserin;
                    final InputStream fServerIn = serverin;
                    final String fReq = req;
                    final Runnable finish = () -> finishTransfer(finished, body, reqSender,
                            fServerOut, fBrowserOut, fCompressedOut,
                            fBrowserIn, fServerIn, fReq);
                    bodySubmitted = handOffBody(_ioPool != null ? _ioPool.get() : null,
                            body, "Server -> Client" + urlSuffix(fReq), finish);
                    if (bodySubmitted) {
                        // Non-keepalive is already decided; publish waiter before
                        // the pool worker's finishTransfer so the caller does
                        // not race on waiter.get() after run() returns.
                        if (_waiter != null) {_waiter.set(1);}
                        // The handler thread detaches here, so nothing would
                        // supervise a worker blocked on a non-reading browser;
                        // attach the periodic watchdog before returning.
                        scheduleBodyWatchdog(body, finished, finish,
                                fBrowserIn, fBrowserOut, fServerIn, fServerOut);
                        return;
                    }
                    // Inline fallback: handOffBody already ran body + finish
                    return;
                }
                runOnIO(s, "Server -> Client" + urlSuffix(req));
            } catch (SSLException she) {
                if (_log.shouldError()) {_log.error("[HTTPServer] SSL error", she);}
                try {
                    if (_browser.getLocalPort() == 443) {_browser.reset();}
                    else {
                        if (browserout == null) {browserout = _browser.getOutputStream();}
                        browserout.write(ERR_UNAVAILABLE.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException ioe) { /* ignored */ }
                _keepalive = false;
            } catch (IOException ioe) {
                // DEBUG: usually the browser hanging up mid-request or mid-response,
                // not a server fault - the 105 lines this made in one run were all
                // "EOF reached before the end of the headers"
                if (_log.shouldDebug()) {
                    _log.debug("[HTTPServer] Error compressing -> " + ioe.getMessage() +
                               urlSuffix(req));
                }
                ioex = ioe;
                _keepalive = false;
            } finally {
                if (!bodySubmitted) {
                    finishTransfer(finished, s, sender, serverout, browserout,
                            compressedout, browserin, serverin, req);
                }
                // bodySubmitted: pool worker owns finishTransfer after the body
            }
        }

        /**
         * Attach a {@link BodyWatchdog} to a detached async body. The poll
         * interval is fixed at {@link BodyWatchdog#POLL_MS} (the SimpleTimer2
         * 5s minimum); the stall window itself is {@link #ioStallTimeoutMs}.
         * The watchdog cancels itself on the next tick after finishTransfer
         * sets the finished flag, so it never outlives the connection.
         *
         * @param body detached Server-to-Client Sender
         * @param finished request-wide finish-once flag
         * @param finish idempotent finish shared with the handoff
         * @param browserin browser-side request stream; may be null
         * @param browserout browser-side response stream; may be null
         * @param serverin I2P-side response body stream; may be null
         * @param serverout I2P-side request stream; may be null
         * @since 0.9.71+
         */
        private void scheduleBodyWatchdog(Sender body, AtomicBoolean finished,
                                          Runnable finish, InputStream browserin,
                                          OutputStream browserout, InputStream serverin,
                                          OutputStream serverout) {
            BodyWatchdog watchdog = new BodyWatchdog(body, finished, finish, browserin,
                    browserout, serverin, serverout, _log);
            try {
                _ctx.simpleTimer2().addPeriodicEvent(watchdog,
                        BodyWatchdog.POLL_MS, BodyWatchdog.POLL_MS);
            } catch (RuntimeException re) {
                // A context without a timer pool (headless tests) runs the
                // transfer unsupervised; socket soTimeout still bounds reads.
                if (_log.shouldWarn()) {
                    _log.warn("[HTTPServer] Unable to schedule body watchdog", re);
                }
            }
        }

        /**
         * Tear down a completed (or failed) request exactly once. Invoked from
         * the handler finally when the body ran on this thread, or from the
         * I/O pool worker after an async body handoff.
         *
         * @param finished CAS flag; first caller performs cleanup
         * @param body Server→Client Sender (may be null)
         * @param reqSender Client→Server Sender (may be null)
         * @param serverout local webserver output (may be null)
         * @param browserout browser-side output (may be null)
         * @param compressedout gzip wrapper (may be null)
         * @param browserin local webserver input (may be null)
         * @param serverin I2P-side response body (may be null)
         * @param req request URL for logging (may be null)
         * @since 0.9.71+
         */
        private void finishTransfer(AtomicBoolean finished, Sender body, Sender reqSender,
                                    OutputStream serverout, OutputStream browserout,
                                    CompressedResponseOutputStream compressedout,
                                    InputStream browserin, InputStream serverin,
                                    String req) {
            if (!finished.compareAndSet(false, true)) {return;}
            IOException ioex = null;
            if (body != null) {
                ioex = body.getFailure();
                if (ioex == null && reqSender != null) {ioex = reqSender.getFailure();}
            } else if (reqSender != null) {
                ioex = reqSender.getFailure();
            }
            if (ioex != null) {propagateFailure(ioex, req);}
            if (_waiter != null) {_waiter.set(_keepalive ? 2 : 1);} // We are now run inline, no need to notify()
            // Close the I2PSocket first to signal the streaming layer that
            // the connection is dead.  This lets browserout.close() (which
            // calls MessageOutputStream.flush → PacketLocal.waitForCompletion)
            // exit immediately instead of blocking up to getDisconnectTimeout().
            if (!_keepalive) try { _browser.close(); } catch (IOException ioe) { /* ignored */ }
            if (browserout != null) {
                try {
                    if (_keepalive) {
                        if (compressedout != null) {compressedout.finish();}
                        else {browserout.flush();}
                    } else {browserout.close();}
                } catch (IOException ioe) { /* ignored */ }
            }
            if (serverout != null) try { serverout.close(); } catch (IOException ioe) { /* ignored */ }
            if (!_keepalive && browserin != null) try { browserin.close(); } catch (IOException ioe) { /* ignored */ }
            if (serverin != null) try { serverin.close(); } catch (IOException ioe) { /* ignored */ }
            try { _webserver.close(); } catch (IOException ioe) { /* ignored */ }
            if (_log.shouldDebug()) {
                _log.debug("Finished server-to-browser: Compressed? " + _shouldCompress + " KeepAlive? " + _keepalive +
                           urlSuffix(req));
            }
        }

        /**
         * Read the server response headers, filter them for security,
         * and re-format them for the client.
         *
         * @param serverin stream from the webserver
         * @param timeout timeout for the header read
         * @param ctx the I2P app context
         * @return the formatted, filtered headers
         * @throws IOException if the webserver stream fails
         */
        static String readAndRewriteServerResponse(InputStream serverin, int timeout, I2PAppContext ctx) throws IOException {
            StringBuilder command = new StringBuilder(512);
            // Change headers to protect server identity
            Map<String, List<String>> headers = readHeaders(null, serverin, command, SERVER_SKIPHEADERS, ctx, timeout);

            SecurityHeaderBuilder.filterCacheControlHeaders(headers);
            SecurityHeaderBuilder.filterSetCookieHeaders(headers);

            List<String> contentTypeList = headers.get("Content-Type");
            String mimeType = "application/octet-stream";
            if (contentTypeList != null && !contentTypeList.isEmpty()) {
                mimeType = contentTypeList.get(0);
            }

            SecurityHeaderBuilder.addSecurityHeaders(headers, mimeType);

            return HttpHeaderFormatter.formatHeaders(headers, command);
        }

        /**
         * On failure, propagate a reset to the other end of the connection,
         * simplified from I2PTunnelRunner.
         *
         * @param ioex the failure that triggered the reset
         * @param req the request URL for logging
         */
        private void propagateFailure(IOException ioex, String req) {
            _keepalive = false;
            // Reset propagation, simplified from I2PTunnelRunner
            boolean i2pReset = false;
            if (ioex instanceof I2PSocketException) {
                I2PSocketException ise = (I2PSocketException) ioex;
                int status = ise.getStatus();
                i2pReset = status == I2PSocketException.STATUS_CONNECTION_RESET;
                if (i2pReset) {
                    if (_log.shouldDebug()) {
                        _log.warn("[HTTPServer] Received I2P RESET -> Resetting socket..." +
                                  urlSuffix(req));
                    }
                    try {_webserver.setSoLinger(true, 0);}
                    catch (IOException ioe) { /* ignored */ }
                }
            }
            if (!i2pReset && ioex instanceof SocketException) {
                String msg = ioex.getMessage();
                boolean sockReset = msg != null && msg.contains("reset");
                if (sockReset) {
                    if (_log.shouldDebug()) {
                        _log.warn("[HTTPServer] Received socket RESET ->  Resetting I2P socket..." +
                                  urlSuffix(req));
                    }
                    try {_browser.reset();}
                    catch (IOException ioe) { /* ignored */ }
                }
            }
        }
    }

    /**
     * Watchdog for an async (detached) body handoff. After {@link #handOffBody}
     * queues the Server-to-Client Sender and the handler thread returns,
     * nothing supervises the pool worker: a browser that stops reading pins it
     * in a blocked write indefinitely (socket soTimeout bounds reads only).
     * This periodic event polls the Sender's last-read stamp and, once no
     * progress is made for {@link #ioStallTimeoutMs}, closes the streams to
     * unblock the worker and runs the idempotent finish so the connection is
     * torn down exactly once. Polling is every 5s, the SimpleTimer2 minimum;
     * detection therefore lands within one poll of the stall window.
     *
     * @since 0.9.71+
     */
    private static final class BodyWatchdog extends SimpleTimer2.TimedEvent {
        /** SimpleTimer2 rejects periods below 5s. */
        private static final long POLL_MS = 5000L;

        /** The sender copying the request body. */
        private final Sender _body;
        /** Set when the copy is finished. */
        private final AtomicBoolean _finished;
        /** Callback run when the copy finishes. */
        private final Runnable _finish;
        /** The browser's input stream. */
        private final InputStream _browserin;
        /** The browser's output stream. */
        private final OutputStream _browserout;
        /** The web server's input stream. */
        private final InputStream _serverin;
        /** The web server's output stream. */
        private final OutputStream _serverout;
        /** Logger for this copy. */
        private final Log _log;

        /**
         * Watches a stalled response body and aborts the connection.
         *
         * @param body the detached Server-to-Client Sender being supervised
         * @param finished request-wide finish-once flag
         * @param finish idempotent finish to run once a stall is confirmed
         * @param browserin browser-side request stream; may be null
         * @param browserout browser-side response stream; may be null
         * @param serverin I2P-side response body stream; may be null
         * @param serverout I2P-side request stream; may be null
         * @param log may be null
         */
        BodyWatchdog(Sender body, AtomicBoolean finished, Runnable finish,
                     InputStream browserin, OutputStream browserout,
                     InputStream serverin, OutputStream serverout, Log log) {
            _body = body;
            _finished = finished;
            _finish = finish;
            _browserin = browserin;
            _browserout = browserout;
            _serverin = serverin;
            _serverout = serverout;
            _log = log;
        }

        @Override
        public void timeReached() {
            if (_finished.get()) {
                cancel();
                return;
            }
            long stallMs = computeStallWindowMs(ioStallTimeoutMs, _body.getBytesTransferred());
            long stallNs = stallMs * 1_000_000L;
            if (!isBodyWatchdogStalled(_body.getLastReadNanos(), System.nanoTime(), stallNs)) {
                // Self-reschedule; cancel() in a later run terminates us.
                schedule(POLL_MS);
                return;
            }
            _stallEventCount.incrementAndGet();
            if (_log != null && _log.shouldWarn()) {
                // Name the side that actually stopped moving, and say so plainly
                // when the stamps cannot tell the two apart. See
                // classifyStalledSide() for why "no read progress" alone used to
                // point every bulk-transfer diagnosis at the local backend.
                long nowNanos = System.nanoTime();
                long readAgeMs = (nowNanos - _body.getLastReadNanos()) / 1_000_000L;
                long writeAgeMs = (nowNanos - _body.getLastWriteNanos()) / 1_000_000L;
                String side = classifyStalledSide(readAgeMs, writeAgeMs,
                                                 _body.getBytesTransferred());
                _log.warn("[HTTPServer] Async body stalled on the " + side + " -> Closing" +
                          "\n* Read idle: " + readAgeMs + "ms * Write idle: " + writeAgeMs +
                          "ms * Transferred: " + _body.getBytesTransferred() + " bytes" +
                          " * Window: " + stallMs + "ms" +
                          "\n* Body: " + _body.getName());
            }
            closeQuietly(_browserin);
            closeQuietly(_serverin);
            closeQuietly(_browserout);
            closeQuietly(_serverout);
            _finish.run();
            cancel();
        }
    }

    /** Pumps a client request body through to the I2P side and back. */
    static class Sender implements Runnable {
        /** Copy buffer size for both directions. */
        private static final int BUF_SIZE = 16*1024;
        /** The output stream to the browser. */
        private final OutputStream _out;
        /** The input stream from the browser. */
        private final InputStream _in;
        /** Connection name used in the logs. */
        private final String _name;
        /**
         * Identity of the far side, e.g. {@code [skank / aanoquc2]}. The
         * request direction used to log a bare "from Client -&gt; Server" with no
         * indication of which eepsite or client a transfer belonged to, which
         * made slow small transfers impossible to attribute.
         *
         * @since 0.9.71+
         */
        private volatile String _targetLabel;
        /**
         * Parsed request line for the transfer, set once known. The request
         * direction builds the Sender before the request line is read, so this
         * is applied after the fact and rendered only when present.
         *
         * @since 0.9.71+
         */
        private volatile String _requestLabel;
        /** Logger, shadows the one in the superclass. */
        private final Log _log;
        /** The failure that ended the copy, if any. */
        private volatile IOException _failure;
        /** Total bytes transferred successfully (read from _in, written to _out). */
        private volatile long _bytesTransferred;
        /** NanoTime of the last successful read from _in. Used for stall detection. */
        private volatile long _lastReadNanos;
        /**
         * NanoTime of the last completed write to _out. Tracked separately from
         * the read stamp because the two stall for entirely different reasons:
         * a stale read stamp means the local source is not delivering, while a
         * fresh read with a stale write stamp means the destination is not
         * accepting, i.e. I2P egress congestion. Both used to look identical,
         * because the read stamp was only advanced after the write returned, so a
         * write blocked on congestion was reported as a stalled source and
         * pointed every diagnosis at the wrong layer.
         *
         * @since 0.9.71+
         */
        private volatile long _lastWriteNanos;
        /**
         * Optional hook raising the source socket's read timeout as the body
         * earns a larger stall window. A response Sender reads the local
         * backend socket under a fixed soTimeout; without this hook that
         * timeout stays at the header-read value for the whole body, so a
         * slow-but-live backend generating a bulk response is killed at 60s
         * while the I2P-side watchdog would tolerate up to
         * {@link I2PTunnelHTTPServer#MAX_STALL_WINDOW_MS}. Null for senders
         * with no tunable source (e.g. the I2P-side request direction).
         *
         * @since 0.9.72+
         */
        private volatile IntConsumer _readTimeoutTuner;
        /**
         * Stall window (ms) last pushed through {@link #_readTimeoutTuner};
         * 0 until the first application. The tuner is only invoked when the
         * progress-scaled window actually changes, so the per-chunk cost is
         * a long compare.
         *
         * @since 0.9.72+
         */
        private long _lastAppliedWindowMs;

        /**
         * Create a Sender to copy data from input to output streams.
         * Caller MUST close streams after Sender completes.
         *
         * @param out the output stream to write to
         * @param in the input stream to read from
         * @param name descriptive name for logging
         * @param log the logging instance
         */
        public Sender(OutputStream out, InputStream in, String name, Log log) {
            this(out, in, name, null, log);
        }

        /**
         * Create a Sender with an explicit far-side label.
         *
         * @param out the output stream to write to
         * @param in the input stream to read from
         * @param name descriptive name for logging
         * @param targetLabel far-side identity, e.g. {@code [skank / aanoquc2]};
         *                    may be null or empty
         * @param log the logging instance
         * @since 0.9.71+
         */
        public Sender(OutputStream out, InputStream in, String name, String targetLabel, Log log) {
            _out = out;
            _in = in;
            _name = name;
            _targetLabel = (targetLabel != null && !targetLabel.isEmpty()) ? targetLabel : null;
            _log = log;
        }

        /**
         * Set the far-side label. The request direction constructs the Sender
         * before the request line is parsed, so the label is applied once it is
         * known rather than at construction.
         *
         * @param label far-side identity, or null/empty to leave unset
         * @since 0.9.71+
         */
        void setTargetLabel(String label) {
            _targetLabel = (label != null && !label.isEmpty()) ? label : null;
        }

        /**
         * Set the parsed request line once it is known.
         *
         * @param request request line or path; null/empty/"Unknown request" leaves it unset
         * @since 0.9.71+
         */
        void setRequestLabel(String request) {
            _requestLabel = (request != null && !request.isEmpty()
                             && !request.equals("Unknown request")) ? request : null;
        }

        /**
         * Install the hook that raises the source socket read timeout as this
         * body earns a larger stall window. Call before {@link #run()}; the
         * first application happens after the first body chunk is read.
         *
         * @param tuner receives the new read timeout in ms, or null for none
         * @since 0.9.72+
         */
        void setReadTimeoutTuner(IntConsumer tuner) {
            _readTimeoutTuner = tuner;
        }

        /**
         * Push the progress-scaled stall window through to the source socket's
         * read timeout, but only when the window actually changes, a tier
         * crossing, so the per-chunk cost is one long compare. A no-op when
         * no tuner is installed.
         *
         * @since 0.9.72+
         */
        private void applySourceReadTimeout() {
            IntConsumer tuner = _readTimeoutTuner;
            if (tuner == null) {return;}
            long windowMs = computeStallWindowMs(ioStallTimeoutMs, _bytesTransferred);
            if (windowMs == _lastAppliedWindowMs) {return;}
            _lastAppliedWindowMs = windowMs;
            tuner.accept((int) Math.min(windowMs, Integer.MAX_VALUE));
        }

        /**
         * Describe the far side for a log line: label plus optional request
         * target, e.g. {@code [skank / aanoquc2] GET /installers/i2pinstall.exe}.
         * Pure and static so the formatting is testable without streams.
         *
         * @param label far-side identity; may be null
         * @param request parsed request line or path; may be null
         * @return the suffix to append to a transfer name, "" if neither is known
         * @since 0.9.71+
         */
        static String describeTarget(String label, String request) {
            boolean hasLabel = label != null && !label.isEmpty();
            boolean hasRequest = request != null && !request.isEmpty()
                && !request.equals("Unknown request");
            if (!hasLabel && !hasRequest)
                return "";
            StringBuilder sb = new StringBuilder();
            sb.append(' ').append(hasLabel ? label : "[?]");
            if (hasRequest)
                sb.append(' ').append(request);
            return sb.toString();
        }

        /**
         * Format a transfer rate as {@code 10.5K/s} / {@code 1.2M/s}, in bytes
         * per second using 1000-based units. Suffixes are B/s below 1 KB/s, K/s
         * below 1 MB/s, and M/s above, so a rate never reads as a bare number
         * with an ambiguous "kbps" that is easy to mistake for bits per second.
         *
         * @param bytes total bytes transferred, negative if the count is not trusted
         * @param secs elapsed seconds; a value of 0 or less formats as "0.0B/s"
         * @return formatted rate, e.g. {@code 60.6K/s}; "0.0B/s" if secs &lt;= 0
         * @since 0.9.71+
         */
    static String formatTransferRate(long bytes, double secs) {
        if (secs <= 0 || bytes < 0)
            return "0.0B/s";
        // Bytes per second, 1000-based. The unit is per second of transfer, not
        // bits per second: the byte count is already known and dividing by eight
        // to get a "kbps" figure only made the number harder to compare against
        // the sizes in the same line.
        double perSec = bytes / secs;
        if (perSec >= 1_000_000.0)
            return String.format("%.1fM/s", perSec / 1_000_000.0);
        if (perSec >= 1_000.0)
            return String.format("%.1fK/s", perSec / 1_000.0);
        return String.format("%.1fB/s", perSec);
    }

        /**
         * Copy data from the input stream to the output stream, flushing
         * after each write to push data through any intermediate buffers
         * (e.g. GZIP or MessageOutputStream). Without explicit flushes,
         * data can accumulate in GZIP's deflater buffer or the streaming
         * layer's MessageOutputStream buffer, causing sawtooth throughput
         * patterns and intermittent stalls on the receiving end.
         * <p>
         * Tracks bytes transferred and detects stalls: if no data is read
         * for {@link I2PTunnelHTTPServer#ioStallTimeoutMs}, the loop breaks
         * and the partial transfer is logged. This prevents a dead peer from
         * holding a handler or I/O thread indefinitely.
         */
        @Override
        public void run() {
            if (_log != null && _log.shouldDebug()) {_log.debug("[HTTPServer] Begin sending " + _name);}
            long startNanos = System.nanoTime();
            _lastReadNanos = startNanos;
            _lastWriteNanos = startNanos;
            try {
                byte[] buf = new byte[BUF_SIZE];
                int read;
                while ((read = _in.read(buf)) != -1) {
                    long now = System.nanoTime();
                    long stallNs = now - _lastReadNanos;
                    // Stamp the read before the write. The write is the step that
                    // blocks on I2P egress congestion, so advancing this stamp
                    // only after it returned made a congested destination
                    // indistinguishable from a silent local source.
                    _lastReadNanos = now;
                    _bytesTransferred += read;
                    // Raise the source read timeout in step with the earned
                    // window before the next (possibly long) blocking read.
                    applySourceReadTimeout();
                    _out.write(buf, 0, read);
                    _out.flush();
                    _lastWriteNanos = System.nanoTime();
                    // Progress includes the write: a slow but advancing I2P flush is
                    // not a stall. Idle is measured from the last completed copy step
                    // by runOnIO via getLastReadNanos(); the inter-read break below
                    // only fires when successive reads themselves were far apart.
                    if (stallNs > computeStallWindowMs(ioStallTimeoutMs, _bytesTransferred) * 1_000_000L) {
                        _stallEventCount.incrementAndGet();
                        if (_log != null && _log.shouldWarn()) {
                            _log.warn("[HTTPServer] Stall detected in " + _name +
                                      " — no data for " + (stallNs / 1_000_000L) + "ms, " +
                                      _bytesTransferred + " bytes transferred");
                        }
                        // Record the truncation as a failure: without it finishTransfer
                        // sees a clean completion, keeps the connection alive, and the
                        // next response is pipelined onto a body that stopped short of
                        // Content-Length — the browser splices the two together.
                        _failure = new IOException("Sender stalled mid-body in " + _name +
                                                  " after " + _bytesTransferred + " bytes");
                        break;
                    }
                }
                long elapsed = System.nanoTime() - startNanos;
                String target = describeTarget(_targetLabel, _requestLabel);
                if (_log != null && _log.shouldInfo() && elapsed > 5_000_000_000L) {
                    double secs = elapsed / 1_000_000_000.0;
                    _log.info("[HTTPServer] Done sending " + _name + target + ": " +
                              _bytesTransferred + " bytes in " + String.format("%.1f", secs) + "s " +
                              formatTransferRate(_bytesTransferred, secs));
                } else if (_log != null && _log.shouldDebug()) {
                    _log.debug("[HTTPServer] Done sending " + _name + target);
                }
            } catch (IOException ioe) {
                // The outer null test is gone because DataHelper.messageOf never
                // returns null: a message-less stream failure previously skipped
                // this whole block and logged nothing at all, which is a silent
                // failure rather than a quiet one.
                final String sendErr = DataHelper.messageOf(ioe);
                if (sendErr.indexOf("Input stream closed") >= 0 ||
                    sendErr.indexOf("Input stream error") >= 0 ||
                    sendErr.indexOf("Socket closed") >= 0 ||
                    sendErr.indexOf("Interrupted") >= 0) {
                    // client closed connection early, or thread interrupted for cancellation
                    if (_log != null && _log.shouldDebug()) {_log.debug("[HTTPServer] Error sending " + _name + " -> " + sendErr);}
                } else {
                    if (_log != null && _log.shouldWarn()) {_log.warn("[HTTPServer] Error sending " + _name + " -> " + sendErr);}
                }
                synchronized(this) {_failure = ioe;}
            }
        }

        /**
         * Get any IOException that occurred during the copy.
         *
         * @return the failure exception, or null if successful
         * @since 0.9.33
         */
        public synchronized IOException getFailure() {
            return _failure;
        }

        /**
         * Total bytes successfully transferred (read from input, written to output).
         *
         * @return bytes transferred, updated live during the transfer
         * @since 0.9.71+
         */
        public long getBytesTransferred() { return _bytesTransferred; }

        /**
         * The label this transfer is logged and reported under.
         *
         * @return the transfer description ("Server -&gt; Client ..."); never null
         * @since 0.9.71+
         */
        public String getName() { return _name; }

        /**
         * NanoTime of the last successful read. Used by callers to detect stalls
         * without interrupting the Sender thread.
         *
         * @return {@link java.lang.System#nanoTime} of the last successful read
         * @since 0.9.71+
         */
        public long getLastReadNanos() { return _lastReadNanos; }

        /**
         * NanoTime of the last completed write. Compared against
         * {@link #getLastReadNanos} to tell a silent local source from a
         * congested I2P destination.
         *
         * @return {@link java.lang.System#nanoTime} of the last completed write
         * @since 0.9.71+
         */
        public long getLastWriteNanos() { return _lastWriteNanos; }

        /**
         * Abort this transfer without ever running it, closing both ends so the
         * connection is torn down promptly.
         * <p>Used when the body cannot be handed to the I/O pool. Running the
         * body on the caller's handler thread instead would pin that thread for
         * the whole transfer, which is how one saturated body starves the
         * runner pool and takes the entire service offline; a client that gets
         * a fast close retries, while one that gets a pinned thread waits
         * indefinitely.
         *
         * @param cause recorded as the transfer failure, propagated to the caller
         * @since 0.9.71+
         */
        public void abort(IOException cause) {
            synchronized (this) {
                if (_failure == null) {
                    _failure = cause != null ? cause
                             : new IOException("Body transfer aborted before it started");
                }
            }
            closeQuietly(_in);
            closeQuietly(_out);
        }
    }

    /**
     * Minimum response size in bytes before gzip compression is applied.
     */
    private static final int MIN_TO_COMPRESS = 1024;

    /** Gzips the response on its way to the browser. */
    private static class CompressedResponseOutputStream extends HTTPResponseOutputStream {
        /** The gzip stream for the response. */
        private GZIPOutputStream _gzipOut;
        /** Request headers used to judge compression. */
        private final String _requestHeaders;

        /**
         * Create a compressed response output stream.
         *
         * @param o the underlying output stream
         * @param isHead true if this filters the response to a HEAD request — no
         *               body is expected, so compression must stay off or the
         *               empty gzip member would be parsed as the next pipelined response
         * @param keepalive if true, don't close the stream on finish
         * @param requestHeaders the HTTP request headers for Accept-Encoding check
         */
        public CompressedResponseOutputStream(OutputStream o, boolean isHead, boolean keepalive, String requestHeaders) {
            super(o, false, keepalive, isHead, null);
            _requestHeaders = requestHeaders;
        }

        /**
         * Finish gzipping but don't close the output stream if keepalive is true.
         * With keepalive off (or no gzip stream active) this flushes and closes.
         *
         * @throws IOException if finishing the gzip stream fails
         * @since 0.9.62
         */
        public void finish() throws IOException {
            if (getKeepAliveOut()) {
                if (_gzipOut != null) {_gzipOut.finish();}
                else {flush();}
            } else {close();}
        }

        /** Content types that are already compressed; skip re-compression. */
        private static final Set<String> COMPRESSED_TYPES = new HashSet<>(
            Arrays.asList("image/gif", "image/jpeg", "image/jpg", "image/png",
                          "image/tiff", "image/webp", "font/woff2",
                          "application/compress", "application/bzip2",
                          "application/gzip", "application/x-bzip",
                          "application/x-bzip2", "application/x-gzip",
                          "application/zip"));

        /**
         * Determine if the response should be gzip-compressed.
         * Skips small responses, images, audio, video, and already-compressed formats.
         * Compression is inline on the server side, decompression on the client side,
         * so we avoid re-compressing formats that are already compressed.
         *
         * @return true if the response should be compressed
         */
    @Override
    protected boolean shouldCompress() {
        return (_dataExpected < 0 || _dataExpected >= MIN_TO_COMPRESS) &&
               // must be null as we write the header in finishHeaders(), can't have two
               (_contentEncoding == null) &&
               (_contentType == null ||
                !_contentType.startsWith("audio/") &&
                !_contentType.startsWith("video/") &&
                !COMPRESSED_TYPES.contains(_contentType)) &&
               // browser must accept x-i2p-gzip
               (_requestHeaders != null && _requestHeaders.indexOf("x-i2p-gzip") >= 0);
    }

        /**
         * Write the Content-Encoding header if compression is enabled.
         *
         * @throws IOException if writing the header fails
         */
        @Override
        protected void finishHeaders() throws IOException {
            // TODO if browser supports gzip, send as gzip
            if (shouldCompress())
                out.write(DataHelper.getASCII("Content-Encoding: x-i2p-gzip\r\n"));
            super.finishHeaders();
        }

        /**
         * Wrap the output stream with gzip compression if compression is enabled.
         *
         * @throws IOException if creating the gzip stream fails
         */
        @Override
        protected void beginProcessing() throws IOException {
            if (shouldCompress()) {
                _gzipOut = new GZIPOutputStream(out);
                out = _gzipOut;
            }
        }
    }

    /**
     * Add an entry to the multimap. If the key already exists, appends
     * the value to the existing list.
     *
     * @param headers the header multimap to modify
     * @param key the header name
     * @param value the header value to add
     */
    static void addEntry(Map<String, List<String>> headers, String key, String value) {
        List<String> entry = headers.get(key);
        if (entry == null) {
            entry = new ArrayList<>(1);
            headers.put(key, entry);
        }
        entry.add(value);
    }

    /**
     * Remove the other matching entries and set this entry as the only one.
     *
     * @param headers the header multimap to modify
     * @param key the header name
     * @param value the header value to set (replaces all existing values for this key)
     */
    static void setEntry(Map<String, List<String>> headers, String key, String value) {
        List<String> entry = headers.get(key);
        if (entry == null) {
            entry = new ArrayList<>(1);
            headers.put(key, entry);
        } else {
            entry.clear();
        }
        entry.add(value);
    }

    /**
     * Get the first matching entry in the multimap.
     *
     * @param headers the header multimap to search
     * @param key the header name to look up
     * @return the first matching entry or null if not present
     */
    private static String getEntryOrNull(Map<String, List<String>> headers, String key) {
      List<String> entries = headers.get(key);
      if (entries == null || entries.isEmpty()) {return null;}
      else {return entries.get(0);}
    }

    /**
     * From I2P to server: socket non-null, in null.
     * From server to I2P: socket null, in non-null.
     * <p>
     * Note: This does not handle RFC 2616 header line splitting,
     * which is obsoleted in RFC 7230.
     *
     * @param socket if null, use in as InputStream
     * @param in if null, use socket.getInputStream() as InputStream
     * @param command out parameter, first line
     * @param skipHeaders MUST be lower case
     * @param ctx I2P app context for clock and timeout
     * @param initialTimeout timeout for the first line read
     * @return the parsed header multimap
     * @throws SocketTimeoutException if timeout is reached before newline
     * @throws EOFException if EOF is reached before newline
     * @throws LineTooLongException if one header too long, or too many headers, or total size too big
     * @throws RequestTooLongException if too long
     * @throws BadRequestException on bad headers
     * @throws IOException on other errors in the underlying stream
     */
    public static Map<String, List<String>> readHeaders(I2PSocket socket, InputStream in, StringBuilder command,
                                                        Set<String> skipHeaders, I2PAppContext ctx, long initialTimeout) throws IOException {
        return readHeadersInternal(socket, in, command, skipHeaders, ctx, initialTimeout);
    }

    /**
     * Read headers from the input stream, using an array of skip headers.
     *
     * @param socket if non-null, read from socket; otherwise read from in
     * @param in if non-null and socket is null, read from this stream
     * @param command output buffer for the first request line
     * @param skipHeaders array of lower-case header names to filter out
     * @param ctx I2P app context for clock and timeout
     * @param initialTimeout timeout for the first line read
     * @return the parsed header multimap
     * @throws IOException on I/O errors
     */
    public static Map<String, List<String>> readHeaders(I2PSocket socket, InputStream in, StringBuilder command,
                                                        String[] skipHeaders, I2PAppContext ctx, long initialTimeout) throws IOException {
        return readHeadersInternal(socket, in, command, new HashSet<>(Arrays.asList(skipHeaders)), ctx, initialTimeout);
    }

    /**
     * Read and parse HTTP headers, filtering out specified headers and enforcing size limits.
     *
     * @param socket if non-null, read from socket; otherwise read from in
     * @param in if non-null and socket is null, read from this stream
     * @param command output buffer for the first request line
     * @param skipHeaders set of lower-case header names to filter out
     * @param ctx I2P app context for clock and timeout
     * @param initialTimeout timeout for the first line read
     * @return the parsed header multimap
     * @throws SocketTimeoutException if the first line read exceeds initialTimeout
     * @throws EOFException on unexpected EOF
     * @throws LineTooLongException if a line, total, or count exceeds limits
     * @throws RequestTooLongException if the request line is too long
     * @throws BadRequestException on malformed headers
     * @throws IOException on other I/O errors
     */
    private static Map<String, List<String>> readHeadersInternal(I2PSocket socket, InputStream in, StringBuilder command,
                                                        Set<String> skipHeaders, I2PAppContext ctx, long initialTimeout) throws IOException {
        HashMap<String, List<String>> headers = new HashMap<>();
        StringBuilder buf = new StringBuilder(128);

        // slowloris / darkloris
        long expire = ctx.clock().now() + initialTimeout + HEADER_FINISH_TIMEOUT;
        if (socket != null) {
            try {
                readLine(socket, command, initialTimeout);
            } catch (LineTooLongException ltle) {
                // convert for first line
                throw new RequestTooLongException("Request too long (Max allowed: " + MAX_LINE_LENGTH + ")");
            }
        } else {
             boolean ok = DataHelper.readLine(in, command);
             if (!ok)
                 throw new EOFException("EOF reached before the end of the headers");
        }

        int totalSize = command.length();
        int i = 0;
        while (true) {
            if (++i > MAX_HEADERS) {
                throw new LineTooLongException("Too many header lines (Max allowed: " + MAX_HEADERS + ")");
            }
            buf.setLength(0);
            if (socket != null) {
                readLine(socket, buf, expire - ctx.clock().now());
            } else {
                 boolean ok = DataHelper.readLine(in, buf);
                 if (!ok)
                     throw new BadRequestException("EOF reached before the end of the headers");
            }
            if ( (buf.length() == 0) ||
                 ((buf.charAt(0) == '\n') || (buf.charAt(0) == '\r')) ) {
                // end of headers reached
                return headers;
            } else {
                if (ctx.clock().now() > expire) {
                    throw new SocketTimeoutException("Timeout (" + ((initialTimeout + HEADER_FINISH_TIMEOUT) / 1000) + "s) receiving headers");
                }
                int split = buf.indexOf(":");
                if (split <= 0)
                    throw new BadRequestException("Invalid HTTP header, missing colon: \"" + buf + "\" request: \"" + command + '"');
                totalSize += buf.length();
                if (totalSize > MAX_TOTAL_HEADER_SIZE)
                    throw new LineTooLongException("Request + headers too big");
                String name = buf.substring(0, split).trim();
                String value = null;
                if (buf.length() > split + 1) {value = buf.substring(split+1).trim();} // ":"
                else {value = "";}

                String lcName = name.toLowerCase(Locale.US);
                if ("accept-encoding".equals(lcName)) {name = "Accept-Encoding";}
                else if ("x-accept-encoding".equals(lcName)) {name = "X-Accept-Encoding";}
                else if ("x-forwarded-for".equals(lcName)) {name = "X-Forwarded-For";}
                else if ("x-forwarded-server".equals(lcName)) {name = "X-Forwarded-Server";}
                else if ("x-forwarded-host".equals(lcName)) {name = "X-Forwarded-Host";}
                else if ("forwarded".equals(lcName)) {name = "Forwarded";}
                else if ("user-agent".equals(lcName)) {name = "User-Agent";}
                else if ("referer".equals(lcName)) {name = "Referer";}
                else if ("connection".equals(lcName)) {name = "Connection";}
                else if ("host".equals(lcName)) {name = "Host";}
                else if (lcName.contains("-encoding") && !lcName.contains("accept") && socket != null) {
                    try {socket.close();}
                    catch (IOException ioe) { /* ignored */ }
                    throw new BadRequestException("Invalid HTTP header: \"" + name + "\" -> Terminating connection...");
                }
                // For incoming, we remove certain headers to prevent spoofing.
                // For outgoing, we remove certain headers to improve anonymity.
                boolean skip = skipHeaders.contains(lcName);
                if (skip) {continue;}

                addEntry(headers, name, value);
            }
        }
    }

    /**
     * Read a line terminated by newline, with a total read timeout.
     * <p>
     * Warning - strips \n but not \r
     * Warning - 8KB line length limit as of 0.7.13, @throws IOException if exceeded
     * Warning - not UTF-8.
     *
     * @param socket the socket to read the line from
     * @param buf StringBuilder the line is appended to, without its newline
     * @param timeout throws SocketTimeoutException immediately if zero or negative
     * @throws SocketTimeoutException if timeout is reached before newline
     * @throws EOFException if EOF is reached before newline
     * @throws LineTooLongException if too long
     * @throws IOException on other errors in the underlying stream
     * @since 0.9.19 modified from {@link DataHelper}
     */
    private static void readLine(I2PSocket socket, StringBuilder buf, long timeout) throws IOException {
        if (timeout <= 0) {throw new SocketTimeoutException();}
        long expires = System.currentTimeMillis() + timeout;
        InputStream in = socket.getInputStream();
        int c;
        int i = 0;
        socket.setReadTimeout(timeout);
        while ((c = in.read()) != -1) {
            if (++i > MAX_LINE_LENGTH) {
                throw new LineTooLongException("Line too long (Maximum characters permitted: " + MAX_LINE_LENGTH + ")");
            }
            if (c == '\n') {break;}
            long newTimeout = expires - System.currentTimeMillis();
            if (newTimeout <= 0) {throw new SocketTimeoutException();}
            buf.append((char)c);
            if (newTimeout != timeout) {
                timeout = newTimeout;
                socket.setReadTimeout(timeout);
            }
        }
        if (c == -1) {
            if (System.currentTimeMillis() >= expires) {throw new SocketTimeoutException();}
            else {throw new EOFException();}
        }
    }

    /**
     * Check the request command against the HTTP blocklist.
     * If matched, logs the blocked destination, closes the socket,
     * and throws BadRequestException.
     *
     * @param socket the incoming I2P socket to close if blocked
     * @param command the request command string to check
     * @throws BadRequestException if the request matches a blocklist entry
     * @throws IOException if closing the socket fails
     */
    private void processBlocklist(I2PSocket socket, StringBuilder command) throws IOException {
        if (_blocklistManager == null) {return;}
        if (_blocklistManager.shouldBlockRequest(command)) {
            String matchedString = _blocklistManager.getMatchedBlocklistString(command);
            String peerB32 = socket.getPeerDestination().toBase32();
            _blocklistManager.logBlockedDestination(peerB32);
            try {socket.close();}
            catch (IOException ioe) {_log.error("[HTTPServer] " + getTunnelLogId(socket.getThisDestination()) + " Error closing socket (" + ioe.getMessage() + ")");}
            throw new BadRequestException(command.toString() + "-> Matches blocklist entry \"" + matchedString + "\"");
        }
    }

    /**
     * Thrown when a header line exceeds MAX_LINE_LENGTH,
     * when there are too many headers, or when total header size exceeds MAX_TOTAL_HEADER_SIZE.
     *
     * @since 0.9.19
     */
    private static class LineTooLongException extends IOException {
        /**
         * Creates with the given message.
         *
         * @param s detail message passed to IOException
         */
        public LineTooLongException(String s) {super(s);}
    }

    /**
     * Thrown when the request line exceeds MAX_LINE_LENGTH.
     */
    private static class RequestTooLongException extends IOException {
        /**
         * Creates with the given message.
         *
         * @param s detail message passed to IOException
         */
        public RequestTooLongException(String s) {super(s);}
    }

    /**
     * Thrown when HTTP headers are malformed.
     */
    private static class BadRequestException extends IOException {
        /**
         * Creates with the given message.
         *
         * @param s detail message passed to IOException
         */
        public BadRequestException(String s) {super(s);}
    }
}
