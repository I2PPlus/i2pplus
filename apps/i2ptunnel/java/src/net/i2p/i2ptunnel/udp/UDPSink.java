package net.i2p.i2ptunnel.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import net.i2p.data.Destination;

/**
 * UDP sink implementation for streaming data.
 */
public class UDPSink implements Sink {

    /** The socket datagrams are written to. */
    protected final DatagramSocket sock;
    /** The address datagrams are sent to. */
    protected final InetAddress remoteHost;
    /** The port datagrams are sent to. */
    protected final int remotePort;

    /**
     *  Opens a DatagramSocket and sends datagrams to the given host and port.
     *
     *  @param host where to send
     *  @param port where to send
     *  @throws IllegalArgumentException on DatagramSocket IOException
     */
    public UDPSink(InetAddress host, int port) {
        try {
            this.sock = new DatagramSocket();
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to open udp-socket", e);
        }
        this.remoteHost = host;
        this.remotePort = port;
    }


    /**
     *  Sends datagrams through the given socket to the specified host and port.
     *
     *  @param socket the socket to send through, owned by the caller
     *  @param host where to send
     *  @param port where to send
     *  @since 0.9.53
     */
    public UDPSink(DatagramSocket socket, InetAddress host, int port) {
        sock = socket;
        this.remoteHost = host;
        this.remotePort = port;
    }

    /**
     *  Sends the data to the configured remote host and port.
     *
     *  @param src unused; a datagram carries no source address
     *  @param fromPort unused; the local port is fixed at construction
     *  @param toPort unused; the remote port is fixed at construction
     *  @throws RuntimeException on DatagramSocket IOException
     *  @since 0.9.53 added fromPort and toPort parameters, breaking change, sorry
     */
    public void send(Destination src, int fromPort, int toPort, byte[] data) {
        // if data.length > this.sock.getSendBufferSize() ...

        DatagramPacket packet = new DatagramPacket(data, data.length, this.remoteHost, this.remotePort);

        try {
            this.sock.send(packet);
        } catch (IOException ioe) {
            throw new RuntimeException("failed to send data", ioe);
        }
    }

    /**
     *  Local port of the DatagramSocket we are sending from.
     *
     *  @return the local port of the DatagramSocket we are sending from
     *  @since 0.9.53
     */
    public int getPort() {
        return this.sock.getLocalPort();
    }

    /**
     *  Returns the underlying DatagramSocket for use by UDPSource constructor.
     *
     *  @return the DatagramSocket to hand to the matching UDPSource
     *  @since 0.9.53
     */
    public DatagramSocket getSocket() {
        return this.sock;
    }

    /**
     * Closes the underlying DatagramSocket.
     *
     * @since 0.9.53
     */
    public void stop() {
        this.sock.close();
    }
}
