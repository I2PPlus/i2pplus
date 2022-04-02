package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.BlockingQueue;

import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
//import net.i2p.router.util.CoDelBlockingQueue;
import net.i2p.router.util.CoDelPriorityBlockingQueue;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Lowest level packet sender, pushes anything on its queue ASAP.
 *
 * There is a UDPSender for each UDPEndpoint.
 * It contains a thread and a queue. Packet to be sent are queued
 * by the PacketPusher.
 */
class UDPSender {
    private final RouterContext _context;
    private final Log _log;
    private final DatagramSocket _socket;
    private String _name;
    private final BlockingQueue<UDPPacket> _outboundQueue;
    private volatile boolean _keepRunning;
    private final Runner _runner;
    private final boolean _dummy;
    private final SocketListener _endpoint;

    private static final int TYPE_POISON = 99999;

    // Queue needs to be big enough that we can compete with NTCP for
    // bandwidth requests, and so CoDel can work well.
    // When full, packets back up into the PacketPusher thread, pre-CoDel.
//    private static final int MIN_QUEUE_SIZE = 128;
//    private static final int MAX_QUEUE_SIZE = 768;
    private static final int MIN_QUEUE_SIZE = SystemVersion.isSlow() ? 128 : 384;
    private static final int MAX_QUEUE_SIZE = SystemVersion.isSlow() ? 768 : 2048;
    private static final int CODEL_TARGET = 100;
    private static final int CODEL_INTERVAL = 500;

    public boolean fullStats() {
        return _context.getBooleanProperty("stat.full");
    }

    public UDPSender(RouterContext ctx, DatagramSocket socket, String name, SocketListener lsnr) {
        _context = ctx;
        _dummy = false; // ctx.commSystem().isDummy();
        _log = ctx.logManager().getLog(UDPSender.class);
        long maxMemory = SystemVersion.getMaxMemory();
        int cores = SystemVersion.getCores();
        boolean isSlow = SystemVersion.isSlow();
        long messageDelay = _context.throttle().getMessageDelay();
        int qsize = (int) Math.max(MIN_QUEUE_SIZE, Math.min(MAX_QUEUE_SIZE, maxMemory / (1024*1024)));
        //_outboundQueue = new CoDelBlockingQueue<UDPPacket>(ctx, "UDP-Sender", qsize, CODEL_TARGET, CODEL_INTERVAL);
        _outboundQueue = new CoDelPriorityBlockingQueue<UDPPacket>(ctx, "UDP-Sender", qsize, CODEL_TARGET, CODEL_INTERVAL);
        _socket = socket;
        _runner = new Runner();
        _name = name;
        _endpoint = lsnr;
        _context.statManager().createRateStat("udp.pushTime", "Time taken for a UDP packet get pushed out", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendQueueSize", "Number of packets queued on the UDP sender", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendQueueFailed", "Failed to add a new packet to the queue", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendQueueTrimmed", "Stale packets removed from queue", "Transport [UDP]", UDPTransport.RATES);
//        _context.statManager().createRequiredRateStat("udp.sendPacketSize", "Size of sent packets (bytes)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize", "Size of sent packets (bytes)", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.socketSendTime", "How long the actual socket.send took", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendBWThrottleTime", "How long send is blocked by bandwidth throttle", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendACKTime", "How long an ACK packet is blocked for", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendFailsafe", "Time bandwidth limiter is stuck", "Transport [UDP]", new long[] { 24*60*60*1000L });
        // used in RouterWatchdog
        if (fullStats())
            _context.statManager().createRequiredRateStat("udp.sendException", "Send fails (Windows exception?)", "Transport [UDP]", new long[] { 60*1000, 10*60*1000 });
        else
            _context.statManager().createRequiredRateStat("udp.sendException", "Send fails (Windows exception?)", "Transport", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_ACK, "ACK-only UDP packet size", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_PUNCH, "Hole punch UDP packet size", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_RESP, "Relay response UDP packet size", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_INTRO, "Relay introduction UDP packet size", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_RREQ, "Relay request UDP packet size", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_TCB, "Peer test Charlie to Bob UDP packet size", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_TBC, "Peer test Bob to Charlie UDP packet size", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_TTA, "Peer test to Alice UDP packet size", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_TFA, "Peer test from Alice UDP packet size", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_CONF, "Session confirmed UDP packet size", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_SREQ, "Session request UDP packet size", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_CREAT, "Session created UDP packet size", "Transport [UDP]", UDPTransport.RATES);
    }

    /**
     *  Cannot be restarted (socket is final)
     */
    public synchronized void startup() {
        if (_log.shouldDebug())
            _log.debug("Starting the runner: " + _name);
        _keepRunning = true;
        I2PThread t = new I2PThread(_runner, _name, true);
        t.setPriority(I2PThread.MAX_PRIORITY - 1);
        t.start();
    }

    public synchronized void shutdown() {
        if (!_keepRunning)
            return;
        _keepRunning = false;
        _outboundQueue.clear();
        UDPPacket poison = UDPPacket.acquire(_context, false);
        poison.setMessageType(TYPE_POISON);
        _outboundQueue.offer(poison);
        for (int i = 1; i <= 5 && !_outboundQueue.isEmpty(); i++) {
            try {
                Thread.sleep(i * 50);
            } catch (InterruptedException ie) {}
        }
        _outboundQueue.clear();
    }

    /**
     *  Clear outbound queue, probably in preparation for sending destroy() to everybody.
     *  @since 0.9.2
     */
    public void clear() {
        _outboundQueue.clear();
    }

/*********
    public DatagramSocket updateListeningPort(DatagramSocket socket, int newPort) {
        return _runner.updateListeningPort(socket, newPort);
    }
**********/


    /**
     * Add the packet to the queue.  This may block until there is space
     * available, if requested, otherwise it returns immediately
     *
     * @param blockTime how long to block IGNORED
     * @deprecated use add(packet)
     */
    @Deprecated
    public void add(UDPPacket packet, int blockTime) {
     /********
        //long expiration = _context.clock().now() + blockTime;
        int remaining = -1;
        long lifetime = -1;
        boolean added = false;
        int removed = 0;
        while ( (_keepRunning) && (remaining < 0) ) {
            //try {
                synchronized (_outboundQueue) {
                    // clear out any too-old packets
                    UDPPacket head = null;
                    if (!_outboundQueue.isEmpty()) {
                        head = (UDPPacket)_outboundQueue.get(0);
                        while (head.getLifetime() > MAX_HEAD_LIFETIME) {
                            _outboundQueue.remove(0);
                            removed++;
                            if (!_outboundQueue.isEmpty())
                                head = (UDPPacket)_outboundQueue.get(0);
                            else
                                break;
                        }
                    }

                    if (true || (_outboundQueue.size() < MAX_QUEUED)) {
                        lifetime = packet.getLifetime();
                        _outboundQueue.add(packet);
                        added = true;
                        remaining = _outboundQueue.size();
                        _outboundQueue.notifyAll();
                    } else {
                        long remainingTime = expiration - _context.clock().now();
                        if (remainingTime > 0) {
                            _outboundQueue.wait(remainingTime);
                        } else {
                            remaining = _outboundQueue.size();
                            _outboundQueue.notifyAll();
                        }
                        lifetime = packet.getLifetime();
                    }
                }
            //} catch (InterruptedException ie) {}
        }
        _context.statManager().addRateData("udp.sendQueueSize", remaining, lifetime);
        if (!added)
            _context.statManager().addRateData("udp.sendQueueFailed", remaining, lifetime);
        if (removed > 0)
            _context.statManager().addRateData("udp.sendQueueTrimmed", removed, remaining);
        if (_log.shouldDebug())
            _log.debug("Added the packet onto the queue with " + remaining + " remaining and a lifetime of " + lifetime);
        return remaining;
     ********/
        add(packet);
    }

    private static final int MAX_HEAD_LIFETIME = 3*1000;

    /**
     * Put it on the queue.
     * BLOCKING if queue is full (backs up PacketPusher thread)
     */
    public void add(UDPPacket packet) {
        if (packet == null || !_keepRunning) return;
        int psz = packet.getPacket().getLength();
        if (psz > PeerState2.MAX_MTU) {
            _log.error("Dropping large UDP packet " + psz + " bytes: " + packet, new Exception());
            return;
        }
        if (_dummy) {
            // testing
            // back to the cache
            packet.release();
            return;
        }
        packet.requestOutboundBandwidth();
        try {
            _outboundQueue.put(packet);
        } catch (InterruptedException ie) {
            packet.release();
            return;
        }
        //size = _outboundQueue.size();
        //_context.statManager().addRateData("udp.sendQueueSize", size, lifetime);
        if (_log.shouldDebug()) {
            _log.debug("Added UDP packet (" + psz + " bytes) to queue with lifetime of " + packet.getLifetime() + "ms");
        }
    }

    private class Runner implements Runnable {
        //private volatile boolean _socketChanged;

        public void run() {
            if (_log.shouldDebug())
                _log.debug("Running the UDP sender");
            //_socketChanged = false;
            while (_keepRunning) {
                //if (_socketChanged) {
                //    Thread.currentThread().setName(_name);
                //    _socketChanged = false;
                //}

                UDPPacket packet = getNextPacket();
                if (packet != null) {
                    if (_log.shouldDebug())
                        _log.debug("Attempting to send UDP packet to known peer" + packet);
                    // ?? int size2 = packet.getPacket().getLength();
                    int size = packet.getPacket().getLength();
                    long acquireTime = _context.clock().now();
                    if (size > 0) {
                        //_context.bandwidthLimiter().requestOutbound(req, size, "UDP sender");
                        FIFOBandwidthLimiter.Request req = packet.getBandwidthRequest();
                        if (req != null) {
                            // failsafe, don't wait forever
                            int waitCount = 0;
                            while (req.getPendingRequested() > 0 && waitCount++ < 5) {
                                req.waitForNextAllocation();
                            }
                            if (waitCount >= 5) {
                                // tell FBL we didn't send it, but send it anyway
                                req.abort();
                                _context.statManager().addRateData("udp.sendFailsafe", 1);
                            }
                        }
                    }

                    long afterBW = _context.clock().now();

                    //if (_log.shouldDebug()) {
                        //if (len > 128)
                        //    len = 128;
                        //_log.debug("Sending packet: (size="+size + "/"+size2 +")\nraw: " + Base64.encode(packet.getPacket().getData(), 0, size));
                    //}

                    if (packet.getMessageType() >= PacketBuilder.TYPE_FIRST)
                        _context.statManager().addRateData("udp.sendPacketSize." + packet.getMessageType(), size, packet.getFragmentCount());

                    //packet.getPacket().setLength(size);
                    try {
                        //long before = _context.clock().now();
                        //synchronized (Runner.this) {
                            // synchronization lets us update safely
                            //_log.debug("Break out datagram for " + packet);
                            DatagramPacket dp = packet.getPacket();
                            //if (_log.shouldDebug())
                            //    _log.debug("Just before socket.send of " + packet);
                            _socket.send(dp);
                            //if (_log.shouldDebug())
                            //    _log.debug("Just after socket.send of " + packet);
                        //}
                        //long sendTime = _context.clock().now() - before;
                        // less than 50 microsec
                        //_context.statManager().addRateData("udp.socketSendTime", sendTime, packet.getLifetime());
                        if (_log.shouldDebug())
                            _log.debug("Sent UDP packet " + packet);
                        long throttleTime = afterBW - acquireTime;
                        if (throttleTime > 10)
                            _context.statManager().addRateData("udp.sendBWThrottleTime", throttleTime, acquireTime - packet.getBegin());
                        if (packet.getMarkedType() == 1)
                            _context.statManager().addRateData("udp.sendACKTime", throttleTime);
                        _context.statManager().addRateData("udp.pushTime", packet.getLifetime());
                        _context.statManager().addRateData("udp.sendPacketSize", size);
                    } catch (IOException ioe) {
                        String ipaddress = packet.getPacket().getAddress().toString().replace("/", "");
                        if (_log.shouldWarn())
//                            _log.warn("Error sending to " + packet.getPacket().getAddress(), ioe);
                            _log.warn("Error sending to " + ipaddress + "\n* Error: " + ioe.getMessage());
                        _context.statManager().addRateData("udp.sendException", 1);
                        if (_socket.isClosed()) {
                            if (_keepRunning) {
                                _keepRunning = false;
                                _endpoint.fail();
                            }
                        }
                    }

                    // back to the cache
                    packet.release();
                }
            }
            if (_log.shouldWarn())
                _log.warn("Stop sending on " + _endpoint);
            _outboundQueue.clear();
        }

        /** @return next packet in queue. Will discard any packet older than MAX_HEAD_LIFETIME */
        private UDPPacket getNextPacket() {
            UDPPacket packet = null;
            while ( (_keepRunning) && (packet == null || packet.getLifetime() > MAX_HEAD_LIFETIME) ) {
                if (packet != null) {
                    _context.statManager().addRateData("udp.sendQueueTrimmed", 1);
                    packet.release();
                }
                try {
                    packet = _outboundQueue.take();
                } catch (InterruptedException ie) {}
                if (packet != null && packet.getMessageType() == TYPE_POISON)
                    return null;
            }
            return packet;
        }

     /******
        public DatagramSocket updateListeningPort(DatagramSocket socket, int newPort) {
            _name = "UDPSend on " + newPort;
            DatagramSocket old = null;
            synchronized (Runner.this) {
                old = _socket;
                _socket = socket;
            }
            _socketChanged = true;
            return old;
        }
      *****/
    }
}
