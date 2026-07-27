package net.i2p.router.transport.udp;

import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import net.i2p.data.DataHelper;
import net.i2p.data.router.RouterIdentity;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Data for a new connection being established, where the remote peer has initiated
 * the connection with us.  In other words, they are Alice and we are Bob.
 */
class InboundEstablishState {  // TODO do all these methods need to be synchronized?
    /** Router context */
    protected final RouterContext _context;
    /** Logger */
    protected final Log _log;
    /** SessionRequest message data from Alice */
    private byte[] _receivedX;
    /** Our IP address as seen by Alice */
    protected byte[] _bobIP;
    /** Our port number as seen by Alice */
    protected final int _bobPort;
    /** Alice's IP address from the SessionCreated message */
    protected final byte[] _aliceIP;
    /** Alice's port number */
    protected final int _alicePort;
    /** Relay tag we sent to Alice */
    protected long _sentRelayTag;
    /** Signed-on time we sent to Alice */
    private long _sentSignedOnTime;
    /** SessionConfirmed message fragments from Alice (fragmentation theoretical only) */
    private byte[][] _receivedIdentity;
    /** Identity before signature verification */
    protected RouterIdentity _receivedUnconfirmedIdentity;
    /** Identity after successful signature verification */
    protected RouterIdentity _receivedConfirmedIdentity;
    /** Timestamp when establishment began */
    protected final long _establishBegin;
    /** Last packet send time */
    protected long _lastSend;
    /** Next scheduled send time */
    protected long _nextSend;
    /** Remote host identifier for uniqueness */
    protected final RemoteHostId _remoteHostId;
    /** Current state in the inbound establishment protocol */
    protected InboundState _currentState;
    /** Messages queued while session was being established */
    private final Queue<OutNetMessage> _queuedMessages;
    /** Count of SessionCreated packets sent (for exponential backoff) */
    protected int _createdSentCount;
    /** Whether Alice requested introduction (default true for SSU 1, false for SSU 2) */
    protected boolean _introductionRequested;
    /** Estimated round-trip time */
    protected int _rtt;

    /**
     * States for inbound SSU session establishment.
     * Tracks the progression of incoming connection setup.
     */
    public enum InboundState {
        /** nothin' known yet */
        IB_STATE_UNKNOWN,
        /** we have received an initial request */
        IB_STATE_REQUEST_RECEIVED,
        /** we have sent a signed creation packet */
        IB_STATE_CREATED_SENT,
        /** we have received one but not all the confirmation packets - never happens in practice - see below. */
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

    /** Basic delay before backoff
     *  Transmissions at 0, 1, 3, 7 sec.
     *  This should be a little shorter than for outbound.
     */
    protected static final long RETRANSMIT_DELAY = SystemVersion.isSlow() ? 1000 : 750;

    /**
     *  Max delay including backoff.
     *  This should be a little shorter than for outbound.
     */
    protected static long getMaxDelay() { return EstablishmentManager.MAX_IB_ESTABLISH_TIME.get(); }

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
        _queuedMessages = new LinkedBlockingQueue<>();
   }

    /**
     *  Return the SSU protocol version for this state (always 1 for SSU1).
     *
     *  @return the protocol version
     *  @since 0.9.54
     */
    public int getVersion() {return 1;}

    /**
     *  Current state of the inbound session establishment.
     *
     *  @return the current InboundState
     */
    public synchronized InboundState getState() {return _currentState;}

    /**
     *  Check if the inbound establish state is complete.
     *
     *  @return true if complete or failed
     */
    public synchronized boolean isComplete() {
        return _currentState == InboundState.IB_STATE_COMPLETE ||
               _currentState == InboundState.IB_STATE_FAILED;
   }

    /**
     *  Notify successful completion of the inbound session establishment.
     */
    public synchronized void complete() {_currentState = InboundState.IB_STATE_COMPLETE;}

    /**
     *  Queue a message to be sent after the session is established.
     *  This will only happen if we decide to send something during establishment
     *  @since 0.9.2
     */
    public void addMessage(OutNetMessage msg) {
        // chance of a duplicate here in a race, that's ok
        if (!_queuedMessages.contains(msg)) {_queuedMessages.offer(msg);}
        else if (_log.shouldWarn()) {_log.warn("Attempt to add duplicate messsage to queue: " + msg);}
   }

    /**
     *  Pull from the message queue
     *  @return null if none
     *  @since 0.9.2
     */
    public OutNetMessage getNextQueuedMessage() {return _queuedMessages.poll();}

    /**
     *  Whether the SessionRequest message has been received from Alice.
     *
     *  @return true if the initial request has arrived
     */
    public synchronized boolean sessionRequestReceived() {return _receivedX != null;}

    /**
     *  The received X value from Alice's SessionRequest message.
     *
     *  @return session request data or null
     */
    public synchronized byte[] getReceivedX() {return _receivedX;}

    /**
     *  Our IP address as reported by Alice in the SessionRequest.
     *
     *  @return our IP bytes or null
     */
    public synchronized byte[] getReceivedOurIP() {return _bobIP;}

    /**
     *  Whether Alice requested an introduction.
     *  True by default if no extended options in session request,
     *  or the value of the introduction flag bit in the extended options.
     *
     *  @return true if introduction was requested
     *  @since 0.9.24
     */
    public synchronized boolean isIntroductionRequested() {return _introductionRequested;}

    /**
     *  Alice's apparent IP address.
     *
     *  @return the IP address Alice appears to be connecting from
     */
    public byte[] getSentIP() {return _aliceIP;}

    /**
     *  Alice's apparent port number.
     *
     *  @return the port Alice appears to be connecting from
     */
    public int getSentPort() {return _alicePort;}

    /**
     *  Mark this establishment attempt as failed.
     */
    public synchronized void fail() {_currentState = InboundState.IB_STATE_FAILED;}

    /**
     *  The relay tag we sent to Alice in the SessionCreated message.
     *
     *  @return the relay tag, or 0 if not yet sent
     */
    public synchronized long getSentRelayTag() {return _sentRelayTag;}

    /**
     *  Set the relay tag to include in the SessionCreated message.
     *
     *  @param tag the relay tag
     */
    public synchronized void setSentRelayTag(long tag) {_sentRelayTag = tag;}

    /**
     *  The signed-on time we sent to Alice.
     *
     *  @return the signed-on timestamp
     */
    public synchronized long getSentSignedOnTime() {return _sentSignedOnTime;}

    /**
     *  Record that a SessionCreated packet was just sent, update backoff state.
     */
    public synchronized void createdPacketSent() {
        _lastSend = _context.clock().now();
        long delay;
        if (_createdSentCount == 0) {delay = RETRANSMIT_DELAY;}
        else {delay = Math.min(RETRANSMIT_DELAY << _createdSentCount, getMaxDelay());}
        _createdSentCount++;
        _nextSend = _lastSend + delay;
        if ((_currentState == InboundState.IB_STATE_UNKNOWN) ||
            (_currentState == InboundState.IB_STATE_REQUEST_RECEIVED)) {
            _currentState = InboundState.IB_STATE_CREATED_SENT;
       }
   }

    /**
     *  How long have we been trying to establish this session?
     *
     *  @return the elapsed time in milliseconds
     */
    public long getLifetime() {return getLifetime(_context.clock().now());}

    /**
     *  How long have we been trying to establish this session, using the given time.
     *
     *  @param now the reference time
     *  @return the elapsed time in milliseconds
     *  @since 0.9.57
     */
    public long getLifetime(long now) {return now - _establishBegin;}

    /**
     *  The wall clock time when this establishment attempt began.
     *
     *  @return the start timestamp
     */
    public long getEstablishBeginTime() {return _establishBegin;}

    /**
     *  The next time at which we should send a packet for this establishment.
     *  Returns the receive time after receiving a packet (including after constructor),
     *  or the send time plus backoff delay after sending a packet.
     *
     *  @return the next scheduled send time
     */
    public synchronized long getNextSendTime() {return _nextSend;}

    /**
     *  The estimated round-trip time for this establishment.
     *
     *  @return the RTT in milliseconds
     */
    synchronized int getRTT() {return _rtt;}

    /**
     *  The RemoteHostId that uniquely identifies this establishment attempt.
     *
     *  @return the remote host identifier
     */
    RemoteHostId getRemoteHostId() {return _remoteHostId;}

    /**
     *  Have we fully received the SessionConfirmed messages from Alice?
     *  Caller must synchronize on this.
     *
     *  @return true if all fragments have been received
     */
    protected boolean confirmedFullyReceived() {
        if (_receivedIdentity != null) {
            for (int i = 0; i < _receivedIdentity.length; i++) {
                if (_receivedIdentity[i] == null) {return false;}
            }
            return true;
        } else {return false;}
    }

    /**
     *  The confirmed identity of Alice (null if forged/unknown).
     *  Note that this isn't really confirmed - see below.
     *
     *  @return Alice's RouterIdentity, or null
     */
    public synchronized RouterIdentity getConfirmedIdentity() {
        return _receivedConfirmedIdentity;
    }

    /**
     *  Update the next send time to now, as we just received a packet.
     *  Must call from synchronized method only.
     */
    protected void packetReceived() {_nextSend = _context.clock().now();}

    /**
     *  Return a human-readable description of this establish state.
     *
     *  @return debug string with peer address, lifetime, relay tag, and state
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("InboundEstablishState ");
        buf.append(Addresses.toString(_aliceIP, _alicePort));
        buf.append("\n* Lifetime: ").append(DataHelper.formatDuration(getLifetime()));
        if (_sentRelayTag > 0) {buf.append("; RelayTag: ").append(_sentRelayTag);}
        buf.append(" -> ").append(_currentState);
        return buf.toString();
   }

}
