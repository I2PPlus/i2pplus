package net.i2p.i2ptunnel.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import net.i2p.I2PAppContext;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * UDP source implementation for streaming data.
 */
public class UDPSource implements Source, Runnable {
    protected final DatagramSocket sock;
    protected Sink sink;
    protected final Thread thread;
    private final int port;
    /** Maximum UDP packet size. */
    public static final int MAX_SIZE = 15360;

    /**
     * Creates a source listening on the specified port.
     *
     * @param port the local UDP port to listen on
     * @throws RuntimeException if the DatagramSocket cannot be opened
     */
    public UDPSource(int port) {
        try {
            this.sock = new DatagramSocket(port);
        } catch (IOException e) {
            throw new RuntimeException("failed to listen...", e);
        }
        this.port = port;
        this.thread = new I2PAppThread(this);
    }

    /**
     * Creates a source using an existing socket (e.g., from UDPSink).
     *
     * @param sock the DatagramSocket to receive on
     */
    public UDPSource(DatagramSocket sock) {
        this.sock = sock;
        port = sock.getLocalPort();
        this.thread = new I2PAppThread(this);
    }

    /**
     *  Sets the sink for received UDP datagrams.
     *
     *  @param sink the sink to receive processed datagrams
     *  @since 0.9.53
     */
    @Override
    public void setSink(Sink sink) {
        this.sink = sink;
    }

    /**
     *  Starts the source thread to begin receiving UDP datagrams.
     *
     *  @since 0.9.53
     */
    public void start() {
        this.thread.start();
    }

    /**
     * Receives UDP datagrams in a loop and forwards them to the configured sink.
     */
    public void run() {
        byte[] buf = new byte[MAX_SIZE];
        DatagramPacket pack = new DatagramPacket(buf, buf.length);
        while(true) {
            try {
                this.sock.receive(pack);

                Sink s = this.sink;
                if (s == null)
                    break;

                byte[] nbuf = new byte[pack.getLength()];

                System.arraycopy(pack.getData(), 0, nbuf, 0, nbuf.length);

                s.send(null, port, 0, nbuf);
            } catch(Exception e) {
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
                if (log.shouldWarn())
                    log.warn("Error sending", e);
                break;
            }
        }
    }

    /**
     *  Local port of the DatagramSocket we are receiving on.
     *
     *  @return the local port of the DatagramSocket we are receiving on
     *  @since 0.9.53
     */
    public int getPort() {
        return port;
    }

    /**
     *  Stops the source thread and closes the socket.
     *
     *  @since 0.9.53
     */
    public void stop() {
        this.sock.close();
    }
}
