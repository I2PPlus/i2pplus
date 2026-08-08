package net.i2p.i2ptunnel.udp;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.util.Log;

/**
 * I2P source implementation for streaming data.
 * Refactored in 0.9.53 to support I2CP protocols and ports
 */
public class I2PSource implements Source {

    /** The I2P session */
    protected final I2PSession sess;
    /** The sink to receive datagrams */
    protected Sink sink;
    /** UDP protocol (unencrypted or encrypted). */
    private final Protocol protocol;
    /** Local UDP port. */
    private final int port;
    /** Datagram dissector for parsing I2P UDP frames. */
    private final I2PDatagramDissector diss;
    private final Log log;

    /**
     *  Protocol enum for I2P source handling.
     *  @since 0.9.53
     */
    public enum Protocol {
        /** Repliable datagrams */
        REPLIABLE,
        /** Raw datagrams */
        RAW,
        /** Both repliable and raw datagrams */
        BOTH
    }

    /**
     * Creates a source handling both repliable and raw datagrams on all ports.
     *
     * @param sess the I2P session to listen on
     */
    public I2PSource(I2PSession sess) {
        this(sess, Protocol.BOTH);
    }

    /**
     * Creates a source listening on all I2CP ports for the specified protocol.
     *
     * @param sess the I2P session to listen on
     * @param protocol REPLIABLE, RAW, or BOTH
     * @since 0.9.53
     */
    public I2PSource(I2PSession sess, Protocol protocol) {
        this(sess, protocol, I2PSession.PORT_ANY);
    }

    /**
     * Creates a source listening on the specified port for the specified protocol.
     *
     * @param sess the I2P session to listen on
     * @param protocol REPLIABLE, RAW, or BOTH
     * @param port I2CP port, or {@link I2PSession#PORT_ANY} for all ports
     * @since 0.9.53
     */
    public I2PSource(I2PSession sess, Protocol protocol, int port) {
        this.sess = sess;
        this.protocol = protocol;
        this.port = port;
        diss = (protocol != Protocol.RAW) ? new I2PDatagramDissector() : null;
        log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
    }

    /**
     * Sets the sink for received I2P datagrams.
     *
     * @param sink the sink to receive datagrams
     * @since 0.9.53
     */
    @Override
    public void setSink(Sink sink) {
        this.sink = sink;
    }

    /**
     * Starts the source to begin receiving datagrams.
     *
     * @since 0.9.53
     */
    @Override
    public void start() {
        Listener l = new Listener();
        if (protocol != Protocol.RAW)
            sess.addMuxedSessionListener(l, I2PSession.PROTO_DATAGRAM, port);
        if (protocol != Protocol.REPLIABLE)
            sess.addMuxedSessionListener(l, I2PSession.PROTO_DATAGRAM_RAW, port);
    }

    /**
     * Listener for incoming I2P datagrams, dispatching to the configured sink.
     */
    protected class Listener implements I2PSessionMuxedListener {

        /**
         *  Always throws, since the muxed variant must be used.
         *
         *  @throws IllegalStateException always
         */
        public void messageAvailable(I2PSession sess, int id, long size) {
            throw new IllegalStateException("muxed");
        }

        /** @since 0.9.53 */
        public void messageAvailable(I2PSession session, int id, long size, int proto, int fromPort, int toPort) {
            if (log.shouldDebug())
                log.debug("Got " + size + " bytes, proto: " + proto + " from port: " + fromPort + " to port: " + toPort);
            try {
                byte[] msg = session.receiveMessage(id);
                if (proto == I2PSession.PROTO_DATAGRAM) {
                    diss.loadI2PDatagram(msg);
                    sink.send(diss.getSender(), fromPort, toPort, diss.getPayload());
                } else if (proto == I2PSession.PROTO_DATAGRAM_RAW) {
                    sink.send(null, fromPort, toPort, msg);
                } else {
                    if (log.shouldWarn())
                        log.warn("dropping message with unknown protocol " + proto);
                }
            } catch(Exception e) {
                if (log.shouldWarn())
                    log.warn("Error receiving datagram", e);
            }
        }

        @Override
        public void reportAbuse(I2PSession arg0, int arg1) {}

        public void disconnected(I2PSession arg0) {}

        public void errorOccurred(I2PSession arg0, String arg1, Throwable arg2) {
            log.error(arg1, arg2);
        }

    }
}
