package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.BlockingQueue;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
//import net.i2p.router.util.CoDelBlockingQueue;
import net.i2p.router.util.CoDelPriorityBlockingQueue;
import net.i2p.stat.RateConstants;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Lowest level UDP packet sender that pushes packets from its queue ASAP.
 * <p>
 * Each UDPSender instance corresponds to a UDPEndpoint, owning its own
 * dedicated sending thread and queue. Packets to be sent are enqueued
 * by the PacketPusher.
 * <p>
 * This class utilizes a CoDel-based priority blocking queue to limit
 * packet queueing delay and manages bandwidth throttling for sending.
 * <p>
 * Supports graceful startup and shutdown signaling using a poison packet.
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

    /**
     * Poison packet message type used to signal shutdown to sender thread.
     */
    private static final int TYPE_POISON = 99999;

    /**
     * Minimum and maximum queue sizes depend on system performance.
     * Queue sizes are tuned to compete with NTCP bandwidth requests and to optimize CoDel behavior.
     */
    private static final int MIN_QUEUE_SIZE = SystemVersion.isSlow() ? 128 : 256;
    private static final int MAX_QUEUE_SIZE = SystemVersion.isSlow() ? 512 : 1024;

    /**
     * CoDel algorithm target delay in milliseconds and interval to control pacing.
     * Defaults are 20ms target and 500ms interval, can be overridden via properties.
     */
    private static final int CODEL_TARGET = 10;
    private static final int CODEL_INTERVAL = 100;

    public static final String PROP_CODEL_TARGET = "router.codelTarget";
    public static final String PROP_CODEL_INTERVAL = "router.codelInterval";

    /** Time rate intervals for statistics collection. */
    static final long[] RATES = RateConstants.STANDARD_RATES;

    /**
     * Returns whether detailed full statistics gathering is enabled.
     *
     * @return true if full stats are enabled, false otherwise
     */
    public boolean fullStats() {
        return _context.getBooleanProperty("stat.full");
    }

    /**
     * Constructs a UDPSender with the given context, socket, name, and endpoint listener.
     * Initializes the CoDel priority queue and all relevant rate statistics trackers.
     *
     * @param ctx the router context
     * @param socket the UDP datagram socket to send packets on
     * @param name thread name identifier for this sender
     * @param lsnr the associated socket listener endpoint
     */
    public UDPSender(RouterContext ctx, DatagramSocket socket, String name, SocketListener lsnr) {
        _context = ctx;
        _dummy = false; // ctx.commSystem().isDummy();
        _log = ctx.logManager().getLog(UDPSender.class);

        long maxMemory = SystemVersion.getMaxMemory();
        int qsize = (int) Math.max(MIN_QUEUE_SIZE, Math.min(MAX_QUEUE_SIZE, (maxMemory * 4) / (1024 * 1024)));

        _outboundQueue = new CoDelPriorityBlockingQueue<>(ctx, "UDP-Sender", qsize,
                ctx.getProperty(PROP_CODEL_TARGET, CODEL_TARGET),
                ctx.getProperty(PROP_CODEL_INTERVAL, CODEL_INTERVAL));
        _socket = socket;
        _runner = new Runner();
        _name = name;
        _endpoint = lsnr;

        _context.statManager().createRateStat("udp.pushTime", "Time taken for a UDP packet get pushed out", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.sendQueueSize", "Number of packets queued on the UDP sender", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.sendQueueFailed", "Failed to add a new packet to the queue", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.sendQueueTrimmed", "Stale packets removed from queue", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.sendPacketSize", "Size of sent packets (bytes)", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.sendBWThrottleTime", "How long send is blocked by bandwidth throttle", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.sendACKTime", "How long an ACK packet is blocked for", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.sendFailsafe", "Time bandwidth limiter is stuck", "Transport [UDP]", RATES);

        if (fullStats())
            _context.statManager().createRequiredRateStat("udp.sendException", "Send fails (Windows exception?)", "Transport [UDP]", RATES);
        else
            _context.statManager().createRequiredRateStat("udp.sendException", "Send fails (Windows exception?)", "Transport", RATES);
    }

    /**
     * Starts the UDP sender thread.
     * <p>
     * This method cannot be called more than once as the socket is final.
     */
    public synchronized void startup() {
        if (_log.shouldDebug()) {
            _log.debug("Starting the runner: " + _name);
        }
        _keepRunning = true;
        I2PThread t = new I2PThread(_runner, _name, true);
        t.start();
    }

    /**
     * Gracefully shuts down the UDP sender thread.
     * <p>
     * Clears the outbound queue and signals the runner thread to stop by
     * enqueueing a poison packet. Waits briefly for the queue to drain.
     */
    public synchronized void shutdown() {
        if (!_keepRunning) {
            return;
        }
        _keepRunning = false;
        _outboundQueue.clear();
        UDPPacket poison = UDPPacket.acquire(_context, false);
        poison.setMessageType(TYPE_POISON);
        _outboundQueue.offer(poison);
        // Wait briefly for queue processing to complete
        for (int i = 1; i <= 5 && !_outboundQueue.isEmpty(); i++) {
            try {
                Thread.sleep(i * 10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        _outboundQueue.clear();
    }

    /**
     * Clears the outbound packet queue.
     * <p>
     * Typically used in preparation for sending destroy notifications.
     *
     * @since 0.9.2
     */
    public void clear() {
        _outboundQueue.clear();
    }

    /**
     * Adds a packet to the queue, blocking if necessary until space is available.
     * <p>
     * This method is deprecated; use {@link #add(UDPPacket)} instead.
     *
     * @param packet the UDP packet to add
     * @param blockTime ignored parameter
     * @deprecated use {@link #add(UDPPacket)} instead
     */
    @Deprecated
    public void add(UDPPacket packet, int blockTime) {
        add(packet);
    }

    /**
     * Adds a UDP packet to the send queue.
     * <p>
     * This operation blocks if the queue is full, which may back up the PacketPusher thread.
     * Packets that are too large or too small are dropped or warned respectively.
     * Bandwidth requests for the packet are initiated before queueing.
     *
     * @param packet the UDP packet to enqueue for sending
     */
    public void add(UDPPacket packet) {
        if (packet == null || !_keepRunning) return;

        int psz = packet.getPacket().getLength();

        // Check size against max MTU minus headers (assumed IPv4)
        if (psz > PeerState2.MAX_MTU - 28) {
            _log.error("Dropping large UDP packet " + psz + " bytes from " + packet, new Exception());
            return;
        }

        if (psz > 0 && psz < SSU2Util.MIN_DATA_LEN && _log.shouldWarn())
            _log.warn("Small UDP packet " + psz + " bytes from " + packet, new Exception());

        if (_dummy) {
            // Testing mode: immediately release the packet back to cache
            packet.release();
            return;
        }

        // Request outbound bandwidth for the packet before queueing
        packet.requestOutboundBandwidth();

        try {
            _outboundQueue.put(packet);
        } catch (InterruptedException ie) {
            // On interrupt, release the packet back to cache and restore interrupt status
            packet.release();
            Thread.currentThread().interrupt();
            return;
        }

        if (_log.shouldDebug()) {
            _log.debug("UDP packet queued -> Lifetime: " + packet.getLifetime() + "ms; Size: " + psz + " bytes");
        }
    }

    /**
     * Internal runner class responsible for dequeuing and sending UDP packets.
     * This class runs on its own dedicated high-priority thread.
     */
    private class Runner implements Runnable {

        @Override
        public void run() {
            if (_log.shouldDebug()) {
                _log.debug("Running the UDP sender...");
            }
            while (_keepRunning) {
                UDPPacket packet = getNextPacket();
                if (packet != null) {
                    if (_log.shouldDebug()) {
                        _log.debug("Attempting to send UDP packet to known peer at " + packet);
                    }
                    int size = packet.getPacket().getLength();
                    long acquireTime = _context.clock().now();

                    if (size > 0) {
                        FIFOBandwidthLimiter.Request req = packet.getBandwidthRequest();
                        if (req != null) {
                            // Failsafe: wait briefly for bandwidth allocation but avoid indefinite blocking
                            int waitCount = 0;
                            while (req.getPendingRequested() > 0 && waitCount++ < 5) {
                                req.waitForNextAllocation();
                            }
                            if (waitCount >= 5) {
                                req.abort();
                                _context.statManager().addRateData("udp.sendFailsafe", 1);
                            }
                        }
                    }

                    long afterBW = _context.clock().now();
                    try {
                        DatagramPacket dp = packet.getPacket();
                        _socket.send(dp);
                        if (_log.shouldDebug()) {
                            _log.debug("Sent UDP packet to " + packet);
                        }
                        long throttleTime = afterBW - acquireTime;
                        if (throttleTime > 10) {
                            _context.statManager().addRateData("udp.sendBWThrottleTime", throttleTime, acquireTime - packet.getBegin());
                        }
                        if (packet.getMarkedType() == 1) {
                            _context.statManager().addRateData("udp.sendACKTime", throttleTime);
                        }
                        _context.statManager().addRateData("udp.pushTime", packet.getLifetime());
                        _context.statManager().addRateData("udp.sendPacketSize", size);
                    } catch (IOException ioe) {
                        String ipaddress = packet.getPacket().getAddress().toString().replace("/", "");
                        if (_log.shouldWarn()) {
                            _log.warn("Error sending to " + ipaddress + "\n* Error: " + ioe.getMessage());
                        }
                        _context.statManager().addRateData("udp.sendException", 1);
                        if (_socket.isClosed()) {
                            if (_keepRunning) {
                                _keepRunning = false;
                                _endpoint.fail();
                            }
                        }
                    }

                    // Release packet back to cache after sending or on failure
                    packet.release();
                }
            }
            if (_log.shouldWarn()) {
                _log.warn("Stop sending on " + _endpoint);
            }
            _outboundQueue.clear();
        }

        /**
         * Retrieves the next UDP packet from the outbound queue for sending.
         * <p>
         * Packets whose lifetime exceed three times the CoDel target delay are dropped
         * to avoid sending stale packets. Queue blocking and interrupt handling is performed here.
         * <p>
         * Poison packets signal shutdown and cause this method to return null.
         *
         * @return the next valid UDPPacket to send, or null if shutdown is triggered by poison packet
         */
        private UDPPacket getNextPacket() {
            UDPPacket packet = null;
            int codelTarget = CODEL_TARGET;
            String propValue = _context.getProperty(PROP_CODEL_TARGET);
            if (propValue != null) {
                try {
                    codelTarget = Integer.parseInt(propValue);
                } catch (NumberFormatException nfe) {
                    if (_log.shouldWarn()) {
                        _log.warn("Invalid property value for " + PROP_CODEL_TARGET + ": " + propValue);
                    }
                }
            }

            while (_keepRunning) {
                try {
                    packet = _outboundQueue.take();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (!_keepRunning) {
                        return null;
                    }
                    continue;
                }

                if (packet == null) {
                    continue;
                }

                if (packet.getMessageType() == TYPE_POISON) {
                    return null;
                }

                if (packet.getLifetime() > codelTarget * 3) {
                    _context.statManager().addRateData("udp.sendQueueTrimmed", 1);
                    packet.release();
                    continue;
                }

                // Valid packet ready for sending
                break;
            }
            return packet;
        }
    }
}
