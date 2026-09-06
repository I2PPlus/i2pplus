package net.i2p.router.transport.udp;

import static net.i2p.router.transport.udp.SSU2Util.*;

import com.southernstorm.noise.protocol.ChaChaPolyCipherState;
import com.southernstorm.noise.protocol.HandshakeState;
import com.southernstorm.noise.protocol.NoiseInit;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.i2p.crypto.HKDF;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyFactory;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.BanLogger;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.TransportImpl;
import net.i2p.util.Addresses;
import net.i2p.util.VersionComparator;

/**
 * Data for a new connection being established, where the remote peer has
 * initiated the connection with us.  In other words, they are Alice and
 * we are Bob.
 *
 * SSU2 only.
 *
 * @since 0.9.54
 */
class InboundEstablishState2 extends InboundEstablishState implements SSU2Payload.PayloadCallback {
    private final BanLogger _banLogger;
    private final UDPTransport _transport;
    private final InetSocketAddress _aliceSocketAddress;
    private final long _rcvConnID;
    private final long _sendConnID;
    private final long _token;
    private final HandshakeState _handshakeState;
    private final int _version;
    private byte[] _sendHeaderEncryptKey1;
    private byte[] _sendHeaderEncryptKey2;
    private byte[] _rcvHeaderEncryptKey2;
    private byte[] _sessCrForReTX;
    private byte[][] _sessConfFragments;
    private long _timeReceived;
    private long _skew; // not adjusted for RTT
    private int _mtu;
    private PeerState2 _pstate;
    private List<UDPPacket> _queuedDataPackets;
    private static final boolean ENFORCE_TOKEN = true; // testing
    private static final long MAX_SKEW = 2*60*1000L;
    private static final String MIN_RELAY_VERSION = "0.9.57"; // SSU2 fixes (2.1.0)

    /**
     *  Start a new handshake with the given incoming packet,
     *  which must be a Session Request or Token Request.
     *
     *  Caller must then check getState() and build a
     *  Retry or Session Created in response.
     *
     *  @param packet with all header encryption removed,
     *                either a SessionRequest OR a TokenRequest.
     */
    public InboundEstablishState2(RouterContext ctx, UDPTransport transport,
                                  UDPPacket packet) throws GeneralSecurityException {
        super(ctx, (InetSocketAddress) packet.getPacket().getSocketAddress());
        _transport = transport;
        _banLogger = new BanLogger();
        _banLogger.initialize(ctx);
        DatagramPacket pkt = packet.getPacket();
        _aliceSocketAddress = (InetSocketAddress) pkt.getSocketAddress();
        byte[] introKey = transport.getSSU2StaticIntroKey();
        _sendHeaderEncryptKey1 = introKey;
        //_sendHeaderEncryptKey2 set below
        //_rcvHeaderEncryptKey2 set below
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte[] data = pkt.getData();
        _rcvConnID = DataHelper.fromLong8(data, off);
        _sendConnID = DataHelper.fromLong8(data, off + SRC_CONN_ID_OFFSET);
        if (_rcvConnID == _sendConnID) {throw new GeneralSecurityException("Identical Connection IDs");}
        int type = data[off + TYPE_OFFSET] & 0xff;
        long token = DataHelper.fromLong8(data, off + TOKEN_OFFSET);
        _version = data[off + VERSION_OFFSET] & 0xff;
        NoiseInit.PatternID pattern;
        KeyFactory hkf;
        int overhead = LONG_HEADER_SIZE + KEY_LEN + MAC_LEN;
        boolean isIPv6 = _aliceIP.length == 16;
        switch (_version) {
            case 2:
                pattern = NoiseInit.PatternID.XK_SSU2;
                _handshakeState = new HandshakeState(pattern, HandshakeState.RESPONDER, transport.getXDHFactory());
                break;
            case 3:
                if (type == SESSION_REQUEST_FLAG_BYTE) {
                    int ipOverhead = (isIPv6 ? PacketBuilder2.IPV6_HEADER_SIZE : PacketBuilder2.IP_HEADER_SIZE) + PacketBuilder2.UDP_HEADER_SIZE;
                    _mtu = Math.max(PeerState2.MIN_MTU, len + ipOverhead);
                }
                pattern = NoiseInit.PatternID.XKHFS_512_SSU2;
                overhead += MAC_LEN + EncType.MLKEM512_X25519_INT.getPubkeyLen();
                hkf = _context.eciesEngine().getHybridKeyFactory(EncType.MLKEM512_X25519);
                _handshakeState = new HandshakeState(pattern, HandshakeState.RESPONDER, transport.getXDHFactory(), hkf);
                break;
            case 4:
                int ourmtu = transport.getSSU2MTU(isIPv6);
                int min = isIPv6 ? PeerState2.MIN_MLKEM768_IPV6_MTU : PeerState2.MIN_MLKEM768_IPV4_MTU;
                if (ourmtu > 0 && ourmtu < min) {
                    _currentState = InboundState.IB_STATE_FAILED;
                    UDPPacket retry = transport.getBuilder2().buildRetryPacket(this, SSU2Util.REASON_VERSION);
                    transport.send(retry);
                    throw new GeneralSecurityException("Our MTU too small for version 4 (retry sent): " + ourmtu);
                }
                if (type == SESSION_REQUEST_FLAG_BYTE) {
                    int ipOverhead = (isIPv6 ? PacketBuilder2.IPV6_HEADER_SIZE : PacketBuilder2.IP_HEADER_SIZE) + PacketBuilder2.UDP_HEADER_SIZE;
                    _mtu = Math.max(min, len + ipOverhead);
                }
                pattern = NoiseInit.PatternID.XKHFS_768_SSU2;
                overhead += MAC_LEN + EncType.MLKEM768_X25519_INT.getPubkeyLen();
                hkf = _context.eciesEngine().getHybridKeyFactory(EncType.MLKEM768_X25519);
                _handshakeState = new HandshakeState(pattern, HandshakeState.RESPONDER, transport.getXDHFactory(), hkf);
                break;
            default:
                throw new GeneralSecurityException("Bad version " + _version + " in session/token request from: " + _aliceSocketAddress);
        }
        _handshakeState.getLocalKeyPair().setKeys(transport.getSSU2StaticPrivKey(), 0,
                                                   transport.getSSU2StaticPubKey(), 0);
        String aliceIP = _aliceSocketAddress.toString().replace("/", "");
        if (type == TOKEN_REQUEST_FLAG_BYTE) {
            if (_log.shouldDebug()) {_log.debug("[SSU] Received Token Request from: " + aliceIP);}
            _currentState = InboundState.IB_STATE_TOKEN_REQUEST_RECEIVED;
            // decrypt in-place
            ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
            chacha.initializeKey(introKey, 0);
            long n = DataHelper.fromLong(data, off + PKT_NUM_OFFSET, 4);
            chacha.setNonce(n);
            chacha.decryptWithAd(data, off, LONG_HEADER_SIZE,
                                 data, off + LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE);
            chacha.destroy();
            processPayload(data, off + LONG_HEADER_SIZE, len - (LONG_HEADER_SIZE + MAC_LEN), true);
            _sendHeaderEncryptKey2 = introKey;
            do {token = ctx.random().nextLong();}
            while (token == 0);
            _token = token;
        } else if (type == SESSION_REQUEST_FLAG_BYTE && (token == 0 ||
                    (ENFORCE_TOKEN && !_transport.getEstablisher().isInboundTokenValid(_remoteHostId, token)))) {
            // i2pd thru 0.9.55 ignores zero token + termination in retry
            if (_log.shouldInfo()) {
                _log.info("[SSU] Invalid token [" + token + "] in Session Request from: " + aliceIP);
            }
            if (token == 0) {
                throw new GeneralSecurityException("Zero token in Session Request from: " + aliceIP);
            }
            _currentState = InboundState.IB_STATE_REQUEST_BAD_TOKEN_RECEIVED;
            _sendHeaderEncryptKey2 = introKey;
            // Generate token for the retry.
            // We do NOT register it with the EstablishmentManager, it must be used immediately.
            do {token = ctx.random().nextLong();}
            while (token == 0);
            _token = token;
            // do NOT bother to init the handshake state and decrypt the payload
            _timeReceived = _establishBegin;
        } else {
            // fast MSB check for key < 2^255
            if ((data[off + LONG_HEADER_SIZE + KEY_LEN - 1] & 0x80) != 0) {
                throw new GeneralSecurityException(" BAD PK message #1");
            }
            // probably don't need again
            _token = token;
            _handshakeState.start();
            if (_version != 2)
                _handshakeState.mixHash(_context.routerHash().getData(), 0, 32);
            _handshakeState.mixHash(data, off, LONG_HEADER_SIZE);

            // decrypt in-place
            try {
                _handshakeState.readMessage(data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE);
            } catch (GeneralSecurityException gse) {
                if (_log.shouldDebug()) {
                    _log.debug("[SSU] Session Request error -> State at failure: " + _handshakeState +
                               '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
                }
                throw gse;
            }
            processPayload(data, off + LONG_HEADER_SIZE, len - overhead, true);
            _sendHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessCreateHeader");
            _currentState = InboundState.IB_STATE_REQUEST_RECEIVED;
        }
        if (_currentState == InboundState.IB_STATE_FAILED) {
            // termination block received
            throw new GeneralSecurityException("Termination block in Session/Token Request");
        }
        if (_timeReceived == 0) {
            _currentState = InboundState.IB_STATE_FAILED;
            throw new GeneralSecurityException("No DateTime block in Session/Token Request");
        }
        _skew = _establishBegin - _timeReceived;
        if (_skew > MAX_SKEW || _skew < 0 - MAX_SKEW) {
            _currentState = InboundState.IB_STATE_FAILED;
            // send retry with termination
            UDPPacket retry = _transport.getBuilder2().buildRetryPacket(this, SSU2Util.REASON_SKEW);
            _transport.send(retry);
            throw new GeneralSecurityException("Max skew of 2m exceeded (" + _skew + "ms) in Session/Token Request (Retry sent)");
        }
        packetReceived();
        if (_log.shouldDebug()) {_log.debug("[SSU] New request type " + type + " len " + len + " on " + this);}
    }

    /**
     * The negotiated SSU 2 version.
     * @return the version
     */
    @Override
    public int getVersion() {return _version;}

    private void processPayload(byte[] payload, int offset, int length, boolean isHandshake) throws GeneralSecurityException {
        try {
            int blocks = SSU2Payload.processPayload(_context, this, payload, offset, length, isHandshake, null);
            if (_log.shouldDebug()) {_log.debug("[SSU] Processed " + blocks + " blocks on " + this);}
        } catch (RIException rie) {
            if (_log.shouldInfo()) {_log.info("[SSU] RouterInfo error: " + rie.getMessage());}
            int reason = rie.getReason();
            PeerStateDestroyed psd = createPeerStateDestroyed(reason);
            _transport.addRecentlyClosed(psd);
            try {
                UDPPacket pkt = _transport.getBuilder2().buildSessionDestroyPacket(reason, psd);
                _transport.send(pkt);
                if (_log.shouldInfo()) {
                    _log.info("[SSU] Sending TERMINATION reason " + reason + " to " + psd);
                    if (!shouldSuppressException(rie)) {
                        _log.info("[SSU] InboundEstablishState Payload Error", rie);
                    }
                }
            } catch (IOException ioe) { /* ignored */ }
            throw new GeneralSecurityException("IES2 Payload Error: " + this, rie);
        } catch (DataFormatException dfe) {
            // no in-session response possible
            if (!shouldSuppressException(dfe)) {
                _log.info("[SSU] InboundEstablishState Payload Error", dfe);
            }
            throw new GeneralSecurityException("IES2 Payload Error: " + this, dfe);
        } catch (Exception e) {
            if (!shouldSuppressException(e) && _log.shouldInfo()) {
                _log.info("[SSU] InboundEstablishState Payload Error\n" + net.i2p.util.HexDump.dump(payload, 0, length), e);
            }
            throw new GeneralSecurityException("IES2 Payload Error", e);
        }
    }

    /////////////////////////////////////////////////////////
    // begin payload callbacks
    /////////////////////////////////////////////////////////

    /**
     * Got a DateTime block.
     */
    @Override
    public void gotDateTime(long time) {
        _timeReceived = time;
    }

    /**
     * Got an Options block.
     */
    public void gotOptions(byte[] options, boolean isHandshake) {
        if (_log.shouldDebug()) {_log.debug("[SSU] Received OPTIONS block");}
    }

    /**
     *   For most errors here we throw a RIException with a reason code,
     *   which is caught in processPayload() to create a PeerStateDestroyed
     *   and send a termination with that reason.
     *
     *   Plain DataFormatExceptions indicate you may not respond in-session.
     */
    public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException {
        if (isHandshake) {throw new DataFormatException("RouterInfo in Session Request");}
        if (_receivedUnconfirmedIdentity != null) {throw new DataFormatException("Duplicate RouterInfo in SessionConfirmed");}
        _receivedUnconfirmedIdentity = ri.getIdentity();
        if (ri.getPublished() < 0) {throw new RIException("Invalid publication date in RouterInfo", REASON_BANNED);}

        // try to find the right address, because we need the MTU
        boolean isIPv6 = _aliceIP.length == 16;
        AddressSelection sel = selectSessionAddress(_transport.getTargetAddresses(ri), isIPv6, _aliceIP);
        RouterAddress ra = sel.ra;
        String mismatchMessage = sel.mismatchMessage;
        if (ra == null) {throw new DataFormatException("No SSU2 address, IPv6? " + isIPv6 + ": " + ri);}
        byte[] nb = new byte[KEY_LEN];
        // compare to the _handshakeState
        _handshakeState.getRemotePublicKey().getPublicKey(nb, 0);
        _sendHeaderEncryptKey1 = parseSessionKeys(ra, nb);

        // only after here can we throw RIExceptions and send a response in-session
        // because we have his ikey and we verified he's the owner of the RI
        Hash h = _receivedUnconfirmedIdentity.calculateHash();
        validateRouterInfo(ri, ra, mismatchMessage);

        _mtu = parseMTU(ra, isIPv6);

        try {
            RouterInfo old = _context.netDb().store(h, ri);
            if (flood && !ri.equals(old)) {
                FloodfillNetworkDatabaseFacade fndf = (FloodfillNetworkDatabaseFacade) _context.netDb();
                if (fndf.floodConditional(ri)) {
                    if (_log.shouldDebug()) {_log.debug("[SSU] Flooded the RouterInfo: " + h);}
                } else {
                    if (_log.shouldInfo()) {_log.info("[SSU] Declined request to flood RouterInfo: " + h);}
                }
            }
        } catch (IllegalArgumentException iae) {
            // generally expired/future RI
            long now = _context.clock().now();
            long published = ri.getPublished();
            throw new RIException("RouterInfo store fail: " + ri, storeFailureReason(now, published), iae);
        }

        _receivedConfirmedIdentity = _receivedUnconfirmedIdentity;
        // deferred relay tag request handling, now that we have the RI
        // formerly in EstablishmentManager.receiveSessionOrTokenReques()
        if (_introductionRequested) {
            String caps = ri.getCapabilities();
            switch (decideIntroduction(ri.getVersion(), caps, getSentPort() >= 1024,
                                       _transport.canIntroduce(isIPv6), _context.random().nextInt(4) == 0)) {
                case INTRO_KEEP:
                    // leave it set to true; createPeerState() will copy to PS2,
                    // who will send the relay tag with ACK 0
                    break;
                case INTRO_BLOCKED:
                    _introductionRequested = false;
                    break;
                default:
                    _introductionRequested = false;
                    if (_log.shouldWarn()) {
                        _log.warn("[SSU] Not offering to relay to Router version " + ri.getVersion() + " caps " + caps + ": " + this);
                    }
                    break;
            }
        }
        createPeerState();
        //_sendHeaderEncryptKey2 calculated below
    }

    /** A yggdrasil IPv6 address starts with 0x02 or 0x03 (first byte 0b0000001x). */
    private static final int YGGDRASIL_FIRST_BYTE_MASK = 0xfe;
    private static final int YGGDRASIL_FIRST_BYTE = 0x02;

    /** Bytes of the source IPv6 prefix that identify an address as this router's own. */
    private static final int IPV6_SELF_PREFIX_BYTES = 8;

    /** SSU 1.0 mtu option value that maps to MIN_MTU; workaround for a bug in 1.9.0. */
    private static final int SSU_V1_MTU_19_BUG = 1276;

    /** A RouterInfo published more than 2 minutes in the future is clock-skewed. */
    private static final long RI_SKEW_FUTURE_WINDOW = 2*60*1000L;

    /** A RouterInfo published more than 1 hour in the past is clock-skewed. */
    private static final long RI_SKEW_PAST_WINDOW = 60*60*1000L;

    /**
     *  Result of {@link #selectSessionAddress(List, boolean, byte[])}: the chosen
     *  RouterInfo address and, when the peer advertises a different IP than the
     *  sender's source address, an explanatory mismatch message.
     *
     *  @since 0.9.71+
     */
    static final class AddressSelection {
        /** the selected address, or null when no address qualified */
        final RouterAddress ra;
        /** set when the advertised IP differs from the sender's; null otherwise */
        final String mismatchMessage;

        AddressSelection(RouterAddress ra, String mismatchMessage) {
            this.ra = ra;
            this.mismatchMessage = mismatchMessage;
        }
    }

    /**
     *  Choose the RouterInfo address this session negotiates with, given the
     *  sender's IP family, so the caller can determine the MTU and keys. The
     *  rules mirror the SSU2 handshake:
     *  <ul>
     *  <li>with several addresses, a pure SSU 1 address (no "s" option) is skipped</li>
     *  <li>an address is skipped when its host is not compatible with the sender's
     *      IP family, unless the capacity bit marks it usable</li>
     *  <li>an address is skipped when it advertises the sender's own IP (yggdrasil
     *      addresses and a matching IPv6 source prefix cannot be ourselves)</li>
     *  <li>an address that advertises a different IP than the sender's source
     *      address is selected but flagged, so the caller can ban it after the
     *      signature is verified</li>
     *  </ul>
     *
     *  @param addrs the addresses from the peer's RouterInfo, in preference order
     *  @param isIPv6 true if the sender connected over IPv6
     *  @param aliceIP the sender's source IP bytes (4 for IPv4, 16 for IPv6)
     *  @return the selection; <code>ra</code> is null when no address qualified
     *  @since 0.9.71+
     */
    static AddressSelection selectSessionAddress(List<RouterAddress> addrs, boolean isIPv6, byte[] aliceIP) {
        for (RouterAddress addr : addrs) {
            // skip SSU 1 address w/o "s"
            if (addrs.size() > 1 && addr.getTransportStyle().equals("SSU") && addr.getOption("s") == null) {
                continue;
            }
            String host = addr.getHost();
            if (host == null) {host = "";}
            String caps = addr.getOption(UDPAddress.PROP_CAPACITY);
            if (caps == null) {caps = "";}
            if (isIPv6 && !host.contains(":") && !caps.contains(TransportImpl.CAP_IPV6)) {continue;}
            else if (!host.contains(".") && !caps.contains(TransportImpl.CAP_IPV4)) {continue;}
            String mismatchMessage = null;
            byte[] infoIP = addr.getIP();
            if (infoIP != null && infoIP.length == aliceIP.length) {
                if (isIPv6) {
                    if (((infoIP[0]) & YGGDRASIL_FIRST_BYTE_MASK) == YGGDRASIL_FIRST_BYTE) {continue;} // ygg
                    if (DataHelper.eq(aliceIP, 0, infoIP, 0, IPV6_SELF_PREFIX_BYTES)) {continue;}
                } else {
                    if (DataHelper.eq(aliceIP, infoIP)) {continue;}
                }
                // We will ban and throw below after checking signature
                mismatchMessage = "IP mismatch actual IP ".concat(Addresses.toString(aliceIP)).concat(" in RI: ");
            }
            return new AddressSelection(addr, mismatchMessage);
        }
        return new AddressSelection(null, null);
    }

    /**
     *  Resolve the session MTU from the peer's "mtu" option. Pure decision logic:
     *  a missing or unparsable value falls back to the per-style/family default,
     *  the 1.9.0 workaround maps 1276 to MIN_MTU for SSU 1, and any other value
     *  is clamped into the legal range for the negotiated style and IP family. A
     *  declared MTU below MIN_MTU (other than the workaround) is rejected.
     *
     *  @param ra the negotiated SSU2/SSU address
     *  @param isIPv6 true for an IPv6 session
     *  @return the effective MTU
     *  @throws RIException with REASON_OPTIONS if a declared MTU is below MIN_MTU
     *  @since 0.9.71+
     */
    static int parseMTU(RouterAddress ra, boolean isIPv6) throws RIException {
        String smtu = ra.getOption(UDPAddress.PROP_MTU);
        int mtu = 0;
        try {mtu = Integer.parseInt(smtu);}
        catch (NumberFormatException nfe) { /* ignored */ }
        if (mtu == 0) {
            if (ra.getTransportStyle().equals(UDPTransport.STYLE2)) {return PeerState2.DEFAULT_MTU;}
            if (isIPv6) {return PeerState2.DEFAULT_SSU_IPV6_MTU;}
            return PeerState2.DEFAULT_SSU_IPV4_MTU;
        }
        if (mtu == SSU_V1_MTU_19_BUG && ra.getTransportStyle().equals("SSU")) {return PeerState2.MIN_MTU;} // workaround for bug in 1.9.0
        // if too small, give up now
        if (mtu < PeerState2.MIN_MTU) {throw new RIException("MTU too small " + mtu, REASON_OPTIONS);}
        if (ra.getTransportStyle().equals(UDPTransport.STYLE2)) {return Math.min(Math.max(mtu, PeerState2.MIN_MTU), PeerState2.MAX_MTU);}
        if (isIPv6) {return Math.min(Math.max(mtu, PeerState2.MIN_SSU_IPV6_MTU), PeerState2.MAX_SSU_IPV6_MTU);}
        return Math.min(Math.max(mtu, PeerState2.MIN_SSU_IPV4_MTU), PeerState2.MAX_SSU_IPV4_MTU);
    }

    /**
     *  Classify a RouterInfo store failure - generally an expired or
     *  future-dated RouterInfo that the netDb rejected - into a termination
     *  reason.
     *
     *  @param now current clock time
     *  @param published the RI's publication date
     *  @return REASON_SKEW when the publication date is outside the accepted skew
     *          window, otherwise REASON_MSG3
     *  @since 0.9.71+
     */
    static int storeFailureReason(long now, long published) {
        if (published > now + RI_SKEW_FUTURE_WINDOW || published < now - RI_SKEW_PAST_WINDOW) {return REASON_SKEW;}
        return REASON_MSG3;
    }

    /**
     *  Decode and verify the SSU2 IKey/S options of the negotiated RouterInfo
     *  address and verify that the peer's S key matches the X25519 public key
     *  already derived from the handshake. Throws before any session state is
     *  modified, so a malformed or dishonest RouterInfo cannot corrupt the
     *  established keys.
     *
     *  @param ra the negotiated address; must carry the "i" and "s" options
     *  @param publicKey the X25519 public key from {@code _handshakeState}
     *  @return the verified IKey, to install as the send header key
     *  @throws DataFormatException with a type-specific message on any bad key or
     *          an S/public-key mismatch
     *  @since 0.9.71+
     */
    static byte[] parseSessionKeys(RouterAddress ra, byte[] publicKey) throws DataFormatException {
        String siv = ra.getOption("i");
        if (siv == null) {throw new DataFormatException("No SSU2 IKey");}
        byte[] ik = Base64.decode(siv);
        if (ik == null) {throw new DataFormatException("BAD SSU2 IKey");}
        if (ik.length != KEY_LEN) {throw new DataFormatException("BAD SSU2 IKey length");}
        String ss = ra.getOption("s");
        if (ss == null) {throw new DataFormatException("No SSU2 S");}
        byte[] s = Base64.decode(ss);
        if (s == null) {throw new DataFormatException("BAD SSU2 S");}
        if (s.length != KEY_LEN) {throw new DataFormatException("BAD SSU2 S length");}
        if (!DataHelper.eqCT(s, 0, publicKey, 0, KEY_LEN)) {throw new DataFormatException("S mismatch in RouterInfo");}
        return ik;
    }

    /** Keep the introduction request; createPeerState() sends the relay tag with ACK 0. */
    static final int INTRO_KEEP = 0;
    /** Drop it: a temporary sent port or no introduction support on the peer's family. */
    static final int INTRO_BLOCKED = 1;
    /** Drop it: the peer's version is too old to relay to. */
    static final int INTRO_VERSION = 2;
    /** Drop it: the peer advertises itself reachable; relaying is not required. */
    static final int INTRO_OPPORTUNISTIC = 3;

    /**
     *  Decide whether a deferred relay-tag request survives once the peer's
     *  RouterInfo is known. Pure decision logic so the branch table is testable:
     *  <ul>
     *  <li>we cannot relay while our sent port is a temporary one or the transport
     *      does not support introduction on the peer's IP family</li>
     *  <li>we do not relay to routers running older than {@link #MIN_RELAY_VERSION}</li>
     *  <li>when the peer already advertises itself reachable we only relay on a
     *      1-in-4 draw - the peer is likely starting up and simply unaware of its
     *      reachability</li>
     *  </ul>
     *
     *  @param version the peer's RouterInfo version string
     *  @param caps the peer's RouterInfo capabilities, or null
     *  @param sentPortOk true if our sent port is a real, stable port (at least 1024)
     *  @param canIntroduce true if the transport can introduce on the peer's IP family
     *  @param randomKeep the pre-rolled 1-in-4 opportunistic keep draw
     *  @return {@link #INTRO_KEEP}, {@link #INTRO_BLOCKED}, {@link #INTRO_VERSION}
     *          or {@link #INTRO_OPPORTUNISTIC}
     *  @since 0.9.71+
     */
    static int decideIntroduction(String version, String caps, boolean sentPortOk, boolean canIntroduce, boolean randomKeep) {
        if (!sentPortOk || !canIntroduce) {return INTRO_BLOCKED;}
        if (VersionComparator.comp(version, MIN_RELAY_VERSION) < 0) {return INTRO_VERSION;}
        if (caps != null && caps.indexOf(Router.CAPABILITY_REACHABLE) >= 0 && !randomKeep) {return INTRO_OPPORTUNISTIC;}
        return INTRO_KEEP;
    }

    /**
     * Validates the RouterInfo for banning conditions and throws RIException if any issue is found.
     * Checks:
     * - Valid publication date
     * - Banned status
     * - Network ID match
     * - IP match
     * - SSU2 version
     * - Old/slow router status
     */
    private void validateRouterInfo(RouterInfo ri, RouterAddress ra, String mismatchMessage) throws RIException {
        Hash h = _receivedUnconfirmedIdentity.calculateHash();
        boolean isBanned = _context.banlist().isBanlisted(h) ||
                           _context.banlist().isBanlistedHostile(h) ||
                           _context.banlist().isBanlistedForever(h);
        String ipPort = Addresses.toString(_aliceIP, _alicePort);

        if (isBanned) {
            if (ri.verifySignature()) {
                _context.blocklist().add(_aliceIP);
            }
            throw new RIException("Router is banned: " + h.toBase64(), REASON_BANNED);
        }

        if (ri.getNetworkId() != _context.router().getNetworkID()) {
            if (ri.verifySignature()) {
                _context.blocklist().add(_aliceIP);
            }
            throw new RIException("SSU2 network ID mismatch", REASON_NETID);
        }

        if (ri.getPublished() < 0) {
            // RI format error, signature was verified, so we can take action
            if (ri.verifySignature()) {
                _context.blocklist().add(_aliceIP);
            }
            // These really hammer the floodfills, so reduce the time on floodfills
            long banDuration = _context.netDb().floodfillEnabled() ? 36*60*60*1000L : 4*24*60*60*1000L;
            _context.banlist().banlistRouter(h, "Invalid publication date",
                                             null, null, _context.clock().now() + banDuration);
            _banLogger.logBan(h, ipPort, "Invalid publication date", banDuration, ri);
            if (_log.shouldWarn() && !isBanned) {
                _log.warn("Banning for 1h and disconnecting from Router [" +
                          h.toBase64().substring(0,6) + "] -> Invalid publication date");
            }
            throw new RIException("Invalid publication date in RouterInfo", REASON_BANNED);
        }

        if (mismatchMessage != null) {
            _context.banlist().banlistRouter(h, "Invalid SSU address",
                                             null, null, _context.clock().now() + 4 * 60 * 60 * 1000L);
            _banLogger.logBan(h, ipPort, "Invalid SSU address", 4 * 60 * 60 * 1000L, ri);
            _context.commSystem().forceDisconnect(h, "Invalid SSU address");
            if (_log.shouldWarn() && !isBanned) {
                _log.warn("Banning for 4h and disconnecting from Router [" +
                          h.toBase64().substring(0,6) + "] -> Invalid SSU address");
            }
            if (ri.verifySignature()) {
                _context.blocklist().add(_aliceIP);
            }
            throw new RIException(mismatchMessage + ri, REASON_BANNED);
        }

        if (!"2".equals(ra.getOption("v"))) {
            throw new RIException("BAD SSU2 v", REASON_VERSION);
        }

        String cap = ri.getCapabilities();
        String bw = ri.getBandwidthTier();
        boolean reachable = cap != null && cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
        boolean isSlow = (cap != null && !cap.isEmpty()) &&
                         (bw.equals("K") || bw.equals("L") || bw.equals("M") || bw.equals("N"));
        String version = ri.getVersion();
        boolean isOld = VersionComparator.comp(version, "0.9.66") < 0;

        if (!reachable && isSlow && isOld) {
            _context.banlist().banlistRouter(h, "Old and slow (" + version + " / " + bw + "U)",
                                             null, null, _context.clock().now() + 60 * 60 * 1000L);
            _banLogger.logBan(h, ipPort, "Old and slow (" + version + " / " + bw + "U)", 60 * 60 * 1000L, ri);
            if (ri.verifySignature()) {
                _context.blocklist().add(_aliceIP);
            }
            if (_log.shouldInfo() && !isBanned) {
                _log.info("Banning for 1h and disconnecting from Router [" +
                          h.toBase64().substring(0,6) + "] -> " + version + " / " + bw + "U");
            }
            _context.commSystem().forceDisconnect(h, "Old and slow (" + version + ")");
            throw new RIException("Old and slow: " + h, REASON_BANNED);
        }
    }

    /**
     * Got an RI fragment block.
     */
    @Override
    public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped, int frag, int totalFrags) {
        if (_log.shouldDebug()) {
            _log.debug("[SSU] Received RouterInfo fragment [" + frag + " / " + totalFrags + "]");
        }
        // not supported, we fragment the whole message now
        throw new IllegalStateException("Fragmented RouterInfo");
    }

    /**
     * Got an Address block.
     */
    @Override
    public void gotAddress(byte[] ip, int port) {
        if (_log.shouldDebug()) {_log.debug("[SSU] Received IP address: " + Addresses.toString(ip, port));}
        _bobIP = ip;
        // final, see super
    }

    /**
     * Got a RelayTagRequest block.
     */
    @Override
    public void gotRelayTagRequest() {
        if (_log.shouldDebug()) {_log.debug("[SSU] Received RelayTagRequest on " + this);}
        _introductionRequested = true;
    }

    /**
     * Got a RelayTag block.
     */
    @Override
    public void gotRelayTag(long tag) {
        // intentionally empty - relay tags are not applicable to inbound connections
    }

    /**
     * Got a RelayRequest block.
     */
    public void gotRelayRequest(byte[] data) {
        if (_receivedConfirmedIdentity == null) {
            throw new IllegalStateException("RouterInfo must be sent first");
        }
    }

    /**
     * Got a RelayResponse block.
     */
    @Override
    public void gotRelayResponse(int status, byte[] data) {
        if (_receivedConfirmedIdentity == null) {
            throw new IllegalStateException("RouterInfo must be sent first");
        }
    }

    /**
     * Got a RelayIntro block.
     */
    @Override
    public void gotRelayIntro(Hash aliceHash, byte[] data) {
        if (_receivedConfirmedIdentity == null) {
            throw new IllegalStateException("RouterInfo must be sent first");
        }
    }

    /**
     * Got a PeerTest block.
     */
    public void gotPeerTest(int msg, int status, Hash h, byte[] data) {
        if (_receivedConfirmedIdentity == null) {
            throw new IllegalStateException("RouterInfo must be sent first");
        }
        _transport.getPeerTestManager().receiveTest(_remoteHostId, _pstate, msg, status, h, data);
    }

    /**
     * Got a Token block.
     */
    @Override
    public void gotToken(long token, long expires) {
        if (_log.shouldDebug()) {
            _log.debug("[SSU] Received Token: " + token + " expires " + DataHelper.formatTime(expires) + " on " + this);
        }
        if (_receivedConfirmedIdentity == null) {
            throw new IllegalStateException("RouterInfo must be sent first");
        }
        _transport.getEstablisher().addOutboundToken(_remoteHostId, token, expires);
    }

    /**
     * Got an I2NP block.
     */
    @Override
    public void gotI2NP(I2NPMessage msg) {
        if (_log.shouldDebug()) {_log.debug("[SSU] Received I2NP block: " + msg);}
        if (getState() != InboundState.IB_STATE_CREATED_SENT) {
            throw new IllegalStateException("I2NP in Session Request");
        }
        if (_receivedConfirmedIdentity == null) {
            throw new IllegalStateException("RouterInfo must be sent first");
        }
        // pass to PeerState2
        _pstate.gotI2NP(msg);
    }

    /**
     * Got a Fragment block.
     */
    @Override
    public void gotFragment(byte[] data, int off, int len, long messageID, int frag, boolean isLast) throws DataFormatException {
        if (_log.shouldDebug()) {_log.debug("[SSU] Received FRAGMENT block: " + messageID);}
        if (getState() != InboundState.IB_STATE_CREATED_SENT) {
            throw new IllegalStateException("I2NP in Session Request");
        }
        if (_receivedConfirmedIdentity == null) {
            throw new IllegalStateException("RouterInfo must be sent first");
        }
        // pass to PeerState2
        _pstate.gotFragment(data, off, len, messageID, frag, isLast);
    }

    /**
     * Got an ACK block.
     */
    public void gotACK(long ackThru, int acks, byte[] ranges) {
        throw new IllegalStateException("ACK in Handshake");
    }

    /**
     * Got a Termination block.
     */
    public void gotTermination(int reason, long count) {
        if (_log.shouldInfo()) {
            _log.info("[SSU] Received TERMINATION block -> " + SSU2Util.terminationCodeToString(reason) + "; Count: " + count + "\n* " + this);
        }
        // this sets the state to FAILED
        fail();
        _transport.getEstablisher().receiveSessionDestroy(_remoteHostId);
    }

    /**
     * Got a PathChallenge block.
     */
    public void gotPathChallenge(RemoteHostId from, byte[] data) {
        throw new IllegalStateException("BAD block in handshake");
    }

    /**
     * Got a PathResponse block.
     */
    public void gotPathResponse(RemoteHostId from, byte[] data) {
        throw new IllegalStateException("BAD block in handshake");
    }

    /////////////////////////////////////////////////////////
    // end payload callbacks
    /////////////////////////////////////////////////////////

    // SSU 1 overrides

    /**
     *  Overridden to destroy the handshake state
     *  @since 0.9.56
     */
    @Override
    public synchronized void fail() {
        _handshakeState.destroy();
        // release any pooled data packets queued before the PeerState was created,
        // otherwise they leak on a failed/out-of-order establish (case 1 in
        // queuePossibleDataPacket). getPeerState() only releases them on success.
        if (_queuedDataPackets != null) {
            for (UDPPacket packet : _queuedDataPackets) {
                packet.release();
            }
            _queuedDataPackets.clear();
        }
        super.fail();
    }

    // SSU 2 things

    /**
     * The send conn ID.
     * @return the send conn id
     */
    public long getSendConnID() {return _sendConnID;}
    /**
     * The rcv conn ID.
     * @return the rcv conn id
     */
    public long getRcvConnID() {return _rcvConnID;}
    /**
     * The token.
     * @return the token
     */
    public long getToken() {return _token;}
    /**
     *  @return may be null
     */
    public EstablishmentManager.Token getNextToken() {
        if (_aliceIP.length == 4 && _transport.isSymNatted()) {return null;}
        return _transport.getEstablisher().getInboundToken(_remoteHostId);
    }
    /**
     * The handshake state.
     * @return the handshake state
     */
    public HandshakeState getHandshakeState() {return _handshakeState;}
    /**
     * The send header encrypt key1.
     * @return the send header encrypt key1
     */
    public byte[] getSendHeaderEncryptKey1() {return _sendHeaderEncryptKey1;}
    /**
     * The rcv header encrypt key1.
     * @return the rcv header encrypt key1
     */
    public byte[] getRcvHeaderEncryptKey1() {return _transport.getSSU2StaticIntroKey();}
    /**
     * The send header encrypt key2.
     * @return the send header encrypt key2
     */
    public byte[] getSendHeaderEncryptKey2() {return _sendHeaderEncryptKey2;}
    /**
     * The rcv header encrypt key2.
     * @return the rcv header encrypt key2
     */
    public synchronized byte[] getRcvHeaderEncryptKey2() {return _rcvHeaderEncryptKey2;}
    /**
     * The sent address.
     * @return the sent address
     */
    public InetSocketAddress getSentAddress() {return _aliceSocketAddress;}

    /**
     * Record that we sent the SessionCreated packet.
     */
    @Override
    public synchronized void createdPacketSent() {
        /// todo state check
        if (_rcvHeaderEncryptKey2 == null) {
            _rcvHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessionConfirmed");
        }
        _lastSend = _context.clock().now();
        long delay;
        if (_createdSentCount == 0) {delay = RETRANSMIT_DELAY;}
        else {delay = Math.min(RETRANSMIT_DELAY << _createdSentCount, getMaxDelay());}
        _createdSentCount++;
        _nextSend = _lastSend + delay;
        _currentState = InboundState.IB_STATE_CREATED_SENT;
    }


    /** Note that we just sent a Retry packet. */
    public synchronized void retryPacketSent() {
        // retry after clock skew
        if (_currentState == InboundState.IB_STATE_FAILED) {return;}
        if (_currentState != InboundState.IB_STATE_RETRY_SENT &&
            _currentState != InboundState.IB_STATE_REQUEST_BAD_TOKEN_RECEIVED &&
            _currentState != InboundState.IB_STATE_TOKEN_REQUEST_RECEIVED) {
            throw new IllegalStateException("BAD state for Retry Sent: " + _currentState);
        }
        _lastSend = _context.clock().now();
        if (_currentState == InboundState.IB_STATE_RETRY_SENT) {
            // We received a retransmtted token request and resent the retry.
            // Won't really be retransmitted, they have 5 sec to respond
            // ensure we expire before retransmitting
            _nextSend = _establishBegin + (5 * RETRANSMIT_DELAY);
            if (_log.shouldWarn())
                _log.warn("[SSU] Retransmit RETRY on " + this);
        } else {
        _currentState = InboundState.IB_STATE_RETRY_SENT;
        // Won't really be retransmitted, they have 5 sec to respond or
        // EstablishmentManager.handleInbound() will fail the connection
        // Alice will retransmit at 1 and 3 seconds, so wait 5
        // We're not going to wait for the 3rd retx at 7 seconds.
        _nextSend = _lastSend + (5 * RETRANSMIT_DELAY);
        }
    }

    /**
     *  All exceptions thrown from here will be fatal. fail() will be called before throwing.
     */
    public synchronized void receiveSessionOrTokenRequestAfterRetry(UDPPacket packet) throws GeneralSecurityException {
        try {
            locked_receiveSessionOrTokenRequestAfterRetry(packet);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("[SSU] Session or Token Request error after retry", gse);
            // fail inside synch rather than have Est. Mgr. do it to prevent races
            fail();
            throw gse;
        }
    }

    /**
     * @since 0.9.56
     */
    private void locked_receiveSessionOrTokenRequestAfterRetry(UDPPacket packet) throws GeneralSecurityException {
        DatagramPacket pkt = packet.getPacket();
        SocketAddress from = pkt.getSocketAddress();
        if (!from.equals(_aliceSocketAddress)) {
            throw new GeneralSecurityException("Address mismatch -> Request: " + _aliceSocketAddress + " Conf: " + from);
        }
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte[] data = pkt.getData();
        long rid = DataHelper.fromLong8(data, off);
        if (rid != _rcvConnID) {
            throw new GeneralSecurityException("Connection ID mismatch -> 1: " + _rcvConnID + " 2: " + rid);
        }
        long sid = DataHelper.fromLong8(data, off + 16);
        if (sid != _sendConnID) {
            throw new GeneralSecurityException("Connection ID mismatch -> 1: " + _sendConnID + " 2: " + sid);
        }

        int type = data[off + TYPE_OFFSET] & 0xff;
        if (_currentState != InboundState.IB_STATE_RETRY_SENT) {
            // not fatal
            if (_log.shouldWarn()) {
                _log.warn("[SSU] Received out-of-order or RETRANSMIT message (Type " + type + ") on: " + this);
            }
            return;
        }
        if (type == TOKEN_REQUEST_FLAG_BYTE) {
            // retransmitted token request
            if (_log.shouldWarn())
                _log.warn("[SSU] Received RETRANSMIT Token Request on: " + this);
            // Est. mgr will resend retry and call retryPacketSent()
            // Note that Java I2P < 0.9.57 doesn't handle retransmitted retries correctly,
            // so this won't work for them
            long now = _context.clock().now();
            // rate limit
            _nextSend = Math.max(now, _lastSend + 750);
            return;
        }
        if (_log.shouldDebug())
            _log.debug("[SSU] Received Session Request after retry on: " + this);
        long token = DataHelper.fromLong8(data, off + 24);
        if (token != _token) {
            // most likely a retransmitted session request with the old invalid token
            // TODO should we retransmit retry in this case?
            throw new GeneralSecurityException("Token mismatch -> Expected: " + _token + " Received: " + token);
        }
        _handshakeState.start();
        int overhead = LONG_HEADER_SIZE + KEY_LEN + MAC_LEN;
        switch (_version) {
            case 2:
                break;
            case 3: {
                overhead += MAC_LEN + EncType.MLKEM512_X25519_INT.getPubkeyLen();
                _handshakeState.mixHash(_context.routerHash().getData(), 0, 32);
                boolean isIPv6 = _aliceIP.length == 16;
                int ipOverhead = (isIPv6 ? PacketBuilder2.IPV6_HEADER_SIZE : PacketBuilder2.IP_HEADER_SIZE) + PacketBuilder2.UDP_HEADER_SIZE;
                int mtu = Math.max(PeerState2.MIN_MTU, len + ipOverhead);
                if (mtu > _mtu)
                    _mtu = mtu;
                break;
            }
            case 4: {
                overhead += MAC_LEN + EncType.MLKEM768_X25519_INT.getPubkeyLen();
                _handshakeState.mixHash(_context.routerHash().getData(), 0, 32);
                boolean isIPv6 = _aliceIP.length == 16;
                int ipOverhead = (isIPv6 ? PacketBuilder2.IPV6_HEADER_SIZE : PacketBuilder2.IP_HEADER_SIZE) + PacketBuilder2.UDP_HEADER_SIZE;
                int min = isIPv6 ? PeerState2.MIN_MLKEM768_IPV6_MTU : PeerState2.MIN_MLKEM768_IPV4_MTU;
                int mtu = Math.max(min, len + ipOverhead);
                if (mtu > _mtu)
                    _mtu = mtu;
                break;
            }
            default:
                throw new GeneralSecurityException("Bad version " + _version + " in session/token request from: " + _aliceSocketAddress);
        }
        _handshakeState.mixHash(data, off, 32);

        // decrypt in-place
        try {
            _handshakeState.readMessage(data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("[SSU] Session Request error -> State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
            throw gse;
        }

        _timeReceived = 0;
        processPayload(data, off + LONG_HEADER_SIZE, len - overhead, true);
        packetReceived();
        if (_currentState == InboundState.IB_STATE_FAILED) {
            // termination block received
            throw new GeneralSecurityException("Termination block in Session Request");
        }
        if (_timeReceived == 0)
            throw new GeneralSecurityException("No DateTime block in Session Request");
        // _nextSend is now(), from packetReceived()
        _rtt = (int) (_nextSend - _lastSend);
        _skew = (_nextSend - _timeReceived) - (_rtt / 2);
        if (_skew > MAX_SKEW || _skew < 0 - MAX_SKEW) {
            // send another retry with termination
            UDPPacket retry = _transport.getBuilder2().buildRetryPacket(this, SSU2Util.REASON_SKEW);
            _transport.send(retry);
            throw new GeneralSecurityException("Max skew of 2m exceeded (" + _skew + "ms) in Session Request");
        }
        _sendHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessCreateHeader");
        _currentState = InboundState.IB_STATE_REQUEST_RECEIVED;
    }

    /**
     * Receive the last messages in the handshake, and create the PeerState.
     * If the message is fragmented, store the data for reassembly and return,
     * unless this was the last one.
     *
     * Exceptions thrown from here are fatal.
     *
     * @return the new PeerState2 if are done, may also be retrieved from getPeerState(),
     *         or null if more fragments to go
     */
    public synchronized PeerState2 receiveSessionConfirmed(UDPPacket packet) {
        try {
            return locked_receiveSessionConfirmed(packet);
        } catch (GeneralSecurityException gse) {
            fail();
            throw new RuntimeException(gse);
        } catch (RuntimeException re) {
            // HandshakeState throws IllegalStateException when state is FAILED,
            // IllegalArgumentException for low-order/invalid Curve25519 keys, etc.
            if (_log.shouldWarn()) {
                _log.warn("[SSU] Corrupt SessionConfirmed received from " + this);
            }
            // Track probing attempts (skip if already blocklisted)
            if (!_context.blocklist().isBlocklisted(Addresses.toString(_aliceIP))) {
                _context.banlist().badPacket(Addresses.toString(_aliceIP), null);
            }
            fail();
            throw re;
        }
    }

    /**
     *  @since 0.9.56
     */
    private PeerState2 locked_receiveSessionConfirmed(UDPPacket packet) throws GeneralSecurityException {
        if (_currentState != InboundState.IB_STATE_CREATED_SENT &&
            _currentState != InboundState.IB_STATE_CONFIRMED_PARTIALLY) {
            throw new GeneralSecurityException("BAD state for SessionConfirmed: " + _currentState);
        }
        DatagramPacket pkt = packet.getPacket();
        SocketAddress from = pkt.getSocketAddress();
        if (!from.equals(_aliceSocketAddress)) {
            throw new GeneralSecurityException("Address mismatch -> Request: " + _aliceSocketAddress + " Conf: " + from);
        }

        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte[] data = pkt.getData();
        long rid = DataHelper.fromLong8(data, off);
        if (rid != _rcvConnID) {
            throw new GeneralSecurityException("Connection ID mismatch -> Request: " + _rcvConnID + " Conf: " + rid);
        }

        byte fragbyte = data[off + SHORT_HEADER_FLAGS_OFFSET];
        int frag = (fragbyte >> 4) & 0x0f;
        // allow both 0/0 (development) and 0/1 to indicate sole fragment
        int totalfrag = fragbyte & 0x0f;
        if (totalfrag > 0 && frag > totalfrag - 1) {
            throw new GeneralSecurityException("BAD SessionConfirmed fragment [" + frag + " / " + totalfrag + "]");
        }
        if (totalfrag > 1) {
            // Fragment processing. Save fragment.
            // If we have all fragments, reassemble and continue,
            // else return to await more.
            if (_sessConfFragments == null) {
                _sessConfFragments = new byte[totalfrag][];
                // change state so we will no longer retransmit session created
                _currentState = InboundState.IB_STATE_CONFIRMED_PARTIALLY;
                _sessCrForReTX = null;
                // force past expiration, we don't have anything to send until we have everything
                _nextSend = _lastSend + 60*1000L;
            } else {
                if (_sessConfFragments.length != totalfrag) // total frag changed
                    throw new GeneralSecurityException("BAD SessionConfirmed fragment [" + frag + " / " + totalfrag + "]");
                if (_sessConfFragments[frag] != null) {
                    if (_log.shouldInfo())
                        _log.info("[SSU] Received duplicate SessionConfirmed fragment [" + frag + "] on " + this);
                    // there is no facility to ack individual fragments
                    //packetReceived();
                    return null;
                }
            }
            if (_log.shouldInfo()) {
                _log.info("[SSU] Received " + len + " bytes SessionConfirmed fragment [" + frag + '/' + totalfrag + "] on " + this);
            }
            byte[] fragdata;
            if (frag == 0) {
                // preserve header
                fragdata = new byte[len];
                System.arraycopy(data, off, fragdata, 0, len);
            } else {
                // discard header
                len -= SHORT_HEADER_SIZE;
                fragdata = new byte[len];
                System.arraycopy(data, off + SHORT_HEADER_SIZE, fragdata, 0, len);
            }
            _sessConfFragments[frag] = fragdata;
            int totalsize = 0;
            for (int i = 0; i < totalfrag; i++) {
                if (_sessConfFragments[i] == null) {
                    if (_log.shouldInfo())
                        _log.info("[SSU] Still missing at least one SessionConfirmed fragment on " + this);
                    // there is no facility to ack individual fragments
                    //packetReceived();
                    return null;
                }
                totalsize += _sessConfFragments[i].length;
            }
            // we have all the fragments
            // make a jumbo packet and process it through noise
            len = totalsize;
            off = 0;
            data = new byte[len];
            int joff = 0;
            for (int i = 0; i < totalfrag; i++) {
                byte[] f = _sessConfFragments[i];
                System.arraycopy(f, 0, data, joff, f.length);
                joff += f.length;
            }
            if (_log.shouldInfo())
                _log.info("[SSU] Have all " + totalfrag + " SessionConfirmed fragments (total length: " + len + " bytes) on " + this);
        }
        _handshakeState.mixHash(data, off, SHORT_HEADER_SIZE);

        // decrypt in-place
        try {
            _handshakeState.readMessage(data, off + SHORT_HEADER_SIZE, len - SHORT_HEADER_SIZE, data, off + SHORT_HEADER_SIZE);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("[SSU] SessionConfirmed error -> State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
            throw gse;
        }

        processPayload(data, off + SHORT_HEADER_SIZE, len - (SHORT_HEADER_SIZE + KEY_LEN + MAC_LEN + MAC_LEN), false);
        packetReceived();
        if (_currentState == InboundState.IB_STATE_FAILED) {
            // termination block received
            throw new GeneralSecurityException("Termination block in SessionConfirmed");
        }
        _sessCrForReTX = null;

        if (_receivedConfirmedIdentity == null)
            throw new GeneralSecurityException("No RouterInfo in SessionConfirmed");

        // createPeerState() called from gotRI()

        _currentState = InboundState.IB_STATE_CONFIRMED_COMPLETELY;
        return _pstate;
    }

    /**
     *  Creates the PeerState and stores in _pstate.
     *  Called from gotRI() so that we can pass any I2NP messages
     *  or fragments immediately to the PeerState.
     */
    private void createPeerState() {
        // split()
        // The CipherStates are from d_ab/d_ba,
        // not from k_ab/k_ba, so there's no use for
        // HandshakeState.split()
        byte[] ckd = _handshakeState.getChainingKey();
        byte[] k_ab = new byte[32];
        byte[] k_ba = new byte[32];
        HKDF hkdf = new HKDF(_context);
        hkdf.calculate(ckd, ZEROLEN, k_ab, k_ba, 0);
        // generate keys
        byte[] d_ab = new byte[32];
        byte[] h_ab = new byte[32];
        byte[] d_ba = new byte[32];
        byte[] h_ba = new byte[32];
        hkdf.calculate(k_ab, ZEROLEN, INFO_DATA, d_ab, h_ab, 0);
        hkdf.calculate(k_ba, ZEROLEN, INFO_DATA, d_ba, h_ba, 0);
        ChaChaPolyCipherState sender = new ChaChaPolyCipherState();
        sender.initializeKey(d_ba, 0);
        ChaChaPolyCipherState rcvr = new ChaChaPolyCipherState();
        rcvr.initializeKey(d_ab, 0);
        Arrays.fill(ckd, (byte) 0);
        Arrays.fill(k_ab, (byte) 0);
        Arrays.fill(k_ba, (byte) 0);
        Arrays.fill(d_ab, (byte) 0);
        Arrays.fill(d_ba, (byte) 0);
        _handshakeState.destroy();
        if (_createdSentCount == 1)
            _rtt = (int) ( _context.clock().now() - _lastSend );
        _pstate = new PeerState2(_context, _transport, _aliceSocketAddress,
                                 _receivedConfirmedIdentity.calculateHash(),
                                 true, _rtt, sender, rcvr,
                                 _sendConnID, _rcvConnID,
                                 _sendHeaderEncryptKey1, h_ba, h_ab);
        // PS2.super adds CLOCK_SKEW_FUDGE that doesn't apply here
        _pstate.adjustClockSkew(_skew - (_rtt / 2) - PeerState.getClockSkewFudge());
        _pstate.setHisMTU(_mtu);
        // set our address. _bobIP and _bobPort in super are not set for SSU2
        boolean isIPv6 = _aliceIP.length == 16;
        RouterAddress ra = _transport.getCurrentExternalAddress(isIPv6);
        if (ra != null)
            _pstate.setOurAddress(ra.getIP(), ra.getPort());
        if (_introductionRequested) {
            long tag = 1 + _context.random().nextLong(EstablishmentManager.MAX_TAG_VALUE);
            setSentRelayTag(tag);
            _pstate.setWeRelayToThemAs(tag);
        }
        _pstate.sendAck0();
    }

    /**
     *  Creates a PeerStateDestroyed after msg 3 failure,
     *  so we can send a termination and deal with subsequent in-session messages.
     *
     *  @since 0.9.57
     */
    private PeerStateDestroyed createPeerStateDestroyed(int reason) {
        byte[] ckd = _handshakeState.getChainingKey();
        byte[] k_ab = new byte[32];
        byte[] k_ba = new byte[32];
        HKDF hkdf = new HKDF(_context);
        hkdf.calculate(ckd, ZEROLEN, k_ab, k_ba, 0);
        byte[] d_ab = new byte[32];
        byte[] h_ab = new byte[32];
        byte[] d_ba = new byte[32];
        byte[] h_ba = new byte[32];
        hkdf.calculate(k_ab, ZEROLEN, INFO_DATA, d_ab, h_ab, 0);
        hkdf.calculate(k_ba, ZEROLEN, INFO_DATA, d_ba, h_ba, 0);
        ChaChaPolyCipherState sender = new ChaChaPolyCipherState();
        sender.initializeKey(d_ba, 0);
        ChaChaPolyCipherState rcvr = new ChaChaPolyCipherState();
        rcvr.initializeKey(d_ab, 0);
        _handshakeState.destroy();
        return new PeerStateDestroyed(_context, _transport, _remoteHostId,
                                      _sendConnID, _rcvConnID, sender, rcvr,
                                      _sendHeaderEncryptKey1, h_ba, h_ab, reason);
    }

    /**
     * Note that we just sent the SessionCreated packet,
     * and save it for retransmission.
     */
    public synchronized void createdPacketSent(DatagramPacket pkt) {
        if (_sessCrForReTX == null) {
            // store pkt for retx
            byte[] data = pkt.getData();
            int off = pkt.getOffset();
            int len = pkt.getLength();
            _sessCrForReTX = new byte[len];
            System.arraycopy(data, off, _sessCrForReTX, 0, len);
        }
        createdPacketSent();
    }

    /**
     * The retransmitted SessionCreated packet, or null if not sent or already got the session confirmed.
     * @return null if not sent or already got the session confirmed
     */
    public synchronized UDPPacket getRetransmitSessionCreatedPacket() {
        if (_sessCrForReTX == null)
            return null;
        if (_log.shouldInfo())
            _log.info("[SSU] RETRANSMIT SessionCreated on " + this);
        UDPPacket packet = UDPPacket.acquire(_context, false);
        DatagramPacket pkt = packet.getPacket();
        byte[] data = pkt.getData();
        int off = pkt.getOffset();
        System.arraycopy(_sessCrForReTX, 0, data, off, _sessCrForReTX.length);
        pkt.setLength(_sessCrForReTX.length);
        pkt.setSocketAddress(_aliceSocketAddress);
        packet.setMessageType(PacketBuilder2.TYPE_CONF);
        packet.setPriority(PacketBuilder2.PRIORITY_HIGH);
        createdPacketSent();
        return packet;
    }

    /**
     * The completed PeerState, or null if we have not received the session confirmed.
     * @return null if we have not received the session confirmed
     */
    public synchronized PeerState2 getPeerState() {
        if (_pstate != null) {
            _currentState = InboundState.IB_STATE_COMPLETE;
            if (_queuedDataPackets != null) {
                for (UDPPacket packet : _queuedDataPackets) {
                    if (_log.shouldInfo())
                        _log.info("[SSU] Passing possible data packet to PeerState2: " + this);
                    _pstate.receivePacket(packet);
                    packet.release();
                }
                _queuedDataPackets.clear();
            }
        }
        return _pstate;
    }

    /**
     * Queue a data packet received before the session completed.
     * @param packet with header still encrypted
     */
    public synchronized void queuePossibleDataPacket(UDPPacket packet) {
        if (_currentState == InboundState.IB_STATE_FAILED)
            return;
        if (_pstate == null) {
            // case 1, race or out-of-order, queue until we have the peerstate
            if (_queuedDataPackets == null) {
                _queuedDataPackets = new ArrayList<>(4);
            } else if (_queuedDataPackets.size() >= 10) {
                if (_log.shouldWarn())
                    _log.warn("[SSU] Not queueing possible data packet from " + packet + " -> Too many packets already queued");
                return;
            }
            if (_log.shouldInfo())
                _log.info("[SSU] Queueing possible data packet on " + this);
            // have to copy it because PacketHandler will release
            DatagramPacket pkt = packet.getPacket();
            UDPPacket packet2 = UDPPacket.acquire(_context, true);
            DatagramPacket pkt2 = packet2.getPacket();
            System.arraycopy(pkt.getData(), pkt.getOffset(), pkt2.getData(), pkt2.getOffset(), pkt.getLength());
            pkt2.setLength(pkt.getLength());
            pkt2.setSocketAddress(pkt.getSocketAddress());
            _queuedDataPackets.add(packet2);
        } else {
            // case 2, race, decrypt header and pass over
            if (_log.shouldInfo())
                _log.info("[SSU] Passing possible data packet to PeerState2: " + this);
            _pstate.receivePacket(packet);
        }
    }

    /**
     * Determines whether the given Throwable (or any of its causes)
     * contains a message that should be suppressed from logging.
     *
     * @param t the Throwable to check
     * @return true if the exception should be suppressed, false otherwise
     * @since 0.9.68+
     */
    private boolean shouldSuppressException(Throwable t) {
        Set<String> suppressionPatterns = new HashSet<>(Arrays.asList(
            "Old and slow",
            "RouterInfo store fail"
        ));

        while (t != null) {
            String message = t.getMessage();
            if (message != null) {
                for (String pattern : suppressionPatterns) {
                    if (message.contains(pattern)) {
                        return true;
                    }
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * A string summary of this state.
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("InboundEstablishState ");
        buf.append(Addresses.toString(_aliceIP, _alicePort));
        if (_log.shouldInfo()) {
            buf.append("\n* Lifetime: ").append(DataHelper.formatDuration(getLifetime()));
            buf.append("; Receive ID: ").append(_rcvConnID);
            buf.append("; Send ID: ").append(_sendConnID);
            buf.append("; Token: ").append(_token);
            if (_sentRelayTag > 0)
                buf.append("; RelayTag: ").append(_sentRelayTag);
            buf.append(' ').append(_currentState);
        }
        return buf.toString();
    }

    /**
     *  For throwing out of gotRI()
     *  @since 0.9.57
     */
    static class RIException extends DataFormatException {
        private final int rsn;
        /**
         * Creates the RI exception.
         */
        public RIException(String msg, int reason) {
            super(msg);
            rsn = reason;
        }
        /**
         * Creates the RI exception.
         */
        public RIException(String msg, int reason, Throwable t) {
            super(msg, t);
            rsn = reason;
        }
        /**
         * The reason code.
         * @return the reason
         */
        public int getReason() {return rsn;}
        /**
         * The message with the reason code prefixed.
         * @return the message
         */
        @Override
        public String getMessage() {return "Code " + rsn + ": " + super.getMessage();}
    }

}
