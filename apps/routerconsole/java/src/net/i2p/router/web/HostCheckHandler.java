package net.i2p.router.web;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.servlet.util.OriginCheck;
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
     * Names are matched case-insensitively, as a Host header is, so they are folded
     * on the way in. An empty set allows only the loopback names and IP addresses,
     * never every name there is - the check has to fail closed if the caller sets
     * nothing.
     *
     * @param hosts contains hostnames or IPs. But we allow all IPs anyway.
     */
    public void setListenHosts(Set<String> hosts) {
        _listenHosts.clear();
        for (String h : hosts) {
            _listenHosts.add(h.toLowerCase(Locale.US));
        }
    }

    /**
     * The host names a Host header may carry when the console is bound to the given
     * addresses. A wildcard bind widens which of the router's addresses answer, not
     * which names are believed, so the localhost names and the operator's allowlist
     * are added whatever was bound. Clearing the set on a wildcard instead turned
     * DNS-rebinding protection off for exactly the operators who exposed the console
     * to a network, which is where rebinding is worth something.
     *
     * @param bound the addresses the console bound to, possibly a wildcard, not modified
     * @param allowedHostsProp the routerconsole.allowedHosts property, null for none
     * @return the lower-cased host names to accept, never empty
     * @since 0.9.72+
     */
    static Set<String> listenHosts(Set<String> bound, String allowedHostsProp) {
        Set<String> rv = new HashSet<>(bound.size() + 6);
        for (String h : bound) {
            rv.add(h.toLowerCase(Locale.US));
        }
        rv.add("localhost");
        rv.add("127.0.0.1");
        rv.add("::1");
        rv.add("0:0:0:0:0:0:0:1");
        if (allowedHostsProp != null) {
            StringTokenizer tok = new StringTokenizer(allowedHostsProp, " ,");
            while (tok.hasMoreTokens()) {
                // Normalized the way the header is normalized, or an entry written
                // with a port - as the name sits in the browser's address bar - is
                // in the set and never matched, because the port comes off the
                // header before the comparison. An unbracketed IPv6 entry loses
                // everything after its first colon here as it does in the header,
                // which costs nothing: numeric addresses are allowed anyway.
                rv.add(getHost(tok.nextToken()).toLowerCase(Locale.US));
            }
        }
        return rv;
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
                OriginCheck.logDenial(httpRequest);
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
        return hostAllowed(host, _listenHosts);
    }

    /**
     * Should we allow a request with this Host header, given the hosts the console
     * is listening on? An empty set is not "everything is allowed" - it is the
     * caller having named nothing, and a rebinding check that turns itself off when
     * nothing was configured is a check that fails open.
     *
     * @param host the HTTP Host header, null ok
     * @param allowed the lower-cased host names set by {@link #setListenHosts}
     * @return true if OK
     * @since 0.9.72+
     */
    static boolean hostAllowed(String host, Set<String> allowed) {
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
        host = getHost(host).toLowerCase(Locale.US);
        if (allowed.contains(host))
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
     * Should this POST pass the Origin check? The rule itself lives in
     * {@link OriginCheck}, because the webapps apply the same one to their own
     * pages and a copy that drifts locks a webapp's own forms out.
     *
     * @param request the HTTP request
     * @return true if allowed
     */
    private boolean allowOrigin(HttpServletRequest request) {
        return OriginCheck.isAllowed(request);
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
