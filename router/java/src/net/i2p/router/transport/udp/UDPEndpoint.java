package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
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
    private final RouterContext _context;
    private final Log _log;
    private int _listenPort;
    private final UDPTransport _transport;
    private UDPSender _sender;
    private UDPReceiver _receiver;
    private DatagramSocket _socket;
    private final InetAddress _bindAddress;
    private final boolean _isIPv4, _isIPv6;
    private static final AtomicInteger _counter = new AtomicInteger();

    private static final int MIN_SOCKET_BUFFER = 256*1024;

    /**
     *  @param transport may be null for unit testing ONLY
     *  @param listenPort -1 or the requested port, may not be honored
     *  @param bindAddress null ok
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
     *  Caller should call getListenPort() after this to get the actual bound port and determine success .
     *
     *  Can be restarted.
     */
    public synchronized void startup() throws SocketException {
        if (_log.shouldLog(Log.DEBUG))
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

    public synchronized void shutdown() {
        if (_sender != null) {
            _sender.shutdown();
            _receiver.shutdown();
        }
        if (_socket != null) {
            _socket.close();
        }
    }

    public void setListenPort(int newPort) { _listenPort = newPort; }

/*******
    public void updateListenPort(int newPort) {
        if (newPort == _listenPort) return;
        try {
            if (_bindAddress == null)
                _socket = new DatagramSocket(_listenPort);
            else
                _socket = new DatagramSocket(_listenPort, _bindAddress);
            _sender.updateListeningPort(_socket, newPort);
            // note: this closes the old socket, so call this after the sender!
            _receiver.updateListeningPort(_socket, newPort);
            _listenPort = newPort;
        } catch (SocketException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to bind on " + _listenPort);
        }
    }
********/

    private static final int MAX_PORT_RETRIES = 20;

    /**
     *  Open socket using requested port in _listenPort and  bind host in _bindAddress.
     *  If _listenPort <= 0, or requested port is busy, repeatedly try a new random port.
     *  @return null on failure
     *  Sets _listenPort to actual port or -1 on failure
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
                 if (_log.shouldLog(Log.WARN))
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


    /** call after startup() to get actual port or -1 on startup failure */
    public int getListenPort() { return _listenPort; }
    public UDPSender getSender() { return _sender; }

    /**
     * Add the packet to the outobund queue to be sent ASAP (as allowed by
     * the bandwidth limiter)
     * BLOCKING if queue is full.
     */
    public void send(UDPPacket packet) {
        _sender.add(packet);
    }

    /**
     * Blocking call to receive the next inbound UDP packet from any peer.
     *
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
     *  @return true for wildcard too
     *  @since IPv6
     */
    public boolean isIPv4() {
        return _isIPv4;
    }

    /**
     *  @return true for wildcard too
     *  @since IPv6
     */
    public boolean isIPv6() {
        return _isIPv6;
    }

    /**
     *  @since 0.9.16
     */
    public void fail() {
        shutdown();
        _transport.fail(this);
    }

    /**
     *  @since 0.9.16
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
