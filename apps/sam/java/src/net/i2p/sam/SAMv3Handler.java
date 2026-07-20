package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.nio.channels.SocketChannel;
import java.util.Properties;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSessionException;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.PasswordManager;

/**
 * Class able to handle a SAM version 3 client connection.
 *
 * @author mkvore
 */

class SAMv3Handler extends SAMv1Handler
{

    private Session session;
    // TODO remove singleton, hang off SAMBridge like dgserver
    public static final SessionsDB sSessionsHash = new SessionsDB();
    volatile boolean stolenSocket;
    volatile boolean streamForwardingSocket;
    /** true once the SESSION CREATE has fully initialized; guards against race in execStreamMessage */
    private volatile boolean sessionReady;
    /** package-visible for {@link SAMHandlerPool} */
    final boolean sendPorts;
    private static final String AUTH_ERROR = "AUTH STATUS RESULT=I2P_ERROR";

    /**
     * Create a new SAM version 3 handler.  This constructor expects
     * that the SAM HELLO message has been still answered (and
     * stripped) from the socket input stream.
     *
     * @param s Socket attached to a SAM client
     * @param verMajor SAM major version to manage (should be 3)
     * @param verMinor SAM minor version to manage
     * @throws SAMException if the version is not supported
     * @throws IOException if an I/O error occurs
    */
    public SAMv3Handler(SocketChannel s, int verMajor, int verMinor, SAMBridge parent) throws SAMException, IOException {
        this(s, verMajor, verMinor, new Properties(), parent);
    }

    /**
     * Create a new SAM version 3 handler.  This constructor expects
     * that the SAM HELLO message has been still answered (and
     * stripped) from the socket input stream.
     *
     * @param s Socket attached to a SAM client
     * @param verMajor SAM major version to manage (should be 3)
     * @param verMinor SAM minor version to manage
     * @param i2cpProps properties to configure the I2CP connection (host, port, etc)
     * @throws SAMException if the version is not supported
     * @throws IOException if an I/O error occurs
     */

    public SAMv3Handler(SocketChannel s, int verMajor, int verMinor, Properties i2cpProps, SAMBridge parent) throws SAMException, IOException {
        super(s, verMajor, verMinor, i2cpProps, parent);
        sendPorts = (verMajor == 3 && verMinor >= 2) || verMajor > 3;
        if (_log.shouldDebug())
            _log.debug("SAMv3Handler instantiated");
    }

    /**
     * Verify the SAM protocol version is supported by this handler.
     *
     * @return true if the major version matches this handler's version
     */
    @Override
    public boolean verifVersion() {
        return (verMajor == 3);
    }

    /**
     * Get the IP address of the connected SAM client.
     *
     * @return the client's IP address as a string
     */
    public String getClientIP() {
        return this.socket.socket().getInetAddress().getHostAddress();
    }

    /**
     *  @return true if the handler has an active data stream (stolen or forwarding)
     */
    boolean hasActiveStream() {
        return stolenSocket || streamForwardingSocket;
    }

    /**
     *  For SAMv3StreamSession connect and accept
     */
    public void stealSocket() {
        stolenSocket = true;
        if (sendPorts) {
            try {
                socket.socket().setSoTimeout(0);
            } catch (SocketException se) { /* ignored */ }
        }
        bridge.unregisterHandlerFromPool(this);
        try {
            socket.configureBlocking(true);
        } catch (IOException ioe) { /* ignored */ }
        stopHandling();
    }

    /**
     *  For SAMv3StreamSession
     *  @since 0.9.20
     */
    SAMBridge getBridge() {
        return bridge;
    }

    /**
     *  For SAMv3DatagramServer
     *  @return may be null
     *  @since 0.9.24
     */
    Session getSession() {
        return session;
    }

    /**
     *  For subsessions created by MasterSession
     *  @since 0.9.25
     */
    void setSession(SAMv3RawSession sess) {
        rawSession = sess; session = sess;
    }

    /**
     *  For subsessions created by MasterSession
     *  @since 0.9.25
     */
    void setSession(SAMv3DatagramSession sess) {
        datagramSession = sess; session = sess;
    }

    /**
     *  For subsessions created by MasterSession
     *  @since 0.9.25
     */
    void setSession(SAMv3StreamSession sess) {
        streamSession = sess; session = sess;
    }

    /**
     * Process a single complete command line dispatched by the pool
     * or the legacy handle() loop. Returns false to signal the caller
     * that the handler should stop.
     *
     * @param line the complete command line (without trailing newline)
     * @return true if the handler should continue processing commands
     */
    boolean processLine(String line) {
        if (_log.shouldInfo())
            _log.info("New message received: [" + line + ']');
        Properties props;
        try {
            props = SAMUtils.parseParams(line);
        } catch (SAMException e) {
            if (_log.shouldDebug())
                _log.debug("Error parsing line: " + line, e);
            writeString("SESSION STATUS RESULT=I2P_ERROR", "invalid command format");
            return true;
        }
        String domain = (String) props.remove(SAMUtils.COMMAND);
        if (domain == null) {
            if (_log.shouldDebug())
                _log.debug("Ignoring newline in SAMv3 command");
            return true;
        }
        String opcode = (String) props.remove(SAMUtils.OPCODE);
        if (_log.shouldDebug()) {
            _log.debug("SAMv3Handler parsing [" + domain + " -> " + opcode + "]");
        }

        if (domain.equals("HELP")) {
            writeString("HELP STATUS RESULT=OK MESSAGE=https://geti2p.net/en/docs/api/samv3\n");
            return true;
        } else if (domain.equals("QUIT") || domain.equals("STOP") || domain.equals("EXIT")) {
            writeString(domain + " STATUS RESULT=OK MESSAGE=bye\n");
            stopHandling();
            return false;
        }

        if (opcode == null) {
            if (!writeString(domain + " STATUS RESULT=I2P_ERROR", "missing subcommand, enter HELP for help")) {
                stopHandling();
                return false;
            }
            return true;
        }

        boolean canContinue;
        if (domain.equals("STREAM")) {
            canContinue = execStreamMessage(opcode, props);
        } else if (domain.equals("SESSION")) {
            if (i2cpProps != null)
                props.putAll(i2cpProps);
            canContinue = execSessionMessage(opcode, props);
        } else if (domain.equals("DEST")) {
            canContinue = execDestMessage(opcode, props);
        } else if (domain.equals("NAMING")) {
            canContinue = execNamingMessage(opcode, props);
        } else if (domain.equals("DATAGRAM")) {
            canContinue = execDatagramMessage(opcode, props);
        } else if (domain.equals("RAW")) {
            canContinue = execRawMessage(opcode, props);
        } else if (domain.equals("AUTH")) {
            canContinue = execAuthMessage(opcode, props);
        } else {
            canContinue = writeString(domain + " STATUS RESULT=I2P_ERROR", "unsupported command, enter HELP for help");
        }

        if (!canContinue) {
            stopHandling();
            return false;
        }
        return true;
    }

    /**
     * Legacy handle() method. Not used when the handler is managed by
     * {@link SAMHandlerPool}, which dispatches via processLine().
     * Retained to fulfill the abstract contract in SAMHandler.
     */
    @Override
    public void handle() {
        if (_log.shouldDebug())
            _log.debug("SAMv3Handler.handle() called (pool mode)");
        // In pool mode, handle() is not called — the pool
        // reads commands and dispatches via processLine().
        // If this does get invoked (standalone mode), treat
        // it as a no-op; stopHandling cleanly shuts down.
        stopHandling();
    }

    /**
     * Stop the SAM handler, close the socket (unless stolen),
     * unregister from the bridge and the handler pool.
     *
     * @since 0.9.20
     */
    @Override
    public void stopHandling() {
        boolean alreadyStopping;
        synchronized (stopLock) {
            alreadyStopping = stopHandler;
            stopHandler = true;
        }
        if (alreadyStopping)
            return;
        if (_log.shouldInfo())
            _log.info("Stopping " + this + " -> Stolen? " + stolenSocket);
        if (!stolenSocket) {
            try {
                closeClientSocket();
            } catch (IOException e) { /* ignored */ }
        }
        if (streamForwardingSocket && this.getStreamSession() != null) {
            try {
                ((SAMv3StreamSession)streamSession).stopForwardingIncoming();
            } catch (SAMException e) {
                if (_log.shouldWarn())
                    _log.warn("Error while stopping forwarding connections" + "\n* Error: " + e.getMessage());
            } catch (InterruptedIOException e) {
                if (_log.shouldWarn())
                    _log.warn("Interrupted while stopping forwarding connections" + "\n* Error: " + e.getMessage());
            }
        }
        die();
        bridge.unregisterHandlerFromPool(this);
        bridge.unregister(this);
    }

    private void die() {
        SessionRecord rec = null;

        if (session!=null) {
            String nick = session.getNick();
            rec = nick != null ? sSessionsHash.get(nick) : null;
        }

        // Close the server socket (if any) so the SocketForwarder thread
        // can exit. Only the session-owning control handler or the FORWARD
        // handler should stop forwarding. Stolen-socket handlers
        // (CONNECT/ACCEPT) must NOT touch forwarding, and pool handlers
        // that reference streamSession but don't own it must not either.
        if (streamSession != null && (session != null || streamForwardingSocket)) {
            try {
                ((SAMv3StreamSession)streamSession).stopForwardingIncoming();
            } catch (SAMException e) { /* no server socket, fine */ }
              catch (InterruptedIOException e) { /* session gone, fine */ }
        }

        if (rec == null)
            return;

        // Only the session-owning control handler performs full cleanup.
        // Stolen-socket and FORWARD handlers share the session with the
        // control handler and must not close it.
        if (!stolenSocket && !streamForwardingSocket) {
            // Free the nick immediately so it can be reused by another handler
            sSessionsHash.del(session.getNick());

            // Close the I2P session and its resources
            session.close();

            // Interrupt and wait for thread group threads, with timeout
            // to avoid blocking the selector thread indefinitely
            rec.getThreadGroup().interrupt();
            long deadline = System.currentTimeMillis() + 10000;
            while (rec.getThreadGroup().activeCount() > 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                rec.getThreadGroup().destroy();
            } catch (IllegalThreadStateException e) { /* threads still running, ignore */ }

            session = null;
            streamSession = null;
        }
    }

    /**
     * Parse and execute a SESSION message.
     *
     * @param opcode the SESSION sub-command (e.g. CREATE, ADD, REMOVE)
     * @param props the parsed message properties
     * @return true if the handler should continue processing commands
     */
    @Override
    protected boolean execSessionMessage(String opcode, Properties props) {
        String nick = (String) props.remove("ID");
        if (nick == null)
            return writeString(SESSION_ERROR, "ID not specified");

        if (opcode.equals("CREATE"))
            return execSessionCreate(nick, props);

        if (opcode.equals("ADD") || opcode.equals("REMOVE"))
            return execSessionAddRemove(opcode, nick, props);

        if (_log.shouldDebug())
            _log.debug("Unrecognized SESSION message opcode: \"" + opcode + "\"");
        return writeString(SESSION_ERROR, "Unrecognized opcode");
    }

    /**
     * Execute SESSION CREATE — generates or validates a destination,
     * registers the nick, builds tunnels, and creates the session object.
     *
     * @param nick validated session ID
     * @param props parsed message properties
     * @return true if the handler should continue processing
     */
    private boolean execSessionCreate(String nick, Properties props) {
        boolean ok = false;
        try {
            String style = (String) props.remove("STYLE");
            if (style == null)
                return writeString(SESSION_ERROR, "No SESSION STYLE specified");

            if (getRawSession() != null || getDatagramSession() != null ||
                getStreamSession() != null) {
                if (_log.shouldDebug())
                    _log.debug("Trying to create a session, but one still exists");
                return writeString(SESSION_ERROR, "Session already exists");
            }
            if (props.isEmpty()) {
                if (_log.shouldDebug())
                    _log.debug("No parameters specified in SESSION CREATE message");
                return writeString(SESSION_ERROR, "No parameters for SESSION CREATE");
            }

            // codeql[java/toctou-race-condition] props is a per-call argument; sSessionsHash.put/get are synchronized in SessionsDB
            String dest = (String) props.remove("DESTINATION");
            if (dest == null) {
                if (_log.shouldDebug())
                    _log.debug("SESSION DESTINATION parameter not specified");
                return writeString(SESSION_ERROR, "DESTINATION not specified");
            }

            dest = initDestination(dest, props);

            i2cpProps.setProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_NONE);
            i2cpProps.setProperty("i2cp.fastReceive", "true");

            Properties allProps = new Properties();
            allProps.putAll(i2cpProps);
            allProps.putAll(props);
            // Shorten per-destination cooldown so CONNECT retries are not penalized
            // codeql[java/toctou-race-condition] allProps is a freshly-built local Properties; no shared-state check-then-act
            if (!allProps.containsKey("i2p.streaming.destinationCooldownMs"))
                allProps.setProperty("i2p.streaming.destinationCooldownMs", "15000");

            if (style.equals("MASTER") || style.equals("PRIMARY")) {
                allProps.setProperty("i2p.streaming.enforceProtocol", "true");
                allProps.setProperty("i2cp.dontPublishLeaseSet", "false");
            }

            try {
                sSessionsHash.put(nick, new SessionRecord(dest, allProps, this));
            } catch (SessionsDB.ExistingIdException e) {
                if (_log.shouldDebug())
                    _log.debug("SESSION ID parameter already in use");
                return writeString("SESSION STATUS RESULT=DUPLICATED_ID\n");
            } catch (SessionsDB.ExistingDestException e) {
                return writeString("SESSION STATUS RESULT=DUPLICATED_DEST\n");
            }

            instantiateSession(style, nick, allProps, props);

            if (stopHandler) {
                session.close();
                sSessionsHash.del(nick);
                session = null;
                streamSession = null;
                return false;
            }
            sessionReady = true;
            ok = true;
            return writeString("SESSION STATUS RESULT=OK DESTINATION=" + dest + '\n');
        } catch (DataFormatException e) {
            _log.error("Invalid SAM destination" + "\n* Error: " + e.getMessage());
            return writeString("SESSION STATUS RESULT=INVALID_KEY", e.getMessage());
        } catch (I2PSessionException e) {
            _log.error("Failed to start SAM session" + "\n* Error: " + e.getMessage());
            return writeString(SESSION_ERROR, e.getMessage());
        } catch (SAMException e) {
            _log.error("Failed to start SAM session" + "\n* Error: " + e.getMessage());
            return writeString(SESSION_ERROR, e.getMessage());
        } catch (IOException e) {
            _log.error("Failed to start SAM session" + "\n* Error: " + e.getMessage());
            return writeString(SESSION_ERROR, e.getMessage());
        } finally {
            if (!ok && nick != null) {
                sSessionsHash.del(nick);
                session = null;
            }
        }
    }

    /**
     * Resolve TRANSIENT or validate a user-supplied private key.
     *
     * @param dest DESTINATION value, may be "TRANSIENT"
     * @param props properties (SIGNATURE_TYPE consumed if TRANSIENT)
     * @return Base64-encoded private key
     */
    private static String initDestination(String dest, Properties props) throws DataFormatException {
        if (dest.equals("TRANSIENT")) {
            String sigTypeStr = (String) props.remove("SIGNATURE_TYPE");
            SigType sigType;
            if (sigTypeStr != null) {
                sigType = SigType.parseSigType(sigTypeStr);
                if (sigType == null)
                    throw new DataFormatException("unsupported SIGNATURE_TYPE " + sigTypeStr);
            } else {
                sigType = SigType.DSA_SHA1;
            }
            ByteArrayOutputStream priv = new ByteArrayOutputStream(663);
            SAMUtils.genRandomKey(priv, null, sigType);
            return Base64.encode(priv.toByteArray());
        }
        if (!SAMUtils.checkPrivateDestination(dest))
            throw new DataFormatException("invalid private key");
        return dest;
    }

    /**
     * Create and start the session object for the given style.
     * Blocks in the session constructors while tunnels are built.
     */
    private void instantiateSession(String style, String nick,
                                     Properties allProps, Properties props)
            throws DataFormatException, I2PSessionException, IOException, SAMException {
        SAMv3DatagramServer dgs = style.equals("RAW") || style.equals("DATAGRAM") ||
                                   style.equals("MASTER") || style.equals("PRIMARY") ?
                                   bridge.getV3DatagramServer(props) : null;
        if (style.equals("RAW")) {
            SAMv3RawSession v3 = new SAMv3RawSession(nick, dgs);
            rawSession = v3; session = v3;
            v3.start();
        } else if (style.equals("DATAGRAM")) {
            SAMv3DatagramSession v3 = new SAMv3DatagramSession(nick, dgs);
            datagramSession = v3; session = v3;
            v3.start();
        } else if (style.equals("STREAM")) {
            SAMv3StreamSession v3 = newSAMStreamSession(nick);
            streamSession = v3; session = v3;
            v3.start();
        } else if (style.equals("MASTER") || style.equals("PRIMARY")) {
            MasterSession v3 = new MasterSession(nick, dgs, this, allProps);
            streamSession = v3; datagramSession = v3; rawSession = v3; session = v3;
            v3.start();
        } else {
            throw new SAMException("Unrecognized SESSION STYLE");
        }
    }

    /**
     * Execute SESSION ADD or REMOVE for MASTER sessions.
     */
    private boolean execSessionAddRemove(String opcode, String nick, Properties props) {
        if (streamSession == null || datagramSession == null || rawSession == null)
            return writeString(SESSION_ERROR, "Not a MASTER session");
        String style = (String) props.remove("STYLE");
        MasterSession msess = (MasterSession) session;
        String msg = opcode.equals("ADD") ? msess.add(nick, style, props) : msess.remove(nick, props);
        if (msg == null)
            return writeString("SESSION STATUS RESULT=OK ID=\"" + nick + '"', opcode + ' ' + nick);
        return writeString(SESSION_ERROR + " ID=\"" + nick + '"', msg);
    }

    /**
     * @throws NPE if login nickname is not registered
     */
    private static SAMv3StreamSession newSAMStreamSession(String login )
        throws IOException, DataFormatException, SAMException {
            return new SAMv3StreamSession(login);
        }

    /**
     * Parse and execute a STREAM message.
     *
     * @param opcode the STREAM sub-command (e.g. CONNECT, ACCEPT, FORWARD)
     * @param props the parsed message properties
     * @return true if the handler should continue processing commands
     */
    @Override
    protected boolean execStreamMessage ( String opcode, Properties props ) {
        String nick = null;
        SessionRecord rec = null;

        if ( session != null ) {
            _log.error("v3 control socket cannot be used for STREAM");
            try {
                notifyStreamResult(true, "I2P_ERROR", "v3 control socket cannot be used for STREAM");
            } catch (IOException e) { /* ignored */ }
            return false;
        }

        nick = (String) props.remove("ID");
        if (nick == null) {
            if (_log.shouldDebug())
                _log.debug("SESSION ID parameter not specified");
            try {
                notifyStreamResult(true, "I2P_ERROR", "ID not specified");
            } catch (IOException e) { /* ignored */ }
            return false;
        }

        rec = sSessionsHash.get(nick);
        if ( rec==null ) {
            if (_log.shouldDebug())
                _log.debug("STREAM SESSION ID does not exist");
            try {
                notifyStreamResult(true, "INVALID_ID", "STREAM SESSION ID " + nick + " does not exist");
            } catch (IOException e) { /* ignored */ }
            return false;
        }

        SAMv3Handler ctl = rec.getHandler();
        streamSession = ctl.streamSession;
        if (streamSession==null || !ctl.sessionReady) {
            if (_log.shouldDebug())
                _log.debug("specified ID is not a stream session");
            try {
                notifyStreamResult(true, "I2P_ERROR",  "specified ID " + nick + " is not a STREAM session");
            } catch (IOException e) { /* ignored */ }
            return false;
        }
        if (streamSession.isDestroyed()) {
            if (_log.shouldDebug())
                _log.debug("Session manager is destroyed");
            // Remove from hash so no further handlers try to use it
            sSessionsHash.del(nick);
            try {
                notifyStreamResult(true, "I2P_ERROR",  "Session is closed");
            } catch (IOException e) { /* ignored */ }
            return false;
        }

        if ( opcode.equals ( "CONNECT" ) ) {
            return execStreamConnect ( props );
        } else if ( opcode.equals ( "ACCEPT" ) ) {
            return execStreamAccept(props);
        } else if ( opcode.equals ( "FORWARD") ) {
            return execStreamForwardIncoming( props );
        } else {
            if (_log.shouldDebug())
                _log.debug ( "Unrecognized STREAM message opcode: \"" + opcode + "\"" );
            try {
                notifyStreamResult(true, "I2P_ERROR",  "Unrecognized STREAM message opcode: "+opcode );
            } catch (IOException e) { /* ignored */ }
            return false;
        }
    }

    /**
     * Parse and execute a STREAM CONNECT message.
     * Dispatches the blocking I2P socket connect to a shared connector pool
     * so the caller's worker thread is never blocked. Error responses
     * (CONNECTION_REFUSED, CANT_REACH_PEER, TIMEOUT, etc.) are sent
     * asynchronously by the ConnectTask.
     *
     * Supports SILENT mode to suppress status messages.
     *
     * @param props the parsed message properties
     * @return true if the handler should continue processing commands
     */
    @Override
    protected boolean execStreamConnect(Properties props) {
        boolean verbose = !Boolean.parseBoolean(props.getProperty("SILENT"));
        try {
            if (props.isEmpty()) {
                if (_log.shouldDebug())
                    _log.debug("No parameters specified in STREAM CONNECT message");
                notifyStreamResult(verbose, "I2P_ERROR", "No parameters specified in STREAM CONNECT message");
                return false;
            }

            String dest = (String) props.remove("DESTINATION");
            if (dest == null) {
                if (_log.shouldDebug())
                    _log.debug("Destination not specified in STREAM CONNECT message");
                notifyStreamResult(verbose, "I2P_ERROR", "Destination not specified in STREAM CONNECT message");
                return false;
            }

            ((SAMv3StreamSession) streamSession).connectAsync(this, dest, props);
            return true;
        } catch (DataFormatException e) {
            if (_log.shouldDebug())
                _log.debug("Invalid destination in STREAM CONNECT message");
            try {
                notifyStreamResult(verbose, "INVALID_KEY", e.getMessage());
            } catch (IOException ioe) { /* client gone */ }
            return false;
        } catch (IOException e) {
            if (_log.shouldWarn())
                _log.warn("STREAM CONNECT error", e);
            return false;
        }
    }

    private boolean execStreamForwardIncoming( Properties props ) {
        // Messages ARE sent if SILENT=true,
        // which is different from CONNECT and ACCEPT.
        // But this matched the specs.
        try {
            try {
                streamForwardingSocket = true;
                ((SAMv3StreamSession)streamSession).startForwardingIncoming(props, sendPorts);
                notifyStreamResult( true, "OK", null );
                return true;
            } catch (SAMException e) {
            if (_log.shouldDebug())
                _log.debug("Forwarding STREAM connections failed" + "\n* Error: " + e.getMessage());
            notifyStreamResult ( true, "I2P_ERROR", "Forwarding failed : " + e.getMessage() );
            }
        } catch (IOException e) { /* ignored */ }
        return false;
    }

    /**
     * Execute STREAM ACCEPT.  Sends "OK" synchronously and starts the
     * blocking accept in a background thread.  Result (TIMEOUT, I2P_ERROR,
     * or the actual streaming data) arrives asynchronously.
     *
     * @return true if accept was launched, false on immediate error
     */
    private boolean execStreamAccept(Properties props) {
        boolean verbose = !Boolean.parseBoolean(props.getProperty("SILENT"));
        try {
            notifyStreamResult(verbose, "OK", null);
            ((SAMv3StreamSession) streamSession).accept(this, verbose);
            return true;
        } catch (InterruptedIOException e) {
            if (_log.shouldDebug())
                _log.debug("STREAM ACCEPT failed" + "\n* Error: " + e.getMessage());
            try {
                notifyStreamResult(verbose, "TIMEOUT", e.getMessage());
            } catch (IOException ioe) { /* client gone */ }
        } catch (SAMException e) {
            if (_log.shouldDebug())
                _log.debug("STREAM ACCEPT failed" + "\n* Error: " + e.getMessage());
            try {
                notifyStreamResult(verbose, "ALREADY_ACCEPTING", e.getMessage());
            } catch (IOException ioe) { /* client gone */ }
        } catch (IOException e) { /* ignored */ }
        return false;
    }


    /**
     * @param verbose if false, does nothing
     * @param result non-null
     * @param message may be null
     */
    public void notifyStreamResult(boolean verbose, String result, String message) throws IOException {
        if (!verbose) return;
        String msgString = createMessageString(message);
        String out = "STREAM STATUS RESULT=" + result + msgString + '\n';

        if (!writeString(out)) {
            throw new IOException ( "Error notifying connection to SAM client" );
        }
    }

    public void notifyStreamIncomingConnection(Destination d, int fromPort, int toPort) throws IOException {
        if (getStreamSession() == null) {
            _log.error("BUG! Received stream connection, but session is null!");
            throw new NullPointerException("BUG! STREAM session is null!");
        }
        StringBuilder buf = new StringBuilder(600);
        buf.append(d.toBase64());
        if (sendPorts) {
        buf.append(" FROM_PORT=").append(fromPort).append(" TO_PORT=").append(toPort);
        }
        buf.append('\n');
        if (!writeString(buf.toString())) {
            throw new IOException("Error notifying connection to SAM client");
        }
    }

    /**
     * Notify the SAM client of an incoming stream connection on a static channel.
     *
     * @param client the client socket channel
     * @param d the destination of the remote peer
     * @throws IOException if writing to the client socket fails
     */
    public static void notifyStreamIncomingConnection(SocketChannel client, Destination d) throws IOException {
        if (!writeString(d.toBase64() + "\n", client)) {
            throw new IOException("Error notifying connection to SAM client");
        }
    }

    /**
     * Notify the SAM client of an incoming stream connection with port information.
     *
     * @param client the client socket channel
     * @param d the destination of the remote peer
     * @param fromPort the source port
     * @param toPort the destination port
     * @throws IOException if writing to the client socket fails
     * @since 0.9.24
     */
    public static void notifyStreamIncomingConnection(SocketChannel client, Destination d, int fromPort, int toPort) throws IOException {
        if (!writeString(d.toBase64() + " FROM_PORT=" + fromPort + " TO_PORT=" + toPort + '\n', client)) {
            throw new IOException("Error notifying connection to SAM client");
        }
    }

    /** @since 0.9.24 */
    private boolean execAuthMessage(String opcode, Properties props) {
        if (opcode.equals("ENABLE")) {
            i2cpProps.setProperty(SAMBridge.PROP_AUTH, "true");
        } else if (opcode.equals("DISABLE")) {
            i2cpProps.setProperty(SAMBridge.PROP_AUTH, "false");
        } else if (opcode.equals("ADD")) {
            String user = props.getProperty("USER");
            String pw = props.getProperty("PASSWORD");
            if (user == null || pw == null)
                return writeString(AUTH_ERROR, "USER and PASSWORD required");
            String prop = SAMBridge.PROP_PW_PREFIX + user + SAMBridge.PROP_PW_SUFFIX;
            if (i2cpProps.containsKey(prop))
                return writeString(AUTH_ERROR, "user " + user + " already exists");
            PasswordManager pm = new PasswordManager(I2PAppContext.getGlobalContext());
            String shash = pm.createHash(pw);
            i2cpProps.setProperty(prop, shash);
        } else if (opcode.equals("REMOVE")) {
            String user = props.getProperty("USER");
            if (user == null)
                return writeString(AUTH_ERROR, "USER required");
            String prop = SAMBridge.PROP_PW_PREFIX + user + SAMBridge.PROP_PW_SUFFIX;
            if (!i2cpProps.containsKey(prop))
                return writeString(AUTH_ERROR, "user " + user + " not found");
            i2cpProps.remove(prop);
        } else {
            return writeString(AUTH_ERROR, "Unknown AUTH command");
        }
        try {
            bridge.saveConfig();
            return writeString("AUTH STATUS RESULT=OK\n");
        } catch (IOException ioe) {
            return writeString(AUTH_ERROR, "Config save failed: " + ioe);
        }
    }

}
