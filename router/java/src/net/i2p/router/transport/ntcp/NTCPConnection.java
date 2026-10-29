package net.i2p.router.transport.ntcp;

import com.southernstorm.noise.protocol.CipherState;
import java.io.Closeable;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.crypto.SipHashInline;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;

import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.BanLogger;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.ntcp.NTCP2Payload.Block;
import net.i2p.router.util.PriBlockingQueue;
import net.i2p.util.Addresses;
import net.i2p.util.ByteCache;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

/**
 * Coordinates a connection to a single peer using NTCP 1 or NTCP 2 protocols.
 * Manages the complete connection lifecycle including establishment,
 * message encryption/decryption, bandwidth management, and graceful shutdown.
 * <p>
 * Public only for UI peers page. Not a public API, not for external use.
 *
 * @since 0.9.16
 */
public class NTCPConnection implements Closeable {
    /** The router running this transport. */
    private final RouterContext _context;
    /** Logger for this class. */
    private final Log _log;
    /** Logger for peers banned by this transport. */
    private static BanLogger _banLogger;
    /** The socket channel, null once closed. */
    private volatile SocketChannel _chan;
    /** The selector key for this connection. */
    private volatile SelectionKey _conKey;
    /** Bandwidth callback fired after inbound reads. */
    private final FIFOBandwidthLimiter.CompleteListener _inboundListener;
    /** Bandwidth callback fired after outbound writes. */
    private final FIFOBandwidthLimiter.CompleteListener _outboundListener;
    /**
     * Queue of ByteBuffer containing data we have read and are ready to process, oldest first.
     * Lockless. Reads are paused at MAX_READ_BUFS entries so a peer that sends faster
     * than we process applies backpressure to the socket instead of growing the queue
     * without bound; the pause lifts once the queue has drained.
     */
    private final Queue<ByteBuffer> _readBufs;
    /**
     * Read buffers queued before the socket read interest is dropped (8 KB each),
     * so one connection can hold at most about 256 KB of read-ahead.
     *
     * @since 0.9.71+
     */
    private static final int MAX_READ_BUFS = 32;
    /**
     * True while read interest is cleared because _readBufs is full.
     *
     * @since 0.9.71+
     */
    private volatile boolean _readPaused;
    /**
     * List of ByteBuffers containing fully populated and encrypted data, ready to write,
     * and already cleared through the bandwidth limiter.
     * Unbounded and lockless.
     */
    private static volatile int MAX_WRITE_BUFS = 512;

    /**
     * Current max write buffers per NTCP connection.
     *
     * @return the current max write buffers
     * @since 0.9.70+
     */
    public static int getMaxWriteBufs() { return MAX_WRITE_BUFS; }

    /**
     * Current max read buffers per NTCP connection.
     *
     * @return the current max read buffers
     * @since 0.9.71+
     */
    public static int getMaxReadBufs() { return MAX_READ_BUFS; }

    /**
     * Max write buffers per NTCP connection (called by Tuner).
     *
     * @param max the maximum write buffers
     * @since 0.9.70+
     */
    public static void setMaxWriteBufs(int max) {
        MAX_WRITE_BUFS = Math.max(128, Math.min(2048, max));
    }
    /** Buffers queued for writing. */
    private final Queue<ByteBuffer> _writeBufs;
    /** Requests that were not granted immediately. */
    private final Set<FIFOBandwidthLimiter.Request> _bwInRequests;
    /** Outstanding outbound bandwidth requests. */
    private final Set<FIFOBandwidthLimiter.Request> _bwOutRequests;
    /** Time the handshake completed. */
    private long _establishedOn;
    /** Phase of the handshake. */
    private volatile EstablishState _establishState;
    /** The transport that owns this connection. */
    private final NTCPTransport _transport;
    /** True if the peer connected to us. */
    private final boolean _isInbound;
    /** True once the connection is closed. */
    private final AtomicBoolean _closed = new AtomicBoolean();
    /** The peer's address as configured. */
    private final RouterAddress _remAddr;
    /** The peer's identity, once established. */
    private RouterIdentity _remotePeer;
    /** Our clock minus the peer's, in seconds. */
    private long _clockSkew; // in seconds
    /**
     * Pending unprepared OutNetMessage instances.
     */
    private final PriBlockingQueue<OutNetMessage> _outbound;
    /**
     * Current prepared OutNetMessages, or empty - synchronize on _writeLock.
     */
    private final List<OutNetMessage> _currentOutbound;
    /** Current partially read I2NP message. */
    private ReadState _curReadState;
    /** Messages read on this connection. */
    private final AtomicInteger _messagesRead = new AtomicInteger();
    /** Messages written on this connection. */
    private final AtomicInteger _messagesWritten = new AtomicInteger();
    /** Time of the last write. */
    private long _lastSendTime;
    /** Time of the last read. */
    private long _lastReceiveTime;
    /**
     * Round trip time of the handshake that established this session, in ms, or
     * 0 when it has not been measured.
     * <p>Both NTCP2 establishment paths already compute this to derive clock
     * skew - {@code now - getCreated()} at the point the peer's timestamp
     * arrives - but discarded it, so the transport had no latency to report and
     * {@link net.i2p.router.transport.Transport#getEstimatedRTT} answered 0 for
     * every NTCP2 peer. With that answer, every consumer of a direct-link
     * latency reading stayed empty: the profile's first-hop RTT was never
     * populated, and the low-latency flag never turned on for NTCP2 peers.
     * <p>Measured once, at establishment, and never updated afterwards: it
     * describes the handshake this session was built on, and the profile-level
     * smoothing in {@code PeerProfile.setFirstHopRtt} is what carries it
     * forward. Recorded here rather than derived by the reader, so the value
     * has exactly one source per session.
     *
     * @since 0.9.71+
     */
    private volatile int _establishmentRtt;

    /**
     * When {@link #_establishmentRtt} was measured, on the router clock, so a
     * reader can tell a handshake-time reading from a current one.
     * <p>Kept beside the value rather than derived from session age, so the
     * measurement's timestamp is the handshake's own and cannot drift if the
     * reader is called at a different moment.
     *
     * @since 0.9.71+
     */
    private volatile long _establishmentRttTime;
    /** Time the byte rates were last computed. */
    private long _lastRateUpdated;
    /**
     * Last time "{@code ntcp.writeBufs.size}" was recorded, to throttle the per-frame
     * RateStat call on the write path (sample at most once per second).
     * Best-effort throttle only; no _writeLock needed.
     */
    private long _lastWriteBufsStat;
    /** Time the connection object was created. */
    private final long _created;
    /** Time before which metadata must not be sent, kept past establish. */
    private long _nextMetaTime = Long.MAX_VALUE;
    /** Reads returning zero in a row. */
    private final AtomicInteger _consecutiveZeroReads = new AtomicInteger();
    /** Lock guarding _curReadState, the current read state. */
    private final Object _readLock = new Object();
    /**
     * Lock guarding _currentOutbound, _outbound, _sendSipk1, _sendSipk2,
     * _sendSipIV and _sender. _writeBufs is drained only by the pumper
     * thread (ConcurrentLinkedQueue), so it no longer needs this lock.
     */
    private final Object _writeLock = new Object();
    /**
     * Lock guarding the byte and rate counters: _bytesReceived, _bytesSent,
     * _lastBytesReceived, _lastBytesSent, _sendBps and _recvBps.
     */
    private final Object _statLock = new Object();

    /** How many consecutive sends were failed due to (estimated) send queue time. */
    private long _nextInfoTime;
    /** True once either side may disconnect. */
    private volatile boolean _mayDisconnect;
    /** True while a write-interest change awaits the selector. */
    private final AtomicBoolean _writeInterestPending = new AtomicBoolean(false);
    /** Time of the last read or write. */
    private volatile long _lastActiveTime = System.currentTimeMillis();
    /** Time of the last zero-length read. */
    private volatile long _lastZeroReadTime = 0;

    /** How often send and receive rates are recomputed for the console peers page. */
    private static final long STAT_UPDATE_TIME_MS = 60*1000L;

    /**
     * Interval between the pool test messages, longer than twice the
     * pumper maximum idle time so it does not interfere with the
     * idle timeout.
     */
    private static final int META_FREQUENCY = 45*60*1000;

    /** How often we send our routerinfo unsolicited. */
    private static final int INFO_FREQUENCY = 50*60*1000;

    /**
     * Why this is 16K, and where it is documented, good question?
     * We claim we can do 32K datagrams so this is a problem.
     * Needs to be fixed. But SSU can handle it?
     * In the meantime, don't let the transport bid on big messages.
     */
    static final int BUFFER_SIZE = 16*1024;
    /** Size of the pooled read-buffer cache. */
    private static final int MAX_DATA_READ_BUFS = SystemVersion.isSlow() ? 4 : 16;
    /** The cache supplying the data read buffers. */
    private static final ByteCache _dataReadBufs = ByteCache.getInstance(MAX_DATA_READ_BUFS, BUFFER_SIZE);
    /** Source of unique connection ids. */
    private static final AtomicLong __connID = new AtomicLong();
    /** Id of this connection, for logs. */
    private final long _connID = __connID.incrementAndGet();

    /**
     * See spec. Max Noise payload 65535,
     * minus 16 byte MAC and 3 byte block header.
     * Includes 9-byte I2NP header.
     */
    static final int NTCP2_MAX_MSG_SIZE = 65516;
    /** Largest padding a single message may carry, in bytes. */
    private static final int PADDING_MAX = 64;
    /** Length of the SipHash IV, in bytes. */
    private static final int SIP_IV_LENGTH = 8;
    /** Cap on the junk read after a failed handshake, in bytes. */
    private static final int NTCP2_FAIL_READ = 1024;
    /** Time allowed for that junk read, in ms. */
    private static final long NTCP2_FAIL_TIMEOUT = 8*1000L;
    /** Delay before closing after a termination block, in ms. */
    private static final long NTCP2_TERMINATION_CLOSE_DELAY = 50;
    /** Preferred cap for a combined payload, in bytes. Tunnel data messages are 1024 + 4 + 9 + 3 = 1040, so five fit; keeping combined messages small minimizes latency. */
    private static final int NTCP2_PREFERRED_PAYLOAD_MAX = 5 * 1040;
    /** Unspecified reason. */
    static final int REASON_UNSPEC = 0;
    /** Termination reason. */
    static final int REASON_TERMINATION = 1;
    /** Timeout reason. */
    static final int REASON_TIMEOUT = 2;
    /** AEAD failure reason. */
    static final int REASON_AEAD = 4;
    /** Options mismatch reason. */
    static final int REASON_OPTIONS = 5;
    /** Signature type mismatch reason. */
    static final int REASON_SIGTYPE = 6;
    /** Clock skew reason. */
    static final int REASON_SKEW = 7;
    /** Padding violation reason. */
    static final int REASON_PADDING = 8;
    /** Framing error reason. */
    static final int REASON_FRAMING = 9;
    /** Payload error reason. */
    static final int REASON_PAYLOAD = 10;
    /** Message 1 error reason. */
    static final int REASON_MSG1 = 11;
    /** Message 2 error reason. */
    static final int REASON_MSG2 = 12;
    /** Message 3 error reason. */
    static final int REASON_MSG3 = 13;
    /** Frame timeout reason. */
    static final int REASON_FRAME_TIMEOUT = 14;
    /** Signature failure reason. */
    static final int REASON_SIGFAIL = 15;
    /** S mismatch reason. */
    static final int REASON_S_MISMATCH = 16;
    /** Banned reason. */
    static final int REASON_BANNED = 17;
    /** Default minimum padding integer. */
    static final int PADDING_MIN_DEFAULT_INT = 0;
    /** Default maximum padding integer. */
    static final int PADDING_MAX_DEFAULT_INT = 1;
    /** Default minimum padding, as a fraction of the message. */
    private static final float PADDING_MIN_DEFAULT = PADDING_MIN_DEFAULT_INT / 16.0f;
    /** Default maximum padding, as a fraction of the message. */
    private static final float PADDING_MAX_DEFAULT = PADDING_MAX_DEFAULT_INT / 16.0f;
    /** Default dummy value. */
    static final int DUMMY_DEFAULT = 0;
    /** Default delay value. */
    static final int DELAY_DEFAULT = 0;
    /** The padding options we advertise. */
    private static final NTCP2Options OUR_PADDING = new NTCP2Options(PADDING_MIN_DEFAULT, PADDING_MAX_DEFAULT,
                                                                     PADDING_MIN_DEFAULT, PADDING_MAX_DEFAULT,
                                                                     DUMMY_DEFAULT, DUMMY_DEFAULT,
                                                                     DELAY_DEFAULT, DELAY_DEFAULT);
    /** Smallest padding range allowed. */
    private static final int MIN_PADDING_RANGE = 16;
    /** Largest padding range allowed. */
    private static final int MAX_PADDING_RANGE = 128;
    /** The padding options in effect for this connection. */
    private volatile NTCP2Options _paddingConfig = OUR_PADDING;
    /** The NTCP version in use. */
    private int _version;
    /** Cipher state for outbound data. */
    private CipherState _sender;
    /** First SipHash ratchet key for outbound data. */
    private long _sendSipk1;
    /** Second SipHash ratchet key for outbound data. */
    private long _sendSipk2;
    /** SipHash IV for outbound data. */
    private byte[] _sendSipIV;

    /**
     * Create an inbound connected (though not established) NTCP connection.
     * Caller MUST call {@code transport.establishing}(this) after construction.
     * Caller MUST key.attach(this) after construction.
     * @param ctx the router context
     * @param transport the NTCP transport
     * @param chan the socket channel
     * @param key the selection key
     */
    public NTCPConnection(RouterContext ctx, NTCPTransport transport, SocketChannel chan, SelectionKey key) {
        this(ctx, transport, null, true);
        _chan = chan;
        _version = 2;
        _conKey = key;
        _establishState = new InboundEstablishState(ctx, transport, this);
    }

    /**
     * Create an outbound unconnected NTCP connection.
     * Caller MUST call {@code transport.establishing}(this) after construction.
     *
     * @param ctx the router context
     * @param transport the NTCP transport
     * @param remotePeer the remote peer identity
     * @param remAddr the remote router address
     * @param version must be 2-5
     * @throws DataFormatException if there's a problem with the address
     */
    public NTCPConnection(RouterContext ctx, NTCPTransport transport, RouterIdentity remotePeer,
                          RouterAddress remAddr, int version) throws DataFormatException {
        this(ctx, transport, remAddr, false);
        _remotePeer = remotePeer;
        _version = version;
        if (version < 2 || version > 5) {
            throw new IllegalArgumentException("Bad version " + version);
        } else {
            try {
                _establishState = new OutboundNTCP2State(ctx, transport, this);
            } catch (IllegalArgumentException iae) {
                throw new DataFormatException("Bad address? " + remAddr, iae);
            }
        }
    }

    /**
     * Base constructor for an inbound or outbound connection.
     *
     * @param ctx the router context
     * @param transport the NTCP transport
     * @param remAddr the remote router address, may be null for inbound
     * @param isIn true for inbound, false for outbound
     * @since 0.9.36
     */
    private NTCPConnection(RouterContext ctx, NTCPTransport transport, RouterAddress remAddr, boolean isIn) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        if (_banLogger == null) {
            _banLogger = new BanLogger();
            _banLogger.initialize(ctx);
        }
        _created = ctx.clock().now();
        _transport = transport;
        _remAddr = remAddr;
        _lastSendTime = _created;
        _lastReceiveTime = _created;
        _lastRateUpdated = _created;
        _readBufs = new ConcurrentLinkedQueue<>();
        _writeBufs = new ConcurrentLinkedQueue<>();
        _bwInRequests = new ConcurrentHashSet<>(4);
        _bwOutRequests = new ConcurrentHashSet<>(16);
        _outbound = new PriBlockingQueue<>(ctx, "NTCP-Connection", 32);
        _currentOutbound = new ArrayList<>(1);
        _isInbound = isIn;
        _inboundListener = new InboundListener();
        _outboundListener = new OutboundListener();
    }

    /**
     * Valid for inbound; valid for outbound shortly after creation.
     *
     * @return the socket channel
     */
    public SocketChannel getChannel() { return _chan; }

    /**
     * Valid for inbound; valid for outbound shortly after creation.
     *
     * @return the selection key
     */
    public SelectionKey getKey() { return _conKey; }

    /**
     * Socket channel for this connection.
     * @param chan the channel this connection reads and writes through
     */
    public void setChannel(SocketChannel chan) { _chan = chan; }

    /**
     * Selection key for this connection.
     * @param key the selection key registering this connection with the pumper
     */
    public void setKey(SelectionKey key) { _conKey = key; }

    /**
     * Whether this is an inbound connection.
     *
     * @return true if inbound
     */
    public boolean isInbound() { return _isInbound; }

    /**
     * Whether the connection is established.
     *
     * @return true if established
     */
    public boolean isEstablished() { return _establishState.isComplete(); }

    /**
     * Whether the connection is over IPv6.
     *
     * @return true if IPv6
     * @since IPv6
     */
    public boolean isIPv6() {
        return _chan != null && _chan.socket().getInetAddress() instanceof Inet6Address;
    }

    /**
     * Return the remote peer's IP address.
     *
     * @return null if unknown
     * @since 0.9.53
     */
    public byte[] getRemoteIP() {
        if (_chan == null)
            return null;
        InetAddress addr = _chan.socket().getInetAddress();
        if (addr == null)
            return null;
        return addr.getAddress();
    }

    /**
     * Return the remote peer's UDP port.
     *
     * @return 0 if unknown
     * @since 2.11.0
     */
    public int getRemotePort() {
        if (_chan == null)
            return 0;
        return _chan.socket().getPort();
    }

    /**
     * Only valid during establishment;
     * replaced with {@link EstablishState}.VERIFIED or FAILED afterwards.
     *
     * @return the establish state
     */
    EstablishState getEstablishState() { return _establishState; }

    /**
     * Only valid for outbound; null for inbound.
     *
     * @return the remote address
     */
    public RouterAddress getRemoteAddress() { return _remAddr; }

    /**
     * Valid for outbound; valid for inbound after handshake.
     *
     * @return the remote peer identity
     */
    public RouterIdentity getRemotePeer() { return _remotePeer; }

    /**
     * Valid for outbound; valid for inbound after handshake.
     *
     * @param ident the remote peer identity
     */
    public void setRemotePeer(RouterIdentity ident) { _remotePeer = ident; }

    /**
     * A positive number means our clock is ahead of theirs.
     * @return the clock skew in seconds, positive if our clock runs ahead
     */
    public long getClockSkew() { return _clockSkew; }

    /**
     * Connection uptime in milliseconds.
     *
     * @return the uptime in ms
     */
    public long getUptime() {
        long establishedOn;
        synchronized(_statLock) {
            if (!isEstablished())
                return getTimeSinceCreated();
            establishedOn = _establishedOn;
        }
        return _context.clock().now() - establishedOn;
    }

    /**
     * Time this connection was established.
     * @return established timestamp, or 0 if not yet established
     * @since 0.9.55
     */
    public long getEstablishedOn() {
        synchronized(_statLock) {
            if (!isEstablished())
                return 0;
            return _establishedOn;
        }
    }

    /**
     * Messages sent on this connection.
     *
     * @return how many messages have been written on this connection
     */
    public int getMessagesSent() { return _messagesWritten.get(); }

    /**
     * Messages received on this connection.
     *
     * @return how many messages have been read on this connection
     */
    public int getMessagesReceived() { return _messagesRead.get(); }

    /**
     * Return the number of messages waiting to be sent.
     *
     * @return outbound queue size
     */
    public int getOutboundQueueSize() {
            int queued = _outbound.size();
            synchronized(_writeLock) {
                queued += _currentOutbound.size();
            }
            return queued;
    }

    /**
     * Check if there are pending outbound messages.
     *
     * @return true if a message is still held for the current outbound write
     */
    private boolean hasCurrentOutbound() {
        synchronized(_writeLock) {
            return ! _currentOutbound.isEmpty();
        }
    }

    /**
     * Drain any pending outbound messages to a new queue.
     * @param to the queue the pending messages are moved into
     * @return how many pending messages were moved into the given queue
     * @since 0.9.46
     */
    private int drainOutboundTo(Queue<OutNetMessage> to) {
        int rv = 0;
        synchronized (_writeLock) {
            rv = _currentOutbound.size();
            if (rv > 0) {
                to.addAll(_currentOutbound);
                _currentOutbound.clear();
            }
            rv += _outbound.drainTo(to);
        }
        return rv;
    }

    /**
     * Time since last send in milliseconds.
     *
     * @return time since last send in ms
     */
    public long getTimeSinceSend() { return _context.clock().now()-_lastSendTime; }

    /**
     * Time since last send in milliseconds.
     * @param now the current time in milliseconds since the epoch
     * @return how many milliseconds have passed since the last send
     * @since 0.9.38
     */
    public long getTimeSinceSend(long now) { return now - _lastSendTime; }

    /**
     * Time since last receive in milliseconds.
     *
     * @return time since last receive in ms
     */
    public long getTimeSinceReceive() { return _context.clock().now()-_lastReceiveTime; }

    /**
     * Time since last receive in milliseconds.
     * @param now the current time in milliseconds since the epoch
     * @return how many milliseconds have passed since the last receive
     * @since 0.9.38
     */
    public long getTimeSinceReceive(long now) { return now - _lastReceiveTime; }

    /**
     * Time since connection creation in milliseconds.
     *
     * @return time since creation in ms
     */
    public long getTimeSinceCreated() { return _context.clock().now()-_created; }

  /**
   * Round trip time of the handshake that established this session.
   * <p>Recorded once when establishment completes, from the same
   * {@code now - getCreated()} interval the clock-skew calculation already
   * performs, and not refreshed for the life of the session. The session's
   * current latency is a question for the transport's own estimator; this is
   * the cost of getting the link up, which is what a profile's first-hop RTT
   * is recorded as.
   *
   * @return the handshake round trip time in ms, or 0 if not yet established
   * @since 0.9.71+
   */
  public int getEstimatedRTT() { return _establishmentRtt; }

  /**
   * Record the handshake round trip time, once the establishment completes.
   * <p>First writer wins. Both establishment directions converge here, so
   * whichever observes VERIFIED first supplies the sample and a late second
   * observation cannot overwrite it - the value has exactly one source per
   * session, and re-deriving it later would make the same session's latency
   * depend on read timing.
   *
   * @param rttMs measured round trip time in ms; values of 0 or less are ignored
   * @since 0.9.71+
   */
void noteEstablishmentRtt(int rttMs) {
     synchronized (this) {
       if (!shouldRecordEstablishmentRtt(_establishmentRtt, rttMs)) return;
       _establishmentRtt = rttMs;
       // Stamped here, where the handshake is the thing being measured, so
       // NTCPTransport can report when this reading was taken instead of letting
       // a caller stamp the present onto a value that may be hours old.
       _establishmentRttTime = _context.clock().now();
     }
   }

   /**
    * When {@link #getEstimatedRTT} was measured, on the router clock.
    *
    * @return the handshake completion time in ms, or
    * {@code Transport.RTT_TIME_UNKNOWN} before establishment has
    * supplied a reading
    * @since 0.9.71+
    */
   public long getEstimatedRTTTime() {
     return (_establishmentRttTime > 0) ? _establishmentRttTime : Transport.RTT_TIME_UNKNOWN;
   }

  /**
   * Whether a candidate handshake timing should become this session's recorded
   * latency.
   * <p>Extracted as a pure predicate so the rule is testable without a router
   * context and a live socket. Two rejections, both about not inventing a
   * measurement: a non-positive interval means the clock did not advance, and
   * an already-recorded value means this session's latency already has a source.
   *
   * @param existingRttMs the value already recorded, 0 if none
   * @param candidateRttMs the newly measured interval
   * @return true if the candidate should be recorded
   * @since 0.9.71+
   */
  static boolean shouldRecordEstablishmentRtt(int existingRttMs, long candidateRttMs) {
    if (candidateRttMs <= 0) return false;
    if (candidateRttMs >= Integer.MAX_VALUE) return false;
    return existingRttMs <= 0;
  }

    /**
     * Time since connection creation in milliseconds.
     * @param now the current time in milliseconds since the epoch
     * @return how many milliseconds have passed since the connection was created
     * @since 0.9.38
     */
    public long getTimeSinceCreated(long now) { return now -_created; }

    /**
     * When this connection was created (not established).
     *
     * @return the creation timestamp
     * @since 0.9.20
     */
    public long getCreated() { return _created; }

    /**
     * The NTCP2 version, for the console.
     * For outbound, will not change.
     * For inbound, defaults to 1, may change to 2 after establishment.
     *
     * @return the version, 1 or 2
     * @since 0.9.36
     */
    public int getVersion() { return _version; }

    /**
     * Records version 2 from {@link InboundEstablishState}.
     * Just for logging, so we know before finishInboundEstablish() is called.
     *
     * @param ver the version to set
     * @since 0.9.36
     */
    public void setVersion(int ver) { _version = ver; }

    /**
     * The may-disconnect flag, set to true.
     * @since 0.9.24
     */
    public void setMayDisconnect() { _mayDisconnect = true; }

    /**
     * Whether the connection may be disconnected.
     *
     * @return true if may disconnect
     * @since 0.9.24
     */
    public boolean getMayDisconnect() { return _mayDisconnect; }

    /**
     * Reset the zero-read counters the EventPumper uses to spot a stalled peer.
     * @since 0.8.12
     */
    public void clearZeroRead() {
        _consecutiveZeroReads.set(0);
        _lastZeroReadTime = 0;
    }

    /**
     * Track time of zero-reads to avoid closing healthy connections.
     * @return current count after incrementing
     */
    public int gotZeroRead() {
        long now = System.currentTimeMillis();
        if (now - _lastZeroReadTime > 1000) {
            _consecutiveZeroReads.set(0); // reset counter if older than 1s
        }
        int count = _consecutiveZeroReads.incrementAndGet();
        _lastZeroReadTime = now;
        return count;
    }

    /**
     * Time of the last zero-byte read.
     *
     * @return last zero read time
     */
    public long getLastZeroReadTime() {
        return _lastZeroReadTime;
    }

    /**
     * Whether the connection is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() { return _closed.get(); }

    @Override
    public void close() { close(false); }

    /**
     * Close the connection, dropping or requeueing whatever is still pending.
     * @param allowRequeue if true, requeue pending messages for another tunnel
     */
    public void close(boolean allowRequeue) {
        if (!_closed.compareAndSet(false,true)) {
            _log.logCloseLoop("NTCPConnection", this);
            return;
        }
        String failReason = null;
        EstablishState es = _establishState;
        if (es != null) {
            failReason = es.getFailReason();
        }
        NTCPConnection toClose = locked_close(allowRequeue);
        if (_log.shouldInfo()) {
            if (failReason != null) {
                _log.info("Closing " + toString() + " -> " + failReason);
            } else {
                _log.info("Closing " + toString());
            }
        }
        if (toClose != null && toClose != this) {
            // won't happen as of 0.9.37
            if (_log.shouldWarn())
                _log.warn("Multiple connections on remove, closing " + toClose + " (already closed " + this + ")");
            _context.statManager().addRateData("ntcp.multipleCloseOnRemove", toClose.getUptime());
            toClose.close();
        }
    }

    /**
     * Close and release EstablishState resources.
     * @param cause the cause of the timeout
     * @param e may be null
     * @since 0.9.16
     */
    void closeOnTimeout(String cause, Exception e) {
        EstablishState es = _establishState;
        if (es != null) {
            es.close(cause, e);
        }
        if (_isInbound && !isEstablished()) {
            byte[] ip = getRemoteIP();
            if (ip != null) {
                _transport.getPumper().trackFailedInboundHandshake(ip);
            }
        }
        close();
    }

    /**
     * The connection; usually this, but could be a second connection with the same peer.
     * @param allowRequeue whether to hand the unsent messages back for requeueing
     * @return usually this, but could be a second connection with the same peer...
     *         only this or null as of 0.9.37
     */
    private NTCPConnection locked_close(boolean allowRequeue) {
        EstablishState es;
        NTCPConnection old;
        synchronized (this) {
        if (_chan != null) try { _chan.close(); } catch (IOException ioe) { /* ignored */ }
        if (_conKey != null) _conKey.cancel();
        // Release any in-progress handshake buffers (SimpleByteCache / _dataReadBufs).
        // Plain close() paths (read errors etc.) would otherwise leak these, as only
        // closeOnTimeout() routed through EstablishState.close(). close()/fail() is
        // idempotent, so this is safe even if already released.
        es = _establishState;
        // close() deferred to after synchronized block to avoid AB/BA deadlock:
        //   Thread A: lock NTCPConnection → lock OutboundNTCP2State (via es.close())
        //   Thread B: lock OutboundNTCP2State (via receive()) → lock NTCPConnection (via finishOutboundEstablishment())
        _establishState = EstablishBase.FAILED;
        old = _transport.removeCon(this);
        _transport.getReader().connectionClosed(this);
        _transport.getWriter().connectionClosed(this);

        // abort() fires the InboundListener synchronously (abort() -> notifyAllocation()).
        // The ownership hand-off inside complete() then returns the attached read
        // buffer to the pool exactly once - either here because _closed is already
        // set, or on the limiter thread that won the hand-off (which delivers it
        // into _readBufs before this drain, or releases it if _closed was set).
        // Never release the buffer directly in this loop; see removeIBRequest().
        for (FIFOBandwidthLimiter.Request req :_bwInRequests) {
            req.abort();
        }
        _bwInRequests.clear();
        for (FIFOBandwidthLimiter.Request req :_bwOutRequests) {
            req.abort();
        }
        _bwOutRequests.clear();

        List<OutNetMessage> pending = new ArrayList<>();
        synchronized(_writeLock) {
        // NOT pooled: write-frame byte arrays are only returned to the pool from
        // EventPumper.writeOneBuffer() (releaseDrainedWriteBuf), never here, so a
        // stranded buffer is just GC'd - never double-released into the pool.
        _writeBufs.clear();
        _outbound.drainTo(pending);
            if (!_currentOutbound.isEmpty())
                pending.addAll(_currentOutbound);
            _currentOutbound.clear();
            if (_sender != null) {
                _sender.destroy();
                _sender = null;
            }
            // zero out everything we can
            _sendSipk1 = 0;
            _sendSipk2 = 0;
            if (_sendSipIV != null) {
                Arrays.fill(_sendSipIV, (byte) 0);
                _sendSipIV = null;
            }
        }
        for (OutNetMessage msg : pending) {
            _transport.afterSend(msg, false, allowRequeue, msg.getLifetime());
        }
        synchronized(_readLock) {
            ByteBuffer bb;
            while ((bb = _readBufs.poll()) != null) {
                EventPumper.releaseBuf(bb);
            }
            if (_curReadState != null) {
                _curReadState.destroy();
                _curReadState = null;
            }
        }
        } // synchronized(this)
        // Close EstablishState outside the NTCPConnection lock to avoid
        // AB/BA deadlock with OutboundNTCP2State.receive() → finishOutboundEstablishment().
        if (es != null && es != EstablishBase.FAILED) {
            es.close("Connection closed", null);
        }
        return old;
    }

    /**
     * Queue the message onto the connection's send queue.
     *
     * @param msg the message to send
     */
    public void send(OutNetMessage msg) {
        if (!_outbound.offer(msg)) {
            if (_log.shouldInfo())
                _log.info("Outbound queue full on " + toShortString() +
                          " -> Dropping message [MsgID " + msg.getMessageId() + ' ' + msg.getMessageType() + ']');
            _transport.getContext().statManager().addRateData("ntcp.sendBacklogTime", msg.getLifetime());
            _transport.afterSend(msg, false, false, msg.getLifetime());
            return;
        }
        _transport.getContext().statManager().addRateData("ntcp.sendQueueSize", _outbound.size());
        if (isEstablished() && !hasCurrentOutbound())
            _transport.getWriter().wantsWrite(this, "enqueued");
    }

    /**
     * Whether the outbound queue is backlogged.
     *
     * @return true if outbound queue is backlogged
     */
    public boolean isBacklogged() { return _outbound.isBacklogged(); }

    /**
     * Returns true if the outbound connection is currently too backlogged to accept new messages,
     * allowing a short grace period after connection establishment and optionally logging details.
     *
     * @return true if too backlogged
     */
    public boolean tooBacklogged() {
        // Allow some slack just after connection establishment
        if (!isTooBacklogged(getUptime(), _outbound.isBacklogged()))
            return false;

        int size = _outbound.size();

        if (_log.shouldInfo()) {
            int writeBufs = _writeBufs.size();
            boolean currentOutboundSet;

            synchronized (_writeLock) {
                currentOutboundSet = !_currentOutbound.isEmpty();
            }

            try {
                String writeStatus = _conKey != null ? String.valueOf((_conKey.interestOps() & SelectionKey.OP_WRITE) != 0) : "null_key";
                _log.info("Outbound connection too backlogged (size=" + size +
                          " wantsWrite=" + writeStatus +
                          " outboundSet=" + currentOutboundSet +
                          " writeBufs=" + writeBufs + ") on " + this);
            } catch (RuntimeException ignored) { /* ignored */ }
        }

        return true;
    }

    /**
     * Inject a DatabaseStoreMessage with our RouterInfo. NTCP 1 or 2.
     * <p>
     * Externally, this is only called by {@link NTCPTransport} for outbound cons,
     * before the con is established, but we know what version it is.
     * <p>
     * Internally, may be called for outbound or inbound, but only after the
     * con is established, so we know what the version is.
     */
    void enqueueInfoMessage() {
        if (_isInbound) {
            // TODO or if outbound and it's not right at the beginning
            // TODO flood
            sendOurRouterInfo(false);
        }
        // don't need to send for NTCP 2 outbound, it's in msg 3
    }

    /**
     * Prepare the next I2NP message for transmission.  This should be run from
     * the Writer thread pool. NTCP 1 or 2.
     * <p>
     * This is the entry point as called from {@code Writer.Runner.run()}.
     *
     * @param prep an instance of PrepBuffer to use as scratch space
     *
     */
    void prepareNextWrite(PrepBuffer prep) {
        if (_closed.get())
            return;
        // Must be established or else session key is null and we can't encrypt
        // This is normal for OB conns but can happen rarely for IB also.
        // wantsWrite() is called at end of OB establishment, and
        // enqueueInfoMessage() is called at end of IB establishment.
        if (!isEstablished()) {
            return;
        }

        synchronized(_writeLock) {
            prepareNextWriteNTCP2(prep);
        }
    }

    /**
     * Buffer for preparing data to be written to the connection.
     * Contains unencrypted data that will be encrypted before transmission.
     */
    static class PrepBuffer {
        /** Unencrypted data buffer. */
        final byte[] unencrypted;

        /**
         * Allocate a buffer large enough for one maximum-sized frame.
         */
        public PrepBuffer() {
            unencrypted = new byte[BUFFER_SIZE];
        }

        /** Initialize (no-op). */
        public void init() {
        }
    }

    /**
     * Reusable NTCP2 payload block list and blocks, per writer thread.
     * The list and the pooled blocks are only used within the
     * prepareNextWriteNTCP2(), sendRouterInfo(), and sendTermination()
     * call stacks, so no other thread can observe or modify them mid-write.
     * Package-private for testing.
     * @since 0.9.71+
     */
    static class BlockPool {
        /** Blocks allocated for the frame being built. */
        final List<Block> blocks = new ArrayList<>(4);
        /** I2NP blocks of the payload being built. */
        private final List<NTCP2Payload.I2NPBlock> i2npBlocks = new ArrayList<>(4);
        /** Padding blocks of the payload being built. */
        private final List<NTCP2Payload.PaddingBlock> paddingBlocks = new ArrayList<>(4);

        /**
         * A pool with nothing reclaimed yet; all three lists start empty and grow only
         * through {@link #release(List)}.
         */
        BlockPool() {}

        /**
         * Get an I2NP block, reusing a pooled one if available.
         *
         * @param m the message the block will carry on the wire
         * @return a pooled block rebound to the message, or a newly allocated one
         */
        NTCP2Payload.I2NPBlock acquireI2NP(I2NPMessage m) {
            if (i2npBlocks.isEmpty())
                return new NTCP2Payload.I2NPBlock(m);
            NTCP2Payload.I2NPBlock b = i2npBlocks.remove(i2npBlocks.size() - 1);
            b.setMessage(m);
            return b;
        }

        /**
         * Get a padding block, reusing a pooled one if available.
         *
         * @param size the number of padding bytes the block must hold
         * @return a pooled block resized to the requested length, or a newly allocated one
         */
        NTCP2Payload.PaddingBlock acquirePadding(int size) {
            if (paddingBlocks.isEmpty())
                return new NTCP2Payload.PaddingBlock(size);
            NTCP2Payload.PaddingBlock b = paddingBlocks.remove(paddingBlocks.size() - 1);
            b.setSize(size);
            return b;
        }

        /**
         * Return the pooled block types to the pool; other types are dropped.
         *
         * @param blocks the blocks written this frame, to be reclaimed for later reuse
         */
        void release(List<Block> blocks) {
            for (Block b : blocks) {
                if (b instanceof NTCP2Payload.I2NPBlock)
                    i2npBlocks.add((NTCP2Payload.I2NPBlock) b);
                else if (b instanceof NTCP2Payload.PaddingBlock)
                    paddingBlocks.add((NTCP2Payload.PaddingBlock) b);
            }
        }
    }

    /** Per-thread pool of payload blocks, avoiding allocator churn. */
    private static final ThreadLocal<BlockPool> _blockPool = new ThreadLocal<BlockPool>() {
        @Override
        protected BlockPool initialValue() {
            return new BlockPool();
        }
    };


    /**
     * NTCP2 frame-size contribution of one message.
     * <p>The NTCP2 block header (3 bytes, see {@link NTCP2Payload#BLOCK_HEADER_SIZE})
     * replaces NTCP1's 16-byte I2NP framing, so a message adds roughly 7 bytes less
     * than its serialized size to a frame. The exact figure mirrors the established
     * packing arithmetic in {@link #prepareNextWriteNTCP2} so frame sizes are
     * unchanged.
     *
     * @param m the message being packed into the frame
     * @return the message's estimated contribution in bytes
     * @since 0.9.71+
     */
    static int getMessageDataSize(I2NPMessage m) {
        return m.getMessageSize() - 7;
    }

    /**
     * Whether a secondary message fits within the preferred NTCP2 payload cap.
     * <p>The cap keeps frames near the preferred 5 KB working size instead of
     * filling the whole 16 KB buffer, so a trailing message is added only while
     * the accumulated frame size (MAC plus already-packed blocks) stays under
     * {@link #NTCP2_PREFERRED_PAYLOAD_MAX}.
     *
     * @param size current frame size (MAC + packed blocks)
     * @param messageDataSize the candidate message's contribution
     * @return true if adding the message keeps the frame at or below the cap
     * @since 0.9.71+
     */
    static boolean canPackMoreMessages(int size, int messageDataSize) {
        return size + messageDataSize <= NTCP2_PREFERRED_PAYLOAD_MAX;
    }

    /**
     * Whether an unsolicited datetime block may be packed into the frame.
     * <p>Scheduled by {@link #_nextMetaTime}; the block is a 2-byte type, 2-byte
     * length and 4-byte datetime, and is only added while the frame still fits in
     * the full 16 KB buffer after its 3-byte block header.
     *
     * @param size current frame size (MAC + packed blocks)
     * @param nextMetaTime when the next datetime block is due, {@link Long#MAX_VALUE}
     *                     until the connection is established
     * @param now current router time
     * @return true if a datetime block is due and fits
     * @since 0.9.71+
     */
    static boolean canSendDatetimeBlock(int size, long nextMetaTime, long now) {
        return nextMetaTime <= now && size + NTCP2Payload.BLOCK_HEADER_SIZE + 4 <= BUFFER_SIZE;
    }

    /**
     * Whether an unsolicited RouterInfo block may be attempted.
     * <p>Scheduled by {@link #_nextInfoTime}; the heuristic estimate of 1024 bytes
     * is only a first gate - the block is discarded and retried later if its real
     * size does not fit (see {@link #canFitRouterInfoBlock}).
     *
     * @param size current frame size (MAC + packed blocks)
     * @param nextInfoTime when the next RouterInfo block is due, {@link Long#MAX_VALUE}
     *                     until the connection is established
     * @param now current router time
     * @return true if a RouterInfo block is due and plausibly fits
     * @since 0.9.71+
     */
    static boolean canSendRouterInfoBlock(int size, long nextInfoTime, long now) {
        return nextInfoTime <= now && size + 1024 <= BUFFER_SIZE;
    }

    /**
     * Whether the serialized RouterInfo block actually fits in the frame.
     * <p>This is the authoritative check after the 1024-byte estimate in
     * {@link #canSendRouterInfoBlock}.
     *
     * @param size current frame size (MAC + packed blocks)
     * @param infoSize the sized RouterInfo block's total length
     * @return true if the block fits within the 16 KB buffer
     * @since 0.9.71+
     */
    static boolean canFitRouterInfoBlock(int size, int infoSize) {
        return size + infoSize <= BUFFER_SIZE;
    }

    /**
     * Whether a received NTCP2 frame length is sane.
     * <p>A frame always carries a 16-byte MAC at minimum; anything shorter is
     * definitely corrupt and the connection is torn down rather than buffered.
     *
     * @param framelen the length announced in the frame header
     * @return true if the frame is long enough to hold its MAC
     * @since 0.9.71+
     */
    static boolean isValidFrameLength(int framelen) {
        return framelen >= OutboundNTCP2State.MAC_SIZE;
    }

    /**
     * The payload length of a data-phase frame: the announced frame length minus
     * the trailing 16-byte AEAD MAC.
     *
     * @param framelen the full frame length, including the MAC
     * @return the number of bytes to pass to {@link NTCP2Payload#processPayload}
     * @since 0.9.71+
     */
    static int framePayloadLength(int framelen) {
        return framelen - OutboundNTCP2State.MAC_SIZE;
    }

    /**
     * Whether the outbound queue is too backlogged to accept new messages.
     * <p>There is a 15 second grace period right after connection establishment so
     * messages queued for a brand-new connection are not refused, then the decision
     * is delegated to the bandwidth limiter's backlog flag.
     *
     * @param uptimeMs the connection uptime, in milliseconds
     * @param outboundBacklogged the bandwidth limiter's backlog flag for the outbound queue
     * @return true if the queue is too backlogged
     * @since 0.9.71+
     */
    static boolean isTooBacklogged(long uptimeMs, boolean outboundBacklogged) {
        return uptimeMs >= 15*1000L && outboundBacklogged;
    }

    /**
     * Whether a received RouterInfo's hash identifies the peer we are talking to.
     *
     * @param h the hash of the received RouterInfo
     * @param peerHash the hash of the remote peer of this connection
     * @return true if the hash matches the remote peer
     * @since 0.9.71+
     */
    static boolean isSamePeer(Hash h, Hash peerHash) {
        return peerHash.equals(h);
    }

    /**
     * Whether a received RouterInfo's hash identifies us.
     *
     * @param h the hash of the received RouterInfo
     * @param ourHash our own router hash
     * @return true if the hash is our own
     * @since 0.9.71+
     */
    static boolean isOwnRouterInfo(Hash h, Hash ourHash) {
        return ourHash.equals(h);
    }

    /**
     * Whether a RouterInfo we just stored is strictly newer than whatever was
     * stored before (or there was no previous copy, so the store is new).
     * copy claims a later publication time.
     * <p>Used on the flood path in {@link NTCP2Payload.PayloadCallback#gotRI}.
     *
     * @param old the previously stored RouterInfo, or null if this is a new store
     * @param ri the RouterInfo that was just stored
     * @return true if flooding is justified by freshness
     * @since 0.9.71+
     */
    static boolean isNewerOrNew(RouterInfo old, RouterInfo ri) {
        return old == null || ri.getPublished() > old.getPublished();
    }

    /**
     * Whether the whole frame can be decrypted in place, bypassing the scratch
     * buffer.
     * <p>Requires being at a frame boundary ({@code received == 0}) with the full
     * announced frame already present in the pumper's buffer, so the ciphertext
     * can be overwritten in place zero-copy.
     *
     * @param received bytes of the current frame read so far outside the shortcut
     * @param remaining bytes left in the pumper buffer
     * @param framelen the announced frame length
     * @return true when the shortcut applies
     * @since 0.9.71+
     */
    static boolean canZeroCopyFrame(int received, int remaining, int framelen) {
        return received == 0 && remaining >= framelen;
    }

    /**
     * Whether the buffered (split-frame) read path needs a fresh destination
     * buffer for the announced frame.
     * <p>Only at a frame boundary with no existing buffer large enough does the
     * read state allocate. Otherwise the partially filled buffer is reused.
     *
     * @param dataBuf the current scratch buffer, or null
     * @param received bytes of the current frame read so far outside the shortcut
     * @param framelen the announced frame length
     * @return true if a new buffer must be allocated
     * @since 0.9.71+
     */
    static boolean needsFrameBuffer(ByteArray dataBuf, int received, int framelen) {
        return received == 0 && (dataBuf == null || dataBuf.getData().length < framelen);
    }

    /**
     * Prepare the next I2NP message for transmission.  This should be run from
     * the Writer thread pool.
     * <p>
     * Caller must synchronize on _writeLock.
     *
     * @param buf we use buf.enencrypted only
     * @since 0.9.36
     */
    private void prepareNextWriteNTCP2(PrepBuffer buf) {
        int size = OutboundNTCP2State.MAC_SIZE;
        BlockPool pool = _blockPool.get();
        List<Block> blocks = pool.blocks;
        blocks.clear();
        long now = _context.clock().now();
        assert Thread.holdsLock(_writeLock);
        if (!_currentOutbound.isEmpty()) {
            if (_log.shouldInfo())
                _log.info("Attempting to send multiple outbound messages with " + _currentOutbound.size() +
                          " already waiting and " + _outbound.size() + " queued");
            return;
        }
        OutNetMessage msg;
        while (true) {
            msg = _outbound.poll();
            if (msg == null)
                return;
            if (msg.getExpiration() >= now)
                break;
            if (_log.shouldInfo())
                _log.info("Dropping expired message in send queue [MsgID " + msg.getMessageId() +
                          ' ' + msg.getMessageType() + "] on " + toShortString());
            _transport.afterSend(msg, false, false, msg.getLifetime());
        }
        _currentOutbound.add(msg);
        I2NPMessage m = msg.getMessage();
        Block block = pool.acquireI2NP(m);
        blocks.add(block);
        size += block.getTotalLength();
        // now add more (maybe)
        if (size < NTCP2_PREFERRED_PAYLOAD_MAX) {
            // keep adding as long as we will be under 5 KB
            while (true) {
                msg = _outbound.peek();
                if (msg == null)
                    break;
                m = msg.getMessage();
                int msz = getMessageDataSize(m);
                if (!canPackMoreMessages(size, msz))
                    break;
                OutNetMessage msg2 = _outbound.poll();
                if (msg2 == null)
                    break;
                if (msg2 != msg) {
                    // if it wasn't the one we sized, put it back
                    _outbound.offer(msg2);
                    break;
                }
                if (msg.getExpiration() >= now) {
                    _currentOutbound.add(msg);
                    block = pool.acquireI2NP(m);
                    blocks.add(block);
                    size += NTCP2Payload.BLOCK_HEADER_SIZE + msz;
                } else {
                    if (_log.shouldInfo())
                        _log.info("Dropping expired message in send queue [MsgID " + msg.getMessageId() +
                                  ' ' + msg.getMessageType() + "] on " + toShortString());
                    _transport.afterSend(msg, false, false, msg.getLifetime());
                }
            }
        }
        if (canSendDatetimeBlock(size, _nextMetaTime, now)) {
            block = new NTCP2Payload.DateTimeBlock(_context);
            blocks.add(block);
            size += block.getTotalLength();
            _nextMetaTime = now + (META_FREQUENCY / 2) + _context.random().nextInt(META_FREQUENCY / 2);
            if (_log.shouldDebug())
                _log.debug("Sending NTCP2 datetime block...");
        }
        // 1024 is an estimate, do final check below
        if (canSendRouterInfoBlock(size, _nextInfoTime, now)) {
            RouterInfo ri = _context.router().getRouterInfo();
            block = new NTCP2Payload.RIBlock(ri, false);
            int sz = block.getTotalLength();
            if (canFitRouterInfoBlock(size, sz)) {
                blocks.add(block);
                size += sz;
                _nextInfoTime = now + (INFO_FREQUENCY / 2) + _context.random().nextInt(INFO_FREQUENCY);
                if (_log.shouldDebug())
                    _log.debug("Sending NTCP2 RouterInfo block...");
            } // else wait until next time
        }
        int availForPad = BUFFER_SIZE - (size + NTCP2Payload.BLOCK_HEADER_SIZE);
        if (availForPad > 0) {
            int padlen = getPaddingSize(size, availForPad);
            // all zeros is fine here
            //block = new NTCP2Payload.PaddingBlock(_context, padlen);
            block = pool.acquirePadding(padlen);
            blocks.add(block);
            size += block.getTotalLength();
        }
        byte[] tmp = size <= BUFFER_SIZE ? buf.unencrypted : new byte[size];
        sendNTCP2(tmp, blocks);
        pool.release(blocks);
    }

    /**
     * NTCP2 only.
     *
     * @param dataSize the total size of the data we are sending
     * @param availForPad the available size for padding, not including padding block header,
     * must be greater than zero
     * @return min 0 max availForPad
     * @since 0.9.36
     */
    private int getPaddingSize(int dataSize, int availForPad) {
        // since we're calculating with percentages, get at least a
        // 0-16 range with the default 0% min 6% max,
        // even for small dataSize.
        if (dataSize < 256)
            dataSize = 256;
        // what we want to send, calculated in proportion to data size
        int minSend = (int) (dataSize * _paddingConfig.getSendMin());
        int maxSend = (int) (dataSize * _paddingConfig.getSendMax());
        // the absolute min and max we can send
        int min = Math.min(minSend, availForPad);
        int max = Math.min(maxSend, availForPad);
        int range = max - min;
        if (range < MIN_PADDING_RANGE) {
            // reduce min to enforce minimum range if possible
            min = Math.max(0, min - (MIN_PADDING_RANGE - range));
            range = max - min;
        } else if (range > MAX_PADDING_RANGE) {
            // Don't send too much, no matter what the config says
            range = MAX_PADDING_RANGE;
        }
        int padlen = min;
        if (range > 0)
            padlen += _context.random().nextInt(1 + range);
        if (_log.shouldDebug())
            _log.debug("Padding params:" +
                      "\n* Data size: " + dataSize + "b * Avail: " + availForPad + "b" +
                      " * MinSend: " + minSend + " * MaxSend: " + maxSend +
                      "\n* Min: " + min + " * Max: " + max + " * Range: " + range +
                      " * Padlen: " + padlen + "b");
        return padlen;
    }

    /**
     * NTCP2 only.
     *
     * @param shouldFlood whether to flood the router info
     * @since 0.9.36, pkg private since 0.9.55 for EventPumper
     */
    void sendOurRouterInfo(boolean shouldFlood) {
        RouterInfo ri = _context.router().getRouterInfo();
        if (ri == null)
            return;
        sendRouterInfo(ri, shouldFlood);
    }

    /**
     * NTCP2 only.
     *
     * @param ri the router info to send to this peer
     * @param shouldFlood whether to flood the router info
     * @since 0.9.36
     */
    private void sendRouterInfo(RouterInfo ri, boolean shouldFlood) {
        // no synch needed, sendNTCP2() is synched
        if (_log.shouldDebug())
            _log.debug("Sending a RouterInfo for: " + ri.getHash() + " flood? " + shouldFlood);
        BlockPool pool = _blockPool.get();
        List<Block> blocks = pool.blocks;
        blocks.clear();
        Block block = new NTCP2Payload.RIBlock(ri, shouldFlood);
        int size = block.getTotalLength();
        if (size + NTCP2Payload.BLOCK_HEADER_SIZE > BUFFER_SIZE) {
            if (_log.shouldWarn())
                _log.warn("RouterInfo [" + ri.getHash().toBase64().substring(0,6) +
                          "] too big for buffer (Size: " +
                          (size + NTCP2Payload.BLOCK_HEADER_SIZE) + " / Max: " + BUFFER_SIZE + ")");
            return;
        }
        blocks.add(block);
        int availForPad = BUFFER_SIZE - (size + NTCP2Payload.BLOCK_HEADER_SIZE);
        if (availForPad > 0) {
            int padlen = getPaddingSize(size, availForPad);
            // all zeros is fine here
            block = pool.acquirePadding(padlen);
            blocks.add(block);
        }
        // use a "read buf" for the temp array
        ByteArray dataBuf = acquireReadBuf();
        sendNTCP2(dataBuf.getData(), blocks);
        releaseReadBuf(dataBuf);
        pool.release(blocks);
    }

    /**
     * NTCP2.
     * For NTCP2, sends termination and then closes the connection after a brief delay.
     *
     * @since 0.9.36
     */
    void sendTerminationAndClose() {
        ReadState rs = null;
        if (isEstablished()) {
            synchronized (_readLock) {
                rs = _curReadState;
            }
        }
        if (rs != null)
            sendTermination(REASON_TIMEOUT, rs.getFramesReceived());
        else
            close();
    }

    /**
     * NTCP2 only. Also closes the connection after a brief delay.
     *
     * @param reason the termination reason code, one of the REASON_* constants
     * @param validFramesRcvd how many valid frames were received, reported to the peer
     * @since 0.9.36
     */
    private void sendTermination(int reason, int validFramesRcvd) {
        // So we don't get called again by the event pumper idle closer
        _lastSendTime = _context.clock().now();
        // TODO add param to clear queues?
        // no synch needed, sendNTCP2() is synched
        if (_log.shouldInfo())
            _log.info("Sending termination, reason: " + reason + "; Valid frames received: " + validFramesRcvd + " on " + this);
        BlockPool pool = _blockPool.get();
        List<Block> blocks = pool.blocks;
        blocks.clear();
        Block block = new NTCP2Payload.TerminationBlock(reason, validFramesRcvd);
        int plen = block.getTotalLength();
        blocks.add(block);
        int padlen = getPaddingSize(plen, PADDING_MAX);
        if (padlen > 0) {
            // all zeros is fine here
            block = pool.acquirePadding(padlen);
            blocks.add(block);
        }
        // use a "read buf" for the temp array
        ByteArray dataBuf = acquireReadBuf();
        synchronized(_writeLock) {
            if (_sender != null) {
                sendNTCP2(dataBuf.getData(), blocks);
                // sendNTCP2() -> wantsWrite() -> write() -> pumper.wantsWrite() -> EventPumper.processWrite()
                // While we still hold _writeLock, only the queue + interest registration
                // happen inline; the socket write is deferred to the pumper thread.
                if (_sender != null) {
                    _sender.destroy();
                    // this "plugs" the NTCP2 sender, so sendNTCP2()
                    // won't send any more after the termination.
                    _sender = null;
                    new DelayedCloser();
                }
            }
        }
        releaseReadBuf(dataBuf);
        pool.release(blocks);
    }

    /**
     * This constructs the payload from the blocks, using the
     * tmp byte array, then encrypts the payload and
     * passes it to the pumper for writing.
     * <p>The frame bytes go into a fixed-size pooled buffer (see
     * {@link EventPumper#acquireWriteBuf}) instead of a per-frame allocation:
     * frames are variable-length, so the old {@code new byte[2 + framelen]} rarely
     * matched the pool's size class and was reallocated on every send. Only the
     * used {@code 2 + framelen} prefix is handed to the writer; the pumper returns
     * the buffer to the pool after the frame has fully drained.
     *
     * @param tmp the output buffer, must have room for the blocks written by
     *            {@link net.i2p.router.transport.ntcp.NTCP2Payload#writePayload}; may be
     *            released immediately on return
     * @param blocks the blocks to write into tmp
     * @since 0.9.36
     */
    private void sendNTCP2(byte[] tmp, List<Block> blocks) {
        int payloadlen = NTCP2Payload.writePayload(tmp, 0, blocks);
        int framelen = payloadlen + OutboundNTCP2State.MAC_SIZE;

        synchronized(_writeLock) {
            if (_sender == null) {
                if (_log.shouldInfo())
                    _log.info("Sender has disappeared");
                return;
            }
            byte[] enc = EventPumper.acquireWriteBuf();
            // The pooled buffer holds one fixed class: BUFFER_SIZE payload + MAC
            // + 2 length bytes. A single message (e.g. a streaming fragment or a
            // large DatabaseStoreMessage) can exceed BUFFER_SIZE - MAC_SIZE, so the
            // frame's payload would not fit. Send such frames in an exact-size
            // spill allocation instead; releaseWriteBuf() discards anything that is
            // not the pooled WRITE_BUFSIZE class, so the spill is released on drain
            // without corrupting the pool.
            if (enc.length < 2 + framelen) {
                EventPumper.releaseWriteBuf(enc);
                enc = new byte[2 + framelen];
            }
            try {
                _sender.encryptWithAd(null, tmp, 0, enc, 2, payloadlen);
            } catch (GeneralSecurityException gse) {
                // TODO anything else?
                EventPumper.releaseWriteBuf(enc);
                _log.error("Data encryption error", gse);
                return;
            }
            // siphash ^ len
            long sipIV = SipHashInline.hash24(_sendSipk1, _sendSipk2, _sendSipIV);
            toLong8LE(_sendSipIV, 0, sipIV);
            enc[0] = (byte) ((framelen >> 8) ^ (sipIV >> 8));
            enc[1] = (byte) (framelen ^ sipIV);
            // Only the used prefix is queued; the array itself stays in the
            // fixed-size pool until the pumper drains the frame.
            wantsWrite(enc, 0, framelen + 2);
        }

        if (_log.shouldDebug()) {
            StringBuilder buf = new StringBuilder(256);
            buf.append("Sending ").append(blocks.size())
               .append(" blocks in ").append(framelen)
               .append(" byte NTCP2 frame...");
            for (int i = 0; i < blocks.size(); i++) {
                buf.append("Block ").append(i).append(": ").append(blocks.get(i).toString());
            }
            _log.debug(buf.toString());
        }
    }

    /**
     * Async callback after the outbound connection was completed (this should NOT block,
     * as it occurs in the selector thread).
     */
    synchronized void outboundConnected() {
        if (_establishState == EstablishBase.FAILED) {
            _conKey.cancel();
            try {_chan.close(); } catch (IOException ignored) { /* ignored */ }
            return;
        }
        try {
            if (_conKey != null) {
                EventPumper.setInterest(_conKey, SelectionKey.OP_READ);
            }
        } catch (CancelledKeyException cke) {
            try {_chan.close(); } catch (IOException ignored) { /* ignored */ }
            return;
        }
        // schedule up the beginning of our handshaking by calling prepareNextWrite on the
        // writer thread pool
        _transport.getWriter().wantsWrite(this, "outbound connected");
    }

    /**
     * The FifoBandwidthLimiter.CompleteListener callback.
     * Does the delayed read.
     * <p>
     * Fires on the limiter thread when the throttled request completes, and
     * synchronously on the closing thread when {@link #close()} aborts the request.
     * The ownership hand-off in {@link #removeIBRequest(FIFOBandwidthLimiter.Request)} guarantees the attached
     * read buffer is delivered or released exactly once across those racers.
     */
    private class InboundListener implements FIFOBandwidthLimiter.CompleteListener {
        /** Runs when the limiter releases read bandwidth. */
        private InboundListener() {}
        @Override
        public void complete(FIFOBandwidthLimiter.Request req) {
            // Lost the hand-off: the other racer owns the attached buffer now.
            if (!removeIBRequest(req))
                return;
            ByteBuffer buf = (ByteBuffer)req.attachment();
            if (_closed.get()) {
                EventPumper.releaseBuf(buf);
                return;
            }
            _context.statManager().addRateData("ntcp.throttledReadComplete", (_context.clock().now()-req.getRequestTime()));
            recv(buf);
            // our reads used to be bw throttled (during which time we were no
            // longer interested in reading from the network), but we aren't
            // throttled anymore, so we should resume being interested in reading
            // unless the read queue is full, in which case the reader thread
            // re-arms reads once it has drained the queue
            if (!_readPaused)
                _transport.getPumper().wantsRead(NTCPConnection.this);
        }
    }

    /**
     * The FifoBandwidthLimiter.CompleteListener callback.
     * Does the delayed write.
     */
    private class OutboundListener implements FIFOBandwidthLimiter.CompleteListener {
        /** Runs when the limiter releases write bandwidth. */
        private OutboundListener() {}
        @Override
        public void complete(FIFOBandwidthLimiter.Request req) {
            removeOBRequest(req);
            ByteBuffer buf = (ByteBuffer)req.attachment();
            if (_closed.get()) {
                return;
            }
            _context.statManager().addRateData("ntcp.throttledWriteComplete", (_context.clock().now()-req.getRequestTime()));
            write(buf);
        }
    }

    /**
     * Hands off ownership of an in-flight read bandwidth request back to the
     * caller. Exactly one caller - the limiter thread when the request is
     * fully allocated, or the closing thread via abort() -&gt; notifyAllocation()
     * - receives true and thereby owns the request's attached read buffer;
     * false callers must leave the buffer alone. This membership check is what
     * makes the read-back pressure release path exactly-once without a lock.
     *
     * @param req the request to hand off
     * @return true if this caller won the hand-off and owns the attached buffer
     * @since 0.9.71+
     */
    private boolean removeIBRequest(FIFOBandwidthLimiter.Request req) {
        return _bwInRequests.remove(req);
    }

    /**
     * Tracks an inbound bandwidth request.
     *
     * @param req the request to track
     */
    private void addIBRequest(FIFOBandwidthLimiter.Request req) {
        _bwInRequests.add(req);
    }

    /**
     * Stops tracking an outbound bandwidth request.
     *
     * @param req the request to forget
     */
    private void removeOBRequest(FIFOBandwidthLimiter.Request req) {
        _bwOutRequests.remove(req);
    }

    /**
     * Tracks an outbound bandwidth request.
     *
     * @param req the request to track
     */
    private void addOBRequest(FIFOBandwidthLimiter.Request req) {
        _bwOutRequests.add(req);
    }

    /**
     * Call when there is data ready to write.
     * If we have bandwidth, calls write() which calls {@code EventPumnper.wantsWrite(}con).
     * If no bandwidth, calls {@link #queuedWrite}.
     *
     * @param data the data to write
     * @since moved from {@link EventPumper} in 0.9.52
     */
    void wantsWrite(byte[] data) {
        wantsWrite(data, 0, data.length);
    }

    /**
     * Call when there is data ready to write.
     * If we have bandwidth, calls write() which calls {@code EventPumnper.wantsWrite(}con).
     * If no bandwidth, calls queuedWrite().
     *
     * @param data the data to write
     * @param off the offset in the data
     * @param len the length of the data
     * @since 0.9.35 off/len version, moved from EventPumper in 0.9.52
     */
    void wantsWrite(byte[] data, int off, int len) {
        ByteBuffer buf = ByteBuffer.wrap(data, off, len);
        FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestOutbound(len, 0, "NTCP write");
        if (req.getPendingRequested() > 0) {
            if (_log.shouldInfo())
                _log.info("Queued write on " + toString() + " for " + len);
            _context.statManager().addRateData("ntcp.wantsQueuedWrite", 1);
            queuedWrite(buf, req);
        } else {
            write(buf);
        }
    }

    /**
     * We have read the data in the buffer, but we can't process it locally yet,
     * because we're choked by the bandwidth limiter.  Cache the contents of
     * the buffer (not copy) and register ourselves to be notified when the
     * contents have been fully allocated
     * The request joins the in-flight set before the listener is registered, so
     * the listener's ownership hand-off always has a member to claim - including
     * when the request is already satisfied and setCompleteListener() completes
     * synchronously.
     *
     * @param buf the buffer to queue
     * @param req the bandwidth request
     * @since 0.9.71+ listener registration ordered after set membership
     */
    void queuedRecv(ByteBuffer buf, FIFOBandwidthLimiter.Request req) {
        req.attach(buf);
        addIBRequest(req);
        req.setCompleteListener(_inboundListener);
    }

    /**
     * Queue a buffer for writing once its bandwidth has been allocated, the
     * outbound counterpart of
     * {@link #queuedRecv(ByteBuffer, FIFOBandwidthLimiter.Request)}.
     *
     * @param buf the buffer to queue
     * @param req the bandwidth request that re-triggers the write once satisfied
     */
    private void queuedWrite(ByteBuffer buf, FIFOBandwidthLimiter.Request req) {
        req.attach(buf);
        req.setCompleteListener(_outboundListener);
        addOBRequest(req);
    }

    /**
     * The contents of the buffer have been read and can be processed asap.
     * This should not block, and the NTCP connection now owns the buffer
     * to do with as it pleases BUT it should eventually copy out the data
     * and call {@link net.i2p.router.transport.ntcp.EventPumper#releaseBuf}.
     *
     * @param buf the buffer received
     */
    void recv(ByteBuffer buf) {
        if (isClosed()) {
            if (_log.shouldWarn())
                _log.warn("Ignoring recv() on closed connection");
            // We own the buffer; return it to the pool rather than leaking it.
            EventPumper.releaseBuf(buf);
            return;
        }
        synchronized(_statLock) {
            _bytesReceived += buf.remaining();
            updateStats();
        }
        _readBufs.offer(buf);
        if (shouldPauseReads(_readPaused, _readBufs.size())) {
            _readPaused = true;
            _context.statManager().addRateData("ntcp.readQueuePause", 1);
            if (_readBufs.isEmpty()) {
                // the reader drained the queue before the pause took effect,
                // and its empty poll saw no pause to resume from, so resume
                // here or nothing would re-arm the read interest
                _readPaused = false;
                _transport.getPumper().wantsRead(this);
            }
        }
        _transport.getReader().wantsRead(this);
    }

    /**
     * Decide whether socket reads should stop because the queue of unread
     * buffers has reached the limit. Once paused, reads stay paused until the
     * queue drains, so a peer cannot push us back over the limit repeatedly.
     *
     * @param readPaused whether reads are already paused
     * @param queuedReads buffers waiting to be processed
     * @return true if the pause should be entered
     * @since 0.9.71+
     */
    static boolean shouldPauseReads(boolean readPaused, int queuedReads) {
        return !readPaused && queuedReads >= MAX_READ_BUFS;
    }

    /**
     * Whether the read queue is full and the socket should stop being read
     * until the queue has drained.
     *
     * @return true if reads are paused
     * @since 0.9.71+
     */
    boolean isReadQueueFull() {
        return _readPaused;
    }

    /**
     * The contents of the buffer have been encrypted / padded / etc and have
     * been fully allocated for the bandwidth limiter.
     * <p>
     * Only enqueues the buffer and registers write interest. The socket write
     * itself is performed exclusively by the pumper thread in
     * {@link net.i2p.router.transport.ntcp.EventPumper#processWrite}: this single-writer contract guarantees that
     * at most one thread calls {@link java.nio.channels.SocketChannel#write} on this connection at a
     * time, so frame bytes can never interleave. It also removes the need to
     * hold _writeLock across the socket drain.
     *
     * @param buf the fully allocated buffer to write out
     * @since 0.9.71+ single-writer contract (previously attempted a direct,
     * lock-serialized drain from the calling thread); the
     * "{@code ntcp.writeBufs.size}" stat is now sampled at most
     * once per second instead of every frame
     */
    private void write(ByteBuffer buf) {
        if (_writeBufs.size() >= MAX_WRITE_BUFS) {
            _log.info("Write queue full (" + _writeBufs.size() + ") on " + toShortString() + " -> Dropping buffer");
            _transport.getContext().statManager().addRateData("ntcp.writeBufs.size", _writeBufs.size(), MAX_WRITE_BUFS);
            _transport.getContext().statManager().addRateData("ntcp.writeQueueFull", 1);
            return;
        }
        _writeBufs.offer(buf);
        // Record the backed-up count at most once per second per connection:
        // a RateStat lookup per frame is measurable on the hot path (see #4 pattern).
        long now = System.currentTimeMillis();
        if (now - _lastWriteBufsStat > 1000L) {
            _lastWriteBufsStat = now;
            _transport.getContext().statManager().addRateData("ntcp.writeBufs.size", _writeBufs.size(), MAX_WRITE_BUFS);
        }
        _transport.getPumper().wantsWrite(this);
    }

    /**
     * Next read buffer, or null if none available.
     *
     * @return the next read buffer, or null
     */
    ByteBuffer getNextReadBuf() {
        ByteBuffer buf = _readBufs.poll();
        if (shouldResumeReads(_readPaused, buf == null)) {
            // queue drained: resume socket reads (re-arms OP_READ in the pumper)
            _readPaused = false;
            _transport.getPumper().wantsRead(this);
        }
        return buf;
    }

    /**
     * Decide whether socket reads should start again after the read queue
     * paused them. Only the empty queue ends the pause, so a slow reader keeps
     * applying backpressure until it has caught up.
     *
     * @param readPaused whether reads are currently paused
     * @param queueEmpty whether the queue came up empty
     * @return true if the pause should be lifted
     * @since 0.9.71+
     */
    static boolean shouldResumeReads(boolean readPaused, boolean queueEmpty) {
        return readPaused && queueEmpty;
    }

    /**
     * Replaces getWriteBufCount().
     * Lock-free; backed by {@link ConcurrentLinkedQueue}, and only the pumper's drain
     * loop consumes. No synchronization required.
     *
     * @return true if write buffer is empty
     * @since 0.8.12
     */
    boolean isWriteBufEmpty() {
        return _writeBufs.isEmpty();
    }

    /**
     * Returns but does not remove the buffer.
     * Call removeWriteBuf() after write complete.
     * Lock-free; backed by ConcurrentLinkedQueue, and only the pumper's drain
     * loop consumes. No synchronization required.
     *
     * @return null if none available
     */
    ByteBuffer getNextWriteBuf() {
        return _writeBufs.peek(); // not remove!  we removeWriteBuf afterwards
    }

    /**
     * Remove the buffer, which must be the current head of _writeBufs.
     * Called only from the pumper's drain loop ({@link net.i2p.router.transport.ntcp.EventPumper#processWrite}).
     * Removal is O(1) via poll(): the single-writer drain guarantees the
     * buffer is at the head (producers only add, the pumper is the only
     * consumer), replacing the previous O(n) equals()-scan.
     * The completion bookkeeping self-synchronizes on _writeLock; the
     * _writeBufs queue operations are lock-free.
     *
     * @param buf the buffer to remove
     * @since 0.9.71+ O(1) head removal (was _writeBufs.remove(buf))
     */
    void removeWriteBuf(ByteBuffer buf) {
        // never clear OutNetMessages during establish phase
        boolean clearMessage = isEstablished();
        synchronized(_statLock) {
            _bytesSent += buf.capacity();
            updateStats();
        }
        // O(1) removal of the head. Single-writer drain (#5) guarantees buf IS
        // the head: producers only offer(), and the pumper is the only consumer.
        _writeBufs.poll();
        // The completion bookkeeping touches _currentOutbound, which the writer
        // pool also manipulates while building frames; that section stays
        // serialized on _writeLock even though the drain itself no longer needs it.
        synchronized(_writeLock) {
            if (clearMessage) {
                List<OutNetMessage> msgs = null;
                if (!_currentOutbound.isEmpty()) {
                    msgs = new ArrayList<>(_currentOutbound);
                    _currentOutbound.clear();
                }
                // push through the bw limiter to reach _writeBufs
                if (!_outbound.isEmpty())
                    _transport.getWriter().wantsWrite(this, "write completed");
                if (msgs != null) {
                    _lastSendTime = _context.clock().now();
                    // stats once is fine for all of them
                    _context.statManager().addRateData("ntcp.sendTime", msgs.get(0).getSendTime());
                    for (OutNetMessage msg : msgs) {
                        if (_log.shouldDebug()) {
                            _log.debug("I2NP message " + _messagesWritten + "/" + msg.getMessageId() + " sent after "
                                      + msg.getSendTime() + "/"
                                      + msg.getLifetime()
                                      + " with " + buf.capacity() + " bytes\n* UniqueID: " + System.identityHashCode(msg) + " on " + toString());
                        }
                        _transport.sendComplete(msg);
                    }
                    _messagesWritten.addAndGet(msgs.size());
                }
            } else {
                // push through the bw limiter to reach _writeBufs
                if (!_outbound.isEmpty())
                    _transport.getWriter().wantsWrite(this, "write completed");
                if (_log.shouldDebug())
                    _log.debug("I2NP meta message sent completely");
                // need to increment as EventPumper will close conn if not completed
                _messagesWritten.incrementAndGet();
            }
        } // synchronized(_writeLock)
    }

    /** Bytes received so far, guarded by _statLock. */
    private long _bytesReceived;
    /** Bytes sent so far, guarded by _statLock. */
    private long _bytesSent;
    /** _bytesReceived when we last updated the rate. */
    private long _lastBytesReceived;
    /** _bytesSent when we last updated the rate. */
    private long _lastBytesSent;
    /** Current send rate, in bytes per second. */
    private float _sendBps;
    /** Current receive rate, in bytes per second. */
    private float _recvBps;

    /**
     * Current send rate in Bps.
     *
     * @return the rate, in bytes per second
     */
    public float getSendRate() { synchronized(_statLock) { return _sendBps; } }
    /**
     * Current receive rate in Bps.
     *
     * @return the rate, in bytes per second
     */
    public float getRecvRate() { synchronized(_statLock) { return _recvBps; } }

    /**
     * Stats only for console.
     */
    private void updateStats() {
        synchronized(_statLock) {
            long now = _context.clock().now();
            long time = now - _lastRateUpdated;
            // If enough time has passed...
            // Perhaps should synchronize, but if so do the time check before synching...
            // only for console so don't bother....
            if (time >= STAT_UPDATE_TIME_MS) {
                long totS = _bytesSent;
                long totR = _bytesReceived;
                long sent = totS - _lastBytesSent; // How much we sent meanwhile
                long recv = totR - _lastBytesReceived; // How much we received meanwhile
                _lastBytesSent = totS;
                _lastBytesReceived = totR;
                _lastRateUpdated = now;

                _sendBps = (0.9f)*_sendBps + (0.1f)*(sent*1000f)/time;
                _recvBps = (0.9f)*_recvBps + (0.1f)*((float)recv*1000)/time;
            }
        }
    }

    /**
     * Connection must be established!
     * <p>
     * The contents of the buffer include some fraction of one or more
     * encrypted and encoded I2NP messages.  Individual I2NP messages are
     * encoded as "sizeof(data)+data+pad+crc", and those are encrypted
     * with the session key and the last 16 bytes of the previous encrypted
     * I2NP message.
     * <p>
     * The NTCP connection now owns the buffer
     * BUT it must copy out the data
     * as reader will call {@link net.i2p.router.transport.ntcp.EventPumper#releaseBuf}.
     * <p>
     * This is the entry point as called from {@link net.i2p.router.transport.ntcp.Reader#processRead}.
     *
     * @param buf the buffer containing encrypted data
     */
    void recvEncryptedI2NP(ByteBuffer buf) {
        if (isBanned()) {return;}
        synchronized(_readLock) {
        if (_curReadState == null)
            throw new IllegalStateException("not established");
        _curReadState.receive(buf);
        }
    }

    /**
     * Handle a received timestamp, NTCP 1 or 2.
     * Caller must synch.
     *
     * @param ts his timestamp in seconds, NOT ms
     * @since 0.9.36 pulled out of readMeta() above
     */
    private void receiveTimestamp(long ts) {
        long ourTs = (_context.clock().now() + 500) / 1000;
        long newSkew = (ourTs - ts);
        if (Math.abs(newSkew*1000) > Router.CLOCK_FUDGE_FACTOR) {
            if (_log.shouldWarn())
                _log.warn("Peer's skew jumped too far (from " + _clockSkew + "s to " + newSkew + "s): " + toString());
            _context.statManager().addRateData("ntcp.corruptSkew", newSkew);
            close();
            return;
        }
        _context.statManager().addRateData("ntcp.receiveMeta", newSkew);
        if (_log.shouldDebug())
            _log.debug("Received NTCP metadata, old skew of " + _clockSkew + "s, new skew of " + newSkew + "s");
        // FIXME does not account for RTT
        _clockSkew = newSkew;
    }

    /**
     * Takes a read buffer from the cache.
     *
     * @return the buffer, zeroed
     */
    private static ByteArray acquireReadBuf() {
        return _dataReadBufs.acquire();
    }

    /**
     * Returns a read buffer to the cache.
     *
     * @param buf the buffer to return
     */
    private static void releaseReadBuf(ByteArray buf) {
        _dataReadBufs.release(buf, false);
    }

    /** The current phase of reading from the socket. */
    private interface ReadState {
        /**
         * Feeds freshly read data to this state.
         *
         * @param buf the data read
         */
        public void receive(ByteBuffer buf);
        /** Releases the buffers this state holds. */
        public void destroy();
        /**
         * Gives the valid frames read so far.
         *
         * @return the frame count
         */
        public int getFramesReceived();
    }

    /**
     * How long a connection's ban/blocklist verdict stays cached, in ms.
     * <p>
     * isBanned() is called on every data-phase frame, and each call copies the
     * remote IP and binary-searches the blocklist's range array before it looks
     * the peer up in the banlist. Caching the verdict for a few seconds takes
     * that work off the busiest receive path; a ban or block added while a
     * connection is already up takes effect on the next check instead of the
     * next frame.
     * @since 0.9.72+
     */
    static final long BAN_RECHECK_MS = 5000;

    /** Cached ban/blocklist verdict, valid until {@link #_nextBanCheck}. */
    private volatile boolean _banned;
    /** Clock time after which the cached verdict is stale, in ms. */
    private volatile long _nextBanCheck;

    /**
     * Whether the cached ban/blocklist verdict has to be re-read.
     * <p>
     * Pure and static so the boundary can be pinned without a socket: the
     * deadline is inclusive, so a verdict cached at T is re-read at T + interval
     * and not before.
     *
     * @param now current clock time in ms
     * @param nextCheckAt the time the cached verdict stops being valid, in ms;
     *                    0 means nothing is cached yet
     * @return true if the lists must be read again
     * @since 0.9.72+
     */
    static boolean banCheckIsDue(long now, long nextCheckAt) {
        return now >= nextCheckAt;
    }

    /**
     * Check if the peer is blocklisted or banned.
     * If so, log and close the connection.
     * <p>
     * The answer is cached for {@link #BAN_RECHECK_MS}, so a caller on a frame
     * loop does not pay for the lookup per frame. The first call on a connection
     * is never served from the cache, so establishment still rejects a banned
     * peer immediately.
     *
     * @return true if banned or blocklisted, false otherwise
     * @since 0.9.68+
     */
    public boolean isBanned() {
        if (_remotePeer == null) {return false;}

        long now = _context.clock().now();
        if (!banCheckIsDue(now, _nextBanCheck)) {return _banned;}
        _nextBanCheck = now + BAN_RECHECK_MS;

        Hash peerHash = _remotePeer.calculateHash();
        if (peerHash == null) {return false;}

        byte[] ip = getRemoteIP();
        if (ip != null && _context.blocklist().isBlocklisted(ip)) {
                if (_log.shouldInfo()) {
                    _log.info("IP address " + Addresses.toString(ip) + " is blocklisted -> Closing connection...");
                }
                _banned = true;
                close();
                return true;
        }

        if (_context.banlist().isBanlisted(peerHash)) {
            if (_log.shouldInfo()) {
                _log.info("Router [" + peerHash.toBase64().substring(0,6) + "] is banned -> Closing connection...");
            }
            _banned = true;
            close();
            return true;
        }

        // Clear the cached verdict on the clean answer as well, so a connection
        // already closing does not keep reporting itself banned from a verdict
        // written in an earlier window.
        _banned = false;
        return false;
    }


    /**
     * We are Alice. NTCP2 only.
     * <p>
     * Caller MUST call recvEncryptedI2NP() after, for any remaining bytes in receive buffer.
     *
     * @param sender use to send to Bob
     * @param receiver use to receive from Bob
     * @param sip_ab 24 bytes to init SipHash to Bob
     * @param sip_ba 24 bytes to init SipHash from Bob
     * @param clockSkew OUR clock minus BOB's clock in seconds (may be negative, obviously, but |val| should
     * be under 1 minute)
     * @since 0.9.36
     */
    synchronized void finishOutboundEstablishment(CipherState sender, CipherState receiver,
                                                   byte[] sip_ab, byte[] sip_ba, long clockSkew) {
        if (isBanned()) {return;}
        finishEstablishment(sender, receiver, sip_ab, sip_ba, clockSkew);
        _context.statManager().addRateData("ntcp.outboundEstablishTime", _establishedOn - _created);
        RouterIdentity remote = getRemotePeer();
        if (remote != null) {
            _transport.markReachable(remote.calculateHash(), false);
        }
        if (!_outbound.isEmpty())
            _transport.getWriter().wantsWrite(this, "outbound established");
        // NTCP2 outbound cannot have extra data
    }

    /**
     * We are Bob. NTCP2 only.
     * <p>
     * Caller MUST call recvEncryptedI2NP() after, for any remaining bytes in receive buffer.
     *
     * @param sender use to send to Alice
     * @param receiver use to receive from Alice
     * @param sip_ba 24 bytes to init SipHash to Alice
     * @param sip_ab 24 bytes to init SipHash from Alice
     * @param clockSkew OUR clock minus ALICE's clock in seconds (may be negative, obviously, but |val| should
     * be under 1 minute)
     * @param hisPadding may be null
     * @since 0.9.36
     */
    synchronized void finishInboundEstablishment(CipherState sender, CipherState receiver,
                                                 byte[] sip_ba, byte[] sip_ab, long clockSkew,
                                                 NTCP2Options hisPadding) {
        if (isBanned()) {return;}
        finishEstablishment(sender, receiver, sip_ba, sip_ab, clockSkew);
        _context.statManager().addRateData("ntcp.inboundEstablishTime", _establishedOn - _created);
        if (hisPadding != null) {
            _paddingConfig = OUR_PADDING.merge(hisPadding);
            if (_log.shouldDebug())
                _log.debug("Received padding options:" +
                          "\n* His padding options: " + hisPadding +
                          "\n* Our padding options: " + OUR_PADDING +
                          "\n* Merged config:       " + _paddingConfig);
        }
        NTCPConnection toClose = _transport.inboundEstablished(this);
        if (toClose != null && toClose != this) {
            int drained = toClose.drainOutboundTo(_outbound);
            if (_log.shouldDebug())
                _log.debug("Old connection closed: " + toClose + " replaced by " + this + "; drained " + drained);
            _context.statManager().addRateData("ntcp.inboundEstablishedDuplicate", toClose.getUptime());
            toClose.close();
        }
    }

    /**
     * We are Bob. NTCP2 only.
     * This is only for invalid payload received in message 3. We send a termination and close.
     * There will be no receiving.
     *
     * @param sender use to send to Alice
     * @param sip_ba 24 bytes to init SipHash to Alice
     * @param reason the reason code
     * @since 0.9.36
     */
    synchronized void failInboundEstablishment(CipherState sender, byte[] sip_ba, int reason) {
        byte[] ip = getRemoteIP();
        _sender = sender;
        _sendSipk1 = fromLong8LE(sip_ba, 0);
        _sendSipk2 = fromLong8LE(sip_ba, 8);
        _sendSipIV = new byte[SIP_IV_LENGTH];
        System.arraycopy(sip_ba, 16, _sendSipIV, 0, SIP_IV_LENGTH);
        _establishState = EstablishBase.VERIFIED;
        _establishedOn = _context.clock().now();
        _nextMetaTime = Long.MAX_VALUE;
        _nextInfoTime = Long.MAX_VALUE;
        _paddingConfig = OUR_PADDING;
        sendTermination(reason, 0);
        _transport.getPumper().blockIP(ip);
    }

    /**
     * We are Alice or Bob. NTCP2 only.
     *
     * @param sender use to send
     * @param receiver use to receive
     * @param sip_send 24 bytes to init SipHash out
     * @param sip_recv 24 bytes to init SipHash in
     * @param clockSkew the clock skew in seconds; a positive value means our clock is ahead
     * @since 0.9.36
     */
    private synchronized void finishEstablishment(CipherState sender, CipherState receiver,
                                                  byte[] sip_send, byte[] sip_recv, long clockSkew) {
        if (isBanned()) {return;}
        if (_establishState == EstablishBase.VERIFIED) {
            IllegalStateException ise = new IllegalStateException("Already finished on " + this);
            _log.error("Already finished", ise);
            throw ise;
        }
        _sender = sender;
        _sendSipk1 = fromLong8LE(sip_send, 0);
        _sendSipk2 = fromLong8LE(sip_send, 8);
        _sendSipIV = new byte[SIP_IV_LENGTH];
        System.arraycopy(sip_send, 16, _sendSipIV, 0, SIP_IV_LENGTH);
        if (_log.shouldDebug())
            _log.debug("Send SipHash keys installed");
        _clockSkew = clockSkew;
        _establishState = EstablishBase.VERIFIED;
        _establishedOn = _context.clock().now();
        _nextMetaTime = _establishedOn + (META_FREQUENCY / 2) + _context.random().nextInt(META_FREQUENCY);
        _nextInfoTime = _establishedOn + (INFO_FREQUENCY / 2) + _context.random().nextInt(INFO_FREQUENCY);
        _curReadState = new NTCP2ReadState(receiver, sip_recv);
    }

    /**
     * Read the encrypted message
     * <p>
     * Call all methods from synchronized parent method.
     *
     * @since 0.9.36
     */
    private class NTCP2ReadState implements ReadState, NTCP2Payload.PayloadCallback {
        /** Scratch buffer for the encrypted frame length. */
        private final byte[] _recvLen = new byte[2];
        /** First SipHash ratchet key for inbound data. */
        private final long _sipk1;
        /** Second SipHash ratchet key for inbound data. */
        private final long _sipk2;
        /** The SipHash ratchet IV, as a byte array. */
        private final byte[] _sipIV = new byte[SIP_IV_LENGTH];
        /** Cipher state for inbound data. */
        private final CipherState _rcvr;
        /** Size of the next frame, valid only once _received is non-negative. */
        private int _framelen;
        /** Bytes received of the current frame, -2 until the length is known. */
        private int _received = -2;
        /** Buffer holding the frame being read. */
        private ByteArray _dataBuf;
        /** Valid frames received in this data phase. */
        private int _frameCount;
        /** True once a termination block was seen, logging only. */
        private boolean _terminated;

        /**
         * NTCP2 read state initialized from the given key data.
         *
         * @param rcvr the cipher state receiving the data
         * @param keyData using first 24 bytes
         */
        public NTCP2ReadState(CipherState rcvr, byte[] keyData) {
            _rcvr = rcvr;
            _sipk1 = fromLong8LE(keyData, 0);
            _sipk2 = fromLong8LE(keyData, 8);
            System.arraycopy(keyData, 16, _sipIV, 0, SIP_IV_LENGTH);
            if (_log.shouldDebug())
                _log.debug("Recv SipHash keys installed");
        }

        @Override
        public void receive(ByteBuffer buf) {
            if (_terminated) {
                if (_log.shouldWarn())
                    _log.warn("Received " + buf.remaining() + " after termination on " + NTCPConnection.this);
                return;
            }
            while (buf.hasRemaining()) {
                if (_received == -2) {
                    _recvLen[0] = buf.get();
                    _received++;
                }
                if (_received == -1 && buf.hasRemaining()) {
                    _recvLen[1] = buf.get();
                    _received++;
                    long sipIV = SipHashInline.hash24(_sipk1, _sipk2, _sipIV);
                    _recvLen[0] ^= (byte) (sipIV >> 8);
                    _recvLen[1] ^= (byte) sipIV;
                    toLong8LE(_sipIV, 0, sipIV);
                    _framelen = (int) DataHelper.fromLong(_recvLen, 0, 2);
                    if (!isValidFrameLength(_framelen)) {
                        if (_log.shouldWarn())
                            _log.warn("Short frame length: " + _framelen + " on " + NTCPConnection.this);
                        destroy();
                        // set a random length, then close
                        delayedClose(buf, _frameCount);
                        return;
                    }
                }
                int remaining = buf.remaining();
                if (remaining <= 0)
                    return;
                if (canZeroCopyFrame(_received, remaining, _framelen)) {
                    // shortcut, zero copy, decrypt directly to the ByteBuffer,
                    // overwriting the encrypted data
                    byte[] data = buf.array();
                    int pos = buf.position();
                    boolean ok = decryptAndProcess(data, pos);
                    buf.position(pos + _framelen);
                    if (!ok) {
                        // decryptAndProcess called destroy() and set _terminated
                        delayedClose(buf, _frameCount);
                        return;
                    }
                    continue;
                }

                // allocate ByteArray,
                // unless we have one already and it's big enough
                if (needsFrameBuffer(_dataBuf, _received, _framelen)) {
                    _dataBuf = allocReadBuf(_framelen);
                }

                // We now have a ByteArray in _dataBuf,
                // copy from ByteBuffer to ByteArray
                int toGet = Math.min(buf.remaining(), _framelen - _received);
                byte[] data = _dataBuf.getData();
                buf.get(data, _received, toGet);
                _received += toGet;
                if (_received < _framelen)
                    return;
                // decrypt to the ByteArray, overwriting the encrypted data
                boolean ok = decryptAndProcess(data, 0);
                // release buf only if we're not going around again
                if (!ok || buf.remaining() < 2) {
                    if (!ok)
                        delayedClose(buf, _frameCount);
                    // delayedClose() may have zeroed out _databuf
                    if (_dataBuf != null) {
                        if (_dataBuf.getData().length == BUFFER_SIZE)
                            releaseReadBuf(_dataBuf);
                        _dataBuf = null;
                    }
                    if (!ok)
                        return;
                }
                // go around again
            }
        }

        /**
         * Allocate the scratch buffer for the announced frame on the buffered
         * (split-frame) read path.
         * <p>A pooled 16 KB buffer is used for regular frames; oversized frames get
         * a one-off allocation since the pool only holds the fixed 16 KB class and
         * a pooled buffer would be too small. A previous pooled buffer that is too
         * small is returned to the pool first so the bounded pool is not drained by
         * repeated large frames.
         *
         * @param framelen the announced frame length
         * @return a fresh buffer for the frame; never null
         * @since 0.9.71+
         */
        private ByteArray allocReadBuf(int framelen) {
            if (_dataBuf != null && _dataBuf.getData().length == BUFFER_SIZE)
                releaseReadBuf(_dataBuf);
            if (framelen > BUFFER_SIZE) {
                if (_log.shouldInfo())
                    _log.info("Allocating big ByteArray: " + framelen + " bytes");
                return new ByteArray(new byte[framelen]);
            }
            return acquireReadBuf();
        }

        /**
         * Decrypts in place.
         * Length is _framelen
         * Side effects: Sets _received = -2, increments _frameCount and _blockCount if valid,
         * calls {@link #destroy()} and sets _terminated if termination block received or if invalid.
         * <p>
         * Does not call close() on failure. Caller MUST call
         * {@link #delayedClose(ByteBuffer, int)} if this returns false.
         *
         * @param data the buffer holding the packet
         * @param off the offset of the packet in the buffer
         * @return success, false for fatal error (AEAD) only
         */
        private boolean decryptAndProcess(byte[] data, int off) {
            if (isBanned()) {return false;}
            if (_log.shouldDebug())
                _log.debug("Decrypting frame " + _frameCount + " with " + _framelen + " bytes");
            try {
                _rcvr.decryptWithAd(null, data, off, data, off, _framelen);
            } catch (GeneralSecurityException gse) {
                // Probing resistance is handled by the caller: it calls
                // delayedClose(), which reads a random number of bytes with
                // a brief timeout before closing (NTCP2FailState).
                if (_log.shouldDebug())
                    _log.warn("Bad AEAD data phase frame " + _frameCount +
                              " with " + _framelen + " bytes on " + NTCPConnection.this, gse);
                else if (_log.shouldWarn())
                    _log.warn("Bad AEAD data phase frame " + _frameCount +
                              " with " + _framelen + " bytes on " + NTCPConnection.this);
                destroy();
                return false;
            }
            // no payload processing errors in the data phase are fatal
            processFrameBlocks(data, off);
            _received = -2;
            _frameCount++;
            return true;
        }

        /**
         * Process the decrypted payload of the current frame, delivering each
         * block to the PayloadCallback.  Payload errors are logged but never
         * escalate to a connection close; only AEAD failures (handled by the
         * caller before this point) are fatal.
         *
         * @param data the buffer holding the decrypted frame
         * @param off the offset of the frame in the buffer
         */
        private void processFrameBlocks(byte[] data, int off) {
            try {
                int blocks = NTCP2Payload.processPayload(_context, this, data, off,
                                                         framePayloadLength(_framelen), false);
                if (_log.shouldDebug())
                    _log.debug("Processed " + blocks + " blocks in frame");
            } catch (IOException ioe) {
                if (_log.shouldWarn())
                    _log.warn("Payload delivery failure -> " + ioe.getMessage());
            } catch (DataFormatException dfe) {
                if (_log.shouldWarn())
                    _log.warn("Payload delivery failure -> " + dfe.getMessage());
            } catch (I2NPMessageException ime) {
                if (_log.shouldWarn())
                    _log.warn("Error parsing I2NP message -> " + ime.getMessage());
                _context.statManager().addRateData("ntcp.corruptI2NPIME", 1);
            }
        }

        @Override
        public void destroy() {
            if (_log.shouldInfo())
                _log.info("NTCP2 read state destroy() on " + NTCPConnection.this);
            if (_dataBuf != null && _dataBuf.getData().length == BUFFER_SIZE)
                releaseReadBuf(_dataBuf);
            _dataBuf = null;
            _rcvr.destroy();
            _terminated = true;
        }

        @Override
        public int getFramesReceived() { return _frameCount; }

        //// PayloadCallbacks

        @Override
        public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException {
            if (_log.shouldDebug())
                _log.debug("Received updated RouterInfo");
            _messagesRead.incrementAndGet();
            Hash h = ri.getHash();
            Hash ph = _remotePeer.calculateHash();
            if (!isSamePeer(h, ph)) {
                if (isOwnRouterInfo(h, _context.routerHash()))
                    return;
                // make a fake DBSM message and send it to the InNetMessagePool
                DatabaseStoreMessage dbsm = new DatabaseStoreMessage(_context);
                dbsm.setEntry(ri);
                dbsm.setMessageExpiration(_context.clock().now() + 10*1000L);
                _transport.messageReceived(dbsm, null, ph, 0, 1000); // fake size
                return;
            }
            try {
                // defensive duplicate of the guard in the foreign-RI branch above;
                // unreachable under normal operation because a peer connection's
                // remote hash is never our own, kept to preserve the exact
                // store-path behavior
                if (isOwnRouterInfo(h, _context.routerHash()))
                    return;
                RouterInfo old = _context.netDb().store(h, ri);
                if (flood && !ri.equals(old)) {
                    FloodfillNetworkDatabaseFacade fndf = (FloodfillNetworkDatabaseFacade) _context.netDb();
                    if (isNewerOrNew(old, ri) && fndf.floodConditional(ri)) {
                        if (_log.shouldDebug())
                            _log.debug("Flooded the RouterInfo: " + h);
                    } else {
                        if (_log.shouldInfo())
                            _log.info("Flood request but we didn't: " + h);
                    }
                }
            } catch (IllegalArgumentException iae) {
                if (_log.shouldInfo())
                    _log.info("RouterInfo store fail [" + ri.getHash().toBase64().substring(0,6) + "] -> " + EstablishBase.storeFailCause(iae));
            }
        }

        @Override
        public void gotDateTime(long time) {
            if (_log.shouldDebug())
                _log.debug("Received updated datetime block");
            receiveTimestamp((time + 500) / 1000);
            // update skew
        }

        @Override
        public void gotI2NP(I2NPMessage msg) {
            if (_log.shouldDebug())
                _log.debug("Received I2NP message: " + msg);
            long timeToRecv = 0; // _context.clock().now() - _stateBegin;
            int size = msg.getMessageSize();
            _transport.messageReceived(msg, _remotePeer, null, timeToRecv, size);
            _lastReceiveTime = _context.clock().now();
            _messagesRead.incrementAndGet();
        }

        @Override
        public void gotOptions(byte[] options, boolean isHandshake) {
            NTCP2Options hisPadding = NTCP2Options.fromByteArray(options);
            if (hisPadding == null) {
                if (_log.shouldWarn())
                    _log.warn("Received options length " + options.length + " on: " + this);
                return;
            }
            _paddingConfig = OUR_PADDING.merge(hisPadding);
            if (_log.shouldDebug())
                _log.debug("Received padding options:" +
                          "\n* His padding options: " + hisPadding +
                          "\n* Our padding options: " + OUR_PADDING +
                          "\n* Merged config: " + _paddingConfig);
        }

        @Override
        public void gotTermination(int reason, long lastReceived) {
            if (_log.shouldInfo())
                _log.info("Received Termination: " + reason +
                          "\n* Total received: " + lastReceived + " on " + NTCPConnection.this);
            // close() calls destroy() sets _terminated
            close();
            if (reason == REASON_BANNED && _remotePeer != null) {
                byte[] ip = getRemoteIP();
                int port = getRemotePort();
                String ipPort = (ip != null && ip.length == 4) ?
                    (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff) + ":" + port :
                    "UNKNOWN";
                _banLogger.logBan(_remotePeer.calculateHash(), ipPort, "They banned us", 2*60*60*1000L);
                _context.banlist().banlistRouter(_remotePeer.calculateHash(), "They banned us", null, null, _context.clock().now() + 2*60*60*1000L);
            }
        }

        @Override
        public void gotUnknown(int type, int len) {
            if (_log.shouldWarn())
                _log.warn("Received unknown block type " + type + " length " + len + " on " + NTCPConnection.this);
        }

        @Override
        public void gotPadding(int paddingLength, int frameLength) {
            if (_log.shouldDebug())
                _log.debug("Received " + paddingLength +
                          " bytes padding in " + frameLength +
                          " byte frame: Ratio: " + (((float) paddingLength) / ((float) frameLength)) +
                          "; Configured min: " + _paddingConfig.getRecvMin() +
                          "; Configured max: " + _paddingConfig.getRecvMax());
        }
    }

    /**
     * After an AEAD failure, read a random number of bytes,
     * with a brief timeout, and then fail.
     * This replaces _curReadState, so no more messages will be received.
     * <p>
     * Call this only on data phase AEAD failure.
     * For other failures, use {@link #sendTermination(int, int)}.
     *
     * @param buf possibly with data remaining
     * @param validFramesRcvd to be sent in termination message
     * @since 0.9.36
     */
    private void delayedClose(ByteBuffer buf, int validFramesRcvd) {
        int toRead = 18 + _context.random().nextInt(NTCP2_FAIL_READ);
        int remaining = toRead - buf.remaining();
        if (remaining > 0) {
            if (_log.shouldWarn())
                _log.warn("Delayed close, AEAD failure after " + validFramesRcvd +
                          " good frames, to read: " + toRead + " on " + this); //, new Exception("I did it"));
            _curReadState = new NTCP2FailState(toRead, validFramesRcvd);
            _curReadState.receive(buf);
        } else {
            if (_log.shouldWarn())
                _log.warn("Immediate close, AEAD failure after " + validFramesRcvd +
                          " good frames and reading " + toRead + " on " + this); //, new Exception("I did it"));
            sendTermination(REASON_AEAD, validFramesRcvd);
        }
    }

    /**
     * After an AEAD failure, read a random number of bytes,
     * with a brief timeout, and then fail.
     * <p>
     * Call all methods from synchronized parent method.
     *
     * @since 0.9.36
     */
    private class NTCP2FailState extends SimpleTimer2.TimedEvent implements ReadState {
        /** Bytes still to read in this handshake phase. */
        private final int _toRead;
        /** Valid frames received before this phase. */
        private final int _validFramesRcvd;
        /** Bytes read so far in this phase. */
        private int _read;

        /**
         * Read state for the NTCP2 failure message.
         *
         * @param toRead how much left to read
         * @param validFramesRcvd to be sent in termination message
         */
        public NTCP2FailState(int toRead, int validFramesRcvd) {
            super(_context.simpleTimer2());
            _toRead = toRead;
            _validFramesRcvd = validFramesRcvd;
            schedule(NTCP2_FAIL_TIMEOUT);
        }

        @Override
        public void receive(ByteBuffer buf) {
            _read += buf.remaining();
            if (_read >= _toRead) {
                cancel();
                // only do this once
                _read = Integer.MIN_VALUE;
                if (_log.shouldWarn())
                    _log.warn("Close after AEAD failure and reading " + _toRead + " on " + NTCPConnection.this);
                sendTermination(REASON_AEAD, _validFramesRcvd);
            }
        }

        @Override
        public void destroy() {
            cancel();
        }

        @Override
        public void timeReached() {
            // only do this once
            _read = Integer.MIN_VALUE;
            if (_log.shouldWarn())
                _log.warn("Timeout after AEAD failure waiting for more data on " + NTCPConnection.this);
            sendTermination(REASON_AEAD, _validFramesRcvd);
        }

        @Override
        public int getFramesReceived() { return 0; }
    }

    /**
     * Close after a brief timeout.
     * Construct after sending termination.
     *
     * @since 0.9.36
     */
    private class DelayedCloser extends SimpleTimer2.TimedEvent {

        /** Schedules itself. */
        public DelayedCloser() {
            super(_context.simpleTimer2());
            schedule(NTCP2_TERMINATION_CLOSE_DELAY);
        }

        @Override
        public void timeReached() {
            close();
        }
    }


    /**
     * Little endian.
     * Same as {@link net.i2p.data.DataHelper#fromLongLE(byte[], int, int)} called with
     * 8 bytes, but allows a negative result.
     *
     * @param src the array to read 8 bytes from
     * @param offset the index of the first byte to read
     * @return the 8 bytes read little-endian, as a signed long
     * @throws ArrayIndexOutOfBoundsException if offset is not inside the array
     * @since 0.9.36
     */
    private static long fromLong8LE(byte[] src, int offset) {
        long rv = 0;
        for (int i = offset + 7; i >= offset; i--) {
            rv <<= 8;
            rv |= src[i] & 0xFF;
        }
        return rv;
    }

    /**
     * Little endian.
     * Same as {@link net.i2p.data.DataHelper#toLongLE(byte[], int, int, long)} called
     * with 8 bytes, but allows a negative value.
     *
     * @param target the array to write 8 bytes into
     * @param offset the index of the first byte to write
     * @param value the value to write, low byte first
     */
    private static void toLong8LE(byte[] target, int offset, long value) {
        int limit = offset + 8;
        for (int i = offset; i < limit; i++) {
            target[i] = (byte) value;
            value >>= 8;
        }
    }

    /**
     * Whether the pumper has registered write interest.
     *
     * @return true if write interest is pending
     */
    public boolean hasWriteInterestPending() {
        return _writeInterestPending.get();
    }

    /**
     * Atomically updates the write-interest-pending flag.
     *
     * @param expected the flag value currently stored, compared against for equality
     * @param update the flag value to store if the comparison succeeds
     * @return true if CAS succeeded
     */
    public boolean compareAndSetWriteInterestPending(boolean expected, boolean update) {
        return _writeInterestPending.compareAndSet(expected, update);
    }

    /** Update last active time to now. */
    public void updateLastActiveTime() {
        _lastActiveTime = System.currentTimeMillis();
    }

    /**
     * Time of the last activity.
     *
     * @return last active time
     */
    public long getLastActiveTime() {
        return _lastActiveTime;
    }

    /** The interest ops currently set on the selector key. */
    private volatile int _currentInterestOps = 0;

    /**
     * Check if the given interest op is not already set.
     *
     * @param ops the interest ops to check
     * @return true if the op is not already set
     */
    public boolean shouldSetInterest(int ops) {
        return (_currentInterestOps & ops) != ops;
    }

    /**
     * Update the cached interest ops.
     *
     * @param ops the new interest ops
     */
    public void updateInterestOps(int ops) {
        _currentInterestOps = ops;
    }

    /**
     * Compact one-line connection identifier for log output: direction,
     * remote address, connection id and peer hash. No stats, no newlines.
     *
     * @return the identifier, never null
     * @since 0.9.71+
     */
    private String toShortString() {
        String addr;
        if (_isInbound) {
            SocketChannel ch = _chan;
            if (ch != null) {
                InetAddress ia = ch.socket().getInetAddress();
                addr = (ia != null ? ia.getHostAddress() : "Unknown") + ':' + ch.socket().getPort();
            } else {
                addr = "null_channel";
            }
        } else {
            addr = _remAddr.getHost() + ':' + _remAddr.getPort();
        }
        return (_isInbound ? "inbound " : "outbound ") + addr + " [conn " + _connID + ' ' +
               (_remotePeer == null ? "Unknown" : _remotePeer.calculateHash().toBase64().substring(0,6)) + ']';
    }

    @Override
    public String toString() {
        String fromIP;
        int port = 0;
        if (_isInbound) {
            SocketChannel ch = _chan;
            if (ch != null) {
                InetAddress addr = ch.socket().getInetAddress();
                fromIP = addr != null ? addr.getHostAddress() : "Unknown";
                port = ch.socket().getPort();
            } else {
                fromIP = "null_channel";
            }
        } else {
            fromIP = null;
        }
        return (_isInbound ? ("Inbound: " + fromIP + ':' + port + ' ')
                           : ("Outbound: " + _remAddr.getHost() + ':' + _remAddr.getPort() + ' ')) +
               (_remotePeer != null ? "[" + _remotePeer.calculateHash().toBase64().substring(0,6) + "]" : "") +
               (_log.shouldDebug() ? "\n* Connection [ID " + _connID + "]" +
               "\n* Created: " + DataHelper.formatDuration(getTimeSinceCreated()) + " ago;" +
               " Last message sent: " + DataHelper.formatDuration(getTimeSinceSend()) + " ago;" +
               " Last message received: " + DataHelper.formatDuration(getTimeSinceReceive()) + " ago" +
               "\n* Messages sent: " + _messagesWritten + ";" +
               " Messages received: " + _messagesRead : "");
    }
}
