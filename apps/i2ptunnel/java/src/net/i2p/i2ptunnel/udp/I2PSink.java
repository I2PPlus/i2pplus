package net.i2p.i2ptunnel.udp;

import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.datagram.I2PDatagramMaker;
import net.i2p.data.Destination;

/**
 * I2P sink implementation that sends datagrams to a fixed destination
 * specified in the constructor.
 *
 * <p>Supports both repliable and raw datagram modes. When not in raw mode,
 * data is wrapped with {@link I2PDatagramMaker} before sending.</p>
 *
 * @author welterde
 */
public class I2PSink implements Sink {

    protected final boolean raw;
    protected final I2PSession sess;
    protected final Destination dest;
    protected final I2PDatagramMaker maker;
    /**
     *  @since 0.9.53
     */
    protected final int toPort;

    /**
     * Creates a repliable (non-raw) sink for the given session and destination.
     *
     * @param sess the I2P session to send through
     * @param dest the fixed destination to send to
     */
    public I2PSink(I2PSession sess, Destination dest) {
        this(sess, dest, false);
    }

    /**
     * Creates a sink for the given session and destination.
     *
     * @param sess the I2P session to send through
     * @param dest the fixed destination to send to
     * @param raw true for raw datagrams, false for repliable
     */
    public I2PSink(I2PSession sess, Destination dest, boolean raw) {
        this(sess, dest, raw, I2PSession.PORT_UNSPECIFIED);
    }

    /**
     * Creates a sink for the given session, destination, and port.
     *
     * @param sess the I2P session to send through
     * @param dest the fixed destination to send to
     * @param raw true for raw datagrams, false for repliable
     * @param toPort I2CP destination port, 0-65535
     * @since 0.9.53
     */
    public I2PSink(I2PSession sess, Destination dest, boolean raw, int toPort) {
        this.sess = sess;
        this.dest = dest;
        this.raw = raw;
        this.toPort = toPort;

        // create maker
        if (raw) {
            this.maker = null;
        } else {
            this.maker = new I2PDatagramMaker();
            this.maker.setI2PDatagramMaker(this.sess);
        }
    }

    /**
     * Sends data to the fixed destination configured in the constructor.
     *
     * @param src ignored
     * @param fromPort I2CP source port, 0-65535
     * @param ign_toPort ignored
     * @param data the data to send
     * @since 0.9.53 added fromPort and toPort parameters, breaking change, sorry
     * @throws RuntimeException if the session is closed
     */
    @Override
    public synchronized void send(Destination src, int fromPort, int ign_toPort, byte[] data) {
        // create payload
        byte[] payload;
        if (!this.raw) {
            synchronized(this.maker) {
                payload = this.maker.makeI2PDatagram(data);
            }
        } else {
            payload = data;
        }

        // send message
        try {
            this.sess.sendMessage(this.dest, payload,
                                  (this.raw ? I2PSession.PROTO_DATAGRAM_RAW : I2PSession.PROTO_DATAGRAM),
                                  fromPort, toPort);
        } catch (I2PSessionException ise) {
            throw new RuntimeException("failed to send data", ise);
        }
    }
}
