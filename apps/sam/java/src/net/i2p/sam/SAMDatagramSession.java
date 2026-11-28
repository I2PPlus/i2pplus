package net.i2p.sam;
/**
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.util.Properties;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.datagram.Datagram2;
import net.i2p.client.datagram.Datagram3;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.client.datagram.I2PDatagramMaker;
import net.i2p.client.datagram.I2PInvalidDatagramException;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

/**
 * SAM DATAGRAM session class, for datagram version 1 (DG1 only), or, for a v3
 * handler, for DG1, DG2 or DG3.
 *
 */
class SAMDatagramSession extends SAMMessageSession {

    /**
     * Largest datagram payload accepted in either direction, in bytes.
     */
    public static final int DGRAM_SIZE_MAX = 31*1024;

    // FIXME make final after fixing SAMv3DatagramSession override
    /**
     * Receiver this session hands assembled incoming datagrams to.
     */
    protected SAMDatagramReceiver recv;
    /**
     * Makes the DG1 datagrams this session sends, null for DG2 or DG3, which
     * are made by {@link Datagram2} / {@link Datagram3} instead.
     */
    private final I2PDatagramMaker dgramMaker;
    /**
     * Dissects the DG1 datagrams this session receives, null for DG2 or DG3.
     */
    private final I2PDatagramDissector dgramDissector;
    /**
     * Datagram version this session sends and accepts: 1, 2 or 3. Anything
     * arriving on another protocol is dropped, so a DG2 session can never be
     * fed a DG1 datagram.
     */
    private final int version;

    /**
     * Create a new SAM DATAGRAM session.
     * A v1 or v2 handler sends DG1 only; a v3 handler may ask for DG1, DG2 or DG3.
     * Listens on ALL ports and protocols.
     *
     * @param dest Base64-encoded destination (private key)
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     * @param v datagram version 1/2/3
     * @throws IOException declared on this constructor, but not raised by the body
     * @throws DataFormatException likewise declared, but not raised by the body
     * @throws I2PSessionException if the I2P session cannot be created from dest
     * @throws IllegalArgumentException if v is not 1, 2 or 3
     */
    protected SAMDatagramSession(String dest, Properties props,
                              SAMDatagramReceiver recv, int v) throws IOException,
                              DataFormatException, I2PSessionException {
        super(dest, props);
        this.recv = recv;
        if (v == 1) {
            dgramMaker = new I2PDatagramMaker(getI2PSession());
            dgramDissector = new I2PDatagramDissector();
        } else if (v == 2 || v == 3) {
            dgramMaker = null;
            dgramDissector = null;
        } else {
            throw new IllegalArgumentException("Bad version: " + v);
        }
        version = v;
    }

    /**
     * Create a new SAM DATAGRAM session on an existing I2P session.
     * v3 only, DG 1/2/3.
     *
     * @param sess the already-established I2P session to run over, which this session does not own
     * @param props unused for now
     * @param listenProtocol I2PSession.PROTO_xxx
     * @param listenPort the local port the datagram tunnel is published from
     * @param recv the object handed each datagram that arrives on the session
     * @param v datagram version 1/2/3
     * @throws I2PSessionException not raised here, since the body only wires up the session
     * @throws IllegalArgumentException if v is not 1, 2 or 3
     * @since 0.9.25
     */
    protected SAMDatagramSession(I2PSession sess, Properties props, int listenProtocol, int listenPort,
                              SAMDatagramReceiver recv, int v) throws
                              I2PSessionException {
        super(sess, listenProtocol, listenPort);
        this.recv = recv;
        if (v == 1) {
            dgramMaker = new I2PDatagramMaker(getI2PSession());
            dgramDissector = new I2PDatagramDissector();
        } else if (v == 2 || v == 3) {
            dgramMaker = null;
            dgramDissector = null;
        } else {
            throw new IllegalArgumentException("Bad version: " + v);
        }
        version = v;
    }

    /**
     * Send bytes through a SAM DATAGRAM session.
     *
     * @param dest the .b32.i2p address to send to
     * @param data Bytes to be sent
     * @param proto ignored, will always use PROTO_DATAGRAM (17), PROTO_DATAGRAM2 (19),
     *              or PROTO_DATAGRAM3 (20)
     *
     * @return True if the data was sent, false otherwise
     * @throws DataFormatException on unknown / bad dest
     * @throws I2PSessionException on serious error, probably session closed
     */
    public boolean sendBytes(String dest, byte[] data, int proto,
                             int fromPort, int toPort) throws DataFormatException, I2PSessionException {
        if (data.length > DGRAM_SIZE_MAX)
            throw new DataFormatException("Datagram size exceeded (" + data.length + ")");
        return sendBytesThroughMessageSession(dest, makeDatagram(dest, data), datagramProtocol(),
                                              fromPort, toPort);
    }

    /**
     * Send bytes through a SAM DATAGRAM session.
     *
     * @param proto ignored, will always use PROTO_DATAGRAM (17), PROTO_DATAGRAM2 (19),
     *              or PROTO_DATAGRAM3 (20)
     * @since 0.9.25
     */
    public boolean sendBytes(String dest, byte[] data, int proto,
                             int fromPort, int toPort,
                             boolean sendLeaseSet, int sendTags,
                             int tagThreshold, int expiration)
                                 throws DataFormatException, I2PSessionException {
        if (data.length > DGRAM_SIZE_MAX)
            throw new DataFormatException("Datagram size exceeded (" + data.length + ")");
        return sendBytesThroughMessageSession(dest, makeDatagram(dest, data), datagramProtocol(),
                                              fromPort, toPort,
                                              sendLeaseSet, sendTags,tagThreshold, expiration);
    }

    /**
     * Wraps the payload in the datagram format this session's version was
     * created with: a repliable DG1 datagram, a repliable DG2 datagram, or a
     * non-repliable DG3 datagram.
     *
     * @param dest the destination the payload is going to, needed as a full
     *             destination for DG2, which addresses the recipient by its hash
     * @param data the payload to wrap
     * @return the datagram to hand to I2CP, never null
     * @throws DataFormatException on unknown / bad dest, or if DG2 was asked for
     *                             a destination that is not a full destination
     */
    private byte[] makeDatagram(String dest, byte[] data) throws DataFormatException {
        switch (version) {
            case 1:
                synchronized (dgramMaker) {
                    return dgramMaker.makeI2PDatagram(data);
                }
            case 2:
                return Datagram2.make(I2PAppContext.getGlobalContext(), getI2PSession(), data,
                                      new Destination(dest).calculateHash());
            default:
                return Datagram3.make(I2PAppContext.getGlobalContext(), getI2PSession(), data);
        }
    }

    /**
     * The I2CP protocol matching this session's datagram version, which is the
     * protocol its listener was registered for.
     *
     * @return PROTO_DATAGRAM (17), PROTO_DATAGRAM2 (19) or PROTO_DATAGRAM3 (20)
     */
    private int datagramProtocol() {
        switch (version) {
            case 1:
                return I2PSession.PROTO_DATAGRAM;
            case 2:
                return I2PSession.PROTO_DATAGRAM2;
            default:
                return I2PSession.PROTO_DATAGRAM3;
        }
    }

    /**
     * Handle a received datagram message from I2P, unwrapping it with the
     * format this session's version was created with, and dropping anything
     * that arrived on another protocol.
     *
     * @param msg the raw I2P datagram message
     * @param proto the I2CP protocol
     * @param fromPort the I2CP from port
     * @param toPort the I2CP to port
     */
    protected void messageReceived(byte[] msg, int proto, int fromPort, int toPort) {
        byte[] payload;
        Destination sender;
        Hash senderHash;
        try {
            if (version == 1 && proto == I2PSession.PROTO_DATAGRAM) {
                synchronized (dgramDissector) {
                    dgramDissector.loadI2PDatagram(msg);
                    sender = dgramDissector.getSender();
                    payload = dgramDissector.extractPayload();
                }
                senderHash = null;
            } else if (version == 2 && proto == I2PSession.PROTO_DATAGRAM2) {
                Datagram2 dg = Datagram2.load(I2PAppContext.getGlobalContext(), getI2PSession(), msg);
                sender = dg.getSender();
                payload = dg.getPayload();
                senderHash = null;
            } else if (version == 3 && proto == I2PSession.PROTO_DATAGRAM3) {
                // DG3 is not repliable, the sender is only known by hash
                Datagram3 dg = Datagram3.load(I2PAppContext.getGlobalContext(), getI2PSession(), msg);
                sender = null;
                payload = dg.getPayload();
                senderHash = dg.getSender();
            } else {
                if (_log.shouldDebug()) {
                    _log.debug("Dropping mismatched datagram -> Datagram version: " + version +
                               " * Protocol: " + proto);
                }
                return;
            }
        } catch (DataFormatException e) {
            if (_log.shouldDebug()) {
                _log.debug("Dropping ill-formatted I2P repliable datagram", e);
            }
            return;
        } catch (I2PInvalidDatagramException e) {
            if (_log.shouldDebug()) {
                _log.debug("Dropping ill-signed I2P repliable datagram", e);
            }
            return;
        }

        try {
            if (sender != null) {
                // DG 1/2
                recv.receiveDatagramBytes(sender, payload, proto, fromPort, toPort);
            } else {
                // DG 3
                recv.receiveDatagramBytes(senderHash, payload, proto, fromPort, toPort);
            }
        } catch (IOException e) {
            _log.error("Error forwarding message to receiver", e);
            close();
        }
    }

    /**
     * Shut down the datagram session.
     */
    protected void shutDown() {
        recv.stopDatagramReceiving();
    }
}
