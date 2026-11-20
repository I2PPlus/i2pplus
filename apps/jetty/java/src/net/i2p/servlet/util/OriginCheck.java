package net.i2p.servlet.util;

import javax.servlet.http.HttpServletRequest;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

/**
 * The Origin-header rule the console and the webapps apply to a POST.
 * <p>
 * One rule for all of them. The console, the address book, SusiMail and I2PSnark
 * each carried their own copy of the parse-and-compare, and the copies drifted:
 * one of them refused every POST that carried an Origin header at all, because
 * the flag standing in for "this Origin parsed" was cleared before the parse and
 * never set by a successful one. A browser sends Origin on a same-origin POST, so
 * that copy locked its own forms out.
 * <p>
 * Nothing here has to send an Origin header. A POST that carries none is not a
 * browser POST and is left to the nonce and password checks behind this one.
 *
 * @since 0.9.72+
 */
public class OriginCheck {

    /**
     * The decision is stateless; this is declared so the class is not documented
     * as relying on an implicit default constructor.
     */
    public OriginCheck() {}

    /**
     * Decide whether an Origin header is acceptable for a request to the given
     * Host header. Same-origin is allowed, as is a request where both sides are
     * loopback <em>and</em> the ports match; anything else is rejected.
     * <p>
     * An Origin of "null" names no origin at all - a sandboxed frame, a data: URL
     * or a blob: page send it, and so does a browser with origin-string cleanup or
     * a header-spoofing privacy extension, on a plain same-origin form POST. It is
     * allowed, because refusing it locks the operator's own forms out and it proves
     * nothing about where the form came from; the CSRF token the form carries is
     * what decides. A request that carries no Origin at all is not a browser POST
     * and is left to the nonce and password checks behind this one.
     * <p>
     * Both sides are compared as literal text. Neither header is ever resolved
     * through DNS: a hostname whose A record points at 127.0.0.1 (DNS rebinding)
     * is not loopback here, which is what keeps the localhost allowance from
     * becoming a CSRF bypass. Resolving attacker-supplied host names on the
     * request thread would also hand a slow or hostile resolver control of the
     * Jetty thread pool.
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
    public static boolean isAllowed(String origin, String hostHeader, boolean secure) {
        // Allow if no Origin header (same-origin form POST without Origin)
        if (origin == null || origin.isEmpty()) {
            return true;
        }

        // A sandboxed frame, a data: URL or a blob: page sends this, and so does a
        // privacy-hardened browser: LibreWolf with uMatrix, and Firefox's tracking
        // protection "origin string cleanup", both send Origin: null for a plain
        // same-origin form POST. It names no origin, so this layer cannot decide
        // the request either way, and refusing it locks the operator's own forms
        // out. The CSRF token the form carries is what decides - a foreign page
        // cannot read it, and every POST form in the console and the three webapps
        // carries one.
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
     * The same decision read straight off the request.
     *
     * @param request the POST being guarded, read for its Origin and Host headers
     *                and for whether it arrived over TLS
     * @return true if the request's Origin is acceptable for its Host
     * @since 0.9.72+
     */
    public static boolean isAllowed(HttpServletRequest request) {
        return isAllowed(request.getHeader("Origin"),
                         request.getHeader("Host"),
                         request.isSecure());
    }

    /**
     * Report a POST refused by {@link #isAllowed(HttpServletRequest)}.
     * <p>
     * This goes to the router log, not to the process's stderr: a refusal written
     * with println() lands in the service wrapper's log, where it is out of order
     * with everything the router logged around it and invisible on the console's
     * log page. The two headers are echoed because the pair that failed, not the
     * fact of the refusal, is what says whether a browser, a proxy or a foreign
     * page sent the request.
     * <p>
     * WARN, throttled per sending address, not ERROR: one refusal is one line per
     * request, so the sender sets the rate, and the three webapps logged nothing
     * on this path before. ERROR is the level an operator watches, and a page
     * POSTing at a tunnel it cannot reach would fill it. The suppressor writes the
     * first refusal for an address and then collapses the repeats with how many it
     * hid, so the finding stays.
     *
     * @param request the POST that was refused
     * @since 0.9.72+
     */
    public static void logDenial(HttpServletRequest request) {
        logDenial(I2PAppContext.getGlobalContext().logManager().getLog(OriginCheck.class),
                  request);
    }

    /**
     * The same report written to a caller's log, so the wording can be checked
     * without a running router's log manager.
     *
     * @param log the log to write to
     * @param request the POST that was refused
     */
    static void logDenial(Log log, HttpServletRequest request) {
        // The address, not the Origin: the Origin is attacker-chosen, so keying on
        // it would let one sender defeat the collapse with a new value per
        // request. Log.throttled() checks the level itself, so there is no guard
        // here to keep in step with the call.
        log.warnThrottled("origin-denied " + request.getRemoteAddr(),
                          "POST denied -> Origin does not match Host" +
                          "\n* Origin: " + logged(request.getHeader("Origin")) +
                          "\n* Host: " + logged(request.getHeader("Host")) +
                          "\n* Path: " + logged(request.getRequestURI()));
    }

    /**
     * A header value as it goes into the log: markup stripped so an
     * attacker-chosen Origin cannot write into the console's log page, and an
     * absent header named as such instead of printed as the word null.
     *
     * @param value the header value, null for a header that was not sent
     * @return the value to print
     */
    private static String logged(String value) {
        return value == null ? "not sent" : DataHelper.stripHTML(value);
    }

    /**
     * True when the host is a textual loopback name: an IPv4 address written in
     * full inside 127/8, the IPv6 loopback in its written forms, or "localhost".
     * No DNS lookup is performed, so a hostname that happens to resolve to a
     * loopback address is not accepted - and neither is one that merely starts
     * with the digits. A prefix test on the name let any host called
     * "127.somewhere.example" through beside a real "127.0.0.1", and it is the
     * name in the header that is compared here, never an address resolved from
     * it, so the whole dotted quad has to be checked.
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
        // 127/8, all four octets: isIPv4Address() is a strict w.x.y.z form check,
        // so once the form is fixed the leading octet is the whole question
        if (host.startsWith("127.") && Addresses.isIPv4Address(host))
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
}
