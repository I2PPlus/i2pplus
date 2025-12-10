package net.i2p.i2ptunnel.socks;

/**
 * Wrapper for adding SOCKS protocol headers to UDP datagrams.
 * <p>
 * This class implements Source and Sink interfaces to wrap outgoing
 * UDP packets with SOCKS headers according to RFC 1928. It maintains
 * a cache of SOCKS headers for different destinations and handles
 * the wrapping/unwrapping of datagrams for proper routing through
 * I2P network.
 * <p>
 * Used by SOCKS UDP tunnel implementations to ensure datagrams
 * conform to SOCKS protocol format, enabling proper delivery
 * to I2P destinations with correct addressing information.
 */

import java.util.Map;
import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocketAddress;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.Log;

/**
 * Put a SOCKS header on a datagram
 * Ref: RFC 1928
 *
 * @author zzz
 */
public class SOCKSUDPWrapper implements Source, Sink {
    private Sink sink;
    private final Map<I2PSocketAddress, SOCKSHeader> cache;

    public SOCKSUDPWrapper(Map<I2PSocketAddress, SOCKSHeader> cache) {
        this.cache = cache;
    }

    public void setSink(Sink sink) {
        this.sink = sink;
    }

    public void start() {}

    /**
     * Use the cached header, which should have the host string and port
     *
     *  May throw RuntimeException from underlying sink
     *  @since 0.9.53 added fromPort and toPort parameters
     *  @throws RuntimeException
     */
    public void send(Destination from, int fromPort, int toPort, byte[] data) {
        if (this.sink == null)
            return;
        if (from == null) {
            // TODO to handle raw replies, SOCKSUDPWrapper would have to use a unique
            // fromPort for every target or request, and we would lookup the
            // destination by toPort
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(SOCKSUDPWrapper.class);
            if (log.shouldWarn())
                log.warn("No support for raw datagrams, from port " + fromPort + " to port " + toPort);
            return;
        }

        SOCKSHeader h = cache.get(new I2PSocketAddress(from, fromPort));
        if (h == null) {
            // RFC 1928 says drop
            // h = new SOCKSHeader(from);
            return;
        }

        byte[] header = h.getBytes();
        byte wrapped[] = new byte[header.length + data.length];
        System.arraycopy(header, 0, wrapped, 0, header.length);
        System.arraycopy(data, 0, wrapped, header.length, data.length);
        this.sink.send(from, fromPort, toPort, wrapped);
    }
}
