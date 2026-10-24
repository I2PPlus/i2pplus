package net.i2p.router.web;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;

/**
 * Block certain Host headers to prevent DNS rebinding attacks.
 * <p>
 * This Handler wraps the ContextHandlerCollection, which handles
 * all the webapps (not just routerconsole).
 * Therefore, this protects all the webapps.
 *
 * @since 0.9.32
 */
public class HostCheckHandler extends GzipHandler
{
    /** The context reading the settings. */
    private final I2PAppContext _context;
    /** Gives the console's configured addresses. */
    private final PortMapper _portMapper;
    /** Host names the console answers on. */
    private final Set<String> _listenHosts;
    /** Property asking for HTTP requests to be redirected. */
    private static final String PROP_REDIRECT = "routerconsole.redirectToHTTPS";
    /** Property enabling response compression. */
    private static final String PROP_GZIP = "routerconsole.enableCompression";

    /**
     * MUST call {@link #setListenHosts} afterwards.
     *
     * @param ctx the context used to resolve console ports, properties and logging
     */
    public HostCheckHandler(I2PAppContext ctx) {
        super();
        _context = ctx;
        _portMapper = ctx.portMapper();
        _listenHosts = new HashSet<>(8);
        //setMinGzipSize(64*1024);
        // set this super small so we clobber almost everything
        // this has the side effect of faster page loads with less progressive rendering
        // include js so we hit the lightbox snark script -> 26/6K
        setMinGzipSize(512);
        if (_context.getBooleanPropertyDefaultTrue(PROP_GZIP)) {
            setCompressionLevel(6);
            addIncludedMimeTypes(
                                 "application/javascript", "application/x-javascript", "text/javascript",
                                 "application/xhtml+xml", "application/xml", "application/pdf", "text/xml",
                                 "image/svg+xml", "application/x-font-ttf", "application/x-font-truetype",
                                 "font/ttf", "font/otf", "font/woff", "text/css", "text/html", "text/plain"
                                );
        } else {
            // poorly documented, but we must put something in if empty all are matched
            // see IncludeExcludeSet
            addIncludedMimeTypes("text/html");
        }
    }

    /**
     * Set the legal hosts.
     * Not synched. Call this BEFORE starting.
     * If empty, all are allowed.
     *
     * @param hosts contains hostnames or IPs. But we allow all IPs anyway.
     */
    public void setListenHosts(Set<String> hosts) {
        _listenHosts.clear();
        _listenHosts.addAll(hosts);
    }

    /**
     * Block by Host header,
     * redirect HTTP to HTTPS,
     * pass everything else to the delegate.
     */
    @Override
    public void handle(String pathInContext,
                       Request baseRequest,
                       HttpServletRequest httpRequest,
                       HttpServletResponse httpResponse)
         throws IOException, ServletException
    {

        String host = httpRequest.getHeader("Host");
        if (!allowHost(host)) {
            Log log = _context.logManager().getLog(HostCheckHandler.class);
            host = DataHelper.stripHTML(getHost(host));
            String s = "Console request denied.\n" +
                       "    To allow access using the hostname \"" + host + "\",\n" +
                       "    add the line \"" + RouterConsoleRunner.PROP_ALLOWED_HOSTS + '=' + host + "\"\n" +
                       "    to advanced configuration and restart.";
            log.logAlways(Log.WARN, s);
            httpResponse.sendError(403, s);
            baseRequest.setHandled(true);
            return;
        }

        // Validate Origin header for POST requests to prevent CSRF
        if ("POST".equalsIgnoreCase(httpRequest.getMethod())) {
            if (!allowOrigin(httpRequest)) {
                Log log = _context.logManager().getLog(HostCheckHandler.class);
                String origin = httpRequest.getHeader("Origin");
                String reqHost = httpRequest.getHeader("Host");
                log.logAlways(Log.WARN, "POST request denied due to invalid Origin header: " + origin + " (expected matching host: " + reqHost + ")");
                httpResponse.sendError(403, "Invalid Origin header for POST request");
                baseRequest.setHandled(true);
                return;
            }
        }

        // redirect HTTP to HTTPS if available, AND:
        // either 1) PROP_REDIRECT is set to true;
        // or 2) PROP_REDIRECT is unset and the Upgrade-Insecure-Requests request header is set
        // https://w3c.github.io/webappsec-upgrade-insecure-requests/
        if (!httpRequest.isSecure()) {
            int httpsPort = _portMapper.getPort(PortMapper.SVC_HTTPS_CONSOLE);
            if (httpsPort > 0 && httpRequest.getLocalPort() != httpsPort) {
                String redir = _context.getProperty(PROP_REDIRECT);
                if (Boolean.parseBoolean(redir) ||
                    (redir == null && "1".equals(httpRequest.getHeader("Upgrade-Insecure-Requests")))) {
                    sendRedirect(httpsPort, httpRequest, httpResponse);
                    baseRequest.setHandled(true);
                    return;
                }
            }
        }

        if (httpRequest.isSecure()) {
            httpResponse.setHeader("Strict-Transport-Security", "max-age=31536000");
        }

        super.handle(pathInContext, baseRequest, httpRequest, httpResponse);
    }

    /**
     * Should we allow a request with this Host header?
     * <p>
     * ref: https://en.wikipedia.org/wiki/DNS_rebinding
     *
     * @param host the HTTP Host header, null ok
     * @return true if OK
     */
    private boolean allowHost(String host) {
        if (host == null)
            return true;
        // common cases
        if (host.equals("127.0.0.1:7657") ||
            host.equals("localhost:7657") ||
            host.equals("[::1]:7657") ||
            host.equals("127.0.0.1:7667") ||
            host.equals("localhost:7667") ||
            host.equals("[::1]:7667"))
            return true;
        // all allowed?
        if (_listenHosts.isEmpty())
            return true;
        host = getHost(host);
        if (_listenHosts.contains(host))
            return true;
        // allow all IP addresses
        if (Addresses.isIPAddress(host))
            return true;
        return false;
    }

    /**
     * Strip [] and port from a host header.
     *
     * @param host the HTTP Host header non-null
     * @return the host with IPv6 brackets and any port removed
     */
    private static String getHost(String host) {
        if (host.startsWith("[")) {
            host = host.substring(1);
            int brack = host.indexOf(']');
            if (brack >= 0)
                host = host.substring(0, brack);
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0)
                host = host.substring(0, colon);
        }
        return host;
    }

    /**
     * Validate Origin header for POST requests.
     * Allows requests with matching Origin (same-origin), or no Origin header.
     * Rejects cross-origin POST requests to prevent CSRF attacks.
     *
     * @param request the HTTP request
     * @return true if allowed
     */
    private boolean allowOrigin(HttpServletRequest request) {
        return originAllowed(request.getHeader("Origin"),
                             request.getHeader("Host"),
                             request.isSecure());
    }

    /**
     * Decide whether an Origin header is acceptable for a request to the given
     * Host header. Same-origin is allowed, as is a request where both sides are
     * loopback <em>and</em> the ports match; anything else is rejected.
     * <p>
     * Both sides are compared as literal text. Neither header is ever resolved
     * through DNS: a hostname whose A record points at 127.0.0.1 (DNS
     * rebinding) is not loopback here, which is what keeps the localhost
     * allowance from becoming a CSRF bypass. Resolving attacker-supplied host
     * names on the request thread would also hand a slow or hostile resolver
     * control of the Jetty thread pool.
     *
     * @param origin the Origin header value, null or empty for a same-origin
     *               form POST that carries no Origin
     * @param hostHeader the Host header value, "host" or "host:port", IPv6
     *                   addresses bracketed
     * @param secure true when the request arrived over TLS, used only for the
     *               default port when the Host header omits one
     * @return true if the request is allowed
     * @since 0.9.72+
     */
    static boolean originAllowed(String origin, String hostHeader, boolean secure) {
        // Allow if no Origin header (same-origin form POST without Origin)
        if (origin == null || origin.isEmpty()) {
            return true;
        }

        // Allow if Origin: null (from file://, sandbox, or detached context)
        if ("null".equalsIgnoreCase(origin)) {
            return true;
        }

        // Extract origin host:port
        String originHost;
        int originPort;
        if (origin.startsWith("http://")) {
            String rest = origin.substring(7);
            originHost = extractHost(rest);
            originPort = extractPort(rest, 80);
        } else if (origin.startsWith("https://")) {
            String rest = origin.substring(8);
            originHost = extractHost(rest);
            originPort = extractPort(rest, 443);
        } else {
            // Unknown scheme, reject
            return false;
        }

        // Extract request host:port
        if (hostHeader == null) {
            return true;
        }
        String requestHost = extractHost(hostHeader);
        int requestPort = extractPort(hostHeader, secure ? 443 : 80);

        // Allow if origin matches request host
        if (originHost != null && requestHost != null &&
            originHost.equalsIgnoreCase(requestHost) && originPort == requestPort) {
            return true;
        }

        // Also allow localhost variants, compared literally - see the note above.
        // The port must still match: without it, a page on any other local port
        // (another web UI on 8080, say) would be accepted as same-origin against
        // the console, since both sides are then "localhost".
        return originPort == requestPort &&
               isLoopbackName(originHost) && isLoopbackName(requestHost);
    }

    /**
     * True when the host is a textual loopback name: an IPv4 address in 127/8,
     * the IPv6 loopback in its written forms, or "localhost". No DNS lookup is
     * performed, so a hostname that happens to resolve to a loopback address is
     * not accepted.
     *
     * @param host the host to test, null-safe
     * @return true if the name itself denotes the local host
     * @since 0.9.72+
     */
    static boolean isLoopbackName(String host) {
        if (host == null || host.isEmpty())
            return false;
        if (host.equalsIgnoreCase("localhost"))
            return true;
        if (host.startsWith("127."))
            return true;
        // Bracketed form, in case a caller passes "[::1]" rather than "::1"
        if (host.startsWith("[") && host.endsWith("]"))
            host = host.substring(1, host.length() - 1);
        return host.equals("::1") ||
               host.equals("0:0:0:0:0:0:0:1") ||
               host.equals("0:0:0:0:0:0:0:0") ||
               host.equals("::");
    }

    /**
     * Extract host from "host:port" or "[ipv6]:port".
     *
     * @param hostPort the header value to split
     * @return the host without brackets, or the input itself when no port is present
     */
    private static String extractHost(String hostPort) {
        if (hostPort.startsWith("[")) {
            int brack = hostPort.indexOf(']');
            if (brack > 0) {
                return hostPort.substring(1, brack);
            }
        }
        int colon = hostPort.indexOf(':');
        if (colon > 0) {
            return hostPort.substring(0, colon);
        }
        return hostPort;
    }

    /**
     * Extract port from "host:port" or "[ipv6]:port", default to defaultPort if not found.
     *
     * @param hostPort the header value to split
     * @param defaultPort the port to assume when the value carries none
     * @return the port, or -1 if a port was present but is not a number, so a
     *         malformed header fails the comparison instead of throwing
     */
    private static int extractPort(String hostPort, int defaultPort) {
        if (hostPort.startsWith("[")) {
            int brack = hostPort.indexOf(']');
            if (brack > 0) {
                String rest = hostPort.substring(brack + 1);
                if (rest.startsWith(":")) {
                    return parsePort(rest.substring(1));
                }
                return defaultPort;
            }
        }
        int colon = hostPort.indexOf(':');
        if (colon > 0) {
            return parsePort(hostPort.substring(colon + 1));
        }
        return defaultPort;
    }

    /**
     * Parse a port written in a header value.
     *
     * @param s the digits after the colon
     * @return the port, or -1 if it is not a valid port number
     */
    private static int parsePort(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Redirect to HTTPS.
     *
     * @param httpsPort the port to redirect the browser to
     * @param httpRequest the request whose server name, URI and query string form the target
     * @param httpResponse the response given the 307 status and Location header
     * @throws IOException if the response output stream cannot be opened
     * @since 0.9.34
     */
    @SuppressWarnings("PMD.AvoidUnnecessaryStringBuilderCreation")
    private static void sendRedirect(int httpsPort, HttpServletRequest httpRequest,
                                     HttpServletResponse httpResponse) throws IOException {
        StringBuilder buf = new StringBuilder(64);
        buf.append("https://");
        String name = httpRequest.getServerName();
        boolean ipv6 = name.indexOf(':') >= 0 && !name.startsWith("[");
        if (ipv6)
            buf.append('[');
        buf.append(name);
        if (ipv6)
            buf.append(']');
        buf.append(':').append(httpsPort)
           .append(httpRequest.getRequestURI());
        String q = httpRequest.getQueryString();
        if (q != null && q.indexOf('\n') < 0 && q.indexOf('\r') < 0)
            buf.append('?').append(q);
        httpResponse.setHeader("Location", buf.toString());
        httpResponse.setHeader("Vary", "Upgrade-Insecure-Requests");
        httpResponse.setStatus(307);
        httpResponse.getOutputStream().close();
    }
}
