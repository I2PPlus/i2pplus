package net.i2p.i2ptunnel;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
     * Constructor. Formatting is a static function of the header map passed to
     * it, so an instance carries no state.
     */
    public HttpHeaderFormatter() {}

    /** Splits a header block at each CRLF. */
    private static final Pattern NEWLINE_SPLIT = Pattern.compile("\r\n");

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
     * </pre>
     *
     * @param headers the HTTP headers map (header name to list of values)
     * @param command the request/response command line (e.g., "GET / HTTP/1.1")
     * @return the formatted HTTP headers as a string
     */
    public static String formatHeaders(Map<String, List<String>> headers, StringBuilder command) {
        StringBuilder buf = new StringBuilder(command.length() + headers.size() * 64);
        buf.append(stripLineBreaks(command.toString().trim())).append("\r\n");
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String name = stripLineBreaks(e.getKey().trim());
            for (String val : e.getValue()) {
                buf.append(name).append(": ").append(stripLineBreaks(val.trim())).append("\r\n");
            }
        }
        buf.append("\r\n");
        return buf.toString();
    }

    /**
     * Removes CR and LF from a header name, value, or request line.
     * <p>
     * Header maps are rebuilt from text that was parsed line by line, so a line
     * break can only reach this point through a value that was constructed
     * elsewhere (a spoofed host, a security header, a server response rewrite).
     * Stripping it keeps a hostile value from splitting the message into extra
     * header lines or an injected body (HTTP response splitting).
     * </p>
     *
     * @param s the header name, value, or request line, may be null
     * @return the input with CR and LF removed, or null if null was passed
     * @since 0.9.71+
     */
    static String stripLineBreaks(String s) {
        if (s == null) {return null;}
        if (s.indexOf('\r') < 0 && s.indexOf('\n') < 0) {return s;}
        StringBuilder rv = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\r' && c != '\n') {rv.append(c);}
        }
        return rv.toString();
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
        String request = stripLineBreaks(command.toString().trim());
        if (request.contains("peer_id")) {
            // Cut the query at the first continuation parameter; a lone
            // peer_id=... query has no '&', and substring(0, -1) threw.
            int ampersand = request.indexOf("&");
            int cut = ampersand >= 0 ? ampersand : request.indexOf("?");
            String truncatedRequest = (cut > 0 ? request.substring(0, cut) : request) + "...";
            if (request.endsWith("HTTP/1.1")) {truncatedRequest += " HTTP/1.1";}
            else if (request.endsWith("HTTP/1.0")) {truncatedRequest += " HTTP/1.0";}
            request = truncatedRequest;
        }
        if (!request.toLowerCase().contains("head")) {
            buf.append("\n* Request: ").append(request);
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            List<String> values = e.getValue();
            if (values == null || values.isEmpty()) {continue;}
            String name = e.getKey();
            String lcName = name.toLowerCase().trim();
            String value = stripLineBreaks(values.get(0).trim());
            boolean hasUA = name.toLowerCase().contains("user-agent") && !value.isEmpty();
            if (request.toLowerCase().contains("head")) {continue;}
            if (lcName.contains("desthash") || lcName.contains("destb64") || lcName.contains("dnt") ||
                lcName.contains("connection") || lcName.contains("accept") || lcName.contains("cookie") ||
                lcName.contains("pragma") || lcName.contains("cache-control") || lcName.contains("referer") ||
                lcName.contains("upgrade-insecure-requests") || (lcName.equals("content-length") && value.equals("0")) ||
                (lcName.contains("user-agent") && hasUA && value.contains("MYOB"))) {
                continue;
            }
            for (String val : values) {
                buf.append("\n* ").append(stripLineBreaks(name.trim())).append(": ").append(stripLineBreaks(val.trim()));
            }
        }
        return buf.toString();
    }

    /**
     * Request lines longer than this are cut at the query string. A tracker or
     * client request carries a whole base64 destination in the query, which is
     * the same destination the headers already name.
     *
     * @since 0.9.72+
     */
    static final int MAX_REQUEST = 96;

    /**
     * Header values longer than this are cut. No log line is readable once a
     * 500 character blob lands in it.
     *
     * @since 0.9.72+
     */
    static final int MAX_VALUE = 64;

    /**
     * Formats the request and headers for the log, in the house continuation
     * form. Unlike the wire form this is not what is sent to the server: the
     * base64 and hash forms of the client destination are dropped as repeats of
     * the base32 form, that form is shortened to the eight characters the log
     * viewer links, and over-long values are cut.
     *
     * @param headers the HTTP headers map (header name to list of values)
     * @param command the request command line (e.g., "GET / HTTP/1.1")
     * @return a string of "\n* name: value" lines, never null
     * @since 0.9.72+
     */
    public static String formatHeadersForLog(Map<String, List<String>> headers, StringBuilder command) {
        StringBuilder buf = new StringBuilder(128 + headers.size() * 32);
        buf.append("\n* Request: ").append(shortRequest(stripLineBreaks(command.toString().trim())));
        Set<String> seen = new HashSet<>(headers.size() * 2);
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            List<String> values = e.getValue();
            if (values == null || values.isEmpty()) {continue;}
            String name = stripLineBreaks(e.getKey().trim());
            String lc = name.toLowerCase();
            // Three spellings of one destination: keep the base32, which is the
            // short one, and drop the hash and the base64 blob as repeats.
            if (lc.equals("x-i2p-destb64") || lc.equals("x-i2p-desthash")) {continue;}
            if (lc.equals("x-i2p-destb32")) {
                String shortB32 = shortB32(values.get(0));
                if (seen.add(lc + shortB32)) {buf.append("\n* ").append(name).append(": ").append(shortB32);}
                continue;
            }
            for (String val : values) {
                String value = shortValue(stripLineBreaks(val.trim()));
                if (!seen.add(lc + value)) {continue;}
                buf.append("\n* ").append(name).append(": ").append(value);
            }
        }
        return buf.toString();
    }

    /**
     * Cuts a request line to what a reader can actually scan, keeping the
     * method, the path and the protocol version.
     *
     * @param request the request line, non-null
     * @return the request line, or its head plus an ellipsis when over
     *         {@link #MAX_REQUEST} characters
     * @since 0.9.72+
     */
    static String shortRequest(String request) {
        if (request.length() <= MAX_REQUEST) {return request;}
        String tail = request.endsWith("HTTP/1.1") ? " HTTP/1.1" :
                      request.endsWith("HTTP/1.0") ? " HTTP/1.0" : "";
        int q = request.indexOf('?');
        if (q > 0) {return request.substring(0, q) + "?..." + tail;}
        return request.substring(0, MAX_REQUEST) + "..." + tail;
    }

    /**
     * The eight-character head of a base32 address in brackets, the form the
     * console log viewer turns into a link. The full address is 60 characters
     * naming the same destination.
     *
     * @param b32 the base32 address, may be null
     * @return the bracketed head, or [unknown] if there is no address
     * @since 0.9.72+
     */
    static String shortB32(String b32) {
        if (b32 == null) {return "[unknown]";}
        String s = b32.trim();
        int dot = s.indexOf(".b32.i2p");
        if (dot > 0) {s = s.substring(0, dot);}
        return '[' + (s.length() <= 8 ? s : s.substring(0, 8)) + ']';
    }

    /**
     * Cuts an over-long header value, keeping its head so it stays recognizable
     * and reporting the length that was hidden.
     *
     * @param value the header value, non-null
     * @return the value, or its head plus an ellipsis and the full length
     * @since 0.9.72+
     */
    static String shortValue(String value) {
        if (value.length() <= MAX_VALUE) {return value;}
        return value.substring(0, MAX_VALUE) + "... [" + value.length() + " chars]";
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
        String[] headerLines = NEWLINE_SPLIT.split(headers);
        for (String headerLine : headerLines) {
            if (headerLine.startsWith("Host:")) {
                // "Host:" is 5 chars; substring(6) assumed the optional space
                // and ate the first character of a compact Host:example.com.
                String hostHeader = headerLine.substring(5).trim();
                int index = hostHeader.indexOf(":");
                return index != -1 ? hostHeader.substring(0, index) : hostHeader;
            }
        }
        return null;
    }
}
