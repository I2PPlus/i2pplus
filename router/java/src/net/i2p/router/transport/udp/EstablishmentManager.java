package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Banlist;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import static net.i2p.router.transport.udp.InboundEstablishState.InboundState.*;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.*;
import net.i2p.router.util.DecayingHashSet;
import net.i2p.router.util.DecayingBloomFilter;
import net.i2p.util.Addresses;
import net.i2p.util.I2PThread;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Coordinate the establishment of new sessions - both inbound and outbound.
 * This has its own thread to add packets to the packet queue when necessary,
 * as well as to drop any failed establishment attempts.
 *
 */
class EstablishmentManager {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    private final PacketBuilder _builder;
    private final int _networkID;

    // SSU 2
    private final PacketBuilder2 _builder2;
    private final boolean _enableSSU2;
    private final Map<RemoteHostId, Token> _outboundTokens;
    private final Map<RemoteHostId, Token> _inboundTokens;

    /** map of RemoteHostId to InboundEstablishState */
    private final ConcurrentHashMap<RemoteHostId, InboundEstablishState> _inboundStates;

    /**
     * Map of RemoteHostId to OutboundEstablishState.
     * The key could be either an IP/Port (for direct) or
     * a Hash (for indirect, before the RelayResponse is received).
     * Once the RelayResponse is received we change the key.
     */
    private final ConcurrentHashMap<RemoteHostId, OutboundEstablishState> _outboundStates;

    /** map of RemoteHostId to List of OutNetMessage for messages exceeding capacity */
    private final ConcurrentHashMap<RemoteHostId, List<OutNetMessage>> _queuedOutbound;

    /**
     *  Map of nonce (Long) to OutboundEstablishState.
     *  Only for indirect, before we receive the RelayResponse.
     *  This is so we can lookup state for the RelayResponse.
     *  After we receive the relay response, _outboundStates is keyed by actual IP.
     */
    private final ConcurrentHashMap<Long, OutboundEstablishState> _liveIntroductions;

    /**
     *  Map of claimed IP/port to OutboundEstablishState.
     *  Only for indirect, before we receive the RelayResponse.
     *  This is so we can lookup a pending introduction by IP
     *  even before we know the "real" IP, so we can match an inbound packet.
     *  After we receive the relay response, _outboundStates is keyed by actual IP.
     */
    private final ConcurrentHashMap<RemoteHostId, OutboundEstablishState> _outboundByClaimedAddress;

    /**
     *  Map of router hash to OutboundEstablishState.
     *  Only for indirect, after we receive the RelayResponse.
     *  This is so we can lookup a pending connection by Hash
     *  even after we've got the IP/port, so we can match a subsequent outbound packet.
     *  Before we receive the relay response, _outboundStates is keyed by hash.
     */
    private final ConcurrentHashMap<Hash, OutboundEstablishState> _outboundByHash;

    private volatile boolean _alive;
    private final Object _activityLock;
    private int _activity;

    /** "bloom filter" */
    private final DecayingBloomFilter _replayFilter;

    /** max outbound in progress - max inbound is half of this */
    private final int DEFAULT_MAX_CONCURRENT_ESTABLISH;
//    private static final int DEFAULT_LOW_MAX_CONCURRENT_ESTABLISH = SystemVersion.isSlow() ? 20 : 40;
//    private static final int DEFAULT_HIGH_MAX_CONCURRENT_ESTABLISH = 150;
    private static final int DEFAULT_LOW_MAX_CONCURRENT_ESTABLISH = SystemVersion.isSlow() ? 50 : 200;
    private static final int DEFAULT_HIGH_MAX_CONCURRENT_ESTABLISH = SystemVersion.isSlow() ? 200 : 400;
    private static final String PROP_MAX_CONCURRENT_ESTABLISH = "i2np.udp.maxConcurrentEstablish";

    /** max pending outbound connections (waiting because we are at MAX_CONCURRENT_ESTABLISH) */
    private static final int MAX_QUEUED_OUTBOUND = 50;

    /** max queued msgs per peer while the peer connection is queued */
    private static final int MAX_QUEUED_PER_PEER = 16;

    private static final long MAX_NONCE = 0xFFFFFFFFl;

    /**
     * Kill any outbound that takes more than this.
     * Two round trips (Req-Created-Confirmed-Data) for direct;
     * 3 1/2 round trips (RReq-RResp+Intro-HolePunch-Req-Created-Confirmed-Data) for indirect.
     * Note that this is way too long for us to be able to fall back to NTCP
     * for individual messages unless the message timer fires first.
     * But SSU probably isn't higher priority than NTCP.
     * And it's important to not fail an establishment too soon and waste it.
     */
    private static final int MAX_OB_ESTABLISH_TIME = 35*1000;

    /**
     * Kill any inbound that takes more than this
     * One round trip (Created-Confirmed)
     */
    private static final int MAX_IB_ESTABLISH_TIME = 15*1000;

    /** max wait before receiving a response to a single message during outbound establishment */
    public static final int OB_MESSAGE_TIMEOUT = 15*1000;

    /** for the DSM and or netdb store */
    private static final int DATA_MESSAGE_TIMEOUT = 10*1000;

    /**
     * Java I2P has always parsed the length of the extended options field,
     * but i2pd hasn't recognized it until this release.
     * No matter, the options weren't defined until this release anyway.
     *
     */
    private static final String VERSION_ALLOW_EXTENDED_OPTIONS = "0.9.24";
    private static final String PROP_DISABLE_EXT_OPTS = "i2np.udp.disableExtendedOptions";

    // SSU 2
    private static final int MAX_TOKENS = 512;
    public static final long IB_TOKEN_EXPIRATION = 60*60*1000L;


    public EstablishmentManager(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(EstablishmentManager.class);
        _networkID = ctx.router().getNetworkID();
        _transport = transport;
        _builder = transport.getBuilder();
        _builder2 = transport.getBuilder2();
        _enableSSU2 = _builder2 != null;
        _inboundStates = new ConcurrentHashMap<RemoteHostId, InboundEstablishState>();
        _outboundStates = new ConcurrentHashMap<RemoteHostId, OutboundEstablishState>();
        _queuedOutbound = new ConcurrentHashMap<RemoteHostId, List<OutNetMessage>>();
        _liveIntroductions = new ConcurrentHashMap<Long, OutboundEstablishState>();
        _outboundByClaimedAddress = new ConcurrentHashMap<RemoteHostId, OutboundEstablishState>();
        _outboundByHash = new ConcurrentHashMap<Hash, OutboundEstablishState>();
        if (_enableSSU2) {
            _inboundTokens = new LHMCache<RemoteHostId, Token>(MAX_TOKENS);
            _outboundTokens = new LHMCache<RemoteHostId, Token>(MAX_TOKENS);
        } else {
            _inboundTokens = null;
            _outboundTokens = null;
        }

        _activityLock = new Object();
        _replayFilter = new DecayingHashSet(ctx, 10*60*1000, 8, "SSU-DH-X");
        DEFAULT_MAX_CONCURRENT_ESTABLISH = Math.max(DEFAULT_LOW_MAX_CONCURRENT_ESTABLISH,
                                                    Math.min(DEFAULT_HIGH_MAX_CONCURRENT_ESTABLISH,
                                                             ctx.bandwidthLimiter().getOutboundKBytesPerSecond() / 2));
        _context.statManager().createRateStat("udp.inboundEstablishTime", "Time to establish new inbound session (ms)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.outboundEstablishTime", "Time to establish new outbound session (ms)", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.inboundEstablishFailedState", "What state a failed inbound establishment request fails in", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.outboundEstablishFailedState", "What state a failed outbound establishment request fails in", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendIntroRelayRequest", "How often we send relay request to reach a peer", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendIntroRelayTimeout", "Relay request timeouts before response (target or intro peer offline)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveIntroRelayResponse", "How long it took to receive a relay response (ms)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.establishDropped", "Dropped an inbound establish message", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.establishRejected", "Pending outbound connections when we refuse to add any more", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.establishOverflow", "Messages queued up on a pending connection when it was too much", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.establishBadIP", "Received IP or port was bad", "Transport [UDP]", UDPTransport.RATES);
        // following are for PeerState
        _context.statManager().createRateStat("udp.congestionOccurred", "Size of CWIN when congestion occurred (duration = sendBps)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.congestedRTO", "RTO after congestion (duration = RTT dev)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendACKPartial", "Number of partial ACKs sent", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.sendBps", "How fast we are transmitting when a packet is acked", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receiveBps", "How fast we are receiving when a packet is fully received (at most one per second)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.mtuIncrease", "Number of resends to peer when MTU was increased", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.mtuDecrease", "Number of resends to peer when MTU was decreased", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.rejectConcurrentActive", "Messages in transit to peer when we reject it", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.allowConcurrentActive", "Messages in transit to peer when we accept it", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.rejectConcurrentSequence", "Consecutive concurrency rejections when we stop rejecting", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.queueDropSize", "How many messages were queued up when it was considered full, causing a tail drop?", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.queueAllowTotalLifetime", "When a peer is retransmitting and we probabalistically allow a new message, what is the sum of the pending message lifetimes? (period is the new message's lifetime)?", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.dupDHX", "Session request replay (duplicate X)", "Transport [UDP]", new long[] { 24*60*60*1000L } );
    }

    public synchronized void startup() {
        _alive = true;
        I2PThread t = new I2PThread(new Establisher(), "UDPEstablisher", true);
        t.setPriority(I2PThread.MAX_PRIORITY - 1);
        t.start();
    }

    public synchronized void shutdown() {
        _alive = false;
        notifyActivity();
    }

    /**
     * Grab the active establishing state
     * @return null if none
     */
    InboundEstablishState getInboundState(RemoteHostId from) {
            InboundEstablishState state = _inboundStates.get(from);
            // if ( (state == null) && (_log.shouldDebug()) )
            //     _log.debug("No inbound states for " + from + ", with remaining: " + _inboundStates);
            return state;
    }

    /**
     * Grab the active establishing state
     * @return null if none
     */
    OutboundEstablishState getOutboundState(RemoteHostId from) {
            OutboundEstablishState state = _outboundStates.get(from);
            if (state == null) {
                state = _outboundByClaimedAddress.get(from);
                if (state != null && _log.shouldInfo())
                    _log.info("Found by claimed address: " + state);
            }
            // if ( (state == null) && (_log.shouldDebug()) )
            //     _log.debug("No outbound states for " + from + ", with remaining: " + _outboundStates);
            return state;
    }

    /**
     * How many concurrent outbound sessions to deal with
     */
    private int getMaxConcurrentEstablish() {
        return _context.getProperty(PROP_MAX_CONCURRENT_ESTABLISH, DEFAULT_MAX_CONCURRENT_ESTABLISH);
    }

    /**
     * Send the message to its specified recipient by establishing a connection
     * with them and sending it off.  This call does not block, and on failure,
     * the message is failed.
     *
     * Note - if we go back to multiple PacketHandler threads, this may need more locking.
     */
    public void establish(OutNetMessage msg) {
        establish(msg, true);
    }

    /**
     *  @param queueIfMaxExceeded true normally, false if called from locked_admit so we don't loop
     *  @since 0.9.2
     */
    private void establish(OutNetMessage msg, boolean queueIfMaxExceeded) {
        RouterInfo toRouterInfo = msg.getTarget();
        RouterAddress ra = _transport.getTargetAddress(toRouterInfo);
        if (ra == null) {
            _transport.failed(msg, "Remote peer has no address, cannot establish");
            return;
        }
        RouterIdentity toIdentity = toRouterInfo.getIdentity();
        Hash toHash = toIdentity.calculateHash();
        int id = toRouterInfo.getNetworkId();
        if (id != _networkID) {
            if (id == -1)
                _context.banlist().banlistRouter(toHash, " <b>➜</b> No network specified", null, null, _context.clock().now() + Banlist.BANLIST_DURATION_NO_NETWORK);
            else
                _context.banlist().banlistRouterForever(toHash, " <b>➜</b> Not in our network: " + id);
            if (_log.shouldWarn())
                _log.warn("Not in our network: " + toRouterInfo, new Exception());
            _transport.markUnreachable(toHash);
            _transport.failed(msg, "Remote peer is on the wrong network, cannot establish connection");
            return;
        }
        UDPAddress addr = new UDPAddress(ra);
        RemoteHostId maybeTo = null;
        InetAddress remAddr = addr.getHostAddress();
        int port = addr.getPort();

        // check for validity and existing inbound state, using the
        // claimed address (which we won't be using if indirect)
        if (remAddr != null && port > 0 && port <= 65535) {
            maybeTo = new RemoteHostId(remAddr.getAddress(), port);

            if ((!_transport.isValid(maybeTo.getIP())) ||
                (Arrays.equals(maybeTo.getIP(), _transport.getExternalIP()) && !_transport.allowLocal())) {
                _transport.failed(msg, "Remote peer's IP isn't valid");
                _transport.markUnreachable(toHash);
                //_context.banlist().banlistRouter(msg.getTarget().getIdentity().calculateHash(), "Invalid SSU address", UDPTransport.STYLE);
                _context.statManager().addRateData("udp.establishBadIP", 1);
                return;
            }

            InboundEstablishState inState = _inboundStates.get(maybeTo);
            if (inState != null) {
                // we have an inbound establishment in progress, queue it there instead
                synchronized (inState) {
                    switch (inState.getState()) {
                      case IB_STATE_UNKNOWN:
                      case IB_STATE_REQUEST_RECEIVED:
                      case IB_STATE_CREATED_SENT:
                      case IB_STATE_CONFIRMED_PARTIALLY:
                      case IB_STATE_CONFIRMED_COMPLETELY:
                        // queue it
                        inState.addMessage(msg);
                        if (_log.shouldWarn())
                            _log.debug("Outbound message queued to inbound establish state");
                        break;

                      case IB_STATE_COMPLETE:
                        // race, send it out (but don't call _transport.send() again and risk a loop)
                        _transport.sendIfEstablished(msg);
                        break;

                      case IB_STATE_FAILED:
                        // race, failed
                        _transport.failed(msg, "Outbound message failed during inbound establish");
                        break;
                    }
                }
                return;
            }
        }

        RemoteHostId to;
        boolean isIndirect = addr.getIntroducerCount() > 0 || maybeTo == null;
        if (isIndirect) {
            to = new RemoteHostId(toHash);
        } else {
            to = maybeTo;
        }

        OutboundEstablishState state = null;
        int deferred = 0;
        boolean rejected = false;
        int queueCount = 0;

            state = _outboundStates.get(to);
            if (state == null) {
                state = _outboundByHash.get(toHash);
                if (state != null && _log.shouldInfo())
                    _log.info("Found by hash: " + state);
            }
            if (state == null) {
                if (queueIfMaxExceeded && _outboundStates.size() >= getMaxConcurrentEstablish()) {
                    if (_queuedOutbound.size() >= MAX_QUEUED_OUTBOUND && !_queuedOutbound.containsKey(to)) {
                        rejected = true;
                    } else {
                        List<OutNetMessage> newQueued = new ArrayList<OutNetMessage>(MAX_QUEUED_PER_PEER);
                        List<OutNetMessage> queued = _queuedOutbound.putIfAbsent(to, newQueued);
                        if (queued == null) {
                            queued = newQueued;
                            if (_log.shouldWarn())
                                _log.warn("Queueing outbound establish to " + to + ", increase " + PROP_MAX_CONCURRENT_ESTABLISH);
                        }
                        // this used to be inside a synchronized (_outboundStates) block,
                        // but that's now a CHM, so protect the ArrayList
                        // There are still races possible but this should prevent AIOOBE and NPE
                        synchronized (queued) {
                            queueCount = queued.size();
                            if (queueCount < MAX_QUEUED_PER_PEER) {
                                queued.add(msg);
                                // increment for the stat below
                                queueCount++;
                            } else {
                                rejected = true;
                            }
                            deferred = _queuedOutbound.size();
                        }
                    }
                } else {
                    // must have a valid session key
                    byte[] keyBytes;
                    int version = _transport.getSSUVersion(ra);
                    if (version == 1) {
                        keyBytes = addr.getIntroKey();
                    } else {
                        String siv = ra.getOption("i");
                        if (siv != null)
                            keyBytes = Base64.decode(siv);
                        else
                            keyBytes = null;
                    }
                    if (keyBytes == null) {
                        _transport.markUnreachable(toHash);
                        _transport.failed(msg, "Peer has no key, cannot establish connection -> marking unreachable");
                        return;
                    }
                    SessionKey sessionKey;
                    try {
                        sessionKey = new SessionKey(keyBytes);
                    } catch (IllegalArgumentException iae) {
                        _transport.markUnreachable(toHash);
                        _transport.failed(msg, "Peer has bad key, cannot establish connection -> marking unreachable");
                        return;
                    }
                    if (version == 1) {
                    boolean allowExtendedOptions = VersionComparator.comp(toRouterInfo.getVersion(),
                                                                          VERSION_ALLOW_EXTENDED_OPTIONS) >= 0
                                                   && !_context.getBooleanProperty(PROP_DISABLE_EXT_OPTS);
                    // w/o ext options, it's always 'requested', no need to set
                    // don't ask if they are indirect
                    boolean requestIntroduction = allowExtendedOptions && !isIndirect &&
                                                  _transport.introducersMaybeRequired(TransportUtil.isIPv6(ra));
                    state = new OutboundEstablishState(_context, maybeTo, to,
                                                       toIdentity, allowExtendedOptions,
                                                       requestIntroduction,
                                                       sessionKey, addr, _transport.getDHFactory());
                    } else if (version == 2) {
                        boolean requestIntroduction = SSU2Util.ENABLE_RELAY && !isIndirect &&
                                                      _transport.introducersMaybeRequired(TransportUtil.isIPv6(ra));
                        state = new OutboundEstablishState2(_context, _transport, maybeTo, to,
                                                            toIdentity, requestIntroduction, sessionKey, ra, addr);
                    } else {
                        // shouldn't happen
                        _transport.failed(msg, "OB to bad addr? " + ra);
                        return;
                    }
                    OutboundEstablishState oldState = _outboundStates.putIfAbsent(to, state);
                    boolean isNew = oldState == null;
                    if (isNew) {
                        if (isIndirect && maybeTo != null)
                            _outboundByClaimedAddress.put(maybeTo, state);
                        if (_log.shouldDebug())
                            _log.debug("Adding new Outbound connection to: " + state);
                    } else {
                        // whoops, somebody beat us to it, throw out the state we just created
                        state = oldState;
                    }
                }
            }
            if (state != null) {
                state.addMessage(msg);
                List<OutNetMessage> queued = _queuedOutbound.remove(to);
                if (queued != null) {
                    // see comments above
                    synchronized (queued) {
                        for (OutNetMessage m : queued) {
                            state.addMessage(m);
                        }
                    }
                }
            }

        if (rejected) {
            if (_log.shouldWarn())
                _log.warn("Too many pending, rejecting outbound establish to " + to);
            _transport.failed(msg, "Too many pending outbound connections");
            _context.statManager().addRateData("udp.establishRejected", deferred);
            return;
        }
        if (queueCount >= MAX_QUEUED_PER_PEER) {
            _transport.failed(msg, "Too many pending messages for the given peer");
            _context.statManager().addRateData("udp.establishOverflow", queueCount, deferred);
            return;
        }

        if (deferred > 0)
            msg.timestamp("Too many deferred establishers");
        else if (state != null)
            msg.timestamp("Establish state already waiting");
        notifyActivity();
    }

    /**
     * How many concurrent inbound sessions to deal with
     */
    private int getMaxInboundEstablishers() {
        return getMaxConcurrentEstablish()/2;
    }

    /**
     * Should we allow another inbound establishment?
     * Used to throttle outbound hole punches.
     * @since 0.9.2
     */
    public boolean shouldAllowInboundEstablishment() {
        return _inboundStates.size() < getMaxInboundEstablishers();
    }

    /**
     * Got a SessionRequest (initiates an inbound establishment)
     *
     * SSU 1 only.
     *
     * @param state as looked up in PacketHandler, but probably null unless retransmitted
     */
    void receiveSessionRequest(RemoteHostId from, InboundEstablishState state, UDPPacketReader reader) {
        if (!TransportUtil.isValidPort(from.getPort()) || !_transport.isValid(from.getIP())) {
            if (_log.shouldInfo())
                _log.info("Received invalid SessionRequest from: " + from);
            return;
        }

        boolean isNew = false;

            if (state == null)
                state = _inboundStates.get(from);
            if (state == null) {
                // TODO this is insufficient to prevent DoSing, especially if
                // IP spoofing is used. For further study.
                if (!shouldAllowInboundEstablishment()) {
                    if (_log.shouldWarn()) {
                        _log.warn("Dropping InboundEstablish -> increase " + PROP_MAX_CONCURRENT_ESTABLISH);
                        if (_log.shouldDebug()) {
                            StringBuilder buf = new StringBuilder(4096);
                            buf.append("Active: ").append(_inboundStates.size()).append('\n');
                            for (InboundEstablishState ies : _inboundStates.values()) {
                                 buf.append(ies.toString()).append('\n');
                            }
                            _log.debug(buf.toString());
                        }
                    }
                    _context.statManager().addRateData("udp.establishDropped", 1);
                    return; // drop the packet
                }

                if (_context.blocklist().isBlocklisted(from.getIP())) {
                    if (_log.shouldInfo())
                        _log.info("Received SessionRequest from blocklisted IP address: " + from);
                    _context.statManager().addRateData("udp.establishBadIP", 1);
                    return; // drop the packet
                }
                if (!_transport.allowConnection())
                    return; // drop the packet
                byte[] fromIP = from.getIP();
                state = new InboundEstablishState(_context, fromIP, from.getPort(),
                                                  _transport.getExternalPort(fromIP.length == 16),
                                                  _transport.getDHBuilder(),
                                                  reader.getSessionRequestReader());

                if (_replayFilter.add(state.getReceivedX(), 0, 8)) {
                    if (_log.shouldWarn())
                        _log.warn("Duplicate X in SessionRequest from: " + from);
                    _context.statManager().addRateData("udp.dupDHX", 1);
                    return; // drop the packet
                }

                InboundEstablishState oldState = _inboundStates.putIfAbsent(from, state);
                isNew = oldState == null;
                if (!isNew)
                    // whoops, somebody beat us to it, throw out the state we just created
                    state = oldState;
            }

        if (isNew) {
            // Don't offer to relay to privileged ports.
            // Only offer for an IPv4 session.
            // TODO if already we have their RI, only offer if they need it (no 'C' cap)
            // if extended options, only if they asked for it
            if (state.isIntroductionRequested() &&
                state.getSentPort() >= 1024 &&
                _transport.canIntroduce(state.getSentIP().length == 16)) {
                // ensure > 0
                long tag = 1 + _context.random().nextLong(MAX_TAG_VALUE);
                state.setSentRelayTag(tag);
            } else {
                // we got an IB even though we were firewalled, hidden, not high cap, etc.
            }
            if (_log.shouldDebug())
                _log.debug("Received NEW SessionRequest: " + state);
        } else {
            if (_log.shouldDebug())
                _log.debug("Received duplicate SessionRequest from: " + state);
        }

        notifyActivity();
    }

    /**
     * Got a SessionRequest OR a TokenRequest (initiates an inbound establishment)
     *
     * SSU 2 only.
     * @param state as looked up in PacketHandler, but null unless retransmitted or retry sent
     * @param packet header decrypted only
     * @since 0.9.54
     */
    void receiveSessionOrTokenRequest(RemoteHostId from, InboundEstablishState2 state, UDPPacket packet) {
        if (!TransportUtil.isValidPort(from.getPort()) || !_transport.isValid(from.getIP())) {
            if (_log.shouldWarn())
                _log.warn("Receive session request from invalid: " + from);
            return;
        }
        boolean isNew = false;
        if (state == null) {
            // TODO this is insufficient to prevent DoSing, especially if
            // IP spoofing is used. For further study.
            if (!shouldAllowInboundEstablishment()) {
                if (_log.shouldWarn())
                    _log.warn("Dropping inbound establish, increase " + PROP_MAX_CONCURRENT_ESTABLISH);
                _context.statManager().addRateData("udp.establishDropped", 1);
                return; // drop the packet
            }
            if (_context.blocklist().isBlocklisted(from.getIP())) {
                if (_log.shouldInfo())
                    _log.info("Received session request from blocklisted IP: " + from);
                _context.statManager().addRateData("udp.establishBadIP", 1);
                return; // drop the packet
            }
            if (!_transport.allowConnection())
                return; // drop the packet
            try {
                state = new InboundEstablishState2(_context, _transport, packet);
            } catch (GeneralSecurityException gse) {
                if (_log.shouldWarn())
                    _log.warn("Corrupt Session/Token Request from: " + from, gse);
                _context.statManager().addRateData("udp.establishDropped", 1);
                return;
            }

          /**** TODO
            if (_replayFilter.add(state.getReceivedX(), 0, 8)) {
                if (_log.shouldWarn())
                    _log.warn("Duplicate X in session request from: " + from);
                _context.statManager().addRateData("udp.dupDHX", 1);
                return; // drop the packet
            }
          ****/

            InboundEstablishState oldState = _inboundStates.putIfAbsent(from, state);
            isNew = oldState == null;
            if (!isNew) {
                // whoops, somebody beat us to it, throw out the state we just created
                if (oldState.getVersion() == 2)
                    state = (InboundEstablishState2) oldState;
                // else don't cast, this is only for printing below
            }
        } else {
            try {
                state.receiveSessionRequestAfterRetry(packet);
            } catch (GeneralSecurityException gse) {
                if (_log.shouldWarn())
                    _log.warn("Corrupt Session Request after Retry from: " + state, gse);
                return;
            }
        }

        if (_log.shouldDebug()) {
            if (isNew)
                _log.debug("Received NEW session/token request " + state);
            else
                _log.debug("Receive DUP session/token request from: " + state);
        }
        // call for both Session and Token request, why not
        if (SSU2Util.ENABLE_RELAY &&
            state.isIntroductionRequested() &&
            state.getSentRelayTag() == 0 &&     // only set once
            state.getSentPort() >= 1024 &&
            _transport.canIntroduce(state.getSentIP().length == 16)) {
            long tag = 1 + _context.random().nextLong(MAX_TAG_VALUE);
            state.setSentRelayTag(tag);
        }
        notifyActivity();
    }

    /**
     * got a SessionConfirmed (should only happen as part of an inbound
     * establishment)
     *
     * SSU 1 only.
     *
     * @param state as looked up in PacketHandler, if null is probably retransmitted
     */
    void receiveSessionConfirmed(RemoteHostId from, InboundEstablishState state, UDPPacketReader reader) {
        if (state == null)
            state = _inboundStates.get(from);
        if (state != null) {
            state.receiveSessionConfirmed(reader.getSessionConfirmedReader());
            notifyActivity();
            if (_log.shouldDebug())
                _log.debug("Received SessionConfirmed from: " + state);
        } else {
            if (_log.shouldInfo())
                _log.info("Received possible duplicate SessionConfirmed from: " + from);
        }
    }

    /**
     * got a SessionConfirmed (should only happen as part of an inbound
     * establishment)
     *
     * SSU 2 only.
     * @param state non-null
     * @param packet header decrypted only
     * @since 0.9.54
     */
    void receiveSessionConfirmed(InboundEstablishState2 state, UDPPacket packet) {
        try {
            state.receiveSessionConfirmed(packet);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Corrupt Session Confirmed on: " + state, gse);
            state.fail();
            return;
        }
        InboundEstablishState.InboundState istate = state.getState();
        if (istate == IB_STATE_CONFIRMED_COMPLETELY ||
            istate == IB_STATE_COMPLETE) {
        // we are done, go right to ps2
        handleCompletelyEstablished(state);
        } else {
            // More RI blocks to come, TODO
        }
        notifyActivity();
        if (_log.shouldDebug())
            _log.debug("Receive session confirmed from: " + state);
    }

    /**
     * Got a SessionCreated (in response to our outbound SessionRequest)
     *
     * SSU 1 only.
     *
     * @param state as looked up in PacketHandler, if null is probably retransmitted
     */
    void receiveSessionCreated(RemoteHostId from, OutboundEstablishState state, UDPPacketReader reader) {
        if (state == null)
            state = _outboundStates.get(from);
        if (state != null) {
            state.receiveSessionCreated(reader.getSessionCreatedReader());
            notifyActivity();
            if (_log.shouldDebug())
                _log.debug("Received SessionCreated from: " + state);
        } else {
            if (_log.shouldInfo())
                _log.info("Received possible duplicate SessionCreated from: " + from);
        }
    }

    /**
     * Got a SessionCreated (in response to our outbound SessionRequest)
     *
     * SSU 2 only.
     *
     * @param state non-null
     * @param packet header decrypted only
     * @since 0.9.54
     */
    void receiveSessionCreated(OutboundEstablishState2 state, UDPPacket packet) {
        try {
            state.receiveSessionCreated(packet);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Corrupt Session Created on: " + state, gse);
            state.fail();
            return;
        }
        notifyActivity();
        if (_log.shouldDebug())
            _log.debug("Receive session created from: " + state);
    }

    /**
     * Got a Retry (in response to our outbound SessionRequest or TokenRequest)
     *
     * SSU 2 only.
     * @since 0.9.54
     */
    void receiveRetry(OutboundEstablishState2 state, UDPPacket packet) {
        try {
            state.receiveRetry(packet);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Corrupt Retry from: " + state, gse);
            state.fail();
            return;
        }
        notifyActivity();
        if (_log.shouldDebug())
            _log.debug("Received retry with token " + state.getToken() + " from: " + state);
    }

    /**
     * Got a SessionDestroy on an established conn
     *
     * SSU 1 or 2
     *
     * @since 0.8.1
     */
    void receiveSessionDestroy(RemoteHostId from, PeerState state) {
        if (_log.shouldDebug())
            _log.debug("Received SessionDestroy on established connection from: " + from);
        _transport.dropPeer(state, false, "Received destroy message");
    }

    /**
     * Got a SessionDestroy during outbound establish
     *
     * SSU 1 or 2
     *
     * @since 0.8.1
     */
    void receiveSessionDestroy(RemoteHostId from, OutboundEstablishState state) {
        if (_log.shouldDebug())
            _log.debug("Received Outbound SessionDestroy from: " + from);
        _outboundStates.remove(from);
        Hash peer = state.getRemoteIdentity().calculateHash();
        _transport.dropPeer(peer, false, "Received destroy message during OB establish");
    }

    /**
     * Got a SessionDestroy - maybe during an inbound establish?
     * TODO - PacketHandler won't look up inbound establishes
     * As this packet was essentially unauthenticated (i.e. intro key, not session key)
     * we just log it as it could be spoofed.
     *
     * SSU 1 or 2
     *
     * @since 0.8.1
     */
    void receiveSessionDestroy(RemoteHostId from) {
        if (_log.shouldWarn())
            _log.warn("Received unauthenticated SessionDestroy from: " + from);
        //InboundEstablishState state = _inboundStates.remove(from);
        //if (state != null) {
        //    Hash peer = state.getConfirmedIdentity().calculateHash();
        //    if (peer != null)
        //        _transport.dropPeer(peer, false, "received destroy message");
        //}
    }

    /**
     * A data packet arrived on an outbound connection being established, which
     * means its complete (yay!).  This is a blocking call, more than I'd like...
     *
     * @return the new PeerState
     */
    PeerState receiveData(OutboundEstablishState state) {
        state.dataReceived();
        //int active = 0;
        //int admitted = 0;
        //int remaining = 0;

            //active = _outboundStates.size();
            _outboundStates.remove(state.getRemoteHostId());
                // there shouldn't have been queued messages for this active state, but just in case...
                List<OutNetMessage> queued = _queuedOutbound.remove(state.getRemoteHostId());
                if (queued != null) {
                    // see comments above
                    synchronized (queued) {
                        for (OutNetMessage m : queued) {
                            state.addMessage(m);
                        }
                    }
                }

        if (_outboundStates.size() < getMaxConcurrentEstablish() && !_queuedOutbound.isEmpty()) {
            locked_admitQueued();
        }
            //remaining = _queuedOutbound.size();

        //if (admitted > 0)
        //    _log.log(Log.CRIT, "Admitted " + admitted + " with " + remaining + " remaining queued and " + active + " active");

        if (_log.shouldDebug())
            _log.debug("Outbound SSU connection successfully established\n* " + state);
        PeerState peer = handleCompletelyEstablished(state);
        notifyActivity();
        return peer;
    }

    /**
     *  Move pending OB messages from _queuedOutbound to _outboundStates.
     *  This isn't so great because _queuedOutbound is not a FIFO.
     */
    private int locked_admitQueued() {
        if (_queuedOutbound.isEmpty())
            return 0;
        int admitted = 0;
        int max = getMaxConcurrentEstablish();
        for (Iterator<Map.Entry<RemoteHostId, List<OutNetMessage>>> iter = _queuedOutbound.entrySet().iterator();
             iter.hasNext() && _outboundStates.size() < max; ) {
            // ok, active shrunk, lets let some queued in.

            Map.Entry<RemoteHostId, List<OutNetMessage>> entry = iter.next();
            // java 5 IllegalStateException here
            try {
                iter.remove();
            } catch (IllegalStateException ise) {
                continue;
            }
            List<OutNetMessage> allQueued = entry.getValue();
            List<OutNetMessage> queued = new ArrayList<OutNetMessage>();
            long now = _context.clock().now();
            synchronized (allQueued) {
                for (OutNetMessage msg : allQueued) {
                    if (now - Router.CLOCK_FUDGE_FACTOR > msg.getExpiration()) {
                        _transport.failed(msg, "Took too long in est. mgr OB queue");
                    } else {
                        queued.add(msg);
                    }

                }
            }
            if (queued.isEmpty())
                continue;

            for (OutNetMessage m : queued) {
                m.timestamp("no longer deferred... establishing");
                establish(m, false);
            }
            admitted++;
        }
        return admitted;
    }

    private void notifyActivity() {
        synchronized (_activityLock) {
            _activity++;
            _activityLock.notifyAll();
        }
    }

    /**
     * ok, fully received, add it to the established cons and queue up a
     * netDb store to them
     *
     */
    private void handleCompletelyEstablished(InboundEstablishState state) {
        if (state.isComplete()) return;

        RouterIdentity remote = state.getConfirmedIdentity();
        PeerState peer;
        int version = state.getVersion();
        if (version == 1) {
            peer = new PeerState(_context, _transport,
                                 state.getSentIP(), state.getSentPort(), remote.calculateHash(), true, state.getRTT());
            peer.setCurrentCipherKey(state.getCipherKey());
            peer.setCurrentMACKey(state.getMACKey());
            peer.setWeRelayToThemAs(state.getSentRelayTag());
        } else {
            InboundEstablishState2 state2 = (InboundEstablishState2) state;
            peer = state2.getPeerState();
        }

        if (version == 1) {
            // Lookup the peer's MTU from the netdb, since it isn't included in the protocol setup (yet)
            // TODO if we don't have RI then we will get it shortly, but too late.
            // Perhaps netdb should notify transport when it gets a new RI...
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(remote.calculateHash());
            if (info != null) {
                RouterAddress addr = _transport.getTargetAddress(info);
                if (addr != null) {
                    String smtu = addr.getOption(UDPAddress.PROP_MTU);
                    if (smtu != null) {
                        try {
                            boolean isIPv6 = state.getSentIP().length == 16;
                            int mtu = MTU.rectify(isIPv6, Integer.parseInt(smtu));
                            peer.setHisMTU(mtu);
                        } catch (NumberFormatException nfe) {}
                    }
                }
            }
        }  // else IES2 sets PS2 MTU
        // 0 is the default
        //peer.setTheyRelayToUsAs(0);

        if (_log.shouldDebug())
            _log.debug("Inbound SSU handle completely established to [" + peer.getRemotePeer().toBase64().substring(0,6) + "]\n* " + state);

        //if (true) // for now, only support direct
        //    peer.setRemoteRequiresIntroduction(false);

        _transport.addRemotePeerState(peer);

        boolean isIPv6 = state.getSentIP().length == 16;
        _transport.inboundConnectionReceived(isIPv6);
        _transport.setIP(remote.calculateHash(), state.getSentIP());

        _context.statManager().addRateData("udp.inboundEstablishTime", state.getLifetime());
        sendInboundComplete(peer);
        OutNetMessage msg;
        while ((msg = state.getNextQueuedMessage()) != null) {
            if (_context.clock().now() - Router.CLOCK_FUDGE_FACTOR > msg.getExpiration()) {
                msg.timestamp("took too long but established...");
                _transport.failed(msg, "Took too long to establish, but it was established");
            } else {
                msg.timestamp("session fully established and sent");
                _transport.send(msg);
            }
        }
        state.complete();
    }

    /**
     * Don't send our info immediately, just send a small data packet, and 5-10s later,
     * if the peer isn't banlisted, *then* send them our info.  this will help kick off
     * the oldnet
     * The "oldnet" was < 0.6.1.10, it is long gone.
     * The delay really slows down the network.
     * The peer is unbanlisted and marked reachable by addRemotePeerState() which calls markReachable()
     * so the check below is fairly pointless.
     * If for some strange reason an oldnet router (NETWORK_ID == 1) does show up,
     * it's handled in UDPTransport.messageReceived()
     * (where it will get dropped, marked unreachable and banlisted at that time).
     */
    private void sendInboundComplete(PeerState peer) {
        // SimpleTimer.getInstance().addEvent(new PublishToNewInbound(peer), 10*1000);
        if (_log.shouldDebug())
            _log.debug("Completing initial handshake with: " + peer);
        DeliveryStatusMessage dsm;
        if (peer.getVersion() == 1) {
            dsm = new DeliveryStatusMessage(_context);
        dsm.setArrival(_networkID); // overloaded, sure, but future versions can check this
                                           // This causes huge values in the inNetPool.droppedDeliveryStatusDelay stat
                                           // so it needs to be caught in InNetMessagePool.
        dsm.setMessageExpiration(_context.clock().now() + DATA_MESSAGE_TIMEOUT);
        dsm.setMessageId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
        // sent below
        } else {
            // SSU 2 uses an ACK of packet 0
            dsm = null;
        }

        // just do this inline
        //_context.simpleTimer2().addEvent(new PublishToNewInbound(peer), 0);

            Hash hash = peer.getRemotePeer();
            if ((hash != null) && (!_context.banlist().isBanlisted(hash)) && (!_transport.isUnreachable(hash))) {
                // ok, we are fine with them, send them our latest info
                //if (_log.shouldInfo())
                //    _log.info("Publishing to the peer after confirm plus delay (without banlist): " + peer);
                // bundle the two messages together for efficiency
                DatabaseStoreMessage dbsm = getOurInfo();
                List<I2NPMessage> msgs = new ArrayList<I2NPMessage>(2);
                if (dsm != null)
                    msgs.add(dsm);
                msgs.add(dbsm);
                _transport.send(msgs, peer);
            } else if (dsm != null) {
                _transport.send(dsm, peer);
                // nuh uh.
                if (_log.shouldWarn())
                    _log.warn("NOT publishing to the peer after confirm plus delay (WITH banlist): " + (hash != null ? hash.toString() : "unknown"));
            }
    }

    /**
     * ok, fully received, add it to the established cons and send any
     * queued messages
     *
     * @return the new PeerState
     */
    private PeerState handleCompletelyEstablished(OutboundEstablishState state) {
        if (state.complete()) {
            RouterIdentity rem = state.getRemoteIdentity();
            if (rem != null)
                return _transport.getPeerState(rem.getHash());
        }

        long now = _context.clock().now();
        RouterIdentity remote = state.getRemoteIdentity();
        // only if == state
        RemoteHostId claimed = state.getClaimedAddress();
        if (claimed != null)
            _outboundByClaimedAddress.remove(claimed, state);
        _outboundByHash.remove(remote.calculateHash(), state);
        int version = state.getVersion();
        PeerState peer;
        if (version == 1) {
            peer = new PeerState(_context, _transport,
                                 state.getSentIP(), state.getSentPort(), remote.calculateHash(), false, state.getRTT());
        peer.setCurrentCipherKey(state.getCipherKey());
        peer.setCurrentMACKey(state.getMACKey());
            int mtu = state.getRemoteAddress().getMTU();
            if (mtu > 0)
                peer.setHisMTU(mtu);
        } else {
            OutboundEstablishState2 state2 = (OutboundEstablishState2) state;
            // OES2 sets PS2 MTU
            peer = state2.getPeerState();
        }
        peer.setTheyRelayToUsAs(state.getReceivedRelayTag());
        // 0 is the default
        //peer.setWeRelayToThemAs(0);

        if (_log.shouldDebug())
            _log.debug("Outbound SSU handle completely established to [" + peer.getRemotePeer().toBase64().substring(0,6) + "]\n* " + state);

        _transport.addRemotePeerState(peer);
        _transport.setIP(remote.calculateHash(), state.getSentIP());

        _context.statManager().addRateData("udp.outboundEstablishTime", state.getLifetime());
        DatabaseStoreMessage dbsm = null;
        if (version == 1) {
            // version 2 sends our RI in handshake
            if (!state.isFirstMessageOurDSM()) {
                dbsm = getOurInfo();
            }
        }

        List<OutNetMessage> msgs = new ArrayList<OutNetMessage>(8);
        OutNetMessage msg;
        while ((msg = state.getNextQueuedMessage()) != null) {
            if (now - Router.CLOCK_FUDGE_FACTOR > msg.getExpiration()) {
                msg.timestamp("took too long but established...");
                _transport.failed(msg, "Took too long to establish, but it was established");
            } else {
                msg.timestamp("session fully established and sent");
                msgs.add(msg);
            }
        }
        _transport.send(dbsm, msgs, peer);
        return peer;
    }

/****
    private void sendOurInfo(PeerState peer, boolean isInbound) {
        if (_log.shouldInfo())
            _log.info("Publishing to the peer after confirm: " +
                      (isInbound ? " inbound con from " + peer : "outbound con to " + peer));
        DatabaseStoreMessage m = getOurInfo();
        _transport.send(m, peer);
    }
****/

    /**
     *  A database store message with our router info
     *  @return non-null
     *  @since 0.9.24 split from sendOurInfo()
     */
    private DatabaseStoreMessage getOurInfo() {
        DatabaseStoreMessage m = new DatabaseStoreMessage(_context);
        m.setEntry(_context.router().getRouterInfo());
        m.setMessageExpiration(_context.clock().now() + DATA_MESSAGE_TIMEOUT);
        return m;
    }

    /** the relay tag is a 4-byte field in the protocol */
    public static final long MAX_TAG_VALUE = 0xFFFFFFFFl;

    /**
     *  This handles both initial send and retransmission of Session Created,
     *  and, for SSU2, send of Retry.
     *  Retry is never retransmnitted.
     *
     *  This may be called more than once.
     *
     *  Caller must synch on state.
     */
    private void sendCreated(InboundEstablishState state) {
        int version = state.getVersion();
        UDPPacket pkt;
        if (version == 1) {
            if (_log.shouldDebug())
            _log.debug("Sending SessionCreated to: " + state);
        try {
            state.generateSessionKey();
        } catch (DHSessionKeyBuilder.InvalidPublicParameterException ippe) {
            if (_log.shouldWarn())
                _log.warn("Peer " + state + " sent us an invalid DH parameter", ippe);
            _inboundStates.remove(state.getRemoteHostId());
            state.fail();
            return;
        }
            pkt = _builder.buildSessionCreatedPacket(state,
                                                           _transport.getExternalPort(state.getSentIP().length == 16),
                                                           _transport.getIntroKey());
        } else {
            InboundEstablishState2 state2 = (InboundEstablishState2) state;
            InboundEstablishState.InboundState istate = state2.getState();
            if (istate == IB_STATE_CREATED_SENT) {
                if (_log.shouldInfo())
                    _log.info("RetransmitSessionCreated packet sent to: " + state);
                // if already sent, get from the state to retx
                pkt = state2.getRetransmitSessionCreatedPacket();
            } else if (istate == IB_STATE_REQUEST_RECEIVED) {
                if (_log.shouldDebug())
                    _log.debug("Send created to: " + state);
                pkt = _builder2.buildSessionCreatedPacket(state2);
            } else if (istate == IB_STATE_TOKEN_REQUEST_RECEIVED ||
                       istate == IB_STATE_REQUEST_BAD_TOKEN_RECEIVED) {
                if (_log.shouldDebug())
                    _log.debug("Send retry to: " + state);
                pkt = _builder2.buildRetryPacket(state2);
            } else {
                if (_log.shouldWarn())
                    _log.warn("Unhandled state " + istate + " on " + state);
                return;
            }
        }
        if (pkt == null) {
            if (_log.shouldWarn())
                _log.warn("Peer " + state + " sent us an invalid IP?");
            _inboundStates.remove(state.getRemoteHostId());
            state.fail();
            return;
        }
        _transport.send(pkt);
        if (version == 1)
            state.createdPacketSent();
        // else PacketBuilder2 told the state
    }

    /**
     *  This handles both initial send and retransmission of Session Request,
     *  and, for SSU2, initial send and retransmission of Token Request.
     *
     *  This may be called more than once.
     *
     *  Caller must synch on state.
     */
    private void sendRequest(OutboundEstablishState state) {
        int version = state.getVersion();
        UDPPacket packet;
        if (version == 1) {
            if (_log.shouldDebug())
            _log.debug("Sent SessionRequest " + state);
            packet = _builder.buildSessionRequestPacket(state);
        } else {
            OutboundEstablishState2 state2 = (OutboundEstablishState2) state;
            OutboundEstablishState.OutboundState ostate = state2.getState();
            if (ostate == OB_STATE_REQUEST_SENT ||
                ostate == OB_STATE_REQUEST_SENT_NEW_TOKEN) {
                if (_log.shouldInfo())
                    _log.info("Resending SessionRequest packet to: " + state);
                // if already sent, get from the state to retx
                packet = state2.getRetransmitSessionRequestPacket();
            } else if (ostate == OB_STATE_NEEDS_TOKEN ||
                       ostate == OB_STATE_TOKEN_REQUEST_SENT) {
                if (_log.shouldDebug())
                    _log.debug("Send Token Request to: " + state);
                packet = _builder2.buildTokenRequestPacket(state2);
            } else if (ostate == OB_STATE_UNKNOWN ||
                       ostate == OB_STATE_RETRY_RECEIVED) {
                if (_log.shouldDebug())
                    _log.debug("Send Session Request to: " + state);
                packet = _builder2.buildSessionRequestPacket(state2);
            } else {
                if (_log.shouldWarn())
                    _log.warn("Unhandled state " + ostate + " on " + state);
                return;
            }
        }
        if (packet != null) {
            _transport.send(packet);
        } else {
            if (_log.shouldWarn())
                _log.warn("Unable to build a SessionRequest packet for: " + state);
        }
        if (version == 1)
            state.requestSent();
        // else PacketBuilder2 told the state
    }

    /**
     *  Send RelayRequests to multiple introducers.
     *  This may be called multiple times, it sets the nonce the first time only
     *  Caller should probably synch on state.
     */
    private void handlePendingIntro(OutboundEstablishState state) {
        long nonce = state.getIntroNonce();
        if (nonce < 0) {
            OutboundEstablishState old;
            do {
                nonce = _context.random().nextLong(MAX_NONCE);
                old = _liveIntroductions.putIfAbsent(Long.valueOf(nonce), state);
            } while (old != null);
            state.setIntroNonce(nonce);
        }
        _context.statManager().addRateData("udp.sendIntroRelayRequest", 1);
        List<UDPPacket> requests = _builder.buildRelayRequest(_transport, this, state, _transport.getIntroKey());
        if (requests.isEmpty()) {
            if (_log.shouldWarn())
                _log.warn("No valid introducers for: " + state);
            processExpired(state);
            return;
        }
        for (UDPPacket req : requests) {
            _transport.send(req);
        }
        if (_log.shouldDebug())
            _log.debug("Sending RelayRequest for: " + state + "\n* Introduction key: " + _transport.getIntroKey());
        state.introSent();
    }

    /**
     *  We are Alice, we sent a RelayRequest to Bob and got a response back.
     */
    void receiveRelayResponse(RemoteHostId bob, UDPPacketReader reader) {
        long nonce = reader.getRelayResponseReader().readNonce();
        OutboundEstablishState state = _liveIntroductions.remove(Long.valueOf(nonce));
        if (state == null) {
            if (_log.shouldDebug())
                _log.debug("Duplicate or unknown RelayResponse: [Nonce " + nonce + "]");
            return; // already established
        }

        // Note that we ignore the Alice (us) IP/Port in the RelayResponse
        int sz = reader.getRelayResponseReader().readCharlieIPSize();
        byte ip[] = new byte[sz];
        reader.getRelayResponseReader().readCharlieIP(ip, 0);
        int port = reader.getRelayResponseReader().readCharliePort();
        if ((!isValid(ip, port)) || (!isValid(bob.getIP(), bob.getPort()))) {
            if (_log.shouldWarn())
                _log.warn("Bad RelayResponse from " + bob + " for " + Addresses.toString(ip, port));
            _context.statManager().addRateData("udp.relayBadIP", 1);
            return;
        }
        InetAddress addr = null;
        try {
            addr = InetAddress.getByAddress(ip);
        } catch (UnknownHostException uhe) {
            if (_log.shouldWarn())
                _log.warn("Introducer for " + state + " (" + bob + ") sent us an invalid address for our target: " + Addresses.toString(ip, port), uhe);
            // TODO either put the nonce back in liveintroductions, or fail
            return;
        }
        _context.statManager().addRateData("udp.receiveIntroRelayResponse", state.getLifetime());
        if (_log.shouldDebug())
            _log.debug("Received RelayResponse for [" + state.getRemoteIdentity().calculateHash().toBase64().substring(0,6) + "]\n* Address: "
                      + addr.toString().replace("/", "") + ":" + port + " (according to " + bob + ") [Nonce " + nonce + "]");
        synchronized (state) {
            RemoteHostId oldId = state.getRemoteHostId();
            state.introduced(ip, port);
            RemoteHostId newId = state.getRemoteHostId();
            // Swap out the RemoteHostId the state is indexed under.
            // It was a Hash, change it to a IP/port.
            // Remove the entry in the byClaimedAddress map as it's now in main map.
            // Add an entry in the byHash map so additional OB pkts can find it.
            _outboundByHash.put(state.getRemoteIdentity().calculateHash(), state);
            RemoteHostId claimed = state.getClaimedAddress();
            if (!oldId.equals(newId)) {
                _outboundStates.remove(oldId);
                _outboundStates.put(newId, state);
                if (_log.shouldInfo())
                    _log.info("RelayResponse replaced " + oldId + " with " + newId + ", claimed address was " + claimed);
            }
            //
            if (claimed != null)
                _outboundByClaimedAddress.remove(oldId, state);  // only if == state
        }
        notifyActivity();
    }

    /**
     *  Called from UDPReceiver.
     *  Accelerate response to RelayResponse if we haven't sent it yet.
     *
     *  @since 0.9.15
     */
    void receiveHolePunch(InetAddress from, int fromPort) {
        RemoteHostId id = new RemoteHostId(from.getAddress(), fromPort);
        OutboundEstablishState state = _outboundStates.get(id);
        if (state != null) {
            boolean sendNow = state.receiveHolePunch();
            if (sendNow) {
                if (_log.shouldInfo())
                    _log.info("Received HolePunch " + state + ", sending SessionRequest now");
                notifyActivity();
            } else {
                if (_log.shouldInfo())
                    _log.info("Received HolePunch " + state + ", already sent SessionRequest");
            }
        } else {
            // HolePunch received before RelayResponse, and we didn't know the IP/port, or it changed
            if (_log.shouldInfo())
                _log.info("No state found for HolePunch from " + from + ":" + fromPort);
        }
    }

    /**
     *  Are IP and port valid? This is only for checking the relay response.
     *  Allow IPv6 as of 0.9.50.
     *  Refuse anybody in the same /16
     *  @since 0.9.3, pkg private since 0.9.45 for PacketBuider
     */
    boolean isValid(byte[] ip, int port) {
        return TransportUtil.isValidPort(port) &&
               ip != null &&
               _transport.isValid(ip) &&
               (!_transport.isTooClose(ip)) &&
               (!_context.blocklist().isBlocklisted(ip));
    }

    /**
     *  Note that while a SessionConfirmed could in theory be fragmented,
     *  in practice a RouterIdentity is 387 bytes and a single fragment is 512 bytes max,
     *  so it will never be fragmented.
     *
     *  Caller must synch on state.
     */
    private void sendConfirmation(OutboundEstablishState state) {
        boolean valid = state.validateSessionCreated();
        if (!valid) {
            // validate clears fields on failure
            // sendDestroy(state) won't work as we haven't sent the confirmed...
            if (_log.shouldWarn())
                _log.warn("SessionCreated failed validation " + state);
            return;
        }

        if (!_transport.isValid(state.getReceivedIP()) || !_transport.isValid(state.getRemoteHostId().getIP())) {
            state.fail();
            return;
        }

        // gives us the opportunity to "detect" our external addr
        _transport.externalAddressReceived(state.getRemoteIdentity().calculateHash(), state.getReceivedIP(), state.getReceivedPort());

        int version = state.getVersion();
        UDPPacket packets[];
        if (version == 1) {
        // signs if we havent signed yet
        state.prepareSessionConfirmed();
            packets = _builder.buildSessionConfirmedPackets(state, _context.router().getRouterInfo().getIdentity());
        } else {
            OutboundEstablishState2 state2 = (OutboundEstablishState2) state;
            OutboundEstablishState.OutboundState ostate = state2.getState();
            // shouldn't happen, we go straight to confirmed after sending
            if (ostate == OB_STATE_CONFIRMED_COMPLETELY)
                return;
            packets = _builder2.buildSessionConfirmedPackets(state2, _context.router().getRouterInfo());
        }
        if (packets == null) {
            state.fail();
            return;
        }

        if (_log.shouldDebug())
            _log.debug("Sending SessionConfirmed to: " + state);

        for (int i = 0; i < packets.length; i++) {
            _transport.send(packets[i]);
        }

        if (version == 1) {
        state.confirmedPacketsSent();
        } else {
            // save for retx
            OutboundEstablishState2 state2 = (OutboundEstablishState2) state;
            // PacketBuilder2 told the state
            //state2.confirmedPacketsSent(packets);
            // we are done, go right to ps2
            handleCompletelyEstablished(state2);
        }
    }

    /**
     *  Tell the other side never mind.
     *  This is only useful after we have received SessionCreated,
     *  and sent SessionConfirmed, but not yet gotten a data packet as an
     *  ack to the SessionConfirmed - otherwise we haven't generated the keys.
     *  Caller should probably synch on state.
     *
     *  SSU1 only.
     *
     *  @since 0.9.2
     */
    private void sendDestroy(OutboundEstablishState state) {
        if (state.getVersion() > 1)
            return;
        UDPPacket packet = _builder.buildSessionDestroyPacket(state);
        if (packet != null) {
            if (_log.shouldDebug())
                _log.debug("Sending SessionDestroy to: " + state);
            _transport.send(packet);
        }
    }

    /**
     *  Tell the other side never mind.
     *  This is only useful after we have sent SessionCreated,
     *  but not received SessionConfirmed
     *  Otherwise we haven't generated the keys.
     *  Caller should probably synch on state.
     *
     *  SSU1 only.
     *
     *  @since 0.9.2
     */
    private void sendDestroy(InboundEstablishState state) {
        if (state.getVersion() > 1)
            return;
        // TODO ban the IP for a while, like we do in NTCP?
        UDPPacket packet = _builder.buildSessionDestroyPacket(state);
        if (packet != null) {
            if (_log.shouldDebug())
                _log.debug("Sent SessionDestroy to: " + state);
            _transport.send(packet);
        }
    }

    /**
     * Drive through the inbound establishment states, adjusting one of them
     * as necessary. Called from Establisher thread only.
     * @return next requested time or Long.MAX_VALUE
     */
    private long handleInbound() {
        long now = _context.clock().now();
        long nextSendTime = Long.MAX_VALUE;
        InboundEstablishState inboundState = null;
        boolean expired = false;

            for (Iterator<InboundEstablishState> iter = _inboundStates.values().iterator(); iter.hasNext(); ) {
                InboundEstablishState cur = iter.next();
                InboundEstablishState.InboundState istate = cur.getState();
                if (istate == IB_STATE_CONFIRMED_COMPLETELY) {
                    // completely received (though the signature may be invalid)
                    iter.remove();
                    inboundState = cur;
                    //if (_log.shouldDebug())
                    //    _log.debug("Removing completely confirmed inbound state");
                    break;
                } else if (cur.getLifetime() > MAX_IB_ESTABLISH_TIME) {
                    // took too long
                    iter.remove();
                    inboundState = cur;
                    //_context.statManager().addRateData("udp.inboundEstablishFailedState", cur.getState(), cur.getLifetime());
                    //if (_log.shouldDebug())
                    //    _log.debug("Removing expired inbound state " + cur);
                    expired = true;
                    break;
                } else if (istate == IB_STATE_FAILED || istate == IB_STATE_COMPLETE) {
                    iter.remove();
                } else {
                    // this will always be > 0
                    long next = cur.getNextSendTime();
                    if (next <= now) {
                        // our turn...
                        inboundState = cur;
                        // if (_log.shouldDebug())
                        //     _log.debug("Processing inbound that wanted activity");
                        break;
                    } else {
                        // nothin to do but wait for them to send us
                        // stuff, so lets move on to the next one being
                        // established
                        if (next < nextSendTime)
                            nextSendTime = next;
                    }
                }
            }

        if (inboundState != null) {
            //if (_log.shouldDebug())
            //    _log.debug("Processing for inbound: " + inboundState);
            synchronized (inboundState) {
                InboundEstablishState.InboundState istate = inboundState.getState();
                switch (istate) {
                  case IB_STATE_REQUEST_RECEIVED:
                  case IB_STATE_TOKEN_REQUEST_RECEIVED:      // SSU2
                  case IB_STATE_REQUEST_BAD_TOKEN_RECEIVED:  // SSU2
                    if (expired)
                        processExpired(inboundState);
                    else
                        sendCreated(inboundState);
                    break;

                  // SSU2 only in practice, should only get here if expired
                  case IB_STATE_CONFIRMED_PARTIALLY:
                    if (expired)
                        processExpired(inboundState);
                    break;

                  case IB_STATE_CREATED_SENT: // fallthrough
                  case IB_STATE_RETRY_SENT:                  // SSU2
                    if (expired) {
                        sendDestroy(inboundState);
                        processExpired(inboundState);
                    } else if (inboundState.getNextSendTime() <= now) {
                        if (istate == IB_STATE_RETRY_SENT) {
                            // Retry is never retransmitted
                            inboundState.fail();
                            processExpired(inboundState);
                        } else {
                        sendCreated(inboundState);
                        }
                    }
                    break;

                  case IB_STATE_CONFIRMED_COMPLETELY:
                    RouterIdentity remote = inboundState.getConfirmedIdentity();
                    if (remote != null) {
                        if (_context.banlist().isBanlistedForever(remote.calculateHash())) {
                            if (_log.shouldWarn())
                                _log.warn("Dropping Inbound connection from permanently banlisted peer: " + remote.calculateHash());
                            // So next time we will not accept the con, rather than doing the whole handshake
                            _context.blocklist().add(inboundState.getSentIP());
                            inboundState.fail();
                            processExpired(inboundState);
                        } else {
                            handleCompletelyEstablished(inboundState);
                        }
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("Confirmed with invalid? " + inboundState);
                        inboundState.fail();
                        processExpired(inboundState);
                    }
                    break;

                  case IB_STATE_COMPLETE:  // fall through
                  case IB_STATE_FAILED: // leak here if fail() was called in IES???
                    break; // already removed;

                  case IB_STATE_UNKNOWN:
                    // Can't happen, always call receiveSessionRequest() before putting in map
                    if (_log.shouldError())
                        _log.error("hrm, state is unknown for " + inboundState);
                    break;

                  default:
                    if (_log.shouldWarn())
                        _log.warn("Unhandled state on " + inboundState);
                    break;
                }
            }

            // ok, since there was something to do, we want to loop again
            nextSendTime = now;
        }

        return nextSendTime;
    }


    /**
     * Drive through the outbound establishment states, adjusting one of them
     * as necessary. Called from Establisher thread only.
     * @return next requested time or Long.MAX_VALUE
     */
    private long handleOutbound() {
        long now = _context.clock().now();
        long nextSendTime = Long.MAX_VALUE;
        OutboundEstablishState outboundState = null;
        //int admitted = 0;
        //int remaining = 0;
        //int active = 0;

            for (Iterator<OutboundEstablishState> iter = _outboundStates.values().iterator(); iter.hasNext(); ) {
                OutboundEstablishState cur = iter.next();
                OutboundEstablishState.OutboundState state = cur.getState();
                if (state == OB_STATE_CONFIRMED_COMPLETELY ||
                    state == OB_STATE_VALIDATION_FAILED) {
                    iter.remove();
                    outboundState = cur;
                    break;
                } else if (cur.getLifetime() >= MAX_OB_ESTABLISH_TIME) {
                    // took too long
                    iter.remove();
                    outboundState = cur;
                    //_context.statManager().addRateData("udp.outboundEstablishFailedState", cur.getState(), cur.getLifetime());
                    //if (_log.shouldDebug())
                    //    _log.debug("Removing expired outbound: " + cur);
                    break;
                } else {
                    // this will be 0 for a new OES that needs sending, > 0 for others
                    long next = cur.getNextSendTime();
                    if (next <= now) {
                        // our turn...
                        outboundState = cur;
                        // if (_log.shouldDebug())
                        //     _log.debug("Outbound wants activity: " + cur);
                        break;
                    } else {
                        // nothin to do but wait for them to send us
                        // stuff, so lets move on to the next one being
                        // established
                        if (next < nextSendTime)
                            nextSendTime = next;
                        // if (_log.shouldDebug())
                        //     _log.debug("Outbound doesn't want activity: " + cur + " (next=" + (when-now) + ")");
                    }
                }
            }

            //admitted = locked_admitQueued();
            //remaining = _queuedOutbound.size();

        //if (admitted > 0)
        //    _log.log(Log.CRIT, "Admitted " + admitted + " in push with " + remaining + " remaining queued and " + active + " active");

        if (outboundState != null) {
            //if (_log.shouldDebug())
            //    _log.debug("Processing for outbound: " + outboundState);
            synchronized (outboundState) {
                boolean expired = outboundState.getLifetime() >= MAX_OB_ESTABLISH_TIME;
                switch (outboundState.getState()) {
                    case OB_STATE_UNKNOWN:  // fall thru
                    case OB_STATE_INTRODUCED:
                    case OB_STATE_NEEDS_TOKEN:             // SSU2 only
                        if (expired)
                            processExpired(outboundState);
                        else
                            sendRequest(outboundState);
                        break;

                    case OB_STATE_REQUEST_SENT:
                    case OB_STATE_TOKEN_REQUEST_SENT:      // SSU2 only
                    case OB_STATE_RETRY_RECEIVED:          // SSU2 only
                    case OB_STATE_REQUEST_SENT_NEW_TOKEN:  // SSU2 only
                        // no response yet (or it was invalid), lets retry
                        long rtime = outboundState.getRequestSentTime();
                        if (expired || (rtime > 0 && rtime + OB_MESSAGE_TIMEOUT <= now))
                            processExpired(outboundState);
                        else if (outboundState.getNextSendTime() <= now)
                            sendRequest(outboundState);
                        break;

                    case OB_STATE_CREATED_RECEIVED:
                        if (expired)
                            processExpired(outboundState);
                        else if (outboundState.getNextSendTime() <= now)
                            sendConfirmation(outboundState);
                        break;

                    case OB_STATE_CONFIRMED_PARTIALLY:
                        long ctime = outboundState.getConfirmedSentTime();
                        if (expired || (ctime > 0 && ctime + OB_MESSAGE_TIMEOUT <= now)) {
                            sendDestroy(outboundState);
                            processExpired(outboundState);
                        } else if (outboundState.getNextSendTime() <= now) {
                            sendConfirmation(outboundState);
                        }
                        break;

                    case OB_STATE_CONFIRMED_COMPLETELY:
                        if (expired)
                            processExpired(outboundState);
                        else
                            handleCompletelyEstablished(outboundState);
                        break;

                    case OB_STATE_PENDING_INTRO:
                        long itime = outboundState.getIntroSentTime();
                        if (expired || (itime > 0 && itime + OB_MESSAGE_TIMEOUT <= now))
                            processExpired(outboundState);
                        else if (outboundState.getNextSendTime() <= now)
                            handlePendingIntro(outboundState);
                        break;

                    case OB_STATE_VALIDATION_FAILED:
                        processExpired(outboundState);
                        break;

                    default:
                        if (_log.shouldWarn())
                            _log.warn("Unhandled state on " + outboundState);
                        break;
                }
            }

            // ok, since there was something to do, we want to loop again
            nextSendTime = now;
        }

        return nextSendTime;
    }

    /**
     *  Caller should probably synch on outboundState
     */
    private void processExpired(OutboundEstablishState outboundState) {
        long nonce = outboundState.getIntroNonce();
        if (nonce >= 0) {
            // remove only if value == state
            boolean removed = _liveIntroductions.remove(Long.valueOf(nonce), outboundState);
            if (removed) {
                if (_log.shouldDebug())
                    _log.debug("Relay request for " + outboundState + " timed out");
                _context.statManager().addRateData("udp.sendIntroRelayTimeout", 1);
            }
        }
        // only if == state
        RemoteHostId claimed = outboundState.getClaimedAddress();
        if (claimed != null)
            _outboundByClaimedAddress.remove(claimed, outboundState);
        _outboundByHash.remove(outboundState.getRemoteIdentity().calculateHash(), outboundState);
        // should have already been removed in handleOutbound() above
        // remove only if value == state
        _outboundStates.remove(outboundState.getRemoteHostId(), outboundState);
        if (outboundState.getState() != OB_STATE_CONFIRMED_COMPLETELY) {
            if (_log.shouldDebug())
                _log.debug("Session with " + outboundState + " has expired; Lifetime: " + outboundState.getLifetime() + "ms ");
            OutNetMessage msg;
            while ((msg = outboundState.getNextQueuedMessage()) != null) {
                _transport.failed(msg, "Expired during failed establishment attempt");
            }
            String err = "Took too long to establish Outbound connection, state is " + outboundState.getState();
            Hash peer = outboundState.getRemoteIdentity().calculateHash();
            //_context.banlist().banlistRouter(peer, err, UDPTransport.STYLE);
            _transport.markUnreachable(peer);
            _transport.dropPeer(peer, false, err);
            //_context.profileManager().commErrorOccurred(peer);
            outboundState.fail();
        } else {
            OutNetMessage msg;
            while ((msg = outboundState.getNextQueuedMessage()) != null) {
                _transport.send(msg);
            }
        }
    }


    /**
     *  Caller should probably synch on inboundState
     *  @since 0.9.2
     */
    private void processExpired(InboundEstablishState inboundState) {
        _inboundStates.remove(inboundState.getRemoteHostId());
        OutNetMessage msg;
        while ((msg = inboundState.getNextQueuedMessage()) != null) {
            _transport.failed(msg, "Expired during failed establish");
        }
    }

    //// SSU 2 ////

    /**
     *  Remember a token that can be used later to connect to the peer
     *
     *  @param token nonzero
     *  @since 0.9.54
     */
    public void addOutboundToken(RemoteHostId peer, long token, long expires) {
        if (expires < _context.clock().now())
            return;
        Token tok = new Token(token, expires);
        synchronized(_outboundTokens) {
            _outboundTokens.put(peer, tok);
        }
    }

    /**
     *  Get a token to connect to the peer
     *
     *  @return 0 if none available
     *  @since 0.9.54
     */
    public long getOutboundToken(RemoteHostId peer) {
        Token tok;
        synchronized(_outboundTokens) {
            tok = _outboundTokens.remove(peer);
        }
        if (tok == null)
            return 0;
        if (tok.expires < _context.clock().now())
            return 0;
        return tok.token;
    }

    /**
     *  Remove our tokens for this length
     *
     *  @since 0.9.54
     */
    public void ipChanged(boolean isIPv6) {
        if (!_enableSSU2)
            return;
        int len = isIPv6 ? 16 : 4;
        // expire while we're at it
        long now = _context.clock().now();
        synchronized(_outboundTokens) {
            for (Iterator<Map.Entry<RemoteHostId, Token>> iter = _outboundTokens.entrySet().iterator(); iter.hasNext(); ) {
                Map.Entry<RemoteHostId, Token> e = iter.next();
                if (e.getKey().getIP().length == len || e.getValue().expires < now)
                    iter.remove();
            }
        }
        synchronized(_inboundTokens) {
            for (Iterator<Map.Entry<RemoteHostId, Token>> iter = _inboundTokens.entrySet().iterator(); iter.hasNext(); ) {
                Map.Entry<RemoteHostId, Token> e = iter.next();
                if (e.getKey().getIP().length == len || e.getValue().expires < now)
                    iter.remove();
            }
        }
    }

    /**
     *  Remove all tokens
     *
     *  @since 0.9.54
     */
    public void portChanged() {
        if (!_enableSSU2)
            return;
        synchronized(_outboundTokens) {
            _outboundTokens.clear();
        }
        synchronized(_inboundTokens) {
            _inboundTokens.clear();
        }
    }

    /**
     *  Get a token that can be used later for the peer to connect to us
     *
     *  @since 0.9.54
     */
    public Token getInboundToken(RemoteHostId peer) {
        long token;
        do {
            token = _context.random().nextLong();
        } while (token == 0);
        long expires = _context.clock().now() + IB_TOKEN_EXPIRATION;
        Token tok = new Token(token, expires);
        synchronized(_inboundTokens) {
            _inboundTokens.put(peer, tok);
        }
        return tok;
    }

    /**
     *  Is the token from this peer valid?
     *
     *  @return valid
     *  @since 0.9.54
     */
    public boolean isInboundTokenValid(RemoteHostId peer, long token) {
        if (token == 0)
            return false;
        Token tok;
        synchronized(_inboundTokens) {
            tok = _inboundTokens.get(peer);
            if (tok == null)
                return false;
            if (tok.token != token)
                return false;
            _inboundTokens.remove(peer);
        }
        return tok.expires >= _context.clock().now();
    }

    public static class Token {
        public final long token, expires;
        public Token(long tok, long exp) {
            token = tok; expires = exp;
        }
    }

    //// End SSU 2 ////


    /**
     * Driving thread, processing up to one step for an inbound peer and up to
     * one step for an outbound peer.  This is prodded whenever any peer's state
     * changes as well.
     *
     */
    private class Establisher implements Runnable {
        public void run() {
            while (_alive) {
                try {
                    doPass();
                } catch (RuntimeException re) {
                    _log.error("Error in the establisher", re);
                    // don't loop too fast
//                    try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                    try { Thread.sleep(300); } catch (InterruptedException ie) {}
                }
            }
            _inboundStates.clear();
            _outboundStates.clear();
            _queuedOutbound.clear();
            _outboundByClaimedAddress.clear();
            _outboundByHash.clear();
        }

        private long _lastFailsafe;
        private static final long FAILSAFE_INTERVAL = 3*60*1000;
        // Debugging
        private long _lastPrinted;
        private static final long PRINT_INTERVAL = 5*1000;

        private void doPass() {
            long now = _context.clock().now();
            if (_log.shouldDebug() && _lastPrinted + PRINT_INTERVAL < now) {
                _lastPrinted = now;
                int iactive = _inboundStates.size();
                int oactive = _outboundStates.size();
                if (iactive > 0 || oactive > 0) {
                    int queued = _queuedOutbound.size();
                    int live = _liveIntroductions.size();
                    int claimed = _outboundByClaimedAddress.size();
                    int hash = _outboundByHash.size();
                    _log.debug("OB states: " + oactive + "; IB states: " + iactive +
                               "; OB queued: " + queued + "; Intros: " + live +
                               "; OB claimed: " + claimed + "; Hash: " + hash);
                }
            }
            _activity = 0;
            if (_lastFailsafe + FAILSAFE_INTERVAL < now) {
                _lastFailsafe = now;
                doFailsafe();
            }

            long nextSendTime = Math.min(handleInbound(), handleOutbound());
            long delay = nextSendTime - now;
            if (delay > 0) {
                if (delay > 1000)
                    delay = 1000;
                try {
                    synchronized (_activityLock) {
                        if (_activity > 0)
                            return;
                        _activityLock.wait(delay);
                    }
                } catch (InterruptedException ie) {
                }
                // if (_log.shouldDebug())
                //     _log.debug("After waiting w/ nextSend=" + nextSendTime
                //                + " and delay=" + delay + " and interrupted=" + interrupted);
            }
        }

        /** @since 0.9.2 */
        private void doFailsafe() {
            for (Iterator<OutboundEstablishState> iter = _liveIntroductions.values().iterator(); iter.hasNext(); ) {
                OutboundEstablishState state = iter.next();
                if (state.getLifetime() > 3*MAX_OB_ESTABLISH_TIME) {
                    iter.remove();
                    if (_log.shouldWarn())
                        _log.warn("Failsafe removal of LiveIntroduction: " + state);
                }
            }
            for (Iterator<OutboundEstablishState> iter = _outboundByClaimedAddress.values().iterator(); iter.hasNext(); ) {
                OutboundEstablishState state = iter.next();
                if (state.getLifetime() > 3*MAX_OB_ESTABLISH_TIME) {
                    iter.remove();
                    if (_log.shouldWarn())
                        _log.warn("Failsafe removal of OutboundByClaimedAddress: " + state);
                }
            }
            for (Iterator<OutboundEstablishState> iter = _outboundByHash.values().iterator(); iter.hasNext(); ) {
                OutboundEstablishState state = iter.next();
                if (state.getLifetime() > 3*MAX_OB_ESTABLISH_TIME) {
                    iter.remove();
                    if (_log.shouldWarn())
                        _log.warn("Failsafe removal of OutboundByHash: " + state);
                }
            }
        }
    }
}
