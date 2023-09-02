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
    private final boolean _enableSSU1, _enableSSU2;
    private final int _networkID;

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
//    static final long MAX_SKEW = 90*24*60*60*1000L;
    static final long MAX_SKEW = 24*60*60*1000L;

    private enum AuthType { NONE, INTRO, BOBINTRO, SESSION }

    PacketHandler(RouterContext ctx, UDPTransport transport, boolean enableSSU1, boolean enableSSU2, EstablishmentManager establisher,
                  InboundMessageFragments inbound, PeerTestManager testManager, IntroductionManager introManager) {
        _context = ctx;
        _log = ctx.logManager().getLog(PacketHandler.class);
        _transport = transport;
        _enableSSU1 = enableSSU1;
        _enableSSU2 = enableSSU2;
        _establisher = establisher;
        _inbound = inbound;
        _testManager = testManager;
        _introManager = introManager;
        _failCache = new LHMCache<RemoteHostId, Object>(24);
        _networkID = ctx.router().getNetworkID();

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
        _context.statManager().createRateStat("udp.receivePacketSize.dataKnown", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.dataKnownAck", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.dataUnknown", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.dataUnknownAck", "Size of inbound packet type (period is packet lifetime)", "Transport [UDP]", UDPTransport.RATES);
    }

    public synchronized void startup() {
        _keepReading = true;
        for (int i = 0; i < _handlers.length; i++) {
            I2PThread t = new I2PThread(_handlers[i], "UDPPktHandler " + (i+1) + '/' + _handlers.length, true);
            t.setPriority(I2PThread.MAX_PRIORITY);
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
                Thread.sleep(i * 10);
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

    private enum PeerType {
        /** the packet is from a peer we are establishing an outbound con to, but failed validation, so fallback */
        OUTBOUND_FALLBACK,
        /** the packet is from a peer we are establishing an inbound con to, but failed validation, so fallback */
        INBOUND_FALLBACK,
        /** the packet is not from anyone we know */
        NEW_PEER
    }

    private class Handler implements Runnable {
        private final UDPPacketReader _reader;

        public Handler() {
            _reader = new UDPPacketReader(_context);
        }

        public void run() {
            while (_keepReading) {
                UDPPacket packet = receiveNext();
                if (packet == null) break; // keepReading is probably false, or bind failed...

                packet.received();
                //if (_log.shouldDebug())
                //    _log.debug("Received: " + packet);
                try {
                    handlePacket(_reader, packet);
                } catch (RuntimeException e) {
                    if (_log.shouldError())
                        _log.error("Internal error handling a UDP packet: " + packet, e);
                }

                // back to the cache with thee!
                packet.release();
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
        private void handlePacket(UDPPacketReader reader, UDPPacket packet) {
            RemoteHostId rem = packet.getRemoteHost();
            PeerState state = _transport.getPeerState(rem);
            if (state == null) {
                //if (_log.shouldDebug())
                //    _log.debug("Packet received is not for a connected peer");
                InboundEstablishState est = _establisher.getInboundState(rem);
                if (est != null) {
                    // Group 2: Inbound Establishment
                    if (_log.shouldDebug())
                        _log.debug("Packet received IS for an Inbound establishment");
                    if (est.getVersion() == 2)
                        receiveSSU2Packet(rem, packet, (InboundEstablishState2) est);
                    else
                    receivePacket(reader, packet, est);
                } else {
                    //if (_log.shouldDebug())
                    //    _log.debug("Packet received is not for an inbound establishment");
                    OutboundEstablishState oest = _establisher.getOutboundState(rem);
                    if (oest != null) {
                        // Group 3: Outbound Establishment
                        if (_log.shouldDebug())
                            _log.debug("Packet received IS for an Outbound establishment");
                        if (oest.getVersion() == 2)
                            receiveSSU2Packet(packet, (OutboundEstablishState2) oest);
                        else
                        receivePacket(reader, packet, oest);
                    } else {
                        // Group 4: New conn or needs fallback
                        if (_log.shouldDebug())
                            _log.debug("Packet received is not for an Inbound or Outbound establishment");
                        // ok, not already known establishment, try as a new one
                        // Last chance for success, using our intro key
                        if (_enableSSU1)
                            receivePacket(reader, packet, PeerType.NEW_PEER);
                        else
                            receiveSSU2Packet(rem, packet, (InboundEstablishState2) null);
                    }
                }
            } else {
                // Group 1: Established
                //if (_log.shouldDebug())
                //    _log.debug("Packet received IS for an existing peer");
                if (state.getVersion() == 2)
                    ((PeerState2) state).receivePacket(rem, packet);
                else
                receivePacket(reader, packet, state);
            }
        }

        ///////////
        // Begin SSU1-only methods (except group 4)
        ///////////

        /**
         * Group 1: Established conn
         * Decrypt and validate the packet then call handlePacket()
         *
         * SSU1 only.
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, PeerState state) {
            AuthType auth = AuthType.NONE;
            boolean isValid = validate(packet, state.getCurrentMACKey());
            if (!isValid) {
                if (state.getNextMACKey() != null)
                    isValid = validate(packet, state.getNextMACKey());
                if (!isValid) {
                    if (_log.shouldDebug())
                        _log.debug("Failed validation with existing connection; trying as new connection: " + packet);

                    isValid = validate(packet, _transport.getIntroKey());
                    if (isValid) {
                        // this is a stray packet from an inbound establishment
                        // process, so try our intro key
                        // (after an outbound establishment process, there wouldn't
                        //  be any stray packets)
                        // These are generally PeerTest packets
                        if (_log.shouldDebug())
                            _log.debug("Failed validation with existing connection, but validation as reestablish/stray passed");
                        packet.decrypt(_transport.getIntroKey());
                        auth = AuthType.INTRO;
                    } else {
                        InboundEstablishState est = _establisher.getInboundState(packet.getRemoteHost());
                        if (est != null) {
                            if (_log.shouldDebug())
                                _log.debug("Packet from an existing peer IS for an Inbound establishment");
                            receivePacket(reader, packet, est, false);
                        } else {
                            if (_log.shouldWarn())
                                _log.warn("Failed validation with existing connection, and validation as reestablish failed too; dropping... " + packet);
                            _context.statManager().addRateData("udp.droppedInvalidReestablish", packet.getLifetime());
                        }
                        return;
                    }
                } else {
                    packet.decrypt(state.getNextCipherKey());
                    auth = AuthType.SESSION;
                }
            } else {
                packet.decrypt(state.getCurrentCipherKey());
                auth = AuthType.SESSION;
            }

            handlePacket(reader, packet, state, null, null, auth);
        }

        /**
         * Group 4: New conn or failed validation - we have no Session Key.
         * Here we attempt to validate the packet with our intro key,
         * then decrypt the packet with our intro key,
         * then call handlePacket().
         *
         * This falls back to SSU2 for PeerType.NEW_PEER.
         * If SSU1 is disabled, call receiveSSU2Packet() directly, don't call this.
         *
         * @param peerType OUTBOUND_FALLBACK, INBOUND_FALLBACK, or NEW_PEER
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, PeerType peerType) {
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
                    // For now, try SSU2 Session/Token Request processing here.
                    // After we've migrated the majority of the network over to SSU2,
                    // we can try SSU2 first.
                    if (_enableSSU2 && peerType == PeerType.NEW_PEER) {
                        int len = packet.getPacket().getLength();
                        if (len >= SSU2Util.MIN_TOKEN_REQUEST_LEN) {
                            boolean handled = receiveSSU2Packet(remoteHost, packet, (InboundEstablishState2) null);
                            if (handled)
                                return;
                        } else if (len >= SSU2Util.MIN_DATA_LEN) {
                            byte[] k1 = _transport.getSSU2StaticIntroKey();
                            long id = SSU2Header.decryptDestConnID(packet.getPacket(), k1);
                            PeerStateDestroyed dead = _transport.getRecentlyClosed(id);
                            if (dead != null) {
                                // Probably termination ack.
                                // Prevent attempted SSU1 fallback processing and adding to fail cache
                                if (_log.shouldDebug())
                                    _log.debug("Handling " + len + " byte packet from " + remoteHost +
                                               " for recently closed ID " + id);
                                dead.receivePacket(remoteHost, packet);
                                return;
                            }
                        }
                        if (_log.shouldDebug())
                            _log.debug("Continuing with SSU1 fallback processing, wasn't an SSU2 packet from " + remoteHost);
                    }

                    // this is slow, that's why we cache it above.
                    List<PeerState> peers = _transport.getPeerStatesByIP(remoteHost);
                    if (!peers.isEmpty()) {
                        StringBuilder buf = new StringBuilder(256);
                        buf.append("Established peer connection with: ");
                        boolean foundSamePort = false;
                        PeerState state = null;
                        int newPort = remoteHost.getPort();
                        for (PeerState ps : peers) {
                            if (ps.getVersion() > 1)
                                continue;
                            boolean valid = false;
                            if (_log.shouldWarn()) {
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
                                if (_log.shouldWarn())
                                    buf.append(" (VALID -> type ").append(reader.readPayloadType()).append("); ");
                                valid = true;
                                if (state == null)
                                    state = ps;
                            } else {
                                if (_log.shouldWarn())
                                    buf.append(" (INVALID) ");
                            }
                        }
                        if (state != null && !foundSamePort) {
                            _transport.changePeerPort(state, newPort);
                            if (_log.shouldWarn()) {
                                buf.append(" Changed port to: ").append(newPort).append(" and handled");
                                _log.warn(buf.toString());
                            }
                            handlePacket(reader, packet, state, null, null, AuthType.SESSION);
                            return;
                        }
                        if (_log.shouldWarn())
                            _log.warn(buf.toString());
                    }
                    synchronized(_failCache) {
                        _failCache.put(remoteHost, DUMMY);
                    }
                }
                if (_log.shouldWarn())
                    _log.warn("Cannot validate received packet; (path) wasCached? " + alreadyFailed + packet);

                long lifetime = packet.getLifetime();
                _context.statManager().addRateData("udp.droppedInvalidEstablish", lifetime);
                switch (peerType) {
                    case INBOUND_FALLBACK:
                        _context.statManager().addRateData("udp.droppedInvalidEstablish.inbound", lifetime, packet.getTimeSinceReceived());
                        break;
                    case OUTBOUND_FALLBACK:
                        _context.statManager().addRateData("udp.droppedInvalidEstablish.outbound", lifetime, packet.getTimeSinceReceived());
                        break;
                    case NEW_PEER:
                        _context.statManager().addRateData("udp.droppedInvalidEstablish.new", lifetime, packet.getTimeSinceReceived());
                        break;
                }
                return;
            } else {
                if (_log.shouldDebug())
                    _log.debug("Valid Introduction packet received " + packet);
            }

            // Packets that get here are probably one of:
            //  304 byte Session Request
            //  96 byte Relay Request
            //  60 byte Relay Response
            //  80 byte Peer Test
            packet.decrypt(_transport.getIntroKey());
            handlePacket(reader, packet, null, null, null, AuthType.INTRO);
        }

        /**
         * SSU1 only.
         *
         * @param state non-null
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, InboundEstablishState state) {
            receivePacket(reader, packet, state, true);
        }

        /**
         * Group 2: Inbound establishing conn
         * Decrypt and validate the packet then call handlePacket()
         *
         * SSU1 only.
         *
         * @param state non-null
         * @param allowFallback if it isn't valid for this establishment state, try as a non-establishment packet
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, InboundEstablishState state, boolean allowFallback) {
            if (_log.shouldDebug()) {
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
                    if (_log.shouldDebug())
                        _log.debug("Valid introduction packet received for Inbound connnection " + packet);

                    packet.decrypt(state.getCipherKey());
                    handlePacket(reader, packet, null, null, null, AuthType.SESSION);
                    return;
                } else {
                    if (_log.shouldWarn())
                        _log.warn("Invalid Inbound introduction packet received, treating as non-establishment " + packet);
                }
            }
            if (allowFallback) {
                // ok, we couldn't handle it with the established stuff, so fall back
                // on earlier state packets
                receivePacket(reader, packet, PeerType.INBOUND_FALLBACK);
            } else {
                _context.statManager().addRateData("udp.droppedInvalidInboundEstablish", packet.getLifetime());
            }
        }

        /**
         * Group 3: Outbound establishing conn
         * Decrypt and validate the packet then call handlePacket()
         *
         * SSU1 only.
         *
         * @param state non-null
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, OutboundEstablishState state) {
            if (_log.shouldDebug()) {
                StringBuilder buf = new StringBuilder(128);
                buf.append("Attempting to receive a packet on a known Outbound state ");
                buf.append(state);
                buf.append("\n* MAC key: ").append(state.getMACKey());
                buf.append("\n* Intro key: ").append(state.getIntroKey());
                _log.debug(buf.toString());
            }

            boolean isValid = false;
            if (state.getMACKey() != null) {
                isValid = validate(packet, state.getMACKey());
                if (isValid) {
                    // this should be the Session Confirmed packet
                    if (_log.shouldDebug())
                        _log.debug("Valid introduction packet received for Outbound established connection " + packet);
                    packet.decrypt(state.getCipherKey());
                    handlePacket(reader, packet, null, state, null, AuthType.SESSION);
                    return;
                }
            }

            // keys not yet exchanged, lets try it with the peer's intro key
            isValid = validate(packet, state.getIntroKey());
            if (isValid) {
                if (_log.shouldDebug())
                    _log.debug("Valid packet received for " + state + " with Bob's intro key " + packet);
                packet.decrypt(state.getIntroKey());
                // the only packet we should be getting with Bob's intro key is Session Created
                handlePacket(reader, packet, null, state, null, AuthType.BOBINTRO);
                return;
            } else {
                if (_log.shouldWarn())
                    _log.warn("Invalid Outbound introduction packet (stale intro key) received for established connection, treating as non-establishment " + packet);
            }

            // ok, we couldn't handle it with the established stuff, so fall back
            // on earlier state packets
            receivePacket(reader, packet, PeerType.OUTBOUND_FALLBACK);
        }

        /**
         * The last step. The packet was decrypted with some key. Now get the message type
         * and send it to one of four places: The EstablishmentManager, IntroductionManager,
         * PeerTestManager, or InboundMessageFragments.
         *
         * SSU1 only.
         *
         * @param state non-null if fully established
         * @param outState non-null if outbound establishing in process
         * @param inState unused always null, TODO use for 48-byte destroys during inbound establishment
         * @param auth what type of authentication succeeded
         */
        private void handlePacket(UDPPacketReader reader, UDPPacket packet, PeerState state,
                                  OutboundEstablishState outState, InboundEstablishState inState,
                                  AuthType auth) {
            reader.initialize(packet);
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
                if (_log.shouldDebug())
                    _log.debug("Received packet from " + state.getRemoteHostId().toString() + " with skew of " + skew + "ms");
                if (auth == AuthType.SESSION && typeOK && (skewOK || state.getMessagesReceived() <= 0))
                    state.adjustClockSkew(skew);
            }
            _context.statManager().addRateData("udp.receivePacketSkew", skew);

            if (!_enableSSU2 && !_context.clock().getUpdatedSuccessfully()) {
                // adjust the clock one time in desperation
                _context.clock().setOffset(0 - skew, true);
                if (skew != 0) {
                    String source;
                    if (state != null)
                        source = state.getRemotePeer().toBase64();
                    else if (outState != null)
                        source = outState.getRemoteHostId().toString();
                    else
                        source = packet.getRemoteHost().toString();
                    _log.logAlways(Log.WARN, "NTP failure, SSU1 adjusted clock by " + DataHelper.formatDuration(Math.abs(skew)) +
                                             " source " + source);
                    skew = 0;
                }
            }

            if (skew > GRACE_PERIOD) {
                _context.statManager().addRateData("udp.droppedInvalidSkew", skew);
                if (state != null && skew > 4 * GRACE_PERIOD && state.getPacketsReceived() <= 0) {
                    _transport.sendDestroy(state, SSU2Util.REASON_SKEW);
                    _transport.dropPeer(state, true, "Clock skew");
                    if (state.getRemotePort() == 65520) {
                        // distinct port of buggy router
                        _context.banlist().banlistRouterHard(state.getRemotePeer(),
                                                                " <b>➜</b> " + _x("Excessive clock skew ({0})"),
                                                                DataHelper.formatDuration(skew));
                    } else {
                        _context.banlist().banlistRouter(DataHelper.formatDuration(skew),
                                                         state.getRemotePeer(),
                                                         " <b>➜</b> " + _x("Excessive clock skew ({0})"));
                    }
                    _context.statManager().addRateData("udp.destroyedInvalidSkew", skew);
                    if (_log.shouldWarn())
                        _log.warn("Dropping connection - packet too far in the past \n* Timestamp: " + new Date(sendOn) + packet +
                                  "; PeerState: " + state);
                } else {
                    if (_log.shouldWarn())
                        _log.warn("Packet too far in the past \n* Timestamp: " + new Date(sendOn) + packet +
                                  "; PeerState: " + state);
                }
                return;
            } else if (skew < 0 - GRACE_PERIOD) {
                _context.statManager().addRateData("udp.droppedInvalidSkew", 0-skew);
                if (state != null && skew < 0 - (4 * GRACE_PERIOD) && state.getPacketsReceived() <= 0) {
                    _transport.sendDestroy(state, SSU2Util.REASON_SKEW);
                    _transport.dropPeer(state, true, "Clock skew");
                    if (state.getRemotePort() == 65520) {
                        // distinct port of buggy router
                        _context.banlist().banlistRouterHard(state.getRemotePeer(),
                                                                " <b>➜</b> " + _x("Excessive clock skew ({0})"),
                                                                DataHelper.formatDuration(0 - skew));
                    } else {
                        _context.banlist().banlistRouter(DataHelper.formatDuration(0 - skew),
                                                         state.getRemotePeer(),
                                                         " <b>➜</b> " + _x("Excessive clock skew ({0})"));
                    }
                    _context.statManager().addRateData("udp.destroyedInvalidSkew", 0-skew);
                    if (_log.shouldWarn())
                        _log.warn("Dropping connection - packet too far in the future \n* Timestamp: " + new Date(sendOn) + packet +
                                  "; PeerState: " + state);
                } else {
                    if (_log.shouldWarn())
                        _log.warn("Packet too far in the future \n* Timestamp: " + new Date(sendOn) + packet +
                                  "; PeerState: " + state);
                }
                return;
            }

            RemoteHostId from = packet.getRemoteHost();

            switch (type) {
                case UDPPacket.PAYLOAD_TYPE_SESSION_REQUEST:
                    if (auth == AuthType.BOBINTRO) {
                        if (_log.shouldWarn())
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    _establisher.receiveSessionRequest(from, inState, reader);
                    break;
                case UDPPacket.PAYLOAD_TYPE_SESSION_CONFIRMED:
                    if (auth != AuthType.SESSION) {
                        if (_log.shouldWarn())
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    _establisher.receiveSessionConfirmed(from, inState, reader);
                    break;
                case UDPPacket.PAYLOAD_TYPE_SESSION_CREATED:
                    // this is the only type that allows BOBINTRO
                    if (auth != AuthType.BOBINTRO && auth != AuthType.SESSION) {
                        if (_log.shouldWarn())
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    _establisher.receiveSessionCreated(from, outState, reader);
                    break;
                case UDPPacket.PAYLOAD_TYPE_DATA:
                    if (auth != AuthType.SESSION) {
                        if (_log.shouldWarn())
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    if (outState != null)
                        state = _establisher.receiveData(outState);
                    if (_log.shouldDebug())
                        _log.debug("Received new DATA packet from " + state + packet);
                    UDPPacketReader.DataReader dr = reader.getDataReader();
                    if (state != null) {
                        if (_log.shouldDebug()) {
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
                    if (auth == AuthType.BOBINTRO) {
                        if (_log.shouldWarn())
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    if (_log.shouldDebug())
                        _log.debug("Received test packet: " + reader + " from " + from);
                    _testManager.receiveTest(from, state, auth == AuthType.SESSION, reader);
                    break;
                case UDPPacket.PAYLOAD_TYPE_RELAY_REQUEST:
                    if (auth == AuthType.BOBINTRO) {
                        if (_log.shouldWarn())
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    if (_log.shouldDebug())
                        _log.debug("Received Relay Request packet: " + reader + " from " + from);
                    _introManager.receiveRelayRequest(from, reader);
                    break;
                case UDPPacket.PAYLOAD_TYPE_RELAY_INTRO:
                    if (auth != AuthType.SESSION) {
                        if (_log.shouldWarn())
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    if (_log.shouldDebug())
                        _log.debug("Received Relay Intro packet: " + reader + " from " + from);
                    _introManager.receiveRelayIntro(from, reader);
                    break;
                case UDPPacket.PAYLOAD_TYPE_RELAY_RESPONSE:
                    if (auth == AuthType.BOBINTRO) {
                        if (_log.shouldWarn())
                            _log.warn("Dropping type " + type + " auth " + auth + packet);
                        break;
                    }
                    if (_log.shouldDebug())
                        _log.debug("Received Relay Response packet: " + reader + " from " + from);
                    _establisher.receiveRelayResponse(from, reader);
                    break;
                case UDPPacket.PAYLOAD_TYPE_SESSION_DESTROY:
                    if (auth == AuthType.BOBINTRO) {
                        if (_log.shouldWarn())
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
                    if (_log.shouldWarn())
                        _log.warn("Dropping type " + type + " auth " + auth + packet);
                    _context.statManager().addRateData("udp.droppedInvalidUnknown", packet.getLifetime());
                    return;
            }
        }
    }

    //// End SSU1-only methods ////

    //// Begin SSU2 Handling ////


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
        // decrypt header
        byte[] k1 = _transport.getSSU2StaticIntroKey();
        byte[] k2;
        SSU2Header.Header header;
        int type;
        if (state == null) {
            // Session Request, Token Request, Peer Test 5-7, or Hole Punch
            k2 = k1;
            header = SSU2Header.trialDecryptHandshakeHeader(packet, k1, k2);
            if (header == null ||
                header.getType() != SSU2Util.SESSION_REQUEST_FLAG_BYTE ||
                header.getVersion() != 2 ||
                header.getNetID() != _networkID) {
                if (header != null && _log.shouldInfo())
                    _log.info("Does not decrypt as Session Request, attempting to decrypt as TokenRequest / PeerTest/ HolePunch \n* " + header + " from " + from);
                // The first 32 bytes were fine, but it corrupted the next 32 bytes
                // TODO make this more efficient, just take the first 32 bytes
                header = SSU2Header.trialDecryptLongHeader(packet, k1, k2);
                if (header == null ||
                    header.getVersion() != 2 ||
                    header.getNetID() != _networkID) {
                    // typical case, SSU 1 that didn't validate, will be logged at WARN level above
                    // in group 4 receive packet
                    //if (_log.shouldDebug())
                    //    _log.debug("Does not decrypt as Session Request, Token Request, or Peer Test: " + header);
                    if (header != null) {
                        // conn ID decryption is the same for short and long header, with k1
                        // presumably a data packet, either ip/port changed, or a race during establishment?
                        // lookup peer state by conn ID, pass over for decryption with the proper k2
                        long id = header.getDestConnID();
                        PeerState2 ps2 = _transport.getPeerState(id);
                        if (ps2 != null) {
                            if (_log.shouldInfo())
                                _log.info("Migrated " + packet.getPacket().getLength() + " byte packet from " + from + ps2);
                            ps2.receivePacket(from, packet);
                            return true;
                        }
                        PeerStateDestroyed dead = _transport.getRecentlyClosed(id);
                        if (dead != null) {
                            // Probably termination ack.
                            // Prevent attempted SSU1 fallback processing and adding to fail cache
                            if (_log.shouldDebug())
                                _log.debug("Handling " + packet.getPacket().getLength() + " byte packet from " + from +
                                           " for recently closed ID " + id);
                            dead.receivePacket(from, packet);
                            return true;
                        }
                    }
                    return false;
                }
                type = header.getType();
                if (type == SSU2Util.SESSION_CONFIRMED_FLAG_BYTE) {
                    // one in a million decrypt of SSU 1 packet with type/version/netid all correct?
                    // can't have session confirmed with null state, avoid NPE below
                    return false;
                }
                if (type == SSU2Util.SESSION_REQUEST_FLAG_BYTE &&
                    packet.getPacket().getLength() == SSU2Util.MIN_HANDSHAKE_DATA_LEN - 1) {
                    // i2pd short 87 byte session request thru 0.9.56, drop packet
                    if (_log.shouldWarn())
                        _log.warn("Received short SessionRequest (87 bytes) from " + from);
                    return true;
                }
            } else {
                type = SSU2Util.SESSION_REQUEST_FLAG_BYTE;
            }
        } else {
            // Session Request (after Retry) or Session Confirmed
            // or retransmitted Session Request or Token Rquest
            k2 = state.getRcvHeaderEncryptKey2();
            if (k2 == null) {
                // Session Request after Retry
                k2 = k1;
                header = SSU2Header.trialDecryptHandshakeHeader(packet, k1, k2);
                if (header == null ||
                    header.getType() != SSU2Util.SESSION_REQUEST_FLAG_BYTE ||
                    header.getVersion() != 2 ||
                    header.getNetID() != _networkID) {
                    // possibly token-request-after-retry? let's see...
                    header = SSU2Header.trialDecryptLongHeader(packet, k1, k2);
                    if (header != null && header.getType() == SSU2Util.SESSION_REQUEST_FLAG_BYTE &&
                        header.getVersion() == 2 && header.getNetID() == _networkID &&
                        packet.getPacket().getLength() == 87) {
                        // i2pd short 87 byte session request thru 0.9.56, drop packet
                        if (_log.shouldWarn())
                            _log.warn("Received short SessionRequest (87 bytes) after Retry on " + state);
                        return true;
                    }
                    if (header == null ||
                        header.getType() != SSU2Util.TOKEN_REQUEST_FLAG_BYTE ||
                        header.getVersion() != 2 ||
                        header.getNetID() != _networkID) {
                        if (_log.shouldWarn())
                            _log.warn("Failed to decrypt Session or Token Request after Retry \n* " + header +
                                      " (" + packet.getPacket().getLength() + " bytes) on " + state);
                        return false;
                    }
                    //yes, retransmitted token request
                }
                if (header.getSrcConnID() != state.getSendConnID()) {
                    if (_log.shouldWarn())
                        _log.warn("Received BAD Source Connection ID \n* " + header +
                                  " (" + packet.getPacket().getLength() + " bytes) on " + state);
                    // TODO could be a retransmitted Session Request,
                    // tell establisher?
                    return false;
                }
                if (header.getDestConnID() != state.getRcvConnID()) {
                    // i2pd bug changing after retry, thru 0.9.56, drop packet
                    if (_log.shouldWarn())
                        _log.warn("Received BAD Destination Connection ID \n* " + header +
                                  " (" + packet.getPacket().getLength() + " bytes) on " + state);
                    return true;
                }
                type = header.getType();
            } else {
                // Session Confirmed or retransmitted Session Request or Token Request
                header = SSU2Header.trialDecryptShortHeader(packet, k1, k2);
                if (header == null) {
                    // Java I2P thru 0.9.56 retransmits session confirmed with 1-2 byte packets
                    if (_log.shouldWarn())
                        _log.warn("Received SessionConfirmed packet was too short (" +
                                  + packet.getPacket().getLength() + " bytes) on " + state);
                    return false;
                }
                if (header.getDestConnID() != state.getRcvConnID()) {
                    if (_log.shouldWarn())
                        _log.warn("Received BAD Destination Connection ID \n* " + header + " on " + state);
                    return false;
                }
                if (header.getPacketNumber() != 0 ||
                    header.getType() != SSU2Util.SESSION_CONFIRMED_FLAG_BYTE) {
                    if (_log.shouldInfo())
                        _log.info("Queueing possible data packet (" + packet.getPacket().getLength() + " bytes) on: " + state);
                    // TODO either attempt to decrypt as a retransmitted
                    // Session Request or Token Request,
                    // or just tell establisher so it can retransmit Session Created or Retry

                    // Possible ordering issues and races:
                    // Case 1: Data packets before (possibly lost or out-of-order) Session Confirmed
                    // Case 2: Data packets after Session Confirmed but it wasn't processed yet
                    // Queue the packet with the state for processing
                    state.queuePossibleDataPacket(packet);
                    return true;
                }
                type = SSU2Util.SESSION_CONFIRMED_FLAG_BYTE;
            }
        }

        // all good
        SSU2Header.acceptTrialDecrypt(packet, header);
        if (type == SSU2Util.SESSION_REQUEST_FLAG_BYTE) {
            if (_log.shouldDebug())
                _log.debug("Received a SessionRequest on " + state);
            _establisher.receiveSessionOrTokenRequest(from, state, packet);
        } else if (type == SSU2Util.TOKEN_REQUEST_FLAG_BYTE) {
            if (_log.shouldDebug())
                _log.debug("Received a TokenRequest on " + state);
            _establisher.receiveSessionOrTokenRequest(from, state, packet);
        } else if (type == SSU2Util.SESSION_CONFIRMED_FLAG_BYTE) {
            if (_log.shouldDebug())
                _log.debug("Received a SessionConfirmed on " + state);
            _establisher.receiveSessionConfirmed(state, packet);
        } else if (type == SSU2Util.PEER_TEST_FLAG_BYTE) {
            if (_log.shouldDebug())
                _log.debug("Received a PeerTest");
            _testManager.receiveTest(from, packet);
        } else if (type == SSU2Util.HOLE_PUNCH_FLAG_BYTE) {
            if (_log.shouldDebug())
                _log.debug("Received a HolePunch");
            _establisher.receiveHolePunch(from, packet);
        } else {
            if (_log.shouldWarn())
                _log.warn("Received UNKNOWN SSU2 message \n* " + header + " from " + from);
        }
        return true;
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
                    if (_log.shouldWarn())
                        _log.warn("Received BAD Destination Connection ID \n* " + header);
                    return false;
                }
            }
        } else {
            // we have only sent a Token Request
            header = null;
        }
        int type;
        if (header == null ||
            header.getType() != SSU2Util.SESSION_CREATED_FLAG_BYTE ||
            header.getVersion() != 2 ||
            header.getNetID() != _networkID) {
            if (_log.shouldInfo())
                _log.info("Does not decrypt as SessionCreated, attempting to decrypt as Retry \n* " + header);
            k2 = state.getRcvRetryHeaderEncryptKey2();
            header = SSU2Header.trialDecryptLongHeader(packet, k1, k2);
            if (header == null ||
                header.getType() != SSU2Util.RETRY_FLAG_BYTE ||
                header.getVersion() != 2 ||
                header.getNetID() != _networkID) {
                if (_log.shouldInfo())
                    _log.info("Does not decrypt as SessionCreated or Retry \n* " + header + " on " + state);
                return false;
            }
            type = SSU2Util.RETRY_FLAG_BYTE;
        } else {
            type = SSU2Util.SESSION_CREATED_FLAG_BYTE;
        }
        if (header.getDestConnID() != state.getRcvConnID()) {
            if (_log.shouldWarn())
                _log.warn("Received BAD Destination Connection ID \n* " + header);
            return false;
        }
        if (header.getSrcConnID() != state.getSendConnID()) {
            if (_log.shouldWarn())
                _log.warn("Received BAD Source Connection ID \n* " + header);
            return false;
        }

        // all good
        SSU2Header.acceptTrialDecrypt(packet, header);
        if (type == SSU2Util.SESSION_CREATED_FLAG_BYTE) {
            if (_log.shouldDebug())
                _log.debug("Received a SessionCreated on " + state);
            _establisher.receiveSessionCreated(state, packet);
        } else {
            if (_log.shouldDebug())
                _log.debug("Received a Retry on " + state);
            _establisher.receiveRetry(state, packet);
        }
        return true;
    }


    //// End SSU2 Handling ////


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
