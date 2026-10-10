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

/** UDP datagram wrapper implementing CDPQEntry for priority queue dispatch. */
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
    private final AtomicReference<FIFOBandwidthLimiter.Request> _bandwidthRequest = new AtomicReference<>();
    private long _seqNum;
    private int _messageType;
    private int _fragmentCount;

    /**
     * Factory for UDP packet caching.
     * Provides new instances for the {@link TryCache}.
     */
    private static class PacketFactory implements TryCache.ObjectFactory<UDPPacket> {
        /** I2P context for logging. */
        static volatile RouterContext context;

        /**
         * New instance.
         *
         * @return a fresh, unreleased packet
         */
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

    /** Static initializer. */
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

    /** Maximum SSU packet size in bytes. */
    static final int MAX_PACKET_SIZE = 1572;
    /** Size of initialization vector in bytes. */
    public static final int IV_SIZE = 16;
    /** Size of MAC (Message Authentication Code) in bytes. */
    public static final int MAC_SIZE = 16;

    // Payload type constants
    /** Packet type: session request. */
    public static final int PAYLOAD_TYPE_SESSION_REQUEST = 0;
    /** Packet type: session created. */
    public static final int PAYLOAD_TYPE_SESSION_CREATED = 1;
    /** Packet type: session confirmed. */
    public static final int PAYLOAD_TYPE_SESSION_CONFIRMED = 2;
    /** Packet type: relay request. */
    public static final int PAYLOAD_TYPE_RELAY_REQUEST = 3;
    /** Packet type: relay response. */
    public static final int PAYLOAD_TYPE_RELAY_RESPONSE = 4;
    /** Packet type: relay intro. */
    public static final int PAYLOAD_TYPE_RELAY_INTRO = 5;
    /** Packet type: data. */
    public static final int PAYLOAD_TYPE_DATA = 6;
    /** Packet type: test. */
    public static final int PAYLOAD_TYPE_TEST = 7;
    /** Packet type: session destroy. */
    public static final int PAYLOAD_TYPE_SESSION_DESTROY = 8;
    /** Largest payload type value. */
    public static final int MAX_PAYLOAD_TYPE = PAYLOAD_TYPE_SESSION_DESTROY;

    /**
     * Packet type enumeration.
     *
     * @param type one of the PAYLOAD_TYPE_* constants
     * @return the human-readable name for the type, or a string naming the
     *         unrecognised value if it is not a known payload type
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
     * Used by the packet cache factory.
     *
     * @param ctx RouterContext for logging and timing
     */
    private UDPPacket(RouterContext ctx) {
        // Buffer sized to MAX_PACKET_SIZE so DatagramSocket.receive() never silently
        // truncates an over-MTU datagram: UDPReceiver rejects size >= MAX_PACKET_SIZE
        // loudly, but a smaller buffer would mask that guard (a truncated length is
        // indistinguishable from a valid short packet).
        _data = new byte[MAX_PACKET_SIZE];
        _packet = new DatagramPacket(_data, _data.length);
        init(ctx);
    }

    /**
     * Initialize or reset the packet state for reuse.
     * Resets metadata and timestamps. Data buffer is not zeroed — callers
     * fully overwrite before send and the DatagramPacket length limits reads.
     *
     * @param ctx RouterContext used for timing and logging
     */
    private void init(RouterContext ctx) {
        _context = ctx;
        _packet.setData(_data);
        _initializeTime = _context.clock().now();
        _markedType = -1;
        _remoteHost = null;
        _released = false;
        _messageType = -1;
        _enqueueTime = 0;
        _receivedTime = 0;
        _fragmentCount = 0;
        _bandwidthRequest.set(null);
    }

    /**
     * Packet sequence number.
     *
     * @param num sequence number to set
     */
    @Override
    public void setSeqNum(long num) {
        _seqNum = num;
    }

    /**
     * Packet sequence number.
     *
     * @return sequence number
     */
    @Override
    public long getSeqNum() {
        return _seqNum;
    }

    /**
     * Underlying packet.
     *
     * @return the datagram carrying the payload, reflecting the buffer and
     *         length currently in use
     */
    public DatagramPacket getPacket() {
        verifyNotReleased();
        return _packet;
    }

    /** Send priority. */
    public int getPriority() {
        return _priority;
    }

    /**
     * Priority level.
     *
     * @param pri the send priority on the OutNetMessage scale of 100 to 1000,
     *        where a larger value means a more urgent packet
     */
    public void setPriority(int pri) {
        _priority = pri;
    }

    /**
     * Initialization time.
     *
     * @return the router clock time at which this packet was created or reset
     */
    public long getBegin() {
        verifyNotReleased();
        return _initializeTime;
    }

    /**
     * Packet lifetime.
     *
     * @return milliseconds elapsed on the router clock since initialization
     */
    public long getLifetime() {
        return _context.clock().now() - _initializeTime;
    }

    /** Reset initialization time. */
    public void resetBegin() {
        _initializeTime = _context.clock().now();
    }

    /**
     * Mark type.
     *
     * @param type 1 to flag the packet as an ack, which lets the sender record
     *        ack send timings separately; any other value is simply stored
     */
    public void markType(int type) {
        verifyNotReleased();
        _markedType = type;
    }

    /**
     * Marked type.
     *
     * @return the mark set by markType(), or -1 if this packet has not been marked
     */
    public int getMarkedType() {
        verifyNotReleased();
        return _markedType;
    }

    /**
     * Message type
     *
     * @return the SSU2 message type this packet carries, or -1 when none has
     *         been set yet
     */
    int getMessageType() {
        return _messageType;
    }

    /**
     * Message type
     *
     * @param type the SSU2 message type being carried, recorded for debug logging
     */
    void setMessageType(int type) {
        _messageType = type;
    }

    /**
     * Fragment count
     *
     * @return the number of data fragments this packet carries, or -1 when it is
     *         an ack rather than a data fragment
     */
    int getFragmentCount() {
        return _fragmentCount;
    }

    /**
     * Fragment count
     *
     * @param count the number of data fragments this packet carries, or -1 to
     *        mark it as an ack with no fragment data
     */
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
            /** Lock for packet access. */
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

    /** Enqueue timestamp. */
    public void setEnqueueTime(long now) {
        _enqueueTime = now;
    }

    /**
     * Marks the packet as received, recording the current time.
     */
    void received() {
        _receivedTime = _context.clock().now();
    }

    /** Enqueue time. */
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

    /** Request outbound bandwidth. */
    public void requestOutboundBandwidth() {
        verifyNotReleased();
        FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestOutbound(_bandwidthRequest.get(),
                                                                                        _packet.getLength(), 0, "UDP sender");
        _bandwidthRequest.set(req);
    }

    /**
     * Bandwidth request.
     *
     * @return the limiter request recorded by requestOutboundBandwidth(), or
     *         null if no bandwidth has been requested for this packet yet
     */
    public FIFOBandwidthLimiter.Request getBandwidthRequest() {
        verifyNotReleased();
        return _bandwidthRequest.get();
    }

    /** Return the packet to the object pool for reuse. */
    public void release() {
        synchronized(this) {
            if (_released) return;
            _released = true;
        }
        FIFOBandwidthLimiter.Request br = _bandwidthRequest.get();
        if (br != null) {
            /** Bandwidth request lock. */
            synchronized (br) {
                if (br.getPendingRequested() > 0) {
                    br.abort();
                    // aborted requests stay queued in the limiter until swept;
                    // drop it so the next cycle allocates a fresh one
                    _bandwidthRequest.set(null);
                }
            }
        }
        if (CACHE)
            _packetCache.release(this);
    }

    /** Clear cache. */
    public static void clearCache() {
        if (CACHE)
            _packetCache.clear();
    }

    /** Reduce cache size. */
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
     * Inbound flag.
     *
     * @param ctx the context whose clock and packet cache the packet uses
     * @param inbound true if the packet is being prepared to be received
     * @return a pooled packet with its state initialized for ctx, ready to be
     *         filled in and sent or received
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

    /** Discard the packet and release it back to the cache. */
    public void drop() {
        release();
    }

}
