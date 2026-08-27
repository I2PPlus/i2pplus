package net.i2p.sam;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * The primary (mux) session for a SAMv3 client, sharing a single I2P
 * destination across multiple protocol subsessions (STREAM, DATAGRAM, RAW).
 *
 * Extends {@link SAMv3StreamSession} to inherit the I2PSession setup;
 * the streaming acceptor thread ({@link StreamAcceptor}) accepts I2P
 * connections and dispatches them to the correct subsession by protocol
 * and port, via {@link SAMv3StreamSession#queueSocket(I2PSocket)}.
 *
 * <p>The master session itself is not directly usable for data transfer —
 * all streaming, datagram, and raw operations are delegated to registered
 * subsessions. Calling {@link #connectAsync}, {@link #accept}, or
 * {@link #startForwardingIncoming} on this instance throws unconditionally.
 *
 * <p>Under heavy tracker traffic, the accept loop may see thousands of
 * connections per minute. Overflow logging is rate-limited to avoid
 * flooding the router log when subsession queues fill faster than
 * clients can consume.
 *
 * @since 0.9.25
 */
class MasterSession extends SAMv3StreamSession implements SAMDatagramReceiver, SAMRawReceiver,
                                                          SAMMessageSess, I2PSessionMuxedListener {
    private final SAMv3Handler handler;
    private final SAMv3DatagramServer dgs;
    private final Map<String, SAMMessageSess> sessions;
    private final StreamAcceptor streamAcceptor;
    private static final String[] INVALID_OPTS = { "PORT", "HOST", "FROM_PORT", "TO_PORT",
                                                   "PROTOCOL", "LISTEN_PORT", "LISTEN_PROTOCOL" };
    /** Log overflow warnings at most once per 5 seconds to avoid flooding */
    private static final long OVERFLOW_LOG_INTERVAL_MS = 5000;
    private volatile long _lastOverflowLog;
    private volatile int _overflowCount;

    /**
     * Build a Session according to information
     * registered with the given nickname.
     *
     * Caller MUST call start().
     *
     * @param nick nickname of the session
     * @throws IOException
     * @throws DataFormatException
     */
    public MasterSession(String nick, SAMv3DatagramServer dgServer, SAMv3Handler handler, Properties props)
            throws IOException, DataFormatException, SAMException {
        super(nick);
        for (int i = 0; i < INVALID_OPTS.length; i++) {
            String p = INVALID_OPTS[i];
            if (props.containsKey(p))
                throw new SAMException("Illegal option " + p + " specificied in MASTER session");
        }
        dgs = dgServer;
        sessions = new ConcurrentHashMap<>(4);
        this.handler = handler;
        I2PSession isess = socketMgr.getSession();
        // if we get a RAW session added with 0/0, it will replace this,
        // and we won't add this back if removed.
        isess.addMuxedSessionListener(this, I2PSession.PROTO_ANY, I2PSession.PORT_ANY);
        streamAcceptor = new StreamAcceptor();
    }

    /**
     * Start the stream acceptor thread. Must be called after construction.
     *
     * @since 0.9.25
     */
    @Override
    public void start() {
        Thread t = new I2PAppThread(streamAcceptor, "SAM-MstrAcc");
        t.start();
    }

    /**
     * Add a protocol subsession (STREAM, DATAGRAM, or RAW) to this
     * master session. The subsession gets its own protocol/port binding
     * and is registered in the global sessions database so incoming
     * connections can be dispatched to it.
     *
     * <p>Sessions are mutually exclusive on (protocol, port) — two
     * subsessions may not listen on the same protocol and port.
     *
     * @param nick unique nickname for this subsession
     * @param style protocol style: "STREAM", "DATAGRAM", or "RAW"
     * @param props session properties (PORT required for DATAGRAM/RAW;
     *              LISTEN_PORT, LISTEN_PROTOCOL optional)
     * @return null on success, or a descriptive error message
     */
    public synchronized String add(String nick, String style, Properties props) {
        if (props.containsKey("DESTINATION"))
            return "SESSION ADD may not contain DESTINATION";
        SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
        if (rec != null || sessions.containsKey(nick))
            return "Duplicate ID " + nick;
        int listenPort = I2PSession.PORT_ANY;
        // codeql[java/toctou-race-condition] props is a per-call argument; sSessionsHash access is synchronized in SessionsDB
        String slp = (String) props.remove("LISTEN_PORT");
                if (slp == null)
                    slp = props.getProperty("FROM_PORT");
        if (slp != null) {
            try {
                listenPort = Integer.parseInt(slp);
                if (listenPort < 0 || listenPort > 65535)
                    return "Invalid LISTEN_PORT " + slp;
                // TODO enforce streaming listen port must be 0 or from port
            } catch (NumberFormatException nfe) {
                return "Invalid LISTEN_PORT " + slp;
            }
        }
        int listenProtocol;
        SAMMessageSess sess;
        SAMv3Handler subhandler;
        try {
            I2PSession isess = socketMgr.getSession();
            subhandler = new SAMv3Handler(handler.getClientSocket(), handler.verMajor,
                                          handler.verMinor, handler.getBridge());
            if (style.equals("RAW")) {
                // codeql[java/toctou-race-condition] props is a per-call argument; no shared-state check-then-act
                if (!props.containsKey("PORT"))
                    return "RAW subsession must specify PORT";
                listenProtocol = I2PSession.PROTO_DATAGRAM_RAW;
                // codeql[java/toctou-race-condition] props is a per-call argument; sSessionsHash access is synchronized in SessionsDB
                String spr = (String) props.remove("LISTEN_PROTOCOL");
                            if (spr == null)
                                spr = props.getProperty("PROTOCOL");
                if (spr != null) {
                    try {
                        listenProtocol = Integer.parseInt(spr);
                        // RAW can't listen on streaming protocol
                        if (listenProtocol < 0 || listenProtocol > 255 ||
                            listenProtocol == I2PSession.PROTO_STREAMING)
                            return "Bad RAW LISTEN_PPROTOCOL " + spr;
                    } catch (NumberFormatException nfe) {
                        return "Bad LISTEN_PROTOCOL " + spr;
                    }
                }
                SAMv3RawSession ssess = new SAMv3RawSession(nick, props, handler, isess, listenProtocol, listenPort, dgs);
                subhandler.setSession(ssess);
                sess = ssess;
            } else if (style.equals("DATAGRAM")) {
                // codeql[java/toctou-race-condition] props is a per-call argument; no shared-state check-then-act
                if (!props.containsKey("PORT"))
                    return "DATAGRAM subsession must specify PORT";
                listenProtocol = I2PSession.PROTO_DATAGRAM;
                SAMv3DatagramSession ssess = new SAMv3DatagramSession(nick, props, handler, isess, listenPort, dgs);
                subhandler.setSession(ssess);
                sess = ssess;
            } else if (style.equals("STREAM")) {
                listenProtocol = I2PSession.PROTO_STREAMING;
                // FIXME need something that hangs off an existing dest
                SAMv3StreamSession ssess = new SAMv3StreamSession(nick, props, handler, socketMgr, listenPort);
                subhandler.setSession(ssess);
                sess = ssess;
            } else {
                return "Unrecognized SESSION STYLE " + style;
            }
        } catch (IOException e) {
            return e.toString();
        } catch (DataFormatException e) {
            return e.toString();
        } catch (SAMException e) {
            return e.toString();
        } catch (I2PSessionException e) {
            return e.toString();
        }

        for (SAMMessageSess s : sessions.values()) {
            if (listenProtocol == s.getListenProtocol() &&
                listenPort == s.getListenPort())
                return "Duplicate protocol " + listenProtocol + " and port " + listenPort;
        }

        rec = new SessionRecord(getDestination().toBase64(), props, subhandler);
        try {
            SAMv3Handler.sSessionsHash.putDupDestOK(nick, rec);
            sessions.put(nick, sess);
        } catch (SessionsDB.ExistingIdException e) {
            return "Duplicate ID " + nick;
        }
        if (_log.shouldWarn())
            _log.warn("added " + style + " proto " + listenProtocol + " port " + listenPort);

        sess.start();
        // all ok
        return null;
    }

    /**
     * Remove a protocol subsession by nickname. The session is closed,
     * removed from the global sessions database, and its port binding
     * is released.
     *
     * @param nick nickname of the subsession to remove
     * @param props session properties (unused, may be null)
     * @return null on success, or a descriptive error message
     */
    public synchronized String remove(String nick, Properties props) {
        boolean ok;
        SAMMessageSess sess = sessions.remove(nick);
        if (sess != null) {
            ok = SAMv3Handler.sSessionsHash.del(nick);
            sess.close();
            // TODO if 0/0, add back this as listener?
            if (_log.shouldWarn())
                _log.warn("removed " + sess + " proto " + sess.getListenProtocol() + " port " + sess.getListenPort());
        } else {
            ok = false;
        }
        if (!ok)
            return "ID " + nick + " not found";
        // all ok
        return null;
    }

    /**
     * Throws {@link IOException} — the master session does not handle
     * datagram data directly; use a DATAGRAM subsession instead.
     *
     * @throws IOException always
     */
    public void receiveDatagramBytes(Destination sender, byte[] data, int proto,
                                     int fromPort, int toPort) throws IOException {
        throw new IOException("master session");
    }

    /** No-op — master delegates datagram handling to subsessions. */
    public void stopDatagramReceiving() { /* no-op */ }

    /**
     * Throws {@link IOException} — the master session does not handle
     * raw data directly; use a RAW subsession instead.
     *
     * @throws IOException always
     */
    public void receiveRawBytes(byte[] data, int proto, int fromPort, int toPort) throws IOException {
        throw new IOException("master session");
    }

    /**
     *  Does nothing.
     */
    public void stopRawReceiving() { /* no-op */ }


    /////// stream session overrides

    /** @throws DataFormatException always */
    @Override
    public void connectAsync(SAMv3Handler handler, String dest, Properties props) throws DataFormatException {
        throw new DataFormatException("master session");
    }

    /** @throws SAMException always */
    @Override
    public void accept(SAMv3Handler handler, boolean verbose) throws SAMException {
        throw new SAMException("master session");
    }

    /** @throws SAMException always */
    @Override
    public void startForwardingIncoming(Properties props, boolean sendPorts) throws SAMException {
        throw new SAMException("master session");
    }

    /** does nothing */
    @Override
    public void stopForwardingIncoming() { /* no-op */ }


    ///// SAMMessageSess interface

    @Override
    public int getListenProtocol() {
        return I2PSession.PROTO_ANY;
    }

    @Override
    public int getListenPort() {
        return I2PSession.PORT_ANY;
    }

    /**
     * Close the master session and all registered subsessions.
     * Stops the stream acceptor thread, removes all subsessions from
     * the global sessions database, and destroys the underlying
     * I2PSocketManager.
     */
    @Override
    public synchronized void close() {
        streamAcceptor.stopRunning();
        for (Map.Entry<String, SAMMessageSess> e : sessions.entrySet()) {
            SAMv3Handler.sSessionsHash.del(e.getKey());
            e.getValue().close();
        }
        sessions.clear();
        super.close();
    }

    // I2PSessionMuxedImpl interface

        /**
         * Called when the I2P session is disconnected.
         *
         * @param session the disconnected session
         */
        public void disconnected(I2PSession session) {
            if (_log.shouldDebug())
                _log.debug("I2P session disconnected");
            close();
        }

        /**
         * Called when an I2P error occurs.
         *
         * @param session the session
         * @param message error description
         * @param error the exception, if any
         */
        public void errorOccurred(I2PSession session, String message,
                                  Throwable error) {
            if (_log.shouldDebug())
                _log.debug("I2P error: " + message, error);
            close();
        }

        /**
         * Called when a message is available (unmuxed).
         *
         * @param session the session
         * @param msgId the message ID
         * @param size the message size
         */
        public void messageAvailable(I2PSession session, int msgId, long size) {
            messageAvailable(session, msgId, size, I2PSession.PROTO_UNSPECIFIED,
                             I2PSession.PORT_UNSPECIFIED, I2PSession.PORT_UNSPECIFIED);
        }

        /**
         * Called when a muxed message is available.
         *
         * @param session the session
         * @param msgId the message ID
         * @param size the message size
         * @param proto the protocol number
         * @param fromPort the source port
         * @param toPort the destination port
         * @since 0.9.24
         */
        public void messageAvailable(I2PSession session, int msgId, long size,
                                     int proto, int fromPort, int toPort) {
            try {
                byte[] msg = session.receiveMessage(msgId);
                if (msg == null)
                    return;
                messageReceived(msg, proto, fromPort, toPort);
            } catch (I2PSessionException e) {
                _log.error("Error fetching I2P message", e);
                close();
            }
        }

        /**
         * Called when abuse is reported on the session.
         *
         * @param session the session
         * @param severity the abuse severity level
         */
        public void reportAbuse(I2PSession session, int severity) {
            _log.warn("Abuse reported (severity: " + severity + ")");
            close();
        }

    /**
     * Handle a received message. Logs a warning for unhandled messages.
     *
     * @param msg the message bytes
     * @param proto the protocol number
     * @param fromPort the source port
     * @param toPort the destination port
     */
    private void messageReceived(byte[] msg, int proto, int fromPort, int toPort) {
        if (_log.shouldWarn())
            _log.warn("Unhandled message received, length = " + msg.length +
                " protocol: " + proto + " from port: " + fromPort + " to port: " + toPort);
    }

    /**
     * Acceptor thread that waits for incoming I2P streaming connections
     * and dispatches them to the appropriate subsession by matching the
     * local port to the subsession's listen port. If no exact match is
     * found, falls back to a subsession listening on port 0 (any port).
     *
     * <p>Each accepted socket is offered to the subsession's queue via
     * {@link SAMv3StreamSession#queueSocket(I2PSocket)}. If the queue
     * is full (the PHP client is not keeping up), the socket is reset
     * and an overflow is logged (rate-limited to avoid flooding).
     *
     * <p>Runs for the lifetime of the master session; stopped by
     * {@link MasterSession#close()}.
     */
    private class StreamAcceptor implements Runnable {

        private volatile boolean stop;

        public StreamAcceptor() { /* no-op */ }

        public void stopRunning() {
            stop = true;
        }

        public void run() {
            if (_log.shouldWarn())
                _log.warn("Stream acceptor started");
            final I2PServerSocket i2pss = socketMgr.getServerSocket();
            while (!stop) {
                // wait and accept a connection from I2P side
                I2PSocket i2ps;
                try {
                    i2ps = i2pss.accept();
                    if (i2ps == null)  // never null as of 0.9.17
                        continue;
                } catch (SocketTimeoutException ste) {
                    continue;
                } catch (ConnectException ce) {
                    if (_log.shouldWarn())
                        _log.warn("Error accepting connection -> " + ce.getMessage());
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                } catch (I2PException ipe) {
                    if (_log.shouldWarn())
                        _log.warn("Error accepting connection -> " + ipe.getMessage());
                    break;
                }
                int port = i2ps.getLocalPort();
                SAMMessageSess foundSess = null;
                Collection<SAMMessageSess> all = sessions.values();
                for (Iterator<SAMMessageSess> iter = all.iterator(); iter.hasNext(); ) {
                    SAMMessageSess sess = iter.next();
                    if (sess.getListenProtocol() != I2PSession.PROTO_STREAMING) {
                        // remove as we may be going around again below
                        iter.remove();
                        continue;
                    }
                    if (sess.getListenPort() == port) {
                        foundSess = sess;
                        break;
                    }
                }
                // We never send streaming out as a raw packet to a default listener,
                // and we don't allow raw to listen on streaming protocol,
                // so we don't have to look for a default protocol,
                // but we do have to look for a default port listener.
                if (foundSess == null) {
                    for (SAMMessageSess sess : all) {
                        if (sess.getListenPort() == 0) {
                            foundSess = sess;
                            break;
                        }
                    }
                }
                if (foundSess != null) {
                    SAMv3StreamSession ssess = (SAMv3StreamSession) foundSess;
                    boolean ok = ssess.queueSocket(i2ps);
                    if (!ok) {
                        _overflowCount++;
                        long now = System.currentTimeMillis();
                        if (now - _lastOverflowLog > OVERFLOW_LOG_INTERVAL_MS) {
                            _lastOverflowLog = now;
                            String peer = "unknown";
                            try { peer = i2ps.getPeerDestination().calculateHash().toBase32().substring(0, 8); } catch (Exception e) { /* ignore */ }
                            if (_log.shouldWarn()) {
                            _log.warn("Rejected incoming connection from " + peer +
                                      "\n* Accept queue overflow for STREAM session \"" + ssess.getNick() +
                                      "\" (Queue " + ssess.getAcceptQueueSize() + " full, total rejected: " + _overflowCount + ")");
                            }
                        }
                        try { i2ps.reset(); } catch (IOException ioe) { /* ignored */ }
                    }
                } else {
                    if (_log.shouldWarn())
                        _log.warn("No subsession found for incoming streaming connection on port " + port);
                }
            }
            if (_log.shouldWarn())
                _log.warn("Stream acceptor stopped");
        }
    }
}
