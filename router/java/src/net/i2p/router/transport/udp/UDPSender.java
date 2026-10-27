package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.BlockingQueue;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
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
public class UDPSender {
    /** The router context. */
    private final RouterContext _context;
    /** The log for the sender. */
    private final Log _log;
    /** The socket to send the datagrams on. */
    private final DatagramSocket _socket;
    /** The thread name of the sender. */
    private String _name;
    /** The packets waiting to be sent. */
    private final BlockingQueue<UDPPacket> _outboundQueue;
    /** True while the sender should keep running. */
    private volatile boolean _keepRunning;
    /** The thread sending the packets. */
    private final Runner _runner;
    /** True in dummy mode, which sends nothing. */
    private final boolean _dummy;
    /** The listener owning the socket. */
    private final SocketListener _endpoint;

    /**
     * Poison packet message type used to signal shutdown to sender thread.
     */
    private static final int TYPE_POISON = 99999;

    /**
     * Minimum and maximum queue sizes depend on system performance.
     * Queue sizes are tuned to compete with NTCP bandwidth requests and to optimize CoDel behavior.
     */
    private static final int MIN_QUEUE_SIZE = 128;
    /** Most packets queued for sending. */
    private static final int MAX_QUEUE_SIZE = 1024;

    /** CoDel algorithm target delay in milliseconds and interval to control pacing. */
    public static final int CODEL_TARGET = 20;
    /**
     * CoDel interval in milliseconds between checks for a packet entering the queue.
     */
    public static final int CODEL_INTERVAL = 100;

    /**
     * Property name overriding the CoDel target delay.
     */
    public static final String PROP_CODEL_TARGET = "router.codelTarget";
    /**
     * Property name overriding the CoDel check interval.
     */
    public static final String PROP_CODEL_INTERVAL = "router.codelInterval";

    /** Time rate intervals for statistics collection. */
    static final long[] RATES = RateConstants.SHORT_TERM_RATES;

    /**
     * Returns whether detailed full statistics gathering is enabled.
     *
     * @return true if full stats are enabled, false otherwise
     */
    public boolean fullStats() {
        return _context.getBooleanProperty("stat.full");
    }

    /**
     * Create a UDPSender with the given context, socket, name, and endpoint listener.
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
        int qsize = (int) Math.max(MIN_QUEUE_SIZE, Math.min(MAX_QUEUE_SIZE, (maxMemory) / (64 * 1024 * 1024L)));

        _outboundQueue = new CoDelPriorityBlockingQueue<>(ctx, "UDP-Sender", qsize,
                ctx.getProperty(PROP_CODEL_TARGET, CODEL_TARGET),
                ctx.getProperty(PROP_CODEL_INTERVAL, CODEL_INTERVAL));
        _socket = socket;
        _runner = new Runner();
        _name = name;
        _endpoint = lsnr;

        _context.statManager().createRequiredRateStat("udp.pushTime", "Time taken for a UDP packet get pushed out", "Transport [UDP]", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRateStat("udp.sendQueueSize", "Number of packets queued on the UDP sender", "Transport [UDP]", RATES);
        _context.statManager().createRateStat("udp.sendQueueTrimmed", "Stale packets removed from queue", "Transport [UDP]", RATES);
        _context.statManager().createRequiredRateStat("udp.sendPacketSize", "Size of sent packets (bytes)", "Transport [UDP]", RATES);
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
        t.setPriority(Thread.MAX_PRIORITY);
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
        _outboundQueue.offer(poison); // NOSONAR false positive S899
        // Wait briefly for queue processing to complete
        for (int i = 1; i <= 5 && !_outboundQueue.isEmpty(); i++) {
            try {
                Thread.sleep(i * 10L);
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
            _log.info("Dropping large UDP packet " + psz + " bytes from " + packet);
            return;
        }

        if (psz > 0 && psz < SSU2Util.MIN_DATA_LEN && _log.shouldInfo())
            _log.info("Small UDP packet " + psz + " bytes from " + packet);

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
            _log.debug("UDP packet queued -> Lifetime: " + packet.getLifetime() + "ms * Size: " + psz + " bytes");
        }
    }

    /**
     * Internal runner class responsible for dequeuing and sending UDP packets.
     * This class runs on its own dedicated high-priority thread.
     */
    private class Runner implements Runnable {

        /** Writes packets to the socket. */
        private Runner() {}

        /**
         * Dequeue and send UDP packets until shutdown.
         */
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
                        // Registered at construction, never recorded: the depth of the
                        // queue we are draining was invisible on a router whose send path
                        // is the thing that stalls. Read per packet so the figure tracks
                        // the queue rather than sampling it on some other clock.
                        _context.statManager().addRateData("udp.sendQueueSize", _outboundQueue.size());
                    } catch (IOException ioe) {
                        String ipaddress = packet.getPacket().getAddress().toString().replace("/", "");
                        if (_log.shouldWarn()) {
                            _log.warn("Error sending to " + ipaddress + "\n* Error: " + ioe.getMessage());
                        }
                        _context.statManager().addRateData("udp.sendException", 1);
                        if (_socket.isClosed() && _keepRunning) {
                                _keepRunning = false;
                                _endpoint.fail();
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
            // getNextPacket() runs once per send-loop iteration, i.e. per outbound
            // packet, so read the CoDel target with a TTL rather than doing a
            // property lookup (config map miss, then System.getProperty) each time.
            int codelTarget = codelTarget();

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

    /** How long a cached PROP_CODEL_TARGET value stays valid. */
    private static final long CODEL_TARGET_TTL = 1000;

    /** Cached CoDel target, refreshed at most once per {@link #CODEL_TARGET_TTL}. */
    private volatile int _codelTarget = CODEL_TARGET;
    /** When the cache was last refreshed. */
    private volatile long _codelTargetRead;

    /**
     * The CoDel target delay in ms, from {@link #PROP_CODEL_TARGET}, read at
     * most once per second. An unparseable value logs once per read and falls
     * back to {@link #CODEL_TARGET}, as does an unset property.
     *
     * @return the CoDel target delay in ms, never null
     * @since 0.9.71+
     */
    private int codelTarget() {
        long now = _context.clock().now();
        if (now - _codelTargetRead < CODEL_TARGET_TTL) {return _codelTarget;}
        _codelTargetRead = now;
        String propValue = _context.getProperty(PROP_CODEL_TARGET);
        if (propValue == null) return _codelTarget;
        try {
            _codelTarget = Integer.parseInt(propValue);
        } catch (NumberFormatException nfe) {
            if (_log.shouldWarn()) {
                _log.warn("Invalid property value for " + PROP_CODEL_TARGET + ": " + propValue);
            }
        }
        return _codelTarget;
    }
}
