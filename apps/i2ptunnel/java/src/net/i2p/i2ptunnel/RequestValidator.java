package net.i2p.i2ptunnel;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Validates HTTP requests for security and policy compliance.
 * <p>
 * This class encapsulates all request validation logic for I2PTunnelHTTPServer,
 * including hostname validation (detecting attempts to access localhost/loopback),
 * inproxy rejection, referer validation, and user-agent filtering.
 * </p>
 * <p>
 * Validations are performed based on configuration options set in the tunnel's
 * client options properties.
 * </p>
 *
 * @since 0.10.0
 */
public class RequestValidator {

    private final Log _log;
    private final String _peerB32;
    private final Properties _opts;
    private final BlocklistManager _blocklistManager;

    /**
     * Creates a new RequestValidator.
     *
     * @param log the Log instance for logging validation events
     * @param peerB32 the Base32-encoded peer destination for logging
     * @param opts the tunnel client options for validation configuration
     * @param blocklistManager the BlocklistManager for logging blocked destinations; may be null
     */
    public RequestValidator(Log log, String peerB32, Properties opts, BlocklistManager blocklistManager) {
        _log = log;
        _peerB32 = peerB32;
        _opts = opts;
        _blocklistManager = blocklistManager;
    }

    /**
     * Validates a hostname from an HTTP request.
     * <p>
     * This method checks for several security issues:
     * <ul>
     *   <li>Attempts to access localhost or loopback addresses</li>
     *   <li>DNS blocking (0.0.0.0 or :: responses)</li>
     *   <li>Non-I2P/non-onion hostnames that resolve to private addresses</li>
     * </ul>
     * </p>
     * <p>
     * Valid hostnames ending in .i2p or .onion are passed through without
     * validation, as these are expected I2P/eepSite or Tor addresses.
     * </p>
     *
     * @param hostname the Host header value from the request; may be null
     * @param command the full request command for blocklist checking
     * @return a ValidationResult containing the validation outcome and any error response
     */
    public ValidationResult validateHostname(String hostname, StringBuilder command) {
        ValidationResult result = new ValidationResult();
        result.isValid = true;

        if (hostname != null && !hostname.endsWith(".i2p") && !hostname.endsWith(".onion")) {
            try {
                InetAddress address = InetAddress.getByName(hostname);
                if (address != null) {
                    if (address.isLinkLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()) {
                        if (_log.shouldWarn()) {
                            _log.warn("[HTTPServer] WARNING! Attempt to access localhost or loopback address via [" + hostname + "]" +
                                      " -> Adding dest to clients blocklist file \n* Client: " + _peerB32);
                        }
                        if (_blocklistManager != null) {
                            _blocklistManager.logBlockedDestination(_peerB32);
                        }
                        result.isValid = false;
                        result.isPossibleExploit = true;
                    } else if (address.isAnyLocalAddress()) {
                        if (!hostname.equals("::") && !hostname.equals("0.0.0.0")) {
                            if (_log.shouldWarn()) {
                                _log.warn("[HTTPServer] DNS server appears to be blocking requests to " + hostname +
                                          " -> Sending Error 403 \n* Client: " + _peerB32);
                            }
                            result.errorResponse = I2PTunnelHTTPServer.ERR_FORBIDDEN;
                            result.isValid = false;
                            result.isPossibleExploit = false;
                        } else {
                            result.errorResponse = I2PTunnelHTTPServer.ERR_FORBIDDEN;
                            result.isValid = false;
                            result.isPossibleExploit = true;
                        }
                    } else {
                        if (_log.shouldInfo() && !hostname.equals(address.getHostAddress())) {
                            _log.info("[HTTPServer] Hostname " + hostname + " validated" +
                                      " -> Resolves to: " + address.getHostAddress());
                        }
                        result.isPossibleExploit = false;
                    }
                } else {
                    if (_log.shouldWarn()) {
                        _log.warn("[HTTPServer] Could not resolve " + hostname + " to IP address" +
                                  " -> Sending Error 404 \n* Client: " + _peerB32);
                    }
                    result.errorResponse = I2PTunnelHTTPServer.ERR_NOT_FOUND;
                    result.isValid = false;
                    result.isPossibleExploit = false;
                }
                if (result.isPossibleExploit) {
                    if (_blocklistManager != null) {
                        _blocklistManager.logBlockedDestination(_peerB32);
                    }
                    if (_log.shouldWarn()) {
                        _log.warn("[HTTPServer] Client attempted to access private or wildcard address " + hostname +
                                  " -> Sending Error 403 and adding to blocklist \n* Client: " + _peerB32);
                    }
                }
            } catch (Exception e) {
                if (_log.shouldWarn()) {
                    _log.warn("[HTTPServer] Error validating hostname " + hostname + ": " + e.getMessage());
                }
            }
        }

        return result;
    }

    /**
     * Checks if the request should be rejected based on inproxy headers.
     * <p>
     * When the OPT_REJECT_INPROXY option is enabled, requests containing
     * X-Forwarded-For, X-Forwarded-Server, Forwarded, or X-Forwarded-Host
     * headers are rejected to prevent proxy abuse.
     * </p>
     *
     * @param headers the HTTP request headers
     * @return true if the request should be rejected, false otherwise
     */
    public boolean shouldRejectInproxy(java.util.Map<String, List<String>> headers) {
        if (!Boolean.parseBoolean(_opts.getProperty(I2PTunnelHTTPServer.OPT_REJECT_INPROXY))) {
            return false;
        }
        return headers.containsKey("X-Forwarded-For") ||
               headers.containsKey("X-Forwarded-Server") ||
               headers.containsKey("Forwarded") ||
               headers.containsKey("X-Forwarded-Host");
    }

    /**
     * Builds a detailed log message for inproxy rejection.
     * <p>
     * This method extracts all relevant headers for logging purposes
     * when an inproxy request is rejected.
     * </p>
     *
     * @param headers the HTTP request headers
     * @return a formatted string suitable for logging
     */
    public String buildInproxyRejectLog(java.util.Map<String, List<String>> headers) {
        StringBuilder buf = new StringBuilder();
        buf.append("[HTTPServer] Refusing Inproxy access \n* Client: ").append(_peerB32);
        List<String> h = headers.get("X-Forwarded-For");
        if (h != null) {buf.append("\n* X-Forwarded-For: ").append(h.get(0));}
        h = headers.get("X-Forwarded-Server");
        if (h != null) {buf.append("\n* X-Forwarded-Server: ").append(h.get(0));}
        h = headers.get("X-Forwarded-Host");
        if (h != null) {buf.append("\n* X-Forwarded-Host: ").append(h.get(0));}
        h = headers.get("Forwarded");
        if (h != null) {buf.append("\n* Forwarded: ").append(h.get(0));}
        return buf.toString();
    }

    /**
     * Checks if the request should be rejected based on referer header.
     * <p>
     * When the OPT_REJECT_REFERER option is enabled, requests with absolute
     * URIs (starting with http:// or https://) in the Referer header are
     * rejected to prevent information leakage.
     * </p>
     *
     * @param headers the HTTP request headers
     * @return true if the request should be rejected, false otherwise
     */
    public boolean shouldRejectReferer(java.util.Map<String, List<String>> headers) {
        if (!Boolean.parseBoolean(_opts.getProperty(I2PTunnelHTTPServer.OPT_REJECT_REFERER))) {
            return false;
        }
        List<String> h = headers.get("Referer");
        if (h == null) {return false;}
        String referer = h.get(0);
        if (referer.length() <= 9) {return false;}
        referer = referer.substring(9);
        return referer.startsWith("http://") || referer.startsWith("https://");
    }

    /**
     * Extracts the referer URL for logging purposes.
     * <p>
     * Strips the "Referer: " prefix from the header value.
     * </p>
     *
     * @param headers the HTTP request headers
     * @return the referer URL without the header prefix, or null if not available
     */
    public String getRefererForLog(java.util.Map<String, List<String>> headers) {
        List<String> h = headers.get("Referer");
        if (h != null && h.get(0).length() > 9) {
            return h.get(0).substring(9);
        }
        return null;
    }

    /**
     * Checks if the request should be rejected based on user-agent header.
     * <p>
     * When the OPT_REJECT_USER_AGENTS option is enabled, requests with
     * blacklisted user agents are rejected. The blacklist is configured
     * via the OPT_USER_AGENTS option as a comma-separated list.
     * </p>
     * <p>
     * A special case is made for user agents starting with "MYOB" which
     * are always allowed. Also, if the blacklist contains "none", requests
     * without a User-Agent header are rejected.
     * </p>
     *
     * @param headers the HTTP request headers
     * @return true if the request should be rejected, false otherwise
     */
    public boolean shouldRejectUserAgent(java.util.Map<String, List<String>> headers) {
        if (!Boolean.parseBoolean(_opts.getProperty(I2PTunnelHTTPServer.OPT_REJECT_USER_AGENTS))) {
            return false;
        }
        if (headers == null || !headers.containsKey("User-Agent")) {
            String blockAgents = _opts.getProperty(I2PTunnelHTTPServer.OPT_USER_AGENTS);
            if (blockAgents != null) {
                String[] agents = DataHelper.split(blockAgents, ",");
                for (String ag : agents) {
                    if (ag.trim().equals("none")) {return true;}
                }
            }
            return false;
        }
        String ua = headers.get("User-Agent").get(0);
        if (ua.startsWith("MYOB")) {return false;}
        String blockAgents = _opts.getProperty(I2PTunnelHTTPServer.OPT_USER_AGENTS);
        if (blockAgents == null) {return false;}
        String[] agents = DataHelper.split(blockAgents, ",");
        for (String ag : agents) {
            if (ag.trim().length() > 0 && ua.contains(ag.trim())) {return true;}
        }
        return false;
    }

    /**
     * Extracts the user agent for blacklisting checks.
     *
     * @param headers the HTTP request headers
     * @return the user agent string, or null if not present
     */
    public String getUserAgentForCheck(java.util.Map<String, List<String>> headers) {
        if (headers != null && headers.containsKey("User-Agent")) {
            return headers.get("User-Agent").get(0);
        }
        return null;
    }

    /**
     * Encapsulates the result of hostname validation.
     * <p>
     * This inner class provides detailed information about whether a request
     * is valid, if it appears to be an exploit attempt, and what error
     * response should be sent.
     * </p>
     *
     * @since 0.10.0
     */
    public static class ValidationResult {
        /** Whether the request is valid */
        public boolean isValid = true;

        /** Whether this appears to be an exploit attempt */
        public boolean isPossibleExploit = false;

        /** The error response to send if the request is invalid; null if no response needed */
        public String errorResponse = null;
    }
}
