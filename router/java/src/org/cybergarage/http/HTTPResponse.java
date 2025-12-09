/******************************************************************
 *
 *	CyberHTTP for Java
 *
 *	Copyright (C) Satoshi Konno 2002-2003
 *
 *	File: HTTPResponse.java
 *
 *	Revision;
 *
 *	11/18/02
 *		- first revision.
 *	10/22/03
 *		- Changed to initialize a content length header.
 *	10/22/04
 *		- Added isSuccessful().
 *
 ******************************************************************/

package org.cybergarage.http;

import org.cybergarage.util.Debug;

import java.io.InputStream;

/**
 * Represents an HTTP response in the CyberLink HTTP framework.
 *
 * <p>This class provides functionality for creating and manipulating HTTP responses:
 *
 * <ul>
 *   <li>Status code management
 *   <li>Header management
 *   <li>Content handling
 *   <li>Response formatting
 * </ul>
 *
 * <p>HTTPResponse is used both for creating server responses and for handling client responses from
 * HTTP requests.
 *
 * @author Satoshi Konno
 * @version 1.0
 * @since 1.0
 */
public class HTTPResponse extends HTTPPacket {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Creates a new HTTP response with default settings. Sets HTTP version to 1.1, content type to
     * HTML, server name, and empty content.
     */
    public HTTPResponse() {
        setVersion(HTTP.VERSION_11);
        setContentType(HTML.CONTENT_TYPE);
        setServer(HTTPServer.getName());
        setContent("");
    }

    /**
     * Creates a copy of an existing HTTP response.
     *
     * @param httpRes HTTP response to copy
     */
    public HTTPResponse(HTTPResponse httpRes) {
        set(httpRes);
    }

    /**
     * Creates an HTTP response by parsing from an input stream.
     *
     * @param in input stream containing the HTTP response
     */
    public HTTPResponse(InputStream in) {
        super(in);
    }

    /**
     * Creates an HTTP response from an HTTP socket.
     *
     * @param httpSock HTTP socket containing the response data
     */
    public HTTPResponse(HTTPSocket httpSock) {
        this(httpSock.getInputStream());
    }

    ////////////////////////////////////////////////
    //	Status Line
    ////////////////////////////////////////////////

    private int statusCode = 0;

    /**
     * Sets the HTTP status code of this response.
     *
     * @param code HTTP status code (e.g., 200, 404, 500)
     */
    public void setStatusCode(int code) {
        statusCode = code;
    }

    /**
     * Gets the HTTP status code of this response.
     *
     * @return HTTP status code, or parsed status code from first line if not explicitly set
     */
    public int getStatusCode() {
        if (statusCode != 0) return statusCode;
        HTTPStatus httpStatus = new HTTPStatus(getFirstLine());
        return httpStatus.getStatusCode();
    }

    /**
     * Checks if this response has a successful status code.
     *
     * @return true if status code is in the 2xx range, false otherwise
     */
    public boolean isSuccessful() {
        return HTTPStatus.isSuccessful(getStatusCode());
    }

    /**
     * Gets the status line string of this HTTP response.
     *
     * @return status line in format "HTTP/version code message"
     */
    public String getStatusLineString() {
        return "HTTP/"
                + getVersion()
                + " "
                + getStatusCode()
                + " "
                + HTTPStatus.code2String(statusCode)
                + HTTP.CRLF;
    }

    ////////////////////////////////////////////////
    //	getHeader
    ////////////////////////////////////////////////

    public String getHeader() {
        StringBuffer str = new StringBuffer();

        str.append(getStatusLineString());
        str.append(getHeaderString());

        return str.toString();
    }

    ////////////////////////////////////////////////
    //	toString
    ////////////////////////////////////////////////

    public String toString() {
        StringBuffer str = new StringBuffer();

        str.append(getStatusLineString());
        str.append(getHeaderString());
        str.append(HTTP.CRLF);
        str.append(getContentString());

        return str.toString();
    }

    public void print() {
        Debug.message(toString());
    }
}
