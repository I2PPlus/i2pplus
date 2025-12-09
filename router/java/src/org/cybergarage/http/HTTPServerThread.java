/******************************************************************
 *
 *	CyberHTTP for Java
 *
 *	Copyright (C) Satoshi Konno 2002-2003
 *
 *	File: HTTPServerThread.java
 *
 *	Revision;
 *
 *	10/10/03
 *		- first revision.
 *
 ******************************************************************/

package org.cybergarage.http;

import java.net.Socket;

/**
 * A thread that handles HTTP requests for an HTTPServer.
 * Each thread manages a single client connection, reading requests
 * and delegating them to the server's request listeners.
 */
public class HTTPServerThread extends Thread {
    private HTTPServer httpServer;
    private Socket sock;

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Creates a new HTTPServerThread to handle a client connection.
     *
     * @param httpServer the HTTPServer that will handle the requests
     * @param sock the client socket
     */
    public HTTPServerThread(HTTPServer httpServer, Socket sock) {
        super("Cyber.HTTPServerThread");
        this.httpServer = httpServer;
        this.sock = sock;
    }

    ////////////////////////////////////////////////
    //	run
    ////////////////////////////////////////////////

    /**
     * Runs the thread to handle HTTP requests from the client.
     * Reads requests continuously until the connection is closed
     * or keep-alive is not requested.
     */
    public void run() {
        HTTPSocket httpSock = new HTTPSocket(sock);
        try {
            if (httpSock.open() == false) return;
            HTTPRequest httpReq = new HTTPRequest();
            httpReq.setSocket(httpSock);
            while (httpReq.read() == true) {
                httpServer.performRequestListener(httpReq);
                if (httpReq.isKeepAlive() == false) break;
            }
        } finally {
            httpSock.close();
        }
    }
}
