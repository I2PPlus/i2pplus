// License: GPLv2+ with I2PTunnel exception. See I2PTunnel.java and docs/LICENSES.md
package net.i2p.i2ptunnel.socks;

import static net.i2p.socks.SOCKS5Constants.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.app.Outproxy;
import net.i2p.client.naming.NamingService;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.I2PTunnelHTTPClientBase;
import net.i2p.socks.SOCKSException;
import net.i2p.util.Addresses;
import net.i2p.util.HexDump;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.PasswordManager;
import net.i2p.util.SipHash;

/**
 * SOCKS5 server implementation with comprehensive protocol support and authentication.
 * <p>This class handles SOCKS version 5 protocol including authentication
 * (username/password and no-auth), multiple address types (IPv4, IPv6, domain),
 * and extended commands including Tor RESOLVE for hostname resolution.
 * It supports UDP ASSOCIATE commands and provides outproxy connectivity
 * through both SOCKS and HTTP CONNECT methods.</p>
 * <p>The server includes security features like localhost blocking, IP-to-domain
 * mapping, and integrates with I2P naming service for destination
 * resolution. It also supports torsocks compatibility through
 * hostname caching mechanisms.</p>
 * <p>
 * Supports torsocks as of 0.9.57.
 */
class SOCKS5Server extends SOCKSServer {

    /** True once the SOCKS handshake has finished. */
    private boolean setupCompleted;
    /** Whether a username and password were configured. */
    private final boolean authRequired;
    /**
     * Torsocks support
     * Cache of fake IPv4 255.x.x.x (as returned from RESOLVE) to hostname
     * torsocks 2.3.0 does NOT support IPv6, even though Tor does now
     * The fake IP is a partial SipHash of the hostname, so collisions aren't predictable.
     * The IPs will change at restart, but torsocks doesn't appear to do any caching.
     */
    private static final Map<String, String> _torCache = new LHMCache<>(256);

    /** Sticky proxy cache: host -&gt; selected proxy, for consistent routing. */
    private final ConcurrentHashMap<String, String> _proxyCache = new ConcurrentHashMap<>();

    /**
     * Create a SOCKS5 server that communicates with the client using
     * the specified socket.  This method should not be invoked
     * directly: new SOCKS5Server objects should be created by using
     * {@link net.i2p.i2ptunnel.socks.SOCKSServerFactory#createSOCKSServer(net.i2p.I2PAppContext, java.net.Socket, java.util.Properties)}.
     * It is assumed that the
     * SOCKS VER field has been stripped from the input stream of the
     * client socket.
     *
     * @param ctx the context to run the tunnel in
     * @param clientSock the accepted socket, positioned just past the version
     * and command bytes already consumed
     * @param props non-null
     */
    public SOCKS5Server(I2PAppContext ctx, Socket clientSock, Properties props) {
        super(ctx, clientSock, props);
        this.authRequired =
                    Boolean.parseBoolean(props.getProperty(I2PTunnelHTTPClientBase.PROP_AUTH));
    }

    public Socket getClientSocket() throws SOCKSException {
        setupServer();

        return clientSock;
    }

    protected void setupServer() throws SOCKSException {
        if (setupCompleted) { return; }

        DataInputStream in;
        DataOutputStream out;
        try {
            in = new DataInputStream(clientSock.getInputStream());
            out = new DataOutputStream(clientSock.getOutputStream());

            init(in, out, clientSock.getInetAddress());
            if (manageRequest(in, out) == Command.UDP_ASSOCIATE)
                handleUDP(in, out);
        } catch (IOException e) {
            throw new SOCKSException("Connection error", e);
        }

        setupCompleted = true;
    }

    /**
     * SOCKS5 connection initialization.  This method assumes that
     * SOCKS "VER" field has been stripped from the input stream.
     *
     * @param in the client input stream, positioned after the VER byte,
     *           carrying the method count and the offered method list
     * @param out the client output stream for the method selection reply
     * @param client the client's IP address, used in the authentication log messages
     * @throws IOException if a stream read or write fails, or if the client
     *                     offers no acceptable authentication method
     */
    private void init(DataInputStream in, DataOutputStream out, InetAddress client) throws IOException {
        int nMethods = in.readUnsignedByte();
        int method = Method.NO_ACCEPTABLE_METHODS;

        for (int i = 0; i < nMethods; ++i) {
            int meth = in.readUnsignedByte();
            if (((!authRequired) && meth == Method.NO_AUTH_REQUIRED) ||
                meth == Method.USERNAME_PASSWORD) {
                // That's fine, we do support this method
                method  = meth;
                break;
            }
        }

        switch (method) {
          case Method.USERNAME_PASSWORD:
            _log.debug("Username/Password authentication required");
            sendInitReply(Method.USERNAME_PASSWORD, out);
            verifyPassword(in, out, client);
            return;

          case Method.NO_AUTH_REQUIRED:
            _log.debug("No authentication required");
            sendInitReply(Method.NO_AUTH_REQUIRED, out);
            return;

          default:
            _log.debug("No suitable authentication methods found (" + Integer.toHexString(method) + ")");
            sendInitReply(Method.NO_ACCEPTABLE_METHODS, out);
            throw new SOCKSException("Unsupported authentication method");
        }
    }

    /**
     * Wait for the username/password message and verify or throw SOCKSException on failure.
     *
     * @param in the client input stream, positioned at the start of the
     *           username/password subnegotiation
     * @param out the client output stream for the authentication reply
     * @param client the client's IP address, used only in the log messages
     * @throws IOException if a stream read or write fails, or if the subnegotiation
     *                     version, the user or password fields, or the credentials are invalid
     * @since 0.8.2
     */
    private void verifyPassword(DataInputStream in, DataOutputStream out, InetAddress client) throws IOException {
        int c = in.readUnsignedByte();
        if (c != AUTH_VERSION) {
            _log.logAlways(Log.WARN, "SOCKS proxy authentication failed");
            throw new SOCKSException("Unsupported authentication version");
        }
        c = in.readUnsignedByte();
        if (c <= 0) {
            _log.logAlways(Log.WARN, "SOCKS proxy authentication failed");
            try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); /* ignored */ }
            throw new SOCKSException("Bad authentication");
        }
        byte[] user = new byte[c];
        in.readFully(user);
        String u = DataHelper.getUTF8(user);
        c = in.readUnsignedByte();
        if (c <= 0) {
            _log.logAlways(Log.WARN, "SOCKS proxy authentication failed -> User: " + u + " on " + client);
            try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); /* ignored */ }
            throw new SOCKSException("Bad authentication");
        }
        byte[] pw = new byte[c];
        in.readFully(pw);
        if (authRequired) {
            // Hopefully these are in UTF-8, since that's what our config file is in
            String p = DataHelper.getUTF8(pw);
            String psha256 = I2PTunnelHTTPClientBase.PROP_PROXY_DIGEST_PREFIX + u +
                             I2PTunnelHTTPClientBase.PROP_PROXY_DIGEST_SHA256_SUFFIX;
            String configPW = props.getProperty(psha256);
            String hex = PasswordManager.sha256Hex(I2PSOCKSTunnel.AUTH_REALM, u, p);
            if (configPW == null || !DataHelper.eqCT(hex, configPW)) {
                _log.logAlways(Log.WARN, "SOCKS proxy authentication failed -> User: " + u + " on " + client);
                try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); /* ignored */ }
                sendAuthReply(AUTH_FAILURE, out);
                throw new SOCKSException("SOCKS authorization failure");
            }
        }
        if (_log.shouldLog(Log.INFO)) {
            _log.info("SOCKS authorization success -> User: " + u + " on " + client);
        }
        sendAuthReply(AUTH_SUCCESS, out);
    }

    /**
     * SOCKS5 request management.  This method assumes that all the
     * stuff preceding or enveloping the actual request (e.g. protocol
     * initialization, integrity/confidentiality encapsulations, etc)
     * has been stripped out of the input/output streams.
     *
     * @param in the client input stream, positioned at the request's version byte
     * @param out the client output stream for the failure reply
     * @return the validated command byte, Command.CONNECT or Command.UDP_ASSOCIATE
     * @throws IOException if a stream read or write fails, or if the request has an
     *                     invalid protocol version, command, or port number
     */
    private int manageRequest(DataInputStream in, DataOutputStream out) throws IOException {
        int socksVer = in.readUnsignedByte();
        if (socksVer != SOCKS_VERSION_5) {
            if (_log.shouldDebug())
                _log.debug("Error in SOCKS5 request (protocol != 5?)");
            throw new SOCKSException("Invalid protocol version in request: " + socksVer);
        }

        int command = checkCommand(in.readUnsignedByte(), out);

        // Reserved byte, should be 0x00
        in.readByte();

        parseAddressType(in, command, out);

        connPort = in.readUnsignedShort();
        if (connPort == 0) {
            if (_log.shouldDebug())
                _log.debug("Trying to connect to TCP port 0?  Dropping!");
            sendRequestReply(Reply.CONNECTION_NOT_ALLOWED_BY_RULESET, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            throw new SOCKSException("Invalid port number in request");
        }
        return command;
    }

    /**
     * Validate the SOCKS5 command byte, replying and throwing for
     * unsupported or unknown commands.
     *
     * @param command the command byte
     * @param out the output stream for the failure reply
     * @return the command byte
     * @throws IOException on stream errors
     * @throws SOCKSException if the command is not supported
     */
    private int checkCommand(int command, DataOutputStream out) throws IOException {
        switch (command) {
        case Command.CONNECT:
            break;

        case Command.BIND:
            if (_log.shouldDebug())
                _log.debug("BIND command is not supported!");
            sendRequestReply(Reply.COMMAND_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            throw new SOCKSException("BIND command not supported");

        case Command.UDP_ASSOCIATE:
            break;

        case Command.TOR_RESOLVE:
            // https://github.com/torproject/torspec/blob/main/socks-extensions.txt
            // reply will be sent below
            break;

        case Command.TOR_RESOLVE_PTR:
        case Command.TOR_CONNECT_DIR:
            // https://github.com/torproject/torspec/blob/main/socks-extensions.txt
            if (_log.shouldDebug())
                _log.debug("Tor command unsupported (" + Integer.toHexString(command) + ")");
            sendRequestReply(Reply.COMMAND_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            throw new SOCKSException("Unsupported command in request");

        default:
            if (_log.shouldDebug())
                _log.debug("Unknown command in request (" + Integer.toHexString(command) + ")");
            sendRequestReply(Reply.COMMAND_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            throw new SOCKSException("Unsupported command in request");
        }
        return command;
    }

    /**
     * Parse and validate the address type and host bytes of a SOCKS5
     * request, filling in connHostName and addressType.  TOR_RESOLVE
     * requests cache the hostname under a fake 255.x.x.x address.
     *
     * @param in the request stream
     * @param command the command byte
     * @param out the output stream for failure replies
     * @throws IOException on stream errors
     */
    private void parseAddressType(DataInputStream in, int command, DataOutputStream out) throws IOException {
        addressType = in.readUnsignedByte();
        switch (addressType) {
            case AddressType.IPV4: {
                byte[] ip = new byte[4];
                in.readFully(ip);
                connHostName = Addresses.toString(ip);
                // Check if the requested IP should be mapped to a domain name
                String mappedDomainName = getMappedDomainNameForIP(connHostName);
                if (mappedDomainName != null) {
                    if (_log.shouldDebug())
                        _log.debug("IPV4 address " + connHostName + " was mapped to domain name " + mappedDomainName);
                    addressType = AddressType.DOMAINNAME;
                    connHostName = mappedDomainName;
                } else if (command != Command.UDP_ASSOCIATE && _log.shouldWarn()) {
                    _log.warn("IPV4 address type in request: " + connHostName + ". Is your client secure?");
                }
                break;
            }

            case AddressType.DOMAINNAME: {
                int addrLen = in.readUnsignedByte();
                if (addrLen == 0) {
                    if (_log.shouldDebug())
                        _log.debug("0-sized address length?");
                    throw new SOCKSException("Illegal DOMAINNAME length");
                }
                byte[] addr = new byte[addrLen];
                in.readFully(addr);
                String host = DataHelper.getUTF8(addr);
                if (command == Command.TOR_RESOLVE) {
                    // For Tor, save hostname for the CONNECT
                    int hash = SipHash.hashCode(addr) & 0xffffff;
                    byte[] fake = new byte[4];
                    fake[0] = (byte) 255;
                    DataHelper.toLong(fake, 1, 3, hash);
                    String fakeIP = Addresses.toString(fake);
                    String old;
                    synchronized(_torCache) {
                        old = _torCache.put(fakeIP, host);
                    }
                    if (old != null && !old.equals(host) && _log.shouldWarn()) {
                        _log.warn("Hash collision " + old + " and " + host);
                    }
                    if (_log.shouldDebug())
                        _log.debug("Cached host " + host + " at address " + fakeIP);
                    sendRequestReply(Reply.SUCCEEDED, AddressType.IPV4, InetAddress.getByName(fakeIP), null, 1, out);
                    throw new SOCKSException("ignore");
                }
                if (host.startsWith("255.")) {
                    // For Tor, where hostname was sent previously in the RESOLVE
                    synchronized(_torCache) {
                        connHostName = _torCache.get(host);
                    }
                    if (connHostName == null) {
                        sendRequestReply(Reply.ADDRESS_TYPE_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
                        throw new SOCKSException("No cache entry found for Tor IP " + host);
                    }
                    if (_log.shouldDebug())
                        _log.debug("Using hostname from previous RESOLVE: " + connHostName);
                } else {
                    connHostName = host;
                }
            }
            if (_log.shouldDebug())
                _log.debug("DOMAINNAME address type in request: " + connHostName);
            break;

            case AddressType.IPV6:
                // RFC 1928: the 16 BND.ADDR bytes are present for ANY command;
                // skipping them for UDP_ASSOCIATE left them in the stream, so the
                // port was parsed from address bytes and every later command in
                // the UDP loop desynced. Always consume them.
                byte[] ip = new byte[16];
                in.readFully(ip);
                if (command != Command.UDP_ASSOCIATE) {
                    connHostName = Addresses.toString(ip);
                    if (_log.shouldWarn())
                        _log.warn("IPV6 address type in request! Is your client secure?");
                }
                break;

            default:
                if (_log.shouldDebug())
                    _log.debug("Unknown address type in request (" + Integer.toHexString(addressType) + ")");
                sendRequestReply(Reply.ADDRESS_TYPE_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
                throw new SOCKSException("Invalid addresses type in request");
        }
    }

    @Override
    protected void confirmConnection() throws SOCKSException {
        DataOutputStream out;
        try {
            out = new DataOutputStream(clientSock.getOutputStream());

            sendRequestReply(Reply.SUCCEEDED, AddressType.IPV4, InetAddress.getByName("127.0.0.1"), null, 1, out);
        } catch (IOException e) {
            throw new SOCKSException("Connection error", e);
        }
    }

    /**
     * Send the specified reply during SOCKS5 initialization.
     *
     * @param replyCode the selected Method constant to send back
     * @param out the client output stream
     * @throws IOException if the write to the client fails
     */
    private void sendInitReply(int replyCode, DataOutputStream out) throws IOException {
        byte[] reply = new byte[2];
        reply[0] = SOCKS_VERSION_5;
        reply[1] = (byte) replyCode;
        if (_log.shouldDebug())
            _log.debug("Sending init reply:\n" + HexDump.dump(reply));
        out.write(reply);
    }

    /**
     * Send the specified reply during SOCKS5 authorization.
     *
     * @param replyCode the result to send back, AUTH_SUCCESS or AUTH_FAILURE
     * @param out the client output stream
     * @throws IOException if the write to the client fails
     * @since 0.8.2
     */
    private void sendAuthReply(int replyCode, DataOutputStream out) throws IOException {
        byte[] reply = new byte[2];
        reply[0] = AUTH_VERSION;
        reply[1] = (byte) replyCode;
        if (_log.shouldDebug())
            _log.debug("Sending auth reply:\n" + HexDump.dump(reply));
        out.write(reply);
    }

    /**
     * Send the specified reply to a request of the client.  Either
     * one of inetAddr or domainName can be null, depending on
     * addressType.
     *
     * @param replyCode the Reply constant to send
     * @param addressType an AddressType constant selecting how the bound
     *                    address is encoded: IPV4, IPV6, or DOMAINNAME
     * @param inetAddr the IPv4 or IPv6 address to report, null if none
     * @param domainName the domain name to report for DOMAINNAME type, null if none
     * @param bindPort the port to report in the reply, 0-65535
     * @param out the client output stream
     * @throws IOException if the write to the client fails
     */
    private void sendRequestReply(int replyCode, int addressType, InetAddress inetAddr, String domainName,
                                  int bindPort, DataOutputStream out) throws IOException {
        ByteArrayOutputStream reps = new ByteArrayOutputStream();
        DataOutputStream dreps = new DataOutputStream(reps);

        dreps.write(SOCKS_VERSION_5);
        dreps.write(replyCode);

        // Reserved byte, should be 0x00
        dreps.write(0x00);

        dreps.write(addressType);

        switch (addressType) {
            case AddressType.IPV4:
            case AddressType.IPV6:
                if (inetAddr != null) {
                    dreps.write(inetAddr.getAddress());
                }
                break;

            case AddressType.DOMAINNAME:
                if (domainName != null) {
                    dreps.writeByte(domainName.length());
                    dreps.writeBytes(domainName);
                }
                break;

            default:
                _log.error("Unknown SOCKS address type -> Not sending reply * Type: 0x" + Integer.toHexString(addressType));
            return;
        }

        dreps.writeShort(bindPort);

        byte[] reply = reps.toByteArray();

        if (_log.shouldDebug()) {
            _log.debug("Sending request reply:\n" + HexDump.dump(reply));
        }

        out.write(reply);
    }

    /**
     * Send a failure reply and swallow any IOException.
     * Only for use in the connectTo() failure paths.
     *
     * @param reply the reply code
     * @param out the output stream
     */
    private void sendFailureReply(int reply, DataOutputStream out) {
        try {
            sendRequestReply(reply, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
        } catch (IOException ioe) { /* ignored */ }
    }

    /**
     * Get an I2PSocket for sending/receiving 8-bit clean data
     * to/from the destination of the SOCKS connection.
     *
     * @return an I2PSocket connected with the destination
     */
    public I2PSocket getDestinationI2PSocket(I2PSOCKSTunnel t) throws SOCKSException {
        setupServer();

        if (connHostName == null) {
            _log.error("BUG: destination hostname has not been initialized!");
            throw new SOCKSException("BUG! See the logs!");
        }
        if (connPort == 0) {
            _log.error("BUG: destination port has not been initialized!");
            throw new SOCKSException("BUG! See the logs!");
        }

        DataOutputStream out; // for errors
        try {
            out = new DataOutputStream(clientSock.getOutputStream());
        } catch (IOException e) {
            throw new SOCKSException("Connection error", e);
        }

        I2PSocket destSock = null;
        try {
            destSock = connectToDestination(t, out);
            confirmConnection();
            _log.debug("Connection confirmed - exchanging data...");
            return destSock;
        } catch (DataFormatException e) {
            if (_log.shouldWarn())
                _log.warn("SOCKS error", e);
            closeDestSocket(destSock);
            sendFailureReply(Reply.HOST_UNREACHABLE, out);
            throw new SOCKSException("Error in destination format");
        } catch (IOException e) {
            if (_log.shouldWarn())
                _log.warn("SOCKS error", e);
            // confirmConnection() throws SOCKSException (an IOException) after
            // the destination socket is already open — a local client that died
            // between handshake and success reply must not leave it connected.
            closeDestSocket(destSock);
            sendFailureReply(Reply.HOST_UNREACHABLE, out);
            throw new SOCKSException("Connection error", e);
        } catch (I2PException e) {
            if (_log.shouldWarn())
                _log.warn("SOCKS error", e);
            closeDestSocket(destSock);
            sendFailureReply(Reply.HOST_UNREACHABLE, out);
            throw new SOCKSException("Connection error", e);
        }
    }

    /**
     * Close a destination I2P socket left open when the SOCKS handshake fails
     * after connecting, so a dead local client cannot accumulate idle streams.
     *
     * @param destSock the socket to close, null is ignored
     */
    private static void closeDestSocket(I2PSocket destSock) {
        if (destSock != null) {
            try {destSock.close();}
            catch (IOException ioe) { /* ignored */ }
        }
    }

    /**
     * Connect to the requested destination via I2P, an outproxy plugin,
     * or a configured outproxy.
     *
     * @param t the owning tunnel
     * @param out the output stream for failure replies
     * @return the connected socket
     * @throws SOCKSException on any connection failure
     * @throws DataFormatException if the destination is malformed
     * @throws IOException on stream errors
     * @throws I2PException on I2P errors
     */
    private I2PSocket connectToDestination(I2PSOCKSTunnel t, DataOutputStream out)
        throws SOCKSException, DataFormatException, IOException, I2PException {
        String hostLowerCase = connHostName.toLowerCase(Locale.US);
        if (NamingService.isI2PHost(hostLowerCase)) {
            Destination dest = _context.namingService().lookup(connHostName);
            if (dest == null) {
                sendFailureReply(Reply.HOST_UNREACHABLE, out);
                throw new SOCKSException("Host not found");
            }
            if (_log.shouldDebug())
                _log.debug("Connecting to " + connHostName + "...");
            Properties overrides = new Properties();
            I2PSocketOptions sktOpts = t.buildOptions(overrides);
            sktOpts.setPort(connPort);
            return t.createI2PSocket(dest, sktOpts);
        } else if (I2PTunnelHTTPClientBase.isBlockedLocalAddress(connHostName)) {
            String err = "No local or private accesses allowed through the Socks Proxy: " + connHostName;
            _log.error(err);
            sendFailureReply(Reply.CONNECTION_NOT_ALLOWED_BY_RULESET, out);
            throw new SOCKSException(err);
        } else {
            Outproxy outproxy = getOutproxyPlugin();
            if (outproxy != null) {
                // In HTTPClient, we use OutproxyRunner to run a Socket,
                // but here, we wrap a Socket in a I2PSocket and use the regular Runner.
                try {
                    return new SocketWrapper(outproxy.connect(connHostName, connPort));
                } catch (IOException ioe) {
                    try {
                        sendRequestReply(Reply.HOST_UNREACHABLE, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
                    } catch (IOException ioe2) { /* ignored */ }
                    throw new SOCKSException("connect failed via outproxy plugin", ioe);
                }
            } else {
                List<String> proxies = t.getProxies(connPort);
                if (proxies == null || proxies.isEmpty()) {
                    String err = "No outproxy configured for port " + connPort + " and no default configured either";
                    _log.error(err);
                    sendFailureReply(Reply.CONNECTION_NOT_ALLOWED_BY_RULESET, out);
                    throw new SOCKSException(err);
                }
                // Sticky proxy selection: reuse the same proxy for the same
                // host; on failure the cached entry is dropped so the next
                // connection re-picks from the list.
                String cached = _proxyCache.get(connHostName);
                String proxy;
                if (cached != null && !proxies.contains(cached)) {
                    _proxyCache.remove(connHostName);
                    proxy = selectProxy(proxies, connHostName);
                } else if (cached != null) {
                    proxy = cached;
                } else {
                    proxy = selectProxy(proxies, connHostName);
                }
                try {
                    return outproxyConnect(t, proxy);
                } catch (SOCKSException se) {
                    _proxyCache.remove(connHostName);
                    sendFailureReply(Reply.HOST_UNREACHABLE, out);
                    throw se;
                }
            }
        }
    }

    /**
     * Select a proxy, preferring a cached one for the same host.
     *
     * @param proxies the configured proxy destinations, non-empty
     * @param host the destination host whose earlier choice is reused if
     *             it is still in the list
     * @return the proxy to use: the cached one for this host if still
     *         listed, otherwise a random pick that is then cached
     */
    private String selectProxy(List<String> proxies, String host) {
        if (proxies.size() == 1) return proxies.get(0);
        String cached = _proxyCache.get(host);
        if (cached != null && proxies.contains(cached)) return cached;
        String picked = proxies.get(_context.random().nextInt(proxies.size()));
        _proxyCache.put(host, picked);
        return picked;
    }

    /**
     * The shared UDP tunnel, started on first use. This isn't really the
     * right place for it, since we can't stop the tunnel once it starts.
     */
    private static SOCKSUDPTunnel tunnel;
    /** Guards starting the shared UDP tunnel. */
    private static final Object _startLock = new Object();
    /** Zero address sent when the client's real address is hidden. */
    private static final byte[] dummyIP = new byte[4];

    /**
     * We got a UDP associate command.
     * Loop here looking for more, never return normally,
     * or else I2PSocksTunnel will create a streaming lib connection.
     * <p>
     * RFC 1928 defines one UDP relay per TCP control connection, but a client
     * may open more; each additional ASSOCIATE request gets its own local port.
     * The loop ends on any other command, or on a closed control connection.
     *
     * @param in the control connection input stream, read for further ASSOCIATE commands
     * @param out the control connection output stream, for the relay port replies
     * @throws SOCKSException always, after the UDP relays are closed, so the
     *                        caller never opens a streaming connection
     */
    private void handleUDP(DataInputStream in, DataOutputStream out) throws SOCKSException {
        List<Integer> ports = new ArrayList<>(1);
        synchronized (_startLock) {
            if (tunnel == null) {
                // tunnel options?
                tunnel = new SOCKSUDPTunnel(new I2PTunnel());
                tunnel.startRunning();
            }
        }
        // try/finally: tunnel.add() binds a relay DatagramSocket and a bind
        // failure throws RuntimeException out of the loop — without the finally
        // the already-bound relays (and their entries in the static tunnel's
        // port map) would leak for the router's lifetime.
        try {
            while (true) {
                // Set it up. connHostName and connPort are the client's info.
                InetAddress ia = null;
                try {
                    ia = InetAddress.getByAddress(connHostName, dummyIP);
                } catch (UnknownHostException uhe) { /* ignored */ } // won't happen, no resolving done here
                int myPort = tunnel.add(ia, connPort);
                ports.add(Integer.valueOf(myPort));
                try {
                    sendRequestReply(Reply.SUCCEEDED, AddressType.IPV4, InetAddress.getByName("127.0.0.1"), null, myPort, out);
                } catch (IOException ioe) { break; }

                // Block for the next control command; anything but another
                // ASSOCIATE ends the loop and closes the UDP relays.
                try {
                    int command = manageRequest(in, out);
                    // don't do this...
                    if (command != Command.UDP_ASSOCIATE)
                        break;
                } catch (IOException ioe) { break; }
            }
        } finally {
            for (Integer i : ports)
                tunnel.remove(i);
        }

        // Prevent I2PSocksTunnel from calling getDestinationI2PSocket() above
        // to create a streaming lib connection...
        // This isn't very elegant...
        //
        throw new SOCKSException("End of UDP Processing");
    }
}
