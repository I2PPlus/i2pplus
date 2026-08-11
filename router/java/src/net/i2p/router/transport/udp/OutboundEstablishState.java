package net.i2p.router.transport.udp;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterIdentity;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Data for a new connection being established, where we initiated the
 * connection with a remote peer.  In other words, we are Alice and
 * they are Bob.
 *
 */
class OutboundEstablishState {
    /** The router context */
    protected final RouterContext _context;
    /** Logger. */
    protected final Log _log;
    // SessionRequest message
    /** Bob's IP */
    protected byte[] _bobIP;
    /** Bob's port */
    protected int _bobPort;
    // SessionCreated message
    /** Alice's IP */
    protected byte[] _aliceIP;
    /** Alice's port */
    protected int _alicePort;
    /** Received relay tag */
    protected long _receivedRelayTag;
    /** Received signed on time */
    private long _receivedSignedOnTime;
    // includes trailing padding to mod 16
    // SessionConfirmed messages
    /** Sent signed on time */
    private long _sentSignedOnTime;
    // general status
    /** Establish begin time */
    protected final long _establishBegin;
    /** Last send time */
    protected long _lastSend;
    /** Next send time */
    protected long _nextSend;
    /** Remote host ID */
    protected RemoteHostId _remoteHostId;
    /** Claimed address */
    private final RemoteHostId _claimedAddress;
    /** Remote peer */
    protected final RouterIdentity _remotePeer;
    /** Whether extended options are allowed */
    private final boolean _allowExtendedOptions;
    /** Whether introduction is needed */
    private final boolean _needIntroduction;
    /** Introduction key */
    private final SessionKey _introKey;
    /** Queued messages */
    private final Queue<OutNetMessage> _queuedMessages;
    /** Current state */
    protected OutboundState _currentState;
    /** Introduction nonce */
    private long _introductionNonce;
    /** Whether first message is our DSM */
    private boolean _isFirstMessageOurDSM;
    // intro
    /** Remote address */
    private final UDPAddress _remoteAddress;
    /** Whether establishment complete */
    private boolean _complete;
    // counts for backoff
    /** Confirmed sent count */
    private int _confirmedSentCount;
    /** Request sent count */
    protected int _requestSentCount;
    /** Intro sent count */
    private int _introSentCount;
    // Times for timeout
    /** Confirmed sent time */
    private long _confirmedSentTime;
    /** Request sent time */
    protected long _requestSentTime;
    /** Intro sent time */
    private long _introSentTime;
    /** Round trip time */
    protected int _rtt;

    /**
     * States for outbound SSU session establishment.
     * Tracks the progression of outgoing connection setup.
     */
    public enum OutboundState {
        /** Nothing sent yet. */
        OB_STATE_UNKNOWN,
        /** We have sent an initial request. */
        OB_STATE_REQUEST_SENT,
        /** We have received a signed creation packet. */
        OB_STATE_CREATED_RECEIVED,
        /** We have sent one or more confirmation packets. */
        OB_STATE_CONFIRMED_PARTIALLY,
        /** We have received a data packet. */
        OB_STATE_CONFIRMED_COMPLETELY,
        /** We need to have someone introduce us to the peer, but haven't received a RelayResponse yet. */
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

    /**
     *  Flat delay between all retransmits (no exponential backoff).
     *  Handshake packets are tiny (~200B) and infrequent per-peer,
     *  so the bandwidth cost of flat retransmission is negligible.
     *  The OB_MESSAGE_TIMEOUT (2.5s) bounds total retransmits per establishment phase.
     *
     *  Was 1000ms with exponential doubling (max gap 16s).
     *  Reduced to 500ms, now 200ms for sub-100ms message delivery.
     */
    protected static final long RETRANSMIT_DELAY = SystemVersion.isSlow() ? 300 : 200;

    /** Max delay including backoff (15 seconds) */
    protected static final long MAX_DELAY = 15*1000L;

    private static final long WAIT_FOR_HOLE_PUNCH_DELAY = 500;

    /**
     *  For SSU2
     *
     *  @param ctx the router context
     *  @param claimedAddress the claimed address from the netdb
     *  @param remoteHostId the remote host ID
     *  @param remotePeer the remote peer identity
     *  @param needIntroduction whether introduction is needed
     *  @param introKey the introduction key
     *  @param addr the UDP address
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
        _queuedMessages = new LinkedBlockingQueue<>();
        _establishBegin = ctx.clock().now();
        _remoteAddress = addr;
        _introductionNonce = -1;
        if (addr.getIntroducerCount() > 0) {
            if (_log.shouldDebug())
                _log.debug("new outbound establish to " + remotePeer.calculateHash() + ", with address: " + addr);
            _currentState = OutboundState.OB_STATE_PENDING_INTRO;
        } else {
            _currentState = OutboundState.OB_STATE_UNKNOWN;
        }
    }

    /**
     *  Version of the SSU protocol in use.
     *
     *  @return the protocol version
     *  @since 0.9.54
     */
    public int getVersion() { return 1; }

    /**
     *  Current outbound handshake state.
     *
     *  @return the current outbound state
     */
    public synchronized OutboundState getState() { return _currentState; }

    /**
     * Mark as complete.
     *
     * @return whether it was previously complete
     */
    public synchronized boolean complete() {
        boolean already = _complete;
        _complete = true;
        return already;
    }

    /**
     *  Remote address of the peer.
     *
     *  @return the remote address
     */
    public UDPAddress getRemoteAddress() { return _remoteAddress; }

    /**
     *  Introduction nonce.
     *
     *  @param nonce the introduction nonce
     */
    public void setIntroNonce(long nonce) { _introductionNonce = nonce; }

    /**
     * Introduction nonce, or -1 if unset.
     *
     * @return the nonce, or -1
     */
    public long getIntroNonce() { return _introductionNonce; }

    /**
     *  Are we allowed to send extended options to this peer?
     *
     *  @return true if allowed
     *  @since 0.9.24
     */
    public boolean isExtendedOptionsAllowed() { return _allowExtendedOptions; }

    /**
     *  Should we ask this peer to be an introducer for us?
     *  Ignored unless allowExtendedOptions is true
     *
     *  @return true if introduction is needed
     *  @since 0.9.24
     */
    public boolean needIntroduction() { return _needIntroduction; }

    /**
     *  Round-trip time estimate.
     *
     *  @return the RTT
     */
    synchronized int getRTT() { return _rtt; }

    /**
     *  Queue a message to be sent after the session is established.
     *
     *  @param msg the message to queue
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
     *
     *  @return true if the first queued message is our DSM
     *  @since 0.9.12
     */
    public boolean isFirstMessageOurDSM() {
        return _isFirstMessageOurDSM;
    }

    /**
     *  Next queued message.
     *
     *  @return the next queued message, or null if none
     */
    public OutNetMessage getNextQueuedMessage() {
        return _queuedMessages.poll();
    }

    /**
     *  Remote router identity.
     *
     *  @return the remote identity
     */
    public RouterIdentity getRemoteIdentity() { return _remotePeer; }

    /**
     *  Bob's introduction key, as published in the netdb
     *
     *  @return the intro key
     */
    public SessionKey getIntroKey() { return _introKey; }

    /**
     * The remote side (Bob) - note that in some places he's called Charlie.
     * Warning - may change after introduction. May be null before introduction.
     *
     * @return the sent IP, or null
     */
    public synchronized byte[] getSentIP() { return _bobIP; }

    /**
     * The remote side (Bob) - note that in some places he's called Charlie.
     * Warning - may change after introduction. May be -1 before introduction.
     *
     * @return the sent port, or -1
     */
    public synchronized int getSentPort() { return _bobPort; }

    /**
     * Blocking call (run in the establisher thread) to determine if the
     * session was created properly.  If it wasn't, all the SessionCreated
     * remnants are dropped (perhaps they were spoofed, etc) so that we can
     * receive another one
     *
     * Generates session key and mac key.
     *
     * @return true if valid
     */
    public synchronized boolean validateSessionCreated() {
        throw new UnsupportedOperationException("see override");
    }

    /**
     *  The SessionCreated validation failed
     */
    public synchronized void fail() {
        _aliceIP = null;
        _receivedRelayTag = 0;
        _receivedSignedOnTime = -1;
        // sure, there's a chance the packet was corrupted, but in practice
        // this means that Bob doesn't know his external port, so give up.
        _currentState = OutboundState.OB_STATE_VALIDATION_FAILED;

        _nextSend = _context.clock().now();
    }

    /**
     *  Relay tag received from the introducer.
     *
     *  @return the received relay tag
     */
    public synchronized long getReceivedRelayTag() { return _receivedRelayTag; }
    /**
     *  Time the signed-on packet was sent.
     *
     *  @return the sent signed-on time
     */
    public synchronized long getSentSignedOnTime() { return _sentSignedOnTime; }
    /**
     *  Time the signed-on packet was received.
     *
     *  @return the received signed-on time
     */
    public synchronized long getReceivedSignedOnTime() { return _receivedSignedOnTime; }
    /**
     *  IP address reported by the peer.
     *
     *  @return the received IP
     */
    public synchronized byte[] getReceivedIP() { return _aliceIP; }
    /**
     *  Port reported by the peer.
     *
     *  @return the received port
     */
    public synchronized int getReceivedPort() { return _alicePort; }

    /** Note that we just sent the SessionConfirmed packet. */
    public synchronized void confirmedPacketsSent() {
        _lastSend = _context.clock().now();
        long delay;
        if (_confirmedSentCount == 0) {
            delay = RETRANSMIT_DELAY;
            _confirmedSentTime = _lastSend;
        } else {
            delay = Math.min(RETRANSMIT_DELAY,
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
     *  When we sent the first SessionConfirmed packet.
     *
     *  @return the time, or 0 if not yet sent
     *  @since 0.9.2
     */
    public long getConfirmedSentTime() { return _confirmedSentTime; }

    /** Note that we just sent the SessionRequest packet. */
    public synchronized void requestSent() {
        _lastSend = _context.clock().now();
        long delay;
        if (_requestSentCount == 0) {
            delay = RETRANSMIT_DELAY;
            _requestSentTime = _lastSend;
        } else {
            delay = Math.min(RETRANSMIT_DELAY,
                             _requestSentTime + EstablishmentManager.OB_MESSAGE_TIMEOUT - _lastSend);
        }
        _requestSentCount++;
        _nextSend = _lastSend + delay;
        if (_log.shouldDebug())
            _log.debug("Sent a Session Request packet; next send in " + delay + "ms on " + this);
        if (_currentState == OutboundState.OB_STATE_UNKNOWN ||
            _currentState == OutboundState.OB_STATE_INTRODUCED)
            _currentState = OutboundState.OB_STATE_REQUEST_SENT;
    }


    /**
     *  When we sent the first SessionRequest packet.
     *
     *  @return the time, or 0 if not yet sent
     *  @since 0.9.2
     */
    public long getRequestSentTime() { return _requestSentTime; }

    /** Note that we just sent the RelayRequest packet. */
    public synchronized void introSent() {
        _lastSend = _context.clock().now();
        long delay;
        if (_introSentCount == 0) {
            delay = RETRANSMIT_DELAY;
            _introSentTime = _lastSend;
        } else {
            delay = Math.min(RETRANSMIT_DELAY,
                             _introSentTime + EstablishmentManager.OB_MESSAGE_TIMEOUT - _lastSend);
        }
        _introSentCount++;
        _nextSend = _lastSend + delay;
        if (_currentState == OutboundState.OB_STATE_UNKNOWN)
            _currentState = OutboundState.OB_STATE_PENDING_INTRO;
    }

    /**
     *  When we sent the first RelayRequest packet.
     *
     *  @return the time, or 0 if not yet sent
     *  @since 0.9.2
     */
    public long getIntroSentTime() { return _introSentTime; }

    /**
     * Record that the introduction failed.
     */
    public synchronized void introductionFailed() {
        _nextSend = _context.clock().now();
        // keep the state as OB_STATE_PENDING_INTRO, so next time the EstablishmentManager asks us
        // whats up, it'll try a new random intro peer
    }

    /**
     *  This changes the remoteHostId from a hash-based one or possibly
     *  incorrect IP/port to what the introducer told us.
     *  All params are for the remote end (NOT the introducer) and must have been validated already.
     *
     *  @param bobIP the remote IP
     *  @param bobPort the remote port
     */
    public synchronized void introduced(byte[] bobIP, int bobPort) {
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
            _log.info("Introduced to " + _remoteHostId + ", attempting to establish connection...");
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
            _log.info(toString() + " accelerating Session Request by " + (_nextSend - now) + "ms");
        _nextSend = now;
        return true;
    }

    /**
     * How long have we been trying to establish this session?
     *
     * @return the lifetime in milliseconds
     */
    public long getLifetime() { return getLifetime(_context.clock().now()); }

    /**
     * How long have we been trying to establish this session?
     *
     * @param now the current time
     * @return the lifetime in milliseconds
     * @since 0.9.57
     */
    public long getLifetime(long now) { return now - _establishBegin; }

    /**
     *  Time the establish began.
     *
     *  @return the establish begin time
     */
    public long getEstablishBeginTime() { return _establishBegin; }

    /**
     *  The next time a message should be sent.
     *
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

    /** We have received a real data packet, so we're done establishing. */
    public synchronized void dataReceived() {
        packetReceived();
        _currentState = OutboundState.OB_STATE_CONFIRMED_COMPLETELY;
    }

    /**
     *  Call from synchronized method only
     */
    protected void packetReceived() {
        _nextSend = _context.clock().now();
    }

    /** String representation */
    @Override
    public String toString() {
        return "OutboundEstablishState [" + _remotePeer.getHash().toBase64().substring(0, 6) + "] " + _remoteHostId +
               "\n* Lifetime: " + DataHelper.formatDuration(getLifetime()) + ' ' + _currentState;
    }
}
