package net.i2p.router.transport.ntcp;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.NoConnectionPendingException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.util.EnumSet;
import java.util.Iterator;
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
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
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
 *   <li>Immediate {@code wakeup()} on pending I/O (no cooldown)</li>
 *   <li>Duplicate-free write request tracking via {@code ConcurrentHashSet}</li>
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
    private long _expireIdleWriteTime;
    private static final boolean _useDirect = false;
    private final boolean _nodelay;

    // Outbound retry throttling (non-hot path, kept for robustness)
    private final Map<Hash, Long> _failedOutboundAttempts = new ConcurrentHashMap<>();
    private final Map<Hash, Integer> _failedOutboundCount = new ConcurrentHashMap<>();
    private static final long MIN_RETRY_INTERVAL = 500;
    private static final int MAX_RETRY_INTERVAL = 5 * 60 * 1000; // 5 minutes
    private static final float RETRY_BACKOFF_FACTOR = 1.5f;
    private long _lastRetryMapClear = System.currentTimeMillis();
    private static final long RETRY_MAP_CLEAR_INTERVAL = 5 * 60 * 1000; // 5 minutes

    /**
     * This probably doesn't need to be bigger than the largest typical
     * message, which is a 5-slot VTBM (~2700 bytes).
     * The occasional larger message can use multiple buffers.
     */
    private static final int BUF_SIZE = 8 * 1024;

    private static class BufferFactory implements TryCache.ObjectFactory<ByteBuffer> {
        public ByteBuffer newInstance() {
            if (_useDirect) {
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
     */
    private static final boolean isSlow = SystemVersion.isSlow();
    private static final long FAILSAFE_ITERATION_FREQ = 2 * 1000L;
    private static final int FAILSAFE_LOOP_COUNT = isSlow ? 512 : 2048;
    private static final long SELECTOR_LOOP_DELAY = isSlow ? 200 : 20;
    private static final long BLOCKED_IP_FREQ = 43 * 60 * 1000;
    /** tunnel test now disabled, but this should be long enough to allow an active tunnel to get started */
    private static final long MIN_EXPIRE_IDLE_TIME = 120 * 1000L;
    private static final long MAX_EXPIRE_IDLE_TIME = 11 * 60 * 1000L;
    private static final long MAY_DISCON_TIMEOUT = 10 * 1000;
    private static final long RI_STORE_INTERVAL = 29 * 60 * 1000;

    /**
     * Do we use direct buffers for reading? Default false.
     * NOT recommended as we don't keep good track of them so they will leak.
     *
     * Unsupported, set _useDirect above.
     *
     * @see java.nio.ByteBuffer
     */
    private static final String PROP_NODELAY = "i2np.ntcp.nodelay";
    private static final int MIN_MINB = SystemVersion.isSlow() ? 4 : 8;
    private static final int MAX_MINB = SystemVersion.isSlow() ? 12 : 16;
    private static final int MIN_BUFS;
    static {
        long maxMemory = SystemVersion.getMaxMemory();
        MIN_BUFS = (int) Math.max(MIN_MINB, Math.min(MAX_MINB, 1 + (maxMemory / (32 * 1024 * 1024))));
    }

    private static final TryCache<ByteBuffer> _bufferCache = new TryCache<>(new BufferFactory(), MIN_BUFS);
    private static final Set<Status> STATUS_OK = EnumSet.of(Status.OK, Status.IPV4_OK_IPV6_UNKNOWN, Status.IPV4_OK_IPV6_FIREWALLED);
    private static final long[] RATES = { 60*1000, 10*60*1000l };

    public EventPumper(RouterContext ctx, NTCPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _transport = transport;
        _expireIdleWriteTime = MAX_EXPIRE_IDLE_TIME;
        _nodelay = ctx.getBooleanPropertyDefaultTrue(PROP_NODELAY);
        _blockedIPs = new ObjectCounter<>();
        _context.statManager().createRateStat("ntcp.pumperKeySetSize", "Number of NTCP Pumper KeySetSize events", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.pumperLoopsPerSecond", "Number of NTCP Pumper loops/s", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.zeroRead", "Number of NTCP zero length read events", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.zeroReadDrop", "Number of NTCP zero length read events dropped", "Transport [NTCP]", RATES);
        _context.statManager().createRateStat("ntcp.dropInboundNoMessage", "Number of NTCP Inbound empty message drop events", "Transport [NTCP]", RATES);
        _context.statManager().createRequiredRateStat("ntcp.inboundConn", "Inbound NTCP Connection", "Transport [NTCP]", RATES);
    }

    public synchronized void startPumping() {
        if (_log.shouldInfo())
            _log.info("Starting NTCP Pumper...");
        try {
            _selector = Selector.open();
            _alive = true;
            I2PThread t = new I2PThread(this, "NTCP Pumper", true);
            t.start();
        } catch (IOException ioe) {
            _log.log(Log.CRIT, "Error opening the NTCP selector", ioe);
        } catch (java.lang.InternalError jlie) {
            // "unable to get address of epoll functions, pre-2.6 kernel?"
            _log.log(Log.CRIT, "Error opening the NTCP selector", jlie);
        }
    }

    public synchronized void stopPumping() {
        _alive = false;
        if (_selector != null && _selector.isOpen())
            _selector.wakeup();
    }

    /**
     * Selector can take quite a while to close after calling stopPumping()
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
                int consecutiveFailures = failCount != null ? failCount : 1;
                long delay = (long) (MIN_RETRY_INTERVAL * Math.pow(RETRY_BACKOFF_FACTOR, Math.min(100, consecutiveFailures - 1)));
                delay = Math.min(delay, MAX_RETRY_INTERVAL);
                if (now - lastFailed < delay) {
                    if (_log.shouldWarn()) {
                        _log.warn("Throttling retry to " + remote + " (last failed " + (now - lastFailed) + "ms ago, attempt " + consecutiveFailures + ")");
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

    @Override
    public void run() {
        int loopCount = 0;
        int loopCountSinceLastRate = 0;
        long lastFailsafeIteration = System.currentTimeMillis();
        long lastLoopRateUpdate = System.currentTimeMillis();
        long lastKeySetUpdate = lastLoopRateUpdate;
        long lastBlockedIPClear = lastFailsafeIteration;
        final boolean shouldDebug = _log.shouldDebug();
        final boolean shouldWarn = _log.shouldWarn();

        while (_alive && _selector != null && _selector.isOpen()) {
            try {
                loopCount++;
                loopCountSinceLastRate++;
                int selectedCount;
                try {
                    selectedCount = _selector.select(SELECTOR_LOOP_DELAY);
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
                }

                runDelayedEvents();

                long now = System.currentTimeMillis();

                // Update loop rate stat 60 seconds
                if (now - lastLoopRateUpdate >= 60_000) {
                    long elapsedMs = now - lastLoopRateUpdate;
                    int elapsedSeconds = (int) (elapsedMs / 1000);
                    if (elapsedSeconds <= 0) elapsedSeconds = 1;
                    int loopsPerSecond = loopCountSinceLastRate / elapsedSeconds;
                    _context.statManager().addRateData("ntcp.pumperLoopsPerSecond", loopsPerSecond);
                    loopCountSinceLastRate = 0;
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

                // Periodic failsafe iteration (every 2s)
                if (now - lastFailsafeIteration >= FAILSAFE_ITERATION_FREQ) {
                    doFailsafeCheck();
                    lastFailsafeIteration = now;
                }

                // CPU protection: light sleep after many loops
                if ((loopCount % FAILSAFE_LOOP_COUNT) == FAILSAFE_LOOP_COUNT - 1) {
                    if (shouldDebug)
                        _log.debug("EventPumper throttle after " + loopCount + " loops");
                    _context.statManager().addRateData("ntcp.failsafeThrottle", 1);
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                // Clear blocked IP table periodically
                if (now - lastBlockedIPClear >= BLOCKED_IP_FREQ) {
                    synchronized (_blockedIPs) {
                        _blockedIPs.clear();
                    }
                    lastBlockedIPClear = now;
                }

            } catch (RuntimeException re) {
                _log.error("Error in NTCP EventPumper", re);
            }
        }

        // Cleanup
        try {
            if (_selector.isOpen()) {
                if (shouldDebug)
                    _log.debug("Closing NTCP EventPumper with " + _selector.keys().size() + " keys");
                for (SelectionKey key : _selector.keys()) {
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
        _wantsConRegister.clear();
        _wantsRead.clear();
        _wantsRegister.clear();
        _wantsWrite.clear();
    }

    /**
     * Periodic failsafe scan of all connections to:
     * - Reassert OP_WRITE interest if data is pending
     * - Close idle/stale connections
     * - Send periodic RouterInfo
     */
    private void doFailsafeCheck() {
        try {
            Set<SelectionKey> all = _selector.keys();
            int failsafeWrites = 0;
            int failsafeCloses = 0;
            int failsafeInvalid = 0;
            boolean haveCap = _transport.haveCapacity(33);
            if (haveCap)
                _expireIdleWriteTime = Math.min(_expireIdleWriteTime + 1000, MAX_EXPIRE_IDLE_TIME);
            else
                _expireIdleWriteTime = Math.max(_expireIdleWriteTime - 3000, MIN_EXPIRE_IDLE_TIME);

            long now = System.currentTimeMillis();
            for (SelectionKey key : all) {
                try {
                    Object att = key.attachment();
                    if (!(att instanceof NTCPConnection)) continue;
                    NTCPConnection con = (NTCPConnection) att;

                    if (!key.isValid() && con.getTimeSinceCreated(now) > 2 * NTCPTransport.ESTABLISH_TIMEOUT) {
                        con.close();
                        key.cancel();
                        failsafeInvalid++;
                        continue;
                    }

                    if (!con.isWriteBufEmpty() && (key.interestOps() & SelectionKey.OP_WRITE) == 0) {
                        setInterest(key, SelectionKey.OP_WRITE);
                        failsafeWrites++;
                    }

                    final long expire;
                    if ((!haveCap || !con.isInbound()) &&
                        con.getMayDisconnect() &&
                        con.getMessagesReceived() <= 2 &&
                        con.getMessagesSent() <= 1) {
                        expire = MAY_DISCON_TIMEOUT;
                    } else {
                        expire = _expireIdleWriteTime;
                    }

                    if (con.getLastActiveTime() + expire < now) {
                        con.sendTerminationAndClose();
                        failsafeCloses++;
                    } else {
                        long estab = con.getEstablishedOn();
                        if (estab > 0) {
                            long uptime = now - estab;
                            if (uptime >= RI_STORE_INTERVAL && (uptime % RI_STORE_INTERVAL) < FAILSAFE_ITERATION_FREQ) {
                                con.sendOurRouterInfo(false);
                            }
                        }
                    }
                } catch (CancelledKeyException ignored) {}
            }
            if (failsafeWrites > 0)
                _context.statManager().addRateData("ntcp.failsafeWrites", failsafeWrites);
            if (failsafeCloses > 0)
                _context.statManager().addRateData("ntcp.failsafeCloses", failsafeCloses);
            if (failsafeInvalid > 0)
                _context.statManager().addRateData("ntcp.failsafeInvalid", failsafeInvalid);
        } catch (ClosedSelectorException ignored) {}
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
            return _useDirect ? ByteBuffer.allocateDirect(BUF_SIZE) : ByteBuffer.allocate(BUF_SIZE);
        buf.clear();
        return buf;
    }

    /**
     * Return a read buffer to the pool.
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
            boolean isBanned = _context.blocklist().isBlocklisted(ip);
            if (isBanned) {
                if (shouldInfo) {
                    _log.info("Refusing Session Request from blocklisted IP address " + ba);
                }
                try {
                    chan.close();
                } catch (IOException ioe) {}
                return;
            }
            if (!_context.commSystem().isExemptIncoming(Addresses.toCanonicalString(ba))) {
                if (!_transport.allowConnection()) {
                    if (shouldWarn) {
                        _log.warn("Refusing Session Request from: " + ba + " -> NTCP connection limit reached");
                    }
                    try {
                        chan.close();
                    } catch (IOException ioe) {}
                    return;
                }
                int count = _blockedIPs.count(ba);
                if (count > 0) {
                    count = _blockedIPs.increment(ba);
                    if (shouldInfo) {
                        _log.info("Blocking NTCP connection attempt from: " + ba + " (Count: " + count + ")");
                    }
                    if (count >= 10 && shouldWarn) {
                        _log.warn("Banning " + ba + " for 8 hours -> Excessive inbound NTCP connection attempts (" + count + ")");
                        byte[] ipBytes = Addresses.getIP(ba);
                        if (ipBytes != null) {
                            _context.blocklist().addTemporary(ipBytes, 8*60*60*1000, "Excessive NTCP connection attempts");
                        }
                    }
                    try {
                        chan.close();
                    } catch (IOException ioe) {}
                    return;
                }
                if (!shouldAllowInboundEstablishment()) {
                    try {
                        chan.close();
                    } catch (IOException ioe) {}
                    return;
                }
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

    private boolean shouldAllowInboundEstablishment() {
        RateStat rs = _context.statManager().getRate("ntcp.inboundConn");
        if (rs == null) return true;
        Rate r = rs.getRate(RateConstants.ONE_MINUTE);
        if (r == null) return true;
        int last;
        long periodStart;
        RateAverages ra = RateAverages.getTemp();
        synchronized (r) {
            last = (int) r.getLastEventCount();
            periodStart = r.getLastCoalesceDate();
            r.computeAverages(ra, true);
        }
        if (last < 15) last = 15;
        int total = (int) ra.getTotalEventCount();
        int current = total - last;
        if (current <= 0) return true;
        int lastPeriod = 60 * 1000;
        int currentTime = (int) (_context.clock().now() - periodStart);
        if (currentTime <= 5 * 1000) return true;
        float lastRate = last / (float) lastPeriod;
        float currentRate = (float) (current / (double) currentTime);
        float factor = _transport.haveCapacity(95) ? 1.05f : 0.95f;
        float minThresh = factor * lastRate;
        int maxConnections = _transport.getMaxConnections();
        int currentConnections = _transport.countPeers();
        if (currentRate > minThresh * 5 / 3 && (currentConnections > (maxConnections * 2 / 3))) {
            int probAccept = Math.max(1, ((int) (4 * 128 * currentRate / minThresh)) - 512);
            int percent = probAccept > 128 ? 100 : (probAccept / 128) * 100;
            if (probAccept >= 128 || _context.random().nextInt(128) < probAccept) {
                if (_log.shouldWarn()) {
                    _log.warn("Dropping incoming TCP connection (" + (percent >= 1 ? Math.min(percent, 100) + "%" : "1%") + " chance)" +
                              " -> Previous/current connections per minute: " + last + " / " + (int) (currentRate * 60 * 1000));
                }
                return false;
            }
        }
        return true;
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
                _log.debug("Processing connect for " + con + ": connected? " + connected);
            if (connected) {
                if (shouldSetKeepAlive(chan))
                    chan.socket().setKeepAlive(true);
                if (_nodelay)
                    chan.socket().setTcpNoDelay(true);
                con.setKey(key);
                con.outboundConnected();
                _context.statManager().addRateData("ntcp.connectSuccessful", 1);
            } else {
                con.closeOnTimeout("Connect failed (10s timeout exceeded) -> Marking unreachable", null);
                _transport.markUnreachable(con.getRemotePeer().calculateHash());
                _context.statManager().addRateData("ntcp.connectFailedTimeout", 1);
            }
        } catch (IOException ioe) {
            if (_log.shouldDebug()) {
                _log.debug("[NTCP] Failed outbound connection to " + con.getRemotePeer(), ioe);
            } else if (_log.shouldWarn()) {
                _log.warn("[NTCP] Failed outbound connection to " + con.getRemotePeer());
            }
            con.closeOnTimeout("Connect failed: " + ioe.getMessage(), ioe);
            RouterIdentity remote = con.getRemotePeer();
            if (remote != null) {
                Hash peerHash = remote.calculateHash();
                _failedOutboundAttempts.put(peerHash, System.currentTimeMillis());
                _failedOutboundCount.merge(peerHash, 1, Integer::sum);
            }
            _transport.markUnreachable(con.getRemotePeer().calculateHash());
            _context.statManager().addRateData("ntcp.connectFailedTimeoutIOE", 1);
        } catch (NoConnectionPendingException ncpe) {
            if (_log.shouldWarn()) _log.warn("Error connecting on " + con, ncpe);
        }
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
                int readCount = 0;
                int bytesRead;
                while ((bytesRead = chan.read(buf)) > 0) {
                    totalRead += bytesRead;
                    readCount++;
                }
                if (bytesRead < 0 && totalRead == 0) totalRead = bytesRead;
                if (shouldDebug && totalRead != 0) {
                    _log.debug("Read " + totalRead + " bytes " + con);
                }
                if (totalRead < 0) {
                    if (con.isInbound() && con.getMessagesReceived() <= 0) {
                        InetAddress addr = chan.socket().getInetAddress();
                        int count;
                        if (addr != null) {
                            String ipStr = Addresses.toString(addr.getAddress()).replace("/", "");
                            count = _blockedIPs.increment(ipStr);
                            if (shouldInfo) {
                                _log.info("EOF on Inbound connection before receiving any data, blocking IP: "
                                          + ipStr + (count > 1 ? " (Count: " + count + ")" : ""));
                            }
                        } else {
                            count = 1;
                            if (shouldInfo) {
                                _log.info("EOF on Inbound connection before receiving any data: " + con);
                            }
                        }
                        _context.statManager().addRateData("ntcp.dropInboundNoMessage", count);
                    } else if (shouldDebug) {
                        _log.debug("EOF on " + con);
                    }
                    con.close();
                    releaseBuf(buf);
                    break;
                }
                if (totalRead == 0) {
                    releaseBuf(buf);
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
                            _log.debug("Nothing to read for " + con + ", remaining interested (Count: " + zeroReadCount + ")");
                        }
                    }
                    break;
                }
                con.clearZeroRead();
                ((Buffer) buf).flip();
                FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestInbound(totalRead, "NTCP read");
                if (req.getPendingRequested() > 0) {
                    clearInterest(key, SelectionKey.OP_READ);
                    con.queuedRecv(buf, req);
                    break;
                } else {
                    con.recv(buf);
                    if (bytesRead < 0) {
                        con.close();
                        break;
                    }
                    if (buf.hasRemaining()) {
                        break;
                    }
                }
            }
        } catch (CancelledKeyException cke) {
            if (buf != null) releaseBuf(buf);
            if (shouldInfo) _log.info("Error reading on " + con + " -> " + cke.getMessage());
            con.close();
            _context.statManager().addRateData("ntcp.readError", 1);
        } catch (IOException ioe) {
            if (buf != null) releaseBuf(buf);
            if (con.isInbound() && con.getMessagesReceived() <= 0) {
                byte[] ip = con.getRemoteIP();
                int count;
                if (ip != null) {
                    String ipStr = Addresses.toString(ip).replace("/", "");
                    count = _blockedIPs.increment(ipStr);
                    if (shouldInfo) {
                        _log.info("Blocking IP address " + ipStr + (count > 1 ? " (Count: " + count + ")" : "")
                                  + " -> IO Error: " + ioe.getMessage());
                    }
                } else {
                    count = 1;
                    if (shouldInfo) {
                        _log.info("IO Error on Inbound connection before receiving any data: " + con);
                    }
                }
                _context.statManager().addRateData("ntcp.dropInboundNoMessage", count);
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
        } catch (NotYetConnectedException nyce) {
            if (buf != null) releaseBuf(buf);
            clearInterest(key, SelectionKey.OP_READ);
            if (shouldInfo) _log.info("Error reading: " + con, nyce);
        }
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

    public boolean processWrite(final NTCPConnection con, final SelectionKey key) {
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
            synchronized (con.getWriteLock()) {
                while (true) {
                    ByteBuffer buf = con.getNextWriteBuf();
                    if (buf != null) {
                        if (buf.remaining() <= 0) {
                            con.removeWriteBuf(buf);
                            continue;
                        }
                        int written = chan.write(buf);
                        if (written == 0) {
                            if ((buf.remaining() > 0) || (!con.isWriteBufEmpty())) {
                                // stay interested
                            } else {
                                rv = true;
                            }
                            break;
                        } else if (buf.remaining() > 0) {
                            break;
                        } else {
                            con.removeWriteBuf(buf);
                        }
                    } else {
                        if (key.isValid()) rv = true;
                        break;
                    }
                }
                if (rv) {
                    clearInterest(key, SelectionKey.OP_WRITE);
                } else {
                    setInterest(key, SelectionKey.OP_WRITE);
                }
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
                con.closeOnTimeout("Connect failed: " + e.getMessage(), e);
                _transport.markUnreachable(con.getRemotePeer().calculateHash());
                _context.statManager().addRateData("ntcp.connectFailedTimeoutIOE", 1);
                RouterIdentity remote = con.getRemotePeer();
                if (remote != null) {
                    Hash peerHash = remote.calculateHash();
                    _failedOutboundAttempts.put(peerHash, System.currentTimeMillis());
                    _failedOutboundCount.merge(peerHash, 1, Integer::sum);
                }
            } catch (CancelledKeyException cke) {
                if (debug) _log.debug("Cancelled key during connect to " + con.getRemotePeer(), cke);
                con.close();
            } catch (Exception e) {
                // Determine if this is a networking exception vs unexpected error
                String exceptionType = e.getClass().getSimpleName();
                boolean isNetworkingError = exceptionType.contains("Blocking") ||
                                        exceptionType.contains("Connection") ||
                                        exceptionType.contains("Selector") ||
                                        exceptionType.contains("Address") ||
                                        exceptionType.equals("IllegalArgumentException") ||
                                        exceptionType.equals("NullPointerException");

                if (debug) {
                    _log.debug("[NTCP] " + (isNetworkingError ? "Connection setup error" : "Unexpected error") +
                              " during outbound registration for " + con.getRemotePeer(), e);
                } else if (warn) {
                    _log.warn("[NTCP] " + (isNetworkingError ? "Connection setup error" : "Unexpected error") +
                              " during outbound registration for " + con.getRemotePeer() +
                              (isNetworkingError ? ": " + exceptionType : ": " + exceptionType + " - " + e.getMessage()));
                }

                if (isNetworkingError) {
                    _transport.markUnreachable(con.getRemotePeer().calculateHash());
                }
                con.close();
            }
        }
    }

    public void blockIP(byte[] ip) {
        if (ip == null) return;
        String ba = Addresses.toString(ip);
        _blockedIPs.increment(ba);
    }

    private long _lastExpired;
    private void expireTimedOut() {
        _transport.expireTimedOut();
    }

    public long getIdleTimeout() {
        return _expireIdleWriteTime;
    }

    public static void setInterest(SelectionKey key, int op) throws CancelledKeyException {
        if (key == null || !key.isValid()) return;
        synchronized (key) {
            int old = key.interestOps();
            if ((old & op) == 0)
                key.interestOps(old | op);
        }
    }

    public static void clearInterest(SelectionKey key, int op) throws CancelledKeyException {
        if (key == null || !key.isValid()) return;
        synchronized (key) {
            int old = key.interestOps();
            if ((old & op) != 0)
                key.interestOps(old & ~op);
        }
    }
}