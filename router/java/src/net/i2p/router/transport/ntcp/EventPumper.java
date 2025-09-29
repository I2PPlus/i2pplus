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
import java.util.Queue;
import java.util.Set;
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
    //private final Set<NTCPConnection> _wantsWrite = new ConcurrentHashSet<NTCPConnection>(32);
    private final Set<NTCPConnection> _wantsWrite = new ConcurrentHashSet<NTCPConnection>(64);
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

    /**
     *  This probably doesn't need to be bigger than the largest typical
     *  message, which is a 5-slot VTBM (~2700 bytes).
     *  The occasional larger message can use multiple buffers.
     */
    private static final int BUF_SIZE = SystemVersion.isSlow() ? 8*1024 : 16*1024;

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
//    private static final long FAILSAFE_ITERATION_FREQ = 2*1000l;
//    private static final int FAILSAFE_LOOP_COUNT = 512;
//    private static final long SELECTOR_LOOP_DELAY = 200;
    private static final int FAILSAFE_ITERATION_FREQ = 30*1000;
    private static final int FAILSAFE_LOOP_COUNT = SystemVersion.isSlow() ? 512 : 1024;
    private static final long SELECTOR_LOOP_DELAY = SystemVersion.isSlow() ? 200 : 100;
    private static final long BLOCKED_IP_FREQ = 15*60*1000;

    /** tunnel test now disabled, but this should be long enough to allow an active tunnel to get started */
    private static final long MIN_EXPIRE_IDLE_TIME = 120*1000l;
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

//    private static final int MIN_MINB = 4;
//    private static final int MAX_MINB = 12;
    private static final int MIN_MINB = SystemVersion.isSlow() ? 8 : 64;
    private static final int MAX_MINB = SystemVersion.isSlow() ? 32 : 512;
    public static final String PROP_MAX_MINB = "i2np.ntcp.eventPumperMaxBuffers";
    private static final int MIN_BUFS;
    static {
        long maxMemory = SystemVersion.getMaxMemory();
        boolean isSlow = SystemVersion.isSlow();
        //MIN_BUFS = (int) Math.max(MIN_MINB, Math.min(MAX_MINB, 1 + (maxMemory / (16*1024*1024))));
        MIN_BUFS = (int) Math.max(MIN_MINB, Math.max(MAX_MINB, 1 + (maxMemory / (4*1024*1024))));
    }

    private static final float DEFAULT_THROTTLE_FACTOR = SystemVersion.isSlow() ? 1.1f : 1.5f;
    private static final String PROP_THROTTLE_FACTOR = "router.throttleFactor";

    private static final TryCache<ByteBuffer> _bufferCache = new TryCache<>(new BufferFactory(), MIN_BUFS);

    private static final Set<Status> STATUS_OK =
        EnumSet.of(Status.OK, Status.IPV4_OK_IPV6_UNKNOWN, Status.IPV4_OK_IPV6_FIREWALLED);

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
            t.setPriority(I2PThread.MAX_PRIORITY - 1);
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
        _selector.wakeup();
    }

    /**
     *  Outbound
     */
    public void registerConnect(NTCPConnection con) {
        if (_log.shouldDebug())
            _log.debug("Registering " + con + "...");
        _context.statManager().addRateData("ntcp.registerConnect", 1);
        _wantsConRegister.offer(con);
        _selector.wakeup();
    }

    /**
     * Ratio of current connections/min vs previous before throttler activates
     *
     * @since 0.9.58+
     */
    private float getThrottleFactor() {
        return _context.getProperty(PROP_THROTTLE_FACTOR, DEFAULT_THROTTLE_FACTOR);
    }

    /**
     *  The selector loop.
     *  On high-bandwidth routers, this is the thread with the highest CPU usage, so
     *  take care to minimize overhead and unnecessary debugging stuff.
     */
    public void run() {
        int loopCount = 0;
        int loopCountSinceLastRate = 0;
        int failsafeLoopCount = FAILSAFE_LOOP_COUNT;
        long lastFailsafeIteration = System.nanoTime();
        long lastBlockedIPClear = lastFailsafeIteration;
        long nanoNow = System.nanoTime();
        long now = nanoNow / 1_000_000L;
        long lastLoopRateUpdate = now;
        long lastKeySetUpdate = now;
        boolean shouldDebug = _log.shouldDebug();

        // Background executor for failsafe loop
        ScheduledExecutorService background = new ScheduledThreadPoolExecutor(1);
        background.scheduleAtFixedRate(this::doFailsafeCheck, 5, 30, TimeUnit.SECONDS);

        try {
            while (_alive && _selector.isOpen()) {
                try {
                    _needsWakeup = false;
                    loopCount++;
                    loopCountSinceLastRate++;

                    try {
                        // Dynamic selector delay
                        long delay = SELECTOR_LOOP_DELAY;
                        if (_wantsWrite.isEmpty() && _wantsRead.isEmpty()) {
                            delay = 1000; // idle
                        }
                        int count = _selector.select(delay);
                        if (count > 0) {
                            Set<SelectionKey> selected = _selector.selectedKeys();
                            processKeys(selected);
                            selected.clear();
                        }
                        runDelayedEvents();
                    } catch (ClosedSelectorException cse) {
                        continue;
                    } catch (IOException ioe) {
                        if (shouldDebug) {_log.warn("Error selecting", ioe);}
                        else if (_log.shouldWarn()) {_log.warn("Error selecting -> " + ioe.getMessage());}
                        continue;
                    } catch (CancelledKeyException cke) {
                        if (shouldDebug) {_log.warn("Error selecting", cke);}
                        else if (_log.shouldWarn()) {_log.warn("Error selecting -> " + cke.getMessage());}
                        continue;
                    }

                    nanoNow = System.nanoTime();
                    now = nanoNow / 1_000_000L;

                    // Update stat every second
                    if (now - lastLoopRateUpdate >= 1000) {
                        _context.statManager().addRateData("ntcp.pumperLoopsPerSecond", loopCountSinceLastRate);
                        loopCountSinceLastRate = 0;
                        lastLoopRateUpdate = now;
                    }

                    // Update keyset size stat every 5s
                    if (now - lastKeySetUpdate >= 5000) {
                        Set<SelectionKey> all = _selector.keys();
                        _context.statManager().addRateData("ntcp.pumperKeySetSize", all.size());
                        lastKeySetUpdate = now;
                    }

                    // Throttle CPU if needed
                    int cpuLoadAvg = SystemVersion.getCPULoadAvg();
                    int pause = SystemVersion.isSlow() || cpuLoadAvg > 95 ? 30 : 10;
                    if ((loopCount % failsafeLoopCount) == failsafeLoopCount - 1) {
                        if (shouldDebug) {
                            _log.debug("EventPumper throttle " + loopCount + " loops in " +
                                      (now - lastFailsafeIteration) + " ms");
                        }
                        _context.statManager().addRateData("ntcp.failsafeThrottle", 1);
                        try {
                            Thread.sleep(pause);
                        } catch (InterruptedException ie) {}
                    }

                    // Clear blocked IPs periodically
                    if (now - lastBlockedIPClear >= BLOCKED_IP_FREQ / 1000L) {
                        _blockedIPs.clear();
                        lastBlockedIPClear = now;
                    }

                } catch (RuntimeException re) {
                    _log.error("Error in EventPumper", re);
                }
            }
        } finally {
            background.shutdownNow();
        }

        // Clean up
        try {
            if (_selector.isOpen()) {
                if (shouldDebug) {_log.debug("Closing down EventPumper with selection keys remaining...");}
                Set<SelectionKey> keys = _selector.keys();
                for (SelectionKey key : keys) {
                    try {
                        Object att = key.attachment();
                        if (att instanceof ServerSocketChannel) {
                            ServerSocketChannel chan = (ServerSocketChannel) att;
                            chan.close();
                            key.cancel();
                        } else if (att instanceof NTCPConnection) {
                            NTCPConnection con = (NTCPConnection) att;
                            con.close();
                            key.cancel();
                        }
                    } catch (IOException ke) {
                        _log.error("Error closing key " + key + " on EventPumper shutdown", ke);
                    }
                }
                _selector.close();
            } else {
                if (shouldDebug) {
                    _log.debug("Closing down EventPumper with no selection keys remaining...");
                }
            }
        } catch (IOException e) {
            _log.error("Error closing keys on EventPumper shutdown", e);
        }

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
                            _log.info("Removing invalid key for: " + con);
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
                            _log.info("Possible early disconnect for: " + con);
                        }
                    } else {
                        expire = _expireIdleWriteTime;
                    }

                    // Use last active time instead of checking send/receive separately
                    if (con.getLastActiveTime() + expire < now) {
                        con.sendTerminationAndClose();
                        if (_log.shouldInfo()) {
                            _log.info("Failsafe or expire close for: " + con);
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
                } catch (CancelledKeyException cke) {
                    // Ignore
                }
            }

            if (failsafeWrites > 0) {
                _context.statManager().addRateData("ntcp.failsafeWrites", failsafeWrites);
            }
            if (failsafeCloses > 0) {
                _context.statManager().addRateData("ntcp.failsafeCloses", failsafeCloses);
            }
            if (failsafeInvalid > 0) {
                _context.statManager().addRateData("ntcp.failsafeInvalid", failsafeInvalid);
            }

        } catch (ClosedSelectorException cse) {
            // Ignore
        }
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
    private static final long WAKEUP_COOLDOWN = 10; // ms

    /**
     *  Called by the connection when it has data ready to write (after bw allocation).
     *  Only wakeup if new.
     */
    public void wantsWrite(NTCPConnection con) {
        if (_wantsWrite.add(con)) {
           maybeWakeup();
        }
    }

    private void maybeWakeup() {
        long now = System.currentTimeMillis();
        if (!_needsWakeup || now - _lastWakeup > WAKEUP_COOLDOWN) {
            if (_log.shouldDebug()) {
                if (_needsWakeup && now - _lastWakeup <= WAKEUP_COOLDOWN) {
                    _log.debug("Throttling selector.wakeup()");
                }
            }
            _needsWakeup = true;
            _selector.wakeup();
            _lastWakeup = now;
        }
    }

    /**
     *  This is only called from NTCPConnection.complete()
     *  if there is more data, which is rare (never?)
     *  so we don't need to check for dups or make _wantsRead a Set.
     */
    public void wantsRead(NTCPConnection con) {
        _wantsRead.offer(con);
        _selector.wakeup();
    }

    /**
     *  High-frequency path in thread.
     */
    public static ByteBuffer acquireBuf() {return _bufferCache.acquire();}

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
        try {
            SocketChannel chan = servChan.accept();
            // don't throw an NPE if the connect is gone again
            if(chan == null) {return;}
            chan.configureBlocking(false);

            byte[] ip = chan.socket().getInetAddress().getAddress();
            String ba = Addresses.toString(ip).replace("/", "");
            boolean isBanned = _context.blocklist().isBlocklisted(ip);
            if (isBanned) {
                if (_log.shouldLog(Log.WARN)) {
                    _log.warn("Refusing Session Request from blocklisted IP address " + ba);
                }
                try {chan.close();}
                catch (IOException ioe) {}
                return;
            }
            if (!_context.commSystem().isExemptIncoming(Addresses.toCanonicalString(ba))) {
                if (!_transport.allowConnection()) {
                    if (_log.shouldWarn()) {
                        _log.warn("Refusing Session Request from: " + ba + " -> NTCP connection limit reached");
                    }
                    try {chan.close();}
                    catch (IOException ioe) {}
                    return;
                }

                int count = _blockedIPs.count(ba);
                if (count > 0) {
                    count = _blockedIPs.increment(ba);
                    if (_log.shouldWarn()) {
                        _log.warn("Blocking NTCP connection attempt from" + (isBanned ? "banned IP address" : "") +
                                  ": " + ba + " (Count: " + count + ")");
                    }
                    if (count >= 30 && _log.shouldWarn()) {
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
        } catch (IOException ioe) { // this is the usual failure path for a timeout or connect refused
            if (_log.shouldInfo()) {
                _log.info("Failed outbound " + con + " (" + ioe.getMessage() + ")");
            }
            con.closeOnTimeout("Connect failed (10s timeout exceeded or connection refused) -> Marking unreachable", ioe);
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
     *  OP_READ will always be set before this is called.
     *  This method will disable the interest if no more reads remain because of inbound bandwidth throttling.
     *  High-frequency path in thread.
     */
    private void processRead(SelectionKey key) {
        final NTCPConnection con = (NTCPConnection)key.attachment();
        final SocketChannel chan = con.getChannel();
        ByteBuffer buf = null;
        try {
            while (true) {
                buf = acquireBuf();
                int read = 0;
                int readThisTime;
                int readCount = 0;
                while ((readThisTime = chan.read(buf)) > 0)  {
                    read += readThisTime;
                    readCount++;
                }
                if (readThisTime < 0 && read == 0) {read = readThisTime;}
                if (_log.shouldDebug()) {
                    _log.debug("Read " + read + " bytes total in " + readCount + " times from " + con);
                }
                if (read < 0) {
                    if (con.isInbound() && con.getMessagesReceived() <= 0) {
                        InetAddress addr = chan.socket().getInetAddress();
                        int count;
                        if (addr != null) {
                            byte[] ip = addr.getAddress();
                            String ba = Addresses.toString(ip).replace("/", "");
                            count = _blockedIPs.increment(ba);
                            if (_log.shouldWarn()) {
                                _log.warn("EOF on Inbound connection before receiving any data " +
                                          "\n* Blocking IP address: " + ba + (count > 1 ? " (Count: " + count + ")" : ""));
                            }
                        } else {
                            count = 1;
                            if (_log.shouldWarn()) {
                                _log.warn("EOF on Inbound connection before receiving any data: " + con);
                            }
                        }
                        _context.statManager().addRateData("ntcp.dropInboundNoMessage", count);
                    } else {
                        if (_log.shouldDebug()) {_log.debug("EOF on " + con);}
                    }
                    con.close();
                    releaseBuf(buf);
                    break;
                }
                if (read == 0) {
                    releaseBuf(buf);
                    int consec = con.gotZeroRead();
                    long now = System.currentTimeMillis();

                    // Only close if multiple zero-reads in a short window
                    if (consec >= 5 && now - con.getLastZeroReadTime() <= 1000) {
                        _context.statManager().addRateData("ntcp.zeroReadDrop", 1);
                        if (_log.shouldWarn()) {
                            _log.warn("Fail safe zero read close " + con);
                        }
                        con.close();
                    } else {
                        _context.statManager().addRateData("ntcp.zeroRead", consec);
                        if (_log.shouldInfo()) {
                            _log.info("Nothing to read for " + con + ", but remaining interested (count: " + consec + ")");
                        }
                    }
                    break;
                }

                // Process the data received
                // clear counter for workaround above
                con.clearZeroRead();
                // go around again if we filled the buffer (so we can read more)
                boolean keepReading = !buf.hasRemaining();
                // ZERO COPY. The buffer will be returned in Reader.processRead()
                // not ByteBuffer to avoid Java 8/9 issues with flip()
                ((Buffer)buf).flip();
                FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestInbound(read, "NTCP read"); //con, buf);
                if (req.getPendingRequested() > 0) {
                    // rare since we generally don't throttle inbound
                    clearInterest(key, SelectionKey.OP_READ);
                    _context.statManager().addRateData("ntcp.queuedRecv", read);
                    con.queuedRecv(buf, req);
                    break;
                } else {
                    // stay interested
                    //key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                    con.recv(buf);
                    _context.statManager().addRateData("ntcp.read", read);
                    if (readThisTime < 0) { // EOF, we're done
                        con.close();
                        break;
                    }
                    if (!keepReading) {break;}
                }
            } // while true
        } catch (CancelledKeyException cke) {
            if (buf != null) {releaseBuf(buf);}
            if (_log.shouldWarn()) _log.warn("Error reading on " + con + "\n* " + cke.getMessage());
            con.close();
            _context.statManager().addRateData("ntcp.readError", 1);
        } catch (IOException ioe) {
            // common, esp. at outbound connect time
            if (buf != null) {releaseBuf(buf);}
            if (con.isInbound() && con.getMessagesReceived() <= 0) {
                byte[] ip = con.getRemoteIP();
                int count;
                if (ip != null) {
                    String ba = Addresses.toString(ip).replace("/", "");
                    count = _blockedIPs.increment(ba);
                    if (_log.shouldWarn()) {
                        _log.warn("Blocking IP address " + ba + (count > 1 ? " (Count: " + count + ")" : "") +
                                  "\n* IO Error: " +  ioe.getMessage());
                    }
                } else {
                    count = 1;
                    if (_log.shouldWarn()) {
                        _log.warn("IO Error on Inbound connection before receiving any data: " + con);
                    }
                }
                _context.statManager().addRateData("ntcp.dropInboundNoMessage", count);
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Error reading: " + con + " (" + ioe.getMessage() + ")");
                }
            }
            if (con.isEstablished()) {_context.statManager().addRateData("ntcp.readError", 1);}
            else {
                // Usually "connection reset by peer", probably a conn limit rejection?
                // although it could be a read failure during the DH handshake
                // Same stat as in processConnect()
                _context.statManager().addRateData("ntcp.connectFailedTimeoutIOE", 1);
                RouterIdentity rem = con.getRemotePeer();
                if (rem != null && !con.isInbound()) {_transport.markUnreachable(rem.calculateHash());}
            }
            con.close();
        } catch (NotYetConnectedException nyce) {
            if (buf != null) {releaseBuf(buf);}
            clearInterest(key, SelectionKey.OP_READ);
            if (_log.shouldWarn()) {_log.warn("Error reading: " + con, nyce);}
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
            if (_log.shouldWarn()) {_log.warn("Error writing on: " + con + "\n* Reason: Cancelled Key Exception");}
            _context.statManager().addRateData("ntcp.writeError", 1);
            con.close();
            rv = true;
        } catch (IOException ioe) {
            if (_log.shouldWarn()) {_log.warn("Error writing on: " + con + "\n* Reason: IO Error");}
            _context.statManager().addRateData("ntcp.writeError", 1);
            con.close();
            rv = true;
        }
        return rv;
    }

    /**
     *  Pull off the 4 _wants* queues and update the interest ops,
     *  which may, according to the javadocs, be a "naive" implementation and block.
     *  High-frequency path in thread.
     */
    private void runDelayedEvents() {
        NTCPConnection con;

        // Process read requests
        while ((con = _wantsRead.poll()) != null) {
            SelectionKey key = con.getKey();
            if (key == null || !key.isValid()) {
                // Connection or key is already invalid; clean up
                con.close();
                continue;
            }
            try {
                setInterest(key, SelectionKey.OP_READ);
            } catch (CancelledKeyException cke) {
                if (_log.shouldWarn()) {
                    _log.warn("runDelayedEvents: Cancelled key during read registration", cke);
                }
                con.close();
            } catch (IllegalArgumentException iae) {
                if (_log.shouldWarn()) {
                    _log.warn("runDelayedEvents: Invalid key for read registration", iae);
                }
                con.close();
            }
        }

        // Process write requests
        if (!_wantsWrite.isEmpty()) {
            for (Iterator<NTCPConnection> iter = _wantsWrite.iterator(); iter.hasNext();) {
                con = iter.next();
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
                    if (_log.shouldWarn()) {
                        _log.warn("runDelayedEvents: Cancelled key during write registration", cke);
                    }
                    con.close();
                } catch (IllegalArgumentException iae) {
                    if (_log.shouldWarn()) {
                        _log.warn("runDelayedEvents: Invalid key for write registration", iae);
                    }
                    con.close();
                }
            }
        }

        // Process server socket registration
        ServerSocketChannel chan;
        while ((chan = _wantsRegister.poll()) != null) {
            try {
                SelectionKey key = chan.register(_selector, SelectionKey.OP_ACCEPT);
                key.attach(chan);
            } catch (ClosedChannelException cce) {
                if (_log.shouldWarn()) {
                    _log.warn("runDelayedEvents: Error registering server socket", cce);
                }
            }
        }

        // Process outbound connection registration
        while ((con = _wantsConRegister.poll()) != null) {
            final SocketChannel schan = con.getChannel();
            try {
                SelectionKey key = schan.register(_selector, SelectionKey.OP_CONNECT);
                key.attach(con);
                con.setKey(key);

                RouterAddress naddr = con.getRemoteAddress();
                if (naddr.getPort() <= 0 || naddr.getIP() == null) {
                    throw new IOException("Invalid NTCP address: " + naddr);
                }

                InetSocketAddress saddr = new InetSocketAddress(
                    InetAddress.getByAddress(naddr.getIP()), naddr.getPort()
                );

                if (saddr != null && schan.connect(saddr)) {
                    setInterest(key, SelectionKey.OP_READ);
                    processConnect(key);
                }
            } catch (IOException | UnresolvedAddressException e) {
                if (_log.shouldWarn()) {
                    _log.warn("runDelayedEvents: Failed outbound connection to " + con, e);
                }
                con.closeOnTimeout("Connect failed: " + e.getMessage(), e);
                _transport.markUnreachable(con.getRemotePeer().calculateHash());
                _context.statManager().addRateData("ntcp.connectFailedTimeoutIOE", 1);
            } catch (CancelledKeyException cke) {
                if (_log.shouldWarn()) {
                    _log.warn("runDelayedEvents: Cancelled key during connect", cke);
                }
                con.close();
            } catch (Exception e) {
                // Catch-all for unexpected errors during registration
                if (_log.shouldWarn()) {
                    _log.warn("runDelayedEvents: Unexpected error during outbound registration", e);
                }
                con.close();
            }
        }

        // Periodic maintenance
        long now = System.currentTimeMillis();
        if (_lastExpired + 1000 <= now) {
            expireTimedOut();
            _lastExpired = now;
        }
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
        int old = key.interestOps();
        NTCPConnection con = (NTCPConnection) key.attachment();
        if (con != null && !con.shouldSetInterest(op)) {
            key.interestOps(old & ~op);
            con.updateInterestOps(old & ~op);
        }
    }

}