package net.i2p.router.transport.ntcp;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.BufferOverflowException;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.NoConnectionPendingException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.BanLogger;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.RandomSource;
import net.i2p.util.SystemVersion;
import net.i2p.util.TryCache;

/**
 * The main NTCP NIO event loop thread responsible for high-throughput, low-latency
 * handling of inbound and outbound NTCP connections using non-blocking I/O.
 *
 * <p>This class is optimized for minimal overhead and maximum event dispatch efficiency.
 * All hot paths (e.g., {@link #processRead}, {@link #processWrite}, {@link #runDelayedEvents})
 * avoid synchronization, allocations, and unnecessary syscalls.
 *
 * <p>Key performance characteristics:
 * <ul>
 *   <li>Fixed 200ms selector timeout for consistent latency</li>
 *   <li>Immediate {@code wakeup()} on pending I/O</li>
 *   <li>Duplicate-free write request tracking via {@code ConcurrentHashSet}</li>
 *   <li>Spin detection: yields briefly when select() returns repeatedly without blocking</li>
 *   <li>Failsafe iteration every 2 seconds to clean stale connections</li>
 *   <li>Efficient buffer pooling with bounded size</li>
 * </ul>
 */
class EventPumper implements Runnable {
    private final RouterContext _context;
    private final Log _log;
    private volatile boolean _alive;
    private Selector _selector;
    private final Set<NTCPConnection> _wantsWrite = new ConcurrentHashSet<>(32);
    /**
     * The following 3 are unbounded and lockless for performance in runDelayedEvents()
     */
    private final Queue<NTCPConnection> _wantsRead = new ConcurrentLinkedQueue<>();
    private final Queue<ServerSocketChannel> _wantsRegister = new ConcurrentLinkedQueue<>();
    private final Queue<NTCPConnection> _wantsConRegister = new ConcurrentLinkedQueue<>();
    private final NTCPTransport _transport;
    private final ObjectCounter<String> _blockedIPs;
    private final ObjectCounter<String> _failedInboundEncryption;
    private long _expireIdleWriteTime;
    /** Tracks consecutive select() calls that returned immediately (tight loop guard) */
    private int _consecutiveFastSelects;
    /** After this many consecutive immediate select returns, yield briefly */
    private static final int MAX_CONSECUTIVE_FAST_SELECTS = 100;
    private static final boolean USE_DIRECT = false;
    private final boolean _nodelay;

    // Outbound retry throttling (non-hot path, kept for robustness)
    private final Map<Hash, Long> _failedOutboundAttempts = new ConcurrentHashMap<>();
    private final Map<Hash, Integer> _failedOutboundCount = new ConcurrentHashMap<>();
    private static final long MIN_RETRY_INTERVAL = 500;
    private static final int MAX_RETRY_INTERVAL = 5 * 60 * 1000; // 5 minutes
    private static final float RETRY_BACKOFF_FACTOR = 1.5f;
    /** Max consecutive failures before giving up on a peer entirely (until map clearance) */
    private static final int MAX_OUTBOUND_RETRY_COUNT = 3;
    private long _lastRetryMapClear = System.currentTimeMillis();
    private static final long RETRY_MAP_CLEAR_INTERVAL = 5 * 60 * 1000L; // 5 minutes
    /** Max entries before clearing retry maps to prevent unbounded growth under DoS */
    private static final int MAX_RETRY_MAP_SIZE = 1024;

    // Cached snapshot of the ntcp.inboundConn one-minute rate, refreshed at most
    // once per INBOUND_RATE_REFRESH_MS. Reading the four volatile fields below is
    // lock-free, so the accept path no longer grabs the global Rate monitor and
    // runs computeAverages() (six bucket reads) per connection during a flood.
    private static final long INBOUND_RATE_REFRESH_MS = 1000;
    private volatile long _inboundSnapshotAt;
    private volatile int _inboundCurrentCount;
    private volatile int _inboundLastCount;
    private volatile long _inboundPeriodStart;

    /**
     * This probably doesn't need to be bigger than the largest typical
     * message, which is a 5-slot VTBM (~2700 bytes).
     * The occasional larger message can use multiple buffers.
     */
    private static final int BUF_SIZE = 8 * 1024;

    private static class BufferFactory implements TryCache.ObjectFactory<ByteBuffer> {
        /**
         * Create a new buffer of BUF_SIZE, direct or heap.
         */
        @Override
        public ByteBuffer newInstance() {
            if (USE_DIRECT) {
                return ByteBuffer.allocateDirect(BUF_SIZE);
            } else {
                return ByteBuffer.allocate(BUF_SIZE);
            }
        }
    }

    /**
     * Every few seconds, iterate across all ntcp connections just to make sure
     * we have their interestOps set properly (and to expire any looong idle cons).
     * As the number of connections grows, we should try to make this happen
     * less frequently (or not at all), but while the connection count is small,
     * the time to iterate across them to check a few flags shouldn't be a problem.
     * @return whether slow
     */
    private static final boolean IS_SLOW = SystemVersion.isSlow();
    private static volatile long _failsafeIterationFreq = 2 * 1000L;
    private static final long MIN_FAILSAFE_FREQ = 2 * 1000L;
    private static final long MAX_FAILSAFE_FREQ = 30 * 1000L;
    /**
     * Max idle loop iterations per second before the pumper sleeps to cap CPU.
     * This is the real governor for idle busy-spin: the selector timeout alone
     * is defeated by wakeup() storms, so we enforce a minimum idle iteration
     * time (1e9 / this) instead of the old fixed 25ms-per-2048-loops failsafe.
     */
    private static volatile int _maxIdleLps = 1000;
    private static final int MIN_MAX_IDLE_LPS = 1;
    private static final int MAX_MAX_IDLE_LPS = 5000;
    private static volatile long _selectorLoopDelay = IS_SLOW ? 100 : 5;
    /** Max delay when idle — must stay low for client responsiveness */
    private static final long SELECTOR_MAX_DELAY = 20;
    private static volatile long _currentDelay = _selectorLoopDelay;
    private static final long BLOCKED_IP_FREQ = 43 * 60 * 1000L;
    /** Tunnel test now disabled, but this should be long enough to allow an active tunnel to get started. */
    private static final long MIN_EXPIRE_IDLE_TIME = 120 * 1000L;
    private static final long MAX_EXPIRE_IDLE_TIME = 11 * 60 * 1000L;
    private static final long MAY_DISCON_TIMEOUT = 10 * 1000L;
    private static final long RI_STORE_INTERVAL = 29 * 60 * 1000L;

    /**
     * Do we use direct buffers for reading? Default false.
     * NOT recommended as we don't keep good track of them so they will leak.
     *
     * Unsupported, set USE_DIRECT above.
     *
     * @see java.nio.ByteBuffer
     */
    private static final String PROP_NODELAY = "i2np.ntcp.nodelay";
    private static final int MIN_MINB = SystemVersion.isSlow() ? 4 : 8;
    private static final int MAX_MINB = SystemVersion.isSlow() ? 12 : Math.max(16, SystemVersion.getCores());
    private static final int MIN_BUFS;
    static {
        long maxMemory = SystemVersion.getMaxMemory();
        MIN_BUFS = (int) Math.max(MIN_MINB, Math.min(MAX_MINB, 1 + (maxMemory / (16 * 1024 * 1024L))));
    }

    private static final TryCache<ByteBuffer> _bufferCache = new TryCache<>(new BufferFactory(), MIN_BUFS);

    /**
     * Fixed size class for NTCP2 data-phase write frames.
     *
     * <p>Each frame carries up to {@link NTCPConnection#BUFFER_SIZE} payload bytes
     * plus the 16-byte MAC (see {@link OutboundNTCP2State#MAC_SIZE}) and a 2-byte
     * length header. The buffer must be a constant size for pooling: the previous
     * per-frame {@code new byte[2 + framelen]} allocation was variable-sized, so
     * every frame smaller than {@link #BUF_SIZE} escaped the cache and was
     * reallocated on each send.
     */
    private static final int WRITE_BUFSIZE = NTCPConnection.BUFFER_SIZE + OutboundNTCP2State.MAC_SIZE + 2;

    private static class WriteBufferFactory implements TryCache.ObjectFactory<byte[]> {
        /**
         * Create a new NTCP2 frame buffer.
         *
         * @return a buffer of {@link #WRITE_BUFSIZE} bytes
         */
        @Override
        public byte[] newInstance() {
            return new byte[WRITE_BUFSIZE];
        }
    }

    /** NTCP2 write-frame buffers, fixed {@link #WRITE_BUFSIZE} class. */
    private static final TryCache<byte[]> _writeBufCache = new TryCache<>(new WriteBufferFactory(), MIN_BUFS);

    /**
     * Acquire a fixed-size NTCP2 frame buffer for the next data-phase frame.
     * High-frequency path in the writer threads.
     *
     * <p>The caller writes the frame into the returned buffer and hands it to the
     * connection via {@code wantsWrite(data, 0, len)}. The pumper later returns it
     * to the pool via {@link #releaseWriteBuf(byte[])} once the frame has fully
     * drained to the socket; error paths in the producer must release it instead.
     *
     * @return a byte array of length {@link #WRITE_BUFSIZE}
     * @since 0.9.71+
     */
    public static byte[] acquireWriteBuf() {
        byte[] data = _writeBufCache.acquire();
        if (data == null)
            return new byte[WRITE_BUFSIZE];
        return data;
    }

    /**
     * Return an NTCP2 frame buffer to the pool.
     *
     * <p>Only buffers of the exact {@link #WRITE_BUFSIZE} class are accepted, so
     * handshake and termination buffers that happen to drain through the same write
     * path never enter this pool. The buffer is discarded, not pooled, when the
     * cache is full.
     *
     * @param data the buffer to return, or null (no-op)
     * @since 0.9.71+
     */
    public static void releaseWriteBuf(byte[] data) {
        if (data == null || data.length != WRITE_BUFSIZE)
            return;
        _writeBufCache.release(data);
    }

    private static final Set<Status> STATUS_OK = EnumSet.of(Status.OK, Status.IPV4_OK_IPV6_UNKNOWN, Status.IPV4_OK_IPV6_FIREWALLED);
    private static final long[] RATES = { 60*1000L, 10*60*1000L };

    /**
     * EventPumper.
     */
    public EventPumper(RouterContext ctx, NTCPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _transport = transport;
        _expireIdleWriteTime = MAX_EXPIRE_IDLE_TIME;
        _nodelay = ctx.getBooleanPropertyDefaultTrue(PROP_NODELAY);
        _blockedIPs = new ObjectCounter<>();
        _failedInboundEncryption = new ObjectCounter<>();
        _context.statManager().createRequiredRateStat("ntcp.pumperKeySetSize", "Number of NTCP Pumper KeySetSize events", "Transport [NTCP]", RATES);
        _context.statManager().createRequiredRateStat("ntcp.pumperLoopsPerSecond", "Number of NTCP Pumper loops/s", "Transport [NTCP]", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRequiredRateStat("ntcp.pumperIdleLoops", "Number of NTCP Pumper idle loops/s", "Transport [NTCP]", RATES);
        _context.statManager().createRequiredRateStat("ntcp.failsafeIterationTime", "NTCP failsafe iteration time in ms", "Transport [NTCP]", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRateStat("ntcp.zeroRead", "Number of NTCP zero length read events", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.zeroReadDrop", "Number of NTCP zero length read events dropped", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.dropInboundNoMessage", "Number of NTCP Inbound empty message drop events", "Transport [NTCP]", RATES);
        _context.statManager().createRequiredRateStat("ntcp.inboundConn", "Inbound NTCP Connection", "Transport [NTCP]", RATES);
        _context.statManager().createRequiredRateStat("ntcp.inboundEstablishFailed", "Inbound NTCP handshake failures", "Transport [NTCP]", RATES);
    }

    /**
     * Open the selector and start the pump thread.
     */
    public synchronized void startPumping() {
        if (_log.shouldInfo())
            _log.info("Starting NTCP Pumper...");
        try {
            _selector = Selector.open();
            _alive = true;
            I2PThread t = new I2PThread(this, "NTCPPumper", true);
            t.setPriority(Thread.MAX_PRIORITY);
            t.start();
        } catch (IOException ioe) {
            _log.log(Log.CRIT, "Error opening the NTCP selector", ioe);
        } catch (InternalError jlie) {
            // "unable to get address of epoll functions, pre-2.6 kernel?"
            _log.log(Log.CRIT, "Error opening the NTCP selector", jlie);
        }
    }

    /**
     * Stop the pump thread and wake the selector.
     */
    public synchronized void stopPumping() {
        _alive = false;
        if (_selector != null && _selector.isOpen())
            _selector.wakeup();
    }

    /**
     * Selector can take quite a while to close after calling stopPumping()
     * @return whether alive
     */
    public boolean isAlive() {
        return _alive || (_selector != null && _selector.isOpen());
    }

    /**
     * Register the acceptor.
     * This is only called from NTCPTransport.bindAddress(), so it isn't clear
     * why this needs a queue.
     */
    public void register(ServerSocketChannel chan) {
        if (_log.shouldDebug())
            _log.debug("Registering ServerSocketChannel...");
        _wantsRegister.offer(chan);
        _selector.wakeup();
    }

    /**
     * Outbound connection registration with optional retry backoff.
     */
    public void registerConnect(NTCPConnection con) {
        if (_log.shouldDebug()) {
            _log.debug("Registering " + con + "...");
        }
        RouterIdentity remote = con.getRemotePeer();
        if (remote != null) {
            Hash peerHash = remote.calculateHash();
            Long lastFailed = _failedOutboundAttempts.get(peerHash);
            Integer failCount = _failedOutboundCount.get(peerHash);
            if (lastFailed != null) {
                long now = System.currentTimeMillis();
                int totalFailures = failCount != null ? failCount : 1;
                // Hard cap: don't retry peers that have failed too many times
                if (totalFailures > MAX_OUTBOUND_RETRY_COUNT) {
                    if (_log.shouldWarn()) {
                        _log.warn("Giving up on retry to " + remote + " (" + totalFailures + " failures, max " +
                                  MAX_OUTBOUND_RETRY_COUNT + ")");
                    }
                    con.closeOnTimeout("Too many consecutive failures", null);
                    return;
                }
                long delay = (long) (MIN_RETRY_INTERVAL * Math.pow(RETRY_BACKOFF_FACTOR, Math.min(100, totalFailures - 1)));
                delay = Math.min(delay, MAX_RETRY_INTERVAL);
                if (now - lastFailed < delay) {
                    if (_log.shouldWarn()) {
                        _log.warn("Throttling retry to " + remote + " (last failed " + (now - lastFailed) + "ms ago, attempt " + totalFailures + ")");
                    }
                    con.closeOnTimeout("Connection retry throttled", null);
                    return;
                }
            }
        }
        _context.statManager().addRateData("ntcp.registerConnect", 1);
        _wantsConRegister.offer(con);
        _selector.wakeup();
    }

    /**
     * Run the selector event loop.
     */
    @Override
    public void run() {
        int loopCountSinceLastRate = 0;
        int idleLoopCountSinceLastRate = 0;
        long lastFailsafeIteration = System.currentTimeMillis();
        long lastLoopRateUpdate = System.currentTimeMillis();
        long lastKeySetUpdate = lastLoopRateUpdate;
        long lastBlockedIPClear = lastFailsafeIteration;
        final boolean shouldDebug = _log.shouldDebug();
        final boolean shouldWarn = _log.shouldWarn();

        while (_alive && _selector != null && _selector.isOpen()) {
            try {
                long iterStartNanos = System.nanoTime();
                loopCountSinceLastRate++;
                int selectedCount;
                try {
                    selectedCount = _selector.select(_currentDelay);
                } catch (ClosedSelectorException cse) {
                    continue;
                } catch (IOException | CancelledKeyException e) {
                    if (shouldDebug)
                        _log.warn("Error selecting", e);
                    else if (shouldWarn)
                        _log.warn("Error selecting -> " + e.getMessage());
                    continue;
                }

                if (selectedCount > 0) {
                    Set<SelectionKey> selected = _selector.selectedKeys();
                    processKeys(selected);
                    selected.clear();
                    _consecutiveFastSelects++;
                    if (_consecutiveFastSelects > MAX_CONSECUTIVE_FAST_SELECTS) {
                        Thread.yield();
                        _consecutiveFastSelects = _consecutiveFastSelects / 2;
                    }
                } else {
                    _consecutiveFastSelects = 0;
                    idleLoopCountSinceLastRate++;
                    if (throttleIdleLoop(iterStartNanos)) {
                        break;
                    }
                }

                runDelayedEvents();

                long now = _context.clock().now();

                // Update loop rate stat every 5 seconds
                if (now - lastLoopRateUpdate >= 5_000) {
                    updateLoopRateStats(loopCountSinceLastRate, idleLoopCountSinceLastRate, lastLoopRateUpdate, now);
                    loopCountSinceLastRate = 0;
                    idleLoopCountSinceLastRate = 0;
                    lastLoopRateUpdate = now;
                }

                // Update keyset size stat every 60 seconds
                if (now - lastKeySetUpdate >= 60_000) {
                    _context.statManager().addRateData("ntcp.pumperKeySetSize", _selector.keys().size());
                    lastKeySetUpdate = now;
                }

                // Clear old retry records
                if (now - _lastRetryMapClear >= RETRY_MAP_CLEAR_INTERVAL) {
                    long cutoff = now - MAX_RETRY_INTERVAL;
                    _failedOutboundAttempts.entrySet().removeIf(e -> e.getValue() < cutoff);
                    _failedOutboundCount.entrySet().removeIf(e -> !_failedOutboundAttempts.containsKey(e.getKey()));
                    _lastRetryMapClear = now;
                }

                // Periodic failsafe iteration (adaptive frequency)
                if (now - lastFailsafeIteration >= _failsafeIterationFreq) {
                    doFailsafeCheck();
                    lastFailsafeIteration = now;
                }

                // Clear blocked IP table periodically
                if (now - lastBlockedIPClear >= BLOCKED_IP_FREQ) {
                    synchronized (_blockedIPs) {
                        _blockedIPs.clear();
                    }
                    // Also clear failed-inbound-encryption counters so entries that
                    // never reach count 5 (and therefore never hit the individual clear
                    // in trackInvalidEncryption) don't accumulate permanently.
                    _failedInboundEncryption.clear();
                    lastBlockedIPClear = now;
                }

            } catch (RuntimeException re) {
                _log.error("Error in NTCP EventPumper", re);
            }
        }

        // Cleanup
        cleanupShutdown();
        _wantsConRegister.clear();
        _wantsRead.clear();
        _wantsRegister.clear();
        _wantsWrite.clear();
    }

    /**
     * Throttle a single idle loop iteration when select() spins faster than the
     * configured maximum (1e9 / _maxIdleLps executes per second). Selector wakeup()
     * storms can defeat the select() timeout and busy-spin the loop; this holds each
     * no-work iteration to a minimum duration so the real work path never sleeps.
     *
     * @param iterStartNanos System.nanoTime() taken at the start of the iteration
     * @return {@code true} if throttling was interrupted and the pumper must exit
     * @since 0.9.71+
     */
    private boolean throttleIdleLoop(long iterStartNanos) {
        int maxIdleLps = _maxIdleLps;
        if (maxIdleLps <= 0) {
            return false;
        }
        long minNanos = 1_000_000_000L / maxIdleLps;
        long elapsed = System.nanoTime() - iterStartNanos;
        if (elapsed >= minNanos) {
            return false;
        }
        long toSleepNanos = minNanos - elapsed;
        _context.statManager().addRateData("ntcp.failsafeThrottle", 1);
        try {
            Thread.sleep(toSleepNanos / 1_000_000L, (int) (toSleepNanos % 1_000_000L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return true;
        }
        return false;
    }

    /**
     * Record pumper loop statistics for each 5-second window: total loops per second
     * and the idle-loop count used by the Tuner to derive an idle ratio. Also scales
     * _currentDelay to curb busy-spinning: raise it quickly when the loop rate stays
     * far above the spin threshold, lower it slowly once the rate drops. The hard
     * idle cap lives in throttleIdleLoop(); this stays as a responsiveness lever.
     *
     * @param loopCountSinceLastRate  total iterations in the window
     * @param idleLoopCountSinceLastRate  no-work iterations in the window
     * @param lastLoopRateUpdate  timestamp of the previous window start
     * @param now  current time
     * @since 0.9.71+
     */
    private void updateLoopRateStats(int loopCountSinceLastRate, int idleLoopCountSinceLastRate,
                                     long lastLoopRateUpdate, long now) {
        long elapsedMs = now - lastLoopRateUpdate;
        int elapsedSeconds = (int) (elapsedMs / 1000);
        if (elapsedSeconds <= 0) elapsedSeconds = 1;
        int loopsPerSecond = loopCountSinceLastRate / elapsedSeconds;
        _context.statManager().addRateData("ntcp.pumperLoopsPerSecond", loopsPerSecond);
        _context.statManager().addRateData("ntcp.pumperIdleLoops", idleLoopCountSinceLastRate);
        if (loopsPerSecond > 1000 && _currentDelay < SELECTOR_MAX_DELAY) {
            long step = Math.min(50, (loopsPerSecond - 1000) / 2000 + 5);
            _currentDelay = Math.min(_currentDelay + step, SELECTOR_MAX_DELAY);
        } else if (loopsPerSecond < 500 && _currentDelay > _selectorLoopDelay) {
            _currentDelay = Math.max(_currentDelay - 5, _selectorLoopDelay);
        }
    }

    /**
     * Close every registered channel (NTCP connections and the server socket) and
     * the selector itself on pumper shutdown. Iterates a snapshot copy of the keys
     * because closing a connection cancels its key and mutates the selector's live
     * key set; iterating the set directly would race with that mutation.
     * @since 0.9.71+
     */
    private void cleanupShutdown() {
        try {
            if (_selector.isOpen()) {
                if (_log.shouldDebug())
                    _log.debug("Closing NTCP EventPumper with " + _selector.keys().size() + " keys");
                List<SelectionKey> keys = new ArrayList<>(_selector.keys());
                for (SelectionKey key : keys) {
                    try {
                        Object att = key.attachment();
                        if (att instanceof ServerSocketChannel)
                            ((ServerSocketChannel) att).close();
                        else if (att instanceof NTCPConnection)
                            ((NTCPConnection) att).close();
                        key.cancel();
                    } catch (IOException e) {
                        _log.error("Error closing key on shutdown", e);
                    }
                }
                _selector.close();
            }
        } catch (IOException e) {
            _log.error("Error closing selector", e);
        }
    }

    /**
     * Periodic failsafe scan of all connections to:
     * - Reassert OP_WRITE interest if data is pending
     * - Close idle/stale connections
     * - Send periodic RouterInfo
     */
    private void doFailsafeCheck() {
        long startTime = System.nanoTime();
        try {
            Set<SelectionKey> all = _selector.keys();
            int failsafeWrites = 0;
            int failsafeCloses = 0;
            int failsafeInvalid = 0;
            boolean haveCap = _transport.haveCapacity(33);
            adjustExpireIdleWriteTime(haveCap);

            long now = System.currentTimeMillis();
            for (SelectionKey key : all) {
                try {
                    Object att = key.attachment();
                    if (!(att instanceof NTCPConnection)) continue;
                    NTCPConnection con = (NTCPConnection) att;

                    if (!key.isValid() && con.getTimeSinceCreated(now) > 2 * NTCPTransport.ESTABLISH_TIMEOUT) {
                        con.closeOnTimeout("Key invalid and connection timeout (>30s)", null);
                        key.cancel();
                        failsafeInvalid++;
                        continue;
                    }

                    if (!con.isWriteBufEmpty() && (key.interestOps() & SelectionKey.OP_WRITE) == 0) {
                        setInterest(key, SelectionKey.OP_WRITE);
                        failsafeWrites++;
                    }

                    final long expire = getIdleExpire(con, haveCap, _expireIdleWriteTime);

                    if (closeIdleOrSendRouterInfo(con, now, expire, _failsafeIterationFreq)) {
                        failsafeCloses++;
                    }
                } catch (CancelledKeyException ignored) { /* ignored */ }
            }
            if (failsafeWrites > 0)
                _context.statManager().addRateData("ntcp.failsafeWrites", failsafeWrites);
            if (failsafeCloses > 0)
                _context.statManager().addRateData("ntcp.failsafeCloses", failsafeCloses);
            if (failsafeInvalid > 0)
                _context.statManager().addRateData("ntcp.failsafeInvalid", failsafeInvalid);
        } catch (ClosedSelectorException ignored) { /* ignored */ }
        long elapsed = (System.nanoTime() - startTime) / 1_000_000;
        _context.statManager().addRateData("ntcp.failsafeIterationTime", elapsed);
    }

    /**
     * Adjust the idle-expire window based on connection capacity so the router
     * keeps fewer idle connections when resources are tight. Raising the window
     * by 1s per check under capacity and lowering it by 3s under pressure lets the
     * window drift toward the useful range without hunting.
     *
     * @param haveCap  {@code true} when this router has spare connection capacity
     * @since 0.9.71+
     */
    private void adjustExpireIdleWriteTime(boolean haveCap) {
        if (haveCap)
            _expireIdleWriteTime = Math.min(_expireIdleWriteTime + 1000, MAX_EXPIRE_IDLE_TIME);
        else
            _expireIdleWriteTime = Math.max(_expireIdleWriteTime - 3000, MIN_EXPIRE_IDLE_TIME);
    }

    /**
     * Choose the idle timeout for a single connection. A barely-communicative
     * connection that is disposable (may disconnect, hardly any traffic) gets the
     * short MAY_DISCON_TIMEOUT so dead handshakes free capacity quickly; everything
     * else falls back to the capacity-adjusted window.
     *
     * @param con  the connection being scanned
     * @param haveCap  {@code true} when this router has spare connection capacity
     * @param expireIdleWriteTime  current capacity-adjusted idle window
     * @return the timeout in milliseconds for this connection
     * @since 0.9.71+
     */
    static long getIdleExpire(NTCPConnection con, boolean haveCap, long expireIdleWriteTime) {
        if ((!haveCap || !con.isInbound()) &&
            con.getMayDisconnect() &&
            con.getMessagesReceived() <= 2 &&
            con.getMessagesSent() <= 1) {
            return MAY_DISCON_TIMEOUT;
        }
        return expireIdleWriteTime;
    }

    /**
     * Close a connection that has exceeded its idle timeout, or send our RouterInfo
     * when the connection has been established long enough that periodic re-announce
     * is due (uptime in the RI_STORE_INTERVAL band).
     *
     * @param con  the connection being scanned
     * @param now  current time in milliseconds
     * @param expire  idle timeout for this connection
     * @param failsafeIterationFreq  pumper slab interval; keeps the RI re-announce
     *                               roughly once per interval per connection
     * @return {@code true} if the connection was idle and has been closed
     * @since 0.9.71+
     */
    static boolean closeIdleOrSendRouterInfo(NTCPConnection con, long now, long expire, long failsafeIterationFreq) {
        if (con.getLastActiveTime() + expire < now) {
            con.sendTerminationAndClose();
            return true;
        }
        long estab = con.getEstablishedOn();
        if (estab > 0) {
            long uptime = now - estab;
            if (uptime >= RI_STORE_INTERVAL && (uptime % RI_STORE_INTERVAL) < failsafeIterationFreq) {
                con.sendOurRouterInfo(false);
            }
        }
        return false;
    }

    private void processKeys(Set<SelectionKey> selected) {
        for (SelectionKey key : selected) {
            try {
                int ops = key.readyOps();
                if ((ops & SelectionKey.OP_ACCEPT) != 0) {
                    _context.statManager().addRateData("ntcp.accept", 1);
                    processAccept(key);
                }
                if ((ops & SelectionKey.OP_CONNECT) != 0) {
                    clearInterest(key, SelectionKey.OP_CONNECT);
                    processConnect(key);
                }
                if ((ops & SelectionKey.OP_READ) != 0)
                    processRead(key);
                if ((ops & SelectionKey.OP_WRITE) != 0)
                    processWrite(key);
            } catch (CancelledKeyException ignored) {
                if (_log.shouldDebug())
                    _log.debug("Key cancelled");
            }
        }
    }

    /**
     * Called by the connection when it has data ready to write (after bw allocation).
     * Only wakeup if new.
     */
    public void wantsWrite(NTCPConnection con) {
        if (con.isClosed()) return;
        if (_wantsWrite.add(con)) {
            _selector.wakeup();
        }
    }

    /**
     * This is only called from NTCPConnection.complete()
     * if there is more data, which is rare (never?)
     * so we don't need to check for dups or make _wantsRead a Set.
     */
    public void wantsRead(NTCPConnection con) {
        if (con.isClosed()) return;
        _wantsRead.offer(con);
        _selector.wakeup();
    }

    /**
     * High-frequency path in thread.
     */
    public static ByteBuffer acquireBuf() {
        ByteBuffer buf = _bufferCache.acquire();
        if (buf == null)
            return USE_DIRECT ? ByteBuffer.allocateDirect(BUF_SIZE) : ByteBuffer.allocate(BUF_SIZE);
        buf.clear();
        return buf;
    }

    /**
     * Read buffer returned to the pool.
     * These buffers must be from acquireBuf(), i.e. capacity() == BUF_SIZE.
     * High-frequency path in thread.
     */
    public static void releaseBuf(ByteBuffer buf) {
        if (buf == null) return;
        if (buf.capacity() < BUF_SIZE) {
            I2PAppContext.getGlobalContext().logManager().getLog(EventPumper.class).error("Bad size " + buf.capacity(), new Exception());
            return;
        }
        buf.clear();
        _bufferCache.release(buf);
    }

    private void processAccept(SelectionKey key) {
        Object att = key.attachment();
        if (!(att instanceof ServerSocketChannel)) {
            if (_log.shouldWarn()) {
                _log.warn("Invalid attachment in processAccept: " + (att != null ? att.getClass().getSimpleName() : "null"));
            }
            key.cancel();
            return;
        }
        ServerSocketChannel servChan = (ServerSocketChannel) att;
        boolean shouldWarn = _log.shouldWarn();
        boolean shouldInfo = _log.shouldInfo();
        try {
            SocketChannel chan = servChan.accept();
            if (chan == null) return;
            chan.configureBlocking(false);
            byte[] ip = chan.socket().getInetAddress().getAddress();
            String ba = Addresses.toString(ip).replace("/", "");
            AcceptVerdict verdict = screenAccept(ip, ba);
            if (verdict != AcceptVerdict.ACCEPT) {
                refuseAccepted(chan, verdict, ba, shouldWarn, shouldInfo);
                return;
            }
            _context.statManager().addRateData("ntcp.inboundConn", 1);
            if (shouldSetKeepAlive(chan)) chan.socket().setKeepAlive(true);
            if (_nodelay) chan.socket().setTcpNoDelay(true);
            SelectionKey ckey = chan.register(_selector, SelectionKey.OP_READ);
            NTCPConnection con = new NTCPConnection(_context, _transport, chan, ckey);
            ckey.attach(con);
            _transport.establishing(con);
        } catch (IOException ioe) {
            if (ioe.toString().contains("reset by peer")) {
                _log.warn("Error accepting NTCP connection: " + ioe.getMessage());
            } else {
                _log.error("Error accepting NTCP connection", ioe);
            }
        }
    }

    /**
     * Outcome of screening an inbound Session Request against the bans,
     * connection limits, and flood defenses before the connection is accepted.
     * @since 0.9.71+
     */
    enum AcceptVerdict {
        /** Accept the connection and register it for the handshake. */
        ACCEPT,
        /** Peer IP is visibly blocklisted. */
        BANNED,
        /** Global NTCP connection limit reached. */
        LIMIT,
        /** Peer already has connections and is exceeding the per-IP cap. */
        BLOCKED,
        /** Inbound flood defense dropped the connection. */
        FLOOD
    }

    /**
     * Screen an inbound connection attempt and decide whether to register it.
     * Blocklisted peers and refused attempts skip the flood/establishment
     * decision entirely; the cheap hard rejections (bans, global limit) are done
     * first so the per-IP count and the rate-based flood check run only for
     * otherwise-eligible peers.
     *
     * @param ip  raw address bytes of the peer
     * @param ba  canonical string form of the peer address
     * @return the {@link AcceptVerdict} for this attempt, never null
     * @since 0.9.71+
     */
    private AcceptVerdict screenAccept(byte[] ip, String ba) {
        if (_context.blocklist().isBlocklisted(ip)) {
            return AcceptVerdict.BANNED;
        }
        if (_context.commSystem().isExemptIncoming(Addresses.toCanonicalString(ba))) {
            return AcceptVerdict.ACCEPT;
        }
        if (!_transport.allowConnection()) {
            return AcceptVerdict.LIMIT;
        }
        if (_blockedIPs.count(ba) > 0) {
            int count = _blockedIPs.increment(ba);
            if (_log.shouldInfo()) {
                _log.info("Blocking NTCP connection attempt from: " + ba + " (Count: " + count + ")");
            }
            if (count >= 30 && _log.shouldWarn()) {
                _log.warn("WARNING! IP Address " + ba +
                          " is making excessive inbound NTCP connection attempts (Count: " + count + ")");
            }
            return AcceptVerdict.BLOCKED;
        }
        return shouldAllowInboundEstablishment() ? AcceptVerdict.ACCEPT : AcceptVerdict.FLOOD;
    }

    /**
     * Log and close a refused inbound connection. The BLOCKED and FLOOD verdicts
     * are already logged by their detectors; only BANNED and LIMIT emit a message
     * here so repeated refusals from the same source stay informative but quiet.
     *
     * @param chan  the rejected socket, closed by this call
     * @param verdict  why the connection was refused (never ACCEPT)
     * @param ba  canonical string form of the peer address
     * @param shouldWarn  pre-fetched log level check for warn
     * @param shouldInfo  pre-fetched log level check for info
     * @since 0.9.71+
     */
    private void refuseAccepted(SocketChannel chan, AcceptVerdict verdict, String ba,
                                boolean shouldWarn, boolean shouldInfo) {
        switch (verdict) {
            case BANNED:
                if (shouldInfo) {
                    _log.info("Refusing Session Request from blocklisted IP address " + ba);
                }
                break;
            case LIMIT:
                if (shouldWarn) {
                    _log.warn("Refusing Session Request from: " + ba + " -> NTCP connection limit reached");
                }
                break;
            default:
                break;
        }
        try {
            chan.close();
        } catch (IOException ioe) { /* ignored */ }
    }

    private boolean shouldAllowInboundEstablishment() {
        refreshInboundRateIfStale();
        InboundFloodDecision verdict = evaluateInboundFlood(_context.clock().now(),
                                                            _inboundCurrentCount, _inboundLastCount, _inboundPeriodStart,
                                                            _transport.haveCapacity(95),
                                                            _transport.getMaxConnections(), _transport.countPeers(),
                                                            _context.random());
        if (verdict.isDrop() && _log.shouldWarn()) {
            _log.warn("Dropping incoming TCP connection (" + (verdict.getPercent() >= 1 ? Math.min(verdict.getPercent(), 100) + "%" : "1%") + " chance)" +
                      " -> Previous/current connections per minute: " + verdict.getLastConnections() + " / " + verdict.getCurrentConnectionsPerMinute());
        }
        return !verdict.isDrop();
    }

    /**
     * Refresh the cached snapshot of the one-minute inbound accept rate.
     *
     * The expensive part — aggregating the {@link Rate} buckets — requires the
     * global Rate monitor and only happens once per refresh interval; every
     * accepted connection in between reads the four volatile fields lock-free.
     * A connection flood therefore never contends on the stat lock.
     */
    private void refreshInboundRateIfStale() {
        long now = _context.clock().now();
        // Snapshot still fresh, serve the cached values without touching the Rate.
        if (now - _inboundSnapshotAt < INBOUND_RATE_REFRESH_MS) return;
        RateStat rs = _context.statManager().getRate("ntcp.inboundConn");
        Rate r = (rs != null) ? rs.getRate(RateConstants.ONE_MINUTE) : null;
        // Rate not registered yet (router startup): nothing to cache, allow everything.
        if (r == null) return;
        synchronized (r) {
            _inboundCurrentCount = (int) r.getCurrentEventCount();
            _inboundLastCount = (int) r.getLastEventCount();
            _inboundPeriodStart = r.getLastCoalesceDate();
        }
        _inboundSnapshotAt = now;
    }

    /** Minimum accepted rate in the previous period; never below this floor. */
    private static final int MIN_INBOUND_LAST_EVENTS = 15;
    /** Length of the previous period used to normalize the baseline rate. */
    private static final long INBOUND_LAST_PERIOD_MS = 60 * 1000L;
    /** Right after a period rolls over, the accept rate is meaningless; always allow. */
    private static final long INBOUND_WARMUP_MS = 5 * 1000L;
    /** Random range for the probabilistic reject decision. */
    private static final int INBOUND_DROP_RANGE = 128;
    /** Ratchet constant: max accept when the current rate exceeds baseline by this bias. */
    private static final int INBOUND_DROP_BIAS = 512;
    /** Flood threshold is relaxed when the transport still has headroom... */
    private static final float INBOUND_SPARE_CAPACITY_FACTOR = 1.05f;
    /** ...and tightened when it reports saturation. */
    private static final float INBOUND_SATURATED_FACTOR = 0.95f;

    /**
     * Decide whether an inbound NTCP connection should be probabilistically
     * rejected because the accept rate is spiking well above the recent baseline
     * while the router is already heavily loaded with connections.
     *
     * This is the pure decision half of {@link #shouldAllowInboundEstablishment()}.
     * It performs no locking and takes raw counters instead of a {@link Rate}, so
     * the hot accept path never blocks on the global Rate monitor. The arithmetic
     * is pinned by {@code InboundFloodDecisionTest} in the router test tree.
     *
     * @param now router clock time in milliseconds
     * @param rawCurrentCount events so far in the current (partial) rate period
     * @param rawLastCount events in the most recent full rate period
     * @param periodStart start time of the current rate period, milliseconds
     * @param hasCapacity true if the transport reports spare bandwidth, which
     *                    relaxes the flood threshold vs. punishing when saturated
     * @param maxConnections the transport connection ceiling
     * @param currentConnections the live peer connection count
     * @param random source of randomness for the probabilistic decision
     * @return the decision; never null
     * @since 0.9.71+
     */
    static InboundFloodDecision evaluateInboundFlood(long now, int rawCurrentCount, int rawLastCount,
                                                     long periodStart, boolean hasCapacity,
                                                     int maxConnections, int currentConnections,
                                                     RandomSource random) {
        int last = Math.max(rawLastCount, MIN_INBOUND_LAST_EVENTS);
        // The Rate totals current + last period events; the original logic measured
        // "current" activity by subtracting the (floored) last period from that total.
        int current = rawCurrentCount + rawLastCount - last;
        if (current <= 0) return InboundFloodDecision.ALLOW;
        int currentTime = (int) (now - periodStart);
        if (currentTime <= INBOUND_WARMUP_MS) return InboundFloodDecision.ALLOW;
        float lastRate = last / (float) INBOUND_LAST_PERIOD_MS;
        float currentRate = (float) (current / (double) currentTime);
        float factor = hasCapacity ? INBOUND_SPARE_CAPACITY_FACTOR : INBOUND_SATURATED_FACTOR;
        float minThresh = factor * lastRate;
        // Only engage when the current rate clearly exceeds the baseline AND the
        // router is past two-thirds of its connection ceiling.
        if (currentRate > minThresh * 5 / 3 && currentConnections > (maxConnections * 2 / 3)) {
            long probAccept = Math.max(1, ((int) (4 * INBOUND_DROP_RANGE * currentRate / minThresh)) - INBOUND_DROP_BIAS);
            int percent = probAccept > INBOUND_DROP_RANGE ? 100 : (int) ((probAccept / INBOUND_DROP_RANGE) * 100);
            if (probAccept >= INBOUND_DROP_RANGE || random.nextInt(INBOUND_DROP_RANGE) < probAccept) {
                return new InboundFloodDecision(true, percent, last,
                                                (int) (currentRate * 60 * 1000));
            }
        }
        return InboundFloodDecision.ALLOW;
    }

    /**
     * Outcome of {@link EventPumper#evaluateInboundFlood(long, int, int, long, boolean, int, int, RandomSource)}:
     * whether to reject the incoming connection plus the numbers used in the
     * warn log, so the caller stays side-effect free.
     *
     * @since 0.9.71+
     */
    static final class InboundFloodDecision {
        /** Constant for "accept, nothing to report", reused on every non-drop path. */
        static final InboundFloodDecision ALLOW = new InboundFloodDecision(false, 0, 0, 0);

        private final boolean _drop;
        private final int _percent;
        private final int _lastConnections;
        private final int _currentConnectionsPerMinute;

        /**
         * @param drop true to reject the connection
         * @param percent nominal rejection chance, 0-100 (0 renders as "1%")
         * @param lastConnections baseline connections in the previous period
         * @param currentConnectionsPerMinute projected current accept rate
         */
        private InboundFloodDecision(boolean drop, int percent, int lastConnections,
                                     int currentConnectionsPerMinute) {
            _drop = drop;
            _percent = percent;
            _lastConnections = lastConnections;
            _currentConnectionsPerMinute = currentConnectionsPerMinute;
        }

        /** @return true if the connection should be rejected */
        boolean isDrop() { return _drop; }

        /** @return nominal rejection chance percent (0 renders as "1%") */
        int getPercent() { return _percent; }

        /** @return baseline connections in the previous full rate period */
        int getLastConnections() { return _lastConnections; }

        /** @return projected current accept rate in connections per minute */
        int getCurrentConnectionsPerMinute() { return _currentConnectionsPerMinute; }
    }

    private void processConnect(SelectionKey key) {
        Object att = key.attachment();
        if (!(att instanceof NTCPConnection)) {
            if (_log.shouldWarn()) {
                _log.warn("Invalid attachment in processConnect: " + (att != null ? att.getClass().getSimpleName() : "null"));
            }
            key.cancel();
            return;
        }
        final NTCPConnection con = (NTCPConnection) att;
        final SocketChannel chan = con.getChannel();
        if (chan == null) {
            con.closeOnTimeout("Channel is null", null);
            key.cancel();
            return;
        }
        try {
            boolean connected = chan.finishConnect();
            if (_log.shouldDebug())
                _log.debug("Processing connect for " + con + " (" + (connected ? "Connected" :  "Disconnected") + ")");
            if (connected) {
                if (shouldSetKeepAlive(chan))
                    chan.socket().setKeepAlive(true);
                if (_nodelay)
                    chan.socket().setTcpNoDelay(true);
                con.setKey(key);
                con.outboundConnected();
                _context.statManager().addRateData("ntcp.connectSuccessful", 1);
            } else {
                con.closeOnTimeout("Connect failed (15s timeout exceeded) -> Marking unreachable", null);
                _transport.markUnreachable(con.getRemotePeer().calculateHash());
                _context.statManager().addRateData("ntcp.connectFailedTimeout", 1);
            }
        } catch (IOException ioe) {
            handleConnectError(con, ioe);
        } catch (NoConnectionPendingException ncpe) {
            if (_log.shouldWarn()) _log.warn("Error connecting on " + con, ncpe);
        }
    }

    /**
     * Handle an outbound connect that failed with an I/O error: log at debug (or a
     * quiet warn) level, close the connection, record the peer for retry backoff,
     * and mark it unreachable so the transport stops attempting it until it hears
     * proof of life.
     *
     * @param con  the connection whose connect failed
     * @param ioe  the failure cause
     * @since 0.9.71+
     */
    private void handleConnectError(NTCPConnection con, IOException ioe) {
        if (_log.shouldDebug()) {
            _log.debug("[NTCP] Failed outbound connection to " + con.getRemotePeer(), ioe);
        } else if (_log.shouldWarn()) {
            _log.warn("[NTCP] Failed outbound connection to " + con.getRemotePeer());
        }
        con.closeOnTimeout("\n* Connect failed: " + ioe.getMessage(), ioe);
        RouterIdentity remote = con.getRemotePeer();
        if (remote != null) {
            if (!con.isInbound()) {
                recordFailedOutbound(remote);
            }
            _transport.markUnreachable(remote.calculateHash());
        }
        _context.statManager().addRateData("ntcp.connectFailedTimeoutIOE", 1);
    }

    /**
     * Remember a failed outbound connect attempt for retry backoff, evicting the
     * whole history when the cap is hit so the maps never grow past MAX_RETRY_MAP_SIZE.
     *
     * @param remote  the failed peer's identity; ignored when null
     * @since 0.9.71+
     */
    private void recordFailedOutbound(RouterIdentity remote) {
        if (remote == null) return;
        Hash peerHash = remote.calculateHash();
        if (_failedOutboundAttempts.size() >= MAX_RETRY_MAP_SIZE) {
            _failedOutboundAttempts.clear();
            _failedOutboundCount.clear();
        }
        _failedOutboundAttempts.put(peerHash, System.currentTimeMillis());
        _failedOutboundCount.merge(peerHash, 1, Integer::sum);
    }

    private boolean shouldSetKeepAlive(SocketChannel chan) {
        if (chan.socket().getInetAddress() instanceof Inet6Address) return false;
        Status status = _context.commSystem().getStatus();
        return !STATUS_OK.contains(status);
    }

    private void processRead(SelectionKey key) {
        Object att = key.attachment();
        if (!(att instanceof NTCPConnection)) {
            if (_log.shouldWarn()) {
                _log.warn("Invalid attachment in processRead: " + (att != null ? att.getClass().getSimpleName() : "null"));
            }
            key.cancel();
            return;
        }
        final NTCPConnection con = (NTCPConnection) att;
        final SocketChannel chan = con.getChannel();
        if (chan == null) {
            con.close();
            key.cancel();
            return;
        }
        ByteBuffer buf = null;
        boolean shouldDebug = _log.shouldDebug();
        boolean shouldInfo = _log.shouldInfo();
        try {
            while (true) {
                buf = acquireBuf();
                int totalRead = 0;
                int bytesRead;
                while ((bytesRead = chan.read(buf)) > 0) {
                    totalRead += bytesRead;
                }
                if (bytesRead < 0 && totalRead == 0) totalRead = bytesRead;
                if (shouldDebug && totalRead != 0) {
                    _log.debug("Read " + totalRead + " bytes " + con);
                }
                if (totalRead < 0) {
                    handleReadEof(con, chan, buf);
                    break;
                }
                if (totalRead == 0) {
                    releaseBuf(buf);
                    handleReadZero(con, shouldDebug, shouldInfo);
                    break;
                }
                con.clearZeroRead();
                buf.flip();
                if (!handleReadData(con, key, buf, totalRead, bytesRead < 0)) {
                    break;
                }
            }
        } catch (CancelledKeyException cke) {
            if (buf != null) releaseBuf(buf);
            if (shouldInfo) _log.info("Error reading on " + con + " -> " + cke.getMessage());
            con.close();
            _context.statManager().addRateData("ntcp.readError", 1);
        } catch (IOException ioe) {
            if (buf != null) releaseBuf(buf);
            handleReadError(con, ioe, shouldInfo);
        } catch (NotYetConnectedException nyce) {
            if (buf != null) releaseBuf(buf);
            clearInterest(key, SelectionKey.OP_READ);
            if (shouldInfo) _log.info("Error reading: " + con, nyce);
        } catch (BufferOverflowException boe) {
            // Rare unchecked exception on read() call, unknown cause
            // Not even listed on SocketChannel.read() javadoc
            // Do not release the buf, maybe it was in the pool twice?
            // We assume this is fatal for the con so we close it.
            clearInterest(key, SelectionKey.OP_READ);
            con.close();
            _context.statManager().addRateData("ntcp.readError", 1);
            if (_log.shouldWarn())
                _log.warn("Error reading on " + con, boe);
        }
    }

    /**
     * Hand a filled read buffer to the connection, honoring the bandwidth limiter.
     * When the limiter queues the read, the buffer's ownership transfers to the
     * connection and read interest is cleared so no more data is drained while the
     * queue is backed up.
     *
     * @param con  the connection the data arrived on
     * @param key  the connection's selection key (to clear OP_READ when throttled)
     * @param buf  flipped buffer containing the received bytes
     * @param totalRead  bytes received in this iteration
     * @param eof  {@code true} if the socket signaled EOF after these bytes
     * @return {@code false} to stop the read loop (throttled, queued, or EOF)
     * @since 0.9.71+
     */
    private boolean handleReadData(NTCPConnection con, SelectionKey key, ByteBuffer buf, int totalRead, boolean eof) {
        FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestInbound(totalRead, "NTCP read");
        if (req.getPendingRequested() > 0) {
            clearInterest(key, SelectionKey.OP_READ);
            con.queuedRecv(buf, req);
            return false;
        }
        con.recv(buf);
        if (eof) {
            con.close();
            return false;
        }
        return !buf.hasRemaining();
    }

    /**
     * Handle end-of-stream on a read. An inbound connection that received nothing
     * before EOF is treated as a connection-flood probe (or a dead handshake);
     * its IP is counted and escalated to the banlist unless already blocklisted.
     *
     * @param con  the connection that hit EOF
     * @param chan  the connection's socket channel (for the peer address)
     * @param buf  the read buffer, released by this call
     * @since 0.9.71+
     */
    private void handleReadEof(NTCPConnection con, SocketChannel chan, ByteBuffer buf) {
        if (con.isInbound() && con.getMessagesReceived() <= 0) {
            InetAddress addr = chan.socket().getInetAddress();
            if (addr != null) {
                String ipStr = Addresses.toString(addr.getAddress()).replace("/", "");
                if (!_context.blocklist().isBlocklisted(ipStr)) {
                    _context.banlist().corruptConnection(ipStr, null);
                }
                countInboundNoMessage(ipStr, con, "EOF");
            } else {
                countInboundNoMessage(null, con, "EOF");
            }
        } else if (_log.shouldDebug()) {
            _log.debug("EOF on " + con);
        }
        con.closeOnTimeout("\n* EOF on " + (con.isInbound() ? "Inbound" : "Outbound") + " connection -> No data received", null);
        releaseBuf(buf);
    }

    /**
     * Handle a select() wakeup with no readable bytes. A burst of these within a
     * one-second window is treated as a connection defect and closed; otherwise the
     * connection stays read-interested and the zero-read count is recorded so the
     * Tuner can judge how much of the loop is spurious wakeups.
     *
     * @param con  the connection that returned zero bytes
     * @param shouldDebug  pre-fetched debug level
     * @param shouldInfo  pre-fetched info level
     * @since 0.9.71+
     */
    private void handleReadZero(NTCPConnection con, boolean shouldDebug, boolean shouldInfo) {
        int zeroReadCount = con.gotZeroRead();
        long now = System.currentTimeMillis();
        // Close connection if multiple zero reads within a short window
        if (zeroReadCount >= 3 && now - con.getLastZeroReadTime() <= 1000) {
            _context.statManager().addRateData("ntcp.zeroReadDrop", 1);
            if (shouldInfo) _log.info("Fail safe zero read close " + con);
            con.close();
        } else {
            _context.statManager().addRateData("ntcp.zeroRead", zeroReadCount);
            if (shouldDebug) {
                _log.debug("Nothing to read for " + con + " -> Remaining interested (Count: " + zeroReadCount + ")");
            }
        }
    }

    /**
     * Record an inbound connection that closed before delivering any message,
     * feeding the per-IP attempt counter and the drop statistic. Shared by the EOF
     * and I/O-error paths so both connection-flood sources funnel into one counter.
     *
     * @param ipStr  the peer address string, or null when it cannot be resolved
     * @param con  the connection that dropped
     * @param reason  short phrase logged with the event, e.g. "EOF" or "IO Error: ..."
     * @since 0.9.71+
     */
    private void countInboundNoMessage(String ipStr, NTCPConnection con, String reason) {
        int count;
        if (ipStr != null) {
            count = _blockedIPs.increment(ipStr);
            if (_log.shouldInfo()) {
                _log.info(reason + " on Inbound connection before receiving any data, blocking IP: "
                          + ipStr + (count > 1 ? " (Count: " + count + ")" : ""));
            }
        } else {
            count = 1;
            if (_log.shouldInfo()) {
                _log.info(reason + " on Inbound connection before receiving any data: " + con);
            }
        }
        _context.statManager().addRateData("ntcp.dropInboundNoMessage", count);
    }

    /**
     * Handle an I/O error on a read. An inbound connection that errored before its
     * first message escalates its IP through countInboundNoMessage(); the connection
     * is recorded as a read error when established or as a failed connect otherwise
     * (marking the peer unreachable if it was an outbound attempt).
     *
     * @param con  the connection that failed
     * @param ioe  the failure cause
     * @param shouldInfo  pre-fetched info level
     * @since 0.9.71+
     */
    private void handleReadError(NTCPConnection con, IOException ioe, boolean shouldInfo) {
        if (con.isInbound() && con.getMessagesReceived() <= 0) {
            byte[] ip = con.getRemoteIP();
            countInboundNoMessage(ip != null ? Addresses.toString(ip).replace("/", "") : null,
                                  con, "IO Error: " + ioe.getMessage());
        } else if (shouldInfo) {
            _log.info("Error reading: " + con + " (" + ioe.getMessage() + ")");
        }
        if (con.isEstablished()) {
            _context.statManager().addRateData("ntcp.readError", 1);
        } else {
            _context.statManager().addRateData("ntcp.connectFailedTimeoutIOE", 1);
            RouterIdentity rem = con.getRemotePeer();
            if (rem != null && !con.isInbound()) {
                _transport.markUnreachable(rem.calculateHash());
            }
        }
        con.close();
    }

    private void processWrite(SelectionKey key) {
        Object att = key.attachment();
        if (!(att instanceof NTCPConnection)) {
            if (_log.shouldWarn()) {
                _log.warn("Invalid attachment in processWrite: " + (att != null ? att.getClass().getSimpleName() : "null"));
            }
            key.cancel();
            return;
        }
        final NTCPConnection con = (NTCPConnection) att;
        processWrite(con, key);
    }

    /**
     * Try to write the queued buffers for the connection.
     *
     * Single-writer contract: this must only be called on the pumper thread
     * (via processWrite(SelectionKey) for OP_WRITE events). Producer threads
     * call wantsWrite() to register interest; they never write to the socket
     * themselves. This keeps SocketChannel.write() strictly single-threaded per
     * connection and removes the _writeLock serialization that previously
     * spanned the whole drain loop (which could stall frame pushes from the
     * writer pool and other connections).
     *
     * @since 0.9.71+ single-writer drain without _writeLock; previously the
     *                 drain ran under the connection's write lock and was also
     *                 invoked directly from producer threads
     */
    private boolean processWrite(final NTCPConnection con, final SelectionKey key) {
        boolean rv = false;
        final SocketChannel chan = con.getChannel();
        if (chan == null) {
            con.close();
            key.cancel();
            return true;
        }
        if (!key.isValid()) {
            con.close();
            key.cancel();
            return true;
        }
        try {
            WriteState state;
            do {
                state = writeOneBuffer(con, chan);
            } while (state == WriteState.DRAIN);
            if (state == WriteState.DONE) {
                rv = true;
            } else if (state == WriteState.EMPTY && key.isValid()) {
                rv = true;
            }
            if (rv) {
                clearInterest(key, SelectionKey.OP_WRITE);
            } else {
                setInterest(key, SelectionKey.OP_WRITE);
            }
        } catch (CancelledKeyException cke) {
            if (_log.shouldInfo()) _log.info("Error writing on: " + con + " -> Socket channel closed or key cancelled");
            _context.statManager().addRateData("ntcp.writeError", 1);
            con.close();
            rv = true;
        } catch (IOException ioe) {
            if (_log.shouldInfo()) _log.info("Error writing on: " + con + " -> IO Error");
            _context.statManager().addRateData("ntcp.writeError", 1);
            con.close();
            rv = true;
        }
        return rv;
    }

    /**
     * Progress of a single write-buffer drain step.
     * @since 0.9.71+
     */
    enum WriteState {
        /** The head buffer was fully flushed; pull the next one. */
        DRAIN,
        /** No more queued buffers; the queue is exhausted. */
        EMPTY,
        /** Socket would block or a partial write remains; stay write-interested. */
        BLOCKED,
        /**
         * Everything is flushed. Retained for structural parity with the original
         * drain loop; the zero-write path reports BLOCKED in practice because a write
         * of zero bytes cannot consume the head buffer, and empty buffers are drained
         * before any write, so this state is not reachable.
         * @since 0.9.71+
         */
        DONE
    }

    /**
     * Write one queued buffer to the socket. Empty head buffers are discarded and
     * the drain continues; a zero-byte write means the socket buffer is full and the
     * caller must keep OP_WRITE interest, unless the queue is empty and fully drained,
     * in which case interest can be dropped.
     *
     * <p>Buffers are returned to the pool once their frame is fully flushed. Only
     * the fixed-size NTCP2 frame class re-enters the pool; smaller handshake and
     * termination buffers are left alone. The release happens on the pumper thread
     * immediately after {@link NTCPConnection#removeWriteBuf} so a frame array can
     * never be reused while any producer still references it.
     *
     * @param con  the connection being drained
     * @param chan  the connection's socket channel
     * @return the {@link WriteState} governing the next step in the drain loop
     * @throws IOException if the channel write fails
     * @since 0.9.71+
     */
    static WriteState writeOneBuffer(NTCPConnection con, SocketChannel chan) throws IOException {
        ByteBuffer buf = con.getNextWriteBuf();
        if (buf == null) {
            return WriteState.EMPTY;
        }
        if (buf.remaining() <= 0) {
            con.removeWriteBuf(buf);
            releaseDrainedWriteBuf(buf);
            return WriteState.DRAIN;
        }
        int written = chan.write(buf);
        if (written == 0) {
            if (buf.remaining() > 0 || !con.isWriteBufEmpty()) {
                return WriteState.BLOCKED;
            }
            return WriteState.DONE;
        }
        if (buf.remaining() > 0) {
            return WriteState.BLOCKED;
        }
        con.removeWriteBuf(buf);
        releaseDrainedWriteBuf(buf);
        return WriteState.DRAIN;
    }

    /**
     * Return a fully drained NTCP2 frame buffer to the pool.
     *
     * <p>Invoked from {@link #writeOneBuffer} on the pumper thread right after the
     * buffer leaves the connection's write queue. Only arrays of the pooled
     * {@link #WRITE_BUFSIZE} class are returned; everything else (handshake,
     * termination, and legacy buffers) is ignored.
     *
     * @param buf the drained buffer, wrapped around a frame array
     * @since 0.9.71+
     */
    static void releaseDrainedWriteBuf(ByteBuffer buf) {
        if (buf != null && buf.hasArray()) {
            releaseWriteBuf(buf.array());
        }
    }

    private static final int MAX_BATCH = SystemVersion.isSlow() ? 1024 : 16384;

    private void runDelayedEvents() {
        boolean debug = _log.shouldDebug();
        boolean warn = _log.shouldWarn();
        processReadRequests(debug, warn);
        processWriteRequests(debug, warn);
        processServerSocketRegistrations(debug, warn);
        processOutboundConnectionRegistrations(debug, warn);
        long now = System.currentTimeMillis();
        if (_lastExpired + 1000 <= now) {
            expireTimedOut();
            _lastExpired = now;
        }
    }

    private void processReadRequests(boolean debug, boolean warn) {
        NTCPConnection con;
        int count = 0;
        while (count++ < MAX_BATCH && (con = _wantsRead.poll()) != null) {
            if (con.isClosed()) continue;
            SelectionKey key = con.getKey();
            if (key == null || !key.isValid()) {
                con.close();
                continue;
            }
            try {
                setInterest(key, SelectionKey.OP_READ);
            } catch (CancelledKeyException cke) {
                if (debug) _log.debug("Cancelled key during read registration for " + con, cke);
                con.close();
            } catch (IllegalArgumentException iae) {
                if (warn) _log.warn("Invalid key for read registration for " + con, iae);
                con.close();
            }
        }
    }

    private void processWriteRequests(boolean debug, boolean warn) {
        if (_wantsWrite.isEmpty()) return;
        for (Iterator<NTCPConnection> iter = _wantsWrite.iterator(); iter.hasNext(); ) {
            NTCPConnection con = iter.next();
            iter.remove();
            if (con.isClosed()) continue;
            SelectionKey key = con.getKey();
            if (key == null || !key.isValid()) {
                con.close();
                continue;
            }
            try {
                setInterest(key, SelectionKey.OP_WRITE);
            } catch (CancelledKeyException cke) {
                if (debug) _log.debug("Cancelled key during write registration for " + con, cke);
                con.close();
            } catch (IllegalArgumentException iae) {
                if (warn) _log.warn("Invalid key for write registration for " + con, iae);
                con.close();
            }
        }
    }

    private void processServerSocketRegistrations(boolean debug, boolean warn) {
        ServerSocketChannel chan;
        int count = 0;
        while (count++ < MAX_BATCH && (chan = _wantsRegister.poll()) != null) {
            try {
                SelectionKey key = chan.register(_selector, SelectionKey.OP_ACCEPT);
                key.attach(chan);
            } catch (ClosedChannelException cce) {
                if (debug) _log.debug("Error registering server socket", cce);
                else if (warn) _log.warn("Error registering server socket: " + cce.getMessage());
            }
        }
    }

    private void processOutboundConnectionRegistrations(boolean debug, boolean warn) {
        NTCPConnection con;
        int count = 0;
        while (count++ < MAX_BATCH && (con = _wantsConRegister.poll()) != null) {
            if (con.isClosed()) continue;
            final SocketChannel schan = con.getChannel();
            if (schan == null) continue;
            try {
                SelectionKey key = schan.register(_selector, SelectionKey.OP_CONNECT);
                key.attach(con);
                con.setKey(key);
                RouterAddress naddr = con.getRemoteAddress();
                if (naddr == null || naddr.getPort() <= 0 || naddr.getIP() == null) {
                    throw new IOException("Invalid NTCP address: " + naddr);
                }
                InetSocketAddress saddr = new InetSocketAddress(InetAddress.getByAddress(naddr.getIP()), naddr.getPort());
                if (schan.connect(saddr)) {
                    setInterest(key, SelectionKey.OP_READ);
                    processConnect(key);
                }
            } catch (IOException | UnresolvedAddressException e) {
                if (debug) {
                    _log.debug("[NTCP] Failed outbound connection to " + con.getRemotePeer(), e);
                } else if (warn) {
                    _log.warn("[NTCP] Failed outbound connection to " + con.getRemotePeer());
                }
                con.closeOnTimeout("\n* Connect failed: " + e.getMessage(), e);
                RouterIdentity remote = con.getRemotePeer();
                if (remote != null) {
                    _transport.markUnreachable(remote.calculateHash());
                }
                _context.statManager().addRateData("ntcp.connectFailedTimeoutIOE", 1);
                recordFailedOutbound(con.getRemotePeer());
            } catch (CancelledKeyException cke) {
                if (debug) _log.debug("Cancelled key during connect to " + con.getRemotePeer(), cke);
                con.close();
            } catch (Exception e) {
                boolean isNetworkingError = classifyConnectException(e) == ConnectErrorKind.NETWORK;
                if (debug) {
                    _log.debug("[NTCP] " + (isNetworkingError ? "Connection setup error" : "Unexpected error") +
                              " during outbound registration for " + con.getRemotePeer(), e);
                } else if (warn) {
                    _log.warn("[NTCP] " + (isNetworkingError ? "Connection setup error" : "Unexpected error") +
                              " during outbound registration for " + con.getRemotePeer() +
                              ": " + e.getClass().getSimpleName() +
                              (isNetworkingError ? "" : " - " + e.getMessage()));
                }
                if (isNetworkingError) {
                    _transport.markUnreachable(con.getRemotePeer().calculateHash());
                }
                con.close();
            }
        }
    }

    /**
     * How a thrown exception from outbound connect setup should be treated.
     * @since 0.9.71+
     */
    enum ConnectErrorKind {
        /** Transport-level failure: the peer should be marked unreachable. */
        NETWORK,
        /** Unexpected internal error: the connection is closed but the peer stays reachable. */
        OTHER
    }

    /**
     * Classify an exception thrown during outbound connect setup. Genuine
     * channel-state failures from the socket layer (already-connected, connect
     * pending, not-yet-connected) mean the peer or the connect attempt is at
     * fault and the peer should be marked unreachable. Anything else - local
     * configuration misuse (blocking-mode channel), invalid arguments, or an
     * internal defect - only tears down the local connection so an internal bug
     * never blames (and blacks out) a healthy remote peer.
     *
     * <p>Classified by exception type rather than class-name substrings: name
     * matching silently misreads e.g. NotYetConnectedException as OTHER and
     * blames a peer for a local IllegalBlockingModeException.
     *
     * @param e  the exception to classify (never null)
     * @return the {@link ConnectErrorKind} for this exception
     * @since 0.9.71+
     */
    static ConnectErrorKind classifyConnectException(Exception e) {
        if (e instanceof AlreadyConnectedException ||
            e instanceof ConnectionPendingException ||
            e instanceof NotYetConnectedException) {
            return ConnectErrorKind.NETWORK;
        }
        return ConnectErrorKind.OTHER;
    }

    /**
     * Record the given IP as blocked.
     */
    public void blockIP(byte[] ip) {
        if (ip == null) return;
        String ba = Addresses.toString(ip);
        _blockedIPs.increment(ba);
    }

    /**
     * Track failed inbound handshake for diagnostic stats.
     * Handshake timeouts on the open internet are network noise, not hostile,
     * so we do not ban for them.
     *
     * @param ip source IP address (tracked for stats only)
     * @param hash optional router hash if available
     */
    public void trackFailedInboundHandshake(byte[] ip, Hash hash) {
        if (ip == null) return;
        _context.statManager().addRateData("ntcp.inboundEstablishFailed", 1);
    }

    /**
     * Track failed inbound handshake with invalid encryption and ban if too many failures.
     * Ban reason: "Invalid encryption"
     * @param ip byte array IP address
     * @param hash optional router hash if available
     */
    public void trackInvalidEncryption(byte[] ip, Hash hash) {
        if (ip == null) return;
        String ba = Addresses.toString(ip);
        int count = _failedInboundEncryption.increment(ba);
        if (count >= 5) {
            _failedInboundEncryption.clear(ba);
            BanLogger bl = BanLogger.getInstance();
            if (bl != null) {
                bl.logBan(hash, ba, "Invalid encryption", 60 * 60 * 1000L);
            }
        }
    }

    /**
     * Track failed inbound handshake (IP only, no hash).
     */
    public void trackFailedInboundHandshake(byte[] ip) {
        trackFailedInboundHandshake(ip, null);
    }

    private long _lastExpired;
    private void expireTimedOut() {
        _transport.expireTimedOut();
    }

    /**
     * The idle timeout for connections.
     * @return the idle timeout
     */
    public long getIdleTimeout() {
        return _expireIdleWriteTime;
    }

    /** Selector loop delay in milliseconds. */
    public static long getSelectorLoopDelay() { return _selectorLoopDelay; }

    /** Max idle loop rate in loops per second. */
    public static int getMaxIdleLps() { return _maxIdleLps; }

    /**
     * Max idle loop rate in loops per second, bounded 1-5000.
     * The pumper enforces this as a minimum idle iteration time (1e9 / rate),
     * capping idle busy-spin even when selector wakeups defeat the timeout.
     */
    public static void setMaxIdleLps(int lps) {
        _maxIdleLps = Math.max(MIN_MAX_IDLE_LPS, Math.min(MAX_MAX_IDLE_LPS, lps));
    }

    /**
     * Selector loop delay, bounded 1-SELECTOR_MAX_DELAY ms.
     * Updates the base delay the pumper relaxes toward and raises the live
     * delay immediately so Tuner-driven increases take effect without waiting
     * for the pumper's own 60s ramp.
     */
    public static void setSelectorLoopDelay(long ms) {
        long v = Math.max(1, Math.min(SELECTOR_MAX_DELAY, ms));
        _selectorLoopDelay = v;
        if (_currentDelay < v)
            _currentDelay = v;
    }

    /** Failsafe iteration frequency in milliseconds. */
    public static long getFailsafeIterationFreq() { return _failsafeIterationFreq; }

    /** Failsafe iteration frequency, bounded by MIN-MAX. */
    public static void setFailsafeIterationFreq(long ms) { _failsafeIterationFreq = Math.max(MIN_FAILSAFE_FREQ, Math.min(MAX_FAILSAFE_FREQ, ms)); }

    /** Interest operations on the given selection key. */
    public static void setInterest(SelectionKey key, int op) throws CancelledKeyException {
        if (key == null || !key.isValid()) return;
        synchronized (key) {
            int old = key.interestOps();
            if ((old & op) == 0)
                key.interestOps(old | op);
        }
    }

    /**
     * Clear the given interest operation on the selection key.
     */
    public static void clearInterest(SelectionKey key, int op) throws CancelledKeyException {
        if (key == null || !key.isValid()) return;
        synchronized (key) {
            int old = key.interestOps();
            if ((old & op) != 0)
                key.interestOps(old & ~op);
        }
    }
}
