package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Arrays;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Lowest level component to pull raw UDP datagrams off the wire as fast as possible,
 * controlled by both the bandwidth limiter and the router's throttle. If the inbound
 * queue gets too large or packets have been waiting around too long, they are dropped.
 * Packets should be pulled off from the queue ASAP by a {@link PacketHandler}
 *
 * There is a UDPReceiver for each UDPEndpoint. It contains a thread but no queue.
 * Received packets are queued in the common PacketHandler queue.
 */
class UDPReceiver {
    private final RouterContext _context;
    private final Log _log;
    private final DatagramSocket _socket;
    private String _name;
    private volatile boolean _keepRunning;
    private final Runner _runner;
    private final UDPTransport _transport;
    private final PacketHandler _handler;
    private final SocketListener _endpoint;

    private static final boolean _isAndroid = SystemVersion.isAndroid();

    public UDPReceiver(RouterContext ctx, UDPTransport transport, DatagramSocket socket, String name,
                       SocketListener lsnr) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPReceiver.class);
        _name = name;
        _socket = socket;
        _transport = transport;
        _endpoint = lsnr;
        _handler = transport.getPacketHandler();
        if (_handler == null) {throw new IllegalStateException();}
        _runner = new Runner();
        _context.statManager().createRateStat("udp.ignorePacketFromDroplist", "Packet lifetime for those dropped on the drop list", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveFailsafe", "How often we failed to receive a UDP packet", "Transport [UDP]", UDPTransport.RATES);
    }

    /**
     *  Cannot be restarted (socket is final)
     */
    public synchronized void startup() {
        _keepRunning = true;
        I2PThread t = new I2PThread(_runner, _name, true);
        t.start();
    }

    public synchronized void shutdown() {
        _keepRunning = false;
    }

    /** if a packet been sitting in the queue for a full second (meaning the handlers are overwhelmed), drop subsequent packets */
    private static final long MAX_QUEUE_PERIOD = 2*1000;

    /** @return zero (was queue size) */
    private int receive(UDPPacket packet) {return doReceive(packet);}

    /**
     * BLOCKING if queue between here and PacketHandler is full.
     *
     * @return zero (was queue size)
     */
    private final int doReceive(UDPPacket packet) {
        if (!_keepRunning) {return 0;}
        if (_log.shouldInfo()) {_log.info("Received UDP packet from " + packet);}

        RemoteHostId from = packet.getRemoteHost();
        if (_transport.isInDropList(from)) {
            if (_log.shouldInfo()) {_log.info("Ignoring UDP packet from the drop-listed peer: " + from);}
            _context.statManager().addRateData("udp.ignorePacketFromDroplist", packet.getLifetime());
            packet.release();
            return 0;
        }

        // drop anything apparently from our IP (any port)
        if (Arrays.equals(from.getIP(), _transport.getExternalIP()) && !_transport.allowLocal()) {
            if (_log.shouldWarn()) {_log.warn("Dropping (spoofed?) UDP packet from ourselves");}
            packet.release();
            return 0;
        }

        // Early drop if PacketHandler queue is backlogged (0.9.68+)
        // This prevents memory buildup when handlers can't keep up with receive rate
        if (_handler.isBacklogged()) {
            if (_log.shouldWarn()) {
                _log.warn("Dropping packet from " + from + " - handler queue backlogged");
            }
            _context.statManager().addRateData("udp.receiveBacklogDrop", 1);
            packet.release();
            return 0;
        }

        try {_handler.queueReceived(packet);}
        catch (InterruptedException ie) {
            packet.release();
            _keepRunning = false;
        }
        //return queueSize + 1;
        return 0;
    }

    private class Runner implements Runnable {

        public void run() {
            while (_keepRunning) {
                UDPPacket packet = UDPPacket.acquire(_context, true);
                DatagramPacket dpacket = packet.getPacket();

                // Android ICS bug - http://code.google.com/p/android/issues/detail?id=24748
                if (_isAndroid) {dpacket.setLength(UDPPacket.MAX_PACKET_SIZE);}

                while (!_context.throttle().acceptNetworkMessage()) {
                    try {Thread.sleep(10);}
                    catch (InterruptedException ie) {}
                }

                try {
                    _socket.receive(dpacket);
                    int size = dpacket.getLength();
                    if (_log.shouldInfo()) {
                        _log.info("After blocking socket.receive, UDP packet [" + System.identityHashCode(packet) + "] is " + size + " bytes");
                    }
                    packet.resetBegin();

                    // and block after we know how much we read but before we release the packet to the inbound queue
                    if (size >= UDPPacket.MAX_PACKET_SIZE) {
                        // DatagramSocket javadocs: If the message is longer than the packet's length, the message is truncated.
                        throw new IOException("UDP packet too large! Truncated and dropped from: " + packet.getRemoteHost());
                    }
                    if (_context.commSystem().isDummy()) {packet.release();} // testing
                    else if (size >= SSU2Util.MIN_DATA_LEN) {
                        FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestInbound(size, "UDP receiver");
                        int waitCount = 0; // failsafe, don't wait forever
                        while (req.getPendingRequested() > 0 && waitCount++ < 5) {req.waitForNextAllocation();}
                        if (waitCount >= 5) {
                            // tell FBL we didn't receive it, but accept it anyway
                            req.abort();
                            _context.statManager().addRateData("udp.receiveFailsafe", 1);
                        }
                        receive(packet);
                        //_context.statManager().addRateData("udp.receivePacketSize", size);
                    } else {
                        // SSU1 had 0 byte hole punch, SSU2 does not
                        if (_log.shouldWarn()) {
                            String ipAddress = dpacket.getAddress().toString().replace("/", "");
                            _log.warn("Dropping short " + size + " byte UDP packet from " + ipAddress + ":" + dpacket.getPort());
                        }
                        packet.release();
                    }
                } catch (IOException ioe) {
                    if (_log.shouldDebug()) {_log.debug("Error receiving UDP packet", ioe);}
                    else if (_log.shouldWarn()) {_log.warn("Error receiving UDP packet: " + ioe.getMessage());}
                    packet.release();
                    if (_socket.isClosed()) {
                        if (_keepRunning) {
                            _keepRunning = false;
                            _endpoint.fail();
                        }
                    } else if (_keepRunning) {
                        // TODO count consecutive errors, give up after too many?
                        try {Thread.sleep(100);}
                        catch (InterruptedException ie) {}
                    }
                }
            }
            if (_log.shouldWarn()) {_log.warn("Stopped receiving UDP packets on: " + _endpoint);}
        }
    }

}
