package net.i2p.router.transport.udp;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Blocking thread to grab new packets off the outbound fragment
 * pool and toss them onto the outbound packet queues.
 *
 * This class runs a dedicated thread that continuously pulls batches of
 * UDP packets from the OutboundMessageFragments pool and sends them out
 * through appropriate UDPEndpoints based on the packet IP version.
 *
 * Thread safety is improved by using concurrent safe collections and
 * volatile flag visibility.
 *
 * Endpoint selection currently picks the first matching endpoint for IPv4 or IPv6.
 */
class PacketPusher implements Runnable {
    private final Log _log;
    private final OutboundMessageFragments _fragments;
    // Use thread-safe CopyOnWriteArrayList for safe concurrent iterations with minimal locking
    private final List<UDPEndpoint> _endpoints;
    private volatile boolean _alive;

    /**
     * Constructs a PacketPusher instance.
     *
     * @param ctx the router context used to get the logger
     * @param fragments the outbound message fragment pool to pull packets from
     * @param endpoints a thread-safe or effectively immutable list of UDPEndpoints
     */
    public PacketPusher(RouterContext ctx, OutboundMessageFragments fragments, List<UDPEndpoint> endpoints) {
        _log = ctx.logManager().getLog(PacketPusher.class);
        _fragments = fragments;

        // Defensive copy into a thread-safe list for safe concurrent use during sending
        _endpoints = (endpoints instanceof CopyOnWriteArrayList) ? endpoints : new CopyOnWriteArrayList<>(endpoints);
    }

    /**
     * Starts the packet pusher thread.
     * This method is synchronized to prevent concurrent startups/shutdowns.
     */
    public synchronized void startup() {
        if (_alive) {
            return;
        }
        _alive = true;
        I2PThread t = new I2PThread(this, "UDPPktPusher", true);
        t.start();
    }

    /**
     * Stops the packet pusher thread.
     * This method is synchronized to prevent concurrent shutdown/startup races.
     */
    public synchronized void shutdown() {
        _alive = false;
        // Interrupt the current thread running this Runnable to unblock blocking calls in getNextVolley()
        // This assumes that getNextVolley() reacts appropriately to Thread.interrupt()
        Thread.currentThread().interrupt();
    }

    /**
     * The main loop for the packet pusher thread.
     * Continuously pulls packets from the fragment pool and sends them out via endpoints.
     * Includes a blocking wait if no packets are currently available.
     */
    public void run() {
        while (_alive) {
            try {
                List<UDPPacket> packets = _fragments.getNextVolley();
                if (packets != null && !packets.isEmpty()) {
                    for (UDPPacket packet : packets) {
                        send(packet);
                    }
                } else {
                    // Sleep briefly or yield if no packets to reduce CPU usage (depends on getNextVolley blocking)
                    Thread.yield();
                }
            } catch (RuntimeException e) {
                _log.error("SSU Output Queue Error", e);
            }
        }
    }

    /**
     * Sends a single UDP packet directly out by selecting an appropriate UDPEndpoint.
     * This bypasses the outbound fragment pool's queue and only blocks if the endpoint queue is full.
     *
     * Endpoint selection tries to match the packet's IP version to an endpoint's IP version.
     * TODO: Improve to track peer-specific endpoint and ensure consistent sending.
     *
     * @param packet the non-null UDPPacket to send
     */
    public void send(UDPPacket packet) {
        boolean isIPv4 = packet.getPacket().getAddress().getAddress().length == 4;

        // Iterate over endpoints safely via CopyOnWriteArrayList avoiding concurrent modification issues
        for (UDPEndpoint ep : _endpoints) {
            if ((isIPv4 && ep.isIPv4()) || (!isIPv4 && ep.isIPv6())) {
                try {
                    // This will block if the endpoint's queue is full as per original design
                    ep.getSender().add(packet);
                    return;
                } catch (Exception e) {
                    _log.error("Error sending packet through endpoint: " + ep, e);
                    // Continue trying other endpoints if available
                }
            }
        }

        // No suitable endpoint found - log warning and release the packet resources
        if (_log.shouldWarn()) {
            _log.warn("No endpoint (socket) available to send out packet to " + packet);
        }
        packet.release();
    }
}
