package net.i2p.router.transport.udp;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.util.CoDelBlockingQueue;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.util.I2PThread;
import net.i2p.util.LHMCache;
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
 */
class PacketHandler {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    private final EstablishmentManager _establisher;
    private final InboundMessageFragments _inbound;
    private final PeerTestManager _testManager;
    private final IntroductionManager _introManager;
    private volatile boolean _keepReading;
    private final Handler[] _handlers;
    private final Map<RemoteHostId, Object> _failCache;
    private final BlockingQueue<UDPPacket> _inboundQueue;
    private static final Object DUMMY = new Object();

    private static final int TYPE_POISON = -99999;
    private static final int MIN_QUEUE_SIZE = 16;
//    private static final int MAX_QUEUE_SIZE = 192;
    private static final int MAX_QUEUE_SIZE = 256;
    private static final int MIN_NUM_HANDLERS = 1;  // if < 32MB
//    private static final int MAX_NUM_HANDLERS = 1;
    private static final int MAX_NUM_HANDLERS = (SystemVersion.isSlow() || SystemVersion.getCores() <= 4 ||
                                                 SystemVersion.getMaxMemory() < 512*1024*1024) ? 4 : Math.max(SystemVersion.getCores(), 8);
    /**
     *  Let packets be up to this much skewed.
     *  This is the same limit as in InNetMessagePool's MessageValidator.
     *  There's no use making it any larger, as messages will just be thrown out there.
     */
    private static final long GRACE_PERIOD = Router.CLOCK_FUDGE_FACTOR + 30*1000;
//    private static final long MAX_SKEW = 90*24*60*60*1000L;
    private static final long MAX_SKEW = 24*60*60*1000L;

    private enum AuthType { NONE, INTRO, BOBINTRO, SESSION }

    PacketHandler(RouterContext ctx, UDPTransport transport, EstablishmentManager establisher,
                  InboundMessageFragments inbound, PeerTestManager testManager, IntroductionManager introManager) {
        _context = ctx;
        _log = ctx.logManager().getLog(PacketHandler.class);
        _transport = transport;
        _establisher = establisher;
        _inbound = inbound;
        _testManager = testManager;
        _introManager = introManager;
        _failCache = new LHMCache<RemoteHostId, Object>(24);

        long maxMemory = SystemVersion.getMaxMemory();
        int cores = SystemVersion.getCores();
        boolean isSlow = SystemVersion.isSlow();
        int qsize = (int) Math.max(MIN_QUEUE_SIZE, Math.min(MAX_QUEUE_SIZE, maxMemory / (2*1024*1024)));
        _inboundQueue = new CoDelBlockingQueue<UDPPacket>(ctx, "UDP-Receiver", qsize);
        int num_handlers;
        if (maxMemory < 32*1024*1024)
            num_handlers = 1;
         else
            num_handlers = MAX_NUM_HANDLERS;
//        else
//            num_handlers = Math.max(MIN_NUM_HANDLERS, Math.min(MAX_NUM_HANDLERS, ctx.bandwidthLimiter().getInboundKBytesPerSecond() / 20));
        _handlers = new Handler[num_handlers];
        for (int i = 0; i < num_handlers; i++) {
            _handlers[i] = new Handler();
        }

        //_context.statManager().createRateStat("udp.handleTime", "Time to handle a received packet after its been pulled off the queue", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.queueTime", "How long after a packet is received can we begin handling it", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSkew", "How long after packet sent did we receive it", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidUnkown", "Age of dropped packet (unknown type)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidReestablish", "Age of dropped packet (no existing key, not establishment)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidEstablish", "Age of dropped packet (establishment, bad key)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidEstablish.inbound", "Age of dropped packet (invalid despite active inbound establishment)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidEstablish.outbound", "Age of dropped packet (invalid despite active outbound establishment)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidEstablish.new", "Age of dropped packet (invalid despite no active establishment) was", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidInboundEstablish", "Age of dropped packet (inbound establishment, bad key) was", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidSkew", "Skew of dropped packet (bad skew) was", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.destroyedInvalidSkew", "Session destroyed (bad skew)", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.packetDequeueTime", "Time for UDPReader to pull a packet off the inbound packet queue (when its slow)", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.packetVerifyTime", "Time for PacketHandler to verify a data packet after dequeueing (period is dequeue time)", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.packetVerifyTimeSlow", "Time for PacketHandler to verify a data packet after dequeueing when its slow (period is dequeue time)", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.packetValidateMultipleCount", "How many times we validate a packet, if done more than once (period = afterValidate-enqueue)", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.packetNoValidationLifetime", "How long packets that are never validated are around for", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receivePacketSize.sessionRequest", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receivePacketSize.sessionConfirmed", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receivePacketSize.sessionCreated", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.dataKnown", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.dataKnownAck", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.dataUnknown", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.dataUnknownAck", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receivePacketSize.test", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receivePacketSize.relayRequest", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receivePacketSize.relayIntro", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receivePacketSize.relayResponse", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
    }

    public synchronized void startup() {
        _keepReading = true;
        for (int i = 0; i < _handlers.length; i++) {
            I2PThread t = new I2PThread(_handlers[i], "UDPPktHandler " + (i+1) + '/' + _handlers.length, true);
            t.setPriority(I2PThread.MAX_PRIORITY - 1);
            t.start();
        }
    }

    public synchronized void shutdown() {
        _keepReading = false;
        stopQueue();
    }

    String getHandlerStatus() {
        StringBuilder rv = new StringBuilder();
        rv.append("Handlers: ").append(_handlers.length);
        for (int i = 0; i < _handlers.length; i++) {
            Handler handler = _handlers[i];
            rv.append(" handler ").append(i).append("; State: ").append(handler._state);
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
        _inboundQueue.put(packet);
    }


    /**
     * Blocking for a while
     *
     * @since IPv6 moved from UDPReceiver
     */
    private void stopQueue() {
        _inboundQueue.clear();
        for (int i = 0; i < _handlers.length; i++) {
            UDPPacket poison = UDPPacket.acquire(_context, false);
            poison.setMessageType(TYPE_POISON);
            _inboundQueue.offer(poison);
        }
        for (int i = 1; i <= 5 && !_inboundQueue.isEmpty(); i++) {
            try {
//                Thread.sleep(i * 50);
                Thread.sleep(i * 30);
            } catch (InterruptedException ie) {}
        }
        _inboundQueue.clear();
    }

    /**
     * Blocking call to retrieve the next inbound packet, or null if we have
     * shut down.
     *
     * @since IPv6 moved from UDPReceiver
     */
    public UDPPacket receiveNext() {
        UDPPacket rv = null;
        //int remaining = 0;
        while (_keepReading && rv == null) {
            try {
                rv = _inboundQueue.take();
            } catch (InterruptedException ie) {}
            if (rv != null && rv.getMessageType() == TYPE_POISON)
                return null;
        }
        //_context.statManager().addRateData("udp.receiveRemaining", remaining, 0);
        return rv;
    }

    /**
     * @since 0.9.42
     */
    private boolean validate(UDPPacket packet, SessionKey key) {
        return packet.validate(key, _transport.getHMAC());
    }

    /** the packet is from a peer we are establishing an outbound con to, but failed validation, so fallback */
    private static final short OUTBOUND_FALLBACK = 1;
    /** the packet is from a peer we are establishing an inbound con to, but failed validation, so fallback */
    private static final short INBOUND_FALLBACK = 2;
    /** the packet is not from anyone we know */
    private static final short NEW_PEER = 3;

    private class Handler implements Runnable {
        private final UDPPacketReader _reader;
        // TODO comment out all uses of _state
        public /* volatile */ int _state;

        public Handler() {
            _reader = new UDPPacketReader(_context);
        }

        public void run() {
            _state = 1;
            while (_keepReading) {
                _state = 2;
                UDPPacket packet = receiveNext();
                _state = 3;
                if (packet == null) break; // keepReading is probably false, or bind failed...

                packet.received();
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("UDP packet received: " + packet);
                _state = 4;
                //long queueTime = packet.getLifetime();
                //long handleStart = _context.clock().now();
                try {
                    _state = 5;
                    handlePacket(_reader, packet);
                    _state = 6;
                } catch (RuntimeException e) {
                    _state = 7;
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Crazy error handling a UDP packet: " + packet, e);
                }
                //long handleTime = _context.clock().now() - handleStart;
                //packet.afterHandling();
                //_context.statManager().addRateData("udp.handleTime", handleTime, packet.getLifetime());
                //_context.statManager().addRateData("udp.queueTime", queueTime, packet.getLifetime());
                _state = 8;

                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Done receiving: " + packet);

           /********
                if (handleTime > 1000) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Took " + handleTime + " to process the packet "
                                  + packet + ": " + _reader);
                }

                long enqueueTime = packet.getEnqueueTime();
                long recvTime = packet.getReceivedTime();
                long beforeValidateTime = packet.getBeforeValidate();
                long afterValidateTime = packet.getAfterValidate();
                int validateCount = packet.getValidateCount();

                long timeToDequeue = recvTime - enqueueTime;
                long timeToValidate = 0;
                long authTime = 0;
                if (afterValidateTime > 0) {
                    timeToValidate = afterValidateTime - enqueueTime;
                    authTime = afterValidateTime - beforeValidateTime;
                }
                if (timeToDequeue > 50)
                    _context.statManager().addRateData("udp.packetDequeueTime", timeToDequeue, timeToDequeue);
                if (authTime > 50)
                    _context.statManager().addRateData("udp.packetAuthRecvTime", authTime, beforeValidateTime-recvTime);
                if (afterValidateTime > 0) {
                    _context.statManager().addRateData("udp.packetVerifyTime", timeToValidate, authTime);
                    if (timeToValidate > 50)
                        _context.statManager().addRateData("udp.packetVerifyTimeSlow", timeToValidate, authTime);
                }
                if (validateCount > 1)
                    _context.statManager().addRateData("udp.packetValidateMultipleCount", validateCount, timeToValidate);
                else if (validateCount <= 0)
                    _context.statManager().addRateData("udp.packetNoValidationLifetime", packet.getLifetime(), 0);
            ********/

                // back to the cache with thee!
                packet.release();
                _state = 9;
            }
        }
    //}

        /**
         * Initial handling, called for every packet
         * Find the state and call the correct receivePacket() variant
         */
        private void handlePacket(UDPPacketReader reader, UDPPacket packet) {

            _state = 10;

            RemoteHostId rem = packet.getRemoteHost();
            PeerState state = _transport.getPeerState(rem);
            if (state == null) {
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Packet received is not for a connected peer");
                _state = 11;
                InboundEstablishState est = _establisher.getInboundState(rem);
                if (est != null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Packet received IS for an Inbound establishment");
                    _state = 12;
                    receivePacket(reader, packet, est);
                } else {
                    //if (_log.shouldLog(Log.DEBUG))
                    //    _log.debug("Packet received is not for an inbound establishment");
                    _state = 13;
                    OutboundEstablishState oest = _establisher.getOutboundState(rem);
                    if (oest != null) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Packet received IS for an Outbound establishment");
                        _state = 14;
                        receivePacket(reader, packet, oest);
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Packet received is not for an Inbound or Outbound establishment");
                        // ok, not already known establishment, try as a new one
                        // Last chance for success, using our intro key
                        _state = 15;
                        receivePacket(reader, packet, NEW_PEER);
                    }
                }
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Packet received IS for an existing peer");
                _state = 16;
                receivePacket(reader, packet, state);
            }
        }

        /**
         * Established conn
         * Decrypt and validate the packet then call handlePacket()
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, PeerState state) {
            _state = 17;
            AuthType auth = AuthType.NONE;
            boolean isValid = validate(packet, state.getCurrentMACKey());
            if (!isValid) {
                _state = 18;
                if (state.getNextMACKey() != null)
                    isValid = validate(packet, state.getNextMACKey());
                if (!isValid) {
                    _state = 19;
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Failed validation with existing connection; trying as new connection: " + packet);

                    isValid = validate(packet, _transport.getIntroKey());
                    if (isValid) {
                        _state = 20;
                        // this is a stray packet from an inbound establishment
                        // process, so try our intro key
                        // (after an outbound establishment process, there wouldn't
                        //  be any stray packets)
                        // These are generally PeerTest packets
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Failed validation with existing connection, but validation as reestablish/stray passed");
                        packet.decrypt(_transport.getIntroKey());
                        auth = AuthType.INTRO;
                    } else {
                        _state = 21;
                        InboundEstablishState est = _establisher.getInboundState(packet.getRemoteHost());
                        if (est != null) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Packet from an existing peer IS for an Inbound establishment");
                            _state = 22;
                            receivePacket(reader, packet, est, false);
                        } else {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Failed validation with existing connection, and validation as reestablish failed too; dropping " + packet);
                            _context.statManager().addRateData("udp.droppedInvalidReestablish", packet.getLifetime());
                        }
                        return;
                    }
                } else {
                    _state = 23;
                    packet.decrypt(state.getNextCipherKey());
                    auth = AuthType.SESSION;
                }
            } else {
                _state = 24;
                packet.decrypt(state.getCurrentCipherKey());
                auth = AuthType.SESSION;
            }

            _state = 25;
            handlePacket(reader, packet, state, null, null, auth);
            _state = 26;
        }

        /**
         * New conn or failed validation - we have no Session Key.
         * Here we attempt to validate the packet with our intro key,
         * then decrypt the packet with our intro key,
         * then call handlePacket().
         *
         * @param peerType OUTBOUND_FALLBACK, INBOUND_FALLBACK, or NEW_PEER
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, short peerType) {
            _state = 27;
            boolean isValid = validate(packet, _transport.getIntroKey());
            if (!isValid) {
                // Note that the vast majority of these are NOT corrupted packets, but
                // packets for which we don't have the PeerState (i.e. SessionKey)
                // Case 1: 48 byte destroy packet, we already closed
                // Case 2: 369 byte session created packet, re-tx of one that failed validation
                //         (peer probably doesn't know his correct external port, esp. on <= 0.9.1

                // Case 3:
                // For peers that change ports, look for an existing session with the same IP
                // If we find it, and the packet validates with its mac key, tell the transport
                // to change the port, and handle the packet.
                // All this since 0.9.3.
                RemoteHostId remoteHost = packet.getRemoteHost();
                boolean alreadyFailed;
                synchronized(_failCache) {
                    alreadyFailed = _failCache.get(remoteHost) != null;
                }
                if (!alreadyFailed) {
                    // this is slow, that's why we cache it above.
                    List<PeerState> peers = _transport.getPeerStatesByIP(remoteHost);
                    if (!peers.isEmpty()) {
                        StringBuilder buf = new StringBuilder(256);
                        buf.append("Established peer connection with: ");
                        boolean foundSamePort = false;
                        PeerState state = null;
                        int newPort = remoteHost.getPort();
                        for (PeerState ps : peers) {
                            boolean valid = false;
                            if (_log.shouldLog(Log.WARN)) {
                                long now = _context.clock().now();
                                long lastSent = now - ps.getLastSendTime();
                                long lastRcvd = now - ps.getLastReceiveTime();
                                String tx = "now";
                                String rx = "now";
                                if (lastSent > 0)
                                    tx = lastSent + "ms ago";
                                if (lastRcvd > 0)
                                    rx = lastSent + "ms ago";
                                buf.append(ps.getRemoteHostId().toString())
                                   .append("\n* Last message sent: ").append(tx).append("; Last message received: ").append(rx);
                            }
                            if (ps.getRemotePort() == newPort) {
                                foundSamePort = true;
                            } else if (validate(packet, ps.getCurrentMACKey())) {
                                packet.decrypt(ps.getCurrentCipherKey());
                                reader.initialize(packet);
                                if (_log.shouldLog(Log.WARN))
                                    buf.append(" (VALID -> type ").append(reader.readPayloadType()).append("); ");
                                valid = true;
                                if (state == null)
                                    state = ps;
                            } else {
                                if (_log.shouldLog(Log.WARN))
                                    buf.append(" (INVALID) ");
                            }
                        }
                        if (state != null && !foundSamePort) {
                            _transport.changePeerPort(state, newPort);
                            if (_log.shouldLog(Log.WARN)) {
                                buf.append(" Changed port to: ").append(newPort).append(" and handled");
                                _log.warn(buf.toString());
                            }
                            handlePacket(reader, packet, state, null, null, AuthType.SESSION);
                            return;
                        }
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(buf.toString());
                    }
                    synchronized(_failCache) {
                        _failCache.put(remoteHost, DUMMY);
                    }
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Cannot validate received packet; (path) wasCached? " + alreadyFailed + packet);

                _context.statManager().addRateData("udp.droppedInvalidEstablish", packet.getLifetime());
                switch (peerType) {
                    case INBOUND_FALLBACK:
                        _context.statManager().addRateData("udp.droppedInvalidEstablish.inbound", packet.getLifetime(), packet.getTimeSinceReceived());
                        break;
                    case OUTBOUND_FALLBACK:
                        _context.statManager().addRateData("udp.droppedInvalidEstablish.outbound", packet.getLifetime(), packet.getTimeSinceReceived());
                        break;
                    case NEW_PEER:
                        _context.statManager().addRateData("udp.droppedInvalidEstablish.new", packet.getLifetime(), packet.getTimeSinceReceived());
                        break;
                }
                _state = 28;
                return;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Valid Introduction packet received " + packet);
            }

            // Packets that get here are probably one of:
            //  304 byte Session Request
            //  96 byte Relay Request
            //  60 byte Relay Response
            //  80 byte Peer Test
            _state = 29;
            packet.decrypt(_transport.getIntroKey());
            handlePacket(reader, packet, null, null, null, AuthType.INTRO);
            _state = 30;
        }

        /**
         * @param state non-null
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, InboundEstablishState state) {
            receivePacket(reader, packet, state, true);
        }

        /**
         * Inbound establishing conn
         * Decrypt and validate the packet then call handlePacket()
         *
         * @param state non-null
         * @param allowFallback if it isn't valid for this establishment state, try as a non-establishment packet
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, InboundEstablishState state, boolean allowFallback) {
            _state = 31;
            if (_log.shouldLog(Log.DEBUG)) {
                StringBuilder buf = new StringBuilder(128);
                buf.append("Attempting to receive a packet on a known Inbound state ");
                buf.append(state);
                buf.append("\n* MAC key: ").append(state.getMACKey());
                buf.append("\n* Intro key: ").append(_transport.getIntroKey());
                _log.debug(buf.toString());
            }
            boolean isValid = false;
            if (state.getMACKey() != null) {
                isValid = validate(packet, state.getMACKey());
                if (isValid) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Valid introduction packet received for Inbound connnection " + packet);

                    _state = 32;
                    packet.decrypt(state.getCipherKey());
                    handlePacket(reader, packet, null, null, null, AuthType.SESSION);
                    return;
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Invalid Inbound introduction packet received, treating as non-establishment " + packet);
                    _state = 33;
                }
            }
            if (allowFallback) {
                // ok, we couldn't handle it with the established stuff, so fall back
                // on earlier state packets
                _state = 34;
                receivePacket(reader, packet, INBOUND_FALLBACK);
            } else {
                _context.statManager().addRateData("udp.droppedInvalidInboundEstablish", packet.getLifetime());
            }
        }

        /**
         * Outbound establishing conn
         * Decrypt and validate the packet then call handlePacket()
         *
         * @param state non-null
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, OutboundEstablishState state) {
            _state = 35;
            if (_log.shouldLog(Log.DEBUG)) {
                StringBuilder buf = new StringBuilder(128);
                buf.append("Attempting to receive a packet on a known Outbound state ");
                buf.append(state);
                buf.append("\n* MAC key: ").append(state.getMACKey());
                buf.append("\n* Intro key: ").append(state.getIntroKey());
                _log.debug(buf.toString());
            }

            boolean isValid = false;
            if (state.getMACKey() != null) {
                _state = 36;
                isValid = validate(packet, state.getMACKey());
                if (isValid) {
                    // this should be the Session Confirmed packet
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Valid introduction packet received for Outbound established connection " + packet);
                    _state = 37;
                    packet.decrypt(state.getCipherKey());
                    handlePacket(reader, packet, null, state, null, AuthType.SESSION);
                    _state = 38;
                    return;
                }
            }

            // keys not yet exchanged, lets try it with the peer's intro key
            isValid = validate(packet, state.getIntroKey());
            if (isValid) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Valid packet received for " + state + " with Bob's intro key " + packet);
                _state = 39;
                packet.decrypt(state.getIntroKey());
                // the only packet we should be getting with Bob's intro key is Session Created
                handlePacket(reader, packet, null, state, null, AuthType.BOBINTRO);
                _state = 40;
                return;
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid Outbound introduction packet received (stale intro key) for established connection, treating as non-establishment " + packet);
            }

            // ok, we couldn't handle it with the established stuff, so fall back
            // on earlier state packets
            _state = 41;
            receivePacket(reader, packet, OUTBOUND_FALLBACK);
            _state = 42;
        }

        /**
         * The last step. The packet was decrypted with some key. Now get the message type
         * and send it to one of four places: The EstablishmentManager, IntroductionManager,
         * PeerTestManager, or InboundMessageFragments.
         *
         * @param state non-null if fully established
         * @param outState non-null if outbound establishing in process
         * @param inState unused always null, TODO use for 48-byte destroys during inbound establishment
         * @param auth what type of authentication succeeded
         */
        private void handlePacket(UDPPacketReader reader, UDPPacket packet, PeerState state,
                                  OutboundEstablishState outState, InboundEstablishState inState,
                                  AuthType auth) {
            _state = 43;
            reader.initialize(packet);
            _state = 44;
            long recvOn = packet.getBegin();
            long sendOn = reader.readTimestamp() * 1000;
            // Positive when we are ahead of them
            long skew = recvOn - sendOn;
            int type = reader.readPayloadType();
            // if it's a bad type, the whole packet is probably corrupt
            boolean typeOK = type <= UDPPacket.MAX_PAYLOAD_TYPE;
            boolean skewOK = skew < MAX_SKEW && skew > (0 - MAX_SKEW) && typeOK;

            // update skew whether or not we will be dropping the packet for excessive skew
            if (state != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Received packet from " + state.getRemoteHostId().toString() + " with skew of " + skew + "ms");
                if (auth == AuthType.SESSION && typeOK && (skewOK || state.getMessagesReceived() <= 0))
                    state.adjustClockSkew(skew);
            }
            _context.statManager().addRateData("udp.receivePacketSkew", skew);

            if (skewOK && !_context.clock().getUpdatedSuccessfully()) {
                // adjust the clock one time in desperation
                // this doesn't seem to work for big skews, we never get anything back,
                // so we have to wait for NTCP to do it
                _context.clock().setOffset(0 - skew, true);
                if (skew != 0) {
                    _log.logAlways(Log.WARN, "NTP failure, UDP adjusting clock by " + DataHelper.formatDuration(Math.abs(skew)));
                    skew = 0;
                }
            }

            if (skew > GRACE_PERIOD) {
                _context.statManager().addRateData("udp.droppedInvalidSkew", skew);
                if (state != null && skew > 4 * GRACE_PERIOD && state.getPacketsReceived() <= 0) {
                    _transport.sendDestroy(state);
                    _transport.dropPeer(state, true, "Clock skew");
                    if (state.getRemotePort() == 65520) {
                        // distinct port of buggy router
                        _context.banlist().banlistRouterForever(state.getRemotePeer(),
                                                                " <b>➜</b> " + _x("Excessive clock skew: {0}"),
                                                                DataHelper.formatDuration(skew));
                    } else {
                        _context.banlist().banlistRouter(DataHelper.formatDuration(skew),
                                                         state.getRemotePeer(),
                                                         " <b>➜</b> " + _x("Excessive clock skew: {0}"));
                    }
                    _context.statManager().addRateData("udp.destroyedInvalidSkew", skew);
                    if (_log.shouldWarn())
                        _log.warn("Dropped connection - packet too far in the past: " + new Date(sendOn) + packet +
                                  " PeerState: " + state);
                } else {
                    if (_log.shouldWarn())
                        _log.warn("Packet too far in the past: " + new Date(sendOn) + packet +
                                  " PeerState: " + state);
                }
                return;
            } else if (skew < 0 - GRACE_PERIOD) {
                _context.statManager().addRateData("udp.droppedInvalidSkew", 0-skew);
                if (state != null && skew < 0 - (4 * GRACE_PERIOD) && state.getPacketsReceived() <= 0) {
                    _transport.sendDestroy(state);
                    _transport.dropPeer(state, true, "Clock skew");
                    if (state.getRemotePort() == 65520) {
                        // distinct port of buggy router
                        _context.banlist().banlistRouterForever(state.getRemotePeer(),
                                                                " <b>➜</b> " + _x("Excessive clock skew: {0}"),
                                                                DataHelper.formatDuration(0 - skew));
                    } else {
                        _context.banlist().banlistRouter(DataHelper.formatDuration(0 - skew),
                                                         state.getRemotePeer(),
                                                         " <b>➜</b> " + _x("Excessive clock skew: {0}"));
                    }
                    _context.statManager().addRateData("udp.destroyedInvalidSkew", 0-skew);
                    if (_log.shouldWarn())
                        _log.warn("Dropped connection - packet too far in the future " + packet + "; PeerState: " + state +
                                  "\n* Date: " + new Date(sendOn));
                } else {
                    if (_log.shouldWarn())
                        _log.warn("Packet too far in the future " + packet + "; PeerState: " + state +
                                  "\n* Date: " + new Date(sendOn));
                }
                return;
            }

            //InetAddress fromHost = packet.getPacket().getAddress();
            //int fromPort = packet.getPacket().getPort();
            //RemoteHostId from = new RemoteHostId(fromHost.getAddress(), fromPort);
            _state = 45;
            RemoteHostId from = packet.getRemoteHost();
            _state = 46;

            switch (type) {
                case UDPPacket.PAYLOAD_TYPE_SESSION_REQUEST:
                    _state = 47;
                    if (auth == AuthType.BOBINTRO) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    _establisher.receiveSessionRequest(from, reader);
                    //_context.statManager().addRateData("udp.receivePacketSize.sessionRequest", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_SESSION_CONFIRMED:
                    _state = 48;
                    if (auth != AuthType.SESSION) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    _establisher.receiveSessionConfirmed(from, reader);
                    //_context.statManager().addRateData("udp.receivePacketSize.sessionConfirmed", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_SESSION_CREATED:
                    _state = 49;
                    // this is the only type that allows BOBINTRO
                    if (auth != AuthType.BOBINTRO && auth != AuthType.SESSION) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    _establisher.receiveSessionCreated(from, reader);
                    //_context.statManager().addRateData("udp.receivePacketSize.sessionCreated", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_DATA:
                    _state = 50;
                    if (auth != AuthType.SESSION) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    if (outState != null)
                        state = _establisher.receiveData(outState);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Received new DATA packet from " + state + packet);
                    UDPPacketReader.DataReader dr = reader.getDataReader();
                    if (state != null) {
                        if (_log.shouldLog(Log.DEBUG)) {
                            StringBuilder msg = new StringBuilder(512);
                            msg.append("Received IDENTITY packet [").append(System.identityHashCode(packet));
                            msg.append("] from [").append(state.getRemotePeer().toBase64().substring(0,6)).append("] ").append(state.getRemoteHostId());
                            try {
                                int count = dr.readFragmentCount();
                                for (int i = 0; i < count; i++) {
                                    msg.append("\n* [MsgID ").append(dr.readMessageId(i)).append("]");
                                    msg.append(" Fragment: ").append(dr.readMessageFragmentNum(i));
                                    if (dr.readMessageIsLast(i))
                                        msg.append("*");
                                }
                            } catch (DataFormatException dfe) {}
                            msg.append(dr.toString());
                            _log.debug(msg.toString());
                        }
                        //packet.beforeReceiveFragments();
                        _inbound.receiveData(state, dr);
                        _context.statManager().addRateData("udp.receivePacketSize.dataKnown", packet.getPacket().getLength(), packet.getLifetime());
                    } else {
                        // doesn't happen
                        _context.statManager().addRateData("udp.receivePacketSize.dataUnknown", packet.getPacket().getLength(), packet.getLifetime());
                    }
                    try {
                        if (dr.readFragmentCount() <= 0)
                            _context.statManager().addRateData("udp.receivePacketSize.dataUnknownAck", packet.getPacket().getLength(), packet.getLifetime());
                    } catch (DataFormatException dfe) {}
                    break;
                case UDPPacket.PAYLOAD_TYPE_TEST:
                    _state = 51;
                    if (auth == AuthType.BOBINTRO) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Received test packet: " + reader + " from " + from);
                    _testManager.receiveTest(from, state, auth == AuthType.SESSION, reader);
                    //_context.statManager().addRateData("udp.receivePacketSize.test", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_RELAY_REQUEST:
                    if (auth == AuthType.BOBINTRO) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Received Relay Request packet: " + reader + " from " + from);
                    _introManager.receiveRelayRequest(from, reader);
                    //_context.statManager().addRateData("udp.receivePacketSize.relayRequest", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_RELAY_INTRO:
                    if (auth != AuthType.SESSION) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Received Relay Intro packet: " + reader + " from " + from);
                    _introManager.receiveRelayIntro(from, reader);
                    //_context.statManager().addRateData("udp.receivePacketSize.relayIntro", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_RELAY_RESPONSE:
                    if (auth == AuthType.BOBINTRO) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Received Relay Response packet: " + reader + " from " + from);
                    _establisher.receiveRelayResponse(from, reader);
                    //_context.statManager().addRateData("udp.receivePacketSize.relayResponse", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_SESSION_DESTROY:
                    _state = 53;
                    if (auth == AuthType.BOBINTRO) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                    } else if (auth != AuthType.SESSION)
                        _establisher.receiveSessionDestroy(from);  // drops
                    else if (outState != null)
                        _establisher.receiveSessionDestroy(from, outState);
                    else if (state != null)
                        _establisher.receiveSessionDestroy(from, state);
                    else
                        _establisher.receiveSessionDestroy(from);  // drops
                    break;
                default:
                    _state = 52;
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dropping type " + type + " auth " + auth + packet);
                    _context.statManager().addRateData("udp.droppedInvalidUnknown", packet.getLifetime());
                    return;
            }
        }
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     *  @since 0.9.20
     */
    private static final String _x(String s) {
        return s;
    }
}
