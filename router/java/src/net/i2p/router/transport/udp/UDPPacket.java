package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.Arrays;

import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.util.CDPQEntry;
import net.i2p.util.TryCache;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Basic delivery unit containing the datagram.  This also maintains a cache
 * of object instances to allow rapid reuse.
 *
 */
class UDPPacket implements CDPQEntry {
    private RouterContext _context;
    private final DatagramPacket _packet;
    private int _priority;
    private volatile long _initializeTime;
    //private volatile long _expiration;
    private final byte[] _data;
    private final byte[] _validateBuf;
    private final byte[] _ivBuf;
    private volatile int _markedType;
    private RemoteHostId _remoteHost;
    private boolean _released;
    //private volatile Exception _releasedBy;
    //private volatile Exception _acquiredBy;
    private long _enqueueTime;
    private long _receivedTime;
    //private long _beforeValidate;
    //private long _afterValidate;
    //private long _beforeReceiveFragments;
    //private long _afterHandlingTime;
    private int _validateCount;
    // private boolean _isInbound;
    private FIFOBandwidthLimiter.Request _bandwidthRequest;
    private long _seqNum;

    private static class PacketFactory implements TryCache.ObjectFactory<UDPPacket> {
        static RouterContext context;
        public UDPPacket newInstance() {
            return new UDPPacket(context);
        }
    }

    //  Warning - this mixes contexts in a multi-router JVM
    private static final TryCache<UDPPacket> _packetCache;
    private static final TryCache.ObjectFactory<UDPPacket> _packetFactory;
    private static final boolean CACHE = true;
    private static final int MIN_CACHE_SIZE = 64;
//    private static final int MAX_CACHE_SIZE = 256;
    private static final int MAX_CACHE_SIZE = 512;
    static {
        if (CACHE) {
            long maxMemory = SystemVersion.getMaxMemory();
            boolean isSlow = SystemVersion.isSlow();
            int cores = SystemVersion.getCores();
            int csize = (int) Math.max(MIN_CACHE_SIZE, Math.min(MAX_CACHE_SIZE, maxMemory / (1024*1024)));
            if (maxMemory >= 1024*1024*1024 && cores >= 4 && !isSlow)
                csize = 1024;
            else if (maxMemory >= 768*1024*1024 && cores >= 4 && !isSlow)
                csize = 512;
            _packetFactory = new PacketFactory();
            _packetCache = new TryCache<>(_packetFactory, csize);
        } else {
            _packetCache = null;
            _packetFactory = null;
        }
    }

    /**
     *  Actually it is one less than this, we assume
     *  if a received packet is this big it is truncated.
     *  This is bigger than PeerState.LARGE_MTU, as the far-end's
     *  LARGE_MTU may be larger than ours.
     *
     *  Due to longstanding bugs, a packet may be larger than LARGE_MTU
     *  (acks and padding). Together with an increase in the LARGE_MTU to
     *  1492 in release 0.8.9, routers from 0.8.9 - 0.8.11 can generate
     *  packets up to 1536. Data packets are always a multiple of 16,
     *  so make this 4 + a multiple of 16.
     */
    static final int MAX_PACKET_SIZE = 1572;
    public static final int IV_SIZE = 16;
    public static final int MAC_SIZE = 16;

    /** Message types, 4 bits max */
    public static final int PAYLOAD_TYPE_SESSION_REQUEST = 0;
    public static final int PAYLOAD_TYPE_SESSION_CREATED = 1;
    public static final int PAYLOAD_TYPE_SESSION_CONFIRMED = 2;
    public static final int PAYLOAD_TYPE_RELAY_REQUEST = 3;
    public static final int PAYLOAD_TYPE_RELAY_RESPONSE = 4;
    public static final int PAYLOAD_TYPE_RELAY_INTRO = 5;
    public static final int PAYLOAD_TYPE_DATA = 6;
    public static final int PAYLOAD_TYPE_TEST = 7;
    /** @since 0.8.1 */
    public static final int PAYLOAD_TYPE_SESSION_DESTROY = 8;
    public static final int MAX_PAYLOAD_TYPE = PAYLOAD_TYPE_SESSION_DESTROY;

    // various flag fields for use in the header
    /**
     *  Defined in the spec from the beginning, Unused
     *  @since 0.9.24
     */
    public static final byte HEADER_FLAG_REKEY = (1 << 3);
    /**
     *  Defined in the spec from the beginning, Used starting in 0.9.24
     *  @since 0.9.24
     */
    public static final byte HEADER_FLAG_EXTENDED_OPTIONS = (1 << 2);

    // Extended options for session request
    public static final int SESS_REQ_MIN_EXT_OPTIONS_LENGTH = 2;
    // bytes 0-1 are flags
    /**
     * set to 1 to request a session tag, i.e. we want him to be an introducer for us
     */
    public static final int SESS_REQ_EXT_FLAG_REQUEST_RELAY_TAG = 0x01;

    // various flag fields for use in the data packets
    public static final byte DATA_FLAG_EXPLICIT_ACK = (byte)(1 << 7);
    public static final byte DATA_FLAG_ACK_BITFIELDS = (1 << 6);
    /** unused */
    public static final byte DATA_FLAG_ECN = (1 << 4);
    public static final byte DATA_FLAG_WANT_ACKS = (1 << 3);
    public static final byte DATA_FLAG_WANT_REPLY = (1 << 2);
    /** unused */
    public static final byte DATA_FLAG_EXTENDED = (1 << 1);

    public static final byte BITFIELD_CONTINUATION = (byte)(1 << 7);

    private static final int MAX_VALIDATE_SIZE = MAX_PACKET_SIZE;

    private UDPPacket(RouterContext ctx) {
        //ctx.statManager().createRateStat("udp.fetchRemoteSlow", "Time to grab the remote ip info", "Transport [UDP]", UDPTransport.RATES);
        // the data buffer is clobbered on init(..), but we need it to bootstrap
        _data = new byte[MAX_PACKET_SIZE];
        _packet = new DatagramPacket(_data, MAX_PACKET_SIZE);
        _validateBuf = new byte[MAX_VALIDATE_SIZE];
        _ivBuf = new byte[IV_SIZE];
        init(ctx);
    }

    private synchronized void init(RouterContext ctx) {
        _context = ctx;
        //_dataBuf = _dataCache.acquire();
        Arrays.fill(_data, (byte)0);
        //_packet = new DatagramPacket(_data, MAX_PACKET_SIZE);
        //
        // WARNING -
        // Doesn't seem like we should have to do this every time,
        // from reading the DatagramPacket javadocs,
        // but we get massive corruption without it.
        _packet.setData(_data);
        // _isInbound = inbound;
        _initializeTime = _context.clock().now();
        _markedType = -1;
        _validateCount = 0;
        _remoteHost = null;
        _released = false;
        // clear out some values to make debugging easier via toString()
        _messageType = -1;
        _enqueueTime = 0;
        _receivedTime = 0;
        _fragmentCount = 0;
    }

    /**
     *  CDPQEntry
     *  @since 0.9.53
     */
    public void setSeqNum(long num) { _seqNum = num; }

    /**
     *  CDPQEntry
     *  @since 0.9.53
     */
    public long getSeqNum() { return _seqNum; }



  /****
    public void writeData(byte src[], int offset, int len) {
        verifyNotReleased();
        System.arraycopy(src, offset, _data, 0, len);
        _packet.setLength(len);
        resetBegin();
    }
  ****/

    /** */
    public synchronized DatagramPacket getPacket() { verifyNotReleased(); return _packet; }
    public int getPriority() { return _priority; }

    /**
     *  @since 0.9.53
     */
    public void setPriority(int pri) { _priority = pri; }

    //public long getExpiration() { verifyNotReleased(); return _expiration; }
    public synchronized long getBegin() { verifyNotReleased(); return _initializeTime; }
    public long getLifetime() { /** verifyNotReleased(); */ return _context.clock().now() - _initializeTime; }
    public synchronized void resetBegin() { _initializeTime = _context.clock().now(); }
    /** flag this packet as a particular type for accounting purposes */
    public synchronized void markType(int type) { verifyNotReleased(); _markedType = type; }
    /**
     * flag this packet as a particular type for accounting purposes, with
     * 1 implying the packet is an ACK, otherwise it is a data packet
     *
     */
    public synchronized int getMarkedType() { verifyNotReleased(); return _markedType; }

    private int _messageType;
    private int _fragmentCount;
    /** only for debugging and stats, does not go on the wire */
    int getMessageType() { return _messageType; }
    /** only for debugging and stats, does not go on the wire */
    void setMessageType(int type) { _messageType = type; }

    /** only for debugging and stats */
    int getFragmentCount() { return _fragmentCount; }

    /** only for debugging and stats */
    void setFragmentCount(int count) { _fragmentCount = count; }

    synchronized RemoteHostId getRemoteHost() {
        if (_remoteHost == null) {
            //long before = System.currentTimeMillis();
            InetAddress addr = _packet.getAddress();
            byte ip[] = addr.getAddress();
            int port = _packet.getPort();
            _remoteHost = new RemoteHostId(ip, port);
            //long timeToFetch = System.currentTimeMillis() - before;
            //if (timeToFetch > 50)
            //    _context.statManager().addRateData("udp.fetchRemoteSlow", timeToFetch, getLifetime());
        }
        return _remoteHost;
    }

    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public void setEnqueueTime(long now) { _enqueueTime = now; }

    /** a packet handler has pulled it off the inbound queue */
    synchronized void received() { _receivedTime = _context.clock().now(); }

    /** a packet handler has decrypted and verified the packet and is about to parse out the good bits */
    //void beforeReceiveFragments() { _beforeReceiveFragments = _context.clock().now(); }
    /** a packet handler has finished parsing out the good bits */
    //void afterHandling() { _afterHandlingTime = _context.clock().now(); }

    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public long getEnqueueTime() { return _enqueueTime; }

    /** a packet handler has pulled it off the inbound queue */
    synchronized long getTimeSinceReceived() { return (_receivedTime > 0 ? _context.clock().now() - _receivedTime : 0); }

    /** a packet handler has decrypted and verified the packet and is about to parse out the good bits */
    //long getTimeSinceReceiveFragments() { return (_beforeReceiveFragments > 0 ? _context.clock().now() - _beforeReceiveFragments : 0); }
    /** a packet handler has finished parsing out the good bits */
    //long getTimeSinceHandling() { return (_afterHandlingTime > 0 ? _context.clock().now() - _afterHandlingTime : 0); }

    /**
     *  So that we can compete with NTCP, we want to request bandwidth
     *  in parallel, on the way into the queue, not on the way out.
     *  Call before enqueueing.
     *  @since 0.9.21
     *  @deprecated unused
     */
    @Deprecated
    public synchronized void requestInboundBandwidth() {
        verifyNotReleased();
        _bandwidthRequest = _context.bandwidthLimiter().requestInbound(_packet.getLength(), "UDP receiver");
    }

    /**
     *  So that we can compete with NTCP, we want to request bandwidth
     *  in parallel, on the way into the queue, not on the way out.
     *  Call before enqueueing.
     *  @since 0.9.21
     */
    public synchronized void requestOutboundBandwidth() {
        verifyNotReleased();
        _bandwidthRequest = _context.bandwidthLimiter().requestOutbound(_packet.getLength(), 0, "UDP sender");
    }

    /**
     *  So that we can compete with NTCP, we want to request bandwidth
     *  in parallel, on the way into the queue, not on the way out.
     *  Call after dequeueing.
     *  @since 0.9.21
     */
    public synchronized FIFOBandwidthLimiter.Request getBandwidthRequest() {
        verifyNotReleased();
        return _bandwidthRequest;
    }

    // Following 5: All used only for stats in PacketHandler, commented out

    /** when it was pulled off the endpoint receive queue */
    //long getReceivedTime() { return _receivedTime; }
    /** when we began validate() */
    //long getBeforeValidate() { return _beforeValidate; }
    /** when we finished validate() */
    //long getAfterValidate() { return _afterValidate; }
    /** how many times we tried to validate the packet */
    //int getValidateCount() { return _validateCount; }

    @Override
    public String toString() {
        synchronized(this) {
            if (_released)
                return "RELEASED PACKET";
        }
        StringBuilder buf = new StringBuilder(256);
        if (_packet.getAddress().getAddress() != null) {
            buf.append(Addresses.toString(_packet.getAddress().getAddress(), _packet.getPort()));
            buf.append("\n* Size: ");
            buf.append(_packet.getLength());
            buf.append(" bytes");
            //buf.append(" id=").append(System.identityHashCode(this));
            buf.append("; Priority: ").append(_priority);
            if (_messageType >= 0)
                buf.append("; Message Type: ").append(_messageType);
            if (_markedType >= 0)
                buf.append("; Mark Type: ").append(_markedType);
            if (_fragmentCount > 0)
                buf.append("; Fragment Count: ").append(_fragmentCount);
            if (_enqueueTime > 0)
                buf.append("; sinceEnqueued: ").append(_context.clock().now() - _enqueueTime);
            if (_receivedTime > 0)
                buf.append("; sinceReceived: ").append(_context.clock().now() - _receivedTime);
            //buf.append(" beforeReceiveFragments=").append((_beforeReceiveFragments > 0 ? _context.clock().now()-_beforeReceiveFragments : -1));
            //buf.append(" sinceHandled=").append((_afterHandlingTime > 0 ? _context.clock().now()-_afterHandlingTime : -1));
            //buf.append("\ndata=").append(Base64.encode(_packet.getData(), _packet.getOffset(), _packet.getLength()));
        } else {
            buf.append("\n* No address for packet - Router restarting?");
        }
        return buf.toString();
    }

    /**
     *  @param inbound unused
     */
    public static UDPPacket acquire(RouterContext ctx, boolean inbound) {
        UDPPacket rv;
        if (CACHE) {
            PacketFactory.context = ctx;
            rv = _packetCache.acquire();
            rv.init(ctx);
        } else {
            rv = new UDPPacket(ctx);
        }
        return rv;
    }

    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public void drop() {
        release();
    }

    public synchronized void release() {
        verifyNotReleased();
        _released = true;
        //_releasedBy = new Exception("released by");
        //_acquiredBy = null;
        //
        //_dataCache.release(_dataBuf);
        if (_bandwidthRequest != null) {
            synchronized(_bandwidthRequest) {
                if (_bandwidthRequest.getPendingRequested() > 0)
                    _bandwidthRequest.abort();
            }
            _bandwidthRequest = null;
        }
        if (!CACHE)
            return;
        _packetCache.release(this);
    }

    /**
     *  Call at shutdown/startup to not hold ctx refs
     *  @since 0.9.2
     */
    public static void clearCache() {
        if (CACHE) {
            PacketFactory.context = null;
            _packetCache.clear();
        }
    }

    private synchronized void verifyNotReleased() {
        if (!CACHE) return;
        if (_released) {
            Log log = _context.logManager().getLog(UDPPacket.class);
            log.error("Already released", new Exception());
            //log.log(Log.CRIT, "Released by: ", _releasedBy);
            //log.log(Log.CRIT, "Acquired by: ", _acquiredBy);
        }
    }
}
