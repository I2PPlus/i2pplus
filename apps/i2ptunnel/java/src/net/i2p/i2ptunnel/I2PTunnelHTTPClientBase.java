/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

/**
 * Abstract base class for HTTP client tunnels with common proxy functionality.
 * <p>
 * Implements HTTP proxy protocol handling including authentication (Basic and Digest),
 * outproxy configuration and selection, error page generation, and request filtering.
 * Serves as foundation for standard HTTP clients and CONNECT clients,
 * handling HTTP protocol parsing, header manipulation, and proxy authentication.
 * <p>
 * Supports multiple outproxies, SSL outproxies, and comprehensive error
 * handling with localized error pages.
 */

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.IDN;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.util.EepGet;
import net.i2p.util.EventDispatcher;
import net.i2p.util.InternalSocket;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.PasswordManager;
import net.i2p.util.PortMapper;
import net.i2p.util.Translate;
import net.i2p.util.TranslateReader;

import java.nio.charset.StandardCharsets;
/**
 * Common things for HTTPClient and ConnectClient
 * Retrofit over them in 0.8.2
 *
 *
 */
public abstract class I2PTunnelHTTPClientBase extends I2PTunnelClientBase implements Runnable {

    private static final int PROXYNONCE_BYTES = 8;
    private static final int SHA256_BYTES = 32;
    /** Nonce size in bytes (date + SHA256). */
    private static final int NONCE_BYTES = DataHelper.DATE_LENGTH + SHA256_BYTES;
    private static final long MAX_NONCE_AGE = 60*60*1000L;
    private static final int MAX_NONCE_COUNT = 1024;
    /**
     *  Property to use local outproxy plugin.
     *  */
    public static final String PROP_USE_OUTPROXY_PLUGIN = "i2ptunnel.useLocalOutproxy";
    /**
     *  Property for SSL outproxies.
     *  */
    public static final String PROP_SSL_OUTPROXIES = "i2ptunnel.httpclient.SSLOutproxies";
    private static final String SLASH = System.getProperty("file.separator");

    /**
     *  This is a standard soTimeout, not a total timeout.
     *  We have no slowloris protection on the client side.
     *  See I2PTunnelHTTPServer or SAM's ReadLine if we need that.
     *
     */
    protected static final int INITIAL_SO_TIMEOUT = 30*1000;

    /**
     *  Cookie that counts meta-refresh retries on a shed connection.
     *  @since 0.9.71+
     */
    static final String SHED_COOKIE = "i2pshed";
    /**
     *  How many meta-refresh pages to serve before the final 503.
     *  @since 0.9.71+
     */
    static final int SHED_MAX_REFRESH = 2;
    /**
     *  Refresh delay in seconds for the intermediate shed page.
     *  @since 0.9.71+
     */
    static final int SHED_REFRESH_SECONDS = 10;
    /**
     *  Short SO_TIMEOUT while reading the shed request head (never on the hot path).
     *  @since 0.9.71+
     */
    static final int SHED_READ_TIMEOUT_MS = 500;

    /**
     *  Failsafe
     *
     */
    protected static final int BROWSER_READ_TIMEOUT = 4*60*60*1000;

    private static final String ERR_AUTH1 =
            "HTTP/1.1 407 Proxy Authentication Required\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "Accept-Charset: ISO-8859-1,utf-8;q=0.7,*;q=0.5\r\n"; // try to get a UTF-8-encoded response back for the password
    // put the auth type and realm in between
    private static final String ERR_AUTH2 =
            "\r\n" +
            "<html><body><H1>I2P ERROR: PROXY AUTHENTICATION REQUIRED</H1>" +
            "This proxy is configured to require authentication.";

    /** list of configured outproxies */
    protected final List<String> _proxyList;

    /** error response when no outproxy is configured */
    protected final static String ERR_NO_OUTPROXY =
         "HTTP/1.1 503 No Outproxy Configured\r\n" +
         "Content-Type: text/html; charset=iso-8859-1\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n"+
         "<html><body><H1>I2P ERROR: No outproxy found</H1>" +
         "Your request was for a site outside of I2P, but you have no " +
         "outproxy configured.  Please configure an outproxy in I2PTunnel";

    /** error response when destination is not found */
    protected final static String ERR_DESTINATION_UNKNOWN =
            "HTTP/1.1 503 Service Unavailable\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: DESTINATION NOT FOUND</H1>" +
            "That I2P Destination was not found. Perhaps you pasted in the " +
            "wrong BASE64 I2P Destination or the link you are following is " +
            "bad. The host (or the WWW proxy, if you're using one) could also " +
            "be temporarily offline.  You may want to <b>retry</b>.  " +
            "Could not find the following Destination:<BR><BR><div>";

    /** HTTP 200 response for established CONNECT tunnels */
    protected final static String SUCCESS_RESPONSE =
        "HTTP/1.1 200 Connection Established\r\n"+
         "Proxy-agent: I2P\r\n"+
         "\r\n";

    private final byte[] _proxyNonce;
    private final ConcurrentHashMap<String, NonceInfo> _nonces;
    private final AtomicInteger _nonceCleanCounter = new AtomicInteger();
    // clearnet host to proxy
    private final Map<String, String> _proxyCache = new LHMCache<>(32);
    // very simple, remember last-failed only
    private String _lastFailedProxy;
    // clearnet host to proxy
    private final Map<String, String> _proxySSLCache = new LHMCache<>(32);
    // very simple, remember last-failed only
    private String _lastFailedSSLProxy;

    /** available as of Java 6 and Android API 9 */
    private static final boolean _haveIDN;
    static {
        boolean h;
        try {
            Class.forName("java.net.IDN", false, ClassLoader.getSystemClassLoader());
            h = true;
        } catch (ClassNotFoundException cnfe) {h = false;}
        _haveIDN = h;
    }

    /**
     *  Generates a unique log prefix for request tracking.
     * <p>
     * This method creates a consistent prefix string that includes the request ID
     * for correlating log entries across multiple threads and connections.
     * </p>
     *
     * @param requestId the unique identifier for this request
     * @return a formatted string suitable for logging
     *
     */
    protected String getPrefix(long requestId) {
        return "[HTTPClient] [Request: " + _clientId + '/' + requestId + "] ";
    }

    // TODO standard proxy config changes require tunnel restart;
    // SSL proxy config is parsed on the fly;
    // allow both to be changed and store the SSL proxy list.
    // TODO should track more than one failed proxy

    /**
     *  Selects an appropriate outproxy for the given host from the proxy pool.
     * <p>
     * The selection is random, but sticky per host: the first chosen proxy
     * for a host is cached and reused. Failed proxies are remembered and
     * temporarily skipped.
     * </p>
     *
     * @param host the target hostname to route through the outproxy (may be used for stickiness)
     * @return the selected proxy destination string (Base32 address), or null if no proxies configured
     * @see #_proxyList
     */
    protected String selectProxy(String host) {
        return selectProxy(host, _proxyList, _proxyList, _lastFailedProxy, _proxyCache, "[HTTPClient] Using outproxy [");
    }

    /**
     *  Selects an SSL-capable outproxy for HTTPS connections.
     * <p>
     * This method is similar to selectProxy() but only considers outproxies
     * configured for SSL support (via i2ptunnel.httpclient.SSLOutproxies).
     * </p>
     * <p>
     * Unlike selectProxy(), we parse the option on the fly so it
     * can be changed without restart. ConnectClient should use selectProxy().
     * </p>
     *
     * @param host the target hostname (may be used for proxy stickiness)
     * @return the selected SSL proxy destination, or null if none configured
     * @see #selectProxy(String)
     */
    protected String selectSSLProxy(String host) {
        String s = getTunnel().getClientOptions().getProperty(PROP_SSL_OUTPROXIES);
        if (s == null) {return null;}
        String[] p = DataHelper.split(s, "[,; \r\n\t]");
        if (p.length == 0) {return null;}
        // todo doesn't check for ""
        return selectProxy(host, _proxySSLCache, Arrays.asList(p), _lastFailedSSLProxy, _proxySSLCache, "[HTTPClient] Using SSL outproxy [");
    }

    /**
     *  Select an outproxy for the given host with load balancing.
     *  Failed proxies are remembered and temporarily skipped.
     *
     *  @param host the target hostname (may be used for proxy stickiness)
     *  @param lock the monitor guarding the proxy state
     *  @param proxies the configured outproxies
     *  @param lastFailed the last failed proxy, or null
     *  @param cache clearnet host to proxy stickiness cache
     *  @param logPrefix the message prefix
     *  @return the selected proxy destination string, or null if none configured
     */
    private String selectProxy(String host, Object lock, List<String> proxies, String lastFailed,
                               Map<String, String> cache, String logPrefix) {
        String rv;
        synchronized (lock) {
            int size = proxies.size();
            if (size <= 0) {return null;}
            if (size == 1) {return proxies.get(0);}
            rv = cache.get(host);
            if (rv == null) {
                List<String> tmpList;
                if (lastFailed != null) {
                    // don't use last failed one
                    tmpList = new ArrayList<>(proxies);
                    tmpList.remove(lastFailed);
                    size = tmpList.size();
                } else {tmpList = proxies;}
                int index = _context.random().nextInt(size);
                rv = tmpList.get(index);
                cache.put(host, rv);
            }
        }
        if (_log.shouldInfo()) {
            _log.info(logPrefix + rv + "] for " + host);
        }
        return rv;
    }

    /**
     *  Update the cache and note if failed.
     *
     *  @param proxy which
     *  @param host clearnet hostname targeted
     *  @param isSSL set to FALSE for ConnectClient
     *  @param ok success or failure
     *
     */
    protected void noteProxyResult(String proxy, String host, boolean isSSL, boolean ok) {
        if (proxy == null) { return; }
        if (isSSL) {
            synchronized (_proxySSLCache) {
                if (ok) {
                    if (proxy.equals(_lastFailedSSLProxy)) {_lastFailedSSLProxy = null;}
                    _proxySSLCache.put(host, proxy);
                } else {
                    _lastFailedSSLProxy = proxy;
                    if (proxy.equals(_proxySSLCache.get(host))) {_proxySSLCache.remove(host);}
                }
            }
        } else {
            synchronized (_proxyList) {
                if (_proxyList.size() > 1) {
                    if (ok) {
                        if (proxy.equals(_lastFailedProxy)) {_lastFailedProxy = null;}
                        _proxyCache.put(host, proxy);
                    } else {
                        _lastFailedProxy = proxy;
                        if (proxy.equals(_proxyCache.get(host))) {_proxyCache.remove(host);}
                    }
                }
            }
        }
        String proxyName = proxy;
        if (proxy != null && proxy.length() > 20) {proxyName = proxy.substring(0,12) + "...";}
        if (isSSL) {
            if (_log.shouldInfo()) {
                _log.info("[HTTPClient] SSL request via outproxy [" + proxyName + "] -> Success? " + ok + " \n* Target: " + host);
            }
        } else {
            if (_log.shouldInfo()) {
                _log.info("[HTTPClient] Request via outproxy [" + proxyName + "] -> Success? " + ok + " \n* Target: " + host);
            }
        }
    }

    /**
     *  -1 (forever) as of 0.9.36,
     *  so that large POSTs won't timeout on the read side
     */
    protected static final int DEFAULT_READ_TIMEOUT = -1;

    /** Counter for unique request IDs */
    protected static final AtomicLong __requestId = new AtomicLong();

    /**
     *  Create a new HTTP client tunnel.
     *
     *  @param localPort the local port to bind to
     *  @param ownDest whether to use our own destination
     *  @param l logging instance
     *  @param notifyThis event dispatcher for notifications
     *  @param handlerName the handler name
     *  @param tunnel the parent I2PTunnel instance
     */
    public I2PTunnelHTTPClientBase(int localPort, boolean ownDest, Logging l,
                               EventDispatcher notifyThis, String handlerName,
                               I2PTunnel tunnel) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis, handlerName, tunnel);
        // force connect delay and bulk profile
        Properties opts = tunnel.getClientOptions();
        opts.setProperty("i2p.streaming.connectDelay", "500");
        opts.remove("i2p.streaming.maxWindowSize");
        _proxyList = new ArrayList<>(4);
        _proxyNonce = new byte[PROXYNONCE_BYTES];
        _context.random().nextBytes(_proxyNonce);
        _nonces = new ConcurrentHashMap<>();
    }

    /**
     *  This constructor always starts the tunnel (ignoring the i2cp.delayOpen option).
     *  It is used to add a client to an existing socket manager.
     *
     *  @param localPort the local port to bind to
     *  @param l logging instance
     *  @param sktMgr the existing socket manager
     *  @param tunnel the parent I2PTunnel instance
     *  @param notifyThis event dispatcher for notifications
     *  @param clientId the client identifier
     */
    public I2PTunnelHTTPClientBase(int localPort, Logging l, I2PSocketManager sktMgr,
            I2PTunnel tunnel, EventDispatcher notifyThis, long clientId )
            throws IllegalArgumentException {
        super(localPort, l, sktMgr, tunnel, notifyThis, clientId);
        // force connect delay and bulk profile
        Properties opts = tunnel.getClientOptions();
        opts.setProperty("i2p.streaming.connectDelay", "500");
        opts.remove("i2p.streaming.maxWindowSize");
        _proxyList = new ArrayList<>(4);
        _proxyNonce = new byte[PROXYNONCE_BYTES];
        _context.random().nextBytes(_proxyNonce);
        _nonces = new ConcurrentHashMap<>();
    }

    //////// Authorization stuff

    /** all auth  */
    public static final String PROP_AUTH = "proxyAuth";
    /** Proxy username property */
    public static final String PROP_USER = "proxyUsername";
    /** Proxy password property */
    public static final String PROP_PW = "proxyPassword";
    /** additional users may be added with proxyPassword.user=pw */
    public static final String PROP_PW_PREFIX = PROP_PW + '.';
    /** Outproxy auth property */
    public static final String PROP_OUTPROXY_AUTH = "outproxyAuth";
    /** Outproxy username property */
    public static final String PROP_OUTPROXY_USER = "outproxyUsername";
    /** Outproxy password property */
    public static final String PROP_OUTPROXY_PW = "outproxyPassword";
    /** passwords for specific outproxies may be added with outproxyUsername.fooproxy.i2p=user and outproxyPassword.fooproxy.i2p=pw */
    public static final String PROP_OUTPROXY_USER_PREFIX = PROP_OUTPROXY_USER + '.';
    /** Outproxy password property prefix. */
    public static final String PROP_OUTPROXY_PW_PREFIX = PROP_OUTPROXY_PW + '.';
    /** new style MD5 auth */
    public static final String PROP_PROXY_DIGEST_PREFIX = "proxy.auth.";
    /** MD5 digest suffix */
    public static final String PROP_PROXY_DIGEST_SUFFIX = ".md5";
    /** SHA-256 digest suffix */
    public static final String PROP_PROXY_DIGEST_SHA256_SUFFIX = ".sha256";
    /** Basic auth type constant */
    public static final String BASIC_AUTH = "basic";
    /** Digest auth type constant */
    public static final String DIGEST_AUTH = "digest";

    /**
     *  Get the authentication realm string.
     *  @return realm string
     */
    protected abstract String getRealm();

    /** Authentication result status for HTTP client connections */
    protected enum AuthResult {
        /** Bad request */
        AUTH_BAD_REQ,
        /** Authentication failed */
        AUTH_BAD,
        /** Stale nonce */
        AUTH_STALE,
        /** Authentication successful */
        AUTH_GOOD
    }

    /** Tracks nonce values for digest authentication. */
    private static class NonceInfo {
        private final long expires;
        private final BitSet counts;

        /**
         * @param exp expiration time
         */
        public NonceInfo(long exp) {
            expires = exp;
            counts = new BitSet(MAX_NONCE_COUNT);
        }

        /**
         * @return the expires
         */
        public long getExpires() {return expires;}

        /**
         * @return whether valid
         */
        public AuthResult isValid(int nc) {
            if (nc <= 0) {return AuthResult.AUTH_BAD;}
            if (nc >= MAX_NONCE_COUNT) {return AuthResult.AUTH_STALE;}
            synchronized(counts) {
                if (counts.get(nc)) {return AuthResult.AUTH_BAD;}
                counts.set(nc);
            }
            return AuthResult.AUTH_GOOD;
        }
    }

    /**
     *  Update the outproxy list then call super.
     *
     *
     */
    @Override
    public void optionsUpdated(I2PTunnel tunnel) {
        if (getTunnel() != tunnel) {return;}
        Properties props = tunnel.getClientOptions();
        // see TunnelController.setSessionOptions()
        String proxies = props.getProperty("proxyList");
        if (proxies != null) {
            StringTokenizer tok = new StringTokenizer(proxies, ",; \r\n\t");
            synchronized(_proxyList) {
                _proxyList.clear();
                while (tok.hasMoreTokens()) {
                    String p = tok.nextToken().trim();
                    if (!p.isEmpty()) {_proxyList.add(p);}
                }
            }
        } else {
            synchronized(_proxyList) {_proxyList.clear();}
        }
        super.optionsUpdated(tunnel);
    }

    /**
     *  Checks if HTTP Digest authentication is required by the outproxy.
     * <p>
     * This method checks the configured authentication requirements and
     * determines whether the current request requires digest authentication.
     * </p>
     *
     * @return true if digest authentication is required, false otherwise
     *
     */
    protected boolean isDigestAuthRequired() {
        String authRequired = getTunnel().getClientOptions().getProperty(PROP_AUTH);
        if (authRequired == null) {return false;}
        return authRequired.toLowerCase(Locale.US).equals("digest");
    }

    /**
     *  Write a real HTTP status on a shed connection so the browser never sees
     *  an empty response: HTML GET navigations get a cookie-capped meta-refresh
     *  page (up to {@link #SHED_MAX_REFRESH} times), everything else gets a 503.
     *  Falls back to a plain 503 if the request head cannot be read in time.
     *
     *  @param s the accepted socket being shed; never null
     *  @since 0.9.71+
     */
    @Override
    protected void writeShedResponse(Socket s) {
        String method = null;
        String accept = null;
        String cookie = null;
        try {
            s.setSoTimeout(SHED_READ_TIMEOUT_MS);
            java.io.InputStream in = s.getInputStream();
            String line = DataHelper.readLine(in);
            if (line != null && line.length() > 0) {
                // strip trailing \r (DataHelper only strips \n)
                if (line.endsWith("\r")) {line = line.substring(0, line.length() - 1);}
                int sp = line.indexOf(' ');
                if (sp > 0) {method = line.substring(0, sp);}
                int maxLines = 64;
                for (int i = 0; i < maxLines; i++) {
                    String h = DataHelper.readLine(in);
                    if (h == null || h.length() <= 1) {break;}
                    if (h.endsWith("\r")) {h = h.substring(0, h.length() - 1);}
                    int colon = h.indexOf(':');
                    if (colon <= 0) {continue;}
                    String name = h.substring(0, colon);
                    String value = h.substring(colon + 1).trim();
                    if (name.equalsIgnoreCase("Accept")) {accept = value;}
                    else if (name.equalsIgnoreCase("Cookie")) {cookie = value;}
                }
            }
        } catch (IOException ioe) {
            // head unreadable; fall through to 503
        }
        try {
            OutputStream out = s.getOutputStream();
            int attempt = parseShedAttempt(cookie);
            if (shouldMetaRefresh(method, accept, attempt)) {
                out.write(buildShedRefreshResponse(attempt + 1).getBytes(StandardCharsets.UTF_8));
            } else {
                out.write(buildShed503Response().getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        } catch (IOException ioe) {
            // ignored; socket closed below
        }
        try {s.close();}
        catch (IOException ioe) { /* ignored */ }
    }

    /**
     *  How many shed meta-refresh attempts the request's Cookie header already used.
     *
     *  @param cookie the full Cookie header value, or null
     *  @return parsed attempt count, or 0 when absent/unparseable
     *  @since 0.9.71+
     */
    static int parseShedAttempt(String cookie) {
        if (cookie == null || cookie.isEmpty()) {return 0;}
        String key = SHED_COOKIE + "=";
        int idx = cookie.indexOf(key);
        if (idx < 0) {return 0;}
        int start = idx + key.length();
        int end = start;
        while (end < cookie.length() && cookie.charAt(end) >= '0' && cookie.charAt(end) <= '9') {end++;}
        if (end == start) {return 0;}
        try {
            return Integer.parseInt(cookie.substring(start, end));
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

    /**
     *  Whether a shed connection should get a meta-refresh page instead of a 503.
     *  Only HTML navigations qualify: GET with an Accept header that asks for
     *  text/html, and fewer than {@link #SHED_MAX_REFRESH} prior attempts.
     *
     *  @param method HTTP method from the request line, or null
     *  @param accept Accept header value, or null
     *  @param attempt prior attempts from the shed cookie
     *  @return true to emit a meta-refresh page
     *  @since 0.9.71+
     */
    static boolean shouldMetaRefresh(String method, String accept, int attempt) {
        if (attempt >= SHED_MAX_REFRESH) {return false;}
        if (method == null || !method.equalsIgnoreCase("GET")) {return false;}
        if (accept == null) {return false;}
        return accept.toLowerCase(Locale.US).contains("text/html");
    }

    /**
     *  Intermediate shed page: unquoted meta refresh (no nested quotes in the tag)
     *  plus a Set-Cookie bump so the browser retries at most
     *  {@link #SHED_MAX_REFRESH} times before the final 503.
     *
     *  @param nextAttempt the attempt count to store in the cookie (1-based after increment)
     *  @return a complete HTTP/1.1 200 response with HTML body
     *  @since 0.9.71+
     */
    static String buildShedRefreshResponse(int nextAttempt) {
        String body = "<!DOCTYPE html><html><head><meta http-equiv=refresh content=" +
                SHED_REFRESH_SECONDS + "></head><body>The server is busy. Retrying in " +
                SHED_REFRESH_SECONDS + " seconds...</body></html>\n";
        return "HTTP/1.1 200 OK\r\n" +
               "Content-Type: text/html; charset=UTF-8\r\n" +
               "Cache-Control: no-store\r\n" +
               "Set-Cookie: " + SHED_COOKIE + "=" + nextAttempt + "; Path=/; Max-Age=60\r\n" +
               "Connection: close\r\n" +
               "Content-Length: " + body.length() + "\r\n" +
               "\r\n" + body;
    }

    /**
     *  Final shed response after refresh budget is exhausted (or non-HTML).
     *
     *  @return a complete HTTP/1.1 503 response
     *  @since 0.9.71+
     */
    static String buildShed503Response() {
        String body = "<!DOCTYPE html><html><head><title>503</title></head><body>" +
                      "The server is busy. Please try again shortly.</body></html>\n";
        return "HTTP/1.1 503 Service Unavailable\r\n" +
               "Content-Type: text/html; charset=UTF-8\r\n" +
               "Cache-Control: no-store\r\n" +
               "Retry-After: " + SHED_REFRESH_SECONDS + "\r\n" +
               "Set-Cookie: " + SHED_COOKIE + "=0; Path=/; Max-Age=0\r\n" +
               "Connection: close\r\n" +
               "Content-Length: " + body.length() + "\r\n" +
               "\r\n" + body;
    }

    /**
     *  Authorization
     *  Ref: RFC 2617
     *  If the socket is an InternalSocket, no auth required.
     *
     *  @param s the client socket
     *  @param requestId the unique request identifier for logging
     *  @param method GET, POST, etc.
     *  @param authorization may be null, the full auth line e.g. "Basic lskjlksjf"
     *  @return success
     */
    protected AuthResult authorize(Socket s, long requestId, String method, String authorization) {
        String authRequired = getTunnel().getClientOptions().getProperty(PROP_AUTH);
        if (authRequired == null) {return AuthResult.AUTH_GOOD;}
        authRequired = authRequired.toLowerCase(Locale.US);
        if (authRequired.equals("false")) {return AuthResult.AUTH_GOOD;}
        if (s instanceof InternalSocket) {
            if (_log.shouldInfo()) {
                _log.info(getPrefix(requestId) + "Access via internal socket: no authorization required!");
            }
            return AuthResult.AUTH_GOOD;
        }
        if (authorization == null) {return AuthResult.AUTH_BAD;}
        if (_log.shouldInfo()) {
            _log.info(getPrefix(requestId) + "Auth: " + summarizeAuthorization(authorization));
        }
        String authLC = authorization.toLowerCase(Locale.US);
        if (authRequired.equals("true") || authRequired.equals(BASIC_AUTH)) {
            if (!authLC.startsWith("basic ")) {return AuthResult.AUTH_BAD;}
            authorization = authorization.substring(6);

            // The browser sends the standard Base64 alphabet, but Base64.decode()
            // uses the I2P alphabet ('~' is 63), so remap before decoding. The
            // standard-alphabet path (safeDecode with useStandardAlphabet) is private.
            byte[] decoded = Base64.decode(authorization.replace("/", "~").replace("+", "="));
            if (decoded != null) {
                // We send Accept-Charset: UTF-8 in the 407 so hopefully it comes back that way inside the B64 ?
                try {
                    String dec = new String(decoded, StandardCharsets.UTF_8);
                    String[] parts = DataHelper.split(dec, ":");
                    String user = parts[0];
                    String pw = parts[1];
                    // first try pw for that user
                    String configPW = getTunnel().getClientOptions().getProperty(PROP_PW_PREFIX + user);
                    if (configPW == null) {
                        // if not, look at default user and pw
                        String configUser = getTunnel().getClientOptions().getProperty(PROP_USER);
                        if (user.equals(configUser))
                            configPW = getTunnel().getClientOptions().getProperty(PROP_PW);
                    }
                    if (configPW != null && pw != null && DataHelper.eqCT(pw, configPW)) {
                        if (_log.shouldInfo()) {
                            _log.info(getPrefix(requestId) + "Good auth - user: " + sanitizeLogValue(user) +
                                      " on " + addrAndPort(s));
                        }
                        return AuthResult.AUTH_GOOD;
                    }
                    _log.logAlways(Log.WARN, "[HTTPClient] HTTP proxy authentication failed -> User: " +
                                    sanitizeLogValue(user) + " on " + addrAndPort(s));
                    // Rate-limit per-IP: brief sleep only on repeated failures.
                    // A full 5 s sleep per attempt is a DoS amplifier — an attacker
                    // can trivially exhaust the thread pool.  The auth failure itself
                    // already denies the request; a per-IP cooldown in the caller is
                    // not implemented.
                } catch (ArrayIndexOutOfBoundsException aioobe) {
                    // no ':' in response; never log the credential material itself
                    if (_log.shouldWarn()) {
                        _log.warn(getPrefix(requestId) + "[HTTPClient] Bad auth B64 (" + authorization.length() + " bytes)", aioobe);
                    }
                    return AuthResult.AUTH_BAD_REQ;
                }
                return AuthResult.AUTH_BAD;
            } else {
                if (_log.shouldWarn()) {
                    _log.warn(getPrefix(requestId) + "[HTTPClient] Bad auth B64 (" + authorization.length() + " bytes)");
                }
                return AuthResult.AUTH_BAD_REQ;
            }
        } else if (authRequired.equals(DIGEST_AUTH)) {
            if (!authLC.startsWith("digest ")) {
                return AuthResult.AUTH_BAD;
            }
            authorization = authorization.substring(7);
            Map<String, String> args = parseArgs(authorization);
            AuthResult rv = validateDigest(method, args, s);
            return rv;
        } else {
            _log.error("[HTTPClient] Unknown proxy authorization type configured: " + authRequired);
            return AuthResult.AUTH_BAD_REQ;
        }
    }

    /**
     *  Verify all of it.
     *  Ref: RFC 2617
     *
     *  @param s just to log the IP on failure
     *
     */
    private AuthResult validateDigest(String method, Map<String, String> args, Socket s) {
        String user = args.get("username");
        String realm = args.get("realm");
        String nonce = args.get("nonce");
        String qop = args.get("qop");
        String uri = args.get("uri");
        String cnonce = args.get("cnonce");
        String nc = args.get("nc");
        String response = args.get("response");
        if (user == null || realm == null || nonce == null || qop == null ||
            uri == null || cnonce == null || nc == null || response == null) {
            if (_log.shouldInfo()) {
                _log.info("[HTTPClient] Bad digest request: " + DataHelper.toString(sanitizeAuthArgs(args)));
            }
            return AuthResult.AUTH_BAD_REQ;
        }
        // RFC 7616
        String algorithm = args.get("algorithm");
        boolean isSHA256 = false;
        if (algorithm != null) {
            algorithm = algorithm.toLowerCase(Locale.US);
            if (algorithm.equals("sha-256")) {isSHA256 = true;}
            else if (!algorithm.equals("md5")) {
                if (_log.shouldLog(Log.INFO)) {
                    _log.info("Bad digest request: " + DataHelper.toString(sanitizeAuthArgs(args)));
                }
                return AuthResult.AUTH_BAD_REQ;
            }
        }
        // nonce check
        AuthResult check = verifyNonce(nonce, nc);
        if (check != AuthResult.AUTH_GOOD) {
            if (_log.shouldInfo()) {
                _log.info("[HTTPClient] Bad digest nonce: " + check + ' ' + DataHelper.toString(sanitizeAuthArgs(args)));
            }
            return check;
        }
        // get H(A1) == stored password
        String ha1 = getTunnel().getClientOptions().getProperty(
            PROP_PROXY_DIGEST_PREFIX + user + (isSHA256 ? PROP_PROXY_DIGEST_SHA256_SUFFIX : PROP_PROXY_DIGEST_SUFFIX));
        if (ha1 == null) {
            _log.logAlways(Log.WARN, "[HTTPClient] HTTP proxy authentication failed -> User: " +
                            sanitizeLogValue(user) + " on " + addrAndPort(s));
            return AuthResult.AUTH_BAD;
        }
        // get H(A2)
        String a2 = method + ':' + uri;
        String ha2 = isSHA256 ? PasswordManager.sha256Hex(a2) : PasswordManager.md5Hex(a2);
        // response check
        String kd = ha1 + ':' + nonce + ':' + nc + ':' + cnonce + ':' + qop + ':' + ha2;
        String hkd = isSHA256 ? PasswordManager.sha256Hex(kd) : PasswordManager.md5Hex(kd);
        if (!DataHelper.eqCT(response, hkd)) {
            _log.logAlways(Log.WARN, "[HTTPClient] HTTP proxy authentication failed -> User: " +
                            sanitizeLogValue(user) + " on " + addrAndPort(s));
            if (_log.shouldInfo()) {
                _log.info("[HTTPClient] Bad digest auth: " + DataHelper.toString(sanitizeAuthArgs(args)));
            }
            return AuthResult.AUTH_BAD;
        }
        if (_log.shouldInfo()) {_log.info("[HTTPClient] Good digest auth - user: " + sanitizeLogValue(user) +
                                          " on " + addrAndPort(s));}
        return AuthResult.AUTH_GOOD;
    }

    /**
     *  Maximum length of an attacker-supplied value (e.g. a username) echoed
     *  to the log.
     *  @since 0.9.71+
     */
    private static final int MAX_LOG_VALUE = 64;

    /**
     *  Summarize an Authorization header for logging without disclosing
     *  credential material. Basic auth carries a decodable user:password pair,
     *  so only the scheme and the credential byte count are logged. The scheme
     *  is attacker-supplied too, so anything with line breaks (log injection)
     *  or an implausible length is replaced.
     *
     *  @param authorization the full header value e.g. "Basic dXNlcjpwYXNz"
     *                       (no "Proxy-Authorization:" prefix), may be null
     *  @return e.g. "Basic (13 bytes)", never the credentials; "null" if null
     *          and "malformed" for a hostile scheme
     *  @since 0.9.71+
     */
    static String summarizeAuthorization(String authorization) {
        if (authorization == null) {return "null";}
        int sp = authorization.indexOf(' ');
        String scheme = sp >= 0 ? authorization.substring(0, sp) : authorization;
        if (scheme.length() > 32 || scheme.indexOf('\r') >= 0 ||
            scheme.indexOf('\n') >= 0 || scheme.indexOf('\0') >= 0) {
            return "malformed";
        }
        int credLen = sp >= 0 ? authorization.length() - sp - 1 : 0;
        return scheme + " (" + credLen + " bytes)";
    }

    /**
     *  Strip line breaks and cap the length of an attacker-supplied value
     *  before it is written to the log, so a hostile username cannot forge
     *  extra log lines.
     *
     *  @param value the raw value, may be null
     *  @return the sanitized value, empty string if null
     *  @since 0.9.71+
     */
    static String sanitizeLogValue(String value) {
        if (value == null) {return "";}
        int len = Math.min(value.length(), MAX_LOG_VALUE);
        StringBuilder rv = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {c = ' ';}
            rv.append(c);
        }
        return rv.toString();
    }

    /**
     *  Copy digest request arguments with the response hash redacted for
     *  logging. The digest response is replayable inside its nonce window,
     *  so it is credential material like the password itself.
     *
     *  @param args the parsed digest arguments, may be null
     *  @return a copy with "response" replaced, empty map if null
     *  @since 0.9.71+
     */
    static Map<String, String> sanitizeAuthArgs(Map<String, String> args) {
        if (args == null) {return new HashMap<String, String>(0);}
        Map<String, String> rv = new HashMap<String, String>(args);
        if (rv.containsKey("response")) {rv.put("response", "redacted");}
        return rv;
    }

    /**
     *  Format the remote address as IP:PORT for ban and failure logging.
     *  IPv6 addresses are bracketed so the port separator is unambiguous.
     *
     *  @param s the socket, may be null
     *  @return e.g. "127.0.0.1:4444" or "[::1]:1234", "unknown" if unavailable
     *  @since 0.9.71+
     */
    static String addrAndPort(Socket s) {
        if (s == null) {return "unknown";}
        InetAddress addr;
        try {
            addr = s.getInetAddress();
        } catch (Exception e) {
            return "unknown";
        }
        if (addr == null) {return "unknown";}
        String host = addr.getHostAddress();
        if (host.indexOf(':') >= 0) {host = '[' + host + ']';}
        return host + ':' + s.getPort();
    }

    /**
     *  Determine whether a proxy request target is a loopback, private,
     *  link-local, shared, or otherwise non-routable address that must not be
     *  reached through the proxy. Non-.i2p hosts are handed to the outproxy
     *  plugin, which opens a raw socket from this machine, so a request for
     *  127.0.0.1 or 10.x would otherwise reach the router itself or the LAN.
     *
     *  <p>The host is parsed as an address rather than matched with string
     *  prefixes, so every address in a blocked block is caught (not only
     *  10.0.x.x or 172.16.x.x) while a name that merely looks similar
     *  (10.0.example.com) is not. Bracketed IPv6 literals, zone ids, and
     *  v4-mapped forms are normalized first.</p>
     *
     *  @param host the request target host, without port, may be null
     *  @return true if the host must not be proxied to
     *  @since 0.9.71+
     */
    static boolean isBlockedLocalAddress(String host) {
        if (host == null) {return true;}
        String h = host.trim();
        if (h.isEmpty()) {return true;}
        if (h.charAt(0) == '[') {
            int end = h.indexOf(']');
            h = end > 0 ? h.substring(1, end) : h.substring(1);
        }
        h = h.toLowerCase(Locale.US);
        if (h.equals("localhost") || h.endsWith(".localhost")) {return true;}
        if (h.indexOf(':') < 0) {return isBlockedIPv4Address(h);}
        int pct = h.indexOf('%');
        if (pct >= 0) {h = h.substring(0, pct);}
        int[] g = parseIPv6Groups(h);
        if (g == null) {return false;}
        if (g[0] == 0 && g[1] == 0 && g[2] == 0 && g[3] == 0 && g[4] == 0) {
            if (g[5] == 0xffff) {
                // v4-mapped, e.g. ::ffff:127.0.0.1
                return isBlockedIPv4Address(((g[6] >> 8) & 0xff) + "." + (g[6] & 0xff) +
                                            "." + ((g[7] >> 8) & 0xff) + "." + (g[7] & 0xff));
            }
            if (g[5] == 0 && g[6] == 0 && g[7] == 0) {return true;}  // :: unspecified
            if (g[5] == 0 && g[6] == 0 && g[7] == 1) {return true;}  // ::1 loopback
            return false;
        }
        if ((g[0] & 0xfe00) == 0xfc00) {return true;}  // fc00::/7 unique local
        if ((g[0] & 0xffc0) == 0xfe80) {return true;}  // fe80::/10 link-local
        if ((g[0] & 0xff00) == 0xff00) {return true;}  // ff00::/8 multicast
        return false;
    }

    /**
     *  Determine whether an IPv4 host is in a blocked non-routable block:
     *  loopback, "this network", RFC 1918 private, link-local, carrier-grade
     *  NAT, benchmarking, documentation, multicast, and reserved space.
     *
     *  <p>A host made only of digits and dots that is not a strict dotted quad
     *  (127.1, 2130706433) is an ambiguous address literal rather than a name,
     *  so it is blocked too; such a host cannot be a resolvable name, as no
     *  top-level label may be all-numeric.</p>
     *
     *  @param host the IPv4 host, lowercase, no brackets, may be null
     *  @return true if blocked
     *  @since 0.9.71+
     */
    static boolean isBlockedIPv4Address(String host) {
        if (host == null || host.isEmpty()) {return true;}
        boolean numeric = true;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {numeric = false; break;}
        }
        if (!numeric) {return false;}
        int[] o = parseDottedQuad(host);
        if (o == null) {return true;}
        int b0 = o[0], b1 = o[1];
        return b0 == 0 ||                 // 0.0.0.0/8 this network
               b0 == 10 ||                // 10.0.0.0/8
               b0 == 127 ||               // 127.0.0.0/8 loopback
               (b0 == 100 && b1 >= 64 && b1 <= 127) ||   // 100.64.0.0/10 CGNAT
               (b0 == 169 && b1 == 254) ||               // 169.254.0.0/16 link-local
               (b0 == 172 && b1 >= 16 && b1 <= 31) ||    // 172.16.0.0/12
               (b0 == 192 && b1 == 0 && o[2] == 0) ||     // 192.0.0.0/24 protocol
               (b0 == 192 && b1 == 0 && o[2] == 2) ||     // 192.0.2.0/24 documentation
               (b0 == 192 && b1 == 168) ||               // 192.168.0.0/16
               (b0 == 198 && (b1 == 18 || b1 == 19)) ||   // 198.18.0.0/15 benchmarking
               (b0 == 198 && b1 == 51 && o[2] == 100) ||  // 198.51.100.0/24 documentation
               (b0 == 203 && b1 == 0 && o[2] == 113) ||   // 203.0.113.0/24 documentation
               b0 >= 224;                  // 224.0.0.0/4 multicast and reserved
    }

    /**
     *  Parse a strict dotted quad into four octets.
     *
     *  @param host lowercase host, digits and dots
     *  @return the octets, or null if not exactly four in-range groups
     *  @since 0.9.71+
     */
    private static int[] parseDottedQuad(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {return null;}
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) {return null;}
            int v = 0;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') {return null;}
                v = v * 10 + (c - '0');
            }
            if (v > 255) {return null;}
            o[i] = v;
        }
        return o;
    }

    /**
     *  Parse an IPv6 address into its eight 16-bit groups, expanding "::"
     *  and converting an embedded IPv4 tail.
     *
     *  @param s the address, lowercase, without brackets or zone id
     *  @return the groups, or null if the address does not parse
     *  @since 0.9.71+
     */
    private static int[] parseIPv6Groups(String s) {
        if (s.isEmpty()) {return null;}
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            int colon = s.lastIndexOf(':', dot);
            if (colon < 0) {return null;}
            int[] o = parseDottedQuad(s.substring(colon + 1));
            if (o == null) {return null;}
            s = s.substring(0, colon + 1) + Integer.toHexString((o[0] << 8) | o[1]) +
                ":" + Integer.toHexString((o[2] << 8) | o[3]);
        }
        int dbl = s.indexOf("::");
        if (dbl != s.lastIndexOf("::")) {return null;}
        String head = dbl >= 0 ? s.substring(0, dbl) : s;
        String tail = dbl >= 0 ? s.substring(dbl + 2) : "";
        String[] hp = head.isEmpty() ? new String[0] : head.split(":", -1);
        String[] tp = tail.isEmpty() ? new String[0] : tail.split(":", -1);
        if (dbl < 0) {
            if (hp.length != 8) {return null;}
        } else if (hp.length + tp.length > 7) {
            return null;
        }
        int[] g = new int[8];
        for (int i = 0; i < hp.length; i++) {
            int v = parseHexGroup(hp[i]);
            if (v < 0) {return null;}
            g[i] = v;
        }
        int i = 8 - tp.length;
        for (int j = 0; j < tp.length; j++, i++) {
            int v = parseHexGroup(tp[j]);
            if (v < 0) {return null;}
            g[i] = v;
        }
        return g;
    }

    /**
     *  Parse one IPv6 group of up to four hex digits.
     *
     *  @param s the group text
     *  @return the value, or -1 if empty, too long, or non-hex
     *  @since 0.9.71+
     */
    private static int parseHexGroup(String s) {
        if (s.isEmpty() || s.length() > 4) {return -1;}
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            int d = Character.digit(s.charAt(i), 16);
            if (d < 0) {return -1;}
            v = (v << 4) | d;
        }
        return v;
    }

    /**
     *  The Base 64 of 40 bytes: (now, sha256 of (now, proxy nonce))
     *
     * @return the nonce
     */
    private String getNonce() {
        byte[] b = new byte[DataHelper.DATE_LENGTH + PROXYNONCE_BYTES];
        byte[] n = new byte[NONCE_BYTES];
        long now = _context.clock().now();
        DataHelper.toLong(b, 0, DataHelper.DATE_LENGTH, now);
        System.arraycopy(_proxyNonce, 0, b, DataHelper.DATE_LENGTH, PROXYNONCE_BYTES);
        System.arraycopy(b, 0, n, 0, DataHelper.DATE_LENGTH);
        byte[] sha256 = PasswordManager.sha256Sum(b);
        System.arraycopy(sha256, 0, n, DataHelper.DATE_LENGTH, SHA256_BYTES);
        String rv = Base64.encode(n);
        _nonces.putIfAbsent(rv, new NonceInfo(now + MAX_NONCE_AGE));
        return rv;
    }

    /**
     *  Verify the Base 64 of 40 bytes: (now, sha256 of (now, proxy nonce))
     *  and the nonce count.
     *  @param b64 nonce non-null
     *  @param ncs nonce count string non-null
     *
     */
    private AuthResult verifyNonce(String b64, String ncs) {
        if (_nonceCleanCounter.incrementAndGet() % 16 == 0) {cleanNonces();}
        byte[] n = Base64.decode(b64);
        if (n == null || n.length != NONCE_BYTES) {return AuthResult.AUTH_BAD;}
        long now = _context.clock().now();
        long stamp = DataHelper.fromLong(n, 0, DataHelper.DATE_LENGTH);
        if (now - stamp > MAX_NONCE_AGE) {
            _nonces.remove(b64);
            return AuthResult.AUTH_STALE;
        }
        NonceInfo info = _nonces.get(b64);
        if (info == null) {return AuthResult.AUTH_STALE;}
        byte[] b = new byte[DataHelper.DATE_LENGTH + PROXYNONCE_BYTES];
        System.arraycopy(n, 0, b, 0, DataHelper.DATE_LENGTH);
        System.arraycopy(_proxyNonce, 0, b, DataHelper.DATE_LENGTH, PROXYNONCE_BYTES);
        byte[] sha256 = PasswordManager.sha256Sum(b);
        if (!DataHelper.eq(sha256, 0, n, DataHelper.DATE_LENGTH, SHA256_BYTES)) {
            return AuthResult.AUTH_BAD;
        }
        try {
            int nc = Integer.parseInt(ncs, 16);
            return info.isValid(nc);
        } catch (NumberFormatException nfe) {
            return AuthResult.AUTH_BAD;
        }
    }


    /**
     *  Remove expired nonces from map
     *
     */
    private void cleanNonces() {
        long now = _context.clock().now();
        for (Iterator<NonceInfo> iter = _nonces.values().iterator(); iter.hasNext(); ) {
            NonceInfo info = iter.next();
            if (info.getExpires() <= now) {iter.remove();}
        }
    }

    /**
     *  What to send if digest auth fails
     *
     *  @param isStale true if the previous nonce was stale
     *  @return the HTTP error response string
     *
     */
    protected String getAuthError(boolean isStale) {
        boolean isDigest = isDigestAuthRequired();
        StringBuilder buf = new StringBuilder(512);
        buf.append(ERR_AUTH1).append("Proxy-Authenticate: ").append(isDigest ? "Digest" : "Basic")
           .append(" realm=\"" + getRealm() + '"');
        if (isDigest) {
            String nonce = getNonce();
            // RFC 7616 most-preferred first, client accepts first that he supports
            // This is also compatible with eepget < 0.9.56 that will use the last one
            // Do we have a SHA256 hash for any user?
            for (String k : getTunnel().getClientOptions().stringPropertyNames()) {
                if (k.startsWith(PROP_PROXY_DIGEST_PREFIX) &&
                    k.endsWith(PROP_PROXY_DIGEST_SHA256_SUFFIX)) {
                    // SHA-256, RFC 7616
                    buf.append(", nonce=\"").append(nonce).append("\", algorithm=SHA-256, charset=UTF-8, qop=\"auth\"");
                    if (isStale) {buf.append(", stale=true");}
                    buf.append("\r\nProxy-Authenticate: Digest realm=\"").append(getRealm()).append("\"");
                    break;
                }
            }

            buf.append(", nonce=\"").append(nonce).append("\", algorithm=MD5, charset=UTF-8, qop=\"auth\"");
            if (isStale) {buf.append(", stale=true");}
        }
        buf.append("\r\n").append(ERR_AUTH2);
        return buf.toString();
    }

    /**
     *  Modified from LoadClientAppsJob.
     *  All keys are mapped to lower case.
     *  Ref: RFC 2617
     *
     *  @param args non-null
     *
     */
    private static Map<String, String> parseArgs(String args) {
        // moved to EepGet, since it needs this too
        return EepGet.parseAuthArgs(args);
    }

    //////// Error page stuff

    /**
     *  Load the error page header file, e.g. foo => errordir/foo-header_xx.ht for lang xx,
     *  or errordir/foo-header.ht, or the backup byte array on fail.
     *
     *  .ht files must be UTF-8 encoded and use \r\n terminators so the
     *  HTTP headers are conformant.
     *  We can't use FileUtil.readFile() because it strips \r
     *
     *  @param base the error page base name
     *  @param backup fallback string if the file cannot be read
     *  @return non-null
     *  */
    protected String getErrorPage(String base, String backup) {
        return getErrorPage(_context, base, backup);
    }

    /**
     *  Load the error page header file, e.g. foo => errordir/foo-header_xx.ht for lang xx,
     *  or errordir/foo-header.ht, or the backup byte array on fail.
     *
     *  .ht files must be UTF-8 encoded and use \r\n terminators so the
     *  HTTP headers are conformant.
     *  We can't use FileUtil.readFile() because it strips \r
     *
     *  @param ctx the I2P application context
     *  @param base the error page base name
     *  @param backup fallback string if the file cannot be read
     *  @return non-null
     *  */
    protected static String getErrorPage(I2PAppContext ctx, String base, String backup) {
        File errorDir = new File(ctx.getBaseDir(), "docs" + SLASH + "proxy");
        File file = new File(errorDir, base + "-header.ht");
        try {return readFile(ctx, file);}
        catch(IOException ioe) {return backup;}
    }

    /** these strings go in the jar, not the war */
    private static final String BUNDLE_NAME = "net.i2p.i2ptunnel.proxy.messages";

    /**
     *  */
    private static String readFile(I2PAppContext ctx, File file) throws IOException {
        char[] buf = new char[512];
        StringBuilder out = new StringBuilder(2048);
        boolean hasSusiDNS = ctx.portMapper().isRegistered(PortMapper.SVC_SUSIDNS);
        boolean hasI2PTunnel = ctx.portMapper().isRegistered(PortMapper.SVC_I2PTUNNEL);
        if (hasSusiDNS && hasI2PTunnel) {
            try (Reader reader = new TranslateReader(ctx, BUNDLE_NAME, new FileInputStream(file))) {
                int len;
                while((len = reader.read(buf)) > 0) {out.append(buf, 0, len);}
            }
        } else {
            // strip out the addressbook links
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                int len;
                while((len = reader.read(buf)) > 0) {out.append(buf, 0, len);}
            }
            if (!hasSusiDNS) {
                DataHelper.replace(out, "<a href=\"http://127.0.0.1:7657/susidns/index\">_(\"Addressbook\")</a>", "");
            }
            if (!hasI2PTunnel) {
                // there are also a couple in auth-header.ht that aren't worth stripping, for auth only
                DataHelper.replace(out,
                                   "<span class=script>_(\"You may want to {0}retry{1} as this will randomly reselect an outproxy from the pool " +
                                   "you have defined {2}here{3} (if you have more than one configured).\", \"<a href=\\\"javascript:parent.window.location.reload()\\\">\", " +
                                   "\"</a>\", \"<a href=\\\"http://127.0.0.1:7657/i2ptunnel/index.jsp\\\">\", \"</a>\")</span>",
                                   "");
                DataHelper.replace(out,
                                   "<noscript>_(\"You may want to retry as this will randomly reselect an outproxy from the pool you " +
                                   "have defined {0}here{1} (if you have more than one configured).\", \"<a href=\\\"http://127.0.0.1:7657/i2ptunnel/index.jsp\\\">\", " +
                                   "\"</a>\")</noscript>",
                                   "");
                DataHelper.replace(out,
                                   "_(\"If you continue to have trouble you may want to edit your outproxy list {0}here{1}.\", " +
                                   "\"<a href=\\\"http://127.0.0.1:7657/i2ptunnel/edit.jsp?tunnel=0\\\">\", \"</a>\")",
                                   "");
            }
            String s = out.toString();
            out.setLength(0);
            try (Reader reader = new TranslateReader(ctx, BUNDLE_NAME, new StringReader(s))) {
                int len;
                while((len = reader.read(buf)) > 0) {out.append(buf, 0, len);}
            }
        }
        // Do we need to replace http://127.0.0.1:7657 console links in the error page?
        // Get the registered host and port from the PortMapper.
        String url = ctx.portMapper().getConsoleURL();
        if (!url.equals("http://127.0.0.1:7657/")) {
            DataHelper.replace(out, "http://127.0.0.1:7657/", url);
        }
        String rv = out.toString();
        return rv;
    }

    /**
     *  Callback for timeout events.
     *  */
    protected class OnTimeout implements I2PTunnelRunner.FailCallback {
        private final Socket _socket;
        private final OutputStream _out;
        private final String _target;
        private final boolean _usingProxy;
        private final String _wwwProxy;
        private final long _requestId;
        private final String _targetHost;
        private final boolean _isSSL;

        /**
         *  Create a timeout callback.
         *
         *  @param s the client socket
         *  @param out the output stream
         *  @param target the URI for an HTTP request, or the host name for CONNECT
         *  @param usingProxy whether a WWW outproxy is in use
         *  @param wwwProxy the outproxy destination
         *  @param id the request identifier
         */
        public OnTimeout(Socket s, OutputStream out, String target, boolean usingProxy, String wwwProxy, long id) {
            _socket = s;
            _out = out;
            _target = target;
            _usingProxy = usingProxy;
            _wwwProxy = wwwProxy;
            _requestId = id;
            _targetHost = null;
            _isSSL = false;
        }

        /**
         *  Create a timeout callback with host tracking.
         *
         *  @param s the client socket
         *  @param out the output stream
         *  @param target the URI for an HTTP request, or the host name for CONNECT
         *  @param usingProxy whether a WWW outproxy is in use
         *  @param wwwProxy the outproxy destination
         *  @param id the request identifier
         *  @param targetHost if non-null, call noteProxyResult() with this as host
         *  @param isSSL to pass to noteProxyResult(). FALSE for ConnectClient.
         *
         */
        public OnTimeout(Socket s, OutputStream out, String target, boolean usingProxy,
                         String wwwProxy, long id, String targetHost, boolean isSSL) {
            _socket = s;
            _out = out;
            _target = target;
            _usingProxy = usingProxy;
            _wwwProxy = wwwProxy;
            _requestId = id;
            _targetHost = targetHost;
            _isSSL = isSSL;
        }

        /**
         *  @param ex may be null
         */
        public void onFail(Exception ex) {
            if (_usingProxy && _targetHost != null) {
                noteProxyResult(_wwwProxy, _targetHost, _isSSL, false);
            }
            Throwable cause = ex != null ? ex.getCause() : null;
            if (cause != null && cause instanceof I2PSocketException) {
                I2PSocketException ise = (I2PSocketException) cause;
                handleI2PSocketException(ise, _out, _target, _usingProxy, _wwwProxy);
            } else {
                handleClientException(ex, _out, _target, _usingProxy, _wwwProxy, _requestId);
            }
            closeSocket(_socket);
        }
    }

    /**
     *  Callback for successful proxy connections.
     *
     */
    protected class OnProxySuccess implements I2PTunnelRunner.SuccessCallback {
        private final String _proxy;
        private final String _host;
        private final boolean _isSSL;

        /**
         *  Create a proxy success callback.
         *
         *  @param proxy the outproxy destination
         *  @param host the target hostname
         *  @param isSSL FALSE for ConnectClient
         */
        public OnProxySuccess(String proxy, String host, boolean isSSL) {
            _proxy = proxy; _host = host; _isSSL = isSSL;
        }

        /** Record a successful proxy connection. */
        public void onSuccess() {
            noteProxyResult(_proxy, _host, _isSSL, true);
        }
    }

    /**
     *  Handle an exception from a client connection.
     *
     *  @param ex may be null
     *  @param out the output stream for the error response
     *  @param targetRequest the original request URI
     *  @param usingWWWProxy whether a WWW outproxy is in use
     *  @param wwwProxy the outproxy destination
     *  @param requestId the request identifier for logging
     *  */
    protected void handleClientException(Exception ex, OutputStream out, String targetRequest,
                                         boolean usingWWWProxy, String wwwProxy, long requestId) {
        if (out == null) {return;}
        String header;
        if (ex instanceof SocketTimeoutException) {
            header = I2PTunnelHTTPServer.ERR_REQUEST_TIMEOUT;
        } else if (usingWWWProxy) {
            header = getErrorPage(I2PAppContext.getGlobalContext(), "dnfp", ERR_DESTINATION_UNKNOWN);
        } else {
            header = getErrorPage(I2PAppContext.getGlobalContext(), "dnf", ERR_DESTINATION_UNKNOWN);
        }
        try {writeErrorMessage(header, out, targetRequest, usingWWWProxy, wwwProxy);}
        catch (IOException ioe) { /* ignored */ }
        try {out.flush();}
        catch (IOException ioe) { /* ignored */ }
    }

    /**
     *  Generate an error page based on the status code
     *  in our custom exception.
     *
     *  @param ise may be null
     *  @param out the output stream for the error response
     *  @param targetRequest the original request URI
     *  @param usingWWWProxy whether a WWW outproxy is in use
     *  @param wwwProxy the outproxy destination
     *
     */
    protected void handleI2PSocketException(I2PSocketException ise, OutputStream out, String targetRequest,
                                            boolean usingWWWProxy, String wwwProxy) {
        if (out == null) {return;}
        int status = ise != null ? ise.getStatus() : -1;
        String error;
        if (status == MessageStatusMessage.STATUS_SEND_FAILURE_NO_LEASESET) {
            // We won't get this one unless it is treated as a hard failure
            // in streaming. See PacketQueue.java
            error = usingWWWProxy ? "nolsp" : "nols";
        } else if (status == MessageStatusMessage.STATUS_SEND_FAILURE_UNSUPPORTED_ENCRYPTION) {
            error = usingWWWProxy ? "encp" : "enc";
        } else if (status == I2PSocketException.STATUS_CONNECTION_RESET) {
            error = usingWWWProxy ? "resetp" : "reset";
        } else {
            error = usingWWWProxy ? "dnfp" : "dnf";
        }
        String header = getErrorPage(error, ERR_DESTINATION_UNKNOWN);
        String message = ise != null ? ise.getLocalizedMessage() : "unknown error";
        try {writeErrorMessage(header, message, out, targetRequest, usingWWWProxy, wwwProxy);}
        catch(IOException ioe) { /* ignored */ }
        try {out.flush();}
        catch(IOException ioe) { /* ignored */ }
    }

    /**
     *  No jump servers or extra message
     *
     *  @param errMessage the error header
     *  @param out the output stream
     *  @param targetRequest the original request URI
     *  @param usingWWWProxy whether a WWW outproxy is in use
     *  @param wwwProxy the outproxy destination
     *  @throws java.io.IOException on write error
     *
     */
    protected void writeErrorMessage(String errMessage, OutputStream out, String targetRequest,
                                     boolean usingWWWProxy, String wwwProxy) throws IOException {
        writeErrorMessage(errMessage, null, out, targetRequest, usingWWWProxy, wwwProxy, null);
    }

    /**
     *  No extra message
     *
     *  @param errMessage the error header
     *  @param out the output stream
     *  @param targetRequest the original request URI
     *  @param usingWWWProxy whether a WWW outproxy is in use
     *  @param wwwProxy the outproxy destination
     *  @param jumpServers comma- or space-separated list, or null
     *  @throws java.io.IOException on write error
     *  */
    protected void writeErrorMessage(String errMessage, OutputStream out, String targetRequest,
                                     boolean usingWWWProxy, String wwwProxy, String jumpServers) throws IOException {
        writeErrorMessage(errMessage, null, out, targetRequest, usingWWWProxy, wwwProxy, jumpServers);
    }

    /**
     *  No jump servers
     *
     *  @param errMessage the error header
     *  @param extraMessage extra message or null, will be HTML-escaped
     *  @param out the output stream
     *  @param targetRequest the original request URI
     *  @param usingWWWProxy whether a WWW outproxy is in use
     *  @param wwwProxy the outproxy destination
     *  @throws java.io.IOException on write error
     *
     */
    protected void writeErrorMessage(String errMessage, String extraMessage,
                                     OutputStream out, String targetRequest,
                                     boolean usingWWWProxy, String wwwProxy) throws IOException {
        writeErrorMessage(errMessage, extraMessage, out, targetRequest, usingWWWProxy, wwwProxy, null);
    }

    /**
     *  Write the complete error message to the output stream.
     *
     *  @param errMessage the error header
     *  @param extraMessage extra message or null, will be HTML-escaped
     *  @param outs the output stream
     *  @param targetRequest the original request URI
     *  @param usingWWWProxy whether a WWW outproxy is in use
     *  @param wwwProxy the outproxy destination
     *  @param jumpServers comma- or space-separated list, or null
     *  @throws java.io.IOException on write error
     *
     */
    protected void writeErrorMessage(String errMessage, String extraMessage,
                                     OutputStream outs, String targetRequest,
                                     boolean usingWWWProxy, String wwwProxy,
                                     String jumpServers) throws IOException {
        if (outs == null) {return;}
        Writer out = new BufferedWriter(new OutputStreamWriter(outs, StandardCharsets.UTF_8));
        if (targetRequest != null) {
            String uri = DataHelper.escapeHTML(targetRequest);
            errMessage = errMessage.replace("<a href=\"\">", "<a href=\"" + uri + "\">");
            errMessage = errMessage.replace("Could not find the following", "Could not establish a connection to the following");
            out.write(errMessage);
            out.write("<a id=proxyrequest href=\"");
            out.write(uri);
            out.write("\">");
            // Long URLs are handled in CSS
            out.write(decodeIDNURI(uri));
            out.write("</a>");
            if (usingWWWProxy) {
                if (wwwProxy == null) {wwwProxy = "No Outproxy configured";}
                else if (wwwProxy.length() > 30) {wwwProxy = wwwProxy.substring(0,29) + "&hellip;";}
                out.write("<hr><span id=outproxy><b>");
                out.write(_t("HTTP Outproxy"));
                out.write(":</b> <span id=outproxydest>" + wwwProxy + "</span></span><br><br>");
            }
            if (extraMessage != null) {
                out.write("<br><b id=extraMsg>" + DataHelper.escapeHTML(extraMessage) + "</b><br><br>");
            }
            if (jumpServers != null && !jumpServers.isEmpty()) {
                boolean first = true;
                if (uri.startsWith("http://")) {uri = uri.substring(7);}
                if (uri.endsWith("/")) {uri = uri.substring(0, uri.length() - 1);}
                StringTokenizer tok = new StringTokenizer(jumpServers, ", ");
                while(tok.hasMoreTokens()) {
                    String jurl = tok.nextToken();
                    String jumphost;
                    try {
                        URI jURI = new URI(jurl);
                        String proto = jURI.getScheme();
                        jumphost = jURI.getHost();
                        if (proto == null || jumphost == null || !proto.toLowerCase(Locale.US).equals("http")) {
                            continue;
                        }
                        jumphost = jumphost.toLowerCase(Locale.US);
                        if (!jumphost.endsWith(".i2p")) {continue;}
                    } catch(URISyntaxException use) {continue;}
                    // Skip jump servers we don't know
                    if (!jumphost.endsWith(".b32.i2p")) {
                        Destination dest = _context.namingService().lookup(jumphost);
                        if (dest == null) {continue;}
                    }

                    if (first) {
                        first = false;
                        out.write("<br><br>\n<div id=jumplinks>\n<h4>");
                        out.write(_t("Click a link below for an address helper from a jump service"));
                        out.write("</h4>\n");
                    } else {out.write("<br>");}
                    String jhostname = jumphost.replace(".i2p", "").replace(".", "_");
                    out.write("<a id=\"" + jhostname + "\" href=\"");
                    out.write(jurl);
                    out.write(uri);
                    out.write("\">");
                    out.write(jumphost.replace(".i2p", ""));
                    out.write("</a>\n");
                }
                if (!first) {out.write("</div>\n");} // We wrote out the opening <div>
            }
        } else {out.write(errMessage);}
        out.write("</div>\n");
        writeFooter(out);
    }

    /**
     *  Decodes Internationalized Domain Names in a URI for display.
     * <p>
     * This method converts punycode-encoded hostnames (xn--) in a URI
     * back to their Unicode representation for human-readable display.
     * If IDN support is unavailable or the URI contains no punycode,
     * the original URI is returned unchanged.
     * </p>
     *
     * @param uri the URI string that may contain encoded hostnames
     * @return the URI with decoded hostname, or the original URI on error
     *
     */
    private static String decodeIDNURI(String uri) {
        if (!_haveIDN) {return uri;}
        if (!uri.contains("xn--")) {return uri;}
        try {
            URI u = new URI(uri);
            String h = u.getHost();
            String hu = IDN.toUnicode(h, IDN.ALLOW_UNASSIGNED);
            if (hu == null || h.equals(hu)) {return uri;}
            int idx = uri.indexOf(h);
            if (idx < 0) {return uri;}
            return uri.substring(0, idx) + hu + uri.substring(idx + h.length(), uri.length());
         } catch(URISyntaxException use) { /* ignored */ }
         return uri;
    }

    /**
     *  Decode a hostname for display.
     *  Returns original string on any error.
     *
     *  @param host the hostname to decode
     *  @return the decoded hostname, or the original on error
     *
     */
    public static String decodeIDNHost(String host) {
        if (!_haveIDN) {return host;}
        if (!host.contains("xn--")) {return host;}
        return IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED);
    }

    /**
     *  Flushes.
     *
     *  Public only for LocalHTTPServer, not for general use
     *  @param out the output stream to write to
     *  @throws java.io.IOException on write error
     *  */
    public static void writeFooter(OutputStream out) throws IOException {
        out.write(getFooter().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     *  Flushes.
     *
     *  Public only for LocalHTTPServer, not for general use
     *  @param out the writer to write to
     *  @throws java.io.IOException on write error
     *
     */
    public static void writeFooter(Writer out) throws IOException {
        out.write(getFooter());
        out.flush();
    }

    private static String getFooter() {
        // The css is hiding this div for now, but we'll keep it here anyway
        // Tag the strings below for translation if we unhide it.
        return "<style>body{display:block!important;pointer-events:auto!important}</style>\n</body>\n</html>\n";
    }

    /**
     *  Translate
     *
     *  @param key the translation key
     *  @return the translated string
     *  */
    protected String _t(String key) {
        return Translate.getString(key, _context, BUNDLE_NAME);
    }

    /**
     *  Translate with one parameter {0}
     *
     *  @param key the translation key
     *  @param o the parameter to insert
     *  @return the translated string
     *  */
    protected String _t(String key, Object o) {
        return Translate.getString(key, o, _context, BUNDLE_NAME);
    }

    /**
     *  Translate with two parameters {0} and {1}
     *
     *  @param key the translation key
     *  @param o the first parameter to insert
     *  @param o2 the second parameter to insert
     *  @return the translated string
     *  */
    protected String _t(String key, Object o, Object o2) {
        return Translate.getString(key, o, o2, _context, BUNDLE_NAME);
    }
}
