/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLException;
import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

/**
 * HTTP server tunnel that filters headers and provides compression.
 * <p>
 * Extends I2PTunnelServer to filter HTTP headers from client to server,
 * replacing the Host header with configured value. Compresses HTTP message
 * body and sets Content-Encoding: x-i2p-gzip when browser requests
 * Accept-Encoding: x-i2p-gzip.
 */
public class I2PTunnelHTTPServer extends I2PTunnelServer {

    /** all of these in SECONDS */
    public static final int DEFAULT_POST_BAN_TIME = 15*60;
    public static final int DEFAULT_POST_MAX = 16;
    public static final int DEFAULT_POST_TOTAL_BAN_TIME = 10*60;
    public static final int DEFAULT_POST_TOTAL_MAX = 30;
    public static final int DEFAULT_POST_WINDOW = 3*60;
    private static final boolean DEFAULT_KEEPALIVE = true;
    public static final String OPT_POST_BAN_TIME = "postBanTime";
    public static final String OPT_POST_MAX = "maxPosts";
    public static final String OPT_POST_TOTAL_BAN_TIME = "postTotalBanTime";
    public static final String OPT_POST_TOTAL_MAX = "maxTotalPosts";
    public static final String OPT_POST_WINDOW = "postCheckTime";

    public static final String OPT_REJECT_INPROXY = "rejectInproxy";
    public static final String OPT_REJECT_REFERER = "rejectReferer";
    public static final String OPT_REJECT_USER_AGENTS = "rejectUserAgents";
    public static final String OPT_USER_AGENTS = "userAgentRejectList";
    public static final String OPT_KEEPALIVE = "keepalive.i2p";
    public static final String OPT_ADD_RESPONSE_HEADER_ALLOW = "addResponseHeaderAllow";
    public static final String OPT_ADD_RESPONSE_HEADER_CACHE_CONTROL = "addResponseHeaderCacheControl";
    public static final String OPT_ADD_RESPONSE_HEADER_NOSNIFF = "addResponseHeaderNoSniff";
    public static final String OPT_ADD_RESPONSE_HEADER_REFERRER_POLICY = "addResponseHeaderReferrerPolicy";

    /** what Host: should we seem to be to the webserver? */
    private String _spoofHost;

    /** client request headers to remove */
    private static final String HASH_HEADER = "X-I2P-DestHash";
    private static final String DEST64_HEADER = "X-I2P-DestB64";
    private static final String DEST32_HEADER = "X-I2P-DestB32";
    private static final String PROXY_CONN_HEADER = "proxy-connection";
    private static final String PRIORITY_HEADER = "Priority";
    private static final String SEC_GPC_HEADER = "Sec-GPC";
    private static final String X_FORWARDED_HEADER = "X-Forwarded-For";
    private static final String X_REAL_IP_HEADER = "X-Real-Ip";

    /** MUST ALL BE LOWER CASE */
    private static final Set<String> CLIENT_SKIPHEADERS = new HashSet<>();
    private static final Set<String> SERVER_SKIPHEADERS = new HashSet<>();

    /** server response headers to remove */
    private static final String AGE_HEADER = "age"; // possible anonymity implications, informational
    private static final String ALT_SVC_HEADER = "alt-svc"; // superfluous
    private static final String DATE_HEADER = "date";
    private static final String EXPIRES_HEADER = "expires"; // not needed with cache-control max-age, and php inserts erroneous header
    private static final String PRAGMA_HEADER = "pragma"; // obsolete
    private static final String REFERER_HEADER = "referer"; // shouldn't be in response headers
    private static final String SERVER_HEADER = "server";
    private static final String STRICT_TRANSPORT_SECURITY_HEADER = "strict-transport-security"; // superfluous
    private static final String VIA_HEADER = "via"; // possible anonymity implications, informational
    private static final String X_CACHE_HEADER = "x-cache"; // possible anonymity implications, informational
    private static final String X_CACHE_HITS_HEADER = "x-cache-hits"; // possible anonymity implications, informational
    private static final String X_CLOUD_TRACE_CONTEXT_HEADER = "x-cloud-trace-context"; // superfluous
    private static final String X_CONTEXTID_HEADER = "x-contextid";
    private static final String X_GOOG_GENERATION_HEADER = "x-goog-generation"; // superfluous
    private static final String X_GOOG_HASH_HEADER = "x-goog-hash"; // superfluous
    private static final String X_GUPLOADER_UPLOADID_HEADER = "x-guploader-uploadid"; // superfluous
    private static final String X_HACKER_HEADER = "x-hacker"; // Wordpress
    private static final String X_NANANANA_HEADER = "x-nananana"; // wordpress batcache, informational
    private static final String X_PANTHEON_STYX_HOSTNAME_HEADER = "x-pantheon-styx-hostname"; // possible anonymity implications, informational
    private static final String X_POWERED_BY_HEADER = "x-powered-by";
    private static final String X_RUNTIME_HEADER = "x-runtime"; // Rails
    private static final String X_SERVED_BY_HEADER = "x-served-by"; // possible anonymity implications, informational
    private static final String X_STYX_REQ_ID_HEADER = "x-styx-req-id"; // possible anonymity implications, informational
    private static final String X_TIMER_HEADER = "x-timer"; // possible anonymity implications, informational

    // https://httpoxy.org
    private static final String PROXY_HEADER = "proxy";

    static {
        CLIENT_SKIPHEADERS.add(HASH_HEADER.toLowerCase(Locale.US));
        CLIENT_SKIPHEADERS.add(DEST64_HEADER.toLowerCase(Locale.US));
        CLIENT_SKIPHEADERS.add(DEST32_HEADER.toLowerCase(Locale.US));
        CLIENT_SKIPHEADERS.add(PRIORITY_HEADER.toLowerCase(Locale.US));
        CLIENT_SKIPHEADERS.add(PROXY_CONN_HEADER);
        CLIENT_SKIPHEADERS.add(SEC_GPC_HEADER.toLowerCase(Locale.US));
        //CLIENT_SKIPHEADERS.add(X_FORWARDED_HEADER.toLowerCase(Locale.US));
        CLIENT_SKIPHEADERS.add(X_REAL_IP_HEADER.toLowerCase(Locale.US));

        SERVER_SKIPHEADERS.add(AGE_HEADER);
        SERVER_SKIPHEADERS.add(ALT_SVC_HEADER);
        SERVER_SKIPHEADERS.add(DATE_HEADER);
        SERVER_SKIPHEADERS.add(EXPIRES_HEADER);
        SERVER_SKIPHEADERS.add(PRAGMA_HEADER);
        SERVER_SKIPHEADERS.add(PROXY_CONN_HEADER);
        SERVER_SKIPHEADERS.add(PROXY_HEADER);
        SERVER_SKIPHEADERS.add(REFERER_HEADER);
        SERVER_SKIPHEADERS.add(SERVER_HEADER);
        SERVER_SKIPHEADERS.add(STRICT_TRANSPORT_SECURITY_HEADER);
        SERVER_SKIPHEADERS.add(VIA_HEADER);
        SERVER_SKIPHEADERS.add(X_CACHE_HEADER);
        SERVER_SKIPHEADERS.add(X_CACHE_HITS_HEADER);
        SERVER_SKIPHEADERS.add(X_CLOUD_TRACE_CONTEXT_HEADER);
        SERVER_SKIPHEADERS.add(X_CONTEXTID_HEADER);
        SERVER_SKIPHEADERS.add(X_GOOG_GENERATION_HEADER);
        SERVER_SKIPHEADERS.add(X_GOOG_HASH_HEADER);
        SERVER_SKIPHEADERS.add(X_GUPLOADER_UPLOADID_HEADER);
        SERVER_SKIPHEADERS.add(X_HACKER_HEADER);
        SERVER_SKIPHEADERS.add(X_NANANANA_HEADER);
        SERVER_SKIPHEADERS.add(X_PANTHEON_STYX_HOSTNAME_HEADER);
        SERVER_SKIPHEADERS.add(X_POWERED_BY_HEADER);
        SERVER_SKIPHEADERS.add(X_RUNTIME_HEADER);
        SERVER_SKIPHEADERS.add(X_SERVED_BY_HEADER);
        SERVER_SKIPHEADERS.add(X_STYX_REQ_ID_HEADER);
    }

    /** timeout for first request line */
    private static final long HEADER_TIMEOUT = 45*1000;
    /** timeout for the rest of the request headers */
    private static final long HEADER_FINISH_TIMEOUT = HEADER_TIMEOUT;
    private static final long START_INTERVAL = (60 * 1000) * 3;
    private static final int MAX_LINE_LENGTH = 8*1024;
    /** ridiculously long, just to prevent OOM DOS @since 0.7.13 */
    private static final int MAX_HEADERS = 60;
    /** Includes request, just to prevent OOM DOS @since 0.9.20 */
    private static final int MAX_TOTAL_HEADER_SIZE = 32*1024;
    // Does not apply to header reads.
    // We set it to forever so that it won't timeout when sending a large response.
    // The server will presumably have its own timeout implemented for POST
    private static final long DEFAULT_HTTP_READ_TIMEOUT = -1;
    // Set a relatively short timeout for GET/HEAD,
    // and a long failsafe timeout for POST/CONNECT, since the user
    // could be POSTing a massive file
    private static final int SERVER_READ_TIMEOUT_GET = 90*1000;
    private static final int SERVER_READ_TIMEOUT_MEDIUM = 5*60*1000;
    private static final int SERVER_READ_TIMEOUT_POST = 4*60*60*1000;

    private long _startedOn = 0L;
    private ConnThrottler _postThrottler;

    final static String ERR_NOT_FOUND =
         "HTTP/1.1 404 Not Found\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n" +
         "<head><title>404 Not Found</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<body>\n" +
         "<center><h1>404 Not Found</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";

    private final static String ERR_UNAVAILABLE =
         "HTTP/1.1 503 Service Unavailable\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n" +
         "<head><title>503 Service Temporarily Unavailable</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<body>\n" +
         "<center><h1>503 Service Temporarily Unavailable</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";

    private final static String ERR_DENIED =
         "HTTP/1.1 429 Too Many Requests\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Retry-After: 600\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n" +
         "<head><title>429 Too Many Requests</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<body>\n" +
         "<center><h1>429 Too Many Requests</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";

    // TODO https://stackoverflow.com/questions/16022624/examples-of-http-api-rate-limiting-http-response-headers
    final static String ERR_FORBIDDEN =

         "HTTP/1.1 403 Denied\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n"+
         "Cache-Control: no-cache\r\n"+
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n" +
         "<head><title>403 Forbidden</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<body>\n" +
         "<center><h1>403 Forbidden</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";

/*
    private final static String ERR_SSL =
         "HTTP/1.1 503 Service Unavailable\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<html>\n<head><title>503 Service Unavailable</title></head>\n" +
         "<center><h1>503 Service Unavailable (SSL not configured)</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";
*/

    private final static String ERR_REQUEST_URI_TOO_LONG =
         "HTTP/1.1 414 Request URI too long\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n<head><title>414 Request URI Too Long</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<center><h1>414 Request URI too long</h1></center>\n" +
         "<hr>\n" +
         //"<p>The requested URL contains too many characters and cannot be processed.</p>\n" +
         "</body>" +
         "\n</html>";

    private final static String ERR_HEADERS_TOO_LARGE =
         "HTTP/1.1 431 Request header fields too large\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n<head><title>431 Request Header Fields Too Large</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<center><h1>431 Request header fields too large</h1></center>\n" +
         "<hr>\n" +
         //"<p>The request headers submitted by your client are too large and cannot be processed.</p>\n" +
         "</body>\n" +
         "</html>";

    /** @since protected since 0.9.33 for I2PTunnelHTTPClientBase, was private */
    protected final static String ERR_REQUEST_TIMEOUT =
         "HTTP/1.1 408 Request timeout\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n" +
         "<head><meta http-equiv=\"refresh\" content=\"5\"></head>\n" +
         "</html>";

    private final static String ERR_BAD_REQUEST =
         "HTTP/1.1 400 Bad Request\r\n" +
         "Content-Type: text/html; charset=utf-8\r\n" +
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n" +
         "\r\n" +
         "<!doctype html>\n" +
         "<html>\n<head><title>400 Bad Request</title><meta name=color-scheme content=\"light dark\"></head>\n" +
         "<center><h1>400 Bad request (malformed datastream)</h1></center>\n" +
         "<hr>\n" +
         "</body>\n" +
         "</html>";

    public I2PTunnelHTTPServer(InetAddress host, int port, String privData, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    public I2PTunnelHTTPServer(InetAddress host, int port, File privkey, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privkey, privkeyname, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    public I2PTunnelHTTPServer(InetAddress host, int port, InputStream privData, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, privkeyname, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    private void setupI2PTunnelHTTPServer(String spoofHost) {
        _spoofHost = (spoofHost != null && spoofHost.trim().length() > 0) ? spoofHost.trim() : null;
        getTunnel().getContext().statManager().createRateStat("i2ptunnel.httpserver.blockingHandleTime",
                                                              "How long the blocking handle takes to complete",
                                                              "Tunnels [HTTPServer]", new long[] { 60*1000, 10*60*1000, 3*60*60*1000 });
        readTimeout = DEFAULT_HTTP_READ_TIMEOUT;
        _blocklistManager = new BlocklistManager(_log, HTTP_BLOCKLIST_CLIENT_LIMIT);
    }

    @Override
    public void startRunning() {
        super.startRunning();
        // Would be better if this was set when the inbound tunnel becomes alive.
        _startedOn = getTunnel().getContext().clock().now();
        setupPostThrottle();
    }

    /** @since 0.9.9 */
    private void setupPostThrottle() {
        int pp = getIntOption(OPT_POST_MAX, 0);
        int pt = getIntOption(OPT_POST_TOTAL_MAX, 0);
        synchronized(this) {
            if (pp != 0 || pt != 0 || _postThrottler != null) {
                long pw = 1000L * getIntOption(OPT_POST_WINDOW, DEFAULT_POST_WINDOW);
                long pb = 1000L * getIntOption(OPT_POST_BAN_TIME, DEFAULT_POST_BAN_TIME);
                long px = 1000L * getIntOption(OPT_POST_TOTAL_BAN_TIME, DEFAULT_POST_TOTAL_BAN_TIME);
                if (_postThrottler == null)
                    _postThrottler = new ConnThrottler(pp, pt, pw, pb, px, "POST/PUT", _log);
                else
                    _postThrottler.updateLimits(pp, pt, pw, pb, px);
                _postThrottler.start();
            }
        }
    }

    /** @since 0.9.9 */
    private int getIntOption(String opt, int dflt) {
        Properties opts = getTunnel().getClientOptions();
        String o = opts.getProperty(opt);
        if (o != null) {
            try {return Integer.parseInt(o);}
            catch (NumberFormatException nfe) {}
        }
        return dflt;
    }

    /** @since 0.9.61+ */
    private final boolean shouldAddResponseHeaderAllow() {
        Properties opts = getTunnel().getClientOptions();
        boolean addAllowHeader = Boolean.parseBoolean(opts.getProperty(OPT_ADD_RESPONSE_HEADER_ALLOW));
        if (!addAllowHeader) {return false;}
        else {return true;}
    }

    /** @since 0.9.61+ */
    private final boolean shouldAddResponseHeaderCacheControl() {
        Properties opts = getTunnel().getClientOptions();
        boolean addCacheControlHeader = Boolean.parseBoolean(opts.getProperty(OPT_ADD_RESPONSE_HEADER_CACHE_CONTROL));
        if (!addCacheControlHeader) {return false;}
        else {return true;}
    }

    /** @since 0.9.61+ */
    private final boolean shouldAddResponseHeaderReferrerPolicy() {
        Properties opts = getTunnel().getClientOptions();
        boolean addReferrerPolicyHeader = Boolean.parseBoolean(opts.getProperty(OPT_ADD_RESPONSE_HEADER_REFERRER_POLICY));
        if (!addReferrerPolicyHeader) {return false;}
        else {return true;}
    }

    /** @since 0.9.61+ */
    private final boolean shouldAddResponseHeaderNoSniff() {
        Properties opts = getTunnel().getClientOptions();
        boolean addNoSniffPolicyHeader = Boolean.parseBoolean(opts.getProperty(OPT_ADD_RESPONSE_HEADER_NOSNIFF));
        if (!addNoSniffPolicyHeader) {return false;}
        else {return true;}
    }

    /** @since 0.9.9 */
    @Override
    public boolean close(boolean forced) {
        synchronized(this) {
            if (_postThrottler != null) {_postThrottler.stop();}
        }
        return super.close(forced);
    }

    /** @since 0.9.9 */
    @Override
    public void optionsUpdated(I2PTunnel tunnel) {
        if (getTunnel() != tunnel) {return;}
        setupPostThrottle();
        Properties props = tunnel.getClientOptions();
        // see TunnelController.setSessionOptions()
        String spoofHost = props.getProperty(TunnelController.PROP_SPOOFED_HOST);
        _spoofHost = (spoofHost != null && spoofHost.trim().length() > 0) ? spoofHost.trim() : null;
        super.optionsUpdated(tunnel);
    }

    /**
     * Called by the thread pool of I2PSocket handlers
     *
     */
    @Override
    protected void blockingHandle(I2PSocket socket) {
        if (socket == null) {return;}
        Hash peerHash = socket.getPeerDestination() != null ? socket.getPeerDestination().calculateHash() : null;
        if (peerHash == null) {return;}
        String peerB32 = socket.getPeerDestination().toBase32();
        if (_log.shouldDebug()) {
            _log.debug("[HTTPServer] Incoming connection to " + toString().replace("/", "") + " (Port " + socket.getLocalPort() + ")" +
                      "\n* From: " + peerB32 + " on port " + socket.getPort());
        }
        // local is fast, so synchronously. Does not need that many threads.
        try {
            if (socket.getLocalPort() == 443) {
                if (getTunnel().getClientOptions().getProperty("targetForPort.443") == null) {
                    // can't write non-ssl error message
                    // client side already sent 200 to browser
                    try {socket.reset();}
                    catch (IOException ioe) {}
                    return;
                }

                /**
                 * We don't know if this is GET or POST or what, so set a huge timeout
                 * and rely on the server to do the actual timeout
                 */
                socket.setReadTimeout(SERVER_READ_TIMEOUT_POST);
                Socket s = getSocket(socket.getPeerDestination().calculateHash(), 443);
                Runnable t = new I2PTunnelRunner(s, socket, slock, null, null, null, (I2PTunnelRunner.FailCallback) null);
                _clientExecutor.execute(t);
                return;
            }

            long afterAccept = getTunnel().getContext().clock().now();
            int requestCount = 0;
            boolean keepalive = getBooleanOption(OPT_KEEPALIVE, DEFAULT_KEEPALIVE);

            do {
                if (requestCount > 0) {
                    if (_log.shouldDebug()) {_log.debug("[HTTPServer] KeepAlive, awaiting request [#" + requestCount + "]");}
                }

                // The headers _should_ be in the first packet, but may not be, depending on the client-side options

                StringBuilder command = new StringBuilder(128);
                Map<String, List<String>> headers;
                try {
                    /*
                     * Catch specific exceptions thrown, to return a good error to the client.
                     * Add 10s to client-side timeout so the client will timeout first and minimize races.
                     */
                    long timeout = requestCount > 0 ? I2PTunnelHTTPClient.BROWSER_KEEPALIVE_TIMEOUT + 10*1000 : HEADER_TIMEOUT;
                    headers = readHeaders(socket, null, command, CLIENT_SKIPHEADERS, getTunnel().getContext(), timeout);
                } catch (SocketTimeoutException ste) {
                    if (requestCount > 0) {
                        if (_log.shouldDebug())
                             _log.debug("[HTTPServer] Timeout reached awaiting request [#" + requestCount + "]");
                    } else {
                        try {sendError(socket, ERR_REQUEST_TIMEOUT);}
                        catch (IOException ioe) {}
                        if (_log.shouldWarn()) {
                            if (ste.getMessage() != null) {
                                _log.warn("[HTTPServer] Request error: " + ste.getMessage() + " \n* Client: " + peerB32);
                            }
                        }
                    }
                    try {socket.close();}
                    catch (IOException ioe) {}
                    return;
                } catch (EOFException eofe) {
                    if (requestCount > 0) {
                        if (_log.shouldDebug())
                             _log.debug("[HTTPServer] Client closed awaiting request [#" + requestCount + "]");
                    } else {
                        try {sendError(socket, ERR_BAD_REQUEST);}
                        catch (IOException ioe) {}
                        if (_log.shouldWarn()) {
                            if (eofe.getMessage() != null) {
                                _log.warn("[HTTPServer] Request error: " + eofe.getMessage() + " \n* Client: " + peerB32);
                            }
                        }
                    }
                    try {socket.close();}
                    catch (IOException ioe) {}
                    return;
                } catch (LineTooLongException ltle) {
                    try {sendError(socket, ERR_HEADERS_TOO_LARGE);}
                    catch (IOException ioe) {}
                    finally {
                        try {socket.close();}
                        catch (IOException ioe) {}
                    }
                    if (_log.shouldWarn()) {
                        _log.warn("[HTTPServer] Request error: Headers too large \n* Client: " + peerB32);
                    }
                    return;
                } catch (RequestTooLongException rtle) {
                    try {sendError(socket, ERR_REQUEST_URI_TOO_LONG);}
                    catch (IOException ioe) {}
                    finally {
                        try {socket.close();}
                        catch (IOException ioe) {}
                    }
                    if (_log.shouldWarn()) {
                        _log.warn("[HTTPServer] Request error: URI too long \n* Client: " + peerB32);
                    }
                    return;
                } catch (BadRequestException bre) {
                    try {sendError(socket, ERR_BAD_REQUEST);}
                    catch (IOException ioe) {}
                    finally {
                        try {socket.close();}
                        catch (IOException ioe) {}
                    }
                    if (_log.shouldDebug()) {
                        if (bre.getMessage() != null) {
                            _log.warn("[HTTPServer] Request error: " + bre.getMessage() + " \n* Client: " + peerB32);
                        }
                    }
                    return;
                }

                /**
                 *  Block requests to localhost or loopback addresses via hostname,
                 *  attempt to validate non-i2p hostnames, and send client a suitable
                 *  error response where applicable
                 *
                 *  @since 0.9.63+
                 */
                String hostname = null;
                boolean isValidRequest = true;
                boolean isPossibleExploit = false;
                List<String> host = headers.get("Host");

                if (peerB32.length() != 60) {
                    _log.warn("[HTTPServer] Invalid B32 (expected 60 characters, got " + peerB32.length() + ") -> Denying request to [" + hostname + "]" +
                    "\n* Client: " + peerB32);
                    isValidRequest = false;
                    isPossibleExploit = false;
                }

                if (host != null) {
                    hostname = host.get(0);
                    int port = hostname.indexOf(":");
                    if (port != -1) {hostname = hostname.substring(0, port);}
                }
                if (_log.shouldDebug()) {_log.debug("[HTTPServer] Incoming request for: " + hostname + "\n* Client: " + peerB32);}
                if (hostname != null && !hostname.endsWith(".i2p") && !hostname.endsWith(".onion")) {
                    InetAddress address = InetAddress.getByName(hostname);
                    if (address != null) {
                        if (address.isLinkLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()) {
                            if (_log.shouldWarn()) {
                                _log.warn("[HTTPServer] WARNING! Attempt to access localhost or loopback address via [" + hostname + "]" +
                                          " -> Adding dest to clients blocklist file \n* Client: " + peerB32);
                            }
                            _blocklistManager.logBlockedDestination(peerB32);
                            isValidRequest = false;
                            isPossibleExploit = true;
                        } else if (address.isAnyLocalAddress()) { // check for 0.0.0.0 response (DNS blocking)
                            if (!hostname.equals("::") || !hostname.equals("0.0.0.0")) {
                                if (_log.shouldWarn()) {
                                    _log.warn("[HTTPServer] DNS server appears to be blocking requests to " + hostname +
                                              " -> Sending Error 403 \n* Client: " + peerB32);
                                }
                                sendError(socket, ERR_FORBIDDEN);
                                isValidRequest = false;
                                isPossibleExploit = false;
                            } else {
                                sendError(socket, ERR_FORBIDDEN);
                                isValidRequest = false;
                                isPossibleExploit = true;
                            }
                        } else {
                            if (_log.shouldInfo() && !hostname.equals(address.getHostAddress())) {
                                _log.info("[HTTPServer] Hostname " + hostname + " validated" +
                                          " -> Resolves to: " + address.getHostAddress());
                            }
                            isPossibleExploit = false;
                        }
                    } else {
                        if (_log.shouldWarn()) {
                            _log.warn("[HTTPServer] Could not resolve " + hostname + " to IP address" +
                                      " -> Sending Error 404 \n* Client: " + peerB32);
                        }
                        sendError(socket, ERR_NOT_FOUND);
                        isValidRequest = false;
                        isPossibleExploit = false;
                    }
                    if (isPossibleExploit) {
                        _blocklistManager.logBlockedDestination(peerB32);
                        if (_log.shouldWarn()) {
                            _log.warn("[HTTPServer] Client attempted to access private or wildcard address " + hostname +
                                      " -> Sending Error 403 and adding to blocklist \n* Client: " + peerB32);
                        }
                    }
                    if (!isValidRequest && socket != null) {
                        try {socket.close();}
                        catch (IOException e) {}
                    }
                }

                long afterHeaders = getTunnel().getContext().clock().now();
                Properties opts = getTunnel().getClientOptions();

                if (Boolean.parseBoolean(opts.getProperty(OPT_REJECT_INPROXY)) &&
                    (headers.containsKey("X-Forwarded-For") ||
                     headers.containsKey("X-Forwarded-Server") ||
                     headers.containsKey("Forwarded") ||  // RFC 7239
                     headers.containsKey("X-Forwarded-Host"))) {
                    if (_log.shouldWarn()) {
                        StringBuilder buf = new StringBuilder();
                        buf.append("[HTTPServer] Refusing Inproxy access \n* Client: ").append(peerB32);
                        List<String> h = headers.get("X-Forwarded-For");
                        if (h != null)
                            buf.append("\n* X-Forwarded-For: ").append(h.get(0));
                        h = headers.get("X-Forwarded-Server");
                        if (h != null)
                            buf.append("\n* X-Forwarded-Server: ").append(h.get(0));
                        h = headers.get("X-Forwarded-Host");
                        if (h != null)
                            buf.append("\n* X-Forwarded-Host: ").append(h.get(0));
                        h = headers.get("Forwarded");
                        if (h != null)
                            buf.append("\n* Forwarded: ").append(h.get(0));
                        _log.warn(buf.toString());
                    }
                    // Send a 403, so the user doesn't get an HTTP Proxy error message
                    // and blame his router or the network.
                    try {sendError(socket, ERR_FORBIDDEN);}
                    catch (IOException ioe) {}
                    try {socket.close();}
                    catch (IOException ioe) {}
                    return;
                }

                if (Boolean.parseBoolean(opts.getProperty(OPT_REJECT_REFERER))) {
                    // reject absolute URIs only
                    List<String> h = headers.get("Referer");
                    if (h != null) {
                        String referer = h.get(0);
                        if (referer.length() > 9) {
                            // "Referer: "
                            referer = referer.substring(9);
                            if (referer.startsWith("http://") || referer.startsWith("https://")) {
                                if (_log.shouldWarn()) {
                                    _log.warn("[HTTPServer] Refusing access (Bad referer) \n* Client: " + peerB32 +
                                              "\n* Referer: " + referer);
                                }
                                try {sendError(socket, ERR_FORBIDDEN);}
                                catch (IOException ioe) {}
                                try {socket.close();}
                                catch (IOException ioe) {}
                                return;
                            }
                        }
                    }
                }

                if (Boolean.parseBoolean(opts.getProperty(OPT_REJECT_USER_AGENTS))) {
                    if (headers != null && headers.containsKey("User-Agent")) {
                        String ua = headers.get("User-Agent").get(0);
                        if (!ua.startsWith("MYOB")) {
                            String blockAgents = opts.getProperty(OPT_USER_AGENTS);
                            if (blockAgents != null) {
                                String[] agents = DataHelper.split(blockAgents, ",");
                                for (int i = 0; i < agents.length; i++) {
                                    String ag = agents[i].trim();
                                    if (ag.equals("none")) {continue;}
                                    if (ag.length() > 0 && ua.contains(ag)) {
                                        if (_log.shouldWarn()) {
                                            _log.warn("[HTTPServer] Refusing access: Blacklisted User Agent (" + ua + ") \n* Client: " + peerB32);
                                        }
                                        try {sendError(socket, ERR_FORBIDDEN);}
                                        catch (IOException ioe) {}
                                        try {socket.close();}
                                        catch (IOException ioe) {}
                                        return;
                                    }
                                }
                            }
                        }
                    } else {
                        // no user-agent, block if blocklist contains "none"
                        String blockAgents = opts.getProperty(OPT_USER_AGENTS);
                        if (blockAgents != null) {
                            String[] agents = DataHelper.split(blockAgents, ",");
                            for (int i = 0; i < agents.length; i++) {
                                String ag = agents[i].trim();
                                if (ag.equals("none")) {
                                    if (_log.shouldWarn()) {
                                        _log.warn("[HTTPServer] Refusing access: User Agent header is blank \n* Client: " + peerB32);
                                    }
                                    try {sendError(socket, ERR_FORBIDDEN);}
                                    catch (IOException ioe) {}
                                    try {socket.close();}
                                    catch (IOException ioe) {}
                                    return;
                                }
                            }
                        }
                    }
                }

                ConnThrottler postThrottler;
                synchronized(this) {
                    postThrottler = _postThrottler;
                }
                if (postThrottler != null && command.length() >= 5 &&
                    (command.substring(0, 5).toUpperCase(Locale.US).equals("POST ") ||
                     command.substring(0, 4).toUpperCase(Locale.US).equals("PUT "))) {
                    if (postThrottler.shouldThrottle(peerHash)) {
                        if (_log.shouldWarn()) {
                            _log.warn("[HTTPServer] Refusing POST/PUT since peer is throttled \n* Client: " + peerB32);
                        }
                        // Send a 429, so the user doesn't get an HTTP Proxy error message
                        // and blame his router or the network.
                        try {sendError(socket, ERR_DENIED);}
                        catch (IOException ioe) {}
                        try {socket.close();}
                        catch (IOException ioe) {}
                        return;
                    }
                }

                addEntry(headers, HASH_HEADER, peerHash.toBase64());
                addEntry(headers, DEST32_HEADER, peerB32);
                addEntry(headers, DEST64_HEADER, socket.getPeerDestination().toBase64());

                // Port-specific spoofhost
                String spoofHost;
                int ourPort = socket.getLocalPort();
                if (ourPort != 80 && ourPort > 0 && ourPort <= 65535) {
                    String portSpoof = opts.getProperty("spoofedHost." + ourPort);
                    if (portSpoof != null) {spoofHost = portSpoof.trim();}
                    else {spoofHost = _spoofHost;}
                } else {spoofHost = _spoofHost;}
                if (spoofHost != null) {setEntry(headers, "Host", spoofHost);}

                // Force Connection: close, unless websocket
                boolean upgrade = false;
                String conn = getEntryOrNull(headers, "Connection");
                if (conn == null) {setEntry(headers, "Connection", "close");}
                else {
                    String connlc = conn.toLowerCase(Locale.US);
                    if (connlc.contains("upgrade")) {
                        upgrade = true;
                        keepalive = false;
                    } else {
                        if (!connlc.contains("keep-alive")) {keepalive = false;}
                        setEntry(headers, "Connection", "close");
                    }
                }

                // process http_blocklist.txt entries
                if (command.length() > 0) {processBlocklist(socket, command);}

                /**
                 * HTTP Persistent Connections (RFC 2616)
                 * for the I2P socket.
                 * Keep it very simple.
                 * Will be set to false for non-GET/HEAD, non-HTTP/1.1,
                 * Connection: close, InternalSocket,
                 * or after analysis of the response headers in CompressedOutputStream,
                 * or on errors in I2PTunnelRunner.
                 * We do NOT support keepalive on the server socket.
                 */
                String cmd = command.toString().trim();
                boolean isGetOrHead = cmd.startsWith("GET ") || cmd.startsWith("HEAD ");
                if (!cmd.endsWith(" HTTP/1.1") || !isGetOrHead) {keepalive = false;}

                // we keep the enc sent by the browser before clobbering it, since it may have been x-i2p-gzip
                String enc = getEntryOrNull(headers, "Accept-Encoding");
                String altEnc = getEntryOrNull(headers, "X-Accept-Encoding");

                /**
                 *  According to rfc2616 s14.3, this *should* force identity, even if 'identity;q=1, *;q=0' didn't.
                 *  As of 0.9.23, the client passes this header through, and we do the same, so if the server and browser
                 *  can do the compression/decompression, we don't have to setEntry(headers, "Accept-Encoding", "");
                 */

                socket.setReadTimeout(readTimeout);
                Socket s = getSocket(socket.getPeerDestination().calculateHash(), socket.getLocalPort());
                long afterSocket = getTunnel().getContext().clock().now();

                /**
                 *  Instead of i2ptunnelrunner, use something that reads the HTTP request from the socket,
                 *  modifies the headers, sends the request to the server, reads the response headers,
                 *  rewriting to include 'Content-Encoding: x-i2p-gzip' if it was one of the
                 *  Accept-Encoding: values, and gzip the payload
                 */
                boolean allowGZIP = true;
                String val = opts.getProperty(TunnelController.PROP_TUN_GZIP);
                if ((val != null) && (!Boolean.parseBoolean(val))) {allowGZIP = false;}
                if (_log.shouldDebug() && (enc != null || altEnc != null)) {
                    _log.debug("[HTTPServer] Encoding header: " + enc + "/" + altEnc);
                }
                boolean alt = (altEnc != null) && (altEnc.indexOf("x-i2p-gzip") >= 0);
                boolean useGZIP = alt || ( (enc != null) && (enc.indexOf("x-i2p-gzip") >= 0) );
                // Don't pass this on, outproxies should strip so I2P traffic isn't so obvious but they probably don't
                if (alt) {headers.remove("X-Accept-Encoding");}

                String modifiedHeader = HttpHeaderFormatter.formatHeaders(headers, command);
                if (_log.shouldDebug()) {_log.debug("[HTTPServer] Modified headers\n\t" + modifiedHeader);}
                else if (_log.shouldInfo() && !command.toString().toLowerCase().contains("head")) {
                    String compactHeaders = HttpHeaderFormatter.formatHeadersCompact(headers, command);
                    _log.info("[HTTPServer] Received request headers" + compactHeaders);
                }

                boolean compress = allowGZIP && useGZIP;
                //boolean addHeaders = shouldAddResponseHeaders();
                // waiter is set to the return value when the CompressedRequestor is done
                AtomicInteger waiter = keepalive ? new AtomicInteger() : null;
                Runnable t = new CompressedRequestor(s, socket, modifiedHeader, getTunnel().getContext(),
                                                     _log, compress, upgrade, _clientExecutor, keepalive, waiter);
                if (keepalive || isGetOrHead) {t.run();} // run inline
                else {_clientExecutor.execute(t);} // run in the server pool


                long afterHandle = getTunnel().getContext().clock().now();
                if (requestCount == 0) {
                    long timeToHandle = afterHandle - afterAccept;
                    getTunnel().getContext().statManager().addRateData("i2ptunnel.httpserver.blockingHandleTime", timeToHandle);
                    if ((timeToHandle > 1500) && (_log.shouldDebug())) {
                        _log.info("[HTTPServer] Took a while (" + timeToHandle + "ms) to handle the request for " + remoteHost + ':' + remotePort +
                                  "\n* Client: " + peerB32 +
                                  "\n* Tasks: Read headers: " + (afterHeaders-afterAccept) + "ms; " +
                                  "Socket create: " + (afterSocket-afterHeaders) + "ms; " +
                                  "Start runners: " + (afterHandle-afterSocket) + "ms");
                     }
                }
                if (keepalive) { // Since we are now running the CompressedRequestor inline, if keepalive is true, we don't need to wait.
                    if (_log.shouldDebug()) {
                        long timeToWait = getTunnel().getContext().clock().now() - afterAccept;
                        // 0: not done; 1: not keepalive-able response; 2: keepalive
                        String code;
                        switch (waiter.get()) {
                            case 0: code = "Not complete"; break;
                            case 1: code = "Not KeepAlive"; break;
                            case 2: code = "KeepAlive"; break;
                            default: code = "Unknown";
                       }
                       _log.debug("[HTTPServer] Waited " + timeToWait + "ms for response [#" + requestCount + "] to complete -> " + code);
                   }
                    if (waiter.get() != 2)
                        break;
                }

                // go around again
                requestCount++;

            } while (keepalive);

        } catch (SocketException ex) {
            int port = socket.getLocalPort();
            try {
                // Send a 503, so the user doesn't get an HTTP Proxy error message
                // and blame his router or the network.
                sendError(socket, ERR_UNAVAILABLE);
            } catch (IOException ioe) {}
            try {
                socket.close();
            } catch (IOException ioe) {}
            // Don't complain too early, Jetty may not be ready.
            int level = getTunnel().getContext().clock().now() - _startedOn > START_INTERVAL ? Log.ERROR : Log.WARN;
            if (_log.shouldLog(level))
                _log.log(level, "[HTTPServer] Error connecting to HTTP server " + getSocketString(port));
        } catch (IOException ex) {
            try {
                socket.close();
            } catch (IOException ioe) {}
            if (_log.shouldWarn())
                if (ex.getMessage().indexOf("Name or service not known") >= 0) {
                    _log.warn("[HTTPServer] Request error: DNS error (blocked?) for: " +
                              ex.getMessage().replace(": Name or service not known", "") + " \n* Client: " + peerB32);
                } else {
                    _log.warn("[HTTPServer] Request error: " + ex.getMessage() + " \n* Client: " + peerB32);
                }
        } catch (OutOfMemoryError oom) {
            // Often actually a file handle limit problem so we can safely send a response
            // java.lang.OutOfMemoryError: unable to create new native thread
            try {
                // Send a 503, so the user doesn't get an HTTP Proxy error message
                // and blame his router or the network.
                sendError(socket, ERR_UNAVAILABLE);
            } catch (IOException ioe) {}
            try {
                socket.close();
            } catch (IOException ioe) {}
            if (_log.shouldError())
                _log.error("[HTTPServer] Out of Memory error (" + oom.getMessage() + ")");
        }
    }

    /**
     *  Send the message, unless port 443, then just reset
     *  @since 0.9.62
     */
    private static void sendError(I2PSocket socket, String resp) throws IOException {
        if (socket.getLocalPort() == 443) {socket.reset();}
        else {socket.getOutputStream().write(resp.getBytes("UTF-8"));}
    }

    private static class CompressedRequestor implements Runnable {
        private final Socket _webserver;
        private final I2PSocket _browser;
        private final String _headers;
        private final I2PAppContext _ctx;
        // shadows _log in super()
        private final Log _log;
        private final boolean _shouldCompress;
        private final boolean _upgrade;
        private final ThreadPoolExecutor _tpe;
        private boolean _keepalive;
        private final AtomicInteger _waiter;
        //private final boolean _addHeaders;
        private static final int BUF_SIZE = 16*1024;

        /**
         *  @param shouldCompress if false, don't compress, just filter server headers
         *  @param waiter to notify when done, if non-null; will set value to 1: not keepalive-able response, or 2: keepalive
         */
        public CompressedRequestor(Socket webserver, I2PSocket browser, String headers,
                                   I2PAppContext ctx, Log log, boolean shouldCompress, boolean upgrade,
                                   ThreadPoolExecutor tpe, boolean keepalive, AtomicInteger waiter) {
            _webserver = webserver;
            _browser = browser;
            _headers = headers;
            _ctx = ctx;
            _log = log;
            _shouldCompress = shouldCompress;
            _upgrade = upgrade;
            _tpe = tpe;
            _keepalive = keepalive;
            _waiter = waiter;
            //_addHeaders = addHeaders;
        }

        /**
         * This thread handles the response from the server back to the browser.
         * If the request was not GET or HEAD, (typically POST or CONNECT), it spawns another thread
         * "Sender" to push the remaining request data from the browser to the server.
         */
        public void run() {
            OutputStream serverout = null;
            OutputStream browserout = null;
            CompressedResponseOutputStream compressedout = null;
            InputStream browserin = null;
            InputStream serverin = null;
            Sender s = null;
            Sender sender = null;
            IOException ioex = null;
            String host = null;
            String url = null;
            String req = null;
            try {
                serverout = _webserver.getOutputStream();
                serverout.write(DataHelper.getUTF8(_headers));
                browserin = _browser.getInputStream();
                // Don't spin off a thread for this except for POSTs and PUTs and Connection: Upgrade
                // beware interference with Shoutcast, etc.?

                if (_headers != null && !_headers.isEmpty()) {
                    String[] requestLines = _headers.split("\r\n");
                    if (requestLines.length > 0) {
                        String requestLine = requestLines[0];
                        String[] requestParts = requestLine.split(" ");
                        url = requestParts.length > 1 ? requestParts[1] : null;
                        if (url != null) {
                            if (url.startsWith("http://")) {url = url.replace("http://", "");}

                            // Truncate long URLs
                            if (url.length() > 100) {
                                url = url.substring(0, 48) + "..." + url.substring(url.length() - 48);
                            }

                            String[] urlParts = url.split("/");
                            if (urlParts.length > 0) {
                                host = urlParts[0];
                                for (int i = 1; i < urlParts.length; i++) {
                                    if (!urlParts[i].trim().isEmpty()) {
                                        host += "/" + urlParts[i];
                                     }
                                }
                            }
                        }
                    }
                }

                if (host != null && host.contains("b32.i2p")) {host = host.substring(0, 12) + "...b32.i2p";}
                req = host != null ? host : "Unknown request";

                boolean isHead = _headers.startsWith("HEAD ");
                boolean isGet = _headers.startsWith("GET ");
                boolean isPost = _headers.startsWith("POST ");
                if (!(isGet || isHead) || _upgrade || browserin.available() > 0) {  // just in case
                    // Unless this is POST, set a huge
                    // timeout and rely on the server to do the actual timeout
                    _browser.setReadTimeout(isPost ?
                                            SERVER_READ_TIMEOUT_MEDIUM :   // medium
                                            SERVER_READ_TIMEOUT_POST);     // long
                    _keepalive = false;
                    sender = new Sender(serverout, browserin, "from Client -> Server", _log);
                    // run in the unlimited client pool
                    _tpe.execute(sender);
                }
                int timeout = (isGet || isHead) ?
                              SERVER_READ_TIMEOUT_GET :   // short
                              SERVER_READ_TIMEOUT_POST;   // long
                _webserver.setSoTimeout(timeout);
                browserout = _browser.getOutputStream();

                try {serverin = new BufferedInputStream(_webserver.getInputStream(), BUF_SIZE);}
                catch (NullPointerException npe) {throw new IOException("getInputStream NPE");}
                StringBuilder command = new StringBuilder(512);
                // Change headers to protect server identity
                Map<String, List<String>> headers = readHeaders(null, serverin, command, SERVER_SKIPHEADERS, _ctx, timeout);

                SecurityHeaderBuilder.filterCacheControlHeaders(headers);
                SecurityHeaderBuilder.filterSetCookieHeaders(headers);

                List<String> contentTypeList = headers.get("Content-Type");
                String mimeType = "application/octet-stream";
                if (contentTypeList != null && !contentTypeList.isEmpty()) {
                    mimeType = contentTypeList.get(0);
                }

                SecurityHeaderBuilder.addSecurityHeaders(headers, command, mimeType);

                String modifiedHeaders = HttpHeaderFormatter.formatHeaders(headers, command);
                // after the headers, set a short timeout
                _webserver.setSoTimeout(SERVER_READ_TIMEOUT_GET);

                if (_shouldCompress) {
                    compressedout = new CompressedResponseOutputStream(browserout, _keepalive);
                    compressedout.write(DataHelper.getUTF8(modifiedHeaders));
                    s = new Sender(compressedout, serverin, "Server -> Client (Gzip) " +
                                   (req != null && !req.equals("") && !req.equals("Unknown request") ? "\n* URL: " + req : ""), _log);
                    browserout = compressedout;
                } else {
                    browserout.write(DataHelper.getUTF8(modifiedHeaders));
                    s = new Sender(browserout, serverin, "Server -> Client " +
                                   (req != null && !req.equals("") && !req.equals("Unknown request") ? "\n* URL: " + req : ""), _log);
                }
                if (_log.shouldDebug())
                    _log.debug("[HTTPServer] Running server-to-browser Compressed? " + _shouldCompress + " KeepAlive? " + _keepalive +
                               (req != null && !req.equals("") && !req.equals("Unknown request") ? "\n* URL: " + req : ""));
                s.run(); // same thread
            } catch (SSLException she) {
                if (_log.shouldError()) {_log.error("[HTTPServer] SSL error", she);}
                try {
                    if (_browser.getLocalPort() == 443) {_browser.reset();}
                    else {
                        if (browserout == null) {browserout = _browser.getOutputStream();}
                        browserout.write(ERR_UNAVAILABLE.getBytes("UTF-8"));
                    }
                } catch (IOException ioe) {}
                _keepalive = false;
            } catch (IOException ioe) {
                if (_log.shouldWarn()) {
                    _log.warn("[HTTPServer] Error compressing -> " + ioe.getMessage()  +
                              (req != null && !req.equals("") && !req.equals("Unknown request") ? "\n* URL: " + req : ""));
                }
                ioex = ioe;
                _keepalive = false;
            } finally {
                if (ioex == null && s != null) {
                    ioex = s.getFailure();
                    if (ioex == null && sender != null) {ioex = sender.getFailure();}
                }
                if (ioex != null) {
                    _keepalive = false;
                    // Reset propagation, simplified from I2PTunnelRunner
                    boolean i2pReset = false;
                    if (ioex instanceof I2PSocketException) {
                        I2PSocketException ise = (I2PSocketException) ioex;
                        int status = ise.getStatus();
                        i2pReset = status == I2PSocketException.STATUS_CONNECTION_RESET;
                        if (i2pReset) {
                            if (_log.shouldDebug()) {
                                _log.warn("[HTTPServer] Received I2P RESET -> Resetting socket..." +
                                          (req != null && !req.equals("") && !req.equals("Unknown request") ? "\n* URL: " + req : ""));
                            }
                            try {_webserver.setSoLinger(true, 0);}
                            catch (IOException ioe) {}
                        }
                    }
                    if (!i2pReset && ioex instanceof SocketException) {
                        String msg = ioex.getMessage();
                        boolean sockReset = msg != null && msg.contains("reset");
                        if (sockReset) {
                            if (_log.shouldDebug()) {
                                _log.warn("[HTTPServer] Received socket RESET ->  Resetting I2P socket..." +
                                          (req != null && !req.equals("") && !req.equals("Unknown request") ? "\n* URL: " + req : ""));
                            }
                            try {_browser.reset();}
                            catch (IOException ioe) {}
                        }
                    }
                }
                if (_waiter != null) {_waiter.set(_keepalive ? 2 : 1);} // We are now run inline, no need to notify()
                if (browserout != null) {
                    try {
                        if (_keepalive) {
                            if (compressedout != null) {compressedout.finish();}
                            else {browserout.flush();}
                        } else {browserout.close();}
                    } catch (IOException ioe) {}
                }
                if (serverout != null) try { serverout.close(); } catch (IOException ioe) {}
                if (!_keepalive && browserin != null) try { browserin.close(); } catch (IOException ioe) {}
                if (serverin != null) try { serverin.close(); } catch (IOException ioe) {}
                try { _webserver.close(); } catch (IOException ioe) {}
                if (!_keepalive) try { _browser.close(); } catch (IOException ioe) {}
                if (_log.shouldDebug()) {
                    _log.debug("Finished server-to-browser: Compressed? " + _shouldCompress + " KeepAlive? " + _keepalive +
                               (req != null && !req.equals("") && !req.equals("Unknown request") ? "\n* URL: " + req : ""));
                }
            }
        }
    }

    /** @since 0.9.63+ */
    private static synchronized String getHostFromHeaders(String headers) {
        return HttpHeaderFormatter.getHostFromHeaders(headers);
    }

    private static class Sender implements Runnable {
        private final OutputStream _out;
        private final InputStream _in;
        private final String _name;
        // shadows _log in super()
        private final Log _log;
        private IOException _failure;

        /**
         *  Caller MUST close streams
         */
        public Sender(OutputStream out, InputStream in, String name, Log log) {
            _out = out;
            _in = in;
            _name = name;
            _log = log;
        }

        public void run() {
            if (_log.shouldDebug()) {_log.debug("[HTTPServer] Begin sending " + _name);}
            try {
                DataHelper.copy(_in, _out);
                if (_log.shouldDebug()) {_log.debug("[HTTPServer] Done sending " + _name);}
            } catch (IOException ioe) {
                if (ioe.getMessage() != null) {
                    if (ioe.getMessage().indexOf("Input stream closed") >= 0 ||
                        ioe.getMessage().indexOf("Input stream error") >= 0 ||
                        ioe.getMessage().indexOf("Socket closed") >= 0) {
                        // client closed connection early?
                            if (_log.shouldDebug()) {_log.debug("[HTTPServer] Error sending " + _name + " -> " + ioe.getMessage());}
                    } else {
                        if (_log.shouldWarn()) {_log.warn("[HTTPServer] Error sending " + _name + " -> " + ioe.getMessage());}
                    }
                }
                synchronized(this) {_failure = ioe;}
            }
        }

        /**
         *  @since 0.9.33
         */
        public synchronized IOException getFailure() {
            return _failure;
        }
    }

    /**
     *  This plus a typ. HTTP response header will fit into a 1730-byte streaming message.
     */
    private static final int MIN_TO_COMPRESS = 1024;

    private static class CompressedResponseOutputStream extends HTTPResponseOutputStream {
        private InternalGZIPOutputStream _gzipOut;

        public CompressedResponseOutputStream(OutputStream o, boolean keepalive) {
            super(o, false, keepalive, false, null);
        }

        /**
         *  Overridden to peek at response code. Always returns line.
         *  Finish gzipping but don't close the output stream,
         *  if keepalive is true.
         *
         *  @since 0.9.62
         */
        public void finish() throws IOException {
            if (getKeepAliveOut()) {
                if (_gzipOut != null) {_gzipOut.finish();}
                else {flush();}
            } else {close();}
        }

        /**
         *  Don't compress small responses or images.
         *  Don't compress things that are already compressed.
         *  Compression is inline, and decompression happens on the client side,
         *  but it's still CPU.
         */
        @Override
        protected boolean shouldCompress() {
            return (_dataExpected < 0 || _dataExpected >= MIN_TO_COMPRESS) &&
                   // must be null as we write the header in finishHeaders(), can't have two
                   (_contentEncoding == null) &&
                   (_contentType == null ||
                    ((!_contentType.startsWith("audio/")) &&
                     (!_contentType.equals("image/gif")) &&
                     (!_contentType.equals("image/jpeg")) &&
                     (!_contentType.equals("image/jpg")) &&
                     (!_contentType.equals("image/png")) &&
                     (!_contentType.equals("image/tiff")) &&
                     (!_contentType.equals("image/webp")) &&
                     (!_contentType.equals("font/woff2")) &&
                     (!_contentType.startsWith("video/")) &&
                     (!_contentType.equals("application/compress")) &&
                     (!_contentType.equals("application/bzip2")) &&
                     (!_contentType.equals("application/gzip")) &&
                     (!_contentType.equals("application/x-bzip")) &&
                     (!_contentType.equals("application/x-bzip2")) &&
                     (!_contentType.equals("application/x-gzip")) &&
                     (!_contentType.equals("application/zip"))));
        }

        @Override
        protected void finishHeaders() throws IOException {
            // TODO if browser supports gzip, send as gzip
            if (shouldCompress())
                out.write(DataHelper.getASCII("Content-Encoding: x-i2p-gzip\r\n"));
            super.finishHeaders();
        }

        @Override
        protected void beginProcessing() throws IOException {
            //if (_log.shouldInfo())
            //    _log.info("Beginning compression processing");
            //out.flush();
            if (shouldCompress()) {
                _gzipOut = new InternalGZIPOutputStream(out);
                out = _gzipOut;
            }
        }

        public long getTotalRead() {
            InternalGZIPOutputStream gzipOut = _gzipOut;
            if (gzipOut != null) {return gzipOut.getTotalRead();}
            else {return 0;}
        }

        public long getTotalCompressed() {
            InternalGZIPOutputStream gzipOut = _gzipOut;
            if (gzipOut != null) {return gzipOut.getTotalCompressed();}
            else {return 0;}
        }
    }

    /** just a wrapper to provide stats for debugging */
    private static class InternalGZIPOutputStream extends GZIPOutputStream {
        public InternalGZIPOutputStream(OutputStream target) throws IOException {super(target);}
        public long getTotalRead() {
            try {return def.getTotalIn();}
            // j2se 1.4.2_08 on linux is sometimes throwing an NPE in the getTotalIn() implementation
            catch (RuntimeException e) {return 0;}
        }
        public long getTotalCompressed() {
            try {return def.getTotalOut();}
            // j2se 1.4.2_08 on linux is sometimes throwing an NPE in the getTotalOut() implementation
            catch (RuntimeException e) {return 0;}
        }
    }

    /**
     *  @return the command followed by the header lines
     */
    protected static String formatHeaders(Map<String, List<String>> headers, StringBuilder command) {
        return HttpHeaderFormatter.formatHeaders(headers, command);
    }

    /**
     *  @return the command followed by the header lines (compact version for logging)
     *
     *  @since 0.9.63+
     */
    protected static String formatHeadersCompact(Map<String, List<String>> headers, StringBuilder command) {
        return HttpHeaderFormatter.formatHeadersCompact(headers, command);
    }

    /**
     * Add an entry to the multimap.
     */
    static void addEntry(Map<String, List<String>> headers, String key, String value) {
        List<String> entry = headers.get(key);
        if (entry == null) {headers.put(key, entry = new ArrayList<String>(1));}
        entry.add(value);
    }

    /**
     * Remove the other matching entries and set this entry as the only one.
     */
    static void setEntry(Map<String, List<String>> headers, String key, String value) {
      List<String> entry = headers.get(key);
      if (entry == null) {headers.put(key, entry = new ArrayList<String>(1));}
      else {entry.clear();}
      entry.add(value);
    }

    /**
     * Get the first matching entry in the multimap
     * @return the first matching entry or null
     */
    private static String getEntryOrNull(Map<String, List<String>> headers, String key) {
      List<String> entries = headers.get(key);
      if(entries == null || entries.size() < 1) {return null;}
      else {return entries.get(0);}
    }

    /**
     *  From I2P to server: socket non-null, in null.
     *  From server to I2P: socket null, in non-null.
     *
     *  Note: This does not handle RFC 2616 header line splitting,
     *  which is obsoleted in RFC 7230.
     *
     *  @param socket if null, use in as InputStream
     *  @param in if null, use socket.getInputStream() as InputStream
     *  @param command out parameter, first line
     *  @param skipHeaders MUST be lower case
     *  @throws SocketTimeoutException if timeout is reached before newline
     *  @throws EOFException if EOF is reached before newline
     *  @throws LineTooLongException if one header too long, or too many headers, or total size too big
     *  @throws RequestTooLongException if too long
     *  @throws BadRequestException on bad headers
     *  @throws IOException on other errors in the underlying stream
     *  @since public since 0.9.57 for SOCKS
      */
    public static Map<String, List<String>> readHeaders(I2PSocket socket, InputStream in, StringBuilder command,
                                                        Set<String> skipHeaders, I2PAppContext ctx, long initialTimeout) throws IOException {
        return readHeadersInternal(socket, in, command, skipHeaders, ctx, initialTimeout);
    }

    /**
     *  @since public since 0.9.57 for SOCKS
      */
    public static Map<String, List<String>> readHeaders(I2PSocket socket, InputStream in, StringBuilder command,
                                                        String[] skipHeaders, I2PAppContext ctx, long initialTimeout) throws IOException {
        return readHeadersInternal(socket, in, command, new HashSet<>(Arrays.asList(skipHeaders)), ctx, initialTimeout);
    }

    private static Map<String, List<String>> readHeadersInternal(I2PSocket socket, InputStream in, StringBuilder command,
                                                        Set<String> skipHeaders, I2PAppContext ctx, long initialTimeout) throws IOException {
        HashMap<String, List<String>> headers = new HashMap<String, List<String>>();
        StringBuilder buf = new StringBuilder(128);

        // slowloris / darkloris
        long expire = ctx.clock().now() + initialTimeout + HEADER_FINISH_TIMEOUT;
        if (socket != null) {
            try {
                readLine(socket, command, initialTimeout);
            } catch (LineTooLongException ltle) {
                // convert for first line
                throw new RequestTooLongException("Request too long (Max allowed: " + MAX_LINE_LENGTH + ")");
            }
        } else {
             boolean ok = DataHelper.readLine(in, command);
             if (!ok)
                 throw new EOFException("EOF reached before the end of the headers");
        }

        //if (_log.shouldDebug())
        //    _log.debug("Read the http command [" + command.toString() + "]");

        int totalSize = command.length();
        int i = 0;
        while (true) {
            if (++i > MAX_HEADERS) {
                throw new LineTooLongException("Too many header lines (Max allowed: " + MAX_HEADERS + ")");
            }
            buf.setLength(0);
            if (socket != null) {
                readLine(socket, buf, expire - ctx.clock().now());
            } else {
                 boolean ok = DataHelper.readLine(in, buf);
                 if (!ok)
                     throw new BadRequestException("EOF reached before the end of the headers");
            }
            if ( (buf.length() == 0) ||
                 ((buf.charAt(0) == '\n') || (buf.charAt(0) == '\r')) ) {
                // end of headers reached
                return headers;
            } else {
                if (ctx.clock().now() > expire) {
                    throw new SocketTimeoutException("Timeout (" + (initialTimeout + HEADER_FINISH_TIMEOUT / 1000) + "s) receiving headers");
                }
                int split = buf.indexOf(":");
                if (split <= 0)
                    throw new BadRequestException("Invalid HTTP header, missing colon: \"" + buf + "\" request: \"" + command + '"');
                totalSize += buf.length();
                if (totalSize > MAX_TOTAL_HEADER_SIZE)
                    throw new LineTooLongException("Request + headers too big");
                String name = buf.substring(0, split).trim();
                String value = null;
                if (buf.length() > split + 1) {value = buf.substring(split+1).trim();} // ":"
                else {value = "";}

                String lcName = name.toLowerCase(Locale.US);
                if ("accept-encoding".equals(lcName)) {name = "Accept-Encoding";}
                else if ("x-accept-encoding".equals(lcName)) {name = "X-Accept-Encoding";}
                else if ("x-forwarded-for".equals(lcName)) {name = "X-Forwarded-For";}
                else if ("x-forwarded-server".equals(lcName)) {name = "X-Forwarded-Server";}
                else if ("x-forwarded-host".equals(lcName)) {name = "X-Forwarded-Host";}
                else if ("forwarded".equals(lcName)) {name = "Forwarded";}
                else if ("user-agent".equals(lcName)) {name = "User-Agent";}
                else if ("referer".equals(lcName)) {name = "Referer";}
                else if ("connection".equals(lcName)) {name = "Connection";}
                else if ("host".equals(lcName)) {name = "Host";}
                else if (lcName.contains("-encoding") && !lcName.contains("accept")) {
                    if (socket != null) {
                        try {socket.close();}
                        catch (IOException ioe) {}
                        throw new BadRequestException("Invalid HTTP header: \"" + name + "\" -> Terminating connection...");
                    }
                }
                // For incoming, we remove certain headers to prevent spoofing.
                // For outgoing, we remove certain headers to improve anonymity.
                boolean skip = skipHeaders.contains(lcName);
                if (skip) {continue;}

                addEntry(headers, name, value);
                //if (_log.shouldDebug()) {
                //    _log.debug("Reading headers sent by client [" + peerB32.substring(0,6) + "]" +
                //    "\n* " + name + ": " + value);
                //}
            }
        }
    }

    /**
     *  Read a line terminated by newline, with a total read timeout.
     *
     *  Warning - strips \n but not \r
     *  Warning - 8KB line length limit as of 0.7.13, @throws IOException if exceeded
     *  Warning - not UTF-8
     *
     *  @param buf output
     *  @param timeout throws SocketTimeoutException immediately if zero or negative
     *  @throws SocketTimeoutException if timeout is reached before newline
     *  @throws EOFException if EOF is reached before newline
     *  @throws LineTooLongException if too long
     *  @throws IOException on other errors in the underlying stream
     *  @since 0.9.19 modified from DataHelper
     */
    private static void readLine(I2PSocket socket, StringBuilder buf, long timeout) throws IOException {
        if (timeout <= 0) {throw new SocketTimeoutException();}
        long expires = System.currentTimeMillis() + timeout;
        InputStream in = socket.getInputStream();
        int c;
        int i = 0;
        socket.setReadTimeout(timeout);
        while ((c = in.read()) != -1) {
            if (++i > MAX_LINE_LENGTH) {
                throw new LineTooLongException("Line too long (Maximum characters permitted: " + MAX_LINE_LENGTH + ")");
            }
            if (c == '\n') {break;}
            long newTimeout = expires - System.currentTimeMillis();
            if (newTimeout <= 0) {throw new SocketTimeoutException();}
            buf.append((char)c);
            if (newTimeout != timeout) {
                timeout = newTimeout;
                socket.setReadTimeout(timeout);
            }
        }
        if (c == -1) {
            if (System.currentTimeMillis() >= expires) {throw new SocketTimeoutException();}
            else {throw new EOFException();}
        }
    }

    /** @since 0.9.62+ */
    private static final int HTTP_BLOCKLIST_CLIENT_LIMIT = 512;
    private BlocklistManager _blocklistManager;

    private void processBlocklist(I2PSocket socket, StringBuilder command) throws BadRequestException, IOException {
        if (_blocklistManager == null) {return;}
        if (_blocklistManager.shouldBlockRequest(command)) {
            String matchedString = _blocklistManager.getMatchedBlocklistString(command);
            String peerB32 = socket.getPeerDestination().toBase32();
            _blocklistManager.logBlockedDestination(peerB32);
            try {
                if (socket != null) {
                    try {socket.close();}
                    catch (IOException ioe) {_log.error("[HTTPServer] Error closing socket (" + ioe.getMessage() + ")");}
                    throw new BadRequestException(command.toString() + "-> Matches blocklist entry \"" + matchedString + "\"");
                }
            } catch (BadRequestException bre) {throw bre;}
        }
    }

    /**
     *  @since 0.9.19
     */
    private static class LineTooLongException extends IOException {
        public LineTooLongException(String s) {super(s);}
    }

    /**
     *  @since 0.9.20
     */
    private static class RequestTooLongException extends IOException {
        public RequestTooLongException(String s) {super(s);}
    }

    /**
     *  @since 0.9.20
     */
    private static class BadRequestException extends IOException {
        public BadRequestException(String s) {super(s);}
    }
}
