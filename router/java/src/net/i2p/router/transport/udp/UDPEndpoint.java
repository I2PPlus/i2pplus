package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Coordinate the low-level datagram socket, creating and managing the UDPSender and
 * UDPReceiver.
 */
class UDPEndpoint implements SocketListener {
    /** The router context. */
    private final RouterContext _context;
    /** Logger for the UDP endpoint. */
    private final Log _log;
    /** The port this endpoint listens on. */
    private int _listenPort;
    /** The transport that owns this endpoint. */
    private final UDPTransport _transport;
    /** The thread sending the packets. */
    private UDPSender _sender;
    /** The thread receiving the packets. */
    private UDPReceiver _receiver;
    /** The socket read and written by the endpoint. */
    private DatagramSocket _socket;
    /** The address the socket is bound to. */
    private final InetAddress _bindAddress;
    /** True for the IPv4 endpoint. */
    private final boolean _isIPv4;
    /** True for the IPv6 endpoint. */
    private final boolean _isIPv6;
    /** Sequence number of the created endpoints. */
    private static final AtomicInteger _counter = new AtomicInteger();

    /** Smallest socket buffer size asked for. */
    private static final int MIN_SOCKET_BUFFER = 256*1024;

    /**
     * Record the settings a later {@link #startup} binds the UDP socket with. No
     * socket is opened here, so the address family flags are decided from
     * {@code bindAddress} alone; a null address means a dual-stack wildcard.
     *
     * @param ctx router context supplying the clock and property source
     * @param transport may be null for unit testing ONLY
     * @param listenPort -1 or the requested port, may not be honored
     * @param bindAddress null ok
     */
    public UDPEndpoint(RouterContext ctx, UDPTransport transport, int listenPort, InetAddress bindAddress) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPEndpoint.class);
        _transport = transport;
        _bindAddress = bindAddress;
        _listenPort = listenPort;
        _isIPv4 = bindAddress == null || bindAddress instanceof Inet4Address;
        _isIPv6 = bindAddress == null || bindAddress instanceof Inet6Address;
    }

    /**
     *  Caller should call {@link #getListenPort()} after this to get the actual bound port and determine success .
     * <p>
     *  Can be restarted.
     *
     * @throws SocketException if no port could be bound to _bindAddress
     */
    public synchronized void startup() throws SocketException {
        if (_log.shouldDebug())
            _log.debug("Starting up the UDP endpoint");
        shutdown();
        _socket = getSocket();
        if (_socket == null) {
            _log.log(Log.CRIT, "[UDP] Unable to open a port");
            throw new SocketException("[SSU] Unable to bind to a port on " + _bindAddress);
        }
        int count = _counter.incrementAndGet();
        _sender = new UDPSender(_context, _socket, "UDPSender " + count, this);
        _sender.startup();
        if (_transport != null) {
            _receiver = new UDPReceiver(_context, _transport, _socket, "UDPReceiver " + count, this);
            _receiver.startup();
        }
    }

    /**
     * Shut down the sender, receiver, and socket.
     * The receiver runs only when a transport was given, so it may be absent.
     */
    public synchronized void shutdown() {
        if (_sender != null) {
            _sender.shutdown();
            if (_receiver != null) {
                _receiver.shutdown();
            }
        }
        if (_socket != null) {
            _socket.close();
        }
    }

    /**
     * The listen port for the next socket bind.
     *
     * @param newPort the port to request at the next bind, or below 1 to pick randomly
     */
    public void setListenPort(int newPort) { _listenPort = newPort; }
    /** Times a different port is tried before failing. */
    private static final int MAX_PORT_RETRIES = 20;

    /**
     *  Open socket using requested port in _listenPort and  bind host in _bindAddress.
     *  If _listenPort &lt;= 0, or requested port is busy, repeatedly try a new random port.
     *  @return null on failure
     *          Sets _listenPort to actual port or -1 on failure
     */
    private DatagramSocket getSocket() {
        DatagramSocket socket = null;
        int port = _listenPort;
        if (port > 0 && !TransportUtil.isValidPort(port)) {
            TransportUtil.logInvalidPort(_log, "UDP", port);
            port = -1;
        }

        for (int i = 0; i < MAX_PORT_RETRIES; i++) {
             if (port <= 0) {
                 // try random ports rather than just do new DatagramSocket()
                 // so we stay out of the way of other I2P stuff
                 port = TransportUtil.selectRandomPort(_context, UDPTransport.STYLE);
             }
              try {
                  if (_bindAddress == null)
                      socket = new DatagramSocket(port);
                  else
                      socket = new DatagramSocket(port, _bindAddress);
                  if (!SystemVersion.isAndroid()) {
                      if (socket.getSendBufferSize() < MIN_SOCKET_BUFFER)
                          socket.setSendBufferSize(MIN_SOCKET_BUFFER);
                      if (socket.getReceiveBufferSize() < MIN_SOCKET_BUFFER)
                          socket.setReceiveBufferSize(MIN_SOCKET_BUFFER);
                  }
                  break;
              } catch (SocketException se) {
                  if (socket != null) {socket.close(); socket = null;}
                  if (_log.shouldWarn())
                      _log.warn("Binding to port " + port + " failed", se);
              }
             port = -1;
        }
        if (socket == null) {
            _log.log(Log.CRIT, "[SSU] Unable to bind to a port on: " + _bindAddress);
        } else if (port != _listenPort) {
            if (_listenPort > 0)
                _log.error("[SSU] Unable to bind to requested port " + _listenPort + ", using random port: " + port);
            else
                _log.logAlways(Log.INFO, "UDP random port selected: " + port);
        }
        _listenPort = port;
        return socket;
    }


    /**
     * Call after {@link #startup()} to get the actual port, or -1 on startup failure.
     *
     * @return the port the socket was actually bound to, or -1 if no bind succeeded
     */
    public int getListenPort() { return _listenPort; }
    /**
     * The UDP sender.
     *
     * @return the UDPSender thread pool that transmits outbound packets
     */
    public UDPSender getSender() { return _sender; }

    /**
     * Add the packet to the outbound queue to be sent ASAP (as allowed by
     * the bandwidth limiter)
     * BLOCKING if queue is full.
     *
     * @param packet the packet to hand to the sender thread for transmission
     */
    public void send(UDPPacket packet) {
        _sender.add(packet);
    }

    /**
     * Blocking call to receive the next inbound UDP packet from any peer.
     * <p>
     * UNIT TESTING ONLY. Direct from the socket.
     * In normal operation, UDPReceiver thread injects to PacketHandler queue.
     *
     * @return null if we have shut down, or on failure
     */
    public UDPPacket receive() {
        UDPPacket packet = UDPPacket.acquire(_context, true);
        try {
            _socket.receive(packet.getPacket());
            return packet;
        } catch (IOException ioe) {
            packet.release();
            return null;
        }
    }

    /**
     *  Clear outbound queue, probably in preparation for sending destroy() to everybody.
     *  @since 0.9.2
     */
    public void clearOutbound() {
        if (_sender != null)
            _sender.clear();
    }

    /**
     * Whether this endpoint was configured for IPv4.
     *
     * @return true for wildcard too
     * @since IPv6
     */
    public boolean isIPv4() {
        return _isIPv4;
    }

    /**
     * Whether this endpoint was configured for IPv6.
     *
     * @return true for wildcard too
     * @since IPv6
     */
    public boolean isIPv6() {
        return _isIPv6;
    }

    /**
     * Shut down the endpoint and tell the transport it failed.
     *
     * @since 0.9.16
     */
    public void fail() {
        shutdown();
        _transport.fail(this);
    }

    /**
     * Endpoint summary for log messages.
     *
     * @since 0.9.16
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("UDP Socket ");
        if (_bindAddress != null)
            buf.append(_bindAddress.toString()).append(' ');
        buf.append("port ").append(_listenPort);
        return buf.toString();
    }
}
