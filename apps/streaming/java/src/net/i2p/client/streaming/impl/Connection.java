package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.BandwidthEstimator;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

/**
 * Maintain the state controlling a streaming connection between two destinations.
 */
class Connection {
    private final I2PAppContext _context;
    private final Log _log;
    private final ConnectionManager _connectionManager;
    private final I2PSession _session;
    private Destination _remotePeer;
    private SigningPublicKey _transientSPK;
    private final AtomicLong _sendStreamId = new AtomicLong();
    private final AtomicLong _receiveStreamId = new AtomicLong();
    private volatile long _lastSendTime;
    private final AtomicLong _lastSendId;
    private final AtomicBoolean _resetReceived = new AtomicBoolean();
    private final AtomicLong _resetSentOn = new AtomicLong();
    private final AtomicBoolean _connected = new AtomicBoolean(true);
    private final AtomicBoolean _finalDisconnect = new AtomicBoolean();
    private volatile boolean _hardDisconnected;
    private final MessageInputStream _inputStream;
    private final MessageOutputStream _outputStream;
    private final SchedulerChooser _chooser;
    /** Locking: _nextSendLock */
    private long _nextSendTime;
    private final AtomicLong _ackedPackets = new AtomicLong();
    private final long _createdOn;
    private final AtomicLong _closeSentOn = new AtomicLong();
    private final AtomicLong _closeReceivedOn = new AtomicLong();
    private final AtomicInteger _unackedPacketsReceived = new AtomicInteger();
    private long _congestionWindowEnd;
    private volatile long _highestAckedThrough;
    private volatile int _ssthresh;
    private final boolean _isInbound;
    private boolean _updatedShareOpts;
    /** Packet ID (Long) to PacketLocal for sent but unacked packets */
    private final TreeMap<Long, PacketLocal> _outboundPackets;
    private final PacketQueue _outboundQueue;
    private final ConnectionPacketHandler _handler;
    private ConnectionOptions _options;
    private final ConnectionDataReceiver _receiver;
    private I2PSocketFull _socket;
    /** set to an error cause if the connection could not be established */
    private String _connectionError;
    private final AtomicLong _disconnectScheduledOn = new AtomicLong();
    private long _lastReceivedOn;
    private final ActivityTimer _activityTimer;
    private long _lastCongestionTime;
    private volatile long _lastCongestionHighestUnacked;

    // Pacing fields for smooth transmission
    private volatile long _pacingRate; // bytes per second
    private volatile long _lastPacketSendTime;
    private final Object _pacingLock = new Object();
    /** has the other side choked us? */
    private volatile boolean _isChoked;
    /** are we choking the other side? */
    private volatile boolean _isChoking;
    private final AtomicInteger _unchokesToSend = new AtomicInteger();
    private final AtomicBoolean _ackSinceCongestion;
    /** Notify this on connection (or connection failure) */
    private final Object _connectLock;
    /** Locking for _nextSendTime */
    private final Object _nextSendLock;
    /** how many messages have been resent and not yet ACKed? */
    private final AtomicInteger _activeResends = new AtomicInteger();
    private final ConEvent _connectionEvent;
    private final RetransmitEvent _retransmitEvent;
    private final int _randomWait;
    private final int _localPort;
    private final int _remotePort;
    private final SimpleTimer2 _timer;
    private final BandwidthEstimator _bwEstimator;

    private final AtomicLong _lifetimeBytesSent = new AtomicLong();
    /** TBD for tcpdump-compatible ack output */
    private long _lowestBytesAckedThrough;
    private final AtomicLong _lifetimeBytesReceived = new AtomicLong();
    private final AtomicLong _lifetimeDupMessageSent = new AtomicLong();
    private final AtomicLong _lifetimeDupMessageReceived = new AtomicLong();

    public static final int MAX_RESEND_DELAY = 45*1000;
    public static final int MIN_RESEND_DELAY = SystemVersion.isSlow() ? 100 : 50;

    /**
     *  Wait up to 5 minutes after disconnection so we can ack/close packets.
     *  Roughly equal to the TIME-WAIT time in RFC 793, where the recommendation is 4 minutes (2 * MSL)
     */
    public static final int DISCONNECT_TIMEOUT = 5*60*1000;

    public static final int DEFAULT_CONNECT_TIMEOUT = 60*1000;
    private static final long MAX_CONNECT_TIMEOUT = 2*60*1000;

    /**
     *  This is the default maximum. See ConnectionOptions.setMaxWindowSize()
     *  where the configured maximum is enforced.
     *  Increased for better throughput on high-bandwidth I2P connections.
     */
    public static final int MAX_WINDOW_SIZE = SystemVersion.isSlow() ? 192 : 384;

    private static final int UNCHOKES_TO_SEND = 8;

    /**
     * Maximum number of packets to retransmit when the timer hits.
     * Increased from 16 to 32 for faster loss recovery.
     */
    private static final int MAX_RTX = 32;

    /**
     * Maximum unacked packets to buffer per connection.
     * Prevents memory exhaustion on stuck/stalled connections.
     * Default is 2x the max window size to allow for bursts.
     */
    private static final int MAX_UNACKED_PACKETS = SystemVersion.isSlow() ? 512 : 768;

    /**
     *  @param opts may be null
     */
    public Connection(I2PAppContext ctx, ConnectionManager manager,
                      I2PSession session, SchedulerChooser chooser,
                      SimpleTimer2 timer,
                      PacketQueue queue, ConnectionPacketHandler handler, ConnectionOptions opts,
                      boolean isInbound) {
        _context = ctx;
        _connectionManager = manager;
        _session = session;
        _chooser = chooser;
        _outboundQueue = queue;
        _handler = handler;
        _isInbound = isInbound;
        _log = _context.logManager().getLog(Connection.class);
        _receiver = new ConnectionDataReceiver(_context, this);
        _options = (opts != null ? opts : new ConnectionOptions());
        _inputStream = new MessageInputStream(_context, _options.getMaxMessageSize(),
                                              _options.getMaxWindowSize(), _options.getInboundBufferSize(),
                                              _options.getMaxPacketCount());
        // FIXME pass through a passive flush delay setting as the 4th arg
        _outputStream = new MessageOutputStream(_context, timer, _receiver,
                                                _options.getMaxMessageSize(), _options.getMaxInitialMessageSize());
        _timer = timer;
        _outboundPackets = new TreeMap<Long, PacketLocal>();
        if (opts != null) {
            _localPort = opts.getLocalPort();
            _remotePort = opts.getPort();
        } else {
            _localPort = 0;
            _remotePort = 0;
        }
        _outputStream.setWriteTimeout((int)_options.getWriteTimeout());
        _inputStream.setReadTimeout((int)_options.getReadTimeout());
        _lastSendId = new AtomicLong(-1);
        _nextSendTime = -1;
        _createdOn = _context.clock().now();
        _congestionWindowEnd = _options.getWindowSize()-1;
        _highestAckedThrough = -1;
        _ssthresh = ConnectionPacketHandler.MAX_SLOW_START_WINDOW;
        _lastCongestionTime = -1;
        _lastCongestionHighestUnacked = -1;
        _lastReceivedOn = -1;
        _activityTimer = new ActivityTimer();
        _ackSinceCongestion = new AtomicBoolean(true);
        _connectLock = new Object();
        _nextSendLock = new Object();

        // Initialize pacing
        _pacingRate = calculatePacingRate();
        _lastPacketSendTime = 0;

        // Initialize connection event and retransmit event
        _connectionEvent = new ConEvent();
        _retransmitEvent = new RetransmitEvent();

        // Initialize random wait for activity timer randomization and bandwidth estimator
        _randomWait = _context.random().nextInt(3*1000); // 0-3 seconds randomization
        _bwEstimator = new SimpleBandwidthEstimator(_context, _options);
    }

    /**
     * Calculate pacing rate based on current congestion window and RTT.
     * Rate = (cwnd * mss) / rtt to smooth transmission.
     */
    private long calculatePacingRate() {
        int cwnd = _options.getWindowSize();
        int mss = _options.getMaxMessageSize();
        int rtt = _options.getRTT();

        if (rtt <= 0 || cwnd <= 0 || mss <= 0) {
            return Long.MAX_VALUE; // No pacing if parameters invalid
        }

        // Calculate rate in bytes per second
        long rateBytesPerSec = (long) cwnd * mss * 1000 / rtt;

        // Ensure minimum rate to prevent excessive delays
        long minRate = 256 * 1024; // 256 KB/s minimum for better bandwidth utilization
        return Math.max(rateBytesPerSec, minRate);
    }

    /**
     * Update pacing rate when congestion window changes.
     */
    private void updatePacingRate() {
        synchronized (_pacingLock) {
            _pacingRate = calculatePacingRate();
        }
    }

    /**
     * Calculate delay needed for pacing based on packet size and current rate.
     */
    private long calculatePacingDelay(int packetSize) {
        synchronized (_pacingLock) {
            if (_pacingRate == Long.MAX_VALUE) {
                return 0; // No pacing
            }

            long now = _context.clock().now();
            long timeSinceLastPacket = now - _lastPacketSendTime;
            long expectedInterval = (long) packetSize * 1000 / _pacingRate;

            if (timeSinceLastPacket >= expectedInterval) {
                return 0; // Can send immediately
            }

            return expectedInterval - timeSinceLastPacket;
        }
    }

    /**
     * @since 0.9.46
     */
    int getSSThresh() {return _ssthresh;}

    public long getNextOutboundPacketNum() {return _lastSendId.incrementAndGet();}

    /**
     * This doesn't "send a choke". Rather, it blocks if the outbound window is full,
     * thus choking the sender that calls this.
     *
     * Block until there is an open outbound packet slot or the write timeout expires.
     * PacketLocal is the only caller, generally with -1.
     *
     * @param timeoutMs 0 or negative means wait forever, 5 minutes max
     * @return true if the packet should be sent, false for a fatal error
     *         will return false after 5 minutes even if timeoutMs is &lt;= 0.
     */
    public boolean packetSendChoke(long timeoutMs) throws IOException, InterruptedException {
        final long MAX_BLOCKING_TIME_MS = 5 * 60 * 1000;  // 5 minutes
        final int WAIT_TIME_MS = 250;

        long start = _context.clock().now();
        long writeExpire = start + timeoutMs;
        boolean started = false;

        synchronized (_outboundPackets) {
            while (true) {
                long timeLeft = writeExpire - _context.clock().now();
                if (!started) {_context.statManager().addRateData("stream.chokeSizeBegin", _outboundPackets.size());}
                if (hasBlockedTooLong(start, MAX_BLOCKING_TIME_MS)) {return false;}

                if (!isConnectedOrError()) {return false;}  // Throws IOException or I2PSocketException
                started = true;

                int unacked = _outboundPackets.size();
                int wsz = _options.getWindowSize();

                if (shouldWait(unacked, wsz)) {
                    if (timeoutMs > 0 && timeLeft <= 0) {
                        if (!handleTimeout(timeLeft, timeoutMs)) {return false;}
                    }
                    _outboundPackets.wait(WAIT_TIME_MS);
                } else {
                    _context.statManager().addRateData("stream.chokeSizeEnd", _outboundPackets.size());
                    return true;
                }
            }
        }
    }

    private boolean hasBlockedTooLong(long start, long maxBlockingTime) {
        return (start + maxBlockingTime < _context.clock().now());
    }

    private boolean isConnectedOrError() throws IOException {
        if (!_connected.get()) {
            if (getResetReceived()) {throw new I2PSocketException(I2PSocketException.STATUS_CONNECTION_RESET);}
            throw new IOException("Socket closed");
        }
        if (_outputStream.getClosed()) {throw new IOException("Output stream closed");}
        return true;
    }

    private boolean shouldWait(int unacked, int wsz) {
        return _isChoked || unacked >= wsz ||
               _activeResends.get() >= (wsz + 1) / 2 ||
               _lastSendId.get() - _highestAckedThrough >= Math.min(MAX_WINDOW_SIZE, 2 * wsz);
    }

    private boolean handleTimeout(long timeLeft, long timeoutMs) {
        int unacked = _outboundPackets.size();
        int wsz = _options.getWindowSize();
        if (_log.shouldDebug()) {
            _log.debug("Outbound window is full " + (_isChoked ? "(choked)" : "") + "\n* " +
                        "UnACKed: " + unacked + " Window Size: " + wsz + " Active resends: " + _activeResends +
                        " Time left: " + timeLeft + "ms");
        }
        return (timeLeft > 0);
    }

    /**
     *  Notify all threads waiting in packetSendChoke()
     */
    void windowAdjusted() {
        synchronized (_outboundPackets) {_outboundPackets.notifyAll();}
    }

    void ackImmediately() {
        PacketLocal packet;
        // if we don't have anything to retransmit, send a small ACK
        // this calls sendPacket() below
        packet = _receiver.send(null, 0, 0);
        if (_log.shouldDebug()) {_log.debug("Sending new ACK: " + packet);}
        //packet.releasePayload();
    }

    /**
     * Got a packet we shouldn't have, send 'em a reset.
     * More than one reset may be sent.
     */
    private void sendReset() {
        long now = _context.clock().now();
        if (_resetSentOn.get() + 10*1000 > now) {return;} // don't send resets too fast
        if (_resetReceived.get()) {return;}
        // Unconditionally set
        _resetSentOn.set(now);
        Destination remotePeer;
        synchronized(this) {
            remotePeer = _remotePeer;
        }
        if ((remotePeer == null) || (_sendStreamId.get() <= 0)) {return;}
        PacketLocal reply = new PacketLocal(_context, remotePeer, this);
        reply.setFlag(Packet.FLAG_RESET);
        reply.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        reply.setSendStreamId(_sendStreamId.get());
        reply.setReceiveStreamId(_receiveStreamId.get());
        // As of 0.9.20 we do not require FROM
        reply.setLocalPort(_localPort);
        reply.setRemotePort(_remotePort);
        // this just sends the packet - no retries or whatnot
        if (_outboundQueue.enqueue(reply)) {
            _unackedPacketsReceived.set(0);
            _lastSendTime = now;
            resetActivityTimer();
        }
    }

    /**
     * Flush any data that we can. Non-blocking.
     */
    void sendAvailable() {
        // this grabs the data, builds a packet, and queues it up via sendPacket
        try {_outputStream.flushAvailable(_receiver, false);}
        catch (IOException ioe) {
            if (_log.shouldError()) {_log.error("Error flushing available", ioe);}
        }
    }

    /**
     *  This sends all 'normal' packets (acks and data) for the first time.
     *  Retransmits are done in ResendPacketEvent below.
     *  Resets, pings, and pongs are done elsewhere in this class,
     *  or in ConnectionManager or ConnectionHandler.
     */
    void sendPacket(PacketLocal packet) {
        if (packet == null) return;

        setNextSendTime(-1);
        if (_options.getRequireFullySigned()) {
            packet.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
            packet.setFlag(Packet.FLAG_SIGNATURE_REQUESTED);
        }

        if ((packet.getSequenceNum() == 0) && (!packet.isFlagSet(Packet.FLAG_SYNCHRONIZE))) {
            // ACK-only
            if (_isChoking) {
                packet.setOptionalDelay(Packet.SEND_DELAY_CHOKE);
                packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            } else if (_unchokesToSend.decrementAndGet() > 0) {
                // don't worry about wrapping around
                packet.setOptionalDelay(0);
                packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            }
        } else {
            int windowSize;
            int remaining;
            synchronized (_outboundPackets) {
                // Defensive: prevent unbounded packet accumulation under memory pressure
                if (_outboundPackets.size() >= MAX_UNACKED_PACKETS) {
                    if (_log.shouldWarn()) {
                        _log.warn("Outbound packet buffer full (" + _outboundPackets.size() +
                                  "), dropping packet " + packet.getSequenceNum());
                    }
                    packet.cancelled();
                    return;
                }
                _outboundPackets.put(Long.valueOf(packet.getSequenceNum()), packet);
                windowSize = _options.getWindowSize();
                remaining = windowSize - _outboundPackets.size() ;
                _outboundPackets.notifyAll();

                if (_isChoking) {
                    packet.setOptionalDelay(Packet.SEND_DELAY_CHOKE);
                    packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                } else if (packet.isFlagSet(Packet.FLAG_CLOSE) ||
                    _unchokesToSend.decrementAndGet() > 0 ||
                    // the other end has no idea what our window size is, so
                    // help him out by requesting acks below the 1/3 point,
                    // if remaining < 3, and every 8 minimum.
                    (remaining < 3) ||
                    (remaining < (windowSize + 2) / 3) /* ||
                    (packet.getSequenceNum() % 8 == 0) */ ) {
                    packet.setOptionalDelay(0);
                    packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                    //if (_log.shouldDebug())
                    //    _log.debug("Requesting no ack delay for packet " + packet);
                }

                int timeout = _options.getRTO();

                // RFC 6298 section 5.1
                if (_retransmitEvent.scheduleIfNotRunning(timeout)) {
                   if (_log.shouldDebug()) {
                       _log.debug("[" + Connection.this + "] Resend in " + timeout + "ms for " + packet);
                   }

                } else {
                    if (_log.shouldDebug()) {
                        _log.debug("[" + Connection.this + "] Timer was already running!");
                    }
                }

                packet.setTimeout(timeout);
            }
        }

        // warning, getStatLog() can be null
        //_context.statManager().getStatLog().addData(Packet.toId(_sendStreamId), "stream.rtt", _options.getRTT(), _options.getWindowSize());

        // Apply pacing to smooth transmission
        long pacingDelay = calculatePacingDelay(packet.getPayloadSize());
        if (pacingDelay > 0) {
            // Schedule packet with pacing delay
            PacedPacketEvent pacedEvent = new PacedPacketEvent(packet);
            pacedEvent.schedule(pacingDelay);
        } else {
            // Send immediately
            enqueuePacket(packet);
        }
    }

    /**
     * Actually enqueue the packet after pacing delay.
     */
    private void enqueuePacket(PacketLocal packet) {
        if (_outboundQueue.enqueue(packet)) {
            synchronized (_pacingLock) {
                _lastPacketSendTime = _context.clock().now();
            }
            _unackedPacketsReceived.set(0);
            _lastSendTime = _context.clock().now();
            resetActivityTimer();
        }
    }

    /**
     *  Process the acks and nacks received in a packet
     *  @return List of packets acked for the first time, or null if none
     */
    public List<PacketLocal> ackPackets(long ackThrough, long nacks[]) {
        // FIXME synch this part too?
        if (ackThrough < _highestAckedThrough) {
            // dupack which won't tell us anything
        } else {
           if (nacks == null) {
                _highestAckedThrough = ackThrough;
            } else {
                long lowest = -1;
                for (int i = 0; i < nacks.length; i++) {
                    if ((lowest < 0) || (nacks[i] < lowest)) {lowest = nacks[i];}
                }
                if (lowest - 1 > _highestAckedThrough) {_highestAckedThrough = lowest - 1;}
            }
        }

        List<PacketLocal> acked = null;
        boolean anyLeft = false;
        synchronized (_outboundPackets) {
            if (!_outboundPackets.isEmpty()) {  // short circuit iterator
                for (Iterator<Map.Entry<Long, PacketLocal>> iter = _outboundPackets.entrySet().iterator(); iter.hasNext(); ) {
                    Map.Entry<Long, PacketLocal> e = iter.next();
                    long id = e.getKey().longValue();
                    if (id <= ackThrough) {
                        boolean nacked = false;
                        if (nacks != null) {
                            // linear search since its probably really tiny
                            for (int i = 0; i < nacks.length; i++) {
                                if (nacks[i] == id) {
                                    nacked = true;
                                    PacketLocal nackedPacket = e.getValue();
                                    // this will do a fast retransmit if appropriate
                                    nackedPacket.incrementNACKs();
                                    break; // NACKed
                                }
                            }
                        }
                        if (!nacked) { // aka ACKed
                            if (acked == null) {acked = new ArrayList<PacketLocal>(8);}
                            PacketLocal ackedPacket = e.getValue();
                            ackedPacket.ackReceived();
                            acked.add(ackedPacket);
                            iter.remove();
                        }
                    } else {
                        /*
                         * TODO
                         * we do not currently do an "implicit nack" of the packets higher
                         * than ackThrough, so those will not be fast retransmitted
                         * we could incrementNACK them here... but we may need to set the fastRettransmit
                         * threshold back to 3 for that.
                         * this will do a fast retransmit if appropriate
                         * This doesn't work because every packet has an ACK in it, so we hit the
                         * FAST_TRANSMIT threshold in a heartbeat and retransmit everything,
                         * even with the threshold at 3. (we never set the NO_ACK field in the header)
                         * Also, we may need to track that we
                         * have the same ackThrough for 3 or 4 consecutive times.
                         * See https: *secure.wikimedia.org/wikipedia/en/wiki/Fast_retransmit
                         */
                        //if (_log.shouldInfo())
                        //    _log.info("ACK thru " + ackThrough + " implicitly NACKs " + id);
                        //PacketLocal nackedPacket = e.getValue();
                        //nackedPacket.incrementNACKs();
                        break; // _outboundPackets is ordered
                    }
                } // for
            } // !isEmpty()
            if (acked != null) {
                _ackedPackets.addAndGet(acked.size());
                for (int i = 0; i < acked.size(); i++) {
                    PacketLocal p = acked.get(i);
                    // removed from _outboundPackets above in iterator
                    if (p.getNumSends() > 1) {
                        _activeResends.decrementAndGet();
                        if (_log.shouldDebug()) {
                            _log.debug("Active resend of " + p + " successful -> " + _activeResends + " resends remaining...");
                        }
                    }
                }
            }
            if ((_outboundPackets.isEmpty()) && (_activeResends.get() != 0)) {
                if (_log.shouldInfo()) {
                    _log.info("All outbound packets ACKed, clearing " + _activeResends);
                }
                _activeResends.set(0);
            }

            anyLeft = !_outboundPackets.isEmpty();
            _outboundPackets.notifyAll();

            if ((acked != null) && (!acked.isEmpty())) {
                _ackSinceCongestion.set(true);
                _bwEstimator.addSample(acked.size());
                if (anyLeft) {
                    // RFC 6298 section 5.3
                    int rto = _options.getRTO();
                    _retransmitEvent.pushBackRTO(rto);

                    if (_log.shouldDebug()) {
                        _log.debug("[" + Connection.this + "] Not all packets ACKed, pushing timer out " + rto);
                    }
                } else {
                    // RFC 6298 section 5.2
                    if (_log.shouldDebug()) {
                        _log.debug("[" + Connection.this + "] All outstanding packets ACKed, cancelling timer");
                    }

                    _retransmitEvent.cancel();
                }
            }
        }
        return acked;
    }

    void eventOccurred() {
        TaskScheduler sched = _chooser.getScheduler(this);
        long before = System.currentTimeMillis();

        sched.eventOccurred(this);
        long elapsed = System.currentTimeMillis() - before;
        // 250 and warn for debugging
        if ((elapsed > 250) && (_log.shouldWarn())) {
            _log.warn("Took " + elapsed + "ms to pump through " + sched + " on " + toString());
        }
    }

    /**
     *  Notify that a close was sent.
     *  Called by CPH.
     *  May be called multiple times... but shouldn't be.
     */
    public void notifyCloseSent() {
        if (!_closeSentOn.compareAndSet(0, _context.clock().now())) {
            // TODO ackImmediately() after sending CLOSE causes this. Bad?
            if (_log.shouldDebug()) {_log.debug("Sent more than one CLOSE: " + toString());}
        }
        // that's it, wait for notifyLastPacketAcked() or closeReceived()
    }

    /**
     *  Notify that a close was received.
     *  Called by CPH.
     *  May be called multiple times.
     */
    public void closeReceived() {
        if (_closeReceivedOn.compareAndSet(0, _context.clock().now())) {
            _inputStream.closeReceived();
            // TODO if outbound && no SYN received, treat like a reset? Could this happen?
            if (_closeSentOn.get() > 0) {disconnect(true);} // received after sent
            else {
                synchronized (_connectLock) {_connectLock.notifyAll();}
            }
        }
    }

    /**
     *  Notify that a close that we sent, and all previous packets, were acked.
     *  Called by CPH. Only call this once.
     *  @since 0.9.9
     */
    public void notifyLastPacketAcked() {
        long cso = _closeSentOn.get();
        if (cso <= 0) {throw new IllegalStateException();}
        // we only create one CLOSE packet so we will only get called once,
        // no need to check
        long cro = _closeReceivedOn.get();
        if (cro > 0 && cro < cso) {disconnect(true);} // received before sent
    }

    /**
     *  Notify that a reset was received.
     *  May be called multiple times.
     */
    public void resetReceived() {
        if (!_resetReceived.compareAndSet(false, true)) {return;}
        IOException ioe = new I2PSocketException(I2PSocketException.STATUS_CONNECTION_RESET);
        _outputStream.streamErrorOccurred(ioe);
        _inputStream.streamErrorOccurred(ioe);
        _connectionError = "Connection reset";
        synchronized (_connectLock) {_connectLock.notifyAll();}
        // RFC 793 end of section 3.4: We are completely done.
        disconnectComplete();
    }

    public boolean getResetReceived() {return _resetReceived.get();}

    public boolean isInbound() {return _isInbound;}

    /**
     *  Always true at the start, even if we haven't gotten a reply on an
     *  outbound connection. Only set to false on disconnect.
     *  For outbound, use getHighestAckedThrough() &gt;= 0 also,
     *  to determine if the connection is up.
     *
     *  In general, this is true until either:
     *  - CLOSE received and CLOSE sent and our CLOSE is acked
     *  - RESET received or sent
     *  - closed on the socket side
     */
    public boolean getIsConnected() {return _connected.get();}

    public boolean getHardDisconnected() {return _hardDisconnected;}

    public boolean getResetSent() {return _resetSentOn.get() > 0;}

    /** @return 0 if not sent */
    public long getResetSentOn() {return _resetSentOn.get();}

    /** @return 0 if not scheduled */
    public long getDisconnectScheduledOn() {return _disconnectScheduledOn.get();}

    /**
     *  Must be called when we are done with this connection.
     *  Enters TIME-WAIT if necessary, and removes from connection manager.
     *  May be called multiple times.
     *  This closes the socket side.
     *  In normal operation, this is called when a CLOSE has been received,
     *  AND a CLOSE has been sent, AND EITHER:
     *  received close before sent close AND our CLOSE has been acked
     *  OR
     *  received close after sent close.
     *
     *  @param cleanDisconnect if true, normal close; if false, send a RESET
     */
    public void disconnect(boolean cleanDisconnect) {
        disconnect(cleanDisconnect, true);
    }

    /**
     *  Must be called when we are done with this connection.
     *  May be called multiple times.
     *  This closes the socket side.
     *  In normal operation, this is called when a CLOSE has been received,
     *  AND a CLOSE has been sent, AND EITHER:
     *  received close before sent close AND our CLOSE has been acked
     *  OR
     *  received close after sent close.
     *
     *  @param cleanDisconnect if true, normal close; if false, send a RESET
     *  @param removeFromConMgr if true, enters TIME-WAIT if necessary.
     *                          if false, MUST call disconnectComplete() later.
     *                          Should always be true unless called from ConnectionManager.
     */
    public void disconnect(boolean cleanDisconnect, boolean removeFromConMgr) {
        if (!_connected.compareAndSet(true, false)) {
            return;
        }
        synchronized (_connectLock) {_connectLock.notifyAll();}

        int disconnectCount = 0;

        if (_closeReceivedOn.get() <= 0) {
            // should have already been called from closeReceived() above
            _inputStream.closeReceived();
        }

        if (cleanDisconnect) {
            if (_log.shouldDebug()) {
                _log.debug("Clean disconnecting from " + getRemotePeerString() + " -> " +
                           (removeFromConMgr ? "Removed from Connection Manager" : "Not removed from Connection Manager") +
                           "\n* " + toString(), new Exception("Disconnected"));
            }
            _outputStream.closeInternal();
        } else {
            _hardDisconnected = true;
            if (_inputStream.getHighestBlockId() >= 0 && !getResetReceived()) {
                // only send a RESET if we ever got something (and he didn't RESET us),
                // otherwise don't waste the crypto and tags
                if (_log.shouldInfo()) {
                    _log.warn("Hard disconnecting " + (disconnectCount > 1 ? "(Count: " + disconnectCount + ") " : "") +
                              "and sending RESET to " + getRemotePeerString() + " -> " +
                              (removeFromConMgr ? "Removed from Connection Manager" : "Not removed from Connection Manager"));
                }
                sendReset();
                disconnectCount++;
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Hard disconnecting " + (disconnectCount > 1 ? "(Count: " + disconnectCount + ") " : "") +
                              "from " + getRemotePeerString() + " -> " +
                              (removeFromConMgr ? "Removed from Connection Manager" : "Not removed from Connection Manager"));
                }
            }
            _outputStream.streamErrorOccurred(new IOException("Hard disconnect"));
            disconnectCount++;
        }

        if (removeFromConMgr) {
            if (!cleanDisconnect) {disconnectComplete();}
            else {
                long cro = _closeReceivedOn.get();
                long cso = _closeSentOn.get();
                if (cro > 0 && cro < cso && getUnackedPacketsSent() <= 0) {
                    if (_log.shouldInfo()) {
                        _log.info("Rcv close -> send close -> last ACKed, skip TIME-WAIT for " + toString());
                    }
                    // They sent the first CLOSE.
                    // We do not need to enter TIME-WAIT, we are done.
                    // clean disconnect, don't schedule TIME-WAIT
                    // remove conn
                    disconnectComplete();
                } else {scheduleDisconnectEvent();}
            }
        }
    }

    /**
     *  Must be called when we are done with this connection.
     *  Final disconnect. Remove from conn manager.
     *  May be called multiple times.
     */
    public void disconnectComplete() {
        if (!_finalDisconnect.compareAndSet(false, true)) {return;}
        _connected.set(false);
        I2PSocketFull s = _socket;
        if (s != null) {
            s.destroy2();
            _socket = null;
        }
        _outputStream.destroy();
        _receiver.destroy();
        _activityTimer.cancel();
        _retransmitEvent.cancel();
        _inputStream.streamErrorOccurred(new IOException("Socket closed"));

        if (_log.shouldInfo()) {_log.info("Connection disconnect complete\n" + toString());}
        _connectionManager.removeConnection(this);
        killOutstandingPackets();
    }

    /**
     *  Cancel and remove all packets awaiting ack
     */
    private void killOutstandingPackets() {
        synchronized (_outboundPackets) {
            if (_outboundPackets.isEmpty()) {return;} // short circuit iterator
            for (PacketLocal pl : _outboundPackets.values()) {pl.cancelled();}
            _outboundPackets.clear();
            _outboundPackets.notifyAll();
        }
    }

    /**
     *  Schedule the end of the TIME-WAIT state,
     *  but only if not previously scheduled.
     *  Must call either this or disconnectComplete()
     *
     *  @return true if a new event was scheduled; false if already scheduled
     *  @since 0.9.9
     */
    private boolean scheduleDisconnectEvent() {
        if (!_disconnectScheduledOn.compareAndSet(0, _context.clock().now())) {return false;}
        schedule(new DisconnectEvent(), DISCONNECT_TIMEOUT);
        return true;
    }

    private class DisconnectEvent implements SimpleTimer.TimedEvent {
        public DisconnectEvent() {
            if (_log.shouldInfo()) {
                _log.info("Disconnect timer initiated on connection to " + getRemotePeerString() + "-> 5 minutes to drop...");
            }
        }
        public void timeReached() {disconnectComplete();}
    }

    /**
     *  Called from SchedulerImpl
     *
     *  @since 0.9.23 moved here so we can use our timer
     */
    public void scheduleConnectionEvent(long msToWait) {
        schedule(_connectionEvent, msToWait);
    }

    /**
     *  Schedule something on our timer.
     *
     *  @since 0.9.23
     */
    public void schedule(SimpleTimer.TimedEvent event, long msToWait) {
        _timer.addEvent(event, msToWait);
    }

    /** who are we talking with
     * @return peer Destination or null if unset
     */
    public synchronized Destination getRemotePeer() {return _remotePeer;}

    private synchronized String getRemotePeerString() {
        if (_remotePeer != null) {return "[" + _remotePeer.calculateHash().toBase32().substring(0,8) + "]";}
        else {return "[Unknown]";}
    }

    /**
     *  @param peer non-null
     */
    public void setRemotePeer(Destination peer) {
        if (peer == null) {throw new NullPointerException();}
        synchronized(this) {
            if (_remotePeer != null) {
                throw new RuntimeException("Remote peer already set [" + _remotePeer + ", " + peer + "]");
            }
            _remotePeer = peer;
        }
        // now that we know who the other end is, get the rtt etc. from the cache
        _connectionManager.updateOptsFromShare(this);
    }

    /**
     *  The key to verify signatures with.
     *  The transient SPK if previously received,
     *  else getRemotePeer().getSigningPublicKey() if previously received,
     *  else null.
     *
     *  @return peer Destination or null if unset
     *  @since 0.9.39
     */
    public synchronized SigningPublicKey getRemoteSPK() {
        if (_transientSPK != null) {return _transientSPK;}
        if (_remotePeer != null) {return _remotePeer.getSigningPublicKey();}
        return null;
    }

    /**
     *  @param transientSPK null ok
     *  @since 0.9.39
     */
    public void setRemoteTransientSPK(SigningPublicKey transientSPK) {
        synchronized(this) {
            if (_transientSPK != null) {
                throw new RuntimeException("Remote Signing Public Key already set");
            }
            _transientSPK = transientSPK;
        }
    }

    /**
     *  What stream do we send data to the peer on?
     *  @return non-global stream sending ID, or 0 if unknown
     */
    public long getSendStreamId() {return _sendStreamId.get();}

    /**
     *  @param id 0 to 0xffffffff
     *  @throws IllegalStateException if already set to nonzero
     */
    public void setSendStreamId(long id) {
        if (!_sendStreamId.compareAndSet(0, id)) {
            throw new IllegalStateException("Send Stream ID already set [" + _sendStreamId + ", " + id + "]");
        }
    }

    /**
     *  The stream ID of a peer connection that sends data to us, or zero if unknown.
     *  @return receive stream ID, or 0 if unknown
     */
    public long getReceiveStreamId() {return _receiveStreamId.get();}

    /**
     *  @param id 0 to 0xffffffff
     *  @throws IllegalStateException if already set to nonzero
     */
    public void setReceiveStreamId(long id) {
        if (!_receiveStreamId.compareAndSet(0, id)) {
            throw new IllegalStateException("Receive Stream ID already set [" + _receiveStreamId + ", " + id + "]");
        }
        synchronized (_connectLock) {_connectLock.notifyAll();}
    }

    /** When did we last send anything to the peer?
     * @return Last time we sent data
     */
    public long getLastSendTime() {return _lastSendTime;}

    /** What was the last packet Id sent to the peer?
     * @return The last sent packet ID
     */
    public long getLastSendId() {return _lastSendId.get();}
    /** Set the packet Id that was sent to a peer.
     * @param id The packet ID
     */
    public void setLastSendId(long id) {_lastSendId.set(id);}

    /**
     * Retrieve the current ConnectionOptions.
     * @return the current ConnectionOptions, non-null
     */
    public ConnectionOptions getOptions() {return _options;}
    /**
     * Set the ConnectionOptions.
     * @param opts ConnectionOptions non-null
     */
    public void setOptions(ConnectionOptions opts) {_options = opts;}

    /** @since 0.9.21 */
    public ConnectionManager getConnectionManager() {return _connectionManager;}

    public I2PSession getSession() {return _session;}
    public I2PSocketFull getSocket() {return _socket;}
    public void setSocket(I2PSocketFull socket) {_socket = socket;}

    /**
     * The remote port.
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getPort() {return _remotePort;}

    /**
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getLocalPort() {return _localPort;}

    public String getConnectionError() {return _connectionError;}
    public void setConnectionError(String err) {_connectionError = err;}

    public long getLifetime() {
        long cso = _closeSentOn.get();
        if (cso <= 0) {return _context.clock().now() - _createdOn;}
        else {return cso - _createdOn;}
    }

    public ConnectionPacketHandler getPacketHandler() {return _handler;}

    public long getLifetimeBytesSent() {return _lifetimeBytesSent.get();}
    public long getLifetimeBytesReceived() {return _lifetimeBytesReceived.get();}
    public long getLifetimeDupMessagesSent() {return _lifetimeDupMessageSent.get();}
    public long getLifetimeDupMessagesReceived() {return _lifetimeDupMessageReceived.get();}
    public void incrementBytesSent(int bytes) {_lifetimeBytesSent.addAndGet(bytes);}
    public void incrementDupMessagesSent(int msgs) {_lifetimeDupMessageSent.addAndGet(msgs);}
    public void incrementBytesReceived(int bytes) {_lifetimeBytesReceived.addAndGet(bytes);}
    public void incrementDupMessagesReceived(int msgs) {_lifetimeDupMessageReceived.addAndGet(msgs);}

    /**
     * Time when the scheduler next want to send a packet, or -1 if
     * never.  This should be set when we want to send on timeout, for
     * instance, or want to delay an ACK.
     * @return the next time the scheduler will want to send a packet, or -1 if never.
     */
    public long getNextSendTime() {
        synchronized(_nextSendLock) {return _nextSendTime;}
    }

    /**
     *  If the next send time is currently &gt;= 0 (i.e. not "never"),
     *  this may make the next time sooner but will not make it later.
     *  If the next send time is currently &lt; 0 (i.e. "never"),
     *  this will set it to the time specified, but not later than
     *  options.getSendAckDelay() from now (1000 ms)
     */
    public void setNextSendTime(long when) {
        synchronized(_nextSendLock) {
            if (_nextSendTime >= 0) {
                if (when < _nextSendTime) {_nextSendTime = when;}
            } else {_nextSendTime = when;}

            if (_nextSendTime >= 0) {
                long max = _context.clock().now() + _options.getSendAckDelay();
                if (max < _nextSendTime) {_nextSendTime = max;}
            }
        }
    }

    /**
     *  Set or clear if we are choking the other side.
     *  If on is true or the value has changed, this will call ackImmediately().
     *  @param on true for choking
     *  @since 0.9.29
     */
    public void setChoking(boolean on) {
        if (on != _isChoking) {
            _isChoking = on;
           if (_log.shouldWarn()) {_log.warn("Choking changed to " + on + " on " + this);}
           if (!on) {_unchokesToSend.set(UNCHOKES_TO_SEND);}
           ackImmediately();
        } else if (on) {ackImmediately();}
    }

    /**
     *  Set or clear if we are being choked by the other side.
     *  @param on true for choked
     *  @since 0.9.29
     */
    public void setChoked(boolean on) {
        if (on != _isChoked) {
           _isChoked = on;
           if (_log.shouldWarn()) {_log.warn("Choked changed to " + on + " on " + this);}
        }
        if (on) {
            congestionOccurred();
            /*
             * https: *en.wikipedia.org/wiki/Transmission_Control_Protocol
             * When a receiver advertises a window size of 0, the sender stops sending data and starts the persist timer.
             *
             * The persist timer is used to protect TCP from a deadlock situation that could arise if a subsequent window
             * size update from the receiver is lost, and the sender cannot send more data until receiving a new window
             * size update from the receiver.
             *
             * When the persist timer expires, the TCP sender attempts recovery by sending a small packet
             * so that the receiver responds by sending another acknowledgement containing the new window size.
             * ...
             * We don't do any of that, but we set the window size to 1, and let the retransmission
             * of packets do the "attempted recovery".
             */
            _options.setWindowSize(1);
            updatePacingRate(); // Update pacing when window changes
        }
    }

    /**
     *  Is the other side choking us?
     *  @return if choked
     *  @since 0.9.29
     */
    public boolean isChoked() {return _isChoked;}

    /** how many packets have we sent and the other side has ACKed?
     * @return Count of how many packets ACKed.
     */
    public long getAckedPackets() {return _ackedPackets.get();}
    public long getCreatedOn() {return _createdOn;}

    /** @return 0 if not sent */
    public long getCloseSentOn() {return _closeSentOn.get();}

    /** @return 0 if not received */
    public long getCloseReceivedOn() {return _closeReceivedOn.get();}

    public void updateShareOpts() {
        if (_closeSentOn.get() > 0 && !_updatedShareOpts) {
            _connectionManager.updateShareOpts(this);
            _updatedShareOpts = true;
        }
    }

    public void incrementUnackedPacketsReceived() {_unackedPacketsReceived.incrementAndGet();}
    public int getUnackedPacketsReceived() {return _unackedPacketsReceived.get();}

    /** how many packets have we sent but not yet received an ACK for?
     * @return Count of packets in-flight.
     */
    public int getUnackedPacketsSent() {
        synchronized (_outboundPackets) {return _outboundPackets.size();}
    }

    public long getCongestionWindowEnd() {return _congestionWindowEnd;}
    public void setCongestionWindowEnd(long endMsg) {_congestionWindowEnd = endMsg;}

    /** @return the highest outbound packet we have received an ack for */
    public long getHighestAckedThrough() {return _highestAckedThrough;}

    public long getLastActivityOn() {
        return (_lastSendTime > _lastReceivedOn ? _lastSendTime : _lastReceivedOn);
    }

    private void congestionOccurred() {
        // if we hit congestion and e.g. 5 packets are resent,
        // Don't set the size to (winSize >> 4).  only set the
        if (_ackSinceCongestion.compareAndSet(true,false)) {
            _lastCongestionTime = _context.clock().now();
            _lastCongestionHighestUnacked = _lastSendId.get();
        }
    }

    void packetReceived() {
        _lastReceivedOn = _context.clock().now();
        resetActivityTimer();
        synchronized (_connectLock) {_connectLock.notifyAll();}
    }

    /**
     * wait until a connection is made or the connection fails within the
     * timeout period, setting the error accordingly.
     */
    void waitForConnect() {
        long now = _context.clock().now();
        long expiration = now + _options.getConnectTimeout();
        while (true) {
            if (_connected.get() && (_receiveStreamId.get() > 0) && (_sendStreamId.get() > 0)) {
                // w00t
                if (_log.shouldDebug()) {_log.debug("waitForConnect(): Connected and we have Stream IDs");}
                return;
            }
            if (_connectionError != null) {
                if (_log.shouldDebug()) {
                    _log.debug("waitForConnect(): connection error found: " + _connectionError);
                }
                return;
            }
            if (!_connected.get()) {
                _connectionError = "Connection failed";
                if (_log.shouldDebug()) {_log.debug("waitForConnect(): not connected");}
                return;
            }

            long timeLeft = expiration - now;
            if ((timeLeft <= 0) && (_options.getConnectTimeout() > 0)) {
                if (_connectionError == null) {
                    _connectionError = "Connection timed out";
                    disconnect(false);
                }
                if (_log.shouldDebug()) {
                    _log.debug("waitForConnect(): timed out: " + _connectionError);
                }
                return;
            }
            if (timeLeft > MAX_CONNECT_TIMEOUT) {timeLeft = MAX_CONNECT_TIMEOUT;}
            else if (_options.getConnectTimeout() <= 0) {timeLeft = DEFAULT_CONNECT_TIMEOUT;}

            if (_log.shouldDebug()) {_log.debug("waitForConnect(): wait " + timeLeft);}
            try {
                synchronized (_connectLock) {_connectLock.wait(timeLeft);}
            } catch (InterruptedException ie) {
                if (_log.shouldDebug()) _log.debug("waitForConnect(): InterruptedException");
                _connectionError = "InterruptedException";
                return;
            }
        }
    }

    private void resetActivityTimer() {
        long howLong = _options.getInactivityTimeout();
        if (howLong <= 0) {return;}
        howLong += _randomWait; // randomize it a bit, so both sides don't do it at once
        _activityTimer.reschedule(howLong, false); // use the later of current and previous timeout
    }

    private class ActivityTimer extends SimpleTimer2.TimedEvent {
        public ActivityTimer() {
            super(_timer);
            setFuzz(5*1000); // sloppy timer, don't reschedule unless at least 5s later
        }
        public void timeReached() {
            if (_log.shouldDebug()) {
                _log.debug("Invoking inactivity timer on connection to " + getRemotePeerString() + "...");
            }
            // uh, nothing more to do...
            if (!_connected.get()) {
                if (_log.shouldDebug()) _log.debug("Inactivity timeout reached, but we are already closed!");
                return;
            }
            // we got rescheduled already
            long left = getTimeLeft();
            if (left > 0) {
                if (_log.shouldDebug()) {
                    _log.debug("Inactivity timeout reached on connection to " + getRemotePeerString() +
                               " but there is time left (" + left + "ms)");
                }
                schedule(left);
                return;
            }
            // these are either going to time out or cause further rescheduling
            if (getUnackedPacketsSent() > 0) {
                if (_log.shouldDebug()) {
                    _log.debug("Inactivity timeout reached on connection to " + getRemotePeerString() +
                               " but there are unACKed packets!");
                }
                return;
            }
            // this shouldn't have been scheduled
            if (_options.getInactivityTimeout() <= 0) {
                if (_log.shouldDebug()) {
                    _log.debug("Inactivity timeout reached on connection to " +
                               getRemotePeerString() + " but there is no timer!");
                }
                return;
            }

            if (_log.shouldDebug()) {
                _log.debug("Inactivity timeout reached on connection to " + getRemotePeerString() +
                           " -> " + _options.getInactivityAction());
            }

            // bugger it, might as well do the hard work now
            switch (_options.getInactivityAction()) {
                case ConnectionOptions.INACTIVITY_ACTION_NOOP:
                    if (_log.shouldInfo()) {
                        _log.info("Inactivity timer expired on connection to " + getRemotePeerString() +
                                  " -> Not doing anything!");
                    }
                    break;
                case ConnectionOptions.INACTIVITY_ACTION_SEND:
                    if (_closeSentOn.get() <= 0 && _closeReceivedOn.get() <= 0) {
                        if (_log.shouldInfo()) {
                            _log.info("Sending some data to " + getRemotePeerString() + " due to inactivity...");
                        }
                        _receiver.send(null, 0, 0, true);
                        break;
                    } // else fall through
                case ConnectionOptions.INACTIVITY_ACTION_DISCONNECT:
                    // fall through
                default:
                    if (_log.shouldInfo()) {
                        int timeout = _options.getInactivityTimeout() / 1000;
                        _log.info("Closing INACTIVE connection to " + getRemotePeerString() + " -> " + timeout + "s timeout reached");
                    }
                    if (_log.shouldDebug()) {
                        StringBuilder buf = new StringBuilder(128);
                        long now = _context.clock().now();
                        buf.append("Last sent packet: ").append(now - _lastSendTime);
                        buf.append("ms ago, last received: ").append(now -_lastReceivedOn);
                        buf.append("ms ago -> Inactivity timeout is: ").append(_options.getInactivityTimeout());
                        _log.debug(buf.toString());
                    }

                    IOException ioe = new IOException("Inactivity timeout");
                    _inputStream.streamErrorOccurred(ioe);
                    _outputStream.streamErrorOccurred(ioe);
                    // Clean disconnect if we have already scheduled one
                    // (generally because we already sent a close)
                    disconnect(_disconnectScheduledOn.get() > 0);
                    break;
            }
        }

        public final long getTimeLeft() {
            if (getLastActivityOn() > 0) {
                return getLastActivityOn() + _options.getInactivityTimeout() - _context.clock().now();
            } else {
                return _createdOn + _options.getInactivityTimeout() - _context.clock().now();
            }
        }
    }

    /** stream that the local peer receives data on
     * @return the inbound message stream, non-null
     */
    public MessageInputStream getInputStream() {return _inputStream;}

    /** stream that the local peer sends data to the remote peer on
     * @return the outbound message stream, non-null
     */
    public MessageOutputStream getOutputStream() {return _outputStream;}

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("Connection: ");
        long id = _receiveStreamId.get();
        if (id > 0) {buf.append(Packet.toId(id));}
        else {buf.append("Unknown");}
        buf.append('/');
        id = _sendStreamId.get();
        if (id > 0) {buf.append(Packet.toId(id));}
        else {buf.append("Unknown");}
        if (_isInbound) {buf.append(" from ");}
        else {buf.append(" to ");}
        Destination remotePeer;
        synchronized(this) {
            remotePeer = _remotePeer;
        }
        if (remotePeer != null) {buf.append("[").append(remotePeer.calculateHash().toBase32().substring(0,8)).append("]");}
        else {buf.append("Unknown");}
        long now = _context.clock().now();
        if  (_log.shouldInfo()) {
            buf.append("\n* Up: ").append(DataHelper.formatDuration(now - _createdOn));
            buf.append("; Window size: ").append(_options.getWindowSize());
            buf.append("; Congestion window: ").append(_congestionWindowEnd - _highestAckedThrough);
            buf.append("; RTT: ").append(_options.getRTT());
            buf.append("; RTO: ").append(_options.getRTO());
            // not synchronized to avoid some kooky races
            buf.append("; UnACKed out: ").append(_outboundPackets.size()).append("; ");
            buf.append("UnACKed in: ").append(getUnackedPacketsReceived());
            int missing = 0;
            long nacks[] = _inputStream.getNacks();
            if (nacks != null) {
                missing = nacks.length;
                buf.append(" [").append(missing).append(" missing]");
            }
            buf.append("\n* Sent: ").append(1 + _lastSendId.get());
            buf.append("; Received: ").append(1 + _inputStream.getHighestBlockId() - missing);
            buf.append("; ACKThru: ").append(_highestAckedThrough);
            buf.append("; SSThresh: ").append(_ssthresh);
            buf.append("; MinRTT: ").append(_options.getMinRTT());
            buf.append("; MaxWin: ").append(_options.getMaxWindowSize());
            buf.append("; MTU: ").append(_options.getMaxMessageSize());
            if (getResetSent())
                buf.append("\n* Reset sent: ").append(DataHelper.formatDuration(now - getResetSentOn())).append(" ago");
            if (getResetReceived())
                buf.append("\n* Reset received: ").append(DataHelper.formatDuration(now - getDisconnectScheduledOn())).append(" ago");
            if (getCloseSentOn() > 0) {
                buf.append("\n* Close sent: ");
                long timeSinceClose = now - getCloseSentOn();
                buf.append(DataHelper.formatDuration(timeSinceClose));
                buf.append(" ago");
            }
            if (getCloseReceivedOn() > 0)
                buf.append("\n* Close received: ").append(DataHelper.formatDuration(now - getCloseReceivedOn())).append(" ago");
        }
        return buf.toString();
    }

    /**
     *  A single retransmit timer for all packets.
     *  See RFCs 5681 and 6298.
     *
     *  @since 0.9.46
     */
    class RetransmitEvent extends SimpleTimer2.TimedEvent {

        private boolean _scheduled;

        RetransmitEvent() {super(_timer);}

        @Override
        public synchronized boolean cancel() {
            _scheduled = false;
            return super.cancel();
        }

        public synchronized boolean scheduleIfNotRunning(long delay) {
            if (_scheduled) {return false;}
            _scheduled = true;
            schedule(delay);
            return true;
        }

        public synchronized void pushBackRTO(int rto) {
            if (!_scheduled) {
                // Safety: if we're being asked to push back RTO, we likely have unacked packets.
                // So just schedule it.
                if (_log.shouldInfo()) {
                    _log.info(Connection.this + " Retransmit timer was not scheduled; rescheduling.", new Exception());
                }
                _scheduled = true;
                schedule(rto);
            } else {
                reschedule(rto, false);
            }
        }

        @Override
        public void timeReached() {

           if (_resetSentOn.get() > 0 || _resetReceived.get() || _finalDisconnect.get()) {
                if (_log.shouldDebug()) {
                    _log.debug(Connection.this + " rtx event after close or reset");
                }
                return;
            }

            if (_log.shouldDebug()) {
                _log.debug(Connection.this + " rtx timer timeReached()");
            }

            congestionOccurred();

            // 1. Double RTO and backoff (RFC 6298 section 5.5 & 5.6)
            pushBackRTO(_options.doubleRTO());

            // 2. cut ssthresh to bandwidth estimate, window to 1
            List<PacketLocal> toResend = null;
            synchronized(_outboundPackets) {
                Map.Entry<Long, PacketLocal> e = _outboundPackets.firstEntry();
                if (e == null) {
                    if (_log.shouldWarn()) {
                        _log.warn(Connection.this + " Retransmission timer hit but nothing transmitted??");
                    }
                    return;
                }

                PacketLocal oldest = e.getValue();
                if (oldest.getNumSends() == 1) {
                    if (_log.shouldDebug()) {
                        _log.debug(Connection.this + " cutting SlowStartThreshold and Window");
                    }
                    _ssthresh = Math.max((int)(_bwEstimator.getBandwidthEstimate() * _options.getMinRTT()), 2 );
                    _ssthresh = Math.min(ConnectionPacketHandler.MAX_SLOW_START_WINDOW, _ssthresh);
                    _options.setWindowSize(1);
                } else if (_log.shouldDebug()) {
                    _log.debug(Connection.this + " not cutting SlowStartThreshold and Window");
                }

                toResend = new ArrayList<>(_outboundPackets.values());
                /*
                 * Round down (RFC 5681 section 4.3 "MUST be no more than half")
                 * https://datatracker.ietf.org/doc/html/rfc5681#section-4.3
                 * Priority retransmission: Sort by sequence number (lower = higher priority)
                 * to give preference to SYN packets and head-of-window data.
                 */
                toResend.sort((p1, p2) -> Long.compareUnsigned(p1.getSequenceNum(), p2.getSequenceNum()));
                toResend = toResend.subList(0, Math.max(1, Math.min(MAX_RTX, toResend.size() / 2)));
            }

            // 3. Retransmit up to half of the packets in flight (RFC 6298 section 5.4 and RFC 5681 section 4.3)
            boolean sentAny = false;
            for (PacketLocal packet : toResend) {
                final int nResends = packet.getNumSends();
                if (packet.getNumSends() > _options.getMaxResends()) {
                    if (_log.shouldDebug()) {
                        _log.debug(Connection.this + " packet " + packet + " resent too many times, closing...");
                    }
                    packet.cancelled();
                    disconnect(false);
                    return;
                } else if (packet.getNumSends() >= 3 &&
                           packet.isFlagSet(Packet.FLAG_CLOSE) &&
                           packet.getPayloadSize() <= 0 &&
                           getCloseReceivedOn() > 0) {
                    // Bug workaround to prevent 5 minutes of retransmission
                    // Routers before 0.9.9 have bugs, they won't ack anything after
                    // they sent a close. Only send 3 CLOSE packets total, then
                    // shut down normally.
                    if (_log.shouldDebug()) {
                        _log.debug(Connection.this + " too many close resends, closing...");
                    }
                    packet.cancelled();
                    disconnect(false);
                    return;
                } else {

                    if (_isChoking) {
                        if (_log.shouldDebug()) {
                            _log.debug(Connection.this + " packet is choking " + packet);
                        }
                        packet.setOptionalDelay(Packet.SEND_DELAY_CHOKE);
                        packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                    } else if (_unchokesToSend.decrementAndGet() > 0) {
                        if (_log.shouldDebug()) {
                            _log.debug(Connection.this + " packet is unchoking " + packet);
                        }
                        // Don't worry about wrapping around
                        packet.setOptionalDelay(0);
                        packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                    } else {
                        if (_log.shouldDebug()) {
                            _log.debug(Connection.this + " packet clearing flag " + packet);
                        }
                        // clear flag
                        packet.setFlag(Packet.FLAG_DELAY_REQUESTED, false);
                    }

                    packet.setResendDelay(_options.getResendDelay() / 1000);
                    if (packet.getReceiveStreamId() <= 0) {packet.setReceiveStreamId(_receiveStreamId.get());}
                    if (packet.getSendStreamId() <= 0) {packet.setSendStreamId(_sendStreamId.get());}
                    packet.setTimeout(_options.getRTO());

                    if (_outboundQueue.enqueue(packet)) {
                        if (_log.shouldInfo()) {
                            _log.info(Connection.this + " resent packet " + packet);
                        }
                        if (nResends == 1) {_activeResends.incrementAndGet();}
                        sentAny = true;
                    } else if (_log.shouldDebug()) {
                       _log.debug(Connection.this + " could not resend packet " + packet);
                    }
                }
            }

            if (sentAny) {
                _lastSendTime = _context.clock().now();
                resetActivityTimer();
                windowAdjusted();
            }
        }
    }

    /**
     * Inner class for paced packet transmission
     */
    private class PacedPacketEvent extends SimpleTimer2.TimedEvent {
        private final PacketLocal _packet;

        public PacedPacketEvent(PacketLocal packet) {
            super(_context.simpleTimer2());
            _packet = packet;
        }

        public void timeReached() {
            enqueuePacket(_packet);
        }
    }

    /**
     * fired to reschedule event notification
     */
    class ConEvent implements SimpleTimer.TimedEvent {
        public ConEvent() {}
        public void timeReached() {eventOccurred();}
        @Override
        public String toString() {return "event on connection to " + getRemotePeerString();}
    }

    /**
     * If we have been explicitly NACKed three times, retransmit the packet even if
     * there are other packets in flight.
     */
    static final int FAST_RETRANSMIT_THRESHOLD = 3;

    /**
     * A new ResendPacketEvent.
     * @since 0.9.46
     */
    ResendPacketEvent newResendPacketEvent(PacketLocal packet) {
        return new ResendPacketEvent(packet);
    }

    /**
     * This is not normally scheduled. It's now used only for fastRetransmit(),
     * where it's scheduled with a delay of zero to put it on the timer queue.
     * Timeout retransmissions are handled by RetransmitEvent above.
     */
    class ResendPacketEvent extends SimpleTimer2.TimedEvent {
        private final PacketLocal _packet;

        public ResendPacketEvent(PacketLocal packet) {
            super(_timer);
            _packet = packet;
        }

        public void timeReached() {retransmit();}

        /**
         * @since 0.9.46
         */
        void fastRetransmit() {reschedule(0);}

        /**
         * Retransmit the packet if we need to.
         *
         * ackImmediately() above calls directly in here, so
         * we have to use forceReschedule() instead of schedule() below,
         * to prevent duplicates in the timer queue.
         *
         * Don't synchronize this, deadlock with ackPackets-&gt;ackReceived-&gt;SimpleTimer2.cancel
         *
         * @return true if the packet was sent, false if it was not
         */
        private boolean retransmit() {
            if (_packet.getAckTime() > 0) {return false;}

            if (_resetSentOn.get() > 0 || _resetReceived.get() || _finalDisconnect.get()) {
                _packet.cancelled();
                return false;
            }

            _context.statManager().addRateData("stream.fastRetransmit", _packet.getLifetime(), _packet.getLifetime());

            // revamp various fields, in case we need to ack more, etc
            // updateAcks done in enqueue()
            //_inputStream.updateAcks(_packet);
            if (_isChoking) {
                _packet.setOptionalDelay(Packet.SEND_DELAY_CHOKE);
                _packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            } else if (_unchokesToSend.decrementAndGet() > 0) {
                // don't worry about wrapping around
                _packet.setOptionalDelay(0);
                _packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            } else {
                _packet.setFlag(Packet.FLAG_DELAY_REQUESTED, false); // clear flag
            }

            _packet.setResendDelay(_options.getResendDelay() / 1000);
            if (_packet.getReceiveStreamId() <= 0) {_packet.setReceiveStreamId(_receiveStreamId.get());}
            if (_packet.getSendStreamId() <= 0) {_packet.setSendStreamId(_sendStreamId.get());}

            int newWindowSize = _options.getWindowSize();
            if (_isChoked) {
                congestionOccurred();
                _options.setWindowSize(1);
            } else if (_ackSinceCongestion.get()) {
                // only shrink the window once per window
                if (_packet.getSequenceNum() > _lastCongestionHighestUnacked) {
                    congestionOccurred();
                    _context.statManager().addRateData("stream.con.windowSizeAtCongestion", newWindowSize, _packet.getLifetime());
                    /*
                     * The timeout for _this_ packet will be doubled below, but we also need to double the RTO for the _next_ packets.
                     * See RFC 6298 section 5 item 5.5
                     * This prevents being stuck at a window size of 1, retransmitting every packet,
                     * never updating the RTT or RTO.
                     */
                    _options.doubleRTO();

                    if (_packet.getNumSends() == 1) {
                        _ssthresh = Math.max((int)(_bwEstimator.getBandwidthEstimate() * _options.getMinRTT()), 2);
                        _ssthresh = Math.min(ConnectionPacketHandler.MAX_SLOW_START_WINDOW, _ssthresh);
                        int wsize = _options.getWindowSize();
                        _options.setWindowSize(Math.min(_ssthresh, wsize));
                        updatePacingRate(); // Update pacing when window changes
                    }

                    if (_log.shouldInfo()) {
                        _log.info("Network congestion: Resending packet [" + _packet.getSequenceNum() + "]"
                                      + "\n* New Window Size: " + newWindowSize + "/" + _options.getWindowSize()
                                      + " for " + Connection.this.toString());
                    }
                    windowAdjusted();
                }
            }

            int numSends = _packet.getNumSends() + 1;
            if (numSends - 1 > _options.getMaxResends()) {
                if (_log.shouldDebug()) {_log.debug("Disconnecting, too many resends of " + _packet);}
                _packet.cancelled();
                disconnect(false);
            } else if (numSends >= 3 &&
                       _packet.isFlagSet(Packet.FLAG_CLOSE) &&
                       _packet.getPayloadSize() <= 0 &&
                       _outboundPackets.size() <= 1 &&
                       getCloseReceivedOn() > 0) {
                /*
                 * Bug workaround to prevent 5 minutes of retransmission
                 * Routers before 0.9.9 have bugs, they won't ack anything after
                 * they sent a close. Only send 3 CLOSE packets total, then
                 * shut down normally.
                 */
                if (_log.shouldInfo()) {
                    _log.info("Too many CLOSE resends, disconnecting: " + Connection.this.toString());
                }
                _packet.cancelled();
                disconnect(true);
            } else {
                //long timeout = _options.getResendDelay() << numSends;
                int timeout = _options.getRTO();
                if ((timeout > MAX_RESEND_DELAY) || (timeout <= 0)) {timeout = MAX_RESEND_DELAY;}
                // set this before enqueue() as it passes it on to the router
                _packet.setTimeout(timeout);

                if (_outboundQueue.enqueue(_packet)) {
                    if (_retransmitEvent.scheduleIfNotRunning(timeout)) {
                        if (_log.shouldDebug()) {
                            _log.debug("[" + Connection.this + "] Fast retransmit and schedule timer");
                        }
                    }

                    // first resend for this packet ?
                    if (numSends == 2) {_activeResends.incrementAndGet();}
                    if (_log.shouldInfo()) {
                            _log.info("Resent packet " + _packet +
                                  "(fast) " +
                                  "\n* Next resend in " + (timeout / 1000) + "s" +
                                  "\n* Active resends: " + _activeResends +
                                  "; Window Size: "
                                  + newWindowSize + "; Lifetime: "
                                  + (_context.clock().now() - _packet.getCreatedOn()) + "ms");
                    }
                    _unackedPacketsReceived.set(0);
                    _lastSendTime = _context.clock().now();
                    resetActivityTimer(); // timer reset added 0.9.1
                }
            }

            // ACKed during resending (... or somethin') ????????????
            if ((_packet.getAckTime() > 0) && (_packet.getNumSends() > 1)) {
                _activeResends.decrementAndGet();
                synchronized (_outboundPackets) {
                    _outboundPackets.notifyAll();
                }
            }
            return true;
        }
    }

}