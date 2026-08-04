// License: GPLv2+ with I2PTunnel exception. See I2PTunnel.java and docs/LICENSES.md
package net.i2p.i2ptunnel.socks;

/**
 * Abstract base class for SOCKS protocol servers.
 * <p>
 * This class provides common functionality for SOCKS4a and SOCKS5 server
 * implementations, including connection details storage, IP-to-domain mapping
 * support, and outproxy plugin integration. It defines the interface
 * for server setup, client socket management, and destination socket
 * creation.
 * <p>
 * Serves as foundation for specific SOCKS protocol implementations,
 * handling shared concerns like security restrictions, error handling,
 * and integration with I2P naming and outproxy services.
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Properties;
import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.Outproxy;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnelHTTPClientBase;
import net.i2p.i2ptunnel.I2PTunnelHTTPServer;
import net.i2p.socks.SOCKS5Client;
import net.i2p.socks.SOCKSException;
import net.i2p.util.Log;

/**
 * Abstract base class used by all SOCKS servers.
 */
abstract class SOCKSServer {

    private static final String PROP_MAPPING_PREFIX = "ipmapping.";

    /**
     * Hostname requested by the SOCKS client.
     */
    protected String connHostName;
    /**
     * Port requested by the SOCKS client.
     */
    protected int connPort;
    /**
     * SOCKS address type (e.g. DOMAINNAME, IPv4, IPv6).
     */
    protected int addressType;

    protected final I2PAppContext _context;
    protected final Socket clientSock;
    protected final Properties props;
    protected final Log _log;

    /** @since 0.9.27 */
    protected SOCKSServer(I2PAppContext ctx, Socket clientSock, Properties props) {
        _context = ctx;
        this.clientSock = clientSock;
        this.props = props;
        _log = ctx.logManager().getLog(getClass());
    }

    /**
     * IP to domain name mapping support. This matches the given IP string
     * against a user-set list of mappings. This enables applications which do
     * not properly support the SOCKS5 DOMAINNAME feature to be used with I2P.
     * @param ip The IP address to check.
     * @return   The domain name if a mapping is found, or null otherwise.
     * @since 0.9.5
     */
    protected String getMappedDomainNameForIP(String ip) {
        if (props.containsKey(PROP_MAPPING_PREFIX + ip))
            return props.getProperty(PROP_MAPPING_PREFIX + ip);
        return null;
    }

    /**
     * Perform server initialization (expecially regarding protected
     * variables).
     */
    protected abstract void setupServer() throws SOCKSException;

    /**
     * Get a socket that can be used to send/receive 8-bit clean data
     * to/from the client.
     *
     * @return a Socket connected with the client
     */
    public abstract Socket getClientSocket() throws SOCKSException;

    /**
     * Confirm to the client that the connection has succeeded
     */
    protected abstract void confirmConnection() throws SOCKSException;

    /**
     * Get an I2PSocket that can be used to send/receive 8-bit clean data
     * to/from the destination of the SOCKS connection.
     *
     * @return an I2PSocket connected with the destination
     */
    public abstract I2PSocket getDestinationI2PSocket(I2PSOCKSTunnel t) throws SOCKSException;

    /**
     *  @since 0.9.27
     * @return whether use outproxy plugin
     */
    private boolean shouldUseOutproxyPlugin() {
        return Boolean.parseBoolean(props.getProperty(I2PTunnelHTTPClientBase.PROP_USE_OUTPROXY_PLUGIN, "true"));
    }

    /**
     *  @return null if disabled or not installed
     *  @since 0.9.27
     */
    protected Outproxy getOutproxyPlugin() {
        if (shouldUseOutproxyPlugin()) {
            ClientAppManager mgr = _context.clientAppManager();
            if (mgr != null) {
                ClientApp op = mgr.getRegisteredApp(Outproxy.NAME);
                if (op != null)
                    return (Outproxy) op;
            }
        }
        return null;
    }

    private static final String[] _skipHeaders = new String[0];

    /**
     *  Act as a SOCKS 5 client to connect to an outproxy
     *  Caller must send success or error to local socks client.
     *
     *  @return open socket or throws error
     *  @since 0.8.2
     */
    protected I2PSocket outproxyConnect(I2PSOCKSTunnel tun, String proxy) throws IOException, I2PException {
        Properties overrides = new Properties();
        overrides.setProperty("option.i2p.streaming.connectDelay", "150");
        I2PSocketOptions proxyOpts = tun.buildOptions(overrides);
        int proxyPort = 0;
        int colon = proxy.indexOf(':');
        if (colon > 0) {
            try {
                proxyPort = Integer.parseInt(proxy.substring(colon + 1));
                if (proxyPort > 0)
                    proxyOpts.setPort(proxyPort);
            } catch (NumberFormatException nfe) { /* ignored */ }
            proxy = proxy.substring(0, colon);
        }
        Destination dest = _context.namingService().lookup(proxy);
        if (dest == null)
            throw new SOCKSException("Outproxy not found");
        I2PSocket destSock = tun.createI2PSocket(dest, proxyOpts);
        OutputStream out = null;
        InputStream in = null;
        try {
            out = destSock.getOutputStream();
            boolean authAvail = Boolean.parseBoolean(props.getProperty(I2PTunnelHTTPClientBase.PROP_OUTPROXY_AUTH));
            String configUser =  null;
            String configPW = null;
            if (authAvail) {
                configUser =  props.getProperty(I2PTunnelHTTPClientBase.PROP_OUTPROXY_USER_PREFIX + proxy);
                configPW = props.getProperty(I2PTunnelHTTPClientBase.PROP_OUTPROXY_PW_PREFIX + proxy);
                if (configUser == null || configPW == null) {
                    configUser =  props.getProperty(I2PTunnelHTTPClientBase.PROP_OUTPROXY_USER);
                    configPW = props.getProperty(I2PTunnelHTTPClientBase.PROP_OUTPROXY_PW);
                }
            }
            boolean https = "connect".equals(props.getProperty(I2PSOCKSTunnel.PROP_OUTPROXY_TYPE));
            if (_log.shouldDebug())
                _log.debug("Connecting to " + (https ? "HTTPS" : "SOCKS") + " outproxy " + proxy + " -> " + connHostName + ":" + connPort);

            if (https) {
                httpsConnect(destSock, out, connHostName, connPort, configUser, configPW);
            } else {
                in = destSock.getInputStream();
                SOCKS5Client.connect(in, out, connHostName, connPort, configUser, configPW);
            }
        } catch (IOException e) {
            try { destSock.close(); } catch (IOException ioe) { /* ignored */ }
            if (in != null) try { in.close(); } catch (IOException ioe) { /* ignored */ }
            if (out != null) try { out.close(); } catch (IOException ioe) { /* ignored */ }
            throw e;
        }
        // that's it, caller will send confirmation to our client
        return destSock;
    }

    /**
     *  Act as a https client to connect to a CONNECT outproxy.
     *
     *  Caller must send success or error to local socks client.
     *  Caller must close destSock and pout.
     *
     *  @param destSock socket to the proxy
     *  @param pout output stream to the proxy
     *  @param connHostName hostname or IP for the proxy to connect to
     *  @param connPort port for the proxy to connect to
     *  @param configUser username unsupported
     *  @param configPW password unsupported
     *  @since 0.9.57
     */
    public void httpsConnect(I2PSocket destSock, OutputStream pout, String connHostName,
                             int connPort, String configUser, String configPW) throws IOException {
        StringBuilder buf = new StringBuilder(64);
        buf.append("CONNECT ");
        boolean v6 = connHostName.contains(":");
        if (v6)
            buf.append('[');
        buf.append(connHostName);
        if (v6)
            buf.append(']');
        buf.append(':');
        buf.append(connPort);
        buf.append(" HTTP/1.1\r\n\r\n");
        if (_log.shouldDebug())
            _log.debug("Request to outproxy: " + buf);
        pout.write(DataHelper.getASCII(buf.toString()));
        pout.flush();
        // eat the response and headers
        buf.setLength(0);
        I2PTunnelHTTPServer.readHeaders(destSock, null, buf, _skipHeaders, _context, (long) 30*1000);
        String[] f = DataHelper.split(buf.toString(), " ", 2);
        if (f.length < 2)
            throw new IOException("Bad response from SOCKS5 proxy");
        if (!f[1].startsWith("200 "))
            throw new IOException("Error from SOCKS5 proxy: " + f[1]);
        if (_log.shouldDebug())
            _log.debug("Response from SOCKS5 proxy: " + buf);
    }
}
