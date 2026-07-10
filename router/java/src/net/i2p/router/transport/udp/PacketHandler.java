package net.i2p.router.transport.udp;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.router.RouterContext;
import net.i2p.router.Tuner;
import net.i2p.router.util.CoDelBlockingQueue;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Pull inbound packets from the inbound receiver's queue, figure out what
 * peer session they belong to (if any), authenticate and decrypt them
 * with the appropriate keys, and push them to the appropriate handler.
 * Data and ACK packets go to the InboundMessageFragments, the various
 * establishment packets go to the EstablishmentManager, and, once implemented,
 * relay packets will go to the relay manager.  At the moment, this is
 * an actual pool of packet handler threads, each pulling off the inbound
 * receiver's queue and pushing them as necessary.
 *
 * Supports dynamic thread count adjustment via {@link #adjustThreads()}.
 * @since 0.9.14 (dynamic resizing since 0.9.70+)
 */
class PacketHandler {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    private final EstablishmentManager _establisher;
    private final PeerTestManager _testManager;
    private volatile boolean _keepReading;
    private final CopyOnWriteArrayList<Handler> _handlers;
    private final AtomicInteger _activeHandlers = new AtomicInteger();
    private final AtomicInteger _processingCount = new AtomicInteger();
    private final BlockingQueue<UDPPacket> _inboundQueue;
    private final int _networkID;

    private static final int TYPE_POISON = -99999;
    private static final int MIN_QUEUE_SIZE = SystemVersion.isSlow() ? 16 : 64;
    private static final int MAX_QUEUE_SIZE = SystemVersion.isSlow() ? 64 : 512;
    private static volatile int _maxHandlers = Math.max(SystemVersion.getCores() / 2, 4);
    private static final AtomicInteger _threadNum = new AtomicInteger();
    private static final int MIN_VERSION = 2;
    private static final int MAX_VERSION = 4;

    PacketHandler(RouterContext ctx, UDPTransport transport, EstablishmentManager establisher,
                  InboundMessageFragments inbound, PeerTestManager testManager, IntroductionManager introManager) {
        _context = ctx;
        _log = ctx.logManager().getLog(PacketHandler.class);
        _transport = transport;
        _establisher = establisher;
        _testManager = testManager;
        _networkID = ctx.router().getNetworkID();

        long maxMemory = SystemVersion.getMaxMemory();
        int qsize = (int) Math.max(MIN_QUEUE_SIZE, Math.min(MAX_QUEUE_SIZE, maxMemory / (2*1024*1024L)));
        _inboundQueue = new CoDelBlockingQueue<>(ctx, "UDP-Receiver", qsize);
        _handlers = new CopyOnWriteArrayList<>();

        _context.statManager().createRateStat("udp.destroyedInvalidSkew", "Session destroyed (bad skew)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.blockedPacketBytes", "Inbound packets blocked (bytes)", "Transport [UDP]", UDPTransport.RATES);
    }

    /**
     * Returns the current max packet handler threads.
     * @since 0.9.70+
     */
    public static int getMaxHandlers() { return _maxHandlers; }

    /**
     * Sets the target max packet handler threads.
     * Takes effect immediately via {@link #adjustThreads()}.
     * @since 0.9.70+
     */
    public static void setMaxHandlers(int handlers) {
        _maxHandlers = Math.max(1, Math.min(16, handlers));
    }

    /**
     * Returns the current number of active handler threads.
     * @since 0.9.70+
     */
    public int getActiveHandlers() { return _activeHandlers.get(); }

    /**
     * Returns the number of handlers actively processing packets (not parked on take()).
     * @since 0.9.70+
     */
    public int getProcessingCount() { return _processingCount.get(); }

    /**
     * Get packet handler pool utilization as a ratio (0.0-1.0).
     * Returns NaN if not started.
     *
     * @since 0.9.70+
     */
    public double getUtilization() {
        int max = getMaxHandlers();
        return max > 0 ? (double) _processingCount.get() / max : Double.NaN;
    }

    public synchronized void startup() {
        _keepReading = true;
        adjustThreads();
    }

    public synchronized void shutdown() {
        _keepReading = false;
        stopQueue();
    }

    /**
     * Dynamically adjust handler thread count to match the target.
     * Handlers are added or removed one at a time. Excess handlers
     * receive a poison packet and exit cleanly.
     * @since 0.9.70+
     */
    void adjustThreads() {
        if (!_keepReading) return;
        int target = _maxHandlers;
        long maxMemory = SystemVersion.getMaxMemory();
        if (maxMemory < 128*1024*1024L) target = 1;
        int current = _activeHandlers.get();
        while (current < target) {
            if (_activeHandlers.compareAndSet(current, current + 1)) {
                Handler h = new Handler();
                _handlers.add(h);
                I2PThread t = new I2PThread(h, "UDPPktHandler." + _threadNum.incrementAndGet(), true);
                t.start();
                current = _activeHandlers.get();
            } else {
                current = _activeHandlers.get();
            }
        }
        while (current > target) {
            if (_activeHandlers.compareAndSet(current, current - 1)) {
                UDPPacket poison = UDPPacket.acquire(_context, false);
                poison.setMessageType(TYPE_POISON);
                _inboundQueue.offer(poison);
                current = _activeHandlers.get();
            } else {
                current = _activeHandlers.get();
            }
        }
    }

    String getHandlerStatus() {
        StringBuilder rv = new StringBuilder();
        rv.append("Handlers: ").append(_activeHandlers.get());
        for (int i = 0; i < _handlers.size(); i++) {
            rv.append(" handler ").append(i);
        }
        return rv.toString();
    }

    /**
     * Blocking call to retrieve the next inbound packet, or null if we have
     * shut down.
     *
     * @since IPv6 moved from UDPReceiver
     */
    public void queueReceived(UDPPacket packet) throws InterruptedException {
        if (_log.shouldDebug()) {_log.debug("Adding packet to queue: " + packet);}
        _inboundQueue.put(packet);
    }

    /**
     * Blocking for a while
     *
     * @since IPv6 moved from UDPReceiver
     */
    private void stopQueue() {
        _inboundQueue.clear();
        for (int i = 0; i < _activeHandlers.get(); i++) {
            UDPPacket poison = UDPPacket.acquire(_context, false);
            poison.setMessageType(TYPE_POISON);
            _inboundQueue.offer(poison);
        }
        for (int i = 1; i <= 5 && !_inboundQueue.isEmpty(); i++) {
            try {Thread.sleep(i * 50L);}
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        _inboundQueue.clear();
        _handlers.clear();
        _activeHandlers.set(0);
    }

    /**
     * Blocking call to retrieve the next inbound packet, or null if we have
     * shut down.
     *
     * @since IPv6 moved from UDPReceiver
     */
    public UDPPacket receiveNext() {
        UDPPacket rv = null;
        while (_keepReading && rv == null) {
            try {rv = _inboundQueue.take();}
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (rv != null && rv.getMessageType() == TYPE_POISON) {
                rv.release();
                return null;
            }
        }
        return rv;
    }

    private class Handler implements Runnable {

        @Override
        public void run() {
            try {
                while (_keepReading) {
                    Tuner.adjustHandlerPriority();
                    UDPPacket packet = receiveNext();
                    if (packet == null) {break;}

                    _processingCount.incrementAndGet();
                    try {
                        packet.received();
                        if (_log.shouldDebug()) {_log.debug("Received packet from " + packet);}
                        try {handlePacket(packet);}
                        catch (RuntimeException e) {
                            if (_log.shouldError()) {
                                _log.error("Internal error handling a UDP packet from " + packet, e);
                            }
                        }
                        // back to the cache with thee!
                        packet.release();
                    } finally {
                        _processingCount.decrementAndGet();
                    }
                }
            } finally {
                _handlers.remove(this);
            }
        }

        /**
         * Initial handling, called for every packet.
         * Find the state and call the correct receivePacket() variant.
         *
         * Classify the packet by source IP/port, into 4 groups:
         *<ol>
         *<li>Established session
         *<li>Pending inbound establishment
         *<li>Pending outbound establishment
         *<li>No established or pending session found
         *</ol>
         */
        private void handlePacket(UDPPacket packet) {
            RemoteHostId rem = packet.getRemoteHost();
            PeerState state = _transport.getPeerState(rem);
            if (state == null) {
                InboundEstablishState est = _establisher.getInboundState(rem);
                if (est != null) {
                    // Group 2: Inbound Establishment
                    if (_log.shouldDebug()) {
                        _log.debug("Packet received IS for an Inbound establishment");
                    }
                    receiveSSU2Packet(rem, packet, (InboundEstablishState2) est);
                } else {
                    OutboundEstablishState oest = _establisher.getOutboundState(rem);
                    if (oest != null) {
                        // Group 3: Outbound Establishment
                        if (_log.shouldDebug()) {
                            _log.debug("Packet received IS for an Outbound establishment");
                        }
                        receiveSSU2Packet(packet, (OutboundEstablishState2) oest);
                    } else {
                        // Group 4: New conn or needs fallback
                        if (_log.shouldDebug()) {
                            _log.debug("Packet received is not for an Inbound or Outbound establishment");
                        }
                        // ok, not already known establishment, try as a new one
                        // Last chance for success, using our intro key
                        receiveSSU2Packet(rem, packet, (InboundEstablishState2) null);
                    }
                }
            } else {
                ((PeerState2) state).receivePacket(rem, packet);
            }
        }
    }

    /**
     *  Decrypt the header and hand off to the state for processing.
     *  Packet is trial-decrypted, so fallback
     *  processing is possible if this returns false.
     *
     *  Possible messages here are Session Request, Token Request, Session Confirmed, or Peer Test.
     *  Data messages out-of-order from Session Confirmed, or following a
     *  Session Confirmed that was lost, or in-order but before the Session Confirmed was processed,
     *  will handed to the state to be queued for deferred handling.
     *
     *  Min packet data size: 56 (token request) if state is null; 40 (data) if state is non-null
     *
     *  @param state must be version 2, but will be null for session request unless retransmitted
     *  @return true if the header was validated as a SSU2 packet, cannot fallback to SSU 1
     *  @since 0.9.54
     */
    private boolean receiveSSU2Packet(RemoteHostId from, UDPPacket packet, InboundEstablishState2 state) {
        byte[] k1 = _transport.getSSU2StaticIntroKey();
        byte[] k2;
        SSU2Header.Header header;
        int type = -1;

        if (state == null) {
            k2 = k1;
            header = SSU2Header.trialDecryptHandshakeHeader(packet, k1, k2);
            if (header == null ||
                header.getType() != SSU2Util.SESSION_REQUEST_FLAG_BYTE ||
                header.getVersion() < MIN_VERSION ||
                header.getVersion() > MAX_VERSION ||
                header.getNetID() != _networkID) {

                if (header != null && _log.shouldInfo()) {
                    _log.info("Packet does not decrypt as Session Request, attempting to decrypt as Token Request / PeerTest / HolePunch \n* " +
                              header + " from " + from);
                }

                header = SSU2Header.trialDecryptLongHeader(packet, k1, k2);
                if (header == null ||
                    header.getVersion() < MIN_VERSION ||
                    header.getVersion() > MAX_VERSION ||
                    header.getNetID() != _networkID) {
                    if (header != null) {
                        long id = header.getDestConnID();
                        PeerState2 ps2 = _transport.getPeerState(id);
                        if (ps2 != null) {
                            if (_log.shouldInfo()) {
                                _log.info("Migrated " + packet.getPacket().getLength() + " byte packet from " + from + ps2);
                            }
                            ps2.receivePacket(from, packet);
                            return true;
                        }
                        PeerStateDestroyed dead = _transport.getRecentlyClosed(id);
                        if (dead != null) {
                            if (_log.shouldDebug()) {
                                _log.debug("Handling " + packet.getPacket().getLength() + " byte packet from " + from +
                                           " for recently closed ID " + id);
                            }
                            dead.receivePacket(from, packet);
                            return true;
                        }
                    }
                    return false;
                }
                type = header.getType();

                if (type == SSU2Util.SESSION_CONFIRMED_FLAG_BYTE) {
                    return false;
                }
            } else {
                type = SSU2Util.SESSION_REQUEST_FLAG_BYTE;
            }
        } else {
            k2 = state.getRcvHeaderEncryptKey2();
            if (k2 == null) {
                k2 = k1;
                header = SSU2Header.trialDecryptHandshakeHeader(packet, k1, k2);
                if (header == null ||
                    header.getType() != SSU2Util.SESSION_REQUEST_FLAG_BYTE ||
                    header.getVersion() < MIN_VERSION ||
                    header.getVersion() > MAX_VERSION ||
                    header.getNetID() != _networkID) {

                    header = SSU2Header.trialDecryptLongHeader(packet, k1, k2);
                    if (header == null ||
                        header.getType() != SSU2Util.TOKEN_REQUEST_FLAG_BYTE ||
                        header.getVersion() < MIN_VERSION ||
                        header.getVersion() > MAX_VERSION ||
                        header.getNetID() != _networkID) {
                        if (_log.shouldWarn()) {
                            _log.warn("Failed to decrypt Session or Token Request after retry \n* " + header +
                                      " (" + packet.getPacket().getLength() + " bytes) on " + state);
                        }
                        return false;
                    }
                }
                if (header.getSrcConnID() != state.getSendConnID()) {
                    if (_log.shouldWarn()) {
                        _log.warn("Received BAD Source Connection ID \n* " + header +
                                  " (" + packet.getPacket().getLength() + " bytes) on " + state);
                    }
                    return false;
                }
                if (header.getDestConnID() != state.getRcvConnID()) {
                    if (_log.shouldWarn()) {
                        _log.warn("Received BAD Destination Connection ID \n* " + header +
                                  " (" + packet.getPacket().getLength() + " bytes) on " + state);
                    }
                    return false;
                }
                type = header.getType();
            } else {
                header = SSU2Header.trialDecryptShortHeader(packet, k1, k2);
                if (header == null) {
                    if (_log.shouldWarn()) {
                        _log.warn("Received SessionConfirmed packet was too short (" +
                                  + packet.getPacket().getLength() + " bytes) on " + state);
                    }
                    return false;
                }
                if (header.getDestConnID() != state.getRcvConnID()) {
                    if (_log.shouldWarn()) {
                        _log.warn("Received BAD Destination Connection ID \n* " + header + " on " + state);
                    }
                    return false;
                }
                if (header.getPacketNumber() != 0 ||
                    header.getType() != SSU2Util.SESSION_CONFIRMED_FLAG_BYTE) {
                    if (_log.shouldInfo()) {
                        _log.info("Queueing possible data packet (" + packet.getPacket().getLength() + " bytes) on: " + state);
                    }
                    state.queuePossibleDataPacket(packet);
                    return true;
                }
                type = SSU2Util.SESSION_CONFIRMED_FLAG_BYTE;
            }
        }

        if (type != -1) {
            SSU2Header.acceptTrialDecrypt(packet, header);
            switch (type) {
              case SSU2Util.SESSION_REQUEST_FLAG_BYTE:
                if (_log.shouldDebug()) {_log.debug("Received a SessionRequest on " + state);}
                _establisher.receiveSessionOrTokenRequest(from, state, packet);
                break;

              case SSU2Util.TOKEN_REQUEST_FLAG_BYTE:
                if (_log.shouldDebug()) {_log.debug("Received a TokenRequest on " + state);}
                _establisher.receiveSessionOrTokenRequest(from, state, packet);
                break;

              case SSU2Util.SESSION_CONFIRMED_FLAG_BYTE:
                if (_log.shouldDebug()) {_log.debug("Received a SessionConfirmed on " + state);}
                _establisher.receiveSessionConfirmed(state, packet);
                break;

              case SSU2Util.PEER_TEST_FLAG_BYTE:
                if (_log.shouldDebug()) {_log.debug("Received a PeerTest from " + from);}
                _testManager.receiveTest(from, packet);
                break;

              case SSU2Util.HOLE_PUNCH_FLAG_BYTE:
                if (_log.shouldDebug()) {_log.debug("Received a HolePunch from " + from);}
                _establisher.receiveHolePunch(from, packet);
                break;

              default:
                if (_log.shouldWarn()) {_log.warn("Received UNKNOWN SSU2 message \n* " + header + " from " + from);}
                break;
            }
            return true;
        }

        return false;
    }

    /**
     *  Decrypt the header and hand off to the state for processing.
     *  Packet is trial-decrypted, so fallback
     *  processing is possible if this returns false.
     *  But that's probably not necessary.
     *
     *  Possible messages here are Session Created or Retry
     *
     *  Min packet data size: 56 (retry)
     *
     *  @param state must be version 2, non-null
     *  @return true if the header was validated as a SSU2 packet, cannot fallback to SSU 1
     *  @since 0.9.54
     */
    private boolean receiveSSU2Packet(UDPPacket packet, OutboundEstablishState2 state) {
        // decrypt header
        byte[] k1 = state.getRcvHeaderEncryptKey1();
        byte[] k2 = state.getRcvHeaderEncryptKey2();
        SSU2Header.Header header;
        if (k2 != null) {
            header = SSU2Header.trialDecryptHandshakeHeader(packet, k1, k2);
            if (header != null) {
                // dest conn ID decrypts the same for both Session Created
                // and Retry, so we can bail out now if it doesn't match
                if (header.getDestConnID() != state.getRcvConnID()) {
                    if (_log.shouldWarn()) {_log.warn("Received BAD Destination Connection ID \n* " + header);}
                    return false;
                }
            }
        } else {header = null;} // we have only sent a Token Request
        int type;
        if (header == null ||
            header.getType() != SSU2Util.SESSION_CREATED_FLAG_BYTE ||
            header.getVersion() < MIN_VERSION ||
            header.getVersion() > MAX_VERSION ||
            header.getNetID() != _networkID) {
            if (_log.shouldInfo()) {
                _log.info("Packet does not decrypt as SessionCreated, attempting to decrypt as Retry" + (header != null ? "\n* " + header : ""));
            }
            k2 = state.getRcvRetryHeaderEncryptKey2();
            header = SSU2Header.trialDecryptLongHeader(packet, k1, k2);
            if (header == null || header.getType() != SSU2Util.RETRY_FLAG_BYTE ||
                header.getVersion() < MIN_VERSION || header.getVersion() > MAX_VERSION || header.getNetID() != _networkID) {
                if (_log.shouldInfo()) {
                    _log.info("Packet does not decrypt as SessionCreated or Retry \n* " + header + " on " + state);
                }
                return false;
            }
            type = SSU2Util.RETRY_FLAG_BYTE;
        } else {type = SSU2Util.SESSION_CREATED_FLAG_BYTE;}
        if (header.getDestConnID() != state.getRcvConnID()) {
            if (_log.shouldWarn()) {_log.warn("Received BAD Destination Connection ID \n* " + header);}
            return false;
        }
        if (header.getSrcConnID() != state.getSendConnID()) {
            if (_log.shouldWarn()) {_log.warn("Received BAD Source Connection ID \n* " + header);}
            return false;
        }

        // all good
        SSU2Header.acceptTrialDecrypt(packet, header);
        if (type == SSU2Util.SESSION_CREATED_FLAG_BYTE) {
            if (_log.shouldDebug()) {_log.debug("Received a SessionCreated on " + state);}
            _establisher.receiveSessionCreated(state, packet);
        } else {
            if (_log.shouldDebug()) {_log.debug("Received a Retry on " + state);}
            _establisher.receiveRetry(state, packet);
        }
        return true;
    }

}
