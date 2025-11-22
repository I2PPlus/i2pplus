package net.i2p.router.transport.ntcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.Buffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.NoConnectionPendingException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.channels.UnresolvedAddressException;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.i2p.I2PAppContext;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;
import net.i2p.data.Hash;
import net.i2p.util.TryCache;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SystemVersion;

/**
 *  The main NTCP NIO thread.
 */
class EventPumper implements Runnable {
    private final RouterContext _context;
    private final Log _log;
    private volatile boolean _alive;
    private Selector _selector;
    private final Queue<NTCPConnection> _wantsWrite = new ConcurrentLinkedQueue<NTCPConnection>();

    /**
     *  The following 3 are unbounded and lockless for performance in runDelayedEvents()
     */
    private final Queue<NTCPConnection> _wantsRead = new ConcurrentLinkedQueue<NTCPConnection>();
    private final Queue<ServerSocketChannel> _wantsRegister = new ConcurrentLinkedQueue<ServerSocketChannel>();
    private final Queue<NTCPConnection> _wantsConRegister = new ConcurrentLinkedQueue<NTCPConnection>();
    private final NTCPTransport _transport;
    private final ObjectCounter<String> _blockedIPs;
    private long _expireIdleWriteTime;
    private static final boolean _useDirect = false;
    private final boolean _nodelay;

    private final Map<Hash, Long> _failedOutboundAttempts = new ConcurrentHashMap<>();
    private static final long MIN_RETRY_INTERVAL = 30_000; // 30 seconds
    private static final int MAX_RETRY_INTERVAL = 5 * 60 * 1000; // 5 minutes
    private static final float RETRY_BACKOFF_FACTOR = 1.5f;
    private long _lastRetryMapClear = System.currentTimeMillis();
    private static final long RETRY_MAP_CLEAR_INTERVAL = 5 * 60 * 1000; // 5 minutes

    /**
     *  This probably doesn't need to be bigger than the largest typical
     *  message, which is a 5-slot VTBM (~2700 bytes).
     *  The occasional larger message can use multiple buffers.
     */
    private static final int BUF_SIZE = 16*1024;

    private static class BufferFactory implements TryCache.ObjectFactory<ByteBuffer> {
        public ByteBuffer newInstance() {
            if (_useDirect) {return ByteBuffer.allocateDirect(BUF_SIZE);}
            else {return ByteBuffer.allocate(BUF_SIZE);}
        }
    }

    /**
     * Every few seconds, iterate across all ntcp connections just to make sure
     * we have their interestOps set properly (and to expire any looong idle cons).
     * as the number of connections grows, we should try to make this happen
     * less frequently (or not at all), but while the connection count is small,
     * the time to iterate across them to check a few flags shouldn't be a problem.
     */
    private static final int FAILSAFE_ITERATION_FREQ = 10*1000;
    private static final int FAILSAFE_LOOP_COUNT = SystemVersion.isSlow() ? 64 : 128;
    private static final long SELECTOR_LOOP_DELAY = SystemVersion.isSlow() ? 500 : 200;
    private static final long BLOCKED_IP_FREQ = 10*60*1000;

    /** tunnel test now disabled, but this should be long enough to allow an active tunnel to get started */
    private static final long MIN_EXPIRE_IDLE_TIME = 90*1000l;
    private static final long MAX_EXPIRE_IDLE_TIME = 11*60*1000l;
    private static final long MAY_DISCON_TIMEOUT = 10*1000;
    private static final long RI_STORE_INTERVAL = 29*60*1000;

    /**
     *  Do we use direct buffers for reading? Default false.
     *  NOT recommended as we don't keep good track of them so they will leak.
     *
     *  Unsupported, set _useDirect above.
     *
     *  @see java.nio.ByteBuffer
     */
    private static final String PROP_NODELAY = "i2np.ntcp.nodelay";
    private static final int MIN_MINB = SystemVersion.isSlow() ? 4 : 8;
    private static final int MAX_MINB = SystemVersion.isSlow() ? 16 : Math.max(SystemVersion.getCores(), 32);
    public static final String PROP_MAX_MINB = "i2np.ntcp.eventPumperMaxBuffers";
    private static final int MIN_BUFS;
    static {
        long maxMemory = SystemVersion.getMaxMemory();
        boolean isSlow = SystemVersion.isSlow();
        MIN_BUFS = (int) Math.max(MIN_MINB, Math.min(MAX_MINB, 1 + (maxMemory / (16*1024*1024))));
    }

    private static final float DEFAULT_THROTTLE_FACTOR = SystemVersion.isSlow() ? 2.5f : 5f;
    private static final String PROP_THROTTLE_FACTOR = "router.throttleFactor";
    private static final TryCache<ByteBuffer> _bufferCache = new TryCache<>(new BufferFactory(), MIN_BUFS);
    private static final Set<Status> STATUS_OK = EnumSet.of(Status.OK, Status.IPV4_OK_IPV6_UNKNOWN, Status.IPV4_OK_IPV6_FIREWALLED);

    public EventPumper(RouterContext ctx, NTCPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _transport = transport;
        _expireIdleWriteTime = MAX_EXPIRE_IDLE_TIME;
        _blockedIPs = new ObjectCounter<String>();
        _context.statManager().createRateStat("ntcp.pumperKeySetSize", "Number of NTCP Pumper KeySetSize events", "Transport [NTCP]", new long[] {60*1000, 10*60*1000} );
        //_context.statManager().createRateStat("ntcp.pumperKeysPerLoop", "", "Transport [NTCP]", new long[] {10*60*1000} );
        _context.statManager().createRateStat("ntcp.pumperLoopsPerSecond", "Number of NTCP Pumper loops/s", "Transport [NTCP]", new long[] {60*1000, 10*60*1000} );
        _context.statManager().createRateStat("ntcp.zeroRead", "Number of NTCP zero length read events", "Transport [NTCP]", new long[] {60*1000, 10*60*1000} );
        _context.statManager().createRateStat("ntcp.zeroReadDrop", "Number of NTCP zero length read events dropped", "Transport [NTCP]", new long[] {60*1000, 10*60*1000} );
        _context.statManager().createRateStat("ntcp.dropInboundNoMessage", "Number of NTCP Inbound empty message drop events", "Transport [NTCP]", new long[] {60*1000, 10*60*1000} );
        _context.statManager().createRequiredRateStat("ntcp.inboundConn", "Inbound NTCP Connection", "Transport [NTCP]", new long[] { 60*1000L } );
        _nodelay = ctx.getBooleanPropertyDefaultTrue(PROP_NODELAY);
    }

    public synchronized void startPumping() {
        if (_log.shouldInfo()) {_log.info("Starting NTCP Pumper...");}
        try {
            _selector = SelectorProvider.provider().openSelector();
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
        if (_selector != null && _selector.isOpen()) {_selector.wakeup();}
    }

    /**
     *  Selector can take quite a while to close after calling stopPumping()
     */
    public boolean isAlive() {
        return _alive || (_selector != null && _selector.isOpen());
    }

    /**
     *  Register the acceptor.
     *  This is only called from NTCPTransport.bindAddress(), so it isn't clear
     *  why this needs a queue.
     */
    public void register(ServerSocketChannel chan) {
        if (_log.shouldDebug())
            _log.debug("Registering ServerSocketChannel...");
        _wantsRegister.offer(chan);
        if (_selectorIsBlocked) {
            _selector.wakeup();
        }
    }

    /**
     *  Outbound
     */
    public void registerConnect(NTCPConnection con) {
        if (_log.shouldDebug()) {
            _log.debug("Registering " + con + "...");
        }

        RouterIdentity remote = con.getRemotePeer();
        if (remote != null) {
            Hash peerHash = remote.calculateHash();
            Long lastFailed = _failedOutboundAttempts.get(peerHash);
            if (lastFailed != null) {
                long now = System.currentTimeMillis();
                long delay = (long) (MIN_RETRY_INTERVAL * Math.pow(RETRY_BACKOFF_FACTOR, Math.min(5, (now - lastFailed) / MIN_RETRY_INTERVAL)));
                delay = Math.min(delay, MAX_RETRY_INTERVAL);
                if (now - lastFailed < delay) {
                    if (_log.shouldWarn()) {
                        _log.warn("Throttling retry to " + remote + " (last failed " + (now - lastFailed) + "ms ago)");
                    }
                    con.closeOnTimeout("Connection retry throttled", null);
                    return;
                }
            }
        }

        _context.statManager().addRateData("ntcp.registerConnect", 1);
        _wantsConRegister.offer(con);
        if (_selectorIsBlocked) {
            _selector.wakeup();
        }
    }

    /**
     * Ratio of current connections/min vs previous before throttler activates
     *
     * @since 0.9.58+
     */
    private float getThrottleFactor() {
        return _context.getProperty(PROP_THROTTLE_FACTOR, DEFAULT_THROTTLE_FACTOR);
    }

    private volatile boolean _selectorIsBlocked = false;
    private long _lastDelayedEventTime = System.currentTimeMillis();
    private static final long DELAYED_EVENT_INTERVAL = 200;

    /**
     * Main selector loop for handling non-blocking IO events.
     * Optimized for minimal overhead on high-bandwidth routers.
     * Uses dynamic selector delays, efficient timing checks, and respects thread interruptions.
     */
    public void run() {
        int loopCount = 0;
        int loopCountSinceLastRate = 0;
        final int failsafeLoopCount = FAILSAFE_LOOP_COUNT;
        long lastFailsafeIteration = System.nanoTime();
        long lastBlockedIPClear = lastFailsafeIteration;
        long lastLoopRateUpdate = System.currentTimeMillis();
        long lastKeySetUpdate = lastLoopRateUpdate;
        final boolean shouldDebug = _log.shouldDebug();
        final boolean shouldWarn = _log.shouldWarn();

        // Background executor for failsafe loop; single daemon thread to avoid resource leaks
        ScheduledExecutorService background = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "EventPumperFailsafe");
            t.setDaemon(true);
            return t;
        });
        background.scheduleAtFixedRate(this::doFailsafeCheck, 5, 90, TimeUnit.SECONDS);

        try {
            while (_alive && _selector.isOpen()) {
                try {
                    _needsWakeup = false;
                    loopCount++;
                    loopCountSinceLastRate++;

                    // Determine selector delay dynamically
                    int loopsPerSecond = getAvgPumpLoops();
                    boolean inactive = _wantsWrite.isEmpty() && _wantsRead.isEmpty();
                    long delay = inactive ? 1000L : SELECTOR_LOOP_DELAY;
                    if (loopsPerSecond > 4000) {delay = Math.max(1000, delay *=8);}
                    else if (loopsPerSecond > 3000) {delay = Math.max(1000, delay *=7);}
                    else if (loopsPerSecond > 2000) {delay = Math.max(1000, delay *=6);}
                    else if (loopsPerSecond > 1000) {delay = Math.max(1000, delay *=3);}
                    else if (inactive && loopsPerSecond < 500) {delay = Math.max(1500, delay);}
                    else if (inactive) {delay = Math.max(1000, delay);}

                    int selectedCount;
                    _selectorIsBlocked = true;
                    try {selectedCount = _selector.select(inactive ? 1000L : delay);}
                    catch (ClosedSelectorException cse) {continue;}
                    catch (IOException | CancelledKeyException e) {
                        if (shouldDebug) _log.warn("Error selecting", e);
                        else if (shouldWarn) _log.warn("Error selecting -> " + e.getMessage());
                        continue;
                    } finally {_selectorIsBlocked = false;}

                    if (selectedCount > 0) {
                        Set<SelectionKey> selected = _selector.selectedKeys();
                        processKeys(selected);
                        selected.clear();
                    }

                    long nowMs = System.currentTimeMillis();

                    if (nowMs - _lastDelayedEventTime >= DELAYED_EVENT_INTERVAL) {
                        runDelayedEvents();
                        _lastDelayedEventTime = nowMs;
                    }


                    // Update loop rate stat 15 seconds
                    if (nowMs - lastLoopRateUpdate >= 15*1000) {
                        _context.statManager().addRateData("ntcp.pumperLoopsPerSecond", loopCountSinceLastRate);
                        loopCountSinceLastRate = 0;
                        lastLoopRateUpdate = nowMs;
                    }

                    // Update keyset size stat every 30 seconds
                    if (nowMs - lastKeySetUpdate >= 30000) {
                        _context.statManager().addRateData("ntcp.pumperKeySetSize", _selector.keys().size());
                        lastKeySetUpdate = nowMs;
                    }

                    if (nowMs - _lastRetryMapClear >= RETRY_MAP_CLEAR_INTERVAL) {
                        long cutoff = nowMs - MAX_RETRY_INTERVAL;
                        _failedOutboundAttempts.entrySet().removeIf(e -> e.getValue() < cutoff);
                        _lastRetryMapClear = nowMs;
                    }

                    // Throttle CPU usage periodically with adaptive pause
                    int cpuLoadAvg = SystemVersion.getCPULoadAvg();
                    int pause = SystemVersion.isSlow() || cpuLoadAvg > 95 || loopsPerSecond > 1000 ? 1000 : 50;
                    if ((loopCount % failsafeLoopCount) == failsafeLoopCount - 1) {
                        if (shouldDebug) {
                            long throttleDuration = nowMs - (lastFailsafeIteration / 1_000_000L);
                            _log.debug("EventPumper throttle " + loopCount + " loops in " + throttleDuration + " ms");
                        }
                        _context.statManager().addRateData("ntcp.failsafeThrottle", 1);
                        try {Thread.sleep(pause);}
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt(); // Respect interruption
                            break;
                        }
                        lastFailsafeIteration = System.nanoTime();
                    }

                    // Clear blocked IPs periodically with synchronization
                    if (nowMs - lastBlockedIPClear >= BLOCKED_IP_FREQ) {
                        synchronized (_blockedIPs) {
                            _blockedIPs.clear();
                        }
                        lastBlockedIPClear = nowMs;
                    }

                } catch (RuntimeException re) {
                    _log.error("Error in NTCP EventPumper", re);
                }
            }
        } finally {
            background.shutdownNow();
        }

        // Clean up selection keys and channels on shutdown
        try {
            if (_selector.isOpen()) {
                if (shouldDebug) {
                    _log.debug("Closing down NTCP EventPumper with selection keys remaining...");
                }
                for (SelectionKey key : _selector.keys()) {
                    try {
                        Object att = key.attachment();
                        if (att instanceof ServerSocketChannel) {
                            ((ServerSocketChannel) att).close();
                            key.cancel();
                        } else if (att instanceof NTCPConnection) {
                            ((NTCPConnection) att).close();
                            key.cancel();
                        }
                    } catch (IOException ioe) {
                        _log.error("Error closing key " + key + " on NTCP EventPumper shutdown", ioe);
                    }
                }
                _selector.close();
            } else if (shouldDebug) {
                _log.debug("Closing down NTCP EventPumper with no selection keys remaining...");
            }
        } catch (IOException e) {
            _log.error("Error closing keys on NTCP EventPumper shutdown", e);
        }

        // Clear wants sets safely
        _wantsConRegister.clear();
        _wantsRead.clear();
        _wantsRegister.clear();
        _wantsWrite.clear();
    }

    private void doFailsafeCheck() {
        try {
            Set<SelectionKey> all = _selector.keys();
            int failsafeWrites = 0;
            int failsafeCloses = 0;
            int failsafeInvalid = 0;

            boolean haveCap = _transport.haveCapacity(33);
            if (haveCap) {
                _expireIdleWriteTime = Math.min(_expireIdleWriteTime + 1000, MAX_EXPIRE_IDLE_TIME);
            } else {
                _expireIdleWriteTime = Math.max(_expireIdleWriteTime - 3000, MIN_EXPIRE_IDLE_TIME);
            }

            long now = System.currentTimeMillis();

            for (SelectionKey key : all) {
                try {
                    Object att = key.attachment();
                    if (!(att instanceof NTCPConnection)) continue;
                    NTCPConnection con = (NTCPConnection) att;

                    if ((!key.isValid()) &&
                        (!((SocketChannel) key.channel()).isConnectionPending()) &&
                        con.getTimeSinceCreated(now) > 2 * NTCPTransport.ESTABLISH_TIMEOUT) {
                        if (_log.shouldInfo()) {
                            _log.info("Removing invalid key... " + con);
                        }
                        con.close();
                        key.cancel();
                        failsafeInvalid++;
                        continue;
                    }

                    if (!con.isWriteBufEmpty()) {
                        if (!con.hasWriteInterestPending() &&
                            ((key.interestOps() & SelectionKey.OP_WRITE) == 0)) {
                            if (con.compareAndSetWriteInterestPending(false, true)) {
                                setInterest(key, SelectionKey.OP_WRITE);
                                failsafeWrites++;
                            }
                        }
                    }

                    final long expire;
                    if ((!haveCap || !con.isInbound()) && con.getMayDisconnect() &&
                        con.getMessagesReceived() <= 2 && con.getMessagesSent() <= 1) {
                        expire = MAY_DISCON_TIMEOUT;
                        if (_log.shouldInfo()) {
                            _log.info("Possible early disconnect... " + con);
                        }
                    } else {
                        expire = _expireIdleWriteTime;
                    }

                    // Use last active time instead of checking send/receive separately
                    if (con.getLastActiveTime() + expire < now) {
                        con.sendTerminationAndClose();
                        if (_log.shouldInfo()) {
                            _log.info("Failsafe or expire close... " + con);
                        }
                        failsafeCloses++;
                    } else {
                        long estab = con.getEstablishedOn();
                        if (estab > 0) {
                            long uptime = now - estab;
                            if (uptime >= RI_STORE_INTERVAL) {
                                long mod = uptime % RI_STORE_INTERVAL;
                                if (mod < FAILSAFE_ITERATION_FREQ) {
                                    con.sendOurRouterInfo(false);
                                }
                            }
                        }
                    }
                } catch (CancelledKeyException cke) {} // Ignore
            }

            if (failsafeWrites > 0) {_context.statManager().addRateData("ntcp.failsafeWrites", failsafeWrites);}
            if (failsafeCloses > 0) {_context.statManager().addRateData("ntcp.failsafeCloses", failsafeCloses);}
            if (failsafeInvalid > 0) {_context.statManager().addRateData("ntcp.failsafeInvalid", failsafeInvalid);}

        } catch (ClosedSelectorException cse) {} // Ignore
    }

    /**
     *  Process all keys from the last select.
     *  High-frequency path in thread.
     */
    private void processKeys(Set<SelectionKey> selected) {
        for (SelectionKey key : selected) {
            try {
                int ops = key.readyOps();
                boolean accept = (ops & SelectionKey.OP_ACCEPT) != 0;
                boolean connect = (ops & SelectionKey.OP_CONNECT) != 0;
                boolean read = (ops & SelectionKey.OP_READ) != 0;
                boolean write = (ops & SelectionKey.OP_WRITE) != 0;
                if (accept) {
                    _context.statManager().addRateData("ntcp.accept", 1);
                    processAccept(key);
                }
                if (connect) {
                    clearInterest(key, SelectionKey.OP_CONNECT);
                    processConnect(key);
                }
                if (read) {processRead(key);}
                if (write) {processWrite(key);}
            } catch (CancelledKeyException cke) {
                if (_log.shouldDebug()) {_log.debug("Key cancelled");}
            }
        }
    }

    private volatile boolean _needsWakeup = false;
    private long _lastWakeup = 0;
    private long _lastWriteWakeup = 0;
    private long _lastReadWakeup = 0;
    private static final int WAKEUP_COOLDOWN = 100; // ms

    /**
     *  Called by the connection when it has data ready to write (after bw allocation).
     *  Only wakeup if new.
     */
    public void wantsWrite(NTCPConnection con) {
        if (con.isClosed()) return;
        if (_wantsWrite.add(con)) {
            long now = System.currentTimeMillis();
            int cooldown = calculateCooldown();
            if (_selectorIsBlocked && now - _lastWriteWakeup > cooldown) {
                _selector.wakeup();
                _lastWriteWakeup = now;
            }
        }
    }

    /**
     *  This is only called from NTCPConnection.complete()
     *  if there is more data, which is rare (never?)
     *  so we don't need to check for dups or make _wantsRead a Set.
     */
    public void wantsRead(NTCPConnection con) {
        if (con.isClosed()) return;
        if (_wantsRead.offer(con)) {
            long now = System.currentTimeMillis();
            int cooldown = calculateCooldown();
            if (_selectorIsBlocked && now - _lastReadWakeup > cooldown) {
                _selector.wakeup();
                _lastReadWakeup = now;
            }
        }
    }

    private int calculateCooldown() {
        long now = System.currentTimeMillis();
        int avgLoops = getAvgPumpLoops();
        int cooldown = WAKEUP_COOLDOWN;
        if (avgLoops > 5000) {
            cooldown = WAKEUP_COOLDOWN * 12;
        } else if (avgLoops > 3000) {
            cooldown = WAKEUP_COOLDOWN * 10;
        } else if (avgLoops > 2000) {
            cooldown = WAKEUP_COOLDOWN * 8;
        } else if (avgLoops > 1000) {
            cooldown = WAKEUP_COOLDOWN * 6;
        } else if (avgLoops > 1000) {
            cooldown = WAKEUP_COOLDOWN * 5;
        }
        return cooldown;
    }

    /**
     *  High-frequency path in thread.
     */
    public static ByteBuffer acquireBuf() {
        ByteBuffer buf = _bufferCache.acquire();
        if (buf == null) {
            return _useDirect ? ByteBuffer.allocateDirect(BUF_SIZE) : ByteBuffer.allocate(BUF_SIZE);
        }
        buf.clear(); // ensure clean state
        return buf;
    }

    /**
     *  Return a read buffer to the pool.
     *  These buffers must be from acquireBuf(), i.e. capacity() == BUF_SIZE.
     *  High-frequency path in thread.
     */
    public static void releaseBuf(ByteBuffer buf) {
        if (buf.capacity() < BUF_SIZE) { // double check
            I2PAppContext.getGlobalContext().logManager().getLog(EventPumper.class).error("Bad size " + buf.capacity(), new Exception());
            return;
        }
        buf.clear();
        _bufferCache.release(buf);
    }

    private void processAccept(SelectionKey key) {
        ServerSocketChannel servChan = (ServerSocketChannel)key.attachment();
        boolean shouldWarn = _log.shouldWarn();
        boolean shouldInfo = _log.shouldInfo();
        try {
            SocketChannel chan = servChan.accept();
            // don't throw an NPE if the connect is gone again
            if (chan == null) {return;}
            chan.configureBlocking(false);

            byte[] ip = chan.socket().getInetAddress().getAddress();
            String ba = Addresses.toString(ip).replace("/", "");
            boolean isBanned = _context.blocklist().isBlocklisted(ip);
            if (isBanned) {
                if (shouldInfo) {
                    _log.info("Refusing Session Request from blocklisted IP address " + ba);
                }
                try {chan.close();}
                catch (IOException ioe) {}
                return;
            }
            if (!_context.commSystem().isExemptIncoming(Addresses.toCanonicalString(ba))) {
                if (!_transport.allowConnection()) {
                    if (shouldWarn) {
                        _log.warn("Refusing Session Request from: " + ba + " -> NTCP connection limit reached");
                    }
                    try {chan.close();}
                    catch (IOException ioe) {}
                    return;
                }

                int count = _blockedIPs.count(ba);
                if (count > 0) {
                    count = _blockedIPs.increment(ba);
                    if (shouldInfo) {
                        _log.info("Blocking NTCP connection attempt from" + (isBanned ? "banned IP address" : "") +
                                  ": " + ba + " (Count: " + count + ")");
                    }
                    if (count >= 30 && shouldWarn) {
                        _log.warn("WARNING! " +  (isBanned ? "Banned " : "")  + "IP Address " + ba +
                                  " is making excessive inbound NTCP connection attempts (Count: " + count + ")");
                    }
                    try {chan.close();}
                    catch (IOException ioe) {}
                    return;
                }

                if (!shouldAllowInboundEstablishment()) {
                    try {chan.close();}
                    catch (IOException ioe) {}
                    return;
                }
            }

            _context.statManager().addRateData("ntcp.inboundConn", 1);

            if (shouldSetKeepAlive(chan)) {chan.socket().setKeepAlive(true);}
            if (_nodelay) {chan.socket().setTcpNoDelay(true);}

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
     * Should we allow another inbound establishment?
     * Used to throttle outbound hole punches.
     * @since 0.9.2
     */
    private boolean shouldAllowInboundEstablishment() {
        RateStat rs = _context.statManager().getRate("ntcp.inboundConn");
        if (rs == null) {return true;}
        Rate r = rs.getRate(60*1000);
        if (r == null) {return true;}
        int last;
        long periodStart;
        RateAverages ra = RateAverages.getTemp();
        synchronized(r) {
            last = (int) r.getLastEventCount();
            periodStart = r.getLastCoalesceDate();
            r.computeAverages(ra, true);
        }
        // compare incoming conns per ms, min of 1 per second or 60/minute
        if (last < 15) {last = 15;}
        int total = (int) ra.getTotalEventCount();
        int current = total - last;
        if (current <= 0) {return true;}
        // getLastEventCount() is normalized to the rate, so we use the canonical period
        int lastPeriod = 60*1000;
        double avg = ra.getAverage();
        int currentTime = (int) (_context.clock().now() - periodStart);
        if (currentTime <= 5*1000) {return true;}
        // compare incoming conns per ms
        // both of these are scaled by actual period in coalesce
        float lastRate = last / (float) lastPeriod;
        float currentRate = (float) (current / (double) currentTime);
        //float factor = _transport.haveCapacity(95) ? 1.05f : 0.95f;
        float factor = _transport.haveCapacity(95) ? getThrottleFactor() : 0.95f;
        float minThresh = factor * lastRate;
        int maxConnections = _transport.getMaxConnections();
        int currentConnections = _transport.countPeers();
        if (currentRate > minThresh * 5 / 3 && (currentConnections > (maxConnections * 2 / 3))) {
            // chance in 128
            // max out at about 25% over the last rate
            int probAccept = Math.max(1, ((int) (4 * 128 * currentRate / minThresh)) - 512);
            int percent = probAccept > 128 ? 100 : (probAccept / 128) * 100;
            if (probAccept >= 128 || _context.random().nextInt(128) < probAccept) {
                if (_log.shouldWarn()) {
                    _log.warn("Dropping incoming TCP connection (" + (percent >= 1 ? percent : "1") + "% chance)" +
                              " -> Previous/current connections per minute: " + last + " / " + (int) (currentRate * 60*1000));
                }
                return false;
            }
        }
        return true;
    }

    private void processConnect(SelectionKey key) {
        final NTCPConnection con = (NTCPConnection)key.attachment();
        final SocketChannel chan = con.getChannel();
        try {
            boolean connected = chan.finishConnect();
            if (_log.shouldDebug())
                _log.debug("Processing connect for " + con + ": connected? " + connected);
            if (connected) {
                if (shouldSetKeepAlive(chan))
                    chan.socket().setKeepAlive(true);
                if (_nodelay)
                    chan.socket().setTcpNoDelay(true);
                // key was already set when the channel was created, why do it again here?
                con.setKey(key);
                con.outboundConnected();
                _context.statManager().addRateData("ntcp.connectSuccessful", 1);
            } else {
                con.closeOnTimeout("Connect failed (10s timeout exceeded) -> Marking unreachable", null);
                _transport.markUnreachable(con.getRemotePeer().calculateHash());
                _context.statManager().addRateData("ntcp.connectFailedTimeout", 1);
            }
            // this is the usual failure path for a timeout or connect refused
        } catch (IOException ioe) {
            if (_log.shouldDebug()) {
                _log.debug("[NTCP2] Failed outbound connection to " + con.getRemotePeer(), ioe);
            } else if (_log.shouldWarn()) {
                _log.warn("[NTCP2] Failed outbound connection to " + con.getRemotePeer());
            }
            con.closeOnTimeout("Connect failed: " + ioe.getMessage(), ioe);
            RouterIdentity remote = con.getRemotePeer();
            if (remote != null) {
                _failedOutboundAttempts.put(remote.calculateHash(), System.currentTimeMillis());
            }
            _transport.markUnreachable(con.getRemotePeer().calculateHash());
            _context.statManager().addRateData("ntcp.connectFailedTimeoutIOE", 1);
        } catch (NoConnectionPendingException ncpe) { // ignore
            if (_log.shouldWarn()) {_log.warn("Error connecting on " + con, ncpe);}
        }
    }

    /**
     *  @since 0.9.20
     */
    private boolean shouldSetKeepAlive(SocketChannel chan) {
        if (chan.socket().getInetAddress() instanceof Inet6Address) {return false;}
        Status status = _context.commSystem().getStatus();
        return !STATUS_OK.contains(status);
    }

    /**
     * Handles reading from the channel associated with the given SelectionKey.
     * <p>
     * This method assumes OP_READ is set before calling and disables interest when
     * no more inbound data can be read, e.g., due to bandwidth throttling or connection closure.
     * It is a high-frequency method invoked within an IO thread.
     * <p>
     * The method reads available data into buffers repeatedly until no more data is currently available
     * or an error/EOF occurs. It manages connection state, bandwidth requests, and logging.
     *
     * @param key the SelectionKey representing the channel ready for read
     */
    private void processRead(SelectionKey key) {
        final NTCPConnection con = (NTCPConnection) key.attachment();
        final SocketChannel chan = con.getChannel();
        ByteBuffer buf = null;
        boolean shouldDebug = _log.shouldDebug();
        boolean shouldInfo = _log.shouldInfo();
        int logCounter = 0;

        try {
            while (true) {
                buf = acquireBuf();  // Acquire buffer once per iteration

                int totalRead = 0;
                int readCount = 0;
                int bytesRead;
                logCounter++;

                // Read as many times as possible until no more data immediately available
                while ((bytesRead = chan.read(buf)) > 0) {
                    totalRead += bytesRead;
                    readCount++;
                }

                // bytesRead < 0 means EOF - if nothing read before, mark EOF
                if (bytesRead < 0 && totalRead == 0) totalRead = bytesRead;

                if (shouldDebug && totalRead != 0) {
                    _log.debug("Read " + totalRead + " bytes " + con);
                }

                if (totalRead < 0) {
                    // EOF handling - log and block IP for inbound connections with zero msg received
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
                    } else if (shouldDebug) {_log.debug("EOF on " + con);}
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
                        if (shouldInfo) {_log.info("Fail safe zero read close " + con);}
                        con.close();
                    } else {
                        _context.statManager().addRateData("ntcp.zeroRead", zeroReadCount);
                        if (shouldDebug) {
                            _log.debug("Nothing to read for " + con + ", remaining interested (Count: " + zeroReadCount + ")");
                        }
                    }
                    break;
                }

                // Data read successfully, clear zero-read count
                con.clearZeroRead();

                // Flip buffer for reading downstream
                ((Buffer) buf).flip();

                // Request bandwidth for inbound data
                FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestInbound(totalRead, "NTCP read");

                if (req.getPendingRequested() > 0) {
                    // Bandwidth throttling active - clear read interest and queue the buffer
                    clearInterest(key, SelectionKey.OP_READ);
                    if (logCounter / 10 == 0) {
                        _context.statManager().addRateData("ntcp.queuedRecv", totalRead);
                    }
                    con.queuedRecv(buf, req);
                    break;
                } else {
                    // No throttling - deliver buffer directly and keep read interest
                    con.recv(buf);
                    if (logCounter / 10 == 0) {
                        _context.statManager().addRateData("ntcp.read", totalRead);
                    }
                    // EOF detected previously? Close connection
                    if (bytesRead < 0) {
                        con.close();
                        break;
                    }

                    // Continue reading if buffer filled entirely (may be more data)
                    if (buf.hasRemaining()) {
                        break;
                    }
                }
            }
        } catch (CancelledKeyException cke) {
            if (buf != null) releaseBuf(buf);
            if (shouldInfo) _log.info("Error reading on " + con + "\n* " + cke.getMessage());
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
                                  + "\n* IO Error: " + ioe.getMessage());
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
            if (shouldInfo) {_log.info("Error reading: " + con, nyce);}
        }
    }

    /**
     *  OP_WRITE will always be set before this is called.
     *  This method will disable the interest if no more writes remain.
     *  High-frequency path in thread.
     */
    private void processWrite(SelectionKey key) {
        final NTCPConnection con = (NTCPConnection)key.attachment();
        processWrite(con, key);
    }

    /**
     *  Asynchronous write all buffers to the channel.
     *  This method will disable the interest if no more writes remain.
     *  If this returns false, caller MUST call wantsWrite(con)
     *
     *  @param key non-null
     *  @return true if all buffers were completely written, false if buffers remain
     *  @since 0.9.53
     */
    public boolean processWrite(final NTCPConnection con, final SelectionKey key) {
        boolean rv = false;
        final SocketChannel chan = con.getChannel();

        if (!key.isValid()) {
            con.close();
            key.cancel();
            return true;
        }

        if (con.isBanned()) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping write on banned connection: " + con);
            }
            return true;
        }

        if (!con.isEstablished() && con.getUptime() > NTCPTransport.ESTABLISH_TIMEOUT) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping write on timed-out unestablished connection: " + con);
            }
            return true;
        }

        try {
            synchronized(con.getWriteLock()) {
                while (true) {
                    ByteBuffer buf = con.getNextWriteBuf();
                    if (buf != null) {
                        if (buf.remaining() <= 0) {
                            con.removeWriteBuf(buf);
                            continue;
                        }
                        int written = chan.write(buf);
                        //totalWritten += written;
                        if (written == 0) {
                            if ((buf.remaining() > 0) || (!con.isWriteBufEmpty())) {}
                            else {rv = true;}
                            break;
                        } else if (buf.remaining() > 0) {break;}
                        else {con.removeWriteBuf(buf);}
                    } else {
                        // Nothing more to write
                        if (key.isValid()) {rv = true;}
                        break;
                    }
                }
                if (rv) {clearInterest(key, SelectionKey.OP_WRITE);}
                else {setInterest(key, SelectionKey.OP_WRITE);}
            }
        // catch and close outside the write lock to avoid deadlocks in NTCPCon.locked_close()
        } catch (CancelledKeyException cke) {
            if (_log.shouldInfo()) {_log.info("Error writing on: " + con + "\n* Reason: Socket channel closed or selection key cancelled");}
            _context.statManager().addRateData("ntcp.writeError", 1);
            con.close();
            rv = true;
        } catch (IOException ioe) {
            if (_log.shouldInfo()) {_log.info("Error writing on: " + con + "\n* Reason: IO Error");}
            _context.statManager().addRateData("ntcp.writeError", 1);
            con.close();
            rv = true;
        }

        if (rv) {clearInterest(key, SelectionKey.OP_WRITE);}

        return rv;
    }

    private static final int MAX_BATCH = SystemVersion.isSlow() ? 32 : 64; // max items per queue per invocation to limit work

    /**
     * Processes delayed events from multiple queues by updating selector interest ops.
     * Limits batch size to avoid long blocking, handles exceptions, and performs periodic maintenance.
     */
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

    /**
     * Processes queued read requests, updating SelectionKey to OP_READ if valid.
     * Closes connections with invalid or cancelled keys.
     *
     * @param debug whether debug logging is enabled
     * @param warn whether warning logging is enabled
     */
    private void processReadRequests(boolean debug, boolean warn) {
        NTCPConnection con;
        int count = 0;
        while (count++ < MAX_BATCH && (con = _wantsRead.poll()) != null) {
            if (con.isClosed()) continue;
            SelectionKey key = con.getKey();
            if (!isKeyValid(key)) {
                logAndClose(debug, "[NTCP2] Dropping connection with invalid key during read registration: ", con);
                con.close();
                continue;
            }
            try {
                setInterest(key, SelectionKey.OP_READ);
            } catch (CancelledKeyException cke) {
                handleException(debug, warn, cke, "[NTCP2] Cancelled key during read registration for connection to ", con);
                con.close();
            } catch (IllegalArgumentException iae) {
                handleException(debug, warn, iae, "[NTCP2] Invalid key for read registration (", con);
                con.close();
            }
        }
    }

    /**
     * Processes queued write requests, updating SelectionKey to OP_WRITE if valid.
     * Closes connections with invalid or cancelled keys.
     *
     * @param debug whether debug logging is enabled
     * @param warn whether warning logging is enabled
     */
    private void processWriteRequests(boolean debug, boolean warn) {
        NTCPConnection con;
        int count = 0;
        while (count++ < MAX_BATCH && (con = _wantsWrite.poll()) != null) {
            if (con.isClosed()) continue;
            SelectionKey key = con.getKey();
            if (!isKeyValid(key)) {
                logAndClose(debug, "[NTCP2] Dropping connection with invalid key during write registration: ", con);
                con.close();
                continue;
            }
            try {
                setInterest(key, SelectionKey.OP_WRITE);
            } catch (CancelledKeyException cke) {
                handleException(debug, warn, cke, "[NTCP2] Cancelled key during write registration for connection to ", con);
                con.close();
            } catch (IllegalArgumentException iae) {
                handleException(debug, warn, iae, "[NTCP2] Invalid key for write registration (", con);
                con.close();
            }
        }
    }

    /**
     * Processes queued server socket channels for registration with selector.
     * Logs errors on failure to register.
     *
     * @param debug whether debug logging is enabled
     * @param warn whether warning logging is enabled
     */
    private void processServerSocketRegistrations(boolean debug, boolean warn) {
        ServerSocketChannel chan;
        int count = 0;
        while (count++ < MAX_BATCH && (chan = _wantsRegister.poll()) != null) {
            try {
                SelectionKey key = chan.register(_selector, SelectionKey.OP_ACCEPT);
                key.attach(chan);
            } catch (ClosedChannelException cce) {
                if (debug) {
                    _log.debug("[NTCP2] Error registering server socket", cce);
                } else if (warn) {
                    _log.warn("[NTCP2] Error registering server socket: " + safeMessage(cce));
                }
            }
        }
    }

    /**
     * Processes queued outbound connections for registration and attempts to connect.
     * Handles exceptions and closes connections on failure.
     *
     * @param debug whether debug logging is enabled
     * @param warn whether warning logging is enabled
     */
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

                InetSocketAddress saddr = new InetSocketAddress(
                    InetAddress.getByAddress(naddr.getIP()), naddr.getPort()
                );

                if (schan.connect(saddr)) {
                    setInterest(key, SelectionKey.OP_READ);
                    processConnect(key);
                }

            } catch (IOException | UnresolvedAddressException e) {
                if (debug) {
                    _log.debug("[NTCP2] Failed outbound connection to " + con.getRemotePeer(), e);
                } else if (warn) {
                    _log.warn("[NTCP2] Failed outbound connection to " + con.getRemotePeer());
                }
                con.closeOnTimeout("Connect failed: " + e.getMessage(), e);
                _transport.markUnreachable(con.getRemotePeer().calculateHash());
                _context.statManager().addRateData("ntcp.connectFailedTimeoutIOE", 1);
                RouterIdentity remote = con.getRemotePeer();
                if (remote != null) {
                    _failedOutboundAttempts.put(remote.calculateHash(), System.currentTimeMillis());
                }
            } catch (CancelledKeyException cke) {
                handleException(debug, warn, cke, "[NTCP2] Cancelled key during connect to ", con);
                con.close();
            } catch (Exception e) {
                if (debug) {
                    _log.debug("[NTCP2] Unexpected error during outbound registration for " + con.getRemotePeer(), e);
                } else if (warn) {
                    _log.warn("[NTCP2] Unexpected error during outbound registration for " + con.getRemotePeer());
                }
                con.close();
            }
        }
    }

    public int getAvgPumpLoops() {
        if (_context == null) {return 0;}
        RateStat rs = _context.statManager().getRate("ntcp.pumperLoopsPerSecond");
        Rate avgLoops = rs.getRate(60*1000);
        int avgLoopsPerSecond = (int) avgLoops.getAvgOrLifetimeAvg();
        return avgLoopsPerSecond;
    }

    /**
     * Checks if the given SelectionKey is non-null and currently valid.
     *
     * @param key the SelectionKey to validate
     * @return true if key is non-null and valid, false otherwise
     */
    private boolean isKeyValid(SelectionKey key) {
        return key != null && key.isValid();
    }

    /**
     * Logs a debug message about dropping a connection and closes it.
     *
     * @param debug whether debug logging is enabled
     * @param msg the message prefix
     * @param con the NTCPConnection to log and close
     */
    private void logAndClose(boolean debug, String msg, NTCPConnection con) {
        if (debug) {_log.debug(msg + con);}
        con.close();
    }

    /**
     * Logs an exception with either debug or warning level including connection info.
     *
     * @param debug whether debug logging is enabled
     * @param warn whether warning logging is enabled
     * @param e the Exception to log
     * @param msgStart the message prefix
     * @param con the NTCPConnection related to the exception
     */
    private void handleException(boolean debug, boolean warn, Exception e, String msgStart, NTCPConnection con) {
        if (debug) {
            _log.debug(msgStart + con.getRemotePeer(), e);
        } else if (warn) {
            _log.warn(msgStart + con.getRemotePeer() + (e.getMessage() != null ? " -> " + e.getMessage() : ""));
        }
    }

    /**
     * Returns a safe string message from an Exception, avoiding null.
     *
     * @param e the Exception
     * @return the message or empty string if null
     */
    private String safeMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : "";
    }

    /**
     *  Temp. block inbound from this IP
     *
     *  @since 0.9.58
     */
    public void blockIP(byte[] ip) {
        if (ip == null) {return;}
        String ba = Addresses.toString(ip);
        _blockedIPs.increment(ba);
    }

    private long _lastExpired;

    private void expireTimedOut() {_transport.expireTimedOut();}

    public long getIdleTimeout() {return _expireIdleWriteTime;}

    /**
     *  Warning - caller should catch unchecked CancelledKeyException
     *
     *  @throws CancelledKeyException which is unchecked
     *  @since 0.9.53
     */
    public static void setInterest(SelectionKey key, int op) throws CancelledKeyException {
        if (key == null || !key.isValid()) return;
        int old = key.interestOps();
        NTCPConnection con = (NTCPConnection) key.attachment();
        if (con != null && con.shouldSetInterest(op)) {
            key.interestOps(old | op);
            con.updateInterestOps(old | op);
        }
    }

    /**
     *  Warning - caller should catch unchecked CancelledKeyException
     *
     *  @throws CancelledKeyException which is unchecked
     *  @since 0.9.53
     */
    public static void clearInterest(SelectionKey key, int op) throws CancelledKeyException {
        if (key == null || !key.isValid()) return;
        int old = key.interestOps();
        NTCPConnection con = (NTCPConnection) key.attachment();
        if (con != null && !con.shouldSetInterest(op)) {
            key.interestOps(old & ~op);
            con.updateInterestOps(old & ~op);
        }
    }

}