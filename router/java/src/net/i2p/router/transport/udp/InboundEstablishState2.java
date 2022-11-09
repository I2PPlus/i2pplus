package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

import com.southernstorm.noise.protocol.ChaChaPolyCipherState;
import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.crypto.HKDF;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.TransportImpl;
import static net.i2p.router.transport.udp.SSU2Util.*;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

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
    private final UDPTransport _transport;
    private final InetSocketAddress _aliceSocketAddress;
    private final long _rcvConnID;
    private final long _sendConnID;
    private final long _token;
    private final HandshakeState _handshakeState;
    private byte[] _sendHeaderEncryptKey1;
    private final byte[] _rcvHeaderEncryptKey1;
    private byte[] _sendHeaderEncryptKey2;
    private byte[] _rcvHeaderEncryptKey2;
    private byte[] _sessCrForReTX;
    private byte[][] _sessConfFragments;
    private long _timeReceived;
    // not adjusted for RTT
    private long _skew;
    private int _mtu;
    private PeerState2 _pstate;
    private List<UDPPacket> _queuedDataPackets;

    // testing
    private static final boolean ENFORCE_TOKEN = true;
    private static final long MAX_SKEW = 2*60*1000L;


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
        DatagramPacket pkt = packet.getPacket();
        _aliceSocketAddress = (InetSocketAddress) pkt.getSocketAddress();
        _handshakeState = new HandshakeState(HandshakeState.PATTERN_ID_XK_SSU2, HandshakeState.RESPONDER, transport.getXDHFactory());
        _handshakeState.getLocalKeyPair().setKeys(transport.getSSU2StaticPrivKey(), 0,
                                                  transport.getSSU2StaticPubKey(), 0);
        byte[] introKey = transport.getSSU2StaticIntroKey();
        _sendHeaderEncryptKey1 = introKey;
        _rcvHeaderEncryptKey1 = introKey;
        //_sendHeaderEncryptKey2 set below
        //_rcvHeaderEncryptKey2 set below
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte data[] = pkt.getData();
        _rcvConnID = DataHelper.fromLong8(data, off);
        _sendConnID = DataHelper.fromLong8(data, off + SRC_CONN_ID_OFFSET);
        if (_rcvConnID == _sendConnID)
            throw new GeneralSecurityException("Identical Connection IDs");
        int type = data[off + TYPE_OFFSET] & 0xff;
        long token = DataHelper.fromLong8(data, off + TOKEN_OFFSET);
        if (type == TOKEN_REQUEST_FLAG_BYTE) {
            if (_log.shouldDebug())
                _log.debug("[SSU2] Received TokenRequest from: " + _aliceSocketAddress);
            _currentState = InboundState.IB_STATE_TOKEN_REQUEST_RECEIVED;
            // decrypt in-place
            ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
            chacha.initializeKey(_rcvHeaderEncryptKey1, 0);
            long n = DataHelper.fromLong(data, off + PKT_NUM_OFFSET, 4);
            chacha.setNonce(n);
            chacha.decryptWithAd(data, off, LONG_HEADER_SIZE,
                                 data, off + LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE);
            chacha.destroy();
            processPayload(data, off + LONG_HEADER_SIZE, len - (LONG_HEADER_SIZE + MAC_LEN), true);
            _sendHeaderEncryptKey2 = introKey;
            do {
                token = ctx.random().nextLong();
            } while (token == 0);
            _token = token;
        } else if (type == SESSION_REQUEST_FLAG_BYTE &&
                   (token == 0 ||
                    (ENFORCE_TOKEN && !_transport.getEstablisher().isInboundTokenValid(_remoteHostId, token)))) {
            if (_log.shouldInfo())
                _log.info("[SSU2] Invalid token [" + token + "] in SessionRequest from: " + _aliceSocketAddress);
            if (token == 0)
                throw new GeneralSecurityException("Zero token in session request from: " + _aliceSocketAddress);
            _currentState = InboundState.IB_STATE_REQUEST_BAD_TOKEN_RECEIVED;
            _sendHeaderEncryptKey2 = introKey;
            // Generate token for the retry.
            // We do NOT register it with the EstablishmentManager, it must be used immediately.
            do {
                token = ctx.random().nextLong();
            } while (token == 0);
            _token = token;
            // do NOT bother to init the handshake state and decrypt the payload
            _timeReceived = _establishBegin;
        } else {
            // fast MSB check for key < 2^255
            if ((data[off + LONG_HEADER_SIZE + KEY_LEN - 1] & 0x80) != 0)
                throw new GeneralSecurityException("Bad PK msg 1");
            // probably don't need again
            _token = token;
            _handshakeState.start();
            //if (_log.shouldDebug())
            //    _log.debug("[SSU2] State after start: " + _handshakeState);
            _handshakeState.mixHash(data, off, LONG_HEADER_SIZE);
            //if (_log.shouldDebug())
            //    _log.debug("[SSU2] State after mixHash 1: " + _handshakeState);

            // decrypt in-place
            try {
                _handshakeState.readMessage(data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE);
            } catch (GeneralSecurityException gse) {
                if (_log.shouldDebug())
                    _log.debug("[SSU2] Session request error -> State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
                throw gse;
            }
            //if (_log.shouldDebug())
            //    _log.debug("[SSU2] State after SessionRequest: " + _handshakeState);
            processPayload(data, off + LONG_HEADER_SIZE, len - (LONG_HEADER_SIZE + KEY_LEN + MAC_LEN), true);
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
            throw new GeneralSecurityException("Skew exceeded in Session/Token Request (retry sent): " + _skew + "ms");
        }
        packetReceived();
        if (_log.shouldDebug())
            _log.debug("[SSU2] New " + this);
    }

    @Override
    public int getVersion() { return 2; }

    private void processPayload(byte[] payload, int offset, int length, boolean isHandshake) throws GeneralSecurityException {
        try {
            int blocks = SSU2Payload.processPayload(_context, this, payload, offset, length, isHandshake, null);
            if (_log.shouldDebug())
                _log.debug("[SSU2] Processed " + blocks + " blocks on " + this);
        } catch (DataFormatException dfe) {
            // probably RI problems, ban for a while??
            //_context.blocklist().add(_aliceIP);
            if (_log.shouldWarn())
                _log.warn("[SSU2] IES2 payload error", dfe);
            throw new GeneralSecurityException("IES2 payload error: " + this, dfe);
        } catch (Exception e) {
            if (!e.toString().contains("RouterInfo store fail"))
                if (_log.shouldWarn())
                    _log.warn("[SSU2] InboundEstablishState Payload Error\n" + net.i2p.util.HexDump.dump(payload, 0, length), e);
            throw new GeneralSecurityException("IES2 payload error", e);
        }
    }

    /////////////////////////////////////////////////////////
    // begin payload callbacks
    /////////////////////////////////////////////////////////

    public void gotDateTime(long time) {
        _timeReceived = time;
    }

    public void gotOptions(byte[] options, boolean isHandshake) {
        if (_log.shouldDebug())
            _log.debug("[SSU2] Received OPTIONS block");
    }

    public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException {
        //if (_log.shouldDebug())
        //    _log.debug("[SSU2] Received RouterInfo block: " + ri);
        if (isHandshake)
            throw new DataFormatException("RouterInfo in SessionRequest");
        if (_receivedUnconfirmedIdentity != null)
            throw new DataFormatException("Duplicate RouterInfo in SessionConfirmed");
        _receivedUnconfirmedIdentity = ri.getIdentity();
        if (ri.getNetworkId() != _context.router().getNetworkID()) {
            // TODO ban
            throw new DataFormatException("SSU2 network ID mismatch");
        }

        // try to find the right address, because we need the MTU
        boolean isIPv6 = _aliceIP.length == 16;
        List<RouterAddress> addrs = _transport.getTargetAddresses(ri);
        RouterAddress ra = null;
        for (RouterAddress addr : addrs) {
            // skip SSU 1 address w/o "s"
            if (addrs.size() > 1 && addr.getTransportStyle().equals("SSU") && addr.getOption("s") == null)
                continue;
            String host = addr.getHost();
            if (host == null)
                host = "";
            String caps = addr.getOption(UDPAddress.PROP_CAPACITY);
            if (caps == null)
                caps = "";
            if (isIPv6) {
                if (!host.contains(":") && !caps.contains(TransportImpl.CAP_IPV6))
                    continue;
            } else {
                if (!host.contains(".") && !caps.contains(TransportImpl.CAP_IPV4))
                    continue;
            }
            ra = addr;
            break;
        }

        if (ra == null)
            throw new DataFormatException("no SSU2 addr, ipv6? " + isIPv6 + ": " + ri);
        String siv = ra.getOption("i");
        if (siv == null)
            throw new DataFormatException("no SSU2 IKey");
        byte[] ik = Base64.decode(siv);
        if (ik == null)
            throw new DataFormatException("bad SSU2 IKey");
        if (ik.length != 32)
            throw new DataFormatException("bad SSU2 IKey len");
        String ss = ra.getOption("s");
        if (ss == null)
            throw new DataFormatException("no SSU2 S");
        byte[] s = Base64.decode(ss);
        if (s == null)
            throw new DataFormatException("bad SSU2 S");
        if (s.length != 32)
            throw new DataFormatException("bad SSU2 S len");
        byte[] nb = new byte[32];
        // compare to the _handshakeState
        _handshakeState.getRemotePublicKey().getPublicKey(nb, 0);
        if (!DataHelper.eqCT(s, 0, nb, 0, KEY_LEN))
            throw new DataFormatException("s mismatch in RI: " + ri);

        if (!"2".equals(ra.getOption("v")))
            throw new DataFormatException("bad SSU2 v");

        String smtu = ra.getOption(UDPAddress.PROP_MTU);
        int mtu = 0;
        try {
            mtu = Integer.parseInt(smtu);
        } catch (NumberFormatException nfe) {}
        if (mtu == 0) {
            if (ra.getTransportStyle().equals(UDPTransport.STYLE2)) {
                mtu = PeerState2.DEFAULT_MTU;
            } else {
                if (isIPv6)
                    mtu = PeerState2.DEFAULT_SSU_IPV6_MTU;
                else
                    mtu = PeerState2.DEFAULT_SSU_IPV4_MTU;
            }
        } else if (mtu == 1276 && ra.getTransportStyle().equals("SSU")) {
            // workaround for bug in 1.9.0
            mtu = PeerState2.MIN_MTU;
        } else {
            // if too small, give up now
            if (mtu < PeerState2.MIN_MTU)
                throw new DataFormatException("MTU too small " + mtu);
            if (ra.getTransportStyle().equals(UDPTransport.STYLE2)) {
                mtu = Math.min(Math.max(mtu, PeerState2.MIN_MTU), PeerState2.MAX_MTU);
            } else {
                if (isIPv6)
                    mtu = Math.min(Math.max(mtu, PeerState2.MIN_SSU_IPV6_MTU), PeerState2.MAX_SSU_IPV6_MTU);
                else
                    mtu = Math.min(Math.max(mtu, PeerState2.MIN_SSU_IPV4_MTU), PeerState2.MAX_SSU_IPV4_MTU);
            }
        }
        _mtu = mtu;

        Hash h = _receivedUnconfirmedIdentity.calculateHash();
        try {
            RouterInfo old = _context.netDb().store(h, ri);
            if (flood && !ri.equals(old)) {
                FloodfillNetworkDatabaseFacade fndf = (FloodfillNetworkDatabaseFacade) _context.netDb();
                if (fndf.floodConditional(ri)) {
                    if (_log.shouldDebug())
                        _log.debug("[SSU2] Flooded the RouterInfo: " + h);
                } else {
                    if (_log.shouldInfo())
                        _log.info("[SSU2] Flood request but we didn't: " + h);
                }
            }
        } catch (IllegalArgumentException iae) {
            // generally expired/future RI
            // don't change reason if already set as clock skew
            throw new DataFormatException("RouterInfo store fail: " + ri, iae);
        }

        _receivedConfirmedIdentity = _receivedUnconfirmedIdentity;
        _sendHeaderEncryptKey1 = ik;
        createPeerState();
        //_sendHeaderEncryptKey2 calculated below
    }

    public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped, int frag, int totalFrags) {
        if (_log.shouldDebug())
            _log.debug("[SSU2] Received RouterInfo fragment [" + frag + " / " + totalFrags + "]");
        // not supported, we fragment the whole message now
        throw new IllegalStateException("fragmented RouterInfo");
    }

    public void gotAddress(byte[] ip, int port) {
        if (_log.shouldDebug())
            _log.debug("[SSU2] Received Address: " + Addresses.toString(ip, port));
        _bobIP = ip;
        // final, see super
        //_bobPort = port;
    }

    public void gotRelayTagRequest() {
        if (!ENABLE_RELAY)
            return;
        if (_log.shouldDebug())
            _log.debug("[SSU2] Received RelayTagRequest");
        _introductionRequested = true;
    }

    public void gotRelayTag(long tag) {
        // shouldn't happen for inbound
    }

    public void gotRelayRequest(byte[] data) {
        if (!ENABLE_RELAY)
            return;
        if (_receivedConfirmedIdentity == null)
            throw new IllegalStateException("RouterInfo must be sent first");
    }

    public void gotRelayResponse(int status, byte[] data) {
        if (!ENABLE_RELAY)
            return;
        if (_receivedConfirmedIdentity == null)
            throw new IllegalStateException("RouterInfo must be sent first");
    }

    public void gotRelayIntro(Hash aliceHash, byte[] data) {
        if (!ENABLE_RELAY)
            return;
        if (_receivedConfirmedIdentity == null)
            throw new IllegalStateException("RouterInfo must be sent first");
    }

    public void gotPeerTest(int msg, int status, Hash h, byte[] data) {
        if (!ENABLE_PEER_TEST)
            return;
        if (_receivedConfirmedIdentity == null)
            throw new IllegalStateException("RouterInfo must be sent first");
        _transport.getPeerTestManager().receiveTest(_remoteHostId, _pstate, msg, status, h, data);
    }

    public void gotToken(long token, long expires) {
        if (_log.shouldDebug())
            _log.debug("[SSU2] Received token: " + token + " expires " + DataHelper.formatTime(expires) + " on " + this);
        if (_receivedConfirmedIdentity == null)
            throw new IllegalStateException("RouterInfo must be sent first");
        _transport.getEstablisher().addOutboundToken(_remoteHostId, token, expires);
    }

    public void gotI2NP(I2NPMessage msg) {
        if (_log.shouldDebug())
            _log.debug("[SSU2] Received I2NP block: " + msg);
        if (getState() != InboundState.IB_STATE_CREATED_SENT)
            throw new IllegalStateException("I2NP in SessionRequest");
        if (_receivedConfirmedIdentity == null)
            throw new IllegalStateException("RouterInfo must be sent first");
        // pass to PeerState2
        _pstate.gotI2NP(msg);
    }

    public void gotFragment(byte[] data, int off, int len, long messageID, int frag, boolean isLast) throws DataFormatException {
        if (_log.shouldDebug())
            _log.debug("[SSU2] Received FRAGMENT block: " + messageID);
        if (getState() != InboundState.IB_STATE_CREATED_SENT)
            throw new IllegalStateException("I2NP in SessionRequest");
        if (_receivedConfirmedIdentity == null)
            throw new IllegalStateException("RouterInfo must be sent first");
        // pass to PeerState2
        _pstate.gotFragment(data, off, len, messageID, frag, isLast);
    }

    public void gotACK(long ackThru, int acks, byte[] ranges) {
        throw new IllegalStateException("ACK in Handshake");
    }

    public void gotTermination(int reason, long count) {
        if (_log.shouldInfo())
            _log.info("[SSU2] Received TERMINATION block -> Reason: " + reason + "; Count: " + count);
        // this sets the state to FAILED
        fail();
        _transport.getEstablisher().receiveSessionDestroy(_remoteHostId);
    }

    public void gotPathChallenge(RemoteHostId from, byte[] data) {
        throw new IllegalStateException("Bad block in handshake");
    }

    public void gotPathResponse(RemoteHostId from, byte[] data) {
        throw new IllegalStateException("Bad block in handshake");
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
        super.fail();
    }

    // SSU 1 unsupported things

    @Override
    public void generateSessionKey() { throw new UnsupportedOperationException(); }

    // SSU 2 things

    public long getSendConnID() { return _sendConnID; }
    public long getRcvConnID() { return _rcvConnID; }
    public long getToken() { return _token; }
    public EstablishmentManager.Token getNextToken() {
        return _transport.getEstablisher().getInboundToken(_remoteHostId);
    }
    public HandshakeState getHandshakeState() { return _handshakeState; }
    public byte[] getSendHeaderEncryptKey1() { return _sendHeaderEncryptKey1; }
    public byte[] getRcvHeaderEncryptKey1() { return _rcvHeaderEncryptKey1; }
    public byte[] getSendHeaderEncryptKey2() { return _sendHeaderEncryptKey2; }
    public synchronized byte[] getRcvHeaderEncryptKey2() { return _rcvHeaderEncryptKey2; }
    public InetSocketAddress getSentAddress() { return _aliceSocketAddress; }

    @Override
    public synchronized void createdPacketSent() {
        /// todo state check
        if (_rcvHeaderEncryptKey2 == null)
            _rcvHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessionConfirmed");
        _lastSend = _context.clock().now();
        long delay;
        if (_createdSentCount == 0) {
            delay = RETRANSMIT_DELAY;
        } else {
            delay = Math.min(RETRANSMIT_DELAY << _createdSentCount, MAX_DELAY);
        }
        _createdSentCount++;
        _nextSend = _lastSend + delay;
        _currentState = InboundState.IB_STATE_CREATED_SENT;
    }


    /** note that we just sent a Retry packet */
    public synchronized void retryPacketSent() {
        // retry after clock skew
        if (_currentState == InboundState.IB_STATE_FAILED ||
            _currentState == InboundState.IB_STATE_RETRY_SENT)
            return;
        if (_currentState != InboundState.IB_STATE_REQUEST_BAD_TOKEN_RECEIVED &&
            _currentState != InboundState.IB_STATE_TOKEN_REQUEST_RECEIVED)
            throw new IllegalStateException("Bad state for Retry Sent: " + _currentState);
        _currentState = InboundState.IB_STATE_RETRY_SENT;
        _lastSend = _context.clock().now();
        // Won't really be retransmitted, they have 9 sec to respond or
        // EstablishmentManager.handleInbound() will fail the connection
        _nextSend = _lastSend + (3 * RETRANSMIT_DELAY);
    }

    /**
     *  All exceptions thrown from here will be fatal. fail() will be called before throwing.
     */
    public synchronized void receiveSessionRequestAfterRetry(UDPPacket packet) throws GeneralSecurityException {
        try {
            locked_receiveSessionRequestAfterRetry(packet);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("[SSU2] Session request error after retry", gse);
            // fail inside synch rather than have Est. Mgr. do it to prevent races
            fail();
            throw gse;
        }
    }

    /**
     * @since 0.9.56
     */
    private void locked_receiveSessionRequestAfterRetry(UDPPacket packet) throws GeneralSecurityException {
        if (_currentState != InboundState.IB_STATE_RETRY_SENT)
            throw new GeneralSecurityException("Bad state for SessionRequest after Retry: " + _currentState);
        if (_log.shouldDebug())
            _log.debug("[SSU2] Received SessionRequest after retry from: " + _aliceSocketAddress);
        DatagramPacket pkt = packet.getPacket();
        SocketAddress from = pkt.getSocketAddress();
        if (!from.equals(_aliceSocketAddress))
            throw new GeneralSecurityException("Address mismatch: req: " + _aliceSocketAddress + " conf: " + from);
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte data[] = pkt.getData();
        long rid = DataHelper.fromLong8(data, off);
        if (rid != _rcvConnID)
            throw new GeneralSecurityException("Connection ID mismatch: 1: " + _rcvConnID + " 2: " + rid);
        long sid = DataHelper.fromLong8(data, off + 16);
        if (sid != _sendConnID)
            throw new GeneralSecurityException("Connection ID mismatch: 1: " + _sendConnID + " 2: " + sid);
        long token = DataHelper.fromLong8(data, off + 24);
        if (token != _token) {
            // most likely a retransmitted session request with the old invalid token
            // TODO should we retransmit retry in this case?
            throw new GeneralSecurityException("Token mismatch: expected: " + _token + " got: " + token);
        }
        _handshakeState.start();
        _handshakeState.mixHash(data, off, 32);
        //if (_log.shouldDebug())
        //    _log.debug("[SSU2] State after mixHash 1: " + _handshakeState);

        // decrypt in-place
        try {
            _handshakeState.readMessage(data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("[SSU2] SessionRequest error -> State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
            throw gse;
        }
        //if (_log.shouldDebug())
        //    _log.debug("[SSU2] State after SessionRequest: " + _handshakeState);
        _timeReceived = 0;
        processPayload(data, off + LONG_HEADER_SIZE, len - (LONG_HEADER_SIZE + KEY_LEN + MAC_LEN), true);
        packetReceived();
        if (_currentState == InboundState.IB_STATE_FAILED) {
            // termination block received
            throw new GeneralSecurityException("Termination block in SessionRequest");
        }
        if (_timeReceived == 0)
            throw new GeneralSecurityException("No DateTime block in SessionRequest");
        // _nextSend is now(), from packetReceived()
        _skew = _nextSend - _timeReceived;
        if (_skew > MAX_SKEW || _skew < 0 - MAX_SKEW) {
            // send another retry with termination
            UDPPacket retry = _transport.getBuilder2().buildRetryPacket(this, SSU2Util.REASON_SKEW);
            _transport.send(retry);
            throw new GeneralSecurityException("Skew exceeded in Session Request: " + _skew);
        }
        _sendHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessCreateHeader");
        _currentState = InboundState.IB_STATE_REQUEST_RECEIVED;
        _rtt = (int) (_nextSend - _lastSend);
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
    public synchronized PeerState2 receiveSessionConfirmed(UDPPacket packet) throws GeneralSecurityException {
        try {
            return locked_receiveSessionConfirmed(packet);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("Session confirmed error", gse);
            // fail inside synch rather than have Est. Mgr. do it to prevent races
            fail();
            throw gse;
        }
    }

    /**
     *  @since 0.9.56
     */
    private PeerState2 locked_receiveSessionConfirmed(UDPPacket packet) throws GeneralSecurityException {
        if (_currentState != InboundState.IB_STATE_CREATED_SENT &&
            _currentState != InboundState.IB_STATE_CONFIRMED_PARTIALLY)
            throw new GeneralSecurityException("Bad state for Session Confirmed: " + _currentState);
        DatagramPacket pkt = packet.getPacket();
        SocketAddress from = pkt.getSocketAddress();
        if (!from.equals(_aliceSocketAddress))
            throw new GeneralSecurityException("Address mismatch: req: " + _aliceSocketAddress + " conf: " + from);
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte data[] = pkt.getData();
        long rid = DataHelper.fromLong8(data, off);
        if (rid != _rcvConnID)
            throw new GeneralSecurityException("Conn ID mismatch: req: " + _rcvConnID + " conf: " + rid);
        byte fragbyte = data[off + SHORT_HEADER_FLAGS_OFFSET];
        int frag = (fragbyte >> 4) & 0x0f;
        // allow both 0/0 (development) and 0/1 to indicate sole fragment
        int totalfrag = fragbyte & 0x0f;
        if (totalfrag > 0 && frag > totalfrag - 1)
            throw new GeneralSecurityException("[SSU2] Bad SessionConfirmed fragment [" + frag + " / " + totalfrag + "]");
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
                _nextSend = _lastSend + 60*1000;
            } else {
                if (_sessConfFragments.length != totalfrag) // total frag changed
                    throw new GeneralSecurityException("[SSU2] Bad SessionConfirmed fragment [" + frag + " / " + totalfrag + "]");
                if (_sessConfFragments[frag] != null) {
                    if (_log.shouldInfo())
                        _log.info("[SSU2] Received duplicate SessionConfirmed fragment [" + frag + "] on " + this);
                    // there is no facility to ack individual fragments
                    //packetReceived();
                    return null;
                }
            }
            if (_log.shouldInfo())
                _log.info("[SSU2] Received " + len + " bytes SessionConfirmed fragment [" + frag + '/' + totalfrag + "] on " + this);
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
                        _log.info("[SSU2] Still missing at least one SessionConfirmed fragment on " + this);
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
                _log.info("[SSU2] Have all " + totalfrag + " SessionConfirmed fragments (total length: " + len + " bytes) on " + this);
        }
        _handshakeState.mixHash(data, off, SHORT_HEADER_SIZE);
        //if (_log.shouldDebug())
        //    _log.debug("[SSU2] State after mixHash 3: " + _handshakeState);

        // decrypt in-place
        try {
            _handshakeState.readMessage(data, off + SHORT_HEADER_SIZE, len - SHORT_HEADER_SIZE, data, off + SHORT_HEADER_SIZE);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("[SSU2] SessionConfirmed error -> State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
            throw gse;
        }
        //if (_log.shouldDebug())
        //    _log.debug("[SSU2] State after SessionConfirmed: " + _handshakeState);
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
      /****
        if (_log.shouldDebug())
            _log.debug("[SSU2] split()\nGenerated Chain key:              " + Base64.encode(ckd) +
                       "\nGenerated split key for A->B:     " + Base64.encode(k_ab) +
                       "\nGenerated split key for B->A:     " + Base64.encode(k_ba) +
                       "\nGenerated encrypt key for A->B:   " + Base64.encode(d_ab) +
                       "\nGenerated encrypt key for B->A:   " + Base64.encode(d_ba) +
                       "\nIntro key for Alice:              " + Base64.encode(_sendHeaderEncryptKey1) +
                       "\nIntro key for Bob:                " + Base64.encode(_rcvHeaderEncryptKey1) +
                       "\nGenerated header key 2 for A->B:  " + Base64.encode(h_ab) +
                       "\nGenerated header key 2 for B->A:  " + Base64.encode(h_ba));
       ****/
        _handshakeState.destroy();
        if (_createdSentCount == 1)
            _rtt = (int) ( _context.clock().now() - _lastSend );
        _pstate = new PeerState2(_context, _transport, _aliceSocketAddress,
                                 _receivedConfirmedIdentity.calculateHash(),
                                 true, _rtt, sender, rcvr,
                                 _sendConnID, _rcvConnID,
                                 _sendHeaderEncryptKey1, h_ba, h_ab);
        // PS2.super adds CLOCK_SKEW_FUDGE that doesn't apply here
        _pstate.adjustClockSkew(_skew - (_rtt / 2) - PeerState.CLOCK_SKEW_FUDGE);
        _pstate.setHisMTU(_mtu);
        // set our address. _bobIP and _bobPort in super are not set for SSU2
        boolean isIPv6 = _aliceIP.length == 16;
        RouterAddress ra = _transport.getCurrentExternalAddress(isIPv6);
        if (ra != null)
            _pstate.setOurAddress(ra.getIP(), ra.getPort());
    }

    /**
     * note that we just sent the SessionCreated packet
     * and save it for retransmission
     */
    public synchronized void createdPacketSent(DatagramPacket pkt) {
        if (_sessCrForReTX == null) {
            // store pkt for retx
            byte data[] = pkt.getData();
            int off = pkt.getOffset();
            int len = pkt.getLength();
            _sessCrForReTX = new byte[len];
            System.arraycopy(data, off, _sessCrForReTX, 0, len);
        }
        createdPacketSent();
    }

    /**
     * @return null if not sent or already got the session confirmed
     */
    public synchronized UDPPacket getRetransmitSessionCreatedPacket() {
        if (_sessCrForReTX == null)
            return null;
        if (_log.shouldInfo())
            _log.info("Retransmit Session created on " + this);
        UDPPacket packet = UDPPacket.acquire(_context, false);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
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
     * @return null if we have not received the session confirmed
     */
    public synchronized PeerState2 getPeerState() {
        if (_pstate != null) {
            _currentState = InboundState.IB_STATE_COMPLETE;
            if (_queuedDataPackets != null) {
                for (UDPPacket packet : _queuedDataPackets) {
                    if (_log.shouldInfo())
                        _log.info("[SSU2] Passing possible data " + packet + " to PeerState2: " + this);
                    _pstate.receivePacket(packet);
                    packet.release();
                }
                _queuedDataPackets.clear();
            }
        }
        return _pstate;
    }

    /**
     * @param packet with header still encrypted
     */
    public synchronized void queuePossibleDataPacket(UDPPacket packet) {
        if (_pstate == null) {
            // case 1, race or out-of-order, queue until we have the peerstate
            if (_queuedDataPackets == null) {
                _queuedDataPackets = new ArrayList<UDPPacket>(4);
            } else if (_queuedDataPackets.size() >= 10) {
                if (_log.shouldWarn())
                    _log.warn("[SSU2] Not queueing possible data " + packet + ", too many queued on " + this);
                return;
            }
            if (_log.shouldInfo())
                _log.info("[SSU2] Queueing possible data " + packet + " on " + this);
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
                _log.info("[SSU2] Passing possible data " + packet + " to PeerState2: " + this);
            _pstate.receivePacket(packet);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("IES2 ");
        buf.append(Addresses.toString(_aliceIP, _alicePort));
        buf.append("\n* Lifetime: ").append(DataHelper.formatDuration(getLifetime()));
        buf.append("; Rcv ID: ").append(_rcvConnID);
        buf.append("; Send ID: ").append(_sendConnID);
        if (_sentRelayTag > 0)
            buf.append("; RelayTag: ").append(_sentRelayTag);
        buf.append(' ').append(_currentState);
        return buf.toString();
    }
}
