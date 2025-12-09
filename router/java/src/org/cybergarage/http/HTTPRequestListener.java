/******************************************************************
 *
 *	CyberHTTP for Java
 *
 *	Copyright (C) Satoshi Konno 2002
 *
 *	File: HTTPRequestListener.java
 *
 *	Revision;
 *
 *	12/13/02
 *		- first revision.
 *
 ******************************************************************/

package org.cybergarage.http;

/**
 * Interface for receiving HTTP request notifications.
 * Classes implementing this interface can handle incoming HTTP requests
 * when registered with an HTTPServer.
 */
public interface HTTPRequestListener {
    /**
     * Called when an HTTP request is received.
     *
     * @param httpReq the received HTTP request
     */
    public void httpRequestRecieved(HTTPRequest httpReq);
}
