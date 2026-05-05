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
 *
 * This Handler wraps the ContextHandlerCollection, which handles
 * all the webapps (not just routerconsole).
 * Therefore, this protects all the webapps.
 *
 * @since 0.9.32
 */
public class HostCheckHandler extends GzipHandler
{
    private final I2PAppContext _context;
    private final PortMapper _portMapper;
    private final Set<String> _listenHosts;
    private static final String PROP_REDIRECT = "routerconsole.redirectToHTTPS";
    private static final String PROP_GZIP = "routerconsole.enableCompression";

    /**
     *  MUST call setListenHosts() afterwards.
     */
    public HostCheckHandler(I2PAppContext ctx) {
        super();
        _context = ctx;
        _portMapper = ctx.portMapper();
        _listenHosts = new HashSet<String>(8);
        //setMinGzipSize(64*1024);
        // set this super small so we clobber almost everything
        // this has the side effect of faster page loads with less progressive rendering
        // include js so we hit the lightbox snark script -> 26/6K
        setMinGzipSize(512);
        if (_context.getBooleanPropertyDefaultTrue(PROP_GZIP)) {
            setCompressionLevel(9);
            addIncludedMimeTypes(
                                 "application/javascript", "application/x-javascript", "text/javascript",
                                 "application/xhtml+xml", "application/xml", "application/pdf", "text/xml",
                                 "image/svg+xml", "application/x-font-ttf", "application/x-font-truetype",
                                 "font/ttf", "font/otf", "font/woff", "text/css", "text/html", "text/plain"
                                );
        } else {
            // poorly documented, but we must put something in,
            // if empty all are matched,
            // see IncludeExcludeSet
//            addIncludedMimeTypes("xyzzy");
            addIncludedMimeTypes("text/html");
        }
    }

    /**
     *  Set the legal hosts.
     *  Not synched. Call this BEFORE starting.
     *  If empty, all are allowed.
     *
     *  @param hosts contains hostnames or IPs. But we allow all IPs anyway.
     */
    public void setListenHosts(Set<String> hosts) {
        _listenHosts.clear();
        _listenHosts.addAll(hosts);
    }

    /**
     *  Block by Host header,
     *  redirect HTTP to HTTPS,
     *  pass everything else to the delegate.
     */
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

        super.handle(pathInContext, baseRequest, httpRequest, httpResponse);
    }

    /**
     *  Should we allow a request with this Host header?
     *
     *  ref: https://en.wikipedia.org/wiki/DNS_rebinding
     *
     *  @param host the HTTP Host header, null ok
     *  @return true if OK
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
        //System.out.println(host + " not found in " + s);
        return false;
    }

    /**
     *  Strip [] and port from a host header
     *
     *  @param host the HTTP Host header non-null
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
     *  Validate Origin header for POST requests.
     *  Allows requests with matching Origin (same-origin), or no Origin header.
     *  Rejects cross-origin POST requests to prevent CSRF attacks.
     *
     *  @param request the HTTP request
     *  @return true if allowed
     */
    private boolean allowOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String host = request.getHeader("Host");

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
        try {
            // Origin format: "https://host:port" or "http://host:port"
            if (origin.startsWith("http://")) {
                String rest = origin.substring(7);
                originHost = extractHost(rest);
                originPort = extractPort(rest, 80);
            } else if (origin.startsWith("https://")) {
                String rest = origin.substring(8);
                originHost = extractHost(rest);
                originPort = extractPort(rest, 443);
            } else {
                // Unknown format, allow for now
                return true;
            }
        } catch (Exception e) {
            // Parse error, allow
            return true;
        }

        // Normalize IPv6 for comparison
        originHost = normalizeIP(originHost);

        // Extract request host:port
        String requestHost;
        int requestPort;
        try {
            if (host == null) {
                return true;
            }
            requestHost = extractHost(host);
            requestPort = extractPort(host, request.isSecure() ? 443 : 80);
        } catch (Exception e) {
            // Parse error, allow
            return true;
        }

        // Normalize IPv6 for comparison
        requestHost = normalizeIP(requestHost);

        // Allow if origin matches request host
        if (originHost != null && requestHost != null &&
            originHost.equalsIgnoreCase(requestHost) && originPort == requestPort) {
            return true;
        }

        // Also allow localhost variants (127.x.x.x, localhost, ::1, and equivalent)
        if (isLocalhost(originHost) && isLocalhost(requestHost)) {
            return true;
        }

        // Reject cross-origin POST
        return false;
    }

    /**
     *  Extract host from "host:port" or "[ipv6]:port"
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
     *  Extract port from "host:port" or "[ipv6]:port", default to defaultPort if not found
     */
    private static int extractPort(String hostPort, int defaultPort) {
        if (hostPort.startsWith("[")) {
            int brack = hostPort.indexOf(']');
            if (brack > 0) {
                String rest = hostPort.substring(brack + 1);
                if (rest.startsWith(":")) {
                    return Integer.parseInt(rest.substring(1));
                }
                return defaultPort;
            }
        }
        int colon = hostPort.indexOf(':');
        if (colon > 0) {
            return Integer.parseInt(hostPort.substring(colon + 1));
        }
        return defaultPort;
    }

    /**
     *  Normalize IPv6 address to canonical form
     */
    private static String normalizeIP(String host) {
        if (host == null) return null;
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            return addr.getHostAddress();
        } catch (Exception e) {
            return host;
        }
    }

    /**
     *  Check if host is localhost/loopback
     */
    private static boolean isLocalhost(String host) {
        if (host == null) return false;
        String normalized = normalizeIP(host);
        // 127.x.x.x, localhost, ::1, 0:0:0:0:0:0:0:1, etc.
        return normalized != null && (
            normalized.equals("127.0.0.1") ||
            normalized.equals("localhost") ||
            normalized.startsWith("127.") ||
            normalized.equals("::1") ||
            normalized.equals("0:0:0:0:0:0:0:1") ||
            normalized.equals("::")
        );
    }

    /**
     *  Redirect to HTTPS
     *
     *  @since 0.9.34
     */
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
