package net.i2p.client.streaming.impl;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import net.i2p.I2PAppContext;
import net.i2p.client.streaming.RouterRestartException;
import net.i2p.data.ByteArray;
import net.i2p.data.Destination;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

/**
 * Receive new connection attempts
 *
 * Use a bounded queue to limit the damage from SYN floods,
 * router overload, or a slow client
 *
 * @author zzz modded to use concurrent and bound queue size
 */
class ConnectionHandler {
    private final I2PAppContext _context;
    private final Log _log;
    private final ByteCache _cache = ByteCache.getInstance(32, 4*1024);
    private final ConnectionManager _manager;
    private final LinkedBlockingDeque<Packet> _synQueue;
    private final SimpleTimer2 _timer;
    private volatile boolean _active;
    private volatile int _acceptTimeout;
    private boolean _restartPending;

    /**
     * Max time a received SYN may sit in the accept queue before accept() is
     * expected to pull it. If accept() is delayed (busy or slow hosted server),
     * the queued SYN is reset after this window, so it must be long enough to
     * absorb brief stalls without refusing inbound connections. Tunable via
     * i2p.streaming.acceptTimeout (default: 60000 / 60 seconds). On a congested
     * fabric RTT is commonly 3-10s, so 30s was too short once a SYN actually
     * queued; 60s gives the RTT-aware floor (see {@link #getAdaptiveSynTimeout})
     * headroom to extend on slow-but-alive tunnels without refusing them.
     *
     * @since 0.9.71+
     */
    synchronized void setAcceptTimeout(int ms) { _acceptTimeout = ms; }

    private int getAcceptTimeout() {
        return _context.getProperty("i2p.streaming.acceptTimeout", 60*1000);
    }

    /** Build success fraction below which the tunnel system is considered stressed. */
    static final double SYN_STRESS_THRESHOLD = 0.40;
    /** Minimum SYN accept-queue timeout (ms) kept even on a genuinely stressed, fast fabric. */
    static final int SYN_STRESS_MIN_TIMEOUT = 10 * 1000;
    /** Default RTT scale factor: the adaptive SYN floor is {@code scale * recentRTT} (capped at the
     *  configured timeout) whenever a positive stall is detected.  4 keeps a handshake alive through
     *  roughly four round-trips of server/queue latency, matching live 3-10s-RTT fabrics while still
     *  failing fast on genuinely dead fast tunnels. Tunable via {@link I2PSocketManagerFull#setRttSynTimeoutScale}. */
    static final int SYN_RTT_SCALE_DEFAULT = 4;
    /** Baseline recent-SYN expire rate (percent) that must be exceeded, along with low
     *  build success, before the clamp is armed. 100 disables the clamp. */
    static final int SYN_EXPIRE_THRESHOLD_DEFAULT = 60;
    /** Minimum interval between re-sampling tunnel build success (ms). */
    private static final long SYN_STRESS_SAMPLE_INTERVAL = 10 * 1000;

    /**
     * This is both SYNs and subsequent packets, and with an initial window size of 12,
     * this is a backlog of 5 to 64 Syns, which seems like plenty for now
     * Don't make this too big because the removal by all the TimeoutSyns is O(n**2) - sortof.
     * Read dynamically from config or Tuner — no restart required.
     * @return the max queue size
     */
    private int getMaxQueueSize() {
        // Tuner override takes precedence over config
        int tuner = I2PSocketManagerFull.getMaxSYNQueueSize();
        if (tuner > 0) return tuner;
        int def = SystemVersion.isSlow() ? 128 : 256;
        return _context.getProperty("i2p.streaming.maxQueueSize", def);
    }

    /**
     * Compute the effective SYN accept-queue timeout for an inbound SYN.
     *
     * <p>A queued SYN is reset via {@code TimeoutSyn} after this window, so it
     * bounds how long a client waits for a connection the server never got
     * around to accepting.  A fixed low clamp (e.g. the historical 10s) fails
     * fast on genuinely dead tunnels but also expires slow-but-alive handshakes
     * on a high-latency / congested fabric, which surfaces as empty pages to
     * every client.  So the clamp is made evidence-gated and RTT-aware:
     *
     * <p><b>Gate (#4).</b> The clamp fires only when there is positive evidence of
     * a real stall, not mere latency: {@code buildSuccess} below
     * {@link #SYN_STRESS_THRESHOLD} <b>and</b> the recent SYN expire rate
     * ({@code recentExpireRatePct}) at or above the Tuner threshold
     * ({@link I2PSocketManagerFull#getSynExprExpireThresh()}, default 60).  A
     * server that is draining its SYN queue — even on a slow tunnel — is treated
     * as healthy and keeps the full configured window.
     *
     * <p><b>RTT-aware floor (#2).</b> When the clamp does fire, the window is not
     * a flat constant: the floor rises with the sampled round-trip time
     * {@code rttMs}.  On a fast fabric the floor is {@link #SYN_STRESS_MIN_TIMEOUT}
     * (true fast-fail); on a slow fabric it is {@code scale * rttMs}, so a
     * slow-but-alive tunnel keeps substantially longer than the historical 10s and
     * its handshake has room to complete.  The result is always capped at the
     * configured timeout and never exceeds it.
     *
     * <p>No data never triggers the clamp: a NaN build success (stand-alone
     * streaming or early startup) returns the configured timeout unchanged, an
     * unknown expire rate ({@code < 0}) is treated as "no evidence" (no clamp), and
     * a non-positive configured timeout is returned unchanged.  A fractional 0.0
     * build success is genuine failure, not "no data".
     *
     * @param configuredTimeoutMs the configured accept timeout (i2p.streaming.acceptTimeout)
     * @param buildSuccess tunnel build success as a fraction [0.0, 1.0]; NaN when unavailable
     * @param recentExpireRatePct percent of recent SYN-queue entries that expired un-accepted,
     *                            or a negative value when the rate is not known yet
     * @param rttMs a recent round-trip time sample in milliseconds, or &lt;=0 when unavailable
     * @return the timeout (ms) to arm the TimeoutSyn with
     */
    static int getAdaptiveSynTimeout(int configuredTimeoutMs, double buildSuccess,
                                     int recentExpireRatePct, int rttMs) {
        if (configuredTimeoutMs <= 0 || Double.isNaN(buildSuccess) || recentExpireRatePct < 0) {
            return configuredTimeoutMs;
        }
        int expireThresh = I2PSocketManagerFull.getSynExprExpireThresh();
        if (expireThresh <= 0) {expireThresh = SYN_EXPIRE_THRESHOLD_DEFAULT;}
        if (expireThresh >= 100) {return configuredTimeoutMs;}
        if (buildSuccess >= SYN_STRESS_THRESHOLD || recentExpireRatePct < expireThresh) {
            return configuredTimeoutMs;
        }
        int scale = I2PSocketManagerFull.getRttSynTimeoutScale();
        if (scale <= 0) {scale = SYN_RTT_SCALE_DEFAULT;}
        long floor = SYN_STRESS_MIN_TIMEOUT;
        if (scale > 0 && rttMs > 0) {
            long rttScaled = (long) scale * (long) rttMs;
            if (rttScaled > floor) {floor = rttScaled;}
        }
        long clamped = Math.min(floor, configuredTimeoutMs);
        return (int) clamped;
    }

    /** Router-clock time of the last tunnel-stress sample. */
    private volatile long _lastStressSampleAt;
    /** Cached tunnel build success fraction; NaN when unavailable. */
    private volatile double _tunnelBuildSuccess;
    /** SYNs added to the acceptance queue within the current sample window. */
    private volatile int _synQueueProcessed;
    /** SYNs that expired un-accepted (removed by {@code TimeoutSyn}) in the current window. */
    private volatile int _synQueueExpired;
    /** Most recently observed SYN accept-queue residence time (ms), i.e. how long a fresh SYN
     *  waited in the queue before being accepted. 0 until the first acceptance. */
    private volatile int _synQueueResidenceMs;
    /** Enqueue clock-times for packets currently in the accept queue, keyed by packet identity. */
    private final ConcurrentHashMap<Packet, Long> _synEnqueueTimes =
            new ConcurrentHashMap<Packet, Long>();

    /**
     * Tunnel build success fraction, sampled at most once per
     * {@link #SYN_STRESS_SAMPLE_INTERVAL} to avoid per-packet StatManager
     * lookups (each {@code SystemVersion} probe is six RateStat reads).
     *
     * <p>Outside a router context (stand-alone streaming) there are no tunnel
     * statistics at all — report NaN so the configured timeout is kept.
     * {@code SystemVersion} reports 0% both for "no tunnel events yet" and for
     * real failure, so 0 is treated as unknown (NaN) rather than stress.
     *
     * @return the build success fraction [0.0, 1.0], or NaN when unavailable
     */
    private double getTunnelBuildSuccess() {
        long now = _context.clock().now();
        if (now - _lastStressSampleAt >= SYN_STRESS_SAMPLE_INTERVAL) {
            double bs = Double.NaN;
            if (_context.isRouterContext()) {
                int pct = SystemVersion.getTunnelBuildSuccess();
                if (pct > 0) {bs = pct / 100.0;}
            }
            _tunnelBuildSuccess = bs;
            _lastStressSampleAt = now;
        }
        return _tunnelBuildSuccess;
    }

    /**
     * Recent SYN expire rate, sampled on the same interval as build success.
     *
     * <p>Counts SYNs added to the accept queue ({@code _synQueueProcessed}) and
     * SYNs later removed by {@code TimeoutSyn} without being accepted
     * ({@code _synQueueExpired}).  The window resets whenever a full
     * {@link #SYN_STRESS_SAMPLE_INTERVAL} elapses, so the rate reflects the
     * most recent tunnel-health window rather than the ever since startup.
     *
     * <p>Until the first window completes there is <em>no evidence</em>, which is
     * reported as {@code -1} so the adaptive clamp never fires on startup.
     *
     * @return percent of queued SYNs that expired un-accepted [0,100], or -1 when
     *         not enough history has accumulated yet
     */
    private int getSynExpireRatePct() {
        long now = _context.clock().now();
        if (now - _lastStressSampleAt >= SYN_STRESS_SAMPLE_INTERVAL) {
            // A full window has elapsed. Publish the just-closed window's expire
            // rate (so the Tuner sees the router-wide accept-queue health), then
            // reset for the next window. Returning the completed rate instead of
            // -1 avoids discarding the window's data at the rollover instant.
            int rate = currentSynExpireRatePct();
            _lastStressSampleAt = now;
            _synQueueProcessed = 0;
            _synQueueExpired = 0;
            publishSynExpireRate(rate);
            return rate;
        }
        return currentSynExpireRatePct();
    }

    /**
     * The expire-rate of the current (in-progress) sample window: percent of
     * queued SYNs that expired un-accepted, or -1 when there is no evidence yet
     * (no SYNs processed this window). Kept separate from the window rollover so
     * the publish-on-rollover path can report the completed window.
     *
     * @return percent [0,100] of queued SYNs that expired, or -1 when no evidence
     */
    private int currentSynExpireRatePct() {
        int processed = _synQueueProcessed;
        if (processed <= 0) {return -1;}
        int expired = _synQueueExpired;
        if (expired <= 0) {return 0;}
        return (int) (100L * expired / processed);
    }

    /**
     * Publish the SYN accept-queue expire rate as a router-wide RateStat so the
     * Tuner can distinguish latency-bound accept stalls (high expire rate: more
     * handler threads will not help — the transport is the bottleneck) from a
     * genuinely parallelizable load. Only meaningful in a router context, and
     * only when a real rate is available.
     *
     * @param ratePct the expire rate percent, or -1 when there is no evidence
     */
    private void publishSynExpireRate(int ratePct) {
        if (!_context.isRouterContext() || ratePct < 0) {return;}
        _context.statManager().addRateData("stream.con.synExpireRate", ratePct);
    }

    /**
     * The effective SYN accept-queue timeout: the configured timeout, clamped
     * -- only while the tunnel system shows positive stall evidence -- to an
     * RTT-aware floor never below {@link #SYN_STRESS_MIN_TIMEOUT} and never
     * above the configured timeout.
     *
     * @return timeout in ms to arm TimeoutSyn with
     */
    private int getEffectiveAcceptTimeout() {
        return getAdaptiveSynTimeout(_acceptTimeout, getTunnelBuildSuccess(),
                                     getSynExpireRatePct(), getRttMs());
    }

    /**
     * A recent round-trip time sample in milliseconds, or 0 when unavailable.
     *
     * <p>On a hosted (server) connection there is no handshake RTT to read before
     * the connection exists, so the adaptive floor uses the SYN accept-queue
     * <em>residence time</em> as a direct proxy for how long the server takes to
     * reach a freshly queued SYN: the window that must exceed this residence to
     * avoid expiring slow handshakes.  An unavailable/zero sample keeps the fixed
     * {@link #SYN_STRESS_MIN_TIMEOUT} floor and is always safe.
     *
     * @return a recent queue-residence RTT in milliseconds, or 0 when not known yet
     */
    private int getRttMs() {
        return _synQueueResidenceMs;
    }

    /**
     * Record how long a freshly accepted SYN spent in the accept queue.
     *
     * <p>Called from the accept loop immediately before a fresh SYN is handed to
     * {@code receiveConnection()}.  The enqueue timestamp (stamped in
     * {@code receiveNewSyn}) is removed and the residence time becomes the
     * sampled {@link #getRttMs()} input to the adaptive SYN timeout.  The sample
     * is lightly smoothed toward the previous value so a single slow acceptance
     * does not swing the floor all the way to configured on its own.
     *
     * <p>Retransmitted and poison SYNs never reach this point, so only genuinely
     * fresh handshakes are measured.
     *
     * @param syn the freshly de-queued SYN about to be accepted
     */
    private void sampleSynResidence(Packet syn) {
        if (syn == null) {return;}
        Long enqueuedAt = _synEnqueueTimes.remove(syn);
        if (enqueuedAt == null) {return;}
        int residence = (int) Math.min(Integer.MAX_VALUE,
                _context.clock().now() - enqueuedAt.longValue());
        if (residence < 0) {residence = 0;}
        int prev = _synQueueResidenceMs;
        _synQueueResidenceMs = (prev + residence) / 2;
    }

    /** Creates a new instance of ConnectionHandler */
    public ConnectionHandler(I2PAppContext context, ConnectionManager mgr, SimpleTimer2 timer) {
        _context = context;
        _log = context.logManager().getLog(ConnectionHandler.class);
        _manager = mgr;
        _timer = timer;
        // Hard backstop only; the effective cap is the configurable soft max
        // (getMaxQueueSize) re-read on each SYN so Tuner wins apply live.
        _synQueue = new LinkedBlockingDeque<>(16384);
        _acceptTimeout = getAcceptTimeout();
    }

    /**
     * The router told us it's going to restart.
     * Call instead of setActive(false).
     *
     * @since 0.9.34
     */
    public synchronized void setRestartPending() {
        _restartPending = true;
        setActive(false);
    }

    /**
     * Whether this handler is actively accepting new connections.
     * When set to false, a poison packet is offered to wake any
     * threads blocked in accept().
     *
     * @param active true to accept connections, false to stop
     */
    public synchronized void setActive(boolean active) {
        // FIXME active=false this only kills for one thread in accept()
        // if there are more, they won't get a poison packet.
        if (_log.shouldInfo()) {
            _log.info("setActive(" + active + ") called, previously " + _active);
        }
        // if starting, clear any old poison
        if (active && !_active) {
            _restartPending = false;
            _synQueue.clear();
            _synEnqueueTimes.clear();
            _synQueueProcessed = 0;
            _synQueueExpired = 0;
        }
        boolean wasActive = _active;
        _active = active;
        if (wasActive && !active) {
            // stopping, clear any pending sockets
            _synQueue.clear();
            _synEnqueueTimes.clear();
            _synQueueProcessed = 0;
            _synQueueExpired = 0;
            _synQueue.offer(new PoisonPacket());
        }
    }

    /**
     * Check if this handler is actively accepting new connections.
     *
     * @return true if accepting connections
     */
    public boolean getActive() {return _active;}

    /**
     * Non-SYN packets with a zero SendStreamID may also be queued here so
     * that they don't get thrown away while the SYN packet before it is queued.
     *
     * Additional overload protection may be required here...
     * We don't have a 3-way handshake, so the SYN fully opens a connection.
     * Does that make us more or less vulnerable to SYN flooding?
     *
     */
    public void receiveNewSyn(Packet packet) {
        if (packet == null) return;
        if (!_active) {
            if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                if (_log.shouldWarn()) {_log.warn("Dropping new SYN request because we're not listening");}
                sendReset(packet);
            } else if (_log.shouldWarn()) {_log.warn("Dropping non-SYN packet -> Not listening");}
            return;
        }
        if (_manager.wasRecentlyClosed(packet.getSendStreamId())) {
            if (_log.shouldWarn()) {_log.warn("Dropping packet for recently closed stream: " + packet);}
            return;
        }
        int timeoutMs = getEffectiveAcceptTimeout();
        if (_log.shouldInfo()) {
            _log.info("Received new SYN packet with " + (timeoutMs / 1000) + "s timeout: " + packet);
        }
        // also check if expiration of the head is long past for overload detection with peek() ?
        // Re-read the max queue size dynamically — Tuner override or config change
        // applies without a restart.
        boolean success = _synQueue.size() < getMaxQueueSize() && _synQueue.offer(packet);
        if (success) {
            _synEnqueueTimes.put(packet, Long.valueOf(_context.clock().now()));
            _synQueueProcessed++;
            _timer.addEvent(new TimeoutSyn(packet), timeoutMs);
        } else {
            // Send RESET so the client can establish a new connection
            // immediately (via its own connect retry logic) rather than
            // waiting for the full RTO (~3s) before the SYN retransmits.
            // The client's connect timeout (default 60s) gives plenty of
            // time for the server to drain its accept queue.
            if (_log.shouldWarn()) {
                _log.warn("SYN queue full, sending RESET to client");
            }
            sendReset(packet);
        }
    }

    /**
     * Receive an incoming connection (built from a received SYN)
     * Non-SYN packets with a zero SendStreamID may also be queued here so
     * that they don't get thrown away while the SYN packet before it is queued.
     *
     * @param timeoutMs max amount of time to wait for a connection (if less
     *                  than 1ms, wait indefinitely)
     * @return connection received. Prior to 0.9.17, or null if there was a timeout or the
     *                  handler was shut down. As of 0.9.17, never null.
     * @throws RouterRestartException (extends I2PException) if the router is apparently restarting, since 0.9.34
     * @throws ConnectException since 0.9.17, returned null before;
     *                  if the I2PServerSocket is closed, or if interrupted.
     * @throws SocketTimeoutException since 0.9.17, returned null before;
     *                  if a timeout was previously set with setSoTimeout and the timeout has been reached.
     */
    public Connection accept(long timeoutMs) throws RouterRestartException, ConnectException, SocketTimeoutException {
        if (_log.shouldDebug()) {_log.debug("Accept with timeout of " + timeoutMs + "ms called...");}

        long expiration = timeoutMs + _context.clock().now();
        while (true) {
            if ((timeoutMs > 0) && (expiration < _context.clock().now())) {
                throw new SocketTimeoutException("accept() timed out");
            }
            if (!_active) { // fail all the ones we had queued up
                while(true) {
                    Packet packet = _synQueue.poll(); // fails immediately if empty
                    if (packet == null || packet.getOptionalDelay() == PoisonPacket.POISON_MAX_DELAY_REQUEST) {
                        break;
                    }
                    _synEnqueueTimes.remove(packet);
                    sendReset(packet);
                }
                    boolean restartPending;
                    synchronized(this) {
                        restartPending = _restartPending;
                    }
                    if (restartPending) {throw new RouterRestartException();}
                throw new ConnectException("ServerSocket closed");
            }

            Packet syn = null;
            while ( _active && syn == null) {
                if (_log.shouldDebug()) {
                    _log.debug("Accept("+ timeoutMs+"): active=" + _active + " queue: "+ _synQueue.size());
                }
                if (timeoutMs <= 0) {
                    try {syn = _synQueue.take();} // waits forever
                    catch (InterruptedException ie) {
                       Thread.currentThread().interrupt();
                       ConnectException ce = new ConnectException("Interrupted accept()");
                       ce.initCause(ie);
                       throw ce;
                    }
                } else {
                    long remaining = expiration - _context.clock().now();
                    // (Don't think this applies anymore for LinkedBlockingQueue)
                    // BUGFIX
                    // The specified amount of real time has elapsed, more or less.
                    // If timeout is zero, however, then real time is not taken into consideration
                    // and the thread simply waits until notified.
                    if (remaining < 1) {break;}
                    try {syn = _synQueue.poll(remaining, TimeUnit.MILLISECONDS);} // waits the specified time max
                    catch (InterruptedException ie) {
                       Thread.currentThread().interrupt();
                       ConnectException ce = new ConnectException("Interrupted accept()");
                       ce.initCause(ie);
                       throw ce;
                    }
                    break;
                }
            }

            if (syn != null) {
                if (syn.getOptionalDelay() == PoisonPacket.POISON_MAX_DELAY_REQUEST) {
                boolean restartPending;
                synchronized(this) {
                    restartPending = _restartPending;
                }
                if (restartPending) {throw new RouterRestartException();}
                    throw new ConnectException("ServerSocket closed");
                }

                /* deal with forged / invalid syn packets in _manager.receiveConnection() */

                // Handle both SYN and non-SYN packets in the queue
                if (syn.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                    // We are single-threaded here, so this is
                    // a good place to check for dup SYNs and drop them
                    Destination from = syn.getOptionalFrom();
                    if (from == null) {
                        if (_log.shouldWarn() && syn != null) {_log.warn("Dropping SYN packet with no FROM: " + syn);}
                        continue; // drop it
                    }
                    Connection oldcon = _manager.getConnectionByOutboundId(syn.getReceiveStreamId());
                    if (oldcon != null && from.equals(oldcon.getRemotePeer())) {
                        // His ID not guaranteed to be unique to us, but probably is...
                        // only act on it on a destination match too
                        // This is a retransmitted SYN - the client hasn't received our
                        // SYN-ACK yet (or it was lost). Re-send the SYN-ACK for the
                        // existing connection rather than destroying it and breaking
                        // any data the client may have already sent using the old
                        // stream IDs.
                        //
                        // Flood gate: a retransmitted SYN uses stream IDs of an
                        // existing (half-open) connection, so it never flows through
                        // ConnectionManager.receiveConnection() and its SYN-burst gate.
                        // An attacker plants a few half-open connections then blasts
                        // retransmitted SYNs, which would otherwise spawn an unbounded
                        // SYN-ACK storm. Check the shared per-dest flood window here so
                        // a dest that exceeds the burst threshold is autobanned and its
                        // retransmits dropped before any SYN-ACK is minted.
                        if (_manager.checkInboundSynFlood(from.calculateHash(), _context.clock().now())) {
                            continue; // drop it without re-sending a SYN-ACK
                        }
                        // Rate-bind SYN-ACK re-sends: a latency-bound client (RTO < I2P RTT)
                        // retransmits its SYN faster than its SYN-ACKs arrive, and each
                        // retransmit would otherwise mint another full signed SYN-ACK into
                        // the shared FIFO (the amplification loop seen on the tracker
                        // tunnel).  Snapshot the decision at read time; state changes don't
                        // advance the throttle window for a rejected retransmit.
                        if (!oldcon.shouldResendSynAck(_context.clock().now())) {
                            if (_log.shouldDebug()) {_log.debug("Dropping retransmitted SYN, SYN-ACK throttle active: " + oldcon);}
                            continue;
                        }
                        // Log the first re-send per connection at WARN (one-shot diagnosis),
                        // subsequent re-sends at DEBUG — the storm log inflation is as much
                        // a problem as the extra packets.
                        boolean alreadyWarned = oldcon.synAckWarnAlreadyLogged();
                        if (_log.shouldWarn() && !alreadyWarned && syn != null) {
                            _log.warn("Received retransmitted SYN for existing connection, re-sending SYN-ACK: " +
                                      oldcon + (syn != null && !syn.toString().isEmpty() ? "\n* SYN: " + syn : ""));
                        } else if (_log.shouldDebug()) {
                            _log.debug("Received retransmitted SYN for existing connection, re-sending SYN-ACK: " + oldcon);
                        }
                        resendSynAck(oldcon, syn);
                        continue;
                    }
                    sampleSynResidence(syn);
                    Connection con = _manager.receiveConnection(syn);
                    if (con != null) {return con;}
                } else {reReceivePacket(syn);} // ... and keep looping
            }
        }
    }

    /**
     *  We found a non-SYN packet that was queued in the syn queue,
     *  check to see if it has a home now, else drop it ...
     */
    private void reReceivePacket(Packet packet) {
        if (packet == null) {
            if (_log.shouldWarn()) {_log.warn("Received null packet, ignoring...");}
            return;
        }
        Connection con = _manager.getConnectionByOutboundId(packet.getReceiveStreamId());
        if (con != null) {
            // Send it through the packet handler again
            if (_log.shouldWarn()) {
                _log.warn("Connection found for queued non-SYN packet: " + packet);
            }
            // false -> don't requeue, fixes a race where a SYN gets dropped
            // between here and PacketHandler, causing the packet to loop forever....
            _manager.getPacketHandler().receivePacketDirect(packet, false);
        } else {
            // log it here, just before we kill it - dest will be unknown
            if (I2PSocketManagerFull.pcapWriter != null && _context.getBooleanProperty(I2PSocketManagerFull.PROP_PCAP)) {
                packet.logTCPDump(null);
            }
            // goodbye
            if (_log.shouldWarn()) {
                _log.warn("Connection not found for queued non-SYN packet, dropping... " + packet);
            }
            packet.releasePayload();
        }
    }

    /**
     *  Send a reset in response to this packet, but only if it
     *  contains a FROM field and Signature that can be verified.
     *
     *  @param packet the incoming packet we're responding to
     */
    private void sendReset(Packet packet) {
        if (packet == null) {
            if (_log.shouldWarn()) {_log.warn("Received NULL packet, cannot send RESET...");}
            return;
        }
        ByteArray ba = _cache.acquire();
        boolean ok = packet.verifySignature(_context, ba.getData());
        _cache.release(ba);
        if (!ok) {
            if (_log.shouldWarn()) {_log.warn("Can't send RESET in response to unverifiable packet: " + packet);}
            return;
        }
        PacketLocal reply = new PacketLocal(_context, packet.getOptionalFrom(), packet.getSession());
        reply.setFlag(Packet.FLAG_RESET | Packet.FLAG_SIGNATURE_INCLUDED);
        reply.setAckThrough(packet.getSequenceNum());
        reply.setSendStreamId(packet.getReceiveStreamId());
        reply.setReceiveStreamId(0);
        reply.setLocalPort(packet.getLocalPort());
        reply.setRemotePort(packet.getRemotePort());
        if (_log.shouldDebug()) {_log.debug("Sending RESET: " + reply + " because of " + packet);}
        _manager.getPacketQueue().enqueue(reply); // this just sends the packet - no retries or whatnot
    }

    /**
     *  Re-send a SYN-ACK in response to a retransmitted SYN for an existing connection.
     *  This prevents the race condition where:
     *  1. Client sends SYN, server creates connection, sends SYN-ACK
     *  2. Client retransmits SYN before receiving SYN-ACK (RTT > RTO)
     *  3. Server destroys the old connection (which the client already completed handshake on)
     *  4. Client's data arrives on dead stream IDs - dropped forever
     *
     *  Instead, we just re-send the SYN-ACK for the existing connection, which is
     *  the standard TCP behavior for retransmitted SYNs.
     *
     *  @param con the existing connection to re-send the SYN-ACK for
     *  @param syn the retransmitted SYN packet
     */
    private void resendSynAck(Connection con, Packet syn) {
        PacketLocal reply = new PacketLocal(_context, con.getRemotePeer(), syn.getSession());
        reply.setFlag(Packet.FLAG_SYNCHRONIZE | Packet.FLAG_SIGNATURE_INCLUDED);
        reply.setSequenceNum(0);
        reply.setAckThrough(syn.getSequenceNum());
        reply.setSendStreamId(con.getReceiveStreamId());
        reply.setReceiveStreamId(con.getSendStreamId());
        reply.setLocalPort(syn.getLocalPort());
        reply.setRemotePort(syn.getRemotePort());
        int mtu = con.getOptions().getMaxMessageSize();
        reply.setFlag(Packet.FLAG_MAX_PACKET_SIZE_INCLUDED);
        reply.setOptionalMaxSize(mtu);
        if (_log.shouldDebug()) {_log.debug("Re-sending SYN-ACK: " + reply + " for existing " + con);}
        if (_manager.getPacketQueue().enqueue(reply)) {
            // only advance the throttle window if the packet was actually queued;
            // a failed enqueue (dead queue) must not consume re-send budget
            con.recordSynAckResend(_context.clock().now());
        }
    }

    /**
     * Build a diagnostic message for a SYN that expired on the accept queue
     * without being accepted.  The historical message ("Expired on the SYN
     * queue: " + packet) printed only stream IDs and flags — no peer hash, no
     * queue pressure, no timeout — so a flood of these read as identical
     * trailing-colon lines that could not be attributed or rate-assessed.
     *
     * <p>This surfaces the data needed to tell "one flooding dest" apart from
     * "many legit clients the app never accepted":
     * <ul>
     * <li>the <em>source</em> dest hash (short base32 prefix), which is the
     *     peer that sent the SYN and what the per-dest flood gate keys on;</li>
     * <li>current queue depth vs max, so a spike is visible as near-full;</li>
     * <li>the effective accept timeout that expired it (evidence-gated clamp).</li>
     * </ul>
     *
     * <p>Idempotent and side-effect free so it can be unit-tested; the appends
     * to {@code out} let the same formatting be reused for other SYN expiry
     * reports (e.g. DEBUG-level summaries) without duplicating the format.
     *
     * @since 0.9.71+
     */
    static void synExpirySummary(Packet syn, StringBuilder out) {
        if (syn == null) {out.append("null SYN"); return;}
        if (syn.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {out.append("SYN ");}
        else {out.append("Pkt ");}
        out.append(syn.getSequenceNum());
        Destination from = syn.getOptionalFrom();
        if (from != null) {
            out.append(" from ").append(from.calculateHash().toBase32().substring(0, 6));
        }
        out.append(" streamIDs [").append(Packet.toId(syn.getReceiveStreamId()))
           .append('/').append(Packet.toId(syn.getSendStreamId())).append(']');
    }

    /**
     * Full single-line WARN for a timed-out SYN, folding in both the packet
     * detail and the queue/timeout pressure that caused the expiry, so the whole
     * picture lands on one log line instead of a bare trailing colon.
     *
     * @since 0.9.71+
     */
    static String synExpiryMessage(Packet syn, int queueDepth, int maxQueueSize, int timeoutMs) {
        StringBuilder out = new StringBuilder(96);
        out.append("Expired on SYN queue (accept timeout ").append(timeoutMs).append("ms, ");
        if (maxQueueSize > 0) {
            out.append(queueDepth).append('/').append(maxQueueSize);
        } else {
            out.append("depth ").append(queueDepth);
        }
        out.append("): ");
        synExpirySummary(syn, out);
        return out.toString();
    }

    /**
     * Timer event that removes a SYN packet from the queue after the
     * accept timeout expires, sending a reset if it was a SYN.
     */
    private class TimeoutSyn extends SimpleTimer2.TimedEvent {
        private final Packet _synPacket;

        TimeoutSyn(Packet packet) {
            super();
            _synPacket = packet;
        }

        public void timeReached() {
            boolean removed = _synQueue.remove(_synPacket);
            if (removed) {
                _synEnqueueTimes.remove(_synPacket);
                _synQueueExpired++;
                if (_synPacket.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                    if (_log.shouldWarn())
                        _log.warn(synExpiryMessage(_synPacket, _synQueue.size(),
                                                   getMaxQueueSize(), getEffectiveAcceptTimeout()));
                    sendReset(_synPacket);
                } else {
                    reReceivePacket(_synPacket);
                }
            }
        }
    }

    /**
     * Simple end-of-queue marker.
     * The standard class limits the delay to POISON_MAX_DELAY_REQUEST so
     * an evil user can't use this to shut us down
     */
    private static class PoisonPacket extends Packet {
        public static final int POISON_MAX_DELAY_REQUEST = Packet.MAX_DELAY_REQUEST + 1;

        public PoisonPacket() {super(null);}

        @Override
        public int getOptionalDelay() {return POISON_MAX_DELAY_REQUEST;}

        @Override
        public String toString() {return "POISON";}
    }

}
