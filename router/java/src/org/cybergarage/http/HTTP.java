/*
 * CyberHTTP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.http;

import java.net.URL;

/**
 * Utility class providing HTTP protocol constants and helper methods.
 *
 * <p>This class contains commonly used HTTP protocol constants including:
 *
 * <ul>
 *   <li>HTTP methods (GET, POST, HEAD, etc.)
 *   <li>HTTP headers (Content-Type, Host, etc.)
 *   <li>HTTP status codes and versions
 *   <li>UPnP-specific constants
 * </ul>
 *
 * <p>Also provides utility methods for URL manipulation and validation.
 *
 * @author Satoshi Konno
 * @version 1.0
 * @since 1.0
 */
public class HTTP {
    ////////////////////////////////////////////////
    // Constants
    ////////////////////////////////////////////////

    /** Host HTTP header field name */
    public static final String HOST = "Host";

    /** Default HTTP version string */
    public static final String VERSION = "1.1";
    /** HTTP/1.0 version string */
    public static final String VERSION_10 = "1.0";
    /** HTTP/1.1 version string */
    public static final String VERSION_11 = "1.1";

    /** HTTP line terminator (CR+LF) */
    public static final String CRLF = "\r\n";
    /** Carriage return character */
    public static final byte CR = '\r';
    /** Line feed character */
    public static final byte LF = '\n';
    /** Tab character */
    public static final String TAB = "\t";

    /** SOAPAction HTTP header field name */
    public static final String SOAP_ACTION = "SOAPAction";

    /** M-SEARCH HTTP method (used by UPnP discovery) */
    public static final String M_SEARCH = "M-SEARCH";
    /** NOTIFY HTTP method (used by UPnP eventing) */
    public static final String NOTIFY = "NOTIFY";
    /** POST HTTP method */
    public static final String POST = "POST";
    /** GET HTTP method */
    public static final String GET = "GET";
    /** HEAD HTTP method */
    public static final String HEAD = "HEAD";
    /** SUBSCRIBE HTTP method (used by UPnP eventing) */
    public static final String SUBSCRIBE = "SUBSCRIBE";
    /** UNSUBSCRIBE HTTP method (used by UPnP eventing) */
    public static final String UNSUBSCRIBE = "UNSUBSCRIBE";

    /** Date HTTP header field name */
    public static final String DATE = "Date";
    /** Cache-Control HTTP header field name */
    public static final String CACHE_CONTROL = "Cache-Control";
    /** Cache-Control no-cache directive value */
    public static final String NO_CACHE = "no-cache";
    /** Cache-Control max-age directive name */
    public static final String MAX_AGE = "max-age";
    /** Connection HTTP header field name */
    public static final String CONNECTION = "Connection";
    /** Connection close directive value */
    public static final String CLOSE = "close";
    /** Keep-Alive HTTP header field name */
    public static final String KEEP_ALIVE = "Keep-Alive";
    /** Content-Type HTTP header field name */
    public static final String CONTENT_TYPE = "Content-Type";
    /** Charset parameter name within Content-Type */
    public static final String CHARSET = "charset";
    /** Content-Length HTTP header field name */
    public static final String CONTENT_LENGTH = "Content-Length";
    /** Content-Language HTTP header field name */
    public static final String CONTENT_LANGUAGE = "Content-Language";
    /** Content-Range HTTP header field name */
    public static final String CONTENT_RANGE = "Content-Range";
    /** Bytes range unit value for Content-Range */
    public static final String CONTENT_RANGE_BYTES = "bytes";
    /** Range HTTP header field name */
    public static final String RANGE = "Range";
    /** Transfer-Encoding HTTP header field name */
    public static final String TRANSFER_ENCODING = "Transfer-Encoding";
    /** Chunked transfer encoding value */
    public static final String CHUNKED = "Chunked";
    /** Location HTTP header field name (used for redirects) */
    public static final String LOCATION = "Location";
    /** Server HTTP header field name */
    public static final String SERVER = "Server";

    /** ST (Search Target) header for UPnP discovery */
    public static final String ST = "ST";
    /** MX (Maximum Wait) header for UPnP discovery */
    public static final String MX = "MX";
    /** MAN (Mandatory Extension) header for UPnP discovery */
    public static final String MAN = "MAN";
    /** NT (Notification Type) header for UPnP eventing */
    public static final String NT = "NT";
    /** NTS (Notification Sub Type) header for UPnP eventing */
    public static final String NTS = "NTS";
    /** USN (Unique Service Name) header for UPnP discovery */
    public static final String USN = "USN";
    /** EXT header confirming MAN was understood */
    public static final String EXT = "EXT";
    /** SID (Session ID) header for UPnP eventing */
    public static final String SID = "SID";
    /** SEQ (Sequence Number) header for UPnP eventing */
    public static final String SEQ = "SEQ";
    /** CALLBACK header for UPnP event subscription */
    public static final String CALLBACK = "CALLBACK";
    /** TIMEOUT header for UPnP event subscription */
    public static final String TIMEOUT = "TIMEOUT";

    /** BOOTID.UPNP.ORG header for UPnP device boot ID */
    public static final String BOOTID_UPNP_ORG = "BOOTID.UPNP.ORG";

    // Thanks for Brent Hills (10/20/04)
    /** MYNAME header for UPnP device identification */
    public static final String MYNAME = "MYNAME";

    /** Request line delimiter (space between method, URI, version) */
    public static final String REQEST_LINE_DELIM = " ";
    /** Header line delimiter (colon-space between name and value) */
    public static final String HEADER_LINE_DELIM = " :";
    /** Status line delimiter (space between version, code, phrase) */
    public static final String STATUS_LINE_DELIM = " ";

    /** Default HTTP port */
    public static final int DEFAULT_PORT = 80;
    /** Default chunk size in bytes (512KB) */
    public static final int DEFAULT_CHUNK_SIZE = 512 * 1024;
    /** Default timeout in seconds */
    public static final int DEFAULT_TIMEOUT = 30;

    ////////////////////////////////////////////////
    // URL
    ////////////////////////////////////////////////

    /**
     * Checks if a string represents an absolute URL.
     *
     * @param urlStr string to check
     * @return true if the string is a valid absolute URL, false otherwise
     */
    public static final boolean isAbsoluteURL(String urlStr) {
        try {
            new URL(urlStr);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts the host name from a URL string.
     *
     * @param urlStr URL string to extract host from
     * @return host name, or empty string if URL is invalid
     */
    public static final String getHost(String urlStr) {
        try {
            URL url = new URL(urlStr);
            return url.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Extracts the port number from a URL string. Returns default HTTP port (80) if no port is
     * specified.
     *
     * @param urlStr URL string to extract port from
     * @return port number, or default port (80) if URL is invalid or no port specified
     */
    public static final int getPort(String urlStr) {
        try {
            URL url = new URL(urlStr);
            // Thanks for Giordano Sassaroli <sassarol@cefriel.it> (08/30/03)
            int port = url.getPort();
            if (port <= 0) port = DEFAULT_PORT;
            return port;
        } catch (Exception e) {
            return DEFAULT_PORT;
        }
    }

    /**
     * Creates a request host URL from host and port.
     *
     * @param host host name or IP address
     * @param port port number
     * @return URL string in format "http://host:port"
     */
    public static final String getRequestHostURL(String host, int port) {
        String reqHost = "http://" + host + ":" + port;
        return reqHost;
    }

    /**
     * Converts a URL to a relative URL path.
     *
     * @param urlStr URL string to convert
     * @param withParam whether to include query parameters in result
     * @return relative URL path, optionally with query parameters
     */
    public static final String toRelativeURL(String urlStr, boolean withParam) {
        String uri = urlStr;
        if (isAbsoluteURL(urlStr) == false) {
            if (0 < urlStr.length() && urlStr.charAt(0) != '/') uri = "/" + urlStr;
        } else {
            try {
                URL url = new URL(urlStr);
                uri = url.getPath();
                if (withParam == true) {
                    String queryStr = url.getQuery();
                    if (!queryStr.isEmpty()) {
                        uri += "?" + queryStr;
                    }
                }
                if (uri.endsWith("/")) uri = uri.substring(0, uri.length() - 1);
            } catch (Exception e) { /* ignored */ }
        }
        return uri;
    }

    /**
     * Converts a URL to a relative URL path with query parameters.
     *
     * @param urlStr URL string to convert
     * @return relative URL path with query parameters
     */
    public static final String toRelativeURL(String urlStr) {
        return toRelativeURL(urlStr, true);
    }

    /**
     * Creates an absolute URL by combining base URL with relative URL.
     *
     * @param baseURLStr base URL string
     * @param relURlStr relative URL to combine with base
     * @return absolute URL string, or empty string if combination fails
     */
    public static final String getAbsoluteURL(String baseURLStr, String relURlStr) {
        try {
            URL baseURL = new URL(baseURLStr);
            String url =
                    baseURL.getProtocol()
                            + "://"
                            + baseURL.getHost()
                            + ":"
                            + baseURL.getPort()
                            + toRelativeURL(relURlStr);
            return url;
        } catch (Exception e) {
            return "";
        }
    }

    ////////////////////////////////////////////////
    // Chunk Size
    ////////////////////////////////////////////////

    /** Current default chunk size for HTTP transfer operations */
    private static int chunkSize = DEFAULT_CHUNK_SIZE;

    /**
     * Sets the default chunk size for HTTP operations.
     *
     * @param size chunk size in bytes
     */
    public static final void setChunkSize(int size) {
        chunkSize = size;
    }

    /**
     * Gets the current default chunk size for HTTP operations.
     *
     * @return chunk size in bytes
     */
    public static final int getChunkSize() {
        return chunkSize;
    }
}
