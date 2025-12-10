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
    protected final RouterContext _context;
    protected final Log _log;
    private byte _receivedX[]; // SessionRequest message
    protected byte _bobIP[];
    protected final int _bobPort;
    protected final byte _aliceIP[]; // SessionCreated message
    protected final int _alicePort;
    protected long _sentRelayTag;
    private long _sentSignedOnTime;
    private byte _receivedIdentity[][]; // SessionConfirmed messages - fragmented in theory but not in practice - see below
    private long _receivedSignedOnTime;
    private byte _receivedSignature[];
    protected RouterIdentity _receivedUnconfirmedIdentity; // sig not verified
    protected RouterIdentity _receivedConfirmedIdentity; // identical to uncomfirmed, but sig now verified
    protected final long _establishBegin; // general status
    //private long _lastReceive;
    protected long _lastSend;
    protected long _nextSend;
    protected final RemoteHostId _remoteHostId;
    protected InboundState _currentState;
    private final Queue<OutNetMessage> _queuedMessages;
    protected int _createdSentCount; // count for backoff
    protected boolean _introductionRequested; // default true for SSU 1, false for SSU 2
    protected int _rtt;

    /**
     * States for inbound SSU session establishment.
     * Tracks the progression of incoming connection setup.
     */
    public enum InboundState {
        IB_STATE_UNKNOWN, /** nothin' known yet */
        IB_STATE_REQUEST_RECEIVED, /** we have received an initial request */
        IB_STATE_CREATED_SENT, /** we have sent a signed creation packet */
        IB_STATE_CONFIRMED_PARTIALLY, /** we have received one but not all the confirmation packets - never happens in practice - see below. */
        IB_STATE_CONFIRMED_COMPLETELY, /** we have all the confirmation packets */
        IB_STATE_FAILED, /** we are explicitly failing it */
        IB_STATE_COMPLETE, /** Successful completion, PeerState created and added to transport */

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
    protected static final long MAX_DELAY = EstablishmentManager.MAX_IB_ESTABLISH_TIME;

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
        _queuedMessages = new LinkedBlockingQueue<OutNetMessage>();
   }

    /**
     * @since 0.9.54
     */
    public int getVersion() {return 1;}

    public synchronized InboundState getState() {return _currentState;}

    /** @return if previously complete */
    public synchronized boolean isComplete() {
        return _currentState == InboundState.IB_STATE_COMPLETE ||
               _currentState == InboundState.IB_STATE_FAILED;
   }

    /** Notify successful completion */
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

    public synchronized boolean sessionRequestReceived() {return _receivedX != null;}
    public synchronized byte[] getReceivedX() {return _receivedX;}
    public synchronized byte[] getReceivedOurIP() {return _bobIP;}

    /**
     *  True (default) if no extended options in session request,
     *  or value of flag bit in the extended options.
     *  @since 0.9.24
     */
    public synchronized boolean isIntroductionRequested() {return _introductionRequested;}

    /** What IP do they appear to be on? */
    public byte[] getSentIP() {return _aliceIP;}

    /** What port number do they appear to be coming from? */
    public int getSentPort() {return _alicePort;}

    public synchronized void fail() {_currentState = InboundState.IB_STATE_FAILED;}

    public synchronized long getSentRelayTag() {return _sentRelayTag;}
    public synchronized void setSentRelayTag(long tag) {_sentRelayTag = tag;}
    public synchronized long getSentSignedOnTime() {return _sentSignedOnTime;}

    /** Note that we just sent a SessionCreated packet */
    public synchronized void createdPacketSent() {
        _lastSend = _context.clock().now();
        long delay;
        if (_createdSentCount == 0) {delay = RETRANSMIT_DELAY;}
        else {delay = Math.min(RETRANSMIT_DELAY << _createdSentCount, MAX_DELAY);}
        _createdSentCount++;
        _nextSend = _lastSend + delay;
        if ((_currentState == InboundState.IB_STATE_UNKNOWN) ||
            (_currentState == InboundState.IB_STATE_REQUEST_RECEIVED)) {
            _currentState = InboundState.IB_STATE_CREATED_SENT;
       }
   }

    /**
     * How long have we been trying to establish this session?
     */
    public long getLifetime() {return getLifetime(_context.clock().now());}

    /**
     * How long have we been trying to establish this session?
     * @since 0.9.57
     */
    public long getLifetime(long now) {return now - _establishBegin;}

    public long getEstablishBeginTime() {return _establishBegin;}

    /**
     *  @return rcv time after receiving a packet (including after constructor),
     *          send time + delay after sending a packet
     */
    public synchronized long getNextSendTime() {return _nextSend;}

    synchronized int getRTT() {return _rtt;}

    /** RemoteHostId, uniquely identifies an attempt */
    RemoteHostId getRemoteHostId() {return _remoteHostId;}

    /**
     *  Have we fully received the SessionConfirmed messages from Alice?
     *  Caller must synch on this.
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
     * Who is Alice (null if forged/unknown)
     *
     * Note that this isn't really confirmed - see below.
     */
    public synchronized RouterIdentity getConfirmedIdentity() {
        return _receivedConfirmedIdentity;
   }

    /**
     *  Call from synchronized method only
     */
    protected void packetReceived() {_nextSend = _context.clock().now();}

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
