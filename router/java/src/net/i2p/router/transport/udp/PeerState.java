package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.util.CachedIteratorCollection;
import net.i2p.router.util.PriBlockingQueue;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Contain all of the state about a UDP connection to a peer.
 * This is instantiated only after a connection is fully established.
 *
 * Public only for UI peers page. Not a public API, not for external use.
 *
 */
public class PeerState {
    private static final long WARN_THROTTLE_MS = 5_000;
    private static final AtomicLong _lastOutboundFailWarn = new AtomicLong(0);

    protected final RouterContext _context;
    protected final Log _log;
    /**
     * The peer are we talking to.  This should be set as soon as this
     * state is created if we are initiating a connection, but if we are
     * receiving the connection this will be set only after the connection
     * is established.
     */
    protected final Hash _remotePeer;

    protected final long _keyEstablishedTime;

    /**
     *  How far off is the remote peer from our clock, in milliseconds?
     *  A positive number means our clock is ahead of theirs.
     */
    private long _clockSkew;
    private final Object _clockSkewLock = new Object();

    /** when did we last send them a packet? */
    private long _lastSendTime;
    /** when did we last send them a message that was ACKed */
    private long _lastSendFullyTime;
    /** when did we last send them a ping? */
    private long _lastPingTime;
    /** when did we last receive a packet from them? */
    private long _lastReceiveTime;
    /** how many consecutive messages have we sent and not received an ACK to */
    private int _consecutiveFailedSends;
    /** when did we last send ACKs to the peer? */
    protected volatile long _lastACKSend;
    /** when did we decide we need to ACK to this peer? */
    protected volatile long _wantACKSendSince;
    /** how many bytes should we send to the peer in a second */
    private volatile int _sendWindowBytes;
    /** how many bytes can we send to the peer in the current second */
    private volatile int _sendWindowBytesRemaining;
    private final Object _sendWindowBytesRemainingLock = new Object();
    private final SimpleBandwidthEstimator _bwEstimator;
    // smoothed value, for display only
    private int _receiveBps;
    private int _receiveBytes;
    private long _receivePeriodBegin;
    private volatile long _lastCongestionOccurred;
    /**
     * when sendWindowBytes is below this, grow the window size quickly,
     * but after we reach it, grow it slowly
     *
     */
    private volatile int _slowStartThreshold;
    /** what IP is the peer sending and receiving packets on? */
    protected final byte[] _remoteIP;
    /** cached IP address */
    protected volatile InetAddress _remoteIPAddress;
    /** what port is the peer sending and receiving packets on? */
    protected volatile int _remotePort;
    /** cached RemoteHostId, used to find the peerState by remote info */
    protected volatile RemoteHostId _remoteHostId;

    /**
     * if we are serving as an introducer to them, this is the the tag that
     * they can publish that, when presented to us, will cause us to send
     * a relay introduction to the current peer
     */
    private long _weRelayToThemAs;
    /**
     * If they have offered to serve as an introducer to us, this is the tag
     * we can use to publish that fact.
     */
    private long _theyRelayToUsAs;
    /** what is the largest packet we can currently send to the peer? */
    protected int _mtu;
    private int _mtuReceive;
    /** what is the largest packet we will ever send to the peer? */
    private int _largeMTU;
    private final int _minMTU;
    /* how many consecutive packets at or under the min MTU have been received */
    private long _consecutiveSmall;
    private int _mtuIncreases;
    private int _mtuDecreases;
    /** current round trip time estimate */
    protected volatile int _rtt;
    /** smoothed mean deviation in the rtt */
    private volatile int _rttDeviation;
    /** current retransmission timeout */
    private volatile int _rto;

    /** how many packets will be considered within the retransmission rate calculation */
    static final long RETRANSMISSION_PERIOD_WIDTH = SystemVersion.isSlow() ? 100 : 200;

    private int _messagesReceived;
    private final AtomicInteger _messagesSent = new AtomicInteger();
    private int _packetsTransmitted;
    /** how many packets were retransmitted within the last RETRANSMISSION_PERIOD_WIDTH packets */
    private int _packetsRetransmitted;
    private long _nextSequenceNumber;
    private final AtomicBoolean _fastRetransmit = new AtomicBoolean();

    /** how many dup packets were received within the last RETRANSMISSION_PERIOD_WIDTH packets */
    protected int _packetsReceivedDuplicate;
    private int _packetsReceived;
    private volatile boolean _mayDisconnect;

    /** list of InboundMessageState for active message */
    protected final ConcurrentMap<Long, InboundMessageState> _inboundMessages;

    /**
     *  Mostly messages that have been transmitted and are awaiting acknowledgement,
     *  although there could be some that have not been sent yet.
     */
    private final CachedIteratorCollection<OutboundMessageState> _outboundMessages;

    /**
     *  Priority queue of messages that have not yet been sent.
     *  They are taken from here and put in _outboundMessages.
     */
    //private final CoDelPriorityBlockingQueue<OutboundMessageState> _outboundQueue;
    private final PriBlockingQueue<OutboundMessageState> _outboundQueue;

    /** when the retransmit timer is about to trigger */
    private long _retransmitTimer;

    protected final UDPTransport _transport;

    /** have we migrated away from this peer to another newer one? */
    protected volatile boolean _dead;

    /** The minimum number of outstanding messages (NOT fragments/packets) */
    private static final int MIN_CONCURRENT_MSGS = SystemVersion.isSlow() ? 16 : 64;
    /** @since 0.9.42 */
    private static final int INIT_CONCURRENT_MSGS = SystemVersion.isSlow() ? 64 : 512;
    /** how many concurrent outbound messages do we allow OutboundMessageFragments to send
        This counts full messages, NOT fragments (UDP packets)
     */
    private int _concurrentMessagesAllowed = INIT_CONCURRENT_MSGS;
    /** how many concurrency rejections have we had in a row */
    private int _consecutiveRejections;
    /** is it inbound? **/
    protected final boolean _isInbound;
    /** Last time it was made an introducer **/
    private long _lastIntroducerTime;

    private static final int MAX_SEND_WINDOW_BYTES = SystemVersion.isSlow() ? 32*1024 : 128*1024;

    /**
     *  Was 32 before 0.9.2, but since the streaming lib goes up to 128,
     *  we would just drop our own msgs right away during slow start.
     *  May need to adjust based on memory.
     */
    private static final int MAX_SEND_MSGS_PENDING = SystemVersion.isSlow() ? 96 : 128;

    /**
     * IPv4 Min MTU
     *
     * 596 gives us 588 IP byes, 568 UDP bytes, and with an SSU data message,
     * 522 fragment bytes, which is enough to send a tunnel data message in 2
     * packets. A tunnel data message sent over the wire is 1044 bytes, meaning
     * we need 522 fragment bytes to fit it in 2 packets - add 46 for SSU, 20
     * for UDP, and 8 for IP, giving us 596.  round up to mod 16, giving a total
     * of 608
     *
     * Well, we really need to count the acks as well, especially
     * 1 + (4 * MAX_RESEND_ACKS_SMALL) which can take up a significant amount of space.
     * We reduce the max acks when using the small MTU but it may not be enough...
     *
     * Goal: VTBM msg fragments 2646 / (620 - 87) fits nicely.
     *
     * Assuming that we can enforce an MTU correctly, this % 16 should be 12,
     * as the IP/UDP header is 28 bytes and data max should be mulitple of 16 for padding efficiency,
     * and so PacketBuilder.buildPacket() works correctly.
     */
    public static final int MIN_MTU = 620;

    /**
     * IPv6/UDP header is 48 bytes, so we want MTU % 16 == 0.
     */
    public static final int MIN_IPV6_MTU = 1280;
    public static final int MAX_IPV6_MTU = 1488;
    private static final int DEFAULT_MTU = MIN_MTU;

    /**
     * IPv4 Max MTU
     *
     * based on measurements, 1350 fits nearly all reasonably small I2NP messages
     * (larger I2NP messages may be up to 1900B-4500B, which isn't going to fit
     * into a live network MTU anyway)
     *
     * TODO
     * VTBM is 2646, it would be nice to fit in two large
     * 2646 / 2 = 1323
     * 1323 + 74 + 46 + 1 + (4 * 9) = 1480
     * So why not make it 1492 (old ethernet is 1492, new is 1500)
     * Changed to 1492 in 0.8.9
     *
     * BUT through 0.8.11,
     * Size estimate was bad, actual packet was up to 48 bytes bigger
     * To be figured out. Curse the ACKs.
     * Assuming that we can enforce an MTU correctly, this % 16 should be 12,
     * as the IP/UDP header is 28 bytes and data max should be mulitple of 16 for padding efficiency,
     * and so PacketBuilder.buildPacket() works correctly.
     */
    public static final int LARGE_MTU = 1484;

    /**
     *  Max of IPv4 and IPv6 max MTUs
     *  @since 0.9.28
     */
    public static final int MAX_MTU = Math.max(LARGE_MTU, MAX_IPV6_MTU);

    /** Amount to adjust up or down in adjustMTU() - should be multiple of 16, at least for SSU 1 */
    private static final int MTU_STEP = 64;

    private static final int MIN_RTO = 1000;
    private static final int INIT_RTO = 1000;
    private static final int INIT_RTT = 0;
    private static final int MAX_RTO = 60*1000;
    /** How frequently do we want to send ACKs to a peer? */
    protected static final int ACK_FREQUENCY = 300;
    protected static final int CLOCK_SKEW_FUDGE = (ACK_FREQUENCY * 2) / 3;

    /**
     *  The max number of acks we save to send as duplicates
     */
    private static final int MAX_RESEND_ACKS = 32;
    /**
     *  The max number of duplicate acks sent in each ack-only messge.
     *  Doesn't really matter, we have plenty of room...
     *  @since 0.7.13
     */
    private static final int MAX_RESEND_ACKS_LARGE = MAX_RESEND_ACKS * 2 / 3;
    /** for small MTU */
    private static final int MAX_RESEND_ACKS_SMALL = MAX_RESEND_ACKS * 2 / 5;

    private static final long RESEND_ACK_TIMEOUT = 60*1000;

    /** If this many acks arrive out of order, fast rtx */
    private static final int FAST_RTX_ACKS = 3;

    /** @since 0.9.68+ */
    private final Object _outboundLock = new Object() {
        private static final long serialVersionUID = 1L;
    };
    private final Object _inboundLock = new Object();

    /**
     *  For SSU2
     *
     *  @since 0.9.54
     */
    protected PeerState(RouterContext ctx, UDPTransport transport,
                        InetSocketAddress addr, Hash remotePeer, boolean isInbound, int rtt) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _transport = transport;
        long now = ctx.clock().now();
        _keyEstablishedTime = now;
        _lastSendTime = now;
        _lastReceiveTime = now;
        _slowStartThreshold = MAX_SEND_WINDOW_BYTES/2;
        _receivePeriodBegin = now;
        _remoteIP = addr.getAddress().getAddress();
        _remotePort = addr.getPort();
        _mtu = PeerState2.MIN_MTU;
        _mtuReceive = PeerState2.MIN_MTU;
        if (_remoteIP.length == 4) {_largeMTU = transport.getSSU2MTU(false);}
        else {_largeMTU = transport.getSSU2MTU(true);}
        _minMTU = PeerState2.MIN_MTU;
        // RFC 5681 sec. 3.1
        _sendWindowBytes = 3 * _mtu;
        _sendWindowBytesRemaining = _sendWindowBytes;
        _rto = INIT_RTO;
        _rtt = INIT_RTT;
        if (rtt > 0) {recalculateTimeouts(rtt);}
        else {_rttDeviation = _rtt;}

        _inboundMessages = new ConcurrentHashMap<>(16);
        _outboundMessages = new CachedIteratorCollection<OutboundMessageState>();
        _outboundQueue = new PriBlockingQueue<OutboundMessageState>(ctx, "UDP-PeerState", 16);
        _remotePeer = remotePeer;
        _isInbound = isInbound;
        _remoteHostId = new RemoteHostId(_remoteIP, _remotePort);
        _bwEstimator = new SimpleBandwidthEstimator(ctx, this);
    }

    /**
     * @since 0.9.54
     */
    public int getVersion() {return 1;}

    /**
     *  Caller should sync; UDPTransport must remove and add to peersByRemoteHost map
     *  @since 0.9.3
     */
    void changePort(int newPort) {
        if (newPort != _remotePort) {
            _remoteHostId = new RemoteHostId(_remoteIP, newPort);
            _remotePort = newPort;
        }
    }

    /**
     * The peer are we talking to. Non-null.
     */
    public Hash getRemotePeer() {return _remotePeer;}

    /**
     * When were the current cipher and MAC keys established/rekeyed?
     * This is the connection uptime.
     */
    public long getKeyEstablishedTime() {return _keyEstablishedTime;}

    /**
     *  How far off is the remote peer from our clock, in milliseconds?
     *  A positive number means our clock is ahead of theirs.
     */
    public long getClockSkew() {return _clockSkew ;}

    /**
     *  When did we last send them a packet?
     *  Set for data, relay, and peer test, but not acks, pings, or termination
     */
    public long getLastSendTime() {return _lastSendTime;}

    /** when did we last send them a message that was ACKed? */
    public long getLastSendFullyTime() {return _lastSendFullyTime;}

    /**
     *  When did we last receive a packet from them?
     *  Set for data, relay, and peer test, but not acks, pings, or termination
     */
    public long getLastReceiveTime() {return _lastReceiveTime;}

    /** how many seconds have we sent packets without any ACKs received? */
    public int getConsecutiveFailedSends() {return _consecutiveFailedSends;}

    /**
     *  how many bytes should we send to the peer in a second
     *  1st stat in CWND column, otherwise unused,
     *  candidate for removal
     */
    public int getSendWindowBytes() {
        synchronized(_sendWindowBytesRemainingLock) {return _sendWindowBytes;}
    }

    /** how many bytes can we send to the peer in the current second */
    public int getSendWindowBytesRemaining() {
        synchronized(_sendWindowBytesRemainingLock) {return _sendWindowBytesRemaining;}
    }

    /** what IP is the peer sending and receiving packets on? */
    public byte[] getRemoteIP() {return _remoteIP;}

    /**
     *  @return may be null if IP is invalid
     */
    public InetAddress getRemoteIPAddress() {
        if (_remoteIPAddress == null) {
            try {_remoteIPAddress = InetAddress.getByAddress(_remoteIP);}
            catch (UnknownHostException uhe) {
                if (_log.shouldError()) {
                    _log.error("Invalid IP address? ", uhe);
                }
                return null;
            }
        }
        return _remoteIPAddress;
    }

    /** what port is the peer sending and receiving packets on? */
    public int getRemotePort() {return _remotePort;}

    /**
     * if we are serving as an introducer to them, this is the the tag that
     * they can publish that, when presented to us, will cause us to send
     * a relay introduction to the current peer
     * @return 0 (no relay) if unset previously
     */
    public long getWeRelayToThemAs() {return _weRelayToThemAs;}

    /**
     * If they have offered to serve as an introducer to us, this is the tag
     * we can use to publish that fact.
     * @return 0 (no relay) if unset previously
     */
    public long getTheyRelayToUsAs() {return _theyRelayToUsAs;}

    /** what is the largest packet we can send to the peer? */
    public int getMTU() {return _mtu;}

    /**
     *  Estimate how large the other side's MTU is.
     *  This could be wrong.
     *  It is used only for the HTML status.
     */
    public int getReceiveMTU() {return _mtuReceive;}

    /**
     *  Update the moving-average clock skew based on the current difference.
     *  The raw skew will be adjusted for RTT/2 here.
     *  A positive number means our clock is ahead of theirs.
     *  @param skew milliseconds, NOT adjusted for RTT.
     */
    void adjustClockSkew(long skew) {
        // the real one-way delay is much less than RTT / 2, due to ack delays,
        // so add a fudge factor
        long actualSkew = skew + CLOCK_SKEW_FUDGE - (_rtt / 2);
        // First time...
        // This is important because we need accurate
        // skews right from the beginning, since the median is taken
        // and fed to the timestamper. Lots of connections only send a few packets.
        if (_packetsReceived <= 1) {
            synchronized(_clockSkewLock) {_clockSkew = actualSkew;}
            return;
        }
        double adj = 0.1 * actualSkew;
        synchronized(_clockSkewLock) {_clockSkew = (long) (0.9*_clockSkew + adj);}
    }

    /**
     *  When did we last send them a packet?
     *  Set for data, relay, and peer test, but not acks, pings, or termination
     */
    void setLastSendTime(long when) {_lastSendTime = when;}

    /**
     *  When did we last receive a packet from them?
     *  Set for data, relay, and peer test, but not acks, pings, or termination
     */
    void setLastReceiveTime(long when) {_lastReceiveTime = when;}

    /**
     *  Note ping sent. Does not update last send time.
     *  @since 0.9.3
     */
    void setLastPingTime(long when) {_lastPingTime = when;}

    /**
     *  Latest of last sent, last ACK, last ping
     *  @since 0.9.3
     */
    long getLastSendOrPingTime() {
        return Math.max(Math.max(_lastSendTime, _lastACKSend), _lastPingTime);
    }

    /**
     * The Westwood+ bandwidth estimate
     * @return the smoothed send transfer rate
     */
    public int getSendBps(long now) {return (int) (_bwEstimator.getBandwidthEstimate(now) * 1000);}

    /**
     * An approximation, for display only
     * @return the smoothed receive transfer rate
     */
    public int getReceiveBps(long now) {
        synchronized (_inboundLock) {
            long duration = now - _receivePeriodBegin;
            if (duration >= 1000) {
                _receiveBps = (int)(0.9f*_receiveBps + 0.1f*(_receiveBytes * (1000f/duration)));
                _receiveBytes = 0;
                _receivePeriodBegin = now;
            }
            return _receiveBps;
        }
    }

    int incrementConsecutiveFailedSends() {
        synchronized(_outboundLock) {
            _consecutiveFailedSends++;
            return _consecutiveFailedSends;
        }
    }

    public long getInactivityTime() {
        long now = _context.clock().now();
        long lastActivity = Math.max(_lastReceiveTime, _lastSendFullyTime);
        return now - lastActivity;
    }

    /**
     * Decrement the remaining bytes in the current period's window,
     * returning true if the full size can be decremented, false if it
     * cannot.  If it is not decremented, the window size remaining is
     * not adjusted at all.
     *
     *  Caller should synch
     */
    private boolean allocateSendingBytes(OutboundMessageState state, long now) {
        int messagePushCount = state.getPushCount();
        if (messagePushCount == 0 && _outboundMessages.size() > _concurrentMessagesAllowed) {
            _consecutiveRejections++;
            _context.statManager().addRateData("udp.rejectConcurrentActive", _outboundMessages.size(), _consecutiveRejections);
            return false;
        }
        final int sendRemaining = getSendWindowBytesRemaining();
        if (sendRemaining <= fragmentOverhead()) {return false;}

        int size = state.getSendSize(_sendWindowBytesRemaining);
        if (size > 0) {
            if (messagePushCount == 0) {
                _context.statManager().addRateData("udp.allowConcurrentActive", _outboundMessages.size(), _concurrentMessagesAllowed);
                if (_consecutiveRejections > 0) {
                    _context.statManager().addRateData("udp.rejectConcurrentSequence", _consecutiveRejections, _outboundMessages.size());
                }
                _consecutiveRejections = 0;
            }
            synchronized(_sendWindowBytesRemainingLock) {_sendWindowBytesRemaining -= size;}
            _lastSendTime = now;
            return true;
        } else {return false;}
    }

    /**
     * If we are serving as an introducer to them, this is the the tag that they can publish that,
     * when presented to us, will cause us to send a relay introduction to the current peer
     * @param tag 1 to Integer.MAX_VALUE, or 0 if relaying disabled
     */
    void setWeRelayToThemAs(long tag) {_weRelayToThemAs = tag;}

    /**
     * If they have offered to serve as an introducer to us, this is the tag we can use to publish that fact.
     * @param tag 1 to Integer.MAX_VALUE, or 0 if relaying disabled
     */
    void setTheyRelayToUsAs(long tag) {_theyRelayToUsAs = tag;}

    /**
     *  Stat in SST column, otherwise unused - candidate for removal
     */
    public int getSlowStartThreshold() {return _slowStartThreshold;}

    /**
     *  2nd stat in CWND column, otherwise unused - candidate for removal
     */
    public int getConcurrentSends() {
        synchronized(_outboundLock) {return _outboundMessages.size();}
    }

    /**
     *  3rd stat in CWND column, otherwise unused,
     *  candidate for removal
     */
    public int getConcurrentSendWindow() {
        synchronized(_outboundLock) {return _concurrentMessagesAllowed;}
    }

    /**
     *  4th stat in CWND column, otherwise unused - candidate for removal
     */
    public int getConsecutiveSendRejections() {
        synchronized(_outboundLock) {return _consecutiveRejections;}
    }

    public boolean isInbound() {return _isInbound;}

    /** @since IPv6 */
    public boolean isIPv6() {return _remoteIP.length == 16;}

    /** the last time we used them as an introducer, or 0 */
    long getIntroducerTime() {return _lastIntroducerTime;}

    /** set the last time we used them as an introducer to now */
    void setIntroducerTime() {_lastIntroducerTime = _context.clock().now();}

    /**
     *  We received the message specified completely.
     *  @param bytes if less than or equal to zero, message is a duplicate.
     */
    void messageFullyReceived(Long messageId, int bytes) {
        long now = _context.clock().now();
        synchronized(_inboundLock) {
            if (bytes > 0) {
                _receiveBytes += bytes;
                _messagesReceived++;
            } else {_packetsReceivedDuplicate++;}

            long duration = now - _receivePeriodBegin;
            if (duration >= 1000) {
                _receiveBps = (int)(0.9f*_receiveBps + 0.1f*(_receiveBytes * (1000f/duration)));
                _receiveBytes = 0;
                _receivePeriodBegin = now;
            }
        }
        messagePartiallyReceived(now);
    }

    /**
     *  We received a partial message, or we want to send some acks.
     */
    void messagePartiallyReceived() {
        messagePartiallyReceived(_context.clock().now());
    }

    /**
     *  We received a partial message, or we want to send some acks.
     *  @since 0.9.52
     */
    protected void messagePartiallyReceived(long now) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fetch the internal id (Long) to InboundMessageState for incomplete inbound messages.
     * Access to this map must be synchronized explicitly!
     */
    Map<Long, InboundMessageState> getInboundMessages() {
        synchronized (_inboundLock) {
            return new HashMap<>(_inboundMessages); // safe copy
        }
    }

    /**
     * Expire partially received inbound messages, returning how many are still pending.
     * This should probably be fired periodically, in case a peer goes silent and we don't
     * try to send them any messages (and don't receive any messages from them either)
     *
     */
    int expireInboundMessages() {
        int rv = 0;
        for (Iterator<Map.Entry<Long, InboundMessageState>> iter = _inboundMessages.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<Long, InboundMessageState> entry = iter.next();
            InboundMessageState state = entry.getValue();
            if (state.isExpired() || state.isComplete() || _dead) {
                iter.remove();
            } else {
                rv++;
            }
        }
        return rv;
    }

    /**
     * Either they told us to back off, or we had to resend to get the data through.
     * Caller should synch on this
     */
    private void congestionOccurred() {
        long now = _context.clock().now();
        if (_lastCongestionOccurred + _rto > now) {return;} // only shrink once every few seconds
        _lastCongestionOccurred = now;
        // 1. Double RTO and backoff (RFC 6298 section 5.5 & 5.6)
        // 2. cut ssthresh to bandwidth estimate, window to 1 MTU
        // 3. Retransmit up to half of the packets in flight (RFC 6298 section 5.4 and RFC 5681 section 4.3)
        int congestionAt = _sendWindowBytes;
        // If we reduced the MTU, then we won't be able to send any previously-fragmented messages,
        // so set to the max MTU. This is the easiest fix, although it violates the RFC.
        //_sendWindowBytes = _mtu;
        int oldsst = _slowStartThreshold;
        float bwe;
        // window and SST set in highestSeqNumAcked()
        if (_fastRetransmit.get()) {bwe = -1;} // for log below
        else {
            _sendWindowBytes = getVersion() == 2 ? PeerState2.MAX_MTU : (isIPv6() ? MAX_IPV6_MTU : LARGE_MTU);
            bwe = _bwEstimator.getBandwidthEstimate(now);
            _slowStartThreshold = Math.max( (int)(bwe * _rtt), 2 * _mtu);
        }

        int oldRto = _rto;
        long oldTimer = _retransmitTimer - now;
        _rto = Math.min(MAX_RTO, Math.max(MIN_RTO, _rto << 1 ));
        _retransmitTimer = now + _rto;
        if (_log.shouldInfo()) {
            _log.info("[" + _remotePeer.toBase64().substring(0,6) + "] Estimated bandwidth: " +
                      DataHelper.formatSize2Decimal((long) (bwe * 1000), false) + "b/s \n* " +
                      "Congestion, RTO: " + oldRto + "ms -> " + _rto + "ms; Timer: " + oldTimer + "ms -> " + _rto +
                      "ms; Window: " + congestionAt + " bytes -> " + _sendWindowBytes +
                      " bytes; SST: " + oldsst + " -> " + _slowStartThreshold +
                      "; FastRetransmit? " + _fastRetransmit);
        }
    }

    /**
     *  We sent a message which was ACKed containing the given # of bytes.
     *  Caller should synch on this
     */
    private void locked_messageACKed(int bytesACKed, int maxPktSz, long lifetime, int numSends, boolean anyPending, boolean anyQueued) {
        _consecutiveFailedSends = 0;
        if (numSends < 2) {
            if (_context.random().nextInt(_concurrentMessagesAllowed) <= 0) {_concurrentMessagesAllowed++;}

            if (_sendWindowBytes <= _slowStartThreshold) {
                _sendWindowBytes += bytesACKed;
                synchronized(_sendWindowBytesRemainingLock) {_sendWindowBytesRemaining += bytesACKed;}
            } else {
                    float prob = ((float)bytesACKed) / ((float)(_sendWindowBytes<<1));
                    float v = _context.random().nextFloat();
                    if (v < 0) {v = 0-v;}
                    if (v <= prob) {
                        _sendWindowBytes += bytesACKed;
                        synchronized(_sendWindowBytesRemainingLock) {_sendWindowBytesRemaining += bytesACKed;}
                    }
            }
        } else {
            int allow = _concurrentMessagesAllowed - 1;
            if (allow < MIN_CONCURRENT_MSGS) {allow = MIN_CONCURRENT_MSGS;}
            _concurrentMessagesAllowed = allow;
        }
        if (_sendWindowBytes > MAX_SEND_WINDOW_BYTES) {_sendWindowBytes = MAX_SEND_WINDOW_BYTES;}
        long now = _context.clock().now();
        _lastSendFullyTime = now;

        synchronized(_sendWindowBytesRemainingLock) {
            _sendWindowBytesRemaining += bytesACKed;
            if (_sendWindowBytesRemaining > _sendWindowBytes) {_sendWindowBytesRemaining = _sendWindowBytes;}
        }

        if (numSends < 2) {
            // caller syncs
            recalculateTimeouts(lifetime);
            adjustMTU(maxPktSz, true);
        }

        if (!anyPending) {
            _retransmitTimer = 0;
            exitFastRetransmit();
        } else {
            // any time new data gets acked, push out the timer
            long oldTimer = _retransmitTimer - now;
            _retransmitTimer = now + getRTO();
            if (_log.shouldDebug()) {
                _log.debug("[" + _remotePeer.toBase64().substring(0,6) + "] ACK, timer: " + oldTimer + " -> " + (_retransmitTimer - now));
            }
        }
        if (anyPending || anyQueued) {_transport.getOMF().nudge();}
    }

    /**
     *  We sent a message which was ACKed containing the given # of bytes.
     */
    private void messageACKed(int bytesACKed, int maxPktSz, long lifetime, int numSends, boolean anyPending, boolean anyQueued) {
        synchronized(_sendWindowBytesRemainingLock) {
            locked_messageACKed(bytesACKed, maxPktSz, lifetime, numSends, anyPending, anyQueued);
            _bwEstimator.addSample(bytesACKed);
        }
        if (numSends >= 2 && _log.shouldDebug()) {
            _log.debug("[" + _remotePeer.toBase64().substring(0,6) + "] ACKed after numSends=" + numSends +
                       " with lifetime=" + lifetime + " and size=" + bytesACKed);
        }
    }

    /** This is the value specified in RFC 2988 */
    private static final float RTT_DAMPENING = 0.125f;

    /**
     *  Adjust the tcp-esque timeouts.
     *  Caller should synch on this
     */
    private void recalculateTimeouts(long lifetime) {
        if (_rtt <= 0) {
            // first measurement
            _rtt = (int) lifetime;
            _rttDeviation = _rtt /  2;
        } else {
            // the rttDev calculation matches that recommended in RFC 2988 (beta = 1/4)
            _rttDeviation = (int)((0.75 * _rttDeviation) + (0.25 * Math.abs(lifetime - _rtt)));
            _rtt = (int)((_rtt * (1.0f - RTT_DAMPENING)) + (RTT_DAMPENING * lifetime));
        }
        // K = 4
        _rto = Math.min(MAX_RTO, Math.max(MIN_RTO, _rtt + (_rttDeviation<<2)));
    }

    /**
     *  Adjust upward if a large packet was successfully sent without retransmission.
     *  Adjust downward if a packet was retransmitted.
     *
     *  Caller should synch on this
     *
     *  @param maxPktSz the largest packet that was sent
     *  @param success was it sent successfully?
     */
    private void adjustMTU(int maxPktSz, boolean success) {
        if (_packetsTransmitted > 0) {
            // heuristic to allow fairly lossy links to use large MTUs
            boolean wantLarge = success &&
                                (float)_packetsRetransmitted / (float)_packetsTransmitted < 0.10f;
            // we only increase if the size was close to the limit
            if (wantLarge) {
                if (_mtu < _largeMTU && maxPktSz > _mtu - (MTU_STEP * 2) &&
                    (_mtuDecreases <= 1 || _context.random().nextInt(_mtuDecreases) <= 0)){
                    _mtu = Math.min(_mtu + MTU_STEP, _largeMTU);
                    _mtuIncreases++;
                    _mtuDecreases = 0;
                    _context.statManager().addRateData("udp.mtuIncrease", _mtuIncreases);
                    if (_log.shouldDebug()) {
                        _log.debug("Increased MTU after " + maxPktSz + " byte packet acked on " + this);
                    }
                }
            } else {
                if (_mtu > _minMTU && maxPktSz > _mtu - (MTU_STEP * 4)) {
                    _mtu = Math.max(_mtu - MTU_STEP, _minMTU);
                    _mtuDecreases++;
                    _mtuIncreases = 0;
                    _context.statManager().addRateData("udp.mtuDecrease", _mtuDecreases);
                    if (_log.shouldDebug()) {
                        _log.debug("Decreased MTU after " + maxPktSz + " byte packet retx on " + this);
                    }
                }
            }
        }
    }

    /**
     *  @since 0.9.2
     */
    void setHisMTU(int mtu) {
        synchronized (_outboundLock) {
            if (mtu <= _minMTU || mtu >= _largeMTU) {return;}
            if (mtu < _largeMTU) {_largeMTU = mtu;}
            if (mtu < _mtu) {_mtu = mtu;}
        }
    }

    /** we are resending a packet, so let's jack up the rto */
    void messageRetransmitted(int packets, int maxPktSz) {
        synchronized(_outboundLock) {
            _context.statManager().addRateData("udp.congestionOccurred", _sendWindowBytes);
            _context.statManager().addRateData("udp.congestedRTO", _rto, _rttDeviation);
            _packetsRetransmitted += packets;
            congestionOccurred();
            adjustMTU(maxPktSz, false);
        }
    }

    void packetsTransmitted(int packets) {
        synchronized(_outboundLock) {
            _packetsTransmitted += packets;
        }
    }

    /** How long does it usually take to get a message ACKed? */
    public int getRTT() {return _rtt;}
    /** How soon should we retransmit an unacked packet? */
    public int getRTO() {return _rto;}
    /** How skewed are the measured RTTs? */
    public int getRTTDeviation() {return _rttDeviation;}

    /**
     *  I2NP messages sent - does not include duplicates.
     *  As of 0.9.24, incremented when bandwidth is allocated just before sending, not when acked.
     */
     public int getMessagesSent() {
       synchronized(_outboundLock) {return _messagesSent.get();}
     }

    /**
     *  I2NP messages received.
     *  As of 0.9.24, does not include duplicates.
     */
    public int getMessagesReceived() {
        synchronized (_inboundLock) {return _messagesReceived;}
    }

    public int getPacketsTransmitted() {
        synchronized (_outboundLock) {return _packetsTransmitted;}
    }

    public int getPacketsRetransmitted() {
        synchronized (_outboundLock) {return _packetsRetransmitted;}
    }

    public int getPacketsReceived() {
        synchronized (_inboundLock) {return _packetsReceived;}
    }

    public int getPacketsReceivedDuplicate() {
        synchronized (_inboundLock) {return _packetsReceivedDuplicate;}
    }

    private static final int MTU_RCV_DISPLAY_THRESHOLD = 20;
    /** 60 */
    private static final int OVERHEAD_SIZE = PacketBuilder.IP_HEADER_SIZE + PacketBuilder.UDP_HEADER_SIZE +
                                             UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
    /** 80 */
    private static final int IPV6_OVERHEAD_SIZE = PacketBuilder.IPV6_HEADER_SIZE + PacketBuilder.UDP_HEADER_SIZE +
                                                  UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;

    /**
     *  @param size not including IP header, UDP header, MAC or IV
     */
    void packetReceived(int size) {
        synchronized(_inboundLock) {
            _packetsReceived++;
            // SSU2 overhead header + MAC == SSU overhead IV + MAC
            if (_remoteIP.length == 4) {size += OVERHEAD_SIZE;}
            else {size += IPV6_OVERHEAD_SIZE;}
            if (size <= _minMTU) {
                _consecutiveSmall++;
                if (_consecutiveSmall >= MTU_RCV_DISPLAY_THRESHOLD) {_mtuReceive = _minMTU;}
            } else {
                _consecutiveSmall = 0;
                if (size > _mtuReceive) {_mtuReceive = size;}
            }
        }
    }

    /**
     *  We received a backoff request, so cut our send window.
     *  NOTE: ECN sending is unimplemented, this is never called.
     */
    void ECNReceived() {
        synchronized(this) {congestionOccurred();}
        _context.statManager().addRateData("udp.congestionOccurred", _sendWindowBytes);
    }

    /**
     *  Same as setLastReceivedTime(now)
     */
    void dataReceived() {_lastReceiveTime = _context.clock().now();}

    /** When did we last send an ACK to the peer? */
    public long getLastACKSend() {return _lastACKSend;}

    /**
     *  All acks have been sent - SSU 1 only, see override
     *
     *  @since 0.9.52
     */
    void clearWantedACKSendSince() {throw new UnsupportedOperationException();}

    /**
     *  @return non-null
     *  @since public since 0.9.57 for SSU2Sender interface only
     */
    public RemoteHostId getRemoteHostId() {return _remoteHostId;}

    /*
     *  TODO:
     *  - Should this use a queue, separate from the list of msgs pending an ack?
     *  - Bring back tail drop?
     *  - Priority queue? (we don't implement priorities in SSU now)
     *  - Backlog / pushback / block instead of dropping? Can't really block here.
     *
     *  SSU does not support isBacklogged() now
     */
    void add(OutboundMessageState state) {
        synchronized (_outboundLock) {
            if (_dead) {
                _transport.failed(state, false);
                return;
            }
            if (state.getPeer() != this) {
                if (_log.shouldWarn()) _log.warn("Not for me!", new Exception("I did it"));
                _transport.failed(state, false);
                return;
            }

            boolean fail;
            fail = !_outboundQueue.offer(state);
            state.setSeqNum(_nextSequenceNumber++);

            if (fail) {
                if (_log.shouldWarn()) {
                    _log.warn("Dropping message, Outbound queue full for " + toString());
                }
                _transport.failed(state, false);
            }
        }
    }

    /** drop all outbound messages */
    void dropOutbound() {
        _dead = true;
        List<OutboundMessageState> tempList;
        synchronized (_outboundLock) {
            tempList = new ArrayList<OutboundMessageState>(_outboundMessages);
            _outboundMessages.clear();
        }
        synchronized (_outboundQueue) {_outboundQueue.drainTo(tempList);}
        for (OutboundMessageState oms : tempList) {_transport.failed(oms, false);}
        // so the ACKSender will drop this peer from its queue
        _wantACKSendSince = 0;
    }

    /**
     * @return number of active outbound messages remaining (unsynchronized)
     */
    public int getOutboundMessageCount() {
        if (_dead) return 0;
        return _outboundMessages.size() + _outboundQueue.size();
    }

    /**
     * Sets to true.
     * @since 0.9.24
     */
    public void setMayDisconnect() {_mayDisconnect = true;}

    /**
     * @since 0.9.24
     */
    public boolean getMayDisconnect() {return _mayDisconnect;}

    /**
     * Processes outbound messages by expiring overdue messages and completing messages marked as done,
     * updating stats and triggering callbacks. Returns the total count of active plus queued messages.
     *
     * @param now current time in milliseconds for expiration checks
     * @return total count of active outbound messages plus those queued for sending
     */
    int finishMessages(long now) {
        if (_outboundMessages.isEmpty()) {return _outboundQueue.size();}
        if (_dead) {
            dropOutbound();
            return 0;
        }

        List<OutboundMessageState> succeeded = new ArrayList<>(16);
        List<OutboundMessageState> failed = new ArrayList<>(8);

        boolean shouldLogInfo = _log.shouldInfo();
        boolean shouldLogWarn = _log.shouldWarn();

        int failedSize = 0;
        int failedCount = 0;
        boolean totalFail = false;

        int rv;
        synchronized (_outboundLock) {
            Iterator<OutboundMessageState> iter = _outboundMessages.iterator();
            while (iter.hasNext()) {
                OutboundMessageState state = iter.next();

                if (state.isComplete()) {
                    iter.remove();
                    succeeded.add(state);
                    continue;
                }

                boolean isFailed = state.isExpired(now) || state.getMaxSends() > OutboundMessageFragments.MAX_VOLLEYS;
                if (isFailed) {
                    iter.remove();
                    String statKey = state.isExpired(now) ? "udp.sendFailed" : "udp.sendAggressiveFailed";
                    _context.statManager().addRateData(statKey, state.getPushCount());
                    failed.add(state);

                    failedSize += state.getUnackedSize();
                    failedCount += state.getUnackedFragments();

                    OutNetMessage msg = state.getMessage();
                    if (msg != null && !_isInbound && state.getSeqNum() == 0) {
                        totalFail = true;
                    }
                }
            }
            rv = _outboundMessages.size();
        }

        for (OutboundMessageState state : succeeded) {
            _transport.succeeded(state);
            OutNetMessage msg = state.getMessage();
            if (msg != null) {
                msg.timestamp("sending complete");
            }
        }

        if (!failed.isEmpty()) {
            Hash hash = getRemoteHostId() != null ? getRemoteHostId().getPeerHash() : null;
            boolean isBanned = hash != null && _context.banlist().isBanlisted(hash);
            if (isBanned) {
                // don't bother logging if the router is banned
                shouldLogInfo = false;
                shouldLogWarn = false;
            }

            for (OutboundMessageState state : failed) {
                OutNetMessage msg = state.getMessage();
                if (msg != null) {
                    msg.timestamp("Expired in the active pool");
                    _transport.failed(state);
                    if (shouldLogInfo) {
                        _log.info("[SSU] Message expired " + state + " \n* " + this);
                    }
                } else {
                    if (shouldLogInfo) {
                        _log.warn("[SSU] Unable to send direct message " + state + " \n* " + this);
                    }
                }
            }

            if (failedSize > 0) {
                if (totalFail) {
                    if (shouldLogWarn) {
                        long currentTime = _context.clock().now();
                        if (_lastOutboundFailWarn.getAndSet(currentTime) < currentTime - WARN_THROTTLE_MS) {
                            _log.warn("[SSU] First Outbound message failed (Timeout after 60s) \n* " + this + " (throttled)");
                        }
                    }
                    _transport.sendDestroy(this, SSU2Util.REASON_FRAME_TIMEOUT);
                    _transport.dropPeer(this, true, "OB First Message Fail");
                    return 0;
                }

                synchronized (_sendWindowBytesRemainingLock) {
                    _sendWindowBytesRemaining += failedSize;
                    _sendWindowBytesRemaining += failedCount * fragmentOverhead();
                    if (_sendWindowBytesRemaining > _sendWindowBytes) {
                        _sendWindowBytesRemaining = _sendWindowBytes;
                    }
                }
            }

            if (rv <= 0) {
                synchronized (this) {
                    _retransmitTimer = 0;
                    exitFastRetransmit();
                }
            }
        }

        return rv + _outboundQueue.size();
    }

    /**
     * Pick one or more messages we want to send and allocate them out of our window
     * Adjusts the retransmit timer if necessary.
     * High usage -
     * OutboundMessageFragments.getNextVolley() calls this 2nd, if finishMessages() returned &gt; 0.
     * TODO combine finishMessages() and allocateSend() so we don't iterate 2 times.
     *
     * @return allocated messages to send (never empty), or null if no messages or no resources
     */
    List<OutboundMessageState> allocateSend(long now) {
        long retransmitTimer;
        synchronized(this) {retransmitTimer = _retransmitTimer;}
        boolean canSendOld = retransmitTimer > 0 && now >= retransmitTimer;
        List<OutboundMessageState> rv = allocateSend2(canSendOld, now);
        if (rv != null && !rv.isEmpty()) {
            synchronized(this) {
                long old = _retransmitTimer;
                if (_retransmitTimer == 0) {_retransmitTimer = now + getRTO();}
                else if (_fastRetransmit.get()) {_retransmitTimer = now + getRTO();} // right?
            }
        } else if (canSendOld) {
            // failsafe - push out or cancel timer to prevent looping
            boolean isEmpty;
            synchronized (_outboundLock) {isEmpty = _outboundMessages.isEmpty();}
            synchronized(this) {
                if (isEmpty) {
                    _retransmitTimer = 0;
                    exitFastRetransmit();
                } else {_retransmitTimer = now + 250;}
            }
        }
        return rv;
    }

    /**
     * Pick one or more messages to send.  This will alloace either old or new messages, but not both.
     * @param canSendOld if any already sent messages can be sent.  If false, only new messages will be considered
     * @param now what time is it now
     * @since 0.9.48
     */
    private List<OutboundMessageState> allocateSend2(boolean canSendOld, long now) {
        if (_dead) return null;

        List<OutboundMessageState> rv = null;

        synchronized (_outboundLock) {
            if (canSendOld) {
                for (OutboundMessageState state : _outboundMessages) {
                    if (_fastRetransmit.get()) {
                        // If fast retx flag set, just add those
                        if (state.getNACKs() < FAST_RTX_ACKS) continue;
                        if (_log.shouldDebug()) {
                            _log.debug("Allocate sending (FAST) to [" + _remotePeer.toBase64().substring(0,6) + "] -> " + state);
                        }
                    } else {
                        if (_log.shouldDebug()) {
                            _log.debug("Allocate sending (OLD) to [" + _remotePeer.toBase64().substring(0,6) + "] -> " + state.getMessageId());
                        }
                    }

                    if (rv == null) {
                        rv = new ArrayList<>(Math.max(4, (1 + _outboundMessages.size()) / 2));
                        _lastSendTime = now;
                    }
                    rv.add(state);

                    // Retransmit up to half of the packets in flight (RFC 6298 section 5.4 and RFC 5681 section 4.3)
                    if (rv.size() >= _outboundMessages.size() / 2 && !_fastRetransmit.get()) {
                        return rv;
                    }
                }
                return rv;
            }

            if (!_outboundMessages.isEmpty()) {
                for (OutboundMessageState state : _outboundMessages) {
                    if (!state.hasUnsentFragments()) continue;

                    boolean should = locked_shouldSend(state, now);
                    if (should) {
                        if (_log.shouldDebug()) {
                            _log.debug("Allocate sending more fragments to [" + _remotePeer.toBase64().substring(0,6) + "] -> " + state.getMessageId());
                        }

                        if (rv == null) {
                            rv = new ArrayList<>(_concurrentMessagesAllowed);
                        }
                        rv.add(state);
                    } else {
                        if (_log.shouldDebug()) {
                            if (rv == null) {
                                _log.debug("Nothing to send (BW) to [" + _remotePeer.toBase64().substring(0,6) + "], with " +
                                           _outboundMessages.size() + " / " + _outboundQueue.size() + " remaining");
                            } else {
                                _log.debug(_remotePeer + " ran out of BW, but managed to send " + rv.size());
                            }
                        }
                        return rv;
                    }
                }
            }

            // Peek at head of _outboundQueue and see if we can send it
            while (true) {
                OutboundMessageState state = _outboundQueue.peek();
                if (state == null || !locked_shouldSend(state, now)) break;

                OutboundMessageState dequeuedState = _outboundQueue.poll();
                if (dequeuedState == null) break;

                _outboundMessages.add(dequeuedState);

                if (_log.shouldDebug()) {
                    _log.debug("Allocating send of NEW message [" + dequeuedState.getMessageId() + "] to [" +
                               _remotePeer.toBase64().substring(0,6) + "]");
                }

                if (rv == null) {
                    rv = new ArrayList<>(_concurrentMessagesAllowed);
                }
                rv.add(dequeuedState);

                if (rv.size() >= _concurrentMessagesAllowed) {
                    return rv;
                }
            }

            return rv;
        }
    }

    /**
     * High usage - OutboundMessageFragments.getNextVolley() calls this 3rd, if allocateSend() returned null.
     * TODO combine finishMessages(), allocateSend() so we don't iterate 2 times.
     *
     * @param now what time it is now
     * @return how long to wait before sending, or Integer.MAX_VALUE if we have nothing to send.
     *         If ready now, will return 0.
     */
    int getNextDelay(long now) {
        synchronized (_sendWindowBytesRemainingLock) {
            if (_dead) return Integer.MAX_VALUE;
            if (_retransmitTimer > 0) {
                return Math.max(0, (int)(_retransmitTimer - now));
            }
            return Integer.MAX_VALUE;
        }
    }

    /**
     *  @since 0.9.3
     */
    public boolean isBacklogged() {return _dead || _outboundQueue.isBacklogged();}

    /**
     *  Always leave room for this many explicit acks.
     *  Only for data packets. Does not affect ack-only packets.
     *  This directly affects data packet overhead, adjust with care.
     */
    private static final int MIN_EXPLICIT_ACKS = 3;
    /** this is room for three explicit acks or two partial acks or one of each = 13 */
    private static final int MIN_ACK_SIZE = 1 + (4 * MIN_EXPLICIT_ACKS);

    /**
     *  how much payload data can we shove in there?
     *  @return MTU - 87, i.e. 533 or 1397 (IPv4), MTU - 107 (IPv6)
     */
    int fragmentSize() {
        // 46 + 20 + 8 + 13 = 74 + 13 = 87 (IPv4)
        // 46 + 40 + 8 + 13 = 94 + 13 = 107 (IPv6)
        return _mtu - (_remoteIP.length == 4 ? PacketBuilder.MIN_DATA_PACKET_OVERHEAD : PacketBuilder.MIN_IPV6_DATA_PACKET_OVERHEAD) - MIN_ACK_SIZE;
    }

    /**
     *  Packet overhead plus room for acks
     *  @return 87 (IPv4), 107 (IPv6)
     *  @since 0.9.49
     */
    int fragmentOverhead() {
        // 46 + 20 + 8 + 13 = 74 + 13 = 87 (IPv4)
        // 46 + 40 + 8 + 13 = 94 + 13 = 107 (IPv6)
        return (_remoteIP.length == 4 ? PacketBuilder.MIN_DATA_PACKET_OVERHEAD : PacketBuilder.MIN_IPV6_DATA_PACKET_OVERHEAD) + MIN_ACK_SIZE;
    }

    /**
     *  Locks this
     */
    private boolean locked_shouldSend(OutboundMessageState state, long now) {
        if (allocateSendingBytes(state, now)) {
            if (state.getPushCount() == 0) {_messagesSent.incrementAndGet();}
            return true;
        } else {
            _context.statManager().addRateData("udp.sendRejected", state.getPushCount());
            if (_log.shouldDebug()) {
                _log.debug("Allocation for [" + _remotePeer.toBase64().substring(0,6) + "] rejected (Window size: " + getSendWindowBytes()
                          + " / available: " + getSendWindowBytesRemaining()
                          + " bytes) for [MsgID" + state.getMessageId() + "]" + state);
            }
            return false;
        }
    }

    /**
     *  An ACK of a fragment was received.
     *
     *  SSU 2 only.
     *
     *  @return true if this fragment of the message was acked for the first time
     */
    protected boolean acked(PacketBuilder.Fragment f) {
        if (_dead) {return false;}

        final OutboundMessageState state = f.state;
        boolean isComplete;
        int ackedSize;
        synchronized(state) {
            ackedSize = state.getUnackedSize();
            if (ackedSize <= 0) {return false;}
            isComplete = state.acked(f.num);
            if (!isComplete) {ackedSize -= state.getUnackedSize();}
        }
        if (ackedSize <= 0) {return false;}
        boolean anyPending;
        synchronized (_outboundLock) {
            if (isComplete) {
                long sn = state.getSeqNum();
                boolean found = false;
                // We don't do _outboundMessages.remove() so we can use the cached iterator and break out early
                for (Iterator<OutboundMessageState> iter = _outboundMessages.iterator(); iter.hasNext(); ) {
                    OutboundMessageState state2 = iter.next();
                    if (state == state2) {
                        iter.remove();
                        found = true;
                        break;
                    } else if (state2.getSeqNum() > sn) {
                        // _outboundMessages is ordered, so once we get to a msg
                        // with a higher sequence number, we can stop
                        break;
                    }
                }
                if (!found) {
                    // shouldn't happen except on race
                    if (_log.shouldWarn()) {_log.warn("ACKed but not found in Outbound messages..." + state);}
                    return false;
                }
            }
            anyPending = !_outboundMessages.isEmpty();
        }

        int numSends = state.getMaxSends();
        _context.statManager().addRateData("udp.partialACKReceived", 1);
        long lifetime = state.getLifetime();
        if (isComplete) {
            _context.statManager().addRateData("udp.sendConfirmTime", lifetime);
            if (state.getFragmentCount() > 1) {
                _context.statManager().addRateData("udp.sendConfirmFragments", state.getFragmentCount());
            }
            _context.statManager().addRateData("udp.sendConfirmVolley", numSends);
            _transport.succeeded(state);
            if (_log.shouldDebug()) {
                if (state.getFragmentCount() > 1) {
                    _log.debug("Received partial ACK of [MsgID " + state.getMessageId() + "] from [" +
                               _remotePeer.toBase64().substring(0,6) + "] ->  Newly-acked: " + ackedSize +
                               " bytes, now complete for: " + state);
                } else {
                    _log.debug("Received ACK of [MsgID " + state.getMessageId() + "] from [" +
                               _remotePeer.toBase32().substring(0,6) + "] after " + lifetime +
                               " and " + numSends + " sends");
                }
            }
        } else {
            if (_log.shouldDebug()) {
                _log.debug("Received partial ACK of [MsgID " + state.getMessageId() + "] from [" + _remotePeer.toBase64().substring(0,6)
                      + "] after " + lifetime + " and " + numSends + " sends ->"
                      + " Complete? false"
                      + "; Newly-acked: " + ackedSize
                      + " bytes; Fragment: " + f.num
                      + " for: " + state);
            }
        }
        state.clearNACKs();
        boolean anyQueued;
        if (anyPending) {anyQueued = false;} // locked_messageACKed will nudge()
        else {
            synchronized (_outboundQueue) {anyQueued = !_outboundQueue.isEmpty();}
        }
        // This adjusts the rtt / rto / window etc
        int maxPktSz = state.fragmentSize(0) +
                       SSU2Payload.BLOCK_HEADER_SIZE +
                       (isIPv6() ? PacketBuilder2.MIN_IPV6_DATA_PACKET_OVERHEAD : PacketBuilder2.MIN_DATA_PACKET_OVERHEAD);
        messageACKed(ackedSize, maxPktSz, lifetime, numSends, anyPending, anyQueued);
        return true;
    }

    /**
     *  Enter or leave fast retransmit mode, and adjust SST and window variables accordingly.
     *  See RFC 5681 sec. 2.4
     *
     *  @param highest the highest sequence number that was acked
     *  @return true if we have something to fast-retransmit
     *  @since 0.9.49
     */
    boolean highestSeqNumAcked(long highest) {
        boolean rv = false;
        boolean startFast = false;
        boolean continueFast = false;
        synchronized(_outboundLock) {
            for (Iterator<OutboundMessageState> iter = _outboundMessages.iterator(); iter.hasNext(); ) {
                OutboundMessageState state = iter.next();
                long sn = state.getSeqNum();
                if (sn >= highest) {break;}
                if (sn < highest) {
                    // This will also increment NACKs for a state that was just partially acked... ok?
                    int nacks = state.incrementNACKs();
                    if (nacks == FAST_RTX_ACKS) {
                        startFast = true;
                        rv = true;
                    } else if (nacks > FAST_RTX_ACKS) {
                        continueFast = true;
                        rv = true;
                    }
                    if (_log.shouldDebug()) {_log.debug("Message NACKed: " + state);}
                }
            }
            if (rv) {
                // Set the variables for fast retransmit - timer will be reset below
                _fastRetransmit.set(true);
                // Caller (IMF) will wakeup OMF
                if (continueFast) {
                  // RFC 5681 sec. 3.2 #4 increase cwnd
                   _sendWindowBytes += _mtu;
                    synchronized(_sendWindowBytesRemainingLock) {_sendWindowBytesRemaining += _mtu;}
                   if (_log.shouldDebug()) {_log.debug("Continue FAST RTX, inflated window: " + this);}
                } else if (startFast) {
                   // RFC 5681 sec. 3.2 #2 set SST (equation 4)
                   // But use W+ BWE instead
                   float bwe = _bwEstimator.getBandwidthEstimate();
                   _slowStartThreshold = Math.max((int)(bwe * _rtt), 2 * _mtu);
                   // RFC 5681 sec. 3.2 #3 set cwnd
                   _sendWindowBytes = _slowStartThreshold + (3 * _mtu);
                    synchronized(_sendWindowBytesRemainingLock) {_sendWindowBytesRemaining = _sendWindowBytes;}
                   if (_log.shouldDebug()) {_log.debug("Start of FAST RTX, inflated window: " + this);}
                }
            } else {exitFastRetransmit();}
        }
        if (rv) {
            synchronized(this) {_retransmitTimer = _context.clock().now();}
        }
        return rv;
    }

    /**
     *  Leave fast retransmit mode if we were in it, and adjust SST and window variables accordingly.
     *  See RFC 5681 sec. 2.4
     *
     *  @since 0.9.49
     */
    private void exitFastRetransmit() {
        if (_fastRetransmit.compareAndSet(true, false)) {
            // RFC 5681 sec. 2.4 #6 deflate the window
            synchronized (_sendWindowBytesRemainingLock) {
                _sendWindowBytes = _slowStartThreshold;
                _sendWindowBytesRemaining = _sendWindowBytes;
            }
            if (_log.shouldDebug()) {
                _log.debug("End of FAST RTX, deflated window: " + this);
            }
        }
    }

    /**
     * SSU 2 only
     *
     * @since 0.9.56
     */
    protected boolean shouldRequestImmediateAck() {
        synchronized(_sendWindowBytesRemainingLock) {
            return _sendWindowBytesRemaining < _sendWindowBytes / 3;
        }
    }

    /**
     * Transfer the basic activity/state from the old peer to the current peer
     *
     *  SSU 1 or 2.
     *
     * @param oldPeer non-null
     */
    void loadFrom(PeerState oldPeer) {
        _rto = oldPeer._rto;
        _rtt = oldPeer._rtt;
        _rttDeviation = oldPeer._rttDeviation;
        _slowStartThreshold = oldPeer._slowStartThreshold;
        _sendWindowBytes = oldPeer._sendWindowBytes;
        oldPeer._dead = true;

        if (getVersion() == oldPeer.getVersion()) {
            Map<Long, InboundMessageState> msgs = new HashMap<Long, InboundMessageState>();
            synchronized (oldPeer._inboundLock) {
                msgs.putAll(oldPeer._inboundMessages);
                oldPeer._inboundMessages.clear();
            }
            if (!_dead) { synchronized (_inboundLock) {_inboundMessages.putAll(msgs);} }
            msgs.clear();

            List<OutboundMessageState> tmp2 = new ArrayList<OutboundMessageState>();
            OutboundMessageState retransmitter = null;
            synchronized (oldPeer._outboundLock) {
                tmp2.addAll(oldPeer._outboundMessages);
                oldPeer._outboundMessages.clear();
            }
            if (!_dead) { synchronized (_outboundLock) {_outboundMessages.addAll(tmp2);} }
        }
    }

    /**
     *  Convenience for OutboundMessageState so it can fail itself
     *  @since 0.9.3
     */
    UDPTransport getTransport() {return _transport;}

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("Router: ").append(_remoteHostId.toString());
        buf.append(" [").append(_remotePeer.toBase64().substring(0,6)).append("]");

        if (getVersion() == 2) {buf.append(_isInbound? " Inbound v2 " : " Outbound v2 ");}
        else {buf.append(_isInbound ? " Inbound" : " Outbound");}
        long now = _context.clock().now();
        buf.append("\n* Received: ").append(now-_lastReceiveTime).append("ms ago");
        if (_lastSendFullyTime != 0) {buf.append("\n* Last successful send: ").append(new Date(_lastSendFullyTime));}
        if (_lastSendTime != 0) {buf.append("\n* Last attempted send: ").append(new Date(_lastSendTime));}
        if (_lastACKSend != 0) {buf.append("\n* Last ACK sent: ").append(new Date(_lastACKSend));}
        if (_log.shouldInfo()) {
            int txQueue = _outboundQueue.size();
            boolean isQueued = _inboundMessages.size() > 0 || _outboundMessages.size() > 0;
            buf.append("\n* Lifetime: ").append(now-_keyEstablishedTime).append("ms")
               .append("; RTT: ").append(_rtt).append("ms")
               .append("; RTTdev: ").append(_rttDeviation).append("ms")
               .append("; RTO: ").append(_rto).append("ms")
               .append("; MTU: ").append(_mtu).append(" bytes")
               .append("; Large MTU: ").append(_largeMTU).append(" bytes")
               .append("\n* Congestion window: ").append(_sendWindowBytes).append(" bytes")
               .append("; Active window: ").append(_sendWindowBytesRemaining).append(" bytes")
               .append("; SST: ").append(_slowStartThreshold).append(" bytes")
               .append("; FastRetransmit? ").append(_fastRetransmit)
               .append("\n*").append(_consecutiveFailedSends > 0 ? " Consecutive fails: " + _consecutiveFailedSends + ";" : "")
               .append(" Messages (received / sent): ").append(_messagesReceived).append(" / ").append(_messagesSent)
               .append(isQueued ? "; Messages (in / out): " + _inboundMessages.size() + " / " + _outboundMessages.size() : "")
               .append(txQueue > 0 ? "; Outbound queue: " + txQueue : "")
               .append("\n* Packets received (OK / Duplicate): ").append(_packetsReceived).append(" / ").append(_packetsReceivedDuplicate)
               .append("\n* Packets sent (OK / Duplicate): ").append(_packetsTransmitted).append(" / ").append(_packetsRetransmitted);
        }
        return buf.toString();
    }

}