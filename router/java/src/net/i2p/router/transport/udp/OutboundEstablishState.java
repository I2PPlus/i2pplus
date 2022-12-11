package net.i2p.router.transport.udp;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

import java.util.Date;

/**
 * Data for a new connection being established, where we initiated the
 * connection with a remote peer.  In other words, we are Alice and
 * they are Bob.
 *
 */
class OutboundEstablishState {
    protected final RouterContext _context;
    protected final Log _log;
    // SessionRequest message
    private byte _sentX[];
    protected byte _bobIP[];
    protected int _bobPort;
    private final DHSessionKeyBuilder.Factory _keyFactory;
    private DHSessionKeyBuilder _keyBuilder;
    // SessionCreated message
    private byte _receivedY[];
    protected byte _aliceIP[];
    protected int _alicePort;
    protected long _receivedRelayTag;
    private long _receivedSignedOnTime;
    private SessionKey _sessionKey;
    private SessionKey _macKey;
    private Signature _receivedSignature;
    // includes trailing padding to mod 16
    private byte[] _receivedEncryptedSignature;
    private byte[] _receivedIV;
    // SessionConfirmed messages
    private long _sentSignedOnTime;
    private Signature _sentSignature;
    // general status
    protected final long _establishBegin;
    //private long _lastReceive;
    protected long _lastSend;
    protected long _nextSend;
    protected RemoteHostId _remoteHostId;
    private final RemoteHostId _claimedAddress;
    protected final RouterIdentity _remotePeer;
    private final boolean _allowExtendedOptions;
    private final boolean _needIntroduction;
    private final SessionKey _introKey;
    private final Queue<OutNetMessage> _queuedMessages;
    protected OutboundState _currentState;
    private long _introductionNonce;
    private boolean _isFirstMessageOurDSM;
    // intro
    private final UDPAddress _remoteAddress;
    private boolean _complete;
    // counts for backoff
    private int _confirmedSentCount;
    protected int _requestSentCount;
    private int _introSentCount;
    // Times for timeout
    private long _confirmedSentTime;
    protected long _requestSentTime;
    private long _introSentTime;
    protected int _rtt;

    public enum OutboundState {
        /** nothin sent yet */
        OB_STATE_UNKNOWN,
        /** we have sent an initial request */
        OB_STATE_REQUEST_SENT,
        /** we have received a signed creation packet */
        OB_STATE_CREATED_RECEIVED,
        /** we have sent one or more confirmation packets */
        OB_STATE_CONFIRMED_PARTIALLY,
        /** we have received a data packet */
        OB_STATE_CONFIRMED_COMPLETELY,
        /** we need to have someone introduce us to the peer, but haven't received a RelayResponse yet */
        OB_STATE_PENDING_INTRO,
        /** RelayResponse received */
        OB_STATE_INTRODUCED,
        /** SessionConfirmed failed validation */
        OB_STATE_VALIDATION_FAILED,

        /**
         * SSU2: We don't have a token
         * @since 0.9.54
         */
        OB_STATE_NEEDS_TOKEN,
        /**
         * SSU2: We have sent a token request
         * @since 0.9.54
         */
        OB_STATE_TOKEN_REQUEST_SENT,
        /**
         * SSU2: We have received a retry
         * @since 0.9.54
         */
        OB_STATE_RETRY_RECEIVED,
        /**
         * SSU2: We have sent a session request after receiving a retry
         * @since 0.9.54
         */
        OB_STATE_REQUEST_SENT_NEW_TOKEN
    }

    /** basic delay before backoff
     *  Transmissions at 0, 1.25, 3.75, 8.75 sec
     *  This should be a little longer than for inbound.
     */
    protected static final long RETRANSMIT_DELAY = 1250;

    /**
     *  max delay including backoff
     *  This should be a little longer than for inbound.
     */
    private static final long MAX_DELAY = 15*1000;

    private static final long WAIT_FOR_HOLE_PUNCH_DELAY = 500;

    /**
     *  @param claimedAddress an IP/port based RemoteHostId, or null if unknown
     *  @param remoteHostId non-null, == claimedAddress if direct, or a hash-based one if indirect
     *  @param remotePeer must have supported sig type
     *  @param allowExtendedOptions are we allowed to send extended options to Bob?
     *  @param needIntroduction should we ask Bob to be an introducer for us?
               ignored unless allowExtendedOptions is true
     *  @param introKey Bob's introduction key, as published in the netdb
     *  @param addr non-null
     */
    public OutboundEstablishState(RouterContext ctx, RemoteHostId claimedAddress,
                                  RemoteHostId remoteHostId,
                                  RouterIdentity remotePeer, boolean allowExtendedOptions,
                                  boolean needIntroduction,
                                  SessionKey introKey, UDPAddress addr,
                                  DHSessionKeyBuilder.Factory dh) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundEstablishState.class);
        if (claimedAddress != null) {
            _bobIP = claimedAddress.getIP();
            _bobPort = claimedAddress.getPort();
        } else {
            //_bobIP = null;
            _bobPort = -1;
        }
        _claimedAddress = claimedAddress;
        _remoteHostId = remoteHostId;
        _allowExtendedOptions = allowExtendedOptions;
        _needIntroduction = needIntroduction;
        _remotePeer = remotePeer;
        _introKey = introKey;
        _queuedMessages = new LinkedBlockingQueue<OutNetMessage>();
        _establishBegin = ctx.clock().now();
        _remoteAddress = addr;
        _introductionNonce = -1;
        _keyFactory = dh;
        if (addr.getIntroducerCount() > 0) {
            if (_log.shouldDebug())
                _log.debug("New Outbound establish to " + remotePeer.calculateHash() + ", with address: " + addr);
            _currentState = OutboundState.OB_STATE_PENDING_INTRO;
        } else {
            _currentState = OutboundState.OB_STATE_UNKNOWN;
        }
    }

    /**
     *  For SSU2
     *
     *  @since 0.9.54
     */
    protected OutboundEstablishState(RouterContext ctx, RemoteHostId claimedAddress,
                                  RemoteHostId remoteHostId,
                                  RouterIdentity remotePeer,
                                  boolean needIntroduction,
                                  SessionKey introKey, UDPAddress addr) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        if (claimedAddress != null) {
            _bobIP = claimedAddress.getIP();
            _bobPort = claimedAddress.getPort();
        } else {
            //_bobIP = null;
            _bobPort = -1;
        }
        _claimedAddress = claimedAddress;
        _remoteHostId = remoteHostId;
        _allowExtendedOptions = false;
        _needIntroduction = needIntroduction;
        _remotePeer = remotePeer;
        _introKey = introKey;
        _queuedMessages = new LinkedBlockingQueue<OutNetMessage>();
        _establishBegin = ctx.clock().now();
        _remoteAddress = addr;
        _introductionNonce = -1;
        _keyFactory = null;
        if (addr.getIntroducerCount() > 0) {
            if (_log.shouldDebug())
                _log.debug("new outbound establish to " + remotePeer.calculateHash() + ", with address: " + addr);
            _currentState = OutboundState.OB_STATE_PENDING_INTRO;
        } else {
            _currentState = OutboundState.OB_STATE_UNKNOWN;
        }
    }

    /**
     * @since 0.9.54
     */
    public int getVersion() { return 1; }

    public synchronized OutboundState getState() { return _currentState; }

    /** @return if previously complete */
    public synchronized boolean complete() {
        boolean already = _complete;
        _complete = true;
        return already;
    }

    /** @return non-null */
    public UDPAddress getRemoteAddress() { return _remoteAddress; }

    public void setIntroNonce(long nonce) { _introductionNonce = nonce; }

    /** @return -1 if unset */
    public long getIntroNonce() { return _introductionNonce; }

    /**
     *  Are we allowed to send extended options to this peer?
     *  @since 0.9.24
     */
    public boolean isExtendedOptionsAllowed() { return _allowExtendedOptions; }

    /**
     *  Should we ask this peer to be an introducer for us?
     *  Ignored unless allowExtendedOptions is true
     *  @since 0.9.24
     */
    public boolean needIntroduction() { return _needIntroduction; }

    synchronized int getRTT() { return _rtt; }

    /**
     *  Queue a message to be sent after the session is established.
     */
    public void addMessage(OutNetMessage msg) {
        if (_queuedMessages.isEmpty()) {
            I2NPMessage m = msg.getMessage();
            if (m.getType() == DatabaseStoreMessage.MESSAGE_TYPE) {
               DatabaseStoreMessage dsm = (DatabaseStoreMessage) m;
               if (dsm.getKey().equals(_context.routerHash())) {
                   // version 2 sends our RI in handshake
                   if (getVersion() > 1)
                       return;
                   _isFirstMessageOurDSM = true;
               }
           }
        }
        // chance of a duplicate here in a race, that's ok
        if (!_queuedMessages.contains(msg))
            _queuedMessages.offer(msg);
        else if (_log.shouldWarn())
             _log.warn("Attempt to add duplicate msg to queue: " + msg);
    }

    /**
     *  Is the first message queued our own DatabaseStoreMessage?
     *  @since 0.9.12
     */
    public boolean isFirstMessageOurDSM() {
        return _isFirstMessageOurDSM;
    }

    /** @return null if none */
    public OutNetMessage getNextQueuedMessage() {
        return _queuedMessages.poll();
    }

    public RouterIdentity getRemoteIdentity() { return _remotePeer; }

    /**
     *  Bob's introduction key, as published in the netdb
     */
    public SessionKey getIntroKey() { return _introKey; }

    /** caller must synch - only call once */
    private void prepareSessionRequest() {
        _keyBuilder = _keyFactory.getBuilder();
        byte X[] = _keyBuilder.getMyPublicValue().toByteArray();
        if (X.length == 257) {
            _sentX = new byte[256];
            System.arraycopy(X, 1, _sentX, 0, _sentX.length);
        } else if (X.length == 256) {
            _sentX = X;
        } else {
            _sentX = new byte[256];
            System.arraycopy(X, 0, _sentX, _sentX.length - X.length, X.length);
        }
    }

    public synchronized byte[] getSentX() {
        // We defer keygen until now so that it gets done in the Establisher loop,
        // and so that we don't waste entropy on failed introductions
        if (_sentX == null)
            prepareSessionRequest();
        return _sentX;
    }

    /**
     * The remote side (Bob) - note that in some places he's called Charlie.
     * Warning - may change after introduction. May be null before introduction.
     */
    public synchronized byte[] getSentIP() { return _bobIP; }

    /**
     * The remote side (Bob) - note that in some places he's called Charlie.
     * Warning - may change after introduction. May be -1 before introduction.
     */
    public synchronized int getSentPort() { return _bobPort; }

    public synchronized void receiveSessionCreated(UDPPacketReader.SessionCreatedReader reader) {
        if (_currentState == OutboundState.OB_STATE_VALIDATION_FAILED) {
            if (_log.shouldWarn())
                _log.warn("SessionCreated already failed");
            return;
        }
        if (_receivedY != null) {
            if (_log.shouldDebug())
                _log.debug("SessionCreated already received, ignoring...");
            return; // already received
        }
        _receivedY = new byte[UDPPacketReader.SessionCreatedReader.Y_LENGTH];
        reader.readY(_receivedY, 0);
        if (_aliceIP == null)
            _aliceIP = new byte[reader.readIPSize()];
        reader.readIP(_aliceIP, 0);
        _alicePort = reader.readPort();
        _receivedRelayTag = reader.readRelayTag();
        _receivedSignedOnTime = reader.readSignedOnTime();
        // handle variable signature size
        SigType type = _remotePeer.getSigningPublicKey().getType();
        if (type == null) {
            // shouldn't happen, we only connect to supported peers
            fail();
            packetReceived();
            return;
        }
        int sigLen = type.getSigLen();
        int mod = sigLen % 16;
        int pad = (mod == 0) ? 0 : (16 - mod);
        int esigLen = sigLen + pad;
        _receivedEncryptedSignature = new byte[esigLen];
        reader.readEncryptedSignature(_receivedEncryptedSignature, 0, esigLen);
        _receivedIV = new byte[UDPPacket.IV_SIZE];
        reader.readIV(_receivedIV, 0);

        if (_log.shouldDebug())
            _log.debug("[Receive session created] Sig: " + Base64.encode(_receivedEncryptedSignature)
                       + "; ReceivedIV: " + Base64.encode(_receivedIV)
                       + "; AliceIP: " + Addresses.toString(_aliceIP)
                       + "; RelayTag: " + _receivedRelayTag
                       + "; SignedOn: " + _receivedSignedOnTime
                       + "; " + this.toString());

        if (_currentState == OutboundState.OB_STATE_UNKNOWN ||
            _currentState == OutboundState.OB_STATE_REQUEST_SENT ||
            _currentState == OutboundState.OB_STATE_INTRODUCED ||
            _currentState == OutboundState.OB_STATE_PENDING_INTRO)
            _currentState = OutboundState.OB_STATE_CREATED_RECEIVED;

        if (_requestSentCount == 1) {
            _rtt = (int) (_context.clock().now() - _requestSentTime);
        }
        packetReceived();
    }

    /**
     * Blocking call (run in the establisher thread) to determine if the
     * session was created properly.  If it wasn't, all the SessionCreated
     * remnants are dropped (perhaps they were spoofed, etc) so that we can
     * receive another one
     *
     *  Generates session key and mac key.
     *
     * @return true if valid
     */
    public synchronized boolean validateSessionCreated() {
        if (_currentState == OutboundState.OB_STATE_VALIDATION_FAILED) {
            if (_log.shouldWarn())
                _log.warn("SessionCreated already failed");
            return false;
        }
        if (_receivedSignature != null) {
            if (_log.shouldDebug())
                _log.debug("SessionCreated already validated");
            return true;
        }

        boolean valid = true;
        try {
            generateSessionKey();
        } catch (DHSessionKeyBuilder.InvalidPublicParameterException ippe) {
            if (_log.shouldWarn())
                _log.warn("Peer " + getRemoteHostId() + " sent us an invalid DH parameter", ippe);
            valid = false;
        }
        if (valid)
            decryptSignature();

        if (valid && verifySessionCreated()) {
            if (_log.shouldDebug())
                _log.debug("SessionCreated passed validation");
            return true;
        } else {
            if (_log.shouldWarn())
                _log.warn("SessionCreated failed validation -> Clearing state for: " + _remoteHostId.toString());
            fail();
            return false;
        }
    }

    /**
     *  The SessionCreated validation failed
     */
    public synchronized void fail() {
        _receivedY = null;
        _aliceIP = null;
        _receivedRelayTag = 0;
        _receivedSignedOnTime = -1;
        _receivedEncryptedSignature = null;
        _receivedIV = null;
        _receivedSignature = null;
        if (_keyBuilder != null) {
            //if (_keyBuilder.getPeerPublicValue() == null)
            //    _keyFactory.returnUnused(_keyBuilder);
            _keyBuilder = null;
        }
        // sure, there's a chance the packet was corrupted, but in practice
        // this means that Bob doesn't know his external port, so give up.
        _currentState = OutboundState.OB_STATE_VALIDATION_FAILED;

        _nextSend = _context.clock().now();
    }

    /**
     *  Generates session key and mac key.
     *  Caller must synch on this.
     */
    private void generateSessionKey() throws DHSessionKeyBuilder.InvalidPublicParameterException {
        if (_sessionKey != null) return;
        if (_keyBuilder == null)
            throw new DHSessionKeyBuilder.InvalidPublicParameterException("Illegal state - never generated a key builder");
        try {
            _keyBuilder.setPeerPublicValue(_receivedY);
        } catch (IllegalStateException ise) {
            throw new DHSessionKeyBuilder.InvalidPublicParameterException("reused keys?", ise);
        }
        _sessionKey = _keyBuilder.getSessionKey();
        ByteArray extra = _keyBuilder.getExtraBytes();
        _macKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
        System.arraycopy(extra.getData(), 0, _macKey.getData(), 0, SessionKey.KEYSIZE_BYTES);
        if (_log.shouldDebug())
            _log.debug("Established outbound keys. Cipher: " + _sessionKey
                       + " mac: " + _macKey);
    }

    /**
     * decrypt the signature (and subsequent pad bytes) with the
     * additional layer of encryption using the negotiated key along side
     * the packet's IV
     *
     *  Caller must synch on this.
     *  Only call this once! Decrypts in-place.
     */
    private void decryptSignature() {
        if (_receivedEncryptedSignature == null) throw new NullPointerException("Encrypted signature is null! this=" + this.toString());
        if (_sessionKey == null) throw new NullPointerException("SessionKey is null!");
        if (_receivedIV == null) throw new NullPointerException("IV is null!");
        _context.aes().decrypt(_receivedEncryptedSignature, 0, _receivedEncryptedSignature, 0,
                               _sessionKey, _receivedIV, _receivedEncryptedSignature.length);
        // handle variable signature size
        SigType type = _remotePeer.getSigningPublicKey().getType();
        // if type == null throws NPE
        int sigLen = type.getSigLen();
        int mod = sigLen % 16;
        if (mod != 0) {
            byte signatureBytes[] = new byte[sigLen];
            System.arraycopy(_receivedEncryptedSignature, 0, signatureBytes, 0, sigLen);
            _receivedSignature = new Signature(type, signatureBytes);
        } else {
            _receivedSignature = new Signature(type, _receivedEncryptedSignature);
        }
        if (_log.shouldDebug())
            _log.debug("Decrypted received signature: " + Base64.encode(_receivedSignature.getData()));
    }

    /**
     * Verify: Alice's IP + Alice's port + Bob's IP + Bob's port + Alice's
     *         new relay tag + Bob's signed on time
     *  Caller must synch on this.
     */
    private boolean verifySessionCreated() {
        byte signed[] = new byte[256+256 // X + Y
                                 + _aliceIP.length + 2
                                 + _bobIP.length + 2
                                 + 4 // sent relay tag
                                 + 4 // signed on time
                                 ];

        int off = 0;
        System.arraycopy(_sentX, 0, signed, off, _sentX.length);
        off += _sentX.length;
        System.arraycopy(_receivedY, 0, signed, off, _receivedY.length);
        off += _receivedY.length;
        System.arraycopy(_aliceIP, 0, signed, off, _aliceIP.length);
        off += _aliceIP.length;
        DataHelper.toLong(signed, off, 2, _alicePort);
        off += 2;
        System.arraycopy(_bobIP, 0, signed, off, _bobIP.length);
        off += _bobIP.length;
        DataHelper.toLong(signed, off, 2, _bobPort);
        off += 2;
        DataHelper.toLong(signed, off, 4, _receivedRelayTag);
        off += 4;
        DataHelper.toLong(signed, off, 4, _receivedSignedOnTime);
        boolean valid = _context.dsa().verifySignature(_receivedSignature, signed, _remotePeer.getSigningPublicKey());
        if (_log.shouldDebug() || (_log.shouldWarn() && !valid)) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("Signed session created");
            buf.append("\n* Signature: ").append(Base64.encode(_receivedSignature.getData()));
            buf.append("\n* Signed on: ").append(new Date(_receivedSignedOnTime));
            buf.append("\n* Alice: ").append(Addresses.toString(_aliceIP, _alicePort));
            buf.append("; Bob: ").append(Addresses.toString(_bobIP, _bobPort));
            buf.append("\n* RelayTag: ").append(_receivedRelayTag);
            if (valid)
                _log.debug(buf.toString());
            else if (_log.shouldWarn())
                _log.warn("Invalid " + buf.toString());
        }
        return valid;
    }

    public synchronized SessionKey getCipherKey() { return _sessionKey; }
    public synchronized SessionKey getMACKey() { return _macKey; }

    public synchronized long getReceivedRelayTag() { return _receivedRelayTag; }
    public synchronized long getSentSignedOnTime() { return _sentSignedOnTime; }
    public synchronized long getReceivedSignedOnTime() { return _receivedSignedOnTime; }
    public synchronized byte[] getReceivedIP() { return _aliceIP; }
    public synchronized int getReceivedPort() { return _alicePort; }

    /**
     *  Let's sign everything so we can fragment properly.
     *
     *  Note that while a SessionConfirmed could in theory be fragmented,
     *  in practice a RouterIdentity is 387 bytes and a single fragment is 512 bytes max,
     *  so it will never be fragmented.
     */
    public synchronized void prepareSessionConfirmed() {
        if (_sentSignedOnTime > 0)
            return;
        byte signed[] = new byte[256+256 // X + Y
                             + _aliceIP.length + 2
                             + _bobIP.length + 2
                             + 4 // Alice's relay key
                             + 4 // signed on time
                             ];

        _sentSignedOnTime = _context.clock().now() / 1000;

        int off = 0;
        System.arraycopy(_sentX, 0, signed, off, _sentX.length);
        off += _sentX.length;
        System.arraycopy(_receivedY, 0, signed, off, _receivedY.length);
        off += _receivedY.length;
        System.arraycopy(_aliceIP, 0, signed, off, _aliceIP.length);
        off += _aliceIP.length;
        DataHelper.toLong(signed, off, 2, _alicePort);
        off += 2;
        System.arraycopy(_bobIP, 0, signed, off, _bobIP.length);
        off += _bobIP.length;
        DataHelper.toLong(signed, off, 2, _bobPort);
        off += 2;
        DataHelper.toLong(signed, off, 4, _receivedRelayTag);
        off += 4;
        DataHelper.toLong(signed, off, 4, _sentSignedOnTime);
        // BUG - if SigningPrivateKey is null, _sentSignature will be null, leading to NPE later
        // should we throw something from here?
        _sentSignature = _context.dsa().sign(signed, _context.keyManager().getSigningPrivateKey());
    }

    public synchronized Signature getSentSignature() { return _sentSignature; }

    /** note that we just sent the SessionConfirmed packet */
    public synchronized void confirmedPacketsSent() {
        _lastSend = _context.clock().now();
        long delay;
        if (_confirmedSentCount == 0) {
            delay = RETRANSMIT_DELAY;
            _confirmedSentTime = _lastSend;
        } else {
            delay = Math.min(RETRANSMIT_DELAY << _confirmedSentCount,
                             _confirmedSentTime + EstablishmentManager.OB_MESSAGE_TIMEOUT - _lastSend);
        }
        _confirmedSentCount++;
        _nextSend = _lastSend + delay;
        if (_log.shouldDebug())
            _log.debug("Send confirm packets, nextSend in " + delay + "ms on " + this);
        if (_currentState == OutboundState.OB_STATE_UNKNOWN ||
            _currentState == OutboundState.OB_STATE_PENDING_INTRO ||
            _currentState == OutboundState.OB_STATE_INTRODUCED ||
            _currentState == OutboundState.OB_STATE_REQUEST_SENT ||
            _currentState == OutboundState.OB_STATE_CREATED_RECEIVED)
            _currentState = OutboundState.OB_STATE_CONFIRMED_PARTIALLY;
    }

    /**
     *  @return when we sent the first SessionConfirmed packet, or 0
     *  @since 0.9.2
     */
    public long getConfirmedSentTime() { return _confirmedSentTime; }

    /** note that we just sent the SessionRequest packet */
    public synchronized void requestSent() {
        _lastSend = _context.clock().now();
        long delay;
        if (_requestSentCount == 0) {
            delay = RETRANSMIT_DELAY;
            _requestSentTime = _lastSend;
        } else {
            delay = Math.min(RETRANSMIT_DELAY << _requestSentCount,
                             _requestSentTime + EstablishmentManager.OB_MESSAGE_TIMEOUT - _lastSend);
        }
        _requestSentCount++;
        _nextSend = _lastSend + delay;
        if (_log.shouldDebug())
            _log.debug("Sent a SessionRequest packet; next send in " + delay + "ms on " + this);
        if (_currentState == OutboundState.OB_STATE_UNKNOWN ||
            _currentState == OutboundState.OB_STATE_INTRODUCED)
            _currentState = OutboundState.OB_STATE_REQUEST_SENT;
    }


    /**
     *  @return when we sent the first SessionRequest packet, or 0
     *  @since 0.9.2
     */
    public long getRequestSentTime() { return _requestSentTime; }

    /** note that we just sent the RelayRequest packet */
    public synchronized void introSent() {
        _lastSend = _context.clock().now();
        long delay;
        if (_introSentCount == 0) {
            delay = RETRANSMIT_DELAY;
            _introSentTime = _lastSend;
        } else {
            delay = Math.min(RETRANSMIT_DELAY << _introSentCount,
                             _introSentTime + EstablishmentManager.OB_MESSAGE_TIMEOUT - _lastSend);
        }
        _introSentCount++;
        _nextSend = _lastSend + delay;
        if (_currentState == OutboundState.OB_STATE_UNKNOWN)
            _currentState = OutboundState.OB_STATE_PENDING_INTRO;
    }

    /**
     *  @return when we sent the first RelayRequest packet, or 0
     *  @since 0.9.2
     */
    public long getIntroSentTime() { return _introSentTime; }

    public synchronized void introductionFailed() {
        _nextSend = _context.clock().now();
        // keep the state as OB_STATE_PENDING_INTRO, so next time the EstablishmentManager asks us
        // whats up, it'll try a new random intro peer
    }

    /**
     *  This changes the remoteHostId from a hash-based one or possibly
     *  incorrect IP/port to what the introducer told us.
     *  All params are for the remote end (NOT the introducer) and must have been validated already.
     */
    public synchronized void introduced(byte bobIP[], int bobPort) {
        if (_currentState != OutboundState.OB_STATE_PENDING_INTRO)
            return; // we've already successfully been introduced, so don't overwrite old settings
        _nextSend = _context.clock().now() + WAIT_FOR_HOLE_PUNCH_DELAY; // wait briefly for the hole punching
        _currentState = OutboundState.OB_STATE_INTRODUCED;
        if (_claimedAddress != null && bobPort == _bobPort && DataHelper.eq(bobIP, _bobIP)) {
            // he's who he said he was
            _remoteHostId = _claimedAddress;
        } else {
            // no IP/port or wrong IP/port in RI
            _bobIP = bobIP;
            _bobPort = bobPort;
            _remoteHostId = new RemoteHostId(bobIP, bobPort);
        }
        if (_log.shouldInfo())
            _log.info("Introduced to " + _remoteHostId + ", now let's get on with establishing...");
    }

    /**
     *  Accelerate response to RelayResponse if we haven't sent it yet.
     *
     *  @return true if we should send the SessionRequest now
     *  @since 0.9.15
     */
    synchronized boolean receiveHolePunch() {
        if (_currentState != OutboundState.OB_STATE_INTRODUCED)
            return false;
        if (_requestSentCount > 0)
            return false;
        long now = _context.clock().now();
        if (_log.shouldInfo())
            _log.info(toString() + " accelerating SessionRequest by " + (_nextSend - now) + " ms");
        _nextSend = now;
        return true;
    }

    /** how long have we been trying to establish this session? */
    public long getLifetime() { return _context.clock().now() - _establishBegin; }
    public long getEstablishBeginTime() { return _establishBegin; }

    /**
     *  @return 0 at initialization (to force sending session request),
     *          rcv time after receiving a packet,
     *          send time + delay after sending a packet (including session request)
     */
    public synchronized long getNextSendTime() { return _nextSend; }

    /**
     *  This should be what the state is currently indexed by in the _outboundStates table.
     *  Beware -
     *  During introduction, this is a router hash.
     *  After introduced() is called, this is set to the IP/port the introducer told us.
     *  @return non-null
     */
    RemoteHostId getRemoteHostId() { return _remoteHostId; }

    /**
     *  This will never be a hash-based address.
     *  This is the 'claimed' (unverified) address from the netdb, or null.
     *  It is not changed after introduction. Use getRemoteHostId() for the verified address.
     *  @return may be null
     */
    RemoteHostId getClaimedAddress() { return _claimedAddress; }

    /** we have received a real data packet, so we're done establishing */
    public synchronized void dataReceived() {
        packetReceived();
        _currentState = OutboundState.OB_STATE_CONFIRMED_COMPLETELY;
    }

    /**
     *  Call from synchronized method only
     */
    protected void packetReceived() {
        _nextSend = _context.clock().now();
        //if (_log.shouldDebug())
        //    _log.debug("Received a packet, nextSend == now");
    }

    /** @since 0.8.9 */
    @Override
    public String toString() {
        return "OutboundEstablishState " + _remotePeer.getHash().toBase64().substring(0, 6) + ' ' + _remoteHostId +
               "\n* Lifetime: " + DataHelper.formatDuration(getLifetime()) +
               ' ' + _currentState;
    }
}
