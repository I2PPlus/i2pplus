package net.i2p.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PipedOutputStream;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A simple in-JVM ServerSocket using Piped Streams.
 * We use port numbers just like regular sockets.
 * Can only be connected by {@link InternalSocket}.
 * <p>
 * Warning - this uses Piped Streams, which don't like multiple writers from threads
 * that may vanish. If you do use multiple writers,
 * you may get intermittent 'write end dead' or 'pipe broken' IOExceptions on the reader side.
 * See http://techtavern.wordpress.com/2008/07/16/whats-this-ioexception-write-end-dead/
 *
 * @since 0.7.9
 */
@SuppressWarnings("PMD.CloseResource")
public class InternalServerSocket extends ServerSocket {
    /** The open sockets, by port. */
    private static final ConcurrentHashMap<Integer, InternalServerSocket> _sockets =
            new ConcurrentHashMap<>(4);
    /** Connections waiting to be accepted. */
    private final BlockingQueue<InternalSocket> _acceptQueue;
    /** The port being listened on. */
    private final Integer _port;
    /** Name shown in the logs for this socket. */
    private final String _label;
    /** False once the socket is closed. */
    private volatile boolean _running;

    /**
     * Claim an internal port for this router. The claim is tracked in a static map,
     * so the same port cannot be taken twice even across routers in one JVM.
     *
     * @param port &gt; 0
     * @throws IOException if the port is not positive, or another server socket already holds it
     */
    public InternalServerSocket(int port) throws IOException {
        this(port, null);
    }

    /**
     * Claim an internal port, naming the service that holds it so debug views
     * can show what listens there instead of a bare port number.
     *
     * @param port &gt; 0
     * @param label human-readable service name, or null for none
     * @throws IOException if the port is not positive, or another server socket already holds it
     * @since 0.9.71
     */
    public InternalServerSocket(int port, String label) throws IOException {
        if (port <= 0) throw new IOException("Bad port: " + port);
        _port = Integer.valueOf(port);
        _label = label;
        InternalServerSocket previous = _sockets.putIfAbsent(_port, this);
        if (previous != null) throw new IOException("Internal port in use: " + port);
        _running = true;
        _acceptQueue = new LinkedBlockingQueue<>();
    }

    @Override
    public void close() {
        _running = false;
        _sockets.remove(_port);
        _acceptQueue.clear();
        try {
            // use null streams as a poison
            _acceptQueue.put(new InternalSocket(null, null));
        } catch (InterruptedException ie) { /* ignored */ }
    }

    @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
    @Override
    public Socket accept() throws IOException {
        InternalSocket serverSock = null;
        while (_running) {
            try {
                serverSock = _acceptQueue.take();
            } catch (InterruptedException ie) {
                if (_running) throw new InterruptedIOException();
                throw new IOException("closed");
            }
            if (serverSock.getInputStream() == null) {// poison
                throw new IOException("closed");
            }
            break;
        }
        return serverSock;
    }

    @Override
    public String toString() {
        return ("Internal server socket on port " + _port);
    }

    /**
     * This is how the client connects.
     *
     * @param port &gt; 0
     * @param clientSock the client socket to wire the paired piped streams onto
     * @throws IOException if no server socket is listening on that port, or it has been closed
     */
    static void internalConnect(int port, InternalSocket clientSock) throws IOException {
        InternalServerSocket iss = _sockets.get(Integer.valueOf(port));
        if (iss == null) throw new IOException("No server for port: " + port);
        TimeoutPipedInputStream cis = new TimeoutPipedInputStream(64 * 1024);
        TimeoutPipedInputStream sis = new TimeoutPipedInputStream(64 * 1024);
        PipedOutputStream cos = new TimeoutPipedOutputStream(sis);
        PipedOutputStream sos = new TimeoutPipedOutputStream(cis);
        clientSock.setInputStream(cis);
        clientSock.setOutputStream(cos);
        iss.queueConnection(new InternalSocket(sis, sos));
    }

    /**
     * Hands a new connection to the accept queue.
     *
     * @param sock the connection to queue
     * @throws IOException if the socket is closed
     */
    private void queueConnection(InternalSocket sock) throws IOException {
        if (!_running) throw new IOException("Server closed for port: " + _port);
        try {
            _acceptQueue.put(sock);
        } catch (InterruptedException ie) { /* ignored */ }
    }

    @Override
    public int getLocalPort() {
        return _port.intValue();
    }

    /** Unsupported, ignored. */
    @Override
    public void setSoTimeout(int timeout) {
        // TODO
    }
    @Override
    public int getSoTimeout() {
        return 0;
    }

    /**
     * Returns null as of 0.9.33, prior to that threw IllegalArgumentException.
     * @return null, as this socket does not wrap a channel
     */
    @Override
    public ServerSocketChannel getChannel() {
        return null;
    }

    /**
     * Returns true as of 0.9.33, prior to that threw IllegalArgumentException.
     * @return always true, as the socket is listening from construction
     */
    @Override
    public boolean isBound() {
        return true;
    }

    /**
     * Supported as of 0.9.33, prior to that threw IllegalArgumentException.
     * @return true once {@link #close} has run, false while still listening
     */
    @Override
    public boolean isClosed() {
        return !_running;
    }

    /**
     * For debugging only.
     *
     * @param out the writer to render the table of open ports into
     * @throws IOException if the writer rejects the HTML fragment
     * @since 0.9.33
     */
    /**
     * The service name given at construction, if any.
     *
     * @return the label, or null for an unlabelled socket
     * @since 0.9.71
     */
    public String getLabel() {return _label;}

    /**
     * All currently claimed internal ports with their optional service labels.
     *
     * @return a copy of the map of port to label (null value for unlabelled sockets), never null
     * @since 0.9.71
     */
    public static Map<Integer, String> getPortLabels() {
        Map<Integer, String> rv = new HashMap<>(_sockets.size());
        for (Map.Entry<Integer, InternalServerSocket> e : _sockets.entrySet()) {
            rv.put(e.getKey(), e.getValue()._label);
        }
        return rv;
    }

    /**
     * Table of the ports currently claimed by internal server sockets.
     *
     * @param out writer to write HTML output to
     * @throws IOException if writing to output fails
     */
    public static void renderStatusHTML(Writer out) throws IOException {
        out.write("<h2 id=debug_sockets>Internal Server Sockets</h2>\n<table id=sockets>\n");
        List<Integer> ports = new ArrayList<>(_sockets.keySet());
        if (!ports.isEmpty()) {
            out.write("<tr><th>Port\n");
            Collections.sort(ports);
            for (Integer i : ports) {
                out.write("<tr><td>" + i + '\n');
            }
        } else {
            out.write("<tr><td><i>none</i>\n");
        }
        out.write("</table>\n");
    }
}
