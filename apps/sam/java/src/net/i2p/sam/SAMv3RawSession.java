package net.i2p.sam;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Properties;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;

/**
 * SAM v3 raw session implementation.
 */
class SAMv3RawSession extends SAMRawSession implements Session, SAMRawReceiver {

    private final String nick;
    private final SAMv3Handler handler;
    private final SAMv3DatagramServer server;
    private final SocketAddress clientAddress;
    private final boolean _sendHeader;

    /**
     * Get the session nickname.
     *
     * @return the nickname this session was registered under
     */
    public String getNick() {
        return nick;
    }

    /**
     * Build a Raw Datagram Session according to information
     * registered with the given nickname
     *
     * Caller MUST call start().
     *
     * @param nick nickname of the session
     * @param dgServer the datagram server this session reports its senders to
     * @throws IOException if the nickname is not registered; the lookup throws InterruptedIOException
     * @throws DataFormatException declared on the superclass constructor, but not raised by the body
     * @throws I2PSessionException if the I2P session cannot be created from the recorded destination
     */
    public SAMv3RawSession(String nick, SAMv3DatagramServer dgServer)
            throws IOException, DataFormatException, I2PSessionException {
        super(
                getRec(nick).getDest(),
                getRec(nick).getProps(),
                null // to be replaced by this
                );
        this.nick = nick;
        this.recv = this; // replacement
        this.server = dgServer;
        SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
        this.handler = rec.getHandler();
        Properties props = rec.getProps();
        clientAddress = getSocketAddress(props, handler);
        _sendHeader = ((handler.verMajor == 3 && handler.verMinor >= 2) || handler.verMajor > 3)
                && Boolean.parseBoolean(props.getProperty("HEADER"));
    }

    /**
     * Look up the registered session record for the given nickname,
     * throwing if it has already disappeared.
     *
     * @return the registered session record
     * @throws InterruptedIOException if the nickname is not registered
     */
    private static SessionRecord getRec(String nick) throws InterruptedIOException {
        SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
        if (rec == null)
            throw new InterruptedIOException();
        return rec;
    }

    /**
     * Build a Raw Session on an existing i2p session
     * registered with the given nickname
     *
     * Caller MUST call start().
     *
     * @param nick nickname of the session
     * @param props the session properties, read for PORT and HOST
     * @param handler the SAM handler owning the session record
     * @param isess the already-running I2P session to wrap, not owned here
     * @param listenProtocol the I2CP protocol to bind, or I2PSession.PROTO_ANY for all
     * @param listenPort the local port to bind, or I2PSession.PORT_ANY to let the router choose
     * @param dgServer the datagram server this session reports its senders to
     * @throws IOException declared on this constructor, but not raised by the body
     * @throws DataFormatException likewise declared, but not raised by the body
     * @throws I2PSessionException if the shared session cannot be attached to
     * @since 0.9.25
     */
    public SAMv3RawSession(
            String nick,
            Properties props,
            SAMv3Handler handler,
            I2PSession isess,
            int listenProtocol,
            int listenPort,
            SAMv3DatagramServer dgServer)
            throws IOException, DataFormatException, I2PSessionException {
        super(isess, props, listenProtocol, listenPort, null); // to be replaced by this
        this.nick = nick;
        this.recv = this; // replacement
        this.server = dgServer;
        this.handler = handler;
        clientAddress = getSocketAddress(props, handler);
        _sendHeader = ((handler.verMajor == 3 && handler.verMinor >= 2) || handler.verMajor > 3)
                && Boolean.parseBoolean(props.getProperty("HEADER"));
    }

    /**
     * Get the socket address from session properties.
     *
     * @param props the session properties containing PORT and optionally HOST
     * @param handler the SAM handler for client IP fallback
     * @return the socket address, or null if PORT is not set
     * @since 0.9.25 moved from constructor
     */
    static SocketAddress getSocketAddress(Properties props, SAMv3Handler handler) {
        String portStr = props.getProperty("PORT");
        if (portStr == null) {
            return null;
        } else {
            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException nfe) {
                return null;
            }
            String host = props.getProperty("HOST");
            if (host == null) {
                host = handler.getClientIP();
            }
            return new InetSocketAddress(host, port);
        }
    }

    /**
     * Receive raw data from I2P and forward to the SAM client.
     *
     * @param data the raw data payload
     * @param proto the I2CP protocol
     * @param fromPort the I2CP from port
     * @param toPort the I2CP to port
     * @throws IOException if forwarding to the client fails
     */
    public void receiveRawBytes(byte[] data, int proto, int fromPort, int toPort) throws IOException {
        if (this.clientAddress == null) {
            this.handler.receiveRawBytes(data, proto, fromPort, toPort);
        } else {
            ByteBuffer msgBuf;
            if (_sendHeader) {
                StringBuilder buf = new StringBuilder(64);
                buf.append("PROTOCOL=")
                        .append(proto)
                        .append(" FROM_PORT=")
                        .append(fromPort)
                        .append(" TO_PORT=")
                        .append(toPort)
                        .append('\n');
                String msg = buf.toString();
                msgBuf = ByteBuffer.allocate(msg.length() + data.length);
                msgBuf.put(DataHelper.getASCII(msg));
            } else {
                msgBuf = ByteBuffer.allocate(data.length);
            }
            msgBuf.put(data);
            msgBuf.flip();
            this.server.send(this.clientAddress, msgBuf);
        }
    }

    /**
     * Stop receiving raw data.
     */
    public void stopRawReceiving() { /* no-op */ }
}
