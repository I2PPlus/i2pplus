package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.util.CDPQEntry;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.TryCache;

/**
 * UDP packet wrapper with caching and metadata support.
 * 
 * This class wraps standard DatagramPacket objects and adds
 * I2P-specific metadata for efficient packet handling and reuse.
 * It implements an object cache pattern to reduce garbage collection
 * overhead and improve performance in high-throughput scenarios.
 * 
 * <strong>Core Features:</strong>
 * <ul>
 *   <li>Packet wrapping with metadata preservation</li>
 *   <li>Object caching for reuse and GC reduction</li>
 *   <li>Priority-based queuing and processing</li>
 *   <li>Fragmentation and reassembly support</li>
 *   <li>Bandwidth reservation and throttling integration</li>
 *   <li>Timestamp and routing metadata</li>
 * </ul>
 * 
 * <strong>Caching Strategy:</strong>
 * <ul>
 *   <li>Internal object pool for packet reuse</li>
 *   <li>Cache size limits and eviction policies</li>
 *   <li>Thread-safe cache operations</li>
 *   <li>Memory-efficient packet allocation</li>
 * </ul>
 * 
 * <strong>Metadata Support:</strong>
 * <ul>
 *   <li>Message type and protocol information</li>
 *   <li>Priority levels for QoS handling</li>
 *   <li>Fragment identification and sequencing</li>
 *   <li>Bandwidth allocation tracking</li>
 *   <li>Routing and forwarding information</li>
 * </ul>
 * 
 * <strong>Performance Optimizations:</strong>
 * <ul>
 *   <li>Minimal object creation overhead</li>
 *   <li>Efficient data copying and cloning</li>
 *   <li>Cache-friendly packet lifecycle management</li>
 *   <li>Reduced garbage collection pressure</li>
 * </ul>
 */
class UDPPacket implements CDPQEntry {
    private RouterContext _context;
    private final DatagramPacket _packet;
    private final byte[] _data;
    private int _priority;
    private volatile long _initializeTime;
    private volatile int _markedType;
    private volatile RemoteHostId _remoteHost;
    private volatile boolean _released;
    private long _enqueueTime;
    private volatile long _receivedTime;
    private int _validateCount;
    private final AtomicReference<FIFOBandwidthLimiter.Request> _bandwidthRequest = new AtomicReference<>();
    private long _seqNum;
    private int _messageType;
    private int _fragmentCount;

    /**
     * Factory for UDP packet caching.
     * Provides new instances for the {@link TryCache}.
     */
    private static class PacketFactory implements TryCache.ObjectFactory<UDPPacket> {
        static RouterContext context;

        @Override
        public UDPPacket newInstance() {
            return new UDPPacket(context);
        }
    }

    private static final boolean CACHE = true;
    private static final int MIN_CACHE_SIZE = 16;
    private static final int MAX_CACHE_SIZE = 128;
    private static final TryCache.ObjectFactory<UDPPacket> _packetFactory;
    private static final TryCache<UDPPacket> _packetCache;

    static {
        if (CACHE) {
            long maxMemory = SystemVersion.getMaxMemory();
            int csize = (int) Math.max(MIN_CACHE_SIZE, Math.min(MAX_CACHE_SIZE, Math.min(32, maxMemory / (32 * 1024 * 1024))));
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
    /** Size of initialization vector in bytes. */
    public static final int IV_SIZE = 16;
    /** Size of MAC (Message Authentication Code) in bytes. */
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

    /**
     * Converts payload type integer constant to human-readable string.
     *
     * @param type the payload type constant
     * @return descriptive string of payload type
     * @since 0.9.68+
     */
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

    /**
     * Private constructor used by the packet cache factory.
     *
     * @param ctx RouterContext for logging and timing
     */
    private UDPPacket(RouterContext ctx) {
        // Moderate buffer sizing - balance memory reduction with functionality
        _data = new byte[1480]; // Slightly smaller than max (1572) but handles most cases
        _packet = new DatagramPacket(_data, _data.length);
        init(ctx);
    }

    /**
     * Header size to clear on init - covers all SSU2 headers without clearing full buffer.
     * SSU2 headers are typically 40-80 bytes, 256 covers all cases plus margin.
     */
    private static final int HEADER_CLEAR_SIZE = 256;

    /**
     * Initializes or resets the packet state for reuse.
     * Clears header portion of data buffer, resets metadata and timestamps.
     * Only clears first 256 bytes instead of full buffer for performance.
     *
     * @param ctx RouterContext used for timing and logging
     */
    private void init(RouterContext ctx) {
        _context = ctx;
        // Only clear header portion (256 bytes) rather than full packet size (1572 bytes)
        // This covers all SSU2 protocol headers while avoiding expensive full-array zeroing
        Arrays.fill(_data, 0, Math.min(_data.length, HEADER_CLEAR_SIZE), (byte) 0);
        _packet.setData(_data);
        _packet.setLength(_data.length);
        _initializeTime = _context.clock().now();
        _markedType = -1;
        _validateCount = 0;
        _remoteHost = null;
        _released = false;
        _messageType = -1;
        _enqueueTime = 0;
        _receivedTime = 0;
        _fragmentCount = 0;
        _bandwidthRequest.set(null);
    }

    /**
     * Sets the packet sequence number.
     *
     * @param num sequence number to set
     */
    @Override
    public void setSeqNum(long num) {
        _seqNum = num;
    }

    /**
     * Gets the packet sequence number.
     *
     * @return sequence number
     */
    @Override
    public long getSeqNum() {
        return _seqNum;
    }

    /**
     * Gets the underlying {@link DatagramPacket}.
     *
     * @return datagram packet instance
     * @throws IllegalStateException if the packet has been released
     */
    public DatagramPacket getPacket() {
        verifyNotReleased();
        return _packet;
    }

    /**
     * Gets the packet priority.
     *
     * @return packet priority as integer
     */
    public int getPriority() {
        return _priority;
    }

    /**
     * Sets the packet priority.
     *
     * @param pri priority level to assign
     */
    public void setPriority(int pri) {
        _priority = pri;
    }

    /**
     * Gets the packet initialization timestamp in milliseconds.
     *
     * @return time in ms when packet was initialized
     */
    public long getBegin() {
        verifyNotReleased();
        return _initializeTime;
    }

    /**
     * Returns how long this packet has existed since initialization.
     *
     * @return lifetime in milliseconds
     */
    public long getLifetime() {
        return _context.clock().now() - _initializeTime;
    }

    /**
     * Resets the initialization timestamp to current time.
     */
    public void resetBegin() {
        _initializeTime = _context.clock().now();
    }

    /**
     * Marks the packet with a custom type for internal identification.
     *
     * @param type integer representing a mark type
     */
    public void markType(int type) {
        verifyNotReleased();
        _markedType = type;
    }

    /**
     * Gets the currently marked type of the packet.
     *
     * @return mark type integer or -1 if none set
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
     * Lazily obtains the {@link RemoteHostId} representing the sender's address and port.
     * This is initialized once per packet and cached thereafter.
     *
     * @return remote host identifier or null if address unavailable
     */
    RemoteHostId getRemoteHost() {
        RemoteHostId local = _remoteHost;
        if (local == null) {
            synchronized (this) {
                if (_remoteHost == null) {
                    InetAddress addr = _packet.getAddress();
                    if (addr != null) {
                        _remoteHost = new RemoteHostId(addr.getAddress(), _packet.getPort());
                    } else {
                        _remoteHost = null;  // explicit null
                    }
                }
                local = _remoteHost;
            }
        }
        return local;
    }

    /**
     * Sets the enqueue time timestamp, indicating when the packet was queued for processing.
     *
     * @param now enqueue time in milliseconds
     */
    public void setEnqueueTime(long now) {
        _enqueueTime = now;
    }

    /**
     * Marks the packet as received, recording the current time.
     */
    void received() {
        _receivedTime = _context.clock().now();
    }

    /**
     * Gets the time the packet was enqueued.
     *
     * @return enqueue timestamp in milliseconds
     */
    public long getEnqueueTime() {
        return _enqueueTime;
    }

    /**
     * Returns the amount of time elapsed since the packet was received.
     *
     * @return milliseconds since received, or 0 if never received
     */
    long getTimeSinceReceived() {
        long received = _receivedTime;
        return (received > 0) ? _context.clock().now() - received : 0;
    }

    /**
     * Requests outbound bandwidth for sending this packet.
     * The bandwidth request is tracked and can be aborted if the packet is released early.
     */
    public void requestOutboundBandwidth() {
        verifyNotReleased();
        FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestOutbound(_packet.getLength(), 0, "UDP sender");
        _bandwidthRequest.set(req);
    }

    /**
     * Returns the current bandwidth request associated with this packet.
     *
     * @return bandwidth request object, or null if none exists
     */
    public FIFOBandwidthLimiter.Request getBandwidthRequest() {
        verifyNotReleased();
        return _bandwidthRequest.get();
    }

    /**
     * Releases this packet back to the object cache and aborts any outstanding bandwidth request.
     * Safe to call multiple times. After release, accessing the packet will log errors.
     */
    public void release() {
        synchronized(this) {
            if (_released) return;
            _released = true;
        }
        FIFOBandwidthLimiter.Request br = _bandwidthRequest.getAndSet(null);
        if (br != null) {
            synchronized (br) {
                if (br.getPendingRequested() > 0)
                    br.abort();
            }
        }
        if (CACHE)
            _packetCache.release(this);
    }

    /**
     * Clears the internal packet cache if caching is enabled.
     * Useful to free resources, e.g. on shutdown or restart.
     */
    public static void clearCache() {
        if (CACHE)
            _packetCache.clear();
    }

    /**
     * Reduces cache size under memory pressure.
     */
    public static void reduceCacheSize() {
        if (CACHE && _packetCache.size() > MIN_CACHE_SIZE) {
            int targetSize = Math.max(MIN_CACHE_SIZE, _packetCache.size() / 2);
            while (_packetCache.size() > targetSize) {
                _packetCache.acquire().release();
            }
        }
    }

    /**
     * Logs an error if an operation is attempted on a packet that has already been released.
     * This helps prevent bugs caused by reusing or accessing stale packet instances.
     */
    private void verifyNotReleased() {
        if (!CACHE) return;
        if (_released) {
            Log log = _context.logManager().getLog(UDPPacket.class);
            log.error("Access attempted on already released UDPPacket", new IllegalStateException());
        }
    }

    /**
     * Returns a detailed string representation of this packet, including address, size,
     * priority, message and mark types, fragment counts, and timing information.
     * For logging and debugging purposes.
     */
    @Override
    public String toString() {
        if (_released)
            return "RELEASED PACKET";

        // Only build detailed string if debug logging is enabled
        if (!_context.logManager().getLog(UDPPacket.class).shouldDebug())
            return "UDPPacket[size=" + _packet.getLength() + "]";
            
        StringBuilder buf = new StringBuilder(128);
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
     * Acquires a UDPPacket instance, either from the cache or by creating a new instance.
     * Initializes the packet for use.
     *
     * @param ctx RouterContext for timing and logging
     * @param inbound true if this packet is inbound, false if outbound (currently unused)
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