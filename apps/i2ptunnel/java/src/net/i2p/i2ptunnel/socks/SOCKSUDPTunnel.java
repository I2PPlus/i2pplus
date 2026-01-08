package net.i2p.i2ptunnel.socks;

/**
 * UDP tunnel implementation for SOCKS protocol with multiple port support.
 * <p>
 * This class extends I2PTunnelUDPClientBase to provide UDP tunneling
 * capabilities through SOCKS protocol, supporting multiple bidirectional
 * ports on the UDP side. It manages SOCKSUDPWrapper instances
 * for port multiplexing and handles datagram routing between
 * I2P and local UDP endpoints.
 * <p>
 * Designed for applications requiring UDP communication through I2P,
 * with support for dynamic port addition and removal. The implementation
 * ensures proper reply routing and maintains port mappings for
 * session tracking.
 */

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.udpTunnel.I2PTunnelUDPClientBase;

/**
 * A Datagram Tunnel that can have multiple bidirectional ports on the UDP side.
 *
 * TX:
 *   (multiple SOCKSUDPPorts -&gt; ) I2PSink
 *
 * RX:
 *   (SOCKSUDPWrapper in multiple SOCKSUDPPorts &lt;- ) MultiSink &lt;- I2PSource
 *
 * The replies must be to the same I2CP toPort as the outbound fromPort.
 * If the server does not honor that, the replies will be dropped.
 *
 * The replies must be repliable. Raw datagrams are not supported, and would
 * require a unique source port for each target.
 *
 * Preliminary, untested, possibly incomplete.
 *
 * @author zzz modded from streamr/StreamrConsumer
 */
public class SOCKSUDPTunnel extends I2PTunnelUDPClientBase {
    private final Map<Integer, SOCKSUDPPort> ports;
    private final MultiSink<SOCKSUDPPort> demuxer;

    /**
     *  Set up a tunnel with no UDP side yet.
     *  Use add() for each port.
     */
    public SOCKSUDPTunnel(I2PTunnel tunnel) {
        super(null, tunnel, tunnel, tunnel);

        this.ports = new ConcurrentHashMap<Integer, SOCKSUDPPort>(1);
        this.demuxer = new MultiSink<SOCKSUDPPort>(ports);
        setSink(this.demuxer);
    }


    /**
     *  Adds a new UDP port to the tunnel for bidirectional SOCKS UDP communication.
     *
     *  @param host the local address to bind to
     *  @param port the local port to bind to, or 0 for any available port
     *  @return the actual port number the socket was bound to
     *  @since 0.9.53
     */
    public int add(InetAddress host, int port) {
        SOCKSUDPPort sup = new SOCKSUDPPort(host, port, ports);
        this.ports.put(Integer.valueOf(sup.getPort()), sup);
        sup.setSink(this);
        sup.start();
        return sup.getPort();
    }

    /**
     *  Removes a UDP port from the tunnel and stops its associated resources.
     *
     *  @param port the UDP port number to remove
     *  @since 0.9.53
     */
    public void remove(Integer port) {
        SOCKSUDPPort sup = this.ports.remove(port);
        if (sup != null)
            sup.stop();
    }

    /**
     *  Starts the tunnel and begins processing datagrams.
     *  Ports must be added after this call.
     *
     *  @since 0.9.53
     */
    @Override
    public final void startRunning() {
        super.startRunning();
        // demuxer start() doesn't do anything
        startall();
    }

    /**
     *  Closes the tunnel and all associated ports.
     *
     *  @param forced if true, force immediate close without graceful shutdown
     *  @return true if closed successfully
     *  @since 0.9.53
     */
    @Override
    public boolean close(boolean forced) {
        stopall();
        return super.close(forced);
    }

    /** you should really add() after startRunning() */
    private void startall() {
    }

    private void stopall() {
         for (SOCKSUDPPort sup : this.ports.values()) {
              sup.stop();
         }
         this.ports.clear();
    }
}
