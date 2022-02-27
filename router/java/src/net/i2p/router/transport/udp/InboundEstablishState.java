package net.i2p.router.transport.udp;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

/**
 * Data for a new connection being established, where the remote peer has
 * initiated the connection with us.  In other words, they are Alice and
 * we are Bob.
 *
 * TODO do all these methods need to be synchronized?
 */
class InboundEstablishState {
    protected final RouterContext _context;
    protected final Log _log;
    // SessionRequest message
    private byte _receivedX[];
    private byte _bobIP[];
    private final int _bobPort;
    private final DHSessionKeyBuilder _keyBuilder;
    // SessionCreated message
    private byte _sentY[];
    protected final byte _aliceIP[];
    protected final int _alicePort;
    protected long _sentRelayTag;
    private long _sentSignedOnTime;
    private SessionKey _sessionKey;
    private SessionKey _macKey;
    private Signature _sentSignature;
    // SessionConfirmed messages - fragmented in theory but not in practice - see below
    private byte _receivedIdentity[][];
    private long _receivedSignedOnTime;
    private byte _receivedSignature[];
    private boolean _verificationAttempted;
    // sig not verified
    protected RouterIdentity _receivedUnconfirmedIdentity;
    // identical to uncomfirmed, but sig now verified
    protected RouterIdentity _receivedConfirmedIdentity;
    // general status
    private final long _establishBegin;
    //private long _lastReceive;
    protected long _lastSend;
    protected long _nextSend;
    protected final RemoteHostId _remoteHostId;
    protected InboundState _currentState;
    private final Queue<OutNetMessage> _queuedMessages;
    // count for backoff
    protected int _createdSentCount;
    // default true
    protected boolean _introductionRequested = true;

    protected int _rtt;

    public enum InboundState {
        /** nothin known yet */
        IB_STATE_UNKNOWN,
        /** we have received an initial request */
        IB_STATE_REQUEST_RECEIVED,
        /** we have sent a signed creation packet */
        IB_STATE_CREATED_SENT,
        /** we have received one but not all the confirmation packets
          * This never happens in practice - see below. */
        IB_STATE_CONFIRMED_PARTIALLY,
        /** we have all the confirmation packets */
        IB_STATE_CONFIRMED_COMPLETELY,
        /** we are explicitly failing it */
        IB_STATE_FAILED,
        /** Successful completion, PeerState created and added to transport */
        IB_STATE_COMPLETE,

        /**
         * SSU2: We have received a token request
         * @since 0.9.54
         */
        IB_STATE_TOKEN_REQUEST_RECEIVED,
        /**
         * SSU2: We have received a request but the token is bad
         * @since 0.9.54
         */
        IB_STATE_REQUEST_BAD_TOKEN_RECEIVED,
        /**
         * SSU2: We have sent a retry
         * @since 0.9.54
         */
        IB_STATE_RETRY_SENT,
    }

    /** basic delay before backoff
     *  Transmissions at 0, 3, 9 sec
     *  Previously: 1500 (0, 1.5, 4.5, 10.5)
     */
    protected static final long RETRANSMIT_DELAY = 3000;

    /** max delay including backoff */
    protected static final long MAX_DELAY = 15*1000;

    /**
     *  @param localPort Must be our external port, otherwise the signature of the
     *                   SessionCreated message will be bad if the external port != the internal port.
     */
    public InboundEstablishState(RouterContext ctx, byte remoteIP[], int remotePort, int localPort,
                                 DHSessionKeyBuilder dh, UDPPacketReader.SessionRequestReader req) {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundEstablishState.class);
        _aliceIP = remoteIP;
        _alicePort = remotePort;
        _remoteHostId = new RemoteHostId(_aliceIP, _alicePort);
        _bobPort = localPort;
        _currentState = InboundState.IB_STATE_UNKNOWN;
        _establishBegin = ctx.clock().now();
        _keyBuilder = dh;
        _queuedMessages = new LinkedBlockingQueue<OutNetMessage>();
        receiveSessionRequest(req);
    }

    /**
     *  For SSU2
     *
     *  @since 0.9.54
     */
    protected InboundEstablishState(RouterContext ctx, InetSocketAddress addr) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _aliceIP = addr.getAddress().getAddress();
        _alicePort = addr.getPort();
        _remoteHostId = new RemoteHostId(_aliceIP, _alicePort);
        _bobPort = 0;
        _currentState = InboundState.IB_STATE_UNKNOWN;
        _establishBegin = ctx.clock().now();
        _keyBuilder = null;
        _queuedMessages = new LinkedBlockingQueue<OutNetMessage>();
    }
    
    /**
     * @since 0.9.54
     */
    public int getVersion() { return 1; }

    public synchronized InboundState getState() { return _currentState; }

    /** @return if previously complete */
    public synchronized boolean isComplete() {
        return _currentState == InboundState.IB_STATE_COMPLETE ||
               _currentState == InboundState.IB_STATE_FAILED;
    }

    /** Notify successful completion */
    public synchronized void complete() {
        _currentState = InboundState.IB_STATE_COMPLETE;
    }

    /**
     *  Queue a message to be sent after the session is established.
     *  This will only happen if we decide to send something during establishment
     *  @since 0.9.2
     */
    public void addMessage(OutNetMessage msg) {
        // chance of a duplicate here in a race, that's ok
        if (!_queuedMessages.contains(msg))
            _queuedMessages.offer(msg);
        else if (_log.shouldLog(Log.WARN))
             _log.warn("attempt to add duplicate msg to queue: " + msg);
    }

    /**
     *  Pull from the message queue
     *  @return null if none
     *  @since 0.9.2
     */
    public OutNetMessage getNextQueuedMessage() {
        return _queuedMessages.poll();
    }

    public synchronized void receiveSessionRequest(UDPPacketReader.SessionRequestReader req) {
        if (_receivedX == null)
            _receivedX = new byte[UDPPacketReader.SessionRequestReader.X_LENGTH];
        req.readX(_receivedX, 0);
        if (_bobIP == null)
            _bobIP = new byte[req.readIPSize()];
        req.readIP(_bobIP, 0);
        byte[] ext = req.readExtendedOptions();
        if (ext != null && ext.length >= UDPPacket.SESS_REQ_MIN_EXT_OPTIONS_LENGTH) {
            _introductionRequested = (ext[1] & (byte) UDPPacket.SESS_REQ_EXT_FLAG_REQUEST_RELAY_TAG) != 0;
            if (_log.shouldInfo())
                _log.info("Received SessionRequest with extended options; need intro? " + _introductionRequested + ' ' + this);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Received SessionRequest, BobIP = " + Addresses.toString(_bobIP));
        if (_currentState == InboundState.IB_STATE_UNKNOWN)
            _currentState = InboundState.IB_STATE_REQUEST_RECEIVED;
        packetReceived();
    }

    public synchronized boolean sessionRequestReceived() { return _receivedX != null; }
    public synchronized byte[] getReceivedX() { return _receivedX; }
    public synchronized byte[] getReceivedOurIP() { return _bobIP; }
    /**
     *  True (default) if no extended options in session request,
     *  or value of flag bit in the extended options.
     *  @since 0.9.24
     */
    public synchronized boolean isIntroductionRequested() { return _introductionRequested; }

    /**
     *  Generates session key and mac key.
     */
    public synchronized void generateSessionKey() throws DHSessionKeyBuilder.InvalidPublicParameterException {
        if (_sessionKey != null) return;
        try {
            _keyBuilder.setPeerPublicValue(_receivedX);
        } catch (IllegalStateException ise) {
            throw new DHSessionKeyBuilder.InvalidPublicParameterException("reused keys?", ise);
        }
        _sessionKey = _keyBuilder.getSessionKey();
        ByteArray extra = _keyBuilder.getExtraBytes();
        _macKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
        System.arraycopy(extra.getData(), 0, _macKey.getData(), 0, SessionKey.KEYSIZE_BYTES);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Established inbound keys. Cipher: " + Base64.encode(_sessionKey.getData())
                       + " mac: " + Base64.encode(_macKey.getData()));
    }

    public synchronized SessionKey getCipherKey() { return _sessionKey; }
    public synchronized SessionKey getMACKey() { return _macKey; }

    /** what IP do they appear to be on? */
    public byte[] getSentIP() { return _aliceIP; }

    /** what port number do they appear to be coming from? */
    public int getSentPort() { return _alicePort; }

    public synchronized byte[] getSentY() {
        if (_sentY == null)
            _sentY = _keyBuilder.getMyPublicValueBytes();
        return _sentY;
    }

    public synchronized void fail() {
        _currentState = InboundState.IB_STATE_FAILED;
    }

    public synchronized long getSentRelayTag() { return _sentRelayTag; }
    public synchronized void setSentRelayTag(long tag) { _sentRelayTag = tag; }
    public synchronized long getSentSignedOnTime() { return _sentSignedOnTime; }

    public synchronized void prepareSessionCreated() {
        if (_sentSignature == null) signSessionCreated();
    }

    public synchronized Signature getSentSignature() { return _sentSignature; }

    /**
     * Sign: Alice's IP + Alice's port + Bob's IP + Bob's port + Alice's
     *       new relay tag + Bob's signed on time
     */
    private void signSessionCreated() {
        byte signed[] = new byte[256 + 256 // X + Y
                                 + _aliceIP.length + 2
                                 + _bobIP.length + 2
                                 + 4 // sent relay tag
                                 + 4 // signed on time
                                 ];
        _sentSignedOnTime = _context.clock().now() / 1000;

        int off = 0;
        System.arraycopy(_receivedX, 0, signed, off, _receivedX.length);
        off += _receivedX.length;
        getSentY();
        System.arraycopy(_sentY, 0, signed, off, _sentY.length);
        off += _sentY.length;
        System.arraycopy(_aliceIP, 0, signed, off, _aliceIP.length);
        off += _aliceIP.length;
        DataHelper.toLong(signed, off, 2, _alicePort);
        off += 2;
        System.arraycopy(_bobIP, 0, signed, off, _bobIP.length);
        off += _bobIP.length;
        DataHelper.toLong(signed, off, 2, _bobPort);
        off += 2;
        DataHelper.toLong(signed, off, 4, _sentRelayTag);
        off += 4;
        DataHelper.toLong(signed, off, 4, _sentSignedOnTime);

        _sentSignature = _context.dsa().sign(signed, _context.keyManager().getSigningPrivateKey());

        if (_log.shouldLog(Log.DEBUG)) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("Signing sessionCreated:");
            //buf.append(" ReceivedX: ").append(Base64.encode(_receivedX));
            //buf.append(" SentY: ").append(Base64.encode(_sentY));
            buf.append(" Alice: ").append(Addresses.toString(_aliceIP, _alicePort));
            buf.append(" Bob: ").append(Addresses.toString(_bobIP, _bobPort));
            buf.append(" RelayTag: ").append(_sentRelayTag);
            buf.append(" SignedOn: ").append(_sentSignedOnTime);
            buf.append(" signature: ").append(Base64.encode(_sentSignature.getData()));
            _log.debug(buf.toString());
        }
    }

    /** note that we just sent a SessionCreated packet */
    public synchronized void createdPacketSent() {
        _lastSend = _context.clock().now();
        long delay;
        if (_createdSentCount == 0) {
            delay = RETRANSMIT_DELAY;
        } else {
            delay = Math.min(RETRANSMIT_DELAY << _createdSentCount, MAX_DELAY);
        }
        _createdSentCount++;
        _nextSend = _lastSend + delay;
        if ( (_currentState == InboundState.IB_STATE_UNKNOWN) || (_currentState == InboundState.IB_STATE_REQUEST_RECEIVED) )
            _currentState = InboundState.IB_STATE_CREATED_SENT;
    }

    /** how long have we been trying to establish this session? */
    public long getLifetime() { return _context.clock().now() - _establishBegin; }
    public long getEstablishBeginTime() { return _establishBegin; }

    /**
     *  @return rcv time after receiving a packet (including after constructor),
     *          send time + delay after sending a packet
     */
    public synchronized long getNextSendTime() { return _nextSend; }

    synchronized int getRTT() { return _rtt; }

    /** RemoteHostId, uniquely identifies an attempt */
    RemoteHostId getRemoteHostId() { return _remoteHostId; }

    /**
     *  Note that while a SessionConfirmed could in theory be fragmented,
     *  in practice a RouterIdentity is 387 bytes and a single fragment is 512 bytes max,
     *  so it will never be fragmented.
     */
    public synchronized void receiveSessionConfirmed(UDPPacketReader.SessionConfirmedReader conf) {
        if (_receivedIdentity == null)
            _receivedIdentity = new byte[conf.readTotalFragmentNum()][];
        int cur = conf.readCurrentFragmentNum();
        if (cur >= _receivedIdentity.length) {
            // avoid AIOOBE
            // should do more than this, but what? disconnect?
            fail();
            packetReceived();
            return;
        }
        if (_receivedIdentity[cur] == null) {
            byte fragment[] = new byte[conf.readCurrentFragmentSize()];
            conf.readFragmentData(fragment, 0);
            _receivedIdentity[cur] = fragment;
        }

        if (cur == _receivedIdentity.length-1) {
            _receivedSignedOnTime = conf.readFinalFragmentSignedOnTime();
            // TODO verify time to prevent replay attacks
            buildIdentity();
            if (_receivedUnconfirmedIdentity != null) {
                SigType type = _receivedUnconfirmedIdentity.getSigningPublicKey().getType();
                if (type != null) {
                    int sigLen = type.getSigLen();
                    if (_receivedSignature == null)
                        _receivedSignature = new byte[sigLen];
                    conf.readFinalSignature(_receivedSignature, 0, sigLen);
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Unsupported sig type from: " + toString());
                    // _x() in UDPTransport
                    _context.banlist().banlistRouterForever(_receivedUnconfirmedIdentity.calculateHash(),
                                                            " <b>➜</b> " + "Unsupported signature type");
                    fail();
                }
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Bad ident from: " + toString());
                fail();
            }
        }

        if ( (_currentState == InboundState.IB_STATE_UNKNOWN) ||
             (_currentState == InboundState.IB_STATE_REQUEST_RECEIVED) ||
             (_currentState == InboundState.IB_STATE_CREATED_SENT) ) {
            if (confirmedFullyReceived())
                _currentState = InboundState.IB_STATE_CONFIRMED_COMPLETELY;
            else
                _currentState = InboundState.IB_STATE_CONFIRMED_PARTIALLY;
        }

        if (_createdSentCount == 1) {
            _rtt = (int) ( _context.clock().now() - _lastSend );
        }

        packetReceived();
    }

    /**
     *  Have we fully received the SessionConfirmed messages from Alice?
     *  Caller must synch on this.
     */
    protected boolean confirmedFullyReceived() {
        if (_receivedIdentity != null) {
            for (int i = 0; i < _receivedIdentity.length; i++) {
                if (_receivedIdentity[i] == null)
                    return false;
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Who is Alice (null if forged/unknown)
     *
     * Note that this isn't really confirmed - see below.
     */
    public synchronized RouterIdentity getConfirmedIdentity() {
        if (!_verificationAttempted) {
            verifyIdentity();
            _verificationAttempted = true;
        }
        return _receivedConfirmedIdentity;
    }

    /**
     *  Construct Alice's RouterIdentity.
     *  Must have received all fragments.
     *  Sets _receivedUnconfirmedIdentity, unless invalid.
     *
     *  Caller must synch on this.
     *
     *  @since 0.9.16 was in verifyIdentity()
     */
    private void buildIdentity() {
        if (_receivedUnconfirmedIdentity != null)
            return;   // dup pkt?
        int frags = _receivedIdentity.length;
        byte[] ident;
        if (frags > 1) {
            int identSize = 0;
            for (int i = 0; i < _receivedIdentity.length; i++)
                identSize += _receivedIdentity[i].length;
            ident = new byte[identSize];
            int off = 0;
            for (int i = 0; i < _receivedIdentity.length; i++) {
                int len = _receivedIdentity[i].length;
                System.arraycopy(_receivedIdentity[i], 0, ident, off, len);
                off += len;
            }
        } else {
            // no need to copy
            ident = _receivedIdentity[0];
        }
        ByteArrayInputStream in = new ByteArrayInputStream(ident);
        RouterIdentity peer = new RouterIdentity();
        try {
            peer.readBytes(in);
            _receivedUnconfirmedIdentity = peer;
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Improperly formatted yet fully received ident", dfe);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Improperly formatted yet fully received ident", ioe);
        }
    }


    /**
     * Determine if Alice sent us a valid confirmation packet.  The
     * identity signs: Alice's IP + Alice's port + Bob's IP + Bob's port
     * + Alice's new relay key + Alice's signed on time
     *
     * Note that the protocol does not include a signature of the RouterIdentity,
     * which could be a problem?
     *
     * Caller must synch on this.
     */
    private void verifyIdentity() {
            if (_receivedUnconfirmedIdentity == null)
                return;   // either not yet recvd or bad ident
            if (_receivedSignature == null)
                return;   // either not yet recvd or bad sig

            byte signed[] = new byte[256+256 // X + Y
                                     + _aliceIP.length + 2
                                     + _bobIP.length + 2
                                     + 4 // Alice's relay key
                                     + 4 // signed on time
                                     ];

            int off = 0;
            System.arraycopy(_receivedX, 0, signed, off, _receivedX.length);
            off += _receivedX.length;
            getSentY();
            System.arraycopy(_sentY, 0, signed, off, _sentY.length);
            off += _sentY.length;
            System.arraycopy(_aliceIP, 0, signed, off, _aliceIP.length);
            off += _aliceIP.length;
            DataHelper.toLong(signed, off, 2, _alicePort);
            off += 2;
            System.arraycopy(_bobIP, 0, signed, off, _bobIP.length);
            off += _bobIP.length;
            DataHelper.toLong(signed, off, 2, _bobPort);
            off += 2;
            DataHelper.toLong(signed, off, 4, _sentRelayTag);
            off += 4;
            DataHelper.toLong(signed, off, 4, _receivedSignedOnTime);
            Signature sig = new Signature(_receivedUnconfirmedIdentity.getSigType(), _receivedSignature);
            boolean ok = _context.dsa().verifySignature(sig, signed, _receivedUnconfirmedIdentity.getSigningPublicKey());
            if (ok) {
                // todo partial spoof detection - get peer.calculateHash(),
                // lookup in netdb locally, if not equal, fail?
                _receivedConfirmedIdentity = _receivedUnconfirmedIdentity;
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Signature failed from " + _receivedUnconfirmedIdentity);
            }
    }

    /**
     *  Call from synchronized method only
     */
    protected void packetReceived() {
        _nextSend = _context.clock().now();
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("InboundEstablishState ");
        buf.append(Addresses.toString(_aliceIP, _alicePort));
        //if (_receivedX != null)
        //    buf.append(" ReceivedX: ").append(Base64.encode(_receivedX, 0, 4));
        //if (_sentY != null)
        //    buf.append(" SentY: ").append(Base64.encode(_sentY, 0, 4));
        //buf.append(" Bob: ").append(Addresses.toString(_bobIP, _bobPort));
        buf.append("; RelayTag: ").append(_sentRelayTag);
        //buf.append(" SignedOn: ").append(_sentSignedOnTime);
        buf.append(' ').append(_currentState);
        return buf.toString();
    }
}
