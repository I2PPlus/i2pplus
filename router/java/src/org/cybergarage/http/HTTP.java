/******************************************************************
 *
 *	CyberHTTP for Java
 *
 *	Copyright (C) Satoshi Konno 2002
 *
 *	File: HTTP.java
 *
 *	Revision:
 *
 *	11/18/02
 *		- first revision.
 *	08/30/03
 *		- Giordano Sassaroli <sassarol@cefriel.it>
 *		- Problem : the method getPort should return the default http port 80 when a port is not specified
 *		- Description : the method is used in ControlRequest.setRequestHost() and in SubscriptionRequest.setService(). maybe the default port check could be done in these methods.
 *	09/03/02
 *		- Added getRequestHostURL().
 *	03/11/04
 *		- Added the following methods to send big content stream.
 *		  post(HTTPResponse, byte[])
 *		  post(HTTPResponse, InputStream)
 *	05/26/04
 *		- Added NO_CATCH and MAX_AGE.
 *	10/20/04
 *		- Brent Hills <bhills@openshores.com>
 *		- Added Range and MYNAME;
 *
 ******************************************************************/

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

    public static final String HOST = "Host";

    public static final String VERSION = "1.1";
    public static final String VERSION_10 = "1.0";
    public static final String VERSION_11 = "1.1";

    public static final String CRLF = "\r\n";
    public static final byte CR = '\r';
    public static final byte LF = '\n';
    public static final String TAB = "\t";

    public static final String SOAP_ACTION = "SOAPAction";

    public static final String M_SEARCH = "M-SEARCH";
    public static final String NOTIFY = "NOTIFY";
    public static final String POST = "POST";
    public static final String GET = "GET";
    public static final String HEAD = "HEAD";
    public static final String SUBSCRIBE = "SUBSCRIBE";
    public static final String UNSUBSCRIBE = "UNSUBSCRIBE";

    public static final String DATE = "Date";
    public static final String CACHE_CONTROL = "Cache-Control";
    public static final String NO_CACHE = "no-cache";
    public static final String MAX_AGE = "max-age";
    public static final String CONNECTION = "Connection";
    public static final String CLOSE = "close";
    public static final String KEEP_ALIVE = "Keep-Alive";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String CHARSET = "charset";
    public static final String CONTENT_LENGTH = "Content-Length";
    public static final String CONTENT_LANGUAGE = "Content-Language";
    public static final String CONTENT_RANGE = "Content-Range";
    public static final String CONTENT_RANGE_BYTES = "bytes";
    public static final String RANGE = "Range";
    public static final String TRANSFER_ENCODING = "Transfer-Encoding";
    public static final String CHUNKED = "Chunked";
    public static final String LOCATION = "Location";
    public static final String SERVER = "Server";

    public static final String ST = "ST";
    public static final String MX = "MX";
    public static final String MAN = "MAN";
    public static final String NT = "NT";
    public static final String NTS = "NTS";
    public static final String USN = "USN";
    public static final String EXT = "EXT";
    public static final String SID = "SID";
    public static final String SEQ = "SEQ";
    public static final String CALLBACK = "CALLBACK";
    public static final String TIMEOUT = "TIMEOUT";

    public static final String BOOTID_UPNP_ORG = "BOOTID.UPNP.ORG";

    // Thanks for Brent Hills (10/20/04)
    public static final String MYNAME = "MYNAME";

    public static final String REQEST_LINE_DELIM = " ";
    public static final String HEADER_LINE_DELIM = " :";
    public static final String STATUS_LINE_DELIM = " ";

    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_CHUNK_SIZE = 512 * 1024;
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
                    if (!queryStr.equals("")) {
                        uri += "?" + queryStr;
                    }
                }
                if (uri.endsWith("/")) uri = uri.substring(0, uri.length() - 1);
            } catch (Exception e) {
            }
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
