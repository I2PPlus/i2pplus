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
 * Lowest level component to pull raw UDP datagrams off the wire as fast
 * as possible, controlled by both the bandwidth limiter and the router's
 * throttle.  If the inbound queue gets too large or packets have been
 * waiting around too long, they are dropped.  Packets should be pulled off
 * from the queue ASAP by a {@link PacketHandler}
 *
 * There is a UDPReceiver for each UDPEndpoint.
 * It contains a thread but no queue. Received packets are queued
 * in the common PacketHandler queue.
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
        if (_handler == null)
            throw new IllegalStateException();
        _runner = new Runner();
        //_context.statManager().createRateStat("udp.receivePacketSize", "How large packets received are", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receiveRemaining", "How many packets are left sitting on the receiver's queue", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.droppedInbound", "How many packet are queued up but not yet received when we drop", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveHolePunch", "How often we receive a NAT hole punch", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.ignorePacketFromDroplist", "Packet lifetime for those dropped on the drop list", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveFailsafe", "limiter stuck?", "Transport [UDP]", UDPTransport.RATES);
    }

    /**
     *  Cannot be restarted (socket is final)
     */
    public synchronized void startup() {
        //adjustDropProbability();
        _keepRunning = true;
        I2PThread t = new I2PThread(_runner, _name, true);
        t.setPriority(I2PThread.MAX_PRIORITY);
        t.start();
    }

    public synchronized void shutdown() {
        _keepRunning = false;
    }

/*********
    private void adjustDropProbability() {
        String p = _context.getProperty("i2np.udp.dropProbability");
        if (p != null) {
            try {
                ARTIFICIAL_DROP_PROBABILITY = Integer.parseInt(p);
            } catch (NumberFormatException nfe) {}
            if (ARTIFICIAL_DROP_PROBABILITY < 0) ARTIFICIAL_DROP_PROBABILITY = 0;
        } else {
            //ARTIFICIAL_DROP_PROBABILITY = 0;
        }
    }
**********/

    /**
     * Replace the old listen port with the new one, returning the old.
     * NOTE: this closes the old socket so that blocking calls unblock!
     *
     */
/*********
    public DatagramSocket updateListeningPort(DatagramSocket socket, int newPort) {
        return _runner.updateListeningPort(socket, newPort);
    }
**********/

    /** if a packet been sitting in the queue for a full second (meaning the handlers are overwhelmed), drop subsequent packets */
    private static final long MAX_QUEUE_PERIOD = 2*1000;

/*********
    private static int ARTIFICIAL_DROP_PROBABILITY = 0; // 4

    private static final int ARTIFICIAL_DELAY = 0; // 200;
    private static final int ARTIFICIAL_DELAY_BASE = 0; //600;
**********/

    /** @return zero (was queue size) */
    private int receive(UDPPacket packet) {
/*********
        //adjustDropProbability();

        if (ARTIFICIAL_DROP_PROBABILITY > 0) {
            // the first check is to let the compiler optimize away this
            // random block on the live system when the probability is == 0
            // (not if it isn't final jr)
            int v = _context.random().nextInt(100);
            if (v <= ARTIFICIAL_DROP_PROBABILITY) {
                if (_log.shouldError())
                    _log.error("Drop with v=" + v + " p=" + ARTIFICIAL_DROP_PROBABILITY + " packet size: " + packet.getPacket().getLength() + ": " + packet);
                _context.statManager().addRateData("udp.droppedInboundProbabalistically", 1, 0);
                return -1;
            } else {
                _context.statManager().addRateData("udp.acceptedInboundProbabalistically", 1, 0);
            }
        }

        if ( (ARTIFICIAL_DELAY > 0) || (ARTIFICIAL_DELAY_BASE > 0) ) {
            long delay = ARTIFICIAL_DELAY_BASE + _context.random().nextInt(ARTIFICIAL_DELAY);
            if (_log.shouldInfo())
                _log.info("Delay packet " + packet + " for " + delay);
            SimpleTimer2.getInstance().addEvent(new ArtificiallyDelayedReceive(packet), delay);
            return -1;
        }
**********/

        return doReceive(packet);
    }

    /**
     * BLOCKING if queue between here and PacketHandler is full.
     *
     * @return zero (was queue size)
     */
    private final int doReceive(UDPPacket packet) {
        if (!_keepRunning)
            return 0;

        if (_log.shouldInfo())
            _log.info("Received UDP packet" + packet);

        RemoteHostId from = packet.getRemoteHost();
        if (_transport.isInDropList(from)) {
            if (_log.shouldInfo())
                _log.info("Ignoring UDP packet from the drop-listed peer: " + from);
            _context.statManager().addRateData("udp.ignorePacketFromDroplist", packet.getLifetime());
            packet.release();
            return 0;
        }

        // drop anything apparently from our IP (any port)
        if (Arrays.equals(from.getIP(), _transport.getExternalIP()) && !_transport.allowLocal()) {
            if (_log.shouldWarn())
                _log.warn("Dropping (spoofed?) UDP packet from ourselves");
            packet.release();
            return 0;
        }

/****
        packet.enqueue();
        boolean rejected = false;
        int queueSize = 0;
        long headPeriod = 0;

            UDPPacket head = _inboundQueue.peek();
            if (head != null) {
                headPeriod = head.getLifetime();
                if (headPeriod > MAX_QUEUE_PERIOD) {
                    rejected = true;
                }
            }
            if (!rejected) {
****/
                try {
                    _handler.queueReceived(packet);
                } catch (InterruptedException ie) {
                    packet.release();
                    _keepRunning = false;
                }
                //return queueSize + 1;
                return 0;
/****
            }

        // rejected
        packet.release();
        _context.statManager().addRateData("udp.droppedInbound", queueSize, headPeriod);
        if (_log.shouldWarn()) {
            queueSize = _inboundQueue.size();
            StringBuilder msg = new StringBuilder();
            msg.append("Dropping inbound packet with ");
            msg.append(queueSize);
            msg.append(" queued for ");
            msg.append(headPeriod);
            msg.append(" packet handlers: ").append(_transport.getPacketHandlerStatus());
            _log.warn(msg.toString());
        }
        return queueSize;
****/
    }

  /****
    private class ArtificiallyDelayedReceive implements SimpleTimer.TimedEvent {
        private UDPPacket _packet;
        public ArtificiallyDelayedReceive(UDPPacket packet) { _packet = packet; }
        public void timeReached() { doReceive(_packet); }
    }
  ****/


    private class Runner implements Runnable {
        //private volatile boolean _socketChanged;

        public void run() {
            //_socketChanged = false;
            while (_keepRunning) {
                //if (_socketChanged) {
                //    Thread.currentThread().setName(_name + "." + _id);
                //    _socketChanged = false;
                //}
                UDPPacket packet = UDPPacket.acquire(_context, true);
                DatagramPacket dpacket = packet.getPacket();

                // Android ICS bug
                // http://code.google.com/p/android/issues/detail?id=24748
                if (_isAndroid)
                    dpacket.setLength(UDPPacket.MAX_PACKET_SIZE);

                // block before we read...
                //if (_log.shouldDebug())
                //    _log.debug("Before throttling receive");
                while (!_context.throttle().acceptNetworkMessage())
                    try { Thread.sleep(10); } catch (InterruptedException ie) {}

                try {
                    //if (_log.shouldInfo())
                    //    _log.info("Before blocking socket.receive on " + System.identityHashCode(packet));
                    //synchronized (Runner.this) {
                        _socket.receive(dpacket);
                    //}
                    int size = dpacket.getLength();
                    if (_log.shouldInfo())
                        _log.info("After blocking socket.receive, UDP packet is " + size + " bytes on " + System.identityHashCode(packet));
                    packet.resetBegin();

                    // and block after we know how much we read but before
                    // we release the packet to the inbound queue
                    if (size >= UDPPacket.MAX_PACKET_SIZE) {
                        // DatagramSocket javadocs: If the message is longer than the packet's length, the message is truncated.
                        throw new IOException("UDP packet too large! Truncated and dropped from: " + packet.getRemoteHost());
                    }
                    if (_context.commSystem().isDummy()) {
                        // testing
                        packet.release();
                    } else if (size >= SSU2Util.MIN_DATA_LEN) {
                        //FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestInbound(size, "UDP receiver");
                        //_context.bandwidthLimiter().requestInbound(req, size, "UDP receiver");
                        FIFOBandwidthLimiter.Request req =
                              _context.bandwidthLimiter().requestInbound(size, "UDP receiver");
                        // failsafe, don't wait forever
                        int waitCount = 0;
                        while (req.getPendingRequested() > 0 && waitCount++ < 5) {
                            req.waitForNextAllocation();
                        }
                        if (waitCount >= 5) {
                            // tell FBL we didn't receive it, but receive it anyway
                            req.abort();
                            _context.statManager().addRateData("udp.receiveFailsafe", 1);
                        }

                        receive(packet);
                        //_context.statManager().addRateData("udp.receivePacketSize", size);
                    } else {
                        // SSU1 had 0 byte hole punch, SSU2 does not
                        if (_log.shouldWarn())
                            _log.warn("Dropping short " + size + " byte UDP packet from [" + dpacket.getAddress() + ":" + dpacket.getPort() + "]");
                        packet.release();
                    }
                } catch (IOException ioe) {
                    //if (_socketChanged) {
                    //    if (_log.shouldInfo())
                    //        _log.info("Changing ports...");
                    //} else {
                    if (_log.shouldDebug()) {
                        _log.debug("Error receiving UDP packet", ioe);
                    } else if (_log.shouldWarn()) {
                        _log.warn("Error receiving UDP packet: " + ioe.getMessage());
                    }
                    //}
                    packet.release();
                    if (_socket.isClosed()) {
                        if (_keepRunning) {
                            _keepRunning = false;
                            _endpoint.fail();
                        }
                    } else if (_keepRunning) {
                        // TODO count consecutive errors, give up after too many?
                        try { Thread.sleep(100); } catch (InterruptedException ie) {}
                    }
                }
            }
            if (_log.shouldWarn())
                _log.warn("Stopped receiving UDP packets on: " + _endpoint);
        }

     /******
        public DatagramSocket updateListeningPort(DatagramSocket socket, int newPort) {
            _name = "UDPReceive on " + newPort;
            DatagramSocket old = null;
            synchronized (Runner.this) {
                old = _socket;
                _socket = socket;
            }
            _socketChanged = true;
            // ok, its switched, now lets break any blocking calls
            old.close();
            return old;
        }
      *****/
    }
}
