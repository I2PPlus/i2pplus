package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Properties;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Base class for SAM protocol handlers.  It implements common
 * methods, but is not able to actually parse the protocol itself:
 * this task is delegated to subclasses.
 *
 */
abstract class SAMHandler implements Runnable, Handler {

    /**
     * _log.
     */
    protected final Log _log;

    /**
     * thread.
     */
    protected I2PAppThread thread;
    /**
     * bridge.
     */
    protected final SAMBridge bridge;

    private final Object socketWLock = new Object(); // Guards writings on socket
    /**
     * socket.
     */
    protected final SocketChannel socket;

    /**
     * verMajor.
     */
    public final int verMajor;
    /**
     * verMinor.
     */
    public final int verMinor;

    /** I2CP options configuring the I2CP connection (port, host, numHops, etc) */
    protected final Properties i2cpProps;

    /**
     * stopLock.
     */
    protected final Object stopLock = new Object();
    /**
     * stopHandler.
     */
    protected boolean stopHandler;

    /**
     * Username this connection authenticated with during the HELLO handshake,
     * or null when the bridge has authentication disabled or the connection
     * was never authenticated. Records created for sub-sessions copy the
     * username of the control connection that created them.
     *
     * @since 0.9.71+
     */
    private volatile String authUser;

    /**
     * Get the username this connection authenticated with, if any.
     *
     * @return the authenticated username, or null if not authenticated
     * @since 0.9.71+
     */
    final String getAuthUser() {
        return authUser;
    }

    /**
     * Record the username this connection authenticated with.
     *
     * @param user the authenticated username, or null
     * @since 0.9.71+
     */
    final void setAuthUser(String user) {
        authUser = user;
    }

    /**
     * Get the remote address and port of this connection, bracketing IPv6,
     * for rejection and ban logging.
     *
     * @return e.g. "127.0.0.1:54321" or "[::1]:1234", "unknown" if unavailable
     * @since 0.9.71+
     */
    final String getRemoteAddrPort() {
        InetAddress addr;
        try {
            addr = socket.socket().getInetAddress();
        } catch (Exception e) {
            return "unknown";
        }
        if (addr == null) {return "unknown";}
        String host = addr.getHostAddress();
        if (host.indexOf(':') >= 0) {host = '[' + host + ']';}
        return host + ':' + socket.socket().getPort();
    }

    /**
     * Get the remote address of this connection.
     *
     * @return the remote IP, or null if unavailable
     * @since 0.9.71+
     */
    private String getRemoteHost() {
        try {
            InetAddress addr = socket.socket().getInetAddress();
            return addr != null ? addr.getHostAddress() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Determine whether two connections belong to the same client, which is
     * what decides whether one of them may use a session nickname owned by
     * the other. Connections that authenticated share their username;
     * otherwise the remote address decides, so a session may be used by a
     * second connection from the same host but not by a host that merely
     * knows the nickname. All loopback addresses count as the same host.
     *
     * @param other the other connection's handler, may be null
     * @return true if this connection may act on the other's sessions
     * @since 0.9.71+
     */
    final boolean sameClient(SAMHandler other) {
        if (other == null || other == this) {return true;}
        return sameClient(authUser, other.authUser, getRemoteHost(), other.getRemoteHost());
    }

    /**
     * Decide whether two SAM connections belong to the same client. When both
     * carry an authenticated user the user decides; otherwise the connections
     * are the same only if they come from the same address, with any loopback
     * address treated as the same host since the platform may report it in
     * several forms.
     *
     * @param myAuthUser authenticated user of this connection, may be null
     * @param otherAuthUser authenticated user of the other connection, may be null
     * @param myHost address of this connection, may be null
     * @param otherHost address of the other connection, may be null
     * @return true if the two connections belong to the same client
     * @since 0.9.71+
     */
    static boolean sameClient(String myAuthUser, String otherAuthUser,
                              String myHost, String otherHost) {
        if (myAuthUser != null && otherAuthUser != null) {return myAuthUser.equals(otherAuthUser);}
        if (myHost == null || otherHost == null) {return false;}
        if (myHost.equals(otherHost)) {return true;}
        return isLoopbackAddress(myHost) && isLoopbackAddress(otherHost);
    }

    /**
     * Determine whether an address is a loopback address, in any of the
     * forms the platform may report it.
     *
     * @param host the address, may be null
     * @return true if it is a loopback address
     * @since 0.9.71+
     */
    static boolean isLoopbackAddress(String host) {
        if (host == null) {return false;}
        if (host.equals("::1") || host.equals("0:0:0:0:0:0:0:1") ||
            host.equals("::ffff:127.0.0.1")) {return true;}
        return host.startsWith("127.");
    }

    /**
     * SAMHandler constructor (to be called by subclasses)
     *
     * @param s Socket attached to a SAM client
     * @param verMajor SAM major version to manage
     * @param verMinor SAM minor version to manage
     * @param i2cpProps properties to configure the I2CP connection (host, port, etc)
     */
    protected SAMHandler(SocketChannel s, int verMajor, int verMinor,
                         Properties i2cpProps, SAMBridge parent) {
        _log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
        socket = s;

        this.verMajor = verMajor;
        this.verMinor = verMinor;
        this.i2cpProps = i2cpProps;
        bridge = parent;
    }

    /**
     * Start handling the SAM connection, detaching a handling thread.
     * Subclasses (e.g. SAMv3Handler) may override to use a shared
     * thread pool instead of a dedicated thread.
     */
    public void startHandling() {
        thread = new I2PAppThread(this, getClass().getSimpleName());
        thread.start();
    }

    /**
     * Actually handle the SAM protocol.
     *
     */
    protected abstract void handle();

    /**
     * Get the channel of the socket connected to the SAM client
     *
     * @return channel
     */
    protected final SocketChannel getClientSocket() {
        return socket;
    }

    /**
     * Write a byte array on the handler's socket.  This method must
     * always be used when writing data, unless you really know what
     * you're doing.
     *
     * @param data A byte array to be written
     * @throws IOException
     */
    protected final void writeBytes(ByteBuffer data) throws IOException {
        synchronized (socketWLock) {
            writeBytes(data, socket);
        }
    }

    /**
     *  Caller must synch.
     *
     * @param data the bytes to write
     * @param out the socket channel to write to
     * @throws IOException on write errors
     */
    private static void writeBytes(ByteBuffer data, SocketChannel out) throws IOException {
        while (data.hasRemaining()) out.write(data);
        // codeql[java/unsafe-cert-trust] flush of the authenticated TLS socket stream; not a trust decision
        out.socket().getOutputStream().flush();
    }

    /**
     * If you're crazy enough to write to the raw socket, grab the write lock
     * with getWriteLock(), synchronize against it, and write to the getOut()
     *
     * @return socket Write lock object
     */
    protected Object getWriteLock() { return socketWLock; }

    /**
     * Write a string to the handler's socket.  This method must
     * always be used when writing strings, unless you really know what
     * you're doing.
     *
     * @param str A string to be written
     *
     * @return True if the string was successfully written, false otherwise
     */
    protected final boolean writeString(String str) {
        synchronized (socketWLock) {
            if (_log.shouldInfo())
                _log.info("Sending client: [" + str + "]");
            return writeString(str, socket);
        }
    }

    /**
     * Unsynchronized, use with caution.
     *
     * @param str the string to write
     * @param out the socket channel to write to
     * @return true on success
     */
    public static boolean writeString(String str, SocketChannel out) {
        try {
            writeBytes(ByteBuffer.wrap(DataHelper.getUTF8(str)), out);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /**
     * Close the socket connected to the SAM client.
     *
     * @throws IOException
     */
    protected final void closeClientSocket() throws IOException {
            socket.close();
    }

    /**
     * Stop the SAM handler, close the client socket,
     * unregister with the bridge.
     */
    public void stopHandling() {
        if (_log.shouldWarn())
            _log.warn("Stopping SAM client: " + this);
        synchronized (stopLock) {
            stopHandler = true;
        }
        try {
            closeClientSocket();
        } catch (IOException e) { /* ignored */ }
        bridge.unregister(this);
    }

    /**
     * Should the handler be stopped?
     *
     * @return True if the handler should be stopped, false otherwise
     */
    protected final boolean shouldStop() {
        synchronized (stopLock) {
            return stopHandler;
        }
    }

    /**
     * Get a string describing the handler.
     *
     * @return A String describing the handler;
     */
    @Override
    public final String toString() {
        return (this.getClass().getSimpleName()
                + " [Version " + verMajor + "." + verMinor
                + " - Client: "
                + this.socket.socket().getInetAddress().getHostAddress() + ":"
                + this.socket.socket().getPort() + "]");
    }

    /**
     * Register with the bridge, call handle(),
     * unregister with the bridge.
     */
    public final void run() {
        bridge.register(this);
        try {
            handle();
        } finally {
            bridge.unregister(this);
        }
    }
}
