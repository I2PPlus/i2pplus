/*
 * CyberHTTP for Java
 * Copyright (C) Satoshi Konno 2002-2004
 */

package org.cybergarage.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.StringTokenizer;
import org.cybergarage.util.Debug;

/** HTTP request message with method, URI, headers, and content body. */
public class HTTPRequest extends HTTPPacket {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public HTTPRequest() {
        setVersion(HTTP.VERSION_10);
    }

    /** Parses an HTTP request from the given input stream. */
    public HTTPRequest(InputStream in) {
        super(in);
    }

    /** Creates an HTTP request from the given socket's input stream. */
    public HTTPRequest(HTTPSocket httpSock) {
        this(httpSock.getInputStream());
        setSocket(httpSock);
    }

    ////////////////////////////////////////////////
    //	Method
    ////////////////////////////////////////////////

    private String method = null;

    /**
     * Sets the HTTP method (e.g., GET, POST).
     *
     * @param value the HTTP method string
     */
    public void setMethod(String value) {
        method = value;
    }

    /**
     * Returns the HTTP method.
     *
     * @return the HTTP method string, or null if not set
     */
    public String getMethod() {
        if (method != null) return method;
        return getFirstLineToken(0);
    }

    /**
     * Checks if the HTTP method matches the given string.
     *
     * @param method the method string to compare
     * @return true if the methods match (case-insensitive), false otherwise
     */
    public boolean isMethod(String method) {
        String headerMethod = getMethod();
        if (headerMethod == null) return false;
        return headerMethod.equalsIgnoreCase(method);
    }

    /**
     * Determines if this is a GET request.
     *
     * @return true if the method is GET, false otherwise
     */
    public boolean isGetRequest() {
        return isMethod(HTTP.GET);
    }

    /**
     * Determines if this is a POST request.
     *
     * @return true if the method is POST, false otherwise
     */
    public boolean isPostRequest() {
        return isMethod(HTTP.POST);
    }

    /**
     * Determines if this is a HEAD request.
     *
     * @return true if the method is HEAD, false otherwise
     */
    public boolean isHeadRequest() {
        return isMethod(HTTP.HEAD);
    }

    /**
     * Determines if this is a SUBSCRIBE request.
     *
     * @return true if the method is SUBSCRIBE, false otherwise
     */
    public boolean isSubscribeRequest() {
        return isMethod(HTTP.SUBSCRIBE);
    }

    /**
     * Determines if this is an UNSUBSCRIBE request.
     *
     * @return true if the method is UNSUBSCRIBE, false otherwise
     */
    public boolean isUnsubscribeRequest() {
        return isMethod(HTTP.UNSUBSCRIBE);
    }

    /**
     * Determines if this is a NOTIFY request.
     *
     * @return true if the method is NOTIFY, false otherwise
     */
    public boolean isNotifyRequest() {
        return isMethod(HTTP.NOTIFY);
    }

    ////////////////////////////////////////////////
    //	URI
    ////////////////////////////////////////////////

    private String uri = null;

    /**
     * Sets the URI for this HTTP request.
     *
     * <p>Sets the request URI and optionally converts it to a relative URL. This is used to handle
     * both absolute and relative URLs in HTTP requests.
     *
     * @param value the URI string to set
     * @param isCheckRelativeURL if true, converts the URI to a relative URL using
     *     HTTP.toRelativeURL()
     */
    public void setURI(String value, boolean isCheckRelativeURL) {
        uri = value;
        if (isCheckRelativeURL == false) return;
        // Thanks for Giordano Sassaroli <sassarol@cefriel.it> (09/02/03)
        uri = HTTP.toRelativeURL(uri);
    }

    /**
     * Sets the URI for this HTTP request without relative URL conversion.
     *
     * <p>This is a convenience method that calls {@link #setURI(String, boolean)} with
     * isCheckRelativeURL set to false.
     *
     * @param value the URI string to set
     */
    public void setURI(String value) {
        setURI(value, false);
    }

    /**
     * Returns the request URI.
     *
     * @return the URI string, or null if not set
     */
    public String getURI() {
        if (uri != null) return uri;
        return getFirstLineToken(1);
    }

    ////////////////////////////////////////////////
    //	URI Parameter
    ////////////////////////////////////////////////

    /**
     * Parses the URI query string and returns the parameters as a ParameterList.
     *
     * @return the ParameterList containing all query parameters, or an empty list if none exist
     */
    public ParameterList getParameterList() {
        ParameterList paramList = new ParameterList();
        String uri = getURI();
        if (uri == null) return paramList;
        int paramIdx = uri.indexOf('?');
        if (paramIdx < 0) return paramList;
        while (0 < paramIdx) {
            int eqIdx = uri.indexOf('=', (paramIdx + 1));
            String name = uri.substring(paramIdx + 1, eqIdx);
            int nextParamIdx = uri.indexOf('&', (eqIdx + 1));
            String value =
                    uri.substring(eqIdx + 1, (0 < nextParamIdx) ? nextParamIdx : uri.length());
            Parameter param = new Parameter(name, value);
            paramList.add(param);
            paramIdx = nextParamIdx;
        }
        return paramList;
    }

    /**
     * Returns the value of the specified URI query parameter.
     *
     * @param name the parameter name
     * @return the parameter value, or null if not found
     */
    public String getParameterValue(String name) {
        ParameterList paramList = getParameterList();
        return paramList.getValue(name);
    }

    ////////////////////////////////////////////////
    //	SOAPAction
    ////////////////////////////////////////////////

    /**
     * Checks if this request has a SOAPAction header.
     *
     * @return true if the SOAPAction header is present, false otherwise
     */
    public boolean isSOAPAction() {
        return hasHeader(HTTP.SOAP_ACTION);
    }

    ////////////////////////////////////////////////
    // Host / Port
    ////////////////////////////////////////////////

    private String requestHost = "";

    /**
     * Sets the host for this request.
     *
     * @param host the host string
     */
    public void setRequestHost(String host) {
        requestHost = host;
    }

    /**
     * Returns the request host.
     *
     * @return the host string
     */
    public String getRequestHost() {
        return requestHost;
    }

    private int requestPort = -1;

    /**
     * Sets the port for this request.
     *
     * @param host the port number
     */
    public void setRequestPort(int host) {
        requestPort = host;
    }

    /**
     * Returns the request port.
     *
     * @return the port number
     */
    public int getRequestPort() {
        return requestPort;
    }

    ////////////////////////////////////////////////
    //	Socket
    ////////////////////////////////////////////////

    private HTTPSocket httpSocket = null;

    /**
     * Sets the HTTPSocket for this request.
     *
     * @param value the HTTPSocket to set
     */
    public void setSocket(HTTPSocket value) {
        httpSocket = value;
    }

    /**
     * Returns the HTTPSocket for this request.
     *
     * @return the HTTPSocket, or null if not set
     */
    public HTTPSocket getSocket() {
        return httpSocket;
    }

    /////////////////////////// /////////////////////
    //	local address/port
    ////////////////////////////////////////////////

    /**
     * Returns the local address of the underlying socket.
     *
     * @return the local address string
     */
    public String getLocalAddress() {
        return getSocket().getLocalAddress();
    }

    /**
     * Returns the local port of the underlying socket.
     *
     * @return the local port number
     */
    public int getLocalPort() {
        return getSocket().getLocalPort();
    }

    ////////////////////////////////////////////////
    //	parseRequest
    ////////////////////////////////////////////////

    /**
     * Parses an HTTP request line and extracts method, URI, and version.
     *
     * <p>This method parses a request line in the format "METHOD URI VERSION" (e.g., "GET /path
     * HTTP/1.1") and sets the corresponding properties on this HTTP request object.
     *
     * @param lineStr the request line string to parse
     * @return true if parsing was successful and all three components were found, false otherwise
     */
    public boolean parseRequestLine(String lineStr) {
        StringTokenizer st = new StringTokenizer(lineStr, HTTP.REQEST_LINE_DELIM);
        if (st.hasMoreTokens() == false) return false;
        setMethod(st.nextToken());
        if (st.hasMoreTokens() == false) return false;
        setURI(st.nextToken());
        if (st.hasMoreTokens() == false) return false;
        setVersion(st.nextToken());
        return true;
    }

    ////////////////////////////////////////////////
    //	First Line
    ////////////////////////////////////////////////

    /**
     * Returns the HTTP version string for this request.
     *
     * @return the HTTP version (e.g., "HTTP/1.1")
     */
    public String getHTTPVersion() {
        if (hasFirstLine() == true) return getFirstLineToken(2);
        return "HTTP/" + super.getVersion();
    }

    /**
     * Returns the first line of the HTTP request (method, URI, and version).
     *
     * @return the request line string
     */
    public String getFirstLineString() {
        return getMethod() + " " + getURI() + " " + getHTTPVersion() + HTTP.CRLF;
    }

    ////////////////////////////////////////////////
    //	getHeader
    ////////////////////////////////////////////////

    /**
     * Returns the complete HTTP header as a string.
     *
     * @return the header string including the request line and headers
     */
    public String getHeader() {
        StringBuffer str = new StringBuffer();

        str.append(getFirstLineString());

        String headerString = getHeaderString();
        str.append(headerString);

        return str.toString();
    }

    ////////////////////////////////////////////////
    //	isKeepAlive
    ////////////////////////////////////////////////

    /**
     * Determines if this connection should be kept alive.
     *
     * @return true if keep-alive is enabled, false otherwise
     */
    public boolean isKeepAlive() {
        if (isCloseConnection() == true) return false;
        if (isKeepAliveConnection() == true) return true;
        String httpVer = getHTTPVersion();
        boolean isHTTP10 = (0 < httpVer.indexOf("1.0")) ? true : false;
        if (isHTTP10 == true) return false;
        return true;
    }

    ////////////////////////////////////////////////
    //	read
    ////////////////////////////////////////////////

    /**
     * Reads the HTTP request from the underlying socket.
     *
     * @return true if the request was read successfully, false otherwise
     */
    public boolean read() {
        return super.read(getSocket());
    }

    ////////////////////////////////////////////////
    //	POST (Response)
    ////////////////////////////////////////////////

    /**
     * Posts an HTTP response back to the client.
     *
     * @param httpRes the HTTP response to send
     * @return true if the response was sent successfully, false otherwise
     */
    public boolean post(HTTPResponse httpRes) {
        HTTPSocket httpSock = getSocket();
        long offset = 0;
        long length = httpRes.getContentLength();
        if (hasContentRange() == true) {
            long firstPos = getContentRangeFirstPosition();
            long lastPos = getContentRangeLastPosition();

            // Thanks for Brent Hills (10/26/04)
            if (lastPos <= 0) lastPos = length - 1;
            if ((firstPos > length) || (lastPos > length))
                return returnResponse(HTTPStatus.INVALID_RANGE);
            httpRes.setContentRange(firstPos, lastPos, length);
            httpRes.setStatusCode(HTTPStatus.PARTIAL_CONTENT);

            offset = firstPos;
            length = lastPos - firstPos + 1;
        }
        return httpSock.post(httpRes, offset, length, isHeadRequest());
        // httpSock.close();
    }

    ////////////////////////////////////////////////
    //	POST (Request)
    ////////////////////////////////////////////////

    private Socket postSocket = null;

    private String bindTo = null;

    /**
     * I2P - bind HTTP socket to specified local host address
     *
     * @param host null to not bind to a particlar local address
     * @since 0.9.50
     */
    public void setBindHost(String host) {
        bindTo = host;
    }

    /**
     * Posts this HTTP request to the specified host and port.
     *
     * @param host the target host
     * @param port the target port
     * @param isKeepAlive whether to keep the connection alive
     * @return the HTTP response
     */
    public HTTPResponse post(String host, int port, boolean isKeepAlive) {
        HTTPResponse httpRes = new HTTPResponse();

        setHost(host, port);

        setConnection((isKeepAlive == true) ? HTTP.KEEP_ALIVE : HTTP.CLOSE);

        boolean isHeaderRequest = isHeadRequest();

        OutputStream out = null;
        InputStream in = null;

        try {
            if (postSocket == null) {
                // Mod for I2P
                // We can't handle the default system soTimeout of 3 minutes or so
                // as when the device goes away, this hangs the display of peers.jsp
                // and who knows what else.
                // Set the timeout to be nice and short, the device should be local and fast.
                // Yeah, the UPnP standard is a minute or something, too bad.
                // If he can't get back to us in a few seconds, forget it.
                // And set the soTimeout to 2 second (for reads).
                // postSocket = new Socket(host, port);
                postSocket = new Socket();
                if (bindTo != null) {
                    boolean fromv6 = bindTo.contains(":");
                    boolean tov6 = host.contains(":");
                    if (fromv6 == tov6) {
                        // Debug.warning("POST bindTo " + bindTo + " connect to " + host);
                        postSocket.bind(new InetSocketAddress(bindTo, 0));
                    } else {
                        Debug.warning(
                                "POST mismatch, NOT binding to " + bindTo + " connect to " + host);
                    }
                }
                postSocket.setSoTimeout(2000);
                SocketAddress sa = new InetSocketAddress(host, port);
                postSocket.connect(sa, 3000);
            }

            out = postSocket.getOutputStream();
            PrintStream pout = new PrintStream(out, true, "UTF-8");
            pout.print(getHeader());
            pout.print(HTTP.CRLF);

            boolean isChunkedRequest = isChunked();

            String content = getContentString();
            int contentLength = 0;
            if (content != null) contentLength = content.length();

            if (0 < contentLength) {
                if (isChunkedRequest == true) {
                    // Thanks for Lee Peik Feng <pflee@users.sourceforge.net> (07/07/05)
                    String chunSizeBuf = Long.toHexString(contentLength);
                    pout.print(chunSizeBuf);
                    pout.print(HTTP.CRLF);
                }
                pout.print(content);
                if (isChunkedRequest == true) pout.print(HTTP.CRLF);
            }

            if (isChunkedRequest == true) {
                pout.print("0");
                pout.print(HTTP.CRLF);
            }

            pout.flush();

            in = postSocket.getInputStream();
            httpRes.set(in, isHeaderRequest);
        } catch (SocketException e) {
            httpRes.setStatusCode(HTTPStatus.INTERNAL_SERVER_ERROR);
            Debug.warning(e);
        } catch (IOException e) {
            // Socket create but without connection
            // TODO Blacklistening the device
            httpRes.setStatusCode(HTTPStatus.INTERNAL_SERVER_ERROR);
            Debug.warning(e);
        } finally {
            if (isKeepAlive == false) {
                try {
                    in.close();
                } catch (Exception e) {
                    // Ignore close exceptions
                }
                if (in != null)
                    try {
                        out.close();
                    } catch (Exception e) {
                        // Ignore close exceptions
                    }
                if (out != null)
                    try {
                        postSocket.close();
                    } catch (Exception e) {
                        // Ignore close exceptions
                    }
                postSocket = null;
            }
        }

        return httpRes;
    }

    /**
     * Posts this HTTP request to the specified host and port with no keep-alive.
     *
     * @param host the target host
     * @param port the target port
     * @return the HTTP response
     */
    public HTTPResponse post(String host, int port) {
        return post(host, port, false);
    }

    ////////////////////////////////////////////////
    //	set
    ////////////////////////////////////////////////

    /**
     * Copies the contents of the given HTTP request into this request.
     *
     * @param httpReq the HTTP request to copy from
     */
    public void set(HTTPRequest httpReq) {
        set((HTTPPacket) httpReq);
        setSocket(httpReq.getSocket());
    }

    ////////////////////////////////////////////////
    //	OK/BAD_REQUEST
    ////////////////////////////////////////////////

    /**
     * Sends an HTTP response with the specified status code and no content.
     *
     * <p>This method creates a new HTTP response with the given status code, sets content length to
     * 0, and sends it back to the client using the post() method. This is commonly used for error
     * responses or responses that don't require a body.
     *
     * @param statusCode the HTTP status code to send (e.g., 200, 404, 500)
     * @return true if the response was sent successfully, false otherwise
     */
    public boolean returnResponse(int statusCode) {
        HTTPResponse httpRes = new HTTPResponse();
        httpRes.setStatusCode(statusCode);
        httpRes.setContentLength(0);
        return post(httpRes);
    }

    /**
     * Sends an HTTP 200 OK response with no content.
     *
     * <p>This is a convenience method that sends a 200 OK status code response, typically used to
     * indicate successful request processing when no response body is needed.
     *
     * @return true if the response was sent successfully, false otherwise
     */
    public boolean returnOK() {
        return returnResponse(HTTPStatus.OK);
    }

    /**
     * Sends an HTTP 400 Bad Request response with no content.
     *
     * <p>This is a convenience method that sends a 400 Bad Request status code response, typically
     * used when the client request is malformed or cannot be understood by the server.
     *
     * @return true if the response was sent successfully, false otherwise
     */
    public boolean returnBadRequest() {
        return returnResponse(HTTPStatus.BAD_REQUEST);
    }

    ////////////////////////////////////////////////
    //	toString
    ////////////////////////////////////////////////

    public String toString() {
        StringBuffer str = new StringBuffer();

        str.append(getHeader());
        str.append(HTTP.CRLF);
        str.append(getContentString());

        return str.toString();
    }

    /**
     * Prints the HTTP request to the debug message stream.
     *
     * <p>This method outputs the complete HTTP request including headers and content to the debug
     * logging system using Debug.message(). Useful for debugging HTTP request processing.
     */
    public void print() {
        Debug.message(toString());
    }
}
