package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.Arrays;

import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.util.CDPQEntry;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.TryCache;

/**
 * Represents a UDP packet with caching support for efficient reuse.
 * Provides access to the underlying DatagramPacket and maintains metadata
 * such as priority, timestamps, message type, and bandwidth requests.
 */
class UDPPacket implements CDPQEntry {
    private RouterContext _context;
    private final DatagramPacket _packet;
    private final byte[] _data;
    private int _priority;
    private volatile long _initializeTime;
    private volatile int _markedType;
    private RemoteHostId _remoteHost;
    private volatile boolean _released;
    private long _enqueueTime;
    private long _receivedTime;
    private int _validateCount;
    private FIFOBandwidthLimiter.Request _bandwidthRequest;
    private long _seqNum;
    private int _messageType;
    private int _fragmentCount;

    // Packet factory and cache for object reuse
    private static class PacketFactory implements TryCache.ObjectFactory<UDPPacket> {
        static RouterContext context;

        @Override
        public UDPPacket newInstance() {
            return new UDPPacket(context);
        }
    }

    private static final boolean CACHE = true;
    private static final int MIN_CACHE_SIZE = 256;
    private static final int MAX_CACHE_SIZE = 2048;
    private static final TryCache.ObjectFactory<UDPPacket> _packetFactory;
    private static final TryCache<UDPPacket> _packetCache;

    static {
        if (CACHE) {
            long maxMemory = SystemVersion.getMaxMemory();
            int csize = (int) Math.max(MIN_CACHE_SIZE, Math.min(MAX_CACHE_SIZE, maxMemory / (1024 * 1024)));
            _packetFactory = new PacketFactory();
            _packetCache = new TryCache<>(_packetFactory, csize);
        } else {
            _packetFactory = null;
            _packetCache = null;
        }
    }

    /**
     * Maximum UDP packet size accepted. Packets of this size are assumed truncated.
     * Size allows for some legacy packet bugs and includes overhead.
     */
    static final int MAX_PACKET_SIZE = 1572;
    public static final int IV_SIZE = 16;
    public static final int MAC_SIZE = 16;

    // Payload type constants
    public static final int PAYLOAD_TYPE_SESSION_REQUEST = 0;
    public static final int PAYLOAD_TYPE_SESSION_CREATED = 1;
    public static final int PAYLOAD_TYPE_SESSION_CONFIRMED = 2;
    public static final int PAYLOAD_TYPE_RELAY_REQUEST = 3;
    public static final int PAYLOAD_TYPE_RELAY_RESPONSE = 4;
    public static final int PAYLOAD_TYPE_RELAY_INTRO = 5;
    public static final int PAYLOAD_TYPE_DATA = 6;
    public static final int PAYLOAD_TYPE_TEST = 7;
    public static final int PAYLOAD_TYPE_SESSION_DESTROY = 8;
    public static final int MAX_PAYLOAD_TYPE = PAYLOAD_TYPE_SESSION_DESTROY;

    /** @since 0.9.68+ */
    public static String payloadTypeToString(int type) {
        switch (type) {
            case PAYLOAD_TYPE_SESSION_REQUEST:    return "Session Request";
            case PAYLOAD_TYPE_SESSION_CREATED:    return "Session Created";
            case PAYLOAD_TYPE_SESSION_CONFIRMED:  return "Session Confirmed";
            case PAYLOAD_TYPE_RELAY_REQUEST:      return "Relay Request";
            case PAYLOAD_TYPE_RELAY_RESPONSE:     return "Relay Response";
            case PAYLOAD_TYPE_RELAY_INTRO:        return "Relay Intro";
            case PAYLOAD_TYPE_DATA:               return "Data";
            case PAYLOAD_TYPE_TEST:               return "Test";
            case PAYLOAD_TYPE_SESSION_DESTROY:    return "Session Destroy";
            default:                              return "Unknown Payload Type: " + type;
        }
    }

    // Private constructor used by cache factory
    private UDPPacket(RouterContext ctx) {
        _data = new byte[MAX_PACKET_SIZE];
        _packet = new DatagramPacket(_data, MAX_PACKET_SIZE);
        init(ctx);
    }

    /**
     * Initializes or resets the packet state.
     * @param ctx RouterContext for timing and logging
     */
    private void init(RouterContext ctx) {
        _context = ctx;
        int len = _packet.getLength() > 0 ? _packet.getLength() : MAX_PACKET_SIZE;
        Arrays.fill(_data, 0, len, (byte) 0);
        _packet.setData(_data);
        _initializeTime = _context.clock().now();
        _markedType = -1;
        _validateCount = 0;
        _remoteHost = null;
        _released = false;
        _messageType = -1;
        _enqueueTime = 0;
        _receivedTime = 0;
        _fragmentCount = 0;
        _bandwidthRequest = null;
    }

    @Override
    public void setSeqNum(long num) {
        _seqNum = num;
    }

    @Override
    public long getSeqNum() {
        return _seqNum;
    }

    /**
     * Gets the wrapped DatagramPacket.
     * @return the DatagramPacket instance
     * @throws IllegalStateException if the packet is already released
     */
    public DatagramPacket getPacket() {
        verifyNotReleased();
        return _packet;
    }

    public int getPriority() {
        return _priority;
    }

    public void setPriority(int pri) {
        _priority = pri;
    }

    /**
     * Returns the packet initialization timestamp.
     * @return initialization time in milliseconds
     */
    public long getBegin() {
        verifyNotReleased();
        return _initializeTime;
    }

    /**
     * Returns the elapsed time since initialization.
     * @return lifetime in milliseconds
     */
    public long getLifetime() {
        return _context.clock().now() - _initializeTime;
    }

    /**
     * Resets initialization time to current time.
     */
    public void resetBegin() {
        _initializeTime = _context.clock().now();
    }

    /**
     * Marks the packet with a specific type for identification.
     * @param type Mark type integer
     */
    public void markType(int type) {
        verifyNotReleased();
        _markedType = type;
    }

    /**
     * Gets the marked type of this packet.
     * @return mark type integer
     */
    public int getMarkedType() {
        verifyNotReleased();
        return _markedType;
    }

    int getMessageType() {
        return _messageType;
    }

    void setMessageType(int type) {
        _messageType = type;
    }

    int getFragmentCount() {
        return _fragmentCount;
    }

    void setFragmentCount(int count) {
        _fragmentCount = count;
    }

    /**
     * Lazily obtains the RemoteHostId representing remote address and port.
     * @return RemoteHostId for this packet's sender
     */
    synchronized RemoteHostId getRemoteHost() {
        if (_remoteHost == null) {
            InetAddress addr = _packet.getAddress();
            if (addr != null) {
                _remoteHost = new RemoteHostId(addr.getAddress(), _packet.getPort());
            }
        }
        return _remoteHost;
    }

    public void setEnqueueTime(long now) {
        _enqueueTime = now;
    }

    /**
     * Marks this packet as received, setting received timestamp.
     */
    synchronized void received() {
        _receivedTime = _context.clock().now();
    }

    /**
     * Returns the enqueue timestamp.
     * @return enqueue time in milliseconds
     */
    public long getEnqueueTime() {
        return _enqueueTime;
    }

    /**
     * Returns how long since this packet was received.
     * @return time since received in milliseconds, or 0 if never received
     */
    synchronized long getTimeSinceReceived() {
        return (_receivedTime > 0) ? _context.clock().now() - _receivedTime : 0;
    }

    /**
     * Requests outbound bandwidth for sending this packet.
     */
    public synchronized void requestOutboundBandwidth() {
        verifyNotReleased();
        _bandwidthRequest = _context.bandwidthLimiter().requestOutbound(_packet.getLength(), 0, "UDP sender");
    }

    /**
     * Returns the current bandwidth request.
     * @return the bandwidth request, or null if none exists
     */
    public synchronized FIFOBandwidthLimiter.Request getBandwidthRequest() {
        verifyNotReleased();
        return _bandwidthRequest;
    }

    /**
     * Releases the packet back to cache and aborts outstanding bandwidth requests.
     * Safe to call multiple times.
     */
    public synchronized void release() {
        if (_released) return;
        _released = true;

        if (_bandwidthRequest != null) {
            synchronized (_bandwidthRequest) {
                if (_bandwidthRequest.getPendingRequested() > 0)
                    _bandwidthRequest.abort();
            }
            _bandwidthRequest = null;
        }
        if (CACHE)
            _packetCache.release(this);
    }

    /**
     * Clears the packet cache if caching is enabled.
     */
    public static void clearCache() {
        if (CACHE)
            _packetCache.clear();
    }

    /**
     * Checks if the packet has already been released and logs an error if so.
     */
    private void verifyNotReleased() {
        if (!CACHE) return;
        if (_released) {
            Log log = _context.logManager().getLog(UDPPacket.class);
            log.error("Access attempted on already released UDPPacket", new IllegalStateException());
        }
    }

    @Override
    public String toString() {
        if (_released)
            return "RELEASED PACKET";

        StringBuilder buf = new StringBuilder(256);
        InetAddress addr = _packet.getAddress();

        if (addr != null && addr.getAddress() != null) {
            buf.append(Addresses.toString(addr.getAddress(), _packet.getPort()))
               .append("\n* Size: ").append(_packet.getLength()).append(" bytes")
               .append("; Priority: ").append(_priority);

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
        } else {
            buf.append("\n* No address for packet - Router restarting?");
        }
        return buf.toString();
    }

    /**
     * Acquires a UDPPacket instance either from cache or new.
     * @param ctx RouterContext used for initialization
     * @param inbound true if packet is inbound, false if outbound (currently unused)
     * @return initialized UDPPacket instance
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
     * Convenience method to release the packet.
     */
    public void drop() {
        release();
    }

}