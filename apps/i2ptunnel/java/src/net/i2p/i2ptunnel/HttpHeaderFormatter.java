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

    /** Splits a request line into its method, target and version. */
    private static final Pattern SPACE_SPLIT = Pattern.compile(" ");

    /**
     * A Host value that may be folded into the request target: a hostname or
     * IPv4 literal with an optional port, as the client wrote it. An IPv6
     * literal in brackets is not matched, and is left as a header of its own.
     */
    private static final Pattern HOST_VALUE = Pattern.compile("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?");

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
     * Request lines longer than this are cut at the query string. A tracker or
     * client request carries a whole base64 destination in the query, which is
     * the same destination the headers already name. The cap is
     * {@link #LINE_BUDGET} less the 11 characters of the {@code * Request: }
     * label the line opens with, so a request line never puts an emitted log
     * line over the house budget on its own - which matters now that the
     * authority from Host is folded into the target and lengthens it.
     *
     * @since 0.9.72+
     */
    static final int MAX_REQUEST = 77;

    /**
     * Header values longer than this are cut. No log line is readable once a
     * 500 character blob lands in it.
     *
     * @since 0.9.72+
     */
    static final int MAX_VALUE = 64;

    /**
     * Width a single continuation line of the header dump is packed to before
     * the next segment starts a new line. House budget for an emitted log line.
     *
     * @since 0.9.72+
     */
    static final int LINE_BUDGET = 88;

    /**
     * Whether a header says nothing the reader does not already know.
     * <p>Measured on 2026-11-26 over 6,481 request dumps: {@code Connection:
     * close} appeared on all of them, {@code Pragma} and {@code Cache-Control:
     * no-cache} on 1,381 each, and 1,375 of the 2,800 {@code Accept-Encoding}
     * lines had an empty value - a header sent with nothing after the colon,
     * which is the request equivalent of printing a measurement that was never
     * taken. A value that does vary is kept: a {@code Connection: keep-alive},
     * or a real {@code Cache-Control}.
     *
     * @param lcName the header name, lower case and trimmed
     * @param value the header value, already shortened, never null
     * @return true when the pair should be left out of the dump
     * @since 0.9.72+
     */
    static boolean isBoilerplate(String lcName, String value) {
        if (value.isEmpty()) {return true;}
        switch (lcName) {
            case "connection":     return value.equalsIgnoreCase("close");
            case "pragma":         return value.equalsIgnoreCase("no-cache");
            case "cache-control":  return value.equalsIgnoreCase("no-cache") ||
                                        value.equalsIgnoreCase("no-cache, no-store");
            default:               return false;
        }
    }

    /**
     * Formats the request and headers for the log, in the house continuation
     * form. Unlike the wire form this is not what is sent to the server: the
     * base64 and hash forms of the client destination are dropped as repeats of
     * the base32 form, that form is shortened to the eight characters the log
     * viewer links, over-long values are cut, and pairs that carry no
     * information are dropped. What is left is packed onto continuation lines
     * up to {@link #LINE_BUDGET} rather than spending a line per header.
     * <p>
     * The header pairs come first and the request line last, because the pairs
     * say who is asking and the request says what for - and the request line is
     * the one segment that cannot share its line, so putting it first pushed
     * every short pair onto a second line after it. The {@code Host} the client
     * sent is folded into the request target rather than repeated as a pair of
     * its own, the way an absolute-form request line writes it.
     *
     * @param headers the HTTP headers map (header name to list of values)
     * @param command the request command line (e.g., "GET / HTTP/1.1")
     * @return a string of "\n* name: value" segments, never null
     * @since 0.9.72+
     */
    public static String formatHeadersForLog(Map<String, List<String>> headers, StringBuilder command) {
        StringBuilder buf = new StringBuilder(128 + headers.size() * 32);
        String request = stripLineBreaks(command.toString().trim());
        String host = foldableHost(headers);
        String folded = requestWithHost(request, host);
        // The authority is named once: either it went into the target because we
        // put it there, or the client wrote an absolute-form target and named it
        // itself.
        boolean hostIsRedundant = folded != null || (host != null && targetIsAbsolute(request));
        Set<String> seen = new HashSet<>(headers.size() * 2);
        int used = 0;
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
                if (seen.add(lc + shortB32)) {used = appendSegment(buf, used, name + ": " + shortB32);}
                continue;
            }
            if (hostIsRedundant && lc.equals("host")) {continue;}
            for (String val : values) {
                String value = shortValue(stripLineBreaks(val.trim()));
                if (isBoilerplate(lc, value)) {continue;}
                if (!seen.add(lc + value)) {continue;}
                used = appendSegment(buf, used, name + ": " + value);
            }
        }
        buf.append("\n* Request: ").append(shortRequest(folded != null ? folded : request));
        return buf.toString();
    }

    /**
     * The Host header value, if the request has one that can be folded into its
     * target. The first usable one wins; a request line may name only one
     * authority.
     *
     * @param headers the HTTP headers map, may be empty
     * @return the authority as the client wrote it, or null when there is no
     *         Host or it is not a plain hostname with an optional port
     * @since 0.9.72+
     */
    static String foldableHost(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            List<String> values = e.getValue();
            if (values == null || values.isEmpty()) {continue;}
            if (!"host".equals(stripLineBreaks(e.getKey().trim()).toLowerCase())) {continue;}
            String value = stripLineBreaks(values.get(0).trim());
            if (HOST_VALUE.matcher(value).matches()) {return value;}
            // A Host that is not a plain authority - a bracketed IPv6 literal,
            // or junk a client or an attacker put there - stays a pair of its
            // own, where shortValue() can cut it and the dump cannot be read as
            // naming a host it does not.
            return null;
        }
        return null;
    }

    /**
     * The request line with the authority written into its target, as an
     * absolute-form request line does: {@code GET host/path HTTP/1.1}. This
     * tunnel serves plain HTTP, so no scheme is invented for the folded form,
     * and a client that did write one keeps what it wrote - an
     * <code>http://</code> is the assumed default and is dropped, an
     * <code>https://</code> is a fact about the request and is kept.
     *
     * @param request the request line, already stripped of line breaks
     * @param host the authority to fold in, or null when there is none
     * @return the request line with the authority in its target, or null when
     *         the target is not a path this can be folded into
     * @since 0.9.72+
     */
    static String requestWithHost(String request, String host) {
        if (host == null) {return null;}
        String target = requestTarget(request);
        if (target == null) {return null;}
        String absolute = null;
        if (target.startsWith("http://")) {absolute = target.substring(7);}
        else if (target.startsWith("/")) {absolute = host + target;}
        else {return null;}
        String[] parts = SPACE_SPLIT.split(request);
        StringBuilder buf = new StringBuilder(request.length() + host.length());
        buf.append(parts[0]).append(' ').append(absolute);
        for (int i = 2; i < parts.length; i++) {buf.append(' ').append(parts[i]);}
        return buf.toString();
    }

    /**
     * The request target, the second token of the request line, or null when the
     * line is too short to have one.
     *
     * @param request the request line, non-null
     * @return the target as the client wrote it, or null when there is none
     * @since 0.9.72+
     */
    static String requestTarget(String request) {
        String[] parts = SPACE_SPLIT.split(request);
        return parts.length >= 2 ? parts[1] : null;
    }

    /**
     * Does the request target name its own authority - an absolute-form request
     * line, {@code GET http://host/path HTTP/1.1}? When it does, the Host header
     * repeats what the target already says, and the dump does not need both.
     *
     * @param request the request line, non-null
     * @return true if the target carries a scheme and an authority
     * @since 0.9.72+
     */
    static boolean targetIsAbsolute(String request) {
        String target = requestTarget(request);
        return target != null && (target.startsWith("http://") || target.startsWith("https://"));
    }

    /**
     * Append one {@code name: value} segment to the header dump, starting a new
     * continuation line only when the current one is full.
     *
     * @param buf the dump being built
     * @param used characters used on the current continuation line, 0 when no
     *             pair has been written on one yet
     * @param segment the pair to append
     * @return characters used on the current continuation line afterwards
     */
    private static int appendSegment(StringBuilder buf, int used, String segment) {
        if (used > 0 && used + 3 + segment.length() > LINE_BUDGET) {
            buf.append("\n* ");
            used = 2;
        } else if (used > 0) {
            buf.append(" * ");
            used += 3;
        } else {
            buf.append("\n* ");
            used = 2;
        }
        buf.append(segment);
        return used + segment.length();
    }

    /**
     * Cuts a request line to what a reader can actually scan, keeping the
     * method, the authority and path, and the protocol version. The result is
     * never longer than {@link #MAX_REQUEST}, so a request line cannot put an
     * emitted log line over the budget no matter what the client asked for.
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
        if (q > 0) {
            int room = MAX_REQUEST - tail.length() - 4;
            return request.substring(0, Math.min(q, room)) + "?..." + tail;
        }
        return request.substring(0, MAX_REQUEST - tail.length() - 3) + "..." + tail;
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
