/*
 * CyberHTTP for Java
 * Copyright (C) Satoshi Konno 2002-2004
 */

package org.cybergarage.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

public class HTTPSocket {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Creates an HTTP socket wrapper around a standard socket.
     *
     * @param socket underlying socket to wrap
     */
    public HTTPSocket(Socket socket) {
        setSocket(socket);
        open();
    }

    /**
     * Creates an HTTP socket wrapper around another HTTP socket.
     *
     * @param socket HTTP socket to wrap
     */
    public HTTPSocket(HTTPSocket socket) {
        setSocket(socket.getSocket());
        setInputStream(socket.getInputStream());
        setOutputStream(socket.getOutputStream());
    }

    ////////////////////////////////////////////////
    //	Socket
    ////////////////////////////////////////////////

    private Socket socket = null;

    private void setSocket(Socket socket) {
        this.socket = socket;
    }

    public Socket getSocket() {
        return socket;
    }

    ////////////////////////////////////////////////
    //	local address/port
    ////////////////////////////////////////////////

    /**
     * Gets the local address of this socket.
     *
     * @return local host address
     */
    public String getLocalAddress() {
        return getSocket().getLocalAddress().getHostAddress();
    }

    /**
     * Gets the local port of this socket.
     *
     * @return local port number
     */
    public int getLocalPort() {
        return getSocket().getLocalPort();
    }

    ////////////////////////////////////////////////
    //	in/out
    ////////////////////////////////////////////////

    private InputStream sockIn = null;
    private OutputStream sockOut = null;

    private void setInputStream(InputStream in) {
        sockIn = in;
    }

    public InputStream getInputStream() {
        return sockIn;
    }

    private void setOutputStream(OutputStream out) {
        sockOut = out;
    }

    private OutputStream getOutputStream() {
        return sockOut;
    }

    ////////////////////////////////////////////////
    //	open/close
    ////////////////////////////////////////////////

    /**
     * Opens the socket streams for input/output operations.
     *
     * @return true if streams were opened successfully, false otherwise
     */
    public boolean open() {
        Socket sock = getSocket();
        try {
            sockIn = sock.getInputStream();
            sockOut = sock.getOutputStream();
        } catch (Exception e) {
            // TODO Add blacklistening of the UPnP Device
            return false;
        }
        return true;
    }

    /**
     * Closes the socket and all associated streams.
     *
     * @return true if closed successfully
     */
    public boolean close() {
        if (sockIn != null)
            try {
                sockIn.close();
            } catch (IOException e) {
            }
        if (sockOut != null)
            try {
                sockOut.close();
            } catch (IOException e) {
            }
        if (socket != null)
            try {
                socket.close();
            } catch (IOException e) {
            }
        return true;
    }

    ////////////////////////////////////////////////
    //	post
    ////////////////////////////////////////////////

    /**
     * Sends an HTTP response using byte array content.
     *
     * <p>This private helper method sends an HTTP response with the specified content. It handles
     * both regular and chunked transfer encoding, sets appropriate headers, and manages the content
     * offset and length for partial content responses.
     *
     * @param httpRes the HTTP response to send
     * @param content the content data as byte array
     * @param contentOffset the starting offset in the content array
     * @param contentLength the length of content to send
     * @param isOnlyHeader if true, only send headers without content body
     * @return true if the response was sent successfully, false otherwise
     */
    private boolean post(
            HTTPResponse httpRes,
            byte content[],
            long contentOffset,
            long contentLength,
            boolean isOnlyHeader) {
        // TODO Check for bad HTTP agents, this method may be list for IOInteruptedException and for
        // blacklistening
        httpRes.setDate(Calendar.getInstance());

        OutputStream out = getOutputStream();

        try {
            httpRes.setContentLength(contentLength);

            out.write(httpRes.getHeader().getBytes(StandardCharsets.UTF_8));
            out.write(HTTP.CRLF.getBytes(StandardCharsets.UTF_8));
            if (isOnlyHeader == true) {
                out.flush();
                return true;
            }

            boolean isChunkedResponse = httpRes.isChunked();

            if (isChunkedResponse == true) {
                // Thanks for Lee Peik Feng <pflee@users.sourceforge.net> (07/07/05)
                String chunSizeBuf = Long.toHexString(contentLength);
                out.write(chunSizeBuf.getBytes(StandardCharsets.UTF_8));
                out.write(HTTP.CRLF.getBytes(StandardCharsets.UTF_8));
            }

            out.write(content, (int) contentOffset, (int) contentLength);

            if (isChunkedResponse == true) {
                out.write(HTTP.CRLF.getBytes(StandardCharsets.UTF_8));
                out.write("0".getBytes(StandardCharsets.UTF_8));
                out.write(HTTP.CRLF.getBytes(StandardCharsets.UTF_8));
            }

            out.flush();
        } catch (Exception e) {
            // Debug.warning(e);
            return false;
        }

        return true;
    }

    /**
     * Sends an HTTP response using input stream content.
     *
     * <p>This private helper method sends an HTTP response with content from an input stream. It
     * handles both regular and chunked transfer encoding, reads content in chunks, and manages the
     * content offset for partial content responses.
     *
     * @param httpRes the HTTP response to send
     * @param in the input stream containing content data
     * @param contentOffset the offset to skip in the input stream before reading
     * @param contentLength the total length of content to send
     * @param isOnlyHeader if true, only send headers without content body
     * @return true if the response was sent successfully, false otherwise
     */
    private boolean post(
            HTTPResponse httpRes,
            InputStream in,
            long contentOffset,
            long contentLength,
            boolean isOnlyHeader) {
        // TODO Check for bad HTTP agents, this method may be list for IOInteruptedException and for
        // blacklistening
        httpRes.setDate(Calendar.getInstance());

        OutputStream out = getOutputStream();

        try {
            httpRes.setContentLength(contentLength);

            out.write(httpRes.getHeader().getBytes(StandardCharsets.UTF_8));
            out.write(HTTP.CRLF.getBytes(StandardCharsets.UTF_8));

            if (isOnlyHeader == true) {
                out.flush();
                return true;
            }

            boolean isChunkedResponse = httpRes.isChunked();

            if (0 < contentOffset) in.skip(contentOffset);

            int chunkSize = HTTP.getChunkSize();
            byte readBuf[] = new byte[chunkSize];
            long readCnt = 0;
            long readSize = (chunkSize < contentLength) ? chunkSize : contentLength;
            int readLen = in.read(readBuf, 0, (int) readSize);
            while (0 < readLen && readCnt < contentLength) {
                if (isChunkedResponse == true) {
                    // Thanks for Lee Peik Feng <pflee@users.sourceforge.net> (07/07/05)
                    String chunSizeBuf = Long.toHexString(readLen);
                    out.write(chunSizeBuf.getBytes(StandardCharsets.UTF_8));
                    out.write(HTTP.CRLF.getBytes(StandardCharsets.UTF_8));
                }
                out.write(readBuf, 0, readLen);
                if (isChunkedResponse == true)
                    out.write(HTTP.CRLF.getBytes(StandardCharsets.UTF_8));
                readCnt += readLen;
                readSize =
                        (chunkSize < (contentLength - readCnt))
                                ? chunkSize
                                : (contentLength - readCnt);
                readLen = in.read(readBuf, 0, (int) readSize);
            }

            if (isChunkedResponse == true) {
                out.write("0".getBytes(StandardCharsets.UTF_8));
                out.write(HTTP.CRLF.getBytes(StandardCharsets.UTF_8));
            }

            out.flush();
        } catch (Exception e) {
            // Debug.warning(e);
            return false;
        }

        return true;
    }

    /**
     * Posts an HTTP response with the specified content parameters.
     *
     * @param httpRes the HTTP response to post
     * @param contentOffset the offset in the content to start from
     * @param contentLength the length of content to send
     * @param isOnlyHeader true if only headers should be sent
     * @return true if posted successfully
     */
    public boolean post(
            HTTPResponse httpRes, long contentOffset, long contentLength, boolean isOnlyHeader) {
        // TODO Close if Connection != keep-alive
        if (httpRes.hasContentInputStream() == true)
            return post(
                    httpRes,
                    httpRes.getContentInputStream(),
                    contentOffset,
                    contentLength,
                    isOnlyHeader);
        return post(httpRes, httpRes.getContent(), contentOffset, contentLength, isOnlyHeader);
    }
}
