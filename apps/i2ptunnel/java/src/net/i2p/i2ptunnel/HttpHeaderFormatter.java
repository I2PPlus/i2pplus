package net.i2p.i2ptunnel;

import java.util.List;
import java.util.Map;

/**
 * Utility class for formatting HTTP headers.
 * <p>
 * This class provides static methods for formatting HTTP headers for
 * both transmission and logging purposes. It handles the conversion
 * of header maps to properly formatted HTTP header strings.
 * </p>
 *
 * @since 0.10.0
 */
public class HttpHeaderFormatter {

    /**
     * Formats headers into an HTTP request/response string.
     * <p>
     * This method takes a header map and formats it into a properly
     * formatted HTTP header block suitable for transmission.
     * The format is:
     * <pre>
     * REQUEST_LINE
     * Header-Name: value
     * ...
     *
     * </pre>
     * </p>
     *
     * @param headers the HTTP headers map (header name to list of values)
     * @param command the request/response command line (e.g., "GET / HTTP/1.1")
     * @return the formatted HTTP headers as a string
     */
    public static String formatHeaders(Map<String, List<String>> headers, StringBuilder command) {
        StringBuilder buf = new StringBuilder(command.length() + headers.size() * 64);
        buf.append(command.toString().trim()).append("\r\n");
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String name = e.getKey();
            for (String val : e.getValue()) {
                buf.append(name.trim()).append(": ").append(val.trim()).append("\r\n");
            }
        }
        buf.append("\r\n");
        return buf.toString();
    }

    /**
     * Formats headers into a compact string for logging.
     * <p>
     * This method creates a compact, human-readable representation of
     * HTTP headers suitable for logging. It filters out sensitive or
     * verbose headers like cookies, authentication headers, and
     * excessive request details.
     * </p>
     * <p>
     * The following are excluded from the output:
     * <ul>
     *   <li>Headers containing "desthash", "destb64", "dnt"</li>
     *   <li>Connection, Accept, Cookie, Pragma, Cache-Control headers</li>
     *   <li>Referer header with full URL</li>
     *   <li>Content-Length: 0</li>
     *   <li>User-Agent containing "MYOB"</li>
     *   <li>HEAD requests (headers only)</li>
     * </ul>
     * </p>
     * <p>
     * Long request URLs containing "peer_id" are truncated.
     * </p>
     *
     * @param headers the HTTP headers map
     * @param command the request command line
     * @return a compact string representation suitable for logging
     */
    public static String formatHeadersCompact(Map<String, List<String>> headers, StringBuilder command) {
        StringBuilder buf = new StringBuilder(command.length() + headers.size() * 64);
        String request = command.toString().trim();
        if (request.contains("peer_id")) {
            int ampersand = request.indexOf("&");
            String truncatedRequest = request.substring(0, ampersand) + "...";
            if (request.endsWith("HTTP/1.1")) {truncatedRequest += " HTTP/1.1";}
            else if (request.endsWith("HTTP/1.0")) {truncatedRequest += " HTTP/1.0";}
            request = truncatedRequest;
        }
        if (!request.toLowerCase().contains("head")) {
            buf.append("\n* Request: ").append(request);
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String name = e.getKey();
            String lcName = name.toLowerCase().trim();
            String value = e.getValue().iterator().next().trim();
            boolean hasUA = name.toLowerCase().contains("user-agent") && !value.isEmpty();
            if (request.toLowerCase().contains("head")) {continue;}
            if (lcName.contains("desthash") || lcName.contains("destb64") || lcName.contains("dnt") ||
                lcName.contains("connection") || lcName.contains("accept") || lcName.contains("cookie") ||
                lcName.contains("pragma") || lcName.contains("cache-control") || lcName.contains("referer") ||
                lcName.contains("upgrade-insecure-requests") || (lcName.equals("content-length") && value.equals("0")) ||
                (lcName.contains("user-agent") && hasUA && value.contains("MYOB"))) {
                continue;
            }
            for (String val : e.getValue()) {
                buf.append("\n* ").append(name.trim()).append(": ").append(val.trim());
            }
        }
        return buf.toString();
    }

    /**
     * Extracts the Host header value from raw HTTP headers.
     * <p>
     * This method parses raw HTTP header text and extracts the value
     * of the Host header. If the host contains a port number, it is
     * stripped and only the hostname is returned.
     * </p>
     *
     * @param headers the raw HTTP headers as a string
     * @return the hostname from the Host header, or null if not found
     */
    public static String getHostFromHeaders(String headers) {
        String[] headerLines = headers.split("\r\n");
        for (String headerLine : headerLines) {
            if (headerLine.startsWith("Host:")) {
                String hostHeader = headerLine.substring(6).trim();
                int index = hostHeader.indexOf(":");
                return index != -1 ? hostHeader.substring(0, index) : hostHeader;
            }
        }
        return null;
    }
}
