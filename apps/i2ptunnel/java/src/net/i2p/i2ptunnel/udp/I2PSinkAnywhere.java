package net.i2p.i2ptunnel.udp;

import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.datagram.I2PDatagramMaker;
import net.i2p.data.Destination;

/**
 * I2P sink implementation that sends datagrams to any destination
 * specified in each {@link #send} call, rather than a fixed destination.
 *
 * <p>Supports both repliable and raw datagram modes. When not in raw mode,
 * data is wrapped with {@link I2PDatagramMaker} before sending.</p>
 */
public class I2PSinkAnywhere implements Sink {

    protected final boolean raw;
    protected final I2PSession sess;
    protected final I2PDatagramMaker maker;

    /**
     * Creates a repliable (non-raw) sink for the given session.
     *
     * @param sess the I2P session to send through
     */
    public I2PSinkAnywhere(I2PSession sess) {
        this(sess, false);
    }

    /**
     * Creates a sink for the given session.
     *
     * @param sess the I2P session to send through
     * @param raw true for raw datagrams, false for repliable
     */
    public I2PSinkAnywhere(I2PSession sess, boolean raw) {
        this.sess = sess;
        this.raw = raw;

        // create maker
        if (raw) {
            this.maker = null;
        } else {
            this.maker = new I2PDatagramMaker();
            this.maker.setI2PDatagramMaker(this.sess);
        }
    }

    /**
     * Sends data to the specified destination using default ports.
     *
     * @param to the destination to send to
     * @param data the data to send
     * @throws RuntimeException if the session is closed
     */
    public void send(Destination to, byte[] data) {
        send(to, I2PSession.PORT_UNSPECIFIED, I2PSession.PORT_UNSPECIFIED, data);
    }

    /**
     * Sends data to the specified destination using the given ports.
     *
     * @param to the destination to send to
     * @param fromPort I2CP source port, 0-65535
     * @param toPort I2CP destination port, 0-65535
     * @param data the data to send
     * @since 0.9.53
     * @throws RuntimeException if the session is closed
     */
    public synchronized void send(Destination to, int fromPort, int toPort, byte[] data) {
        // create payload
        byte[] payload;
        if(!this.raw) {
            synchronized(this.maker) {
                payload = this.maker.makeI2PDatagram(data);
            }
        } else {
            payload = data;
        }

        // send message
        try {
            this.sess.sendMessage(to, payload,
                                  (this.raw ? I2PSession.PROTO_DATAGRAM_RAW : I2PSession.PROTO_DATAGRAM),
                                  fromPort, toPort);
        } catch (I2PSessionException ise) {
            throw new RuntimeException("failed to send data", ise);
        }
    }
}
